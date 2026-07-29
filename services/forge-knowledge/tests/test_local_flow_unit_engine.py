from __future__ import annotations

from collections import defaultdict
from dataclasses import replace
from typing import Sequence

from knowledge_service.boundary_contract import LocalBoundaryDescriptor, LocalBoundaryFact
from knowledge_service.file_classification import FileClassifier
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode, FlowNodeKey, dedupe_evidence
from knowledge_service.knowledge_defaults import load_knowledge_defaults
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode
from knowledge_service.local_flow_unit_engine import (
    LocalFlowRootOrigin,
    LocalFlowSeedProvenance,
    LocalFlowUnit,
    LocalFlowUnitEngine,
)

SOURCE = "source-neutral"
OTHER_SOURCE = "source-other"
REVISION = "revision-current"


def node(
    node_id: str,
    *,
    source: str = SOURCE,
    entrypoint: bool = False,
    kind: str = "CALLABLE",
    domain: str = "CODE",
    revision: str = REVISION,
) -> FlowGraphNode:
    return FlowGraphNode(
        source_id=source,
        graph_id=revision,
        graph_revision=revision,
        node_id=node_id,
        stable_key=f"{source}:key:{node_id}",
        node_kind=kind,
        label=node_id,
        relative_path=f"src/{node_id}.txt",
        line_start=1,
        line_end=2,
        entrypoint=entrypoint,
        execution_role="EXECUTABLE" if entrypoint else None,
        flow_domain=domain,
    )


def edge(
    edge_id: str,
    source_node: str,
    target: str | None,
    *,
    source: str = SOURCE,
    target_source: str | None = None,
    status: str = "RESOLVED",
    domain: str = "CODE",
    edge_type: str = "CALLS",
) -> FlowGraphEdge:
    return FlowGraphEdge(
        source_id=source,
        graph_id=REVISION,
        graph_revision=REVISION,
        edge_id=edge_id,
        edge_type=edge_type,
        from_node_id=source_node,
        to_node_id=target,
        to_source_id=target_source,
        to_graph_id=REVISION if target_source else None,
        to_graph_revision=REVISION if target_source else None,
        resolution_status=status,
        external=status == "EXTERNAL_TARGET",
        unresolved_target={"name": f"target-{edge_id}"} if target is None else None,
        flow_domain=domain,
    )


def evidence(item: FlowGraphEdge, index: int = 0) -> FlowGraphEvidence:
    suffix = f"-{index}" if index else ""
    return FlowGraphEvidence(
        source_id=item.source_id,
        graph_id=item.graph_id,
        graph_revision=item.graph_revision,
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
        source_id=item.source_id,
        graph_id=item.graph_id,
        graph_revision=item.graph_revision,
        evidence_id=f"ev-node-{item.node_id}",
        node_id=item.node_id,
        edge_id=None,
        relative_path=item.relative_path,
        line_start=item.line_start,
        line_end=item.line_end,
        text="node claim",
    )


def boundary(
    boundary_id: str,
    owner: FlowGraphNode,
    role: str,
    *,
    descriptors: tuple[LocalBoundaryDescriptor, ...] = (),
    evidence_items: tuple[FlowGraphEvidence, ...] = (),
    domain: str = "CODE",
) -> LocalBoundaryFact:
    return LocalBoundaryFact(
        boundary_id=boundary_id,
        stable_key=f"{owner.source_id}:boundary:{boundary_id}",
        source_id=owner.source_id,
        graph_id=owner.graph_id,
        graph_revision=owner.graph_revision,
        owner_node_id=owner.node_id,
        role=role,
        status="TRUSTED",
        provenance="STATIC",
        confidence=0.91,
        flow_domain=domain,
        descriptors=descriptors,
        evidence=evidence_items,
    )


def descriptor(
    descriptor_id: str,
    path: str,
    value,
    *,
    evidence_items: tuple[FlowGraphEvidence, ...] = (),
) -> LocalBoundaryDescriptor:
    return LocalBoundaryDescriptor(
        descriptor_id=descriptor_id,
        path=path,
        value_type=type(value).__name__.upper(),
        value=value,
        origin="STATIC",
        confidence=0.8,
        evidence=evidence_items,
    )


