from __future__ import annotations

import hashlib
import json
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.canonical_narration_contract import (
    CanonicalNarrationClause,
    CanonicalNarrationPlan,
    NarrationClauseKind,
    NarrationSemanticOperation,
)
from knowledge_service.canonical_narration_planner import CanonicalNarrationPlanner
from knowledge_service.formatter_policy import FormatterPolicy
from knowledge_service.formatter_protocol import (
    EndToEndFormatterAllGraphsFailed,
    EndToEndFormatterClauseTooLarge,
    EndToEndFormatterDeadlineExceeded,
    EndToEndFormatterError,
    EndToEndFormatterProviderError,
    EndToEndFormatterProviderResult,
    EndToEndFormatterSegment,
    EndToEndFormatterValidationError,
    ValidatedFormatterClause,
)
from knowledge_service.formatter_validation import (
    formatter_validation_summary,
    narration_ownership_metrics,
    rollup_formatter_validation_summaries,
    validate_combined_provider_clauses,
    validate_provider_clauses,
)
from knowledge_service.knowledge_query_schema import (
    KnowledgeGraphAnswer,
    KnowledgeGraphAnswerQueryEntry,
    KnowledgeHumanQueryResponse,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
)


@dataclass(frozen=True)
class EndToEndFormatterAnswer:
    graph_id: str
    sources: tuple[str, ...]
    query_entries: tuple[KnowledgeGraphAnswerQueryEntry, ...]
    text: str
    complete: bool
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    plan: CanonicalNarrationPlan


@dataclass(frozen=True)
class EndToEndFormatterAnswerResult:
    answer_language: str
    answers: tuple[EndToEndFormatterAnswer, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    metrics: Mapping[str, Any]


class EndToEndFormatterSegmentPlanner:
    def __init__(self, *, policy: FormatterPolicy | None = None) -> None:
        self.policy = policy or FormatterPolicy()
        self.serialization_count = 0

    def segments(self, plan: CanonicalNarrationPlan) -> tuple[EndToEndFormatterSegment, ...]:
        clauses = tuple(plan.clauses)
        if not clauses:
            raise EndToEndFormatterValidationError(("canonical narration plan contains no clauses",))
        segments: list[EndToEndFormatterSegment] = []
        current: list[CanonicalNarrationClause] = []
        for clause in clauses:
            clause_size = len(self._serialize_clause_payload(clause))
            if clause_size > self.policy.max_serialized_clause_chars:
                raise EndToEndFormatterClauseTooLarge(
                    graph_id=plan.graph_id,
                    clause_ref=clause.clause_ref,
                    serialized_character_count=clause_size,
                    configured_character_budget=self.policy.max_serialized_clause_chars,
                )
            candidate = (*current, clause)
            if current and (
                len(candidate) > self.policy.max_clauses_per_segment
                or len(self._serialize_input(plan, candidate, len(segments))) > self.policy.max_serialized_segment_chars
            ):
                segments.append(self._segment(plan, tuple(current), len(segments)))
                current = [clause]
            else:
                current = list(candidate)
        if current:
            segments.append(self._segment(plan, tuple(current), len(segments)))
        return tuple(segments)

    def _segment(self, plan: CanonicalNarrationPlan, clauses: tuple[CanonicalNarrationClause, ...], index: int) -> EndToEndFormatterSegment:
        formatter_input = self._input(plan, clauses, index)
        raw = self._serialize_payload(formatter_input)
        if len(raw) > self.policy.max_serialized_segment_chars and len(clauses) == 1:
            raise EndToEndFormatterClauseTooLarge(
                graph_id=plan.graph_id,
                clause_ref=clauses[0].clause_ref,
                serialized_character_count=len(raw),
                configured_character_budget=self.policy.max_serialized_segment_chars,
            )
        return EndToEndFormatterSegment(
            segment_ref=str(formatter_input["segmentRef"]),
            graph_id=plan.graph_id,
            response_language=plan.response_language,
            clause_refs=tuple(formatter_input["clauseOrder"]),
            clauses=clauses,
            formatter_input=formatter_input,
            prompt_hash_seed=_sha256(raw),
        )

    def _input(self, plan: CanonicalNarrationPlan, clauses: tuple[CanonicalNarrationClause, ...], index: int) -> dict[str, Any]:
        return {
            "graphId": plan.graph_id,
            "responseLanguage": plan.response_language,
            "segmentRef": f"{plan.graph_id}:segment:{index + 1}",
            "segmentIndex": index,
            "segmentCount": None,
            "clauseOrder": [clause.clause_ref for clause in clauses],
            "clauses": [self._clause_payload(clause, plan.response_language) for clause in clauses],
        }

    def _serialize_input(self, plan: CanonicalNarrationPlan, clauses: tuple[CanonicalNarrationClause, ...], index: int) -> str:
        return self._serialize_payload(self._input(plan, clauses, index))

    def _serialize_clause_payload(self, clause: CanonicalNarrationClause) -> str:
        return self._serialize_payload(self._clause_payload(clause, ""))

    def _serialize_payload(self, payload: Mapping[str, Any]) -> str:
        self.serialization_count += 1
        return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)

    def _clause_payload(self, clause: CanonicalNarrationClause, response_language: str) -> dict[str, Any]:
        return {
            "clauseRef": clause.clause_ref,
            "clauseKind": clause.clause_kind.value,
            "semanticOperation": clause.semantic_operation.value,
            "allowedCanonicalRefs": list(clause.allowed_canonical_refs),
            "canonicalDisplayValues": dict(clause.display_values),
            "responseLanguage": response_language,
        }


