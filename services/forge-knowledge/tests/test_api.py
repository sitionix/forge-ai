from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import time
from types import SimpleNamespace

import httpx
from semantic_test_support import seed_semantic_graph
from support import (
    AsgiTestClient,
    DeterministicFinalFlowFormatterProvider,
    DeterministicQueryInterpretationProvider,
    build_test_app,
    write_runtime_config,
)

import knowledge_service.main as knowledge_main
from knowledge_service.ai_runtime_discovery import (
    READY,
    UNAVAILABLE,
    AiRuntimeDiscoveryRegistry,
    AiRuntimeDiscoveryService,
    AiRuntimeEffortOption,
    AiRuntimeModelOption,
    AiRuntimeProviderOptions,
)
from knowledge_service.canonical_narration_contract import CanonicalNarrationMetrics
from knowledge_service.generative_runtime import GenerativeRequest, GenerativeResponse
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.query_interpretation import QueryInterpretationProviderResult


class BrokenQueryInterpretationProvider:
    def __init__(self):
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "validationErrors": list(validation_errors or [])})
        return QueryInterpretationProviderResult(raw_text="not json", prompt_char_length=100)


class StaticAiRuntimeSource:
    def __init__(self, result: AiRuntimeProviderOptions) -> None:
        self.provider_id = result.provider_id
        self.display_name = result.display_name
        self._result = result

    async def discover(self) -> AiRuntimeProviderOptions:
        return self._result


class RoutingFakeGenerativeProvider:
    provider_id = "fake-generative"
    provider_version = "test"

    def __init__(self) -> None:
        self.requests: list[GenerativeRequest] = []
        self.query_provider = DeterministicQueryInterpretationProvider()
        self.formatter_provider = DeterministicFinalFlowFormatterProvider()

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        self.requests.append(request)
        if "BEGIN_QUERY_INTERPRETATION_INPUT_JSON" in request.prompt:
            raw_text = self.query_provider.complete(_extract_prompt_json(request.prompt, "QUERY_INTERPRETATION_INPUT")).raw_text
        elif "BEGIN_CANONICAL_FORMATTER_INPUT_JSON" in request.prompt:
            raw_text = self.formatter_provider.generate(
                _extract_prompt_json(request.prompt, "CANONICAL_FORMATTER_INPUT"),
                deadline_at=9999999999.0,
                cancel_event=None,
            ).raw_text
        else:
            raise AssertionError("unexpected generative prompt")
        return GenerativeResponse(
            raw_text=raw_text,
            provider_id=self.provider_id,
            provider_version=self.provider_version,
            model_id=request.model_id,
            duration_ms=1.0,
            prompt_char_length=len(request.prompt),
            prompt_hash=hashlib.sha256(request.prompt.encode("utf-8")).hexdigest(),
            response_char_length=len(raw_text),
            response_hash=hashlib.sha256(raw_text.encode("utf-8")).hexdigest(),
            provider_metadata={"done": True},
        )

    def close(self) -> None:
        return None


def _extract_prompt_json(prompt: str, marker: str) -> dict:
    start = f"BEGIN_{marker}_JSON"
    end = f"END_{marker}_JSON"
    payload = prompt.split(start, 1)[1].split(end, 1)[0].strip()
    return json.loads(payload)


def _remove_injected_query_formatter_providers(app, *, query: bool = False, formatter: bool = False) -> None:
    if query and hasattr(app.state, "query_interpretation_provider"):
        del app.state.query_interpretation_provider
    if formatter and hasattr(app.state, "end_to_end_formatter_provider"):
        del app.state.end_to_end_formatter_provider


def _audit_alias(*parts: str) -> str:
    return "".join(parts)


