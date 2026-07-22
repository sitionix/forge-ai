from __future__ import annotations

import hashlib
import json
import re
import time
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any, Callable, Dict, Mapping, Sequence

from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.entrypoint_kinds import tree_kind_for_entrypoint, trigger_kind_for_entrypoint
from knowledge_service.flow_boundary_classifier import FLOW_BOUNDARY_CLASSIFIER, FlowBoundaryClassifier
from knowledge_service.flow_family import FlowFamily
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.flow_narrative import FlowGapVerificationStatus, FlowNarrativePartKind, FlowNarrativePlan
from knowledge_service.knowledge_query_schema import KnowledgeQueryDiagnostic
from knowledge_service.operation_facts import (
    AvailableOperationFact,
    normalize_http_method,
    normalize_route,
    normalize_transport_kind,
)


class HumanNarrationStage(str, Enum):
    GROUNDING_SPLIT = "GROUNDING_SPLIT"
    GROUNDING_BATCHING = "GROUNDING_BATCHING"
    GROUNDING_LLM = "GROUNDING_LLM"
    GROUNDING_VALIDATION = "GROUNDING_VALIDATION"
    CLAIM_ASSEMBLY = "CLAIM_ASSEMBLY"
    NARRATION_SEGMENTATION = "NARRATION_SEGMENTATION"
    NARRATION_LLM = "NARRATION_LLM"
    NARRATION_VALIDATION = "NARRATION_VALIDATION"
    FAMILY_STITCHING = "FAMILY_STITCHING"


