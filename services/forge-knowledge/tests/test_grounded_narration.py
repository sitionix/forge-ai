from __future__ import annotations

import hashlib
import json
from dataclasses import replace
from types import SimpleNamespace

import pytest

from knowledge_service.flow_explanations import (
    FlowExplanationProviderResult,
    HumanAnswerContractViolation,
    HumanAnswerPromptRenderer,
    HumanFlowAnswerService,
    PromptBudgetEstimator,
)
from knowledge_service.grounded_narration import (
    EvidenceSlice,
    EvidenceWorkItem,
    GroundedClaimAssembler,
    GroundedClaimService,
    GroundedClaimValidator,
    GroundedNarrationError,
    GroundingBatch,
    GroundingBatchPlanner,
    GroundingPromptFactory,
    HumanNarrationStage,
    LosslessEvidenceSplitter,
    NarrativeFactDescriptor,
    NarrativeFactUnit,
    NarrativeProjection,
    NarrationAtom,
    NarrationSegmentPlanner,
    GroundedNarrativeClaim,
    NarrationAtomPlanner,
)


def _hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _descriptor(ref: str = "u1", **kwargs) -> NarrativeFactDescriptor:
    values = {
        "ref": ref,
        "fact_kind": "operation",
        "certainty": "VERIFIED",
        "source": "source-a",
        "symbol": "Unit.run",
    }
    values.update(kwargs)
    return NarrativeFactDescriptor(**values)


def _work_item(text: str, *, work_ref: str = "w1", unit_ref: str = "u1", order: int = 1, source: str = "source-a") -> EvidenceWorkItem:
    return EvidenceWorkItem(
        work_ref=work_ref,
        narrative_plan_ref="plan-1",
        fragment_ref="fragment-1",
        unit_ref=unit_ref,
        original_evidence_owner={"ownerKind": "NODE", "ownerSourceId": source, "evidenceIdentity": work_ref},
        evidence_source="NODE_DESCRIPTION",
        source=source,
        path="src/Neutral.java",
        line_start=10,
        line_end=12,
        exact_text=text,
        order=order,
        utf8_hash=_hash(text),
    )


def _slice(
    ref: str,
    text: str,
    *,
    work_ref: str = "w1",
    unit_ref: str = "u1",
    evidence_order: int = 1,
    slice_order: int = 1,
) -> EvidenceSlice:
    return EvidenceSlice(
        slice_ref=ref,
        work_ref=work_ref,
        unit_ref=unit_ref,
        source="source-a",
        path="src/Neutral.java",
        line_start=1,
        line_end=1,
        text=text,
        evidence_order=evidence_order,
        slice_order=slice_order,
        offset_start=0,
        offset_end=len(text),
        utf8_hash=_hash(text),
        original_utf8_hash=_hash(text),
    )


def _grounding_len(text: str, *, descriptor: NarrativeFactDescriptor | None = None, item: EvidenceWorkItem | None = None) -> int:
    descriptor = descriptor or _descriptor()
    item = item or _work_item(text)
    factory = GroundingPromptFactory()
    llm_input, _ = factory.build(
        original_question="Explain the neutral flow",
        response_language="en",
        descriptors_by_ref={descriptor.ref: descriptor},
        slices=(factory.probe_slice(item, text),),
    )
    return len(HumanAnswerPromptRenderer().render(llm_input).encode("utf-8"))


def _splitter(context_tokens: int, descriptors: dict[str, NarrativeFactDescriptor] | None = None) -> LosslessEvidenceSplitter:
    return LosslessEvidenceSplitter(
        renderer=HumanAnswerPromptRenderer(),
        budget_estimator=PromptBudgetEstimator(context_tokens=context_tokens, reserved_output_tokens=0, fixed_framing_reserve_tokens=0),
        descriptors_by_ref=descriptors or {"u1": _descriptor()},
        original_question="Explain the neutral flow",
        response_language="en",
    )


