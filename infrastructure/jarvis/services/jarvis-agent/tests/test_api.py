import asyncio
import json

from jarvis_agent.intent_schema import CommandRequest
from jarvis_agent.main import actions, command, health, status
from jarvis_agent.ollama_client import OllamaUnavailableError


def test_health_endpoint() -> None:
    response = asyncio.run(health())

    assert response == {"status": "UP"}


def test_actions_endpoint_hides_commands() -> None:
    body = asyncio.run(actions())

    assert "actions" in body
    assert any(action["action"] == "ollama_status" for action in body["actions"])
    assert all(action["action"] != "open_url" for action in body["actions"])
    assert "command" not in str(body)


def test_status_endpoint_returns_runtime_status(monkeypatch) -> None:
    async def reachable():
        return None

    monkeypatch.setattr("jarvis_agent.main.ollama.health", reachable)

    body = asyncio.run(status())

    assert body["status"] == "UP"
    assert body["model"]["defaultModel"] == "qwen2.5-coder:7b"
    assert body["ollama"]["status"] == "UP"
    assert body["actions"]["count"] == 2


def test_status_endpoint_marks_ollama_down(monkeypatch) -> None:
    async def unavailable():
        raise OllamaUnavailableError("unavailable")

    monkeypatch.setattr("jarvis_agent.main.ollama.health", unavailable)

    body = asyncio.run(status())

    assert body["status"] == "UP"
    assert body["ollama"]["status"] == "DOWN"


def test_status_endpoint_does_not_execute_actions(monkeypatch) -> None:
    async def reachable():
        return None

    def fail_execute(*args, **kwargs):
        raise AssertionError("status endpoint must not execute actions")

    monkeypatch.setattr("jarvis_agent.main.ollama.health", reachable)
    monkeypatch.setattr("jarvis_agent.main.executor.execute", fail_execute)

    body = asyncio.run(status())

    assert body["status"] == "UP"


def test_command_returns_controlled_error_when_ollama_unavailable(monkeypatch) -> None:
    async def unavailable(*args, **kwargs):
        raise OllamaUnavailableError("unavailable")

    monkeypatch.setattr("jarvis_agent.main.ollama.classify_intent", unavailable)

    response = asyncio.run(command(CommandRequest(text="перевір ollama")))

    assert response.status_code == 503
    assert json.loads(response.body)["code"] == "OLLAMA_UNAVAILABLE"


def test_command_rejects_empty_input() -> None:
    response = asyncio.run(command(CommandRequest(text="   ")))

    assert response.status_code == 400
    assert json.loads(response.body)["code"] == "INVALID_COMMAND"


def test_command_rejects_invalid_model_json(monkeypatch) -> None:
    async def invalid_json(*args, **kwargs):
        return "not json"

    monkeypatch.setattr("jarvis_agent.main.ollama.classify_intent", invalid_json)

    response = asyncio.run(command(CommandRequest(text="перевір ollama")))

    assert response.status_code == 422
    assert json.loads(response.body)["code"] == "INVALID_MODEL_RESPONSE"


def test_command_executes_allowlisted_intent_with_mocked_subprocess(monkeypatch) -> None:
    async def valid_intent(*args, **kwargs):
        return '{"action":"ollama_status","target":"health","arguments":{}}'

    command_args = ["bash", "-lc", "curl -s http://localhost:11434/api/tags >/dev/null && echo 'Ollama is reachable'"]
    completed = __import__("subprocess").CompletedProcess(
        args=command_args,
        returncode=0,
        stdout="Ollama is reachable\n",
        stderr="",
    )
    monkeypatch.setattr("jarvis_agent.main.ollama.classify_intent", valid_intent)
    with __import__("unittest.mock").mock.patch(
        "jarvis_agent.action_executor.subprocess.run",
        return_value=completed,
    ) as run:
        response = asyncio.run(command(CommandRequest(text="перевір ollama")))

    run.assert_called_once()
    assert response.execution.executed is True
