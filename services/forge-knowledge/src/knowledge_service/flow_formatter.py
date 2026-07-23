from __future__ import annotations

import hashlib
import json
import math
import re
import time
import urllib.parse
from collections import defaultdict, deque
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any, Deque, Dict, Iterable, Mapping, Sequence

import httpx

from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.config import (
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
    DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
)
from knowledge_service.flow_boundary_classifier import FLOW_BOUNDARY_CLASSIFIER, FlowBoundaryClassifier, FlowBoundaryKind
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
from knowledge_service.language_policy import normalize_response_language
from knowledge_service.operation_facts import AvailableOperationFact, normalize_http_method, normalize_route, normalize_transport_kind
from knowledge_service.query_interpretation import QueryRetrievalPlan


VERIFIED = "VERIFIED"
UNVERIFIED = "UNVERIFIED"
AMBIGUOUS = "AMBIGUOUS"

_DEFAULT_MIN_CALL_TIMEOUT_SECONDS = 0.01
_DEADLINE_COMPLETION_GRACE_SECONDS = 0.005
_FRAMING_RESERVE_TOKENS = 512
_REPAIRABLE_ATTEMPTS = (1, 2)
_ALLOWED_FORMATTER_RESPONSE_KEYS = frozenset({"sections"})
_ALLOWED_FORMATTER_SECTION_KEYS = frozenset({"sectionRef", "steps"})
_ALLOWED_FORMATTER_STEP_KEYS = frozenset({"groupRefs", "certainty", "text"})
_ROUTE_RE = re.compile(r"(?<!\w)/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+/?)+")


class FlowFormatterGroupKind(str, Enum):
    ENTRYPOINT = "ENTRYPOINT"
    LINEAR_EXECUTION = "LINEAR_EXECUTION"
    ORDERED_CALL_GROUP = "ORDERED_CALL_GROUP"
    OPERATION = "OPERATION"
    TRANSITION = "TRANSITION"
    EXTERNAL_BOUNDARY = "EXTERNAL_BOUNDARY"
    UNRESOLVED_BOUNDARY = "UNRESOLVED_BOUNDARY"
    UNVERIFIED_GAP = "UNVERIFIED_GAP"
    AMBIGUOUS_GAP = "AMBIGUOUS_GAP"
    EXPLICIT_BRANCH = "EXPLICIT_BRANCH"
    BRANCH_ITEM = "BRANCH_ITEM"
    JOIN = "JOIN"
    CYCLE = "CYCLE"
    SHARED_CONTINUATION = "SHARED_CONTINUATION"
    AVAILABLE_FACTS_END = "AVAILABLE_FACTS_END"
    TYPED_RESULT = "TYPED_RESULT"


class FlowPresentationSectionKind(str, Enum):
    VERIFIED_FRAGMENT = "VERIFIED_FRAGMENT"
    UNVERIFIED_GAP = "UNVERIFIED_GAP"
    AMBIGUOUS_GAP = "AMBIGUOUS_GAP"


@dataclass(frozen=True)
class FlowFormatterGroup:
    group_ref: str
    order: int
    depth: int
    kind: FlowFormatterGroupKind
    certainty: str = VERIFIED
    source: str | None = None
    source_display_hint: str | None = None
    symbol: str | None = None
    from_source: str | None = None
    from_symbol: str | None = None
    to_source: str | None = None
    to_symbol: str | None = None
    relation_kind: str | None = None
    resolution_status: str | None = None
    transport_kind: str | None = None
    method: str | None = None
    route: str | None = None
    topic: str | None = None
    schedule: str | None = None
    operation_identity: str | None = None
    interface_identity: str | None = None
    boundary_kind: str | None = None
    target_descriptor: str | None = None
    summary: str | None = None
    child_groups: tuple["FlowFormatterGroup", ...] = ()
    terminal_semantic: str | None = None
    fragment_ref: str | None = None
    section_ref: str | None = None
    branch_path: str = ""
    merge_scope: str | None = None


@dataclass(frozen=True)
class FlowPresentationSection:
    section_ref: str
    kind: FlowPresentationSectionKind
    source: str | None
    entrypoint: str | None
    certainty: str
    ordered_groups: tuple[FlowFormatterGroup, ...]


@dataclass(frozen=True)
class FlowFormatterPlan:
    source: str
    entrypoint: str
    groups: tuple[FlowFormatterGroup, ...]
    response_language: str
    sections: tuple[FlowPresentationSection, ...] = ()
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    structural_metrics: Dict[str, Any] = field(default_factory=dict)
    planning_duration_ms: float = 0.0

    @property
    def group_count(self) -> int:
        return sum(1 for _ in self.walk())

    @property
    def branch_count(self) -> int:
        return sum(1 for group in self.walk() if group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH)

    @property
    def gap_count(self) -> int:
        return sum(
            1
            for group in self.walk()
            if group.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.AMBIGUOUS_GAP}
        )

    def walk(self) -> Iterable[FlowFormatterGroup]:
        stack = list(reversed(self.groups))
        while stack:
            group = stack.pop()
            yield group
            stack.extend(reversed(group.child_groups))


@dataclass(frozen=True)
class FlowFormatterContinuity:
    source: str | None = None
    symbol: str | None = None
    transition_kind: str | None = None
    gap_kind: str | None = None
    transport_kind: str | None = None
    method: str | None = None
    route: str | None = None


@dataclass(frozen=True)
class FlowFormatterSegment:
    plan_source: str
    plan_entrypoint: str
    response_language: str
    segment_index: int
    segment_count: int
    terminal: bool
    sections: tuple[FlowPresentationSection, ...]
    previous: FlowFormatterContinuity | None
    next: FlowFormatterContinuity | None
    rendered_input_tokens: int
    reserved_output_tokens: int
    fixed_framing_reserve_tokens: int
    context_tokens: int
    minimum_valid_output_tokens: int
    serialized_group_tokens: tuple[int, ...] = ()

    @property
    def required_groups(self) -> tuple[FlowFormatterGroup, ...]:
        return tuple(group for section in self.sections for group in section.ordered_groups)

    def to_prompt_input(self, original_question: str) -> Dict[str, Any]:
        required = self.required_groups
        sections = [
            {
                "sectionRef": section.section_ref,
                "kind": section.kind.value,
                "source": section.source,
                "entrypoint": section.entrypoint,
                "certainty": section.certainty,
                "orderedGroups": [_group_payload(group, include_children=False) for group in section.ordered_groups],
            }
            for section in self.sections
        ]
        group_to_section = {
            group.group_ref: section.section_ref
            for section in self.sections
            for group in section.ordered_groups
        }
        return {
            "originalQuestion": original_question,
            "responseLanguage": self.response_language,
            "planRoot": {
                "source": self.plan_source,
                "entrypoint": self.plan_entrypoint,
            },
            "segment": {
                "index": self.segment_index,
                "count": self.segment_count,
                "terminal": self.terminal,
            },
            "coverageContract": {
                "requiredSectionRefs": [section.section_ref for section in self.sections],
                "requiredGroupRefs": [group.group_ref for group in required],
                "certaintyByGroupRef": {group.group_ref: group.certainty for group in required},
                "sectionByGroupRef": group_to_section,
                "mergeScopeByGroupRef": {group.group_ref: group.merge_scope for group in required},
                "order": [group.group_ref for group in required],
                "groupRefsBySection": {
                    section.section_ref: [group.group_ref for group in section.ordered_groups]
                    for section in self.sections
                },
            },
            "continuity": {
                "previous": _continuity_payload(self.previous),
                "next": _continuity_payload(self.next),
            },
            "promptBudget": {
                "renderedInputTokens": self.rendered_input_tokens,
                "reservedOutputTokens": self.reserved_output_tokens,
                "fixedFramingReserveTokens": self.fixed_framing_reserve_tokens,
                "contextTokens": self.context_tokens,
                "minimumValidOutputTokens": self.minimum_valid_output_tokens,
            },
            "sections": _without_empty({"sections": sections})["sections"],
        }


@dataclass(frozen=True)
class FlowFormatterProviderResult:
    raw_text: str
    prompt_char_length: int
    truncated: bool = False


@dataclass(frozen=True)
class FlowFormatterStepText:
    group_refs: tuple[str, ...]
    certainty: str
    text: str


@dataclass(frozen=True)
class FlowFormatterAnswer:
    source: str
    entrypoint: str
    text: str
    plan: FlowFormatterPlan