def _valid_grounding_payload() -> dict:
    return {
        "claims": [
            {
                "claimRef": "c1",
                "unitRef": "u1",
                "evidenceRefs": ["e1"],
                "text": "The unit calls the worker and continues execution.",
            }
        ],
        "processedEvidence": [
            {"evidenceRef": "e1", "disposition": "CLAIMED", "claimRefs": ["c1"]},
            {"evidenceRef": "e2", "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []},
        ],
    }


def _grounding_batch() -> GroundingBatch:
    llm_input = {
        "promptKind": "GROUNDING",
        "responseLanguage": "en",
        "units": [
            _descriptor("u1", route="/supported/path").to_prompt_dict(),
        ],
        "evidenceSlices": [
            {"evidenceRef": "e1", "unitRef": "u1", "text": "unit calls worker"},
            {"evidenceRef": "e2", "unitRef": "u1", "text": "same behavior repeated"},
        ],
        "coverageContract": {"evidenceRefs": ["e1", "e2"], "unitRefs": ["u1"]},
    }
    return GroundingBatch(
        index=1,
        total=1,
        llm_input=llm_input,
        evidence_ref_to_slice_ref={"e1": "s1", "e2": "s2"},
        slice_ref_to_evidence_ref={"s1": "e1", "s2": "e2"},
    )


def test_lossless_splitter_keeps_small_evidence_as_one_slice():
    text = "the unit calls a downstream worker"
    slices = _splitter(20_000).split([_work_item(text)])

    assert [item.text for item in slices] == [text]
    assert _hash("".join(item.text for item in slices)) == _hash(text)


def test_lossless_splitter_prefers_line_boundaries_for_multiline_evidence():
    line = "line keeps one complete behavior " + ("x" * 240) + "\n"
    text = line * 4
    context = _grounding_len(line) + 8

    slices = _splitter(context).split([_work_item(text)])

    assert len(slices) == 4
    assert all(item.text == line for item in slices)
    assert "".join(item.text for item in slices) == text
    assert _hash("".join(item.text for item in slices)) == _hash(text)


def test_lossless_splitter_subdivides_one_oversized_line_at_unicode_codepoints():
    text = "αЖ漢🙂" * 80
    context = _grounding_len(text[:12]) + 8

    slices = _splitter(context).split([_work_item(text)])

    assert len(slices) > 1
    assert "".join(item.text for item in slices).encode("utf-8") == text.encode("utf-8")
    assert all(item.text.encode("utf-8").decode("utf-8") == item.text for item in slices)


def test_lossless_splitter_preserves_multilingual_hash_closure():
    text = "English Українська العربية हिन्दी 漢字 emoji 🙂🚀\n" * 5

    slices = _splitter(40_000).split([_work_item(text)])

    joined = "".join(item.text for item in slices)
    assert joined == text
    assert _hash(joined) == _hash(text)


def test_empty_evidence_produces_no_slice_and_structural_fact_remains_available():
    descriptor = _descriptor("u1", fact_kind="gap", certainty="UNVERIFIED", from_symbol="Upstream.run", to_symbol="Worker.run")
    slices = _splitter(20_000, {"u1": descriptor}).split([_work_item("", unit_ref="u1")])
    projection = NarrativeProjection(
        narrative_plan_ref="plan-1",
        source="source-a",
        entrypoint="Upstream.run",
        units=(NarrativeFactUnit(descriptor, 1, 1, 1),),
        evidence_work_items=(),
    )

    atoms = NarrationAtomPlanner().plan(projection, ())

    assert slices == ()
    assert len(atoms) == 1
    assert atoms[0].atom_kind == "UNVERIFIED_GAP"
    assert atoms[0].certainty == "UNVERIFIED"


def test_repeated_looking_evidence_records_remain_owner_distinct():
    text = "identical looking persisted evidence"
    items = [
        _work_item(text, work_ref="w1", source="source-a", order=1),
        _work_item(text, work_ref="w2", source="source-b", order=2),
        _work_item(text, work_ref="w3", source="source-c", order=3),
    ]

    slices = _splitter(20_000).split(items)

    assert [item.work_ref for item in slices] == ["w1", "w2", "w3"]
    assert [item.text for item in slices] == [text, text, text]
    assert len({(item.work_ref, item.original_utf8_hash) for item in slices}) == 3


def test_minimum_context_impossible_fails_before_grounding_provider_call():
    calls = []
    with pytest.raises(GroundedNarrationError) as exc:
        _splitter(1).split([_work_item("x")])

    assert exc.value.stage is HumanNarrationStage.GROUNDING_SPLIT
    assert exc.value.diagnostic_code == "HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED"
    assert calls == []


def test_grounding_validator_accounts_for_every_slice_once_and_no_new_behavior():
    payload = GroundedClaimValidator().validate(json.dumps(_valid_grounding_payload()), _grounding_batch(), response_language="en")

    assert [item["evidenceRef"] for item in payload["processedEvidence"]] == ["e1", "e2"]
    assert payload["processedEvidence"][1]["disposition"] == "NO_NEW_BEHAVIOR"


def test_grounding_validator_normalizes_missing_accounting_to_no_new_behavior():
    payload = _valid_grounding_payload()
    payload["processedEvidence"].pop()

    result = GroundedClaimValidator().validate(json.dumps(payload), _grounding_batch(), response_language="en")

    assert [item["evidenceRef"] for item in result["processedEvidence"]] == ["e1", "e2"]
    assert result["processedEvidence"][1] == {"evidenceRef": "e2", "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}


@pytest.mark.parametrize(
    ("mutate", "expected"),
    [
        (lambda payload: payload["processedEvidence"].append({"evidenceRef": "e3", "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}), "foreign evidence"),
        (lambda payload: payload["claims"][0].update({"unitRef": "u2"}), "foreign unit owner"),
        (lambda payload: payload["claims"][0].update({"evidenceRefs": []}), "at least one evidence ref"),
        (lambda payload: payload["claims"][0].update({"text": "The graphId shows nodeId evidenceId while execution continues."}), "persisted graph or evidence ids"),
        (lambda payload: payload["claims"][0].update({"text": "The unit returns HTTP 500 through /unsupported/path after completing the operation."}), "unsupported"),
    ],
)
def test_grounding_validator_rejects_invalid_grounding_contracts(mutate, expected):
    payload = _valid_grounding_payload()
    mutate(payload)

    with pytest.raises(GroundedNarrationError) as exc:
        GroundedClaimValidator().validate(json.dumps(payload), _grounding_batch(), response_language="en")

    assert expected.lower() in str(exc.value).lower()


def test_grounding_validator_rejects_wrong_response_language():
    payload = _valid_grounding_payload()
    payload["claims"][0]["text"] = "The worker receives control and finishes downstream processing with a verified result."

    with pytest.raises(GroundedNarrationError) as exc:
        GroundedClaimValidator().validate(json.dumps(payload), _grounding_batch(), response_language="uk")

    assert "language" in str(exc.value).lower()


def test_grounding_validator_rejects_substantial_raw_evidence_copy():
    raw = "RAW_EVIDENCE_SENTINEL " + ("full persisted evidence excerpt " * 4)
    batch = _grounding_batch()
    batch = GroundingBatch(
        index=batch.index,
        total=batch.total,
        llm_input={
            **batch.llm_input,
            "evidenceSlices": [
                {"evidenceRef": "e1", "unitRef": "u1", "text": raw},
                {"evidenceRef": "e2", "unitRef": "u1", "text": "same behavior repeated"},
            ],
        },
        evidence_ref_to_slice_ref=batch.evidence_ref_to_slice_ref,
        slice_ref_to_evidence_ref=batch.slice_ref_to_evidence_ref,
    )
    payload = _valid_grounding_payload()
    payload["claims"][0]["text"] = raw

    with pytest.raises(GroundedNarrationError) as exc:
        GroundedClaimValidator().validate(json.dumps(payload), batch, response_language="en")

    assert "raw evidence slice" in str(exc.value)


def test_grounding_validator_rejects_short_exact_raw_evidence_copy():
    batch = GroundingBatch(
        index=1,
        total=1,
        llm_input={
            "promptKind": "GROUNDING",
            "responseLanguage": "en",
            "units": [_descriptor("u1").to_prompt_dict()],
            "evidenceSlices": [{"evidenceRef": "e1", "unitRef": "u1", "text": "calls worker"}],
            "coverageContract": {"evidenceRefs": ["e1"], "unitRefs": ["u1"]},
        },
        evidence_ref_to_slice_ref={"e1": "s1"},
        slice_ref_to_evidence_ref={"s1": "e1"},
    )
    payload = {
        "claims": [{"claimRef": "c1", "unitRef": "u1", "evidenceRefs": ["e1"], "text": "calls worker"}],
        "processedEvidence": [{"evidenceRef": "e1", "disposition": "CLAIMED", "claimRefs": ["c1"]}],
    }

    with pytest.raises(GroundedNarrationError) as exc:
        GroundedClaimValidator().validate(json.dumps(payload), batch, response_language="en")

    assert "raw evidence slice" in str(exc.value)


class _AlwaysValidText:
    def validate(self, text, language):
        return SimpleNamespace(valid=True, errors=[])


def test_descriptor_owned_literals_do_not_trigger_raw_copy_false_positive():
    descriptor = _descriptor("u1", method="POST", route="/neutral/path", symbol="Unit.run")
    batch = GroundingBatch(
        index=1,
        total=1,
        llm_input={
            "promptKind": "GROUNDING",
            "responseLanguage": "en",
            "units": [descriptor.to_prompt_dict()],
            "evidenceSlices": [{"evidenceRef": "e1", "unitRef": "u1", "text": "POST /neutral/path Unit.run"}],
            "coverageContract": {"evidenceRefs": ["e1"], "unitRefs": ["u1"]},
        },
        evidence_ref_to_slice_ref={"e1": "s1"},
        slice_ref_to_evidence_ref={"s1": "e1"},
    )
    payload = {
        "claims": [{"claimRef": "c1", "unitRef": "u1", "evidenceRefs": ["e1"], "text": "POST /neutral/path Unit.run"}],
        "processedEvidence": [{"evidenceRef": "e1", "disposition": "CLAIMED", "claimRefs": ["c1"]}],
    }

    result = GroundedClaimValidator(text_validator=_AlwaysValidText()).validate(json.dumps(payload), batch, response_language="en")

    assert result["claims"][0]["text"] == "POST /neutral/path Unit.run"


def test_structural_literal_only_evidence_may_be_no_new_behavior():
    descriptor = _descriptor("u1", method="POST", route="/neutral/path", symbol="Unit.run")
    batch = GroundingBatch(
        index=1,
        total=1,
        llm_input={
            "promptKind": "GROUNDING",
            "responseLanguage": "en",
            "units": [descriptor.to_prompt_dict()],
            "evidenceSlices": [{"evidenceRef": "e1", "unitRef": "u1", "text": "POST /neutral/path Unit.run"}],
            "coverageContract": {"evidenceRefs": ["e1"], "unitRefs": ["u1"]},
        },
        evidence_ref_to_slice_ref={"e1": "s1"},
        slice_ref_to_evidence_ref={"s1": "e1"},
    )
    payload = {
        "claims": [],
        "processedEvidence": [{"evidenceRef": "e1", "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}],
    }

    result = GroundedClaimValidator(text_validator=_AlwaysValidText()).validate(json.dumps(payload), batch, response_language="en")

    assert result["processedEvidence"][0]["disposition"] == "NO_NEW_BEHAVIOR"


def test_grounding_repair_that_still_copies_raw_evidence_fails_family():
    batch = GroundingBatch(
        index=1,
        total=1,
        llm_input={
            "promptKind": "GROUNDING",
            "responseLanguage": "en",
            "units": [_descriptor("u1").to_prompt_dict()],
            "evidenceSlices": [{"evidenceRef": "e1", "unitRef": "u1", "text": "raw behavior prose"}],
            "coverageContract": {"evidenceRefs": ["e1"], "unitRefs": ["u1"]},
        },
        evidence_ref_to_slice_ref={"e1": "s1"},
        slice_ref_to_evidence_ref={"s1": "e1"},
    )
    payload = {
        "claims": [{"claimRef": "c1", "unitRef": "u1", "evidenceRefs": ["e1"], "text": "raw behavior prose"}],
        "processedEvidence": [{"evidenceRef": "e1", "disposition": "CLAIMED", "claimRefs": ["c1"]}],
    }
    attempts = []

    def complete(llm_input, validation_errors, stage, batch_index, batch_total):
        attempts.append(validation_errors)
        return json.dumps(payload)

    with pytest.raises(GroundedNarrationError):
        GroundedClaimService(GroundedClaimValidator(text_validator=_AlwaysValidText())).ground((batch,), response_language="en", complete=complete)

    assert len(attempts) == 2
    assert attempts[1]


def test_grounded_claim_service_normalizes_missing_processed_evidence_without_repair():
    attempts = []
    invalid = _valid_grounding_payload()
    invalid["processedEvidence"] = invalid["processedEvidence"][:1]

    def complete(llm_input, validation_errors, stage, batch_index, batch_total):
        attempts.append((validation_errors, stage, batch_index, batch_total))
        return json.dumps(invalid)

    payloads = GroundedClaimService().ground((_grounding_batch(),), response_language="en", complete=complete)

    assert len(payloads) == 1
    assert len(attempts) == 1
    assert attempts[0][0] is None
    assert payloads[0]["processedEvidence"][1] == {"evidenceRef": "e2", "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}


def test_grounding_batch_planner_uses_dynamic_batch_count_and_fitting_prompts():
    descriptor = _descriptor()
    slices = tuple(_slice(f"s{index}", "slice text " + ("x" * 180), evidence_order=index) for index in range(1, 7))
    renderer = HumanAnswerPromptRenderer()
    output_reserve = 10_000
    one_slice_input, _ = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=output_reserve),
    )._batch_input("Explain the neutral flow", "en", {"u1": descriptor}, slices[:1], index=1, total=1)
    context = len(renderer.render(one_slice_input).encode("utf-8")) + output_reserve + 16
    planner = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=context, reserved_output_tokens=output_reserve, fixed_framing_reserve_tokens=0),
    )

    plan = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        descriptors_by_ref={"u1": descriptor},
        evidence_slices=slices,
    )

    assert len(plan.batches) > 1
    assert [batch.total for batch in plan.batches] == [len(plan.batches)] * len(plan.batches)
    assert [ref for batch in plan.batches for ref in batch.evidence_ref_to_slice_ref.values()] == [item.slice_ref for item in slices]
    assert all(PromptBudgetEstimator(context_tokens=context, reserved_output_tokens=output_reserve).estimate(renderer.render(batch.llm_input)).fits for batch in plan.batches)


def test_grounding_prompt_factory_removes_batch_count_and_preserves_planned_provider_hash():
    descriptor = _descriptor()
    renderer = HumanAnswerPromptRenderer()
    planner = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=2_000),
    )
    plan = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        descriptors_by_ref={"u1": descriptor},
        evidence_slices=(_slice("s1", "unit calls worker"),),
    )

    batch = plan.batches[0]
    prompt = renderer.render(batch.llm_input)
    assert "batch" not in batch.llm_input
    assert batch.planned_initial_prompt_hash == _hash(prompt)
    assert batch.budget_metrics["minimumValidOutputTokens"] <= batch.budget_metrics["reservedOutputTokens"]


