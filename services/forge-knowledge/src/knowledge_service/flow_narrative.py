from __future__ import annotations

import re
from dataclasses import dataclass, replace
from enum import Enum
from typing import Iterable, Mapping, Protocol, Sequence

from knowledge_service.entrypoint_kinds import EntrypointExecutionKind, EntrypointKind
from knowledge_service.flow_family import FlowFamily
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode, FlowNodeKey
from knowledge_service.knowledge_query_schema import KnowledgeQueryDiagnostic


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
class HttpOperationFact:
    fragment_key: str
    role: str
    source_id: str
    symbol: str
    method: str
    route: str
    operation_identity: str | None = None
    request_contract_identity: str | None = None
    response_contract_identity: str | None = None
    target_service_identity: str | None = None


@dataclass(frozen=True)
class FlowCorrelationResult:
    status: FlowCorrelationStatus
    source_fragment_key: str
    target_fragment_keys: tuple[str, ...] = ()
    gap: FlowNarrativeGap | None = None
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()


class FlowCorrelationAdapter(Protocol):
    def correlate(self, fragments: Sequence[AvailableFlowFragment]) -> tuple[FlowCorrelationResult, ...]:
        ...


class HttpFlowCorrelationAdapter:
    def correlate(self, fragments: Sequence[AvailableFlowFragment]) -> tuple[FlowCorrelationResult, ...]:
        fragment_by_key = {fragment.key: fragment for fragment in fragments}
        inbound_by_key: dict[tuple[str, str], list[HttpOperationFact]] = {}
        outbound: list[HttpOperationFact] = []
        for fragment in fragments:
            for fact in self._facts(fragment):
                if fact.role == "INBOUND":
                    inbound_by_key.setdefault((fact.method, fact.route), []).append(fact)
                elif fact.role == "OUTBOUND":
                    outbound.append(fact)
        results: list[FlowCorrelationResult] = []
        for source_fact in sorted(outbound, key=self._fact_sort_key):
            candidates = [
                fact
                for fact in inbound_by_key.get((source_fact.method, source_fact.route), [])
                if fact.fragment_key != source_fact.fragment_key
            ]
            candidates = self._filter_by_exact_identity(source_fact, candidates)
            if not candidates:
                results.append(
                    FlowCorrelationResult(
                        status=FlowCorrelationStatus.NO_MATCH,
                        source_fragment_key=source_fact.fragment_key,
                        gap=self._gap(
                            source_fact,
                            None,
                            FlowGapVerificationStatus.MISSING_DOWNSTREAM,
                            "No unique inbound HTTP operation with the same exact typed facts is available.",
                        ),
                    )
                )
                continue
            unique_target_keys = tuple(sorted({fact.fragment_key for fact in candidates}))
            if len(unique_target_keys) == 1:
                target_fact = sorted(candidates, key=self._fact_sort_key)[0]
                results.append(
                    FlowCorrelationResult(
                        status=FlowCorrelationStatus.EXACT_UNVERIFIED,
                        source_fragment_key=source_fact.fragment_key,
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
                    source_fragment_key=source_fact.fragment_key,
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
                            sourceId=fragment_by_key.get(source_fact.fragment_key).source_id if fragment_by_key.get(source_fact.fragment_key) else None,
                            metadata={
                                "transportKind": "HTTP",
                                "method": source_fact.method,
                                "route": source_fact.route,
                                "candidateCount": len(unique_target_keys),
                            },
                        ),
                    ),
                )
            )
        return tuple(results)

    def _filter_by_exact_identity(
        self,
        source: HttpOperationFact,
        candidates: Sequence[HttpOperationFact],
    ) -> list[HttpOperationFact]:
        result = list(candidates)
        for attr in (
            "operation_identity",
            "request_contract_identity",
            "response_contract_identity",
            "target_service_identity",
        ):
            source_value = getattr(source, attr)
            if not source_value:
                continue
            matching = [candidate for candidate in result if getattr(candidate, attr) == source_value]
            if matching:
                result = matching
        return result

    def _facts(self, fragment: AvailableFlowFragment) -> tuple[HttpOperationFact, ...]:
        facts: dict[tuple[str, str, str, str, str], HttpOperationFact] = {}
        for node in fragment.family.nodes:
            fact = self._node_fact(fragment, node)
            if fact is not None:
                facts.setdefault((fact.fragment_key, fact.role, fact.method, fact.route, fact.symbol), fact)
        for edge in (*fragment.family.transitions, *fragment.family.boundary_transitions):
            fact = self._edge_fact(fragment, edge)
            if fact is not None:
                facts.setdefault((fact.fragment_key, fact.role, fact.method, fact.route, fact.symbol), fact)
        return tuple(facts[key] for key in sorted(facts))

    def _node_fact(self, fragment: AvailableFlowFragment, node: FlowGraphNode) -> HttpOperationFact | None:
        if str(node.entrypoint_kind or "").upper() != EntrypointKind.HTTP.value:
            return None
        method = self._method(node.entrypoint_http_method)
        route = self._route(node.entrypoint_route)
        if not method or not route:
            return None
        role = "OUTBOUND" if str(node.execution_role or "").upper() == EntrypointExecutionKind.CLIENT_OPERATION.value else "INBOUND"
        if role == "INBOUND" and str(node.execution_role or "").upper() in {
            EntrypointExecutionKind.CLIENT_OPERATION.value,
            EntrypointExecutionKind.CONTRACT_DECLARATION.value,
            EntrypointExecutionKind.SUPPORTING_DECLARATION.value,
        }:
            return None
        return HttpOperationFact(
            fragment_key=fragment.key,
            role=role,
            source_id=node.source_id,
            symbol=self._symbol(node),
            method=method,
            route=route,
            operation_identity=self._identity(node.entrypoint_interface_method),
        )

    def _edge_fact(self, fragment: AvailableFlowFragment, edge: FlowGraphEdge) -> HttpOperationFact | None:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        kind = self._identity(metadata.get("connectorKind") or metadata.get("transportKind"))
        method = self._method(metadata.get("httpMethod") or metadata.get("method"))
        route = self._route(metadata.get("routeTemplate") or metadata.get("route"))
        if kind != "HTTP" or not method or not route:
            return None
        return HttpOperationFact(
            fragment_key=fragment.key,
            role="OUTBOUND",
            source_id=edge.source_id,
            symbol=str(metadata.get("fromSymbol") or edge.from_node_id),
            method=method,
            route=route,
            operation_identity=self._identity(metadata.get("operationIdentity") or metadata.get("interfaceMethod")),
            request_contract_identity=self._identity(metadata.get("requestContractIdentity")),
            response_contract_identity=self._identity(metadata.get("responseContractIdentity")),
            target_service_identity=self._identity(metadata.get("targetServiceIdentity")),
        )

    def _gap(
        self,
        source: HttpOperationFact,
        target: HttpOperationFact | None,
        status: FlowGapVerificationStatus,
        reason: str,
    ) -> FlowNarrativeGap:
        return FlowNarrativeGap(
            kind=FlowNarrativePartKind.UNVERIFIED_GAP.value if status is FlowGapVerificationStatus.UNVERIFIED else FlowNarrativePartKind.AMBIGUOUS_GAP.value,
            verification_status=status,
            from_source=source.source_id,
            from_symbol=source.symbol,
            to_source=target.source_id if target is not None else None,
            to_symbol=target.symbol if target is not None else None,
            transport_kind="HTTP",
            method=source.method,
            route=source.route,
            operation_identity=source.operation_identity,
            reason=reason,
        )

    def _fact_sort_key(self, fact: HttpOperationFact) -> tuple[str, str, str, str, str]:
        return (fact.fragment_key, fact.role, fact.method, fact.route, fact.symbol)

    def _symbol(self, node: FlowGraphNode) -> str:
        qualified = str(node.qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if node.node_kind == "CALLABLE" and len(parts) >= 2:
                return ".".join(parts[-2:])
            return parts[-1] if parts else qualified
        return str(node.label or node.node_id)

    def _method(self, value: object) -> str | None:
        text = str(value or "").strip().upper()
        return text or None

    def _route(self, value: object) -> str | None:
        text = str(value or "").strip()
        if not text:
            return None
        text = re.sub(r"/+", "/", text)
        if not text.startswith("/"):
            text = f"/{text}"
        if len(text) > 1:
            text = text.rstrip("/")
        return text

    def _identity(self, value: object) -> str | None:
        text = str(value or "").strip()
        return text or None


class FlowNarrativePlanner:
    def __init__(self, adapters: Sequence[FlowCorrelationAdapter] | None = None) -> None:
        self.adapters = tuple(adapters or (HttpFlowCorrelationAdapter(),))

    def fragments(self, families: Sequence[FlowFamily]) -> tuple[AvailableFlowFragment, ...]:
        return tuple(
            AvailableFlowFragment(
                key=self._fragment_key(family),
                root=family.entrypoint,
                root_role=str(family.entrypoint.execution_role or ""),
                source_id=family.entrypoint.source_id,
                family=family,
                matched_anchor_count=len(family.anchors or ()),
            )
            for family in families
        )

    def assemble(
        self,
        families: Sequence[FlowFamily],
        *,
        max_plans: int,
    ) -> tuple[tuple[FlowNarrativePlan, ...], tuple[KnowledgeQueryDiagnostic, ...]]:
        fragments = self.fragments(families)
        if not fragments:
            return (), ()
        correlation_results = tuple(result for adapter in self.adapters for result in adapter.correlate(fragments))
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        diagnostics.extend(item for result in correlation_results for item in result.diagnostics)
        fragment_by_key = {fragment.key: fragment for fragment in fragments}
        used: set[str] = set()
        plans: list[FlowNarrativePlan] = []
        exact_by_source = {
            result.source_fragment_key: result
            for result in correlation_results
            if result.status is FlowCorrelationStatus.EXACT_UNVERIFIED and len(result.target_fragment_keys) == 1
        }
        ambiguous_by_source = {
            result.source_fragment_key: result
            for result in correlation_results
            if result.status is FlowCorrelationStatus.AMBIGUOUS and result.gap is not None
        }
        for fragment in sorted(fragments, key=self._fragment_sort_key):
            if fragment.key in used:
                continue
            parts = [FlowNarrativePart(FlowNarrativePartKind.VERIFIED_FRAGMENT, fragment=fragment)]
            used.add(fragment.key)
            current = fragment
            while current.key in exact_by_source:
                result = exact_by_source[current.key]
                target_key = result.target_fragment_keys[0]
                if target_key in used or target_key not in fragment_by_key or result.gap is None:
                    break
                parts.append(FlowNarrativePart(FlowNarrativePartKind.UNVERIFIED_GAP, gap=result.gap))
                target = fragment_by_key[target_key]
                parts.append(FlowNarrativePart(FlowNarrativePartKind.VERIFIED_FRAGMENT, fragment=target))
                used.add(target_key)
                current = target
            if current.key in ambiguous_by_source:
                result = ambiguous_by_source[current.key]
                if result.gap is not None:
                    parts.append(FlowNarrativePart(FlowNarrativePartKind.AMBIGUOUS_GAP, gap=result.gap))
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

    def _plan_sort_key(self, plan: FlowNarrativePlan) -> tuple[float, str]:
        return (-float(plan.relevance_score or 0.0), plan.key)


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