def anchor(node_id: str, score: float = 0.9, *, kind: str = "CALLABLE", source: str = SOURCE, domain: str = "CODE") -> KnowledgeQueryMatchedNode:
    return KnowledgeQueryMatchedNode(
        sourceId=source,
        nodeId=node_id,
        stableKey=f"{source}:key:{node_id}",
        nodeKind=kind,
        label=node_id,
        score=score,
        matchReasons=["GRAPH_MATCH", "QUERY_ORIGINAL"],
        graphId=REVISION,
        graphRevision=REVISION,
        flowDomain=domain,
    )


class FakeFlowRepository:
    def __init__(
        self,
        nodes: Sequence[FlowGraphNode],
        edges: Sequence[FlowGraphEdge],
        *,
        boundaries: Sequence[LocalBoundaryFact] = (),
        evidence_items: Sequence[FlowGraphEvidence] | None = None,
    ) -> None:
        self.nodes = {self._node_key(item): item for item in nodes}
        self.edges = {self._edge_key(item): item for item in edges}
        self.boundaries = tuple(boundaries)
        self.evidence = tuple(evidence_items or tuple(evidence(item) for item in edges))
        self.calls = defaultdict(int)

    def load_nodes(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, FlowGraphNode]:
        self.calls["load_nodes"] += 1
        result = {}
        for requested in node_keys:
            for key, item in self.nodes.items():
                if (
                    key[0] == requested[0]
                    and key[2] == requested[2]
                    and requested[1] in {item.graph_id, item.graph_revision or ""}
                    and (include_tests or str(item.flow_domain or "").upper() != "TEST")
                ):
                    result[key] = item
        return result

    def load_incoming_calls(self, target_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, tuple[FlowGraphEdge, ...]]:
        self.calls["load_incoming_calls"] += 1
        targets = set(target_keys)
        result = defaultdict(list)
        for item in self.edges.values():
            target_key = self._to_key(item)
            source_key = self._from_key(item)
            if target_key not in targets or source_key not in self.nodes:
                continue
            if not include_tests and (str(item.flow_domain or "").upper() == "TEST" or str(self.nodes[source_key].flow_domain or "").upper() == "TEST"):
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

    def load_boundaries(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, tuple[LocalBoundaryFact, ...]]:
        self.calls["load_boundaries"] += 1
        keys = set(node_keys)
        result = defaultdict(list)
        for item in self.boundaries:
            if item.owner_key not in keys:
                continue
            if not include_tests and str(item.flow_domain or "").upper() == "TEST":
                continue
            result[item.owner_key].append(item)
        return {key: tuple(sorted(value, key=lambda fact: fact.boundary_id)) for key, value in result.items()}

    def hydrate_local_units(self, units: Sequence[LocalFlowUnit]) -> tuple[LocalFlowUnit, ...]:
        self.calls["hydrate_local_units"] += 1
        hydrated = []
        for unit in units:
            edge_ids = {item.edge_id for item in (*unit.execution_transitions, *unit.topology_boundaries)}
            node_ids = {item.node_id for item in (*unit.execution_nodes, *unit.supporting_context)}
            selected = dedupe_evidence([item for item in self.evidence if item.edge_id in edge_ids or item.node_id in node_ids])
            evidence_by_edge = defaultdict(list)
            for item in selected:
                if item.edge_id:
                    evidence_by_edge[item.edge_id].append(item.evidence_id)
            hydrated.append(
                replace(
                    unit,
                    execution_transitions=tuple(
                        replace(item, evidence_ids=tuple(evidence_by_edge.get(item.edge_id, []))) for item in unit.execution_transitions
                    ),
                    topology_boundaries=tuple(
                        replace(item, evidence_ids=tuple(evidence_by_edge.get(item.edge_id, []))) for item in unit.topology_boundaries
                    ),
                    evidence=dedupe_evidence([*unit.evidence, *selected]),
                )
            )
        return tuple(hydrated)

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
        return (item.to_source_id or item.source_id, item.to_graph_revision or item.to_graph_id or item.graph_revision or item.graph_id, item.to_node_id)


def build(nodes, edges, anchors, *, include_tests=False, boundaries=(), evidence_items=None, provenance=()):
    repository = FakeFlowRepository(nodes, edges, boundaries=boundaries, evidence_items=evidence_items)
    result = LocalFlowUnitEngine(repository).build(
        anchors,
        include_tests=include_tests,
        anchor_seed_provenance=provenance,
    )
    return result, repository


def unit_ids(unit):
    return {item.node_id for item in unit.execution_nodes}


