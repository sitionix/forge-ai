from __future__ import annotations

from dataclasses import replace

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.graph_relation_semantics import (
    EXECUTION_CONTINUATION,
    FAMILY_TRAVERSAL,
    SUPPORTING_RELATION,
    GraphRelationSemantics,
)


REVISION = "graph-current"


def n(
    node_id: str,
    *,
    source: str = "source-a",
    symbol: str | None = None,
    entrypoint: bool = False,
    execution_role: str | None = "EXECUTABLE",
) -> FlowGraphNode:
    label = symbol or node_id
    return FlowGraphNode(
        source_id=source,
        graph_id=f"{source}:{REVISION}",
        graph_revision=f"{source}:{REVISION}",
        node_id=node_id,
        stable_key=f"{source}:{node_id}",
        node_kind="CALLABLE",
        label=label,
        qualified_name=label,
        relative_path=f"src/{source}/{node_id}.java",
        line_start=1,
        line_end=3,
        entrypoint=entrypoint,
        execution_role=execution_role,
    )


def e(
    edge_id: str,
    source_node: FlowGraphNode,
    target_node: FlowGraphNode | None,
    *,
    edge_type: str = "CALLS",
    source: str | None = None,
    metadata: dict[str, object] | None = None,
) -> FlowGraphEdge:
    edge_source = source or source_node.source_id
    return FlowGraphEdge(
        source_id=edge_source,
        graph_id=f"{edge_source}:{REVISION}",
        graph_revision=f"{edge_source}:{REVISION}",
        edge_id=edge_id,
        edge_type=edge_type,
        from_node_id=source_node.node_id,
        to_node_id=target_node.node_id if target_node is not None else None,
        resolution_status="RESOLVED" if target_node is not None else "UNRESOLVED",
        to_source_id=target_node.source_id if target_node is not None and target_node.source_id != edge_source else None,
        to_graph_id=f"{target_node.source_id}:{REVISION}" if target_node is not None and target_node.source_id != edge_source else None,
        to_graph_revision=f"{target_node.source_id}:{REVISION}" if target_node is not None and target_node.source_id != edge_source else None,
        unresolved_target={"name": "missing"} if target_node is None else None,
        metadata=metadata,
    )


def ev(evidence_id: str, owner: FlowGraphNode | FlowGraphEdge, text: str) -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id=owner.source_id,
        graph_id=f"{owner.source_id}:{REVISION}",
        graph_revision=f"{owner.source_id}:{REVISION}",
        evidence_id=evidence_id,
        node_id=owner.node_id if isinstance(owner, FlowGraphNode) else None,
        edge_id=owner.edge_id if isinstance(owner, FlowGraphEdge) else None,
        relative_path=f"src/{owner.source_id}/Evidence.java",
        line_start=1,
        line_end=1,
        text=text,
    )


def f(root: FlowGraphNode, nodes: tuple[FlowGraphNode, ...], edges: tuple[FlowGraphEdge, ...], *, score: float = 1.0) -> EntrypointFlow:
    return EntrypointFlow(
        key=EntrypointFlowKey(root.source_id, root.graph_revision or root.graph_id, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, score, ("TEST",), 0),),
        nodes=nodes,
        transitions=edges,
        boundary_transitions=(),
        evidence=(),
        complete=True,
        coverage=EntrypointFlowCoverage(len(nodes), len(edges), 0, 1, len(nodes)),
        diagnostics=(),
        relevance_score=score,
    )


def assembler(extra_edges: dict[str, tuple[str, ...]] | None = None) -> FlowFamilyAssembler:
    semantics = {
        "CALLS": (EXECUTION_CONTINUATION, FAMILY_TRAVERSAL),
        "OVERRIDES": (SUPPORTING_RELATION,),
        "IMPLEMENTS": (SUPPORTING_RELATION,),
        **(extra_edges or {}),
    }
    return FlowFamilyAssembler(GraphRelationSemantics(semantics))


