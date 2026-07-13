from __future__ import annotations

import json
import re
import time
import urllib.parse
from collections import Counter
from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional, Sequence

import httpx

from knowledge_service.config import (
    DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS,
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
)
from knowledge_service.entrypoint_flow_engine import EntrypointFlow
from knowledge_service.flow_boundary_classifier import FlowBoundaryClassifier, FLOW_BOUNDARY_CLASSIFIER
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphEvidenceKey, FlowGraphNode, evidence_key
from knowledge_service.knowledge_query_schema import (
    FlowExplanation,
    FlowExplanationBoundary,
    FlowExplanationNarrative,
    FlowExplanationStep,
    FlowExplanationStatus,
    FlowExplanationTransition,
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


FLOW_EXPLANATION_LLM_FAILED = "FLOW_EXPLANATION_LLM_FAILED"
FLOW_EXPLANATION_SCHEMA_INVALID = "FLOW_EXPLANATION_SCHEMA_INVALID"
FLOW_EXPLANATION_VALIDATION_FAILED = "FLOW_EXPLANATION_VALIDATION_FAILED"
FLOW_EXPLANATION_LIMIT_REACHED = "FLOW_EXPLANATION_LIMIT_REACHED"
FLOW_EXPLANATION_SKIPPED_NO_FLOW = "FLOW_EXPLANATION_SKIPPED_NO_FLOW"

_BACKTICK_TOKEN_RE = re.compile(r"`([^`]+)`")
_MIN_MEANINGFUL_NARRATIVE_WORDS = 24
_MIN_DISTINCT_MEANINGFUL_NARRATIVE_WORDS = 12
_DEFAULT_MIN_CALL_TIMEOUT_SECONDS = 0.01
_DEADLINE_COMPLETION_GRACE_SECONDS = 0.005


@dataclass(frozen=True)
class FlowExplanationProviderResult:
    raw_text: str
    prompt_char_length: int


@dataclass(frozen=True)
class PackedFlowContext:
    flow_index: int
    llm_input: Dict[str, Any]
    evidence_by_ref: Dict[str, FlowGraphEvidence]

    @property
    def evidence_refs(self) -> set[str]:
        return set(self.evidence_by_ref)


@dataclass(frozen=True)
class FlowExplanationAttempt:
    prompt_char_length: int = 0
    retried: bool = False


@dataclass(frozen=True)
class PerFlowExplanationResult:
    flow_index: int
    flow: EntrypointFlow
    context: PackedFlowContext
    explanation: Optional[Dict[str, Any]]
    diagnostics: List[KnowledgeQueryDiagnostic] = field(default_factory=list)
    attempt: FlowExplanationAttempt = field(default_factory=FlowExplanationAttempt)

    @property
    def ok(self) -> bool:
        return self.explanation is not None


@dataclass(frozen=True)
class FlowExplanationRun:
    query_response: KnowledgeQueryResponse
    results: List[PerFlowExplanationResult]
    diagnostics: List[KnowledgeQueryDiagnostic]


class FlowExplanationDeadlineExceeded(Exception):
    pass


class FlowExplanationPromptRenderer:
    def render(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious response failed validation. Correct these issues without changing the input facts:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += "\n"
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, indent=2, sort_keys=True)
        return (
            "You explain exactly one code flow using only the provided flow facts.\n"
            "Return strict JSON only. Do not use Markdown. Do not invent calls, symbols, classes, methods, side effects, ordering, or boundaries.\n"
            "The response shape is: {\"title\":\"string\","
            "\"narrative\":[{\"text\":\"string\",\"nodeRefs\":[\"n1\"],\"transitionRefs\":[\"t1\"],\"boundaryRefs\":[\"b1\"]}],"
            "\"steps\":[{\"nodeRef\":\"n1\",\"explanation\":\"string\",\"transitionRefs\":[\"t1\"],\"evidenceRefs\":[\"e1\"]}],"
            "\"transitions\":[{\"transitionRef\":\"t1\",\"explanation\":\"string\",\"evidenceRefs\":[\"e1\"]}],"
            "\"boundaries\":[{\"boundaryRef\":\"b1\",\"kind\":\"EXTERNAL\",\"explanation\":\"string\",\"evidenceRefs\":[\"e3\"]}]}.\n"
            "The steps array must cover every input nodeRef. The transitions array must cover every input transitionRef. "
            "Boundary explanations are required for every input boundaryRef when input boundaries exist.\n"
            "Use nodeRefs, transitionRefs, and boundaryRefs to ground each sentence in the exact graph facts it explains.\n"
            "Describe branches and cycles as graph structure; do not imply sibling CALLS transitions are a sequential execution path.\n"
            "When mentioning a code identifier, symbol, method, class, or boundary target in prose, wrap the exact identifier in backticks. "
            "Do not wrap ordinary natural-language words.\n"
            "Use the requested answerLanguage. Shared nodes are repeated here because this is a self-contained flow.\n"
            f"{validation_block}"
            "BEGIN_FLOW_CONTEXT_JSON\n"
            f"{context_json}\n"
            "END_FLOW_CONTEXT_JSON\n"
        )


class HumanAnswerGenerationFailed(Exception):
    pass


class HumanAnswerPromptRenderer:
    def render(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious response failed validation. Correct these issues using only the supplied facts:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += "\n"
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, indent=2, sort_keys=True)
        return (
            "Answer the user's code-flow question as a detailed technical step-by-step explanation for exactly one supplied flow.\n"
            "Return strict JSON only with exactly this shape: {\"text\":\"human-readable answer\"}.\n"
            "Use only the requested answerLanguage for prose; do not mix in another natural language except exact code symbols and quoted literals.\n"
            "Directly answer the question using only the supplied verified flow facts.\n"
            "The text must start with the trigger when present: HTTP method and route, then the entrypoint symbol and its responsibility.\n"
            "Then describe execution as plain numbered steps in the supplied CALLS-tree order. For every meaningful step, include the exact class or method symbol, "
            "what data enters that step, what the step does, what it calls next, and any grounded validation, persistence, or side effect.\n"
            "When validation facts include thresholds, null or empty checks, exception classes, or error messages, include those exact grounded details.\n"
            "Describe branches as branches or parallel actions when facts branch; do not fabricate a sequence between sibling branches.\n"
            "Finish with the observable result: returned response or status, persisted data, and emitted event or external side effect when those facts are supplied.\n"
            "Keep the answer plain text. Numbered lines are preferred; Markdown tables, headings, badges, and graph/debug formatting are not.\n"
            "Do not collapse the flow into a generic summary or repeat tautologies such as creating a site creates a site.\n"
            "Do not omit available method names, class names, trigger details, validation rules, persistence details, side effects, or final results.\n"
            "Do not invent validation, side effects, transports, routes, statuses, or ordering unsupported by the supplied facts.\n"
            "Do not call an outbox write a direct Kafka publish unless the supplied facts include Kafka dispatch.\n"
            "Do not mention graph terminology, retrieval mechanics, refs, nodes, transitions, boundaries, hierarchy, scores, or internal IDs.\n"
            f"{validation_block}"
            "BEGIN_VERIFIED_FLOW_FACTS_JSON\n"
            f"{context_json}\n"
            "END_VERIFIED_FLOW_FACTS_JSON\n"
        )


class CompactFlowProjector:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def to_tool_response(self, request: KnowledgeQueryRequest, execution: Any) -> KnowledgeQueryToolContextResponse:
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            trees=[self._tree(flow) for flow in tuple(execution.flows or ())],
            diagnostics=self._diagnostics(execution),
        )

    def human_llm_input(self, request: KnowledgeQueryRequest, flow: EntrypointFlow) -> Dict[str, Any]:
        tree = self._tree(flow)
        return {
            "queryText": request.queryText,
            "answerLanguage": request.answerLanguage,
            "source": tree.source,
            "entrypoint": tree.entrypoint.symbol,
            "tree": tree.entrypoint.dict(exclude_none=True),
        }

    def flow_answer_identity(self, flow: EntrypointFlow) -> tuple[str, str]:
        return str(flow.key.source_id or ""), self._symbol(flow.entrypoint)

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
        normalized = str(value or "").strip().upper()
        return {
            "HTTP": "HTTP_ENDPOINT",
            "KAFKA": "KAFKA_LISTENER",
            "SCHEDULED": "SCHEDULED_TASK",
            "MESSAGE": "MESSAGE_HANDLER",
            "BOOTSTRAP": "APPLICATION_ENTRYPOINT",
        }.get(normalized, "ENTRYPOINT")

    def _trigger(self, node: FlowGraphNode) -> FlowToolTrigger | None:
        if not node.entrypoint:
            return None
        normalized = str(node.entrypoint_kind or "").strip().upper()
        trigger_kind = {
            "HTTP": "HTTP",
            "KAFKA": "KAFKA",
            "SCHEDULED": "SCHEDULED",
            "MESSAGE": "MESSAGE",
            "BOOTSTRAP": "BOOTSTRAP",
        }.get(normalized)
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


