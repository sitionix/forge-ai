import asyncio

import httpx
from support import AsgiTestClient as TestClient
import pytest

from jarvis_agent.knowledge_client import KnowledgeClient, KnowledgeUpstreamResponseError
from support import (
    FakeKnowledgeClient,
    FakeModelClient,
    RecordingActionExecutor,
    build_test_app,
    flow_query_payload,
    human_answer_bundle,
    knowledge_bad_response,
    knowledge_unavailable,
    normalized_query_payload,
    ollama_unavailable,
    query_payload,
    write_runtime_config,
)


GRAPH_BUNDLE_FIELDS = {
    "status",
    "queryId",
    "intent",
    "matchedSources",
    "matchedNodes",
    "flows",
    "flowExplanations",
    "coverage",
    "nodes",
    "transitions",
    "boundaries",
}


def assert_compact_human_response(body, *, answer_language="uk", entrypoint="JarvisGateway") -> None:
    assert set(body) == {"answerLanguage", "answers", "diagnostics"}
    assert body["answerLanguage"] == answer_language
    assert body["answers"] == [
        {
            "source": "forge-ai",
            "entrypoint": entrypoint,
            "text": "JarvisGateway handles the request.",
        }
    ]
    assert body["diagnostics"] == []
    assert not (GRAPH_BUNDLE_FIELDS & set(body))


def test_health_endpoint(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.get("/health")

    assert response.json() == {"status": "UP"}


def test_actions_endpoint_hides_commands(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        body = client.get("/api/v1/jarvis/actions").json()

    assert "actions" in body
    assert any(action["action"] == "ollama_status" for action in body["actions"])
    assert "command" not in str(body)


def test_status_endpoint_returns_runtime_status(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        body = client.get("/api/v1/jarvis/status").json()

    assert body["status"] == "UP"
    assert body["model"]["defaultModel"] == "qwen2.5-coder:14b"
    assert body["model"]["contextTokens"] == 32768
    assert body["ollama"]["status"] == "UNKNOWN"
    assert body["actions"]["count"] == 2


def test_status_endpoint_does_not_call_ollama(tmp_path) -> None:
    model = FakeModelClient(health_error=ollama_unavailable())
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        body = client.get("/api/v1/jarvis/status").json()

    assert body["status"] == "UP"
    assert body["ollama"]["status"] == "UNKNOWN"
    assert model.health_calls == 0


def test_command_returns_controlled_error_when_ollama_unavailable(tmp_path) -> None:
    model = FakeModelClient(classify_error=ollama_unavailable())
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check ollama"})

    assert response.status_code == 503
    assert response.json()["code"] == "OLLAMA_UNAVAILABLE"


def test_command_rejects_empty_input(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "   "})

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_COMMAND"


def test_command_rejects_invalid_model_json(tmp_path) -> None:
    model = FakeModelClient(intent_response="not json")
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check ollama"})

    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_MODEL_RESPONSE"


def test_command_executes_allowlisted_intent_with_safe_executor(tmp_path) -> None:
    app, _, _, _, executor, _, _ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check ollama"})

    assert response.status_code == 200
    assert response.json()["execution"]["executed"] is True
    assert executor.invocations == [("ollama_status", "health", "check ollama")]


def test_command_rejects_unsupported_action(tmp_path) -> None:
    model = FakeModelClient(intent_response='{"action":"unsupported","target":null,"arguments":{}}')
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "do something"})

    assert response.status_code == 403
    assert response.json()["code"] == "UNSUPPORTED_ACTION"


def test_command_maps_action_execution_failure(tmp_path) -> None:
    config_file = write_runtime_config(tmp_path)
    _, _, app_config, _, _, _, _ = build_test_app(config_file)
    from jarvis_agent.action_registry import ActionRegistry

    registry = ActionRegistry.from_yaml(app_config.allowed_actions_path)
    executor = RecordingActionExecutor(registry, fail=True)
    app, *_ = build_test_app(config_file, executor=executor)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check ollama"})

    assert response.status_code == 500
    assert response.json()["code"] == "ACTION_EXECUTION_FAILED"


def test_query_rejects_blank_question(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("   "))

    assert response.status_code == 422


def test_query_rejects_old_request_shape(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))
    old_payload = {"qu" + "ery": "explain JarvisGateway", "intent": "AU" + "TO"}

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=old_payload)

    assert response.status_code == 422