def test_nested_executable_entrypoint_subsumes_nested_root_and_keeps_one_family():
    root_a = n("root-a", symbol="RootA.handle", entrypoint=True)
    root_b = n("root-b", symbol="RootB.handle", entrypoint=True)
    worker = n("worker", symbol="Worker.run")
    a_to_b = e("a-to-b", root_a, root_b)
    b_to_worker = e("b-to-worker", root_b, worker)

    result = assembler().assemble(
        (
            f(root_a, (root_a, root_b, worker), (a_to_b, b_to_worker)),
            f(root_b, (root_b, worker), (b_to_worker,)),
        )
    )

    assert len(result.families) == 1
    family = result.families[0]
    assert family.entrypoint == root_a
    assert [node.qualified_name for node in family.nested_entrypoints] == ["RootB.handle"]
    assert family.subordinate_entrypoint_count == 1
    assert {edge.edge_id for edge in family.transitions} == {"a-to-b", "b-to-worker"}


def test_same_root_raw_flows_are_merged_without_losing_branches_or_evidence():
    root = n("root", symbol="Root.handle", entrypoint=True)
    branch_a = n("branch-a", symbol="BranchA.run")
    branch_b = n("branch-b", symbol="BranchB.run")
    edge_a = e("root-to-a", root, branch_a)
    edge_b = e("root-to-b", root, branch_b)
    flow_a = replace(
        f(root, (root, branch_a), (edge_a,), score=0.7),
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 0.7, ("ANCHOR_A",), 0),),
        evidence=(ev("ev-a", edge_a, "branch a evidence"),),
    )
    flow_b = replace(
        f(root, (root, branch_b), (edge_b,), score=0.9),
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 0.9, ("ANCHOR_B",), 1),),
        evidence=(ev("ev-b", edge_b, "branch b evidence"),),
    )

    result = assembler().assemble((flow_a, flow_b))

    assert result.raw_candidate_flow_count == 2
    assert len(result.families) == 1
    family = result.families[0]
    assert {node.qualified_name for node in family.nodes} == {"Root.handle", "BranchA.run", "BranchB.run"}
    assert {edge.edge_id for edge in family.transitions} == {"root-to-a", "root-to-b"}
    assert {item.evidence_id for item in family.evidence} == {"ev-a", "ev-b"}
    assert family.relevance_score == 0.9
    assert set(family.anchors[0].match_reasons) == {"ANCHOR_A", "ANCHOR_B"}


def test_inferred_roots_are_scoped_to_their_own_reachability_component():
    explicit = n("explicit", symbol="Explicit.handle", entrypoint=True)
    worker = n("worker", symbol="Worker.run")
    inferred = n("inferred", symbol="ScheduledJob.run", entrypoint=True, execution_role="INFERRED_TOPOLOGY_ROOT")
    explicit_edge = e("explicit-to-worker", explicit, worker)
    explicit_flow = f(explicit, (explicit, worker), (explicit_edge,))
    inferred_flow = replace(
        f(inferred, (inferred,), (), score=0.8),
        origin=EntrypointFlowOrigin.INFERRED_ROOT,
    )

    result = assembler().assemble((explicit_flow, inferred_flow))

    assert [family.entrypoint.qualified_name for family in result.families] == ["Explicit.handle", "ScheduledJob.run"]


def test_inferred_root_subordinate_to_explicit_root_is_not_independent():
    explicit = n("explicit", symbol="Explicit.handle", entrypoint=True)
    inferred = n("inferred", symbol="InferredContinuation.run", entrypoint=True, execution_role="INFERRED_TOPOLOGY_ROOT")
    edge = e("explicit-to-inferred", explicit, inferred)
    explicit_flow = f(explicit, (explicit, inferred), (edge,))
    inferred_flow = replace(
        f(inferred, (inferred,), (), score=0.8),
        origin=EntrypointFlowOrigin.INFERRED_ROOT,
    )

    result = assembler().assemble((explicit_flow, inferred_flow))

    assert len(result.families) == 1
    assert result.families[0].entrypoint.qualified_name == "Explicit.handle"


