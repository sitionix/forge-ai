from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Iterable, Optional

from knowledge_service.observability import observed_connect


SEMANTIC_BUILDER_VERSION = 1
SEMANTIC_ELIGIBLE_NODE_KINDS = ("FILE", "TYPE", "CALLABLE", "EXTERNAL")
SQLITE_SEMANTIC_BUSY_TIMEOUT_MS = 5000
SEMANTIC_INVENTORY_MEMBERSHIP_NODE_FILTER_SQL = """
  AND EXISTS (
    SELECT 1
    FROM analysis_files af_current
    WHERE af_current.source_id = n.source_id
      AND af_current.relative_path = n.relative_path
      AND af_current.content_hash = n.content_hash
  )
  AND EXISTS (
    SELECT 1
    FROM files f_current
    WHERE f_current.source_id = n.source_id
      AND f_current.relative_path = n.relative_path
      AND f_current.content_hash = n.content_hash
  )
"""


class SemanticIndexStatus(str, Enum):
    MISSING = "MISSING"
    PENDING = "PENDING"
    BUILDING = "BUILDING"
    READY = "READY"
    FAILED = "FAILED"
    STALE = "STALE"


@dataclass(frozen=True)
class SemanticGraphInfo:
    source_id: str
    graph_id: Optional[str]
    graph_revision: Optional[str]
    total_node_count: int


@dataclass(frozen=True)
class SemanticIndexStatusView:
    source_id: str
    status: SemanticIndexStatus
    graph_revision: Optional[str]
    builder_version: int
    total_node_count: int
    indexed_node_count: int
    embedding_model: Optional[str]
    embedding_dimension: Optional[int]
    updated_at: Optional[str]
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    last_build_id: Optional[str] = None
    last_error: Optional[str] = None

    @property
    def progress_percent(self) -> float:
        if self.total_node_count <= 0:
            return 0.0
        indexed = max(0, min(self.indexed_node_count, self.total_node_count))
        return round((indexed / self.total_node_count) * 100.0, 1)

    @property
    def ready(self) -> bool:
        return (
            self.status == SemanticIndexStatus.READY
            and self.total_node_count > 0
            and self.indexed_node_count >= self.total_node_count
        )

    @property
    def stale(self) -> bool:
        return self.status == SemanticIndexStatus.STALE

    def to_dict(self) -> dict[str, Any]:
        indexed_node_count = max(0, min(self.indexed_node_count, self.total_node_count))
        return {
            "status": self.status.value,
            "graphRevision": self.graph_revision,
            "builderVersion": self.builder_version,
            "totalNodeCount": self.total_node_count,
            "indexedNodeCount": indexed_node_count,
            "progressPercent": self.progress_percent,
            "totalFactCount": self.total_node_count,
            "indexedFactCount": indexed_node_count,
            "percentOfFacts": self.progress_percent,
            "ready": self.ready,
            "stale": self.stale,
            "embeddingModel": self.embedding_model,
            "embeddingDimension": self.embedding_dimension,
            "updatedAt": self.updated_at,
            "startedAt": self.started_at,
            "completedAt": self.completed_at,
            "lastBuildId": self.last_build_id,
            "lastError": self.last_error,
        }