def test_complete_canonical_splitter_prompt_overflow_triggers_lossless_split():
    descriptor = _descriptor(
        outgoing_transitions=tuple(f"t{index}" for index in range(40)),
        operation_identity="operation.identity.with.extra.metadata",
        interface_identity="interface.identity.with.extra.metadata",
    )
    text = "".join(f"line {index} keeps behavior\n" for index in range(1, 40))
    item = _work_item(text)
    renderer = HumanAnswerPromptRenderer()
    reduced_evidence = {
        "evidenceRef": "e1",
        "unitRef": "u1",
        "source": item.source,
        "path": item.path,
        "lineStart": item.line_start,
        "lineEnd": item.line_end,
        "text": text,
    }
    old_reduced_input = {
        "promptKind": "GROUNDING",
        "originalQuestion": "Explain the neutral flow",
        "responseLanguage": "en",
        "units": [descriptor.to_prompt_dict()],
        "evidenceSlices": [reduced_evidence],
        "coverageContract": {"evidenceRefs": ["e1"], "unitRefs": ["u1"]},
    }
    factory = GroundingPromptFactory()
    canonical_input, _ = factory.build(
        original_question="Explain the neutral flow",
        response_language="en",
        descriptors_by_ref={"u1": descriptor},
        slices=(factory.probe_slice(item, text),),
    )
    context = len(renderer.render(old_reduced_input).encode("utf-8")) + 8
    assert len(renderer.render(old_reduced_input).encode("utf-8")) <= context
    assert len(renderer.render(canonical_input).encode("utf-8")) > context

    slices = _splitter(context, {"u1": descriptor}).split([item])

    assert len(slices) > 1
    assert "".join(slice_item.text for slice_item in slices) == text
    for slice_item in slices:
        llm_input, _ = factory.build(
            original_question="Explain the neutral flow",
            response_language="en",
            descriptors_by_ref={"u1": descriptor},
            slices=(slice_item,),
        )
        assert PromptBudgetEstimator(context_tokens=context, reserved_output_tokens=0).estimate(renderer.render(llm_input)).fits


