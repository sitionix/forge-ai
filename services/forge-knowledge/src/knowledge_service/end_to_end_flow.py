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
from knowledge_service.local_flow_unit_engine import LocalFlowUnit

END_TO_END_TRANSITION_KIND = "PROVEN_BOUNDARY_CONTINUATION"
END_TO_END_VERIFICATION_PROVEN = "PROVEN"
_ORPHAN_PROVEN_LINK_ISSUE_CODES = frozenset(
    {
        "END_TO_END_RESOLUTION_RECORD_MISSING",
        "END_TO_END_RESOLUTION_STATUS_MISMATCH",
        "END_TO_END_RESOLUTION_IDENTITY_MISMATCH",
        "END_TO_END_REQUIRED_UNIT_MAPPING_MISSING",
        "END_TO_END_REQUIRED_UNIT_MAPPING_MISMATCH",
        "END_TO_END_TARGET_UNIT_MAPPING_MISSING",
        "END_TO_END_REFERENCED_UNIT_MISSING",
        "END_TO_END_TARGET_MATERIALIZATION_MISMATCH",
        "END_TO_END_DUPLICATE_CANONICAL_RECORD",
        "END_TO_END_PROVEN_LINK_NOT_ASSEMBLED",
    }
)


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
class EndToEndAssemblyIssue:
    code: str
    message: str
    severity: str = "WARN"
    resolution_id: str | None = None
    required_boundary_identity: BoundaryIdentity | None = None
    provided_boundary_identity: BoundaryIdentity | None = None
    affected_local_unit_ids: tuple[str, ...] = ()
    missing_local_unit_ids: tuple[str, ...] = ()
    metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class EndToEndUnassembledProvenLink:
    link: ProvenBoundaryLink
    reason: str
    diagnostics: tuple[str, ...]
    affected_local_unit_ids: tuple[str, ...] = ()
    missing_local_unit_ids: tuple[str, ...] = ()


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
    input_local_unit_count: int = 0
    input_initial_unit_count: int = 0
    input_discovered_target_unit_count: int = 0
    canonical_unit_count: int = 0
    graph_count: int = 0
    discovered_component_count: int = 0
    returned_component_count: int = 0
    omitted_component_count: int = 0
    multi_source_graph_count: int = 0
    singleton_graph_count: int = 0
    input_proven_link_count: int = 0
    assembled_proven_link_count: int = 0
    unassembled_proven_link_count: int = 0
    proven_resolution_count: int = 0
    assembled_cross_source_transition_count: int = 0
    open_ambiguous_boundary_count: int = 0
    open_unresolved_boundary_count: int = 0
    discovered_open_boundary_count: int = 0
    retained_open_boundary_count: int = 0
    omitted_open_boundary_count: int = 0
    discovered_open_ambiguous_boundary_count: int = 0
    discovered_open_unresolved_boundary_count: int = 0
    retained_open_ambiguous_boundary_count: int = 0
    retained_open_unresolved_boundary_count: int = 0
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
    input_local_unit_ids: tuple[str, ...] = ()
    retained_canonical_unit_ids: tuple[str, ...] = ()
    orphaned_proven_resolutions: tuple[ProvenBoundaryLink, ...] = ()
    unassembled_proven_links: tuple[EndToEndUnassembledProvenLink, ...] = ()
    unassembled_local_unit_ids: tuple[str, ...] = ()
    diagnostics: tuple[EndToEndFlowDiagnostic, ...] = ()
    metrics: EndToEndFlowAssemblyMetrics = EndToEndFlowAssemblyMetrics()
    complete: bool = True
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
        input_ordered_units = tuple(sorted(local_units or (), key=lambda item: item.unit_id))
        input_local_unit_ids = tuple(unit.unit_id for unit in input_ordered_units)
        ordered_units = input_ordered_units
        diagnostics: list[EndToEndFlowDiagnostic] = []
        issues: list[EndToEndAssemblyIssue] = []
        truncated = bool(resolver_truncated)
        omitted_by_unit_limit: tuple[str, ...] = ()

        if len(ordered_units) > self.limits.max_units:
            truncated = True
            retained_ids = {unit.unit_id for unit in ordered_units[: self.limits.max_units]}
            omitted_by_unit_limit = tuple(unit.unit_id for unit in ordered_units if unit.unit_id not in retained_ids)
            omitted_query_entries = tuple(unit_id for unit_id in requested_query_entries if unit_id in set(omitted_by_unit_limit))
            diagnostics.append(
                _limit_diagnostic(
                    "unit",
                    self.limits.max_units,
                    len(ordered_units),
                    metadata={
                        "omittedLocalUnitIds": _bounded_ids(omitted_by_unit_limit),
                        "omittedLocalUnitIdCount": len(omitted_by_unit_limit),
                        "omittedQueryEntryUnitIds": omitted_query_entries,
                    },
                )
            )
            if omitted_by_unit_limit:
                issues.append(
                    EndToEndAssemblyIssue(
                        code="END_TO_END_ASSEMBLY_LIMIT_REACHED",
                        message="End-to-end flow assembly omitted canonical input units because the unit limit was reached.",
                        affected_local_unit_ids=omitted_by_unit_limit,
                        metadata={
                            "limitKind": "unit",
                            "limit": self.limits.max_units,
                            "attempted": len(ordered_units),
                            "omittedLocalUnitIds": _bounded_ids(omitted_by_unit_limit),
                            "omittedLocalUnitIdCount": len(omitted_by_unit_limit),
                            "omittedQueryEntryUnitIds": omitted_query_entries,
                        },
                    )
                )
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
        resolution_by_id, duplicate_resolution_ids = _resolution_records_by_id(boundary_resolution, units_by_id, issues)
        resolution_by_required = {
            boundary_identity(resolution.required_boundary): resolution
            for resolution in sorted(resolution_by_id.values(), key=lambda item: item.resolution_id)
        }
        target_materialization_by_resolution, duplicate_materialization_ids = _target_materializations_by_id(boundary_resolution, units_by_id, issues)
        proven_links, duplicate_proven_link_ids = _deduplicated_proven_links(boundary_resolution, units_by_id, issues)

        transitions: list[EndToEndCrossSourceTransition] = []
        assembled_proven_links: list[ProvenBoundaryLink] = []
        unassembled_proven: list[EndToEndUnassembledProvenLink] = []

        for link in proven_links:
            required_unit_ids = tuple(sorted({str(item or "") for item in link.required_unit_ids if str(item or "")}))
            validation_issue = _validate_proven_link(
                link,
                resolution_by_id=resolution_by_id,
                target_materialization_by_resolution=target_materialization_by_resolution,
                duplicate_resolution_ids=duplicate_resolution_ids,
                duplicate_materialization_ids=duplicate_materialization_ids,
                duplicate_proven_link_ids=duplicate_proven_link_ids,
            )
            if validation_issue is not None:
                affected, missing = _link_unit_scope(
                    link,
                    target_materialization_by_resolution.get(link.resolution_id),
                    units_by_id,
                    required_units_by_boundary,
                )
                issue = _issue_with_scope(validation_issue, affected, missing)
                issues.append(issue)
                unassembled_proven.append(_unassembled_link(link, "CANONICAL_RECORD_INCONSISTENT", issue))
                continue

            if not required_unit_ids:
                materialization = target_materialization_by_resolution.get(link.resolution_id)
                affected, missing = _link_unit_scope(link, materialization, units_by_id, required_units_by_boundary)
                issue = EndToEndAssemblyIssue(
                    code="END_TO_END_REQUIRED_UNIT_MAPPING_MISSING",
                    message="A proven boundary resolution did not retain exact required local-unit provenance.",
                    resolution_id=link.resolution_id,
                    required_boundary_identity=link.required_boundary_identity,
                    provided_boundary_identity=link.provided_boundary_identity,
                    affected_local_unit_ids=affected,
                    missing_local_unit_ids=missing,
                    metadata={"reason": "REQUIRED_UNIT_MAPPING_MISSING"},
                )
                issues.append(issue)
                unassembled_proven.append(_unassembled_link(link, "REQUIRED_UNIT_MAPPING_MISSING", issue))
                continue

            materialization = target_materialization_by_resolution.get(link.resolution_id)
            if (
                materialization is None
                or materialization.materialization_status is BoundaryTargetMaterializationStatus.NOT_MATERIALIZED
                or not materialization.target_local_unit_ids
            ):
                affected, missing = _link_unit_scope(link, materialization, units_by_id, required_units_by_boundary)
                issue = EndToEndAssemblyIssue(
                    code="END_TO_END_TARGET_UNIT_MAPPING_MISSING",
                    message="A proven boundary resolution did not retain exact target local-unit materialization.",
                    resolution_id=link.resolution_id,
                    required_boundary_identity=link.required_boundary_identity,
                    provided_boundary_identity=link.provided_boundary_identity,
                    affected_local_unit_ids=affected,
                    missing_local_unit_ids=missing,
                    metadata={
                        "reason": "TARGET_UNIT_MAPPING_MISSING",
                        "materializationStatus": materialization.materialization_status.value if materialization else None,
                    },
                )
                issues.append(issue)
                unassembled_proven.append(_unassembled_link(link, "TARGET_UNIT_MAPPING_MISSING", issue))
                continue

            target_unit_ids = tuple(sorted({str(item or "") for item in materialization.target_local_unit_ids if str(item or "")}))
            referenced_ids = (*required_unit_ids, *target_unit_ids)
            missing_ids = tuple(sorted(unit_id for unit_id in referenced_ids if unit_id not in units_by_id))
            if missing_ids:
                affected_ids = tuple(sorted(unit_id for unit_id in referenced_ids if unit_id in units_by_id))
                issue = EndToEndAssemblyIssue(
                    code="END_TO_END_REFERENCED_UNIT_MISSING",
                    message="A proven boundary resolution referenced a local unit absent from the canonical unit set.",
                    resolution_id=link.resolution_id,
                    required_boundary_identity=link.required_boundary_identity,
                    provided_boundary_identity=link.provided_boundary_identity,
                    affected_local_unit_ids=affected_ids,
                    missing_local_unit_ids=missing_ids,
                    metadata={
                        "reason": "REFERENCED_UNIT_MISSING",
                        "missingLocalUnitIds": missing_ids,
                    },
                )
                issues.append(issue)
                unassembled_proven.append(_unassembled_link(link, "REFERENCED_UNIT_MISSING", issue))
                continue

            new_transition_count = len(required_unit_ids) * len(target_unit_ids)
            if len(transitions) + new_transition_count > self.limits.max_proven_transitions:
                truncated = True
                affected_ids = tuple(sorted(set(required_unit_ids) | set(target_unit_ids)))
                diagnostics.append(
                    _limit_diagnostic(
                        "proven transition",
                        self.limits.max_proven_transitions,
                        len(transitions) + new_transition_count,
                        metadata={"resolutionId": link.resolution_id},
                    )
                )
                issue = EndToEndAssemblyIssue(
                    code="END_TO_END_PROVEN_LINK_NOT_ASSEMBLED",
                    message="A proven boundary link was not assembled because the transition limit was reached.",
                    resolution_id=link.resolution_id,
                    required_boundary_identity=link.required_boundary_identity,
                    provided_boundary_identity=link.provided_boundary_identity,
                    affected_local_unit_ids=affected_ids,
                    metadata={
                        "reason": "TRANSITION_LIMIT_REACHED",
                        "requiredTransitionCount": new_transition_count,
                        "assembledTransitionCount": len(transitions),
                        "transitionLimit": self.limits.max_proven_transitions,
                    },
                )
                issues.append(issue)
                unassembled_proven.append(_unassembled_link(link, "TRANSITION_LIMIT_REACHED", issue))
                continue

            if materialization.materialization_status is BoundaryTargetMaterializationStatus.PARTIAL:
                truncated = True
                omitted_target_unit_ids = tuple(
                    sorted({str(item or "") for item in materialization.omitted_target_local_unit_ids if str(item or "")})
                )
                issues.append(
                    EndToEndAssemblyIssue(
                        code="END_TO_END_TARGET_MATERIALIZATION_PARTIAL",
                        message="A partial boundary target materialization assembled only explicitly retained target local units.",
                        resolution_id=link.resolution_id,
                        required_boundary_identity=link.required_boundary_identity,
                        provided_boundary_identity=link.provided_boundary_identity,
                        affected_local_unit_ids=tuple(sorted(set(required_unit_ids) | set(target_unit_ids))),
                        missing_local_unit_ids=tuple(unit_id for unit_id in omitted_target_unit_ids if unit_id not in units_by_id),
                        metadata={
                            "materializationStatus": materialization.materialization_status.value,
                            "targetLocalUnitIds": target_unit_ids,
                            "omittedTargetLocalUnitIds": omitted_target_unit_ids,
                        },
                    )
                )

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
            assembled_proven_links.append(link)

        issues.extend(_resolver_issues(boundary_resolution, resolution_by_required, required_units_by_boundary))

        all_discovered_open_boundaries, required_without_resolution = self._open_boundaries(
            ordered_units,
            required_units_by_boundary,
            resolution_by_required,
            tuple(resolution_by_id.values()),
            issues,
        )
        retained_open_boundaries = all_discovered_open_boundaries
        omitted_open_boundaries: tuple[EndToEndOpenBoundary, ...] = ()

        if len(all_discovered_open_boundaries) > self.limits.max_open_boundaries:
            truncated = True
            omitted_open_boundaries = tuple(all_discovered_open_boundaries[self.limits.max_open_boundaries :])
            diagnostics.append(
                _limit_diagnostic(
                    "open boundary",
                    self.limits.max_open_boundaries,
                    len(all_discovered_open_boundaries),
                    metadata={"omittedOpenBoundaryCount": len(omitted_open_boundaries)},
                )
            )
            for item in omitted_open_boundaries:
                issues.append(
                    EndToEndAssemblyIssue(
                        code="END_TO_END_ASSEMBLY_LIMIT_REACHED",
                        message="End-to-end flow assembly omitted an open boundary because the open-boundary limit was reached.",
                        required_boundary_identity=item.required_boundary_identity,
                        affected_local_unit_ids=tuple(sorted(unit_id for unit_id in item.source_unit_ids if unit_id in units_by_id)),
                        metadata={
                            "limitKind": "open boundary",
                            "limit": self.limits.max_open_boundaries,
                            "attempted": len(all_discovered_open_boundaries),
                            "requiredBoundary": _identity_payload(item.required_boundary_identity),
                        },
                    )
                )
            retained_open_boundaries = all_discovered_open_boundaries[: self.limits.max_open_boundaries]

        discovered_components = _components(tuple(unit_ref_by_id), tuple(transitions))
        components = discovered_components
        omitted_component_unit_ids: tuple[str, ...] = ()
        if len(discovered_components) > self.limits.max_connected_components:
            truncated = True
            omitted_components = tuple(discovered_components[self.limits.max_connected_components :])
            omitted_component_unit_ids = tuple(sorted(unit_id for component in omitted_components for unit_id in component))
            diagnostics.append(
                _limit_diagnostic(
                    "connected component",
                    self.limits.max_connected_components,
                    len(discovered_components),
                    metadata={
                        "omittedLocalUnitIds": _bounded_ids(omitted_component_unit_ids),
                        "omittedLocalUnitIdCount": len(omitted_component_unit_ids),
                    },
                )
            )
            if omitted_component_unit_ids:
                issues.append(
                    EndToEndAssemblyIssue(
                        code="END_TO_END_ASSEMBLY_LIMIT_REACHED",
                        message="End-to-end flow assembly omitted connected components because the component limit was reached.",
                        affected_local_unit_ids=omitted_component_unit_ids,
                        metadata={
                            "limitKind": "connected component",
                            "limit": self.limits.max_connected_components,
                            "attempted": len(discovered_components),
                            "omittedLocalUnitIds": _bounded_ids(omitted_component_unit_ids),
                            "omittedLocalUnitIdCount": len(omitted_component_unit_ids),
                        },
                    )
                )
            components = discovered_components[: self.limits.max_connected_components]

        assembled_unit_ids = frozenset(unit_id for component in components for unit_id in component)
        graphs: list[EndToEndFlowGraph] = []
        for component in components:
            graph = self._graph(
                component,
                unit_ref_by_id,
                transitions,
                retained_open_boundaries,
                query_entry_ids,
                issues,
            )
            graphs.append(graph)

        graphs = sorted(graphs, key=lambda item: item.stable_graph_id)
        issue_diagnostics = tuple(sorted((_diagnostic_from_issue(issue) for issue in issues), key=_diagnostic_sort_key))
        assembly_diagnostics = tuple(sorted((*diagnostics, *issue_diagnostics), key=_diagnostic_sort_key))
        missing_required_mapping = sum(1 for issue in issues if issue.code == "END_TO_END_REQUIRED_UNIT_MAPPING_MISSING")
        missing_target_mapping = sum(1 for issue in issues if issue.code == "END_TO_END_TARGET_UNIT_MAPPING_MISSING")
        referenced_unit_missing = sum(len(issue.missing_local_unit_ids) for issue in issues if issue.code == "END_TO_END_REFERENCED_UNIT_MISSING")
        unassembled_local_unit_ids = tuple(sorted(set(input_local_unit_ids) - assembled_unit_ids))
        assembly_complete = not truncated and not issues and all(graph.coverage.complete for graph in graphs)
        metrics = self._metrics(
            requested_query_entries,
            input_local_unit_ids,
            unit_refs,
            graphs,
            discovered_components,
            all_discovered_open_boundaries,
            retained_open_boundaries,
            omitted_open_boundaries,
            boundary_resolution,
            len(unassembled_proven),
            len(assembled_proven_links),
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
                "discoveredComponentCount": metrics.discovered_component_count,
                "returnedComponentCount": metrics.returned_component_count,
                "omittedComponentCount": metrics.omitted_component_count,
                "multiSourceGraphCount": metrics.multi_source_graph_count,
                "singletonGraphCount": metrics.singleton_graph_count,
                "inputProvenLinkCount": metrics.input_proven_link_count,
                "assembledProvenLinkCount": metrics.assembled_proven_link_count,
                "unassembledProvenLinkCount": metrics.unassembled_proven_link_count,
                "provenResolutionCount": metrics.proven_resolution_count,
                "assembledCrossSourceTransitionCount": metrics.assembled_cross_source_transition_count,
                "openAmbiguousBoundaryCount": metrics.open_ambiguous_boundary_count,
                "openUnresolvedBoundaryCount": metrics.open_unresolved_boundary_count,
                "discoveredOpenBoundaryCount": metrics.discovered_open_boundary_count,
                "retainedOpenBoundaryCount": metrics.retained_open_boundary_count,
                "omittedOpenBoundaryCount": metrics.omitted_open_boundary_count,
                "discoveredOpenAmbiguousBoundaryCount": metrics.discovered_open_ambiguous_boundary_count,
                "discoveredOpenUnresolvedBoundaryCount": metrics.discovered_open_unresolved_boundary_count,
                "retainedOpenAmbiguousBoundaryCount": metrics.retained_open_ambiguous_boundary_count,
                "retainedOpenUnresolvedBoundaryCount": metrics.retained_open_unresolved_boundary_count,
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
            input_local_unit_ids=input_local_unit_ids,
            retained_canonical_unit_ids=tuple(sorted(unit_ref_by_id)),
            orphaned_proven_resolutions=tuple(sorted((item.link for item in unassembled_proven), key=_proven_link_sort_key)),
            unassembled_proven_links=tuple(sorted(unassembled_proven, key=_unassembled_link_sort_key)),
            unassembled_local_unit_ids=unassembled_local_unit_ids,
            diagnostics=(*assembly_diagnostics, aggregate),
            metrics=metrics,
            complete=assembly_complete,
            truncated=truncated,
        )

    def _open_boundaries(
        self,
        units: Sequence[LocalFlowUnit],
        required_units_by_boundary: Mapping[BoundaryIdentity, tuple[str, ...]],
        resolution_by_required: Mapping[BoundaryIdentity, BoundaryResolution],
        resolutions: Sequence[BoundaryResolution],
        issues: list[EndToEndAssemblyIssue],
    ) -> tuple[tuple[EndToEndOpenBoundary, ...], int]:
        open_boundaries: list[EndToEndOpenBoundary] = []
        for resolution in sorted(resolutions, key=lambda item: item.resolution_id):
            if resolution.status is BoundaryResolutionStatus.PROVEN:
                continue
            open_boundaries.append(_open_boundary_from_resolution(resolution))
        required_without_resolution = 0
        for identity, unit_ids in sorted(required_units_by_boundary.items()):
            if identity in resolution_by_required:
                continue
            required_without_resolution += 1
            issues.append(
                EndToEndAssemblyIssue(
                    code="END_TO_END_REQUIRED_BOUNDARY_NOT_RESOLVED",
                    message="An accepted required boundary in an active local unit had no boundary resolution record.",
                    required_boundary_identity=identity,
                    affected_local_unit_ids=unit_ids,
                    metadata={"sourceUnitIds": unit_ids},
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
        return tuple(sorted(open_boundaries, key=_open_boundary_sort_key)), required_without_resolution

    def _graph(
        self,
        component: frozenset[str],
        unit_ref_by_id: Mapping[str, EndToEndUnitRef],
        transitions: Sequence[EndToEndCrossSourceTransition],
        open_boundaries: Sequence[EndToEndOpenBoundary],
        query_entry_ids: Sequence[str],
        issues: Sequence[EndToEndAssemblyIssue],
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
                (
                    projected
                    for item in open_boundaries
                    if (projected := _project_open_boundary(item, component)) is not None
                ),
                key=_open_boundary_sort_key,
            )
        )
        graph_issues = tuple(sorted((issue for issue in issues if _issue_affects_component(issue, component)), key=_issue_sort_key))
        incoming = {transition.target_unit_id for transition in graph_transitions}
        topology_entries = tuple(sorted(unit_id for unit_id in component if unit_id not in incoming))
        query_entries = tuple(sorted(unit_id for unit_id in query_entry_ids if unit_id in component))
        cycle_count = 1 if _has_directed_cycle(component, graph_transitions) else 0
        diagnostics = [_diagnostic_from_issue(issue) for issue in graph_issues]
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
        graph_truncated = any(
            issue.code == "END_TO_END_ASSEMBLY_LIMIT_REACHED" or issue.code == "END_TO_END_TARGET_MATERIALIZATION_PARTIAL"
            or issue.code == "END_TO_END_RESOLVER_INCOMPLETE"
            for issue in graph_issues
        )
        orphan_resolution_count = sum(1 for issue in graph_issues if issue.code in _ORPHAN_PROVEN_LINK_ISSUE_CODES)
        missing_unit_mapping_count = sum(
            1
            for issue in graph_issues
            if issue.code
            in {
                "END_TO_END_REQUIRED_UNIT_MAPPING_MISSING",
                "END_TO_END_TARGET_UNIT_MAPPING_MISSING",
                "END_TO_END_REFERENCED_UNIT_MISSING",
            }
        )
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
        input_local_unit_ids: Sequence[str],
        unit_refs: Sequence[EndToEndUnitRef],
        graphs: Sequence[EndToEndFlowGraph],
        discovered_components: Sequence[frozenset[str]],
        discovered_open_boundaries: Sequence[EndToEndOpenBoundary],
        retained_open_boundaries: Sequence[EndToEndOpenBoundary],
        omitted_open_boundaries: Sequence[EndToEndOpenBoundary],
        boundary_resolution: BoundaryResolutionResult | None,
        orphaned_proven_resolution_count: int,
        assembled_proven_link_count: int,
        missing_required_unit_mapping_count: int,
        missing_target_unit_mapping_count: int,
        referenced_unit_missing_count: int,
        required_boundaries_without_resolution_count: int,
        truncated: bool,
    ) -> EndToEndFlowAssemblyMetrics:
        graph_count = len(graphs)
        unique_discovered_open_boundaries = tuple({_open_boundary_unique_key(item): item for item in discovered_open_boundaries}.values())
        unique_retained_open_boundaries = tuple({_open_boundary_unique_key(item): item for item in retained_open_boundaries}.values())
        return EndToEndFlowAssemblyMetrics(
            input_local_unit_count=len(tuple(input_local_unit_ids)),
            input_initial_unit_count=len(tuple(requested_query_entries)),
            input_discovered_target_unit_count=sum(1 for item in unit_refs if item.recursively_discovered),
            canonical_unit_count=len(unit_refs),
            graph_count=graph_count,
            discovered_component_count=len(discovered_components),
            returned_component_count=graph_count,
            omitted_component_count=max(0, len(discovered_components) - graph_count),
            multi_source_graph_count=sum(1 for graph in graphs if graph.coverage.source_count > 1),
            singleton_graph_count=sum(1 for graph in graphs if graph.coverage.unit_count == 1),
            input_proven_link_count=len(boundary_resolution.proven_links if boundary_resolution else ()),
            assembled_proven_link_count=assembled_proven_link_count,
            unassembled_proven_link_count=orphaned_proven_resolution_count,
            proven_resolution_count=len(boundary_resolution.proven_links if boundary_resolution else ()),
            assembled_cross_source_transition_count=sum(len(graph.proven_cross_source_transitions) for graph in graphs),
            open_ambiguous_boundary_count=sum(1 for item in unique_discovered_open_boundaries if item.status is BoundaryResolutionStatus.AMBIGUOUS),
            open_unresolved_boundary_count=sum(1 for item in unique_discovered_open_boundaries if item.status is BoundaryResolutionStatus.UNRESOLVED),
            discovered_open_boundary_count=len(unique_discovered_open_boundaries),
            retained_open_boundary_count=len(unique_retained_open_boundaries),
            omitted_open_boundary_count=len(tuple({_open_boundary_unique_key(item): item for item in omitted_open_boundaries}.values())),
            discovered_open_ambiguous_boundary_count=sum(
                1 for item in unique_discovered_open_boundaries if item.status is BoundaryResolutionStatus.AMBIGUOUS
            ),
            discovered_open_unresolved_boundary_count=sum(
                1 for item in unique_discovered_open_boundaries if item.status is BoundaryResolutionStatus.UNRESOLVED
            ),
            retained_open_ambiguous_boundary_count=sum(1 for item in unique_retained_open_boundaries if item.status is BoundaryResolutionStatus.AMBIGUOUS),
            retained_open_unresolved_boundary_count=sum(1 for item in unique_retained_open_boundaries if item.status is BoundaryResolutionStatus.UNRESOLVED),
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


def _resolution_records_by_id(
    boundary_resolution: BoundaryResolutionResult | None,
    units_by_id: Mapping[str, LocalFlowUnit],
    issues: list[EndToEndAssemblyIssue],
) -> tuple[dict[str, BoundaryResolution], frozenset[str]]:
    grouped: dict[str, list[BoundaryResolution]] = defaultdict(list)
    for resolution in boundary_resolution.resolutions if boundary_resolution else ():
        grouped[resolution.resolution_id].append(resolution)
    records: dict[str, BoundaryResolution] = {}
    duplicates: set[str] = set()
    for resolution_id, items in sorted(grouped.items()):
        if len(items) > 1:
            duplicates.add(resolution_id)
            affected = tuple(
                sorted(
                    {
                        unit_id
                        for item in items
                        for unit_id in item.required_unit_ids
                        if unit_id in units_by_id
                    }
                )
            )
            first = min(items, key=lambda item: _resolution_record_key(item))
            issues.append(
                EndToEndAssemblyIssue(
                    code="END_TO_END_DUPLICATE_CANONICAL_RECORD",
                    message="Duplicate BoundaryResolution records used the same resolution ID.",
                    resolution_id=resolution_id,
                    required_boundary_identity=boundary_identity(first.required_boundary),
                    provided_boundary_identity=(
                        boundary_identity(first.selected_provided_boundary) if first.selected_provided_boundary else None
                    ),
                    affected_local_unit_ids=affected,
                    metadata={"recordType": "BoundaryResolution"},
                )
            )
            continue
        records[resolution_id] = items[0]
    return records, frozenset(duplicates)


def _resolver_issues(
    boundary_resolution: BoundaryResolutionResult | None,
    resolution_by_required: Mapping[BoundaryIdentity, BoundaryResolution],
    required_units_by_boundary: Mapping[BoundaryIdentity, tuple[str, ...]],
) -> tuple[EndToEndAssemblyIssue, ...]:
    if boundary_resolution is None:
        return ()
    truncation = boundary_resolution.truncation
    issues: list[EndToEndAssemblyIssue] = []
    issues.extend(
        _resolver_boundary_issues(
            "CANDIDATE_SET_TRUNCATED",
            truncation.truncated_required_identities,
            resolution_by_required,
            required_units_by_boundary,
        )
    )
    issues.extend(
        _resolver_boundary_issues(
            "CANDIDATE_DESCRIPTOR_SCAN_TRUNCATED",
            truncation.descriptor_scan_truncated_required_identities,
            resolution_by_required,
            required_units_by_boundary,
        )
    )
    issues.extend(
        _resolver_boundary_issues(
            "RESOLVER_LIMIT_REACHED",
            truncation.resolver_limit_required_identities,
            resolution_by_required,
            required_units_by_boundary,
        )
    )
    if truncation.resolver_limit_reached and not truncation.resolver_limit_required_identities:
        issues.append(_resolver_issue("RESOLVER_LIMIT_REACHED", affected_local_unit_ids=()))
    if truncation.recursion_limit_reached and not truncation.resolver_limit_required_identities:
        issues.append(_resolver_issue("RECURSION_LIMIT_REACHED", affected_local_unit_ids=()))
    if truncation.candidate_sets_truncated > 0 and not truncation.truncated_required_identities:
        issues.append(_resolver_issue("CANDIDATE_SET_TRUNCATED", affected_local_unit_ids=()))
    if truncation.candidate_descriptor_scan_truncated and not truncation.descriptor_scan_truncated_required_identities:
        issues.append(_resolver_issue("CANDIDATE_DESCRIPTOR_SCAN_TRUNCATED", affected_local_unit_ids=()))
    if truncation.active_unit_provenance_missing:
        issues.append(_resolver_issue("ACTIVE_UNIT_PROVENANCE_MISSING", affected_local_unit_ids=truncation.active_unit_ids))
    return tuple(sorted(issues, key=_issue_sort_key))


def _resolver_boundary_issues(
    reason: str,
    identities: Sequence[BoundaryIdentity],
    resolution_by_required: Mapping[BoundaryIdentity, BoundaryResolution],
    required_units_by_boundary: Mapping[BoundaryIdentity, tuple[str, ...]],
) -> tuple[EndToEndAssemblyIssue, ...]:
    issues: list[EndToEndAssemblyIssue] = []
    for identity in sorted(set(identities)):
        resolution = resolution_by_required.get(identity)
        unit_ids = resolution.required_unit_ids if resolution is not None else required_units_by_boundary.get(identity, ())
        issues.append(
            _resolver_issue(
                reason,
                affected_required_boundary_identity=identity,
                affected_resolution_id=resolution.resolution_id if resolution is not None else None,
                affected_local_unit_ids=unit_ids,
            )
        )
    return tuple(issues)


def _resolver_issue(
    reason: str,
    *,
    affected_required_boundary_identity: BoundaryIdentity | None = None,
    affected_resolution_id: str | None = None,
    affected_local_unit_ids: Sequence[str] = (),
) -> EndToEndAssemblyIssue:
    return EndToEndAssemblyIssue(
        code="END_TO_END_RESOLVER_INCOMPLETE",
        message="Generic boundary resolver incompleteness affected end-to-end assembly.",
        resolution_id=affected_resolution_id,
        required_boundary_identity=affected_required_boundary_identity,
        affected_local_unit_ids=tuple(sorted({str(item or "") for item in affected_local_unit_ids if str(item or "")})),
        metadata={"reason": reason},
    )


def _target_materializations_by_id(
    boundary_resolution: BoundaryResolutionResult | None,
    units_by_id: Mapping[str, LocalFlowUnit],
    issues: list[EndToEndAssemblyIssue],
) -> tuple[dict[str, BoundaryTargetMaterialization], frozenset[str]]:
    grouped: dict[str, list[BoundaryTargetMaterialization]] = defaultdict(list)
    for item in boundary_resolution.target_materializations if boundary_resolution else ():
        grouped[item.resolution_id].append(item)
    records: dict[str, BoundaryTargetMaterialization] = {}
    duplicates: set[str] = set()
    for resolution_id, items in sorted(grouped.items()):
        if len(items) > 1:
            duplicates.add(resolution_id)
            affected = tuple(
                sorted(
                    {
                        unit_id
                        for item in items
                        for unit_id in (*item.target_local_unit_ids, *item.omitted_target_local_unit_ids)
                        if unit_id in units_by_id
                    }
                )
            )
            first = min(items, key=_target_materialization_sort_key)
            issues.append(
                EndToEndAssemblyIssue(
                    code="END_TO_END_DUPLICATE_CANONICAL_RECORD",
                    message="Duplicate BoundaryTargetMaterialization records used the same resolution ID.",
                    resolution_id=resolution_id,
                    provided_boundary_identity=first.selected_provided_boundary_identity,
                    affected_local_unit_ids=affected,
                    metadata={"recordType": "BoundaryTargetMaterialization"},
                )
            )
            continue
        records[resolution_id] = items[0]
    return records, frozenset(duplicates)


def _deduplicated_proven_links(
    boundary_resolution: BoundaryResolutionResult | None,
    units_by_id: Mapping[str, LocalFlowUnit],
    issues: list[EndToEndAssemblyIssue],
) -> tuple[tuple[ProvenBoundaryLink, ...], frozenset[str]]:
    grouped: dict[str, list[ProvenBoundaryLink]] = defaultdict(list)
    for link in boundary_resolution.proven_links if boundary_resolution else ():
        grouped[link.resolution_id].append(link)
    links: list[ProvenBoundaryLink] = []
    duplicates: set[str] = set()
    for resolution_id, items in sorted(grouped.items()):
        if len(items) > 1:
            duplicates.add(resolution_id)
            affected = tuple(
                sorted(
                    {
                        unit_id
                        for link in items
                        for unit_id in link.required_unit_ids
                        if unit_id in units_by_id
                    }
                )
            )
            first = min(items, key=_proven_link_sort_key)
            issues.append(
                EndToEndAssemblyIssue(
                    code="END_TO_END_DUPLICATE_CANONICAL_RECORD",
                    message="Duplicate ProvenBoundaryLink records used the same resolution ID.",
                    resolution_id=resolution_id,
                    required_boundary_identity=first.required_boundary_identity,
                    provided_boundary_identity=first.provided_boundary_identity,
                    affected_local_unit_ids=affected,
                    metadata={"recordType": "ProvenBoundaryLink"},
                )
            )
        links.extend(items)
    return tuple(sorted(links, key=_proven_link_sort_key)), frozenset(duplicates)


def _validate_proven_link(
    link: ProvenBoundaryLink,
    *,
    resolution_by_id: Mapping[str, BoundaryResolution],
    target_materialization_by_resolution: Mapping[str, BoundaryTargetMaterialization],
    duplicate_resolution_ids: frozenset[str],
    duplicate_materialization_ids: frozenset[str],
    duplicate_proven_link_ids: frozenset[str],
) -> EndToEndAssemblyIssue | None:
    if link.resolution_id in duplicate_resolution_ids or link.resolution_id in duplicate_materialization_ids or link.resolution_id in duplicate_proven_link_ids:
        return EndToEndAssemblyIssue(
            code="END_TO_END_DUPLICATE_CANONICAL_RECORD",
            message="A proven boundary link references a duplicated canonical record.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={"reason": "CANONICAL_RECORD_INCONSISTENT"},
        )
    resolution = resolution_by_id.get(link.resolution_id)
    if resolution is None:
        return EndToEndAssemblyIssue(
            code="END_TO_END_RESOLUTION_RECORD_MISSING",
            message="A proven boundary link had no matching BoundaryResolution record.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={"reason": "CANONICAL_RECORD_INCONSISTENT"},
        )
    if resolution.status is not BoundaryResolutionStatus.PROVEN:
        return EndToEndAssemblyIssue(
            code="END_TO_END_RESOLUTION_STATUS_MISMATCH",
            message="A proven boundary link referenced a BoundaryResolution that was not PROVEN.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={"resolutionStatus": resolution.status.value},
        )
    if boundary_identity(resolution.required_boundary) != link.required_boundary_identity:
        return EndToEndAssemblyIssue(
            code="END_TO_END_RESOLUTION_IDENTITY_MISMATCH",
            message="A proven boundary link disagreed with the BoundaryResolution required boundary identity.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={"mismatch": "requiredBoundary"},
        )
    if resolution.selected_provided_boundary is None or boundary_identity(resolution.selected_provided_boundary) != link.provided_boundary_identity:
        return EndToEndAssemblyIssue(
            code="END_TO_END_RESOLUTION_IDENTITY_MISMATCH",
            message="A proven boundary link disagreed with the BoundaryResolution selected provided boundary identity.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={"mismatch": "providedBoundary"},
        )
    if tuple(sorted(resolution.required_unit_ids)) != tuple(sorted(link.required_unit_ids)):
        return EndToEndAssemblyIssue(
            code="END_TO_END_REQUIRED_UNIT_MAPPING_MISMATCH",
            message="A proven boundary link disagreed with the BoundaryResolution required local-unit IDs.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={
                "resolutionRequiredUnitIds": tuple(sorted(resolution.required_unit_ids)),
                "linkRequiredUnitIds": tuple(sorted(link.required_unit_ids)),
            },
        )
    materialization = target_materialization_by_resolution.get(link.resolution_id)
    if materialization is None:
        return EndToEndAssemblyIssue(
            code="END_TO_END_TARGET_UNIT_MAPPING_MISSING",
            message="A proven boundary resolution did not retain exact target local-unit materialization.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={"reason": "TARGET_UNIT_MAPPING_MISSING"},
        )
    retained_target_ids = tuple(sorted({str(item or "") for item in materialization.target_local_unit_ids if str(item or "")}))
    omitted_target_ids = tuple(sorted({str(item or "") for item in materialization.omitted_target_local_unit_ids if str(item or "")}))
    if set(retained_target_ids) & set(omitted_target_ids):
        return _target_materialization_mismatch(link, "retainedOmittedOverlap")
    if materialization.resolution_id != link.resolution_id:
        return _target_materialization_mismatch(link, "resolutionId")
    if materialization.selected_provided_boundary_identity != link.provided_boundary_identity:
        return _target_materialization_mismatch(link, "selectedProvidedBoundary")
    if materialization.target_owner_identity != link.target_owner:
        return _target_materialization_mismatch(link, "targetOwner")
    if materialization.materialization_status is BoundaryTargetMaterializationStatus.MATERIALIZED and omitted_target_ids:
        return _target_materialization_mismatch(link, "materializedContainsOmittedTargets")
    if materialization.materialization_status is BoundaryTargetMaterializationStatus.NOT_MATERIALIZED and retained_target_ids:
        return _target_materialization_mismatch(link, "notMaterializedContainsRetainedTargets")
    if (
        materialization.materialization_status is BoundaryTargetMaterializationStatus.PARTIAL
        and not retained_target_ids
        and not omitted_target_ids
        and not materialization.diagnostics
    ):
        return _target_materialization_mismatch(link, "partialWithoutTargetsOrDiagnostic")
    if materialization.materialization_status not in {
        BoundaryTargetMaterializationStatus.MATERIALIZED,
        BoundaryTargetMaterializationStatus.PARTIAL,
    }:
        return EndToEndAssemblyIssue(
            code="END_TO_END_TARGET_UNIT_MAPPING_MISSING",
            message="A proven boundary resolution target materialization was not materialized.",
            resolution_id=link.resolution_id,
            required_boundary_identity=link.required_boundary_identity,
            provided_boundary_identity=link.provided_boundary_identity,
            metadata={
                "reason": "TARGET_UNIT_MAPPING_MISSING",
                "materializationStatus": materialization.materialization_status.value,
            },
        )
    return None


