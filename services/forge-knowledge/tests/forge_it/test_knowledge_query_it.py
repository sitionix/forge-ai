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
