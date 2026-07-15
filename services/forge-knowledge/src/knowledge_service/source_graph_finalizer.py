from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from typing import Any

from knowledge_service.graph_state_repository import GRAPH_STATE_FINALIZING, GraphStateRepository
from knowledge_service.overview_projection import refresh_overview_for_sources
from knowledge_service.semantic_index import SemanticIndexStore


class CrossSourceGraphResolver:
    def __init__(self, store: Any) -> None:
        self.store = store

    def finalize_source(self, conn: sqlite3.Connection, source_id: str, created_at: str) -> None:
        self.store._resolve_source_type_relation_edges(conn, source_id)
        self.store._refresh_source_overrides_and_inherited_entrypoints(conn, source_id, created_at)
        self.store._resolve_source_call_edges(conn, source_id)
        self.store._expand_source_interface_dispatch_edges(conn, source_id)
        graph_id = self.store._refresh_graph_state(conn, source_id, created_at)
        if graph_id:
            SemanticIndexStore.mark_current_graph_pending_conn(conn, source_id)
        refresh_overview_for_sources(conn, [source_id])


class SourceGraphFinalizer:
    def __init__(
        self,
        store: Any,
        *,
        state_repository: GraphStateRepository | None = None,
        resolver: CrossSourceGraphResolver | None = None,
    ) -> None:
        self.store = store
        self.state_repository = state_repository or GraphStateRepository(store)
        self.resolver = resolver or CrossSourceGraphResolver(store)

    def finalize_source_graph(self, source_id: str) -> None:
        self.store.init()
        created_at = datetime.now(timezone.utc).isoformat()

        def write(conn: sqlite3.Connection) -> None:
            self.state_repository.set_status_conn(conn, source_id, GRAPH_STATE_FINALIZING, created_at)
            self.store._finalize_graph_replacement(conn, source_id, created_at)

        try:
            self.store._write_with_busy_retry(write)
        except Exception as exc:
            self.store.mark_source_graph_failed(source_id, exc)
            raise
