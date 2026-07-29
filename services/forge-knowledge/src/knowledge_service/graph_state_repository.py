from __future__ import annotations

import json
import logging
import sqlite3
from typing import Any, List, Mapping, Optional, Sequence

from knowledge_service.overview_projection import refresh_overview_for_sources
from knowledge_service.semantic_index import SemanticIndexStore


GRAPH_STATE_DIRTY = "DIRTY"
GRAPH_STATE_FINALIZING = "FINALIZING"
GRAPH_STATE_READY = "READY"
GRAPH_STATE_FAILED = "FAILED"

LOGGER = logging.getLogger(__name__)


class GraphStateRepository:
    def __init__(self, store: Any) -> None:
        self.store = store

    def dirty_source_ids(self, source_ids: Optional[Sequence[str]] = None) -> List[str]:
        statuses = (GRAPH_STATE_DIRTY, GRAPH_STATE_FINALIZING, GRAPH_STATE_FAILED)
        clauses = [f"status IN ({','.join('?' for _ in statuses)})"]
        params: List[Any] = list(statuses)
        if source_ids:
            clauses.append(f"source_id IN ({','.join('?' for _ in source_ids)})")
            params.extend(source_ids)
        with self.store._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT source_id
                FROM analysis_graph_state
                WHERE {" AND ".join(clauses)}
                ORDER BY source_id
                """,
                params,
            ).fetchall()
        return [str(row["source_id"]) for row in rows]

    def mark_failed(self, source_id: str, diagnostic: Mapping[str, Any], updated_at: str) -> None:
        def write(conn: sqlite3.Connection) -> None:
            self.set_status_conn(conn, source_id, GRAPH_STATE_FAILED, updated_at, [diagnostic])
            refresh_overview_for_sources(conn, [source_id])

        try:
            self.store._write_with_busy_retry(write)
        except Exception:
            LOGGER.warning("Failed to persist graph FAILED state for source %s", source_id, exc_info=True)

    def mark_dirty_conn(self, conn: sqlite3.Connection, source_id: str, updated_at: str) -> None:
        self.set_status_conn(conn, source_id, GRAPH_STATE_DIRTY, updated_at)
        SemanticIndexStore.mark_current_graph_pending_conn(conn, source_id)

    def set_status_conn(
        self,
        conn: sqlite3.Connection,
        source_id: str,
        status: str,
        updated_at: str,
        diagnostics: Optional[Sequence[Mapping[str, Any]]] = None,
    ) -> None:
        existing = conn.execute("SELECT * FROM analysis_graph_state WHERE source_id = ?", (source_id,)).fetchone()
        diagnostics_json = json.dumps([dict(item) for item in diagnostics or []])
        if existing is None:
            conn.execute(
                """
                INSERT INTO analysis_graph_state(
                    source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count,
                    status, diagnostics_json, updated_at
                )
                VALUES (?, '', '', 0, 0, 0, 0, ?, ?, ?)
                """,
                (source_id, status, diagnostics_json, updated_at),
            )
            return
        conn.execute(
            """
            UPDATE analysis_graph_state
            SET status = ?,
                diagnostics_json = ?,
                updated_at = ?
            WHERE source_id = ?
            """,
            (status, diagnostics_json, updated_at, source_id),
        )

    def identity_by_source(self, conn: sqlite3.Connection, source_ids: List[str]) -> dict[str, dict[str, Optional[str]]]:
        if not source_ids or not self.store._table_exists(conn, "analysis_graph_state"):
            return {}
        placeholders = ",".join("?" for _ in source_ids)
        rows = conn.execute(
            f"""
            SELECT source_id, graph_id, content_identity
            FROM analysis_graph_state
            WHERE source_id IN ({placeholders})
            """,
            [*source_ids],
        ).fetchall()
        return {
            str(row["source_id"]): {
                "graphId": row["graph_id"],
                "graphRevision": row["content_identity"],
            }
            for row in rows
        }