def unit_signature(unit):
    return {
        "id": unit.unit_id,
        "roots": [(root.node.node_id, root.origin.value) for root in unit.roots],
        "anchors": [(item.original_anchor.nodeId, item.expanded_seed.node_id) for item in unit.anchors],
        "nodes": [item.node_id for item in unit.execution_nodes],
        "transitions": [(item.from_node_id, item.to_node_id, item.edge_id) for item in unit.execution_transitions],
        "topology": [(item.from_node_id, item.to_node_id, item.boundary_reason, item.resolution_status) for item in unit.topology_boundaries],
        "generic": [(item.owner_node_id, item.role, item.boundary_id) for item in unit.generic_boundaries],
        "truncated": unit.coverage.truncated,
    }


def test_bidirectional_corridor_excludes_pre_anchor_sibling_and_retains_downstream_boundaries():
    root = node("Root", entrypoint=True)
    repository = node("Repository")
    provided = boundary("provided-root", root, "PROVIDED")
    required = boundary("required-repo", repository, "REQUIRED")
    nodes = [root, node("Service"), node("Anchor"), repository, node("Audit"), node("Unrelated")]
    edges = [
        edge("root-service", "Root", "Service"),
        edge("root-unrelated", "Root", "Unrelated"),
        edge("service-anchor", "Service", "Anchor"),
        edge("anchor-repo", "Anchor", "Repository"),
        edge("anchor-audit", "Anchor", "Audit"),
    ]

    result, _repo = build(nodes, edges, [anchor("Anchor")], boundaries=[provided, required])

    unit = result.local_units[0]
    assert unit_ids(unit) == {"Root", "Service", "Anchor", "Repository", "Audit"}
    assert ("Root", "Unrelated") not in {(item.from_node_id, item.to_node_id) for item in unit.execution_transitions}
    assert {(item.from_node_id, item.to_node_id) for item in unit.execution_transitions} == {
        ("Root", "Service"),
        ("Service", "Anchor"),
        ("Anchor", "Repository"),
        ("Anchor", "Audit"),
    }
    assert {(item.owner_node_id, item.role) for item in unit.generic_boundaries} == {("Root", "PROVIDED"), ("Repository", "REQUIRED")}
    assert unit.roots[0].node.node_id == "Root"
    assert unit.roots[0].origin is LocalFlowRootOrigin.EXPLICIT_GRAPH_FACT


def test_two_roots_converging_on_one_anchor_are_one_local_unit_with_two_roots():
    nodes = [node("RootA", entrypoint=True), node("RootB", entrypoint=True), node("Shared"), node("Anchor")]
    edges = [
        edge("a-shared", "RootA", "Shared"),
        edge("b-shared", "RootB", "Shared"),
        edge("shared-anchor", "Shared", "Anchor"),
    ]

    result, _repo = build(nodes, edges, [anchor("Anchor")])

    assert len(result.local_units) == 1
    assert {root.node.node_id for root in result.local_units[0].roots} == {"RootA", "RootB"}


def test_multi_root_local_unit_preserves_nodes_boundaries_evidence_and_cycles_once():
    root_a = node("RootA", entrypoint=True)
    root_b = node("RootB", entrypoint=True)
    shared = node("Shared")
    selected = node("Anchor")
    downstream = node("Downstream")
    nodes = [root_b, downstream, selected, root_a, shared]
    edges = [
        edge("downstream-boundary", "Downstream", None, status="UNRESOLVED"),
        edge("anchor-downstream", "Anchor", "Downstream"),
        edge("b-shared", "RootB", "Shared"),
        edge("anchor-shared-cycle", "Anchor", "Shared"),
        edge("shared-anchor", "Shared", "Anchor"),
        edge("a-boundary", "RootA", None, status="UNRESOLVED"),
        edge("b-boundary", "RootB", None, status="UNRESOLVED"),
        edge("a-shared", "RootA", "Shared"),
    ]
    evidence_items = [
        *(evidence(item) for item in edges),
        node_evidence(root_a),
        node_evidence(root_b),
        node_evidence(shared),
        node_evidence(selected),
        node_evidence(downstream),
    ]
    result, _repo = build(
        nodes,
        list(reversed(edges)),
        [anchor("Anchor")],
        boundaries=[
            boundary("root-a-provided", root_a, "PROVIDED", evidence_items=(node_evidence(root_a),)),
            boundary("root-b-provided", root_b, "PROVIDED", evidence_items=(node_evidence(root_b),)),
            boundary("downstream-required", downstream, "REQUIRED", evidence_items=(node_evidence(downstream),)),
        ],
        evidence_items=evidence_items,
    )

    assert len(result.local_units) == 1
    unit = result.local_units[0]
    assert {root.node.node_id for root in unit.roots} == {"RootA", "RootB"}
    assert unit_ids(unit) == {"RootA", "RootB", "Shared", "Anchor", "Downstream"}
    assert {(item.from_node_id, item.to_node_id) for item in unit.execution_transitions} == {
        ("RootA", "Shared"),
        ("RootB", "Shared"),
        ("Shared", "Anchor"),
        ("Anchor", "Downstream"),
        ("Anchor", "Shared"),
    }
    assert {item.edge_id for item in unit.topology_boundaries} == {"downstream-boundary"}
    assert {item.owner_node_id for item in unit.generic_boundaries} == {"RootA", "RootB", "Downstream"}
    assert {"ev-a-shared", "ev-b-shared", "ev-node-RootA", "ev-node-RootB"} <= {item.evidence_id for item in unit.evidence}
    assert unit.coverage.cycle_detected is True


