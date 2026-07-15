from __future__ import annotations

import asyncio
import json
import sqlite3
from datetime import datetime, timezone

import httpx
import pytest

from knowledge_service.flow_explanations import FlowExplanationProviderResult
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import build_knowledge_query_service
from knowledge_service.semantic_index import SemanticIndexStore
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


def request(query: str, *, max_flows: int = 10, include_tests: bool = False, answer_language: str | None = None):
    payload = {
        "queryText": query,
        "intent": "FLOW_EXPLANATION",
        "includeTests": include_tests,
        "maxFlows": max_flows,
    }
    if answer_language is not None:
        payload["answerLanguage"] = answer_language
    return payload


def graph_query(app, payload: dict) -> dict:
    service = build_knowledge_query_service(app.state.knowledge_dependencies.graph_store, app.state.app_config)
    return service.query(KnowledgeQueryRequest(**payload)).dict()


def query_all_surfaces(app, query: str):
    payload = request(query)
    return (
        graph_query(app, payload),
        post(app, "/api/v1/knowledge/query", payload).json(),
        post(app, "/api/v1/knowledge/query/tool-context", payload).json(),
    )


def explicit(node_id: str, evidence_id: str):
    return {
        "id": f"claim-{node_id}", "node_id": node_id, "claimKind": "ENTRYPOINT_HINT",
        "summary": "typed execution root", "evidence_ids": [evidence_id], "entrypointKind": "HTTP",
    }


def assert_flow_refs_close(payload: dict) -> None:
    for flow in payload.get("flows", []):
        node_refs = {item["nodeRef"] for item in flow.get("nodes", [])}
        transition_refs = {item["transitionRef"] for item in flow.get("transitions", [])}
        boundary_refs = {item["boundaryRef"] for item in flow.get("boundaries", [])}
        evidence_refs = {item["evidenceRef"] for item in flow.get("evidence", [])}
        evidence_owner_by_ref = {item["evidenceRef"]: item["ownerRef"] for item in flow.get("evidence", [])}
        assert flow["entrypoint"]["nodeRef"] in node_refs
        for item in flow.get("matchedAnchors", []):
            assert item["anchorRef"] in node_refs
        for item in flow.get("transitions", []):
            assert item["fromNodeRef"] in node_refs
            assert item["toNodeRef"] in node_refs
            assert set(item.get("evidenceRefs", [])) <= evidence_refs
            for ref in item.get("evidenceRefs", []):
                assert evidence_owner_by_ref[ref] == item["transitionRef"]
        for item in flow.get("boundaries", []):
            assert item["fromNodeRef"] in node_refs
            assert "toNodeRef" not in item
            assert set(item.get("evidenceRefs", [])) <= evidence_refs
            for ref in item.get("evidenceRefs", []):
                assert evidence_owner_by_ref[ref] == item["boundaryRef"]
        for item in flow.get("evidence", []):
            assert item["ownerRef"] in node_refs | transition_refs | boundary_refs


def _flows_by_index(payload: dict) -> dict[int, dict]:
    return {int(flow["flowIndex"]): flow for flow in payload.get("flows", [])}


def assert_explanation_refs_close(payload: dict) -> None:
    flows_by_index = _flows_by_index(payload)
    for explanation in payload.get("flowExplanations", []):
        flow = flows_by_index[int(explanation["flowIndex"])]
        node_refs = {item["nodeRef"] for item in flow.get("nodes", [])}
        transition_refs = {item["transitionRef"] for item in flow.get("transitions", [])}
        boundary_refs = {item["boundaryRef"] for item in flow.get("boundaries", [])}
        evidence_refs = {item["evidenceRef"] for item in flow.get("evidence", [])}
        for item in explanation.get("narrative", []):
            assert set(item.get("nodeRefs", [])) <= node_refs
            assert set(item.get("transitionRefs", [])) <= transition_refs
            assert set(item.get("boundaryRefs", [])) <= boundary_refs
        for item in explanation.get("steps", []):
            assert item["nodeRef"] in node_refs
            assert set(item.get("transitionRefs", [])) <= transition_refs
            assert set(item.get("evidenceRefs", [])) <= evidence_refs
        for item in explanation.get("transitionExplanations", []):
            assert item["transitionRef"] in transition_refs
            assert set(item.get("evidenceRefs", [])) <= evidence_refs
        for item in explanation.get("boundaries", []):
            assert item["boundaryRef"] in boundary_refs
            assert item["fromNodeRef"] in node_refs
            assert set(item.get("evidenceRefs", [])) <= evidence_refs


