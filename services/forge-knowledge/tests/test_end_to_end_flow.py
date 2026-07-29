from __future__ import annotations

from dataclasses import replace

from test_local_flow_unit_engine import boundary, descriptor, edge, node, node_evidence

from knowledge_service.boundary_resolution import (
    BoundaryCandidateEvaluation,
    BoundaryResolution,
    BoundaryResolutionResult,
    BoundaryResolutionStatus,
    BoundaryResolutionTruncationState,
    BoundaryTargetMaterialization,
    BoundaryTargetMaterializationStatus,
    BoundaryTargetSeedIdentity,
    BoundaryTargetSeedRelation,
    ProvenBoundaryLink,
    boundary_identity,
    boundary_owner_identity,
    descriptor_fingerprint,
    evidence_references,
    stable_resolution_id,
)
from knowledge_service.end_to_end_flow import (
    END_TO_END_TRANSITION_KIND,
    EndToEndFlowAssembler,
    EndToEndFlowAssemblyLimits,
)
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode
from knowledge_service.local_flow_unit_engine import LocalFlowCoverage, LocalFlowRoot, LocalFlowRootOrigin, LocalFlowUnit


def unit(
    unit_id: str,
    owner: FlowGraphNode,
    *,
    boundaries=(),
    transitions: tuple[FlowGraphEdge, ...] = (),
    complete: bool = True,
) -> LocalFlowUnit:
    evidence_items = (node_evidence(owner),)
    return LocalFlowUnit(
        unit_id=unit_id,
        source_id=owner.source_id,
        graph_revision=owner.graph_revision or owner.graph_id,
        roots=(LocalFlowRoot(node=owner, origin=LocalFlowRootOrigin.EXPLICIT_GRAPH_FACT, distance_to_nearest_seed=0),),
        anchors=(),
        execution_nodes=(owner,),
        execution_transitions=transitions,
        generic_boundaries=tuple(boundaries),
        topology_boundaries=(),
        supporting_context=(),
        evidence=evidence_items,
        complete=complete,
        coverage=LocalFlowCoverage(
            node_count=1,
            transition_count=len(transitions),
            generic_boundary_count=len(boundaries),
            topology_boundary_count=0,
            anchor_count=0,
            root_count=1,
            max_depth_reached=0,
        ),
        diagnostics=(),
    )


def neutral_boundary(boundary_id: str, owner: FlowGraphNode, role: str, value: str):
    return boundary(
        boundary_id,
        owner,
        role,
        descriptors=(descriptor(f"{boundary_id}-descriptor", "neutral.identity", value, evidence_items=(node_evidence(owner),)),),
        evidence_items=(node_evidence(owner),),
    )


def proven(
    required,
    provided,
    *,
    required_unit_ids: tuple[str, ...],
    target_unit_ids: tuple[str, ...],
    resolution_id: str | None = None,
    status: BoundaryTargetMaterializationStatus = BoundaryTargetMaterializationStatus.MATERIALIZED,
) -> BoundaryResolutionResult:
    resolution_id = resolution_id or stable_resolution_id(required)
    fingerprint = descriptor_fingerprint(required.descriptors[0])
    refs = evidence_references(
        (
            *required.evidence,
            *provided.evidence,
            *required.descriptors[0].evidence,
            *provided.descriptors[0].evidence,
        )
    )
    resolution = BoundaryResolution(
        resolution_id=resolution_id,
        required_boundary=required,
        status=BoundaryResolutionStatus.PROVEN,
        selected_provided_boundary=provided,
        evaluated_candidates=(),
        proving_descriptor_fingerprints=(fingerprint,),
        evidence_references=refs,
        diagnostics=(),
        required_unit_ids=required_unit_ids,
    )
    link = ProvenBoundaryLink(
        resolution_id=resolution_id,
        required_boundary_identity=boundary_identity(required),
        provided_boundary_identity=boundary_identity(provided),
        target_owner=boundary_owner_identity(provided),
        proving_descriptor_fingerprints=(fingerprint,),
        evidence_references=refs,
        required_unit_ids=required_unit_ids,
    )
    seed = BoundaryTargetSeedIdentity(
        source_id=provided.source_id,
        graph_revision=provided.graph_revision or provided.graph_id,
        node_id=provided.owner_node_id,
        stable_key=f"{provided.source_id}:key:{provided.owner_node_id}",
    )
    materialization = BoundaryTargetMaterialization(
        resolution_id=resolution_id,
        selected_provided_boundary_identity=boundary_identity(provided),
        target_owner_identity=boundary_owner_identity(provided),
        target_local_unit_ids=target_unit_ids,
        expanded_target_seed_identities=(seed,),
        owner_to_seed_reasons=(BoundaryTargetSeedRelation(seed_identity=seed, reasons=("GENERIC_BOUNDARY_PROVIDED_OWNER",)),),
        materialization_status=status,
        diagnostics=(),
    )
    return BoundaryResolutionResult(
        resolutions=(resolution,),
        proven_links=(link,),
        ambiguous_links=(),
        unresolved_boundaries=(),
        discovered_provided_owners=(boundary_owner_identity(provided),),
        target_materializations=(materialization,),
    )