LEGACY_AUDIT_ALIASES = (
    _audit_alias("walk", "throughPlanningDurationMs"),
    _audit_alias("walk", "throughStepCount"),
    _audit_alias("walk", "through"),
    _audit_alias("walk", "throughPlans"),
    _audit_alias("formatter", "GroupCount"),
    _audit_alias("formatter", "OutputSplitCallCount"),
    _audit_alias("selected", "ExecutableNodeCount"),
    _audit_alias("selected", "ExecutableStageRefs"),
    _audit_alias("selected", "ExecutableSymbols"),
    _audit_alias("selected", "ExecutableStages"),
    _audit_alias("executable", "PublicStageCount"),
    _audit_alias("missing", "ExecutableStageRefs"),
    _audit_alias("duplicate", "ExecutableStageRefs"),
    _audit_alias("standalone", "OperationStageCount"),
    _audit_alias("boundary", "StageCount"),
    _audit_alias("ownerless", "BoundaryStageCount"),
    _audit_alias("executable", "OwnedBoundaryFactCount"),
    _audit_alias("ownerless", "BoundaryFactCount"),
    _audit_alias("presentation", "StageCount"),
    _audit_alias("presentation", "StageRefs"),
    _audit_alias("presentation", "Stages"),
    _audit_alias("expected", "PresentationStageCount"),
    _audit_alias("expected", "PublicStageCount"),
    _audit_alias("validated", "FormatterStepCount"),
    _audit_alias("stitched", "PublicStepCount"),
    _audit_alias("public", "StepCount"),
    _audit_alias("stage", "CountContractExpected"),
    _audit_alias("stage", "CountContractMatched"),
    _audit_alias("stage", "OwnershipRecords"),
    _audit_alias("stage", "OwnershipMap"),
    _audit_alias("owned", "FactRefsByStageRef"),
    _audit_alias("fact", "OwnerByFactRef"),
    _audit_alias("stitching", "DurationMs"),
)


def _assert_no_legacy_audit_aliases(record: dict) -> None:
    text = json.dumps(record, sort_keys=True)
    for alias in LEGACY_AUDIT_ALIASES:
        assert f'"{alias}"' not in text


def _assert_forbidden_ai_runtime_fields_absent(payload) -> None:
    forbidden = {
        "schemaVersion",
        "currentSelection",
        "activeSelection",
        "actions",
        "applyEnabled",
        "profiles",
        "capabilities",
        "metadata",
        "message",
        "usage",
        "limits",
        "rateLimits",
        "authentication",
        "account",
        "isDefault",
        "serviceTiers",
        "speedTiers",
        "runningModels",
        "loadedModels",
        "VRAM",
        "sizeBytes",
        "parameterSize",
        "quantization",
        "family",
        "modelContextLimit",
        "configuredContextTokens",
        "embeddingLength",
        "digest",
    }
    if isinstance(payload, dict):
        assert forbidden.isdisjoint(payload.keys())
        for value in payload.values():
            _assert_forbidden_ai_runtime_fields_absent(value)
    elif isinstance(payload, list):
        for value in payload:
            _assert_forbidden_ai_runtime_fields_absent(value)


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


