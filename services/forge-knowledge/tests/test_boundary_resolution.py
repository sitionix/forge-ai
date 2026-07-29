from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Sequence

from knowledge_service.boundary_contract import LocalBoundaryDescriptor, LocalBoundaryFact
from knowledge_service.boundary_resolution import (
    BoundaryCandidateLoadLimits,
    BoundaryCandidateLoadResult,
    BoundaryResolutionStatus,
    GenericBoundaryResolver,
    boundary_identity,
    compare_boundary_descriptors,
    descriptor_fingerprint,
)
from knowledge_service.flow_graph_contract import FlowGraphEvidence

REVISION = "revision-current"


@dataclass(frozen=True)
class Unit:
    unit_id: str
    generic_boundaries: tuple[LocalBoundaryFact, ...]


def evidence(evidence_id: str, *, source: str = "source-a", node: str = "owner") -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id=source,
        graph_id=REVISION,
        graph_revision=REVISION,
        evidence_id=evidence_id,
        node_id=node,
        edge_id=None,
        relative_path=f"{source}/{node}.txt",
        line_start=1,
        line_end=1,
        text="grounded fact",
        owner_kind="BOUNDARY",
        owner_source_id=source,
        owner_node_id=node,
    )


def descriptor(
    descriptor_id: str,
    path: str,
    value,
    *,
    value_type: str = "STRING",
    source: str = "source-a",
    node: str = "owner",
    with_evidence: bool = True,
    origin: str = "STATIC",
) -> LocalBoundaryDescriptor:
    return LocalBoundaryDescriptor(
        descriptor_id=descriptor_id,
        path=path,
        value_type=value_type,
        value=value,
        origin=origin,
        confidence=0.9,
        evidence=(evidence(f"ev-{descriptor_id}", source=source, node=node),) if with_evidence else (),
    )


def boundary(
    boundary_id: str,
    role: str,
    *,
    source: str = "source-a",
    node: str = "owner",
    descriptors: Sequence[LocalBoundaryDescriptor] = (),
    with_evidence: bool = True,
    confidence: float = 0.9,
    provenance: str = "STATIC",
    status: str = "TRUSTED",
    flow_domain: str = "CODE",
) -> LocalBoundaryFact:
    return LocalBoundaryFact(
        boundary_id=boundary_id,
        stable_key=f"{source}:boundary:{boundary_id}",
        source_id=source,
        graph_id=REVISION,
        graph_revision=REVISION,
        owner_node_id=node,
        role=role,
        status=status,
        provenance=provenance,
        confidence=confidence,
        flow_domain=flow_domain,
        descriptors=tuple(descriptors),
        evidence=(evidence(f"ev-boundary-{boundary_id}", source=source, node=node),) if with_evidence else (),
    )


def load_result(
    required: Sequence[LocalBoundaryFact],
    provided: Sequence[LocalBoundaryFact],
    *,
    truncated: Sequence[LocalBoundaryFact] = (),
) -> BoundaryCandidateLoadResult:
    provided_by_fingerprint = {}
    for candidate in provided:
        for item in candidate.descriptors:
            provided_by_fingerprint.setdefault(descriptor_fingerprint(item), set()).add(boundary_identity(candidate))
    candidates_by_required = {}
    for item in required:
        fingerprints = {descriptor_fingerprint(descriptor_item) for descriptor_item in item.descriptors}
        candidates_by_required[boundary_identity(item)] = tuple(
            sorted(
                (
                    candidate
                    for candidate in provided
                    if candidate.source_id != item.source_id
                    and fingerprints & {descriptor_fingerprint(descriptor_item) for descriptor_item in candidate.descriptors}
                ),
                key=lambda candidate: boundary_identity(candidate),
            )
        )
    return BoundaryCandidateLoadResult(
        candidates_by_required_identity=candidates_by_required,
        provided_boundaries_by_fingerprint={key: frozenset(value) for key, value in provided_by_fingerprint.items()},
        eligible_provided_boundary_count=len(provided),
        provided_candidates_by_source={source: sum(1 for item in provided if item.source_id == source) for source in sorted({item.source_id for item in provided})},
        descriptor_fingerprints_queried=len({descriptor_fingerprint(item) for boundary_item in required for item in boundary_item.descriptors}),
        truncated_required_identities=frozenset(boundary_identity(item) for item in truncated),
    )


