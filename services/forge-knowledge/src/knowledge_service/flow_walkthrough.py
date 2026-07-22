from __future__ import annotations

import re
import time
from collections import defaultdict
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Mapping, Sequence

from knowledge_service.flow_family import FlowFamily
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowNodeKey
from knowledge_service.flow_narrative import FlowGapVerificationStatus, FlowNarrativePartKind, FlowNarrativePlan
from knowledge_service.graph_relation_semantics import GraphRelationSemantics, graph_relation_semantics
from knowledge_service.knowledge_query_schema import (
    KnowledgeFlowAnswer,
    KnowledgeHumanQueryResponse,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
)
from knowledge_service.operation_facts import AvailableOperationFact, normalize_http_method, normalize_route, normalize_transport_kind
from knowledge_service.query_interpretation import QueryRetrievalPlan


class FlowWalkthroughStepKind(str, Enum):
    ENTRYPOINT = "ENTRYPOINT"
    EXECUTION = "EXECUTION"
    OPERATION = "OPERATION"
    TRANSITION = "TRANSITION"
    BOUNDARY = "BOUNDARY"
    UNVERIFIED_GAP = "UNVERIFIED_GAP"
    AMBIGUOUS_GAP = "AMBIGUOUS_GAP"
    BRANCH = "BRANCH"
    BRANCH_ITEM = "BRANCH_ITEM"
    JOIN = "JOIN"
    CYCLE = "CYCLE"
    SHARED_CONTINUATION = "SHARED_CONTINUATION"
    AVAILABLE_FLOW_END = "AVAILABLE_FLOW_END"
    RESULT = "RESULT"


@dataclass(frozen=True)
class FlowWalkthroughStep:
    internal_ref: str
    order: int
    depth: int
    kind: FlowWalkthroughStepKind
    certainty: str = "verified"
    source: str | None = None
    symbol: str | None = None
    fromSource: str | None = None
    fromSymbol: str | None = None
    toSource: str | None = None
    toSymbol: str | None = None
    relationKind: str | None = None
    transportKind: str | None = None
    method: str | None = None
    route: str | None = None
    topic: str | None = None
    schedule: str | None = None
    boundaryKind: str | None = None
    target: str | None = None
    summary: str | None = None
    child_steps: tuple["FlowWalkthroughStep", ...] = ()
    terminal: bool = False


@dataclass(frozen=True)
class FlowWalkthroughPlan:
    source: str
    entrypoint: str
    steps: tuple[FlowWalkthroughStep, ...]
    used_language: str
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    planning_duration_ms: float = 0.0
    rendering_duration_ms: float = 0.0

    @property
    def step_count(self) -> int:
        return sum(1 for _ in self.walk())

    @property
    def branch_count(self) -> int:
        return sum(1 for step in self.walk() if step.kind is FlowWalkthroughStepKind.BRANCH)

    @property
    def gap_count(self) -> int:
        return sum(
            1
            for step in self.walk()
            if step.kind in {FlowWalkthroughStepKind.UNVERIFIED_GAP, FlowWalkthroughStepKind.AMBIGUOUS_GAP}
        )

    def walk(self):
        stack = list(reversed(self.steps))
        while stack:
            step = stack.pop()
            yield step
            stack.extend(reversed(step.child_steps))


@dataclass(frozen=True)
class FlowWalkthroughAnswer:
    source: str
    entrypoint: str
    text: str
    plan: FlowWalkthroughPlan


