from __future__ import annotations

import hashlib
import json
import sqlite3
import time
from dataclasses import replace

from semantic_test_support import seed_semantic_graph

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.entrypoint_flow_store import EntrypointFlowGraphRepository
from knowledge_service.flow_explanations import FlowProjectionBuilder
from knowledge_service.flow_family import FlowFamilyAssembler
from knowledge_service.flow_formatter import FlowFormatterGroupKind, FlowFormatterPlanBuilder
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.flow_narrative import FlowCorrelationStatus, FlowNarrativePartKind, FlowNarrativePlanner, HttpFlowCorrelationAdapter
from knowledge_service.graph_analysis import GraphAnalysisEngine
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef, GraphNode
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import KnowledgeQueryService, SourceScopeResolver, UnifiedAnchorSearcher
from knowledge_service.operation_facts import AvailableOperationFact
from knowledge_service.query_interpretation import QueryRetrievalPlan

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


def _persist_graph_result(
    db_path,
    *,
    source_id: str,
    file_id: int,
    relative_path: str,
    content: str,
    result: GraphAnalysisResult,
):
    store = AnalysisStore(db_path)
    InventoryStore(db_path).init()
    store.init()
    now = "2026-07-25T00:00:00+00:00"
    lines = content.splitlines()
    content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
    row = {
        "id": file_id,
        "source_id": source_id,
        "relative_path": relative_path,
        "content_hash": content_hash,
        "flow_domain": "CODE",
    }
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES (?, ?, 'operation-test', '.', 1, '[]', '{}', ?)
            """,
            (source_id, source_id, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO files(
                id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
            )
            VALUES (?, ?, '.', '.', ?, '.java', 'java', 'CODE', ?, ?, ?, ?, 'utf-8:replace', ?)
            """,
            (file_id, source_id, relative_path, len(content.encode("utf-8")), content_hash, now, len(lines), now),
        )
    materialized = GraphAnalysisEngine().materialize(row, "job-1", "test-analyzer", "1", result, lines)
    store.replace_file_graph_analysis(
        file_id,
        {
            "source_id": source_id,
            "relative_path": relative_path,
            "content_hash": content_hash,
            "analyzer_name": "test-analyzer",
            "analyzer_version": "1",
            "flow_domain": "CODE",
            "status": "ANALYZED",
            "analyzed_at": now,
            "diagnostics": [],
        },
        materialized,
    )
    return store, materialized


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
    assert large_delta == 6
    assert large_delta - small_delta == 4


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


def _seed_registration_operation_pair(
    db_path,
    *,
    downstream_source: str = "fixture-auth",
    downstream_node: str = "auth-register",
    outbound_route: str | None = "api/v1/registrations",
    outbound_method: str | None = "POST",
    outbound_status: str = "TRUSTED",
    outbound_transport: str = "HTTP",
    outbound_edge_type: str = "CALLS",
    outbound_direction_role: str | None = None,
    outbound_target_service_identity: str | None = None,
):
    outbound_metadata = {
        "transportKind": outbound_transport,
        "httpMethod": outbound_method,
        "routeTemplate": outbound_route,
        "operationIdentity": "HTTP POST /api/v1/registrations",
        "interfaceIdentity": "AuthRegistrationApi.register",
        "requestContractIdentity": "RegistrationRequest",
        "responseContractIdentity": "RegistrationResponse",
    }
    target_identity = downstream_source if outbound_target_service_identity is None else outbound_target_service_identity
    if target_identity:
        outbound_metadata["targetServiceIdentity"] = target_identity
    if outbound_direction_role is not None:
        outbound_metadata["directionRole"] = outbound_direction_role
    upstream_revision = seed_semantic_graph(
        db_path,
        source_id="fixture-bff",
        nodes=[
            {
                "id": "bff-register",
                "nodeKind": "CALLABLE",
                "name": "Gateway.submit",
                "qualified": "fixture.bff.Gateway.submit",
                "path": "src/Gateway.java",
            },
        ],
        edges=[
            {
                "id": "bff-auth-http",
                "fromNodeId": "bff-register",
                "toNodeId": None,
                "edgeType": outbound_edge_type,
                "resolutionStatus": "EXTERNAL_TARGET",
                "status": outbound_status,
                "metadata": outbound_metadata,
            }
        ],
        claims=[
            _claim(
                "claim-bff-register",
                "bff-register",
                method="POST",
                route="/bff/registrations",
                interface_method="BffRegistrationApi.submit",
            )
        ],
        evidence_ids=["ev-bff-register"],
    )
    downstream_revision = seed_semantic_graph(
        db_path,
        source_id=downstream_source,
        nodes=[
            {
                "id": downstream_node,
                "nodeKind": "CALLABLE",
                "name": "RegistrationEndpoint.accept",
                "qualified": f"{downstream_source}.RegistrationEndpoint.accept",
                "path": "src/RegistrationEndpoint.java",
            },
        ],
        claims=[
            _claim(
                f"claim-{downstream_node}",
                downstream_node,
                method="POST",
                route="/api/v1/registrations/",
                interface_method="AuthRegistrationApi.register",
            )
        ],
        evidence_ids=[f"ev-{downstream_node}"],
    )
    return (
        ("fixture-bff", upstream_revision, "bff-register"),
        (downstream_source, downstream_revision, downstream_node),
    )