def resolve(required: Sequence[LocalBoundaryFact], provided: Sequence[LocalBoundaryFact], *, truncated: Sequence[LocalBoundaryFact] = ()):
    return GenericBoundaryResolver().resolve((Unit("unit-a", tuple(required)),), load_result(required, provided, truncated=truncated))


def test_canonical_descriptor_behaviour_preserves_exact_typed_semantics():
    same_left = descriptor("same-left", " contract.identity ", "Alpha")
    same_right = descriptor("same-right", "contract.identity", "Alpha")
    assert descriptor_fingerprint(same_left) == descriptor_fingerprint(same_right)

    assert descriptor_fingerprint(descriptor("case-a", "contract.identity", "Alpha")) != descriptor_fingerprint(
        descriptor("case-b", "contract.identity", "alpha")
    )
    assert descriptor_fingerprint(descriptor("num-string", "contract.identity", "1", value_type="STRING")) != descriptor_fingerprint(
        descriptor("num-int", "contract.identity", 1, value_type="INTEGER")
    )
    assert descriptor_fingerprint(descriptor("bool", "contract.flag", True, value_type="BOOLEAN")) != descriptor_fingerprint(
        descriptor("bool-string", "contract.flag", "true", value_type="STRING")
    )
    assert descriptor_fingerprint(descriptor("object-a", "contract.object", {"b": 2, "a": 1}, value_type="OBJECT")) == descriptor_fingerprint(
        descriptor("object-b", "contract.object", {"a": 1, "b": 2}, value_type="OBJECT")
    )
    assert descriptor_fingerprint(descriptor("array-a", "contract.array", [1, 2], value_type="ARRAY")) != descriptor_fingerprint(
        descriptor("array-b", "contract.array", [2, 1], value_type="ARRAY")
    )


def test_duplicate_equal_descriptors_dedupe_for_scoring_but_conflicts_and_missing_stay_visible():
    required = boundary(
        "required",
        "REQUIRED",
        descriptors=(
            descriptor("required-a", "contract.identity", "alpha"),
            descriptor("required-a-duplicate", "contract.identity", "alpha"),
            descriptor("required-conflict", "contract.kind", "left"),
            descriptor("required-missing", "contract.onlyRequired", "value"),
        ),
    )
    provided = boundary(
        "provided",
        "PROVIDED",
        source="source-b",
        descriptors=(
            descriptor("provided-a", "contract.identity", "alpha", source="source-b"),
            descriptor("provided-conflict", "contract.kind", "right", source="source-b"),
            descriptor("provided-missing", "contract.onlyProvided", "value", source="source-b"),
        ),
    )

    matches, conflicts, missing = compare_boundary_descriptors(required, provided)

    assert len(matches) == 1
    assert matches[0].required_descriptor_ids == ("required-a", "required-a-duplicate")
    assert len(conflicts) == 1
    assert conflicts[0].path == "contract.kind"
    assert {item.path for item in missing} == {"contract.onlyRequired", "contract.onlyProvided"}


def test_unique_exact_evidence_backed_descriptor_produces_proven():
    required = boundary("required", "REQUIRED", descriptors=(descriptor("required-key", "contract.identity", "alpha"),))
    provided = boundary("provided", "PROVIDED", source="source-b", descriptors=(descriptor("provided-key", "contract.identity", "alpha", source="source-b"),))

    result = resolve((required,), (provided,))

    assert [item.status for item in result.resolutions] == [BoundaryResolutionStatus.PROVEN]
    assert result.proven_links[0].target_owner.owner_node_id == "owner"


