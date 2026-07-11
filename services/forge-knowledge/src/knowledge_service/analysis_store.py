from __future__ import annotations

import json
import base64
import hashlib
import sqlite3
import threading
import time
import uuid
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Sequence, Set

from knowledge_service.anchor_expansion_contract import (
    AnchorEntrypointHint,
    AnchorExpansionBundle,
    AnchorExpansionEdge,
    AnchorExpansionNode,
    AnchorExpansionRequest,
)
from knowledge_service.errors import KnowledgeError
from knowledge_service.flow_builder import (
    FlowGraphBundle,
    FlowGraphEdge,
    FlowGraphEvidence,
    FlowGraphNode,
    FlowGraphSourceScope,
    flow_graph_bundle_to_public_bundle,
)
from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause
from knowledge_service.observability import observed_connect
from knowledge_service.overview_projection import ensure_overview_schema, rebuild_overview, refresh_overview_for_sources
from knowledge_service.semantic_index import SemanticIndexStatus, SemanticIndexStore, ensure_semantic_index_schema


ANALYSIS_SCHEMA_MIGRATIONS = (
    (1, "remove_legacy_analysis_job_counter"),
    (2, "add_analysis_job_source_scope"),
    (3, "reset_analysis_cache_for_graph_v1_cutover"),
    (4, "reconcile_graph_diagnostics_schema"),
    (5, "add_analysis_job_mode"),
    (6, "remove_legacy_graph_lifecycle"),
    (7, "current_state_graph_storage"),
    (8, "yaml_graph_contract_cleanup"),
    (9, "clean_yaml_graph_contract_persistence"),
)
CURRENT_ANALYSIS_SCHEMA_VERSION = ANALYSIS_SCHEMA_MIGRATIONS[-1][0]
SQLITE_WRITE_BUSY_TIMEOUT_MS = 5000
SQLITE_STATUS_BUSY_TIMEOUT_MS = 500
GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS = (0.05, 0.15, 0.3)
GRAPH_CONTRACT_VERSION = "GRAPH_CURRENT_V1"
GRAPH_SORT_VERSION = "ID_ASC_V1"
GRAPH_CURSOR_SIGNATURE_CONTEXT = "knowledge-graph-cursor-v1"
GRAPH_NODE_DETAIL_RELATION_LIMIT = 25


def _chunks(values: List[int], size: int):
    for offset in range(0, len(values), max(1, size)):
        yield values[offset : offset + max(1, size)]


