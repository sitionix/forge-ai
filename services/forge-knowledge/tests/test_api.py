from __future__ import annotations

import asyncio
import json
import logging
import time
from types import SimpleNamespace

import httpx

import knowledge_service.main as knowledge_main
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.query_interpretation import QueryInterpretationProviderResult
from semantic_test_support import seed_semantic_graph
from support import AsgiTestClient, build_test_app, write_runtime_config

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
    execution_kind: str = "EXECUTABLE",
    evidence_ids: list[str] | None = None,
) -> dict:
    claim = {
        "id": f"claim-{node_id}",
        "node_id": node_id,
        "claimKind": "ENTRYPOINT_HINT",
        "summary": "entrypoint",
        "evidence_ids": evidence_ids or ["ev-node-query"],
        "entrypointKind": kind,
        "entrypointExecutionKind": execution_kind,
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


def test_analysis_disabled_skips_analysis_lifespan_startup(tmp_path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, analysis_enabled=False))

    with AsgiTestClient(app) as client:
        response = client.get("/health")

    assert response.json() == {"status": "UP"}
    assert deps.analysis_supervisor._started is False


def test_startup_maintenance_disabled_skips_analysis_reconciliation(tmp_path, monkeypatch):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, startup_maintenance_enabled=False))

    def fail_if_called(_source_ids):
        raise AssertionError("dirty source finalization must not run when startup maintenance is disabled")

    monkeypatch.setattr(deps.analysis_supervisor, "_finalize_dirty_sources", fail_if_called)

    with AsgiTestClient(app) as client:
        response = client.get("/health")

    assert response.json() == {"status": "UP"}
    assert deps.analysis_supervisor._started is True


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


def test_explicit_forbidden_answer_language_returns_controlled_422(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))
    query_provider = app.state.query_interpretation_provider

    async def exercise():
        async with _async_client(app) as client:
            human = await client.post("/api/v1/knowledge/query", json={"queryText": "A.start", "answerLanguage": "ru"})
            tool = await client.post("/api/v1/knowledge/query/tool-context", json={"queryText": "A.start", "answerLanguage": "ru"})
            return human.status_code, human.json(), tool.status_code, tool.json()

    human_status, human_payload, tool_status, tool_payload = asyncio.run(exercise())

    expected = {
        "code": "RESPONSE_LANGUAGE_NOT_ALLOWED",
        "message": "The requested response language is not allowed.",
    }
    assert human_status == 422
    assert human_payload["code"] == expected["code"]
    assert human_payload["message"] == expected["message"]
    assert "correlationId" in human_payload
    assert tool_status == 422
    assert tool_payload == expected
    assert query_provider.calls == []


def test_query_endpoint_returns_formatter_backed_human_answer(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(
                client.post("/api/v1/knowledge/query", json={"queryText": "A.start", "answerLanguage": "uk"})
            )
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 200
    assert payload["answerLanguage"] == "uk"
    assert len(payload["answers"]) == 1
    answer = payload["answers"][0]
    assert answer["source"] == "source-a"
    assert answer["entrypoint"] == "A.start"
    assert answer["text"].startswith("1. ")
    assert "\n2. " in answer["text"]
    assert "A.start" in answer["text"]
    assert "B.work" in answer["text"]
    assert "POST" in answer["text"]
    assert "/api/v1/alpha" in answer["text"]
    assert "evidence" not in answer["text"].lower()
    assert "GRAPH_V2" not in answer["text"]
    assert payload["diagnostics"] == []
    assert "status" not in payload
    assert "flows" not in payload


def test_query_tool_context_remains_technical_and_human_response_has_no_evidence(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            body = {"queryText": "A.start"}
            tool = await _await_with_wakeup(client.post("/api/v1/knowledge/query/tool-context", json=body))
            human = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json=body))
            return tool.status_code, tool.json(), human.status_code, human.json()

    tool_status, tool_payload, human_status, human_payload = asyncio.run(exercise())

    assert tool_status == 200
    assert human_status == 200
    tree = tool_payload["flows"][0]["parts"][0]["tree"]
    assert tree["source"] == "source-a"
    assert tree["entrypoint"]["symbol"] == "A.start"
    assert tree["entrypoint"]["trigger"] == {"kind": "HTTP", "method": "POST", "route": "/api/v1/alpha"}
    assert tree["entrypoint"]["children"][0]["symbol"] == "B.work"
    assert "excerpt-ev-node-query" in json.dumps(tool_payload)
    assert "excerpt-ev-node-query" not in json.dumps(human_payload)
    assert "tree" not in json.dumps(human_payload)


