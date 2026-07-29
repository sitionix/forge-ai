from __future__ import annotations

import json
from collections import defaultdict
from dataclasses import replace
from typing import Any, Iterable, Sequence

from knowledge_service.boundary_contract import LocalBoundaryDescriptor, LocalBoundaryFact
from knowledge_service.boundary_resolution import (
    ACCEPTED_BOUNDARY_STATUSES,
    BOUNDARY_ROLE_PROVIDED,
    BoundaryCandidateLoadLimits,
    BoundaryCandidateLoadResult,
    BoundaryIdentity,
    BoundaryResolutionDiagnostic,
    boundary_identity,
    descriptor_fingerprint,
    descriptor_fingerprint_from_row,
)
from knowledge_service.entrypoint_kinds import EntrypointExecutionKind
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowNodeKey, dedupe_evidence
from knowledge_service.graph_query_contract import graph_query_contract, sql_in_clause
from knowledge_service.graph_relation_semantics import EXECUTION_CONTINUATION, graph_relation_semantics
from knowledge_service.local_flow_unit_engine import LocalFlowUnit

_SQLITE_BIND_CHUNK_SIZE = 800


def clean_identity(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def _chunks(values: Sequence[Any], size: int = _SQLITE_BIND_CHUNK_SIZE) -> Iterable[Sequence[Any]]:
    for offset in range(0, len(values), size):
        yield values[offset: offset + size]


def _source_fair_boundary_identity_limit(identities: Sequence[BoundaryIdentity], limit: int) -> tuple[BoundaryIdentity, ...]:
    if limit <= 0:
        return ()
    by_source: dict[str, list[BoundaryIdentity]] = defaultdict(list)
    for identity in sorted(identities):
        by_source[identity.source_id].append(identity)
    offsets = {source_id: 0 for source_id in by_source}
    retained: list[BoundaryIdentity] = []
    while len(retained) < limit:
        progressed = False
        for source_id in sorted(by_source):
            offset = offsets[source_id]
            values = by_source[source_id]
            if offset >= len(values):
                continue
            retained.append(values[offset])
            offsets[source_id] = offset + 1
            progressed = True
            if len(retained) >= limit:
                break
        if not progressed:
            break
    return tuple(sorted(retained))


class LocalFlowUnitGraphRepository:
    def __init__(self, graph_store: Any) -> None:
        self.graph_store = graph_store
        self._metrics: dict[str, int] = defaultdict(int)
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

    def hydrate_local_units(self, units: Sequence[LocalFlowUnit]) -> tuple[LocalFlowUnit, ...]:
        self.graph_store.init()
        if not units:
            return ()
        edge_ids_by_source: dict[str, set[str]] = defaultdict(set)
        node_ids_by_source: dict[str, set[str]] = defaultdict(set)
        for unit in units:
            for edge in (*unit.execution_transitions, *unit.topology_boundaries):
                edge_ids_by_source[edge.source_id].add(edge.edge_id)
            for node in (*unit.execution_nodes, *unit.supporting_context):
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

        hydrated: list[LocalFlowUnit] = []
        for unit in units:
            flow_edge_ids = {
                edge.edge_id
                for edge in (*unit.execution_transitions, *unit.topology_boundaries)
            }
            flow_node_keys = {(node.source_id, node.node_id) for node in (*unit.execution_nodes, *unit.supporting_context)}
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
                    *unit.evidence,
                    *edge_evidence,
                    *node_evidence,
                ]
            )
            replacements: dict[str, Any] = {
                "execution_transitions": tuple(self._edge_with_evidence(edge, edge_ids_by_edge) for edge in unit.execution_transitions),
                "topology_boundaries": tuple(self._edge_with_evidence(edge, edge_ids_by_edge) for edge in unit.topology_boundaries),
                "evidence": evidence,
            }
            hydrated.append(replace(unit, **replacements))
        return tuple(hydrated)

    def find_provided_boundary_candidates(
        self,
        required_boundaries: Sequence[LocalBoundaryFact],
        *,
        eligible_source_ids: Sequence[str],
        include_tests: bool,
        internal_limits: BoundaryCandidateLoadLimits | None = None,
    ) -> BoundaryCandidateLoadResult:
        limits = internal_limits or BoundaryCandidateLoadLimits()
        source_ids = tuple(sorted(dict.fromkeys(str(source_id or "").strip() for source_id in eligible_source_ids if str(source_id or "").strip())))
        required = tuple(
            sorted(
                (
                    boundary
                    for boundary in required_boundaries
                    if str(boundary.role or "").strip().upper() == "REQUIRED"
                    and str(boundary.status or "").strip().upper() in ACCEPTED_BOUNDARY_STATUSES
                ),
                key=self._boundary_sort_key,
            )
        )
        fingerprints_by_required: dict[BoundaryIdentity, tuple[Any, ...]] = {}
        for boundary in required:
            fingerprints_by_required[boundary_identity(boundary)] = tuple(sorted({descriptor_fingerprint(descriptor) for descriptor in boundary.descriptors}))
        queried_fingerprints = tuple(sorted({fingerprint for values in fingerprints_by_required.values() for fingerprint in values}))
        if not required or not source_ids or not queried_fingerprints:
            return BoundaryCandidateLoadResult(
                candidates_by_required_identity={boundary_identity(boundary): () for boundary in required},
                provided_boundaries_by_fingerprint={},
                eligible_provided_boundary_count=0,
                provided_candidates_by_source={},
                descriptor_fingerprints_queried=len(queried_fingerprints),
                candidate_descriptor_row_budget=max(0, int(limits.max_candidate_descriptor_rows_scanned)),
            )

        diagnostics: list[BoundaryResolutionDiagnostic] = []
        truncated_required: set[BoundaryIdentity] = set()
        path_type_pairs = tuple(sorted({(fingerprint.path, fingerprint.value_type) for fingerprint in queried_fingerprints}))
        required_by_path_type: dict[tuple[str, str], set[BoundaryIdentity]] = defaultdict(set)
        for required_identity, fingerprints in fingerprints_by_required.items():
            for fingerprint in fingerprints:
                required_by_path_type[(fingerprint.path, fingerprint.value_type)].add(required_identity)
        if len(path_type_pairs) > limits.max_descriptor_path_type_pairs:
            omitted_path_type_pairs = set(path_type_pairs[limits.max_descriptor_path_type_pairs:])
            path_type_pairs = path_type_pairs[: limits.max_descriptor_path_type_pairs]
            truncated_required.update(
                required_identity
                for pair in omitted_path_type_pairs
                for required_identity in required_by_path_type.get(pair, set())
            )
            diagnostics.append(
                BoundaryResolutionDiagnostic(
                    code="BOUNDARY_RESOLUTION_LIMIT_REACHED",
                    message="Boundary descriptor path/type query set reached the internal resolver limit.",
                    severity="WARN",
                    metadata={"pathTypePairLimit": limits.max_descriptor_path_type_pairs},
                )
            )

        self.graph_store.init()
        sql_before = int(self._metrics.get("sqlStatements", 0))
        candidate_ids_by_fingerprint: dict[Any, set[BoundaryIdentity]] = defaultdict(set)
        candidate_db_ids_by_identity: dict[BoundaryIdentity, tuple[str, str]] = {}
        eligible_provided_count = 0
        descriptor_rows_scanned = 0
        descriptor_rows_matched_exactly = 0
        descriptor_row_budget = max(0, int(limits.max_candidate_descriptor_rows_scanned))
        descriptor_scan_truncated = False
        inspected_sources: set[str] = set()
        truncated_sources: set[str] = set()
        candidate_pages_loaded = 0
        with self.graph_store._connect() as conn:
            if not self.graph_store._table_exists(conn, "analysis_graph_boundaries"):
                return BoundaryCandidateLoadResult(
                    candidates_by_required_identity={boundary_identity(boundary): () for boundary in required},
                    provided_boundaries_by_fingerprint={},
                    eligible_provided_boundary_count=0,
                    provided_candidates_by_source={},
                    descriptor_fingerprints_queried=len(queried_fingerprints),
                    diagnostics=tuple(diagnostics),
                    sql_statements=int(self._metrics.get("sqlStatements", 0)) - sql_before,
                    candidate_descriptor_row_budget=descriptor_row_budget,
                    required_candidate_sets_incomplete=len(truncated_required),
                )
            eligible_provided_count = self._count_eligible_provided_boundaries(conn, source_ids, include_tests)
            queried_set = set(queried_fingerprints)
            path_type_chunks = tuple(tuple(chunk) for chunk in _chunks(path_type_pairs, max(1, limits.max_path_type_chunk_size)))
            scan_keys = tuple((source_id, index, chunk) for source_id in source_ids for index, chunk in enumerate(path_type_chunks))
            active_scan_keys = set(scan_keys)
            cursors: dict[tuple[str, int, tuple[tuple[str, str], ...]], tuple[str, str, str, str, str, str, str]] = {}
            page_turn_size = max(
                1,
                min(
                    max(1, int(limits.max_candidate_descriptor_page_size)),
                    max(1, descriptor_row_budget // max(1, len(source_ids))),
                ),
            )
            if descriptor_row_budget <= 0 and active_scan_keys:
                descriptor_scan_truncated = True
                truncated_sources.update(source_ids)
                truncated_required.update(fingerprints_by_required)
                active_scan_keys = set()
            while active_scan_keys and descriptor_rows_scanned < descriptor_row_budget:
                progressed = False
                for scan_key in sorted(active_scan_keys, key=lambda item: (item[1], item[0])):
                    if descriptor_rows_scanned >= descriptor_row_budget:
                        break
                    source_id, _chunk_index, pair_chunk = scan_key
                    remaining_budget = descriptor_row_budget - descriptor_rows_scanned
                    page_limit = min(page_turn_size, remaining_budget)
                    rows = self._query_provided_boundary_descriptor_candidate_row_page(
                        conn,
                        source_id,
                        pair_chunk,
                        include_tests,
                        cursor=cursors.get(scan_key),
                        limit=page_limit,
                    )
                    candidate_pages_loaded += 1
                    inspected_sources.add(source_id)
                    if not rows:
                        active_scan_keys.remove(scan_key)
                        continue
                    progressed = True
                    descriptor_rows_scanned += len(rows)
                    for row in rows:
                        fingerprint = descriptor_fingerprint_from_row(
                            row.get("descriptor_path"),
                            row.get("descriptor_value_type"),
                            row.get("descriptor_value_json"),
                        )
                        if fingerprint not in queried_set:
                            continue
                        descriptor_rows_matched_exactly += 1
                        identity = BoundaryIdentity(
                            source_id=str(row.get("source_id") or ""),
                            graph_revision=str(row.get("graph_revision") or row.get("graph_id") or ""),
                            boundary_key=str(row.get("boundary_stable_key") or row.get("boundary_id") or ""),
                            owner_node_id=str(row.get("node_id") or ""),
                        )
                        candidate_ids_by_fingerprint[fingerprint].add(identity)
                        candidate_db_ids_by_identity.setdefault(identity, (str(row.get("source_id") or ""), str(row.get("boundary_id") or "")))
                    cursors[scan_key] = self._candidate_descriptor_scan_key(rows[-1])
                    if len(rows) < page_limit:
                        active_scan_keys.remove(scan_key)
                if not progressed:
                    break
            if active_scan_keys:
                descriptor_scan_truncated = True
                truncated_sources.update(scan_key[0] for scan_key in active_scan_keys)
                truncated_required.update(fingerprints_by_required)

            candidate_identities_by_required: dict[BoundaryIdentity, tuple[BoundaryIdentity, ...]] = {}
            for required_identity, fingerprints in sorted(fingerprints_by_required.items()):
                identities = sorted(
                    {
                        identity
                        for fingerprint in fingerprints
                        for identity in candidate_ids_by_fingerprint.get(fingerprint, set())
                        if identity.source_id != required_identity.source_id
                    }
                )
                if len(identities) > limits.max_candidates_per_required:
                    truncated_required.add(required_identity)
                    identities = list(_source_fair_boundary_identity_limit(identities, limits.max_candidates_per_required))
                candidate_identities_by_required[required_identity] = tuple(identities)

            selected_identities = sorted({identity for values in candidate_identities_by_required.values() for identity in values})
            if len(selected_identities) > limits.max_candidate_boundaries_total:
                retained = set(_source_fair_boundary_identity_limit(selected_identities, limits.max_candidate_boundaries_total))
                truncated_required.update(
                    required_identity
                    for required_identity, identities in candidate_identities_by_required.items()
                    if any(identity not in retained for identity in identities)
                )
                selected_identities = sorted(retained)

            facts_by_identity: dict[BoundaryIdentity, LocalBoundaryFact] = {}
            ids_by_source: dict[str, list[str]] = defaultdict(list)
            for identity in selected_identities:
                source_id, boundary_id = candidate_db_ids_by_identity.get(identity, ("", ""))
                if source_id and boundary_id:
                    ids_by_source[source_id].append(boundary_id)
            identity_by_source = self.graph_store._graph_identity_by_source(conn, sorted(ids_by_source))
            for source_id, boundary_ids in sorted(ids_by_source.items()):
                identity = identity_by_source.get(source_id) or {}
                for chunk in _chunks(sorted(set(boundary_ids)), max(1, limits.max_boundary_id_chunk_size)):
                    rows = self._query_boundary_rows_by_boundary_ids(conn, source_id, list(chunk), include_tests)
                    self._metrics["boundaryCandidateRowsLoaded"] += len(rows)
                    for fact in self._boundary_facts_from_rows(rows, identity):
                        facts_by_identity[boundary_identity(fact)] = fact

        provided_by_fingerprint = {
            fingerprint: frozenset(identities)
            for fingerprint, identities in sorted(candidate_ids_by_fingerprint.items(), key=lambda item: item[0])
        }
        candidates_by_required = {
            required_identity: tuple(
                facts_by_identity[identity]
                for identity in identities
                if identity in facts_by_identity
            )
            for required_identity, identities in sorted(candidate_identities_by_required.items())
        }
        candidates_by_source: dict[str, int] = defaultdict(int)
        for fact in {boundary_identity(fact): fact for values in candidates_by_required.values() for fact in values}.values():
            candidates_by_source[fact.source_id] += 1
        if truncated_required:
            diagnostics.append(
                BoundaryResolutionDiagnostic(
                    code="BOUNDARY_CANDIDATE_SET_INCOMPLETE",
                    message="One or more boundary candidate sets reached an internal resolver limit.",
                    severity="WARN",
                    metadata={
                        "requiredBoundaryCount": len(truncated_required),
                        "candidateDescriptorRowsScanned": descriptor_rows_scanned,
                        "candidateDescriptorRowsMatchedExactly": descriptor_rows_matched_exactly,
                        "candidateDescriptorRowBudget": descriptor_row_budget,
                        "candidateDescriptorScanTruncated": descriptor_scan_truncated,
                        "candidateSourcesInspected": len(inspected_sources),
                        "candidateSourcesTruncated": len(truncated_sources),
                        "candidatePagesLoaded": candidate_pages_loaded,
                        "requiredCandidateSetsIncomplete": len(truncated_required),
                    },
                )
            )
        return BoundaryCandidateLoadResult(
            candidates_by_required_identity=candidates_by_required,
            provided_boundaries_by_fingerprint=provided_by_fingerprint,
            eligible_provided_boundary_count=eligible_provided_count,
            provided_candidates_by_source=dict(sorted(candidates_by_source.items())),
            descriptor_fingerprints_queried=len(queried_fingerprints),
            truncated_required_identities=frozenset(truncated_required),
            diagnostics=tuple(diagnostics),
            sql_statements=int(self._metrics.get("sqlStatements", 0)) - sql_before,
            candidate_descriptor_rows_scanned=descriptor_rows_scanned,
            candidate_descriptor_rows_matched_exactly=descriptor_rows_matched_exactly,
            candidate_descriptor_row_budget=descriptor_row_budget,
            candidate_descriptor_scan_truncated=descriptor_scan_truncated,
            candidate_sources_inspected=len(inspected_sources),
            candidate_sources_truncated=len(truncated_sources),
            candidate_pages_loaded=candidate_pages_loaded,
            required_candidate_sets_incomplete=len(truncated_required),
        )

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

    def _query_nodes(self, conn: Any, source_id: str, ids: list[str], include_tests: bool) -> list[dict[str, Any]]:
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
    ) -> list[dict[str, Any]]:
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
        projected: list[dict[str, Any]] = []
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
    ) -> list[dict[str, Any]]:
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
        projected: list[dict[str, Any]] = []
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
             AND {self._current_evidence_clause("boundary_ev")}
            LEFT JOIN analysis_files boundary_af
              ON boundary_af.file_id = boundary_ev.analysis_file_id
            LEFT JOIN analysis_graph_boundary_descriptor_evidence de
              ON de.descriptor_id = d.id
            LEFT JOIN analysis_graph_evidence descriptor_ev
              ON descriptor_ev.id = de.evidence_id
             AND descriptor_ev.source_id = b.source_id
             AND {self._current_evidence_clause("descriptor_ev")}
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

    def _count_eligible_provided_boundaries(
        self,
        conn: Any,
        source_ids: Sequence[str],
        include_tests: bool,
    ) -> int:
        if not source_ids:
            return 0
        self._metrics["sqlStatements"] += 1
        source_sql, source_params = sql_in_clause(source_ids)
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        row = conn.execute(
            f"""
            SELECT COUNT(DISTINCT b.id) AS count
            FROM analysis_graph_boundaries b
            JOIN analysis_graph_nodes n
              ON n.source_id = b.source_id
             AND n.id = b.node_id
             AND n.status IN ({current_status_sql})
             AND {self.graph_store._inventory_membership_graph_node_clause("n")}
             AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            WHERE b.source_id IN ({source_sql})
              AND b.role = ?
              AND b.status IN ({current_status_sql})
              AND b.rejection_reason IS NULL
              AND {self.graph_store._inventory_membership_graph_edge_clause("b")}
              AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
            """,
            [
                *current_status_params,
                include_tests,
                *source_params,
                BOUNDARY_ROLE_PROVIDED,
                *current_status_params,
                include_tests,
            ],
        ).fetchone()
        return int(row["count"] or 0) if row is not None else 0

    def _query_provided_boundary_descriptor_candidate_row_page(
        self,
        conn: Any,
        source_id: str,
        path_type_pairs: Sequence[tuple[str, str]],
        include_tests: bool,
        *,
        cursor: tuple[str, str, str, str, str, str, str] | None,
        limit: int,
    ) -> list[dict[str, Any]]:
        if not source_id or not path_type_pairs or limit <= 0:
            return []
        self._metrics["sqlStatements"] += 1
        path_type_sql = " OR ".join("(TRIM(d.descriptor_path) = ? AND TRIM(d.value_type) = ?)" for _ in path_type_pairs)
        path_type_params = [value for pair in path_type_pairs for value in pair]
        contract = graph_query_contract()
        current_status_sql, current_status_params = sql_in_clause(contract.statuses_for_current_graph())
        cursor_values = cursor or ("", "", "", "", "", "", "")
        cursor_clause = """
              AND (
                    ? = 0
                 OR source_id > ?
                 OR (source_id = ? AND graph_revision > ?)
                 OR (source_id = ? AND graph_revision = ? AND boundary_sort_key > ?)
                 OR (source_id = ? AND graph_revision = ? AND boundary_sort_key = ? AND boundary_id > ?)
                 OR (source_id = ? AND graph_revision = ? AND boundary_sort_key = ? AND boundary_id = ? AND descriptor_path > ?)
                 OR (source_id = ? AND graph_revision = ? AND boundary_sort_key = ? AND boundary_id = ? AND descriptor_path = ? AND descriptor_value_type > ?)
                 OR (source_id = ? AND graph_revision = ? AND boundary_sort_key = ? AND boundary_id = ? AND descriptor_path = ? AND descriptor_value_type = ? AND descriptor_id > ?)
              )
        """
        cursor_params = [
            0 if cursor is None else 1,
            cursor_values[0],
            cursor_values[0],
            cursor_values[1],
            cursor_values[0],
            cursor_values[1],
            cursor_values[2],
            cursor_values[0],
            cursor_values[1],
            cursor_values[2],
            cursor_values[3],
            cursor_values[0],
            cursor_values[1],
            cursor_values[2],
            cursor_values[3],
            cursor_values[4],
            cursor_values[0],
            cursor_values[1],
            cursor_values[2],
            cursor_values[3],
            cursor_values[4],
            cursor_values[5],
            cursor_values[0],
            cursor_values[1],
            cursor_values[2],
            cursor_values[3],
            cursor_values[4],
            cursor_values[5],
            cursor_values[6],
        ]
        rows = conn.execute(
            f"""
            WITH candidate_rows AS (
                SELECT b.id AS boundary_id,
                       b.source_id AS source_id,
                       COALESCE(NULLIF(b.stable_key, ''), b.id) AS boundary_sort_key,
                       b.stable_key AS boundary_stable_key,
                       b.node_id AS node_id,
                       COALESCE(NULLIF(state.graph_id, ''), b.source_id || ':query-current-facts') AS graph_id,
                       COALESCE(NULLIF(state.content_identity, ''), NULLIF(state.graph_id, ''), b.source_id || ':query-current-facts') AS graph_revision,
                       d.id AS descriptor_id,
                       TRIM(d.descriptor_path) AS descriptor_path,
                       TRIM(d.value_type) AS descriptor_value_type,
                       d.value_json AS descriptor_value_json
                FROM analysis_graph_boundaries b
                JOIN analysis_graph_nodes n
                  ON n.source_id = b.source_id
                 AND n.id = b.node_id
                 AND n.status IN ({current_status_sql})
                 AND {self.graph_store._inventory_membership_graph_node_clause("n")}
                 AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
                JOIN analysis_graph_boundary_descriptors d
                  ON d.boundary_id = b.id
                 AND d.status IN ({current_status_sql})
                LEFT JOIN analysis_graph_state state
                  ON state.source_id = b.source_id
                WHERE b.source_id = ?
                  AND b.role = ?
                  AND b.status IN ({current_status_sql})
                  AND b.rejection_reason IS NULL
                  AND {self.graph_store._inventory_membership_graph_edge_clause("b")}
                  AND (? OR COALESCE({self.graph_store._inventory_flow_domain_sql("n")}, '') != 'TEST')
                  AND ({path_type_sql})
            )
            SELECT *
            FROM candidate_rows
            WHERE 1 = 1
            {cursor_clause}
            ORDER BY source_id, graph_revision, boundary_sort_key, boundary_id, descriptor_path, descriptor_value_type, descriptor_id
            LIMIT ?
            """,
            [
                *current_status_params,
                include_tests,
                *current_status_params,
                source_id,
                BOUNDARY_ROLE_PROVIDED,
                *current_status_params,
                include_tests,
                *path_type_params,
                *cursor_params,
                limit,
            ],
        ).fetchall()
        return [self.graph_store._row_dict(row) for row in rows]

    def _candidate_descriptor_scan_key(self, row: dict[str, Any]) -> tuple[str, str, str, str, str, str, str]:
        return (
            str(row.get("source_id") or ""),
            str(row.get("graph_revision") or row.get("graph_id") or ""),
            str(row.get("boundary_sort_key") or row.get("boundary_stable_key") or row.get("boundary_id") or ""),
            str(row.get("boundary_id") or ""),
            str(row.get("descriptor_path") or ""),
            str(row.get("descriptor_value_type") or ""),
            str(row.get("descriptor_id") or ""),
        )

    def _query_boundary_rows_by_boundary_ids(
        self,
        conn: Any,
        source_id: str,
        boundary_ids: list[str],
        include_tests: bool,
    ) -> list[dict[str, Any]]:
        if not source_id or not boundary_ids:
            return []
        self._metrics["sqlStatements"] += 1
        placeholders = ",".join("?" for _ in boundary_ids)
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
             AND {self._current_evidence_clause("boundary_ev")}
            LEFT JOIN analysis_files boundary_af
              ON boundary_af.file_id = boundary_ev.analysis_file_id
            LEFT JOIN analysis_graph_boundary_descriptor_evidence de
              ON de.descriptor_id = d.id
            LEFT JOIN analysis_graph_evidence descriptor_ev
              ON descriptor_ev.id = de.evidence_id
             AND descriptor_ev.source_id = b.source_id
             AND {self._current_evidence_clause("descriptor_ev")}
            LEFT JOIN analysis_files descriptor_af
              ON descriptor_af.file_id = descriptor_ev.analysis_file_id
            WHERE b.source_id = ?
              AND b.id IN ({placeholders})
              AND b.role = ?
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
                *boundary_ids,
                BOUNDARY_ROLE_PROVIDED,
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

    def _current_evidence_clause(self, alias: str) -> str:
        return f"""
        EXISTS (
            SELECT 1
            FROM analysis_files af_current
            WHERE af_current.source_id = {alias}.source_id
              AND af_current.relative_path = {alias}.relative_path
              AND af_current.content_hash = {alias}.content_hash
              AND af_current.status IN ('ANALYZED', 'ANALYZED_WITH_DIAGNOSTICS', 'PARTIAL')
        )
        AND EXISTS (
            SELECT 1
            FROM files f_current
            WHERE f_current.source_id = {alias}.source_id
              AND f_current.relative_path = {alias}.relative_path
              AND f_current.content_hash = {alias}.content_hash
        )
        """

    def _query_available_operation_fact_rows(
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
    ) -> list[dict[str, Any]]:
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
    ) -> list[dict[str, Any]]:
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

    def _json_object(self, value: object) -> dict[str, Any]:
        if isinstance(value, dict):
            return value
        try:
            parsed = json.loads(str(value or "{}"))
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}

    def _query_edge_evidence(self, conn: Any, source_id: str, edge_ids: Sequence[str]) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
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
    ) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
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