class HumanFlowAnswerService:
    def __init__(
        self,
        provider: Any,
        *,
        max_prompt_chars: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4,
        request_deadline_seconds: float = DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        projector: CompactFlowProjector | None = None,
        renderer: HumanAnswerPromptRenderer | None = None,
        cancel_event: Any | None = None,
    ) -> None:
        self.provider = provider
        self.max_prompt_chars = max(4096, int(max_prompt_chars or DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4))
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS))
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.projector = projector or CompactFlowProjector()
        self.renderer = renderer or HumanAnswerPromptRenderer()
        self.cancel_event = cancel_event

    def answer(
        self,
        request: KnowledgeQueryRequest,
        execution: Any,
        *,
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
        for flow in flows:
            source, entrypoint = self.projector.flow_answer_identity(flow)
            try:
                if self._cancelled():
                    raise FlowExplanationDeadlineExceeded()
                llm_input = self.projector.human_llm_input(request, flow)
                prompt_len = len(self.renderer.render(llm_input))
                if prompt_len > self.max_prompt_chars:
                    raise HumanAnswerGenerationFailed("human answer prompt exceeded budget")
                first = self._complete_with_deadline(llm_input, deadline_at)
                text = self._validate_text(first.raw_text)
                answers.append(KnowledgeFlowAnswer(source=source, entrypoint=entrypoint, text=text))
            except FlowExplanationDeadlineExceeded:
                deadline_failures += 1
                diagnostics.append(self._flow_failure_diagnostic(source, entrypoint))
                if self._cancelled():
                    break
            except HumanAnswerGenerationFailed:
                diagnostics.append(self._flow_failure_diagnostic(source, entrypoint))

        if answers:
            return KnowledgeHumanQueryResponse(
                answerLanguage=request.answerLanguage,
                answers=answers,
                diagnostics=diagnostics,
            )
        if deadline_failures:
            raise FlowExplanationDeadlineExceeded()
        raise HumanAnswerGenerationFailed("no grounded flow answers")

    def _flow_failure_diagnostic(self, source: str, entrypoint: str) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code="HUMAN_FLOW_ANSWER_GENERATION_FAILED",
            message="The local model could not explain one selected flow.",
            severity="WARN",
            sourceId=source or None,
            metadata={"entrypoint": entrypoint},
        )

    def _complete_with_deadline(self, llm_input: Mapping[str, Any], deadline_at: float) -> FlowExplanationProviderResult:
        if self._cancelled() or self._remaining_seconds(deadline_at) <= self.min_call_timeout_seconds:
            raise FlowExplanationDeadlineExceeded()
        remaining = self._remaining_seconds(deadline_at)
        try:
            result = self.provider.complete(llm_input, timeout_seconds=remaining)
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise FlowExplanationDeadlineExceeded() from exc
        except Exception as exc:
            raise HumanAnswerGenerationFailed(str(type(exc).__name__)) from exc
        if self._cancelled() or time.monotonic() > deadline_at + _DEADLINE_COMPLETION_GRACE_SECONDS:
            raise FlowExplanationDeadlineExceeded()
        return result

    def _validate_text(self, raw_text: str) -> str:
        try:
            payload = json.loads(raw_text)
        except Exception as exc:
            raise HumanAnswerGenerationFailed("human answer was not valid JSON") from exc
        if not isinstance(payload, dict):
            raise HumanAnswerGenerationFailed("human answer was not a JSON object")
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip():
            raise HumanAnswerGenerationFailed("human answer text missing")
        normalized = text.strip()
        forbidden = ("nodeRef", "transitionRef", "boundaryRef", "evidenceRef", "flowIndex", "analysis-graph-")
        if any(token in normalized for token in forbidden):
            raise HumanAnswerGenerationFailed("human answer exposed internal graph details")
        return normalized

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
        renderer: FlowExplanationPromptRenderer | None = None,
    ) -> None:
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS)
        if self.context_tokens < 1024:
            raise ValueError("Flow explanation context_tokens must be at least 1024")
        self.renderer = renderer or FlowExplanationPromptRenderer()
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


