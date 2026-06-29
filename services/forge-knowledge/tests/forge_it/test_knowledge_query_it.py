from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone

import pytest
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config


pytestmark = pytest.mark.forge_it


def test_knowledge_query_searches_all_current_graph_sources_without_source_id(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_query_graph(app_config.store_path)

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/knowledge/query",
            json={"query": "поясни як працює JarvisGateway", "intent": "AUTO"},
        )
        no_candidates = client.post("/api/v1/knowledge/query", json={"query": "does-not-exist"})

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
            {"id": "edge-controller-usecase", "from": "controller-create", "to": "usecase-execute"},
            {"id": "edge-usecase-repository", "from": "usecase-execute", "to": "repository-save"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "UseCase.execute", "intent": "AUTO"})

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
    edges = [{"id": "zzz-anchor-terminal", "from": "anchor-execute", "to": "terminal-save"}]
    for index in range(2100):
        nodes.extend(
            [
                (f"noise-from-{index}", f"NoiseFrom{index}", "CALLABLE"),
                (f"noise-to-{index}", f"NoiseTo{index}", "CALLABLE"),
            ]
        )
        edges.append({"id": f"aaa-noise-{index:04d}", "from": f"noise-from-{index}", "to": f"noise-to-{index}"})
    seed_flow_graph(app_config.store_path, "flow-large-source", nodes, edges)

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "Anchor.execute", "intent": "AUTO"})

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
            {"id": "edge-a-usecase", "from": "controller-a", "to": "usecase-execute"},
            {"id": "edge-b-usecase", "from": "controller-b", "to": "usecase-execute"},
            {"id": "edge-usecase-save", "from": "usecase-execute", "to": "repository-save"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "UseCase.execute"})

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
            {"id": "edge-controller-usecase", "from": "controller-create", "to": "usecase-execute"},
            {"id": "edge-usecase-save", "from": "usecase-execute", "to": "repository-save"},
            {"id": "edge-usecase-publish", "from": "usecase-execute", "to": "event-publish"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "UseCase.execute"})

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
            {"id": "edge-a-b", "from": "a", "to": "b"},
            {"id": "edge-b-c", "from": "b", "to": "c"},
            {"id": "edge-c-a", "from": "c", "to": "a"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "Alpha"})

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
            ("external-http", "HttpClient.post", "EXTERNAL"),
        ],
        [
            {"id": "edge-external", "from": "controller-create", "to": "external-http", "resolution": "EXTERNAL_TARGET"},
            {"id": "edge-unresolved", "from": "controller-create", "to": None, "resolution": "UNRESOLVED"},
        ],
    )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "Controller.create"})

    body = response.json()
    stop_reasons = {flow["stopReason"] for flow in body["flowPaths"]}
    assert {"EXTERNAL_NODE", "UNRESOLVED_EDGE"} <= stop_reasons
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
                {"id": f"{source_id}-edge-1", "from": f"{source_id}-controller", "to": f"{source_id}-usecase"},
                {"id": f"{source_id}-edge-2", "from": f"{source_id}-usecase", "to": f"{source_id}-repo"},
            ],
        )

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/query", json={"query": "SharedUseCase.execute"})

    body = response.json()
    assert {flow["sourceId"] for flow in body["flowPaths"]} == {"source-one", "source-two"}
    assert body["coverage"]["searchedSourceCount"] == 2