class GroundedNarrationError(Exception):
    def __init__(
        self,
        stage: HumanNarrationStage,
        message: str,
        *,
        diagnostic_code: str | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> None:
        self.stage = stage
        self.diagnostic_code = diagnostic_code or f"HUMAN_NARRATION_{stage.value}_FAILED"
        self.metadata = dict(metadata or {})
        super().__init__(message)


@dataclass(frozen=True)
class NarrativeFactDescriptor:
    ref: str
    fact_kind: str
    certainty: str = "VERIFIED"
    source: str | None = None
    symbol: str | None = None
    from_source: str | None = None
    from_symbol: str | None = None
    to_source: str | None = None
    to_symbol: str | None = None
    transition_kind: str | None = None
    resolution_status: str | None = None
    transport_kind: str | None = None
    method: str | None = None
    route: str | None = None
    topic: str | None = None
    schedule: str | None = None
    interface_identity: str | None = None
    operation_identity: str | None = None
    target_service_identity: str | None = None
    branch_parent: str | None = None
    incoming_transition: str | None = None
    outgoing_transitions: tuple[str, ...] = ()
    boundary_kind: str | None = None
    boundary_reason: str | None = None
    target: str | None = None
    trigger: Mapping[str, Any] | None = None
    gap_verification_status: str | None = None
    terminal_role: str | None = None
    cross_source: bool = False

    def to_prompt_dict(self, *, ref_key: str = "unitRef") -> Dict[str, Any]:
        payload = _without_none(
            {
                ref_key: self.ref,
                "type": self.fact_kind,
                "certainty": self.certainty,
                "source": self.source,
                "symbol": self.symbol,
                "fromSource": self.from_source,
                "fromSymbol": self.from_symbol,
                "toSource": self.to_source,
                "toSymbol": self.to_symbol,
                "transitionKind": self.transition_kind,
                "resolutionStatus": self.resolution_status,
                "transportKind": self.transport_kind,
                "method": self.method,
                "route": self.route,
                "topic": self.topic,
                "schedule": self.schedule,
                "interfaceIdentity": self.interface_identity,
                "operationIdentity": self.operation_identity,
                "targetServiceIdentity": self.target_service_identity,
                "branchParent": self.branch_parent,
                "incomingTransition": self.incoming_transition,
                "outgoingTransitions": list(self.outgoing_transitions) or None,
                "boundaryKind": self.boundary_kind,
                "boundaryReason": self.boundary_reason,
                "target": self.target,
                "trigger": dict(self.trigger) if isinstance(self.trigger, Mapping) else None,
                "gapVerificationStatus": self.gap_verification_status,
                "terminalRole": self.terminal_role,
                "crossSource": True if self.cross_source else None,
            }
        )
        return payload


@dataclass(frozen=True)
class NarrativeFactUnit:
    descriptor: NarrativeFactDescriptor
    plan_part_order: int
    fragment_order: int
    fact_order: int

    @property
    def ref(self) -> str:
        return self.descriptor.ref


@dataclass(frozen=True)
class EvidenceWorkItem:
    work_ref: str
    narrative_plan_ref: str
    fragment_ref: str
    unit_ref: str
    original_evidence_owner: Mapping[str, Any]
    evidence_source: str
    source: str | None
    path: str | None
    line_start: int | None
    line_end: int | None
    exact_text: str
    order: int
    utf8_hash: str


@dataclass(frozen=True)
class EvidenceSlice:
    slice_ref: str
    work_ref: str
    unit_ref: str
    source: str | None
    path: str | None
    line_start: int | None
    line_end: int | None
    text: str
    evidence_order: int
    slice_order: int
    offset_start: int
    offset_end: int
    utf8_hash: str
    original_utf8_hash: str

    def to_prompt_dict(self, evidence_ref: str) -> Dict[str, Any]:
        return _without_none(
            {
                "evidenceRef": evidence_ref,
                "unitRef": self.unit_ref,
                "source": self.source,
                "path": self.path,
                "lineStart": self.line_start,
                "lineEnd": self.line_end,
                "text": self.text,
            }
        )


@dataclass(frozen=True)
class NarrativeProjection:
    narrative_plan_ref: str
    source: str
    entrypoint: str
    units: tuple[NarrativeFactUnit, ...]
    evidence_work_items: tuple[EvidenceWorkItem, ...]

    @property
    def descriptors_by_ref(self) -> Dict[str, NarrativeFactDescriptor]:
        return {unit.ref: unit.descriptor for unit in self.units}


@dataclass(frozen=True)
class GroundingBatch:
    index: int
    total: int
    llm_input: Mapping[str, Any]
    evidence_ref_to_slice_ref: Mapping[str, str]
    slice_ref_to_evidence_ref: Mapping[str, str]


@dataclass(frozen=True)
class GroundingBatchPlan:
    batches: tuple[GroundingBatch, ...]
    serialization_count: int
    planning_ms: float


@dataclass(frozen=True)
class GroundedNarrativeClaim:
    claim_ref: str
    unit_ref: str
    certainty: str
    text: str
    evidence_slice_refs: tuple[str, ...]
    canonical_unit_order: int
    canonical_claim_order: int


@dataclass(frozen=True)
class NarrationAtom:
    ref: str
    atom_kind: str
    unit_ref: str
    certainty: str
    descriptor: NarrativeFactDescriptor
    claims: tuple[GroundedNarrativeClaim, ...] = ()
    canonical_order: int = 0

    def to_prompt_dict(self) -> Dict[str, Any]:
        unit_payload = self.descriptor.to_prompt_dict(ref_key="unitRef")
        unit_payload.pop("unitRef", None)
        return _without_none(
            {
                "atomRef": self.ref,
                "atomKind": self.atom_kind,
                "certainty": self.certainty,
                "unit": unit_payload,
                "claims": [
                    {
                        "claimRef": claim.claim_ref,
                        "certainty": claim.certainty,
                        "text": claim.text,
                    }
                    for claim in self.claims
                ] or None,
            }
        )


@dataclass(frozen=True)
class NarrationSegment:
    llm_input: Mapping[str, Any]
    index: int
    total: int
    terminal: bool
    atoms: tuple[NarrationAtom, ...]


@dataclass(frozen=True)
class NarrationSegmentPlan:
    segments: tuple[NarrationSegment, ...]
    serialization_count: int
    planning_ms: float


@dataclass(frozen=True)
class FamilyNarrationPreparation:
    projection: NarrativeProjection
    evidence_slices: tuple[EvidenceSlice, ...]
    grounding_batches: tuple[GroundingBatch, ...]
    grounded_claims: tuple[GroundedNarrativeClaim, ...]
    narration_atoms: tuple[NarrationAtom, ...]
    narration_segments: tuple[NarrationSegment, ...]
    metrics: Mapping[str, Any]


class NarrativeFactProjector:
    def __init__(self, boundary_classifier: FlowBoundaryClassifier | None = None) -> None:
        self.boundary_classifier = boundary_classifier or FLOW_BOUNDARY_CLASSIFIER

    def project(self, request: Any, flow: Any, plan: Any) -> NarrativeProjection:
        if isinstance(flow, FlowNarrativePlan):
            return self._project_plan(request, flow, plan)
        return self._project_single_flow(
            request,
            flow,
            tuple(getattr(flow, "operation_facts", ()) or ()),
            plan,
            narrative_plan_ref=self._flow_key(flow),
            fragment_ref=self._flow_key(flow),
            ref_prefix="",
            plan_part_order=1,
            fragment_order=1,
        )

    def identity(self, flow: Any) -> tuple[str, str]:
        if isinstance(flow, FlowNarrativePlan):
            fragments = flow.fragments
            if not fragments:
                return "", ""
            return str(fragments[0].source_id or ""), self._symbol(fragments[0].root)
        return str(getattr(getattr(flow, "key", None), "source_id", "") or ""), self._symbol(flow.entrypoint)

    def _project_plan(self, request: Any, narrative_plan: FlowNarrativePlan, plan: Any) -> NarrativeProjection:
        all_units: list[NarrativeFactUnit] = []
        all_work: list[EvidenceWorkItem] = []
        fragment_index = 0
        gap_index = 0
        plan_ref = narrative_plan.key or "narrative-plan"
        source, entrypoint = self.identity(narrative_plan)
        for part_index, part in enumerate(narrative_plan.parts, start=1):
            if part.kind is FlowNarrativePartKind.VERIFIED_FRAGMENT and part.fragment is not None:
                fragment_index += 1
                projection = self._project_single_flow(
                    request,
                    part.fragment.family,
                    part.fragment.operation_facts,
                    plan,
                    narrative_plan_ref=plan_ref,
                    fragment_ref=part.fragment.key,
                    ref_prefix=f"p{fragment_index}_",
                    plan_part_order=part_index,
                    fragment_order=fragment_index,
                )
                all_units.extend(projection.units)
                all_work.extend(projection.evidence_work_items)
                continue
            if part.gap is not None:
                gap_index += 1
                certainty = "AMBIGUOUS" if part.gap.verification_status is FlowGapVerificationStatus.AMBIGUOUS else "UNVERIFIED"
                descriptor = NarrativeFactDescriptor(
                    ref=f"g{gap_index}",
                    fact_kind="gap",
                    certainty=certainty,
                    from_source=part.gap.from_source,
                    from_symbol=part.gap.from_symbol,
                    to_source=part.gap.to_source,
                    to_symbol=part.gap.to_symbol,
                    transport_kind=part.gap.transport_kind,
                    method=part.gap.method,
                    route=part.gap.route,
                    operation_identity=part.gap.operation_identity,
                    gap_verification_status=part.gap.verification_status.value,
                )
                all_units.append(
                    NarrativeFactUnit(
                        descriptor=descriptor,
                        plan_part_order=part_index,
                        fragment_order=fragment_index,
                        fact_order=len(all_units) + 1,
                    )
                )
        return self._with_terminal_role(
            NarrativeProjection(
                narrative_plan_ref=plan_ref,
                source=source,
                entrypoint=entrypoint,
                units=tuple(all_units),
                evidence_work_items=tuple(
                    replace(item, order=index)
                    for index, item in enumerate(sorted(all_work, key=lambda value: value.order), start=1)
                ),
            )
        )

    def _project_single_flow(
        self,
        request: Any,
        flow: Any,
        operation_facts: Sequence[AvailableOperationFact],
        plan: Any,
        *,
        narrative_plan_ref: str,
        fragment_ref: str,
        ref_prefix: str,
        plan_part_order: int,
        fragment_order: int,
    ) -> NarrativeProjection:
        node_by_key = {self._node_key(node): node for node in flow.nodes}
        operation_facts_by_node = self._operation_facts_by_node(operation_facts)
        evidence_by_node: Dict[tuple[str, str], list[FlowGraphEvidence]] = {}
        evidence_by_edge: Dict[tuple[str, str], list[FlowGraphEvidence]] = {}
        for item in tuple(getattr(flow, "evidence", ()) or ()):
            if item.edge_id:
                evidence_by_edge.setdefault((item.source_id, item.edge_id), []).append(item)
            elif item.node_id:
                evidence_by_node.setdefault((item.source_id, item.node_id), []).append(item)

        outgoing: Dict[tuple[str, str, str], list[FlowGraphEdge]] = {}
        for edge in sorted(flow.transitions, key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            outgoing.setdefault(self._from_key(edge), []).append(edge)
        boundaries: Dict[tuple[str, str, str], list[FlowGraphEdge]] = {}
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
                events.append(("boundary", edge_key, {"edge": replace(edge, boundary_reason=edge.boundary_reason or "CURRENT_TARGET_NODE_MISSING"), "parent": frame["node_key"]}))
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
        for edge in sorted(tuple(getattr(flow, "supporting_transitions", ()) or ()), key=lambda item: self._edge_sort_key(item, evidence_by_edge)):
            events.append(("supporting", self._edge_key(edge), {"edge": edge}))
        for fact in self._external_operation_facts(operation_facts, node_by_key):
            events.append(("operation", fact.structural_owner, {"fact": fact}))

        node_ref_by_key: Dict[tuple[str, str, str], str] = {}
        transition_ref_by_key: Dict[tuple[str, str], str] = {}
        supporting_ref_by_key: Dict[tuple[str, str], str] = {}
        boundary_ref_by_key: Dict[tuple[str, str], str] = {}
        operation_ref_by_key: Dict[str, str] = {}
        node_count = transition_count = supporting_count = boundary_count = operation_count = 0
        for event_type, key, _metadata in events:
            if event_type == "node" and key not in node_ref_by_key:
                node_count += 1
                node_ref_by_key[key] = f"{ref_prefix}n{node_count}"
            elif event_type == "transition" and key not in transition_ref_by_key:
                transition_count += 1
                transition_ref_by_key[key] = f"{ref_prefix}t{transition_count}"
            elif event_type == "supporting" and key not in supporting_ref_by_key:
                supporting_count += 1
                supporting_ref_by_key[key] = f"{ref_prefix}s{supporting_count}"
            elif event_type == "boundary" and key not in boundary_ref_by_key:
                boundary_count += 1
                boundary_ref_by_key[key] = f"{ref_prefix}b{boundary_count}"
            elif event_type == "operation" and str(key) not in operation_ref_by_key:
                operation_count += 1
                operation_ref_by_key[str(key)] = f"{ref_prefix}o{operation_count}"

        outgoing_refs_by_node: Dict[tuple[str, str, str], list[str]] = {}
        for edge in flow.transitions:
            ref = transition_ref_by_key.get(self._edge_key(edge))
            if ref:
                outgoing_refs_by_node.setdefault(self._from_key(edge), []).append(ref)
        for edge in flow.boundary_transitions:
            ref = boundary_ref_by_key.get(self._edge_key(edge))
            if ref:
                outgoing_refs_by_node.setdefault(self._from_key(edge), []).append(ref)

        units: list[NarrativeFactUnit] = []
        work_items: list[EvidenceWorkItem] = []
        seen_refs: set[str] = set()
        for event_type, key, metadata in events:
            descriptor: NarrativeFactDescriptor | None
            evidence_items: tuple[FlowGraphEvidence, ...] = ()
            free_text_items: tuple[tuple[str, Mapping[str, Any], str | None, str | None, int | None, int | None, str], ...] = ()
            if event_type == "node":
                node = node_by_key.get(key)
                if node is None:
                    continue
                ref = node_ref_by_key[key]
                if ref in seen_refs:
                    continue
                incoming = metadata.get("incoming")
                parent = metadata.get("parent")
                operation_node_facts = operation_facts_by_node.get(key, ())
                descriptor = self._node_descriptor(
                    ref,
                    node,
                    incoming_transition=transition_ref_by_key.get(incoming) if incoming else None,
                    branch_parent=node_ref_by_key.get(parent) if parent else None,
                    outgoing_transitions=tuple(outgoing_refs_by_node.get(key, ())),
                    operation_facts=operation_node_facts,
                )
                evidence_items = tuple(evidence_by_node.get((node.source_id, node.node_id), ()))
                if _clean(node.summary):
                    free_text_items = (
                        (
                            "NODE_DESCRIPTION",
                            {"ownerKind": "NODE", "ownerSourceId": node.source_id, "nodeId": node.node_id},
                            node.source_id,
                            node.relative_path,
                            node.line_start,
                            node.line_end,
                            str(node.summary),
                        ),
                    )
                for op_fact in operation_node_facts:
                    work_items.extend(self._operation_evidence_work_items(op_fact, narrative_plan_ref, fragment_ref, ref, len(work_items)))
            elif event_type == "transition":
                edge = metadata["edge"]
                ref = transition_ref_by_key[key]
                if ref in seen_refs:
                    continue
                descriptor = self._transition_descriptor(ref, edge, node_by_key, node_ref_by_key)
                evidence_items = tuple(evidence_by_edge.get(self._edge_key(edge), ()))
            elif event_type == "supporting":
                edge = metadata["edge"]
                ref = supporting_ref_by_key[key]
                if ref in seen_refs:
                    continue
                descriptor = self._supporting_descriptor(ref, edge, node_by_key, node_ref_by_key)
                evidence_items = tuple(evidence_by_edge.get(self._edge_key(edge), ()))
            elif event_type == "operation":
                operation = metadata["fact"]
                ref = operation_ref_by_key[str(key)]
                if ref in seen_refs:
                    continue
                descriptor = self._operation_descriptor(ref, operation)
                work_items.extend(self._operation_evidence_work_items(operation, narrative_plan_ref, fragment_ref, ref, len(work_items)))
                evidence_items = ()
            else:
                edge = metadata["edge"]
                ref = boundary_ref_by_key[key]
                if ref in seen_refs:
                    continue
                descriptor = self._boundary_descriptor(ref, edge, node_by_key, node_ref_by_key)
                evidence_items = tuple(evidence_by_edge.get(self._edge_key(edge), ()))
                if _clean(edge.boundary_reason):
                    free_text_items = (
                        (
                            "BOUNDARY_DESCRIPTION",
                            {"ownerKind": "EDGE", "ownerSourceId": edge.source_id, "edgeId": edge.edge_id},
                            edge.source_id,
                            None,
                            None,
                            None,
                            str(edge.boundary_reason),
                        ),
                    )
            if descriptor is None:
                continue
            fact_order = len(units) + 1
            units.append(
                NarrativeFactUnit(
                    descriptor=descriptor,
                    plan_part_order=plan_part_order,
                    fragment_order=fragment_order,
                    fact_order=fact_order,
                )
            )
            seen_refs.add(descriptor.ref)
            for item in evidence_items:
                work_items.append(
                    self._graph_evidence_work_item(
                        item,
                        narrative_plan_ref=narrative_plan_ref,
                        fragment_ref=fragment_ref,
                        unit_ref=descriptor.ref,
                        order=len(work_items) + 1,
                    )
                )
            for evidence_source, owner, source, path, line_start, line_end, text in free_text_items:
                work_items.append(
                    self._free_text_work_item(
                        evidence_source,
                        owner,
                        source,
                        path,
                        line_start,
                        line_end,
                        text,
                        narrative_plan_ref=narrative_plan_ref,
                        fragment_ref=fragment_ref,
                        unit_ref=descriptor.ref,
                        order=len(work_items) + 1,
                    )
                )
        return self._with_terminal_role(
            NarrativeProjection(
                narrative_plan_ref=narrative_plan_ref,
                source=str(getattr(getattr(flow, "key", None), "source_id", "") or ""),
                entrypoint=self._symbol(flow.entrypoint),
                units=tuple(units),
                evidence_work_items=tuple(work_items),
            )
        )

    def _with_terminal_role(self, projection: NarrativeProjection) -> NarrativeProjection:
        if not projection.units:
            return projection
        units = list(projection.units)
        last = units[-1]
        units[-1] = replace(last, descriptor=replace(last.descriptor, terminal_role="TERMINAL"))
        return replace(projection, units=tuple(units))

    def _node_descriptor(
        self,
        ref: str,
        node: FlowGraphNode,
        *,
        incoming_transition: str | None,
        branch_parent: str | None,
        outgoing_transitions: Sequence[str],
        operation_facts: Sequence[AvailableOperationFact],
    ) -> NarrativeFactDescriptor:
        trigger = self._trigger(node, operation_facts)
        return NarrativeFactDescriptor(
            ref=ref,
            fact_kind="node",
            source=node.source_id,
            symbol=self._symbol(node),
            transition_kind=self._node_kind(node),
            trigger=trigger,
            incoming_transition=incoming_transition,
            branch_parent=branch_parent,
            outgoing_transitions=tuple(outgoing_transitions),
            transport_kind=trigger.get("kind") if trigger else None,
            method=trigger.get("method") if trigger else None,
            route=trigger.get("route") if trigger else None,
            topic=trigger.get("topic") if trigger else None,
            schedule=trigger.get("schedule") if trigger else None,
            interface_identity=trigger.get("interfaceMethod") if trigger else None,
        )

    def _transition_descriptor(
        self,
        ref: str,
        edge: FlowGraphEdge,
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        node_ref_by_key: Mapping[tuple[str, str, str], str],
    ) -> NarrativeFactDescriptor:
        from_key = self._from_key(edge)
        to_key = self._to_key(edge)
        from_node = node_by_key.get(from_key)
        to_node = node_by_key.get(to_key) if to_key is not None else None
        from_source = from_node.source_id if from_node is not None else edge.source_id
        to_source = to_node.source_id if to_node is not None else (edge.to_source_id or edge.source_id)
        connector = self._connector_metadata(edge.metadata if isinstance(edge.metadata, dict) else {})
        return NarrativeFactDescriptor(
            ref=ref,
            fact_kind="transition",
            from_source=from_source,
            to_source=to_source,
            from_symbol=self._symbol(from_node) if from_node else edge.from_node_id,
            to_symbol=self._symbol(to_node) if to_node else edge.to_node_id,
            transition_kind=edge.edge_type,
            resolution_status=edge.resolution_status,
            transport_kind=connector.get("kind") if connector else None,
            method=connector.get("method") if connector else None,
            route=connector.get("route") if connector else None,
            interface_identity=connector.get("interfaceMethod") if connector else None,
            incoming_transition=None,
            branch_parent=node_ref_by_key.get(from_key),
            cross_source=from_source != to_source,
        )

    def _supporting_descriptor(
        self,
        ref: str,
        edge: FlowGraphEdge,
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        node_ref_by_key: Mapping[tuple[str, str, str], str],
    ) -> NarrativeFactDescriptor:
        from_key = self._from_key(edge)
        to_key = self._to_key(edge)
        from_node = node_by_key.get(from_key)
        to_node = node_by_key.get(to_key) if to_key is not None else None
        return NarrativeFactDescriptor(
            ref=ref,
            fact_kind="supporting",
            from_source=from_node.source_id if from_node is not None else edge.source_id,
            to_source=to_node.source_id if to_node is not None else (edge.to_source_id or edge.source_id),
            from_symbol=self._symbol(from_node) if from_node else edge.from_node_id,
            to_symbol=self._symbol(to_node) if to_node else edge.to_node_id,
            transition_kind=edge.edge_type,
            resolution_status=edge.resolution_status,
            branch_parent=node_ref_by_key.get(from_key),
        )

    def _operation_descriptor(self, ref: str, fact: AvailableOperationFact) -> NarrativeFactDescriptor:
        return NarrativeFactDescriptor(
            ref=ref,
            fact_kind="operation",
            source=fact.owner_source_id,
            symbol=self._operation_symbol(fact),
            transition_kind=str(fact.direction_role or "OPERATION"),
            transport_kind=normalize_transport_kind(fact.transport_kind),
            method=normalize_http_method(fact.method),
            route=normalize_route(fact.normalized_route),
            topic=_clean(fact.topic),
            schedule=_clean(fact.schedule),
            operation_identity=fact.operation_identity,
            interface_identity=fact.interface_identity,
            target_service_identity=fact.target_service_identity,
            trigger=self._operation_trigger((fact,)),
        )

    def _boundary_descriptor(
        self,
        ref: str,
        edge: FlowGraphEdge,
        node_by_key: Mapping[tuple[str, str, str], FlowGraphNode],
        node_ref_by_key: Mapping[tuple[str, str, str], str],
    ) -> NarrativeFactDescriptor:
        from_key = self._from_key(edge)
        from_node = node_by_key.get(from_key)
        projection = self.boundary_classifier.project(edge)
        symbol = self._boundary_symbol(edge, projection.target)
        return NarrativeFactDescriptor(
            ref=ref,
            fact_kind="boundary",
            from_source=from_node.source_id if from_node is not None else edge.source_id,
            from_symbol=self._symbol(from_node) if from_node else edge.from_node_id,
            transition_kind=edge.edge_type,
            resolution_status=projection.resolution_status,
            boundary_kind=projection.kind.value,
            target=projection.target or symbol,
            symbol=symbol,
            branch_parent=node_ref_by_key.get(from_key),
        )

    def _graph_evidence_work_item(
        self,
        item: FlowGraphEvidence,
        *,
        narrative_plan_ref: str,
        fragment_ref: str,
        unit_ref: str,
        order: int,
    ) -> EvidenceWorkItem:
        exact_text = str(item.text or "")
        owner = {
            "ownerKind": item.owner_kind or ("EDGE" if item.edge_id else "NODE" if item.node_id else "UNKNOWN"),
            "ownerSourceId": item.owner_source_id or item.source_id,
            "nodeId": item.owner_node_id or item.node_id,
            "edgeId": item.owner_edge_id or item.edge_id,
            "evidenceId": item.evidence_id,
            "graphId": item.graph_id,
            "graphRevision": item.graph_revision,
        }
        return EvidenceWorkItem(
            work_ref=f"w{order}",
            narrative_plan_ref=narrative_plan_ref,
            fragment_ref=fragment_ref,
            unit_ref=unit_ref,
            original_evidence_owner=_without_none(owner),
            evidence_source="GRAPH_EVIDENCE",
            source=item.source_id,
            path=item.relative_path,
            line_start=item.line_start,
            line_end=item.line_end,
            exact_text=exact_text,
            order=order,
            utf8_hash=_sha256(exact_text),
        )

    def _operation_evidence_work_items(
        self,
        fact: AvailableOperationFact,
        narrative_plan_ref: str,
        fragment_ref: str,
        unit_ref: str,
        current_count: int,
    ) -> list[EvidenceWorkItem]:
        result: list[EvidenceWorkItem] = []
        for item in tuple(fact.evidence or ()):
            exact_text = str(getattr(item, "excerpt", "") or "")
            order = current_count + len(result) + 1
            result.append(
                EvidenceWorkItem(
                    work_ref=f"w{order}",
                    narrative_plan_ref=narrative_plan_ref,
                    fragment_ref=fragment_ref,
                    unit_ref=unit_ref,
                    original_evidence_owner=_without_none(
                        {
                            "ownerKind": "OPERATION_FACT",
                            "ownerSourceId": fact.owner_source_id,
                            "nodeId": fact.owner_node_id,
                            "edgeId": fact.owner_edge_id,
                            "structuralOwner": fact.structural_owner,
                        }
                    ),
                    evidence_source="OPERATION_FACT_EVIDENCE",
                    source=getattr(item, "source_id", fact.source_id),
                    path=getattr(item, "relative_path", None),
                    line_start=getattr(item, "line_start", None),
                    line_end=getattr(item, "line_end", None),
                    exact_text=exact_text,
                    order=order,
                    utf8_hash=_sha256(exact_text),
                )
            )
        return result

    def _free_text_work_item(
        self,
        evidence_source: str,
        owner: Mapping[str, Any],
        source: str | None,
        path: str | None,
        line_start: int | None,
        line_end: int | None,
        text: str,
        *,
        narrative_plan_ref: str,
        fragment_ref: str,
        unit_ref: str,
        order: int,
    ) -> EvidenceWorkItem:
        exact_text = str(text or "")
        return EvidenceWorkItem(
            work_ref=f"w{order}",
            narrative_plan_ref=narrative_plan_ref,
            fragment_ref=fragment_ref,
            unit_ref=unit_ref,
            original_evidence_owner=dict(owner),
            evidence_source=evidence_source,
            source=source,
            path=path,
            line_start=line_start,
            line_end=line_end,
            exact_text=exact_text,
            order=order,
            utf8_hash=_sha256(exact_text),
        )

    def _sorted_child_edges(
        self,
        node_key: tuple[str, str, str],
        outgoing: Mapping[tuple[str, str, str], Sequence[FlowGraphEdge]],
        boundaries: Mapping[tuple[str, str, str], Sequence[FlowGraphEdge]],
        evidence_by_edge: Mapping[tuple[str, str], Sequence[FlowGraphEvidence]],
    ) -> list[FlowGraphEdge]:
        return sorted(
            [*outgoing.get(node_key, ()), *boundaries.get(node_key, ())],
            key=lambda item: self._edge_sort_key(item, evidence_by_edge),
        )

    def _operation_facts_by_node(
        self,
        operation_facts: Sequence[AvailableOperationFact],
    ) -> Dict[tuple[str, str, str], tuple[AvailableOperationFact, ...]]:
        grouped: Dict[tuple[str, str, str], list[AvailableOperationFact]] = {}
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

    def _symbol(self, node: FlowGraphNode | None) -> str:
        if node is None:
            return ""
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
            return tree_kind_for_entrypoint(node.entrypoint_kind)
        if node.node_kind == "CALLABLE":
            return "METHOD"
        return node.node_kind

    def _trigger(
        self,
        node: FlowGraphNode,
        operation_facts: Sequence[AvailableOperationFact] = (),
    ) -> Dict[str, Any] | None:
        operation_trigger = self._operation_trigger(operation_facts)
        if operation_trigger is not None and not node.entrypoint:
            return operation_trigger
        if not node.entrypoint:
            return None
        trigger_kind = trigger_kind_for_entrypoint(node.entrypoint_kind)
        if trigger_kind is None:
            return operation_trigger
        return _without_none(
            {
                "kind": trigger_kind,
                "method": _clean(node.entrypoint_http_method),
                "route": _clean(node.entrypoint_route),
                "topic": _clean(node.entrypoint_topic),
                "schedule": _clean(node.entrypoint_schedule),
                "interfaceMethod": _clean(node.entrypoint_interface_method),
            }
        )

    def _operation_trigger(self, operation_facts: Sequence[AvailableOperationFact]) -> Dict[str, Any] | None:
        for fact in sorted(operation_facts, key=self._operation_fact_sort_key):
            transport = normalize_transport_kind(fact.transport_kind)
            if not transport:
                continue
            return _without_none(
                {
                    "kind": transport,
                    "method": normalize_http_method(fact.method),
                    "route": normalize_route(fact.normalized_route),
                    "topic": _clean(fact.topic),
                    "schedule": _clean(fact.schedule),
                    "interfaceMethod": _clean(fact.interface_identity or fact.operation_identity),
                }
            )
        return None

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

    def _connector_metadata(self, metadata: Mapping[str, Any]) -> Dict[str, Any] | None:
        connector = _without_none(
            {
                "kind": _clean(metadata.get("connectorKind") if isinstance(metadata.get("connectorKind"), str) else None),
                "method": _clean(metadata.get("httpMethod") if isinstance(metadata.get("httpMethod"), str) else None),
                "route": _clean(metadata.get("routeTemplate") if isinstance(metadata.get("routeTemplate"), str) else None),
                "interfaceMethod": _clean(metadata.get("targetInterfaceMethod") if isinstance(metadata.get("targetInterfaceMethod"), str) else None),
            }
        )
        return connector or None

    def _boundary_symbol(self, edge: FlowGraphEdge, projected_target: str | None) -> str | None:
        target = edge.unresolved_target or {}
        if not isinstance(target, dict):
            return self._compact_symbol(projected_target)
        for key in ("qualifiedName", "target", "displayName", "label", "symbol"):
            value = _clean(target.get(key) if isinstance(target.get(key), str) else None)
            if value:
                return self._compact_symbol(value)
        name = _clean(target.get("name") if isinstance(target.get("name"), str) else None)
        for key in ("interfaceType", "receiverTypeHint", "targetTypeText"):
            owner = _clean(target.get(key) if isinstance(target.get(key), str) else None)
            if owner and name and owner != name and self._looks_like_symbol(owner):
                return f"{self._compact_symbol(owner)}.{name}"
        return self._compact_symbol(projected_target or name)

    def _compact_symbol(self, value: str | None) -> str | None:
        normalized = _clean(value)
        if not normalized:
            return None
        parts = [part for part in normalized.split(".") if part]
        if len(parts) >= 2:
            return ".".join(parts[-2:])
        return normalized

    def _looks_like_symbol(self, value: str) -> bool:
        return re.match(r"^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$", value) is not None

    def _flow_key(self, flow: Any) -> str:
        key = getattr(flow, "key", None)
        return ":".join(
            str(item or "")
            for item in (
                getattr(key, "source_id", None),
                getattr(key, "graph_revision", None),
                getattr(key, "entrypoint_node_id", None),
            )
        )


class EvidenceWorkPlanner:
    def plan(self, projection: NarrativeProjection) -> tuple[EvidenceWorkItem, ...]:
        work_items = tuple(sorted(projection.evidence_work_items, key=lambda item: item.order))
        return tuple(replace(item, order=index, work_ref=f"w{index}") for index, item in enumerate(work_items, start=1))


class LosslessEvidenceSplitter:
    def __init__(
        self,
        *,
        renderer: Any,
        budget_estimator: Any,
        descriptors_by_ref: Mapping[str, NarrativeFactDescriptor],
        original_question: str,
        response_language: str,
    ) -> None:
        self.renderer = renderer
        self.budget_estimator = budget_estimator
        self.descriptors_by_ref = descriptors_by_ref
        self.original_question = original_question
        self.response_language = response_language

    def split(self, work_items: Sequence[EvidenceWorkItem]) -> tuple[EvidenceSlice, ...]:
        slices: list[EvidenceSlice] = []
        for item in sorted(work_items, key=lambda value: value.order):
            text = item.exact_text
            if text == "":
                continue
            parts = self._split_text(item, text)
            joined = "".join(parts)
            if joined != text or _sha256(joined) != item.utf8_hash:
                raise GroundedNarrationError(
                    HumanNarrationStage.GROUNDING_SPLIT,
                    "Evidence splitting failed lossless hash closure.",
                    diagnostic_code="LOSSLESS_EVIDENCE_HASH_MISMATCH",
                    metadata={"workRef": item.work_ref},
                )
            offset = 0
            for part_index, part in enumerate(parts, start=1):
                next_offset = offset + len(part)
                slices.append(
                    EvidenceSlice(
                        slice_ref=f"es{len(slices) + 1}",
                        work_ref=item.work_ref,
                        unit_ref=item.unit_ref,
                        source=item.source,
                        path=item.path,
                        line_start=item.line_start,
                        line_end=item.line_end,
                        text=part,
                        evidence_order=item.order,
                        slice_order=part_index,
                        offset_start=offset,
                        offset_end=next_offset,
                        utf8_hash=_sha256(part),
                        original_utf8_hash=item.utf8_hash,
                    )
                )
                offset = next_offset
        return tuple(slices)

    def _split_text(self, item: EvidenceWorkItem, text: str) -> list[str]:
        if self._fits(item, text):
            return [text]
        if not text:
            return []
        lines = text.splitlines(keepends=True)
        parts: list[str] = []
        line_offset = 0
        while line_offset < len(lines):
            best_line_end = self._largest_fitting_line_end(item, lines, line_offset)
            if best_line_end > line_offset:
                parts.append("".join(lines[line_offset:best_line_end]))
                line_offset = best_line_end
                continue
            oversized_line = lines[line_offset]
            char_offset = 0
            while char_offset < len(oversized_line):
                best_char_end = self._largest_fitting_codepoint_end(item, oversized_line, char_offset)
                if best_char_end <= char_offset:
                    raise GroundedNarrationError(
                        HumanNarrationStage.GROUNDING_SPLIT,
                        "Configured context cannot fit grounding framing plus one Unicode code point of evidence.",
                        diagnostic_code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                        metadata={"workRef": item.work_ref},
                    )
                parts.append(oversized_line[char_offset:best_char_end])
                char_offset = best_char_end
            line_offset += 1
        return parts

    def _largest_fitting_line_end(self, item: EvidenceWorkItem, lines: Sequence[str], offset: int) -> int:
        low = offset + 1
        high = len(lines)
        best = offset
        while low <= high:
            mid = (low + high) // 2
            candidate = "".join(lines[offset:mid])
            if self._fits(item, candidate):
                best = mid
                low = mid + 1
            else:
                high = mid - 1
        return best

    def _largest_fitting_codepoint_end(self, item: EvidenceWorkItem, text: str, offset: int) -> int:
        low = offset + 1
        high = len(text)
        best = offset
        while low <= high:
            mid = (low + high) // 2
            if self._fits(item, text[offset:mid]):
                best = mid
                low = mid + 1
            else:
                high = mid - 1
        return best

    def _fits(self, item: EvidenceWorkItem, text: str) -> bool:
        descriptor = self.descriptors_by_ref.get(item.unit_ref)
        if descriptor is None:
            raise GroundedNarrationError(
                HumanNarrationStage.GROUNDING_SPLIT,
                "Evidence work item references a missing narrative fact descriptor.",
                diagnostic_code="GROUNDING_EVIDENCE_OWNER_MISSING",
                metadata={"workRef": item.work_ref, "unitRef": item.unit_ref},
            )
        llm_input = {
            "promptKind": "GROUNDING",
            "originalQuestion": self.original_question,
            "responseLanguage": self.response_language,
            "units": [descriptor.to_prompt_dict()],
            "evidenceSlices": [
                _without_none(
                    {
                        "evidenceRef": "e1",
                        "unitRef": item.unit_ref,
                        "source": item.source,
                        "path": item.path,
                        "lineStart": item.line_start,
                        "lineEnd": item.line_end,
                        "text": text,
                    }
                )
            ],
            "coverageContract": {"evidenceRefs": ["e1"], "unitRefs": [item.unit_ref]},
        }
        return bool(self.budget_estimator.estimate(self.renderer.render(llm_input)).fits)


class GroundingBatchPlanner:
    def __init__(self, *, renderer: Any, budget_estimator: Any) -> None:
        self.renderer = renderer
        self.budget_estimator = budget_estimator

    def plan(
        self,
        *,
        original_question: str,
        response_language: str,
        descriptors_by_ref: Mapping[str, NarrativeFactDescriptor],
        evidence_slices: Sequence[EvidenceSlice],
    ) -> GroundingBatchPlan:
        started = time.perf_counter()
        ordered_slices = tuple(sorted(evidence_slices, key=lambda item: (item.evidence_order, item.slice_order, item.slice_ref)))
        missing_descriptor_refs = sorted({item.unit_ref for item in ordered_slices if item.unit_ref not in descriptors_by_ref})
        if missing_descriptor_refs:
            raise GroundedNarrationError(
                HumanNarrationStage.GROUNDING_BATCHING,
                "Evidence slices reference missing narrative fact descriptors.",
                diagnostic_code="GROUNDING_EVIDENCE_OWNER_MISSING",
                metadata={"unitRefs": missing_descriptor_refs},
            )
        atoms = [
            {
                "slice": item,
                "serialized": json.dumps(
                    {
                        "unit": descriptors_by_ref[item.unit_ref].to_prompt_dict(),
                        "evidence": item.to_prompt_dict("e"),
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                    sort_keys=True,
                ),
            }
            for item in ordered_slices
        ]
        serialization_count = len(atoms)
        batches: list[GroundingBatch] = []
        offset = 0
        empty_budget = self._empty_budget(original_question, response_language)
        available = empty_budget["availableInputTokens"]
        output_reserve = empty_budget["reservedOutputTokens"]
        while offset < len(atoms):
            selected: list[Mapping[str, Any]] = []
            serialized_tokens = 0
            while offset + len(selected) < len(atoms):
                candidate = atoms[offset + len(selected)]
                serialized_tokens += self.budget_estimator.estimate_text_tokens(str(candidate["serialized"]))
                candidate_slices = [item["slice"] for item in [*selected, candidate]]
                estimated_output_tokens = self._estimated_grounding_output_tokens(candidate_slices, descriptors_by_ref)
                if selected and (serialized_tokens > available or estimated_output_tokens > output_reserve):
                    break
                selected.append(candidate)
            if not selected:
                selected = [atoms[offset]]
            while selected:
                llm_input, ref_map = self._batch_input(
                    original_question,
                    response_language,
                    descriptors_by_ref,
                    [item["slice"] for item in selected],
                    index=len(batches) + 1,
                    total=0,
                )
                if (
                    self.budget_estimator.estimate(self.renderer.render(llm_input)).fits
                    and (
                        self._estimated_grounding_output_tokens([item["slice"] for item in selected], descriptors_by_ref) <= output_reserve
                        or len(selected) == 1
                    )
                ):
                    batches.append(
                        GroundingBatch(
                            index=len(batches) + 1,
                            total=0,
                            llm_input=llm_input,
                            evidence_ref_to_slice_ref=ref_map,
                            slice_ref_to_evidence_ref={value: key for key, value in ref_map.items()},
                        )
                    )
                    break
                selected = selected[:-1]
            if not selected:
                raise GroundedNarrationError(
                    HumanNarrationStage.GROUNDING_BATCHING,
                    "A single lossless evidence slice cannot fit inside the grounding batch prompt.",
                    diagnostic_code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                )
            offset += len(selected)
        total = len(batches)
        final_batches: list[GroundingBatch] = []
        slice_by_ref = {item.slice_ref: item for item in ordered_slices}
        for batch in batches:
            slices = [
                slice_by_ref[slice_ref]
                for slice_ref in batch.evidence_ref_to_slice_ref.values()
            ]
            llm_input, ref_map = self._batch_input(
                original_question,
                response_language,
                descriptors_by_ref,
                slices,
                index=batch.index,
                total=total,
            )
            if not self.budget_estimator.estimate(self.renderer.render(llm_input)).fits:
                raise GroundedNarrationError(
                    HumanNarrationStage.GROUNDING_BATCHING,
                    "Final grounding batch prompt no longer fits after batch-count finalization.",
                    diagnostic_code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                )
            final_batches.append(
                GroundingBatch(
                    index=batch.index,
                    total=total,
                    llm_input=llm_input,
                    evidence_ref_to_slice_ref=ref_map,
                    slice_ref_to_evidence_ref={value: key for key, value in ref_map.items()},
                )
            )
        return GroundingBatchPlan(
            batches=tuple(final_batches),
            serialization_count=serialization_count,
            planning_ms=(time.perf_counter() - started) * 1000,
        )

    def _empty_budget(self, original_question: str, response_language: str) -> Dict[str, int]:
        empty = {
            "promptKind": "GROUNDING",
            "originalQuestion": original_question,
            "responseLanguage": response_language,
            "units": [],
            "evidenceSlices": [],
            "coverageContract": {"evidenceRefs": [], "unitRefs": [], "evidenceOwners": {}, "evidenceRefsByUnit": {}},
        }
        estimate = self.budget_estimator.estimate(self.renderer.render(empty))
        return {
            "availableInputTokens": max(
                0,
                int(estimate.context_tokens)
                - int(estimate.reserved_output_tokens)
                - int(estimate.fixed_framing_reserve_tokens)
                - int(estimate.rendered_input_tokens),
            ),
            "reservedOutputTokens": max(0, int(estimate.reserved_output_tokens)),
        }

    def _minimum_grounding_output_tokens(self, evidence_count: int) -> int:
        payload = {
            "claims": [],
            "processedEvidence": [
                {"evidenceRef": f"e{index}", "disposition": "NO_NEW_BEHAVIOR", "claimRefs": []}
                for index in range(1, max(0, evidence_count) + 1)
            ],
        }
        return self.budget_estimator.estimate_text_tokens(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True))

    def _estimated_grounding_output_tokens(
        self,
        slices: Sequence[EvidenceSlice],
        descriptors_by_ref: Mapping[str, NarrativeFactDescriptor],
    ) -> int:
        claims = []
        processed = []
        for index, item in enumerate(slices, start=1):
            claim_ref = f"c{index}"
            evidence_ref = f"e{index}"
            unit_ref = descriptors_by_ref[item.unit_ref].ref if item.unit_ref in descriptors_by_ref else item.unit_ref
            claims.append(
                {
                    "claimRef": claim_ref,
                    "unitRef": unit_ref,
                    "evidenceRefs": [evidence_ref],
                    "text": (
                        "Стислий підтверджений технічний факт мовою відповіді без копіювання доказу; "
                        "compact grounded technical claim in the requested language without copying evidence."
                    ),
                }
            )
            processed.append({"evidenceRef": evidence_ref, "disposition": "CLAIMED", "claimRefs": [claim_ref]})
        payload = {"claims": claims, "processedEvidence": processed}
        return self.budget_estimator.estimate_text_tokens(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        )

    def _batch_input(
        self,
        original_question: str,
        response_language: str,
        descriptors_by_ref: Mapping[str, NarrativeFactDescriptor],
        slices: Sequence[EvidenceSlice],
        *,
        index: int,
        total: int,
    ) -> tuple[Dict[str, Any], Dict[str, str]]:
        evidence_ref_to_slice_ref: Dict[str, str] = {}
        evidence_payloads: list[Dict[str, Any]] = []
        evidence_owners: Dict[str, str] = {}
        evidence_refs_by_unit: Dict[str, list[str]] = {}
        unit_refs: list[str] = []
        for batch_index, item in enumerate(slices, start=1):
            evidence_ref = f"e{batch_index}"
            evidence_ref_to_slice_ref[evidence_ref] = item.slice_ref
            evidence_owners[evidence_ref] = item.unit_ref
            evidence_refs_by_unit.setdefault(item.unit_ref, []).append(evidence_ref)
            evidence_payloads.append(item.to_prompt_dict(evidence_ref))
            if item.unit_ref not in unit_refs:
                unit_refs.append(item.unit_ref)
        units = []
        for unit_ref in unit_refs:
            if unit_ref not in descriptors_by_ref:
                continue
            unit = descriptors_by_ref[unit_ref].to_prompt_dict()
            unit["ownedEvidenceRefs"] = evidence_refs_by_unit.get(unit_ref, [])
            units.append(unit)
        return (
            {
                "promptKind": "GROUNDING",
                "originalQuestion": original_question,
                "responseLanguage": response_language,
                "batch": {"index": index, "total": total},
                "units": units,
                "evidenceSlices": evidence_payloads,
                "coverageContract": {
                    "evidenceRefs": list(evidence_ref_to_slice_ref),
                    "unitRefs": unit_refs,
                    "evidenceOwners": evidence_owners,
                    "evidenceRefsByUnit": evidence_refs_by_unit,
                },
            },
            evidence_ref_to_slice_ref,
        )


class GroundedClaimValidator:
    _RAW_EVIDENCE_LEAK_MIN_UTF8_BYTES = 64

    def __init__(self, text_validator: HumanAnswerTextValidator | None = None) -> None:
        self.text_validator = text_validator or HumanAnswerTextValidator()

    def validate(
        self,
        raw_text: str,
        batch: GroundingBatch,
        *,
        response_language: str,
    ) -> Mapping[str, Any]:
        try:
            payload = json.loads(raw_text)
        except Exception as exc:
            raise GroundedNarrationError(
                HumanNarrationStage.GROUNDING_VALIDATION,
                "Grounding response must be strict JSON.",
                diagnostic_code="GROUNDING_RESPONSE_MALFORMED",
            ) from exc
        errors = self._payload_errors(payload, batch, response_language=response_language)
        if errors:
            raise GroundedNarrationError(
                HumanNarrationStage.GROUNDING_VALIDATION,
                "; ".join(errors),
                diagnostic_code="GROUNDING_RESPONSE_CONTRACT_VIOLATION",
                metadata={"validationErrors": errors, "batchIndex": batch.index},
            )
        return payload

    def _payload_errors(self, payload: Any, batch: GroundingBatch, *, response_language: str) -> list[str]:
        if not isinstance(payload, dict):
            return ["Grounding response must be a JSON object."]
        errors: list[str] = []
        if sorted(payload.keys()) != ["claims", "processedEvidence"]:
            errors.append("Grounding response must contain exactly claims and processedEvidence.")
        claims = payload.get("claims")
        processed = payload.get("processedEvidence")
        if not isinstance(claims, list):
            errors.append("claims must be an array.")
            claims = []
        if not isinstance(processed, list):
            errors.append("processedEvidence must be an array.")
            processed = []
        evidence_refs = set(batch.evidence_ref_to_slice_ref)
        unit_refs = {
            str(item.get("unitRef"))
            for item in batch.llm_input.get("units", [])
            if isinstance(item, dict) and str(item.get("unitRef") or "").strip()
        }
        evidence_owner = {
            str(item.get("evidenceRef")): str(item.get("unitRef"))
            for item in batch.llm_input.get("evidenceSlices", [])
            if isinstance(item, dict)
        }
        claim_refs: set[str] = set()
        claim_texts: list[str] = []
        for index, claim in enumerate(claims, start=1):
            if not isinstance(claim, dict):
                errors.append(f"claims[{index}] must be an object.")
                continue
            if sorted(claim.keys()) != ["claimRef", "evidenceRefs", "text", "unitRef"]:
                errors.append(f"claims[{index}] must contain exactly claimRef, unitRef, evidenceRefs, and text.")
            claim_ref = str(claim.get("claimRef") or "").strip()
            unit_ref = str(claim.get("unitRef") or "").strip()
            if not claim_ref:
                errors.append(f"claims[{index}].claimRef must be non-empty.")
            elif claim_ref in claim_refs:
                errors.append(f"claimRef {claim_ref} is duplicated.")
            else:
                claim_refs.add(claim_ref)
            if unit_ref not in unit_refs:
                errors.append(f"claims[{index}] has a foreign unit owner.")
            refs = claim.get("evidenceRefs")
            if not isinstance(refs, list) or not refs:
                errors.append(f"claims[{index}].evidenceRefs must contain at least one evidence ref.")
                refs = []
            for ref_value in refs:
                evidence_ref = str(ref_value or "").strip()
                if evidence_ref not in evidence_refs:
                    errors.append(f"claims[{index}] cites foreign evidence {evidence_ref}.")
                elif evidence_owner.get(evidence_ref) != unit_ref:
                    errors.append(f"claims[{index}] cites evidence owned by another unit.")
            text = claim.get("text")
            if not isinstance(text, str) or not text.strip():
                errors.append(f"claims[{index}].text must be a non-empty string.")
            elif self._leaks_raw_evidence(text, batch, refs):
                errors.append(f"claims[{index}].text copies a raw evidence slice; rewrite it as a compact non-verbatim behavior claim or mark that evidence NO_NEW_BEHAVIOR.")
            else:
                claim_texts.append(text)
        processed_refs: list[str] = []
        for index, item in enumerate(processed, start=1):
            if not isinstance(item, dict):
                errors.append(f"processedEvidence[{index}] must be an object.")
                continue
            if sorted(item.keys()) != ["claimRefs", "disposition", "evidenceRef"]:
                errors.append(f"processedEvidence[{index}] must contain exactly evidenceRef, disposition, and claimRefs.")
            evidence_ref = str(item.get("evidenceRef") or "").strip()
            if evidence_ref not in evidence_refs:
                errors.append(f"processedEvidence[{index}] contains a foreign evidence ref.")
            processed_refs.append(evidence_ref)
            disposition = str(item.get("disposition") or "").strip().upper()
            if disposition not in {"CLAIMED", "NO_NEW_BEHAVIOR"}:
                errors.append(f"processedEvidence[{index}].disposition is invalid.")
            refs = item.get("claimRefs")
            if not isinstance(refs, list):
                errors.append(f"processedEvidence[{index}].claimRefs must be an array.")
                refs = []
            for ref_value in refs:
                if str(ref_value or "").strip() not in claim_refs:
                    errors.append(f"processedEvidence[{index}] references a foreign claim.")
            if disposition == "NO_NEW_BEHAVIOR" and refs:
                errors.append(f"processedEvidence[{index}] NO_NEW_BEHAVIOR must not cite claims.")
            if disposition == "CLAIMED" and not refs:
                errors.append(f"processedEvidence[{index}] CLAIMED evidence must cite at least one claim.")
        if set(processed_refs) != evidence_refs:
            missing = sorted(evidence_refs - set(processed_refs))
            foreign = sorted(set(processed_refs) - evidence_refs)
            if missing:
                errors.append(f"Missing processed evidence refs: {', '.join(missing)}.")
            if foreign:
                errors.append(f"Foreign processed evidence refs: {', '.join(foreign)}.")
        if len(processed_refs) != len(set(processed_refs)):
            errors.append("processedEvidence contains duplicate evidence refs.")
        normalized = json.dumps(payload, ensure_ascii=False)
        if _contains_internal_id_leak(normalized):
            errors.append("Grounding response must not expose persisted graph or evidence ids.")
        unsupported = _unsupported_claim_errors(" ".join(claim_texts), batch.llm_input)
        errors.extend(unsupported)
        if claim_texts:
            text_validation = self.text_validator.validate(" ".join(claim_texts), response_language)
            if not text_validation.valid:
                errors.extend(text_validation.errors)
        return errors

    def _leaks_raw_evidence(self, text: str, batch: GroundingBatch, refs: Sequence[Any]) -> bool:
        for evidence_ref in refs or []:
            ref = str(evidence_ref or "")
            for item in batch.llm_input.get("evidenceSlices", []):
                if not isinstance(item, dict) or item.get("evidenceRef") != ref:
                    continue
                raw = str(item.get("text") or "").strip()
                if raw and len(raw.encode("utf-8")) >= self._RAW_EVIDENCE_LEAK_MIN_UTF8_BYTES and raw in str(text or ""):
                    return True
        return False


class GroundedClaimService:
    def __init__(self, validator: GroundedClaimValidator | None = None) -> None:
        self.validator = validator or GroundedClaimValidator()

    def ground(
        self,
        batches: Sequence[GroundingBatch],
        *,
        response_language: str,
        complete: Callable[[Mapping[str, Any], Sequence[str] | None, HumanNarrationStage, int | None, int | None], str],
    ) -> tuple[Mapping[str, Any], ...]:
        payloads: list[Mapping[str, Any]] = []
        for batch in batches:
            validation_errors: Sequence[str] | None = None
            for attempt in (1, 2):
                raw = complete(
                    batch.llm_input,
                    validation_errors,
                    HumanNarrationStage.GROUNDING_LLM,
                    batch.index,
                    batch.total,
                )
                try:
                    payloads.append(self.validator.validate(raw, batch, response_language=response_language))
                    break
                except GroundedNarrationError as exc:
                    if attempt == 1:
                        validation_errors = list(exc.metadata.get("validationErrors") or [str(exc)])
                        continue
                    raise
        return tuple(payloads)


class GroundedClaimAssembler:
    def assemble(
        self,
        *,
        projection: NarrativeProjection,
        batches: Sequence[GroundingBatch],
        payloads: Sequence[Mapping[str, Any]],
    ) -> tuple[GroundedNarrativeClaim, ...]:
        started = time.perf_counter()
        unit_order = {unit.ref: index for index, unit in enumerate(projection.units, start=1)}
        claims: list[GroundedNarrativeClaim] = []
        provider_order = 0
        for batch, payload in zip(batches, payloads):
            processed_claim_refs = {
                str(ref)
                for item in payload.get("processedEvidence", [])
                if isinstance(item, dict)
                for ref in item.get("claimRefs", [])
            }
            for claim in payload.get("claims", []):
                if not isinstance(claim, dict):
                    continue
                claim_ref = str(claim.get("claimRef") or "")
                if claim_ref not in processed_claim_refs:
                    continue
                provider_order += 1
                evidence_slice_refs = tuple(
                    batch.evidence_ref_to_slice_ref[str(ref)]
                    for ref in claim.get("evidenceRefs", [])
                    if str(ref) in batch.evidence_ref_to_slice_ref
                )
                claims.append(
                    GroundedNarrativeClaim(
                        claim_ref=f"c{len(claims) + 1}",
                        unit_ref=str(claim.get("unitRef") or ""),
                        certainty="VERIFIED",
                        text=str(claim.get("text") or "").strip(),
                        evidence_slice_refs=evidence_slice_refs,
                        canonical_unit_order=unit_order.get(str(claim.get("unitRef") or ""), 1_000_000_000),
                        canonical_claim_order=provider_order,
                    )
                )
        merged: Dict[tuple[str, str, str, tuple[str, ...]], GroundedNarrativeClaim] = {}
        for claim in sorted(claims, key=self._claim_sort_key):
            key = (
                claim.unit_ref,
                _normalize_claim_text(claim.text),
                claim.certainty,
                tuple(sorted(claim.evidence_slice_refs)),
            )
            existing = merged.get(key)
            if existing is None:
                merged[key] = claim
                continue
            merged[key] = replace(
                existing,
                evidence_slice_refs=tuple(dict.fromkeys((*existing.evidence_slice_refs, *claim.evidence_slice_refs))),
            )
        ordered = tuple(sorted(merged.values(), key=self._claim_sort_key))
        self.last_planning_ms = (time.perf_counter() - started) * 1000
        return ordered

    def _claim_sort_key(self, claim: GroundedNarrativeClaim) -> tuple[int, str, int]:
        return (claim.canonical_unit_order, claim.unit_ref, claim.canonical_claim_order)


class NarrationAtomPlanner:
    def plan(
        self,
        projection: NarrativeProjection,
        grounded_claims: Sequence[GroundedNarrativeClaim],
    ) -> tuple[NarrationAtom, ...]:
        claims_by_unit: Dict[str, list[GroundedNarrativeClaim]] = {}
        for claim in grounded_claims:
            claims_by_unit.setdefault(claim.unit_ref, []).append(claim)
        atoms: list[NarrationAtom] = []
        for unit in projection.units:
            descriptor = unit.descriptor
            claims = tuple(sorted(claims_by_unit.get(unit.ref, ()), key=lambda item: item.canonical_claim_order))
            atom_kind = self._atom_kind(descriptor, claims)
            atoms.append(
                NarrationAtom(
                    ref=f"a{len(atoms) + 1}",
                    atom_kind=atom_kind,
                    unit_ref=unit.ref,
                    certainty=descriptor.certainty,
                    descriptor=descriptor,
                    claims=claims,
                    canonical_order=len(atoms) + 1,
                )
            )
        return tuple(atoms)

    def _atom_kind(self, descriptor: NarrativeFactDescriptor, claims: Sequence[GroundedNarrativeClaim]) -> str:
        if descriptor.certainty == "AMBIGUOUS":
            return "AMBIGUOUS_GAP"
        if descriptor.certainty == "UNVERIFIED":
            return "UNVERIFIED_GAP"
        if claims:
            if descriptor.terminal_role == "TERMINAL":
                return "TERMINAL_RESULT_CLAIM"
            return "VERIFIED_CLAIM"
        if descriptor.fact_kind == "transition":
            return "VERIFIED_TRANSITION"
        if descriptor.fact_kind == "operation":
            return "VERIFIED_OPERATION"
        if descriptor.fact_kind == "boundary":
            return "BOUNDARY"
        if descriptor.fact_kind == "gap":
            return "AMBIGUOUS_GAP" if descriptor.certainty == "AMBIGUOUS" else "UNVERIFIED_GAP"
        if len(descriptor.outgoing_transitions) > 1:
            return "BRANCH_START"
        return "VERIFIED_OPERATION"


class NarrationSegmentPlanner:
    def __init__(self, *, renderer: Any, budget_estimator: Any) -> None:
        self.renderer = renderer
        self.budget_estimator = budget_estimator

    def plan(
        self,
        *,
        original_question: str,
        response_language: str,
        source: str,
        entrypoint: str,
        atoms: Sequence[NarrationAtom],
    ) -> NarrationSegmentPlan:
        started = time.perf_counter()
        ordered_atoms = tuple(sorted(atoms, key=lambda atom: atom.canonical_order))
        serialized_atoms = [
            json.dumps(atom.to_prompt_dict(), ensure_ascii=False, separators=(",", ":"), sort_keys=True)
            for atom in ordered_atoms
        ]
        serialization_count = len(serialized_atoms)
        groups: list[tuple[NarrationAtom, ...]] = []
        offset = 0
        empty_budget = self._empty_budget(original_question, response_language, source, entrypoint)
        available = empty_budget["availableInputTokens"]
        output_reserve = empty_budget["reservedOutputTokens"]
        while offset < len(ordered_atoms):
            selected: list[NarrationAtom] = []
            serialized_tokens = 0
            while offset + len(selected) < len(ordered_atoms):
                next_index = offset + len(selected)
                atom = ordered_atoms[next_index]
                if selected and self._would_mix_gap_and_verified(selected[-1], atom):
                    break
                serialized_tokens += self.budget_estimator.estimate_text_tokens(serialized_atoms[next_index])
                estimated_output_tokens = self._estimated_narration_output_tokens([*selected, atom], terminal=True)
                if selected and (
                    serialized_tokens > available
                    or (output_reserve > 0 and estimated_output_tokens > output_reserve)
                ):
                    break
                selected.append(atom)
            if not selected:
                selected = [ordered_atoms[offset]]
            while selected:
                candidate_input = self._segment_input(
                    original_question,
                    response_language,
                    source,
                    entrypoint,
                    tuple(selected),
                    ordered_atoms,
                    index=len(groups) + 1,
                    total=0,
                    terminal=False,
                )
                if (
                    self.budget_estimator.estimate(self.renderer.render(candidate_input)).fits
                    and (
                        output_reserve <= 0
                        or self._estimated_narration_output_tokens(selected, terminal=True) <= output_reserve
                        or len(selected) == 1
                    )
                ):
                    groups.append(tuple(selected))
                    break
                selected = selected[:-1]
            if not selected:
                raise GroundedNarrationError(
                    HumanNarrationStage.NARRATION_SEGMENTATION,
                    "A single compact narration atom cannot fit in the final narration prompt.",
                    diagnostic_code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                )
            offset += len(selected)
        total = len(groups)
        segments: list[NarrationSegment] = []
        for index, group in enumerate(groups, start=1):
            terminal = index == total
            llm_input = self._segment_input(
                original_question,
                response_language,
                source,
                entrypoint,
                group,
                ordered_atoms,
                index=index,
                total=total,
                terminal=terminal,
            )
            if not self.budget_estimator.estimate(self.renderer.render(llm_input)).fits:
                raise GroundedNarrationError(
                    HumanNarrationStage.NARRATION_SEGMENTATION,
                    "Final narration segment prompt no longer fits after segment-count finalization.",
                    diagnostic_code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                )
            if (
                output_reserve > 0
                and self._estimated_narration_output_tokens(group, terminal=terminal) > output_reserve
                and len(group) > 1
            ):
                raise GroundedNarrationError(
                    HumanNarrationStage.NARRATION_SEGMENTATION,
                    "Final narration segment output estimate no longer fits after segment-count finalization.",
                    diagnostic_code="HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                )
            segments.append(NarrationSegment(llm_input=llm_input, index=index, total=total, terminal=terminal, atoms=group))
        return NarrationSegmentPlan(
            segments=tuple(segments),
            serialization_count=serialization_count,
            planning_ms=(time.perf_counter() - started) * 1000,
        )

    def _would_mix_gap_and_verified(self, left: NarrationAtom, right: NarrationAtom) -> bool:
        left_gap = left.certainty in {"UNVERIFIED", "AMBIGUOUS"}
        right_gap = right.certainty in {"UNVERIFIED", "AMBIGUOUS"}
        return left_gap != right_gap

    def _empty_budget(self, original_question: str, response_language: str, source: str, entrypoint: str) -> Dict[str, int]:
        empty = self._segment_input(
            original_question,
            response_language,
            source,
            entrypoint,
            (),
            (),
            index=1,
            total=1,
            terminal=False,
        )
        estimate = self.budget_estimator.estimate(self.renderer.render(empty))
        return {
            "availableInputTokens": max(
                0,
                int(estimate.context_tokens)
                - int(estimate.reserved_output_tokens)
                - int(estimate.fixed_framing_reserve_tokens)
                - int(estimate.rendered_input_tokens),
            ),
            "reservedOutputTokens": max(0, int(estimate.reserved_output_tokens)),
        }

    def _estimated_narration_output_tokens(self, atoms: Sequence[NarrationAtom], *, terminal: bool) -> int:
        steps = [
            {
                "atomRefs": [atom.ref],
                "certainty": atom.certainty,
                "text": "Крок.",
            }
            for atom in atoms
        ]
        payload = {
            "steps": steps,
            "result": (
                "Підсумок."
                if terminal
                else None
            ),
        }
        return self.budget_estimator.estimate_text_tokens(
            json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        )

    def _segment_input(
        self,
        original_question: str,
        response_language: str,
        source: str,
        entrypoint: str,
        atoms: Sequence[NarrationAtom],
        all_atoms: Sequence[NarrationAtom],
        *,
        index: int,
        total: int,
        terminal: bool,
    ) -> Dict[str, Any]:
        atom_payloads = [atom.to_prompt_dict() for atom in atoms]
        return {
            "promptKind": "FINAL_NARRATION",
            "originalQuestion": original_question,
            "responseLanguage": response_language,
            "familyRoot": {"source": source, "entrypoint": entrypoint},
            "segment": {
                "index": int(index),
                "total": int(total),
                "terminal": bool(terminal),
                "incomingContext": self._incoming_context(atoms, all_atoms),
                "outgoingContext": self._outgoing_context(atoms, all_atoms),
            },
            "narrationAtoms": atom_payloads,
            "coverageContract": self._coverage(atom_payloads),
            "suggestedStepPlan": self._suggested_step_plan(atom_payloads),
        }

    def _coverage(self, atom_payloads: Sequence[Mapping[str, Any]]) -> Dict[str, Any]:
        refs = [str(atom.get("atomRef")) for atom in atom_payloads if str(atom.get("atomRef") or "").strip()]
        return {
            "canonicalAtomRefs": refs,
            "requiredAtomRefs": refs,
            "atomCertainty": {str(atom.get("atomRef")): str(atom.get("certainty") or "VERIFIED") for atom in atom_payloads},
            "gapRefs": [str(atom.get("atomRef")) for atom in atom_payloads if str(atom.get("certainty") or "") in {"UNVERIFIED", "AMBIGUOUS"}],
        }

    def _suggested_step_plan(self, atom_payloads: Sequence[Mapping[str, Any]]) -> list[Dict[str, Any]]:
        groups: list[Dict[str, Any]] = []
        for atom in atom_payloads:
            ref = str(atom.get("atomRef") or "")
            certainty = str(atom.get("certainty") or "VERIFIED")
            if not ref:
                continue
            groups.append({"atomRefs": [ref], "certainty": certainty})
        return groups

    def _incoming_context(self, atoms: Sequence[NarrationAtom], all_atoms: Sequence[NarrationAtom]) -> list[Dict[str, Any]]:
        if not atoms:
            return []
        first_order = atoms[0].canonical_order
        previous_atoms = [atom for atom in all_atoms if atom.canonical_order < first_order]
        if not previous_atoms:
            return []
        return [self._continuity_descriptor(previous_atoms[-1])]

    def _outgoing_context(self, atoms: Sequence[NarrationAtom], all_atoms: Sequence[NarrationAtom]) -> list[Dict[str, Any]]:
        if not atoms:
            return []
        last_order = atoms[-1].canonical_order
        following_atoms = [atom for atom in all_atoms if atom.canonical_order > last_order]
        if not following_atoms:
            return []
        return [self._continuity_descriptor(following_atoms[0])]

    def _continuity_descriptor(self, atom: NarrationAtom) -> Dict[str, Any]:
        descriptor = atom.descriptor
        return _without_none(
            {
                "previousSource": descriptor.from_source or descriptor.source,
                "previousSymbol": descriptor.from_symbol or descriptor.symbol,
                "nextSource": descriptor.to_source,
                "nextSymbol": descriptor.to_symbol,
                "transitionKind": descriptor.transition_kind,
                "transportKind": descriptor.transport_kind,
                "method": descriptor.method,
                "route": descriptor.route,
                "boundaryKind": descriptor.boundary_kind,
                "gapKind": descriptor.gap_verification_status,
            }
        )


class FamilyNarrationService:
    def __init__(
        self,
        *,
        projector: NarrativeFactProjector | None = None,
        evidence_work_planner: EvidenceWorkPlanner | None = None,
        claim_service: GroundedClaimService | None = None,
        claim_assembler: GroundedClaimAssembler | None = None,
        atom_planner: NarrationAtomPlanner | None = None,
    ) -> None:
        self.projector = projector or NarrativeFactProjector()
        self.evidence_work_planner = evidence_work_planner or EvidenceWorkPlanner()
        self.claim_service = claim_service or GroundedClaimService()
        self.claim_assembler = claim_assembler or GroundedClaimAssembler()
        self.atom_planner = atom_planner or NarrationAtomPlanner()

    def prepare(
        self,
        *,
        request: Any,
        flow: Any,
        plan: Any,
        response_language: str,
        renderer: Any,
        budget_estimator: Any,
        complete_grounding: Callable[[Mapping[str, Any], Sequence[str] | None, HumanNarrationStage, int | None, int | None], str],
    ) -> FamilyNarrationPreparation:
        projection_started = time.perf_counter()
        projection = self.projector.project(request, flow, plan)
        evidence_work_items = self.evidence_work_planner.plan(projection)
        projection = replace(projection, evidence_work_items=evidence_work_items)
        projection_ms = (time.perf_counter() - projection_started) * 1000
        descriptors_by_ref = projection.descriptors_by_ref
        splitter_started = time.perf_counter()
        splitter = LosslessEvidenceSplitter(
            renderer=renderer,
            budget_estimator=budget_estimator,
            descriptors_by_ref=descriptors_by_ref,
            original_question=str(getattr(request, "queryText", "")),
            response_language=response_language,
        )
        slices = splitter.split(evidence_work_items)
        split_ms = (time.perf_counter() - splitter_started) * 1000
        batch_plan = GroundingBatchPlanner(renderer=renderer, budget_estimator=budget_estimator).plan(
            original_question=str(getattr(request, "queryText", "")),
            response_language=response_language,
            descriptors_by_ref=descriptors_by_ref,
            evidence_slices=slices,
        )
        grounding_payloads = self.claim_service.ground(
            batch_plan.batches,
            response_language=response_language,
            complete=complete_grounding,
        ) if batch_plan.batches else ()
        claims = self.claim_assembler.assemble(
            projection=projection,
            batches=batch_plan.batches,
            payloads=grounding_payloads,
        )
        atoms = self.atom_planner.plan(projection, claims)
        source, entrypoint = self.projector.identity(flow)
        segment_plan = NarrationSegmentPlanner(renderer=renderer, budget_estimator=budget_estimator).plan(
            original_question=str(getattr(request, "queryText", "")),
            response_language=response_language,
            source=source,
            entrypoint=entrypoint,
            atoms=atoms,
        )
        no_new_behavior_count = sum(
            1
            for payload in grounding_payloads
            for item in payload.get("processedEvidence", [])
            if isinstance(item, dict) and str(item.get("disposition") or "").upper() == "NO_NEW_BEHAVIOR"
        )
        slice_counts_by_work_ref: Dict[str, int] = {}
        for slice_item in slices:
            slice_counts_by_work_ref[slice_item.work_ref] = slice_counts_by_work_ref.get(slice_item.work_ref, 0) + 1
        metrics = {
            "narrativePlanCount": 1,
            "evidenceRecordCount": len(evidence_work_items),
            "evidenceUtf8Bytes": sum(len(item.exact_text.encode("utf-8")) for item in evidence_work_items),
            "evidenceSliceCount": len(slices),
            "splitEvidenceRecordCount": sum(1 for count in slice_counts_by_work_ref.values() if count > 1),
            "groundingBatchCount": len(batch_plan.batches),
            "groundedClaimCount": len(claims),
            "noNewBehaviorEvidenceCount": no_new_behavior_count,
            "narrationAtomCount": len(atoms),
            "narrationSegmentCount": len(segment_plan.segments),
            "EVIDENCE_SPLIT_MS": round(split_ms, 3),
            "GROUNDING_BATCH_PLAN_MS": round(batch_plan.planning_ms, 3),
            "CLAIM_ASSEMBLY_MS": round(getattr(self.claim_assembler, "last_planning_ms", 0.0), 3),
            "NARRATION_SEGMENT_PLAN_MS": round(segment_plan.planning_ms, 3),
            "EVIDENCE_SERIALIZATION_COUNT": batch_plan.serialization_count,
            "NARRATION_SERIALIZATION_COUNT": segment_plan.serialization_count,
            "NARRATIVE_FACT_PROJECTION_MS": round(projection_ms, 3),
            "evidenceRecordManifest": [
                {
                    "workRef": item.work_ref,
                    "unitRef": item.unit_ref,
                    "length": len(item.exact_text),
                    "utf8Bytes": len(item.exact_text.encode("utf-8")),
                    "hash": item.utf8_hash,
                    "sliceCount": slice_counts_by_work_ref.get(item.work_ref, 0),
                }
                for item in evidence_work_items
            ],
        }
        return FamilyNarrationPreparation(
            projection=projection,
            evidence_slices=slices,
            grounding_batches=batch_plan.batches,
            grounded_claims=claims,
            narration_atoms=atoms,
            narration_segments=segment_plan.segments,
            metrics=metrics,
        )


def _clean(value: Any) -> str | None:
    normalized = str(value or "").strip()
    return normalized or None


def _without_none(value: Mapping[str, Any]) -> Dict[str, Any]:
    result: Dict[str, Any] = {}
    for key, item in value.items():
        if item is None:
            continue
        if isinstance(item, dict):
            nested = _without_none(item)
            if nested:
                result[key] = nested
        elif isinstance(item, list) and not item:
            continue
        elif isinstance(item, tuple) and not item:
            continue
        else:
            result[key] = item
    return result


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


def _normalize_claim_text(value: str) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip())


def _contains_internal_id_leak(value: str) -> bool:
    forbidden = (
        "graphId",
        "graphRevision",
        "nodeId",
        "edgeId",
        "evidenceId",
        "analysis-graph-",
        "stableKey",
        "SQLite",
    )
    return any(token in str(value or "") for token in forbidden)


def _unsupported_claim_errors(text: str, llm_input: Mapping[str, Any]) -> list[str]:
    rendered_input = json.dumps(dict(llm_input), ensure_ascii=False)
    errors: list[str] = []
    routes = {
        route.rstrip(".,;:!?)\"]")
        for route in re.findall(r"/[A-Za-z0-9_./{}:-]+", str(text or ""))
    }
    for route in sorted(item for item in routes if item):
        if route not in rendered_input:
            errors.append(f"Response mentions unsupported route or path {route}.")
    for status in sorted(set(re.findall(r"\bHTTP\s+([1-5][0-9][0-9])\b", str(text or ""), flags=re.IGNORECASE))):
        if status not in rendered_input:
            errors.append(f"Response mentions unsupported HTTP status {status}.")
    return errors


def technical_diagnostic(stage: HumanNarrationStage, source: str, entrypoint: str, code: str | None = None) -> KnowledgeQueryDiagnostic:
    return KnowledgeQueryDiagnostic(
        code=code or f"HUMAN_NARRATION_{stage.value}_FAILED",
        message="The local model could not explain one selected flow.",
        severity="WARN",
        sourceId=source or None,
        metadata={"entrypoint": entrypoint, "stage": stage.value},
    )
