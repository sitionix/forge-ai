from __future__ import annotations

import time
from dataclasses import replace
from types import SimpleNamespace

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.flow_narrative import FlowNarrativePlanner
from knowledge_service.flow_walkthrough import (
    DeterministicFlowWalkthroughAnswerService,
    FlowMessageCatalog,
    FlowWalkthroughPlanner,
)
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.operation_facts import AvailableOperationFact
from knowledge_service.query_interpretation import QueryRetrievalPlan


REVISION = "graph-current"


def _node(
    node_id: str,
    *,
    source: str = "source-a",
    role: str = "EXECUTABLE",
    entrypoint: bool = False,
    method: str | None = None,
    route: str | None = None,
    summary: str | None = None,
    kind: str | None = None,
    topic: str | None = None,
    schedule: str | None = None,
) -> FlowGraphNode:
    return FlowGraphNode(
        source_id=source,
        graph_id=f"{source}:{REVISION}",
        graph_revision=f"{source}:{REVISION}",
        node_id=node_id,
        stable_key=f"{source}:{node_id}",
        node_kind="CALLABLE",
        label=node_id,
        qualified_name=f"{source}.{node_id}",
        relative_path=f"src/{source}/{node_id}.txt",
        line_start=1,
        line_end=1,
        summary=summary,
        entrypoint=entrypoint,
        entrypoint_kind=kind or ("HTTP" if method or route else None),
        entrypoint_http_method=method,
        entrypoint_route=route,
        entrypoint_topic=topic,
        entrypoint_schedule=schedule,
        execution_role=role,
    )


def _edge(
    edge_id: str,
    source_node: FlowGraphNode,
    target_node: FlowGraphNode | None,
    *,
    line: int = 1,
    status: str = "RESOLVED",
    target: str | None = None,
) -> FlowGraphEdge:
    return FlowGraphEdge(
        source_id=source_node.source_id,
        graph_id=source_node.graph_id,
        graph_revision=source_node.graph_revision,
        edge_id=edge_id,
        edge_type="CALLS",
        from_node_id=source_node.node_id,
        to_node_id=target_node.node_id if target_node is not None else None,
        resolution_status=status,
        to_source_id=target_node.source_id if target_node is not None and target_node.source_id != source_node.source_id else None,
        to_graph_id=target_node.graph_id if target_node is not None and target_node.source_id != source_node.source_id else None,
        to_graph_revision=target_node.graph_revision if target_node is not None and target_node.source_id != source_node.source_id else None,
        unresolved_target={"name": target or "Missing.target"} if target_node is None else None,
        evidence_ids=(f"ev-{edge_id}",),
    )


def _evidence(edge: FlowGraphEdge, line: int) -> FlowGraphEvidence:
    return FlowGraphEvidence(
        source_id=edge.source_id,
        graph_id=edge.graph_id,
        graph_revision=edge.graph_revision,
        evidence_id=f"ev-{edge.edge_id}",
        node_id=None,
        edge_id=edge.edge_id,
        relative_path="src/Neutral.txt",
        line_start=line,
        line_end=line,
        text="not rendered",
    )