def test_required_boundary_unit_membership_preserves_all_exact_local_units():
    required = boundary("required", "REQUIRED", descriptors=(descriptor("required-key", "contract.identity", "alpha"),))
    provided = boundary("provided", "PROVIDED", source="source-b", descriptors=(descriptor("provided-key", "contract.identity", "alpha", source="source-b"),))
    load = load_result((required,), (provided,))

    result = GenericBoundaryResolver().resolve(
        (
            Unit("unit-b", (required,)),
            Unit("unit-a", (required,)),
            Unit("unit-b", (required,)),
        ),
        load,
    )

    assert result.resolutions[0].required_unit_ids == ("unit-a", "unit-b")
    assert result.proven_links[0].required_unit_ids == ("unit-a", "unit-b")


def test_unique_composite_descriptor_set_produces_proven_when_individual_fingerprints_are_common():
    required = boundary(
        "required",
        "REQUIRED",
        descriptors=(descriptor("required-a", "contract.a", "one"), descriptor("required-b", "contract.b", "two")),
    )
    provided = boundary(
        "provided",
        "PROVIDED",
        source="source-b",
        descriptors=(descriptor("provided-a", "contract.a", "one", source="source-b"), descriptor("provided-b", "contract.b", "two", source="source-b")),
    )
    only_a = boundary("only-a", "PROVIDED", source="source-c", descriptors=(descriptor("provided-c-a", "contract.a", "one", source="source-c"),))
    only_b = boundary("only-b", "PROVIDED", source="source-d", descriptors=(descriptor("provided-d-b", "contract.b", "two", source="source-d"),))

    result = resolve((required,), (only_b, provided, only_a))

    assert result.resolutions[0].status is BoundaryResolutionStatus.PROVEN
    selected = next(item for item in result.resolutions[0].evaluated_candidates if item.provided_boundary_identity == boundary_identity(provided))
    assert selected.proof_eligibility == ("UNIQUE_COMPOSITE_DESCRIPTOR_SET",)


def test_common_single_descriptor_is_unresolved_and_duplicate_composite_is_ambiguous():
    required_common = boundary("required-common", "REQUIRED", descriptors=(descriptor("required-common-key", "contract.common", "same"),))
    common_a = boundary("common-a", "PROVIDED", source="source-b", descriptors=(descriptor("common-a-key", "contract.common", "same", source="source-b"),))
    common_b = boundary("common-b", "PROVIDED", source="source-c", descriptors=(descriptor("common-b-key", "contract.common", "same", source="source-c"),))

    common_result = resolve((required_common,), (common_a, common_b))

    assert common_result.resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED

    required_composite = boundary(
        "required-composite",
        "REQUIRED",
        descriptors=(descriptor("required-a", "contract.a", "one"), descriptor("required-b", "contract.b", "two")),
    )
    duplicate_a = boundary(
        "duplicate-a",
        "PROVIDED",
        source="source-b",
        descriptors=(descriptor("duplicate-a1", "contract.a", "one", source="source-b"), descriptor("duplicate-a2", "contract.b", "two", source="source-b")),
    )
    duplicate_b = boundary(
        "duplicate-b",
        "PROVIDED",
        source="source-c",
        descriptors=(descriptor("duplicate-b1", "contract.a", "one", source="source-c"), descriptor("duplicate-b2", "contract.b", "two", source="source-c")),
    )

    duplicate_result = resolve((required_composite,), (duplicate_b, duplicate_a))

    assert duplicate_result.resolutions[0].status is BoundaryResolutionStatus.AMBIGUOUS
    assert duplicate_result.proven_links == ()


