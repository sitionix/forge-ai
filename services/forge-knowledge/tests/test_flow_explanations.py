from __future__ import annotations

import json

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_explanations import FlowExplanationContextPacker, FlowExplanationValidator
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest


SOURCE = "flow-explanation-source"
REVISION = "flow-explanation-revision"


def node(node_id: str, *, entrypoint: bool = False) -> FlowGraphNode:
    return FlowGraphNode(
        source_id=SOURCE,
        graph_id=REVISION,
        graph_revision=REVISION,
        node_id=node_id,
        stable_key=node_id,
        node_kind="CALLABLE",
        label=node_id,
        qualified_name=node_id,
        entrypoint=entrypoint,
    )


def edge(edge_id: str, source: str, target: str | None, *, status: str = "RESOLVED") -> FlowGraphEdge:
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
        unresolved_target={"name": f"Boundary{edge_id}"} if target is None else None,
    )


def flow(nodes: list[FlowGraphNode], transitions: list[FlowGraphEdge], boundaries: list[FlowGraphEdge] | None = None) -> EntrypointFlow:
    root = nodes[0]
    return EntrypointFlow(
        key=EntrypointFlowKey(SOURCE, REVISION, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=tuple(nodes),
        transitions=tuple(transitions),
        boundary_transitions=tuple(boundaries or ()),
        evidence=(),
        complete=True,
        coverage=EntrypointFlowCoverage(len(nodes), len(transitions), len(boundaries or ()), 1, 1),
        diagnostics=(),
        relevance_score=1.0,
    )


def valid_response_for(context):
    steps = context.llm_input["steps"]
    transitions = context.llm_input["transitions"]
    boundaries = context.llm_input["boundaries"]
    return {
        "title": "Graph flow explanation",
        "narrative": [
            {
                "text": "This graph slice is described through node references, transition references, and boundary references without adding a path order.",
                "nodeRefs": [item["nodeRef"] for item in steps],
                "transitionRefs": [item["transitionRef"] for item in transitions],
                "boundaryRefs": [item["boundaryRef"] for item in boundaries],
            },
            {
                "text": "The second grounded block explains that branches, shared descendants, and cycles remain graph structure rather than sequential steps.",
                "nodeRefs": [item["nodeRef"] for item in steps],
                "transitionRefs": [item["transitionRef"] for item in transitions],
                "boundaryRefs": [item["boundaryRef"] for item in boundaries],
            },
        ],
        "steps": [
            {
                "nodeRef": step["nodeRef"],
                "explanation": f"`{step['symbol']}` is a node in the entrypoint-rooted graph.",
                "transitionRefs": [
                    item["transitionRef"]
                    for item in transitions
                    if item["fromNodeRef"] == step["nodeRef"]
                ],
                "evidenceRefs": [],
            }
            for step in steps
        ],
        "transitions": [
            {
                "transitionRef": item["transitionRef"],
                "explanation": f"`{item['fromSymbol']}` has a CALLS transition to `{item['toSymbol']}`.",
                "evidenceRefs": [],
            }
            for item in transitions
        ],
        "boundaries": [
            {
                "boundaryRef": item["boundaryRef"],
                "kind": item["kind"],
                "explanation": f"{item['kind']} remains a boundary in this graph slice.",
                "evidenceRefs": [],
            }
            for item in boundaries
        ],
    }


def assert_validator_accepts_graph(graph_flow: EntrypointFlow) -> None:
    context = FlowExplanationContextPacker().pack(
        request=KnowledgeQueryRequest(queryText="Alpha", intent="FLOW_EXPLANATION"),
        flow=graph_flow,
        flow_index=1,
        source_display_name=SOURCE,
    )
    explanation, errors, code = FlowExplanationValidator().validate(json.dumps(valid_response_for(context)), context)
    assert explanation is not None, (code, errors)


def test_validator_accepts_sibling_branch_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True), node("Beta"), node("Gamma"), node("Delta")],
            [edge("ab", "Alpha", "Beta"), edge("ag", "Alpha", "Gamma"), edge("ad", "Alpha", "Delta")],
        )
    )


def test_validator_accepts_diamond_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True), node("Beta"), node("Gamma"), node("Delta")],
            [edge("ab", "Alpha", "Beta"), edge("ag", "Alpha", "Gamma"), edge("bd", "Beta", "Delta"), edge("gd", "Gamma", "Delta")],
        )
    )


def test_validator_accepts_cycle_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True), node("Beta"), node("Gamma")],
            [edge("ab", "Alpha", "Beta"), edge("bg", "Beta", "Gamma"), edge("gb", "Gamma", "Beta")],
        )
    )


def test_validator_accepts_external_boundary_graph_refs():
    assert_validator_accepts_graph(
        flow(
            [node("Alpha", entrypoint=True)],
            [],
            [edge("outside", "Alpha", None, status="EXTERNAL_TARGET")],
        )
    )