def test_ai_runtime_endpoint_returns_minimal_dynamic_provider_contract(tmp_path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path))
    service = AiRuntimeDiscoveryService(
        AiRuntimeDiscoveryRegistry(
            [
                StaticAiRuntimeSource(
                    AiRuntimeProviderOptions(
                        provider_id="ollama",
                        display_name="Ollama",
                        status=READY,
                        version="0.30.6",
                        models=(
                            AiRuntimeModelOption(
                                model_id="qwen2.5-coder:14b",
                                display_name="qwen2.5-coder:14b",
                                modified_at="2026-06-14T11:24:58.816615534+03:00",
                            ),
                        ),
                    )
                ),
                StaticAiRuntimeSource(
                    AiRuntimeProviderOptions(
                        provider_id="codex",
                        display_name="Codex",
                        status=READY,
                        version="0.146.0",
                        models=(
                            AiRuntimeModelOption(
                                model_id="gpt-5.6-sol",
                                display_name="GPT-5.6-Sol",
                                description="Latest frontier agentic coding model.",
                                efforts=(
                                    AiRuntimeEffortOption(
                                        effort_id="low",
                                        description="Fast responses with lighter reasoning",
                                    ),
                                ),
                            ),
                        ),
                    )
                ),
            ]
        )
    )
    object.__setattr__(deps, "ai_runtime_discovery", service)

    async def exercise():
        async with _async_client(app) as client:
            response = await client.get("/api/v1/knowledge/ai-runtime")
            return response.status_code, response.json()

    status_code, payload = asyncio.run(exercise())

    assert status_code == 200
    assert payload == {
        "providers": [
            {
                "providerId": "ollama",
                "displayName": "Ollama",
                "status": "READY",
                "models": [
                    {
                        "modelId": "qwen2.5-coder:14b",
                        "displayName": "qwen2.5-coder:14b",
                        "modifiedAt": "2026-06-14T11:24:58.816615534+03:00",
                    }
                ],
                "version": "0.30.6",
            },
            {
                "providerId": "codex",
                "displayName": "Codex",
                "status": "READY",
                "models": [
                    {
                        "modelId": "gpt-5.6-sol",
                        "displayName": "GPT-5.6-Sol",
                        "description": "Latest frontier agentic coding model.",
                        "efforts": [{"effortId": "low", "description": "Fast responses with lighter reasoning"}],
                    }
                ],
                "version": "0.146.0",
            },
        ]
    }
    _assert_forbidden_ai_runtime_fields_absent(payload)


def test_ai_runtime_endpoint_returns_200_with_both_registered_providers_unavailable(tmp_path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path))
    service = AiRuntimeDiscoveryService(
        AiRuntimeDiscoveryRegistry(
            [
                StaticAiRuntimeSource(
                    AiRuntimeProviderOptions(
                        provider_id="ollama",
                        display_name="Ollama",
                        status=UNAVAILABLE,
                    )
                ),
                StaticAiRuntimeSource(
                    AiRuntimeProviderOptions(
                        provider_id="codex",
                        display_name="Codex",
                        status=UNAVAILABLE,
                    )
                ),
            ]
        )
    )
    object.__setattr__(deps, "ai_runtime_discovery", service)

    async def exercise():
        async with _async_client(app) as client:
            response = await client.get("/api/v1/knowledge/ai-runtime")
            return response.status_code, response.json()

    status_code, payload = asyncio.run(exercise())

    assert status_code == 200
    assert payload == {
        "providers": [
            {
                "providerId": "ollama",
                "displayName": "Ollama",
                "status": "UNAVAILABLE",
                "models": [],
            },
            {
                "providerId": "codex",
                "displayName": "Codex",
                "status": "UNAVAILABLE",
                "models": [],
            },
        ]
    }
    _assert_forbidden_ai_runtime_fields_absent(payload)


def test_ai_runtime_endpoint_missing_discovery_service_returns_503_safe_error(tmp_path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path))
    object.__setattr__(deps, "ai_runtime_discovery", None)

    async def exercise():
        async with _async_client(app) as client:
            response = await client.get("/api/v1/knowledge/ai-runtime")
            return response.status_code, response.json()

    status_code, payload = asyncio.run(exercise())

    assert status_code == 503
    assert payload["code"] == "AI_RUNTIME_DISCOVERY_UNAVAILABLE"
    assert payload["message"] == "AI runtime discovery is unavailable"
    assert "correlationId" in payload


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
    assert answer["sources"] == ["source-a"]
    assert answer["queryEntries"][0]["root"]["label"] == "A.start"
    assert "A.start" in answer["text"]
    assert "evidence" not in answer["text"].lower()
    assert "GRAPH_V2" not in answer["text"]
    assert payload["diagnostics"] == []
    assert "status" not in payload


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
    graph = tool_payload["graphs"][0]
    assert graph["units"][0]["sourceId"] == "source-a"
    assert graph["units"][0]["nodes"][0]["label"] == "A.start"
    assert "excerpt-ev-node-query" in json.dumps(tool_payload)
    assert "excerpt-ev-node-query" not in json.dumps(human_payload)
    assert "tree" not in json.dumps(human_payload)


