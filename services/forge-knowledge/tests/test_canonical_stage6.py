from __future__ import annotations

from knowledge_service.end_to_end_flow import EndToEndFlowCoverage, EndToEndFlowGraph, EndToEndUnitRef
from knowledge_service.end_to_end_projection import EndToEndProjectionBuilder
from knowledge_service.end_to_end_selection import EndToEndGraphSelector
from knowledge_service.entrypoint_flow_engine import EntrypointFlowOrigin, LocalFlowAnchorProvenance, LocalFlowCoverage, LocalFlowRoot, LocalFlowUnit
from knowledge_service.flow_graph_contract import FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryMatchedNode
from knowledge_service.local_flow_selection import LocalFlowUnitSelector


def node(node_id: str, *, qualified_name: str | None = None, source: str = "source-a") -> FlowGraphNode:
    return FlowGraphNode(
        source_id=source,
        graph_id="graph-a",
        graph_revision="rev-a",
        node_id=node_id,
        stable_key=qualified_name or node_id,
        node_kind="CALLABLE",
        label=qualified_name or node_id,
        qualified_name=qualified_name,
    )


def matched(node_id: str, *, score: float = 0.9) -> KnowledgeQueryMatchedNode:
    return KnowledgeQueryMatchedNode(
        sourceId="source-a",
        nodeId=node_id,
        stableKey=node_id,
        nodeKind="CALLABLE",
        label=node_id,
        score=score,
        matchReasons=["QUERY_EXACT_IDENTIFIER"],
        graphId="graph-a",
        graphRevision="rev-a",
        qualifiedName=node_id,
    )


def unit(unit_id: str, root_name: str, *, score: float = 0.9, source: str = "source-a") -> LocalFlowUnit:
    root = node(root_name, qualified_name=root_name, source=source)
    anchor = LocalFlowAnchorProvenance(
        original_anchor=matched(root_name, score=score),
        expanded_seed=root,
        anchor_to_seed_reasons=("ORIGINAL_MATCH",),
        query_provenance=("QUERY_EXACT_IDENTIFIER",),
        distance_to_nearest_root=0,
    )
    return LocalFlowUnit(
        unit_id=unit_id,
        source_id=source,
        graph_revision="rev-a",
        roots=(LocalFlowRoot(root, EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT, 0),),
        anchors=(anchor,),
        execution_nodes=(root,),
        execution_transitions=(),
        generic_boundaries=(),
        topology_boundaries=(),
        supporting_context=(),
        evidence=(),
        complete=True,
        coverage=LocalFlowCoverage(1, 0, 0, 0, 1, 1, 0),
        diagnostics=(),
    )


def graph(graph_id: str, units: tuple[LocalFlowUnit, ...], query_entries: tuple[str, ...]) -> EndToEndFlowGraph:
    refs = tuple(
        EndToEndUnitRef(
            unit_id=item.unit_id,
            source_id=item.source_id,
            graph_revision=item.graph_revision,
            local_unit=item,
            query_selected_initial=item.unit_id in query_entries,
            recursively_discovered=item.unit_id not in query_entries,
        )
        for item in units
    )
    return EndToEndFlowGraph(
        stable_graph_id=graph_id,
        unit_refs=refs,
        query_entry_unit_ids=query_entries,
        topology_entry_unit_ids=query_entries,
        proven_cross_source_transitions=(),
        open_boundaries=(),
        coverage=EndToEndFlowCoverage(len(units), len({item.source_id for item in units}), len(units), 0, 0, 0, 0, len(query_entries), len(query_entries), 0, 0, 0, True, False),
    )


def test_exact_code_identifier_selects_only_matching_units_and_no_fallback():
    selected = LocalFlowUnitSelector().select(
        [unit("unit-a", "com.example.Match"), unit("unit-b", "com.example.Other")],
        code_identifiers=("com.example.Match",),
    )

    assert selected.selected_unit_ids == ("unit-a",)
    assert selected.rejected_unit_ids == ("unit-b",)


def test_exact_identifier_with_no_match_is_empty():
    selected = LocalFlowUnitSelector().select([unit("unit-a", "com.example.Match")], code_identifiers=("Missing",))

    assert selected.selected_unit_ids == ()
    assert selected.selected_units == ()
    assert selected.rejected_unit_ids == ("unit-a",)


def test_reversed_unit_order_and_duplicates_do_not_change_selection():
    units = [unit("unit-a", "A", score=0.91), unit("unit-b", "B", score=0.9), unit("unit-a", "A", score=0.91)]

    forward = LocalFlowUnitSelector().select(units)
    reverse = LocalFlowUnitSelector().select(tuple(reversed(units)))

    assert forward.selected_unit_ids == reverse.selected_unit_ids == ("unit-a", "unit-b")


def test_graph_selection_omits_whole_components_for_max_flows_deterministically():
    unit_a = unit("unit-a", "A", score=0.95)
    unit_b = unit("unit-b", "B", score=0.75)
    graphs = (graph("graph-b", (unit_b,), ("unit-b",)), graph("graph-a", (unit_a,), ("unit-a",)))

    result = EndToEndGraphSelector().select(
        tuple(reversed(graphs)),
        score_by_unit_id={"unit-a": 0.95, "unit-b": 0.75},
        selected_initial_unit_ids=("unit-a", "unit-b"),
        max_graphs=1,
    )

    assert result.selected_graph_ids == ("graph-a",)
    assert result.omitted_graph_ids == ("graph-b",)
    assert result.truncated is True
    assert any(item["code"] == "END_TO_END_GRAPH_MAX_FLOWS_REACHED" for item in result.diagnostics)


def test_projection_exposes_graphs_not_legacy_flows():
    local = unit("unit-a", "A")
    projected = EndToEndProjectionBuilder().graph(graph("graph-a", (local,), ("unit-a",)))
    payload = projected.dict()

    assert payload["graphId"] == "graph-a"
    assert payload["units"][0]["unitId"] == "unit-a"
    assert "flows" not in payload
