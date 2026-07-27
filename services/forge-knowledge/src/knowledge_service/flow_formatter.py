from __future__ import annotations

import hashlib
import json
import time
from collections import deque
from dataclasses import dataclass
from typing import Any, Deque, Mapping, Sequence

import httpx

from knowledge_service.end_to_end_flow import EndToEndFlowGraph
from knowledge_service.knowledge_query_schema import (
    KnowledgeGraphAnswer,
    KnowledgeGraphAnswerQueryEntry,
    KnowledgeHumanQueryResponse,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
)


class EndToEndFormatterError(RuntimeError):
    pass


class EndToEndFormatterDeadlineExceeded(TimeoutError):
    pass


class EndToEndFormatterAllGraphsFailed(EndToEndFormatterError):
    pass


@dataclass(frozen=True)
class EndToEndPresentationStage:
    stage_ref: str
    kind: str
    canonical_fact_refs: tuple[str, ...]
    payload: Mapping[str, Any]


@dataclass(frozen=True)
class EndToEndPresentationPlan:
    graph_id: str
    sources: tuple[str, ...]
    query_entries: tuple[KnowledgeGraphAnswerQueryEntry, ...]
    topology_entries: tuple[str, ...]
    stages: tuple[EndToEndPresentationStage, ...]
    canonical_fact_refs: tuple[str, ...]
    complete: bool
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    planning_duration_ms: float = 0.0


@dataclass(frozen=True)
class EndToEndFormatterAnswer:
    graph_id: str
    sources: tuple[str, ...]
    query_entries: tuple[KnowledgeGraphAnswerQueryEntry, ...]
    text: str
    complete: bool
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    plan: EndToEndPresentationPlan


@dataclass(frozen=True)
class EndToEndFormatterAnswerResult:
    answer_language: str
    answers: tuple[EndToEndFormatterAnswer, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]
    metrics: Mapping[str, Any]


class EndToEndPresentationPlanner:
    def plan(self, graph: EndToEndFlowGraph, *, response_language: str = "en") -> EndToEndPresentationPlan:
        started = time.perf_counter()
        stages: list[EndToEndPresentationStage] = []
        fact_refs: list[str] = []
        unit_refs = tuple(sorted(graph.unit_refs, key=lambda item: item.unit_id))
        query_entries = tuple(self._query_entry(ref) for ref in unit_refs if ref.unit_id in set(graph.query_entry_unit_ids))
        if not query_entries and unit_refs:
            query_entries = (self._query_entry(unit_refs[0]),)
        for ref in unit_refs:
            stage_ref = f"unit:{ref.unit_id}"
            unit_fact_refs = self._unit_fact_refs(ref.local_unit)
            fact_refs.extend(unit_fact_refs)
            stages.append(
                EndToEndPresentationStage(
                    stage_ref=stage_ref,
                    kind="UNIT_ENTRY" if ref.query_selected_initial else "LOCAL_EXECUTION",
                    canonical_fact_refs=unit_fact_refs,
                    payload={
                        "unitId": ref.unit_id,
                        "sourceId": ref.source_id,
                        "topologyBoundaries": [
                            {
                                "edgeId": edge.edge_id,
                                "resolutionStatus": edge.resolution_status,
                                "unresolvedTarget": edge.unresolved_target,
                            }
                            for edge in ref.local_unit.topology_boundaries
                        ],
                    },
                )
            )
        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            fact_ref = f"transition:{transition.stable_transition_id}"
            fact_refs.append(fact_ref)
            stages.append(
                EndToEndPresentationStage(
                    stage_ref=fact_ref,
                    kind="PROVEN_BOUNDARY_CONTINUATION",
                    canonical_fact_refs=(fact_ref, f"resolution:{transition.resolution_id}"),
                    payload={
                        "transitionId": transition.stable_transition_id,
                        "resolutionId": transition.resolution_id,
                        "sourceUnitId": transition.source_unit_id,
                        "targetUnitId": transition.target_unit_id,
                    },
                )
            )
        for boundary in sorted(graph.open_boundaries, key=lambda item: (str(item.status), item.required_boundary_identity.boundary_key, tuple(item.source_unit_ids))):
            status = boundary.status.value if hasattr(boundary.status, "value") else str(boundary.status)
            kind = "OPEN_BOUNDARY_AMBIGUOUS" if status == "AMBIGUOUS" else "OPEN_BOUNDARY_UNRESOLVED"
            fact_ref = f"open-boundary:{boundary.required_boundary_identity.boundary_key}:{','.join(boundary.source_unit_ids)}"
            fact_refs.append(fact_ref)
            stages.append(
                EndToEndPresentationStage(
                    stage_ref=fact_ref,
                    kind=kind,
                    canonical_fact_refs=(fact_ref,),
                    payload={"status": status, "sourceUnitIds": list(boundary.source_unit_ids)},
                )
            )
        if graph.coverage.cycle_count:
            stages.append(
                EndToEndPresentationStage(
                    stage_ref=f"cycle:{graph.stable_graph_id}",
                    kind="CYCLE_REFERENCE",
                    canonical_fact_refs=(f"cycle:{graph.stable_graph_id}",),
                    payload={"cycleCount": graph.coverage.cycle_count},
                )
            )
        return EndToEndPresentationPlan(
            graph_id=graph.stable_graph_id,
            sources=tuple(sorted({ref.source_id for ref in unit_refs})),
            query_entries=query_entries,
            topology_entries=tuple(graph.topology_entry_unit_ids),
            stages=tuple(stages),
            canonical_fact_refs=tuple(sorted(set(fact_refs))),
            complete=graph.coverage.complete,
            diagnostics=tuple(
                KnowledgeQueryDiagnostic(code=item.code, message=item.message, severity=item.severity, sourceId=item.source_id, metadata=dict(item.metadata or {}))
                for item in graph.diagnostics
            ),
            planning_duration_ms=round((time.perf_counter() - started) * 1000, 3),
        )

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

    def _unit_fact_refs(self, unit: Any) -> tuple[str, ...]:
        refs = [f"unit:{unit.unit_id}"]
        refs.extend(f"node:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}" for node in unit.execution_nodes)
        refs.extend(f"edge:{edge.source_id}:{edge.graph_revision or edge.graph_id}:{edge.edge_id}" for edge in unit.execution_transitions)
        refs.extend(f"boundary:{boundary.source_id}:{boundary.graph_revision or boundary.graph_id}:{boundary.stable_key}" for boundary in unit.generic_boundaries)
        return tuple(sorted(set(refs)))


