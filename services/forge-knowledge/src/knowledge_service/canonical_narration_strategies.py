from __future__ import annotations

import hashlib
import json
from collections import defaultdict
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol, Sequence

from knowledge_service.boundary_resolution import BoundaryResolutionStatus, boundary_identity
from knowledge_service.canonical_narration_contract import (
    CanonicalFormatterAssertion,
    CanonicalNarrationClause,
    CanonicalReferenceKind,
    CycleMembership,
    FormatterAssertionPredicate,
    FormatterAssertionValue,
    NarrationClauseKind,
    NarrationSemanticOperation,
    canonical_ref,
    sorted_unique,
)
from knowledge_service.end_to_end_flow import EndToEndFlowGraph
from knowledge_service.formatter_policy import FormatterPolicy


class NarrationContextKind(str, Enum):
    UNIT = "UNIT"
    PROVEN_TRANSITION = "PROVEN_TRANSITION"
    OPEN_BOUNDARY = "OPEN_BOUNDARY"
    BRANCH = "BRANCH"
    CONVERGENCE = "CONVERGENCE"
    SHARED_UNIT = "SHARED_UNIT"
    CYCLE = "CYCLE"


@dataclass(frozen=True)
class NarrationContext:
    context_kind: NarrationContextKind
    graph: EndToEndFlowGraph
    policy: FormatterPolicy
    unit_ref: Any | None = None
    transition: Any | None = None
    boundary: Any | None = None
    source_unit_id: str | None = None
    target_unit_id: str | None = None
    transitions: tuple[Any, ...] = ()
    cycle_membership: CycleMembership | None = None


class NarrationStrategy(Protocol):
    additive: bool
    owned_fact_kinds: tuple[CanonicalReferenceKind, ...]
    semantic_operations: tuple[NarrationSemanticOperation, ...]

    def supports(self, context: NarrationContext) -> bool: ...

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]: ...


class NarrationStrategyRegistry:
    def __init__(self, strategies: Sequence[NarrationStrategy] = ()) -> None:
        self._strategies: list[NarrationStrategy] = list(strategies)

    @property
    def ordered_strategies(self) -> tuple[NarrationStrategy, ...]:
        return tuple(self._strategies)

    def register(self, strategy: NarrationStrategy) -> None:
        self._strategies.append(strategy)

    def resolve(self, context: NarrationContext) -> tuple[NarrationStrategy, ...]:
        matches = tuple(strategy for strategy in self._strategies if strategy.supports(context))
        if not matches:
            raise NarrationStrategyResolutionError(f"No canonical narration strategy matched {context.context_kind.value}")
        exclusive = tuple(strategy for strategy in matches if not getattr(strategy, "additive", False))
        if len(exclusive) > 1:
            names = ", ".join(type(strategy).__name__ for strategy in exclusive)
            raise NarrationStrategyResolutionError(f"Multiple exclusive canonical narration strategies matched {context.context_kind.value}: {names}")
        return matches

    def build_all(self, contexts: Sequence[NarrationContext]) -> tuple[CanonicalNarrationClause, ...]:
        clauses: list[CanonicalNarrationClause] = []
        for context in contexts:
            for strategy in self.resolve(context):
                clauses.extend(strategy.build(context))
        return tuple(sorted(clauses, key=lambda item: item.ordering_key))


class NarrationStrategyResolutionError(RuntimeError):
    pass


