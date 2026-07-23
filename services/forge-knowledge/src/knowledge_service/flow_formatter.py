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
_ALLOWED_FORMATTER_STEP_KEYS = frozenset({"stageRef", "certainty", "assertionSubject", "coveredFactRefs", "text"})
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


class FlowExecutionStageKind(str, Enum):
    EXECUTABLE = "EXECUTABLE"
    STANDALONE_OPERATION = "STANDALONE_OPERATION"
    BOUNDARY = "BOUNDARY"
    UNVERIFIED_GAP = "UNVERIFIED_GAP"
    AMBIGUOUS_GAP = "AMBIGUOUS_GAP"
    STRUCTURAL = "STRUCTURAL"


class FlowPresentationSectionKind(str, Enum):
    VERIFIED_FRAGMENT = "VERIFIED_FRAGMENT"
    UNVERIFIED_GAP = "UNVERIFIED_GAP"
    AMBIGUOUS_GAP = "AMBIGUOUS_GAP"


class FlowAssertionSubject(str, Enum):
    FLOW_EXECUTION = "FLOW_EXECUTION"
    DIRECT_RELATION = "DIRECT_RELATION"
    BOUNDARY_RELATION = "BOUNDARY_RELATION"
    BRANCH_PATH = "BRANCH_PATH"
    CYCLE_REFERENCE = "CYCLE_REFERENCE"
    SHARED_CONTINUATION = "SHARED_CONTINUATION"
    TERMINAL_SEMANTIC = "TERMINAL_SEMANTIC"
    TYPED_RESULT = "TYPED_RESULT"


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
    node_kind: str | None = None
    execution_role: str | None = None
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
    typed_operations: tuple[Dict[str, Any], ...] = ()
    supporting_facts: tuple[Dict[str, Any], ...] = ()
    owned_boundaries: tuple[Dict[str, Any], ...] = ()
    child_groups: tuple["FlowFormatterGroup", ...] = ()
    terminal_semantic: str | None = None
    fragment_ref: str | None = None
    section_ref: str | None = None
    branch_path: str = ""
    assertion_subject: str | None = None
    assertion_status: str | None = None


@dataclass(frozen=True)
class FlowExecutionStage:
    stage_ref: str
    section_ref: str
    order: int
    depth: int
    kind: FlowExecutionStageKind
    certainty: str
    assertion_subject: str
    assertion_status: str
    source: str | None = None
    source_display_hint: str | None = None
    symbol: str | None = None
    node_kind: str | None = None
    execution_role: str | None = None
    incoming: Dict[str, Any] = field(default_factory=dict)
    typed_operations: tuple[Dict[str, Any], ...] = ()
    supporting_facts: tuple[Dict[str, Any], ...] = ()
    owned_summaries: tuple[Dict[str, Any], ...] = ()
    owned_boundaries: tuple[Dict[str, Any], ...] = ()
    outgoing_stage_refs: tuple[str, ...] = ()
    branch_path: str = ""
    terminal_semantic: str | None = None
    owned_fact_refs: tuple[str, ...] = ()
    stage_part_ref: str | None = None
    stage_part_index: int | None = None
    stage_part_count: int | None = None
    source_group_ref: str | None = None
    source_group_kind: FlowFormatterGroupKind | None = None


@dataclass(frozen=True)
class FlowPresentationSection:
    section_ref: str
    kind: FlowPresentationSectionKind
    source: str | None
    entrypoint: str | None
    certainty: str
    ordered_groups: tuple[FlowFormatterGroup, ...]
    stages: tuple[FlowExecutionStage, ...] = ()