def assert_tool_refs_close(tool_payload: dict, base_payload: dict) -> None:
    base_by_index = _flows_by_index(base_payload)
    for tool_flow in tool_payload.get("flows", []):
        flow = base_by_index[int(tool_flow["flowIndex"])]
        node_refs = {item["nodeRef"] for item in flow.get("nodes", [])}
        transition_refs = {item["transitionRef"] for item in flow.get("transitions", [])}
        boundary_refs = {item["boundaryRef"] for item in flow.get("boundaries", [])}
        evidence_refs = {item["evidenceRef"] for item in flow.get("evidence", [])}
        assert {item["nodeRef"] for item in tool_flow.get("steps", [])} == node_refs
        assert {item["transitionRef"] for item in tool_flow.get("transitions", [])} == transition_refs
        assert {item["boundaryRef"] for item in tool_flow.get("boundaries", [])} == boundary_refs
        for item in tool_flow.get("narrative", []):
            assert set(item.get("nodeRefs", [])) <= node_refs
            assert set(item.get("transitionRefs", [])) <= transition_refs
            assert set(item.get("boundaryRefs", [])) <= boundary_refs
        for item in tool_flow.get("steps", []):
            assert item["nodeRef"] in node_refs
            step_evidence_refs = {evidence["ref"] for evidence in item.get("evidence", [])}
            assert step_evidence_refs <= evidence_refs
            for ref in step_evidence_refs:
                assert flow_evidence_owner(flow, ref) == item["nodeRef"]
        for item in tool_flow.get("transitions", []):
            assert item["fromNodeRef"] in node_refs
            assert item["toNodeRef"] in node_refs
            transition_evidence_refs = {evidence["ref"] for evidence in item.get("evidence", [])}
            assert transition_evidence_refs <= evidence_refs
            for ref in transition_evidence_refs:
                assert flow_evidence_owner(flow, ref) == item["transitionRef"]
        for item in tool_flow.get("boundaries", []):
            assert item["fromNodeRef"] in node_refs
            boundary_evidence_refs = {evidence["ref"] for evidence in item.get("evidence", [])}
            assert boundary_evidence_refs <= evidence_refs
            for ref in boundary_evidence_refs:
                assert flow_evidence_owner(flow, ref) == item["boundaryRef"]


def flow_evidence_owner(flow: dict, evidence_ref: str) -> str:
    return next(item["ownerRef"] for item in flow.get("evidence", []) if item["evidenceRef"] == evidence_ref)


def assert_boundary_facts_match(base_payload: dict, explanation_payload: dict | None = None, tool_payload: dict | None = None) -> None:
    base_by_index = _flows_by_index(base_payload)
    if explanation_payload is not None:
        for explanation in explanation_payload.get("flowExplanations", []):
            flow = base_by_index[int(explanation["flowIndex"])]
            base_boundaries = {item["boundaryRef"]: item for item in flow.get("boundaries", [])}
            explanation_boundaries = {item["boundaryRef"]: item for item in explanation.get("boundaries", [])}
            assert set(explanation_boundaries) == set(base_boundaries)
            for boundary_ref, base_boundary in base_boundaries.items():
                explanation_boundary = explanation_boundaries[boundary_ref]
                assert explanation_boundary["fromNodeRef"] == base_boundary["fromNodeRef"]
                assert explanation_boundary["kind"] == base_boundary["kind"]
                assert explanation_boundary.get("target") == base_boundary.get("target")
                assert explanation_boundary["resolutionStatus"] == base_boundary["resolutionStatus"]
    if tool_payload is not None:
        for tool_flow in tool_payload.get("flows", []):
            flow = base_by_index[int(tool_flow["flowIndex"])]
            base_boundaries = {item["boundaryRef"]: item for item in flow.get("boundaries", [])}
            tool_boundaries = {item["boundaryRef"]: item for item in tool_flow.get("boundaries", [])}
            assert set(tool_boundaries) == set(base_boundaries)
            for boundary_ref, base_boundary in base_boundaries.items():
                tool_boundary = tool_boundaries[boundary_ref]
                assert tool_boundary["fromNodeRef"] == base_boundary["fromNodeRef"]
                assert tool_boundary["kind"] == base_boundary["kind"]
                assert tool_boundary.get("target") == base_boundary.get("target")
                assert tool_boundary["resolutionStatus"] == base_boundary["resolutionStatus"]


def assert_no_internal_ids(payload: dict, secrets: tuple[str, ...]) -> None:
    rendered = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    for secret in secrets:
        assert secret not in rendered


def assert_no_slice_truncation(payload: dict) -> None:
    assert "ENTRYPOINT_FLOW_SLICE_TRUNCATED" not in str(payload)
    for flow in payload.get("flows", []):
        assert flow["complete"] is True
        assert flow["coverage"]["truncated"] is False


def compact_tree_items(tool_payload: dict) -> list[dict]:
    items: list[dict] = []
    stack = [tree["entrypoint"] for tree in tool_payload.get("trees", [])]
    while stack:
        item = stack.pop()
        items.append(item)
        stack.extend(reversed(item.get("children", [])))
    return items


def compact_tree_symbols(tool_payload: dict) -> set[str]:
    return {item.get("symbol") for item in compact_tree_items(tool_payload)}


def assert_compact_human_contract(payload: dict) -> None:
    assert set(payload) == {"answerLanguage", "answers", "diagnostics"}
    assert payload["answers"]
    assert all(answer["text"] for answer in payload["answers"])
    rendered = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("status", "flows", "flowExplanations", "nodeRef", "transitionRef", "boundaryRef", "evidenceRef"):
        assert forbidden not in rendered


def assert_compact_tool_contract(payload: dict) -> None:
    assert set(payload) == {"queryText", "trees", "diagnostics"}
    rendered = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("status", "nodeRef", "transitionRef", "boundaryRef", "evidenceRef", "flowIndex"):
        assert forbidden not in rendered


