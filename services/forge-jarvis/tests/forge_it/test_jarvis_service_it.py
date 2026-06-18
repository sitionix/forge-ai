from __future__ import annotations

import pytest
from support import AsgiTestClient as TestClient
from pydantic import ValidationError

from jarvis_agent.config import load_forge_settings
from support import (
    FakeKnowledgeClient,
    FakeModelClient,
    build_test_app,
    knowledge_bad_response,
    knowledge_bundle,
    knowledge_unavailable,
    ollama_bad_response,
    ollama_unavailable,
    write_runtime_config,
)

pytestmark = pytest.mark.forge_it


PUBLIC_ENDPOINTS = {
    ("GET", "/health"),
    ("GET", "/api/v1/jarvis/status"),
    ("GET", "/api/v1/jarvis/actions"),
    ("POST", "/api/v1/jarvis/command"),
    ("POST", "/api/v1/jarvis/chat"),
}


def public_routes(app):
    routes = set()
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", set()) or set()
        if not path or path in {"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"}:
            continue
        for method in methods:
            if method not in {"HEAD", "OPTIONS"}:
                routes.add((method, path))
    return routes


def test_route_inventory_matches_manifest(tmp_path):
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    assert public_routes(app) == PUBLIC_ENDPOINTS


def test_startup_from_root_config_and_invalid_config_failure(tmp_path):
    config_file = write_runtime_config(tmp_path)
    app, *_ = build_test_app(config_file)

    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP"}

    config_file.write_text(config_file.read_text(encoding="utf-8").replace("port: 7071", "port: 70000"), encoding="utf-8")
    with pytest.raises(ValidationError):
        load_forge_settings(
            config_file=config_file,
            environ={
                "FORGE_AI_HOME": str(tmp_path),
                "FORGE_CONFIG_DIR": str(tmp_path / "config"),
                "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
                "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
            },
        )


def test_status_actions_command_and_chat_success_paths(tmp_path):
    app, _, _, _, executor, model, knowledge = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        status = client.get("/api/v1/jarvis/status").json()
        actions = client.get("/api/v1/jarvis/actions").json()
        command = client.post("/api/v1/jarvis/command", json={"text": "check ollama"}).json()
        chat = client.post("/api/v1/jarvis/chat", json={"message": "explain JarvisGateway"}).json()

    assert status["ollama"]["status"] == "UP"
    assert actions["actions"] and "command" not in str(actions)
    assert command["execution"]["executed"] is True
    assert executor.invocations == [("ollama_status", "health", "check ollama")]
    assert knowledge.calls == [("explain JarvisGateway", 12000)]
    assert model.prompts and "public interface JarvisGateway" in model.prompts[0]
    assert chat["diagnostics"] == []


def test_status_when_model_provider_is_down(tmp_path):
    model = FakeModelClient(health_error=ollama_unavailable())
    app, *_ = build_test_app(write_runtime_config(tmp_path), model=model)

    with TestClient(app) as client:
        status = client.get("/api/v1/jarvis/status").json()

    assert status["status"] == "UP"
    assert status["ollama"]["status"] == "DOWN"


def test_command_validation_and_failure_matrix(tmp_path):
    with TestClient(build_test_app(write_runtime_config(tmp_path))[0]) as client:
        assert client.post("/api/v1/jarvis/command", json={"text": "   "}).json()["code"] == "INVALID_COMMAND"

    invalid_model = FakeModelClient(intent_response="not json")
    with TestClient(build_test_app(write_runtime_config(tmp_path), model=invalid_model)[0]) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check"})
        assert response.status_code == 422
        assert response.json()["code"] == "INVALID_MODEL_RESPONSE"

    unsupported = FakeModelClient(intent_response='{"action":"unknown","target":"missing","arguments":{}}')
    with TestClient(build_test_app(write_runtime_config(tmp_path), model=unsupported)[0]) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check"})
        assert response.status_code == 403
        assert response.json()["code"] == "UNSUPPORTED_ACTION"

    unavailable = FakeModelClient(classify_error=ollama_unavailable())
    with TestClient(build_test_app(write_runtime_config(tmp_path), model=unavailable)[0]) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check"})
        assert response.status_code == 503
        assert response.json()["code"] == "OLLAMA_UNAVAILABLE"

    malformed = FakeModelClient(classify_error=ollama_bad_response())
    with TestClient(build_test_app(write_runtime_config(tmp_path), model=malformed)[0]) as client:
        response = client.post("/api/v1/jarvis/command", json={"text": "check"})
        assert response.status_code == 422
        assert response.json()["code"] == "INVALID_MODEL_RESPONSE"


def test_chat_failure_matrix_and_diagnostics(tmp_path):
    with TestClient(build_test_app(write_runtime_config(tmp_path))[0]) as client:
        blank = client.post("/api/v1/jarvis/chat", json={"message": "   "})
        assert blank.status_code == 400
        assert blank.json()["code"] == "INVALID_CHAT_MESSAGE"

    empty = FakeKnowledgeClient(bundle=knowledge_bundle(context=[], diagnostics=[{"code": "SEARCH_EMPTY", "message": "No matches"}]))
    with TestClient(build_test_app(write_runtime_config(tmp_path), knowledge=empty)[0]) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "missing"})
        body = response.json()
        assert body["usedContext"] == []
        assert {item["code"] for item in body["diagnostics"]} == {"SEARCH_EMPTY", "CONTEXT_EMPTY"}

    with TestClient(build_test_app(write_runtime_config(tmp_path), knowledge=FakeKnowledgeClient(error=knowledge_unavailable()))[0]) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain"})
        assert response.status_code == 503
        assert response.json()["code"] == "KNOWLEDGE_UNAVAILABLE"

    with TestClient(build_test_app(write_runtime_config(tmp_path), knowledge=FakeKnowledgeClient(error=knowledge_bad_response()))[0]) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain"})
        assert response.status_code == 502
        assert response.json()["code"] == "KNOWLEDGE_BAD_RESPONSE"

    with TestClient(build_test_app(write_runtime_config(tmp_path), model=FakeModelClient(generate_error=ollama_unavailable()))[0]) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain"})
        assert response.status_code == 503
        assert response.json()["code"] == "OLLAMA_UNAVAILABLE"

    with TestClient(build_test_app(write_runtime_config(tmp_path), model=FakeModelClient(generate_error=ollama_bad_response()))[0]) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain"})
        assert response.status_code == 502
        assert response.json()["code"] == "OLLAMA_BAD_RESPONSE"


def test_chat_surfaces_knowledge_analysis_diagnostics_when_context_is_not_ready(tmp_path):
    knowledge = FakeKnowledgeClient(
        bundle=knowledge_bundle(
            context=[],
            diagnostics=[
                {
                    "code": "ANALYSIS_NOT_READY",
                    "message": "Knowledge analysis has not produced current context yet.",
                    "sourceId": "forge-ai",
                }
            ],
        )
    )
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        response = client.post("/api/v1/jarvis/chat", json={"message": "explain analyzed files"})

    body = response.json()
    assert response.status_code == 200
    assert body["usedContext"] == []
    assert "No relevant local Knowledge context was found" in body["answer"]
    assert {item["code"] for item in body["diagnostics"]} == {"ANALYSIS_NOT_READY", "CONTEXT_EMPTY"}
    assert knowledge.calls == [("explain analyzed files", 12000)]
