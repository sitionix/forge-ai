from __future__ import annotations

from collections import defaultdict
from dataclasses import replace

from test_entrypoint_flow_engine import FakeFlowRepository, anchor, boundary, descriptor, edge, node, node_evidence

from knowledge_service import knowledge_query_service as query_module
from knowledge_service.anchor_expansion_contract import AnchorExpansionBundle, AnchorExpansionEdge, AnchorExpansionNode
from knowledge_service.boundary_resolution import BoundaryCandidateLoadResult, BoundaryResolutionStatus, boundary_identity, descriptor_fingerprint
from knowledge_service.entrypoint_flow_engine import EntrypointFlowEngine
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import (
    AnchorExpansionService,
    CandidatePoolKind,
    CandidateRetrievalResult,
    KnowledgeQueryService,
    QuerySource,
)
from knowledge_service.query_interpretation import QueryRetrievalPlan


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


class StaticSourceScopeResolver:
    def __init__(self, eligible_sources):
        self.eligible_sources = tuple(eligible_sources)

    def resolve(self):
        return list(self.eligible_sources), []


class StaticAnchorSearcher:
    def __init__(self, anchors):
        self.anchors = tuple(anchors)

    def search(self, query, eligible_sources, policy, include_tests=False):
        return self._result()

    def search_plan(self, plan, eligible_sources, policy, include_tests=False, scope=None):
        return self._result()

    def _result(self):
        anchors = list(self.anchors)
        return CandidateRetrievalResult(
            pools={kind: [] for kind in CandidatePoolKind},
            all_candidates=anchors,
            display_candidates=anchors,
            diagnostics=[],
            truncated=False,
        )


class ReorderingFlowEngine:
    def __init__(self, repo: BoundaryResolvingRepository, *, reverse: bool = False) -> None:
        self.inner = EntrypointFlowEngine(repo)
        self.reverse = reverse

    def build(self, anchors, *, max_flows, include_tests, anchor_seed_provenance=()):
        result = self.inner.build(
            anchors,
            max_flows=max_flows,
            include_tests=include_tests,
            anchor_seed_provenance=anchor_seed_provenance,
        )
        if not self.reverse:
            return result
        return replace(result, flows=tuple(reversed(result.flows)), local_units=tuple(reversed(result.local_units)))

    def public_flows(self, flows):
        return self.inner.public_flows(flows)


class ReorderingFamilyAssembler:
    def __init__(self, *, reverse: bool = False, strip_local_unit_ids: bool = False) -> None:
        self.inner = FlowFamilyAssembler()
        self.reverse = reverse
        self.strip_local_unit_ids = strip_local_unit_ids

    def assemble(self, raw_flows, *, supporting_nodes=None, supporting_relations=()):
        result = self.inner.assemble(
            raw_flows,
            supporting_nodes=supporting_nodes,
            supporting_relations=supporting_relations,
        )
        families = result.families
        if self.strip_local_unit_ids:
            families = tuple(replace(family, local_unit_ids=()) for family in families)
        if self.reverse:
            families = tuple(reversed(families))
        return replace(result, families=families)

    def rank(self, families):
        ranked = self.inner.rank(families)
        return tuple(reversed(ranked)) if self.reverse else ranked


def full_query_service(
    repo: BoundaryResolvingRepository,
    anchors,
    eligible_sources,
    *,
    reverse_flow_result: bool = False,
    family_assembler=None,
) -> KnowledgeQueryService:
    service = KnowledgeQueryService(
        StaticSourceScopeResolver(eligible_sources),
        StaticAnchorSearcher(anchors),
        repo,
        flow_engine=ReorderingFlowEngine(repo, reverse=reverse_flow_result),
    )
    if family_assembler is not None:
        service.family_assembler = family_assembler
    return service


def plan_for_code_identifier(identifier: str) -> QueryRetrievalPlan:
    return QueryRetrievalPlan(
        original_query=identifier,
        normalized_query=identifier,
        search_queries=(),
        code_identifiers=(identifier,),
        concepts=(),
        effective_intent="FLOW_EXPLANATION",
        detected_language="und",
        response_language="en",
    )


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


