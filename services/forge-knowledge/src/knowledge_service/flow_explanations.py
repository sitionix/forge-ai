from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.parse
from collections import deque
from dataclasses import dataclass
from typing import Any, Deque, Dict, List, Mapping, Sequence

import httpx

from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.config import (
    DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
)
from knowledge_service.entrypoint_kinds import tree_kind_for_entrypoint, trigger_kind_for_entrypoint
from knowledge_service.entrypoint_flow_engine import EntrypointFlow
from knowledge_service.flow_boundary_classifier import FlowBoundaryClassifier, FLOW_BOUNDARY_CLASSIFIER
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.knowledge_query_schema import (
    FlowToolEvidence,
    FlowToolTrigger,
    FlowToolTree,
    FlowToolTreeItem,
    KnowledgeFlowAnswer,
    KnowledgeHumanQueryResponse,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryToolContextResponse,
)
from knowledge_service.query_interpretation import QueryRetrievalPlan


FLOW_EXPLANATION_LIMIT_REACHED = "FLOW_EXPLANATION_LIMIT_REACHED"

_DEFAULT_MIN_CALL_TIMEOUT_SECONDS = 0.01
_DEADLINE_COMPLETION_GRACE_SECONDS = 0.005


@dataclass(frozen=True)
class FlowExplanationProviderResult:
    raw_text: str
    prompt_char_length: int


@dataclass(frozen=True)
class HumanAnswerContextPolicy:
    max_evidence_per_item: int = 3
    max_excerpt_chars: int = 260

    def compact_evidence(self, evidence: Sequence[Mapping[str, Any]]) -> tuple[list[Dict[str, Any]], bool]:
        compacted = False
        deduped: list[Dict[str, Any]] = []
        seen_keys: set[tuple[str, str, str, str]] = set()
        seen_excerpts: set[str] = set()
        for raw in evidence:
            item = dict(raw)
            key = (
                str(item.get("path") or ""),
                str(item.get("lineStart") or ""),
                str(item.get("lineEnd") or ""),
                str(item.get("excerpt") or ""),
            )
            if key in seen_keys:
                compacted = True
                continue
            seen_keys.add(key)
            excerpt = item.get("excerpt")
            if excerpt is not None:
                text = str(excerpt)
                if text in seen_excerpts:
                    item.pop("excerpt", None)
                    compacted = True
                else:
                    seen_excerpts.add(text)
                    if len(text) > self.max_excerpt_chars:
                        item["excerpt"] = f"{text[: max(0, self.max_excerpt_chars - 3)]}..."
                        compacted = True
            deduped.append(item)
        limited = deduped[: max(0, self.max_evidence_per_item)]
        if len(limited) < len(deduped):
            compacted = True
        return limited, compacted


class HumanAnswerGenerationFailed(Exception):
    pass


class HumanAnswerDeadlineExceeded(HumanAnswerGenerationFailed):
    pass


class HumanAnswerProviderUnavailable(HumanAnswerGenerationFailed):
    pass


class HumanAnswerRepairExhausted(HumanAnswerGenerationFailed):
    pass


class HumanAnswerContextBudgetExceeded(HumanAnswerGenerationFailed):
    pass


class HumanAnswerContractViolation(HumanAnswerGenerationFailed):
    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = [str(error) for error in errors if str(error).strip()]
        super().__init__("; ".join(self.errors) or "human answer violated output contract")


class HumanAnswerMalformedResponse(HumanAnswerContractViolation):
    pass


class HumanAnswerLanguagePolicyViolation(HumanAnswerContractViolation):
    pass


