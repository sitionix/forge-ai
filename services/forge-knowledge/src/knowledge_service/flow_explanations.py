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
            "Return strict JSON only with exactly this shape: "
            "{\"steps\":[{\"factRefs\":[\"n1\"],\"text\":\"human-readable grounded step\"}],"
            "\"result\":\"human-readable observable result\"}.\n"
            "Write all natural-language prose in the supplied responseLanguage. "
            "Preserve code identifiers, class names, method names, routes, constants, topic names, and quoted code literals exactly as supplied.\n"
            "Directly answer the question using only the supplied verified flow facts.\n"
            "Use the supplied orderedFacts and coverageContract as the authoritative execution order and grounding contract.\n"
            "Every factRefs value must exist in coverageContract.canonicalFactRefs. Cover every required node, transition, and boundary exactly once, in canonical order.\n"
            "The suggestedStepPlan groups all required refs in canonical order. Prefer copying each suggestedStepPlan factRefs array exactly and writing only the step text for it.\n"
            "Use as many concise steps as needed. Low-level boundary refs still need coverage; group adjacent boundary refs with their owning node when the text explains them, or use short boundary-only steps.\n"
            "Each step text must explain only the facts named by that step's factRefs. Do not cite a producer and a downstream consumer in the same step unless the same factRefs explicitly connect them.\n"
            "The tree kind fields are internal classifier labels for grounding only. Never copy labels such as UNRESOLVED_CALL, EXTERNAL_CALL, METHOD, HTTP_ENDPOINT, KAFKA_LISTENER, or ENTRYPOINT into the answer.\n"
            "Start with the trigger and entrypoint when available, including the HTTP method and route only when they are supplied.\n"
            "The final public answer will be numbered by the server from your steps. Do not include Markdown, backticks, raw JSON, graph refs, evidence refs, node ids, or transition ids in step text.\n"
            "Explain branches as branches; do not fabricate a sequence between sibling branches.\n"
            "Mention exact class or method symbols where they help identify the code.\n"
            "Explain what data arrives, what the code does, what it calls next, and grounded validation, persistence, or side effects when supplied.\n"
            "When validation facts include thresholds, null or empty checks, exception classes, or error messages, include the exact grounded detail.\n"
            "End with the observable result: returned response or status, persisted data, emitted event, or external side effect when supplied.\n"
            "If the supplied facts do not include a return value, status, persistence, event, or side effect, state that the verified facts do not provide that detail.\n"
            "Keep all step and result prose as escaped plain text inside JSON strings.\n"
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


