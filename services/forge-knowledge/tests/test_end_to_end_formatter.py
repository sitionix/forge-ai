from __future__ import annotations

import json
from dataclasses import replace
from typing import Any, Mapping

import pytest
from test_end_to_end_flow import combine_results, neutral_boundary, open_result, proven, unit
from test_local_flow_unit_engine import edge, node

from knowledge_service.boundary_resolution import BoundaryResolutionStatus
from knowledge_service.canonical_narration_contract import (
    CanonicalNarrationClause,
    FormatterAssertionPredicate,
    FormatterAssertionValue,
    NarrationClauseKind,
    NarrationSemanticOperation,
)
from knowledge_service.canonical_narration_planner import CanonicalNarrationPlanner
from knowledge_service.canonical_narration_strategies import (
    CycleMembershipExtractor,
    NarrationContext,
    NarrationContextKind,
    NarrationStrategyRegistry,
)
from knowledge_service.end_to_end_flow import EndToEndFlowAssembler
from knowledge_service.formatter_policy import FormatterPolicy
from knowledge_service.formatter_protocol import EndToEndFormatterAllGraphsFailed, EndToEndFormatterValidationError
from knowledge_service.formatter_service import EndToEndFormatterAnswerService, EndToEndFormatterSegmentPlanner
from knowledge_service.formatter_validation import validate_provider_clauses

LANGUAGE_TEXTS = {
    "en": "This canonical clause is grounded in {refs} with clear factual context.",
    "uk": "Цей канонічний пункт спирається на {refs} і має зрозумілий фактичний контекст.",
    "fr": "Cette phrase canonique s'appuie sur {refs} avec un contexte factuel clair.",
    "de": "Diese kanonische Aussage stützt sich auf {refs} mit klarem faktischem Kontext.",
    "pl": "To kanoniczne zdanie opiera się na {refs} i ma jasny kontekst faktograficzny.",
}


class CanonicalFormatterProvider:
    def __init__(
        self,
        *,
        bad_first: bool = False,
        mutate_clause=None,
        text_by_language: dict[str, str] | None = None,
    ) -> None:
        self.calls: list[dict[str, Any]] = []
        self.bad_first = bad_first
        self.mutate_clause = mutate_clause
        self.text_by_language = text_by_language or {}

    def generate(self, formatter_input, *, deadline_at, cancel_event, validation_errors=()):
        del deadline_at, cancel_event
        self.calls.append({"input": dict(formatter_input), "validationErrors": list(validation_errors or [])})
        clauses = list(formatter_input.get("clauses") or [])
        if self.bad_first and len(self.calls) == 1:
            clauses = clauses[:-1]
        language = str(formatter_input.get("responseLanguage") or "en")
        response_clauses = []
        for clause in clauses:
            refs = self._refs(clause)
            rendered = {
                "clauseRef": clause["clauseRef"],
                "referencedCanonicalRefs": refs,
                "textTemplate": self._text(clause, language, refs),
            }
            if self.mutate_clause is not None:
                rendered = self.mutate_clause(dict(rendered), clause, dict(formatter_input), len(self.calls))
            response_clauses.append(rendered)
        return type(
            "ProviderResult",
            (),
            {
                "raw_text": json.dumps({"clauses": response_clauses}, ensure_ascii=False),
                "prompt_char_length": 100,
                "prompt_hash": f"prompt-{len(self.calls)}",
                "duration_ms": 1.0,
            },
        )()

    def _refs(self, clause: Mapping[str, Any]) -> list[str]:
        allowed = {ref for ref in clause.get("allowedCanonicalRefs") or [] if isinstance(ref, str)}
        preferred: list[str] = []
        for prefix in (
            "unit:",
            "topology-boundary:",
            "edge:",
            "root:",
            "node:",
            "transition:",
            "open-boundary:",
            "branch:",
            "convergence:",
            "cycle:",
            "shared-unit:",
        ):
            for ref in sorted(allowed):
                if ref.startswith(prefix) and ref not in preferred:
                    preferred.append(ref)
        return sorted(preferred[:2])

    def _text(self, clause: Mapping[str, Any], language: str, refs: list[str]) -> str:
        placeholder = ", ".join(f"{{{{ref:{ref}}}}}" for ref in refs) if refs else str(clause.get("clauseKind") or "clause")
        if language in self.text_by_language:
            return self.text_by_language[language].format(refs=placeholder)
        return f"This canonical clause is grounded in {placeholder}."


def test_formatter_calls_provider_and_validates_distinct_answer_languages():
    graph = _linear_graph()
    texts = {}
    for language in ("en", "uk", "fr", "de", "pl"):
        provider = CanonicalFormatterProvider(text_by_language=LANGUAGE_TEXTS)
        service = EndToEndFormatterAnswerService(provider)

        result = service.answer(None, type("Execution", (), {"selected_graphs": (graph,)})(), plan=_plan(language))

        assert result.metrics["formatterProviderCallCount"] == 1
        assert result.metrics["formatterRepairCallCount"] == 0
        assert result.metrics["validatedClauseCount"] == result.metrics["narrationClauseCount"]
        assert provider.calls[0]["input"]["responseLanguage"] == language
        texts[language] = result.answers[0].text
    assert len(set(texts.values())) == 5