class UnitNarrationStrategy:
    additive = False
    owned_fact_kinds = (
        CanonicalReferenceKind.UNIT,
        CanonicalReferenceKind.ROOT,
        CanonicalReferenceKind.ANCHOR,
        CanonicalReferenceKind.NODE,
        CanonicalReferenceKind.EDGE,
        CanonicalReferenceKind.TOPOLOGY_BOUNDARY,
        CanonicalReferenceKind.GENERIC_BOUNDARY,
        CanonicalReferenceKind.CONTEXT,
        CanonicalReferenceKind.EVIDENCE,
        CanonicalReferenceKind.COVERAGE,
    )
    semantic_operations = (
        NarrationSemanticOperation.PRESENT_UNIT,
        NarrationSemanticOperation.PRESENT_UNIT_ROOTS,
        NarrationSemanticOperation.PRESENT_QUERY_ANCHORS,
        NarrationSemanticOperation.PRESENT_EXECUTION_NODES,
        NarrationSemanticOperation.EXECUTES_LOCAL_TRANSITION,
        NarrationSemanticOperation.PRESENT_TOPOLOGY_BOUNDARY,
        NarrationSemanticOperation.PRESENT_GENERIC_BOUNDARY,
        NarrationSemanticOperation.PRESENT_SUPPORTING_CONTEXT,
        NarrationSemanticOperation.PRESENT_EVIDENCE,
        NarrationSemanticOperation.PRESENT_COVERAGE,
    )

    def supports(self, context: NarrationContext) -> bool:
        return context.context_kind is NarrationContextKind.UNIT and context.unit_ref is not None

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        ref = context.unit_ref
        unit = ref.local_unit
        unit_ref = canonical_ref(CanonicalReferenceKind.UNIT, unit.unit_id)
        base_order = ("unit", unit.unit_id)
        display = _unit_display_values(unit)
        clauses: list[CanonicalNarrationClause] = [
            _clause(
                clause_ref=canonical_ref(CanonicalReferenceKind.UNIT, unit.unit_id, "overview"),
                clause_kind=NarrationClauseKind.UNIT_INTRODUCTION,
                semantic_operation=NarrationSemanticOperation.PRESENT_UNIT,
                subject_refs=(unit_ref,),
                object_refs=(),
                qualifier_refs=(canonical_ref(CanonicalReferenceKind.SOURCE, unit.source_id),),
                canonical_fact_refs=(unit_ref,),
                display_values=display,
                ordering_key=(*base_order, "00-overview"),
                assertions=(
                    _assertion(
                        unit_ref,
                        "unit-status",
                        predicate=FormatterAssertionPredicate.UNIT_STATUS,
                        subject_ref=unit_ref,
                        value=FormatterAssertionValue.UNIT_PRESENT,
                    ),
                ),
            ),
            _clause(
                clause_ref=canonical_ref(CanonicalReferenceKind.COVERAGE, unit.unit_id),
                clause_kind=NarrationClauseKind.UNIT_COVERAGE,
                semantic_operation=NarrationSemanticOperation.PRESENT_COVERAGE,
                subject_refs=(unit_ref,),
                object_refs=(),
                qualifier_refs=(),
                canonical_fact_refs=(canonical_ref(CanonicalReferenceKind.COVERAGE, unit.unit_id),),
                display_values=display,
                ordering_key=(*base_order, "90-coverage"),
                assertions=(
                    _assertion(
                        canonical_ref(CanonicalReferenceKind.COVERAGE, unit.unit_id),
                        "local-execution-status",
                        predicate=FormatterAssertionPredicate.LOCAL_EXECUTION_STATUS,
                        subject_ref=unit_ref,
                        value=_unit_execution_status(unit),
                    ),
                    _assertion(
                        canonical_ref(CanonicalReferenceKind.COVERAGE, unit.unit_id),
                        "coverage",
                        predicate=FormatterAssertionPredicate.UNIT_STATUS,
                        subject_ref=unit_ref,
                        value=FormatterAssertionValue.COMPLETE if unit.complete else FormatterAssertionValue.TRUNCATED,
                    ),
                ),
            ),
        ]
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "10-roots",
                NarrationClauseKind.UNIT_ROOTS,
                NarrationSemanticOperation.PRESENT_UNIT_ROOTS,
                _root_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "20-anchors",
                NarrationClauseKind.UNIT_ANCHORS,
                NarrationSemanticOperation.PRESENT_QUERY_ANCHORS,
                _anchor_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "30-execution-nodes",
                NarrationClauseKind.UNIT_EXECUTION_NODES,
                NarrationSemanticOperation.PRESENT_EXECUTION_NODES,
                _node_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "40-local-transitions",
                NarrationClauseKind.UNIT_LOCAL_TRANSITIONS,
                NarrationSemanticOperation.EXECUTES_LOCAL_TRANSITION,
                _edge_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "50-topology-boundaries",
                NarrationClauseKind.UNIT_TOPOLOGY_BOUNDARIES,
                NarrationSemanticOperation.PRESENT_TOPOLOGY_BOUNDARY,
                _topology_boundary_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "60-generic-boundaries",
                NarrationClauseKind.UNIT_GENERIC_BOUNDARIES,
                NarrationSemanticOperation.PRESENT_GENERIC_BOUNDARY,
                _generic_boundary_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "70-supporting-context",
                NarrationClauseKind.UNIT_SUPPORTING_CONTEXT,
                NarrationSemanticOperation.PRESENT_SUPPORTING_CONTEXT,
                _context_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        clauses.extend(
            _collection_clauses(
                unit.unit_id,
                "80-evidence",
                NarrationClauseKind.UNIT_EVIDENCE,
                NarrationSemanticOperation.PRESENT_EVIDENCE,
                _evidence_refs(unit),
                display,
                context.policy,
                subject_refs=(unit_ref,),
            )
        )
        return tuple(clauses)