def append_stale_resolved_call_boundary(
    store_path,
    *,
    source_id: str,
    from_node_id: str,
    to_node_id: str,
    edge_id: str,
    evidence_id: str,
) -> None:
    with sqlite3.connect(store_path) as conn:
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = OFF")
        node = conn.execute(
            """
            SELECT job_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash
            FROM analysis_graph_nodes
            WHERE source_id = ? AND id = ?
            """,
            (source_id, from_node_id),
        ).fetchone()
        assert node is not None
        now = datetime.now(timezone.utc).isoformat()
        conn.execute(
            """
            INSERT INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                from_node_id, to_node_id, edge_type, resolution_status, confidence,
                unresolved_target_json, metadata_json, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CALLS', 'RESOLVED', 0.91,
                    NULL, '{}', 'TRUSTED', ?, ?, 'STATIC', 'CODE')
            """,
            (
                edge_id,
                node["job_id"],
                source_id,
                node["inventory_file_id"],
                node["analysis_file_id"],
                node["file_id"],
                node["relative_path"],
                node["content_hash"],
                from_node_id,
                to_node_id,
                now,
                now,
            ),
        )
        conn.execute(
            "INSERT INTO analysis_graph_edge_evidence(edge_id, evidence_id) VALUES (?, ?)",
            (edge_id, evidence_id),
        )
        graph_id = SemanticIndexStore.compute_graph_revision_conn(conn, source_id)
        counts = conn.execute(
            """
            SELECT
              (SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?) AS node_count,
              (SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = ?) AS edge_count,
              (SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = ?) AS claim_count,
              (SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = ?) AS evidence_count
            """,
            (source_id, source_id, source_id, source_id),
        ).fetchone()
        conn.execute(
            """
            UPDATE analysis_graph_state
            SET graph_id = ?, content_identity = ?, node_count = ?, edge_count = ?, claim_count = ?, evidence_count = ?, updated_at = ?
            WHERE source_id = ?
            """,
            (
                graph_id,
                graph_id,
                int(counts["node_count"]),
                int(counts["edge_count"]),
                int(counts["claim_count"]),
                int(counts["evidence_count"]),
                now,
                source_id,
            ),
        )


def replace_evidence_excerpts(store_path, source_id: str, excerpts_by_id: dict[str, str]) -> None:
    with sqlite3.connect(store_path) as conn:
        for evidence_id, excerpt in excerpts_by_id.items():
            conn.execute(
                """
                UPDATE analysis_graph_evidence
                SET excerpt = ?, excerpt_hash = ?
                WHERE source_id = ? AND id = ?
                """,
                (excerpt, excerpt, source_id, evidence_id),
            )


class FailingProvider:
    def complete(self, *_args, **_kwargs):
        raise RuntimeError("expected")


class GroundedProvider:
    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        sources = str(llm_input.get("entrypoint") or "")
        target = sources or "the selected flow"
        response = {"text": f"1. {target} starts the grounded flow.\n2. The grounded answer for {target} is returned."}
        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=128)


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
    base = graph_query(app, request("Gamma"))
    explained = post(app, "/api/v1/knowledge/query", request("Gamma")).json()
    tool = post(app, "/api/v1/knowledge/query/tool-context", request("Gamma")).json()

    assert len(base["flows"]) == 1
    flow = base["flows"][0]
    assert flow["entrypoint"]["label"] == "Alpha"
    assert {node["label"] for node in flow["nodes"]} == {"Alpha", "Beta", "Gamma", "Delta", "Epsilon"}
    assert len(flow["transitions"]) == 4
    assert len(flow["boundaries"]) == 1
    assert explained == {
        "code": "HUMAN_ANSWER_GENERATION_FAILED",
        "message": "The local model could not produce any grounded flow answers.",
    }
    assert_compact_tool_contract(tool)
    assert len(tool["trees"]) == 1
    assert compact_tree_symbols(tool) >= {"Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Omega"}
    assert_flow_refs_close(base)
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

    all_flows = graph_query(app, request("Delta"))
    limited = graph_query(app, request("Delta", max_flows=2))

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

    excluded = graph_query(app, request("Gamma", include_tests=False))
    included = graph_query(app, request("Gamma", include_tests=True))
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
    body = graph_query(app, request("Beta"))
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
            """INSERT INTO analysis_files(file_id,source_id,relative_path,content_hash,analyzer_name,analyzer_version,status,diagnostics_json,flow_domain)
               VALUES (?, 'neutral-e', 'src/stale.txt', 'stale-hash', 'fixture', '1', 'ANALYZED', '[]', 'CODE')""",
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
    body = graph_query(app, request("Beta"))
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
    base = graph_query(app, request(f"Node{depth}"))
    explained = post(app, "/api/v1/knowledge/query", request(f"Node{depth}")).json()
    tool = post(app, "/api/v1/knowledge/query/tool-context", request(f"Node{depth}")).json()

    assert len(base["flows"]) == 1
    assert base["flows"][0]["coverage"]["nodeCount"] == depth + 1
    assert base["flows"][0]["coverage"]["transitionCount"] == depth
    assert explained["code"] == "HUMAN_ANSWER_GENERATION_FAILED"
    assert_compact_tool_contract(tool)
    assert len(compact_tree_items(tool)) == depth + 1
    assert {f"Node{depth}", "Alpha"} <= compact_tree_symbols(tool)
    assert_flow_refs_close(base)
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

    body = graph_query(app, request("Alpha"))

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

    body = graph_query(app, request("Anchor", max_flows=10))

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

    body = graph_query(app, request("Public Anchor"))

    rendered = str(body)
    for secret in ("secret-node-db-id", "secret-target-node-db-id", "secret-edge-db-id", "secret-evidence-db-id", "secret-vector-db-id"):
        assert secret not in rendered
    assert body["flows"][0]["entrypoint"]["label"] == "Public Alpha"
    assert_flow_refs_close(body)


