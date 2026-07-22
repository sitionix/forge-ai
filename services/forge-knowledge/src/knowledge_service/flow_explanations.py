from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.parse
from collections import deque
from dataclasses import dataclass, replace
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
from knowledge_service.grounded_narration import (
    FamilyNarrationPreparation,
    FamilyNarrationService,
    GroundedNarrationError,
    HumanNarrationStage,
    NarrativeFactProjector,
    NarrationAtomPlanner,
    NarrationSegment,
    NarrationSegmentPlanner,
    assert_final_narration_prompt_safe,
    technical_diagnostic,
)
from knowledge_service.flow_boundary_classifier import FlowBoundaryClassifier, FLOW_BOUNDARY_CLASSIFIER
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.operation_facts import AvailableOperationFact, normalize_http_method, normalize_route, normalize_transport_kind
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
    done_reason: str | None = None
    prompt_eval_count: int | None = None
    eval_count: int | None = None
    reserved_output_tokens: int | None = None
    duration_ms: float | None = None


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


class HumanAnswerOutputCapacitySplit(HumanAnswerGenerationFailed):
    def __init__(self, violation: HumanAnswerContractViolation) -> None:
        self.violation = violation
        super().__init__("provider output ended at the reserved output budget")


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
        prompt_kind = str(llm_input.get("promptKind") or "").upper()
        if prompt_kind == "GROUNDING":
            return self._render_grounding(llm_input, validation_errors)
        if prompt_kind == "FINAL_NARRATION":
            return self._render_final_narration(llm_input, validation_errors)
        raise ValueError("Human narration prompts must use GROUNDING or FINAL_NARRATION promptKind.")

    def _render_grounding(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious grounding response failed validation. Correct these exact contract violations using only this batch:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += (
                "\nFor any raw evidence copy violation, be conservative: do not return a claim that closely follows a supplied evidence slice. "
                "Use NO_NEW_BEHAVIOR with claimRefs [] for evidence that cannot support a compact non-verbatim claim. "
                "For any evidence ownership violation, use the claim unit's ownedEvidenceRefs and coverageContract.evidenceOwners so each claim cites only evidence owned by that same unitRef; split claims when cited evidence has different owners. "
                "For any language violation, rewrite every claim text in the ISO language named by responseLanguage while preserving code identifiers exactly. "
                "Keep processedEvidence complete and internally consistent. Return a replacement JSON object only.\n"
            )
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        return (
            "Extract compact technical claims from one evidence-grounding batch for one assembled execution family.\n"
            "Return strict JSON only with exactly this shape: "
            "{\"claims\":[{\"claimRef\":\"c1\",\"unitRef\":\"u1\",\"evidenceRefs\":[\"e1\"],\"text\":\"Compact grounded technical claim.\"}],"
            "\"processedEvidence\":[{\"evidenceRef\":\"e1\",\"disposition\":\"CLAIMED\",\"claimRefs\":[\"c1\"]}]}.\n"
            "Allowed processedEvidence dispositions are CLAIMED and NO_NEW_BEHAVIOR.\n"
            "Each claim object must contain exactly claimRef, unitRef, evidenceRefs, and text. "
            "Each processedEvidence object must contain exactly evidenceRef, disposition, and claimRefs. "
            "claimRefs must always be a JSON array, for example [] or [\"c1\"]; never return a string, object, null, reason, note, summary, unitRef, or any extra field there.\n"
            "Every supplied evidenceRef must appear exactly once in processedEvidence. "
            "Every claim must cite one supplied unitRef and at least one supplied evidenceRef owned by that same unitRef. "
            "coverageContract.evidenceOwners maps each evidenceRef to its only valid unitRef owner and is authoritative. "
            "Each unit also lists ownedEvidenceRefs; claim.evidenceRefs must be a subset of ownedEvidenceRefs for claim.unitRef. "
            "Do not cite foreign evidence or units.\n"
            "The unit descriptor is already the public typed behavior that final narration can use. "
            "Create a claim only when evidence adds a distinct behavior not already represented by that unit descriptor. "
            "If the supplied evidence only restates, illustrates, or line-level supports descriptor fields, do not create a claim for that evidence. "
            "It is valid, and often preferred, to return claims [] when the descriptors already cover the behavior for this batch. "
            "Do not create claims for local setup, helper calls, guard code, boilerplate, or literal code lines unless they add a distinct behavior absent from the descriptor. "
            "Create the smallest number of distinct claims needed. Group evidence in one claim only when it has the same unit owner and supports the same behavior. "
            "Still account for every evidenceRef exactly once in processedEvidence. "
            "NO_NEW_BEHAVIOR means the evidence was processed but adds no distinct behavior beyond the unit descriptor or already represented claims.\n"
            "responseLanguage is an ISO 639 language code; write natural-language claim text in that language, not English unless responseLanguage is en. "
            "For example, responseLanguage uk means Ukrainian and responseLanguage de means German. "
            "Preserve code identifiers, routes, constants, topics, methods, and quoted code identifiers exactly. "
            "Do not copy a full raw evidence slice as claim text. Claim text must be a compact behavior statement, not a verbatim evidence sentence. "
            "Before returning, compare every claim text with its cited evidence text; if a claim contains the complete evidence text, rewrite it or mark that evidence NO_NEW_BEHAVIOR. "
            "If an evidence slice cannot support a non-verbatim behavior claim, or if you are uncertain, mark that evidence NO_NEW_BEHAVIOR. Do not write final answer prose.\n"
            "Do not expose graph ids, node ids, edge ids, evidence ids, analysis ids, SQLite ids, refs, or retrieval mechanics in claim text.\n"
            "Do not invent routes, transports, statuses, exceptions, side effects, or causal transitions unsupported by this batch.\n"
            f"{validation_block}"
            "BEGIN_GROUNDING_BATCH_JSON\n"
            f"{context_json}\n"
            "END_GROUNDING_BATCH_JSON\n"
        )

    def _render_final_narration(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious final narration response failed validation. Correct these exact contract violations using only this segment:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += (
                "\nFor any language violation, rewrite every step text and terminal result in the ISO language named by responseLanguage while preserving code identifiers exactly. "
                "Use natural localized prose, not English fallback labels; do not put certainty labels such as VERIFIED, UNVERIFIED, or AMBIGUOUS inside text. "
                "For any missing, duplicate, foreign, or out-of-order atomRef violation, rebuild steps from coverageContract.requiredAtomRefs in exactly that order. "
                "The result field never covers atomRefs; if a terminal atom is missing, add it to a step and then provide result only if this segment is terminal. "
                "If unsure, use one terse step per suggestedStepPlan entry and copy that entry's atomRefs exactly; do this especially for any atomRef named as missing. "
                "Return a replacement JSON object only. Keep the text natural and grounded in the supplied atoms.\n"
            )
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        return (
            "Write one concise human-readable narration segment for one assembled execution family.\n"
            "Return strict JSON only with exactly this shape: "
            "{\"steps\":[{\"atomRefs\":[\"a1\"],\"certainty\":\"VERIFIED\",\"text\":\"human-readable flow step\"}],\"result\":null} "
            "for non-terminal segments, or the same shape with result as a grounded string only for the terminal segment.\n"
            "responseLanguage is an ISO 639 language code; write all natural-language prose in that language, not English unless responseLanguage is en. "
            "For example, responseLanguage uk means Ukrainian and responseLanguage de means German. "
            "Use the natural vocabulary and script of responseLanguage; for Ukrainian, step text must be Ukrainian Cyrillic prose. "
            "Preserve code identifiers, routes, constants, topics, and method names exactly as supplied.\n"
            "Use only narrationAtoms. The prompt intentionally contains no raw evidence; do not ask for or mention evidence, refs, batches, slices, graph ids, or retrieval mechanics.\n"
            "coverageContract is authoritative. Cover every required atomRef exactly once in canonical order. "
            "suggestedStepPlan lists the required step units. Return exactly one step for each suggestedStepPlan entry, in the same order. "
            "Copy each listed atomRef exactly; do not merge, skip, summarize, replace, or omit trailing suggested entries. "
            "The result field is not coverage and does not cover any atomRef, including terminal result atoms. "
            "The final narration LLM must not decide flow ordering.\n"
            "Never combine a VERIFIED atom with an UNVERIFIED or AMBIGUOUS gap atom. "
            "Do not linearize independent branches as a false sequence. Do not connect unrelated sources without a supplied transition or gap.\n"
            "For UNVERIFIED or AMBIGUOUS atoms, preserve the supplied certainty and explicitly state that the current graph does not verify the direct causal transition.\n"
            "The certainty field carries VERIFIED, UNVERIFIED, or AMBIGUOUS; do not repeat those uppercase certainty labels in human-visible text.\n"
            "Only the terminal segment may include result. Non-terminal result must be null.\n"
            "incomingContext and outgoingContext are public-safe continuity descriptors only; do not cover or copy them as refs.\n"
            "Do not invent routes, transports, statuses, exceptions, validations, side effects, results, or ordering unsupported by narrationAtoms.\n"
            f"{validation_block}"
            "BEGIN_NARRATION_SEGMENT_JSON\n"
            f"{context_json}\n"
            "END_NARRATION_SEGMENT_JSON\n"
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
        projection = NarrativeFactProjector(self.boundary_classifier).project(request, flow, plan)
        atoms = NarrationAtomPlanner().plan(projection, ())
        atom_payloads = [atom.to_prompt_dict() for atom in atoms]
        atom_refs = [str(atom.get("atomRef")) for atom in atom_payloads if str(atom.get("atomRef") or "").strip()]
        return {
            "promptKind": "FINAL_NARRATION",
            "originalQuestion": request.queryText,
            "detectedLanguage": plan.detected_language,
            "responseLanguage": plan.response_language,
            "intent": plan.effective_intent,
            "familyRoot": {"source": projection.source, "entrypoint": projection.entrypoint},
            "segment": {"index": 1, "total": 1, "terminal": True, "incomingContext": [], "outgoingContext": []},
            "narrationAtoms": atom_payloads,
            "coverageContract": {
                "canonicalAtomRefs": atom_refs,
                "requiredAtomRefs": atom_refs,
                "atomCertainty": {str(atom.get("atomRef")): str(atom.get("certainty") or "VERIFIED") for atom in atom_payloads},
                "gapRefs": [
                    str(atom.get("atomRef"))
                    for atom in atom_payloads
                    if str(atom.get("certainty") or "") in {"UNVERIFIED", "AMBIGUOUS"}
                ],
            },
            "suggestedStepPlan": [
                {"atomRefs": [str(atom.get("atomRef"))], "certainty": str(atom.get("certainty") or "VERIFIED")}
                for atom in atom_payloads
                if str(atom.get("atomRef") or "").strip()
            ],
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
                parts.append(FlowToolPart(kind=part.kind.value, tree=self._tree(part.fragment.family, part.fragment.operation_facts)))
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

    def _tree_item_dict(self, item: FlowToolTreeItem) -> Dict[str, Any]:
        data = item.dict(exclude_none=True)
        children = [
            self._tree_item_dict(child)
            for child in item.children
        ]
        data["children"] = children
        return data

    def _tree(
        self,
        flow: EntrypointFlow | FlowFamily,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> FlowToolTree:
        node_by_key = {self._node_key(node): node for node in flow.nodes}
        operation_facts_by_node = self._operation_facts_by_node(operation_facts)
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
        root = self._node_item(
            flow.entrypoint,
            evidence_by_node.get((flow.entrypoint.source_id, flow.entrypoint.node_id), []),
            root_source=root_source,
            operation_facts=operation_facts_by_node.get(root_key, ()),
        )
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
                frame["item"].children.append(
                    self._node_item(
                        target,
                        child_evidence,
                        root_source=root_source,
                        transition=entry,
                        cycle=True,
                        operation_facts=operation_facts_by_node.get(target_key, ()),
                    )
                )
                continue
            if target_key in rendered:
                frame["item"].children.append(
                    self._node_item(
                        target,
                        child_evidence,
                        root_source=root_source,
                        transition=entry,
                        shared=True,
                        operation_facts=operation_facts_by_node.get(target_key, ()),
                    )
                )
                continue
            child = self._node_item(
                target,
                child_evidence,
                root_source=root_source,
                transition=entry,
                operation_facts=operation_facts_by_node.get(target_key, ()),
            )
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
        for fact in self._external_operation_facts(operation_facts, node_by_key):
            root.children.append(self._operation_item(fact, root_source=root_source))
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

    def _node_item(
        self,
        node: FlowGraphNode,
        evidence: Sequence[FlowGraphEvidence],
        *,
        root_source: str | None = None,
        transition: FlowGraphEdge | None = None,
        cycle: bool = False,
        shared: bool = False,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> FlowToolTreeItem:
        return FlowToolTreeItem(
            source=node.source_id if root_source and node.source_id != root_source else None,
            symbol=self._symbol(node),
            kind=self._node_kind(node),
            trigger=self._trigger(node, operation_facts),
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

    def _operation_item(self, fact: AvailableOperationFact, *, root_source: str) -> FlowToolTreeItem:
        return FlowToolTreeItem(
            source=fact.owner_source_id if fact.owner_source_id != root_source else None,
            symbol=self._operation_symbol(fact),
            kind="OPERATION",
            trigger=self._operation_trigger((fact,)),
            path=fact.owner_relative_path,
            evidence=[self._operation_evidence(item) for item in fact.evidence],
            children=[],
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

    def _trigger(
        self,
        node: FlowGraphNode,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> FlowToolTrigger | None:
        operation_trigger = self._operation_trigger(operation_facts)
        if operation_trigger is not None and not node.entrypoint:
            return operation_trigger
        if not node.entrypoint:
            return None
        trigger_kind = trigger_kind_for_entrypoint(node.entrypoint_kind)
        if trigger_kind is None:
            return operation_trigger
        return FlowToolTrigger(
            kind=trigger_kind,
            method=self._clean(node.entrypoint_http_method),
            route=self._clean(node.entrypoint_route),
            topic=self._clean(node.entrypoint_topic),
            schedule=self._clean(node.entrypoint_schedule),
            interfaceMethod=self._clean(node.entrypoint_interface_method),
        )

    def _operation_facts_by_node(
        self,
        operation_facts: Sequence[AvailableOperationFact],
    ) -> Dict[tuple[str, str, str], tuple[AvailableOperationFact, ...]]:
        grouped: Dict[tuple[str, str, str], List[AvailableOperationFact]] = {}
        for fact in operation_facts:
            grouped.setdefault(fact.owner_key, []).append(fact)
        return {
            key: tuple(sorted(values, key=self._operation_fact_sort_key))
            for key, values in grouped.items()
        }

    def _external_operation_facts(
        self,
        operation_facts: Sequence[AvailableOperationFact],
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
    ) -> tuple[AvailableOperationFact, ...]:
        return tuple(
            sorted(
                (
                    fact
                    for fact in operation_facts
                    if fact.owner_key not in node_by_key
                    and str(fact.direction_role or "") == "OUTBOUND"
                ),
                key=self._operation_fact_sort_key,
            )
        )

    def _operation_symbol(self, fact: AvailableOperationFact) -> str:
        qualified = str(fact.owner_qualified_name or "").strip()
        if qualified:
            parts = [part for part in qualified.split(".") if part]
            if len(parts) >= 2:
                return ".".join(parts[-2:])
            return parts[-1] if parts else qualified
        identity = fact.interface_identity or fact.operation_identity
        if identity:
            return str(identity)
        method = normalize_http_method(fact.method)
        route = normalize_route(fact.normalized_route)
        return " ".join(part for part in (method, route) if part) or fact.owner_node_id

    def _operation_evidence(self, item: Any) -> FlowToolEvidence:
        return FlowToolEvidence(
            path=getattr(item, "relative_path", None),
            lineStart=getattr(item, "line_start", None),
            lineEnd=getattr(item, "line_end", None),
            excerpt=getattr(item, "excerpt", None),
        )

    def _operation_trigger(self, operation_facts: Sequence[AvailableOperationFact]) -> FlowToolTrigger | None:
        for fact in sorted(operation_facts, key=self._operation_fact_sort_key):
            transport = normalize_transport_kind(fact.transport_kind)
            if not transport:
                continue
            return FlowToolTrigger(
                kind=transport,
                method=normalize_http_method(fact.method),
                route=normalize_route(fact.normalized_route),
                topic=self._clean(fact.topic),
                schedule=self._clean(fact.schedule),
                interfaceMethod=self._clean(fact.interface_identity or fact.operation_identity),
            )
        return None

    def _operation_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[int, str, str, str, str, str]:
        direction_rank = {"OUTBOUND": 0, "INBOUND": 1, "SUPPORTING": 2}.get(str(fact.direction_role or ""), 3)
        return (
            direction_rank,
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
            fact.operation_identity or fact.interface_identity or "",
            fact.structural_owner,
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
        family_narration_service: FamilyNarrationService | None = None,
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
        self.family_narration_service = family_narration_service or FamilyNarrationService()
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.cancel_event = cancel_event
        self.audit_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.prompt_budget_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.pipeline_records: Deque[Dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))

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
                prepared = self._prepare_family_narration(
                    request,
                    narrative_plan,
                    effective_plan,
                    deadline_at,
                    flow_index=flow_index,
                    source=source,
                    entrypoint=entrypoint,
                    requested_language=request.answerLanguage,
                    resolved_language=resolved_language,
                )
                text = self._answer_one_family(
                    prepared.narration_segments,
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
            except GroundedNarrationError as exc:
                validation_errors = exc.metadata.get("validationErrors") if isinstance(exc.metadata, Mapping) else None
                if validation_errors:
                    self._record_validation_errors(
                        entrypoint=entrypoint,
                        attempt_count=2,
                        errors=[str(error) for error in validation_errors],
                        segment_index=exc.metadata.get("batchIndex"),
                    )
                diagnostics.append(technical_diagnostic(exc.stage, source, entrypoint, exc.diagnostic_code))
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
        if diagnostics and any(
            item.code in {FLOW_FAMILY_SEGMENT_CONTEXT_BUDGET_EXCEEDED, "HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED"}
            or str(item.code).endswith("_CONTEXT_BUDGET_EXCEEDED")
            for item in diagnostics
        ):
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
    ) -> tuple[NarrationSegment, ...]:
        prepared = self._prepare_family_narration(
            request,
            flow,
            plan,
            time.monotonic() + self.request_deadline_seconds,
            flow_index=flow_index,
            source=source,
            entrypoint=entrypoint,
            requested_language=request.answerLanguage,
            resolved_language=plan.response_language,
        )
        return prepared.narration_segments

    def _prepare_family_narration(
        self,
        request: KnowledgeQueryRequest,
        flow: EntrypointFlow | FlowFamily | FlowNarrativePlan,
        plan: QueryRetrievalPlan,
        deadline_at: float,
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        requested_language: str | None,
        resolved_language: str,
    ) -> FamilyNarrationPreparation:
        if self._cancelled() or self._remaining_seconds(deadline_at) <= self.min_call_timeout_seconds:
            raise HumanAnswerDeadlineExceeded()

        def complete_grounding(
            llm_input: Mapping[str, Any],
            validation_errors: Sequence[str] | None,
            stage: HumanNarrationStage,
            batch_index: int | None,
            batch_count: int | None,
        ) -> FlowExplanationProviderResult:
            return self._complete_with_deadline(
                llm_input,
                deadline_at,
                validation_errors=validation_errors,
                flow_index=flow_index,
                source=source,
                entrypoint=entrypoint,
                attempt_count=2 if validation_errors else 1,
                requested_language=requested_language,
                resolved_language=resolved_language,
                segment_index=batch_index,
                segment_count=batch_count,
                provider_stage=stage.value,
            )

        prepared = self.family_narration_service.prepare(
            request=request,
            flow=flow,
            plan=plan,
            response_language=resolved_language,
            renderer=self.renderer,
            budget_estimator=self.budget_estimator,
            complete_grounding=complete_grounding,
        )
        self.pipeline_records.append(
            {
                "flowIndex": flow_index,
                "source": source,
                "entrypoint": entrypoint,
                **dict(prepared.metrics),
            }
        )
        return prepared

    def _answer_one_family(
        self,
        segments: Sequence[NarrationSegment],
        deadline_at: float,
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        requested_language: str | None,
        resolved_language: str,
    ) -> str:
        payloads: list[Mapping[str, Any]] = []
        accepted_segments: list[NarrationSegment] = []
        for segment in segments:
            for accepted_segment, payload in self._answer_one_segment_adaptive(
                segment,
                deadline_at,
                flow_index=flow_index,
                source=source,
                entrypoint=entrypoint,
                requested_language=requested_language,
                resolved_language=resolved_language,
            ):
                accepted_segments.append(accepted_segment)
                payloads.append(payload)
        final_segments = tuple(
            replace(segment, index=index, total=len(accepted_segments), terminal=(index == len(accepted_segments)))
            for index, segment in enumerate(accepted_segments, start=1)
        )
        final_payloads = list(payloads)
        for index, segment in enumerate(final_segments):
            if segment.terminal == accepted_segments[index].terminal:
                continue
            final_payloads[index] = dict(final_payloads[index])
        self._validate_family_segment_coverage(final_payloads, final_segments)
        return self._stitch_segment_payloads(final_payloads)

    def _answer_one_segment_adaptive(
        self,
        segment: NarrationSegment,
        deadline_at: float,
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        requested_language: str | None,
        resolved_language: str,
    ) -> tuple[tuple[NarrationSegment, Mapping[str, Any]], ...]:
        try:
            payload = self._answer_one_segment(
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
            return ((segment, payload),)
        except HumanAnswerOutputCapacitySplit:
            child_segments = self._split_narration_segment(segment)
            if not child_segments:
                raise
            results: list[tuple[NarrationSegment, Mapping[str, Any]]] = []
            for child in child_segments:
                results.extend(
                    self._answer_one_segment_adaptive(
                        child,
                        deadline_at,
                        flow_index=flow_index,
                        source=source,
                        entrypoint=entrypoint,
                        requested_language=requested_language,
                        resolved_language=resolved_language,
                    )
                )
            return tuple(results)

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
                provider_stage=HumanNarrationStage.NARRATION_LLM.value,
            )
            try:
                return self._validate_payload(result.raw_text, resolved_language, llm_input)
            except HumanAnswerContractViolation as exc:
                self._record_validation_errors(entrypoint=entrypoint, attempt_count=attempt_count, errors=exc.errors, segment_index=segment_index)
                if attempt_count == 1 and self._is_output_capacity_failure(exc, result):
                    self._mark_latest_audit_attempt_type(entrypoint, segment_index, "OUTPUT_CAPACITY_SPLIT")
                    raise HumanAnswerOutputCapacitySplit(exc) from exc
                if attempt_count == 1:
                    validation_errors = exc.errors
                    continue
                raise HumanAnswerRepairExhausted("human answer repair failed validation") from exc
        raise HumanAnswerRepairExhausted("human answer repair failed validation")

    def _split_narration_segment(self, segment: NarrationSegment) -> tuple[NarrationSegment, ...]:
        atoms = tuple(segment.atoms or ())
        if len(atoms) > 1:
            midpoint = max(1, len(atoms) // 2)
            if midpoint >= len(atoms):
                midpoint = len(atoms) - 1
            groups = (atoms[:midpoint], atoms[midpoint:])
        elif len(atoms) == 1 and len(atoms[0].claims) > 1:
            atom = atoms[0]
            claims = tuple(sorted(atom.claims, key=lambda item: item.canonical_claim_order))
            midpoint = max(1, len(claims) // 2)
            left = replace(
                atom,
                ref=f"{atom.ref}p1",
                atom_kind="VERIFIED_CLAIM",
                descriptor=replace(atom.descriptor, terminal_role=None),
                claims=claims[:midpoint],
            )
            right = replace(
                atom,
                ref=f"{atom.ref}p2",
                claims=claims[midpoint:],
            )
            groups = ((left,), (right,))
        else:
            return ()
        root = segment.llm_input.get("familyRoot") if isinstance(segment.llm_input.get("familyRoot"), dict) else {}
        planner = NarrationSegmentPlanner(renderer=self.renderer, budget_estimator=self.budget_estimator)
        result: list[NarrationSegment] = []
        for index, group in enumerate(groups, start=1):
            if not group:
                continue
            child_terminal = bool(segment.terminal and index == len(groups))
            llm_input = planner._segment_input(
                str(segment.llm_input.get("originalQuestion") or ""),
                str(segment.llm_input.get("responseLanguage") or "en"),
                str(root.get("source") or ""),
                str(root.get("entrypoint") or ""),
                tuple(group),
                tuple(atoms),
                index=index,
                total=len(groups),
                terminal=child_terminal,
            )
            estimate = self.budget_estimator.estimate(self.renderer.render(llm_input))
            minimum_output = self._minimum_valid_output_tokens(llm_input)
            result.append(
                NarrationSegment(
                    llm_input=llm_input,
                    index=index,
                    total=len(groups),
                    terminal=child_terminal,
                    atoms=tuple(group),
                    budget_metrics={
                        "renderedInputTokens": int(estimate.rendered_input_tokens),
                        "availableInputTokens": max(0, int(estimate.context_tokens) - int(estimate.reserved_output_tokens) - int(estimate.fixed_framing_reserve_tokens)),
                        "minimumValidOutputTokens": int(minimum_output),
                        "reservedOutputTokens": int(estimate.reserved_output_tokens),
                        "atomCount": len(group),
                        "claimCount": sum(len(atom.claims) for atom in group),
                    },
                )
            )
        return tuple(result)

    def _is_output_capacity_failure(self, exc: HumanAnswerContractViolation, result: FlowExplanationProviderResult) -> bool:
        if not isinstance(exc, HumanAnswerMalformedResponse):
            return False
        done_reason = str(getattr(result, "done_reason", "") or "").lower()
        if done_reason in {"length", "num_predict", "max_tokens", "max_tokens_reached"}:
            return True
        try:
            return (
                result.eval_count is not None
                and result.reserved_output_tokens is not None
                and int(result.reserved_output_tokens) > 0
                and int(result.eval_count) >= int(result.reserved_output_tokens)
            )
        except Exception:
            return False

    def _mark_latest_audit_attempt_type(self, entrypoint: str, segment_index: int | None, attempt_type: str) -> None:
        for record in reversed(self.audit_records):
            if record.get("flowEntrypoint") == entrypoint and record.get("segmentIndex") == segment_index:
                record["attemptType"] = attempt_type
                return

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
        provider_stage: str | None = None,
    ) -> FlowExplanationProviderResult:
        if self._cancelled() or self._remaining_seconds(deadline_at) <= self.min_call_timeout_seconds:
            raise HumanAnswerDeadlineExceeded()
        assert_final_narration_prompt_safe(llm_input)
        prompt = self.renderer.render(llm_input, validation_errors)
        self._record_prompt_budget_check(
            prompt,
            llm_input,
            flow_index=flow_index,
            source=source,
            entrypoint=entrypoint,
            attempt="REPAIR" if validation_errors else "INITIAL",
            validation_errors=validation_errors,
            segment_index=segment_index,
            segment_count=segment_count,
            provider_stage=provider_stage,
        )
        remaining = self._remaining_seconds(deadline_at)
        started = time.perf_counter()
        try:
            result = self.provider.complete(llm_input, validation_errors=validation_errors, timeout_seconds=remaining)
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise HumanAnswerDeadlineExceeded() from exc
        except Exception as exc:
            raise HumanAnswerProviderUnavailable(str(type(exc).__name__)) from exc
        duration_ms = (time.perf_counter() - started) * 1000
        result = self._with_provider_call_metadata(result, duration_ms=duration_ms)
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
            provider_stage=provider_stage,
            duration_ms=duration_ms,
            remaining_deadline_before_call=remaining,
            remaining_deadline_after_call=self._remaining_seconds(deadline_at),
            result=result,
        )
        return result

    def _with_provider_call_metadata(self, result: Any, *, duration_ms: float) -> FlowExplanationProviderResult:
        raw_text = str(getattr(result, "raw_text", result if isinstance(result, str) else "") or "")
        prompt_char_length = int(getattr(result, "prompt_char_length", 0) or 0)
        return FlowExplanationProviderResult(
            raw_text=raw_text,
            prompt_char_length=prompt_char_length,
            done_reason=getattr(result, "done_reason", None),
            prompt_eval_count=getattr(result, "prompt_eval_count", None),
            eval_count=getattr(result, "eval_count", None),
            reserved_output_tokens=self.reserved_output_tokens,
            duration_ms=duration_ms,
        )

    def _record_prompt_budget_check(
        self,
        prompt: str,
        llm_input: Mapping[str, Any],
        *,
        flow_index: int,
        source: str,
        entrypoint: str,
        attempt: str,
        validation_errors: Sequence[str] | None = None,
        segment_index: int | None = None,
        segment_count: int | None = None,
        provider_stage: str | None = None,
    ) -> PromptBudgetEstimate:
        estimate = self.budget_estimator.estimate(prompt)
        minimum_output_tokens = self._minimum_valid_output_tokens(llm_input)
        budget_payload = self._prompt_budget_payload(estimate, attempt, minimum_output_tokens=minimum_output_tokens)
        prompt_kind = str(llm_input.get("promptKind") or "LEGACY_NARRATION")
        self.prompt_budget_records.append(
            {
                "flowIndex": int(flow_index),
                "segmentIndex": segment_index,
                "segmentCount": segment_count,
                "source": source,
                "entrypoint": entrypoint,
                "providerStage": provider_stage,
                "promptKind": prompt_kind,
                "attempt": attempt,
                "attemptType": "VALIDATION_REPAIR" if attempt == "REPAIR" else "INITIAL",
                "validationErrors": [str(error) for error in validation_errors or ()],
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
        if int(estimate.reserved_output_tokens) > 0 and minimum_output_tokens > int(estimate.reserved_output_tokens):
            raise HumanAnswerContextBudgetExceeded("The minimum valid model response exceeds the reserved output budget.")
        return estimate

    def _prompt_budget_payload(self, estimate: PromptBudgetEstimate, attempt: str, *, minimum_output_tokens: int) -> Dict[str, Any]:
        available_input_tokens = max(
            0,
            int(estimate.context_tokens)
            - int(estimate.reserved_output_tokens)
            - int(estimate.fixed_framing_reserve_tokens),
        )
        return {
            "attempt": attempt,
            "renderedInputTokens": int(estimate.rendered_input_tokens),
            "availableInputTokens": available_input_tokens,
            "reservedOutputTokens": int(estimate.reserved_output_tokens),
            "minimumValidOutputTokens": int(minimum_output_tokens),
            "fixedFramingReserveTokens": int(estimate.fixed_framing_reserve_tokens),
            "totalRequiredTokens": int(estimate.total_required_tokens),
            "contextTokens": int(estimate.context_tokens),
            "fits": bool(estimate.fits),
            "inputFits": bool(estimate.fits),
            "outputFits": bool(int(estimate.reserved_output_tokens) <= 0 or int(minimum_output_tokens) <= int(estimate.reserved_output_tokens)),
        }

    def _minimum_valid_output_tokens(self, llm_input: Mapping[str, Any]) -> int:
        prompt_kind = str(llm_input.get("promptKind") or "").upper()
        if prompt_kind == "GROUNDING":
            coverage = llm_input.get("coverageContract") if isinstance(llm_input.get("coverageContract"), dict) else {}
            evidence_refs = [
                str(ref)
                for ref in (coverage.get("evidenceRefs") or [])
                if str(ref).strip()
            ]
            payload = {
                "claims": [],
                "processedEvidence": [
                    {"evidenceRef": ref, "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}
                    for ref in evidence_refs
                ],
            }
            return self.budget_estimator.estimate_text_tokens(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True))
        if prompt_kind == "FINAL_NARRATION":
            coverage = llm_input.get("coverageContract") if isinstance(llm_input.get("coverageContract"), dict) else {}
            refs = [
                str(ref)
                for ref in (coverage.get("requiredAtomRefs") or coverage.get("canonicalAtomRefs") or [])
                if str(ref).strip()
            ]
            atom_certainty = coverage.get("atomCertainty") if isinstance(coverage.get("atomCertainty"), dict) else {}
            segment = llm_input.get("segment") if isinstance(llm_input.get("segment"), dict) else {}
            payload = {
                "steps": [
                    {
                        "atomRefs": [ref],
                        "certainty": str(atom_certainty.get(ref) or "VERIFIED"),
                        "text": "x",
                    }
                    for ref in refs
                ],
                "result": "x" if bool(segment.get("terminal")) else None,
            }
            return self.budget_estimator.estimate_text_tokens(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True))
        return 0

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
            for ref in (coverage.get("canonicalAtomRefs") or [])
            if str(ref).strip() and re.search(rf"(?<![\w$]){re.escape(str(ref))}(?![\w$])", normalized)
        ]
        if leaked_local_refs:
            raise HumanAnswerContractViolation(["Response must not expose internal graph refs, node ids, transition refs, evidence refs, or analysis ids."])
        if "**" in normalized or "`" in normalized:
            raise HumanAnswerContractViolation(["Response must be escaped plain text without Markdown bold or backticks."])
        if re.search(r"(?i)\b(?:node|transition|boundary|evidence|unit|atom)\s+[a-z]\d+\b", normalized):
            raise HumanAnswerContractViolation(["Response must not expose internal graph refs, node ids, transition refs, evidence refs, or analysis ids."])
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
        canonical_refs = [
            str(item)
            for item in (coverage.get("canonicalAtomRefs") or [])
            if str(item).strip()
        ]
        required_refs = [
            str(item)
            for item in (coverage.get("requiredAtomRefs") or [])
            if str(item).strip()
        ]
        required_atoms = required_refs or canonical_refs
        ref_order = {ref: index for index, ref in enumerate(canonical_refs)}
        atom_items = list(llm_input.get("narrationAtoms", []) or [])
        facts = {
            str(item.get("atomRef")): {
                **item,
                "ref": item.get("atomRef"),
                "type": "gap" if str(item.get("certainty") or "") in {"UNVERIFIED", "AMBIGUOUS"} else "atom",
            }
            for item in atom_items
            if isinstance(item, dict) and str(item.get("atomRef") or "").strip()
        }
        if not canonical_refs:
            errors.append("No canonical atom refs were supplied for validation.")
            return errors

        seen_refs: list[str] = []
        last_index = -1
        for step_index, step in enumerate(steps, start=1):
            if not isinstance(step, dict):
                errors.append(f"steps[{step_index}] must be an object.")
                continue
            step_extra = sorted(str(key) for key in step if key not in {"atomRefs", "certainty", "text"})
            if step_extra:
                errors.append(f"steps[{step_index}] must not include extra fields.")
            text = step.get("text")
            if not isinstance(text, str) or not text.strip():
                errors.append(f"steps[{step_index}].text must be a non-empty string.")
            certainty = str(step.get("certainty") or "VERIFIED").strip().upper().replace("-", "_").replace(" ", "_")
            invalid_certainty = certainty not in {"VERIFIED", "UNVERIFIED", "AMBIGUOUS"}
            refs = step.get("atomRefs")
            if not isinstance(refs, list) or not refs:
                errors.append(f"steps[{step_index}].atomRefs must be a non-empty array.")
                continue
            step_ref_values: list[str] = []
            for ref_value in refs:
                ref = str(ref_value or "").strip()
                if not ref:
                    errors.append(f"steps[{step_index}].atomRefs contains a blank ref.")
                    continue
                if ref not in ref_order:
                    errors.append(f"steps[{step_index}] contains a foreign atomRef.")
                    continue
                if ref in seen_refs or ref in step_ref_values:
                    errors.append(f"atomRef {ref} is duplicated.")
                    continue
                current_index = ref_order[ref]
                if current_index < last_index:
                    errors.append(f"atomRef {ref} is out of canonical order.")
                last_index = max(last_index, current_index)
                step_ref_values.append(ref)
            seen_refs.extend(step_ref_values)
            if invalid_certainty and not any(facts.get(ref, {}).get("type") == "gap" for ref in step_ref_values):
                certainty = "VERIFIED"
            elif invalid_certainty:
                errors.append(f"steps[{step_index}].certainty must be VERIFIED, UNVERIFIED, or AMBIGUOUS.")
            errors.extend(self._validate_step_certainty(step_index, certainty, step_ref_values, facts))
            errors.extend(self._validate_step_ownership(step_index, step_ref_values, facts))

        missing_atoms = [ref for ref in required_atoms if ref not in seen_refs]
        if missing_atoms:
            errors.append(f"Missing narration atoms: {', '.join(missing_atoms)}.")
        return errors

    def _validate_family_segment_coverage(
        self,
        payloads: Sequence[Mapping[str, Any]],
        segments: Sequence[NarrationSegment],
    ) -> None:
        expected: list[str] = []
        covered: list[str] = []
        result_segments = 0
        for payload, segment in zip(payloads, segments):
            coverage = segment.llm_input.get("coverageContract") if isinstance(segment.llm_input.get("coverageContract"), dict) else {}
            expected.extend(
                str(ref)
                for ref in (coverage.get("requiredAtomRefs") or coverage.get("canonicalAtomRefs") or [])
                if str(ref).strip()
            )
            for step in payload.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                refs = step.get("atomRefs")
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
                "narrationAtoms": llm_input.get("narrationAtoms"),
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
        provider_stage: str | None = None,
        duration_ms: float | None = None,
        remaining_deadline_before_call: float | None = None,
        remaining_deadline_after_call: float | None = None,
        result: FlowExplanationProviderResult | None = None,
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
                "providerStage": provider_stage,
                "promptKind": "GROUNDING" if provider_stage == HumanNarrationStage.GROUNDING_LLM.value else "FINAL_NARRATION",
                "segmentIndex": segment_index,
                "segmentCount": segment_count,
                "attemptCount": attempt_count,
                "attemptType": "VALIDATION_REPAIR" if attempt_count > 1 else "INITIAL",
                "requestedLanguage": str(requested_language or "AUTO"),
                "resolvedLanguage": resolved_language,
                "durationMs": round(float(duration_ms or 0.0), 3),
                "remainingDeadlineBeforeCall": round(float(remaining_deadline_before_call or 0.0), 3),
                "remainingDeadlineAfterCall": round(float(remaining_deadline_after_call or 0.0), 3),
                "doneReason": getattr(result, "done_reason", None),
                "promptEvalCount": getattr(result, "prompt_eval_count", None),
                "evalCount": getattr(result, "eval_count", None),
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
        return FlowExplanationProviderResult(
            raw_text=response_text,
            prompt_char_length=len(prompt),
            done_reason=raw.get("done_reason") or raw.get("doneReason"),
            prompt_eval_count=raw.get("prompt_eval_count") or raw.get("promptEvalCount"),
            eval_count=raw.get("eval_count") or raw.get("evalCount"),
            reserved_output_tokens=self.reserved_output_tokens,
        )

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