class ProvenTransitionNarrationStrategy:
    additive = False
    owned_fact_kinds = (
        CanonicalReferenceKind.TRANSITION,
        CanonicalReferenceKind.RESOLUTION,
        CanonicalReferenceKind.REQUIRED_BOUNDARY,
        CanonicalReferenceKind.PROVIDED_BOUNDARY,
    )
    semantic_operations = (NarrationSemanticOperation.CONTINUES_WITH_PROVEN_TARGET,)

    def supports(self, context: NarrationContext) -> bool:
        return context.context_kind is NarrationContextKind.PROVEN_TRANSITION and context.transition is not None

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        transition = context.transition
        fact_ref = canonical_ref(CanonicalReferenceKind.TRANSITION, transition.stable_transition_id)
        required_ref = canonical_ref(CanonicalReferenceKind.REQUIRED_BOUNDARY, _identity_ref(transition.required_endpoint.boundary_identity))
        provided_ref = canonical_ref(CanonicalReferenceKind.PROVIDED_BOUNDARY, _identity_ref(transition.provided_endpoint.boundary_identity))
        facts = sorted_unique(
            (
                fact_ref,
                canonical_ref(CanonicalReferenceKind.RESOLUTION, transition.resolution_id),
                required_ref,
                provided_ref,
            )
        )
        display = {
            fact_ref: transition.stable_transition_id,
            canonical_ref(CanonicalReferenceKind.RESOLUTION, transition.resolution_id): transition.resolution_id,
            canonical_ref(CanonicalReferenceKind.UNIT, transition.source_unit_id): transition.source_unit_id,
            canonical_ref(CanonicalReferenceKind.UNIT, transition.target_unit_id): transition.target_unit_id,
            required_ref: transition.required_endpoint.boundary_identity.boundary_key,
            provided_ref: transition.provided_endpoint.boundary_identity.boundary_key,
        }
        return (
            _clause(
                clause_ref=fact_ref,
                clause_kind=NarrationClauseKind.PROVEN_CONTINUATION,
                semantic_operation=NarrationSemanticOperation.CONTINUES_WITH_PROVEN_TARGET,
                subject_refs=(canonical_ref(CanonicalReferenceKind.UNIT, transition.source_unit_id),),
                object_refs=(canonical_ref(CanonicalReferenceKind.UNIT, transition.target_unit_id),),
                qualifier_refs=(required_ref, provided_ref),
                canonical_fact_refs=facts,
                display_values=display,
                ordering_key=("transition", transition.source_unit_id, transition.stable_transition_id),
                assertions=(
                    _assertion(
                        fact_ref,
                        "connectivity",
                        predicate=FormatterAssertionPredicate.CONNECTIVITY_STATUS,
                        subject_ref=canonical_ref(CanonicalReferenceKind.UNIT, transition.source_unit_id),
                        object_ref=canonical_ref(CanonicalReferenceKind.UNIT, transition.target_unit_id),
                        value=FormatterAssertionValue.PROVEN,
                    ),
                ),
            ),
        )


class AmbiguousBoundaryNarrationStrategy:
    additive = False
    owned_fact_kinds = (CanonicalReferenceKind.OPEN_BOUNDARY,)
    semantic_operations = (NarrationSemanticOperation.HAS_AMBIGUOUS_CONTINUATION,)

    def supports(self, context: NarrationContext) -> bool:
        return (
            context.context_kind is NarrationContextKind.OPEN_BOUNDARY
            and context.boundary is not None
            and context.boundary.status is BoundaryResolutionStatus.AMBIGUOUS
        )

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        return (_open_boundary_clause(context, NarrationSemanticOperation.HAS_AMBIGUOUS_CONTINUATION, FormatterAssertionValue.AMBIGUOUS),)


class UnresolvedBoundaryNarrationStrategy:
    additive = False
    owned_fact_kinds = (CanonicalReferenceKind.OPEN_BOUNDARY,)
    semantic_operations = (NarrationSemanticOperation.HAS_UNRESOLVED_CONTINUATION,)

    def supports(self, context: NarrationContext) -> bool:
        return (
            context.context_kind is NarrationContextKind.OPEN_BOUNDARY
            and context.boundary is not None
            and context.boundary.status is BoundaryResolutionStatus.UNRESOLVED
        )

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        return (_open_boundary_clause(context, NarrationSemanticOperation.HAS_UNRESOLVED_CONTINUATION, FormatterAssertionValue.UNRESOLVED),)


