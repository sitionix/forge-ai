from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from knowledge_service.graph_schema import (
    GRAPH_ANALYSIS_ENGINE_VERSION,
    GraphClaimKind,
    GraphEdgeType,
    GraphFactOrigin,
    GraphFactStatus,
    GraphFlowDomain,
    GraphNodeKind,
    GraphResolutionStatus,
    classify_flow_domain,
)
from knowledge_service.source_catalog import SourceMetadata


ANALYSIS_SCHEMA_MIGRATIONS = (
    (1, "remove_legacy_analysis_job_counter"),
    (2, "add_analysis_job_source_scope"),
    (3, "reset_analysis_cache_for_graph_v1_cutover"),
)
PROJECTED_FACT_STATUSES = (GraphFactStatus.TRUSTED.value, GraphFactStatus.DERIVED.value)
PROJECTED_FACT_STATUS_SQL = ",".join(f"'{status}'" for status in PROJECTED_FACT_STATUSES)
RESOLUTION_NODE_KINDS = (
    GraphNodeKind.CALLABLE.value,
    GraphNodeKind.TYPE.value,
    GraphNodeKind.FIELD.value,
    GraphNodeKind.DATA.value,
    GraphNodeKind.CONFIG.value,
    GraphNodeKind.RESOURCE.value,
    GraphNodeKind.EXTERNAL.value,
    GraphNodeKind.UNKNOWN.value,
)
RESOLUTION_NODE_KIND_SQL = ",".join(f"'{kind}'" for kind in RESOLUTION_NODE_KINDS)


