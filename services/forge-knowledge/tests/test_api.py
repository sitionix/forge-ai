import asyncio
import time

from support import AsgiTestClient as TestClient

from support import build_test_app, write_runtime_config
from knowledge_service.flow_explanations import FlowExplanationProviderResult
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.main import _knowledge_query_flow_explanations_response, _run_in_thread
from semantic_test_support import seed_semantic_graph


class FakeFlowExplanationProvider:
    def __init__(self, delay_seconds=0.0):
        self.calls = []
        self.delay_seconds = delay_seconds

    def complete(self, llm_input, validation_errors=None):
        self.calls.append(dict(llm_input))
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
                    "explanation": f"{step['symbol']} is part of this flow.",
                    "transitionRefs": [step["callToNext"]["transitionRef"]] if step.get("callToNext") else [],
                    "evidenceRefs": [item["ref"] for item in step.get("evidence", [])],
                }
                for step in llm_input["steps"]
            ],
            "transitions": [
                {
                    "transitionRef": transition["transitionRef"],
                    "explanation": f"{transition['fromSymbol']} leads to {transition['toSymbol']} in the ordered flow.",
                    "evidenceRefs": [item["ref"] for item in transition.get("evidence", [])],
                }
                for transition in llm_input.get("transitions", [])
            ],
            "boundaries": [],
        }
        import json

        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)


def test_health_and_status_endpoint_reports_inventory_state(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP"}
        payload = client.get("/api/v1/knowledge/status").json()

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

    with TestClient(app) as client:
        payload = client.get("/api/v1/knowledge/sources").json()

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

    with TestClient(app) as client:
        payload = client.post("/api/v1/knowledge/query/flow-explanations", json={"queryText": "A.start"}).json()

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

    class RequestContext:
        pass

    async def health_request():
        messages = []
        received = False

        async def receive():
            nonlocal received
            if received:
                return {"type": "http.disconnect"}
            received = True
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            messages.append(message)

        await app(
            {
                "type": "http",
                "asgi": {"version": "3.0"},
                "http_version": "1.1",
                "method": "GET",
                "path": "/health",
                "raw_path": b"/health",
                "query_string": b"",
                "headers": [(b"accept", b"application/json")],
                "client": ("runtimeclient", 50000),
                "server": ("testserver", 80),
                "scheme": "http",
            },
            receive,
            send,
        )
        start = next(message for message in messages if message["type"] == "http.response.start")
        body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
        return start["status"], body

    async def exercise():
        request_context = RequestContext()
        request_context.app = app
        slow = asyncio.create_task(
            _run_in_thread(
                _knowledge_query_flow_explanations_response,
                request_context,
                KnowledgeQueryRequest(queryText="A.start"),
            )
        )
        await asyncio.sleep(0.05)
        started = time.monotonic()
        health = await health_request()
        elapsed = time.monotonic() - started
        response = await slow
        return health, elapsed, response

    health, elapsed, response = asyncio.run(exercise())

    assert health[0] == 200
    assert health[1] == b'{"status":"UP"}'
    assert elapsed < 0.25
    assert response.status == "OK"
