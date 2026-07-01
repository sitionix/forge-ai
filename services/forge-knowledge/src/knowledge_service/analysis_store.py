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
from knowledge_service.semantic_index import SemanticIndexStore, ensure_semantic_index_schema
from knowledge_service.source_catalog import SourceMetadata


ANALYSIS_SCHEMA_MIGRATIONS = (
    (1, "remove_legacy_analysis_job_counter"),
    (2, "add_analysis_job_source_scope"),
    (3, "reset_analysis_cache_for_graph_v1_cutover"),
    (4, "reconcile_graph_diagnostics_schema"),
    (5, "add_analysis_job_mode"),
    (6, "remove_legacy_graph_lifecycle"),
    (7, "current_state_graph_storage"),
)
SQLITE_WRITE_BUSY_TIMEOUT_MS = 5000
SQLITE_STATUS_BUSY_TIMEOUT_MS = 500
GRAPH_STORE_LOCK_RETRY_DELAYS_SECONDS = (0.05, 0.15, 0.3)
SERVICE_DIAGNOSTIC_ROW_LIMIT = 1000
GRAPH_CONTRACT_VERSION = "GRAPH_CURRENT_V1"
GRAPH_SORT_VERSION = "ID_ASC_V1"
GRAPH_CURSOR_SIGNATURE_CONTEXT = "knowledge-graph-cursor-v1"
GRAPH_NODE_DETAIL_RELATION_LIMIT = 25
PARTIAL_GRAPH_NOT_PROMOTED_CODE = "PARTIAL_GRAPH_NOT_PROMOTED"
GRAPH_COVERAGE_RECOVERY_REASON = "CURRENT_GRAPH_DEGRADED_RECOVERED"
GRAPH_COVERAGE_PARTIAL_REASON = "CURRENT_GRAPH_COVERAGE_PARTIAL"


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
            self._drop_rejected_graph_storage(conn)
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
                    line_start INTEGER,
                    line_end INTEGER,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE
                )
            """)
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
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE
                )
            """)
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
                    evidence_ids_json TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    rejection_reason TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    FOREIGN KEY(node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE
                )
            """)
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
                    edge_kind TEXT,
                    resolution_status TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    evidence_id TEXT,
                    evidence_ids_json TEXT NOT NULL DEFAULT '[]',
                    unresolved_target_json TEXT,
                    metadata_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    FOREIGN KEY(analysis_file_id) REFERENCES analysis_files(file_id) ON DELETE CASCADE,
                    FOREIGN KEY(from_node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE,
                    FOREIGN KEY(to_node_id) REFERENCES analysis_graph_nodes(id) ON DELETE CASCADE,
                    FOREIGN KEY(evidence_id) REFERENCES analysis_graph_evidence(id) ON DELETE SET NULL
                )
            """)
            self._create_analysis_graph_diagnostics_table(conn)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_status ON analysis_files(source_id, status)")
            conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_analysis_files_current ON analysis_files(file_id, content_hash, analyzer_name, analyzer_version, engine_version, status)"
            )
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_path ON analysis_files(source_id, relative_path)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_state_source ON analysis_graph_state(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source ON analysis_graph_nodes(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_file ON analysis_graph_nodes(analysis_file_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_flow_created ON analysis_graph_nodes(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_claims_node_kind ON analysis_graph_claims(node_id, claim_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source ON analysis_graph_edges(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_nodes ON analysis_graph_edges(from_node_id, to_node_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_flow_created ON analysis_graph_edges(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_diagnostics_source_code ON analysis_graph_diagnostics(source_id, severity, code)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source ON analysis_graph_nodes(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_file ON analysis_graph_nodes(analysis_file_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_flow_created ON analysis_graph_nodes(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_claims_node_kind ON analysis_graph_claims(node_id, claim_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source ON analysis_graph_edges(source_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_nodes ON analysis_graph_edges(from_node_id, to_node_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_flow_created ON analysis_graph_edges(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_diagnostics_source_code ON analysis_graph_diagnostics(source_id, severity, code)")
            ensure_semantic_index_schema(conn)
            ensure_overview_schema(conn)
            self._current_resolution_has_coverage_tables = self._table_exists(conn, "files") and self._table_exists(conn, "knowledge_source_overview")
            self._migration_stage("after_canonical_schema")
            self._migration_stage("after_pointer_mutation")
            self._drop_legacy_fact_tables(conn)
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
        self._write_with_busy_retry(write)

    def status(self) -> Dict[str, Any]:
        self.init()
        active = self.active_job(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS)
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            latest = conn.execute("SELECT * FROM analysis_jobs WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1").fetchone()
            counts = conn.execute("SELECT COUNT(*) AS symbols FROM analysis_graph_nodes n WHERE " + self._inventory_membership_graph_node_clause("n")).fetchone()
            relations = conn.execute("SELECT COUNT(*) AS relations FROM analysis_graph_edges e WHERE " + self._inventory_membership_graph_edge_clause("e")).fetchone()
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

    def replace_file_analysis(
        self, file_id: int, state: Dict[str, Any], symbols: List[Dict[str, Any]], roles: List[Dict[str, Any]], relations: List[Dict[str, Any]]
    ) -> None:
        raise KnowledgeError("GRAPH_LEGACY_WRITE_REMOVED", "Legacy symbol/relation writes have been removed; use current graph analysis.")

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
        operation = "delete_file_analysis"
        table = "analysis_files"
        try:
            self._upsert_file(conn, file_id, state)
            self._delete_file_graph(conn, file_id)
            operation = "insert_nodes"
            table = "analysis_graph_nodes"
            for node in graph.get("nodes") or []:
                node_file_id = int(node.get("analysis_file_id") or node.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                        INSERT INTO analysis_graph_nodes(
                            id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, stable_key, node_kind,
                            language, name, qualified_name, display_name, parent_node_id, line_start, line_end,
                            confidence, status, metadata_json, created_at, updated_at, fact_origin, flow_domain
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
                        node.get("line_start"),
                        node.get("line_end"),
                        node["confidence"],
                        node["status"],
                        json.dumps(node.get("metadata") or {}),
                        created_at,
                        created_at,
                        node.get("fact_origin"),
                        node.get("flow_domain"),
                    ),
                )
            operation = "insert_evidence"
            table = "analysis_graph_evidence"
            for item in graph.get("evidence") or []:
                evidence_file_id = int(item.get("analysis_file_id") or item.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                        INSERT INTO analysis_graph_evidence(
                            id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash, line_start,
                            line_end, excerpt, excerpt_hash, evidence_kind, metadata_json, created_at, updated_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        json.dumps(item.get("metadata") or {}),
                        created_at,
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
                            id, job_id, source_id, node_id, claim_kind, summary, confidence, status, evidence_ids_json,
                            metadata_json, rejection_reason, created_at, updated_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        json.dumps(claim.get("evidence_ids") or []),
                        json.dumps(claim.get("metadata") or {}),
                        claim.get("rejection_reason"),
                        created_at,
                        created_at,
                        claim.get("fact_origin"),
                        claim.get("flow_domain"),
                    ),
                )
            operation = "insert_edges"
            table = "analysis_graph_edges"
            for edge in graph.get("edges") or []:
                edge_file_id = int(edge.get("analysis_file_id") or edge.get("inventory_file_id") or file_id)
                evidence_ids = [edge["evidence_id"]] if edge.get("evidence_id") else []
                conn.execute(
                    """
                        INSERT INTO analysis_graph_edges(
                            id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                            from_node_id, to_node_id, edge_type, edge_kind, resolution_status, confidence, evidence_id, evidence_ids_json,
                            unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        edge.get("edge_kind") or edge["edge_type"],
                        edge["resolution_status"],
                        edge["confidence"],
                        edge.get("evidence_id"),
                        json.dumps(evidence_ids),
                        json.dumps(edge.get("unresolved_target")) if edge.get("unresolved_target") is not None else None,
                        json.dumps(edge.get("metadata") or {}),
                        edge["status"],
                        created_at,
                        created_at,
                        edge.get("fact_origin"),
                        edge.get("flow_domain"),
                    ),
                )
            operation = "insert_diagnostics"
            table = "analysis_graph_diagnostics"
            for diagnostic in graph.get("diagnostics") or []:
                diagnostic_file_id = int(diagnostic.get("analysis_file_id") or diagnostic.get("inventory_file_id") or file_id)
                conn.execute(
                    """
                        INSERT INTO analysis_graph_diagnostics(
                            id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                            severity, stage, code, diagnostic_code, message, candidate_id, line_start, line_end, metadata_json,
                            created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            self._resolve_source_call_edges(conn, state["source_id"])
            graph_id = self._refresh_graph_state(conn, source_id, created_at)
            if graph_id:
                SemanticIndexStore.mark_current_graph_pending_conn(conn, source_id)
            refresh_overview_for_sources(conn, [state["source_id"]])
        except sqlite3.Error as exc:
            raise self._graph_store_error(table, operation, exc) from exc

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
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            rows = conn.execute(
                f"""
                WITH claim AS (
                    SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                    FROM analysis_graph_claims
                    WHERE status IN ('TRUSTED', 'DERIVED', 'CANDIDATE')
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
                    WHERE claim_kind = 'ENTRYPOINT_HINT'
                      AND status IN ('TRUSTED', 'DERIVED')
                    GROUP BY source_id, node_id
                )
                SELECT n.*,
                       sources.display_name AS source_display_name,
                       af.relative_path,
                       claim.summary,
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
                WHERE n.source_id IN ({source_placeholders})
                  AND {self._inventory_membership_graph_node_clause("n")}
                ORDER BY n.source_id, lower(COALESCE(n.display_name, n.qualified_name, n.name, n.id)), n.id
                LIMIT ?
                """,
                [*source_ids, safe_limit],
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
                    "summary": row["summary"],
                    "metadataText": row["metadata_json"],
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
                    OR lower(COALESCE(n.metadata_json, '')) LIKE ?
                )"""
            )
            token_params.extend([pattern, pattern, pattern, pattern, pattern, pattern, pattern, pattern, pattern])
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            rows = conn.execute(
                f"""
                WITH claim AS (
                    SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                    FROM analysis_graph_claims
                    WHERE status IN ('TRUSTED', 'DERIVED', 'CANDIDATE')
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
                    WHERE claim_kind = 'ENTRYPOINT_HINT'
                      AND status IN ('TRUSTED', 'DERIVED')
                    GROUP BY source_id, node_id
                )
                SELECT n.*,
                       sources.display_name AS source_display_name,
                       af.relative_path,
                       claim.summary,
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
                WHERE n.source_id IN ({source_placeholders})
                  AND {self._inventory_membership_graph_node_clause("n")}
                  AND ({" OR ".join(token_clauses)})
                ORDER BY n.confidence DESC, graph_degree DESC, n.source_id, lower(COALESCE(n.display_name, n.qualified_name, n.name, n.id)), n.id
                LIMIT ?
                """,
                [*source_ids, *token_params, safe_limit],
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
                    "summary": row["summary"],
                    "metadataText": row["metadata_json"],
                    "degree": int(row["graph_degree"] or 0),
                }
            )
            candidates.append(projected)
        return candidates

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
                    relation_rows = conn.execute(
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
                    for edge in relation_rows:
                        from_node_id = str(edge["from_node_id"])
                        to_node_id = str(edge["to_node_id"] or "")
                        if not to_node_id or edge["resolution_status"] in {"UNRESOLVED", "DYNAMIC_TARGET"}:
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
        external = [node for node in nodes if node.get("nodeKind") == "EXTERNAL" or node.get("external")]
        verified_paths = self._verified_paths_from_evidence(evidence)
        return {"nodes": nodes, "edges": edges, "evidence": evidence, "unresolved": unresolved, "external": external, "verifiedPaths": verified_paths}

    def load_call_adjacency_for_sources(
        self,
        source_scopes: List[Dict[str, Any]],
        max_edges: int = 2000,
        max_evidence: int = 25,
    ) -> Dict[str, Any]:
        self.init()
        safe_max_edges = max(1, min(int(max_edges or 1), 10000))
        safe_max_evidence = max(0, min(int(max_evidence or 0), 500))
        grouped: Dict[str, Set[str]] = {}
        for scope in source_scopes:
            source_id = str(scope.get("sourceId") or "")
            if not source_id:
                continue
            anchor_ids = {str(node_id) for node_id in scope.get("nodeIds") or [] if str(node_id)}
            grouped.setdefault(source_id, set()).update(anchor_ids)
        if not grouped:
            return {"nodes": [], "edges": [], "evidence": [], "unresolved": [], "external": [], "verifiedPaths": [], "truncated": False}

        nodes: List[Dict[str, Any]] = []
        edges: List[Dict[str, Any]] = []
        evidence: List[Dict[str, Any]] = []
        truncated = False
        with self._connect(busy_timeout_ms=SQLITE_STATUS_BUSY_TIMEOUT_MS) as conn:
            remaining_edges = safe_max_edges + 1
            edge_ids_by_source: Dict[str, Set[str]] = {}
            node_ids_by_source: Dict[str, Set[str]] = {source_id: set(anchor_ids) for source_id, anchor_ids in grouped.items()}
            for source_id, anchor_ids in sorted(grouped.items()):
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
                          AND e.edge_type = 'CALLS'
                          AND e.status IN ('TRUSTED', 'DERIVED')
                          AND (e.from_node_id IN ({frontier_placeholders}) OR e.to_node_id IN ({frontier_placeholders}))
                        ORDER BY e.id
                        LIMIT ?
                        """,
                        [source_id, *frontier_list, *frontier_list, remaining_edges],
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
        unresolved = [
            edge
            for edge in edges
            if not edge.get("toNodeId") or str(edge.get("resolutionStatus") or "").upper() in {"UNRESOLVED", "DYNAMIC_TARGET", "MULTIPLE_CANDIDATES"}
        ]
        external = [node for node in nodes if node.get("nodeKind") == "EXTERNAL" or node.get("external")]
        verified_paths = self._verified_paths_from_evidence(evidence)
        return {"nodes": nodes, "edges": edges, "evidence": evidence, "unresolved": unresolved, "external": external, "verifiedPaths": verified_paths, "truncated": truncated}

    def _attach_current_graph_identity(self, conn: sqlite3.Connection, items: List[Dict[str, Any]]) -> None:
        source_ids = sorted({str(item.get("sourceId") or "") for item in items if item.get("sourceId")})
        if not source_ids:
            return
        identity_by_source = self._graph_identity_by_source(conn, source_ids)
        for item in items:
            identity = identity_by_source.get(str(item.get("sourceId") or ""))
            if not identity:
                continue
            item.setdefault("graphId", identity.get("graphId"))
            item.setdefault("graphRevision", identity.get("graphRevision"))

    def _query_slice_nodes(self, conn: sqlite3.Connection, source_id: str, node_ids: Set[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        ids = sorted(node_ids)
        placeholders = ",".join("?" for _ in ids)
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
             AND entry.claim_kind = 'ENTRYPOINT_HINT'
             AND entry.status IN ('TRUSTED', 'DERIVED')
            WHERE n.source_id = ?
              AND n.id IN ({placeholders})
            ORDER BY n.id
            """,
            [source_id, *ids],
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
            disjunctions.append(f"(e.from_node_id IN ({placeholders}) AND (e.to_node_id IS NULL OR e.resolution_status IN ('UNRESOLVED', 'DYNAMIC_TARGET')))")
            params.extend(ids)
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
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_edges edge
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = edge.source_id
                 AND ev.id = edge.evidence_id
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
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_claims claim
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = claim.source_id
                 AND EXISTS (
                    SELECT 1
                    FROM json_each(claim.evidence_ids_json)
                    WHERE value = ev.id
                 )
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
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain,
                       edge.id AS edge_id, NULL AS node_id
                FROM analysis_graph_edges edge
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = edge.source_id
                 AND ev.id = edge.evidence_id
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
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain,
                       NULL AS edge_id, claim.node_id AS node_id
                FROM analysis_graph_claims claim
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = claim.source_id
                 AND EXISTS (
                    SELECT 1
                    FROM json_each(claim.evidence_ids_json)
                    WHERE value = ev.id
                 )
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
                "evidenceKind": row["evidence_kind"],
                "excerptHash": row["excerpt_hash"],
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
                "metadata": self._json_dict(row["metadata_json"]),
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
                "evidenceKind": row["evidence_kind"],
                "excerptHash": row["excerpt_hash"],
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
                "metadata": self._json_dict(row["metadata_json"]),
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
                 AND entry.claim_kind = 'ENTRYPOINT_HINT'
                 AND entry.status IN ('TRUSTED', 'DERIVED')
                WHERE {where}
                ORDER BY n.id
                LIMIT ?
                """,
                [*params, safe_page_size + 1],
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
            row = conn.execute(
                """
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
                 AND entry.claim_kind = 'ENTRYPOINT_HINT'
                 AND entry.status IN ('TRUSTED', 'DERIVED')
                WHERE n.source_id = ?
                  AND n.id = ?
                """,
                (requested_source, node_id),
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
                detail["evidence"] = self._graph_evidence(conn, node_id=node_id)
            detail["relations"] = self._graph_node_relations(conn, node_id, requested_source)
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
        if query.include_external == "hide":
            clauses.append("n.node_kind != 'EXTERNAL'")
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
        if not query.include_unresolved:
            clauses.append("e.to_node_id IS NOT NULL")
            clauses.append("e.resolution_status NOT IN ('UNRESOLVED', 'DYNAMIC_TARGET', 'EXTERNAL_TARGET')")
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
                 AND entry.claim_kind = 'ENTRYPOINT_HINT'
                 AND entry.status IN ('TRUSTED', 'DERIVED')
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
            [*node_params, *edge_params, limit],
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
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_edges edge
                JOIN analysis_graph_evidence ev ON ev.source_id = edge.source_id AND ev.id = edge.evidence_id
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
                SELECT ev.id, ev.source_id, ev.analysis_file_id, af.relative_path, ev.line_start, ev.line_end,
                       ev.evidence_kind, ev.excerpt_hash, ev.metadata_json, ev.fact_origin, ev.flow_domain
                FROM analysis_graph_claims claim
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = claim.source_id
                 AND EXISTS (SELECT 1 FROM json_each(claim.evidence_ids_json) WHERE value = ev.id)
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

    def _graph_node_relations(self, conn: sqlite3.Connection, node_id: str, source_id: str, limit: int = GRAPH_NODE_DETAIL_RELATION_LIMIT) -> Dict[str, Any]:
        def rows_for(direction: str) -> List[sqlite3.Row]:
            column = "e.from_node_id" if direction == "outgoing" else "e.to_node_id"
            return conn.execute(
                f"""
                SELECT e.id, e.edge_type, e.from_node_id, e.to_node_id, e.confidence, e.evidence_id,
                       e.metadata_json, e.fact_origin, e.flow_domain, e.unresolved_target_json,
                       ev_af.relative_path AS evidence_relative_path,
                       ev.line_start AS evidence_line_start,
                       ev.line_end AS evidence_line_end,
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
                LEFT JOIN analysis_graph_evidence ev ON ev.source_id = e.source_id AND ev.id = e.evidence_id
                LEFT JOIN analysis_files ev_af ON ev_af.file_id = ev.analysis_file_id
                WHERE e.source_id = ?
                  AND {column} = ?
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
                return value.get("name") or value.get("qualifiedName") or value.get("displayName") or value.get("symbol")
            return str(value)

        def item_from(row: sqlite3.Row) -> Dict[str, Any]:
            metadata = self._json_dict(row["metadata_json"])
            line_start = row["evidence_line_start"] or metadata.get("lineStart") or metadata.get("callsiteLineStart") or metadata.get("sourceLineStart")
            line_end = row["evidence_line_end"] or metadata.get("lineEnd") or metadata.get("callsiteLineEnd") or metadata.get("sourceLineEnd") or line_start
            source_path = row["evidence_relative_path"] or metadata.get("relativePath") or metadata.get("sourcePath") or metadata.get("file")
            return {
                "edgeId": row["id"],
                "edgeKind": row["edge_type"],
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
                "evidenceCount": 1 if row["evidence_id"] else 0,
                "factOrigin": row["fact_origin"],
                "flowDomain": row["flow_domain"],
            }

        def group(rows: List[sqlite3.Row]) -> Dict[str, Any]:
            return {"totalCount": int(rows[0]["total_count"]) if rows else 0, "items": [item_from(row) for row in rows]}

        return {"incoming": group(rows_for("incoming")), "outgoing": group(rows_for("outgoing"))}

    def _claims_for_node_detail(self, conn: sqlite3.Connection, row: Dict[str, Any]) -> List[sqlite3.Row]:
        return conn.execute(
            """
            SELECT id, node_id, claim_kind, summary, confidence, status, rejection_reason, evidence_ids_json, metadata_json, fact_origin, flow_domain,
                   CASE WHEN node_id = ? THEN 1 ELSE 0 END AS selected_node_claim
            FROM analysis_graph_claims
            WHERE source_id = ?
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
            (row["id"], row["source_id"], row["id"], row.get("parent_node_id"), row.get("analysis_file_id")),
        ).fetchall()

    def _graph_node_projection(self, row: Dict[str, Any]) -> Dict[str, Any]:
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
            "metadata": {key: value for key, value in metadata.items() if key in {"callTargetCategory", "sliceDefaultVisibility", "sourceKind", "displayScore", "flowScore", "unresolvedReason"}},
        }

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
            "metadata": {key: value for key, value in metadata.items() if key in {"callKind", "callTargetCategory", "displayScore", "flowScore", "methodName", "receiverText", "receiverTypeHint", "sliceDefaultVisibility", "unresolvedReason"}},
        }

    def _fact_summary_from_claim_rows(self, claims: List[Dict[str, Any]], row: Dict[str, Any]) -> Dict[str, Any]:
        candidates = [claim for claim in claims if claim.get("claim_kind") == "RESPONSIBILITY" and claim.get("status") in {"TRUSTED", "LOW_CONFIDENCE"}]

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
                SET status = 'STALE',
                    indexed_node_count = 0,
                    embedding_model = NULL,
                    embedding_dimension = NULL,
                    updated_at = ?,
                    started_at = NULL
                WHERE source_id = ?
                """,
                (now, source_id),
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
            conn.execute(
                f"DELETE FROM analysis_graph_edges WHERE from_node_id IN ({placeholders}) OR to_node_id IN ({placeholders})",
                [*batch, *batch],
            )
            conn.execute(f"DELETE FROM analysis_graph_claims WHERE node_id IN ({placeholders})", batch)
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
            placeholders = ",".join("?" for _ in graph_node_ids)
            conn.execute(
                f"DELETE FROM analysis_graph_edges WHERE from_node_id IN ({placeholders}) OR to_node_id IN ({placeholders})",
                [*graph_node_ids, *graph_node_ids],
            )
            conn.execute(f"DELETE FROM analysis_graph_claims WHERE node_id IN ({placeholders})", graph_node_ids)
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
        rows = conn.execute(
            """
            SELECT id, metadata_json
            FROM analysis_graph_edges
            WHERE source_id = ?
              AND edge_type = 'CALLS'
              AND to_node_id IS NULL
              AND resolution_status IN ('UNRESOLVED', 'MULTIPLE_CANDIDATES')
        """,
            (source_id,),
        ).fetchall()
        if not rows:
            return
        type_rows = conn.execute(
            """
            SELECT id, name, qualified_name
            FROM analysis_graph_nodes
            WHERE source_id = ?
              AND node_kind = 'TYPE'
              AND status = 'TRUSTED'
        """,
            (source_id,),
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
                    self._mark_call_edge_multiple(conn, edge["id"], metadata, len(type_candidates))
                continue
            callable_candidates = self._callable_candidates_for_type(
                conn, type_candidates[0]["id"], str(method_name), metadata.get("argumentCount")
            )
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
                    WHERE id = ?
                """,
                    (callable_candidates[0]["id"], json.dumps(metadata), edge["id"]),
                )
            elif len(callable_candidates) > 1:
                self._mark_call_edge_multiple(conn, edge["id"], metadata, len(callable_candidates))

    def _callable_candidates_for_type(
        self, conn: sqlite3.Connection, type_node_id: str, method_name: str, argument_count: Optional[int]
    ) -> List[sqlite3.Row]:
        rows = conn.execute(
            """
            SELECT id, metadata_json
            FROM analysis_graph_nodes
            WHERE parent_node_id = ?
              AND node_kind = 'CALLABLE'
              AND name = ?
              AND status = 'TRUSTED'
        """,
            (type_node_id, method_name),
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

    def _mark_call_edge_multiple(self, conn: sqlite3.Connection, edge_id: str, metadata: Dict[str, Any], candidate_count: int) -> None:
        metadata["resolutionStatus"] = "MULTIPLE_CANDIDATES"
        metadata["candidateCount"] = candidate_count
        metadata["candidateKind"] = metadata.get("candidateKind") or "METHOD"
        metadata = classify_call_metadata(metadata, metadata.get("flowDomain"), None, "MULTIPLE_CANDIDATES", None)
        conn.execute(
            """
            UPDATE analysis_graph_edges
            SET resolution_status = 'MULTIPLE_CANDIDATES',
                metadata_json = ?
            WHERE id = ?
        """,
            (json.dumps(metadata), edge_id),
        )

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
        )

    def _insert_file(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute(
            """
            INSERT INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                symbol_count = ?,
                relation_count = ?,
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
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    def _drop_rejected_graph_storage(self, conn: sqlite3.Connection) -> None:
        required_columns = {
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
            },
            "analysis_graph_evidence": {
                "id",
                "source_id",
                "analysis_file_id",
                "file_id",
                "relative_path",
                "content_hash",
            },
            "analysis_graph_diagnostics": {"id", "source_id", "diagnostic_code", "message", "severity"},
            "analysis_graph_nodes": {
                "id",
                "source_id",
                "analysis_file_id",
                "file_id",
                "relative_path",
                "content_hash",
                "stable_key",
                "node_kind",
            },
        }
        fact_tables = (
            "analysis_graph_claims",
            "analysis_graph_edges",
            "analysis_graph_evidence",
            "analysis_graph_diagnostics",
            "analysis_graph_nodes",
        )
        table_names = self._table_names(conn)
        rejected_graph_tables = {table for table in table_names if table.startswith("graph_")}
        needs_reset = bool(rejected_graph_tables)
        if not needs_reset:
            needs_reset = any(
                self._table_exists(conn, table) and not required.issubset(self._table_columns(conn, table))
                for table, required in required_columns.items()
            )
        if not needs_reset:
            return
        reset_tables = {
            table
            for table in table_names
            if table.startswith("graph_") or table.startswith("analysis_graph_")
        }
        reset_tables.update({
            "semantic_vectors",
            "semantic_documents",
            "semantic_index_state",
        })
        self._drop_graph_lifecycle_triggers(conn, reset_tables)
        self._drop_tables_child_first(conn, {table for table in reset_tables if self._table_exists(conn, table)})
        if self._table_exists(conn, "analysis_files"):
            conn.execute("DELETE FROM analysis_files")
        if self._table_exists(conn, "analysis_job_files"):
            conn.execute(
                """
                UPDATE analysis_job_files
                SET analysis_file_id = NULL,
                    status = CASE WHEN status IN ('RUNNING', 'COMPLETED') THEN 'PENDING' ELSE status END,
                    updated_at = datetime('now')
                """
            )

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
            self._drop_rejected_graph_storage(conn)
            return
        if version == 7:
            self._drop_rejected_graph_storage(conn)
            return
        raise RuntimeError(f"Unknown analysis schema migration: {version}")

    def _create_analysis_graph_diagnostics_table(self, conn: sqlite3.Connection) -> None:
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
                diagnostic_code TEXT,
                message TEXT NOT NULL,
                candidate_id TEXT,
                line_start INTEGER,
                line_end INTEGER,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT
            )
        """)

    def _reconcile_graph_diagnostics_schema(self, conn: sqlite3.Connection) -> None:
        if not self._table_exists(conn, "analysis_graph_diagnostics"):
            self._create_analysis_graph_diagnostics_table(conn)
            return
        columns = {row["name"]: row for row in conn.execute("PRAGMA table_info(analysis_graph_diagnostics)").fetchall()}
        id_column = columns.get("id")
        id_type = str(id_column["type"] or "").upper() if id_column else ""
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
            "diagnostic_code",
            "message",
            "candidate_id",
            "line_start",
            "line_end",
            "metadata_json",
            "created_at",
            "fact_origin",
            "flow_domain",
        }
        if id_type == "TEXT" and set(columns) == required:
            return
        suffix = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S%f")
        old_table = f"analysis_graph_diagnostics_old_{suffix}"
        old_columns = set(columns)
        conn.execute(f"ALTER TABLE analysis_graph_diagnostics RENAME TO {old_table}")
        self._create_analysis_graph_diagnostics_table(conn)
        if "source_id" in old_columns and "code" in old_columns and "message" in old_columns:
            def expr(column: str, default: str = "NULL") -> str:
                return column if column in old_columns else default

            now = datetime.now(timezone.utc).isoformat()
            target_columns = [
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
                "diagnostic_code",
                "message",
                "candidate_id",
                "line_start",
                "line_end",
                "metadata_json",
                "created_at",
                "fact_origin",
                "flow_domain",
            ]
            selected = [
                "CAST(COALESCE(id, 'diagnostic:' || rowid) AS TEXT)" if "id" in old_columns else "'diagnostic:' || rowid",
                "COALESCE(job_id, 'legacy')" if "job_id" in old_columns else "'legacy'",
                "source_id",
                expr("inventory_file_id"),
                expr("analysis_file_id"),
                expr("file_id"),
                expr("relative_path"),
                expr("content_hash"),
                "COALESCE(severity, 'WARN')" if "severity" in old_columns else "'WARN'",
                "COALESCE(stage, 'ANALYSIS')" if "stage" in old_columns else "'ANALYSIS'",
                "code",
                "COALESCE(diagnostic_code, code)" if "diagnostic_code" in old_columns else "code",
                "message",
                expr("candidate_id"),
                expr("line_start"),
                expr("line_end"),
                "COALESCE(metadata_json, '{}')" if "metadata_json" in old_columns else "'{}'",
                "COALESCE(created_at, ?)" if "created_at" in old_columns else "?",
                expr("fact_origin"),
                expr("flow_domain"),
            ]
            conn.execute(
                f"""
                INSERT OR IGNORE INTO analysis_graph_diagnostics({", ".join(target_columns)})
                SELECT {", ".join(selected)}
                FROM {old_table}
                """,
                (now,),
            )
        conn.execute(f"DROP TABLE {old_table}")

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
