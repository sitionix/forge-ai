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


class CanonicalFormatterProvider:
    def __init__(self, *, bad_first: bool = False, open_as_proven: bool = False) -> None:
        self.calls = []
        self.bad_first = bad_first
        self.open_as_proven = open_as_proven

    def generate(self, formatter_input, *, deadline_at, cancel_event, validation_errors=()):
        del deadline_at, cancel_event
        self.calls.append({"input": dict(formatter_input), "validationErrors": list(validation_errors or [])})
        stages = list(formatter_input.get("stages") or [])
        if self.bad_first and len(self.calls) == 1:
            stages = stages[:-1]
        language = str(formatter_input.get("responseLanguage") or "en")
        steps = [
            {
                "stageRef": stage["stageRef"],
                "coveredFactRefs": list(stage.get("ownedFactRefs") or []),
                "text": self._text(stage, language),
            }
            for stage in stages
        ]
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

    def _text(self, stage, language: str) -> str:
        label = self._label(stage)
        kind = stage.get("kind")
        if self.open_as_proven and str(kind).startswith("OPEN_BOUNDARY"):
            return f"This proven selected target explains the verified continuation for {label}."
        if language == "uk":
            return f"Ця відповідь пояснює канонічний етап виконання з перевіреними фактами для {label}."
        if language == "fr":
            return f"Cette réponse explique l'étape canonique d'exécution avec des faits établis pour {label}."
        if language == "de":
            return f"Diese Antwort erklärt den kanonischen Ausführungsschritt mit belegten Fakten für {label}."
        return f"This response explains the canonical execution stage with grounded facts for {label}."

    def _label(self, stage) -> str:
        payload = stage.get("payload") or {}
        for value in (
            payload.get("unitId"),
            payload.get("transitionId"),
            payload.get("sourceUnitId"),
            payload.get("targetUnitId"),
            (payload.get("requiredBoundary") or {}).get("boundaryKey") if isinstance(payload.get("requiredBoundary"), dict) else None,
        ):
            if value:
                return str(value)
        return str(stage.get("kind") or stage.get("stageRef"))


def test_formatter_calls_provider_and_validates_distinct_answer_languages():
    graph = _linear_graph()
    texts = {}
    for language in ("en", "uk", "fr", "de"):
        provider = CanonicalFormatterProvider()
        service = EndToEndFormatterAnswerService(provider)

        result = service.answer(None, type("Execution", (), {"selected_graphs": (graph,)})(), plan=_plan(language))

        assert result.metrics["formatterProviderCallCount"] == 1
        assert result.metrics["formatterRepairCallCount"] == 0
        assert result.metrics["validatedFormatterStepCount"] == result.metrics["presentationStageCount"]
        assert provider.calls[0]["input"]["responseLanguage"] == language
        texts[language] = result.answers[0].text
    assert len(set(texts.values())) == 4


def test_formatter_uses_one_bounded_repair_attempt_with_exact_validation_errors():
    provider = CanonicalFormatterProvider(bad_first=True)
    service = EndToEndFormatterAnswerService(provider)

    result = service.answer(None, type("Execution", (), {"selected_graphs": (_linear_graph(),)})(), plan=_plan("en"))

    assert result.metrics["formatterProviderCallCount"] == 2
    assert result.metrics["formatterRepairCallCount"] == 1
    assert provider.calls[1]["validationErrors"]


def test_formatter_rejects_open_boundary_described_as_proven_after_repair():
    provider = CanonicalFormatterProvider(open_as_proven=True)
    service = EndToEndFormatterAnswerService(provider)

    with pytest.raises(EndToEndFormatterAllGraphsFailed):
        service.answer(None, type("Execution", (), {"selected_graphs": (_ambiguous_graph(),)})(), plan=_plan("en"))

    assert len(provider.calls) == 2
    assert provider.calls[1]["validationErrors"]


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