class BranchNarrationStrategy:
    additive = False
    owned_fact_kinds = (CanonicalReferenceKind.BRANCH,)
    semantic_operations = (NarrationSemanticOperation.BRANCHES_TO,)

    def supports(self, context: NarrationContext) -> bool:
        return context.context_kind is NarrationContextKind.BRANCH and bool(context.source_unit_id) and bool(context.transitions)

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        transition_ids = tuple(sorted(item.stable_transition_id for item in context.transitions))
        source_unit_id = str(context.source_unit_id or "")
        fact_ref = canonical_ref(CanonicalReferenceKind.BRANCH, source_unit_id, _sha256("|".join(transition_ids))[:12])
        target_refs = tuple(sorted(canonical_ref(CanonicalReferenceKind.UNIT, item.target_unit_id) for item in context.transitions))
        display = {fact_ref: "branch", canonical_ref(CanonicalReferenceKind.UNIT, source_unit_id): source_unit_id}
        display.update({canonical_ref(CanonicalReferenceKind.TRANSITION, item): item for item in transition_ids})
        display.update({ref: ref.rsplit(":", 1)[-1] for ref in target_refs})
        return (
            _clause(
                clause_ref=fact_ref,
                clause_kind=NarrationClauseKind.BRANCH,
                semantic_operation=NarrationSemanticOperation.BRANCHES_TO,
                subject_refs=(canonical_ref(CanonicalReferenceKind.UNIT, source_unit_id),),
                object_refs=target_refs,
                qualifier_refs=tuple(canonical_ref(CanonicalReferenceKind.TRANSITION, item) for item in transition_ids),
                canonical_fact_refs=(fact_ref,),
                display_values=display,
                ordering_key=("branch", source_unit_id, fact_ref),
                assertions=(
                    _assertion(
                        fact_ref,
                        "structural-relation",
                        predicate=FormatterAssertionPredicate.STRUCTURAL_RELATION,
                        subject_ref=canonical_ref(CanonicalReferenceKind.UNIT, source_unit_id),
                        value=FormatterAssertionValue.BRANCH,
                    ),
                ),
            ),
        )


class ConvergenceNarrationStrategy:
    additive = False
    owned_fact_kinds = (CanonicalReferenceKind.CONVERGENCE,)
    semantic_operations = (NarrationSemanticOperation.CONVERGES_AT,)

    def supports(self, context: NarrationContext) -> bool:
        return context.context_kind is NarrationContextKind.CONVERGENCE and bool(context.target_unit_id) and bool(context.transitions)

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        transition_ids = tuple(sorted(item.stable_transition_id for item in context.transitions))
        target_unit_id = str(context.target_unit_id or "")
        fact_ref = canonical_ref(CanonicalReferenceKind.CONVERGENCE, target_unit_id, _sha256("|".join(transition_ids))[:12])
        source_refs = tuple(sorted(canonical_ref(CanonicalReferenceKind.UNIT, item.source_unit_id) for item in context.transitions))
        display = {fact_ref: "convergence", canonical_ref(CanonicalReferenceKind.UNIT, target_unit_id): target_unit_id}
        display.update({canonical_ref(CanonicalReferenceKind.TRANSITION, item): item for item in transition_ids})
        display.update({ref: ref.rsplit(":", 1)[-1] for ref in source_refs})
        return (
            _clause(
                clause_ref=fact_ref,
                clause_kind=NarrationClauseKind.CONVERGENCE,
                semantic_operation=NarrationSemanticOperation.CONVERGES_AT,
                subject_refs=source_refs,
                object_refs=(canonical_ref(CanonicalReferenceKind.UNIT, target_unit_id),),
                qualifier_refs=tuple(canonical_ref(CanonicalReferenceKind.TRANSITION, item) for item in transition_ids),
                canonical_fact_refs=(fact_ref,),
                display_values=display,
                ordering_key=("convergence", target_unit_id, fact_ref),
                assertions=(
                    _assertion(
                        fact_ref,
                        "structural-relation",
                        predicate=FormatterAssertionPredicate.STRUCTURAL_RELATION,
                        subject_ref=canonical_ref(CanonicalReferenceKind.UNIT, target_unit_id),
                        value=FormatterAssertionValue.CONVERGENCE,
                    ),
                ),
            ),
        )


class SharedUnitNarrationStrategy:
    additive = False
    owned_fact_kinds = (CanonicalReferenceKind.SHARED_UNIT,)
    semantic_operations = (NarrationSemanticOperation.REFERENCES_SHARED_UNIT,)

    def supports(self, context: NarrationContext) -> bool:
        return context.context_kind is NarrationContextKind.SHARED_UNIT and bool(context.target_unit_id) and bool(context.transitions)

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        transition_ids = tuple(sorted(item.stable_transition_id for item in context.transitions))
        target_unit_id = str(context.target_unit_id or "")
        fact_ref = canonical_ref(CanonicalReferenceKind.SHARED_UNIT, target_unit_id, _sha256("|".join(transition_ids))[:12])
        display = {
            fact_ref: "shared unit",
            canonical_ref(CanonicalReferenceKind.UNIT, target_unit_id): target_unit_id,
            **{canonical_ref(CanonicalReferenceKind.TRANSITION, item): item for item in transition_ids},
        }
        return (
            _clause(
                clause_ref=fact_ref,
                clause_kind=NarrationClauseKind.SHARED_UNIT_REFERENCE,
                semantic_operation=NarrationSemanticOperation.REFERENCES_SHARED_UNIT,
                subject_refs=(canonical_ref(CanonicalReferenceKind.UNIT, target_unit_id),),
                object_refs=(),
                qualifier_refs=tuple(canonical_ref(CanonicalReferenceKind.TRANSITION, item) for item in transition_ids),
                canonical_fact_refs=(fact_ref,),
                display_values=display,
                ordering_key=("shared-unit", target_unit_id, fact_ref),
                assertions=(
                    _assertion(
                        fact_ref,
                        "structural-relation",
                        predicate=FormatterAssertionPredicate.STRUCTURAL_RELATION,
                        subject_ref=canonical_ref(CanonicalReferenceKind.UNIT, target_unit_id),
                        value=FormatterAssertionValue.SHARED_UNIT,
                    ),
                ),
            ),
        )


