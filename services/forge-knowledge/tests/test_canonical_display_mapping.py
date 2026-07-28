from __future__ import annotations

from dataclasses import replace

import pytest
from test_local_flow_unit_engine import anchor as matched_anchor
from test_local_flow_unit_engine import boundary, edge, node

from knowledge_service.canonical_narration_contract import NarrationClauseKind
from knowledge_service.canonical_narration_planner import CanonicalNarrationPlanner
from knowledge_service.canonical_narration_strategies import (
    CanonicalDisplayValueCollector,
    CanonicalDisplayValueConflictError,
    _anchor_ref,
    _context_ref,
    _edge_ref,
    _evidence_ref,
    _generic_boundary_ref,
    _node_ref,
    _root_ref,
    _topology_boundary_ref,
    _unit_display_values,
)
from knowledge_service.end_to_end_flow import EndToEndFlowCoverage, EndToEndFlowGraph, EndToEndUnitRef
from knowledge_service.flow_graph_contract import FlowGraphEvidence
from knowledge_service.formatter_placeholders import validate_clause_placeholders
from knowledge_service.local_flow_unit_engine import (
    LocalFlowAnchorProvenance,
    LocalFlowCoverage,
    LocalFlowRoot,
    LocalFlowRootOrigin,
    LocalFlowUnit,
)


@pytest.mark.parametrize(
    ("pairs", "description"),
    [
        (lambda unit: [(_root_ref(unit.roots[0]), "Root Z"), (_root_ref(unit.roots[1]), "Root A")], "roots"),
        (lambda unit: [(_anchor_ref(unit.anchors[0]), "Seed Z"), (_anchor_ref(unit.anchors[1]), "Seed A")], "anchors"),
        (lambda unit: [(_node_ref(unit.execution_nodes[0]), "Exec Z"), (_node_ref(unit.execution_nodes[1]), "Exec A")], "execution nodes"),
        (
            lambda unit: [(_edge_ref(unit.execution_transitions[0]), "target-transition-z"), (_edge_ref(unit.execution_transitions[1]), "target-transition-a")],
            "local transitions",
        ),
        (
            lambda unit: [
                (_topology_boundary_ref(unit.topology_boundaries[0]), "target-topology-z"),
                (_topology_boundary_ref(unit.topology_boundaries[1]), "target-topology-a"),
            ],
            "topology boundaries",
        ),
        (
            lambda unit: [
                (_generic_boundary_ref(unit.generic_boundaries[0]), "source-a:boundary:boundary-z"),
                (_generic_boundary_ref(unit.generic_boundaries[1]), "source-a:boundary:boundary-a"),
            ],
            "generic boundaries",
        ),
        (
            lambda unit: [(_context_ref(unit.supporting_context[0]), "Context Z"), (_context_ref(unit.supporting_context[1]), "Context A")],
            "supporting context",
        ),
        (
            lambda unit: [(_evidence_ref(unit.evidence[0]), "src/Z.java:20"), (_evidence_ref(unit.evidence[1]), "src/A.java:10")],
            "evidence",
        ),
    ],
    ids=[
        "roots-stored-order-differs-from-ref-order",
        "anchors-score-order-differs-from-identity-order",
        "execution-node-order-differs-from-ref-order",
        "local-transition-order-differs-from-ref-order",
        "topology-boundary-order-differs-from-ref-order",
        "generic-boundary-order-differs-from-ref-order",
        "supporting-context-order-differs-from-ref-order",
        "evidence-order-differs-from-ref-order",
    ],
)
def test_unit_display_values_map_each_ref_from_the_same_object(pairs, description):
    del description
    unit = _display_unit()
    values = _unit_display_values(unit)

    for ref, expected_display in pairs(unit):
        assert values[ref] == expected_display


def test_reversed_unit_collections_produce_same_ref_display_map():
    unit = _display_unit()
    reversed_unit = replace(
        unit,
        roots=tuple(reversed(unit.roots)),
        anchors=tuple(reversed(unit.anchors)),
        execution_nodes=tuple(reversed(unit.execution_nodes)),
        execution_transitions=tuple(reversed(unit.execution_transitions)),
        generic_boundaries=tuple(reversed(unit.generic_boundaries)),
        topology_boundaries=tuple(reversed(unit.topology_boundaries)),
        supporting_context=tuple(reversed(unit.supporting_context)),
        evidence=tuple(reversed(unit.evidence)),
    )

    assert _unit_display_values(reversed_unit) == _unit_display_values(unit)


def test_conflicting_display_values_for_one_ref_fail_closed():
    collector = CanonicalDisplayValueCollector()
    collector.add("node:source-a:revision-current:conflict", "First")

    with pytest.raises(CanonicalDisplayValueConflictError):
        collector.add("node:source-a:revision-current:conflict", "Second")


def test_duplicate_identical_ref_display_pairs_deduplicate_safely():
    collector = CanonicalDisplayValueCollector()

    collector.add("node:source-a:revision-current:duplicate", "Duplicate")
    collector.add("node:source-a:revision-current:duplicate", "Duplicate")

    assert collector.build() == {"node:source-a:revision-current:duplicate": "Duplicate"}


def test_blank_display_value_uses_canonical_ref_fallback():
    collector = CanonicalDisplayValueCollector()

    collector.add("node:source-a:revision-current:fallback", "")

    assert collector.build() == {"node:source-a:revision-current:fallback": "fallback"}