class HumanAnswerPromptRenderer:
    def render(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious response failed validation. Correct these exact contract violations using only the supplied facts:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += "\nReturn a replacement JSON object only. Keep the text natural and grounded in the supplied facts.\n"
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, indent=2, sort_keys=True)
        return (
            "Answer the user's code-flow question as a concise technical walkthrough for exactly one supplied flow.\n"
            "Return strict JSON only with exactly this shape: {\"text\":\"human-readable answer\"}.\n"
            "Use only the requested responseLanguage for prose. Exact code symbols, HTTP routes, and quoted literals may stay as-is.\n"
            "Directly answer the question using only the supplied verified flow facts.\n"
            "The tree kind fields are internal classifier labels for grounding only. Never copy labels such as UNRESOLVED_CALL, EXTERNAL_CALL, METHOD, HTTP_ENDPOINT, KAFKA_LISTENER, or ENTRYPOINT into the answer.\n"
            "Start with the trigger and entrypoint when available, including the HTTP method and route only when they are supplied.\n"
            "Natural output may be one concise paragraph, multiple paragraphs, numbered steps, a branch-oriented explanation, or a single-step explanation when the grounded flow has one step.\n"
            "Mention exact class or method symbols where they help identify the code.\n"
            "Explain what data arrives, what the code does, what it calls next, and grounded validation, persistence, or side effects when supplied.\n"
            "When validation facts include thresholds, null or empty checks, exception classes, or error messages, include the exact grounded detail.\n"
            "Explain branches as branches; do not fabricate a sequence between sibling branches.\n"
            "End with the observable result: returned response or status, persisted data, emitted event, or external side effect when supplied.\n"
            "If the supplied facts do not include a return value, status, persistence, event, or side effect, state that the verified facts do not provide that detail.\n"
            "Keep the answer as escaped plain text inside the JSON string.\n"
            "Do not collapse the flow into a generic summary or mechanically repeat every graph field.\n"
            "Do not omit available method names, class names, trigger details, validation rules, persistence details, side effects, or final results.\n"
            "Do not invent validation, side effects, transports, routes, statuses, or ordering unsupported by the supplied facts.\n"
            "Do not infer default framework behavior or use speculative language such as likely, probably, maybe, assuming, or presumably.\n"
            "Do not mention retrieval mechanics, refs, internal graph ids, or internal scores.\n"
            f"{validation_block}"
            "BEGIN_VERIFIED_FLOW_FACTS_JSON\n"
            f"{context_json}\n"
            "END_VERIFIED_FLOW_FACTS_JSON\n"
        )


