from __future__ import annotations

import json
import re
import urllib.parse
from collections import Counter
from dataclasses import dataclass, field
from typing import Any, Dict, List, Mapping, Optional, Sequence

import httpx

from knowledge_service.flow_builder import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowUnit
from knowledge_service.knowledge_query_schema import (
    FlowExplanation,
    FlowExplanationBoundary,
    FlowExplanationStep,
    FlowToolAddress,
    FlowToolBoundary,
    FlowToolContext,
    FlowToolEvidence,
    FlowToolStep,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryFlowExplanationResponse,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryToolContextResponse,
)


FLOW_EXPLANATION_LLM_FAILED = "FLOW_EXPLANATION_LLM_FAILED"
FLOW_EXPLANATION_SCHEMA_INVALID = "FLOW_EXPLANATION_SCHEMA_INVALID"
FLOW_EXPLANATION_VALIDATION_FAILED = "FLOW_EXPLANATION_VALIDATION_FAILED"
FLOW_EXPLANATION_LIMIT_REACHED = "FLOW_EXPLANATION_LIMIT_REACHED"
FLOW_EXPLANATION_SKIPPED_NO_FLOW = "FLOW_EXPLANATION_SKIPPED_NO_FLOW"

_DOTTED_SYMBOL_RE = re.compile(r"\b[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\b")
_CALL_WORD_RE = re.compile(r"\b(?:calls?|invokes?|delegates?\s+to|forwards?\s+to)\b", re.IGNORECASE)


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
    flow_unit: FlowUnit
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
            "Return strict JSON only. Do not use Markdown. Do not invent calls, symbols, classes, methods, side effects, or boundaries.\n"
            "The response shape is: {\"title\":\"string\",\"narrative\":[\"string\"],"
            "\"steps\":[{\"order\":1,\"explanation\":\"string\",\"evidenceRefs\":[\"e1\"]}],"
            "\"boundaries\":[{\"kind\":\"EXTERNAL_BOUNDARY\",\"explanation\":\"string\",\"evidenceRefs\":[\"e3\"]}]}.\n"
            "The steps array must cover every input step order. Boundary explanations are required when input boundaries exist.\n"
            "Use the requested answerLanguage. Shared nodes are repeated here because this is a self-contained flow.\n"
            f"{validation_block}"
            "BEGIN_FLOW_CONTEXT_JSON\n"
            f"{context_json}\n"
            "END_FLOW_CONTEXT_JSON\n"
        )


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
        self.context_tokens = max(1024, int(context_tokens or 4096))
        self.renderer = renderer or FlowExplanationPromptRenderer()
        self._client = http_client or httpx.Client(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))

    def complete(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> FlowExplanationProviderResult:
        prompt = self.renderer.render(llm_input, validation_errors)
        response = self._client.post(
            f"{self.base_url}/api/generate",
            json={
                "model": self.model,
                "prompt": prompt,
                "stream": False,
                "format": "json",
                "options": {"num_ctx": self.context_tokens},
            },
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


class FlowExplanationContextPacker:
    def pack(
        self,
        *,
        request: KnowledgeQueryRequest,
        flow_unit: FlowUnit,
        flow_index: int,
        source_display_name: str | None,
    ) -> PackedFlowContext:
        evidence_by_ref: Dict[str, FlowGraphEvidence] = {}
        evidence_ref_by_id: Dict[str, str] = {}
        for index, evidence in enumerate(flow_unit.evidence, start=1):
            ref = f"e{index}"
            evidence_by_ref[ref] = evidence
            evidence_ref_by_id[evidence.evidence_id] = ref

        nodes = list(flow_unit.nodes)
        edges_by_from_to = self._edges_by_from_to(flow_unit.edges)
        steps: List[Dict[str, Any]] = []
        for order, node in enumerate(nodes, start=1):
            next_node = nodes[order] if order < len(nodes) else None
            call_edge = edges_by_from_to.get((node.node_id, next_node.node_id)) if next_node else None
            step_refs = self._step_refs(node, call_edge, flow_unit.evidence, evidence_ref_by_id)
            step: Dict[str, Any] = {
                "order": order,
                "symbol": self._symbol(node),
                "nodeLabel": node.label,
                "qualifiedName": node.qualified_name,
                "kind": node.node_kind,
                "source": source_display_name,
                "relativePath": node.relative_path,
                "summary": node.summary,
                "evidence": [self._evidence_item(ref, evidence_by_ref[ref]) for ref in step_refs],
            }
            if next_node and call_edge:
                call_refs = self._edge_refs(call_edge, flow_unit.evidence, evidence_ref_by_id)
                step["callToNext"] = {
                    "order": order + 1,
                    "symbol": self._symbol(next_node),
                    "evidenceRefs": call_refs,
                    "evidence": [self._evidence_item(ref, evidence_by_ref[ref]) for ref in call_refs],
                }
            steps.append(step)

        boundaries = [
            self._boundary_item(edge, flow_unit.evidence, evidence_ref_by_id, evidence_by_ref)
            for edge in flow_unit.boundary_edges
        ]
        llm_input: Dict[str, Any] = {
            "queryText": request.queryText,
            "answerLanguage": request.answerLanguage,
            "flowIndex": flow_index,
            "source": source_display_name,
            "steps": steps,
            "boundaries": boundaries,
        }
        return PackedFlowContext(flow_index=flow_index, llm_input=llm_input, evidence_by_ref=evidence_by_ref)

    def _edges_by_from_to(self, edges: Sequence[FlowGraphEdge]) -> Dict[tuple[str, str], FlowGraphEdge]:
        result: Dict[tuple[str, str], FlowGraphEdge] = {}
        for edge in edges:
            if edge.to_node_id:
                result[(edge.from_node_id, edge.to_node_id)] = edge
        return result

    def _step_refs(
        self,
        node: FlowGraphNode,
        call_edge: FlowGraphEdge | None,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_id: Mapping[str, str],
    ) -> List[str]:
        refs: List[str] = []
        for item in evidence:
            if item.node_id == node.node_id:
                self._append_ref(refs, evidence_ref_by_id.get(item.evidence_id))
        if call_edge is not None:
            for ref in self._edge_refs(call_edge, evidence, evidence_ref_by_id):
                self._append_ref(refs, ref)
        return refs

    def _edge_refs(
        self,
        edge: FlowGraphEdge,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_id: Mapping[str, str],
    ) -> List[str]:
        refs: List[str] = []
        for evidence_id in edge.evidence_ids:
            self._append_ref(refs, evidence_ref_by_id.get(evidence_id))
        for item in evidence:
            if item.edge_id == edge.edge_id:
                self._append_ref(refs, evidence_ref_by_id.get(item.evidence_id))
        return refs

    def _boundary_item(
        self,
        edge: FlowGraphEdge,
        evidence: Sequence[FlowGraphEvidence],
        evidence_ref_by_id: Mapping[str, str],
        evidence_by_ref: Mapping[str, FlowGraphEvidence],
    ) -> Dict[str, Any]:
        refs = self._edge_refs(edge, evidence, evidence_ref_by_id)
        return {
            "kind": self._boundary_kind(edge),
            "target": self._boundary_target(edge),
            "evidence": [self._evidence_item(ref, evidence_by_ref[ref]) for ref in refs],
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

    def _boundary_kind(self, edge: FlowGraphEdge) -> str:
        if edge.external or str(edge.resolution_status or "").upper() == "EXTERNAL_TARGET":
            return "EXTERNAL_BOUNDARY"
        return "UNRESOLVED_BOUNDARY"

    def _boundary_target(self, edge: FlowGraphEdge) -> str | None:
        target = edge.unresolved_target or {}
        for key in ("name", "qualifiedName", "target", "kindHint"):
            value = target.get(key) if isinstance(target, dict) else None
            if value:
                return str(value)
        if isinstance(target, dict) and target:
            return json.dumps(target, ensure_ascii=False, sort_keys=True)
        return None


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
        if not isinstance(narrative, list) or not narrative or not all(isinstance(item, str) and item.strip() for item in narrative):
            errors.append("narrative must be a non-empty list of non-empty strings")

        steps: List[Dict[str, Any]] = []
        raw_steps = parsed.get("steps")
        if not isinstance(raw_steps, list):
            errors.append("steps must be a list")
        else:
            for index, item in enumerate(raw_steps, start=1):
                if not isinstance(item, dict):
                    errors.append(f"steps[{index}] must be an object")
                    continue
                order = item.get("order")
                if isinstance(order, bool) or not isinstance(order, int):
                    errors.append(f"steps[{index}].order must be an integer")
                    continue
                explanation = item.get("explanation")
                if not isinstance(explanation, str) or not explanation.strip():
                    errors.append(f"steps[{index}].explanation must be a non-empty string")
                steps.append(
                    {
                        "order": order,
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
                kind = item.get("kind")
                explanation = item.get("explanation")
                if not isinstance(kind, str) or not kind.strip():
                    errors.append(f"boundaries[{index}].kind must be a non-empty string")
                if not isinstance(explanation, str) or not explanation.strip():
                    errors.append(f"boundaries[{index}].explanation must be a non-empty string")
                boundaries.append(
                    {
                        "kind": str(kind or ""),
                        "explanation": str(explanation or ""),
                        "evidenceRefs": self._string_list(item.get("evidenceRefs")),
                    }
                )
        if errors:
            return {}, errors
        return {"title": title.strip(), "narrative": [item.strip() for item in narrative], "steps": steps, "boundaries": boundaries}, []

    def _validate_grounding(self, explanation: Mapping[str, Any], context: PackedFlowContext) -> List[str]:
        errors: List[str] = []
        input_steps = context.llm_input.get("steps") if isinstance(context.llm_input.get("steps"), list) else []
        input_orders = {int(step["order"]) for step in input_steps if isinstance(step, dict) and isinstance(step.get("order"), int)}
        returned_orders = [int(step["order"]) for step in explanation.get("steps", [])]
        returned_order_set = set(returned_orders)
        outside_orders = sorted(returned_order_set - input_orders)
        if outside_orders:
            errors.append(f"step orders are outside the input flow: {outside_orders}")
        missing_orders = sorted(input_orders - returned_order_set)
        if missing_orders:
            errors.append(f"steps must cover every input flow step; missing {missing_orders}")
        duplicate_orders = sorted(order for order, count in Counter(returned_orders).items() if count > 1)
        if duplicate_orders:
            errors.append(f"step orders must be unique; duplicates {duplicate_orders}")

        input_boundary_kinds = [str(item.get("kind") or "") for item in context.llm_input.get("boundaries", []) if isinstance(item, dict)]
        output_boundary_kinds = [str(item.get("kind") or "") for item in explanation.get("boundaries", []) if isinstance(item, dict)]
        input_boundary_counts = Counter(kind for kind in input_boundary_kinds if kind)
        output_boundary_counts = Counter(kind for kind in output_boundary_kinds if kind)
        for kind, count in input_boundary_counts.items():
            if output_boundary_counts[kind] < count:
                errors.append(f"boundary kind {kind} must be represented")
        for kind in output_boundary_counts:
            if kind not in input_boundary_counts:
                errors.append(f"boundary kind {kind} is not present in the input flow")

        for ref in self._output_evidence_refs(explanation):
            if ref not in context.evidence_refs:
                errors.append(f"evidence ref {ref} is not present in the packed flow context")

        text = self._explanation_text(explanation)
        allowed_aliases_by_order = self._allowed_aliases_by_order(input_steps)
        allowed_symbols = {alias for aliases in allowed_aliases_by_order.values() for alias in aliases}
        for symbol in sorted(_DOTTED_SYMBOL_RE.findall(text)):
            if symbol not in allowed_symbols:
                errors.append(f"symbol {symbol} is not present in the input flow context")

        errors.extend(self._call_errors(text, allowed_aliases_by_order))
        return errors

    def _string_list(self, value: Any) -> List[str]:
        if not isinstance(value, list):
            return []
        return [str(item) for item in value if isinstance(item, str) and item]

    def _output_evidence_refs(self, explanation: Mapping[str, Any]) -> List[str]:
        refs: List[str] = []
        for section in ("steps", "boundaries"):
            items = explanation.get(section)
            if not isinstance(items, list):
                continue
            for item in items:
                if isinstance(item, dict):
                    refs.extend(str(ref) for ref in item.get("evidenceRefs", []) if isinstance(ref, str))
        return refs

    def _explanation_text(self, explanation: Mapping[str, Any]) -> str:
        parts: List[str] = [str(explanation.get("title") or "")]
        parts.extend(str(item) for item in explanation.get("narrative", []) if isinstance(item, str))
        for section in ("steps", "boundaries"):
            items = explanation.get(section)
            if isinstance(items, list):
                parts.extend(str(item.get("explanation") or "") for item in items if isinstance(item, dict))
        return "\n".join(parts)

    def _allowed_aliases_by_order(self, input_steps: Sequence[Any]) -> Dict[int, set[str]]:
        result: Dict[int, set[str]] = {}
        for item in input_steps:
            if not isinstance(item, dict) or not isinstance(item.get("order"), int):
                continue
            aliases: set[str] = set()
            for key in ("symbol", "nodeLabel", "qualifiedName"):
                value = item.get(key)
                if isinstance(value, str) and value:
                    aliases.add(value)
                    aliases.update(self._suffix_aliases(value))
            result[int(item["order"])] = aliases
        return result

    def _suffix_aliases(self, value: str) -> set[str]:
        parts = [part for part in value.split(".") if part]
        aliases: set[str] = set()
        for index in range(0, max(0, len(parts) - 1)):
            alias = ".".join(parts[index:])
            if "." in alias:
                aliases.add(alias)
        return aliases

    def _call_errors(self, text: str, aliases_by_order: Mapping[int, set[str]]) -> List[str]:
        errors: List[str] = []
        alias_to_order = {alias: order for order, aliases in aliases_by_order.items() for alias in aliases}
        ordered_aliases = sorted(alias_to_order, key=len, reverse=True)
        allowed_pairs = {(order, order + 1) for order in aliases_by_order if order + 1 in aliases_by_order}
        segments = [segment for segment in re.split(r"[\n.;]+", text) if _CALL_WORD_RE.search(segment)]
        for segment in segments:
            for source in ordered_aliases:
                for target in ordered_aliases:
                    if source == target:
                        continue
                    pattern = re.compile(
                        rf"{re.escape(source)}.{{0,80}}{_CALL_WORD_RE.pattern}.{{0,80}}{re.escape(target)}",
                        re.IGNORECASE,
                    )
                    if not pattern.search(segment):
                        continue
                    pair = (alias_to_order[source], alias_to_order[target])
                    if pair not in allowed_pairs:
                        errors.append(f"described call {source} -> {target} is not an ordered CALLS edge in this flow")
        return sorted(set(errors))


class FlowExplanationService:
    def __init__(
        self,
        provider: Any,
        *,
        max_prompt_chars: int = 32768,
        packer: FlowExplanationContextPacker | None = None,
        validator: FlowExplanationValidator | None = None,
        renderer: FlowExplanationPromptRenderer | None = None,
    ) -> None:
        self.provider = provider
        self.max_prompt_chars = max(4096, int(max_prompt_chars or 32768))
        self.packer = packer or FlowExplanationContextPacker()
        self.validator = validator or FlowExplanationValidator()
        self.renderer = renderer or FlowExplanationPromptRenderer()

    def explain(self, request: KnowledgeQueryRequest, execution: Any) -> FlowExplanationRun:
        query_response = execution.response
        flow_units: tuple[FlowUnit, ...] = tuple(execution.flow_units or ())
        source_names = {source.sourceId: source.displayName for source in query_response.matchedSources}
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        results: List[PerFlowExplanationResult] = []
        if not flow_units:
            diagnostic = KnowledgeQueryDiagnostic(
                code=FLOW_EXPLANATION_SKIPPED_NO_FLOW,
                message="No FlowUnits were available for per-flow explanation.",
                severity="INFO",
            )
            return FlowExplanationRun(query_response=query_response, results=[], diagnostics=[diagnostic])

        for flow_index, flow_unit in enumerate(flow_units, start=1):
            source_display_name = source_names.get(flow_unit.key.source_id) or flow_unit.key.source_id or None
            packed = self.packer.pack(
                request=request,
                flow_unit=flow_unit,
                flow_index=flow_index,
                source_display_name=source_display_name,
            )
            prompt_len = len(self.renderer.render(packed.llm_input))
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
                        flow_unit=flow_unit,
                        context=packed,
                        explanation=None,
                        diagnostics=[diagnostic],
                        attempt=FlowExplanationAttempt(prompt_char_length=prompt_len),
                    )
                )
                continue
            result = self._explain_one(flow_unit, packed)
            diagnostics.extend(result.diagnostics)
            results.append(result)
        return FlowExplanationRun(query_response=query_response, results=results, diagnostics=diagnostics)

    def to_ui_response(self, run: FlowExplanationRun) -> KnowledgeQueryFlowExplanationResponse:
        base = run.query_response.dict()
        base["flowExplanations"] = [self._ui_explanation(result) for result in run.results]
        base["diagnostics"] = [*base.get("diagnostics", []), *[diagnostic.dict() for diagnostic in run.diagnostics]]
        return KnowledgeQueryFlowExplanationResponse(**base)

    def to_tool_response(self, request: KnowledgeQueryRequest, run: FlowExplanationRun) -> KnowledgeQueryToolContextResponse:
        compact_diagnostics = [self._compact_diagnostic(diagnostic) for diagnostic in run.diagnostics]
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            answerLanguage=request.answerLanguage,
            status=run.query_response.status,
            flows=[self._tool_flow(result) for result in run.results],
            diagnostics=compact_diagnostics,
        )

    def _explain_one(self, flow_unit: FlowUnit, context: PackedFlowContext) -> PerFlowExplanationResult:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        try:
            first = self.provider.complete(context.llm_input)
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
                flow_unit=flow_unit,
                context=context,
                explanation=None,
                diagnostics=[diagnostic],
            )
        explanation, errors, code = self.validator.validate(first.raw_text, context)
        if explanation is not None:
            return PerFlowExplanationResult(
                flow_index=context.flow_index,
                flow_unit=flow_unit,
                context=context,
                explanation=explanation,
                diagnostics=[],
                attempt=FlowExplanationAttempt(prompt_char_length=first.prompt_char_length),
            )

        try:
            second = self.provider.complete(context.llm_input, errors)
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
                flow_unit=flow_unit,
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
                flow_unit=flow_unit,
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
            flow_unit=flow_unit,
            context=context,
            explanation=None,
            diagnostics=diagnostics,
            attempt=FlowExplanationAttempt(prompt_char_length=second.prompt_char_length, retried=True),
        )

    def _ui_explanation(self, result: PerFlowExplanationResult) -> FlowExplanation:
        steps_by_order = self._step_explanations(result.explanation)
        boundaries = self._boundary_explanations(result.explanation)
        input_steps = result.context.llm_input.get("steps") or []
        return FlowExplanation(
            flowIndex=result.flow_index,
            title=str(result.explanation.get("title") if result.explanation else ""),
            narrative=list(result.explanation.get("narrative") if result.explanation else []),
            steps=[
                FlowExplanationStep(
                    order=int(step["order"]),
                    nodeLabel=str(step.get("nodeLabel") or step.get("symbol") or ""),
                    explanation=steps_by_order.get(int(step["order"]), {}).get("explanation"),
                    evidenceRefs=steps_by_order.get(int(step["order"]), {}).get("evidenceRefs", []),
                )
                for step in input_steps
                if isinstance(step, dict) and isinstance(step.get("order"), int)
            ],
            boundaries=[
                FlowExplanationBoundary(
                    kind=str(item.get("kind") or ""),
                    explanation=str(item.get("explanation") or "") if result.explanation else None,
                    evidenceRefs=list(item.get("evidenceRefs") or []),
                )
                for item in boundaries
            ],
            status="OK" if result.ok else "FAILED",
        )

    def _tool_flow(self, result: PerFlowExplanationResult) -> FlowToolContext:
        steps_by_order = self._step_explanations(result.explanation)
        input_steps = [step for step in result.context.llm_input.get("steps", []) if isinstance(step, dict)]
        input_boundaries = [item for item in result.context.llm_input.get("boundaries", []) if isinstance(item, dict)]
        return FlowToolContext(
            flowIndex=result.flow_index,
            title=str(result.explanation.get("title") if result.explanation else ""),
            narrative=list(result.explanation.get("narrative") if result.explanation else []),
            steps=[
                self._tool_step(result, step, steps_by_order.get(int(step["order"]), {}))
                for step in input_steps
                if isinstance(step.get("order"), int)
            ],
            boundaries=[self._tool_boundary(result, item, index) for index, item in enumerate(input_boundaries)],
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
        address = self._address(step, evidence)
        return FlowToolStep(
            order=int(step["order"]),
            symbol=str(step.get("symbol") or step.get("nodeLabel") or ""),
            kind=str(step.get("kind") or ""),
            address=address,
            explanation=str(explanation_step.get("explanation") or "") if explanation_step else None,
            evidence=[item for item in evidence if item is not None],
        )

    def _tool_boundary(self, result: PerFlowExplanationResult, item: Mapping[str, Any], index: int) -> FlowToolBoundary:
        explanations = self._boundary_explanations(result.explanation)
        explanation_item = explanations[index] if index < len(explanations) else {}
        evidence_refs = list(explanation_item.get("evidenceRefs") or []) or [
            evidence["ref"] for evidence in item.get("evidence", []) if isinstance(evidence, dict)
        ]
        evidence = [self._tool_evidence(ref, result.context.evidence_by_ref.get(ref)) for ref in evidence_refs]
        return FlowToolBoundary(
            kind=str(item.get("kind") or ""),
            target=str(item.get("target")) if item.get("target") else None,
            explanation=str(explanation_item.get("explanation") or "") if explanation_item else None,
            evidence=[entry for entry in evidence if entry is not None],
        )

    def _address(self, step: Mapping[str, Any], evidence: Sequence[FlowToolEvidence | None]) -> FlowToolAddress:
        first_evidence = next((item for item in evidence if item is not None and item.relativePath), None)
        return FlowToolAddress(
            service=str(step.get("source")) if step.get("source") else None,
            relativePath=first_evidence.relativePath if first_evidence else self._node_relative_path(step),
            lineStart=first_evidence.lineStart if first_evidence else None,
            lineEnd=first_evidence.lineEnd if first_evidence else None,
        )

    def _node_relative_path(self, step: Mapping[str, Any]) -> str | None:
        value = step.get("relativePath")
        return str(value) if value else None

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

    def _step_explanations(self, explanation: Mapping[str, Any] | None) -> Dict[int, Dict[str, Any]]:
        if not explanation:
            return {}
        return {int(item["order"]): dict(item) for item in explanation.get("steps", []) if isinstance(item, dict) and isinstance(item.get("order"), int)}

    def _boundary_explanations(self, explanation: Mapping[str, Any] | None) -> List[Dict[str, Any]]:
        if not explanation:
            return []
        return [dict(item) for item in explanation.get("boundaries", []) if isinstance(item, dict)]

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
