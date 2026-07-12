from __future__ import annotations

from dataclasses import replace

from knowledge_service.entrypoint_flow_engine import EntrypointFlowEngine, EntrypointFlowOrigin
from knowledge_service.flow_graph_contract import FlowGraphBundle, FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode
from knowledge_service.knowledge_query_service import KnowledgeQueryPolicy
from knowledge_service.file_classification import FileClassifier
from knowledge_service.knowledge_defaults import load_knowledge_defaults


SOURCE = "source-neutral"
REVISION = "revision-current"


def node(node_id: str, *, entrypoint: bool = False, domain: str = "CODE") -> FlowGraphNode:
    return FlowGraphNode(
        source_id=SOURCE, graph_id=REVISION, graph_revision=REVISION, node_id=node_id,
        stable_key=f"key:{node_id}", node_kind="CALLABLE", label=node_id,
        relative_path=f"src/{node_id}.txt", line_start=1, line_end=2,
        entrypoint=entrypoint, flow_domain=domain,
    )


def edge(edge_id: str, source: str, target: str | None, *, status: str = "RESOLVED", domain: str = "CODE") -> FlowGraphEdge:
    return FlowGraphEdge(
        source_id=SOURCE, graph_id=REVISION, graph_revision=REVISION, edge_id=edge_id,
        edge_type="CALLS", from_node_id=source, to_node_id=target,
        resolution_status=status, external=status == "EXTERNAL_TARGET",
        unresolved_target={"name": "Omega"} if target is None else None,
        evidence_ids=(f"ev-{edge_id}",), flow_domain=domain,
    )


def evidence(item: FlowGraphEdge) -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id=SOURCE, graph_id=REVISION, graph_revision=REVISION,
        evidence_id=f"ev-{item.edge_id}", node_id=None, edge_id=item.edge_id,
        relative_path="src/calls.txt", line_start=10, line_end=10, text="call site",
    )


def anchor(node_id: str, score: float = 0.9, *, domain: str = "CODE") -> KnowledgeQueryMatchedNode:
    return KnowledgeQueryMatchedNode(
        sourceId=SOURCE, nodeId=node_id, stableKey=f"key:{node_id}", nodeKind="CALLABLE",
        label=node_id, score=score, matchReasons=["GRAPH_MATCH"], graphId=REVISION,
        graphRevision=REVISION, flowDomain=domain,
    )


def build(nodes, edges, anchors, *, max_flows=10, include_tests=False, policy=None):
    bundle = FlowGraphBundle(tuple(nodes), tuple(edges), tuple(evidence(item) for item in edges))
    return EntrypointFlowEngine().build(
        bundle, anchors, policy or KnowledgeQueryPolicy(), max_flows=max_flows, include_tests=include_tests,
    )


def ids(flow):
    return {item.node_id for item in flow.nodes}


def transitions(flow):
    return {(item.from_node_id, item.to_node_id) for item in flow.transitions}


def test_one_entrypoint_keeps_all_sibling_calls_in_one_flow():
    nodes = [node("Alpha", entrypoint=True), node("Beta"), node("Gamma"), node("Delta")]
    edges = [edge("e1", "Alpha", "Beta"), edge("e2", "Alpha", "Gamma"), edge("e3", "Alpha", "Delta")]
    result = build(nodes, edges, [anchor("Gamma")])
    assert len(result.flows) == 1
    assert ids(result.flows[0]) == {"Alpha", "Beta", "Gamma", "Delta"}
    assert transitions(result.flows[0]) == {("Alpha", "Beta"), ("Alpha", "Gamma"), ("Alpha", "Delta")}


def test_three_entrypoints_repeat_shared_downstream_slice():
    nodes = [node(name, entrypoint=name in {"Alpha", "Beta", "Phi"}) for name in ["Alpha", "Beta", "Phi", "Gamma", "Delta", "Epsilon"]]
    edges = [edge("a", "Alpha", "Gamma"), edge("b", "Beta", "Gamma"), edge("f", "Phi", "Gamma"), edge("c", "Gamma", "Delta"), edge("d", "Delta", "Epsilon")]
    result = build(nodes, edges, [anchor("Delta")])
    assert len(result.flows) == 3
    assert {flow.entrypoint.node_id for flow in result.flows} == {"Alpha", "Beta", "Phi"}
    assert all({"Gamma", "Delta", "Epsilon"} <= ids(flow) for flow in result.flows)


def test_multiple_anchors_under_one_entrypoint_merge_origins():
    nodes = [node("Alpha", entrypoint=True), node("Beta"), node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("ag", "Alpha", "Gamma")]
    result = build(nodes, edges, [anchor("Beta"), anchor("Gamma", 0.8)])
    assert len(result.flows) == 1
    assert {item.node_id for item in result.flows[0].anchors} == {"Beta", "Gamma"}


def test_many_to_many_entrypoint_anchor_association_is_deduplicated():
    nodes = [node("Alpha", entrypoint=True), node("Beta", entrypoint=True), node("Gamma"), node("Delta")]
    edges = [edge("ag", "Alpha", "Gamma"), edge("bg", "Beta", "Gamma"), edge("gd", "Gamma", "Delta")]
    result = build(nodes, edges, [anchor("Gamma"), anchor("Delta")])
    assert len(result.flows) == 2
    assert all({item.node_id for item in flow.anchors} == {"Gamma", "Delta"} for flow in result.flows)


