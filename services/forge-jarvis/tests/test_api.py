from support import AsgiTestClient as TestClient
import pytest

from jarvis_agent.knowledge_client import KnowledgeClient
from support import (
    FakeKnowledgeClient,
    FakeModelClient,
    RecordingActionExecutor,
    build_test_app,
    knowledge_bad_response,
    knowledge_query_bundle,
    knowledge_unavailable,
    ollama_unavailable,
    query_payload,
    write_runtime_config,
)


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
    assert body["model"]["defaultModel"] == "qwen2.5-coder:7b"
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


def test_query_calls_knowledge_gateway(tmp_path) -> None:
    knowledge = FakeKnowledgeClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload(" explain JarvisGateway "))

    assert response.status_code == 200
    assert knowledge.calls == [query_payload("explain JarvisGateway")]
    assert response.json()["matchedNodes"][0]["sourceId"] == "forge-ai"


def test_query_does_not_call_ollama_generation(tmp_path) -> None:
    model = FakeModelClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    assert response.status_code == 200
    assert model.prompts == []
    assert model.health_calls == 0


def test_query_response_preserves_factual_bundle(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("explain JarvisGateway"))

    body = response.json()
    assert body["status"] == "OK"
    assert body["matchedSources"][0]["sourceId"] == "forge-ai"
    assert body["matchedNodes"][0]["label"] == "JarvisGateway"
    assert body["flowPaths"][0]["flowId"] == "flow-1"
    assert body["flowPaths"][0]["nodeIds"] == ["node-jarvis-gateway"]
    assert body["flowPaths"][0]["edgeIds"] == []
    assert body["nodes"][0]["sourceId"] == "forge-ai"
    assert "answer" not in body


def test_query_no_candidates_preserves_controlled_response(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(
        bundle=knowledge_query_bundle(
            status="NO_CANDIDATES", matched_nodes=[], flow_paths=[], nodes=[], diagnostics=[{"code": "NO_GRAPH_CANDIDATES", "message": "No graph candidates"}]
        )
    )
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/query", json=query_payload("unknown"))

    body = response.json()
    assert body["status"] == "NO_CANDIDATES"
    assert body["matchedNodes"] == []
    assert body["flowPaths"] == []
    assert any(diagnostic["code"] == "NO_GRAPH_CANDIDATES" for diagnostic in body["diagnostics"])


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

    assert response.json()["status"] == "OK"
    assert executor.invocations == []


def test_non_localhost_knowledge_base_url_rejected() -> None:
    with pytest.raises(ValueError):
        KnowledgeClient("http://example.com:7081", 120)
