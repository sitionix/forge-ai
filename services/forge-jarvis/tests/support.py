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
    headers: Dict[str, str]

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

    def get(self, path: str, headers: Optional[Dict[str, str]] = None) -> AsgiResponse:
        return asyncio.run(self._request("GET", path, None, headers or {}))

    def post(self, path: str, json: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None) -> AsgiResponse:
        return asyncio.run(self._request("POST", path, json or {}, headers or {}))

    async def _request(self, method: str, path: str, payload: Optional[Dict[str, Any]], headers: Dict[str, str]) -> AsgiResponse:
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
            "headers": [
                (b"content-type", b"application/json"),
                (b"accept", b"application/json"),
                *[(key.lower().encode("utf-8"), value.encode("utf-8")) for key, value in headers.items()],
            ],
            "client": ("testclient", 50000),
            "server": ("testserver", 80),
            "scheme": "http",
        }
        await self.app(scope, receive, send)
        start = next(message for message in messages if message["type"] == "http.response.start")
        status = start["status"]
        response_headers = {key.decode("utf-8").lower(): value.decode("utf-8") for key, value in start.get("headers", [])}
        response_body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
        return AsgiResponse(status, response_body, response_headers)


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
        self.health_calls = 0

    async def health(self) -> None:
        self.health_calls += 1
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
        self.bundle = bundle if bundle is not None else knowledge_query_bundle()
        self.error = error
        self.calls: List[Dict[str, Any]] = []
        self.flow_explanation_calls: List[Dict[str, Any]] = []
        self.paths: List[str] = []

    async def query(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self.paths.append("/api/v1/knowledge/query")
        self.calls.append(dict(payload))
        if self.error:
            raise self.error
        return self.bundle

    async def query_flow_explanations(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self.paths.append("/api/v1/knowledge/query")
        self.flow_explanation_calls.append(dict(payload))
        if self.error:
            raise self.error
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


def knowledge_query_bundle(
    *,
    status: str = "OK",
    intent: str = "UNKNOWN",
    matched_nodes: Optional[List[Dict[str, Any]]] = None,
    flows: Optional[List[Dict[str, Any]]] = None,
    nodes: Optional[List[Dict[str, Any]]] = None,
    edges: Optional[List[Dict[str, Any]]] = None,
    evidence: Optional[List[Dict[str, Any]]] = None,
    flow_explanations: Optional[List[Dict[str, Any]]] = None,
    diagnostics: Optional[List[Dict[str, str]]] = None,
) -> Dict[str, Any]:
    return {
        "queryId": "query-test",
        "status": status,
        "intent": intent,
        "matchedSources": [
            {
                "sourceId": "forge-ai",
                "displayName": "Forge AI",
                "score": 0.95,
            }
        ]
        if status != "NO_CANDIDATES"
        else [],
        "matchedNodes": matched_nodes
        if matched_nodes is not None
        else []
        if status == "NO_CANDIDATES"
        else [
            {
                "sourceId": "forge-ai",
                "nodeKind": "CALLABLE",
                "label": "JarvisGateway",
                "score": 0.95,
                "matchReasons": ["NAME_MATCH"],
                "relativePath": "src/JarvisGateway.java",
            }
        ],
        "flows": flows
        if flows is not None
        else []
        if status == "NO_CANDIDATES"
        else [
            {
                "flowIndex": 1,
                "source": "forge-ai",
                "entrypoint": {"nodeRef": "n1", "label": "JarvisGateway", "kind": "CALLABLE"},
                "entrypointOrigin": "EXPLICIT_GRAPH_FACT",
                "matchedAnchors": [{"anchorRef": "n1", "label": "JarvisGateway", "score": 0.95, "distance": 0, "matchReasons": ["NAME_MATCH"]}],
                "nodes": nodes if nodes is not None else [{"nodeRef": "n1", "label": "JarvisGateway", "kind": "CALLABLE"}],
                "transitions": edges if edges is not None else [],
                "boundaries": [],
                "evidence": [],
                "complete": True,
                "coverage": {"nodeCount": 1, "transitionCount": 0, "boundaryCount": 0, "anchorCount": 1, "maxDepthReached": 0, "cycleDetected": False, "truncated": False},
                "diagnostics": [],
            }
        ],
        "coverage": {
            "searchedSourceCount": 1,
            "matchedSourceCount": 0 if status == "NO_CANDIDATES" else 1,
            "matchedNodeCount": 0 if status == "NO_CANDIDATES" else 1,
            "flowCount": 0 if status == "NO_CANDIDATES" else 1,
            "nodeCount": 0 if status == "NO_CANDIDATES" else 1,
            "edgeCount": 0,
            "evidenceCount": len(evidence or []),
            "truncated": False,
            "continuationAvailable": False,
        },
        "flowExplanations": flow_explanations or [],
        "diagnostics": diagnostics or [],
    }


def human_answer_bundle(
    *,
    text: str = "JarvisGateway handles the request.",
    answer_language: str = "uk",
    answers: Optional[List[Dict[str, str]]] = None,
    sources: Optional[List[Dict[str, str]]] = None,
    diagnostics: Optional[List[Dict[str, str]]] = None,
) -> Dict[str, Any]:
    if answers is None:
        source_items = sources if sources is not None else [{"source": "forge-ai", "entrypoint": "JarvisGateway"}]
        answers = [{**item, "text": text} for item in source_items]
    return {
        "answerLanguage": answer_language,
        "answers": answers,
        "diagnostics": diagnostics or [],
    }


def query_payload(query_text: str) -> Dict[str, Any]:
    return {"queryText": query_text}


def flow_query_payload(
    query_text: str,
    *,
    answer_language: str = "uk",
    include_tests: bool = False,
    max_flows: Optional[int] = None,
) -> Dict[str, Any]:
    payload = {
        "queryText": query_text,
        "intent": "FLOW_EXPLANATION",
        "answerLanguage": answer_language,
        "includeTests": include_tests,
    }
    if max_flows is not None:
        payload["maxFlows"] = max_flows
    return payload


def normalized_query_payload(
    query_text: str,
    *,
    intent: str = "UNKNOWN",
    answer_language: str = "en",
    include_tests: bool = False,
    max_flows: int = 10,
) -> Dict[str, Any]:
    return {
        "queryText": query_text.strip(),
        "intent": intent,
        "answerLanguage": answer_language,
        "includeTests": include_tests,
        "maxFlows": max_flows,
    }


def write_runtime_config(tmp_path: Path) -> Path:
    config_dir = tmp_path / "config"
    runtime_dir = tmp_path / "var"
    jarvis_dir = config_dir / "jarvis"
    jarvis_dir.mkdir(parents=True, exist_ok=True)
    (jarvis_dir / "system-prompt.md").write_text("Return one intent JSON object.\n", encoding="utf-8")
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
    generative:
      provider: ollama
      base-url: http://localhost:11434
      model: qwen2.5-coder:14b
      context-tokens: 32768
    query:
      flow-explanation:
        request-timeout-seconds: 180
    services:
      jarvis:
        host: 127.0.0.1
        port: 7071
        knowledge-base-url: http://127.0.0.1:7081
        model-runtime:
          request-timeout-seconds: 120
        knowledge:
          request-timeout-seconds: 120
          flow-explanation-transport-grace-seconds: 5
        actions-file: "{jarvis_dir / "allowed-actions.yaml"}"
        system-prompt-path: "{jarvis_dir / "system-prompt.md"}"
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