def test_grounding_batch_planner_accounts_for_minimum_output_reserve():
    descriptor = _descriptor()
    slices = tuple(_slice(f"s{index}", "tiny evidence", evidence_order=index) for index in range(1, 6))
    renderer = HumanAnswerPromptRenderer()
    calibration = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=50_000),
    )
    output_reserve = calibration._estimated_grounding_output_tokens(slices[:2], {"u1": descriptor})
    planner = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=output_reserve, fixed_framing_reserve_tokens=0),
    )

    plan = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        descriptors_by_ref={"u1": descriptor},
        evidence_slices=slices,
    )

    assert [len(batch.evidence_ref_to_slice_ref) for batch in plan.batches] == [2, 2, 1]
    assert [ref for batch in plan.batches for ref in batch.evidence_ref_to_slice_ref.values()] == [item.slice_ref for item in slices]


def test_grounding_batch_planner_has_no_single_slice_output_budget_bypass():
    descriptor = _descriptor()
    renderer = HumanAnswerPromptRenderer()
    planner = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=1, fixed_framing_reserve_tokens=0),
    )

    with pytest.raises(GroundedNarrationError) as exc:
        planner.plan(
            original_question="Explain the neutral flow",
            response_language="en",
            descriptors_by_ref={"u1": descriptor},
            evidence_slices=(_slice("s1", "tiny evidence"),),
        )

    assert exc.value.stage is HumanNarrationStage.GROUNDING_BATCHING