@dataclass(frozen=True)
class FlowWalkthroughAnswerResult:
    answer_language: str
    answers: tuple[FlowWalkthroughAnswer, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    metrics: Dict[str, Any]


@dataclass
class _PlannerState:
    rendered: set[FlowNodeKey] = field(default_factory=set)
    order: int = 0

    def next_order(self) -> int:
        self.order += 1
        return self.order


class FlowWalkthroughPlanner:
    def __init__(self, semantics: GraphRelationSemantics | None = None) -> None:
        self.semantics = semantics or graph_relation_semantics()

    def plan(self, narrative_plan: FlowNarrativePlan, *, response_language: str = "en") -> FlowWalkthroughPlan:
        started = time.perf_counter()
        diagnostics: list[KnowledgeQueryDiagnostic] = list(narrative_plan.diagnostics)
        state = _PlannerState()
        steps: list[FlowWalkthroughStep] = []
        source = ""
        entrypoint = ""
        for part_index, part in enumerate(narrative_plan.parts, start=1):
            if part.kind is FlowNarrativePartKind.VERIFIED_FRAGMENT and part.fragment is not None:
                fragment = part.fragment
                if not source:
                    source = fragment.source_id
                    entrypoint = self._symbol(fragment.root)
                steps.extend(self._fragment_steps(fragment.family, fragment.operation_facts, state, part_index=part_index))
                continue
            if part.gap is not None:
                steps.append(self._gap_step(part.gap, state, part_index=part_index))
        if steps and steps[-1].kind not in {
            FlowWalkthroughStepKind.AVAILABLE_FLOW_END,
            FlowWalkthroughStepKind.RESULT,
            FlowWalkthroughStepKind.CYCLE,
            FlowWalkthroughStepKind.SHARED_CONTINUATION,
        }:
            last = self._last_runtime_step(steps[-1])
            steps.append(
                FlowWalkthroughStep(
                    internal_ref=f"available-end:{state.next_order()}",
                    order=state.order,
                    depth=0,
                    kind=FlowWalkthroughStepKind.AVAILABLE_FLOW_END,
                    source=last.source,
                    symbol=last.symbol,
                    terminal=True,
                )
            )
        return FlowWalkthroughPlan(
            source=source,
            entrypoint=entrypoint,
            steps=tuple(steps),
            used_language=response_language,
            diagnostics=tuple(diagnostics),
            planning_duration_ms=round((time.perf_counter() - started) * 1000, 3),
        )

    def _fragment_steps(
        self,
        family: FlowFamily,
        operation_facts: Sequence[AvailableOperationFact],
        state: _PlannerState,
        *,
        part_index: int,
    ) -> list[FlowWalkthroughStep]:
        node_by_key = {self._node_key(node): node for node in family.nodes}
        evidence_by_edge = self._evidence_by_edge(family.evidence)
        outgoing: dict[FlowNodeKey, list[FlowGraphEdge]] = defaultdict(list)
        for edge in family.transitions:
            if self.semantics.is_execution_continuation(edge):
                outgoing[self._from_key(edge)].append(edge)
        for key, edges in list(outgoing.items()):
            outgoing[key] = sorted(edges, key=lambda edge: self._edge_sort_key(edge, evidence_by_edge))
        boundaries: dict[FlowNodeKey, list[FlowGraphEdge]] = defaultdict(list)
        for edge in sorted(family.boundary_transitions, key=lambda edge: self._edge_sort_key(edge, evidence_by_edge)):
            boundaries[self._from_key(edge)].append(edge)
        for key, edges in list(boundaries.items()):
            boundaries[key] = self._dedupe_boundary_edges(edges)
        operations_by_node = self._operation_facts_by_node(operation_facts)
        root_key = self._node_key(family.entrypoint)
        steps = self._node_steps(
            family.entrypoint,
            root_key,
            node_by_key,
            outgoing,
            boundaries,
            operations_by_node,
            state,
            part_index=part_index,
            depth=0,
            ancestry=(),
            incoming=None,
            root=True,
        )
        for fact in self._external_operation_facts(operation_facts, node_by_key):
            steps.append(self._operation_step(fact, state, part_index=part_index, depth=0))
        return steps

    def _node_steps(
        self,
        node: FlowGraphNode,
        node_key: FlowNodeKey,
        node_by_key: Mapping[FlowNodeKey, FlowGraphNode],
        outgoing: Mapping[FlowNodeKey, Sequence[FlowGraphEdge]],
        boundaries: Mapping[FlowNodeKey, Sequence[FlowGraphEdge]],
        operations_by_node: Mapping[FlowNodeKey, Sequence[AvailableOperationFact]],
        state: _PlannerState,
        *,
        part_index: int,
        depth: int,
        ancestry: Sequence[FlowNodeKey],
        incoming: FlowGraphEdge | None,
        root: bool,
    ) -> list[FlowWalkthroughStep]:
        steps: list[FlowWalkthroughStep] = []
        current_node = node
        current_key = node_key
        current_depth = depth
        current_ancestry = tuple(ancestry)
        current_incoming = incoming
        current_root = root

        while True:
            if current_key in current_ancestry:
                steps.append(
                    FlowWalkthroughStep(
                        internal_ref=f"cycle:{part_index}:{state.next_order()}",
                        order=state.order,
                        depth=current_depth,
                        kind=FlowWalkthroughStepKind.CYCLE,
                        source=current_node.source_id,
                        symbol=self._symbol(current_node),
                    )
                )
                return steps
            if current_key in state.rendered:
                steps.append(
                    FlowWalkthroughStep(
                        internal_ref=f"shared:{part_index}:{state.next_order()}",
                        order=state.order,
                        depth=current_depth,
                        kind=FlowWalkthroughStepKind.SHARED_CONTINUATION,
                        source=current_node.source_id,
                        symbol=self._symbol(current_node),
                    )
                )
                return steps

            state.rendered.add(current_key)
            node_operations = tuple(operations_by_node.get(current_key, ()))
            steps.append(
                self._node_step(
                    current_node,
                    state,
                    part_index=part_index,
                    depth=current_depth,
                    root=current_root,
                    incoming=current_incoming,
                    operations=node_operations,
                )
            )
            steps.extend(
                self._boundary_step(edge, current_node, state, part_index=part_index, depth=current_depth)
                for edge in boundaries.get(current_key, ())
            )

            child_entries: list[tuple[FlowGraphEdge, FlowGraphNode | None, FlowNodeKey | None]] = []
            for edge in outgoing.get(current_key, ()):
                target_key = self._to_key(edge)
                target = node_by_key.get(target_key) if target_key is not None else None
                child_entries.append((edge, target, target_key))

            if len(child_entries) > 1:
                branch_children: list[FlowWalkthroughStep] = []
                for edge, target, target_key in child_entries:
                    item_step = FlowWalkthroughStep(
                        internal_ref=f"branch-item:{part_index}:{state.next_order()}",
                        order=state.order,
                        depth=current_depth + 1,
                        kind=FlowWalkthroughStepKind.BRANCH_ITEM,
                        source=target.source_id if target is not None else edge.to_source_id,
                        symbol=self._symbol(target) if target is not None else self._edge_target(edge),
                        fromSource=current_node.source_id,
                        fromSymbol=self._symbol(current_node),
                        toSource=target.source_id if target is not None else edge.to_source_id,
                        toSymbol=self._symbol(target) if target is not None else self._edge_target(edge),
                        relationKind=edge.edge_type,
                    )
                    nested = []
                    if target is not None and target_key is not None:
                        nested = self._node_steps(
                            target,
                            target_key,
                            node_by_key,
                            outgoing,
                            boundaries,
                            operations_by_node,
                            state,
                            part_index=part_index,
                            depth=current_depth + 2,
                            ancestry=(*current_ancestry, current_key),
                            incoming=edge,
                            root=False,
                        )
                    branch_children.append(self._replace_children(item_step, nested))
                steps.append(
                    FlowWalkthroughStep(
                        internal_ref=f"branch:{part_index}:{state.next_order()}",
                        order=state.order,
                        depth=current_depth,
                        kind=FlowWalkthroughStepKind.BRANCH,
                        source=current_node.source_id,
                        symbol=self._symbol(current_node),
                        child_steps=tuple(branch_children),
                    )
                )
                return steps

            if len(child_entries) == 1:
                edge, target, target_key = child_entries[0]
                if target is None or target_key is None:
                    steps.append(
                        FlowWalkthroughStep(
                            internal_ref=f"boundary-missing:{part_index}:{state.next_order()}",
                            order=state.order,
                            depth=current_depth,
                            kind=FlowWalkthroughStepKind.BOUNDARY,
                            source=current_node.source_id,
                            symbol=self._symbol(current_node),
                            fromSource=current_node.source_id,
                            fromSymbol=self._symbol(current_node),
                            toSource=edge.to_source_id,
                            toSymbol=self._edge_target(edge),
                            relationKind=edge.edge_type,
                            boundaryKind=str(edge.resolution_status or "UNRESOLVED"),
                            target=self._edge_target(edge),
                        )
                    )
                    return steps
                current_ancestry = (*current_ancestry, current_key)
                current_node = target
                current_key = target_key
                current_incoming = edge
                current_root = False
                continue

            return steps
        return steps

    def _node_step(
        self,
        node: FlowGraphNode,
        state: _PlannerState,
        *,
        part_index: int,
        depth: int,
        root: bool,
        incoming: FlowGraphEdge | None,
        operations: Sequence[AvailableOperationFact],
    ) -> FlowWalkthroughStep:
        operation = self._primary_operation(operations)
        return FlowWalkthroughStep(
            internal_ref=f"node:{part_index}:{state.next_order()}",
            order=state.order,
            depth=depth,
            kind=FlowWalkthroughStepKind.ENTRYPOINT if root else FlowWalkthroughStepKind.EXECUTION,
            source=node.source_id,
            symbol=self._symbol(node),
            fromSource=incoming.source_id if incoming is not None else None,
            fromSymbol=incoming.from_node_id if incoming is not None else None,
            relationKind=incoming.edge_type if incoming is not None else None,
            transportKind=normalize_transport_kind(operation.transport_kind) if operation is not None else self._entrypoint_transport(node),
            method=normalize_http_method(operation.method) if operation is not None else self._clean(node.entrypoint_http_method),
            route=normalize_route(operation.normalized_route) if operation is not None else self._clean(node.entrypoint_route),
            topic=self._clean(operation.topic) if operation is not None else self._clean(node.entrypoint_topic),
            schedule=self._clean(operation.schedule) if operation is not None else self._clean(node.entrypoint_schedule),
            summary=self._clean(node.summary),
        )

    def _operation_step(
        self,
        fact: AvailableOperationFact,
        state: _PlannerState,
        *,
        part_index: int,
        depth: int,
    ) -> FlowWalkthroughStep:
        return FlowWalkthroughStep(
            internal_ref=f"operation:{part_index}:{state.next_order()}",
            order=state.order,
            depth=depth,
            kind=FlowWalkthroughStepKind.OPERATION,
            source=fact.owner_source_id,
            symbol=self._operation_symbol(fact),
            transportKind=normalize_transport_kind(fact.transport_kind),
            method=normalize_http_method(fact.method),
            route=normalize_route(fact.normalized_route),
            topic=self._clean(fact.topic),
            schedule=self._clean(fact.schedule),
            target=self._clean(fact.target_service_identity),
        )

    def _boundary_step(
        self,
        edge: FlowGraphEdge,
        owner: FlowGraphNode,
        state: _PlannerState,
        *,
        part_index: int,
        depth: int,
    ) -> FlowWalkthroughStep:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        return FlowWalkthroughStep(
            internal_ref=f"boundary:{part_index}:{state.next_order()}",
            order=state.order,
            depth=depth,
            kind=FlowWalkthroughStepKind.BOUNDARY,
            source=owner.source_id,
            symbol=self._symbol(owner),
            fromSource=owner.source_id,
            fromSymbol=self._symbol(owner),
            toSource=edge.to_source_id,
            toSymbol=self._edge_target(edge),
            relationKind=edge.edge_type,
            transportKind=normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind")),
            method=normalize_http_method(metadata.get("httpMethod") or metadata.get("method")),
            route=normalize_route(metadata.get("routeTemplate") or metadata.get("route")),
            boundaryKind=str(edge.resolution_status or ""),
            target=self._edge_target(edge),
        )

    def _dedupe_boundary_edges(self, edges: Sequence[FlowGraphEdge]) -> list[FlowGraphEdge]:
        deduped: list[FlowGraphEdge] = []
        seen: set[tuple[str, str, str]] = set()
        for edge in edges:
            metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
            key = (
                normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind")) or "",
                normalize_http_method(metadata.get("httpMethod") or metadata.get("method")) or "",
                normalize_route(metadata.get("routeTemplate") or metadata.get("route")) or "",
            )
            if key in seen:
                continue
            seen.add(key)
            deduped.append(edge)
        return deduped

    def _gap_step(self, gap, state: _PlannerState, *, part_index: int) -> FlowWalkthroughStep:
        status = gap.verification_status
        kind = (
            FlowWalkthroughStepKind.AMBIGUOUS_GAP
            if status is FlowGapVerificationStatus.AMBIGUOUS
            else FlowWalkthroughStepKind.UNVERIFIED_GAP
        )
        return FlowWalkthroughStep(
            internal_ref=f"gap:{part_index}:{state.next_order()}",
            order=state.order,
            depth=0,
            kind=kind,
            certainty="ambiguous" if kind is FlowWalkthroughStepKind.AMBIGUOUS_GAP else "unverified",
            fromSource=gap.from_source,
            fromSymbol=gap.from_symbol,
            toSource=gap.to_source,
            toSymbol=gap.to_symbol,
            transportKind=normalize_transport_kind(gap.transport_kind),
            method=normalize_http_method(gap.method),
            route=normalize_route(gap.route),
            target=gap.operation_identity,
        )

    def _last_runtime_step(self, step: FlowWalkthroughStep) -> FlowWalkthroughStep:
        current = step
        while current.child_steps:
            current = current.child_steps[-1]
        return current

    def _replace_children(self, step: FlowWalkthroughStep, children: Sequence[FlowWalkthroughStep]) -> FlowWalkthroughStep:
        return FlowWalkthroughStep(
            internal_ref=step.internal_ref,
            order=step.order,
            depth=step.depth,
            kind=step.kind,
            certainty=step.certainty,
            source=step.source,
            symbol=step.symbol,
            fromSource=step.fromSource,
            fromSymbol=step.fromSymbol,
            toSource=step.toSource,
            toSymbol=step.toSymbol,
            relationKind=step.relationKind,
            transportKind=step.transportKind,
            method=step.method,
            route=step.route,
            topic=step.topic,
            schedule=step.schedule,
            boundaryKind=step.boundaryKind,
            target=step.target,
            summary=step.summary,
            child_steps=tuple(children),
            terminal=step.terminal,
        )

    def _primary_operation(self, operation_facts: Sequence[AvailableOperationFact]) -> AvailableOperationFact | None:
        for fact in sorted(operation_facts, key=self._operation_fact_sort_key):
            if normalize_transport_kind(fact.transport_kind):
                return fact
        return None

    def _operation_facts_by_node(
        self,
        operation_facts: Sequence[AvailableOperationFact],
    ) -> Dict[FlowNodeKey, tuple[AvailableOperationFact, ...]]:
        grouped: Dict[FlowNodeKey, list[AvailableOperationFact]] = defaultdict(list)
        for fact in operation_facts:
            grouped[fact.owner_key].append(fact)
        return {
            key: tuple(sorted(values, key=self._operation_fact_sort_key))
            for key, values in grouped.items()
        }

    def _external_operation_facts(
        self,
        operation_facts: Sequence[AvailableOperationFact],
        node_by_key: Mapping[FlowNodeKey, FlowGraphNode],
    ) -> tuple[AvailableOperationFact, ...]:
        return tuple(
            sorted(
                (
                    fact
                    for fact in operation_facts
                    if fact.owner_key not in node_by_key
                    and str(fact.direction_role or "") == "OUTBOUND"
                ),
                key=self._operation_fact_sort_key,
            )
        )

    def _operation_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[int, str, str, str, str, str]:
        direction_rank = {"INBOUND": 0, "OUTBOUND": 1, "SUPPORTING": 2}.get(str(fact.direction_role or ""), 3)
        return (
            direction_rank,
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            fact.operation_identity or fact.interface_identity or "",
            fact.structural_owner,
        )

    def _evidence_by_edge(self, evidence: Sequence[FlowGraphEvidence]) -> Dict[tuple[str, str], list[FlowGraphEvidence]]:
        result: Dict[tuple[str, str], list[FlowGraphEvidence]] = defaultdict(list)
        for item in evidence:
            if item.edge_id:
                result[(item.source_id, item.edge_id)].append(item)
        return result

    def _edge_sort_key(
        self,
        edge: FlowGraphEdge,
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]],
    ) -> tuple[int, int, str, str, str]:
        line_starts = [
            item.line_start
            for item in evidence_by_edge.get((edge.source_id, edge.edge_id), ())
            if item.line_start is not None
        ]
        first_line = min(line_starts) if line_starts else 1_000_000_000
        return (first_line, 0 if line_starts else 1, edge.to_node_id or "", edge.edge_id, edge.resolution_status)

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

    def _entrypoint_transport(self, node: FlowGraphNode) -> str | None:
        return self._clean(node.entrypoint_kind)

    def _edge_target(self, edge: FlowGraphEdge) -> str | None:
        target = edge.unresolved_target if isinstance(edge.unresolved_target, dict) else {}
        for key in ("qualifiedName", "target", "displayName", "label", "symbol", "name", "targetTypeText", "receiverTypeHint"):
            value = self._clean(target.get(key) if isinstance(target.get(key), str) else None)
            if value:
                return self._compact_symbol(value)
        return self._clean(edge.to_node_id)

    def _operation_symbol(self, fact: AvailableOperationFact) -> str:
        qualified = self._clean(fact.owner_qualified_name)
        if qualified:
            return self._compact_symbol(qualified) or qualified
        identity = self._clean(fact.interface_identity or fact.operation_identity)
        if identity:
            return identity
        return " ".join(part for part in (normalize_http_method(fact.method), normalize_route(fact.normalized_route)) if part) or fact.owner_node_id

    def _symbol(self, node: FlowGraphNode | None) -> str:
        if node is None:
            return ""
        qualified = self._clean(node.qualified_name)
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if str(node.node_kind or "").upper() == "CALLABLE" and len(parts) >= 2:
                return ".".join(parts[-2:])
            if str(node.node_kind or "").upper() == "CALLABLE":
                return parts[-1] if parts else qualified
            return qualified
        return str(node.label or node.node_id)

    def _compact_symbol(self, value: str | None) -> str | None:
        normalized = self._clean(value)
        if not normalized:
            return None
        parts = [part for part in normalized.split(".") if part]
        if len(parts) >= 2 and self._looks_like_symbol(normalized):
            return ".".join(parts[-2:])
        return normalized

    def _looks_like_symbol(self, value: str) -> bool:
        return re.match(r"^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$", value) is not None

    def _clean(self, value: Any) -> str | None:
        normalized = str(value or "").strip()
        return normalized or None


