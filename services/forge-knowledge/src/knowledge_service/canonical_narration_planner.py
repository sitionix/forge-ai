from __future__ import annotations

import time
from collections import Counter, defaultdict, deque
from typing import Any, Sequence

from knowledge_service.canonical_narration_contract import (
    CanonicalFactOwnership,
    CanonicalNarrationClause,
    CanonicalNarrationPlan,
    valid_canonical_ref,
)
from knowledge_service.canonical_narration_strategies import (
    CycleMembershipExtractor,
    NarrationContext,
    NarrationContextKind,
    NarrationStrategyRegistry,
    NarrationStrategyResolutionError,
    default_narration_strategy_registry,
)
from knowledge_service.end_to_end_flow import EndToEndFlowGraph
from knowledge_service.formatter_policy import FormatterPolicy
from knowledge_service.knowledge_query_schema import KnowledgeGraphAnswerQueryEntry, KnowledgeQueryDiagnostic


class CanonicalNarrationPlanner:
    def __init__(
        self,
        *,
        registry: NarrationStrategyRegistry | None = None,
        policy: FormatterPolicy | None = None,
        cycle_extractor: CycleMembershipExtractor | None = None,
    ) -> None:
        self.registry = registry or default_narration_strategy_registry()
        self.policy = policy or FormatterPolicy()
        self.cycle_extractor = cycle_extractor or CycleMembershipExtractor()

    def plan(self, graph: EndToEndFlowGraph, *, response_language: str = "en") -> CanonicalNarrationPlan:
        started = time.perf_counter()
        diagnostics = [_graph_diagnostic(item) for item in graph.diagnostics]
        unit_refs_by_id = {ref.unit_id: ref for ref in graph.unit_refs}
        unit_order = self._unit_order(graph)
        exact_query_ids = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id in unit_refs_by_id)
        query_entries = tuple(self._query_entry(unit_refs_by_id[unit_id]) for unit_id in exact_query_ids)
        missing_query_ids = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id not in unit_refs_by_id)
        if missing_query_ids:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="CANONICAL_NARRATION_QUERY_ENTRY_MISSING",
                    message="Canonical query-entry IDs were preserved, but at least one referenced unit is absent from the selected graph.",
                    severity="WARN",
                    metadata={"graphId": graph.stable_graph_id, "missingQueryEntryUnitIds": missing_query_ids},
                )
            )
        if not graph.query_entry_unit_ids:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="CANONICAL_NARRATION_QUERY_ENTRY_ABSENT",
                    message="No canonical query-entry unit was provided; narration did not invent one.",
                    severity="WARN",
                    metadata={"graphId": graph.stable_graph_id},
                )
            )

        contexts = self._contexts(graph, unit_refs_by_id, unit_order)
        clauses: tuple[CanonicalNarrationClause, ...]
        try:
            clauses = self.registry.build_all(contexts)
        except NarrationStrategyResolutionError as exc:
            clauses = ()
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="CANONICAL_NARRATION_STRATEGY_RESOLUTION_FAILED",
                    message="Canonical narration strategy dispatch failed closed.",
                    severity="ERROR",
                    metadata={"graphId": graph.stable_graph_id, "reason": str(exc)},
                )
            )
        ownership_diagnostics, ownership = _validate_clause_ownership(clauses, graph.stable_graph_id)
        diagnostics.extend(ownership_diagnostics)
        return CanonicalNarrationPlan(
            graph_id=graph.stable_graph_id,
            response_language=response_language,
            clauses=clauses,
            canonical_fact_ownership=ownership,
            complete=graph.coverage.complete,
            diagnostics=tuple(diagnostics),
            sources=tuple(sorted({ref.source_id for ref in graph.unit_refs})),
            query_entries=query_entries,
            topology_entries=tuple(graph.topology_entry_unit_ids),
            planning_duration_ms=round((time.perf_counter() - started) * 1000, 3),
        )

    def _contexts(
        self,
        graph: EndToEndFlowGraph,
        unit_refs_by_id: dict[str, Any],
        unit_order: Sequence[str],
    ) -> tuple[NarrationContext, ...]:
        transitions_by_source: dict[str, list[Any]] = defaultdict(list)
        transitions_by_target: dict[str, list[Any]] = defaultdict(list)
        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            transitions_by_source[transition.source_unit_id].append(transition)
            transitions_by_target[transition.target_unit_id].append(transition)

        contexts: list[NarrationContext] = []
        emitted_transitions: set[str] = set()
        for unit_id in unit_order:
            ref = unit_refs_by_id[unit_id]
            inbound = tuple(transitions_by_target.get(unit_id, ()))
            if len(inbound) > 1:
                contexts.append(
                    NarrationContext(
                        context_kind=NarrationContextKind.CONVERGENCE,
                        graph=graph,
                        policy=self.policy,
                        target_unit_id=unit_id,
                        transitions=inbound,
                    )
                )
                contexts.append(
                    NarrationContext(
                        context_kind=NarrationContextKind.SHARED_UNIT,
                        graph=graph,
                        policy=self.policy,
                        target_unit_id=unit_id,
                        transitions=inbound,
                    )
                )
            contexts.append(NarrationContext(context_kind=NarrationContextKind.UNIT, graph=graph, policy=self.policy, unit_ref=ref))
            outbound = tuple(transitions_by_source.get(unit_id, ()))
            if len(outbound) > 1:
                contexts.append(
                    NarrationContext(
                        context_kind=NarrationContextKind.BRANCH,
                        graph=graph,
                        policy=self.policy,
                        source_unit_id=unit_id,
                        transitions=outbound,
                    )
                )
            for transition in outbound:
                contexts.append(
                    NarrationContext(
                        context_kind=NarrationContextKind.PROVEN_TRANSITION,
                        graph=graph,
                        policy=self.policy,
                        transition=transition,
                    )
                )
                emitted_transitions.add(transition.stable_transition_id)

        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            if transition.stable_transition_id in emitted_transitions:
                continue
            contexts.append(
                NarrationContext(
                    context_kind=NarrationContextKind.PROVEN_TRANSITION,
                    graph=graph,
                    policy=self.policy,
                    transition=transition,
                )
            )

        for boundary in sorted(
            graph.open_boundaries,
            key=lambda item: (
                getattr(item.status, "value", str(item.status)),
                item.required_boundary_identity.boundary_key,
                tuple(item.source_unit_ids),
            ),
        ):
            contexts.append(
                NarrationContext(
                    context_kind=NarrationContextKind.OPEN_BOUNDARY,
                    graph=graph,
                    policy=self.policy,
                    boundary=boundary,
                )
            )

        membership = self.cycle_extractor.extract(graph)
        if membership.cycle_transition_ids:
            contexts.append(
                NarrationContext(
                    context_kind=NarrationContextKind.CYCLE,
                    graph=graph,
                    policy=self.policy,
                    cycle_membership=membership,
                )
            )
        return tuple(contexts)

    def _unit_order(self, graph: EndToEndFlowGraph) -> tuple[str, ...]:
        unit_ids = tuple(sorted(ref.unit_id for ref in graph.unit_refs))
        if not unit_ids:
            return ()
        outgoing: dict[str, list[str]] = defaultdict(list)
        incoming: dict[str, set[str]] = defaultdict(set)
        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            outgoing[transition.source_unit_id].append(transition.target_unit_id)
            incoming[transition.target_unit_id].add(transition.source_unit_id)
        starts = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id in unit_ids)
        if not starts:
            starts = tuple(unit_id for unit_id in graph.topology_entry_unit_ids if unit_id in unit_ids)
        ordered: list[str] = []
        seen: set[str] = set()
        queue = deque(starts or unit_ids)
        while queue:
            unit_id = queue.popleft()
            if unit_id in seen or unit_id not in unit_ids:
                continue
            seen.add(unit_id)
            ordered.append(unit_id)
            for target_id in sorted(outgoing.get(unit_id, ())):
                if incoming.get(target_id, set()).issubset(seen) or target_id not in seen:
                    queue.append(target_id)
        ordered.extend(unit_id for unit_id in unit_ids if unit_id not in seen)
        return tuple(ordered)

    def _query_entry(self, ref: Any) -> KnowledgeGraphAnswerQueryEntry:
        root = ref.local_unit.roots[0].node if ref.local_unit.roots else None
        return KnowledgeGraphAnswerQueryEntry(
            unitId=ref.unit_id,
            sourceId=ref.source_id,
            root={
                "nodeId": getattr(root, "node_id", None),
                "stableKey": getattr(root, "stable_key", None),
                "label": getattr(root, "label", None),
                "qualifiedName": getattr(root, "qualified_name", None),
            },
        )


