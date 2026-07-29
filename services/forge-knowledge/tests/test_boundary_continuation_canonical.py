from __future__ import annotations

from collections import defaultdict
from typing import Sequence

from test_end_to_end_flow import neutral_boundary, unit
from test_local_flow_unit_engine import boundary, descriptor, node, node_evidence

from knowledge_service.boundary_resolution import (
    BoundaryCandidateLoadResult,
    BoundaryResolutionStatus,
    descriptor_fingerprint,
)
from knowledge_service.end_to_end_flow import EndToEndFlowAssembler
from knowledge_service.knowledge_query_service import BoundaryContinuationPolicy, KnowledgeQueryService, QuerySource
from knowledge_service.local_flow_unit_engine import LocalFlowBuildResult


def test_empty_exact_selection_causes_zero_boundary_candidate_calls():
    repo = _ContinuationRepository(())
    service = _service(repo, ())

    result = service._assemble_generic_boundary_continuations((), _sources("source-a"), include_tests=False)

    assert result.local_units == ()
    assert repo.candidate_load_calls == 0


def test_selected_unit_recursively_continues_a_to_b_to_c_and_assembles_transitions():
    graph = _abc_fixture()
    repo = _ContinuationRepository((graph["unit_b"], graph["unit_c"]), candidates=(graph["provided_b"], graph["provided_c"]))
    service = _service(repo, (graph["unit_b"], graph["unit_c"]))

    continuation = service._assemble_generic_boundary_continuations((graph["unit_a"],), _sources("source-a", "source-b", "source-c"), include_tests=False)
    assembled = EndToEndFlowAssembler().assemble(
        continuation.local_units,
        query_entry_unit_ids=continuation.initial_selected_local_unit_ids,
        boundary_resolution=continuation.boundary_resolution,
    )

    assert [unit.unit_id for unit in continuation.local_units] == ["unit-a", "unit-b", "unit-c"]
    assert repo.candidate_load_calls == 2
    assert assembled.graphs[0].coverage.proven_cross_source_transition_count == 2


def test_ambiguous_and_unresolved_boundaries_materialise_open_boundaries_only():
    graph = _ambiguous_fixture()
    repo = _ContinuationRepository((), candidates=(graph["provided_b"], graph["provided_c"]))
    service = _service(repo, ())

    continuation = service._assemble_generic_boundary_continuations((graph["unit_a"],), _sources("source-a", "source-b", "source-c"), include_tests=False)
    assembled = EndToEndFlowAssembler().assemble(
        continuation.local_units,
        query_entry_unit_ids=continuation.initial_selected_local_unit_ids,
        boundary_resolution=continuation.boundary_resolution,
    )

    assert not assembled.graphs[0].proven_cross_source_transitions
    assert assembled.graphs[0].open_boundaries[0].status is BoundaryResolutionStatus.AMBIGUOUS

    unresolved_repo = _ContinuationRepository((), candidates=())
    unresolved_service = _service(unresolved_repo, ())
    unresolved = unresolved_service._assemble_generic_boundary_continuations((graph["unit_a"],), _sources("source-a", "source-b"), include_tests=False)
    unresolved_graph = EndToEndFlowAssembler().assemble(
        unresolved.local_units,
        query_entry_unit_ids=unresolved.initial_selected_local_unit_ids,
        boundary_resolution=unresolved.boundary_resolution,
    ).graphs[0]
    assert unresolved_graph.open_boundaries[0].status is BoundaryResolutionStatus.UNRESOLVED


def test_target_unit_limit_creates_partial_materialisation_and_omitted_targets_do_not_assemble():
    graph = _branch_fixture()
    repo = _ContinuationRepository((graph["unit_b"], graph["unit_c"]), candidates=(graph["provided_b"], graph["provided_c"]))
    service = _service(repo, (graph["unit_b"], graph["unit_c"]), continuation_policy=BoundaryContinuationPolicy(max_boundary_target_units=1))

    continuation = service._assemble_generic_boundary_continuations((graph["unit_a"],), _sources("source-a", "source-b", "source-c"), include_tests=False)
    assembled = EndToEndFlowAssembler().assemble(
        continuation.local_units,
        query_entry_unit_ids=continuation.initial_selected_local_unit_ids,
        boundary_resolution=continuation.boundary_resolution,
    )

    materializations = continuation.boundary_resolution.target_materializations
    assert any(item.omitted_target_local_unit_ids for item in materializations)
    assert assembled.graphs[0].coverage.proven_cross_source_transition_count == 1
    assert all("END_TO_END_REFERENCED_UNIT_MISSING" != diagnostic.code for diagnostic in assembled.diagnostics)


def test_http_only_metadata_creates_no_canonical_connectivity():
    owner_a = node("HttpA", source="source-a")
    owner_b = node("HttpB", source="source-b")
    unit_a = unit("unit-a", owner_a)
    unit_b = unit("unit-b", owner_b)
    repo = _ContinuationRepository((unit_b,), candidates=())
    service = _service(repo, (unit_b,))

    continuation = service._assemble_generic_boundary_continuations((unit_a,), _sources("source-a", "source-b"), include_tests=False)
    assembled = EndToEndFlowAssembler().assemble(
        continuation.local_units,
        query_entry_unit_ids=continuation.initial_selected_local_unit_ids,
        boundary_resolution=continuation.boundary_resolution,
    )

    assert not assembled.graphs[0].proven_cross_source_transitions
    assert all("HTTP" not in diagnostic.code for diagnostic in assembled.diagnostics)
    assert all("TRANSPORT" not in diagnostic.code for diagnostic in assembled.diagnostics)