def test_real_stack_missing_current_target_does_not_leak_internal_target_id(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    secrets = (
        "secret-entrypoint-db-id",
        "secret-edge-db-id",
        "secret-evidence-db-id",
        "secret-claim-db-id",
        "secret-missing-target-db-id",
        "secret-semantic-document-id",
        "secret-vector-db-id",
    )
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-missing",
        nodes=[
            {
                "id": "secret-entrypoint-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Entry",
                "qualified": "PublicEntry",
                "path": "src/PublicEntry.txt",
            }
        ],
        edges=[],
        claims=[
            {
                "id": "secret-claim-db-id",
                "node_id": "secret-entrypoint-db-id",
                "claimKind": "ENTRYPOINT_HINT",
                "summary": "typed root",
                "evidence_ids": ["secret-evidence-db-id"],
            }
        ],
        evidence_ids=["secret-evidence-db-id"],
    )
    with sqlite3.connect(config.store_path) as conn:
        conn.execute(
            "UPDATE analysis_graph_evidence SET excerpt='public evidence excerpt', excerpt_hash='public-evidence-excerpt' WHERE source_id='neutral-missing'"
        )
    append_stale_resolved_call_boundary(
        config.store_path,
        source_id="neutral-missing",
        from_node_id="secret-entrypoint-db-id",
        to_node_id="secret-missing-target-db-id",
        edge_id="secret-edge-db-id",
        evidence_id="secret-evidence-db-id",
    )
    app.state.flow_explanation_provider = FailingProvider()

    base = graph_query(app, request("Public Entry"))
    explained = post(app, "/api/v1/knowledge/query", request("Public Entry")).json()
    tool = post(app, "/api/v1/knowledge/query/tool-context", request("Public Entry")).json()

    assert base["status"] == "OK"
    flow = base["flows"][0]
    assert flow["coverage"]["transitionCount"] == 0
    assert flow["coverage"]["boundaryCount"] == 1
    boundary = flow["boundaries"][0]
    assert boundary["kind"] == "CURRENT_TARGET_NODE_MISSING"
    assert boundary["target"] is None
    assert "toNodeRef" not in boundary
    assert explained["code"] == "HUMAN_ANSWER_GENERATION_FAILED"
    assert_compact_tool_contract(tool)
    assert "Target missing from current graph" in compact_tree_symbols(tool)
    assert_flow_refs_close(base)
    assert_no_internal_ids(base, secrets)
    assert_no_internal_ids(explained, secrets)
    assert_no_internal_ids(tool, secrets)


def test_real_stack_external_boundary_kind_target_and_evidence_are_canonical_across_surfaces(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    descriptor = "PublicExternalGateway.call"
    secrets = (
        "secret-external-entry-db-id",
        "secret-external-edge-db-id",
        "secret-external-evidence-db-id",
        "secret-external-claim-db-id",
    )
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-external-boundary",
        nodes=[
            {
                "id": "secret-external-entry-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public External Entry",
                "qualified": "PublicExternalEntry",
                "path": "src/PublicExternalEntry.txt",
            }
        ],
        edges=[
            {
                "id": "secret-external-edge-db-id",
                "fromNodeId": "secret-external-entry-db-id",
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "unresolved": {"qualifiedName": descriptor},
                "evidence_id": "secret-external-evidence-db-id",
            }
        ],
        claims=[
            {
                "id": "secret-external-claim-db-id",
                "node_id": "secret-external-entry-db-id",
                "claimKind": "ENTRYPOINT_HINT",
                "summary": "typed root",
                "evidence_ids": ["ev-node-query"],
            }
        ],
    )
    replace_evidence_excerpts(
        config.store_path,
        "neutral-external-boundary",
        {
            "secret-external-evidence-db-id": "public external boundary evidence",
            "ev-node-query": "public root evidence",
        },
    )
    app.state.flow_explanation_provider = GroundedProvider()

    base, explained, tool = query_all_surfaces(app, "Public External Entry")

    boundary = base["flows"][0]["boundaries"][0]
    assert boundary["kind"] == "EXTERNAL"
    assert boundary["target"] == descriptor
    assert boundary["resolutionStatus"] == "EXTERNAL_TARGET"
    assert_flow_refs_close(base)
    assert_compact_human_contract(explained)
    assert "PublicExternalEntry" in explained["answers"][0]["text"]
    assert_compact_tool_contract(tool)
    external_item = next(item for item in compact_tree_items(tool) if item["symbol"] == descriptor)
    assert external_item["kind"] == "EXTERNAL_CALL"
    assert external_item["evidence"][0]["excerpt"] == "public external boundary evidence"
    assert_no_internal_ids(base, secrets)
    assert_no_internal_ids(explained, secrets)
    assert_no_internal_ids(tool, secrets)


