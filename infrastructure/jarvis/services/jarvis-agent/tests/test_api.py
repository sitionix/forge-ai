import asyncio
import json

import pytest

from jarvis_agent.chat_schema import ChatRequest
from jarvis_agent.intent_schema import CommandRequest
from jarvis_agent.knowledge_client import KnowledgeClient, KnowledgeUnavailableError
from jarvis_agent.main import actions, chat, command, health, status
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


def knowledge_bundle(context=None, diagnostics=None):
    return {
        "query": "JarvisGateway",
        "context": context if context is not None else [
            {
                "sourceId": "forge-ai",
                "displayName": "Forge AI Service SOX",
                "relativePath": "application/src/main/java/JarvisGateway.java",
                "lineStart": 1,
                "lineEnd": 40,
                "content": "public interface JarvisGateway {}",
                "matchType": "content",
                "reason": "Matched JarvisGateway",
                "score": 1.0,
                "metadata": {"tags": ["java"]},
            }
        ],
        "sourcesUsed": [],
        "budget": {"maxChars": 12000, "usedChars": 33, "truncated": False},
        "diagnostics": diagnostics or [],
    }


def test_chat_rejects_blank_message() -> None:
    response = asyncio.run(chat(ChatRequest(message="   ")))

    assert response.status_code == 400
    assert json.loads(response.body)["code"] == "INVALID_CHAT_MESSAGE"


def test_chat_calls_knowledge_context(monkeypatch) -> None:
    calls = []

    async def context(query, max_context_chars):
        calls.append((query, max_context_chars))
        return knowledge_bundle()

    async def generate_text(prompt):
        return "JarvisGateway proxies Jarvis calls."

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", generate_text)

    response = asyncio.run(chat(ChatRequest(message=" поясни JarvisGateway ", maxContextChars=12000)))

    assert calls == [("поясни JarvisGateway", 12000)]
    assert response.answer == "JarvisGateway proxies Jarvis calls."


def test_chat_calls_ollama_with_retrieved_context(monkeypatch) -> None:
    prompts = []

    async def context(*args, **kwargs):
        return knowledge_bundle()

    async def generate_text(prompt):
        prompts.append(prompt)
        return "Answer from context"

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", generate_text)

    asyncio.run(chat(ChatRequest(message="поясни JarvisGateway")))

    assert "Knowledge context:" in prompts[0]
    assert "[1] forge-ai/application/src/main/java/JarvisGateway.java lines 1-40" in prompts[0]
    assert "public interface JarvisGateway {}" in prompts[0]


def test_chat_response_includes_answer_and_used_context(monkeypatch) -> None:
    async def context(*args, **kwargs):
        return knowledge_bundle()

    async def generate_text(*args, **kwargs):
        return "Answer from context"

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", generate_text)

    response = asyncio.run(chat(ChatRequest(message="поясни JarvisGateway")))

    assert response.answer == "Answer from context"
    assert response.usedContext[0].sourceId == "forge-ai"
    assert response.usedContext[0].relativePath == "application/src/main/java/JarvisGateway.java"
    assert response.usedContext[0].reason == "Matched JarvisGateway"
    assert response.usedContext[0].score == 1.0


def test_chat_empty_context_returns_clear_answer_and_diagnostic(monkeypatch) -> None:
    async def context(*args, **kwargs):
        return knowledge_bundle(context=[])

    async def fail_generate_text(*args, **kwargs):
        raise AssertionError("Ollama must not be called when context is empty")

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", fail_generate_text)

    response = asyncio.run(chat(ChatRequest(message="невідоме")))

    assert "No relevant local Knowledge context was found" in response.answer
    assert response.usedContext == []
    assert any(diagnostic.code == "CONTEXT_EMPTY" for diagnostic in response.diagnostics)


def test_chat_maps_knowledge_unavailable_to_controlled_error(monkeypatch) -> None:
    async def unavailable(*args, **kwargs):
        raise KnowledgeUnavailableError("unavailable")

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", unavailable)

    response = asyncio.run(chat(ChatRequest(message="поясни JarvisGateway")))

    assert response.status_code == 503
    assert json.loads(response.body)["code"] == "KNOWLEDGE_UNAVAILABLE"


def test_chat_maps_ollama_unavailable_to_controlled_error(monkeypatch) -> None:
    async def context(*args, **kwargs):
        return knowledge_bundle()

    async def unavailable(*args, **kwargs):
        raise OllamaUnavailableError("unavailable")

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", unavailable)

    response = asyncio.run(chat(ChatRequest(message="поясни JarvisGateway")))

    assert response.status_code == 503
    assert json.loads(response.body)["code"] == "OLLAMA_UNAVAILABLE"


def test_chat_does_not_execute_actions(monkeypatch) -> None:
    async def context(*args, **kwargs):
        return knowledge_bundle()

    async def generate_text(*args, **kwargs):
        return "Answer from context"

    def fail_execute(*args, **kwargs):
        raise AssertionError("chat must not execute actions")

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", generate_text)
    monkeypatch.setattr("jarvis_agent.main.executor.execute", fail_execute)

    response = asyncio.run(chat(ChatRequest(message="поясни JarvisGateway")))

    assert response.answer == "Answer from context"


def test_chat_does_not_mutate_files(monkeypatch, tmp_path) -> None:
    async def context(*args, **kwargs):
        return knowledge_bundle()

    async def generate_text(*args, **kwargs):
        return "Answer from context"

    def fail_write_text(*args, **kwargs):
        raise AssertionError("chat must not mutate files")

    monkeypatch.setattr("jarvis_agent.main.knowledge.context", context)
    monkeypatch.setattr("jarvis_agent.main.ollama.generate_text", generate_text)
    monkeypatch.setattr(type(tmp_path), "write_text", fail_write_text)

    response = asyncio.run(chat(ChatRequest(message="поясни JarvisGateway")))

    assert response.answer == "Answer from context"


def test_non_localhost_knowledge_base_url_rejected() -> None:
    with pytest.raises(ValueError):
        KnowledgeClient("http://example.com:7081", 120)
