from __future__ import annotations

import pytest
from support import build_test_app, write_runtime_config

from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import build_knowledge_query_service
from knowledge_service.overview_projection import read_overview
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStore
from semantic_test_support import seed_semantic_graph


pytestmark = pytest.mark.forge_it


def test_semantic_query_improves_human_retrieval_and_flow_paths(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    assert app is not None
    seed_semantic_flow_fixture(app_config.store_path, refresh_overview=True)
    provider = fake_semantic_provider()
    SemanticIndexBuilder(
        app_config.store_path,
        provider,
        config=SemanticBuildConfig(batch_size=2, max_edges_per_document=8),
    ).build(["forge-semantic-test"], force=True)
    service = build_knowledge_query_service(deps.analysis_store, app_config, embedding_provider=provider)

    response = service.query(KnowledgeQueryRequest(query="де Jarvis передає query в Knowledge"))

    matched_ids = [node.nodeId for node in response.matchedNodes]
    assert {"jarvis-query-service-query", "jarvis-knowledge-client-query", "knowledge-query-service-query"} & set(matched_ids)
    assert any("SEMANTIC_VECTOR_SIMILARITY" in node.matchReasons for node in response.matchedNodes)
    if "wiremock-query-params" in matched_ids:
        assert matched_ids.index("wiremock-query-params") > min(
            matched_ids.index(node_id)
            for node_id in matched_ids
            if node_id in {"jarvis-query-service-query", "jarvis-knowledge-client-query", "knowledge-query-service-query"}
        )
    assert response.flowPaths
    for flow in response.flowPaths:
        edges_by_id = {edge["id"]: edge for edge in flow.edges}
        for index, edge_id in enumerate(flow.edgeIds):
            edge = edges_by_id[edge_id]
            assert edge["fromNodeId"] == flow.nodeIds[index]
            assert edge["toNodeId"] == flow.nodeIds[index + 1]


def test_exact_query_beats_semantic_candidate(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_flow_fixture(app_config.store_path)
    provider = fake_semantic_provider()
    SemanticIndexBuilder(app_config.store_path, provider, config=SemanticBuildConfig()).build(["forge-semantic-test"], force=True)
    service = build_knowledge_query_service(deps.analysis_store, app_config, embedding_provider=provider)

    response = service.query(KnowledgeQueryRequest(query="WireMockQueryParams"))

    assert response.matchedNodes[0].nodeId == "wiremock-query-params"
    assert "NAME_MATCH" in response.matchedNodes[0].matchReasons


@pytest.mark.parametrize("state", ["PENDING", "STALE", "FAILED"])
def test_query_falls_back_when_semantic_not_ready(tmp_path, state):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_flow_fixture(app_config.store_path)
    provider = fake_semantic_provider()
    if state == "STALE":
        SemanticIndexBuilder(app_config.store_path, provider, config=SemanticBuildConfig()).build(["forge-semantic-test"], force=True)
        seed_semantic_flow_fixture(app_config.store_path, graph_suffix="new", extra_node=True)
    elif state == "FAILED":
        graph = SemanticIndexStore(app_config.store_path)
        current = graph.status_for_source("forge-semantic-test")
        graph.mark_source_failed(
            "forge-semantic-test",
            current.graph_revision or "revision",
            current.total_node_count,
            error="test failure",
            diagnostics=[{"code": "SEMANTIC_BUILD_FAILED"}],
        )
    service = build_knowledge_query_service(deps.analysis_store, app_config, embedding_provider=provider)

    response = service.query(KnowledgeQueryRequest(query="Jarvis query Knowledge"))

    assert response.matchedNodes
    assert response.status in {"OK", "AMBIGUOUS"}
    if state == "STALE":
        assert any(diagnostic.code == "SEMANTIC_INDEX_STALE" for diagnostic in response.diagnostics)
    if state == "FAILED":
        assert any(diagnostic.code == "SEMANTIC_INDEX_FAILED" for diagnostic in response.diagnostics)


def test_service_overview_reports_semantic_ready_after_build(tmp_path):
    _, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_flow_fixture(app_config.store_path, refresh_overview=True)
    provider = fake_semantic_provider()
    SemanticIndexBuilder(app_config.store_path, provider, config=SemanticBuildConfig()).build(["forge-semantic-test"], force=True)

    overview = read_overview(app_config.store_path)

    source = next(source for source in overview["sources"] if source["sourceId"] == "forge-semantic-test")
    assert "semanticIndex" not in source
    assert source["analysis"]["semanticPercent"] == source["analysis"]["percent"]


def fake_semantic_provider():
    return FakeDeterministicEmbeddingProvider(
        dimension=32,
        semantic_keywords={
            "jarvis_knowledge_query": ("jarvis", "knowledge", "query", "передає"),
        },
    )


def seed_semantic_flow_fixture(db_path, *, graph_suffix="one", refresh_overview=False, extra_node=False):
    nodes = [
        {
            "id": "jarvis-query-service-query",
            "kind": "CALLABLE",
            "name": "JarvisQueryService.query",
            "qualified": "jarvis.JarvisQueryService.query",
            "path": "src/jarvis/query_service.py",
        },
        {
            "id": "jarvis-knowledge-client-query",
            "kind": "CALLABLE",
            "name": "KnowledgeClient.query",
            "qualified": "jarvis.KnowledgeClient.query",
            "path": "src/jarvis/knowledge_client.py",
        },
        {
            "id": "knowledge-query-service-query",
            "kind": "CALLABLE",
            "name": "KnowledgeQueryService.query",
            "qualified": "knowledge.KnowledgeQueryService.query",
            "path": "src/knowledge/query_service.py",
        },
        {
            "id": "wiremock-query-params",
            "kind": "TYPE",
            "name": "WireMockQueryParams",
            "qualified": "test.WireMockQueryParams",
            "path": "src/test/WireMockQueryParams.java",
        },
    ]
    if extra_node:
        nodes.append(
            {
                "id": "new-semantic-node",
                "kind": "CALLABLE",
                "name": "NewSemanticNode.query",
                "qualified": "example.NewSemanticNode.query",
                "path": "src/new_semantic_node.py",
            }
        )
    seed_semantic_graph(
        db_path,
        source_id="forge-semantic-test",
        graph_suffix=graph_suffix,
        refresh_overview=refresh_overview,
        nodes=nodes,
        edges=[
            {"id": "edge-jarvis-client", "from": "jarvis-query-service-query", "to": "jarvis-knowledge-client-query", "type": "CALLS"},
            {"id": "edge-client-knowledge", "from": "jarvis-knowledge-client-query", "to": "knowledge-query-service-query", "type": "CALLS"},
            {
                "id": "edge-client-endpoint",
                "from": "jarvis-knowledge-client-query",
                "to": None,
                "type": "CALLS",
                "resolution": "EXTERNAL_TARGET",
                "unresolved": {"name": "/api/v1/knowledge/query", "kindHint": "HTTP_ENDPOINT"},
            },
        ],
        claims=[
            {
                "id": "claim-jarvis-service",
                "node_id": "jarvis-query-service-query",
                "summary": "Receives Jarvis query text and passes it to KnowledgeClient.query.",
                "evidence_ids": ["ev-node-query"],
            },
            {
                "id": "claim-knowledge-client",
                "node_id": "jarvis-knowledge-client-query",
                "summary": "Sends Jarvis query payload to the Knowledge query endpoint.",
                "evidence_ids": ["ev-node-query"],
            },
            {
                "id": "claim-knowledge-service",
                "node_id": "knowledge-query-service-query",
                "summary": "Handles Knowledge query requests and extracts matched flow paths.",
                "evidence_ids": ["ev-node-query"],
            },
            {
                "id": "claim-wiremock",
                "node_id": "wiremock-query-params",
                "summary": "Parses WireMock query parameters in tests.",
                "evidence_ids": ["ev-node-query"],
            },
        ],
    )