def test_knowledge_query_stage3_code_aware_search_hardening(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_stage3_search_graph(app_config.store_path)

    with TestClient(app) as client:
        exact_file = client.post("/api/v1/knowledge/query", json={"query": "jarvis.html"}).json()
        exact_callable = client.post("/api/v1/knowledge/query", json={"query": "submitJarvisQuery"}).json()
        path_query = client.post("/api/v1/knowledge/query", json={"query": "static/operator/jarvis.html"}).json()
        qualified_full = client.post(
            "/api/v1/knowledge/query",
            json={"query": "com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController"},
        ).json()
        qualified_suffix = client.post("/api/v1/knowledge/query", json={"query": "ForgeAiInfrastructureJarvisController"}).json()
        endpoint_query = client.post("/api/v1/knowledge/query", json={"query": "/api/v1/knowledge/query"}).json()
        lexical_query = client.post("/api/v1/knowledge/query", json={"query": "jarvis query knowledge"}).json()
        typo_query = client.post("/api/v1/knowledge/query", json={"query": "AgetnChatPage"}).json()
        exact_typo_baseline = client.post("/api/v1/knowledge/query", json={"query": "AgentChatPage"}).json()
        multi_source = client.post("/api/v1/knowledge/query", json={"query": "SharedJarvisQuery"}).json()
        no_candidates = client.post("/api/v1/knowledge/query", json={"query": "definitely-no-such-node-xyz"}).json()
        flow_integration = client.post("/api/v1/knowledge/query", json={"query": "JarvisQueryService.query"}).json()

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

    assert endpoint_query["matchedNodes"][0]["kind"] == "EXTERNAL"
    assert endpoint_query["matchedNodes"][0]["label"] == "/api/v1/knowledge/query"
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
    now = datetime.now(timezone.utc).isoformat()
    full_controller = "com.sitionix.forgeai.api.ForgeAiInfrastructureJarvisController"
    sources = {
        "forge-console-test": {
            "display": "Forge Console Test",
            "files": [
                "boot/src/main/resources/static/operator/jarvis.html",
                "boot/src/main/resources/static/operator/operator-ui.js",
                "src/pages/AgentChatPage.tsx",
            ],
            "nodes": [
                ("console-file-jarvis-html", "FILE", "jarvis.html", "", "boot/src/main/resources/static/operator/jarvis.html"),
                ("console-file-operator-ui", "FILE", "operator-ui.js", "", "boot/src/main/resources/static/operator/operator-ui.js"),
                ("console-submit-jarvis-query", "CALLABLE", "submitJarvisQuery", "operator.submitJarvisQuery", "boot/src/main/resources/static/operator/operator-ui.js"),
                ("console-render-jarvis-result", "CALLABLE", "renderJarvisResult", "operator.renderJarvisResult", "boot/src/main/resources/static/operator/operator-ui.js"),
                ("console-agent-chat-page", "TYPE", "AgentChatPage", "console.AgentChatPage", "src/pages/AgentChatPage.tsx"),
                ("console-infra-endpoint", "EXTERNAL", "/api/v1/infrastructure/jarvis/query", "/api/v1/infrastructure/jarvis/query", "boot/src/main/resources/static/operator/operator-ui.js"),
            ],
            "edges": [
                ("console-call-submit-endpoint", "console-submit-jarvis-query", "console-infra-endpoint", "EXTERNAL_TARGET"),
                ("console-call-submit-render", "console-submit-jarvis-query", "console-render-jarvis-result", "RESOLVED"),
            ],
        },
        "forge-nexus-test": {
            "display": "Forge Nexus Test",
            "files": ["src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java"],
            "nodes": [
                ("nexus-type-jarvis-controller", "TYPE", "ForgeAiInfrastructureJarvisController", full_controller, "src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java"),
                ("nexus-controller-query", "CALLABLE", "ForgeAiInfrastructureJarvisController.query", f"{full_controller}.query", "src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java"),
                ("nexus-infra-endpoint", "EXTERNAL", "/api/v1/infrastructure/jarvis/query", "/api/v1/infrastructure/jarvis/query", "src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java"),
            ],
            "edges": [("nexus-call-controller-endpoint", "nexus-controller-query", "nexus-infra-endpoint", "EXTERNAL_TARGET")],
        },
        "forge-jarvis-test": {
            "display": "Forge Jarvis Test",
            "files": ["src/jarvis_agent/query_service.py", "src/jarvis_agent/knowledge_client.py"],
            "nodes": [
                ("jarvis-query-service-query", "CALLABLE", "JarvisQueryService.query", "jarvis_agent.query_service.JarvisQueryService.query", "src/jarvis_agent/query_service.py"),
                ("jarvis-knowledge-client-query", "CALLABLE", "KnowledgeClient.query", "jarvis_agent.knowledge_client.KnowledgeClient.query", "src/jarvis_agent/knowledge_client.py"),
                ("jarvis-knowledge-endpoint", "EXTERNAL", "/api/v1/knowledge/query", "/api/v1/knowledge/query", "src/jarvis_agent/knowledge_client.py"),
                ("jarvis-shared-query", "CALLABLE", "SharedJarvisQuery", "jarvis_agent.SharedJarvisQuery", "src/jarvis_agent/query_service.py"),
            ],
            "edges": [
                ("jarvis-call-service-client", "jarvis-query-service-query", "jarvis-knowledge-client-query", "RESOLVED"),
                ("jarvis-call-client-endpoint", "jarvis-knowledge-client-query", "jarvis-knowledge-endpoint", "EXTERNAL_TARGET"),
            ],
        },
        "forge-knowledge-test": {
            "display": "Forge Knowledge Test",
            "files": ["src/knowledge_service/knowledge_query_service.py"],
            "nodes": [
                ("knowledge-query-service-type", "TYPE", "KnowledgeQueryService", "knowledge_service.query.KnowledgeQueryService", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-query-service-query", "CALLABLE", "KnowledgeQueryService.query", "knowledge_service.query.KnowledgeQueryService.query", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-unified-searcher-search", "CALLABLE", "UnifiedAnchorSearcher.search", "knowledge_service.query.UnifiedAnchorSearcher.search", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-flow-extractor-extract", "CALLABLE", "FlowPathExtractor.extract", "knowledge_service.query.FlowPathExtractor.extract", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-endpoint", "EXTERNAL", "/api/v1/knowledge/query", "/api/v1/knowledge/query", "src/knowledge_service/knowledge_query_service.py"),
                ("knowledge-shared-query", "CALLABLE", "SharedJarvisQuery", "knowledge_service.SharedJarvisQuery", "src/knowledge_service/knowledge_query_service.py"),
            ],
            "edges": [
                ("knowledge-call-service-searcher", "knowledge-query-service-query", "knowledge-unified-searcher-search", "RESOLVED"),
                ("knowledge-call-searcher-flow", "knowledge-unified-searcher-search", "knowledge-flow-extractor-extract", "RESOLVED"),
            ],
        },
    }
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        file_id = 90000
        for source_id, fixture in sources.items():
            snapshot_id = f"stage3:{source_id}"
            job_id = f"stage3-job:{source_id}"
            conn.execute(
                """
                INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
                VALUES (?, ?, 'stage3', '.', 1, '[]', '{}', ?)
                """,
                (source_id, fixture["display"], now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
                VALUES (?, ?, ?, 'PUBLISHED', ?, ?, '{}', ?)
                """,
                (snapshot_id, source_id, job_id, now, now, f"{source_id}:stage3"),
            )
            file_ids = {}
            for relative_path in fixture["files"]:
                file_id += 1
                file_ids[relative_path] = file_id
                extension = "." + relative_path.rsplit(".", 1)[-1] if "." in relative_path.rsplit("/", 1)[-1] else ""
                conn.execute(
                    """
                    INSERT OR REPLACE INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
                    VALUES (?, ?, '.', '.', ?, ?, 'fixture', 'CODE', 100, ?, ?, 20, 'utf-8:replace', ?)
                    """,
                    (file_id, source_id, relative_path, extension, f"hash-{source_id}-{file_id}", now, now),
                )
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version, flow_domain)
                    VALUES (?, ?, ?, ?, 'stage3-fixture', '1', 'ANALYZED', ?, ?, ?, '[]', 'GRAPH_V1', 'CODE')
                    """,
                    (file_id, source_id, relative_path, f"hash-{source_id}-{file_id}", now, len(fixture["nodes"]), len(fixture["edges"])),
                )
            for index, (node_id, kind, name, qualified_name, relative_path) in enumerate(fixture["nodes"], start=1):
                node_file_id = file_ids[relative_path]
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_nodes(
                        id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language, name,
                        qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, metadata_json,
                        created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'fixture', ?, ?, ?, NULL, ?, ?, 0.96, 'TRUSTED', ?, ?, 'STATIC', 'CODE')
                    """,
                    (
                        node_id,
                        snapshot_id,
                        job_id,
                        source_id,
                        node_file_id,
                        node_file_id,
                        f"{source_id}|{relative_path}|{kind}|{name}",
                        kind,
                        name,
                        qualified_name or name,
                        name,
                        index,
                        index,
                        json.dumps({"displayScore": 1.0}),
                        now,
                    ),
                )
            for index, (edge_id, from_node, to_node, resolution_status) in enumerate(fixture["edges"], start=1):
                evidence_id = f"ev-{edge_id}"
                first_file_id = next(iter(file_ids.values()))
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_evidence(
                        id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                        line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, 'EDGE', ?, ?, ?, '{}', ?, 'STATIC', 'CODE')
                    """,
                    (evidence_id, snapshot_id, job_id, source_id, first_file_id, f"hash-{source_id}-edge", f"excerpt-{edge_id}", index, index, now),
                )
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_edges(
                        id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                        resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                        created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CALLS', ?, 0.91, ?, NULL, '{}', 'TRUSTED', ?, 'STATIC', 'CODE')
                    """,
                    (edge_id, snapshot_id, job_id, source_id, first_file_id, first_file_id, from_node, to_node, resolution_status, evidence_id, now),
                )
            conn.execute(
                """
                INSERT OR REPLACE INTO graph_current_snapshots(source_id, snapshot_id, published_at)
                VALUES (?, ?, ?)
                """,
                (source_id, snapshot_id, now),
            )


def seed_query_graph(db_path):
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        for source_id, display_name, snapshot_id, file_id, gateway_name in [
            ("source-a", "Source A", "query-a:source-a", 1001, "JarvisGateway"),
            ("source-b", "Source B", "query-b:source-b", 2001, "JarvisGatewayAdapter"),
        ]:
            conn.execute(
                """
                INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
                VALUES (?, ?, 'test', '.', 1, '[]', '{}', ?)
                """,
                (source_id, display_name, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
                VALUES (?, ?, '.', '.', ?, '.java', 'java', 'CODE', 100, ?, ?, 20, 'utf-8:replace', ?)
                """,
                (file_id, source_id, f"src/{gateway_name}.java", f"hash-{source_id}", now, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version, flow_domain)
                VALUES (?, ?, ?, ?, 'fixture', '1', 'ANALYZED', ?, 4, 3, '[]', 'GRAPH_V1', 'CODE')
                """,
                (file_id, source_id, f"src/{gateway_name}.java", f"hash-{source_id}", now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
                VALUES (?, ?, ?, 'PUBLISHED', ?, ?, '{}', ?)
                """,
                (snapshot_id, source_id, snapshot_id.split(":", 1)[0], now, now, f"{source_id}:CODE:query-revision"),
            )
            node_rows = [
                ("file", "FILE", f"{gateway_name}.java", f"{source_id}|FILE|{gateway_name}.java", None),
                ("type", "TYPE", gateway_name.replace("Gateway", "GatewayType"), f"example.{gateway_name}", "file"),
                ("gateway", "CALLABLE", gateway_name, f"example.{gateway_name}", "type"),
                ("helper", "CALLABLE", f"{gateway_name}Helper", f"example.{gateway_name}Helper", "type"),
            ]
            for index, (suffix, kind, name, stable_key, parent_suffix) in enumerate(node_rows, start=1):
                node_id = f"{source_id}-{suffix}"
                parent_id = f"{source_id}-{parent_suffix}" if parent_suffix else None
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_nodes(
                        id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language, name,
                        qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, metadata_json,
                        created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'java', ?, ?, ?, ?, ?, ?, 0.95, 'TRUSTED', ?, ?, 'STATIC', 'CODE')
                    """,
                    (
                        node_id,
                        snapshot_id,
                        snapshot_id.split(":", 1)[0],
                        source_id,
                        file_id,
                        file_id,
                        stable_key,
                        kind,
                        name,
                        stable_key,
                        name,
                        parent_id,
                        index,
                        index,
                        json.dumps({"displayScore": 1.0}),
                        now,
                    ),
                )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                    line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, 'CLAIM', ?, 3, 6, '{}', ?, 'STATIC', 'CODE')
                """,
                (f"{source_id}-ev-gateway", snapshot_id, snapshot_id.split(":", 1)[0], source_id, file_id, f"hash-{source_id}", f"excerpt-{source_id}", now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_claims(
                    id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence,
                    status, evidence_ids_json, metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, 'RESPONSIBILITY', ?, 0.9, 'TRUSTED', ?, '{}', NULL, ?, 'STATIC', 'CODE')
                """,
                (
                    f"{source_id}-claim-gateway",
                    snapshot_id,
                    snapshot_id.split(":", 1)[0],
                    source_id,
                    f"{source_id}-gateway",
                    f"{gateway_name} orchestrates local query calls.",
                    json.dumps([f"{source_id}-ev-gateway"]),
                    now,
                ),
            )
            edge_rows = [
                ("decl-file-type", f"{source_id}-file", f"{source_id}-type", "DECLARES"),
                ("decl-type-gateway", f"{source_id}-type", f"{source_id}-gateway", "DECLARES"),
                ("call-gateway-helper", f"{source_id}-gateway", f"{source_id}-helper", "CALLS"),
            ]
            for suffix, from_node, to_node, edge_type in edge_rows:
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_edges(
                        id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                        resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                        created_at, fact_origin, flow_domain
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESOLVED', 0.85, NULL, NULL, '{}', 'TRUSTED', ?, 'STATIC', 'CODE')
                    """,
                    (f"{source_id}-{suffix}", snapshot_id, snapshot_id.split(":", 1)[0], source_id, file_id, file_id, from_node, to_node, edge_type, now),
                )
            conn.execute(
                """
                INSERT OR REPLACE INTO graph_current_snapshots(source_id, snapshot_id, published_at)
                VALUES (?, ?, ?)
                """,
                (source_id, snapshot_id, now),
            )


def seed_flow_graph(db_path, source_id, node_rows, edge_rows):
    now = datetime.now(timezone.utc).isoformat()
    snapshot_id = f"snapshot:{source_id}"
    job_id = f"job:{source_id}"
    file_id = 50000 + sum((index + 1) * ord(char) for index, char in enumerate(source_id))
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES (?, ?, 'test', '.', 1, '[]', '{}', ?)
            """,
            (source_id, source_id.replace("-", " ").title(), now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
            VALUES (?, ?, '.', '.', ?, '.java', 'java', 'CODE', 100, ?, ?, 20, 'utf-8:replace', ?)
            """,
            (file_id, source_id, f"src/{source_id}/Flow.java", f"hash-{source_id}", now, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, 'fixture', '1', 'ANALYZED', ?, ?, ?, '[]', 'GRAPH_V1', 'CODE')
            """,
            (file_id, source_id, f"src/{source_id}/Flow.java", f"hash-{source_id}", now, len(node_rows), len(edge_rows)),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
            VALUES (?, ?, ?, 'PUBLISHED', ?, ?, '{}', ?)
            """,
            (snapshot_id, source_id, job_id, now, now, f"{source_id}:CODE:flow-revision"),
        )
        for index, (node_id, label, kind) in enumerate(node_rows, start=1):
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_nodes(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language, name,
                    qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, metadata_json,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'java', ?, ?, ?, NULL, ?, ?, 0.95, 'TRUSTED', '{}', ?, 'STATIC', 'CODE')
                """,
                (
                    node_id,
                    snapshot_id,
                    job_id,
                    source_id,
                    file_id,
                    file_id,
                    f"{source_id}|{node_id}",
                    kind,
                    label,
                    f"example.{label}",
                    label,
                    index,
                    index,
                    now,
                ),
            )
        for index, edge in enumerate(edge_rows, start=1):
            edge_id = edge["id"]
            evidence_id = f"ev-{edge_id}"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                    line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, 'EDGE', ?, ?, ?, '{}', ?, 'STATIC', 'CODE')
                """,
                (evidence_id, snapshot_id, job_id, source_id, file_id, f"hash-{source_id}", f"excerpt-{edge_id}", index, index, now),
            )
            unresolved = None if edge.get("to") else json.dumps({"name": "unresolvedTarget"})
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CALLS', ?, 0.9, ?, ?, '{}', 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (
                    edge_id,
                    snapshot_id,
                    job_id,
                    source_id,
                    file_id,
                    file_id,
                    edge["from"],
                    edge.get("to"),
                    edge.get("resolution") or ("RESOLVED" if edge.get("to") else "UNRESOLVED"),
                    evidence_id,
                    unresolved,
                    now,
                ),
            )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES (?, ?, ?)
            """,
            (source_id, snapshot_id, now),
        )