def test_query_runtime_uses_dependencies_generative_provider(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    _remove_injected_query_formatter_providers(app, query=True)
    generative_provider = RoutingFakeGenerativeProvider()
    object.__setattr__(deps, "generative_provider", generative_provider)
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query/tool-context", json={"queryText": "A.start"}))

    response = asyncio.run(exercise())

    assert response.status_code == 200
    assert len(generative_provider.query_provider.calls) == 1
    assert any("BEGIN_QUERY_INTERPRETATION_INPUT_JSON" in request.prompt for request in generative_provider.requests)


def test_formatter_runtime_uses_dependencies_generative_provider(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    _remove_injected_query_formatter_providers(app, formatter=True)
    generative_provider = RoutingFakeGenerativeProvider()
    object.__setattr__(deps, "generative_provider", generative_provider)
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))

    response = asyncio.run(exercise())

    assert response.status_code == 200
    assert len(generative_provider.formatter_provider.calls) >= 1
    assert any("BEGIN_CANONICAL_FORMATTER_INPUT_JSON" in request.prompt for request in generative_provider.requests)


def test_missing_generative_provider_fails_query_without_local_ollama_fallback(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    _remove_injected_query_formatter_providers(app, query=True)
    object.__setattr__(deps, "generative_provider", None)
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query/tool-context", json={"queryText": "A.start"}))

    response = asyncio.run(exercise())

    assert response.status_code == 503
    assert response.json()["code"] == "GENERATIVE_PROVIDER_UNAVAILABLE"
    assert response.json()["message"] == "Knowledge generative provider is not configured"
    assert not hasattr(knowledge_main, "LocalOllamaQueryInterpretationClient")


def test_missing_generative_provider_fails_formatter_without_local_ollama_fallback(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    _remove_injected_query_formatter_providers(app, formatter=True)
    object.__setattr__(deps, "generative_provider", None)
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))

    response = asyncio.run(exercise())

    assert response.status_code == 503
    assert response.json()["code"] == "GENERATIVE_PROVIDER_UNAVAILABLE"
    assert response.json()["message"] == "Knowledge generative provider is not configured"
    assert not hasattr(knowledge_main, "LocalOllamaEndToEndFormatterClient")


