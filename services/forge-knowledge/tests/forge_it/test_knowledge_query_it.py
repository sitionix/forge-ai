from __future__ import annotations

import json

import pytest
from semantic_test_support import seed_semantic_graph
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config


pytestmark = pytest.mark.forge_it


def query_payload(query_text, *, intent="UNKNOWN", answer_language="en", include_tests=False, max_flows=10):
    return {
        "queryText": query_text,
        "intent": intent,
        "answerLanguage": answer_language,
        "includeTests": include_tests,
        "maxFlows": max_flows,
    }


def test_knowledge_query_rejects_old_request_shape(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_query_graph(app_config.store_path)
    old_payload = {"qu" + "ery": "SiteController createSite", "intent": "AU" + "TO"}

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=old_payload)

    assert response.status_code == 422


def test_knowledge_query_searches_all_current_graph_sources_without_source_id(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_query_graph(app_config.store_path)

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/knowledge/query",
            json=query_payload("поясни як працює JarvisGateway"),
        )
        no_candidates = client.post("/api/v1/knowledge/query", json=query_payload("does-not-exist"))

    assert response.status_code == 200
    body = response.json()
    assert body["status"] in {"OK", "AMBIGUOUS"}
    assert {source["sourceId"] for source in body["matchedSources"]} >= {"source-a", "source-b"}
    assert all(node["sourceId"] for node in body["matchedNodes"])
    assert any(node["sourceId"] == "source-a" and node["label"] == "JarvisGateway" for node in body["matchedNodes"])
    assert body["coverage"]["searchedSourceCount"] == 2
    assert body["coverage"]["matchedNodeCount"] >= 2
    assert body["flowPaths"]
    assert body["coverage"]["flowPathCount"] == len(body["flowPaths"])
    assert body["nodes"]
    node_ids = {node["id"] for node in body["nodes"]}
    for edge in body["edges"]:
        assert edge["fromNodeId"] in node_ids
        assert edge["toNodeId"] in node_ids
    for flow in body["flowPaths"]:
        assert flow["sourceId"]
        assert flow["nodeIds"]
        assert len(flow["edgeIds"]) == max(0, len(flow["nodeIds"]) - 1)
        flow_node_ids = {node["id"] for node in flow["nodes"]}
        for edge in flow["edges"]:
            assert edge["fromNodeId"] in flow_node_ids
            assert edge["toNodeId"] in flow_node_ids
    assert body["evidence"]
    raw_text = json.dumps(body)
    assert "class JarvisGateway" not in raw_text
    assert "Knowledge context" not in raw_text
    assert "Traceback" not in raw_text

    assert no_candidates.status_code == 200
    no_candidates_body = no_candidates.json()
    assert no_candidates_body["status"] == "NO_CANDIDATES"
    assert no_candidates_body["matchedNodes"] == []
    assert no_candidates_body["flowPaths"] == []
    assert any(diagnostic["code"] == "NO_GRAPH_CANDIDATES" for diagnostic in no_candidates_body["diagnostics"])


