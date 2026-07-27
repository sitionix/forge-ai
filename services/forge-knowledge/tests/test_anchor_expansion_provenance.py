from __future__ import annotations

from collections import defaultdict
from typing import Sequence

from knowledge_service.anchor_expansion_contract import AnchorExpansionBundle, AnchorExpansionEdge, AnchorExpansionNode
from knowledge_service.entrypoint_flow_engine import EntrypointFlow, EntrypointFlowEngine
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode, FlowNodeKey
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode
from knowledge_service.knowledge_query_service import (
    AnchorExpansionService,
    KnowledgeQueryPolicy,
    KnowledgeQueryService,
    QuerySource,
)

SOURCE = "source"
GRAPH = "graph"
REVISION = "revision"


def matched(node_id: str, kind: str, *, score: float = 0.9, reasons: tuple[str, ...] = ("QUERY_ORIGINAL",)) -> KnowledgeQueryMatchedNode:
    return KnowledgeQueryMatchedNode(
        sourceId=SOURCE,
        nodeId=node_id,
        stableKey=f"{SOURCE}:key:{node_id}",
        nodeKind=kind,
        label=node_id,
        score=score,
        matchReasons=list(reasons),
        graphId=GRAPH,
        graphRevision=REVISION,
    )


def expansion_node(node_id: str, kind: str, *, entrypoint_contract: bool = False) -> AnchorExpansionNode:
    return AnchorExpansionNode(
        source_id=SOURCE,
        graph_id=GRAPH,
        graph_revision=REVISION,
        node_id=node_id,
        stable_key=f"{SOURCE}:key:{node_id}",
        node_kind=kind,
        label=node_id,
        entrypoint_contract=entrypoint_contract,
    )


def expansion_edge(edge_id: str, edge_type: str, source_node: str, target_node: str) -> AnchorExpansionEdge:
    return AnchorExpansionEdge(
        source_id=SOURCE,
        graph_id=GRAPH,
        graph_revision=REVISION,
        edge_id=edge_id,
        edge_type=edge_type,
        from_node_id=source_node,
        to_node_id=target_node,
    )


class ExpansionStore:
    def __init__(self, bundle: AnchorExpansionBundle) -> None:
        self.bundle = bundle
        self.requests = []

    def query_anchor_expansion(self, requests):
        self.requests.append(tuple(requests))
        return self.bundle


def source() -> QuerySource:
    return QuerySource(SOURCE, SOURCE, GRAPH, REVISION, 10, 10, "READY")


def expand(candidates: Sequence[KnowledgeQueryMatchedNode], bundle: AnchorExpansionBundle):
    return AnchorExpansionService(ExpansionStore(bundle)).expand(candidates, [source()], KnowledgeQueryPolicy())


def flow_seed_provenance(result):
    service = KnowledgeQueryService(None, None, object())
    return service._flow_seed_provenance(result)


def provenance_signature(result):
    return [(item.original_anchor.nodeId, item.expanded_seed.nodeId, item.anchor_to_seed_reasons) for item in flow_seed_provenance(result)]


def test_type_and_field_mapping_to_same_seed_keep_exact_distinct_reasons():
    bundle = AnchorExpansionBundle(
        nodes=(
            expansion_node("Type", "TYPE"),
            expansion_node("Field", "FIELD"),
            expansion_node("Callable", "CALLABLE"),
        ),
        edges=(
            expansion_edge("type-callable", "DECLARES", "Type", "Callable"),
            expansion_edge("callable-field", "USES_FIELD", "Callable", "Field"),
        ),
    )

    result = expand([matched("Type", "TYPE", score=0.8), matched("Field", "FIELD", score=0.7)], bundle)

    assert provenance_signature(result) == [
        ("Field", "Callable", ("FIELD_USED_BY_CALLABLE",)),
        ("Type", "Callable", ("TYPE_DECLARED_CALLABLE",)),
    ]
    specs = {item.original_anchor.nodeId: item for item in flow_seed_provenance(result)}
    assert specs["Type"].original_anchor.score == 0.8
    assert specs["Field"].original_anchor.score == 0.7
    assert specs["Type"].original_anchor.matchReasons == ["QUERY_ORIGINAL"]