def _registration_plan() -> QueryRetrievalPlan:
    return QueryRetrievalPlan(
        original_query="створити юзера",
        normalized_query="create user",
        search_queries=("AgentProjectController.addAgentToProject",),
        code_identifiers=(),
        concepts=("user creation",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="uk",
        response_language="uk",
    )


def _seed_registration_query_graph(db_path):
    outbound_metadata = {
        "transportKind": "HTTP",
        "httpMethod": "POST",
        "routeTemplate": "/api/v1/registrations",
        "operationIdentity": "HTTP POST /api/v1/registrations",
        "interfaceIdentity": "RegistrationApi.create",
        "targetServiceIdentity": "registration-auth",
    }
    bff_revision = seed_semantic_graph(
        db_path,
        source_id="registration-bff",
        nodes=[
            {
                "id": "bff-create-user",
                "nodeKind": "CALLABLE",
                "name": "UserRegistrationController.createUser",
                "qualified": "bff.UserRegistrationController.createUser",
                "path": "src/UserRegistrationController.java",
            },
            {
                "id": "agent-project",
                "nodeKind": "CALLABLE",
                "name": "AgentProjectController.addAgentToProject",
                "qualified": "bff.AgentProjectController.addAgentToProject",
                "path": "src/AgentProjectController.java",
            },
        ],
        edges=[
            {
                "id": "bff-auth-http",
                "fromNodeId": "bff-create-user",
                "toNodeId": None,
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "metadata": outbound_metadata,
            }
        ],
        claims=[
            _claim(
                "claim-bff-create-user",
                "bff-create-user",
                method="POST",
                route="/bff/users",
                interface_method="BffUsersApi.create",
            ),
            _claim(
                "claim-agent-project",
                "agent-project",
                method="POST",
                route="/agent-projects",
                interface_method="AgentProjectsApi.addAgent",
            ),
        ],
        evidence_ids=["ev-bff-create-user", "ev-agent-project"],
    )
    auth_revision = seed_semantic_graph(
        db_path,
        source_id="registration-auth",
        nodes=[
            {
                "id": "auth-entry",
                "nodeKind": "CALLABLE",
                "name": "IdentityEndpoint.accept",
                "qualified": "auth.IdentityEndpoint.accept",
                "path": "src/IdentityEndpoint.java",
            },
            {
                "id": "auth-service",
                "nodeKind": "CALLABLE",
                "name": "IdentityService.persist",
                "qualified": "auth.IdentityService.persist",
                "path": "src/IdentityService.java",
            },
        ],
        edges=[{"id": "auth-entry-service", "fromNodeId": "auth-entry", "toNodeId": "auth-service", "edgeType": "CALLS"}],
        claims=[
            _claim(
                "claim-auth-entry",
                "auth-entry",
                method="POST",
                route="/api/v1/registrations",
                interface_method="RegistrationApi.create",
            )
        ],
        evidence_ids=["ev-auth-entry", "ev-auth-service"],
    )
    return (
        ("registration-bff", bff_revision, "bff-create-user"),
        ("registration-auth", auth_revision, "auth-entry"),
    )


def _loaded_nodes_and_facts(db_path, *keys):
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    key_set = set(keys)
    nodes = repo.load_nodes(key_set, include_tests=False)
    facts = repo.load_available_operation_facts(key_set, include_tests=False)
    return nodes, facts


def _correlation_results(nodes, facts, *keys):
    families = _families(*(_flow(nodes[key]) for key in keys))
    fragments = FlowNarrativePlanner().fragments(families, operation_facts=facts)
    return HttpFlowCorrelationAdapter().correlate(fragments)


def test_persisted_generic_http_operation_facts_support_registration_style_exact_correlation(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    upstream_key, downstream_key = _seed_registration_operation_pair(db_path)
    nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key, downstream_key)

    outbound_facts = [fact for fact in facts if fact.direction_role == "OUTBOUND"]
    assert len(outbound_facts) == 1
    outbound = outbound_facts[0]
    inbound = next(fact for fact in facts if fact.direction_role == "INBOUND" and fact.owner_source_id == "fixture-auth")

    assert outbound.source_channel == "EDGE_METADATA"
    assert outbound.owner_source_id == "fixture-bff"
    assert outbound.owner_node_id == "bff-register"
    assert outbound.owner_edge_id == "bff-auth-http"
    assert outbound.owner_qualified_name == "fixture.bff.Gateway.submit"
    assert outbound.owner_graph_revision == upstream_key[1]
    assert outbound.transport_kind == "HTTP"
    assert outbound.method == "POST"
    assert outbound.normalized_route == "/api/v1/registrations"
    assert outbound.operation_identity == "HTTP POST /api/v1/registrations"
    assert outbound.interface_identity == "AuthRegistrationApi.register"
    assert outbound.request_contract_identity == "RegistrationRequest"
    assert outbound.response_contract_identity == "RegistrationResponse"
    assert outbound.target_service_identity == "fixture-auth"
    assert outbound.eligibility is not None
    assert outbound.eligibility.inventory_current is True
    assert outbound.eligibility.analyzed_current is True
    assert outbound.evidence

    assert inbound.source_channel == "ENTRYPOINT_HINT"
    assert inbound.owner_source_id == "fixture-auth"
    assert inbound.owner_node_id == "auth-register"
    assert inbound.owner_qualified_name == "fixture-auth.RegistrationEndpoint.accept"
    assert inbound.owner_graph_revision == downstream_key[1]
    assert inbound.transport_kind == "HTTP"
    assert inbound.method == "POST"
    assert inbound.normalized_route == "/api/v1/registrations"
    assert inbound.interface_identity == "AuthRegistrationApi.register"
    assert outbound.method == inbound.method
    assert outbound.normalized_route == inbound.normalized_route

    results = _correlation_results(nodes, facts, upstream_key, downstream_key)
    exact = [result for result in results if result.status is FlowCorrelationStatus.EXACT_UNVERIFIED]
    assert len(exact) == 1
    assert exact[0].target_fragment_keys == (":".join(downstream_key),)


def test_matching_inbound_operation_lookup_discovers_auth_after_initial_bff_only(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    bff_key, auth_key = _seed_registration_query_graph(db_path)
    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))

    initial_facts = repo.load_available_operation_facts({bff_key}, include_tests=False)
    assert [fact.owner_source_id for fact in initial_facts if fact.direction_role == "OUTBOUND"] == ["registration-bff"]
    assert all(fact.owner_source_id != "registration-auth" for fact in initial_facts)

    inbound_matches = repo.load_matching_inbound_operation_facts(
        [fact for fact in initial_facts if fact.direction_role == "OUTBOUND"],
        eligible_source_ids=["registration-bff", "registration-auth"],
        include_tests=False,
    )

    assert [(fact.owner_source_id, fact.owner_node_id, fact.method, fact.normalized_route) for fact in inbound_matches] == [
        ("registration-auth", auth_key[2], "POST", "/api/v1/registrations")
    ]


