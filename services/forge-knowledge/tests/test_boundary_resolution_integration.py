from __future__ import annotations

from collections import defaultdict

from test_entrypoint_flow_engine import FakeFlowRepository, anchor, boundary, descriptor, edge, node, node_evidence

from knowledge_service.anchor_expansion_contract import AnchorExpansionBundle, AnchorExpansionEdge, AnchorExpansionNode
from knowledge_service.boundary_resolution import BoundaryCandidateLoadResult, BoundaryResolutionStatus, boundary_identity, descriptor_fingerprint
from knowledge_service.entrypoint_flow_engine import EntrypointFlowEngine
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.knowledge_query_service import AnchorExpansionService, KnowledgeQueryService, QuerySource


class BoundaryResolvingRepository(FakeFlowRepository):
    def find_provided_boundary_candidates(self, required_boundaries, *, eligible_source_ids, include_tests, internal_limits=None):
        self.calls["find_provided_boundary_candidates"] += 1
        eligible = set(eligible_source_ids)
        provided = tuple(
            item
            for item in self.boundaries
            if item.role == "PROVIDED"
            and item.source_id in eligible
            and (include_tests or str(item.flow_domain or "").upper() != "TEST")
            and item.status in {"TRUSTED", "DERIVED"}
        )
        provided_by_fingerprint = defaultdict(set)
        for item in provided:
            for desc in item.descriptors:
                provided_by_fingerprint[descriptor_fingerprint(desc)].add(boundary_identity(item))
        candidates_by_required = {}
        for required in required_boundaries:
            required_fingerprints = {descriptor_fingerprint(item) for item in required.descriptors}
            candidates_by_required[boundary_identity(required)] = tuple(
                sorted(
                    (
                        candidate
                        for candidate in provided
                        if candidate.source_id != required.source_id
                        and required_fingerprints & {descriptor_fingerprint(item) for item in candidate.descriptors}
                    ),
                    key=lambda item: boundary_identity(item),
                )
            )
        return BoundaryCandidateLoadResult(
            candidates_by_required_identity=candidates_by_required,
            provided_boundaries_by_fingerprint={key: frozenset(value) for key, value in provided_by_fingerprint.items()},
            eligible_provided_boundary_count=len(provided),
            provided_candidates_by_source={source: sum(1 for item in provided if item.source_id == source) for source in sorted({item.source_id for item in provided})},
            descriptor_fingerprints_queried=len({descriptor_fingerprint(item) for boundary_item in required_boundaries for item in boundary_item.descriptors}),
            sql_statements=1,
        )


def service_for(repo: BoundaryResolvingRepository) -> KnowledgeQueryService:
    return KnowledgeQueryService(None, None, repo, flow_engine=EntrypointFlowEngine(repo))


class ExpansionStore:
    def __init__(self, bundle: AnchorExpansionBundle) -> None:
        self.bundle = bundle

    def query_anchor_expansion(self, requests):
        return self.bundle


def family_inputs(repo: BoundaryResolvingRepository, anchors):
    result = EntrypointFlowEngine(repo).build(anchors, max_flows=10, include_tests=False)
    assembly = FlowFamilyAssembler().assemble(result.flows)
    return FlowFamilyAssembler().rank(assembly.families), result.local_units


def sources(*source_ids: str):
    return tuple(QuerySource(source_id=item, display_name=item, graph_id="revision-current", graph_revision="revision-current", node_count=1, edge_count=0) for item in source_ids)


def test_proven_boundary_materializes_target_unit_separately_without_cross_source_edge():
    source_root = node("RootA", source="source-a", entrypoint=True)
    source_call = node("CallA", source="source-a")
    target_owner = node("TargetB", source="source-b", entrypoint=True)
    target_child = node("ChildB", source="source-b")
    required = boundary(
        "required-a",
        source_call,
        "REQUIRED",
        descriptors=(descriptor("required-key", "neutral.identity", "alpha", evidence_items=(node_evidence(source_call),)),),
        evidence_items=(node_evidence(source_call),),
    )
    provided = boundary(
        "provided-b",
        target_owner,
        "PROVIDED",
        descriptors=(descriptor("provided-key", "neutral.identity", "alpha", evidence_items=(node_evidence(target_owner),)),),
        evidence_items=(node_evidence(target_owner),),
    )
    repo = BoundaryResolvingRepository(
        [source_root, source_call, target_owner, target_child],
        [edge("a-call", "RootA", "CallA", source="source-a"), edge("b-child", "TargetB", "ChildB", source="source-b")],
        boundaries=[required, provided],
    )
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])
    source_unit_id = units[0].unit_id

    result = service_for(repo)._assemble_generic_boundary_continuations(families, units, sources("source-a", "source-b"), include_tests=False)

    assert result.boundary_resolution is not None
    assert [item.status for item in result.boundary_resolution.resolutions] == [BoundaryResolutionStatus.PROVEN]
    assert result.boundary_resolution.proven_links[0].target_owner.owner_node_id == "TargetB"
    assert {unit.source_id for unit in result.local_units} == {"source-a", "source-b"}
    assert source_unit_id in {unit.unit_id for unit in result.local_units}
    assert all(
        edge_item.source_id == unit.source_id
        for unit in result.local_units
        for edge_item in unit.execution_transitions
    )


