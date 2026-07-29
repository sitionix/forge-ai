from __future__ import annotations

import hashlib
import itertools
import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping, Sequence

from knowledge_service.boundary_contract import LocalBoundaryDescriptor, LocalBoundaryFact
from knowledge_service.flow_graph_contract import FlowGraphEvidence, evidence_key

ACCEPTED_BOUNDARY_STATUSES = frozenset({"TRUSTED", "DERIVED"})
BOUNDARY_ROLE_REQUIRED = "REQUIRED"
BOUNDARY_ROLE_PROVIDED = "PROVIDED"


class BoundaryResolutionStatus(str, Enum):
    PROVEN = "PROVEN"
    AMBIGUOUS = "AMBIGUOUS"
    UNRESOLVED = "UNRESOLVED"


class BoundaryTargetMaterializationStatus(str, Enum):
    MATERIALIZED = "MATERIALIZED"
    PARTIAL = "PARTIAL"
    NOT_MATERIALIZED = "NOT_MATERIALIZED"


@dataclass(frozen=True, order=True)
class BoundaryIdentity:
    source_id: str
    graph_revision: str
    boundary_key: str
    owner_node_id: str


@dataclass(frozen=True, order=True)
class BoundaryOwnerIdentity:
    source_id: str
    graph_revision: str
    owner_node_id: str
    boundary_identity: BoundaryIdentity


@dataclass(frozen=True, order=True)
class EvidenceReference:
    source_id: str
    graph_revision: str
    evidence_id: str
    owner_kind: str | None = None
    owner_node_id: str | None = None
    owner_edge_id: str | None = None


@dataclass(frozen=True, order=True)
class DescriptorFingerprint:
    path: str
    value_type: str
    canonical_value: str
    fingerprint_hash: str


@dataclass(frozen=True)
class BoundaryDescriptorMatch:
    fingerprint: DescriptorFingerprint
    required_descriptor_ids: tuple[str, ...]
    provided_descriptor_ids: tuple[str, ...]
    required_evidence_refs: tuple[EvidenceReference, ...]
    provided_evidence_refs: tuple[EvidenceReference, ...]


@dataclass(frozen=True)
class BoundaryDescriptorConflict:
    path: str
    value_type: str
    required_descriptor_ids: tuple[str, ...]
    provided_descriptor_ids: tuple[str, ...]
    required_fingerprint_hashes: tuple[str, ...]
    provided_fingerprint_hashes: tuple[str, ...]


@dataclass(frozen=True)
class BoundaryMissingDescriptor:
    side: str
    path: str
    value_type: str
    descriptor_ids: tuple[str, ...]


@dataclass(frozen=True)
class BoundaryCandidateEvaluation:
    required_boundary_identity: BoundaryIdentity
    provided_boundary_identity: BoundaryIdentity
    required_unit_identity: str
    provided_owner_identity: BoundaryOwnerIdentity
    exact_descriptor_matches: tuple[BoundaryDescriptorMatch, ...]
    conflicting_descriptors: tuple[BoundaryDescriptorConflict, ...]
    missing_descriptors: tuple[BoundaryMissingDescriptor, ...]
    evidence_sufficiency: str
    provenance_summary: Mapping[str, Any]
    confidence_summary: Mapping[str, Any]
    candidate_score: float
    proof_eligibility: tuple[str, ...]
    rejection_reasons: tuple[str, ...]
    proving_descriptor_fingerprints: tuple[DescriptorFingerprint, ...] = ()


@dataclass(frozen=True)
class ProvenBoundaryLink:
    resolution_id: str
    required_boundary_identity: BoundaryIdentity
    provided_boundary_identity: BoundaryIdentity
    target_owner: BoundaryOwnerIdentity
    proving_descriptor_fingerprints: tuple[DescriptorFingerprint, ...]
    evidence_references: tuple[EvidenceReference, ...]
    required_unit_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class BoundaryResolutionDiagnostic:
    code: str
    message: str
    severity: str = "INFO"
    source_id: str | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, order=True)
class BoundaryTargetSeedIdentity:
    source_id: str
    graph_revision: str
    node_id: str
    stable_key: str