def test_adaptive_grounding_output_split_preserves_each_slice_once():
    descriptor = _descriptor()
    renderer = HumanAnswerPromptRenderer()
    slices = tuple(_slice(f"s{index}", f"evidence {index}", evidence_order=index) for index in range(1, 5))
    plan = GroundingBatchPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=2_000),
    ).plan(
        original_question="Explain the neutral flow",
        response_language="en",
        descriptors_by_ref={"u1": descriptor},
        evidence_slices=slices,
    )
    calls = []

    def complete(llm_input, validation_errors, stage, batch_index, batch_total):
        calls.append((tuple(item["evidenceRef"] for item in llm_input["evidenceSlices"]), validation_errors))
        if len(calls) == 1:
            return SimpleNamespace(raw_text='{"claims":[', done_reason="length")
        return SimpleNamespace(
            raw_text=json.dumps(
                {
                    "claims": [],
                    "processedEvidence": [
                        {"evidenceRef": item["evidenceRef"], "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}
                        for item in llm_input["evidenceSlices"]
                    ],
                }
            ),
            done_reason="stop",
        )

    service = GroundedClaimService()
    payloads = service.ground(plan.batches, response_language="en", complete=complete)

    accepted_refs = [ref for batch in service.last_accepted_batches for ref in batch.evidence_ref_to_slice_ref.values()]
    assert len(payloads) == 2
    assert sorted(accepted_refs) == [item.slice_ref for item in slices]
    assert len(accepted_refs) == len(set(accepted_refs))
    assert calls[0][1] is None
    assert all(call[1] is None for call in calls)


def test_claim_assembly_preserves_no_new_behavior_accounting_and_owner_order():
    descriptor = _descriptor()
    projection = NarrativeProjection(
        narrative_plan_ref="plan-1",
        source="source-a",
        entrypoint="Unit.run",
        units=(NarrativeFactUnit(descriptor, 1, 1, 1),),
        evidence_work_items=(),
    )
    batch = _grounding_batch()
    payload = _valid_grounding_payload()

    claims = GroundedClaimAssembler().assemble(projection=projection, batches=(batch,), payloads=(payload,))

    assert len(claims) == 1
    assert claims[0].unit_ref == "u1"
    assert claims[0].evidence_slice_refs == ("s1",)


def _atoms() -> tuple[NarrationAtom, ...]:
    return (
        NarrationAtom(
            ref="a1",
            atom_kind="VERIFIED_CLAIM",
            unit_ref="u1",
            certainty="VERIFIED",
            descriptor=_descriptor("u1", symbol="Start.run", outgoing_transitions=("u2",)),
            claims=(
                GroundedNarrativeClaim("c1", "u1", "VERIFIED", "The start unit invokes the first worker.", ("s1",), 1, 1),
            ),
            canonical_order=1,
        ),
        NarrationAtom(
            ref="a2",
            atom_kind="VERIFIED_CLAIM",
            unit_ref="u2",
            certainty="VERIFIED",
            descriptor=_descriptor("u2", symbol="Worker.run"),
            claims=(
                GroundedNarrativeClaim("c2", "u2", "VERIFIED", "The worker completes the verified operation.", ("s2",), 2, 2),
            ),
            canonical_order=2,
        ),
        NarrationAtom(
            ref="a3",
            atom_kind="UNVERIFIED_GAP",
            unit_ref="u3",
            certainty="UNVERIFIED",
            descriptor=_descriptor(
                "u3",
                fact_kind="gap",
                certainty="UNVERIFIED",
                from_symbol="Worker.run",
                to_symbol="Continuation.run",
                gap_verification_status="UNVERIFIED",
            ),
            canonical_order=3,
        ),
        NarrationAtom(
            ref="a4",
            atom_kind="TERMINAL_RESULT_CLAIM",
            unit_ref="u4",
            certainty="VERIFIED",
            descriptor=_descriptor("u4", symbol="Continuation.run", terminal_role="TERMINAL"),
            claims=(
                GroundedNarrativeClaim("c3", "u4", "VERIFIED", "The continuation reports the final result.", ("s3",), 4, 3),
            ),
            canonical_order=4,
        ),
    )


def test_final_narration_segmenter_splits_only_between_atoms_and_excludes_raw_evidence_or_previous_prose():
    atoms = _atoms()
    renderer = HumanAnswerPromptRenderer()
    calibration_planner = NarrationSegmentPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=0),
    )
    single_atom_inputs = [
        calibration_planner._segment_input(
            "Explain the neutral flow",
            "en",
            "source-a",
            "Start.run",
            (atom,),
            atoms,
            index=index,
            total=len(atoms),
            terminal=index == len(atoms),
        )
        for index, atom in enumerate(atoms, start=1)
    ]
    context = max(len(renderer.render(llm_input).encode("utf-8")) for llm_input in single_atom_inputs) + 32
    planner = NarrationSegmentPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=context, reserved_output_tokens=0, fixed_framing_reserve_tokens=0),
    )

    plan = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        source="source-a",
        entrypoint="Start.run",
        atoms=atoms,
    )

    assert len(plan.segments) > 1
    covered = [ref for segment in plan.segments for ref in segment.llm_input["coverageContract"]["requiredAtomRefs"]]
    assert covered == ["a1", "a2", "a3", "a4"]
    assert any(segment.llm_input["coverageContract"]["requiredAtomRefs"] == ["a3"] for segment in plan.segments)
    assert [segment.terminal for segment in plan.segments].count(True) == 1
    prompts = [renderer.render(segment.llm_input) for segment in plan.segments]
    assert "RAW_EVIDENCE_SENTINEL" not in "\n".join(prompts)
    assert "generated prose from a previous segment" not in "\n".join(prompts)
    assert all(PromptBudgetEstimator(context_tokens=context, reserved_output_tokens=0).estimate(prompt).fits for prompt in prompts)