class _ContinuationRepository:
    def __init__(self, target_units, *, candidates=()) -> None:
        self.target_units_by_owner = {unit.roots[0].node.node_id: unit for unit in target_units}
        self.candidates = tuple(candidates)
        self.candidate_load_calls = 0

    def find_provided_boundary_candidates(self, required_boundaries, *, eligible_source_ids, include_tests, internal_limits=None):
        del eligible_source_ids, include_tests, internal_limits
        self.candidate_load_calls += 1
        candidates_by_required = {}
        provided_by_fingerprint = defaultdict(set)
        for required in required_boundaries:
            required_fingerprints = {descriptor_fingerprint(descriptor) for descriptor in required.descriptors}
            matched = []
            for candidate in self.candidates:
                candidate_fingerprints = {descriptor_fingerprint(descriptor) for descriptor in candidate.descriptors}
                if required.source_id != candidate.source_id and required_fingerprints & candidate_fingerprints:
                    matched.append(candidate)
                    for fingerprint in required_fingerprints & candidate_fingerprints:
                        provided_by_fingerprint[fingerprint].add(_identity(candidate))
            candidates_by_required[_identity(required)] = tuple(sorted(matched, key=lambda item: item.boundary_id))
        return BoundaryCandidateLoadResult(
            candidates_by_required_identity=candidates_by_required,
            provided_boundaries_by_fingerprint={fingerprint: frozenset(values) for fingerprint, values in provided_by_fingerprint.items()},
            eligible_provided_boundary_count=len(self.candidates),
            provided_candidates_by_source={},
            descriptor_fingerprints_queried=1,
        )

    def load_nodes(self, node_keys, *, include_tests):
        del include_tests
        nodes = {}
        for key in node_keys:
            unit = self.target_units_by_owner.get(key[2])
            if unit is not None:
                node_item = unit.roots[0].node
                nodes[(node_item.source_id, node_item.graph_revision or node_item.graph_id, node_item.node_id)] = node_item
        return nodes

    def metrics(self):
        return {}


class _ContinuationEngine:
    def __init__(self, target_units):
        self.units_by_owner = {unit.roots[0].node.node_id: unit for unit in target_units}

    def build(self, anchors, *, include_tests, anchor_seed_provenance=()):
        del include_tests, anchor_seed_provenance
        units = tuple(
            sorted(
                (
                    self.units_by_owner[anchor.nodeId]
                    for anchor in anchors
                    if anchor.nodeId in self.units_by_owner
                ),
                key=lambda item: item.unit_id,
            )
        )
        return LocalFlowBuildResult(local_units=units, diagnostics=[], truncated=False)


def _service(repo, target_units: Sequence = (), *, continuation_policy: BoundaryContinuationPolicy | None = None):
    return KnowledgeQueryService(
        object(),
        object(),
        repo,
        flow_engine=_ContinuationEngine(target_units),
        continuation_policy=continuation_policy,
    )


def _sources(*source_ids: str):
    return tuple(QuerySource(source_id, source_id, f"{source_id}:graph", "revision-current", 1, 0, "READY") for source_id in source_ids)


def _identity(boundary):
    from knowledge_service.boundary_resolution import boundary_identity

    return boundary_identity(boundary)


def _abc_fixture():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    req_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "ab")
    prov_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "ab")
    req_bc = neutral_boundary("required-bc", owner_b, "REQUIRED", "bc")
    prov_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "bc")
    return {
        "unit_a": unit("unit-a", owner_a, boundaries=(req_ab,)),
        "unit_b": unit("unit-b", owner_b, boundaries=(prov_b, req_bc)),
        "unit_c": unit("unit-c", owner_c, boundaries=(prov_c,)),
        "provided_b": prov_b,
        "provided_c": prov_c,
    }


def _ambiguous_fixture():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    evidence_a = (node_evidence(owner_a),)
    evidence_b = (node_evidence(owner_b),)
    evidence_c = (node_evidence(owner_c),)
    req = boundary(
        "required",
        owner_a,
        "REQUIRED",
        descriptors=(
            descriptor("required-b-descriptor", "ambiguous.b", "b", evidence_items=evidence_a),
            descriptor("required-c-descriptor", "ambiguous.c", "c", evidence_items=evidence_a),
        ),
        evidence_items=evidence_a,
    )
    prov_b = boundary(
        "provided-b",
        owner_b,
        "PROVIDED",
        descriptors=(descriptor("provided-b-descriptor", "ambiguous.b", "b", evidence_items=evidence_b),),
        evidence_items=evidence_b,
    )
    prov_c = boundary(
        "provided-c",
        owner_c,
        "PROVIDED",
        descriptors=(descriptor("provided-c-descriptor", "ambiguous.c", "c", evidence_items=evidence_c),),
        evidence_items=evidence_c,
    )
    return {"unit_a": unit("unit-a", owner_a, boundaries=(req,)), "provided_b": prov_b, "provided_c": prov_c}


def _branch_fixture():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    req_b = neutral_boundary("required-b", owner_a, "REQUIRED", "b")
    req_c = neutral_boundary("required-c", owner_a, "REQUIRED", "c")
    prov_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    prov_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "c")
    return {
        "unit_a": unit("unit-a", owner_a, boundaries=(req_b, req_c)),
        "unit_b": unit("unit-b", owner_b, boundaries=(prov_b,)),
        "unit_c": unit("unit-c", owner_c, boundaries=(prov_c,)),
        "provided_b": prov_b,
        "provided_c": prov_c,
    }