def test_registration_query_rejects_expansion_only_agent_project_and_adds_auth_continuation(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _bff_key, _auth_key = _seed_registration_query_graph(db_path)

    result = _service(db_path).query_with_flows(
        KnowledgeQueryRequest(queryText="створити юзера"),
        plan=_registration_plan(),
    )

    matched_qualified = [node.qualifiedName for node in result.response.matchedNodes]
    assert all("AgentProjectController.addAgentToProject" not in str(value) for value in matched_qualified)
    assert {source.sourceId for source in result.response.matchedSources} == {"registration-bff"}
    assert len(result.narrative_plans) == 1
    parts = result.narrative_plans[0].parts
    assert [part.kind for part in parts] == [
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
        FlowNarrativePartKind.UNVERIFIED_GAP,
        FlowNarrativePartKind.VERIFIED_FRAGMENT,
    ]
    assert parts[0].fragment.source_id == "registration-bff"
    assert parts[1].gap.verification_status == "UNVERIFIED"
    assert parts[1].gap.method == "POST"
    assert parts[1].gap.route == "/api/v1/registrations"
    assert parts[2].fragment.source_id == "registration-auth"
    assert any(node.qualified_name == "auth.IdentityService.persist" for node in parts[2].fragment.family.nodes)
    assert sum(1 for part in parts if part.kind is FlowNarrativePartKind.UNVERIFIED_GAP) == 1


def test_create_site_query_uses_typed_continuation_without_catalog_attachment(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="site-bff",
        nodes=[
            {
                "id": "create-site",
                "nodeKind": "CALLABLE",
                "name": "SiteController.createSite",
                "qualified": "bff.SiteController.createSite",
                "path": "src/SiteController.java",
            }
        ],
        edges=[
            {
                "id": "site-http",
                "fromNodeId": "create-site",
                "toNodeId": None,
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "metadata": {
                    "transportKind": "HTTP",
                    "httpMethod": "POST",
                    "routeTemplate": "/api/v1/sites",
                    "operationIdentity": "HTTP POST /api/v1/sites",
                    "interfaceIdentity": "SitesApi.create",
                    "targetServiceIdentity": "site-service",
                },
            }
        ],
        claims=[_claim("claim-create-site", "create-site", method="POST", route="/bff/sites", interface_method="BffSitesApi.create")],
        evidence_ids=["ev-create-site"],
    )
    seed_semantic_graph(
        db_path,
        source_id="site-service",
        nodes=[
            {
                "id": "site-entry",
                "nodeKind": "CALLABLE",
                "name": "SiteEndpoint.accept",
                "qualified": "site.SiteEndpoint.accept",
                "path": "src/SiteEndpoint.java",
            }
        ],
        claims=[_claim("claim-site-entry", "site-entry", method="POST", route="/api/v1/sites", interface_method="SitesApi.create")],
        evidence_ids=["ev-site-entry"],
    )
    plan = QueryRetrievalPlan(
        original_query="створити сайт",
        normalized_query="create site",
        search_queries=("site creation execution flow",),
        code_identifiers=(),
        concepts=("site creation",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="uk",
        response_language="uk",
    )

    result = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="створити сайт"), plan=plan)

    assert len(result.narrative_plans) == 1
    parts = result.narrative_plans[0].parts
    assert [part.fragment.source_id for part in parts if part.fragment is not None] == ["site-bff", "site-service"]
    assert sum(1 for part in parts if part.kind is FlowNarrativePartKind.UNVERIFIED_GAP) == 1