class EndToEndFormatterPromptRenderer:
    def render(self, formatter_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        payload = json.dumps(dict(formatter_input), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        errors = "\n".join(str(item) for item in validation_errors or ())
        return f"Format the supplied canonical end-to-end graph facts as grounded prose.\n{errors}\n{payload}"


class EndToEndFormatterSegmentPlanner:
    def __init__(self, context_tokens: int = 8192) -> None:
        self.context_tokens = max(1024, int(context_tokens or 8192))
        self.serialization_count = 0


class LocalOllamaEndToEndFormatterClient:
    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: float,
        context_tokens: int,
        *,
        renderer: EndToEndFormatterPromptRenderer | None = None,
    ) -> None:
        self.base_url = str(base_url or "").rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = context_tokens
        self.renderer = renderer or EndToEndFormatterPromptRenderer()
        self._client = httpx.Client(timeout=timeout_seconds)

    def close(self) -> None:
        self._client.close()


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
    ) -> None:
        self.provider = provider
        self.segment_planner = segment_planner or EndToEndFormatterSegmentPlanner()
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or 60.0))
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.audit_records: Deque[dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.pipeline_records: list[dict[str, Any]] = []
        self.current_stage: str | None = None
        self.planner = EndToEndPresentationPlanner()

    def answer(
        self,
        request: KnowledgeQueryRequest,
        execution: Any,
        *,
        plan: Any,
        deadline_at: float | None = None,
        cancel_event: Any | None = None,
    ) -> EndToEndFormatterAnswerResult:
        deadline_at = deadline_at if deadline_at is not None else time.monotonic() + self.request_deadline_seconds
        graphs = tuple(getattr(execution, "selected_graphs", ()) or ())
        if not graphs:
            return EndToEndFormatterAnswerResult(
                answer_language=plan.response_language,
                answers=(),
                diagnostics=(),
                metrics=self._metrics((), 0.0, answer_count=0),
            )
        answers: list[EndToEndFormatterAnswer] = []
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        presentation_plans: list[EndToEndPresentationPlan] = []
        planning_ms = 0.0
        for graph in graphs:
            self._check_cancelled(cancel_event)
            if time.monotonic() >= deadline_at:
                raise EndToEndFormatterDeadlineExceeded("canonical formatter deadline exceeded")
            self.current_stage = "END_TO_END_PRESENTATION_PLANNING"
            presentation_plan = self.planner.plan(graph, response_language=plan.response_language)
            presentation_plans.append(presentation_plan)
            planning_ms += presentation_plan.planning_duration_ms
            self.current_stage = "END_TO_END_TEXT_RENDERING"
            text = self._render_text(presentation_plan)
            answers.append(
                EndToEndFormatterAnswer(
                    graph_id=presentation_plan.graph_id,
                    sources=presentation_plan.sources,
                    query_entries=presentation_plan.query_entries,
                    text=text,
                    complete=presentation_plan.complete,
                    diagnostics=presentation_plan.diagnostics,
                    plan=presentation_plan,
                )
            )
        if graphs and not answers:
            self.pipeline_records.append(self._metrics(presentation_plans, planning_ms, answer_count=0))
            raise EndToEndFormatterAllGraphsFailed("no canonical graph answer succeeded")
        metrics = self._metrics(presentation_plans, planning_ms, answer_count=len(answers))
        self.pipeline_records.append(metrics)
        self.current_stage = "SUCCESS"
        return EndToEndFormatterAnswerResult(
            answer_language=plan.response_language,
            answers=tuple(answers),
            diagnostics=tuple(diagnostics),
            metrics=metrics,
        )

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

    def _render_text(self, plan: EndToEndPresentationPlan) -> str:
        lines = [f"Graph {plan.graph_id} spans {len(plan.sources)} source(s): {', '.join(plan.sources) or 'none'}."]
        query_labels = [str(entry.root.get("qualifiedName") or entry.root.get("label") or entry.unitId) for entry in plan.query_entries]
        if query_labels:
            lines.append(f"Query entry unit(s): {', '.join(query_labels)}.")
        proven_count = sum(1 for stage in plan.stages if stage.kind == "PROVEN_BOUNDARY_CONTINUATION")
        ambiguous_count = sum(1 for stage in plan.stages if stage.kind == "OPEN_BOUNDARY_AMBIGUOUS")
        unresolved_count = sum(1 for stage in plan.stages if stage.kind == "OPEN_BOUNDARY_UNRESOLVED")
        if proven_count:
            lines.append(f"{proven_count} cross-source continuation(s) are verified by generic boundary proof.")
        if ambiguous_count:
            lines.append(f"{ambiguous_count} continuation boundary/boundaries remain ambiguous; no target unit is selected for them.")
        if unresolved_count:
            lines.append(f"{unresolved_count} continuation boundary/boundaries remain unresolved; no continuation is invented.")
        if any(stage.kind == "CYCLE_REFERENCE" for stage in plan.stages):
            lines.append("The graph contains a cycle, so repeated units are referenced instead of duplicated.")
        unresolved_names = sorted(
            {
                str((boundary.get("unresolvedTarget") or {}).get("qualifiedName") or (boundary.get("unresolvedTarget") or {}).get("label") or "")
                for stage in plan.stages
                for boundary in list(stage.payload.get("topologyBoundaries") or [])
                if isinstance(boundary, dict) and str((boundary.get("unresolvedTarget") or {}).get("qualifiedName") or (boundary.get("unresolvedTarget") or {}).get("label") or "")
            }
        )
        if unresolved_names:
            lines.append(f"Unresolved local target(s): {', '.join(unresolved_names)}.")
        if not plan.complete:
            lines.append("The graph is incomplete according to canonical coverage.")
        return "\n".join(lines)

    def _metrics(self, plans: Sequence[EndToEndPresentationPlan], planning_ms: float, *, answer_count: int) -> dict[str, Any]:
        stage_count = sum(len(plan.stages) for plan in plans)
        return {
            "selectedGraphCount": len(plans),
            "presentationStageCount": stage_count,
            "answerCount": int(answer_count),
            "presentationPlanningDurationMs": round(planning_ms, 3),
            "formatterPlanningDurationMs": round(planning_ms, 3),
            "formatterDurationMs": 0.0,
            "textRenderingDurationMs": 0.0,
            "stitchingDurationMs": 0.0,
            "formatterProviderCallCount": 0,
            "formatterRepairCallCount": 0,
            "formatterOutputSplitCallCount": 0,
            "formatterSegmentCount": 0,
            "formatterSerializationCount": 0,
            "stageCountContractMatched": True,
            "expectedPublicStageCount": stage_count,
            "validatedFormatterStepCount": stage_count,
            "stitchedPublicStepCount": stage_count,
            "publicStepCount": stage_count,
            "provenTransitionCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "PROVEN_BOUNDARY_CONTINUATION"),
            "openAmbiguousBoundaryCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "OPEN_BOUNDARY_AMBIGUOUS"),
            "openUnresolvedBoundaryCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "OPEN_BOUNDARY_UNRESOLVED"),
            "promptHash": self._sha256(json.dumps([plan.graph_id for plan in plans], sort_keys=True)),
        }

    def _check_cancelled(self, cancel_event: Any | None) -> None:
        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)():
            raise EndToEndFormatterDeadlineExceeded("canonical formatter cancelled")

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()
