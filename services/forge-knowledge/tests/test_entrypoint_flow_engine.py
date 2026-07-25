from __future__ import annotations

from collections import defaultdict
from dataclasses import replace
from typing import Sequence

from knowledge_service.entrypoint_flow_engine import EntrypointFlow, EntrypointFlowEngine, EntrypointFlowOrigin
from knowledge_service.file_classification import FileClassifier
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowNodeKey, dedupe_evidence
from knowledge_service.knowledge_defaults import load_knowledge_defaults
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode
from knowledge_service.operation_facts import AvailableOperationFact, OperationFactEligibility

SOURCE = "source-neutral"
REVISION = "revision-current"


def node(node_id: str, *, entrypoint: bool = False, domain: str = "CODE") -> FlowGraphNode:
    return FlowGraphNode(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        node_id=node_id,
        stable_key=f"key:{node_id}",
        node_kind="CALLABLE",
        label=node_id,
        relative_path=f"src/{node_id}.txt",
        line_start=1,
        line_end=2,
        entrypoint=entrypoint,
        flow_domain=domain,
    )


def edge(edge_id: str, source: str, target: str | None, *, status: str = "RESOLVED", domain: str = "CODE") -> FlowGraphEdge:
    return FlowGraphEdge(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        edge_id=edge_id,
        edge_type="CALLS",
        from_node_id=source,
        to_node_id=target,
        resolution_status=status,
        external=status == "EXTERNAL_TARGET",
        unresolved_target={"name": f"target-{edge_id}"} if target is None else None,
        flow_domain=domain,
    )


def evidence(item: FlowGraphEdge, index: int = 0) -> FlowGraphEvidence:
    suffix = f"-{index}" if index else ""
    return FlowGraphEvidence(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        evidence_id=f"ev-{item.edge_id}{suffix}",
        node_id=None,
        edge_id=item.edge_id,
        relative_path="src/calls.txt",
        line_start=10 + index,
        line_end=10 + index,
        text="call site",
    )


def node_evidence(item: FlowGraphNode) -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        evidence_id=f"ev-node-{item.node_id}",
        node_id=item.node_id,
        edge_id=None,
        relative_path=item.relative_path,
        line_start=item.line_start,
        line_end=item.line_end,
        text="node claim",
    )


def anchor(node_id: str, score: float = 0.9, *, domain: str = "CODE") -> KnowledgeQueryMatchedNode:
    return KnowledgeQueryMatchedNode(
        sourceId=SOURCE,
        nodeId=node_id,
        stableKey=f"key:{node_id}",
        nodeKind="CALLABLE",
        label=node_id,
        score=score,
        matchReasons=["GRAPH_MATCH"],
        graphId=REVISION,
        graphRevision=REVISION,
        flowDomain=domain,
    )