@dataclass(frozen=True)
class FlowFormatterAnswerResult:
    answer_language: str
    answers: tuple[FlowFormatterAnswer, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    metrics: Dict[str, Any]


@dataclass
class _PlannerState:
    rendered: set[FlowNodeKey] = field(default_factory=set)
    order: int = 0

    def next_ref(self) -> tuple[int, str]:
        self.order += 1
        return self.order, f"g{self.order}"


class FlowFormatterError(Exception):
    pass


class FlowFormatterDeadlineExceeded(FlowFormatterError):
    pass


class FlowFormatterProviderUnavailable(FlowFormatterError):
    pass


class FlowFormatterBudgetError(FlowFormatterError):
    pass


class FlowFormatterContractViolation(FlowFormatterError):
    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = [str(error) for error in errors if str(error).strip()]
        super().__init__("; ".join(self.errors) or "formatter response violated output contract")


class FlowFormatterSegmentFailed(FlowFormatterError):
    pass


class FlowFormatterAllPlansFailed(FlowFormatterError):
    pass


class FlowFormatterPlanBuilder:
    def __init__(
        self,
        semantics: GraphRelationSemantics | None = None,
        boundary_classifier: FlowBoundaryClassifier | None = None,
    ) -> None:
        self.semantics = semantics or graph_relation_semantics()
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def plan(self, narrative_plan: FlowNarrativePlan, *, response_language: str = "en") -> FlowFormatterPlan:
        started = time.perf_counter()
        diagnostics: list[KnowledgeQueryDiagnostic] = list(narrative_plan.diagnostics)
        state = _PlannerState()
        groups: list[FlowFormatterGroup] = []
        source = ""
        entrypoint = ""
        for part_index, part in enumerate(narrative_plan.parts, start=1):
            if part.kind is FlowNarrativePartKind.VERIFIED_FRAGMENT and part.fragment is not None:
                fragment = part.fragment
                if not source:
                    source = fragment.source_id
                    entrypoint = self._symbol(fragment.root)
                groups.extend(self._fragment_groups(fragment.family, fragment.operation_facts, state, part_index=part_index))
                continue
            if part.gap is not None:
                groups.append(replace(self._gap_group(part.gap, state), fragment_ref=f"gap{part_index}"))
        if groups and self._last_runtime_group(groups[-1]).kind not in {
            FlowFormatterGroupKind.AVAILABLE_FACTS_END,
            FlowFormatterGroupKind.TYPED_RESULT,
            FlowFormatterGroupKind.CYCLE,
            FlowFormatterGroupKind.SHARED_CONTINUATION,
            FlowFormatterGroupKind.UNRESOLVED_BOUNDARY,
            FlowFormatterGroupKind.EXTERNAL_BOUNDARY,
        }:
            last = self._last_runtime_group(groups[-1])
            order, group_ref = state.next_ref()
            groups.append(
                FlowFormatterGroup(
                    group_ref=group_ref,
                    order=order,
                    depth=0,
                    kind=FlowFormatterGroupKind.AVAILABLE_FACTS_END,
                    source=last.source,
                    symbol=last.symbol,
                    terminal_semantic=FlowFormatterGroupKind.AVAILABLE_FACTS_END.value,
                )
            )
        groups_with_hints = self._apply_source_display_hints(tuple(groups))
        sectioned_groups, sections = self._presentation_sections(groups_with_hints)
        metrics = {
            "formatterGroupCount": sum(1 for _ in _walk_groups(sectioned_groups)),
            "presentationSectionCount": len(sections),
            "branchCount": sum(1 for group in _walk_groups(sectioned_groups) if group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH),
            "gapCount": sum(
                1
                for group in _walk_groups(sectioned_groups)
                if group.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.AMBIGUOUS_GAP}
            ),
        }
        return FlowFormatterPlan(
            source=source,
            entrypoint=entrypoint,
            groups=sectioned_groups,
            response_language=response_language,
            sections=sections,
            diagnostics=tuple(diagnostics),
            structural_metrics=metrics,
            planning_duration_ms=round((time.perf_counter() - started) * 1000, 3),
        )

    def _fragment_groups(
        self,
        family: FlowFamily,
        operation_facts: Sequence[AvailableOperationFact],
        state: _PlannerState,
        *,
        part_index: int,
    ) -> list[FlowFormatterGroup]:
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
        groups = self._node_groups(
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
            incoming_from_symbol=None,
            root=True,
        )
        for fact in self._external_operation_facts(operation_facts, node_by_key):
            groups.append(self._operation_group(fact, state, depth=0))
        return [self._with_fragment_ref(group, f"f{part_index}") for group in groups]

    def _node_groups(
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
        incoming_from_symbol: str | None,
        root: bool,
    ) -> list[FlowFormatterGroup]:
        groups: list[FlowFormatterGroup] = []
        current_node = node
        current_key = node_key
        current_depth = depth
        current_ancestry = tuple(ancestry)
        current_incoming = incoming
        current_incoming_from_symbol = incoming_from_symbol
        current_root = root

        while True:
            if current_key in current_ancestry:
                order, group_ref = state.next_ref()
                groups.append(
                    FlowFormatterGroup(
                        group_ref=group_ref,
                        order=order,
                        depth=current_depth,
                        kind=FlowFormatterGroupKind.CYCLE,
                        source=current_node.source_id,
                        symbol=self._symbol(current_node),
                    )
                )
                return groups
            if current_key in state.rendered:
                order, group_ref = state.next_ref()
                groups.append(
                    FlowFormatterGroup(
                        group_ref=group_ref,
                        order=order,
                        depth=current_depth,
                        kind=FlowFormatterGroupKind.SHARED_CONTINUATION,
                        source=current_node.source_id,
                        symbol=self._symbol(current_node),
                    )
                )
                return groups

            state.rendered.add(current_key)
            node_operations = tuple(operations_by_node.get(current_key, ()))
            groups.append(
                self._node_group(
                    current_node,
                    state,
                    depth=current_depth,
                    root=current_root,
                    incoming=current_incoming,
                    incoming_from_symbol=current_incoming_from_symbol,
                    operations=node_operations,
                )
            )

            child_entries: list[tuple[FlowGraphEdge, FlowGraphNode | None, FlowNodeKey | None]] = []
            for edge in outgoing.get(current_key, ()):
                target_key = self._to_key(edge)
                target = node_by_key.get(target_key) if target_key is not None else None
                child_entries.append((edge, target, target_key))
            has_verified_downstream = bool(child_entries)
            for edge in boundaries.get(current_key, ()):
                if self._human_relevant_boundary(edge, has_verified_downstream=has_verified_downstream):
                    groups.append(self._boundary_group(edge, current_node, state, depth=current_depth))

            if not child_entries:
                return groups

            if len(child_entries) == 1:
                edge, target, target_key = child_entries[0]
                if target is None or target_key is None:
                    if self._human_relevant_boundary(edge, has_verified_downstream=False):
                        groups.append(self._boundary_group(edge, current_node, state, depth=current_depth))
                    return groups
                current_ancestry = (*current_ancestry, current_key)
                current_incoming_from_symbol = self._symbol(current_node)
                current_node = target
                current_key = target_key
                current_incoming = edge
                current_root = False
                continue

            if self._has_explicit_branch([edge for edge, _target, _target_key in child_entries]):
                order, group_ref = state.next_ref()
                branch_children: list[FlowFormatterGroup] = []
                for edge, target, target_key in child_entries:
                    item = self._branch_item_group(edge, current_node, target, state, depth=current_depth + 1)
                    nested: list[FlowFormatterGroup] = []
                    if target is not None and target_key is not None:
                        nested = self._node_groups(
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
                            incoming_from_symbol=self._symbol(current_node),
                            root=False,
                        )
                    elif self._human_relevant_boundary(edge, has_verified_downstream=False):
                        nested = [self._boundary_group(edge, current_node, state, depth=current_depth + 2)]
                    branch_children.append(replace(item, child_groups=tuple(nested)))
                groups.append(
                    FlowFormatterGroup(
                        group_ref=group_ref,
                        order=order,
                        depth=current_depth,
                        kind=FlowFormatterGroupKind.EXPLICIT_BRANCH,
                        source=current_node.source_id,
                        symbol=self._symbol(current_node),
                        child_groups=tuple(branch_children),
                    )
                )
                return groups

            order, group_ref = state.next_ref()
            ordered_children: list[FlowFormatterGroup] = []
            for edge, target, target_key in child_entries:
                if target is None or target_key is None:
                    if self._human_relevant_boundary(edge, has_verified_downstream=False):
                        ordered_children.append(self._boundary_group(edge, current_node, state, depth=current_depth + 1))
                    continue
                ordered_children.extend(
                    self._node_groups(
                        target,
                        target_key,
                        node_by_key,
                        outgoing,
                        boundaries,
                        operations_by_node,
                        state,
                        part_index=part_index,
                        depth=current_depth + 1,
                        ancestry=(*current_ancestry, current_key),
                        incoming=edge,
                        incoming_from_symbol=self._symbol(current_node),
                        root=False,
                    )
                )
            groups.append(
                FlowFormatterGroup(
                    group_ref=group_ref,
                    order=order,
                    depth=current_depth,
                    kind=FlowFormatterGroupKind.ORDERED_CALL_GROUP,
                    source=current_node.source_id,
                    symbol=self._symbol(current_node),
                    child_groups=tuple(ordered_children),
                )
            )
            return groups

    def _node_group(
        self,
        node: FlowGraphNode,
        state: _PlannerState,
        *,
        depth: int,
        root: bool,
        incoming: FlowGraphEdge | None,
        incoming_from_symbol: str | None,
        operations: Sequence[AvailableOperationFact],
    ) -> FlowFormatterGroup:
        operation = self._primary_operation(operations)
        order, group_ref = state.next_ref()
        return FlowFormatterGroup(
            group_ref=group_ref,
            order=order,
            depth=depth,
            kind=FlowFormatterGroupKind.ENTRYPOINT if root else FlowFormatterGroupKind.LINEAR_EXECUTION,
            source=node.source_id,
            symbol=self._symbol(node),
            from_source=incoming.source_id if incoming is not None else None,
            from_symbol=self._clean(incoming_from_symbol) if incoming is not None else None,
            relation_kind=incoming.edge_type if incoming is not None else None,
            resolution_status=incoming.resolution_status if incoming is not None else None,
            transport_kind=normalize_transport_kind(operation.transport_kind) if operation is not None else self._entrypoint_transport(node),
            method=normalize_http_method(operation.method) if operation is not None else self._clean(node.entrypoint_http_method),
            route=normalize_route(operation.normalized_route) if operation is not None else self._clean(node.entrypoint_route),
            topic=self._clean(operation.topic) if operation is not None else self._clean(node.entrypoint_topic),
            schedule=self._clean(operation.schedule) if operation is not None else self._clean(node.entrypoint_schedule),
            operation_identity=self._clean(operation.operation_identity) if operation is not None else None,
            interface_identity=self._clean(operation.interface_identity) if operation is not None else self._clean(node.entrypoint_interface_method),
            summary=self._clean(node.summary),
        )

    def _operation_group(self, fact: AvailableOperationFact, state: _PlannerState, *, depth: int) -> FlowFormatterGroup:
        order, group_ref = state.next_ref()
        return FlowFormatterGroup(
            group_ref=group_ref,
            order=order,
            depth=depth,
            kind=FlowFormatterGroupKind.OPERATION,
            source=fact.owner_source_id,
            symbol=self._operation_symbol(fact),
            transport_kind=normalize_transport_kind(fact.transport_kind),
            method=normalize_http_method(fact.method),
            route=normalize_route(fact.normalized_route),
            topic=self._clean(fact.topic),
            schedule=self._clean(fact.schedule),
            operation_identity=self._clean(fact.operation_identity),
            interface_identity=self._clean(fact.interface_identity),
            target_descriptor=self._clean(fact.target_service_identity),
        )

    def _boundary_group(
        self,
        edge: FlowGraphEdge,
        owner: FlowGraphNode,
        state: _PlannerState,
        *,
        depth: int,
    ) -> FlowFormatterGroup:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        projection = self.boundary_classifier.project(edge)
        order, group_ref = state.next_ref()
        kind = (
            FlowFormatterGroupKind.EXTERNAL_BOUNDARY
            if projection.kind is FlowBoundaryKind.EXTERNAL
            else FlowFormatterGroupKind.UNRESOLVED_BOUNDARY
        )
        return FlowFormatterGroup(
            group_ref=group_ref,
            order=order,
            depth=depth,
            kind=kind,
            certainty=UNVERIFIED if kind is FlowFormatterGroupKind.UNRESOLVED_BOUNDARY else VERIFIED,
            source=owner.source_id,
            symbol=self._symbol(owner),
            from_source=owner.source_id,
            from_symbol=self._symbol(owner),
            to_source=edge.to_source_id,
            to_symbol=self._edge_target(edge),
            relation_kind=edge.edge_type,
            resolution_status=projection.resolution_status,
            transport_kind=normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind")),
            method=normalize_http_method(metadata.get("httpMethod") or metadata.get("method")),
            route=normalize_route(metadata.get("routeTemplate") or metadata.get("route")),
            operation_identity=self._clean(metadata.get("operationIdentity") if isinstance(metadata.get("operationIdentity"), str) else None),
            interface_identity=self._clean(metadata.get("interfaceIdentity") if isinstance(metadata.get("interfaceIdentity"), str) else None),
            boundary_kind=projection.kind.value,
            target_descriptor=self._clean(projection.target) or self._edge_target(edge),
        )

    def _branch_item_group(
        self,
        edge: FlowGraphEdge,
        owner: FlowGraphNode,
        target: FlowGraphNode | None,
        state: _PlannerState,
        *,
        depth: int,
    ) -> FlowFormatterGroup:
        order, group_ref = state.next_ref()
        return FlowFormatterGroup(
            group_ref=group_ref,
            order=order,
            depth=depth,
            kind=FlowFormatterGroupKind.BRANCH_ITEM,
            source=target.source_id if target is not None else edge.to_source_id,
            symbol=self._symbol(target) if target is not None else self._edge_target(edge),
            from_source=owner.source_id,
            from_symbol=self._symbol(owner),
            to_source=target.source_id if target is not None else edge.to_source_id,
            to_symbol=self._symbol(target) if target is not None else self._edge_target(edge),
            relation_kind=edge.edge_type,
            resolution_status=edge.resolution_status,
        )

    def _gap_group(self, gap, state: _PlannerState) -> FlowFormatterGroup:
        status = gap.verification_status
        kind = (
            FlowFormatterGroupKind.AMBIGUOUS_GAP
            if status is FlowGapVerificationStatus.AMBIGUOUS
            else FlowFormatterGroupKind.UNVERIFIED_GAP
        )
        order, group_ref = state.next_ref()
        return FlowFormatterGroup(
            group_ref=group_ref,
            order=order,
            depth=0,
            kind=kind,
            certainty=AMBIGUOUS if kind is FlowFormatterGroupKind.AMBIGUOUS_GAP else UNVERIFIED,
            from_source=gap.from_source,
            from_symbol=gap.from_symbol,
            to_source=gap.to_source,
            to_symbol=gap.to_symbol,
            transport_kind=normalize_transport_kind(gap.transport_kind),
            method=normalize_http_method(gap.method),
            route=normalize_route(gap.route),
            operation_identity=gap.operation_identity,
            target_descriptor=gap.operation_identity,
            boundary_kind=gap.kind,
        )

    def _human_relevant_boundary(self, edge: FlowGraphEdge, *, has_verified_downstream: bool) -> bool:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        projection = self.boundary_classifier.project(edge)
        target_descriptor = projection.target or self._edge_target(edge)
        meaningful_target = self._meaningful_target_descriptor(target_descriptor)
        typed_transport = any(
            self._clean(metadata.get(key) if isinstance(metadata.get(key), str) else None)
            for key in ("transportKind", "connectorKind", "httpMethod", "method", "routeTemplate", "route", "topic", "schedule", "operationIdentity", "interfaceIdentity")
        )
        external = projection.kind is FlowBoundaryKind.EXTERNAL or bool(edge.external)
        terminates_path = not has_verified_downstream
        explicit_boundary_reason = bool(self._clean(edge.boundary_reason))
        if has_verified_downstream and not (typed_transport or external or explicit_boundary_reason):
            return False
        return bool(typed_transport or external or explicit_boundary_reason or (terminates_path and meaningful_target))

    def _meaningful_target_descriptor(self, value: str | None) -> bool:
        normalized = self._clean(value)
        if not normalized:
            return False
        return normalized.startswith("/") or "." in normalized or "::" in normalized or "#" in normalized

    def _has_explicit_branch(self, edges: Sequence[FlowGraphEdge]) -> bool:
        return any(self._edge_has_explicit_branch(edge) for edge in edges)

    def _edge_has_explicit_branch(self, edge: FlowGraphEdge) -> bool:
        return self.semantics.is_explicit_branch(edge)

    def _dedupe_boundary_edges(self, edges: Sequence[FlowGraphEdge]) -> list[FlowGraphEdge]:
        deduped: list[FlowGraphEdge] = []
        seen: set[tuple[str, str, str, str, str]] = set()
        for edge in edges:
            metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
            projection = self.boundary_classifier.project(edge)
            key = (
                normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind")) or "",
                normalize_http_method(metadata.get("httpMethod") or metadata.get("method")) or "",
                normalize_route(metadata.get("routeTemplate") or metadata.get("route")) or "",
                projection.kind.value,
                projection.target or "",
            )
            if key in seen:
                continue
            seen.add(key)
            deduped.append(edge)
        return deduped

    def _apply_source_display_hints(self, groups: tuple[FlowFormatterGroup, ...]) -> tuple[FlowFormatterGroup, ...]:
        symbol_sources: dict[str, set[str]] = defaultdict(set)
        for group in _walk_groups(groups):
            if group.symbol and group.source:
                symbol_sources[group.symbol].add(group.source)
            if group.from_symbol and group.from_source:
                symbol_sources[group.from_symbol].add(group.from_source)
            if group.to_symbol and group.to_source:
                symbol_sources[group.to_symbol].add(group.to_source)
        ambiguous_symbols = {symbol for symbol, sources in symbol_sources.items() if len(sources) > 1}
        previous_source: str | None = None
        required: set[str] = set()
        for group in _walk_groups(groups):
            group_source = group.source or group.from_source or group.to_source
            if group.kind is FlowFormatterGroupKind.ENTRYPOINT and group.depth == 0:
                required.add(group.group_ref)
            if group_source and previous_source and group_source != previous_source:
                required.add(group.group_ref)
            if group.symbol in ambiguous_symbols or group.from_symbol in ambiguous_symbols or group.to_symbol in ambiguous_symbols:
                required.add(group.group_ref)
            if group.from_source and group.to_source and group.from_source != group.to_source:
                required.add(group.group_ref)
            if group_source:
                previous_source = group_source
        return tuple(self._with_source_hint(group, required) for group in groups)

    def _with_source_hint(self, group: FlowFormatterGroup, required: set[str]) -> FlowFormatterGroup:
        return replace(
            group,
            source_display_hint="REQUIRED" if group.group_ref in required else "OPTIONAL",
            child_groups=tuple(self._with_source_hint(child, required) for child in group.child_groups),
        )

    def _with_fragment_ref(self, group: FlowFormatterGroup, fragment_ref: str) -> FlowFormatterGroup:
        return replace(
            group,
            fragment_ref=fragment_ref,
            child_groups=tuple(self._with_fragment_ref(child, fragment_ref) for child in group.child_groups),
        )

    def _presentation_sections(
        self,
        groups: tuple[FlowFormatterGroup, ...],
    ) -> tuple[tuple[FlowFormatterGroup, ...], tuple[FlowPresentationSection, ...]]:
        flat = list(self._walk_with_branch_paths(groups))
        replacements: dict[str, FlowFormatterGroup] = {}
        sections: list[FlowPresentationSection] = []
        current_ref: str | None = None
        current_kind: FlowPresentationSectionKind | None = None
        current_source: str | None = None
        current_entrypoint: str | None = None
        current_certainty: str = VERIFIED
        current_fragment: str | None = None
        current_groups: list[FlowFormatterGroup] = []
        previous_verified_source: str | None = None

        def flush() -> None:
            nonlocal current_ref, current_kind, current_source, current_entrypoint, current_certainty, current_fragment, current_groups
            if current_ref and current_kind and current_groups:
                sections.append(
                    FlowPresentationSection(
                        section_ref=current_ref,
                        kind=current_kind,
                        source=current_source,
                        entrypoint=current_entrypoint,
                        certainty=current_certainty,
                        ordered_groups=tuple(current_groups),
                    )
                )
            current_ref = None
            current_kind = None
            current_source = None
            current_entrypoint = None
            current_certainty = VERIFIED
            current_fragment = None
            current_groups = []

        for group, branch_path in flat:
            gap_kind = self._gap_section_kind(group)
            if gap_kind is not None:
                flush()
                section_ref = f"s{len(sections) + 1}"
                updated = self._sectioned_group(
                    group,
                    section_ref=section_ref,
                    branch_path=branch_path,
                    merge_scope=f"p1|{section_ref}|{group.fragment_ref or ''}|{branch_path}|{group.certainty}|gap:{group.group_ref}",
                )
                replacements[group.group_ref] = updated
                sections.append(
                    FlowPresentationSection(
                        section_ref=section_ref,
                        kind=gap_kind,
                        source=None,
                        entrypoint=None,
                        certainty=group.certainty,
                        ordered_groups=(updated,),
                    )
                )
                previous_verified_source = None
                continue

            group_source = self._section_source(group)
            starts_new_section = (
                current_ref is None
                or current_kind is not FlowPresentationSectionKind.VERIFIED_FRAGMENT
                or current_fragment != group.fragment_ref
                or (group_source is not None and previous_verified_source is not None and group_source != previous_verified_source)
            )
            if starts_new_section:
                flush()
                current_ref = f"s{len(sections) + 1}"
                current_kind = FlowPresentationSectionKind.VERIFIED_FRAGMENT
                current_source = group_source
                current_entrypoint = self._section_entrypoint(group)
                current_certainty = VERIFIED
                current_fragment = group.fragment_ref

            section_ref = current_ref or f"s{len(sections) + 1}"
            updated = self._sectioned_group(
                group,
                section_ref=section_ref,
                branch_path=branch_path,
                merge_scope=self._merge_scope(group, section_ref, branch_path),
            )
            replacements[group.group_ref] = updated
            current_groups.append(updated)
            if group_source:
                previous_verified_source = group_source
                if current_source is None:
                    current_source = group_source
            if current_entrypoint is None:
                current_entrypoint = self._section_entrypoint(group)
        flush()
        return tuple(self._rebuild_sectioned_tree(group, replacements) for group in groups), tuple(sections)

    def _walk_with_branch_paths(
        self,
        groups: Sequence[FlowFormatterGroup],
        branch_path: str = "",
    ) -> Iterable[tuple[FlowFormatterGroup, str]]:
        for group in groups:
            if group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH:
                group_path = branch_path
                child_path = f"{branch_path}/{group.group_ref}" if branch_path else group.group_ref
            elif group.kind is FlowFormatterGroupKind.BRANCH_ITEM:
                group_path = f"{branch_path}/{group.group_ref}" if branch_path else group.group_ref
                child_path = group_path
            else:
                group_path = branch_path
                child_path = group_path
            yield group, group_path
            yield from self._walk_with_branch_paths(group.child_groups, child_path)

    def _gap_section_kind(self, group: FlowFormatterGroup) -> FlowPresentationSectionKind | None:
        if group.kind is FlowFormatterGroupKind.UNVERIFIED_GAP:
            return FlowPresentationSectionKind.UNVERIFIED_GAP
        if group.kind is FlowFormatterGroupKind.AMBIGUOUS_GAP:
            return FlowPresentationSectionKind.AMBIGUOUS_GAP
        return None

    def _section_source(self, group: FlowFormatterGroup) -> str | None:
        return group.source or group.from_source or group.to_source

    def _section_entrypoint(self, group: FlowFormatterGroup) -> str | None:
        return group.symbol or group.to_symbol or group.from_symbol

    def _sectioned_group(
        self,
        group: FlowFormatterGroup,
        *,
        section_ref: str,
        branch_path: str,
        merge_scope: str,
    ) -> FlowFormatterGroup:
        return replace(group, section_ref=section_ref, branch_path=branch_path, merge_scope=merge_scope)

    def _merge_scope(self, group: FlowFormatterGroup, section_ref: str, branch_path: str) -> str:
        boundary = "linear"
        if group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH:
            boundary = f"explicit-branch:{group.group_ref}"
        elif group.kind in {FlowFormatterGroupKind.CYCLE, FlowFormatterGroupKind.SHARED_CONTINUATION}:
            boundary = f"{group.kind.value}:{group.group_ref}"
        elif group.terminal_semantic:
            boundary = f"terminal:{group.group_ref}"
        return "|".join(("p1", section_ref, group.fragment_ref or "", branch_path, group.certainty, boundary))

    def _rebuild_sectioned_tree(
        self,
        group: FlowFormatterGroup,
        replacements: Mapping[str, FlowFormatterGroup],
    ) -> FlowFormatterGroup:
        base = replacements.get(group.group_ref, group)
        return replace(
            base,
            child_groups=tuple(self._rebuild_sectioned_tree(child, replacements) for child in group.child_groups),
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
        return {key: tuple(sorted(values, key=self._operation_fact_sort_key)) for key, values in grouped.items()}

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
                    if fact.owner_key not in node_by_key and str(fact.direction_role or "") == "OUTBOUND"
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

    def _last_runtime_group(self, group: FlowFormatterGroup) -> FlowFormatterGroup:
        current = group
        while current.child_groups:
            current = current.child_groups[-1]
        return current

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
        return None

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


class FlowFormatterSegmentPlanner:
    def __init__(self, *, context_tokens: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS, framing_reserve_tokens: int = _FRAMING_RESERVE_TOKENS) -> None:
        self.context_tokens = max(1024, int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS))
        self.framing_reserve_tokens = max(0, int(framing_reserve_tokens or 0))
        self.serialization_count = 0

    def plan(self, formatter_plan: FlowFormatterPlan) -> tuple[FlowFormatterSegment, ...]:
        self.serialization_count = 0
        formatter_plan = self._ensure_sections(formatter_plan)
        candidate_sections = tuple(
            section
            for original in formatter_plan.sections
            for section in self._partition_oversized_section(original)
        )
        serialized = {group.group_ref: self._group_tokens(group) for section in candidate_sections for group in section.ordered_groups}
        segments: list[tuple[FlowPresentationSection, ...]] = []
        current: list[FlowPresentationSection] = []
        for section in candidate_sections:
            single_serialized = {group.group_ref: self._known_group_tokens(group, serialized) for group in section.ordered_groups}
            if not self._fits((section,), single_serialized):
                raise FlowFormatterBudgetError("formatter group cannot fit within the configured context budget")
            candidate = tuple([*current, section])
            if current and not self._fits(candidate, serialized):
                segments.append(tuple(current))
                current = [section]
                continue
            current.append(section)
        if current:
            segments.append(tuple(current))
        return self._segments(formatter_plan, tuple(segments), serialized)

    def split_segment(self, segment: FlowFormatterSegment) -> tuple[FlowFormatterSegment, ...]:
        sections = segment.sections
        if len(sections) > 1:
            midpoint = max(1, len(sections) // 2)
            return self._segments_from_existing(segment, (sections[:midpoint], sections[midpoint:]))
        if len(sections) == 1:
            partitions = self._split_section(sections[0])
            if len(partitions) > 1:
                return self._segments_from_existing(segment, tuple((item,) for item in partitions))
        return ()

    def _segments(
        self,
        formatter_plan: FlowFormatterPlan,
        section_segments: tuple[tuple[FlowPresentationSection, ...], ...],
        serialized: Mapping[str, int],
    ) -> tuple[FlowFormatterSegment, ...]:
        count = len(section_segments)
        segments: list[FlowFormatterSegment] = []
        for index, sections in enumerate(section_segments, start=1):
            previous = self._continuity(self._last_group(section_segments[index - 2])) if index > 1 and section_segments[index - 2] else None
            next_value = self._continuity(self._first_group(section_segments[index])) if index < count and section_segments[index] else None
            rendered_input = self._input_tokens(sections, serialized)
            minimum_output = self._minimum_output_tokens(sections)
            reserved_output = self._reserved_output_tokens(sections, minimum_output)
            segments.append(
                FlowFormatterSegment(
                    plan_source=formatter_plan.source,
                    plan_entrypoint=formatter_plan.entrypoint,
                    response_language=formatter_plan.response_language,
                    segment_index=index,
                    segment_count=count,
                    terminal=index == count,
                    sections=sections,
                    previous=previous,
                    next=next_value,
                    rendered_input_tokens=rendered_input,
                    reserved_output_tokens=reserved_output,
                    fixed_framing_reserve_tokens=self.framing_reserve_tokens,
                    context_tokens=self.context_tokens,
                    minimum_valid_output_tokens=minimum_output,
                    serialized_group_tokens=tuple(self._known_group_tokens(group, serialized) for section in sections for group in section.ordered_groups),
                )
            )
        return tuple(segments)

    def _segments_from_existing(
        self,
        segment: FlowFormatterSegment,
        section_segments: tuple[tuple[FlowPresentationSection, ...], ...],
    ) -> tuple[FlowFormatterSegment, ...]:
        serialized = {
            group.group_ref: self._group_tokens(group)
            for sections in section_segments
            for section in sections
            for group in section.ordered_groups
        }
        count = len(section_segments)
        result: list[FlowFormatterSegment] = []
        for index, sections in enumerate(section_segments, start=1):
            previous = self._continuity(self._last_group(section_segments[index - 2])) if index > 1 and section_segments[index - 2] else segment.previous
            next_value = self._continuity(self._first_group(section_segments[index])) if index < count and section_segments[index] else segment.next
            minimum_output = self._minimum_output_tokens(sections)
            result.append(
                replace(
                    segment,
                    segment_index=index,
                    segment_count=count,
                    terminal=segment.terminal and index == count,
                    sections=sections,
                    previous=previous,
                    next=next_value,
                    rendered_input_tokens=self._input_tokens(sections, serialized),
                    reserved_output_tokens=self._reserved_output_tokens(sections, minimum_output),
                    minimum_valid_output_tokens=minimum_output,
                    serialized_group_tokens=tuple(self._known_group_tokens(group, serialized) for section in sections for group in section.ordered_groups),
                )
            )
        return tuple(result)

    def _ensure_sections(self, formatter_plan: FlowFormatterPlan) -> FlowFormatterPlan:
        if formatter_plan.sections:
            return formatter_plan
        groups, sections = FlowFormatterPlanBuilder()._presentation_sections(formatter_plan.groups)
        return replace(formatter_plan, groups=groups, sections=sections)

    def _partition_oversized_section(self, section: FlowPresentationSection) -> tuple[FlowPresentationSection, ...]:
        serialized = {group.group_ref: self._group_tokens(group) for group in section.ordered_groups}
        if self._fits((section,), serialized):
            return (section,)
        if section.kind in {FlowPresentationSectionKind.UNVERIFIED_GAP, FlowPresentationSectionKind.AMBIGUOUS_GAP}:
            raise FlowFormatterBudgetError("formatter gap group cannot fit within the configured context budget")

        result: list[FlowPresentationSection] = []
        scopes = self._merge_scope_ranges(section.ordered_groups)
        current: list[FlowFormatterGroup] = []
        for scope_groups in scopes:
            scope_section = replace(section, ordered_groups=tuple(scope_groups))
            scope_serialized = {group.group_ref: self._known_group_tokens(group, serialized) for group in scope_groups}
            if not self._fits((scope_section,), scope_serialized):
                if current:
                    result.append(replace(section, ordered_groups=tuple(current)))
                    current = []
                result.extend(self._split_scope_to_fit(section, tuple(scope_groups), serialized))
                continue
            candidate = [*current, *scope_groups]
            candidate_section = replace(section, ordered_groups=tuple(candidate))
            candidate_serialized = {group.group_ref: self._known_group_tokens(group, serialized) for group in candidate}
            if current and not self._fits((candidate_section,), candidate_serialized):
                result.append(replace(section, ordered_groups=tuple(current)))
                current = list(scope_groups)
                continue
            current = candidate
        if current:
            result.append(replace(section, ordered_groups=tuple(current)))
        return tuple(result)

    def _split_scope_to_fit(
        self,
        section: FlowPresentationSection,
        groups: tuple[FlowFormatterGroup, ...],
        serialized: Mapping[str, int],
    ) -> tuple[FlowPresentationSection, ...]:
        result: list[FlowPresentationSection] = []
        current: list[FlowFormatterGroup] = []
        for group in groups:
            single = replace(section, ordered_groups=(group,))
            single_serialized = {group.group_ref: self._known_group_tokens(group, serialized)}
            if not self._fits((single,), single_serialized):
                raise FlowFormatterBudgetError("formatter group cannot fit within the configured context budget")
            candidate = [*current, group]
            candidate_section = replace(section, ordered_groups=tuple(candidate))
            candidate_serialized = {item.group_ref: self._known_group_tokens(item, serialized) for item in candidate}
            if current and not self._fits((candidate_section,), candidate_serialized):
                result.append(replace(section, ordered_groups=tuple(current)))
                current = [group]
                continue
            current.append(group)
        if current:
            result.append(replace(section, ordered_groups=tuple(current)))
        return tuple(result)

    def _split_section(self, section: FlowPresentationSection) -> tuple[FlowPresentationSection, ...]:
        scopes = self._merge_scope_ranges(section.ordered_groups)
        if len(scopes) > 1:
            midpoint = max(1, len(scopes) // 2)
            first = tuple(group for scope in scopes[:midpoint] for group in scope)
            second = tuple(group for scope in scopes[midpoint:] for group in scope)
            return (replace(section, ordered_groups=first), replace(section, ordered_groups=second))
        groups = section.ordered_groups
        if len(groups) <= 1 or section.kind in {FlowPresentationSectionKind.UNVERIFIED_GAP, FlowPresentationSectionKind.AMBIGUOUS_GAP}:
            return (section,)
        midpoint = max(1, len(groups) // 2)
        return (
            replace(section, ordered_groups=groups[:midpoint]),
            replace(section, ordered_groups=groups[midpoint:]),
        )

    def _merge_scope_ranges(self, groups: Sequence[FlowFormatterGroup]) -> tuple[tuple[FlowFormatterGroup, ...], ...]:
        ranges: list[tuple[FlowFormatterGroup, ...]] = []
        current_scope: str | None = None
        current: list[FlowFormatterGroup] = []
        for group in groups:
            if current and group.merge_scope != current_scope:
                ranges.append(tuple(current))
                current = []
            current_scope = group.merge_scope
            current.append(group)
        if current:
            ranges.append(tuple(current))
        return tuple(ranges)

    def _fits(self, sections: Sequence[FlowPresentationSection], serialized: Mapping[str, int]) -> bool:
        rendered_input = self._input_tokens(sections, serialized)
        minimum_output = self._minimum_output_tokens(sections)
        reserved_output = self._reserved_output_tokens(sections, minimum_output)
        return rendered_input + reserved_output + self.framing_reserve_tokens <= self.context_tokens and minimum_output <= reserved_output

    def _group_tokens(self, group: FlowFormatterGroup) -> int:
        self.serialization_count += 1
        return _estimate_tokens(json.dumps(_group_payload(group, include_children=False), ensure_ascii=False, sort_keys=True, separators=(",", ":")))

    def _known_group_tokens(self, group: FlowFormatterGroup, serialized: Mapping[str, int]) -> int:
        if group.group_ref in serialized:
            return serialized[group.group_ref]
        return self._group_tokens(group)

    def _input_tokens(self, sections: Sequence[FlowPresentationSection], serialized: Mapping[str, int]) -> int:
        group_tokens = sum(self._known_group_tokens(group, serialized) for section in sections for group in section.ordered_groups)
        required_count = sum(len(section.ordered_groups) for section in sections)
        return group_tokens + 260 + len(sections) * 24 + required_count * 10

    def _minimum_output_tokens(self, sections: Sequence[FlowPresentationSection]) -> int:
        skeleton = {
            "sections": [
                {
                    "sectionRef": section.section_ref,
                    "steps": [
                        {"groupRefs": [group.group_ref], "certainty": group.certainty, "text": "x"}
                        for group in section.ordered_groups
                    ],
                }
                for section in sections
            ]
        }
        return _estimate_tokens(json.dumps(skeleton, ensure_ascii=False, separators=(",", ":")))

    def _reserved_output_tokens(self, sections: Sequence[FlowPresentationSection], minimum_output_tokens: int) -> int:
        required_count = sum(len(section.ordered_groups) for section in sections)
        return max(minimum_output_tokens, required_count * 64 + len(sections) * 16 + 160)

    def _first_group(self, sections: Sequence[FlowPresentationSection]) -> FlowFormatterGroup | None:
        for section in sections:
            if section.ordered_groups:
                return section.ordered_groups[0]
        return None

    def _last_group(self, sections: Sequence[FlowPresentationSection]) -> FlowFormatterGroup | None:
        for section in reversed(tuple(sections)):
            if section.ordered_groups:
                return section.ordered_groups[-1]
        return None

    def _continuity(self, group: FlowFormatterGroup | None) -> FlowFormatterContinuity | None:
        if group is None:
            return None
        last = group
        return FlowFormatterContinuity(
            source=last.source or last.to_source or last.from_source,
            symbol=last.symbol or last.to_symbol or last.from_symbol,
            transition_kind=last.relation_kind,
            gap_kind=last.kind.value if last.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.AMBIGUOUS_GAP} else None,
            transport_kind=last.transport_kind,
            method=last.method,
            route=last.route,
        )


class FlowFormatterPromptRenderer:
    def render(self, formatter_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious JSON failed validation. Correct these exact issues:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += "\n"
        context_json = json.dumps(dict(formatter_input), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return (
            "Format one segment of a backend-owned execution-flow plan for a human reader.\n"
            f"Required responseLanguage: {dict(formatter_input).get('responseLanguage', '')}. Do not use English unless responseLanguage is en.\n"
            "Return strict JSON only. Do not include prose outside JSON.\n"
            "The JSON shape is exactly: {\"sections\":[{\"sectionRef\":\"string\",\"steps\":[{\"groupRefs\":[\"string\"],\"certainty\":\"string\",\"text\":\"string\"}]}]}.\n"
            "Return every supplied sectionRef exactly once, in the supplied section order.\n"
            "Cover every supplied groupRef exactly once across all steps.\n"
            "Each step groupRefs array must be one contiguous ordered range inside that section.\n"
            "Combine adjacent groups only when their supplied mergeScope value is identical.\n"
            "Do not combine groups across sections, certainty values, gaps, branch paths, independent answers, or different merge scopes.\n"
            "Produce the smallest clear set of steps that explains the ordered structure without inventing behavior.\n"
            "Use summaries where useful, translate summaries into responseLanguage, preserve supplied identifiers when mentioned, and avoid repeating equivalent information.\n"
            "Write every text value in responseLanguage.\n"
            "Use only the supplied structural fields and summaries.\n"
            "Do not mention internal refs, evidence records, retrieval mechanics, formatter mechanics, or JSON structure.\n"
            "Do not infer behavior from a symbol, source name, package name, route, or class name.\n"
            f"{validation_block}"
            "BEGIN_FLOW_FORMATTER_INPUT_JSON\n"
            f"{context_json}\n"
            "END_FLOW_FORMATTER_INPUT_JSON\n"
        )


class FlowFormatterResponseValidator:
    def __init__(self, language_validator: HumanAnswerTextValidator | None = None) -> None:
        self.language_validator = language_validator or HumanAnswerTextValidator()

    def validate(self, raw_text: str, segment: FlowFormatterSegment) -> tuple[FlowFormatterStepText, ...]:
        errors: list[str] = []
        try:
            payload = json.loads(raw_text)
        except Exception as exc:
            raise FlowFormatterContractViolation(["Response must be strict JSON."]) from exc
        if not isinstance(payload, dict):
            raise FlowFormatterContractViolation(["Response JSON root must be an object."])
        extra_root = [key for key in payload if key not in _ALLOWED_FORMATTER_RESPONSE_KEYS]
        if extra_root:
            errors.append(f"Response must not include extra root fields: {', '.join(sorted(extra_root))}.")

        required_groups = segment.required_groups
        required_refs = [group.group_ref for group in required_groups]
        required_sections = list(segment.sections)
        required_section_refs = [section.section_ref for section in required_sections]
        section_by_ref = {section.section_ref: section for section in required_sections}
        group_by_ref = {group.group_ref: group for group in required_groups}
        group_index = {group.group_ref: index for index, group in enumerate(required_groups)}
        certainty_by_ref = {group.group_ref: group.certainty for group in required_groups}
        section_by_group_ref = {
            group.group_ref: section.section_ref
            for section in required_sections
            for group in section.ordered_groups
        }

        sections_payload = payload.get("sections")
        if not isinstance(sections_payload, list):
            errors.append("sections must be a list.")
            sections_payload = []
        actual_section_refs: list[str] = []

        parsed_steps: list[FlowFormatterStepText] = []
        seen: set[str] = set()
        last_index = -1
        for section_index, section_item in enumerate(sections_payload):
            if not isinstance(section_item, dict):
                errors.append(f"sections[{section_index}] must be an object.")
                continue
            extra_section = [key for key in section_item if key not in _ALLOWED_FORMATTER_SECTION_KEYS]
            if extra_section:
                errors.append(f"sections[{section_index}] must not include extra fields: {', '.join(sorted(extra_section))}.")
            missing_section = [key for key in _ALLOWED_FORMATTER_SECTION_KEYS if key not in section_item]
            if missing_section:
                errors.append(f"sections[{section_index}] missing required fields: {', '.join(sorted(missing_section))}.")
            section_ref = str(section_item.get("sectionRef") or "").strip()
            actual_section_refs.append(section_ref)
            if section_ref not in section_by_ref:
                errors.append(f"sectionRef {section_ref or '<empty>'} was not supplied.")
            steps = section_item.get("steps")
            if not isinstance(steps, list):
                errors.append(f"sections[{section_index}].steps must be a list.")
                continue
            for step_index, item in enumerate(steps):
                if not isinstance(item, dict):
                    errors.append(f"sections[{section_index}].steps[{step_index}] must be an object.")
                    continue
                extra_step = [key for key in item if key not in _ALLOWED_FORMATTER_STEP_KEYS]
                if extra_step:
                    errors.append(f"sections[{section_index}].steps[{step_index}] must not include extra fields: {', '.join(sorted(extra_step))}.")
                missing_step = [key for key in _ALLOWED_FORMATTER_STEP_KEYS if key not in item]
                if missing_step:
                    errors.append(f"sections[{section_index}].steps[{step_index}] missing required fields: {', '.join(sorted(missing_step))}.")
                raw_group_refs = item.get("groupRefs")
                group_refs = tuple(str(ref or "").strip() for ref in raw_group_refs) if isinstance(raw_group_refs, list) else ()
                certainty = str(item.get("certainty") or "").strip()
                text = str(item.get("text") or "").strip() if isinstance(item.get("text"), str) else ""
                if not group_refs:
                    errors.append(f"sections[{section_index}].steps[{step_index}].groupRefs must be a non-empty list.")
                    continue
                if any(not ref for ref in group_refs):
                    errors.append(f"sections[{section_index}].steps[{step_index}].groupRefs must contain only non-empty strings.")
                for ref in group_refs:
                    if ref in seen:
                        errors.append(f"groupRef {ref} appears more than once.")
                    seen.add(ref)
                    if ref not in group_by_ref:
                        errors.append(f"groupRef {ref} was not supplied.")
                    elif section_by_group_ref.get(ref) != section_ref:
                        errors.append(f"groupRef {ref} does not belong to sectionRef {section_ref}.")
                supplied = [group_by_ref[ref] for ref in group_refs if ref in group_by_ref]
                if supplied:
                    indexes = [group_index[group.group_ref] for group in supplied]
                    if indexes != sorted(indexes):
                        errors.append(f"groupRefs {', '.join(group_refs)} must preserve supplied group order.")
                    if indexes and min(indexes) <= last_index:
                        errors.append("steps must preserve canonical group order.")
                    if indexes:
                        last_index = max(last_index, max(indexes))
                    expected_range = list(range(min(indexes), max(indexes) + 1))
                    if indexes != expected_range:
                        errors.append(f"groupRefs {', '.join(group_refs)} must form one contiguous ordered range.")
                    certainties = {group.certainty for group in supplied}
                    if len(certainties) != 1:
                        errors.append(f"groupRefs {', '.join(group_refs)} must not combine different certainty values.")
                    elif certainty != supplied[0].certainty:
                        errors.append(f"groupRefs {', '.join(group_refs)} certainty must be {supplied[0].certainty}.")
                    merge_scopes = {group.merge_scope for group in supplied}
                    if len(merge_scopes) != 1:
                        errors.append(f"groupRefs {', '.join(group_refs)} must share one structural merge scope.")
                    branch_paths = {group.branch_path for group in supplied}
                    if len(branch_paths) != 1:
                        errors.append(f"groupRefs {', '.join(group_refs)} must not combine separate branch paths.")
                    gap_flags = {group.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.AMBIGUOUS_GAP} for group in supplied}
                    if len(gap_flags) != 1:
                        errors.append(f"groupRefs {', '.join(group_refs)} must not combine a gap with verified execution.")
                if not text:
                    errors.append(f"groupRefs {', '.join(group_refs)} text must be non-empty.")
                parsed_steps.append(FlowFormatterStepText(group_refs=group_refs, certainty=certainty, text=text))

        if actual_section_refs != required_section_refs:
            errors.append("sections must appear exactly once in the supplied section order.")
        missing_refs = [ref for ref in required_refs if ref not in seen]
        if missing_refs:
            errors.append(f"Missing groupRefs: {', '.join(missing_refs)}.")
        foreign_or_empty_sections = [ref for ref in actual_section_refs if not ref or ref not in section_by_ref]
        duplicate_sections = sorted({ref for ref in actual_section_refs if ref and actual_section_refs.count(ref) > 1})
        if foreign_or_empty_sections:
            errors.append("sections must not include empty or foreign sectionRefs.")
        if duplicate_sections:
            errors.append(f"sectionRefs appear more than once: {', '.join(duplicate_sections)}.")
        self._validate_text(parsed_steps, required_groups, segment.response_language, errors)
        if errors:
            raise FlowFormatterContractViolation(errors)
        return tuple(parsed_steps)

    def _validate_text(
        self,
        steps: Sequence[FlowFormatterStepText],
        groups: Sequence[FlowFormatterGroup],
        response_language: str,
        errors: list[str],
    ) -> None:
        all_text = "\n".join(step.text for step in steps)
        language_result = self.language_validator.validate(all_text, response_language)
        if not language_result.valid:
            errors.extend(language_result.errors)
        all_allowed_routes = {value for group in groups for value in self._group_routes(group)}
        local_refs = [group.group_ref for group in groups]
        for step in steps:
            text = step.text
            for group_ref in local_refs:
                if re.search(rf"(?<!\w){re.escape(group_ref)}(?!\w)", text):
                    errors.append(f"groupRef {group_ref} text exposes a response-local ref.")
            for route in _ROUTE_RE.findall(text):
                normalized_route = route.rstrip(".,;:)")
                if normalized_route not in all_allowed_routes:
                    errors.append(f"groupRefs {', '.join(step.group_refs)} text contains unsupported route {route!r}.")

    def _group_routes(self, group: FlowFormatterGroup) -> tuple[str, ...]:
        return tuple(value for value in (group.route, group.target_descriptor) if isinstance(value, str) and value.startswith("/"))


class FlowFormatterStitcher:
    def stitch(self, sections: Sequence[FlowPresentationSection], steps: Sequence[FlowFormatterStepText]) -> str:
        group_by_ref = {group.group_ref: group for section in sections for group in section.ordered_groups}
        section_by_ref = {section.section_ref: section for section in sections}
        counters: list[int] = []
        lines: list[str] = []
        rendered_sections: set[str] = set()
        for step in steps:
            group = group_by_ref[step.group_refs[0]]
            section_ref = group.section_ref or ""
            if section_ref and section_ref not in rendered_sections:
                heading = self._heading(section_by_ref.get(section_ref))
                if lines:
                    lines.append("")
                if heading:
                    lines.append(heading)
                rendered_sections.add(section_ref)
            depth = max(0, int(group.depth))
            while len(counters) <= depth:
                counters.append(0)
            counters[depth] += 1
            del counters[depth + 1 :]
            number = ".".join(str(item) for item in counters[: depth + 1]) + "."
            indent = "   " * depth
            lines.append(f"{indent}{number} {step.text.strip()}")
        return "\n".join(lines).strip()

    def _heading(self, section: FlowPresentationSection | None) -> str:
        if section is None:
            return ""
        values = [value for value in (section.source, section.entrypoint) if value]
        return " · ".join(values)


class FlowFormatterAnswerService:
    def __init__(
        self,
        provider: Any,
        *,
        plan_builder: FlowFormatterPlanBuilder | None = None,
        segment_planner: FlowFormatterSegmentPlanner | None = None,
        prompt_renderer: FlowFormatterPromptRenderer | None = None,
        validator: FlowFormatterResponseValidator | None = None,
        stitcher: FlowFormatterStitcher | None = None,
        request_deadline_seconds: float = DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        provider_name: str | None = None,
        provider_model: str | None = None,
        audit_max_records: int = 200,
    ) -> None:
        self.provider = provider
        self.plan_builder = plan_builder or FlowFormatterPlanBuilder()
        self.segment_planner = segment_planner or FlowFormatterSegmentPlanner()
        self.prompt_renderer = prompt_renderer or FlowFormatterPromptRenderer()
        self.validator = validator or FlowFormatterResponseValidator()
        self.stitcher = stitcher or FlowFormatterStitcher()
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS))
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.audit_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.pipeline_records: list[Dict[str, Any]] = []
        self.current_stage: str | None = None
        self._provider_call_count = 0
        self._repair_call_count = 0
        self._output_split_count = 0
        self._formatter_duration_ms = 0.0
        self._stitching_duration_ms = 0.0
        self._serialization_count = 0
        self._executed_segment_count = 0

    def answer(
        self,
        request: KnowledgeQueryRequest,
        execution: Any,
        *,
        plan: QueryRetrievalPlan,
        deadline_at: float | None = None,
        cancel_event: Any | None = None,
    ) -> FlowFormatterAnswerResult:
        if deadline_at is None:
            deadline_at = time.monotonic() + self.request_deadline_seconds
        self._reset_metrics()
        narrative_plans = tuple(getattr(execution, "narrative_plans", ()) or ())
        if not narrative_plans:
            return FlowFormatterAnswerResult(
                answer_language=plan.response_language,
                answers=(),
                diagnostics=(),
                metrics=self._metrics((), (), 0.0, answer_count=0),
            )
        answers: list[FlowFormatterAnswer] = []
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        formatter_plans: list[FlowFormatterPlan] = []
        total_planning_ms = 0.0
        used_language = plan.response_language
        for narrative_index, narrative_plan in enumerate(narrative_plans, start=1):
            self._check_cancelled(cancel_event)
            self.current_stage = "FORMATTER_PLAN_BUILDING"
            formatter_plan = self.plan_builder.plan(narrative_plan, response_language=plan.response_language)
            formatter_plans.append(formatter_plan)
            total_planning_ms += formatter_plan.planning_duration_ms
            self.current_stage = "FORMATTER_SEGMENT_PLANNING"
            segments = self.segment_planner.plan(formatter_plan)
            self._serialization_count += self.segment_planner.serialization_count
            try:
                self.current_stage = "FINAL_FORMATTER"
                steps: list[FlowFormatterStepText] = []
                stitch_sections: list[FlowPresentationSection] = []
                for segment in segments:
                    self._check_cancelled(cancel_event)
                    if time.monotonic() >= deadline_at:
                        raise FlowFormatterDeadlineExceeded("final formatter deadline exceeded")
                    segment_steps, segment_sections = self._format_segment(
                        request,
                        segment,
                        deadline_at,
                        cancel_event=cancel_event,
                    )
                    steps.extend(segment_steps)
                    stitch_sections.extend(segment_sections)
                self.current_stage = "FORMATTER_STITCHING"
                stitching_started = time.perf_counter()
                text = self.stitcher.stitch(tuple(stitch_sections), steps)
                self._stitching_duration_ms += round((time.perf_counter() - stitching_started) * 1000, 3)
                answers.append(
                    FlowFormatterAnswer(
                        source=formatter_plan.source,
                        entrypoint=formatter_plan.entrypoint,
                        text=text,
                        plan=formatter_plan,
                    )
                )
            except FlowFormatterDeadlineExceeded:
                self.pipeline_records.append(self._metrics(formatter_plans, narrative_plans, total_planning_ms, answer_count=len(answers)))
                raise
            except FlowFormatterError as exc:
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FLOW_FORMATTER_PLAN_FAILED",
                        message="One independent answer could not be formatted by the final formatter.",
                        severity="ERROR",
                        sourceId=formatter_plan.source or None,
                        metadata={
                            "entrypoint": formatter_plan.entrypoint,
                            "narrativePlanIndex": narrative_index,
                            "errorClass": type(exc).__name__,
                        },
                    )
                )
                continue
        if narrative_plans and not answers:
            self.pipeline_records.append(self._metrics(formatter_plans, narrative_plans, total_planning_ms, answer_count=0))
            raise FlowFormatterAllPlansFailed("no independent formatter plan succeeded")
        metrics = self._metrics(formatter_plans, narrative_plans, total_planning_ms, answer_count=len(answers))
        self.pipeline_records.append(metrics)
        self.current_stage = "SUCCESS"
        return FlowFormatterAnswerResult(
            answer_language=used_language,
            answers=tuple(answers),
            diagnostics=tuple(diagnostics),
            metrics=metrics,
        )

    def to_response(self, result: FlowFormatterAnswerResult) -> KnowledgeHumanQueryResponse:
        return KnowledgeHumanQueryResponse(
            answerLanguage=result.answer_language,
            answers=[
                KnowledgeFlowAnswer(source=answer.source, entrypoint=answer.entrypoint, text=answer.text)
                for answer in result.answers
            ],
            diagnostics=list(result.diagnostics),
        )

    def _format_segment(
        self,
        request: KnowledgeQueryRequest,
        segment: FlowFormatterSegment,
        deadline_at: float,
        *,
        cancel_event: Any | None,
    ) -> tuple[tuple[FlowFormatterStepText, ...], tuple[FlowPresentationSection, ...]]:
        validation_errors: Sequence[str] | None = None
        formatter_input = segment.to_prompt_input(request.queryText)
        for attempt_count in _REPAIRABLE_ATTEMPTS:
            self._check_cancelled(cancel_event)
            result = self._complete_segment(formatter_input, segment, deadline_at, validation_errors, attempt_count)
            if result.truncated:
                split = self.segment_planner.split_segment(segment)
                if not split:
                    raise FlowFormatterSegmentFailed("formatter response was truncated for an indivisible segment")
                self._output_split_count += 1
                split_steps: list[FlowFormatterStepText] = []
                split_sections: list[FlowPresentationSection] = []
                for child_segment in split:
                    child_steps, child_sections = self._format_segment(
                        request,
                        child_segment,
                        deadline_at,
                        cancel_event=cancel_event,
                    )
                    split_steps.extend(child_steps)
                    split_sections.extend(child_sections)
                return tuple(split_steps), tuple(split_sections)
            try:
                return self.validator.validate(result.raw_text, segment), segment.sections
            except FlowFormatterContractViolation as exc:
                if self.audit_records:
                    self.audit_records[-1]["postValidationErrors"] = list(exc.errors)
                if attempt_count == 1:
                    validation_errors = exc.errors
                    self._repair_call_count += 1
                    continue
                raise FlowFormatterSegmentFailed("formatter response repair failed validation") from exc
        raise FlowFormatterSegmentFailed("formatter response repair failed validation")

    def _complete_segment(
        self,
        formatter_input: Mapping[str, Any],
        segment: FlowFormatterSegment,
        deadline_at: float,
        validation_errors: Sequence[str] | None,
        attempt_count: int,
    ) -> FlowFormatterProviderResult:
        remaining_before = max(0.0, deadline_at - time.monotonic())
        if remaining_before <= self.min_call_timeout_seconds:
            raise FlowFormatterDeadlineExceeded("final formatter deadline exceeded")
        prompt = self.prompt_renderer.render(formatter_input, validation_errors)
        started = time.perf_counter()
        self._provider_call_count += 1
        self._executed_segment_count += 1 if attempt_count == 1 else 0
        try:
            result = self.provider.complete(formatter_input, validation_errors=validation_errors, timeout_seconds=remaining_before)
        except (TimeoutError, httpx.TimeoutException) as exc:
            duration_ms = round((time.perf_counter() - started) * 1000, 3)
            self._formatter_duration_ms += duration_ms
            self._record_audit(
                prompt,
                "",
                segment,
                attempt_count=attempt_count,
                duration_ms=duration_ms,
                remaining_before=remaining_before,
                remaining_after=max(0.0, deadline_at - time.monotonic()),
                validation_errors=validation_errors,
                truncated=True,
                error_class=type(exc).__name__,
            )
            raise FlowFormatterDeadlineExceeded("final formatter deadline exceeded") from exc
        except Exception as exc:
            duration_ms = round((time.perf_counter() - started) * 1000, 3)
            self._formatter_duration_ms += duration_ms
            self._record_audit(
                prompt,
                "",
                segment,
                attempt_count=attempt_count,
                duration_ms=duration_ms,
                remaining_before=remaining_before,
                remaining_after=max(0.0, deadline_at - time.monotonic()),
                validation_errors=validation_errors,
                truncated=False,
                error_class=type(exc).__name__,
            )
            raise FlowFormatterProviderUnavailable(str(type(exc).__name__)) from exc
        duration_ms = round((time.perf_counter() - started) * 1000, 3)
        self._formatter_duration_ms += duration_ms
        if time.monotonic() > deadline_at + _DEADLINE_COMPLETION_GRACE_SECONDS:
            raise FlowFormatterDeadlineExceeded("final formatter deadline exceeded")
        self._record_audit(
            prompt,
            getattr(result, "raw_text", ""),
            segment,
            attempt_count=attempt_count,
            duration_ms=duration_ms,
            remaining_before=remaining_before,
            remaining_after=max(0.0, deadline_at - time.monotonic()),
            validation_errors=validation_errors,
            truncated=bool(getattr(result, "truncated", False)),
        )
        return FlowFormatterProviderResult(
            raw_text=str(getattr(result, "raw_text", "")),
            prompt_char_length=int(getattr(result, "prompt_char_length", len(prompt)) or len(prompt)),
            truncated=bool(getattr(result, "truncated", False)),
        )

    def _record_audit(
        self,
        prompt: str,
        raw_response: str,
        segment: FlowFormatterSegment,
        *,
        attempt_count: int,
        duration_ms: float,
        remaining_before: float,
        remaining_after: float,
        validation_errors: Sequence[str] | None,
        truncated: bool,
        error_class: str | None = None,
    ) -> None:
        record = {
            "provider": self._provider_name(),
            "model": self._provider_model(),
            "promptLength": len(prompt),
            "promptHash": self._sha256(prompt),
            "rawResponseLength": len(str(raw_response or "")),
            "rawResponseHash": self._sha256(str(raw_response or "")),
            "attemptCount": attempt_count,
            "durationMs": duration_ms,
            "remainingDeadlineBeforeCall": round(remaining_before, 3),
            "remainingDeadlineAfterCall": round(remaining_after, 3),
            "responseLanguage": segment.response_language,
            "segmentIndex": segment.segment_index,
            "segmentCount": segment.segment_count,
            "groupCount": len(segment.required_groups),
            "renderedInputTokens": segment.rendered_input_tokens,
            "reservedOutputTokens": segment.reserved_output_tokens,
            "minimumValidOutputTokens": segment.minimum_valid_output_tokens,
            "contextTokens": segment.context_tokens,
            "truncated": truncated,
        }
        if error_class:
            record["errorClass"] = error_class
        if validation_errors:
            record["validationErrors"] = list(validation_errors)
        self.audit_records.append(record)

    def _metrics(
        self,
        formatter_plans: Sequence[FlowFormatterPlan],
        narrative_plans: Sequence[FlowNarrativePlan],
        planning_ms: float,
        *,
        answer_count: int,
    ) -> Dict[str, Any]:
        group_count = sum(plan.group_count for plan in formatter_plans)
        segment_count = max(self._executed_segment_count, 0)
        return {
            "narrativePlanCount": len(narrative_plans),
            "formatterGroupCount": group_count,
            "walkthroughStepCount": group_count,
            "branchCount": sum(plan.branch_count for plan in formatter_plans),
            "gapCount": sum(plan.gap_count for plan in formatter_plans),
            "answerCount": int(answer_count),
            "formatterSegmentCount": segment_count,
            "formatterSerializationCount": self._serialization_count,
            "formatterPlanningDurationMs": round(planning_ms, 3),
            "walkthroughPlanningDurationMs": round(planning_ms, 3),
            "formatterDurationMs": round(self._formatter_duration_ms, 3),
            "totalFormatterDurationMs": round(self._formatter_duration_ms, 3),
            "textRenderingDurationMs": round(self._formatter_duration_ms, 3),
            "stitchingDurationMs": round(self._stitching_duration_ms, 3),
            "formatterProviderCallCount": self._provider_call_count,
            "formatterRepairCallCount": self._repair_call_count,
            "formatterOutputSplitCallCount": self._output_split_count,
            "finalAnswerProviderCallCount": self._provider_call_count,
            "groundingProviderCallCount": 0,
            "analysisProviderCallCount": 0,
            "toolContextFormatterCallCount": 0,
        }

    def _reset_metrics(self) -> None:
        self._provider_call_count = 0
        self._repair_call_count = 0
        self._output_split_count = 0
        self._formatter_duration_ms = 0.0
        self._stitching_duration_ms = 0.0
        self._serialization_count = 0
        self._executed_segment_count = 0

    def _check_cancelled(self, cancel_event: Any | None) -> None:
        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)():
            raise FlowFormatterDeadlineExceeded("final formatter cancelled")

    def _provider_name(self) -> str:
        value = self.provider_name or getattr(self.provider, "name", None)
        return str(value or self.provider.__class__.__name__)

    def _provider_model(self) -> str:
        value = self.provider_model or getattr(self.provider, "model", None)
        return str(value or "")

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