class CycleNarrationStrategy:
    additive = False
    owned_fact_kinds = (CanonicalReferenceKind.CYCLE,)
    semantic_operations = (NarrationSemanticOperation.REFERENCES_CYCLE,)

    def supports(self, context: NarrationContext) -> bool:
        return context.context_kind is NarrationContextKind.CYCLE and context.cycle_membership is not None

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        membership = context.cycle_membership
        assert membership is not None
        fact_ref = canonical_ref(CanonicalReferenceKind.CYCLE, context.graph.stable_graph_id, _sha256("|".join(membership.cycle_transition_ids))[:12])
        display = {fact_ref: "cycle"}
        display.update({canonical_ref(CanonicalReferenceKind.TRANSITION, item): item for item in membership.cycle_transition_ids})
        display.update({canonical_ref(CanonicalReferenceKind.UNIT, item): item for item in membership.cycle_unit_ids})
        return (
            _clause(
                clause_ref=fact_ref,
                clause_kind=NarrationClauseKind.CYCLE_REFERENCE,
                semantic_operation=NarrationSemanticOperation.REFERENCES_CYCLE,
                subject_refs=tuple(canonical_ref(CanonicalReferenceKind.UNIT, item) for item in membership.cycle_unit_ids),
                object_refs=(),
                qualifier_refs=tuple(canonical_ref(CanonicalReferenceKind.TRANSITION, item) for item in membership.cycle_transition_ids),
                canonical_fact_refs=(fact_ref,),
                display_values=display,
                ordering_key=("cycle", context.graph.stable_graph_id, fact_ref),
                assertions=(
                    _assertion(
                        fact_ref,
                        "structural-relation",
                        predicate=FormatterAssertionPredicate.STRUCTURAL_RELATION,
                        subject_ref=fact_ref,
                        value=FormatterAssertionValue.CYCLE,
                    ),
                ),
            ),
        )


class CycleMembershipExtractor:
    def extract(self, graph: EndToEndFlowGraph) -> CycleMembership:
        unit_ids = tuple(sorted(ref.unit_id for ref in graph.unit_refs))
        outgoing: dict[str, list[str]] = defaultdict(list)
        transition_by_pair: dict[tuple[str, str], list[str]] = defaultdict(list)
        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            outgoing[transition.source_unit_id].append(transition.target_unit_id)
            transition_by_pair[(transition.source_unit_id, transition.target_unit_id)].append(transition.stable_transition_id)
        components = self._strongly_connected_components(unit_ids, outgoing)
        cycle_units: set[str] = set()
        cycle_transitions: set[str] = set()
        for component in components:
            component_set = set(component)
            has_self_loop = any((unit_id, unit_id) in transition_by_pair for unit_id in component)
            if len(component_set) <= 1 and not has_self_loop:
                continue
            cycle_units.update(component_set)
            for (source_id, target_id), transition_ids in transition_by_pair.items():
                if source_id in component_set and target_id in component_set:
                    cycle_transitions.update(transition_ids)
        return CycleMembership(cycle_unit_ids=tuple(sorted(cycle_units)), cycle_transition_ids=tuple(sorted(cycle_transitions)))

    def _strongly_connected_components(self, unit_ids: Sequence[str], outgoing: dict[str, list[str]]) -> tuple[tuple[str, ...], ...]:
        index = 0
        stack: list[str] = []
        on_stack: set[str] = set()
        indexes: dict[str, int] = {}
        lowlinks: dict[str, int] = {}
        components: list[tuple[str, ...]] = []

        def visit(unit_id: str) -> None:
            nonlocal index
            indexes[unit_id] = index
            lowlinks[unit_id] = index
            index += 1
            stack.append(unit_id)
            on_stack.add(unit_id)
            for target_id in sorted(outgoing.get(unit_id, ())):
                if target_id not in indexes:
                    visit(target_id)
                    lowlinks[unit_id] = min(lowlinks[unit_id], lowlinks[target_id])
                elif target_id in on_stack:
                    lowlinks[unit_id] = min(lowlinks[unit_id], indexes[target_id])
            if lowlinks[unit_id] == indexes[unit_id]:
                component: list[str] = []
                while stack:
                    item = stack.pop()
                    on_stack.remove(item)
                    component.append(item)
                    if item == unit_id:
                        break
                components.append(tuple(sorted(component)))

        for unit_id in sorted(unit_ids):
            if unit_id not in indexes:
                visit(unit_id)
        return tuple(sorted(components))