def test_graph_analysis_persisted_http_operation_metadata_loads_and_correlates(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _store, upstream_graph = _persist_graph_result(
        db_path,
        source_id="persisted-gateway",
        file_id=1,
        relative_path="src/Gateway.java",
        content="class Gateway {\n  void submit() { client.post(); }\n}\n",
        result=GraphAnalysisResult(
            nodes=[
                GraphNode(
                    localId="gateway-submit",
                    nodeKind="CALLABLE",
                    name="Gateway.submit",
                    qualifiedName="persisted.gateway.Gateway.submit",
                    lineStart=1,
                    lineEnd=2,
                    confidence=1.0,
                )
            ],
            edges=[
                GraphEdge(
                    localId="gateway-auth-http",
                    fromNodeLocalId="gateway-submit",
                    toNodeLocalId=None,
                    edgeType="CALLS",
                    resolutionStatus="EXTERNAL_TARGET",
                    confidence=1.0,
                    evidence=[GraphEvidenceRef(lineStart=2, lineEnd=2, text="client.post()")],
                    unresolvedTarget={"name": "AuthRegistrationApi.register"},
                    metadata={
                        "transportKind": "HTTP",
                        "httpMethod": "POST",
                        "routeTemplate": "/api/v1/registrations",
                        "operationIdentity": "HTTP POST /api/v1/registrations",
                        "interfaceIdentity": "AuthRegistrationApi.register",
                        "requestContractIdentity": "RegistrationRequest",
                        "responseContractIdentity": "RegistrationResponse",
                        "targetServiceIdentity": "persisted-auth",
                    },
                )
            ],
        ),
    )
    _other_store, downstream_graph = _persist_graph_result(
        db_path,
        source_id="persisted-auth",
        file_id=2,
        relative_path="src/RegistrationEndpoint.java",
        content="class RegistrationEndpoint {\n  void accept() {}\n}\n",
        result=GraphAnalysisResult(
            nodes=[
                GraphNode(
                    localId="registration-accept",
                    nodeKind="CALLABLE",
                    name="RegistrationEndpoint.accept",
                    qualifiedName="persisted.auth.RegistrationEndpoint.accept",
                    lineStart=1,
                    lineEnd=2,
                    confidence=1.0,
                )
            ],
            claims=[
                GraphClaim(
                    localId="registration-entrypoint",
                    nodeLocalId="registration-accept",
                    claimKind="ENTRYPOINT_HINT",
                    summary="POST /api/v1/registrations",
                    confidence=1.0,
                    evidence=[GraphEvidenceRef(lineStart=2, lineEnd=2, text="accept")],
                    metadata={
                        "entrypointKind": "HTTP",
                        "httpMethod": "POST",
                        "route": "/api/v1/registrations/",
                        "interfaceMethod": "AuthRegistrationApi.register",
                        "entrypointExecutionKind": "EXECUTABLE",
                    },
                )
            ],
        ),
    )
    upstream_node_id = upstream_graph["nodes"][0]["id"]
    downstream_node_id = downstream_graph["nodes"][0]["id"]
    requested_upstream_key = ("persisted-gateway", "", upstream_node_id)
    requested_downstream_key = ("persisted-auth", "", downstream_node_id)

    with sqlite3.connect(db_path) as conn:
        metadata = json.loads(
            conn.execute(
                "SELECT metadata_json FROM analysis_graph_edges WHERE source_id = ?",
                ("persisted-gateway",),
            ).fetchone()[0]
        )
    assert metadata["transportKind"] == "HTTP"
    assert metadata["httpMethod"] == "POST"
    assert metadata["routeTemplate"] == "/api/v1/registrations"
    assert metadata["operationIdentity"] == "HTTP POST /api/v1/registrations"
    assert metadata["interfaceIdentity"] == "AuthRegistrationApi.register"
    assert metadata["requestContractIdentity"] == "RegistrationRequest"
    assert metadata["responseContractIdentity"] == "RegistrationResponse"
    assert metadata["targetServiceIdentity"] == "persisted-auth"

    repo = EntrypointFlowGraphRepository(AnalysisStore(db_path))
    facts = repo.load_available_operation_facts({requested_upstream_key, requested_downstream_key}, include_tests=False)
    outbound = next(fact for fact in facts if fact.direction_role == "OUTBOUND")
    inbound = next(fact for fact in facts if fact.direction_role == "INBOUND")

    assert outbound.source_channel == "EDGE_METADATA"
    assert outbound.owner_edge_id == upstream_graph["edges"][0]["id"]
    assert outbound.method == "POST"
    assert outbound.normalized_route == "/api/v1/registrations"
    assert outbound.operation_identity == "HTTP POST /api/v1/registrations"
    assert outbound.interface_identity == "AuthRegistrationApi.register"
    assert outbound.request_contract_identity == "RegistrationRequest"
    assert outbound.response_contract_identity == "RegistrationResponse"
    assert outbound.target_service_identity == "persisted-auth"
    assert outbound.evidence
    assert inbound.method == outbound.method
    assert inbound.normalized_route == outbound.normalized_route

    upstream_node = FlowGraphNode(
        source_id=outbound.owner_source_id,
        graph_id=outbound.owner_graph_id,
        graph_revision=outbound.owner_graph_revision,
        node_id=outbound.owner_node_id,
        stable_key=outbound.structural_owner,
        node_kind="CALLABLE",
        label="Gateway.submit",
        qualified_name=outbound.owner_qualified_name,
        entrypoint=True,
        execution_role="CLIENT_OPERATION",
    )
    downstream_node = FlowGraphNode(
        source_id=inbound.owner_source_id,
        graph_id=inbound.owner_graph_id,
        graph_revision=inbound.owner_graph_revision,
        node_id=inbound.owner_node_id,
        stable_key=inbound.structural_owner,
        node_kind="CALLABLE",
        label="RegistrationEndpoint.accept",
        qualified_name=inbound.owner_qualified_name,
        entrypoint=True,
        entrypoint_kind="HTTP",
        entrypoint_http_method=inbound.method,
        entrypoint_route=inbound.normalized_route,
        execution_role="EXECUTABLE",
    )
    families = _families(_flow(upstream_node), _flow(downstream_node))
    fragments = FlowNarrativePlanner().fragments(families, operation_facts=facts)
    assert {
        fragment.source_id: [
            (fact.direction_role, fact.method, fact.normalized_route, fact.operation_identity, fact.interface_identity)
            for fact in fragment.operation_facts
        ]
        for fragment in fragments
    } == {
        "persisted-gateway": [
            (
                "OUTBOUND",
                "POST",
                "/api/v1/registrations",
                "HTTP POST /api/v1/registrations",
                "AuthRegistrationApi.register",
            )
        ],
        "persisted-auth": [
            ("INBOUND", "POST", "/api/v1/registrations", None, "AuthRegistrationApi.register")
        ],
    }
    results = HttpFlowCorrelationAdapter().correlate(fragments)
    assert [result.status for result in results] == [FlowCorrelationStatus.EXACT_UNVERIFIED]


def test_supporting_http_edge_metadata_does_not_become_operation_fact(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    upstream_key, _downstream_key = _seed_registration_operation_pair(db_path, outbound_edge_type="OVERRIDES")

    _nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key)

    assert all(fact.source_channel != "EDGE_METADATA" for fact in facts)
    assert all(fact.direction_role != "OUTBOUND" for fact in facts)


def test_invalid_or_contradictory_edge_direction_metadata_is_not_guessed(tmp_path):
    for direction_role in ("INBOUND", "SIDEWAYS"):
        db_path = tmp_path / f"{direction_role.lower()}.sqlite"
        upstream_key, _downstream_key = _seed_registration_operation_pair(
            db_path,
            outbound_direction_role=direction_role,
        )

        _nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key)

        assert all(fact.source_channel != "EDGE_METADATA" for fact in facts)
        assert all(fact.direction_role != "OUTBOUND" for fact in facts)