@pytest.mark.parametrize(
    "payload",
    [
        {"queryText": "explain", "intent": "AUTOMATIC"},
        {"queryText": "explain", "intent": "COMPONENT_USAGE"},
        {"queryText": "explain", "includeTests": "false"},
        {"queryText": "explain", "maxFlows": "10"},
        {"queryText": "explain", "maxFlows": 0},
        {"queryText": "explain", "maxFlows": 999},
    ],
)
def test_query_rejects_invalid_optional_controls(tmp_path, payload) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=payload)

    assert response.status_code == 422


@pytest.mark.parametrize(
    ("payload", "expected_payload"),
    [
        (query_payload(" explain JarvisGateway "), normalized_query_payload("explain JarvisGateway")),
        (
            flow_query_payload(" explain JarvisGateway "),
            normalized_query_payload("explain JarvisGateway", intent="FLOW_EXPLANATION", include_tests=False, max_flows=10),
        ),
    ],
)
def test_query_intents_call_knowledge_human_endpoint_once(tmp_path, payload, expected_payload) -> None:
    knowledge = FakeKnowledgeClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=payload)

    assert response.status_code == 200
    assert knowledge.calls == [expected_payload]
    assert knowledge.paths == ["/api/v1/knowledge/query"]
    assert_compact_human_response(response.json())


@pytest.mark.parametrize("answer_language", ["de", "fr"])
def test_query_accepts_explicit_dynamic_language_and_forwards_unchanged(tmp_path, answer_language) -> None:
    knowledge = FakeKnowledgeClient(bundle=human_answer_bundle(answer_language=answer_language))
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/jarvis/query",
            json=flow_query_payload("explain JarvisGateway", answer_language=answer_language),
        )

    assert response.status_code == 200
    assert response.json()["answerLanguage"] == answer_language
    assert knowledge.calls == [
        normalized_query_payload(
            "explain JarvisGateway",
            intent="FLOW_EXPLANATION",
            answer_language=answer_language,
            include_tests=False,
            max_flows=10,
        )
    ]
    assert knowledge.paths == ["/api/v1/knowledge/query"]


def test_query_forwards_explicit_ru_and_preserves_knowledge_422(tmp_path) -> None:
    body = {
        "code": "RESPONSE_LANGUAGE_NOT_ALLOWED",
        "message": "The requested response language is not allowed.",
    }
    knowledge = FakeKnowledgeClient(error=KnowledgeUpstreamResponseError(422, body))
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post(
            "/api/v1/jarvis/query",
            json=flow_query_payload("explain JarvisGateway", answer_language="ru"),
        )

    assert response.status_code == 422
    assert response.json() == body
    assert knowledge.calls == [
        normalized_query_payload(
            "explain JarvisGateway",
            intent="FLOW_EXPLANATION",
            answer_language="ru",
            include_tests=False,
            max_flows=10,
        )
    ]
    assert knowledge.paths == ["/api/v1/knowledge/query"]


def test_human_query_generation_failure_preserves_upstream_error(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(
        error=KnowledgeUpstreamResponseError(
            502,
            {
                "code": "HUMAN_ANSWER_GENERATION_FAILED",
                "message": "The local model could not produce any grounded flow answers.",
            },
        )
    )
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=flow_query_payload("JarvisGateway"))

    assert response.status_code == 502
    assert response.json() == {
        "code": "HUMAN_ANSWER_GENERATION_FAILED",
        "message": "The local model could not produce any grounded flow answers.",
    }
    assert knowledge.calls == [
        normalized_query_payload("JarvisGateway", intent="FLOW_EXPLANATION", include_tests=False, max_flows=10)
    ]
    assert knowledge.paths == ["/api/v1/knowledge/query"]


def test_human_query_response_preserves_multiple_answers_and_diagnostics(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(
        bundle=human_answer_bundle(
            answers=[
                {"source": "service-a", "entrypoint": "ControllerA.create", "text": "A creates the site."},
                {"source": "service-b", "entrypoint": "ListenerB.handle", "text": "B handles the event."},
            ],
            diagnostics=[
                {
                    "code": "HUMAN_FLOW_ANSWER_GENERATION_FAILED",
                    "message": "The local model could not explain one selected flow.",
                    "severity": "WARN",
                    "sourceId": "service-c",
                    "metadata": {"entrypoint": "ListenerC.handle"},
                }
            ],
        )
    )
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=flow_query_payload("site flow"))

    assert response.status_code == 200
    assert response.json() == {
        "answerLanguage": "uk",
        "answers": [
            {"source": "service-a", "entrypoint": "ControllerA.create", "text": "A creates the site."},
            {"source": "service-b", "entrypoint": "ListenerB.handle", "text": "B handles the event."},
        ],
        "diagnostics": [
            {
                "code": "HUMAN_FLOW_ANSWER_GENERATION_FAILED",
                "message": "The local model could not explain one selected flow.",
                "severity": "WARN",
                "sourceId": "service-c",
                "metadata": {"entrypoint": "ListenerC.handle"},
            }
        ],
    }
    assert knowledge.paths == ["/api/v1/knowledge/query"]


