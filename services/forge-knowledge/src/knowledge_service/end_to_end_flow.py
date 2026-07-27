from __future__ import annotations

import hashlib
import json
from collections import defaultdict, deque
from dataclasses import dataclass, field
from typing import Any, Mapping, Sequence

from knowledge_service.boundary_resolution import (
    ACCEPTED_BOUNDARY_STATUSES,
    BOUNDARY_ROLE_PROVIDED,
    BOUNDARY_ROLE_REQUIRED,
    BoundaryCandidateEvaluation,
    BoundaryIdentity,
    BoundaryOwnerIdentity,
    BoundaryResolution,
    BoundaryResolutionResult,
    BoundaryResolutionStatus,
    BoundaryTargetMaterialization,
    BoundaryTargetMaterializationStatus,
    BoundaryTargetSeedIdentity,
    DescriptorFingerprint,
    EvidenceReference,
    ProvenBoundaryLink,
    boundary_identity,
)
from knowledge_service.entrypoint_flow_engine import LocalFlowUnit

END_TO_END_TRANSITION_KIND = "PROVEN_BOUNDARY_CONTINUATION"
END_TO_END_VERIFICATION_PROVEN = "PROVEN"


@dataclass(frozen=True)
class EndToEndUnitRef:
    unit_id: str
    source_id: str
    graph_revision: str
    local_unit: LocalFlowUnit
    query_selected_initial: bool
    recursively_discovered: bool


@dataclass(frozen=True)
class EndToEndBoundaryEndpoint:
    boundary_identity: BoundaryIdentity
    owner_source_id: str
    owner_graph_revision: str
    owner_node_id: str
    role: str
    local_unit_ids: tuple[str, ...]


@dataclass(frozen=True)
class EndToEndCrossSourceTransition:
    stable_transition_id: str
    resolution_id: str
    source_unit_id: str
    target_unit_id: str
    required_endpoint: EndToEndBoundaryEndpoint
    provided_endpoint: EndToEndBoundaryEndpoint
    target_seed_identities: tuple[BoundaryTargetSeedIdentity, ...]
    proving_descriptor_fingerprints: tuple[DescriptorFingerprint, ...]
    evidence_references: tuple[EvidenceReference, ...]
    verification_status: str = END_TO_END_VERIFICATION_PROVEN
    transition_kind: str = END_TO_END_TRANSITION_KIND


@dataclass(frozen=True)
class EndToEndOpenBoundary:
    required_boundary_identity: BoundaryIdentity
    source_unit_ids: tuple[str, ...]
    status: BoundaryResolutionStatus
    viable_candidate_owner_identities: tuple[BoundaryOwnerIdentity, ...]
    viable_candidate_boundary_identities: tuple[BoundaryIdentity, ...]
    rejection_reason_codes: tuple[str, ...]
    descriptor_fingerprint_hashes: tuple[str, ...]
    diagnostics: tuple[str, ...] = ()


@dataclass(frozen=True)
class EndToEndFlowCoverage:
    unit_count: int
    source_count: int
    local_node_count: int
    local_execution_transition_count: int
    proven_cross_source_transition_count: int
    open_ambiguous_boundary_count: int
    open_unresolved_boundary_count: int
    query_entry_unit_count: int
    topology_entry_unit_count: int
    cycle_count: int
    orphan_resolution_count: int
    missing_unit_mapping_count: int
    complete: bool
    truncated: bool


@dataclass(frozen=True)
class EndToEndFlowDiagnostic:
    code: str
    message: str
    severity: str = "INFO"
    source_id: str | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class EndToEndFlowGraph:
    stable_graph_id: str
    unit_refs: tuple[EndToEndUnitRef, ...]
    query_entry_unit_ids: tuple[str, ...]
    topology_entry_unit_ids: tuple[str, ...]
    proven_cross_source_transitions: tuple[EndToEndCrossSourceTransition, ...]
    open_boundaries: tuple[EndToEndOpenBoundary, ...]
    coverage: EndToEndFlowCoverage
    diagnostics: tuple[EndToEndFlowDiagnostic, ...] = ()


