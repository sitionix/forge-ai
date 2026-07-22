from __future__ import annotations

import json
import time
from dataclasses import replace
from types import SimpleNamespace

import pytest

from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.flow_formatter import (
    AMBIGUOUS,
    UNVERIFIED,
    VERIFIED,
    FlowFormatterAnswerService,
    FlowFormatterContractViolation,
    FlowFormatterGroup,
    FlowFormatterGroupKind,
    FlowFormatterPlan,
    FlowFormatterPlanBuilder,
    FlowFormatterProviderResult,
    FlowFormatterResponseValidator,
    FlowFormatterSegmentPlanner,
)
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.flow_narrative import FlowNarrativePlanner
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
    metadata: dict | None = None,
    external: bool = False,
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
        external=external,
        unresolved_target={"name": target or "Missing.target"} if target_node is None else None,
        metadata=metadata,
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
        text="raw evidence text must not be formatted",
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
    direction: str | None = None,
    method: str | None = None,
    route: str | None = None,
    topic: str | None = None,
    schedule: str | None = None,
    target_service: str | None = None,
    operation_identity: str | None = None,
) -> AvailableOperationFact:
    return AvailableOperationFact(
        owner_source_id=node.source_id,
        owner_graph_id=node.graph_id,
        owner_graph_revision=node.graph_revision,
        owner_node_id=node.node_id,
        source_id=node.source_id,
        execution_role=role or node.execution_role,
        transport_kind=transport,
        direction_role=direction,
        method=method or node.entrypoint_http_method,
        normalized_route=route or node.entrypoint_route,
        topic=topic,
        schedule=schedule,
        operation_identity=operation_identity,
        target_service_identity=target_service,
        owner_qualified_name=node.qualified_name,
    )


def _families(*flows: EntrypointFlow):
    assembler = FlowFamilyAssembler()
    return assembler.rank(assembler.assemble(flows).families)


def _plans(*flows: EntrypointFlow, facts: tuple[AvailableOperationFact, ...] = ()):
    return FlowNarrativePlanner().assemble(_families(*flows), max_plans=10, operation_facts=facts)[0]


def _formatter_plan(*flows: EntrypointFlow, facts: tuple[AvailableOperationFact, ...] = (), language: str = "en"):
    plans = _plans(*flows, facts=facts)
    return FlowFormatterPlanBuilder().plan(plans[0], response_language=language)


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


class ContractFormatterProvider:
    def __init__(self, *, language: str | None = None, responses: list[dict | str | FlowFormatterProviderResult] | None = None):
        self.language = language
        self.responses = list(responses or [])
        self.calls = []

    def complete(self, formatter_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"formatterInput": dict(formatter_input), "validationErrors": list(validation_errors or [])})
        if self.responses:
            response = self.responses.pop(0)
            if isinstance(response, FlowFormatterProviderResult):
                return response
            raw = response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)
            return FlowFormatterProviderResult(raw_text=raw, prompt_char_length=100)
        response_language = self.language or formatter_input["responseLanguage"]
        steps = []
        certainty_by_ref = formatter_input["coverageContract"]["certaintyByGroupRef"]
        for group in _flatten(formatter_input["groups"]):
            ref = group["groupRef"]
            certainty = certainty_by_ref[ref]
            steps.append({"groupRef": ref, "certainty": certainty, "text": _contract_text(group, certainty, response_language)})
        return FlowFormatterProviderResult(raw_text=json.dumps({"steps": steps}, ensure_ascii=False), prompt_char_length=100)


def _flatten(groups):
    for group in groups:
        yield group
        yield from _flatten(group.get("childGroups", []))