class FlowMessageCatalog:
    def __init__(self, catalogs: Mapping[str, Mapping[str, str]] | None = None, *, default_language: str = "en") -> None:
        self.default_language = default_language
        self.catalogs = dict(catalogs or _DEFAULT_CATALOGS)

    def resolve_language(self, requested_language: str | None) -> tuple[str, KnowledgeQueryDiagnostic | None]:
        requested = str(requested_language or "").strip().lower() or self.default_language
        if requested in self.catalogs:
            return requested, None
        fallback = self.default_language if self.default_language in self.catalogs else sorted(self.catalogs)[0]
        return fallback, KnowledgeQueryDiagnostic(
            code="FLOW_WALKTHROUGH_LANGUAGE_FALLBACK",
            message="Requested response language catalog is unavailable; deterministic flow text used the default catalog.",
            severity="INFO",
            metadata={"requestedLanguage": requested, "usedLanguage": fallback},
        )

    def template(self, language: str, key: str) -> str:
        catalog = self.catalogs.get(language) or self.catalogs.get(self.default_language) or {}
        return catalog.get(key) or _DEFAULT_CATALOGS["en"].get(key) or "{symbol}"


class FlowWalkthroughRenderer:
    def __init__(self, message_catalog: FlowMessageCatalog | None = None) -> None:
        self.message_catalog = message_catalog or FlowMessageCatalog()

    def render(self, plan: FlowWalkthroughPlan) -> tuple[str, str, KnowledgeQueryDiagnostic | None, float]:
        started = time.perf_counter()
        language, diagnostic = self.message_catalog.resolve_language(plan.used_language)
        lines: list[str] = []
        for index, step in enumerate(plan.steps, start=1):
            self._render_step(lines, step, language, f"{index}.")
        return "\n".join(lines).strip(), language, diagnostic, round((time.perf_counter() - started) * 1000, 3)

    def _render_step(self, lines: list[str], step: FlowWalkthroughStep, language: str, number: str) -> None:
        text = self._step_text(step, language)
        indent = "   " * max(0, number.count(".") - 1)
        if text:
            lines.append(f"{indent}{number} {text}")
        for child_index, child in enumerate(step.child_steps, start=1):
            self._render_step(lines, child, language, f"{number}{child_index}.")

    def _step_text(self, step: FlowWalkthroughStep, language: str) -> str:
        key = self._message_key(step)
        template = self.message_catalog.template(language, key)
        values = {
            "symbol": self._with_source(step.symbol, step.source),
            "source": step.source or "",
            "from_symbol": self._with_source(step.fromSymbol, step.fromSource),
            "to_symbol": self._with_source(step.toSymbol, step.toSource),
            "target": step.target or step.toSymbol or "",
            "method": step.method or "",
            "route": step.route or "",
            "topic": step.topic or "",
            "schedule": step.schedule or "",
            "transport": step.transportKind or "",
            "relation": step.relationKind or "",
            "summary": self._summary_sentence(step.summary),
        }
        return self._clean_spacing(template.format(**values))

    def _message_key(self, step: FlowWalkthroughStep) -> str:
        if step.kind is FlowWalkthroughStepKind.ENTRYPOINT and step.transportKind == "HTTP" and (step.method or step.route):
            return "flow.entrypoint.http"
        if step.kind is FlowWalkthroughStepKind.ENTRYPOINT:
            return "flow.entrypoint.generic"
        if step.kind is FlowWalkthroughStepKind.EXECUTION:
            return "flow.execution.call"
        if step.kind is FlowWalkthroughStepKind.OPERATION and step.transportKind == "HTTP" and (step.method or step.route):
            return "flow.operation.http"
        if step.kind is FlowWalkthroughStepKind.OPERATION:
            return "flow.operation.generic"
        if step.kind is FlowWalkthroughStepKind.BOUNDARY:
            return "flow.boundary.unresolved"
        if step.kind is FlowWalkthroughStepKind.UNVERIFIED_GAP:
            return "flow.gap.unverified"
        if step.kind is FlowWalkthroughStepKind.AMBIGUOUS_GAP:
            return "flow.gap.ambiguous"
        if step.kind is FlowWalkthroughStepKind.BRANCH:
            return "flow.branch.start"
        if step.kind is FlowWalkthroughStepKind.BRANCH_ITEM:
            return "flow.branch.item"
        if step.kind is FlowWalkthroughStepKind.CYCLE:
            return "flow.cycle"
        if step.kind is FlowWalkthroughStepKind.SHARED_CONTINUATION:
            return "flow.shared"
        if step.kind is FlowWalkthroughStepKind.RESULT:
            return "flow.result"
        return "flow.availableEnd"

    def _with_source(self, symbol: str | None, source: str | None) -> str:
        symbol_text = str(symbol or "").strip()
        source_text = str(source or "").strip()
        if symbol_text and source_text:
            return f"{symbol_text} ({source_text})"
        return symbol_text or source_text

    def _summary_sentence(self, value: str | None) -> str:
        text = str(value or "").strip()
        if not text:
            return ""
        if text.endswith((".", "!", "?")):
            return f" {text}"
        return f" {text}."

    def _clean_spacing(self, value: str) -> str:
        text = re.sub(r"\s+", " ", str(value or "")).strip()
        text = text.replace("  .", ".").replace(" .", ".")
        return text


