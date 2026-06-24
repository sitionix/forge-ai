from __future__ import annotations

import json
import base64
import hashlib
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set

from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.observability import observed_connect
from knowledge_service.overview_projection import ensure_overview_schema, read_overview, rebuild_overview, refresh_overview_for_sources
from knowledge_service.source_catalog import SourceMetadata


ANALYSIS_SCHEMA_MIGRATIONS = (
    (1, "remove_legacy_analysis_job_counter"),
    (2, "add_analysis_job_source_scope"),
    (3, "reset_analysis_cache_for_graph_v1_cutover"),
    (4, "reconcile_graph_diagnostics_schema"),
    (5, "add_analysis_job_mode"),
    (6, "add_immutable_graph_snapshots"),
)
SQLITE_WRITE_BUSY_TIMEOUT_MS = 5000
SQLITE_STATUS_BUSY_TIMEOUT_MS = 500
GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS = (0.05, 0.15, 0.3)
SERVICE_DIAGNOSTIC_ROW_LIMIT = 1000
GRAPH_CONTRACT_VERSION = "GRAPH_SNAPSHOT_V2"
GRAPH_SORT_VERSION = "ID_ASC_V1"
GRAPH_CURSOR_SIGNATURE_CONTEXT = "knowledge-graph-cursor-v1"