@dataclass(frozen=True)
class EndToEndFlowAssemblyLimits:
    max_units: int = 1000
    max_proven_transitions: int = 5000
    max_open_boundaries: int = 5000
    max_connected_components: int = 500


@dataclass(frozen=True)
class EndToEndFlowAssemblyMetrics:
    input_initial_unit_count: int = 0
    input_discovered_target_unit_count: int = 0
    canonical_unit_count: int = 0
    graph_count: int = 0
    multi_source_graph_count: int = 0
    singleton_graph_count: int = 0
    proven_resolution_count: int = 0
    assembled_cross_source_transition_count: int = 0
    open_ambiguous_boundary_count: int = 0
    open_unresolved_boundary_count: int = 0
    required_boundaries_without_resolution_count: int = 0
    orphaned_proven_resolution_count: int = 0
    missing_required_unit_mapping_count: int = 0
    missing_target_unit_mapping_count: int = 0
    referenced_unit_missing_count: int = 0
    query_entry_unit_count: int = 0
    topology_entry_unit_count: int = 0
    cycle_count: int = 0
    complete_graph_count: int = 0
    incomplete_graph_count: int = 0
    assembly_truncated: bool = False


@dataclass(frozen=True)
class EndToEndFlowAssemblyResult:
    graphs: tuple[EndToEndFlowGraph, ...]
    all_canonical_unit_refs: tuple[EndToEndUnitRef, ...]
    orphaned_proven_resolutions: tuple[ProvenBoundaryLink, ...] = ()
    unassembled_local_unit_ids: tuple[str, ...] = ()
    diagnostics: tuple[EndToEndFlowDiagnostic, ...] = ()
    metrics: EndToEndFlowAssemblyMetrics = EndToEndFlowAssemblyMetrics()
    truncated: bool = False


