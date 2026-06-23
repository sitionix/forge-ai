from __future__ import annotations

import json
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from knowledge_service.file_metadata import FileMetadata
from knowledge_service.graph_schema import GRAPH_ANALYSIS_ENGINE_VERSION
from knowledge_service.overview_projection import ensure_overview_schema, rebuild_overview, refresh_overview_for_sources
from knowledge_service.skipped_reasons import SkippedBreakdown, normalize_skipped_breakdown
from knowledge_service.source_catalog import SourceMetadata


SQLITE_WRITE_BUSY_TIMEOUT_MS = 5000


class InventoryStore:
    _init_lock = threading.Lock()
    _initialized_paths: Set[str] = set()

    def __init__(self, db_path: Path):
        self.db_path = db_path

    def init(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        init_key = str(self.db_path.resolve())
        if init_key in InventoryStore._initialized_paths:
            return
        with InventoryStore._init_lock:
            if init_key in InventoryStore._initialized_paths:
                return
            with self._connect() as conn:
                conn.execute("PRAGMA journal_mode=WAL")
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
                conn.execute("CREATE INDEX IF NOT EXISTS idx_files_source ON files(source_id)")
                conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_files_source_path ON files(source_id, relative_path)")
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS context_chunks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        file_id INTEGER NOT NULL,
                        source_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        line_start INTEGER NOT NULL,
                        line_end INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        indexed_at TEXT NOT NULL,
                        FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE
                    )
                """)
                conn.execute("CREATE INDEX IF NOT EXISTS idx_context_chunks_file ON context_chunks(file_id)")
                conn.execute("CREATE INDEX IF NOT EXISTS idx_context_chunks_source_path ON context_chunks(source_id, relative_path)")
                conn.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS context_chunks_fts
                    USING fts5(content, source_id UNINDEXED, relative_path UNINDEXED, chunk_id UNINDEXED)
                """)
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS inventory_source_state (
                        source_id TEXT PRIMARY KEY,
                        eligible_file_count INTEGER NOT NULL,
                        skipped_count INTEGER,
                        skipped_reasons_json TEXT,
                        last_inventory_at TEXT NOT NULL
                        )
                    """)
                ensure_overview_schema(conn)
                rebuild_overview(conn)
            InventoryStore._initialized_paths.add(init_key)

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
            self._ensure_context_schema(conn)
            if replace_all:
                incoming_source_ids = sorted({source.sourceId for source in sources})
                if incoming_source_ids:
                    placeholders = ",".join("?" for _ in incoming_source_ids)
                    removed_rows = conn.execute(f"SELECT id FROM files WHERE source_id NOT IN ({placeholders})", incoming_source_ids).fetchall()
                    self._delete_context_for_file_ids(conn, [int(row["id"]) for row in removed_rows])
                    conn.execute(f"DELETE FROM files WHERE source_id NOT IN ({placeholders})", incoming_source_ids)
                    conn.execute(f"DELETE FROM sources WHERE source_id NOT IN ({placeholders})", incoming_source_ids)
                    conn.execute(f"DELETE FROM inventory_source_state WHERE source_id NOT IN ({placeholders})", incoming_source_ids)
                else:
                    removed_rows = conn.execute("SELECT id FROM files").fetchall()
                    self._delete_context_for_file_ids(conn, [int(row["id"]) for row in removed_rows])
                    conn.execute("DELETE FROM files")
                    conn.execute("DELETE FROM sources")
                    conn.execute("DELETE FROM inventory_source_state")
            for source in sources:
                conn.execute(
                    """
                    INSERT INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(source_id) DO UPDATE SET
                        display_name = excluded.display_name,
                        group_name = excluded.group_name,
                        path = excluded.path,
                        root_exists = excluded.root_exists,
                        tags_json = excluded.tags_json,
                        metadata_json = excluded.metadata_json,
                        last_seen_at = excluded.last_seen_at
                    """,
                    (
                        source.sourceId,
                        source.displayName,
                        source.group,
                        source.path,
                        1 if source.rootExists else 0,
                        json.dumps(source.tags),
                        json.dumps(source.public_dict(include_absolute_root=True)),
                        now,
                    ),
                )
            files_by_source_path: Dict[str, set[str]] = {}
            for file in files:
                files_by_source_path.setdefault(file.sourceId, set()).add(file.relativePath)
            for source_id in scoped_source_ids:
                current_paths = files_by_source_path.get(source_id, set())
                if current_paths:
                    placeholders = ",".join("?" for _ in current_paths)
                    params = [source_id, *sorted(current_paths)]
                    removed_rows = conn.execute(f"SELECT id FROM files WHERE source_id = ? AND relative_path NOT IN ({placeholders})", params).fetchall()
                    self._delete_context_for_file_ids(conn, [int(row["id"]) for row in removed_rows])
                    conn.execute(f"DELETE FROM files WHERE source_id = ? AND relative_path NOT IN ({placeholders})", params)
                else:
                    removed_rows = conn.execute("SELECT id FROM files WHERE source_id = ?", (source_id,)).fetchall()
                    self._delete_context_for_file_ids(conn, [int(row["id"]) for row in removed_rows])
                    conn.execute("DELETE FROM files WHERE source_id = ?", (source_id,))
            for file in files:
                existing = conn.execute(
                    "SELECT id, content_hash FROM files WHERE source_id = ? AND relative_path = ?",
                    (file.sourceId, file.relativePath),
                ).fetchone()
                if existing is None:
                    cursor = conn.execute(
                        "INSERT INTO files(source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            file.sourceId,
                            file.sourcePath,
                            file.absolutePath,
                            file.relativePath,
                            file.extension,
                            file.language,
                            file.flowDomain,
                            file.sizeBytes,
                            file.contentHash,
                            file.lastModified,
                            file.lineCount,
                            file.decodePolicy,
                            now,
                        ),
                    )
                    file_id = int(cursor.lastrowid)
                    self._replace_context_chunks(conn, file_id, file, now)
                    continue
                file_id = int(existing["id"])
                content_changed = str(existing["content_hash"]) != file.contentHash
                conn.execute(
                    """
                    UPDATE files
                    SET source_path = ?, absolute_path = ?, extension = ?, language = ?, flow_domain = ?,
                        size_bytes = ?, content_hash = ?, last_modified = ?, line_count = ?, decode_policy = ?, indexed_at = ?
                    WHERE id = ?
                    """,
                    (
                        file.sourcePath,
                        file.absolutePath,
                        file.extension,
                        file.language,
                        file.flowDomain,
                        file.sizeBytes,
                        file.contentHash,
                        file.lastModified,
                        file.lineCount,
                        file.decodePolicy,
                        now,
                        file_id,
                    ),
                )
                if content_changed or file.changed:
                    self._replace_context_chunks(conn, file_id, file, now)
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
            refresh_overview_for_sources(conn, scoped_source_ids)
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

    def current_file_index(self, source_id: str) -> Dict[str, Dict[str, object]]:
        self.init()
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT relative_path, content_hash, size_bytes, last_modified, line_count, decode_policy
                FROM files
                WHERE source_id = ?
                """,
                (source_id,),
            ).fetchall()
        return {
            row["relative_path"]: {
                "contentHash": row["content_hash"],
                "sizeBytes": row["size_bytes"],
                "lastModified": row["last_modified"],
                "lineCount": row["line_count"],
                "decodePolicy": row["decode_policy"],
            }
            for row in rows
        }

    def status(self) -> Dict[str, Any]:
        self.init()
        with self._connect() as conn:
            build = conn.execute(
                "SELECT completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json FROM inventory_builds ORDER BY id DESC LIMIT 1"
            ).fetchone()
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
            "files": [
                {
                    "sourceId": row["source_id"],
                    "sourcePath": row["source_path"],
                    "relativePath": row["relative_path"],
                    "extension": row["extension"],
                    "language": row["language"],
                    "flowDomain": row["flow_domain"],
                    "sizeBytes": row["size_bytes"],
                    "contentHash": row["content_hash"],
                    "lastModified": row["last_modified"],
                    "lineCount": row["line_count"],
                    "decodePolicy": row["decode_policy"],
                }
                for row in rows
            ],
            "limit": limit,
            "offset": offset,
            "total": total,
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
        return [
            {
                "id": row["id"],
                "sourceId": row["source_id"],
                "relativePath": row["relative_path"],
                "contentHash": row["content_hash"],
                "sizeBytes": row["size_bytes"],
                "lastModified": row["last_modified"],
                "lineCount": row["line_count"],
                "decodePolicy": row["decode_policy"],
            }
            for row in rows
        ]

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

    def search_context_chunks(self, query: str, source_ids: List[str], groups: List[str], limit: int, include_content: bool) -> List[sqlite3.Row]:
        self.init()
        safe_limit = max(1, min(int(limit or 1), 200))
        clauses = ["context_chunks_fts MATCH ?"]
        params: list[Any] = [self._fts_query(query)]
        if source_ids:
            clauses.append("c.source_id IN (%s)" % ",".join("?" for _ in source_ids))
            params.extend(source_ids)
        if groups:
            clauses.append("s.group_name IN (%s)" % ",".join("?" for _ in groups))
            params.extend(groups)
        content_column = "c.content" if include_content else "NULL AS content"
        with self._connect() as conn:
            return conn.execute(
                f"""
                SELECT
                    c.id AS chunk_id,
                    c.source_id,
                    c.relative_path,
                    c.content_hash,
                    c.line_start,
                    c.line_end,
                    {content_column},
                    f.language,
                    f.flow_domain,
                    s.display_name,
                    s.group_name,
                    s.metadata_json,
                    bm25(context_chunks_fts) AS rank_score
                FROM context_chunks_fts
                JOIN context_chunks c ON c.id = context_chunks_fts.chunk_id
                JOIN files f ON f.id = c.file_id
                JOIN sources s ON s.source_id = c.source_id
                WHERE {' AND '.join(clauses)}
                ORDER BY rank_score, c.source_id, c.relative_path, c.line_start
                LIMIT ?
                """,
                [*params, safe_limit],
            ).fetchall()

    def _ensure_context_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS context_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                content TEXT NOT NULL,
                indexed_at TEXT NOT NULL,
                FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE
            )
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_context_chunks_file ON context_chunks(file_id)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_context_chunks_source_path ON context_chunks(source_id, relative_path)")
        conn.execute("""
            CREATE VIRTUAL TABLE IF NOT EXISTS context_chunks_fts
            USING fts5(content, source_id UNINDEXED, relative_path UNINDEXED, chunk_id UNINDEXED)
        """)

    def _replace_context_chunks(self, conn: sqlite3.Connection, file_id: int, file: FileMetadata, indexed_at: str) -> None:
        self._delete_context_for_file_ids(conn, [file_id])
        if not file.chunks:
            return
        source = conn.execute("SELECT display_name, group_name, tags_json, metadata_json FROM sources WHERE source_id = ?", (file.sourceId,)).fetchone()
        source_text = ""
        if source is not None:
            source_text = " ".join(
                [
                    str(source["display_name"] or ""),
                    str(source["group_name"] or ""),
                    str(source["tags_json"] or ""),
                    str(source["metadata_json"] or ""),
                ]
            )
        for chunk in file.chunks:
            cursor = conn.execute(
                """
                INSERT INTO context_chunks(file_id, source_id, relative_path, content_hash, line_start, line_end, content, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (file_id, file.sourceId, file.relativePath, file.contentHash, chunk.lineStart, chunk.lineEnd, chunk.content, indexed_at),
            )
            chunk_id = int(cursor.lastrowid)
            conn.execute(
                "INSERT INTO context_chunks_fts(rowid, content, source_id, relative_path, chunk_id) VALUES (?, ?, ?, ?, ?)",
                (chunk_id, self._searchable_content(chunk.content, file.relativePath, source_text), file.sourceId, file.relativePath, chunk_id),
            )

    def _delete_context_for_file_ids(self, conn: sqlite3.Connection, file_ids: List[int]) -> None:
        if not file_ids:
            return
        placeholders = ",".join("?" for _ in file_ids)
        chunk_ids = [int(row["id"]) for row in conn.execute(f"SELECT id FROM context_chunks WHERE file_id IN ({placeholders})", file_ids).fetchall()]
        if chunk_ids:
            chunk_placeholders = ",".join("?" for _ in chunk_ids)
            conn.execute(f"DELETE FROM context_chunks_fts WHERE rowid IN ({chunk_placeholders})", chunk_ids)
        conn.execute(f"DELETE FROM context_chunks WHERE file_id IN ({placeholders})", file_ids)

    def _chunk_content(self, content: str, chunk_lines: int = 80, overlap_lines: int = 8) -> List[tuple[int, int, str]]:
        lines = content.splitlines()
        if not lines:
            return []
        chunks: list[tuple[int, int, str]] = []
        start = 1
        step = max(1, chunk_lines - overlap_lines)
        while start <= len(lines):
            end = min(len(lines), start + chunk_lines - 1)
            chunk = "\n".join(lines[start - 1 : end]).strip()
            if chunk:
                chunks.append((start, end, chunk))
            if end == len(lines):
                break
            start += step
        return chunks

    def _fts_query(self, query: str) -> str:
        terms = []
        for raw in query.replace("/", " ").replace("\\", " ").replace(".", " ").replace("_", " ").replace("-", " ").split():
            for part in self._split_identifier(raw):
                term = "".join(ch for ch in part.lower() if ch.isalnum())
                if term:
                    terms.append(f"{term}*")
        return " OR ".join(terms) if terms else '""'

    def _searchable_content(self, content: str, relative_path: str, source_text: str) -> str:
        tokens: list[str] = [relative_path, content, source_text]
        for raw in re.findall(r"[A-Za-z][A-Za-z0-9_]*", f"{relative_path}\n{content}\n{source_text}"):
            tokens.extend(self._split_identifier(raw))
        return "\n".join(tokens)

    def _split_identifier(self, value: str) -> List[str]:
        spaced = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", value.replace("_", " "))
        return [part for part in spaced.split() if part]

    def _connect(self, busy_timeout_ms: int = SQLITE_WRITE_BUSY_TIMEOUT_MS) -> sqlite3.Connection:
        timeout_seconds = max(busy_timeout_ms, 1) / 1000.0
        conn = sqlite3.connect(self.db_path, timeout=timeout_seconds)
        conn.execute(f"PRAGMA busy_timeout = {max(int(busy_timeout_ms), 1)}")
        conn.execute("PRAGMA foreign_keys = ON")
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