def test_real_stack_dynamic_unresolved_boundary_kind_target_are_canonical_across_surfaces(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    descriptor = "RuntimeSelectedHandler.handle"
    secrets = (
        "secret-unresolved-entry-db-id",
        "secret-unresolved-edge-db-id",
        "secret-unresolved-evidence-db-id",
        "secret-unresolved-claim-db-id",
    )
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-unresolved-boundary",
        nodes=[
            {
                "id": "secret-unresolved-entry-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Dynamic Entry",
                "qualified": "PublicDynamicEntry",
                "path": "src/PublicDynamicEntry.txt",
            }
        ],
        edges=[
            {
                "id": "secret-unresolved-edge-db-id",
                "fromNodeId": "secret-unresolved-entry-db-id",
                "edgeType": "CALLS",
                "resolutionStatus": "DYNAMIC_TARGET",
                "unresolved": {"name": descriptor},
                "evidence_id": "secret-unresolved-evidence-db-id",
            }
        ],
        claims=[
            {
                "id": "secret-unresolved-claim-db-id",
                "node_id": "secret-unresolved-entry-db-id",
                "claimKind": "ENTRYPOINT_HINT",
                "summary": "typed root",
                "evidence_ids": ["ev-node-query"],
            }
        ],
    )
    replace_evidence_excerpts(
        config.store_path,
        "neutral-unresolved-boundary",
        {
            "secret-unresolved-evidence-db-id": "public unresolved boundary evidence",
            "ev-node-query": "public root evidence",
        },
    )
    app.state.flow_explanation_provider = GroundedProvider()

    base, explained, tool = query_all_surfaces(app, "Public Dynamic Entry")

    boundary = base["flows"][0]["boundaries"][0]
    assert boundary["kind"] == "UNRESOLVED"
    assert boundary["target"] == descriptor
    assert boundary["resolutionStatus"] == "DYNAMIC_TARGET"
    assert_flow_refs_close(base)
    assert_compact_human_contract(explained)
    assert "PublicDynamicEntry" in explained["answers"][0]["text"]
    assert_compact_tool_contract(tool)
    unresolved_item = next(item for item in compact_tree_items(tool) if item["symbol"] == descriptor)
    assert unresolved_item["kind"] == "UNRESOLVED_CALL"
    assert unresolved_item["evidence"][0]["excerpt"] == "public unresolved boundary evidence"
    assert_no_internal_ids(base, secrets)
    assert_no_internal_ids(explained, secrets)
    assert_no_internal_ids(tool, secrets)


def test_real_stack_mixed_boundary_flow_preserves_distinct_canonical_boundaries(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    external_descriptor = "PublicExternalBoundary.call"
    unresolved_descriptor = "PublicUnresolvedBoundary.call"
    secrets = (
        "secret-mixed-entry-db-id",
        "secret-mixed-worker-db-id",
        "secret-mixed-internal-edge-db-id",
        "secret-mixed-external-edge-db-id",
        "secret-mixed-unresolved-edge-db-id",
        "secret-mixed-missing-edge-db-id",
        "secret-mixed-missing-target-db-id",
        "secret-mixed-ev-internal",
        "secret-mixed-ev-external",
        "secret-mixed-ev-unresolved",
        "secret-mixed-ev-missing",
    )
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-mixed-boundaries",
        nodes=[
            {
                "id": "secret-mixed-entry-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Mixed Entry",
                "qualified": "PublicMixedEntry",
                "path": "src/PublicMixedEntry.txt",
            },
            {
                "id": "secret-mixed-worker-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Worker",
                "qualified": "PublicWorker",
                "path": "src/PublicWorker.txt",
            },
        ],
        edges=[
            {
                "id": "secret-mixed-internal-edge-db-id",
                "fromNodeId": "secret-mixed-entry-db-id",
                "toNodeId": "secret-mixed-worker-db-id",
                "edgeType": "CALLS",
                "evidence_id": "secret-mixed-ev-internal",
            },
            {
                "id": "secret-mixed-external-edge-db-id",
                "fromNodeId": "secret-mixed-entry-db-id",
                "edgeType": "CALLS",
                "resolutionStatus": "EXTERNAL_TARGET",
                "unresolved": {"displayName": external_descriptor},
                "evidence_id": "secret-mixed-ev-external",
            },
            {
                "id": "secret-mixed-unresolved-edge-db-id",
                "fromNodeId": "secret-mixed-entry-db-id",
                "edgeType": "CALLS",
                "resolutionStatus": "UNRESOLVED",
                "unresolved": {"target": unresolved_descriptor},
                "evidence_id": "secret-mixed-ev-unresolved",
            },
        ],
        claims=[explicit("secret-mixed-entry-db-id", "ev-node-query")],
        evidence_ids=["ev-node-query", "secret-mixed-ev-missing"],
    )
    replace_evidence_excerpts(
        config.store_path,
        "neutral-mixed-boundaries",
        {
            "secret-mixed-ev-internal": "public internal transition evidence",
            "secret-mixed-ev-external": "public external boundary evidence",
            "secret-mixed-ev-unresolved": "public unresolved boundary evidence",
            "secret-mixed-ev-missing": "public missing boundary evidence",
            "ev-node-query": "public root evidence",
        },
    )
    append_stale_resolved_call_boundary(
        config.store_path,
        source_id="neutral-mixed-boundaries",
        from_node_id="secret-mixed-entry-db-id",
        to_node_id="secret-mixed-missing-target-db-id",
        edge_id="secret-mixed-missing-edge-db-id",
        evidence_id="secret-mixed-ev-missing",
    )
    app.state.flow_explanation_provider = GroundedProvider()

    base, explained, tool = query_all_surfaces(app, "Public Mixed Entry")

    assert len(base["flows"]) == 1
    flow = base["flows"][0]
    assert flow["coverage"]["transitionCount"] == 1
    assert flow["coverage"]["boundaryCount"] == 3
    assert len(flow["transitions"]) == 1
    assert len(flow["boundaries"]) == 3
    assert {(item["kind"], item.get("target")) for item in flow["boundaries"]} == {
        ("CURRENT_TARGET_NODE_MISSING", None),
        ("EXTERNAL", external_descriptor),
        ("UNRESOLVED", unresolved_descriptor),
    }
    assert not any(item.get("toNodeRef") for item in flow["boundaries"])
    assert_flow_refs_close(base)
    assert_compact_human_contract(explained)
    assert_compact_tool_contract(tool)
    items = compact_tree_items(tool)
    assert next(item for item in items if item["symbol"] == external_descriptor)["kind"] == "EXTERNAL_CALL"
    assert next(item for item in items if item["symbol"] == unresolved_descriptor)["kind"] == "UNRESOLVED_CALL"
    assert next(item for item in items if item["symbol"] == "Target missing from current graph")["kind"] == "UNRESOLVED_CALL"
    assert_no_internal_ids(base, secrets)
    assert_no_internal_ids(explained, secrets)
    assert_no_internal_ids(tool, secrets)