def _contract_text(group, certainty, language):
    identifiers = []
    for value in group.get("identifierHints", []):
        if isinstance(value, str) and value and value not in identifiers:
            identifiers.append(value)
    values = ", ".join(identifiers) or group["kind"]
    if language == "uk":
        uncertainty = " з непідтвердженим зв'язком" if certainty == UNVERIFIED else " з неоднозначним зв'язком" if certainty == AMBIGUOUS else ""
        return f"Цей крок описує доступний потік{uncertainty}: {values}."
    if language == "de":
        return f"Dieser Schritt beschreibt den verfügbaren Ablauf: {values}."
    if language == "fr":
        return f"Cette étape décrit le flux disponible: {values}."
    uncertainty = " with an unconfirmed connection" if certainty == UNVERIFIED else " with an ambiguous connection" if certainty == AMBIGUOUS else ""
    return f"This step describes the available flow{uncertainty}: {values}."


def _answer(plans, language: str = "en", provider: ContractFormatterProvider | None = None, context_tokens: int = 32768):
    provider = provider or ContractFormatterProvider()
    service = FlowFormatterAnswerService(
        provider,
        segment_planner=FlowFormatterSegmentPlanner(context_tokens=context_tokens),
    )
    result = service.answer(
        KnowledgeQueryRequest(queryText="neutral flow"),
        SimpleNamespace(narrative_plans=tuple(plans)),
        plan=_retrieval_plan(language),
    )
    return service.to_response(result), result.metrics, provider


def test_linear_flow_plan_has_ordered_groups_and_no_user_facing_text():
    root = _node("Root.start", entrypoint=True, method="POST", route="/neutral")
    first = _node("OperationA.run", summary="Transforms the request.")
    second = _node("OperationB.finish", summary="Returns the stored status.")
    e1 = _edge("root-first", root, first, line=10)
    e2 = _edge("first-second", first, second, line=20)

    plan = _formatter_plan(_flow(root, (root, first, second), (e1, e2), evidence=(_evidence(e1, 10), _evidence(e2, 20))))

    assert [group.kind for group in plan.groups] == [
        FlowFormatterGroupKind.ENTRYPOINT,
        FlowFormatterGroupKind.LINEAR_EXECUTION,
        FlowFormatterGroupKind.LINEAR_EXECUTION,
        FlowFormatterGroupKind.AVAILABLE_FACTS_END,
    ]
    payload = json.dumps([group.__dict__ for group in plan.groups], default=str)
    assert "The entrypoint receives" not in payload
    assert "Execution reaches" not in payload
    assert "raw evidence text" not in payload


def test_multiple_ordinary_calls_create_ordered_group_without_explicit_branch():
    root = _node("Root.start", entrypoint=True)
    first = _node("First.run")
    second = _node("Second.run")
    e_first = _edge("root-first", root, first, line=30)
    e_second = _edge("root-second", root, second, line=10)

    plan = _formatter_plan(_flow(root, (root, first, second), (e_first, e_second), evidence=(_evidence(e_first, 30), _evidence(e_second, 10))))
    ordered = next(group for group in plan.groups if group.kind is FlowFormatterGroupKind.ORDERED_CALL_GROUP)

    assert not any(group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH for group in plan.walk())
    assert [child.symbol for child in ordered.child_groups[:2]] == ["Second.run", "First.run"]


def test_explicit_persisted_branch_preserves_child_hierarchy():
    root = _node("Root.start", entrypoint=True)
    left = _node("Left.run")
    right = _node("Right.run")
    e_left = _edge("root-left", root, left, line=20, metadata={"flowControl": "BRANCH"})
    e_right = _edge("root-right", root, right, line=10, metadata={"flowControl": "BRANCH"})

    plan = _formatter_plan(_flow(root, (root, left, right), (e_left, e_right), evidence=(_evidence(e_left, 20), _evidence(e_right, 10))))
    branch = next(group for group in plan.groups if group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH)

    assert [child.kind for child in branch.child_groups] == [FlowFormatterGroupKind.BRANCH_ITEM, FlowFormatterGroupKind.BRANCH_ITEM]
    assert [child.to_symbol for child in branch.child_groups] == ["Right.run", "Left.run"]
    assert [child.child_groups[0].symbol for child in branch.child_groups] == ["Right.run", "Left.run"]