def test_file_and_type_mapping_to_same_seed_do_not_exchange_reasons():
    bundle = AnchorExpansionBundle(
        nodes=(
            expansion_node("File", "FILE"),
            expansion_node("Type", "TYPE"),
            expansion_node("Callable", "CALLABLE"),
        ),
        edges=(
            expansion_edge("file-callable", "DECLARES", "File", "Callable"),
            expansion_edge("type-callable", "DECLARES", "Type", "Callable"),
        ),
    )

    result = expand([matched("File", "FILE"), matched("Type", "TYPE")], bundle)

    assert provenance_signature(result) == [
        ("File", "Callable", ("FILE_DECLARED_NODE",)),
        ("Type", "Callable", ("TYPE_DECLARED_CALLABLE",)),
    ]


def test_one_anchor_mapping_to_several_seeds_preserves_reason_per_seed_and_deduplicates_exact_records():
    bundle = AnchorExpansionBundle(
        nodes=(
            expansion_node("Type", "TYPE"),
            expansion_node("CallableA", "CALLABLE"),
            expansion_node("CallableB", "CALLABLE"),
        ),
        edges=(
            expansion_edge("type-a-1", "DECLARES", "Type", "CallableA"),
            expansion_edge("type-a-2", "DECLARES", "Type", "CallableA"),
            expansion_edge("type-b", "DECLARES", "Type", "CallableB"),
        ),
    )

    result = expand([matched("Type", "TYPE")], bundle)

    assert provenance_signature(result) == [
        ("Type", "CallableA", ("TYPE_DECLARED_CALLABLE",)),
        ("Type", "CallableB", ("TYPE_DECLARED_CALLABLE",)),
    ]


def test_several_reasons_for_one_exact_anchor_seed_pair_are_preserved():
    bundle = AnchorExpansionBundle(
        nodes=(expansion_node("Callable", "CALLABLE"),),
        edges=(expansion_edge("self-override", "OVERRIDES", "Callable", "Callable"),),
    )

    result = expand([matched("Callable", "CALLABLE")], bundle)

    assert provenance_signature(result) == [
        ("Callable", "Callable", ("CALLABLE_OVERRIDE_IMPLEMENTATION", "ORIGINAL_MATCH")),
    ]


def test_reversing_anchor_expansion_order_keeps_identical_exact_provenance():
    nodes = (
        expansion_node("Type", "TYPE"),
        expansion_node("Field", "FIELD"),
        expansion_node("Callable", "CALLABLE"),
    )
    edges = (
        expansion_edge("type-callable", "DECLARES", "Type", "Callable"),
        expansion_edge("callable-field", "USES_FIELD", "Callable", "Field"),
    )

    first = expand([matched("Type", "TYPE"), matched("Field", "FIELD")], AnchorExpansionBundle(nodes=nodes, edges=edges))
    second = expand(
        [matched("Field", "FIELD"), matched("Type", "TYPE")],
        AnchorExpansionBundle(nodes=tuple(reversed(nodes)), edges=tuple(reversed(edges))),
    )

    assert provenance_signature(first) == provenance_signature(second)


def graph_node(node_id: str, *, entrypoint: bool = False, kind: str = "CALLABLE") -> FlowGraphNode:
    return FlowGraphNode(
        source_id=SOURCE,
        graph_id=GRAPH,
        graph_revision=REVISION,
        node_id=node_id,
        stable_key=f"{SOURCE}:key:{node_id}",
        node_kind=kind,
        label=node_id,
        entrypoint=entrypoint,
        execution_role="EXECUTABLE" if entrypoint else None,
    )


def call(edge_id: str, source_node: str, target_node: str) -> FlowGraphEdge:
    return FlowGraphEdge(
        source_id=SOURCE,
        graph_id=GRAPH,
        graph_revision=REVISION,
        edge_id=edge_id,
        edge_type="CALLS",
        from_node_id=source_node,
        to_node_id=target_node,
        resolution_status="RESOLVED",
    )