class FlowExplanationContextPacker:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def pack(
        self,
        *,
        request: KnowledgeQueryRequest,
        flow: EntrypointFlow,
        flow_index: int,
        source_display_name: str | None,
    ) -> PackedFlowContext:
        evidence_by_ref: Dict[str, FlowGraphEvidence] = {}
        evidence_ref_by_key: Dict[FlowGraphEvidenceKey, str] = {}
        for index, evidence in enumerate(flow.evidence, start=1):
            ref = f"e{index}"
            evidence_by_ref[ref] = evidence
            evidence_ref_by_key[evidence_key(evidence)] = ref

        nodes = list(flow.nodes)
        node_ref_by_id = {node.node_id: f"n{index}" for index, node in enumerate(nodes, start=1)}
        steps: List[Dict[str, Any]] = []
        transitions: List[Dict[str, Any]] = []
        for node in nodes:
            node_ref = node_ref_by_id[node.node_id]
            step_refs = self._step_refs(node, flow.evidence, evidence_ref_by_key)
            step: Dict[str, Any] = {
                "nodeRef": node_ref,
                "symbol": self._symbol(node),
                "nodeLabel": node.label,
                "qualifiedName": node.qualified_name,
                "kind": node.node_kind,
                "source": source_display_name,
                "relativePath": node.relative_path,
                "lineStart": node.line_start,
                "lineEnd": node.line_end,
                "summary": node.summary,
                "evidence": [self._evidence_item(ref, evidence_by_ref[ref]) for ref in step_refs],
            }
            steps.append(step)

        nodes_by_id = {node.node_id: node for node in nodes}
        for transition_index, edge in enumerate(flow.transitions, start=1):
            from_node = nodes_by_id[edge.from_node_id]
            to_node = nodes_by_id[edge.to_node_id or ""]
            call_refs = self._edge_refs(edge, flow.evidence, evidence_ref_by_key)
            transitions.append({
                "transitionRef": f"t{transition_index}",
                "fromNodeRef": node_ref_by_id[from_node.node_id],
                "toNodeRef": node_ref_by_id[to_node.node_id],
                "fromSymbol": self._symbol(from_node),
                "toSymbol": self._symbol(to_node),
                "evidenceRefs": call_refs,
                "evidence": [self._evidence_item(ref, evidence_by_ref[ref]) for ref in call_refs],
            })

        boundaries = [
            self._boundary_item(index, edge, flow.evidence, evidence_ref_by_key, evidence_by_ref, node_ref_by_id)
            for index, edge in enumerate(flow.boundary_transitions, start=1)
        ]
        llm_input: Dict[str, Any] = {
            "queryText": request.queryText,
            "answerLanguage": request.answerLanguage,
            "flowIndex": flow_index,
            "source": source_display_name,
            "entrypoint": self._symbol(flow.entrypoint),
            "entrypointOrigin": flow.origin.value,
            "matchedAnchors": [
                {"symbol": item.label, "score": item.score, "distance": item.distance, "matchReasons": list(item.match_reasons)}
                for item in flow.anchors
            ],
            "steps": steps,
            "transitions": transitions,
            "boundaries": boundaries,
        }
        return PackedFlowContext(
            flow_index=flow_index,
            llm_input=llm_input,
            evidence_by_ref=evidence_by_ref,
        )

    def _edges_by_from_to(self, edges: Sequence[FlowGraphEdge]) -> Dict[tuple[str, str], FlowGraphEdge]:
        result: Dict[tuple[str, str], FlowGraphEdge] = {}
        for edge in edges:
            if edge.to_node_id:
                result[(edge.from_node_id, edge.to_node_id)] = edge
        return result

    def _step_refs(
        self,
        node: FlowGraphNode,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_key: Mapping[FlowGraphEvidenceKey, str],
    ) -> List[str]:
        refs: List[str] = []
        for item in evidence:
            if item.node_id == node.node_id:
                self._append_ref(refs, evidence_ref_by_key.get(evidence_key(item)))
        return refs

    def _edge_refs(
        self,
        edge: FlowGraphEdge,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_key: Mapping[FlowGraphEvidenceKey, str],
    ) -> List[str]:
        refs: List[str] = []
        linked_evidence_ids = set(edge.evidence_ids)
        for item in evidence:
            if item.edge_id == edge.edge_id:
                if linked_evidence_ids and item.evidence_id not in linked_evidence_ids:
                    continue
                self._append_ref(refs, evidence_ref_by_key.get(evidence_key(item)))
        return refs

    def _boundary_item(
        self,
        index: int,
        edge: FlowGraphEdge,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_key: Mapping[FlowGraphEvidenceKey, str],
        evidence_by_ref: Mapping[str, FlowGraphEvidence],
        node_ref_by_id: Mapping[str, str],
    ) -> Dict[str, Any]:
        refs = self._edge_refs(edge, evidence, evidence_ref_by_key)
        projection = self.boundary_classifier.project(edge)
        return {
            "boundaryRef": f"b{index}",
            "fromNodeRef": node_ref_by_id.get(edge.from_node_id),
            "kind": projection.kind.value,
            "target": projection.target,
            "resolutionStatus": projection.resolution_status,
            "evidence": [self._evidence_item(ref, evidence_by_ref[ref]) for ref in refs],
            "evidenceRefs": refs,
        }

    def _evidence_item(self, ref: str, evidence: FlowGraphEvidence) -> Dict[str, Any]:
        return {
            "ref": ref,
            "relativePath": evidence.relative_path,
            "lineStart": evidence.line_start,
            "lineEnd": evidence.line_end,
            "excerpt": evidence.text,
        }

    def _append_ref(self, refs: List[str], ref: str | None) -> None:
        if ref and ref not in refs:
            refs.append(ref)

    def _symbol(self, node: FlowGraphNode) -> str:
        return str(node.qualified_name or node.label or node.node_id)