def test_incidental_unresolved_boundary_is_absent_from_formatter_plan_but_tool_context_keeps_it():
    root = _node("Root.start", entrypoint=True)
    downstream = _node("Worker.run")
    edge = _edge("root-worker", root, downstream)
    incidental = _edge("root-helper", root, None, status="UNRESOLVED", target="Helper.missing")

    plan = _formatter_plan(_flow(root, (root, downstream), (edge,), boundaries=(incidental,)))

    assert not any(group.kind is FlowFormatterGroupKind.UNRESOLVED_BOUNDARY for group in plan.walk())


def test_terminal_meaningful_boundary_is_preserved():
    root = _node("Root.start", entrypoint=True)
    boundary = _edge("root-missing", root, None, status="UNRESOLVED", target="Missing.operation")

    plan = _formatter_plan(_flow(root, (root,), boundaries=(boundary,)))

    assert any(group.kind is FlowFormatterGroupKind.UNRESOLVED_BOUNDARY for group in plan.walk())


def test_two_exact_fragments_insert_one_unverified_gap_in_position():
    outbound = _node("Client.call", source="client", role="CLIENT_OPERATION", entrypoint=True, method="POST", route="/items")
    inbound = _node("Handler.receive", source="service", role="EXECUTABLE", entrypoint=True, method="POST", route="/items")

    plan = _formatter_plan(_flow(outbound, (outbound,)), _flow(inbound, (inbound,)), facts=(_fact(outbound), _fact(inbound)))

    assert [group.kind for group in plan.groups[:3]] == [
        FlowFormatterGroupKind.ENTRYPOINT,
        FlowFormatterGroupKind.UNVERIFIED_GAP,
        FlowFormatterGroupKind.ENTRYPOINT,
    ]
    assert plan.groups[1].certainty == UNVERIFIED


def test_ambiguous_correlation_creates_gap_without_selected_causal_target():
    outbound = _node("Client.call", source="client", role="CLIENT_OPERATION", entrypoint=True, method="POST", route="/items")
    target_a = _node("HandlerA.receive", source="service-a", role="EXECUTABLE", entrypoint=True, method="POST", route="/items")
    target_b = _node("HandlerB.receive", source="service-b", role="EXECUTABLE", entrypoint=True, method="POST", route="/items")

    plan = _formatter_plan(
        _flow(outbound, (outbound,)),
        _flow(target_a, (target_a,)),
        _flow(target_b, (target_b,)),
        facts=(_fact(outbound), _fact(target_a), _fact(target_b)),
    )
    gap = next(group for group in plan.groups if group.kind is FlowFormatterGroupKind.AMBIGUOUS_GAP)

    assert gap.certainty == AMBIGUOUS
    assert gap.to_symbol is None


def test_cycle_and_shared_continuation_are_represented_once_without_recursion():
    root = _node("Root.start", entrypoint=True)
    first = _node("First.run")
    second = _node("Second.run")
    shared = _node("Shared.finish")
    cycle_edges = (_edge("root-first", root, first), _edge("first-root", first, root))
    shared_edges = (
        _edge("root-first", root, first, line=10),
        _edge("root-second", root, second, line=20),
        _edge("first-shared", first, shared, line=30),
        _edge("second-shared", second, shared, line=40),
    )

    cycle_plan = _formatter_plan(_flow(root, (root, first), cycle_edges))
    shared_plan = _formatter_plan(_flow(root, (root, first, second, shared), shared_edges))

    assert sum(1 for group in cycle_plan.walk() if group.kind is FlowFormatterGroupKind.CYCLE) == 1
    assert sum(1 for group in shared_plan.walk() if group.symbol == "Shared.finish") == 2
    assert sum(1 for group in shared_plan.walk() if group.kind is FlowFormatterGroupKind.SHARED_CONTINUATION) == 1
    assert shared_plan.group_count < 20