def test_rendered_placeholders_use_exact_object_display_value_not_neighbouring_value():
    unit = _display_unit()
    plan = CanonicalNarrationPlanner().plan(_display_graph(unit), response_language="en")
    root_clause = next(clause for clause in plan.clauses if clause.clause_kind is NarrationClauseKind.UNIT_ROOTS)
    root_z_ref = _root_ref(unit.roots[0])

    result = validate_clause_placeholders(f"Selected {{{{ref:{root_z_ref}}}}}.", [root_z_ref], root_clause)

    assert result.errors == ()
    assert result.rendered_text == "Selected Root Z."
    assert "Root A" not in result.rendered_text


def _display_unit() -> LocalFlowUnit:
    owner = _named_node("owner", "Owner")
    roots = (
        LocalFlowRoot(node=_named_node("z-root", "Root Z"), origin=LocalFlowRootOrigin.EXPLICIT_GRAPH_FACT, distance_to_nearest_seed=0),
        LocalFlowRoot(node=_named_node("a-root", "Root A"), origin=LocalFlowRootOrigin.EXPLICIT_GRAPH_FACT, distance_to_nearest_seed=0),
    )
    anchors = (
        LocalFlowAnchorProvenance(
            original_anchor=matched_anchor("z-anchor", score=0.99, source="source-a"),
            expanded_seed=_named_node("z-seed", "Seed Z"),
            anchor_to_seed_reasons=("score-order",),
            query_provenance=("query",),
            distance_to_nearest_root=0,
        ),
        LocalFlowAnchorProvenance(
            original_anchor=matched_anchor("a-anchor", score=0.1, source="source-a"),
            expanded_seed=_named_node("a-seed", "Seed A"),
            anchor_to_seed_reasons=("identity-order",),
            query_provenance=("query",),
            distance_to_nearest_root=0,
        ),
    )
    execution_nodes = (_named_node("z-exec", "Exec Z"), _named_node("a-exec", "Exec A"))
    execution_transitions = (
        edge("transition-z", "owner", "target-transition-z", source="source-a"),
        edge("transition-a", "owner", "target-transition-a", source="source-a"),
    )
    topology_boundaries = (
        edge("topology-z", "owner", None, source="source-a", status="UNRESOLVED"),
        edge("topology-a", "owner", None, source="source-a", status="UNRESOLVED"),
    )
    generic_boundaries = (
        boundary("boundary-z", owner, "REQUIRED"),
        boundary("boundary-a", owner, "REQUIRED"),
    )
    supporting_context = (_named_node("z-context", "Context Z"), _named_node("a-context", "Context A"))
    evidence = (
        _evidence("ev-z", "src/Z.java", 20),
        _evidence("ev-a", "src/A.java", 10),
    )
    return LocalFlowUnit(
        unit_id="unit-display",
        source_id="source-a",
        graph_revision="revision-current",
        roots=roots,
        anchors=anchors,
        execution_nodes=execution_nodes,
        execution_transitions=execution_transitions,
        generic_boundaries=generic_boundaries,
        topology_boundaries=topology_boundaries,
        supporting_context=supporting_context,
        evidence=evidence,
        complete=True,
        coverage=LocalFlowCoverage(
            node_count=len(execution_nodes),
            transition_count=len(execution_transitions),
            generic_boundary_count=len(generic_boundaries),
            topology_boundary_count=len(topology_boundaries),
            anchor_count=len(anchors),
            root_count=len(roots),
            max_depth_reached=0,
        ),
        diagnostics=(),
    )


def _display_graph(unit: LocalFlowUnit) -> EndToEndFlowGraph:
    return EndToEndFlowGraph(
        stable_graph_id="graph-display",
        unit_refs=(
            EndToEndUnitRef(
                unit_id=unit.unit_id,
                source_id=unit.source_id,
                graph_revision=unit.graph_revision,
                local_unit=unit,
                query_selected_initial=True,
                recursively_discovered=False,
            ),
        ),
        query_entry_unit_ids=(unit.unit_id,),
        topology_entry_unit_ids=(unit.unit_id,),
        proven_cross_source_transitions=(),
        open_boundaries=(),
        coverage=EndToEndFlowCoverage(
            unit_count=1,
            source_count=1,
            local_node_count=len(unit.execution_nodes),
            local_execution_transition_count=len(unit.execution_transitions),
            proven_cross_source_transition_count=0,
            open_ambiguous_boundary_count=0,
            open_unresolved_boundary_count=0,
            query_entry_unit_count=1,
            topology_entry_unit_count=1,
            cycle_count=0,
            orphan_resolution_count=0,
            missing_unit_mapping_count=0,
            complete=True,
            truncated=False,
        ),
    )


def _named_node(node_id: str, display: str):
    return replace(node(node_id, source="source-a"), stable_key=f"source-a:key:{node_id}", label=display, qualified_name=display)


def _evidence(evidence_id: str, relative_path: str, line_start: int) -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id="source-a",
        graph_id="revision-current",
        graph_revision="revision-current",
        evidence_id=evidence_id,
        node_id=None,
        edge_id=None,
        relative_path=relative_path,
        line_start=line_start,
        line_end=line_start,
        text="evidence",
    )
