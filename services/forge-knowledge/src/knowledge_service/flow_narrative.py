from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, replace
from enum import Enum
from typing import Iterable, Mapping, Protocol, Sequence

from knowledge_service.entrypoint_kinds import EntrypointExecutionKind
from knowledge_service.flow_family import FlowFamily
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode, FlowNodeKey
from knowledge_service.graph_relation_semantics import GraphRelationSemantics, graph_relation_semantics
from knowledge_service.knowledge_query_schema import KnowledgeQueryDiagnostic
from knowledge_service.operation_facts import (
    AvailableOperationFact,
    clean_identity,
    normalize_http_method,
    normalize_route,
    normalize_transport_kind,
    split_operation_interface_identity,
)


class FlowNarrativePartKind(str, Enum):
    VERIFIED_FRAGMENT = "VERIFIED_FRAGMENT"
    UNVERIFIED_GAP = "UNVERIFIED_GAP"
    AMBIGUOUS_GAP = "AMBIGUOUS_GAP"
    TERMINAL_BOUNDARY = "TERMINAL_BOUNDARY"


class FlowGapVerificationStatus(str, Enum):
    UNVERIFIED = "UNVERIFIED"
    AMBIGUOUS = "AMBIGUOUS"
    MISSING_UPSTREAM = "MISSING_UPSTREAM"
    MISSING_DOWNSTREAM = "MISSING_DOWNSTREAM"


class FlowCorrelationStatus(str, Enum):
    VERIFIED = "VERIFIED"
    EXACT_UNVERIFIED = "EXACT_UNVERIFIED"
    AMBIGUOUS = "AMBIGUOUS"
    NO_MATCH = "NO_MATCH"


@dataclass(frozen=True)
class AvailableFlowFragment:
    key: str
    root: FlowGraphNode
    root_role: str
    source_id: str
    family: FlowFamily
    matched_anchor_count: int = 0
    operation_facts: tuple[AvailableOperationFact, ...] = ()


@dataclass(frozen=True)
class FlowNarrativeGap:
    kind: str
    verification_status: FlowGapVerificationStatus
    from_source: str | None
    from_symbol: str | None
    to_source: str | None
    to_symbol: str | None
    transport_kind: str | None = None
    method: str | None = None
    route: str | None = None
    operation_identity: str | None = None
    reason: str | None = None


@dataclass(frozen=True)
class FlowNarrativePart:
    kind: FlowNarrativePartKind
    fragment: AvailableFlowFragment | None = None
    gap: FlowNarrativeGap | None = None


