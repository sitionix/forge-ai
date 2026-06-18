from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timezone
from urllib.parse import quote

import pytest
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config


pytestmark = pytest.mark.forge_it


def test_graph_snapshot_manifest_cursor_pagination_and_compatibility(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=125, edge_count=360)

    with TestClient(app) as client:
        manifest_response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")
        assert manifest_response.status_code == 200
        assert manifest_response.headers["etag"]
        assert manifest_response.headers["x-graph-revision"]
        manifest = manifest_response.json()
        assert manifest["graphRevision"]
        assert manifest["totalNodeCount"] == 125
        assert manifest["totalEdgeCount"] == 360
        assert manifest["nodeTypeCounts"]["CALLABLE"] > 0
        assert manifest["edgeTypeCounts"]["CALLS"] > 0

        not_modified = client.get(
            "/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE",
            headers={"If-None-Match": manifest_response.headers["etag"]},
        )
        assert not_modified.status_code == 304

        nodes = traverse_pages(client, "nodes", manifest["graphRevision"], page_size=37)
        assert len(nodes) == manifest["totalNodeCount"]
        assert len({node["id"] for node in nodes}) == len(nodes)
        assert [node["id"] for node in nodes] == sorted(node["id"] for node in nodes)

        edges = traverse_pages(client, "edges", manifest["graphRevision"], page_size=53)
        assert len(edges) == manifest["totalEdgeCount"]
        assert len({edge["id"] for edge in edges}) == len(edges)
        assert [edge["id"] for edge in edges] == sorted(edge["id"] for edge in edges)
        node_ids = {node["id"] for node in nodes}
        assert all(edge["fromNodeId"] in node_ids and edge["toNodeId"] in node_ids for edge in edges if edge["toNodeId"])

        invalid_cursor = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&cursor=bad"
        )
        assert invalid_cursor.status_code == 400
        assert invalid_cursor.json()["code"] == "GRAPH_CURSOR_INVALID"

        stale = client.get("/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision=stale&pageSize=10")
        assert stale.status_code == 409
        assert stale.json()["code"] == "GRAPH_SNAPSHOT_STALE"

        no_external = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeExternal=hide").json()
        assert no_external["totalNodeCount"] < manifest["totalNodeCount"]

        no_isolated = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeIsolated=false").json()
        assert no_isolated["totalNodeCount"] < manifest["totalNodeCount"]

        full_graph = client.get("/api/v1/knowledge/analysis/graph?sourceId=forge-ai&flowDomain=CODE&limit=80").json()
        assert full_graph["nodes"]
        assert full_graph["meta"]["maxNodeLimit"] == 500

        graph_slice = client.get("/api/v1/knowledge/analysis/graph/slice?sourceId=forge-ai&flowDomain=CODE&maxNodes=20&maxEdges=30").json()
        assert graph_slice["nodes"]
        assert graph_slice["request"]["maxNodes"] == 20


def traverse_pages(client: TestClient, kind: str, graph_revision: str, page_size: int):
    cursor = None
    items = []
    while True:
        path = (
            f"/api/v1/knowledge/analysis/graph/{kind}?sourceId=forge-ai&flowDomain=CODE"
            f"&graphRevision={quote(graph_revision)}&pageSize={page_size}"
        )
        if cursor:
            path += f"&cursor={quote(cursor)}"
        page = client.get(path).json()
        items.extend(page["items"])
        cursor = page.get("nextCursor")
        if page["complete"]:
            assert not cursor
            return items
        assert cursor


def seed_graph_fixture(db_path, node_count: int, edge_count: int) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES ('forge-ai', 'Forge AI', 'platform', '.', 1, '[]', '{}', ?)
            """,
            (now,),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
            VALUES (1, 'forge-ai', '.', '.', 'src/GraphFixture.java', '.java', 'java', 'CODE', 100, 'hash-1', ?, 100, 'utf-8:replace', ?)
            """,
            (now, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version)
            VALUES (1, 'forge-ai', 'src/GraphFixture.java', 'hash-1', 'fixture', '1', 'ANALYZED', ?, ?, ?, '[]', 'GRAPH_V1')
            """,
            (now, node_count, edge_count),
        )
        for index in range(node_count):
            kind = ["FILE", "TYPE", "CALLABLE", "FIELD", "EXTERNAL"][index % 5]
            node_id = f"node-{index:05d}"
            degree_target = None if index == node_count - 1 else "node-00000"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_nodes(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language, name,
                    qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, metadata_json,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, 'job-1', 'forge-ai', 1, 1, ?, ?, 'java', ?, ?, ?, ?, ?, ?, 0.9, 'TRUSTED', ?, ?, 'STATIC', 'CODE')
                """,
                (
                    node_id,
                    f"stable:{node_id}",
                    kind,
                    f"Name{index}",
                    f"com.example.Name{index}",
                    f"Name{index}",
                    degree_target if index % 7 == 0 else None,
                    index + 1,
                    index + 1,
                    json.dumps({"displayScore": 1.0 / (index + 1)}),
                    now,
                ),
            )
        for index in range(edge_count):
            from_node = f"node-{index % (node_count - 1):05d}"
            to_node = f"node-{(index * 7 + 3) % (node_count - 1):05d}"
            relation = ["DECLARES", "CALLS", "REFERENCES", "IMPORTS"][index % 4]
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, 'job-1', 'forge-ai', 1, 1, ?, ?, ?, 'RESOLVED', 0.8, NULL, NULL, ?, 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (f"edge-{index:05d}", from_node, to_node, relation, json.dumps({"flowScore": 0.5}), now),
            )