def test_formatter_uses_policy_bounded_repair_attempt_with_exact_validation_errors():
    provider = CanonicalFormatterProvider(bad_first=True)
    service = EndToEndFormatterAnswerService(provider)

    result = service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))

    assert result.metrics["formatterProviderCallCount"] == 2
    assert result.metrics["formatterRepairCallCount"] == 1
    assert provider.calls[1]["validationErrors"]


def test_open_boundary_clauses_generate_internal_machine_assertions():
    ambiguous_plan = CanonicalNarrationPlanner().plan(_ambiguous_graph(), response_language="en")
    ambiguous = next(clause for clause in ambiguous_plan.clauses if clause.semantic_operation is NarrationSemanticOperation.HAS_AMBIGUOUS_CONTINUATION)
    ambiguous_assertions = {(item.predicate, item.value) for item in ambiguous.required_assertions}

    assert (FormatterAssertionPredicate.BOUNDARY_STATUS, FormatterAssertionValue.AMBIGUOUS) in ambiguous_assertions
    assert (FormatterAssertionPredicate.TARGET_SELECTION_STATUS, FormatterAssertionValue.NONE) in ambiguous_assertions
    assert (FormatterAssertionPredicate.PROOF_STATUS, FormatterAssertionValue.NOT_PROVEN) in ambiguous_assertions
    assert (FormatterAssertionPredicate.CANDIDATE_CARDINALITY, FormatterAssertionValue.MULTIPLE) in ambiguous_assertions

    unresolved_plan = CanonicalNarrationPlanner().plan(_unresolved_graph(), response_language="en")
    unresolved = next(clause for clause in unresolved_plan.clauses if clause.semantic_operation is NarrationSemanticOperation.HAS_UNRESOLVED_CONTINUATION)
    unresolved_assertions = {(item.predicate, item.value) for item in unresolved.required_assertions}

    assert (FormatterAssertionPredicate.BOUNDARY_STATUS, FormatterAssertionValue.UNRESOLVED) in unresolved_assertions
    assert (FormatterAssertionPredicate.TARGET_SELECTION_STATUS, FormatterAssertionValue.NONE) in unresolved_assertions
    assert (FormatterAssertionPredicate.PROOF_STATUS, FormatterAssertionValue.NOT_PROVEN) in unresolved_assertions
    assert FormatterAssertionPredicate.CANDIDATE_CARDINALITY not in {item.predicate for item in unresolved.required_assertions}


def test_proven_transition_clause_generates_proven_connectivity_assertion():
    plan = CanonicalNarrationPlanner().plan(_linear_graph(), response_language="en")
    transition = next(clause for clause in plan.clauses if clause.semantic_operation is NarrationSemanticOperation.CONTINUES_WITH_PROVEN_TARGET)

    assert [item.assertion_ref for item in transition.required_assertions] == sorted(item.assertion_ref for item in transition.required_assertions)
    assert [
        (item.predicate, item.subject_ref, item.object_ref, item.value)
        for item in transition.required_assertions
    ] == [
        (
            FormatterAssertionPredicate.CONNECTIVITY_STATUS,
            "unit:unit-a",
            "unit:unit-b",
            FormatterAssertionValue.PROVEN,
        )
    ]


def test_provider_response_rejects_unexpected_server_owned_fields():
    provider = CanonicalFormatterProvider(
        mutate_clause=lambda clause, _server_clause, _input, _call: {
            **clause,
            "serverOwnedFacts": ["unit:unit-a"],
            "serverOwnedSemantics": [],
        }
    )
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))

    assert len(provider.calls) == 2
    assert provider.calls[1]["validationErrors"]


def test_canonical_placeholder_is_substituted_with_display_value():
    provider = CanonicalFormatterProvider()
    service = EndToEndFormatterAnswerService(provider)

    result = service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))

    assert "{{ref:" not in result.answers[0].text
    assert "A" in result.answers[0].text


@pytest.mark.parametrize(
    "graph_factory,unknown_ref",
    [
        (lambda: _linear_graph(), "unit:not-owned-by-this-clause"),
        (lambda: _ambiguous_graph(), "unit:unit-b"),
    ],
)
def test_formatter_rejects_unknown_or_unrelated_placeholders(graph_factory, unknown_ref):
    def mutate(clause, _server_clause, _input, _call):
        return {
            **clause,
            "referencedCanonicalRefs": sorted([*clause["referencedCanonicalRefs"], unknown_ref]),
            "textTemplate": f"{clause['textTemplate']} {{{{ref:{unknown_ref}}}}}",
        }

    provider = CanonicalFormatterProvider(mutate_clause=mutate)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (graph_factory(),)})(), plan=_plan("en"))

    assert len(provider.calls) == 2