def unit_containing(units, node_id: str):
    return next(unit for unit in units if any(node_item.node_id == node_id for node_item in unit.execution_nodes))


def family_for(families, node_id: str):
    return next(family for family in families if family.entrypoint.node_id == node_id)


def test_query_with_flows_all_plan_rejected_families_return_no_local_units():
    root_a = node("RootA", source="source-a", entrypoint=True)
    root_b = node("RootB", source="source-a", entrypoint=True)
    repo = BoundaryResolvingRepository([root_a, root_b], [], boundaries=())

    result = full_query_service(
        repo,
        [anchor("RootA", source="source-a"), anchor("RootB", source="source-a")],
        sources("source-a"),
    ).query_with_flows(
        KnowledgeQueryRequest(queryText="RootA RootB"),
        plan=plan_for_code_identifier("DoesNotExist.handle"),
    )

    assert result.flows == ()
    assert result.response.flows == []
    assert result.local_units == ()
    assert result.end_to_end_assembly is not None
    assert result.end_to_end_assembly.graphs == ()
    assert repo.calls["find_provided_boundary_candidates"] == 0


def test_query_with_flows_missing_family_provenance_keeps_empty_local_units_fail_closed():
    root_a = node("RootA", source="source-a", entrypoint=True)
    root_b = node("RootB", source="source-a", entrypoint=True)
    repo = BoundaryResolvingRepository([root_a, root_b], [], boundaries=())

    result = full_query_service(
        repo,
        [anchor("RootA", source="source-a"), anchor("RootB", source="source-a")],
        sources("source-a"),
        family_assembler=ReorderingFamilyAssembler(strip_local_unit_ids=True),
    ).query_with_flows(
        KnowledgeQueryRequest(queryText="RootA"),
        plan=plan_for_code_identifier("RootA"),
    )

    assert result.boundary_resolution is not None
    assert result.boundary_resolution.truncation.active_unit_provenance_missing is True
    assert any(item.code == "BOUNDARY_ACTIVE_UNIT_PROVENANCE_MISSING" for item in result.response.diagnostics)
    assert result.response.coverage.truncated is True
    assert result.response.coverage.continuationAvailable is True
    assert result.local_units == ()
    assert result.end_to_end_assembly is not None
    assert result.end_to_end_assembly.truncated is True
    assert result.end_to_end_assembly.all_canonical_unit_refs == ()
    assert repo.calls["find_provided_boundary_candidates"] == 0


def test_query_with_flows_selected_unit_continues_without_rejected_initial_unit():
    selected_root = node("RootA", source="source-a", entrypoint=True)
    rejected_root = node("RootB", source="source-a", entrypoint=True)
    target_owner = node("TargetC", source="source-c", entrypoint=True)
    required_a = boundary(
        "required-a",
        selected_root,
        "REQUIRED",
        descriptors=(descriptor("required-a-key", "neutral.identity", "c", evidence_items=(node_evidence(selected_root),)),),
        evidence_items=(node_evidence(selected_root),),
    )
    required_b = boundary(
        "required-b",
        rejected_root,
        "REQUIRED",
        descriptors=(descriptor("required-b-key", "neutral.other", "ignored", evidence_items=(node_evidence(rejected_root),)),),
        evidence_items=(node_evidence(rejected_root),),
    )
    provided_c = boundary(
        "provided-c",
        target_owner,
        "PROVIDED",
        descriptors=(descriptor("provided-c-key", "neutral.identity", "c", evidence_items=(node_evidence(target_owner),)),),
        evidence_items=(node_evidence(target_owner),),
    )
    repo = BoundaryResolvingRepository([selected_root, rejected_root, target_owner], [], boundaries=[required_a, required_b, provided_c])

    result = full_query_service(
        repo,
        [anchor("RootB", source="source-a"), anchor("RootA", source="source-a")],
        sources("source-a", "source-c"),
    ).query_with_flows(
        KnowledgeQueryRequest(queryText="RootA"),
        plan=plan_for_code_identifier("RootA"),
    )

    assert result.boundary_resolution is not None
    assert [item.status for item in result.boundary_resolution.resolutions] == [BoundaryResolutionStatus.PROVEN]
    assert result.end_to_end_assembly is not None
    assert len(result.end_to_end_assembly.graphs) == 1
    assert {unit_ref.unit_id for graph in result.end_to_end_assembly.graphs for unit_ref in graph.unit_refs} == {
        unit.unit_id for unit in result.local_units
    }
    assert {node_item.node_id for unit in result.local_units for node_item in unit.execution_nodes} == {"RootA", "TargetC"}
    assert "RootB" not in {node_item.node_id for unit in result.local_units for node_item in unit.execution_nodes}


