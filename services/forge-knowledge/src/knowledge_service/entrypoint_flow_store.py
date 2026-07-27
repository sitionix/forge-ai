from __future__ import annotations

import json
from collections import defaultdict
from dataclasses import replace
from typing import Any, Dict, Iterable, List, Sequence

from knowledge_service.entrypoint_flow_engine import EntrypointFlow, LocalBoundaryDescriptor, LocalBoundaryFact
from knowledge_service.entrypoint_kinds import EntrypointExecutionKind
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowNodeKey, dedupe_evidence
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause
from knowledge_service.graph_relation_semantics import EXECUTION_CONTINUATION, SUPPORTING_RELATION, graph_relation_semantics
from knowledge_service.operation_facts import (
    AvailableOperationFact,
    OperationFactEligibility,
    OperationFactEvidence,
    clean_identity,
    edge_backed_http_direction,
    merge_semantic_operation_facts,
    normalize_http_method,
    normalize_route,
    normalize_transport_kind,
    split_operation_interface_identity,
)

_SQLITE_BIND_CHUNK_SIZE = 800


def _chunks(values: Sequence[str], size: int = _SQLITE_BIND_CHUNK_SIZE) -> Iterable[Sequence[str]]:
    for offset in range(0, len(values), size):
        yield values[offset: offset + size]