class FlowExplanationValidator:
    def validate(self, raw_text: str, context: PackedFlowContext) -> tuple[Optional[Dict[str, Any]], List[str], str]:
        try:
            parsed = json.loads(raw_text)
        except json.JSONDecodeError as exc:
            return None, [f"JSON must parse: {exc.msg}"], FLOW_EXPLANATION_SCHEMA_INVALID
        if not isinstance(parsed, dict):
            return None, ["JSON root must be an object"], FLOW_EXPLANATION_SCHEMA_INVALID

        normalized, schema_errors = self._normalize_schema(parsed)
        if schema_errors:
            return None, schema_errors, FLOW_EXPLANATION_SCHEMA_INVALID

        validation_errors = self._validate_grounding(normalized, context)
        if validation_errors:
            return None, validation_errors, FLOW_EXPLANATION_VALIDATION_FAILED
        return normalized, [], ""

    def _normalize_schema(self, parsed: Mapping[str, Any]) -> tuple[Dict[str, Any], List[str]]:
        errors: List[str] = []
        title = parsed.get("title")
        if not isinstance(title, str) or not title.strip():
            errors.append("title must be a non-empty string")

        narrative = parsed.get("narrative")
        normalized_narrative: List[Dict[str, Any]] = []
        if not isinstance(narrative, list) or not narrative:
            errors.append("narrative must be a non-empty list")
        else:
            for index, item in enumerate(narrative, start=1):
                if not isinstance(item, dict):
                    errors.append(f"narrative[{index}] must be an object")
                    continue
                text = item.get("text")
                if not isinstance(text, str) or not text.strip():
                    errors.append(f"narrative[{index}].text must be a non-empty string")
                normalized_narrative.append(
                    {
                        "text": str(text or "").strip(),
                        "nodeRefs": self._string_list(item.get("nodeRefs")),
                        "transitionRefs": self._string_list(item.get("transitionRefs")),
                        "boundaryRefs": self._string_list(item.get("boundaryRefs")),
                    }
                )

        steps: List[Dict[str, Any]] = []
        raw_steps = parsed.get("steps")
        if not isinstance(raw_steps, list):
            errors.append("steps must be a list")
        else:
            for index, item in enumerate(raw_steps, start=1):
                if not isinstance(item, dict):
                    errors.append(f"steps[{index}] must be an object")
                    continue
                node_ref = item.get("nodeRef")
                if not isinstance(node_ref, str) or not node_ref.strip():
                    errors.append(f"steps[{index}].nodeRef must be a non-empty string")
                explanation = item.get("explanation")
                if not isinstance(explanation, str) or not explanation.strip():
                    errors.append(f"steps[{index}].explanation must be a non-empty string")
                steps.append(
                    {
                        "nodeRef": str(node_ref or "").strip(),
                        "explanation": str(explanation or ""),
                        "transitionRefs": self._string_list(item.get("transitionRefs")),
                        "evidenceRefs": self._string_list(item.get("evidenceRefs")),
                    }
                )

        transitions: List[Dict[str, Any]] = []
        raw_transitions = parsed.get("transitions", [])
        if not isinstance(raw_transitions, list):
            errors.append("transitions must be a list")
        else:
            for index, item in enumerate(raw_transitions, start=1):
                if not isinstance(item, dict):
                    errors.append(f"transitions[{index}] must be an object")
                    continue
                transition_ref = item.get("transitionRef")
                explanation = item.get("explanation")
                if not isinstance(transition_ref, str) or not transition_ref.strip():
                    errors.append(f"transitions[{index}].transitionRef must be a non-empty string")
                if not isinstance(explanation, str) or not explanation.strip():
                    errors.append(f"transitions[{index}].explanation must be a non-empty string")
                transitions.append(
                    {
                        "transitionRef": str(transition_ref or "").strip(),
                        "explanation": str(explanation or ""),
                        "evidenceRefs": self._string_list(item.get("evidenceRefs")),
                    }
                )

        boundaries: List[Dict[str, Any]] = []
        raw_boundaries = parsed.get("boundaries", [])
        if not isinstance(raw_boundaries, list):
            errors.append("boundaries must be a list")
        else:
            for index, item in enumerate(raw_boundaries, start=1):
                if not isinstance(item, dict):
                    errors.append(f"boundaries[{index}] must be an object")
                    continue
                boundary_ref = item.get("boundaryRef")
                kind = item.get("kind")
                explanation = item.get("explanation")
                if not isinstance(boundary_ref, str) or not boundary_ref.strip():
                    errors.append(f"boundaries[{index}].boundaryRef must be a non-empty string")
                if not isinstance(kind, str) or not kind.strip():
                    errors.append(f"boundaries[{index}].kind must be a non-empty string")
                if not isinstance(explanation, str) or not explanation.strip():
                    errors.append(f"boundaries[{index}].explanation must be a non-empty string")
                boundaries.append(
                    {
                        "boundaryRef": str(boundary_ref or "").strip(),
                        "kind": str(kind or ""),
                        "explanation": str(explanation or ""),
                        "evidenceRefs": self._string_list(item.get("evidenceRefs")),
                    }
                )
        if errors:
            return {}, errors
        return {
            "title": title.strip(),
            "narrative": normalized_narrative,
            "steps": steps,
            "transitions": transitions,
            "boundaries": boundaries,
        }, []

    def _validate_grounding(self, explanation: Mapping[str, Any], context: PackedFlowContext) -> List[str]:
        errors: List[str] = []
        input_steps = context.llm_input.get("steps") if isinstance(context.llm_input.get("steps"), list) else []
        input_transitions = context.llm_input.get("transitions") if isinstance(context.llm_input.get("transitions"), list) else []
        input_boundaries = context.llm_input.get("boundaries") if isinstance(context.llm_input.get("boundaries"), list) else []

        input_node_by_ref = {
            str(step.get("nodeRef")): step
            for step in input_steps
            if isinstance(step, dict) and isinstance(step.get("nodeRef"), str)
        }
        input_transition_by_ref = {
            str(item.get("transitionRef")): item
            for item in input_transitions
            if isinstance(item, dict) and isinstance(item.get("transitionRef"), str)
        }
        input_boundary_by_ref = {
            str(item.get("boundaryRef")): item
            for item in input_boundaries
            if isinstance(item, dict) and isinstance(item.get("boundaryRef"), str)
        }

        output_steps = [item for item in explanation.get("steps", []) if isinstance(item, dict)]
        output_node_refs = [str(step.get("nodeRef") or "") for step in output_steps]
        self._extend_ref_set_errors(errors, "node", set(input_node_by_ref), output_node_refs)

        output_transition_refs = [
            str(item.get("transitionRef") or "")
            for item in explanation.get("transitions", [])
            if isinstance(item, dict)
        ]
        self._extend_ref_set_errors(errors, "transition", set(input_transition_by_ref), output_transition_refs)

        output_boundaries = [item for item in explanation.get("boundaries", []) if isinstance(item, dict)]
        output_boundary_refs = [str(item.get("boundaryRef") or "") for item in output_boundaries]
        self._extend_ref_set_errors(errors, "boundary", set(input_boundary_by_ref), output_boundary_refs)
        for item in output_boundaries:
            boundary_ref = str(item.get("boundaryRef") or "")
            input_boundary = input_boundary_by_ref.get(boundary_ref)
            if input_boundary is not None and str(item.get("kind") or "") != str(input_boundary.get("kind") or ""):
                errors.append(f"boundaryRef {boundary_ref} kind does not match the input boundary")

        for ref in self._output_evidence_refs(explanation):
            if ref not in context.evidence_refs:
                errors.append(f"evidence ref {ref} is not present in the packed flow context")
        errors.extend(self._evidence_ownership_errors(explanation, input_steps, input_transitions, input_boundaries))
        for item in output_steps:
            unknown_transition_refs = sorted(set(item.get("transitionRefs") or []) - set(input_transition_by_ref))
            if unknown_transition_refs:
                errors.append(f"nodeRef {item.get('nodeRef')} references unknown transitions {unknown_transition_refs}")
        errors.extend(self._step_transition_ownership_errors(output_steps, input_transition_by_ref))

        errors.extend(self._narrative_errors(explanation, input_node_by_ref, input_transition_by_ref, input_boundary_by_ref))
        aliases_by_node_ref = self._allowed_aliases_by_node_ref(input_steps)
        aliases_by_boundary_ref = self._allowed_boundary_aliases_by_ref(input_boundaries)
        errors.extend(
            self._symbol_grounding_errors(
                explanation,
                aliases_by_node_ref,
                input_transition_by_ref,
                aliases_by_boundary_ref,
            )
        )
        return errors

    def _extend_ref_set_errors(self, errors: List[str], label: str, input_refs: set[str], output_refs: Sequence[str]) -> None:
        output_counts = Counter(ref for ref in output_refs if ref)
        missing = sorted(input_refs - set(output_counts))
        extra = sorted(set(output_counts) - input_refs)
        duplicates = sorted(ref for ref, count in output_counts.items() if count > 1)
        if missing:
            errors.append(f"{label} refs must cover every input {label}; missing {missing}")
        if extra:
            errors.append(f"{label} refs are not present in the input flow: {extra}")
        if duplicates:
            errors.append(f"{label} refs must be unique; duplicates {duplicates}")

    def _step_transition_ownership_errors(
        self,
        output_steps: Sequence[Mapping[str, Any]],
        input_transition_by_ref: Mapping[str, Any],
    ) -> List[str]:
        errors: List[str] = []
        expected_by_node_ref: Dict[str, List[str]] = {}
        for transition_ref, transition in input_transition_by_ref.items():
            if not isinstance(transition, dict):
                continue
            from_node_ref = str(transition.get("fromNodeRef") or "")
            if from_node_ref:
                expected_by_node_ref.setdefault(from_node_ref, []).append(str(transition_ref))

        for step in output_steps:
            node_ref = str(step.get("nodeRef") or "")
            actual_refs = list(step.get("transitionRefs") or [])
            actual = set(actual_refs)
            expected = set(expected_by_node_ref.get(node_ref, []))
            duplicates = sorted(ref for ref, count in Counter(actual_refs).items() if count > 1)
            if duplicates:
                errors.append(f"nodeRef {node_ref} transitionRefs must be unique; duplicates {duplicates}")
            if expected and actual != expected:
                errors.append(f"nodeRef {node_ref} must reference its exact outgoing transition refs {sorted(expected)}")
            if not expected and actual:
                errors.append(f"terminal nodeRef {node_ref} must not reference transitions")
            wrong_owner = sorted(
                ref
                for ref in actual
                if ref in input_transition_by_ref
                and str(input_transition_by_ref[ref].get("fromNodeRef") or "") != node_ref
            )
            if wrong_owner:
                errors.append(f"nodeRef {node_ref} references another node's transition refs {wrong_owner}")
        return errors

    def _string_list(self, value: Any) -> List[str]:
        if not isinstance(value, list):
            return []
        return [str(item) for item in value if isinstance(item, str) and item]

    def _evidence_ownership_errors(
        self,
        explanation: Mapping[str, Any],
        input_steps: Sequence[Any],
        input_transitions: Sequence[Any],
        input_boundaries: Sequence[Any],
    ) -> List[str]:
        errors: List[str] = []
        allowed_by_step = {
            str(item.get("nodeRef")): self._context_evidence_refs(item)
            for item in input_steps
            if isinstance(item, dict) and isinstance(item.get("nodeRef"), str)
        }
        allowed_by_transition = {
            str(item.get("transitionRef")): self._context_evidence_refs(item)
            for item in input_transitions
            if isinstance(item, dict) and isinstance(item.get("transitionRef"), str)
        }
        allowed_by_boundary = {
            str(item.get("boundaryRef")): self._context_evidence_refs(item)
            for item in input_boundaries
            if isinstance(item, dict) and isinstance(item.get("boundaryRef"), str)
        }
        for item in explanation.get("steps", []):
            if not isinstance(item, dict):
                continue
            node_ref = str(item.get("nodeRef") or "")
            allowed = allowed_by_step.get(node_ref)
            if allowed is None:
                continue
            for ref in item.get("evidenceRefs", []):
                if isinstance(ref, str) and ref not in allowed:
                    errors.append(f"evidence ref {ref} is not valid for nodeRef {node_ref}")
        for item in explanation.get("transitions", []):
            if not isinstance(item, dict):
                continue
            transition_ref = str(item.get("transitionRef") or "")
            allowed = allowed_by_transition.get(transition_ref)
            if allowed is None:
                continue
            for ref in item.get("evidenceRefs", []):
                if isinstance(ref, str) and ref not in allowed:
                    errors.append(f"evidence ref {ref} is not valid for transitionRef {transition_ref}")
        for item in explanation.get("boundaries", []):
            if not isinstance(item, dict):
                continue
            boundary_ref = str(item.get("boundaryRef") or "")
            allowed = allowed_by_boundary.get(boundary_ref)
            if allowed is None:
                continue
            for ref in item.get("evidenceRefs", []):
                if isinstance(ref, str) and ref not in allowed:
                    errors.append(f"evidence ref {ref} is not valid for boundaryRef {boundary_ref}")
        return errors

    def _context_evidence_refs(self, item: Mapping[str, Any]) -> set[str]:
        refs = {str(ref) for ref in item.get("evidenceRefs", []) if isinstance(ref, str)}
        evidence = item.get("evidence", [])
        if isinstance(evidence, list):
            for evidence_item in evidence:
                if isinstance(evidence_item, dict) and isinstance(evidence_item.get("ref"), str):
                    refs.add(str(evidence_item["ref"]))
        return refs

    def _output_evidence_refs(self, explanation: Mapping[str, Any]) -> List[str]:
        refs: List[str] = []
        for section in ("steps", "transitions", "boundaries"):
            items = explanation.get(section)
            if not isinstance(items, list):
                continue
            for item in items:
                if isinstance(item, dict):
                    refs.extend(str(ref) for ref in item.get("evidenceRefs", []) if isinstance(ref, str))
        return refs

    def _explanation_text(self, explanation: Mapping[str, Any]) -> str:
        parts: List[str] = [str(explanation.get("title") or "")]
        parts.extend(str(item.get("text") or "") for item in explanation.get("narrative", []) if isinstance(item, dict))
        for section in ("steps", "transitions", "boundaries"):
            items = explanation.get(section)
            if isinstance(items, list):
                parts.extend(str(item.get("explanation") or "") for item in items if isinstance(item, dict))
        return "\n".join(parts)

    def _narrative_errors(
        self,
        explanation: Mapping[str, Any],
        input_node_by_ref: Mapping[str, Any],
        input_transition_by_ref: Mapping[str, Any],
        input_boundary_by_ref: Mapping[str, Any],
    ) -> List[str]:
        errors: List[str] = []
        narrative = [item for item in explanation.get("narrative", []) if isinstance(item, dict)]
        texts = [str(item.get("text") or "") for item in narrative]
        words = [word for text in texts for word in re.findall(r"\w+", text, flags=re.UNICODE)]
        meaningful_words = [word.lower() for word in words if len(word) >= 3]
        if len(narrative) < 2:
            errors.append("narrative must contain at least two grounded blocks")
        if len(words) < _MIN_MEANINGFUL_NARRATIVE_WORDS or len(set(meaningful_words)) < _MIN_DISTINCT_MEANINGFUL_NARRATIVE_WORDS:
            errors.append("narrative must contain meaningful explanatory detail")
        known_node_refs = set(input_node_by_ref)
        known_transition_refs = set(input_transition_by_ref)
        known_boundary_refs = set(input_boundary_by_ref)
        for index, item in enumerate(narrative, start=1):
            node_refs = set(item.get("nodeRefs") or [])
            transition_refs = set(item.get("transitionRefs") or [])
            boundary_refs = set(item.get("boundaryRefs") or [])
            if not node_refs and not transition_refs and not boundary_refs:
                errors.append(f"narrative[{index}] must include at least one grounding ref")
            unknown_nodes = sorted(node_refs - known_node_refs)
            unknown_transitions = sorted(transition_refs - known_transition_refs)
            unknown_boundaries = sorted(boundary_refs - known_boundary_refs)
            if unknown_nodes:
                errors.append(f"narrative[{index}] references unknown nodes {unknown_nodes}")
            if unknown_transitions:
                errors.append(f"narrative[{index}] references unknown transitions {unknown_transitions}")
            if unknown_boundaries:
                errors.append(f"narrative[{index}] references unknown boundaries {unknown_boundaries}")
        return errors

    def _allowed_aliases_by_node_ref(self, input_steps: Sequence[Any]) -> Dict[str, set[str]]:
        result: Dict[str, set[str]] = {}
        for item in input_steps:
            if not isinstance(item, dict) or not isinstance(item.get("nodeRef"), str):
                continue
            aliases: set[str] = set()
            for key in ("symbol", "nodeLabel", "qualifiedName"):
                value = item.get(key)
                if isinstance(value, str) and value:
                    aliases.update(self._aliases(value))
            result[str(item["nodeRef"])] = aliases
        return result

    def _allowed_boundary_aliases_by_ref(self, input_boundaries: Sequence[Any]) -> Dict[str, set[str]]:
        result: Dict[str, set[str]] = {}
        for item in input_boundaries:
            if not isinstance(item, dict) or not isinstance(item.get("boundaryRef"), str):
                continue
            aliases: set[str] = set()
            target = item.get("target")
            if isinstance(target, str) and target:
                aliases.update(self._aliases(target))
            kind = item.get("kind")
            if isinstance(kind, str) and kind:
                aliases.add(kind)
            result[str(item["boundaryRef"])] = aliases
        return result

    def _aliases(self, value: str) -> set[str]:
        parts = [part for part in value.split(".") if part]
        aliases: set[str] = {value}
        aliases.update(part for part in parts if part)
        for index in range(0, max(0, len(parts) - 1)):
            alias = ".".join(parts[index:])
            if "." in alias:
                aliases.add(alias)
        return aliases

    def _symbol_grounding_errors(
        self,
        explanation: Mapping[str, Any],
        aliases_by_node_ref: Mapping[str, set[str]],
        input_transition_by_ref: Mapping[str, Any],
        aliases_by_boundary_ref: Mapping[str, set[str]],
    ) -> List[str]:
        errors: List[str] = []
        all_aliases = set().union(*aliases_by_node_ref.values(), *aliases_by_boundary_ref.values()) if aliases_by_node_ref or aliases_by_boundary_ref else set()

        for symbol in sorted(self._code_symbols(str(explanation.get("title") or ""))):
            if symbol not in all_aliases:
                errors.append(f"symbol {symbol} is not present in the input flow context")

        for text, node_refs, transition_refs, boundary_refs in self._grounded_texts(explanation):
            selected_aliases = self._selected_aliases(
                node_refs,
                transition_refs,
                boundary_refs,
                aliases_by_node_ref,
                input_transition_by_ref,
                aliases_by_boundary_ref,
            )
            for symbol in sorted(self._code_symbols(text)):
                if symbol not in all_aliases:
                    errors.append(f"symbol {symbol} is not present in the input flow context")
                elif selected_aliases and symbol not in selected_aliases:
                    errors.append(f"symbol {symbol} is not grounded by refs for this text")
        return sorted(set(errors))

    def _grounded_texts(self, explanation: Mapping[str, Any]) -> List[tuple[str, set[str], set[str], set[str]]]:
        items: List[tuple[str, set[str], set[str], set[str]]] = []
        for item in explanation.get("narrative", []):
            if isinstance(item, dict):
                items.append(
                    (
                        str(item.get("text") or ""),
                        set(item.get("nodeRefs") or []),
                        set(item.get("transitionRefs") or []),
                        set(item.get("boundaryRefs") or []),
                    )
                )
        for item in explanation.get("steps", []):
            if isinstance(item, dict):
                items.append(
                    (
                        str(item.get("explanation") or ""),
                        {str(item.get("nodeRef") or "")},
                        set(item.get("transitionRefs") or []),
                        set(),
                    )
                )
        for item in explanation.get("transitions", []):
            if isinstance(item, dict):
                items.append((str(item.get("explanation") or ""), set(), {str(item.get("transitionRef") or "")}, set()))
        for item in explanation.get("boundaries", []):
            if isinstance(item, dict):
                items.append((str(item.get("explanation") or ""), set(), set(), {str(item.get("boundaryRef") or "")}))
        return items

    def _selected_aliases(
        self,
        node_refs: set[str],
        transition_refs: set[str],
        boundary_refs: set[str],
        aliases_by_node_ref: Mapping[str, set[str]],
        input_transition_by_ref: Mapping[str, Any],
        aliases_by_boundary_ref: Mapping[str, set[str]],
    ) -> set[str]:
        selected: set[str] = set()
        for ref in node_refs:
            selected.update(aliases_by_node_ref.get(ref, set()))
        for ref in transition_refs:
            transition = input_transition_by_ref.get(ref)
            if not isinstance(transition, dict):
                continue
            selected.update(aliases_by_node_ref.get(str(transition.get("fromNodeRef") or ""), set()))
            selected.update(aliases_by_node_ref.get(str(transition.get("toNodeRef") or ""), set()))
        for ref in boundary_refs:
            selected.update(aliases_by_boundary_ref.get(ref, set()))
        return selected

    def _code_symbols(self, text: str) -> set[str]:
        symbols: set[str] = set()
        for raw in _BACKTICK_TOKEN_RE.findall(text):
            token = raw.strip()
            if token:
                symbols.add(token)
        return symbols