class EndToEndFlowAssembler:
    def __init__(self, limits: EndToEndFlowAssemblyLimits | None = None) -> None:
        self.limits = limits or EndToEndFlowAssemblyLimits()

    def assemble(
        self,
        local_units: Sequence[LocalFlowUnit],
        *,
        query_entry_unit_ids: Sequence[str],
        boundary_resolution: BoundaryResolutionResult | None,
        resolver_truncated: bool = False,
    ) -> EndToEndFlowAssemblyResult:
        requested_query_entries = tuple(sorted({str(item or "") for item in query_entry_unit_ids if str(item or "")}))
        ordered_units = tuple(sorted(local_units or (), key=lambda item: item.unit_id))
        diagnostics: list[EndToEndFlowDiagnostic] = []
        truncated = bool(resolver_truncated)

        if len(ordered_units) > self.limits.max_units:
            truncated = True
            diagnostics.append(_limit_diagnostic("unit", self.limits.max_units, len(ordered_units)))
            ordered_units = ordered_units[: self.limits.max_units]

        units_by_id = {unit.unit_id: unit for unit in ordered_units}
        query_entry_ids = tuple(unit_id for unit_id in requested_query_entries if unit_id in units_by_id)
        unit_refs = tuple(
            EndToEndUnitRef(
                unit_id=unit.unit_id,
                source_id=unit.source_id,
                graph_revision=str(unit.graph_revision or ""),
                local_unit=unit,
                query_selected_initial=unit.unit_id in query_entry_ids,
                recursively_discovered=unit.unit_id not in query_entry_ids,
            )
            for unit in ordered_units
        )
        unit_ref_by_id = {item.unit_id: item for item in unit_refs}
        required_units_by_boundary = _required_units_by_boundary(ordered_units)
        resolution_by_required = {
            boundary_identity(resolution.required_boundary): resolution
            for resolution in sorted((boundary_resolution.resolutions if boundary_resolution else ()), key=lambda item: item.resolution_id)
        }
        target_materialization_by_resolution = {
            item.resolution_id: item
            for item in sorted((boundary_resolution.target_materializations if boundary_resolution else ()), key=_target_materialization_sort_key)
        }

        transitions: list[EndToEndCrossSourceTransition] = []
        orphaned_proven: list[ProvenBoundaryLink] = []
        missing_required_mapping = 0
        missing_target_mapping = 0
        referenced_unit_missing = 0

        for link in sorted((boundary_resolution.proven_links if boundary_resolution else ()), key=_proven_link_sort_key):
            required_unit_ids = tuple(sorted({str(item or "") for item in link.required_unit_ids if str(item or "")}))
            if not required_unit_ids:
                missing_required_mapping += 1
                orphaned_proven.append(link)
                diagnostics.append(
                    _diagnostic(
                        "END_TO_END_REQUIRED_UNIT_MAPPING_MISSING",
                        "A proven boundary resolution did not retain exact required local-unit provenance.",
                        severity="WARN",
                        metadata={"resolutionId": link.resolution_id, "requiredBoundary": _identity_payload(link.required_boundary_identity)},
                    )
                )
                continue

            materialization = target_materialization_by_resolution.get(link.resolution_id)
            if (
                materialization is None
                or materialization.materialization_status is BoundaryTargetMaterializationStatus.NOT_MATERIALIZED
                or not materialization.target_local_unit_ids
            ):
                missing_target_mapping += 1
                orphaned_proven.append(link)
                diagnostics.append(
                    _diagnostic(
                        "END_TO_END_TARGET_UNIT_MAPPING_MISSING",
                        "A proven boundary resolution did not retain exact target local-unit materialization.",
                        severity="WARN",
                        metadata={"resolutionId": link.resolution_id, "providedBoundary": _identity_payload(link.provided_boundary_identity)},
                    )
                )
                continue

            target_unit_ids = tuple(sorted({str(item or "") for item in materialization.target_local_unit_ids if str(item or "")}))
            referenced_ids = (*required_unit_ids, *target_unit_ids)
            missing_ids = tuple(sorted(unit_id for unit_id in referenced_ids if unit_id not in units_by_id))
            if missing_ids:
                referenced_unit_missing += len(missing_ids)
                orphaned_proven.append(link)
                diagnostics.append(
                    _diagnostic(
                        "END_TO_END_REFERENCED_UNIT_MISSING",
                        "A proven boundary resolution referenced a local unit absent from the canonical unit set.",
                        severity="WARN",
                        metadata={"resolutionId": link.resolution_id, "missingLocalUnitIds": missing_ids},
                    )
                )
                continue

            new_transition_count = len(required_unit_ids) * len(target_unit_ids)
            if len(transitions) + new_transition_count > self.limits.max_proven_transitions:
                truncated = True
                orphaned_proven.append(link)
                diagnostics.append(_limit_diagnostic("proven transition", self.limits.max_proven_transitions, len(transitions) + new_transition_count))
                diagnostics.append(
                    _diagnostic(
                        "END_TO_END_PROVEN_LINK_NOT_ASSEMBLED",
                        "A proven boundary link was not assembled because the transition limit was reached.",
                        severity="WARN",
                        metadata={"resolutionId": link.resolution_id},
                    )
                )
                break

            required_endpoint = EndToEndBoundaryEndpoint(
                boundary_identity=link.required_boundary_identity,
                owner_source_id=link.required_boundary_identity.source_id,
                owner_graph_revision=link.required_boundary_identity.graph_revision,
                owner_node_id=link.required_boundary_identity.owner_node_id,
                role=BOUNDARY_ROLE_REQUIRED,
                local_unit_ids=required_unit_ids,
            )
            provided_endpoint = EndToEndBoundaryEndpoint(
                boundary_identity=link.provided_boundary_identity,
                owner_source_id=link.provided_boundary_identity.source_id,
                owner_graph_revision=link.provided_boundary_identity.graph_revision,
                owner_node_id=link.provided_boundary_identity.owner_node_id,
                role=BOUNDARY_ROLE_PROVIDED,
                local_unit_ids=target_unit_ids,
            )
            for source_unit_id in required_unit_ids:
                for target_unit_id in target_unit_ids:
                    transitions.append(
                        EndToEndCrossSourceTransition(
                            stable_transition_id=_transition_id(link, source_unit_id, target_unit_id),
                            resolution_id=link.resolution_id,
                            source_unit_id=source_unit_id,
                            target_unit_id=target_unit_id,
                            required_endpoint=required_endpoint,
                            provided_endpoint=provided_endpoint,
                            target_seed_identities=tuple(sorted(materialization.expanded_target_seed_identities)),
                            proving_descriptor_fingerprints=tuple(sorted(link.proving_descriptor_fingerprints)),
                            evidence_references=tuple(sorted(link.evidence_references)),
                        )
                    )

        open_boundaries, required_without_resolution, open_truncated = self._open_boundaries(
            ordered_units,
            required_units_by_boundary,
            resolution_by_required,
            boundary_resolution,
            diagnostics,
        )
        truncated = truncated or open_truncated

        if len(open_boundaries) > self.limits.max_open_boundaries:
            truncated = True
            diagnostics.append(_limit_diagnostic("open boundary", self.limits.max_open_boundaries, len(open_boundaries)))
            open_boundaries = open_boundaries[: self.limits.max_open_boundaries]

        components = _components(tuple(unit_ref_by_id), tuple(transitions))
        if len(components) > self.limits.max_connected_components:
            truncated = True
            diagnostics.append(_limit_diagnostic("connected component", self.limits.max_connected_components, len(components)))
            components = components[: self.limits.max_connected_components]

        assembled_unit_ids = frozenset(unit_id for component in components for unit_id in component)
        graphs: list[EndToEndFlowGraph] = []
        graph_diagnostics = list(diagnostics)
        for component in components:
            graph = self._graph(
                component,
                unit_ref_by_id,
                transitions,
                open_boundaries,
                query_entry_ids,
                truncated,
                resolver_truncated,
                len(orphaned_proven),
                missing_required_mapping + missing_target_mapping + referenced_unit_missing,
                graph_diagnostics,
            )
            graphs.append(graph)

        graphs = sorted(graphs, key=lambda item: item.stable_graph_id)
        metrics = self._metrics(
            requested_query_entries,
            unit_refs,
            graphs,
            boundary_resolution,
            len(orphaned_proven),
            missing_required_mapping,
            missing_target_mapping,
            referenced_unit_missing,
            required_without_resolution,
            truncated,
        )
        aggregate = _diagnostic(
            "END_TO_END_FLOW_ASSEMBLY_DIAGNOSTICS",
            "End-to-end flow graph assembly diagnostics.",
            metadata={
                "inputInitialUnitCount": metrics.input_initial_unit_count,
                "inputDiscoveredTargetUnitCount": metrics.input_discovered_target_unit_count,
                "canonicalUnitCount": metrics.canonical_unit_count,
                "graphCount": metrics.graph_count,
                "multiSourceGraphCount": metrics.multi_source_graph_count,
                "singletonGraphCount": metrics.singleton_graph_count,
                "provenResolutionCount": metrics.proven_resolution_count,
                "assembledCrossSourceTransitionCount": metrics.assembled_cross_source_transition_count,
                "openAmbiguousBoundaryCount": metrics.open_ambiguous_boundary_count,
                "openUnresolvedBoundaryCount": metrics.open_unresolved_boundary_count,
                "requiredBoundariesWithoutResolutionCount": metrics.required_boundaries_without_resolution_count,
                "orphanedProvenResolutionCount": metrics.orphaned_proven_resolution_count,
                "missingRequiredUnitMappingCount": metrics.missing_required_unit_mapping_count,
                "missingTargetUnitMappingCount": metrics.missing_target_unit_mapping_count,
                "referencedUnitMissingCount": metrics.referenced_unit_missing_count,
                "queryEntryUnitCount": metrics.query_entry_unit_count,
                "topologyEntryUnitCount": metrics.topology_entry_unit_count,
                "cycleCount": metrics.cycle_count,
                "completeGraphCount": metrics.complete_graph_count,
                "incompleteGraphCount": metrics.incomplete_graph_count,
                "assemblyTruncated": metrics.assembly_truncated,
            },
        )
        return EndToEndFlowAssemblyResult(
            graphs=tuple(graphs),
            all_canonical_unit_refs=unit_refs,
            orphaned_proven_resolutions=tuple(sorted(orphaned_proven, key=_proven_link_sort_key)),
            unassembled_local_unit_ids=tuple(sorted(set(unit_ref_by_id) - assembled_unit_ids)),
            diagnostics=(*diagnostics, aggregate),
            metrics=metrics,
            truncated=truncated,
        )

    def _open_boundaries(
        self,
        units: Sequence[LocalFlowUnit],
        required_units_by_boundary: Mapping[BoundaryIdentity, tuple[str, ...]],
        resolution_by_required: Mapping[BoundaryIdentity, BoundaryResolution],
        boundary_resolution: BoundaryResolutionResult | None,
        diagnostics: list[EndToEndFlowDiagnostic],
    ) -> tuple[list[EndToEndOpenBoundary], int, bool]:
        open_boundaries: list[EndToEndOpenBoundary] = []
        truncated = bool(
            boundary_resolution
            and (
                boundary_resolution.truncation.candidate_sets_truncated > 0
                or boundary_resolution.truncation.resolver_limit_reached
                or boundary_resolution.truncation.recursion_limit_reached
                or boundary_resolution.truncation.candidate_descriptor_scan_truncated
                or boundary_resolution.truncation.active_unit_provenance_missing
            )
        )
        for resolution in sorted((boundary_resolution.resolutions if boundary_resolution else ()), key=lambda item: item.resolution_id):
            if resolution.status is BoundaryResolutionStatus.PROVEN:
                continue
            open_boundaries.append(_open_boundary_from_resolution(resolution))
        required_without_resolution = 0
        for identity, unit_ids in sorted(required_units_by_boundary.items()):
            if identity in resolution_by_required:
                continue
            required_without_resolution += 1
            diagnostics.append(
                _diagnostic(
                    "END_TO_END_REQUIRED_BOUNDARY_NOT_RESOLVED",
                    "An accepted required boundary in an active local unit had no boundary resolution record.",
                    severity="WARN",
                    metadata={"requiredBoundary": _identity_payload(identity), "sourceUnitIds": unit_ids},
                )
            )
            open_boundaries.append(
                EndToEndOpenBoundary(
                    required_boundary_identity=identity,
                    source_unit_ids=unit_ids,
                    status=BoundaryResolutionStatus.UNRESOLVED,
                    viable_candidate_owner_identities=(),
                    viable_candidate_boundary_identities=(),
                    rejection_reason_codes=("END_TO_END_REQUIRED_BOUNDARY_NOT_RESOLVED",),
                    descriptor_fingerprint_hashes=(),
                    diagnostics=("END_TO_END_REQUIRED_BOUNDARY_NOT_RESOLVED",),
                )
            )
        return sorted(open_boundaries, key=_open_boundary_sort_key), required_without_resolution, truncated

    def _graph(
        self,
        component: frozenset[str],
        unit_ref_by_id: Mapping[str, EndToEndUnitRef],
        transitions: Sequence[EndToEndCrossSourceTransition],
        open_boundaries: Sequence[EndToEndOpenBoundary],
        query_entry_ids: Sequence[str],
        assembly_truncated: bool,
        resolver_truncated: bool,
        orphan_resolution_count: int,
        missing_unit_mapping_count: int,
        base_diagnostics: Sequence[EndToEndFlowDiagnostic],
    ) -> EndToEndFlowGraph:
        graph_units = tuple(unit_ref_by_id[unit_id] for unit_id in sorted(component))
        graph_transitions = tuple(
            sorted(
                (transition for transition in transitions if transition.source_unit_id in component and transition.target_unit_id in component),
                key=lambda item: item.stable_transition_id,
            )
        )
        graph_open = tuple(
            sorted(
                (item for item in open_boundaries if set(item.source_unit_ids) & set(component)),
                key=_open_boundary_sort_key,
            )
        )
        incoming = {transition.target_unit_id for transition in graph_transitions}
        topology_entries = tuple(sorted(unit_id for unit_id in component if unit_id not in incoming))
        query_entries = tuple(sorted(unit_id for unit_id in query_entry_ids if unit_id in component))
        cycle_count = 1 if _has_directed_cycle(component, graph_transitions) else 0
        diagnostics = list(base_diagnostics)
        if cycle_count:
            diagnostics.append(
                _diagnostic(
                    "END_TO_END_GRAPH_CYCLE_DETECTED",
                    "An end-to-end flow graph contains a retained cross-source cycle.",
                    severity="INFO",
                    metadata={"unitCount": len(component), "transitionCount": len(graph_transitions)},
                )
            )
        local_node_count = sum(len(unit.local_unit.execution_nodes) for unit in graph_units)
        local_transition_count = sum(len(unit.local_unit.execution_transitions) for unit in graph_units)
        source_count = len({unit.source_id for unit in graph_units})
        graph_truncated = assembly_truncated or resolver_truncated
        complete = (
            not graph_truncated
            and all(unit.local_unit.complete for unit in graph_units)
            and not graph_open
            and orphan_resolution_count == 0
            and missing_unit_mapping_count == 0
        )
        coverage = EndToEndFlowCoverage(
            unit_count=len(graph_units),
            source_count=source_count,
            local_node_count=local_node_count,
            local_execution_transition_count=local_transition_count,
            proven_cross_source_transition_count=len(graph_transitions),
            open_ambiguous_boundary_count=sum(1 for item in graph_open if item.status is BoundaryResolutionStatus.AMBIGUOUS),
            open_unresolved_boundary_count=sum(1 for item in graph_open if item.status is BoundaryResolutionStatus.UNRESOLVED),
            query_entry_unit_count=len(query_entries),
            topology_entry_unit_count=len(topology_entries),
            cycle_count=cycle_count,
            orphan_resolution_count=orphan_resolution_count,
            missing_unit_mapping_count=missing_unit_mapping_count,
            complete=complete,
            truncated=graph_truncated,
        )
        return EndToEndFlowGraph(
            stable_graph_id=_graph_id(component, graph_transitions, graph_open),
            unit_refs=graph_units,
            query_entry_unit_ids=query_entries,
            topology_entry_unit_ids=topology_entries,
            proven_cross_source_transitions=graph_transitions,
            open_boundaries=graph_open,
            coverage=coverage,
            diagnostics=tuple(sorted(diagnostics, key=_diagnostic_sort_key)),
        )

    def _metrics(
        self,
        requested_query_entries: Sequence[str],
        unit_refs: Sequence[EndToEndUnitRef],
        graphs: Sequence[EndToEndFlowGraph],
        boundary_resolution: BoundaryResolutionResult | None,
        orphaned_proven_resolution_count: int,
        missing_required_unit_mapping_count: int,
        missing_target_unit_mapping_count: int,
        referenced_unit_missing_count: int,
        required_boundaries_without_resolution_count: int,
        truncated: bool,
    ) -> EndToEndFlowAssemblyMetrics:
        graph_count = len(graphs)
        return EndToEndFlowAssemblyMetrics(
            input_initial_unit_count=len(tuple(requested_query_entries)),
            input_discovered_target_unit_count=sum(1 for item in unit_refs if item.recursively_discovered),
            canonical_unit_count=len(unit_refs),
            graph_count=graph_count,
            multi_source_graph_count=sum(1 for graph in graphs if graph.coverage.source_count > 1),
            singleton_graph_count=sum(1 for graph in graphs if graph.coverage.unit_count == 1),
            proven_resolution_count=len(boundary_resolution.proven_links if boundary_resolution else ()),
            assembled_cross_source_transition_count=sum(len(graph.proven_cross_source_transitions) for graph in graphs),
            open_ambiguous_boundary_count=sum(graph.coverage.open_ambiguous_boundary_count for graph in graphs),
            open_unresolved_boundary_count=sum(graph.coverage.open_unresolved_boundary_count for graph in graphs),
            required_boundaries_without_resolution_count=required_boundaries_without_resolution_count,
            orphaned_proven_resolution_count=orphaned_proven_resolution_count,
            missing_required_unit_mapping_count=missing_required_unit_mapping_count,
            missing_target_unit_mapping_count=missing_target_unit_mapping_count,
            referenced_unit_missing_count=referenced_unit_missing_count,
            query_entry_unit_count=sum(len(graph.query_entry_unit_ids) for graph in graphs),
            topology_entry_unit_count=sum(len(graph.topology_entry_unit_ids) for graph in graphs),
            cycle_count=sum(graph.coverage.cycle_count for graph in graphs),
            complete_graph_count=sum(1 for graph in graphs if graph.coverage.complete),
            incomplete_graph_count=sum(1 for graph in graphs if not graph.coverage.complete),
            assembly_truncated=truncated,
        )


