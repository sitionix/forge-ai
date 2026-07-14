from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from contextlib import suppress
from types import SimpleNamespace

import httpx

import knowledge_service.main as knowledge_main
from knowledge_service.entrypoint_flow_engine import (
    EntrypointFlow,
    EntrypointFlowAnchor,
    EntrypointFlowCoverage,
    EntrypointFlowEngine,
    EntrypointFlowKey,
    EntrypointFlowOrigin,
)
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest, KnowledgeQueryResponse, KnowledgeQueryStatus
from support import build_test_app, write_runtime_config
from knowledge_service.flow_explanations import FlowExplanationProviderResult
from knowledge_service.query_interpretation import QueryInterpretationProviderResult
from semantic_test_support import seed_semantic_graph


class FakeFlowExplanationProvider:
    def __init__(self, delay_seconds=0.0):
        self.calls = []
        self.delay_seconds = delay_seconds

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "timeoutSeconds": timeout_seconds})
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        if llm_input.get("responseLanguage") == "uk":
            response = {"text": "1. A.start передає виконання до B.work за підтвердженим деревом викликів.\n2. B.work повертає підтверджений результат."}
        else:
            response = {"text": "1. A.start delegates to B.work using the grounded call tree.\n2. B.work returns the grounded result."}
        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)


class PerEntrypointAnswerProvider:
    def __init__(self, fail_entrypoints=None):
        self.calls = []
        self.fail_entrypoints = set(fail_entrypoints or [])

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "timeoutSeconds": timeout_seconds})
        entrypoint = str(llm_input.get("entrypoint") or "")
        if entrypoint in self.fail_entrypoints:
            raise RuntimeError("expected")
        response = {"text": f"1. {entrypoint} starts the selected flow.\n2. The grounded flow answer for {entrypoint} is returned."}
        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)


class SentinelAnswerProvider:
    def __init__(self, sentinel: str):
        self.sentinel = sentinel
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append(
            {
                "llmInput": dict(llm_input),
                "validationErrors": list(validation_errors or []),
                "timeoutSeconds": timeout_seconds,
            }
        )
        response = {"text": f"1. {self.sentinel}\n2. The selected flow returns the configured provider result."}
        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)


class BrokenQueryInterpretationProvider:
    def __init__(self):
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "validationErrors": list(validation_errors or [])})
        return QueryInterpretationProviderResult(raw_text="not json", prompt_char_length=100)


def _entrypoint_claim(
    node_id: str,
    *,
    kind: str = "HTTP",
    http_method: str | None = None,
    route: str | None = None,
    interface_method: str | None = None,
) -> dict:
    claim = {
        "id": f"claim-{node_id}",
        "node_id": node_id,
        "claimKind": "ENTRYPOINT_HINT",
        "summary": "entrypoint",
        "evidence_ids": ["ev-node-query"],
        "entrypointKind": kind,
    }
    if http_method:
        claim["httpMethod"] = http_method
    if route:
        claim["route"] = route
    if interface_method:
        claim["interfaceMethod"] = interface_method
    return claim


def _seed_a_start_flow(app_config) -> None:
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
        claims=[_entrypoint_claim("a-start", http_method="POST", route="/api/v1/alpha")],
    )


def _async_client(app):
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://testserver")


async def _await_with_wakeup(awaitable, *, timeout=2.0, interval=0.01):
    task = asyncio.create_task(awaitable)
    deadline = asyncio.get_running_loop().time() + timeout
    try:
        while not task.done():
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                task.cancel()
                raise asyncio.TimeoutError()
            await asyncio.sleep(min(interval, remaining))
        return await task
    finally:
        if not task.done():
            task.cancel()


