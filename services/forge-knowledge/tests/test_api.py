import asyncio
import json
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
from knowledge_service.flow_explanations import FLOW_EXPLANATION_LIMIT_REACHED, FlowExplanationProviderResult
from semantic_test_support import seed_semantic_graph


class FakeFlowExplanationProvider:
    def __init__(self, delay_seconds=0.0):
        self.calls = []
        self.delay_seconds = delay_seconds

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append({"llmInput": dict(llm_input), "timeoutSeconds": timeout_seconds})
        if self.delay_seconds:
            time.sleep(self.delay_seconds)
        node_refs = [step["nodeRef"] for step in llm_input["steps"]]
        transition_refs = [transition["transitionRef"] for transition in llm_input.get("transitions", [])]
        response = {
            "title": "A.start to B.work",
            "narrative": [
                {
                    "text": "A.start and B.work are explained from the packed graph facts, including the node references and the CALLS transition that connects the two symbols.",
                    "nodeRefs": node_refs,
                    "transitionRefs": transition_refs,
                    "boundaryRefs": [],
                },
                {
                    "text": "The graph transition evidence identifies the caller node and downstream callee node without treating sibling calls as an ordered path.",
                    "nodeRefs": node_refs,
                    "transitionRefs": transition_refs,
                    "boundaryRefs": [],
                },
            ],
            "steps": [
                {
                    "nodeRef": step["nodeRef"],
                    "explanation": f"`{step['symbol']}` is part of this flow.",
                    "transitionRefs": [item["transitionRef"] for item in llm_input.get("transitions", []) if item["fromNodeRef"] == step["nodeRef"]],
                    "evidenceRefs": [item["ref"] for item in step.get("evidence", [])],
                }
                for step in llm_input["steps"]
            ],
            "transitions": [
                {
                    "transitionRef": transition["transitionRef"],
                    "explanation": f"`{transition['fromSymbol']}` has a CALLS transition to `{transition['toSymbol']}`.",
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

    assert payload["flows"]
    assert payload["flowExplanations"][0]["flowIndex"] == 1
    assert payload["flowExplanations"][0]["narrative"]
    assert [step["nodeRef"] for step in payload["flowExplanations"][0]["steps"]] == ["n1", "n2"]
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
        def query_with_flows(self, body):
            time.sleep(0.05)
            return SimpleNamespace(response=query_response, flows=flow_result.flows)

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", lambda *_args, **_kwargs: SlowQueryService())
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    deadline_at = time.monotonic() + 0.2
    time.sleep(0.03)

    response = knowledge_main._knowledge_query_flow_explanations_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
        deadline_at=deadline_at,
    )

    assert response.status == KnowledgeQueryStatus.OK
    assert provider.calls
    assert 0 < provider.calls[0]["timeoutSeconds"] < 0.17


def test_expired_flow_explanation_deadline_before_worker_returns_controlled_response(tmp_path, monkeypatch):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    def fail_if_called(*_args, **_kwargs):
        raise AssertionError("query service should not be built after deadline expiry")

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", fail_if_called)
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    response = knowledge_main._knowledge_query_flow_explanations_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
        deadline_at=time.monotonic() - 0.001,
    )

    assert response.status == KnowledgeQueryStatus.OK
    assert response.diagnostics
    assert response.diagnostics[0].code == FLOW_EXPLANATION_LIMIT_REACHED
    assert response.diagnostics[0].metadata["stage"] == "BEFORE_QUERY"
    assert provider.calls == []


def test_expired_tool_context_deadline_before_worker_returns_controlled_response(tmp_path, monkeypatch):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    def fail_if_called(*_args, **_kwargs):
        raise AssertionError("query service should not be built after deadline expiry")

    monkeypatch.setattr(knowledge_main, "build_knowledge_query_service", fail_if_called)

    response = knowledge_main._knowledge_query_tool_context_response(
        SimpleNamespace(app=app),
        KnowledgeQueryRequest(queryText="A.start"),
        deadline_at=time.monotonic() - 0.001,
    )

    assert response.status == KnowledgeQueryStatus.OK
    assert response.diagnostics
    assert response.diagnostics[0].code == FLOW_EXPLANATION_LIMIT_REACHED
    assert response.diagnostics[0].metadata["stage"] == "BEFORE_QUERY"


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
            node_refs = [step["nodeRef"] for step in llm_input["steps"]]
            transition_refs = [transition["transitionRef"] for transition in llm_input.get("transitions", [])]
            response = {
                "title": "cancelled request flow",
                "narrative": [
                    {
                        "text": "This blocked explanation has enough detail to represent the first flow facts, the participating node references, and the CALLS transitions in the graph.",
                        "nodeRefs": node_refs,
                        "transitionRefs": transition_refs,
                        "boundaryRefs": [],
                    },
                    {
                        "text": "The second grounded block describes the same graph slice with enough factual words for validation while avoiding any invented execution order.",
                        "nodeRefs": node_refs,
                        "transitionRefs": transition_refs,
                        "boundaryRefs": [],
                    },
                ],
                "steps": [
                    {
                        "nodeRef": step["nodeRef"],
                        "explanation": f"`{step['symbol']}` is grounded.",
                        "transitionRefs": [item["transitionRef"] for item in llm_input.get("transitions", []) if item["fromNodeRef"] == step["nodeRef"]],
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