def combine_results(*results: BoundaryResolutionResult) -> BoundaryResolutionResult:
    return BoundaryResolutionResult(
        resolutions=tuple(sorted((item for result in results for item in result.resolutions), key=lambda item: item.resolution_id)),
        proven_links=tuple(sorted((item for result in results for item in result.proven_links), key=lambda item: item.resolution_id)),
        ambiguous_links=tuple(sorted((item for result in results for item in result.ambiguous_links), key=lambda item: item.resolution_id)),
        unresolved_boundaries=tuple(sorted(item for result in results for item in result.unresolved_boundaries)),
        discovered_provided_owners=tuple(sorted(item for result in results for item in result.discovered_provided_owners)),
        target_materializations=tuple(sorted((item for result in results for item in result.target_materializations), key=lambda item: item.resolution_id)),
    )


def open_result(required, status: BoundaryResolutionStatus, required_unit_ids: tuple[str, ...], *candidates) -> BoundaryResolutionResult:
    evaluations = tuple(
        BoundaryCandidateEvaluation(
            required_boundary_identity=boundary_identity(required),
            provided_boundary_identity=boundary_identity(candidate),
            required_unit_identity=",".join(required_unit_ids),
            provided_owner_identity=boundary_owner_identity(candidate),
            exact_descriptor_matches=(),
            conflicting_descriptors=(),
            missing_descriptors=(),
            evidence_sufficiency="SUFFICIENT",
            provenance_summary={},
            confidence_summary={},
            candidate_score=0.0,
            proof_eligibility=("UNIQUE_EXACT_DESCRIPTOR",) if status is BoundaryResolutionStatus.AMBIGUOUS else (),
            rejection_reasons=(),
            proving_descriptor_fingerprints=(),
        )
        for candidate in candidates
    )
    resolution = BoundaryResolution(
        resolution_id=stable_resolution_id(required),
        required_boundary=required,
        status=status,
        selected_provided_boundary=None,
        evaluated_candidates=evaluations,
        proving_descriptor_fingerprints=(),
        evidence_references=(),
        diagnostics=(),
        required_unit_ids=required_unit_ids,
    )
    return BoundaryResolutionResult(
        resolutions=(resolution,),
        proven_links=(),
        ambiguous_links=(resolution,) if status is BoundaryResolutionStatus.AMBIGUOUS else (),
        unresolved_boundaries=(boundary_identity(required),) if status is BoundaryResolutionStatus.UNRESOLVED else (),
        discovered_provided_owners=(),
    )


def assemble(units, initial_ids, result, *, limits: EndToEndFlowAssemblyLimits | None = None, resolver_truncated: bool = False):
    return EndToEndFlowAssembler(limits).assemble(
        units,
        query_entry_unit_ids=initial_ids,
        boundary_resolution=result,
        resolver_truncated=resolver_truncated,
    )


def graph_content(result):
    return [
        (
            tuple(ref.unit_id for ref in graph.unit_refs),
            tuple(transition.stable_transition_id for transition in graph.proven_cross_source_transitions),
            tuple((item.required_boundary_identity, item.status.value, item.source_unit_ids) for item in graph.open_boundaries),
            graph.query_entry_unit_ids,
            graph.topology_entry_unit_ids,
        )
        for graph in result.graphs
    ]