def test_health_and_status_endpoint_reports_inventory_state(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    async def exercise():
        async with _async_client(app) as client:
            health = await client.get("/health")
            status = await client.get("/api/v1/knowledge/status")
            return health.json(), status.json()

    health, payload = asyncio.run(exercise())

    assert health == {"status": "UP"}
    assert payload["status"] == "UP"
    assert payload["catalog"]["configured"] is True
    assert payload["inventory"]["skippedCount"] == 0
    assert payload["inventory"]["skippedBreakdown"] == {"total": 0, "byReason": {}}
    assert payload["inventoryRefresh"]["enabled"] is False
    assert "search" not in payload
    assert "vectorStore" not in payload
    assert "rag" not in payload


def test_missing_config_sources_response(tmp_path):
    config_file = write_runtime_config(tmp_path)
    (tmp_path / "config" / "knowledge" / "knowledge-sources.yaml").unlink()
    app, _, _, _ = build_test_app(config_file)

    async def exercise():
        async with _async_client(app) as client:
            response = await client.get("/api/v1/knowledge/sources")
            return response.json()

    payload = asyncio.run(exercise())

    assert payload["sources"] == []
    assert payload["message"] == "No local knowledge-sources.yaml configured"


def test_query_flow_explanations_endpoint_returns_human_answer(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
        claims=[
            _entrypoint_claim(
                "a-start",
                http_method="POST",
                route="/api/v1/sites",
                interface_method="com.app.SiteApi.createSite",
            )
        ],
    )
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(
                client.post("/api/v1/knowledge/query", json={"queryText": "A.start", "answerLanguage": "uk"})
            )
            return response.json()

    response_payload = asyncio.run(exercise())

    assert response_payload["answerLanguage"] == "uk"
    assert response_payload["answers"] == [
        {
            "source": "source-a",
            "entrypoint": "A.start",
            "text": "1. A.start передає виконання до B.work за підтвердженим деревом викликів.\n2. B.work повертає підтверджений результат.",
        }
    ]
    assert response_payload["diagnostics"] == []
    assert "status" not in response_payload
    assert "flows" not in response_payload
    assert "flowExplanations" not in response_payload
    assert "nodeRef" not in json.dumps(response_payload)
    assert len(provider.calls) == 1
    assert provider.calls[0]["llmInput"]["entrypoint"] == "A.start"
    assert provider.calls[0]["llmInput"]["tree"]["symbol"] == "A.start"
    assert provider.calls[0]["llmInput"]["tree"]["trigger"] == {
        "kind": "HTTP",
        "method": "POST",
        "route": "/api/v1/sites",
        "interfaceMethod": "com.app.SiteApi.createSite",
    }


def test_query_endpoint_uses_configured_llm_provider_and_tool_context_does_not(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    query_provider = app.state.query_interpretation_provider
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
        claims=[_entrypoint_claim("a-start", http_method="POST", route="/api/v1/alpha")],
    )
    sentinel = "Unique sentinel sentence from the fake configured LLM provider."
    provider = SentinelAnswerProvider(sentinel)
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            human = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "how does A.start work"}))
            tool = await _await_with_wakeup(client.post("/api/v1/knowledge/query/tool-context", json={"queryText": "A.start"}))
            return human.status_code, human.json(), tool.status_code, tool.json()

    human_status, human_payload, tool_status, tool_payload = asyncio.run(exercise())

    assert human_status == 200
    assert sentinel in human_payload["answers"][0]["text"]
    assert sentinel not in json.dumps(tool_payload)
    assert tool_status == 200
    assert len(provider.calls) == 1
    assert len(query_provider.calls) == 2
    assert query_provider.calls[0]["llmInput"]["queryText"] == "how does A.start work"
    assert query_provider.calls[1]["llmInput"]["queryText"] == "A.start"
    assert provider.calls[0]["llmInput"]["entrypoint"] == "A.start"
    assert provider.calls[0]["llmInput"]["responseLanguage"] == "en"
    audit = app.state.human_answer_audit_artifacts
    assert len(audit) == 1
    assert audit[0]["provider"] == app_config.analysis_provider
    assert audit[0]["model"] == app_config.analysis_model
    assert audit[0]["flowEntrypoint"] == "A.start"
    assert audit[0]["attemptCount"] == 1
    assert audit[0]["requestedLanguage"] == "AUTO"
    assert audit[0]["resolvedLanguage"] == "en"
    assert audit[0]["promptLength"] > 0
    assert len(audit[0]["promptHash"]) == 64
    assert audit[0]["rawResponseLength"] > 0
    assert len(audit[0]["rawResponseHash"]) == 64