def test_claim_and_edge_duplicate_operation_dedupes_correlation_and_public_gap(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    upstream_key, downstream_key = _seed_registration_operation_pair(db_path)
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_claims(
                id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                rejection_reason, created_at, updated_at, entrypoint_kind,
                entrypoint_http_method, entrypoint_route, entrypoint_topic, entrypoint_schedule,
                entrypoint_interface_method, entrypoint_execution_kind, fact_origin, flow_domain
            )
            VALUES (
                'claim-bff-client-operation', 'semantic-job:one:fixture-bff', 'fixture-bff', 'bff-register',
                'ENTRYPOINT_HINT', 'POST /api/v1/registrations', 0.9, 'TRUSTED', NULL,
                '2026-07-25T00:00:00+00:00', '2026-07-25T00:00:00+00:00',
                'HTTP', 'POST', '/api/v1/registrations', NULL, NULL,
                'AuthRegistrationApi.register', 'CLIENT_OPERATION', 'STATIC', 'CODE'
            )
            """
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_graph_claim_evidence(claim_id, evidence_id)
            VALUES ('claim-bff-client-operation', 'ev-bff-register')
            """
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_graph_owner_evidence(
                owner_kind, owner_source_id, owner_node_id, owner_edge_id, evidence_source_id, evidence_id
            )
            VALUES ('NODE', 'fixture-bff', 'bff-register', '', 'fixture-bff', 'ev-bff-register')
            """
        )

    nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key, downstream_key)
    outbound_facts = [fact for fact in facts if fact.owner_source_id == "fixture-bff" and fact.direction_role == "OUTBOUND"]
    assert len(outbound_facts) == 1
    assert outbound_facts[0].operation_identity == "HTTP POST /api/v1/registrations"
    assert outbound_facts[0].interface_identity == "AuthRegistrationApi.register"
    assert {item.relative_path for item in outbound_facts[0].evidence} == {"src/Gateway.java"}

    families = _families(*(_flow(nodes[key]) for key in (upstream_key, downstream_key)))
    fragments = FlowNarrativePlanner().fragments(families, operation_facts=facts)
    source_fragment = next(fragment for fragment in fragments if fragment.source_id == "fixture-bff")
    assert [fact.direction_role for fact in source_fragment.operation_facts].count("OUTBOUND") == 1

    correlations = HttpFlowCorrelationAdapter().correlate(fragments)
    assert [result.status for result in correlations] == [FlowCorrelationStatus.EXACT_UNVERIFIED]

    plans, diagnostics = FlowNarrativePlanner().assemble(families, max_plans=10, operation_facts=facts)
    assert diagnostics == ()
    assert len(plans) == 1
    assert [part.kind for part in plans[0].parts].count(FlowNarrativePartKind.UNVERIFIED_GAP) == 1
    tool = FlowProjectionBuilder().to_tool_response(
        KnowledgeQueryRequest(queryText="registration", intent="FLOW_EXPLANATION"),
        type("Execution", (), {"narrative_plans": plans, "flows": (), "response": None})(),
    )
    assert sum(1 for part in tool.flows[0].parts if part.kind == "UNVERIFIED_GAP") == 1


def test_distinct_edge_backed_outbound_operations_remain_distinct(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    revision = seed_semantic_graph(
        db_path,
        source_id="multi-operation-client",
        nodes=[
            {
                "id": "gateway",
                "nodeKind": "CALLABLE",
                "name": "Gateway.send",
                "qualified": "fixture.Gateway.send",
                "path": "src/Gateway.java",
            },
        ],
        edges=[
            {
                "id": "create-operation",
                "fromNodeId": "gateway",
                "toNodeId": None,
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "metadata": {
                    "transportKind": "HTTP",
                    "httpMethod": "POST",
                    "routeTemplate": "/items",
                    "operationIdentity": "HTTP POST /items",
                },
            },
            {
                "id": "update-operation",
                "fromNodeId": "gateway",
                "toNodeId": None,
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "metadata": {
                    "transportKind": "HTTP",
                    "httpMethod": "PUT",
                    "routeTemplate": "/items/{id}",
                    "operationIdentity": "HTTP PUT /items/{id}",
                },
            },
        ],
        evidence_ids=["ev-gateway"],
    )

    _nodes, facts = _loaded_nodes_and_facts(db_path, ("multi-operation-client", revision, "gateway"))
    outbound_facts = [fact for fact in facts if fact.direction_role == "OUTBOUND"]

    assert len(outbound_facts) == 2
    assert {(fact.method, fact.normalized_route) for fact in outbound_facts} == {
        ("POST", "/items"),
        ("PUT", "/items/{id}"),
    }


def test_multiple_exact_downstream_http_operation_matches_are_ambiguous(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    upstream_key, first_downstream_key = _seed_registration_operation_pair(db_path)
    second_downstream_revision = seed_semantic_graph(
        db_path,
        source_id="fixture-auth-copy",
        nodes=[
            {
                "id": "auth-register-copy",
                "nodeKind": "CALLABLE",
                "name": "RegistrationEndpoint.accept",
                "qualified": "fixture-auth-copy.RegistrationEndpoint.accept",
                "path": "src/RegistrationEndpoint.java",
            },
        ],
        claims=[
            _claim(
                "claim-auth-register-copy",
                "auth-register-copy",
                method="POST",
                route="/api/v1/registrations",
                interface_method="AuthRegistrationApi.register",
            )
        ],
        evidence_ids=["ev-auth-register-copy"],
    )
    second_downstream_key = ("fixture-auth-copy", second_downstream_revision, "auth-register-copy")
    nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key, first_downstream_key, second_downstream_key)

    results = _correlation_results(nodes, facts, upstream_key, first_downstream_key, second_downstream_key)

    ambiguous = [result for result in results if result.status is FlowCorrelationStatus.AMBIGUOUS]
    assert len(ambiguous) == 1
    assert set(ambiguous[0].target_fragment_keys) == {":".join(first_downstream_key), ":".join(second_downstream_key)}


def test_continuation_lookup_ambiguous_missing_stale_malformed_and_test_inbound_facts_fail_closed(tmp_path):
    ambiguous_db = tmp_path / "ambiguous.sqlite"
    upstream_key, _first_downstream_key = _seed_registration_operation_pair(ambiguous_db, outbound_target_service_identity="")
    seed_semantic_graph(
        ambiguous_db,
        source_id="fixture-auth-copy",
        nodes=[
            {
                "id": "auth-register-copy",
                "nodeKind": "CALLABLE",
                "name": "RegistrationEndpoint.accept",
                "qualified": "fixture-auth-copy.RegistrationEndpoint.accept",
                "path": "src/RegistrationEndpoint.java",
            },
        ],
        claims=[_claim("claim-auth-register-copy", "auth-register-copy", route="/api/v1/registrations", interface_method="AuthRegistrationApi.register")],
        evidence_ids=["ev-auth-register-copy"],
    )
    ambiguous_service = _service(ambiguous_db)
    upstream_family = _families(_flow(EntrypointFlowGraphRepository(AnalysisStore(ambiguous_db)).load_nodes({upstream_key}, include_tests=False)[upstream_key]))
    ambiguous_result = ambiguous_service._assemble_exact_downstream_continuations(
        upstream_family,
        SourceScopeResolver(AnalysisStore(ambiguous_db)).resolve()[0],
        include_tests=False,
    )
    assert [family.key.source_id for family in ambiguous_result.families] == ["fixture-bff"]
    assert any(item.code == "FLOW_CONTINUATION_AMBIGUOUS" for item in ambiguous_result.diagnostics)

    missing_db = tmp_path / "missing.sqlite"
    missing_upstream_key, _missing_downstream_key = _seed_registration_operation_pair(missing_db, downstream_source="missing-auth")
    with sqlite3.connect(missing_db) as conn:
        conn.execute("DELETE FROM analysis_graph_claims WHERE source_id = 'missing-auth'")
    missing_repo = EntrypointFlowGraphRepository(AnalysisStore(missing_db))
    missing_facts = missing_repo.load_available_operation_facts({missing_upstream_key}, include_tests=False)
    assert missing_repo.load_matching_inbound_operation_facts(missing_facts, eligible_source_ids=["fixture-bff", "missing-auth"], include_tests=False) == ()

    stale_db = tmp_path / "stale-inbound.sqlite"
    stale_upstream_key, _stale_downstream_key = _seed_registration_operation_pair(stale_db, downstream_source="stale-auth")
    with sqlite3.connect(stale_db) as conn:
        conn.execute("UPDATE files SET content_hash = 'changed-auth-hash' WHERE source_id = 'stale-auth'")
    stale_repo = EntrypointFlowGraphRepository(AnalysisStore(stale_db))
    stale_facts = stale_repo.load_available_operation_facts({stale_upstream_key}, include_tests=False)
    assert stale_repo.load_matching_inbound_operation_facts(stale_facts, eligible_source_ids=["fixture-bff", "stale-auth"], include_tests=False) == ()

    malformed_db = tmp_path / "malformed.sqlite"
    malformed_upstream_key, _malformed_downstream_key = _seed_registration_operation_pair(malformed_db, downstream_source="malformed-auth")
    with sqlite3.connect(malformed_db) as conn:
        conn.execute("UPDATE analysis_graph_claims SET entrypoint_route = NULL WHERE source_id = 'malformed-auth'")
    malformed_repo = EntrypointFlowGraphRepository(AnalysisStore(malformed_db))
    malformed_facts = malformed_repo.load_available_operation_facts({malformed_upstream_key}, include_tests=False)
    assert malformed_repo.load_matching_inbound_operation_facts(
        malformed_facts,
        eligible_source_ids=["fixture-bff", "malformed-auth"],
        include_tests=False,
    ) == ()

    test_db = tmp_path / "test-excluded.sqlite"
    test_upstream_key, _test_downstream_key = _seed_registration_operation_pair(test_db, downstream_source="test-auth")
    with sqlite3.connect(test_db) as conn:
        conn.execute("UPDATE files SET flow_domain = 'TEST' WHERE source_id = 'test-auth'")
    test_repo = EntrypointFlowGraphRepository(AnalysisStore(test_db))
    test_facts = test_repo.load_available_operation_facts({test_upstream_key}, include_tests=False)
    assert test_repo.load_matching_inbound_operation_facts(test_facts, eligible_source_ids=["fixture-bff", "test-auth"], include_tests=False) == ()
    assert len(test_repo.load_matching_inbound_operation_facts(test_facts, eligible_source_ids=["fixture-bff", "test-auth"], include_tests=True)) == 1


def test_recursive_exact_continuation_terminates_without_duplicate_fragments_or_gaps(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        source_id="chain-a",
        nodes=[{"id": "entry-a", "nodeKind": "CALLABLE", "name": "CreateRoot.start", "qualified": "a.CreateRoot.start", "path": "src/A.java"}],
        edges=[
            {
                "id": "a-b",
                "fromNodeId": "entry-a",
                "toNodeId": None,
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "metadata": {
                    "transportKind": "HTTP",
                    "httpMethod": "POST",
                    "routeTemplate": "/b",
                    "interfaceIdentity": "ChainB.accept",
                    "targetServiceIdentity": "chain-b",
                },
            }
        ],
        claims=[_claim("claim-entry-a", "entry-a", method="POST", route="/start")],
        evidence_ids=["ev-entry-a"],
    )
    seed_semantic_graph(
        db_path,
        source_id="chain-b",
        nodes=[{"id": "entry-b", "nodeKind": "CALLABLE", "name": "ChainB.accept", "qualified": "b.ChainB.accept", "path": "src/B.java"}],
        edges=[
            {
                "id": "b-c",
                "fromNodeId": "entry-b",
                "toNodeId": None,
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "metadata": {
                    "transportKind": "HTTP",
                    "httpMethod": "POST",
                    "routeTemplate": "/c",
                    "interfaceIdentity": "ChainC.accept",
                    "targetServiceIdentity": "chain-c",
                },
            }
        ],
        claims=[_claim("claim-entry-b", "entry-b", method="POST", route="/b", interface_method="ChainB.accept")],
        evidence_ids=["ev-entry-b"],
    )
    seed_semantic_graph(
        db_path,
        source_id="chain-c",
        nodes=[{"id": "entry-c", "nodeKind": "CALLABLE", "name": "ChainC.accept", "qualified": "c.ChainC.accept", "path": "src/C.java"}],
        claims=[_claim("claim-entry-c", "entry-c", method="POST", route="/c", interface_method="ChainC.accept")],
        evidence_ids=["ev-entry-c"],
    )
    plan = QueryRetrievalPlan(
        original_query="root start",
        normalized_query="root start",
        search_queries=(),
        code_identifiers=(),
        concepts=("root start",),
        effective_intent="FLOW_EXPLANATION",
        detected_language="en",
        response_language="en",
    )

    result = _service(db_path).query_with_flows(KnowledgeQueryRequest(queryText="root start"), plan=plan)

    assert len(result.narrative_plans) == 1
    parts = result.narrative_plans[0].parts
    fragment_keys = [part.fragment.key for part in parts if part.fragment is not None]
    gap_keys = [(part.gap.from_source, part.gap.to_source, part.gap.route) for part in parts if part.gap is not None]
    assert fragment_keys == list(dict.fromkeys(fragment_keys))
    assert gap_keys == list(dict.fromkeys(gap_keys))
    assert [part.fragment.source_id for part in parts if part.fragment is not None] == ["chain-a", "chain-b", "chain-c"]
    assert [part.gap.route for part in parts if part.gap is not None] == ["/b", "/c"]


def test_missing_http_method_or_route_does_not_create_false_correlation(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    upstream_key, downstream_key = _seed_registration_operation_pair(db_path, outbound_route=None)
    nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key, downstream_key)

    assert all(fact.direction_role != "OUTBOUND" for fact in facts)
    results = _correlation_results(nodes, facts, upstream_key, downstream_key)

    assert all(result.status is not FlowCorrelationStatus.EXACT_UNVERIFIED for result in results)


def test_stale_and_non_current_edge_operation_facts_are_not_loaded(tmp_path):
    stale_db_path = tmp_path / "stale.sqlite"
    stale_key, _downstream_key = _seed_registration_operation_pair(stale_db_path)
    with sqlite3.connect(stale_db_path) as conn:
        conn.execute(
            "UPDATE files SET content_hash = 'new-hash' WHERE source_id = 'fixture-bff' AND relative_path = 'src/Gateway.java'"
        )
    _nodes, stale_facts = _loaded_nodes_and_facts(stale_db_path, stale_key)

    non_current_db_path = tmp_path / "non-current.sqlite"
    non_current_key, _other_downstream_key = _seed_registration_operation_pair(non_current_db_path, outbound_status="CANDIDATE")
    _nodes, non_current_facts = _loaded_nodes_and_facts(non_current_db_path, non_current_key)

    assert all(fact.direction_role != "OUTBOUND" for fact in stale_facts)
    assert all(fact.direction_role != "OUTBOUND" for fact in non_current_facts)


def test_non_http_helper_boundary_metadata_does_not_become_operation_fact(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    upstream_key, _downstream_key = _seed_registration_operation_pair(db_path, outbound_transport="QUEUE")
    _nodes, facts = _loaded_nodes_and_facts(db_path, upstream_key)

    assert all(fact.source_channel != "EDGE_METADATA" for fact in facts)
    assert all(fact.direction_role != "OUTBOUND" for fact in facts)


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


def test_exact_http_continuation_starts_with_source_even_when_target_ranks_higher():
    outbound = _node("Client.create", source="client", role="CLIENT_OPERATION")
    inbound = _node("Controller.create", source="service", role="EXECUTABLE")

    plans, diagnostics = FlowNarrativePlanner().assemble(
        _families(
            replace(_flow(outbound), relevance_score=0.2),
            replace(_flow(inbound), relevance_score=1.0),
        ),
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
    assert plans[0].parts[0].fragment.source_id == "client"
    assert plans[0].parts[2].fragment.source_id == "service"


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
    formatter_plan = FlowFormatterPlanBuilder().plan(plans[0])

    assert len(tool.flows) == 1
    assert [part.kind for part in tool.flows[0].parts] == [
        "VERIFIED_FRAGMENT",
        "UNVERIFIED_GAP",
        "VERIFIED_FRAGMENT",
    ]
    assert tool.flows[0].parts[0].tree.entrypoint.trigger.method == "POST"
    assert [group.kind for group in formatter_plan.groups[:2]] == [
        FlowFormatterGroupKind.ENTRYPOINT,
        FlowFormatterGroupKind.UNVERIFIED_GAP,
    ]
    assert formatter_plan.groups[0].method == "POST"
    assert formatter_plan.groups[1].certainty == "UNVERIFIED"


def test_catalog_client_operation_does_not_attach_to_unrelated_fragment():
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
    formatter_plan = FlowFormatterPlanBuilder().plan(plans[0])

    assert diagnostics == ()
    assert len(plans) == 2
    assert all(
        part.kind is not FlowNarrativePartKind.UNVERIFIED_GAP
        for plan in plans
        for part in plan.parts
    )
    assert all(
        fact.owner_source_id != "contract-source"
        for plan in plans
        for fragment in plan.fragments
        for fact in fragment.operation_facts
    )
    assert len(tool.flows) == 2
    assert all(item.kind != "OPERATION" for flow in tool.flows for item in flow.parts[0].tree.entrypoint.children)
    assert [group.kind for group in formatter_plan.groups[:1]] == [FlowFormatterGroupKind.ENTRYPOINT]


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