class AnalysisStore:
    def __init__(self, db_path: Path):
        self.db_path = db_path

    def init(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
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
                    engine_version TEXT,
                    last_progress_at TEXT,
                    symbol_count INTEGER NOT NULL,
                    relation_count INTEGER NOT NULL,
                    diagnostics_json TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_jobs", "current_source_id", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "current_relative_path", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "source_ids_json", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "last_progress_at", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "engine_version", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_files (
                    file_id INTEGER PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    analyzer_name TEXT NOT NULL,
                    analyzer_version TEXT NOT NULL,
                    engine_version TEXT,
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
                    fact_origin TEXT,
                    flow_domain TEXT,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_graph_nodes", "fact_origin", "TEXT")
            self._ensure_column(conn, "analysis_graph_nodes", "flow_domain", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_edges (
                    id TEXT PRIMARY KEY,
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
                    fact_origin TEXT,
                    flow_domain TEXT,
                    created_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_graph_edges", "fact_origin", "TEXT")
            self._ensure_column(conn, "analysis_graph_edges", "flow_domain", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_evidence (
                    id TEXT PRIMARY KEY,
                    job_id TEXT NOT NULL,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    content_hash TEXT NOT NULL,
                    line_start INTEGER NOT NULL,
                    line_end INTEGER NOT NULL,
                    excerpt_hash TEXT NOT NULL,
                    evidence_kind TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_graph_evidence", "fact_origin", "TEXT")
            self._ensure_column(conn, "analysis_graph_evidence", "flow_domain", "TEXT")
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
                    fact_origin TEXT,
                    flow_domain TEXT,
                    metadata_json TEXT NOT NULL,
                    rejection_reason TEXT,
                    created_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_graph_claims", "fact_origin", "TEXT")
            self._ensure_column(conn, "analysis_graph_claims", "flow_domain", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_diagnostics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_id TEXT,
                    source_id TEXT NOT NULL,
                    inventory_file_id INTEGER,
                    analysis_file_id INTEGER,
                    relative_path TEXT,
                    stage TEXT NOT NULL,
                    code TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    message TEXT NOT NULL,
                    candidate_id TEXT,
                    line_start INTEGER,
                    line_end INTEGER,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_graph_diagnostics", "fact_origin", "TEXT")
            self._ensure_column(conn, "analysis_graph_diagnostics", "flow_domain", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_graph_resolution_candidates (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    edge_id TEXT NOT NULL,
                    candidate_node_id TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    reason TEXT,
                    fact_origin TEXT,
                    flow_domain TEXT,
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_graph_resolution_candidates", "fact_origin", "TEXT")
            self._ensure_column(conn, "analysis_graph_resolution_candidates", "flow_domain", "TEXT")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_job_files_job_status ON analysis_job_files(job_id, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_job_files_source_domain ON analysis_job_files(source_id, flow_domain)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_status ON analysis_files(source_id, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_domain_origin ON analysis_graph_nodes(source_id, flow_domain, fact_origin)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_resolution ON analysis_graph_edges(source_id, resolution_status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_domain_origin ON analysis_graph_edges(source_id, flow_domain, fact_origin)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_diagnostics_group ON analysis_graph_diagnostics(source_id, inventory_file_id, stage, code)")
            self._drop_legacy_fact_tables(conn)
            self._run_schema_migrations(conn)

    def create_job(self, job: Dict[str, Any]) -> None:
        self.init()
        with self._connect() as conn:
            conn.execute("""
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, engine_version, last_progress_at, symbol_count, relation_count, diagnostics_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, self._job_params(job))

    def update_job(self, job_id: str, updates: Dict[str, Any]) -> None:
        current = self.job(job_id)
        if current is None:
            return
        current.update(updates)
        self.init()
        with self._connect() as conn:
            conn.execute("""
                UPDATE analysis_jobs
                SET status = ?, started_at = ?, completed_at = ?, source_count = ?, file_count = ?, processed_file_count = ?,
                    failed_file_count = ?, current_source_id = ?, current_relative_path = ?, source_ids_json = ?, engine_version = ?, last_progress_at = ?,
                    symbol_count = ?, relation_count = ?, diagnostics_json = ?
                WHERE job_id = ?
            """, (*self._job_params(current)[1:], job_id))

    def create_job_files(self, job_id: str, rows: List[sqlite3.Row], status: str = "PENDING") -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            for row in rows:
                flow_domain = self._flow_domain(row)
                conn.execute("""
                    INSERT OR REPLACE INTO analysis_job_files(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, extension,
                        content_hash, line_count, decode_policy, flow_domain, status, attempt_count,
                        started_at, completed_at, diagnostics_json, engine_version, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    self._job_file_id(job_id, row["id"]),
                    job_id,
                    row["source_id"],
                    row["id"],
                    row["id"],
                    row["relative_path"],
                    row["extension"],
                    row["content_hash"],
                    row["line_count"],
                    row["decode_policy"],
                    flow_domain,
                    status,
                    0,
                    None,
                    None,
                    json.dumps([]),
                    GRAPH_ANALYSIS_ENGINE_VERSION,
                    now,
                    now,
                ))

    def start_job_file(self, job_id: str, row: sqlite3.Row) -> None:
        self.update_job_file(job_id, row["id"], {"status": "RUNNING", "started_at": self._now_iso()})

    def update_job_file(self, job_id: str, inventory_file_id: int, updates: Dict[str, Any]) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            current = conn.execute("SELECT * FROM analysis_job_files WHERE id = ?", (self._job_file_id(job_id, inventory_file_id),)).fetchone()
            if current is None:
                return
            values = {
                "status": updates.get("status", current["status"]),
                "attempt_count": updates.get("attempt_count", current["attempt_count"]),
                "started_at": updates.get("started_at", current["started_at"]),
                "completed_at": updates.get("completed_at", current["completed_at"]),
                "diagnostics_json": json.dumps(updates.get("diagnostics", json.loads(current["diagnostics_json"] or "[]"))),
                "updated_at": now,
            }
            conn.execute("""
                UPDATE analysis_job_files
                SET status = ?, attempt_count = ?, started_at = ?, completed_at = ?, diagnostics_json = ?, updated_at = ?
                WHERE id = ?
            """, (
                values["status"],
                values["attempt_count"],
                values["started_at"],
                values["completed_at"],
                values["diagnostics_json"],
                values["updated_at"],
                self._job_file_id(job_id, inventory_file_id),
            ))

    def job_files(self, job_id: str, status: Optional[str] = None) -> List[Dict[str, Any]]:
        clauses, params = ["job_id = ?"], [job_id]
        if status:
            clauses.append("status = ?")
            params.append(status)
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM analysis_job_files WHERE {' AND '.join(clauses)} ORDER BY relative_path",
                params,
            ).fetchall()
        return [self._job_file(row) for row in rows]

    def stop_pending_job_files(self, job_id: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            conn.execute("""
                UPDATE analysis_job_files
                SET status = 'STOPPED', completed_at = ?, updated_at = ?
                WHERE job_id = ? AND status IN ('PENDING', 'RUNNING')
            """, (now, now, job_id))

    def job(self, job_id: str) -> Optional[Dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE job_id = ?", (job_id,)).fetchone()
        return self._job(row) if row else None

    def active_job(self) -> Optional[Dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            row = conn.execute(
                "SELECT * FROM analysis_jobs WHERE engine_version = ? AND status IN ('QUEUED', 'RUNNING') ORDER BY started_at DESC LIMIT 1",
                (GRAPH_ANALYSIS_ENGINE_VERSION,),
            ).fetchone()
        return self._job(row) if row else None

    def request_stop(self, job_id: str) -> Optional[Dict[str, Any]]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE job_id = ?", (job_id,)).fetchone()
            if row is None:
                return None
            job = self._job(row)
            if job["status"] not in {"QUEUED", "RUNNING", "STOP_REQUESTED"}:
                return job
            diagnostics = job.get("diagnostics") or []
            if not any(item.get("code") == "ANALYSIS_JOB_STOP_REQUESTED" for item in diagnostics):
                diagnostics.append({
                    "code": "ANALYSIS_JOB_STOP_REQUESTED",
                    "message": "Analysis stop was requested by the operator.",
                })
            job.update({
                "status": "STOP_REQUESTED",
                "completedAt": now,
                "currentSourceId": None,
                "currentRelativePath": None,
                "lastProgressAt": now,
                "diagnostics": diagnostics[-20:],
            })
            conn.execute("""
                UPDATE analysis_jobs
                SET status = ?, completed_at = ?, current_source_id = NULL, current_relative_path = NULL,
                    last_progress_at = ?, diagnostics_json = ?
                WHERE job_id = ?
            """, (job["status"], job["completedAt"], job["lastProgressAt"], json.dumps(job["diagnostics"]), job_id))
            return job

    def stop_requested(self, job_id: str) -> bool:
        job = self.job(job_id)
        return job is not None and job["status"] in {"STOP_REQUESTED", "STOPPED"}

    def mark_interrupted_jobs(self) -> None:
        self.init()
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT * FROM analysis_jobs WHERE engine_version = ? AND status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')",
                (GRAPH_ANALYSIS_ENGINE_VERSION,),
            ).fetchall()
            for row in rows:
                diagnostics = json.loads(row["diagnostics_json"] or "[]")
                diagnostics.append({
                    "code": "ANALYSIS_JOB_INTERRUPTED",
                    "message": "Analysis job was interrupted by Knowledge service restart.",
                })
                status = "STOPPED" if row["status"] == "STOP_REQUESTED" else "FAILED"
                conn.execute("""
                    UPDATE analysis_jobs
                    SET status = ?,
                        completed_at = COALESCE(completed_at, datetime('now')),
                        current_source_id = NULL,
                        current_relative_path = NULL,
                        diagnostics_json = ?
                    WHERE job_id = ?
                """, (status, json.dumps(diagnostics[-20:]), row["job_id"]))
                conn.execute("""
                    UPDATE analysis_job_files
                    SET status = ?, completed_at = COALESCE(completed_at, datetime('now')), updated_at = datetime('now')
                    WHERE job_id = ? AND status IN ('PENDING', 'RUNNING')
                """, (status, row["job_id"]))

    def status(self) -> Dict[str, Any]:
        self.init()
        active = self.active_job()
        with self._connect() as conn:
            latest = conn.execute(
                "SELECT * FROM analysis_jobs WHERE engine_version = ? AND status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1",
                (GRAPH_ANALYSIS_ENGINE_VERSION,),
            ).fetchone()
            counts = conn.execute(f"""
                SELECT COUNT(*) AS symbols
                FROM analysis_graph_nodes n
                JOIN analysis_files af
                  ON af.file_id = n.inventory_file_id
                 AND af.source_id = n.source_id
                 AND af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND af.status = 'ANALYZED'
                JOIN files f
                  ON f.id = n.inventory_file_id
                 AND f.source_id = n.source_id
                 AND f.relative_path = af.relative_path
                 AND f.content_hash = af.content_hash
                WHERE n.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND n.node_kind != ?
                  AND n.inventory_file_id IS NOT NULL
            """, (GraphNodeKind.FILE.value,)).fetchone()
            relations = conn.execute(f"""
                SELECT COUNT(*) AS relations
                FROM analysis_graph_edges e
                JOIN analysis_graph_nodes from_node ON from_node.id = e.from_node_id AND from_node.source_id = e.source_id
                JOIN analysis_graph_nodes to_node ON to_node.id = e.to_node_id AND to_node.source_id = e.source_id
                JOIN analysis_files from_af
                  ON from_af.file_id = from_node.inventory_file_id
                 AND from_af.source_id = from_node.source_id
                 AND from_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND from_af.status = 'ANALYZED'
                JOIN files from_file
                  ON from_file.id = from_node.inventory_file_id
                 AND from_file.source_id = from_node.source_id
                 AND from_file.relative_path = from_af.relative_path
                 AND from_file.content_hash = from_af.content_hash
                JOIN analysis_files to_af
                  ON to_af.file_id = to_node.inventory_file_id
                 AND to_af.source_id = to_node.source_id
                 AND to_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND to_af.status = 'ANALYZED'
                JOIN files to_file
                  ON to_file.id = to_node.inventory_file_id
                 AND to_file.source_id = to_node.source_id
                 AND to_file.relative_path = to_af.relative_path
                 AND to_file.content_hash = to_af.content_hash
                WHERE e.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND e.to_node_id IS NOT NULL
                  AND from_node.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND to_node.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND from_node.node_kind != ?
                  AND to_node.node_kind != ?
            """, (GraphNodeKind.FILE.value, GraphNodeKind.FILE.value)).fetchone()
        if not latest and not active:
            return {"status": "EMPTY", "latestJobId": None, "activeJob": None, "symbolCount": 0, "relationCount": 0}
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
        }

    def service_status(
        self,
        catalog_sources: Optional[List[SourceMetadata]],
        analyzer_name: str,
        analyzer_version: str,
        inventory_status: Dict[str, Any],
        details_source_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        self.init()
        active = self.active_job()
        active_source = active.get("currentSourceId") if active else None
        diagnostics_by_source = self._service_diagnostics(active)
        with self._connect() as conn:
            stats_rows = conn.execute("""
                SELECT
                    s.source_id,
                    s.display_name,
                    s.group_name,
                    s.path,
                    s.root_exists,
                    s.tags_json,
                    COUNT(f.id) AS inventory_file_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
                          AND af.engine_version = ?
                          AND af.status = 'ANALYZED'
                    ) THEN 1 ELSE 0 END) AS analyzed_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
                          AND af.engine_version = ?
                          AND af.status = 'FAILED'
                    ) THEN 1 ELSE 0 END) AS failed_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
                          AND af.engine_version = ?
                          AND af.status = 'SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS'
                    ) THEN 1 ELSE 0 END) AS skipped_too_large_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.engine_version = ?
                          AND (
                              af.content_hash != f.content_hash
                              OR af.analyzer_name != ?
                              OR af.analyzer_version != ?
                          )
                    ) THEN 1 ELSE 0 END) AS stale_count
                FROM sources s
                LEFT JOIN files f ON f.source_id = s.source_id
                GROUP BY s.source_id, s.display_name, s.group_name, s.path, s.root_exists, s.tags_json
            """, (
                analyzer_name, analyzer_version, GRAPH_ANALYSIS_ENGINE_VERSION,
                analyzer_name, analyzer_version, GRAPH_ANALYSIS_ENGINE_VERSION,
                analyzer_name, analyzer_version, GRAPH_ANALYSIS_ENGINE_VERSION,
                GRAPH_ANALYSIS_ENGINE_VERSION, analyzer_name, analyzer_version,
            )).fetchall()
            symbol_rows = conn.execute(f"""
                SELECT n.source_id, COUNT(*) AS count
                FROM analysis_graph_nodes n
                JOIN analysis_files af
                  ON af.file_id = n.inventory_file_id
                 AND af.source_id = n.source_id
                 AND af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND af.status = 'ANALYZED'
                JOIN files f
                  ON f.id = n.inventory_file_id
                 AND f.source_id = n.source_id
                 AND f.relative_path = af.relative_path
                 AND f.content_hash = af.content_hash
                WHERE n.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND n.node_kind != ?
                  AND n.inventory_file_id IS NOT NULL
                GROUP BY n.source_id
            """, (GraphNodeKind.FILE.value,)).fetchall()
            relation_rows = conn.execute(f"""
                SELECT e.source_id, COUNT(*) AS count
                FROM analysis_graph_edges e
                JOIN analysis_graph_nodes from_node ON from_node.id = e.from_node_id AND from_node.source_id = e.source_id
                JOIN analysis_graph_nodes to_node ON to_node.id = e.to_node_id AND to_node.source_id = e.source_id
                JOIN analysis_files from_af
                  ON from_af.file_id = from_node.inventory_file_id
                 AND from_af.source_id = from_node.source_id
                 AND from_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND from_af.status = 'ANALYZED'
                JOIN files from_file
                  ON from_file.id = from_node.inventory_file_id
                 AND from_file.source_id = from_node.source_id
                 AND from_file.relative_path = from_af.relative_path
                 AND from_file.content_hash = from_af.content_hash
                JOIN analysis_files to_af
                  ON to_af.file_id = to_node.inventory_file_id
                 AND to_af.source_id = to_node.source_id
                 AND to_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND to_af.status = 'ANALYZED'
                JOIN files to_file
                  ON to_file.id = to_node.inventory_file_id
                 AND to_file.source_id = to_node.source_id
                 AND to_file.relative_path = to_af.relative_path
                 AND to_file.content_hash = to_af.content_hash
                WHERE e.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND e.to_node_id IS NOT NULL
                  AND from_node.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND to_node.status IN ({PROJECTED_FACT_STATUS_SQL})
                  AND from_node.node_kind != ?
                  AND to_node.node_kind != ?
                GROUP BY e.source_id
            """, (GraphNodeKind.FILE.value, GraphNodeKind.FILE.value)).fetchall()
            inventory_state = self._inventory_source_state(conn)
        stats_by_source = {row["source_id"]: row for row in stats_rows}
        symbols_by_source = {row["source_id"]: row["count"] or 0 for row in symbol_rows}
        relations_by_source = {row["source_id"]: row["count"] or 0 for row in relation_rows}
        source_ids = self._service_source_ids(catalog_sources, stats_by_source)
        services: List[Dict[str, Any]] = []
        for source_id in source_ids:
            catalog = self._catalog_source(catalog_sources, source_id)
            row = stats_by_source.get(source_id)
            root_exists = catalog.rootExists if catalog is not None else bool(row["root_exists"]) if row is not None else False
            label = catalog.displayName if catalog is not None else row["display_name"] if row is not None else source_id
            group = catalog.group if catalog is not None else row["group_name"] if row is not None else None
            path = catalog.path if catalog is not None else row["path"] if row is not None else None
            tags = catalog.tags if catalog is not None else json.loads(row["tags_json"] or "[]") if row is not None else []
            inventory_count = int(row["inventory_file_count"] or 0) if row is not None else 0
            analyzed = int(row["analyzed_count"] or 0) if row is not None else 0
            failed = int(row["failed_count"] or 0) if row is not None else 0
            skipped_too_large = int(row["skipped_too_large_count"] or 0) if row is not None else 0
            stale = int(row["stale_count"] or 0) if row is not None else 0
            source_inventory = inventory_state.get(source_id, {})
            skipped_count = source_inventory.get("skippedCount")
            skipped_breakdown = source_inventory.get("skippedBreakdown")
            is_running = active is not None and active_source == source_id
            completed_outcomes = analyzed + failed + skipped_too_large
            active_processed = int(active.get("processedFileCount") or 0) if is_running else 0
            processed = max(completed_outcomes, active_processed)
            pending = max(inventory_count - processed, 0)
            percent = round((processed / inventory_count) * 100, 1) if inventory_count else 0.0
            service = {
                "sourceId": source_id,
                "label": label,
                "displayName": label,
                "group": group,
                "path": path,
                "rootExists": root_exists,
                "tags": tags,
                "inventory": {
                    "status": self._inventory_service_status(root_exists, inventory_count, source_inventory, inventory_status),
                    "eligibleFileCount": inventory_count,
                    "skippedCount": skipped_count,
                    "skippedBreakdown": skipped_breakdown,
                    "lastInventoryAt": source_inventory.get("lastInventoryAt"),
                },
                "analysis": {
                    "status": self._analysis_service_status(is_running, inventory_count, analyzed, failed, pending, stale),
                    "inventoryFileCount": inventory_count,
                    "analyzedFileCount": analyzed,
                    "percent": percent,
                    "processedFileCount": processed,
                    "failedFileCount": failed,
                    "pendingFileCount": pending,
                    "staleFileCount": stale,
                    "skippedTooLargeFileCount": skipped_too_large,
                    "currentRelativePath": active.get("currentRelativePath") if is_running else None,
                    "lastProgressAt": active.get("lastProgressAt") if is_running else None,
                    "activeJobId": active.get("jobId") if is_running else None,
                },
                "facts": {
                    "symbolCount": symbols_by_source.get(source_id, 0),
                    "relationCount": relations_by_source.get(source_id, 0),
                },
                "diagnostics": diagnostics_by_source.get(source_id, []),
            }
            if details_source_id and source_id == details_source_id:
                service["details"] = self.service_details(source_id)
            services.append(service)
        return {"services": services, "activeJob": active}

    def service_details(self, source_id: str) -> Dict[str, Any]:
        return {
            "symbols": self.symbols(source_id, None, None, None, None, 20, 0),
            "relations": self.relations(source_id, None, None, None, 20, 0),
            "failures": self.files(source_id, "FAILED", None, 10, 0),
        }

    def _service_diagnostics(self, active: Optional[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
        with self._connect() as conn:
            rows = conn.execute("""
                SELECT source_id, diagnostics_json
                FROM analysis_files
                WHERE diagnostics_json IS NOT NULL AND diagnostics_json != '[]'
                  AND engine_version = ?
            """, (GRAPH_ANALYSIS_ENGINE_VERSION,)).fetchall()
        return self._group_diagnostics_by_source(rows, active)

    def _inventory_source_state(self, conn: sqlite3.Connection) -> Dict[str, Dict[str, Any]]:
        try:
            rows = conn.execute("SELECT * FROM inventory_source_state").fetchall()
        except sqlite3.OperationalError:
            return {}
        result: Dict[str, Dict[str, Any]] = {}
        for row in rows:
            skipped_breakdown = None
            if row["skipped_reasons_json"]:
                try:
                    skipped_breakdown = json.loads(row["skipped_reasons_json"])
                except json.JSONDecodeError:
                    skipped_breakdown = None
            result[row["source_id"]] = {
                "eligibleFileCount": row["eligible_file_count"],
                "skippedCount": row["skipped_count"],
                "skippedBreakdown": skipped_breakdown,
                "lastInventoryAt": row["last_inventory_at"],
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

    def _inventory_service_status(self, root_exists: bool, inventory_count: int, source_inventory: Dict[str, Any], inventory_status: Dict[str, Any]) -> str:
        if not root_exists:
            return "MISSING_ROOT"
        if inventory_count > 0 or source_inventory:
            return "READY"
        return inventory_status.get("status") or "EMPTY"

    def _analysis_service_status(self, is_running: bool, inventory_count: int, analyzed: int, failed: int, pending: int, stale: int) -> str:
        if is_running:
            return "RUNNING"
        if inventory_count <= 0:
            return "NOT_ANALYZED"
        if stale > 0:
            return "OUTDATED"
        if analyzed == 0 and failed == 0:
            return "NOT_ANALYZED"
        if pending == 0 and failed == 0:
            return "COMPLETED"
        return "PARTIAL"

    def _group_diagnostics_by_source(self, rows, active: Optional[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
        grouped: Dict[str, Dict[str, Dict[str, Any]]] = {}
        for row in rows:
            source_id = row["source_id"]
            self._collect_diagnostics(grouped, source_id, json.loads(row["diagnostics_json"] or "[]"))
        if active:
            for diagnostic in active.get("diagnostics") or []:
                source_id = diagnostic.get("sourceId")
                if source_id:
                    self._collect_diagnostics(grouped, source_id, [diagnostic])
        return {
            source_id: sorted(items.values(), key=lambda item: item["count"], reverse=True)
            for source_id, items in grouped.items()
        }

    def _collect_diagnostics(self, grouped: Dict[str, Dict[str, Dict[str, Any]]], source_id: str, diagnostics: List[Dict[str, Any]]) -> None:
        bucket = grouped.setdefault(source_id, {})
        for diagnostic in diagnostics:
            code = diagnostic.get("code") or "DIAGNOSTIC"
            stage = diagnostic.get("stage")
            key = f"{stage or ''}:{code}"
            item = bucket.setdefault(key, {
                "code": code,
                "stage": stage,
                "severity": diagnostic.get("severity"),
                "message": diagnostic.get("message") or "-",
                "count": 0,
                "examples": [],
            })
            item["count"] += 1
            relative_path = diagnostic.get("relativePath")
            if relative_path and len(item["examples"]) < 10:
                item["examples"].append(relative_path)

    def replace_file_graph_analysis(self, file_id: int, state: Dict[str, Any], graph: Dict[str, List[Dict[str, Any]]]) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            self._delete_file_analysis(conn, file_id)
            for evidence in graph.get("evidence") or []:
                conn.execute("""
                    INSERT OR REPLACE INTO analysis_graph_evidence(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, content_hash, line_start, line_end,
                        excerpt_hash, evidence_kind, fact_origin, flow_domain, metadata_json, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    evidence["id"], evidence["job_id"], evidence["source_id"], evidence.get("inventory_file_id"), evidence.get("analysis_file_id"),
                    evidence["content_hash"], evidence["line_start"], evidence["line_end"], evidence["excerpt_hash"],
                    evidence["evidence_kind"], evidence.get("fact_origin") or GraphFactOrigin.UNKNOWN.value,
                    evidence.get("flow_domain") or state.get("flow_domain") or GraphFlowDomain.UNKNOWN.value,
                    json.dumps(evidence.get("metadata") or {}), now,
                ))
            for node in graph.get("nodes") or []:
                conn.execute("""
                    INSERT OR REPLACE INTO analysis_graph_nodes(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language,
                        name, qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status,
                        fact_origin, flow_domain, metadata_json, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    node["id"], node["job_id"], node["source_id"], node.get("inventory_file_id"), node.get("analysis_file_id"),
                    node["stable_key"], node["node_kind"], node.get("language"), node["name"], node.get("qualified_name"),
                    node.get("display_name"), node.get("parent_node_id"), node.get("line_start"), node.get("line_end"),
                    node["confidence"], node["status"], node.get("fact_origin") or GraphFactOrigin.UNKNOWN.value,
                    node.get("flow_domain") or state.get("flow_domain") or GraphFlowDomain.UNKNOWN.value,
                    json.dumps(node.get("metadata") or {}), now,
                ))
            for edge in graph.get("edges") or []:
                conn.execute("""
                    INSERT OR REPLACE INTO analysis_graph_edges(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                        resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                        fact_origin, flow_domain, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    edge["id"], edge["job_id"], edge["source_id"], edge.get("inventory_file_id"), edge.get("analysis_file_id"),
                    edge["from_node_id"], edge.get("to_node_id"), edge["edge_type"], edge["resolution_status"],
                    edge["confidence"], edge.get("evidence_id"), json.dumps(edge.get("unresolved_target")) if edge.get("unresolved_target") is not None else None,
                    json.dumps(edge.get("metadata") or {}), edge["status"], edge.get("fact_origin") or GraphFactOrigin.UNKNOWN.value,
                    edge.get("flow_domain") or state.get("flow_domain") or GraphFlowDomain.UNKNOWN.value, now,
                ))
            for claim in graph.get("claims") or []:
                conn.execute("""
                    INSERT OR REPLACE INTO analysis_graph_claims(
                        id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                        evidence_ids_json, fact_origin, flow_domain, metadata_json, rejection_reason, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    claim["id"], claim["job_id"], claim["source_id"], claim["node_id"], claim["claim_kind"],
                    claim["summary"], claim["confidence"], claim["status"], json.dumps(claim.get("evidence_ids") or []),
                    claim.get("fact_origin") or GraphFactOrigin.UNKNOWN.value,
                    claim.get("flow_domain") or state.get("flow_domain") or GraphFlowDomain.UNKNOWN.value,
                    json.dumps(claim.get("metadata") or {}), claim.get("rejection_reason"), now,
                ))
            for diagnostic in graph.get("diagnostics") or []:
                conn.execute("""
                    INSERT INTO analysis_graph_diagnostics(
                        job_id, source_id, inventory_file_id, analysis_file_id, relative_path, stage, code, severity,
                        message, candidate_id, line_start, line_end, fact_origin, flow_domain, metadata_json, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    diagnostic.get("jobId") or state.get("job_id"),
                    diagnostic.get("sourceId") or state["source_id"],
                    diagnostic.get("inventoryFileId") or file_id,
                    diagnostic.get("analysisFileId") or file_id,
                    diagnostic.get("relativePath") or state["relative_path"],
                    diagnostic.get("stage") or "CANDIDATE_VALIDATE",
                    diagnostic.get("code") or "ANALYSIS_GRAPH_DIAGNOSTIC",
                    diagnostic.get("severity") or "WARN",
                    diagnostic.get("message") or "Graph analysis diagnostic.",
                    diagnostic.get("candidateId"),
                    diagnostic.get("lineStart"),
                    diagnostic.get("lineEnd"),
                    diagnostic.get("factOrigin") or GraphFactOrigin.UNKNOWN.value,
                    diagnostic.get("flowDomain") or state.get("flow_domain") or GraphFlowDomain.UNKNOWN.value,
                    json.dumps(diagnostic.get("metadata") or {}),
                    now,
                ))
            self._upsert_file(conn, file_id, state)

    def mark_file(self, file_id: int, state: Dict[str, Any]) -> None:
        self.init()
        with self._connect() as conn:
            self._delete_file_analysis(conn, file_id)
            self._upsert_file(conn, file_id, state)

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
            rows = conn.execute(f"""
                SELECT af.file_id FROM analysis_files af
                LEFT JOIN files f ON f.id = af.file_id
                WHERE {where}
            """, params).fetchall()
            self._reattach_current_analysis_files(conn, source_ids)
            for row in rows:
                if conn.execute("SELECT 1 FROM analysis_files WHERE file_id = ?", (row["file_id"],)).fetchone() is None:
                    continue
                self._delete_file_analysis(conn, row["file_id"])
                conn.execute("DELETE FROM analysis_files WHERE file_id = ?", (row["file_id"],))

    def files(self, source_id: Optional[str], status: Optional[str], path_contains: Optional[str], limit: int, offset: int) -> Dict[str, Any]:
        clauses, params = ["engine_version = ?"], [GRAPH_ANALYSIS_ENGINE_VERSION]
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

    def symbols(
        self,
        source_id: Optional[str],
        role: Optional[str],
        kind: Optional[str],
        path_contains: Optional[str],
        name_contains: Optional[str],
        limit: int,
        offset: int,
        flow_domain: Optional[str] = None,
        fact_origin: Optional[str] = None,
    ) -> Dict[str, Any]:
        clauses, params = [
            f"n.status IN ({PROJECTED_FACT_STATUS_SQL})",
            "n.node_kind != ?",
            "n.inventory_file_id IS NOT NULL",
        ], []
        params.append(GraphNodeKind.FILE.value)
        if source_id:
            clauses.append("n.source_id = ?")
            params.append(source_id)
        if role:
            clauses.append("""
                EXISTS (
                    SELECT 1
                    FROM analysis_graph_claims rc
                    WHERE rc.node_id = n.id
                      AND rc.claim_kind = ?
                      AND rc.status IN (%s)
                      AND (
                          rc.summary = ?
                          OR rc.metadata_json LIKE ?
                          OR rc.metadata_json LIKE ?
                      )
                )
            """ % PROJECTED_FACT_STATUS_SQL)
            params.extend([
                GraphClaimKind.ROLE.value,
                role,
                f'%"role": "{role}"%',
                f'%"role":"{role}"%',
            ])
        if kind:
            clauses.append("n.node_kind = ?")
            params.append(kind)
        if path_contains:
            clauses.append("af.relative_path LIKE ?")
            params.append(f"%{path_contains}%")
        if name_contains:
            clauses.append("n.name LIKE ?")
            params.append(f"%{name_contains}%")
        if flow_domain:
            clauses.append("COALESCE(n.flow_domain, ?) = ?")
            params.extend([GraphFlowDomain.UNKNOWN.value, flow_domain])
        if fact_origin:
            clauses.append("COALESCE(n.fact_origin, ?) = ?")
            params.extend([GraphFactOrigin.UNKNOWN.value, fact_origin])
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"""
                SELECT COUNT(*) AS count
                FROM analysis_graph_nodes n
                JOIN analysis_files af
                  ON af.file_id = n.inventory_file_id
                 AND af.source_id = n.source_id
                 AND af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND af.status = 'ANALYZED'
                JOIN files f
                  ON f.id = n.inventory_file_id
                 AND f.source_id = n.source_id
                 AND f.relative_path = af.relative_path
                 AND f.content_hash = af.content_hash
                {where}
            """, params).fetchone()["count"]
            rows = conn.execute(f"""
                SELECT n.*, af.relative_path AS projected_relative_path
                FROM analysis_graph_nodes n
                JOIN analysis_files af
                  ON af.file_id = n.inventory_file_id
                 AND af.source_id = n.source_id
                 AND af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND af.status = 'ANALYZED'
                JOIN files f
                  ON f.id = n.inventory_file_id
                 AND f.source_id = n.source_id
                 AND f.relative_path = af.relative_path
                 AND f.content_hash = af.content_hash
                {where}
                ORDER BY n.source_id, projected_relative_path, n.line_start
                LIMIT ? OFFSET ?
            """, [*params, limit, offset]).fetchall()
            node_ids = [row["id"] for row in rows]
            claims = self._claims_by_node(conn, node_ids)
            evidence = self._evidence_by_id(conn, self._claim_evidence_ids(claims))
        return {"symbols": [self._graph_symbol(row, claims.get(row["id"], []), evidence) for row in rows], "total": total, "limit": limit, "offset": offset}

    def relations(
        self,
        source_id: Optional[str],
        relation: Optional[str],
        from_symbol_id: Optional[str],
        to_symbol_id: Optional[str],
        limit: int,
        offset: int,
        flow_domain: Optional[str] = None,
        fact_origin: Optional[str] = None,
    ) -> Dict[str, Any]:
        clauses, params = [
            f"e.status IN ({PROJECTED_FACT_STATUS_SQL})",
            "e.to_node_id IS NOT NULL",
            f"from_node.status IN ({PROJECTED_FACT_STATUS_SQL})",
            f"to_node.status IN ({PROJECTED_FACT_STATUS_SQL})",
            "from_node.node_kind != ?",
            "to_node.node_kind != ?",
        ], []
        params.extend([GraphNodeKind.FILE.value, GraphNodeKind.FILE.value])
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        if relation:
            clauses.append("e.edge_type = ?")
            params.append(relation)
        if from_symbol_id:
            clauses.append("e.from_node_id = ?")
            params.append(from_symbol_id)
        if to_symbol_id:
            clauses.append("e.to_node_id = ?")
            params.append(to_symbol_id)
        if flow_domain:
            clauses.append("COALESCE(e.flow_domain, ?) = ?")
            params.extend([GraphFlowDomain.UNKNOWN.value, flow_domain])
        if fact_origin:
            clauses.append("COALESCE(e.fact_origin, ?) = ?")
            params.extend([GraphFactOrigin.UNKNOWN.value, fact_origin])
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"""
                SELECT COUNT(*) AS count
                FROM analysis_graph_edges e
                JOIN analysis_graph_nodes from_node ON from_node.id = e.from_node_id AND from_node.source_id = e.source_id
                JOIN analysis_graph_nodes to_node ON to_node.id = e.to_node_id AND to_node.source_id = e.source_id
                JOIN analysis_files from_af
                  ON from_af.file_id = from_node.inventory_file_id
                 AND from_af.source_id = from_node.source_id
                 AND from_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND from_af.status = 'ANALYZED'
                JOIN files from_file
                  ON from_file.id = from_node.inventory_file_id
                 AND from_file.source_id = from_node.source_id
                 AND from_file.relative_path = from_af.relative_path
                 AND from_file.content_hash = from_af.content_hash
                JOIN analysis_files to_af
                  ON to_af.file_id = to_node.inventory_file_id
                 AND to_af.source_id = to_node.source_id
                 AND to_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND to_af.status = 'ANALYZED'
                JOIN files to_file
                  ON to_file.id = to_node.inventory_file_id
                 AND to_file.source_id = to_node.source_id
                 AND to_file.relative_path = to_af.relative_path
                 AND to_file.content_hash = to_af.content_hash
                {where}
            """, params).fetchone()["count"]
            rows = conn.execute(f"""
                SELECT e.*, fe.line_start AS evidence_line_start, fe.line_end AS evidence_line_end
                FROM analysis_graph_edges e
                JOIN analysis_graph_nodes from_node ON from_node.id = e.from_node_id AND from_node.source_id = e.source_id
                JOIN analysis_graph_nodes to_node ON to_node.id = e.to_node_id AND to_node.source_id = e.source_id
                JOIN analysis_files from_af
                  ON from_af.file_id = from_node.inventory_file_id
                 AND from_af.source_id = from_node.source_id
                 AND from_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND from_af.status = 'ANALYZED'
                JOIN files from_file
                  ON from_file.id = from_node.inventory_file_id
                 AND from_file.source_id = from_node.source_id
                 AND from_file.relative_path = from_af.relative_path
                 AND from_file.content_hash = from_af.content_hash
                JOIN analysis_files to_af
                  ON to_af.file_id = to_node.inventory_file_id
                 AND to_af.source_id = to_node.source_id
                 AND to_af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
                 AND to_af.status = 'ANALYZED'
                JOIN files to_file
                  ON to_file.id = to_node.inventory_file_id
                 AND to_file.source_id = to_node.source_id
                 AND to_file.relative_path = to_af.relative_path
                 AND to_file.content_hash = to_af.content_hash
                LEFT JOIN analysis_graph_evidence fe ON fe.id = e.evidence_id
                {where}
                ORDER BY e.source_id, e.edge_type, COALESCE(fe.line_start, 1)
                LIMIT ? OFFSET ?
            """, [*params, limit, offset]).fetchall()
        return {"relations": [self._graph_relation(row) for row in rows], "total": total, "limit": limit, "offset": offset}

    def resolve_graph_for_sources(self, source_ids: Optional[List[str]] = None) -> None:
        self.init()
        clauses = [f"e.status IN ({PROJECTED_FACT_STATUS_SQL})", "e.to_node_id IS NULL"]
        params: List[Any] = []
        if source_ids:
            clauses.append("e.source_id IN (%s)" % ",".join("?" for _ in source_ids))
            params.extend(source_ids)
        where = " AND ".join(clauses)
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            edges = conn.execute(f"SELECT e.* FROM analysis_graph_edges e WHERE {where}", params).fetchall()
            for edge in edges:
                unresolved = self._decode_json_value(edge["unresolved_target_json"], {})
                metadata = self._decode_json_value(edge["metadata_json"], {})
                candidates = self._resolution_candidates(conn, edge, unresolved, metadata)
                conn.execute("DELETE FROM analysis_graph_resolution_candidates WHERE edge_id = ?", (edge["id"],))
                for candidate in candidates:
                    conn.execute("""
                        INSERT INTO analysis_graph_resolution_candidates(edge_id, candidate_node_id, confidence, reason, fact_origin, flow_domain, metadata_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, (
                        edge["id"],
                        candidate["node_id"],
                        candidate["confidence"],
                        candidate["reason"],
                        GraphFactOrigin.RESOLVER.value,
                        edge["flow_domain"] if "flow_domain" in edge.keys() and edge["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
                        json.dumps(candidate.get("metadata") or {}),
                        now,
                    ))
                status, target_id = self._resolution_decision(unresolved, metadata, candidates)
                metadata["resolutionCandidates"] = [
                    {"nodeId": candidate["node_id"], "confidence": candidate["confidence"], "reason": candidate["reason"]}
                    for candidate in candidates[:10]
                ]
                conn.execute("""
                    UPDATE analysis_graph_edges
                    SET to_node_id = ?, resolution_status = ?, metadata_json = ?
                    WHERE id = ?
                """, (target_id, status, json.dumps(metadata), edge["id"]))

    def graph_slice(
        self,
        entry_node_id: str,
        max_depth: int = 3,
        edge_types: Optional[List[str]] = None,
        include_unresolved: bool = False,
        include_evidence: bool = False,
    ) -> Dict[str, Any]:
        self.init()
        edge_type_set = set(edge_types or [GraphEdgeType.CALLS.value])
        visited_nodes = {entry_node_id}
        visited_edges: set[str] = set()
        frontier = [entry_node_id]
        with self._connect() as conn:
            for _ in range(max(0, max_depth)):
                if not frontier:
                    break
                placeholders = ",".join("?" for _ in frontier)
                rows = conn.execute(
                    f"""
                    SELECT * FROM analysis_graph_edges
                    WHERE from_node_id IN ({placeholders})
                      AND status IN ({PROJECTED_FACT_STATUS_SQL})
                    """,
                    frontier,
                ).fetchall()
                next_frontier: List[str] = []
                for edge in rows:
                    if edge["edge_type"] not in edge_type_set:
                        continue
                    if edge["to_node_id"] is None and not include_unresolved:
                        continue
                    visited_edges.add(edge["id"])
                    if edge["to_node_id"] and edge["to_node_id"] not in visited_nodes:
                        visited_nodes.add(edge["to_node_id"])
                        next_frontier.append(edge["to_node_id"])
                frontier = next_frontier
            nodes = self._graph_nodes(conn, sorted(visited_nodes))
            edges = self._graph_edges(conn, sorted(visited_edges))
            claims = self._graph_claims(conn, sorted(visited_nodes))
            evidence = self._graph_evidence(conn, edges, claims) if include_evidence else []
        return {
            "entrypoint": entry_node_id,
            "nodes": nodes,
            "edges": edges,
            "claims": claims,
            "evidence": evidence,
            "uncertainties": [edge for edge in edges if edge["resolutionStatus"] != GraphResolutionStatus.RESOLVED.value],
            "diagnostics": [],
        }

    def graph_metrics(self, job_id: Optional[str] = None, source_id: Optional[str] = None) -> Dict[str, Any]:
        self.init()
        clauses: list[str] = []
        params: list[Any] = []
        if job_id:
            clauses.append("job_id = ?")
            params.append(job_id)
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as conn:
            return {
                "filesByJobStatusDomain": [
                    dict(row) for row in conn.execute(f"""
                        SELECT job_id, source_id, flow_domain, status, COUNT(*) AS count
                        FROM analysis_job_files
                        {where}
                        GROUP BY job_id, source_id, flow_domain, status
                        ORDER BY count DESC
                    """, params).fetchall()
                ],
                "nodesByDomainOriginKindStatus": self._metrics_rows(conn, "analysis_graph_nodes", where, params, "node_kind"),
                "edgesByDomainOriginTypeResolutionStatus": [
                    dict(row) for row in conn.execute(f"""
                        SELECT source_id, flow_domain, fact_origin, edge_type, resolution_status, status, COUNT(*) AS count
                        FROM analysis_graph_edges
                        {where}
                        GROUP BY source_id, flow_domain, fact_origin, edge_type, resolution_status, status
                        ORDER BY count DESC
                    """, params).fetchall()
                ],
                "claimsByDomainOriginKindStatus": self._metrics_rows(conn, "analysis_graph_claims", where, params, "claim_kind"),
                "diagnosticsByDomainStageCode": [
                    dict(row) for row in conn.execute(f"""
                        SELECT source_id, flow_domain, stage, code, severity, COUNT(*) AS count
                        FROM analysis_graph_diagnostics
                        {where}
                        GROUP BY source_id, flow_domain, stage, code, severity
                        ORDER BY count DESC
                    """, params).fetchall()
                ],
            }

    def _delete_file_analysis(self, conn: sqlite3.Connection, file_id: int) -> None:
        node_ids = [row["id"] for row in conn.execute("SELECT id FROM analysis_graph_nodes WHERE inventory_file_id = ?", (file_id,)).fetchall()]
        edge_ids = [row["id"] for row in conn.execute("SELECT id FROM analysis_graph_edges WHERE inventory_file_id = ?", (file_id,)).fetchall()]
        if node_ids:
            placeholders = ",".join("?" for _ in node_ids)
            extra_edge_ids = [
                row["id"]
                for row in conn.execute(
                    f"SELECT id FROM analysis_graph_edges WHERE from_node_id IN ({placeholders}) OR to_node_id IN ({placeholders})",
                    [*node_ids, *node_ids],
                ).fetchall()
            ]
            edge_ids.extend(extra_edge_ids)
            conn.execute(f"DELETE FROM analysis_graph_claims WHERE node_id IN ({placeholders})", node_ids)
            conn.execute(f"DELETE FROM analysis_graph_nodes WHERE id IN ({placeholders})", node_ids)
        if edge_ids:
            unique_edge_ids = sorted(set(edge_ids))
            placeholders = ",".join("?" for _ in unique_edge_ids)
            conn.execute(f"DELETE FROM analysis_graph_resolution_candidates WHERE edge_id IN ({placeholders})", unique_edge_ids)
            conn.execute(f"DELETE FROM analysis_graph_edges WHERE id IN ({placeholders})", unique_edge_ids)
        conn.execute("DELETE FROM analysis_graph_evidence WHERE inventory_file_id = ?", (file_id,))
        conn.execute("DELETE FROM analysis_graph_diagnostics WHERE inventory_file_id = ?", (file_id,))

    def _reattach_current_analysis_files(self, conn: sqlite3.Connection, source_ids: Optional[List[str]]) -> None:
        clauses = ["current.id IS NULL"]
        params: list[Any] = []
        if source_ids:
            placeholders = ",".join("?" for _ in source_ids)
            clauses.append(f"af.source_id IN ({placeholders})")
            params.extend(source_ids)
        where = " AND ".join(clauses)
        rows = conn.execute(f"""
            SELECT af.file_id AS old_file_id, f.id AS new_file_id
            FROM analysis_files af
            LEFT JOIN files current ON current.id = af.file_id
            JOIN files f
              ON f.source_id = af.source_id
             AND f.relative_path = af.relative_path
             AND f.content_hash = af.content_hash
            WHERE {where}
        """, params).fetchall()
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
            conn.execute("UPDATE analysis_graph_nodes SET inventory_file_id = ?, analysis_file_id = ? WHERE inventory_file_id = ?", (new_file_id, new_file_id, old_file_id))
            conn.execute("UPDATE analysis_graph_edges SET inventory_file_id = ?, analysis_file_id = ? WHERE inventory_file_id = ?", (new_file_id, new_file_id, old_file_id))
            conn.execute("UPDATE analysis_graph_evidence SET inventory_file_id = ?, analysis_file_id = ? WHERE inventory_file_id = ?", (new_file_id, new_file_id, old_file_id))
            conn.execute("UPDATE analysis_graph_diagnostics SET inventory_file_id = ?, analysis_file_id = ? WHERE inventory_file_id = ?", (new_file_id, new_file_id, old_file_id))
            conn.execute("UPDATE analysis_job_files SET inventory_file_id = ?, analysis_file_id = ? WHERE inventory_file_id = ?", (new_file_id, new_file_id, old_file_id))

    def _upsert_file(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute("""
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, engine_version, flow_domain, status, analyzed_at, symbol_count, relation_count, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            file_id, state["source_id"], state["relative_path"], state["content_hash"], state["analyzer_name"], state["analyzer_version"],
            state.get("engine_version") or GRAPH_ANALYSIS_ENGINE_VERSION,
            state.get("flow_domain") or classify_flow_domain(state["relative_path"], None).value,
            state["status"], state.get("analyzed_at"), state["symbol_count"], state["relation_count"],
            state.get("attempt_count", 0), state.get("last_attempt_at"), state.get("last_error_code"),
            state.get("last_error_message"), state.get("last_raw_response_preview"),
            json.dumps(state.get("diagnostics") or []),
        ))

    def _job_params(self, job: Dict[str, Any]):
        return (
            job["jobId"], job["status"], job.get("startedAt"), job.get("completedAt"), job.get("sourceCount", 0), job.get("fileCount", 0),
            job.get("processedFileCount", 0), job.get("failedFileCount", 0),
            job.get("currentSourceId"), job.get("currentRelativePath"), json.dumps(job.get("sourceIds") or []),
            job.get("engineVersion") or GRAPH_ANALYSIS_ENGINE_VERSION, job.get("lastProgressAt"),
            job.get("symbolCount", 0), job.get("relationCount", 0),
            json.dumps(job.get("diagnostics") or []),
        )

    def _job(self, row) -> Dict[str, Any]:
        return {
            "jobId": row["job_id"], "status": row["status"], "startedAt": row["started_at"], "completedAt": row["completed_at"],
            "sourceCount": row["source_count"], "fileCount": row["file_count"], "processedFileCount": row["processed_file_count"],
            "failedFileCount": row["failed_file_count"],
            "currentSourceId": row["current_source_id"], "currentRelativePath": row["current_relative_path"],
            "sourceIds": json.loads(row["source_ids_json"] or "[]"),
            "engineVersion": row["engine_version"] if "engine_version" in row.keys() else None,
            "lastProgressAt": row["last_progress_at"],
            "symbolCount": row["symbol_count"], "relationCount": row["relation_count"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
        }

    def _job_file(self, row) -> Dict[str, Any]:
        return {
            "jobFileId": row["id"],
            "jobId": row["job_id"],
            "sourceId": row["source_id"],
            "inventoryFileId": row["inventory_file_id"],
            "analysisFileId": row["analysis_file_id"],
            "relativePath": row["relative_path"],
            "extension": row["extension"],
            "contentHash": row["content_hash"],
            "lineCount": row["line_count"],
            "decodePolicy": row["decode_policy"],
            "flowDomain": row["flow_domain"],
            "status": row["status"],
            "attemptCount": row["attempt_count"],
            "startedAt": row["started_at"],
            "completedAt": row["completed_at"],
            "engineVersion": row["engine_version"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
        }

    def _file(self, row) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"], "relativePath": row["relative_path"], "contentHash": row["content_hash"],
            "analysisStatus": row["status"], "analyzedAt": row["analyzed_at"], "symbolCount": row["symbol_count"],
            "relationCount": row["relation_count"], "attemptCount": row["attempt_count"],
            "lastAttemptAt": row["last_attempt_at"], "lastErrorCode": row["last_error_code"],
            "lastErrorMessage": row["last_error_message"], "lastRawResponsePreview": row["last_raw_response_preview"],
            "engineVersion": row["engine_version"] if "engine_version" in row.keys() else None,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() else None,
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
        }

    def _job_file_id(self, job_id: str, inventory_file_id: int) -> str:
        return f"{job_id}:{inventory_file_id}"

    def _now_iso(self) -> str:
        return datetime.now(timezone.utc).isoformat()

    def _flow_domain(self, row: sqlite3.Row) -> str:
        return self._row_value(row, "flow_domain") or classify_flow_domain(row["relative_path"], row["extension"]).value

    def _row_value(self, row: sqlite3.Row, key: str) -> Optional[Any]:
        try:
            if hasattr(row, "keys") and key not in row.keys():
                return None
            return row[key]
        except (KeyError, IndexError):
            return None

    def _graph_symbol(self, row, claims: List[sqlite3.Row], evidence: Dict[str, sqlite3.Row]) -> Dict[str, Any]:
        metadata = self._decode_json_value(row["metadata_json"], {})
        responsibility = self._best_claim(claims, GraphClaimKind.RESPONSIBILITY)
        role_claims = [
            claim for claim in claims
            if claim["claim_kind"] == GraphClaimKind.ROLE.value and claim["status"] in PROJECTED_FACT_STATUSES
        ]
        roles = []
        for claim in role_claims:
            claim_metadata = self._decode_json_value(claim["metadata_json"], {})
            role = claim_metadata.get("role") or claim["summary"] or GraphNodeKind.UNKNOWN.value
            roles.append({
                "role": role,
                "confidence": claim["confidence"],
                "evidence": self._claim_evidence_strings(claim, evidence),
                "classifier": "graph-analysis",
                "classifierVersion": GRAPH_ANALYSIS_ENGINE_VERSION,
            })
        return {
            "symbolId": row["id"],
            "sourceId": row["source_id"],
            "relativePath": row["projected_relative_path"],
            "name": row["name"],
            "kind": row["node_kind"],
            "roles": roles,
            "lineStart": row["line_start"] or 1,
            "lineEnd": row["line_end"] or row["line_start"] or 1,
            "summary": responsibility["summary"] if responsibility else None,
            "metadata": metadata,
            "graphNodeId": row["id"],
            "stableKey": row["stable_key"],
            "nodeKind": row["node_kind"],
            "displayName": row["display_name"],
            "qualifiedName": row["qualified_name"],
            "responsibilitySummary": responsibility["summary"] if responsibility else None,
            "confidence": row["confidence"],
            "factStatus": row["status"],
            "factOrigin": row["fact_origin"] if "fact_origin" in row.keys() and row["fact_origin"] else GraphFactOrigin.UNKNOWN.value,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() and row["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
            "evidenceCount": 1 if metadata.get("evidenceId") else 0,
            "diagnosticCount": self._diagnostic_count_for_row(row),
        }

    def _graph_relation(self, row) -> Dict[str, Any]:
        metadata = self._decode_json_value(row["metadata_json"], {})
        line_start = row["evidence_line_start"] or 1
        line_end = row["evidence_line_end"] or line_start
        return {
            "relationId": row["id"],
            "sourceId": row["source_id"],
            "fromSymbolId": row["from_node_id"],
            "toSymbolId": row["to_node_id"],
            "relation": row["edge_type"],
            "confidence": row["confidence"],
            "evidence": self._edge_evidence_strings(row),
            "lineStart": line_start,
            "lineEnd": line_end,
            "metadata": metadata,
            "graphEdgeId": row["id"],
            "fromGraphNodeId": row["from_node_id"],
            "toGraphNodeId": row["to_node_id"],
            "edgeType": row["edge_type"],
            "resolutionStatus": row["resolution_status"],
            "factStatus": row["status"],
            "factOrigin": row["fact_origin"] if "fact_origin" in row.keys() and row["fact_origin"] else GraphFactOrigin.UNKNOWN.value,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() and row["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
            "unresolvedTarget": self._decode_json_value(row["unresolved_target_json"], None),
            "evidenceCount": 1 if row["evidence_id"] else 0,
            "diagnosticCount": self._diagnostic_count_for_row(row),
        }

    def _decode_json_value(self, value: Optional[str], fallback: Any) -> Any:
        if not value:
            return fallback
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return fallback

    def _resolution_candidates(self, conn: sqlite3.Connection, edge: sqlite3.Row, unresolved: Dict[str, Any], edge_metadata: Dict[str, Any]) -> List[Dict[str, Any]]:
        target_name = self._target_name(unresolved)
        if not target_name:
            return []
        source_id = edge["source_id"]
        simple_name = self._target_simple_name(target_name)
        owner_hint = self._owner_hint(unresolved, edge_metadata, target_name)
        receiver_hint = self._receiver_hint(unresolved, edge_metadata, target_name)
        from_node = conn.execute("SELECT * FROM analysis_graph_nodes WHERE id = ?", (edge["from_node_id"],)).fetchone()
        from_parent = None
        if from_node and from_node["parent_node_id"]:
            from_parent = conn.execute("SELECT * FROM analysis_graph_nodes WHERE id = ?", (from_node["parent_node_id"],)).fetchone()
        field_type_hint = self._field_type_hint(conn, from_parent, receiver_hint)
        imports = self._file_imports(conn, source_id, from_node["inventory_file_id"] if from_node else None)
        rows = conn.execute(f"""
            SELECT n.*
            FROM analysis_graph_nodes n
            JOIN analysis_files af
              ON af.file_id = n.inventory_file_id
             AND af.source_id = n.source_id
             AND af.engine_version = '{GRAPH_ANALYSIS_ENGINE_VERSION}'
             AND af.status = 'ANALYZED'
            JOIN files f
              ON f.id = n.inventory_file_id
             AND f.source_id = n.source_id
             AND f.relative_path = af.relative_path
             AND f.content_hash = af.content_hash
            WHERE n.source_id = ?
              AND n.status IN ({PROJECTED_FACT_STATUS_SQL})
              AND n.node_kind IN ({RESOLUTION_NODE_KIND_SQL})
              AND (
                  n.qualified_name = ?
                  OR n.display_name = ?
                  OR n.name = ?
                  OR n.qualified_name LIKE ?
              )
            ORDER BY confidence DESC, qualified_name
            LIMIT 50
        """, (source_id, target_name, target_name, simple_name, f"%.{simple_name}")).fetchall()
        kind_hint = str(unresolved.get("kindHint") or "").upper()
        result: List[Dict[str, Any]] = []
        for row in rows:
            metadata = self._decode_json_value(row["metadata_json"], {})
            confidence = 0.45
            signals: List[str] = []
            candidate_owner = self._candidate_owner(row)
            if row["qualified_name"] == target_name or row["display_name"] == target_name:
                confidence += 0.45
                signals.append("qualified_name")
            elif row["name"] == simple_name:
                confidence += 0.12
                signals.append("simple_name")
            if kind_hint and kind_hint == row["node_kind"]:
                confidence += 0.05
                signals.append("kind")
            if owner_hint and self._owner_matches(candidate_owner, owner_hint):
                confidence += 0.25
                signals.append("owner")
            if field_type_hint and self._owner_matches(candidate_owner, field_type_hint):
                confidence += 0.30
                signals.append("receiver_field")
            if imports and self._import_matches(candidate_owner, row["qualified_name"], imports):
                confidence += 0.12
                signals.append("import")
            if from_parent and candidate_owner and self._same_package(from_parent["qualified_name"], candidate_owner):
                confidence += 0.06
                signals.append("package")
            confidence = min(confidence, 0.99)
            strong_signals = {"qualified_name", "owner", "receiver_field"}
            has_strong_evidence = bool(strong_signals.intersection(signals))
            result.append({
                "node_id": row["id"],
                "confidence": confidence,
                "reason": "+".join(signals) if signals else "weak_name",
                "metadata": {
                    "qualifiedName": row["qualified_name"],
                    "nodeKind": row["node_kind"],
                    "nodeMetadata": metadata,
                    "signals": signals,
                    "hasStrongEvidence": has_strong_evidence,
                    "ownerHint": owner_hint,
                    "receiverHint": receiver_hint,
                    "fieldTypeHint": field_type_hint,
                },
            })
        return sorted(result, key=lambda item: item["confidence"], reverse=True)

    def _resolution_decision(self, unresolved: Dict[str, Any], metadata: Dict[str, Any], candidates: List[Dict[str, Any]]) -> tuple[str, Optional[str]]:
        kind_hint = str(unresolved.get("kindHint") or metadata.get("kindHint") or "").upper()
        if kind_hint in {"EXTERNAL", "RESOURCE"} and not candidates:
            return GraphResolutionStatus.EXTERNAL_TARGET.value, None
        if kind_hint == "DYNAMIC" or metadata.get("dynamic") is True:
            return GraphResolutionStatus.DYNAMIC_TARGET.value, None
        if not candidates:
            return GraphResolutionStatus.UNRESOLVED.value, None
        strong_candidates = [
            candidate for candidate in candidates
            if candidate.get("metadata", {}).get("hasStrongEvidence") is True and candidate["confidence"] >= 0.75
        ]
        if kind_hint == "INTERFACE" or self._has_interface_candidate(strong_candidates):
            return GraphResolutionStatus.INTERFACE_TARGET.value, None
        if len(strong_candidates) == 1:
            return GraphResolutionStatus.RESOLVED.value, strong_candidates[0]["node_id"]
        if len(strong_candidates) > 1:
            return GraphResolutionStatus.MULTIPLE_CANDIDATES.value, None
        if len(candidates) > 1:
            return GraphResolutionStatus.MULTIPLE_CANDIDATES.value, None
        return GraphResolutionStatus.UNRESOLVED.value, None

    def _candidate_owner(self, row: sqlite3.Row) -> Optional[str]:
        qualified_name = row["qualified_name"] or row["display_name"] or ""
        if "." not in qualified_name:
            return None
        return qualified_name.rsplit(".", 1)[0]

    def _owner_hint(self, unresolved: Dict[str, Any], metadata: Dict[str, Any], target_name: str) -> Optional[str]:
        for source in (unresolved, metadata):
            for key in ("qualifiedOwner", "ownerType", "declaringType", "receiverType", "parentType", "typeName", "className"):
                value = source.get(key)
                if isinstance(value, str) and value.strip():
                    return value.strip()
        if "." in target_name:
            owner = target_name.rsplit(".", 1)[0]
            if owner and not owner[0].islower():
                return owner
        return None

    def _receiver_hint(self, unresolved: Dict[str, Any], metadata: Dict[str, Any], target_name: str) -> Optional[str]:
        for source in (unresolved, metadata):
            for key in ("receiver", "receiverName", "receiverLocalName", "fieldName"):
                value = source.get(key)
                if isinstance(value, str) and value.strip():
                    return value.strip()
        if "." in target_name:
            receiver = target_name.split(".", 1)[0]
            if receiver and receiver[0].islower():
                return receiver
        return None

    def _field_type_hint(self, conn: sqlite3.Connection, parent: Optional[sqlite3.Row], receiver_hint: Optional[str]) -> Optional[str]:
        if not parent or not receiver_hint:
            return None
        rows = conn.execute(f"""
            SELECT metadata_json, name
            FROM analysis_graph_nodes
            WHERE parent_node_id = ?
              AND node_kind = ?
              AND status IN ({PROJECTED_FACT_STATUS_SQL})
        """, (parent["id"], GraphNodeKind.FIELD.value)).fetchall()
        for row in rows:
            metadata = self._decode_json_value(row["metadata_json"], {})
            if row["name"] == receiver_hint or metadata.get("receiverName") == receiver_hint:
                type_name = metadata.get("typeName")
                if isinstance(type_name, str) and type_name.strip():
                    return type_name.strip()
        return None

    def _file_imports(self, conn: sqlite3.Connection, source_id: str, inventory_file_id: Optional[int]) -> List[str]:
        if inventory_file_id is None:
            return []
        rows = conn.execute(f"""
            SELECT target.qualified_name
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes file_node ON file_node.id = e.from_node_id
            JOIN analysis_graph_nodes target ON target.id = e.to_node_id
            WHERE e.source_id = ?
              AND file_node.inventory_file_id = ?
              AND e.edge_type = ?
              AND e.status IN ({PROJECTED_FACT_STATUS_SQL})
              AND target.status IN ({PROJECTED_FACT_STATUS_SQL})
        """, (source_id, inventory_file_id, GraphEdgeType.IMPORTS.value)).fetchall()
        return [row["qualified_name"] for row in rows if row["qualified_name"]]

    def _owner_matches(self, candidate_owner: Optional[str], hint: str) -> bool:
        if not candidate_owner or not hint:
            return False
        normalized_owner = candidate_owner.replace("$", ".")
        normalized_hint = hint.replace("$", ".")
        return (
            normalized_owner == normalized_hint
            or normalized_owner.endswith(f".{normalized_hint}")
            or normalized_hint.endswith(f".{normalized_owner}")
            or normalized_owner.split(".")[-1] == normalized_hint.split(".")[-1]
        )

    def _import_matches(self, candidate_owner: Optional[str], candidate_qualified_name: Optional[str], imports: List[str]) -> bool:
        if not candidate_owner:
            return False
        for imported in imports:
            if imported.endswith(".*"):
                package = imported[:-2]
                if candidate_owner.startswith(f"{package}."):
                    return True
                continue
            if self._owner_matches(candidate_owner, imported):
                return True
            if candidate_qualified_name and self._owner_matches(candidate_qualified_name, imported):
                return True
        return False

    def _same_package(self, left: Optional[str], right: Optional[str]) -> bool:
        if not left or not right or "." not in left or "." not in right:
            return False
        left_package = left.rsplit(".", 1)[0]
        right_package = right.rsplit(".", 1)[0]
        return left_package == right_package

    def _target_simple_name(self, target_name: str) -> str:
        simple = target_name.split(".")[-1]
        if "(" in simple:
            simple = simple.split("(", 1)[0]
        return simple

    def _has_interface_candidate(self, candidates: List[Dict[str, Any]]) -> bool:
        for candidate in candidates:
            metadata = candidate.get("metadata", {}).get("nodeMetadata", {})
            if metadata.get("interface") is True or str(metadata.get("declaringTypeKind") or "").lower() == "interface":
                return True
        return False

    def _target_name(self, unresolved: Dict[str, Any]) -> Optional[str]:
        for key in ("qualifiedName", "name", "target", "displayName"):
            value = unresolved.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return None

    def _claims_by_node(self, conn: sqlite3.Connection, node_ids: List[str]) -> Dict[str, List[sqlite3.Row]]:
        if not node_ids:
            return {}
        rows = conn.execute(
            f"SELECT * FROM analysis_graph_claims WHERE node_id IN ({','.join('?' for _ in node_ids)}) ORDER BY confidence DESC",
            node_ids,
        ).fetchall()
        result: Dict[str, List[sqlite3.Row]] = {}
        for row in rows:
            result.setdefault(row["node_id"], []).append(row)
        return result

    def _claim_evidence_ids(self, claims_by_node: Dict[str, List[sqlite3.Row]]) -> List[str]:
        ids: List[str] = []
        for claims in claims_by_node.values():
            for claim in claims:
                ids.extend(json.loads(claim["evidence_ids_json"] or "[]"))
        return sorted(set(ids))

    def _evidence_by_id(self, conn: sqlite3.Connection, evidence_ids: List[str]) -> Dict[str, sqlite3.Row]:
        if not evidence_ids:
            return {}
        rows = conn.execute(
            f"SELECT * FROM analysis_graph_evidence WHERE id IN ({','.join('?' for _ in evidence_ids)})",
            evidence_ids,
        ).fetchall()
        return {row["id"]: row for row in rows}

    def _best_claim(self, claims: List[sqlite3.Row], claim_kind: GraphClaimKind) -> Optional[sqlite3.Row]:
        matching = [claim for claim in claims if claim["claim_kind"] == claim_kind.value and claim["status"] in PROJECTED_FACT_STATUSES]
        if not matching:
            return None
        return sorted(matching, key=lambda item: (item["status"] == GraphFactStatus.TRUSTED.value, item["confidence"]), reverse=True)[0]

    def _diagnostic_count(self, conn: sqlite3.Connection, file_id: Optional[int]) -> int:
        if file_id is None:
            return 0
        row = conn.execute("SELECT COUNT(*) AS count FROM analysis_graph_diagnostics WHERE inventory_file_id = ?", (file_id,)).fetchone()
        return int(row["count"] or 0)

    def _diagnostic_count_for_row(self, row: sqlite3.Row) -> int:
        if row["inventory_file_id"] is None:
            return 0
        with self._connect() as conn:
            return self._diagnostic_count(conn, row["inventory_file_id"])

    def _claim_evidence_strings(self, claim: sqlite3.Row, evidence: Dict[str, sqlite3.Row]) -> List[str]:
        result: List[str] = []
        for evidence_id in json.loads(claim["evidence_ids_json"] or "[]"):
            row = evidence.get(evidence_id)
            if row:
                result.append(f"line {row['line_start']}-{row['line_end']}")
        return result

    def _edge_evidence_strings(self, edge: sqlite3.Row) -> List[str]:
        if edge["evidence_line_start"]:
            return [f"line {edge['evidence_line_start']}-{edge['evidence_line_end']}"]
        return []

    def _graph_nodes(self, conn: sqlite3.Connection, node_ids: List[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        rows = conn.execute(
            f"SELECT * FROM analysis_graph_nodes WHERE id IN ({','.join('?' for _ in node_ids)}) ORDER BY source_id, qualified_name",
            node_ids,
        ).fetchall()
        return [{
            "id": row["id"],
            "sourceId": row["source_id"],
            "inventoryFileId": row["inventory_file_id"],
            "nodeKind": row["node_kind"],
            "name": row["name"],
            "qualifiedName": row["qualified_name"],
            "parentNodeId": row["parent_node_id"],
            "lineStart": row["line_start"],
            "lineEnd": row["line_end"],
            "confidence": row["confidence"],
            "status": row["status"],
            "stableKey": row["stable_key"],
            "factOrigin": row["fact_origin"] if "fact_origin" in row.keys() and row["fact_origin"] else GraphFactOrigin.UNKNOWN.value,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() and row["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
            "metadata": self._decode_json_value(row["metadata_json"], {}),
        } for row in rows]

    def _graph_edges(self, conn: sqlite3.Connection, edge_ids: List[str]) -> List[Dict[str, Any]]:
        if not edge_ids:
            return []
        rows = conn.execute(
            f"SELECT * FROM analysis_graph_edges WHERE id IN ({','.join('?' for _ in edge_ids)}) ORDER BY edge_type",
            edge_ids,
        ).fetchall()
        return [{
            "id": row["id"],
            "sourceId": row["source_id"],
            "fromNodeId": row["from_node_id"],
            "toNodeId": row["to_node_id"],
            "edgeType": row["edge_type"],
            "resolutionStatus": row["resolution_status"],
            "confidence": row["confidence"],
            "evidenceId": row["evidence_id"],
            "unresolvedTarget": self._decode_json_value(row["unresolved_target_json"], None),
            "status": row["status"],
            "factOrigin": row["fact_origin"] if "fact_origin" in row.keys() and row["fact_origin"] else GraphFactOrigin.UNKNOWN.value,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() and row["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
            "metadata": self._decode_json_value(row["metadata_json"], {}),
        } for row in rows]

    def _graph_claims(self, conn: sqlite3.Connection, node_ids: List[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        rows = conn.execute(
            f"SELECT * FROM analysis_graph_claims WHERE node_id IN ({','.join('?' for _ in node_ids)}) AND status IN ({PROJECTED_FACT_STATUS_SQL}) ORDER BY confidence DESC",
            node_ids,
        ).fetchall()
        return [{
            "id": row["id"],
            "sourceId": row["source_id"],
            "nodeId": row["node_id"],
            "claimKind": row["claim_kind"],
            "summary": row["summary"],
            "confidence": row["confidence"],
            "status": row["status"],
            "factOrigin": row["fact_origin"] if "fact_origin" in row.keys() and row["fact_origin"] else GraphFactOrigin.UNKNOWN.value,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() and row["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
            "evidenceIds": json.loads(row["evidence_ids_json"] or "[]"),
            "metadata": self._decode_json_value(row["metadata_json"], {}),
        } for row in rows]

    def _graph_evidence(self, conn: sqlite3.Connection, edges: List[Dict[str, Any]], claims: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        evidence_ids = {edge["evidenceId"] for edge in edges if edge.get("evidenceId")}
        for claim in claims:
            evidence_ids.update(claim.get("evidenceIds") or [])
        if not evidence_ids:
            return []
        rows = conn.execute(
            f"SELECT * FROM analysis_graph_evidence WHERE id IN ({','.join('?' for _ in evidence_ids)}) ORDER BY source_id, line_start",
            sorted(evidence_ids),
        ).fetchall()
        return [{
            "id": row["id"],
            "sourceId": row["source_id"],
            "inventoryFileId": row["inventory_file_id"],
            "contentHash": row["content_hash"],
            "lineStart": row["line_start"],
            "lineEnd": row["line_end"],
            "excerptHash": row["excerpt_hash"],
            "evidenceKind": row["evidence_kind"],
            "factOrigin": row["fact_origin"] if "fact_origin" in row.keys() and row["fact_origin"] else GraphFactOrigin.UNKNOWN.value,
            "flowDomain": row["flow_domain"] if "flow_domain" in row.keys() and row["flow_domain"] else GraphFlowDomain.UNKNOWN.value,
            "metadata": self._decode_json_value(row["metadata_json"], {}),
        } for row in rows]

    def _metrics_rows(self, conn: sqlite3.Connection, table: str, where: str, params: List[Any], kind_column: str) -> List[Dict[str, Any]]:
        return [
            dict(row)
            for row in conn.execute(f"""
                SELECT source_id, flow_domain, fact_origin, {kind_column} AS kind, status, COUNT(*) AS count
                FROM {table}
                {where}
                GROUP BY source_id, flow_domain, fact_origin, {kind_column}, status
                ORDER BY count DESC
            """, params).fetchall()
        ]

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _drop_legacy_fact_tables(self, conn: sqlite3.Connection) -> None:
        for table in (
            "analysis_symbol_roles",
            "analysis_relations",
            "analysis_symbols",
            "symbol_tokens",
            "edges",
            "symbols",
            "file_extraction_state",
            "fact_builds",
        ):
            conn.execute(f"DROP TABLE IF EXISTS {table}")

    def reset_analysis_cache(self) -> None:
        self.init()
        with self._connect() as conn:
            self._reset_analysis_cache(conn)

    def _reset_analysis_cache(self, conn: sqlite3.Connection) -> None:
        tables = (
            "analysis_graph_resolution_candidates",
            "analysis_graph_diagnostics",
            "analysis_graph_claims",
            "analysis_graph_edges",
            "analysis_graph_nodes",
            "analysis_graph_evidence",
            "analysis_job_files",
            "analysis_files",
            "analysis_jobs",
        )
        for table in tables:
            conn.execute(f"DELETE FROM {table}")

    def _run_schema_migrations(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_schema_migrations (
                version INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
        """)
        applied = {
            row["version"]
            for row in conn.execute("SELECT version FROM analysis_schema_migrations").fetchall()
        }
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
            self._ensure_column(conn, "analysis_jobs", "engine_version", "TEXT")
            self._ensure_column(conn, "analysis_files", "engine_version", "TEXT")
            self._reset_analysis_cache(conn)
            return
        raise RuntimeError(f"Unknown analysis schema migration: {version}")

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
            "job_id", "status", "started_at", "completed_at", "source_count", "file_count",
            "processed_file_count", "failed_file_count", "current_source_id", "current_relative_path",
            "source_ids_json", "engine_version", "last_progress_at", "symbol_count", "relation_count", "diagnostics_json",
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
                source_ids_json TEXT,
                engine_version TEXT,
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
