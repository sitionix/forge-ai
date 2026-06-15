from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from knowledge_service.source_catalog import SourceMetadata


ANALYSIS_SCHEMA_MIGRATIONS = (
    (1, "remove_legacy_analysis_job_counter"),
    (2, "add_analysis_job_source_scope"),
)


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
                    last_progress_at TEXT,
                    symbol_count INTEGER NOT NULL,
                    relation_count INTEGER NOT NULL,
                    diagnostics_json TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "analysis_jobs", "current_source_id", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "current_relative_path", "TEXT")
            self._ensure_column(conn, "analysis_jobs", "last_progress_at", "TEXT")
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
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_files_status ON analysis_files(source_id, status)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_symbols_source_kind ON analysis_symbols(source_id, kind)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_roles_role ON analysis_symbol_roles(role)")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_analysis_relations_relation ON analysis_relations(source_id, relation)")
            self._drop_legacy_fact_tables(conn)
            self._run_schema_migrations(conn)

    def create_job(self, job: Dict[str, Any]) -> None:
        self.init()
        with self._connect() as conn:
            conn.execute("""
                INSERT INTO analysis_jobs(job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count, current_source_id, current_relative_path, source_ids_json, last_progress_at, symbol_count, relation_count, diagnostics_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    failed_file_count = ?, current_source_id = ?, current_relative_path = ?, source_ids_json = ?, last_progress_at = ?,
                    symbol_count = ?, relation_count = ?, diagnostics_json = ?
                WHERE job_id = ?
            """, (*self._job_params(current)[1:], job_id))

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
            rows = conn.execute("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')").fetchall()
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

    def status(self) -> Dict[str, Any]:
        self.init()
        active = self.active_job()
        with self._connect() as conn:
            latest = conn.execute("SELECT * FROM analysis_jobs WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1").fetchone()
            counts = conn.execute("SELECT COUNT(*) AS symbols FROM analysis_symbols").fetchone()
            relations = conn.execute("SELECT COUNT(*) AS relations FROM analysis_relations").fetchone()
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

    def service_status(self, catalog_sources: Optional[List[SourceMetadata]], analyzer_name: str, analyzer_version: str, inventory_status: Dict[str, Any]) -> Dict[str, Any]:
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
                          AND af.status = 'ANALYZED'
                    ) THEN 1 ELSE 0 END) AS analyzed_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
                          AND af.status = 'FAILED'
                    ) THEN 1 ELSE 0 END) AS failed_count,
                    SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM analysis_files af
                        WHERE af.source_id = f.source_id
                          AND af.relative_path = f.relative_path
                          AND af.content_hash = f.content_hash
                          AND af.analyzer_name = ?
                          AND af.analyzer_version = ?
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
                          )
                    ) THEN 1 ELSE 0 END) AS stale_count
                FROM sources s
                LEFT JOIN files f ON f.source_id = s.source_id
                GROUP BY s.source_id, s.display_name, s.group_name, s.path, s.root_exists, s.tags_json
            """, (
                analyzer_name, analyzer_version,
                analyzer_name, analyzer_version,
                analyzer_name, analyzer_version,
                analyzer_name, analyzer_version,
            )).fetchall()
            symbol_rows = conn.execute("SELECT source_id, COUNT(*) AS count FROM analysis_symbols GROUP BY source_id").fetchall()
            relation_rows = conn.execute("SELECT source_id, COUNT(*) AS count FROM analysis_relations GROUP BY source_id").fetchall()
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
            processed = active.get("processedFileCount") if is_running else analyzed + failed + skipped_too_large
            pending = max(inventory_count - analyzed - failed - skipped_too_large, 0)
            percent = round((analyzed / inventory_count) * 100, 1) if inventory_count else 0.0
            services.append({
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
            })
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
        return {
            source_id: sorted(items.values(), key=lambda item: item["count"], reverse=True)
            for source_id, items in grouped.items()
        }

    def _collect_diagnostics(self, grouped: Dict[str, Dict[str, Dict[str, Any]]], source_id: str, diagnostics: List[Dict[str, Any]]) -> None:
        bucket = grouped.setdefault(source_id, {})
        for diagnostic in diagnostics:
            code = diagnostic.get("code") or "DIAGNOSTIC"
            item = bucket.setdefault(code, {
                "code": code,
                "message": diagnostic.get("message") or "-",
                "count": 0,
                "examples": [],
            })
            item["count"] += 1
            relative_path = diagnostic.get("relativePath")
            if relative_path and len(item["examples"]) < 10:
                item["examples"].append(relative_path)

    def unchanged(self, file_id: int, content_hash: str, analyzer_name: str, analyzer_version: str) -> bool:
        self.init()
        with self._connect() as conn:
            row = conn.execute("""
                SELECT file_id FROM analysis_files
                WHERE file_id = ? AND content_hash = ? AND analyzer_name = ? AND analyzer_version = ? AND status = 'ANALYZED'
            """, (file_id, content_hash, analyzer_name, analyzer_version)).fetchone()
        return row is not None

    def unchanged_file_ids(self, rows: List[sqlite3.Row], analyzer_name: str, analyzer_version: str) -> set[int]:
        if not rows:
            return set()
        self.init()
        result: set[int] = set()
        with self._connect() as conn:
            for offset in range(0, len(rows), 400):
                batch = rows[offset:offset + 400]
                clauses: list[str] = []
                params: list[Any] = [analyzer_name, analyzer_version]
                for row in batch:
                    clauses.append("(file_id = ? AND content_hash = ?)")
                    params.extend([row["id"], row["content_hash"]])
                matches = conn.execute(f"""
                    SELECT file_id FROM analysis_files
                    WHERE analyzer_name = ?
                      AND analyzer_version = ?
                      AND status = 'ANALYZED'
                      AND ({' OR '.join(clauses)})
                """, params).fetchall()
                result.update(row["file_id"] for row in matches)
        return result

    def replace_file_analysis(self, file_id: int, state: Dict[str, Any], symbols: List[Dict[str, Any]], roles: List[Dict[str, Any]], relations: List[Dict[str, Any]]) -> None:
        self.init()
        with self._connect() as conn:
            self._delete_file_analysis(conn, file_id)
            for symbol in symbols:
                conn.execute("""
                    INSERT INTO analysis_symbols(symbol_id, file_id, source_id, relative_path, name, kind, line_start, line_end, summary, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    symbol["symbol_id"], file_id, symbol["source_id"], symbol["relative_path"], symbol["name"], symbol["kind"],
                    symbol["line_start"], symbol["line_end"], symbol.get("summary"), json.dumps(symbol.get("metadata") or {}),
                ))
            for role in roles:
                conn.execute("""
                    INSERT INTO analysis_symbol_roles(symbol_id, role, confidence, evidence_json, classifier, classifier_version)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (role["symbol_id"], role["role"], role["confidence"], json.dumps(role["evidence"]), role["classifier"], role["classifier_version"]))
            for relation in relations:
                conn.execute("""
                    INSERT INTO analysis_relations(relation_id, source_id, from_symbol_id, to_symbol_id, relation, confidence, evidence_json, line_start, line_end, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    relation["relation_id"], relation["source_id"], relation["from_symbol_id"], relation["to_symbol_id"],
                    relation["relation"], relation["confidence"], json.dumps(relation["evidence"]), relation["line_start"],
                    relation["line_end"], json.dumps(relation.get("metadata") or {}),
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

    def symbols(self, source_id: Optional[str], role: Optional[str], kind: Optional[str], path_contains: Optional[str], name_contains: Optional[str], limit: int, offset: int) -> Dict[str, Any]:
        clauses, params = [], []
        if source_id:
            clauses.append("s.source_id = ?")
            params.append(source_id)
        if role:
            clauses.append("EXISTS (SELECT 1 FROM analysis_symbol_roles r WHERE r.symbol_id = s.symbol_id AND r.role = ?)")
            params.append(role)
        if kind:
            clauses.append("s.kind = ?")
            params.append(kind)
        if path_contains:
            clauses.append("s.relative_path LIKE ?")
            params.append(f"%{path_contains}%")
        if name_contains:
            clauses.append("s.name LIKE ?")
            params.append(f"%{name_contains}%")
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_symbols s {where}", params).fetchone()["count"]
            rows = conn.execute(f"SELECT s.* FROM analysis_symbols s {where} ORDER BY s.source_id, s.relative_path, s.line_start LIMIT ? OFFSET ?", [*params, limit, offset]).fetchall()
            roles = self._roles(conn, [row["symbol_id"] for row in rows])
        return {"symbols": [self._symbol(row, roles.get(row["symbol_id"], [])) for row in rows], "total": total, "limit": limit, "offset": offset}

    def relations(self, source_id: Optional[str], relation: Optional[str], from_symbol_id: Optional[str], to_symbol_id: Optional[str], limit: int, offset: int) -> Dict[str, Any]:
        clauses, params = [], []
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        if relation:
            clauses.append("relation = ?")
            params.append(relation)
        if from_symbol_id:
            clauses.append("from_symbol_id = ?")
            params.append(from_symbol_id)
        if to_symbol_id:
            clauses.append("to_symbol_id = ?")
            params.append(to_symbol_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        self.init()
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM analysis_relations {where}", params).fetchone()["count"]
            rows = conn.execute(f"SELECT * FROM analysis_relations {where} ORDER BY source_id, relation, line_start LIMIT ? OFFSET ?", [*params, limit, offset]).fetchall()
        return {"relations": [self._relation(row) for row in rows], "total": total, "limit": limit, "offset": offset}

    def _delete_file_analysis(self, conn: sqlite3.Connection, file_id: int) -> None:
        ids = [row["symbol_id"] for row in conn.execute("SELECT symbol_id FROM analysis_symbols WHERE file_id = ?", (file_id,)).fetchall()]
        if ids:
            placeholders = ",".join("?" for _ in ids)
            conn.execute(f"DELETE FROM analysis_symbol_roles WHERE symbol_id IN ({placeholders})", ids)
            conn.execute(f"DELETE FROM analysis_relations WHERE from_symbol_id IN ({placeholders}) OR to_symbol_id IN ({placeholders})", [*ids, *ids])
        conn.execute("DELETE FROM analysis_symbols WHERE file_id = ?", (file_id,))

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
            conn.execute("UPDATE analysis_symbols SET file_id = ? WHERE file_id = ?", (new_file_id, old_file_id))

    def _upsert_file(self, conn: sqlite3.Connection, file_id: int, state: Dict[str, Any]) -> None:
        conn.execute("""
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, attempt_count, last_attempt_at, last_error_code, last_error_message, last_raw_response_preview, diagnostics_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (
            file_id, state["source_id"], state["relative_path"], state["content_hash"], state["analyzer_name"], state["analyzer_version"],
            state["status"], state.get("analyzed_at"), state["symbol_count"], state["relation_count"],
            state.get("attempt_count", 0), state.get("last_attempt_at"), state.get("last_error_code"),
            state.get("last_error_message"), state.get("last_raw_response_preview"),
            json.dumps(state.get("diagnostics") or []),
        ))

    def _job_params(self, job: Dict[str, Any]):
        return (
            job["jobId"], job["status"], job.get("startedAt"), job.get("completedAt"), job.get("sourceCount", 0), job.get("fileCount", 0),
            job.get("processedFileCount", 0), job.get("failedFileCount", 0),
            job.get("currentSourceId"), job.get("currentRelativePath"), json.dumps(job.get("sourceIds") or []), job.get("lastProgressAt"),
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
            "lastProgressAt": row["last_progress_at"],
            "symbolCount": row["symbol_count"], "relationCount": row["relation_count"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
        }

    def _file(self, row) -> Dict[str, Any]:
        return {
            "sourceId": row["source_id"], "relativePath": row["relative_path"], "contentHash": row["content_hash"],
            "analysisStatus": row["status"], "analyzedAt": row["analyzed_at"], "symbolCount": row["symbol_count"],
            "relationCount": row["relation_count"], "attemptCount": row["attempt_count"],
            "lastAttemptAt": row["last_attempt_at"], "lastErrorCode": row["last_error_code"],
            "lastErrorMessage": row["last_error_message"], "lastRawResponsePreview": row["last_raw_response_preview"],
            "diagnostics": json.loads(row["diagnostics_json"] or "[]"),
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
            result.setdefault(row["symbol_id"], []).append({
                "role": row["role"], "confidence": row["confidence"], "evidence": json.loads(row["evidence_json"] or "[]"),
                "classifier": row["classifier"], "classifierVersion": row["classifier_version"],
            })
        return result

    def _symbol(self, row, roles: List[Dict[str, Any]]) -> Dict[str, Any]:
        return {
            "symbolId": row["symbol_id"], "sourceId": row["source_id"], "relativePath": row["relative_path"],
            "name": row["name"], "kind": row["kind"], "roles": roles, "lineStart": row["line_start"], "lineEnd": row["line_end"],
            "summary": row["summary"], "metadata": json.loads(row["metadata_json"] or "{}"),
        }

    def _relation(self, row) -> Dict[str, Any]:
        return {
            "relationId": row["relation_id"], "sourceId": row["source_id"], "fromSymbolId": row["from_symbol_id"],
            "toSymbolId": row["to_symbol_id"], "relation": row["relation"], "confidence": row["confidence"],
            "evidence": json.loads(row["evidence_json"] or "[]"), "lineStart": row["line_start"], "lineEnd": row["line_end"],
            "metadata": json.loads(row["metadata_json"] or "{}"),
        }

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
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
            "last_progress_at", "symbol_count", "relation_count", "diagnostics_json",
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