def test_explicit_root_for_one_anchor_does_not_suppress_another_inferred_root():
    nodes = [node("Root", entrypoint=True), node("AnchorA"), node("Detached")]
    edges = [edge("root-a", "Root", "AnchorA")]

    result, _repo = build(nodes, edges, [anchor("AnchorA"), anchor("Detached")])

    assert len(result.local_units) == 2
    roots_by_anchor = {unit.anchors[0].original_anchor.nodeId: [(root.node.node_id, root.origin) for root in unit.roots] for unit in result.local_units}
    assert roots_by_anchor["AnchorA"] == [("Root", LocalFlowRootOrigin.EXPLICIT_GRAPH_FACT)]
    assert roots_by_anchor["Detached"] == [("Detached", LocalFlowRootOrigin.INFERRED_ROOT)]


def test_overlapping_anchors_merge_but_independent_same_source_anchor_stays_separate_and_order_stable():
    nodes = [node("Root", entrypoint=True), node("A"), node("B"), node("OtherRoot", entrypoint=True), node("C")]
    edges = [edge("root-a", "Root", "A"), edge("a-b", "A", "B"), edge("other-c", "OtherRoot", "C")]

    first, _repo = build(nodes, edges, [anchor("A", 0.8), anchor("B", 0.7), anchor("C", 0.9)])
    second, _repo = build(nodes, edges, [anchor("C", 0.9), anchor("B", 0.7), anchor("A", 0.8)])

    assert len(first.local_units) == 2
    merged = next(unit for unit in first.local_units if {item.original_anchor.nodeId for item in unit.anchors} == {"A", "B"})
    independent = next(unit for unit in first.local_units if {item.original_anchor.nodeId for item in unit.anchors} == {"C"})
    assert unit_ids(merged) == {"Root", "A", "B"}
    assert unit_ids(independent) == {"OtherRoot", "C"}
    assert [unit_signature(unit) for unit in first.local_units] == [unit_signature(unit) for unit in second.local_units]


def test_multiple_incoming_paths_and_downstream_branches_are_preserved():
    nodes = [
        node("RootA", entrypoint=True),
        node("RootB", entrypoint=True),
        node("CallerA"),
        node("CallerB"),
        node("Anchor"),
        node("Left"),
        node("Right"),
    ]
    edges = [
        edge("ra-ca", "RootA", "CallerA"),
        edge("rb-cb", "RootB", "CallerB"),
        edge("ca-anchor", "CallerA", "Anchor"),
        edge("cb-anchor", "CallerB", "Anchor"),
        edge("anchor-left", "Anchor", "Left"),
        edge("anchor-right", "Anchor", "Right"),
    ]

    result, _repo = build(nodes, edges, [anchor("Anchor")])
    unit = result.local_units[0]

    assert {root.node.node_id for root in unit.roots} == {"RootA", "RootB"}
    assert {(item.from_node_id, item.to_node_id) for item in unit.execution_transitions} == {
        ("RootA", "CallerA"),
        ("RootB", "CallerB"),
        ("CallerA", "Anchor"),
        ("CallerB", "Anchor"),
        ("Anchor", "Left"),
        ("Anchor", "Right"),
    }


def test_cycles_are_retained_once_and_do_not_block_non_cycle_branches():
    nodes = [node("Root", entrypoint=True), node("A"), node("B"), node("Tail")]
    edges = [
        edge("root-a", "Root", "A"),
        edge("a-b", "A", "B"),
        edge("b-a", "B", "A"),
        edge("a-tail", "A", "Tail"),
    ]

    result, _repo = build(nodes, edges, [anchor("A")])
    unit = result.local_units[0]

    assert unit.coverage.cycle_detected is True
    assert {(item.from_node_id, item.to_node_id) for item in unit.execution_transitions} == {
        ("Root", "A"),
        ("A", "B"),
        ("B", "A"),
        ("A", "Tail"),
    }
    assert unit_ids(unit) == {"Root", "A", "B", "Tail"}


