from __future__ import annotations

from collections import defaultdict
from dataclasses import replace
from typing import Any, Dict, Iterable, List, Sequence

from knowledge_service.entrypoint_flow_engine import EntrypointFlow
from knowledge_service.entrypoint_kinds import EntrypointExecutionKind
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowNodeKey, dedupe_evidence
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause


_SQLITE_BIND_CHUNK_SIZE = 800


def _chunks(values: Sequence[str], size: int = _SQLITE_BIND_CHUNK_SIZE) -> Iterable[Sequence[str]]:
    for offset in range(0, len(values), size):
        yield values[offset: offset + size]


class EntrypointFlowGraphRepository:
    def __init__(self, graph_store: Any) -> None:
        self.graph_store = graph_store
        self._metrics: Dict[str, int] = defaultdict(int)

    def load_nodes(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, FlowGraphNode]:
        self.graph_store.init()
        grouped = self._group_node_ids_by_source(node_keys)
        if not grouped:
            return {}
        result: dict[FlowNodeKey, FlowGraphNode] = {}
        with self.graph_store._connect() as conn:
            identity_by_source = self.graph_store._graph_identity_by_source(conn, sorted(grouped))
            for source_id, ids in sorted(grouped.items()):
                identity = identity_by_source.get(source_id) or {}
                for chunk in _chunks(sorted(ids)):
                    rows = self._query_nodes(conn, source_id, list(chunk), include_tests)
                    self._metrics["nodeRowsLoaded"] += len(rows)
                    self.graph_store._attach_current_graph_identity(conn, rows)
                    for row in rows:
                        node = self.graph_store._flow_graph_node_from_public_graph(row)
                        if node is None or not self._matches_current_identity(node, identity):
                            continue
                        result[(node.source_id, node.graph_revision or node.graph_id, node.node_id)] = node
        return result

    def load_incoming_calls(
        self,
        target_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        return self._load_call_edges(target_keys, include_tests=include_tests, direction="incoming")

    def load_outgoing_calls(
        self,
        source_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        return self._load_call_edges(source_keys, include_tests=include_tests, direction="outgoing")

    def hydrate_evidence(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]:
        self.graph_store.init()
        if not flows:
            return ()
        edge_ids_by_source: dict[str, set[str]] = defaultdict(set)
        node_ids_by_source: dict[str, set[str]] = defaultdict(set)
        for flow in flows:
            for edge in (*flow.transitions, *flow.boundary_transitions):
                edge_ids_by_source[edge.source_id].add(edge.edge_id)
            for node in flow.nodes:
                node_ids_by_source[node.source_id].add(node.node_id)

        edge_evidence_by_source: dict[str, list[FlowGraphEvidence]] = defaultdict(list)
        node_evidence_by_source: dict[str, list[FlowGraphEvidence]] = defaultdict(list)
        with self.graph_store._connect() as conn:
            for source_id, edge_ids in sorted(edge_ids_by_source.items()):
                rows = self._query_edge_evidence(conn, source_id, sorted(edge_ids))
                self._metrics["evidenceRowsLoaded"] += len(rows)
                self.graph_store._attach_current_graph_identity(conn, rows)
                evidence = [item for item in (self.graph_store._flow_graph_evidence_from_public_graph(row) for row in rows) if item is not None]
                edge_evidence_by_source[source_id].extend(evidence)
            for source_id, node_ids in sorted(node_ids_by_source.items()):
                rows = self._query_node_evidence(conn, source_id, sorted(node_ids))
                self._metrics["evidenceRowsLoaded"] += len(rows)
                self.graph_store._attach_current_graph_identity(conn, rows)
                node_evidence_by_source[source_id].extend(
                    item for item in (self.graph_store._flow_graph_evidence_from_public_graph(row) for row in rows) if item is not None
                )

        hydrated: list[EntrypointFlow] = []
        for flow in flows:
            flow_edge_ids = {edge.edge_id for edge in (*flow.transitions, *flow.boundary_transitions)}
            flow_node_keys = {(node.source_id, node.node_id) for node in flow.nodes}
            edge_evidence = [item for values in edge_evidence_by_source.values() for item in values if item.edge_id in flow_edge_ids]
            node_evidence = [
                item
                for values in node_evidence_by_source.values()
                for item in values
                if item.node_id is not None and (item.source_id, item.node_id) in flow_node_keys
            ]
            edge_ids_by_edge: dict[str, list[str]] = defaultdict(list)
            for item in edge_evidence:
                if item.edge_id:
                    edge_ids_by_edge[item.edge_id].append(item.evidence_id)
            evidence = dedupe_evidence([
                *edge_evidence,
                *node_evidence,
            ])
            hydrated.append(replace(
                flow,
                transitions=tuple(self._edge_with_evidence(edge, edge_ids_by_edge) for edge in flow.transitions),
                boundary_transitions=tuple(self._edge_with_evidence(edge, edge_ids_by_edge) for edge in flow.boundary_transitions),
                evidence=evidence,
            ))
        return tuple(hydrated)

    def metrics(self) -> dict[str, int]:
        return dict(self._metrics)

    def _load_call_edges(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
        direction: str,
    ) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        self.graph_store.init()
        grouped = self._group_node_ids_by_source(node_keys)
        if not grouped:
            return {}
        result: dict[FlowNodeKey, list[FlowGraphEdge]] = defaultdict(list)
        with self.graph_store._connect() as conn:
            target_identity_by_source = self.graph_store._graph_identity_by_source(conn, sorted(grouped))
            for source_id, ids in sorted(grouped.items()):
                target_identity = target_identity_by_source.get(source_id) or {}
                for chunk in _chunks(sorted(ids)):
                    rows = self._query_call_edges(conn, source_id, list(chunk), include_tests, direction)
                    self._metrics["edgeRowsLoaded"] += len(rows)
                    self.graph_store._attach_current_graph_identity(conn, rows)
                    edge_identity_by_source = self.graph_store._graph_identity_by_source(
                        conn,
                        sorted({str(row.get("sourceId") or "") for row in rows if row.get("sourceId")}),
                    )
                    for row in rows:
                        edge = self.graph_store._flow_graph_edge_from_public_graph(row, {})
                        if edge is None:
                            continue
                        edge_identity = edge_identity_by_source.get(edge.source_id) or {}
                        if not self._matches_current_identity(edge, edge_identity):
                            continue
                        if direction == "incoming" and not self._matches_target_identity(edge, target_identity):
                            continue
                        key = self._to_key(edge) if direction == "incoming" else self._from_key(edge)
                        if key is not None:
                            result[key].append(edge)
        return {key: tuple(sorted(edges, key=self._edge_sort_key)) for key, edges in result.items()}

    def _query_nodes(self, conn: Any, source_id: str, ids: list[str], include_tests: bool) -> List[Dict[str, Any]]:
        if not ids:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        responsibility_status_sql, responsibility_status_params = sql_in_clause(contract.statuses_for_responsibility_summary())
        rows = conn.execute(
            f"""
            WITH claim AS (
                SELECT source_id, node_id, group_concat(summary, ' ') AS summary
                FROM (
                    SELECT source_id, node_id, summary
                    FROM analysis_graph_claims
                    WHERE source_id = ?
                      AND node_id IN ({placeholders})
                      AND claim_kind = ?
                      AND status IN ({responsibility_status_sql})
                      AND rejection_reason IS NULL
                    ORDER BY source_id, node_id, status, confidence DESC, id
                )
                GROUP BY source_id, node_id
            )
            SELECT n.*,
                   {self.graph_store._inventory_flow_domain_sql("n")} AS effective_flow_domain,
                   COALESCE(af.relative_path, n.relative_path) AS relative_path,
                   0 AS graph_degree,
                   CASE WHEN entry.id IS NULL THEN 0 ELSE 1 END AS entrypoint,
                   entry.entrypoint_kind AS entrypoint_kind,
                   entry.entrypoint_http_method AS entrypoint_http_method,
                   entry.entrypoint_route AS entrypoint_route,
                   entry.entrypoint_topic AS entrypoint_topic,
                   entry.entrypoint_schedule AS entrypoint_schedule,
                   entry.entrypoint_interface_method AS entrypoint_interface_method,
                   claim.summary AS summary
            FROM analysis_graph_nodes n
            LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
            LEFT JOIN analysis_graph_claims entry
              ON entry.source_id = n.source_id
             AND entry.node_id = n.id
             AND entry.claim_kind = ?
             AND entry.status IN ({current_status_sql})
             AND COALESCE(entry.entrypoint_execution_kind, ?) = ?
            LEFT JOIN claim
              ON claim.source_id = n.source_id
             AND claim.node_id = n.id
            WHERE n.source_id = ?
              AND n.id IN ({placeholders})
              AND n.status IN ({current_status_sql})
              AND {self.graph_store._inventory_membership_graph_node_clause("n")}
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            ORDER BY n.id
            """,
            [
                source_id,
                *ids,
                contract.responsibility_claim_kind,
                *responsibility_status_params,
                contract.entrypoint_claim_kind,
                *current_status_params,
                EntrypointExecutionKind.EXECUTABLE.value,
                EntrypointExecutionKind.EXECUTABLE.value,
                source_id,
                *ids,
                *current_status_params,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._graph_node_projection(self.graph_store._row_dict(row)) for row in rows]

    def _query_call_edges(
        self,
        conn: Any,
        source_id: str,
        ids: list[str],
        include_tests: bool,
        direction: str,
    ) -> List[Dict[str, Any]]:
        if not ids:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        if direction == "incoming":
            return self._query_incoming_call_edges(conn, source_id, ids, include_tests, current_status_sql, current_status_params)
        frontier_column = "e.from_node_id"
        params: list[Any] = [
            source_id,
            contract.calls_edge_type,
            *current_status_params,
            *ids,
            include_tests,
        ]
        rows = conn.execute(
            f"""
            SELECT e.*,
                   {self.graph_store._inventory_flow_domain_sql("e")} AS effective_flow_domain,
                   fn.display_name AS from_display_name,
                   fn.qualified_name AS from_qualified_name,
                   fn.name AS from_name,
                   tn.display_name AS to_display_name,
                   tn.qualified_name AS to_qualified_name,
                   tn.name AS to_name,
                   tn.source_id AS to_source_id,
                   target_state.graph_id AS to_graph_id,
                   target_state.content_identity AS to_graph_revision
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes fn
              ON fn.source_id = e.source_id
             AND fn.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes tn
              ON tn.id = e.to_node_id
            LEFT JOIN analysis_graph_state target_state
              ON target_state.source_id = tn.source_id
             AND target_state.status = 'READY'
            WHERE e.source_id = ?
              AND e.edge_type = ?
              AND e.status IN ({current_status_sql})
              AND {self.graph_store._inventory_membership_graph_edge_clause("e")}
              AND {frontier_column} IN ({placeholders})
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("e")}, '') != 'TEST')
            ORDER BY {frontier_column}, e.relative_path, e.id
            """,
            params,
        ).fetchall()
        projected: List[Dict[str, Any]] = []
        for row in rows:
            item = self.graph_store._graph_edge_projection(self.graph_store._row_dict(row))
            item["sourceId"] = row["source_id"]
            item["flowDomain"] = item.get("flowDomain") or row["effective_flow_domain"]
            projected.append(item)
        return projected

    def _query_incoming_call_edges(
        self,
        conn: Any,
        target_source_id: str,
        ids: list[str],
        include_tests: bool,
        current_status_sql: str,
        current_status_params: Sequence[Any],
    ) -> List[Dict[str, Any]]:
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        rows = conn.execute(
            f"""
            SELECT e.*,
                   {self.graph_store._inventory_flow_domain_sql("e")} AS effective_flow_domain,
                   fn.display_name AS from_display_name,
                   fn.qualified_name AS from_qualified_name,
                   fn.name AS from_name,
                   tn.display_name AS to_display_name,
                   tn.qualified_name AS to_qualified_name,
                   tn.name AS to_name,
                   tn.source_id AS to_source_id,
                   target_state.graph_id AS to_graph_id,
                   target_state.content_identity AS to_graph_revision
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes tn
              ON tn.source_id = ?
             AND tn.id = e.to_node_id
             AND tn.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("tn")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("tn")}, '') != 'TEST')
            JOIN analysis_graph_state target_state
              ON target_state.source_id = tn.source_id
             AND target_state.status = 'READY'
            JOIN analysis_graph_nodes fn
              ON fn.source_id = e.source_id
             AND fn.id = e.from_node_id
             AND fn.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("fn")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("fn")}, '') != 'TEST')
            JOIN analysis_graph_state caller_state
              ON caller_state.source_id = e.source_id
             AND caller_state.status = 'READY'
            WHERE e.edge_type = ?
              AND e.status IN ({current_status_sql})
              AND {self.graph_store._inventory_membership_graph_edge_clause("e")}
              AND e.to_node_id IN ({placeholders})
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("e")}, '') != 'TEST')
            ORDER BY e.to_node_id, e.relative_path, e.id
            """,
            [
                target_source_id,
                *current_status_params,
                include_tests,
                *current_status_params,
                include_tests,
                contract.calls_edge_type,
                *current_status_params,
                *ids,
                include_tests,
            ],
        ).fetchall()
        projected: List[Dict[str, Any]] = []
        for row in rows:
            item = self.graph_store._graph_edge_projection(self.graph_store._row_dict(row))
            item["sourceId"] = row["source_id"]
            item["flowDomain"] = item.get("flowDomain") or row["effective_flow_domain"]
            projected.append(item)
        return projected

    def _query_edge_evidence(self, conn: Any, source_id: str, edge_ids: Sequence[str]) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        if not edge_ids:
            return result
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        for chunk in _chunks(list(edge_ids)):
            self._metrics["sqlStatements"] += 1
            placeholders = ",".join("?" for _ in chunk)
            rows = conn.execute(
                f"""
                SELECT ev.id,
                       ev.source_id,
                       ev.analysis_file_id,
                       COALESCE(af.relative_path, ev.relative_path) AS relative_path,
                       ev.line_start,
                       ev.line_end,
                       ev.excerpt,
                       ev.evidence_kind,
                       ev.excerpt_hash,
                       ev.fact_origin,
                       ev.flow_domain,
                       edge.id AS edge_id,
                       NULL AS node_id
                FROM analysis_graph_edges edge
                JOIN analysis_graph_edge_evidence link ON link.edge_id = edge.id
                JOIN analysis_graph_evidence ev
                  ON ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE edge.source_id = ?
                  AND edge.edge_type = ?
                  AND edge.status IN ({current_status_sql})
                  AND {self.graph_store._inventory_membership_graph_edge_clause("edge")}
                  AND edge.id IN ({placeholders})
                ORDER BY edge.id, relative_path, ev.line_start, ev.line_end, ev.id
                """,
                [source_id, contract.calls_edge_type, *current_status_params, *chunk],
            ).fetchall()
            result.extend(self.graph_store._linked_evidence_projection(rows))
        return result

    def _query_node_evidence(
        self,
        conn: Any,
        source_id: str,
        node_ids: Sequence[str],
    ) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        if not node_ids:
            return result
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        for chunk in _chunks(list(node_ids)):
            self._metrics["sqlStatements"] += 1
            placeholders = ",".join("?" for _ in chunk)
            rows = conn.execute(
                f"""
                SELECT ev.id,
                       ev.source_id,
                       ev.analysis_file_id,
                       COALESCE(af.relative_path, ev.relative_path) AS relative_path,
                       ev.line_start,
                       ev.line_end,
                       ev.excerpt,
                       ev.evidence_kind,
                       ev.excerpt_hash,
                       ev.fact_origin,
                       ev.flow_domain,
                       NULL AS edge_id,
                       claim.node_id AS node_id
                FROM analysis_graph_claims claim
                JOIN analysis_graph_claim_evidence link ON link.claim_id = claim.id
                JOIN analysis_graph_evidence ev
                  ON ev.id = link.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE claim.source_id = ?
                  AND claim.status IN ({current_status_sql})
                  AND claim.node_id IN ({placeholders})
                  AND claim.rejection_reason IS NULL
                ORDER BY claim.node_id, relative_path, ev.line_start, ev.line_end, ev.id
                """,
                [source_id, *current_status_params, *chunk],
            ).fetchall()
            result.extend(self.graph_store._linked_evidence_projection(rows))
        return result

    def _group_node_ids_by_source(self, node_keys: set[FlowNodeKey]) -> dict[str, set[str]]:
        grouped: dict[str, set[str]] = defaultdict(set)
        for source_id, _revision, node_id in node_keys:
            if source_id and node_id:
                grouped[source_id].add(node_id)
        return grouped

    def _edge_with_evidence(self, edge: FlowGraphEdge, evidence_ids_by_edge: dict[str, list[str]]) -> FlowGraphEdge:
        return replace(edge, evidence_ids=tuple(dict.fromkeys(evidence_ids_by_edge.get(edge.edge_id, []))))

    def _matches_current_identity(self, item: FlowGraphNode | FlowGraphEdge, identity: dict[str, str | None]) -> bool:
        graph_id = str(identity.get("graphId") or "")
        revision = str(identity.get("graphRevision") or graph_id)
        item_revision = item.graph_revision or item.graph_id
        return bool(item_revision) and item_revision in {graph_id, revision}

    def _matches_target_identity(self, edge: FlowGraphEdge, identity: dict[str, str | None]) -> bool:
        graph_id = str(identity.get("graphId") or "")
        revision = str(identity.get("graphRevision") or graph_id)
        item_revision = edge.to_graph_revision or edge.to_graph_id
        return bool(item_revision) and item_revision in {graph_id, revision}

    def _from_key(self, edge: FlowGraphEdge) -> FlowNodeKey:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.from_node_id)

    def _to_key(self, edge: FlowGraphEdge) -> FlowNodeKey | None:
        if not edge.to_node_id:
            return None
        return (
            edge.to_source_id or edge.source_id,
            edge.to_graph_revision or edge.to_graph_id or edge.graph_revision or edge.graph_id,
            edge.to_node_id,
        )

    def _edge_sort_key(self, edge: FlowGraphEdge) -> tuple[str, str, str, str, str]:
        return (
            edge.source_id,
            edge.graph_revision or edge.graph_id,
            edge.from_node_id,
            edge.to_node_id or "",
            edge.edge_id,
        )