def test_unresolved_conflict_missing_evidence_confidence_and_provenance_fail_closed():
    no_match = boundary("no-match", "REQUIRED", descriptors=(descriptor("no-match-required", "contract.identity", "alpha"),), confidence=1.0)
    matching_provenance = boundary(
        "matching-provenance",
        "PROVIDED",
        source="source-b",
        descriptors=(descriptor("no-match-provided", "contract.identity", "beta", source="source-b"),),
        confidence=1.0,
        provenance="STATIC",
    )
    assert resolve((no_match,), (matching_provenance,)).resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED

    conflict_required = boundary(
        "conflict-required",
        "REQUIRED",
        descriptors=(descriptor("conflict-required-match", "contract.match", "same"), descriptor("conflict-required-value", "contract.identity", "alpha")),
    )
    conflict_provided = boundary(
        "conflict-provided",
        "PROVIDED",
        source="source-b",
        descriptors=(
            descriptor("conflict-provided-match", "contract.match", "same", source="source-b"),
            descriptor("conflict-provided-value", "contract.identity", "beta", source="source-b"),
        ),
    )
    assert resolve((conflict_required,), (conflict_provided,)).resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED

    missing_evidence_required = boundary(
        "missing-evidence-required",
        "REQUIRED",
        descriptors=(descriptor("missing-evidence-required-key", "contract.identity", "alpha", with_evidence=False),),
        with_evidence=False,
    )
    missing_evidence_provided = boundary(
        "missing-evidence-provided",
        "PROVIDED",
        source="source-b",
        descriptors=(descriptor("missing-evidence-provided-key", "contract.identity", "alpha", source="source-b", with_evidence=False),),
        with_evidence=False,
    )
    assert resolve((missing_evidence_required,), (missing_evidence_provided,)).resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED


def test_same_source_excluded_one_provided_may_satisfy_several_required_and_order_is_deterministic():
    required_a = boundary("required-a", "REQUIRED", descriptors=(descriptor("required-a-key", "contract.identity", "shared"),))
    required_b = boundary("required-b", "REQUIRED", node="other", descriptors=(descriptor("required-b-key", "contract.identity", "shared", node="other"),))
    same_source = boundary("same-source", "PROVIDED", descriptors=(descriptor("same-source-key", "contract.identity", "shared"),))
    provided = boundary("provided", "PROVIDED", source="source-b", descriptors=(descriptor("provided-key", "contract.identity", "shared", source="source-b"),))

    first = resolve((required_a, required_b), (same_source, provided))
    second = resolve((required_b, required_a), (provided, same_source))

    assert len(first.proven_links) == 2
    assert {link.target_owner.boundary_identity for link in first.proven_links} == {boundary_identity(provided)}
    assert [item.resolution_id for item in first.resolutions] == [item.resolution_id for item in second.resolutions]
    assert [item.status for item in first.resolutions] == [item.status for item in second.resolutions]


def test_candidate_truncation_prevents_proven():
    required = boundary("required", "REQUIRED", descriptors=(descriptor("required-key", "contract.identity", "alpha"),))
    provided = boundary("provided", "PROVIDED", source="source-b", descriptors=(descriptor("provided-key", "contract.identity", "alpha", source="source-b"),))

    result = resolve((required,), (provided,), truncated=(required,))

    assert result.resolutions[0].status is BoundaryResolutionStatus.UNRESOLVED
    assert any(item.code == "BOUNDARY_CANDIDATE_SET_INCOMPLETE" for item in result.diagnostics)


def test_limits_contract_is_internal_only_and_constructible():
    assert BoundaryCandidateLoadLimits(max_candidates_per_required=1).max_candidates_per_required == 1


def test_identical_labels_methods_or_fixture_paths_do_not_affect_resolution():
    required = boundary("required", "REQUIRED", descriptors=(descriptor("method-name", "neutral.identity", "alpha"),))
    provided = boundary("provided", "PROVIDED", source="source-b", descriptors=(descriptor("method-name", "neutral.identity", "alpha", source="source-b"),))
    renamed = replace(provided, stable_key=provided.stable_key, owner_node_id=provided.owner_node_id)

    assert resolve((required,), (renamed,)).resolutions[0].status is BoundaryResolutionStatus.PROVEN
