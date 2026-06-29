from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List

from knowledge_service.inventory_store import SQLITE_WRITE_BUSY_TIMEOUT_MS
from knowledge_service.observability import observed_connect


@dataclass(frozen=True)
class RetentionPolicy:
    inventory_build_days: int = 30
    analysis_job_days: int = 30
    analysis_diagnostic_days: int = 30
    graph_snapshot_days: int = 30
    graph_tombstone_days: int = 7
    keep_completed_jobs: int = 50
    keep_snapshots_per_source: int = 5


@dataclass
class MaintenanceResult:
    ran_at: str
    checkpoint: Dict[str, Any] = field(default_factory=dict)
    optimize: str = "not_run"
    retention: Dict[str, int] = field(default_factory=dict)
    integrity_check: str = "not_run"
    foreign_key_check_count: int = 0


class StorageOperations:
    def __init__(self, db_path: Path, policy: RetentionPolicy | None = None, busy_timeout_ms: int = SQLITE_WRITE_BUSY_TIMEOUT_MS) -> None:
        self.db_path = db_path
        self.policy = policy or RetentionPolicy()
        self.busy_timeout_ms = busy_timeout_ms
        self._last_result: MaintenanceResult | None = None

    def startup_maintenance(self) -> MaintenanceResult:
        return self.run_maintenance(checkpoint_mode="PASSIVE", run_optimize=False)

    def run_maintenance(self, *, checkpoint_mode: str = "TRUNCATE", run_optimize: bool = True) -> MaintenanceResult:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        ran_at = self._now()
        with self._connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute(f"PRAGMA busy_timeout = {self.busy_timeout_ms}")
            conn.execute("PRAGMA foreign_keys = ON")
            retention = self.apply_retention(conn)
            optimize = "not_run"
            if run_optimize:
                conn.execute("PRAGMA optimize")
                optimize = "ok"
            integrity = str(conn.execute("PRAGMA integrity_check").fetchone()[0])
            fk_count = len(conn.execute("PRAGMA foreign_key_check").fetchall())
        with self._connect() as conn:
            checkpoint = self._checkpoint(conn, checkpoint_mode)
        result = MaintenanceResult(
            ran_at=ran_at,
            checkpoint=checkpoint,
            optimize=optimize,
            retention=retention,
            integrity_check=integrity,
            foreign_key_check_count=fk_count,
        )
        self._last_result = result
        return result

    def diagnostics(self) -> Dict[str, Any]:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            journal_mode = str(conn.execute("PRAGMA journal_mode=WAL").fetchone()[0])
            busy_timeout = int(conn.execute("PRAGMA busy_timeout").fetchone()[0])
            foreign_keys = int(conn.execute("PRAGMA foreign_keys").fetchone()[0])
            integrity = str(conn.execute("PRAGMA integrity_check").fetchone()[0])
            fk_rows = conn.execute("PRAGMA foreign_key_check").fetchall()
            row_counts = self._row_counts(conn)
            largest_tables = self._largest_tables(conn)
        last = self._last_result
        return {
            "dbFileSizeBytes": self._file_size(self.db_path),
            "walFileSizeBytes": self._file_size(Path(f"{self.db_path}-wal")),
            "journalMode": journal_mode,
            "busyTimeoutMs": busy_timeout,
            "foreignKeys": bool(foreign_keys),
            "integrityCheck": integrity,
            "foreignKeyCheckResult": "ok" if not fk_rows else "failed",
            "foreignKeyCheckCount": len(fk_rows),
            "largestTables": largest_tables,
            "rowCounts": row_counts,
            "lastMaintenanceTime": last.ran_at if last else None,
            "lastMaintenanceResult": self._maintenance_payload(last),
            "retentionDeletionCounts": dict(last.retention) if last else {},
            "retentionPolicy": self.policy.__dict__,
        }

    def apply_retention(self, conn: sqlite3.Connection) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        now = datetime.now(timezone.utc)
        inventory_cutoff = self._iso(now - timedelta(days=self.policy.inventory_build_days))
        job_cutoff = self._iso(now - timedelta(days=self.policy.analysis_job_days))
        diagnostics_cutoff = self._iso(now - timedelta(days=self.policy.analysis_diagnostic_days))
        snapshot_cutoff = self._iso(now - timedelta(days=self.policy.graph_snapshot_days))
        tombstone_cutoff = self._iso(now - timedelta(days=self.policy.graph_tombstone_days))

        if self._table_exists(conn, "inventory_builds"):
            latest = self._ids(conn, "SELECT id FROM inventory_builds ORDER BY id DESC LIMIT 1")
            counts["inventory_builds"] = self._delete(
                conn,
                "DELETE FROM inventory_builds WHERE completed_at IS NOT NULL AND completed_at < ? AND id NOT IN (%s)" % self._placeholders(latest),
                [inventory_cutoff, *latest],
            )
        if self._table_exists(conn, "analysis_graph_diagnostics"):
            counts["analysis_diagnostics"] = self._delete(
                conn,
                """
                DELETE FROM analysis_graph_diagnostics
                WHERE created_at < ?
                  AND snapshot_id NOT IN (SELECT snapshot_id FROM graph_current_snapshots)
                  AND snapshot_id NOT IN (SELECT snapshot_id FROM graph_snapshot_tombstones WHERE expired_at >= ?)
                """,
                [diagnostics_cutoff, self._now()],
            )
        if self._table_exists(conn, "analysis_jobs"):
            protected_jobs = set(
                self._ids(
                    conn,
                    "SELECT job_id FROM analysis_jobs WHERE status IN ('QUEUED', 'RUNNING', 'STOP_REQUESTED')",
                )
            )
            protected_jobs.update(
                self._ids(
                    conn,
                    "SELECT job_id FROM analysis_jobs WHERE completed_at IS NOT NULL ORDER BY completed_at DESC LIMIT ?",
                    [self.policy.keep_completed_jobs],
                )
            )
            expired_jobs = self._ids(
                conn,
                "SELECT job_id FROM analysis_jobs WHERE completed_at IS NOT NULL AND completed_at < ?",
                [job_cutoff],
            )
            delete_jobs = [job_id for job_id in expired_jobs if job_id not in protected_jobs]
            if delete_jobs and self._table_exists(conn, "analysis_job_files"):
                counts["analysis_job_file_rows"] = self._delete(
                    conn,
                    "DELETE FROM analysis_job_files WHERE job_id IN (%s)" % self._placeholders(delete_jobs),
                    delete_jobs,
                )
            else:
                counts["analysis_job_file_rows"] = 0
            counts["analysis_jobs"] = self._delete(
                conn,
                "DELETE FROM analysis_jobs WHERE job_id IN (%s)" % self._placeholders(delete_jobs),
                delete_jobs,
            )
        if self._table_exists(conn, "context_chunks") and self._table_exists(conn, "files"):
            orphan_chunks = self._ids(
                conn,
                """
                SELECT context_chunks.id
                FROM context_chunks
                LEFT JOIN files ON files.id = context_chunks.file_id
                WHERE files.id IS NULL
                """,
            )
            if orphan_chunks and self._table_exists(conn, "context_chunks_fts"):
                self._delete(conn, "DELETE FROM context_chunks_fts WHERE rowid IN (%s)" % self._placeholders(orphan_chunks), orphan_chunks)
            counts["context_chunks_for_deleted_files"] = self._delete(
                conn,
                "DELETE FROM context_chunks WHERE id IN (%s)" % self._placeholders(orphan_chunks),
                orphan_chunks,
            )
        counts["orphaned_runtime_rows"] = self._delete_orphaned_runtime_rows(conn)
        counts.update(self._retain_graph_snapshots(conn, snapshot_cutoff, tombstone_cutoff))
        return counts

    def _retain_graph_snapshots(self, conn: sqlite3.Connection, snapshot_cutoff: str, tombstone_cutoff: str) -> Dict[str, int]:
        counts = {
            "graph_snapshots": 0,
            "graph_tombstones": 0,
            "graph_evidence_claims_diagnostics": 0,
        }
        if not self._table_exists(conn, "graph_snapshots"):
            return counts
        protected = set(self._ids(conn, "SELECT snapshot_id FROM graph_current_snapshots")) if self._table_exists(conn, "graph_current_snapshots") else set()
        if self._table_exists(conn, "graph_snapshot_tombstones"):
            protected.update(self._ids(conn, "SELECT snapshot_id FROM graph_snapshot_tombstones WHERE expired_at >= ?", [self._now()]))
            counts["graph_tombstones"] = self._delete(
                conn,
                "DELETE FROM graph_snapshot_tombstones WHERE expired_at < ?",
                [tombstone_cutoff],
            )
        for source_id in self._ids(conn, "SELECT DISTINCT source_id FROM graph_snapshots"):
            protected.update(
                self._ids(
                    conn,
                    """
                    SELECT snapshot_id
                    FROM graph_snapshots
                    WHERE source_id = ?
                    ORDER BY COALESCE(published_at, created_at) DESC
                    LIMIT ?
                    """,
                    [source_id, self.policy.keep_snapshots_per_source],
                )
            )
        candidates = self._ids(
            conn,
            """
            SELECT snapshot_id
            FROM graph_snapshots
            WHERE COALESCE(published_at, created_at) < ?
              AND state NOT IN ('BUILDING')
            """,
            [snapshot_cutoff],
        )
        delete_ids = [snapshot_id for snapshot_id in candidates if snapshot_id not in protected]
        for table in ("analysis_graph_claims", "analysis_graph_edges", "analysis_graph_diagnostics", "analysis_graph_evidence"):
            if self._table_exists(conn, table):
                counts["graph_evidence_claims_diagnostics"] += self._delete(
                    conn,
                    f"DELETE FROM {table} WHERE snapshot_id IN ({self._placeholders(delete_ids)})",
                    delete_ids,
                )
        for table in ("graph_snapshot_metrics", "analysis_graph_nodes"):
            if self._table_exists(conn, table):
                self._delete(conn, f"DELETE FROM {table} WHERE snapshot_id IN ({self._placeholders(delete_ids)})", delete_ids)
        counts["graph_snapshots"] = self._delete(
            conn,
            "DELETE FROM graph_snapshots WHERE snapshot_id IN (%s)" % self._placeholders(delete_ids),
            delete_ids,
        )
        return counts

    def _delete_orphaned_runtime_rows(self, conn: sqlite3.Connection) -> int:
        count = 0
        if self._table_exists(conn, "analysis_files") and self._table_exists(conn, "files"):
            count += self._delete(
                conn,
                """
                DELETE FROM analysis_files
                WHERE file_id NOT IN (SELECT id FROM files)
                  AND status NOT IN ('PENDING')
                """,
                [],
            )
        return count

    def _checkpoint(self, conn: sqlite3.Connection, mode: str) -> Dict[str, Any]:
        safe_mode = mode.upper() if mode.upper() in {"PASSIVE", "FULL", "RESTART", "TRUNCATE"} else "PASSIVE"
        row = conn.execute(f"PRAGMA wal_checkpoint({safe_mode})").fetchone()
        return {"mode": safe_mode, "busy": int(row[0]), "logFrames": int(row[1]), "checkpointedFrames": int(row[2])}

    def _connect(self) -> sqlite3.Connection:
        timeout_seconds = max(self.busy_timeout_ms, 1) / 1000.0
        conn = observed_connect(self.db_path, timeout=timeout_seconds)
        conn.execute(f"PRAGMA busy_timeout = {self.busy_timeout_ms}")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        return conn

    def _row_counts(self, conn: sqlite3.Connection) -> Dict[str, int]:
        tables = [
            "inventory_builds",
            "sources",
            "files",
            "context_chunks",
            "analysis_jobs",
            "analysis_job_files",
            "analysis_files",
            "graph_snapshots",
            "graph_current_snapshots",
            "graph_snapshot_tombstones",
            "analysis_graph_nodes",
            "analysis_graph_edges",
            "analysis_graph_evidence",
            "analysis_graph_claims",
            "analysis_graph_diagnostics",
            "semantic_index_state",
            "semantic_documents",
            "semantic_vectors",
        ]
        return {table: self._count(conn, table) for table in tables if self._table_exists(conn, table)}

    def _largest_tables(self, conn: sqlite3.Connection) -> List[Dict[str, Any]]:
        rows = conn.execute(
            """
            SELECT name
            FROM sqlite_master
            WHERE type IN ('table', 'virtual table')
              AND name NOT LIKE 'sqlite_%'
            ORDER BY name
            """
        ).fetchall()
        sizes = [{"table": row["name"], "rowCount": self._count(conn, row["name"])} for row in rows]
        return sorted(sizes, key=lambda item: item["rowCount"], reverse=True)[:10]

    def _maintenance_payload(self, result: MaintenanceResult | None) -> Dict[str, Any] | None:
        if result is None:
            return None
        return {
            "ranAt": result.ran_at,
            "checkpoint": result.checkpoint,
            "optimize": result.optimize,
            "integrityCheck": result.integrity_check,
            "foreignKeyCheckCount": result.foreign_key_check_count,
        }

    def _count(self, conn: sqlite3.Connection, table: str) -> int:
        try:
            return int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
        except sqlite3.Error:
            return 0

    def _ids(self, conn: sqlite3.Connection, sql: str, params: Iterable[Any] | None = None) -> List[Any]:
        return [row[0] for row in conn.execute(sql, list(params or [])).fetchall()]

    def _delete(self, conn: sqlite3.Connection, sql: str, params: List[Any]) -> int:
        if "IN ()" in sql:
            return 0
        cursor = conn.execute(sql, params)
        return max(int(cursor.rowcount or 0), 0)

    def _table_exists(self, conn: sqlite3.Connection, table: str) -> bool:
        return conn.execute("SELECT 1 FROM sqlite_master WHERE type IN ('table', 'virtual table') AND name = ?", (table,)).fetchone() is not None

    def _placeholders(self, values: Iterable[Any]) -> str:
        values = list(values)
        return ",".join("?" for _ in values) if values else ""

    def _file_size(self, path: Path) -> int:
        return path.stat().st_size if path.exists() else 0

    def _now(self) -> str:
        return self._iso(datetime.now(timezone.utc))

    def _iso(self, value: datetime) -> str:
        return value.isoformat()
