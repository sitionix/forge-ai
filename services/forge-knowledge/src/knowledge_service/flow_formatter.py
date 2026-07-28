from __future__ import annotations

import hashlib
import json
import re
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import httpx

from knowledge_service.answer_language import HumanAnswerTextValidator
from knowledge_service.boundary_resolution import boundary_identity, descriptor_fingerprint
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


class EndToEndFormatterValidationError(EndToEndFormatterError):
    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = tuple(str(item) for item in errors if str(item).strip())
        super().__init__("; ".join(self.errors) or "canonical formatter validation failed")


class EndToEndFormatterProviderError(EndToEndFormatterError):
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
    response_language: str
    sources: tuple[str, ...]
    query_entries: tuple[KnowledgeGraphAnswerQueryEntry, ...]
    topology_entries: tuple[str, ...]
    stages: tuple[EndToEndPresentationStage, ...]
    canonical_fact_refs: tuple[str, ...]
    complete: bool
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    planning_duration_ms: float = 0.0


@dataclass(frozen=True)
class EndToEndFormatterSegment:
    segment_ref: str
    graph_id: str
    response_language: str
    stage_refs: tuple[str, ...]
    formatter_input: Mapping[str, Any]
    prompt_hash_seed: str


@dataclass(frozen=True)
class EndToEndFormatterProviderResult:
    raw_text: str
    prompt_char_length: int
    prompt_hash: str
    duration_ms: float
    provider_name: str | None = None
    provider_model: str | None = None


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
        diagnostics = [
            KnowledgeQueryDiagnostic(code=item.code, message=item.message, severity=item.severity, sourceId=item.source_id, metadata=dict(item.metadata or {}))
            for item in graph.diagnostics
        ]
        unit_refs_by_id = {ref.unit_id: ref for ref in graph.unit_refs}
        unit_order = self._unit_order(graph)
        exact_query_ids = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id in unit_refs_by_id)
        query_entries = tuple(self._query_entry(unit_refs_by_id[unit_id]) for unit_id in exact_query_ids)
        missing_query_ids = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id not in unit_refs_by_id)
        if missing_query_ids:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="END_TO_END_PRESENTATION_QUERY_ENTRY_MISSING",
                    message="Canonical query-entry IDs were preserved, but at least one referenced unit is absent from the selected graph.",
                    severity="WARN",
                    metadata={"missingQueryEntryUnitIds": missing_query_ids},
                )
            )
        if not graph.query_entry_unit_ids:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="END_TO_END_PRESENTATION_QUERY_ENTRY_ABSENT",
                    message="No canonical query-entry unit was provided; presentation did not invent one.",
                    severity="WARN",
                    metadata={"graphId": graph.stable_graph_id},
                )
            )

        transitions_by_source: dict[str, list[Any]] = defaultdict(list)
        transitions_by_target: dict[str, list[Any]] = defaultdict(list)
        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            transitions_by_source[transition.source_unit_id].append(transition)
            transitions_by_target[transition.target_unit_id].append(transition)

        emitted_transitions: set[str] = set()
        for unit_id in unit_order:
            ref = unit_refs_by_id[unit_id]
            inbound = tuple(transitions_by_target.get(unit_id, ()))
            if len(inbound) > 1:
                stage = self._convergence_stage(unit_id, inbound)
                stages.append(stage)
                fact_refs.extend(stage.canonical_fact_refs)
                shared = self._shared_unit_stage(unit_id, inbound)
                stages.append(shared)
                fact_refs.extend(shared.canonical_fact_refs)
            unit_stage = self._unit_stage(ref)
            stages.append(unit_stage)
            fact_refs.extend(unit_stage.canonical_fact_refs)
            outbound = tuple(transitions_by_source.get(unit_id, ()))
            if len(outbound) > 1:
                stage = self._branch_stage(unit_id, outbound)
                stages.append(stage)
                fact_refs.extend(stage.canonical_fact_refs)
            for transition in outbound:
                transition_stage = self._transition_stage(transition)
                stages.append(transition_stage)
                fact_refs.extend(transition_stage.canonical_fact_refs)
                emitted_transitions.add(transition.stable_transition_id)

        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            if transition.stable_transition_id in emitted_transitions:
                continue
            transition_stage = self._transition_stage(transition)
            stages.append(transition_stage)
            fact_refs.extend(transition_stage.canonical_fact_refs)

        for boundary in sorted(graph.open_boundaries, key=lambda item: (str(item.status), item.required_boundary_identity.boundary_key, tuple(item.source_unit_ids))):
            stage = self._open_boundary_stage(boundary)
            stages.append(stage)
            fact_refs.extend(stage.canonical_fact_refs)

        if graph.coverage.cycle_count:
            stage = self._cycle_stage(graph)
            stages.append(stage)
            fact_refs.extend(stage.canonical_fact_refs)

        canonical_fact_refs = tuple(sorted(set(fact_refs)))
        return EndToEndPresentationPlan(
            graph_id=graph.stable_graph_id,
            response_language=response_language,
            sources=tuple(sorted({ref.source_id for ref in graph.unit_refs})),
            query_entries=query_entries,
            topology_entries=tuple(graph.topology_entry_unit_ids),
            stages=tuple(stages),
            canonical_fact_refs=canonical_fact_refs,
            complete=graph.coverage.complete,
            diagnostics=tuple(diagnostics),
            planning_duration_ms=round((time.perf_counter() - started) * 1000, 3),
        )

    def _unit_order(self, graph: EndToEndFlowGraph) -> tuple[str, ...]:
        unit_ids = tuple(sorted(ref.unit_id for ref in graph.unit_refs))
        if not unit_ids:
            return ()
        outgoing: dict[str, list[str]] = defaultdict(list)
        incoming: dict[str, set[str]] = defaultdict(set)
        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            outgoing[transition.source_unit_id].append(transition.target_unit_id)
            incoming[transition.target_unit_id].add(transition.source_unit_id)
        starts = tuple(unit_id for unit_id in graph.query_entry_unit_ids if unit_id in unit_ids)
        if not starts:
            starts = tuple(unit_id for unit_id in graph.topology_entry_unit_ids if unit_id in unit_ids)
        ordered: list[str] = []
        seen: set[str] = set()
        queue = deque(starts or unit_ids)
        while queue:
            unit_id = queue.popleft()
            if unit_id in seen or unit_id not in unit_ids:
                continue
            seen.add(unit_id)
            ordered.append(unit_id)
            for target_id in sorted(outgoing.get(unit_id, ())):
                if incoming.get(target_id, set()).issubset(seen) or target_id not in seen:
                    queue.append(target_id)
        ordered.extend(unit_id for unit_id in unit_ids if unit_id not in seen)
        return tuple(ordered)

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

    def _unit_stage(self, ref: Any) -> EndToEndPresentationStage:
        unit = ref.local_unit
        fact_refs = self._unit_fact_refs(unit)
        return EndToEndPresentationStage(
            stage_ref=f"unit:{unit.unit_id}",
            kind="UNIT_ENTRY" if ref.query_selected_initial else "LOCAL_EXECUTION",
            canonical_fact_refs=fact_refs,
            payload={
                "unitId": unit.unit_id,
                "sourceId": unit.source_id,
                "graphRevision": unit.graph_revision,
                "roots": [
                    {
                        "node": self._node_payload(root.node),
                        "origin": root.origin.value if hasattr(root.origin, "value") else str(root.origin),
                        "distanceToNearestSeed": root.distance_to_nearest_seed,
                    }
                    for root in unit.roots
                ],
                "queryAnchors": [
                    {
                        "originalAnchor": {
                            "sourceId": anchor.original_anchor.sourceId,
                            "nodeId": anchor.original_anchor.nodeId,
                            "stableKey": anchor.original_anchor.stableKey,
                            "label": anchor.original_anchor.label,
                            "qualifiedName": anchor.original_anchor.qualifiedName,
                            "matchReasons": list(anchor.original_anchor.matchReasons),
                        },
                        "expandedSeed": self._node_payload(anchor.expanded_seed),
                        "anchorToSeedReasons": list(anchor.anchor_to_seed_reasons),
                        "queryProvenance": list(anchor.query_provenance),
                        "distanceToNearestRoot": anchor.distance_to_nearest_root,
                    }
                    for anchor in unit.anchors
                ],
                "executionNodes": [self._node_payload(node) for node in unit.execution_nodes],
                "localExecutionTransitions": [self._edge_payload(edge) for edge in unit.execution_transitions],
                "topologyBoundaries": [self._edge_payload(edge) for edge in unit.topology_boundaries],
                "genericBoundaries": [self._boundary_payload(boundary) for boundary in unit.generic_boundaries],
                "supportingContext": [self._node_payload(node) for node in unit.supporting_context],
                "evidenceRefs": [self._evidence_payload(evidence) for evidence in unit.evidence],
                "complete": bool(unit.complete),
                "truncated": bool(unit.coverage.truncated),
                "coverage": {
                    "nodeCount": unit.coverage.node_count,
                    "transitionCount": unit.coverage.transition_count,
                    "genericBoundaryCount": unit.coverage.generic_boundary_count,
                    "topologyBoundaryCount": unit.coverage.topology_boundary_count,
                    "anchorCount": unit.coverage.anchor_count,
                    "rootCount": unit.coverage.root_count,
                    "maxDepthReached": unit.coverage.max_depth_reached,
                    "cycleDetected": unit.coverage.cycle_detected,
                },
            },
        )

    def _transition_stage(self, transition: Any) -> EndToEndPresentationStage:
        fact_ref = f"transition:{transition.stable_transition_id}"
        fact_refs = (
            fact_ref,
            f"resolution:{transition.resolution_id}",
            f"required-boundary:{_identity_ref(transition.required_endpoint.boundary_identity)}",
            f"provided-boundary:{_identity_ref(transition.provided_endpoint.boundary_identity)}",
        )
        return EndToEndPresentationStage(
            stage_ref=fact_ref,
            kind="PROVEN_BOUNDARY_CONTINUATION",
            canonical_fact_refs=tuple(sorted(set(fact_refs))),
            payload={
                "transitionId": transition.stable_transition_id,
                "resolutionId": transition.resolution_id,
                "sourceUnitId": transition.source_unit_id,
                "targetUnitId": transition.target_unit_id,
                "requiredBoundary": self._endpoint_payload(transition.required_endpoint),
                "providedBoundary": self._endpoint_payload(transition.provided_endpoint),
                "targetSeeds": [_dataclass_payload(item) for item in transition.target_seed_identities],
                "provingDescriptorFingerprintHashes": sorted(item.fingerprint_hash for item in transition.proving_descriptor_fingerprints),
                "evidenceRefs": [_dataclass_payload(item) for item in transition.evidence_references],
            },
        )

    def _open_boundary_stage(self, boundary: Any) -> EndToEndPresentationStage:
        status = boundary.status.value if hasattr(boundary.status, "value") else str(boundary.status)
        kind = "OPEN_BOUNDARY_AMBIGUOUS" if status == "AMBIGUOUS" else "OPEN_BOUNDARY_UNRESOLVED"
        fact_ref = f"open-boundary:{_identity_ref(boundary.required_boundary_identity)}:{','.join(boundary.source_unit_ids)}"
        return EndToEndPresentationStage(
            stage_ref=fact_ref,
            kind=kind,
            canonical_fact_refs=(fact_ref,),
            payload={
                "requiredBoundary": self._identity_payload(boundary.required_boundary_identity),
                "sourceUnitIds": list(boundary.source_unit_ids),
                "status": status,
                "viableCandidateOwners": [self._owner_payload(owner) for owner in boundary.viable_candidate_owner_identities],
                "viableCandidateBoundaries": [self._identity_payload(item) for item in boundary.viable_candidate_boundary_identities],
                "rejectionReasonCodes": list(boundary.rejection_reason_codes),
                "descriptorFingerprintHashes": list(boundary.descriptor_fingerprint_hashes),
                "diagnostics": list(boundary.diagnostics),
            },
        )

    def _branch_stage(self, source_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in transitions))
        fact_ref = f"branch:{source_unit_id}:{_sha256('|'.join(transition_ids))[:12]}"
        return EndToEndPresentationStage(
            stage_ref=fact_ref,
            kind="BRANCH",
            canonical_fact_refs=(fact_ref, *tuple(f"transition:{item}" for item in transition_ids)),
            payload={"sourceUnitId": source_unit_id, "transitionIds": list(transition_ids), "targetUnitIds": sorted({item.target_unit_id for item in transitions})},
        )

    def _convergence_stage(self, target_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in transitions))
        fact_ref = f"convergence:{target_unit_id}:{_sha256('|'.join(transition_ids))[:12]}"
        return EndToEndPresentationStage(
            stage_ref=fact_ref,
            kind="CONVERGENCE",
            canonical_fact_refs=(fact_ref, *tuple(f"transition:{item}" for item in transition_ids)),
            payload={"targetUnitId": target_unit_id, "transitionIds": list(transition_ids), "sourceUnitIds": sorted({item.source_unit_id for item in transitions})},
        )

    def _shared_unit_stage(self, target_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in transitions))
        fact_ref = f"shared-unit:{target_unit_id}:{_sha256('|'.join(transition_ids))[:12]}"
        return EndToEndPresentationStage(
            stage_ref=fact_ref,
            kind="SHARED_UNIT_REFERENCE",
            canonical_fact_refs=(fact_ref, *tuple(f"transition:{item}" for item in transition_ids), f"unit:{target_unit_id}"),
            payload={"unitId": target_unit_id, "transitionIds": list(transition_ids), "renderedOnce": True},
        )

    def _cycle_stage(self, graph: EndToEndFlowGraph) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in graph.proven_cross_source_transitions))
        fact_ref = f"cycle:{graph.stable_graph_id}"
        return EndToEndPresentationStage(
            stage_ref=fact_ref,
            kind="CYCLE_REFERENCE",
            canonical_fact_refs=(fact_ref, *tuple(f"transition:{item}" for item in transition_ids)),
            payload={"graphId": graph.stable_graph_id, "cycleCount": graph.coverage.cycle_count, "transitionIds": list(transition_ids)},
        )

    def _unit_fact_refs(self, unit: Any) -> tuple[str, ...]:
        refs = [f"unit:{unit.unit_id}"]
        refs.extend(f"root:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}" for node in (root.node for root in unit.roots))
        refs.extend(f"node:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}" for node in unit.execution_nodes)
        refs.extend(f"edge:{edge.source_id}:{edge.graph_revision or edge.graph_id}:{edge.edge_id}" for edge in unit.execution_transitions)
        refs.extend(f"topology-boundary:{edge.source_id}:{edge.graph_revision or edge.graph_id}:{edge.edge_id}" for edge in unit.topology_boundaries)
        refs.extend(f"generic-boundary:{_identity_ref(boundary_identity(boundary))}" for boundary in unit.generic_boundaries)
        refs.extend(f"context:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}" for node in unit.supporting_context)
        refs.extend(f"evidence:{item.source_id}:{item.graph_revision or item.graph_id}:{item.evidence_id}" for item in unit.evidence)
        return tuple(sorted(set(refs)))

    def _node_payload(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "nodeId": item.node_id,
            "stableKey": item.stable_key,
            "kind": item.node_kind,
            "label": item.label,
            "qualifiedName": item.qualified_name,
            "relativePath": item.relative_path,
            "lineStart": item.line_start,
            "lineEnd": item.line_end,
            "summary": item.summary,
            "entrypoint": item.entrypoint,
            "entrypointKind": item.entrypoint_kind,
            "executionRole": item.execution_role,
            "flowDomain": item.flow_domain,
        }

    def _edge_payload(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "edgeId": item.edge_id,
            "edgeType": item.edge_type,
            "fromNodeId": item.from_node_id,
            "toNodeId": item.to_node_id,
            "resolutionStatus": item.resolution_status,
            "toSourceId": item.to_source_id,
            "toGraphRevision": item.to_graph_revision or item.to_graph_id,
            "external": item.external,
            "unresolvedTarget": item.unresolved_target,
            "evidenceIds": list(item.evidence_ids),
            "flowDomain": item.flow_domain,
            "boundaryReason": item.boundary_reason,
        }

    def _boundary_payload(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "boundaryId": item.boundary_id,
            "boundaryKey": item.stable_key,
            "ownerNodeId": item.owner_node_id,
            "role": item.role,
            "status": item.status,
            "provenance": item.provenance,
            "confidence": item.confidence,
            "flowDomain": item.flow_domain,
            "descriptorFingerprintHashes": sorted(
                {
                    descriptor_fingerprint(descriptor).fingerprint_hash
                    for descriptor in item.descriptors
                }
            ),
            "evidenceRefs": [self._evidence_payload(evidence) for evidence in item.evidence],
        }

    def _endpoint_payload(self, item: Any) -> dict[str, Any]:
        return {
            "boundary": self._identity_payload(item.boundary_identity),
            "ownerSourceId": item.owner_source_id,
            "ownerGraphRevision": item.owner_graph_revision,
            "ownerNodeId": item.owner_node_id,
            "role": item.role,
            "localUnitIds": list(item.local_unit_ids),
        }

    def _identity_payload(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision,
            "boundaryKey": item.boundary_key,
            "ownerNodeId": item.owner_node_id,
        }

    def _owner_payload(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision,
            "ownerNodeId": item.owner_node_id,
            "boundary": self._identity_payload(item.boundary_identity),
        }

    def _evidence_payload(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "evidenceId": item.evidence_id,
            "nodeId": item.node_id,
            "edgeId": item.edge_id,
            "relativePath": item.relative_path,
            "lineStart": item.line_start,
            "lineEnd": item.line_end,
            "excerpt": item.text,
            "ownerKind": item.owner_kind,
            "ownerSourceId": item.owner_source_id,
            "ownerNodeId": item.owner_node_id,
            "ownerEdgeId": item.owner_edge_id,
        }