def test_formatter_rejects_declared_reference_without_placeholder():
    def mutate(clause, server_clause, _input, _call):
        declared = min(server_clause["allowedCanonicalRefs"])
        return {**clause, "referencedCanonicalRefs": [declared], "textTemplate": "This clause omits the declared placeholder."}

    provider = CanonicalFormatterProvider(mutate_clause=mutate)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))


def test_presentation_planner_does_not_invent_query_entry_when_missing():
    graph = _without_query_entries(_linear_graph())

    plan = CanonicalNarrationPlanner().plan(graph, response_language="en")

    assert plan.query_entries == ()
    assert any(item.code == "CANONICAL_NARRATION_QUERY_ENTRY_ABSENT" for item in plan.diagnostics)


def test_formatter_fails_closed_without_query_entry_and_does_not_call_provider():
    graph = _without_query_entries(_linear_graph())
    provider = CanonicalFormatterProvider()
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (graph,)})(), plan=_plan("en"))

    assert provider.calls == []
    assert service.pipeline_records[-1]["formatterProviderCallCount"] == 0
    assert service.audit_records[-1]["validationResult"] == "FAILED_NO_QUERY_ENTRY"


def test_presentation_plan_contains_branch_convergence_and_shared_unit_clauses_without_duplicate_unit_introduction():
    graph = _branch_convergence_graph()
    plan = CanonicalNarrationPlanner().plan(graph, response_language="en")
    clause_kinds = [clause.clause_kind for clause in plan.clauses]

    assert NarrationClauseKind.BRANCH in clause_kinds
    assert NarrationClauseKind.CONVERGENCE in clause_kinds
    assert NarrationClauseKind.SHARED_UNIT_REFERENCE in clause_kinds
    assert [
        clause.clause_ref
        for clause in plan.clauses
        if clause.semantic_operation is NarrationSemanticOperation.PRESENT_UNIT and clause.clause_ref.startswith("unit:unit-d:")
    ] == ["unit:unit-d:overview"]


def test_linear_clause_sequence_places_transition_between_units():
    plan = CanonicalNarrationPlanner().plan(_linear_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-a"),
        _continuation_sequence_item("unit-a", "unit-b"),
        *_unit_sequence("unit-b"),
    ]


def test_branch_clause_sequence_places_branch_before_outbound_transitions():
    plan = CanonicalNarrationPlanner().plan(_branch_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-a"),
        _branch_sequence_item("unit-a", ("unit-b", "unit-c")),
        _continuation_sequence_item("unit-a", "unit-b"),
        _continuation_sequence_item("unit-a", "unit-c"),
        *_unit_sequence("unit-b"),
        *_unit_sequence("unit-c"),
    ]


def test_convergence_clause_sequence_is_attached_to_entering_target_unit():
    plan = CanonicalNarrationPlanner().plan(_convergence_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-b"),
        _continuation_sequence_item("unit-b", "unit-d"),
        *_unit_sequence("unit-c"),
        _continuation_sequence_item("unit-c", "unit-d"),
        _convergence_sequence_item(("unit-b", "unit-c"), "unit-d"),
        _shared_unit_sequence_item("unit-d"),
        *_unit_sequence("unit-d"),
    ]


def test_branch_plus_convergence_clause_sequence_preserves_planner_order():
    plan = CanonicalNarrationPlanner().plan(_branch_convergence_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-a"),
        _branch_sequence_item("unit-a", ("unit-b", "unit-c")),
        _continuation_sequence_item("unit-a", "unit-b"),
        _continuation_sequence_item("unit-a", "unit-c"),
        *_unit_sequence("unit-b"),
        _continuation_sequence_item("unit-b", "unit-d"),
        *_unit_sequence("unit-c"),
        _continuation_sequence_item("unit-c", "unit-d"),
        _convergence_sequence_item(("unit-b", "unit-c"), "unit-d"),
        _shared_unit_sequence_item("unit-d"),
        *_unit_sequence("unit-d"),
    ]


def test_cycle_plus_tail_clause_sequence_keeps_cycle_after_component_narration():
    plan = CanonicalNarrationPlanner().plan(_cycle_plus_tail_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-a"),
        _continuation_sequence_item("unit-a", "unit-b"),
        *_unit_sequence("unit-b"),
        _branch_sequence_item("unit-b", ("unit-a", "unit-c")),
        _continuation_sequence_item("unit-b", "unit-c"),
        _continuation_sequence_item("unit-b", "unit-a"),
        *_unit_sequence("unit-c"),
        _cycle_sequence_item(("unit-a", "unit-b")),
    ]


def test_open_ambiguous_boundary_clause_sequence_follows_unit_clauses():
    plan = CanonicalNarrationPlanner().plan(_ambiguous_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-a"),
        _open_boundary_sequence_item("unit-a", NarrationSemanticOperation.HAS_AMBIGUOUS_CONTINUATION),
    ]


def test_open_unresolved_boundary_clause_sequence_follows_unit_clauses():
    plan = CanonicalNarrationPlanner().plan(_unresolved_graph(), response_language="en")

    assert _semantic_sequence(plan) == [
        *_unit_sequence("unit-a"),
        _open_boundary_sequence_item("unit-a", NarrationSemanticOperation.HAS_UNRESOLVED_CONTINUATION),
    ]