def test_human_query_malformed_knowledge_response_maps_to_controlled_error(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(bundle={"answerLanguage": "uk", "answers": [{"source": "forge-ai"}], "diagnostics": []})
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=flow_query_payload("JarvisGateway"))

    assert response.status_code == 502
    assert response.json()["code"] == "KNOWLEDGE_BAD_RESPONSE"
    assert knowledge.calls == [
        normalized_query_payload("JarvisGateway", intent="FLOW_EXPLANATION", include_tests=False, max_flows=10)
    ]
    assert knowledge.paths == ["/api/v1/knowledge/query"]


def test_query_does_not_call_ollama_generation(tmp_path) -> None:
    model = FakeModelClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    assert response.status_code == 200
    assert model.prompts == []
    assert model.health_calls == 0


def test_query_response_preserves_human_contract(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    assert response.status_code == 200
    assert_compact_human_response(response.json())


@pytest.mark.parametrize(
    ("status_code", "body"),
    [
        (404, {"code": "NO_GROUNDED_GRAPH_CANDIDATES", "message": "No grounded graph candidates were found."}),
        (
            503,
            {
                "code": "HUMAN_ANSWER_CONTEXT_BUDGET_EXCEEDED",
                "message": "The complete grounded flow exceeds the available model context.",
                "correlationId": "corr-knowledge-budget",
            },
        ),
        (502, {"code": "HUMAN_ANSWER_GENERATION_FAILED", "message": "The local model could not produce any grounded flow answers."}),
        (504, {"code": "HUMAN_ANSWER_GENERATION_TIMEOUT", "message": "The local model timed out while generating grounded flow answers."}),
    ],
)
def test_query_preserves_controlled_knowledge_error(tmp_path, status_code, body) -> None:
    knowledge = FakeKnowledgeClient(
        error=KnowledgeUpstreamResponseError(status_code, body)
    )
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("unknown"))

    assert response.status_code == status_code
    assert response.json() == body
    assert knowledge.calls == [normalized_query_payload("unknown")]
    assert knowledge.paths == ["/api/v1/knowledge/query"]


def test_query_maps_knowledge_unavailable_to_controlled_error(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(error=knowledge_unavailable())
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    assert response.status_code == 503
    assert response.json()["code"] == "KNOWLEDGE_UNAVAILABLE"


def test_query_maps_malformed_knowledge_response(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(error=knowledge_bad_response())
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    assert response.status_code == 502
    assert response.json()["code"] == "KNOWLEDGE_BAD_RESPONSE"


def test_query_does_not_execute_actions(tmp_path) -> None:
    app, _, _, _, executor, _, _ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    assert response.status_code == 200
    assert_compact_human_response(response.json())
    assert executor.invocations == []


def test_non_localhost_knowledge_base_url_rejected() -> None:
    with pytest.raises(ValueError):
        KnowledgeClient("http://example.com:7081", 120)


def test_query_client_uses_human_timeout_beyond_normal_knowledge_boundary() -> None:
    async def exercise():
        calls = []

        async def handler(request: httpx.Request) -> httpx.Response:
            calls.append(request.url.path)
            read_timeout = request.extensions.get("timeout", {}).get("read", 0)
            if read_timeout <= 0.12:
                raise httpx.ReadTimeout("old timeout boundary", request=request)
            await asyncio.sleep(0.001)
            return httpx.Response(
                200,
                json=human_answer_bundle(text="JarvisGateway handles the request."),
            )

        http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler), timeout=httpx.Timeout(0.1))
        client = KnowledgeClient(
            "http://127.0.0.1:7081",
            timeout_seconds=0.1,
            human_query_timeout_seconds=0.15,
            http_client=http_client,
        )
        try:
            result = await client.query(flow_query_payload("JarvisGateway"))
        finally:
            await client.aclose()
        return result, calls

    result, calls = asyncio.run(exercise())

    assert calls == ["/api/v1/knowledge/query"]
    assert result["answers"][0]["text"] == "JarvisGateway handles the request."
    assert "status" not in result
    assert "flows" not in result