def test_exact_mapping_produces_transition_without_mutating_units():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(required,), transitions=(edge("local-a", "A", "A", source="source-a"),))
    unit_b = unit("unit-b", owner_b)
    original_units = (unit_a, unit_b)

    result = assemble(original_units, ("unit-a",), proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",)))

    assert result.graphs[0].coverage.unit_count == 2
    assert result.graphs[0].coverage.proven_cross_source_transition_count == 1
    transition = result.graphs[0].proven_cross_source_transitions[0]
    assert transition.transition_kind == END_TO_END_TRANSITION_KIND
    assert transition.source_unit_id == "unit-a"
    assert transition.target_unit_id == "unit-b"
    assert transition.required_endpoint.local_unit_ids == ("unit-a",)
    assert transition.provided_endpoint.local_unit_ids == ("unit-b",)
    assert original_units == (unit_a, unit_b)


def test_missing_required_target_or_referenced_unit_mapping_fails_closed():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_b = unit("unit-b", owner_b)

    missing_required = proven(required, provided, required_unit_ids=(), target_unit_ids=("unit-b",))
    missing_target = proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=(), status=BoundaryTargetMaterializationStatus.NOT_MATERIALIZED)
    missing_referenced = proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("missing-unit",))

    required_result = assemble((unit_a, unit_b), ("unit-a",), missing_required)
    target_result = assemble((unit_a, unit_b), ("unit-a",), missing_target)
    referenced_result = assemble((unit_a, unit_b), ("unit-a",), missing_referenced)

    assert required_result.graphs[0].proven_cross_source_transitions == ()
    assert target_result.graphs[0].proven_cross_source_transitions == ()
    assert referenced_result.graphs[0].proven_cross_source_transitions == ()
    assert missing_required.resolutions[0].status is BoundaryResolutionStatus.PROVEN
    assert any(item.code == "END_TO_END_REQUIRED_UNIT_MAPPING_MISSING" for item in required_result.diagnostics)
    assert any(item.code == "END_TO_END_TARGET_UNIT_MAPPING_MISSING" for item in target_result.diagnostics)
    assert any(item.code == "END_TO_END_REFERENCED_UNIT_MISSING" for item in referenced_result.diagnostics)


def test_linear_graph_singleton_order_stability_and_completeness():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    owner_d = node("D", source="source-d")
    required_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "b")
    provided_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    required_bc = neutral_boundary("required-bc", owner_b, "REQUIRED", "c")
    provided_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "c")
    unit_a = unit("unit-a", owner_a, boundaries=(required_ab,))
    unit_b = unit("unit-b", owner_b, boundaries=(provided_b, required_bc))
    unit_c = unit("unit-c", owner_c, boundaries=(provided_c,))
    unit_d = unit("unit-d", owner_d)
    resolutions = combine_results(
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-ab"),
        proven(required_bc, provided_c, required_unit_ids=("unit-b",), target_unit_ids=("unit-c",), resolution_id="resolution-bc"),
    )

    forward = assemble((unit_a, unit_b, unit_c, unit_d), ("unit-a",), resolutions)
    reversed_order = assemble((unit_d, unit_c, unit_b, unit_a), ("unit-a",), resolutions)

    assert len(forward.graphs) == 2
    main = next(graph for graph in forward.graphs if graph.coverage.unit_count == 3)
    singleton = next(graph for graph in forward.graphs if graph.coverage.unit_count == 1)
    assert {transition.source_unit_id for transition in main.proven_cross_source_transitions} == {"unit-a", "unit-b"}
    assert singleton.coverage.complete is True
    assert main.query_entry_unit_ids == ("unit-a",)
    assert main.topology_entry_unit_ids == ("unit-a",)
    assert main.coverage.complete is True
    assert graph_content(forward) == graph_content(reversed_order)
    assert [graph.stable_graph_id for graph in forward.graphs] == [graph.stable_graph_id for graph in reversed_order.graphs]


def test_branching_convergence_multi_target_and_duplicate_unit_mappings_are_preserved():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    required_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "b")
    required_ac = neutral_boundary("required-ac", owner_a, "REQUIRED", "c")
    provided_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    provided_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "c")
    unit_a = unit("unit-a", owner_a, boundaries=(required_ab, required_ac))
    unit_b = unit("unit-b", owner_b, boundaries=(provided_b,))
    unit_c = unit("unit-c", owner_c, boundaries=(provided_c,))
    branch_results = combine_results(
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-ab"),
        proven(required_ac, provided_c, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="resolution-ac"),
    )

    branch = assemble((unit_a, unit_b, unit_c), ("unit-a",), branch_results)
    assert sorted((item.source_unit_id, item.target_unit_id) for item in branch.graphs[0].proven_cross_source_transitions) == [
        ("unit-a", "unit-b"),
        ("unit-a", "unit-c"),
    ]

    convergence = assemble(
        (unit_a, unit_b, unit_c),
        ("unit-a", "unit-b"),
        proven(required_ab, provided_c, required_unit_ids=("unit-a", "unit-b"), target_unit_ids=("unit-c",), resolution_id="resolution-converge"),
    )
    assert sorted((item.source_unit_id, item.target_unit_id) for item in convergence.graphs[0].proven_cross_source_transitions) == [
        ("unit-a", "unit-c"),
        ("unit-b", "unit-c"),
    ]

    multi_target = assemble(
        (unit_a, unit_b, unit_c),
        ("unit-a",),
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b", "unit-c"), resolution_id="resolution-multi-target"),
    )
    assert sorted((item.source_unit_id, item.target_unit_id) for item in multi_target.graphs[0].proven_cross_source_transitions) == [
        ("unit-a", "unit-b"),
        ("unit-a", "unit-c"),
    ]