def test_independent_graph_components_keep_component_local_clause_sequences():
    graphs = _independent_graphs()

    sequences = [_semantic_sequence(CanonicalNarrationPlanner().plan(graph, response_language="en")) for graph in graphs]

    assert sequences == [
        _unit_sequence("unit-b", has_generic=False),
        _unit_sequence("unit-a", has_generic=False),
    ]


def test_reversed_canonical_inputs_produce_same_clause_sequence():
    graph = _branch_convergence_graph()
    reversed_graph = replace(
        graph,
        unit_refs=tuple(reversed(graph.unit_refs)),
        proven_cross_source_transitions=tuple(reversed(graph.proven_cross_source_transitions)),
        open_boundaries=tuple(reversed(graph.open_boundaries)),
    )
    open_graph = _mixed_open_boundary_graph()
    reversed_open_graph = replace(open_graph, open_boundaries=tuple(reversed(open_graph.open_boundaries)))

    planner = CanonicalNarrationPlanner()

    assert _semantic_sequence(planner.plan(reversed_graph, response_language="en")) == _semantic_sequence(planner.plan(graph, response_language="en"))
    assert _semantic_sequence(planner.plan(reversed_open_graph, response_language="en")) == _semantic_sequence(
        planner.plan(open_graph, response_language="en")
    )


def test_strategy_registry_preserves_context_then_strategy_order_without_global_sort():
    graph = _singleton_graph()
    contexts = (
        NarrationContext(context_kind=NarrationContextKind.UNIT, graph=graph, policy=FormatterPolicy(), source_unit_id="context-b"),
        NarrationContext(context_kind=NarrationContextKind.UNIT, graph=graph, policy=FormatterPolicy(), source_unit_id="context-a"),
    )
    registry = NarrationStrategyRegistry((_SyntheticStrategy("late", "z-order"), _SyntheticStrategy("early", "a-order")))

    clauses = registry.build_all(contexts)

    assert [clause.clause_ref for clause in clauses] == [
        "unit:context-b:late",
        "unit:context-b:early",
        "unit:context-a:late",
        "unit:context-a:early",
    ]


def test_presentation_plan_owns_every_fact_once_and_structural_refs_are_context():
    plan = CanonicalNarrationPlanner().plan(_branch_convergence_graph(), response_language="en")
    owned = [fact for clause in plan.clauses for fact in clause.canonical_fact_refs]

    assert len(owned) == len(set(owned))
    for clause in plan.clauses:
        if clause.clause_kind in {NarrationClauseKind.BRANCH, NarrationClauseKind.CONVERGENCE, NarrationClauseKind.SHARED_UNIT_REFERENCE}:
            assert all(fact.startswith(("branch:", "convergence:", "shared-unit:")) for fact in clause.canonical_fact_refs)
            assert any(ref.startswith("transition:") for ref in clause.qualifier_refs)


@pytest.mark.parametrize(
    "mutate_clause",
    [
        lambda _clause, _server_clause, _input, _call: {},
        lambda clause, _server_clause, _input, _call: {**clause, "clauseRef": "clause:extra"},
        lambda clause, _server_clause, _input, _call: {**clause, "referencedCanonicalRefs": []},
        lambda clause, _server_clause, _input, _call: {**clause, "textTemplate": ""},
    ],
)
def test_formatter_rejects_invalid_clause_protocol(mutate_clause):
    provider = CanonicalFormatterProvider(mutate_clause=mutate_clause)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))

    assert len(provider.calls) == 2


def test_two_selected_graphs_produce_two_answers():
    provider = CanonicalFormatterProvider()
    service = EndToEndFormatterAnswerService(provider)

    result = service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(), _branch_convergence_graph())})(), plan=_plan("en"))

    assert result.metrics["selectedGraphCount"] == 2
    assert result.metrics["answerCount"] == 2
    assert len(result.answers) == 2


def test_second_selected_graph_failure_fails_entire_human_answer():
    failing_graph_id = _branch_convergence_graph().stable_graph_id

    def mutate(clause, _server_clause, formatter_input, _call):
        if formatter_input["graphId"] == failing_graph_id:
            return {**clause, "clauseRef": "missing"}
        return clause

    provider = CanonicalFormatterProvider(mutate_clause=mutate)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed) as exc:
        service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(), _branch_convergence_graph())})(), plan=_plan("en"))

    assert len(exc.value.failed_graph_ids) == 1
    assert service.pipeline_records[-1]["selectedGraphCount"] == 2
    assert service.pipeline_records[-1]["answerCount"] == 0


def test_segments_preserve_clause_order_and_do_not_split_clauses():
    policy = FormatterPolicy(max_serialized_clause_chars=4096, max_serialized_segment_chars=8192, max_clauses_per_segment=2)
    plan = CanonicalNarrationPlanner(policy=policy).plan(_branch_convergence_graph(), response_language="en")

    segments = EndToEndFormatterSegmentPlanner(policy=policy).segments(plan)

    assert all(len(segment.clauses) <= 2 for segment in segments)
    assert [ref for segment in segments for ref in segment.clause_refs] == [clause.clause_ref for clause in plan.clauses]
    assert len({ref for segment in segments for ref in segment.clause_refs}) == len(plan.clauses)