def _required_units_by_boundary(units: Sequence[LocalFlowUnit]) -> dict[BoundaryIdentity, tuple[str, ...]]:
    grouped: dict[BoundaryIdentity, set[str]] = defaultdict(set)
    for unit in sorted(units, key=lambda item: item.unit_id):
        for boundary in sorted(unit.generic_boundaries, key=lambda item: boundary_identity(item)):
            if str(boundary.role or "").strip().upper() != BOUNDARY_ROLE_REQUIRED:
                continue
            if str(boundary.status or "").strip().upper() not in ACCEPTED_BOUNDARY_STATUSES:
                continue
            grouped[boundary_identity(boundary)].add(unit.unit_id)
    return {identity: tuple(sorted(unit_ids)) for identity, unit_ids in sorted(grouped.items())}


def _open_boundary_from_resolution(resolution: BoundaryResolution) -> EndToEndOpenBoundary:
    evaluations = tuple(sorted(resolution.evaluated_candidates, key=lambda item: item.provided_boundary_identity))
    viable = _viable_candidate_evaluations(resolution.status, evaluations)
    reason_codes = {reason for evaluation in evaluations for reason in evaluation.rejection_reasons}
    reason_codes.update(item.code for item in resolution.diagnostics)
    fingerprint_hashes = {match.fingerprint.fingerprint_hash for evaluation in evaluations for match in evaluation.exact_descriptor_matches}
    fingerprint_hashes.update(fingerprint.fingerprint_hash for fingerprint in resolution.proving_descriptor_fingerprints)
    return EndToEndOpenBoundary(
        required_boundary_identity=boundary_identity(resolution.required_boundary),
        source_unit_ids=tuple(sorted(resolution.required_unit_ids)),
        status=resolution.status,
        viable_candidate_owner_identities=tuple(sorted((item.provided_owner_identity for item in viable), key=_owner_sort_key)),
        viable_candidate_boundary_identities=tuple(sorted(item.provided_boundary_identity for item in viable)),
        rejection_reason_codes=tuple(sorted(reason_codes)),
        descriptor_fingerprint_hashes=tuple(sorted(fingerprint_hashes)),
        diagnostics=tuple(sorted(item.code for item in resolution.diagnostics)),
    )