def test_existing_app_state_injected_query_and_formatter_providers_still_work(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    object.__setattr__(deps, "generative_provider", None)
    _seed_a_start_flow(app_config)

    async def exercise():
        async with _async_client(app) as client:
            return await _await_with_wakeup(client.post("/api/v1/knowledge/query", json={"queryText": "A.start"}))

    response = asyncio.run(exercise())

    assert response.status_code == 200
    assert len(app.state.query_interpretation_provider.calls) == 1
    assert len(app.state.end_to_end_formatter_provider.calls) >= 1


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
    assert record["queryInterpreter"]["providerCallCount"] == 1
    narration = record["narration"]
    assert narration["selectedGraphCount"] == 1
    assert narration["answerCount"] == 1
    assert narration["narrationClauseCount"] >= 1
    assert narration["validatedClauseCount"] == narration["narrationClauseCount"]
    assert narration["publicClauseCount"] == narration["narrationClauseCount"]
    assert narration["narrationContractMatched"] is True
    assert narration["canonicalFactOwnership"]
    assert narration["missingClauseCount"] == 0
    assert narration["duplicateClauseCount"] == 0
    assert narration["unknownClauseCount"] == 0
    assert narration["unownedCanonicalFactCount"] == 0
    assert narration["duplicateCanonicalFactCount"] == 0
    assert narration["formatterProviderCallCount"] == 1
    assert narration["formatterRepairCallCount"] == 0
    assert narration["narrationPlanningDurationMs"] >= 0
    assert narration["formatterDurationMs"] >= 0
    assert narration["totalFormatterDurationMs"] >= 0
    _assert_no_legacy_audit_aliases(record)
    files = sorted(app.state.app_config.query_audit_directory.glob("human-query-terminal-*.json"))
    assert len(files) == 1
    file_record = json.loads(files[0].read_text(encoding="utf-8"))
    assert file_record["correlationId"] == "corr-terminal-success"
    _assert_no_legacy_audit_aliases(file_record)


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
    record = app.state.human_query_terminal_audit_artifacts[-1]
    assert record["narration"] == CanonicalNarrationMetrics.empty().to_audit_payload()
    _assert_no_legacy_audit_aliases(record)


def test_terminal_audit_aggregates_multiple_canonical_formatter_records():
    first = CanonicalNarrationMetrics(
        selected_graph_count=1,
        answer_count=0,
        narration_clause_count=2,
        narration_clause_refs=("unit:a:overview", "transition:a-b"),
        narration_clause_kinds=("UNIT_INTRODUCTION", "PROVEN_CONTINUATION"),
        narration_semantic_operations=("PRESENT_UNIT", "CONTINUES_WITH_PROVEN_TARGET"),
        canonical_fact_count=3,
        duplicate_canonical_fact_count=1,
        missing_clause_count=1,
        formatter_provider_call_count=1,
        formatter_repair_call_count=1,
        formatter_segment_count=1,
        formatter_serialization_count=2,
        narration_planning_duration_ms=1.5,
        formatter_duration_ms=2.5,
        total_formatter_duration_ms=4.0,
    ).to_audit_payload()
    second = CanonicalNarrationMetrics(
        selected_graph_count=1,
        answer_count=0,
        narration_clause_count=1,
        narration_clause_refs=("unit:b:overview",),
        narration_clause_kinds=("UNIT_INTRODUCTION",),
        narration_semantic_operations=("PRESENT_UNIT",),
        canonical_fact_count=1,
        unknown_clause_count=1,
        formatter_provider_call_count=2,
        formatter_segment_count=1,
        formatter_serialization_count=1,
        narration_planning_duration_ms=0.5,
        formatter_duration_ms=1.0,
        total_formatter_duration_ms=1.5,
    ).to_audit_payload()

    record = knowledge_main._human_query_terminal_audit_record(
        KnowledgeQueryRequest(queryText="multi graph"),
        correlation_id="corr-aggregate",
        retrieval_plan=None,
        query_result=None,
        selected_graphs=(),
        interpretation_records=[],
        answer_records=[],
        pipeline_records=[first, second],
        terminal_status=502,
        terminal_error_code="FINAL_FORMATTER_FAILED",
        terminal_error_message="failed",
        terminal_stage="CANONICAL_TEXT_RENDERING",
        unexpected_exception_class=None,
        unexpected_exception_stage=None,
    )

    narration = record["narration"]
    assert narration["selectedGraphCount"] == 2
    assert narration["answerCount"] == 0
    assert narration["narrationClauseCount"] == 3
    assert narration["narrationClauseRefs"] == ["unit:a:overview", "transition:a-b", "unit:b:overview"]
    assert narration["missingClauseCount"] == 1
    assert narration["unknownClauseCount"] == 1
    assert narration["duplicateCanonicalFactCount"] == 1
    assert narration["formatterProviderCallCount"] == 3
    assert narration["formatterRepairCallCount"] == 1
    assert narration["formatterSegmentCount"] == 2
    assert narration["formatterSerializationCount"] == 3
    assert narration["narrationPlanningDurationMs"] == 2.0
    assert narration["formatterDurationMs"] == 3.5
    assert narration["totalFormatterDurationMs"] == 5.5
    assert narration["narrationContractMatched"] is False
    _assert_no_legacy_audit_aliases(record)


def test_query_endpoint_returns_one_answer_per_independent_graph(tmp_path):
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
    assert len(payload["answers"]) == 1
    assert [entry["root"]["label"] for entry in payload["answers"][0]["queryEntries"]] == ["A.start"]
    assert payload["diagnostics"] == []


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