class DeterministicFlowWalkthroughAnswerService:
    def __init__(
        self,
        *,
        planner: FlowWalkthroughPlanner | None = None,
        renderer: FlowWalkthroughRenderer | None = None,
        default_language: str = "en",
    ) -> None:
        self.planner = planner or FlowWalkthroughPlanner()
        self.renderer = renderer or FlowWalkthroughRenderer(FlowMessageCatalog(default_language=default_language))
        self.pipeline_records: list[Dict[str, Any]] = []
        self.current_stage: str | None = None

    def answer(
        self,
        request: KnowledgeQueryRequest,
        execution: Any,
        *,
        plan: QueryRetrievalPlan,
    ) -> FlowWalkthroughAnswerResult:
        narrative_plans = tuple(getattr(execution, "narrative_plans", ()) or ())
        if not narrative_plans:
            return FlowWalkthroughAnswerResult(
                answer_language=plan.response_language,
                answers=(),
                diagnostics=(),
                metrics=self._metrics((), (), 0.0, 0.0),
            )
        answers: list[FlowWalkthroughAnswer] = []
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        rendered_plans: list[FlowWalkthroughPlan] = []
        total_planning_ms = 0.0
        total_rendering_ms = 0.0
        used_language = plan.response_language
        for narrative_plan in narrative_plans:
            self.current_stage = "WALKTHROUGH_PLANNING"
            walkthrough = self.planner.plan(narrative_plan, response_language=plan.response_language)
            total_planning_ms += walkthrough.planning_duration_ms
            self.current_stage = "TEXT_RENDERING"
            text, language, fallback_diagnostic, render_ms = self.renderer.render(walkthrough)
            used_language = language
            total_rendering_ms += render_ms
            if fallback_diagnostic is not None:
                diagnostics.append(fallback_diagnostic)
            rendered = FlowWalkthroughPlan(
                source=walkthrough.source,
                entrypoint=walkthrough.entrypoint,
                steps=walkthrough.steps,
                used_language=language,
                diagnostics=walkthrough.diagnostics,
                planning_duration_ms=walkthrough.planning_duration_ms,
                rendering_duration_ms=render_ms,
            )
            rendered_plans.append(rendered)
            answers.append(
                FlowWalkthroughAnswer(
                    source=rendered.source,
                    entrypoint=rendered.entrypoint,
                    text=text,
                    plan=rendered,
                )
            )
        metrics = self._metrics(rendered_plans, narrative_plans, total_planning_ms, total_rendering_ms)
        self.pipeline_records.append(metrics)
        self.current_stage = "SUCCESS"
        return FlowWalkthroughAnswerResult(
            answer_language=used_language,
            answers=tuple(answers),
            diagnostics=tuple(diagnostics),
            metrics=metrics,
        )

    def to_response(self, result: FlowWalkthroughAnswerResult) -> KnowledgeHumanQueryResponse:
        return KnowledgeHumanQueryResponse(
            answerLanguage=result.answer_language,
            answers=[
                KnowledgeFlowAnswer(source=answer.source, entrypoint=answer.entrypoint, text=answer.text)
                for answer in result.answers
            ],
            diagnostics=list(result.diagnostics),
        )

    def _metrics(
        self,
        rendered_plans: Sequence[FlowWalkthroughPlan],
        narrative_plans: Sequence[FlowNarrativePlan],
        planning_ms: float,
        rendering_ms: float,
    ) -> Dict[str, Any]:
        return {
            "narrativePlanCount": len(narrative_plans),
            "walkthroughStepCount": sum(plan.step_count for plan in rendered_plans),
            "branchCount": sum(plan.branch_count for plan in rendered_plans),
            "gapCount": sum(plan.gap_count for plan in rendered_plans),
            "answerCount": len(rendered_plans),
            "walkthroughPlanningDurationMs": round(planning_ms, 3),
            "textRenderingDurationMs": round(rendering_ms, 3),
            "finalAnswerProviderCallCount": 0,
            "groundingProviderCallCount": 0,
        }


