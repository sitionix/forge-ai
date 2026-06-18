from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI

from jarvis_agent.action_executor import ActionExecutionError
from jarvis_agent.action_registry import ActionRegistry
from jarvis_agent.bootstrap import JarvisDependencies
from jarvis_agent.config import AppConfig, ForgeSettings, load_forge_settings
from jarvis_agent.intent_schema import ExecutionResult, Intent
from jarvis_agent.knowledge_client import KnowledgeBadResponseError, KnowledgeUnavailableError
from jarvis_agent.main import create_app
from jarvis_agent.ollama_client import OllamaBadResponseError, OllamaUnavailableError


@dataclass(frozen=True)
class AsgiResponse:
    status_code: int
    body: bytes

    def json(self) -> Dict[str, Any]:
        return json.loads(self.body.decode("utf-8") or "{}")


class AsgiTestClient:
    __test__ = False

    def __init__(self, app: FastAPI) -> None:
        self.app = app

    def __enter__(self) -> "AsgiTestClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None

    def get(self, path: str) -> AsgiResponse:
        return asyncio.run(self._request("GET", path, None))

    def post(self, path: str, json: Optional[Dict[str, Any]] = None) -> AsgiResponse:
        return asyncio.run(self._request("POST", path, json or {}))

    async def _request(self, method: str, path: str, payload: Optional[Dict[str, Any]]) -> AsgiResponse:
        raw_path, _, query = path.partition("?")
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        messages: List[Dict[str, Any]] = []
        received = False

        async def receive() -> Dict[str, Any]:
            nonlocal received
            if received:
                return {"type": "http.disconnect"}
            received = True
            return {"type": "http.request", "body": body, "more_body": False}

        async def send(message: Dict[str, Any]) -> None:
            messages.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": method,
            "path": raw_path,
            "raw_path": raw_path.encode("utf-8"),
            "query_string": query.encode("utf-8"),
            "headers": [(b"content-type", b"application/json"), (b"accept", b"application/json")],
            "client": ("testclient", 50000),
            "server": ("testserver", 80),
            "scheme": "http",
        }
        await self.app(scope, receive, send)
        status = next(message["status"] for message in messages if message["type"] == "http.response.start")
        response_body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
        return AsgiResponse(status, response_body)


class FakeModelClient:
    def __init__(
        self,
        *,
        intent_response: str = '{"action":"ollama_status","target":"health","arguments":{}}',
        generate_response: str = "Answer from context",
        health_error: Optional[Exception] = None,
        classify_error: Optional[Exception] = None,
        generate_error: Optional[Exception] = None,
    ) -> None:
        self.intent_response = intent_response
        self.generate_response = generate_response
        self.health_error = health_error
        self.classify_error = classify_error
        self.generate_error = generate_error
        self.prompts: List[str] = []

    async def health(self) -> None:
        if self.health_error:
            raise self.health_error

    async def classify_intent(self, system_prompt: str, user_text: str, actions: List[Dict[str, Any]]) -> str:
        if self.classify_error:
            raise self.classify_error
        return self.intent_response

    async def generate_text(self, prompt: str) -> str:
        if self.generate_error:
            raise self.generate_error
        self.prompts.append(prompt)
        return self.generate_response


class FakeKnowledgeClient:
    def __init__(self, *, bundle: Optional[Dict[str, Any]] = None, error: Optional[Exception] = None) -> None:
        self.bundle = bundle if bundle is not None else knowledge_bundle()
        self.error = error
        self.calls: List[tuple[str, int]] = []

    async def context(self, query: str, max_context_chars: int) -> Dict[str, Any]:
        if self.error:
            raise self.error
        self.calls.append((query, max_context_chars))
        return self.bundle


class RecordingActionExecutor:
    def __init__(self, registry: ActionRegistry, *, fail: bool = False) -> None:
        self.registry = registry
        self.fail = fail
        self.invocations: List[tuple[str, Optional[str], str]] = []

    def execute(self, intent: Intent, user_text: str) -> ExecutionResult:
        self.registry.resolve(intent)
        self.invocations.append((intent.action, intent.target, user_text))
        if self.fail:
            raise ActionExecutionError("controlled failure")
        return ExecutionResult(executed=True, message=f"Action executed: {intent.action}.{intent.target}", output="ok")