def test_explicit_entrypoint_stops_reverse_traversal_above_fact():
    nodes = [node("Alpha"), node("Beta", entrypoint=True), node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma")]
    result = build(nodes, edges, [anchor("Gamma")])
    assert [flow.entrypoint.node_id for flow in result.flows] == ["Beta"]
    assert result.flows[0].origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT


def test_inferred_root_is_marked_and_diagnosed():
    nodes = [node("Alpha"), node("Beta")]
    result = build(nodes, [edge("ab", "Alpha", "Beta")], [anchor("Beta")])
    assert result.flows[0].origin is EntrypointFlowOrigin.INFERRED_ROOT
    assert any(item.code == "ENTRYPOINT_FLOW_INFERRED_ROOT" for item in result.flows[0].diagnostics)


def test_cycle_edge_is_retained_once_and_traversal_terminates():
    nodes = [node("Alpha", entrypoint=True), node("Beta"), node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma"), edge("gb", "Gamma", "Beta")]
    flow = build(nodes, edges, [anchor("Gamma")]).flows[0]
    assert transitions(flow) == {("Alpha", "Beta"), ("Beta", "Gamma"), ("Gamma", "Beta")}
    assert flow.coverage.cycle_detected is True
    assert len(flow.nodes) == 3


def test_external_boundary_and_evidence_remain_owned_by_edge():
    nodes = [node("Alpha", entrypoint=True)]
    boundary = edge("outside", "Alpha", None, status="EXTERNAL_TARGET")
    flow = build(nodes, [boundary], [anchor("Alpha")]).flows[0]
    assert [item.edge_id for item in flow.boundary_transitions] == ["outside"]
    assert flow.evidence[0].edge_id == "outside"
    public = EntrypointFlowEngine().public_flows([flow])[0]
    assert public.boundaries[0].evidenceRefs == ["e1"]
    assert public.evidence[0].ownerRef == "b1"


def test_max_flows_limits_entrypoints_not_branches():
    nodes = [node("Alpha", entrypoint=True), node("Beta", entrypoint=True), node("Gamma"), node("Delta"), node("Epsilon")]
    edges = [edge("ag", "Alpha", "Gamma"), edge("bg", "Beta", "Gamma"), edge("gd", "Gamma", "Delta"), edge("ge", "Gamma", "Epsilon")]
    result = build(nodes, edges, [anchor("Gamma")], max_flows=1)
    assert len(result.flows) == 1
    assert {"Gamma", "Delta", "Epsilon"} <= ids(result.flows[0])
    assert any(item.code == "ENTRYPOINT_FLOW_MAX_FLOWS_REACHED" for item in result.diagnostics)


def test_include_tests_uses_persisted_flow_domain():
    production = node("Alpha", entrypoint=True)
    test_root = node("Beta", entrypoint=True, domain="TEST")
    shared = node("Gamma")
    edges = [edge("ag", "Alpha", "Gamma"), edge("bg", "Beta", "Gamma", domain="TEST")]
    excluded = build([production, test_root, shared], edges, [anchor("Gamma")], include_tests=False)
    included = build([production, test_root, shared], edges, [anchor("Gamma")], include_tests=True)
    assert {flow.entrypoint.node_id for flow in excluded.flows} == {"Alpha"}
    assert {flow.entrypoint.node_id for flow in included.flows} == {"Alpha", "Beta"}


def test_graph_revision_identity_prevents_stale_facts_from_joining_current_flow():
    current_nodes = [node("Alpha", entrypoint=True), node("Beta")]
    stale_node = replace(node("Stale"), graph_id="revision-stale", graph_revision="revision-stale")
    stale_edge = replace(edge("stale", "Stale", "Beta"), graph_id="revision-stale", graph_revision="revision-stale")
    result = build([*current_nodes, stale_node], [edge("ab", "Alpha", "Beta"), stale_edge], [anchor("Beta")])
    assert {flow.entrypoint.node_id for flow in result.flows} == {"Alpha"}


def test_public_flow_uses_response_local_refs_without_graph_ids():
    result = build([node("Alpha", entrypoint=True), node("Beta")], [edge("secret-edge", "Alpha", "Beta")], [anchor("Beta")])
    payload = result.public_flows[0].dict()
    rendered = str(payload)
    assert "secret-edge" not in rendered
    assert "revision-current" not in rendered
    assert {item["nodeRef"] for item in payload["nodes"]} == {"n1", "n2"}


def test_configured_multimodule_test_source_root_is_persisted_as_test():
    classifier = FileClassifier.from_config(load_knowledge_defaults()["knowledge"]["file_classification"])
    assert classifier.classify("module/src/test/java/arbitrary/Alpha.java").flow_domain == "TEST"
    assert classifier.classify("module/src/integrationTest/java/arbitrary/Beta.java").flow_domain == "TEST"