def test_oversized_single_clause_fails_before_provider_call():
    policy = FormatterPolicy(max_serialized_clause_chars=1024, max_serialized_segment_chars=4096)
    graph = _large_unit_graph()
    provider = CanonicalFormatterProvider()
    service = EndToEndFormatterAnswerService(provider, segment_planner=EndToEndFormatterSegmentPlanner(policy=policy))

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (graph,)})(), plan=_plan("en"))

    assert provider.calls == []


def test_unknown_open_boundary_status_fails_closed():
    graph = _ambiguous_graph()
    malformed_boundary = replace(graph.open_boundaries[0], status="MALFORMED")
    malformed_graph = replace(graph, open_boundaries=(malformed_boundary,))

    plan = CanonicalNarrationPlanner().plan(malformed_graph, response_language="en")

    assert any(item.code == "CANONICAL_NARRATION_STRATEGY_RESOLUTION_FAILED" for item in plan.diagnostics)


def test_unit_semantics_distinguish_singleton_transition_open_boundary_and_truncated():
    singleton = CanonicalNarrationPlanner().plan(_singleton_graph(), response_language="en")
    singleton_coverage = _coverage_clause(singleton)
    assert any(item.value is FormatterAssertionValue.NO_LOCAL_TRANSITIONS for item in singleton_coverage.required_assertions)

    transitioned = CanonicalNarrationPlanner().plan(_local_transition_graph(), response_language="en")
    transitioned_coverage = _coverage_clause(transitioned)
    assert any(item.value is FormatterAssertionValue.HAS_LOCAL_TRANSITIONS for item in transitioned_coverage.required_assertions)

    open_boundary = CanonicalNarrationPlanner().plan(_topology_boundary_graph(), response_language="en")
    open_coverage = _coverage_clause(open_boundary)
    assert any(item.value is FormatterAssertionValue.HAS_OPEN_TOPOLOGY_BOUNDARY for item in open_coverage.required_assertions)

    truncated_graph = replace(_singleton_graph(), unit_refs=(replace(_singleton_graph().unit_refs[0], local_unit=replace(_singleton_graph().unit_refs[0].local_unit, complete=False)),))
    truncated = CanonicalNarrationPlanner().plan(truncated_graph, response_language="en")
    truncated_coverage = _coverage_clause(truncated)
    assert any(item.value is FormatterAssertionValue.TRUNCATED for item in truncated_coverage.required_assertions)


def test_cycle_membership_uses_only_cycle_transitions():
    graph = _cycle_plus_tail_graph()

    membership = CycleMembershipExtractor().extract(graph)

    assert membership.cycle_unit_ids == ("unit-a", "unit-b")
    assert len(membership.cycle_transition_ids) == 2
    assert all("unit-c" not in transition_id for transition_id in membership.cycle_transition_ids)


def test_validation_rejects_unknown_placeholder_without_reading_prose_semantics():
    plan = CanonicalNarrationPlanner().plan(_linear_graph(), response_language="en")
    segment = EndToEndFormatterSegmentPlanner().segments(plan)[0]
    clause_ref = segment.clause_refs[0]
    raw = json.dumps(
        {
            "clauses": [
                {
                    "clauseRef": clause_ref,
                    "referencedCanonicalRefs": ["unit:unknown"],
                    "textTemplate": "Dowolny tekst {{ref:unit:unknown}}.",
                },
                *[
                    {
                        "clauseRef": ref,
                        "referencedCanonicalRefs": [],
                        "textTemplate": "Dowolny tekst bez odwolania.",
                    }
                    for ref in segment.clause_refs[1:]
                ],
            ]
        }
    )

    with pytest.raises(EndToEndFormatterValidationError):
        validate_provider_clauses(raw, plan, segment)


def _coverage_clause(plan):
    return next(clause for clause in plan.clauses if clause.semantic_operation is NarrationSemanticOperation.PRESENT_COVERAGE)


def _semantic_sequence(plan) -> list[tuple[str, str, str, tuple[str, ...], tuple[str, ...]]]:
    return [
        (
            _stable_clause_ref(clause),
            clause.clause_kind.value,
            clause.semantic_operation.value,
            _unit_refs(clause.subject_refs),
            _unit_refs(clause.object_refs),
        )
        for clause in plan.clauses
    ]