def _viable_candidate_evaluations(
    status: BoundaryResolutionStatus,
    evaluations: Sequence[BoundaryCandidateEvaluation],
) -> tuple[BoundaryCandidateEvaluation, ...]:
    if status is BoundaryResolutionStatus.AMBIGUOUS:
        proof_eligible = tuple(item for item in evaluations if item.proof_eligibility and not item.rejection_reasons)
        if proof_eligible:
            return proof_eligible
    return tuple(item for item in evaluations if item.exact_descriptor_matches)


def _components(
    unit_ids: Sequence[str],
    transitions: Sequence[EndToEndCrossSourceTransition],
) -> list[frozenset[str]]:
    adjacency: dict[str, set[str]] = {unit_id: set() for unit_id in unit_ids}
    for transition in transitions:
        adjacency.setdefault(transition.source_unit_id, set()).add(transition.target_unit_id)
        adjacency.setdefault(transition.target_unit_id, set()).add(transition.source_unit_id)
    seen: set[str] = set()
    components: list[frozenset[str]] = []
    for unit_id in sorted(adjacency):
        if unit_id in seen:
            continue
        component: set[str] = set()
        pending = deque([unit_id])
        while pending:
            current = pending.popleft()
            if current in component:
                continue
            component.add(current)
            pending.extend(sorted(adjacency.get(current, set()) - component))
        seen.update(component)
        components.append(frozenset(component))
    return sorted(components, key=lambda item: tuple(sorted(item)))