def test_ambiguous_and_unresolved_boundaries_do_not_materialize_target_units():
    root = node("RootA", source="source-a", entrypoint=True)
    ambiguous_owner_a = node("AmbiguousB", source="source-b", entrypoint=True)
    ambiguous_owner_b = node("AmbiguousC", source="source-c", entrypoint=True)
    required = boundary(
        "required-a",
        root,
        "REQUIRED",
        descriptors=(
            descriptor("required-one", "neutral.one", "same", evidence_items=(node_evidence(root),)),
            descriptor("required-two", "neutral.two", "same", evidence_items=(node_evidence(root),)),
        ),
        evidence_items=(node_evidence(root),),
    )
    provided_a = boundary(
        "provided-b",
        ambiguous_owner_a,
        "PROVIDED",
        descriptors=(
            descriptor("provided-b-one", "neutral.one", "same", evidence_items=(node_evidence(ambiguous_owner_a),)),
            descriptor("provided-b-two", "neutral.two", "same", evidence_items=(node_evidence(ambiguous_owner_a),)),
        ),
        evidence_items=(node_evidence(ambiguous_owner_a),),
    )
    provided_b = boundary(
        "provided-c",
        ambiguous_owner_b,
        "PROVIDED",
        descriptors=(
            descriptor("provided-c-one", "neutral.one", "same", evidence_items=(node_evidence(ambiguous_owner_b),)),
            descriptor("provided-c-two", "neutral.two", "same", evidence_items=(node_evidence(ambiguous_owner_b),)),
        ),
        evidence_items=(node_evidence(ambiguous_owner_b),),
    )
    repo = BoundaryResolvingRepository([root, ambiguous_owner_a, ambiguous_owner_b], [], boundaries=[required, provided_a, provided_b])
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])

    result = service_for(repo)._assemble_generic_boundary_continuations(families, units, sources("source-a", "source-b", "source-c"), include_tests=False)

    assert result.boundary_resolution is not None
    assert result.boundary_resolution.resolutions[0].status is BoundaryResolutionStatus.AMBIGUOUS
    assert {unit.source_id for unit in result.local_units} == {"source-a"}


def test_failed_target_materialization_preserves_proven_resolution_with_diagnostic():
    root = node("RootA", source="source-a", entrypoint=True)
    missing_owner = node("MissingB", source="source-b", entrypoint=True)
    required = boundary(
        "required-a",
        root,
        "REQUIRED",
        descriptors=(descriptor("required-key", "neutral.identity", "alpha", evidence_items=(node_evidence(root),)),),
        evidence_items=(node_evidence(root),),
    )
    provided = boundary(
        "provided-b",
        missing_owner,
        "PROVIDED",
        descriptors=(descriptor("provided-key", "neutral.identity", "alpha", evidence_items=(node_evidence(missing_owner),)),),
        evidence_items=(node_evidence(missing_owner),),
    )
    repo = BoundaryResolvingRepository([root], [], boundaries=[required, provided])
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])

    result = service_for(repo)._assemble_generic_boundary_continuations(families, units, sources("source-a", "source-b"), include_tests=False)

    assert result.boundary_resolution is not None
    assert result.boundary_resolution.resolutions[0].status is BoundaryResolutionStatus.PROVEN
    assert any(item.code == "BOUNDARY_TARGET_UNIT_NOT_MATERIALIZED" for item in result.boundary_resolution.diagnostics)
    assert {unit.source_id for unit in result.local_units} == {"source-a"}