@dataclass(frozen=True)
class FlowFormatterPlan:
    source: str
    entrypoint: str
    groups: tuple[FlowFormatterGroup, ...]
    response_language: str
    sections: tuple[FlowPresentationSection, ...] = ()
    stages: tuple[FlowExecutionStage, ...] = ()
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    structural_metrics: Dict[str, Any] = field(default_factory=dict)
    planning_duration_ms: float = 0.0

    @property
    def group_count(self) -> int:
        return sum(1 for _ in self.walk())

    @property
    def presentation_stage_count(self) -> int:
        return len(self.stages)

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

    def walk_stages(self) -> Iterable[FlowExecutionStage]:
        return iter(self.stages)


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
    serialized_stage_tokens: tuple[int, ...] = ()

    @property
    def required_stages(self) -> tuple[FlowExecutionStage, ...]:
        return tuple(stage for section in self.sections for stage in section.stages)

    @property
    def required_groups(self) -> tuple[FlowFormatterGroup, ...]:
        return tuple(group for section in self.sections for group in section.ordered_groups)

    def to_prompt_input(self, original_question: str) -> Dict[str, Any]:
        required = self.required_stages
        sections = [
            {
                "sectionRef": section.section_ref,
                "kind": section.kind.value,
                "source": section.source,
                "entrypoint": section.entrypoint,
                "certainty": section.certainty,
                "stages": [_stage_payload(stage) for stage in section.stages],
            }
            for section in self.sections
        ]
        stage_to_section = {
            stage.stage_ref: section.section_ref
            for section in self.sections
            for stage in section.stages
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
                "requiredStageRefs": [stage.stage_ref for stage in required],
                "certaintyByStageRef": {stage.stage_ref: stage.certainty for stage in required},
                "assertionSubjectByStageRef": {stage.stage_ref: stage.assertion_subject for stage in required},
                "assertionStatusByStageRef": {stage.stage_ref: stage.assertion_status for stage in required},
                "coveredFactRefsByStageRef": {stage.stage_ref: list(stage.owned_fact_refs) for stage in required},
                "sectionByStageRef": stage_to_section,
                "order": [stage.stage_ref for stage in required],
                "stageRefsBySection": {
                    section.section_ref: [stage.stage_ref for stage in section.stages]
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
    stage_ref: str
    certainty: str
    assertion_subject: str
    covered_fact_refs: tuple[str, ...]
    text: str

    @property
    def group_refs(self) -> tuple[str, ...]:
        return (self.stage_ref,)


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
        stages, sections = self._execution_stages(sectioned_groups, sections)
        structural_metrics = self._structural_metrics(stages)
        metrics = {
            "formatterGroupCount": sum(1 for _ in _walk_groups(sectioned_groups)),
            **structural_metrics,
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
            stages=stages,
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
            child_entries: list[tuple[FlowGraphEdge, FlowGraphNode | None, FlowNodeKey | None]] = []
            for edge in outgoing.get(current_key, ()):
                target_key = self._to_key(edge)
                target = node_by_key.get(target_key) if target_key is not None else None
                child_entries.append((edge, target, target_key))
            has_verified_downstream = bool(child_entries)
            groups.append(
                self._node_group(
                    current_node,
                    state,
                    depth=current_depth,
                    root=current_root,
                    incoming=current_incoming,
                    incoming_from_symbol=current_incoming_from_symbol,
                    operations=node_operations,
                    outgoing=[edge for edge, _target, _target_key in child_entries],
                )
            )
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
                        ordered_children.append(self._boundary_group(edge, current_node, state, depth=current_depth))
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
                        depth=current_depth,
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
        outgoing: Sequence[FlowGraphEdge],
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
            node_kind=self._clean(node.node_kind),
            execution_role=self._clean(node.execution_role),
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
            typed_operations=tuple(self._operation_fact_payload(fact) for fact in sorted(operations, key=self._operation_fact_sort_key)),
            supporting_facts=tuple(self._outgoing_transition_payload(edge) for edge in outgoing),
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
            execution_role=self._clean(fact.execution_role),
            transport_kind=normalize_transport_kind(fact.transport_kind),
            method=normalize_http_method(fact.method),
            route=normalize_route(fact.normalized_route),
            topic=self._clean(fact.topic),
            schedule=self._clean(fact.schedule),
            operation_identity=self._clean(fact.operation_identity),
            interface_identity=self._clean(fact.interface_identity),
            target_descriptor=self._clean(fact.target_service_identity),
            typed_operations=(self._operation_fact_payload(fact),),
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
            node_kind=self._clean(owner.node_kind),
            execution_role=self._clean(owner.execution_role),
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
            owned_boundaries=(self._boundary_fact_payload(edge, owner, projection),),
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
    ) -> FlowFormatterGroup:
        return replace(
            group,
            section_ref=section_ref,
            branch_path=branch_path,
            assertion_subject=group.assertion_subject or self._assertion_subject(group),
            assertion_status=group.assertion_status or self._assertion_status(group),
        )

    def _assertion_subject(self, group: FlowFormatterGroup) -> str:
        if group.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.AMBIGUOUS_GAP}:
            return FlowAssertionSubject.DIRECT_RELATION.value
        if group.kind in {FlowFormatterGroupKind.EXPLICIT_BRANCH, FlowFormatterGroupKind.BRANCH_ITEM, FlowFormatterGroupKind.JOIN}:
            return FlowAssertionSubject.BRANCH_PATH.value
        if group.kind is FlowFormatterGroupKind.CYCLE:
            return FlowAssertionSubject.CYCLE_REFERENCE.value
        if group.kind is FlowFormatterGroupKind.SHARED_CONTINUATION:
            return FlowAssertionSubject.SHARED_CONTINUATION.value
        if group.kind is FlowFormatterGroupKind.AVAILABLE_FACTS_END:
            return FlowAssertionSubject.TERMINAL_SEMANTIC.value
        if group.kind is FlowFormatterGroupKind.TYPED_RESULT:
            return FlowAssertionSubject.TYPED_RESULT.value
        if group.kind in {FlowFormatterGroupKind.EXTERNAL_BOUNDARY, FlowFormatterGroupKind.UNRESOLVED_BOUNDARY}:
            return FlowAssertionSubject.BOUNDARY_RELATION.value
        return FlowAssertionSubject.FLOW_EXECUTION.value

    def _assertion_status(self, group: FlowFormatterGroup) -> str:
        if group.kind is FlowFormatterGroupKind.AMBIGUOUS_GAP:
            return AMBIGUOUS
        if group.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.UNRESOLVED_BOUNDARY}:
            return UNVERIFIED
        return group.certainty or VERIFIED

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

    def _execution_stages(
        self,
        groups: tuple[FlowFormatterGroup, ...],
        sections: tuple[FlowPresentationSection, ...],
    ) -> tuple[tuple[FlowExecutionStage, ...], tuple[FlowPresentationSection, ...]]:
        drafts: list[Dict[str, Any]] = []
        terminals: list[FlowFormatterGroup] = []
        for group in _walk_groups(groups):
            if group.kind is FlowFormatterGroupKind.AVAILABLE_FACTS_END:
                terminals.append(group)
                continue
            if group.kind is FlowFormatterGroupKind.ORDERED_CALL_GROUP:
                continue
            stage_ref = f"st{len(drafts) + 1}"
            drafts.append(self._stage_draft(stage_ref, group, len(drafts) + 1))

        for terminal in terminals:
            target = self._terminal_owner(drafts, terminal)
            if target is not None:
                target["terminal_semantic"] = terminal.terminal_semantic or FlowFormatterGroupKind.AVAILABLE_FACTS_END.value
                target["supporting_facts"].append(
                    _without_empty(
                        {
                            "kind": FlowAssertionSubject.TERMINAL_SEMANTIC.value,
                            "terminalSemantic": target["terminal_semantic"],
                        }
                    )
                )

        self._link_outgoing_stage_refs(drafts)
        stages = tuple(self._final_stage(draft, fact_index) for fact_index, draft in self._stage_fact_indexes(drafts))
        stages_by_section: Dict[str, list[FlowExecutionStage]] = defaultdict(list)
        for stage in stages:
            stages_by_section[stage.section_ref].append(stage)
        updated_sections = tuple(
            updated
            for section in sections
            for updated in (replace(section, stages=tuple(stages_by_section.get(section.section_ref, ()))),)
            if updated.stages
        )
        return stages, updated_sections

    def _stage_draft(self, stage_ref: str, group: FlowFormatterGroup, order: int) -> Dict[str, Any]:
        typed_operations = list(group.typed_operations)
        fallback_operation = self._group_operation_payload(group)
        if fallback_operation and fallback_operation not in typed_operations:
            typed_operations.append(fallback_operation)
        incoming = self._incoming_payload(group)
        owned_summaries = [{"kind": "SUMMARY", "summary": group.summary}] if group.summary else []
        supporting_facts = [dict(item) for item in group.supporting_facts]
        owned_boundaries = [dict(item) for item in group.owned_boundaries]
        if group.kind in {FlowFormatterGroupKind.UNVERIFIED_GAP, FlowFormatterGroupKind.AMBIGUOUS_GAP}:
            supporting_facts.append(self._gap_fact_payload(group))
        elif group.kind in {
            FlowFormatterGroupKind.EXPLICIT_BRANCH,
            FlowFormatterGroupKind.BRANCH_ITEM,
            FlowFormatterGroupKind.JOIN,
            FlowFormatterGroupKind.CYCLE,
            FlowFormatterGroupKind.SHARED_CONTINUATION,
        }:
            supporting_facts.append(self._structural_fact_payload(group))
        if not incoming and not typed_operations and not owned_summaries and not supporting_facts and not owned_boundaries:
            supporting_facts.append(self._structural_fact_payload(group))
        return {
            "stage_ref": stage_ref,
            "section_ref": group.section_ref or "s1",
            "order": order,
            "depth": max(0, int(group.depth)),
            "kind": self._stage_kind(group),
            "certainty": group.certainty or VERIFIED,
            "assertion_subject": group.assertion_subject or self._assertion_subject(group),
            "assertion_status": group.assertion_status or self._assertion_status(group),
            "source": group.source,
            "source_display_hint": group.source_display_hint,
            "symbol": group.symbol,
            "node_kind": group.node_kind,
            "execution_role": group.execution_role,
            "incoming": incoming,
            "typed_operations": typed_operations,
            "supporting_facts": supporting_facts,
            "owned_summaries": owned_summaries,
            "owned_boundaries": owned_boundaries,
            "outgoing_stage_refs": [],
            "branch_path": group.branch_path,
            "terminal_semantic": group.terminal_semantic,
            "source_group_ref": group.group_ref,
            "source_group_kind": group.kind,
        }

    def _stage_kind(self, group: FlowFormatterGroup) -> FlowExecutionStageKind:
        if group.kind in {FlowFormatterGroupKind.ENTRYPOINT, FlowFormatterGroupKind.LINEAR_EXECUTION}:
            return FlowExecutionStageKind.EXECUTABLE
        if group.kind is FlowFormatterGroupKind.OPERATION:
            return FlowExecutionStageKind.STANDALONE_OPERATION
        if group.kind in {FlowFormatterGroupKind.EXTERNAL_BOUNDARY, FlowFormatterGroupKind.UNRESOLVED_BOUNDARY}:
            return FlowExecutionStageKind.BOUNDARY
        if group.kind is FlowFormatterGroupKind.UNVERIFIED_GAP:
            return FlowExecutionStageKind.UNVERIFIED_GAP
        if group.kind is FlowFormatterGroupKind.AMBIGUOUS_GAP:
            return FlowExecutionStageKind.AMBIGUOUS_GAP
        return FlowExecutionStageKind.STRUCTURAL

    def _terminal_owner(self, drafts: Sequence[Dict[str, Any]], terminal: FlowFormatterGroup) -> Dict[str, Any] | None:
        for draft in reversed(drafts):
            if draft["kind"] not in {FlowExecutionStageKind.EXECUTABLE, FlowExecutionStageKind.BOUNDARY}:
                continue
            if terminal.source and draft.get("source") and terminal.source != draft.get("source"):
                continue
            if terminal.symbol and draft.get("symbol") and terminal.symbol != draft.get("symbol"):
                continue
            return draft
        for draft in reversed(drafts):
            if draft["kind"] in {FlowExecutionStageKind.EXECUTABLE, FlowExecutionStageKind.BOUNDARY}:
                return draft
        return drafts[-1] if drafts else None

    def _link_outgoing_stage_refs(self, drafts: Sequence[Dict[str, Any]]) -> None:
        for index, draft in enumerate(drafts):
            incoming = draft.get("incoming") if isinstance(draft.get("incoming"), dict) else {}
            from_source = incoming.get("fromSource")
            from_symbol = incoming.get("fromSymbol")
            if not from_symbol:
                continue
            owner = self._nearest_prior_stage(drafts[:index], from_source, from_symbol)
            if owner is None:
                continue
            if draft["stage_ref"] not in owner["outgoing_stage_refs"]:
                owner["outgoing_stage_refs"].append(draft["stage_ref"])
            if draft["kind"] in {FlowExecutionStageKind.UNVERIFIED_GAP, FlowExecutionStageKind.AMBIGUOUS_GAP}:
                continue
            owner["supporting_facts"].append(
                _without_empty(
                    {
                        "kind": "OUTGOING_STAGE",
                        "toSource": draft.get("source") or incoming.get("toSource"),
                        "toSymbol": draft.get("symbol") or incoming.get("toSymbol"),
                        "relationKind": incoming.get("relationKind"),
                        "resolutionStatus": incoming.get("resolutionStatus"),
                    }
                )
            )
        for index, draft in enumerate(drafts):
            if draft["kind"] not in {FlowExecutionStageKind.UNVERIFIED_GAP, FlowExecutionStageKind.AMBIGUOUS_GAP}:
                continue
            relation = draft.get("incoming") if isinstance(draft.get("incoming"), dict) else {}
            target = self._nearest_later_stage(drafts[index + 1 :], relation.get("toSource"), relation.get("toSymbol"))
            if target is not None and target["stage_ref"] not in draft["outgoing_stage_refs"]:
                draft["outgoing_stage_refs"].append(target["stage_ref"])

    def _nearest_prior_stage(
        self,
        drafts: Sequence[Dict[str, Any]],
        source: Any,
        symbol: Any,
    ) -> Dict[str, Any] | None:
        for candidate in reversed(tuple(drafts)):
            if candidate["kind"] is not FlowExecutionStageKind.EXECUTABLE:
                continue
            if source and candidate.get("source") != source:
                continue
            if candidate.get("symbol") == symbol:
                return candidate
        return None

    def _nearest_later_stage(
        self,
        drafts: Sequence[Dict[str, Any]],
        source: Any,
        symbol: Any,
    ) -> Dict[str, Any] | None:
        for candidate in drafts:
            if candidate["kind"] is not FlowExecutionStageKind.EXECUTABLE:
                continue
            if source and candidate.get("source") != source:
                continue
            if not symbol or candidate.get("symbol") == symbol:
                return candidate
        return None

    def _stage_fact_indexes(self, drafts: Sequence[Dict[str, Any]]) -> tuple[tuple[int, Dict[str, Any]], ...]:
        return tuple((index, draft) for index, draft in enumerate(drafts, start=1))

    def _final_stage(self, draft: Dict[str, Any], stage_index: int) -> FlowExecutionStage:
        fact_counter = 0
        owned_fact_refs: list[str] = []

        def assign(payload: Dict[str, Any]) -> Dict[str, Any]:
            nonlocal fact_counter
            fact_counter += 1
            ref = f"f{stage_index}_{fact_counter}"
            owned_fact_refs.append(ref)
            result = dict(payload)
            result["factRef"] = ref
            return result

        incoming = assign(draft["incoming"]) if draft.get("incoming") else {}
        typed_operations = tuple(assign(dict(item)) for item in draft.get("typed_operations", ()) if item)
        supporting_facts = tuple(assign(dict(item)) for item in draft.get("supporting_facts", ()) if item)
        owned_summaries = tuple(assign(dict(item)) for item in draft.get("owned_summaries", ()) if item)
        owned_boundaries = tuple(assign(dict(item)) for item in draft.get("owned_boundaries", ()) if item)
        return FlowExecutionStage(
            stage_ref=draft["stage_ref"],
            section_ref=draft["section_ref"],
            order=int(draft["order"]),
            depth=int(draft["depth"]),
            kind=draft["kind"],
            certainty=draft["certainty"],
            assertion_subject=draft["assertion_subject"],
            assertion_status=draft["assertion_status"],
            source=draft.get("source"),
            source_display_hint=draft.get("source_display_hint"),
            symbol=draft.get("symbol"),
            node_kind=draft.get("node_kind"),
            execution_role=draft.get("execution_role"),
            incoming=incoming,
            typed_operations=typed_operations,
            supporting_facts=supporting_facts,
            owned_summaries=owned_summaries,
            owned_boundaries=owned_boundaries,
            outgoing_stage_refs=tuple(draft.get("outgoing_stage_refs", ())),
            branch_path=draft.get("branch_path") or "",
            terminal_semantic=draft.get("terminal_semantic"),
            owned_fact_refs=tuple(owned_fact_refs),
            source_group_ref=draft.get("source_group_ref"),
            source_group_kind=draft.get("source_group_kind"),
        )

    def _structural_metrics(self, stages: Sequence[FlowExecutionStage]) -> Dict[str, Any]:
        stage_refs = [stage.stage_ref for stage in stages]
        fact_refs = [fact_ref for stage in stages for fact_ref in stage.owned_fact_refs]
        selected_executable_stages = [stage for stage in stages if stage.kind is FlowExecutionStageKind.EXECUTABLE]
        standalone_operation_count = sum(1 for stage in stages if stage.kind is FlowExecutionStageKind.STANDALONE_OPERATION)
        gap_count = sum(1 for stage in stages if stage.kind in {FlowExecutionStageKind.UNVERIFIED_GAP, FlowExecutionStageKind.AMBIGUOUS_GAP})
        boundary_count = sum(1 for stage in stages if stage.kind is FlowExecutionStageKind.BOUNDARY)
        structural_count = sum(1 for stage in stages if stage.kind is FlowExecutionStageKind.STRUCTURAL)
        presentation_count = len(stages)
        expected_count = len(selected_executable_stages) + standalone_operation_count + gap_count + boundary_count + structural_count
        stage_refs_by_section: dict[str, list[str]] = defaultdict(list)
        for stage in stages:
            stage_refs_by_section[stage.section_ref].append(stage.stage_ref)
        presentation_stages = [_stage_payload(stage) for stage in stages]
        stage_ownership_records = [
            {
                "stageRef": stage.stage_ref,
                "sectionRef": stage.section_ref,
                "order": stage.order,
                "kind": stage.kind.value,
                "certainty": stage.certainty,
                "assertionSubject": stage.assertion_subject,
                "source": stage.source,
                "symbol": stage.symbol,
                "ownedFactRefs": list(stage.owned_fact_refs),
            }
            for stage in stages
        ]
        return {
            "selectedExecutableNodeCount": len(selected_executable_stages),
            "selectedExecutableStageRefs": [stage.stage_ref for stage in selected_executable_stages],
            "selectedExecutableSymbols": [stage.symbol for stage in selected_executable_stages if stage.symbol],
            "selectedExecutableStages": [
                {
                    "stageRef": stage.stage_ref,
                    "sectionRef": stage.section_ref,
                    "order": stage.order,
                    "source": stage.source,
                    "symbol": stage.symbol,
                }
                for stage in selected_executable_stages
            ],
            "standaloneOperationStageCount": standalone_operation_count,
            "gapStageCount": gap_count,
            "boundaryStageCount": boundary_count,
            "structuralStageCount": structural_count,
            "presentationStageCount": presentation_count,
            "publicStepCount": presentation_count,
            "stageCountContractExpected": expected_count,
            "stageCountContractMatched": expected_count == presentation_count,
            "expectedPresentationStageCount": presentation_count,
            "presentationStageRefs": stage_refs,
            "presentationStages": presentation_stages,
            "stageRefsBySection": dict(stage_refs_by_section),
            "stageOwnershipRecords": stage_ownership_records,
            "stageOwnershipMap": {stage.stage_ref: list(stage.owned_fact_refs) for stage in stages},
            "ownedFactRefsByStageRef": {stage.stage_ref: list(stage.owned_fact_refs) for stage in stages},
            "factOwnerByFactRef": {fact_ref: stage.stage_ref for stage in stages for fact_ref in stage.owned_fact_refs},
            "missingStageRefs": 0,
            "duplicateStageRefs": len(stage_refs) - len(set(stage_refs)),
            "unownedFactRefs": 0,
            "duplicateFactRefs": len(fact_refs) - len(set(fact_refs)),
        }

    def _incoming_payload(self, group: FlowFormatterGroup) -> Dict[str, Any]:
        to_source = group.to_source or group.source
        to_symbol = group.to_symbol or group.symbol
        return _without_empty(
            {
                "fromSource": group.from_source,
                "fromSymbol": group.from_symbol,
                "toSource": to_source,
                "toSymbol": to_symbol,
                "relationKind": group.relation_kind,
                "resolutionStatus": group.resolution_status,
                "transportKind": group.transport_kind,
                "method": group.method,
                "route": group.route,
                "topic": group.topic,
                "schedule": group.schedule,
                "operationIdentity": group.operation_identity,
                "interfaceIdentity": group.interface_identity,
                "boundaryKind": group.boundary_kind,
                "targetDescriptor": group.target_descriptor,
            }
        )

    def _group_operation_payload(self, group: FlowFormatterGroup) -> Dict[str, Any]:
        payload = _without_empty(
            {
                "kind": "TYPED_OPERATION",
                "source": group.source,
                "symbol": group.symbol,
                "executionRole": group.execution_role,
                "transportKind": group.transport_kind,
                "method": group.method,
                "route": group.route,
                "topic": group.topic,
                "schedule": group.schedule,
                "operationIdentity": group.operation_identity,
                "interfaceIdentity": group.interface_identity,
                "targetDescriptor": group.target_descriptor,
            }
        )
        meaningful_keys = set(payload) - {"kind", "source", "symbol"}
        return payload if meaningful_keys else {}

    def _gap_fact_payload(self, group: FlowFormatterGroup) -> Dict[str, Any]:
        return _without_empty(
            {
                "kind": group.kind.value,
                "fromSource": group.from_source,
                "fromSymbol": group.from_symbol,
                "toSource": group.to_source,
                "toSymbol": group.to_symbol,
                "transportKind": group.transport_kind,
                "method": group.method,
                "route": group.route,
                "operationIdentity": group.operation_identity,
                "targetDescriptor": group.target_descriptor,
                "assertionSubject": FlowAssertionSubject.DIRECT_RELATION.value,
                "assertionStatus": group.assertion_status or self._assertion_status(group),
            }
        )

    def _structural_fact_payload(self, group: FlowFormatterGroup) -> Dict[str, Any]:
        return _without_empty(
            {
                "kind": group.kind.value,
                "source": group.source,
                "symbol": group.symbol,
                "fromSource": group.from_source,
                "fromSymbol": group.from_symbol,
                "toSource": group.to_source,
                "toSymbol": group.to_symbol,
                "relationKind": group.relation_kind,
                "resolutionStatus": group.resolution_status,
                "branchPath": group.branch_path,
            }
        )

    def _outgoing_transition_payload(self, edge: FlowGraphEdge) -> Dict[str, Any]:
        return _without_empty(
            {
                "kind": "OUTGOING_RELATION",
                "fromSource": edge.source_id,
                "toSource": edge.to_source_id or edge.source_id,
                "toSymbol": self._edge_target(edge),
                "relationKind": edge.edge_type,
                "resolutionStatus": edge.resolution_status,
            }
        )

    def _boundary_fact_payload(self, edge: FlowGraphEdge, owner: FlowGraphNode, projection: Any) -> Dict[str, Any]:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        return _without_empty(
            {
                "kind": "BOUNDARY_RELATION",
                "fromSource": owner.source_id,
                "fromSymbol": self._symbol(owner),
                "toSource": edge.to_source_id,
                "toSymbol": self._edge_target(edge),
                "relationKind": edge.edge_type,
                "resolutionStatus": projection.resolution_status,
                "transportKind": normalize_transport_kind(metadata.get("transportKind") or metadata.get("connectorKind")),
                "method": normalize_http_method(metadata.get("httpMethod") or metadata.get("method")),
                "route": normalize_route(metadata.get("routeTemplate") or metadata.get("route")),
                "topic": self._clean(metadata.get("topic") if isinstance(metadata.get("topic"), str) else None),
                "schedule": self._clean(metadata.get("schedule") if isinstance(metadata.get("schedule"), str) else None),
                "operationIdentity": self._clean(metadata.get("operationIdentity") if isinstance(metadata.get("operationIdentity"), str) else None),
                "interfaceIdentity": self._clean(metadata.get("interfaceIdentity") if isinstance(metadata.get("interfaceIdentity"), str) else None),
                "boundaryKind": projection.kind.value,
                "targetDescriptor": self._clean(projection.target) or self._edge_target(edge),
            }
        )

    def _operation_fact_payload(self, fact: AvailableOperationFact) -> Dict[str, Any]:
        return _without_empty(
            {
                "kind": "TYPED_OPERATION",
                "source": fact.source_id,
                "ownerSource": fact.owner_source_id,
                "ownerSymbol": self._compact_symbol(fact.owner_qualified_name),
                "executionRole": self._clean(fact.execution_role),
                "transportKind": normalize_transport_kind(fact.transport_kind),
                "directionRole": self._clean(fact.direction_role),
                "method": normalize_http_method(fact.method),
                "route": normalize_route(fact.normalized_route),
                "topic": self._clean(fact.topic),
                "schedule": self._clean(fact.schedule),
                "operationIdentity": self._clean(fact.operation_identity),
                "interfaceIdentity": self._clean(fact.interface_identity),
                "requestContractIdentity": self._clean(fact.request_contract_identity),
                "responseContractIdentity": self._clean(fact.response_contract_identity),
                "targetServiceIdentity": self._clean(fact.target_service_identity),
                "sourceChannel": self._clean(fact.source_channel),
            }
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
        serialized = {self._stage_key(stage): self._stage_tokens(stage) for section in candidate_sections for stage in section.stages}
        segments: list[tuple[FlowPresentationSection, ...]] = []
        current: list[FlowPresentationSection] = []
        for section in candidate_sections:
            single_serialized = {self._stage_key(stage): self._known_stage_tokens(stage, serialized) for stage in section.stages}
            if not self._fits((section,), single_serialized):
                raise FlowFormatterBudgetError("formatter stage cannot fit within the configured context budget")
            candidate = tuple([*current, section])
            if current and (not self._fits(candidate, serialized) or self._has_duplicate_stage_or_section_ref(candidate)):
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
                    serialized_stage_tokens=tuple(self._known_stage_tokens(stage, serialized) for section in sections for stage in section.stages),
                )
            )
        return tuple(segments)

    def _segments_from_existing(
        self,
        segment: FlowFormatterSegment,
        section_segments: tuple[tuple[FlowPresentationSection, ...], ...],
    ) -> tuple[FlowFormatterSegment, ...]:
        serialized = {
            self._stage_key(stage): self._stage_tokens(stage)
            for sections in section_segments
            for section in sections
            for stage in section.stages
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
                    serialized_stage_tokens=tuple(self._known_stage_tokens(stage, serialized) for section in sections for stage in section.stages),
                )
            )
        return tuple(result)

    def _ensure_sections(self, formatter_plan: FlowFormatterPlan) -> FlowFormatterPlan:
        if formatter_plan.sections and all(section.stages for section in formatter_plan.sections):
            return formatter_plan
        groups, sections = FlowFormatterPlanBuilder()._presentation_sections(formatter_plan.groups)
        stages, sections = FlowFormatterPlanBuilder()._execution_stages(groups, sections)
        return replace(formatter_plan, groups=groups, sections=sections, stages=stages)

    def _partition_oversized_section(self, section: FlowPresentationSection) -> tuple[FlowPresentationSection, ...]:
        serialized = {self._stage_key(stage): self._stage_tokens(stage) for stage in section.stages}
        if self._fits((section,), serialized):
            return (section,)
        if section.kind in {FlowPresentationSectionKind.UNVERIFIED_GAP, FlowPresentationSectionKind.AMBIGUOUS_GAP}:
            raise FlowFormatterBudgetError("formatter gap stage cannot fit within the configured context budget")

        result: list[FlowPresentationSection] = []
        current: list[FlowExecutionStage] = []
        for stage in section.stages:
            single_section = replace(section, stages=(stage,), ordered_groups=())
            single_serialized = {self._stage_key(stage): self._known_stage_tokens(stage, serialized)}
            if not self._fits((single_section,), single_serialized):
                if current:
                    result.append(replace(section, stages=tuple(current), ordered_groups=()))
                    current = []
                result.extend(replace(section, stages=(part,), ordered_groups=()) for part in self._split_stage_to_fit(section, stage))
                continue
            candidate = [*current, stage]
            candidate_section = replace(section, stages=tuple(candidate), ordered_groups=())
            candidate_serialized = {self._stage_key(item): self._known_stage_tokens(item, serialized) for item in candidate}
            if current and not self._fits((candidate_section,), candidate_serialized):
                result.append(replace(section, stages=tuple(current), ordered_groups=()))
                current = [stage]
                continue
            current = candidate
        if current:
            result.append(replace(section, stages=tuple(current), ordered_groups=()))
        return tuple(result)

    def _split_stage_to_fit(self, section: FlowPresentationSection, stage: FlowExecutionStage) -> tuple[FlowExecutionStage, ...]:
        fact_refs = tuple(stage.owned_fact_refs)
        if len(fact_refs) <= 1:
            raise FlowFormatterBudgetError("formatter stage fact cannot fit within the configured context budget")
        parts: list[tuple[str, ...]] = []
        current: list[str] = []
        for fact_ref in fact_refs:
            candidate_refs = tuple([*current, fact_ref])
            candidate = self._stage_part(stage, candidate_refs, part_index=1, part_count=1)
            candidate_section = replace(section, stages=(candidate,), ordered_groups=())
            serialized = {self._stage_key(candidate): self._stage_tokens(candidate)}
            if not self._fits((candidate_section,), serialized):
                if not current:
                    raise FlowFormatterBudgetError("formatter stage fact cannot fit within the configured context budget")
                parts.append(tuple(current))
                current = [fact_ref]
                single = self._stage_part(stage, tuple(current), part_index=1, part_count=1)
                single_section = replace(section, stages=(single,), ordered_groups=())
                single_serialized = {self._stage_key(single): self._stage_tokens(single)}
                if not self._fits((single_section,), single_serialized):
                    raise FlowFormatterBudgetError("formatter stage fact cannot fit within the configured context budget")
                continue
            current.append(fact_ref)
        if current:
            parts.append(tuple(current))
        count = len(parts)
        return tuple(self._stage_part(stage, refs, part_index=index, part_count=count) for index, refs in enumerate(parts, start=1))

    def _stage_part(
        self,
        stage: FlowExecutionStage,
        fact_refs: tuple[str, ...],
        *,
        part_index: int,
        part_count: int,
    ) -> FlowExecutionStage:
        allowed = set(fact_refs)

        def fact_selected(item: Mapping[str, Any]) -> bool:
            return str(item.get("factRef") or "") in allowed

        incoming = dict(stage.incoming) if stage.incoming and fact_selected(stage.incoming) else {}
        return replace(
            stage,
            incoming=incoming,
            typed_operations=tuple(dict(item) for item in stage.typed_operations if fact_selected(item)),
            supporting_facts=tuple(dict(item) for item in stage.supporting_facts if fact_selected(item)),
            owned_summaries=tuple(dict(item) for item in stage.owned_summaries if fact_selected(item)),
            owned_boundaries=tuple(dict(item) for item in stage.owned_boundaries if fact_selected(item)),
            owned_fact_refs=fact_refs,
            stage_part_ref=f"{stage.stage_ref}p{part_index}",
            stage_part_index=part_index,
            stage_part_count=part_count,
        )

    def _split_section(self, section: FlowPresentationSection) -> tuple[FlowPresentationSection, ...]:
        stages = section.stages
        if len(stages) <= 1 or section.kind in {FlowPresentationSectionKind.UNVERIFIED_GAP, FlowPresentationSectionKind.AMBIGUOUS_GAP}:
            return (section,)
        midpoint = max(1, len(stages) // 2)
        return (
            replace(section, stages=stages[:midpoint], ordered_groups=()),
            replace(section, stages=stages[midpoint:], ordered_groups=()),
        )

    def _fits(self, sections: Sequence[FlowPresentationSection], serialized: Mapping[str, int]) -> bool:
        rendered_input = self._input_tokens(sections, serialized)
        minimum_output = self._minimum_output_tokens(sections)
        reserved_output = self._reserved_output_tokens(sections, minimum_output)
        return rendered_input + reserved_output + self.framing_reserve_tokens <= self.context_tokens and minimum_output <= reserved_output

    def _stage_tokens(self, stage: FlowExecutionStage) -> int:
        self.serialization_count += 1
        return _estimate_tokens(json.dumps(_stage_payload(stage), ensure_ascii=False, sort_keys=True, separators=(",", ":")))

    def _known_stage_tokens(self, stage: FlowExecutionStage, serialized: Mapping[str, int]) -> int:
        key = self._stage_key(stage)
        if key in serialized:
            return serialized[key]
        return self._stage_tokens(stage)

    def _stage_key(self, stage: FlowExecutionStage) -> str:
        return stage.stage_part_ref or stage.stage_ref

    def _has_duplicate_stage_or_section_ref(self, sections: Sequence[FlowPresentationSection]) -> bool:
        section_refs = [section.section_ref for section in sections]
        stage_refs = [stage.stage_ref for section in sections for stage in section.stages]
        return len(section_refs) != len(set(section_refs)) or len(stage_refs) != len(set(stage_refs))

    def _input_tokens(self, sections: Sequence[FlowPresentationSection], serialized: Mapping[str, int]) -> int:
        stage_tokens = sum(self._known_stage_tokens(stage, serialized) for section in sections for stage in section.stages)
        required_count = sum(len(section.stages) for section in sections)
        return stage_tokens + 260 + len(sections) * 24 + required_count * 10

    def _minimum_output_tokens(self, sections: Sequence[FlowPresentationSection]) -> int:
        skeleton = {
            "sections": [
                {
                    "sectionRef": section.section_ref,
                    "steps": [
                        {
                            "stageRef": stage.stage_ref,
                            "certainty": stage.certainty,
                            "assertionSubject": stage.assertion_subject,
                            "coveredFactRefs": list(stage.owned_fact_refs),
                            "text": "x",
                        }
                        for stage in section.stages
                    ],
                }
                for section in sections
            ]
        }
        return _estimate_tokens(json.dumps(skeleton, ensure_ascii=False, separators=(",", ":")))

    def _reserved_output_tokens(self, sections: Sequence[FlowPresentationSection], minimum_output_tokens: int) -> int:
        required_count = sum(len(section.stages) for section in sections)
        fact_count = sum(len(stage.owned_fact_refs) for section in sections for stage in section.stages)
        return max(
            minimum_output_tokens + required_count * 128 + fact_count * 32,
            required_count * 192 + fact_count * 24 + len(sections) * 64 + 256,
        )

    def _first_group(self, sections: Sequence[FlowPresentationSection]) -> FlowExecutionStage | None:
        for section in sections:
            if section.stages:
                return section.stages[0]
        return None

    def _last_group(self, sections: Sequence[FlowPresentationSection]) -> FlowExecutionStage | None:
        for section in reversed(tuple(sections)):
            if section.stages:
                return section.stages[-1]
        return None

    def _continuity(self, stage: FlowExecutionStage | None) -> FlowFormatterContinuity | None:
        if stage is None:
            return None
        incoming = stage.incoming if isinstance(stage.incoming, dict) else {}
        return FlowFormatterContinuity(
            source=stage.source or incoming.get("toSource") or incoming.get("fromSource"),
            symbol=stage.symbol or incoming.get("toSymbol") or incoming.get("fromSymbol"),
            transition_kind=incoming.get("relationKind"),
            gap_kind=stage.kind.value if stage.kind in {FlowExecutionStageKind.UNVERIFIED_GAP, FlowExecutionStageKind.AMBIGUOUS_GAP} else None,
            transport_kind=incoming.get("transportKind"),
            method=incoming.get("method"),
            route=incoming.get("route"),
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
            "The JSON shape is exactly: {\"sections\":[{\"sectionRef\":\"string\",\"steps\":[{\"stageRef\":\"string\",\"certainty\":\"string\",\"assertionSubject\":\"string\",\"coveredFactRefs\":[\"string\"],\"text\":\"string\"}]}]}.\n"
            "Return every supplied sectionRef exactly once, in the supplied section order.\n"
            "Return exactly one step for every supplied stageRef, in the supplied stage order, and never combine stageRefs.\n"
            "Echo the exact certainty and assertionSubject supplied for each stageRef.\n"
            "For each step, coveredFactRefs must exactly equal the ownedFactRefs supplied for that stageRef.\n"
            "Treat assertionSubject as the semantic subject of the step and assertionStatus as the status of only that subject.\n"
            "When assertionStatus is UNVERIFIED or AMBIGUOUS, make that status clear in text without changing the assertionSubject.\n"
            "When assertionStatus is not VERIFIED, do not transfer that status from assertionSubject to any other supplied identifier or entity.\n"
            "For DIRECT_RELATION, explain only the supplied relation between from/to identifiers and typed transport fields.\n"
            "For each stage, synthesize all supplied owned summaries, typed operations, supporting facts, owned boundaries, incoming context, outgoing context, and terminal semantics into one complete description.\n"
            "Use all distinct supplied facts, translate persisted summaries into responseLanguage, preserve supplied identifiers and typed literals when mentioned, and avoid repeating equivalent information.\n"
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

        required_stages = segment.required_stages
        required_refs = [stage.stage_ref for stage in required_stages]
        required_sections = list(segment.sections)
        required_section_refs = [section.section_ref for section in required_sections]
        section_by_ref = {section.section_ref: section for section in required_sections}
        stage_by_ref = {stage.stage_ref: stage for stage in required_stages}
        stage_index = {stage.stage_ref: index for index, stage in enumerate(required_stages)}
        section_by_stage_ref = {
            stage.stage_ref: section.section_ref
            for section in required_sections
            for stage in section.stages
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
                stage_ref = str(item.get("stageRef") or "").strip()
                certainty = str(item.get("certainty") or "").strip()
                assertion_subject = str(item.get("assertionSubject") or "").strip()
                raw_fact_refs = item.get("coveredFactRefs")
                covered_fact_refs = tuple(str(ref or "").strip() for ref in raw_fact_refs) if isinstance(raw_fact_refs, list) else ()
                text = str(item.get("text") or "").strip() if isinstance(item.get("text"), str) else ""
                if not stage_ref:
                    errors.append(f"sections[{section_index}].steps[{step_index}].stageRef must be a non-empty string.")
                    continue
                if stage_ref in seen:
                    errors.append(f"stageRef {stage_ref} appears more than once.")
                seen.add(stage_ref)
                stage = stage_by_ref.get(stage_ref)
                if stage is None:
                    errors.append(f"stageRef {stage_ref} was not supplied.")
                elif section_by_stage_ref.get(stage_ref) != section_ref:
                    errors.append(f"stageRef {stage_ref} does not belong to sectionRef {section_ref}.")
                if stage is not None:
                    index = stage_index[stage_ref]
                    if index <= last_index:
                        errors.append("steps must preserve canonical stage order.")
                    last_index = index
                    if certainty != stage.certainty:
                        errors.append(f"stageRef {stage_ref} certainty must be {stage.certainty}.")
                    if assertion_subject != stage.assertion_subject:
                        errors.append(f"stageRef {stage_ref} assertionSubject must be {stage.assertion_subject}.")
                    if list(covered_fact_refs) != list(stage.owned_fact_refs):
                        errors.append(f"stageRef {stage_ref} coveredFactRefs must exactly match supplied ownedFactRefs.")
                    if len(covered_fact_refs) != len(set(covered_fact_refs)):
                        errors.append(f"stageRef {stage_ref} coveredFactRefs must not contain duplicates.")
                    foreign_facts = [ref for ref in covered_fact_refs if ref not in stage.owned_fact_refs]
                    if foreign_facts:
                        errors.append(f"stageRef {stage_ref} coveredFactRefs contains foreign fact refs: {', '.join(foreign_facts)}.")
                if not text:
                    errors.append(f"stageRef {stage_ref} text must be non-empty.")
                parsed_steps.append(
                    FlowFormatterStepText(
                        stage_ref=stage_ref,
                        certainty=certainty,
                        assertion_subject=assertion_subject,
                        covered_fact_refs=covered_fact_refs,
                        text=text,
                    )
                )

        if actual_section_refs != required_section_refs:
            errors.append("sections must appear exactly once in the supplied section order.")
        missing_refs = [ref for ref in required_refs if ref not in seen]
        if missing_refs:
            errors.append(f"Missing stageRefs: {', '.join(missing_refs)}.")
        foreign_or_empty_sections = [ref for ref in actual_section_refs if not ref or ref not in section_by_ref]
        duplicate_sections = sorted({ref for ref in actual_section_refs if ref and actual_section_refs.count(ref) > 1})
        if foreign_or_empty_sections:
            errors.append("sections must not include empty or foreign sectionRefs.")
        if duplicate_sections:
            errors.append(f"sectionRefs appear more than once: {', '.join(duplicate_sections)}.")
        self._validate_text(parsed_steps, required_stages, segment.response_language, errors)
        if errors:
            raise FlowFormatterContractViolation(errors)
        return tuple(parsed_steps)

    def _validate_text(
        self,
        steps: Sequence[FlowFormatterStepText],
        stages: Sequence[FlowExecutionStage],
        response_language: str,
        errors: list[str],
    ) -> None:
        all_text = "\n".join(step.text for step in steps)
        language_result = self.language_validator.validate(all_text, response_language)
        if not language_result.valid:
            errors.extend(language_result.errors)
        all_allowed_routes = {value for stage in stages for value in self._stage_routes(stage)}
        local_refs = [ref for stage in stages for ref in (stage.stage_ref, *stage.owned_fact_refs)]
        for step in steps:
            text = step.text
            for local_ref in local_refs:
                if re.search(rf"(?<!\w){re.escape(local_ref)}(?!\w)", text):
                    errors.append(f"stageRef/factRef {local_ref} text exposes a response-local ref.")
            for route in _ROUTE_RE.findall(text):
                normalized_route = route.rstrip(".,;:)")
                if normalized_route not in all_allowed_routes:
                    errors.append(f"stageRef {step.stage_ref} text contains unsupported route {route!r}.")

    def _stage_routes(self, stage: FlowExecutionStage) -> tuple[str, ...]:
        values: list[str] = []
        containers: list[Any] = [stage.incoming, *stage.typed_operations, *stage.supporting_facts, *stage.owned_boundaries]
        for item in containers:
            if not isinstance(item, Mapping):
                continue
            for key in ("route", "targetDescriptor"):
                value = item.get(key)
                if isinstance(value, str) and value.startswith("/") and value not in values:
                    values.append(value)
        return tuple(values)


class FlowFormatterStitcher:
    def stitch(self, sections: Sequence[FlowPresentationSection], steps: Sequence[FlowFormatterStepText]) -> str:
        stage_by_ref: Dict[str, FlowExecutionStage] = {}
        for section in sections:
            for stage in section.stages:
                stage_by_ref.setdefault(stage.stage_ref, stage)
        section_by_ref = {section.section_ref: section for section in sections}
        counters: list[int] = []
        lines: list[str] = []
        rendered_sections: set[str] = set()
        for step in self._combine_stage_parts(steps):
            stage = stage_by_ref[step.stage_ref]
            section_ref = stage.section_ref or ""
            if section_ref and section_ref not in rendered_sections:
                heading = self._heading(section_by_ref.get(section_ref))
                if lines:
                    lines.append("")
                if heading:
                    lines.append(heading)
                rendered_sections.add(section_ref)
            depth = max(0, int(stage.depth))
            while len(counters) <= depth:
                counters.append(0)
            counters[depth] += 1
            del counters[depth + 1 :]
            number = ".".join(str(item) for item in counters[: depth + 1]) + "."
            indent = "   " * depth
            identifier = self._stage_identifier(stage)
            prefix = f"{identifier} — " if identifier else ""
            lines.append(f"{indent}{number} {prefix}{step.text.strip()}")
        return "\n".join(lines).strip()

    def _combine_stage_parts(self, steps: Sequence[FlowFormatterStepText]) -> tuple[FlowFormatterStepText, ...]:
        combined: list[FlowFormatterStepText] = []
        index_by_stage_ref: dict[str, int] = {}
        for step in steps:
            if step.stage_ref not in index_by_stage_ref:
                index_by_stage_ref[step.stage_ref] = len(combined)
                combined.append(step)
                continue
            index = index_by_stage_ref[step.stage_ref]
            previous = combined[index]
            text = " ".join(part for part in (previous.text.strip(), step.text.strip()) if part)
            combined[index] = FlowFormatterStepText(
                stage_ref=previous.stage_ref,
                certainty=previous.certainty,
                assertion_subject=previous.assertion_subject,
                covered_fact_refs=(*previous.covered_fact_refs, *step.covered_fact_refs),
                text=text,
            )
        return tuple(combined)

    def _heading(self, section: FlowPresentationSection | None) -> str:
        if section is None:
            return ""
        return self._display_text(section.source)

    def _stage_identifier(self, stage: FlowExecutionStage) -> str:
        if stage.kind is FlowExecutionStageKind.EXECUTABLE:
            return self._display_text(stage.symbol)
        if stage.kind in {FlowExecutionStageKind.UNVERIFIED_GAP, FlowExecutionStageKind.AMBIGUOUS_GAP}:
            incoming = stage.incoming if isinstance(stage.incoming, dict) else {}
            from_value = self._display_text(incoming.get("fromSymbol") or incoming.get("fromSource"))
            to_value = self._display_text(incoming.get("toSymbol") or incoming.get("toSource"))
            if from_value and to_value:
                return f"{from_value} -> {to_value}"
            return from_value or to_value or stage.kind.value
        if stage.kind is FlowExecutionStageKind.BOUNDARY:
            incoming = stage.incoming if isinstance(stage.incoming, dict) else {}
            return self._display_text(incoming.get("targetDescriptor") or incoming.get("toSymbol") or stage.symbol)
        return self._display_text(stage.symbol or stage.kind.value)

    def _display_text(self, value: Any) -> str:
        return re.sub(r"\s+", " ", str(value or "").strip())


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
            if len(narrative_plans) > 1:
                formatter_plan = self._with_response_local_refs(formatter_plan, f"p{narrative_index}_")
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

    def _with_response_local_refs(self, plan: FlowFormatterPlan, prefix: str) -> FlowFormatterPlan:
        if not prefix:
            return plan
        section_ref_by_ref = {section.section_ref: f"{prefix}{section.section_ref}" for section in plan.sections}
        stage_ref_by_ref = {stage.stage_ref: f"{prefix}{stage.stage_ref}" for stage in plan.stages}
        fact_ref_by_ref = {
            fact_ref: f"{prefix}{fact_ref}"
            for stage in plan.stages
            for fact_ref in stage.owned_fact_refs
        }

        def scoped_payload(value: Any) -> Any:
            if isinstance(value, dict):
                result: Dict[str, Any] = {}
                for key, item in value.items():
                    if key == "factRef" and isinstance(item, str):
                        result[key] = fact_ref_by_ref.get(item, f"{prefix}{item}")
                    elif key == "stageRef" and isinstance(item, str):
                        result[key] = stage_ref_by_ref.get(item, f"{prefix}{item}")
                    elif key == "sectionRef" and isinstance(item, str):
                        result[key] = section_ref_by_ref.get(item, f"{prefix}{item}")
                    elif key == "outgoingStageRefs" and isinstance(item, list):
                        result[key] = [stage_ref_by_ref.get(str(entry), f"{prefix}{entry}") for entry in item]
                    elif key == "ownedFactRefs" and isinstance(item, list):
                        result[key] = [fact_ref_by_ref.get(str(entry), f"{prefix}{entry}") for entry in item]
                    else:
                        result[key] = scoped_payload(item)
                return result
            if isinstance(value, list):
                return [scoped_payload(item) for item in value]
            return value

        stages_by_old_ref: dict[str, FlowExecutionStage] = {}
        for stage in plan.stages:
            stages_by_old_ref[stage.stage_ref] = replace(
                stage,
                stage_ref=stage_ref_by_ref[stage.stage_ref],
                section_ref=section_ref_by_ref.get(stage.section_ref, f"{prefix}{stage.section_ref}"),
                incoming=scoped_payload(stage.incoming) if stage.incoming else {},
                typed_operations=tuple(scoped_payload(dict(item)) for item in stage.typed_operations),
                supporting_facts=tuple(scoped_payload(dict(item)) for item in stage.supporting_facts),
                owned_summaries=tuple(scoped_payload(dict(item)) for item in stage.owned_summaries),
                owned_boundaries=tuple(scoped_payload(dict(item)) for item in stage.owned_boundaries),
                outgoing_stage_refs=tuple(stage_ref_by_ref.get(ref, f"{prefix}{ref}") for ref in stage.outgoing_stage_refs),
                owned_fact_refs=tuple(fact_ref_by_ref[ref] for ref in stage.owned_fact_refs),
                stage_part_ref=f"{prefix}{stage.stage_part_ref}" if stage.stage_part_ref else None,
            )

        updated_sections: list[FlowPresentationSection] = []
        for section in plan.sections:
            updated_sections.append(
                replace(
                    section,
                    section_ref=section_ref_by_ref[section.section_ref],
                    stages=tuple(stages_by_old_ref[stage.stage_ref] for stage in section.stages),
                )
            )
        updated_stages = tuple(stages_by_old_ref[stage.stage_ref] for stage in plan.stages)
        structural_metrics = self.plan_builder._structural_metrics(updated_stages)
        metrics = dict(plan.structural_metrics)
        metrics.update(structural_metrics)
        metrics["presentationSectionCount"] = len(updated_sections)
        return replace(
            plan,
            sections=tuple(updated_sections),
            stages=updated_stages,
            structural_metrics=metrics,
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
            "groupCount": len(segment.required_stages),
            "stageCount": len(segment.required_stages),
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
        group_count = sum(plan.presentation_stage_count for plan in formatter_plans)
        segment_count = max(self._executed_segment_count, 0)
        structural = self._combined_structural_metrics(formatter_plans)
        return {
            "narrativePlanCount": len(narrative_plans),
            "formatterGroupCount": group_count,
            "walkthroughStepCount": group_count,
            "branchCount": structural["structuralStageCount"],
            "gapCount": structural["gapStageCount"],
            "answerCount": int(answer_count),
            **structural,
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

    def _combined_structural_metrics(self, formatter_plans: Sequence[FlowFormatterPlan]) -> Dict[str, Any]:
        def total_int(key: str) -> int:
            return sum(int(plan.structural_metrics.get(key) or 0) for plan in formatter_plans)

        symbols: list[str] = []
        stage_refs: list[str] = []
        selected_stages: list[Dict[str, Any]] = []
        presentation_stage_refs: list[str] = []
        presentation_stages: list[Dict[str, Any]] = []
        stage_ownership_records: list[Dict[str, Any]] = []
        fact_refs: list[str] = []
        for plan in formatter_plans:
            symbols.extend(str(value) for value in plan.structural_metrics.get("selectedExecutableSymbols", []) if value)
            stage_refs.extend(str(value) for value in plan.structural_metrics.get("selectedExecutableStageRefs", []) if value)
            selected_stages.extend(
                dict(value)
                for value in plan.structural_metrics.get("selectedExecutableStages", [])
                if isinstance(value, dict)
            )
            presentation_stage_refs.extend(
                str(value)
                for value in plan.structural_metrics.get("presentationStageRefs", [])
                if value
            )
            presentation_stages.extend(
                dict(value)
                for value in plan.structural_metrics.get("presentationStages", [])
                if isinstance(value, dict)
            )
            stage_ownership_records.extend(
                dict(value)
                for value in plan.structural_metrics.get("stageOwnershipRecords", [])
                if isinstance(value, dict)
            )
        fact_refs.extend(
            str(fact_ref)
            for record in stage_ownership_records
            for fact_ref in (record.get("ownedFactRefs") or [])
            if fact_ref
        )
        return {
            "selectedExecutableNodeCount": total_int("selectedExecutableNodeCount"),
            "selectedExecutableStageRefs": stage_refs,
            "selectedExecutableSymbols": symbols,
            "selectedExecutableStages": selected_stages,
            "standaloneOperationStageCount": total_int("standaloneOperationStageCount"),
            "gapStageCount": total_int("gapStageCount"),
            "boundaryStageCount": total_int("boundaryStageCount"),
            "structuralStageCount": total_int("structuralStageCount"),
            "presentationStageCount": total_int("presentationStageCount"),
            "publicStepCount": total_int("publicStepCount"),
            "stageCountContractExpected": total_int("stageCountContractExpected"),
            "stageCountContractMatched": all(
                bool(plan.structural_metrics.get("stageCountContractMatched", False))
                for plan in formatter_plans
            ),
            "expectedPresentationStageCount": total_int("expectedPresentationStageCount"),
            "presentationStageRefs": presentation_stage_refs,
            "presentationStages": presentation_stages,
            "stageOwnershipRecords": stage_ownership_records,
            "stageOwnershipMap": {
                str(record.get("stageRef")): list(record.get("ownedFactRefs") or [])
                for record in stage_ownership_records
                if record.get("stageRef")
            },
            "ownedFactRefsByStageRef": {
                str(record.get("stageRef")): list(record.get("ownedFactRefs") or [])
                for record in stage_ownership_records
                if record.get("stageRef")
            },
            "factOwnerByFactRef": {
                str(fact_ref): str(record.get("stageRef"))
                for record in stage_ownership_records
                for fact_ref in (record.get("ownedFactRefs") or [])
                if record.get("stageRef") and fact_ref
            },
            "missingStageRefs": total_int("missingStageRefs"),
            "duplicateStageRefs": len(presentation_stage_refs) - len(set(presentation_stage_refs)),
            "unownedFactRefs": total_int("unownedFactRefs"),
            "duplicateFactRefs": len(fact_refs) - len(set(fact_refs)),
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


def _stage_payload(stage: FlowExecutionStage) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "stageRef": stage.stage_ref,
        "sectionRef": stage.section_ref,
        "order": stage.order,
        "depth": stage.depth,
        "kind": stage.kind.value,
        "certainty": stage.certainty,
        "assertionSubject": stage.assertion_subject,
        "assertionStatus": stage.assertion_status,
        "source": stage.source,
        "sourceDisplayHint": stage.source_display_hint,
        "symbol": stage.symbol,
        "nodeKind": stage.node_kind,
        "executionRole": stage.execution_role,
        "incoming": stage.incoming,
        "typedOperations": list(stage.typed_operations),
        "supportingFacts": list(stage.supporting_facts),
        "ownedSummaries": list(stage.owned_summaries),
        "ownedBoundaries": list(stage.owned_boundaries),
        "outgoingStageRefs": list(stage.outgoing_stage_refs),
        "branchPath": stage.branch_path,
        "terminalSemantic": stage.terminal_semantic,
        "ownedFactRefs": list(stage.owned_fact_refs),
        "stagePartRef": stage.stage_part_ref,
        "stagePartIndex": stage.stage_part_index,
        "stagePartCount": stage.stage_part_count,
    }
    return _without_empty(payload)


def _group_payload(group: FlowFormatterGroup, *, include_children: bool = True) -> Dict[str, Any]:
    payload: Dict[str, Any] = {
        "groupRef": group.group_ref,
        "sectionRef": group.section_ref,
        "order": group.order,
        "depth": group.depth,
        "kind": group.kind.value,
        "certainty": group.certainty,
        "assertionSubject": group.assertion_subject,
        "assertionStatus": group.assertion_status,
        "branchPath": group.branch_path,
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