def test_query_with_flows_reversed_internal_order_preserves_local_units():
    def execute(*, reverse: bool):
        selected_root = node("RootA", source="source-a", entrypoint=True)
        rejected_root = node("RootB", source="source-a", entrypoint=True)
        target_owner = node("TargetC", source="source-c", entrypoint=True)
        required_a = boundary(
            "required-a",
            selected_root,
            "REQUIRED",
            descriptors=(descriptor("required-a-key", "neutral.identity", "c", evidence_items=(node_evidence(selected_root),)),),
            evidence_items=(node_evidence(selected_root),),
        )
        required_b = boundary(
            "required-b",
            rejected_root,
            "REQUIRED",
            descriptors=(descriptor("required-b-key", "neutral.other", "ignored", evidence_items=(node_evidence(rejected_root),)),),
            evidence_items=(node_evidence(rejected_root),),
        )
        provided_c = boundary(
            "provided-c",
            target_owner,
            "PROVIDED",
            descriptors=(descriptor("provided-c-key", "neutral.identity", "c", evidence_items=(node_evidence(target_owner),)),),
            evidence_items=(node_evidence(target_owner),),
        )
        repo = BoundaryResolvingRepository([selected_root, rejected_root, target_owner], [], boundaries=[required_a, required_b, provided_c])
        anchors = [anchor("RootA", source="source-a"), anchor("RootB", source="source-a")]
        eligible = sources("source-a", "source-c")
        if reverse:
            anchors = list(reversed(anchors))
            eligible = tuple(reversed(eligible))
        service = full_query_service(
            repo,
            anchors,
            eligible,
            reverse_flow_result=reverse,
            family_assembler=ReorderingFamilyAssembler(reverse=reverse),
        )
        return service.query_with_flows(
            KnowledgeQueryRequest(queryText="RootA"),
            plan=plan_for_code_identifier("RootA"),
        )

    forward = execute(reverse=False)
    reversed_order = execute(reverse=True)

    assert [unit.unit_id for unit in forward.local_units] == [unit.unit_id for unit in reversed_order.local_units]
    assert forward.end_to_end_assembly is not None
    assert reversed_order.end_to_end_assembly is not None
    assert [graph.stable_graph_id for graph in forward.end_to_end_assembly.graphs] == [
        graph.stable_graph_id for graph in reversed_order.end_to_end_assembly.graphs
    ]
    assert [
        tuple(node_item.node_id for node_item in unit.execution_nodes)
        for unit in forward.local_units
    ] == [
        tuple(node_item.node_id for node_item in unit.execution_nodes)
        for unit in reversed_order.local_units
    ]


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


def test_rejected_initial_unit_cannot_resolve_or_materialize_target_unit():
    selected_root = node("RootA", source="source-a", entrypoint=True)
    rejected_root = node("RootB", source="source-a", entrypoint=True)
    target_owner = node("TargetC", source="source-c", entrypoint=True)
    required_b = boundary(
        "required-b",
        rejected_root,
        "REQUIRED",
        descriptors=(descriptor("required-b-key", "neutral.identity", "c", evidence_items=(node_evidence(rejected_root),)),),
        evidence_items=(node_evidence(rejected_root),),
    )
    provided_c = boundary(
        "provided-c",
        target_owner,
        "PROVIDED",
        descriptors=(descriptor("provided-c-key", "neutral.identity", "c", evidence_items=(node_evidence(target_owner),)),),
        evidence_items=(node_evidence(target_owner),),
    )
    repo = BoundaryResolvingRepository([selected_root, rejected_root, target_owner], [], boundaries=[required_b, provided_c])
    families, units = family_inputs(repo, [anchor("RootA", source="source-a"), anchor("RootB", source="source-a")])

    result = service_for(repo)._assemble_generic_boundary_continuations(
        (family_for(families, "RootA"),),
        units,
        sources("source-a", "source-c"),
        include_tests=False,
    )

    assert repo.calls["find_provided_boundary_candidates"] == 0
    assert result.boundary_resolution is not None
    assert result.boundary_resolution.resolutions == ()
    assert {node_item.node_id for unit in result.local_units for node_item in unit.execution_nodes} == {"RootA"}