def test_knowledge_query_integration_extracts_linear_calls_path(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_flow_graph(
        app_config.store_path,
        "flow-linear",
        [
            ("controller-create", "Controller.create", "CALLABLE"),
            ("usecase-execute", "UseCase.execute", "CALLABLE"),
            ("repository-save", "Repository.save", "CALLABLE"),
        ],
        [
            {"id": "edge-controller-usecase", "fromNodeId": "controller-create", "toNodeId": "usecase-execute"},
            {"id": "edge-usecase-repository", "fromNodeId": "usecase-execute", "toNodeId": "repository-save"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("UseCase.execute"))

    assert response.status_code == 200
    body = response.json()
    flow = body["flowPaths"][0]
    assert flow["nodeIds"] == ["controller-create", "usecase-execute", "repository-save"]
    assert flow["edgeIds"] == ["edge-controller-usecase", "edge-usecase-repository"]
    assert flow["matchedNodeIds"] == ["usecase-execute"]
    assert flow["complete"] is True
    assert flow["stopReason"] == "TERMINAL_NODE"
    assert flow["evidenceIds"]


def test_knowledge_query_integration_bounds_adjacency_around_matched_node(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    nodes = [
        ("anchor-execute", "Anchor.execute", "CALLABLE"),
        ("terminal-save", "Terminal.save", "CALLABLE"),
    ]
    edges = [{"id": "zzz-anchor-terminal", "fromNodeId": "anchor-execute", "toNodeId": "terminal-save"}]
    for index in range(2100):
        nodes.extend(
            [
                (f"noise-from-{index}", f"NoiseFrom{index}", "CALLABLE"),
                (f"noise-to-{index}", f"NoiseTo{index}", "CALLABLE"),
            ]
        )
        edges.append({"id": f"aaa-noise-{index:04d}", "fromNodeId": f"noise-from-{index}", "toNodeId": f"noise-to-{index}"})
    seed_flow_graph(app_config.store_path, "flow-large-source", nodes, edges)

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("Anchor.execute"))

    assert response.status_code == 200
    body = response.json()
    assert body["coverage"]["flowPathCount"] == 1
    flow = body["flowPaths"][0]
    assert flow["nodeIds"] == ["anchor-execute", "terminal-save"]
    assert flow["edgeIds"] == ["zzz-anchor-terminal"]
    assert not any(diagnostic["code"] == "RESULT_LIMIT_REACHED" for diagnostic in body["diagnostics"])


def test_knowledge_query_integration_extracts_multiple_entrypoints(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_flow_graph(
        app_config.store_path,
        "flow-entrypoints",
        [
            ("controller-a", "ControllerA.create", "CALLABLE"),
            ("controller-b", "ControllerB.create", "CALLABLE"),
            ("usecase-execute", "UseCase.execute", "CALLABLE"),
            ("repository-save", "Repository.save", "CALLABLE"),
        ],
        [
            {"id": "edge-a-usecase", "fromNodeId": "controller-a", "toNodeId": "usecase-execute"},
            {"id": "edge-b-usecase", "fromNodeId": "controller-b", "toNodeId": "usecase-execute"},
            {"id": "edge-usecase-save", "fromNodeId": "usecase-execute", "toNodeId": "repository-save"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("UseCase.execute"))

    body = response.json()
    assert sorted(flow["nodeIds"] for flow in body["flowPaths"]) == [
        ["controller-a", "usecase-execute", "repository-save"],
        ["controller-b", "usecase-execute", "repository-save"],
    ]


def test_knowledge_query_integration_extracts_downstream_branches(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_flow_graph(
        app_config.store_path,
        "flow-branches",
        [
            ("controller-create", "Controller.create", "CALLABLE"),
            ("usecase-execute", "UseCase.execute", "CALLABLE"),
            ("repository-save", "Repository.save", "CALLABLE"),
            ("event-publish", "EventPublisher.publish", "CALLABLE"),
        ],
        [
            {"id": "edge-controller-usecase", "fromNodeId": "controller-create", "toNodeId": "usecase-execute"},
            {"id": "edge-usecase-save", "fromNodeId": "usecase-execute", "toNodeId": "repository-save"},
            {"id": "edge-usecase-publish", "fromNodeId": "usecase-execute", "toNodeId": "event-publish"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("UseCase.execute"))

    body = response.json()
    assert sorted(flow["nodeIds"] for flow in body["flowPaths"]) == [
        ["controller-create", "usecase-execute", "event-publish"],
        ["controller-create", "usecase-execute", "repository-save"],
    ]
    assert "MAIN_FLOW" not in json.dumps(body)
    assert "PERSISTENCE" not in json.dumps(body)


def test_knowledge_query_integration_detects_calls_cycle(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_flow_graph(
        app_config.store_path,
        "flow-cycle",
        [("a", "Alpha", "CALLABLE"), ("b", "Beta", "CALLABLE"), ("c", "Gamma", "CALLABLE")],
        [
            {"id": "edge-a-b", "fromNodeId": "a", "toNodeId": "b"},
            {"id": "edge-b-c", "fromNodeId": "b", "toNodeId": "c"},
            {"id": "edge-c-a", "fromNodeId": "c", "toNodeId": "a"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("Alpha"))

    body = response.json()
    assert any(flow["stopReason"] == "CYCLE_DETECTED" and flow["complete"] is False for flow in body["flowPaths"])
    assert any(diagnostic["code"] == "CYCLE_DETECTED" for diagnostic in body["diagnostics"])


def test_knowledge_query_integration_preserves_external_and_unresolved_boundaries(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_flow_graph(
        app_config.store_path,
        "flow-boundaries",
        [
            ("controller-create", "Controller.create", "CALLABLE"),
        ],
        [
            {
                "id": "edge-external",
                "fromNodeId": "controller-create",
                "toNodeId": None,
                "resolutionStatus": "EXTERNAL_TARGET",
                "unresolved": {"name": "HttpClient.post", "kindHint": "CALLABLE"},
            },
            {"id": "edge-unresolved", "fromNodeId": "controller-create", "toNodeId": None, "resolutionStatus": "UNRESOLVED"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("Controller.create"))

    body = response.json()
    stop_reasons = {flow["stopReason"] for flow in body["flowPaths"]}
    assert {"EXTERNAL_TARGET", "UNRESOLVED_EDGE"} <= stop_reasons
    external_flow = next(flow for flow in body["flowPaths"] if flow["stopReason"] == "EXTERNAL_TARGET")
    assert external_flow["boundaryEdgeIds"] == ["edge-external"]
    assert external_flow["edgeIds"] == []
    assert body["external"][0]["unresolvedTarget"]["name"] == "HttpClient.post"
    unresolved_flow = next(flow for flow in body["flowPaths"] if flow["stopReason"] == "UNRESOLVED_EDGE")
    assert unresolved_flow["boundaryEdgeIds"] == ["edge-unresolved"]
    assert unresolved_flow["edgeIds"] == []


def test_knowledge_query_integration_searches_all_sources_for_flow_paths(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    for source_id in ("source-one", "source-two"):
        seed_flow_graph(
            app_config.store_path,
            source_id,
            [
                (f"{source_id}-controller", "Controller.create", "CALLABLE"),
                (f"{source_id}-usecase", "SharedUseCase.execute", "CALLABLE"),
                (f"{source_id}-repo", "Repository.save", "CALLABLE"),
            ],
            [
                {"id": f"{source_id}-edge-1", "fromNodeId": f"{source_id}-controller", "toNodeId": f"{source_id}-usecase"},
                {"id": f"{source_id}-edge-2", "fromNodeId": f"{source_id}-usecase", "toNodeId": f"{source_id}-repo"},
            ],
        )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json=query_payload("SharedUseCase.execute"))

    body = response.json()
    assert {flow["sourceId"] for flow in body["flowPaths"]} == {"source-one", "source-two"}
    assert body["coverage"]["searchedSourceCount"] == 2


def test_knowledge_query_stage3_code_aware_search_hardening(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_stage3_search_graph(app_config.store_path)

    with TestClient(app) as client:
        exact_file = client.post("/api/v1/knowledge/query", json=query_payload("jarvis.html")).json()
        exact_callable = client.post("/api/v1/knowledge/query", json=query_payload("submitJarvisQuery")).json()
        path_query = client.post("/api/v1/knowledge/query", json=query_payload("static/operator/jarvis.html")).json()
        qualified_full = client.post(
            "/api/v1/knowledge/query",
            json=query_payload("com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController"),
        ).json()
        qualified_suffix = client.post("/api/v1/knowledge/query", json=query_payload("ForgeAiInfrastructureJarvisController")).json()
        endpoint_query = client.post("/api/v1/knowledge/query", json=query_payload("/api/v1/knowledge/query")).json()
        lexical_query = client.post("/api/v1/knowledge/query", json=query_payload("jarvis query knowledge")).json()
        typo_query = client.post("/api/v1/knowledge/query", json=query_payload("AgetnChatPage")).json()
        exact_typo_baseline = client.post("/api/v1/knowledge/query", json=query_payload("AgentChatPage")).json()
        multi_source = client.post("/api/v1/knowledge/query", json=query_payload("SharedJarvisQuery")).json()
        no_candidates = client.post("/api/v1/knowledge/query", json=query_payload("definitely-no-such-node-xyz")).json()
        flow_integration = client.post("/api/v1/knowledge/query", json=query_payload("JarvisQueryService.query")).json()

    assert exact_file["matchedNodes"][0]["nodeId"] == "console-file-jarvis-html"
    assert exact_file["matchedNodes"][0]["sourceId"] == "forge-console-test"
    assert "EXACT_FILE_NAME" in exact_file["matchedNodes"][0]["matchReasons"]

    assert exact_callable["matchedNodes"][0]["nodeId"] == "console-submit-jarvis-query"
    assert exact_callable["matchedNodes"][0]["sourceId"] == "forge-console-test"
    assert any("console-submit-jarvis-query" in flow["matchedNodeIds"] for flow in exact_callable["flowPaths"])

    assert path_query["matchedNodes"][0]["nodeId"] == "console-file-jarvis-html"
    assert any(reason.startswith("PATH_") or reason == "PATH_MATCH" for reason in path_query["matchedNodes"][0]["matchReasons"])

    assert qualified_full["matchedNodes"][0]["nodeId"] == "nexus-type-jarvis-controller"
    assert qualified_full["matchedNodes"][0]["sourceId"] == "forge-nexus-test"
    assert "QUALIFIED_NAME_EXACT" in qualified_full["matchedNodes"][0]["matchReasons"] or "EXACT_QUALIFIED_NAME" in qualified_full["matchedNodes"][0]["matchReasons"]

    assert qualified_suffix["matchedNodes"][0]["nodeId"] == "nexus-type-jarvis-controller"
    assert qualified_suffix["matchedNodes"][0]["sourceId"] == "forge-nexus-test"

    assert endpoint_query["matchedNodes"][0]["nodeKind"] == "CALLABLE"
    assert endpoint_query["matchedNodes"][0]["sourceId"]
    assert "EXACT_ENDPOINT" in endpoint_query["matchedNodes"][0]["matchReasons"]

    lexical_node_ids = {node["nodeId"] for node in lexical_query["matchedNodes"]}
    assert {"jarvis-query-service-query", "jarvis-knowledge-client-query", "knowledge-query-service-query"} & lexical_node_ids
    assert {node["sourceId"] for node in lexical_query["matchedNodes"]} >= {"forge-jarvis-test", "forge-knowledge-test"}
    assert lexical_query["coverage"]["searchedSourceCount"] == 4

    assert typo_query["matchedNodes"][0]["nodeId"] == "console-agent-chat-page"
    assert typo_query["matchedNodes"][0]["sourceId"] == "forge-console-test"
    assert any(reason.startswith("FUZZY") for reason in typo_query["matchedNodes"][0]["matchReasons"])
    assert exact_typo_baseline["matchedNodes"][0]["nodeId"] == "console-agent-chat-page"
    assert exact_typo_baseline["matchedNodes"][0]["score"] > typo_query["matchedNodes"][0]["score"]

    assert {node["sourceId"] for node in multi_source["matchedNodes"] if node["label"] == "SharedJarvisQuery"} >= {
        "forge-jarvis-test",
        "forge-knowledge-test",
    }

    assert no_candidates["status"] == "NO_CANDIDATES"
    assert no_candidates["matchedNodes"] == []
    assert no_candidates["flowPaths"] == []
    assert any(diagnostic["code"] == "NO_GRAPH_CANDIDATES" for diagnostic in no_candidates["diagnostics"])

    assert flow_integration["matchedNodes"][0]["nodeId"] == "jarvis-query-service-query"
    assert flow_integration["flowPaths"]
    for flow in flow_integration["flowPaths"]:
        edges_by_id = {edge["id"]: edge for edge in flow["edges"]}
        for index, edge_id in enumerate(flow["edgeIds"]):
            edge = edges_by_id[edge_id]
            assert edge["fromNodeId"] == flow["nodeIds"][index]
            assert edge["toNodeId"] == flow["nodeIds"][index + 1]



def seed_stage3_search_graph(db_path):
    full_controller = "com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController"
    sources = {
        "forge-console-test": {
            "nodes": [
                ("console-file-jarvis-html", "FILE", "jarvis.html", "", "boot/src/main/resources/static/operator/jarvis.html"),
                ("console-file-operator-ui", "FILE", "operator-ui.js", "", "boot/src/main/resources/static/operator/operator-ui.js"),
                ("console-submit-jarvis-query", "CALLABLE", "submitJarvisQuery", "operator.submitJarvisQuery", "boot/src/main/resources/static/operator/operator-ui.js"),
                ("console-agent-chat-page", "TYPE", "AgentChatPage", "ui.AgentChatPage", "src/pages/AgentChatPage.tsx"),
            ],
            "edges": [("console-call-submit", "console-agent-chat-page", "console-submit-jarvis-query", "RESOLVED")],
        },
        "forge-nexus-test": {
            "nodes": [
                ("nexus-type-jarvis-controller", "TYPE", "ForgeAiInfrastructureJarvisController", full_controller, "src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java"),
                ("nexus-callable-query", "CALLABLE", "query", f"{full_controller}.query", "src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java"),
            ],
            "edges": [
                ("nexus-call-controller-query", "nexus-type-jarvis-controller", "nexus-callable-query", "RESOLVED"),
                (
                    "nexus-call-query-endpoint",
                    "nexus-callable-query",
                    None,
                    "EXTERNAL_TARGET",
                    "CALLS",
                    {"name": "/api/v1/knowledge/query", "kindHint": "HTTP_ENDPOINT"},
                ),
            ],
        },
        "forge-jarvis-test": {
            "nodes": [
                ("jarvis-query-service-query", "CALLABLE", "JarvisQueryService.query", "jarvis_agent.query_service.JarvisQueryService.query", "src/jarvis_agent/query_service.py"),
                ("jarvis-knowledge-client-query", "CALLABLE", "KnowledgeClient.query", "jarvis_agent.knowledge_client.KnowledgeClient.query", "src/jarvis_agent/knowledge_client.py"),
                ("jarvis-shared-query", "CALLABLE", "SharedJarvisQuery", "jarvis_agent.SharedJarvisQuery", "src/jarvis_agent/query_service.py"),
            ],
            "edges": [
                ("jarvis-call-service-client", "jarvis-query-service-query", "jarvis-knowledge-client-query", "RESOLVED"),
                (
                    "jarvis-call-client-endpoint",
                    "jarvis-knowledge-client-query",
                    None,
                    "EXTERNAL_TARGET",
                    "CALLS",
                    {"name": "/api/v1/knowledge/query", "kindHint": "HTTP_ENDPOINT"},
                ),
            ],
        },
        "forge-knowledge-test": {
            "nodes": [
                ("knowledge-query-service-type", "TYPE", "KnowledgeQueryService", "knowledge_service.query.KnowledgeQueryService", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-query-service-query", "CALLABLE", "KnowledgeQueryService.query", "knowledge_service.query.KnowledgeQueryService.query", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-unified-searcher-search", "CALLABLE", "UnifiedAnchorSearcher.search", "knowledge_service.query.UnifiedAnchorSearcher.search", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-flow-extractor-extract", "CALLABLE", "FlowPathExtractor.extract", "knowledge_service.query.FlowPathExtractor.extract", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-shared-query", "CALLABLE", "SharedJarvisQuery", "knowledge_service.SharedJarvisQuery", "src/knowledge_service/knowledge_query_service.py"),
            ],
            "edges": [
                ("knowledge-call-service-searcher", "knowledge-query-service-query", "knowledge-unified-searcher-search", "RESOLVED"),
                ("knowledge-call-searcher-flow", "knowledge-unified-searcher-search", "knowledge-flow-extractor-extract", "RESOLVED"),
                (
                    "knowledge-call-query-endpoint",
                    "knowledge-query-service-query",
                    None,
                    "EXTERNAL_TARGET",
                    "CALLS",
                    {"name": "/api/v1/knowledge/query", "kindHint": "HTTP_ENDPOINT"},
                ),
            ],
        },
    }
    for source_id, fixture in sources.items():
        _seed_current_graph_from_fixture(db_path, source_id, fixture["nodes"], fixture["edges"], graph_suffix="stage3")


def seed_query_graph(db_path):
    for source_id, gateway_name in (("source-a", "JarvisGateway"), ("source-b", "JarvisGatewayAdapter")):
        path = f"src/{gateway_name}.java"
        nodes = [
            (f"{source_id}-file", "FILE", f"{gateway_name}.java", f"{source_id}|FILE|{gateway_name}.java", path),
            (f"{source_id}-type", "TYPE", gateway_name.replace("Gateway", "GatewayType"), f"example.{gateway_name}", path),
            (f"{source_id}-gateway", "CALLABLE", gateway_name, f"example.{gateway_name}", path),
            (f"{source_id}-helper", "CALLABLE", f"{gateway_name}Helper", f"example.{gateway_name}Helper", path),
        ]
        edges = [
            (f"{source_id}-decl-file-type", f"{source_id}-file", f"{source_id}-type", "RESOLVED", "DECLARES"),
            (f"{source_id}-decl-type-gateway", f"{source_id}-type", f"{source_id}-gateway", "RESOLVED", "DECLARES"),
            (f"{source_id}-call-gateway-helper", f"{source_id}-gateway", f"{source_id}-helper", "RESOLVED", "CALLS"),
        ]
        claims = [
            {
                "id": f"{source_id}-claim-gateway",
                "node_id": f"{source_id}-gateway",
                "summary": f"{gateway_name} orchestrates local query calls.",
                "evidence_ids": ["ev-node-query"],
            }
        ]
        _seed_current_graph_from_fixture(db_path, source_id, nodes, edges, graph_suffix="query", claims=claims)


def seed_flow_graph(db_path, source_id, node_rows, edge_rows):
    nodes = [
        (node_id, kind, label, f"example.{label}", f"src/{source_id}/Flow.java")
        for node_id, label, kind in node_rows
    ]
    edges = [
        (
            edge["id"],
            edge["fromNodeId"],
            edge.get("toNodeId"),
            edge.get("resolutionStatus") or ("RESOLVED" if edge.get("toNodeId") else "UNRESOLVED"),
            "CALLS",
            edge.get("unresolved"),
        )
        for edge in edge_rows
    ]
    _seed_current_graph_from_fixture(db_path, source_id, nodes, edges, graph_suffix="flow")


def _seed_current_graph_from_fixture(db_path, source_id, node_rows, edge_rows, *, graph_suffix, claims=None):
    nodes = [
        {
            "id": node_id,
            "nodeKind": kind,
            "name": name,
            "qualified": qualified_name or name,
            "path": relative_path,
            "line_start": index,
            "line_end": index,
        }
        for index, (node_id, kind, name, qualified_name, relative_path) in enumerate(node_rows, start=1)
    ]
    edges = [
        {
            "id": edge_id,
            "fromNodeId": from_node,
            "toNodeId": to_node,
            "edgeType": edge_type,
            "resolutionStatus": resolution_status,
        }
        for edge_id, from_node, to_node, resolution_status, *edge_type_value in edge_rows
        for edge_type in [edge_type_value[0] if edge_type_value else "CALLS"]
    ]
    for edge, row in zip(edges, edge_rows):
        if len(row) > 5:
            edge["unresolved"] = row[5]
    seed_semantic_graph(db_path, source_id=source_id, graph_suffix=graph_suffix, nodes=nodes, edges=edges, claims=claims or [])
