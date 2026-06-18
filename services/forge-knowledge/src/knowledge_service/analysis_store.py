from __future__ import annotations

import json
import base64
import sqlite3
import threading
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set

from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_call_intelligence import classify_call_metadata
from knowledge_service.source_catalog import SourceMetadata


ANALYSIS_SCHEMA_MIGRATIONS = (
    (1, "remove_legacy_analysis_job_counter"),
    (2, "add_analysis_job_source_scope"),
    (3, "reset_analysis_cache_for_graph_v1_cutover"),
    (4, "reconcile_graph_diagnostics_schema"),
)


class AnalysisStore:
    _init_lock = threading.Lock()
    _initialized_paths: Set[str] = set()

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
                CREATE TABLE IF NOT EXISTS analysis_symbols (
                    symbol_id TEXT PRIMARY KEY,
                    file_id INTEGER NOT NULL,
                    source_id TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    line_start INTEGER NOT NULL,
                    line_end INTEGER NOT NULL,
                    summary TEXT,
                    metadata_json TEXT NOT NULL
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_symbol_roles (
                    symbol_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    evidence_json TEXT NOT NULL,
                    classifier TEXT NOT NULL,
                    classifier_version TEXT NOT NULL
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS analysis_relations (
                    relation_id TEXT PRIMARY KEY,
                    source_id TEXT NOT NULL,
                    from_symbol_id TEXT NOT NULL,
                    to_symbol_id TEXT NOT NULL,
                    relation TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    evidence_json TEXT NOT NULL,
                    line_start INTEGER NOT NULL,
                    line_end INTEGER NOT NULL,
                    metadata_json TEXT NOT NULL
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
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT
                )
            """)
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
                    metadata_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT
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
                    fact_origin TEXT,
                    flow_domain TEXT
                )
            """)
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
                    created_at TEXT NOT NULL,
                    fact_origin TEXT,
                    flow_domain TEXT
                )
            """)
            self._create_analysis_graph_diagnostics_table(conn)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_status ON analysis_files(source_id, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_symbols_source_kind ON analysis_symbols(source_id, kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_roles_role ON analysis_symbol_roles(role)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_relations_relation ON analysis_relations(source_id, relation)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_kind ON analysis_graph_nodes(source_id, node_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_file ON analysis_graph_nodes(analysis_file_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_snapshot_page ON analysis_graph_nodes(source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_nodes_source_flow_created ON analysis_graph_nodes(source_id, flow_domain, created_at)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_claims_node_kind ON analysis_graph_claims(node_id, claim_kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_type ON analysis_graph_edges(source_id, edge_type)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_nodes ON analysis_graph_edges(from_node_id, to_node_id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_snapshot_page ON analysis_graph_edges(source_id, flow_domain, id)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_graph_edges_source_flow_created ON analysis_graph_edges(source_id, flow_domain, created_at)")
            self._drop_legacy_fact_tables(conn)
            self._run_schema_migrations(conn)
            self._reconcile_graph_diagnostics_schema(conn)

    def create_job(self, job: Dict[str, Any]) -> None:
        self.init()
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, symbol_count, relation_count, diagnostics_json, engine_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
                self._job_params(job),
            )

    def update_job(self, job_id: str, updates: Dict[str, Any]) -> None:
        current = self.job(job_id)
        if current is None:
            return
        current.update(updates)
        self.init()
        with self._connect() as conn:
            conn.execute(
                """
                UPDATE analysis_jobs
                SET status = ?, started_at = ?, completed_at = ?, source_count = ?, file_count = ?, processed_file_count = ?,
                    failed_file_count = ?, current_source_id = ?, current_relative_path = ?, source_ids_json = ?, last_progress_at = ?,
                    symbol_count = ?, relation_count = ?, diagnostics_json = ?, engine_version = ?
                WHERE job_id = ?
            """,
                (*self._job_params(current)[1:], job_id),
            )

    def create_job_files(self, job_id: str, rows: List[sqlite3.Row], flow_domain_by_file_id: Dict[int, str], engine_version: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
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
        with self._connect() as conn:
            conn.execute(
                f"""
                UPDATE analysis_job_files
                SET {", ".join(updates)}
                WHERE job_id = ? AND inventory_file_id = ?
            """,
                params,
            )

    def stop_incomplete_job_files(self, job_id: str) -> None:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
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

    def job(self, job_id: str) -> Optional[Dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE job_id = ?", (job_id,)).fetchone()
        return self._job(row) if row else None

    def active_job(self) -> Optional[Dict[str, Any]]:
        self.init()
        with self._connect() as conn:
            row = conn.execute("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY started_at DESC LIMIT 1").fetchone()
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
                diagnostics.append(
                    {
                        "code": "ANALYSIS_JOB_STOP_REQUESTED",
                        "message": "Analysis stop was requested by the operator.",
                    }
                )
            job.update(
                {
                    "status": "STOP_REQUESTED",
                    "completedAt": now,
                    "currentSourceId": None,
                    "currentRelativePath": None,
                    "lastProgressAt": now,
                    "diagnostics": diagnostics[-20:],
                }
            )
            conn.execute(
                """
                UPDATE analysis_jobs
                SET status = ?, completed_at = ?, current_source_id = NULL, current_relative_path = NULL,
                    last_progress_at = ?, diagnostics_json = ?
                WHERE job_id = ?
            """,
                (job["status"], job["completedAt"], job["lastProgressAt"], json.dumps(job["diagnostics"]), job_id),
            )
            return job

    def stop_requested(self, job_id: str) -> bool:
        job = self.job(job_id)
        return job is not None and job["status"] in {"STOP_REQUESTED", "STOPPED"}

    def mark_interrupted_jobs(self) -> None:
        self.init()
        with self._connect() as conn:
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

    def status(self) -> Dict[str, Any]:
        self.init()
        active = self.active_job()
        with self._connect() as conn:
            latest = conn.execute("SELECT * FROM analysis_jobs WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1").fetchone()
            counts = conn.execute("SELECT COUNT(*) AS symbols FROM analysis_graph_nodes").fetchone()
            relations = conn.execute("SELECT COUNT(*) AS relations FROM analysis_graph_edges").fetchone()
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
        engine_version: Optional[str],
        inventory_status: Dict[str, Any],
    ) -> Dict[str, Any]:
        self.init()
        active = self.active_job()
        active_source = active.get("currentSourceId") if active else None
        diagnostics_by_source = self._service_diagnostics(active)
        with self._connect() as conn:
            stats_rows = conn.execute(
                """
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
                          AND COALESCE(af.engine_version, '') = COALESCE(?, '')
                          AND af.status = 'ANALYZED'
                    ) THEN 1 ELSE 0 END) AS analyzed_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
                          AND COALESCE(af.engine_version, '') = COALESCE(?, '')
                          AND af.status = 'FAILED'
                    ) THEN 1 ELSE 0 END) AS failed_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
                          AND COALESCE(af.engine_version, '') = COALESCE(?, '')
                          AND af.status = 'SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS'
                    ) THEN 1 ELSE 0 END) AS skipped_too_large_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND (
                              af.content_hash != f.content_hash
                              OR af.analyzer_name != ?
                              OR af.analyzer_version != ?
                              OR COALESCE(af.engine_version, '') != COALESCE(?, '')
                          )
                    ) THEN 1 ELSE 0 END) AS stale_count
                FROM sources s
                LEFT JOIN files f ON f.source_id = s.source_id
                GROUP BY s.source_id, s.display_name, s.group_name, s.path, s.root_exists, s.tags_json
            """,
                (
                    analyzer_name,
                    analyzer_version,
                    engine_version,
                    analyzer_name,
                    analyzer_version,
                    engine_version,
                    analyzer_name,
                    analyzer_version,
                    engine_version,
                    analyzer_name,
                    analyzer_version,
                    engine_version,
                ),
            ).fetchall()
            symbol_rows = conn.execute("SELECT source_id, COUNT(*) AS count FROM analysis_graph_nodes GROUP BY source_id").fetchall()
            relation_rows = conn.execute("SELECT source_id, COUNT(*) AS count FROM analysis_graph_edges GROUP BY source_id").fetchall()
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
            processed = min(inventory_count, completed_outcomes + active_processed) if is_running else completed_outcomes
            pending = max(inventory_count - processed, 0) if is_running else max(inventory_count - completed_outcomes, 0)
            percent = round((processed / inventory_count) * 100, 1) if inventory_count else 0.0
            services.append(
                {
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
            )
        return {"services": services, "activeJob": active}

    def _service_diagnostics(self, active: Optional[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
        with self._connect() as conn:
            rows = conn.execute("""
                SELECT source_id, diagnostics_json
                FROM analysis_files
                WHERE diagnostics_json IS NOT NULL AND diagnostics_json != '[]'
            """).fetchall()
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
        return {source_id: sorted(items.values(), key=lambda item: item["count"], reverse=True) for source_id, items in grouped.items()}

    def _collect_diagnostics(self, grouped: Dict[str, Dict[str, Dict[str, Any]]], source_id: str, diagnostics: List[Dict[str, Any]]) -> None:
        bucket = grouped.setdefault(source_id, {})
        for diagnostic in diagnostics:
            code = diagnostic.get("code") or "DIAGNOSTIC"
            item = bucket.setdefault(
                code,
                {
                    "code": code,
                    "message": diagnostic.get("message") or "-",
                    "count": 0,
                    "examples": [],
                },
            )
            item["count"] += 1
            relative_path = diagnostic.get("relativePath")
            if relative_path and len(item["examples"]) < 10:
                item["examples"].append(relative_path)

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

    def unchanged_file_ids(
        self, rows: List[sqlite3.Row], analyzer_name: str, analyzer_version: str, engine_version: Optional[str] = None
    ) -> set[int]:
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
        self.init()
        with self._connect() as conn:
            self._delete_file_analysis(conn, file_id)
            for symbol in symbols:
                conn.execute(
                    """
                    INSERT INTO analysis_symbols(symbol_id, file_id, source_id, relative_path, name, kind, line_start, line_end, summary, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    (
                        symbol["symbol_id"],
                        file_id,
                        symbol["source_id"],
                        symbol["relative_path"],
                        symbol["name"],
                        symbol["kind"],
                        symbol["line_start"],
                        symbol["line_end"],
                        symbol.get("summary"),
                        json.dumps(symbol.get("metadata") or {}),
                    ),
                )
            for role in roles:
                conn.execute(
                    """
                    INSERT INTO analysis_symbol_roles(symbol_id, role, confidence, evidence_json, classifier, classifier_version)
                    VALUES (?, ?, ?, ?, ?, ?)
                """,
                    (role["symbol_id"], role["role"], role["confidence"], json.dumps(role["evidence"]), role["classifier"], role["classifier_version"]),
                )
            for relation in relations:
                conn.execute(
                    """
                    INSERT INTO analysis_relations(relation_id, source_id, from_symbol_id, to_symbol_id, relation, confidence, evidence_json, line_start, line_end, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                    (
                        relation["relation_id"],
                        relation["source_id"],
                        relation["from_symbol_id"],
                        relation["to_symbol_id"],
                        relation["relation"],
                        relation["confidence"],
                        json.dumps(relation["evidence"]),
                        relation["line_start"],
                        relation["line_end"],
                        json.dumps(relation.get("metadata") or {}),
                    ),
                )
            self._upsert_file(conn, file_id, state)

    def replace_file_graph_analysis(self, file_id: int, state: Dict[str, Any], graph: Dict[str, List[Dict[str, Any]]]) -> None:
        self.init()
        created_at = datetime.now(timezone.utc).isoformat()
        operation = "delete_file_analysis"
        table = "analysis_files"
        try:
            with self._connect() as conn:
                self._delete_file_analysis(conn, file_id)
                operation = "insert_nodes"
                table = "analysis_graph_nodes"
                for node in graph.get("nodes") or []:
                    conn.execute(
                        """
                        INSERT INTO analysis_graph_nodes(
                            id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind,
                            language, name, qualified_name, display_name, parent_node_id, line_start, line_end,
                            confidence, status, metadata_json, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                        (
                            node["id"],
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
                            id, job_id, source_id, inventory_file_id, analysis_file_id, content_hash, line_start,
                            line_end, excerpt_hash, evidence_kind, metadata_json, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                        (
                            item["id"],
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
                            id, job_id, source_id, node_id, claim_kind, summary, confidence, status, evidence_ids_json,
                            metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                            id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id,
                            edge_type, resolution_status, confidence, evidence_id, unresolved_target_json,
                            metadata_json, status, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                        (
                            edge["id"],
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
                            id, job_id, source_id, inventory_file_id, analysis_file_id, severity, stage, code,
                            message, candidate_id, line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                        (
                            diagnostic["id"],
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
                self._resolve_source_call_edges(conn, state["source_id"])
                operation = "upsert_analysis_file"
                table = "analysis_files"
                self._upsert_file(conn, file_id, state)
        except sqlite3.Error as exc:
            raise KnowledgeError(
                "ANALYSIS_GRAPH_STORE_FAILED",
                f"Graph persistence failed while writing {table}.",
                stage="GRAPH_STORE",
                severity="ERROR",
                table=table,
                operation=operation,
                exceptionType=type(exc).__name__,
                sqliteMessage=str(exc),
            ) from exc

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
            rows = conn.execute(
                f"""
                SELECT af.file_id FROM analysis_files af
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

    def symbols(
        self,
        source_id: Optional[str],
        role: Optional[str],
        kind: Optional[str],
        path_contains: Optional[str],
        name_contains: Optional[str],
        limit: int,
        offset: int,
    ) -> Dict[str, Any]:
        clauses, params = [self._current_graph_node_clause("n")], []
        if source_id:
            clauses.append("n.source_id = ?")
            params.append(source_id)
        if path_contains:
            clauses.append("af.relative_path LIKE ?")
            params.append(f"%{path_contains}%")
        if name_contains:
            clauses.append("n.name LIKE ?")
            params.append(f"%{name_contains}%")
        where = f"WHERE {' AND '.join(clauses)}"
        self.init()
        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT n.*, af.relative_path
                FROM analysis_graph_nodes n
                JOIN analysis_files af ON af.file_id = n.analysis_file_id
                {where}
                ORDER BY n.source_id, af.relative_path, n.line_start
            """,
                params,
            ).fetchall()
            projected = [self._graph_symbol_projection(conn, self._row_dict(row)) for row in rows]
        if kind:
            projected = [item for item in projected if item.get("kind") == kind or item.get("nodeKind") == kind]
        if role:
            projected = [item for item in projected if any(item_role.get("role") == role for item_role in item.get("roles") or [])]
        total = len(projected)
        return {"symbols": projected[offset : offset + limit], "total": total, "limit": limit, "offset": offset}

    def relations(
        self, source_id: Optional[str], relation: Optional[str], from_symbol_id: Optional[str], to_symbol_id: Optional[str], limit: int, offset: int
    ) -> Dict[str, Any]:
        clauses, params = [self._current_graph_edge_clause("e")], []
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
        where = f"WHERE {' AND '.join(clauses)}"
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges e {where}", params).fetchone()["count"]
            rows = conn.execute(
                f"SELECT e.* FROM analysis_graph_edges e {where} ORDER BY e.source_id, e.edge_type LIMIT ? OFFSET ?", [*params, limit, offset]
            ).fetchall()
            projected = [self._graph_relation_projection(conn, self._row_dict(row)) for row in rows]
        return {"relations": projected, "total": total, "limit": limit, "offset": offset}

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
            node_where, node_params = self._graph_snapshot_node_where(source_id, flow_domain, fact_origin, node_kind, include_external, include_isolated)
            edge_where, edge_params = self._graph_snapshot_edge_where(source_id, flow_domain, fact_origin, edge_type, include_unresolved)
            node_count = int(conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {node_where}", node_params).fetchone()["count"] or 0)
            edge_count = int(conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {edge_where}", edge_params).fetchone()["count"] or 0)
            node_max = conn.execute(f"SELECT MAX(created_at) AS value FROM analysis_graph_nodes n WHERE {node_where}", node_params).fetchone()["value"]
            edge_max = conn.execute(f"SELECT MAX(created_at) AS value FROM analysis_graph_edges e WHERE {edge_where}", edge_params).fetchone()["value"]
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
            revision = self._graph_snapshot_revision(
                source_id,
                flow_domain,
                fact_origin,
                node_kind,
                edge_type,
                include_external,
                include_unresolved,
                include_isolated,
                node_count,
                edge_count,
                node_max,
                edge_max,
            )
            return {
                "graphRevision": revision,
                "sourceId": source_id,
                "sourceName": self._graph_source_name(conn, source_id),
                "flowDomain": flow_domain,
                "filters": {
                    "factOrigin": fact_origin,
                    "nodeKind": node_kind,
                    "edgeType": edge_type,
                    "includeExternal": include_external,
                    "includeUnresolved": include_unresolved,
                    "includeIsolated": include_isolated,
                },
                "totalNodeCount": node_count,
                "totalEdgeCount": edge_count,
                "connectedComponentCount": None,
                "largestComponentNodeCount": None,
                "largestComponentEdgeCount": None,
                "nodeTypeCounts": node_types,
                "edgeTypeCounts": edge_types,
                "defaultNodePageSize": default_node_page_size,
                "defaultEdgePageSize": default_edge_page_size,
                "etag": self._graph_snapshot_etag(revision),
                "generatedAt": datetime.now(timezone.utc).isoformat(),
                "status": self._graph_status(conn, source_id),
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
        manifest = self.graph_snapshot_manifest(
            source_id,
            flow_domain,
            fact_origin=fact_origin,
            node_kind=node_kind,
            include_external=include_external,
            include_unresolved=include_unresolved,
            include_isolated=include_isolated,
        )
        self._assert_graph_snapshot_revision(graph_revision, manifest["graphRevision"])
        cursor_value = self._decode_graph_snapshot_cursor(cursor, graph_revision, "nodes")
        safe_page_size = max(1, min(int(page_size or manifest["defaultNodePageSize"]), 5000))
        with self._connect() as conn:
            where, params = self._graph_snapshot_node_where(source_id, flow_domain, fact_origin, node_kind, include_external, include_isolated)
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
                    GROUP BY from_node_id
                ) out_degree ON out_degree.node_id = n.id
                LEFT JOIN (
                    SELECT to_node_id AS node_id, COUNT(*) AS count
                    FROM analysis_graph_edges
                    GROUP BY to_node_id
                ) in_degree ON in_degree.node_id = n.id
                LEFT JOIN analysis_graph_claims entry
                  ON entry.node_id = n.id
                 AND entry.claim_kind = 'ENTRYPOINT_HINT'
                 AND entry.status IN ('TRUSTED', 'DERIVED')
                WHERE {where}
                ORDER BY n.id
                LIMIT ?
                """,
                [*params, safe_page_size + 1],
            ).fetchall()
        items = [self._graph_snapshot_node_projection(self._row_dict(row)) for row in rows[:safe_page_size]]
        complete = len(rows) <= safe_page_size
        next_cursor = None if complete or not items else self._encode_graph_snapshot_cursor(graph_revision, "nodes", items[-1]["id"])
        return {"graphRevision": graph_revision, "items": items, "nextCursor": next_cursor, "complete": complete, "returnedCount": len(items)}

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
        manifest = self.graph_snapshot_manifest(
            source_id,
            flow_domain,
            fact_origin=fact_origin,
            edge_type=edge_type,
            include_external=include_external,
            include_unresolved=include_unresolved,
        )
        self._assert_graph_snapshot_revision(graph_revision, manifest["graphRevision"])
        cursor_value = self._decode_graph_snapshot_cursor(cursor, graph_revision, "edges")
        safe_page_size = max(1, min(int(page_size or manifest["defaultEdgePageSize"]), 5000))
        with self._connect() as conn:
            where, params = self._graph_snapshot_edge_where(source_id, flow_domain, fact_origin, edge_type, include_unresolved)
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
                LEFT JOIN analysis_graph_nodes fn ON fn.id = e.from_node_id
                LEFT JOIN analysis_graph_nodes tn ON tn.id = e.to_node_id
                WHERE {where}
                ORDER BY e.id
                LIMIT ?
                """,
                [*params, safe_page_size + 1],
            ).fetchall()
        items = [self._graph_snapshot_edge_projection(self._row_dict(row)) for row in rows[:safe_page_size]]
        complete = len(rows) <= safe_page_size
        next_cursor = None if complete or not items else self._encode_graph_snapshot_cursor(graph_revision, "edges", items[-1]["id"])
        return {"graphRevision": graph_revision, "items": items, "nextCursor": next_cursor, "complete": complete, "returnedCount": len(items)}

    def graph(
        self,
        source_id: Optional[str],
        graph_node_id: Optional[str],
        graph_edge_id: Optional[str],
        inventory_file_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        edge_type: Optional[str],
        depth: int,
        limit: int,
        include_evidence: bool,
        include_diagnostics: bool,
        include_claims: bool = True,
    ) -> Dict[str, Any]:
        self.init()
        safe_depth = max(0, min(int(2 if depth is None else depth), 4))
        requested_limit = int(limit if limit is not None else 150)
        unlimited = requested_limit <= 0
        node_limit = 1_000_000 if unlimited else max(1, min(requested_limit, 500))
        edge_limit = 1_000_000 if unlimited else min(node_limit * 2, 1000)
        with self._connect() as conn:
            return self._fact_graph(
                conn,
                source_id,
                graph_node_id,
                graph_edge_id,
                inventory_file_id,
                flow_domain,
                fact_origin,
                node_kind,
                edge_type,
                safe_depth,
                node_limit,
                edge_limit,
                include_evidence,
                include_diagnostics,
                include_claims,
            )

    def _fact_graph(
        self,
        conn: sqlite3.Connection,
        source_id: Optional[str],
        graph_node_id: Optional[str],
        graph_edge_id: Optional[str],
        inventory_file_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        edge_type: Optional[str],
        depth: int,
        node_limit: int,
        edge_limit: int,
        include_evidence: bool,
        include_diagnostics: bool,
        include_claims: bool = True,
    ) -> Dict[str, Any]:
        diagnostics: List[Dict[str, Any]] = []
        total_node_count = self._fact_total_nodes(conn, source_id)
        total_edge_count = self._fact_total_edges(conn, source_id)
        if graph_node_id:
            node_rows, edge_rows, not_found = self._fact_slice_by_node(conn, source_id, graph_node_id, depth, node_limit + 1, edge_limit + 1, edge_type)
            if not_found:
                diagnostics.append(not_found)
        elif graph_edge_id:
            node_rows, edge_rows, not_found = self._fact_slice_by_edge(conn, source_id, graph_edge_id, depth, node_limit + 1, edge_limit + 1, edge_type)
            if not_found:
                diagnostics.append(not_found)
        else:
            node_rows, edge_rows = self._fact_overview(conn, source_id, inventory_file_id, node_limit + 1, edge_limit + 1, edge_type)

        node_rows = [row for row in node_rows if self._fact_node_matches(row, flow_domain, fact_origin, node_kind)]
        node_rows = node_rows[: node_limit + 1]
        node_id_set = {row["id"] for row in node_rows[:node_limit]}
        matched_edge_rows = [row for row in edge_rows if self._fact_edge_matches(row, flow_domain, fact_origin, edge_type)]
        drawable_edge_rows = [row for row in matched_edge_rows if row.get("from_node_id") in node_id_set and row.get("to_node_id") in node_id_set]
        skipped_missing_endpoint_count = len(matched_edge_rows) - len(drawable_edge_rows)
        skipped_by_limit_count = max(0, len(drawable_edge_rows) - edge_limit)
        selected_node_row = next((row for row in node_rows if row["id"] == graph_node_id), None)
        selected_edge_row = next((row for row in drawable_edge_rows if row["id"] == graph_edge_id), None)
        source_name = self._graph_source_name(conn, source_id)
        status = self._graph_status(conn, source_id)
        if include_diagnostics:
            diagnostics.extend(self._graph_source_diagnostics(conn, source_id))
        node_limited = len(node_rows) > node_limit
        truncated = node_limited or skipped_missing_endpoint_count > 0 or skipped_by_limit_count > 0
        node_rows = node_rows[:node_limit]
        edge_rows = drawable_edge_rows[:edge_limit]
        node_views = [self._fact_node(conn, row, include_evidence, include_diagnostics, include_claims) for row in node_rows]
        node_by_id = {node["id"]: node for node in node_views}
        edge_views = [self._fact_edge(conn, row, node_by_id, include_evidence) for row in edge_rows]
        claims = self._fact_claims(node_views) if include_claims else []
        evidence = self._fact_evidence(node_views, edge_views) if include_evidence else []
        return {
            "sourceId": source_id,
            "sourceName": source_name,
            "status": status,
            "filters": {
                "flowDomain": flow_domain,
                "factOrigin": fact_origin,
                "nodeKind": node_kind,
                "edgeType": edge_type,
                "depth": depth,
                "limit": node_limit,
                "includeEvidence": include_evidence,
                "includeClaims": include_claims,
                "includeDiagnostics": include_diagnostics,
            },
            "nodes": node_views,
            "edges": edge_views,
            "claims": claims,
            "evidence": evidence,
            "selected": {
                "node": node_by_id.get(selected_node_row["id"]) if selected_node_row is not None else None,
                "edge": next((edge for edge in edge_views if edge["id"] == selected_edge_row["id"]), None) if selected_edge_row is not None else None,
            },
            "diagnostics": diagnostics[:50],
            "meta": {
                "truncated": truncated,
                "totalNodeCount": total_node_count,
                "totalEdgeCount": total_edge_count,
                "returnedNodeCount": len(node_views),
                "returnedEdgeCount": len(edge_views),
                "skippedEdgeCount": skipped_missing_endpoint_count + skipped_by_limit_count,
                "skippedMissingEndpointCount": skipped_missing_endpoint_count,
                "skippedByLimitCount": skipped_by_limit_count,
                "truncationReason": self._graph_truncation_reason(
                    node_limited,
                    skipped_missing_endpoint_count,
                    skipped_by_limit_count,
                ),
                "maxNodeLimit": 500,
                "maxEdgeLimit": 1000,
            },
        }

    def _graph_truncation_reason(self, node_limited: bool, skipped_missing_endpoint_count: int, skipped_by_limit_count: int) -> Optional[str]:
        reasons: List[str] = []
        if node_limited:
            reasons.append("NODE_LIMIT")
        if skipped_missing_endpoint_count > 0:
            reasons.append("EDGE_ENDPOINT_NOT_RETURNED")
        if skipped_by_limit_count > 0:
            reasons.append("EDGE_LIMIT")
        return ",".join(reasons) if reasons else None

    def _fact_total_nodes(self, conn: sqlite3.Connection, source_id: Optional[str]) -> int:
        clauses = [self._current_graph_node_clause("n")]
        params: List[Any] = []
        if source_id:
            clauses.append("n.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_nodes n WHERE {' AND '.join(clauses)}", params).fetchone()
        return int(row["count"] or 0)

    def _fact_total_edges(self, conn: sqlite3.Connection, source_id: Optional[str]) -> int:
        clauses = [self._current_graph_edge_clause("e")]
        params: List[Any] = []
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges e WHERE {' AND '.join(clauses)}", params).fetchone()
        return int(row["count"] or 0)

    def _fact_overview(
        self, conn: sqlite3.Connection, source_id: Optional[str], inventory_file_id: Optional[str], node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        clauses = [self._current_graph_node_clause("n")]
        params: List[Any] = []
        if source_id:
            clauses.append("n.source_id = ?")
            params.append(source_id)
        file_id = self._graph_inventory_file_id(inventory_file_id)
        if file_id is not None:
            clauses.append("n.inventory_file_id = ?")
            params.append(file_id)
        rows = conn.execute(
            f"""
            SELECT n.*,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree
            FROM analysis_graph_nodes n
            LEFT JOIN (
                SELECT from_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY from_node_id
            ) out_degree ON out_degree.node_id = n.id
            LEFT JOIN (
                SELECT to_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY to_node_id
            ) in_degree ON in_degree.node_id = n.id
            WHERE {" AND ".join(clauses)}
            ORDER BY n.status = 'TRUSTED' DESC, n.confidence DESC, graph_degree DESC, n.source_id, n.line_start
            LIMIT ?
        """,
            [*params, node_limit],
        ).fetchall()
        node_rows = [self._row_dict(row) for row in rows]
        edge_rows = self._fact_edges_for_nodes(conn, {row["id"] for row in node_rows}, source_id, edge_limit, edge_type)
        return node_rows, edge_rows

    def _fact_slice_by_node(
        self, conn: sqlite3.Connection, source_id: Optional[str], node_id: str, depth: int, node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], Optional[Dict[str, Any]]]:
        seed = self._fact_node_by_id(conn, node_id, source_id)
        if seed is None:
            return [], [], self._graph_not_found("GRAPH_NODE_NOT_FOUND", "Selected graph node was not found.", node_id)
        node_ids: Set[str] = {node_id}
        edge_rows: Dict[str, Dict[str, Any]] = {}
        frontier: Set[str] = {node_id}
        for _ in range(depth):
            if not frontier or len(node_ids) >= node_limit or len(edge_rows) >= edge_limit:
                break
            rows = self._fact_neighbor_edges(conn, frontier, source_id, edge_limit - len(edge_rows), edge_type)
            next_frontier: Set[str] = set()
            for row in rows:
                edge_rows.setdefault(row["id"], row)
                for adjacent in (row["from_node_id"], row["to_node_id"]):
                    if adjacent and adjacent not in node_ids and len(node_ids) < node_limit:
                        node_ids.add(adjacent)
                        next_frontier.add(adjacent)
            frontier = next_frontier
        return self._fact_nodes_by_ids(conn, node_ids), list(edge_rows.values()), None

    def _fact_slice_by_edge(
        self, conn: sqlite3.Connection, source_id: Optional[str], edge_id: str, depth: int, node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], Optional[Dict[str, Any]]]:
        edge = self._fact_edge_by_id(conn, edge_id, source_id)
        if edge is None:
            return [], [], self._graph_not_found("GRAPH_EDGE_NOT_FOUND", "Selected graph edge was not found.", edge_id)
        seed_ids = {item for item in (edge["from_node_id"], edge["to_node_id"]) if item}
        node_rows = self._fact_nodes_by_ids(conn, seed_ids)
        edge_rows = {edge["id"]: edge}
        if depth > 0:
            extra_nodes, extra_edges, _ = self._fact_slice_from_seeds(conn, source_id, seed_ids, depth, node_limit, edge_limit, edge_type)
            node_rows = self._dedupe_rows([*node_rows, *extra_nodes], "id")[:node_limit]
            edge_rows.update({row["id"]: row for row in extra_edges})
        return node_rows, list(edge_rows.values())[:edge_limit], None

    def _fact_slice_from_seeds(
        self, conn: sqlite3.Connection, source_id: Optional[str], seed_ids: Set[str], depth: int, node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], None]:
        node_ids = set(seed_ids)
        edge_rows: Dict[str, Dict[str, Any]] = {}
        frontier = set(seed_ids)
        for _ in range(depth):
            if not frontier or len(node_ids) >= node_limit or len(edge_rows) >= edge_limit:
                break
            rows = self._fact_neighbor_edges(conn, frontier, source_id, edge_limit - len(edge_rows), edge_type)
            next_frontier: Set[str] = set()
            for row in rows:
                edge_rows.setdefault(row["id"], row)
                for adjacent in (row["from_node_id"], row["to_node_id"]):
                    if adjacent and adjacent not in node_ids and len(node_ids) < node_limit:
                        node_ids.add(adjacent)
                        next_frontier.add(adjacent)
            frontier = next_frontier
        return self._fact_nodes_by_ids(conn, node_ids), list(edge_rows.values()), None

    def _fact_node_by_id(self, conn: sqlite3.Connection, node_id: str, source_id: Optional[str]) -> Optional[Dict[str, Any]]:
        clauses = [self._current_graph_node_clause("n"), "n.id = ?"]
        params: List[Any] = [node_id]
        if source_id:
            clauses.append("n.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT n.*, 0 AS graph_degree FROM analysis_graph_nodes n WHERE {' AND '.join(clauses)}", params).fetchone()
        return self._row_dict(row) if row else None

    def _fact_edge_by_id(self, conn: sqlite3.Connection, edge_id: str, source_id: Optional[str]) -> Optional[Dict[str, Any]]:
        clauses = [self._current_graph_edge_clause("e"), "e.id = ?"]
        params: List[Any] = [edge_id]
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT e.* FROM analysis_graph_edges e WHERE {' AND '.join(clauses)}", params).fetchone()
        return self._row_dict(row) if row else None

    def _fact_nodes_by_ids(self, conn: sqlite3.Connection, node_ids: Set[str]) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        placeholders = ",".join("?" for _ in node_ids)
        rows = conn.execute(
            f"""
            SELECT n.*,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree
            FROM analysis_graph_nodes n
            LEFT JOIN (
                SELECT from_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY from_node_id
            ) out_degree ON out_degree.node_id = n.id
            LEFT JOIN (
                SELECT to_node_id AS node_id, COUNT(*) AS count
                FROM analysis_graph_edges
                GROUP BY to_node_id
            ) in_degree ON in_degree.node_id = n.id
            WHERE {self._current_graph_node_clause("n")}
              AND n.id IN ({placeholders})
            ORDER BY n.source_id, n.analysis_file_id, n.line_start
        """,
            list(node_ids),
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _fact_neighbor_edges(
        self, conn: sqlite3.Connection, frontier: Set[str], source_id: Optional[str], limit: int, edge_type: Optional[str]
    ) -> List[Dict[str, Any]]:
        if not frontier or limit <= 0:
            return []
        placeholders = ",".join("?" for _ in frontier)
        clauses = [self._current_graph_edge_clause("e"), f"(e.from_node_id IN ({placeholders}) OR e.to_node_id IN ({placeholders}))"]
        params: List[Any] = [*frontier, *frontier]
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        if edge_type:
            clauses.append("e.edge_type = ?")
            params.append(edge_type)
        rows = conn.execute(
            f"""
            SELECT e.*
            FROM analysis_graph_edges e
            WHERE {" AND ".join(clauses)}
            ORDER BY e.status = 'TRUSTED' DESC, e.confidence DESC, e.edge_type
            LIMIT ?
        """,
            [*params, limit],
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _fact_edges_for_nodes(
        self, conn: sqlite3.Connection, node_ids: Set[str], source_id: Optional[str], limit: int, edge_type: Optional[str]
    ) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        placeholders = ",".join("?" for _ in node_ids)
        clauses = [self._current_graph_edge_clause("e"), f"e.from_node_id IN ({placeholders})", f"(e.to_node_id IS NULL OR e.to_node_id IN ({placeholders}))"]
        params: List[Any] = [*node_ids, *node_ids]
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        if edge_type:
            clauses.append("e.edge_type = ?")
            params.append(edge_type)
        rows = conn.execute(
            f"""
            SELECT e.*
            FROM analysis_graph_edges e
            WHERE {" AND ".join(clauses)}
            ORDER BY e.status = 'TRUSTED' DESC, e.confidence DESC, e.edge_type
            LIMIT ?
        """,
            [*params, limit],
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _current_graph_node_clause(self, alias: str) -> str:
        return f"""EXISTS (
            SELECT 1
            FROM analysis_files af
            JOIN files f ON f.id = af.file_id
            WHERE af.file_id = {alias}.analysis_file_id
              AND af.status = 'ANALYZED'
              AND f.content_hash = af.content_hash
        )"""

    def _current_graph_edge_clause(self, alias: str) -> str:
        return f"""EXISTS (
            SELECT 1
            FROM analysis_graph_nodes fn
            JOIN analysis_files faf ON faf.file_id = fn.analysis_file_id AND faf.status = 'ANALYZED'
            JOIN files ff ON ff.id = faf.file_id AND ff.content_hash = faf.content_hash
            LEFT JOIN analysis_graph_nodes tn ON tn.id = {alias}.to_node_id
            LEFT JOIN analysis_files taf ON taf.file_id = tn.analysis_file_id AND taf.status = 'ANALYZED'
            LEFT JOIN files tf ON tf.id = taf.file_id AND tf.content_hash = taf.content_hash
            WHERE fn.id = {alias}.from_node_id
              AND ({alias}.to_node_id IS NULL OR tf.id IS NOT NULL)
        )"""

    def _graph_snapshot_node_where(
        self,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        include_external: str,
        include_isolated: bool,
    ) -> tuple[str, List[Any]]:
        clauses = [self._current_graph_node_clause("n")]
        params: List[Any] = []
        if source_id:
            clauses.append("n.source_id = ?")
            params.append(source_id)
        if flow_domain:
            clauses.append("n.flow_domain = ?")
            params.append(flow_domain.upper())
        if fact_origin:
            clauses.append("n.fact_origin = ?")
            params.append(fact_origin.upper())
        if node_kind:
            clauses.append("n.node_kind = ?")
            params.append(node_kind.upper())
        if str(include_external or "show").lower() == "hide":
            clauses.append("n.node_kind != 'EXTERNAL'")
        if not include_isolated:
            clauses.append(
                """EXISTS (
                    SELECT 1
                    FROM analysis_graph_edges ge
                    WHERE ge.from_node_id = n.id OR ge.to_node_id = n.id
                )"""
            )
        return " AND ".join(clauses), params

    def _graph_snapshot_edge_where(
        self,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        edge_type: Optional[str],
        include_unresolved: bool,
    ) -> tuple[str, List[Any]]:
        clauses = [self._current_graph_edge_clause("e")]
        params: List[Any] = []
        if source_id:
            clauses.append("e.source_id = ?")
            params.append(source_id)
        if flow_domain:
            clauses.append("e.flow_domain = ?")
            params.append(flow_domain.upper())
        if fact_origin:
            clauses.append("e.fact_origin = ?")
            params.append(fact_origin.upper())
        if edge_type:
            clauses.append("e.edge_type = ?")
            params.append(edge_type.upper())
        if not include_unresolved:
            clauses.append("e.to_node_id IS NOT NULL")
            clauses.append("e.resolution_status NOT IN ('UNRESOLVED', 'DYNAMIC_TARGET', 'EXTERNAL_TARGET')")
        return " AND ".join(clauses), params

    def _graph_snapshot_revision(
        self,
        source_id: Optional[str],
        flow_domain: Optional[str],
        fact_origin: Optional[str],
        node_kind: Optional[str],
        edge_type: Optional[str],
        include_external: str,
        include_unresolved: bool,
        include_isolated: bool,
        node_count: int,
        edge_count: int,
        node_max_created_at: Optional[str],
        edge_max_created_at: Optional[str],
    ) -> str:
        parts = [
            source_id or "all",
            flow_domain or "all",
            fact_origin or "all",
            node_kind or "all",
            edge_type or "all",
            str(include_external or "show"),
            "unresolved" if include_unresolved else "resolved-only",
            "isolated" if include_isolated else "connected-only",
            str(node_count),
            str(edge_count),
            node_max_created_at or "-",
            edge_max_created_at or "-",
        ]
        token = base64.urlsafe_b64encode("|".join(parts).encode("utf-8")).decode("ascii").rstrip("=")
        return f"{source_id or 'all'}:{flow_domain or 'ALL'}:graph-v1:{token}"

    def _graph_snapshot_etag(self, graph_revision: str) -> str:
        return f'"{base64.urlsafe_b64encode(graph_revision.encode("utf-8")).decode("ascii").rstrip("=")}"'

    def _assert_graph_snapshot_revision(self, requested: str, current: str) -> None:
        if not requested:
            raise KnowledgeError("GRAPH_SNAPSHOT_REVISION_REQUIRED", "graphRevision is required.")
        if requested != current:
            raise KnowledgeError("GRAPH_SNAPSHOT_STALE", "Graph snapshot revision is stale.", requested=requested, current=current)

    def _encode_graph_snapshot_cursor(self, graph_revision: str, page_kind: str, last_id: str) -> str:
        payload = {"graphRevision": graph_revision, "kind": page_kind, "lastId": last_id}
        return base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode("utf-8")).decode("ascii").rstrip("=")

    def _decode_graph_snapshot_cursor(self, cursor: Optional[str], graph_revision: str, page_kind: str) -> Optional[str]:
        if not cursor:
            return None
        try:
            padded = cursor + ("=" * (-len(cursor) % 4))
            payload = json.loads(base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8"))
        except (ValueError, TypeError, json.JSONDecodeError):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph snapshot cursor is invalid.")
        if payload.get("graphRevision") != graph_revision or payload.get("kind") != page_kind or not payload.get("lastId"):
            raise KnowledgeError("GRAPH_CURSOR_INVALID", "Graph snapshot cursor does not match this snapshot request.")
        return str(payload["lastId"])

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

    def _fact_node_matches(self, row: Dict[str, Any], flow_domain: Optional[str], fact_origin: Optional[str], node_kind: Optional[str]) -> bool:
        if flow_domain and str(row.get("flow_domain") or "").upper() != flow_domain.upper():
            return False
        if fact_origin and str(row.get("fact_origin") or "").upper() != fact_origin.upper():
            return False
        if node_kind and str(row.get("node_kind") or "").upper() != node_kind.upper():
            return False
        return True

    def _fact_edge_matches(self, row: Dict[str, Any], flow_domain: Optional[str], fact_origin: Optional[str], edge_type: Optional[str]) -> bool:
        if flow_domain and str(row.get("flow_domain") or "").upper() != flow_domain.upper():
            return False
        if fact_origin and str(row.get("fact_origin") or "").upper() != fact_origin.upper():
            return False
        if edge_type and str(row.get("edge_type") or "").upper() != edge_type.upper():
            return False
        return True

    def _fact_node(
        self, conn: sqlite3.Connection, row: Dict[str, Any], include_evidence: bool, include_diagnostics: bool, include_claims: bool = True
    ) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        claims = [self._fact_claim_view(conn, claim, include_evidence) for claim in self._fact_claim_rows(conn, row["id"])] if include_claims else []
        summary = self._fact_summary(conn, row)
        diagnostics = self._fact_file_diagnostics(conn, row.get("analysis_file_id")) if include_diagnostics else []
        view = {
            "id": row["id"],
            "graphNodeId": row["id"],
            "stableKey": row.get("stable_key") or row["id"],
            "label": row.get("display_name") or row.get("qualified_name") or row.get("name") or row["id"],
            "qualifiedName": row.get("qualified_name") or row.get("name"),
            "nodeKind": row.get("node_kind"),
            "sourceKind": metadata.get("sourceKind"),
            "flowDomain": row.get("flow_domain"),
            "factOrigin": row.get("fact_origin"),
            "status": row.get("status"),
            "confidence": row.get("confidence"),
            "sourceId": row.get("source_id"),
            "relativePath": self._fact_relative_path(conn, row.get("analysis_file_id")),
            "lineStart": row.get("line_start"),
            "lineEnd": row.get("line_end"),
            "parentNodeId": row.get("parent_node_id"),
            "claimSummary": summary.get("summary"),
            "responsibilitySummary": summary.get("summary"),
            "summarySource": summary.get("summarySource"),
            "summaryClaimId": summary.get("summaryClaimId"),
            "summaryClaimNodeId": summary.get("summaryClaimNodeId"),
            "summaryConfidence": summary.get("summaryConfidence"),
            "summaryEvidenceCount": summary.get("summaryEvidenceCount"),
            "roles": [self._role_from_claim(claim) for claim in claims if claim.get("claimKind") == "ROLE"],
            "claims": claims,
            "evidenceCount": sum(claim.get("evidenceCount") or 0 for claim in claims),
            "diagnosticCount": len(diagnostics),
            "degree": int(row.get("graph_degree") or 0),
            "metadata": metadata,
        }
        if include_evidence:
            view["evidence"] = [item for claim in claims for item in claim.get("evidence") or []]
        if include_diagnostics:
            view["diagnostics"] = diagnostics
        return view

    def _fact_edge(self, conn: sqlite3.Connection, row: Dict[str, Any], node_by_id: Dict[str, Dict[str, Any]], include_evidence: bool) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        evidence = self._fact_evidence_by_id(conn, row.get("evidence_id")) if row.get("evidence_id") else None
        unresolved_target = None
        if row.get("unresolved_target_json"):
            try:
                unresolved_target = json.loads(row.get("unresolved_target_json"))
            except (TypeError, json.JSONDecodeError):
                unresolved_target = row.get("unresolved_target_json")
        view = {
            "id": row["id"],
            "graphEdgeId": row["id"],
            "stableKey": metadata.get("stableKey") or row["id"],
            "from": row["from_node_id"],
            "to": row.get("to_node_id"),
            "fromLabel": node_by_id.get(row["from_node_id"], {}).get("label") or row["from_node_id"],
            "toLabel": node_by_id.get(row.get("to_node_id"), {}).get("label") if row.get("to_node_id") else None,
            "edgeType": row.get("edge_type"),
            "relation": row.get("edge_type"),
            "resolutionStatus": row.get("resolution_status"),
            "unresolvedTarget": unresolved_target,
            "flowDomain": row.get("flow_domain"),
            "factOrigin": row.get("fact_origin"),
            "status": row.get("status"),
            "confidence": row.get("confidence"),
            "evidenceCount": 1 if evidence else 0,
            "diagnosticCount": 0,
            "lineStart": evidence.get("lineStart") if evidence else None,
            "lineEnd": evidence.get("lineEnd") if evidence else None,
            "metadata": metadata,
        }
        if include_evidence and evidence:
            evidence["edgeId"] = row["id"]
            view["evidence"] = [evidence]
        return view

    def _fact_claim_rows(self, conn: sqlite3.Connection, node_id: str) -> List[Dict[str, Any]]:
        rows = conn.execute(
            """
            SELECT *
            FROM analysis_graph_claims
            WHERE node_id = ?
            ORDER BY claim_kind = 'RESPONSIBILITY' DESC, status = 'TRUSTED' DESC, confidence DESC
        """,
            (node_id,),
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _fact_claim_view(self, conn: sqlite3.Connection, row: Dict[str, Any], include_evidence: bool) -> Dict[str, Any]:
        evidence_ids = self._json_list(row.get("evidence_ids_json"))
        evidence = [self._fact_evidence_by_id(conn, evidence_id) for evidence_id in evidence_ids]
        evidence = [item for item in evidence if item is not None]
        view = {
            "id": row["id"],
            "nodeId": row["node_id"],
            "type": row.get("claim_kind"),
            "claimKind": row.get("claim_kind"),
            "summary": row.get("summary"),
            "confidence": row.get("confidence"),
            "status": row.get("status"),
            "evidenceCount": len(evidence_ids),
            "rejectionReason": row.get("rejection_reason"),
            "metadata": self._json_dict(row.get("metadata_json")),
        }
        if include_evidence:
            view["evidence"] = evidence
        return view

    def _fact_summary(self, conn: sqlite3.Connection, row: Dict[str, Any]) -> Dict[str, Any]:
        direct = self._responsibility_claim(conn, row["id"])
        if direct:
            return self._summary_from_claim(direct, "DIRECT")
        parent_id = row.get("parent_node_id")
        if parent_id:
            parent = self._responsibility_claim(conn, parent_id)
            if parent:
                return self._summary_from_claim(parent, "PARENT_FALLBACK")
        file_row = conn.execute(
            """
            SELECT id
            FROM analysis_graph_nodes
            WHERE analysis_file_id = ?
              AND node_kind = 'FILE'
            ORDER BY confidence DESC
            LIMIT 1
        """,
            (row.get("analysis_file_id"),),
        ).fetchone()
        if file_row:
            file_claim = self._responsibility_claim(conn, file_row["id"])
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

    def _responsibility_claim(self, conn: sqlite3.Connection, node_id: str) -> Optional[Dict[str, Any]]:
        row = conn.execute(
            """
            SELECT *
            FROM analysis_graph_claims
            WHERE node_id = ?
              AND claim_kind = 'RESPONSIBILITY'
              AND status IN ('TRUSTED', 'LOW_CONFIDENCE')
            ORDER BY status = 'TRUSTED' DESC, confidence DESC
            LIMIT 1
        """,
            (node_id,),
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

    def _fact_evidence_by_id(self, conn: sqlite3.Connection, evidence_id: Optional[str]) -> Optional[Dict[str, Any]]:
        if not evidence_id:
            return None
        row = conn.execute("SELECT * FROM analysis_graph_evidence WHERE id = ?", (evidence_id,)).fetchone()
        if row is None:
            return None
        metadata = self._json_dict(row["metadata_json"])
        return {
            "id": row["id"],
            "text": metadata.get("text") or "-",
            "sourceId": row["source_id"],
            "relativePath": self._fact_relative_path(conn, row["analysis_file_id"]),
            "lineStart": row["line_start"],
            "lineEnd": row["line_end"],
            "evidenceKind": row["evidence_kind"],
            "factOrigin": row["fact_origin"],
            "flowDomain": row["flow_domain"],
            "metadata": metadata,
        }

    def _fact_file_diagnostics(self, conn: sqlite3.Connection, file_id: Optional[int]) -> List[Dict[str, Any]]:
        if file_id is None:
            return []
        rows = conn.execute(
            """
            SELECT *
            FROM analysis_graph_diagnostics
            WHERE analysis_file_id = ?
            ORDER BY created_at DESC
            LIMIT 25
        """,
            (file_id,),
        ).fetchall()
        diagnostics = [
            {
                "severity": row["severity"],
                "stage": row["stage"],
                "code": row["code"],
                "message": row["message"],
                "sourceId": row["source_id"],
                "relativePath": self._fact_relative_path(conn, row["analysis_file_id"]),
                "metadata": self._json_dict(row["metadata_json"]),
            }
            for row in rows
        ]
        diagnostics.extend(self._graph_file_diagnostics(conn, [file_id]).get(file_id, []))
        return diagnostics

    def _fact_relative_path(self, conn: sqlite3.Connection, file_id: Optional[int]) -> Optional[str]:
        if file_id is None:
            return None
        row = conn.execute("SELECT relative_path FROM analysis_files WHERE file_id = ?", (file_id,)).fetchone()
        return row["relative_path"] if row else None

    def _role_from_claim(self, claim: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "role": claim.get("summary"),
            "confidence": claim.get("confidence"),
            "evidence": [item.get("text") for item in claim.get("evidence") or [] if item.get("text")],
            "classifier": claim.get("metadata", {}).get("analyzerName") or "graph-analysis",
            "classifierVersion": claim.get("metadata", {}).get("analyzerVersion") or "1",
        }

    def _fact_claims(self, nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        claims: List[Dict[str, Any]] = []
        for node in nodes:
            claims.extend(node.get("claims") or [])
        return claims

    def _fact_evidence(self, nodes: List[Dict[str, Any]], edges: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        evidence: List[Dict[str, Any]] = []
        for node in nodes:
            evidence.extend(node.get("evidence") or [])
        for edge in edges:
            evidence.extend(edge.get("evidence") or [])
        return evidence

    def _graph_symbol_projection(self, conn: sqlite3.Connection, row: Dict[str, Any]) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        claims = [self._fact_claim_view(conn, claim, True) for claim in self._fact_claim_rows(conn, row["id"])]
        summary = self._fact_summary(conn, row)
        source_kind = metadata.get("sourceKind") or self._source_kind_from_node_kind(row.get("node_kind"))
        return {
            "symbolId": row["id"],
            "sourceId": row["source_id"],
            "relativePath": row.get("relative_path") or self._fact_relative_path(conn, row.get("analysis_file_id")),
            "name": row["name"],
            "kind": source_kind,
            "nodeKind": row.get("node_kind"),
            "roles": [self._role_from_claim(claim) for claim in claims if claim.get("claimKind") == "ROLE"],
            "lineStart": row.get("line_start"),
            "lineEnd": row.get("line_end"),
            "summary": summary.get("summary"),
            "summarySource": summary.get("summarySource"),
            "metadata": metadata,
        }

    def _graph_relation_projection(self, conn: sqlite3.Connection, row: Dict[str, Any]) -> Dict[str, Any]:
        evidence = self._fact_evidence_by_id(conn, row.get("evidence_id")) if row.get("evidence_id") else None
        return {
            "relationId": row["id"],
            "sourceId": row["source_id"],
            "fromSymbolId": row["from_node_id"],
            "toSymbolId": row.get("to_node_id"),
            "relation": row["edge_type"],
            "confidence": row["confidence"],
            "evidence": [evidence.get("text")] if evidence and evidence.get("text") else [],
            "lineStart": evidence.get("lineStart") if evidence else None,
            "lineEnd": evidence.get("lineEnd") if evidence else None,
            "metadata": self._json_dict(row.get("metadata_json")),
        }

    def _source_kind_from_node_kind(self, node_kind: Optional[str]) -> str:
        value = str(node_kind or "UNKNOWN").upper()
        if value == "TYPE":
            return "CLASS"
        if value == "CALLABLE":
            return "METHOD"
        if value == "CONFIG":
            return "CONFIG_ENTRY"
        return value

    def _graph_total_nodes(self, conn: sqlite3.Connection, source_id: Optional[str]) -> int:
        clauses = [self._current_symbol_clause("s")]
        params: List[Any] = []
        if source_id:
            clauses.append("s.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_symbols s WHERE {' AND '.join(clauses)}", params).fetchone()
        return int(row["count"] or 0)

    def _graph_total_edges(self, conn: sqlite3.Connection, source_id: Optional[str]) -> int:
        clauses = [self._current_relation_clause("r")]
        params: List[Any] = []
        if source_id:
            clauses.append("r.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_relations r WHERE {' AND '.join(clauses)}", params).fetchone()
        return int(row["count"] or 0)

    def _graph_overview(
        self, conn: sqlite3.Connection, source_id: Optional[str], inventory_file_id: Optional[str], node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        clauses = [self._current_symbol_clause("s")]
        params: List[Any] = []
        if source_id:
            clauses.append("s.source_id = ?")
            params.append(source_id)
        file_id = self._graph_inventory_file_id(inventory_file_id)
        if file_id is not None:
            clauses.append("s.file_id = ?")
            params.append(file_id)
        where = " AND ".join(clauses)
        candidate_limit = min(max(node_limit * 6, 300), 2500)
        rows = conn.execute(
            f"""
            SELECT s.*,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree
            FROM analysis_symbols s
            LEFT JOIN (
                SELECT from_symbol_id AS symbol_id, COUNT(*) AS count
                FROM analysis_relations
                GROUP BY from_symbol_id
            ) out_degree ON out_degree.symbol_id = s.symbol_id
            LEFT JOIN (
                SELECT to_symbol_id AS symbol_id, COUNT(*) AS count
                FROM analysis_relations
                GROUP BY to_symbol_id
            ) in_degree ON in_degree.symbol_id = s.symbol_id
            WHERE {where}
            ORDER BY s.source_id, s.relative_path, s.line_start
            LIMIT ?
        """,
            [*params, candidate_limit],
        ).fetchall()
        symbol_rows = sorted(
            [self._row_dict(row) for row in rows],
            key=lambda row: self._graph_symbol_rank(row),
        )[:node_limit]
        relation_rows = self._graph_relations_for_nodes(conn, {row["symbol_id"] for row in symbol_rows}, source_id, edge_limit, edge_type)
        return symbol_rows, relation_rows

    def _graph_slice_by_node(
        self, conn: sqlite3.Connection, source_id: Optional[str], graph_node_id: str, depth: int, node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], Optional[Dict[str, Any]]]:
        seed = self._graph_symbol_by_id(conn, graph_node_id, source_id)
        if seed is None:
            return [], [], self._graph_not_found("GRAPH_NODE_NOT_FOUND", "Selected graph node was not found.", graph_node_id)
        node_ids: Set[str] = {graph_node_id}
        edge_rows: Dict[str, Dict[str, Any]] = {}
        frontier: Set[str] = {graph_node_id}
        for _ in range(depth):
            if not frontier or len(node_ids) >= node_limit or len(edge_rows) >= edge_limit:
                break
            rows = self._graph_neighbor_relations(conn, frontier, source_id, edge_limit - len(edge_rows), edge_type)
            next_frontier: Set[str] = set()
            for row in rows:
                edge_rows.setdefault(row["relation_id"], row)
                for symbol_id in (row["from_symbol_id"], row["to_symbol_id"]):
                    if symbol_id not in node_ids and len(node_ids) < node_limit:
                        node_ids.add(symbol_id)
                        next_frontier.add(symbol_id)
            frontier = next_frontier
        return self._graph_symbols_by_ids(conn, node_ids), list(edge_rows.values()), None

    def _graph_slice_by_edge(
        self, conn: sqlite3.Connection, source_id: Optional[str], graph_edge_id: str, depth: int, node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], Optional[Dict[str, Any]]]:
        relation = self._graph_relation_by_id(conn, graph_edge_id, source_id)
        if relation is None:
            return [], [], self._graph_not_found("GRAPH_EDGE_NOT_FOUND", "Selected graph edge was not found.", graph_edge_id)
        seed_ids = {relation["from_symbol_id"], relation["to_symbol_id"]}
        symbol_rows = self._graph_symbols_by_ids(conn, seed_ids)
        relation_rows = {relation["relation_id"]: relation}
        if depth > 0:
            extra_symbols, extra_relations, _ = self._graph_slice_from_seeds(conn, source_id, seed_ids, depth, node_limit, edge_limit, edge_type)
            symbol_rows = self._dedupe_rows([*symbol_rows, *extra_symbols], "symbol_id")[:node_limit]
            relation_rows.update({row["relation_id"]: row for row in extra_relations})
        return symbol_rows, list(relation_rows.values())[:edge_limit], None

    def _graph_slice_from_seeds(
        self, conn: sqlite3.Connection, source_id: Optional[str], seed_ids: Set[str], depth: int, node_limit: int, edge_limit: int, edge_type: Optional[str]
    ) -> tuple[List[Dict[str, Any]], List[Dict[str, Any]], None]:
        node_ids: Set[str] = set(seed_ids)
        edge_rows: Dict[str, Dict[str, Any]] = {}
        frontier = set(seed_ids)
        for _ in range(depth):
            if not frontier or len(node_ids) >= node_limit or len(edge_rows) >= edge_limit:
                break
            rows = self._graph_neighbor_relations(conn, frontier, source_id, edge_limit - len(edge_rows), edge_type)
            next_frontier: Set[str] = set()
            for row in rows:
                edge_rows.setdefault(row["relation_id"], row)
                for symbol_id in (row["from_symbol_id"], row["to_symbol_id"]):
                    if symbol_id not in node_ids and len(node_ids) < node_limit:
                        node_ids.add(symbol_id)
                        next_frontier.add(symbol_id)
            frontier = next_frontier
        return self._graph_symbols_by_ids(conn, node_ids), list(edge_rows.values()), None

    def _graph_neighbor_relations(
        self, conn: sqlite3.Connection, frontier: Set[str], source_id: Optional[str], limit: int, edge_type: Optional[str]
    ) -> List[Dict[str, Any]]:
        if not frontier or limit <= 0:
            return []
        placeholders = ",".join("?" for _ in frontier)
        clauses = [self._current_relation_clause("r"), f"(r.from_symbol_id IN ({placeholders}) OR r.to_symbol_id IN ({placeholders}))"]
        params: List[Any] = [*frontier, *frontier]
        if source_id:
            clauses.append("r.source_id = ?")
            params.append(source_id)
        if edge_type:
            clauses.append("r.relation = ?")
            params.append(edge_type)
        rows = conn.execute(
            f"""
            SELECT r.*
            FROM analysis_relations r
            WHERE {" AND ".join(clauses)}
            ORDER BY r.confidence DESC, r.relation, r.line_start
            LIMIT ?
        """,
            [*params, limit],
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _graph_relations_for_nodes(
        self, conn: sqlite3.Connection, node_ids: Set[str], source_id: Optional[str], limit: int, edge_type: Optional[str]
    ) -> List[Dict[str, Any]]:
        if not node_ids:
            return []
        placeholders = ",".join("?" for _ in node_ids)
        clauses = [self._current_relation_clause("r"), f"r.from_symbol_id IN ({placeholders})", f"r.to_symbol_id IN ({placeholders})"]
        params: List[Any] = [*node_ids, *node_ids]
        if source_id:
            clauses.append("r.source_id = ?")
            params.append(source_id)
        if edge_type:
            clauses.append("r.relation = ?")
            params.append(edge_type)
        rows = conn.execute(
            f"""
            SELECT r.*
            FROM analysis_relations r
            WHERE {" AND ".join(clauses)}
            ORDER BY r.confidence DESC, r.relation, r.line_start
            LIMIT ?
        """,
            [*params, limit],
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _graph_symbol_by_id(self, conn: sqlite3.Connection, symbol_id: str, source_id: Optional[str]) -> Optional[Dict[str, Any]]:
        clauses = [self._current_symbol_clause("s"), "s.symbol_id = ?"]
        params: List[Any] = [symbol_id]
        if source_id:
            clauses.append("s.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT s.*, 0 AS graph_degree FROM analysis_symbols s WHERE {' AND '.join(clauses)}", params).fetchone()
        return self._row_dict(row) if row else None

    def _graph_relation_by_id(self, conn: sqlite3.Connection, relation_id: str, source_id: Optional[str]) -> Optional[Dict[str, Any]]:
        clauses = [self._current_relation_clause("r"), "r.relation_id = ?"]
        params: List[Any] = [relation_id]
        if source_id:
            clauses.append("r.source_id = ?")
            params.append(source_id)
        row = conn.execute(f"SELECT r.* FROM analysis_relations r WHERE {' AND '.join(clauses)}", params).fetchone()
        return self._row_dict(row) if row else None

    def _graph_symbols_by_ids(self, conn: sqlite3.Connection, symbol_ids: Set[str]) -> List[Dict[str, Any]]:
        if not symbol_ids:
            return []
        placeholders = ",".join("?" for _ in symbol_ids)
        rows = conn.execute(
            f"""
            SELECT s.*,
                   COALESCE(out_degree.count, 0) + COALESCE(in_degree.count, 0) AS graph_degree
            FROM analysis_symbols s
            LEFT JOIN (
                SELECT from_symbol_id AS symbol_id, COUNT(*) AS count
                FROM analysis_relations
                GROUP BY from_symbol_id
            ) out_degree ON out_degree.symbol_id = s.symbol_id
            LEFT JOIN (
                SELECT to_symbol_id AS symbol_id, COUNT(*) AS count
                FROM analysis_relations
                GROUP BY to_symbol_id
            ) in_degree ON in_degree.symbol_id = s.symbol_id
            WHERE {self._current_symbol_clause("s")}
              AND s.symbol_id IN ({placeholders})
            ORDER BY s.source_id, s.relative_path, s.line_start
        """,
            list(symbol_ids),
        ).fetchall()
        return [self._row_dict(row) for row in rows]

    def _current_symbol_clause(self, alias: str) -> str:
        return f"""EXISTS (
            SELECT 1
            FROM analysis_files af
            JOIN files f ON f.id = af.file_id
            WHERE af.file_id = {alias}.file_id
              AND af.status = 'ANALYZED'
              AND f.content_hash = af.content_hash
        )"""

    def _current_relation_clause(self, alias: str) -> str:
        return f"""EXISTS (
            SELECT 1
            FROM analysis_symbols fs
            JOIN analysis_symbols ts ON ts.symbol_id = {alias}.to_symbol_id
            JOIN analysis_files faf ON faf.file_id = fs.file_id AND faf.status = 'ANALYZED'
            JOIN analysis_files taf ON taf.file_id = ts.file_id AND taf.status = 'ANALYZED'
            JOIN files ff ON ff.id = faf.file_id AND ff.content_hash = faf.content_hash
            JOIN files tf ON tf.id = taf.file_id AND tf.content_hash = taf.content_hash
            WHERE fs.symbol_id = {alias}.from_symbol_id
        )"""

    def _graph_inventory_file_id(self, value: Optional[str]) -> Optional[int]:
        if value is None or str(value).strip() == "":
            return None
        try:
            return int(value)
        except ValueError:
            return -1

    def _graph_symbol_rank(self, row: Dict[str, Any]) -> tuple[int, int, int, str, int]:
        kind_rank = {
            "CALLABLE": 0,
            "TYPE": 1,
            "CONFIG": 2,
            "RESOURCE": 3,
            "FILE": 4,
            "FIELD": 5,
            "UNKNOWN": 9,
        }.get(self._graph_node_kind(row.get("kind")), 8)
        diagnostics_rank = 0 if self._json_list(row.get("diagnostics_json")) else 1
        degree = int(row.get("graph_degree") or 0)
        return kind_rank, diagnostics_rank, -degree, row.get("relative_path") or "", int(row.get("line_start") or 0)

    def _graph_symbol_matches(self, row: Dict[str, Any], flow_domain: Optional[str], fact_origin: Optional[str], node_kind: Optional[str]) -> bool:
        metadata = self._json_dict(row.get("metadata_json"))
        if flow_domain and self._graph_node_flow_domain(row, metadata) != flow_domain.upper():
            return False
        if fact_origin and self._graph_fact_origin(metadata) != fact_origin.upper():
            return False
        if node_kind:
            wanted = node_kind.upper()
            if self._graph_node_kind(row.get("kind")) != wanted and str(row.get("kind") or "").upper() != wanted:
                return False
        return True

    def _graph_relation_matches(self, row: Dict[str, Any], flow_domain: Optional[str], fact_origin: Optional[str], edge_type: Optional[str]) -> bool:
        metadata = self._json_dict(row.get("metadata_json"))
        if flow_domain and self._graph_edge_flow_domain(row, metadata) != flow_domain.upper():
            return False
        if fact_origin and self._graph_fact_origin(metadata) != fact_origin.upper():
            return False
        if edge_type and str(row.get("relation") or "").upper() != edge_type.upper():
            return False
        return True

    def _graph_node(
        self, row: Dict[str, Any], roles: List[Dict[str, Any]], diagnostics: List[Dict[str, Any]], include_evidence: bool, include_diagnostics: bool
    ) -> Dict[str, Any]:
        metadata = self._json_dict(row.get("metadata_json"))
        evidence_items = [
            {
                "claimType": role.get("role"),
                "text": evidence,
                "sourceId": row.get("source_id"),
                "relativePath": row.get("relative_path"),
                "lineStart": row.get("line_start"),
                "lineEnd": row.get("line_end"),
            }
            for role in roles
            for evidence in role.get("evidence", [])
        ]
        claims = [
            {
                "id": f"{row['symbol_id']}:{role.get('role')}",
                "nodeId": row["symbol_id"],
                "type": role.get("role"),
                "summary": role.get("role"),
                "confidence": role.get("confidence"),
                "evidenceCount": len(role.get("evidence", [])),
                **({"evidence": role.get("evidence", [])} if include_evidence else {}),
            }
            for role in roles
        ]
        confidence_values = [float(role["confidence"]) for role in roles if role.get("confidence") is not None]
        confidence = metadata.get("confidence")
        if confidence is None and confidence_values:
            confidence = max(confidence_values)
        view = {
            "id": row["symbol_id"],
            "graphNodeId": row["symbol_id"],
            "stableKey": metadata.get("stableKey") or row["symbol_id"],
            "label": metadata.get("qualifiedName") or metadata.get("displayName") or row.get("name") or row["symbol_id"],
            "qualifiedName": metadata.get("qualifiedName") or row.get("name"),
            "nodeKind": metadata.get("nodeKind") or self._graph_node_kind(row.get("kind")),
            "sourceKind": row.get("kind"),
            "flowDomain": self._graph_node_flow_domain(row, metadata),
            "factOrigin": self._graph_fact_origin(metadata),
            "status": metadata.get("status") or ("DIAGNOSTIC" if diagnostics else "TRUSTED"),
            "confidence": confidence,
            "sourceId": row.get("source_id"),
            "relativePath": row.get("relative_path"),
            "lineStart": row.get("line_start"),
            "lineEnd": row.get("line_end"),
            "claimSummary": metadata.get("claimSummary") or metadata.get("responsibility") or row.get("summary"),
            "responsibilitySummary": metadata.get("responsibility") or row.get("summary"),
            "roles": roles,
            "claims": claims,
            "evidenceCount": len(evidence_items),
            "diagnosticCount": len(diagnostics),
            "degree": int(row.get("graph_degree") or 0),
            "metadata": metadata,
        }
        if include_evidence:
            view["evidence"] = evidence_items
        if include_diagnostics:
            view["diagnostics"] = diagnostics
        return view

    def _graph_edge(
        self, row: Dict[str, Any], node_by_id: Dict[str, Dict[str, Any]], include_evidence: bool, include_diagnostics: bool
    ) -> Optional[Dict[str, Any]]:
        if row["from_symbol_id"] not in node_by_id or row["to_symbol_id"] not in node_by_id:
            return None
        metadata = self._json_dict(row.get("metadata_json"))
        evidence = row.get("evidence")
        if evidence is None:
            evidence = self._json_list(row.get("evidence_json"))
        diagnostics = metadata.get("diagnostics") if isinstance(metadata.get("diagnostics"), list) else []
        view = {
            "id": row["relation_id"],
            "graphEdgeId": row["relation_id"],
            "stableKey": metadata.get("stableKey") or row["relation_id"],
            "from": row["from_symbol_id"],
            "to": row["to_symbol_id"],
            "fromLabel": node_by_id[row["from_symbol_id"]].get("label"),
            "toLabel": node_by_id[row["to_symbol_id"]].get("label"),
            "edgeType": metadata.get("edgeType") or row.get("relation"),
            "relation": row.get("relation"),
            "resolutionStatus": metadata.get("resolutionStatus") or "RESOLVED",
            "unresolvedTarget": metadata.get("unresolvedTarget"),
            "flowDomain": self._graph_edge_flow_domain(row, metadata),
            "factOrigin": self._graph_fact_origin(metadata),
            "status": metadata.get("status") or "TRUSTED",
            "confidence": row.get("confidence"),
            "evidenceCount": len(evidence),
            "diagnosticCount": len(diagnostics),
            "lineStart": row.get("line_start"),
            "lineEnd": row.get("line_end"),
            "metadata": metadata,
        }
        if include_evidence:
            view["evidence"] = [
                {
                    "edgeId": row["relation_id"],
                    "text": item,
                    "sourceId": row.get("source_id"),
                    "lineStart": row.get("line_start"),
                    "lineEnd": row.get("line_end"),
                }
                for item in evidence
            ]
        if include_diagnostics:
            view["diagnostics"] = diagnostics
        return view

    def _graph_file_diagnostics(self, conn: sqlite3.Connection, file_ids: List[int]) -> Dict[int, List[Dict[str, Any]]]:
        ids = sorted({int(file_id) for file_id in file_ids if file_id is not None})
        if not ids:
            return {}
        placeholders = ",".join("?" for _ in ids)
        rows = conn.execute(
            f"SELECT file_id, source_id, relative_path, diagnostics_json, last_error_code, last_error_message FROM analysis_files WHERE file_id IN ({placeholders})",
            ids,
        ).fetchall()
        result: Dict[int, List[Dict[str, Any]]] = {}
        for row in rows:
            diagnostics = self._json_list(row["diagnostics_json"])
            if row["last_error_code"] and not diagnostics:
                diagnostics = [{"code": row["last_error_code"], "message": row["last_error_message"]}]
            result[row["file_id"]] = [self._graph_diagnostic(item, row["source_id"], row["relative_path"]) for item in diagnostics]
        return result

    def _graph_source_diagnostics(self, conn: sqlite3.Connection, source_id: Optional[str]) -> List[Dict[str, Any]]:
        clauses = ["diagnostics_json IS NOT NULL", "diagnostics_json != '[]'"]
        params: List[Any] = []
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        rows = conn.execute(
            f"""
            SELECT source_id, relative_path, diagnostics_json
            FROM analysis_files
            WHERE {" AND ".join(clauses)}
            ORDER BY analyzed_at DESC
            LIMIT 50
        """,
            params,
        ).fetchall()
        diagnostics: List[Dict[str, Any]] = []
        for row in rows:
            diagnostics.extend([self._graph_diagnostic(item, row["source_id"], row["relative_path"]) for item in self._json_list(row["diagnostics_json"])])
        active = self.active_job()
        if active:
            for item in active.get("diagnostics") or []:
                if source_id and item.get("sourceId") not in {source_id, None}:
                    continue
                diagnostics.append(self._graph_diagnostic(item, item.get("sourceId") or source_id, item.get("relativePath")))
        return diagnostics[:50]

    def _graph_status(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Dict[str, Any]:
        active = self.active_job()
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
                SUM(CASE WHEN status = 'ANALYZED' THEN 1 ELSE 0 END) AS analyzed,
                SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                SUM(CASE WHEN status = 'SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS' THEN 1 ELSE 0 END) AS skipped,
                MAX(analyzed_at) AS last_analyzed_at
            FROM analysis_files
            {where}
        """,
            params,
        ).fetchone()
        graph_where = "WHERE source_id = ?" if source_id else ""
        graph_params = [source_id] if source_id else []
        node_count = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_nodes {graph_where}", graph_params).fetchone()["count"]
        edge_count = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_graph_edges {graph_where}", graph_params).fetchone()["count"]
        trusted_nodes = conn.execute(
            f"SELECT COUNT(*) AS count FROM analysis_graph_nodes {graph_where} {'AND' if graph_where else 'WHERE'} status = 'TRUSTED'", graph_params
        ).fetchone()["count"]
        trusted_edges = conn.execute(
            f"SELECT COUNT(*) AS count FROM analysis_graph_edges {graph_where} {'AND' if graph_where else 'WHERE'} status = 'TRUSTED'", graph_params
        ).fetchone()["count"]
        analyzed = int(file_counts["analyzed"] or 0)
        failed = int(file_counts["failed"] or 0)
        skipped = int(file_counts["skipped"] or 0)
        completed_outcomes = analyzed + failed + skipped
        processed = completed_outcomes
        running_for_source = active is not None and (
            not source_id or active.get("currentSourceId") == source_id or source_id in (active.get("sourceIds") or [])
        )
        total_files = int(inventory_count or (active or {}).get("fileCount") or (latest or {}).get("fileCount") or 0)
        if running_for_source:
            analysis_status = "RUNNING"
            job_id = active.get("jobId")
            current_file = active.get("currentRelativePath") if not source_id or active.get("currentSourceId") == source_id else None
            last_updated = active.get("lastProgressAt") or active.get("startedAt")
            processed = min(total_files, completed_outcomes + int(active.get("processedFileCount") or 0))
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
        progress = round((processed / total_files) * 100, 1) if total_files else 0.0
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
            "fileCount": total_files,
            "failedFileCount": failed,
            "progressPercent": progress,
            "currentFile": current_file,
            "trustedFactsCount": int(trusted_nodes or 0) + int(trusted_edges or 0),
            "diagnosticsCount": int(diagnostics_count or 0),
            "lastUpdatedAt": last_updated,
        }

    def _graph_source_name(self, conn: sqlite3.Connection, source_id: Optional[str]) -> Optional[str]:
        if not source_id:
            return None
        row = conn.execute("SELECT display_name FROM sources WHERE source_id = ?", (source_id,)).fetchone()
        return row["display_name"] if row else None

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

    def _graph_evidence(self, nodes: List[Dict[str, Any]], edges: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        evidence: List[Dict[str, Any]] = []
        for node in nodes:
            evidence.extend(node.get("evidence") or [])
        for edge in edges:
            evidence.extend(edge.get("evidence") or [])
        return evidence

    def _graph_not_found(self, code: str, message: str, item_id: str) -> Dict[str, Any]:
        return {
            "severity": "WARN",
            "stage": "GRAPH_PROJECTION",
            "code": code,
            "message": message,
            "itemId": item_id,
        }

    def _graph_node_kind(self, kind: Optional[str]) -> str:
        value = str(kind or "UNKNOWN").upper()
        if value in {"METHOD", "FUNCTION", "CONTRACT_OPERATION"}:
            return "CALLABLE"
        if value in {"CLASS", "INTERFACE", "DTO", "RECORD"}:
            return "TYPE"
        if value == "CONFIG_ENTRY":
            return "CONFIG"
        if value in {"FILE", "FIELD", "RESOURCE", "DATA", "EXTERNAL"}:
            return value
        return "UNKNOWN"

    def _graph_node_flow_domain(self, row: Dict[str, Any], metadata: Dict[str, Any]) -> str:
        explicit = metadata.get("flowDomain")
        if explicit:
            return str(explicit).upper()
        path = str(row.get("relative_path") or "").lower()
        kind = str(row.get("kind") or "").upper()
        if "/test/" in f"/{path}" or path.startswith("test/") or path.endswith("test.java"):
            return "TEST"
        if kind == "CONFIG_ENTRY" or path.endswith((".yaml", ".yml", ".properties", ".toml", ".ini")):
            return "CONFIG"
        if path.endswith((".md", ".adoc", ".txt")):
            return "DOC"
        if path.endswith((".json", ".csv", ".xml")):
            return "DATA"
        if "workflow" in path or "/.github/" in f"/{path}":
            return "WORKFLOW"
        if "pom.xml" in path or "build.gradle" in path:
            return "BUILD"
        return "CODE"

    def _graph_edge_flow_domain(self, row: Dict[str, Any], metadata: Dict[str, Any]) -> str:
        explicit = metadata.get("flowDomain")
        if explicit:
            return str(explicit).upper()
        relation = str(row.get("relation") or "").upper()
        if relation == "CONFIGURES":
            return "CONFIG"
        return "CODE"

    def _graph_fact_origin(self, metadata: Dict[str, Any]) -> str:
        return str(metadata.get("factOrigin") or "LLM").upper()

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

    def _delete_file_analysis(self, conn: sqlite3.Connection, file_id: int) -> None:
        graph_node_ids = [
            row["id"]
            for row in conn.execute("SELECT id FROM analysis_graph_nodes WHERE analysis_file_id = ? OR inventory_file_id = ?", (file_id, file_id)).fetchall()
        ]
        if graph_node_ids:
            placeholders = ",".join("?" for _ in graph_node_ids)
            conn.execute(f"DELETE FROM analysis_graph_claims WHERE node_id IN ({placeholders})", graph_node_ids)
            conn.execute(
                f"DELETE FROM analysis_graph_edges WHERE from_node_id IN ({placeholders}) OR to_node_id IN ({placeholders})", [*graph_node_ids, *graph_node_ids]
            )
        conn.execute("DELETE FROM analysis_graph_nodes WHERE analysis_file_id = ? OR inventory_file_id = ?", (file_id, file_id))
        conn.execute("DELETE FROM analysis_graph_evidence WHERE analysis_file_id = ? OR inventory_file_id = ?", (file_id, file_id))
        conn.execute("DELETE FROM analysis_graph_diagnostics WHERE analysis_file_id = ? OR inventory_file_id = ?", (file_id, file_id))
        ids = [row["symbol_id"] for row in conn.execute("SELECT symbol_id FROM analysis_symbols WHERE file_id = ?", (file_id,)).fetchall()]
        if ids:
            placeholders = ",".join("?" for _ in ids)
            conn.execute(f"DELETE FROM analysis_symbol_roles WHERE symbol_id IN ({placeholders})", ids)
            conn.execute(f"DELETE FROM analysis_relations WHERE from_symbol_id IN ({placeholders}) OR to_symbol_id IN ({placeholders})", [*ids, *ids])
        conn.execute("DELETE FROM analysis_symbols WHERE file_id = ?", (file_id,))

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
            callable_candidates = self._callable_candidates_for_type(conn, type_candidates[0]["id"], str(method_name), metadata.get("argumentCount"))
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

    def _callable_candidates_for_type(self, conn: sqlite3.Connection, type_node_id: str, method_name: str, argument_count: Optional[int]) -> List[sqlite3.Row]:
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
            conn.execute("UPDATE analysis_symbols SET file_id = ? WHERE file_id = ?", (new_file_id, old_file_id))
            for table in ("analysis_graph_nodes", "analysis_graph_edges", "analysis_graph_evidence", "analysis_graph_diagnostics"):
                conn.execute(
                    f"UPDATE {table} SET analysis_file_id = ?, inventory_file_id = ? WHERE analysis_file_id = ? OR inventory_file_id = ?",
                    (new_file_id, new_file_id, old_file_id, old_file_id),
                )

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
        )

    def _job(self, row) -> Dict[str, Any]:
        return {
            "jobId": row["job_id"],
            "status": row["status"],
            "startedAt": row["started_at"],
            "completedAt": row["completed_at"],
            "sourceCount": row["source_count"],
            "fileCount": row["file_count"],
            "processedFileCount": row["processed_file_count"],
            "failedFileCount": row["failed_file_count"],
            "currentSourceId": row["current_source_id"],
            "currentRelativePath": row["current_relative_path"],
            "sourceIds": json.loads(row["source_ids_json"] or "[]"),
            "lastProgressAt": row["last_progress_at"],
            "symbolCount": row["symbol_count"],
            "relationCount": row["relation_count"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
            "engineVersion": row["engine_version"] if "engine_version" in row.keys() else None,
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

    def _roles(self, conn: sqlite3.Connection, symbol_ids: List[str]) -> Dict[str, List[Dict[str, Any]]]:
        if not symbol_ids:
            return {}
        rows = conn.execute(
            f"SELECT * FROM analysis_symbol_roles WHERE symbol_id IN ({','.join('?' for _ in symbol_ids)}) ORDER BY confidence DESC",
            symbol_ids,
        ).fetchall()
        result: Dict[str, List[Dict[str, Any]]] = {}
        for row in rows:
            result.setdefault(row["symbol_id"], []).append(
                {
                    "role": row["role"],
                    "confidence": row["confidence"],
                    "evidence": json.loads(row["evidence_json"] or "[]"),
                    "classifier": row["classifier"],
                    "classifierVersion": row["classifier_version"],
                }
            )
        return result

    def _symbol(self, row, roles: List[Dict[str, Any]]) -> Dict[str, Any]:
        return {
            "symbolId": row["symbol_id"],
            "sourceId": row["source_id"],
            "relativePath": row["relative_path"],
            "name": row["name"],
            "kind": row["kind"],
            "roles": roles,
            "lineStart": row["line_start"],
            "lineEnd": row["line_end"],
            "summary": row["summary"],
            "metadata": json.loads(row["metadata_json"] or "{}"),
        }

    def _relation(self, row) -> Dict[str, Any]:
        return {
            "relationId": row["relation_id"],
            "sourceId": row["source_id"],
            "fromSymbolId": row["from_symbol_id"],
            "toSymbolId": row["to_symbol_id"],
            "relation": row["relation"],
            "confidence": row["confidence"],
            "evidence": json.loads(row["evidence_json"] or "[]"),
            "lineStart": row["line_start"],
            "lineEnd": row["line_end"],
            "metadata": json.loads(row["metadata_json"] or "{}"),
        }

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=30.0)
        conn.execute("PRAGMA busy_timeout = 30000")
        conn.row_factory = sqlite3.Row
        return conn

    def _drop_legacy_fact_tables(self, conn: sqlite3.Connection) -> None:
        for table in ("symbol_tokens", "edges", "symbols", "file_extraction_state", "fact_builds"):
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
        raise RuntimeError(f"Unknown analysis schema migration: {version}")

    def _create_analysis_graph_diagnostics_table(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS analysis_graph_diagnostics (
                id TEXT PRIMARY KEY,
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
                flow_domain TEXT
            )
        """)

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
            "candidate_id": "TEXT",
            "line_start": "INTEGER",
            "line_end": "INTEGER",
            "fact_origin": "TEXT",
            "flow_domain": "TEXT",
        }.items():
            if column not in columns:
                conn.execute(f"ALTER TABLE analysis_graph_diagnostics ADD COLUMN {column} {declaration}")
                columns[column] = {"name": column}

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