class CompactFlowProjector:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None, context_policy: HumanAnswerContextPolicy | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER
        self.context_policy = context_policy or HumanAnswerContextPolicy()
        self._context_compacted = False
        self._last_context_diagnostics: List[KnowledgeQueryDiagnostic] = []

    def to_tool_response(self, request: KnowledgeQueryRequest, execution: Any) -> KnowledgeQueryToolContextResponse:
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            trees=[self._tree(flow) for flow in tuple(execution.flows or ())],
            diagnostics=self._diagnostics(execution),
        )

    def human_llm_input(self, request: KnowledgeQueryRequest, flow: EntrypointFlow, plan: QueryRetrievalPlan | None = None) -> Dict[str, Any]:
        if plan is None:
            plan = fallback_human_answer_plan(request)
        self._context_compacted = False
        tree = self._tree(flow)
        human_tree = self._human_tree_item(tree.entrypoint)
        self._last_context_diagnostics = []
        if self._context_compacted:
            self._last_context_diagnostics.append(KnowledgeQueryDiagnostic(
                code="HUMAN_ANSWER_CONTEXT_COMPACTED",
                message="Human answer evidence context was compacted before prompt rendering.",
                severity="INFO",
                sourceId=flow.key.source_id,
                metadata={
                    "maxEvidencePerItem": self.context_policy.max_evidence_per_item,
                    "maxExcerptChars": self.context_policy.max_excerpt_chars,
                },
            ))
        return {
            "originalQuestion": request.queryText,
            "detectedLanguage": plan.detected_language,
            "responseLanguage": plan.response_language,
            "intent": plan.effective_intent,
            "source": tree.source,
            "entrypoint": tree.entrypoint.symbol,
            "tree": human_tree,
        }

    def flow_answer_identity(self, flow: EntrypointFlow) -> tuple[str, str]:
        return str(flow.key.source_id or ""), self._symbol(flow.entrypoint)

    def context_diagnostics(self) -> list[KnowledgeQueryDiagnostic]:
        return list(self._last_context_diagnostics)

    def _human_tree_item(self, item: FlowToolTreeItem) -> Dict[str, Any]:
        data = item.dict(exclude_none=True)
        children = [
            self._human_tree_item(child)
            for child in item.children
        ]
        data["children"] = children
        if data.get("evidence"):
            compacted, was_compacted = self.context_policy.compact_evidence(
                [evidence for evidence in data["evidence"] if isinstance(evidence, dict)]
            )
            data["evidence"] = compacted
            self._context_compacted = self._context_compacted or was_compacted
        return data

    def _tree(self, flow: EntrypointFlow) -> FlowToolTree:
        node_by_id = {node.node_id: node for node in flow.nodes}
        evidence_by_node: Dict[str, List[FlowGraphEvidence]] = {}
        evidence_by_edge: Dict[str, List[FlowGraphEvidence]] = {}
        for item in flow.evidence:
            if item.edge_id:
                evidence_by_edge.setdefault(item.edge_id, []).append(item)
            elif item.node_id:
                evidence_by_node.setdefault(item.node_id, []).append(item)
        outgoing: Dict[str, List[FlowGraphEdge]] = {}
        for edge in sorted(flow.transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            outgoing.setdefault(edge.from_node_id, []).append(edge)
        boundaries: Dict[str, List[FlowGraphEdge]] = {}
        for edge in sorted(flow.boundary_transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            boundaries.setdefault(edge.from_node_id, []).append(edge)

        root = self._node_item(flow.entrypoint, evidence_by_node.get(flow.entrypoint.node_id, []))
        rendered = {flow.entrypoint.node_id}
        stack: List[Dict[str, Any]] = [
            {
                "node": flow.entrypoint,
                "item": root,
                "entries": self._sorted_child_edges(flow.entrypoint.node_id, outgoing, boundaries, evidence_by_edge),
                "index": 0,
                "ancestry": {flow.entrypoint.node_id},
            }
        ]
        while stack:
            frame = stack[-1]
            if frame["index"] >= len(frame["entries"]):
                stack.pop()
                continue
            entry = frame["entries"][frame["index"]]
            frame["index"] += 1
            if entry in boundaries.get(frame["node"].node_id, []):
                frame["item"].children.append(self._boundary_item(entry, evidence_by_edge.get(entry.edge_id, [])))
                continue
            target = node_by_id.get(entry.to_node_id or "")
            if target is None:
                frame["item"].children.append(self._boundary_item(replace_edge_boundary(entry), evidence_by_edge.get(entry.edge_id, [])))
                continue
            child_evidence = [*evidence_by_node.get(target.node_id, []), *evidence_by_edge.get(entry.edge_id, [])]
            if target.node_id in frame["ancestry"]:
                frame["item"].children.append(self._node_item(target, child_evidence, cycle=True))
                continue
            if target.node_id in rendered:
                frame["item"].children.append(self._node_item(target, child_evidence, shared=True))
                continue
            child = self._node_item(target, child_evidence)
            frame["item"].children.append(child)
            rendered.add(target.node_id)
            stack.append(
                {
                    "node": target,
                    "item": child,
                    "entries": self._sorted_child_edges(target.node_id, outgoing, boundaries, evidence_by_edge),
                    "index": 0,
                    "ancestry": {*frame["ancestry"], target.node_id},
                }
            )
        return FlowToolTree(source=str(flow.key.source_id or ""), entrypoint=root)

    def _sorted_child_edges(
        self,
        node_id: str,
        outgoing: Mapping[str, Sequence[FlowGraphEdge]],
        boundaries: Mapping[str, Sequence[FlowGraphEdge]],
        evidence_by_edge: Mapping[str, Sequence[FlowGraphEvidence]],
    ) -> List[FlowGraphEdge]:
        return sorted(
            [*outgoing.get(node_id, ()), *boundaries.get(node_id, ())],
            key=lambda item: self._edge_sort_key(item, evidence_by_edge),
        )

    def _node_item(
        self,
        node: FlowGraphNode,
        evidence: Sequence[FlowGraphEvidence],
        *,
        cycle: bool = False,
        shared: bool = False,
    ) -> FlowToolTreeItem:
        return FlowToolTreeItem(
            symbol=self._symbol(node),
            kind=self._node_kind(node),
            trigger=self._trigger(node),
            path=node.relative_path,
            lineStart=node.line_start,
            lineEnd=node.line_end,
            description=node.summary,
            evidence=[self._evidence(item) for item in evidence],
            children=[],
            cycle=True if cycle else None,
            shared=True if shared else None,
        )

    def _boundary_item(self, edge: FlowGraphEdge, evidence: Sequence[FlowGraphEvidence]) -> FlowToolTreeItem:
        projection = self.boundary_classifier.project(edge)
        kind = "EXTERNAL_CALL" if projection.kind.value == "EXTERNAL" else "UNRESOLVED_CALL"
        symbol = self._boundary_symbol(edge, projection.target) or (
            "External call" if kind == "EXTERNAL_CALL" else "Unresolved call"
        )
        description = "Calls an external client boundary." if kind == "EXTERNAL_CALL" else None
        if edge.boundary_reason == "CURRENT_TARGET_NODE_MISSING":
            symbol = self._boundary_symbol(edge, projection.target) or "Target missing from current graph"
            description = "Target is missing from the current graph."
        return FlowToolTreeItem(
            symbol=symbol,
            kind=kind,
            path=None,
            lineStart=None,
            lineEnd=None,
            description=description,
            evidence=[self._evidence(item) for item in evidence],
            children=[],
        )

    def _boundary_symbol(self, edge: FlowGraphEdge, projected_target: str | None) -> str | None:
        target = edge.unresolved_target or {}
        if not isinstance(target, dict):
            return self._compact_symbol(projected_target)
        for key in ("qualifiedName", "target", "displayName", "label", "symbol"):
            value = self._clean(target.get(key) if isinstance(target.get(key), str) else None)
            if value:
                return self._compact_symbol(value)
        name = self._clean(target.get("name") if isinstance(target.get("name"), str) else None)
        for key in ("interfaceType", "receiverTypeHint", "targetTypeText"):
            owner = self._clean(target.get(key) if isinstance(target.get(key), str) else None)
            if owner and name and owner != name and self._looks_like_symbol(owner):
                return f"{self._compact_symbol(owner)}.{name}"
        return self._compact_symbol(projected_target or name)

    def _compact_symbol(self, value: str | None) -> str | None:
        normalized = self._clean(value)
        if not normalized:
            return None
        parts = [part for part in normalized.split(".") if part]
        if len(parts) >= 2:
            return ".".join(parts[-2:])
        return normalized

    def _looks_like_symbol(self, value: str) -> bool:
        return re.match(r"^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$", value) is not None

    def _evidence(self, item: FlowGraphEvidence) -> FlowToolEvidence:
        return FlowToolEvidence(
            path=item.relative_path,
            lineStart=item.line_start,
            lineEnd=item.line_end,
            excerpt=item.text,
        )

    def _symbol(self, node: FlowGraphNode) -> str:
        qualified = str(node.qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if node.node_kind == "CALLABLE" and len(parts) >= 2:
                return ".".join(parts[-2:])
            return parts[-1] if parts else qualified
        return str(node.label or node.node_id)

    def _node_kind(self, node: FlowGraphNode) -> str:
        if node.entrypoint:
            return self._entrypoint_kind(node.entrypoint_kind)
        if node.node_kind == "CALLABLE":
            return "METHOD"
        return node.node_kind

    def _entrypoint_kind(self, value: str | None) -> str:
        return tree_kind_for_entrypoint(value)

    def _trigger(self, node: FlowGraphNode) -> FlowToolTrigger | None:
        if not node.entrypoint:
            return None
        trigger_kind = trigger_kind_for_entrypoint(node.entrypoint_kind)
        if trigger_kind is None:
            return None
        return FlowToolTrigger(
            kind=trigger_kind,
            method=self._clean(node.entrypoint_http_method),
            route=self._clean(node.entrypoint_route),
            topic=self._clean(node.entrypoint_topic),
            schedule=self._clean(node.entrypoint_schedule),
            interfaceMethod=self._clean(node.entrypoint_interface_method),
        )

    def _unresolved_target_name(self, edge: FlowGraphEdge) -> str | None:
        target = edge.unresolved_target or {}
        for key in ("name", "qualifiedName", "targetTypeText", "receiverTypeHint"):
            value = target.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return None

    def _edge_sort_key(
        self,
        edge: FlowGraphEdge,
        evidence_by_edge: Mapping[str, Sequence[FlowGraphEvidence]] | None = None,
    ) -> tuple[str, int, int, str, str, str]:
        line_starts = [
            item.line_start
            for item in (evidence_by_edge or {}).get(edge.edge_id, ())
            if item.line_start is not None
        ]
        first_line = min(line_starts) if line_starts else 1_000_000_000
        return (edge.from_node_id, first_line, 0 if line_starts else 1, edge.to_node_id or "", edge.edge_id, edge.resolution_status)

    def _clean(self, value: str | None) -> str | None:
        normalized = str(value or "").strip()
        return normalized or None

    def _diagnostics(self, execution: Any) -> List[KnowledgeQueryDiagnostic]:
        response = getattr(execution, "response", None)
        diagnostics = list(getattr(response, "diagnostics", []) or [])
        for flow in tuple(getattr(execution, "flows", ()) or ()):
            diagnostics.extend(flow.diagnostics)
        return [
            self._compact_diagnostic(item)
            for item in diagnostics
            if not str(item.code).startswith("SEMANTIC_") and item.code != "ENTRYPOINT_FLOW_TIMINGS"
        ]

    def _compact_diagnostic(self, diagnostic: KnowledgeQueryDiagnostic) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code=diagnostic.code,
            message=diagnostic.message,
            severity=diagnostic.severity,
            sourceId=diagnostic.sourceId,
            metadata={},
        )


def replace_edge_boundary(edge: FlowGraphEdge) -> FlowGraphEdge:
    from dataclasses import replace

    return replace(edge, boundary_reason="CURRENT_TARGET_NODE_MISSING")


def fallback_human_answer_plan(request: KnowledgeQueryRequest) -> QueryRetrievalPlan:
    response_language = _explicit_or_default_response_language(request.answerLanguage)
    return QueryRetrievalPlan(
        original_query=request.queryText,
        normalized_query=request.queryText,
        search_queries=(),
        code_identifiers=(),
        concepts=(),
        effective_intent="FLOW_EXPLANATION",
        detected_language="und",
        response_language=response_language,
    )


def _explicit_or_default_response_language(value: str | None) -> str:
    normalized = str(value or "").strip().lower().split("-", 1)[0]
    return normalized if normalized in {"uk", "en"} else "en"


class HumanFlowAnswerService:
    def __init__(
        self,
        provider: Any,
        *,
        max_prompt_chars: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4,
        request_deadline_seconds: float = DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        projector: CompactFlowProjector | None = None,
        renderer: HumanAnswerPromptRenderer | None = None,
        text_validator: HumanAnswerTextValidator | None = None,
        provider_name: str | None = None,
        provider_model: str | None = None,
        cancel_event: Any | None = None,
        audit_max_records: int = 200,
    ) -> None:
        self.provider = provider
        self.max_prompt_chars = max(4096, int(max_prompt_chars or DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4))
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS))
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.projector = projector or CompactFlowProjector()
        self.renderer = renderer or HumanAnswerPromptRenderer()
        self.text_validator = text_validator or HumanAnswerTextValidator()
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.cancel_event = cancel_event
        self.audit_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))

    def answer(
        self,
        request: KnowledgeQueryRequest,
        execution: Any,
        *,
        plan: QueryRetrievalPlan | None = None,
        deadline_at: float | None = None,
    ) -> KnowledgeHumanQueryResponse:
        flows = tuple(execution.flows or ())
        if not flows:
            raise HumanAnswerGenerationFailed("no grounded flows")
        if deadline_at is None:
            deadline_at = time.monotonic() + self.request_deadline_seconds
        answers: List[KnowledgeFlowAnswer] = []
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        deadline_failures = 0
        effective_plan = plan or self._fallback_plan(request)
        resolved_language = effective_plan.response_language
        for flow in flows:
            source, entrypoint = self.projector.flow_answer_identity(flow)
            try:
                if self._cancelled():
                    raise HumanAnswerDeadlineExceeded()
                llm_input = self.projector.human_llm_input(request, flow, effective_plan)
                diagnostics.extend(self.projector.context_diagnostics())
                text = self._answer_one_flow(
                    llm_input,
                    deadline_at,
                    entrypoint=entrypoint,
                    requested_language=request.answerLanguage,
                    resolved_language=resolved_language,
                )
                answers.append(KnowledgeFlowAnswer(source=source, entrypoint=entrypoint, text=text))
            except HumanAnswerDeadlineExceeded:
                deadline_failures += 1
                diagnostics.append(self._flow_failure_diagnostic(source, entrypoint))
                if self._cancelled():
                    break
            except HumanAnswerGenerationFailed:
                diagnostics.append(self._flow_failure_diagnostic(source, entrypoint))

        if answers:
            return KnowledgeHumanQueryResponse(
                answerLanguage=resolved_language,
                answers=answers,
                diagnostics=diagnostics,
            )
        if deadline_failures:
            raise HumanAnswerDeadlineExceeded()
        raise HumanAnswerGenerationFailed("no grounded flow answers")

    def _fallback_plan(self, request: KnowledgeQueryRequest) -> QueryRetrievalPlan:
        return fallback_human_answer_plan(request)

    def _answer_one_flow(
        self,
        llm_input: Mapping[str, Any],
        deadline_at: float,
        *,
        entrypoint: str,
        requested_language: str | None,
        resolved_language: str,
    ) -> str:
        validation_errors: Sequence[str] | None = None
        for attempt_count in (1, 2):
            result = self._complete_with_deadline(
                llm_input,
                deadline_at,
                validation_errors=validation_errors,
                entrypoint=entrypoint,
                attempt_count=attempt_count,
                requested_language=requested_language,
                resolved_language=resolved_language,
            )
            try:
                return self._validate_text(result.raw_text, resolved_language)
            except HumanAnswerContractViolation as exc:
                self._record_validation_errors(entrypoint=entrypoint, attempt_count=attempt_count, errors=exc.errors)
                if attempt_count == 1:
                    validation_errors = exc.errors
                    continue
                raise HumanAnswerRepairExhausted("human answer repair failed validation") from exc
        raise HumanAnswerRepairExhausted("human answer repair failed validation")

    def _flow_failure_diagnostic(self, source: str, entrypoint: str) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code="HUMAN_FLOW_ANSWER_GENERATION_FAILED",
            message="The local model could not explain one selected flow.",
            severity="WARN",
            sourceId=source or None,
            metadata={"entrypoint": entrypoint},
        )

    def _complete_with_deadline(
        self,
        llm_input: Mapping[str, Any],
        deadline_at: float,
        *,
        validation_errors: Sequence[str] | None = None,
        entrypoint: str,
        attempt_count: int,
        requested_language: str | None,
        resolved_language: str,
    ) -> FlowExplanationProviderResult:
        if self._cancelled() or self._remaining_seconds(deadline_at) <= self.min_call_timeout_seconds:
            raise HumanAnswerDeadlineExceeded()
        prompt = self.renderer.render(llm_input, validation_errors)
        if len(prompt) > self.max_prompt_chars:
            raise HumanAnswerContextBudgetExceeded("human answer prompt exceeded budget")
        remaining = self._remaining_seconds(deadline_at)
        try:
            result = self.provider.complete(llm_input, validation_errors=validation_errors, timeout_seconds=remaining)
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise HumanAnswerDeadlineExceeded() from exc
        except Exception as exc:
            raise HumanAnswerProviderUnavailable(str(type(exc).__name__)) from exc
        if self._cancelled() or time.monotonic() > deadline_at + _DEADLINE_COMPLETION_GRACE_SECONDS:
            raise HumanAnswerDeadlineExceeded()
        self._record_audit(
            prompt,
            result.raw_text,
            entrypoint=entrypoint,
            attempt_count=attempt_count,
            requested_language=requested_language,
            resolved_language=resolved_language,
        )
        return result

    def _validate_text(self, raw_text: str, language: str) -> str:
        try:
            payload = json.loads(raw_text)
        except Exception as exc:
            raise HumanAnswerMalformedResponse(["Response must be strict JSON with a non-empty text string."]) from exc
        if not isinstance(payload, dict):
            raise HumanAnswerMalformedResponse(["Response must be a JSON object."])
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            raise HumanAnswerMalformedResponse(["Response JSON must contain a non-empty text string."])
        normalized = text.strip()
        forbidden = ("nodeRef", "transitionRef", "boundaryRef", "evidenceRef", "flowIndex", "analysis-graph-")
        if any(token in normalized for token in forbidden):
            raise HumanAnswerContractViolation(["Response must not expose internal graph refs, node ids, transition refs, evidence refs, or analysis ids."])
        text_validation = self.text_validator.validate(normalized, language)
        if not text_validation.valid:
            if any("language" in error.lower() or "russian" in error.lower() for error in text_validation.errors):
                raise HumanAnswerLanguagePolicyViolation(text_validation.errors)
            raise HumanAnswerContractViolation(text_validation.errors)
        return normalized

    def _record_audit(
        self,
        prompt: str,
        raw_response: str,
        *,
        entrypoint: str,
        attempt_count: int,
        requested_language: str | None,
        resolved_language: str,
    ) -> None:
        self.audit_records.append(
            {
                "provider": self._provider_name(),
                "model": self._provider_model(),
                "promptLength": len(prompt),
                "promptHash": self._sha256(prompt),
                "rawResponseLength": len(raw_response),
                "rawResponseHash": self._sha256(raw_response),
                "flowEntrypoint": entrypoint,
                "attemptCount": attempt_count,
                "requestedLanguage": str(requested_language or "AUTO"),
                "resolvedLanguage": resolved_language,
            }
        )

    def _record_validation_errors(self, *, entrypoint: str, attempt_count: int, errors: Sequence[str]) -> None:
        for record in reversed(self.audit_records):
            if record.get("flowEntrypoint") == entrypoint and record.get("attemptCount") == attempt_count:
                record["validationErrors"] = [str(error) for error in errors]
                return

    def _provider_name(self) -> str:
        value = self.provider_name or getattr(self.provider, "name", None)
        return str(value or self.provider.__class__.__name__)

    def _provider_model(self) -> str:
        value = self.provider_model or getattr(self.provider, "model", None)
        return str(value or "")

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def _remaining_seconds(self, deadline_at: float) -> float:
        return max(0.0, deadline_at - time.monotonic())

    def _cancelled(self) -> bool:
        return bool(self.cancel_event is not None and getattr(self.cancel_event, "is_set", lambda: False)())