def _flow(
    root: FlowGraphNode,
    nodes: tuple[FlowGraphNode, ...],
    edges: tuple[FlowGraphEdge, ...] = (),
    boundaries: tuple[FlowGraphEdge, ...] = (),
    evidence: tuple[FlowGraphEvidence, ...] = (),
) -> EntrypointFlow:
    return EntrypointFlow(
        key=EntrypointFlowKey(root.source_id, root.graph_revision or root.graph_id, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=nodes,
        transitions=edges,
        boundary_transitions=boundaries,
        evidence=evidence,
        complete=True,
        coverage=EntrypointFlowCoverage(len(nodes), len(edges), len(boundaries), 1, len(nodes)),
        diagnostics=(),
        relevance_score=1.0,
    )


def _fact(
    node: FlowGraphNode,
    *,
    role: str | None = None,
    transport: str = "HTTP",
    method: str | None = None,
    route: str | None = None,
    topic: str | None = None,
    schedule: str | None = None,
    target_service: str | None = None,
) -> AvailableOperationFact:
    return AvailableOperationFact(
        owner_source_id=node.source_id,
        owner_graph_id=node.graph_id,
        owner_graph_revision=node.graph_revision,
        owner_node_id=node.node_id,
        source_id=node.source_id,
        execution_role=role or node.execution_role,
        transport_kind=transport,
        direction_role=None,
        method=method or node.entrypoint_http_method,
        normalized_route=route or node.entrypoint_route,
        topic=topic,
        schedule=schedule,
        target_service_identity=target_service,
        owner_qualified_name=node.qualified_name,
    )


def _families(*flows: EntrypointFlow):
    return FlowFamilyAssembler().rank(FlowFamilyAssembler().assemble(flows).families)


def _plans(*flows: EntrypointFlow, facts: tuple[AvailableOperationFact, ...] = ()):
    return FlowNarrativePlanner().assemble(_families(*flows), max_plans=10, operation_facts=facts)[0]


def _retrieval_plan(language: str = "en") -> QueryRetrievalPlan:
    return QueryRetrievalPlan(
        original_query="neutral flow",
        normalized_query="neutral flow",
        search_queries=("neutral flow",),
        code_identifiers=(),
        concepts=(),
        effective_intent="FLOW_EXPLANATION",
        detected_language=language,
        response_language=language,
    )


def _answer(plans, language: str = "en"):
    service = DeterministicFlowWalkthroughAnswerService()
    result = service.answer(
        KnowledgeQueryRequest(queryText="neutral flow"),
        SimpleNamespace(narrative_plans=tuple(plans)),
        plan=_retrieval_plan(language),
    )
    return service.to_response(result), result.metrics


def test_linear_flow_renders_ordered_numbered_text_and_zero_model_calls():
    root = _node("Root.start", entrypoint=True, method="POST", route="/neutral")
    first = _node("OperationA.run", summary="Transforms the request.")
    second = _node("OperationB.finish", summary="Returns the stored status.")
    e1 = _edge("root-first", root, first, line=10)
    e2 = _edge("first-second", first, second, line=20)

    response, metrics = _answer(_plans(_flow(root, (root, first, second), (e1, e2), evidence=(_evidence(e1, 10), _evidence(e2, 20)))))

    text = response.answers[0].text
    assert text.startswith("1. The entrypoint Root.start")
    assert "2. Execution reaches OperationA.run" in text
    assert "3. Execution reaches OperationB.finish" in text
    assert "Transforms the request." in text
    assert "Returns the stored status." in text
    assert metrics["finalAnswerProviderCallCount"] == 0
    assert metrics["groundingProviderCallCount"] == 0


def test_partial_flow_boundary_returns_readable_available_end():
    root = _node("Root.start", entrypoint=True)
    operation = _node("OperationA.run")
    e1 = _edge("root-operation", root, operation)
    boundary = _edge("operation-missing", operation, None, status="UNRESOLVED", target="Missing.operation")

    response, _metrics = _answer(_plans(_flow(root, (root, operation), (e1,), boundaries=(boundary,))))

    text = response.answers[0].text
    assert "OperationA.run" in text
    assert "not present in the current facts" in text
    assert "The available facts end" in text


def test_duplicate_unresolved_boundaries_are_grouped_per_owner():
    root = _node("Root.start", entrypoint=True)
    operation = _node("OperationA.run")
    e1 = _edge("root-operation", root, operation)
    first_boundary = _edge("operation-missing-a", operation, None, status="UNRESOLVED", target="Missing.first")
    second_boundary = _edge("operation-missing-b", operation, None, status="UNRESOLVED", target="Missing.second")

    response, _metrics = _answer(_plans(_flow(root, (root, operation), (e1,), boundaries=(first_boundary, second_boundary))))

    text = response.answers[0].text
    assert text.count("The next target from OperationA.run") == 1


def test_two_exact_fragments_render_unverified_gap_between_verified_fragments():
    outbound = _node("Client.call", source="client", role="CLIENT_OPERATION", entrypoint=True, method="POST", route="/items")
    inbound = _node("Handler.receive", source="service", role="EXECUTABLE", entrypoint=True, method="POST", route="/items")

    response, _metrics = _answer(_plans(_flow(outbound, (outbound,)), _flow(inbound, (inbound,)), facts=(_fact(outbound), _fact(inbound))))

    text = response.answers[0].text
    assert text.index("Client.call") < text.index("not verified") < text.rindex("Handler.receive")
    assert "direct relation is not verified" in text


def test_ambiguous_gap_does_not_select_a_target_or_fabricate_continuation():
    outbound = _node("Client.call", source="client", role="CLIENT_OPERATION", entrypoint=True, method="POST", route="/items")
    target_a = _node("HandlerA.receive", source="service-a", role="EXECUTABLE", entrypoint=True, method="POST", route="/items")
    target_b = _node("HandlerB.receive", source="service-b", role="EXECUTABLE", entrypoint=True, method="POST", route="/items")

    response, _metrics = _answer(_plans(
        _flow(outbound, (outbound,)),
        _flow(target_a, (target_a,)),
        _flow(target_b, (target_b,)),
        facts=(_fact(outbound), _fact(target_a), _fact(target_b)),
    ))

    first_answer = response.answers[0].text
    assert "multiple matching targets" in first_answer
    assert "so no continuation is selected" in first_answer


def test_branch_structure_uses_nested_numbering_without_false_sequence():
    root = _node("Root.start", entrypoint=True)
    left = _node("Left.run")
    right = _node("Right.run")
    e_left = _edge("root-left", root, left, line=20)
    e_right = _edge("root-right", root, right, line=10)

    response, _metrics = _answer(_plans(_flow(root, (root, left, right), (e_left, e_right), evidence=(_evidence(e_left, 20), _evidence(e_right, 10)))))

    text = response.answers[0].text
    assert "2. From Root.start" in text
    assert "2.1. One branch reaches Right.run" in text
    assert "2.2. One branch reaches Left.run" in text
    assert text.index("Right.run") < text.index("Left.run")


def test_cycle_is_shown_once_without_infinite_traversal():
    root = _node("Root.start", entrypoint=True)
    operation = _node("OperationA.run")
    e1 = _edge("root-operation", root, operation)
    e2 = _edge("operation-root", operation, root)

    response, metrics = _answer(_plans(_flow(root, (root, operation), (e1, e2))))

    assert "cycle is shown once" in response.answers[0].text
    assert metrics["walkthroughStepCount"] < 10


def test_shared_node_is_explained_once_and_rejoin_is_indicated():
    root = _node("Root.start", entrypoint=True)
    left = _node("Left.run")
    right = _node("Right.run")
    shared = _node("Shared.finish")
    edges = (
        _edge("root-left", root, left, line=10),
        _edge("root-right", root, right, line=20),
        _edge("left-shared", left, shared, line=30),
        _edge("right-shared", right, shared, line=40),
    )

    response, _metrics = _answer(_plans(_flow(root, (root, left, right, shared), edges)))

    text = response.answers[0].text
    assert text.count("Execution reaches Shared.finish") == 1
    assert "already described" in text


def test_structural_only_facts_render_without_summary_or_invented_behavior():
    root = _node("Root.start", entrypoint=True)

    response, _metrics = _answer(_plans(_flow(root, (root,))))

    text = response.answers[0].text
    assert "The walkthrough starts at Root.start" in text
    assert "success" not in text.lower()


def test_persisted_summary_is_included_once_with_owner_and_evidence_is_not_rendered():
    root = _node("Root.start", entrypoint=True, summary="Uses the accepted compact summary.")

    response, _metrics = _answer(_plans(_flow(root, (root,), evidence=(FlowGraphEvidence(
        root.source_id,
        root.graph_id,
        root.graph_revision,
        "ev-root",
        root.node_id,
        None,
        "src/Neutral.txt",
        1,
        1,
        "raw evidence excerpt",
    ),))))

    text = response.answers[0].text
    assert text.count("Uses the accepted compact summary.") == 1
    assert "raw evidence excerpt" not in text
    assert "evidence" not in text.lower()


def test_identical_symbols_from_different_sources_keep_source_ownership_visible():
    root = _node("Same.run", source="source-a", entrypoint=True)
    target = _node("Same.run", source="source-b")
    edge = _edge("a-b", root, target)

    response, _metrics = _answer(_plans(_flow(root, (root, target), (edge,))))

    text = response.answers[0].text
    assert "Same.run (source-a)" in text
    assert "Same.run (source-b)" in text


def test_two_independent_entrypoints_produce_two_answer_items():
    first = _node("First.start", source="source-a", entrypoint=True)
    second = _node("Second.start", source="source-b", entrypoint=True)

    response, metrics = _answer(_plans(_flow(first, (first,)), _flow(second, (second,))))

    assert [answer.entrypoint for answer in response.answers] == ["First.start", "Second.start"]
    assert metrics["answerCount"] == 2


def test_non_http_typed_flow_uses_same_generic_renderer():
    root = _node("Schedule.run", entrypoint=True, kind="SCHEDULE", schedule="0 0 * * *")
    operation = _node("Publisher.send")
    edge = _edge("schedule-publish", root, operation)
    facts = (_fact(root, transport="SCHEDULE", method=None, route=None, schedule="0 0 * * *"),)

    response, _metrics = _answer(_plans(_flow(root, (root, operation), (edge,)), facts=facts))

    text = response.answers[0].text
    assert "The walkthrough starts at Schedule.run" in text
    assert "HTTP" not in text


def test_large_flow_plans_and_renders_without_context_budget_or_model_calls():
    count = 2000
    root = _node("Root.start", entrypoint=True)
    nodes = [root]
    edges = []
    previous = root
    for index in range(count):
        current = _node(f"Step{index:04d}.run")
        nodes.append(current)
        edges.append(_edge(f"edge-{index:04d}", previous, current, line=index + 1))
        previous = current

    plans = _plans(_flow(root, tuple(nodes), tuple(edges)))
    started = time.perf_counter()
    response, metrics = _answer(plans)
    elapsed = time.perf_counter() - started

    assert response.answers[0].text.startswith("1. The walkthrough starts at Root.start")
    assert "Step1999.run" in response.answers[0].text
    assert metrics["finalAnswerProviderCallCount"] == 0
    assert metrics["groundingProviderCallCount"] == 0
    assert "context budget" not in response.answers[0].text.lower()
    assert elapsed < 2.0


def test_message_catalog_falls_back_without_planner_language_branching():
    assert FlowMessageCatalog().resolve_language("uk")[0] == "uk"
    used, diagnostic = FlowMessageCatalog().resolve_language("fr")

    assert used == "en"
    assert diagnostic is not None
    assert diagnostic.metadata["requestedLanguage"] == "fr"
    assert not hasattr(FlowWalkthroughPlanner(), "response_language")
