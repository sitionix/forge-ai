from __future__ import annotations

import json

import pytest
from test_end_to_end_flow import combine_results, neutral_boundary, open_result, proven, unit
from test_local_flow_unit_engine import node

from knowledge_service.boundary_resolution import BoundaryResolutionStatus
from knowledge_service.end_to_end_flow import EndToEndFlowAssembler
from knowledge_service.flow_formatter import (
    EndToEndFormatterAllGraphsFailed,
    EndToEndFormatterAnswerService,
    EndToEndPresentationPlanner,
)

LANGUAGE_TEXTS = {
    "en": "This canonical stage is grounded in {refs} with clear factual context.",
    "uk": "Цей канонічний етап спирається на {refs} і має зрозумілий фактичний контекст.",
    "fr": "Cette étape canonique s'appuie sur {refs} avec un contexte factuel clair.",
    "de": "Dieser kanonische Schritt stützt sich auf {refs} mit klarem faktischem Kontext.",
    "pl": "Ten etap kanoniczny opiera się na {refs} i ma jasny kontekst faktograficzny.",
}


class CanonicalFormatterProvider:
    def __init__(self, *, bad_first: bool = False, mutate_step=None, text_by_language: dict[str, str] | None = None) -> None:
        self.calls = []
        self.bad_first = bad_first
        self.mutate_step = mutate_step
        self.text_by_language = text_by_language or {}

    def generate(self, formatter_input, *, deadline_at, cancel_event, validation_errors=()):
        del deadline_at, cancel_event
        self.calls.append({"input": dict(formatter_input), "validationErrors": list(validation_errors or [])})
        stages = list(formatter_input.get("stages") or [])
        if self.bad_first and len(self.calls) == 1:
            stages = stages[:-1]
        language = str(formatter_input.get("responseLanguage") or "en")
        steps = []
        for stage in stages:
            refs = self._refs(stage)
            step = {
                "stageRef": stage["stageRef"],
                "coveredFactRefs": list(stage.get("ownedFactRefs") or []),
                "assertions": list(stage.get("requiredAssertions") or []),
                "referencedCanonicalRefs": refs,
                "textTemplate": self._text(stage, language, refs),
            }
            if self.mutate_step is not None:
                step = self.mutate_step(dict(step), stage, len(self.calls))
            steps.append(step)
        return type(
            "ProviderResult",
            (),
            {
                "raw_text": json.dumps({"steps": steps}, ensure_ascii=False),
                "prompt_char_length": 100,
                "prompt_hash": f"prompt-{len(self.calls)}",
                "duration_ms": 1.0,
            },
        )()

    def _refs(self, stage) -> list[str]:
        allowed = set(stage.get("allowedCanonicalRefs") or [])
        candidates = [
            ref
            for ref in [*list(stage.get("ownedFactRefs") or ()), *list(stage.get("contextFactRefs") or ())]
            if isinstance(ref, str) and ref in allowed
        ]
        preferred = []
        for prefix in ("unit:", "topology-boundary:", "edge:", "root:", "node:", "transition:", "open-boundary:", "branch:", "convergence:", "cycle:", "shared-unit:"):
            for ref in candidates:
                if ref.startswith(prefix) and ref not in preferred:
                    preferred.append(ref)
        for ref in candidates:
            if ref not in preferred:
                preferred.append(ref)
        return sorted(dict.fromkeys(preferred[:2]))

    def _text(self, stage, language: str, refs: list[str]) -> str:
        placeholder = ", ".join(f"{{{{ref:{ref}}}}}" for ref in refs) if refs else str(stage.get("kind") or "stage")
        if language in self.text_by_language:
            return self.text_by_language[language].format(refs=placeholder)
        return f"This canonical stage is grounded in {placeholder}."


def test_formatter_calls_provider_and_validates_distinct_answer_languages():
    graph = _linear_graph()
    texts = {}
    for language in ("en", "uk", "fr", "de", "pl"):
        provider = CanonicalFormatterProvider(text_by_language=LANGUAGE_TEXTS)
        service = EndToEndFormatterAnswerService(provider)

        result = service.answer(None, type("Execution", (), {"selected_graphs": (graph,)})(), plan=_plan(language))

        assert result.metrics["formatterProviderCallCount"] == 1
        assert result.metrics["formatterRepairCallCount"] == 0
        assert result.metrics["validatedFormatterStepCount"] == result.metrics["presentationStageCount"]
        assert provider.calls[0]["input"]["responseLanguage"] == language
        texts[language] = result.answers[0].text
    assert len(set(texts.values())) == 5