@dataclass(frozen=True)
class GraphQuery:
    source_id: str
    graph_id: str
    resource: str
    flow_domain: str
    fact_origin: str
    node_kind: str
    edge_type: str
    include_external: str
    include_unresolved: bool
    include_isolated: bool
    search: str = ""
    contract_version: str = GRAPH_CONTRACT_VERSION
    sort_version: str = GRAPH_SORT_VERSION

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(json.dumps(self.as_payload(), sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()

    def as_payload(self) -> Dict[str, Any]:
        return {
            "contractVersion": self.contract_version,
            "sortVersion": self.sort_version,
            "graphId": self.graph_id,
            "sourceId": self.source_id,
            "resource": self.resource,
            "flowDomain": self.flow_domain,
            "factOrigin": self.fact_origin,
            "nodeKind": self.node_kind,
            "edgeType": self.edge_type,
            "includeExternal": self.include_external,
            "includeUnresolved": self.include_unresolved,
            "includeIsolated": self.include_isolated,
            "search": self.search,
        }


class AnalysisStore:
    _init_lock = threading.Lock()
    _initialized_paths: Set[str] = set()
    _migration_fault_stage: Optional[str] = None

    def __init__(self, db_path: Path):
        self.db_path = db_path
        self._current_resolution_has_coverage_tables: Optional[bool] = None

    def init(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        init_key = str(self.db_path.resolve())
        if init_key in AnalysisStore._initialized_paths:
            return
        with AnalysisStore._init_lock:
            if init_key in AnalysisStore._initialized_paths:
                return
            self._init_schema()
            AnalysisStore._initialized_paths.add(init_key)

    def _init_schema(self) -> None:
        with self._connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("BEGIN")
            self._reset_graph_persistence_if_schema_outdated(conn)
            self._create_analysis_lifecycle_schema(conn)
            self._create_graph_state_schema(conn)
            self._create_analysis_file_schema(conn)
            self._create_job_file_schema(conn)
            self._create_graph_node_schema(conn)
            self._create_graph_evidence_schema(conn)
            self._create_graph_claim_schema(conn)
            self._create_graph_edge_schema(conn)
            self._create_graph_evidence_link_schema(conn)
            self._create_graph_diagnostics_schema(conn)
            self._create_runtime_event_schema(conn)
            self._create_analysis_indexes(conn)
            self._run_post_schema_reconciliation(conn)

    def _reset_graph_persistence_if_schema_outdated(self, conn: sqlite3.Connection) -> None:
        if not self._needs_graph_persistence_reset(conn):
            return
        reset_tables = self._graph_persistence_reset_tables(conn)
        if not reset_tables:
            return
        self._drop_graph_lifecycle_triggers(conn, reset_tables)
        self._drop_tables_child_first(conn, {table for table in reset_tables if self._table_exists(conn, table)})

    def _needs_graph_persistence_reset(self, conn: sqlite3.Connection) -> bool:
        table_names = self._table_names(conn)
        analysis_tables_exist = any(table.startswith("analysis_") and table != "analysis_schema_migrations" for table in table_names)
        current_version = self._analysis_schema_version(conn)
        if current_version is None:
            return analysis_tables_exist or any(table.startswith(("graph_", "semantic_")) for table in table_names)
        if current_version < CURRENT_ANALYSIS_SCHEMA_VERSION:
            return True
        if any(table.startswith("graph_") for table in table_names):
            return True
        return self._current_graph_schema_shape_is_outdated(conn)

    def _analysis_schema_version(self, conn: sqlite3.Connection) -> Optional[int]:
        if not self._table_exists(conn, "analysis_schema_migrations"):
            return None
        row = conn.execute("SELECT MAX(version) AS version FROM analysis_schema_migrations").fetchone()
        return int(row["version"]) if row is not None and row["version"] is not None else None

    def _graph_persistence_reset_tables(self, conn: sqlite3.Connection) -> Set[str]:
        table_names = self._table_names(conn)
        if (self._analysis_schema_version(conn) or 0) < CURRENT_ANALYSIS_SCHEMA_VERSION:
            return {table for table in table_names if not self._preserve_table_during_graph_reset(table)}
        return {table for table in table_names if self._is_current_graph_persistence_table(table)}

    def _preserve_table_during_graph_reset(self, table: str) -> bool:
        if table == "analysis_schema_migrations":
            return True
        if table.startswith("sqlite_"):
            return True
        if table.startswith("context_chunks_fts"):
            return True
        return table in {
            "sources",
            "files",
            "context_chunks",
            "inventory_builds",
            "inventory_source_state",
            "knowledge_source_overview",
        }

    def _is_current_graph_persistence_table(self, table: str) -> bool:
        return (
            table in {"analysis_jobs", "analysis_files", "analysis_job_files", "analysis_runtime_events"}
            or table.startswith("analysis_graph_")
            or table.startswith("semantic_")
            or table.startswith("graph_")
        )

    def _current_graph_schema_shape_is_outdated(self, conn: sqlite3.Connection) -> bool:
        required_columns = {
            "analysis_jobs": {
                "job_id",
                "status",
                "started_at",
                "completed_at",
                "source_count",
                "file_count",
                "processed_file_count",
                "failed_file_count",
                "current_source_id",
                "current_relative_path",
                "source_ids_json",
                "last_progress_at",
                "diagnostics_json",
                "engine_version",
                "mode",
            },
            "analysis_files": {
                "file_id",
                "source_id",
                "relative_path",
                "content_hash",
                "analyzer_name",
                "analyzer_version",
                "status",
                "diagnostics_json",
                "engine_version",
                "flow_domain",
            },
            "analysis_job_files": {"id", "job_id", "source_id", "inventory_file_id", "analysis_file_id", "status"},
            "analysis_graph_state": {"source_id", "graph_id", "content_identity", "node_count", "edge_count", "claim_count", "evidence_count"},
            "analysis_graph_nodes": {
                "id",
                "source_id",
                "analysis_file_id",
                "file_id",
                "relative_path",
                "content_hash",
                "stable_key",
                "node_kind",
                "parameter_count",
            },
            "analysis_graph_evidence": {
                "id",
                "source_id",
                "analysis_file_id",
                "file_id",
                "relative_path",
                "content_hash",
                "excerpt_hash",
                "evidence_kind",
            },
            "analysis_graph_claims": {"id", "source_id", "node_id", "claim_kind", "summary", "status"},
            "analysis_graph_edges": {
                "id",
                "source_id",
                "analysis_file_id",
                "file_id",
                "relative_path",
                "content_hash",
                "from_node_id",
                "edge_type",
                "resolution_status",
                "argument_count",
                "metadata_json",
            },
            "analysis_graph_diagnostics": {"id", "source_id", "message", "severity", "metadata_json"},
        }
        removed_metadata_tables = {"analysis_graph_nodes", "analysis_graph_evidence", "analysis_graph_claims"}
        for table, required in required_columns.items():
            if not self._table_exists(conn, table):
                continue
            columns = self._table_columns(conn, table)
            if not required.issubset(columns):
                return True
            if table in removed_metadata_tables and "metadata_json" in columns:
                return True
        return False

    def _create_analysis_lifecycle_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_jobs (
                job_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                started_at TEXT,
                completed_at TEXT,
                source_count INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                processed_file_count INTEGER NOT NULL,
                failed_file_count INTEGER NOT NULL,
                current_source_id TEXT,
                current_relative_path TEXT,
                source_ids_json TEXT,
                last_progress_at TEXT,
                diagnostics_json TEXT NOT NULL,
                engine_version TEXT,
                mode TEXT NOT NULL DEFAULT 'FULL'
            )
        """)
        self._ensure_column(conn, "analysis_jobs", "current_source_id", "TEXT")
        self._ensure_column(conn, "analysis_jobs", "current_relative_path", "TEXT")
        self._ensure_column(conn, "analysis_jobs", "source_ids_json", "TEXT")
        self._ensure_column(conn, "analysis_jobs", "last_progress_at", "TEXT")
        self._ensure_column(conn, "analysis_jobs", "engine_version", "TEXT")
        self._ensure_column(conn, "analysis_jobs", "mode", "TEXT NOT NULL DEFAULT 'FULL'")

    def _create_graph_state_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_state (
                source_id TEXT PRIMARY KEY,
                graph_id TEXT NOT NULL,
                content_identity TEXT NOT NULL,
                node_count INTEGER NOT NULL DEFAULT 0,
                edge_count INTEGER NOT NULL DEFAULT 0,
                claim_count INTEGER NOT NULL DEFAULT 0,
                evidence_count INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL
            )
        """)

    def _create_analysis_file_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_files (
                file_id INTEGER PRIMARY KEY,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                analyzer_name TEXT NOT NULL,
                analyzer_version TEXT NOT NULL,
                status TEXT NOT NULL,
                analyzed_at TEXT,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                last_attempt_at TEXT,
                last_error_code TEXT,
                last_error_message TEXT,
                last_raw_response_preview TEXT,
                diagnostics_json TEXT NOT NULL,
                engine_version TEXT,
                flow_domain TEXT
            )
        """)
        self._ensure_column(conn, "analysis_files", "attempt_count", "INTEGER NOT NULL DEFAULT 0")
        self._ensure_column(conn, "analysis_files", "last_attempt_at", "TEXT")
        self._ensure_column(conn, "analysis_files", "last_error_code", "TEXT")
        self._ensure_column(conn, "analysis_files", "last_error_message", "TEXT")
        self._ensure_column(conn, "analysis_files", "last_raw_response_preview", "TEXT")
        self._ensure_column(conn, "analysis_files", "engine_version", "TEXT")
        self._ensure_column(conn, "analysis_files", "flow_domain", "TEXT")

    def _create_job_file_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_job_files (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER NOT NULL,
                analysis_file_id INTEGER,
                relative_path TEXT NOT NULL,
                extension TEXT,
                content_hash TEXT NOT NULL,
                line_count INTEGER NOT NULL DEFAULT 0,
                decode_policy TEXT,
                flow_domain TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                started_at TEXT,
                completed_at TEXT,
                diagnostics_json TEXT,
                engine_version TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(job_id) REFERENCES analysis_jobs(job_id) ON DELETE CASCADE
            )
        """)

    def _create_graph_node_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_nodes (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER NOT NULL,
                analysis_file_id INTEGER NOT NULL,
                file_id INTEGER NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                stable_key TEXT NOT NULL,
                node_kind TEXT NOT NULL,
                language TEXT,
                name TEXT,
                qualified_name TEXT,
                display_name TEXT,
                parent_node_id TEXT,
                parameter_count INTEGER,
                line_start INTEGER,
                line_end INTEGER,
                confidence REAL NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE
            )
        """)

    def _create_graph_evidence_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_evidence (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER NOT NULL,
                analysis_file_id INTEGER NOT NULL,
                file_id INTEGER NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                line_start INTEGER,
                line_end INTEGER,
                excerpt TEXT,
                excerpt_hash TEXT NOT NULL,
                evidence_kind TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE
            )
        """)

    def _create_graph_claim_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_claims (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                claim_kind TEXT NOT NULL,
                summary TEXT NOT NULL,
                confidence REAL NOT NULL,
                status TEXT NOT NULL,
                rejection_reason TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                FOREIGN KEY(node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE
            )
        """)

    def _create_graph_edge_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_edges (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER NOT NULL,
                analysis_file_id INTEGER NOT NULL,
                file_id INTEGER NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                from_node_id TEXT NOT NULL,
                to_node_id TEXT,
                edge_type TEXT NOT NULL,
                resolution_status TEXT NOT NULL,
                argument_count INTEGER,
                confidence REAL NOT NULL,
                unresolved_target_json TEXT,
                metadata_json TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE,
                FOREIGN KEY(from_node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE,
                FOREIGN KEY(to_node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE
            )
        """)

    def _create_graph_evidence_link_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_claim_evidence (
                claim_id TEXT NOT NULL,
                evidence_id TEXT NOT NULL,
                PRIMARY KEY (claim_id, evidence_id),
                FOREIGN KEY(claim_id) REFERENCES analysis_graph_claims(id) ON DELETE CASCADE,
                FOREIGN KEY(evidence_id) REFERENCES analysis_graph_evidence(id) ON DELETE CASCADE
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_edge_evidence (
                edge_id TEXT NOT NULL,
                evidence_id TEXT NOT NULL,
                PRIMARY KEY (edge_id, evidence_id),
                FOREIGN KEY(edge_id) REFERENCES analysis_graph_edges(id) ON DELETE CASCADE,
                FOREIGN KEY(evidence_id) REFERENCES analysis_graph_evidence(id) ON DELETE CASCADE
            )
        """)

    def _create_graph_diagnostics_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_diagnostics (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
                file_id INTEGER,
                relative_path TEXT,
                content_hash TEXT,
                severity TEXT NOT NULL,
                stage TEXT NOT NULL,
                code TEXT NOT NULL,
                message TEXT NOT NULL,
                candidate_id TEXT,
                line_start INTEGER,
                line_end INTEGER,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE
            )
        """)

    def _create_runtime_event_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_runtime_events (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                source_id TEXT,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
                relative_path TEXT,
                content_hash TEXT,
                attempt INTEGER,
                stage TEXT NOT NULL,
                event_type TEXT NOT NULL,
                status TEXT NOT NULL,
                started_at TEXT,
                completed_at TEXT,
                duration_ms INTEGER,
                error_code TEXT,
                error_message TEXT,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(job_id) REFERENCES analysis_jobs(job_id) ON DELETE CASCADE
            )
        """)

    def _create_analysis_indexes(self, conn: sqlite3.Connection) -> None:
        indexes_by_table = {
            "analysis_files": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_files_status ON analysis_files(source_id, status)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_files_current ON analysis_files(file_id, content_hash, analyzer_name, analyzer_version, engine_version, status)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_files_path ON analysis_files(source_id, relative_path)",
            ),
            "analysis_graph_state": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_state_source ON analysis_graph_state(source_id)",
            ),
            "analysis_graph_nodes": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source ON analysis_graph_nodes(source_id)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_file ON analysis_graph_nodes(analysis_file_id)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_flow_created ON analysis_graph_nodes(source_id, flow_domain, created_at)",
            ),
            "analysis_graph_claims": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_claims_node_kind ON analysis_graph_claims(node_id, claim_kind)",
            ),
            "analysis_graph_edges": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source ON analysis_graph_edges(source_id)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_nodes ON analysis_graph_edges(from_node_id, to_node_id)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_flow_created ON analysis_graph_edges(source_id, flow_domain, created_at)",
            ),
            "analysis_graph_claim_evidence": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_claim_evidence_evidence ON analysis_graph_claim_evidence(evidence_id)",
            ),
            "analysis_graph_edge_evidence": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_edge_evidence_evidence ON analysis_graph_edge_evidence(evidence_id)",
            ),
            "analysis_graph_diagnostics": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_graph_diagnostics_source_code ON analysis_graph_diagnostics(source_id, severity, code)",
            ),
            "analysis_runtime_events": (
                "CREATE INDEX IF NOT EXISTS idx_analysis_runtime_events_file ON analysis_runtime_events(job_id, source_id, relative_path, attempt)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_runtime_events_stage ON analysis_runtime_events(job_id, stage, status)",
                "CREATE INDEX IF NOT EXISTS idx_analysis_runtime_events_path ON analysis_runtime_events(source_id, relative_path, created_at)",
            ),
        }
        seen_names: Set[str] = set()
        for statements in indexes_by_table.values():
            for statement in statements:
                index_name = statement.split(" IF NOT EXISTS ", 1)[1].split(" ", 1)[0]
                if index_name in seen_names:
                    raise RuntimeError(f"Duplicate analysis index declaration: {index_name}")
                seen_names.add(index_name)
                conn.execute(statement)

    def _run_post_schema_reconciliation(self, conn: sqlite3.Connection) -> None:
        ensure_semantic_index_schema(conn)
        ensure_overview_schema(conn)
        self._current_resolution_has_coverage_tables = self._table_exists(conn, "files") and self._table_exists(conn, "knowledge_source_overview")
        self._migration_stage("after_canonical_schema")
        self._migration_stage("after_pointer_mutation")
        self._run_schema_migrations(conn)
        self._reconcile_graph_diagnostics_schema(conn)
        self._reconcile_orphan_job_files(conn)
        self._reconcile_graph_runtime_inventory_membership(conn)
        ensure_semantic_index_schema(conn)
        SemanticIndexStore.reconcile_missing_states_conn(conn)
        ensure_overview_schema(conn)
        rebuild_overview(conn)

    def _migration_stage(self, stage: str) -> None:
        if AnalysisStore._migration_fault_stage == stage:
            raise RuntimeError(f"injected migration failure at {stage}")

    def _reconcile_orphan_job_files(self, conn: sqlite3.Connection) -> None:
        diagnostic = json.dumps(
            [
                {
                    "code": "ANALYSIS_JOB_FILE_ORPHANED",
                    "message": "Analysis job file was left incomplete after its parent job stopped or failed.",
                }
            ]
        )
        conn.execute(
            """
            UPDATE analysis_job_files
            SET status = CASE
                    WHEN (SELECT status FROM analysis_jobs WHERE job_id = analysis_job_files.job_id) = 'STOPPED' THEN 'STOPPED'
                    ELSE 'FAILED'
                END,
                completed_at = COALESCE(analysis_job_files.completed_at, datetime('now')),
                updated_at = datetime('now'),
                diagnostics_json = CASE
                    WHEN analysis_job_files.diagnostics_json IS NULL OR analysis_job_files.diagnostics_json = '[]'
                    THEN ?
                    ELSE analysis_job_files.diagnostics_json
                END
            WHERE analysis_job_files.status IN ('PENDING', 'RUNNING')
              AND EXISTS (
                  SELECT 1
                  FROM analysis_jobs
                  WHERE analysis_jobs.job_id = analysis_job_files.job_id
                    AND analysis_jobs.status NOT IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')
              )
        """,
            (diagnostic,),
        )

    def create_job(self, job: Dict[str, Any]) -> None:
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                """
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, diagnostics_json, engine_version, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                self._job_params(job),
            )
            refresh_overview_for_sources(conn, job.get("sourceIds") or [])

        self._write_with_busy_retry(write)

    def update_job(self, job_id: str, updates: Dict[str, Any]) -> None:
        current = self.job(job_id)
        if current is None:
            return
        current.update(updates)
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                """
                UPDATE analysis_jobs
                SET status = ?, started_at = ?, completed_at = ?, source_count = ?, file_count = ?, processed_file_count = ?,
                    failed_file_count = ?, current_source_id = ?, current_relative_path = ?, source_ids_json = ?, last_progress_at = ?,
                    diagnostics_json = ?, engine_version = ?, mode = ?
                WHERE job_id = ?
                """,
                (*self._job_params(current)[1:], job_id),
            )
            refresh_overview_for_sources(conn, self._overview_sources_for_job(conn, job_id, current.get("sourceIds") or []))

        self._write_with_busy_retry(write)

    def create_job_files(self, job_id: str, rows: List[sqlite3.Row], flow_domain_by_file_id: Dict[int, str], engine_version: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()

        def write(conn: sqlite3.Connection) -> None:
            for row in rows:
                line_count = int(row["line_count"] or 0) if "line_count" in row.keys() else 0
                decode_policy = row["decode_policy"] if "decode_policy" in row.keys() else None
                job_file_id = f"{job_id}:{row['id']}"
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_job_files(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, extension,
                        content_hash, line_count, decode_policy, flow_domain, status, attempt_count,
                        started_at, completed_at, diagnostics_json, engine_version, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    (
                        job_file_id,
                        job_id,
                        row["source_id"],
                        row["id"],
                        None,
                        row["relative_path"],
                        row["extension"],
                        row["content_hash"],
                        line_count,
                        decode_policy,
                        flow_domain_by_file_id.get(int(row["id"]), "CODE"),
                        "PENDING",
                        0,
                        None,
                        None,
                        json.dumps([]),
                        engine_version,
                        now,
                        now,
                    ),
                )
            refresh_overview_for_sources(conn, sorted({row["source_id"] for row in rows}))

        self._write_with_busy_retry(write)

    def create_job_with_pending_files(
        self,
        job: Dict[str, Any],
        rows: List[sqlite3.Row],
        flow_domain_by_file_id: Dict[int, str],
        engine_version: str,
        *,
        reset_failed_current_state: bool = False,
    ) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        job_params = self._job_params(job)

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                """
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, diagnostics_json, engine_version, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                job_params,
            )
            for row in rows:
                line_count = int(row["line_count"] or 0) if "line_count" in row.keys() else 0
                decode_policy = row["decode_policy"] if "decode_policy" in row.keys() else None
                job_file_id = f"{job['jobId']}:{row['id']}"
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_job_files(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, extension,
                        content_hash, line_count, decode_policy, flow_domain, status, attempt_count,
                        started_at, completed_at, diagnostics_json, engine_version, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        job_file_id,
                        job["jobId"],
                        row["source_id"],
                        row["id"],
                        None,
                        row["relative_path"],
                        row["extension"],
                        row["content_hash"],
                        line_count,
                        decode_policy,
                        flow_domain_by_file_id.get(int(row["id"]), "CODE"),
                        "PENDING",
                        0,
                        None,
                        None,
                        json.dumps([]),
                        engine_version,
                        now,
                        now,
                    ),
                )
                if reset_failed_current_state:
                    conn.execute(
                        """
                        UPDATE analysis_files
                        SET status = 'PENDING',
                            last_attempt_at = ?,
                            last_error_code = NULL,
                            last_error_message = NULL,
                            last_raw_response_preview = NULL
                        WHERE source_id = ?
                          AND relative_path = ?
                          AND content_hash = ?
                          AND status = 'FAILED'
                        """,
                        (now, row["source_id"], row["relative_path"], row["content_hash"]),
                    )
            refresh_overview_for_sources(conn, sorted({row["source_id"] for row in rows}))

        self._write_with_busy_retry(write)

    def update_job_file(
        self,
        job_id: str,
        inventory_file_id: int,
        status: str,
        *,
        analysis_file_id: Optional[int] = None,
        attempt_count: int = 0,
        diagnostics: Optional[List[Dict[str, Any]]] = None,
        line_count: Optional[int] = None,
        flow_domain: Optional[str] = None,
        engine_version: Optional[str] = None,
        started: bool = False,
        completed: bool = False,
    ) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        updates = [
            "status = ?",
            "attempt_count = ?",
            "diagnostics_json = ?",
            "updated_at = ?",
        ]
        params: List[Any] = [status, attempt_count, json.dumps(diagnostics or []), now]
        if analysis_file_id is not None:
            updates.append("analysis_file_id = ?")
            params.append(analysis_file_id)
        if line_count is not None:
            updates.append("line_count = ?")
            params.append(line_count)
        if flow_domain is not None:
            updates.append("flow_domain = ?")
            params.append(flow_domain)
        if engine_version is not None:
            updates.append("engine_version = ?")
            params.append(engine_version)
        if started:
            updates.append("started_at = COALESCE(started_at, ?)")
            params.append(now)
        if completed:
            updates.append("completed_at = ?")
            params.append(now)
        params.extend([job_id, inventory_file_id])

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                f"""
                UPDATE analysis_job_files
                SET {", ".join(updates)}
                WHERE job_id = ? AND inventory_file_id = ?
            """,
                params,
            )
            row = conn.execute(
                "SELECT source_id FROM analysis_job_files WHERE job_id = ? AND inventory_file_id = ?",
                (job_id, inventory_file_id),
            ).fetchone()
            if row is not None:
                refresh_overview_for_sources(conn, [row["source_id"]])

        self._write_with_busy_retry(write)

    def record_runtime_event(self, event: Dict[str, Any]) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        metadata = event.get("metadata")
        if metadata is None:
            metadata = event.get("metadata_json")
        if not isinstance(metadata, dict):
            metadata = {}
        params = (
            str(event.get("id") or uuid.uuid4()),
            str(event["job_id"]),
            event.get("source_id"),
            event.get("inventory_file_id"),
            event.get("analysis_file_id"),
            event.get("relative_path"),
            event.get("content_hash"),
            event.get("attempt"),
            str(event["stage"]),
            str(event["event_type"]),
            str(event["status"]),
            event.get("started_at"),
            event.get("completed_at"),
            event.get("duration_ms"),
            event.get("error_code"),
            event.get("error_message"),
            json.dumps(metadata, ensure_ascii=False, default=str),
            str(event.get("created_at") or now),
        )

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                """
                INSERT INTO analysis_runtime_events(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, content_hash,
                    attempt, stage, event_type, status, started_at, completed_at, duration_ms,
                    error_code, error_message, metadata_json, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                params,
            )

        self._write_with_busy_retry(write)

    def runtime_events(
        self,
        *,
        job_id: Optional[str] = None,
        source_id: Optional[str] = None,
        relative_path: Optional[str] = None,
        limit: int = 200,
        offset: int = 0,
    ) -> Dict[str, Any]:
        self.init()
        clauses: List[str] = []
        params: List[Any] = []
        if job_id:
            clauses.append("job_id = ?")
            params.append(job_id)
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        if relative_path:
            clauses.append("relative_path = ?")
            params.append(relative_path)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        bounded_limit = max(1, min(int(limit), 1000))
        bounded_offset = max(0, int(offset))
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_runtime_events {where}", params).fetchone()["count"]
            rows = conn.execute(
                f"""
                SELECT *
                FROM analysis_runtime_events
                {where}
                ORDER BY created_at, id
                LIMIT ? OFFSET ?
                """,
                [*params, bounded_limit, bounded_offset],
            ).fetchall()
        return {
            "total": int(total or 0),
            "events": [self._runtime_event(row) for row in rows],
        }

    def stop_incomplete_job_files(self, job_id: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                """
                UPDATE analysis_job_files
                SET status = 'STOPPED',
                    completed_at = COALESCE(completed_at, ?),
                    updated_at = ?
                WHERE job_id = ?
                  AND status IN ('PENDING', 'RUNNING')
            """,
                (now, now, job_id),
            )
            refresh_overview_for_sources(conn, self._overview_sources_for_job(conn, job_id, []))

        self._write_with_busy_retry(write)

    def job(self, job_id: str) -> Optional[Dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE job_id = ?", (job_id,)).fetchone()
        return self._job(row) if row else None

    def active_job(self, busy_timeout_ms: int = SQLITE_WRITE_BUSY_TIMEOUT_MS) -> Optional[Dict[str, Any]]:
        self.init()
        with self._connect(busy_timeout_ms=busy_timeout_ms) as conn:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY started_at DESC LIMIT 1").fetchone()
        return self._job(row) if row else None

    def request_stop(self, job_id: str) -> Optional[Dict[str, Any]]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()

        def write(conn: sqlite3.Connection) -> Optional[Dict[str, Any]]:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE job_id = ?", (job_id,)).fetchone()
            if row is None:
                return None
            job = self._job(row)
            if job["status"] not in {"QUEUED", "RUNNING", "STOP_REQUESTED"}:
                return job
            diagnostics = job.get("diagnostics") or []
            if not any(item.get("code") == "ANALYSIS_JOB_STOP_REQUESTED" for item in diagnostics):
                diagnostics.append(
                    {
                        "code": "ANALYSIS_JOB_STOP_REQUESTED",
                        "message": "Analysis stop was requested by the operator.",
                    }
                )
            job.update(
                {
                    "status": "STOP_REQUESTED",
                    "completedAt": None,
                    "currentSourceId": None,
                    "currentRelativePath": None,
                    "lastProgressAt": now,
                    "diagnostics": diagnostics[-20:],
                }
            )
            conn.execute(
                """
                UPDATE analysis_jobs
                SET status = ?, completed_at = NULL, current_source_id = NULL, current_relative_path = NULL,
                    last_progress_at = ?, diagnostics_json = ?
                WHERE job_id = ?
            """,
                (job["status"], job["lastProgressAt"], json.dumps(job["diagnostics"]), job_id),
            )
            refresh_overview_for_sources(conn, self._overview_sources_for_job(conn, job_id, job.get("sourceIds") or []))
            return job

        return self._write_with_busy_retry(write)

    def stop_requested(self, job_id: str) -> bool:
        job = self.job(job_id)
        return job is not None and job["status"] in {"STOP_REQUESTED", "STOPPED"}

    def mark_interrupted_jobs(self) -> None:
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            rows = conn.execute("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')").fetchall()
            for row in rows:
                diagnostics = json.loads(row["diagnostics_json"] or "[]")
                diagnostics.append(
                    {
                        "code": "ANALYSIS_JOB_INTERRUPTED",
                        "message": "Analysis job was interrupted by Knowledge service restart.",
                    }
                )
                status = "STOPPED" if row["status"] == "STOP_REQUESTED" else "FAILED"
                conn.execute(
                    """
                    UPDATE analysis_jobs
                    SET status = ?,
                        completed_at = COALESCE(completed_at, datetime('now')),
                        current_source_id = NULL,
                        current_relative_path = NULL,
                        diagnostics_json = ?
                    WHERE job_id = ?
                """,
                    (status, json.dumps(diagnostics[-20:]), row["job_id"]),
                )
                conn.execute(
                    """
                    UPDATE analysis_job_files
                    SET status = ?,
                        completed_at = COALESCE(completed_at, datetime('now')),
                        updated_at = datetime('now'),
                        diagnostics_json = ?
                    WHERE job_id = ?
                      AND status IN ('PENDING', 'RUNNING')
                """,
                    (
                        "STOPPED" if status == "STOPPED" else "FAILED",
                        json.dumps(diagnostics[-20:]),
                        row["job_id"],
                    ),
                )
                refresh_overview_for_sources(conn, self._overview_sources_for_job(conn, row["job_id"], json.loads(row["source_ids_json"] or "[]")))
        self._write_with_busy_retry(write)

    def status(self) -> Dict[str, Any]:
        self.init()
        active = self.active_job(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS)
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            latest = conn.execute("SELECT * FROM analysis_jobs WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1").fetchone()
            current_failures = self._current_failure_summary(conn, None)
            analysis_state = self._current_analysis_state(conn, None)
        if not latest and not active:
            return {
                "status": "EMPTY",
                "latestJobId": None,
                "activeJob": None,
                **current_failures,
                **analysis_state,
            }
        latest_job = self._job(latest) if latest else None
        return {
            "status": "RUNNING" if active else "READY",
            "latestJobId": latest_job["jobId"] if latest_job else None,
            "activeJob": active,
            "lastCompletedAt": latest_job["completedAt"] if latest_job else None,
            "sourceCount": latest_job["sourceCount"] if latest_job else 0,
            "fileCount": latest_job["fileCount"] if latest_job else 0,
            "scannedFileCount": latest_job["processedFileCount"] if latest_job else 0,
            "failedFileCount": latest_job["failedFileCount"] if latest_job else 0,
            **current_failures,
            **analysis_state,
        }

    def current_failed_inventory_rows(self, source_ids: Optional[List[str]] = None) -> List[sqlite3.Row]:
        self.init()
        clauses = [
            "af.status = 'FAILED'",
            "f.id IS NOT NULL",
            "f.content_hash = af.content_hash",
        ]
        params: List[Any] = []
        if source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            clauses.append(f"af.source_id IN ({placeholders})")
            params.extend(source_ids)
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            return conn.execute(
                f"""
                SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json
                FROM analysis_files af
                JOIN files f
                  ON f.source_id = af.source_id
                 AND f.relative_path = af.relative_path
                 AND f.content_hash = af.content_hash
                JOIN sources s ON s.source_id = f.source_id
                WHERE {" AND ".join(clauses)}
                ORDER BY f.source_id, f.relative_path
                """,
                params,
            ).fetchall()

    def current_failure_breakdown(self, source_ids: Optional[List[str]] = None) -> Dict[str, int]:
        self.init()
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            return self._current_failure_summary(conn, None if source_ids else None, source_ids).get("failureCodeBreakdown", {})

    def current_analysis_state(self, source_ids: Optional[List[str]] = None) -> Dict[str, Any]:
        self.init()
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            return self._current_analysis_state(conn, source_ids)

    def _current_analysis_state(self, conn: sqlite3.Connection, source_ids: Optional[List[str]] = None) -> Dict[str, Any]:
        file_clauses: List[str] = []
        params: List[Any] = []
        if source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            file_clauses.append(f"f.source_id IN ({placeholders})")
            params.extend(source_ids)
        file_where = f"WHERE {' AND '.join(file_clauses)}" if file_clauses else ""
        row = conn.execute(
            f"""
            SELECT
                COUNT(f.id) AS total,
                SUM(CASE WHEN af.status = 'ANALYZED' THEN 1 ELSE 0 END) AS succeeded,
                SUM(CASE WHEN af.status = 'PARTIAL' THEN 1 ELSE 0 END) AS partial,
                SUM(CASE WHEN af.status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                SUM(CASE WHEN af.status = 'SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS' THEN 1 ELSE 0 END) AS skipped,
                SUM(CASE WHEN af.status = 'PENDING' THEN 1 ELSE 0 END) AS explicit_pending
            FROM files f
            LEFT JOIN analysis_files af
              ON af.source_id = f.source_id
             AND af.relative_path = f.relative_path
             AND af.content_hash = f.content_hash
            {file_where}
            """,
            params,
        ).fetchone()
        job_clauses = ["j.status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')"]
        job_params: List[Any] = []
        if source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            job_clauses.append(f"jf.source_id IN ({placeholders})")
            job_params.extend(source_ids)
        job_counts = conn.execute(
            f"""
            SELECT
                SUM(CASE WHEN jf.status = 'PENDING' THEN 1 ELSE 0 END) AS pending,
                SUM(CASE WHEN jf.status = 'RUNNING' THEN 1 ELSE 0 END) AS running
            FROM analysis_job_files jf
            JOIN analysis_jobs j ON j.job_id = jf.job_id
            WHERE {" AND ".join(job_clauses)}
            """,
            job_params,
        ).fetchone()
        total = int(row["total"] or 0)
        succeeded = int(row["succeeded"] or 0)
        partial = int(row["partial"] or 0)
        failed = int(row["failed"] or 0)
        skipped = int(row["skipped"] or 0)
        running = int(job_counts["running"] or 0)
        terminal = succeeded + partial + failed + skipped
        pending = max(total - terminal - running, 0)
        completed = terminal
        return {
            "totalFiles": total,
            "pendingFiles": pending,
            "runningFiles": running,
            "succeededFiles": succeeded,
            "partialFiles": partial,
            "failedFiles": failed,
            "retryableFiles": failed,
            "skippedFiles": skipped,
            "completedFiles": completed,
            "completionPercent": round((completed / total) * 100, 1) if total else 0.0,
        }

    def _current_failure_summary(
        self,
        conn: sqlite3.Connection,
        source_id: Optional[str],
        source_ids: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        clauses = [
            "af.status = 'FAILED'",
            "f.id IS NOT NULL",
            "f.content_hash = af.content_hash",
        ]
        params: List[Any] = []
        if source_id:
            clauses.append("af.source_id = ?")
            params.append(source_id)
        elif source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            clauses.append(f"af.source_id IN ({placeholders})")
            params.extend(source_ids)
        where = " AND ".join(clauses)
        base = f"""
            FROM analysis_files af
            JOIN files f
              ON f.source_id = af.source_id
             AND f.relative_path = af.relative_path
             AND f.content_hash = af.content_hash
            WHERE {where}
        """
        failed = conn.execute(f"SELECT COUNT(*) AS count {base}", params).fetchone()["count"]
        breakdown = {
            row["code"]: int(row["count"] or 0)
            for row in conn.execute(
                f"""
                SELECT COALESCE(af.last_error_code, 'ANALYSIS_FILE_FAILED') AS code, COUNT(*) AS count
                {base}
                GROUP BY COALESCE(af.last_error_code, 'ANALYSIS_FILE_FAILED')
                ORDER BY count DESC, code
                """,
                params,
            ).fetchall()
        }
        return {
            "currentFailedFileCount": int(failed or 0),
            "currentPartialFileCount": 0,
            "retryableFailureCount": int(failed or 0),
            "failureCodeBreakdown": breakdown,
        }

    def unchanged(self, file_id: int, content_hash: str, analyzer_name: str, analyzer_version: str, engine_version: Optional[str] = None) -> bool:
        self.init()
        engine_clause = "AND COALESCE(engine_version, '') = COALESCE(?, '')" if engine_version is not None else ""
        params: list[Any] = [file_id, content_hash, analyzer_name, analyzer_version]
        if engine_version is not None:
            params.append(engine_version)
        with self._connect() as conn:
            row = conn.execute(
                f"""
                SELECT af.file_id
                FROM files f
                JOIN analysis_files af
                  ON af.source_id = f.source_id
                 AND af.relative_path = f.relative_path
                 AND af.content_hash = f.content_hash
                WHERE f.id = ?
                  AND f.content_hash = ?
                  AND af.analyzer_name = ?
                  AND af.analyzer_version = ?
                  AND af.status = 'ANALYZED'
                  {engine_clause}
            """,
                params,
            ).fetchone()
        return row is not None

    def unchanged_file_ids(self, rows: List[sqlite3.Row], analyzer_name: str, analyzer_version: str, engine_version: Optional[str] = None) -> set[int]:
        if not rows:
            return set()
        self.init()
        result: set[int] = set()
        with self._connect() as conn:
            for offset in range(0, len(rows), 400):
                batch = rows[offset : offset + 400]
                clauses: list[str] = []
                params: list[Any] = [analyzer_name, analyzer_version]
                engine_clause = ""
                if engine_version is not None:
                    engine_clause = "AND COALESCE(engine_version, '') = COALESCE(?, '')"
                    params.append(engine_version)
                for row in batch:
                    clauses.append("(f.id = ? AND f.source_id = ? AND f.relative_path = ? AND f.content_hash = ?)")
                    params.extend([row["id"], row["source_id"], row["relative_path"], row["content_hash"]])
                matches = conn.execute(
                    f"""
                    SELECT f.id
                    FROM files f
                    JOIN analysis_files af
                      ON af.source_id = f.source_id
                     AND af.relative_path = f.relative_path
                     AND af.content_hash = f.content_hash
                    WHERE af.analyzer_name = ?
                      AND af.analyzer_version = ?
                      {engine_clause}
                      AND af.status = 'ANALYZED'
                      AND ({" OR ".join(clauses)})
                """,
                    params,
                ).fetchall()
                result.update(row["id"] for row in matches)
        return result

    def replace_file_graph_analysis(self, file_id: int, state: Dict[str, Any], graph: Dict[str, List[Dict[str, Any]]]) -> None:
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        operation = "delete_file_analysis"
        table = "analysis_files"
        attempts = len(GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS) + 1
        for attempt in range(attempts):
            try:
                with self._connect() as conn:
                    self._replace_file_graph_analysis_once(conn, file_id, state, graph, created_at)
                return
            except KnowledgeError as exc:
                if (
                    exc.code == "ANALYSIS_GRAPH_STORE_FAILED"
                    and self._is_sqlite_busy_message(str(exc.details.get("sqliteMessage") or ""))
                    and attempt < attempts - 1
                ):
                    time.sleep(GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS[attempt])
                    continue
                raise
            except sqlite3.OperationalError as exc:
                if not self._is_sqlite_busy(exc) or attempt >= attempts - 1:
                    raise self._graph_store_error(table, operation, exc) from exc
                time.sleep(GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS[attempt])
            except sqlite3.Error as exc:
                raise self._graph_store_error(table, operation, exc) from exc

    def _replace_file_graph_analysis_once(
        self,
        conn: sqlite3.Connection,
        file_id: int,
        state: Dict[str, Any],
        graph: Dict[str, List[Dict[str, Any]]],
        created_at: str,
    ) -> None:
        def replace_identity() -> None:
            self._upsert_file(conn, file_id, state)
            self._delete_file_graph(conn, file_id)

        self._run_graph_store_step("analysis_files", "delete_file_analysis", replace_identity)
        self._insert_graph_nodes(conn, file_id, state, graph, created_at)
        self._insert_graph_evidence(conn, file_id, state, graph, created_at)
        self._insert_graph_claims(conn, graph, created_at)
        self._insert_graph_edges(conn, file_id, state, graph, created_at)
        self._insert_graph_diagnostics(conn, file_id, state, graph, created_at)
        self._insert_claim_evidence_links(conn, graph)
        self._insert_edge_evidence_links(conn, graph)
        self._finalize_graph_replacement(conn, state["source_id"], created_at)

    def _run_graph_store_step(self, table: str, operation: str, action) -> Any:
        try:
            return action()
        except sqlite3.Error as exc:
            raise self._graph_store_error(table, operation, exc) from exc

    def _insert_graph_nodes(
        self,
        conn: sqlite3.Connection,
        file_id: int,
        state: Dict[str, Any],
        graph: Dict[str, List[Dict[str, Any]]],
        created_at: str,
    ) -> None:
        def insert() -> None:
            for node in graph.get("nodes") or []:
                node_file_id = int(node.get("analysis_file_id") or node.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                    INSERT INTO analysis_graph_nodes(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, stable_key, node_kind,
                        language, name, qualified_name, display_name, parent_node_id, parameter_count, line_start, line_end,
                        confidence, status, created_at, updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        node["id"],
                        node["job_id"],
                        node["source_id"],
                        node.get("inventory_file_id") or node_file_id,
                        node.get("analysis_file_id") or node_file_id,
                        node_file_id,
                        state["relative_path"],
                        state["content_hash"],
                        node["stable_key"],
                        node["node_kind"],
                        node.get("language"),
                        node["name"],
                        node.get("qualified_name"),
                        node.get("display_name"),
                        node.get("parent_node_id"),
                        node.get("parameter_count"),
                        node.get("line_start"),
                        node.get("line_end"),
                        node["confidence"],
                        node["status"],
                        created_at,
                        created_at,
                        node.get("fact_origin"),
                        node.get("flow_domain"),
                    ),
                )

        self._run_graph_store_step("analysis_graph_nodes", "insert_nodes", insert)

    def _insert_graph_evidence(
        self,
        conn: sqlite3.Connection,
        file_id: int,
        state: Dict[str, Any],
        graph: Dict[str, List[Dict[str, Any]]],
        created_at: str,
    ) -> None:
        def insert() -> None:
            for item in graph.get("evidence") or []:
                evidence_file_id = int(item.get("analysis_file_id") or item.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                    INSERT INTO analysis_graph_evidence(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, line_start,
                        line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        item["id"],
                        item["job_id"],
                        item["source_id"],
                        item.get("inventory_file_id") or evidence_file_id,
                        item.get("analysis_file_id") or evidence_file_id,
                        evidence_file_id,
                        state["relative_path"],
                        item["content_hash"],
                        item["line_start"],
                        item["line_end"],
                        item.get("excerpt"),
                        item["excerpt_hash"],
                        item["evidence_kind"],
                        created_at,
                        created_at,
                        item.get("fact_origin"),
                        item.get("flow_domain"),
                    ),
                )

        self._run_graph_store_step("analysis_graph_evidence", "insert_evidence", insert)

    def _insert_graph_claims(
        self,
        conn: sqlite3.Connection,
        graph: Dict[str, List[Dict[str, Any]]],
        created_at: str,
    ) -> None:
        def insert() -> None:
            for claim in graph.get("claims") or []:
                conn.execute(
                    """
                    INSERT INTO analysis_graph_claims(
                        id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                        rejection_reason, created_at, updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        claim["id"],
                        claim["job_id"],
                        claim["source_id"],
                        claim["node_id"],
                        claim["claim_kind"],
                        claim["summary"],
                        claim["confidence"],
                        claim["status"],
                        claim.get("rejection_reason"),
                        created_at,
                        created_at,
                        claim.get("fact_origin"),
                        claim.get("flow_domain"),
                    ),
                )

        self._run_graph_store_step("analysis_graph_claims", "insert_claims", insert)

    def _insert_graph_edges(
        self,
        conn: sqlite3.Connection,
        file_id: int,
        state: Dict[str, Any],
        graph: Dict[str, List[Dict[str, Any]]],
        created_at: str,
    ) -> None:
        def insert() -> None:
            for edge in graph.get("edges") or []:
                edge_file_id = int(edge.get("analysis_file_id") or edge.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                    INSERT INTO analysis_graph_edges(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                        from_node_id, to_node_id, edge_type, resolution_status, confidence,
                        argument_count, unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        edge["id"],
                        edge["job_id"],
                        edge["source_id"],
                        edge.get("inventory_file_id") or edge_file_id,
                        edge.get("analysis_file_id") or edge_file_id,
                        edge_file_id,
                        state["relative_path"],
                        state["content_hash"],
                        edge["from_node_id"],
                        edge.get("to_node_id"),
                        edge["edge_type"],
                        edge["resolution_status"],
                        edge["confidence"],
                        edge.get("argument_count"),
                        json.dumps(edge.get("unresolved_target")) if edge.get("unresolved_target") is not None else None,
                        json.dumps(edge.get("metadata") or {}),
                        edge["status"],
                        created_at,
                        created_at,
                        edge.get("fact_origin"),
                        edge.get("flow_domain"),
                    ),
                )

        self._run_graph_store_step("analysis_graph_edges", "insert_edges", insert)

    def _insert_graph_diagnostics(
        self,
        conn: sqlite3.Connection,
        file_id: int,
        state: Dict[str, Any],
        graph: Dict[str, List[Dict[str, Any]]],
        created_at: str,
    ) -> None:
        def insert() -> None:
            for diagnostic in graph.get("diagnostics") or []:
                diagnostic_file_id = int(diagnostic.get("analysis_file_id") or diagnostic.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                    INSERT INTO analysis_graph_diagnostics(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                        severity, stage, code, message, candidate_id, line_start, line_end, metadata_json,
                        created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        diagnostic["id"],
                        diagnostic["job_id"],
                        diagnostic["source_id"],
                        diagnostic.get("inventory_file_id") or diagnostic_file_id,
                        diagnostic.get("analysis_file_id") or diagnostic_file_id,
                        diagnostic_file_id,
                        state["relative_path"],
                        state["content_hash"],
                        diagnostic["severity"],
                        diagnostic["stage"],
                        diagnostic["code"],
                        diagnostic["message"],
                        diagnostic.get("candidate_id"),
                        diagnostic.get("line_start"),
                        diagnostic.get("line_end"),
                        json.dumps(diagnostic.get("metadata") or {}),
                        created_at,
                        diagnostic.get("fact_origin"),
                        diagnostic.get("flow_domain"),
                    ),
                )

        self._run_graph_store_step("analysis_graph_diagnostics", "insert_diagnostics", insert)

    def _insert_claim_evidence_links(self, conn: sqlite3.Connection, graph: Dict[str, List[Dict[str, Any]]]) -> None:
        def insert() -> None:
            for claim in graph.get("claims") or []:
                for evidence_id in claim.get("evidence_ids") or []:
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO analysis_graph_claim_evidence(claim_id, evidence_id)
                        VALUES (?, ?)
                        """,
                        (claim["id"], evidence_id),
                    )

        self._run_graph_store_step("analysis_graph_claim_evidence", "insert_claim_evidence_links", insert)

    def _insert_edge_evidence_links(self, conn: sqlite3.Connection, graph: Dict[str, List[Dict[str, Any]]]) -> None:
        def insert() -> None:
            for edge in graph.get("edges") or []:
                for evidence_id in edge.get("evidence_ids") or []:
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO analysis_graph_edge_evidence(edge_id, evidence_id)
                        VALUES (?, ?)
                        """,
                        (edge["id"], evidence_id),
                    )

        self._run_graph_store_step("analysis_graph_edge_evidence", "insert_edge_evidence_links", insert)

    def _finalize_graph_replacement(self, conn: sqlite3.Connection, source_id: str, created_at: str) -> None:
        def finalize() -> None:
            self._resolve_source_call_edges(conn, source_id)
            graph_id = self._refresh_graph_state(conn, source_id, created_at)
            if graph_id:
                SemanticIndexStore.mark_current_graph_pending_conn(conn, source_id)
            refresh_overview_for_sources(conn, [source_id])

        self._run_graph_store_step("analysis_graph_state", "finalize_graph_replacement", finalize)

    def mark_file(self, file_id: int, state: Dict[str, Any]) -> None:
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            affected_sources = self._delete_file_analysis(conn, file_id)
            affected_sources.update(self._delete_analysis_identity(conn, state))
            self._upsert_file(conn, file_id, state)
            self._mark_semantic_sources_stale(conn, affected_sources)
            refresh_overview_for_sources(conn, [state["source_id"]])

        self._write_with_busy_retry(write)

    def mark_file_failed_attempt(self, file_id: int, state: Dict[str, Any]) -> None:
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            published = self._published_analysis_identity_row(conn, file_id, state)
            if published is not None:
                self._update_analysis_file_attempt_metadata(conn, int(published["file_id"]), state)
                refresh_overview_for_sources(conn, [state["source_id"]])
                return

            existing_identity = self._analysis_identity_row(conn, file_id, state)
            if existing_identity is not None:
                self._update_analysis_file_row(conn, int(existing_identity["file_id"]), state)
                refresh_overview_for_sources(conn, [state["source_id"]])
                return

            existing_file = conn.execute("SELECT source_id FROM analysis_files WHERE file_id = ?", (file_id,)).fetchone()
            if existing_file is None:
                self._insert_file(conn, file_id, state)
            refresh_overview_for_sources(conn, [state["source_id"]])

        self._write_with_busy_retry(write)

    def cleanup_stale_files(self, source_ids: Optional[List[str]] = None) -> None:
        self.init()
        clauses: list[str] = [
            """
            NOT EXISTS (
                SELECT 1
                FROM files f
                WHERE f.source_id = af.source_id
                  AND f.relative_path = af.relative_path
                  AND f.content_hash = af.content_hash
            )
            """
        ]
        params: list[Any] = []
        if source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            clauses.append(f"af.source_id IN ({placeholders})")
            params.extend(source_ids)
        where = " AND ".join(clauses)
        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT af.file_id, af.source_id FROM analysis_files af
                WHERE {where}
            """,
                params,
            ).fetchall()
            affected_sources: Set[str] = set()
            for row in rows:
                if conn.execute("SELECT 1 FROM analysis_files WHERE file_id = ?", (row["file_id"],)).fetchone() is None:
                    continue
                affected_sources.update(self._delete_file_analysis(conn, int(row["file_id"])))
                conn.execute("DELETE FROM analysis_files WHERE file_id = ?", (row["file_id"],))
            overview_sources = source_ids or sorted({row["source_id"] for row in rows})
            self._mark_semantic_sources_stale(conn, affected_sources)
            refresh_overview_for_sources(conn, overview_sources)

    def files(self, source_id: Optional[str], status: Optional[str], path_contains: Optional[str], limit: int, offset: int) -> Dict[str, Any]:
        clauses, params = [], []
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        if status:
            clauses.append("status = ?")
            params.append(status)
        if path_contains:
            clauses.append("relative_path LIKE ?")
            params.append(f"%{path_contains}%")
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_files {where}", params).fetchone()["count"]
            rows = conn.execute(f"SELECT * FROM analysis_files {where} ORDER BY source_id, relative_path LIMIT ? OFFSET ?", [*params, limit, offset]).fetchall()
        return {"files": [self._file(row) for row in rows], "total": total, "limit": limit, "offset": offset}

    def diagnostics(self, source_id: Optional[str], limit: int, offset: int) -> Dict[str, Any]:
        clauses, params = [], []
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_diagnostics {where}", params).fetchone()["count"]
            rows = conn.execute(
                f"""
                SELECT source_id, severity, stage, code, message, line_start, line_end, metadata_json, created_at
                FROM analysis_graph_diagnostics
                {where}
                ORDER BY created_at DESC, source_id, code
                LIMIT ? OFFSET ?
                """,
                [*params, limit, offset],
            ).fetchall()
        return {"diagnostics": [self._diagnostic_detail(row) for row in rows], "total": total, "limit": limit, "offset": offset}

    def query_current_graph_sources(self) -> List[Dict[str, Any]]:
        self.init()
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            rows = conn.execute(
                """
                WITH known_sources AS (
                    SELECT source_id
                    FROM sources
                    UNION
                    SELECT source_id
                    FROM analysis_graph_state
                    UNION
                    SELECT source_id
                    FROM analysis_graph_nodes
                )
                SELECT known_sources.source_id,
                       COALESCE(sources.display_name, known_sources.source_id) AS display_name,
                       state.graph_id,
                       state.content_identity,
                       COALESCE(state.node_count, 0) AS node_count,
                       COALESCE(state.edge_count, 0) AS edge_count
                FROM known_sources
                LEFT JOIN sources ON sources.source_id = known_sources.source_id
                LEFT JOIN analysis_graph_state state ON state.source_id = known_sources.source_id
                ORDER BY known_sources.source_id
                """
            ).fetchall()
        return [
            {
                "sourceId": row["source_id"],
                "displayName": row["display_name"] or row["source_id"],
                "graphId": row["graph_id"],
                "graphRevision": row["content_identity"],
                "nodeCount": int(row["node_count"] or 0),
                "edgeCount": int(row["edge_count"] or 0),
            }
            for row in rows
        ]

    def query_search_documents(self, source_ids: List[str], limit: int) -> List[Dict[str, Any]]:
        self.init()
        if not source_ids:
            return []
        safe_limit = max(1, min(int(limit or 100), 10000))
        source_placeholders = ",".join("?" for _ in source_ids)
        contract = graph_query_contract()
        claim_status_sql, claim_status_params = sql_in_clause(contract.statuses_for_claim_text())
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            rows = conn.execute(
                f"""
                WITH claim AS (
                    SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                    FROM analysis_graph_claims
                    WHERE status IN ({claim_status_sql})
                    GROUP BY source_id, node_id
                ),
                out_degree AS (
                    SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    GROUP BY source_id, from_node_id
                ),
                in_degree AS (
                    SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE to_node_id IS NOT NULL
                    GROUP BY source_id, to_node_id
                ),
                entry AS (
                    SELECT source_id, node_id, 1 AS entrypoint
                    FROM analysis_graph_claims
                    WHERE claim_kind = ?
                      AND status IN ({entry_status_sql})
                    GROUP BY source_id, node_id
                ),
                external_target AS (
                    SELECT source_id,
                           from_node_id AS node_id,
                           group_concat(
                               COALESCE(
                                   json_extract(unresolved_target_json, '$.name'),
                                   json_extract(unresolved_target_json, '$.qualifiedName'),
                                   unresolved_target_json
                               ),
                               ' '
                           ) AS external_target_text
                    FROM analysis_graph_edges
                    WHERE resolution_status = ?
                      AND unresolved_target_json IS NOT NULL
                    GROUP BY source_id, from_node_id
                )
                SELECT n.*,
                       sources.display_name AS source_display_name,
                       af.relative_path,
                       claim.summary,
                       external_target.external_target_text,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       COALESCE(entry.entrypoint, 0) AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN sources ON sources.source_id = n.source_id
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN claim
                  ON claim.source_id = n.source_id
                 AND claim.node_id = n.id
                LEFT JOIN out_degree
                  ON out_degree.source_id = n.source_id
                 AND out_degree.node_id = n.id
                LEFT JOIN in_degree
                  ON in_degree.source_id = n.source_id
                 AND in_degree.node_id = n.id
                LEFT JOIN entry
                  ON entry.source_id = n.source_id
                 AND entry.node_id = n.id
                LEFT JOIN external_target
                  ON external_target.source_id = n.source_id
                 AND external_target.node_id = n.id
                WHERE n.source_id IN ({source_placeholders})
                  AND {self._inventory_membership_graph_node_clause("n")}
                ORDER BY n.source_id, lower(COALESCE(n.display_name, n.qualified_name, n.name, n.id)), n.id
                LIMIT ?
                """,
                [
                    *claim_status_params,
                    contract.entrypoint_claim_kind,
                    *entry_status_params,
                    contract.external_target_status,
                    *source_ids,
                    safe_limit,
                ],
            ).fetchall()
            revision_by_source = self._graph_identity_by_source(conn, source_ids)
        documents: List[Dict[str, Any]] = []
        for row in rows:
            row_dict = self._row_dict(row)
            projected = self._graph_node_projection(row_dict)
            projected.update(
                {
                    "graphId": revision_by_source.get(row["source_id"], {}).get("graphId"),
                    "graphRevision": revision_by_source.get(row["source_id"], {}).get("graphRevision"),
                    "sourceDisplayName": row["source_display_name"] or row["source_id"],
                    "displayName": row["display_name"],
                    "stableKey": row["stable_key"],
                    "summary": row["summary"],
                    "metadataText": row["external_target_text"],
                    "degree": int(row["graph_degree"] or 0),
                }
            )
            documents.append(projected)
        return documents

    def query_search_documents_by_node_ids(self, source_node_pairs: List[tuple[str, str]], limit: int) -> List[Dict[str, Any]]:
        self.init()
        if not source_node_pairs:
            return []
        requested: List[tuple[str, str]] = []
        seen: Set[tuple[str, str]] = set()
        for source_id, node_id in source_node_pairs:
            key = (str(source_id or ""), str(node_id or ""))
            if not key[0] or not key[1] or key in seen:
                continue
            seen.add(key)
            requested.append(key)
        if not requested:
            return []
        safe_limit = max(1, min(int(limit or len(requested) or 1), len(requested), 1000))
        pair_clauses: List[str] = []
        pair_params: List[Any] = []
        for source_id, node_id in requested[:safe_limit]:
            pair_clauses.append("(n.source_id = ? AND n.id = ?)")
            pair_params.extend([source_id, node_id])
        source_ids = sorted({source_id for source_id, _ in requested[:safe_limit]})
        contract = graph_query_contract()
        claim_status_sql, claim_status_params = sql_in_clause(contract.statuses_for_claim_text())
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            rows = conn.execute(
                f"""
                WITH claim AS (
                    SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                    FROM analysis_graph_claims
                    WHERE status IN ({claim_status_sql})
                    GROUP BY source_id, node_id
                ),
                out_degree AS (
                    SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    GROUP BY source_id, from_node_id
                ),
                in_degree AS (
                    SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE to_node_id IS NOT NULL
                    GROUP BY source_id, to_node_id
                ),
                entry AS (
                    SELECT source_id, node_id, 1 AS entrypoint
                    FROM analysis_graph_claims
                    WHERE claim_kind = ?
                      AND status IN ({entry_status_sql})
                    GROUP BY source_id, node_id
                ),
                external_target AS (
                    SELECT source_id,
                           from_node_id AS node_id,
                           group_concat(
                               COALESCE(
                                   json_extract(unresolved_target_json, '$.name'),
                                   json_extract(unresolved_target_json, '$.qualifiedName'),
                                   unresolved_target_json
                               ),
                               ' '
                           ) AS external_target_text
                    FROM analysis_graph_edges
                    WHERE resolution_status = ?
                      AND unresolved_target_json IS NOT NULL
                    GROUP BY source_id, from_node_id
                )
                SELECT n.*,
                       sources.display_name AS source_display_name,
                       af.relative_path,
                       claim.summary,
                       external_target.external_target_text,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       COALESCE(entry.entrypoint, 0) AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN sources ON sources.source_id = n.source_id
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN claim
                  ON claim.source_id = n.source_id
                 AND claim.node_id = n.id
                LEFT JOIN out_degree
                  ON out_degree.source_id = n.source_id
                 AND out_degree.node_id = n.id
                LEFT JOIN in_degree
                  ON in_degree.source_id = n.source_id
                 AND in_degree.node_id = n.id
                LEFT JOIN entry
                  ON entry.source_id = n.source_id
                 AND entry.node_id = n.id
                LEFT JOIN external_target
                  ON external_target.source_id = n.source_id
                 AND external_target.node_id = n.id
                WHERE ({' OR '.join(pair_clauses)})
                  AND {self._inventory_membership_graph_node_clause("n")}
                ORDER BY n.source_id, lower(COALESCE(n.display_name, n.qualified_name, n.name, n.id)), n.id
                LIMIT ?
                """,
                [
                    *claim_status_params,
                    contract.entrypoint_claim_kind,
                    *entry_status_params,
                    contract.external_target_status,
                    *pair_params,
                    safe_limit,
                ],
            ).fetchall()
            revision_by_source = self._graph_identity_by_source(conn, source_ids)
        documents: List[Dict[str, Any]] = []
        for row in rows:
            row_dict = self._row_dict(row)
            projected = self._graph_node_projection(row_dict)
            projected.update(
                {
                    "graphId": revision_by_source.get(row["source_id"], {}).get("graphId"),
                    "graphRevision": revision_by_source.get(row["source_id"], {}).get("graphRevision"),
                    "sourceDisplayName": row["source_display_name"] or row["source_id"],
                    "displayName": row["display_name"],
                    "stableKey": row["stable_key"],
                    "summary": row["summary"],
                    "metadataText": row["external_target_text"],
                    "degree": int(row["graph_degree"] or 0),
                }
            )
            documents.append(projected)
        return documents

    def query_anchor_candidates(self, tokens: List[str], source_ids: List[str], limit: int) -> List[Dict[str, Any]]:
        self.init()
        if not tokens or not source_ids:
            return []
        safe_limit = max(1, min(int(limit or 100), 1000))
        source_placeholders = ",".join("?" for _ in source_ids)
        contract = graph_query_contract()
        claim_status_sql, claim_status_params = sql_in_clause(contract.statuses_for_claim_text())
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        token_clauses: List[str] = []
        token_params: List[Any] = []
        for token in tokens[:12]:
            pattern = f"%{str(token).lower()}%"
            token_clauses.append(
                """(
                    lower(n.id) LIKE ?
                    OR lower(n.stable_key) LIKE ?
                    OR lower(n.node_kind) LIKE ?
                    OR lower(n.name) LIKE ?
                    OR lower(COALESCE(n.qualified_name, '')) LIKE ?
                    OR lower(COALESCE(n.display_name, '')) LIKE ?
                    OR lower(COALESCE(af.relative_path, '')) LIKE ?
                    OR lower(COALESCE(claim.summary, '')) LIKE ?
                    OR lower(COALESCE(external_target.external_target_text, '')) LIKE ?
                )"""
            )
            token_params.extend([pattern, pattern, pattern, pattern, pattern, pattern, pattern, pattern, pattern])
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            rows = conn.execute(
                f"""
                WITH claim AS (
                    SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                    FROM analysis_graph_claims
                    WHERE status IN ({claim_status_sql})
                    GROUP BY source_id, node_id
                ),
                out_degree AS (
                    SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    GROUP BY source_id, from_node_id
                ),
                in_degree AS (
                    SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE to_node_id IS NOT NULL
                    GROUP BY source_id, to_node_id
                ),
                entry AS (
                    SELECT source_id, node_id, 1 AS entrypoint
                    FROM analysis_graph_claims
                    WHERE claim_kind = ?
                      AND status IN ({entry_status_sql})
                    GROUP BY source_id, node_id
                ),
                external_target AS (
                    SELECT source_id,
                           from_node_id AS node_id,
                           group_concat(
                               COALESCE(
                                   json_extract(unresolved_target_json, '$.name'),
                                   json_extract(unresolved_target_json, '$.qualifiedName'),
                                   unresolved_target_json
                               ),
                               ' '
                           ) AS external_target_text
                    FROM analysis_graph_edges
                    WHERE resolution_status = ?
                      AND unresolved_target_json IS NOT NULL
                    GROUP BY source_id, from_node_id
                )
                SELECT n.*,
                       sources.display_name AS source_display_name,
                       af.relative_path,
                       claim.summary,
                       external_target.external_target_text,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       COALESCE(entry.entrypoint, 0) AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN sources ON sources.source_id = n.source_id
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN claim
                  ON claim.source_id = n.source_id
                 AND claim.node_id = n.id
                LEFT JOIN out_degree
                  ON out_degree.source_id = n.source_id
                 AND out_degree.node_id = n.id
                LEFT JOIN in_degree
                  ON in_degree.source_id = n.source_id
                 AND in_degree.node_id = n.id
                LEFT JOIN entry
                  ON entry.source_id = n.source_id
                 AND entry.node_id = n.id
                LEFT JOIN external_target
                  ON external_target.source_id = n.source_id
                 AND external_target.node_id = n.id
                WHERE n.source_id IN ({source_placeholders})
                  AND {self._inventory_membership_graph_node_clause("n")}
                  AND ({" OR ".join(token_clauses)})
                ORDER BY n.confidence DESC, graph_degree DESC, n.source_id, lower(COALESCE(n.display_name, n.qualified_name, n.name, n.id)), n.id
                LIMIT ?
                """,
                [
                    *claim_status_params,
                    contract.entrypoint_claim_kind,
                    *entry_status_params,
                    contract.external_target_status,
                    *source_ids,
                    *token_params,
                    safe_limit,
                ],
            ).fetchall()
            revision_by_source = self._graph_identity_by_source(conn, source_ids)
        candidates: List[Dict[str, Any]] = []
        for row in rows:
            row_dict = self._row_dict(row)
            projected = self._graph_node_projection(row_dict)
            projected.update(
                {
                    "graphId": revision_by_source.get(row["source_id"], {}).get("graphId"),
                    "graphRevision": revision_by_source.get(row["source_id"], {}).get("graphRevision"),
                    "sourceDisplayName": row["source_display_name"] or row["source_id"],
                    "displayName": row["display_name"],
                    "stableKey": row["stable_key"],
                    "summary": row["summary"],
                    "metadataText": row["external_target_text"],
                    "degree": int(row["graph_degree"] or 0),
                }
            )
            candidates.append(projected)
        return candidates

    def query_anchor_expansion(
        self,
        source_node_pairs: Sequence[AnchorExpansionRequest],
        max_per_anchor: int = 30,
        max_total: int = 200,
    ) -> AnchorExpansionBundle:
        self.init()
        requested = self._anchor_expansion_requested_pairs(source_node_pairs)
        if not requested:
            return AnchorExpansionBundle()
        safe_max_total = max(1, min(int(max_total or 1), 1000))
        safe_relation_limit = max(1, min(safe_max_total + max(1, int(max_per_anchor or 1)) * len(requested), 2000))
        grouped: Dict[str, Set[str]] = {}
        for source_id, node_id in requested:
            grouped.setdefault(source_id, set()).add(node_id)

        nodes: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        entrypoint_hints: List[Dict[str, Any]] = []
        truncated = False
        contract = graph_query_contract()
        declares_edge_type = contract.required_edge_type("DECLARES")
        uses_field_edge_type = contract.required_edge_type("USES_FIELD")
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())

        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            node_ids_by_source: Dict[str, Set[str]] = {source_id: set(ids) for source_id, ids in grouped.items()}
            first_hop_ids_by_source: Dict[str, Set[str]] = {source_id: set() for source_id in grouped}

            for source_id, anchor_ids in sorted(grouped.items()):
                anchor_nodes = self._query_anchor_expansion_nodes(conn, source_id, anchor_ids)
                nodes.extend(anchor_nodes)
                for node in anchor_nodes:
                    parent_node_id = str(node.get("parentNodeId") or "")
                    if parent_node_id:
                        node_ids_by_source.setdefault(source_id, set()).add(parent_node_id)

                ids = sorted(anchor_ids)
                placeholders = ",".join("?" for _ in ids)
                rows = conn.execute(
                    f"""
                    SELECT e.*,
                           fn.display_name AS from_display_name,
                           fn.qualified_name AS from_qualified_name,
                           fn.name AS from_name,
                           tn.display_name AS to_display_name,
                           tn.qualified_name AS to_qualified_name,
                           tn.name AS to_name
                    FROM analysis_graph_edges e
                    LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
                    LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
                    WHERE e.source_id = ?
                      AND e.edge_type IN (?, ?)
                      AND e.status IN ({current_status_sql})
                      AND e.resolution_status = ?
                      AND {self._inventory_membership_graph_edge_clause("e")}
                      AND (e.from_node_id IN ({placeholders}) OR e.to_node_id IN ({placeholders}))
                    ORDER BY e.edge_type, e.from_node_id, e.to_node_id, e.id
                    LIMIT ?
                    """,
                    [
                        source_id,
                        declares_edge_type,
                        uses_field_edge_type,
                        *current_status_params,
                        contract.resolved_status,
                        *ids,
                        *ids,
                        safe_relation_limit + 1,
                    ],
                ).fetchall()
                if len(rows) > safe_relation_limit:
                    truncated = True
                    rows = rows[:safe_relation_limit]
                for row in rows:
                    edges.append(self._anchor_expansion_edge_projection(self._row_dict(row)))
                    for node_id in (str(row["from_node_id"] or ""), str(row["to_node_id"] or "")):
                        if node_id:
                            node_ids_by_source.setdefault(source_id, set()).add(node_id)
                            if node_id not in anchor_ids:
                                first_hop_ids_by_source.setdefault(source_id, set()).add(node_id)

            remaining_edges = max(0, safe_relation_limit - len(edges))
            for source_id, first_hop_ids in sorted(first_hop_ids_by_source.items()):
                if remaining_edges <= 0 or not first_hop_ids:
                    break
                ids = sorted(first_hop_ids)
                placeholders = ",".join("?" for _ in ids)
                rows = conn.execute(
                    f"""
                    SELECT e.*,
                           fn.display_name AS from_display_name,
                           fn.qualified_name AS from_qualified_name,
                           fn.name AS from_name,
                           tn.display_name AS to_display_name,
                           tn.qualified_name AS to_qualified_name,
                           tn.name AS to_name
                    FROM analysis_graph_edges e
                    LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
                    LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
                    WHERE e.source_id = ?
                      AND e.edge_type = ?
                      AND e.status IN ({current_status_sql})
                      AND e.resolution_status = ?
                      AND {self._inventory_membership_graph_edge_clause("e")}
                      AND e.from_node_id IN ({placeholders})
                    ORDER BY e.edge_type, e.from_node_id, e.to_node_id, e.id
                    LIMIT ?
                    """,
                    [
                        source_id,
                        declares_edge_type,
                        *current_status_params,
                        contract.resolved_status,
                        *ids,
                        remaining_edges + 1,
                    ],
                ).fetchall()
                if len(rows) > remaining_edges:
                    truncated = True
                    rows = rows[:remaining_edges]
                remaining_edges -= len(rows)
                for row in rows:
                    edges.append(self._anchor_expansion_edge_projection(self._row_dict(row)))
                    for node_id in (str(row["from_node_id"] or ""), str(row["to_node_id"] or "")):
                        if node_id:
                            node_ids_by_source.setdefault(source_id, set()).add(node_id)

            for source_id, node_ids in sorted(node_ids_by_source.items()):
                nodes.extend(self._query_anchor_expansion_nodes(conn, source_id, node_ids))
                entrypoint_hints.extend(self._query_anchor_expansion_entrypoint_hints(conn, source_id, node_ids))

            nodes = self._dedupe_by_id(nodes, "nodeId")
            edges = self._dedupe_by_id(edges, "edgeId")
            entrypoint_hints = self._dedupe_by_id(entrypoint_hints, "nodeId")
            self._attach_current_graph_identity(conn, nodes)
            self._attach_current_graph_identity(conn, edges)
            self._attach_current_graph_identity(conn, entrypoint_hints)

        return AnchorExpansionBundle(
            nodes=tuple(self._anchor_expansion_node(item) for item in nodes),
            edges=tuple(self._anchor_expansion_edge(item) for item in edges),
            entrypoint_hints=tuple(self._anchor_entrypoint_hint(item) for item in entrypoint_hints),
            truncated=truncated,
        )

    def query_graph_slice(self, anchors: List[Dict[str, Any]], depth: int) -> Dict[str, List[Dict[str, Any]]]:
        self.init()
        safe_depth = max(1, min(int(depth or 1), 4))
        grouped: Dict[str, Set[str]] = {}
        for anchor in anchors:
            source_id = str(anchor.get("sourceId") or "")
            node_id = str(anchor.get("nodeId") or anchor.get("id") or "")
            if source_id and node_id:
                grouped.setdefault(source_id, set()).add(node_id)
        nodes: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        evidence: List[Dict[str, Any]] = []
        unresolved: List[Dict[str, Any]] = []
        unresolved_statuses = set(graph_query_contract().unresolved_resolution_statuses())
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            for source_id, anchor_ids in grouped.items():
                selected_node_ids = set(anchor_ids)
                frontier = set(anchor_ids)
                unresolved_edge_ids: Set[str] = set()
                for _ in range(safe_depth):
                    if not frontier:
                        break
                    frontier_list = sorted(frontier)
                    frontier_placeholders = ",".join("?" for _ in frontier_list)
                    edge_rows = conn.execute(
                        f"""
                        SELECT id, from_node_id, to_node_id, resolution_status
                        FROM analysis_graph_edges
                        WHERE source_id = ?
                          AND (from_node_id IN ({frontier_placeholders}) OR to_node_id IN ({frontier_placeholders}))
                        ORDER BY id
                        LIMIT 500
                        """,
                        [source_id, *frontier_list, *frontier_list],
                    ).fetchall()
                    next_frontier: Set[str] = set()
                    for edge in edge_rows:
                        from_node_id = str(edge["from_node_id"])
                        to_node_id = str(edge["to_node_id"] or "")
                        if not to_node_id or edge["resolution_status"] in unresolved_statuses:
                            unresolved_edge_ids.add(str(edge["id"]))
                        for node_id in (from_node_id, to_node_id):
                            if node_id and node_id not in selected_node_ids:
                                selected_node_ids.add(node_id)
                                next_frontier.add(node_id)
                    frontier = next_frontier
                nodes.extend(self._query_slice_nodes(conn, source_id, selected_node_ids))
                slice_edges = self._query_slice_edges(conn, source_id, selected_node_ids)
                edges.extend(slice_edges)
                unresolved.extend(self._query_unresolved_slice_edges(conn, source_id, selected_node_ids, unresolved_edge_ids))
                evidence.extend(self._query_slice_evidence(conn, source_id, selected_node_ids, {edge["id"] for edge in slice_edges}))
        nodes = self._dedupe_by_id(nodes, "id")
        edges = self._dedupe_by_id(edges, "id")
        evidence = self._dedupe_by_id(evidence, "id")
        self._attach_current_graph_identity(conn, nodes)
        self._attach_current_graph_identity(conn, edges)
        self._attach_current_graph_identity(conn, evidence)
        unresolved = self._dedupe_by_id(unresolved, "id")
        self._attach_current_graph_identity(conn, unresolved)
        external = [edge for edge in unresolved if edge.get("external")]
        verified_paths = self._verified_paths_from_evidence(evidence)
        return {"nodes": nodes, "edges": edges, "evidence": evidence, "unresolved": unresolved, "external": external, "verifiedPaths": verified_paths}

    def load_call_flow_graph(
        self,
        source_scopes: Sequence[FlowGraphSourceScope],
        max_edges: int = 2000,
        max_evidence: int = 25,
    ) -> FlowGraphBundle:
        self.init()
        safe_max_edges = max(1, min(int(max_edges or 1), 10000))
        safe_max_evidence = max(0, min(int(max_evidence or 0), 500))
        grouped: Dict[str, Set[str]] = {}
        requested_graph_by_source: Dict[str, str] = {}
        for scope in source_scopes:
            if not isinstance(scope, FlowGraphSourceScope):
                continue
            source_id = str(scope.source_id or "")
            if not source_id:
                continue
            requested_graph_by_source.setdefault(source_id, str(scope.graph_id or ""))
            anchor_ids = {str(node_id) for node_id in scope.node_ids if str(node_id)}
            grouped.setdefault(source_id, set()).update(anchor_ids)
        if not grouped:
            return FlowGraphBundle()

        nodes: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        evidence: List[Dict[str, Any]] = []
        truncated = False
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            identity_by_source = self._graph_identity_by_source(conn, sorted(grouped))
            active_grouped: Dict[str, Set[str]] = {}
            for source_id, anchor_ids in grouped.items():
                identity = identity_by_source.get(source_id) or {}
                requested_graph_id = requested_graph_by_source.get(source_id) or ""
                current_graph_id = str(identity.get("graphId") or "")
                if requested_graph_id and current_graph_id and requested_graph_id != current_graph_id:
                    continue
                active_grouped[source_id] = set(anchor_ids)
            if not active_grouped:
                return FlowGraphBundle()
            remaining_edges = safe_max_edges + 1
            edge_ids_by_source: Dict[str, Set[str]] = {}
            node_ids_by_source: Dict[str, Set[str]] = {source_id: set(anchor_ids) for source_id, anchor_ids in active_grouped.items()}
            for source_id, anchor_ids in sorted(active_grouped.items()):
                if remaining_edges <= 0:
                    truncated = True
                    break
                node_ids = node_ids_by_source.setdefault(source_id, set())
                frontier = {node_id for node_id in anchor_ids if node_id}
                scope_edge_ids = edge_ids_by_source.setdefault(source_id, set())
                while frontier and remaining_edges > 0:
                    frontier_list = sorted(frontier)
                    frontier_placeholders = ",".join("?" for _ in frontier_list)
                    edge_rows = conn.execute(
                        f"""
                        SELECT e.*,
                               fn.display_name AS from_display_name,
                               fn.qualified_name AS from_qualified_name,
                               fn.name AS from_name,
                               tn.display_name AS to_display_name,
                               tn.qualified_name AS to_qualified_name,
                               tn.name AS to_name
                        FROM analysis_graph_edges e
                        LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
                        LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
                        WHERE e.source_id = ?
                          AND e.edge_type = ?
                          AND e.status IN ({current_status_sql})
                          AND (e.from_node_id IN ({frontier_placeholders}) OR e.to_node_id IN ({frontier_placeholders}))
                        ORDER BY e.id
                        LIMIT ?
                        """,
                        [source_id, contract.calls_edge_type, *current_status_params, *frontier_list, *frontier_list, remaining_edges],
                    ).fetchall()
                    if len(edge_rows) >= remaining_edges:
                        truncated = True
                        edge_rows = edge_rows[: max(0, remaining_edges - 1)]
                    remaining_edges -= len(edge_rows)
                    next_frontier: Set[str] = set()
                    for row in edge_rows:
                        edge_id = str(row["id"])
                        if edge_id in scope_edge_ids:
                            continue
                        item = self._graph_edge_projection(self._row_dict(row))
                        item["sourceId"] = row["source_id"]
                        edges.append(item)
                        scope_edge_ids.add(edge_id)
                        for node_id in (str(row["from_node_id"]), str(row["to_node_id"] or "")):
                            if node_id and node_id not in node_ids:
                                node_ids.add(node_id)
                                next_frontier.add(node_id)
                    if truncated or not next_frontier:
                        break
                    frontier = next_frontier

            for source_id, node_ids in sorted(node_ids_by_source.items()):
                nodes.extend(self._query_slice_nodes(conn, source_id, node_ids))

            remaining_evidence = safe_max_evidence
            for source_id, node_ids in sorted(node_ids_by_source.items()):
                if remaining_evidence <= 0:
                    break
                scope_evidence = self._query_flow_path_evidence(
                    conn,
                    source_id,
                    node_ids,
                    edge_ids_by_source.get(source_id, set()),
                    remaining_evidence,
                )
                evidence.extend(scope_evidence)
                remaining_evidence -= len(scope_evidence)

            nodes = self._dedupe_by_id(nodes, "id")
            edges = self._dedupe_by_id(edges, "id")
            evidence = self._dedupe_by_id(evidence, "id")
            self._attach_current_graph_identity(conn, nodes)
            self._attach_current_graph_identity(conn, edges)
            self._attach_current_graph_identity(conn, evidence)
        return self._flow_graph_bundle_from_public_graph(nodes, edges, evidence, truncated)

    def load_call_adjacency_for_sources(
        self,
        source_scopes: List[Dict[str, Any]],
        max_edges: int = 2000,
        max_evidence: int = 25,
    ) -> Dict[str, Any]:
        bundle = self.load_call_flow_graph(
            self._legacy_flow_graph_source_scopes(source_scopes),
            max_edges=max_edges,
            max_evidence=max_evidence,
        )
        public_bundle = flow_graph_bundle_to_public_bundle(bundle)
        return {
            "nodes": list(public_bundle["nodes"]),
            "edges": list(public_bundle["edges"]),
            "evidence": list(public_bundle["evidence"]),
            "unresolved": list(public_bundle["unresolved"]),
            "external": list(public_bundle["external"]),
            "verifiedPaths": list(public_bundle["verifiedPaths"]),
            "truncated": bool(public_bundle["truncated"]),
        }

    def _legacy_flow_graph_source_scopes(self, source_scopes: Sequence[Dict[str, Any]]) -> List[FlowGraphSourceScope]:
        scopes: List[FlowGraphSourceScope] = []
        for scope in source_scopes:
            source_id = str(scope.get("sourceId") or "")
            if not source_id:
                continue
            node_ids = tuple(sorted(str(node_id) for node_id in scope.get("nodeIds") or [] if str(node_id)))
            scopes.append(
                FlowGraphSourceScope(
                    source_id=source_id,
                    graph_id=str(scope.get("graphId") or ""),
                    graph_revision=str(scope.get("graphRevision")) if scope.get("graphRevision") else None,
                    node_ids=node_ids,
                )
            )
        return scopes

    def _flow_graph_bundle_from_public_graph(
        self,
        nodes: Sequence[Dict[str, Any]],
        edges: Sequence[Dict[str, Any]],
        evidence: Sequence[Dict[str, Any]],
        truncated: bool,
    ) -> FlowGraphBundle:
        flow_evidence = tuple(
            item
            for item in (self._flow_graph_evidence_from_public_graph(raw) for raw in evidence)
            if item is not None
        )
        evidence_ids_by_edge: Dict[str, List[str]] = defaultdict(list)
        for item in flow_evidence:
            if item.edge_id:
                evidence_ids_by_edge[item.edge_id].append(item.evidence_id)
        flow_edges = tuple(
            edge
            for edge in (self._flow_graph_edge_from_public_graph(raw, evidence_ids_by_edge) for raw in edges)
            if edge is not None
        )
        flow_nodes = tuple(
            node
            for node in (self._flow_graph_node_from_public_graph(raw) for raw in nodes)
            if node is not None
        )
        return FlowGraphBundle(nodes=flow_nodes, edges=flow_edges, evidence=flow_evidence, truncated=truncated)

    def _flow_graph_node_from_public_graph(self, item: Dict[str, Any]) -> FlowGraphNode | None:
        node_id = str(item.get("id") or "")
        source_id = str(item.get("sourceId") or "")
        graph_id = str(item.get("graphId") or "")
        if not node_id or not source_id or not graph_id:
            return None
        return FlowGraphNode(
            source_id=source_id,
            graph_id=graph_id,
            graph_revision=str(item.get("graphRevision")) if item.get("graphRevision") else None,
            node_id=node_id,
            stable_key=str(item.get("stableKey") or node_id),
            node_kind=str(item.get("nodeKind") or ""),
            label=str(item.get("label") or item.get("name") or node_id),
            qualified_name=str(item.get("qualifiedName")) if item.get("qualifiedName") else None,
            relative_path=str(item.get("relativePath")) if item.get("relativePath") else None,
            summary=str(item.get("summary")) if item.get("summary") else None,
            entrypoint=bool(item.get("entrypoint")),
        )

    def _flow_graph_edge_from_public_graph(self, item: Dict[str, Any], evidence_ids_by_edge: Dict[str, List[str]]) -> FlowGraphEdge | None:
        edge_id = str(item.get("id") or "")
        source_id = str(item.get("sourceId") or "")
        graph_id = str(item.get("graphId") or "")
        from_node_id = str(item.get("fromNodeId") or "")
        if not edge_id or not source_id or not graph_id or not from_node_id:
            return None
        return FlowGraphEdge(
            source_id=source_id,
            graph_id=graph_id,
            graph_revision=str(item.get("graphRevision")) if item.get("graphRevision") else None,
            edge_id=edge_id,
            edge_type=str(item.get("edgeType") or ""),
            from_node_id=from_node_id,
            to_node_id=str(item.get("toNodeId")) if item.get("toNodeId") else None,
            resolution_status=str(item.get("resolutionStatus") or "RESOLVED"),
            external=bool(item.get("external")) or str(item.get("resolutionStatus") or "").upper() == graph_query_contract().external_target_status,
            unresolved_target=item.get("unresolvedTarget") if isinstance(item.get("unresolvedTarget"), dict) else None,
            evidence_ids=tuple(evidence_ids_by_edge.get(edge_id) or ()),
        )

    def _flow_graph_evidence_from_public_graph(self, item: Dict[str, Any]) -> FlowGraphEvidence | None:
        evidence_id = str(item.get("id") or "")
        source_id = str(item.get("sourceId") or "")
        graph_id = str(item.get("graphId") or "")
        if not evidence_id or not source_id or not graph_id:
            return None
        return FlowGraphEvidence(
            source_id=source_id,
            graph_id=graph_id,
            graph_revision=str(item.get("graphRevision")) if item.get("graphRevision") else None,
            evidence_id=evidence_id,
            node_id=str(item.get("nodeId")) if item.get("nodeId") else None,
            edge_id=str(item.get("edgeId")) if item.get("edgeId") else None,
            relative_path=str(item.get("relativePath")) if item.get("relativePath") else None,
            line_start=int(item.get("lineStart")) if item.get("lineStart") is not None else None,
            line_end=int(item.get("lineEnd")) if item.get("lineEnd") is not None else None,
            text=str(item.get("excerpt")) if item.get("excerpt") else None,
        )

    def _attach_current_graph_identity(self, conn: sqlite3.Connection, items: List[Dict[str, Any]]) -> None:
        source_ids = sorted({str(item.get("sourceId") or "") for item in items if item.get("sourceId")})
        if not source_ids:
            return
        identity_by_source = self._graph_identity_by_source(conn, source_ids)
        for item in items:
            identity = identity_by_source.get(str(item.get("sourceId") or ""))
            if not identity:
                continue
            if not item.get("graphId"):
                item["graphId"] = identity.get("graphId")
            if not item.get("graphRevision"):
                item["graphRevision"] = identity.get("graphRevision")

    def _anchor_expansion_requested_pairs(self, source_node_pairs: Sequence[AnchorExpansionRequest]) -> List[tuple[str, str]]:
        requested: List[tuple[str, str]] = []
        seen: Set[tuple[str, str]] = set()
        for item in source_node_pairs or ():
            if not isinstance(item, AnchorExpansionRequest):
                continue
            source_id = str(item.source_id or "")
            graph_id = str(item.graph_id or "")
            node_id = str(item.node_id or "")
            key = (source_id, node_id)
            if not source_id or not graph_id or not node_id or key in seen:
                continue
            seen.add(key)
            requested.append(key)
        return requested

    def _query_anchor_expansion_nodes(self, conn: sqlite3.Connection, source_id: str, node_ids: Set[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        ids = sorted(node_ids)
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT n.*, af.relative_path,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                   CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint
            FROM analysis_graph_nodes n
            LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
            LEFT JOIN (
                SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY source_id, from_node_id
            ) out_degree ON out_degree.source_id = n.source_id AND out_degree.node_id = n.id
            LEFT JOIN (
                SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                WHERE to_node_id IS NOT NULL
                GROUP BY source_id, to_node_id
            ) in_degree ON in_degree.source_id = n.source_id AND in_degree.node_id = n.id
            LEFT JOIN analysis_graph_claims entry
              ON entry.source_id = n.source_id
             AND entry.node_id = n.id
             AND entry.claim_kind = ?
             AND entry.status IN ({entry_status_sql})
            WHERE n.source_id = ?
              AND n.id IN ({placeholders})
              AND {self._inventory_membership_graph_node_clause("n")}
            ORDER BY n.id
            """,
            [contract.entrypoint_claim_kind, *entry_status_params, source_id, *ids],
        ).fetchall()
        return [self._anchor_expansion_node_projection(self._row_dict(row)) for row in rows]

    def _query_anchor_expansion_entrypoint_hints(self, conn: sqlite3.Connection, source_id: str, node_ids: Set[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        ids = sorted(node_ids)
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT source_id, node_id, id
            FROM analysis_graph_claims
            WHERE source_id = ?
              AND node_id IN ({placeholders})
              AND claim_kind = ?
              AND status IN ({entry_status_sql})
            ORDER BY node_id, id
            """,
            [source_id, *ids, contract.entrypoint_claim_kind, *entry_status_params],
        ).fetchall()
        return [
            {
                "sourceId": row["source_id"],
                "graphId": "",
                "graphRevision": "",
                "nodeId": row["node_id"],
                "claimId": row["id"],
            }
            for row in rows
        ]

    def _query_slice_nodes(self, conn: sqlite3.Connection, source_id: str, node_ids: Set[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        ids = sorted(node_ids)
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        claim_status_sql, claim_status_params = sql_in_clause(contract.statuses_for_claim_text())
        rows = conn.execute(
            f"""
            WITH claim AS (
                SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                FROM analysis_graph_claims
                WHERE status IN ({claim_status_sql})
                GROUP BY source_id, node_id
            )
            SELECT n.*, af.relative_path,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                   CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint,
                   claim.summary AS summary
            FROM analysis_graph_nodes n
            LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
            LEFT JOIN (
                SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY source_id, from_node_id
            ) out_degree ON out_degree.source_id = n.source_id AND out_degree.node_id = n.id
            LEFT JOIN (
                SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                WHERE to_node_id IS NOT NULL
                GROUP BY source_id, to_node_id
            ) in_degree ON in_degree.source_id = n.source_id AND in_degree.node_id = n.id
            LEFT JOIN analysis_graph_claims entry
              ON entry.source_id = n.source_id
             AND entry.node_id = n.id
             AND entry.claim_kind = ?
             AND entry.status IN ({entry_status_sql})
            LEFT JOIN claim
              ON claim.source_id = n.source_id
             AND claim.node_id = n.id
            WHERE n.source_id = ?
              AND n.id IN ({placeholders})
            ORDER BY n.id
            """,
            [*claim_status_params, contract.entrypoint_claim_kind, *entry_status_params, source_id, *ids],
        ).fetchall()
        return [self._graph_node_projection(self._row_dict(row)) for row in rows]

    def _query_slice_edges(self, conn: sqlite3.Connection, source_id: str, node_ids: Set[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        ids = sorted(node_ids)
        placeholders = ",".join("?" for _ in ids)
        rows = conn.execute(
            f"""
            SELECT e.*,
                   fn.display_name AS from_display_name,
                   fn.qualified_name AS from_qualified_name,
                   fn.name AS from_name,
                   tn.display_name AS to_display_name,
                   tn.qualified_name AS to_qualified_name,
                   tn.name AS to_name
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
            WHERE e.source_id = ?
              AND e.from_node_id IN ({placeholders})
              AND e.to_node_id IN ({placeholders})
            ORDER BY e.id
            LIMIT 1000
            """,
            [source_id, *ids, *ids],
        ).fetchall()
        return [self._graph_edge_projection(self._row_dict(row)) for row in rows]

    def _query_unresolved_slice_edges(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        node_ids: Set[str],
        unresolved_edge_ids: Set[str],
    ) -> List[Dict[str, Any]]:
        if not node_ids and not unresolved_edge_ids:
            return []
        clauses: List[str] = ["e.source_id = ?"]
        params: List[Any] = [source_id]
        disjunctions: List[str] = []
        if node_ids:
            ids = sorted(node_ids)
            placeholders = ",".join("?" for _ in ids)
            unresolved_sql, unresolved_params = sql_in_clause(graph_query_contract().unresolved_resolution_statuses())
            disjunctions.append(f"(e.from_node_id IN ({placeholders}) AND (e.to_node_id IS NULL OR e.resolution_status IN ({unresolved_sql})))")
            params.extend(ids)
            params.extend(unresolved_params)
        if unresolved_edge_ids:
            edge_ids = sorted(unresolved_edge_ids)
            placeholders = ",".join("?" for _ in edge_ids)
            disjunctions.append(f"e.id IN ({placeholders})")
            params.extend(edge_ids)
        clauses.append(f"({' OR '.join(disjunctions)})")
        rows = conn.execute(
            f"""
            SELECT e.*,
                   fn.display_name AS from_display_name,
                   fn.qualified_name AS from_qualified_name,
                   fn.name AS from_name,
                   tn.display_name AS to_display_name,
                   tn.qualified_name AS to_qualified_name,
                   tn.name AS to_name
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
            WHERE {" AND ".join(clauses)}
            ORDER BY e.id
            LIMIT 200
            """,
            params,
        ).fetchall()
        return [self._graph_edge_projection(self._row_dict(row)) for row in rows]

    def _query_slice_evidence(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        node_ids: Set[str],
        edge_ids: Set[str],
    ) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        if edge_ids:
            ids = sorted(edge_ids)
            placeholders = ",".join("?" for _ in ids)
            rows = conn.execute(
                f"""
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end, ev.excerpt,
                       ev.evidence_kind, ev.excerpt_hash, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_edges edge
                JOIN analysis_graph_edge_evidence link ON link.edge_id = edge.id
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = edge.source_id
                 AND ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE edge.source_id = ?
                  AND edge.id IN ({placeholders})
                ORDER BY ev.id
                LIMIT 200
                """,
                [source_id, *ids],
            ).fetchall()
            result.extend(self._evidence_projection(rows))
        if node_ids:
            ids = sorted(node_ids)
            placeholders = ",".join("?" for _ in ids)
            rows = conn.execute(
                f"""
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end, ev.excerpt,
                       ev.evidence_kind, ev.excerpt_hash, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_claims claim
                JOIN analysis_graph_claim_evidence link ON link.claim_id = claim.id
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = claim.source_id
                 AND ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE claim.source_id = ?
                  AND claim.node_id IN ({placeholders})
                ORDER BY ev.id
                LIMIT 200
                """,
                [source_id, *ids],
            ).fetchall()
            result.extend(self._evidence_projection(rows))
        return result

    def _query_flow_path_evidence(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        node_ids: Set[str],
        edge_ids: Set[str],
        limit: int,
    ) -> List[Dict[str, Any]]:
        if limit <= 0:
            return []
        result: List[Dict[str, Any]] = []
        if edge_ids:
            ids = sorted(edge_ids)
            placeholders = ",".join("?" for _ in ids)
            rows = conn.execute(
                f"""
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end, ev.excerpt,
                       ev.evidence_kind, ev.excerpt_hash, ev.fact_origin, ev.flow_domain,
                       edge.id AS edge_id, NULL AS node_id
                FROM analysis_graph_edges edge
                JOIN analysis_graph_edge_evidence link ON link.edge_id = edge.id
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = edge.source_id
                 AND ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE edge.source_id = ?
                  AND edge.id IN ({placeholders})
                ORDER BY ev.id
                LIMIT ?
                """,
                [source_id, *ids, limit],
            ).fetchall()
            result.extend(self._linked_evidence_projection(rows))
        remaining = limit - len(result)
        if remaining > 0 and node_ids:
            ids = sorted(node_ids)
            placeholders = ",".join("?" for _ in ids)
            rows = conn.execute(
                f"""
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end, ev.excerpt,
                       ev.evidence_kind, ev.excerpt_hash, ev.fact_origin, ev.flow_domain,
                       NULL AS edge_id, claim.node_id AS node_id
                FROM analysis_graph_claims claim
                JOIN analysis_graph_claim_evidence link ON link.claim_id = claim.id
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = claim.source_id
                 AND ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE claim.source_id = ?
                  AND claim.node_id IN ({placeholders})
                ORDER BY ev.id
                LIMIT ?
                """,
                [source_id, *ids, remaining],
            ).fetchall()
            result.extend(self._linked_evidence_projection(rows))
        return result

    def _linked_evidence_projection(self, rows: List[sqlite3.Row]) -> List[Dict[str, Any]]:
        result = []
        for row in rows:
            item = {
                "id": row["id"],
                "sourceId": row["source_id"],
                "relativePath": row["relative_path"],
                "lineStart": row["line_start"],
                "lineEnd": row["line_end"],
                "excerpt": row["excerpt"],
                "evidenceKind": row["evidence_kind"],
                "excerptHash": row["excerpt_hash"],
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
            }
            if row["edge_id"]:
                item["edgeId"] = row["edge_id"]
            if row["node_id"]:
                item["nodeId"] = row["node_id"]
            result.append(item)
        return result

    def _evidence_projection(self, rows: List[sqlite3.Row]) -> List[Dict[str, Any]]:
        return [
            {
                "id": row["id"],
                "sourceId": row["source_id"],
                "relativePath": row["relative_path"],
                "lineStart": row["line_start"],
                "lineEnd": row["line_end"],
                "excerpt": row["excerpt"],
                "evidenceKind": row["evidence_kind"],
                "excerptHash": row["excerpt_hash"],
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
            }
            for row in rows
        ]

    def _dedupe_by_id(self, items: List[Dict[str, Any]], field: str) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        seen: Set[str] = set()
        for item in items:
            key = str(item.get("sourceId") or "") + ":" + str(item.get(field) or "")
            if key in seen:
                continue
            seen.add(key)
            result.append(item)
        return result

    def _verified_paths_from_evidence(self, evidence: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        paths: List[Dict[str, Any]] = []
        seen: Set[str] = set()
        for item in evidence:
            relative_path = item.get("relativePath")
            if not relative_path:
                continue
            key = f"{item.get('sourceId')}:{relative_path}"
            if key in seen:
                continue
            seen.add(key)
            paths.append(
                {
                    "sourceId": item.get("sourceId"),
                    "relativePath": relative_path,
                    "lineStart": item.get("lineStart"),
                    "lineEnd": item.get("lineEnd"),
                }
            )
        return paths

    def graph_metadata(self, source_id: Optional[str]) -> Dict[str, Any]:
        self.init()
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            source_row = self._selected_graph_source_row(conn, source_id)
            selected_source_id = str(source_row["source_id"]) if source_row else source_id
            overview_row = (
                conn.execute("SELECT * FROM knowledge_source_overview WHERE source_id = ?", (selected_source_id,)).fetchone()
                if selected_source_id and self._table_exists(conn, "knowledge_source_overview")
                else None
            )
            graph_state = self._current_graph_state(conn, selected_source_id) if selected_source_id else None
            diagnostics = (
                conn.execute(
                    """
                    SELECT COUNT(*) AS total,
                           SUM(CASE WHEN severity = 'ERROR' THEN 1 ELSE 0 END) AS errors,
                           SUM(CASE WHEN severity = 'WARN' THEN 1 ELSE 0 END) AS warnings
                    FROM analysis_graph_diagnostics
                    WHERE source_id = ?
                    """,
                    (selected_source_id,),
                ).fetchone()
                if selected_source_id
                else None
            )
            analysis_state = self._current_analysis_state(conn, [selected_source_id] if selected_source_id else None)
        source_name = source_row["display_name"] if source_row else selected_source_id
        inventory = {
            "status": overview_row["inventory_status"] if overview_row else ("READY" if source_row and source_row["root_exists"] else "UNKNOWN"),
            "fileCount": int(overview_row["inventory_file_count"] or 0) if overview_row else int(analysis_state.get("totalFiles", 0)),
            "skippedCount": int(overview_row["skipped_file_count"] or 0) if overview_row else int(analysis_state.get("skippedFiles", 0)),
        }
        analysis = {
            "status": overview_row["analysis_state"] if overview_row else "UNKNOWN",
            "totalFiles": int(overview_row["analysis_total_files"] or 0) if overview_row else int(analysis_state.get("totalFiles", 0)),
            "processedFiles": int(overview_row["analysis_processed_files"] or 0) if overview_row else int(analysis_state.get("completedFiles", 0)),
            "failedFiles": int(overview_row["analysis_failed_files"] or 0) if overview_row else int(analysis_state.get("failedFiles", 0)),
            "pendingFiles": int(overview_row["analysis_pending_files"] or 0) if overview_row else int(analysis_state.get("pendingFiles", 0)),
            "percent": float(overview_row["completion_percent"] or 0.0) if overview_row else float(analysis_state.get("completionPercent", 0.0)),
        }
        return {
            "sourceId": selected_source_id,
            "sourceName": source_name,
            "source": {
                "sourceId": selected_source_id,
                "displayName": source_name,
                "group": source_row["group_name"] if source_row else None,
                "path": source_row["path"] if source_row else None,
                "rootExists": bool(source_row["root_exists"]) if source_row else False,
            },
            "analysis": analysis,
            "inventory": inventory,
            "graphAvailable": graph_state is not None,
            "graphId": graph_state["graph_id"] if graph_state else None,
            "graphRevision": graph_state["content_identity"] if graph_state else None,
            "currentGraphNodeCount": int(graph_state["node_count"] or 0) if graph_state else 0,
            "currentGraphEdgeCount": int(graph_state["edge_count"] or 0) if graph_state else 0,
            "representedFileCount": self._represented_file_count_from_state(graph_state),
            "expectedAnalyzedFileCount": int(analysis_state.get("completedFiles", 0)),
            "coverageStatus": "READY" if graph_state else "NO_GRAPH",
            "degradedReason": None,
            "lastAnalyzedAt": overview_row["updated_at"] if overview_row else None,
            "lastGraphUpdatedAt": graph_state["updated_at"] if graph_state else None,
            "diagnostics": {
                "total": int(diagnostics["total"] or 0) if diagnostics else 0,
                "errors": int(diagnostics["errors"] or 0) if diagnostics else 0,
                "warnings": int(diagnostics["warnings"] or 0) if diagnostics else 0,
            },
        }

    def graph_manifest(
        self,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str] = None,
        node_kind: Optional[str] = None,
        edge_type: Optional[str] = None,
        include_external: str = "show",
        include_unresolved: bool = True,
        include_isolated: bool = True,
        search: Optional[str] = None,
        default_node_page_size: int = 500,
        default_edge_page_size: int = 1000,
    ) -> Dict[str, Any]:
        self.init()
        with self._connect() as conn:
            selected_source_id = self._selected_graph_source_id(conn, source_id)
            graph_state = self._current_graph_state(conn, selected_source_id) if selected_source_id else None
            if graph_state is None:
                revision = self._empty_graph_revision(selected_source_id, flow_domain)
                return {
                    "graphRevision": revision,
                    "graphId": None,
                    "sourceId": selected_source_id,
                    "sourceName": selected_source_id,
                    "flowDomain": flow_domain,
                    "filters": self._raw_graph_filters(fact_origin, node_kind, edge_type, include_external, include_unresolved, include_isolated, search),
                    "totalNodeCount": 0,
                    "totalEdgeCount": 0,
                    "connectedComponentCount": None,
                    "largestComponentNodeCount": None,
                    "largestComponentEdgeCount": None,
                    "nodeTypeCounts": {},
                    "edgeTypeCounts": {},
                    "defaultNodePageSize": default_node_page_size,
                    "defaultEdgePageSize": default_edge_page_size,
                    "etag": self._graph_etag(revision),
                    "generatedAt": datetime.now(timezone.utc).isoformat(),
                    "status": {},
                }
            query = self._graph_query(
                "manifest",
                str(graph_state["graph_id"]),
                selected_source_id,
                flow_domain,
                fact_origin,
                node_kind,
                edge_type,
                include_external,
                include_unresolved,
                include_isolated,
                search,
            )
            metric = self._graph_metric_live(conn, query)
            revision = self._graph_revision(query)
            return {
                "graphRevision": revision,
                "graphId": query.graph_id,
                "sourceId": query.source_id,
                "sourceName": query.source_id,
                "flowDomain": None if query.flow_domain == "ALL" else query.flow_domain,
                "filters": self._graph_query_filters(query),
                "queryFingerprint": query.fingerprint,
                "totalNodeCount": metric["total_node_count"],
                "totalEdgeCount": metric["total_edge_count"],
                "connectedComponentCount": None,
                "largestComponentNodeCount": None,
                "largestComponentEdgeCount": None,
                "nodeTypeCounts": metric["node_type_counts"],
                "edgeTypeCounts": metric["edge_type_counts"],
                "defaultNodePageSize": default_node_page_size,
                "defaultEdgePageSize": default_edge_page_size,
                "etag": self._graph_etag(revision),
                "generatedAt": metric["created_at"],
                "status": {},
            }

    def graph_view(
        self,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str] = None,
        node_kind: Optional[str] = None,
        edge_type: Optional[str] = None,
        include_external: str = "show",
        include_unresolved: bool = True,
        include_isolated: bool = True,
        search: Optional[str] = None,
        max_nodes: int = 80,
    ) -> Dict[str, Any]:
        self.init()
        safe_max_nodes = max(0, min(int(max_nodes or 0), 5000))
        with self._connect() as conn:
            selected_source_id = self._selected_graph_source_id(conn, source_id)
            graph_state = self._current_graph_state(conn, selected_source_id) if selected_source_id else None
            if graph_state is None:
                return self._empty_graph_view(selected_source_id, flow_domain, self._empty_graph_revision(selected_source_id, flow_domain), safe_max_nodes)
            query = self._graph_query(
                "view",
                str(graph_state["graph_id"]),
                selected_source_id,
                flow_domain,
                fact_origin,
                node_kind,
                edge_type,
                include_external,
                include_unresolved,
                include_isolated,
                search,
            )
            metric = self._graph_metric_live(conn, query)
            revision = self._graph_revision(query)
            total_nodes = metric["total_node_count"]
            total_edges = metric["total_edge_count"]
            node_limit = total_nodes if safe_max_nodes <= 0 else min(safe_max_nodes, total_nodes)
            visible_nodes = self._relationship_aware_graph_view_nodes(conn, query, node_limit)
            visible_ids = [node["id"] for node in visible_nodes]
            visible_edges, visible_internal_edge_count = self._relationship_aware_graph_view_edges(conn, query, visible_ids, max(node_limit * 2, 80))
            boundary_edge_count = self._graph_view_boundary_edge_count(conn, query, visible_ids) if visible_ids else 0
            hidden_edge_count = max(0, total_edges - len(visible_edges))
            return {
                "sourceId": query.source_id,
                "sourceName": query.source_id,
                "graphId": query.graph_id,
                "graphRevision": revision,
                "queryFingerprint": query.fingerprint,
                "selectionPolicy": "RELATIONSHIP_AWARE",
                "maxNodes": safe_max_nodes,
                "filters": self._graph_query_filters(query),
                "nodes": visible_nodes,
                "edges": visible_edges,
                "totalMatchingNodeCount": total_nodes,
                "totalMatchingEdgeCount": total_edges,
                "visibleNodeCount": len(visible_nodes),
                "visibleEdgeCount": len(visible_edges),
                "hiddenNodeCount": max(0, total_nodes - len(visible_nodes)),
                "hiddenEdgeCount": hidden_edge_count,
                "hiddenBoundaryEdgeCount": boundary_edge_count,
                "internalEdgeCount": visible_internal_edge_count,
                "hasMore": len(visible_nodes) < total_nodes or hidden_edge_count > 0,
                "generatedAt": metric["created_at"],
                "status": {},
            }

    def graph_nodes(
        self,
        graph_revision: str,
        cursor: Optional[str],
        page_size: int,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str] = None,
        node_kind: Optional[str] = None,
        include_external: str = "show",
        include_unresolved: bool = True,
        include_isolated: bool = True,
        search: Optional[str] = None,
    ) -> Dict[str, Any]:
        safe_page_size = max(1, min(int(page_size or 500), 5000))
        with self._connect() as conn:
            payload = self._decode_graph_revision(graph_revision)
            query = self._graph_query(
                "nodes",
                str(payload["graphId"]),
                source_id or str(payload["sourceId"]),
                flow_domain,
                fact_origin,
                node_kind,
                payload.get("edgeType"),
                include_external,
                include_unresolved,
                include_isolated,
                search,
            )
            self._assert_graph_query_current(conn, payload, query)
            cursor_value = self._decode_graph_cursor(cursor, query, "nodes")
            where, params = self._graph_node_where(query)
            if cursor_value:
                where = f"{where} AND n.id > ?"
                params = [*params, cursor_value]
            contract = graph_query_contract()
            entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
            rows = conn.execute(
                f"""
                SELECT n.*, af.relative_path,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN (
                    SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    GROUP BY source_id, from_node_id
                ) out_degree ON out_degree.source_id = n.source_id AND out_degree.node_id = n.id
                LEFT JOIN (
                    SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE to_node_id IS NOT NULL
                    GROUP BY source_id, to_node_id
                ) in_degree ON in_degree.source_id = n.source_id AND in_degree.node_id = n.id
                LEFT JOIN analysis_graph_claims entry
                  ON entry.source_id = n.source_id
                 AND entry.node_id = n.id
                 AND entry.claim_kind = ?
                 AND entry.status IN ({entry_status_sql})
                WHERE {where}
                ORDER BY n.id
                LIMIT ?
                """,
                [contract.entrypoint_claim_kind, *entry_status_params, *params, safe_page_size + 1],
            ).fetchall()
        items = [self._graph_node_projection(self._row_dict(row)) for row in rows[:safe_page_size]]
        complete = len(rows) <= safe_page_size
        next_cursor = None if complete or not items else self._encode_graph_cursor(query, "nodes", items[-1]["id"])
        return {"graphRevision": graph_revision, "graphId": query.graph_id, "queryFingerprint": query.fingerprint, "items": items, "nextCursor": next_cursor, "complete": complete, "returnedCount": len(items)}

    def graph_edges(
        self,
        graph_revision: str,
        cursor: Optional[str],
        page_size: int,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str] = None,
        edge_type: Optional[str] = None,
        include_external: str = "show",
        include_unresolved: bool = True,
        search: Optional[str] = None,
    ) -> Dict[str, Any]:
        safe_page_size = max(1, min(int(page_size or 1000), 5000))
        with self._connect() as conn:
            payload = self._decode_graph_revision(graph_revision)
            query = self._graph_query(
                "edges",
                str(payload["graphId"]),
                source_id or str(payload["sourceId"]),
                flow_domain,
                fact_origin,
                payload.get("nodeKind"),
                edge_type,
                include_external,
                include_unresolved,
                bool(payload.get("includeIsolated", True)),
                search,
            )
            self._assert_graph_query_current(conn, payload, query)
            cursor_value = self._decode_graph_cursor(cursor, query, "edges")
            where, params = self._graph_edge_where(query)
            if cursor_value:
                where = f"{where} AND e.id > ?"
                params = [*params, cursor_value]
            rows = conn.execute(
                f"""
                SELECT e.*,
                       fn.display_name AS from_display_name,
                       fn.qualified_name AS from_qualified_name,
                       fn.name AS from_name,
                       tn.display_name AS to_display_name,
                       tn.qualified_name AS to_qualified_name,
                       tn.name AS to_name
                FROM analysis_graph_edges e
                LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
                LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
                WHERE {where}
                ORDER BY e.id
                LIMIT ?
                """,
                [*params, safe_page_size + 1],
            ).fetchall()
        items = [self._graph_edge_projection(self._row_dict(row)) for row in rows[:safe_page_size]]
        complete = len(rows) <= safe_page_size
        next_cursor = None if complete or not items else self._encode_graph_cursor(query, "edges", items[-1]["id"])
        return {"graphRevision": graph_revision, "graphId": query.graph_id, "queryFingerprint": query.fingerprint, "items": items, "nextCursor": next_cursor, "complete": complete, "returnedCount": len(items)}

    def graph_node_detail(self, graph_revision: str, node_id: str, source_id: Optional[str], include_evidence: bool = False) -> Dict[str, Any]:
        with self._connect() as conn:
            payload = self._decode_graph_revision(graph_revision)
            requested_source = source_id or str(payload["sourceId"])
            query = self._graph_query(
                "nodes",
                str(payload["graphId"]),
                requested_source,
                payload.get("flowDomain"),
                payload.get("factOrigin"),
                payload.get("nodeKind"),
                payload.get("edgeType"),
                payload.get("includeExternal", "show"),
                bool(payload.get("includeUnresolved", True)),
                bool(payload.get("includeIsolated", True)),
                payload.get("search"),
            )
            self._assert_graph_query_current(conn, payload, query)
            contract = graph_query_contract()
            entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
            row = conn.execute(
                f"""
                SELECT n.*, af.relative_path,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN (
                    SELECT source_id, from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    GROUP BY source_id, from_node_id
                ) out_degree ON out_degree.source_id = n.source_id AND out_degree.node_id = n.id
                LEFT JOIN (
                    SELECT source_id, to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE to_node_id IS NOT NULL
                    GROUP BY source_id, to_node_id
                ) in_degree ON in_degree.source_id = n.source_id AND in_degree.node_id = n.id
                LEFT JOIN analysis_graph_claims entry
                  ON entry.source_id = n.source_id
                 AND entry.node_id = n.id
                 AND entry.claim_kind = ?
                 AND entry.status IN ({entry_status_sql})
                WHERE n.source_id = ?
                  AND n.id = ?
                """,
                (contract.entrypoint_claim_kind, *entry_status_params, requested_source, node_id),
            ).fetchone()
            if row is None:
                if conn.execute("SELECT 1 FROM analysis_graph_nodes WHERE id = ? LIMIT 1", (node_id,)).fetchone():
                    raise KnowledgeError("GRAPH_ITEM_SCOPE_MISMATCH", "Graph item is outside the requested source.")
                raise KnowledgeError("GRAPH_NODE_NOT_FOUND", "Graph node was not found.")
            row_dict = self._row_dict(row)
            detail = self._graph_node_projection(row_dict)
            detail["parentNodeId"] = row_dict.get("parent_node_id")
            claims = self._claims_for_node_detail(conn, row_dict)
            detail["claims"] = [
                {
                    "id": claim["id"],
                    "claimKind": claim["claim_kind"],
                    "summary": claim["summary"],
                    "confidence": claim["confidence"],
                    "status": claim["status"],
                    "rejectionReason": claim["rejection_reason"],
                    "factOrigin": claim["fact_origin"],
                    "flowDomain": claim["flow_domain"],
                }
                for claim in claims
                if claim["selected_node_claim"]
            ]
            summary = self._fact_summary_from_claim_rows([self._row_dict(claim) for claim in claims], row_dict)
            detail.update(
                {
                    "claimSummary": summary.get("summary"),
                    "responsibilitySummary": summary.get("summary"),
                    "summarySource": summary.get("summarySource"),
                    "summaryClaimId": summary.get("summaryClaimId"),
                    "summaryClaimNodeId": summary.get("summaryClaimNodeId"),
                    "summaryConfidence": summary.get("summaryConfidence"),
                    "summaryEvidenceCount": summary.get("summaryEvidenceCount"),
                }
            )
            if include_evidence:
                detail["evidence"] = self._graph_evidence(conn, node_id=node_id)
            detail["connections"] = self._graph_node_connections(conn, node_id, requested_source)
            return {"graphRevision": graph_revision, "graphId": query.graph_id, "item": detail}

    def graph_edge_detail(self, graph_revision: str, edge_id: str, source_id: Optional[str], include_evidence: bool = False) -> Dict[str, Any]:
        with self._connect() as conn:
            payload = self._decode_graph_revision(graph_revision)
            requested_source = source_id or str(payload["sourceId"])
            query = self._graph_query(
                "edges",
                str(payload["graphId"]),
                requested_source,
                payload.get("flowDomain"),
                payload.get("factOrigin"),
                payload.get("nodeKind"),
                payload.get("edgeType"),
                payload.get("includeExternal", "show"),
                bool(payload.get("includeUnresolved", True)),
                bool(payload.get("includeIsolated", True)),
                payload.get("search"),
            )
            self._assert_graph_query_current(conn, payload, query)
            row = conn.execute(
                """
                SELECT e.*,
                       fn.display_name AS from_display_name,
                       fn.qualified_name AS from_qualified_name,
                       fn.name AS from_name,
                       tn.display_name AS to_display_name,
                       tn.qualified_name AS to_qualified_name,
                       tn.name AS to_name
                FROM analysis_graph_edges e
                LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
                LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
                WHERE e.source_id = ?
                  AND e.id = ?
                """,
                (requested_source, edge_id),
            ).fetchone()
            if row is None:
                if conn.execute("SELECT 1 FROM analysis_graph_edges WHERE id = ? LIMIT 1", (edge_id,)).fetchone():
                    raise KnowledgeError("GRAPH_ITEM_SCOPE_MISMATCH", "Graph item is outside the requested source.")
                raise KnowledgeError("GRAPH_EDGE_NOT_FOUND", "Graph edge was not found.")
            detail = self._graph_edge_projection(self._row_dict(row))
            if include_evidence:
                detail["evidence"] = self._graph_evidence(conn, edge_id=edge_id)
            return {"graphRevision": graph_revision, "graphId": query.graph_id, "item": detail}

    def _selected_graph_source_row(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Optional[sqlite3.Row]:
        if source_id and self._table_exists(conn, "sources"):
            return conn.execute(
                "SELECT source_id, display_name, group_name, path, root_exists, last_seen_at FROM sources WHERE source_id = ?",
                (source_id,),
            ).fetchone()
        row = conn.execute(
            """
            SELECT s.source_id, s.display_name, s.group_name, s.path, s.root_exists, s.last_seen_at
            FROM sources s
            JOIN analysis_graph_state state ON state.source_id = s.source_id
            ORDER BY state.updated_at DESC, s.source_id
            LIMIT 1
            """
        ).fetchone() if self._table_exists(conn, "sources") and self._table_exists(conn, "analysis_graph_state") else None
        if row is not None:
            return row
        if self._table_exists(conn, "sources"):
            return conn.execute(
                "SELECT source_id, display_name, group_name, path, root_exists, last_seen_at FROM sources ORDER BY source_id LIMIT 1"
            ).fetchone()
        return None

    def _selected_graph_source_id(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Optional[str]:
        row = self._selected_graph_source_row(conn, source_id)
        return str(row["source_id"]) if row else source_id

    def _current_graph_state(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Optional[sqlite3.Row]:
        if not source_id or not self._table_exists(conn, "analysis_graph_state"):
            return None
        return conn.execute("SELECT * FROM analysis_graph_state WHERE source_id = ?", (source_id,)).fetchone()

    def _represented_file_count_from_state(self, graph_state: Optional[sqlite3.Row]) -> int:
        return 0 if graph_state is None else int(graph_state["node_count"] or 0)

    def _raw_graph_filters(
        self,
        fact_origin: Optional[str],
        node_kind: Optional[str],
        edge_type: Optional[str],
        include_external: str,
        include_unresolved: bool,
        include_isolated: bool,
        search: Optional[str],
    ) -> Dict[str, Any]:
        return {
            "factOrigin": fact_origin,
            "nodeKind": node_kind,
            "edgeType": edge_type,
            "includeExternal": include_external,
            "includeUnresolved": include_unresolved,
            "includeIsolated": include_isolated,
            "search": self._normalize_graph_search(search),
        }

    def _empty_graph_revision(self, source_id: Optional[str], flow_domain: Optional[str]) -> str:
        return f"{source_id or 'all'}:{flow_domain or 'ALL'}:graph-empty"

    def _inventory_membership_graph_node_clause(self, alias: str = "n") -> str:
        return f"""
        EXISTS (
            SELECT 1
            FROM analysis_files af_current
            WHERE af_current.source_id = {alias}.source_id
              AND af_current.relative_path = {alias}.relative_path
              AND af_current.content_hash = {alias}.content_hash
        )
        AND EXISTS (
            SELECT 1
            FROM files f_current
            WHERE f_current.source_id = {alias}.source_id
              AND f_current.relative_path = {alias}.relative_path
              AND f_current.content_hash = {alias}.content_hash
        )
        """

    def _inventory_membership_graph_edge_clause(self, alias: str = "e") -> str:
        return f"""
        EXISTS (
            SELECT 1
            FROM analysis_files af_current
            WHERE af_current.source_id = {alias}.source_id
              AND af_current.relative_path = {alias}.relative_path
              AND af_current.content_hash = {alias}.content_hash
        )
        AND EXISTS (
            SELECT 1
            FROM files f_current
            WHERE f_current.source_id = {alias}.source_id
              AND f_current.relative_path = {alias}.relative_path
              AND f_current.content_hash = {alias}.content_hash
        )
        """

    def _empty_graph_view(self, source_id: Optional[str], flow_domain: Optional[str], revision: str, max_nodes: int) -> Dict[str, Any]:
        return {
            "sourceId": source_id,
            "sourceName": source_id,
            "graphId": None,
            "graphRevision": revision,
            "queryFingerprint": None,
            "selectionPolicy": "RELATIONSHIP_AWARE",
            "maxNodes": max_nodes,
            "filters": {"flowDomain": flow_domain},
            "nodes": [],
            "edges": [],
            "totalMatchingNodeCount": 0,
            "totalMatchingEdgeCount": 0,
            "visibleNodeCount": 0,
            "visibleEdgeCount": 0,
            "hiddenNodeCount": 0,
            "hiddenEdgeCount": 0,
            "hiddenBoundaryEdgeCount": 0,
            "internalEdgeCount": 0,
            "hasMore": False,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "status": {},
        }

    def _graph_query_filters(self, query: GraphQuery) -> Dict[str, Any]:
        return {
            "factOrigin": None if query.fact_origin == "ALL" else query.fact_origin,
            "nodeKind": None if query.node_kind == "ALL" else query.node_kind,
            "edgeType": None if query.edge_type == "ALL" else query.edge_type,
            "includeExternal": query.include_external,
            "includeUnresolved": query.include_unresolved,
            "includeIsolated": query.include_isolated,
            "search": query.search or None,
        }

    def _normalize_graph_dimension(self, value: Optional[str]) -> str:
        if value is None:
            return "ALL"
        normalized = str(value).strip()
        return normalized.upper() if normalized else "ALL"

    def _normalize_graph_search(self, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        normalized = str(value).strip()
        return normalized or None

    def _graph_query(
        self,
        resource: str,
        graph_id: str,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        edge_type: Optional[str],
        include_external: str,
        include_unresolved: bool,
        include_isolated: bool,
        search: Optional[str] = None,
    ) -> GraphQuery:
        resource_normalized = str(resource or "").lower()
        if resource_normalized not in {"manifest", "nodes", "edges", "view"}:
            raise KnowledgeError("GRAPH_FILTER_INVALID", "Graph resource is not supported.")
        include_external_normalized = str(include_external or "show").strip().lower()
        if include_external_normalized not in {"show", "hide"}:
            raise KnowledgeError("GRAPH_FILTER_INVALID", "includeExternal must be 'show' or 'hide'.")
        return GraphQuery(
            source_id=str(source_id or "all"),
            graph_id=graph_id,
            resource=resource_normalized,
            flow_domain=self._normalize_graph_dimension(flow_domain),
            fact_origin=self._normalize_graph_dimension(fact_origin),
            node_kind=self._normalize_graph_dimension(node_kind),
            edge_type=self._normalize_graph_dimension(edge_type),
            include_external=include_external_normalized,
            include_unresolved=bool(include_unresolved),
            include_isolated=bool(include_isolated),
            search=self._normalize_graph_search(search),
        )

    def _graph_metric_live(self, conn: sqlite3.Connection, query: GraphQuery) -> Dict[str, Any]:
        node_where, node_params = self._graph_node_where(query)
        edge_where, edge_params = self._graph_edge_where(query)
        node_count = int(conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {node_where}", node_params).fetchone()["count"] or 0)
        edge_count = int(conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {edge_where}", edge_params).fetchone()["count"] or 0)
        node_types = {
            row["node_kind"] or "UNKNOWN": int(row["count"] or 0)
            for row in conn.execute(
                f"SELECT n.node_kind, COUNT(*) AS count FROM analysis_graph_nodes n WHERE {node_where} GROUP BY n.node_kind",
                node_params,
            ).fetchall()
        }
        edge_types = {
            row["edge_type"] or "UNKNOWN": int(row["count"] or 0)
            for row in conn.execute(
                f"SELECT e.edge_type, COUNT(*) AS count FROM analysis_graph_edges e WHERE {edge_where} GROUP BY e.edge_type",
                edge_params,
            ).fetchall()
        }
        return {
            "total_node_count": node_count,
            "total_edge_count": edge_count,
            "node_type_counts": node_types,
            "edge_type_counts": edge_types,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }

    def _graph_node_where(self, query: GraphQuery) -> tuple[str, List[Any]]:
        clauses = ["n.source_id = ?"]
        params: List[Any] = [query.source_id]
        if query.flow_domain != "ALL":
            clauses.append("n.flow_domain = ?")
            params.append(query.flow_domain.upper())
        if query.fact_origin != "ALL":
            clauses.append("n.fact_origin = ?")
            params.append(query.fact_origin.upper())
        if query.node_kind != "ALL":
            clauses.append("n.node_kind = ?")
            params.append(query.node_kind.upper())
        if not query.include_isolated:
            clauses.append("EXISTS (SELECT 1 FROM analysis_graph_edges ge WHERE ge.source_id = n.source_id AND (ge.from_node_id = n.id OR ge.to_node_id = n.id))")
        if query.search:
            pattern = self._graph_search_pattern(query.search)
            clauses.append(
                """(
                    n.id LIKE ? ESCAPE '\\'
                    OR n.name LIKE ? ESCAPE '\\'
                    OR n.qualified_name LIKE ? ESCAPE '\\'
                    OR n.display_name LIKE ? ESCAPE '\\'
                    OR n.node_kind LIKE ? ESCAPE '\\'
                    OR n.relative_path LIKE ? ESCAPE '\\'
                )"""
            )
            params.extend([pattern, pattern, pattern, pattern, pattern, pattern])
        return " AND ".join(clauses), params

    def _graph_edge_where(self, query: GraphQuery) -> tuple[str, List[Any]]:
        clauses = ["e.source_id = ?"]
        params: List[Any] = [query.source_id]
        if query.flow_domain != "ALL":
            clauses.append("e.flow_domain = ?")
            params.append(query.flow_domain.upper())
        if query.fact_origin != "ALL":
            clauses.append("e.fact_origin = ?")
            params.append(query.fact_origin.upper())
        if query.edge_type != "ALL":
            clauses.append("e.edge_type = ?")
            params.append(query.edge_type.upper())
        if query.include_external == "hide":
            clauses.append("e.resolution_status != ?")
            params.append(graph_query_contract().external_target_status)
        if not query.include_unresolved:
            clauses.append("e.to_node_id IS NOT NULL")
            hidden_status_sql, hidden_status_params = sql_in_clause(graph_query_contract().hidden_unresolved_resolution_statuses())
            clauses.append(f"e.resolution_status NOT IN ({hidden_status_sql})")
            params.extend(hidden_status_params)
        if query.search:
            pattern = self._graph_search_pattern(query.search)
            clauses.append(
                """(
                    e.edge_type LIKE ? ESCAPE '\\'
                    OR EXISTS (
                        SELECT 1
                        FROM analysis_graph_nodes sn
                        WHERE sn.source_id = e.source_id
                          AND sn.id IN (e.from_node_id, e.to_node_id)
                          AND (
                            sn.id LIKE ? ESCAPE '\\'
                            OR sn.name LIKE ? ESCAPE '\\'
                            OR sn.qualified_name LIKE ? ESCAPE '\\'
                            OR sn.display_name LIKE ? ESCAPE '\\'
                            OR sn.node_kind LIKE ? ESCAPE '\\'
                            OR sn.relative_path LIKE ? ESCAPE '\\'
                          )
                    )
                )"""
            )
            params.extend([pattern, pattern, pattern, pattern, pattern, pattern, pattern])
        return " AND ".join(clauses), params

    def _relationship_aware_graph_view_nodes(self, conn: sqlite3.Connection, query: GraphQuery, limit: int) -> List[Dict[str, Any]]:
        if limit <= 0:
            return []
        node_where, node_params = self._graph_node_where(query)
        edge_where, edge_params = self._graph_edge_where(query)
        contract = graph_query_contract()
        entry_status_sql, entry_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            WITH candidate_nodes AS (
                SELECT n.*, af.relative_path,
                       CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN analysis_graph_claims entry
                  ON entry.source_id = n.source_id
                 AND entry.node_id = n.id
                 AND entry.claim_kind = ?
                 AND entry.status IN ({entry_status_sql})
                WHERE {node_where}
            ),
            filtered_edges AS (
                SELECT e.from_node_id, e.to_node_id
                FROM analysis_graph_edges e
                JOIN candidate_nodes fn ON fn.id = e.from_node_id
                JOIN candidate_nodes tn ON tn.id = e.to_node_id
                WHERE {edge_where}
                  AND e.to_node_id IS NOT NULL
            ),
            node_degree AS (
                SELECT node_id, COUNT(*) AS count
                FROM (
                    SELECT from_node_id AS node_id FROM filtered_edges
                    UNION ALL
                    SELECT to_node_id AS node_id FROM filtered_edges
                )
                GROUP BY node_id
            )
            SELECT candidate_nodes.*, COALESCE(node_degree.count, 0) AS graph_degree
            FROM candidate_nodes
            LEFT JOIN node_degree ON node_degree.node_id = candidate_nodes.id
            ORDER BY CASE WHEN COALESCE(node_degree.count, 0) > 0 THEN 0 ELSE 1 END,
                     candidate_nodes.entrypoint DESC,
                     COALESCE(node_degree.count, 0) DESC,
                     lower(COALESCE(candidate_nodes.relative_path, '')),
                     lower(COALESCE(candidate_nodes.display_name, candidate_nodes.qualified_name, candidate_nodes.name, candidate_nodes.id)),
                     candidate_nodes.id
            LIMIT ?
            """,
            [contract.entrypoint_claim_kind, *entry_status_params, *node_params, *edge_params, limit],
        ).fetchall()
        return [self._graph_node_projection(self._row_dict(row)) for row in rows]

    def _relationship_aware_graph_view_edges(self, conn: sqlite3.Connection, query: GraphQuery, visible_node_ids: List[str], max_edges: int) -> tuple[List[Dict[str, Any]], int]:
        if not visible_node_ids:
            return [], 0
        edge_where, edge_params = self._graph_edge_where(query)
        placeholders = ",".join("?" for _ in visible_node_ids)
        internal_count = int(
            conn.execute(
                f"""
                SELECT COUNT(*) AS count
                FROM analysis_graph_edges e
                WHERE {edge_where}
                  AND e.from_node_id IN ({placeholders})
                  AND e.to_node_id IN ({placeholders})
                """,
                [*edge_params, *visible_node_ids, *visible_node_ids],
            ).fetchone()["count"]
            or 0
        )
        edge_limit = max(0, min(int(max_edges or 0), 5000))
        if edge_limit <= 0:
            return [], internal_count
        rows = conn.execute(
            f"""
            SELECT e.*,
                   fn.display_name AS from_display_name,
                   fn.qualified_name AS from_qualified_name,
                   fn.name AS from_name,
                   tn.display_name AS to_display_name,
                   tn.qualified_name AS to_qualified_name,
                   tn.name AS to_name
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
            WHERE {edge_where}
              AND e.from_node_id IN ({placeholders})
              AND e.to_node_id IN ({placeholders})
            ORDER BY e.edge_type, e.id
            LIMIT ?
            """,
            [*edge_params, *visible_node_ids, *visible_node_ids, edge_limit],
        ).fetchall()
        return [self._graph_edge_projection(self._row_dict(row)) for row in rows], internal_count

    def _graph_view_boundary_edge_count(self, conn: sqlite3.Connection, query: GraphQuery, visible_node_ids: List[str]) -> int:
        if not visible_node_ids:
            return 0
        edge_where, edge_params = self._graph_edge_where(query)
        placeholders = ",".join("?" for _ in visible_node_ids)
        return int(
            conn.execute(
                f"""
                SELECT COUNT(*) AS count
                FROM analysis_graph_edges e
                WHERE {edge_where}
                  AND (
                    (e.from_node_id IN ({placeholders}) AND (e.to_node_id IS NULL OR e.to_node_id NOT IN ({placeholders})))
                    OR (e.to_node_id IN ({placeholders}) AND e.from_node_id NOT IN ({placeholders}))
                  )
                """,
                [*edge_params, *visible_node_ids, *visible_node_ids, *visible_node_ids, *visible_node_ids],
            ).fetchone()["count"]
            or 0
        )

    def _graph_search_pattern(self, value: str) -> str:
        escaped = str(value).replace("\\", "\\\\").replace("_", "\\_")
        return f"%{escaped}%"

    def _graph_revision(self, query: GraphQuery) -> str:
        payload = self._graph_revision_payload(query)
        token = base64.urlsafe_b64encode(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).decode("ascii").rstrip("=")
        return f"{query.source_id}:{query.flow_domain}:graph:{token}"

    def _graph_revision_payload(self, query: GraphQuery) -> Dict[str, Any]:
        payload = query.as_payload()
        payload["queryFingerprint"] = query.fingerprint
        return payload

    def _decode_graph_revision(self, graph_revision: str) -> Dict[str, Any]:
        if not graph_revision:
            raise KnowledgeError("GRAPH_REVISION_REQUIRED", "graphRevision is required.")
        token = graph_revision.rsplit(":", 1)[-1]
        try:
            padded = token + ("=" * (-len(token) % 4))
            payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError, TypeError):
            raise KnowledgeError("GRAPH_REVISION_STALE", "Graph revision is stale.")
        if not isinstance(payload, dict) or payload.get("contractVersion") != GRAPH_CONTRACT_VERSION or not payload.get("graphId"):
            raise KnowledgeError("GRAPH_REVISION_STALE", "Graph revision is stale.")
        return payload

    def _graph_etag(self, graph_revision: str) -> str:
        return f'"{base64.urlsafe_b64encode(graph_revision.encode("utf-8")).decode("ascii").rstrip("=")}"'

    def _assert_graph_query_current(self, conn: sqlite3.Connection, payload: Dict[str, Any], query: GraphQuery) -> None:
        expected = self._graph_revision_payload(query)
        keys = (
            "contractVersion",
            "sortVersion",
            "graphId",
            "sourceId",
            "flowDomain",
            "factOrigin",
            "nodeKind",
            "edgeType",
            "includeExternal",
            "includeUnresolved",
            "includeIsolated",
            "search",
        )
        if any(payload.get(key) != expected.get(key) for key in keys):
            raise KnowledgeError("GRAPH_REVISION_STALE", "Graph revision is stale.")
        state = self._current_graph_state(conn, query.source_id)
        if state is None or state["graph_id"] != query.graph_id:
            raise KnowledgeError("GRAPH_REVISION_STALE", "Graph revision is stale.")

    def _encode_graph_cursor(self, query: GraphQuery, page_kind: str, last_id: str) -> str:
        payload = {
            "contractVersion": GRAPH_CONTRACT_VERSION,
            "graphId": query.graph_id,
            "sourceId": query.source_id,
            "resource": page_kind,
            "queryFingerprint": query.fingerprint,
            "sortVersion": query.sort_version,
            "last": {"id": last_id},
        }
        payload["signature"] = self._graph_cursor_signature(payload)
        return base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode("utf-8")).decode("ascii").rstrip("=")

    def _graph_cursor_signature(self, payload: Dict[str, Any]) -> str:
        body = {key: value for key, value in payload.items() if key != "signature"}
        encoded = json.dumps(body, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(f"{GRAPH_CURSOR_SIGNATURE_CONTEXT}:{encoded}".encode("utf-8")).hexdigest()

    def _decode_graph_cursor(self, cursor: Optional[str], query: GraphQuery, page_kind: str) -> Optional[str]:
        if not cursor:
            return None
        try:
            padded = cursor + ("=" * (-len(cursor) % 4))
            payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        except (ValueError, TypeError, json.JSONDecodeError):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph cursor is invalid.")
        if not isinstance(payload, dict) or payload.get("contractVersion") != GRAPH_CONTRACT_VERSION:
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph cursor is invalid.")
        if payload.get("signature") != self._graph_cursor_signature(payload):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph cursor is invalid.")
        if payload.get("resource") != page_kind:
            raise KnowledgeError("GRAPH_CURSOR_RESOURCE_MISMATCH", "Graph cursor resource does not match this request.")
        if payload.get("sourceId") != query.source_id or payload.get("graphId") != query.graph_id:
            raise KnowledgeError("GRAPH_CURSOR_QUERY_MISMATCH", "Graph cursor query does not match this request.")
        if payload.get("queryFingerprint") != query.fingerprint or payload.get("sortVersion") != query.sort_version:
            raise KnowledgeError("GRAPH_CURSOR_QUERY_MISMATCH", "Graph cursor query does not match this request.")
        last = payload.get("last")
        if not isinstance(last, dict) or not last.get("id"):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph cursor is invalid.")
        return str(last["id"])

    def _graph_evidence(self, conn: sqlite3.Connection, node_id: Optional[str] = None, edge_id: Optional[str] = None) -> List[Dict[str, Any]]:
        if edge_id:
            rows = conn.execute(
                """
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end, ev.excerpt,
                       ev.evidence_kind, ev.excerpt_hash, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_edges edge
                JOIN analysis_graph_edge_evidence link ON link.edge_id = edge.id
                JOIN analysis_graph_evidence ev ON ev.source_id = edge.source_id AND ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE edge.id = ?
                ORDER BY ev.id
                LIMIT 100
                """,
                (edge_id,),
            ).fetchall()
        elif node_id:
            rows = conn.execute(
                """
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end, ev.excerpt,
                       ev.evidence_kind, ev.excerpt_hash, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_claims claim
                JOIN analysis_graph_claim_evidence link ON link.claim_id = claim.id
                JOIN analysis_graph_evidence ev ON ev.source_id = claim.source_id AND ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE claim.node_id = ?
                ORDER BY ev.id
                LIMIT 100
                """,
                (node_id,),
            ).fetchall()
        else:
            rows = []
        return self._evidence_projection(rows)

    def _graph_node_connections(self, conn: sqlite3.Connection, node_id: str, source_id: str, limit: int = GRAPH_NODE_DETAIL_RELATION_LIMIT) -> Dict[str, Any]:
        def rows_for(direction: str) -> List[sqlite3.Row]:
            column = "e.from_node_id" if direction == "outgoing" else "e.to_node_id"
            return conn.execute(
                f"""
                SELECT e.id, e.edge_type, e.from_node_id, e.to_node_id, e.confidence,
                       e.metadata_json, e.fact_origin, e.flow_domain, e.unresolved_target_json,
                       ev_af.relative_path AS evidence_relative_path,
                       ev.line_start AS evidence_line_start,
                       ev.line_end AS evidence_line_end,
                       COUNT(DISTINCT link.evidence_id) AS evidence_count,
                       fn.display_name AS source_display_name,
                       fn.qualified_name AS source_qualified_name,
                       fn.name AS source_name,
                       fn.node_kind AS source_node_kind,
                       tn.display_name AS target_display_name,
                       tn.qualified_name AS target_qualified_name,
                       tn.name AS target_name,
                       tn.node_kind AS target_node_kind,
                       COUNT(*) OVER() AS total_count
                FROM analysis_graph_edges e
                JOIN analysis_graph_nodes fn ON fn.source_id = e.source_id AND fn.id = e.from_node_id
                LEFT JOIN analysis_graph_nodes tn ON tn.source_id = e.source_id AND tn.id = e.to_node_id
                LEFT JOIN analysis_graph_edge_evidence link ON link.edge_id = e.id
                LEFT JOIN analysis_graph_evidence ev ON ev.source_id = e.source_id AND ev.id = link.evidence_id
                LEFT JOIN analysis_files ev_af ON ev_af.file_id = ev.analysis_file_id
                WHERE e.source_id = ?
                  AND {column} = ?
                GROUP BY e.id
                ORDER BY e.edge_type, e.id
                LIMIT ?
                """,
                (source_id, node_id, limit),
            ).fetchall()

        def unresolved_target_name(row: sqlite3.Row) -> Optional[str]:
            if not row["unresolved_target_json"]:
                return None
            try:
                value = json.loads(row["unresolved_target_json"])
            except (TypeError, json.JSONDecodeError):
                return str(row["unresolved_target_json"])
            if isinstance(value, dict):
                return value.get("name") or value.get("qualifiedName") or value.get("displayName")
            return str(value)

        def item_from(row: sqlite3.Row) -> Dict[str, Any]:
            line_start = row["evidence_line_start"]
            line_end = row["evidence_line_end"] or line_start
            source_path = row["evidence_relative_path"]
            return {
                "edgeId": row["id"],
                "edgeType": row["edge_type"],
                "sourceNodeId": row["from_node_id"],
                "sourceName": row["source_display_name"] or row["source_qualified_name"] or row["source_name"] or row["from_node_id"],
                "sourceKind": row["source_node_kind"],
                "targetNodeId": row["to_node_id"],
                "targetName": row["target_display_name"] or row["target_qualified_name"] or row["target_name"] or unresolved_target_name(row) or row["to_node_id"],
                "targetKind": row["target_node_kind"],
                "sourcePath": source_path,
                "lineStart": line_start,
                "lineEnd": line_end,
                "confidence": row["confidence"],
                "evidenceCount": int(row["evidence_count"] or 0),
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
            }

        def group(rows: List[sqlite3.Row]) -> Dict[str, Any]:
            return {"totalCount": int(rows[0]["total_count"]) if rows else 0, "items": [item_from(row) for row in rows]}

        return {"incoming": group(rows_for("incoming")), "outgoing": group(rows_for("outgoing"))}

    def _claims_for_node_detail(self, conn: sqlite3.Connection, row: Dict[str, Any]) -> List[sqlite3.Row]:
        contract = graph_query_contract()
        responsibility_status_sql, responsibility_status_params = sql_in_clause(contract.statuses_for_responsibility_summary())
        return conn.execute(
            f"""
            SELECT claim.id, claim.node_id, claim.claim_kind, claim.summary, claim.confidence, claim.status,
                   claim.rejection_reason, claim.fact_origin, claim.flow_domain,
                   COUNT(DISTINCT link.evidence_id) AS evidence_count,
                   CASE WHEN claim.node_id = ? THEN 1 ELSE 0 END AS selected_node_claim
            FROM analysis_graph_claims claim
            LEFT JOIN analysis_graph_claim_evidence link ON link.claim_id = claim.id
            WHERE claim.source_id = ?
              AND (
                claim.node_id = ?
                OR (
                  claim.claim_kind = ?
                  AND claim.status IN ({responsibility_status_sql})
                  AND claim.rejection_reason IS NULL
                  AND (
                    claim.node_id = ?
                    OR claim.node_id IN (
                      SELECT id
                      FROM analysis_graph_nodes
                      WHERE analysis_file_id = ?
                        AND node_kind = ?
                      ORDER BY confidence DESC
                      LIMIT 1
                    )
                  )
                )
              )
            GROUP BY claim.id
            ORDER BY selected_node_claim DESC, confidence DESC, id
            LIMIT 100
            """,
            (
                row["id"],
                row["source_id"],
                row["id"],
                contract.responsibility_claim_kind,
                *responsibility_status_params,
                row.get("parent_node_id"),
                row.get("analysis_file_id"),
                contract.file_node_kind,
            ),
        ).fetchall()

    def _graph_node_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "id": row["id"],
            "nodeKind": row.get("node_kind"),
            "name": row.get("name"),
            "label": row.get("display_name") or row.get("qualified_name") or row.get("name") or row["id"],
            "qualifiedName": row.get("qualified_name"),
            "relativePath": row.get("relative_path"),
            "sourceId": row.get("source_id"),
            "flowDomain": row.get("flow_domain"),
            "factOrigin": row.get("fact_origin"),
            "lineStart": row.get("line_start"),
            "lineEnd": row.get("line_end"),
            "summary": row.get("summary"),
            "status": row.get("status"),
            "confidence": row.get("confidence"),
            "degree": int(row.get("graph_degree") or 0),
            "entrypoint": bool(row.get("entrypoint")),
        }

    def _anchor_expansion_node_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "graphId": "",
            "graphRevision": "",
            "nodeId": row["id"],
            "stableKey": row.get("stable_key") or row["id"],
            "nodeKind": row.get("node_kind"),
            "label": row.get("display_name") or row.get("qualified_name") or row.get("name") or row["id"],
            "relativePath": row.get("relative_path"),
            "qualifiedName": row.get("qualified_name"),
            "parentNodeId": row.get("parent_node_id"),
            "entrypoint": bool(row.get("entrypoint")),
        }

    def _anchor_expansion_edge_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "graphId": "",
            "graphRevision": "",
            "edgeId": row["id"],
            "edgeType": row["edge_type"],
            "fromNodeId": row["from_node_id"],
            "toNodeId": row["to_node_id"],
        }

    def _anchor_expansion_node(self, item: Dict[str, Any]) -> AnchorExpansionNode:
        return AnchorExpansionNode(
            source_id=str(item["sourceId"]),
            graph_id=str(item["graphId"]),
            graph_revision=str(item["graphRevision"]) if item.get("graphRevision") else None,
            node_id=str(item["nodeId"]),
            stable_key=str(item["stableKey"]),
            node_kind=str(item["nodeKind"] or ""),
            label=str(item["label"] or item["nodeId"]),
            parent_node_id=str(item["parentNodeId"]) if item.get("parentNodeId") else None,
            relative_path=str(item["relativePath"]) if item.get("relativePath") else None,
            qualified_name=str(item["qualifiedName"]) if item.get("qualifiedName") else None,
            entrypoint=bool(item.get("entrypoint")),
            score=float(item["score"]) if item.get("score") is not None else None,
        )

    def _anchor_expansion_edge(self, item: Dict[str, Any]) -> AnchorExpansionEdge:
        return AnchorExpansionEdge(
            source_id=str(item["sourceId"]),
            graph_id=str(item["graphId"]),
            graph_revision=str(item["graphRevision"]) if item.get("graphRevision") else None,
            edge_id=str(item["edgeId"]),
            edge_type=str(item["edgeType"]),
            from_node_id=str(item["fromNodeId"]),
            to_node_id=str(item["toNodeId"]),
        )

    def _anchor_entrypoint_hint(self, item: Dict[str, Any]) -> AnchorEntrypointHint:
        return AnchorEntrypointHint(
            source_id=str(item["sourceId"]),
            graph_id=str(item["graphId"]),
            graph_revision=str(item["graphRevision"]) if item.get("graphRevision") else None,
            node_id=str(item["nodeId"]),
            claim_id=str(item["claimId"]),
        )

    def _graph_edge_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        unresolved_target = None
        if row.get("unresolved_target_json"):
            try:
                unresolved_target = json.loads(row.get("unresolved_target_json"))
            except (TypeError, json.JSONDecodeError):
                unresolved_target = row.get("unresolved_target_json")
        return {
            "id": row["id"],
            "fromNodeId": row.get("from_node_id"),
            "toNodeId": row.get("to_node_id"),
            "fromLabel": row.get("from_display_name") or row.get("from_qualified_name") or row.get("from_name") or row.get("from_node_id"),
            "toLabel": row.get("to_display_name") or row.get("to_qualified_name") or row.get("to_name") or row.get("to_node_id"),
            "edgeType": row.get("edge_type"),
            "resolutionStatus": row.get("resolution_status"),
            "external": row.get("resolution_status") == graph_query_contract().external_target_status,
            "confidence": row.get("confidence"),
            "flowDomain": row.get("flow_domain"),
            "factOrigin": row.get("fact_origin"),
            "status": row.get("status"),
            "unresolvedTarget": unresolved_target,
            "metadata": {
                key: value
                for key, value in metadata.items()
                if key
                in {
                    "callKind",
                    "callTargetCategory",
                    "methodName",
                    "receiverText",
                    "receiverTypeHint",
                    "resolutionReason",
                    "sliceDefaultVisibility",
                    "targetTypeHint",
                    "targetTypeText",
                    "unresolvedReason",
                }
            },
        }

    def _fact_summary_from_claim_rows(self, claims: List[Dict[str, Any]], row: Dict[str, Any]) -> Dict[str, Any]:
        candidates = [
            claim
            for claim in claims
            if claim.get("claim_kind") == "RESPONSIBILITY" and claim.get("status") in {"TRUSTED", "CANDIDATE"} and not claim.get("rejection_reason")
        ]

        def pick(node_id: Optional[str]) -> Optional[Dict[str, Any]]:
            if not node_id:
                return None
            matches = [claim for claim in candidates if claim.get("node_id") == node_id]
            if not matches:
                return None
            return sorted(matches, key=lambda claim: (claim.get("status") == "TRUSTED", float(claim.get("confidence") or 0.0)), reverse=True)[0]

        direct = pick(row["id"])
        if direct:
            return self._summary_from_claim(direct, "DIRECT")
        parent = pick(row.get("parent_node_id"))
        if parent:
            return self._summary_from_claim(parent, "PARENT_FALLBACK")
        file_claim = next((claim for claim in candidates if claim.get("node_id") not in {row["id"], row.get("parent_node_id")}), None)
        if file_claim:
            return self._summary_from_claim(file_claim, "FILE_FALLBACK")
        return {"summary": None, "summarySource": "NONE", "summaryClaimId": None, "summaryClaimNodeId": None, "summaryConfidence": None, "summaryEvidenceCount": 0}

    def _summary_from_claim(self, claim: Dict[str, Any], source: str) -> Dict[str, Any]:
        return {
            "summary": claim.get("summary"),
            "summarySource": source,
            "summaryClaimId": claim.get("id"),
            "summaryClaimNodeId": claim.get("node_id"),
            "summaryConfidence": claim.get("confidence"),
            "summaryEvidenceCount": int(claim.get("evidence_count") or 0),
        }

    def _graph_status(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Dict[str, Any]:
        active = self._active_job_from_conn(conn)
        latest_row = conn.execute("SELECT * FROM analysis_jobs WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1").fetchone()
        latest = self._job(latest_row) if latest_row else None
        clauses = []
        params: List[Any] = []
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        inventory_where = "WHERE source_id = ?" if source_id else ""
        inventory_params = [source_id] if source_id else []
        inventory_count = conn.execute(f"SELECT COUNT(*) AS count FROM files {inventory_where}", inventory_params).fetchone()["count"]
        file_counts = conn.execute(
            f"""
            SELECT
                SUM(CASE WHEN af.status = 'ANALYZED' THEN 1 ELSE 0 END) AS analyzed,
                SUM(CASE WHEN af.status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                SUM(CASE WHEN af.status = 'SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS' THEN 1 ELSE 0 END) AS skipped,
                MAX(af.analyzed_at) AS last_analyzed_at
            FROM analysis_files af
            JOIN files f
              ON f.source_id = af.source_id
             AND f.relative_path = af.relative_path
             AND f.content_hash = af.content_hash
            {where.replace("source_id", "af.source_id")}
        """,
            params,
        ).fetchone()
        graph_node_clauses = [self._inventory_membership_graph_node_clause("n")]
        graph_edge_clauses = [self._inventory_membership_graph_edge_clause("e")]
        graph_params: List[Any] = []
        if source_id:
            graph_node_clauses.append("n.source_id = ?")
            graph_edge_clauses.append("e.source_id = ?")
            graph_params.append(source_id)
        node_count = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {' AND '.join(graph_node_clauses)}", graph_params).fetchone()[
            "count"
        ]
        edge_count = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {' AND '.join(graph_edge_clauses)}", graph_params).fetchone()[
            "count"
        ]
        trusted_status = graph_query_contract().trusted_status
        trusted_nodes = conn.execute(
            f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {' AND '.join(graph_node_clauses)} AND n.status = ?",
            [*graph_params, trusted_status],
        ).fetchone()["count"]
        trusted_edges = conn.execute(
            f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {' AND '.join(graph_edge_clauses)} AND e.status = ?",
            [*graph_params, trusted_status],
        ).fetchone()["count"]
        failed = int(file_counts["failed"] or 0)
        failure_summary = self._current_failure_summary(conn, source_id)
        analysis_state = self._current_analysis_state(conn, [source_id] if source_id else None)
        processed = analysis_state["completedFiles"]
        running_for_source = active is not None and (
            not source_id or active.get("currentSourceId") == source_id or source_id in (active.get("sourceIds") or [])
        )
        total_files = int(inventory_count or (active or {}).get("fileCount") or (latest or {}).get("fileCount") or 0)
        if running_for_source:
            analysis_status = "RUNNING"
            job_id = active.get("jobId")
            current_file = active.get("currentRelativePath") if not source_id or active.get("currentSourceId") == source_id else None
            last_updated = active.get("lastProgressAt") or active.get("startedAt")
            processed = analysis_state["completedFiles"]
        elif inventory_count == 0 and node_count == 0 and edge_count == 0:
            analysis_status = "EMPTY"
            job_id = latest.get("jobId") if latest else None
            current_file = None
            last_updated = file_counts["last_analyzed_at"] or (latest or {}).get("completedAt")
        elif processed == 0:
            analysis_status = "NOT_ANALYZED"
            job_id = latest.get("jobId") if latest else None
            current_file = None
            last_updated = file_counts["last_analyzed_at"] or (latest or {}).get("completedAt")
        elif failed > 0 or processed < int(inventory_count or 0):
            analysis_status = "PARTIAL"
            job_id = latest.get("jobId") if latest else None
            current_file = None
            last_updated = file_counts["last_analyzed_at"] or (latest or {}).get("completedAt")
        else:
            analysis_status = "READY"
            job_id = latest.get("jobId") if latest else None
            current_file = None
            last_updated = file_counts["last_analyzed_at"] or (latest or {}).get("completedAt")
        progress = analysis_state["completionPercent"]
        diagnostics_count = conn.execute(
            f"""
            SELECT COUNT(*) AS count
            FROM analysis_files
            {where}
              {"AND" if where else "WHERE"} diagnostics_json IS NOT NULL
              AND diagnostics_json != '[]'
        """,
            params,
        ).fetchone()["count"]
        return {
            "analysisStatus": analysis_status,
            "jobId": job_id,
            "engineVersion": "GRAPH_V1",
            "processedFileCount": processed,
            "processedFiles": processed,
            "fileCount": total_files,
            "failedFileCount": failed,
            "failedFiles": failed,
            **analysis_state,
            "currentFailedFileCount": failure_summary["currentFailedFileCount"],
            "currentPartialFileCount": failure_summary["currentPartialFileCount"],
            "retryableFailureCount": failure_summary["retryableFailureCount"],
            "failureCodeBreakdown": failure_summary["failureCodeBreakdown"],
            "activeJob": active,
            "progressPercent": progress,
            "currentFile": current_file,
            "trustedFactsCount": int(trusted_nodes or 0) + int(trusted_edges or 0),
            "diagnosticsCount": int(diagnostics_count or 0),
            "lastUpdatedAt": last_updated,
        }

    def _active_job_from_conn(self, conn: sqlite3.Connection) -> Optional[Dict[str, Any]]:
        row = conn.execute("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY started_at DESC LIMIT 1").fetchone()
        return self._job(row) if row else None

    def _graph_diagnostic(self, item: Dict[str, Any], source_id: Optional[str], relative_path: Optional[str]) -> Dict[str, Any]:
        code = item.get("code") or "DIAGNOSTIC"
        severity = item.get("severity")
        if not severity:
            severity = "ERROR" if "FAILED" in code or "ERROR" in code else "WARN"
        return {
            "severity": severity,
            "stage": item.get("stage") or "ANALYSIS",
            "code": code,
            "sourceId": item.get("sourceId") or source_id,
            "file": item.get("relativePath") or relative_path,
            "relativePath": item.get("relativePath") or relative_path,
            "message": item.get("message") or "-",
            **({"metadata": item.get("metadata")} if item.get("metadata") else {}),
            **({"rawPreview": item.get("rawPreview")} if item.get("rawPreview") else {}),
        }

    def _row_dict(self, row) -> Dict[str, Any]:
        return dict(row)

    def _json_dict(self, value: Any) -> Dict[str, Any]:
        if isinstance(value, dict):
            return value
        if not value:
            return {}
        try:
            parsed = json.loads(value)
        except (TypeError, json.JSONDecodeError):
            return {}
        return parsed if isinstance(parsed, dict) else {}

    def _json_list(self, value: Any) -> List[Any]:
        if isinstance(value, list):
            return value
        if not value:
            return []
        try:
            parsed = json.loads(value)
        except (TypeError, json.JSONDecodeError):
            return []
        return parsed if isinstance(parsed, list) else []

    def _graph_job_id(self, graph: Dict[str, List[Dict[str, Any]]], state: Dict[str, Any]) -> str:
        for key in ("nodes", "edges", "claims", "evidence", "diagnostics"):
            for row in graph.get(key) or []:
                if row.get("job_id"):
                    return str(row["job_id"])
        return str(state.get("job_id") or "direct")

    def _graph_identity_by_source(self, conn: sqlite3.Connection, source_ids: List[str]) -> Dict[str, Dict[str, Optional[str]]]:
        if not source_ids or not self._table_exists(conn, "analysis_graph_state"):
            return {}
        placeholders = ",".join("?" for _ in source_ids)
        rows = conn.execute(
            f"""
            SELECT source_id, graph_id, content_identity
            FROM analysis_graph_state
            WHERE source_id IN ({placeholders})
            """,
            source_ids,
        ).fetchall()
        return {
            str(row["source_id"]): {
                "graphId": row["graph_id"],
                "graphRevision": row["content_identity"],
            }
            for row in rows
        }

    def _reconcile_graph_runtime_inventory_membership(self, conn: sqlite3.Connection) -> None:
        if not self._table_exists(conn, "analysis_graph_nodes"):
            return
        source_ids = {
            str(row["source_id"])
            for row in conn.execute(
                """
                SELECT source_id FROM analysis_graph_nodes WHERE source_id IS NOT NULL
                UNION
                SELECT source_id FROM analysis_graph_edges WHERE source_id IS NOT NULL
                UNION
                SELECT source_id FROM analysis_graph_claims WHERE source_id IS NOT NULL
                UNION
                SELECT source_id FROM analysis_graph_evidence WHERE source_id IS NOT NULL
                UNION
                SELECT source_id FROM analysis_graph_state WHERE source_id IS NOT NULL
                UNION
                SELECT source_id FROM semantic_documents WHERE source_id IS NOT NULL
                """
            ).fetchall()
            if row["source_id"]
        }
        affected_sources = self._delete_graph_rows_without_inventory_membership(conn)
        affected_sources.update(self._purge_stale_semantic_documents(conn))
        source_ids.update(affected_sources)
        now = datetime.now(timezone.utc).isoformat()
        for source_id in sorted(source_ids):
            self._refresh_graph_state(conn, source_id, now)
        self._mark_semantic_sources_stale_lightweight(conn, affected_sources)

    def _mark_semantic_sources_stale_lightweight(self, conn: sqlite3.Connection, source_ids: Set[str]) -> None:
        if not source_ids or not self._table_exists(conn, "semantic_index_state"):
            return
        now = datetime.now(timezone.utc).isoformat()
        for source_id in sorted(source_ids):
            conn.execute(
                """
                UPDATE semantic_index_state
                SET status = ?,
                    indexed_node_count = 0,
                    embedding_model = NULL,
                    embedding_dimension = NULL,
                    updated_at = ?,
                    started_at = NULL
                WHERE source_id = ?
                """,
                (SemanticIndexStatus.STALE.value, now, source_id),
            )

    def _purge_stale_semantic_documents(self, conn: sqlite3.Connection) -> Set[str]:
        if not all(self._table_exists(conn, table) for table in ("semantic_documents", "analysis_graph_nodes", "analysis_graph_state")):
            return set()
        stale_rows = conn.execute(
            """
            SELECT DISTINCT d.source_id
            FROM semantic_documents d
            LEFT JOIN analysis_graph_nodes n
              ON n.source_id = d.source_id
             AND n.id = d.node_id
            LEFT JOIN analysis_graph_state state
              ON state.source_id = d.source_id
            WHERE n.id IS NULL
               OR state.graph_id IS NULL
               OR d.graph_id != state.graph_id
            """
        ).fetchall()
        source_ids = {str(row["source_id"]) for row in stale_rows if row["source_id"]}
        if not source_ids:
            return set()
        conn.execute(
            """
            DELETE FROM semantic_documents
            WHERE document_id IN (
                SELECT d.document_id
                FROM semantic_documents d
                LEFT JOIN analysis_graph_nodes n
                  ON n.source_id = d.source_id
                 AND n.id = d.node_id
                LEFT JOIN analysis_graph_state state
                  ON state.source_id = d.source_id
                WHERE n.id IS NULL
                   OR state.graph_id IS NULL
                   OR d.graph_id != state.graph_id
            )
            """
        )
        if self._table_exists(conn, "semantic_vectors"):
            conn.execute(
                """
                DELETE FROM semantic_vectors
                WHERE document_id NOT IN (SELECT document_id FROM semantic_documents)
                """
        )
        return source_ids

    def _delete_graph_rows_without_inventory_membership(self, conn: sqlite3.Connection) -> Set[str]:
        if not all(self._table_exists(conn, table) for table in ("analysis_graph_nodes", "analysis_files", "files")):
            return set()
        if not {"id", "source_id", "relative_path", "content_hash"}.issubset(self._table_columns(conn, "files")):
            return set()
        if not {"file_id", "source_id", "relative_path", "content_hash"}.issubset(self._table_columns(conn, "analysis_files")):
            return set()
        invalid_nodes = conn.execute(
            """
            SELECT n.source_id, n.id
            FROM analysis_graph_nodes n
            WHERE NOT EXISTS (
                    SELECT 1
                    FROM analysis_files af
                    WHERE af.source_id = n.source_id
                      AND af.relative_path = n.relative_path
                      AND af.content_hash = n.content_hash
                )
               OR NOT EXISTS (
                    SELECT 1
                    FROM files f
                    WHERE f.source_id = n.source_id
                      AND f.relative_path = n.relative_path
                      AND f.content_hash = n.content_hash
                )
            """
        ).fetchall()
        affected_sources = {str(row["source_id"]) for row in invalid_nodes if row["source_id"]}
        node_ids = [str(row["id"]) for row in invalid_nodes if row["id"]]
        for batch in _chunks(node_ids, 400):
            placeholders = ",".join("?" for _ in batch)
            self._delete_semantic_documents_for_nodes(conn, batch)
            conn.execute(f"DELETE FROM analysis_graph_nodes WHERE id IN ({placeholders})", batch)
        for table in ("analysis_graph_edges", "analysis_graph_evidence", "analysis_graph_diagnostics"):
            if not self._table_exists(conn, table):
                continue
            rows = conn.execute(
                f"""
                SELECT DISTINCT row.source_id
                FROM {table} row
                WHERE NOT EXISTS (
                        SELECT 1
                        FROM analysis_files af
                        WHERE af.source_id = row.source_id
                          AND af.relative_path = row.relative_path
                          AND af.content_hash = row.content_hash
                    )
                   OR NOT EXISTS (
                        SELECT 1
                        FROM files f
                        WHERE f.source_id = row.source_id
                          AND f.relative_path = row.relative_path
                          AND f.content_hash = row.content_hash
                    )
                """
            ).fetchall()
            affected_sources.update(str(row["source_id"]) for row in rows if row["source_id"])
            conn.execute(
                f"""
                DELETE FROM {table}
                WHERE id IN (
                    SELECT row.id
                    FROM {table} row
                    WHERE NOT EXISTS (
                            SELECT 1
                            FROM analysis_files af
                            WHERE af.source_id = row.source_id
                              AND af.relative_path = row.relative_path
                              AND af.content_hash = row.content_hash
                        )
                       OR NOT EXISTS (
                            SELECT 1
                            FROM files f
                            WHERE f.source_id = row.source_id
                              AND f.relative_path = row.relative_path
                              AND f.content_hash = row.content_hash
                        )
                )
                """
            )
        orphan_edges = conn.execute(
            """
            SELECT DISTINCT edge.source_id
            FROM analysis_graph_edges edge
            LEFT JOIN analysis_graph_nodes from_node
              ON from_node.source_id = edge.source_id
             AND from_node.id = edge.from_node_id
            LEFT JOIN analysis_graph_nodes to_node
              ON to_node.source_id = edge.source_id
             AND to_node.id = edge.to_node_id
            WHERE from_node.id IS NULL
               OR (edge.to_node_id IS NOT NULL AND to_node.id IS NULL)
            """
        ).fetchall()
        affected_sources.update(str(row["source_id"]) for row in orphan_edges if row["source_id"])
        conn.execute(
            """
            DELETE FROM analysis_graph_edges
            WHERE id IN (
                SELECT edge.id
                FROM analysis_graph_edges edge
                LEFT JOIN analysis_graph_nodes from_node
                  ON from_node.source_id = edge.source_id
                 AND from_node.id = edge.from_node_id
                LEFT JOIN analysis_graph_nodes to_node
                  ON to_node.source_id = edge.source_id
                 AND to_node.id = edge.to_node_id
                WHERE from_node.id IS NULL
                   OR (edge.to_node_id IS NOT NULL AND to_node.id IS NULL)
            )
            """
        )
        return affected_sources

    def _delete_file_graph(self, conn: sqlite3.Connection, file_id: int) -> Set[str]:
        graph_node_ids = [
            row["id"]
            for row in conn.execute(
                "SELECT id FROM analysis_graph_nodes WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?",
                (file_id, file_id, file_id),
            ).fetchall()
        ]
        source_ids = {
            str(row["source_id"])
            for row in conn.execute(
                """
                SELECT source_id FROM analysis_graph_nodes WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_edges WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_evidence WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_diagnostics WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?
                """,
                (file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id),
            ).fetchall()
            if row["source_id"]
        }
        if graph_node_ids:
            self._delete_semantic_documents_for_nodes(conn, graph_node_ids)
        conn.execute("DELETE FROM analysis_graph_nodes WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?", (file_id, file_id, file_id))
        conn.execute(
            "DELETE FROM analysis_graph_edges WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?",
            (file_id, file_id, file_id),
        )
        conn.execute(
            "DELETE FROM analysis_graph_evidence WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?",
            (file_id, file_id, file_id),
        )
        conn.execute(
            "DELETE FROM analysis_graph_diagnostics WHERE analysis_file_id = ? OR inventory_file_id = ? OR file_id = ?",
            (file_id, file_id, file_id),
        )
        return source_ids

    def _delete_semantic_documents_for_nodes(self, conn: sqlite3.Connection, node_ids: List[str]) -> None:
        if not node_ids or not self._table_exists(conn, "semantic_documents"):
            return
        placeholders = ",".join("?" for _ in node_ids)
        conn.execute(
            f"""
            DELETE FROM semantic_documents
            WHERE node_id IN ({placeholders})
            """,
            node_ids,
        )

    def _refresh_graph_state(self, conn: sqlite3.Connection, source_id: str, updated_at: Optional[str] = None) -> Optional[str]:
        updated_at = updated_at or datetime.now(timezone.utc).isoformat()
        counts = conn.execute(
            """
            SELECT
              (SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?) AS node_count,
              (SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = ?) AS edge_count,
              (SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = ?) AS claim_count,
              (SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = ?) AS evidence_count
            """,
            (source_id, source_id, source_id, source_id),
        ).fetchone()
        node_count = int(counts["node_count"] or 0)
        edge_count = int(counts["edge_count"] or 0)
        claim_count = int(counts["claim_count"] or 0)
        evidence_count = int(counts["evidence_count"] or 0)
        if node_count <= 0:
            conn.execute("DELETE FROM analysis_graph_state WHERE source_id = ?", (source_id,))
            return None
        content_identity = SemanticIndexStore.compute_graph_revision_conn(conn, source_id)
        graph_id = content_identity
        conn.execute(
            """
            INSERT INTO analysis_graph_state(
                source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_id) DO UPDATE SET
                graph_id = excluded.graph_id,
                content_identity = excluded.content_identity,
                node_count = excluded.node_count,
                edge_count = excluded.edge_count,
                claim_count = excluded.claim_count,
                evidence_count = excluded.evidence_count,
                updated_at = excluded.updated_at
            """,
            (source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, updated_at),
        )
        return graph_id

    def _delete_analysis_identity(self, conn: sqlite3.Connection, state: Dict[str, Any]) -> Set[str]:
        rows = conn.execute(
            """
            SELECT file_id
            FROM analysis_files
            WHERE source_id = ?
              AND relative_path = ?
              AND content_hash = ?
            """,
            (state["source_id"], state["relative_path"], state["content_hash"]),
        ).fetchall()
        affected_sources: Set[str] = set()
        for row in rows:
            file_id = int(row["file_id"])
            affected_sources.update(self._delete_file_analysis(conn, file_id))
            conn.execute("DELETE FROM analysis_files WHERE file_id = ?", (file_id,))
        return affected_sources

    def _delete_file_analysis(self, conn: sqlite3.Connection, file_id: int) -> Set[str]:
        sources = {
            str(row["source_id"])
            for row in conn.execute(
                """
                SELECT source_id FROM analysis_files WHERE file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_nodes WHERE analysis_file_id = ? OR inventory_file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_edges WHERE analysis_file_id = ? OR inventory_file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_evidence WHERE analysis_file_id = ? OR inventory_file_id = ?
                UNION
                SELECT source_id FROM analysis_graph_diagnostics WHERE analysis_file_id = ? OR inventory_file_id = ?
                """,
                (file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id, file_id),
            ).fetchall()
            if row["source_id"]
        }
        for source_id in sorted(sources):
            self._delete_file_graph(conn, file_id)
            self._refresh_graph_state(conn, source_id, datetime.now(timezone.utc).isoformat())
        self._delete_semantic_index_for_file_ids(conn, [file_id])
        return sources

    def _delete_semantic_index_for_file_ids(self, conn: sqlite3.Connection, file_ids: List[int]) -> Set[str]:
        file_ids = sorted({int(file_id) for file_id in file_ids if file_id is not None})
        if not file_ids or not all(
            self._table_exists(conn, table)
            for table in ("analysis_graph_nodes", "semantic_documents", "semantic_vectors")
        ):
            return set()
        affected_sources: Set[str] = set()
        for batch in _chunks(file_ids, 400):
            placeholders = ",".join("?" for _ in batch)
            source_rows = conn.execute(
                f"""
                SELECT DISTINCT d.source_id
                FROM semantic_documents d
                JOIN analysis_graph_nodes n
                  ON n.source_id = d.source_id
                 AND n.id = d.node_id
                WHERE COALESCE(n.analysis_file_id, n.inventory_file_id, n.file_id) IN ({placeholders})
                """,
                [*batch],
            ).fetchall()
            affected_sources.update(str(row["source_id"]) for row in source_rows if row["source_id"])
            conn.execute(
                f"""
                DELETE FROM semantic_vectors
                WHERE document_id IN (
                    SELECT d.document_id
                    FROM semantic_documents d
                    JOIN analysis_graph_nodes n
                      ON n.source_id = d.source_id
                     AND n.id = d.node_id
                    WHERE COALESCE(n.analysis_file_id, n.inventory_file_id, n.file_id) IN ({placeholders})
                )
                """,
                [*batch],
            )
            conn.execute(
                f"""
                DELETE FROM semantic_documents
                WHERE document_id IN (
                    SELECT d.document_id
                    FROM semantic_documents d
                    JOIN analysis_graph_nodes n
                      ON n.source_id = d.source_id
                     AND n.id = d.node_id
                    WHERE COALESCE(n.analysis_file_id, n.inventory_file_id, n.file_id) IN ({placeholders})
                )
                """,
                [*batch],
            )
        return affected_sources

    def _mark_semantic_sources_stale(self, conn: sqlite3.Connection, source_ids: Set[str]) -> None:
        if not source_ids or not self._table_exists(conn, "semantic_index_state"):
            return
        for source_id in sorted(source_ids):
            graph = SemanticIndexStore.current_graph_info_conn(conn, source_id)
            if graph.graph_revision and graph.total_node_count > 0:
                SemanticIndexStore.mark_source_stale_conn(conn, source_id, graph.graph_revision, graph.total_node_count)

    def _resolve_source_call_edges(self, conn: sqlite3.Connection, source_id: str) -> None:
        contract = graph_query_contract()
        pending_status_sql, pending_status_params = sql_in_clause(contract.resolver_pending_statuses())
        rows = conn.execute(
            f"""
            SELECT id, metadata_json, unresolved_target_json, argument_count
            FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type = ?
              AND to_node_id IS NULL
              AND resolution_status IN ({pending_status_sql})
        """,
            (source_id, contract.calls_edge_type, *pending_status_params),
        ).fetchall()
        if not rows:
            return
        type_rows = conn.execute(
            """
            SELECT id, name, qualified_name
            FROM analysis_graph_nodes
            WHERE source_id = ?
              AND node_kind = ?
              AND status = ?
        """,
            (source_id, contract.type_node_kind, contract.trusted_status),
        ).fetchall()
        types_by_simple: Dict[str, List[sqlite3.Row]] = {}
        types_by_qualified: Dict[str, List[sqlite3.Row]] = {}
        for row in type_rows:
            types_by_simple.setdefault(row["name"], []).append(row)
            if row["qualified_name"]:
                types_by_qualified.setdefault(row["qualified_name"], []).append(row)
        for edge in rows:
            metadata = self._json_dict(edge["metadata_json"])
            unresolved_target = self._json_dict(edge["unresolved_target_json"])
            method_name = unresolved_target.get("name")
            type_hint = unresolved_target.get("receiverTypeHint") or unresolved_target.get("targetTypeText")
            if not method_name or not type_hint:
                continue
            type_candidates = types_by_qualified.get(str(type_hint), []) or types_by_simple.get(str(type_hint).rsplit(".", 1)[-1], [])
            if len(type_candidates) != 1:
                if len(type_candidates) > 1:
                    self._mark_call_edge_multiple(conn, edge["id"], metadata, len(type_candidates))
                continue
            callable_candidates = self._callable_candidates_for_type(
                conn, type_candidates[0]["id"], str(method_name), edge["argument_count"]
            )
            if len(callable_candidates) == 1:
                metadata = self._resolved_call_metadata(metadata, unresolved_target)
                metadata = self._edge_metadata_for_storage(metadata)
                conn.execute(
                    """
                    UPDATE analysis_graph_edges
                    SET to_node_id = ?,
                        resolution_status = ?,
                        unresolved_target_json = NULL,
                        metadata_json = ?
                    WHERE id = ?
                """,
                    (callable_candidates[0]["id"], contract.resolved_status, json.dumps(metadata), edge["id"]),
                )
            elif len(callable_candidates) > 1:
                self._mark_call_edge_multiple(conn, edge["id"], metadata, len(callable_candidates))

    def _resolved_call_metadata(self, metadata: Dict[str, Any], unresolved_target: Dict[str, Any]) -> Dict[str, Any]:
        result = dict(metadata or {})
        call_kind = str(result.get("callKind") or "")
        receiver_type_hint = unresolved_target.get("receiverTypeHint")
        target_type_text = unresolved_target.get("targetTypeText") or receiver_type_hint
        result.pop("unresolvedReason", None)
        result.pop("unresolvedTarget", None)
        result["resolutionReason"] = self._resolved_call_reason(call_kind, receiver_type_hint, target_type_text)
        if "METHOD_REFERENCE" in call_kind:
            if receiver_type_hint:
                result["receiverTypeHint"] = receiver_type_hint
            if target_type_text:
                result["targetTypeText"] = target_type_text
        return classify_call_metadata(result, result.get("flowDomain"), graph_query_contract().resolved_status, None)

    def _resolved_call_reason(self, call_kind: str, receiver_type_hint: Optional[str], target_type_text: Optional[str]) -> str:
        if call_kind in {"FIELD_RECEIVER", "FIELD_METHOD_REFERENCE"} and receiver_type_hint:
            return "FIELD_TYPE_HINT"
        if call_kind in {"PARAMETER_RECEIVER", "PARAMETER_METHOD_REFERENCE"} and receiver_type_hint:
            return "PARAMETER_TYPE_HINT"
        if call_kind in {"LOCAL_VARIABLE_RECEIVER", "LOCAL_VARIABLE_METHOD_REFERENCE"} and receiver_type_hint:
            return "LOCAL_VARIABLE_TYPE_HINT"
        if call_kind in {"STATIC_METHOD", "STATIC_METHOD_REFERENCE"} and target_type_text:
            return "QUALIFIED_NAME_MATCH"
        if call_kind in {"LOCAL_METHOD", "THIS_METHOD", "METHOD_REFERENCE"}:
            return "SAME_TYPE_METHOD"
        return "SAME_FILE_UNIQUE_METHOD"

    def _callable_candidates_for_type(
        self, conn: sqlite3.Connection, type_node_id: str, method_name: str, argument_count: Optional[int]
    ) -> List[sqlite3.Row]:
        contract = graph_query_contract()
        rows = conn.execute(
            """
            SELECT id, parameter_count
            FROM analysis_graph_nodes
            WHERE parent_node_id = ?
              AND node_kind = ?
              AND name = ?
              AND status = ?
        """,
            (type_node_id, contract.callable_node_kind, method_name, contract.trusted_status),
        ).fetchall()
        if argument_count is None:
            return rows
        matching = [row for row in rows if row["parameter_count"] is not None and int(row["parameter_count"]) == int(argument_count)]
        return matching

    def _mark_call_edge_multiple(self, conn: sqlite3.Connection, edge_id: str, metadata: Dict[str, Any], candidate_count: int) -> None:
        contract = graph_query_contract()
        metadata = classify_call_metadata(metadata, metadata.get("flowDomain"), contract.multiple_candidates_status, None)
        metadata = self._edge_metadata_for_storage(metadata)
        conn.execute(
            """
            UPDATE analysis_graph_edges
            SET resolution_status = ?,
                metadata_json = ?
            WHERE id = ?
        """,
            (contract.multiple_candidates_status, json.dumps(metadata), edge_id),
        )

    def _edge_metadata_for_storage(self, metadata: Dict[str, Any]) -> Dict[str, Any]:
        return {
            key: value
            for key, value in (metadata or {}).items()
            if key
            in {
                "callKind",
                "callTargetCategory",
                "methodName",
                "receiverText",
                "receiverTypeHint",
                "resolutionReason",
                "sliceDefaultVisibility",
                "targetTypeHint",
                "targetTypeText",
                "unresolvedReason",
            }
            and value is not None
        }

    def _published_analysis_identity_row(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> Optional[sqlite3.Row]:
        return conn.execute(
            """
            SELECT af.file_id
            FROM analysis_files af
            WHERE af.source_id = ?
              AND af.relative_path = ?
              AND af.content_hash = ?
              AND af.status IN ('ANALYZED', 'PARTIAL')
              AND EXISTS (
                  SELECT 1
                  FROM files f
                  WHERE f.source_id = af.source_id
                    AND f.relative_path = af.relative_path
                    AND f.content_hash = af.content_hash
              )
            ORDER BY CASE WHEN af.file_id = ? THEN 0 ELSE 1 END, af.file_id
            LIMIT 1
            """,
            (state["source_id"], state["relative_path"], state["content_hash"], file_id),
        ).fetchone()

    def _analysis_identity_row(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> Optional[sqlite3.Row]:
        return conn.execute(
            """
            SELECT file_id
            FROM analysis_files
            WHERE source_id = ?
              AND relative_path = ?
              AND content_hash = ?
            ORDER BY CASE WHEN file_id = ? THEN 0 ELSE 1 END, file_id
            LIMIT 1
            """,
            (state["source_id"], state["relative_path"], state["content_hash"], file_id),
        ).fetchone()

    def _analysis_file_values(self, file_id: int, state: Dict[str, Any]) -> tuple:
        return (
            file_id,
            state["source_id"],
            state["relative_path"],
            state["content_hash"],
            state["analyzer_name"],
            state["analyzer_version"],
            state["status"],
            state.get("analyzed_at"),
            state.get("attempt_count", 0),
            state.get("last_attempt_at"),
            state.get("last_error_code"),
            state.get("last_error_message"),
            state.get("last_raw_response_preview"),
            json.dumps(state.get("diagnostics") or []),
            state.get("engine_version"),
            state.get("flow_domain"),
        )

    def _insert_file(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute(
            """
            INSERT INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
            self._analysis_file_values(file_id, state),
        )

    def _update_analysis_file_row(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute(
            """
            UPDATE analysis_files
            SET source_id = ?,
                relative_path = ?,
                content_hash = ?,
                analyzer_name = ?,
                analyzer_version = ?,
                status = ?,
                analyzed_at = ?,
                attempt_count = ?,
                last_attempt_at = ?,
                last_error_code = ?,
                last_error_message = ?,
                last_raw_response_preview = ?,
                diagnostics_json = ?,
                engine_version = ?,
                flow_domain = ?
            WHERE file_id = ?
            """,
            (
                state["source_id"],
                state["relative_path"],
                state["content_hash"],
                state["analyzer_name"],
                state["analyzer_version"],
                state["status"],
                state.get("analyzed_at"),
                state.get("attempt_count", 0),
                state.get("last_attempt_at"),
                state.get("last_error_code"),
                state.get("last_error_message"),
                state.get("last_raw_response_preview"),
                json.dumps(state.get("diagnostics") or []),
                state.get("engine_version"),
                state.get("flow_domain"),
                file_id,
            ),
        )

    def _update_analysis_file_attempt_metadata(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute(
            """
            UPDATE analysis_files
            SET attempt_count = ?,
                last_attempt_at = ?,
                last_error_code = ?,
                last_error_message = ?,
                last_raw_response_preview = ?
            WHERE file_id = ?
            """,
            (
                state.get("attempt_count", 0),
                state.get("last_attempt_at"),
                state.get("last_error_code"),
                state.get("last_error_message"),
                state.get("last_raw_response_preview"),
                file_id,
            ),
        )

    def _upsert_file(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
            self._analysis_file_values(file_id, state),
        )

    def _job_params(self, job: Dict[str, Any]):
        return (
            job["jobId"],
            job["status"],
            job.get("startedAt"),
            job.get("completedAt"),
            job.get("sourceCount", 0),
            job.get("fileCount", 0),
            job.get("processedFileCount", 0),
            job.get("failedFileCount", 0),
            job.get("currentSourceId"),
            job.get("currentRelativePath"),
            json.dumps(job.get("sourceIds") or []),
            job.get("lastProgressAt"),
            json.dumps(job.get("diagnostics") or []),
            job.get("engineVersion"),
            job.get("mode") or "FULL",
        )

    def _overview_sources_for_job(self, conn: sqlite3.Connection, job_id: str, fallback_source_ids: List[str]) -> List[str]:
        rows = conn.execute(
            "SELECT DISTINCT source_id FROM analysis_job_files WHERE job_id = ? ORDER BY source_id",
            (job_id,),
        ).fetchall()
        source_ids = [row["source_id"] for row in rows]
        if source_ids:
            return source_ids
        return sorted({source_id for source_id in fallback_source_ids if source_id})

    def _job(self, row) -> Dict[str, Any]:
        return {
            "jobId": row["job_id"],
            "status": row["status"],
            "startedAt": row["started_at"],
            "completedAt": row["completed_at"],
            "sourceCount": row["source_count"],
            "fileCount": row["file_count"],
            "processedFileCount": row["processed_file_count"],
            "processedFiles": row["processed_file_count"],
            "failedFileCount": row["failed_file_count"],
            "failedFiles": row["failed_file_count"],
            "currentSourceId": row["current_source_id"],
            "currentRelativePath": row["current_relative_path"],
            "sourceIds": json.loads(row["source_ids_json"] or "[]"),
            "lastProgressAt": row["last_progress_at"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
            "engineVersion": row["engine_version"] if "engine_version" in row.keys() else None,
            "mode": row["mode"] if "mode" in row.keys() else "FULL",
        }

    def _file(self, row) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "relativePath": row["relative_path"],
            "contentHash": row["content_hash"],
            "analysisStatus": row["status"],
            "analyzedAt": row["analyzed_at"],
            "attemptCount": row["attempt_count"],
            "lastAttemptAt": row["last_attempt_at"],
            "lastErrorCode": row["last_error_code"],
            "lastErrorMessage": row["last_error_message"],
            "lastRawResponsePreview": row["last_raw_response_preview"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
            "engineVersion": row["engine_version"] if "engine_version" in row.keys() else None,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() else None,
        }

    def _diagnostic_detail(self, row) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"],
            "severity": row["severity"],
            "stage": row["stage"],
            "code": row["code"],
            "message": row["message"],
            "lineStart": row["line_start"],
            "lineEnd": row["line_end"],
            "metadata": json.loads(row["metadata_json"] or "{}"),
            "createdAt": row["created_at"],
        }

    def _runtime_event(self, row) -> Dict[str, Any]:
        return {
            "id": row["id"],
            "jobId": row["job_id"],
            "sourceId": row["source_id"],
            "inventoryFileId": row["inventory_file_id"],
            "analysisFileId": row["analysis_file_id"],
            "relativePath": row["relative_path"],
            "contentHash": row["content_hash"],
            "attempt": row["attempt"],
            "stage": row["stage"],
            "eventType": row["event_type"],
            "status": row["status"],
            "startedAt": row["started_at"],
            "completedAt": row["completed_at"],
            "durationMs": row["duration_ms"],
            "errorCode": row["error_code"],
            "errorMessage": row["error_message"],
            "metadata": json.loads(row["metadata_json"] or "{}"),
            "createdAt": row["created_at"],
        }

    def _connect(self, busy_timeout_ms: int = SQLITE_WRITE_BUSY_TIMEOUT_MS) -> sqlite3.Connection:
        timeout_seconds = max(busy_timeout_ms, 1) / 1000.0
        conn = observed_connect(self.db_path, timeout=timeout_seconds)
        conn.execute(f"PRAGMA busy_timeout = {max(int(busy_timeout_ms), 1)}")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        return conn

    def _write_with_busy_retry(self, action):
        attempts = len(GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS) + 1
        for attempt in range(attempts):
            try:
                with self._connect() as conn:
                    return action(conn)
            except sqlite3.OperationalError as exc:
                if not self._is_sqlite_busy(exc) or attempt >= attempts - 1:
                    raise
                time.sleep(GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS[attempt])
        return None

    def _is_sqlite_busy(self, exc: sqlite3.Error) -> bool:
        return self._is_sqlite_busy_message(str(exc))

    def _is_sqlite_busy_message(self, message: str) -> bool:
        message = message.lower()
        return "database is locked" in message or "database is busy" in message or "locked" in message

    def _graph_store_error(self, table: str, operation: str, exc: sqlite3.Error) -> KnowledgeError:
        return KnowledgeError(
            "ANALYSIS_GRAPH_STORE_FAILED",
            f"Graph persistence failed while writing {table}.",
            stage="GRAPH_STORE",
            severity="ERROR",
            table=table,
            operation=operation,
            exceptionType=type(exc).__name__,
            sqliteMessage=str(exc),
        )

    def _table_sql(self, conn: sqlite3.Connection, table: str) -> str:
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
        return str(row["sql"] or "") if row else ""

    def _table_exists(self, conn: sqlite3.Connection, table: str) -> bool:
        row = conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
        return row is not None

    def _table_columns(self, conn: sqlite3.Connection, table: str) -> Set[str]:
        if not self._table_exists(conn, table):
            return set()
        return {str(row["name"]) for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}

    def _table_names(self, conn: sqlite3.Connection) -> Set[str]:
        return {
            str(row["name"])
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()
        }

    def _drop_tables_child_first(self, conn: sqlite3.Connection, tables: Set[str]) -> None:
        pending = set(tables)
        while pending:
            referenced_by_remaining: Set[str] = set()
            for table in pending:
                for row in conn.execute(f'PRAGMA foreign_key_list("{table.replace(chr(34), chr(34) + chr(34))}")').fetchall():
                    target = str(row["table"])
                    if target in pending:
                        referenced_by_remaining.add(target)
            leaves = sorted(pending - referenced_by_remaining)
            if not leaves:
                leaves = sorted(pending)
            for table in leaves:
                quoted = table.replace('"', '""')
                conn.execute(f'DROP TABLE IF EXISTS "{quoted}"')
                pending.remove(table)

    def _drop_graph_lifecycle_triggers(self, conn: sqlite3.Connection, reset_tables: Set[str]) -> None:
        rows = conn.execute(
            "SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'trigger'"
        ).fetchall()
        for row in rows:
            name = str(row["name"])
            table_name = str(row["tbl_name"] or "")
            sql = str(row["sql"] or "")
            if table_name in reset_tables or any(table in sql for table in reset_tables):
                conn.execute(f'DROP TRIGGER IF EXISTS "{name.replace(chr(34), chr(34) + chr(34))}"')

    def _run_schema_migrations(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
        """)
        applied = {row["version"] for row in conn.execute("SELECT version FROM analysis_schema_migrations").fetchall()}
        expected_version = 1
        for version, name in ANALYSIS_SCHEMA_MIGRATIONS:
            if version != expected_version:
                raise RuntimeError("Analysis schema migrations must be sequential")
            expected_version += 1
            if version in applied:
                continue
            self._apply_schema_migration(conn, version)
            conn.execute(
                "INSERT INTO analysis_schema_migrations(version, name, applied_at) VALUES (?, ?, ?)",
                (version, name, datetime.now(timezone.utc).isoformat()),
            )

    def _apply_schema_migration(self, conn: sqlite3.Connection, version: int) -> None:
        if version == 1:
            return
        if version == 2:
            self._ensure_column(conn, "analysis_jobs", "source_ids_json", "TEXT")
            return
        if version == 3:
            return
        if version == 4:
            self._reconcile_graph_diagnostics_schema(conn)
            return
        if version == 5:
            self._ensure_column(conn, "analysis_jobs", "mode", "TEXT NOT NULL DEFAULT 'FULL'")
            return
        if version == 6:
            return
        if version == 7:
            return
        if version == 8:
            return
        if version == 9:
            return
        raise RuntimeError(f"Unknown analysis schema migration: {version}")

    def _reconcile_graph_diagnostics_schema(self, conn: sqlite3.Connection) -> None:
        if not self._table_exists(conn, "analysis_graph_diagnostics"):
            self._create_graph_diagnostics_schema(conn)
            return
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(analysis_graph_diagnostics)").fetchall()}
        required = {
            "id",
            "job_id",
            "source_id",
            "inventory_file_id",
            "analysis_file_id",
            "file_id",
            "relative_path",
            "content_hash",
            "severity",
            "stage",
            "code",
            "message",
            "candidate_id",
            "line_start",
            "line_end",
            "metadata_json",
            "created_at",
            "fact_origin",
            "flow_domain",
        }
        if columns == required:
            return
        conn.execute("DROP TABLE analysis_graph_diagnostics")
        self._create_graph_diagnostics_schema(conn)

    def _ensure_column(self, conn: sqlite3.Connection, table: str, column: str, declaration: str) -> None:
        columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
        if column not in columns:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")
