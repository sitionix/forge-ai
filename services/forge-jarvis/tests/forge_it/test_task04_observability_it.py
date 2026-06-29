from __future__ import annotations

import pytest
from support import AsgiTestClient as TestClient
from support import FakeKnowledgeClient, FakeModelClient, build_test_app, write_runtime_config

from jarvis_agent.knowledge_client import KnowledgeUnavailableError
from jarvis_agent.ollama_client import OllamaUnavailableError

pytestmark = pytest.mark.forge_it


def test_it_obs_04_jarvis_observability_and_redaction(tmp_path):
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        status = client.get("/api/v1/jarvis/status")
        actions = client.get("/api/v1/jarvis/actions")
        command = client.post("/api/v1/jarvis/command", json={"text": "check ollama"})
        query = client.post("/api/v1/jarvis/query", json={"query": "explain JarvisGateway"})

    for response in (status, actions, command, query):
        assert response.headers["x-correlation-id"]
        assert "route;dur=" in response.headers["server-timing"]
        assert "knowledge;dur=" in response.headers["server-timing"]
        assert "ollama;dur=" in response.headers["server-timing"]
        assert "action;dur=" in response.headers["server-timing"]
        assert "x-response-bytes" in response.headers


def test_it_obs_05_jarvis_correlation_id_is_sanitized_and_forwarded(tmp_path):
    knowledge = FakeKnowledgeClient()
    app, *_ = build_test_app(write_runtime_config(tmp_path), knowledge=knowledge)

    with TestClient(app) as client:
        unsafe = client.post("/api/v1/jarvis/query", json={"query": "explain"}, headers={"X-Correlation-Id": "unsafe value"})
        safe = client.post("/api/v1/jarvis/query", json={"query": "explain"}, headers={"X-Correlation-Id": "corr-safe"})

    assert unsafe.headers["x-correlation-id"] != "unsafe value"
    assert safe.headers["x-correlation-id"] == "corr-safe"


def test_it_obs_06_jarvis_public_error_redaction(tmp_path):
    app, *_ = build_test_app(
        write_runtime_config(tmp_path),
        model=FakeModelClient(classify_error=OllamaUnavailableError("http://localhost:11434 prompt secret")),
        knowledge=FakeKnowledgeClient(error=KnowledgeUnavailableError("http://127.0.0.1:7081 source content")),
    )

    with TestClient(app) as client:
        command = client.post("/api/v1/jarvis/command", json={"text": "check ollama"})
        query = client.post("/api/v1/jarvis/query", json={"query": "explain"})
        invalid_command = client.post("/api/v1/jarvis/command", json={"text": "   "})

    assert command.status_code == 503
    assert query.status_code == 503
    assert invalid_command.status_code == 400

    for response in (command, query, invalid_command):
        text = response.body.decode("utf-8")
        assert "correlationId" in text
        assert "http://localhost" not in text
        assert "http://127.0.0.1" not in text
        assert "prompt secret" not in text
        assert "source content" not in text
        assert "Traceback" not in text