def test_narration_segmenter_has_no_single_atom_output_budget_bypass():
    with pytest.raises(GroundedNarrationError) as exc:
        NarrationSegmentPlanner(
            renderer=HumanAnswerPromptRenderer(),
            budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=1),
        ).plan(
            original_question="Explain the neutral flow",
            response_language="en",
            source="source-a",
            entrypoint="Start.run",
            atoms=(_atoms()[0],),
        )

    assert exc.value.stage is HumanNarrationStage.NARRATION_SEGMENTATION


def test_one_claim_heavy_atom_partitions_claims_once_and_preserves_terminal_role():
    claims = tuple(
        GroundedNarrativeClaim(f"c{index}", "u1", "VERIFIED", "claim text " + ("x" * 160), (f"s{index}",), 1, index)
        for index in range(1, 8)
    )
    atom = NarrationAtom(
        ref="a1",
        atom_kind="TERMINAL_RESULT_CLAIM",
        unit_ref="u1",
        certainty="VERIFIED",
        descriptor=_descriptor("u1", terminal_role="TERMINAL"),
        claims=claims,
        canonical_order=1,
    )
    renderer = HumanAnswerPromptRenderer()
    calibration = NarrationSegmentPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=10_000),
    )
    one_claim_atom = replace(atom, claims=claims[:1])
    context = len(renderer.render(calibration._segment_input("Explain", "en", "source-a", "Start.run", (one_claim_atom,), (one_claim_atom,), index=1, total=1, terminal=True)).encode("utf-8")) + 10_000 + 128
    planner = NarrationSegmentPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=context, reserved_output_tokens=10_000),
    )

    plan = planner.plan(
        original_question="Explain",
        response_language="en",
        source="source-a",
        entrypoint="Start.run",
        atoms=(atom,),
    )

    partitioned_atoms = [atom for segment in plan.segments for atom in segment.atoms]
    claim_refs = [claim.claim_ref for atom in partitioned_atoms for claim in atom.claims]
    terminal_atoms = [atom for atom in partitioned_atoms if atom.descriptor.terminal_role == "TERMINAL"]
    assert len(partitioned_atoms) > 1
    assert sorted(claim_refs) == [claim.claim_ref for claim in claims]
    assert len(claim_refs) == len(set(claim_refs))
    assert terminal_atoms == [partitioned_atoms[-1]]