def _target_materialization_mismatch(link: ProvenBoundaryLink, mismatch: str) -> EndToEndAssemblyIssue:
    return EndToEndAssemblyIssue(
        code="END_TO_END_TARGET_MATERIALIZATION_MISMATCH",
        message="A proven boundary link disagreed with its BoundaryTargetMaterialization record.",
        resolution_id=link.resolution_id,
        required_boundary_identity=link.required_boundary_identity,
        provided_boundary_identity=link.provided_boundary_identity,
        metadata={"mismatch": mismatch},
    )


def _link_unit_scope(
    link: ProvenBoundaryLink,
    materialization: BoundaryTargetMaterialization | None,
    units_by_id: Mapping[str, LocalFlowUnit],
    required_units_by_boundary: Mapping[BoundaryIdentity, tuple[str, ...]],
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    required_ids = tuple(sorted({*(link.required_unit_ids or ()), *required_units_by_boundary.get(link.required_boundary_identity, ())}))
    target_ids = tuple(sorted(materialization.target_local_unit_ids if materialization else ()))
    omitted_ids = tuple(sorted(materialization.omitted_target_local_unit_ids if materialization else ()))
    referenced = tuple(sorted({str(item or "") for item in (*required_ids, *target_ids, *omitted_ids) if str(item or "")}))
    affected = tuple(unit_id for unit_id in referenced if unit_id in units_by_id)
    missing = tuple(unit_id for unit_id in referenced if unit_id not in units_by_id)
    return affected, missing


def _issue_with_scope(
    issue: EndToEndAssemblyIssue,
    affected_local_unit_ids: Sequence[str],
    missing_local_unit_ids: Sequence[str],
) -> EndToEndAssemblyIssue:
    return EndToEndAssemblyIssue(
        code=issue.code,
        message=issue.message,
        severity=issue.severity,
        resolution_id=issue.resolution_id,
        required_boundary_identity=issue.required_boundary_identity,
        provided_boundary_identity=issue.provided_boundary_identity,
        affected_local_unit_ids=tuple(sorted(set(affected_local_unit_ids))),
        missing_local_unit_ids=tuple(sorted(set(missing_local_unit_ids))),
        metadata=dict(issue.metadata or {}),
    )


def _unassembled_link(link: ProvenBoundaryLink, reason: str, issue: EndToEndAssemblyIssue) -> EndToEndUnassembledProvenLink:
    return EndToEndUnassembledProvenLink(
        link=link,
        reason=reason,
        diagnostics=(issue.code,),
        affected_local_unit_ids=issue.affected_local_unit_ids,
        missing_local_unit_ids=issue.missing_local_unit_ids,
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


def _project_open_boundary(item: EndToEndOpenBoundary, component: frozenset[str]) -> EndToEndOpenBoundary | None:
    source_unit_ids = tuple(sorted(set(item.source_unit_ids) & set(component)))
    if not source_unit_ids:
        return None
    return EndToEndOpenBoundary(
        required_boundary_identity=item.required_boundary_identity,
        source_unit_ids=source_unit_ids,
        status=item.status,
        viable_candidate_owner_identities=item.viable_candidate_owner_identities,
        viable_candidate_boundary_identities=item.viable_candidate_boundary_identities,
        rejection_reason_codes=item.rejection_reason_codes,
        descriptor_fingerprint_hashes=item.descriptor_fingerprint_hashes,
        diagnostics=item.diagnostics,
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


def _identity_sort_text(identity: BoundaryIdentity | None) -> str:
    if identity is None:
        return ""
    return f"{identity.source_id}:{identity.graph_revision}:{identity.boundary_key}:{identity.owner_node_id}"


def _owner_sort_key(owner: BoundaryOwnerIdentity) -> tuple[str, str, str, BoundaryIdentity]:
    return (owner.source_id, owner.graph_revision, owner.owner_node_id, owner.boundary_identity)


def _resolution_record_key(item: BoundaryResolution) -> tuple[str, BoundaryIdentity, str, BoundaryIdentity | None, tuple[str, ...]]:
    return (
        item.resolution_id,
        boundary_identity(item.required_boundary),
        item.status.value,
        boundary_identity(item.selected_provided_boundary) if item.selected_provided_boundary else None,
        tuple(sorted(item.required_unit_ids)),
    )


def _proven_link_sort_key(link: ProvenBoundaryLink) -> tuple[str, BoundaryIdentity, BoundaryIdentity, tuple[str, ...]]:
    return (link.resolution_id, link.required_boundary_identity, link.provided_boundary_identity, tuple(link.required_unit_ids))


def _unassembled_link_sort_key(item: EndToEndUnassembledProvenLink) -> tuple[str, BoundaryIdentity, BoundaryIdentity, tuple[str, ...], str]:
    return (*_proven_link_sort_key(item.link), item.reason)


def _target_materialization_sort_key(
    item: BoundaryTargetMaterialization,
) -> tuple[str, BoundaryIdentity, BoundaryOwnerIdentity, tuple[str, ...], tuple[str, ...]]:
    return (
        item.resolution_id,
        item.selected_provided_boundary_identity,
        item.target_owner_identity,
        item.target_local_unit_ids,
        item.omitted_target_local_unit_ids,
    )


def _open_boundary_sort_key(item: EndToEndOpenBoundary) -> tuple[BoundaryIdentity, str, tuple[str, ...]]:
    return (item.required_boundary_identity, item.status.value, item.source_unit_ids)


def _open_boundary_unique_key(item: EndToEndOpenBoundary) -> tuple[BoundaryIdentity, str]:
    return (item.required_boundary_identity, item.status.value)


def _issue_sort_key(item: EndToEndAssemblyIssue) -> tuple[str, str, str, str, tuple[str, ...], tuple[str, ...]]:
    return (
        item.code,
        item.resolution_id or "",
        _identity_sort_text(item.required_boundary_identity),
        _identity_sort_text(item.provided_boundary_identity),
        item.affected_local_unit_ids,
        item.missing_local_unit_ids,
    )


def _issue_affects_component(item: EndToEndAssemblyIssue, component: frozenset[str]) -> bool:
    return bool(set(item.affected_local_unit_ids) & set(component))


def _diagnostic_sort_key(item: EndToEndFlowDiagnostic) -> tuple[str, str, str]:
    return (item.code, item.source_id or "", json.dumps(dict(item.metadata or {}), sort_keys=True, default=str))


def _diagnostic_from_issue(item: EndToEndAssemblyIssue) -> EndToEndFlowDiagnostic:
    metadata: dict[str, Any] = dict(item.metadata or {})
    if item.resolution_id is not None:
        metadata.setdefault("resolutionId", item.resolution_id)
    if item.required_boundary_identity is not None:
        metadata.setdefault("requiredBoundary", _identity_payload(item.required_boundary_identity))
    if item.provided_boundary_identity is not None:
        metadata.setdefault("providedBoundary", _identity_payload(item.provided_boundary_identity))
    if item.affected_local_unit_ids:
        metadata.setdefault("affectedLocalUnitIds", item.affected_local_unit_ids)
    if item.missing_local_unit_ids:
        metadata.setdefault("missingLocalUnitIds", item.missing_local_unit_ids)
    return _diagnostic(item.code, item.message, severity=item.severity, metadata=metadata)


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


def _limit_diagnostic(kind: str, limit: int, attempted: int, *, metadata: Mapping[str, Any] | None = None) -> EndToEndFlowDiagnostic:
    payload = {"limitKind": kind, "limit": limit, "attempted": attempted}
    payload.update(dict(metadata or {}))
    return _diagnostic(
        "END_TO_END_ASSEMBLY_LIMIT_REACHED",
        "End-to-end flow assembly reached an internal safety limit.",
        severity="WARN",
        metadata=payload,
    )


def _bounded_ids(ids: Sequence[str], *, limit: int = 100) -> tuple[str, ...]:
    return tuple(sorted(ids))[:limit]