def test_self_calls_do_not_expand_forever():
    nodes = [node("Root", entrypoint=True), node("Anchor")]
    edges = [edge("root-anchor", "Root", "Anchor"), edge("self", "Anchor", "Anchor")]

    result, repo = build(nodes, edges, [anchor("Anchor")])
    unit = result.local_units[0]

    assert unit.coverage.cycle_detected is True
    assert {(item.from_node_id, item.to_node_id) for item in unit.execution_transitions} == {("Root", "Anchor"), ("Anchor", "Anchor")}
    assert repo.calls["load_outgoing_calls"] < 5


def test_topology_boundaries_are_separate_for_unresolved_external_cross_source_and_missing_targets():
    nodes = [node("Anchor", entrypoint=True), node("Remote", source=OTHER_SOURCE)]
    edges = [
        edge("unresolved", "Anchor", None, status="UNRESOLVED"),
        edge("external", "Anchor", None, status="EXTERNAL_TARGET"),
        edge("cross", "Anchor", "Remote", target_source=OTHER_SOURCE),
        edge("missing", "Anchor", "Missing"),
    ]

    result, _repo = build(nodes, edges, [anchor("Anchor")])
    unit = result.local_units[0]

    assert unit.execution_transitions == ()
    assert {(item.edge_id, item.boundary_reason, item.resolution_status) for item in unit.topology_boundaries} == {
        ("unresolved", None, "UNRESOLVED"),
        ("external", None, "EXTERNAL_TARGET"),
        ("cross", "CROSS_SOURCE_TARGET", "RESOLVED"),
        ("missing", "CURRENT_TARGET_NODE_MISSING", "RESOLVED"),
    }


def test_generic_boundary_descriptors_and_descriptor_evidence_are_preserved_without_collapsing_conflicts():
    owner = node("Repository")
    boundary_ev = node_evidence(owner)
    descriptor_ev = replace(boundary_ev, evidence_id="descriptor-ev", text="descriptor support")
    one = descriptor("d1", "operation.name", "first", evidence_items=(descriptor_ev,))
    two = descriptor("d2", "operation.name", "second", evidence_items=(descriptor_ev,))
    required = boundary("required", owner, "REQUIRED", descriptors=(one, two), evidence_items=(boundary_ev,))
    nodes = [node("Root", entrypoint=True), node("Anchor"), owner]
    edges = [edge("root-anchor", "Root", "Anchor"), edge("anchor-repo", "Anchor", "Repository")]

    result, _repo = build(nodes, edges, [anchor("Anchor")], boundaries=[required])
    loaded = result.local_units[0].generic_boundaries[0]

    assert loaded.role == "REQUIRED"
    assert [(item.path, item.value) for item in loaded.descriptors] == [("operation.name", "first"), ("operation.name", "second")]
    assert loaded.evidence == (boundary_ev,)
    assert loaded.descriptors[0].evidence == (descriptor_ev,)


def test_original_type_file_field_and_callable_anchor_provenance_survives_seed_mapping_without_supporting_edges_as_execution():
    file_node = node("File", kind="FILE")
    type_node = node("Type", kind="TYPE")
    field_node = node("Field", kind="FIELD")
    callable_node = node("Callable")
    root = node("Root", entrypoint=True)
    nodes = [file_node, type_node, field_node, callable_node, root]
    edges = [
        edge("root-callable", "Root", "Callable"),
        edge("field-use", "Callable", "Field", edge_type="USES_FIELD"),
    ]
    provenance = (
        LocalFlowSeedProvenance(anchor("Type", kind="TYPE"), anchor("Callable"), ("TYPE_DECLARED_CALLABLE",)),
        LocalFlowSeedProvenance(anchor("File", kind="FILE"), anchor("Callable"), ("FILE_DECLARED_NODE",)),
        LocalFlowSeedProvenance(anchor("Field", kind="FIELD"), anchor("Callable"), ("FIELD_USED_BY_CALLABLE",)),
        LocalFlowSeedProvenance(anchor("Callable"), anchor("Callable"), ("ORIGINAL_MATCH",)),
    )

    result, _repo = build(nodes, edges, [anchor("Callable")], provenance=provenance)
    unit = result.local_units[0]

    assert {(item.original_anchor.nodeKind, item.original_anchor.nodeId, item.expanded_seed.node_id) for item in unit.anchors} == {
        ("TYPE", "Type", "Callable"),
        ("FILE", "File", "Callable"),
        ("FIELD", "Field", "Callable"),
        ("CALLABLE", "Callable", "Callable"),
    }
    assert {item.node_id for item in unit.supporting_context} == {"File", "Type", "Field"}
    assert {(item.from_node_id, item.to_node_id, item.edge_type) for item in unit.execution_transitions} == {("Root", "Callable", "CALLS")}