def knowledge_bundle(context: Optional[List[Dict[str, Any]]] = None, diagnostics: Optional[List[Dict[str, str]]] = None) -> Dict[str, Any]:
    return {
        "query": "JarvisGateway",
        "context": context
        if context is not None
        else [
            {
                "sourceId": "forge-ai",
                "displayName": "Forge AI",
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


def write_runtime_config(tmp_path: Path) -> Path:
    config_dir = tmp_path / "config"
    runtime_dir = tmp_path / "var"
    jarvis_dir = config_dir / "jarvis"
    jarvis_dir.mkdir(parents=True, exist_ok=True)
    (jarvis_dir / "system-prompt.md").write_text("Return one intent JSON object.\n", encoding="utf-8")
    (jarvis_dir / "chat-prompt.md").write_text("Answer from Knowledge context.\n", encoding="utf-8")
    (jarvis_dir / "allowed-actions.yaml").write_text(
        """
actions:
  ollama_status:
    description: Check local model runtime health
    targets:
      health:
        command: ["echo", "ollama ok"]
  open_application:
    description: Open an allowlisted app
    targets:
      editor:
        command: ["echo", "open editor"]
""".lstrip(),
        encoding="utf-8",
    )
    forge_config = config_dir / "forge-ai.yaml"
    forge_config.write_text(
        f"""
forge:
  ai:
    home: "{tmp_path}"
    config-dir: "{config_dir}"
    runtime-dir: "{runtime_dir}"
    workspace-root: "{tmp_path / "workspace"}"
    logging:
      level: INFO
      console-enabled: false
      file-enabled: false
      directory: "{runtime_dir / "logs"}"
    services:
      jarvis:
        host: 127.0.0.1
        port: 7071
        knowledge-base-url: http://127.0.0.1:7081
        model-runtime:
          provider: ollama
          base-url: http://localhost:11434
          model: qwen2.5-coder:7b
          request-timeout-seconds: 120
        knowledge:
          request-timeout-seconds: 120
          default-max-context-chars: 12000
        actions-file: "{jarvis_dir / "allowed-actions.yaml"}"
        system-prompt-path: "{jarvis_dir / "system-prompt.md"}"
        chat-prompt-path: "{jarvis_dir / "chat-prompt.md"}"
""".lstrip(),
        encoding="utf-8",
    )
    return forge_config


def build_test_app(
    config_file: Path,
    *,
    model: Optional[FakeModelClient] = None,
    knowledge: Optional[FakeKnowledgeClient] = None,
    executor: Optional[RecordingActionExecutor] = None,
) -> tuple[FastAPI, ForgeSettings, AppConfig, JarvisDependencies, RecordingActionExecutor, FakeModelClient, FakeKnowledgeClient]:
    env = {
        "FORGE_CONFIG_FILE": str(config_file),
        "FORGE_AI_HOME": str(config_file.parents[1]),
        "FORGE_CONFIG_DIR": str(config_file.parent),
        "FORGE_RUNTIME_DIR": str(config_file.parents[1] / "var"),
        "FORGE_WORKSPACE_ROOT": str(config_file.parents[1] / "workspace"),
    }
    settings = load_forge_settings(config_file=config_file, environ=env)
    app_config = AppConfig.from_forge_settings(settings, env)
    registry = ActionRegistry.from_yaml(app_config.allowed_actions_path)
    model_client = model or FakeModelClient()
    knowledge_client = knowledge or FakeKnowledgeClient()
    action_executor = executor or RecordingActionExecutor(registry)
    deps = JarvisDependencies(
        knowledge_client=knowledge_client,
        model_client=model_client,
        action_registry=registry,
        action_executor=action_executor,  # type: ignore[arg-type]
    )
    return create_app(settings=settings, dependencies=deps), settings, app_config, deps, action_executor, model_client, knowledge_client


def knowledge_unavailable() -> KnowledgeUnavailableError:
    return KnowledgeUnavailableError("unavailable")


def knowledge_bad_response() -> KnowledgeBadResponseError:
    return KnowledgeBadResponseError("bad response")


def ollama_unavailable() -> OllamaUnavailableError:
    return OllamaUnavailableError("unavailable")


def ollama_bad_response() -> OllamaBadResponseError:
    return OllamaBadResponseError("bad response")