_DEFAULT_CATALOGS: Mapping[str, Mapping[str, str]] = {
    "en": {
        "flow.entrypoint.http": "The entrypoint {symbol} receives {method} {route}.{summary}",
        "flow.entrypoint.generic": "The walkthrough starts at {symbol}.{summary}",
        "flow.execution.call": "Execution reaches {symbol}.{summary}",
        "flow.operation.http": "It performs {method} {route} through {transport}.{summary}",
        "flow.operation.generic": "It performs the {transport} operation at {symbol}.{summary}",
        "flow.boundary.external": "Execution leaves the available flow at {target}.",
        "flow.boundary.unresolved": "The next target from {from_symbol} is not present in the current facts.",
        "flow.gap.unverified": "The available facts suggest {method} {route} connects {from_symbol} to {to_symbol}, but the direct relation is not verified. The walkthrough continues with the separate verified fragment at {to_symbol}.",
        "flow.gap.ambiguous": "The available facts show {from_symbol} may continue through {method} {route}, but multiple matching targets exist, so no continuation is selected.",
        "flow.branch.start": "From {symbol}, execution can continue through these verified branches:",
        "flow.branch.item": "One branch reaches {to_symbol}.",
        "flow.cycle": "Execution reaches {symbol} again, so the cycle is shown once and is not expanded further.",
        "flow.shared": "The continuation at {symbol} was already described, so this branch rejoins that shared operation.",
        "flow.availableEnd": "The available facts end at {symbol}; no verified result is available.",
        "flow.result": "The available result is {summary}",
    },
    "uk": {
        "flow.entrypoint.http": "Вхідна точка {symbol} отримує {method} {route}.{summary}",
        "flow.entrypoint.generic": "Прохід починається з {symbol}.{summary}",
        "flow.execution.call": "Виконання переходить до {symbol}.{summary}",
        "flow.operation.http": "Виконується операція {method} {route} через {transport}.{summary}",
        "flow.operation.generic": "Виконується операція {transport} у {symbol}.{summary}",
        "flow.boundary.external": "Виконання виходить за межі доступного потоку в {target}.",
        "flow.boundary.unresolved": "Наступна ціль від {from_symbol} відсутня в поточних фактах.",
        "flow.gap.unverified": "Наявні факти вказують, що {method} {route} може з'єднувати {from_symbol} з {to_symbol}, але прямий зв'язок не підтверджено. Прохід продовжується окремим підтвердженим фрагментом у {to_symbol}.",
        "flow.gap.ambiguous": "Наявні факти показують, що {from_symbol} може продовжуватись через {method} {route}, але є кілька відповідних цілей, тому продовження не вибрано.",
        "flow.branch.start": "Від {symbol} виконання може йти такими підтвердженими гілками:",
        "flow.branch.item": "Одна гілка переходить до {to_symbol}.",
        "flow.cycle": "Виконання знову досягає {symbol}, тому цикл показано один раз без подальшого розгортання.",
        "flow.shared": "Продовження в {symbol} вже описано, тому ця гілка приєднується до спільної операції.",
        "flow.availableEnd": "Наявні факти закінчуються на {symbol}; підтвердженого результату немає.",
        "flow.result": "Доступний результат: {summary}",
    },
}