def test_final_narration_validation_allows_adjacent_verified_claims_but_rejects_verified_gap():
    service = HumanFlowAnswerService(provider=SimpleNamespace())
    renderer = HumanAnswerPromptRenderer()
    planner = NarrationSegmentPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=0),
    )
    verified_segment = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        source="source-a",
        entrypoint="Start.run",
        atoms=_atoms()[:2],
    ).segments[0]
    service._validate_payload(
        json.dumps(
            {
                "steps": [
                    {
                        "atomRefs": ["a1", "a2"],
                        "certainty": "VERIFIED",
                        "text": "The start unit invokes the worker, and the worker completes the verified operation.",
                    }
                ],
                "result": "The terminal result is available.",
            }
        ),
        "en",
        verified_segment.llm_input,
    )
    gap_segment = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        source="source-a",
        entrypoint="Start.run",
        atoms=(_atoms()[2],),
    ).segments[0]

    with pytest.raises(HumanAnswerContractViolation) as exc:
        service._validate_payload(
            json.dumps(
                {
                    "steps": [
                        {
                            "atomRefs": ["a3"],
                            "certainty": "VERIFIED",
                            "text": "The graph verifies the direct transition to the continuation.",
                        }
                    ],
                    "result": "The terminal result is available.",
                }
            ),
            "en",
            gap_segment.llm_input,
        )

    assert "unverified gap as verified" in str(exc.value)