def test_include_tests_false_excludes_test_domain_flow_content():
    production = node("ProdRoot", entrypoint=True)
    test_root = node("TestRoot", entrypoint=True, domain="TEST")
    shared = node("Anchor")
    edges = [edge("prod-anchor", "ProdRoot", "Anchor"), edge("test-anchor", "TestRoot", "Anchor", domain="TEST")]

    excluded, _repo = build([production, test_root, shared], edges, [anchor("Anchor")], include_tests=False)
    included, _repo = build([production, test_root, shared], edges, [anchor("Anchor")], include_tests=True)

    assert {root.node.node_id for root in excluded.local_units[0].roots} == {"ProdRoot"}
    assert {root.node.node_id for root in included.local_units[0].roots} == {"ProdRoot", "TestRoot"}


def test_stale_cross_revision_anchor_is_skipped_with_diagnostics():
    current = node("Current")
    stale_anchor = anchor("Current").copy(update={"graphRevision": "stale-revision", "graphId": "stale-revision"})

    result, _repo = build([current], [], [stale_anchor])

    assert result.local_units == ()
    assert any(item.code == "LOCAL_FLOW_SEED_NOT_CURRENT" for item in result.diagnostics)


def test_large_branching_graph_truncates_deterministically_and_fails_closed():
    nodes = [node("Anchor", entrypoint=True), *[node(f"Child{i:04d}") for i in range(1600)]]
    edges = [edge(f"edge-{i:04d}", "Anchor", f"Child{i:04d}") for i in range(1600)]

    first, _repo = build(nodes, edges, [anchor("Anchor")])
    second, _repo = build(list(reversed(nodes)), list(reversed(edges)), [anchor("Anchor")])

    assert first.local_units[0].coverage.truncated is True
    assert first.local_units[0].complete is False
    assert len(first.local_units[0].execution_nodes) <= 1500
    assert unit_signature(first.local_units[0]) == unit_signature(second.local_units[0])


def test_wide_fanout_loads_by_frontier_rounds_not_per_child():
    nodes = [node("Anchor", entrypoint=True), *[node(f"Child{i}") for i in range(50)]]
    edges = [edge(f"edge-{i}", "Anchor", f"Child{i}") for i in range(50)]

    result, repo = build(nodes, edges, [anchor("Anchor")])

    assert result.local_units[0].coverage.transition_count == 50
    assert repo.calls["load_outgoing_calls"] <= 2
    assert repo.calls["load_nodes"] <= 4
    assert repo.calls["load_boundaries"] == 1


def test_evidence_hydration_remains_batched_for_edges_nodes_and_boundaries():
    root = node("Root", entrypoint=True)
    child = node("Child")
    call = edge("root-child", "Root", "Child")
    boundary_ev = node_evidence(child)
    required = boundary("required", child, "REQUIRED", evidence_items=(boundary_ev,))
    evidence_items = [evidence(call, 1), evidence(call, 2), node_evidence(root)]

    result, repo = build([root, child], [call], [anchor("Root")], boundaries=[required], evidence_items=evidence_items)
    unit = result.local_units[0]

    assert repo.calls["hydrate_local_units"] == 1
    assert {item.evidence_id for item in unit.evidence} == {"ev-root-child-1", "ev-root-child-2", "ev-node-Root", "ev-node-Child"}
    assert unit.execution_transitions[0].evidence_ids == ("ev-root-child-1", "ev-root-child-2")


def test_configured_multimodule_test_source_root_is_persisted_as_test():
    classifier = FileClassifier.from_config(load_knowledge_defaults()["knowledge"]["file_classification"])
    assert classifier.classify("module/src/test/java/arbitrary/Alpha.java").flow_domain == "TEST"
    assert classifier.classify("module/src/integrationTest/java/arbitrary/Beta.java").flow_domain == "TEST"