def test_formatter_uses_one_bounded_repair_attempt_with_exact_validation_errors():
    provider = CanonicalFormatterProvider(bad_first=True)
    service = EndToEndFormatterAnswerService(provider)

    result = service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))

    assert result.metrics["formatterProviderCallCount"] == 2
    assert result.metrics["formatterRepairCallCount"] == 1
    assert provider.calls[1]["validationErrors"]


def test_open_boundary_stages_generate_machine_assertions():
    ambiguous_plan = EndToEndPresentationPlanner().plan(_ambiguous_graph(), response_language="en")
    ambiguous_stage = next(stage for stage in ambiguous_plan.stages if stage.stage_ref.startswith("open-boundary:"))
    ambiguous_assertions = {(item.predicate, item.value) for item in ambiguous_stage.required_assertions}

    assert ("BOUNDARY_STATUS", "AMBIGUOUS") in ambiguous_assertions
    assert ("TARGET_SELECTION_STATUS", "NONE") in ambiguous_assertions
    assert ("PROOF_STATUS", "NOT_PROVEN") in ambiguous_assertions
    assert ("CANDIDATE_CARDINALITY", "MULTIPLE") in ambiguous_assertions

    unresolved_plan = EndToEndPresentationPlanner().plan(_unresolved_graph(), response_language="en")
    unresolved_stage = next(stage for stage in unresolved_plan.stages if stage.stage_ref.startswith("open-boundary:"))
    unresolved_assertions = {(item.predicate, item.value) for item in unresolved_stage.required_assertions}

    assert ("BOUNDARY_STATUS", "UNRESOLVED") in unresolved_assertions
    assert ("TARGET_SELECTION_STATUS", "NONE") in unresolved_assertions
    assert ("PROOF_STATUS", "NOT_PROVEN") in unresolved_assertions
    assert "CANDIDATE_CARDINALITY" not in {item.predicate for item in unresolved_stage.required_assertions}


def test_proven_transition_stage_generates_proven_connectivity_assertion():
    plan = EndToEndPresentationPlanner().plan(_linear_graph(), response_language="en")
    transition_stage = next(stage for stage in plan.stages if stage.stage_ref.startswith("transition:"))

    assert [item.assertion_ref for item in transition_stage.required_assertions] == sorted(item.assertion_ref for item in transition_stage.required_assertions)
    assert [
        (item.predicate, item.subject_ref, item.object_ref, item.value)
        for item in transition_stage.required_assertions
    ] == [("CONNECTIVITY_STATUS", "unit:unit-a", "unit:unit-b", "PROVEN")]


@pytest.mark.parametrize(
    "mutate_step",
    [
        lambda step, _stage, _call: {**step, "assertions": [*step["assertions"], {"assertionRef": "assertion:unknown", "predicate": "PROOF_STATUS", "subjectRef": "unit:missing", "objectRef": None, "value": "PROVEN"}]},
        lambda step, _stage, _call: {**step, "assertions": step["assertions"][:-1]},
        lambda step, _stage, _call: {**step, "assertions": [*step["assertions"], step["assertions"][0]]},
        lambda step, _stage, _call: {
            **step,
            "assertions": [
                {**assertion, "value": "WRONG_VALUE"} if index == 0 else assertion
                for index, assertion in enumerate(step["assertions"])
            ],
        },
    ],
)
def test_formatter_rejects_unknown_missing_duplicate_or_wrong_assertions(mutate_step):
    provider = CanonicalFormatterProvider(mutate_step=mutate_step)
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
        (lambda: _linear_graph(), "unit:not-owned-by-this-stage"),
        (lambda: _ambiguous_graph(), "unit:unit-b"),
    ],
)
def test_formatter_rejects_unknown_or_unrelated_placeholders(graph_factory, unknown_ref):
    def mutate(step, _stage, _call):
        return {
            **step,
            "referencedCanonicalRefs": sorted([*step["referencedCanonicalRefs"], unknown_ref]),
            "textTemplate": f"{step['textTemplate']} {{{{ref:{unknown_ref}}}}}",
        }

    provider = CanonicalFormatterProvider(mutate_step=mutate)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (graph_factory(),)})(), plan=_plan("en"))

    assert len(provider.calls) == 2


