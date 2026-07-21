from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.parse
from collections import deque
from dataclasses import dataclass
from typing import Any, Callable, Deque, Dict, List, Mapping, Sequence

import httpx

from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.config import (
    DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
)
from knowledge_service.entrypoint_kinds import tree_kind_for_entrypoint, trigger_kind_for_entrypoint
from knowledge_service.entrypoint_flow_engine import EntrypointFlow
from knowledge_service.flow_family import FlowFamily
from knowledge_service.flow_narrative import FlowGapVerificationStatus, FlowNarrativeGap, FlowNarrativePartKind, FlowNarrativePlan
from knowledge_service.flow_boundary_classifier import FlowBoundaryClassifier, FLOW_BOUNDARY_CLASSIFIER
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.knowledge_query_schema import (
    FlowToolEvidence,
    FlowToolFlow,
    FlowToolGap,
    FlowToolPart,
    FlowToolSupportingRelation,
    FlowToolTransition,
    FlowToolTrigger,
    FlowToolTree,
    FlowToolTreeItem,
    KnowledgeFlowAnswer,
    KnowledgeHumanQueryResponse,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
    KnowledgeQueryToolContextResponse,
)
from knowledge_service.query_interpretation import QueryRetrievalPlan


FLOW_EXPLANATION_LIMIT_REACHED = "FLOW_EXPLANATION_LIMIT_REACHED"
FLOW_FAMILY_SEGMENT_CONTEXT_BUDGET_EXCEEDED = "FLOW_FAMILY_SEGMENT_CONTEXT_BUDGET_EXCEEDED"
DEFAULT_HUMAN_ANSWER_RESERVED_OUTPUT_TOKENS = 2048

_DEFAULT_MIN_CALL_TIMEOUT_SECONDS = 0.01
_DEADLINE_COMPLETION_GRACE_SECONDS = 0.005


@dataclass(frozen=True)
class FlowExplanationProviderResult:
    raw_text: str
    prompt_char_length: int


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


@dataclass(frozen=True)
class PromptBudgetEstimate:
    rendered_input_tokens: int
    context_tokens: int
    reserved_output_tokens: int
    fixed_framing_reserve_tokens: int

    @property
    def total_required_tokens(self) -> int:
        return (
            self.rendered_input_tokens
            + self.reserved_output_tokens
            + self.fixed_framing_reserve_tokens
        )

    @property
    def fits(self) -> bool:
        return self.total_required_tokens <= self.context_tokens


@dataclass(frozen=True)
class FlowNarrationSegment:
    llm_input: Mapping[str, Any]
    index: int
    total: int
    terminal: bool


@dataclass(frozen=True)
class FlowNarrationUnit:
    kind: str
    fact: Mapping[str, Any]
    serialized: str
    cut_preference: int

    @property
    def ref(self) -> str:
        return str(self.fact.get("ref") or "")


@dataclass(frozen=True)
class FlowNarrationSegmentPlan:
    units: tuple[FlowNarrationUnit, ...]


class FlowNarrationPlanner:
    def plan(self, full_input: Mapping[str, Any]) -> FlowNarrationSegmentPlan:
        units: list[FlowNarrationUnit] = []
        for fact in full_input.get("orderedFacts", []) or []:
            if not isinstance(fact, dict):
                continue
            kind = self._unit_kind(fact)
            units.append(
                FlowNarrationUnit(
                    kind=kind,
                    fact=dict(fact),
                    serialized=json.dumps(dict(fact), ensure_ascii=False, separators=(",", ":"), sort_keys=True),
                    cut_preference=self._cut_preference(fact),
                )
            )
        return FlowNarrationSegmentPlan(units=tuple(units))

    def _unit_kind(self, fact: Mapping[str, Any]) -> str:
        fact_type = str(fact.get("type") or "").upper()
        if fact_type == "TRANSITION" and isinstance(fact.get("connector"), dict):
            return "TRANSPORT_CONNECTOR"
        if fact_type == "TRANSITION":
            return "EXECUTION_TRANSITION"
        if fact_type == "BOUNDARY":
            return "BOUNDARY"
        if fact_type == "SUPPORTING":
            return "SUPPORTING_RELATION"
        return "NODE"

    def _cut_preference(self, fact: Mapping[str, Any]) -> int:
        if fact.get("type") == "boundary":
            return 80
        if fact.get("type") == "transition" and isinstance(fact.get("connector"), dict):
            return 75
        if fact.get("type") == "transition" and bool(fact.get("crossSource")):
            return 70
        if fact.get("type") == "supporting":
            return 40
        return 50


class PromptBudgetEstimator:
    """Fail-closed prompt budget check for rendered human-answer prompts.

    No model tokenizer is bundled for the local Ollama models in this service. When
    a suitable tokenizer is not injected, the fallback counts one token per UTF-8
    byte. The authoritative total adds only the reserved output budget and an
    optional fixed model-framing reserve because prose, JSON, and repair errors
    are counted in the exact rendered prompt being checked.
    """

    def __init__(
        self,
        *,
        context_tokens: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS,
        reserved_output_tokens: int = DEFAULT_HUMAN_ANSWER_RESERVED_OUTPUT_TOKENS,
        fixed_framing_reserve_tokens: int = 0,
        tokenizer: Callable[[str], int] | None = None,
    ) -> None:
        self.context_tokens = max(1, int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS))
        self.reserved_output_tokens = max(0, int(reserved_output_tokens))
        self.fixed_framing_reserve_tokens = max(0, int(fixed_framing_reserve_tokens))
        self.tokenizer = tokenizer

    def estimate(self, rendered_prompt: str) -> PromptBudgetEstimate:
        return PromptBudgetEstimate(
            rendered_input_tokens=self._rendered_input_tokens(rendered_prompt),
            context_tokens=self.context_tokens,
            reserved_output_tokens=self.reserved_output_tokens,
            fixed_framing_reserve_tokens=self.fixed_framing_reserve_tokens,
        )

    def estimate_text_tokens(self, text: str) -> int:
        return self._rendered_input_tokens(text)

    def ensure_fits(self, rendered_prompt: str) -> PromptBudgetEstimate:
        estimate = self.estimate(rendered_prompt)
        if not estimate.fits:
            raise HumanAnswerContextBudgetExceeded("The complete grounded flow exceeds the available model context.")
        return estimate

    def _rendered_input_tokens(self, rendered_prompt: str) -> int:
        if self.tokenizer is not None:
            return max(0, int(self.tokenizer(rendered_prompt)))
        return len(str(rendered_prompt or "").encode("utf-8"))


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
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        return (
            "Answer as a concise technical walkthrough for exactly one supplied segment of one assembled execution family.\n"
            "Return strict JSON only with exactly this shape: "
            "{\"steps\":[{\"unitRefs\":[\"n1\"],\"certainty\":\"VERIFIED\",\"text\":\"human-readable grounded step\"}],"
            "\"result\":null} for non-terminal segments, or "
            "{\"steps\":[{\"unitRefs\":[\"n1\"],\"certainty\":\"VERIFIED\",\"text\":\"human-readable grounded step\"}],"
            "\"result\":\"human-readable observable result\"} for the terminal segment when the supplied facts provide one. Terminal result may be null when no grounded final result is supplied.\n"
            "Write all natural-language prose in the supplied responseLanguage. "
            "Preserve code identifiers, routes, constants, topics, and quoted code exactly as supplied.\n"
            "Directly answer the question using only the supplied verified flow facts.\n"
            "Use only this segment's orderedFacts; do not refer to previous or future segment prose.\n"
            "If segment.terminal is false, result must be null and must not claim family completion.\n"
            "incomingContext and outgoingContext are continuity descriptors only; do not cover or copy them as refs.\n"
            "orderedFacts and coverageContract are the authoritative order and grounding contract.\n"
            "Every unitRefs value must exist in coverageContract.canonicalUnitRefs. Cover every required node, transition, and gap exactly once in canonical order.\n"
            "Supporting relation and low-level boundary refs are optional context; include those refs only when the step text uses that supplied fact.\n"
            "Prefer copying suggestedStepPlan unitRefs and certainty, writing only the step text.\n"
            "Use as many concise steps as needed. Each step text must explain only the facts named by that step's unitRefs.\n"
            "Use certainty VERIFIED only for verified units. For an unverified or ambiguous gap unit, use certainty UNVERIFIED or AMBIGUOUS and explicitly describe that the current graph does not verify a direct causal transition.\n"
            "Never combine a gap unit with verified execution units in the same step.\n"
            "Never copy internal kind labels, refs, ids, Markdown, backticks, or raw JSON into step text.\n"
            "Start with the supplied trigger and entrypoint. Explain branches as branches; do not sequence sibling branches.\n"
            "Explain what data arrives, what the code does, what it calls next, and grounded validation, persistence, or side effects when supplied.\n"
            "When validation facts include thresholds, null or empty checks, exception classes, or error messages, include the exact grounded detail.\n"
            "Only the terminal segment may include an observable result when supplied.\n"
            "Do not collapse the flow into a generic summary or mechanically repeat every graph field.\n"
            "Do not omit available method names, class names, trigger details, validation rules, persistence details, side effects, or final results.\n"
            "Do not invent validation, side effects, transports, routes, statuses, or ordering unsupported by the supplied facts.\n"
            "Do not infer default framework behavior or speculate beyond the supplied facts.\n"
            "Do not mention retrieval mechanics, refs, internal graph ids, or internal scores.\n"
            f"{validation_block}"
            "BEGIN_VERIFIED_FLOW_FACTS_JSON\n"
            f"{context_json}\n"
            "END_VERIFIED_FLOW_FACTS_JSON\n"
        )