class EndToEndFormatterPromptRenderer:
    def render(self, formatter_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        payload = json.dumps(dict(formatter_input), ensure_ascii=False, indent=2, sort_keys=True)
        errors = "\n".join(f"- {item}" for item in validation_errors or ())
        repair = f"\nPrevious JSON failed validation. Correct these exact issues:\n{errors}\n" if errors else ""
        return (
            "Format canonical end-to-end execution graph facts as grounded prose.\n"
            "Return strict JSON only. Do not include prose outside JSON.\n"
            "The JSON shape is exactly: {\"steps\":[{\"stageRef\":\"string\",\"coveredFactRefs\":[\"string\"],\"text\":\"string\"}]}.\n"
            "Return exactly one step per supplied stage, in the supplied stageOrder.\n"
            "Use responseLanguage for every text value.\n"
            "Use only facts present in the stage payloads and coveredFactRefs. Do not invent source IDs, symbols, transitions, routes, HTTP methods, or targets.\n"
            "For AMBIGUOUS boundaries, say several eligible continuations exist and no target was selected.\n"
            "For UNRESOLVED boundaries, say no proven continuation is available.\n"
            "Do not describe AMBIGUOUS or UNRESOLVED boundaries as proven.\n"
            f"{repair}"
            "BEGIN_CANONICAL_FORMATTER_INPUT_JSON\n"
            f"{payload}\n"
            "END_CANONICAL_FORMATTER_INPUT_JSON\n"
        )


class EndToEndFormatterSegmentPlanner:
    def __init__(self, context_tokens: int = 8192) -> None:
        self.context_tokens = max(1024, int(context_tokens or 8192))
        self.serialization_count = 0

    def segments(self, plan: EndToEndPresentationPlan) -> tuple[EndToEndFormatterSegment, ...]:
        stages = tuple(plan.stages)
        if not stages:
            return ()
        max_chars = max(4096, int(self.context_tokens * 3.2))
        base = self._base_input(plan)
        segments: list[EndToEndFormatterSegment] = []
        current: list[EndToEndPresentationStage] = []
        for stage in stages:
            candidate = (*current, stage)
            if current and len(self._serialize_input(base, candidate)) > max_chars:
                segments.append(self._segment(plan, base, tuple(current), len(segments)))
                current = [stage]
            else:
                current = list(candidate)
        if current:
            segments.append(self._segment(plan, base, tuple(current), len(segments)))
        return tuple(segments)

    def _base_input(self, plan: EndToEndPresentationPlan) -> dict[str, Any]:
        return {
            "graphId": plan.graph_id,
            "responseLanguage": plan.response_language,
            "queryEntries": [_query_entry_payload(item) for item in plan.query_entries],
            "topologyEntries": list(plan.topology_entries),
            "complete": plan.complete,
        }

    def _segment(
        self,
        plan: EndToEndPresentationPlan,
        base: Mapping[str, Any],
        stages: tuple[EndToEndPresentationStage, ...],
        index: int,
    ) -> EndToEndFormatterSegment:
        formatter_input = {
            **dict(base),
            "segmentRef": f"{plan.graph_id}:segment:{index + 1}",
            "segmentIndex": index,
            "stageOrder": [stage.stage_ref for stage in stages],
            "stages": [self._stage_payload(stage) for stage in stages],
        }
        raw = self._serialize_input({}, stages, explicit_input=formatter_input)
        return EndToEndFormatterSegment(
            segment_ref=str(formatter_input["segmentRef"]),
            graph_id=plan.graph_id,
            response_language=plan.response_language,
            stage_refs=tuple(formatter_input["stageOrder"]),
            formatter_input=formatter_input,
            prompt_hash_seed=_sha256(raw),
        )

    def _serialize_input(
        self,
        base: Mapping[str, Any],
        stages: Sequence[EndToEndPresentationStage],
        *,
        explicit_input: Mapping[str, Any] | None = None,
    ) -> str:
        self.serialization_count += 1
        payload = dict(explicit_input) if explicit_input is not None else {**dict(base), "stageOrder": [stage.stage_ref for stage in stages], "stages": [self._stage_payload(stage) for stage in stages]}
        return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)

    def _stage_payload(self, stage: EndToEndPresentationStage) -> dict[str, Any]:
        return {
            "stageRef": stage.stage_ref,
            "kind": stage.kind,
            "ownedFactRefs": list(stage.canonical_fact_refs),
            "payload": _json_safe(stage.payload),
        }