def test_final_narration_validation_rejects_non_terminal_result_and_internal_refs():
    service = HumanFlowAnswerService(provider=SimpleNamespace())
    renderer = HumanAnswerPromptRenderer()
    planner = NarrationSegmentPlanner(
        renderer=renderer,
        budget_estimator=PromptBudgetEstimator(context_tokens=50_000, reserved_output_tokens=0),
    )
    segment = planner.plan(
        original_question="Explain the neutral flow",
        response_language="en",
        source="source-a",
        entrypoint="Start.run",
        atoms=_atoms()[:1],
    ).segments[0]
    non_terminal_input = {
        **segment.llm_input,
        "segment": {**segment.llm_input["segment"], "terminal": False},
    }

    with pytest.raises(HumanAnswerContractViolation) as exc:
        service._validate_payload(
            json.dumps(
                {
                    "steps": [
                        {
                            "atomRefs": ["a1"],
                            "certainty": "VERIFIED",
                            "text": "The first atom a1 exposes an internal local ref.",
                        }
                    ],
                    "result": "A non-terminal segment should not return a result.",
                }
            ),
            "en",
            non_terminal_input,
        )

    assert "Non-terminal segment result" in str(exc.value)
    with pytest.raises(HumanAnswerContractViolation) as leaked:
        service._validate_payload(
            json.dumps(
                {
                    "steps": [
                        {
                            "atomRefs": ["a1"],
                            "certainty": "VERIFIED",
                            "text": "The first atom a1 exposes an internal local ref.",
                        }
                    ],
                    "result": None,
                }
            ),
            "en",
            segment.llm_input,
        )

    assert "internal graph refs" in str(leaked.value)


def test_tool_context_style_flow_is_not_required_for_grounding_projection_components():
    descriptor = _descriptor(
        "u1",
        fact_kind="transition",
        transition_kind="ASYNC_CONTINUATION",
        transport_kind="MESSAGE",
        from_symbol="ExternalTrigger",
        to_symbol="DownstreamWorker",
    )
    slices = _splitter(20_000, {"u1": descriptor}).split([
        _work_item("external trigger schedules an asynchronous continuation", unit_ref="u1")
    ])
    plan = GroundingBatchPlanner(
        renderer=HumanAnswerPromptRenderer(),
        budget_estimator=PromptBudgetEstimator(context_tokens=20_000, reserved_output_tokens=2_000),
    ).plan(
        original_question="Explain the neutral asynchronous continuation",
        response_language="en",
        descriptors_by_ref={"u1": descriptor},
        evidence_slices=slices,
    )

    assert len(plan.batches) == 1
    assert plan.batches[0].llm_input["units"][0]["transitionKind"] == "ASYNC_CONTINUATION"
    assert plan.batches[0].llm_input["units"][0]["transportKind"] == "MESSAGE"