def test_real_stack_shared_evidence_row_has_owner_specific_public_refs(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    secrets = (
        "secret-entrypoint-db-id",
        "secret-edge-a-db-id",
        "secret-edge-b-db-id",
        "secret-shared-evidence-db-id",
    )
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-shared-evidence",
        nodes=[
            {
                "id": "secret-entrypoint-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Entry",
                "qualified": "PublicEntry",
                "path": "src/Example.java",
            },
            {
                "id": "secret-worker-a-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Worker A",
                "qualified": "PublicWorkerA",
                "path": "src/Example.java",
            },
            {
                "id": "secret-worker-b-db-id",
                "nodeKind": "CALLABLE",
                "name": "Public Worker B",
                "qualified": "PublicWorkerB",
                "path": "src/Example.java",
            },
        ],
        edges=[
            {
                "id": "secret-edge-a-db-id",
                "fromNodeId": "secret-entrypoint-db-id",
                "toNodeId": "secret-worker-a-db-id",
                "edgeType": "CALLS",
                "evidence_id": "secret-shared-evidence-db-id",
            },
            {
                "id": "secret-edge-b-db-id",
                "fromNodeId": "secret-entrypoint-db-id",
                "toNodeId": "secret-worker-b-db-id",
                "edgeType": "CALLS",
                "evidence_id": "secret-shared-evidence-db-id",
            },
        ],
        claims=[
            {
                "id": "secret-entrypoint-claim-db-id",
                "node_id": "secret-entrypoint-db-id",
                "claimKind": "ENTRYPOINT_HINT",
                "summary": "public entrypoint root",
                "evidence_ids": [],
            }
        ],
        evidence_ids=["secret-shared-evidence-db-id"],
    )
    replace_evidence_excerpts(
        config.store_path,
        "neutral-shared-evidence",
        {"secret-shared-evidence-db-id": "shared call evidence"},
    )
    with sqlite3.connect(config.store_path) as conn:
        assert conn.execute(
            "SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = 'neutral-shared-evidence'"
        ).fetchone()[0] == 1
        assert conn.execute(
            "SELECT COUNT(*) FROM analysis_graph_edge_evidence WHERE evidence_id = 'secret-shared-evidence-db-id'"
        ).fetchone()[0] == 2
    app.state.flow_explanation_provider = GroundedProvider()

    base, explained, tool = query_all_surfaces(app, "Public Entry")

    assert len(base["flows"]) == 1
    flow = base["flows"][0]
    assert flow["coverage"]["transitionCount"] == 2
    assert len(flow["transitions"]) == 2
    transition_a, transition_b = flow["transitions"]
    assert transition_a["transitionRef"] != transition_b["transitionRef"]
    assert transition_a["evidenceRefs"]
    assert transition_b["evidenceRefs"]
    transition_a_evidence_ref = transition_a["evidenceRefs"][0]
    transition_b_evidence_ref = transition_b["evidenceRefs"][0]
    assert transition_a_evidence_ref != transition_b_evidence_ref

    evidence_by_ref = {item["evidenceRef"]: item for item in flow["evidence"]}
    assert set(evidence_by_ref) == {transition_a_evidence_ref, transition_b_evidence_ref}
    evidence_a = evidence_by_ref[transition_a_evidence_ref]
    evidence_b = evidence_by_ref[transition_b_evidence_ref]
    assert evidence_a["ownerRef"] == transition_a["transitionRef"]
    assert evidence_b["ownerRef"] == transition_b["transitionRef"]
    assert evidence_a["relativePath"] == evidence_b["relativePath"] == "src/Example.java"
    assert evidence_a["lineStart"] == evidence_b["lineStart"]
    assert evidence_a["lineEnd"] == evidence_b["lineEnd"]
    assert evidence_a["excerpt"] == evidence_b["excerpt"] == "shared call evidence"

    assert_compact_human_contract(explained)
    assert "PublicEntry" in explained["answers"][0]["text"]
    assert_compact_tool_contract(tool)
    worker_items = [
        item
        for item in compact_tree_items(tool)
        if item["symbol"] in {"PublicWorkerA", "PublicWorkerB"}
    ]
    assert len(worker_items) == 2
    assert [item["evidence"][0]["excerpt"] for item in worker_items] == ["shared call evidence", "shared call evidence"]
    assert all(set(item["evidence"][0]) == {"path", "lineStart", "lineEnd", "excerpt"} for item in worker_items)

    assert_flow_refs_close(base)
    assert_no_internal_ids(base, secrets)
    assert_no_internal_ids(explained, secrets)
    assert_no_internal_ids(tool, secrets)