@dataclass(frozen=True)
class FlowNarrativePlan:
    key: str
    parts: tuple[FlowNarrativePart, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    complete: bool = True
    relevance_score: float = 0.0

    @property
    def fragments(self) -> tuple[AvailableFlowFragment, ...]:
        return tuple(part.fragment for part in self.parts if part.fragment is not None)


@dataclass(frozen=True)
class FlowCorrelationResult:
    status: FlowCorrelationStatus
    source_fragment_key: str
    operation_key: tuple[str, str, str, str, str, str] | None = None
    target_fragment_keys: tuple[str, ...] = ()
    gap: FlowNarrativeGap | None = None
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()


class FlowCorrelationAdapter(Protocol):
    def correlate(self, fragments: Sequence[AvailableFlowFragment]) -> tuple[FlowCorrelationResult, ...]:
        ...


class HttpFlowCorrelationAdapter:
    def correlate(self, fragments: Sequence[AvailableFlowFragment]) -> tuple[FlowCorrelationResult, ...]:
        fragment_by_key = {fragment.key: fragment for fragment in fragments}
        inbound_by_key: dict[tuple[str, str], list[tuple[str, AvailableOperationFact]]] = {}
        outbound: list[tuple[str, AvailableOperationFact]] = []
        for fragment in fragments:
            for fact in fragment.operation_facts:
                if normalize_transport_kind(fact.transport_kind) != "HTTP":
                    continue
                method = normalize_http_method(fact.method)
                route = normalize_route(fact.normalized_route)
                if not method or not route:
                    continue
                if fact.direction_role == "INBOUND":
                    inbound_by_key.setdefault((method, route), []).append((fragment.key, fact))
                elif fact.direction_role == "OUTBOUND":
                    outbound.append((fragment.key, fact))
        results: list[FlowCorrelationResult] = []
        for source_fragment_key, source_fact in sorted(outbound, key=lambda item: self._fact_sort_key(item[1])):
            method = normalize_http_method(source_fact.method)
            route = normalize_route(source_fact.normalized_route)
            if not method or not route:
                continue
            operation_key = source_fact.operation_key(source_fragment_key)
            candidates = [
                (fragment_key, fact)
                for fragment_key, fact in inbound_by_key.get((method, route), [])
                if fragment_key != source_fragment_key
            ]
            candidates = self._filter_by_exact_identity(source_fact, candidates)
            if not candidates:
                results.append(
                    FlowCorrelationResult(
                        status=FlowCorrelationStatus.NO_MATCH,
                        source_fragment_key=source_fragment_key,
                        operation_key=operation_key,
                        gap=self._gap(
                            source_fact,
                            None,
                            FlowGapVerificationStatus.MISSING_DOWNSTREAM,
                            "No unique inbound HTTP operation with the same exact typed facts is available.",
                        ),
                    )
                )
                continue
            unique_target_keys = tuple(sorted({fragment_key for fragment_key, _fact in candidates}))
            if len(unique_target_keys) == 1:
                _target_key, target_fact = sorted(candidates, key=lambda item: self._fact_sort_key(item[1]))[0]
                results.append(
                    FlowCorrelationResult(
                        status=FlowCorrelationStatus.EXACT_UNVERIFIED,
                        source_fragment_key=source_fragment_key,
                        operation_key=operation_key,
                        target_fragment_keys=unique_target_keys,
                        gap=self._gap(
                            source_fact,
                            target_fact,
                            FlowGapVerificationStatus.UNVERIFIED,
                            "Exact HTTP operation facts identify one continuation, but no persisted execution edge verifies the transition.",
                        ),
                    )
                )
                continue
            results.append(
                FlowCorrelationResult(
                    status=FlowCorrelationStatus.AMBIGUOUS,
                    source_fragment_key=source_fragment_key,
                    operation_key=operation_key,
                    target_fragment_keys=unique_target_keys,
                    gap=self._gap(
                        source_fact,
                        None,
                        FlowGapVerificationStatus.AMBIGUOUS,
                        "Several inbound HTTP fragments match the available exact operation facts; no target was selected.",
                    ),
                    diagnostics=(
                        KnowledgeQueryDiagnostic(
                            code="FLOW_CORRELATION_AMBIGUOUS",
                            message="Several fragments satisfy an exact HTTP correlation; the planner did not select one.",
                            severity="INFO",
                            sourceId=fragment_by_key.get(source_fragment_key).source_id if fragment_by_key.get(source_fragment_key) else None,
                            metadata={
                                "transportKind": "HTTP",
                                "method": method,
                                "route": route,
                                "candidateCount": len(unique_target_keys),
                            },
                        ),
                    ),
                )
            )
        return tuple(results)

    def _filter_by_exact_identity(
        self,
        source: AvailableOperationFact,
        candidates: Sequence[tuple[str, AvailableOperationFact]],
    ) -> list[tuple[str, AvailableOperationFact]]:
        result = list(candidates)
        for attr in (
            "operation_identity",
            "interface_identity",
            "request_contract_identity",
            "response_contract_identity",
            "target_service_identity",
        ):
            source_value = getattr(source, attr)
            if not source_value:
                continue
            result = [
                item
                for item in result
                if not getattr(item[1], attr) or getattr(item[1], attr) == source_value
            ]
            matching = [item for item in result if getattr(item[1], attr) == source_value]
            if matching:
                result = matching
        return result

    def _gap(
        self,
        source: AvailableOperationFact,
        target: AvailableOperationFact | None,
        status: FlowGapVerificationStatus,
        reason: str,
    ) -> FlowNarrativeGap:
        method = normalize_http_method(source.method)
        route = normalize_route(source.normalized_route)
        return FlowNarrativeGap(
            kind=FlowNarrativePartKind.UNVERIFIED_GAP.value if status is FlowGapVerificationStatus.UNVERIFIED else FlowNarrativePartKind.AMBIGUOUS_GAP.value,
            verification_status=status,
            from_source=source.owner_source_id,
            from_symbol=self._symbol(source),
            to_source=target.owner_source_id if target is not None else None,
            to_symbol=self._symbol(target) if target is not None else None,
            transport_kind="HTTP",
            method=method,
            route=route,
            operation_identity=source.operation_identity,
            reason=reason,
        )

    def _fact_sort_key(self, fact: AvailableOperationFact) -> tuple[str, str, str, str, str]:
        return (
            fact.source_id,
            fact.direction_role or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            self._symbol(fact),
        )

    def _symbol(self, fact: AvailableOperationFact) -> str:
        qualified = str(fact.owner_qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if len(parts) >= 2:
                return ".".join(parts[-2:])
            return parts[-1] if parts else qualified
        return str(fact.owner_node_id)


class OperationFactProjector:
    def __init__(self, semantics: GraphRelationSemantics | None = None) -> None:
        self.semantics = semantics or graph_relation_semantics()

    def project(
        self,
        family: FlowFamily,
        available_facts: Sequence[AvailableOperationFact],
    ) -> tuple[AvailableOperationFact, ...]:
        node_by_key = {self._node_key(node): node for node in family.nodes}
        node_keys = set(node_by_key)
        reached_non_root_targets = {
            self._to_key(edge)
            for edge in family.transitions
            if self.semantics.is_execution_continuation(edge)
            and self._to_key(edge) is not None
            and self._from_key(edge) in node_keys
            and self._to_key(edge) in node_keys
            and self._from_key(edge) != self._to_key(edge)
            and node_by_key.get(self._from_key(edge)) is not None
            and str(node_by_key[self._from_key(edge)].node_kind or "").upper() == "CALLABLE"
        }
        facts: dict[tuple[str, str, str, str, str, str, str, str, str], AvailableOperationFact] = {}
        for fact in available_facts:
            if fact.owner_key not in node_keys:
                continue
            projected = self._classify_node_fact(fact, node_by_key.get(fact.owner_key), reached_non_root_targets)
            if projected is None:
                continue
            facts.setdefault(self._fact_key(projected), projected)
        for edge in (*family.transitions, *family.boundary_transitions):
            projected = self._edge_fact(edge, node_by_key)
            if projected is None:
                continue
            facts.setdefault(self._fact_key(projected), projected)
        return tuple(facts[key] for key in sorted(facts))

    def _classify_node_fact(
        self,
        fact: AvailableOperationFact,
        node: FlowGraphNode | None,
        reached_non_root_targets: set[FlowNodeKey | None],
    ) -> AvailableOperationFact | None:
        transport = normalize_transport_kind(fact.transport_kind)
        role = str(fact.execution_role or "").upper()
        if transport == "HTTP" and (not normalize_http_method(fact.method) or not normalize_route(fact.normalized_route)):
            return None
        direction = "SUPPORTING"
        if transport == "HTTP" and role == EntrypointExecutionKind.EXECUTABLE.value and node is not None and node.entrypoint:
            direction = "INBOUND"
        elif transport == "HTTP" and role == EntrypointExecutionKind.CLIENT_OPERATION.value:
            direction = "OUTBOUND"
        elif transport == "HTTP" and role != EntrypointExecutionKind.EXECUTABLE.value and fact.owner_key in reached_non_root_targets:
            direction = "OUTBOUND"
        return fact.with_direction(direction)

    def _edge_fact(
        self,
        edge: FlowGraphEdge,
        node_by_key: Mapping[FlowNodeKey, FlowGraphNode],
    ) -> AvailableOperationFact | None:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        transport = normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind"))
        method = normalize_http_method(metadata.get("httpMethod") or metadata.get("method"))
        route = normalize_route(metadata.get("routeTemplate") or metadata.get("route"))
        if transport != "HTTP" or not method or not route:
            return None
        operation_identity = clean_identity(metadata.get("operationIdentity"))
        interface_identity = clean_identity(metadata.get("interfaceMethod") or metadata.get("targetInterfaceMethod"))
        if not operation_identity and not interface_identity:
            operation_identity, interface_identity = split_operation_interface_identity(metadata.get("targetEntrypoint"))
        owner_node = node_by_key.get(self._from_key(edge))
        return AvailableOperationFact(
            owner_source_id=edge.source_id,
            owner_graph_id=edge.graph_id,
            owner_graph_revision=edge.graph_revision,
            owner_node_id=edge.from_node_id,
            source_id=edge.source_id,
            execution_role="EDGE_BOUNDARY",
            transport_kind=transport,
            direction_role="OUTBOUND",
            method=method,
            normalized_route=route,
            operation_identity=operation_identity,
            interface_identity=interface_identity,
            request_contract_identity=clean_identity(metadata.get("requestContractIdentity")),
            response_contract_identity=clean_identity(metadata.get("responseContractIdentity")),
            target_service_identity=clean_identity(metadata.get("targetServiceIdentity")),
            owner_qualified_name=owner_node.qualified_name if owner_node is not None else edge.from_node_id,
            owner_edge_id=edge.edge_id,
            source_channel="EDGE_METADATA",
        )

    def _fact_key(self, fact: AvailableOperationFact) -> tuple[str, str, str, str, str, str, str, str, str]:
        return (
            fact.owner_source_id,
            fact.owner_graph_revision or fact.owner_graph_id,
            fact.owner_node_id,
            fact.owner_edge_id or "",
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            fact.operation_identity or "",
            fact.interface_identity or "",
        )

    def _node_key(self, node: FlowGraphNode) -> FlowNodeKey:
        return (node.source_id, node.graph_revision or node.graph_id, node.node_id)

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


class FlowNarrativePlanner:
    def __init__(
        self,
        adapters: Sequence[FlowCorrelationAdapter] | None = None,
        operation_projector: OperationFactProjector | None = None,
    ) -> None:
        self.adapters = tuple(adapters or (HttpFlowCorrelationAdapter(),))
        self.operation_projector = operation_projector or OperationFactProjector()

    def fragments(
        self,
        families: Sequence[FlowFamily],
        *,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> tuple[AvailableFlowFragment, ...]:
        fragments = tuple(
            AvailableFlowFragment(
                key=self._fragment_key(family),
                root=family.entrypoint,
                root_role=str(family.entrypoint.execution_role or ""),
                source_id=family.entrypoint.source_id,
                family=family,
                matched_anchor_count=len(family.anchors or ()),
                operation_facts=self.operation_projector.project(family, operation_facts),
            )
            for family in families
        )
        return self._attach_catalog_client_operations(fragments, operation_facts)

    def _attach_catalog_client_operations(
        self,
        fragments: Sequence[AvailableFlowFragment],
        operation_facts: Sequence[AvailableOperationFact],
    ) -> tuple[AvailableFlowFragment, ...]:
        if not fragments or not operation_facts:
            return tuple(fragments)
        assigned_owners = {
            fact.structural_owner
            for fragment in fragments
            for fact in fragment.operation_facts
        }
        updated = {fragment.key: fragment for fragment in fragments}
        for fact in sorted(operation_facts, key=self._catalog_fact_sort_key):
            if fact.structural_owner in assigned_owners:
                continue
            if str(fact.source_channel or "") != "CATALOG_CONTRACT":
                continue
            if str(fact.execution_role or "").upper() != EntrypointExecutionKind.CLIENT_OPERATION.value:
                continue
            target_identity = clean_identity(fact.target_service_identity)
            method = normalize_http_method(fact.method)
            route = normalize_route(fact.normalized_route)
            if not target_identity or normalize_transport_kind(fact.transport_kind) != "HTTP" or not method or not route:
                continue
            candidates = [
                fragment
                for fragment in updated.values()
                if fragment.source_id != target_identity
                and self._fragment_has_inbound_http(fragment, method, route)
            ]
            if len(candidates) != 1:
                continue
            fragment = candidates[0]
            projected = fact.with_direction("OUTBOUND")
            updated[fragment.key] = replace(
                fragment,
                operation_facts=tuple(
                    sorted(
                        (*fragment.operation_facts, projected),
                        key=self._fragment_operation_fact_sort_key,
                    )
                ),
            )
            assigned_owners.add(fact.structural_owner)
        return tuple(updated[fragment.key] for fragment in fragments)

    def _fragment_has_inbound_http(self, fragment: AvailableFlowFragment, method: str, route: str) -> bool:
        return any(
            fact.direction_role == "INBOUND"
            and normalize_transport_kind(fact.transport_kind) == "HTTP"
            and normalize_http_method(fact.method) == method
            and normalize_route(fact.normalized_route) == route
            for fact in fragment.operation_facts
        )

    def _catalog_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[str, str, str, str, str]:
        return (
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            fact.target_service_identity or "",
            fact.structural_owner,
        )

    def _fragment_operation_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[str, str, str, str, str]:
        direction_order = {"INBOUND": "0", "OUTBOUND": "1", "SUPPORTING": "2"}
        return (
            direction_order.get(str(fact.direction_role or ""), "9"),
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            fact.structural_owner,
        )

    def assemble(
        self,
        families: Sequence[FlowFamily],
        *,
        max_plans: int,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> tuple[tuple[FlowNarrativePlan, ...], tuple[KnowledgeQueryDiagnostic, ...]]:
        fragments = self.fragments(families, operation_facts=operation_facts)
        if not fragments:
            return (), ()
        correlation_results = tuple(result for adapter in self.adapters for result in adapter.correlate(fragments))
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        diagnostics.extend(item for result in correlation_results for item in result.diagnostics)
        fragment_by_key = {fragment.key: fragment for fragment in fragments}
        used: set[str] = set()
        plans: list[FlowNarrativePlan] = []
        exact_by_source: dict[str, list[FlowCorrelationResult]] = defaultdict(list)
        for result in sorted(
            (
                result
                for result in correlation_results
                if result.status is FlowCorrelationStatus.EXACT_UNVERIFIED and len(result.target_fragment_keys) == 1
            ),
            key=self._correlation_sort_key,
        ):
            exact_by_source[result.source_fragment_key].append(result)
        ambiguous_by_source: dict[str, list[FlowCorrelationResult]] = defaultdict(list)
        for result in sorted(
            (
                result
                for result in correlation_results
                if result.status is FlowCorrelationStatus.AMBIGUOUS and result.gap is not None
            ),
            key=self._correlation_sort_key,
        ):
            ambiguous_by_source[result.source_fragment_key].append(result)
        exact_target_keys = {
            target_key
            for results in exact_by_source.values()
            for result in results
            for target_key in result.target_fragment_keys
        }
        for fragment in sorted(fragments, key=lambda item: self._root_fragment_sort_key(item, exact_target_keys)):
            if fragment.key in used:
                continue
            parts = [FlowNarrativePart(FlowNarrativePartKind.VERIFIED_FRAGMENT, fragment=fragment)]
            used.add(fragment.key)
            self._append_continuations(fragment, fragment_by_key, exact_by_source, ambiguous_by_source, used, parts, set())
            plans.append(self._plan(parts))
        ranked = tuple(sorted(plans, key=self._plan_sort_key))
        max_count = max(1, int(max_plans or 1))
        omitted = max(0, len(ranked) - max_count)
        selected = ranked[:max_count]
        if omitted:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="NARRATIVE_PLAN_MAX_FLOWS_REACHED",
                    message="Independent narrative plans were omitted by maxFlows.",
                    severity="INFO",
                    metadata={
                        "returnedPlanCount": len(selected),
                        "discoveredPlanCount": len(ranked),
                        "omittedPlanCount": omitted,
                        "maxFlows": max_count,
                    },
                )
            )
        return selected, tuple(diagnostics)

    def _plan(self, parts: Sequence[FlowNarrativePart]) -> FlowNarrativePlan:
        fragments = tuple(part.fragment for part in parts if part.fragment is not None)
        key = "|".join(fragment.key for fragment in fragments)
        diagnostics = tuple(item for fragment in fragments for item in fragment.family.diagnostics)
        complete = all(fragment.family.complete for fragment in fragments) and not any(part.gap for part in parts)
        relevance_score = max((fragment.family.relevance_score for fragment in fragments), default=0.0)
        return FlowNarrativePlan(
            key=key,
            parts=tuple(parts),
            diagnostics=diagnostics,
            complete=complete,
            relevance_score=relevance_score,
        )

    def _fragment_key(self, family: FlowFamily) -> str:
        return ":".join(
            (
                str(family.key.source_id),
                str(family.key.graph_revision),
                str(family.key.entrypoint_node_id),
            )
        )

    def _fragment_sort_key(self, fragment: AvailableFlowFragment) -> tuple[float, str, str, str]:
        key = fragment.family.key
        return (
            -float(fragment.family.relevance_score or 0.0),
            key.source_id,
            key.graph_revision,
            key.entrypoint_node_id,
        )

    def _root_fragment_sort_key(self, fragment: AvailableFlowFragment, exact_target_keys: set[str]) -> tuple[int, float, str, str, str]:
        return (1 if fragment.key in exact_target_keys else 0, *self._fragment_sort_key(fragment))

    def _plan_sort_key(self, plan: FlowNarrativePlan) -> tuple[float, int, int, str]:
        fragment_count = sum(1 for part in plan.parts if part.fragment is not None)
        gap_count = sum(1 for part in plan.parts if part.gap is not None)
        return (-float(plan.relevance_score or 0.0), -fragment_count, -gap_count, plan.key)

    def _append_continuations(
        self,
        fragment: AvailableFlowFragment,
        fragment_by_key: Mapping[str, AvailableFlowFragment],
        exact_by_source: Mapping[str, Sequence[FlowCorrelationResult]],
        ambiguous_by_source: Mapping[str, Sequence[FlowCorrelationResult]],
        used: set[str],
        parts: list[FlowNarrativePart],
        stack: set[str],
    ) -> None:
        if fragment.key in stack:
            return
        next_stack = {*stack, fragment.key}
        for result in exact_by_source.get(fragment.key, ()):
            target_key = result.target_fragment_keys[0]
            if target_key not in fragment_by_key or result.gap is None:
                continue
            parts.append(FlowNarrativePart(FlowNarrativePartKind.UNVERIFIED_GAP, gap=result.gap))
            if target_key in used:
                continue
            target = fragment_by_key[target_key]
            parts.append(FlowNarrativePart(FlowNarrativePartKind.VERIFIED_FRAGMENT, fragment=target))
            used.add(target_key)
            self._append_continuations(target, fragment_by_key, exact_by_source, ambiguous_by_source, used, parts, next_stack)
        for result in ambiguous_by_source.get(fragment.key, ()):
            if result.gap is not None:
                parts.append(FlowNarrativePart(FlowNarrativePartKind.AMBIGUOUS_GAP, gap=result.gap))

    def _correlation_sort_key(self, result: FlowCorrelationResult) -> tuple[str, str, str, str, str, str, str]:
        key = result.operation_key or ("", "", "", "", "", "")
        return (result.source_fragment_key, *key)


def replace_plan_fragments(
    plan: FlowNarrativePlan,
    replacements: Mapping[str, FlowFamily],
) -> FlowNarrativePlan:
    parts: list[FlowNarrativePart] = []
    for part in plan.parts:
        if part.fragment is None:
            parts.append(part)
            continue
        replacement = replacements.get(part.fragment.key)
        if replacement is None:
            parts.append(part)
            continue
        parts.append(replace(part, fragment=replace(part.fragment, family=replacement, root=replacement.entrypoint)))
    return replace(plan, parts=tuple(parts))