def test_formatter_rejects_declared_reference_without_placeholder():
    def mutate(step, _stage, _call):
        owned = step["coveredFactRefs"][0]
        return {**step, "referencedCanonicalRefs": [owned], "textTemplate": "This text omits the declared placeholder."}

    provider = CanonicalFormatterProvider(mutate_step=mutate)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))


def test_presentation_planner_does_not_invent_query_entry_when_missing():
    graph = _linear_graph()
    graph = _without_query_entries(graph)

    plan = EndToEndPresentationPlanner().plan(graph, response_language="en")

    assert plan.query_entries == ()
    assert any(item.code == "END_TO_END_PRESENTATION_QUERY_ENTRY_ABSENT" for item in plan.diagnostics)


def test_formatter_fails_closed_without_query_entry_and_does_not_call_provider():
    graph = _without_query_entries(_linear_graph())
    provider = CanonicalFormatterProvider()
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (graph,)})(), plan=_plan("en"))

    assert provider.calls == []
    assert service.pipeline_records[-1]["formatterProviderCallCount"] == 0
    assert service.audit_records[-1]["validationResult"] == "FAILED_NO_QUERY_ENTRY"


def test_presentation_plan_contains_branch_convergence_and_shared_unit_stages_without_duplicate_unit_rendering():
    graph = _branch_convergence_graph()
    plan = EndToEndPresentationPlanner().plan(graph, response_language="en")
    stage_kinds = [stage.kind for stage in plan.stages]

    assert "BRANCH" in stage_kinds
    assert "CONVERGENCE" in stage_kinds
    assert "SHARED_UNIT_REFERENCE" in stage_kinds
    assert [stage.stage_ref for stage in plan.stages if stage.stage_ref == "unit:unit-d"] == ["unit:unit-d"]


def test_presentation_plan_owns_every_fact_once_and_structural_refs_are_context():
    plan = EndToEndPresentationPlanner().plan(_branch_convergence_graph(), response_language="en")
    owned = [fact for stage in plan.stages for fact in stage.owned_fact_refs]

    assert len(owned) == len(set(owned))
    for stage in plan.stages:
        if stage.kind in {"BRANCH", "CONVERGENCE", "SHARED_UNIT_REFERENCE"}:
            assert all(fact.startswith(("branch:", "convergence:", "shared-unit:")) for fact in stage.owned_fact_refs)
            assert any(fact.startswith("transition:") for fact in stage.context_fact_refs)


@pytest.mark.parametrize(
    "mutate_step",
    [
        lambda step, _stage, _call: {**step, "coveredFactRefs": []},
        lambda step, _stage, _call: {**step, "coveredFactRefs": step["coveredFactRefs"][:-1]},
        lambda step, _stage, _call: {**step, "coveredFactRefs": sorted([*step["coveredFactRefs"], "fact:extra"])},
        lambda step, stage, _call: {
            **step,
            "coveredFactRefs": sorted([*step["coveredFactRefs"], *(stage.get("contextFactRefs") or ["fact:context"])]),
        },
    ],
)
def test_formatter_rejects_empty_subset_extra_or_context_fact_coverage(mutate_step):
    provider = CanonicalFormatterProvider(mutate_step=mutate_step)
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
    def mutate(step, _stage, call):
        if call >= 2:
            return {**step, "assertions": step["assertions"][:-1]}
        return step

    provider = CanonicalFormatterProvider(mutate_step=mutate)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed) as exc:
        service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(), _branch_convergence_graph())})(), plan=_plan("en"))

    assert len(exc.value.failed_graph_ids) == 1
    assert service.pipeline_records[-1]["selectedGraphCount"] == 2
    assert service.pipeline_records[-1]["answerCount"] == 0


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


def _without_query_entries(graph):
    return type(graph)(
        stable_graph_id=graph.stable_graph_id,
        unit_refs=graph.unit_refs,
        query_entry_unit_ids=(),
        topology_entry_unit_ids=graph.topology_entry_unit_ids,
        proven_cross_source_transitions=graph.proven_cross_source_transitions,
        open_boundaries=graph.open_boundaries,
        coverage=graph.coverage,
        diagnostics=graph.diagnostics,
    )
