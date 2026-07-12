from __future__ import annotations

import asyncio
import sqlite3

import httpx
import pytest

from semantic_test_support import seed_semantic_graph
from support import build_test_app, write_runtime_config


pytestmark = pytest.mark.forge_it


def post(app, path: str, payload: dict):
    async def exercise():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver") as client:
            task = asyncio.create_task(client.post(path, json=payload))
            while not task.done():
                await asyncio.sleep(0.01)
            return await task
    return asyncio.run(exercise())


def request(query: str, *, max_flows: int = 10, include_tests: bool = False):
    return {
        "queryText": query,
        "intent": "FLOW_EXPLANATION",
        "answerLanguage": "uk",
        "includeTests": include_tests,
        "maxFlows": max_flows,
    }


def explicit(node_id: str, evidence_id: str):
    return {
        "id": f"claim-{node_id}", "node_id": node_id, "claimKind": "ENTRYPOINT_HINT",
        "summary": "typed execution root", "evidence_ids": [evidence_id],
    }


def assert_flow_refs_close(payload: dict) -> None:
    for flow in payload.get("flows", []):
        node_refs = {item["nodeRef"] for item in flow.get("nodes", [])}
        transition_refs = {item["transitionRef"] for item in flow.get("transitions", [])}
        boundary_refs = {item["boundaryRef"] for item in flow.get("boundaries", [])}
        evidence_refs = {item["evidenceRef"] for item in flow.get("evidence", [])}
        assert flow["entrypoint"]["nodeRef"] in node_refs
        for item in flow.get("matchedAnchors", []):
            assert item["anchorRef"] in node_refs
        for item in flow.get("transitions", []):
            assert item["fromNodeRef"] in node_refs
            assert item["toNodeRef"] in node_refs
            assert set(item.get("evidenceRefs", [])) <= evidence_refs
        for item in flow.get("boundaries", []):
            assert item["fromNodeRef"] in node_refs
            assert set(item.get("evidenceRefs", [])) <= evidence_refs
        for item in flow.get("evidence", []):
            assert item["ownerRef"] in node_refs | transition_refs | boundary_refs


def assert_no_slice_truncation(payload: dict) -> None:
    assert "ENTRYPOINT_FLOW_SLICE_TRUNCATED" not in str(payload)
    for flow in payload.get("flows", []):
        assert flow["complete"] is True
        assert flow["coverage"]["truncated"] is False