def _has_directed_cycle(
    component: frozenset[str],
    transitions: Sequence[EndToEndCrossSourceTransition],
) -> bool:
    outgoing: dict[str, set[str]] = {unit_id: set() for unit_id in component}
    indegree: dict[str, int] = {unit_id: 0 for unit_id in component}
    for transition in transitions:
        if transition.source_unit_id not in component or transition.target_unit_id not in component:
            continue
        if transition.target_unit_id not in outgoing[transition.source_unit_id]:
            outgoing[transition.source_unit_id].add(transition.target_unit_id)
            indegree[transition.target_unit_id] += 1
    queue = deque(sorted(unit_id for unit_id, count in indegree.items() if count == 0))
    visited = 0
    while queue:
        current = queue.popleft()
        visited += 1
        for target in sorted(outgoing.get(current, ())):
            indegree[target] -= 1
            if indegree[target] == 0:
                queue.append(target)
    return visited < len(component)


def _transition_id(link: ProvenBoundaryLink, source_unit_id: str, target_unit_id: str) -> str:
    payload = {
        "resolutionId": link.resolution_id,
        "required": _identity_payload(link.required_boundary_identity),
        "provided": _identity_payload(link.provided_boundary_identity),
        "sourceUnitId": source_unit_id,
        "targetUnitId": target_unit_id,
    }
    return "e2et_" + _hash_payload(payload)