def ensure_semantic_index_schema(conn: sqlite3.Connection) -> None:
    if _table_exists(conn, "semantic_documents"):
        columns = _table_columns(conn, "semantic_documents")
        legacy_claim_ids = "claim_ids" + "_json"
        legacy_evidence_ids = "evidence_ids" + "_json"
        if "graph_revision" in columns or "graph_id" not in columns or legacy_claim_ids in columns or legacy_evidence_ids in columns:
            conn.execute("DROP TABLE IF EXISTS semantic_vectors")
            conn.execute("DROP TABLE IF EXISTS semantic_documents")
    if _table_exists(conn, "semantic_vectors"):
        columns = _table_columns(conn, "semantic_vectors")
        legacy_vector_payload = "vector" + "_blob"
        if "graph_revision" in columns or "graph_id" not in columns or legacy_vector_payload in columns:
            conn.execute("DROP TABLE IF EXISTS semantic_vectors")
            conn.execute("DROP TABLE IF EXISTS semantic_documents")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS semantic_index_state (
            source_id TEXT NOT NULL,
            graph_revision TEXT NOT NULL,
            status TEXT NOT NULL,
            builder_version INTEGER NOT NULL,
            embedding_model TEXT,
            embedding_dimension INTEGER,
            total_node_count INTEGER NOT NULL DEFAULT 0,
            indexed_node_count INTEGER NOT NULL DEFAULT 0,
            last_build_id TEXT,
            last_error TEXT,
            diagnostics_json TEXT NOT NULL DEFAULT '[]',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            started_at TEXT,
            completed_at TEXT,
            PRIMARY KEY (source_id)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS semantic_documents (
            document_id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            node_id TEXT NOT NULL,
            node_kind TEXT NOT NULL,
            graph_id TEXT NOT NULL,
            document_type TEXT NOT NULL,
            builder_version INTEGER NOT NULL,
            text_hash TEXT NOT NULL,
            text TEXT NOT NULL,
            claim_ids_payload TEXT NOT NULL DEFAULT '[]',
            evidence_ids_payload TEXT NOT NULL DEFAULT '[]',
            status TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS semantic_vectors (
            document_id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            node_id TEXT NOT NULL,
            graph_id TEXT NOT NULL,
            embedding_model TEXT NOT NULL,
            embedding_dimension INTEGER NOT NULL,
            vector_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(document_id) REFERENCES semantic_documents(document_id) ON DELETE CASCADE
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_semantic_index_state_status ON semantic_index_state(status)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_semantic_index_state_revision ON semantic_index_state(graph_revision)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_semantic_documents_source_graph ON semantic_documents(source_id, graph_id, status)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_semantic_documents_node ON semantic_documents(source_id, node_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_semantic_vectors_source_graph ON semantic_vectors(source_id, graph_id, embedding_model)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_semantic_vectors_node ON semantic_vectors(source_id, node_id)")
    conn.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS idx_semantic_documents_unique_graph
        ON semantic_documents(source_id, node_id, graph_id, builder_version)
        """
    )
    if _table_exists(conn, "analysis_files") and _table_exists(conn, "analysis_graph_nodes"):
        conn.execute(
            """
            CREATE TRIGGER IF NOT EXISTS trg_analysis_file_delete_current_graph
            AFTER DELETE ON analysis_files
            BEGIN
                DELETE FROM analysis_graph_nodes
                WHERE source_id = OLD.source_id
                  AND analysis_file_id = OLD.file_id;
                DELETE FROM analysis_graph_evidence
                WHERE source_id = OLD.source_id
                  AND analysis_file_id = OLD.file_id;
                DELETE FROM analysis_graph_diagnostics
                WHERE source_id = OLD.source_id
                  AND analysis_file_id = OLD.file_id;
            END
            """
        )


class SemanticIndexStore:
    def __init__(self, db_path: Path):
        self.db_path = db_path

    def init(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            ensure_semantic_index_schema(conn)

    def get_state(self, source_id: str) -> Optional[dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            row = self.get_state_conn(conn, source_id)
            return _row_to_dict(row) if row is not None else None

    def list_states(self) -> list[dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            rows = conn.execute("SELECT * FROM semantic_index_state ORDER BY source_id").fetchall()
            return [_row_to_dict(row) for row in rows]

    def mark_source_pending(
        self,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.mark_source_pending_conn(conn, source_id, graph_revision, total_node_count, builder_version=builder_version)

    def mark_current_graph_pending(self, source_id: str) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.mark_current_graph_pending_conn(conn, source_id)

    def mark_source_stale(
        self,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.mark_source_stale_conn(conn, source_id, graph_revision, total_node_count, builder_version=builder_version)

    def mark_source_building(
        self,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        indexed_node_count: int = 0,
        build_id: Optional[str] = None,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.mark_source_building_conn(
                conn,
                source_id,
                graph_revision,
                total_node_count,
                indexed_node_count=indexed_node_count,
                build_id=build_id,
                builder_version=builder_version,
            )

    def mark_source_ready(
        self,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        indexed_node_count: int,
        *,
        embedding_model: str,
        embedding_dimension: int,
        build_id: Optional[str] = None,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.mark_source_ready_conn(
                conn,
                source_id,
                graph_revision,
                total_node_count,
                indexed_node_count,
                embedding_model=embedding_model,
                embedding_dimension=embedding_dimension,
                build_id=build_id,
                builder_version=builder_version,
            )

    def mark_source_failed(
        self,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        error: str,
        diagnostics: Optional[list[dict[str, Any]]] = None,
        build_id: Optional[str] = None,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.mark_source_failed_conn(
                conn,
                source_id,
                graph_revision,
                total_node_count,
                error=error,
                diagnostics=diagnostics,
                build_id=build_id,
                builder_version=builder_version,
            )

    def status_for_source(self, source_id: str) -> SemanticIndexStatusView:
        self.init()
        with self._connect() as conn:
            return self.status_for_source_conn(conn, source_id)

    def list_statuses(self, source_ids: Iterable[str]) -> dict[str, dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            return self.statuses_for_sources_conn(conn, source_ids)

    def reconcile_missing_states(self, source_ids: Optional[Iterable[str]] = None) -> int:
        self.init()
        with self._connect() as conn:
            return self.reconcile_missing_states_conn(conn, source_ids)

    @classmethod
    def get_state_conn(cls, conn: sqlite3.Connection, source_id: str) -> Optional[sqlite3.Row]:
        if not _table_exists(conn, "semantic_index_state"):
            return None
        return conn.execute("SELECT * FROM semantic_index_state WHERE source_id = ?", (source_id,)).fetchone()

    @classmethod
    def mark_current_graph_pending_conn(cls, conn: sqlite3.Connection, source_id: str) -> SemanticIndexStatusView:
        ensure_semantic_index_schema(conn)
        graph = cls.current_graph_info_conn(conn, source_id)
        if graph.total_node_count <= 0 or not graph.graph_revision:
            return cls.status_for_source_conn(conn, source_id)
        return cls.mark_source_pending_conn(conn, source_id, graph.graph_revision, graph.total_node_count)

    @classmethod
    def mark_source_pending_conn(
        cls,
        conn: sqlite3.Connection,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        ensure_semantic_index_schema(conn)
        existing = cls.get_state_conn(conn, source_id)
        if existing is not None and existing["graph_revision"] == graph_revision:
            return cls.status_for_source_conn(conn, source_id)
        if existing is not None:
            return cls.mark_source_stale_conn(
                conn,
                source_id,
                graph_revision,
                total_node_count,
                builder_version=builder_version,
            )
        now = _now()
        cls._upsert_state_conn(
            conn,
            source_id=source_id,
            graph_revision=graph_revision,
            status=SemanticIndexStatus.PENDING,
            builder_version=builder_version,
            total_node_count=total_node_count,
            indexed_node_count=0,
            updated_at=now,
            created_at=now,
            embedding_model=None,
            embedding_dimension=None,
            last_build_id=None,
            last_error=None,
            diagnostics_json="[]",
            started_at=None,
            completed_at=None,
        )
        return cls.status_for_source_conn(conn, source_id)

    @classmethod
    def mark_source_stale_conn(
        cls,
        conn: sqlite3.Connection,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        ensure_semantic_index_schema(conn)
        existing = cls.get_state_conn(conn, source_id)
        now = _now()
        cls._upsert_state_conn(
            conn,
            source_id=source_id,
            graph_revision=graph_revision,
            status=SemanticIndexStatus.STALE,
            builder_version=builder_version,
            total_node_count=total_node_count,
            indexed_node_count=0,
            updated_at=now,
            created_at=existing["created_at"] if existing is not None else now,
            embedding_model=existing["embedding_model"] if existing is not None else None,
            embedding_dimension=existing["embedding_dimension"] if existing is not None else None,
            last_build_id=existing["last_build_id"] if existing is not None else None,
            last_error=None,
            diagnostics_json="[]",
            started_at=None,
            completed_at=existing["completed_at"] if existing is not None else None,
        )
        return cls.status_for_source_conn(conn, source_id)

    @classmethod
    def mark_source_building_conn(
        cls,
        conn: sqlite3.Connection,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        indexed_node_count: int = 0,
        build_id: Optional[str] = None,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        ensure_semantic_index_schema(conn)
        existing = cls.get_state_conn(conn, source_id)
        now = _now()
        cls._upsert_state_conn(
            conn,
            source_id=source_id,
            graph_revision=graph_revision,
            status=SemanticIndexStatus.BUILDING,
            builder_version=builder_version,
            total_node_count=total_node_count,
            indexed_node_count=indexed_node_count,
            updated_at=now,
            created_at=existing["created_at"] if existing is not None else now,
            embedding_model=existing["embedding_model"] if existing is not None else None,
            embedding_dimension=existing["embedding_dimension"] if existing is not None else None,
            last_build_id=build_id,
            last_error=None,
            diagnostics_json="[]",
            started_at=now,
            completed_at=None,
        )
        return cls.status_for_source_conn(conn, source_id)

    @classmethod
    def mark_source_ready_conn(
        cls,
        conn: sqlite3.Connection,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        indexed_node_count: int,
        *,
        embedding_model: str,
        embedding_dimension: int,
        build_id: Optional[str] = None,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        ensure_semantic_index_schema(conn)
        existing = cls.get_state_conn(conn, source_id)
        now = _now()
        cls._upsert_state_conn(
            conn,
            source_id=source_id,
            graph_revision=graph_revision,
            status=SemanticIndexStatus.READY,
            builder_version=builder_version,
            total_node_count=total_node_count,
            indexed_node_count=indexed_node_count,
            updated_at=now,
            created_at=existing["created_at"] if existing is not None else now,
            embedding_model=embedding_model,
            embedding_dimension=embedding_dimension,
            last_build_id=build_id,
            last_error=None,
            diagnostics_json="[]",
            started_at=existing["started_at"] if existing is not None else None,
            completed_at=now,
        )
        return cls.status_for_source_conn(conn, source_id)

    @classmethod
    def mark_source_failed_conn(
        cls,
        conn: sqlite3.Connection,
        source_id: str,
        graph_revision: str,
        total_node_count: int,
        *,
        error: str,
        diagnostics: Optional[list[dict[str, Any]]] = None,
        build_id: Optional[str] = None,
        builder_version: int = SEMANTIC_BUILDER_VERSION,
    ) -> SemanticIndexStatusView:
        ensure_semantic_index_schema(conn)
        existing = cls.get_state_conn(conn, source_id)
        now = _now()
        cls._upsert_state_conn(
            conn,
            source_id=source_id,
            graph_revision=graph_revision,
            status=SemanticIndexStatus.FAILED,
            builder_version=builder_version,
            total_node_count=total_node_count,
            indexed_node_count=existing["indexed_node_count"] if existing is not None else 0,
            updated_at=now,
            created_at=existing["created_at"] if existing is not None else now,
            embedding_model=existing["embedding_model"] if existing is not None else None,
            embedding_dimension=existing["embedding_dimension"] if existing is not None else None,
            last_build_id=build_id,
            last_error=error[:1000],
            diagnostics_json=json.dumps(diagnostics or [], separators=(",", ":")),
            started_at=existing["started_at"] if existing is not None else None,
            completed_at=now,
        )
        return cls.status_for_source_conn(conn, source_id)

    @classmethod
    def status_for_source_conn(cls, conn: sqlite3.Connection, source_id: str) -> SemanticIndexStatusView:
        if not _table_exists(conn, "semantic_index_state"):
            graph = cls.current_graph_info_conn(conn, source_id)
            return cls._missing_or_pending_status(source_id, graph)
        graph = cls.current_graph_info_conn(conn, source_id)
        state = cls.get_state_conn(conn, source_id)
        if graph.total_node_count <= 0 or not graph.graph_revision:
            return SemanticIndexStatusView(
                source_id=source_id,
                status=SemanticIndexStatus.MISSING,
                graph_revision=None,
                builder_version=SEMANTIC_BUILDER_VERSION,
                total_node_count=0,
                indexed_node_count=0,
                embedding_model=None,
                embedding_dimension=None,
                updated_at=state["updated_at"] if state is not None else None,
            )
        if state is None:
            return cls._missing_or_pending_status(source_id, graph)
        stored_status = _status(state["status"])
        effective_status = stored_status
        if state["graph_revision"] != graph.graph_revision:
            effective_status = SemanticIndexStatus.STALE
        total = graph.total_node_count
        indexed = cls._indexed_current_fact_count_conn(
            conn,
            source_id,
            graph,
            builder_version=int(state["builder_version"] or SEMANTIC_BUILDER_VERSION),
            embedding_model=state["embedding_model"],
            current_revision_only=(state["graph_revision"] == graph.graph_revision),
        )
        if effective_status == SemanticIndexStatus.BUILDING:
            indexed = max(indexed, int(state["indexed_node_count"] or 0))
        return SemanticIndexStatusView(
            source_id=source_id,
            status=effective_status,
            graph_revision=graph.graph_revision,
            builder_version=int(state["builder_version"] or SEMANTIC_BUILDER_VERSION),
            total_node_count=total,
            indexed_node_count=indexed,
            embedding_model=state["embedding_model"],
            embedding_dimension=state["embedding_dimension"],
            updated_at=state["updated_at"],
            started_at=state["started_at"],
            completed_at=state["completed_at"],
            last_build_id=state["last_build_id"],
            last_error=state["last_error"],
        )

    @classmethod
    def statuses_for_sources_conn(cls, conn: sqlite3.Connection, source_ids: Iterable[str]) -> dict[str, dict[str, Any]]:
        return {source_id: cls.status_for_source_conn(conn, source_id).to_dict() for source_id in source_ids}

    @classmethod
    def _indexed_current_fact_count_conn(
        cls,
        conn: sqlite3.Connection,
        source_id: str,
        graph: SemanticGraphInfo,
        *,
        builder_version: int,
        embedding_model: Optional[str],
        current_revision_only: bool,
    ) -> int:
        if (
            not source_id
            or not graph.graph_id
            or graph.total_node_count <= 0
            or not all(
                _table_exists(conn, table)
                for table in ("files", "analysis_files", "analysis_graph_nodes", "semantic_documents", "semantic_vectors")
            )
        ):
            return 0
        revision_clause = "AND d.graph_id = ?" if current_revision_only else ""
        params: list[Any] = [builder_version]
        if current_revision_only:
            params.append(graph.graph_id)
        params.extend([embedding_model, embedding_model, source_id])
        row = conn.execute(
            f"""
            SELECT COUNT(DISTINCT n.id) AS count
            FROM analysis_graph_nodes n
            JOIN semantic_documents d
              ON d.source_id = n.source_id
             AND d.node_id = n.id
             AND d.builder_version = ?
             AND d.status = 'READY'
             {revision_clause}
            JOIN semantic_vectors v
              ON v.document_id = d.document_id
             AND v.source_id = d.source_id
             AND v.node_id = d.node_id
             AND v.graph_id = d.graph_id
             AND (? IS NULL OR v.embedding_model = ?)
            WHERE n.source_id = ?
              AND n.status IN ('TRUSTED', 'DERIVED')
              AND n.node_kind IN ('FILE', 'TYPE', 'CALLABLE', 'EXTERNAL')
              {SEMANTIC_INVENTORY_MEMBERSHIP_NODE_FILTER_SQL}
            """,
            params,
        ).fetchone()
        return int(row["count"] or 0) if row is not None else 0

    @classmethod
    def reconcile_missing_states_conn(cls, conn: sqlite3.Connection, source_ids: Optional[Iterable[str]] = None) -> int:
        ensure_semantic_index_schema(conn)
        if source_ids is None:
            if not _table_exists(conn, "analysis_graph_nodes"):
                return 0
            source_ids = [
                row["source_id"]
                for row in conn.execute(
                    """
                    SELECT DISTINCT n.source_id
                    FROM analysis_graph_nodes n
                    LEFT JOIN semantic_index_state state ON state.source_id = n.source_id
                    WHERE state.source_id IS NULL
                    ORDER BY n.source_id
                    """
                ).fetchall()
            ]
        created = 0
        for source_id in sorted({source_id for source_id in source_ids if source_id}):
            if cls.get_state_conn(conn, source_id) is not None:
                continue
            graph = cls.current_graph_info_conn(conn, source_id)
            if graph.total_node_count <= 0 or not graph.graph_revision:
                continue
            cls.mark_source_pending_conn(conn, source_id, graph.graph_revision, graph.total_node_count)
            created += 1
        return created

    @classmethod
    def current_graph_info_conn(cls, conn: sqlite3.Connection, source_id: str) -> SemanticGraphInfo:
        if not all(_table_exists(conn, table) for table in ("files", "analysis_files", "analysis_graph_nodes")):
            return SemanticGraphInfo(source_id=source_id, graph_id=None, graph_revision=None, total_node_count=0)
        total = int(
            conn.execute(
                f"""
                SELECT COUNT(*) AS count
                FROM analysis_graph_nodes n
                WHERE n.source_id = ?
                  AND n.status IN ('TRUSTED', 'DERIVED')
                  AND n.node_kind IN ('FILE', 'TYPE', 'CALLABLE', 'EXTERNAL')
                  {SEMANTIC_INVENTORY_MEMBERSHIP_NODE_FILTER_SQL}
                """,
                (source_id,),
            ).fetchone()["count"]
            or 0
        )
        if total <= 0:
            return SemanticGraphInfo(source_id=source_id, graph_id=None, graph_revision=None, total_node_count=0)
        state = None
        if _table_exists(conn, "analysis_graph_state"):
            state = conn.execute("SELECT graph_id, content_identity FROM analysis_graph_state WHERE source_id = ?", (source_id,)).fetchone()
        revision = str(state["content_identity"] or state["graph_id"]) if state is not None and (state["content_identity"] or state["graph_id"]) else cls.compute_graph_revision_conn(conn, source_id)
        return SemanticGraphInfo(source_id=source_id, graph_id=revision, graph_revision=revision, total_node_count=total)

    @classmethod
    def compute_graph_revision_conn(cls, conn: sqlite3.Connection, source_id: str) -> str:
        digest = hashlib.sha256()
        digest.update(b"semantic-index-current-graph-v1\n")
        digest.update(source_id.encode("utf-8"))
        digest.update(b"\n")
        for table_name, sql in _REVISION_QUERIES:
            digest.update(table_name.encode("utf-8"))
            digest.update(b"\n")
            rows = _stable_rows(conn, sql, (source_id,))
            for row in rows:
                digest.update(json.dumps(row, sort_keys=True, separators=(",", ":")).encode("utf-8"))
                digest.update(b"\n")
        return f"{source_id}:current-graph:{digest.hexdigest()}"

    @staticmethod
    def progress_percent(indexed_node_count: int, total_node_count: int) -> float:
        if total_node_count <= 0:
            return 0.0
        return round((max(0, min(indexed_node_count, total_node_count)) / total_node_count) * 100.0, 1)

    @classmethod
    def _missing_or_pending_status(cls, source_id: str, graph: SemanticGraphInfo) -> SemanticIndexStatusView:
        if graph.total_node_count <= 0 or not graph.graph_revision:
            return SemanticIndexStatusView(
                source_id=source_id,
                status=SemanticIndexStatus.MISSING,
                graph_revision=None,
                builder_version=SEMANTIC_BUILDER_VERSION,
                total_node_count=0,
                indexed_node_count=0,
                embedding_model=None,
                embedding_dimension=None,
                updated_at=None,
            )
        return SemanticIndexStatusView(
            source_id=source_id,
            status=SemanticIndexStatus.PENDING,
            graph_revision=graph.graph_revision,
            builder_version=SEMANTIC_BUILDER_VERSION,
            total_node_count=graph.total_node_count,
            indexed_node_count=0,
            embedding_model=None,
            embedding_dimension=None,
            updated_at=None,
        )

    @classmethod
    def _upsert_state_conn(
        cls,
        conn: sqlite3.Connection,
        *,
        source_id: str,
        graph_revision: str,
        status: SemanticIndexStatus,
        builder_version: int,
        total_node_count: int,
        indexed_node_count: int,
        updated_at: str,
        created_at: str,
        embedding_model: Optional[str],
        embedding_dimension: Optional[int],
        last_build_id: Optional[str],
        last_error: Optional[str],
        diagnostics_json: str,
        started_at: Optional[str],
        completed_at: Optional[str],
    ) -> None:
        conn.execute(
            """
            INSERT INTO semantic_index_state(
                source_id, graph_revision, status, builder_version, embedding_model, embedding_dimension,
                total_node_count, indexed_node_count, last_build_id, last_error, diagnostics_json,
                created_at, updated_at, started_at, completed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_id) DO UPDATE SET
                graph_revision = excluded.graph_revision,
                status = excluded.status,
                builder_version = excluded.builder_version,
                embedding_model = excluded.embedding_model,
                embedding_dimension = excluded.embedding_dimension,
                total_node_count = excluded.total_node_count,
                indexed_node_count = excluded.indexed_node_count,
                last_build_id = excluded.last_build_id,
                last_error = excluded.last_error,
                diagnostics_json = excluded.diagnostics_json,
                updated_at = excluded.updated_at,
                started_at = excluded.started_at,
                completed_at = excluded.completed_at
            """,
            (
                source_id,
                graph_revision,
                status.value,
                builder_version,
                embedding_model,
                embedding_dimension,
                max(0, int(total_node_count or 0)),
                max(0, int(indexed_node_count or 0)),
                last_build_id,
                last_error,
                diagnostics_json,
                created_at,
                updated_at,
                started_at,
                completed_at,
            ),
        )

    def _connect(self) -> sqlite3.Connection:
        timeout_seconds = SQLITE_SEMANTIC_BUSY_TIMEOUT_MS / 1000.0
        conn = observed_connect(self.db_path, timeout=timeout_seconds)
        conn.execute(f"PRAGMA busy_timeout = {SQLITE_SEMANTIC_BUSY_TIMEOUT_MS}")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        return conn


_REVISION_QUERIES: tuple[tuple[str, str], ...] = (
    (
        "nodes",
        """
        SELECT id, stable_key, node_kind, language, name, qualified_name, display_name,
               parent_node_id, relative_path, content_hash, line_start, line_end, confidence, status,
               fact_origin, flow_domain
        FROM analysis_graph_nodes
        WHERE source_id = ?
        ORDER BY id
        """,
    ),
    (
        "evidence",
        """
        SELECT id, relative_path, content_hash, line_start, line_end, excerpt_hash, evidence_kind,
               fact_origin, flow_domain
        FROM analysis_graph_evidence
        WHERE source_id = ?
        ORDER BY id
        """,
    ),
    (
        "claims",
        """
        SELECT id, node_id, claim_kind, summary, confidence, status,
               rejection_reason, fact_origin, flow_domain
        FROM analysis_graph_claims
        WHERE source_id = ?
        ORDER BY id
        """,
    ),
    (
        "edges",
        """
        SELECT id, from_node_id, to_node_id, edge_type, resolution_status, confidence,
               unresolved_target_json, status, fact_origin,
               flow_domain
        FROM analysis_graph_edges
        WHERE source_id = ?
        ORDER BY id
        """,
    ),
    (
        "claim_evidence",
        """
        SELECT link.claim_id, link.evidence_id
        FROM analysis_graph_claim_evidence link
        JOIN analysis_graph_claims claim ON claim.id = link.claim_id
        WHERE claim.source_id = ?
        ORDER BY link.claim_id, link.evidence_id
        """,
    ),
    (
        "edge_evidence",
        """
        SELECT link.edge_id, link.evidence_id
        FROM analysis_graph_edge_evidence link
        JOIN analysis_graph_edges edge ON edge.id = link.edge_id
        WHERE edge.source_id = ?
        ORDER BY link.edge_id, link.evidence_id
        """,
    ),
)

_JSON_COLUMNS = {"unresolved_target_json"}


def _stable_rows(conn: sqlite3.Connection, sql: str, params: tuple[Any, ...]) -> list[dict[str, Any]]:
    cursor = conn.execute(sql, params)
    columns = [description[0] for description in cursor.description or []]
    rows = []
    for row in cursor.fetchall():
        item = {}
        for index, column in enumerate(columns):
            value = row[column] if isinstance(row, sqlite3.Row) else row[index]
            item[column] = _normalize_value(column, value)
        rows.append(item)
    return rows


def _normalize_value(column: str, value: Any) -> Any:
    if column not in _JSON_COLUMNS or value in (None, ""):
        return value
    try:
        return json.loads(value)
    except (TypeError, ValueError):
        return value


def _status(value: str) -> SemanticIndexStatus:
    try:
        return SemanticIndexStatus(value)
    except ValueError:
        return SemanticIndexStatus.FAILED


def _row_to_dict(row: sqlite3.Row) -> dict[str, Any]:
    return {key: row[key] for key in row.keys()}


def _table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    return (
        conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'virtual table') AND name = ?",
            (table_name,),
        ).fetchone()
        is not None
    )


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _table_columns(conn: sqlite3.Connection, table_name: str) -> set[str]:
    return {str(row["name"]) for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()}