def test_same_symbol_across_sources_requires_source_display_hint():
    root = _node("Same.run", source="source-a", entrypoint=True)
    target = _node("Same.run", source="source-b")
    edge = _edge("a-b", root, target)

    plan = _formatter_plan(_flow(root, (root, target), (edge,)))

    same_groups = [group for group in plan.walk() if group.symbol == "Same.run"]
    assert {group.source_display_hint for group in same_groups} == {"REQUIRED"}


def test_partial_flow_uses_available_facts_end_without_success_or_failure_status():
    root = _node("Root.start", entrypoint=True)

    plan = _formatter_plan(_flow(root, (root,)))
    terminal = plan.groups[-1]

    assert terminal.kind is FlowFormatterGroupKind.AVAILABLE_FACTS_END
    assert terminal.terminal_semantic == "AVAILABLE_FACTS_END"
    assert "success" not in json.dumps(_group_dicts(plan), default=str).lower()
    assert "failure" not in json.dumps(_group_dicts(plan), default=str).lower()


def test_non_http_typed_flow_uses_same_structural_planner():
    root = _node("Schedule.run", entrypoint=True, kind="SCHEDULE", schedule="0 0 * * *")
    operation = _node("Publisher.send")
    edge = _edge("schedule-publish", root, operation)
    facts = (_fact(root, transport="SCHEDULE", method=None, route=None, schedule="0 0 * * *"),)

    plan = _formatter_plan(_flow(root, (root, operation), (edge,)), facts=facts)

    assert plan.groups[0].transport_kind == "SCHEDULE"
    assert plan.groups[0].schedule == "0 0 * * *"
    assert not any(group.kind.name.startswith("HTTP") for group in plan.walk())


def test_small_plan_uses_one_formatter_segment_call_and_one_answer():
    root = _node("Root.start", entrypoint=True, method="POST", route="/neutral")
    operation = _node("Worker.run")
    edge = _edge("root-worker", root, operation)
    plans = _plans(_flow(root, (root, operation), (edge,)))

    response, metrics, provider = _answer(plans)

    assert len(response.answers) == 1
    assert metrics["formatterSegmentCount"] == 1
    assert metrics["formatterProviderCallCount"] == 1
    assert len(provider.calls) == 1
    assert response.answers[0].text.startswith("1. ")


@pytest.mark.parametrize("language", ["en", "uk", "de", "fr"])
def test_dynamic_language_output_matches_response_language_and_preserves_identifiers(language):
    root = _node("Root.start", entrypoint=True, method="POST", route="/neutral")
    plans = _plans(_flow(root, (root,)))

    response, _metrics, _provider = _answer(plans, language=language)

    assert response.answerLanguage == language
    assert "Root.start" in response.answers[0].text


def test_wrong_language_gets_one_repair_then_plan_fails():
    root = _node("Root.start", entrypoint=True)
    plans = _plans(_flow(root, (root,)))
    provider = ContractFormatterProvider(language="en")

    with pytest.raises(Exception):
        _answer(plans, language="uk", provider=provider)

    assert len(provider.calls) == 2
    assert provider.calls[1]["validationErrors"]


def test_exact_group_coverage_rejects_reorder_and_duplicates():
    group_a = FlowFormatterGroup("g1", 1, 0, FlowFormatterGroupKind.ENTRYPOINT, symbol="A.start", source="source-a")
    group_b = FlowFormatterGroup("g2", 2, 0, FlowFormatterGroupKind.LINEAR_EXECUTION, symbol="B.work", source="source-a")
    segment = FlowFormatterSegmentPlanner().plan(FlowFormatterPlan("source-a", "A.start", (group_a, group_b), "en"))[0]
    raw = json.dumps(
        {
            "steps": [
                {"groupRef": "g2", "certainty": VERIFIED, "text": "This step describes the available flow: B.work."},
                {"groupRef": "g2", "certainty": VERIFIED, "text": "This step describes the available flow: B.work."},
            ]
        }
    )

    with pytest.raises(FlowFormatterContractViolation):
        FlowFormatterResponseValidator().validate(raw, segment)


def test_gap_certainty_cannot_be_upgraded_to_verified():
    group = FlowFormatterGroup("g1", 1, 0, FlowFormatterGroupKind.UNVERIFIED_GAP, certainty=UNVERIFIED, from_symbol="A.call", to_symbol="B.receive", route="/items")
    segment = FlowFormatterSegmentPlanner().plan(FlowFormatterPlan("source-a", "A.call", (group,), "en"))[0]
    raw = json.dumps(
        {
            "steps": [
                {
                    "groupRef": "g1",
                    "certainty": VERIFIED,
                    "text": "This step describes the available flow with an unconfirmed connection: A.call, B.receive, /items.",
                }
            ]
        }
    )

    with pytest.raises(FlowFormatterContractViolation):
        FlowFormatterResponseValidator().validate(raw, segment)


def test_large_plan_segments_dynamically_and_stitches_one_answer():
    groups = tuple(
        FlowFormatterGroup(f"g{index}", index, 0, FlowFormatterGroupKind.LINEAR_EXECUTION, symbol=f"Step{index}.run", source="source-a")
        for index in range(1, 160)
    )
    plan = FlowFormatterPlan("source-a", "Step1.run", groups, "en")
    segments = FlowFormatterSegmentPlanner(context_tokens=1800).plan(plan)

    assert len(segments) > 1
    assert [group.group_ref for segment in segments for group in segment.required_groups] == [group.group_ref for group in groups]


def test_truncated_formatter_output_splits_segment_without_lost_groups():
    root = _node("Root.start", entrypoint=True)
    first = _node("First.run")
    second = _node("Second.run")
    e1 = _edge("root-first", root, first, line=10)
    e2 = _edge("root-second", root, second, line=20)
    plans = _plans(_flow(root, (root, first, second), (e1, e2), evidence=(_evidence(e1, 10), _evidence(e2, 20))))
    provider = ContractFormatterProvider(
        responses=[
            FlowFormatterProviderResult(raw_text='{"steps":[', prompt_char_length=100, truncated=True),
        ]
    )

    response, metrics, provider = _answer(plans, provider=provider)

    assert len(response.answers) == 1
    assert metrics["formatterOutputSplitCallCount"] == 1
    assert metrics["formatterProviderCallCount"] > 1


def test_complete_contract_violation_gets_one_repair():
    root = _node("Root.start", entrypoint=True)
    plans = _plans(_flow(root, (root,)))
    provider = ContractFormatterProvider(
        responses=[
            {"steps": []},
        ]
    )

    response, metrics, provider = _answer(plans, provider=provider)

    assert len(response.answers) == 1
    assert metrics["formatterRepairCallCount"] == 1
    assert len(provider.calls) == 2


def test_formatter_prompt_contains_no_raw_evidence_excerpt():
    root = _node("Root.start", entrypoint=True)
    evidence = FlowGraphEvidence(root.source_id, root.graph_id, root.graph_revision, "ev-root", root.node_id, None, "src/Root.txt", 1, 1, "secret evidence excerpt")
    plans = _plans(_flow(root, (root,), evidence=(evidence,)))

    _response, _metrics, provider = _answer(plans)
    prompt_json = json.dumps(provider.calls[0]["formatterInput"], ensure_ascii=False)

    assert "secret evidence excerpt" not in prompt_json
    assert "evidence" not in prompt_json.lower()


def test_large_flow_scale_keeps_group_coverage_linear_enough():
    count = 2000
    root = _node("Root.start", source="client-source", role="CLIENT_OPERATION", entrypoint=True, method="POST", route="/bulk")
    nodes = [root]
    edges = []
    evidence = []
    previous = root
    for index in range(count):
        current = _node(f"Step{index:04d}.run", source=f"source-{index % 5}")
        edge = _edge(f"edge-{index:04d}", previous, current, line=index + 1)
        nodes.append(current)
        edges.append(edge)
        evidence.append(_evidence(edge, index + 1))
        previous = current
    ordered_a = _node("OrderedA.run", source="source-ordered")
    ordered_b = _node("OrderedB.run", source="source-ordered")
    ordered_source = nodes[count - 20]
    ordered_edge_a = _edge("ordered-a", ordered_source, ordered_a, line=count - 25)
    ordered_edge_b = _edge("ordered-b", ordered_source, ordered_b, line=count - 24)
    edges.extend((ordered_edge_a, ordered_edge_b))
    evidence.extend((_evidence(ordered_edge_a, count - 25), _evidence(ordered_edge_b, count - 24)))

    branch_source = nodes[count - 10]
    branch_join = nodes[count - 9]
    branch_left = _node("BranchLeft.run", source="branch-source")
    branch_right = _node("BranchRight.run", source="branch-source")
    branch_line = count - 10
    original_join_edge = edges[count - 10]
    branch_edges = (
        _edge("branch-left", branch_source, branch_left, line=branch_line, metadata={"flowControl": "BRANCH"}),
        _edge("branch-right", branch_source, branch_right, line=branch_line + 1, metadata={"flowControl": "BRANCH"}),
        _edge("branch-left-join", branch_left, branch_join, line=branch_line + 2),
        _edge("branch-right-join", branch_right, branch_join, line=branch_line + 3),
    )
    edges[count - 10] = branch_edges[0]
    edges.extend(branch_edges[1:])
    evidence = [item for item in evidence if item.edge_id != original_join_edge.edge_id]
    evidence.extend(_evidence(edge, branch_line + offset) for offset, edge in enumerate(branch_edges))

    cycle_edge = _edge("cycle-back", nodes[count - 5], nodes[count - 15], line=count - 5)
    edges.append(cycle_edge)
    evidence.append(_evidence(cycle_edge, 501))
    inbound = _node("Inbound.receive", source="receiver-source", role="EXECUTABLE", entrypoint=True, method="POST", route="/bulk")
    boundary = _edge("terminal-boundary", previous, None, status="UNRESOLVED", target="Remote.finish", metadata={"transportKind": "QUEUE", "topic": "items.done"})

    started = time.perf_counter()
    plan = _formatter_plan(
        _flow(root, tuple(nodes + [ordered_a, ordered_b, branch_left, branch_right]), tuple(edges), boundaries=(boundary,), evidence=tuple(evidence)),
        _flow(inbound, (inbound,)),
        facts=(_fact(root), _fact(inbound)),
    )
    segments = FlowFormatterSegmentPlanner(context_tokens=32768).plan(plan)
    elapsed = time.perf_counter() - started

    refs = [group.group_ref for group in plan.walk()]
    segmented_refs = [group.group_ref for segment in segments for group in segment.required_groups]
    assert len(refs) == len(set(refs))
    assert segmented_refs == refs
    assert len(segments) > 1
    assert sum(1 for group in plan.walk() if group.kind is FlowFormatterGroupKind.ORDERED_CALL_GROUP) >= 1
    assert sum(1 for group in plan.walk() if group.kind is FlowFormatterGroupKind.EXPLICIT_BRANCH) >= 1
    assert sum(1 for group in plan.walk() if group.kind is FlowFormatterGroupKind.UNVERIFIED_GAP) == 1
    assert sum(1 for group in plan.walk() if group.kind is FlowFormatterGroupKind.CYCLE) >= 1
    assert any(group.source_display_hint == "REQUIRED" for group in plan.walk())
    assert elapsed < 5.0


def _group_dicts(plan):
    return [
        {
            "kind": group.kind.value,
            "symbol": group.symbol,
            "terminalSemantic": group.terminal_semantic,
            "summary": group.summary,
        }
        for group in plan.walk()
    ]