def test_human_query_writes_formatter_terminal_audit_record(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    app.state.app_config.query_audit_directory = tmp_path / "audit"
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(
                client.post(
                    "/api/v1/knowledge/query",
                    json={"queryText": "A.start"},
                    headers={"X-Correlation-Id": "corr-terminal-success"},
                )
            )

    response = asyncio.run(exercise())

    assert response.status_code == 200
    records = list(app.state.human_query_terminal_audit_artifacts)
    assert len(records) == 1
    record = records[0]
    assert record["correlationId"] == "corr-terminal-success"
    assert record["terminalHttpStatus"] == 200
    assert record["terminalStage"] == "SUCCESS"
    assert record["queryInterpreterCallCount"] == 1
    assert record["narrativePlanCount"] == 1
    assert record["verifiedFragmentCount"] == 1
    assert record["walkthroughStepCount"] >= 3
    assert record["answerCount"] == 1
    assert record["formatterProviderCallCount"] == 1
    assert record["finalAnswerProviderCallCount"] == 1
    assert record["groundingProviderCallCount"] == 0
    assert record["toolContextFormatterCallCount"] == 0
    assert record["providerCallCount"] == 1
    assert record["fetchDurationMs"] >= 0
    assert record["walkthroughPlanningDurationMs"] >= 0
    assert record["formatterPlanningDurationMs"] >= 0
    assert record["formatterDurationMs"] >= 0
    assert record["textRenderingDurationMs"] >= 0
    files = sorted(app.state.app_config.query_audit_directory.glob("human-query-terminal-*.json"))
    assert len(files) == 1
    assert json.loads(files[0].read_text(encoding="utf-8"))["correlationId"] == "corr-terminal-success"


def test_query_interpretation_failure_returns_502_without_final_answer_call(tmp_path):
    app, _, _app_config, _ = build_test_app(write_runtime_config(tmp_path))
    broken_interpreter = BrokenQueryInterpretationProvider()
    app.state.query_interpretation_provider = broken_interpreter

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "how does A.start work"}))

    response = asyncio.run(exercise())

    assert response.status_code == 502
    assert response.json()["code"] == "QUERY_INTERPRETATION_FAILED"
    assert len(broken_interpreter.calls) == 2


def test_query_endpoint_returns_one_answer_per_independent_narrative_plan(tmp_path):
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
    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "Shared.work"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 200
    assert [answer["entrypoint"] for answer in payload["answers"]] == roots
    assert all(answer["text"].startswith("1. ") for answer in payload["answers"])
    assert payload["diagnostics"] == []
    assert "flows" not in payload


def test_query_endpoint_no_candidates_returns_404(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "Missing"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 404
    assert payload["code"] == "NO_GROUNDED_GRAPH_CANDIDATES"
    assert payload["message"] == "No grounded graph candidates were found."
    assert "correlationId" in payload


def test_unexpected_human_query_exception_logs_stage_and_correlation(tmp_path, monkeypatch, caplog, capsys):
    app, _, _app_config, _ = build_test_app(write_runtime_config(tmp_path))

    def fail_build_query_service(*_args, **_kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", fail_build_query_service)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(
                client.post(
                    "/api/v1/knowledge/query",
                    json={"queryText": "A.start"},
                    headers={"X-Correlation-Id": "corr-unexpected"},
                )
            )

    with caplog.at_level(logging.ERROR, logger="knowledge_service.main"):
        response = asyncio.run(exercise())

    assert response.status_code == 503
    assert response.json()["code"] == "KNOWLEDGE_QUERY_FAILED"
    record = app.state.human_query_terminal_audit_artifacts[-1]
    assert record["correlationId"] == "corr-unexpected"
    assert record["terminalStage"] == "UNEXPECTED_EXCEPTION"
    assert record["unexpectedExceptionClass"] == "RuntimeError"
    assert record["unexpectedExceptionStage"] == "RETRIEVAL"
    matching_logs = [item for item in caplog.records if item.message == "knowledge_human_query_unexpected_exception"]
    if matching_logs:
        log_record = matching_logs[0]
        assert log_record.correlationId == "corr-unexpected"
        assert log_record.terminalStage == "UNEXPECTED_EXCEPTION"
        assert log_record.failureStage == "RETRIEVAL"
        assert log_record.exceptionClass == "RuntimeError"
        assert log_record.exc_info is not None
    else:
        captured = capsys.readouterr()
        assert "knowledge_human_query_unexpected_exception" in captured.err
        assert "RuntimeError: boom" in captured.err


def test_expired_human_query_deadline_before_worker_returns_controlled_response(tmp_path, monkeypatch):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    def fail_if_called(*_args, **_kwargs):
        raise AssertionError("query service should not be built after deadline expiry")

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", fail_if_called)

    response = knowledge_main._knowledge_human_query_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
        deadline_at=time.monotonic() - 0.001,
    )

    assert response.status_code == 504
    body = json.loads(response.body.decode("utf-8"))
    assert body["code"] == "HUMAN_QUERY_TIMEOUT"
    assert body["message"] == "Knowledge human query timed out."
    assert "correlationId" in body


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
    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))
            return response.status_code, response.json()

    status, payload = asyncio.run(exercise())

    assert status == 200
    text = payload["answers"][0]["text"]
    assert "POST" not in text
    assert "/api/" not in text
    assert "receives ." not in text
    assert "receives." not in text