def test_query_audit_memory_records_are_bounded(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    app.state.app_config.query_audit_memory_max_records = 1
    _seed_a_start_flow(app.state.app_config)
    app.state.flow_explanation_provider = SentinelAnswerProvider("bounded memory audit")

    async def exercise():
        async with _async_client(app) as client:
            first = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            second = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            return first.status_code, second.status_code

    assert asyncio.run(exercise()) == (200, 200)
    assert len(app.state.human_answer_audit_artifacts) == 1
    assert len(app.state.query_interpretation_audit_artifacts) == 1


def test_query_audit_disk_retention_removes_old_files(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    app.state.app_config.query_audit_directory = tmp_path / "audit"
    app.state.app_config.query_audit_max_retained_files = 1
    _seed_a_start_flow(app.state.app_config)
    app.state.flow_explanation_provider = SentinelAnswerProvider("bounded disk audit")

    async def exercise():
        async with _async_client(app) as client:
            first = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            second = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            return first.status_code, second.status_code

    assert asyncio.run(exercise()) == (200, 200)
    files = sorted(app.state.app_config.query_audit_directory.glob("human-answer-*.json"))
    assert len(files) == 1
    assert not list(app.state.app_config.query_audit_directory.glob("*.tmp"))


def test_query_audit_write_failure_warns_without_failing_query(tmp_path, caplog, capsys):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    audit_file = tmp_path / "audit-file"
    audit_file.write_text("not a directory\n", encoding="utf-8")
    app.state.app_config.query_audit_directory = audit_file
    app.state.app_config.query_audit_max_retained_files = 1
    _seed_a_start_flow(app.state.app_config)
    app.state.flow_explanation_provider = SentinelAnswerProvider("audit warning")

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))

    with caplog.at_level(logging.WARNING, logger="knowledge_service.main"):
        response = asyncio.run(exercise())

    assert response.status_code == 200
    captured = capsys.readouterr()
    assert any(record.message == "human_answer_audit_write_failed" for record in caplog.records) or "human_answer_audit_write_failed" in captured.err


def test_query_interpretation_failure_returns_502_without_final_answer_call(tmp_path):
    app, _, _app_config, _ = build_test_app(write_runtime_config(tmp_path))
    broken_interpreter = BrokenQueryInterpretationProvider()
    final_provider = SentinelAnswerProvider("should not be used")
    app.state.query_interpretation_provider = broken_interpreter
    app.state.flow_explanation_provider = final_provider

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "how does A.start work"}))

    response = asyncio.run(exercise())

    assert response.status_code == 502
    assert response.json()["code"] == "QUERY_INTERPRETATION_FAILED"
    assert len(broken_interpreter.calls) == 2
    assert final_provider.calls == []


def test_query_endpoint_returns_one_answer_per_distinct_flow(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    roots = ["A.start", "B.start", "C.start"]
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            *[
                {"id": root.lower()[0], "nodeKind": "CALLABLE", "name": root, "qualified": root, "path": f"src/{root[0]}.java"}
                for root in roots
            ],
            {"id": "shared", "nodeKind": "CALLABLE", "name": "Shared.work", "qualified": "Shared.work", "path": "src/Shared.java"},
        ],
        edges=[
            {"id": f"edge-{root[0].lower()}-shared", "fromNodeId": root.lower()[0], "toNodeId": "shared", "edgeType": "CALLS"}
            for root in roots
        ],
        claims=[_entrypoint_claim(root.lower()[0]) for root in roots],
    )
    provider = PerEntrypointAnswerProvider()
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "Shared.work"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 200
    assert len(provider.calls) == 3
    assert [call["llmInput"]["entrypoint"] for call in provider.calls] == roots
    assert [answer["entrypoint"] for answer in payload["answers"]] == roots
    assert [answer["text"] for answer in payload["answers"]] == [
        f"1. {root} starts the selected flow.\n2. The grounded flow answer for {root} is returned."
        for root in roots
    ]
    assert payload["diagnostics"] == []
    assert "status" not in payload
    assert "flows" not in payload
    for call in provider.calls:
        rendered_prompt_facts = json.dumps(call["llmInput"], ensure_ascii=False)
        other_roots = set(roots) - {call["llmInput"]["entrypoint"]}
        assert not any(root in rendered_prompt_facts for root in other_roots)


def test_query_endpoint_partial_flow_failure_keeps_successful_answers(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b", "nodeKind": "CALLABLE", "name": "B.start", "qualified": "B.start", "path": "src/B.java"},
            {"id": "shared", "nodeKind": "CALLABLE", "name": "Shared.work", "qualified": "Shared.work", "path": "src/Shared.java"},
        ],
        edges=[
            {"id": "edge-a-shared", "fromNodeId": "a", "toNodeId": "shared", "edgeType": "CALLS"},
            {"id": "edge-b-shared", "fromNodeId": "b", "toNodeId": "shared", "edgeType": "CALLS"},
        ],
        claims=[_entrypoint_claim("a"), _entrypoint_claim("b", kind="KAFKA")],
    )
    provider = PerEntrypointAnswerProvider(fail_entrypoints={"B.start"})
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "Shared.work"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 200
    assert len(provider.calls) == 2
    assert payload["answers"] == [
        {
            "source": "source-a",
            "entrypoint": "A.start",
            "text": "1. A.start starts the selected flow.\n2. The grounded flow answer for A.start is returned.",
        }
    ]
    assert payload["diagnostics"] == [
        {
            "code": "HUMAN_FLOW_ANSWER_GENERATION_FAILED",
            "message": "The local model could not explain one selected flow.",
            "severity": "WARN",
            "sourceId": "source-a",
            "metadata": {"entrypoint": "B.start"},
        }
    ]