class LocalOllamaEndToEndFormatterClient:
    name = "local-ollama-end-to-end-formatter"

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

    def generate(
        self,
        formatter_input: Mapping[str, Any],
        *,
        deadline_at: float,
        cancel_event: Any | None,
        validation_errors: Sequence[str] = (),
    ) -> EndToEndFormatterProviderResult:
        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)():
            raise EndToEndFormatterDeadlineExceeded("canonical formatter cancelled")
        remaining = max(0.0, deadline_at - time.monotonic())
        if remaining <= 0.0:
            raise EndToEndFormatterDeadlineExceeded("canonical formatter deadline exceeded")
        timeout_seconds = max(0.001, min(float(self.timeout_seconds or remaining), remaining))
        prompt = self.renderer.render(formatter_input, validation_errors)
        prompt_hash = _sha256(prompt)
        started = time.perf_counter()
        try:
            response = self._client.post(
                f"{self.base_url}/api/generate",
                json={
                    "model": self.model,
                    "prompt": prompt,
                    "stream": False,
                    "format": "json",
                    "options": {"temperature": 0},
                },
                timeout=timeout_seconds,
            )
            response.raise_for_status()
            payload = response.json()
            raw_text = str(payload.get("response") or "")
        except httpx.TimeoutException as exc:
            raise EndToEndFormatterDeadlineExceeded("canonical formatter provider timed out") from exc
        except Exception as exc:
            raise EndToEndFormatterProviderError("canonical formatter provider failed") from exc
        if not raw_text.strip():
            raise EndToEndFormatterProviderError("canonical formatter provider returned an empty response")
        return EndToEndFormatterProviderResult(
            raw_text=raw_text,
            prompt_char_length=len(prompt),
            prompt_hash=prompt_hash,
            duration_ms=round((time.perf_counter() - started) * 1000, 3),
            provider_name=self.name,
            provider_model=self.model,
        )

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
        language_validator: HumanAnswerTextValidator | None = None,
    ) -> None:
        self.provider = provider
        self.segment_planner = segment_planner or EndToEndFormatterSegmentPlanner()
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or 60.0))
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.audit_records: deque[dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))
        self.pipeline_records: list[dict[str, Any]] = []
        self.current_stage: str | None = None
        self.planner = EndToEndPresentationPlanner()
        self.language_validator = language_validator or HumanAnswerTextValidator()

    def answer(
        self,
        request: KnowledgeQueryRequest,
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
        presentation_plans: list[EndToEndPresentationPlan] = []
        planning_ms = 0.0
        total_provider_calls = 0
        total_repair_calls = 0
        total_formatter_ms = 0.0
        total_segment_count = 0
        for graph in graphs:
            self._check_cancelled(cancel_event)
            if time.monotonic() >= deadline_at:
                raise EndToEndFormatterDeadlineExceeded("canonical formatter deadline exceeded")
            self.current_stage = "END_TO_END_PRESENTATION_PLANNING"
            presentation_plan = self.planner.plan(graph, response_language=plan.response_language)
            presentation_plans.append(presentation_plan)
            planning_ms += presentation_plan.planning_duration_ms
            if not presentation_plan.query_entries:
                self._record_formatter_audit(presentation_plan, 0, 0, "", "FAILED_NO_QUERY_ENTRY", 0.0)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_QUERY_ENTRY_MISSING",
                        message="The selected canonical graph did not contain a query-entry unit, so no human answer was formatted.",
                        severity="WARN",
                        metadata={"graphId": presentation_plan.graph_id},
                    )
                )
                continue
            self.current_stage = "END_TO_END_TEXT_RENDERING"
            try:
                text, provider_calls, repair_calls, formatter_ms, prompt_hash, validation_result, segment_count = self._render_text(
                    presentation_plan,
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
                self._record_formatter_audit(presentation_plan, provider_calls, repair_calls, prompt_hash, validation_result, formatter_ms)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_FAILED",
                        message="The canonical formatter failed validation for a selected end-to-end graph.",
                        severity="WARN",
                        metadata={"graphId": presentation_plan.graph_id},
                    )
                )
                total_provider_calls += provider_calls
                total_repair_calls += repair_calls
                total_formatter_ms += formatter_ms
                total_segment_count += segment_count
                continue
            self._record_formatter_audit(presentation_plan, provider_calls, repair_calls, prompt_hash, validation_result, formatter_ms)
            total_provider_calls += provider_calls
            total_repair_calls += repair_calls
            total_formatter_ms += formatter_ms
            total_segment_count += segment_count
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
        metrics = self._metrics(
            presentation_plans,
            planning_ms,
            answer_count=len(answers),
            provider_call_count=total_provider_calls,
            repair_call_count=total_repair_calls,
            formatter_duration_ms=total_formatter_ms,
            segment_count=total_segment_count,
        )
        self.pipeline_records.append(metrics)
        if graphs and not answers:
            self.current_stage = "END_TO_END_TEXT_RENDERING"
            raise EndToEndFormatterAllGraphsFailed("no canonical graph answer succeeded")
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

    def _render_text(
        self,
        plan: EndToEndPresentationPlan,
        *,
        deadline_at: float,
        cancel_event: Any | None,
    ) -> tuple[str, int, int, float, str, str, int]:
        segments = self.segment_planner.segments(plan)
        if not segments:
            raise EndToEndFormatterValidationError(("presentation plan contains no canonical stages",))
        validation_errors: tuple[str, ...] = ()
        provider_call_count = 0
        repair_call_count = 0
        formatter_duration_ms = 0.0
        prompt_hashes: list[str] = []
        last_errors: tuple[str, ...] = ()
        for attempt_index in (0, 1):
            if attempt_index == 1:
                repair_call_count += len(segments)
            segment_steps: dict[str, dict[str, Any]] = {}
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
                    parsed_steps = self._validate_provider_steps(result.raw_text, plan, segment)
                except EndToEndFormatterValidationError as exc:
                    structure_errors.extend(exc.errors)
                    continue
                segment_steps.update(parsed_steps)
            if structure_errors:
                last_errors = tuple(structure_errors)
                if attempt_index == 0:
                    validation_errors = last_errors
                    continue
                break
            ordered_steps = [segment_steps[stage.stage_ref] for stage in plan.stages]
            text = "\n".join(str(step["text"]).strip() for step in ordered_steps if str(step.get("text") or "").strip())
            language_result = self.language_validator.validate(text, plan.response_language)
            if language_result.valid:
                return text, provider_call_count, repair_call_count, round(formatter_duration_ms, 3), _sha256("|".join(prompt_hashes)), "VALID", len(segments)
            last_errors = tuple(language_result.errors)
            if attempt_index == 0:
                validation_errors = last_errors
                continue
        error = EndToEndFormatterValidationError(last_errors or ("canonical formatter validation failed",))
        error.provider_calls = provider_call_count
        error.repair_calls = repair_call_count
        error.formatter_duration_ms = round(formatter_duration_ms, 3)
        error.prompt_hash = _sha256("|".join(prompt_hashes))
        error.validation_result = "FAILED"
        error.segment_count = len(segments)
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

    def _validate_provider_steps(
        self,
        raw_text: str,
        plan: EndToEndPresentationPlan,
        segment: EndToEndFormatterSegment,
    ) -> dict[str, dict[str, Any]]:
        errors: list[str] = []
        try:
            payload = json.loads(raw_text)
        except (TypeError, ValueError):
            raise EndToEndFormatterValidationError(("formatter response is not valid JSON",))
        if not isinstance(payload, dict):
            raise EndToEndFormatterValidationError(("formatter response must be a JSON object",))
        if set(payload) != {"steps"}:
            errors.append("formatter response must contain exactly the 'steps' field")
        steps = payload.get("steps")
        if not isinstance(steps, list):
            errors.append("formatter response steps must be an array")
            raise EndToEndFormatterValidationError(errors)
        expected_refs = tuple(segment.stage_refs)
        actual_refs = tuple(str(step.get("stageRef") or "") for step in steps if isinstance(step, dict))
        if actual_refs != expected_refs:
            errors.append(f"formatter steps must preserve exact stage order {list(expected_refs)}")
        if len(actual_refs) != len(set(actual_refs)):
            errors.append("formatter response contains duplicate stage refs")
        unknown = tuple(ref for ref in actual_refs if ref not in expected_refs)
        if unknown:
            errors.append(f"formatter response contains unknown stage refs: {list(unknown)}")
        missing = tuple(ref for ref in expected_refs if ref not in actual_refs)
        if missing:
            errors.append(f"formatter response is missing stage refs: {list(missing)}")
        stage_by_ref = {stage.stage_ref: stage for stage in plan.stages}
        validated: dict[str, dict[str, Any]] = {}
        for index, step in enumerate(steps):
            if not isinstance(step, dict):
                errors.append(f"formatter step {index} must be an object")
                continue
            if set(step) != {"stageRef", "coveredFactRefs", "text"}:
                errors.append(f"formatter step {index} must contain exactly stageRef, coveredFactRefs, and text")
            stage_ref = str(step.get("stageRef") or "")
            stage = stage_by_ref.get(stage_ref)
            covered = step.get("coveredFactRefs")
            if not isinstance(covered, list) or not all(isinstance(item, str) and item for item in covered):
                errors.append(f"formatter step {stage_ref or index} coveredFactRefs must be non-empty strings")
                covered = []
            if stage is not None:
                owned = set(stage.canonical_fact_refs)
                unknown_facts = tuple(str(item) for item in covered if str(item) not in owned)
                if unknown_facts:
                    errors.append(f"formatter step {stage_ref} covers facts not owned by that stage: {list(unknown_facts)}")
            text = str(step.get("text") or "").strip()
            if not text:
                errors.append(f"formatter step {stage_ref or index} text must be non-empty")
            if stage is not None:
                errors.extend(self._validate_text_against_stage(text, stage))
            validated[stage_ref] = {"stageRef": stage_ref, "coveredFactRefs": list(covered), "text": text}
        if errors:
            raise EndToEndFormatterValidationError(tuple(errors))
        return validated

    def _validate_text_against_stage(self, text: str, stage: EndToEndPresentationStage) -> tuple[str, ...]:
        errors: list[str] = []
        allowed_values = _approved_public_values(stage.payload)
        for route in _HTTP_ROUTE_RE.findall(text):
            if route not in allowed_values:
                errors.append(f"formatter step {stage.stage_ref} invented HTTP route {route}")
        for method in _HTTP_METHOD_RE.findall(text):
            if method.upper() not in allowed_values:
                errors.append(f"formatter step {stage.stage_ref} invented HTTP method {method.upper()}")
        if stage.kind in {"OPEN_BOUNDARY_AMBIGUOUS", "OPEN_BOUNDARY_UNRESOLVED"} and _PROVEN_BOUNDARY_TEXT_RE.search(text):
            errors.append(f"formatter step {stage.stage_ref} describes an open boundary as proven or selected")
        return tuple(errors)

    def _record_formatter_audit(
        self,
        plan: EndToEndPresentationPlan,
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
                "stageCount": len(plan.stages),
                "factCount": len(plan.canonical_fact_refs),
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
        plans: Sequence[EndToEndPresentationPlan],
        planning_ms: float,
        *,
        answer_count: int,
        provider_call_count: int,
        repair_call_count: int,
        formatter_duration_ms: float,
        segment_count: int,
    ) -> dict[str, Any]:
        stage_count = sum(len(plan.stages) for plan in plans)
        stage_ownership = [
            {"stageRef": stage.stage_ref, "kind": stage.kind, "ownedFactRefs": list(stage.canonical_fact_refs)}
            for plan in plans
            for stage in plan.stages
        ]
        prompt_seed = json.dumps([[plan.graph_id, [stage.stage_ref for stage in plan.stages]] for plan in plans], sort_keys=True)
        return {
            "selectedGraphCount": len(plans),
            "presentationStageCount": stage_count,
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
            "stageCountContractMatched": True,
            "stageCountContractExpected": stage_count,
            "expectedPublicStageCount": stage_count,
            "expectedPresentationStageCount": stage_count,
            "validatedFormatterStepCount": stage_count if answer_count else 0,
            "stitchedPublicStepCount": stage_count if answer_count else 0,
            "publicStepCount": stage_count if answer_count else 0,
            "provenTransitionCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "PROVEN_BOUNDARY_CONTINUATION"),
            "openAmbiguousBoundaryCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "OPEN_BOUNDARY_AMBIGUOUS"),
            "openUnresolvedBoundaryCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "OPEN_BOUNDARY_UNRESOLVED"),
            "branchCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "BRANCH"),
            "structuralStageCount": sum(1 for plan in plans for stage in plan.stages if stage.kind in {"BRANCH", "CONVERGENCE", "CYCLE_REFERENCE", "SHARED_UNIT_REFERENCE"}),
            "presentationStageRefs": [stage.stage_ref for plan in plans for stage in plan.stages],
            "presentationStages": [
                {"stageRef": stage.stage_ref, "kind": stage.kind, "ownedFactRefs": list(stage.canonical_fact_refs)}
                for plan in plans
                for stage in plan.stages
            ],
            "stageOwnershipRecords": stage_ownership,
            "deduplicatedFactCount": len({fact for plan in plans for fact in plan.canonical_fact_refs}),
            "missingStageRefs": 0,
            "duplicateStageRefs": 0,
            "unownedFactRefs": 0,
            "duplicateFactRefs": 0,
            "promptHash": _sha256(prompt_seed),
        }

    def _check_cancelled(self, cancel_event: Any | None) -> None:
        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)():
            raise EndToEndFormatterDeadlineExceeded("canonical formatter cancelled")