class LocalOllamaFlowExplanationClient:
    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: int,
        context_tokens: int,
        http_client: httpx.Client | None = None,
        renderer: Any | None = None,
    ) -> None:
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS)
        if self.context_tokens < 1024:
            raise ValueError("Flow explanation context_tokens must be at least 1024")
        self.renderer = renderer or HumanAnswerPromptRenderer()
        self._client = http_client or httpx.Client(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))

    def complete(
        self,
        llm_input: Mapping[str, Any],
        validation_errors: Sequence[str] | None = None,
        timeout_seconds: float | None = None,
    ) -> FlowExplanationProviderResult:
        prompt = self.renderer.render(llm_input, validation_errors)
        call_timeout = self._call_timeout(timeout_seconds)
        response = self._client.post(
            f"{self.base_url}/api/generate",
            json={
                "model": self.model,
                "prompt": prompt,
                "stream": False,
                "format": "json",
                "options": {"num_ctx": self.context_tokens},
            },
            timeout=httpx.Timeout(call_timeout, connect=min(5.0, call_timeout)),
        )
        response.raise_for_status()
        raw = response.json()
        response_text = raw.get("response")
        if not isinstance(response_text, str):
            raise ValueError("Ollama returned no response text")
        return FlowExplanationProviderResult(raw_text=response_text, prompt_char_length=len(prompt))

    def close(self) -> None:
        self._client.close()

    def _require_localhost(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("Flow explanation LLM base URL must point to localhost")
        return base_url

    def _call_timeout(self, timeout_seconds: float | None) -> float:
        configured = max(0.001, float(self.timeout_seconds or 0.001))
        if timeout_seconds is None:
            return configured
        return max(0.001, min(configured, float(timeout_seconds)))