def test_query_endpoint_total_flow_failure_returns_502(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[{"id": "a", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"}],
        claims=[_entrypoint_claim("a")],
    )
    app.state.flow_explanation_provider = PerEntrypointAnswerProvider(fail_entrypoints={"A.start"})

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 502
    assert payload == {
        "code": "HUMAN_ANSWER_GENERATION_FAILED",
        "message": "The local model could not produce any grounded flow answers.",
    }


def test_query_endpoint_no_candidates_returns_404(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))
    app.state.flow_explanation_provider = PerEntrypointAnswerProvider()

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "Missing"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 404
    assert payload == {
        "code": "NO_GROUNDED_GRAPH_CANDIDATES",
        "message": "No grounded graph candidates were found.",
    }


def test_removed_flow_explanations_route_is_not_in_openapi(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    assert "/api/v1/knowledge/query/flow-explanations" not in app.openapi()["paths"]


def test_slow_flow_explanation_does_not_block_concurrent_health_request(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
        claims=[_entrypoint_claim("a-start", http_method="POST", route="/api/v1/sites")],
    )
    app.state.flow_explanation_provider = FakeFlowExplanationProvider(delay_seconds=0.4)

    async def exercise():
        async with _async_client(app) as client:
            slow = asyncio.create_task(
                _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            )
            await asyncio.sleep(0.05)
            started = time.monotonic()
            health = await client.get("/health")
            elapsed = time.monotonic() - started
            response = await slow
            return health, elapsed, response

    health, elapsed, response = asyncio.run(exercise())

    assert health.status_code == 200
    assert health.json() == {"status": "UP"}
    assert elapsed < 0.25
    assert response.json()["answers"][0]["text"] == "1. A.start delegates to B.work using the grounded call tree.\n2. B.work returns the grounded result."
    assert "status" not in response.json()


def test_query_preparation_consumes_flow_explanation_deadline_before_llm_call(tmp_path, monkeypatch):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    app_config.flow_explanation_request_timeout_seconds = 0.2
    app.state.app_config.flow_explanation_request_timeout_seconds = 0.2
    flow_nodes = (
        FlowGraphNode("source-a", "graph-a", "graph-a", "a-start", "a-start", "CALLABLE", "A.start"),
        FlowGraphNode("source-a", "graph-a", "graph-a", "b-work", "b-work", "CALLABLE", "B.work"),
    )
    flow_edge = FlowGraphEdge("source-a", "graph-a", "graph-a", "edge-a-b", "CALLS", "a-start", "b-work", "RESOLVED")
    flow = EntrypointFlow(
        key=EntrypointFlowKey("source-a", "graph-a", "a-start"),
        entrypoint=flow_nodes[0],
        origin=EntrypointFlowOrigin.EXPLICIT_GRAPH_FACT,
        anchors=(EntrypointFlowAnchor("a-start", "A.start", 1.0, ("EXACT_NAME",), 0),),
        nodes=flow_nodes,
        transitions=(flow_edge,),
        boundary_transitions=(),
        evidence=(),
        complete=True,
        coverage=EntrypointFlowCoverage(2, 1, 0, 1, 1),
        diagnostics=(),
        relevance_score=1.0,
    )
    flow_result = SimpleNamespace(flows=(flow,), public_flows=EntrypointFlowEngine().public_flows([flow]))
    query_response = KnowledgeQueryResponse(
        queryId="query-test",
        status=KnowledgeQueryStatus.OK,
        intent="FLOW_EXPLANATION",
        matchedSources=[{"sourceId": "source-a", "displayName": "Source A", "score": 1.0}],
        flows=flow_result.public_flows,
    )

    class SlowQueryService:
        def query_with_flows(self, body, plan=None):
            time.sleep(0.05)
            return SimpleNamespace(response=query_response, flows=flow_result.flows)

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", lambda *_args, **_kwargs: SlowQueryService())
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    deadline_at = time.monotonic() + 0.2
    time.sleep(0.03)

    response = knowledge_main._knowledge_human_query_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
        deadline_at=deadline_at,
    )

    assert response.answers[0].text == "1. A.start delegates to B.work using the grounded call tree.\n2. B.work returns the grounded result."
    assert response.answers[0].entrypoint == "A.start"
    assert provider.calls
    assert 0 < provider.calls[0]["timeoutSeconds"] < 0.17


def test_expired_flow_explanation_deadline_before_worker_returns_controlled_response(tmp_path, monkeypatch):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    def fail_if_called(*_args, **_kwargs):
        raise AssertionError("query service should not be built after deadline expiry")

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", fail_if_called)
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    response = knowledge_main._knowledge_human_query_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
        deadline_at=time.monotonic() - 0.001,
    )

    assert response.status_code == 504
    body = json.loads(response.body.decode("utf-8"))
    assert body == {
        "code": "FLOW_EXPLANATION_TIMEOUT",
        "message": "Knowledge flow explanation timed out.",
    }
    assert provider.calls == []