def test_distinct_resolutions_between_same_units_are_not_deduplicated():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required_one = neutral_boundary("required-one", owner_a, "REQUIRED", "one")
    required_two = neutral_boundary("required-two", owner_a, "REQUIRED", "two")
    provided_one = neutral_boundary("provided-one", owner_b, "PROVIDED", "one")
    provided_two = neutral_boundary("provided-two", owner_b, "PROVIDED", "two")
    unit_a = unit("unit-a", owner_a, boundaries=(required_one, required_two))
    unit_b = unit("unit-b", owner_b, boundaries=(provided_one, provided_two))
    resolutions = combine_results(
        proven(required_one, provided_one, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-one"),
        proven(required_two, provided_two, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-two"),
    )

    result = assemble((unit_a, unit_b), ("unit-a",), resolutions)

    assert len(result.graphs[0].proven_cross_source_transitions) == 2
    assert {item.resolution_id for item in result.graphs[0].proven_cross_source_transitions} == {"resolution-one", "resolution-two"}


def test_cycles_are_retained_without_inventing_topology_entries():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "b")
    provided_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    required_ba = neutral_boundary("required-ba", owner_b, "REQUIRED", "a")
    provided_a = neutral_boundary("provided-a", owner_a, "PROVIDED", "a")
    unit_a = unit("unit-a", owner_a, boundaries=(required_ab, provided_a))
    unit_b = unit("unit-b", owner_b, boundaries=(provided_b, required_ba))
    resolutions = combine_results(
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-ab"),
        proven(required_ba, provided_a, required_unit_ids=("unit-b",), target_unit_ids=("unit-a",), resolution_id="resolution-ba"),
    )

    result = assemble((unit_b, unit_a), ("unit-a",), resolutions)

    graph = result.graphs[0]
    assert sorted((item.source_unit_id, item.target_unit_id) for item in graph.proven_cross_source_transitions) == [
        ("unit-a", "unit-b"),
        ("unit-b", "unit-a"),
    ]
    assert graph.topology_entry_unit_ids == ()
    assert graph.query_entry_unit_ids == ("unit-a",)
    assert graph.coverage.cycle_count == 1
    assert any(item.code == "END_TO_END_GRAPH_CYCLE_DETECTED" for item in graph.diagnostics)


def test_ambiguous_unresolved_and_missing_resolution_boundaries_are_open_only():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    ambiguous_required = neutral_boundary("required-ambiguous", owner_a, "REQUIRED", "shared")
    unresolved_required = neutral_boundary("required-unresolved", owner_a, "REQUIRED", "missing")
    omitted_required = neutral_boundary("required-omitted", owner_a, "REQUIRED", "omitted")
    provided_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "shared")
    provided_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "shared")
    unit_a = unit("unit-a", owner_a, boundaries=(ambiguous_required, unresolved_required, omitted_required))
    result = combine_results(
        open_result(ambiguous_required, BoundaryResolutionStatus.AMBIGUOUS, ("unit-a",), provided_b, provided_c),
        open_result(unresolved_required, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",), provided_b),
    )

    assembled = assemble((unit_a,), ("unit-a",), result)

    graph = assembled.graphs[0]
    assert graph.proven_cross_source_transitions == ()
    assert graph.coverage.open_ambiguous_boundary_count == 1
    assert graph.coverage.open_unresolved_boundary_count == 2
    assert graph.coverage.complete is False
    ambiguous = next(item for item in graph.open_boundaries if item.status is BoundaryResolutionStatus.AMBIGUOUS)
    assert tuple(owner.owner_node_id for owner in ambiguous.viable_candidate_owner_identities) == ("B", "C")
    assert any(item.code == "END_TO_END_REQUIRED_BOUNDARY_NOT_RESOLVED" for item in assembled.diagnostics)


def test_candidate_incomplete_resolution_marks_graph_and_assembly_truncated():
    owner_a = node("A", source="source-a")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "x")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unresolved = open_result(required, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",))
    unresolved = replace(
        unresolved,
        truncation=replace(
            unresolved.truncation,
            candidate_sets_truncated=1,
            truncated_required_identities=(boundary_identity(required),),
        ),
    )

    result = assemble((unit_a,), ("unit-a",), unresolved, resolver_truncated=True)

    assert result.truncated is True
    assert result.graphs[0].coverage.truncated is True
    assert result.graphs[0].coverage.complete is False


def test_incomplete_local_unit_makes_graph_incomplete_but_singleton_without_required_can_complete():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    complete_singleton = assemble((unit("unit-a", owner_a),), ("unit-a",), None)
    incomplete_singleton = assemble((unit("unit-b", owner_b, complete=False),), ("unit-b",), None)

    assert complete_singleton.graphs[0].coverage.complete is True
    assert incomplete_singleton.graphs[0].coverage.complete is False


def test_identity_is_stable_and_changes_for_resolution_or_unit_mapping_changes():
    owner_a = node("Same", source="source-a")
    owner_b = node("Same", source="source-b")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_b = unit("unit-b", owner_b)
    unit_c = unit("unit-c", node("Same", source="source-c"))
    base = proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-one")
    changed_resolution = proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-two")
    changed_target = proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="resolution-one")

    first = assemble((unit_b, unit_a), ("unit-a",), base)
    second = assemble((unit_a, unit_b), ("unit-a",), base)
    changed_resolution_result = assemble((unit_a, unit_b), ("unit-a",), changed_resolution)
    changed_target_result = assemble((unit_a, unit_c), ("unit-a",), changed_target)

    assert first.graphs[0].stable_graph_id == second.graphs[0].stable_graph_id
    assert (
        first.graphs[0].proven_cross_source_transitions[0].stable_transition_id
        != changed_resolution_result.graphs[0].proven_cross_source_transitions[0].stable_transition_id
    )
    assert (
        first.graphs[0].proven_cross_source_transitions[0].stable_transition_id
        != changed_target_result.graphs[0].proven_cross_source_transitions[0].stable_transition_id
    )


