from __future__ import annotations

import asyncio
import json

import httpx
import pytest
from semantic_test_support import seed_semantic_graph
from support import build_test_app, write_runtime_config

from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import build_knowledge_query_service

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


def explicit(node_id: str, evidence_id: str, *, method: str | None = None, route: str | None = None):
    claim = {
        "id": f"claim-{node_id}",
        "node_id": node_id,
        "claimKind": "ENTRYPOINT_HINT",
        "summary": "typed execution root",
        "evidence_ids": [evidence_id],
        "entrypointKind": "HTTP",
    }
    if method:
        claim["httpMethod"] = method
    if route:
        claim["route"] = route
    return claim


def test_formatter_human_and_tool_context_project_same_persisted_flow(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "entry", "nodeKind": "CALLABLE", "name": "Entry.run", "qualified": "pkg.Entry.run", "path": "src/Entry.java"},
            {"id": "worker", "nodeKind": "CALLABLE", "name": "Worker.apply", "qualified": "pkg.Worker.apply", "path": "src/Worker.java"},
        ],
        edges=[{"id": "entry-worker", "fromNodeId": "entry", "toNodeId": "worker", "edgeType": "CALLS", "evidence_id": "ev-edge"}],
        claims=[explicit("entry", "ev-entry", method="POST", route="/flows")],
        evidence_ids=["ev-entry", "ev-edge"],
    )

    base_payload, human_payload, tool_payload = query_all_surfaces(app, "Entry.run")

    assert len(base_payload["graphs"]) == 1
    assert human_payload["answerLanguage"] == "en"
    assert len(human_payload["answers"]) == 1
    answer = human_payload["answers"][0]
    assert answer["sources"] == ["source-a"]
    assert answer["queryEntries"][0]["root"]["label"] == "Entry.run"
    assert "Entry.run" in answer["text"]
    assert human_payload["diagnostics"] == []
    assert "excerpt" not in json.dumps(human_payload)
    tool_graph = tool_payload["graphs"][0]
    assert tool_graph["graphId"] == base_payload["graphs"][0]["graphId"]
    assert tool_graph["units"][0]["nodes"][0]["label"] == "Entry.run"
    assert "excerpt-ev-entry" in json.dumps(tool_payload)


def test_multiple_independent_entrypoints_preserve_backend_order(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "alpha", "nodeKind": "CALLABLE", "name": "Alpha.start", "qualified": "Alpha.start", "path": "src/Alpha.java"},
            {"id": "beta", "nodeKind": "CALLABLE", "name": "Beta.start", "qualified": "Beta.start", "path": "src/Beta.java"},
            {"id": "shared", "nodeKind": "CALLABLE", "name": "Shared.run", "qualified": "Shared.run", "path": "src/Shared.java"},
        ],
        edges=[
            {"id": "alpha-shared", "fromNodeId": "alpha", "toNodeId": "shared", "edgeType": "CALLS", "evidence_id": "ev-alpha"},
            {"id": "beta-shared", "fromNodeId": "beta", "toNodeId": "shared", "edgeType": "CALLS", "evidence_id": "ev-beta"},
        ],
        claims=[explicit("alpha", "ev-alpha"), explicit("beta", "ev-beta")],
        evidence_ids=["ev-alpha", "ev-beta"],
    )

    payload = post(app, "/api/v1/knowledge/query", request("Shared.run")).json()

    assert len(payload["answers"]) == 1
    assert [entry["root"]["label"] for entry in payload["answers"][0]["queryEntries"]] == ["Alpha.start"]
    assert "tree" not in json.dumps(payload)


def test_partial_unresolved_boundary_is_formatted_and_successful(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "entry", "nodeKind": "CALLABLE", "name": "Entry.run", "qualified": "Entry.run", "path": "src/Entry.java"},
        ],
        edges=[
            {
                "id": "entry-missing",
                "fromNodeId": "entry",
                "edgeType": "CALLS",
                "resolutionStatus": "UNRESOLVED",
                "unresolved": {"qualifiedName": "Missing.run"},
                "evidence_id": "ev-entry",
            }
        ],
        claims=[explicit("entry", "ev-entry")],
        evidence_ids=["ev-entry"],
    )

    response = post(app, "/api/v1/knowledge/query", request("Entry.run"))
    payload = response.json()

    assert response.status_code == 200
    assert len(payload["answers"]) == 1
    text = payload["answers"][0]["text"]
    assert "Entry.run" in text
    assert "Missing.run" in text
    assert "evidence" not in text.lower()
    assert "formatter" not in text.lower()


def test_http_only_metadata_does_not_merge_components_without_generic_proof(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    for source_id, node_id, qualified in (
        ("source-a", "http-a", "HttpA.run"),
        ("source-b", "http-b", "HttpB.run"),
    ):
        seed_semantic_graph(
            app_config.store_path,
            source_id=source_id,
            nodes=[
                {"id": node_id, "nodeKind": "CALLABLE", "name": qualified, "qualified": qualified, "path": f"src/{node_id}.java"},
            ],
            claims=[explicit(node_id, f"ev-{node_id}", method="POST", route="/same-route")],
            evidence_ids=[f"ev-{node_id}"],
        )

    base_payload, _human_payload, tool_payload = query_all_surfaces(app, "HttpA.run HttpB.run")

    assert base_payload["graphs"]
    assert all(not graph["provenTransitions"] for graph in tool_payload["graphs"])
    graph_sources = [sorted({unit["sourceId"] for unit in graph["units"]}) for graph in tool_payload["graphs"]]
    assert graph_sources
    assert all(len(source_ids) == 1 for source_ids in graph_sources)
    assert sorted(source_ids[0] for source_ids in graph_sources) == ["source-a", "source-b"]
    assert "gap" not in json.dumps(tool_payload).lower()
    diagnostic_text = json.dumps([diagnostic for graph in tool_payload["graphs"] for diagnostic in graph.get("diagnostics", [])]).upper()
    assert "TRANSPORT" not in diagnostic_text
    assert "HTTP" not in diagnostic_text


def test_human_terminal_audit_records_canonical_formatter_calls(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "entry", "nodeKind": "CALLABLE", "name": "Entry.run", "qualified": "Entry.run", "path": "src/Entry.java"},
        ],
        claims=[explicit("entry", "ev-entry")],
        evidence_ids=["ev-entry"],
    )

    response = post(app, "/api/v1/knowledge/query", request("Entry.run"))

    assert response.status_code == 200
    record = app.state.human_query_terminal_audit_artifacts[-1]
    assert record["terminalStage"] == "SUCCESS"
    assert record["queryInterpreter"]["providerCallCount"] == 1
    narration = record["narration"]
    assert narration["answerCount"] == 1
    assert narration["formatterProviderCallCount"] == 1
    assert narration["formatterRepairCallCount"] == 0
    assert narration["narrationClauseCount"] >= 1
    assert narration["validatedClauseCount"] == narration["narrationClauseCount"]
    assert narration["publicClauseCount"] == narration["narrationClauseCount"]
    assert narration["narrationContractMatched"] is True
    assert narration["canonicalFactOwnership"]
    assert narration["narrationPlanningDurationMs"] >= 0
    assert narration["formatterDurationMs"] >= 0
