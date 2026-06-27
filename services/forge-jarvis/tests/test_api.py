from support import AsgiTestClient as TestClient
import pytest

from jarvis_agent.knowledge_client import KnowledgeClient
from support import (
    FakeKnowledgeClient,
    FakeModelClient,
    RecordingActionExecutor,
    build_test_app,
    knowledge_bad_response,
    knowledge_bundle,
    knowledge_unavailable,
    ollama_bad_response,
    ollama_unavailable,
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


def test_chat_rejects_blank_message(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "   "})

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_CHAT_MESSAGE"


def test_chat_calls_knowledge_context(tmp_path) -> None:
    knowledge = FakeKnowledgeClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": " explain JarvisGateway ", "maxContextChars": 12000})

    assert response.status_code == 200
    assert knowledge.calls == [("explain JarvisGateway", 12000)]
    assert response.json()["answer"] == "Answer from context"


def test_chat_calls_ollama_with_retrieved_context(tmp_path) -> None:
    model = FakeModelClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    assert "Knowledge context:" in model.prompts[0]
    assert "[1] forge-ai/application/src/main/java/JarvisGateway.java lines 1-40" in model.prompts[0]
    assert "public interface JarvisGateway {}" in model.prompts[0]


def test_chat_response_includes_answer_and_used_context(tmp_path) -> None:
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    body = response.json()
    assert body["answer"] == "Answer from context"
    assert body["usedContext"][0]["sourceId"] == "forge-ai"
    assert body["usedContext"][0]["relativePath"] == "application/src/main/java/JarvisGateway.java"
    assert body["usedContext"][0]["content"] is None


def test_chat_empty_context_returns_clear_answer_and_diagnostic(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(bundle=knowledge_bundle(context=[]))
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "unknown"})

    body = response.json()
    assert "No relevant local Knowledge context was found" in body["answer"]
    assert body["usedContext"] == []
    assert any(diagnostic["code"] == "CONTEXT_EMPTY" for diagnostic in body["diagnostics"])


def test_chat_maps_knowledge_unavailable_to_controlled_error(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(error=knowledge_unavailable())
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    assert response.status_code == 503
    assert response.json()["code"] == "KNOWLEDGE_UNAVAILABLE"


def test_chat_maps_malformed_knowledge_response(tmp_path) -> None:
    knowledge = FakeKnowledgeClient(error=knowledge_bad_response())
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    assert response.status_code == 502
    assert response.json()["code"] == "KNOWLEDGE_BAD_RESPONSE"


def test_chat_maps_ollama_unavailable_to_controlled_error(tmp_path) -> None:
    model = FakeModelClient(generate_error=ollama_unavailable())
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    assert response.status_code == 503
    assert response.json()["code"] == "OLLAMA_UNAVAILABLE"


def test_chat_maps_malformed_model_response(tmp_path) -> None:
    model = FakeModelClient(generate_error=ollama_bad_response())
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    assert response.status_code == 502
    assert response.json()["code"] == "OLLAMA_BAD_RESPONSE"


def test_chat_does_not_execute_actions(tmp_path) -> None:
    app, _, _, _, executor, _, _ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"})

    assert response.json()["answer"] == "Answer from context"
    assert executor.invocations == []


def test_non_localhost_knowledge_base_url_rejected() -> None:
    with pytest.raises(ValueError):
        KnowledgeClient("http://example.com:7081", 120)
