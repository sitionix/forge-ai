from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from knowledge_service.file_metadata import FileMetadata
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
                    error_message TEXT
                )
            """)
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
                    size_bytes INTEGER NOT NULL,
                    content_hash TEXT NOT NULL,
                    last_modified TEXT NOT NULL,
                    indexed_at TEXT NOT NULL
                )
            """)

    def replace_inventory(self, sources: List[SourceMetadata], files: List[FileMetadata], skipped: int, started_at: str, completed_at: str) -> Dict[str, Any]:
        self.init()
        now = datetime.now(timezone.utc).isoformat()
        with self._connect() as conn:
            conn.execute("DELETE FROM files")
            conn.execute("DELETE FROM sources")
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
                    "INSERT INTO files(source_id, source_path, absolute_path, relative_path, extension, size_bytes, content_hash, last_modified, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (file.sourceId, file.sourcePath, file.absolutePath, file.relativePath, file.extension, file.sizeBytes, file.contentHash, file.lastModified, now),
                )
            conn.execute(
                "INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, error_message) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (started_at, completed_at, "COMPLETED", len(sources), len(files), skipped, None),
            )
        return {"status": "COMPLETED", "sourceCount": len(sources), "fileCount": len(files), "skippedCount": skipped, "startedAt": started_at, "completedAt": completed_at}

    def status(self) -> Dict[str, Any]:
        self.init()
        with self._connect() as conn:
            build = conn.execute("SELECT completed_at, status, source_count, file_count, skipped_count FROM inventory_builds ORDER BY id DESC LIMIT 1").fetchone()
        if not build:
            return {"status": "EMPTY", "sourceCount": 0, "fileCount": 0}
        return {"status": "READY" if build["status"] == "COMPLETED" else build["status"], "lastBuildAt": build["completed_at"], "sourceCount": build["source_count"], "fileCount": build["file_count"], "skippedCount": build["skipped_count"]}

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
                f"SELECT source_id, source_path, relative_path, extension, size_bytes, content_hash, last_modified FROM files {where} ORDER BY source_id, relative_path LIMIT ? OFFSET ?",
                [*params, limit, offset],
            ).fetchall()
        return {
            "files": [{
                "sourceId": row["source_id"], "sourcePath": row["source_path"], "relativePath": row["relative_path"],
                "extension": row["extension"], "sizeBytes": row["size_bytes"], "contentHash": row["content_hash"], "lastModified": row["last_modified"],
            } for row in rows],
            "limit": limit, "offset": offset, "total": total,
        }

    def search_rows(self, source_ids: List[str], groups: List[str]) -> Tuple[List[sqlite3.Row], Dict[str, sqlite3.Row]]:
        self.init()
        clauses: list[str] = []
        params: list[Any] = []
        if source_ids:
            clauses.append("f.source_id IN (%s)" % ",".join("?" for _ in source_ids))
            params.extend(source_ids)
        if groups:
            clauses.append("s.group_name IN (%s)" % ",".join("?" for _ in groups))
            params.extend(groups)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as conn:
            files = conn.execute(f"SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json FROM files f JOIN sources s ON s.source_id = f.source_id {where}", params).fetchall()
            sources = {row["source_id"]: row for row in conn.execute("SELECT * FROM sources").fetchall()}
        return files, sources

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn
