from __future__ import annotations

import sqlite3
import time
from dataclasses import replace

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.entrypoint_flow_store import EntrypointFlowGraphRepository
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.flow_explanations import FlowProjectionBuilder
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.flow_narrative import FlowNarrativePartKind, FlowNarrativePlanner
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import KnowledgeQueryService, SourceScopeResolver, UnifiedAnchorSearcher
from knowledge_service.operation_facts import AvailableOperationFact
from semantic_test_support import seed_semantic_graph


REVISION = "graph-current"


def _entrypoint_claim(node_id: str, *, method: str = "POST", route: str = "/items", role: str = "EXECUTABLE"):
    return {
        "id": f"claim-{node_id}",
        "node_id": node_id,
        "claimKind": "ENTRYPOINT_HINT",
        "summary": f"{method} {route}",
        "entrypointKind": "HTTP",
        "httpMethod": method,
        "route": route,
        "entrypointExecutionKind": role,
        "evidence_ids": [f"ev-{node_id}"],
    }


def _claim(
    claim_id: str,
    node_id: str,
    *,
    method: str = "POST",
    route: str = "/items",
    role: str = "EXECUTABLE",
    interface_method: str | None = None,
):
    claim = _entrypoint_claim(node_id, method=method, route=route, role=role)
    claim["id"] = claim_id
    if interface_method is not None:
        claim["interfaceMethod"] = interface_method
    return claim


def _service(db_path) -> KnowledgeQueryService:
    store = AnalysisStore(db_path)
    return KnowledgeQueryService(
        SourceScopeResolver(store),
        UnifiedAnchorSearcher(store),
        EntrypointFlowGraphRepository(store),
    )


def test_partial_source_current_successful_facts_are_queryable(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="partial-source",
        nodes=[
            {"id": "entry", "nodeKind": "CALLABLE", "name": "Entry.start", "qualified": "Entry.start", "path": "src/Entry.java"},
            {"id": "worker", "nodeKind": "CALLABLE", "name": "Worker.run", "qualified": "Worker.run", "path": "src/Worker.java"},
            {"id": "repo", "nodeKind": "CALLABLE", "name": "Repo.save", "qualified": "Repo.save", "path": "src/Repo.java"},
        ],
        edges=[
            {"id": "entry-worker", "fromNodeId": "entry", "toNodeId": "worker", "edgeType": "CALLS"},
            {"id": "worker-repo", "fromNodeId": "worker", "toNodeId": "repo", "edgeType": "CALLS"},
        ],
        claims=[_entrypoint_claim("entry", route="/partial")],
        evidence_ids=["ev-entry", "ev-worker", "ev-repo"],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute("UPDATE analysis_graph_state SET status = 'PARTIAL' WHERE source_id = 'partial-source'")
        for index in range(4, 101):
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, 'partial-source', '.', '.', ?, '.java', 'java', 'CODE', 1, ?, 'now', 1, 'utf-8:replace', 'now')
                """,
                (90000 + index, f"src/Unprocessed{index}.java", f"pending-hash-{index}"),
            )
        for index in range(101, 103):
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_files(
                    file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status,
                    analyzed_at, diagnostics_json, flow_domain
                )
                VALUES (?, 'partial-source', ?, ?, 'fixture', '1', 'FAILED', 'now', '[]', 'CODE')
                """,
                (90000 + index, f"src/Failed{index}.java", f"failed-hash-{index}"),
            )

    result = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="Entry.start"))

    assert result.response.status in {"OK", "AMBIGUOUS"}
    assert len(result.narrative_plans) == 1
    assert [node.qualified_name for node in result.flows[0].nodes] == ["Entry.start", "Repo.save", "Worker.run"]
    assert any(item.code == "PARTIAL_SOURCE_GRAPH_STATE" for item in result.response.diagnostics)


def test_stale_fact_is_excluded_while_current_fact_from_same_source_remains(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="mixed-source",
        nodes=[
            {"id": "current", "nodeKind": "CALLABLE", "name": "Current.start", "qualified": "Current.start", "path": "src/Current.java"},
            {"id": "stale", "nodeKind": "CALLABLE", "name": "Stale.start", "qualified": "Stale.start", "path": "src/Stale.java"},
        ],
        claims=[_entrypoint_claim("current", route="/current"), _entrypoint_claim("stale", route="/stale")],
        evidence_ids=["ev-current", "ev-stale"],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "UPDATE files SET content_hash = 'new-current-hash' WHERE source_id = 'mixed-source' AND relative_path = 'src/Stale.java'"
        )

    stale = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="Stale.start"))
    current = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="Current.start"))

    assert all(
        "Stale.start" != node.qualified_name
        for flow in stale.flows
        for node in flow.nodes
    )
    assert current.response.status in {"OK", "AMBIGUOUS"}
    assert len(current.narrative_plans) == 1


def test_client_operation_claim_hydrates_available_operation_fact_without_semantic_document(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="operation-source",
        nodes=[
            {
                "id": "client",
                "nodeKind": "CALLABLE",
                "name": "Client.create",
                "qualified": "client.Client.create",
                "path": "src/Client.java",
            },
        ],
        claims=[
            _claim(
                "claim-client-create",
                "client",
                method="POST",
                route="/items",
                role="CLIENT_OPERATION",
                interface_method="HTTP POST /items",
            )
        ],
        evidence_ids=["ev-client"],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute("DELETE FROM semantic_documents WHERE source_id = 'operation-source'")

    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    facts = repo.load_available_operation_facts({("operation-source", "", "client")}, include_tests=False)

    assert len(facts) == 1
    fact = facts[0]
    assert fact.owner_node_id == "client"
    assert fact.source_id == "operation-source"
    assert fact.execution_role == "CLIENT_OPERATION"
    assert fact.transport_kind == "HTTP"
    assert fact.method == "POST"
    assert fact.normalized_route == "/items"
    assert fact.operation_identity == "HTTP POST /items"
    assert fact.interface_identity is None
    assert fact.eligibility is not None
    assert fact.eligibility.inventory_current is True
    assert fact.eligibility.analyzed_current is True
    assert fact.evidence


def test_partial_source_client_operation_fact_remains_queryable(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="partial-operation-source",
        nodes=[
            {
                "id": "client",
                "nodeKind": "CALLABLE",
                "name": "Client.create",
                "qualified": "client.Client.create",
                "path": "src/Client.java",
            },
        ],
        claims=[_claim("claim-client-create", "client", role="CLIENT_OPERATION")],
        evidence_ids=["ev-client"],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute("UPDATE analysis_graph_state SET status = 'PARTIAL' WHERE source_id = 'partial-operation-source'")
        for index in range(20):
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, 'partial-operation-source', '.', '.', ?, '.java', 'java', 'CODE', 1, ?, 'now', 1, 'utf-8:replace', 'now')
                """,
                (91000 + index, f"src/Pending{index}.java", f"pending-operation-hash-{index}"),
            )

    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    facts = repo.load_available_operation_facts({("partial-operation-source", "", "client")}, include_tests=False)

    assert [(fact.execution_role, fact.transport_kind, fact.method, fact.normalized_route) for fact in facts] == [
        ("CLIENT_OPERATION", "HTTP", "POST", "/items")
    ]


def test_entrypoint_flow_timing_sql_metrics_are_request_deltas(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="metrics-source",
        nodes=[
            {"id": "entry", "nodeKind": "CALLABLE", "name": "Entry.start", "qualified": "Entry.start", "path": "src/Entry.java"},
            {"id": "worker", "nodeKind": "CALLABLE", "name": "Worker.run", "qualified": "Worker.run", "path": "src/Worker.java"},
        ],
        edges=[{"id": "entry-worker", "fromNodeId": "entry", "toNodeId": "worker", "edgeType": "CALLS"}],
        claims=[_entrypoint_claim("entry", route="/metrics")],
        evidence_ids=["ev-entry", "ev-worker"],
    )
    service = _service(db_path)

    first = service.query_with_flows(KnowledgeQueryRequest(queryText="Entry.start"))
    second = service.query_with_flows(KnowledgeQueryRequest(queryText="Entry.start"))
    first_sql = _timing_metadata(first)["sqlStatements"]
    second_sql = _timing_metadata(second)["sqlStatements"]

    assert first_sql > 0
    assert second_sql == first_sql


def _timing_metadata(result):
    return next(item.metadata for item in result.response.diagnostics if item.code == "ENTRYPOINT_FLOW_TIMINGS")


def test_available_operation_fact_sql_scales_by_chunk_not_node(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    nodes = [
        {
            "id": f"node-{index:04d}",
            "nodeKind": "CALLABLE",
            "name": f"Client.operation{index:04d}",
            "qualified": f"client.Client.operation{index:04d}",
            "path": "src/Client.java",
        }
        for index in range(2000)
    ]
    claims = [
        _claim(f"claim-node-{index:04d}", f"node-{index:04d}", role="CLIENT_OPERATION", route=f"/items/{index:04d}")
        for index in range(2000)
    ]
    seed_semantic_graph(
        db_path,
        source_id="scaling-source",
        nodes=nodes,
        claims=claims,
        evidence_ids=[f"ev-node-{index:04d}" for index in range(2000)],
    )
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))

    small_keys = {("scaling-source", "", f"node-{index:04d}") for index in range(20)}
    before = repo.metrics().get("sqlStatements", 0)
    small = repo.load_available_operation_facts(small_keys, include_tests=False)
    small_delta = repo.metrics().get("sqlStatements", 0) - before

    large_keys = {("scaling-source", "", f"node-{index:04d}") for index in range(2000)}
    before = repo.metrics().get("sqlStatements", 0)
    large = repo.load_available_operation_facts(large_keys, include_tests=False)
    large_delta = repo.metrics().get("sqlStatements", 0) - before

    assert len(small) == 20
    assert len(large) == 2000
    assert small_delta == 2
    assert large_delta == 4
    assert large_delta - small_delta == 2


def _node(node_id: str, *, source: str, role: str, method: str = "POST", route: str = "/items") -> FlowGraphNode:
    return FlowGraphNode(
        source_id=source,
        graph_id=f"{source}:{REVISION}",
        graph_revision=f"{source}:{REVISION}",
        node_id=node_id,
        stable_key=f"{source}:{node_id}",
        node_kind="CALLABLE",
        label=node_id,
        qualified_name=f"{source}.{node_id}",
        relative_path=f"src/{source}/{node_id}.java",
        line_start=1,
        line_end=1,
        entrypoint=True,
        entrypoint_kind="HTTP",
        entrypoint_http_method=method,
        entrypoint_route=route,
        execution_role=role,
    )


def _fact(
    node: FlowGraphNode,
    *,
    method: str | None = None,
    route: str | None = None,
    execution_role: str | None = None,
    operation_identity: str | None = None,
    interface_identity: str | None = None,
    transport: str = "HTTP",
) -> AvailableOperationFact:
    return AvailableOperationFact(
        owner_source_id=node.source_id,
        owner_graph_id=node.graph_id,
        owner_graph_revision=node.graph_revision,
        owner_node_id=node.node_id,
        source_id=node.source_id,
        execution_role=execution_role or node.execution_role,
        transport_kind=transport,
        direction_role=None,
        method=method or node.entrypoint_http_method,
        normalized_route=route or node.entrypoint_route,
        operation_identity=operation_identity,
        interface_identity=interface_identity,
        owner_qualified_name=node.qualified_name,
    )


def _facts_for(*nodes: FlowGraphNode) -> tuple[AvailableOperationFact, ...]:
    return tuple(_fact(node) for node in nodes)


def _flow(
    root: FlowGraphNode,
    nodes: tuple[FlowGraphNode, ...] | None = None,
    edges: tuple[FlowGraphEdge, ...] = (),
    evidence: tuple[FlowGraphEvidence, ...] = (),
) -> EntrypointFlow:
    selected_nodes = nodes or (root,)
    return EntrypointFlow(
        key=EntrypointFlowKey(root.source_id, root.graph_revision or root.graph_id, root.node_id),
        entrypoint=root,
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor(root.node_id, root.label, 1.0, ("TEST",), 0),),
        nodes=selected_nodes,
        transitions=edges,
        boundary_transitions=(),
        evidence=evidence,
        complete=True,
        coverage=EntrypointFlowCoverage(len(selected_nodes), len(edges), 0, 1, len(selected_nodes)),
        diagnostics=(),
        relevance_score=1.0,
    )


def _families(*flows: EntrypointFlow):
    return FlowFamilyAssembler().rank(FlowFamilyAssembler().assemble(flows).families)


def test_disconnected_exact_http_fragments_become_one_plan_with_unverified_gap():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(outbound), _flow(inbound)),
        max_plans=10,
        operation_facts=_facts_for(outbound, inbound),
    )

    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    assert plans[0].parts[1].gap.verification_status == "UNVERIFIED"


def test_ambiguous_http_target_does_not_merge_plans():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    target_a = _node("ControllerA.create", source="service-a", role="EXECUTABLE")
    target_b = _node("ControllerB.create", source="service-b", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(outbound), _flow(target_a), _flow(target_b)),
        max_plans=10,
        operation_facts=_facts_for(outbound, target_a, target_b),
    )

    assert len(plans) == 3
    assert any(part.kind is FlowNarrativePartKind.AMBIGUOUS_GAP for plan in plans for part in plan.parts)
    assert any(item.code == "FLOW_CORRELATION_AMBIGUOUS" for item in diagnostics)


def test_independent_roots_sharing_downstream_remain_independent_plans():
    entry_a = _node("EntryA.start", source="source-a", role="EXECUTABLE", method="GET", route="/a")
    entry_b = _node("EntryB.start", source="source-a", role="EXECUTABLE", method="GET", route="/b")
    shared = replace(entry_a, node_id="Shared.work", label="Shared.work", qualified_name="source-a.Shared.work", entrypoint=False)
    edge_a = FlowGraphEdge("source-a", entry_a.graph_id, entry_a.graph_revision, "a-shared", "CALLS", entry_a.node_id, shared.node_id, "RESOLVED")
    edge_b = FlowGraphEdge("source-a", entry_b.graph_id, entry_b.graph_revision, "b-shared", "CALLS", entry_b.node_id, shared.node_id, "RESOLVED")

    plans, _diagnostics = FlowNarrativePlanner().assemble(
        _families(
            _flow(entry_a, (entry_a, shared), (edge_a,)),
            _flow(entry_b, (entry_b, shared), (edge_b,)),
        ),
        max_plans=10,
    )

    assert len(plans) == 2
    assert all(len(plan.fragments) == 1 for plan in plans)


def test_contract_reached_by_execution_projects_as_outbound_without_separate_card():
    caller = _node("Caller.start", source="client", role="EXECUTABLE", route="/start")
    contract = replace(
        caller,
        node_id="ClientContract.create",
        label="ClientContract.create",
        qualified_name="client.ClientContract.create",
        entrypoint=False,
        entrypoint_kind=None,
        entrypoint_http_method=None,
        entrypoint_route=None,
        execution_role="CONTRACT_DECLARATION",
    )
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")
    edge = FlowGraphEdge("client", caller.graph_id, caller.graph_revision, "caller-contract", "CALLS", caller.node_id, contract.node_id, "RESOLVED")
    families = _families(_flow(caller, (caller, contract), (edge,)), _flow(inbound))

    plans, diagnostics = FlowNarrativePlanner().assemble(
        families,
        max_plans=10,
        operation_facts=(
            _fact(caller, route="/start"),
            _fact(contract, method="POST", route="/items", execution_role="CONTRACT_DECLARATION"),
            _fact(inbound),
        ),
    )

    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]


def test_standalone_contract_is_not_outbound_and_does_not_correlate():
    contract = _node("ClientContract.create", source="client", role="CONTRACT_DECLARATION")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(contract), _flow(inbound)),
        max_plans=10,
        operation_facts=(_fact(contract, execution_role="CONTRACT_DECLARATION"), _fact(inbound)),
    )

    assert diagnostics == ()
    assert len(plans) == 1
    assert all(FlowNarrativePartKind.UNVERIFIED_GAP not in [part.kind for part in plan.parts] for plan in plans)


def test_conflicting_operation_identity_fails_closed_without_gap_to_target():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(outbound), _flow(inbound)),
        max_plans=10,
        operation_facts=(
            _fact(outbound, operation_identity="operation-a"),
            _fact(inbound, operation_identity="operation-b"),
        ),
    )

    assert diagnostics == ()
    assert len(plans) == 2
    assert all(FlowNarrativePartKind.UNVERIFIED_GAP not in [part.kind for part in plan.parts] for plan in plans)


def test_multiple_outbound_operations_are_not_overwritten():
    source = _node("ClientGateway.start", source="client", role="EXECUTABLE", method="GET", route="/gateway")
    outbound_a = replace(
        source,
        node_id="GeneratedClient.create",
        label="GeneratedClient.create",
        qualified_name="client.GeneratedClient.create",
        entrypoint=False,
        entrypoint_kind=None,
        entrypoint_http_method=None,
        entrypoint_route=None,
        execution_role="CLIENT_OPERATION",
    )
    outbound_b = replace(
        outbound_a,
        node_id="GeneratedClient.update",
        label="GeneratedClient.update",
        qualified_name="client.GeneratedClient.update",
    )
    target_a = _node("CreateController.create", source="service-a", role="EXECUTABLE", method="POST", route="/items")
    target_b = _node("UpdateController.update", source="service-b", role="EXECUTABLE", method="PUT", route="/items/{id}")
    edge_a = FlowGraphEdge("client", source.graph_id, source.graph_revision, "source-a", "CALLS", source.node_id, outbound_a.node_id, "RESOLVED")
    edge_b = FlowGraphEdge("client", source.graph_id, source.graph_revision, "source-b", "CALLS", source.node_id, outbound_b.node_id, "RESOLVED")

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(source, (source, outbound_a, outbound_b), (edge_a, edge_b)), _flow(target_a), _flow(target_b)),
        max_plans=10,
        operation_facts=(
            _fact(source, method="GET", route="/gateway"),
            _fact(outbound_a, method="POST", route="/items", execution_role="CLIENT_OPERATION"),
            _fact(outbound_b, method="PUT", route="/items/{id}", execution_role="CLIENT_OPERATION"),
            _fact(target_a, method="POST", route="/items"),
            _fact(target_b, method="PUT", route="/items/{id}"),
        ),
    )

    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts].count(FlowNarrativePartKind.UNVERIFIED_GAP) == 2
    assert {part.gap.route for part in plans[0].parts if part.gap is not None} == {"/items", "/items/{id}"}


def test_boundary_metadata_projects_outbound_operation_fact():
    outbound = _node("Client.create", source="client", role="EXECUTABLE", method="GET", route="/local")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")
    boundary = FlowGraphEdge(
        "client",
        outbound.graph_id,
        outbound.graph_revision,
        "http-boundary",
        "CALLS",
        outbound.node_id,
        None,
        "EXTERNAL_TARGET",
        external=True,
        metadata={
            "transportKind": "HTTP",
            "httpMethod": "POST",
            "routeTemplate": "/items",
            "operationIdentity": "create-item",
        },
    )

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(outbound, (outbound,), (), evidence=()), _flow(inbound)),
        max_plans=10,
        operation_facts=(_fact(outbound, method="GET", route="/local"), _fact(inbound, operation_identity="create-item")),
    )

    assert len(plans) == 2
    assert all(FlowNarrativePartKind.UNVERIFIED_GAP not in [part.kind for part in plan.parts] for plan in plans)

    flow_with_boundary = _flow(outbound, (outbound,), (), evidence=())
    flow_with_boundary = replace(flow_with_boundary, boundary_transitions=(boundary,))
    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(flow_with_boundary, _flow(inbound)),
        max_plans=10,
        operation_facts=(_fact(outbound, method="GET", route="/local"), _fact(inbound, operation_identity="create-item")),
    )

    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    assert plans[0].parts[1].gap.operation_identity == "create-item"


def test_tool_and_human_projection_share_gap_and_operation_order():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")
    plans, _diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(outbound), _flow(inbound)),
        max_plans=10,
        operation_facts=_facts_for(outbound, inbound),
    )
    request = KnowledgeQueryRequest(queryText="create item", intent="FLOW_EXPLANATION")
    projector = FlowProjectionBuilder()

    tool = projector.to_tool_response(request, type("Execution", (), {"narrative_plans": plans, "flows": (), "response": None})())
    llm_input = projector.human_llm_input(
        request,
        plans[0],
        type("Plan", (), {"detected_language": "en", "response_language": "en", "effective_intent": "FLOW_EXPLANATION"})(),
    )

    assert len(tool.flows) == 1
    assert [part.kind for part in tool.flows[0].parts] == [
        "VERIFIED_FRAGMENT",
        "UNVERIFIED_GAP",
        "VERIFIED_FRAGMENT",
    ]
    assert tool.flows[0].parts[0].tree.entrypoint.trigger.method == "POST"
    units = [atom["unit"] for atom in llm_input["narrationAtoms"]]
    assert [unit["type"] for unit in units][:2] == ["node", "gap"]
    assert units[0]["trigger"]["method"] == "POST"
    assert units[1]["gapVerificationStatus"] == "UNVERIFIED"


def test_catalog_client_operation_attaches_to_non_target_fragment_and_is_projected():
    upstream = _node("Gateway.create", source="gateway", role="EXECUTABLE")
    downstream = _node("Controller.create", source="service", role="EXECUTABLE")
    catalog_operation = AvailableOperationFact(
        owner_source_id="contract-source",
        owner_graph_id="contract-source:graph",
        owner_graph_revision="contract-source:graph",
        owner_node_id="client-create",
        source_id="contract-source",
        execution_role="CLIENT_OPERATION",
        transport_kind="HTTP",
        direction_role=None,
        method="POST",
        normalized_route="/items",
        operation_identity="HTTP POST /items",
        target_service_identity="service",
        owner_qualified_name="generated.Client.createWithHttpInfo",
        owner_relative_path="generated/Client.java",
        source_channel="CATALOG_CONTRACT",
    )

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(_flow(upstream), _flow(downstream)),
        max_plans=10,
        operation_facts=(_fact(upstream), _fact(downstream), catalog_operation),
    )
    request = KnowledgeQueryRequest(queryText="create item", intent="FLOW_EXPLANATION")
    projector = FlowProjectionBuilder()
    tool = projector.to_tool_response(request, type("Execution", (), {"narrative_plans": plans, "flows": (), "response": None})())
    llm_input = projector.human_llm_input(
        request,
        plans[0],
        type("Plan", (), {"detected_language": "en", "response_language": "en", "effective_intent": "FLOW_EXPLANATION"})(),
    )

    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    assert plans[0].parts[0].fragment.operation_facts[-1].owner_source_id == "contract-source"
    assert len(tool.flows) == 1
    assert any(item.kind == "OPERATION" for item in tool.flows[0].parts[0].tree.entrypoint.children)
    assert [atom["unit"]["type"] for atom in llm_input["narrationAtoms"]] == ["node", "operation", "gap", "node"]


def test_large_partial_fragment_with_exact_http_gap_is_linear_enough():
    count = 2000
    root = _node("Client.bulkCreate", source="client", role="CLIENT_OPERATION", method="POST", route="/bulk-items")
    nodes = [root]
    edges = []
    evidence = []
    previous = root
    for index in range(count):
        node = replace(
            root,
            node_id=f"GeneratedStep{index:04d}.call",
            label=f"GeneratedStep{index:04d}.call",
            qualified_name=f"client.GeneratedStep{index:04d}.call",
            entrypoint=False,
            execution_role=None,
        )
        edge = FlowGraphEdge(
            "client",
            root.graph_id,
            root.graph_revision,
            f"edge-{index:04d}",
            "CALLS",
            previous.node_id,
            node.node_id,
            "RESOLVED",
            evidence_ids=(f"evidence-{index:04d}",),
        )
        nodes.append(node)
        edges.append(edge)
        evidence.append(
            FlowGraphEvidence(
                "client",
                root.graph_id,
                root.graph_revision,
                f"evidence-{index:04d}",
                None,
                edge.edge_id,
                "src/client/BulkClient.java",
                10,
                10,
                "repeated-looking call evidence",
            )
        )
        previous = node
    inbound = _node("BulkController.create", source="service", role="EXECUTABLE", method="POST", route="/bulk-items")

    started = time.perf_counter()
    families = _families(_flow(root, tuple(nodes), tuple(edges), tuple(evidence)), _flow(inbound))
    plans, diagnostics = FlowNarrativePlanner().assemble(families, max_plans=10, operation_facts=_facts_for(root, inbound))
    elapsed = time.perf_counter() - started

    assert diagnostics == ()
    assert len(plans) == 1
    assert elapsed < 5.0
    assert [part.kind for part in plans[0].parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    first_fragment = plans[0].parts[0].fragment
    assert len(first_fragment.family.transitions) == count
    assert len(first_fragment.family.evidence) == count