def _graph_id(
    unit_ids: frozenset[str],
    transitions: Sequence[EndToEndCrossSourceTransition],
    open_boundaries: Sequence[EndToEndOpenBoundary],
) -> str:
    payload = {
        "unitIds": sorted(unit_ids),
        "transitionIds": sorted(transition.stable_transition_id for transition in transitions),
        "openBoundaries": [
            {
                "required": _identity_payload(item.required_boundary_identity),
                "status": item.status.value,
                "sourceUnitIds": list(item.source_unit_ids),
            }
            for item in sorted(open_boundaries, key=_open_boundary_sort_key)
        ],
    }
    return "e2e_" + _hash_payload(payload)


def _hash_payload(payload: Mapping[str, Any]) -> str:
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def _identity_payload(identity: BoundaryIdentity) -> dict[str, str]:
    return {
        "sourceId": identity.source_id,
        "graphRevision": identity.graph_revision,
        "boundaryKey": identity.boundary_key,
        "ownerNodeId": identity.owner_node_id,
    }


def _owner_sort_key(owner: BoundaryOwnerIdentity) -> tuple[str, str, str, BoundaryIdentity]:
    return (owner.source_id, owner.graph_revision, owner.owner_node_id, owner.boundary_identity)


def _proven_link_sort_key(link: ProvenBoundaryLink) -> tuple[str, BoundaryIdentity, BoundaryIdentity, tuple[str, ...]]:
    return (link.resolution_id, link.required_boundary_identity, link.provided_boundary_identity, tuple(link.required_unit_ids))