def default_narration_strategy_registry() -> NarrationStrategyRegistry:
    return NarrationStrategyRegistry(
        (
            UnitNarrationStrategy(),
            ProvenTransitionNarrationStrategy(),
            AmbiguousBoundaryNarrationStrategy(),
            UnresolvedBoundaryNarrationStrategy(),
            BranchNarrationStrategy(),
            ConvergenceNarrationStrategy(),
            SharedUnitNarrationStrategy(),
            CycleNarrationStrategy(),
        )
    )


def _open_boundary_clause(
    context: NarrationContext,
    operation: NarrationSemanticOperation,
    status_value: FormatterAssertionValue,
) -> CanonicalNarrationClause:
    boundary = context.boundary
    identity_ref = _identity_ref(boundary.required_boundary_identity)
    fact_ref = canonical_ref(CanonicalReferenceKind.OPEN_BOUNDARY, identity_ref, ",".join(boundary.source_unit_ids))
    candidate_owner_refs = tuple(
        sorted(
            canonical_ref(CanonicalReferenceKind.CANDIDATE_OWNER, owner.source_id, owner.graph_revision, owner.owner_node_id)
            for owner in boundary.viable_candidate_owner_identities
        )
    )
    candidate_boundary_refs = tuple(
        sorted(canonical_ref(CanonicalReferenceKind.CANDIDATE_BOUNDARY, _identity_ref(item)) for item in boundary.viable_candidate_boundary_identities)
    )
    display = {
        fact_ref: boundary.required_boundary_identity.boundary_key,
        **{canonical_ref(CanonicalReferenceKind.UNIT, unit_id): unit_id for unit_id in boundary.source_unit_ids},
        **{ref: ref.rsplit(":", 1)[-1] for ref in candidate_owner_refs},
        **{ref: ref.rsplit(":", 1)[-1] for ref in candidate_boundary_refs},
    }
    assertions = [
        _assertion(
            fact_ref,
            "boundary-status",
            predicate=FormatterAssertionPredicate.BOUNDARY_STATUS,
            subject_ref=fact_ref,
            value=status_value,
        ),
        _assertion(
            fact_ref,
            "target-selection",
            predicate=FormatterAssertionPredicate.TARGET_SELECTION_STATUS,
            subject_ref=fact_ref,
            value=FormatterAssertionValue.NONE,
        ),
        _assertion(
            fact_ref,
            "proof-status",
            predicate=FormatterAssertionPredicate.PROOF_STATUS,
            subject_ref=fact_ref,
            value=FormatterAssertionValue.NOT_PROVEN,
        ),
    ]
    if status_value is FormatterAssertionValue.AMBIGUOUS:
        assertions.append(
            _assertion(
                fact_ref,
                "candidate-cardinality",
                predicate=FormatterAssertionPredicate.CANDIDATE_CARDINALITY,
                subject_ref=fact_ref,
                value=FormatterAssertionValue.MULTIPLE,
            )
        )
    return _clause(
        clause_ref=fact_ref,
        clause_kind=NarrationClauseKind.OPEN_BOUNDARY,
        semantic_operation=operation,
        subject_refs=tuple(canonical_ref(CanonicalReferenceKind.UNIT, item) for item in boundary.source_unit_ids),
        object_refs=(),
        qualifier_refs=(fact_ref, *candidate_owner_refs, *candidate_boundary_refs),
        canonical_fact_refs=(fact_ref,),
        display_values=display,
        ordering_key=("open-boundary", status_value.value, identity_ref, ",".join(boundary.source_unit_ids)),
        assertions=tuple(assertions),
    )


def _collection_clauses(
    unit_id: str,
    order_prefix: str,
    clause_kind: NarrationClauseKind,
    semantic_operation: NarrationSemanticOperation,
    fact_refs: Sequence[str],
    display_values: dict[str, str],
    policy: FormatterPolicy,
    *,
    subject_refs: Sequence[str],
) -> tuple[CanonicalNarrationClause, ...]:
    sorted_refs = tuple(sorted_unique(tuple(fact_refs)))
    if not sorted_refs:
        return ()
    chunks: list[tuple[str, ...]] = []
    current: list[str] = []
    for fact_ref in sorted_refs:
        candidate = (*current, fact_ref)
        candidate_clause = _clause(
            clause_ref=_chunk_clause_ref(unit_id, clause_kind, candidate),
            clause_kind=clause_kind,
            semantic_operation=semantic_operation,
            subject_refs=subject_refs,
            object_refs=candidate,
            qualifier_refs=(),
            canonical_fact_refs=candidate,
            display_values=display_values,
            ordering_key=("unit", unit_id, order_prefix, _sha256("|".join(candidate))[:12]),
        )
        if current and len(_serialized_clause_input(candidate_clause)) > policy.max_serialized_clause_chars:
            chunks.append(tuple(current))
            current = [fact_ref]
        else:
            current = list(candidate)
    if current:
        chunks.append(tuple(current))
    clauses: list[CanonicalNarrationClause] = []
    for chunk in chunks:
        clauses.append(
            _clause(
                clause_ref=_chunk_clause_ref(unit_id, clause_kind, chunk),
                clause_kind=clause_kind,
                semantic_operation=semantic_operation,
                subject_refs=subject_refs,
                object_refs=chunk,
                qualifier_refs=(),
                canonical_fact_refs=chunk,
                display_values=display_values,
                ordering_key=("unit", unit_id, order_prefix, _sha256("|".join(chunk))[:12]),
            )
        )
    return tuple(clauses)


