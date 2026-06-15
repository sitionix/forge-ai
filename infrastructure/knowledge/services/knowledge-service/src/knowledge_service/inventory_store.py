from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from knowledge_service.file_metadata import FileMetadata
from knowledge_service.graph_schema import GRAPH_ANALYSIS_ENGINE_VERSION
from knowledge_service.skipped_reasons import SkippedBreakdown, normalize_skipped_breakdown
from knowledge_service.source_catalog import SourceMetadata


class InventoryStore:
    def __init__(self, db_path: Path):
        self.db_path = db_path

    def init(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS inventory_builds (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    status TEXT NOT NULL,
                    source_count INTEGER NOT NULL,
                    file_count INTEGER NOT NULL,
                    skipped_count INTEGER NOT NULL,
                    skipped_reasons_json TEXT,
                    error_message TEXT
                )
            """)
            self._ensure_column(conn, "inventory_builds", "skipped_reasons_json", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS sources (
                    source_id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    group_name TEXT,
                    path TEXT NOT NULL,
                    root_exists INTEGER NOT NULL,
                    tags_json TEXT NOT NULL,
                    metadata_json TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_id TEXT NOT NULL,
                    source_path TEXT NOT NULL,
                    absolute_path TEXT NOT NULL,
                    relative_path TEXT NOT NULL,
                    extension TEXT,
                    language TEXT,
                    flow_domain TEXT,
                    size_bytes INTEGER NOT NULL,
                    content_hash TEXT NOT NULL,
                    last_modified TEXT NOT NULL,
                    line_count INTEGER NOT NULL DEFAULT 0,
                    decode_policy TEXT NOT NULL DEFAULT 'utf-8:replace',
                    indexed_at TEXT NOT NULL
                )
            """)
            self._ensure_column(conn, "files", "line_count", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(conn, "files", "decode_policy", "TEXT NOT NULL DEFAULT 'utf-8:replace'")
            self._ensure_column(conn, "files", "language", "TEXT")
            self._ensure_column(conn, "files", "flow_domain", "TEXT")
            conn.execute("""
                CREATE TABLE IF NOT EXISTS inventory_source_state (
                    source_id TEXT PRIMARY KEY,
                    eligible_file_count INTEGER NOT NULL,
                    skipped_count INTEGER,
                    skipped_reasons_json TEXT,
                    last_inventory_at TEXT NOT NULL
                )
            """)

    def replace_inventory(
        self,
        sources: List[SourceMetadata],
        files: List[FileMetadata],
        skipped: SkippedBreakdown,
        started_at: str,
        completed_at: str,
        replace_all: bool = True,
        replace_source_ids: Optional[List[str]] = None,
        source_skipped: Optional[Dict[str, SkippedBreakdown]] = None,
    ) -> Dict[str, Any]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        skipped_breakdown = skipped.public_dict()
        skipped_count = skipped_breakdown["total"]
        scoped_source_ids = sorted(set(replace_source_ids or [source.sourceId for source in sources]))
        with self._connect() as conn:
            if replace_all:
                conn.execute("DELETE FROM files")
                conn.execute("DELETE FROM sources")
                conn.execute("DELETE FROM inventory_source_state")
            elif scoped_source_ids:
                placeholders = ",".join("?" for _ in scoped_source_ids)
                conn.execute(f"DELETE FROM files WHERE source_id IN ({placeholders})", scoped_source_ids)
                conn.execute(f"DELETE FROM sources WHERE source_id IN ({placeholders})", scoped_source_ids)
                conn.execute(f"DELETE FROM inventory_source_state WHERE source_id IN ({placeholders})", scoped_source_ids)
            for source in sources:
                conn.execute(
                    "INSERT INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        source.sourceId, source.displayName, source.group, source.path, 1 if source.rootExists else 0,
                        json.dumps(source.tags), json.dumps(source.public_dict(include_absolute_root=True)), now,
                    ),
                )
            for file in files:
                conn.execute(
                    "INSERT INTO files(source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        file.sourceId, file.sourcePath, file.absolutePath, file.relativePath, file.extension,
                        file.language, file.flowDomain, file.sizeBytes, file.contentHash, file.lastModified,
                        file.lineCount, file.decodePolicy, now,
                    ),
                )
            files_by_source: Dict[str, int] = {}
            for file in files:
                files_by_source[file.sourceId] = files_by_source.get(file.sourceId, 0) + 1
            for source_id in scoped_source_ids:
                breakdown = (source_skipped or {}).get(source_id)
                skipped_public = breakdown.public_dict() if breakdown is not None else None
                conn.execute(
                    "INSERT OR REPLACE INTO inventory_source_state(source_id, eligible_file_count, skipped_count, skipped_reasons_json, last_inventory_at) VALUES (?, ?, ?, ?, ?)",
                    (
                        source_id,
                        files_by_source.get(source_id, 0),
                        skipped_public.get("total") if skipped_public else None,
                        json.dumps(skipped_public) if skipped_public else None,
                        now,
                    ),
                )
            conn.execute(
                "INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (started_at, completed_at, "COMPLETED", len(sources), len(files), skipped_count, json.dumps(skipped_breakdown), None),
            )
        return {
            "status": "COMPLETED",
            "sourceCount": len(sources),
            "fileCount": len(files),
            "skippedCount": skipped_count,
            "skippedBreakdown": skipped_breakdown,
            "startedAt": started_at,
            "completedAt": completed_at,
        }

    def status(self) -> Dict[str, Any]:
        self.init()
        with self._connect() as conn:
            build = conn.execute("SELECT completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json FROM inventory_builds ORDER BY id DESC LIMIT 1").fetchone()
        if not build:
            return {"status": "EMPTY", "sourceCount": 0, "fileCount": 0, "skippedCount": 0, "skippedBreakdown": {"total": 0, "byReason": {}}}
        skipped_breakdown = normalize_skipped_breakdown(
            self._decode_json(build["skipped_reasons_json"]),
            build["skipped_count"],
        )
        return {
            "status": "READY" if build["status"] == "COMPLETED" else build["status"],
            "lastBuildAt": build["completed_at"],
            "sourceCount": build["source_count"],
            "fileCount": build["file_count"],
            "skippedCount": build["skipped_count"],
            "skippedBreakdown": skipped_breakdown,
        }

    def source_ids(self) -> List[str]:
        self.init()
        with self._connect() as conn:
            rows = conn.execute("SELECT source_id FROM sources ORDER BY source_id").fetchall()
        return [row["source_id"] for row in rows]

    def files(self, source_id: Optional[str], path_contains: Optional[str], extension: Optional[str], limit: int, offset: int) -> Dict[str, Any]:
        self.init()
        clauses: list[str] = []
        params: list[Any] = []
        if source_id:
            clauses.append("source_id = ?")
            params.append(source_id)
        if path_contains:
            clauses.append("relative_path LIKE ?")
            params.append(f"%{path_contains}%")
        if extension:
            clauses.append("extension = ?")
            params.append(extension if extension.startswith(".") else f".{extension}")
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as conn:
            total = conn.execute(f"SELECT COUNT(*) AS count FROM files {where}", params).fetchone()["count"]
            rows = conn.execute(
                f"SELECT source_id, source_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy FROM files {where} ORDER BY source_id, relative_path LIMIT ? OFFSET ?",
                [*params, limit, offset],
            ).fetchall()
        return {
            "files": [{
                "sourceId": row["source_id"], "sourcePath": row["source_path"], "relativePath": row["relative_path"],
                "extension": row["extension"], "language": row["language"], "flowDomain": row["flow_domain"],
                "sizeBytes": row["size_bytes"], "contentHash": row["content_hash"],
                "lastModified": row["last_modified"], "lineCount": row["line_count"], "decodePolicy": row["decode_policy"],
            } for row in rows],
            "limit": limit, "offset": offset, "total": total,
        }

    def snapshot_files(self, source_ids: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        self.init()
        clauses: list[str] = []
        params: list[Any] = []
        if source_ids:
            clauses.append("source_id IN (%s)" % ",".join("?" for _ in source_ids))
            params.extend(source_ids)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT id, source_id, relative_path, content_hash, size_bytes, last_modified, line_count, decode_policy FROM files {where}",
                params,
            ).fetchall()
        return [{
            "id": row["id"],
            "sourceId": row["source_id"],
            "relativePath": row["relative_path"],
            "contentHash": row["content_hash"],
            "sizeBytes": row["size_bytes"],
            "lastModified": row["last_modified"],
            "lineCount": row["line_count"],
            "decodePolicy": row["decode_policy"],
        } for row in rows]

    def analyzed_file_ids(self, file_ids: List[int]) -> set[int]:
        if not file_ids:
            return set()
        self.init()
        with self._connect() as conn:
            rows = conn.execute(
                f"SELECT file_id FROM analysis_files WHERE file_id IN ({','.join('?' for _ in file_ids)})",
                file_ids,
            ).fetchall()
        return {row["file_id"] for row in rows}

    def search_rows(
        self,
        source_ids: List[str],
        groups: List[str],
        analyzer_name: Optional[str] = None,
        analyzer_version: Optional[str] = None,
        only_needing_analysis: bool = False,
        engine_version: str = GRAPH_ANALYSIS_ENGINE_VERSION,
    ) -> Tuple[List[sqlite3.Row], Dict[str, sqlite3.Row]]:
        self.init()
        if only_needing_analysis and (not analyzer_name or not analyzer_version or not self._table_exists("analysis_files")):
            only_needing_analysis = False
        clauses: list[str] = []
        params: list[Any] = []
        if source_ids:
            clauses.append("f.source_id IN (%s)" % ",".join("?" for _ in source_ids))
            params.extend(source_ids)
        if groups:
            clauses.append("s.group_name IN (%s)" % ",".join("?" for _ in groups))
            params.extend(groups)
        if only_needing_analysis:
            clauses.append("""
                NOT EXISTS (
                    SELECT 1 FROM analysis_files af
                    WHERE af.source_id = f.source_id
                      AND af.relative_path = f.relative_path
                      AND af.content_hash = f.content_hash
                      AND af.analyzer_name = ?
                      AND af.analyzer_version = ?
                      AND af.engine_version = ?
                      AND af.status = 'ANALYZED'
                )
            """)
            params.extend([analyzer_name or "", analyzer_version or "", engine_version])
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as conn:
            files = conn.execute(
                f"""
                SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json
                FROM files f
                JOIN sources s ON s.source_id = f.source_id
                {where}
                ORDER BY f.source_id, f.relative_path
                """,
                params,
            ).fetchall()
            sources = {row["source_id"]: row for row in conn.execute("SELECT * FROM sources").fetchall()}
        return files, sources

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _ensure_column(self, conn: sqlite3.Connection, table: str, column: str, declaration: str) -> None:
        columns = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
        if column not in columns:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")

    def _table_exists(self, table: str) -> bool:
        with self._connect() as conn:
            row = conn.execute("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", (table,)).fetchone()
        return row is not None

    def _decode_json(self, value: Optional[str]) -> Optional[Dict[str, Any]]:
        if not value:
            return None
        try:
            decoded = json.loads(value)
        except json.JSONDecodeError:
            return None
        return decoded if isinstance(decoded, dict) else None