def _target_materialization_sort_key(item: BoundaryTargetMaterialization) -> tuple[str, BoundaryIdentity, BoundaryOwnerIdentity, tuple[str, ...]]:
    return (item.resolution_id, item.selected_provided_boundary_identity, item.target_owner_identity, item.target_local_unit_ids)


def _open_boundary_sort_key(item: EndToEndOpenBoundary) -> tuple[BoundaryIdentity, str, tuple[str, ...]]:
    return (item.required_boundary_identity, item.status.value, item.source_unit_ids)


def _diagnostic_sort_key(item: EndToEndFlowDiagnostic) -> tuple[str, str, str]:
    return (item.code, item.source_id or "", json.dumps(dict(item.metadata or {}), sort_keys=True, default=str))


def _diagnostic(
    code: str,
    message: str,
    *,
    severity: str = "INFO",
    source_id: str | None = None,
    metadata: Mapping[str, Any] | None = None,
) -> EndToEndFlowDiagnostic:
    return EndToEndFlowDiagnostic(
        code=code,
        message=message,
        severity=severity,
        source_id=source_id,
        metadata=dict(metadata or {}),
    )


def _limit_diagnostic(kind: str, limit: int, attempted: int) -> EndToEndFlowDiagnostic:
    return _diagnostic(
        "END_TO_END_ASSEMBLY_LIMIT_REACHED",
        "End-to-end flow assembly reached an internal safety limit.",
        severity="WARN",
        metadata={"limitKind": kind, "limit": limit, "attempted": attempted},
    )
