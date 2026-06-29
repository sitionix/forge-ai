from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

from knowledge_service.observability import observed_connect
from knowledge_service.semantic_index import SemanticIndexStore


def ensure_overview_schema(conn: sqlite3.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS knowledge_source_overview (
            source_id TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            group_name TEXT,
            source_path TEXT NOT NULL,
            root_exists INTEGER NOT NULL,
            inventory_status TEXT NOT NULL,
            inventory_file_count INTEGER NOT NULL,
            skipped_file_count INTEGER NOT NULL,
            analysis_state TEXT NOT NULL,
            analysis_total_files INTEGER NOT NULL,
            analysis_processed_files INTEGER NOT NULL,
            analysis_succeeded_files INTEGER NOT NULL,
            analysis_partial_files INTEGER NOT NULL,
            analysis_failed_files INTEGER NOT NULL,
            analysis_skipped_files INTEGER NOT NULL,
            analysis_pending_files INTEGER NOT NULL,
            completion_percent REAL NOT NULL,
            active_job_id TEXT,
            active_job_mode TEXT,
            active_job_total_files INTEGER,
            active_job_processed_files INTEGER,
            active_job_failed_files INTEGER,
            active_job_current_relative_path TEXT,
            updated_at TEXT NOT NULL,
            version INTEGER NOT NULL
        )
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_source_overview_version ON knowledge_source_overview(version)")


def refresh_overview_for_sources(conn: sqlite3.Connection, source_ids: Optional[Iterable[str]] = None) -> None:
    ensure_overview_schema(conn)
    if not _table_exists(conn, "sources"):
        conn.execute("DELETE FROM knowledge_source_overview")
        return
    ids = sorted({source_id for source_id in (source_ids or []) if source_id})
    if not ids:
        ids = [row["source_id"] for row in conn.execute("SELECT source_id FROM sources ORDER BY source_id").fetchall()]
    if not ids:
        conn.execute("DELETE FROM knowledge_source_overview")
        return

    existing_sources = {
        row["source_id"]: row
        for row in conn.execute(
            f"SELECT * FROM sources WHERE source_id IN ({','.join('?' for _ in ids)})",
            ids,
        ).fetchall()
    }
    for source_id in ids:
        source = existing_sources.get(source_id)
        if source is None:
            conn.execute("DELETE FROM knowledge_source_overview WHERE source_id = ?", (source_id,))
            continue
        _refresh_one(conn, source)


def rebuild_overview(conn: sqlite3.Connection) -> None:
    ensure_overview_schema(conn)
    if not _table_exists(conn, "sources"):
        conn.execute("DELETE FROM knowledge_source_overview")
        return
    conn.execute("DELETE FROM knowledge_source_overview WHERE source_id NOT IN (SELECT source_id FROM sources)")
    refresh_overview_for_sources(conn, None)


def read_overview(db_path: Path) -> Dict[str, Any]:
    with observed_connect(db_path, timeout=0.5) as conn:
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout = 500")
        rows = conn.execute(
            """
            SELECT *
            FROM knowledge_source_overview
            ORDER BY source_id
            """
        ).fetchall()
        semantic_statuses = SemanticIndexStore.statuses_for_sources_conn(conn, [row["source_id"] for row in rows])
    max_version = max((int(row["version"] or 0) for row in rows), default=0)
    updated_at = max((row["updated_at"] for row in rows if row["updated_at"]), default=None)
    sources = [_overview_source(row, semantic_statuses.get(row["source_id"])) for row in rows]
    active = next((source["activeJob"] for source in sources if source["activeJob"] is not None), None)
    return {
        "version": max_version,
        "updatedAt": updated_at,
        "sources": sources,
        "activeJob": active,
    }


def _refresh_one(conn: sqlite3.Connection, source: sqlite3.Row) -> None:
    now = datetime.now(timezone.utc).isoformat()
    source_id = source["source_id"]
    source_columns = set(source.keys())
    group_name = source["group_name"] if "group_name" in source_columns else None
    source_path = source["path"] if "path" in source_columns else ""
    root_exists = int(source["root_exists"] or 0) if "root_exists" in source_columns else 1
    inventory = None
    if _table_exists(conn, "inventory_source_state"):
        inventory = conn.execute(
            """
            SELECT eligible_file_count, skipped_count
            FROM inventory_source_state
            WHERE source_id = ?
            """,
            (source_id,),
        ).fetchone()
    file_columns = _table_columns(conn, "files")
    if _table_exists(conn, "analysis_files") and "content_hash" in file_columns:
        counts = conn.execute(
            """
            SELECT
                COUNT(f.id) AS total,
                SUM(CASE WHEN af.status = 'ANALYZED' THEN 1 ELSE 0 END) AS succeeded,
                SUM(CASE WHEN af.status = 'PARTIAL' THEN 1 ELSE 0 END) AS partial,
                SUM(CASE WHEN af.status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
                SUM(CASE WHEN af.status = 'SKIPPED_TOO_LARGE_FOR_AI_ANALYSIS' THEN 1 ELSE 0 END) AS skipped
            FROM files f
            LEFT JOIN analysis_files af
              ON af.source_id = f.source_id
             AND af.relative_path = f.relative_path
             AND af.content_hash = f.content_hash
            WHERE f.source_id = ?
            """,
            (source_id,),
        ).fetchone()
    else:
        counts = conn.execute(
            """
            SELECT COUNT(f.id) AS total, 0 AS succeeded, 0 AS partial, 0 AS failed, 0 AS skipped
            FROM files f
            WHERE f.source_id = ?
            """,
            (source_id,),
        ).fetchone()
    active = None
    if _table_exists(conn, "analysis_jobs") and _table_exists(conn, "analysis_job_files"):
        active = conn.execute(
            """
            SELECT j.*
            FROM analysis_jobs j
            WHERE j.status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')
              AND (
                j.current_source_id = ?
                OR EXISTS (
                    SELECT 1
                    FROM analysis_job_files jf
                    WHERE jf.job_id = j.job_id
                      AND jf.source_id = ?
                )
              )
            ORDER BY COALESCE(j.last_progress_at, j.started_at) DESC
            LIMIT 1
            """,
            (source_id, source_id),
        ).fetchone()
    total = _int_value(counts, "total")
    succeeded = _int_value(counts, "succeeded")
    partial = _int_value(counts, "partial")
    failed = _int_value(counts, "failed")
    skipped = _int_value(counts, "skipped")
    processed = succeeded + partial + failed + skipped
    pending = max(total - processed, 0)
    analysis_state = _analysis_state(active, total, processed, failed, pending)
    previous = conn.execute(
        "SELECT version FROM knowledge_source_overview WHERE source_id = ?",
        (source_id,),
    ).fetchone()
    conn.execute(
        """
        INSERT OR REPLACE INTO knowledge_source_overview(
            source_id, display_name, group_name, source_path, root_exists,
            inventory_status, inventory_file_count, skipped_file_count,
            analysis_state, analysis_total_files, analysis_processed_files,
            analysis_succeeded_files, analysis_partial_files, analysis_failed_files,
            analysis_skipped_files, analysis_pending_files, completion_percent,
            active_job_id, active_job_mode, active_job_total_files,
            active_job_processed_files, active_job_failed_files,
            active_job_current_relative_path, updated_at, version
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            source_id,
            source["display_name"],
            group_name,
            source_path,
            root_exists,
            "READY" if root_exists else "UNAVAILABLE",
            _int_value(inventory, "eligible_file_count", total),
            _int_value(inventory, "skipped_count"),
            analysis_state,
            total,
            processed,
            succeeded,
            partial,
            failed,
            skipped,
            pending,
            round((processed / total) * 100, 1) if total else 0.0,
            active["job_id"] if active else None,
            active["mode"] if active and "mode" in active.keys() else None,
            int(active["file_count"] or 0) if active else None,
            int(active["processed_file_count"] or 0) if active else None,
            int(active["failed_file_count"] or 0) if active else None,
            active["current_relative_path"] if active else None,
            now,
            int(previous["version"] or 0) + 1 if previous else 1,
        ),
    )


def _analysis_state(active: Optional[sqlite3.Row], total: int, processed: int, failed: int, pending: int) -> str:
    if active is not None:
        return "STOP_REQUESTED" if active["status"] == "STOP_REQUESTED" else "RUNNING"
    if total == 0:
        return "EMPTY"
    if processed == 0:
        return "NOT_ANALYZED"
    if pending == 0 and failed == 0:
        return "COMPLETED"
    return "PARTIAL"


def _int_value(row: Optional[sqlite3.Row], key: str, default: int = 0) -> int:
    if row is None:
        return default
    value = row[key]
    return int(value or 0)


def _table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table_name,),
    ).fetchone()
    return row is not None


def _table_columns(conn: sqlite3.Connection, table_name: str) -> set[str]:
    if not _table_exists(conn, table_name):
        return set()
    return {row["name"] for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()}


def _overview_source(row: sqlite3.Row, semantic_index: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    active_job = None
    if row["active_job_id"]:
        active_job = {
            "jobId": row["active_job_id"],
            "sourceId": row["source_id"],
            "status": row["analysis_state"],
            "mode": row["active_job_mode"],
            "selectedFileCount": row["active_job_total_files"],
            "processedFileCount": row["active_job_processed_files"],
            "failedFileCount": row["active_job_failed_files"],
            "currentRelativePath": row["active_job_current_relative_path"],
        }
    return {
        "sourceId": row["source_id"],
        "displayName": row["display_name"],
        "group": row["group_name"],
        "rootExists": bool(row["root_exists"]),
        "inventory": {
            "status": row["inventory_status"],
            "fileCount": row["inventory_file_count"],
            "skippedCount": row["skipped_file_count"],
        },
        "analysis": {
            "status": row["analysis_state"],
            "totalFiles": row["analysis_total_files"],
            "processedFiles": row["analysis_processed_files"],
            "succeededFiles": row["analysis_succeeded_files"],
            "partialFiles": row["analysis_partial_files"],
            "failedFiles": row["analysis_failed_files"],
            "skippedFiles": row["analysis_skipped_files"],
            "pendingFiles": row["analysis_pending_files"],
            "percent": row["completion_percent"],
            "activeJobId": row["active_job_id"],
            "activeJobMode": row["active_job_mode"],
            "activeJobSelectedFileCount": row["active_job_total_files"],
            "activeJobProcessedFileCount": row["active_job_processed_files"],
            "activeJobFailedFileCount": row["active_job_failed_files"],
            "activeJobCurrentRelativePath": row["active_job_current_relative_path"],
        },
        "semanticIndex": semantic_index
        or {
            "status": "MISSING",
            "graphRevision": None,
            "builderVersion": 1,
            "totalNodeCount": 0,
            "indexedNodeCount": 0,
            "progressPercent": 0.0,
            "ready": False,
            "stale": False,
            "embeddingModel": None,
            "embeddingDimension": None,
            "updatedAt": None,
            "startedAt": None,
            "completedAt": None,
            "lastBuildId": None,
            "lastError": None,
        },
        "activeJob": active_job,
        "updatedAt": row["updated_at"],
        "version": row["version"],
    }