def test_real_stack_large_edge_evidence_does_not_exceed_sqlite_bind_limit(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    width = 2100
    nodes = [
        {"id": "PublicRoot", "nodeKind": "CALLABLE", "name": "Public Root", "path": "src/LargeGraph.txt"},
        *[
            {"id": f"Child{i:04d}", "nodeKind": "CALLABLE", "name": f"Child {i:04d}", "path": "src/LargeGraph.txt"}
            for i in range(width)
        ],
    ]
    edges = [
        {
            "id": f"edge-{i:04d}",
            "fromNodeId": "PublicRoot",
            "toNodeId": f"Child{i:04d}",
            "edgeType": "CALLS",
            "evidence_id": f"edge-evidence-{i:04d}",
        }
        for i in range(width)
    ]
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-large-evidence",
        nodes=nodes,
        edges=edges,
        claims=[explicit("PublicRoot", "root-claim-evidence")],
        evidence_ids=["root-claim-evidence"],
    )

    body = graph_query(app, request("Public Root"))

    assert body["status"] == "OK"
    assert "too many SQL variables" not in json.dumps(body)
    assert len(body["flows"]) == 1
    flow = body["flows"][0]
    assert flow["coverage"]["nodeCount"] == width + 1
    assert flow["coverage"]["transitionCount"] == width
    assert all(item["evidenceRefs"] for item in flow["transitions"])
    node_refs = {node["nodeRef"] for node in flow["nodes"]}
    assert any(evidence["ownerRef"] in node_refs for evidence in flow["evidence"])
    assert "FLOW_EVIDENCE_TRUNCATED" not in json.dumps(body)
    timings = next(item for item in body["diagnostics"] if item["code"] == "ENTRYPOINT_FLOW_TIMINGS")
    assert timings["metadata"]["sqlStatements"] < 100
    assert_flow_refs_close(body)
    assert_no_slice_truncation(body)