@dataclass(frozen=True)
class GraphSnapshotQuery:
    source_id: str
    snapshot_id: str
    resource: str
    flow_domain: str
    fact_origin: str
    node_kind: str
    edge_type: str
    include_external: str
    include_unresolved: bool
    include_isolated: bool
    contract_version: str = GRAPH_CONTRACT_VERSION
    sort_version: str = GRAPH_SORT_VERSION

    @property
    def fingerprint(self) -> str:
        return hashlib.sha256(json.dumps(self.as_payload(), sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()

    def as_payload(self) -> Dict[str, Any]:
        return {
            "contractVersion": self.contract_version,
            "sortVersion": self.sort_version,
            "snapshotId": self.snapshot_id,
            "sourceId": self.source_id,
            "resource": self.resource,
            "flowDomain": self.flow_domain,
            "factOrigin": self.fact_origin,
            "nodeKind": self.node_kind,
            "edgeType": self.edge_type,
            "includeExternal": self.include_external,
            "includeUnresolved": self.include_unresolved,
            "includeIsolated": self.include_isolated,
        }


class AnalysisStore:
    _init_lock = threading.Lock()
    _initialized_paths: Set[str] = set()
    _migration_fault_stage: Optional[str] = None

    def __init__(self, db_path: Path):
        self.db_path = db_path

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
                    symbol_count INTEGER NOT NULL,
                    relation_count INTEGER NOT NULL,
                    diagnostics_json TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_jobs", "current_source_id", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "current_relative_path", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "last_progress_at", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "engine_version", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "mode", "TEXT NOT NULL DEFAULT 'FULL'")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS graph_snapshots (
                    snapshot_id TEXT PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    published_at TEXT,
                    manifest_json TEXT NOT NULL DEFAULT '{}',
                    content_identity TEXT,
                    UNIQUE(source_id, snapshot_id)
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS graph_current_snapshots (
                    source_id TEXT PRIMARY KEY,
                    snapshot_id TEXT NOT NULL,
                    published_at TEXT NOT NULL,
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE RESTRICT
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS graph_snapshot_metrics (
                    snapshot_id TEXT NOT NULL,
                    query_fingerprint TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    flow_domain TEXT NOT NULL,
                    fact_origin TEXT NOT NULL,
                    node_kind TEXT NOT NULL,
                    edge_type TEXT NOT NULL,
                    include_external TEXT NOT NULL,
                    include_unresolved INTEGER NOT NULL,
                    include_isolated INTEGER NOT NULL,
                    total_node_count INTEGER NOT NULL,
                    total_edge_count INTEGER NOT NULL,
                    node_type_counts_json TEXT NOT NULL,
                    edge_type_counts_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(snapshot_id, query_fingerprint),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS graph_snapshot_tombstones (
                    snapshot_id TEXT PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    expired_at TEXT NOT NULL,
                    reason TEXT NOT NULL
                )
            """)
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
                    symbol_count INTEGER NOT NULL,
                    relation_count INTEGER NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_attempt_at TEXT,
                    last_error_code TEXT,
                    last_error_message TEXT,
                    last_raw_response_preview TEXT,
                    diagnostics_json TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_files", "attempt_count", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(conn, "analysis_files", "last_attempt_at", "TEXT")
            self._ensure_column(conn, "analysis_files", "last_error_code", "TEXT")
            self._ensure_column(conn, "analysis_files", "last_error_message", "TEXT")
            self._ensure_column(conn, "analysis_files", "last_raw_response_preview", "TEXT")
            self._ensure_column(conn, "analysis_files", "engine_version", "TEXT")
            self._ensure_column(conn, "analysis_files", "flow_domain", "TEXT")
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
                    updated_at TEXT NOT NULL
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_nodes (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    stable_key TEXT NOT NULL,
                    node_kind TEXT NOT NULL,
                    language TEXT,
                    name TEXT NOT NULL,
                    qualified_name TEXT,
                    display_name TEXT,
                    parent_node_id TEXT,
                    line_start INTEGER,
                    line_end INTEGER,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_evidence (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    content_hash TEXT NOT NULL,
                    line_start INTEGER NOT NULL,
                    line_end INTEGER NOT NULL,
                    excerpt_hash TEXT NOT NULL,
                    evidence_kind TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_claims (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    claim_kind TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    evidence_ids_json TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    rejection_reason TEXT,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, node_id) REFERENCES analysis_graph_nodes(source_id, snapshot_id, id) ON DELETE CASCADE
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_edges (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    from_node_id TEXT NOT NULL,
                    to_node_id TEXT,
                    edge_type TEXT NOT NULL,
                    resolution_status TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    evidence_id TEXT,
                    unresolved_target_json TEXT,
                    metadata_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, from_node_id) REFERENCES analysis_graph_nodes(source_id, snapshot_id, id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, to_node_id) REFERENCES analysis_graph_nodes(source_id, snapshot_id, id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, evidence_id) REFERENCES analysis_graph_evidence(source_id, snapshot_id, id) ON DELETE SET NULL
                )
            """)
            for table in (
                "analysis_graph_nodes",
                "analysis_graph_evidence",
                "analysis_graph_claims",
                "analysis_graph_edges",
            ):
                self._ensure_column(conn, table, "snapshot_id", "TEXT")
            self._create_analysis_graph_diagnostics_table(conn)
            self._ensure_column(conn, "analysis_graph_diagnostics", "snapshot_id", "TEXT")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_status ON analysis_files(source_id, status)")
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_analysis_files_current ON analysis_files(file_id, content_hash, analyzer_name, analyzer_version, engine_version, status)"
            )
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_path ON analysis_files(source_id, relative_path)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source ON analysis_graph_nodes(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_graph_snapshots_source_state ON graph_snapshots(source_id, state, published_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_graph_snapshot_metrics_lookup ON graph_snapshot_metrics(snapshot_id, query_fingerprint)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_graph_snapshot_tombstones_source ON graph_snapshot_tombstones(source_id, expired_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_snapshot ON analysis_graph_nodes(snapshot_id, source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_file ON analysis_graph_nodes(analysis_file_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_snapshot_page ON analysis_graph_nodes(source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_flow_created ON analysis_graph_nodes(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_claims_node_kind ON analysis_graph_claims(node_id, claim_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source ON analysis_graph_edges(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_snapshot ON analysis_graph_edges(snapshot_id, source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_nodes ON analysis_graph_edges(from_node_id, to_node_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_snapshot_page ON analysis_graph_edges(source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_flow_created ON analysis_graph_edges(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_diagnostics_source_code ON analysis_graph_diagnostics(source_id, severity, code)")
            self._ensure_graph_snapshot_schema(conn)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source ON analysis_graph_nodes(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_snapshot ON analysis_graph_nodes(snapshot_id, source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_file ON analysis_graph_nodes(analysis_file_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_snapshot_page ON analysis_graph_nodes(source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_flow_created ON analysis_graph_nodes(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_claims_node_kind ON analysis_graph_claims(node_id, claim_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source ON analysis_graph_edges(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_snapshot ON analysis_graph_edges(snapshot_id, source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_nodes ON analysis_graph_edges(from_node_id, to_node_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_snapshot_page ON analysis_graph_edges(source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_flow_created ON analysis_graph_edges(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_diagnostics_source_code ON analysis_graph_diagnostics(source_id, severity, code)")
            self._migration_stage("after_canonical_schema")
            self._migrate_legacy_symbol_relation_tables(conn)
            self._migration_stage("after_pointer_mutation")
            self._drop_legacy_fact_tables(conn)
            self._run_schema_migrations(conn)
            self._reconcile_graph_diagnostics_schema(conn)
            self._backfill_legacy_graph_snapshots(conn)
            self._reconcile_orphan_job_files(conn)
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
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, symbol_count, relation_count, diagnostics_json, engine_version, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                self._job_params(job),
            )
            refresh_overview_for_sources(conn, job.get("sourceIds") or [])

        self._write_with_busy_retry(write)

    def update_job(self, job_id: str, updates: Dict[str, Any]) -> None:
        current = self.job(job_id)
        if current is None:
            return
        old_status = current.get("status")
        current.update(updates)
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            conn.execute(
                """
                UPDATE analysis_jobs
                SET status = ?, started_at = ?, completed_at = ?, source_count = ?, file_count = ?, processed_file_count = ?,
                    failed_file_count = ?, current_source_id = ?, current_relative_path = ?, source_ids_json = ?, last_progress_at = ?,
                    symbol_count = ?, relation_count = ?, diagnostics_json = ?, engine_version = ?, mode = ?
                WHERE job_id = ?
            """,
                (*self._job_params(current)[1:], job_id),
            )
            new_status = current.get("status")
            if old_status != new_status:
                if new_status == "COMPLETED":
                    self._publish_job_graph_snapshots(conn, job_id)
                elif new_status in {"FAILED", "STOPPED"}:
                    self._finalize_unpublished_job_graph_snapshots(conn, job_id, "CANCELLED" if new_status == "STOPPED" else "FAILED")
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
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, symbol_count, relation_count, diagnostics_json, engine_version, mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            conn.execute(
                """
                UPDATE graph_snapshots
                SET state = 'FAILED'
                WHERE state = 'BUILDING'
                  AND job_id IN (
                      SELECT job_id
                      FROM analysis_jobs
                      WHERE status IN ('FAILED', 'STOPPED')
                  )
                """
            )

        self._write_with_busy_retry(write)

    def status(self) -> Dict[str, Any]:
        self.init()
        active = self.active_job(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS)
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            latest = conn.execute("SELECT * FROM analysis_jobs WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1").fetchone()
            counts = conn.execute(
                "SELECT COUNT(*) AS symbols FROM analysis_graph_nodes n WHERE " + self._current_graph_node_clause("n")
            ).fetchone()
            relations = conn.execute(
                "SELECT COUNT(*) AS relations FROM analysis_graph_edges e WHERE " + self._current_graph_edge_clause("e")
            ).fetchone()
            current_failures = self._current_failure_summary(conn, None)
            analysis_state = self._current_analysis_state(conn, None)
        if not latest and not active:
            return {
                "status": "EMPTY",
                "latestJobId": None,
                "activeJob": None,
                "symbolCount": 0,
                "relationCount": 0,
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
            "symbolCount": counts["symbols"],
            "relationCount": relations["relations"],
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

    def service_status(
        self,
        catalog_sources: Optional[List[SourceMetadata]],
    ) -> Dict[str, Any]:
        self.init()
        return read_overview(self.db_path)

    def _service_active_job_summary(self, active: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        if not active:
            return None
        return {
            "jobId": active.get("jobId"),
            "sourceId": active.get("currentSourceId"),
            "status": active.get("status"),
            "mode": active.get("mode"),
            "selectedFileCount": active.get("fileCount"),
            "processedFileCount": active.get("processedFileCount"),
            "failedFileCount": active.get("failedFileCount"),
            "currentRelativePath": active.get("currentRelativePath"),
        }

    def _inventory_source_state(self, conn: sqlite3.Connection) -> Dict[str, Dict[str, Any]]:
        rows = conn.execute("SELECT source_id, skipped_count FROM inventory_source_state").fetchall()
        result: Dict[str, Dict[str, Any]] = {}
        for row in rows:
            result[row["source_id"]] = {
                "skippedCount": row["skipped_count"],
            }
        return result

    def _service_source_ids(self, catalog_sources: Optional[List[SourceMetadata]], stats_by_source: Dict[str, Any]) -> List[str]:
        if catalog_sources is not None:
            return [source.sourceId for source in catalog_sources]
        return sorted(stats_by_source.keys())

    def _catalog_source(self, catalog_sources: Optional[List[SourceMetadata]], source_id: str) -> Optional[SourceMetadata]:
        if catalog_sources is None:
            return None
        for source in catalog_sources:
            if source.sourceId == source_id:
                return source
        return None

    def _analysis_service_status(self, is_running: bool, processed: int, failed: int, pending: int) -> str:
        if is_running:
            return "RUNNING"
        if processed == 0:
            return "NOT_ANALYZED"
        if pending == 0 and failed == 0:
            return "COMPLETED"
        return "PARTIAL"

    def unchanged(self, file_id: int, content_hash: str, analyzer_name: str, analyzer_version: str, engine_version: Optional[str] = None) -> bool:
        self.init()
        engine_clause = "AND COALESCE(engine_version, '') = COALESCE(?, '')" if engine_version is not None else ""
        params: list[Any] = [file_id, content_hash, analyzer_name, analyzer_version]
        if engine_version is not None:
            params.append(engine_version)
        with self._connect() as conn:
            row = conn.execute(
                f"""
                SELECT file_id FROM analysis_files
                WHERE file_id = ? AND content_hash = ? AND analyzer_name = ? AND analyzer_version = ? AND status = 'ANALYZED'
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
                    clauses.append("(file_id = ? AND content_hash = ?)")
                    params.extend([row["id"], row["content_hash"]])
                matches = conn.execute(
                    f"""
                    SELECT file_id FROM analysis_files
                    WHERE analyzer_name = ?
                      AND analyzer_version = ?
                      {engine_clause}
                      AND status = 'ANALYZED'
                      AND ({" OR ".join(clauses)})
                """,
                    params,
                ).fetchall()
                result.update(row["file_id"] for row in matches)
        return result

    def replace_file_analysis(
        self, file_id: int, state: Dict[str, Any], symbols: List[Dict[str, Any]], roles: List[Dict[str, Any]], relations: List[Dict[str, Any]]
    ) -> None:
        raise KnowledgeError("GRAPH_LEGACY_WRITE_REMOVED", "Legacy graph writes have been removed; use snapshot graph publication.")

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
        source_id = state["source_id"]
        job_id = self._graph_job_id(graph, state)
        snapshot_id = self._graph_snapshot_id(job_id, source_id)
        operation = "delete_file_analysis"
        table = "analysis_files"
        try:
            self._ensure_building_graph_snapshot(conn, snapshot_id, source_id, job_id, created_at)
            self._delete_file_graph_from_snapshot(conn, file_id, snapshot_id)
            operation = "insert_nodes"
            table = "analysis_graph_nodes"
            for node in graph.get("nodes") or []:
                conn.execute(
                    """
                        INSERT INTO analysis_graph_nodes(
                            id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind,
                            language, name, qualified_name, display_name, parent_node_id, line_start, line_end,
                            confidence, status, metadata_json, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        node["id"],
                        snapshot_id,
                        node["job_id"],
                        node["source_id"],
                        node.get("inventory_file_id"),
                        node.get("analysis_file_id"),
                        node["stable_key"],
                        node["node_kind"],
                        node.get("language"),
                        node["name"],
                        node.get("qualified_name"),
                        node.get("display_name"),
                        node.get("parent_node_id"),
                        node.get("line_start"),
                        node.get("line_end"),
                        node["confidence"],
                        node["status"],
                        json.dumps(node.get("metadata") or {}),
                        created_at,
                        node.get("fact_origin"),
                        node.get("flow_domain"),
                    ),
                )
            operation = "insert_evidence"
            table = "analysis_graph_evidence"
            for item in graph.get("evidence") or []:
                conn.execute(
                    """
                        INSERT INTO analysis_graph_evidence(
                            id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, content_hash, line_start,
                            line_end, excerpt_hash, evidence_kind, metadata_json, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        item["id"],
                        snapshot_id,
                        item["job_id"],
                        item["source_id"],
                        item.get("inventory_file_id"),
                        item.get("analysis_file_id"),
                        item["content_hash"],
                        item["line_start"],
                        item["line_end"],
                        item["excerpt_hash"],
                        item["evidence_kind"],
                        json.dumps(item.get("metadata") or {}),
                        created_at,
                        item.get("fact_origin"),
                        item.get("flow_domain"),
                    ),
                )
            operation = "insert_claims"
            table = "analysis_graph_claims"
            for claim in graph.get("claims") or []:
                conn.execute(
                    """
                        INSERT INTO analysis_graph_claims(
                            id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence, status, evidence_ids_json,
                            metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        claim["id"],
                        snapshot_id,
                        claim["job_id"],
                        claim["source_id"],
                        claim["node_id"],
                        claim["claim_kind"],
                        claim["summary"],
                        claim["confidence"],
                        claim["status"],
                        json.dumps(claim.get("evidence_ids") or []),
                        json.dumps(claim.get("metadata") or {}),
                        claim.get("rejection_reason"),
                        created_at,
                        claim.get("fact_origin"),
                        claim.get("flow_domain"),
                    ),
                )
            operation = "insert_edges"
            table = "analysis_graph_edges"
            for edge in graph.get("edges") or []:
                conn.execute(
                    """
                        INSERT INTO analysis_graph_edges(
                            id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id,
                            edge_type, resolution_status, confidence, evidence_id, unresolved_target_json,
                            metadata_json, status, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        edge["id"],
                        snapshot_id,
                        edge["job_id"],
                        edge["source_id"],
                        edge.get("inventory_file_id"),
                        edge.get("analysis_file_id"),
                        edge["from_node_id"],
                        edge.get("to_node_id"),
                        edge["edge_type"],
                        edge["resolution_status"],
                        edge["confidence"],
                        edge.get("evidence_id"),
                        json.dumps(edge.get("unresolved_target")) if edge.get("unresolved_target") is not None else None,
                        json.dumps(edge.get("metadata") or {}),
                        edge["status"],
                        created_at,
                        edge.get("fact_origin"),
                        edge.get("flow_domain"),
                    ),
                )
            operation = "insert_diagnostics"
            table = "analysis_graph_diagnostics"
            for diagnostic in graph.get("diagnostics") or []:
                conn.execute(
                    """
                        INSERT INTO analysis_graph_diagnostics(
                            id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, severity, stage, code,
                            message, candidate_id, line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        diagnostic["id"],
                        snapshot_id,
                        diagnostic["job_id"],
                        diagnostic["source_id"],
                        diagnostic.get("inventory_file_id"),
                        diagnostic.get("analysis_file_id"),
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
            operation = "resolve_source_call_edges"
            table = "analysis_graph_edges"
            self._resolve_source_call_edges(conn, state["source_id"], snapshot_id)
            operation = "upsert_analysis_file"
            table = "analysis_files"
            self._upsert_file(conn, file_id, state)
            if conn.execute("SELECT 1 FROM analysis_jobs WHERE job_id = ?", (job_id,)).fetchone() is None:
                self._publish_graph_snapshot(conn, snapshot_id)
            refresh_overview_for_sources(conn, [state["source_id"]])
        except sqlite3.Error as exc:
            raise self._graph_store_error(table, operation, exc) from exc

    def mark_file(self, file_id: int, state: Dict[str, Any]) -> None:
        self.init()

        def write(conn: sqlite3.Connection) -> None:
            self._delete_file_analysis(conn, file_id)
            self._upsert_file(conn, file_id, state)
            refresh_overview_for_sources(conn, [state["source_id"]])

        self._write_with_busy_retry(write)

    def cleanup_stale_files(self, source_ids: Optional[List[str]] = None) -> None:
        self.init()
        clauses: list[str] = ["f.id IS NULL"]
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
                LEFT JOIN files f ON f.id = af.file_id
                WHERE {where}
            """,
                params,
            ).fetchall()
            self._reattach_current_analysis_files(conn, source_ids)
            for row in rows:
                if conn.execute("SELECT 1 FROM analysis_files WHERE file_id = ?", (row["file_id"],)).fetchone() is None:
                    continue
                self._delete_file_analysis(conn, row["file_id"])
                conn.execute("DELETE FROM analysis_files WHERE file_id = ?", (row["file_id"],))
            refresh_overview_for_sources(conn, source_ids or sorted({row["source_id"] for row in rows}))

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

    def graph_snapshot_metadata(self, source_id: Optional[str]) -> Dict[str, Any]:
        self.init()
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            source_row = None
            has_sources = self._table_exists(conn, "sources")
            if source_id and has_sources:
                source_row = conn.execute(
                    """
                    SELECT source_id, display_name, group_name, path, root_exists, last_seen_at
                    FROM sources
                    WHERE source_id = ?
                    """,
                    (source_id,),
                ).fetchone()
            elif has_sources:
                source_row = conn.execute(
                    """
                    SELECT source_id, display_name, group_name, path, root_exists, last_seen_at
                    FROM sources
                    ORDER BY source_id
                    LIMIT 1
                    """
                ).fetchone()
            selected_source_id = str(source_row["source_id"]) if source_row else source_id
            overview_row = None
            if selected_source_id:
                overview_row = conn.execute(
                    """
                    SELECT *
                    FROM knowledge_source_overview
                    WHERE source_id = ?
                    """,
                    (selected_source_id,),
                ).fetchone()
            current = None
            if selected_source_id:
                current = conn.execute(
                    """
                    SELECT current.snapshot_id, current.published_at, snapshot.content_identity
                    FROM graph_current_snapshots current
                    JOIN graph_snapshots snapshot
                      ON snapshot.source_id = current.source_id
                     AND snapshot.snapshot_id = current.snapshot_id
                    WHERE current.source_id = ?
                    """,
                    (selected_source_id,),
                ).fetchone()
            diagnostics = None
            if selected_source_id:
                diagnostics = conn.execute(
                    """
                    SELECT COUNT(*) AS total,
                           SUM(CASE WHEN severity = 'ERROR' THEN 1 ELSE 0 END) AS errors,
                           SUM(CASE WHEN severity = 'WARN' THEN 1 ELSE 0 END) AS warnings
                    FROM analysis_graph_diagnostics
                    WHERE source_id = ?
                      AND (? IS NULL OR snapshot_id = ?)
                    """,
                    (selected_source_id, current["snapshot_id"] if current else None, current["snapshot_id"] if current else None),
                ).fetchone()
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
            "graphAvailable": current is not None,
            "snapshotId": current["snapshot_id"] if current else None,
            "graphRevision": current["content_identity"] if current else None,
            "lastAnalyzedAt": overview_row["updated_at"] if overview_row else None,
            "lastGraphPublishedAt": current["published_at"] if current else None,
            "diagnostics": {
                "total": int(diagnostics["total"] or 0) if diagnostics else 0,
                "errors": int(diagnostics["errors"] or 0) if diagnostics else 0,
                "warnings": int(diagnostics["warnings"] or 0) if diagnostics else 0,
            },
        }

    def graph_snapshot_manifest(
        self,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str] = None,
        node_kind: Optional[str] = None,
        edge_type: Optional[str] = None,
        include_external: str = "show",
        include_unresolved: bool = True,
        include_isolated: bool = True,
        default_node_page_size: int = 500,
        default_edge_page_size: int = 1000,
    ) -> Dict[str, Any]:
        self.init()
        with self._connect() as conn:
            snapshot_id = self._current_snapshot_id(conn, source_id)
            if snapshot_id is None:
                revision = f"{source_id or 'all'}:{flow_domain or 'ALL'}:graph-empty"
                return {
                    "graphRevision": revision,
                    "snapshotId": None,
                    "sourceId": source_id,
                    "sourceName": source_id,
                    "flowDomain": flow_domain,
                    "filters": {
                        "factOrigin": fact_origin,
                        "nodeKind": node_kind,
                        "edgeType": edge_type,
                        "includeExternal": include_external,
                        "includeUnresolved": include_unresolved,
                        "includeIsolated": include_isolated,
                    },
                    "totalNodeCount": 0,
                    "totalEdgeCount": 0,
                    "connectedComponentCount": None,
                    "largestComponentNodeCount": None,
                    "largestComponentEdgeCount": None,
                    "nodeTypeCounts": {},
                    "edgeTypeCounts": {},
                    "defaultNodePageSize": default_node_page_size,
                    "defaultEdgePageSize": default_edge_page_size,
                    "etag": self._graph_snapshot_etag(revision),
                    "generatedAt": datetime.now(timezone.utc).isoformat(),
                    "status": {},
                }
            query = self._graph_query(
                conn,
                "manifest",
                snapshot_id,
                source_id,
                flow_domain,
                fact_origin,
                node_kind,
                edge_type,
                include_external,
                include_unresolved,
                include_isolated,
            )
            metric = self._graph_metric_or_backfill(conn, query)
            revision = self._graph_snapshot_revision(query)
            return {
                "graphRevision": revision,
                "snapshotId": snapshot_id,
                "sourceId": query.source_id,
                "sourceName": query.source_id,
                "flowDomain": None if query.flow_domain == "ALL" else query.flow_domain,
                "filters": self._graph_query_filters(query),
                "queryFingerprint": query.fingerprint,
                "totalNodeCount": int(metric["total_node_count"] or 0),
                "totalEdgeCount": int(metric["total_edge_count"] or 0),
                "connectedComponentCount": None,
                "largestComponentNodeCount": None,
                "largestComponentEdgeCount": None,
                "nodeTypeCounts": self._json_dict(metric["node_type_counts_json"]),
                "edgeTypeCounts": self._json_dict(metric["edge_type_counts_json"]),
                "defaultNodePageSize": default_node_page_size,
                "defaultEdgePageSize": default_edge_page_size,
                "etag": self._graph_snapshot_etag(revision),
                "generatedAt": metric["created_at"],
                "status": {},
            }

    def _graph_metric(self, conn: sqlite3.Connection, query: GraphSnapshotQuery) -> Optional[sqlite3.Row]:
        return conn.execute(
            """
            SELECT *
            FROM graph_snapshot_metrics
            WHERE snapshot_id = ?
              AND query_fingerprint = ?
            """,
            (query.snapshot_id, query.fingerprint),
        ).fetchone()

    def _graph_metric_or_backfill(self, conn: sqlite3.Connection, query: GraphSnapshotQuery) -> sqlite3.Row:
        metric = self._graph_metric(conn, query)
        if metric is not None:
            return metric
        self._assert_snapshot_readable(conn, query.snapshot_id, query.source_id)
        self._insert_graph_snapshot_metric(conn, query, datetime.now(timezone.utc).isoformat())
        repaired = self._graph_metric(conn, query)
        if repaired is None:
            raise KnowledgeError("GRAPH_SNAPSHOT_METRICS_MISSING", "Graph snapshot metrics are missing for this query.")
        return repaired

    def _graph_query_filters(self, query: GraphSnapshotQuery) -> Dict[str, Any]:
        return {
            "factOrigin": None if query.fact_origin == "ALL" else query.fact_origin,
            "nodeKind": None if query.node_kind == "ALL" else query.node_kind,
            "edgeType": None if query.edge_type == "ALL" else query.edge_type,
            "includeExternal": query.include_external,
            "includeUnresolved": query.include_unresolved,
            "includeIsolated": query.include_isolated,
        }

    def _graph_query(
        self,
        conn: sqlite3.Connection,
        resource: str,
        snapshot_id: str,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        edge_type: Optional[str],
        include_external: str,
        include_unresolved: bool,
        include_isolated: bool,
    ) -> GraphSnapshotQuery:
        resource_normalized = str(resource or "").lower()
        if resource_normalized not in {"manifest", "nodes", "edges"}:
            raise KnowledgeError("GRAPH_FILTER_INVALID", "Graph resource is not supported.")
        source = source_id
        if not source:
            row = conn.execute("SELECT source_id FROM graph_snapshots WHERE snapshot_id = ?", (snapshot_id,)).fetchone()
            source = row["source_id"] if row else "all"
        include_external_normalized = str(include_external or "show").strip().lower()
        if include_external_normalized not in {"show", "hide"}:
            raise KnowledgeError("GRAPH_FILTER_INVALID", "includeExternal must be 'show' or 'hide'.")
        return GraphSnapshotQuery(
            source_id=str(source),
            snapshot_id=snapshot_id,
            resource=resource_normalized,
            flow_domain=self._normalize_graph_dimension(flow_domain),
            fact_origin=self._normalize_graph_dimension(fact_origin),
            node_kind=self._normalize_graph_dimension(node_kind),
            edge_type=self._normalize_graph_dimension(edge_type),
            include_external=include_external_normalized,
            include_unresolved=bool(include_unresolved),
            include_isolated=bool(include_isolated),
        )

    def _normalize_graph_dimension(self, value: Optional[str]) -> str:
        if value is None or str(value).strip() == "":
            return "ALL"
        normalized = str(value).strip().upper()
        if any(ch in normalized for ch in ("%", "'", "\"", ";", "\x00")):
            raise KnowledgeError("GRAPH_FILTER_INVALID", "Graph filter value is invalid.")
        return normalized

    def _graph_revision_payload(self, query: GraphSnapshotQuery) -> Dict[str, Any]:
        payload = query.as_payload()
        payload["resource"] = "manifest"
        payload["queryFingerprint"] = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()
        return payload

    def _graph_payload_matches_query(self, payload: Dict[str, Any], query: GraphSnapshotQuery) -> None:
        expected = self._graph_revision_payload(query)
        for key in (
            "contractVersion",
            "sortVersion",
            "snapshotId",
            "sourceId",
            "flowDomain",
            "factOrigin",
            "nodeKind",
            "edgeType",
            "includeExternal",
            "includeUnresolved",
            "includeIsolated",
            "queryFingerprint",
        ):
            if payload.get(key) != expected.get(key):
                if key == "sourceId":
                    raise KnowledgeError("GRAPH_SNAPSHOT_SOURCE_MISMATCH", "Graph snapshot belongs to another source.")
                raise KnowledgeError("GRAPH_CURSOR_QUERY_MISMATCH", "Graph request does not match the snapshot query.")

    def _stored_manifest_from_snapshot(self, conn: sqlite3.Connection, snapshot_id: str) -> Optional[Dict[str, Any]]:
        row = conn.execute("SELECT source_id FROM graph_snapshots WHERE snapshot_id = ? AND state = 'PUBLISHED'", (snapshot_id,)).fetchone()
        if row is None:
            return None
        query = self._graph_query(conn, "manifest", snapshot_id, row["source_id"], None, None, None, None, "show", True, True)
        metric = self._graph_metric(conn, query)
        if metric is None:
            return None
        revision = self._graph_snapshot_revision(query)
        return {
            "graphRevision": revision,
            "snapshotId": snapshot_id,
            "sourceId": row["source_id"],
            "sourceName": self._graph_source_name(conn, row["source_id"]),
            "flowDomain": None,
            "filters": self._graph_query_filters(query),
            "queryFingerprint": query.fingerprint,
            "totalNodeCount": int(metric["total_node_count"] or 0),
            "totalEdgeCount": int(metric["total_edge_count"] or 0),
            "connectedComponentCount": None,
            "largestComponentNodeCount": None,
            "largestComponentEdgeCount": None,
            "nodeTypeCounts": self._json_dict(metric["node_type_counts_json"]),
            "edgeTypeCounts": self._json_dict(metric["edge_type_counts_json"]),
            "defaultNodePageSize": 500,
            "defaultEdgePageSize": 1000,
            "etag": self._graph_snapshot_etag(revision),
            "generatedAt": metric["created_at"],
            "status": self._graph_status_or_empty(conn, row["source_id"]),
        }

    def graph_snapshot_nodes(
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
    ) -> Dict[str, Any]:
        safe_page_size = max(1, min(int(page_size or 500), 5000))
        with self._connect() as conn:
            revision_payload = self._decode_graph_snapshot_revision(graph_revision)
            snapshot_id = str(revision_payload["snapshotId"])
            query = self._graph_query(
                conn,
                "nodes",
                snapshot_id,
                source_id,
                flow_domain,
                fact_origin,
                node_kind,
                revision_payload.get("edgeType"),
                include_external,
                include_unresolved,
                include_isolated,
            )
            self._graph_payload_matches_query(revision_payload, query)
            self._assert_snapshot_readable(conn, snapshot_id, query.source_id)
            cursor_value = self._decode_graph_snapshot_cursor(cursor, query, "nodes")
            where, params = self._graph_snapshot_node_where(snapshot_id, query.source_id, query.flow_domain, query.fact_origin, query.node_kind, query.include_external, query.include_isolated)
            if cursor_value:
                where = f"{where} AND n.id > ?"
                params = [*params, cursor_value]
            rows = conn.execute(
                f"""
                SELECT n.*, af.relative_path,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN (
                    SELECT from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE snapshot_id = ?
                    GROUP BY from_node_id
                ) out_degree ON out_degree.node_id = n.id
                LEFT JOIN (
                    SELECT to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE snapshot_id = ?
                    GROUP BY to_node_id
                ) in_degree ON in_degree.node_id = n.id
                LEFT JOIN analysis_graph_claims entry
                  ON entry.node_id = n.id
                 AND entry.snapshot_id = n.snapshot_id
                 AND entry.claim_kind = 'ENTRYPOINT_HINT'
                 AND entry.status IN ('TRUSTED', 'DERIVED')
                WHERE {where}
                ORDER BY n.id
                LIMIT ?
                """,
                [snapshot_id, snapshot_id, *params, safe_page_size + 1],
            ).fetchall()
        items = [self._graph_snapshot_node_projection(self._row_dict(row)) for row in rows[:safe_page_size]]
        complete = len(rows) <= safe_page_size
        next_cursor = None if complete or not items else self._encode_graph_snapshot_cursor(query, "nodes", items[-1]["id"])
        return {"graphRevision": graph_revision, "snapshotId": query.snapshot_id, "queryFingerprint": query.fingerprint, "items": items, "nextCursor": next_cursor, "complete": complete, "returnedCount": len(items)}

    def graph_snapshot_edges(
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
    ) -> Dict[str, Any]:
        safe_page_size = max(1, min(int(page_size or 1000), 5000))
        with self._connect() as conn:
            revision_payload = self._decode_graph_snapshot_revision(graph_revision)
            snapshot_id = str(revision_payload["snapshotId"])
            query = self._graph_query(
                conn,
                "edges",
                snapshot_id,
                source_id,
                flow_domain,
                fact_origin,
                revision_payload.get("nodeKind"),
                edge_type,
                include_external,
                include_unresolved,
                bool(revision_payload.get("includeIsolated", True)),
            )
            self._graph_payload_matches_query(revision_payload, query)
            self._assert_snapshot_readable(conn, snapshot_id, query.source_id)
            cursor_value = self._decode_graph_snapshot_cursor(cursor, query, "edges")
            where, params = self._graph_snapshot_edge_where(snapshot_id, query.source_id, query.flow_domain, query.fact_origin, query.edge_type, query.include_unresolved)
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
                LEFT JOIN analysis_graph_nodes fn ON fn.snapshot_id = e.snapshot_id AND fn.id = e.from_node_id
                LEFT JOIN analysis_graph_nodes tn ON tn.snapshot_id = e.snapshot_id AND tn.id = e.to_node_id
                WHERE {where}
                ORDER BY e.id
                LIMIT ?
                """,
                [*params, safe_page_size + 1],
            ).fetchall()
        items = [self._graph_snapshot_edge_projection(self._row_dict(row)) for row in rows[:safe_page_size]]
        complete = len(rows) <= safe_page_size
        next_cursor = None if complete or not items else self._encode_graph_snapshot_cursor(query, "edges", items[-1]["id"])
        return {"graphRevision": graph_revision, "snapshotId": query.snapshot_id, "queryFingerprint": query.fingerprint, "items": items, "nextCursor": next_cursor, "complete": complete, "returnedCount": len(items)}

    def graph_snapshot_node_detail(
        self,
        graph_revision: str,
        node_id: str,
        source_id: Optional[str],
        include_evidence: bool = False,
    ) -> Dict[str, Any]:
        with self._connect() as conn:
            revision_payload = self._decode_graph_snapshot_revision(graph_revision)
            snapshot_id = str(revision_payload["snapshotId"])
            requested_source = source_id or str(revision_payload["sourceId"])
            self._assert_snapshot_readable(conn, snapshot_id, requested_source)
            row = conn.execute(
                """
                SELECT n.*, af.relative_path,
                       COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree,
                       CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint
                FROM analysis_graph_nodes n
                LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
                LEFT JOIN (
                    SELECT from_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE snapshot_id = ?
                    GROUP BY from_node_id
                ) out_degree ON out_degree.node_id = n.id
                LEFT JOIN (
                    SELECT to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    WHERE snapshot_id = ?
                    GROUP BY to_node_id
                ) in_degree ON in_degree.node_id = n.id
                LEFT JOIN analysis_graph_claims entry
                  ON entry.node_id = n.id
                 AND entry.snapshot_id = n.snapshot_id
                 AND entry.claim_kind = 'ENTRYPOINT_HINT'
                 AND entry.status IN ('TRUSTED', 'DERIVED')
                WHERE n.snapshot_id = ?
                  AND n.id = ?
                """,
                (snapshot_id, snapshot_id, snapshot_id, node_id),
            ).fetchone()
            if row is None:
                scoped = conn.execute(
                    "SELECT source_id, snapshot_id FROM analysis_graph_nodes WHERE id = ? LIMIT 1",
                    (node_id,),
                ).fetchone()
                if scoped is not None:
                    raise KnowledgeError("GRAPH_ITEM_SCOPE_MISMATCH", "Graph item is outside the requested snapshot or source.")
                raise KnowledgeError("GRAPH_NODE_NOT_FOUND", "Graph node was not found.")
            if requested_source and row["source_id"] != requested_source:
                raise KnowledgeError("GRAPH_ITEM_SCOPE_MISMATCH", "Graph item is outside the requested snapshot or source.")
            row_dict = self._row_dict(row)
            detail = self._graph_snapshot_node_projection(row_dict)
            detail["snapshotId"] = snapshot_id
            detail["parentNodeId"] = row_dict.get("parent_node_id")
            claims = conn.execute(
                """
                SELECT id, node_id, claim_kind, summary, confidence, status, rejection_reason, evidence_ids_json, metadata_json, fact_origin, flow_domain,
                       CASE WHEN node_id = ? THEN 1 ELSE 0 END AS selected_node_claim
                FROM analysis_graph_claims
                WHERE snapshot_id = ?
                  AND (
                    node_id = ?
                    OR (
                      claim_kind = 'RESPONSIBILITY'
                      AND status IN ('TRUSTED', 'LOW_CONFIDENCE')
                      AND (
                        node_id = ?
                        OR node_id IN (
                          SELECT id
                          FROM analysis_graph_nodes
                          WHERE analysis_file_id = ?
                            AND snapshot_id = ?
                            AND node_kind = 'FILE'
                          ORDER BY confidence DESC
                          LIMIT 1
                        )
                      )
                    )
                  )
                ORDER BY selected_node_claim DESC, confidence DESC, id
                LIMIT 100
                """,
                (node_id, snapshot_id, node_id, row_dict.get("parent_node_id"), row_dict.get("analysis_file_id"), snapshot_id),
            ).fetchall()
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
                    "metadata": self._json_dict(claim["metadata_json"]),
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
                detail["evidence"] = self._graph_snapshot_evidence(conn, snapshot_id, node_id=node_id)
            return {"graphRevision": graph_revision, "snapshotId": snapshot_id, "item": detail}

    def graph_snapshot_edge_detail(
        self,
        graph_revision: str,
        edge_id: str,
        source_id: Optional[str],
        include_evidence: bool = False,
    ) -> Dict[str, Any]:
        with self._connect() as conn:
            revision_payload = self._decode_graph_snapshot_revision(graph_revision)
            snapshot_id = str(revision_payload["snapshotId"])
            requested_source = source_id or str(revision_payload["sourceId"])
            self._assert_snapshot_readable(conn, snapshot_id, requested_source)
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
                LEFT JOIN analysis_graph_nodes fn ON fn.snapshot_id = e.snapshot_id AND fn.id = e.from_node_id
                LEFT JOIN analysis_graph_nodes tn ON tn.snapshot_id = e.snapshot_id AND tn.id = e.to_node_id
                WHERE e.snapshot_id = ?
                  AND e.id = ?
                """,
                (snapshot_id, edge_id),
            ).fetchone()
            if row is None:
                scoped = conn.execute(
                    "SELECT source_id, snapshot_id FROM analysis_graph_edges WHERE id = ? LIMIT 1",
                    (edge_id,),
                ).fetchone()
                if scoped is not None:
                    raise KnowledgeError("GRAPH_ITEM_SCOPE_MISMATCH", "Graph item is outside the requested snapshot or source.")
                raise KnowledgeError("GRAPH_EDGE_NOT_FOUND", "Graph edge was not found.")
            if requested_source and row["source_id"] != requested_source:
                raise KnowledgeError("GRAPH_ITEM_SCOPE_MISMATCH", "Graph item is outside the requested snapshot or source.")
            detail = self._graph_snapshot_edge_projection(self._row_dict(row))
            detail["snapshotId"] = snapshot_id
            if include_evidence:
                detail["evidence"] = self._graph_snapshot_evidence(conn, snapshot_id, edge_id=edge_id)
            return {"graphRevision": graph_revision, "snapshotId": snapshot_id, "item": detail}

    def _current_graph_node_clause(self, alias: str) -> str:
        return f"""{alias}.snapshot_id IN (
            SELECT snapshot_id FROM graph_current_snapshots
        )"""

    def _current_graph_edge_clause(self, alias: str) -> str:
        return f"""{alias}.snapshot_id IN (
            SELECT snapshot_id FROM graph_current_snapshots
        ) AND EXISTS (
            SELECT 1
            FROM analysis_graph_nodes fn
            LEFT JOIN analysis_graph_nodes tn ON tn.id = {alias}.to_node_id
            WHERE fn.id = {alias}.from_node_id
              AND fn.snapshot_id = {alias}.snapshot_id
              AND (tn.id IS NULL OR tn.snapshot_id = {alias}.snapshot_id)
        )"""

    def _graph_snapshot_node_where(
        self,
        snapshot_id: str,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        include_external: str,
        include_isolated: bool,
    ) -> tuple[str, List[Any]]:
        clauses = ["n.snapshot_id = ?"]
        params: List[Any] = [snapshot_id]
        if source_id and source_id != "all":
            clauses.append("n.source_id = ?")
            params.append(source_id)
        if flow_domain and flow_domain != "ALL":
            clauses.append("n.flow_domain = ?")
            params.append(flow_domain.upper())
        if fact_origin and fact_origin != "ALL":
            clauses.append("n.fact_origin = ?")
            params.append(fact_origin.upper())
        if node_kind and node_kind != "ALL":
            clauses.append("n.node_kind = ?")
            params.append(node_kind.upper())
        if str(include_external or "show").lower() == "hide":
            clauses.append("n.node_kind != 'EXTERNAL'")
        if not include_isolated:
            clauses.append(
                """EXISTS (
                    SELECT 1
                    FROM analysis_graph_edges ge
                    WHERE ge.snapshot_id = n.snapshot_id
                      AND (ge.from_node_id = n.id OR ge.to_node_id = n.id)
                )"""
            )
        return " AND ".join(clauses), params

    def _graph_snapshot_edge_where(
        self,
        snapshot_id: str,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        edge_type: Optional[str],
        include_unresolved: bool,
    ) -> tuple[str, List[Any]]:
        clauses = ["e.snapshot_id = ?"]
        params: List[Any] = [snapshot_id]
        if source_id and source_id != "all":
            clauses.append("e.source_id = ?")
            params.append(source_id)
        if flow_domain and flow_domain != "ALL":
            clauses.append("e.flow_domain = ?")
            params.append(flow_domain.upper())
        if fact_origin and fact_origin != "ALL":
            clauses.append("e.fact_origin = ?")
            params.append(fact_origin.upper())
        if edge_type and edge_type != "ALL":
            clauses.append("e.edge_type = ?")
            params.append(edge_type.upper())
        if not include_unresolved:
            clauses.append("e.to_node_id IS NOT NULL")
            clauses.append("e.resolution_status NOT IN ('UNRESOLVED', 'DYNAMIC_TARGET', 'EXTERNAL_TARGET')")
        return " AND ".join(clauses), params

    def _graph_snapshot_revision(self, query: GraphSnapshotQuery) -> str:
        payload = self._graph_revision_payload(query)
        token = base64.urlsafe_b64encode(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")).decode("ascii").rstrip("=")
        return f"{query.source_id}:{query.flow_domain}:graph-snapshot:{token}"

    def _graph_snapshot_etag(self, graph_revision: str) -> str:
        return f'"{base64.urlsafe_b64encode(graph_revision.encode("utf-8")).decode("ascii").rstrip("=")}"'

    def _decode_graph_snapshot_revision(self, graph_revision: str) -> Dict[str, Any]:
        if not graph_revision:
            raise KnowledgeError("GRAPH_SNAPSHOT_REVISION_REQUIRED", "graphRevision is required.")
        token = graph_revision.rsplit(":", 1)[-1]
        try:
            padded = token + ("=" * (-len(token) % 4))
            payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError, TypeError):
            raise KnowledgeError("GRAPH_SNAPSHOT_STALE", "Graph snapshot revision is stale.")
        if not isinstance(payload, dict) or payload.get("contractVersion") != GRAPH_CONTRACT_VERSION or not payload.get("snapshotId"):
            raise KnowledgeError("GRAPH_SNAPSHOT_STALE", "Graph snapshot revision is stale.")
        return payload

    def _snapshot_id_from_graph_revision(self, graph_revision: str) -> str:
        return str(self._decode_graph_snapshot_revision(graph_revision)["snapshotId"])

    def _graph_revision_has_snapshot_token(self, graph_revision: str) -> bool:
        try:
            self._decode_graph_snapshot_revision(graph_revision)
        except KnowledgeError:
            return False
        return True

    def _graph_snapshot_exists(self, conn: sqlite3.Connection, snapshot_id: str) -> bool:
        row = conn.execute(
            "SELECT 1 FROM graph_snapshots WHERE snapshot_id = ? AND state IN ('PUBLISHED', 'RETIRED')",
            (snapshot_id,),
        ).fetchone()
        return row is not None

    def _assert_snapshot_readable(self, conn: sqlite3.Connection, snapshot_id: str, source_id: Optional[str]) -> sqlite3.Row:
        row = conn.execute(
            "SELECT snapshot_id, source_id, state FROM graph_snapshots WHERE snapshot_id = ?",
            (snapshot_id,),
        ).fetchone()
        if row is None:
            tombstone = conn.execute("SELECT source_id FROM graph_snapshot_tombstones WHERE snapshot_id = ?", (snapshot_id,)).fetchone()
            if tombstone is not None:
                if source_id and tombstone["source_id"] != source_id:
                    raise KnowledgeError("GRAPH_SNAPSHOT_SOURCE_MISMATCH", "Graph snapshot belongs to another source.")
                raise KnowledgeError("GRAPH_SNAPSHOT_EXPIRED", "Graph snapshot is no longer retained.", snapshotId=snapshot_id)
            raise KnowledgeError("GRAPH_SNAPSHOT_NOT_FOUND", "Graph snapshot was not found.", snapshotId=snapshot_id)
        if source_id and row["source_id"] != source_id:
            raise KnowledgeError("GRAPH_SNAPSHOT_SOURCE_MISMATCH", "Graph snapshot belongs to another source.", snapshotId=snapshot_id)
        if row["state"] not in {"PUBLISHED", "RETIRED"}:
            raise KnowledgeError("GRAPH_SNAPSHOT_NOT_FOUND", "Graph snapshot was not found.", snapshotId=snapshot_id)
        return row

    def _graph_snapshot_evidence(
        self,
        conn: sqlite3.Connection,
        snapshot_id: str,
        node_id: Optional[str] = None,
        edge_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        if edge_id:
            rows = conn.execute(
                """
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_edges edge
                JOIN analysis_graph_evidence ev
                  ON ev.snapshot_id = edge.snapshot_id
                 AND ev.id = edge.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE edge.snapshot_id = ? AND edge.id = ?
                ORDER BY ev.id
                LIMIT 100
                """,
                (snapshot_id, edge_id),
            ).fetchall()
        elif node_id:
            rows = conn.execute(
                """
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_claims claim
                JOIN analysis_graph_evidence ev
                  ON ev.snapshot_id = claim.snapshot_id
                 AND EXISTS (
                    SELECT 1
                    FROM json_each(claim.evidence_ids_json)
                    WHERE value = ev.id
                 )
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE claim.snapshot_id = ? AND claim.node_id = ?
                ORDER BY ev.id
                LIMIT 100
                """,
                (snapshot_id, node_id),
            ).fetchall()
        else:
            rows = []
        return [
            {
                "id": row["id"],
                "sourceId": row["source_id"],
                "relativePath": row["relative_path"],
                "lineStart": row["line_start"],
                "lineEnd": row["line_end"],
                "evidenceKind": row["evidence_kind"],
                "excerptHash": row["excerpt_hash"],
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
                "metadata": self._json_dict(row["metadata_json"]),
            }
            for row in rows
        ]

    def _assert_graph_snapshot_revision(self, requested: str, current: str) -> None:
        if not requested:
            raise KnowledgeError("GRAPH_SNAPSHOT_REVISION_REQUIRED", "graphRevision is required.")
        if requested != current:
            raise KnowledgeError("GRAPH_SNAPSHOT_STALE", "Graph snapshot revision is stale.", requested=requested, current=current)

    def _encode_graph_snapshot_cursor(self, query: GraphSnapshotQuery, page_kind: str, last_id: str) -> str:
        payload = {
            "contractVersion": GRAPH_CONTRACT_VERSION,
            "snapshotId": query.snapshot_id,
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

    def _decode_graph_snapshot_cursor(self, cursor: Optional[str], query: GraphSnapshotQuery, page_kind: str) -> Optional[str]:
        if not cursor:
            return None
        try:
            padded = cursor + ("=" * (-len(cursor) % 4))
            payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        except (ValueError, TypeError, json.JSONDecodeError):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph snapshot cursor is invalid.")
        if not isinstance(payload, dict) or payload.get("contractVersion") != GRAPH_CONTRACT_VERSION:
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph snapshot cursor is invalid.")
        if payload.get("signature") != self._graph_cursor_signature(payload):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph snapshot cursor is invalid.")
        if payload.get("resource") != page_kind:
            raise KnowledgeError("GRAPH_CURSOR_RESOURCE_MISMATCH", "Graph snapshot cursor resource does not match this request.")
        if payload.get("sourceId") != query.source_id:
            raise KnowledgeError("GRAPH_CURSOR_SOURCE_MISMATCH", "Graph snapshot cursor source does not match this request.")
        if payload.get("snapshotId") != query.snapshot_id:
            raise KnowledgeError("GRAPH_CURSOR_QUERY_MISMATCH", "Graph snapshot cursor snapshot does not match this request.")
        if payload.get("queryFingerprint") != query.fingerprint or payload.get("sortVersion") != query.sort_version:
            raise KnowledgeError("GRAPH_CURSOR_QUERY_MISMATCH", "Graph snapshot cursor query does not match this request.")
        last = payload.get("last")
        if not isinstance(last, dict) or not last.get("id"):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph snapshot cursor is invalid.")
        return str(last["id"])

    def _graph_snapshot_node_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        return {
            "id": row["id"],
            "graphNodeId": row["id"],
            "stableKey": row.get("stable_key") or row["id"],
            "kind": row.get("node_kind"),
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
            "status": row.get("status"),
            "confidence": row.get("confidence"),
            "degree": int(row.get("graph_degree") or 0),
            "entrypoint": bool(row.get("entrypoint")),
            "external": row.get("node_kind") == "EXTERNAL",
            "summaryAvailable": bool(metadata.get("responsibility") or metadata.get("claimSummary")),
            "importance": metadata.get("displayScore") or metadata.get("flowScore") or row.get("confidence"),
            "metadata": {
                key: value
                for key, value in metadata.items()
                if key
                in {
                    "callTargetCategory",
                    "sliceDefaultVisibility",
                    "sourceKind",
                    "displayScore",
                    "flowScore",
                    "unresolvedReason",
                }
            },
        }

    def _graph_snapshot_edge_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        unresolved_target = None
        if row.get("unresolved_target_json"):
            try:
                unresolved_target = json.loads(row.get("unresolved_target_json"))
            except (TypeError, json.JSONDecodeError):
                unresolved_target = row.get("unresolved_target_json")
        return {
            "id": row["id"],
            "graphEdgeId": row["id"],
            "stableKey": metadata.get("stableKey") or row["id"],
            "fromNodeId": row.get("from_node_id"),
            "toNodeId": row.get("to_node_id"),
            "from": row.get("from_node_id"),
            "to": row.get("to_node_id"),
            "fromLabel": row.get("from_display_name") or row.get("from_qualified_name") or row.get("from_name") or row.get("from_node_id"),
            "toLabel": row.get("to_display_name") or row.get("to_qualified_name") or row.get("to_name") or row.get("to_node_id"),
            "relation": row.get("edge_type"),
            "edgeType": row.get("edge_type"),
            "classification": metadata.get("callTargetCategory") or metadata.get("sliceDefaultVisibility"),
            "resolutionStatus": row.get("resolution_status"),
            "resolved": bool(row.get("to_node_id")) and row.get("resolution_status") not in {"UNRESOLVED", "DYNAMIC_TARGET"},
            "external": row.get("resolution_status") == "EXTERNAL_TARGET",
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
                    "displayScore",
                    "flowScore",
                    "methodName",
                    "receiverText",
                    "receiverTypeHint",
                    "sliceDefaultVisibility",
                    "unresolvedReason",
                }
            },
        }

    def _fact_claim_rows(self, conn: sqlite3.Connection, snapshot_id: str, node_id: str) -> List[Dict[str, Any]]:
        rows = conn.execute(
            """
            SELECT *
            FROM analysis_graph_claims
            WHERE snapshot_id = ?
              AND node_id = ?
            ORDER BY claim_kind = 'RESPONSIBILITY' DESC, status = 'TRUSTED' DESC, confidence DESC
        """,
            (snapshot_id, node_id),
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _fact_summary(self, conn: sqlite3.Connection, row: Dict[str, Any]) -> Dict[str, Any]:
        direct = self._responsibility_claim(conn, row["snapshot_id"], row["id"])
        if direct:
            return self._summary_from_claim(direct, "DIRECT")
        parent_id = row.get("parent_node_id")
        if parent_id:
            parent = self._responsibility_claim(conn, row["snapshot_id"], parent_id)
            if parent:
                return self._summary_from_claim(parent, "PARENT_FALLBACK")
        file_row = conn.execute(
            """
            SELECT id
            FROM analysis_graph_nodes
            WHERE analysis_file_id = ?
              AND snapshot_id = ?
              AND node_kind = 'FILE'
            ORDER BY confidence DESC
            LIMIT 1
        """,
            (row.get("analysis_file_id"), row["snapshot_id"]),
        ).fetchone()
        if file_row:
            file_claim = self._responsibility_claim(conn, row["snapshot_id"], file_row["id"])
            if file_claim:
                return self._summary_from_claim(file_claim, "FILE_FALLBACK")
        return {
            "summary": None,
            "summarySource": "NONE",
            "summaryClaimId": None,
            "summaryClaimNodeId": None,
            "summaryConfidence": None,
            "summaryEvidenceCount": 0,
        }

    def _fact_summary_from_claim_rows(self, claims: List[Dict[str, Any]], row: Dict[str, Any]) -> Dict[str, Any]:
        candidates = [
            claim
            for claim in claims
            if claim.get("claim_kind") == "RESPONSIBILITY" and claim.get("status") in {"TRUSTED", "LOW_CONFIDENCE"}
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
        return {
            "summary": None,
            "summarySource": "NONE",
            "summaryClaimId": None,
            "summaryClaimNodeId": None,
            "summaryConfidence": None,
            "summaryEvidenceCount": 0,
        }

    def _responsibility_claim(self, conn: sqlite3.Connection, snapshot_id: str, node_id: str) -> Optional[Dict[str, Any]]:
        row = conn.execute(
            """
            SELECT *
            FROM analysis_graph_claims
            WHERE snapshot_id = ?
              AND node_id = ?
              AND claim_kind = 'RESPONSIBILITY'
              AND status IN ('TRUSTED', 'LOW_CONFIDENCE')
            ORDER BY status = 'TRUSTED' DESC, confidence DESC
            LIMIT 1
        """,
            (snapshot_id, node_id),
        ).fetchone()
        return self._row_dict(row) if row else None

    def _summary_from_claim(self, claim: Dict[str, Any], source: str) -> Dict[str, Any]:
        return {
            "summary": claim.get("summary"),
            "summarySource": source,
            "summaryClaimId": claim.get("id"),
            "summaryClaimNodeId": claim.get("node_id"),
            "summaryConfidence": claim.get("confidence"),
            "summaryEvidenceCount": len(self._json_list(claim.get("evidence_ids_json"))),
        }

    def _node_kind_from_source_kind(self, kind: Optional[str]) -> str:
        value = str(kind or "UNKNOWN").upper()
        if value == "CLASS":
            return "TYPE"
        if value == "METHOD":
            return "CALLABLE"
        if value == "CONFIG_ENTRY":
            return "CONFIG"
        return value

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
        graph_node_clauses = [self._current_graph_node_clause("n")]
        graph_edge_clauses = [self._current_graph_edge_clause("e")]
        graph_params = []
        if source_id:
            graph_node_clauses.append("n.source_id = ?")
            graph_edge_clauses.append("e.source_id = ?")
            graph_params.append(source_id)
        node_count = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {' AND '.join(graph_node_clauses)}", graph_params).fetchone()["count"]
        edge_count = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {' AND '.join(graph_edge_clauses)}", graph_params).fetchone()["count"]
        trusted_nodes = conn.execute(
            f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {' AND '.join(graph_node_clauses)} AND n.status = 'TRUSTED'", graph_params
        ).fetchone()["count"]
        trusted_edges = conn.execute(
            f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {' AND '.join(graph_edge_clauses)} AND e.status = 'TRUSTED'", graph_params
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

    def _graph_source_name(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Optional[str]:
        if not source_id:
            return None
        try:
            row = conn.execute("SELECT display_name FROM sources WHERE source_id = ?", (source_id,)).fetchone()
        except sqlite3.OperationalError:
            return source_id
        return row["display_name"] if row else None

    def _graph_status_or_empty(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Dict[str, Any]:
        try:
            return self._graph_status(conn, source_id)
        except sqlite3.OperationalError:
            return {
                "analysisStatus": "READY",
                "jobId": None,
                "processedFileCount": 0,
                "processedFiles": 0,
                "fileCount": 0,
                "failedFileCount": 0,
                "failedFiles": 0,
                "progressPercent": 0,
                "trustedFactsCount": 0,
                "diagnosticsCount": 0,
                "lastUpdatedAt": None,
            }

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

    def _graph_claims(self, nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        claims: List[Dict[str, Any]] = []
        for node in nodes:
            claims.extend(node.get("claims") or [])
        return claims

    def _dedupe_rows(self, rows: List[Dict[str, Any]], key: str) -> List[Dict[str, Any]]:
        seen: Set[str] = set()
        result: List[Dict[str, Any]] = []
        for row in rows:
            value = row.get(key)
            if value in seen:
                continue
            seen.add(value)
            result.append(row)
        return result

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

    def _graph_snapshot_id(self, job_id: str, source_id: str) -> str:
        return f"{job_id}:{source_id}"

    def _ensure_building_graph_snapshot(self, conn: sqlite3.Connection, snapshot_id: str, source_id: str, job_id: str, created_at: str) -> None:
        conn.execute(
            """
            INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, manifest_json)
            VALUES (?, ?, ?, 'BUILDING', ?, '{}')
            ON CONFLICT(snapshot_id) DO UPDATE SET state = CASE
                WHEN graph_snapshots.state = 'BUILDING' THEN 'BUILDING'
                ELSE graph_snapshots.state
            END
            """,
            (snapshot_id, source_id, job_id, created_at),
        )

    def _publish_job_graph_snapshots(self, conn: sqlite3.Connection, job_id: str) -> None:
        rows = conn.execute(
            """
            SELECT snapshot_id
            FROM graph_snapshots
            WHERE job_id = ?
              AND state = 'BUILDING'
            ORDER BY source_id
            """,
            (job_id,),
        ).fetchall()
        for row in rows:
            self._publish_graph_snapshot(conn, row["snapshot_id"])

    def _finalize_unpublished_job_graph_snapshots(self, conn: sqlite3.Connection, job_id: str, state: str) -> None:
        conn.execute(
            """
            UPDATE graph_snapshots
            SET state = ?
            WHERE job_id = ?
              AND state = 'BUILDING'
            """,
            (state, job_id),
        )

    def _publish_graph_snapshot(self, conn: sqlite3.Connection, snapshot_id: str) -> None:
        row = conn.execute("SELECT * FROM graph_snapshots WHERE snapshot_id = ?", (snapshot_id,)).fetchone()
        if row is None:
            return
        current = conn.execute(
            """
            SELECT current.snapshot_id, snapshot.published_at
            FROM graph_current_snapshots current
            JOIN graph_snapshots snapshot ON snapshot.snapshot_id = current.snapshot_id
            WHERE current.source_id = ?
            """,
            (row["source_id"],),
        ).fetchone()
        if row["state"] == "PUBLISHED":
            if current is None or current["snapshot_id"] == snapshot_id:
                self._ensure_default_graph_snapshot_metrics(conn, snapshot_id, row["source_id"])
                return
            if current["published_at"] and row["published_at"] and current["published_at"] > row["published_at"]:
                raise KnowledgeError("GRAPH_SNAPSHOT_STALE", "A newer graph snapshot is already current.")
        if row["state"] != "BUILDING":
            raise KnowledgeError("GRAPH_SNAPSHOT_NOT_PUBLISHABLE", "Graph snapshot is not eligible for publication.")
        if current is not None and current["published_at"] and row["created_at"] and current["published_at"] > row["created_at"]:
            raise KnowledgeError("GRAPH_SNAPSHOT_STALE", "A newer graph snapshot is already current.")
        source_id = row["source_id"]
        self._validate_graph_snapshot_for_publication(conn, snapshot_id, source_id)
        self._rebuild_graph_snapshot_metrics(conn, snapshot_id, source_id)
        published_at = datetime.now(timezone.utc).isoformat()
        manifest = self._stored_graph_manifest(conn, snapshot_id, source_id, published_at)
        conn.execute(
            """
            UPDATE graph_snapshots
            SET state = 'RETIRED'
            WHERE source_id = ?
              AND state = 'PUBLISHED'
              AND snapshot_id != ?
            """,
            (source_id, snapshot_id),
        )
        conn.execute(
            """
            UPDATE graph_snapshots
            SET state = 'PUBLISHED',
                published_at = ?,
                manifest_json = ?,
                content_identity = ?
            WHERE snapshot_id = ?
            """,
            (published_at, json.dumps(manifest, separators=(",", ":")), manifest["graphRevision"], snapshot_id),
        )
        conn.execute(
            """
            INSERT INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES (?, ?, ?)
            ON CONFLICT(source_id) DO UPDATE SET snapshot_id = excluded.snapshot_id, published_at = excluded.published_at
            """,
            (source_id, snapshot_id, published_at),
        )
        self._retain_graph_snapshots(conn, source_id)

    def _ensure_default_graph_snapshot_metrics(self, conn: sqlite3.Connection, snapshot_id: str, source_id: str) -> None:
        query = self._graph_query(conn, "manifest", snapshot_id, source_id, None, None, None, None, "show", True, True)
        if self._graph_metric(conn, query) is None:
            self._rebuild_graph_snapshot_metrics(conn, snapshot_id, source_id)

    def _validate_graph_snapshot_for_publication(self, conn: sqlite3.Connection, snapshot_id: str, source_id: str) -> None:
        node_count = int(conn.execute("SELECT COUNT(*) AS count FROM analysis_graph_nodes WHERE snapshot_id = ?", (snapshot_id,)).fetchone()["count"] or 0)
        edge_count = int(conn.execute("SELECT COUNT(*) AS count FROM analysis_graph_edges WHERE snapshot_id = ?", (snapshot_id,)).fetchone()["count"] or 0)
        if node_count <= 0:
            raise KnowledgeError("GRAPH_SNAPSHOT_VALIDATION_FAILED", "Graph snapshot must contain at least one node.")
        wrong_source = conn.execute(
            """
            SELECT 1
            FROM (
                SELECT source_id FROM analysis_graph_nodes WHERE snapshot_id = ?
                UNION ALL
                SELECT source_id FROM analysis_graph_edges WHERE snapshot_id = ?
                UNION ALL
                SELECT source_id FROM analysis_graph_evidence WHERE snapshot_id = ?
                UNION ALL
                SELECT source_id FROM analysis_graph_claims WHERE snapshot_id = ?
                UNION ALL
                SELECT source_id FROM analysis_graph_diagnostics WHERE snapshot_id = ?
            )
            WHERE source_id != ?
            LIMIT 1
            """,
            (snapshot_id, snapshot_id, snapshot_id, snapshot_id, snapshot_id, source_id),
        ).fetchone()
        if wrong_source is not None:
            raise KnowledgeError("GRAPH_SNAPSHOT_VALIDATION_FAILED", "Graph snapshot contains rows for a different source.")
        missing_endpoint = conn.execute(
            """
            SELECT 1
            FROM analysis_graph_edges edge
            LEFT JOIN analysis_graph_nodes source_node
              ON source_node.snapshot_id = edge.snapshot_id
             AND source_node.id = edge.from_node_id
            LEFT JOIN analysis_graph_nodes target_node
              ON target_node.snapshot_id = edge.snapshot_id
             AND target_node.id = edge.to_node_id
            WHERE edge.snapshot_id = ?
              AND (source_node.id IS NULL OR (edge.to_node_id IS NOT NULL AND target_node.id IS NULL))
            LIMIT 1
            """,
            (snapshot_id,),
        ).fetchone()
        if missing_endpoint is not None:
            raise KnowledgeError("GRAPH_SNAPSHOT_VALIDATION_FAILED", "Graph snapshot contains an edge with a missing endpoint.")
        missing_claim_node = conn.execute(
            """
            SELECT 1
            FROM analysis_graph_claims claim
            LEFT JOIN analysis_graph_nodes node
              ON node.snapshot_id = claim.snapshot_id
             AND node.id = claim.node_id
            WHERE claim.snapshot_id = ?
              AND node.id IS NULL
            LIMIT 1
            """,
            (snapshot_id,),
        ).fetchone()
        if missing_claim_node is not None:
            raise KnowledgeError("GRAPH_SNAPSHOT_VALIDATION_FAILED", "Graph snapshot contains a claim for a missing node.")
        if edge_count < 0:
            raise KnowledgeError("GRAPH_SNAPSHOT_VALIDATION_FAILED", "Graph snapshot edge count is invalid.")

    def _rebuild_graph_snapshot_metrics(self, conn: sqlite3.Connection, snapshot_id: str, source_id: str) -> None:
        conn.execute("DELETE FROM graph_snapshot_metrics WHERE snapshot_id = ?", (snapshot_id,))
        now = datetime.now(timezone.utc).isoformat()
        values = self._graph_metric_dimensions(conn, snapshot_id)
        for flow_domain in values["flow_domain"]:
            for fact_origin in values["fact_origin"]:
                for node_kind in values["node_kind"]:
                    for edge_type in values["edge_type"]:
                        for include_external in ("show", "hide"):
                            for include_unresolved in (True, False):
                                for include_isolated in (True, False):
                                    query = GraphSnapshotQuery(
                                        source_id=source_id,
                                        snapshot_id=snapshot_id,
                                        resource="manifest",
                                        flow_domain=flow_domain,
                                        fact_origin=fact_origin,
                                        node_kind=node_kind,
                                        edge_type=edge_type,
                                        include_external=include_external,
                                        include_unresolved=include_unresolved,
                                        include_isolated=include_isolated,
                                    )
                                    self._insert_graph_snapshot_metric(conn, query, now)

    def _graph_metric_dimensions(self, conn: sqlite3.Connection, snapshot_id: str) -> Dict[str, List[str]]:
        def values(table: str, column: str) -> List[str]:
            rows = conn.execute(
                f"SELECT DISTINCT {column} AS value FROM {table} WHERE snapshot_id = ? AND {column} IS NOT NULL ORDER BY {column}",
                (snapshot_id,),
            ).fetchall()
            return ["ALL", *[str(row["value"]).upper() for row in rows if row["value"]]]

        flow_values = sorted(set(values("analysis_graph_nodes", "flow_domain") + values("analysis_graph_edges", "flow_domain")))
        fact_values = sorted(set(values("analysis_graph_nodes", "fact_origin") + values("analysis_graph_edges", "fact_origin")))
        return {
            "flow_domain": flow_values or ["ALL"],
            "fact_origin": fact_values or ["ALL"],
            "node_kind": values("analysis_graph_nodes", "node_kind"),
            "edge_type": values("analysis_graph_edges", "edge_type"),
        }

    def _insert_graph_snapshot_metric(self, conn: sqlite3.Connection, query: GraphSnapshotQuery, created_at: str) -> None:
        node_where, node_params = self._graph_snapshot_node_where(
            query.snapshot_id,
            query.source_id,
            query.flow_domain,
            query.fact_origin,
            query.node_kind,
            query.include_external,
            query.include_isolated,
        )
        edge_where, edge_params = self._graph_snapshot_edge_where(
            query.snapshot_id,
            query.source_id,
            query.flow_domain,
            query.fact_origin,
            query.edge_type,
            query.include_unresolved,
        )
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
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_snapshot_metrics(
                snapshot_id, query_fingerprint, source_id, flow_domain, fact_origin, node_kind, edge_type,
                include_external, include_unresolved, include_isolated, total_node_count, total_edge_count,
                node_type_counts_json, edge_type_counts_json, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                query.snapshot_id,
                query.fingerprint,
                query.source_id,
                query.flow_domain,
                query.fact_origin,
                query.node_kind,
                query.edge_type,
                query.include_external,
                1 if query.include_unresolved else 0,
                1 if query.include_isolated else 0,
                node_count,
                edge_count,
                json.dumps(node_types, separators=(",", ":")),
                json.dumps(edge_types, separators=(",", ":")),
                created_at,
            ),
        )

    def _stored_graph_manifest(self, conn: sqlite3.Connection, snapshot_id: str, source_id: str, generated_at: str) -> Dict[str, Any]:
        query = self._graph_query(conn, "manifest", snapshot_id, source_id, None, None, None, None, "show", True, True)
        metric = self._graph_metric(conn, query)
        if metric is None:
            raise KnowledgeError("GRAPH_SNAPSHOT_METRICS_MISSING", "Graph snapshot metrics are missing for this query.")
        revision = self._graph_snapshot_revision(query)
        return {
            "graphRevision": revision,
            "snapshotId": snapshot_id,
            "sourceId": source_id,
            "sourceName": self._graph_source_name(conn, source_id),
            "flowDomain": None,
            "filters": self._graph_query_filters(query),
            "queryFingerprint": query.fingerprint,
            "totalNodeCount": int(metric["total_node_count"] or 0),
            "totalEdgeCount": int(metric["total_edge_count"] or 0),
            "connectedComponentCount": None,
            "largestComponentNodeCount": None,
            "largestComponentEdgeCount": None,
            "nodeTypeCounts": self._json_dict(metric["node_type_counts_json"]),
            "edgeTypeCounts": self._json_dict(metric["edge_type_counts_json"]),
            "defaultNodePageSize": 500,
            "defaultEdgePageSize": 1000,
            "etag": self._graph_snapshot_etag(revision),
            "generatedAt": generated_at,
            "status": self._graph_status_or_empty(conn, source_id),
        }

    def _current_snapshot_id(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Optional[str]:
        if not source_id:
            row = conn.execute("SELECT snapshot_id FROM graph_current_snapshots ORDER BY published_at DESC LIMIT 1").fetchone()
            return row["snapshot_id"] if row else None
        row = conn.execute("SELECT snapshot_id FROM graph_current_snapshots WHERE source_id = ?", (source_id,)).fetchone()
        return row["snapshot_id"] if row else None

    def _snapshot_id_from_revision(self, graph_revision: str) -> Optional[str]:
        marker = ":graph-snapshot:"
        if marker not in graph_revision:
            return None
        return graph_revision.split(marker, 1)[1]

    def _delete_file_graph_from_snapshot(self, conn: sqlite3.Connection, file_id: int, snapshot_id: str) -> None:
        graph_node_ids = [
            row["id"]
            for row in conn.execute(
                "SELECT id FROM analysis_graph_nodes WHERE snapshot_id = ? AND (analysis_file_id = ? OR inventory_file_id = ?)",
                (snapshot_id, file_id, file_id),
            ).fetchall()
        ]
        if graph_node_ids:
            placeholders = ",".join("?" for _ in graph_node_ids)
            conn.execute(f"DELETE FROM analysis_graph_claims WHERE snapshot_id = ? AND node_id IN ({placeholders})", [snapshot_id, *graph_node_ids])
            conn.execute(
                f"DELETE FROM analysis_graph_edges WHERE snapshot_id = ? AND (from_node_id IN ({placeholders}) OR to_node_id IN ({placeholders}))",
                [snapshot_id, *graph_node_ids, *graph_node_ids],
            )
        conn.execute("DELETE FROM analysis_graph_nodes WHERE snapshot_id = ? AND (analysis_file_id = ? OR inventory_file_id = ?)", (snapshot_id, file_id, file_id))
        conn.execute("DELETE FROM analysis_graph_evidence WHERE snapshot_id = ? AND (analysis_file_id = ? OR inventory_file_id = ?)", (snapshot_id, file_id, file_id))
        conn.execute(
            "DELETE FROM analysis_graph_diagnostics WHERE snapshot_id = ? AND (analysis_file_id = ? OR inventory_file_id = ?)",
            (snapshot_id, file_id, file_id),
        )

    def _retain_graph_snapshots(self, conn: sqlite3.Connection, source_id: str, keep_published: int = 3) -> None:
        current = self._current_snapshot_id(conn, source_id)
        published = [
            row["snapshot_id"]
            for row in conn.execute(
                """
                SELECT snapshot_id
                FROM graph_snapshots
                WHERE source_id = ?
                  AND state IN ('PUBLISHED', 'RETIRED')
                ORDER BY published_at DESC, created_at DESC
                """,
                (source_id,),
            ).fetchall()
        ]
        keep = set(published[: max(1, keep_published)])
        if current:
            keep.add(current)
        rows = conn.execute(
            """
            SELECT snapshot_id
            FROM graph_snapshots
            WHERE source_id = ?
              AND snapshot_id NOT IN (%s)
            """
            % ",".join("?" for _ in keep),
            [source_id, *keep],
        ).fetchall() if keep else conn.execute("SELECT snapshot_id FROM graph_snapshots WHERE source_id = ?", (source_id,)).fetchall()
        for row in rows:
            snapshot_id = row["snapshot_id"]
            conn.execute(
                """
                INSERT INTO graph_snapshot_tombstones(snapshot_id, source_id, expired_at, reason)
                VALUES (?, ?, ?, 'RETENTION')
                ON CONFLICT(snapshot_id) DO UPDATE SET expired_at = excluded.expired_at, reason = excluded.reason
                """,
                (snapshot_id, source_id, datetime.now(timezone.utc).isoformat()),
            )
            conn.execute("DELETE FROM graph_current_snapshots WHERE snapshot_id = ?", (snapshot_id,))
            self._delete_graph_snapshot_rows(conn, snapshot_id)
            conn.execute("DELETE FROM graph_snapshots WHERE snapshot_id = ?", (snapshot_id,))

    def _delete_graph_snapshot_rows(self, conn: sqlite3.Connection, snapshot_id: str) -> None:
        for table in ("graph_snapshot_metrics", "analysis_graph_claims", "analysis_graph_edges", "analysis_graph_evidence", "analysis_graph_diagnostics", "analysis_graph_nodes"):
            conn.execute(f"DELETE FROM {table} WHERE snapshot_id = ?", (snapshot_id,))

    def _backfill_legacy_graph_snapshots(self, conn: sqlite3.Connection) -> None:
        rows = conn.execute(
            """
            SELECT source_id
            FROM analysis_graph_nodes
            WHERE snapshot_id IS NULL
            GROUP BY source_id
            """
        ).fetchall()
        now = datetime.now(timezone.utc).isoformat()
        for row in rows:
            source_id = row["source_id"]
            snapshot_id = self._graph_snapshot_id("legacy", source_id)
            self._ensure_building_graph_snapshot(conn, snapshot_id, source_id, "legacy", now)
            for table in ("analysis_graph_nodes", "analysis_graph_edges", "analysis_graph_evidence", "analysis_graph_claims", "analysis_graph_diagnostics"):
                conn.execute(f"UPDATE {table} SET snapshot_id = ? WHERE source_id = ? AND snapshot_id IS NULL", (snapshot_id, source_id))
            self._publish_graph_snapshot(conn, snapshot_id)
        pending_legacy_rows = conn.execute(
            """
            SELECT snapshot_id
            FROM graph_snapshots
            WHERE job_id = 'legacy'
              AND state = 'BUILDING'
            """
        ).fetchall()
        for row in pending_legacy_rows:
            node_count = int(conn.execute(
                "SELECT COUNT(*) AS count FROM analysis_graph_nodes WHERE snapshot_id = ?",
                (row["snapshot_id"],),
            ).fetchone()["count"] or 0)
            if node_count > 0:
                self._publish_graph_snapshot(conn, row["snapshot_id"])

    def _migrate_legacy_symbol_relation_tables(self, conn: sqlite3.Connection) -> None:
        tables = {
            row["name"]
            for row in conn.execute(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                  AND name IN ('analysis_symbols', 'analysis_symbol_roles', 'analysis_relations')
                """
            ).fetchall()
        }
        if "analysis_symbols" not in tables:
            return
        now = datetime.now(timezone.utc).isoformat()
        sources = conn.execute("SELECT DISTINCT source_id FROM analysis_symbols WHERE source_id IS NOT NULL ORDER BY source_id").fetchall()
        for source in sources:
            source_id = source["source_id"]
            snapshot_id = self._graph_snapshot_id("legacy-symbols", source_id)
            if conn.execute("SELECT 1 FROM graph_snapshots WHERE snapshot_id = ? AND state IN ('PUBLISHED', 'RETIRED')", (snapshot_id,)).fetchone():
                continue
            self._ensure_building_graph_snapshot(conn, snapshot_id, source_id, "legacy-symbols", now)
            symbols = conn.execute(
                """
                SELECT *
                FROM analysis_symbols
                WHERE source_id = ?
                ORDER BY symbol_id
                """,
                (source_id,),
            ).fetchall()
            for symbol in symbols:
                metadata = self._json_dict(symbol["metadata_json"])
                node_kind = self._node_kind_from_source_kind(symbol["kind"])
                conn.execute(
                    """
                    INSERT OR IGNORE INTO analysis_graph_nodes(
                        id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key,
                        node_kind, language, name, qualified_name, display_name, parent_node_id, line_start, line_end,
                        confidence, status, metadata_json, created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, 'legacy-symbols', ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 1.0, 'TRUSTED', ?, ?, 'MIGRATED', ?)
                    """,
                    (
                        symbol["symbol_id"],
                        snapshot_id,
                        source_id,
                        symbol["file_id"],
                        symbol["file_id"],
                        f"legacy:{source_id}:{symbol['symbol_id']}",
                        node_kind,
                        metadata.get("language"),
                        symbol["name"],
                        metadata.get("qualifiedName") or symbol["name"],
                        symbol["name"],
                        symbol["line_start"],
                        symbol["line_end"],
                        json.dumps({**metadata, "sourceKind": symbol["kind"]}, separators=(",", ":")),
                        now,
                        metadata.get("flowDomain") or "CODE",
                    ),
                )
            if "analysis_symbol_roles" in tables:
                roles = conn.execute(
                    """
                    SELECT role.*
                    FROM analysis_symbol_roles role
                    JOIN analysis_symbols symbol ON symbol.symbol_id = role.symbol_id
                    WHERE symbol.source_id = ?
                    ORDER BY role.symbol_id, role.role
                    """,
                    (source_id,),
                ).fetchall()
                for role in roles:
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO analysis_graph_claims(
                            id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                            evidence_ids_json, metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, 'legacy-symbols', ?, ?, 'ROLE', ?, ?, 'TRUSTED', '[]', ?, NULL, ?, 'MIGRATED', 'CODE')
                        """,
                        (
                            f"legacy-role:{role['symbol_id']}:{role['role']}",
                            snapshot_id,
                            source_id,
                            role["symbol_id"],
                            role["role"],
                            role["confidence"],
                            json.dumps(
                                {
                                    "classifier": role["classifier"],
                                    "classifierVersion": role["classifier_version"],
                                    "legacyEvidence": self._json_list(role["evidence_json"]),
                                },
                                separators=(",", ":"),
                            ),
                            now,
                        ),
                    )
            if "analysis_relations" in tables:
                relations = conn.execute(
                    """
                    SELECT relation.*
                    FROM analysis_relations relation
                    JOIN analysis_symbols source_symbol ON source_symbol.symbol_id = relation.from_symbol_id
                    JOIN analysis_symbols target_symbol ON target_symbol.symbol_id = relation.to_symbol_id
                    WHERE relation.source_id = ?
                      AND source_symbol.source_id = relation.source_id
                      AND target_symbol.source_id = relation.source_id
                    ORDER BY relation.relation_id
                    """,
                    (source_id,),
                ).fetchall()
                for relation in relations:
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO analysis_graph_edges(
                            id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id,
                            to_node_id, edge_type, resolution_status, confidence, evidence_id, unresolved_target_json,
                            metadata_json, status, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, 'legacy-symbols', ?, NULL, NULL, ?, ?, ?, 'RESOLVED', ?, NULL, NULL, ?, 'TRUSTED', ?, 'MIGRATED', ?)
                        """,
                        (
                            relation["relation_id"],
                            snapshot_id,
                            source_id,
                            relation["from_symbol_id"],
                            relation["to_symbol_id"],
                            relation["relation"],
                            relation["confidence"],
                            json.dumps(
                                {
                                    **self._json_dict(relation["metadata_json"]),
                                    "legacyEvidence": self._json_list(relation["evidence_json"]),
                                },
                                separators=(",", ":"),
                            ),
                            now,
                            self._json_dict(relation["metadata_json"]).get("flowDomain") or "CODE",
                        ),
                    )
            self._migration_stage("after_legacy_copy")
            self._migration_stage("before_current_activation")
            self._publish_graph_snapshot(conn, snapshot_id)

    def _delete_file_analysis(self, conn: sqlite3.Connection, file_id: int) -> None:
        # Graph facts are immutable snapshot-owned rows. They are removed only by
        # snapshot retention/cascade, never by current-file state cleanup.
        return

    def _resolve_source_call_edges(self, conn: sqlite3.Connection, source_id: str, snapshot_id: str) -> None:
        rows = conn.execute(
            """
            SELECT id, metadata_json
            FROM analysis_graph_edges
            WHERE source_id = ?
              AND snapshot_id = ?
              AND edge_type = 'CALLS'
              AND to_node_id IS NULL
              AND resolution_status IN ('UNRESOLVED', 'MULTIPLE_CANDIDATES')
        """,
            (source_id, snapshot_id),
        ).fetchall()
        if not rows:
            return
        type_rows = conn.execute(
            """
            SELECT id, name, qualified_name
            FROM analysis_graph_nodes
            WHERE source_id = ?
              AND snapshot_id = ?
              AND node_kind = 'TYPE'
              AND status = 'TRUSTED'
        """,
            (source_id, snapshot_id),
        ).fetchall()
        types_by_simple: Dict[str, List[sqlite3.Row]] = {}
        types_by_qualified: Dict[str, List[sqlite3.Row]] = {}
        for row in type_rows:
            types_by_simple.setdefault(row["name"], []).append(row)
            if row["qualified_name"]:
                types_by_qualified.setdefault(row["qualified_name"], []).append(row)
        for edge in rows:
            metadata = self._json_dict(edge["metadata_json"])
            method_name = metadata.get("methodName")
            type_hint = metadata.get("receiverTypeHint") or metadata.get("targetTypeText")
            if not method_name or not type_hint:
                continue
            type_candidates = types_by_qualified.get(str(type_hint), []) or types_by_simple.get(str(type_hint).rsplit(".", 1)[-1], [])
            if len(type_candidates) != 1:
                if len(type_candidates) > 1:
                    self._mark_call_edge_multiple(conn, snapshot_id, edge["id"], metadata, len(type_candidates))
                continue
            callable_candidates = self._callable_candidates_for_type(conn, snapshot_id, type_candidates[0]["id"], str(method_name), metadata.get("argumentCount"))
            if len(callable_candidates) == 1:
                metadata["resolutionStatus"] = "RESOLVED"
                metadata["resolver"] = "STATIC_TYPE_HINT"
                metadata = classify_call_metadata(metadata, metadata.get("flowDomain"), None, "RESOLVED", None)
                conn.execute(
                    """
                    UPDATE analysis_graph_edges
                    SET to_node_id = ?,
                        resolution_status = 'RESOLVED',
                        unresolved_target_json = NULL,
                        metadata_json = ?
                    WHERE snapshot_id = ?
                      AND id = ?
                """,
                    (callable_candidates[0]["id"], json.dumps(metadata), snapshot_id, edge["id"]),
                )
            elif len(callable_candidates) > 1:
                self._mark_call_edge_multiple(conn, snapshot_id, edge["id"], metadata, len(callable_candidates))

    def _callable_candidates_for_type(
        self, conn: sqlite3.Connection, snapshot_id: str, type_node_id: str, method_name: str, argument_count: Optional[int]
    ) -> List[sqlite3.Row]:
        rows = conn.execute(
            """
            SELECT id, metadata_json
            FROM analysis_graph_nodes
            WHERE parent_node_id = ?
              AND snapshot_id = ?
              AND node_kind = 'CALLABLE'
              AND name = ?
              AND status = 'TRUSTED'
        """,
            (type_node_id, snapshot_id, method_name),
        ).fetchall()
        if argument_count is None:
            return rows
        matching = []
        for row in rows:
            metadata = self._json_dict(row["metadata_json"])
            parameters = metadata.get("parameters") if isinstance(metadata.get("parameters"), list) else []
            if len(parameters) == int(argument_count):
                matching.append(row)
        return matching or rows

    def _mark_call_edge_multiple(self, conn: sqlite3.Connection, snapshot_id: str, edge_id: str, metadata: Dict[str, Any], candidate_count: int) -> None:
        metadata["resolutionStatus"] = "MULTIPLE_CANDIDATES"
        metadata["candidateCount"] = candidate_count
        metadata["candidateKind"] = metadata.get("candidateKind") or "METHOD"
        metadata = classify_call_metadata(metadata, metadata.get("flowDomain"), None, "MULTIPLE_CANDIDATES", None)
        conn.execute(
            """
            UPDATE analysis_graph_edges
            SET resolution_status = 'MULTIPLE_CANDIDATES',
                metadata_json = ?
            WHERE snapshot_id = ?
              AND id = ?
        """,
            (json.dumps(metadata), snapshot_id, edge_id),
        )

    def _reattach_current_analysis_files(self, conn: sqlite3.Connection, source_ids: Optional[List[str]]) -> None:
        clauses = ["current.id IS NULL"]
        params: list[Any] = []
        if source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            clauses.append(f"af.source_id IN ({placeholders})")
            params.extend(source_ids)
        where = " AND ".join(clauses)
        rows = conn.execute(
            f"""
            SELECT af.file_id AS old_file_id, f.id AS new_file_id
            FROM analysis_files af
            LEFT JOIN files current ON current.id = af.file_id
            JOIN files f
              ON f.source_id = af.source_id
             AND f.relative_path = af.relative_path
             AND f.content_hash = af.content_hash
            WHERE {where}
        """,
            params,
        ).fetchall()
        for row in rows:
            old_file_id = row["old_file_id"]
            new_file_id = row["new_file_id"]
            if old_file_id == new_file_id:
                continue
            existing = conn.execute("SELECT 1 FROM analysis_files WHERE file_id = ?", (new_file_id,)).fetchone()
            if existing:
                self._delete_file_analysis(conn, old_file_id)
                conn.execute("DELETE FROM analysis_files WHERE file_id = ?", (old_file_id,))
                continue
            conn.execute("UPDATE analysis_files SET file_id = ? WHERE file_id = ?", (new_file_id, old_file_id))
            # Snapshot graph rows retain their original analysis_file_id for immutable history.

    def _upsert_file(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
            (
                file_id,
                state["source_id"],
                state["relative_path"],
                state["content_hash"],
                state["analyzer_name"],
                state["analyzer_version"],
                state["status"],
                state.get("analyzed_at"),
                state["symbol_count"],
                state["relation_count"],
                state.get("attempt_count", 0),
                state.get("last_attempt_at"),
                state.get("last_error_code"),
                state.get("last_error_message"),
                state.get("last_raw_response_preview"),
                json.dumps(state.get("diagnostics") or []),
                state.get("engine_version"),
                state.get("flow_domain"),
            ),
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
            job.get("symbolCount", 0),
            job.get("relationCount", 0),
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
            "symbolCount": row["symbol_count"],
            "relationCount": row["relation_count"],
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
            "symbolCount": row["symbol_count"],
            "relationCount": row["relation_count"],
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

    def _drop_legacy_fact_tables(self, conn: sqlite3.Connection) -> None:
        for table in (
            "symbol_tokens",
            "edges",
            "symbols",
            "file_extraction_state",
            "fact_builds",
            "analysis_symbols",
            "analysis_symbol_roles",
            "analysis_relations",
        ):
            conn.execute(f"DROP TABLE IF EXISTS {table}")

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
            self._drop_legacy_analysis_job_counter(conn)
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
            self._ensure_graph_snapshot_schema(conn)
            return
        raise RuntimeError(f"Unknown analysis schema migration: {version}")

    def _create_analysis_graph_diagnostics_table(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_diagnostics (
                id TEXT NOT NULL,
                snapshot_id TEXT NOT NULL,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
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
                PRIMARY KEY(snapshot_id, id),
                UNIQUE(source_id, snapshot_id, id),
                FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
            )
        """)

    def _ensure_graph_snapshot_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS graph_snapshots (
                snapshot_id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                job_id TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at TEXT NOT NULL,
                published_at TEXT,
                manifest_json TEXT NOT NULL DEFAULT '{}',
                content_identity TEXT,
                UNIQUE(source_id, snapshot_id)
            )
        """)
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_graph_snapshots_source_snapshot ON graph_snapshots(source_id, snapshot_id)")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS graph_current_snapshots (
                source_id TEXT PRIMARY KEY,
                snapshot_id TEXT NOT NULL,
                published_at TEXT NOT NULL,
                FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE RESTRICT
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS graph_snapshot_metrics (
                snapshot_id TEXT NOT NULL,
                query_fingerprint TEXT NOT NULL,
                source_id TEXT NOT NULL,
                flow_domain TEXT NOT NULL,
                fact_origin TEXT NOT NULL,
                node_kind TEXT NOT NULL,
                edge_type TEXT NOT NULL,
                include_external TEXT NOT NULL,
                include_unresolved INTEGER NOT NULL,
                include_isolated INTEGER NOT NULL,
                total_node_count INTEGER NOT NULL,
                total_edge_count INTEGER NOT NULL,
                node_type_counts_json TEXT NOT NULL,
                edge_type_counts_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY(snapshot_id, query_fingerprint),
                FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS graph_snapshot_tombstones (
                snapshot_id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                expired_at TEXT NOT NULL,
                reason TEXT NOT NULL
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_graph_snapshot_metrics_lookup ON graph_snapshot_metrics(snapshot_id, query_fingerprint)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_graph_snapshot_tombstones_source ON graph_snapshot_tombstones(source_id, expired_at)")
        self._rebuild_graph_current_snapshots_table_if_needed(conn)
        self._rebuild_graph_snapshot_metrics_table_if_needed(conn)
        for table in ("analysis_graph_nodes", "analysis_graph_evidence", "analysis_graph_claims", "analysis_graph_edges", "analysis_graph_diagnostics"):
            self._ensure_column(conn, table, "snapshot_id", "TEXT")
        self._ensure_legacy_graph_snapshot_rows(conn)
        for table in ("analysis_graph_nodes", "analysis_graph_evidence", "analysis_graph_claims", "analysis_graph_edges", "analysis_graph_diagnostics"):
            self._rebuild_graph_snapshot_table_if_needed(conn, table)
        self._backfill_legacy_graph_snapshots(conn)

    def _table_sql(self, conn: sqlite3.Connection, table: str) -> str:
        row = conn.execute("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
        return str(row["sql"] or "") if row else ""

    def _table_exists(self, conn: sqlite3.Connection, table: str) -> bool:
        row = conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
        return row is not None

    def _rebuild_graph_current_snapshots_table_if_needed(self, conn: sqlite3.Connection) -> None:
        sql = self._table_sql(conn, "graph_current_snapshots")
        if "FOREIGN KEY(source_id, snapshot_id)" in sql:
            return
        suffix = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        old_table = f"graph_current_snapshots_old_{suffix}"
        conn.execute(f"ALTER TABLE graph_current_snapshots RENAME TO {old_table}")
        conn.execute("""
            CREATE TABLE graph_current_snapshots (
                source_id TEXT PRIMARY KEY,
                snapshot_id TEXT NOT NULL,
                published_at TEXT NOT NULL,
                FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE RESTRICT
            )
        """)
        old_columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({old_table})").fetchall()}
        if {"source_id", "snapshot_id", "published_at"}.issubset(old_columns):
            conn.execute(
                f"""
                INSERT INTO graph_current_snapshots(source_id, snapshot_id, published_at)
                SELECT current.source_id, current.snapshot_id, current.published_at
                FROM {old_table} current
                JOIN graph_snapshots snapshot
                  ON snapshot.source_id = current.source_id
                 AND snapshot.snapshot_id = current.snapshot_id
                """
            )
        conn.execute(f"DROP TABLE {old_table}")

    def _rebuild_graph_snapshot_metrics_table_if_needed(self, conn: sqlite3.Connection) -> None:
        sql = self._table_sql(conn, "graph_snapshot_metrics")
        if "FOREIGN KEY(source_id, snapshot_id)" in sql:
            return
        suffix = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        old_table = f"graph_snapshot_metrics_old_{suffix}"
        conn.execute(f"ALTER TABLE graph_snapshot_metrics RENAME TO {old_table}")
        conn.execute("""
            CREATE TABLE graph_snapshot_metrics (
                snapshot_id TEXT NOT NULL,
                query_fingerprint TEXT NOT NULL,
                source_id TEXT NOT NULL,
                flow_domain TEXT NOT NULL,
                fact_origin TEXT NOT NULL,
                node_kind TEXT NOT NULL,
                edge_type TEXT NOT NULL,
                include_external TEXT NOT NULL,
                include_unresolved INTEGER NOT NULL,
                include_isolated INTEGER NOT NULL,
                total_node_count INTEGER NOT NULL,
                total_edge_count INTEGER NOT NULL,
                node_type_counts_json TEXT NOT NULL,
                edge_type_counts_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                PRIMARY KEY(snapshot_id, query_fingerprint),
                FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
            )
        """)
        old_columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({old_table})").fetchall()}
        expected = {
            "snapshot_id", "query_fingerprint", "source_id", "flow_domain", "fact_origin", "node_kind", "edge_type",
            "include_external", "include_unresolved", "include_isolated", "total_node_count", "total_edge_count",
            "node_type_counts_json", "edge_type_counts_json", "created_at",
        }
        if expected.issubset(old_columns):
            conn.execute(
                f"""
                INSERT INTO graph_snapshot_metrics(
                    snapshot_id, query_fingerprint, source_id, flow_domain, fact_origin, node_kind, edge_type,
                    include_external, include_unresolved, include_isolated, total_node_count, total_edge_count,
                    node_type_counts_json, edge_type_counts_json, created_at
                )
                SELECT metrics.snapshot_id, metrics.query_fingerprint, metrics.source_id, metrics.flow_domain,
                       metrics.fact_origin, metrics.node_kind, metrics.edge_type, metrics.include_external,
                       metrics.include_unresolved, metrics.include_isolated, metrics.total_node_count,
                       metrics.total_edge_count, metrics.node_type_counts_json, metrics.edge_type_counts_json,
                       metrics.created_at
                FROM {old_table} metrics
                JOIN graph_snapshots snapshot
                  ON snapshot.source_id = metrics.source_id
                 AND snapshot.snapshot_id = metrics.snapshot_id
                """
            )
        conn.execute(f"DROP TABLE {old_table}")

    def _reconcile_graph_diagnostics_schema(self, conn: sqlite3.Connection) -> None:
        columns = {row["name"]: row for row in conn.execute("PRAGMA table_info(analysis_graph_diagnostics)").fetchall()}
        id_column = columns.get("id")
        if id_column is None:
            self._create_analysis_graph_diagnostics_table(conn)
            columns = {row["name"]: row for row in conn.execute("PRAGMA table_info(analysis_graph_diagnostics)").fetchall()}
            id_column = columns.get("id")
        id_type = str(id_column["type"] or "").upper() if id_column else ""
        if id_type != "TEXT":
            suffix = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
            old_table = f"analysis_graph_diagnostics_old_{suffix}"
            conn.execute(f"ALTER TABLE analysis_graph_diagnostics RENAME TO {old_table}")
            self._create_analysis_graph_diagnostics_table(conn)
            conn.execute(f"DROP TABLE {old_table}")
            columns = {row["name"]: row for row in conn.execute("PRAGMA table_info(analysis_graph_diagnostics)").fetchall()}
        for column, declaration in {
            "snapshot_id": "TEXT",
            "candidate_id": "TEXT",
            "line_start": "INTEGER",
            "line_end": "INTEGER",
            "fact_origin": "TEXT",
            "flow_domain": "TEXT",
        }.items():
            if column not in columns:
                conn.execute(f"ALTER TABLE analysis_graph_diagnostics ADD COLUMN {column} {declaration}")
                columns[column] = {"name": column}
        self._ensure_legacy_graph_snapshot_rows(conn)
        self._rebuild_graph_snapshot_table_if_needed(conn, "analysis_graph_diagnostics")

    def _ensure_legacy_graph_snapshot_rows(self, conn: sqlite3.Connection) -> None:
        now = datetime.now(timezone.utc).isoformat()
        source_ids: set[str] = set()
        for table in ("analysis_graph_nodes", "analysis_graph_edges", "analysis_graph_evidence", "analysis_graph_claims", "analysis_graph_diagnostics"):
            exists = conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
            if exists is None:
                continue
            columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
            if "source_id" not in columns or "snapshot_id" not in columns:
                continue
            rows = conn.execute(f"SELECT DISTINCT source_id FROM {table} WHERE source_id IS NOT NULL AND snapshot_id IS NULL").fetchall()
            source_ids.update(row["source_id"] for row in rows)
        for source_id in source_ids:
            snapshot_id = self._graph_snapshot_id("legacy", source_id)
            conn.execute(
                """
                INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
                VALUES (?, ?, 'legacy', 'BUILDING', ?, NULL, '{}', NULL)
                ON CONFLICT(snapshot_id) DO NOTHING
                """,
                (snapshot_id, source_id, now),
            )

    def _rebuild_graph_snapshot_table_if_needed(self, conn: sqlite3.Connection, table: str) -> None:
        columns = conn.execute(f"PRAGMA table_info({table})").fetchall()
        if not columns:
            return
        table_sql = self._table_sql(conn, table)
        pk_columns = {row["name"]: row["pk"] for row in columns if row["pk"]}
        snapshot_column = next((row for row in columns if row["name"] == "snapshot_id"), None)
        if (
            pk_columns.get("snapshot_id") == 1
            and pk_columns.get("id") == 2
            and snapshot_column is not None
            and snapshot_column["notnull"]
            and "FOREIGN KEY(source_id, snapshot_id)" in table_sql
            and "_old_" not in table_sql
        ):
            return
        suffix = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        old_table = f"{table}_old_{suffix}"
        conn.execute(f"ALTER TABLE {table} RENAME TO {old_table}")
        self._create_graph_snapshot_owned_table(conn, table)
        old_columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({old_table})").fetchall()}
        new_columns = [row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()]
        insert_columns = [column for column in new_columns if column in old_columns or column == "snapshot_id"]
        select_columns = []
        for column in insert_columns:
            if column == "snapshot_id" and "snapshot_id" in old_columns:
                select_columns.append("COALESCE(snapshot_id, 'legacy:' || source_id)")
            elif column == "job_id" and "job_id" in old_columns:
                select_columns.append("COALESCE(job_id, 'legacy')")
            else:
                select_columns.append(column)
        conn.execute(
            f"""
            INSERT INTO {table}({", ".join(insert_columns)})
            SELECT {", ".join(select_columns)}
            FROM {old_table}
            """
        )
        conn.execute(f"DROP TABLE {old_table}")

    def _create_graph_snapshot_owned_table(self, conn: sqlite3.Connection, table: str) -> None:
        definitions = {
            "analysis_graph_nodes": """
                CREATE TABLE analysis_graph_nodes (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    stable_key TEXT NOT NULL,
                    node_kind TEXT NOT NULL,
                    language TEXT,
                    name TEXT NOT NULL,
                    qualified_name TEXT,
                    display_name TEXT,
                    parent_node_id TEXT,
                    line_start INTEGER,
                    line_end INTEGER,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
                )
            """,
            "analysis_graph_evidence": """
                CREATE TABLE analysis_graph_evidence (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    content_hash TEXT NOT NULL,
                    line_start INTEGER NOT NULL,
                    line_end INTEGER NOT NULL,
                    excerpt_hash TEXT NOT NULL,
                    evidence_kind TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
                )
            """,
            "analysis_graph_claims": """
                CREATE TABLE analysis_graph_claims (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    claim_kind TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    evidence_ids_json TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    rejection_reason TEXT,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, node_id) REFERENCES analysis_graph_nodes(source_id, snapshot_id, id) ON DELETE CASCADE
                )
            """,
            "analysis_graph_edges": """
                CREATE TABLE analysis_graph_edges (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    from_node_id TEXT NOT NULL,
                    to_node_id TEXT,
                    edge_type TEXT NOT NULL,
                    resolution_status TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    evidence_id TEXT,
                    unresolved_target_json TEXT,
                    metadata_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, from_node_id) REFERENCES analysis_graph_nodes(source_id, snapshot_id, id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, to_node_id) REFERENCES analysis_graph_nodes(source_id, snapshot_id, id) ON DELETE CASCADE,
                    FOREIGN KEY(source_id, snapshot_id, evidence_id) REFERENCES analysis_graph_evidence(source_id, snapshot_id, id) ON DELETE SET NULL
                )
            """,
            "analysis_graph_diagnostics": """
                CREATE TABLE analysis_graph_diagnostics (
                    id TEXT NOT NULL,
                    snapshot_id TEXT NOT NULL,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
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
                    PRIMARY KEY(snapshot_id, id),
                    UNIQUE(source_id, snapshot_id, id),
                    FOREIGN KEY(source_id, snapshot_id) REFERENCES graph_snapshots(source_id, snapshot_id) ON DELETE CASCADE
                )
            """,
        }
        conn.execute(definitions[table])

    def _drop_legacy_analysis_job_counter(self, conn: sqlite3.Connection) -> None:
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(analysis_jobs)").fetchall()}
        if "skipped_unchanged_file_count" not in columns:
            return
        try:
            conn.execute("ALTER TABLE analysis_jobs DROP COLUMN skipped_unchanged_file_count")
        except sqlite3.OperationalError:
            self._rebuild_analysis_jobs_without_legacy_coverage_counter(conn)

    def _rebuild_analysis_jobs_without_legacy_coverage_counter(self, conn: sqlite3.Connection) -> None:
        kept_columns = [
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
            "last_progress_at",
            "symbol_count",
            "relation_count",
            "diagnostics_json",
        ]
        conn.execute("""
            CREATE TABLE analysis_jobs_new (
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
                last_progress_at TEXT,
                symbol_count INTEGER NOT NULL,
                relation_count INTEGER NOT NULL,
                diagnostics_json TEXT NOT NULL
            )
        """)
        joined = ", ".join(kept_columns)
        conn.execute(f"INSERT INTO analysis_jobs_new({joined}) SELECT {joined} FROM analysis_jobs")
        conn.execute("DROP TABLE analysis_jobs")
        conn.execute("ALTER TABLE analysis_jobs_new RENAME TO analysis_jobs")

    def _ensure_column(self, conn: sqlite3.Connection, table: str, column: str, declaration: str) -> None:
        columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
        if column not in columns:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")