class FlowExplanationService:
    def __init__(
        self,
        provider: Any,
        *,
        max_prompt_chars: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4,
        request_deadline_seconds: float = DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        packer: FlowExplanationContextPacker | None = None,
        validator: FlowExplanationValidator | None = None,
        renderer: FlowExplanationPromptRenderer | None = None,
        cancel_event: Any | None = None,
    ) -> None:
        self.provider = provider
        self.max_prompt_chars = max(4096, int(max_prompt_chars or DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4))
        self.request_deadline_seconds = max(
            0.001,
            float(request_deadline_seconds or DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS),
        )
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.packer = packer or FlowExplanationContextPacker()
        self.validator = validator or FlowExplanationValidator()
        self.renderer = renderer or FlowExplanationPromptRenderer()
        self.cancel_event = cancel_event

    def explain(self, request: KnowledgeQueryRequest, execution: Any, *, deadline_at: float | None = None) -> FlowExplanationRun:
        query_response = execution.response
        flows: tuple[EntrypointFlow, ...] = tuple(execution.flows or ())
        source_names = {source.sourceId: source.displayName for source in query_response.matchedSources}
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        results: List[PerFlowExplanationResult] = []
        if not flows:
            diagnostic = KnowledgeQueryDiagnostic(
                code=FLOW_EXPLANATION_SKIPPED_NO_FLOW,
                message="No entrypoint flows were available for per-flow explanation.",
                severity="INFO",
            )
            return FlowExplanationRun(query_response=query_response, results=[], diagnostics=[diagnostic])

        if deadline_at is None:
            deadline_at = time.monotonic() + self.request_deadline_seconds
        for flow_index, flow in enumerate(flows, start=1):
            source_display_name = source_names.get(flow.key.source_id) or flow.key.source_id or None
            packed = self.packer.pack(
                request=request,
                flow=flow,
                flow_index=flow_index,
                source_display_name=source_display_name,
            )
            prompt_len = len(self.renderer.render(packed.llm_input))
            if not self._can_start_call(deadline_at):
                diagnostic = self._deadline_diagnostic(
                    "Flow explanation request deadline was reached before this flow could be explained.",
                    flow_index,
                )
                diagnostics.append(diagnostic)
                results.append(
                    PerFlowExplanationResult(
                        flow_index=flow_index,
                        flow=flow,
                        context=packed,
                        explanation=None,
                        diagnostics=[diagnostic],
                        attempt=FlowExplanationAttempt(prompt_char_length=prompt_len),
                    )
                )
                continue
            if prompt_len > self.max_prompt_chars:
                diagnostic = self._diagnostic(
                    FLOW_EXPLANATION_LIMIT_REACHED,
                    "Flow explanation prompt exceeded the configured local LLM prompt budget.",
                    flow_index,
                    severity="WARN",
                    metadata={"promptCharLength": prompt_len, "maxPromptChars": self.max_prompt_chars},
                )
                diagnostics.append(diagnostic)
                results.append(
                    PerFlowExplanationResult(
                        flow_index=flow_index,
                        flow=flow,
                        context=packed,
                        explanation=None,
                        diagnostics=[diagnostic],
                        attempt=FlowExplanationAttempt(prompt_char_length=prompt_len),
                    )
                )
                continue
            result = self._explain_one(flow, packed, deadline_at)
            diagnostics.extend(result.diagnostics)
            results.append(result)
        return FlowExplanationRun(query_response=query_response, results=results, diagnostics=diagnostics)

    def to_ui_response(self, run: FlowExplanationRun) -> Dict[str, Any]:
        base = run.query_response.dict()
        base["flowExplanations"] = [self._ui_explanation(result) for result in run.results]
        base["diagnostics"] = [*base.get("diagnostics", []), *[diagnostic.dict() for diagnostic in run.diagnostics]]
        return base

    def to_tool_response(self, request: KnowledgeQueryRequest, run: FlowExplanationRun) -> KnowledgeQueryToolContextResponse:
        execution = type(
            "FlowProjectionExecution",
            (),
            {
                "response": run.query_response,
                "flows": tuple(result.flow for result in run.results),
            },
        )()
        return CompactFlowProjector().to_tool_response(request, execution)

    def _explain_one(self, flow: EntrypointFlow, context: PackedFlowContext, deadline_at: float) -> PerFlowExplanationResult:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        try:
            first = self._complete_with_deadline(context.llm_input, deadline_at)
        except FlowExplanationDeadlineExceeded:
            diagnostic = self._deadline_diagnostic(
                "Flow explanation request deadline was reached before this flow could be explained.",
                context.flow_index,
            )
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=None,
                diagnostics=[diagnostic],
            )
        except Exception as exc:
            diagnostic = self._diagnostic(
                FLOW_EXPLANATION_LLM_FAILED,
                "Local LLM call failed while explaining this flow.",
                context.flow_index,
                severity="WARN",
                metadata={"error": type(exc).__name__},
            )
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=None,
                diagnostics=[diagnostic],
            )
        explanation, errors, code = self.validator.validate(first.raw_text, context)
        if explanation is not None:
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=explanation,
                diagnostics=[],
                attempt=FlowExplanationAttempt(prompt_char_length=first.prompt_char_length),
            )

        if not self._can_start_call(deadline_at):
            diagnostics.append(
                self._diagnostic(
                    FLOW_EXPLANATION_LIMIT_REACHED,
                    "Flow explanation request deadline was reached before validation retry could start.",
                    context.flow_index,
                    severity="WARN",
                    metadata={"requestDeadlineSeconds": self.request_deadline_seconds, "validationErrors": errors[:10]},
                )
            )
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=None,
                diagnostics=diagnostics,
                attempt=FlowExplanationAttempt(prompt_char_length=first.prompt_char_length),
            )

        try:
            second = self._complete_with_deadline(context.llm_input, deadline_at, errors)
        except FlowExplanationDeadlineExceeded:
            diagnostics.append(
                self._diagnostic(
                    FLOW_EXPLANATION_LIMIT_REACHED,
                    "Flow explanation request deadline was reached before validation retry could complete.",
                    context.flow_index,
                    severity="WARN",
                    metadata={"requestDeadlineSeconds": self.request_deadline_seconds, "validationErrors": errors[:10]},
                )
            )
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=None,
                diagnostics=diagnostics,
                attempt=FlowExplanationAttempt(prompt_char_length=first.prompt_char_length),
            )
        except Exception as exc:
            diagnostics.append(
                self._diagnostic(
                    FLOW_EXPLANATION_LLM_FAILED,
                    "Local LLM retry failed while explaining this flow.",
                    context.flow_index,
                    severity="WARN",
                    metadata={"error": type(exc).__name__, "validationErrors": errors[:10]},
                )
            )
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=None,
                diagnostics=diagnostics,
                attempt=FlowExplanationAttempt(prompt_char_length=first.prompt_char_length, retried=True),
            )

        explanation, retry_errors, retry_code = self.validator.validate(second.raw_text, context)
        if explanation is not None:
            diagnostics.append(
                self._diagnostic(
                    code,
                    "Flow explanation LLM output failed validation and was corrected on retry.",
                    context.flow_index,
                    severity="INFO",
                    metadata={"validationErrors": errors[:10], "retried": True},
                )
            )
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow=flow,
                context=context,
                explanation=explanation,
                diagnostics=diagnostics,
                attempt=FlowExplanationAttempt(prompt_char_length=second.prompt_char_length, retried=True),
            )

        diagnostics.append(
            self._diagnostic(
                retry_code or code,
                "Flow explanation LLM output failed validation after one retry.",
                context.flow_index,
                severity="WARN",
                metadata={"validationErrors": retry_errors[:10], "initialValidationErrors": errors[:10], "retried": True},
            )
        )
        return PerFlowExplanationResult(
            flow_index=context.flow_index,
            flow=flow,
            context=context,
            explanation=None,
            diagnostics=diagnostics,
            attempt=FlowExplanationAttempt(prompt_char_length=second.prompt_char_length, retried=True),
        )

    def _remaining_seconds(self, deadline_at: float) -> float:
        return max(0.0, deadline_at - time.monotonic())

    def _can_start_call(self, deadline_at: float) -> bool:
        return not self._cancelled() and self._remaining_seconds(deadline_at) > self.min_call_timeout_seconds

    def _complete_with_deadline(
        self,
        llm_input: Mapping[str, Any],
        deadline_at: float,
        validation_errors: Sequence[str] | None = None,
    ) -> FlowExplanationProviderResult:
        if self._cancelled():
            raise FlowExplanationDeadlineExceeded()
        remaining = self._remaining_seconds(deadline_at)
        if remaining <= self.min_call_timeout_seconds:
            raise FlowExplanationDeadlineExceeded()
        try:
            result = self.provider.complete(llm_input, validation_errors, timeout_seconds=remaining)
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise FlowExplanationDeadlineExceeded() from exc
        if self._cancelled():
            raise FlowExplanationDeadlineExceeded()
        if time.monotonic() > deadline_at + _DEADLINE_COMPLETION_GRACE_SECONDS:
            raise FlowExplanationDeadlineExceeded()
        return result

    def _cancelled(self) -> bool:
        return bool(self.cancel_event is not None and getattr(self.cancel_event, "is_set", lambda: False)())

    def _deadline_diagnostic(self, message: str, flow_index: int) -> KnowledgeQueryDiagnostic:
        return self._diagnostic(
            FLOW_EXPLANATION_LIMIT_REACHED,
            message,
            flow_index,
            severity="WARN",
            metadata={"requestDeadlineSeconds": self.request_deadline_seconds},
        )

    def _ui_explanation(self, result: PerFlowExplanationResult) -> FlowExplanation:
        steps_by_ref = self._step_explanations(result.explanation)
        transitions_by_ref = self._transition_explanations(result.explanation)
        boundaries_by_ref = self._boundary_explanations(result.explanation)
        input_steps = result.context.llm_input.get("steps") or []
        input_transitions = [item for item in result.context.llm_input.get("transitions", []) if isinstance(item, dict)]
        input_boundaries = [item for item in result.context.llm_input.get("boundaries", []) if isinstance(item, dict)]
        return FlowExplanation(
            flowIndex=result.flow_index,
            title=str(result.explanation.get("title") if result.explanation else ""),
            narrative=self._public_narrative(result.explanation),
            steps=[
                FlowExplanationStep(
                    nodeRef=str(step.get("nodeRef") or ""),
                    nodeLabel=str(step.get("nodeLabel") or step.get("symbol") or ""),
                    explanation=steps_by_ref.get(str(step.get("nodeRef") or ""), {}).get("explanation"),
                    transitionRefs=list(steps_by_ref.get(str(step.get("nodeRef") or ""), {}).get("transitionRefs") or []),
                    evidenceRefs=self._ui_evidence_refs(result, steps_by_ref.get(str(step.get("nodeRef") or ""), {}).get("evidenceRefs", [])),
                )
                for step in input_steps
                if isinstance(step, dict) and isinstance(step.get("nodeRef"), str)
            ],
            transitionExplanations=[
                FlowExplanationTransition(
                    transitionRef=str(input_transition.get("transitionRef") or ""),
                    explanation=str(item.get("explanation") or "") if item else None,
                    evidenceRefs=self._ui_evidence_refs(result, item.get("evidenceRefs") or []),
                )
                for input_transition in input_transitions
                for item in [transitions_by_ref.get(str(input_transition.get("transitionRef") or ""), {})]
                if isinstance(input_transition.get("transitionRef"), str)
            ],
            boundaries=[
                FlowExplanationBoundary(
                    boundaryRef=str(input_boundary.get("boundaryRef") or ""),
                    fromNodeRef=str(input_boundary.get("fromNodeRef") or ""),
                    kind=str(input_boundary.get("kind") or ""),
                    resolutionStatus=str(input_boundary.get("resolutionStatus") or ""),
                    target=self._optional_string(input_boundary.get("target")),
                    explanation=str(item.get("explanation") or "") if item else None,
                    evidenceRefs=self._ui_evidence_refs(result, item.get("evidenceRefs") or []),
                )
                for input_boundary in input_boundaries
                for item in [boundaries_by_ref.get(str(input_boundary.get("boundaryRef") or ""), {})]
            ],
            status=FlowExplanationStatus.OK if result.ok else FlowExplanationStatus.FAILED,
        )

    def _tool_flow(self, result: PerFlowExplanationResult) -> FlowToolContext:
        steps_by_ref = self._step_explanations(result.explanation)
        transitions_by_ref = self._transition_explanations(result.explanation)
        input_steps = [step for step in result.context.llm_input.get("steps", []) if isinstance(step, dict)]
        input_transitions = [item for item in result.context.llm_input.get("transitions", []) if isinstance(item, dict)]
        input_boundaries = [item for item in result.context.llm_input.get("boundaries", []) if isinstance(item, dict)]
        return FlowToolContext(
            flowIndex=result.flow_index,
            status=FlowExplanationStatus.OK if result.ok else FlowExplanationStatus.FAILED,
            title=str(result.explanation.get("title") if result.explanation else ""),
            narrative=self._public_narrative(result.explanation),
            steps=[
                self._tool_step(result, step, steps_by_ref.get(str(step.get("nodeRef") or ""), {}))
                for step in input_steps
                if isinstance(step.get("nodeRef"), str)
            ],
            transitions=[
                self._tool_transition(result, item, transitions_by_ref.get(str(item.get("transitionRef") or ""), {}))
                for item in input_transitions
                if isinstance(item.get("fromNodeRef"), str)
                and isinstance(item.get("toNodeRef"), str)
            ],
            boundaries=[self._tool_boundary(result, item) for item in input_boundaries],
            diagnostics=[self._compact_diagnostic(diagnostic) for diagnostic in result.diagnostics],
        )

    def _tool_step(
        self,
        result: PerFlowExplanationResult,
        step: Mapping[str, Any],
        explanation_step: Mapping[str, Any],
    ) -> FlowToolStep:
        evidence_refs = list(explanation_step.get("evidenceRefs") or []) or [item["ref"] for item in step.get("evidence", []) if isinstance(item, dict)]
        evidence = [self._tool_evidence(ref, result.context.evidence_by_ref.get(ref)) for ref in evidence_refs]
        node_evidence_refs = [item["ref"] for item in step.get("evidence", []) if isinstance(item, dict)]
        node_evidence = [self._tool_evidence(ref, result.context.evidence_by_ref.get(ref)) for ref in node_evidence_refs]
        address = self._address(step, node_evidence)
        return FlowToolStep(
            nodeRef=str(step.get("nodeRef") or ""),
            symbol=str(step.get("symbol") or step.get("nodeLabel") or ""),
            kind=str(step.get("kind") or ""),
            address=address,
            explanation=str(explanation_step.get("explanation") or "") if explanation_step else None,
            evidence=[item for item in evidence if item is not None],
        )

    def _tool_transition(
        self,
        result: PerFlowExplanationResult,
        item: Mapping[str, Any],
        explanation_item: Mapping[str, Any],
    ) -> FlowToolTransition:
        evidence_refs = list(explanation_item.get("evidenceRefs") or []) or [
            evidence["ref"] for evidence in item.get("evidence", []) if isinstance(evidence, dict)
        ]
        evidence = [self._tool_evidence(ref, result.context.evidence_by_ref.get(ref)) for ref in evidence_refs]
        return FlowToolTransition(
            transitionRef=str(item.get("transitionRef") or ""),
            fromNodeRef=str(item.get("fromNodeRef") or ""),
            toNodeRef=str(item.get("toNodeRef") or ""),
            fromSymbol=str(item.get("fromSymbol") or ""),
            toSymbol=str(item.get("toSymbol") or ""),
            explanation=str(explanation_item.get("explanation") or "") if explanation_item else None,
            evidence=[entry for entry in evidence if entry is not None],
        )

    def _tool_boundary(self, result: PerFlowExplanationResult, item: Mapping[str, Any]) -> FlowToolBoundary:
        explanations = self._boundary_explanations(result.explanation)
        explanation_item = explanations.get(str(item.get("boundaryRef") or ""), {})
        evidence_refs = list(explanation_item.get("evidenceRefs") or []) or [
            evidence["ref"] for evidence in item.get("evidence", []) if isinstance(evidence, dict)
        ]
        evidence = [self._tool_evidence(ref, result.context.evidence_by_ref.get(ref)) for ref in evidence_refs]
        return FlowToolBoundary(
            boundaryRef=str(item.get("boundaryRef") or ""),
            fromNodeRef=str(item.get("fromNodeRef") or ""),
            kind=str(item.get("kind") or ""),
            resolutionStatus=str(item.get("resolutionStatus") or ""),
            target=self._optional_string(item.get("target")),
            explanation=str(explanation_item.get("explanation") or "") if explanation_item else None,
            evidence=[entry for entry in evidence if entry is not None],
        )

    def _optional_string(self, value: Any) -> str | None:
        if isinstance(value, str) and value.strip():
            return value.strip()
        return None

    def _address(self, step: Mapping[str, Any], evidence: Sequence[FlowToolEvidence | None]) -> FlowToolAddress:
        node_path = self._node_relative_path(step)
        node_line_start = self._node_line(step.get("lineStart"))
        node_line_end = self._node_line(step.get("lineEnd"))
        if node_path and node_line_start is not None:
            return FlowToolAddress(
                service=str(step.get("source")) if step.get("source") else None,
                relativePath=node_path,
                lineStart=node_line_start,
                lineEnd=node_line_end if node_line_end is not None else node_line_start,
            )
        first_evidence = next((item for item in evidence if item is not None and item.relativePath), None)
        return FlowToolAddress(
            service=str(step.get("source")) if step.get("source") else None,
            relativePath=first_evidence.relativePath if first_evidence else node_path,
            lineStart=first_evidence.lineStart if first_evidence else None,
            lineEnd=(
                first_evidence.lineEnd if first_evidence and first_evidence.lineEnd is not None else first_evidence.lineStart
            )
            if first_evidence
            else None,
        )

    def _node_relative_path(self, step: Mapping[str, Any]) -> str | None:
        value = step.get("relativePath")
        return str(value) if value else None

    def _node_line(self, value: Any) -> int | None:
        if value is None:
            return None
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _tool_evidence(self, ref: str, evidence: FlowGraphEvidence | None) -> FlowToolEvidence | None:
        if evidence is None:
            return None
        return FlowToolEvidence(
            ref=ref,
            relativePath=evidence.relative_path,
            lineStart=evidence.line_start,
            lineEnd=evidence.line_end,
            excerpt=evidence.text,
        )

    def _step_explanations(self, explanation: Mapping[str, Any] | None) -> Dict[str, Dict[str, Any]]:
        if not explanation:
            return {}
        return {
            str(item.get("nodeRef")): dict(item)
            for item in explanation.get("steps", [])
            if isinstance(item, dict) and isinstance(item.get("nodeRef"), str)
        }

    def _transition_explanations(self, explanation: Mapping[str, Any] | None) -> Dict[str, Dict[str, Any]]:
        if not explanation:
            return {}
        return {
            str(item.get("transitionRef")): dict(item)
            for item in explanation.get("transitions", [])
            if isinstance(item, dict) and isinstance(item.get("transitionRef"), str)
        }

    def _boundary_explanations(self, explanation: Mapping[str, Any] | None) -> Dict[str, Dict[str, Any]]:
        if not explanation:
            return {}
        return {
            str(item.get("boundaryRef")): dict(item)
            for item in explanation.get("boundaries", [])
            if isinstance(item, dict) and isinstance(item.get("boundaryRef"), str)
        }

    def _public_narrative(self, explanation: Mapping[str, Any] | None) -> List[FlowExplanationNarrative]:
        if not explanation:
            return []
        return [
            FlowExplanationNarrative(
                text=str(item.get("text") or ""),
                nodeRefs=self._public_string_list(item.get("nodeRefs")),
                transitionRefs=self._public_string_list(item.get("transitionRefs")),
                boundaryRefs=self._public_string_list(item.get("boundaryRefs")),
            )
            for item in explanation.get("narrative", [])
            if isinstance(item, dict)
        ]

    def _public_string_list(self, value: Any) -> List[str]:
        if not isinstance(value, list):
            return []
        return [str(item) for item in value if isinstance(item, str) and item]

    def _ui_evidence_refs(self, result: PerFlowExplanationResult, refs: Sequence[str]) -> List[str]:
        public_refs: List[str] = []
        for ref in refs:
            public_ref = str(ref)
            if public_ref in result.context.evidence_by_ref and public_ref not in public_refs:
                public_refs.append(public_ref)
        return public_refs

    def _diagnostic(
        self,
        code: str,
        message: str,
        flow_index: int,
        *,
        severity: str,
        metadata: Mapping[str, Any] | None = None,
    ) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code=code,
            message=message,
            severity=severity,
            metadata={"flowIndex": flow_index, **dict(metadata or {})},
        )

    def _compact_diagnostic(self, diagnostic: KnowledgeQueryDiagnostic) -> KnowledgeQueryDiagnostic:
        metadata = {}
        flow_index = diagnostic.metadata.get("flowIndex") if isinstance(diagnostic.metadata, dict) else None
        if flow_index is not None:
            metadata["flowIndex"] = flow_index
        retried = diagnostic.metadata.get("retried") if isinstance(diagnostic.metadata, dict) else None
        if retried is not None:
            metadata["retried"] = retried
        return KnowledgeQueryDiagnostic(
            code=diagnostic.code,
            message=diagnostic.message,
            severity=diagnostic.severity,
            sourceId=diagnostic.sourceId,
            metadata=metadata,
        )