class FlowStore:
    def __init__(self, nodes: Sequence[FlowGraphNode], edges: Sequence[FlowGraphEdge]) -> None:
        self.nodes = {(item.source_id, item.graph_revision or item.graph_id, item.node_id): item for item in nodes}
        self.edges = tuple(edges)

    def load_nodes(self, node_keys: set[FlowNodeKey], *, include_tests: bool) -> dict[FlowNodeKey, FlowGraphNode]:
        result = {}
        for requested in node_keys:
            for key, item in self.nodes.items():
                if key[0] == requested[0] and key[2] == requested[2] and requested[1] in {item.graph_id, item.graph_revision or ""}:
                    result[key] = item
        return result

    def load_incoming_calls(self, target_keys: set[FlowNodeKey], *, include_tests: bool):
        result = defaultdict(list)
        for item in self.edges:
            key = (item.source_id, item.graph_revision or item.graph_id, item.to_node_id)
            if key in target_keys:
                result[key].append(item)
        return {key: tuple(value) for key, value in result.items()}

    def load_outgoing_calls(self, source_keys: set[FlowNodeKey], *, include_tests: bool):
        result = defaultdict(list)
        for item in self.edges:
            key = (item.source_id, item.graph_revision or item.graph_id, item.from_node_id)
            if key in source_keys:
                result[key].append(item)
        return {key: tuple(value) for key, value in result.items()}

    def load_boundaries(self, node_keys: set[FlowNodeKey], *, include_tests: bool):
        return {}

    def hydrate_evidence(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]:
        return tuple(flows)

    def metrics(self):
        return {}


def test_real_expansion_service_to_flow_engine_preserves_contextual_anchor_reasons_without_manual_provenance():
    nodes = (
        expansion_node("Type", "TYPE"),
        expansion_node("Field", "FIELD"),
        expansion_node("Callable", "CALLABLE"),
        expansion_node("Root", "CALLABLE"),
    )
    edges = (
        expansion_edge("type-callable", "DECLARES", "Type", "Callable"),
        expansion_edge("callable-field", "USES_FIELD", "Callable", "Field"),
    )
    first_expansion = expand([matched("Type", "TYPE"), matched("Field", "FIELD")], AnchorExpansionBundle(nodes=nodes, edges=edges))
    second_expansion = expand(
        [matched("Field", "FIELD"), matched("Type", "TYPE")],
        AnchorExpansionBundle(nodes=tuple(reversed(nodes)), edges=tuple(reversed(edges))),
    )

    repository = FlowStore(
        [
            graph_node("Root", entrypoint=True),
            graph_node("Type", kind="TYPE"),
            graph_node("Field", kind="FIELD"),
            graph_node("Callable"),
        ],
        [call("root-callable", "Root", "Callable")],
    )
    first = EntrypointFlowEngine(repository).build(
        first_expansion.flow_seed_nodes,
        max_flows=10,
        include_tests=False,
        anchor_seed_provenance=flow_seed_provenance(first_expansion),
    )
    second = EntrypointFlowEngine(
        FlowStore(
            [
                graph_node("Root", entrypoint=True),
                graph_node("Type", kind="TYPE"),
                graph_node("Field", kind="FIELD"),
                graph_node("Callable"),
            ],
            [call("root-callable", "Root", "Callable")],
        )
    ).build(
        second_expansion.flow_seed_nodes,
        max_flows=10,
        include_tests=False,
        anchor_seed_provenance=flow_seed_provenance(second_expansion),
    )

    anchors = {(item.original_anchor.nodeId, item.expanded_seed.node_id): item.anchor_to_seed_reasons for item in first.local_units[0].anchors}
    assert anchors == {
        ("Field", "Callable"): ("FIELD_USED_BY_CALLABLE",),
        ("Type", "Callable"): ("TYPE_DECLARED_CALLABLE",),
    }
    assert first.local_units[0].unit_id == second.local_units[0].unit_id
    assert [(item.original_anchor.nodeId, item.expanded_seed.node_id, item.anchor_to_seed_reasons) for item in first.local_units[0].anchors] == [
        (item.original_anchor.nodeId, item.expanded_seed.node_id, item.anchor_to_seed_reasons) for item in second.local_units[0].anchors
    ]