@dataclass(frozen=True, order=True)
class BoundaryTargetSeedRelation:
    seed_identity: BoundaryTargetSeedIdentity
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class BoundaryTargetMaterialization:
    resolution_id: str
    selected_provided_boundary_identity: BoundaryIdentity
    target_owner_identity: BoundaryOwnerIdentity
    target_local_unit_ids: tuple[str, ...]
    expanded_target_seed_identities: tuple[BoundaryTargetSeedIdentity, ...]
    owner_to_seed_reasons: tuple[BoundaryTargetSeedRelation, ...]
    materialization_status: BoundaryTargetMaterializationStatus
    diagnostics: tuple[BoundaryResolutionDiagnostic, ...] = ()
    omitted_target_local_unit_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class BoundaryResolution:
    resolution_id: str
    required_boundary: LocalBoundaryFact
    status: BoundaryResolutionStatus
    selected_provided_boundary: LocalBoundaryFact | None
    evaluated_candidates: tuple[BoundaryCandidateEvaluation, ...]
    proving_descriptor_fingerprints: tuple[DescriptorFingerprint, ...]
    evidence_references: tuple[EvidenceReference, ...]
    diagnostics: tuple[BoundaryResolutionDiagnostic, ...]
    required_unit_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class BoundaryResolutionTruncationState:
    candidate_sets_truncated: int = 0
    resolver_limit_reached: bool = False
    recursion_limit_reached: bool = False
    candidate_descriptor_scan_truncated: bool = False
    active_unit_provenance_missing: bool = False
    truncated_required_identities: tuple[BoundaryIdentity, ...] = ()
    descriptor_scan_truncated_required_identities: tuple[BoundaryIdentity, ...] = ()
    resolver_limit_required_identities: tuple[BoundaryIdentity, ...] = ()
    active_unit_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class BoundaryResolverMetrics:
    required_boundary_count: int = 0
    eligible_provided_boundary_count: int = 0
    provided_candidates_by_source: Mapping[str, int] = field(default_factory=dict)
    descriptor_fingerprints_queried: int = 0
    candidate_pairs_evaluated: int = 0
    proven_count: int = 0
    ambiguous_count: int = 0
    unresolved_count: int = 0
    candidate_sets_truncated: int = 0
    conflict_count: int = 0
    evidence_insufficient_count: int = 0
    target_owners_discovered: int = 0
    target_units_considered: int = 0
    target_units_materialized: int = 0
    target_units_omitted: int = 0
    partial_target_materialization_count: int = 0
    resolution_rounds: int = 0
    resolution_cycles_detected: int = 0
    resolver_sql_statements: int = 0
    candidate_descriptor_rows_scanned: int = 0
    candidate_descriptor_rows_matched_exactly: int = 0
    candidate_descriptor_row_budget: int = 0
    candidate_descriptor_scan_truncated: bool = False
    candidate_sources_inspected: int = 0
    candidate_sources_truncated: int = 0
    candidate_pages_loaded: int = 0
    required_candidate_sets_incomplete: int = 0


@dataclass(frozen=True)
class BoundaryCandidateLoadResult:
    candidates_by_required_identity: Mapping[BoundaryIdentity, tuple[LocalBoundaryFact, ...]]
    provided_boundaries_by_fingerprint: Mapping[DescriptorFingerprint, frozenset[BoundaryIdentity]]
    eligible_provided_boundary_count: int
    provided_candidates_by_source: Mapping[str, int]
    descriptor_fingerprints_queried: int
    truncated_required_identities: frozenset[BoundaryIdentity] = frozenset()
    diagnostics: tuple[BoundaryResolutionDiagnostic, ...] = ()
    sql_statements: int = 0
    candidate_descriptor_rows_scanned: int = 0
    candidate_descriptor_rows_matched_exactly: int = 0
    candidate_descriptor_row_budget: int = 0
    candidate_descriptor_scan_truncated: bool = False
    candidate_sources_inspected: int = 0
    candidate_sources_truncated: int = 0
    candidate_pages_loaded: int = 0
    required_candidate_sets_incomplete: int = 0


@dataclass(frozen=True)
class BoundaryCandidateLoadLimits:
    max_descriptor_path_type_pairs: int = 5000
    max_candidate_boundaries_total: int = 20000
    max_candidates_per_required: int = 1000
    max_candidate_descriptor_rows_scanned: int = 100000
    max_candidate_descriptor_page_size: int = 500
    max_source_chunk_size: int = 200
    max_path_type_chunk_size: int = 200
    max_boundary_id_chunk_size: int = 500


@dataclass(frozen=True)
class BoundaryResolutionResult:
    resolutions: tuple[BoundaryResolution, ...]
    proven_links: tuple[ProvenBoundaryLink, ...]
    ambiguous_links: tuple[BoundaryResolution, ...]
    unresolved_boundaries: tuple[BoundaryIdentity, ...]
    discovered_provided_owners: tuple[BoundaryOwnerIdentity, ...]
    discovered_local_units: tuple[Any, ...] = ()
    target_materializations: tuple[BoundaryTargetMaterialization, ...] = ()
    diagnostics: tuple[BoundaryResolutionDiagnostic, ...] = ()
    truncation: BoundaryResolutionTruncationState = BoundaryResolutionTruncationState()
    metrics: BoundaryResolverMetrics = BoundaryResolverMetrics()


def boundary_identity(boundary: LocalBoundaryFact) -> BoundaryIdentity:
    return BoundaryIdentity(
        source_id=str(boundary.source_id or ""),
        graph_revision=str(boundary.graph_revision or boundary.graph_id or ""),
        boundary_key=str(boundary.stable_key or boundary.boundary_id or ""),
        owner_node_id=str(boundary.owner_node_id or ""),
    )


def boundary_owner_identity(boundary: LocalBoundaryFact) -> BoundaryOwnerIdentity:
    identity = boundary_identity(boundary)
    return BoundaryOwnerIdentity(
        source_id=identity.source_id,
        graph_revision=identity.graph_revision,
        owner_node_id=identity.owner_node_id,
        boundary_identity=identity,
    )


