import asyncio
import json
import threading
import time
from contextlib import suppress
from types import SimpleNamespace

import httpx

import knowledge_service.main as knowledge_main
from knowledge_service.flow_builder import FlowBuilder, FlowGraphBundle, FlowGraphEdge, FlowGraphNode
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest, KnowledgeQueryResponse, KnowledgeQueryStatus
from knowledge_service.knowledge_query_service import KnowledgeQueryPolicy
from support import build_test_app, write_runtime_config
from knowledge_service.flow_explanations import FlowExplanationProviderResult
from semantic_test_support import seed_semantic_graph


class FakeFlowExplanationProvider:
    def __init__(self, delay_seconds=0.0):
        self.calls = []
        self.delay_seconds = delay_seconds

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "timeoutSeconds": timeout_seconds})
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        step_refs = [step["stepRef"] for step in llm_input["steps"]]
        transition_refs = [transition["transitionRef"] for transition in llm_input.get("transitions", [])]
        response = {
            "title": "A.start to B.work",
            "narrative": [
                {
                    "text": "A.start and B.work are explained from the packed flow facts.",
                    "stepRefs": step_refs,
                    "transitionRefs": transition_refs,
                    "boundaryRefs": [],
                },
                {
                    "text": "The ordered transition evidence shows how this flow moves between those steps.",
                    "stepRefs": step_refs,
                    "transitionRefs": transition_refs,
                    "boundaryRefs": [],
                },
            ],
            "steps": [
                {
                    "stepRef": step["stepRef"],
                    "order": step["order"],
                    "explanation": f"`{step['symbol']}` is part of this flow.",
                    "transitionRefs": [step["callToNext"]["transitionRef"]] if step.get("callToNext") else [],
                    "evidenceRefs": [item["ref"] for item in step.get("evidence", [])],
                }
                for step in llm_input["steps"]
            ],
            "transitions": [
                {
                    "transitionRef": transition["transitionRef"],
                    "explanation": f"`{transition['fromSymbol']}` leads to `{transition['toSymbol']}` in the ordered flow.",
                    "evidenceRefs": [item["ref"] for item in transition.get("evidence", [])],
                }
                for transition in llm_input.get("transitions", [])
            ],
            "boundaries": [],
        }
        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)


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


def test_query_flow_explanations_endpoint_returns_per_flow_text(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
    )
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    async def exercise():
        async with _async_client(app) as client:
            response = await _await_with_wakeup(client.post("/api/v1/knowledge/query/flow-explanations", json={"queryText": "A.start"}))
            return response.json()

    payload = asyncio.run(exercise())

    assert payload["flowPaths"]
    assert payload["flowExplanations"][0]["flowIndex"] == 1
    assert payload["flowExplanations"][0]["narrative"]
    assert [step["order"] for step in payload["flowExplanations"][0]["steps"]] == [1, 2]
    assert len(provider.calls) == 1


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
    )
    app.state.flow_explanation_provider = FakeFlowExplanationProvider(delay_seconds=0.4)

    async def exercise():
        async with _async_client(app) as client:
            slow = asyncio.create_task(
                _await_with_wakeup(client.post("/api/v1/knowledge/query/flow-explanations", json={"queryText": "A.start"}))
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
    assert response.json()["status"] == "OK"


def test_query_preparation_consumes_flow_explanation_deadline_before_llm_call(tmp_path, monkeypatch):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    app_config.analysis_request_timeout_seconds = 0.2
    app.state.app_config.analysis_request_timeout_seconds = 0.2
    flow_result = FlowBuilder().build(
        FlowGraphBundle(
            nodes=(
                FlowGraphNode("source-a", "graph-a", "graph-a", "a-start", "a-start", "CALLABLE", "A.start"),
                FlowGraphNode("source-a", "graph-a", "graph-a", "b-work", "b-work", "CALLABLE", "B.work"),
            ),
            edges=(FlowGraphEdge("source-a", "graph-a", "graph-a", "edge-a-b", "CALLS", "a-start", "b-work", "RESOLVED"),),
        ),
        [
            SimpleNamespace(
                sourceId="source-a",
                graphId="graph-a",
                nodeId="a-start",
                nodeKind="CALLABLE",
                score=1.0,
                matchReasons=("EXACT_NAME",),
            )
        ],
        set(),
        KnowledgeQueryPolicy(max_flow_paths=10),
    )
    query_response = KnowledgeQueryResponse(
        queryId="query-test",
        status=KnowledgeQueryStatus.OK,
        intent="FLOW_EXPLANATION",
        matchedSources=[{"sourceId": "source-a", "displayName": "Source A", "score": 1.0}],
        flowPaths=flow_result.flow_paths,
    )

    class SlowQueryService:
        def query_with_flow_units(self, body):
            time.sleep(0.05)
            return SimpleNamespace(response=query_response, flow_units=flow_result.flow_units)

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", lambda *_args, **_kwargs: SlowQueryService())
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    response = knowledge_main._knowledge_query_flow_explanations_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
    )

    assert response.status == KnowledgeQueryStatus.OK
    assert provider.calls
    assert 0 < provider.calls[0]["timeoutSeconds"] < 0.2


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
            step_refs = [step["stepRef"] for step in llm_input["steps"]]
            transition_refs = [transition["transitionRef"] for transition in llm_input.get("transitions", [])]
            response = {
                "title": "cancelled request flow",
                "narrative": [
                    {
                        "text": "This blocked explanation has enough detail to represent the first flow facts.",
                        "stepRefs": step_refs,
                        "transitionRefs": transition_refs,
                        "boundaryRefs": [],
                    },
                    {
                        "text": "The second grounded block exists only so validation can pass if not cancelled.",
                        "stepRefs": step_refs,
                        "transitionRefs": transition_refs,
                        "boundaryRefs": [],
                    },
                ],
                "steps": [
                    {
                        "stepRef": step["stepRef"],
                        "order": step["order"],
                        "explanation": f"`{step['symbol']}` is grounded.",
                        "transitionRefs": [step["callToNext"]["transitionRef"]] if step.get("callToNext") else [],
                        "evidenceRefs": [item["ref"] for item in step.get("evidence", [])],
                    }
                    for step in llm_input["steps"]
                ],
                "transitions": [
                    {
                        "transitionRef": transition["transitionRef"],
                        "explanation": f"`{transition['fromSymbol']}` reaches `{transition['toSymbol']}`.",
                        "evidenceRefs": [item["ref"] for item in transition.get("evidence", [])],
                    }
                    for transition in llm_input.get("transitions", [])
                ],
                "boundaries": [],
            }
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
            request_task = asyncio.create_task(client.post("/api/v1/knowledge/query/flow-explanations", json={"queryText": "B.work"}))
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
