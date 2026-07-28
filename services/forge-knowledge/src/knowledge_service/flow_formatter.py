from __future__ import annotations

import hashlib
import json
import re
import time
from collections import Counter, defaultdict, deque
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


class EndToEndFormatterStageTooLarge(EndToEndFormatterValidationError):
    def __init__(self, *, graph_id: str, stage_ref: str, serialized_character_count: int, configured_character_budget: int) -> None:
        self.graph_id = graph_id
        self.stage_ref = stage_ref
        self.serialized_character_count = serialized_character_count
        self.configured_character_budget = configured_character_budget
        super().__init__(
            (
                "END_TO_END_FORMATTER_STAGE_TOO_LARGE",
                f"graphId={graph_id}",
                f"stageRef={stage_ref}",
                f"serializedCharacterCount={serialized_character_count}",
                f"configuredCharacterBudget={configured_character_budget}",
            )
        )


@dataclass(frozen=True)
class CanonicalFormatterAssertion:
    assertion_ref: str
    predicate: str
    subject_ref: str
    object_ref: str | None = None
    value: str | None = None


@dataclass(frozen=True)
class EndToEndPresentationStage:
    stage_ref: str
    kind: str
    owned_fact_refs: tuple[str, ...]
    context_fact_refs: tuple[str, ...]
    required_assertions: tuple[CanonicalFormatterAssertion, ...]
    allowed_canonical_refs: tuple[str, ...]
    canonical_display_values: Mapping[str, str]
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
    context_fact_refs: tuple[str, ...]
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
                stage = _STAGE_BUILDERS["convergence"](self, unit_id, inbound)
                stages.append(stage)
                shared = _STAGE_BUILDERS["shared_unit"](self, unit_id, inbound)
                stages.append(shared)
            unit_stage = _STAGE_BUILDERS["unit"](self, ref)
            stages.append(unit_stage)
            outbound = tuple(transitions_by_source.get(unit_id, ()))
            if len(outbound) > 1:
                stage = _STAGE_BUILDERS["branch"](self, unit_id, outbound)
                stages.append(stage)
            for transition in outbound:
                transition_stage = _STAGE_BUILDERS["transition"](self, transition)
                stages.append(transition_stage)
                emitted_transitions.add(transition.stable_transition_id)

        for transition in sorted(graph.proven_cross_source_transitions, key=lambda item: item.stable_transition_id):
            if transition.stable_transition_id in emitted_transitions:
                continue
            transition_stage = _STAGE_BUILDERS["transition"](self, transition)
            stages.append(transition_stage)

        for boundary in sorted(graph.open_boundaries, key=lambda item: (str(item.status), item.required_boundary_identity.boundary_key, tuple(item.source_unit_ids))):
            stage = _STAGE_BUILDERS["open_boundary"](self, boundary)
            stages.append(stage)

        if graph.coverage.cycle_count:
            stage = _STAGE_BUILDERS["cycle"](self, graph)
            stages.append(stage)

        stage_diagnostics, canonical_fact_refs, context_fact_refs = _validate_presentation_stage_ownership(stages, graph.stable_graph_id)
        diagnostics.extend(stage_diagnostics)
        return EndToEndPresentationPlan(
            graph_id=graph.stable_graph_id,
            response_language=response_language,
            sources=tuple(sorted({ref.source_id for ref in graph.unit_refs})),
            query_entries=query_entries,
            topology_entries=tuple(graph.topology_entry_unit_ids),
            stages=tuple(stages),
            canonical_fact_refs=canonical_fact_refs,
            context_fact_refs=context_fact_refs,
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
        stage_ref = f"unit:{unit.unit_id}"
        return _presentation_stage(
            stage_ref=stage_ref,
            kind="UNIT_ENTRY" if ref.query_selected_initial else "LOCAL_EXECUTION",
            owned_fact_refs=fact_refs,
            context_fact_refs=(),
            required_assertions=(
                _assertion(
                    stage_ref,
                    "unit-execution",
                    predicate="UNIT_EXECUTION",
                    subject_ref=f"unit:{unit.unit_id}",
                    value="CONNECTED",
                ),
            ),
            allowed_canonical_refs=(),
            canonical_display_values=self._unit_display_values(unit),
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
        return _presentation_stage(
            stage_ref=fact_ref,
            kind="PROVEN_BOUNDARY_CONTINUATION",
            owned_fact_refs=tuple(sorted(set(fact_refs))),
            context_fact_refs=tuple(sorted({f"unit:{transition.source_unit_id}", f"unit:{transition.target_unit_id}"})),
            required_assertions=(
                _assertion(
                    fact_ref,
                    "connectivity",
                    predicate="CONNECTIVITY_STATUS",
                    subject_ref=f"unit:{transition.source_unit_id}",
                    object_ref=f"unit:{transition.target_unit_id}",
                    value="PROVEN",
                ),
            ),
            allowed_canonical_refs=(),
            canonical_display_values={
                f"transition:{transition.stable_transition_id}": transition.stable_transition_id,
                f"resolution:{transition.resolution_id}": transition.resolution_id,
                f"unit:{transition.source_unit_id}": transition.source_unit_id,
                f"unit:{transition.target_unit_id}": transition.target_unit_id,
                f"required-boundary:{_identity_ref(transition.required_endpoint.boundary_identity)}": transition.required_endpoint.boundary_identity.boundary_key,
                f"provided-boundary:{_identity_ref(transition.provided_endpoint.boundary_identity)}": transition.provided_endpoint.boundary_identity.boundary_key,
            },
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
        kind = _OPEN_BOUNDARY_AMBIGUOUS_STAGE_KIND if status == "AMBIGUOUS" else _OPEN_BOUNDARY_UNRESOLVED_STAGE_KIND
        fact_ref = f"open-boundary:{_identity_ref(boundary.required_boundary_identity)}:{','.join(boundary.source_unit_ids)}"
        assertions = [
            _assertion(
                fact_ref,
                "boundary-status",
                predicate="BOUNDARY_STATUS",
                subject_ref=fact_ref,
                value=status,
            ),
            _assertion(
                fact_ref,
                "target-selection",
                predicate="TARGET_SELECTION_STATUS",
                subject_ref=fact_ref,
                value="NONE",
            ),
            _assertion(
                fact_ref,
                "proof-status",
                predicate="PROOF_STATUS",
                subject_ref=fact_ref,
                value="NOT_PROVEN",
            ),
        ]
        if status == "AMBIGUOUS":
            assertions.append(
                _assertion(
                    fact_ref,
                    "candidate-cardinality",
                    predicate="CANDIDATE_CARDINALITY",
                    subject_ref=fact_ref,
                    value="MULTIPLE",
                )
            )
        return _presentation_stage(
            stage_ref=fact_ref,
            kind=kind,
            owned_fact_refs=(fact_ref,),
            context_fact_refs=tuple(sorted(f"unit:{unit_id}" for unit_id in boundary.source_unit_ids)),
            required_assertions=tuple(assertions),
            allowed_canonical_refs=tuple(
                sorted(
                    {
                        f"candidate-owner:{owner.source_id}:{owner.graph_revision}:{owner.owner_node_id}"
                        for owner in boundary.viable_candidate_owner_identities
                    }
                    | {
                        f"candidate-boundary:{_identity_ref(item)}"
                        for item in boundary.viable_candidate_boundary_identities
                    }
                )
            ),
            canonical_display_values={
                fact_ref: boundary.required_boundary_identity.boundary_key,
                **{f"unit:{unit_id}": unit_id for unit_id in boundary.source_unit_ids},
                **{
                    f"candidate-owner:{owner.source_id}:{owner.graph_revision}:{owner.owner_node_id}": owner.owner_node_id
                    for owner in boundary.viable_candidate_owner_identities
                },
                **{
                    f"candidate-boundary:{_identity_ref(item)}": item.boundary_key
                    for item in boundary.viable_candidate_boundary_identities
                },
            },
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
        return _presentation_stage(
            stage_ref=fact_ref,
            kind="BRANCH",
            owned_fact_refs=(fact_ref,),
            context_fact_refs=tuple(sorted({f"unit:{source_unit_id}", *tuple(f"transition:{item}" for item in transition_ids)})),
            required_assertions=(
                _assertion(
                    fact_ref,
                    "structural-relation",
                    predicate="STRUCTURAL_RELATION",
                    subject_ref=f"unit:{source_unit_id}",
                    value="BRANCH",
                ),
            ),
            allowed_canonical_refs=tuple(sorted(f"unit:{item.target_unit_id}" for item in transitions)),
            canonical_display_values={
                fact_ref: "branch",
                f"unit:{source_unit_id}": source_unit_id,
                **{f"unit:{item.target_unit_id}": item.target_unit_id for item in transitions},
                **{f"transition:{item}": item for item in transition_ids},
            },
            payload={"sourceUnitId": source_unit_id, "transitionIds": list(transition_ids), "targetUnitIds": sorted({item.target_unit_id for item in transitions})},
        )

    def _convergence_stage(self, target_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in transitions))
        fact_ref = f"convergence:{target_unit_id}:{_sha256('|'.join(transition_ids))[:12]}"
        return _presentation_stage(
            stage_ref=fact_ref,
            kind="CONVERGENCE",
            owned_fact_refs=(fact_ref,),
            context_fact_refs=tuple(sorted({f"unit:{target_unit_id}", *tuple(f"transition:{item}" for item in transition_ids)})),
            required_assertions=(
                _assertion(
                    fact_ref,
                    "structural-relation",
                    predicate="STRUCTURAL_RELATION",
                    subject_ref=f"unit:{target_unit_id}",
                    value="CONVERGENCE",
                ),
            ),
            allowed_canonical_refs=tuple(sorted(f"unit:{item.source_unit_id}" for item in transitions)),
            canonical_display_values={
                fact_ref: "convergence",
                f"unit:{target_unit_id}": target_unit_id,
                **{f"unit:{item.source_unit_id}": item.source_unit_id for item in transitions},
                **{f"transition:{item}": item for item in transition_ids},
            },
            payload={"targetUnitId": target_unit_id, "transitionIds": list(transition_ids), "sourceUnitIds": sorted({item.source_unit_id for item in transitions})},
        )

    def _shared_unit_stage(self, target_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in transitions))
        fact_ref = f"shared-unit:{target_unit_id}:{_sha256('|'.join(transition_ids))[:12]}"
        return _presentation_stage(
            stage_ref=fact_ref,
            kind="SHARED_UNIT_REFERENCE",
            owned_fact_refs=(fact_ref,),
            context_fact_refs=tuple(sorted({f"unit:{target_unit_id}", *tuple(f"transition:{item}" for item in transition_ids)})),
            required_assertions=(
                _assertion(
                    fact_ref,
                    "structural-relation",
                    predicate="STRUCTURAL_RELATION",
                    subject_ref=f"unit:{target_unit_id}",
                    value="SHARED_UNIT",
                ),
            ),
            allowed_canonical_refs=(),
            canonical_display_values={
                fact_ref: "shared unit",
                f"unit:{target_unit_id}": target_unit_id,
                **{f"transition:{item}": item for item in transition_ids},
            },
            payload={"unitId": target_unit_id, "transitionIds": list(transition_ids), "renderedOnce": True},
        )

    def _cycle_stage(self, graph: EndToEndFlowGraph) -> EndToEndPresentationStage:
        transition_ids = tuple(sorted(transition.stable_transition_id for transition in graph.proven_cross_source_transitions))
        fact_ref = f"cycle:{graph.stable_graph_id}"
        return _presentation_stage(
            stage_ref=fact_ref,
            kind="CYCLE_REFERENCE",
            owned_fact_refs=(fact_ref,),
            context_fact_refs=tuple(sorted(f"transition:{item}" for item in transition_ids)),
            required_assertions=(
                _assertion(
                    fact_ref,
                    "structural-relation",
                    predicate="STRUCTURAL_RELATION",
                    subject_ref=fact_ref,
                    value="CYCLE",
                ),
            ),
            allowed_canonical_refs=(),
            canonical_display_values={
                fact_ref: "cycle",
                **{f"transition:{item}": item for item in transition_ids},
            },
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

    def _unit_display_values(self, unit: Any) -> dict[str, str]:
        values = {f"unit:{unit.unit_id}": self._unit_display(unit)}
        for root in unit.roots:
            node = root.node
            values[f"root:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}"] = self._node_display(node)
        for node in unit.execution_nodes:
            values[f"node:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}"] = self._node_display(node)
        for edge in unit.execution_transitions:
            values[f"edge:{edge.source_id}:{edge.graph_revision or edge.graph_id}:{edge.edge_id}"] = self._edge_display(edge)
        for edge in unit.topology_boundaries:
            values[f"topology-boundary:{edge.source_id}:{edge.graph_revision or edge.graph_id}:{edge.edge_id}"] = self._edge_display(edge)
        for boundary in unit.generic_boundaries:
            values[f"generic-boundary:{_identity_ref(boundary_identity(boundary))}"] = str(boundary.stable_key or boundary.boundary_id)
        for node in unit.supporting_context:
            values[f"context:{node.source_id}:{node.graph_revision or node.graph_id}:{node.node_id}"] = self._node_display(node)
        for evidence in unit.evidence:
            values[f"evidence:{evidence.source_id}:{evidence.graph_revision or evidence.graph_id}:{evidence.evidence_id}"] = self._evidence_display(evidence)
        return values

    def _unit_display(self, unit: Any) -> str:
        for root in unit.roots:
            display = self._node_display(root.node)
            if display:
                return display
        return str(unit.unit_id)

    def _node_display(self, node: Any) -> str:
        return str(getattr(node, "qualified_name", None) or getattr(node, "label", None) or getattr(node, "node_id", "") or "")

    def _edge_display(self, edge: Any) -> str:
        unresolved = getattr(edge, "unresolved_target", None)
        if isinstance(unresolved, Mapping):
            for key in ("qualifiedName", "qualified_name", "name", "symbol", "target"):
                value = unresolved.get(key)
                if value:
                    return str(value)
        return str(getattr(edge, "to_node_id", None) or getattr(edge, "edge_id", "") or "")

    def _evidence_display(self, evidence: Any) -> str:
        location = str(getattr(evidence, "relative_path", None) or getattr(evidence, "evidence_id", "") or "")
        line_start = getattr(evidence, "line_start", None)
        if line_start is not None:
            return f"{location}:{line_start}"
        return location

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


def _build_unit_stage(planner: EndToEndPresentationPlanner, ref: Any) -> EndToEndPresentationStage:
    return planner._unit_stage(ref)


def _build_transition_stage(planner: EndToEndPresentationPlanner, transition: Any) -> EndToEndPresentationStage:
    return planner._transition_stage(transition)


def _build_open_boundary_stage(planner: EndToEndPresentationPlanner, boundary: Any) -> EndToEndPresentationStage:
    return planner._open_boundary_stage(boundary)


def _build_branch_stage(planner: EndToEndPresentationPlanner, source_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
    return planner._branch_stage(source_unit_id, transitions)


def _build_convergence_stage(planner: EndToEndPresentationPlanner, target_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
    return planner._convergence_stage(target_unit_id, transitions)


def _build_shared_unit_stage(planner: EndToEndPresentationPlanner, target_unit_id: str, transitions: Sequence[Any]) -> EndToEndPresentationStage:
    return planner._shared_unit_stage(target_unit_id, transitions)


def _build_cycle_stage(planner: EndToEndPresentationPlanner, graph: EndToEndFlowGraph) -> EndToEndPresentationStage:
    return planner._cycle_stage(graph)


_STAGE_BUILDERS = {
    "unit": _build_unit_stage,
    "transition": _build_transition_stage,
    "open_boundary": _build_open_boundary_stage,
    "branch": _build_branch_stage,
    "convergence": _build_convergence_stage,
    "shared_unit": _build_shared_unit_stage,
    "cycle": _build_cycle_stage,
}


class EndToEndFormatterPromptRenderer:
    def render(self, formatter_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        payload = json.dumps(dict(formatter_input), ensure_ascii=False, indent=2, sort_keys=True)
        errors = "\n".join(f"- {item}" for item in validation_errors or ())
        repair = f"\nPrevious JSON failed validation. Correct these exact issues:\n{errors}\n" if errors else ""
        return (
            "Format canonical end-to-end execution graph facts as grounded prose.\n"
            "Return strict JSON only. Do not include prose outside JSON.\n"
            "The JSON shape is exactly: {\"steps\":[{\"stageRef\":\"string\",\"coveredFactRefs\":[\"string\"],\"assertions\":[{\"assertionRef\":\"string\",\"predicate\":\"string\",\"subjectRef\":\"string\",\"objectRef\":\"string|null\",\"value\":\"string|null\"}],\"referencedCanonicalRefs\":[\"string\"],\"textTemplate\":\"string with {{ref:canonical-ref}} placeholders\"}]}.\n"
            "Return exactly one step per supplied stage, in the supplied stageOrder.\n"
            "coveredFactRefs must exactly equal the supplied ownedFactRefs for the same stage, sorted in ascending order.\n"
            "assertions must exactly equal requiredAssertions for the same stage, sorted by assertionRef.\n"
            "referencedCanonicalRefs may contain only allowedCanonicalRefs for that same stage and every referencedCanonicalRef must appear as a {{ref:...}} placeholder.\n"
            "Use responseLanguage for every text value.\n"
            "Use requiredAssertions as the semantic source of truth and do not add claims outside those assertions.\n"
            "Use placeholders instead of printing canonical IDs, symbols, routes, methods, transitions, or sources directly.\n"
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
            stage_serialized = self._serialize_input(base, (stage,))
            if len(stage_serialized) > max_chars:
                raise EndToEndFormatterStageTooLarge(
                    graph_id=plan.graph_id,
                    stage_ref=stage.stage_ref,
                    serialized_character_count=len(stage_serialized),
                    configured_character_budget=max_chars,
                )
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
            "ownedFactRefs": list(stage.owned_fact_refs),
            "contextFactRefs": list(stage.context_fact_refs),
            "requiredAssertions": [_assertion_payload(item) for item in stage.required_assertions],
            "allowedCanonicalRefs": list(stage.allowed_canonical_refs),
            "canonicalDisplayValues": dict(stage.canonical_display_values),
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
        planned_presentations: list[EndToEndPresentationPlan] = []
        planning_ms = 0.0
        total_provider_calls = 0
        total_repair_calls = 0
        total_formatter_ms = 0.0
        total_segment_count = 0
        failed_graph_ids: list[str] = []
        validation_summaries: list[Mapping[str, Any]] = []
        for graph in graphs:
            self._check_cancelled(cancel_event)
            if time.monotonic() >= deadline_at:
                raise EndToEndFormatterDeadlineExceeded("canonical formatter deadline exceeded")
            self.current_stage = "END_TO_END_PRESENTATION_PLANNING"
            presentation_plan = self.planner.plan(graph, response_language=plan.response_language)
            planned_presentations.append(presentation_plan)
            planning_ms += presentation_plan.planning_duration_ms
            invalid_plan_diagnostics = tuple(item for item in presentation_plan.diagnostics if item.code == "END_TO_END_PRESENTATION_OWNERSHIP_INVALID")
            if invalid_plan_diagnostics:
                self._record_formatter_audit(presentation_plan, 0, 0, "", "FAILED_PLAN_OWNERSHIP", 0.0)
                failed_graph_ids.append(presentation_plan.graph_id)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_PLAN_INVALID",
                        message="The canonical presentation plan failed ownership validation.",
                        severity="WARN",
                        metadata={
                            "graphId": presentation_plan.graph_id,
                            "diagnosticCodes": tuple(item.code for item in invalid_plan_diagnostics),
                        },
                    )
                )
                continue
            if not presentation_plan.query_entries:
                self._record_formatter_audit(presentation_plan, 0, 0, "", "FAILED_NO_QUERY_ENTRY", 0.0)
                failed_graph_ids.append(presentation_plan.graph_id)
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
                text, provider_calls, repair_calls, formatter_ms, prompt_hash, validation_result, segment_count, validation_summary = self._render_text(
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
                validation_summary = dict(getattr(exc, "validation_summary", {}) or {})
                self._record_formatter_audit(presentation_plan, provider_calls, repair_calls, prompt_hash, validation_result, formatter_ms)
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="FINAL_FORMATTER_FAILED",
                        message="The canonical formatter failed validation for a selected end-to-end graph.",
                        severity="WARN",
                        metadata={"graphId": presentation_plan.graph_id},
                    )
                )
                failed_graph_ids.append(presentation_plan.graph_id)
                total_provider_calls += provider_calls
                total_repair_calls += repair_calls
                total_formatter_ms += formatter_ms
                total_segment_count += segment_count
                validation_summaries.append(validation_summary)
                continue
            self._record_formatter_audit(presentation_plan, provider_calls, repair_calls, prompt_hash, validation_result, formatter_ms)
            total_provider_calls += provider_calls
            total_repair_calls += repair_calls
            total_formatter_ms += formatter_ms
            total_segment_count += segment_count
            validation_summaries.append(validation_summary)
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
            planned_presentations,
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
            self.current_stage = "END_TO_END_TEXT_RENDERING"
            failure = EndToEndFormatterAllGraphsFailed("one or more selected canonical graph answers failed")
            failure.failed_graph_ids = tuple(failed_graph_ids)
            failure.diagnostics = tuple(diagnostics)
            raise failure
        if graphs and len(answers) != len(graphs):
            self.current_stage = "END_TO_END_TEXT_RENDERING"
            failure = EndToEndFormatterAllGraphsFailed("selectedGraphCount did not equal humanAnswerCount")
            failure.failed_graph_ids = tuple(graph.stable_graph_id for graph in graphs)
            failure.diagnostics = tuple(diagnostics)
            raise failure
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
    ) -> tuple[str, int, int, float, str, str, int, dict[str, Any]]:
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
            segment_steps: dict[str, list[dict[str, Any]]] = defaultdict(list)
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
                for stage_ref, step in parsed_steps.items():
                    segment_steps[stage_ref].append(step)
            if structure_errors:
                last_errors = tuple(structure_errors)
                if attempt_index == 0:
                    validation_errors = last_errors
                    continue
                break
            try:
                validation_summary = self._validate_combined_provider_steps(plan, segment_steps)
            except EndToEndFormatterValidationError as exc:
                last_errors = exc.errors
                if attempt_index == 0:
                    validation_errors = last_errors
                    continue
                break
            ordered_steps = [segment_steps[stage.stage_ref][0] for stage in plan.stages]
            text = "\n".join(str(step["text"]).strip() for step in ordered_steps if str(step.get("text") or "").strip())
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
        error.validation_summary = _formatter_validation_summary(plan, {})
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
            if set(step) != {"stageRef", "coveredFactRefs", "assertions", "referencedCanonicalRefs", "textTemplate"}:
                errors.append(f"formatter step {index} must contain exactly stageRef, coveredFactRefs, assertions, referencedCanonicalRefs, and textTemplate")
            stage_ref = str(step.get("stageRef") or "")
            stage = stage_by_ref.get(stage_ref)
            covered = step.get("coveredFactRefs")
            if not isinstance(covered, list) or not all(isinstance(item, str) and item for item in covered):
                errors.append(f"formatter step {stage_ref or index} coveredFactRefs must be non-empty strings")
                covered = []
            raw_assertions = step.get("assertions")
            assertions = _normalised_assertion_payloads(raw_assertions)
            if assertions is None:
                errors.append(f"formatter step {stage_ref or index} assertions must be assertion objects")
                assertions = []
            referenced = step.get("referencedCanonicalRefs")
            if not isinstance(referenced, list) or not all(isinstance(item, str) and item for item in referenced):
                errors.append(f"formatter step {stage_ref or index} referencedCanonicalRefs must be strings")
                referenced = []
            if stage is not None:
                expected_coverage = list(stage.owned_fact_refs)
                sorted_covered = sorted(dict.fromkeys(str(item) for item in covered))
                if list(covered) != sorted_covered:
                    errors.append(f"formatter step {stage_ref} coveredFactRefs must be sorted and deduplicated")
                if set(covered) != set(stage.owned_fact_refs):
                    missing = sorted(set(stage.owned_fact_refs) - set(covered))
                    extra = sorted(set(covered) - set(stage.owned_fact_refs))
                    errors.append(f"formatter step {stage_ref} coverage must equal owned facts; missing={missing}; extra={extra}")
                elif list(covered) != expected_coverage:
                    errors.append(f"formatter step {stage_ref} coveredFactRefs must preserve canonical owned fact order")
                expected_assertions = [_assertion_payload(item) for item in stage.required_assertions]
                if assertions != expected_assertions:
                    expected_refs = [item["assertionRef"] for item in expected_assertions]
                    actual_refs = [str(item.get("assertionRef") or "") for item in assertions]
                    missing_assertions = sorted(set(expected_refs) - set(actual_refs))
                    extra_assertions = sorted(set(actual_refs) - set(expected_refs))
                    errors.append(
                        f"formatter step {stage_ref} assertions must equal required assertions; missing={missing_assertions}; extra={extra_assertions}"
                    )
                elif assertions != sorted(assertions, key=lambda item: str(item.get("assertionRef") or "")):
                    errors.append(f"formatter step {stage_ref} assertions must be sorted by assertionRef")
                if len([item.get("assertionRef") for item in assertions]) != len({item.get("assertionRef") for item in assertions}):
                    errors.append(f"formatter step {stage_ref} assertions contain duplicate assertion refs")
                sorted_referenced = sorted(dict.fromkeys(str(item) for item in referenced))
                if list(referenced) != sorted_referenced:
                    errors.append(f"formatter step {stage_ref} referencedCanonicalRefs must be sorted and deduplicated")
                allowed_refs = set(stage.allowed_canonical_refs)
                unknown_references = tuple(str(item) for item in referenced if str(item) not in allowed_refs)
                if unknown_references:
                    errors.append(f"formatter step {stage_ref} references canonical refs outside the stage contract: {list(unknown_references)}")
            text_template = str(step.get("textTemplate") or "").strip()
            if not text_template:
                errors.append(f"formatter step {stage_ref or index} textTemplate must be non-empty")
            rendered_text = text_template
            if stage is not None:
                placeholder_result = _validate_placeholders(text_template, referenced, stage)
                errors.extend(placeholder_result.errors)
                rendered_text = placeholder_result.rendered_text
            validated[stage_ref] = {
                "stageRef": stage_ref,
                "coveredFactRefs": list(covered),
                "assertions": list(assertions),
                "referencedCanonicalRefs": list(referenced),
                "textTemplate": text_template,
                "text": rendered_text,
            }
        if errors:
            raise EndToEndFormatterValidationError(tuple(errors))
        return validated

    def _validate_combined_provider_steps(
        self,
        plan: EndToEndPresentationPlan,
        segment_steps: Mapping[str, Sequence[dict[str, Any]]],
    ) -> dict[str, Any]:
        summary = _formatter_validation_summary(plan, segment_steps)
        errors: list[str] = []
        if summary["missingStageRefs"]:
            errors.append(f"formatter response is missing stage refs: {summary['missingStageRefsList']}")
        if summary["duplicateStageRefs"]:
            errors.append(f"formatter response duplicated stage refs: {summary['duplicateStageRefsList']}")
        if summary["unknownStageRefs"]:
            errors.append(f"formatter response returned unknown stage refs: {summary['unknownStageRefsList']}")
        if summary["omittedOwnedFactRefs"]:
            errors.append(f"formatter omitted owned fact refs: {summary['omittedOwnedFactRefsList']}")
        if summary["duplicateFactRefs"]:
            errors.append(f"formatter covered owned facts more than once: {summary['duplicateFactRefsList']}")
        if summary["unownedFactRefs"]:
            errors.append(f"formatter covered unowned or wrong-stage fact refs: {summary['unownedFactRefsList']}")
        if errors:
            raise EndToEndFormatterValidationError(tuple(errors))
        return summary

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
        validation_summaries: Sequence[Mapping[str, Any]] = (),
    ) -> dict[str, Any]:
        stage_count = sum(len(plan.stages) for plan in plans)
        ownership_metrics = _presentation_ownership_metrics(plans)
        coverage_metrics = _rollup_formatter_validation_summaries(validation_summaries)
        missing_stage_refs = int(coverage_metrics.get("missingStageRefs") or 0)
        duplicate_stage_refs = int(ownership_metrics.get("duplicateStageRefs") or 0) + int(coverage_metrics.get("duplicateStageRefs") or 0)
        unowned_fact_refs = int(ownership_metrics.get("unownedFactRefs") or 0) + int(coverage_metrics.get("unownedFactRefs") or 0)
        duplicate_fact_refs = int(ownership_metrics.get("duplicateFactRefs") or 0) + int(coverage_metrics.get("duplicateFactRefs") or 0)
        public_step_count = int(coverage_metrics.get("publicStepCount") or 0) if answer_count else 0
        validated_step_count = int(coverage_metrics.get("validatedFormatterStepCount") or 0) if answer_count else 0
        stage_count_contract_matched = (
            int(answer_count) == len(plans)
            and missing_stage_refs == 0
            and duplicate_stage_refs == 0
            and unowned_fact_refs == 0
            and duplicate_fact_refs == 0
            and validated_step_count == stage_count
        )
        stage_ownership = [
            {
                "stageRef": stage.stage_ref,
                "kind": stage.kind,
                "ownedFactRefs": list(stage.owned_fact_refs),
                "contextFactRefs": list(stage.context_fact_refs),
            }
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
            "stageCountContractMatched": bool(stage_count_contract_matched),
            "stageCountContractExpected": stage_count,
            "expectedPublicStageCount": stage_count,
            "expectedPresentationStageCount": stage_count,
            "validatedFormatterStepCount": validated_step_count,
            "stitchedPublicStepCount": public_step_count,
            "publicStepCount": public_step_count,
            "provenTransitionCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "PROVEN_BOUNDARY_CONTINUATION"),
            "openAmbiguousBoundaryCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == _OPEN_BOUNDARY_AMBIGUOUS_STAGE_KIND),
            "openUnresolvedBoundaryCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == _OPEN_BOUNDARY_UNRESOLVED_STAGE_KIND),
            "branchCount": sum(1 for plan in plans for stage in plan.stages if stage.kind == "BRANCH"),
            "structuralStageCount": sum(1 for plan in plans for stage in plan.stages if stage.kind in {"BRANCH", "CONVERGENCE", "CYCLE_REFERENCE", "SHARED_UNIT_REFERENCE"}),
            "presentationStageRefs": [stage.stage_ref for plan in plans for stage in plan.stages],
            "presentationStages": [
                {
                    "stageRef": stage.stage_ref,
                    "kind": stage.kind,
                    "ownedFactRefs": list(stage.owned_fact_refs),
                    "contextFactRefs": list(stage.context_fact_refs),
                }
                for plan in plans
                for stage in plan.stages
            ],
            "stageOwnershipRecords": stage_ownership,
            "deduplicatedFactCount": len({fact for plan in plans for fact in plan.canonical_fact_refs}),
            "missingStageRefs": missing_stage_refs,
            "duplicateStageRefs": duplicate_stage_refs,
            "unownedFactRefs": unowned_fact_refs,
            "duplicateFactRefs": duplicate_fact_refs,
            "unknownStageRefs": int(coverage_metrics.get("unknownStageRefs") or 0),
            "omittedOwnedFactRefs": int(coverage_metrics.get("omittedOwnedFactRefs") or 0),
            "unknownOwnedFactRefs": int(ownership_metrics.get("unknownOwnedFactRefs") or 0),
            "unknownContextFactRefs": int(ownership_metrics.get("unknownContextFactRefs") or 0),
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


def _presentation_stage(
    *,
    stage_ref: str,
    kind: str,
    owned_fact_refs: Sequence[str],
    context_fact_refs: Sequence[str],
    required_assertions: Sequence[CanonicalFormatterAssertion],
    allowed_canonical_refs: Sequence[str],
    canonical_display_values: Mapping[str, str],
    payload: Mapping[str, Any],
) -> EndToEndPresentationStage:
    owned = _sorted_unique(owned_fact_refs)
    context = _sorted_unique(context_fact_refs)
    assertions = tuple(sorted(required_assertions, key=lambda item: item.assertion_ref))
    allowed = _sorted_unique((*owned, *context, *allowed_canonical_refs, *(canonical_display_values or {}).keys()))
    display_values = {ref: _canonical_ref_display(ref) for ref in allowed}
    display_values.update({str(ref): str(value) for ref, value in (canonical_display_values or {}).items() if str(ref).strip() and str(value).strip()})
    return EndToEndPresentationStage(
        stage_ref=stage_ref,
        kind=kind,
        owned_fact_refs=owned,
        context_fact_refs=context,
        required_assertions=assertions,
        allowed_canonical_refs=allowed,
        canonical_display_values={ref: display_values.get(ref, _canonical_ref_display(ref)) for ref in allowed},
        payload=payload,
    )


def _assertion(
    stage_ref: str,
    local_ref: str,
    *,
    predicate: str,
    subject_ref: str,
    object_ref: str | None = None,
    value: str | None = None,
) -> CanonicalFormatterAssertion:
    return CanonicalFormatterAssertion(
        assertion_ref=f"assertion:{stage_ref}:{local_ref}",
        predicate=predicate,
        subject_ref=subject_ref,
        object_ref=object_ref,
        value=value,
    )


def _assertion_payload(item: CanonicalFormatterAssertion) -> dict[str, str | None]:
    return {
        "assertionRef": item.assertion_ref,
        "predicate": item.predicate,
        "subjectRef": item.subject_ref,
        "objectRef": item.object_ref,
        "value": item.value,
    }


def _normalised_assertion_payloads(value: Any) -> list[dict[str, str | None]] | None:
    if not isinstance(value, list):
        return None
    result: list[dict[str, str | None]] = []
    for item in value:
        if not isinstance(item, Mapping) or set(item) != {"assertionRef", "predicate", "subjectRef", "objectRef", "value"}:
            return None
        assertion_ref = str(item.get("assertionRef") or "").strip()
        predicate = str(item.get("predicate") or "").strip()
        subject_ref = str(item.get("subjectRef") or "").strip()
        object_ref = item.get("objectRef")
        assertion_value = item.get("value")
        if not assertion_ref or not predicate or not subject_ref:
            return None
        if object_ref is not None:
            object_ref = str(object_ref).strip() or None
        if assertion_value is not None:
            assertion_value = str(assertion_value).strip() or None
        result.append(
            {
                "assertionRef": assertion_ref,
                "predicate": predicate,
                "subjectRef": subject_ref,
                "objectRef": object_ref,
                "value": assertion_value,
            }
        )
    return result


@dataclass(frozen=True)
class _PlaceholderValidationResult:
    errors: tuple[str, ...]
    rendered_text: str


def _validate_placeholders(
    text_template: str,
    referenced_canonical_refs: Sequence[str],
    stage: EndToEndPresentationStage,
) -> _PlaceholderValidationResult:
    errors: list[str] = []
    placeholders = tuple(_PLACEHOLDER_RE.findall(text_template))
    referenced = tuple(str(item) for item in referenced_canonical_refs)
    allowed = set(stage.allowed_canonical_refs)
    placeholder_set = set(placeholders)
    referenced_set = set(referenced)
    unknown_placeholders = sorted(ref for ref in placeholder_set if ref not in allowed)
    if unknown_placeholders:
        errors.append(f"formatter step {stage.stage_ref} contains unknown placeholders: {unknown_placeholders}")
    missing_placeholders = sorted(ref for ref in referenced_set if ref not in placeholder_set)
    if missing_placeholders:
        errors.append(f"formatter step {stage.stage_ref} declares refs without placeholders: {missing_placeholders}")
    undeclared_placeholders = sorted(ref for ref in placeholder_set if ref not in referenced_set)
    if undeclared_placeholders:
        errors.append(f"formatter step {stage.stage_ref} contains undeclared placeholders: {undeclared_placeholders}")
    rendered = _PLACEHOLDER_RE.sub(lambda match: stage.canonical_display_values.get(match.group(1), _canonical_ref_display(match.group(1))), text_template)
    if not rendered.strip():
        errors.append(f"formatter step {stage.stage_ref} rendered text must be non-empty")
    return _PlaceholderValidationResult(errors=tuple(errors), rendered_text=rendered.strip())


def _validate_presentation_stage_ownership(
    stages: Sequence[EndToEndPresentationStage],
    graph_id: str,
) -> tuple[list[KnowledgeQueryDiagnostic], tuple[str, ...], tuple[str, ...]]:
    stage_refs = [stage.stage_ref for stage in stages]
    duplicate_stage_refs = sorted(ref for ref, count in Counter(stage_refs).items() if count > 1)
    owned_by_fact: dict[str, list[str]] = defaultdict(list)
    unknown_owned_fact_refs: list[str] = []
    for stage in stages:
        for fact_ref in stage.owned_fact_refs:
            if not _valid_canonical_fact_ref(fact_ref):
                unknown_owned_fact_refs.append(str(fact_ref))
            owned_by_fact[str(fact_ref)].append(stage.stage_ref)
    duplicate_owned_fact_refs = sorted(fact_ref for fact_ref, owners in owned_by_fact.items() if len(owners) > 1)
    context_refs = tuple(sorted({str(ref) for stage in stages for ref in stage.context_fact_refs if str(ref).strip()}))
    unknown_context_fact_refs = sorted(ref for ref in context_refs if ref not in owned_by_fact)
    diagnostics: list[KnowledgeQueryDiagnostic] = []
    if duplicate_stage_refs or duplicate_owned_fact_refs or unknown_owned_fact_refs or unknown_context_fact_refs:
        diagnostics.append(
            KnowledgeQueryDiagnostic(
                code="END_TO_END_PRESENTATION_OWNERSHIP_INVALID",
                message="Canonical presentation plan fact ownership is invalid.",
                severity="ERROR",
                metadata={
                    "graphId": graph_id,
                    "duplicateStageRefs": duplicate_stage_refs,
                    "duplicateOwnedFactRefs": duplicate_owned_fact_refs,
                    "unknownOwnedFactRefs": sorted(set(unknown_owned_fact_refs)),
                    "unknownContextFactRefs": unknown_context_fact_refs,
                    "unownedRequiredFactRefs": unknown_context_fact_refs,
                },
            )
        )
    return diagnostics, tuple(sorted(owned_by_fact)), context_refs


def _presentation_ownership_metrics(plans: Sequence[EndToEndPresentationPlan]) -> dict[str, Any]:
    duplicate_stage_count = 0
    duplicate_fact_count = 0
    unknown_owned_count = 0
    unknown_context_count = 0
    for plan in plans:
        stage_refs = [stage.stage_ref for stage in plan.stages]
        duplicate_stage_count += sum(1 for count in Counter(stage_refs).values() if count > 1)
        owned_by_fact: dict[str, list[str]] = defaultdict(list)
        for stage in plan.stages:
            for fact_ref in stage.owned_fact_refs:
                if not _valid_canonical_fact_ref(fact_ref):
                    unknown_owned_count += 1
                owned_by_fact[str(fact_ref)].append(stage.stage_ref)
        duplicate_fact_count += sum(1 for owners in owned_by_fact.values() if len(owners) > 1)
        unknown_context_count += sum(
            1
            for stage in plan.stages
            for fact_ref in stage.context_fact_refs
            if str(fact_ref) not in owned_by_fact
        )
    return {
        "duplicateStageRefs": duplicate_stage_count,
        "duplicateFactRefs": duplicate_fact_count,
        "unknownOwnedFactRefs": unknown_owned_count,
        "unknownContextFactRefs": unknown_context_count,
        "unownedFactRefs": unknown_context_count,
    }


def _formatter_validation_summary(
    plan: EndToEndPresentationPlan,
    segment_steps: Mapping[str, Sequence[Mapping[str, Any]]],
) -> dict[str, Any]:
    expected_stage_refs = [stage.stage_ref for stage in plan.stages]
    expected_stage_ref_set = set(expected_stage_refs)
    actual_stage_refs = [str(ref) for ref in segment_steps]
    missing_stage_refs = [ref for ref in expected_stage_refs if not segment_steps.get(ref)]
    duplicate_stage_refs = sorted(ref for ref, steps in segment_steps.items() if len(steps) > 1)
    unknown_stage_refs = sorted(ref for ref in actual_stage_refs if ref not in expected_stage_ref_set)
    fact_owner_by_ref: dict[str, str] = {}
    for stage in plan.stages:
        for fact_ref in stage.owned_fact_refs:
            fact_owner_by_ref[str(fact_ref)] = stage.stage_ref
    covered_by_fact_ref: dict[str, list[str]] = defaultdict(list)
    unowned_fact_refs: list[str] = []
    for stage_ref, steps in segment_steps.items():
        for step in steps:
            for fact_ref in step.get("coveredFactRefs") or ():
                fact_ref = str(fact_ref)
                owner = fact_owner_by_ref.get(fact_ref)
                if owner != stage_ref:
                    unowned_fact_refs.append(fact_ref)
                    continue
                covered_by_fact_ref[fact_ref].append(stage_ref)
    omitted_owned_fact_refs = sorted(fact_ref for fact_ref in plan.canonical_fact_refs if not covered_by_fact_ref.get(fact_ref))
    duplicate_fact_refs = sorted(fact_ref for fact_ref, owners in covered_by_fact_ref.items() if len(owners) > 1)
    stage_count_contract_matched = (
        not missing_stage_refs
        and not duplicate_stage_refs
        and not unknown_stage_refs
        and not omitted_owned_fact_refs
        and not duplicate_fact_refs
        and not unowned_fact_refs
    )
    validated_step_count = sum(len(steps) for ref, steps in segment_steps.items() if ref in expected_stage_ref_set)
    return {
        "missingStageRefs": len(missing_stage_refs),
        "missingStageRefsList": missing_stage_refs,
        "duplicateStageRefs": len(duplicate_stage_refs),
        "duplicateStageRefsList": duplicate_stage_refs,
        "unknownStageRefs": len(unknown_stage_refs),
        "unknownStageRefsList": unknown_stage_refs,
        "omittedOwnedFactRefs": len(omitted_owned_fact_refs),
        "omittedOwnedFactRefsList": omitted_owned_fact_refs,
        "duplicateFactRefs": len(duplicate_fact_refs),
        "duplicateFactRefsList": duplicate_fact_refs,
        "unownedFactRefs": len(unowned_fact_refs),
        "unownedFactRefsList": sorted(set(unowned_fact_refs)),
        "stageCountContractMatched": stage_count_contract_matched,
        "validatedFormatterStepCount": validated_step_count,
        "publicStepCount": len(expected_stage_refs) if stage_count_contract_matched else 0,
    }


def _rollup_formatter_validation_summaries(summaries: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    keys = (
        "missingStageRefs",
        "duplicateStageRefs",
        "unknownStageRefs",
        "omittedOwnedFactRefs",
        "duplicateFactRefs",
        "unownedFactRefs",
        "validatedFormatterStepCount",
        "publicStepCount",
    )
    return {key: sum(int(summary.get(key) or 0) for summary in summaries) for key in keys}


def _valid_canonical_fact_ref(value: Any) -> bool:
    text = str(value or "").strip()
    if not text or ":" not in text:
        return False
    prefix = text.split(":", 1)[0]
    return prefix in {
        "unit",
        "root",
        "node",
        "edge",
        "topology-boundary",
        "generic-boundary",
        "context",
        "evidence",
        "transition",
        "resolution",
        "required-boundary",
        "provided-boundary",
        "open-boundary",
        "branch",
        "convergence",
        "cycle",
        "shared-unit",
    }


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


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


def _sorted_unique(values: Sequence[str]) -> tuple[str, ...]:
    return tuple(sorted(dict.fromkeys(str(item) for item in values if str(item).strip())))


def _canonical_ref_display(ref: str) -> str:
    text = str(ref or "").strip()
    if not text:
        return ""
    if ":" not in text:
        return text
    return text.rsplit(":", 1)[-1]


_PLACEHOLDER_RE = re.compile(r"\{\{ref:([^{}]+)\}\}")
_OPEN_BOUNDARY_AMBIGUOUS_STAGE_KIND = "OPEN_BOUNDARY_AMBIGUOUS"
_OPEN_BOUNDARY_UNRESOLVED_STAGE_KIND = "OPEN_BOUNDARY_UNRESOLVED"