def test_separate_top_level_entrypoints_with_shared_downstream_remain_two_families():
    root_a = n("root-a", symbol="RootA.handle", entrypoint=True)
    root_b = n("root-b", symbol="RootB.handle", entrypoint=True)
    shared = n("shared", symbol="SharedWorker.run")
    a_to_shared = e("a-to-shared", root_a, shared)
    b_to_shared = e("b-to-shared", root_b, shared)

    result = assembler().assemble(
        (
            f(root_a, (root_a, shared), (a_to_shared,), score=1.0),
            f(root_b, (root_b, shared), (b_to_shared,), score=0.9),
        )
    )

    assert len(result.families) == 2
    assert [family.entrypoint.qualified_name for family in result.families] == ["RootA.handle", "RootB.handle"]
    assert all(family.subordinate_entrypoint_count == 0 for family in result.families)


def test_mutual_root_cycle_preserves_roots_and_emits_diagnostic():
    root_a = n("root-a", symbol="RootA.handle", entrypoint=True)
    root_b = n("root-b", symbol="RootB.handle", entrypoint=True)
    a_to_b = e("a-to-b", root_a, root_b)
    b_to_a = e("b-to-a", root_b, root_a)

    result = assembler().assemble(
        (
            f(root_a, (root_a, root_b), (a_to_b, b_to_a), score=1.0),
            f(root_b, (root_b, root_a), (b_to_a, a_to_b), score=0.9),
        )
    )

    assert len(result.families) == 2
    assert [diagnostic.code for diagnostic in result.diagnostics] == ["FLOW_FAMILY_ROOT_CYCLE"]
    assert all(family.coverage.cycle_detected for family in result.families)


def test_declaration_plus_implementation_keeps_declaration_as_supporting_context_only():
    declaration = n(
        "contract",
        symbol="ContractRoot.declare",
        entrypoint=True,
        execution_role="CONTRACT_DECLARATION",
    )
    implementation = n("implementation", symbol="Implementation.run", entrypoint=True)
    worker = n("worker", symbol="Worker.run")
    impl_to_contract = e("impl-overrides-contract", implementation, declaration, edge_type="OVERRIDES")
    impl_to_worker = e("impl-to-worker", implementation, worker)

    result = assembler().assemble(
        (
            f(declaration, (declaration,), (), score=1.0),
            f(implementation, (implementation, worker), (impl_to_worker,), score=0.8),
        ),
        supporting_nodes={
            (declaration.source_id, declaration.graph_revision or declaration.graph_id, declaration.node_id): declaration,
            (implementation.source_id, implementation.graph_revision or implementation.graph_id, implementation.node_id): implementation,
        },
        supporting_relations=(impl_to_contract,),
    )

    assert len(result.families) == 1
    family = result.families[0]
    assert family.entrypoint == implementation
    assert {node.qualified_name for node in family.nodes} == {"ContractRoot.declare", "Implementation.run", "Worker.run"}
    assert [edge.edge_id for edge in family.supporting_transitions] == ["impl-overrides-contract"]


def test_generated_client_inside_family_is_not_a_separate_answer_root():
    caller = n("caller", symbol="Caller.handle", entrypoint=True)
    generated_client = n(
        "generated-client",
        symbol="GeneratedClient.operation",
        entrypoint=True,
        execution_role="CLIENT_OPERATION",
    )
    target = n("target", source="source-b", symbol="Target.handle", entrypoint=True)
    caller_to_client = e("caller-to-client", caller, generated_client)
    client_to_target = e(
        "client-to-target",
        generated_client,
        target,
        metadata={"transportConnector": True, "connectorKind": "HTTP", "httpMethod": "POST", "routeTemplate": "/items"},
    )

    result = assembler().assemble(
        (
            f(caller, (caller, generated_client, target), (caller_to_client, client_to_target), score=1.0),
            f(generated_client, (generated_client, target), (client_to_target,), score=0.95),
            f(target, (target,), (), score=0.9),
        )
    )

    assert len(result.families) == 1
    family = result.families[0]
    assert family.entrypoint == caller
    assert "GeneratedClient.operation" in {node.qualified_name for node in family.nodes}
    assert [key.entrypoint_node_id for key in family.raw_flow_keys] == ["caller", "generated-client", "target"]