def descriptor_fingerprint(descriptor: LocalBoundaryDescriptor) -> DescriptorFingerprint:
    path = str(descriptor.path or "").strip()
    value_type = str(descriptor.value_type or "").strip()
    canonical_value = canonical_descriptor_value(descriptor.value)
    payload = {
        "path": path,
        "valueType": value_type,
        "value": canonical_value,
    }
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
    return DescriptorFingerprint(path=path, value_type=value_type, canonical_value=canonical_value, fingerprint_hash=digest)


def descriptor_fingerprint_from_row(path: object, value_type: object, raw_json: object) -> DescriptorFingerprint | None:
    try:
        value = json.loads(str(raw_json or ""))
    except (TypeError, json.JSONDecodeError):
        return None
    return descriptor_fingerprint(
        LocalBoundaryDescriptor(
            descriptor_id="",
            path=str(path or ""),
            value_type=str(value_type or ""),
            value=value,
            origin="",
        )
    )


def canonical_descriptor_value(value: Any) -> str:
    return json.dumps(_canonical_json_value(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def stable_resolution_id(required: LocalBoundaryFact) -> str:
    raw = json.dumps(_identity_payload(boundary_identity(required)), sort_keys=True, separators=(",", ":"))
    return "br_" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def evidence_references(items: Sequence[FlowGraphEvidence]) -> tuple[EvidenceReference, ...]:
    refs: dict[tuple[str, str, str, str, str, str], EvidenceReference] = {}
    for item in items:
        if not item.evidence_id:
            continue
        key = evidence_key(item)
        ref = EvidenceReference(
            source_id=str(item.source_id or ""),
            graph_revision=str(item.graph_revision or item.graph_id or ""),
            evidence_id=str(item.evidence_id or ""),
            owner_kind=key.owner_kind,
            owner_node_id=key.owner_node_id,
            owner_edge_id=key.owner_edge_id,
        )
        refs.setdefault(_evidence_ref_sort_key(ref), ref)
    return tuple(refs[key] for key in sorted(refs))


class GenericBoundaryResolver:
    def resolve(
        self,
        local_units: Sequence[Any],
        candidate_load: BoundaryCandidateLoadResult,
    ) -> BoundaryResolutionResult:
        required_by_identity: dict[BoundaryIdentity, tuple[LocalBoundaryFact, set[str]]] = {}
        for unit in sorted(local_units, key=lambda item: str(getattr(item, "unit_id", ""))):
            unit_id = str(getattr(unit, "unit_id", "") or "")
            for boundary in sorted(getattr(unit, "generic_boundaries", ()) or (), key=_boundary_sort_key):
                if str(boundary.role or "").strip().upper() != BOUNDARY_ROLE_REQUIRED:
                    continue
                identity = boundary_identity(boundary)
                _boundary, unit_ids = required_by_identity.setdefault(identity, (boundary, set()))
                if unit_id:
                    unit_ids.add(unit_id)

        resolutions: list[BoundaryResolution] = []
        diagnostics: list[BoundaryResolutionDiagnostic] = list(candidate_load.diagnostics)
        conflict_count = 0
        evidence_insufficient_count = 0
        candidate_pairs_evaluated = 0

        for identity in sorted(required_by_identity):
            required, unit_ids = required_by_identity[identity]
            candidates = tuple(sorted(candidate_load.candidates_by_required_identity.get(identity, ()), key=_boundary_sort_key))
            truncated = identity in candidate_load.truncated_required_identities
            resolution = self._resolve_required_boundary(
                required,
                tuple(sorted(unit_ids)),
                candidates,
                candidate_load.provided_boundaries_by_fingerprint,
                candidate_set_truncated=truncated,
            )
            resolutions.append(resolution)
            diagnostics.extend(resolution.diagnostics)
            candidate_pairs_evaluated += len(resolution.evaluated_candidates)
            conflict_count += sum(1 for item in resolution.evaluated_candidates if item.conflicting_descriptors)
            evidence_insufficient_count += sum(1 for item in resolution.evaluated_candidates if "BOUNDARY_EVIDENCE_INSUFFICIENT" in item.rejection_reasons)

        proven_links = tuple(self._proven_link(resolution) for resolution in resolutions if resolution.status is BoundaryResolutionStatus.PROVEN)
        ambiguous = tuple(resolution for resolution in resolutions if resolution.status is BoundaryResolutionStatus.AMBIGUOUS)
        unresolved = tuple(boundary_identity(resolution.required_boundary) for resolution in resolutions if resolution.status is BoundaryResolutionStatus.UNRESOLVED)
        owners = tuple(sorted((link.target_owner for link in proven_links), key=_owner_sort_key))
        truncation = BoundaryResolutionTruncationState(
            candidate_sets_truncated=len(candidate_load.truncated_required_identities),
            candidate_descriptor_scan_truncated=candidate_load.candidate_descriptor_scan_truncated,
            truncated_required_identities=tuple(sorted(candidate_load.truncated_required_identities)),
            descriptor_scan_truncated_required_identities=tuple(sorted(candidate_load.truncated_required_identities))
            if candidate_load.candidate_descriptor_scan_truncated
            else (),
        )
        metrics = BoundaryResolverMetrics(
            required_boundary_count=len(required_by_identity),
            eligible_provided_boundary_count=candidate_load.eligible_provided_boundary_count,
            provided_candidates_by_source=dict(sorted(candidate_load.provided_candidates_by_source.items())),
            descriptor_fingerprints_queried=candidate_load.descriptor_fingerprints_queried,
            candidate_pairs_evaluated=candidate_pairs_evaluated,
            proven_count=len(proven_links),
            ambiguous_count=len(ambiguous),
            unresolved_count=len(unresolved),
            candidate_sets_truncated=len(candidate_load.truncated_required_identities),
            conflict_count=conflict_count,
            evidence_insufficient_count=evidence_insufficient_count,
            target_owners_discovered=len(owners),
            resolver_sql_statements=candidate_load.sql_statements,
            candidate_descriptor_rows_scanned=candidate_load.candidate_descriptor_rows_scanned,
            candidate_descriptor_rows_matched_exactly=candidate_load.candidate_descriptor_rows_matched_exactly,
            candidate_descriptor_row_budget=candidate_load.candidate_descriptor_row_budget,
            candidate_descriptor_scan_truncated=candidate_load.candidate_descriptor_scan_truncated,
            candidate_sources_inspected=candidate_load.candidate_sources_inspected,
            candidate_sources_truncated=candidate_load.candidate_sources_truncated,
            candidate_pages_loaded=candidate_load.candidate_pages_loaded,
            required_candidate_sets_incomplete=len(candidate_load.truncated_required_identities),
        )
        diagnostics.append(
            BoundaryResolutionDiagnostic(
                code="GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS",
                message="Generic boundary resolution diagnostics.",
                severity="INFO",
                metadata={
                    "requiredBoundaryCount": metrics.required_boundary_count,
                    "eligibleProvidedBoundaryCount": metrics.eligible_provided_boundary_count,
                    "providedCandidatesBySource": metrics.provided_candidates_by_source,
                    "descriptorFingerprintsQueried": metrics.descriptor_fingerprints_queried,
                    "candidatePairsEvaluated": metrics.candidate_pairs_evaluated,
                    "PROVENCount": metrics.proven_count,
                    "AMBIGUOUSCount": metrics.ambiguous_count,
                    "UNRESOLVEDCount": metrics.unresolved_count,
                    "candidateSetsTruncated": metrics.candidate_sets_truncated,
                    "conflictCount": metrics.conflict_count,
                    "evidenceInsufficientCount": metrics.evidence_insufficient_count,
                    "targetOwnersDiscovered": metrics.target_owners_discovered,
                    "targetUnitsConsidered": metrics.target_units_considered,
                    "targetUnitsMaterialized": metrics.target_units_materialized,
                    "targetUnitsOmitted": metrics.target_units_omitted,
                    "partialTargetMaterializationCount": metrics.partial_target_materialization_count,
                    "resolutionRounds": metrics.resolution_rounds,
                    "resolutionCyclesDetected": metrics.resolution_cycles_detected,
                    "resolverSQLStatements": metrics.resolver_sql_statements,
                    "candidateDescriptorRowsScanned": metrics.candidate_descriptor_rows_scanned,
                    "candidateDescriptorRowsMatchedExactly": metrics.candidate_descriptor_rows_matched_exactly,
                    "candidateDescriptorRowBudget": metrics.candidate_descriptor_row_budget,
                    "candidateDescriptorScanTruncated": metrics.candidate_descriptor_scan_truncated,
                    "candidateSourcesInspected": metrics.candidate_sources_inspected,
                    "candidateSourcesTruncated": metrics.candidate_sources_truncated,
                    "candidatePagesLoaded": metrics.candidate_pages_loaded,
                    "requiredCandidateSetsIncomplete": metrics.required_candidate_sets_incomplete,
                },
            )
        )
        return BoundaryResolutionResult(
            resolutions=tuple(sorted(resolutions, key=lambda item: item.resolution_id)),
            proven_links=tuple(sorted(proven_links, key=lambda item: item.resolution_id)),
            ambiguous_links=tuple(sorted(ambiguous, key=lambda item: item.resolution_id)),
            unresolved_boundaries=tuple(sorted(unresolved)),
            discovered_provided_owners=owners,
            diagnostics=tuple(diagnostics),
            truncation=truncation,
            metrics=metrics,
        )

    def _resolve_required_boundary(
        self,
        required: LocalBoundaryFact,
        required_unit_ids: Sequence[str],
        candidates: Sequence[LocalBoundaryFact],
        provided_boundaries_by_fingerprint: Mapping[DescriptorFingerprint, frozenset[BoundaryIdentity]],
        *,
        candidate_set_truncated: bool,
    ) -> BoundaryResolution:
        unit_ids = tuple(sorted({str(item or "") for item in required_unit_ids if str(item or "")}))
        candidate_unit_identity = unit_ids[0] if len(unit_ids) == 1 else ",".join(unit_ids)
        evaluations = tuple(
            self._evaluate_candidate(required, candidate_unit_identity, candidate, provided_boundaries_by_fingerprint)
            for candidate in candidates
        )
        diagnostics: list[BoundaryResolutionDiagnostic] = []
        selected: LocalBoundaryFact | None = None
        proving: tuple[DescriptorFingerprint, ...] = ()
        evidence_refs: tuple[EvidenceReference, ...] = ()
        status = BoundaryResolutionStatus.UNRESOLVED

        if candidate_set_truncated:
            diagnostics.append(
                _boundary_diagnostic(
                    "BOUNDARY_CANDIDATE_SET_INCOMPLETE",
                    required,
                    "Boundary candidate set was incomplete; resolution failed closed.",
                    severity="WARN",
                    metadata={"candidateCount": len(candidates)},
                )
            )
        proof_eligible = [item for item in evaluations if item.proof_eligibility and not item.rejection_reasons]
        if candidate_set_truncated:
            status = BoundaryResolutionStatus.UNRESOLVED
        elif len(proof_eligible) == 1:
            status = BoundaryResolutionStatus.PROVEN
            winner = proof_eligible[0]
            selected = next((item for item in candidates if boundary_identity(item) == winner.provided_boundary_identity), None)
            proving = winner.proving_descriptor_fingerprints
            evidence_refs = _candidate_evidence_references(winner, selected)
            diagnostics.append(
                _boundary_diagnostic(
                    "BOUNDARY_RESOLUTION_PROVEN",
                    required,
                    "Required boundary resolved to one proven provided boundary.",
                    metadata=_resolution_metadata(winner, len(candidates)),
                )
            )
        elif len(proof_eligible) > 1 or self._has_non_unique_composite_ambiguity(evaluations):
            status = BoundaryResolutionStatus.AMBIGUOUS
            diagnostics.append(
                _boundary_diagnostic(
                    "BOUNDARY_RESOLUTION_AMBIGUOUS",
                    required,
                    "Required boundary matched multiple indistinguishable provided candidates.",
                    metadata={"candidateCount": len(candidates), "proofEligibleCount": len(proof_eligible)},
                )
            )
        else:
            diagnostics.append(
                _boundary_diagnostic(
                    "BOUNDARY_RESOLUTION_UNRESOLVED",
                    required,
                    "Required boundary did not have a complete exact evidence-backed unique provided candidate.",
                    metadata={"candidateCount": len(candidates), "rejectionReasons": _aggregate_rejection_reasons(evaluations)},
                )
            )

        for evaluation in evaluations:
            if evaluation.conflicting_descriptors:
                diagnostics.append(
                    _boundary_diagnostic(
                        "BOUNDARY_DESCRIPTOR_CONFLICT",
                        required,
                        "Candidate contains material descriptor conflicts.",
                        metadata={
                            "providedBoundary": _identity_payload(evaluation.provided_boundary_identity),
                            "conflictCount": len(evaluation.conflicting_descriptors),
                        },
                    )
                )
            if "BOUNDARY_EVIDENCE_INSUFFICIENT" in evaluation.rejection_reasons:
                diagnostics.append(
                    _boundary_diagnostic(
                        "BOUNDARY_EVIDENCE_INSUFFICIENT",
                        required,
                        "Candidate lacks persisted evidence for the proving descriptor set.",
                        metadata={"providedBoundary": _identity_payload(evaluation.provided_boundary_identity)},
                    )
                )

        return BoundaryResolution(
            resolution_id=stable_resolution_id(required),
            required_boundary=required,
            status=status,
            selected_provided_boundary=selected if status is BoundaryResolutionStatus.PROVEN else None,
            evaluated_candidates=tuple(sorted(evaluations, key=lambda item: item.provided_boundary_identity)),
            proving_descriptor_fingerprints=proving,
            evidence_references=evidence_refs,
            diagnostics=tuple(diagnostics),
            required_unit_ids=unit_ids,
        )

    def _evaluate_candidate(
        self,
        required: LocalBoundaryFact,
        required_unit_identity: str,
        provided: LocalBoundaryFact,
        provided_boundaries_by_fingerprint: Mapping[DescriptorFingerprint, frozenset[BoundaryIdentity]],
    ) -> BoundaryCandidateEvaluation:
        required_identity = boundary_identity(required)
        provided_identity = boundary_identity(provided)
        matches, conflicts, missing = compare_boundary_descriptors(required, provided)
        evidence_backed = tuple(match for match in matches if match.required_evidence_refs and match.provided_evidence_refs)
        proving: tuple[DescriptorFingerprint, ...] = ()
        proof_eligibility: tuple[str, ...] = ()
        rejection_reasons: list[str] = []

        if str(required.role or "").strip().upper() != BOUNDARY_ROLE_REQUIRED or not _accepted(required.status):
            rejection_reasons.append("REQUIRED_BOUNDARY_NOT_ACCEPTED")
        if str(provided.role or "").strip().upper() != BOUNDARY_ROLE_PROVIDED or not _accepted(provided.status):
            rejection_reasons.append("PROVIDED_BOUNDARY_NOT_ACCEPTED")
        if required_identity.source_id == provided_identity.source_id:
            rejection_reasons.append("SAME_SOURCE_CANDIDATE")
        if not matches:
            rejection_reasons.append("NO_EXACT_DESCRIPTOR_MATCH")
        if conflicts:
            rejection_reasons.append("BOUNDARY_DESCRIPTOR_CONFLICT")
        if not provided.owner_node_id:
            rejection_reasons.append("PROVIDED_OWNER_MISSING")
        if matches and not evidence_backed:
            rejection_reasons.append("BOUNDARY_EVIDENCE_INSUFFICIENT")

        if not rejection_reasons:
            unique_matches = [
                match
                for match in evidence_backed
                if _eligible_fingerprint_boundaries(
                    provided_boundaries_by_fingerprint.get(match.fingerprint, frozenset()),
                    required_identity,
                )
                == frozenset({provided_identity})
            ]
            if unique_matches:
                first = min(unique_matches, key=lambda item: item.fingerprint)
                proving = (first.fingerprint,)
                proof_eligibility = ("UNIQUE_EXACT_DESCRIPTOR",)
            else:
                fingerprints = tuple(sorted({match.fingerprint for match in evidence_backed}))
                if fingerprints:
                    matching_boundaries = _intersect_boundary_sets(
                        _eligible_fingerprint_boundaries(
                            provided_boundaries_by_fingerprint.get(fingerprint, frozenset()),
                            required_identity,
                        )
                        for fingerprint in fingerprints
                    )
                    if matching_boundaries == frozenset({provided_identity}) and len(fingerprints) > 1:
                        proving = fingerprints
                        proof_eligibility = ("UNIQUE_COMPOSITE_DESCRIPTOR_SET",)
                    else:
                        rejection_reasons.append("NON_DISCRIMINATING_DESCRIPTOR_SET")

        candidate_score = _candidate_score(matches, conflicts, proof_eligibility, required, provided)
        return BoundaryCandidateEvaluation(
            required_boundary_identity=required_identity,
            provided_boundary_identity=provided_identity,
            required_unit_identity=required_unit_identity,
            provided_owner_identity=boundary_owner_identity(provided),
            exact_descriptor_matches=matches,
            conflicting_descriptors=conflicts,
            missing_descriptors=missing,
            evidence_sufficiency="SUFFICIENT" if evidence_backed else "INSUFFICIENT",
            provenance_summary={
                "requiredBoundaryProvenance": required.provenance,
                "providedBoundaryProvenance": provided.provenance,
                "descriptorOrigins": tuple(sorted({descriptor.origin for descriptor in (*required.descriptors, *provided.descriptors) if descriptor.origin})),
            },
            confidence_summary={
                "requiredBoundaryConfidence": required.confidence,
                "providedBoundaryConfidence": provided.confidence,
                "matchedDescriptorConfidenceMin": _matched_confidence_min(matches, required, provided),
            },
            candidate_score=candidate_score,
            proof_eligibility=proof_eligibility,
            rejection_reasons=tuple(sorted(set(rejection_reasons))),
            proving_descriptor_fingerprints=proving,
        )

    def _has_non_unique_composite_ambiguity(self, evaluations: Sequence[BoundaryCandidateEvaluation]) -> bool:
        signatures: dict[tuple[str, ...], int] = {}
        for evaluation in evaluations:
            if evaluation.rejection_reasons and set(evaluation.rejection_reasons) - {"NON_DISCRIMINATING_DESCRIPTOR_SET"}:
                continue
            fingerprints = tuple(sorted({match.fingerprint.fingerprint_hash for match in evaluation.exact_descriptor_matches}))
            if len(fingerprints) <= 1:
                continue
            signatures[fingerprints] = signatures.get(fingerprints, 0) + 1
        return any(count > 1 for count in signatures.values())

    def _proven_link(self, resolution: BoundaryResolution) -> ProvenBoundaryLink:
        if resolution.selected_provided_boundary is None:
            raise RuntimeError("PROVEN boundary resolution requires a selected provided boundary")
        return ProvenBoundaryLink(
            resolution_id=resolution.resolution_id,
            required_boundary_identity=boundary_identity(resolution.required_boundary),
            provided_boundary_identity=boundary_identity(resolution.selected_provided_boundary),
            target_owner=boundary_owner_identity(resolution.selected_provided_boundary),
            proving_descriptor_fingerprints=resolution.proving_descriptor_fingerprints,
            evidence_references=resolution.evidence_references,
            required_unit_ids=resolution.required_unit_ids,
        )


def compare_boundary_descriptors(
    required: LocalBoundaryFact,
    provided: LocalBoundaryFact,
) -> tuple[tuple[BoundaryDescriptorMatch, ...], tuple[BoundaryDescriptorConflict, ...], tuple[BoundaryMissingDescriptor, ...]]:
    required_records = _descriptor_records(required)
    provided_records = _descriptor_records(provided)
    required_by_fp = _records_by_fingerprint(required_records)
    provided_by_fp = _records_by_fingerprint(provided_records)

    matches: list[BoundaryDescriptorMatch] = []
    for fingerprint in sorted(set(required_by_fp) & set(provided_by_fp)):
        required_items = required_by_fp[fingerprint]
        provided_items = provided_by_fp[fingerprint]
        matches.append(
            BoundaryDescriptorMatch(
                fingerprint=fingerprint,
                required_descriptor_ids=tuple(sorted(item.descriptor.descriptor_id for item in required_items)),
                provided_descriptor_ids=tuple(sorted(item.descriptor.descriptor_id for item in provided_items)),
                required_evidence_refs=_descriptor_record_evidence(required_items, required),
                provided_evidence_refs=_descriptor_record_evidence(provided_items, provided),
            )
        )

    required_by_path_type = _records_by_path_type(required_records)
    provided_by_path_type = _records_by_path_type(provided_records)
    conflicts: list[BoundaryDescriptorConflict] = []
    for path_type in sorted(set(required_by_path_type) & set(provided_by_path_type)):
        required_items = required_by_path_type[path_type]
        provided_items = provided_by_path_type[path_type]
        required_fps = {item.fingerprint for item in required_items}
        provided_fps = {item.fingerprint for item in provided_items}
        if required_fps == provided_fps:
            continue
        if required_fps - provided_fps and provided_fps - required_fps:
            conflicts.append(
                BoundaryDescriptorConflict(
                    path=path_type[0],
                    value_type=path_type[1],
                    required_descriptor_ids=tuple(sorted(item.descriptor.descriptor_id for item in required_items if item.fingerprint not in provided_fps)),
                    provided_descriptor_ids=tuple(sorted(item.descriptor.descriptor_id for item in provided_items if item.fingerprint not in required_fps)),
                    required_fingerprint_hashes=tuple(sorted(item.fingerprint.fingerprint_hash for item in required_items if item.fingerprint not in provided_fps)),
                    provided_fingerprint_hashes=tuple(sorted(item.fingerprint.fingerprint_hash for item in provided_items if item.fingerprint not in required_fps)),
                )
            )

    missing: list[BoundaryMissingDescriptor] = []
    for path_type in sorted(set(required_by_path_type) - set(provided_by_path_type)):
        missing.append(
            BoundaryMissingDescriptor(
                side="PROVIDED",
                path=path_type[0],
                value_type=path_type[1],
                descriptor_ids=tuple(sorted(item.descriptor.descriptor_id for item in required_by_path_type[path_type])),
            )
        )
    for path_type in sorted(set(provided_by_path_type) - set(required_by_path_type)):
        missing.append(
            BoundaryMissingDescriptor(
                side="REQUIRED",
                path=path_type[0],
                value_type=path_type[1],
                descriptor_ids=tuple(sorted(item.descriptor.descriptor_id for item in provided_by_path_type[path_type])),
            )
        )
    return tuple(matches), tuple(conflicts), tuple(missing)


@dataclass(frozen=True)
class _DescriptorRecord:
    descriptor: LocalBoundaryDescriptor
    fingerprint: DescriptorFingerprint


def _descriptor_records(boundary: LocalBoundaryFact) -> tuple[_DescriptorRecord, ...]:
    return tuple(
        sorted(
            (_DescriptorRecord(descriptor=item, fingerprint=descriptor_fingerprint(item)) for item in boundary.descriptors),
            key=lambda item: (item.fingerprint, item.descriptor.descriptor_id),
        )
    )


def _records_by_fingerprint(records: Sequence[_DescriptorRecord]) -> dict[DescriptorFingerprint, tuple[_DescriptorRecord, ...]]:
    grouped: dict[DescriptorFingerprint, list[_DescriptorRecord]] = {}
    for record in records:
        grouped.setdefault(record.fingerprint, []).append(record)
    return {key: tuple(value) for key, value in grouped.items()}


def _records_by_path_type(records: Sequence[_DescriptorRecord]) -> dict[tuple[str, str], tuple[_DescriptorRecord, ...]]:
    grouped: dict[tuple[str, str], list[_DescriptorRecord]] = {}
    for record in records:
        grouped.setdefault((record.fingerprint.path, record.fingerprint.value_type), []).append(record)
    return {key: tuple(value) for key, value in grouped.items()}


def _descriptor_record_evidence(records: Sequence[_DescriptorRecord], boundary: LocalBoundaryFact) -> tuple[EvidenceReference, ...]:
    descriptor_refs = evidence_references(tuple(item for record in records for item in record.descriptor.evidence))
    if descriptor_refs:
        return descriptor_refs
    return evidence_references(boundary.evidence)


def _canonical_json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Mapping):
        return {str(key): _canonical_json_value(value[key]) for key in sorted(value, key=lambda item: str(item))}
    if isinstance(value, (list, tuple)):
        return [_canonical_json_value(item) for item in value]
    return str(value)


def _accepted(status: object) -> bool:
    return str(status or "").strip().upper() in ACCEPTED_BOUNDARY_STATUSES


def _intersect_boundary_sets(sets: Sequence[frozenset[BoundaryIdentity]]) -> frozenset[BoundaryIdentity]:
    iterator = iter([item for item in sets if item])
    try:
        result = next(iterator)
    except StopIteration:
        return frozenset()
    for item in iterator:
        result = result & item
    return result


def _eligible_fingerprint_boundaries(
    identities: frozenset[BoundaryIdentity],
    required_identity: BoundaryIdentity,
) -> frozenset[BoundaryIdentity]:
    return frozenset(identity for identity in identities if identity.source_id != required_identity.source_id)


def _candidate_score(
    matches: Sequence[BoundaryDescriptorMatch],
    conflicts: Sequence[BoundaryDescriptorConflict],
    proof_eligibility: Sequence[str],
    required: LocalBoundaryFact,
    provided: LocalBoundaryFact,
) -> float:
    return (
        len({match.fingerprint for match in matches}) * 10.0
        + len(proof_eligibility) * 100.0
        - len(conflicts) * 25.0
        + max(0.0, min(float(required.confidence or 0.0), 1.0))
        + max(0.0, min(float(provided.confidence or 0.0), 1.0))
    )


def _matched_confidence_min(
    matches: Sequence[BoundaryDescriptorMatch],
    required: LocalBoundaryFact,
    provided: LocalBoundaryFact,
) -> float | None:
    ids = {
        descriptor_id
        for match in matches
        for descriptor_id in itertools.chain(match.required_descriptor_ids, match.provided_descriptor_ids)
    }
    values = [
        float(descriptor.confidence)
        for descriptor in (*required.descriptors, *provided.descriptors)
        if descriptor.descriptor_id in ids and descriptor.confidence is not None
    ]
    return min(values) if values else None


def _candidate_evidence_references(
    evaluation: BoundaryCandidateEvaluation,
    selected: LocalBoundaryFact | None,
) -> tuple[EvidenceReference, ...]:
    refs: dict[tuple[str, str, str, str, str, str], EvidenceReference] = {}
    proving_hashes = {fingerprint.fingerprint_hash for fingerprint in evaluation.proving_descriptor_fingerprints}
    for match in evaluation.exact_descriptor_matches:
        if match.fingerprint.fingerprint_hash not in proving_hashes:
            continue
        for ref in (*match.required_evidence_refs, *match.provided_evidence_refs):
            refs.setdefault(_evidence_ref_sort_key(ref), ref)
    if selected is not None:
        for ref in evidence_references(selected.evidence):
            refs.setdefault(_evidence_ref_sort_key(ref), ref)
    return tuple(refs[key] for key in sorted(refs))


def _boundary_diagnostic(
    code: str,
    boundary: LocalBoundaryFact,
    message: str,
    *,
    severity: str = "INFO",
    metadata: Mapping[str, Any] | None = None,
) -> BoundaryResolutionDiagnostic:
    return BoundaryResolutionDiagnostic(
        code=code,
        message=message,
        severity=severity,
        source_id=boundary.source_id,
        metadata={
            "requiredBoundary": _identity_payload(boundary_identity(boundary)),
            **dict(metadata or {}),
        },
    )


def _resolution_metadata(evaluation: BoundaryCandidateEvaluation, candidate_count: int) -> dict[str, Any]:
    return {
        "providedBoundary": _identity_payload(evaluation.provided_boundary_identity),
        "candidateCount": candidate_count,
        "proofEligibility": evaluation.proof_eligibility,
        "provingDescriptorHashes": tuple(fingerprint.fingerprint_hash for fingerprint in evaluation.proving_descriptor_fingerprints),
    }


def _aggregate_rejection_reasons(evaluations: Sequence[BoundaryCandidateEvaluation]) -> tuple[str, ...]:
    return tuple(sorted({reason for evaluation in evaluations for reason in evaluation.rejection_reasons}))


def _identity_payload(identity: BoundaryIdentity) -> dict[str, str]:
    return {
        "sourceId": identity.source_id,
        "graphRevision": identity.graph_revision,
        "boundaryKey": identity.boundary_key,
        "ownerNodeId": identity.owner_node_id,
    }


def _boundary_sort_key(boundary: LocalBoundaryFact) -> tuple[str, str, str, str, str]:
    return (
        str(boundary.source_id or ""),
        str(boundary.graph_revision or boundary.graph_id or ""),
        str(boundary.owner_node_id or ""),
        str(boundary.role or ""),
        str(boundary.stable_key or boundary.boundary_id or ""),
    )


def _owner_sort_key(owner: BoundaryOwnerIdentity) -> tuple[str, str, str, BoundaryIdentity]:
    return (owner.source_id, owner.graph_revision, owner.owner_node_id, owner.boundary_identity)


def _evidence_ref_sort_key(ref: EvidenceReference) -> tuple[str, str, str, str, str, str]:
    return (
        ref.source_id,
        ref.graph_revision,
        ref.evidence_id,
        ref.owner_kind or "",
        ref.owner_node_id or "",
        ref.owner_edge_id or "",
    )