class EndToEndFormatterAnswerService:
    def __init__(
        self,
        provider: Any,
        *,
        segment_planner: EndToEndFormatterSegmentPlanner | None = None,
        request_deadline_seconds: float = 60.0,
        provider_name: str | None = None,
        provider_model: str | None = None,
        audit_max_records: int = 100,
        language_validator: HumanAnswerTextValidator | None = None,
        planner: CanonicalNarrationPlanner | None = None,
    ) -> None:
        self.provider = provider
        self.segment_planner = segment_planner or EndToEndFormatterSegmentPlanner()
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or 60.0))
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.audit_records: deque[dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.pipeline_records: list[dict[str, Any]] = []
        self.current_stage: str | None = None
        self.planner = planner or CanonicalNarrationPlanner(policy=self.segment_planner.policy)
        self.language_validator = language_validator or HumanAnswerTextValidator()

    def answer(
        self,
        request: KnowledgeQueryRequest | None,
        execution: Any,
        *,
        plan: Any,
        deadline_at: float | None = None,
        cancel_event: Any | None = None,
    ) -> EndToEndFormatterAnswerResult:
        del request
        deadline_at = deadline_at if deadline_at is not None else time.monotonic() + self.request_deadline_seconds
        graphs = tuple(getattr(execution, "selected_graphs", ()) or ())
        if not graphs:
            return EndToEndFormatterAnswerResult(
                answer_language=plan.response_language,
                answers=(),
                diagnostics=(),
                metrics=self._metrics(
                    (),
                    0.0,
                    answer_count=0,
                    provider_call_count=0,
                    repair_call_count=0,
                    formatter_duration_ms=0.0,
                    segment_count=0,
                ),
            )

        answers: list[EndToEndFormatterAnswer] = []
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        planned: list[CanonicalNarrationPlan] = []
        validation_summaries = []
        planning_ms = 0.0
        total_provider_calls = 0
        total_repair_calls = 0
        total_formatter_ms = 0.0
        total_segment_count = 0
        failed_graph_ids: list[str] = []

        for graph in graphs:
            self._check_cancelled(cancel_event)
            if time.monotonic() >= deadline_at:
                raise EndToEndFormatterDeadlineExceeded("canonical formatter deadline exceeded")
            self.current_stage = "CANONICAL_NARRATION_PLANNING"
            narration_plan = self.planner.plan(graph, response_language=plan.response_language)
            planned.append(narration_plan)
            planning_ms += narration_plan.planning_duration_ms
            invalid_plan_diagnostics = tuple(item for item in narration_plan.diagnostics if item.severity == "ERROR")
            if invalid_plan_diagnostics:
                self._record_formatter_audit(narration_plan, 0, 0, "", "FAILED_PLAN_VALIDATION", 0.0)
                failed_graph_ids.append(narration_plan.graph_id)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_PLAN_INVALID",
                        message="The canonical narration plan failed validation.",
                        severity="WARN",
                        metadata={"graphId": narration_plan.graph_id, "diagnosticCodes": tuple(item.code for item in invalid_plan_diagnostics)},
                    )
                )
                continue
            if not narration_plan.query_entries:
                self._record_formatter_audit(narration_plan, 0, 0, "", "FAILED_NO_QUERY_ENTRY", 0.0)
                failed_graph_ids.append(narration_plan.graph_id)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_QUERY_ENTRY_MISSING",
                        message="The selected canonical graph did not contain a query-entry unit, so no human answer was formatted.",
                        severity="WARN",
                        metadata={"graphId": narration_plan.graph_id},
                    )
                )
                continue
            self.current_stage = "CANONICAL_TEXT_RENDERING"
            try:
                text, provider_calls, repair_calls, formatter_ms, prompt_hash, validation_result, segment_count, validation_summary = self._render_text(
                    narration_plan,
                    deadline_at=deadline_at,
                    cancel_event=cancel_event,
                )
            except EndToEndFormatterDeadlineExceeded:
                raise
            except EndToEndFormatterError as exc:
                provider_calls = int(getattr(exc, "provider_calls", 0) or 0)
                repair_calls = int(getattr(exc, "repair_calls", 0) or 0)
                formatter_ms = float(getattr(exc, "formatter_duration_ms", 0.0) or 0.0)
                prompt_hash = str(getattr(exc, "prompt_hash", "") or "")
                validation_result = str(getattr(exc, "validation_result", "FAILED") or "FAILED")
                segment_count = int(getattr(exc, "segment_count", 0) or 0)
                validation_summary = getattr(exc, "validation_summary", formatter_validation_summary(narration_plan, {}))
                self._record_formatter_audit(narration_plan, provider_calls, repair_calls, prompt_hash, validation_result, formatter_ms)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_FAILED",
                        message="The canonical formatter failed validation for a selected end-to-end graph.",
                        severity="WARN",
                        metadata={"graphId": narration_plan.graph_id},
                    )
                )
                failed_graph_ids.append(narration_plan.graph_id)
                total_provider_calls += provider_calls
                total_repair_calls += repair_calls
                total_formatter_ms += formatter_ms
                total_segment_count += segment_count
                validation_summaries.append(validation_summary)
                continue
            self._record_formatter_audit(narration_plan, provider_calls, repair_calls, prompt_hash, validation_result, formatter_ms)
            total_provider_calls += provider_calls
            total_repair_calls += repair_calls
            total_formatter_ms += formatter_ms
            total_segment_count += segment_count
            validation_summaries.append(validation_summary)
            answers.append(
                EndToEndFormatterAnswer(
                    graph_id=narration_plan.graph_id,
                    sources=narration_plan.sources,
                    query_entries=narration_plan.query_entries,
                    text=text,
                    complete=narration_plan.complete,
                    diagnostics=narration_plan.diagnostics,
                    plan=narration_plan,
                )
            )

        metrics = self._metrics(
            planned,
            planning_ms,
            answer_count=0 if failed_graph_ids else len(answers),
            provider_call_count=total_provider_calls,
            repair_call_count=total_repair_calls,
            formatter_duration_ms=total_formatter_ms,
            segment_count=total_segment_count,
            validation_summaries=validation_summaries,
        )
        self.pipeline_records.append(metrics)
        if failed_graph_ids:
            self.current_stage = "CANONICAL_TEXT_RENDERING"
            failure = EndToEndFormatterAllGraphsFailed("one or more selected canonical graph answers failed")
            failure.failed_graph_ids = tuple(failed_graph_ids)
            failure.diagnostics = tuple(diagnostics)
            raise failure
        if graphs and len(answers) != len(graphs):
            self.current_stage = "CANONICAL_TEXT_RENDERING"
            failure = EndToEndFormatterAllGraphsFailed("selectedGraphCount did not equal humanAnswerCount")
            failure.failed_graph_ids = tuple(graph.stable_graph_id for graph in graphs)
            failure.diagnostics = tuple(diagnostics)
            raise failure
        self.current_stage = "SUCCESS"
        return EndToEndFormatterAnswerResult(answer_language=plan.response_language, answers=tuple(answers), diagnostics=tuple(diagnostics), metrics=metrics)

    def to_response(self, result: EndToEndFormatterAnswerResult) -> KnowledgeHumanQueryResponse:
        return KnowledgeHumanQueryResponse(
            answerLanguage=result.answer_language,
            answers=[
                KnowledgeGraphAnswer(
                    graphId=answer.graph_id,
                    sources=list(answer.sources),
                    queryEntries=list(answer.query_entries),
                    text=answer.text,
                    complete=answer.complete,
                    diagnostics=list(answer.diagnostics),
                )
                for answer in result.answers
            ],
            diagnostics=list(result.diagnostics),
        )

    def _render_text(
        self,
        plan: CanonicalNarrationPlan,
        *,
        deadline_at: float,
        cancel_event: Any | None,
    ) -> tuple[str, int, int, float, str, str, int, Any]:
        segments = self.segment_planner.segments(plan)
        validation_errors: tuple[str, ...] = ()
        provider_call_count = 0
        repair_call_count = 0
        formatter_duration_ms = 0.0
        prompt_hashes: list[str] = []
        last_errors: tuple[str, ...] = ()
        for attempt_index in range(1 + self.segment_planner.policy.max_repair_attempts):
            if attempt_index > 0:
                repair_call_count += len(segments)
            segment_clauses: dict[str, list[ValidatedFormatterClause]] = defaultdict(list)
            prompt_hashes.clear()
            formatter_duration_ms = 0.0
            structure_errors: list[str] = []
            for segment in segments:
                result = self._provider_generate(
                    segment.formatter_input,
                    deadline_at=deadline_at,
                    cancel_event=cancel_event,
                    validation_errors=validation_errors,
                )
                provider_call_count += 1
                formatter_duration_ms += result.duration_ms
                prompt_hashes.append(result.prompt_hash)
                try:
                    validated = validate_provider_clauses(result.raw_text, plan, segment)
                except EndToEndFormatterValidationError as exc:
                    structure_errors.extend(exc.errors)
                    continue
                for clause_ref, clause in validated.items():
                    segment_clauses[clause_ref].append(clause)
            if structure_errors:
                last_errors = tuple(structure_errors)
                if attempt_index < self.segment_planner.policy.max_repair_attempts:
                    validation_errors = last_errors
                    continue
                break
            try:
                validation_summary = validate_combined_provider_clauses(plan, segment_clauses)
            except EndToEndFormatterValidationError as exc:
                last_errors = exc.errors
                if attempt_index < self.segment_planner.policy.max_repair_attempts:
                    validation_errors = last_errors
                    continue
                break
            ordered = [segment_clauses[clause.clause_ref][0] for clause in plan.clauses]
            text = "\n".join(clause.text.strip() for clause in ordered if clause.text.strip())
            language_result = self.language_validator.validate(text, plan.response_language)
            if language_result.valid:
                return (
                    text,
                    provider_call_count,
                    repair_call_count,
                    round(formatter_duration_ms, 3),
                    _sha256("|".join(prompt_hashes)),
                    "VALID",
                    len(segments),
                    validation_summary,
                )
            last_errors = tuple(language_result.errors)
            if attempt_index < self.segment_planner.policy.max_repair_attempts:
                validation_errors = last_errors
                continue
        error = EndToEndFormatterValidationError(last_errors or ("canonical formatter validation failed",))
        error.provider_calls = provider_call_count
        error.repair_calls = repair_call_count
        error.formatter_duration_ms = round(formatter_duration_ms, 3)
        error.prompt_hash = _sha256("|".join(prompt_hashes))
        error.validation_result = "FAILED"
        error.segment_count = len(segments)
        error.validation_summary = formatter_validation_summary(plan, {})
        raise error

    def _provider_generate(
        self,
        formatter_input: Mapping[str, Any],
        *,
        deadline_at: float,
        cancel_event: Any | None,
        validation_errors: Sequence[str],
    ) -> EndToEndFormatterProviderResult:
        if not hasattr(self.provider, "generate"):
            raise EndToEndFormatterProviderError("canonical formatter provider does not implement generate")
        result = self.provider.generate(
            formatter_input,
            deadline_at=deadline_at,
            cancel_event=cancel_event,
            validation_errors=tuple(validation_errors or ()),
        )
        if isinstance(result, EndToEndFormatterProviderResult):
            return result
        raw_text = str(getattr(result, "raw_text", "") or "")
        prompt_hash = str(getattr(result, "prompt_hash", "") or "") or _sha256(json.dumps(formatter_input, sort_keys=True, default=str))
        return EndToEndFormatterProviderResult(
            raw_text=raw_text,
            prompt_char_length=int(getattr(result, "prompt_char_length", 0) or 0),
            prompt_hash=prompt_hash,
            duration_ms=float(getattr(result, "duration_ms", 0.0) or 0.0),
            provider_name=getattr(result, "provider_name", self.provider_name),
            provider_model=getattr(result, "provider_model", self.provider_model),
        )

    def _record_formatter_audit(
        self,
        plan: CanonicalNarrationPlan,
        provider_call_count: int,
        repair_call_count: int,
        prompt_hash: str,
        validation_result: str,
        duration_ms: float,
    ) -> None:
        self.audit_records.append(
            {
                "graphId": plan.graph_id,
                "responseLanguage": plan.response_language,
                "stageCount": len(plan.clauses),
                "clauseCount": len(plan.clauses),
                "factCount": len(plan.canonical_fact_refs),
                "assertionCount": sum(len(clause.required_assertions) for clause in plan.clauses),
                "formatterProviderCallCount": provider_call_count,
                "formatterRepairCallCount": repair_call_count,
                "promptHash": prompt_hash,
                "validationResult": validation_result,
                "durationMs": round(duration_ms, 3),
                "provider": self.provider_name,
                "model": self.provider_model,
            }
        )

    def _metrics(
        self,
        plans: Sequence[CanonicalNarrationPlan],
        planning_ms: float,
        *,
        answer_count: int,
        provider_call_count: int,
        repair_call_count: int,
        formatter_duration_ms: float,
        segment_count: int,
        validation_summaries: Sequence[Any] = (),
    ) -> dict[str, Any]:
        clause_count = sum(len(plan.clauses) for plan in plans)
        ownership = narration_ownership_metrics(plans)
        coverage = rollup_formatter_validation_summaries(validation_summaries)
        missing_stage_refs = int(coverage.get("missingStageRefs") or 0)
        duplicate_stage_refs = int(ownership.get("duplicateStageRefs") or 0) + int(coverage.get("duplicateStageRefs") or 0)
        unowned_fact_refs = int(ownership.get("unownedFactRefs") or 0) + int(coverage.get("unownedFactRefs") or 0)
        duplicate_fact_refs = int(ownership.get("duplicateFactRefs") or 0) + int(coverage.get("duplicateFactRefs") or 0)
        public_step_count = int(coverage.get("publicStepCount") or 0) if answer_count else 0
        validated_step_count = int(coverage.get("validatedFormatterStepCount") or 0) if answer_count else 0
        contract_matched = (
            int(answer_count) == len(plans)
            and missing_stage_refs == 0
            and duplicate_stage_refs == 0
            and unowned_fact_refs == 0
            and duplicate_fact_refs == 0
            and validated_step_count == clause_count
        )
        prompt_seed = json.dumps([[plan.graph_id, [clause.clause_ref for clause in plan.clauses]] for plan in plans], sort_keys=True)
        return {
            "selectedGraphCount": len(plans),
            "presentationStageCount": clause_count,
            "presentationClauseCount": clause_count,
            "answerCount": int(answer_count),
            "presentationPlanningDurationMs": round(planning_ms, 3),
            "formatterPlanningDurationMs": round(planning_ms, 3),
            "formatterDurationMs": round(formatter_duration_ms, 3),
            "totalFormatterDurationMs": round(planning_ms + formatter_duration_ms, 3),
            "textRenderingDurationMs": round(formatter_duration_ms, 3),
            "stitchingDurationMs": 0.0,
            "formatterProviderCallCount": int(provider_call_count),
            "formatterRepairCallCount": int(repair_call_count),
            "formatterOutputSplitCallCount": 0,
            "formatterSegmentCount": int(segment_count),
            "formatterSerializationCount": int(self.segment_planner.serialization_count),
            "stageCountContractMatched": bool(contract_matched),
            "stageCountContractExpected": clause_count,
            "expectedPublicStageCount": clause_count,
            "expectedPresentationStageCount": clause_count,
            "validatedFormatterStepCount": validated_step_count,
            "validatedFormatterClauseCount": validated_step_count,
            "stitchedPublicStepCount": public_step_count,
            "publicStepCount": public_step_count,
            "publicClauseCount": public_step_count,
            "provenTransitionCount": sum(
                1
                for plan in plans
                for clause in plan.clauses
                if clause.semantic_operation is NarrationSemanticOperation.CONTINUES_WITH_PROVEN_TARGET
            ),
            "openAmbiguousBoundaryCount": sum(
                1
                for plan in plans
                for clause in plan.clauses
                if clause.semantic_operation is NarrationSemanticOperation.HAS_AMBIGUOUS_CONTINUATION
            ),
            "openUnresolvedBoundaryCount": sum(
                1
                for plan in plans
                for clause in plan.clauses
                if clause.semantic_operation is NarrationSemanticOperation.HAS_UNRESOLVED_CONTINUATION
            ),
            "branchCount": sum(1 for plan in plans for clause in plan.clauses if clause.clause_kind is NarrationClauseKind.BRANCH),
            "structuralStageCount": sum(
                1
                for plan in plans
                for clause in plan.clauses
                if clause.clause_kind
                in {
                    NarrationClauseKind.BRANCH,
                    NarrationClauseKind.CONVERGENCE,
                    NarrationClauseKind.CYCLE_REFERENCE,
                    NarrationClauseKind.SHARED_UNIT_REFERENCE,
                }
            ),
            "presentationStageRefs": [clause.clause_ref for plan in plans for clause in plan.clauses],
            "presentationStages": [
                {
                    "stageRef": clause.clause_ref,
                    "kind": clause.clause_kind.value,
                    "semanticOperation": clause.semantic_operation.value,
                    "ownedFactRefs": list(clause.canonical_fact_refs),
                    "contextFactRefs": sorted({*clause.subject_refs, *clause.object_refs, *clause.qualifier_refs} - set(clause.canonical_fact_refs)),
                }
                for plan in plans
                for clause in plan.clauses
            ],
            "deduplicatedFactCount": len({fact for plan in plans for fact in plan.canonical_fact_refs}),
            "missingStageRefs": missing_stage_refs,
            "duplicateStageRefs": duplicate_stage_refs,
            "unownedFactRefs": unowned_fact_refs,
            "duplicateFactRefs": duplicate_fact_refs,
            "unknownStageRefs": int(coverage.get("unknownStageRefs") or 0),
            "omittedOwnedFactRefs": int(coverage.get("omittedOwnedFactRefs") or 0),
            "unknownOwnedFactRefs": int(ownership.get("unknownOwnedFactRefs") or 0),
            "unknownContextFactRefs": int(ownership.get("unknownContextFactRefs") or 0),
            "promptHash": _sha256(prompt_seed),
        }

    def _check_cancelled(self, cancel_event: Any | None) -> None:
        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)():
            raise EndToEndFormatterDeadlineExceeded("canonical formatter cancelled")


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()