class FlowProjectionBuilder:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def to_tool_response(self, request: KnowledgeQueryRequest, execution: Any) -> KnowledgeQueryToolContextResponse:
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            trees=[self._tree(flow) for flow in tuple(execution.flows or ())],
            diagnostics=self._diagnostics(execution),
        )

    def human_llm_input(self, request: KnowledgeQueryRequest, flow: EntrypointFlow, plan: QueryRetrievalPlan) -> Dict[str, Any]:
        tree = self._tree(flow)
        ordered_facts, coverage_contract = self._ordered_facts(flow)
        return {
            "originalQuestion": request.queryText,
            "detectedLanguage": plan.detected_language,
            "responseLanguage": plan.response_language,
            "intent": plan.effective_intent,
            "source": tree.source,
            "entrypoint": tree.entrypoint.symbol,
            "tree": self._tree_item_dict(tree.entrypoint),
            "orderedFacts": ordered_facts,
            "coverageContract": coverage_contract,
            "suggestedStepPlan": self._suggested_step_plan(ordered_facts),
        }

    def flow_answer_identity(self, flow: EntrypointFlow) -> tuple[str, str]:
        return str(flow.key.source_id or ""), self._symbol(flow.entrypoint)

    def _tree_item_dict(self, item: FlowToolTreeItem) -> Dict[str, Any]:
        data = item.dict(exclude_none=True)
        children = [
            self._tree_item_dict(child)
            for child in item.children
        ]
        data["children"] = children
        return data

    def _tree(self, flow: EntrypointFlow) -> FlowToolTree:
        node_by_key = {self._node_key(node): node for node in flow.nodes}
        evidence_by_node: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        evidence_by_edge: Dict[tuple[str, str], List[FlowGraphEvidence]] = {}
        for item in flow.evidence:
            if item.edge_id:
                evidence_by_edge.setdefault((item.source_id, item.edge_id), []).append(item)
            elif item.node_id:
                evidence_by_node.setdefault((item.source_id, item.node_id), []).append(item)
        outgoing: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        for edge in sorted(flow.transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            outgoing.setdefault(self._from_key(edge), []).append(edge)
        boundaries: Dict[tuple[str, str, str], List[FlowGraphEdge]] = {}
        for edge in sorted(flow.boundary_transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            boundaries.setdefault(self._from_key(edge), []).append(edge)

        root_key = self._node_key(flow.entrypoint)
        root = self._node_item(flow.entrypoint, evidence_by_node.get((flow.entrypoint.source_id, flow.entrypoint.node_id), []))
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
                frame["item"].children.append(self._node_item(target, child_evidence, cycle=True))
                continue
            if target_key in rendered:
                frame["item"].children.append(self._node_item(target, child_evidence, shared=True))
                continue
            child = self._node_item(target, child_evidence)
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
        return FlowToolTree(source=str(flow.key.source_id or ""), entrypoint=root)

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

        node_ref_by_key: Dict[tuple[str, str, str], str] = {}
        transition_ref_by_key: Dict[tuple[str, str], str] = {}
        boundary_ref_by_key: Dict[tuple[str, str], str] = {}
        node_count = transition_count = boundary_count = 0
        for event_type, key, _metadata in events:
            if event_type == "node" and key not in node_ref_by_key:
                node_count += 1
                node_ref_by_key[key] = f"n{node_count}"
            elif event_type == "transition" and key not in transition_ref_by_key:
                transition_count += 1
                transition_ref_by_key[key] = f"t{transition_count}"
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
        for event_type, key, metadata in events:
            if event_type == "node":
                node_item = node_by_key.get(key)
                if node_item is None:
                    continue
                ref = node_ref_by_key[key]
                if any(item.get("ref") == ref for item in facts):
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
                if any(item.get("ref") == ref for item in facts):
                    continue
                fact = self._transition_fact(
                    ref,
                    edge,
                    node_by_key,
                    node_ref_by_key,
                    evidence_by_edge.get(self._edge_key(edge), []),
                )
            else:
                edge = metadata["edge"]
                ref = boundary_ref_by_key[key]
                if any(item.get("ref") == ref for item in facts):
                    continue
                fact = self._boundary_fact(
                    ref,
                    edge,
                    node_by_key,
                    node_ref_by_key,
                    evidence_by_edge.get(self._edge_key(edge), []),
                )
            facts.append(fact)
            canonical_refs.append(str(fact["ref"]))
        return facts, {
            "canonicalFactRefs": canonical_refs,
            "nodeRefs": [fact["ref"] for fact in facts if fact.get("type") == "node"],
            "transitionRefs": [fact["ref"] for fact in facts if fact.get("type") == "transition"],
            "boundaryRefs": [fact["ref"] for fact in facts if fact.get("type") == "boundary"],
        }

    def _suggested_step_plan(self, facts: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
        facts_by_ref = {str(fact.get("ref")): fact for fact in facts if str(fact.get("ref") or "").strip()}
        groups: list[Dict[str, Any]] = []
        current: Dict[str, Any] | None = None

        def current_node_refs(group: Mapping[str, Any] | None) -> set[str]:
            if not group:
                return set()
            return {
                ref
                for ref in group.get("factRefs", [])
                if facts_by_ref.get(ref, {}).get("type") == "node"
            }

        def append_summary(group: Dict[str, Any], summary: str) -> None:
            cleaned = summary.strip()
            if cleaned:
                group.setdefault("summaries", []).append(cleaned)

        for fact in facts:
            ref = str(fact.get("ref") or "").strip()
            fact_type = str(fact.get("type") or "").strip()
            if not ref or not fact_type:
                continue
            if fact_type == "node":
                summary = str(fact.get("displaySymbol") or fact.get("nodeIdentity", {}).get("nodeId") or ref)
                if current is not None:
                    groups.append(current)
                current = {"factRefs": [ref], "summaries": [summary]}
            elif fact_type == "transition":
                summary = f"{fact.get('fromSymbol') or fact.get('fromRef') or ''} -> {fact.get('toSymbol') or fact.get('toRef') or ''}".strip()
            else:
                summary = f"{fact.get('fromSymbol') or fact.get('fromRef') or ''} boundary {fact.get('displaySymbol') or fact.get('target') or ''}".strip()
            if fact_type != "node":
                owner_refs = {str(fact.get("fromRef") or "")}
                if fact_type == "transition":
                    owner_refs.add(str(fact.get("toRef") or ""))
                if current is not None and current_node_refs(current) and current_node_refs(current) & owner_refs:
                    current["factRefs"].append(ref)
                    append_summary(current, summary)
                else:
                    if current is not None:
                        groups.append(current)
                    current = {"factRefs": [ref], "summaries": [summary]}
        if current is not None:
            groups.append(current)
        return [
            {
                "factRefs": list(group.get("factRefs") or []),
                "summary": "; ".join(str(item) for item in group.get("summaries", []) if str(item).strip()),
            }
            for group in groups
            if group.get("factRefs")
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
            "nodeIdentity": {
                "source": node.source_id,
                "graphRevision": node.graph_revision or node.graph_id,
                "nodeId": node.node_id,
            },
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
        return self._without_none({
            "ref": ref,
            "type": "transition",
            "edgeIdentity": {
                "source": edge.source_id,
                "graphRevision": edge.graph_revision or edge.graph_id,
                "edgeId": edge.edge_id,
            },
            "edgeType": edge.edge_type,
            "resolutionStatus": edge.resolution_status,
            "fromRef": node_ref_by_key.get(from_key),
            "toRef": node_ref_by_key.get(to_key) if to_key is not None else None,
            "fromSymbol": self._symbol(from_node) if from_node else edge.from_node_id,
            "toSymbol": self._symbol(to_node) if to_node else edge.to_node_id,
            "crossSource": bool(to_node is not None and to_node.source_id != edge.source_id),
            "evidence": [self._evidence(item).dict(exclude_none=True) for item in evidence],
        })

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
                result[key] = self._without_none(item)
            else:
                result[key] = item
        return result

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
        request_deadline_seconds: float = DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        projector: FlowProjectionBuilder | None = None,
        renderer: HumanAnswerPromptRenderer | None = None,
        text_validator: HumanAnswerTextValidator | None = None,
        provider_name: str | None = None,
        provider_model: str | None = None,
        cancel_event: Any | None = None,
        audit_max_records: int = 200,
    ) -> None:
        self.provider = provider
        self.max_prompt_chars = max(1, int(max_prompt_chars or DEFAULT_GENERATIVE_CONTEXT_TOKENS * 4))
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS))
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.projector = projector or FlowProjectionBuilder()
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
        context_budget_failures = 0
        if plan is None:
            raise HumanAnswerGenerationFailed("query retrieval plan is required")
        effective_plan = plan
        resolved_language = effective_plan.response_language
        for flow in flows:
            source, entrypoint = self.projector.flow_answer_identity(flow)
            try:
                if self._cancelled():
                    raise HumanAnswerDeadlineExceeded()
                llm_input = self.projector.human_llm_input(request, flow, effective_plan)
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
            except HumanAnswerContextBudgetExceeded:
                context_budget_failures += 1
                diagnostics.append(self._context_budget_diagnostic(source, entrypoint))
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
        if context_budget_failures:
            raise HumanAnswerContextBudgetExceeded("The complete grounded flow exceeds the available model context.")
        raise HumanAnswerGenerationFailed("no grounded flow answers")

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
                return self._validate_text(result.raw_text, resolved_language, llm_input)
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

    def _context_budget_diagnostic(self, source: str, entrypoint: str) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
            message="The complete grounded flow exceeds the available model context.",
            severity="ERROR",
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
            raise HumanAnswerContextBudgetExceeded("The complete grounded flow exceeds the available model context.")
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

    def _validate_text(self, raw_text: str, language: str, llm_input: Mapping[str, Any]) -> str:
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
        forbidden = ("nodeRef", "transitionRef", "boundaryRef", "evidenceRef", "flowIndex", "analysis-graph-")
        if any(token in normalized for token in forbidden):
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
        return normalized

    def _validate_structured_answer_payload(self, payload: Mapping[str, Any], llm_input: Mapping[str, Any]) -> List[str]:
        errors: List[str] = []
        allowed_keys = {"steps", "result"}
        extra_keys = sorted(str(key) for key in payload if key not in allowed_keys)
        if extra_keys:
            errors.append(f"Response must not include extra fields: {', '.join(extra_keys)}.")
        steps = payload.get("steps")
        if not isinstance(steps, list) or not steps:
            errors.append("Response JSON must contain a non-empty steps array.")
            return errors
        result = payload.get("result")
        if not isinstance(result, str) or not result.strip():
            errors.append("Response JSON must contain a non-empty result string.")

        coverage = llm_input.get("coverageContract") if isinstance(llm_input.get("coverageContract"), dict) else {}
        canonical_refs = [str(item) for item in coverage.get("canonicalFactRefs", []) if str(item).strip()]
        required_nodes = [str(item) for item in coverage.get("nodeRefs", []) if str(item).strip()]
        required_transitions = [str(item) for item in coverage.get("transitionRefs", []) if str(item).strip()]
        required_boundaries = [str(item) for item in coverage.get("boundaryRefs", []) if str(item).strip()]
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
            step_extra = sorted(str(key) for key in step if key not in {"factRefs", "text"})
            if step_extra:
                errors.append(f"steps[{step_index}] must not include extra fields: {', '.join(step_extra)}.")
            text = step.get("text")
            if not isinstance(text, str) or not text.strip():
                errors.append(f"steps[{step_index}].text must be a non-empty string.")
            refs = step.get("factRefs")
            if not isinstance(refs, list) or not refs:
                errors.append(f"steps[{step_index}].factRefs must be a non-empty array.")
                continue
            step_ref_values: list[str] = []
            for ref_value in refs:
                ref = str(ref_value or "").strip()
                if not ref:
                    errors.append(f"steps[{step_index}].factRefs contains a blank ref.")
                    continue
                if ref not in ref_order:
                    errors.append(f"steps[{step_index}] contains foreign factRef {ref}.")
                    continue
                if ref in seen_refs or ref in step_ref_values:
                    errors.append(f"factRef {ref} is duplicated.")
                    continue
                current_index = ref_order[ref]
                if current_index < last_index:
                    errors.append(f"factRef {ref} is out of canonical order.")
                last_index = max(last_index, current_index)
                step_ref_values.append(ref)
            seen_refs.extend(step_ref_values)
            errors.extend(self._validate_step_ownership(step_index, step_ref_values, facts))

        missing_nodes = [ref for ref in required_nodes if ref not in seen_refs]
        missing_transitions = [ref for ref in required_transitions if ref not in seen_refs]
        missing_boundaries = [ref for ref in required_boundaries if ref not in seen_refs]
        if missing_nodes:
            errors.append(f"Missing executable flow node facts: {', '.join(missing_nodes)}.")
        if missing_transitions:
            errors.append(f"Missing resolved transition facts: {', '.join(missing_transitions)}.")
        if missing_boundaries:
            errors.append(f"Missing boundary facts: {', '.join(missing_boundaries)}.")
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
            if fact_type == "boundary" and node_refs:
                owner = str(fact.get("fromRef") or "")
                if owner not in node_refs:
                    errors.append(f"steps[{step_index}] claims boundary {ref} without its owning node.")
            if fact_type == "transition" and "EVENT" in str(fact.get("edgeType") or "").upper():
                adjacent = {str(fact.get("fromRef") or ""), str(fact.get("toRef") or "")}
                if len(node_refs & adjacent) > 1:
                    errors.append(f"steps[{step_index}] merges asynchronous producer and consumer facts for {ref}; explain them in distinct steps.")
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
        return re.sub(r"^\s*(?:\d+(?:\.\d+)*|[A-Za-zА-Яа-яІіЇїЄєҐґ])[\.)]\s+", "", value).strip()

    def _unsupported_claim_errors(self, text: str, llm_input: Mapping[str, Any]) -> List[str]:
        rendered_facts = json.dumps(
            {
                "tree": llm_input.get("tree"),
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
        speculative = ("likely", "probably", "maybe", "assuming", "presumably")
        lowered = text.lower()
        if any(token in lowered for token in speculative):
            errors.append("Response must not speculate beyond supplied facts.")
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