def _stable_clause_ref(clause) -> str:
    subjects = _unit_refs(clause.subject_refs)
    objects = _unit_refs(clause.object_refs)
    if clause.clause_kind in _UNIT_CLAUSE_KINDS:
        return f"{subjects[0]}:{clause.clause_kind.value}"
    if clause.clause_kind is NarrationClauseKind.PROVEN_CONTINUATION:
        return f"transition:{subjects[0]}->{objects[0]}"
    if clause.clause_kind is NarrationClauseKind.BRANCH:
        return f"branch:{subjects[0]}->{','.join(objects)}"
    if clause.clause_kind is NarrationClauseKind.CONVERGENCE:
        return f"convergence:{','.join(subjects)}->{objects[0]}"
    if clause.clause_kind is NarrationClauseKind.SHARED_UNIT_REFERENCE:
        return f"shared:{subjects[0]}"
    if clause.clause_kind is NarrationClauseKind.OPEN_BOUNDARY:
        return f"open:{clause.semantic_operation.value}:{','.join(subjects)}"
    if clause.clause_kind is NarrationClauseKind.CYCLE_REFERENCE:
        return f"cycle:{','.join(subjects)}"
    return clause.clause_ref.split(":", 1)[0]


def _unit_refs(refs) -> tuple[str, ...]:
    return tuple(ref for ref in refs if ref.startswith("unit:"))


_UNIT_CLAUSE_KINDS = {
    NarrationClauseKind.UNIT_INTRODUCTION,
    NarrationClauseKind.UNIT_ROOTS,
    NarrationClauseKind.UNIT_ANCHORS,
    NarrationClauseKind.UNIT_EXECUTION_NODES,
    NarrationClauseKind.UNIT_LOCAL_TRANSITIONS,
    NarrationClauseKind.UNIT_TOPOLOGY_BOUNDARIES,
    NarrationClauseKind.UNIT_GENERIC_BOUNDARIES,
    NarrationClauseKind.UNIT_SUPPORTING_CONTEXT,
    NarrationClauseKind.UNIT_EVIDENCE,
    NarrationClauseKind.UNIT_COVERAGE,
}


def _unit_sequence(unit_id: str, *, has_generic: bool = True) -> list[tuple[str, str, str, tuple[str, ...], tuple[str, ...]]]:
    subject = (f"unit:{unit_id}",)
    kinds = [
        (NarrationClauseKind.UNIT_INTRODUCTION, NarrationSemanticOperation.PRESENT_UNIT),
        (NarrationClauseKind.UNIT_ROOTS, NarrationSemanticOperation.PRESENT_UNIT_ROOTS),
        (NarrationClauseKind.UNIT_EXECUTION_NODES, NarrationSemanticOperation.PRESENT_EXECUTION_NODES),
    ]
    if has_generic:
        kinds.append((NarrationClauseKind.UNIT_GENERIC_BOUNDARIES, NarrationSemanticOperation.PRESENT_GENERIC_BOUNDARY))
    kinds.extend(
        [
            (NarrationClauseKind.UNIT_EVIDENCE, NarrationSemanticOperation.PRESENT_EVIDENCE),
            (NarrationClauseKind.UNIT_COVERAGE, NarrationSemanticOperation.PRESENT_COVERAGE),
        ]
    )
    return [(f"{subject[0]}:{kind.value}", kind.value, operation.value, subject, ()) for kind, operation in kinds]


def _continuation_sequence_item(source_unit_id: str, target_unit_id: str) -> tuple[str, str, str, tuple[str, ...], tuple[str, ...]]:
    return (
        f"transition:unit:{source_unit_id}->unit:{target_unit_id}",
        NarrationClauseKind.PROVEN_CONTINUATION.value,
        NarrationSemanticOperation.CONTINUES_WITH_PROVEN_TARGET.value,
        (f"unit:{source_unit_id}",),
        (f"unit:{target_unit_id}",),
    )


def _branch_sequence_item(source_unit_id: str, target_unit_ids: tuple[str, ...]) -> tuple[str, str, str, tuple[str, ...], tuple[str, ...]]:
    targets = tuple(f"unit:{unit_id}" for unit_id in target_unit_ids)
    return (
        f"branch:unit:{source_unit_id}->{','.join(targets)}",
        NarrationClauseKind.BRANCH.value,
        NarrationSemanticOperation.BRANCHES_TO.value,
        (f"unit:{source_unit_id}",),
        targets,
    )


def _convergence_sequence_item(source_unit_ids: tuple[str, ...], target_unit_id: str) -> tuple[str, str, str, tuple[str, ...], tuple[str, ...]]:
    sources = tuple(f"unit:{unit_id}" for unit_id in source_unit_ids)
    target = f"unit:{target_unit_id}"
    return (
        f"convergence:{','.join(sources)}->{target}",
        NarrationClauseKind.CONVERGENCE.value,
        NarrationSemanticOperation.CONVERGES_AT.value,
        sources,
        (target,),
    )


def _shared_unit_sequence_item(unit_id: str) -> tuple[str, str, str, tuple[str, ...], tuple[str, ...]]:
    return (
        f"shared:unit:{unit_id}",
        NarrationClauseKind.SHARED_UNIT_REFERENCE.value,
        NarrationSemanticOperation.REFERENCES_SHARED_UNIT.value,
        (f"unit:{unit_id}",),
        (),
    )


def _cycle_sequence_item(unit_ids: tuple[str, ...]) -> tuple[str, str, str, tuple[str, ...], tuple[str, ...]]:
    units = tuple(f"unit:{unit_id}" for unit_id in unit_ids)
    return (
        f"cycle:{','.join(units)}",
        NarrationClauseKind.CYCLE_REFERENCE.value,
        NarrationSemanticOperation.REFERENCES_CYCLE.value,
        units,
        (),
    )