def test_real_stack_branching_explanation_returns_one_human_answer_without_refs(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    nodes = [
        {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/Alpha.txt"},
        {"id": "Beta", "nodeKind": "CALLABLE", "name": "Beta", "path": "src/Beta.txt"},
        {"id": "Gamma", "nodeKind": "CALLABLE", "name": "Gamma", "path": "src/Gamma.txt"},
        {"id": "Delta", "nodeKind": "CALLABLE", "name": "Delta", "path": "src/Delta.txt"},
        {"id": "Epsilon", "nodeKind": "CALLABLE", "name": "Epsilon", "path": "src/Epsilon.txt"},
    ]
    edges = [
        {"id": "ab", "fromNodeId": "Alpha", "toNodeId": "Beta", "edgeType": "CALLS"},
        {"id": "ag", "fromNodeId": "Alpha", "toNodeId": "Gamma", "edgeType": "CALLS"},
        {"id": "ad", "fromNodeId": "Alpha", "toNodeId": "Delta", "edgeType": "CALLS"},
        {"id": "ge", "fromNodeId": "Gamma", "toNodeId": "Epsilon", "edgeType": "CALLS"},
        {"id": "outside", "fromNodeId": "Epsilon", "edgeType": "CALLS", "resolutionStatus": "UNRESOLVED", "unresolved": {"name": "ExternalTarget"}},
    ]
    seed_semantic_graph(config.store_path, source_id="neutral-explain", nodes=nodes, edges=edges, claims=[explicit("Alpha", "ev-node-query")])
    app.state.flow_explanation_provider = GroundedProvider()

    base = graph_query(app, request("Gamma"))
    explained = post(app, "/api/v1/knowledge/query", request("Gamma")).json()

    assert len(base["flows"]) == 1
    assert_compact_human_contract(explained)
    assert explained["answers"][0]["source"] == "neutral-explain"
    assert explained["answers"][0]["entrypoint"] == "Alpha"
    assert "Alpha" in explained["answers"][0]["text"]
    transition_pairs = {
        (item["fromNodeRef"], item["toNodeRef"])
        for item in base["flows"][0]["transitions"]
    }
    assert all((transition["fromNodeRef"], transition["toNodeRef"]) in transition_pairs for transition in base["flows"][0]["transitions"])
    assert not any(
        item["fromNodeRef"] != base["flows"][0]["entrypoint"]["nodeRef"]
        and item["toNodeRef"] in {
            transition["toNodeRef"]
            for transition in base["flows"][0]["transitions"]
            if transition["fromNodeRef"] == base["flows"][0]["entrypoint"]["nodeRef"]
        }
        for item in base["flows"][0]["transitions"]
    )
    assert_no_internal_ids(explained, ("secret-node-db-id", "secret-edge-db-id", "secret-evidence-db-id"))


def test_real_stack_branching_tool_context_preserves_graph_contract(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    nodes = [
        {"id": "Alpha", "nodeKind": "CALLABLE", "name": "Alpha", "path": "src/Alpha.txt", "lineStart": 10, "lineEnd": 12},
        {"id": "Beta", "nodeKind": "CALLABLE", "name": "Beta", "path": "src/Beta.txt", "lineStart": 20, "lineEnd": 22},
        {"id": "Gamma", "nodeKind": "CALLABLE", "name": "Gamma", "path": "src/Gamma.txt", "lineStart": 30, "lineEnd": 32},
        {"id": "Delta", "nodeKind": "CALLABLE", "name": "Delta", "path": "src/Delta.txt", "lineStart": 40, "lineEnd": 42},
    ]
    edges = [
        {"id": "ab", "fromNodeId": "Alpha", "toNodeId": "Beta", "edgeType": "CALLS", "evidence_id": "ev-ab"},
        {"id": "ag", "fromNodeId": "Alpha", "toNodeId": "Gamma", "edgeType": "CALLS", "evidence_id": "ev-ag"},
        {"id": "ad", "fromNodeId": "Alpha", "toNodeId": "Delta", "edgeType": "CALLS", "evidence_id": "ev-ad"},
        {"id": "outside", "fromNodeId": "Gamma", "edgeType": "CALLS", "resolutionStatus": "UNRESOLVED", "unresolved": {"name": "ExternalTarget"}, "evidence_id": "ev-outside"},
    ]
    seed_semantic_graph(config.store_path, source_id="neutral-tool", nodes=nodes, edges=edges, claims=[explicit("Alpha", "ev-node-query")])
    app.state.flow_explanation_provider = FailingProvider()

    base = graph_query(app, request("Gamma"))
    tool = post(app, "/api/v1/knowledge/query/tool-context", request("Gamma")).json()

    assert len(base["flows"]) == 1
    assert_compact_tool_contract(tool)
    assert len(tool["trees"]) == 1
    root = tool["trees"][0]["entrypoint"]
    assert root["symbol"] == "Alpha"
    child_symbols = {child["symbol"] for child in root["children"]}
    assert {"Beta", "Gamma", "Delta"} <= child_symbols
    gamma = next(child for child in root["children"] if child["symbol"] == "Gamma")
    assert {child["symbol"] for child in gamma["children"]} == {"ExternalTarget"}
    assert all(child["evidence"] for child in root["children"])
    assert_no_internal_ids(tool, ("secret-node-db-id", "secret-edge-db-id", "secret-evidence-db-id"))


def test_real_stack_anchor_expansion_processes_all_declared_callables(tmp_path):
    app, _, config, _ = build_test_app(write_runtime_config(tmp_path))
    count = 80
    nodes = [
        {"id": "PublicType", "nodeKind": "TYPE", "name": "Public Type", "path": "src/PublicType.txt"},
        *[
            {"id": f"Callable{i:02d}", "nodeKind": "CALLABLE", "name": f"Callable {i:02d}", "path": f"src/Callable{i:02d}.txt"}
            for i in range(count)
        ],
    ]
    edges = [
        {"id": f"declares-{i:02d}", "fromNodeId": "PublicType", "toNodeId": f"Callable{i:02d}", "edgeType": "DECLARES"}
        for i in range(count)
    ]
    seed_semantic_graph(
        config.store_path,
        source_id="neutral-anchor-expansion",
        nodes=nodes,
        edges=edges,
        claims=[explicit(f"Callable{i:02d}", "ev-node-query") for i in range(count)],
    )

    body = graph_query(app, request("Public Type", max_flows=10))

    diagnostic = next(item for item in body["diagnostics"] if item["code"] == "ENTRYPOINT_FLOW_MAX_FLOWS_REACHED")
    assert diagnostic["metadata"]["discoveredEntrypointCount"] == count
    assert diagnostic["metadata"]["returnedFlowCount"] == 10
    assert diagnostic["metadata"]["omittedFlowCount"] == count - 10
    assert len(body["flows"]) == 10
    assert "ANCHOR_EXPANSION_LIMIT_REACHED" not in json.dumps(body)
    assert_flow_refs_close(body)