def _clause(
    *,
    clause_ref: str,
    clause_kind: NarrationClauseKind,
    semantic_operation: NarrationSemanticOperation,
    subject_refs: Sequence[str],
    object_refs: Sequence[str],
    qualifier_refs: Sequence[str],
    canonical_fact_refs: Sequence[str],
    display_values: dict[str, str],
    ordering_key: Sequence[str],
    assertions: Sequence[CanonicalFormatterAssertion] = (),
) -> CanonicalNarrationClause:
    facts = sorted_unique(tuple(canonical_fact_refs))
    allowed = sorted_unique((*facts, *tuple(subject_refs), *tuple(object_refs), *tuple(qualifier_refs), *tuple(display_values)))
    display = {ref: _canonical_ref_display(ref) for ref in allowed}
    display.update({str(ref): str(value) for ref, value in display_values.items() if str(ref).strip() and str(value).strip()})
    return CanonicalNarrationClause(
        clause_ref=clause_ref,
        clause_kind=clause_kind,
        semantic_operation=semantic_operation,
        subject_refs=sorted_unique(tuple(subject_refs)),
        object_refs=sorted_unique(tuple(object_refs)),
        qualifier_refs=sorted_unique(tuple(qualifier_refs)),
        canonical_fact_refs=facts,
        display_values={ref: display.get(ref, _canonical_ref_display(ref)) for ref in allowed},
        ordering_key=tuple(str(item) for item in ordering_key),
        required_assertions=tuple(sorted(assertions, key=lambda item: item.assertion_ref)),
        allowed_canonical_refs=allowed,
    )


def _assertion(
    clause_ref: str,
    local_ref: str,
    *,
    predicate: FormatterAssertionPredicate,
    subject_ref: str,
    object_ref: str | None = None,
    value: FormatterAssertionValue | str | None = None,
) -> CanonicalFormatterAssertion:
    return CanonicalFormatterAssertion(
        assertion_ref=f"assertion:{clause_ref}:{local_ref}",
        predicate=predicate,
        subject_ref=subject_ref,
        object_ref=object_ref,
        value=value,
    )


def _unit_execution_status(unit: Any) -> FormatterAssertionValue:
    if unit.coverage.truncated or not unit.complete:
        return FormatterAssertionValue.TRUNCATED
    if unit.execution_transitions:
        return FormatterAssertionValue.HAS_LOCAL_TRANSITIONS
    if unit.topology_boundaries:
        return FormatterAssertionValue.HAS_OPEN_TOPOLOGY_BOUNDARY
    return FormatterAssertionValue.NO_LOCAL_TRANSITIONS


def _unit_display_values(unit: Any) -> dict[str, str]:
    values = {
        canonical_ref(CanonicalReferenceKind.UNIT, unit.unit_id): _unit_display(unit),
        canonical_ref(CanonicalReferenceKind.SOURCE, unit.source_id): unit.source_id,
        canonical_ref(CanonicalReferenceKind.COVERAGE, unit.unit_id): "coverage",
    }
    for ref, node in zip(_root_refs(unit), (root.node for root in unit.roots)):
        values[ref] = _node_display(node)
    for ref, anchor in zip(_anchor_refs(unit), unit.anchors):
        values[ref] = _node_display(anchor.expanded_seed)
    for ref, node in zip(_node_refs(unit), unit.execution_nodes):
        values[ref] = _node_display(node)
    for ref, edge in zip(_edge_refs(unit), unit.execution_transitions):
        values[ref] = _edge_display(edge)
    for ref, edge in zip(_topology_boundary_refs(unit), unit.topology_boundaries):
        values[ref] = _edge_display(edge)
    for ref, boundary in zip(_generic_boundary_refs(unit), unit.generic_boundaries):
        values[ref] = boundary.stable_key or boundary.boundary_id
    for ref, node in zip(_context_refs(unit), unit.supporting_context):
        values[ref] = _node_display(node)
    for ref, evidence in zip(_evidence_refs(unit), unit.evidence):
        values[ref] = evidence.evidence_id
    return values