def test_real_stack_one_entrypoint_many_branches_and_failed_explanation_preserves_facts(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    nodes = [
        {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/alpha.txt"},
        {"id": "Beta", "nodeKind": "CALLABLE", "name": "Beta", "path": "src/beta.txt"},
        {"id": "Gamma", "nodeKind": "CALLABLE", "name": "Gamma", "path": "src/gamma.txt"},
        {"id": "Delta", "nodeKind": "CALLABLE", "name": "Delta", "path": "src/delta.txt"},
        {"id": "Epsilon", "nodeKind": "CALLABLE", "name": "Epsilon", "path": "src/epsilon.txt"},
    ]
    edges = [
        {"id": "ab", "fromNodeId": "Alpha", "toNodeId": "Beta", "edgeType": "CALLS"},
        {"id": "ag", "fromNodeId": "Alpha", "toNodeId": "Gamma", "edgeType": "CALLS"},
        {"id": "ad", "fromNodeId": "Alpha", "toNodeId": "Delta", "edgeType": "CALLS"},
        {"id": "ge", "fromNodeId": "Gamma", "toNodeId": "Epsilon", "edgeType": "CALLS"},
        {"id": "outside", "fromNodeId": "Epsilon", "edgeType": "CALLS", "resolutionStatus": "UNRESOLVED", "unresolved": {"name": "Omega"}},
    ]
    seed_semantic_graph(config.store_path, source_id="neutral-a", nodes=nodes, edges=edges, claims=[explicit("Alpha", "ev-node-query")])

    class FailingProvider:
        def complete(self, *_args, **_kwargs):
            raise RuntimeError("expected")

    app.state.flow_explanation_provider = FailingProvider()
    base = post(app, "/api/v1/knowledge/query", request("Gamma")).json()
    explained = post(app, "/api/v1/knowledge/query/flow-explanations", request("Gamma")).json()
    tool = post(app, "/api/v1/knowledge/query/tool-context", request("Gamma")).json()

    assert len(base["flows"]) == 1
    flow = base["flows"][0]
    assert flow["entrypoint"]["label"] == "Alpha"
    assert {node["label"] for node in flow["nodes"]} == {"Alpha", "Beta", "Gamma", "Delta", "Epsilon"}
    assert len(flow["transitions"]) == 4
    assert len(flow["boundaries"]) == 1
    assert explained["flows"] == base["flows"]
    assert explained["flowExplanations"][0]["status"] == "FAILED"
    assert len(tool["flows"]) == 1
    assert tool["flows"][0]["status"] == "FAILED"
    assert len(tool["flows"][0]["steps"]) == 5
    assert len(tool["flows"][0]["transitions"]) == 4
    assert_flow_refs_close(base)
    assert_flow_refs_close(explained)
    assert_no_slice_truncation(base)


def test_real_stack_three_roots_shared_suffix_and_max_flows(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    roots = ["Alpha", "Beta", "Phi"]
    nodes = [{"id": item, "nodeKind": "CALLABLE", "name": item, "path": f"src/{item}.txt"} for item in [*roots, "Gamma", "Delta", "Epsilon"]]
    edges = [
        *[{"id": f"{root}-g", "fromNodeId": root, "toNodeId": "Gamma", "edgeType": "CALLS"} for root in roots],
        {"id": "gd", "fromNodeId": "Gamma", "toNodeId": "Delta", "edgeType": "CALLS"},
        {"id": "de", "fromNodeId": "Delta", "toNodeId": "Epsilon", "edgeType": "CALLS"},
    ]
    claims = [explicit(root, "ev-node-query") for root in roots]
    seed_semantic_graph(config.store_path, source_id="neutral-b", nodes=nodes, edges=edges, claims=claims)

    all_flows = post(app, "/api/v1/knowledge/query", request("Delta")).json()
    limited = post(app, "/api/v1/knowledge/query", request("Delta", max_flows=2)).json()

    assert len(all_flows["flows"]) == 3
    assert {flow["entrypoint"]["label"] for flow in all_flows["flows"]} == set(roots)
    assert all({"Gamma", "Delta", "Epsilon"} <= {node["label"] for node in flow["nodes"]} for flow in all_flows["flows"])
    assert len(limited["flows"]) == 2
    diagnostic = next(item for item in limited["diagnostics"] if item["code"] == "ENTRYPOINT_FLOW_MAX_FLOWS_REACHED")
    assert diagnostic["metadata"]["omittedFlowCount"] == 1


def test_real_stack_include_tests_uses_persisted_classification(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    nodes = [{"id": item, "nodeKind": "CALLABLE", "name": item, "path": f"src/{item}.txt"} for item in ["Alpha", "Beta", "Gamma"]]
    edges = [
        {"id": "ag", "fromNodeId": "Alpha", "toNodeId": "Gamma", "edgeType": "CALLS"},
        {"id": "bg", "fromNodeId": "Beta", "toNodeId": "Gamma", "edgeType": "CALLS"},
    ]
    seed_semantic_graph(config.store_path, source_id="neutral-c", nodes=nodes, edges=edges, claims=[explicit("Alpha", "ev-node-query"), explicit("Beta", "ev-node-query")])
    with sqlite3.connect(config.store_path) as conn:
        conn.execute("UPDATE files SET flow_domain='TEST' WHERE source_id='neutral-c' AND relative_path='src/Beta.txt'")
        conn.execute("UPDATE analysis_files SET flow_domain='TEST' WHERE source_id='neutral-c' AND relative_path='src/Beta.txt'")
        conn.execute("UPDATE analysis_graph_nodes SET flow_domain='TEST' WHERE source_id='neutral-c' AND id='Beta'")
        conn.execute("UPDATE analysis_graph_claims SET flow_domain='TEST' WHERE source_id='neutral-c' AND node_id='Beta'")
        conn.execute("UPDATE analysis_graph_edges SET flow_domain='TEST' WHERE source_id='neutral-c' AND id='bg'")

    excluded = post(app, "/api/v1/knowledge/query", request("Gamma", include_tests=False)).json()
    included = post(app, "/api/v1/knowledge/query", request("Gamma", include_tests=True)).json()
    assert {flow["entrypoint"]["label"] for flow in excluded["flows"]} == {"Alpha"}
    assert {flow["entrypoint"]["label"] for flow in included["flows"]} == {"Alpha", "Beta"}


def test_real_stack_without_explicit_fact_returns_diagnosed_inferred_root(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        config.store_path, source_id="neutral-d",
        nodes=[
            {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/a.txt"},
            {"id": "Beta", "nodeKind": "CALLABLE", "name": "Beta", "path": "src/b.txt"},
        ],
        edges=[{"id": "ab", "fromNodeId": "Alpha", "toNodeId": "Beta", "edgeType": "CALLS"}],
    )
    body = post(app, "/api/v1/knowledge/query", request("Beta")).json()
    assert len(body["flows"]) == 1
    assert body["flows"][0]["entrypointOrigin"] == "INFERRED_ROOT"
    assert any(item["code"] == "ENTRYPOINT_FLOW_INFERRED_ROOT" for item in body["flows"][0]["diagnostics"])


def test_real_stack_ignores_stale_inventory_revision_facts(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        config.store_path, source_id="neutral-e",
        nodes=[
            {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/a.txt"},
            {"id": "Beta", "nodeKind": "CALLABLE", "name": "Beta", "path": "src/b.txt"},
        ],
        edges=[{"id": "ab", "fromNodeId": "Alpha", "toNodeId": "Beta", "edgeType": "CALLS"}],
        claims=[explicit("Alpha", "ev-node-query")],
    )
    with sqlite3.connect(config.store_path) as conn:
        stale_file_id = 999001
        conn.execute(
            """INSERT INTO analysis_files(file_id,source_id,relative_path,content_hash,analyzer_name,analyzer_version,status,diagnostics_json,engine_version,flow_domain)
               VALUES (?, 'neutral-e', 'src/stale.txt', 'stale-hash', 'fixture', '1', 'ANALYZED', '[]', 'GRAPH_V1', 'CODE')""",
            (stale_file_id,),
        )
        now = "2026-01-01T00:00:00+00:00"
        conn.execute(
            """INSERT INTO analysis_graph_nodes(id,job_id,source_id,inventory_file_id,analysis_file_id,file_id,relative_path,content_hash,stable_key,node_kind,language,name,qualified_name,display_name,confidence,status,created_at,updated_at,fact_origin,flow_domain)
               VALUES ('Stale','job-stale','neutral-e',?, ?,?,'src/stale.txt','stale-hash','stale','CALLABLE','fixture','Stale','Stale','Stale',1,'TRUSTED',?,?,'STATIC','CODE')""",
            (stale_file_id, stale_file_id, stale_file_id, now, now),
        )
        conn.execute(
            """INSERT INTO analysis_graph_edges(id,job_id,source_id,inventory_file_id,analysis_file_id,file_id,relative_path,content_hash,from_node_id,to_node_id,edge_type,resolution_status,confidence,metadata_json,status,created_at,updated_at,fact_origin,flow_domain)
               VALUES ('stale-beta','job-stale','neutral-e',?,?,?,'src/stale.txt','stale-hash','Stale','Beta','CALLS','RESOLVED',1,'{}','TRUSTED',?,?,'STATIC','CODE')""",
            (stale_file_id, stale_file_id, stale_file_id, now, now),
        )
    body = post(app, "/api/v1/knowledge/query", request("Beta")).json()
    assert {flow["entrypoint"]["label"] for flow in body["flows"]} == {"Alpha"}
    assert "Stale" not in str(body)


def test_real_stack_deep_flow_is_complete_across_all_endpoints(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    depth = 120
    nodes = [
        {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/Alpha.txt"},
        *[
            {"id": f"Node{i}", "nodeKind": "CALLABLE", "name": f"Node{i}", "path": f"src/Node{i}.txt"}
            for i in range(1, depth + 1)
        ],
    ]
    edges = [{"id": "edge-0", "fromNodeId": "Alpha", "toNodeId": "Node1", "edgeType": "CALLS"}]
    edges.extend(
        {"id": f"edge-{i}", "fromNodeId": f"Node{i}", "toNodeId": f"Node{i + 1}", "edgeType": "CALLS"}
        for i in range(1, depth)
    )
    seed_semantic_graph(config.store_path, source_id="neutral-deep", nodes=nodes, edges=edges, claims=[explicit("Alpha", "ev-node-query")])

    class FailingProvider:
        def complete(self, *_args, **_kwargs):
            raise RuntimeError("expected")

    app.state.flow_explanation_provider = FailingProvider()
    base = post(app, "/api/v1/knowledge/query", request(f"Node{depth}")).json()
    explained = post(app, "/api/v1/knowledge/query/flow-explanations", request(f"Node{depth}")).json()
    tool = post(app, "/api/v1/knowledge/query/tool-context", request(f"Node{depth}")).json()

    assert len(base["flows"]) == 1
    assert base["flows"][0]["coverage"]["nodeCount"] == depth + 1
    assert base["flows"][0]["coverage"]["transitionCount"] == depth
    assert explained["flows"] == base["flows"]
    assert explained["flowExplanations"][0]["status"] == "FAILED"
    assert len(tool["flows"][0]["steps"]) == depth + 1
    assert len(tool["flows"][0]["transitions"]) == depth
    assert_flow_refs_close(base)
    assert_flow_refs_close(explained)
    assert_no_slice_truncation(base)


def test_real_stack_large_fanout_and_boundaries_are_one_complete_flow(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    width = 120
    nodes = [
        {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/Alpha.txt"},
        *[
            {"id": f"Child{i}", "nodeKind": "CALLABLE", "name": f"Child{i}", "path": f"src/Child{i}.txt"}
            for i in range(width)
        ],
    ]
    edges = [
        {"id": f"edge-{i}", "fromNodeId": "Alpha", "toNodeId": f"Child{i}", "edgeType": "CALLS"}
        for i in range(width)
    ]
    edges.extend(
        {"id": f"boundary-{i}", "fromNodeId": "Alpha", "edgeType": "CALLS", "resolutionStatus": "UNRESOLVED", "unresolved": {"name": f"Boundary{i}"}}
        for i in range(15)
    )
    seed_semantic_graph(config.store_path, source_id="neutral-wide", nodes=nodes, edges=edges, claims=[explicit("Alpha", "ev-node-query")])

    body = post(app, "/api/v1/knowledge/query", request("Alpha")).json()

    assert len(body["flows"]) == 1
    assert body["flows"][0]["coverage"]["nodeCount"] == width + 1
    assert body["flows"][0]["coverage"]["transitionCount"] == width
    assert body["flows"][0]["coverage"]["boundaryCount"] == 15
    assert_flow_refs_close(body)
    assert_no_slice_truncation(body)


def test_real_stack_many_entrypoints_discovers_all_before_max_flows(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    roots = [f"Root{i:02d}" for i in range(40)]
    nodes = [
        *[
            {"id": root, "nodeKind": "CALLABLE", "name": root, "path": f"src/{root}.txt"}
            for root in roots
        ],
        {"id": "Shared", "nodeKind": "CALLABLE", "name": "Shared", "path": "src/Shared.txt"},
        {"id": "Anchor", "nodeKind": "CALLABLE", "name": "Anchor", "path": "src/Anchor.txt"},
    ]
    edges = [{"id": f"{root}-shared", "fromNodeId": root, "toNodeId": "Shared", "edgeType": "CALLS"} for root in roots]
    edges.append({"id": "shared-anchor", "fromNodeId": "Shared", "toNodeId": "Anchor", "edgeType": "CALLS"})
    seed_semantic_graph(config.store_path, source_id="neutral-many", nodes=nodes, edges=edges, claims=[explicit(root, "ev-node-query") for root in roots])

    body = post(app, "/api/v1/knowledge/query", request("Anchor", max_flows=10)).json()

    diagnostic = next(item for item in body["diagnostics"] if item["code"] == "ENTRYPOINT_FLOW_MAX_FLOWS_REACHED")
    assert diagnostic["metadata"]["discoveredEntrypointCount"] == 40
    assert diagnostic["metadata"]["returnedFlowCount"] == 10
    assert diagnostic["metadata"]["omittedFlowCount"] == 30
    assert len(body["flows"]) == 10
    assert all({"Shared", "Anchor"} <= {node["label"] for node in flow["nodes"]} for flow in body["flows"])
    assert_flow_refs_close(body)
    assert_no_slice_truncation(body)


def test_real_stack_public_contract_has_no_internal_id_leakage(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-secret",
        nodes=[
            {"id": "secret-node-db-id", "nodeKind": "CALLABLE", "name": "Public Alpha", "qualified": "PublicAlpha", "path": "src/PublicAlpha.txt"},
            {"id": "secret-target-node-db-id", "nodeKind": "CALLABLE", "name": "Public Anchor", "qualified": "PublicAnchor", "path": "src/PublicAnchor.txt"},
        ],
        edges=[
            {
                "id": "secret-edge-db-id",
                "fromNodeId": "secret-node-db-id",
                "toNodeId": "secret-target-node-db-id",
                "edgeType": "CALLS",
                "evidence_id": "secret-evidence-db-id",
            }
        ],
        claims=[{"id": "secret-claim-db-id", "node_id": "secret-node-db-id", "claimKind": "ENTRYPOINT_HINT", "summary": "typed root", "evidence_ids": ["ev-node-query"]}],
    )
    with sqlite3.connect(config.store_path) as conn:
        conn.execute(
            "UPDATE analysis_graph_evidence SET excerpt='public call evidence', excerpt_hash='public-call-evidence' WHERE id='secret-evidence-db-id'"
        )

    body = post(app, "/api/v1/knowledge/query", request("Public Anchor")).json()

    rendered = str(body)
    for secret in ("secret-node-db-id", "secret-target-node-db-id", "secret-edge-db-id", "secret-evidence-db-id", "secret-vector-db-id"):
        assert secret not in rendered
    assert body["flows"][0]["entrypoint"]["label"] == "Public Alpha"
    assert_flow_refs_close(body)