class FlowProjectionBuilder:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def to_tool_response(self, request: KnowledgeQueryRequest, execution: Any) -> KnowledgeQueryToolContextResponse:
        narrative_plans = tuple(getattr(execution, "narrative_plans", ()) or ())
        if not narrative_plans:
            return KnowledgeQueryToolContextResponse(
                queryText=request.queryText,
                flows=[
                    FlowToolFlow(
                        source=str(flow.key.source_id or ""),
                        entrypoint=self._symbol(flow.entrypoint),
                        parts=[FlowToolPart(kind=FlowNarrativePartKind.VERIFIED_FRAGMENT.value, tree=self._tree(flow))],
                        complete=bool(flow.complete),
                        diagnostics=list(flow.diagnostics),
                    )
                    for flow in tuple(getattr(execution, "flows", ()) or ())
                ],
                diagnostics=self._diagnostics(execution),
            )
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            flows=[self._tool_flow(plan) for plan in narrative_plans],
            diagnostics=self._diagnostics(execution),
        )

    def human_llm_input(self, request: KnowledgeQueryRequest, flow: EntrypointFlow | FlowFamily | FlowNarrativePlan, plan: QueryRetrievalPlan) -> Dict[str, Any]:
        if isinstance(flow, FlowNarrativePlan):
            return self._human_llm_input_for_plan(request, flow, plan)
        ordered_facts, coverage_contract = self._ordered_facts(flow)
        return {
            "originalQuestion": request.queryText,
            "detectedLanguage": plan.detected_language,
            "responseLanguage": plan.response_language,
            "intent": plan.effective_intent,
            "rootSource": flow.key.source_id,
            "entrypoint": self._symbol(flow.entrypoint),
            "orderedFacts": ordered_facts,
            "coverageContract": coverage_contract,
            "suggestedStepPlan": self._suggested_step_plan(ordered_facts),
        }

    def flow_answer_identity(self, flow: EntrypointFlow | FlowFamily | FlowNarrativePlan) -> tuple[str, str]:
        if isinstance(flow, FlowNarrativePlan):
            fragments = flow.fragments
            if not fragments:
                return "", ""
            return str(fragments[0].source_id or ""), self._symbol(fragments[0].root)
        return str(flow.key.source_id or ""), self._symbol(flow.entrypoint)

    def _tool_flow(self, plan: FlowNarrativePlan) -> FlowToolFlow:
        fragments = plan.fragments
        first = fragments[0] if fragments else None
        parts: list[FlowToolPart] = []
        for part in plan.parts:
            if part.kind is FlowNarrativePartKind.VERIFIED_FRAGMENT and part.fragment is not None:
                parts.append(FlowToolPart(kind=part.kind.value, tree=self._tree(part.fragment.family)))
            elif part.gap is not None:
                parts.append(FlowToolPart(kind=part.kind.value, gap=self._tool_gap(part.gap)))
        return FlowToolFlow(
            source=first.source_id if first is not None else None,
            entrypoint=self._symbol(first.root) if first is not None else None,
            parts=parts,
            complete=bool(plan.complete),
            diagnostics=list(plan.diagnostics),
        )

    def _tool_gap(self, gap: FlowNarrativeGap) -> FlowToolGap:
        return FlowToolGap(
            kind=gap.kind,
            verificationStatus=gap.verification_status.value,
            fromSource=gap.from_source,
            fromSymbol=gap.from_symbol,
            toSource=gap.to_source,
            toSymbol=gap.to_symbol,
            transportKind=gap.transport_kind,
            method=gap.method,
            route=gap.route,
            operationIdentity=gap.operation_identity,
            reason=gap.reason,
        )

    def _human_llm_input_for_plan(
        self,
        request: KnowledgeQueryRequest,
        narrative_plan: FlowNarrativePlan,
        plan: QueryRetrievalPlan,
    ) -> Dict[str, Any]:
        ordered_facts, coverage_contract = self._ordered_plan_facts(narrative_plan)
        source, entrypoint = self.flow_answer_identity(narrative_plan)
        return {
            "originalQuestion": request.queryText,
            "detectedLanguage": plan.detected_language,
            "responseLanguage": plan.response_language,
            "intent": plan.effective_intent,
            "rootSource": source,
            "entrypoint": entrypoint,
            "orderedFacts": ordered_facts,
            "coverageContract": coverage_contract,
            "suggestedStepPlan": self._suggested_step_plan(ordered_facts),
        }

    def _tree_item_dict(self, item: FlowToolTreeItem) -> Dict[str, Any]:
        data = item.dict(exclude_none=True)
        children = [
            self._tree_item_dict(child)
            for child in item.children
        ]
        data["children"] = children
        return data

    def _ordered_plan_facts(self, plan: FlowNarrativePlan) -> tuple[list[Dict[str, Any]], Dict[str, Any]]:
        facts: list[Dict[str, Any]] = []
        part_index = 0
        gap_index = 0
        for part in plan.parts:
            if part.kind is FlowNarrativePartKind.VERIFIED_FRAGMENT and part.fragment is not None:
                part_index += 1
                fragment_facts, _coverage = self._ordered_facts(part.fragment.family)
                facts.extend(self._remap_fact_refs(fragment_facts, f"p{part_index}_"))
                continue
            if part.gap is not None:
                gap_index += 1
                facts.append(self._gap_fact(f"g{gap_index}", part.gap))
        return facts, self._coverage_contract(facts)

    def _coverage_contract(self, facts: Sequence[Mapping[str, Any]]) -> Dict[str, Any]:
        canonical_refs = [str(fact["ref"]) for fact in facts if str(fact.get("ref") or "").strip()]
        required_refs = [
            str(fact["ref"])
            for fact in facts
            if fact.get("type") not in {"supporting", "boundary"} and str(fact.get("ref") or "").strip()
        ]
        return {
            "canonicalUnitRefs": canonical_refs,
            "canonicalFactRefs": canonical_refs,
            "requiredUnitRefs": required_refs,
            "nodeRefs": [fact["ref"] for fact in facts if fact.get("type") == "node"],
            "transitionRefs": [fact["ref"] for fact in facts if fact.get("type") == "transition"],
            "supportingRefs": [fact["ref"] for fact in facts if fact.get("type") == "supporting"],
            "boundaryRefs": [fact["ref"] for fact in facts if fact.get("type") == "boundary"],
            "gapRefs": [fact["ref"] for fact in facts if fact.get("type") == "gap"],
        }

    def _remap_fact_refs(self, facts: Sequence[Mapping[str, Any]], prefix: str) -> list[Dict[str, Any]]:
        ref_map = {
            str(fact.get("ref")): f"{prefix}{fact.get('ref')}"
            for fact in facts
            if str(fact.get("ref") or "").strip()
        }

        def remap_value(value: Any) -> Any:
            if isinstance(value, str):
                return ref_map.get(value, value)
            if isinstance(value, list):
                return [remap_value(item) for item in value]
            if isinstance(value, dict):
                return {key: remap_value(item) for key, item in value.items()}
            return value

        remapped: list[Dict[str, Any]] = []
        for fact in facts:
            item = {key: remap_value(value) for key, value in dict(fact).items()}
            if item.get("ref") in ref_map:
                item["ref"] = ref_map[str(item["ref"])]
            remapped.append(item)
        return remapped

    def _gap_fact(self, ref: str, gap: FlowNarrativeGap) -> Dict[str, Any]:
        return self._without_none(
            {
                "ref": ref,
                "type": "gap",
                "certainty": "AMBIGUOUS" if gap.verification_status is FlowGapVerificationStatus.AMBIGUOUS else "UNVERIFIED",
                "verificationStatus": gap.verification_status.value,
                "fromSource": gap.from_source,
                "fromSymbol": gap.from_symbol,
                "toSource": gap.to_source,
                "toSymbol": gap.to_symbol,
                "transportKind": gap.transport_kind,
                "method": gap.method,
                "route": gap.route,
                "operationIdentity": gap.operation_identity,
                "reason": gap.reason,
            }
        )

    def _tree(self, flow: EntrypointFlow | FlowFamily) -> FlowToolTree:
        node_by_key = {self._node_key(node): node for node in flow.nodes}
        evidence_by_node: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        evidence_by_edge: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        for item in flow.evidence:
            if item.edge_id:
                evidence_by_edge.setdefault((item.source_id, item.edge_id), []).append(item)
            elif item.node_id:
                evidence_by_node.setdefault((item.source_id, item.node_id), []).append(item)
        outgoing: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        supporting_edges = tuple(getattr(flow, "supporting_transitions", ()) or ())
        for edge in sorted(flow.transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            outgoing.setdefault(self._from_key(edge), []).append(edge)
        boundaries: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        for edge in sorted(flow.boundary_transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            boundaries.setdefault(self._from_key(edge), []).append(edge)

        root_key = self._node_key(flow.entrypoint)
        root_source = flow.entrypoint.source_id
        root = self._node_item(flow.entrypoint, evidence_by_node.get((flow.entrypoint.source_id, flow.entrypoint.node_id), []), root_source=root_source)
        rendered = {root_key}
        stack: List[Dict[str, Any]] = [
            {
                "node": flow.entrypoint,
                "node_key": root_key,
                "item": root,
                "entries": self._sorted_child_edges(root_key, outgoing, boundaries, evidence_by_edge),
                "index": 0,
                "ancestry": {root_key},
            }
        ]
        while stack:
            frame = stack[-1]
            if frame["index"] >= len(frame["entries"]):
                stack.pop()
                continue
            entry = frame["entries"][frame["index"]]
            frame["index"] += 1
            edge_key = self._edge_key(entry)
            if entry in boundaries.get(frame["node_key"], []):
                frame["item"].children.append(self._boundary_item(entry, evidence_by_edge.get(edge_key, [])))
                continue
            target_key = self._to_key(entry)
            target = node_by_key.get(target_key) if target_key is not None else None
            if target is None:
                frame["item"].children.append(self._boundary_item(replace_edge_boundary(entry), evidence_by_edge.get(edge_key, [])))
                continue
            child_evidence = [*evidence_by_node.get((target.source_id, target.node_id), []), *evidence_by_edge.get(edge_key, [])]
            if target_key in frame["ancestry"]:
                frame["item"].children.append(self._node_item(target, child_evidence, root_source=root_source, transition=entry, cycle=True))
                continue
            if target_key in rendered:
                frame["item"].children.append(self._node_item(target, child_evidence, root_source=root_source, transition=entry, shared=True))
                continue
            child = self._node_item(target, child_evidence, root_source=root_source, transition=entry)
            frame["item"].children.append(child)
            rendered.add(target_key)
            stack.append(
                {
                    "node": target,
                    "node_key": target_key,
                    "item": child,
                    "entries": self._sorted_child_edges(target_key, outgoing, boundaries, evidence_by_edge),
                    "index": 0,
                    "ancestry": {*frame["ancestry"], target_key},
                }
            )
        return FlowToolTree(
            source=str(flow.key.source_id or ""),
            entrypoint=root,
            supportingRelations=self._supporting_relation_items(supporting_edges, node_by_key, evidence_by_edge, root_source=root_source),
        )

    def _sorted_child_edges(
        self,
        node_key: tuple[str, str, str],
        outgoing: Mapping[tuple[str, str, str], Sequence[FlowGraphEdge]],
        boundaries: Mapping[tuple[str, str, str], Sequence[FlowGraphEdge]],
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]],
    ) -> List[FlowGraphEdge]:
        return sorted(
            [*outgoing.get(node_key, ()), *boundaries.get(node_key, ())],
            key=lambda item: self._edge_sort_key(item, evidence_by_edge),
        )

    def _supporting_relation_items(
        self,
        supporting_edges: Sequence[FlowGraphEdge],
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]],
        *,
        root_source: str,
    ) -> list[FlowToolSupportingRelation]:
        items: list[FlowToolSupportingRelation] = []
        for edge in sorted(supporting_edges, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            from_node = node_by_key.get(self._from_key(edge))
            to_key = self._to_key(edge)
            to_node = node_by_key.get(to_key) if to_key is not None else None
            symbol = self._symbol(to_node) if to_node is not None else (edge.to_node_id or edge.edge_id)
            source = from_node.source_id if from_node is not None else edge.source_id
            target_source = to_node.source_id if to_node is not None else (edge.to_source_id or edge.source_id)
            items.append(
                FlowToolSupportingRelation(
                    relation=edge.edge_type,
                    source=source if source != root_source else None,
                    targetSource=target_source if target_source != root_source or target_source != source else None,
                    symbol=symbol,
                    path=to_node.relative_path if to_node is not None else None,
                    lineStart=to_node.line_start if to_node is not None else None,
                    lineEnd=to_node.line_end if to_node is not None else None,
                    description=to_node.summary if to_node is not None else None,
                    evidence=[self._evidence(item) for item in evidence_by_edge.get(self._edge_key(edge), [])],
                )
            )
        return items

    def _ordered_facts(self, flow: EntrypointFlow) -> tuple[list[Dict[str, Any]], Dict[str, Any]]:
        node_by_key = {self._node_key(node): node for node in flow.nodes}
        evidence_by_node: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        evidence_by_edge: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        for item in flow.evidence:
            if item.edge_id:
                evidence_by_edge.setdefault((item.source_id, item.edge_id), []).append(item)
            elif item.node_id:
                evidence_by_node.setdefault((item.source_id, item.node_id), []).append(item)
        outgoing: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        supporting_edges = tuple(getattr(flow, "supporting_transitions", ()) or ())
        for edge in sorted(flow.transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            outgoing.setdefault(self._from_key(edge), []).append(edge)
        boundaries: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        for edge in sorted(flow.boundary_transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            boundaries.setdefault(self._from_key(edge), []).append(edge)

        root_key = self._node_key(flow.entrypoint)
        events: list[tuple[str, Any, Dict[str, Any]]] = [("node", root_key, {"incoming": None, "parent": None})]
        rendered = {root_key}
        stack: list[dict[str, Any]] = [
            {
                "node_key": root_key,
                "entries": self._sorted_child_edges(root_key, outgoing, boundaries, evidence_by_edge),
                "index": 0,
                "ancestry": {root_key},
            }
        ]
        while stack:
            frame = stack[-1]
            if frame["index"] >= len(frame["entries"]):
                stack.pop()
                continue
            edge = frame["entries"][frame["index"]]
            frame["index"] += 1
            edge_key = self._edge_key(edge)
            if edge in boundaries.get(frame["node_key"], ()):
                events.append(("boundary", edge_key, {"edge": edge, "parent": frame["node_key"]}))
                continue
            target_key = self._to_key(edge)
            target = node_by_key.get(target_key) if target_key is not None else None
            if target is None or target_key is None:
                events.append(("boundary", edge_key, {"edge": replace_edge_boundary(edge), "parent": frame["node_key"]}))
                continue
            events.append(("transition", edge_key, {"edge": edge, "parent": frame["node_key"], "target": target_key}))
            if target_key in frame["ancestry"] or target_key in rendered:
                continue
            rendered.add(target_key)
            events.append(("node", target_key, {"incoming": edge_key, "parent": frame["node_key"]}))
            stack.append(
                {
                    "node_key": target_key,
                    "entries": self._sorted_child_edges(target_key, outgoing, boundaries, evidence_by_edge),
                    "index": 0,
                    "ancestry": {*frame["ancestry"], target_key},
                }
            )
        for edge in sorted(supporting_edges, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            events.append(("supporting", self._edge_key(edge), {"edge": edge}))

        node_ref_by_key: Dict[tuple[str, str, str], str] = {}
        transition_ref_by_key: Dict[tuple[str, str], str] = {}
        supporting_ref_by_key: Dict[tuple[str, str], str] = {}
        boundary_ref_by_key: Dict[tuple[str, str], str] = {}
        node_count = transition_count = supporting_count = boundary_count = 0
        for event_type, key, _metadata in events:
            if event_type == "node" and key not in node_ref_by_key:
                node_count += 1
                node_ref_by_key[key] = f"n{node_count}"
            elif event_type == "transition" and key not in transition_ref_by_key:
                transition_count += 1
                transition_ref_by_key[key] = f"t{transition_count}"
            elif event_type == "supporting" and key not in supporting_ref_by_key:
                supporting_count += 1
                supporting_ref_by_key[key] = f"s{supporting_count}"
            elif event_type == "boundary" and key not in boundary_ref_by_key:
                boundary_count += 1
                boundary_ref_by_key[key] = f"b{boundary_count}"

        outgoing_refs_by_node: Dict[tuple[str, str, str], List[str]] = {}
        for edge in flow.transitions:
            ref = transition_ref_by_key.get(self._edge_key(edge))
            if ref:
                outgoing_refs_by_node.setdefault(self._from_key(edge), []).append(ref)
        for edge in flow.boundary_transitions:
            ref = boundary_ref_by_key.get(self._edge_key(edge))
            if ref:
                outgoing_refs_by_node.setdefault(self._from_key(edge), []).append(ref)
        canonical_refs: list[str] = []
        facts: list[Dict[str, Any]] = []
        seen_fact_refs: set[str] = set()
        for event_type, key, metadata in events:
            if event_type == "node":
                node_item = node_by_key.get(key)
                if node_item is None:
                    continue
                ref = node_ref_by_key[key]
                if ref in seen_fact_refs:
                    continue
                incoming = metadata.get("incoming")
                parent = metadata.get("parent")
                fact = self._node_fact(
                    ref,
                    node_item,
                    evidence_by_node.get((node_item.source_id, node_item.node_id), []),
                    incomingTransition=transition_ref_by_key.get(incoming) if incoming else None,
                    branchParent=node_ref_by_key.get(parent) if parent else None,
                    outgoingTransitions=outgoing_refs_by_node.get(key, []),
                )
            elif event_type == "transition":
                edge = metadata["edge"]
                ref = transition_ref_by_key[key]
                if ref in seen_fact_refs:
                    continue
                fact = self._transition_fact(
                    ref,
                    edge,
                    node_by_key,
                    node_ref_by_key,
                    evidence_by_edge.get(self._edge_key(edge), []),
                )
            elif event_type == "supporting":
                edge = metadata["edge"]
                ref = supporting_ref_by_key[key]
                if ref in seen_fact_refs:
                    continue
                fact = self._supporting_fact(
                    ref,
                    edge,
                    node_by_key,
                    node_ref_by_key,
                    evidence_by_edge.get(self._edge_key(edge), []),
                )
            else:
                edge = metadata["edge"]
                ref = boundary_ref_by_key[key]
                if ref in seen_fact_refs:
                    continue
                fact = self._boundary_fact(
                    ref,
                    edge,
                    node_by_key,
                    node_ref_by_key,
                    evidence_by_edge.get(self._edge_key(edge), []),
                )
            facts.append(fact)
            seen_fact_refs.add(str(fact["ref"]))
            canonical_refs.append(str(fact["ref"]))
        return facts, self._coverage_contract(facts)

    def _suggested_step_plan(self, facts: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
        facts_by_ref = {str(fact.get("ref")): fact for fact in facts if str(fact.get("ref") or "").strip()}
        groups: list[Dict[str, Any]] = []
        current: Dict[str, Any] | None = None

        def current_node_refs(group: Mapping[str, Any] | None) -> set[str]:
            if not group:
                return set()
            return {
                ref
                for ref in group.get("unitRefs", [])
                if facts_by_ref.get(ref, {}).get("type") == "node"
            }

        for fact in facts:
            ref = str(fact.get("ref") or "").strip()
            fact_type = str(fact.get("type") or "").strip()
            if not ref or not fact_type:
                continue
            if fact_type == "node":
                if current is not None:
                    groups.append(current)
                current = {"unitRefs": [ref], "certainty": "VERIFIED"}
                continue
            if fact_type == "gap":
                if current is not None:
                    groups.append(current)
                groups.append({"unitRefs": [ref], "certainty": fact.get("certainty") or "UNVERIFIED"})
                current = None
                continue
            if fact_type in {"transition", "boundary", "supporting"}:
                owner_refs = {str(fact.get("fromRef") or "")}
                if fact_type in {"transition", "supporting"}:
                    owner_refs.add(str(fact.get("toRef") or ""))
                if current is not None and current_node_refs(current) and current_node_refs(current) & owner_refs:
                    current["unitRefs"].append(ref)
                else:
                    if current is not None:
                        groups.append(current)
                    current = {"unitRefs": [ref], "certainty": "VERIFIED"}
        if current is not None:
            groups.append(current)
        return [
            {"unitRefs": list(group.get("unitRefs") or []), "certainty": group.get("certainty") or "VERIFIED"}
            for group in groups
            if group.get("unitRefs")
        ]

    def _node_fact(
        self,
        ref: str,
        node: FlowGraphNode,
        evidence: Sequence[FlowGraphEvidence],
        *,
        incomingTransition: str | None,
        branchParent: str | None,
        outgoingTransitions: Sequence[str],
    ) -> Dict[str, Any]:
        fact: Dict[str, Any] = {
            "ref": ref,
            "type": "node",
            "source": node.source_id,
            "displaySymbol": self._symbol(node),
            "kind": self._node_kind(node),
            "path": node.relative_path,
            "lineStart": node.line_start,
            "lineEnd": node.line_end,
            "description": node.summary,
            "evidence": [self._evidence(item).dict(exclude_none=True) for item in evidence],
            "incomingTransition": incomingTransition,
            "outgoingTransitions": list(outgoingTransitions),
            "branchParent": branchParent,
        }
        trigger = self._trigger(node)
        if trigger is not None:
            fact["trigger"] = trigger.dict(exclude_none=True)
        return self._without_none(fact)

    def _transition_fact(
        self,
        ref: str,
        edge: FlowGraphEdge,
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        node_ref_by_key: Mapping[tuple[str, str, str], str],
        evidence: Sequence[FlowGraphEvidence],
    ) -> Dict[str, Any]:
        from_key = self._from_key(edge)
        to_key = self._to_key(edge)
        from_node = node_by_key.get(from_key)
        to_node = node_by_key.get(to_key) if to_key is not None else None
        from_source = from_node.source_id if from_node is not None else edge.source_id
        to_source = to_node.source_id if to_node is not None else (edge.to_source_id or edge.source_id)
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        connector = self._connector_metadata(metadata)
        return self._without_none({
            "ref": ref,
            "type": "transition",
            "edgeType": edge.edge_type,
            "resolutionStatus": edge.resolution_status,
            "fromSource": from_source,
            "toSource": to_source,
            "fromRef": node_ref_by_key.get(from_key),
            "toRef": node_ref_by_key.get(to_key) if to_key is not None else None,
            "fromSymbol": self._symbol(from_node) if from_node else edge.from_node_id,
            "toSymbol": self._symbol(to_node) if to_node else edge.to_node_id,
            "crossSource": True if from_source != to_source else None,
            "connector": connector,
            "evidence": [self._evidence(item).dict(exclude_none=True) for item in evidence],
        })

    def _supporting_fact(
        self,
        ref: str,
        edge: FlowGraphEdge,
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        node_ref_by_key: Mapping[tuple[str, str, str], str],
        evidence: Sequence[FlowGraphEvidence],
    ) -> Dict[str, Any]:
        from_key = self._from_key(edge)
        to_key = self._to_key(edge)
        from_node = node_by_key.get(from_key)
        to_node = node_by_key.get(to_key) if to_key is not None else None
        return self._without_none({
            "ref": ref,
            "type": "supporting",
            "edgeType": edge.edge_type,
            "resolutionStatus": edge.resolution_status,
            "fromSource": from_node.source_id if from_node is not None else edge.source_id,
            "toSource": to_node.source_id if to_node is not None else (edge.to_source_id or edge.source_id),
            "fromRef": node_ref_by_key.get(from_key),
            "toRef": node_ref_by_key.get(to_key) if to_key is not None else None,
            "fromSymbol": self._symbol(from_node) if from_node else edge.from_node_id,
            "toSymbol": self._symbol(to_node) if to_node else edge.to_node_id,
            "evidence": [self._evidence(item).dict(exclude_none=True) for item in evidence],
        })

    def _connector_metadata(self, metadata: Mapping[str, Any]) -> Dict[str, Any] | None:
        connector_kind = self._clean(metadata.get("connectorKind") if isinstance(metadata.get("connectorKind"), str) else None)
        http_method = self._clean(metadata.get("httpMethod") if isinstance(metadata.get("httpMethod"), str) else None)
        route = self._clean(metadata.get("routeTemplate") if isinstance(metadata.get("routeTemplate"), str) else None)
        target_interface = self._clean(metadata.get("targetInterfaceMethod") if isinstance(metadata.get("targetInterfaceMethod"), str) else None)
        connector = self._without_none({
            "kind": connector_kind,
            "method": http_method,
            "route": route,
            "interfaceMethod": target_interface,
        })
        return connector or None

    def _boundary_fact(
        self,
        ref: str,
        edge: FlowGraphEdge,
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        node_ref_by_key: Mapping[tuple[str, str, str], str],
        evidence: Sequence[FlowGraphEvidence],
    ) -> Dict[str, Any]:
        from_key = self._from_key(edge)
        from_node = node_by_key.get(from_key)
        projection = self.boundary_classifier.project(edge)
        symbol = self._boundary_symbol(edge, projection.target)
        return self._without_none({
            "ref": ref,
            "type": "boundary",
            "fromSource": from_node.source_id if from_node is not None else edge.source_id,
            "fromRef": node_ref_by_key.get(from_key),
            "fromSymbol": self._symbol(from_node) if from_node else edge.from_node_id,
            "edgeType": edge.edge_type,
            "resolutionStatus": projection.resolution_status,
            "boundaryKind": projection.kind.value,
            "boundaryReason": edge.boundary_reason,
            "target": projection.target or symbol,
            "displaySymbol": symbol,
            "evidence": [self._evidence(item).dict(exclude_none=True) for item in evidence],
        })

    def _node_item(
        self,
        node: FlowGraphNode,
        evidence: Sequence[FlowGraphEvidence],
        *,
        root_source: str | None = None,
        transition: FlowGraphEdge | None = None,
        cycle: bool = False,
        shared: bool = False,
    ) -> FlowToolTreeItem:
        return FlowToolTreeItem(
            source=node.source_id if root_source and node.source_id != root_source else None,
            symbol=self._symbol(node),
            kind=self._node_kind(node),
            trigger=self._trigger(node),
            transition=self._tool_transition(transition, node) if transition is not None else None,
            path=node.relative_path,
            lineStart=node.line_start,
            lineEnd=node.line_end,
            description=node.summary,
            evidence=[self._evidence(item) for item in evidence],
            children=[],
            cycle=True if cycle else None,
            shared=True if shared else None,
        )

    def _tool_transition(self, edge: FlowGraphEdge, target: FlowGraphNode | None = None) -> FlowToolTransition:
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        from_source = edge.source_id
        to_source = target.source_id if target is not None else (edge.to_source_id or edge.source_id)
        connector_kind = self._clean(metadata.get("connectorKind") if isinstance(metadata.get("connectorKind"), str) else None)
        http_method = self._clean(metadata.get("httpMethod") if isinstance(metadata.get("httpMethod"), str) else None)
        route = self._clean(metadata.get("routeTemplate") if isinstance(metadata.get("routeTemplate"), str) else None)
        return FlowToolTransition(
            edgeType=edge.edge_type,
            resolutionStatus=edge.resolution_status,
            crossSource=True if from_source != to_source else None,
            connectorKind=connector_kind,
            method=http_method,
            route=route,
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
            if node.node_kind == "CALLABLE":
                return parts[-1] if parts else qualified
            return qualified
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
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]] | None = None,
    ) -> tuple[str, int, int, str, str, str]:
        line_starts = [
            item.line_start
            for item in (evidence_by_edge or {}).get(self._edge_key(edge), ())
            if item.line_start is not None
        ]
        first_line = min(line_starts) if line_starts else 1_000_000_000
        return (edge.from_node_id, first_line, 0 if line_starts else 1, edge.to_node_id or "", edge.edge_id, edge.resolution_status)

    def _node_key(self, node: FlowGraphNode) -> tuple[str, str, str]:
        return (node.source_id, node.graph_revision or node.graph_id, node.node_id)

    def _edge_key(self, edge: FlowGraphEdge) -> tuple[str, str]:
        return (edge.source_id, edge.edge_id)

    def _from_key(self, edge: FlowGraphEdge) -> tuple[str, str, str]:
        return (edge.source_id, edge.graph_revision or edge.graph_id, edge.from_node_id)

    def _to_key(self, edge: FlowGraphEdge) -> tuple[str, str, str] | None:
        if not edge.to_node_id:
            return None
        return (
            edge.to_source_id or edge.source_id,
            edge.to_graph_revision or edge.to_graph_id or edge.graph_revision or edge.graph_id,
            edge.to_node_id,
        )

    def _clean(self, value: str | None) -> str | None:
        normalized = str(value or "").strip()
        return normalized or None

    def _without_none(self, value: Dict[str, Any]) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        for key, item in value.items():
            if item is None:
                continue
            if isinstance(item, dict):
                nested = self._without_none(item)
                if nested:
                    result[key] = nested
            elif isinstance(item, list) and not item:
                continue
            else:
                result[key] = item
        return result

    def _diagnostics(self, execution: Any) -> List[KnowledgeQueryDiagnostic]:
        response = getattr(execution, "response", None)
        diagnostics = list(getattr(response, "diagnostics", []) or [])
        for flow in tuple(getattr(execution, "flows", ()) or ()):
            diagnostics.extend(flow.diagnostics)
        for narrative_plan in tuple(getattr(execution, "narrative_plans", ()) or ()):
            diagnostics.extend(narrative_plan.diagnostics)
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
        context_tokens: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS,
        budget_estimator: PromptBudgetEstimator | None = None,
        reserved_output_tokens: int | None = None,
        request_deadline_seconds: float = DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        projector: FlowProjectionBuilder | None = None,
        renderer: HumanAnswerPromptRenderer | None = None,
        text_validator: HumanAnswerTextValidator | None = None,
        narration_planner: FlowNarrationPlanner | None = None,
        provider_name: str | None = None,
        provider_model: str | None = None,
        cancel_event: Any | None = None,
        audit_max_records: int = 200,
    ) -> None:
        self.provider = provider
        if budget_estimator is None:
            self.budget_estimator = PromptBudgetEstimator(
                context_tokens=context_tokens,
                reserved_output_tokens=(
                    DEFAULT_HUMAN_ANSWER_RESERVED_OUTPUT_TOKENS
                    if reserved_output_tokens is None
                    else reserved_output_tokens
                ),
            )
        else:
            self.budget_estimator = budget_estimator
            if reserved_output_tokens is not None and int(reserved_output_tokens) != self.budget_estimator.reserved_output_tokens:
                raise ValueError("reserved_output_tokens must match PromptBudgetEstimator")
        self.reserved_output_tokens = self.budget_estimator.reserved_output_tokens
        provider_reserved_output_tokens = getattr(provider, "reserved_output_tokens", None)
        if provider_reserved_output_tokens is not None and int(provider_reserved_output_tokens) != self.reserved_output_tokens:
            raise ValueError("provider reserved_output_tokens must match PromptBudgetEstimator")
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS))
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.projector = projector or FlowProjectionBuilder()
        self.renderer = renderer or HumanAnswerPromptRenderer()
        self.text_validator = text_validator or HumanAnswerTextValidator()
        self.narration_planner = narration_planner or FlowNarrationPlanner()
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.cancel_event = cancel_event
        self.audit_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.prompt_budget_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))

    def answer(
        self,
        request: KnowledgeQueryRequest,
        execution: Any,
        *,
        plan: QueryRetrievalPlan | None = None,
        deadline_at: float | None = None,
    ) -> KnowledgeHumanQueryResponse:
        narrative_plans = tuple(getattr(execution, "narrative_plans", ()) or ())
        fallback_flows = tuple(getattr(execution, "flows", ()) or ()) if not narrative_plans else ()
        selected_items: tuple[Any, ...] = narrative_plans or fallback_flows
        if not selected_items:
            raise HumanAnswerGenerationFailed("no grounded flows")
        if deadline_at is None:
            deadline_at = time.monotonic() + self.request_deadline_seconds
        answers: List[KnowledgeFlowAnswer] = []
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        deadline_failures = 0
        if plan is None:
            raise HumanAnswerGenerationFailed("query retrieval plan is required")
        effective_plan = plan
        resolved_language = effective_plan.response_language
        for flow_index, narrative_plan in enumerate(selected_items, start=1):
            source, entrypoint = self.projector.flow_answer_identity(narrative_plan)
            try:
                if self._cancelled():
                    raise HumanAnswerDeadlineExceeded()
                segments = self._segments_for_flow(
                    request,
                    narrative_plan,
                    effective_plan,
                    flow_index=flow_index,
                    source=source,
                    entrypoint=entrypoint,
                )
                text = self._answer_one_family(
                    segments,
                    deadline_at,
                    flow_index=flow_index,
                    source=source,
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
            except HumanAnswerContextBudgetExceeded:
                diagnostics.append(self._segment_budget_diagnostic(source, entrypoint))
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
        if diagnostics and any(item.code == FLOW_FAMILY_SEGMENT_CONTEXT_BUDGET_EXCEEDED for item in diagnostics):
            raise HumanAnswerContextBudgetExceeded("One assembled flow family exceeded the segment context budget.")
        raise HumanAnswerGenerationFailed("no grounded flow answers")

    def _segments_for_flow(
        self,
        request: KnowledgeQueryRequest,
        flow: EntrypointFlow | FlowFamily | FlowNarrativePlan,
        plan: QueryRetrievalPlan,
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
    ) -> tuple[FlowNarrationSegment, ...]:
        full_input = self.projector.human_llm_input(request, flow, plan)
        single_input = self._segment_llm_input(
            full_input,
            full_input.get("orderedFacts", []),
            segment_index=1,
            total_segments=1,
            terminal=True,
        )
        single_prompt = self.renderer.render(single_input)
        if self.budget_estimator.estimate(single_prompt).fits:
            return (
                FlowNarrationSegment(
                    llm_input=single_input,
                    index=1,
                    total=1,
                    terminal=True,
                ),
            )
        narration_plan = self.narration_planner.plan(full_input)
        if not narration_plan.units:
            raise HumanAnswerContextBudgetExceeded("The assembled flow family has no segmentable facts.")
        fact_groups = self._segment_unit_groups(full_input, narration_plan.units)
        segments: list[FlowNarrationSegment] = []
        total = len(fact_groups)
        for index, group in enumerate(fact_groups, start=1):
            terminal = index == total
            llm_input = self._segment_llm_input(
                full_input,
                group,
                segment_index=index,
                total_segments=total,
                terminal=terminal,
            )
            segments.append(FlowNarrationSegment(llm_input=llm_input, index=index, total=total, terminal=terminal))
        return tuple(segments)

    def _segment_unit_groups(
        self,
        full_input: Mapping[str, Any],
        units: Sequence[FlowNarrationUnit],
    ) -> list[list[Dict[str, Any]]]:
        groups: list[list[Dict[str, Any]]] = []
        offset = 0
        total_hint = max(1, len(units))
        while offset < len(units):
            selected_units = self._candidate_units_by_budget(full_input, units, offset, total_hint)
            if not selected_units:
                selected_units = [units[offset]]
            facts = [dict(unit.fact) for unit in selected_units]
            while facts:
                candidate_input = self._segment_llm_input(
                    full_input,
                    facts,
                    segment_index=len(groups) + 1,
                    total_segments=total_hint,
                    terminal=False,
                )
                if self.budget_estimator.estimate(self.renderer.render(candidate_input)).fits:
                    break
                facts = facts[:-1]
            if not facts:
                raise HumanAnswerContextBudgetExceeded("An indivisible flow-family atomic unit exceeds the model context.")
            groups.append(facts)
            offset += len(facts)
        return groups

    def _candidate_units_by_budget(
        self,
        full_input: Mapping[str, Any],
        units: Sequence[FlowNarrationUnit],
        offset: int,
        total_hint: int,
    ) -> list[FlowNarrationUnit]:
        empty_input = self._segment_llm_input(
            full_input,
            [],
            segment_index=1,
            total_segments=total_hint,
            terminal=False,
        )
        empty_estimate = self.budget_estimator.estimate(self.renderer.render(empty_input))
        available = max(0, empty_estimate.context_tokens - empty_estimate.reserved_output_tokens - empty_estimate.fixed_framing_reserve_tokens - empty_estimate.rendered_input_tokens)
        selected: list[FlowNarrationUnit] = []
        serialized_tokens = 0
        best_count = 0
        best_rank = -1
        for index in range(offset, len(units)):
            unit = units[index]
            serialized_tokens += self.budget_estimator.estimate_text_tokens(unit.serialized)
            if selected and serialized_tokens > available:
                break
            selected.append(unit)
            if unit.cut_preference >= best_rank:
                best_rank = unit.cut_preference
                best_count = len(selected)
        if not selected:
            return []
        return selected[: max(1, best_count or len(selected))]

    def _segment_llm_input(
        self,
        full_input: Mapping[str, Any],
        facts: Sequence[Mapping[str, Any]],
        *,
        segment_index: int,
        total_segments: int,
        terminal: bool,
    ) -> Dict[str, Any]:
        ordered_facts = [dict(fact) for fact in facts]
        coverage = self._coverage_for_segment(ordered_facts)
        result = {
            key: value
            for key, value in dict(full_input).items()
            if key not in {"orderedFacts", "coverageContract", "suggestedStepPlan"}
        }
        result["segment"] = {
            "index": int(segment_index),
            "total": int(total_segments),
            "terminal": bool(terminal),
            "incomingContext": self._incoming_context(full_input, ordered_facts),
            "outgoingContext": self._outgoing_context(full_input, ordered_facts),
        }
        result["familyRoot"] = {
            "source": result.get("rootSource") or result.get("source"),
            "entrypoint": result.get("entrypoint"),
        }
        result["orderedFacts"] = ordered_facts
        result["coverageContract"] = coverage
        result["suggestedStepPlan"] = self.projector._suggested_step_plan(ordered_facts)
        return result

    def _coverage_for_segment(self, facts: Sequence[Mapping[str, Any]]) -> Dict[str, Any]:
        canonical_refs = [str(fact["ref"]) for fact in facts if str(fact.get("ref") or "").strip()]
        required_refs = [
            str(fact["ref"])
            for fact in facts
            if fact.get("type") not in {"supporting", "boundary"} and str(fact.get("ref") or "").strip()
        ]
        return {
            "canonicalUnitRefs": canonical_refs,
            "canonicalFactRefs": canonical_refs,
            "requiredUnitRefs": required_refs,
            "nodeRefs": [str(fact["ref"]) for fact in facts if fact.get("type") == "node"],
            "transitionRefs": [str(fact["ref"]) for fact in facts if fact.get("type") == "transition"],
            "supportingRefs": [str(fact["ref"]) for fact in facts if fact.get("type") == "supporting"],
            "boundaryRefs": [str(fact["ref"]) for fact in facts if fact.get("type") == "boundary"],
            "gapRefs": [str(fact["ref"]) for fact in facts if fact.get("type") == "gap"],
        }

    def _incoming_context(self, full_input: Mapping[str, Any], segment_facts: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
        segment_refs = {str(fact.get("ref") or "") for fact in segment_facts}
        segment_nodes = {str(fact.get("ref") or "") for fact in segment_facts if fact.get("type") == "node"}
        result: list[Dict[str, Any]] = []
        for fact in full_input.get("orderedFacts", []) or []:
            if not isinstance(fact, dict) or str(fact.get("ref") or "") in segment_refs:
                continue
            if fact.get("type") in {"transition", "supporting"} and str(fact.get("toRef") or "") in segment_nodes:
                result.append(self._continuity_descriptor(fact))
        return self._dedupe_continuity(result)

    def _outgoing_context(self, full_input: Mapping[str, Any], segment_facts: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
        segment_refs = {str(fact.get("ref") or "") for fact in segment_facts}
        segment_nodes = {str(fact.get("ref") or "") for fact in segment_facts if fact.get("type") == "node"}
        result: list[Dict[str, Any]] = []
        for fact in full_input.get("orderedFacts", []) or []:
            if not isinstance(fact, dict) or str(fact.get("ref") or "") in segment_refs:
                continue
            if fact.get("type") in {"transition", "supporting", "boundary"} and str(fact.get("fromRef") or "") in segment_nodes:
                result.append(self._continuity_descriptor(fact))
        return self._dedupe_continuity(result)

    def _continuity_descriptor(self, fact: Mapping[str, Any]) -> Dict[str, Any]:
        connector = fact.get("connector") if isinstance(fact.get("connector"), dict) else {}
        descriptor = {
            "relation": fact.get("edgeType") or fact.get("type"),
            "fromSource": fact.get("fromSource"),
            "fromSymbol": fact.get("fromSymbol"),
            "toSource": fact.get("toSource"),
            "toSymbol": fact.get("toSymbol"),
            "connectorKind": connector.get("kind"),
            "method": connector.get("method"),
            "route": connector.get("route"),
            "boundaryKind": fact.get("boundaryKind"),
            "target": fact.get("target") or fact.get("displaySymbol"),
        }
        return {key: value for key, value in descriptor.items() if value}

    def _dedupe_continuity(self, items: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
        seen: set[str] = set()
        result: list[Dict[str, Any]] = []
        for item in items:
            key = json.dumps(dict(item), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            if key in seen:
                continue
            seen.add(key)
            result.append(dict(item))
        return result

    def _answer_one_family(
        self,
        segments: Sequence[FlowNarrationSegment],
        deadline_at: float,
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        requested_language: str | None,
        resolved_language: str,
    ) -> str:
        payloads: list[Mapping[str, Any]] = []
        for segment in segments:
            payloads.append(
                self._answer_one_segment(
                    segment.llm_input,
                    deadline_at,
                    flow_index=flow_index,
                    source=source,
                    entrypoint=entrypoint,
                    segment_index=segment.index,
                    segment_count=segment.total,
                    requested_language=requested_language,
                    resolved_language=resolved_language,
                )
            )
        self._validate_family_segment_coverage(payloads, segments)
        return self._stitch_segment_payloads(payloads)

    def _answer_one_segment(
        self,
        llm_input: Mapping[str, Any],
        deadline_at: float,
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        segment_index: int,
        segment_count: int,
        requested_language: str | None,
        resolved_language: str,
    ) -> Mapping[str, Any]:
        validation_errors: Sequence[str] | None = None
        for attempt_count in (1, 2):
            result = self._complete_with_deadline(
                llm_input,
                deadline_at,
                validation_errors=validation_errors,
                flow_index=flow_index,
                source=source,
                entrypoint=entrypoint,
                attempt_count=attempt_count,
                requested_language=requested_language,
                resolved_language=resolved_language,
                segment_index=segment_index,
                segment_count=segment_count,
            )
            try:
                return self._validate_payload(result.raw_text, resolved_language, llm_input)
            except HumanAnswerContractViolation as exc:
                self._record_validation_errors(entrypoint=entrypoint, attempt_count=attempt_count, errors=exc.errors, segment_index=segment_index)
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

    def _segment_budget_diagnostic(self, source: str, entrypoint: str) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code=FLOW_FAMILY_SEGMENT_CONTEXT_BUDGET_EXCEEDED,
            message="One assembled flow family contains an indivisible fact group that exceeds the model context.",
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
        flow_index: int,
        source: str,
        entrypoint: str,
        attempt_count: int,
        requested_language: str | None,
        resolved_language: str,
        segment_index: int | None = None,
        segment_count: int | None = None,
    ) -> FlowExplanationProviderResult:
        if self._cancelled() or self._remaining_seconds(deadline_at) <= self.min_call_timeout_seconds:
            raise HumanAnswerDeadlineExceeded()
        prompt = self.renderer.render(llm_input, validation_errors)
        self._record_prompt_budget_check(
            prompt,
            llm_input,
            flow_index=flow_index,
            source=source,
            entrypoint=entrypoint,
            attempt="REPAIR" if validation_errors else "INITIAL",
            segment_index=segment_index,
            segment_count=segment_count,
        )
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
            segment_index=segment_index,
            segment_count=segment_count,
        )
        return result

    def _record_prompt_budget_check(
        self,
        prompt: str,
        llm_input: Mapping[str, Any],
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        attempt: str,
        segment_index: int | None = None,
        segment_count: int | None = None,
    ) -> PromptBudgetEstimate:
        estimate = self.budget_estimator.estimate(prompt)
        budget_payload = self._prompt_budget_payload(estimate, attempt)
        self.prompt_budget_records.append(
            {
                "flowIndex": int(flow_index),
                "segmentIndex": segment_index,
                "segmentCount": segment_count,
                "source": source,
                "entrypoint": entrypoint,
                "attempt": attempt,
                "promptCharCount": len(prompt),
                "promptUtf8Bytes": len(prompt.encode("utf-8")),
                "promptHash": self._sha256(prompt),
                "llmInputHash": self._sha256(json.dumps(dict(llm_input), ensure_ascii=False, sort_keys=True, separators=(",", ":"))),
                **budget_payload,
                "promptBudgetEstimate": budget_payload,
            }
        )
        if not estimate.fits:
            raise HumanAnswerContextBudgetExceeded("The complete grounded flow exceeds the available model context.")
        return estimate

    def _prompt_budget_payload(self, estimate: PromptBudgetEstimate, attempt: str) -> Dict[str, Any]:
        return {
            "attempt": attempt,
            "renderedInputTokens": int(estimate.rendered_input_tokens),
            "reservedOutputTokens": int(estimate.reserved_output_tokens),
            "fixedFramingReserveTokens": int(estimate.fixed_framing_reserve_tokens),
            "totalRequiredTokens": int(estimate.total_required_tokens),
            "contextTokens": int(estimate.context_tokens),
            "fits": bool(estimate.fits),
        }

    def _validate_text(self, raw_text: str, language: str, llm_input: Mapping[str, Any]) -> str:
        return self._render_structured_answer(self._validate_payload(raw_text, language, llm_input))

    def _validate_payload(self, raw_text: str, language: str, llm_input: Mapping[str, Any]) -> Mapping[str, Any]:
        try:
            payload = json.loads(raw_text)
        except Exception as exc:
            raise HumanAnswerMalformedResponse(["Response must be strict JSON with steps and result fields."]) from exc
        if not isinstance(payload, dict):
            raise HumanAnswerMalformedResponse(["Response must be a JSON object."])
        errors = self._validate_structured_answer_payload(payload, llm_input)
        if errors:
            raise HumanAnswerContractViolation(errors)
        normalized = self._render_structured_answer(payload)
        forbidden = (
            "graphId",
            "graphRevision",
            "nodeId",
            "edgeId",
            "evidenceId",
            "nodeRef",
            "transitionRef",
            "boundaryRef",
            "evidenceRef",
            "flowIndex",
            "analysis-graph-",
        )
        if any(token in normalized for token in forbidden):
            raise HumanAnswerContractViolation(["Response must not expose internal graph refs, node ids, transition refs, evidence refs, or analysis ids."])
        coverage = llm_input.get("coverageContract") if isinstance(llm_input.get("coverageContract"), dict) else {}
        leaked_local_refs = [
            str(ref)
            for ref in coverage.get("canonicalFactRefs", [])
            if str(ref).strip() and re.search(rf"(?<![\w$]){re.escape(str(ref))}(?![\w$])", normalized)
        ]
        if leaked_local_refs:
            raise HumanAnswerContractViolation(["Response must not expose internal graph refs, node ids, transition refs, evidence refs, or analysis ids."])
        if "**" in normalized or "`" in normalized:
            raise HumanAnswerContractViolation(["Response must be escaped plain text without Markdown bold or backticks."])
        unsupported = self._unsupported_claim_errors(normalized, llm_input)
        if unsupported:
            raise HumanAnswerContractViolation(unsupported)
        text_validation = self.text_validator.validate(normalized, language)
        if not text_validation.valid:
            if any("language" in error.lower() for error in text_validation.errors):
                raise HumanAnswerLanguagePolicyViolation(text_validation.errors)
            raise HumanAnswerContractViolation(text_validation.errors)
        return payload

    def _stitch_segment_payloads(self, payloads: Sequence[Mapping[str, Any]]) -> str:
        lines: list[str] = []
        for payload in payloads:
            for step in payload.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                text = self._strip_step_number(str(step.get("text") or "").strip())
                if text:
                    lines.append(f"{len(lines) + 1}. {text}")
        terminal = payloads[-1] if payloads else {}
        result = self._strip_step_number(str(terminal.get("result") or "").strip())
        if result:
            lines.append(f"{len(lines) + 1}. {result}")
        return "\n".join(lines).strip()

    def _validate_structured_answer_payload(self, payload: Mapping[str, Any], llm_input: Mapping[str, Any]) -> List[str]:
        errors: List[str] = []
        allowed_keys = {"steps", "result"}
        extra_keys = sorted(str(key) for key in payload if key not in allowed_keys)
        if extra_keys:
            errors.append("Response must not include extra fields.")
        steps = payload.get("steps")
        if not isinstance(steps, list) or not steps:
            errors.append("Response JSON must contain a non-empty steps array.")
            return errors
        result = payload.get("result")
        segment = llm_input.get("segment") if isinstance(llm_input.get("segment"), dict) else {}
        terminal = bool(segment.get("terminal"))
        if terminal:
            if result is not None and not isinstance(result, str):
                errors.append("Terminal segment result must be a string, null, or absent.")
        elif result is not None and (not isinstance(result, str) or result.strip()):
            errors.append("Non-terminal segment result must be null or an empty string.")

        coverage = llm_input.get("coverageContract") if isinstance(llm_input.get("coverageContract"), dict) else {}
        canonical_refs = [str(item) for item in (coverage.get("canonicalUnitRefs") or coverage.get("canonicalFactRefs") or []) if str(item).strip()]
        required_refs = [str(item) for item in coverage.get("requiredUnitRefs", []) if str(item).strip()]
        required_nodes = [str(item) for item in coverage.get("nodeRefs", []) if str(item).strip() and (not required_refs or str(item) in required_refs)]
        required_transitions = [str(item) for item in coverage.get("transitionRefs", []) if str(item).strip() and (not required_refs or str(item) in required_refs)]
        required_boundaries = [str(item) for item in coverage.get("boundaryRefs", []) if str(item).strip() and (not required_refs or str(item) in required_refs)]
        required_gaps = [str(item) for item in coverage.get("gapRefs", []) if str(item).strip() and (not required_refs or str(item) in required_refs)]
        ref_order = {ref: index for index, ref in enumerate(canonical_refs)}
        facts = {
            str(item.get("ref")): item
            for item in llm_input.get("orderedFacts", [])
            if isinstance(item, dict) and str(item.get("ref") or "").strip()
        }
        if not canonical_refs:
            errors.append("No canonical fact refs were supplied for validation.")
            return errors

        seen_refs: list[str] = []
        last_index = -1
        for step_index, step in enumerate(steps, start=1):
            if not isinstance(step, dict):
                errors.append(f"steps[{step_index}] must be an object.")
                continue
            step_extra = sorted(str(key) for key in step if key not in {"unitRefs", "factRefs", "certainty", "text"})
            if step_extra:
                errors.append(f"steps[{step_index}] must not include extra fields.")
            text = step.get("text")
            if not isinstance(text, str) or not text.strip():
                errors.append(f"steps[{step_index}].text must be a non-empty string.")
            certainty = str(step.get("certainty") or "VERIFIED").strip().upper().replace("-", "_").replace(" ", "_")
            invalid_certainty = certainty not in {"VERIFIED", "UNVERIFIED", "AMBIGUOUS"}
            refs = step.get("unitRefs")
            if refs is None:
                refs = step.get("factRefs")
            if not isinstance(refs, list) or not refs:
                errors.append(f"steps[{step_index}].unitRefs must be a non-empty array.")
                continue
            step_ref_values: list[str] = []
            for ref_value in refs:
                ref = str(ref_value or "").strip()
                if not ref:
                    errors.append(f"steps[{step_index}].unitRefs contains a blank ref.")
                    continue
                if ref not in ref_order:
                    errors.append(f"steps[{step_index}] contains a foreign unitRef.")
                    continue
                if ref in seen_refs or ref in step_ref_values:
                    errors.append(f"unitRef {ref} is duplicated.")
                    continue
                current_index = ref_order[ref]
                if current_index < last_index:
                    errors.append(f"unitRef {ref} is out of canonical order.")
                last_index = max(last_index, current_index)
                step_ref_values.append(ref)
            seen_refs.extend(step_ref_values)
            if invalid_certainty and not any(facts.get(ref, {}).get("type") == "gap" for ref in step_ref_values):
                certainty = "VERIFIED"
            elif invalid_certainty:
                errors.append(f"steps[{step_index}].certainty must be VERIFIED, UNVERIFIED, or AMBIGUOUS.")
            errors.extend(self._validate_step_certainty(step_index, certainty, step_ref_values, facts))
            errors.extend(self._validate_step_ownership(step_index, step_ref_values, facts))

        missing_nodes = [ref for ref in required_nodes if ref not in seen_refs]
        missing_transitions = [ref for ref in required_transitions if ref not in seen_refs]
        missing_boundaries = [ref for ref in required_boundaries if ref not in seen_refs]
        missing_gaps = [ref for ref in required_gaps if ref not in seen_refs]
        if missing_nodes:
            errors.append(f"Missing executable flow node facts: {', '.join(missing_nodes)}.")
        if missing_transitions:
            errors.append(f"Missing resolved transition facts: {', '.join(missing_transitions)}.")
        if missing_boundaries:
            errors.append(f"Missing boundary facts: {', '.join(missing_boundaries)}.")
        if missing_gaps:
            errors.append(f"Missing gap facts: {', '.join(missing_gaps)}.")
        return errors

    def _validate_family_segment_coverage(
        self,
        payloads: Sequence[Mapping[str, Any]],
        segments: Sequence[FlowNarrationSegment],
    ) -> None:
        expected: list[str] = []
        covered: list[str] = []
        result_segments = 0
        for payload, segment in zip(payloads, segments):
            coverage = segment.llm_input.get("coverageContract") if isinstance(segment.llm_input.get("coverageContract"), dict) else {}
            expected.extend(
                str(ref)
                for ref in (coverage.get("requiredUnitRefs") or coverage.get("canonicalFactRefs", []))
                if str(ref).strip()
            )
            for step in payload.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                refs = step.get("unitRefs")
                if refs is None:
                    refs = step.get("factRefs")
                covered.extend(str(ref) for ref in refs or [] if str(ref).strip())
            result = payload.get("result")
            if isinstance(result, str) and result.strip():
                result_segments += 1
                if not segment.terminal:
                    raise HumanAnswerContractViolation(["Only the terminal segment may include a result."])
        covered_required = [ref for ref in covered if ref in set(expected)]
        if sorted(covered_required) != sorted(expected) or len(covered_required) != len(set(covered_required)):
            raise HumanAnswerContractViolation(["Segment fact coverage must be exact and non-overlapping across the family."])
        if segments and result_segments > 1:
            raise HumanAnswerContractViolation(["At most one terminal segment result is allowed."])

    def _validate_step_certainty(
        self,
        step_index: int,
        certainty: str,
        refs: Sequence[str],
        facts: Mapping[str, Mapping[str, Any]],
    ) -> List[str]:
        if not refs:
            return []
        errors: List[str] = []
        gap_refs = [ref for ref in refs if facts.get(ref, {}).get("type") == "gap"]
        if gap_refs and len(gap_refs) != len(refs):
            errors.append(f"steps[{step_index}] must not combine gap units with verified execution units.")
        if gap_refs:
            allowed = {str(facts.get(ref, {}).get("certainty") or "UNVERIFIED").upper() for ref in gap_refs}
            if certainty == "VERIFIED":
                errors.append(f"steps[{step_index}] narrates an unverified gap as verified.")
            elif certainty not in allowed:
                errors.append(f"steps[{step_index}].certainty does not match the supplied gap certainty.")
        return errors

    def _validate_step_ownership(self, step_index: int, refs: Sequence[str], facts: Mapping[str, Mapping[str, Any]]) -> List[str]:
        if not refs:
            return []
        errors: List[str] = []
        node_refs = {ref for ref in refs if facts.get(ref, {}).get("type") == "node"}
        for ref in refs:
            fact = facts.get(ref, {})
            fact_type = fact.get("type")
            if fact_type == "transition" and node_refs:
                adjacent = {str(fact.get("fromRef") or ""), str(fact.get("toRef") or "")}
                if not node_refs & adjacent:
                    errors.append(f"steps[{step_index}] claims transition {ref} without its owning node.")
            if fact_type == "supporting" and node_refs:
                adjacent = {str(fact.get("fromRef") or ""), str(fact.get("toRef") or "")}
                if not node_refs & adjacent:
                    errors.append(f"steps[{step_index}] claims supporting relation {ref} without its owning node.")
            if fact_type == "boundary" and node_refs:
                owner = str(fact.get("fromRef") or "")
                if owner not in node_refs:
                    errors.append(f"steps[{step_index}] claims boundary {ref} without its owning node.")
        return errors

    def _render_structured_answer(self, payload: Mapping[str, Any]) -> str:
        lines: list[str] = []
        for index, step in enumerate(payload.get("steps") or [], start=1):
            if not isinstance(step, dict):
                continue
            text = self._strip_step_number(str(step.get("text") or "").strip())
            if text:
                lines.append(f"{index}. {text}")
        result = self._strip_step_number(str(payload.get("result") or "").strip())
        if result:
            lines.append(f"{len(lines) + 1}. {result}")
        return "\n".join(lines).strip()

    def _strip_step_number(self, value: str) -> str:
        return re.sub(r"^\s*\d+(?:\.\d+)*[\.)]\s+", "", value).strip()

    def _unsupported_claim_errors(self, text: str, llm_input: Mapping[str, Any]) -> List[str]:
        rendered_facts = json.dumps(
            {
                "orderedFacts": llm_input.get("orderedFacts"),
            },
            ensure_ascii=False,
        )
        errors: List[str] = []
        routes = {
            route.rstrip(".,;:!?)\"]")
            for route in re.findall(r"/[A-Za-z0-9_./{}:-]+", text)
        }
        for route in sorted(item for item in routes if item):
            if route not in rendered_facts:
                errors.append(f"Response mentions unsupported route or path {route}.")
        for status in sorted(set(re.findall(r"\bHTTP\s+([1-5][0-9][0-9])\b", text, flags=re.IGNORECASE))):
            if status not in rendered_facts:
                errors.append(f"Response mentions unsupported HTTP status {status}.")
        return errors

    def _record_audit(
        self,
        prompt: str,
        raw_response: str,
        *,
        entrypoint: str,
        attempt_count: int,
        requested_language: str | None,
        resolved_language: str,
        segment_index: int | None = None,
        segment_count: int | None = None,
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
                "segmentIndex": segment_index,
                "segmentCount": segment_count,
                "attemptCount": attempt_count,
                "requestedLanguage": str(requested_language or "AUTO"),
                "resolvedLanguage": resolved_language,
            }
        )

    def _record_validation_errors(
        self,
        *,
        entrypoint: str,
        attempt_count: int,
        errors: Sequence[str],
        segment_index: int | None = None,
    ) -> None:
        for record in reversed(self.audit_records):
            if (
                record.get("flowEntrypoint") == entrypoint
                and record.get("attemptCount") == attempt_count
                and record.get("segmentIndex") == segment_index
            ):
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
        reserved_output_tokens: int = DEFAULT_HUMAN_ANSWER_RESERVED_OUTPUT_TOKENS,
    ) -> None:
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS)
        if self.context_tokens < 1024:
            raise ValueError("Flow explanation context_tokens must be at least 1024")
        self.reserved_output_tokens = max(0, int(reserved_output_tokens))
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
                "options": {"num_ctx": self.context_tokens, "num_predict": self.reserved_output_tokens},
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