class LocalOllamaFlowFormatterClient:
    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: int,
        context_tokens: int,
        http_client: httpx.Client | None = None,
        renderer: FlowFormatterPromptRenderer | None = None,
    ) -> None:
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS)
        if self.context_tokens < 1024:
            raise ValueError("Flow formatter context_tokens must be at least 1024")
        self.renderer = renderer or FlowFormatterPromptRenderer()
        self._client = http_client or httpx.Client(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))

    def complete(
        self,
        formatter_input: Mapping[str, Any],
        validation_errors: Sequence[str] | None = None,
        timeout_seconds: float | None = None,
    ) -> FlowFormatterProviderResult:
        prompt = self.renderer.render(formatter_input, validation_errors)
        call_timeout = self._call_timeout(timeout_seconds)
        budget = formatter_input.get("promptBudget") if isinstance(formatter_input.get("promptBudget"), dict) else {}
        reserved_output_tokens = max(1, int((budget or {}).get("reservedOutputTokens") or 1))
        call_context_tokens = self._call_context_tokens(budget)
        response = self._client.post(
            f"{self.base_url}/api/generate",
            json={
                "model": self.model,
                "prompt": prompt,
                "stream": False,
                "format": "json",
                "options": {
                    "num_ctx": call_context_tokens,
                    "num_predict": reserved_output_tokens,
                    "temperature": 0,
                },
            },
            timeout=httpx.Timeout(call_timeout, connect=min(5.0, call_timeout)),
        )
        response.raise_for_status()
        raw = response.json()
        response_text = raw.get("response")
        if not isinstance(response_text, str):
            raise ValueError("Ollama returned no response text")
        done_reason = str(raw.get("done_reason") or raw.get("doneReason") or "").strip().lower()
        eval_count = int(raw.get("eval_count") or raw.get("evalCount") or 0)
        truncated = done_reason in {"length", "num_predict", "truncated"} or (eval_count >= reserved_output_tokens and not _looks_complete_json(response_text))
        return FlowFormatterProviderResult(raw_text=response_text, prompt_char_length=len(prompt), truncated=truncated)

    def close(self) -> None:
        self._client.close()

    def _require_localhost(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("Flow formatter LLM base URL must point to localhost")
        return base_url

    def _call_timeout(self, timeout_seconds: float | None) -> float:
        configured = max(0.001, float(self.timeout_seconds or 0.001))
        if timeout_seconds is None:
            return configured
        return max(0.001, min(configured, float(timeout_seconds)))

    def _call_context_tokens(self, budget: Mapping[str, Any] | None) -> int:
        values = budget if isinstance(budget, Mapping) else {}
        required = 0
        for key in ("renderedInputTokens", "reservedOutputTokens", "fixedFramingReserveTokens"):
            try:
                required += max(0, int(values.get(key) or 0))
            except (TypeError, ValueError):
                continue
        if required <= 0:
            return self.context_tokens
        rounded = 1 << max(10, int(required - 1).bit_length())
        return max(1024, min(self.context_tokens, rounded))


def _walk_groups(groups: Sequence[FlowFormatterGroup]) -> Iterable[FlowFormatterGroup]:
    stack = list(reversed(tuple(groups)))
    while stack:
        group = stack.pop()
        yield group
        stack.extend(reversed(group.child_groups))


def _group_payload(group: FlowFormatterGroup, *, include_children: bool = True) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "groupRef": group.group_ref,
        "sectionRef": group.section_ref,
        "order": group.order,
        "depth": group.depth,
        "kind": group.kind.value,
        "certainty": group.certainty,
        "branchPath": group.branch_path,
        "mergeScope": group.merge_scope,
        "source": group.source,
        "sourceDisplayHint": group.source_display_hint,
        "symbol": group.symbol,
        "fromSource": group.from_source,
        "fromSymbol": group.from_symbol,
        "toSource": group.to_source,
        "toSymbol": group.to_symbol,
        "relationKind": group.relation_kind,
        "transportKind": group.transport_kind,
        "method": group.method,
        "route": group.route,
        "topic": group.topic,
        "schedule": group.schedule,
        "operationIdentity": group.operation_identity,
        "interfaceIdentity": group.interface_identity,
        "boundaryKind": group.boundary_kind,
        "targetDescriptor": group.target_descriptor,
        "summary": group.summary,
        "terminalSemantic": group.terminal_semantic,
        "childGroups": [_group_payload(child) for child in group.child_groups] if include_children else [],
    }
    return _without_empty(payload)


def _without_empty(value: Dict[str, Any]) -> Dict[str, Any]:
    result: Dict[str, Any] = {}
    for key, item in value.items():
        if item is None:
            continue
        if isinstance(item, list) and not item:
            continue
        if isinstance(item, dict):
            nested = _without_empty(item)
            if nested:
                result[key] = nested
            continue
        result[key] = item
    return result


def _continuity_payload(continuity: FlowFormatterContinuity | None) -> Dict[str, Any] | None:
    if continuity is None:
        return None
    return _without_empty(
        {
            "source": continuity.source,
            "symbol": continuity.symbol,
            "transitionKind": continuity.transition_kind,
            "gapKind": continuity.gap_kind,
            "transportKind": continuity.transport_kind,
            "method": continuity.method,
            "route": continuity.route,
        }
    )


def _estimate_tokens(value: str) -> int:
    return max(1, int(math.ceil(len(str(value or "")) / 4)))


def _looks_complete_json(value: str) -> bool:
    try:
        json.loads(value)
        return True
    except Exception:
        return False


def resolved_formatter_language(value: str | None, default_language: str = "en") -> str:
    return normalize_response_language(value) or normalize_response_language(default_language) or "en"