def test_missing_connector_keeps_caller_and_target_as_separate_families():
    caller = n("caller", symbol="Caller.handle", entrypoint=True)
    target = n("target", source="source-b", symbol="Target.handle", entrypoint=True)
    unresolved_boundary = replace(e("caller-boundary", caller, None), resolution_status="UNRESOLVED")
    caller_flow = replace(
        f(caller, (caller,), ()),
        boundary_transitions=(unresolved_boundary,),
        coverage=EntrypointFlowCoverage(1, 0, 1, 1, 1),
    )

    result = assembler().assemble((caller_flow, f(target, (target,), (), score=0.9)))

    assert len(result.families) == 2
    assert [family.entrypoint.qualified_name for family in result.families] == ["Caller.handle", "Target.handle"]
    assert result.families[0].boundary_transitions == (unresolved_boundary,)


def test_different_identical_symbols_across_sources_preserve_source_identity():
    root_a = n("root", source="source-a", symbol="SiteController.createSite", entrypoint=True)
    root_b = n("root", source="source-b", symbol="SiteController.createSite", entrypoint=True)
    cross_source = e("a-to-b", root_a, root_b)

    result = assembler().assemble(
        (
            f(root_a, (root_a, root_b), (cross_source,), score=1.0),
            f(root_b, (root_b,), (), score=0.9),
        )
    )

    assert len(result.families) == 1
    family = result.families[0]
    assert family.entrypoint.source_id == "source-a"
    assert [(node.source_id, node.qualified_name) for node in family.nodes] == [
        ("source-a", "SiteController.createSite"),
        ("source-b", "SiteController.createSite"),
    ]


def test_assembler_uses_policy_semantics_for_non_http_execution_relation():
    root_a = n("root-a", symbol="RootA.handle", entrypoint=True)
    root_b = n("root-b", symbol="RootB.handle", entrypoint=True)
    generic_edge = e("a-to-b", root_a, root_b, edge_type="ASYNC_CONTINUES")

    result = assembler({"ASYNC_CONTINUES": (EXECUTION_CONTINUATION, FAMILY_TRAVERSAL)}).assemble(
        (
            f(root_a, (root_a, root_b), (generic_edge,), score=1.0),
            f(root_b, (root_b,), (), score=0.9),
        )
    )

    assert len(result.families) == 1
    assert result.families[0].entrypoint == root_a


def test_owner_scoped_evidence_is_set_union_by_identity_not_text_content():
    root_a = n("root-a", symbol="RootA.handle", entrypoint=True)
    root_b = n("root-b", symbol="RootB.handle", entrypoint=True)
    shared_text = "same excerpt"
    a_to_b = e("a-to-b", root_a, root_b)
    b_to_a = e("b-to-a", root_b, root_a)
    flow_a = replace(
        f(root_a, (root_a, root_b), (a_to_b,)),
        evidence=(ev("same-id", a_to_b, shared_text), ev("same-id", root_a, shared_text)),
    )
    flow_b = replace(
        f(root_b, (root_b, root_a), (b_to_a,)),
        evidence=(ev("same-id", b_to_a, shared_text),),
    )

    result = assembler().assemble((flow_a, flow_b))

    evidence_owners = {
        (item.evidence_id, item.node_id, item.edge_id)
        for family in result.families
        for item in family.evidence
    }
    assert evidence_owners == {
        ("same-id", "root-a", None),
        ("same-id", None, "a-to-b"),
        ("same-id", None, "b-to-a"),
    }