def test_selected_initial_unit_can_continue_while_rejected_unit_stays_inactive():
    selected_root = node("RootA", source="source-a", entrypoint=True)
    rejected_root = node("RootB", source="source-a", entrypoint=True)
    target_owner = node("TargetC", source="source-c", entrypoint=True)
    required_a = boundary(
        "required-a",
        selected_root,
        "REQUIRED",
        descriptors=(descriptor("required-a-key", "neutral.identity", "c", evidence_items=(node_evidence(selected_root),)),),
        evidence_items=(node_evidence(selected_root),),
    )
    required_b = boundary(
        "required-b",
        rejected_root,
        "REQUIRED",
        descriptors=(descriptor("required-b-key", "neutral.other", "ignored", evidence_items=(node_evidence(rejected_root),)),),
        evidence_items=(node_evidence(rejected_root),),
    )
    provided_c = boundary(
        "provided-c",
        target_owner,
        "PROVIDED",
        descriptors=(descriptor("provided-c-key", "neutral.identity", "c", evidence_items=(node_evidence(target_owner),)),),
        evidence_items=(node_evidence(target_owner),),
    )
    repo = BoundaryResolvingRepository([selected_root, rejected_root, target_owner], [], boundaries=[required_a, required_b, provided_c])
    families, units = family_inputs(repo, [anchor("RootB", source="source-a"), anchor("RootA", source="source-a")])
    selected = (family_for(families, "RootA"),)
    service = service_for(repo)

    forward = service._assemble_generic_boundary_continuations(selected, units, sources("source-a", "source-c"), include_tests=False)
    reversed_order = service._assemble_generic_boundary_continuations(tuple(reversed(selected)), tuple(reversed(units)), sources("source-c", "source-a"), include_tests=False)

    assert forward.boundary_resolution is not None
    assert [item.status for item in forward.boundary_resolution.resolutions] == [BoundaryResolutionStatus.PROVEN]
    assert {node_item.node_id for unit in forward.local_units for node_item in unit.execution_nodes} == {"RootA", "TargetC"}
    assert "RootB" not in {node_item.node_id for unit in forward.local_units for node_item in unit.execution_nodes}
    assert [item.resolution_id for item in forward.boundary_resolution.resolutions] == [
        item.resolution_id for item in reversed_order.boundary_resolution.resolutions
    ]
    assert [unit.unit_id for unit in forward.local_units] == [unit.unit_id for unit in reversed_order.local_units]