class FakeFlowRepository:
    def __init__(
        self,
        nodes: Sequence[FlowGraphNode],
        edges: Sequence[FlowGraphEdge],
        evidence_items: Sequence[FlowGraphEvidence] | None = None,
        operation_facts: Sequence[AvailableOperationFact] | None = None,
    ) -> None:
        self.nodes = {self._node_key(item): item for item in nodes}
        self.edges = {self._edge_key(item): item for item in edges}
        self.evidence = tuple(evidence_items or tuple(evidence(item) for item in edges))
        self.operation_facts = tuple(operation_facts or ())
        self.calls = defaultdict(int)

    def load_nodes(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, FlowGraphNode]:
        self.calls["load_nodes"] += 1
        result = {}
        for requested in node_keys:
            for key, item in self.nodes.items():
                if key[0] == requested[0] and key[2] == requested[2] and requested[1] in {item.graph_id, item.graph_revision or ""}:
                    if include_tests or str(item.flow_domain or "").upper() != "TEST":
                        result[key] = item
        return result

    def load_incoming_calls(self, target_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        self.calls["load_incoming_calls"] += 1
        targets = set(target_keys)
        result = defaultdict(list)
        for item in self.edges.values():
            target_key = self._to_key(item)
            source_key = self._from_key(item)
            if target_key not in targets:
                continue
            if source_key not in self.nodes:
                continue
            if not include_tests and (
                str(item.flow_domain or "").upper() == "TEST"
                or str(self.nodes[source_key].flow_domain or "").upper() == "TEST"
            ):
                continue
            result[target_key].append(item)
        return {key: tuple(sorted(value, key=lambda edge: edge.edge_id)) for key, value in result.items()}

    def load_outgoing_calls(self, source_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        self.calls["load_outgoing_calls"] += 1
        sources = set(source_keys)
        result = defaultdict(list)
        for item in self.edges.values():
            source_key = self._from_key(item)
            if source_key not in sources:
                continue
            if not include_tests and str(item.flow_domain or "").upper() == "TEST":
                continue
            result[source_key].append(item)
        return {key: tuple(sorted(value, key=lambda edge: edge.edge_id)) for key, value in result.items()}

    def hydrate_evidence(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]:
        self.calls["hydrate_evidence"] += 1
        hydrated = []
        for flow in flows:
            edge_ids = {item.edge_id for item in (*flow.transitions, *flow.boundary_transitions)}
            node_ids = {item.node_id for item in flow.nodes}
            selected = dedupe_evidence([
                item for item in self.evidence
                if item.edge_id in edge_ids or item.node_id in node_ids
            ])
            evidence_by_edge = defaultdict(list)
            for item in selected:
                if item.edge_id:
                    evidence_by_edge[item.edge_id].append(item.evidence_id)
            hydrated.append(replace(
                flow,
                transitions=tuple(replace(item, evidence_ids=tuple(evidence_by_edge.get(item.edge_id, []))) for item in flow.transitions),
                boundary_transitions=tuple(replace(item, evidence_ids=tuple(evidence_by_edge.get(item.edge_id, []))) for item in flow.boundary_transitions),
                evidence=selected,
            ))
        return tuple(hydrated)

    def load_available_operation_facts(
        self,
        node_keys: set[FlowNodeKey],
        *,
        include_tests: bool,
    ) -> tuple[AvailableOperationFact, ...]:
        self.calls["load_available_operation_facts"] += 1
        return tuple(fact for fact in self.operation_facts if fact.owner_key in node_keys)

    def metrics(self) -> dict[str, int]:
        return dict(self.calls)

    def _node_key(self, item: FlowGraphNode) -> FlowNodeKey:
        return (item.source_id, item.graph_revision or item.graph_id, item.node_id)

    def _edge_key(self, item: FlowGraphEdge):
        return (item.source_id, item.graph_revision or item.graph_id, item.edge_id)

    def _from_key(self, item: FlowGraphEdge) -> FlowNodeKey:
        return (item.source_id, item.graph_revision or item.graph_id, item.from_node_id)

    def _to_key(self, item: FlowGraphEdge) -> FlowNodeKey | None:
        if not item.to_node_id:
            return None
        return (item.source_id, item.graph_revision or item.graph_id, item.to_node_id)


def operation_fact(
    owner: FlowGraphNode,
    *,
    direction: str = "INBOUND",
    method: str | None = "POST",
    route: str | None = "/registrations",
    transport: str = "HTTP",
    current: bool = True,
) -> AvailableOperationFact:
    return AvailableOperationFact(
        owner_source_id=owner.source_id,
        owner_graph_id=owner.graph_id,
        owner_graph_revision=owner.graph_revision,
        owner_node_id=owner.node_id,
        source_id=owner.source_id,
        execution_role="EXECUTABLE" if direction == "INBOUND" else "CLIENT_OPERATION",
        transport_kind=transport,
        direction_role=direction,
        method=method,
        normalized_route=route,
        owner_qualified_name=owner.qualified_name,
        eligibility=OperationFactEligibility(
            status="TRUSTED",
            inventory_current=current,
            analyzed_current=current,
        ),
    )


def build(nodes, edges, anchors, *, max_flows=10, include_tests=False, evidence_items=None, operation_facts=None):
    repository = FakeFlowRepository(nodes, edges, evidence_items, operation_facts)
    return EntrypointFlowEngine(repository).build(
        anchors,
        max_flows=max_flows,
        include_tests=include_tests,
    )


def ids(flow):
    return {item.node_id for item in flow.nodes}


def transitions(flow):
    return {(item.from_node_id, item.to_node_id) for item in flow.transitions}


def assert_public_refs_close(payload):
    node_refs = {item["nodeRef"] for item in payload["nodes"]}
    transition_refs = {item["transitionRef"] for item in payload["transitions"]}
    boundary_refs = {item["boundaryRef"] for item in payload["boundaries"]}
    evidence_refs = {item["evidenceRef"] for item in payload["evidence"]}
    assert payload["entrypoint"]["nodeRef"] in node_refs
    for item in payload["transitions"]:
        assert item["fromNodeRef"] in node_refs
        assert item["toNodeRef"] in node_refs
        assert set(item.get("evidenceRefs", [])) <= evidence_refs
    for item in payload["boundaries"]:
        assert item["fromNodeRef"] in node_refs
        assert set(item.get("evidenceRefs", [])) <= evidence_refs
    for item in payload["matchedAnchors"]:
        assert item["anchorRef"] in node_refs
    for item in payload["evidence"]:
        assert item["ownerRef"] in node_refs | transition_refs | boundary_refs


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


def test_inbound_http_operation_fact_promotes_callable_to_explicit_root():
    beta = node("Beta")
    nodes = [node("Alpha"), beta, node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma")]

    result = build(nodes, edges, [anchor("Gamma")], operation_facts=[operation_fact(beta)])

    assert [flow.entrypoint.node_id for flow in result.flows] == ["Beta"]
    assert result.flows[0].origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT
    assert result.flows[0].entrypoint.entrypoint is True
    assert result.flows[0].entrypoint.entrypoint_kind == "HTTP"
    assert result.flows[0].entrypoint.entrypoint_http_method == "POST"
    assert result.flows[0].entrypoint.entrypoint_route == "/registrations"


def test_outbound_http_operation_fact_does_not_promote_callable_to_explicit_root():
    beta = node("Beta")
    nodes = [node("Alpha"), beta, node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma")]

    result = build(nodes, edges, [anchor("Gamma")], operation_facts=[operation_fact(beta, direction="OUTBOUND")])

    assert [flow.entrypoint.node_id for flow in result.flows] == ["Alpha"]
    assert result.flows[0].origin is EntrypointFlowOrigin.INFERRED_ROOT


def test_similarly_named_callable_without_http_operation_fact_is_not_explicit_root():
    nodes = [node("Alpha"), node("RegistrationController"), node("Gamma")]
    edges = [edge("ar", "Alpha", "RegistrationController"), edge("rg", "RegistrationController", "Gamma")]

    result = build(nodes, edges, [anchor("Gamma")])

    assert [flow.entrypoint.node_id for flow in result.flows] == ["Alpha"]
    assert result.flows[0].origin is EntrypointFlowOrigin.INFERRED_ROOT


def test_stale_inbound_http_operation_fact_does_not_promote_root():
    beta = node("Beta")
    nodes = [node("Alpha"), beta, node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma")]

    result = build(nodes, edges, [anchor("Gamma")], operation_facts=[operation_fact(beta, current=False)])

    assert [flow.entrypoint.node_id for flow in result.flows] == ["Alpha"]
    assert result.flows[0].origin is EntrypointFlowOrigin.INFERRED_ROOT


def test_non_http_operation_fact_does_not_promote_root():
    beta = node("Beta")
    nodes = [node("Alpha"), beta, node("Gamma")]
    edges = [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma")]

    result = build(nodes, edges, [anchor("Gamma")], operation_facts=[operation_fact(beta, transport="QUEUE")])

    assert [flow.entrypoint.node_id for flow in result.flows] == ["Alpha"]
    assert result.flows[0].origin is EntrypointFlowOrigin.INFERRED_ROOT


def test_inferred_root_is_marked_and_diagnosed():
    nodes = [node("Alpha"), node("Beta")]
    result = build(nodes, [edge("ab", "Alpha", "Beta")], [anchor("Beta")])
    assert result.flows[0].origin is EntrypointFlowOrigin.INFERRED_ROOT
    assert any(item.code == "ENTRYPOINT_FLOW_INFERRED_ROOT" for item in result.flows[0].diagnostics)


def test_inferred_roots_are_omitted_when_explicit_entrypoints_are_reachable():
    nodes = [node("Alpha", entrypoint=True), node("Beta"), node("Detached")]
    edges = [edge("ab", "Alpha", "Beta")]
    result = build(nodes, edges, [anchor("Beta"), anchor("Detached")])
    assert [flow.entrypoint.node_id for flow in result.flows] == ["Alpha"]
    assert result.flows[0].origin is EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT


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


def test_engine_discovers_all_entrypoints_before_family_level_max_flows():
    nodes = [node("Alpha", entrypoint=True), node("Beta", entrypoint=True), node("Gamma"), node("Delta"), node("Epsilon")]
    edges = [edge("ag", "Alpha", "Gamma"), edge("bg", "Beta", "Gamma"), edge("gd", "Gamma", "Delta"), edge("ge", "Gamma", "Epsilon")]
    result = build(nodes, edges, [anchor("Gamma")], max_flows=1)
    assert len(result.flows) == 2
    assert {flow.entrypoint.node_id for flow in result.flows} == {"Alpha", "Beta"}
    assert all({"Gamma", "Delta", "Epsilon"} <= ids(flow) for flow in result.flows)
    assert result.diagnostics == []


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
    assert_public_refs_close(payload)


def test_deep_reverse_discovery_has_no_depth_limit():
    nodes = [node("Alpha", entrypoint=True), *[node(f"Node{i}") for i in range(1, 51)], node("Omega")]
    edges = [edge("e0", "Alpha", "Node1")]
    edges.extend(edge(f"e{i}", f"Node{i}", f"Node{i + 1}") for i in range(1, 50))
    edges.append(edge("e50", "Node50", "Omega"))
    result = build(nodes, edges, [anchor("Omega")])
    flow = result.flows[0]
    assert flow.entrypoint.node_id == "Alpha"
    assert flow.coverage.node_count == 52
    assert flow.coverage.transition_count == 51
    assert not any(item.code == "ENTRYPOINT_FLOW_SLICE_TRUNCATED" for item in [*result.diagnostics, *flow.diagnostics])


def test_deep_downstream_graph_has_no_depth_limit():
    nodes = [node("Alpha", entrypoint=True), *[node(f"Node{i}") for i in range(1, 101)]]
    edges = [edge("e0", "Alpha", "Node1")]
    edges.extend(edge(f"e{i}", f"Node{i}", f"Node{i + 1}") for i in range(1, 100))
    flow = build(nodes, edges, [anchor("Alpha")]).flows[0]
    assert flow.coverage.node_count == 101
    assert flow.coverage.transition_count == 100
    assert flow.complete is True


def test_wide_fanout_has_no_edge_per_node_limit():
    nodes = [node("Alpha", entrypoint=True), *[node(f"Child{i}") for i in range(100)]]
    edges = [edge(f"e{i}", "Alpha", f"Child{i}") for i in range(100)]
    flow = build(nodes, edges, [anchor("Alpha")]).flows[0]
    assert flow.coverage.node_count == 101
    assert flow.coverage.transition_count == 100
    assert transitions(flow) == {("Alpha", f"Child{i}") for i in range(100)}


def test_more_than_two_thousand_edges_reaches_fixed_point():
    count = 2005
    nodes = [node("Alpha", entrypoint=True), *[node(f"Node{i}") for i in range(1, count + 1)]]
    edges = [edge("e0", "Alpha", "Node1")]
    edges.extend(edge(f"e{i}", f"Node{i}", f"Node{i + 1}") for i in range(1, count))
    flow = build(nodes, edges, [anchor(f"Node{count}")]).flows[0]
    assert flow.coverage.node_count == count + 1
    assert flow.coverage.transition_count == count
    assert flow.coverage.truncated is False


def test_more_than_previous_entrypoint_limit_discovers_all_raw_flows():
    roots = [f"Root{i:02d}" for i in range(40)]
    nodes = [*[node(root, entrypoint=True) for root in roots], node("Shared"), node("Anchor")]
    edges = [edge(f"{root}-s", root, "Shared") for root in roots]
    edges.append(edge("shared-anchor", "Shared", "Anchor"))
    result = build(nodes, edges, [anchor("Anchor")], max_flows=10)
    assert result.discovered_entrypoint_count == 40
    assert len(result.flows) == 40
    assert result.truncated is False
    assert result.diagnostics == []


def test_evidence_above_previous_budget_is_complete_and_public_refs_close():
    nodes = [node("Alpha", entrypoint=True), *[node(f"Child{i}") for i in range(30)]]
    edges = [edge(f"e{i}", "Alpha", f"Child{i}") for i in range(30)]
    evidence_items = [evidence(item, 1) for item in edges] + [evidence(item, 2) for item in edges] + [node_evidence(nodes[0])]
    result = build(nodes, edges, [anchor("Alpha")], evidence_items=evidence_items)
    flow = result.flows[0]
    assert len(flow.evidence) == 61
    assert all(edge.evidence_ids for edge in flow.transitions)
    payload = result.public_flows[0].dict()
    assert len(payload["evidence"]) == 61
    assert "FLOW_EVIDENCE_TRUNCATED" not in str(payload)
    assert "ev-e0" not in str(payload)
    assert_public_refs_close(payload)


def test_missing_resolved_target_becomes_boundary_without_dangling_transition():
    nodes = [node("Alpha", entrypoint=True)]
    missing = edge("missing", "Alpha", "Missing")
    flow = build(nodes, [missing], [anchor("Alpha")]).flows[0]
    assert flow.transitions == ()
    assert len(flow.boundary_transitions) == 1
    assert flow.boundary_transitions[0].boundary_reason == "CURRENT_TARGET_NODE_MISSING"
    assert any(item.code == "ENTRYPOINT_FLOW_CURRENT_TARGET_NODE_MISSING" for item in flow.diagnostics)
    payload = EntrypointFlowEngine().public_flows([flow])[0].dict()
    assert payload["boundaries"][0]["kind"] == "CURRENT_TARGET_NODE_MISSING"
    assert_public_refs_close(payload)


def test_configured_multimodule_test_source_root_is_persisted_as_test():
    classifier = FileClassifier.from_config(load_knowledge_defaults()["knowledge"]["file_classification"])
    assert classifier.classify("module/src/test/java/arbitrary/Alpha.java").flow_domain == "TEST"
    assert classifier.classify("module/src/integrationTest/java/arbitrary/Beta.java").flow_domain == "TEST"