def _open_boundary_sequence_item(unit_id: str, operation: NarrationSemanticOperation) -> tuple[str, str, str, tuple[str, ...], tuple[str, ...]]:
    subject = (f"unit:{unit_id}",)
    return (
        f"open:{operation.value}:{','.join(subject)}",
        NarrationClauseKind.OPEN_BOUNDARY.value,
        operation.value,
        subject,
        (),
    )


class _SyntheticStrategy:
    additive = True
    owned_fact_kinds = ()
    semantic_operations = ()

    def __init__(self, label: str, ordering_prefix: str) -> None:
        self.label = label
        self.ordering_prefix = ordering_prefix

    def supports(self, context: NarrationContext) -> bool:
        return bool(context.source_unit_id)

    def build(self, context: NarrationContext) -> tuple[CanonicalNarrationClause, ...]:
        unit_ref = f"unit:{context.source_unit_id}"
        clause_ref = f"{unit_ref}:{self.label}"
        return (
            CanonicalNarrationClause(
                clause_ref=clause_ref,
                clause_kind=NarrationClauseKind.UNIT_INTRODUCTION,
                semantic_operation=NarrationSemanticOperation.PRESENT_UNIT,
                subject_refs=(unit_ref,),
                object_refs=(),
                qualifier_refs=(),
                canonical_fact_refs=(clause_ref,),
                display_values={unit_ref: str(context.source_unit_id), clause_ref: self.label},
                ordering_key=(self.ordering_prefix, str(context.source_unit_id)),
                allowed_canonical_refs=(unit_ref, clause_ref),
            ),
        )


def _plan(language: str):
    return type("Plan", (), {"response_language": language})()


def _linear_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "checkout")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "checkout")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_b = unit("unit-b", owner_b, boundaries=(provided,))
    result = EndToEndFlowAssembler().assemble(
        (unit_a, unit_b),
        query_entry_unit_ids=("unit-a",),
        boundary_resolution=proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",)),
    )
    return result.graphs[0]


def _ambiguous_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "checkout")
    candidate = neutral_boundary("provided-b", owner_b, "PROVIDED", "checkout")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    result = EndToEndFlowAssembler().assemble(
        (unit_a,),
        query_entry_unit_ids=("unit-a",),
        boundary_resolution=open_result(required, BoundaryResolutionStatus.AMBIGUOUS, ("unit-a",), candidate),
    )
    return result.graphs[0]


def _unresolved_graph():
    owner_a = node("A", source="source-a")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "checkout")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    result = EndToEndFlowAssembler().assemble(
        (unit_a,),
        query_entry_unit_ids=("unit-a",),
        boundary_resolution=open_result(required, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",)),
    )
    return result.graphs[0]


def _branch_convergence_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    owner_d = node("D", source="source-d")
    req_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "ab")
    req_ac = neutral_boundary("required-ac", owner_a, "REQUIRED", "ac")
    req_bd = neutral_boundary("required-bd", owner_b, "REQUIRED", "bd")
    req_cd = neutral_boundary("required-cd", owner_c, "REQUIRED", "cd")
    prov_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "ab")
    prov_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "ac")
    prov_d1 = neutral_boundary("provided-d1", owner_d, "PROVIDED", "bd")
    prov_d2 = neutral_boundary("provided-d2", owner_d, "PROVIDED", "cd")
    unit_a = unit("unit-a", owner_a, boundaries=(req_ab, req_ac))
    unit_b = unit("unit-b", owner_b, boundaries=(prov_b, req_bd))
    unit_c = unit("unit-c", owner_c, boundaries=(prov_c, req_cd))
    unit_d = unit("unit-d", owner_d, boundaries=(prov_d1, prov_d2))
    resolution = combine_results(
        proven(req_ab, prov_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="res-ab"),
        proven(req_ac, prov_c, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="res-ac"),
        proven(req_bd, prov_d1, required_unit_ids=("unit-b",), target_unit_ids=("unit-d",), resolution_id="res-bd"),
        proven(req_cd, prov_d2, required_unit_ids=("unit-c",), target_unit_ids=("unit-d",), resolution_id="res-cd"),
    )
    result = EndToEndFlowAssembler().assemble(
        (unit_a, unit_b, unit_c, unit_d),
        query_entry_unit_ids=("unit-a",),
        boundary_resolution=resolution,
    )
    return result.graphs[0]


def _branch_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    req_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "ab")
    req_ac = neutral_boundary("required-ac", owner_a, "REQUIRED", "ac")
    prov_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "ab")
    prov_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "ac")
    unit_a = unit("unit-a", owner_a, boundaries=(req_ab, req_ac))
    unit_b = unit("unit-b", owner_b, boundaries=(prov_b,))
    unit_c = unit("unit-c", owner_c, boundaries=(prov_c,))
    result = EndToEndFlowAssembler().assemble(
        (unit_a, unit_b, unit_c),
        query_entry_unit_ids=("unit-a",),
        boundary_resolution=combine_results(
            proven(req_ab, prov_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="res-ab"),
            proven(req_ac, prov_c, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="res-ac"),
        ),
    )
    return result.graphs[0]