def test_shared_family_provenance_activates_only_exact_originating_units():
    root_a = node("RootA", source="source-a", entrypoint=True)
    root_b = node("RootB", source="source-a", entrypoint=True)
    unrelated = node("RootD", source="source-a", entrypoint=True)
    target_c = node("TargetC", source="source-c", entrypoint=True)
    target_e = node("TargetE", source="source-e", entrypoint=True)
    required_b = boundary(
        "required-b",
        root_b,
        "REQUIRED",
        descriptors=(descriptor("required-b-key", "neutral.shared", "c", evidence_items=(node_evidence(root_b),)),),
        evidence_items=(node_evidence(root_b),),
    )
    provided_c = boundary(
        "provided-c",
        target_c,
        "PROVIDED",
        descriptors=(descriptor("provided-c-key", "neutral.shared", "c", evidence_items=(node_evidence(target_c),)),),
        evidence_items=(node_evidence(target_c),),
    )
    required_d = boundary(
        "required-d",
        unrelated,
        "REQUIRED",
        descriptors=(descriptor("required-d-key", "neutral.unrelated", "e", evidence_items=(node_evidence(unrelated),)),),
        evidence_items=(node_evidence(unrelated),),
    )
    provided_e = boundary(
        "provided-e",
        target_e,
        "PROVIDED",
        descriptors=(descriptor("provided-e-key", "neutral.unrelated", "e", evidence_items=(node_evidence(target_e),)),),
        evidence_items=(node_evidence(target_e),),
    )
    repo = BoundaryResolvingRepository([root_a, root_b, unrelated, target_c, target_e], [], boundaries=[required_b, provided_c, required_d, provided_e])
    families, units = family_inputs(
        repo,
        [anchor("RootA", source="source-a"), anchor("RootB", source="source-a"), anchor("RootD", source="source-a")],
    )
    selected = replace(
        family_for(families, "RootA"),
        local_unit_ids=tuple(sorted((unit_containing(units, "RootA").unit_id, unit_containing(units, "RootB").unit_id))),
    )

    result = service_for(repo)._assemble_generic_boundary_continuations(
        (selected,),
        units,
        sources("source-a", "source-c", "source-e"),
        include_tests=False,
    )

    assert result.boundary_resolution is not None
    assert [item.required_boundary.boundary_id for item in result.boundary_resolution.resolutions] == ["required-b"]
    assert {node_item.node_id for unit in result.local_units for node_item in unit.execution_nodes} == {"RootA", "RootB", "TargetC"}


def test_missing_family_local_unit_provenance_fails_closed():
    root = node("RootA", source="source-a", entrypoint=True)
    target = node("TargetB", source="source-b", entrypoint=True)
    required = boundary(
        "required-a",
        root,
        "REQUIRED",
        descriptors=(descriptor("required-key", "neutral.identity", "b", evidence_items=(node_evidence(root),)),),
        evidence_items=(node_evidence(root),),
    )
    provided = boundary(
        "provided-b",
        target,
        "PROVIDED",
        descriptors=(descriptor("provided-key", "neutral.identity", "b", evidence_items=(node_evidence(target),)),),
        evidence_items=(node_evidence(target),),
    )
    repo = BoundaryResolvingRepository([root, target], [], boundaries=[required, provided])
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])
    selected_without_provenance = replace(families[0], local_unit_ids=())

    result = service_for(repo)._assemble_generic_boundary_continuations(
        (selected_without_provenance,),
        units,
        sources("source-a", "source-b"),
        include_tests=False,
    )

    assert repo.calls["find_provided_boundary_candidates"] == 0
    assert result.boundary_resolution is not None
    assert result.boundary_resolution.truncation.active_unit_provenance_missing is True
    assert any(item.code == "BOUNDARY_ACTIVE_UNIT_PROVENANCE_MISSING" for item in result.diagnostics)
    assert {node_item.node_id for unit in result.local_units for node_item in unit.execution_nodes} == set()


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
    assert len(result.boundary_resolution.target_materializations) == 3
    assert result.boundary_resolution.metrics.resolution_cycles_detected >= 1
    assert any(item.code == "BOUNDARY_RESOLUTION_CYCLE_DETECTED" for item in result.boundary_resolution.diagnostics)
    assert not any(item.code == "BOUNDARY_RESOLUTION_LIMIT_REACHED" for item in result.boundary_resolution.diagnostics)


def test_actual_round_limit_uses_limit_diagnostic(monkeypatch):
    root = node("RootA", source="source-a", entrypoint=True)
    repo = BoundaryResolvingRepository([root], [], boundaries=[])
    families, units = family_inputs(repo, [anchor("RootA", source="source-a")])
    monkeypatch.setattr(query_module, "_MAX_BOUNDARY_RESOLUTION_ROUNDS", 0)

    result = service_for(repo)._assemble_generic_boundary_continuations(families, units, sources("source-a"), include_tests=False)

    assert result.boundary_resolution is not None
    assert result.boundary_resolution.truncation.resolver_limit_reached is True
    assert any(item.code == "BOUNDARY_RESOLUTION_LIMIT_REACHED" for item in result.boundary_resolution.diagnostics)