def test_tool_context_endpoint_returns_compact_nested_tree(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
        claims=[_entrypoint_claim("a-start", http_method="POST", route="/api/v1/sites")],
    )
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query/tool-context", json={"queryText": "A.start"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 200
    assert payload["queryText"] == "A.start"
    assert payload["trees"][0]["source"] == "source-a"
    assert payload["trees"][0]["entrypoint"]["symbol"] == "A.start"
    assert payload["trees"][0]["entrypoint"]["kind"] == "HTTP_ENDPOINT"
    assert payload["trees"][0]["entrypoint"]["trigger"] == {"kind": "HTTP", "method": "POST", "route": "/api/v1/sites"}
    assert payload["trees"][0]["entrypoint"]["children"][0]["symbol"] == "B.work"
    rendered = json.dumps(payload)
    assert "status" not in payload
    assert "flows" not in payload
    assert "nodeRef" not in rendered
    assert "transitionRef" not in rendered
    assert provider.calls == []


def test_missing_http_trigger_details_are_not_invented(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
        claims=[_entrypoint_claim("a-start")],
    )
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            return response.status_code

    status = asyncio.run(exercise())

    assert status == 200
    tree = provider.calls[0]["llmInput"]["tree"]
    assert tree["trigger"] == {"kind": "HTTP"}
    assert "method" not in tree["trigger"]
    assert "route" not in tree["trigger"]


def test_cancelled_flow_explanation_request_does_not_start_subsequent_flow_calls(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "d-start", "nodeKind": "CALLABLE", "name": "D.start", "qualified": "D.start", "path": "src/D.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[
            {"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"},
            {"id": "edge-d-b", "fromNodeId": "d-start", "toNodeId": "b-work", "edgeType": "CALLS"},
        ],
    )

    class BlockingProvider:
        def __init__(self):
            self.calls = []
            self.first_started = threading.Event()
            self.first_returned = threading.Event()
            self.release_first = threading.Event()
            self.second_started = threading.Event()

        def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
            self.calls.append({"llmInput": dict(llm_input), "timeoutSeconds": timeout_seconds})
            if len(self.calls) == 1:
                self.first_started.set()
                self.release_first.wait(timeout=1.0)
                self.first_returned.set()
            else:
                self.second_started.set()
            response = {"text": "1. The cancelled request had already started producing a human answer.\n2. The provider returns without starting a second flow."}
            return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)

    provider = BlockingProvider()
    app.state.flow_explanation_provider = provider

    async def wait_for_event(event, timeout=1.0):
        deadline = asyncio.get_running_loop().time() + timeout
        while not event.is_set():
            if asyncio.get_running_loop().time() >= deadline:
                raise AssertionError("timed out waiting for event")
            await asyncio.sleep(0.01)

    async def exercise():
        async with _async_client(app) as client:
            request_task = asyncio.create_task(client.post("/api/v1/knowledge/query", json={"queryText": "B.work"}))
            await wait_for_event(provider.first_started)
            request_task.cancel()
            with suppress(asyncio.CancelledError):
                await request_task
            provider.release_first.set()
            await wait_for_event(provider.first_returned)
            await asyncio.sleep(0.05)

    asyncio.run(exercise())

    assert len(provider.calls) == 1
    assert provider.second_started.is_set() is False