def _convergence_graph():
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    owner_d = node("D", source="source-d")
    req_bd = neutral_boundary("required-bd", owner_b, "REQUIRED", "bd")
    req_cd = neutral_boundary("required-cd", owner_c, "REQUIRED", "cd")
    prov_d1 = neutral_boundary("provided-d1", owner_d, "PROVIDED", "bd")
    prov_d2 = neutral_boundary("provided-d2", owner_d, "PROVIDED", "cd")
    unit_b = unit("unit-b", owner_b, boundaries=(req_bd,))
    unit_c = unit("unit-c", owner_c, boundaries=(req_cd,))
    unit_d = unit("unit-d", owner_d, boundaries=(prov_d1, prov_d2))
    result = EndToEndFlowAssembler().assemble(
        (unit_b, unit_c, unit_d),
        query_entry_unit_ids=("unit-b", "unit-c"),
        boundary_resolution=combine_results(
            proven(req_bd, prov_d1, required_unit_ids=("unit-b",), target_unit_ids=("unit-d",), resolution_id="res-bd"),
            proven(req_cd, prov_d2, required_unit_ids=("unit-c",), target_unit_ids=("unit-d",), resolution_id="res-cd"),
        ),
    )
    return result.graphs[0]


def _mixed_open_boundary_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-a")
    req_a = neutral_boundary("required-a", owner_a, "REQUIRED", "a")
    req_b = neutral_boundary("required-b", owner_a, "REQUIRED", "b")
    candidate = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(req_b, req_a))
    result = EndToEndFlowAssembler().assemble(
        (unit_a,),
        query_entry_unit_ids=("unit-a",),
        boundary_resolution=combine_results(
            open_result(req_b, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",)),
            open_result(req_a, BoundaryResolutionStatus.AMBIGUOUS, ("unit-a",), candidate),
        ),
    )
    return result.graphs[0]


def _independent_graphs():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    result = EndToEndFlowAssembler().assemble(
        (unit("unit-a", owner_a), unit("unit-b", owner_b)),
        query_entry_unit_ids=("unit-a", "unit-b"),
        boundary_resolution=None,
    )
    return result.graphs


def _singleton_graph():
    owner = node("Solo", source="source-a")
    result = EndToEndFlowAssembler().assemble((unit("unit-solo", owner),), query_entry_unit_ids=("unit-solo",), boundary_resolution=None)
    return result.graphs[0]


def _local_transition_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-a")
    unit_a = unit("unit-a", owner_a, transitions=(edge("edge-a-b", owner_a.node_id, owner_b.node_id, source=owner_a.source_id),))
    result = EndToEndFlowAssembler().assemble((unit_a,), query_entry_unit_ids=("unit-a",), boundary_resolution=None)
    return result.graphs[0]


def _topology_boundary_graph():
    owner = node("A", source="source-a")
    open_edge = edge("edge-open", owner.node_id, None, source=owner.source_id, status="UNRESOLVED")
    local = unit("unit-a", owner)
    local = replace(
        local,
        topology_boundaries=(open_edge,),
        coverage=replace(local.coverage, topology_boundary_count=1),
    )
    result = EndToEndFlowAssembler().assemble((local,), query_entry_unit_ids=("unit-a",), boundary_resolution=None)
    return result.graphs[0]


def _large_unit_graph():
    owner = node("Large" + ("X" * 3000), source="source-a")
    local = unit("unit-large", owner)
    result = EndToEndFlowAssembler().assemble((local,), query_entry_unit_ids=("unit-large",), boundary_resolution=None)
    return result.graphs[0]


def _cycle_plus_tail_graph():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    req_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "ab")
    prov_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "ab")
    req_ba = neutral_boundary("required-ba", owner_b, "REQUIRED", "ba")
    prov_a = neutral_boundary("provided-a", owner_a, "PROVIDED", "ba")
    req_bc = neutral_boundary("required-bc", owner_b, "REQUIRED", "bc")
    prov_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "bc")
    unit_a = unit("unit-a", owner_a, boundaries=(req_ab, prov_a))
    unit_b = unit("unit-b", owner_b, boundaries=(prov_b, req_ba, req_bc))
    unit_c = unit("unit-c", owner_c, boundaries=(prov_c,))
    resolution = combine_results(
        proven(req_ab, prov_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="res-ab"),
        proven(req_ba, prov_a, required_unit_ids=("unit-b",), target_unit_ids=("unit-a",), resolution_id="res-ba"),
        proven(req_bc, prov_c, required_unit_ids=("unit-b",), target_unit_ids=("unit-c",), resolution_id="res-bc"),
    )
    result = EndToEndFlowAssembler().assemble((unit_a, unit_b, unit_c), query_entry_unit_ids=("unit-a",), boundary_resolution=resolution)
    return result.graphs[0]


def _without_query_entries(graph):
    return replace(graph, query_entry_unit_ids=())