def test_assembly_limits_fail_closed_without_path_enumeration():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    required_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "b")
    required_ac = neutral_boundary("required-ac", owner_a, "REQUIRED", "c")
    provided_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    provided_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "c")
    unit_a = unit("unit-a", owner_a, boundaries=(required_ab, required_ac))
    unit_b = unit("unit-b", owner_b, boundaries=(provided_b,))
    unit_c = unit("unit-c", owner_c, boundaries=(provided_c,))
    resolutions = combine_results(
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-ab"),
        proven(required_ac, provided_c, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="resolution-ac"),
    )

    unit_limited = assemble((unit_a, unit_b, unit_c), ("unit-a",), resolutions, limits=EndToEndFlowAssemblyLimits(max_units=2))
    transition_limited = assemble((unit_a, unit_b, unit_c), ("unit-a",), resolutions, limits=EndToEndFlowAssemblyLimits(max_proven_transitions=1))
    open_limited = assemble(
        (unit_a,),
        ("unit-a",),
        open_result(required_ab, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",)),
        limits=EndToEndFlowAssemblyLimits(max_open_boundaries=0),
    )
    component_limited = assemble(
        (unit("unit-a", owner_a), unit("unit-b", owner_b)),
        ("unit-a",),
        None,
        limits=EndToEndFlowAssemblyLimits(max_connected_components=1),
    )
    branching_cycle = combine_results(
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-ab"),
        proven(required_ac, provided_c, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="resolution-ac"),
        proven(
            neutral_boundary("required-ca", owner_c, "REQUIRED", "a"),
            neutral_boundary("provided-a", owner_a, "PROVIDED", "a"),
            required_unit_ids=("unit-c",),
            target_unit_ids=("unit-a",),
            resolution_id="resolution-ca",
        ),
    )
    cyclic = assemble(
        (unit_a, unit_b, replace(unit_c, generic_boundaries=(*unit_c.generic_boundaries, neutral_boundary("required-ca", owner_c, "REQUIRED", "a")))),
        ("unit-a",),
        branching_cycle,
    )

    assert unit_limited.truncated is True
    assert transition_limited.truncated is True
    assert open_limited.truncated is True
    assert component_limited.truncated is True
    assert all(
        any(item.code == "END_TO_END_ASSEMBLY_LIMIT_REACHED" for item in result.diagnostics)
        for result in (unit_limited, transition_limited, open_limited, component_limited)
    )
    assert len(cyclic.graphs[0].proven_cross_source_transitions) == 3


def test_component_local_mapping_failure_does_not_contaminate_independent_singleton():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_d = node("D", source="source-d")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_d = unit("unit-d", owner_d)
    missing_target = proven(
        required,
        provided,
        required_unit_ids=("unit-a",),
        target_unit_ids=(),
        status=BoundaryTargetMaterializationStatus.NOT_MATERIALIZED,
    )

    result = assemble((unit_a, unit_d), ("unit-a",), missing_target)

    graph_a = next(graph for graph in result.graphs if tuple(ref.unit_id for ref in graph.unit_refs) == ("unit-a",))
    graph_d = next(graph for graph in result.graphs if tuple(ref.unit_id for ref in graph.unit_refs) == ("unit-d",))
    assert graph_a.coverage.complete is False
    assert graph_a.coverage.missing_unit_mapping_count == 1
    assert graph_d.coverage.complete is True
    assert graph_d.coverage.missing_unit_mapping_count == 0
    assert not any(item.code == "END_TO_END_TARGET_UNIT_MAPPING_MISSING" for item in graph_d.diagnostics)
    assert result.complete is False
    assert result.metrics.missing_target_unit_mapping_count == 1


def test_orphan_with_no_valid_affected_unit_remains_assembly_level_only():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_d = node("D", source="source-d")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_d = unit("unit-d", owner_d)
    orphan = proven(
        required,
        provided,
        required_unit_ids=(),
        target_unit_ids=(),
        status=BoundaryTargetMaterializationStatus.NOT_MATERIALIZED,
    )

    result = assemble((unit_d,), ("unit-d",), orphan)

    assert result.graphs[0].coverage.complete is True
    assert result.graphs[0].diagnostics == ()
    assert result.complete is False
    assert result.orphaned_proven_resolutions == orphan.proven_links
    assert any(item.code == "END_TO_END_TARGET_UNIT_MAPPING_MISSING" for item in result.diagnostics)


def test_shared_open_boundary_is_projected_per_component_and_counted_once():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required = neutral_boundary("required-shared", owner_a, "REQUIRED", "shared")
    unit_a = unit("unit-a", owner_a)
    unit_b = unit("unit-b", owner_b)
    unresolved = open_result(required, BoundaryResolutionStatus.UNRESOLVED, ("unit-b", "unit-a"))

    forward = assemble((unit_a, unit_b), ("unit-a",), unresolved)
    reversed_units = assemble((unit_b, unit_a), ("unit-a",), unresolved)

    assert len(forward.graphs) == 2
    projections = sorted(graph.open_boundaries[0].source_unit_ids for graph in forward.graphs)
    assert projections == [("unit-a",), ("unit-b",)]
    for graph in forward.graphs:
        graph_unit_ids = {ref.unit_id for ref in graph.unit_refs}
        assert set(graph.open_boundaries[0].source_unit_ids) <= graph_unit_ids
    assert forward.metrics.open_unresolved_boundary_count == 1
    assert [graph.stable_graph_id for graph in forward.graphs] == [graph.stable_graph_id for graph in reversed_units.graphs]


def test_unit_limit_preserves_input_and_unassembled_unit_accounting():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    unit_a = unit("unit-a", owner_a)
    unit_b = unit("unit-b", owner_b)
    unit_c = unit("unit-c", owner_c)

    result = assemble(
        (unit_c, unit_b, unit_a),
        ("unit-c",),
        None,
        limits=EndToEndFlowAssemblyLimits(max_units=2),
    )

    assert result.input_local_unit_ids == ("unit-a", "unit-b", "unit-c")
    assert result.retained_canonical_unit_ids == ("unit-a", "unit-b")
    assert result.unassembled_local_unit_ids == ("unit-c",)
    assert result.truncated is True
    assert any(
        item.code == "END_TO_END_ASSEMBLY_LIMIT_REACHED" and item.metadata.get("omittedQueryEntryUnitIds") == ("unit-c",)
        for item in result.diagnostics
    )


def test_transition_limit_accounts_for_every_overflowing_proven_link_without_partial_assembly():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    owner_d = node("D", source="source-d")
    required_ab = neutral_boundary("required-ab", owner_a, "REQUIRED", "b")
    required_ac = neutral_boundary("required-ac", owner_a, "REQUIRED", "c")
    required_ad = neutral_boundary("required-ad", owner_a, "REQUIRED", "d")
    provided_b = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    provided_c = neutral_boundary("provided-c", owner_c, "PROVIDED", "c")
    provided_d = neutral_boundary("provided-d", owner_d, "PROVIDED", "d")
    unit_a = unit("unit-a", owner_a, boundaries=(required_ab, required_ac, required_ad))
    unit_b = unit("unit-b", owner_b)
    unit_c = unit("unit-c", owner_c)
    unit_d = unit("unit-d", owner_d)
    resolutions = combine_results(
        proven(required_ab, provided_b, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",), resolution_id="resolution-1"),
        proven(required_ac, provided_c, required_unit_ids=("unit-a",), target_unit_ids=("unit-c",), resolution_id="resolution-2"),
        proven(required_ad, provided_d, required_unit_ids=("unit-a",), target_unit_ids=("unit-d",), resolution_id="resolution-3"),
    )

    result = assemble((unit_a, unit_b, unit_c, unit_d), ("unit-a",), resolutions, limits=EndToEndFlowAssemblyLimits(max_proven_transitions=1))

    assert result.metrics.input_proven_link_count == 3
    assert result.metrics.assembled_proven_link_count == 1
    assert result.metrics.unassembled_proven_link_count == 2
    assert len(result.graphs[0].proven_cross_source_transitions) == 1
    assert [item.reason for item in result.unassembled_proven_links] == ["TRANSITION_LIMIT_REACHED", "TRANSITION_LIMIT_REACHED"]
    assert {item.link.resolution_id for item in result.unassembled_proven_links} == {"resolution-2", "resolution-3"}


def test_component_limit_records_omitted_component_units_without_returning_graphs():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    unit_a = unit("unit-a", owner_a)
    unit_b = unit("unit-b", owner_b)

    result = assemble((unit_b, unit_a), ("unit-a",), None, limits=EndToEndFlowAssemblyLimits(max_connected_components=1))

    assert len(result.graphs) == 1
    assert tuple(ref.unit_id for ref in result.graphs[0].unit_refs) == ("unit-a",)
    assert result.unassembled_local_unit_ids == ("unit-b",)
    assert result.metrics.discovered_component_count == 2
    assert result.metrics.returned_component_count == 1
    assert result.metrics.omitted_component_count == 1


def test_canonical_record_mismatch_blocks_link_without_affecting_unrelated_component():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    owner_d = node("D", source="source-d")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    other_provided = neutral_boundary("provided-c", owner_c, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_b = unit("unit-b", owner_b)
    unit_d = unit("unit-d", owner_d)
    result = proven(required, provided, required_unit_ids=("unit-a",), target_unit_ids=("unit-b",))
    inconsistent = replace(
        result,
        resolutions=(replace(result.resolutions[0], selected_provided_boundary=other_provided),),
    )

    assembled = assemble((unit_a, unit_b, unit_d), ("unit-a",), inconsistent)

    assert all(not graph.proven_cross_source_transitions for graph in assembled.graphs)
    graph_d = next(graph for graph in assembled.graphs if tuple(ref.unit_id for ref in graph.unit_refs) == ("unit-d",))
    assert graph_d.coverage.complete is True
    assert not graph_d.diagnostics
    assert assembled.unassembled_proven_links[0].reason == "CANONICAL_RECORD_INCONSISTENT"
    assert any(item.code == "END_TO_END_RESOLUTION_IDENTITY_MISMATCH" for item in assembled.diagnostics)
    assert result.resolutions[0].selected_provided_boundary == provided


def test_partial_materialization_assembles_only_explicit_targets_and_remains_incomplete():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "target")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "target")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_b = unit("unit-b", owner_b)
    unit_c = unit("unit-c", owner_c)

    partial = assemble(
        (unit_a, unit_b, unit_c),
        ("unit-a",),
        proven(
            required,
            provided,
            required_unit_ids=("unit-a",),
            target_unit_ids=("unit-b", "unit-c"),
            status=BoundaryTargetMaterializationStatus.PARTIAL,
        ),
    )
    empty_partial = assemble(
        (unit_a, unit_b, unit_c),
        ("unit-a",),
        proven(
            required,
            provided,
            required_unit_ids=("unit-a",),
            target_unit_ids=(),
            status=BoundaryTargetMaterializationStatus.PARTIAL,
        ),
    )

    assert sorted((item.source_unit_id, item.target_unit_id) for item in partial.graphs[0].proven_cross_source_transitions) == [
        ("unit-a", "unit-b"),
        ("unit-a", "unit-c"),
    ]
    assert partial.complete is False
    assert partial.graphs[0].coverage.complete is False
    assert partial.truncated is True
    assert empty_partial.graphs[0].proven_cross_source_transitions == ()
    assert empty_partial.unassembled_proven_links[0].reason == "CANONICAL_RECORD_INCONSISTENT"


def test_resolver_truncation_is_component_scoped():
    owner_a = node("A", source="source-a")
    owner_d = node("D", source="source-d")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "missing")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_d = unit("unit-d", owner_d)
    unresolved = replace(
        open_result(required, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",)),
        truncation=BoundaryResolutionTruncationState(
            candidate_sets_truncated=1,
            truncated_required_identities=(boundary_identity(required),),
        ),
    )

    result = assemble((unit_d, unit_a), ("unit-a",), unresolved, resolver_truncated=True)

    graph_a = next(graph for graph in result.graphs if tuple(ref.unit_id for ref in graph.unit_refs) == ("unit-a",))
    graph_d = next(graph for graph in result.graphs if tuple(ref.unit_id for ref in graph.unit_refs) == ("unit-d",))
    assert graph_a.coverage.truncated is True
    assert graph_a.coverage.complete is False
    assert graph_d.coverage.truncated is False
    assert graph_d.coverage.complete is True
    assert result.truncated is True
    assert result.complete is False


def test_unscoped_resolver_issue_is_assembly_level_only():
    owner_d = node("D", source="source-d")
    result = assemble(
        (unit("unit-d", owner_d),),
        ("unit-d",),
        BoundaryResolutionResult(
            resolutions=(),
            proven_links=(),
            ambiguous_links=(),
            unresolved_boundaries=(),
            discovered_provided_owners=(),
            truncation=BoundaryResolutionTruncationState(resolver_limit_reached=True),
        ),
        resolver_truncated=True,
    )

    assert result.truncated is True
    assert result.complete is False
    assert result.graphs[0].coverage.truncated is False
    assert result.graphs[0].coverage.complete is True
    assert not result.graphs[0].diagnostics


def test_duplicate_non_proven_resolution_falls_back_to_one_missing_resolution_open_boundary():
    owner_a = node("A", source="source-a")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "missing")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unresolved = open_result(required, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",))
    duplicate = replace(unresolved, resolutions=(unresolved.resolutions[0], unresolved.resolutions[0]))

    result = assemble((unit_a,), ("unit-a",), duplicate)

    graph = result.graphs[0]
    assert len(graph.open_boundaries) == 1
    assert graph.open_boundaries[0].diagnostics == ("END_TO_END_REQUIRED_BOUNDARY_NOT_RESOLVED",)
    assert graph.coverage.open_unresolved_boundary_count == 1
    assert any(item.code == "END_TO_END_DUPLICATE_CANONICAL_RECORD" for item in result.diagnostics)


def test_open_boundary_limit_counts_all_discovered_and_scopes_omissions():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    owner_c = node("C", source="source-c")
    owner_d = node("D", source="source-d")
    required_a = neutral_boundary("required-a", owner_a, "REQUIRED", "a")
    required_b = neutral_boundary("required-b", owner_b, "REQUIRED", "b")
    required_c = neutral_boundary("required-c", owner_c, "REQUIRED", "c")
    unit_a = unit("unit-a", owner_a, boundaries=(required_a,))
    unit_b = unit("unit-b", owner_b, boundaries=(required_b,))
    unit_c = unit("unit-c", owner_c, boundaries=(required_c,))
    unit_d = unit("unit-d", owner_d)
    result = assemble(
        (unit_d, unit_c, unit_b, unit_a),
        ("unit-a",),
        combine_results(
            open_result(required_a, BoundaryResolutionStatus.UNRESOLVED, ("unit-a",)),
            open_result(required_b, BoundaryResolutionStatus.UNRESOLVED, ("unit-b",)),
            open_result(required_c, BoundaryResolutionStatus.UNRESOLVED, ("unit-c",)),
        ),
        limits=EndToEndFlowAssemblyLimits(max_open_boundaries=1),
    )

    assert result.metrics.discovered_open_boundary_count == 3
    assert result.metrics.retained_open_boundary_count == 1
    assert result.metrics.omitted_open_boundary_count == 2
    assert result.metrics.open_unresolved_boundary_count == 3
    assert sum(len(graph.open_boundaries) for graph in result.graphs) == 1
    graph_d = next(graph for graph in result.graphs if tuple(ref.unit_id for ref in graph.unit_refs) == ("unit-d",))
    assert graph_d.coverage.complete is True
    omitted_graphs = [
        graph
        for graph in result.graphs
        if tuple(ref.unit_id for ref in graph.unit_refs) in {("unit-b",), ("unit-c",)}
    ]
    assert all(graph.coverage.truncated is True and graph.coverage.complete is False for graph in omitted_graphs)
    assert any(
        item.code == "END_TO_END_ASSEMBLY_LIMIT_REACHED" and item.metadata.get("limitKind") == "open boundary"
        for item in result.diagnostics
    )


def test_partial_materialization_consistency_rejects_overlapping_retained_and_omitted_targets():
    owner_a = node("A", source="source-a")
    owner_b = node("B", source="source-b")
    required = neutral_boundary("required-a", owner_a, "REQUIRED", "b")
    provided = neutral_boundary("provided-b", owner_b, "PROVIDED", "b")
    unit_a = unit("unit-a", owner_a, boundaries=(required,))
    unit_b = unit("unit-b", owner_b)
    result = proven(
        required,
        provided,
        required_unit_ids=("unit-a",),
        target_unit_ids=("unit-b",),
        status=BoundaryTargetMaterializationStatus.PARTIAL,
    )
    malformed = replace(
        result,
        target_materializations=(replace(result.target_materializations[0], omitted_target_local_unit_ids=("unit-b",)),),
    )

    assembled = assemble((unit_a, unit_b), ("unit-a",), malformed)

    assert all(not graph.proven_cross_source_transitions for graph in assembled.graphs)
    assert assembled.unassembled_proven_links[0].reason == "CANONICAL_RECORD_INCONSISTENT"
    assert any(item.code == "END_TO_END_TARGET_MATERIALIZATION_MISMATCH" for item in assembled.diagnostics)