def test_contextual_provided_owner_uses_structural_anchor_expansion_only():
    root = node("RootA", source="source-a", entrypoint=True)
    type_owner = node("TypeB", source="source-b", kind="TYPE")
    executable_seed = node("SeedB", source="source-b", entrypoint=True)
    required = boundary(
        "required-a",
        root,
        "REQUIRED",
        descriptors=(descriptor("required-key", "neutral.identity", "alpha", evidence_items=(node_evidence(root),)),),
        evidence_items=(node_evidence(root),),
    )
    provided = boundary(
        "provided-b",
        type_owner,
        "PROVIDED",
        descriptors=(descriptor("provided-key", "neutral.identity", "alpha", evidence_items=(node_evidence(type_owner),)),),
        evidence_items=(node_evidence(type_owner),),
    )
    expansion = AnchorExpansionBundle(
        nodes=(
            AnchorExpansionNode("source-b", "revision-current", "revision-current", "TypeB", "source-b:key:TypeB", "TYPE", "TypeB"),
            AnchorExpansionNode("source-b", "revision-current", "revision-current", "SeedB", "source-b:key:SeedB", "CALLABLE", "SeedB"),
        ),
        edges=(
            AnchorExpansionEdge("source-b", "revision-current", "revision-current", "type-seed", "DECLARES", "TypeB", "SeedB"),
        ),
    )
    repo = BoundaryResolvingRepository([root, type_owner, executable_seed], [], boundaries=[required, provided])
    service = KnowledgeQueryService(
        None,
        None,
        repo,
        flow_engine=EntrypointFlowEngine(repo),
        anchor_expander=AnchorExpansionService(ExpansionStore(expansion)),
    )
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])

    result = service._assemble_generic_boundary_continuations(families, units, sources("source-a", "source-b"), include_tests=False)

    assert result.boundary_resolution is not None
    assert result.boundary_resolution.resolutions[0].status is BoundaryResolutionStatus.PROVEN
    assert {unit.source_id for unit in result.local_units} == {"source-a", "source-b"}
    target_unit = next(unit for unit in result.local_units if unit.source_id == "source-b")
    assert {item.node_id for item in target_unit.execution_nodes} == {"SeedB"}
    assert all(item.node_id != "TypeB" for item in target_unit.execution_nodes)


def test_recursive_resolution_discovers_second_target_and_cycle_terminates():
    root_a = node("RootA", source="source-a", entrypoint=True)
    root_b = node("RootB", source="source-b", entrypoint=True)
    root_c = node("RootC", source="source-c", entrypoint=True)
    required_a = boundary(
        "required-a",
        root_a,
        "REQUIRED",
        descriptors=(descriptor("required-a-key", "neutral.toB", "b", evidence_items=(node_evidence(root_a),)),),
        evidence_items=(node_evidence(root_a),),
    )
    provided_b = boundary(
        "provided-b",
        root_b,
        "PROVIDED",
        descriptors=(descriptor("provided-b-key", "neutral.toB", "b", evidence_items=(node_evidence(root_b),)),),
        evidence_items=(node_evidence(root_b),),
    )
    required_b = boundary(
        "required-b",
        root_b,
        "REQUIRED",
        descriptors=(descriptor("required-b-key", "neutral.toC", "c", evidence_items=(node_evidence(root_b),)),),
        evidence_items=(node_evidence(root_b),),
    )
    provided_c = boundary(
        "provided-c",
        root_c,
        "PROVIDED",
        descriptors=(descriptor("provided-c-key", "neutral.toC", "c", evidence_items=(node_evidence(root_c),)),),
        evidence_items=(node_evidence(root_c),),
    )
    required_c_cycle = boundary(
        "required-c-cycle",
        root_c,
        "REQUIRED",
        descriptors=(descriptor("required-c-key", "neutral.toB", "b", evidence_items=(node_evidence(root_c),)),),
        evidence_items=(node_evidence(root_c),),
    )
    repo = BoundaryResolvingRepository([root_a, root_b, root_c], [], boundaries=[required_a, provided_b, required_b, provided_c, required_c_cycle])
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])

    result = service_for(repo)._assemble_generic_boundary_continuations(families, units, sources("source-a", "source-b", "source-c"), include_tests=False)

    assert result.boundary_resolution is not None
    assert {unit.source_id for unit in result.local_units} == {"source-a", "source-b", "source-c"}
    assert len(result.boundary_resolution.proven_links) == 3
    assert result.boundary_resolution.metrics.resolution_cycles_detected >= 1