class EntrypointFlowGraphRepository:
    def __init__(self, graph_store: Any) -> None:
        self.graph_store = graph_store
        self._metrics: Dict[str, int] = defaultdict(int)
        self._boundary_fact_source_cache: dict[str, bool] = {}

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

    def load_boundaries(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> dict[FlowNodeKey, tuple[LocalBoundaryFact, ...]]:
        self.graph_store.init()
        grouped = self._group_node_ids_by_source(node_keys)
        if not grouped:
            return {}
        result: dict[FlowNodeKey, list[LocalBoundaryFact]] = defaultdict(list)
        with self.graph_store._connect() as conn:
            if not self.graph_store._table_exists(conn, "analysis_graph_boundaries"):
                return {}
            identity_by_source = self.graph_store._graph_identity_by_source(conn, sorted(grouped))
            for source_id, ids in sorted(grouped.items()):
                identity = identity_by_source.get(source_id) or {}
                for chunk in _chunks(sorted(ids)):
                    rows = self._query_boundary_rows(conn, source_id, list(chunk), include_tests)
                    self._metrics["boundaryFactRowsLoaded"] += len(rows)
                    facts = self._boundary_facts_from_rows(rows, identity)
                    for fact in facts:
                        result[fact.owner_key].append(fact)
        return {key: tuple(sorted(values, key=self._boundary_sort_key)) for key, values in result.items()}

    def hydrate_evidence(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]:
        self.graph_store.init()
        if not flows:
            return ()
        edge_ids_by_source: dict[str, set[str]] = defaultdict(set)
        node_ids_by_source: dict[str, set[str]] = defaultdict(set)
        for flow in flows:
            for edge in (*flow.transitions, *flow.boundary_transitions, *tuple(getattr(flow, "supporting_transitions", ()) or ())):
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
            flow_edge_ids = {
                edge.edge_id
                for edge in (*flow.transitions, *flow.boundary_transitions, *tuple(getattr(flow, "supporting_transitions", ()) or ()))
            }
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
            evidence = dedupe_evidence(
                [
                    *flow.evidence,
                    *edge_evidence,
                    *node_evidence,
                ]
            )
            replacements: Dict[str, Any] = {
                "transitions": tuple(self._edge_with_evidence(edge, edge_ids_by_edge) for edge in flow.transitions),
                "boundary_transitions": tuple(self._edge_with_evidence(edge, edge_ids_by_edge) for edge in flow.boundary_transitions),
                "evidence": evidence,
            }
            if hasattr(flow, "supporting_transitions"):
                replacements["supporting_transitions"] = tuple(
                    self._edge_with_evidence(edge, edge_ids_by_edge)
                    for edge in tuple(getattr(flow, "supporting_transitions", ()) or ())
                )
            hydrated.append(replace(flow, **replacements))
        return tuple(hydrated)

    def load_supporting_relations(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> tuple[dict[FlowNodeKey, FlowGraphNode], tuple[FlowGraphEdge, ...]]:
        self.graph_store.init()
        node_ids = sorted({node_id for _source_id, _revision, node_id in node_keys if node_id})
        if not node_ids:
            return {}, ()
        supporting_edge_types = graph_relation_semantics().edge_types_with(SUPPORTING_RELATION)
        if not supporting_edge_types:
            return {}, ()
        edges: dict[tuple[str, str], FlowGraphEdge] = {}
        endpoint_keys: set[FlowNodeKey] = set(node_keys)
        source_ids = sorted({source_id for source_id, _revision, _node_id in node_keys if source_id})
        with self.graph_store._connect() as conn:
            source_identity = self.graph_store._graph_identity_by_source(conn, source_ids)
            for chunk in _chunks(node_ids):
                rows = self._query_supporting_edges(conn, chunk, supporting_edge_types, include_tests)
                self._metrics["supportingEdgeRowsLoaded"] += len(rows)
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
                    if edge.source_id in source_identity and not self._matches_current_identity(edge, source_identity.get(edge.source_id) or {}):
                        continue
                    edges[(edge.source_id, edge.edge_id)] = edge
                    endpoint_keys.add(self._from_key(edge))
                    to_key = self._to_key(edge)
                    if to_key is not None:
                        endpoint_keys.add(to_key)
        nodes = self.load_nodes(endpoint_keys, include_tests=include_tests)
        return nodes, tuple(sorted(edges.values(), key=self._edge_sort_key))

    def load_available_operation_facts(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> tuple[AvailableOperationFact, ...]:
        self.graph_store.init()
        grouped = self._group_node_ids_by_source(node_keys)
        if not grouped:
            return ()
        facts: list[AvailableOperationFact] = []
        with self.graph_store._connect() as conn:
            for source_id, ids in sorted(grouped.items()):
                has_boundary_facts = self._source_has_boundary_facts(conn, source_id)
                for chunk in _chunks(sorted(ids)):
                    rows = self._query_available_operation_fact_rows(conn, source_id, list(chunk), include_tests)
                    self._metrics["operationFactRowsLoaded"] += len(rows)
                    if has_boundary_facts:
                        boundary_rows = self._query_boundary_operation_fact_rows(conn, source_id, list(chunk), include_tests)
                        self._metrics["operationFactRowsLoaded"] += len(boundary_rows)
                        facts.extend(self._operation_facts_from_boundary_rows(boundary_rows))
                    facts.extend(self._operation_facts_from_rows(rows))
                    edge_rows = self._query_edge_operation_fact_rows(conn, source_id, list(chunk), include_tests)
                    self._metrics["operationFactRowsLoaded"] += len(edge_rows)
                    facts.extend(self._operation_facts_from_edge_rows(edge_rows))
        return tuple(sorted(self._dedupe_operation_facts(facts), key=self._operation_fact_sort_key))

    def load_matching_inbound_operation_facts(
        self,
        outbound_facts: Sequence[AvailableOperationFact],
        *,
        eligible_source_ids: Sequence[str],
        include_tests: bool,
    ) -> tuple[AvailableOperationFact, ...]:
        requested = tuple(
            fact
            for fact in outbound_facts
            if normalize_transport_kind(fact.transport_kind) == "HTTP"
            and str(fact.direction_role or "").strip().upper() == "OUTBOUND"
            and normalize_http_method(fact.method)
            and normalize_route(fact.normalized_route)
        )
        source_ids = tuple(dict.fromkeys(str(source_id or "").strip() for source_id in eligible_source_ids if str(source_id or "").strip()))
        if not requested or not source_ids:
            return ()
        methods = tuple(sorted({normalize_http_method(fact.method) or "" for fact in requested if normalize_http_method(fact.method)}))
        self.graph_store.init()
        with self.graph_store._connect() as conn:
            boundary_rows = (
                self._query_matching_inbound_boundary_operation_fact_rows(conn, source_ids, methods, include_tests)
                if any(self._source_has_boundary_facts(conn, source_id) for source_id in source_ids)
                else []
            )
            self._metrics["operationFactRowsLoaded"] += len(boundary_rows)
            rows = self._query_matching_inbound_operation_fact_rows(conn, source_ids, methods, include_tests)
            self._metrics["operationFactRowsLoaded"] += len(rows)
        facts = [
            fact
            for fact in (*self._operation_facts_from_boundary_rows(boundary_rows), *self._operation_facts_from_rows(rows))
            if self._inbound_fact_matches_any_outbound(fact, requested)
        ]
        return tuple(sorted(self._dedupe_operation_facts(facts), key=self._operation_fact_sort_key))

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
        execution_edge_types = graph_relation_semantics().edge_types_with(EXECUTION_CONTINUATION)
        if not execution_edge_types:
            return {}
        result: dict[FlowNodeKey, list[FlowGraphEdge]] = defaultdict(list)
        with self.graph_store._connect() as conn:
            target_identity_by_source = self.graph_store._graph_identity_by_source(conn, sorted(grouped))
            for source_id, ids in sorted(grouped.items()):
                target_identity = target_identity_by_source.get(source_id) or {}
                for chunk in _chunks(sorted(ids)):
                    rows = self._query_call_edges(conn, source_id, list(chunk), include_tests, direction, execution_edge_types)
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
                   entry.entrypoint_execution_kind AS entrypoint_execution_kind,
                   COALESCE(role_entry.entrypoint_execution_kind, contract_entry.entrypoint_execution_kind) AS contract_entrypoint_execution_kind,
                   claim.summary AS summary
            FROM analysis_graph_nodes n
            LEFT JOIN analysis_files af ON af.file_id = n.analysis_file_id
            LEFT JOIN analysis_graph_claims entry
              ON entry.source_id = n.source_id
             AND entry.node_id = n.id
             AND entry.claim_kind = ?
             AND entry.status IN ({current_status_sql})
             AND COALESCE(entry.entrypoint_execution_kind, ?) = ?
            LEFT JOIN analysis_graph_claims contract_entry
              ON contract_entry.source_id = n.source_id
             AND contract_entry.node_id = n.id
             AND contract_entry.claim_kind = ?
             AND contract_entry.status IN ({current_status_sql})
             AND COALESCE(contract_entry.entrypoint_execution_kind, '') = 'CONTRACT_DECLARATION'
            LEFT JOIN analysis_graph_claims role_entry
              ON role_entry.source_id = n.source_id
             AND role_entry.node_id = n.id
             AND role_entry.claim_kind = ?
             AND role_entry.status IN ({current_status_sql})
             AND COALESCE(role_entry.entrypoint_execution_kind, '') NOT IN (?, '')
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
                contract.entrypoint_claim_kind,
                *current_status_params,
                contract.entrypoint_claim_kind,
                *current_status_params,
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
        edge_types: Sequence[str],
    ) -> List[Dict[str, Any]]:
        if not ids or not edge_types:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        edge_type_sql, edge_type_params = sql_in_clause(edge_types)
        if direction == "incoming":
            return self._query_incoming_call_edges(
                conn,
                source_id,
                ids,
                include_tests,
                current_status_sql,
                current_status_params,
                edge_type_sql,
                edge_type_params,
            )
        frontier_column = "e.from_node_id"
        params: list[Any] = [
            source_id,
            *edge_type_params,
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
                   COALESCE(NULLIF(target_state.graph_id, ''), tn.source_id || ':query-current-facts') AS to_graph_id,
                   COALESCE(NULLIF(target_state.content_identity, ''), NULLIF(target_state.graph_id, ''), tn.source_id || ':query-current-facts') AS to_graph_revision
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes fn
              ON fn.source_id = e.source_id
             AND fn.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes tn
              ON tn.source_id = COALESCE(e.to_source_id, e.source_id)
             AND tn.id = e.to_node_id
            LEFT JOIN analysis_graph_state target_state
              ON target_state.source_id = tn.source_id
            WHERE e.source_id = ?
              AND e.edge_type IN ({edge_type_sql})
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
        edge_type_sql: str,
        edge_type_params: Sequence[Any],
    ) -> List[Dict[str, Any]]:
        placeholders = ",".join("?" for _ in ids)
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
                   COALESCE(NULLIF(target_state.graph_id, ''), tn.source_id || ':query-current-facts') AS to_graph_id,
                   COALESCE(NULLIF(target_state.content_identity, ''), NULLIF(target_state.graph_id, ''), tn.source_id || ':query-current-facts') AS to_graph_revision
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes tn
              ON tn.source_id = ?
             AND COALESCE(e.to_source_id, e.source_id) = tn.source_id
             AND tn.id = e.to_node_id
             AND tn.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("tn")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("tn")}, '') != 'TEST')
            LEFT JOIN analysis_graph_state target_state
              ON target_state.source_id = tn.source_id
            JOIN analysis_graph_nodes fn
              ON fn.source_id = e.source_id
             AND fn.id = e.from_node_id
             AND fn.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("fn")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("fn")}, '') != 'TEST')
            WHERE e.edge_type IN ({edge_type_sql})
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
                *edge_type_params,
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

    def _query_supporting_edges(
        self,
        conn: Any,
        ids: Sequence[str],
        edge_types: Sequence[str],
        include_tests: bool,
    ) -> List[Dict[str, Any]]:
        if not ids or not edge_types:
            return []
        self._metrics["sqlStatements"] += 1
        id_sql, id_params = sql_in_clause([str(item) for item in ids])
        edge_type_sql, edge_type_params = sql_in_clause([str(item) for item in edge_types])
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
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
                   COALESCE(NULLIF(target_state.graph_id, ''), tn.source_id || ':query-current-facts') AS to_graph_id,
                   COALESCE(NULLIF(target_state.content_identity, ''), NULLIF(target_state.graph_id, ''), tn.source_id || ':query-current-facts') AS to_graph_revision
            FROM analysis_graph_edges e
            LEFT JOIN analysis_graph_nodes fn
              ON fn.source_id = e.source_id
             AND fn.id = e.from_node_id
            LEFT JOIN analysis_graph_nodes tn
              ON tn.source_id = COALESCE(e.to_source_id, e.source_id)
             AND tn.id = e.to_node_id
            LEFT JOIN analysis_graph_state target_state
              ON target_state.source_id = tn.source_id
            WHERE e.edge_type IN ({edge_type_sql})
              AND e.status IN ({current_status_sql})
              AND {self.graph_store._inventory_membership_graph_edge_clause("e")}
              AND (e.from_node_id IN ({id_sql}) OR e.to_node_id IN ({id_sql}))
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("e")}, '') != 'TEST')
            ORDER BY e.source_id, e.edge_type, e.from_node_id, e.to_node_id, e.id
            """,
            [
                *edge_type_params,
                *current_status_params,
                *id_params,
                *id_params,
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

    def _query_boundary_rows(
        self,
        conn: Any,
        source_id: str,
        ids: list[str],
        include_tests: bool,
    ) -> list[dict[str, Any]]:
        if not ids:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT b.id AS boundary_id,
                   b.source_id AS source_id,
                   b.stable_key AS boundary_stable_key,
                   b.node_id AS node_id,
                   b.role AS boundary_role,
                   b.status AS boundary_status,
                   b.fact_origin AS boundary_fact_origin,
                   b.confidence AS boundary_confidence,
                   COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, b.flow_domain) AS effective_flow_domain,
                   COALESCE(NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_id,
                   COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_revision,
                   d.id AS descriptor_id,
                   d.descriptor_path AS descriptor_path,
                   d.value_type AS descriptor_value_type,
                   d.value_json AS descriptor_value_json,
                   d.origin AS descriptor_origin,
                   d.confidence AS descriptor_confidence,
                   boundary_ev.id AS boundary_evidence_id,
                   boundary_ev.source_id AS boundary_evidence_source_id,
                   COALESCE(boundary_af.relative_path, boundary_ev.relative_path) AS boundary_evidence_relative_path,
                   boundary_ev.line_start AS boundary_evidence_line_start,
                   boundary_ev.line_end AS boundary_evidence_line_end,
                   boundary_ev.excerpt AS boundary_evidence_excerpt,
                   descriptor_ev.id AS descriptor_evidence_id,
                   descriptor_ev.source_id AS descriptor_evidence_source_id,
                   COALESCE(descriptor_af.relative_path, descriptor_ev.relative_path) AS descriptor_evidence_relative_path,
                   descriptor_ev.line_start AS descriptor_evidence_line_start,
                   descriptor_ev.line_end AS descriptor_evidence_line_end,
                   descriptor_ev.excerpt AS descriptor_evidence_excerpt
            FROM analysis_graph_boundaries b
            JOIN analysis_graph_nodes n
              ON n.source_id = b.source_id
             AND n.id = b.node_id
             AND n.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("n")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            LEFT JOIN analysis_graph_state state
              ON state.source_id = b.source_id
            LEFT JOIN analysis_graph_boundary_descriptors d
              ON d.boundary_id = b.id
             AND d.status IN ({current_status_sql})
            LEFT JOIN analysis_graph_boundary_evidence be
              ON be.boundary_id = b.id
            LEFT JOIN analysis_graph_evidence boundary_ev
              ON boundary_ev.id = be.evidence_id
             AND boundary_ev.source_id = b.source_id
            LEFT JOIN analysis_files boundary_af
              ON boundary_af.file_id = boundary_ev.analysis_file_id
            LEFT JOIN analysis_graph_boundary_descriptor_evidence de
              ON de.descriptor_id = d.id
            LEFT JOIN analysis_graph_evidence descriptor_ev
              ON descriptor_ev.id = de.evidence_id
             AND descriptor_ev.source_id = b.source_id
            LEFT JOIN analysis_files descriptor_af
              ON descriptor_af.file_id = descriptor_ev.analysis_file_id
            WHERE b.source_id = ?
              AND b.node_id IN ({placeholders})
              AND b.status IN ({current_status_sql})
              AND b.rejection_reason IS NULL
              AND {self.graph_store._inventory_membership_graph_edge_clause("b")}
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            ORDER BY b.source_id, b.node_id, b.role, b.id, d.descriptor_path, d.id,
                     boundary_ev.relative_path, boundary_ev.line_start, boundary_ev.line_end, boundary_ev.id,
                     descriptor_ev.relative_path, descriptor_ev.line_start, descriptor_ev.line_end, descriptor_ev.id
            """,
            [
                *current_status_params,
                include_tests,
                *current_status_params,
                source_id,
                *ids,
                *current_status_params,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _boundary_facts_from_rows(
        self,
        rows: Sequence[dict[str, Any]],
        identity: dict[str, str | None],
    ) -> tuple[LocalBoundaryFact, ...]:
        grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in rows:
            grouped[str(row.get("boundary_id") or "")].append(row)
        facts: list[LocalBoundaryFact] = []
        for boundary_id, boundary_rows in grouped.items():
            if not boundary_id or not boundary_rows:
                continue
            first = boundary_rows[0]
            graph_id = str(first.get("graph_id") or "")
            graph_revision = str(first.get("graph_revision")) if first.get("graph_revision") else None
            probe = FlowGraphNode(
                source_id=str(first.get("source_id") or ""),
                graph_id=graph_id,
                graph_revision=graph_revision,
                node_id=str(first.get("node_id") or ""),
                stable_key=str(first.get("node_id") or ""),
                node_kind="CALLABLE",
                label=str(first.get("node_id") or ""),
            )
            if not self._matches_current_identity(probe, identity):
                continue
            facts.append(
                LocalBoundaryFact(
                    boundary_id=boundary_id,
                    stable_key=str(first.get("boundary_stable_key") or boundary_id),
                    source_id=str(first.get("source_id") or ""),
                    graph_id=graph_id,
                    graph_revision=graph_revision,
                    owner_node_id=str(first.get("node_id") or ""),
                    role=str(first.get("boundary_role") or "").strip().upper(),
                    status=str(first.get("boundary_status") or ""),
                    provenance=clean_identity(first.get("boundary_fact_origin")),
                    confidence=float(first.get("boundary_confidence") or 0.0),
                    flow_domain=clean_identity(first.get("effective_flow_domain")),
                    descriptors=self._local_boundary_descriptors(boundary_rows, graph_id, graph_revision),
                    evidence=dedupe_evidence(self._local_boundary_evidence(boundary_rows, "boundary")),
                )
            )
        return tuple(sorted(facts, key=self._boundary_sort_key))

    def _local_boundary_descriptors(
        self,
        rows: Sequence[dict[str, Any]],
        graph_id: str,
        graph_revision: str | None,
    ) -> tuple[LocalBoundaryDescriptor, ...]:
        grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in rows:
            descriptor_id = str(row.get("descriptor_id") or "")
            if descriptor_id:
                grouped[descriptor_id].append(row)
        descriptors: list[LocalBoundaryDescriptor] = []
        for descriptor_id, descriptor_rows in grouped.items():
            first = descriptor_rows[0]
            raw_value = str(first.get("descriptor_value_json") or "")
            try:
                value: Any = json.loads(raw_value)
            except json.JSONDecodeError:
                value = raw_value
            descriptors.append(
                LocalBoundaryDescriptor(
                    descriptor_id=descriptor_id,
                    path=str(first.get("descriptor_path") or ""),
                    value_type=str(first.get("descriptor_value_type") or ""),
                    value=value,
                    origin=str(first.get("descriptor_origin") or ""),
                    confidence=float(first.get("descriptor_confidence")) if first.get("descriptor_confidence") is not None else None,
                    evidence=dedupe_evidence(self._local_boundary_evidence(descriptor_rows, "descriptor", graph_id=graph_id, graph_revision=graph_revision)),
                )
            )
        return tuple(sorted(descriptors, key=self._local_boundary_descriptor_sort_key))

    def _local_boundary_evidence(
        self,
        rows: Sequence[dict[str, Any]],
        prefix: str,
        *,
        graph_id: str | None = None,
        graph_revision: str | None = None,
    ) -> tuple[FlowGraphEvidence, ...]:
        result: list[FlowGraphEvidence] = []
        seen: set[tuple[str, str]] = set()
        for row in rows:
            evidence_id = str(row.get(f"{prefix}_evidence_id") or "")
            evidence_source_id = str(row.get(f"{prefix}_evidence_source_id") or "")
            if not evidence_id or not evidence_source_id or (evidence_source_id, evidence_id) in seen:
                continue
            seen.add((evidence_source_id, evidence_id))
            result.append(
                FlowGraphEvidence(
                    source_id=evidence_source_id,
                    graph_id=graph_id or str(row.get("graph_id") or ""),
                    graph_revision=graph_revision or (str(row.get("graph_revision")) if row.get("graph_revision") else None),
                    evidence_id=evidence_id,
                    node_id=str(row.get("node_id") or "") or None,
                    edge_id=None,
                    relative_path=str(row.get(f"{prefix}_evidence_relative_path")) if row.get(f"{prefix}_evidence_relative_path") else None,
                    line_start=int(row.get(f"{prefix}_evidence_line_start")) if row.get(f"{prefix}_evidence_line_start") is not None else None,
                    line_end=int(row.get(f"{prefix}_evidence_line_end")) if row.get(f"{prefix}_evidence_line_end") is not None else None,
                    text=str(row.get(f"{prefix}_evidence_excerpt")) if row.get(f"{prefix}_evidence_excerpt") else None,
                    owner_kind="BOUNDARY_DESCRIPTOR" if prefix == "descriptor" else "BOUNDARY",
                    owner_source_id=str(row.get("source_id") or ""),
                    owner_node_id=str(row.get("node_id") or "") or None,
                    owner_edge_id=None,
                )
            )
        return tuple(result)

    def _query_available_operation_fact_rows(
        self,
        conn: Any,
        source_id: str,
        ids: list[str],
        include_tests: bool,
    ) -> List[Dict[str, Any]]:
        if not ids:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT c.id AS claim_id,
                   c.source_id AS source_id,
                   c.node_id AS node_id,
                   c.entrypoint_kind AS entrypoint_kind,
                   c.entrypoint_http_method AS entrypoint_http_method,
                   c.entrypoint_route AS entrypoint_route,
                   c.entrypoint_topic AS entrypoint_topic,
                   c.entrypoint_schedule AS entrypoint_schedule,
                   c.entrypoint_interface_method AS entrypoint_interface_method,
                   c.entrypoint_execution_kind AS entrypoint_execution_kind,
                   c.status AS claim_status,
                   c.rejection_reason AS rejection_reason,
                   COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, c.flow_domain) AS effective_flow_domain,
                   n.qualified_name AS owner_qualified_name,
                   n.relative_path AS owner_relative_path,
                   COALESCE(NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_id,
                   COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_revision,
                   EXISTS (
                       SELECT 1
                       FROM files f_current
                       WHERE f_current.source_id = n.source_id
                         AND f_current.relative_path = n.relative_path
                         AND f_current.content_hash = n.content_hash
                   ) AS inventory_current,
                   EXISTS (
                       SELECT 1
                       FROM analysis_files af_current
                       WHERE af_current.source_id = n.source_id
                         AND af_current.relative_path = n.relative_path
                         AND af_current.content_hash = n.content_hash
                         AND af_current.status IN ('ANALYZED', 'ANALYZED_WITH_DIAGNOSTICS', 'PARTIAL')
                   ) AS analyzed_current,
                   ev.source_id AS evidence_source_id,
                   COALESCE(af.relative_path, ev.relative_path) AS evidence_relative_path,
                   ev.line_start AS evidence_line_start,
                   ev.line_end AS evidence_line_end,
                   ev.excerpt AS evidence_excerpt
            FROM analysis_graph_claims c
            JOIN analysis_graph_nodes n
              ON n.source_id = c.source_id
             AND n.id = c.node_id
             AND n.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("n")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            LEFT JOIN analysis_graph_state state
              ON state.source_id = n.source_id
            LEFT JOIN analysis_graph_claim_evidence ce
              ON ce.claim_id = c.id
            LEFT JOIN analysis_graph_evidence ev
              ON ev.id = ce.evidence_id
             AND ev.source_id = c.source_id
            LEFT JOIN analysis_files af
              ON af.file_id = ev.analysis_file_id
            WHERE c.source_id = ?
              AND c.node_id IN ({placeholders})
              AND c.claim_kind = ?
              AND c.status IN ({current_status_sql})
              AND c.rejection_reason IS NULL
              AND COALESCE(c.entrypoint_kind, '') != ''
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            ORDER BY c.source_id, c.node_id, c.entrypoint_execution_kind, c.entrypoint_kind,
                     c.entrypoint_http_method, c.entrypoint_route, c.entrypoint_interface_method,
                     c.id, ev.relative_path, ev.line_start, ev.line_end, ev.id
            """,
            [
                *current_status_params,
                include_tests,
                source_id,
                *ids,
                contract.entrypoint_claim_kind,
                *current_status_params,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _query_matching_inbound_operation_fact_rows(
        self,
        conn: Any,
        source_ids: Sequence[str],
        methods: Sequence[str],
        include_tests: bool,
    ) -> List[Dict[str, Any]]:
        if not source_ids or not methods:
            return []
        self._metrics["sqlStatements"] += 1
        source_placeholders = ",".join("?" for _ in source_ids)
        method_placeholders = ",".join("?" for _ in methods)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT c.id AS claim_id,
                   c.source_id AS source_id,
                   c.node_id AS node_id,
                   c.entrypoint_kind AS entrypoint_kind,
                   c.entrypoint_http_method AS entrypoint_http_method,
                   c.entrypoint_route AS entrypoint_route,
                   c.entrypoint_topic AS entrypoint_topic,
                   c.entrypoint_schedule AS entrypoint_schedule,
                   c.entrypoint_interface_method AS entrypoint_interface_method,
                   c.entrypoint_execution_kind AS entrypoint_execution_kind,
                   c.status AS claim_status,
                   c.rejection_reason AS rejection_reason,
                   COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, c.flow_domain) AS effective_flow_domain,
                   n.qualified_name AS owner_qualified_name,
                   n.relative_path AS owner_relative_path,
                   COALESCE(NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_id,
                   COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_revision,
                   EXISTS (
                       SELECT 1
                       FROM files f_current
                       WHERE f_current.source_id = n.source_id
                         AND f_current.relative_path = n.relative_path
                         AND f_current.content_hash = n.content_hash
                   ) AS inventory_current,
                   EXISTS (
                       SELECT 1
                       FROM analysis_files af_current
                       WHERE af_current.source_id = n.source_id
                         AND af_current.relative_path = n.relative_path
                         AND af_current.content_hash = n.content_hash
                         AND af_current.status IN ('ANALYZED', 'ANALYZED_WITH_DIAGNOSTICS', 'PARTIAL')
                   ) AS analyzed_current,
                   ev.source_id AS evidence_source_id,
                   COALESCE(af.relative_path, ev.relative_path) AS evidence_relative_path,
                   ev.line_start AS evidence_line_start,
                   ev.line_end AS evidence_line_end,
                   ev.excerpt AS evidence_excerpt
            FROM analysis_graph_claims c
            JOIN analysis_graph_nodes n
              ON n.source_id = c.source_id
             AND n.id = c.node_id
             AND n.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("n")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            LEFT JOIN analysis_graph_state state
              ON state.source_id = n.source_id
            LEFT JOIN analysis_graph_claim_evidence ce
              ON ce.claim_id = c.id
            LEFT JOIN analysis_graph_evidence ev
              ON ev.id = ce.evidence_id
             AND ev.source_id = c.source_id
            LEFT JOIN analysis_files af
              ON af.file_id = ev.analysis_file_id
            WHERE c.source_id IN ({source_placeholders})
              AND c.claim_kind = ?
              AND c.status IN ({current_status_sql})
              AND c.rejection_reason IS NULL
              AND c.entrypoint_kind = 'HTTP'
              AND c.entrypoint_execution_kind = ?
              AND c.entrypoint_http_method IN ({method_placeholders})
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            ORDER BY c.source_id, c.node_id, c.entrypoint_execution_kind, c.entrypoint_kind,
                     c.entrypoint_http_method, c.entrypoint_route, c.entrypoint_interface_method,
                     c.id, ev.relative_path, ev.line_start, ev.line_end, ev.id
            """,
            [
                *current_status_params,
                include_tests,
                *source_ids,
                contract.entrypoint_claim_kind,
                *current_status_params,
                EntrypointExecutionKind.EXECUTABLE.value,
                *methods,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _query_boundary_operation_fact_rows(
        self,
        conn: Any,
        source_id: str,
        ids: list[str],
        include_tests: bool,
    ) -> list[dict[str, Any]]:
        if not ids:
            return []
        if not self.graph_store._table_exists(conn, "analysis_graph_boundaries"):
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT b.id AS boundary_id,
                   b.source_id AS source_id,
                   b.node_id AS node_id,
                   b.role AS boundary_role,
                   b.status AS boundary_status,
                   b.rejection_reason AS rejection_reason,
                   b.fact_origin AS boundary_fact_origin,
                   COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, b.flow_domain) AS effective_flow_domain,
                   n.qualified_name AS owner_qualified_name,
                   n.relative_path AS owner_relative_path,
                   COALESCE(NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_id,
                   COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_revision,
                   EXISTS (
                       SELECT 1
                       FROM files f_current
                       WHERE f_current.source_id = n.source_id
                         AND f_current.relative_path = n.relative_path
                         AND f_current.content_hash = n.content_hash
                   ) AS inventory_current,
                   EXISTS (
                       SELECT 1
                       FROM analysis_files af_current
                       WHERE af_current.source_id = n.source_id
                         AND af_current.relative_path = n.relative_path
                         AND af_current.content_hash = n.content_hash
                         AND af_current.status IN ('ANALYZED', 'ANALYZED_WITH_DIAGNOSTICS', 'PARTIAL')
                   ) AS analyzed_current,
                   d.id AS descriptor_id,
                   d.descriptor_path AS descriptor_path,
                   d.value_type AS descriptor_value_type,
                   d.value_json AS descriptor_value_json,
                   d.origin AS descriptor_origin,
                   d.confidence AS descriptor_confidence,
                   ev.source_id AS evidence_source_id,
                   COALESCE(af.relative_path, ev.relative_path) AS evidence_relative_path,
                   ev.line_start AS evidence_line_start,
                   ev.line_end AS evidence_line_end,
                   ev.excerpt AS evidence_excerpt
            FROM analysis_graph_boundaries b
            JOIN analysis_graph_nodes n
              ON n.source_id = b.source_id
             AND n.id = b.node_id
             AND n.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("n")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            JOIN analysis_graph_boundary_descriptors d
              ON d.boundary_id = b.id
            LEFT JOIN analysis_graph_state state
              ON state.source_id = b.source_id
            LEFT JOIN analysis_graph_boundary_evidence be
              ON be.boundary_id = b.id
            LEFT JOIN analysis_graph_evidence ev
              ON ev.id = be.evidence_id
             AND ev.source_id = b.source_id
            LEFT JOIN analysis_files af
              ON af.file_id = ev.analysis_file_id
            WHERE b.source_id = ?
              AND b.node_id IN ({placeholders})
              AND b.status IN ({current_status_sql})
              AND b.rejection_reason IS NULL
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            ORDER BY b.source_id, b.node_id, b.role, b.id, d.descriptor_path, d.id,
                     ev.relative_path, ev.line_start, ev.line_end, ev.id
            """,
            [
                *current_status_params,
                include_tests,
                source_id,
                *ids,
                *current_status_params,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _source_has_boundary_facts(self, conn: Any, source_id: str) -> bool:
        source_key = str(source_id or "")
        if source_key in self._boundary_fact_source_cache:
            return self._boundary_fact_source_cache[source_key]
        if not self.graph_store._table_exists(conn, "analysis_graph_boundaries"):
            return False
        row = conn.execute(
            """
            SELECT 1
            FROM analysis_graph_boundaries
            WHERE source_id = ?
            LIMIT 1
            """,
            (source_key,),
        ).fetchone()
        result = row is not None
        if result:
            self._boundary_fact_source_cache[source_key] = True
        return result

    def _query_matching_inbound_boundary_operation_fact_rows(
        self,
        conn: Any,
        source_ids: Sequence[str],
        methods: Sequence[str],
        include_tests: bool,
    ) -> list[dict[str, Any]]:
        if not source_ids or not methods:
            return []
        if not self.graph_store._table_exists(conn, "analysis_graph_boundaries"):
            return []
        self._metrics["sqlStatements"] += 1
        source_placeholders = ",".join("?" for _ in source_ids)
        method_placeholders = ",".join("?" for _ in methods)
        lowered_methods = [str(method or "").strip().lower() for method in methods]
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT b.id AS boundary_id,
                   b.source_id AS source_id,
                   b.node_id AS node_id,
                   b.role AS boundary_role,
                   b.status AS boundary_status,
                   b.rejection_reason AS rejection_reason,
                   b.fact_origin AS boundary_fact_origin,
                   COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, b.flow_domain) AS effective_flow_domain,
                   n.qualified_name AS owner_qualified_name,
                   n.relative_path AS owner_relative_path,
                   COALESCE(NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_id,
                   COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), n.source_id || ':query-current-facts') AS graph_revision,
                   EXISTS (
                       SELECT 1
                       FROM files f_current
                       WHERE f_current.source_id = n.source_id
                         AND f_current.relative_path = n.relative_path
                         AND f_current.content_hash = n.content_hash
                   ) AS inventory_current,
                   EXISTS (
                       SELECT 1
                       FROM analysis_files af_current
                       WHERE af_current.source_id = n.source_id
                         AND af_current.relative_path = n.relative_path
                         AND af_current.content_hash = n.content_hash
                         AND af_current.status IN ('ANALYZED', 'ANALYZED_WITH_DIAGNOSTICS', 'PARTIAL')
                   ) AS analyzed_current,
                   d.id AS descriptor_id,
                   d.descriptor_path AS descriptor_path,
                   d.value_type AS descriptor_value_type,
                   d.value_json AS descriptor_value_json,
                   d.origin AS descriptor_origin,
                   d.confidence AS descriptor_confidence,
                   ev.source_id AS evidence_source_id,
                   COALESCE(af.relative_path, ev.relative_path) AS evidence_relative_path,
                   ev.line_start AS evidence_line_start,
                   ev.line_end AS evidence_line_end,
                   ev.excerpt AS evidence_excerpt
            FROM analysis_graph_boundaries b
            JOIN analysis_graph_boundary_descriptor_index method_index
              ON method_index.boundary_id = b.id
             AND method_index.descriptor_path IN ('http.method', 'operation.method')
             AND method_index.normalized_scalar_value IN ({method_placeholders})
            JOIN analysis_graph_nodes n
              ON n.source_id = b.source_id
             AND n.id = b.node_id
             AND n.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("n")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            JOIN analysis_graph_boundary_descriptors d
              ON d.boundary_id = b.id
            LEFT JOIN analysis_graph_state state
              ON state.source_id = b.source_id
            LEFT JOIN analysis_graph_boundary_evidence be
              ON be.boundary_id = b.id
            LEFT JOIN analysis_graph_evidence ev
              ON ev.id = be.evidence_id
             AND ev.source_id = b.source_id
            LEFT JOIN analysis_files af
              ON af.file_id = ev.analysis_file_id
            WHERE b.source_id IN ({source_placeholders})
              AND b.role = 'PROVIDED'
              AND b.status IN ({current_status_sql})
              AND b.rejection_reason IS NULL
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            ORDER BY b.source_id, b.node_id, b.role, b.id, d.descriptor_path, d.id,
                     ev.relative_path, ev.line_start, ev.line_end, ev.id
            """,
            [
                *lowered_methods,
                *current_status_params,
                include_tests,
                *source_ids,
                *current_status_params,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _query_edge_operation_fact_rows(
        self,
        conn: Any,
        source_id: str,
        ids: list[str],
        include_tests: bool,
    ) -> List[Dict[str, Any]]:
        if not ids:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        rows = conn.execute(
            f"""
            SELECT e.id AS edge_id,
                   e.source_id AS source_id,
                   e.from_node_id AS from_node_id,
                   e.edge_type AS edge_type,
                   e.metadata_json AS metadata_json,
                   e.status AS edge_status,
                   e.resolution_status AS edge_resolution_status,
                   COALESCE({self.graph_store._inventory_flow_domain_sql("e")}, {self.graph_store._inventory_flow_domain_sql("fn")}) AS effective_flow_domain,
                   fn.qualified_name AS owner_qualified_name,
                   fn.relative_path AS owner_relative_path,
                   COALESCE(NULLIF(state.graph_id, ''), e.source_id || ':query-current-facts') AS graph_id,
                   COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), e.source_id || ':query-current-facts') AS graph_revision,
                   EXISTS (
                       SELECT 1
                       FROM files f_current
                       WHERE f_current.source_id = e.source_id
                         AND f_current.relative_path = e.relative_path
                         AND f_current.content_hash = e.content_hash
                   ) AS inventory_current,
                   EXISTS (
                       SELECT 1
                       FROM analysis_files af_current
                       WHERE af_current.source_id = e.source_id
                         AND af_current.relative_path = e.relative_path
                         AND af_current.content_hash = e.content_hash
                         AND af_current.status IN ('ANALYZED', 'ANALYZED_WITH_DIAGNOSTICS', 'PARTIAL')
                   ) AS analyzed_current,
                   ev.source_id AS evidence_source_id,
                   COALESCE(af.relative_path, ev.relative_path) AS evidence_relative_path,
                   ev.line_start AS evidence_line_start,
                   ev.line_end AS evidence_line_end,
                   ev.excerpt AS evidence_excerpt
            FROM analysis_graph_edges e
            JOIN analysis_graph_nodes fn
              ON fn.source_id = e.source_id
             AND fn.id = e.from_node_id
             AND fn.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("fn")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("fn")}, '') != 'TEST')
            LEFT JOIN analysis_graph_state state
              ON state.source_id = e.source_id
            LEFT JOIN analysis_graph_owner_evidence owner
              ON owner.owner_kind = 'EDGE'
             AND owner.owner_source_id = e.source_id
             AND owner.owner_edge_id = e.id
            LEFT JOIN analysis_graph_evidence ev
              ON ev.source_id = owner.evidence_source_id
             AND ev.id = owner.evidence_id
            LEFT JOIN analysis_files af
              ON af.file_id = ev.analysis_file_id
            WHERE e.source_id = ?
              AND e.from_node_id IN ({placeholders})
              AND e.status IN ({current_status_sql})
              AND {self.graph_store._inventory_membership_graph_edge_clause("e")}
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("e")}, '') != 'TEST')
            ORDER BY e.source_id, e.from_node_id, e.id, ev.relative_path, ev.line_start, ev.line_end, ev.id
            """,
            [
                *current_status_params,
                include_tests,
                source_id,
                *ids,
                *current_status_params,
                include_tests,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _operation_facts_from_rows(
        self,
        rows: Sequence[Dict[str, Any]],
    ) -> tuple[AvailableOperationFact, ...]:
        grouped: dict[str, list[Dict[str, Any]]] = defaultdict(list)
        for row in rows:
            grouped[str(row.get("claim_id") or "")].append(row)
        facts: list[AvailableOperationFact] = []
        for claim_id, claim_rows in grouped.items():
            if not claim_id or not claim_rows:
                continue
            first = claim_rows[0]
            operation_identity, interface_identity = split_operation_interface_identity(first.get("entrypoint_interface_method"))
            evidence = tuple(
                OperationFactEvidence(
                    source_id=str(row.get("evidence_source_id") or first.get("source_id") or ""),
                    relative_path=str(row.get("evidence_relative_path")) if row.get("evidence_relative_path") else None,
                    line_start=int(row.get("evidence_line_start")) if row.get("evidence_line_start") is not None else None,
                    line_end=int(row.get("evidence_line_end")) if row.get("evidence_line_end") is not None else None,
                    excerpt=str(row.get("evidence_excerpt")) if row.get("evidence_excerpt") else None,
                )
                for row in claim_rows
                if row.get("evidence_source_id")
            )
            facts.append(
                AvailableOperationFact(
                    owner_source_id=str(first.get("source_id") or ""),
                    owner_graph_id=str(first.get("graph_id") or ""),
                    owner_graph_revision=str(first.get("graph_revision")) if first.get("graph_revision") else None,
                    owner_node_id=str(first.get("node_id") or ""),
                    source_id=str(first.get("source_id") or ""),
                    execution_role=clean_identity(first.get("entrypoint_execution_kind")),
                    transport_kind=normalize_transport_kind(first.get("entrypoint_kind")),
                    direction_role=self._claim_fact_direction(first),
                    method=normalize_http_method(first.get("entrypoint_http_method")),
                    normalized_route=normalize_route(first.get("entrypoint_route")),
                    topic=clean_identity(first.get("entrypoint_topic")),
                    schedule=clean_identity(first.get("entrypoint_schedule")),
                    operation_identity=operation_identity,
                    interface_identity=interface_identity,
                    owner_qualified_name=clean_identity(first.get("owner_qualified_name")),
                    owner_relative_path=clean_identity(first.get("owner_relative_path")),
                    evidence=evidence,
                    eligibility=OperationFactEligibility(
                        status=clean_identity(first.get("claim_status")),
                        rejection_reason=clean_identity(first.get("rejection_reason")),
                        flow_domain=clean_identity(first.get("effective_flow_domain")),
                        inventory_current=bool(first.get("inventory_current")),
                        analyzed_current=bool(first.get("analyzed_current")),
                    ),
                    source_channel="ENTRYPOINT_HINT",
                )
            )
        return tuple(facts)

    def _operation_facts_from_edge_rows(self, rows: Sequence[Dict[str, Any]]) -> tuple[AvailableOperationFact, ...]:
        grouped: dict[str, list[Dict[str, Any]]] = defaultdict(list)
        for row in rows:
            grouped[str(row.get("edge_id") or "")].append(row)
        facts: list[AvailableOperationFact] = []
        for edge_id, edge_rows in grouped.items():
            if not edge_id or not edge_rows:
                continue
            first = edge_rows[0]
            metadata = self._json_object(first.get("metadata_json"))
            transport = normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind"))
            method = normalize_http_method(metadata.get("httpMethod") or metadata.get("method"))
            route = normalize_route(metadata.get("routeTemplate") or metadata.get("route"))
            if transport != "HTTP" or not method or not route:
                continue
            direction = self._edge_fact_direction(first.get("edge_type"), metadata)
            if direction is None:
                continue
            operation_identity = clean_identity(metadata.get("operationIdentity"))
            interface_identity = clean_identity(
                metadata.get("interfaceIdentity")
                or metadata.get("interfaceMethod")
                or metadata.get("targetInterfaceMethod")
            )
            if not operation_identity and not interface_identity:
                operation_identity, interface_identity = split_operation_interface_identity(metadata.get("targetEntrypoint"))
            evidence = tuple(
                OperationFactEvidence(
                    source_id=str(row.get("evidence_source_id") or first.get("source_id") or ""),
                    relative_path=str(row.get("evidence_relative_path")) if row.get("evidence_relative_path") else None,
                    line_start=int(row.get("evidence_line_start")) if row.get("evidence_line_start") is not None else None,
                    line_end=int(row.get("evidence_line_end")) if row.get("evidence_line_end") is not None else None,
                    excerpt=str(row.get("evidence_excerpt")) if row.get("evidence_excerpt") else None,
                )
                for row in edge_rows
                if row.get("evidence_source_id")
            )
            facts.append(
                AvailableOperationFact(
                    owner_source_id=str(first.get("source_id") or ""),
                    owner_graph_id=str(first.get("graph_id") or ""),
                    owner_graph_revision=str(first.get("graph_revision")) if first.get("graph_revision") else None,
                    owner_node_id=str(first.get("from_node_id") or ""),
                    source_id=str(first.get("source_id") or ""),
                    execution_role="EDGE_METADATA",
                    transport_kind=transport,
                    direction_role=direction,
                    method=method,
                    normalized_route=route,
                    operation_identity=operation_identity,
                    interface_identity=interface_identity,
                    request_contract_identity=clean_identity(metadata.get("requestContractIdentity")),
                    response_contract_identity=clean_identity(metadata.get("responseContractIdentity")),
                    target_service_identity=clean_identity(metadata.get("targetServiceIdentity")),
                    owner_qualified_name=clean_identity(first.get("owner_qualified_name")),
                    owner_relative_path=clean_identity(first.get("owner_relative_path")),
                    owner_edge_id=edge_id,
                    evidence=evidence,
                    eligibility=OperationFactEligibility(
                        status=clean_identity(first.get("edge_status")),
                        rejection_reason=None,
                        flow_domain=clean_identity(first.get("effective_flow_domain")),
                        inventory_current=bool(first.get("inventory_current")),
                        analyzed_current=bool(first.get("analyzed_current")),
                    ),
                    source_channel="EDGE_METADATA",
                )
            )
        return tuple(facts)

    def _operation_facts_from_boundary_rows(self, rows: Sequence[dict[str, Any]]) -> tuple[AvailableOperationFact, ...]:
        grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for row in rows:
            grouped[str(row.get("boundary_id") or "")].append(row)
        facts: list[AvailableOperationFact] = []
        for boundary_id, boundary_rows in grouped.items():
            if not boundary_id or not boundary_rows:
                continue
            first = boundary_rows[0]
            descriptors = self._boundary_descriptor_values(boundary_rows)
            role = str(first.get("boundary_role") or "").strip().upper()
            direction = "INBOUND" if role == "PROVIDED" else "OUTBOUND" if role == "REQUIRED" else None
            if direction is None:
                continue
            transport = normalize_transport_kind(
                self._first_descriptor(descriptors, "transport.kind")
                or self._first_descriptor(descriptors, "provided.kind")
                or self._first_descriptor(descriptors, "connector.kind")
            )
            method = normalize_http_method(
                self._first_descriptor(descriptors, "http.method")
                or self._first_descriptor(descriptors, "operation.method")
            )
            route = normalize_route(
                self._first_descriptor(descriptors, "http.route")
                or self._first_descriptor(descriptors, "operation.routeTemplate")
                or self._first_descriptor(descriptors, "operation.route")
            )
            topic = clean_identity(self._first_descriptor(descriptors, "messaging.topic"))
            schedule = clean_identity(self._first_descriptor(descriptors, "schedule.expression"))
            if not any((transport, method, route, topic, schedule)):
                continue
            operation_identity = clean_identity(self._first_descriptor(descriptors, "operation.identity"))
            interface_identity = clean_identity(
                self._first_descriptor(descriptors, "interface.identity")
                or self._first_descriptor(descriptors, "interface.method")
            )
            evidence = tuple(
                dict.fromkeys(
                    OperationFactEvidence(
                        source_id=str(row.get("evidence_source_id") or first.get("source_id") or ""),
                        relative_path=str(row.get("evidence_relative_path")) if row.get("evidence_relative_path") else None,
                        line_start=int(row.get("evidence_line_start")) if row.get("evidence_line_start") is not None else None,
                        line_end=int(row.get("evidence_line_end")) if row.get("evidence_line_end") is not None else None,
                        excerpt=str(row.get("evidence_excerpt")) if row.get("evidence_excerpt") else None,
                    )
                    for row in boundary_rows
                    if row.get("evidence_source_id")
                )
            )
            facts.append(
                AvailableOperationFact(
                    owner_source_id=str(first.get("source_id") or ""),
                    owner_graph_id=str(first.get("graph_id") or ""),
                    owner_graph_revision=str(first.get("graph_revision")) if first.get("graph_revision") else None,
                    owner_node_id=str(first.get("node_id") or ""),
                    source_id=str(first.get("source_id") or ""),
                    execution_role=role,
                    transport_kind=transport,
                    direction_role=direction,
                    method=method,
                    normalized_route=route,
                    topic=topic,
                    schedule=schedule,
                    operation_identity=operation_identity,
                    interface_identity=interface_identity,
                    request_contract_identity=clean_identity(self._first_descriptor(descriptors, "contract.request")),
                    response_contract_identity=clean_identity(self._first_descriptor(descriptors, "contract.response")),
                    target_service_identity=clean_identity(self._first_descriptor(descriptors, "target.serviceIdentity")),
                    owner_qualified_name=clean_identity(first.get("owner_qualified_name")),
                    owner_relative_path=clean_identity(first.get("owner_relative_path")),
                    evidence=evidence,
                    eligibility=OperationFactEligibility(
                        status=clean_identity(first.get("boundary_status")),
                        rejection_reason=clean_identity(first.get("rejection_reason")),
                        flow_domain=clean_identity(first.get("effective_flow_domain")),
                        inventory_current=bool(first.get("inventory_current")),
                        analyzed_current=bool(first.get("analyzed_current")),
                    ),
                    source_channel="BOUNDARY_FACT",
                )
            )
        return tuple(facts)

    def _boundary_descriptor_values(self, rows: Sequence[dict[str, Any]]) -> dict[str, list[Any]]:
        values: dict[str, list[Any]] = defaultdict(list)
        seen: set[tuple[str, str]] = set()
        for row in rows:
            path = str(row.get("descriptor_path") or "")
            raw_json = str(row.get("descriptor_value_json") or "")
            if not path or (path, raw_json) in seen:
                continue
            seen.add((path, raw_json))
            try:
                parsed = json.loads(raw_json)
            except json.JSONDecodeError:
                parsed = raw_json
            values[path].append(parsed)
        return values

    def _first_descriptor(self, descriptors: dict[str, list[Any]], path: str) -> Any:
        values = descriptors.get(path) or []
        return values[0] if values else None

    def _claim_fact_direction(self, row: Dict[str, Any]) -> str | None:
        if normalize_transport_kind(row.get("entrypoint_kind")) != "HTTP":
            return None
        if not normalize_http_method(row.get("entrypoint_http_method")) or not normalize_route(row.get("entrypoint_route")):
            return None
        role = str(row.get("entrypoint_execution_kind") or "").strip().upper()
        if role == EntrypointExecutionKind.EXECUTABLE.value:
            return "INBOUND"
        if role == EntrypointExecutionKind.CLIENT_OPERATION.value:
            return "OUTBOUND"
        return None

    def _edge_fact_direction(self, edge_type: object, metadata: Dict[str, Any]) -> str | None:
        return edge_backed_http_direction(
            metadata,
            execution_continuation=EXECUTION_CONTINUATION in graph_relation_semantics().edge_semantics(str(edge_type or "")),
        )

    def _inbound_fact_matches_any_outbound(
        self,
        inbound: AvailableOperationFact,
        outbound_facts: Sequence[AvailableOperationFact],
    ) -> bool:
        if not self._is_current_inbound_http_fact(inbound):
            return False
        return any(self._operation_facts_match(outbound, inbound) for outbound in outbound_facts)

    def _is_current_inbound_http_fact(self, fact: AvailableOperationFact) -> bool:
        if normalize_transport_kind(fact.transport_kind) != "HTTP":
            return False
        if str(fact.direction_role or "").strip().upper() != "INBOUND":
            return False
        if not normalize_http_method(fact.method) or not normalize_route(fact.normalized_route):
            return False
        if fact.eligibility is not None and (not fact.eligibility.inventory_current or not fact.eligibility.analyzed_current):
            return False
        return True

    def _operation_facts_match(self, outbound: AvailableOperationFact, inbound: AvailableOperationFact) -> bool:
        if normalize_transport_kind(outbound.transport_kind) != normalize_transport_kind(inbound.transport_kind):
            return False
        if normalize_http_method(outbound.method) != normalize_http_method(inbound.method):
            return False
        if normalize_route(outbound.normalized_route) != normalize_route(inbound.normalized_route):
            return False
        if outbound.target_service_identity and inbound.owner_source_id != outbound.target_service_identity:
            return False
        for attr in (
            "operation_identity",
            "interface_identity",
            "request_contract_identity",
            "response_contract_identity",
        ):
            outbound_value = clean_identity(getattr(outbound, attr))
            inbound_value = clean_identity(getattr(inbound, attr))
            if outbound_value and inbound_value and outbound_value != inbound_value:
                return False
        return True

    def _json_object(self, value: object) -> dict[str, Any]:
        if isinstance(value, dict):
            return value
        try:
            parsed = json.loads(str(value or "{}"))
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    def _dedupe_operation_facts(self, facts: Sequence[AvailableOperationFact]) -> tuple[AvailableOperationFact, ...]:
        return merge_semantic_operation_facts(facts)

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
                       NULL AS node_id,
                       owner.owner_kind,
                       owner.owner_source_id,
                       owner.owner_node_id,
                       owner.owner_edge_id
                FROM analysis_graph_edges edge
                JOIN analysis_graph_owner_evidence owner
                  ON owner.owner_kind = 'EDGE'
                 AND owner.owner_source_id = edge.source_id
                 AND owner.owner_edge_id = edge.id
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = owner.evidence_source_id
                 AND ev.id = owner.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE edge.source_id = ?
                  AND edge.status IN ({current_status_sql})
                  AND {self.graph_store._inventory_membership_graph_edge_clause("edge")}
                  AND edge.id IN ({placeholders})
                ORDER BY edge.id, relative_path, ev.line_start, ev.line_end, ev.id
                """,
                [source_id, *current_status_params, *chunk],
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
                       owner.owner_node_id AS node_id,
                       owner.owner_kind,
                       owner.owner_source_id,
                       owner.owner_node_id,
                       owner.owner_edge_id
                FROM analysis_graph_owner_evidence owner
                JOIN analysis_graph_evidence ev
                  ON ev.source_id = owner.evidence_source_id
                 AND ev.id = owner.evidence_id
                LEFT JOIN analysis_files af ON af.file_id = ev.analysis_file_id
                WHERE owner.owner_kind = 'NODE'
                  AND owner.owner_source_id = ?
                  AND owner.owner_node_id IN ({placeholders})
                  AND EXISTS (
                      SELECT 1
                      FROM analysis_graph_claims claim
                      WHERE claim.source_id = owner.owner_source_id
                        AND claim.node_id = owner.owner_node_id
                        AND claim.status IN ({current_status_sql})
                        AND claim.rejection_reason IS NULL
                  )
                ORDER BY owner.owner_node_id, relative_path, ev.line_start, ev.line_end, ev.id
                """,
                [source_id, *chunk, *current_status_params],
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
        if not graph_id and not revision:
            return bool(item_revision)
        return bool(item_revision) and item_revision in {graph_id, revision}

    def _matches_target_identity(self, edge: FlowGraphEdge, identity: dict[str, str | None]) -> bool:
        graph_id = str(identity.get("graphId") or "")
        revision = str(identity.get("graphRevision") or graph_id)
        item_revision = edge.to_graph_revision or edge.to_graph_id
        if not graph_id and not revision:
            return bool(item_revision)
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

    def _boundary_sort_key(self, boundary: LocalBoundaryFact) -> tuple[str, str, str, str, str]:
        return (
            boundary.source_id,
            boundary.graph_revision or boundary.graph_id,
            boundary.owner_node_id,
            boundary.role,
            boundary.stable_key or boundary.boundary_id,
        )

    def _local_boundary_descriptor_sort_key(self, descriptor: LocalBoundaryDescriptor) -> tuple[str, str, str, str]:
        return (
            descriptor.path,
            descriptor.value_type,
            json.dumps(descriptor.value, sort_keys=True, default=str),
            descriptor.descriptor_id,
        )

    def _operation_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[str, str, str, str, str, str]:
        return (
            fact.owner_source_id,
            fact.owner_graph_revision or fact.owner_graph_id,
            fact.owner_node_id,
            fact.transport_kind or "",
            fact.method or "",
            fact.normalized_route or "",
        )