def _identity_ref(identity: Any) -> str:
    return f"{identity.source_id}:{identity.graph_revision}:{identity.boundary_key}:{identity.owner_node_id}"


def _dataclass_payload(item: Any) -> dict[str, Any]:
    if hasattr(item, "__dataclass_fields__"):
        return {key: _json_safe(getattr(item, key)) for key in item.__dataclass_fields__}
    if isinstance(item, Mapping):
        return {str(key): _json_safe(value) for key, value in item.items()}
    return {"value": _json_safe(item)}


def _query_entry_payload(item: KnowledgeGraphAnswerQueryEntry) -> dict[str, Any]:
    return {"unitId": item.unitId, "sourceId": item.sourceId, "root": dict(item.root or {})}


def _json_safe(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_json_safe(item) for item in value]
    if hasattr(value, "value"):
        return value.value
    if hasattr(value, "__dataclass_fields__"):
        return _dataclass_payload(value)
    return value


def _approved_public_values(value: Any) -> set[str]:
    values: set[str] = set()

    def visit(item: Any) -> None:
        if isinstance(item, Mapping):
            for child in item.values():
                visit(child)
            return
        if isinstance(item, (list, tuple, set, frozenset)):
            for child in item:
                visit(child)
            return
        text = str(item or "").strip()
        if text:
            values.add(text)
            values.add(text.upper())

    visit(value)
    return values


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


_HTTP_ROUTE_RE = re.compile(r"(?<!\w)/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+/?)+")
_HTTP_METHOD_RE = re.compile(r"\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b", re.IGNORECASE)
_PROVEN_BOUNDARY_TEXT_RE = re.compile(
    r"\b(proven|verified|confirmed|selected target|target selected|доведен|підтвердж|sélectionn|prouvé|confirmé|bewiesen|bestätigt|ausgewählt)\b",
    re.IGNORECASE,
)