def _root_refs(unit: Any) -> tuple[str, ...]:
    return tuple(sorted(canonical_ref(CanonicalReferenceKind.ROOT, _node_identity(root.node)) for root in unit.roots))


def _anchor_refs(unit: Any) -> tuple[str, ...]:
    return tuple(
        sorted(
            canonical_ref(
                CanonicalReferenceKind.ANCHOR,
                anchor.original_anchor.sourceId,
                anchor.original_anchor.graphRevision or anchor.original_anchor.graphId or "",
                anchor.original_anchor.stableKey or anchor.original_anchor.nodeId,
                anchor.expanded_seed.node_id,
            )
            for anchor in unit.anchors
        )
    )


def _node_refs(unit: Any) -> tuple[str, ...]:
    return tuple(sorted(canonical_ref(CanonicalReferenceKind.NODE, _node_identity(node)) for node in unit.execution_nodes))


def _edge_refs(unit: Any) -> tuple[str, ...]:
    return tuple(sorted(canonical_ref(CanonicalReferenceKind.EDGE, _edge_identity(edge)) for edge in unit.execution_transitions))


def _topology_boundary_refs(unit: Any) -> tuple[str, ...]:
    return tuple(sorted(canonical_ref(CanonicalReferenceKind.TOPOLOGY_BOUNDARY, _edge_identity(edge)) for edge in unit.topology_boundaries))


def _generic_boundary_refs(unit: Any) -> tuple[str, ...]:
    return tuple(sorted(canonical_ref(CanonicalReferenceKind.GENERIC_BOUNDARY, _identity_ref(boundary_identity(boundary))) for boundary in unit.generic_boundaries))


def _context_refs(unit: Any) -> tuple[str, ...]:
    return tuple(sorted(canonical_ref(CanonicalReferenceKind.CONTEXT, _node_identity(node)) for node in unit.supporting_context))


def _evidence_refs(unit: Any) -> tuple[str, ...]:
    return tuple(
        sorted(
            canonical_ref(CanonicalReferenceKind.EVIDENCE, item.source_id, item.graph_revision or item.graph_id, item.evidence_id)
            for item in unit.evidence
        )
    )


def _identity_ref(identity: Any) -> str:
    return f"{identity.source_id}:{identity.graph_revision}:{identity.boundary_key}:{identity.owner_node_id}"


def _node_identity(node: Any) -> str:
    return f"{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}"


def _edge_identity(edge: Any) -> str:
    return f"{edge.source_id}:{edge.graph_revision or edge.graph_id}:{edge.edge_id}"


def _unit_display(unit: Any) -> str:
    if unit.roots:
        return _node_display(unit.roots[0].node)
    if unit.execution_nodes:
        return _node_display(unit.execution_nodes[0])
    return unit.unit_id


def _node_display(node: Any) -> str:
    return str(getattr(node, "qualified_name", None) or getattr(node, "label", None) or getattr(node, "stable_key", None) or getattr(node, "node_id", ""))


def _edge_display(edge: Any) -> str:
    unresolved_target = getattr(edge, "unresolved_target", None)
    unresolved_values: tuple[Any, ...] = ()
    if isinstance(unresolved_target, dict):
        unresolved_values = (
            unresolved_target.get("qualifiedName"),
            unresolved_target.get("qualified_name"),
            unresolved_target.get("name"),
            unresolved_target.get("label"),
            unresolved_target.get("target"),
        )
    values = (
        getattr(edge, "to_qualified_name", None),
        getattr(edge, "to_label", None),
        getattr(edge, "to_name", None),
        getattr(edge, "to_node_id", None),
        *unresolved_values,
        getattr(edge, "label", None),
        getattr(edge, "edge_id", None),
    )
    return next((str(value) for value in values if str(value or "").strip()), str(getattr(edge, "edge_id", "")))


def _chunk_clause_ref(unit_id: str, clause_kind: NarrationClauseKind, fact_refs: Sequence[str]) -> str:
    return canonical_ref(CanonicalReferenceKind.UNIT, unit_id, clause_kind.value.lower(), _sha256("|".join(sorted(fact_refs)))[:12])


def _serialized_clause_input(clause: CanonicalNarrationClause) -> str:
    payload = {
        "clauseRef": clause.clause_ref,
        "clauseKind": clause.clause_kind.value,
        "semanticOperation": clause.semantic_operation.value,
        "allowedCanonicalRefs": list(clause.allowed_canonical_refs),
        "canonicalDisplayValues": dict(clause.display_values),
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _canonical_ref_display(ref: str) -> str:
    text = str(ref or "").strip()
    if not text:
        return ""
    if ":" not in text:
        return text
    return text.rsplit(":", 1)[-1]


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()