def _validate_clause_ownership(
    clauses: Sequence[CanonicalNarrationClause],
    graph_id: str,
) -> tuple[list[KnowledgeQueryDiagnostic], tuple[CanonicalFactOwnership, ...]]:
    clause_refs = [clause.clause_ref for clause in clauses]
    duplicate_clause_refs = sorted(ref for ref, count in Counter(clause_refs).items() if count > 1)
    owners_by_fact: dict[str, list[str]] = defaultdict(list)
    unknown_owned: list[str] = []
    unknown_context: list[str] = []
    for clause in clauses:
        for fact_ref in clause.canonical_fact_refs:
            if not valid_canonical_ref(fact_ref):
                unknown_owned.append(fact_ref)
            owners_by_fact[fact_ref].append(clause.clause_ref)
        for ref in (*clause.subject_refs, *clause.object_refs, *clause.qualifier_refs, *clause.allowed_canonical_refs):
            if not valid_canonical_ref(ref):
                unknown_context.append(ref)

    duplicate_owned = sorted(fact_ref for fact_ref, owners in owners_by_fact.items() if len(owners) > 1)
    diagnostics: list[KnowledgeQueryDiagnostic] = []
    if duplicate_clause_refs or duplicate_owned or unknown_owned or unknown_context:
        diagnostics.append(
            KnowledgeQueryDiagnostic(
                code="CANONICAL_NARRATION_OWNERSHIP_INVALID",
                message="Canonical narration plan fact ownership is invalid.",
                severity="ERROR",
                metadata={
                    "graphId": graph_id,
                    "duplicateClauseRefs": duplicate_clause_refs,
                    "duplicateOwnedFactRefs": duplicate_owned,
                    "unknownOwnedFactRefs": sorted(set(unknown_owned)),
                    "unknownContextFactRefs": sorted(set(unknown_context)),
                    "unownedRequiredFactRefs": [],
                },
            )
        )
    ownership = tuple(
        CanonicalFactOwnership(fact_ref=fact_ref, owner_clause_ref=owners[0])
        for fact_ref, owners in sorted(owners_by_fact.items())
        if owners
    )
    return diagnostics, ownership


def _graph_diagnostic(item: Any) -> KnowledgeQueryDiagnostic:
    return KnowledgeQueryDiagnostic(
        code=item.code,
        message=item.message,
        severity=item.severity,
        sourceId=getattr(item, "source_id", None),
        metadata=dict(item.metadata or {}),
    )
