from __future__ import annotations

import sqlite3
import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI

from knowledge_service.analysis_service import AnalysisProvider
from knowledge_service.bootstrap import KnowledgeDependencies, build_dependencies
from knowledge_service.config import AppConfig, ForgeSettings, load_forge_settings
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.main import create_app
from knowledge_service.query_interpretation import QueryInterpretationProviderResult
from knowledge_service.flow_formatter import FlowFormatterProviderResult


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
        self._lifespan = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._previous_loop: Optional[asyncio.AbstractEventLoop] = None

    def __enter__(self) -> "AsgiTestClient":
        try:
            self._previous_loop = asyncio.get_event_loop()
        except RuntimeError:
            self._previous_loop = None
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        self._lifespan = self.app.router.lifespan_context(self.app)
        self._loop.run_until_complete(self._lifespan.__aenter__())
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._lifespan is not None and self._loop is not None:
            self._loop.run_until_complete(self._lifespan.__aexit__(exc_type, exc, tb))
            self._lifespan = None
        if self._loop is not None:
            self._loop.close()
            self._loop = None
        asyncio.set_event_loop(self._previous_loop)
        self._previous_loop = None
        return None

    def get(self, path: str, headers: Optional[Dict[str, str]] = None) -> AsgiResponse:
        return self._run(self._request("GET", path, None, headers or {}))

    def post(self, path: str, json: Optional[Dict[str, Any]] = None, headers: Optional[Dict[str, str]] = None) -> AsgiResponse:
        return self._run(self._request("POST", path, json or {}, headers or {}))

    def _run(self, awaitable):
        if self._loop is not None:
            return self._loop.run_until_complete(awaitable)
        return asyncio.run(awaitable)

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


class DeterministicAnalysisProvider:
    name = "deterministic-test"
    version = "1.0"

    def analyze(
        self,
        payload: Dict[str, object],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult:
        return GraphAnalysisResult(
            diagnostics=[
                {
                    "code": "TEST_PROVIDER_USED",
                    "message": "Deterministic analysis provider was used.",
                    "severity": "INFO",
                    "stage": "AI_CALL",
                    "sourceId": payload.get("sourceId"),
                    "relativePath": payload.get("relativePath"),
                }
            ]
        )


class FailingAnalysisProvider:
    name = "deterministic-test"
    version = "1.0"

    def analyze(
        self,
        payload: Dict[str, object],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult:
        raise KnowledgeError(
            "ANALYSIS_AI_TRANSPORT_ERROR",
            "deterministic provider failure",
            stage="AI_CALL",
            severity="ERROR",
            raw_preview="provider failed",
        )


class DeterministicQueryInterpretationProvider:
    name = "deterministic-query-interpretation"
    model = "deterministic"

    def __init__(self) -> None:
        self.calls: List[Dict[str, Any]] = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append(
            {
                "llmInput": dict(llm_input),
                "validationErrors": list(validation_errors or []),
                "timeoutSeconds": timeout_seconds,
            }
        )
        query_text = str(llm_input.get("queryText") or "").strip()
        explicit_language = llm_input.get("explicitAnswerLanguage")
        detected = "uk" if any("\u0400" <= char <= "\u04ff" for char in _strip_code_symbols(query_text)) else "en"
        if not _strip_code_symbols(query_text).strip():
            detected = "und"
        response_language = str(explicit_language or "").strip().lower() or ("uk" if detected in {"uk", "ru"} else "en")
        identifiers = _query_identifiers(query_text)
        payload = {
            "detectedLanguage": detected,
            "responseLanguage": response_language,
            "normalizedQuery": query_text,
            "searchQueries": [query_text],
            "codeIdentifiers": identifiers,
            "concepts": [],
        }
        return QueryInterpretationProviderResult(raw_text=json.dumps(payload), prompt_char_length=100)


class DeterministicFinalFlowFormatterProvider:
    name = "deterministic-final-flow-formatter"
    model = "deterministic"

    def __init__(self) -> None:
        self.calls: List[Dict[str, Any]] = []

    def complete(self, formatter_input, validation_errors=None, timeout_seconds=None):
        self.calls.append(
            {
                "formatterInput": dict(formatter_input),
                "validationErrors": list(validation_errors or []),
                "timeoutSeconds": timeout_seconds,
            }
        )
        response_language = str(formatter_input.get("responseLanguage") or "en").lower()
        sections = []
        for section in formatter_input.get("sections", []):
            steps = []
            current: List[Dict[str, Any]] = []
            current_scope = None
            for group in section.get("orderedGroups", []):
                scope = group.get("mergeScope")
                if current and scope != current_scope:
                    steps.append(_formatter_step(current, response_language))
                    current = []
                current_scope = scope
                current.append(group)
            if current:
                steps.append(_formatter_step(current, response_language))
            sections.append({"sectionRef": section.get("sectionRef"), "steps": steps})
        return FlowFormatterProviderResult(raw_text=json.dumps({"sections": sections}, ensure_ascii=False), prompt_char_length=100)


def _strip_code_symbols(value: str) -> str:
    result = value
    for identifier in _query_identifiers(value):
        result = result.replace(identifier, " ")
    return result


def _query_identifiers(value: str) -> List[str]:
    import re

    identifiers: List[str] = []
    for match in re.finditer(r"\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+\b", value):
        identifiers.append(match.group(0))
    return identifiers


def _flatten_formatter_groups(groups):
    for group in (groups if isinstance(groups, list) else []):
        if not isinstance(group, dict):
            continue
        yield group
        yield from _flatten_formatter_groups(group.get("childGroups", []))


def _formatter_step(groups: List[Dict[str, Any]], language: str) -> Dict[str, Any]:
    certainty = str(groups[0].get("certainty") or "VERIFIED")
    return {
        "groupRefs": [group.get("groupRef") for group in groups],
        "certainty": certainty,
        "text": _formatter_sentence(groups, certainty, language),
    }


def _formatter_sentence(groups: List[Dict[str, Any]], certainty: str, language: str) -> str:
    identifiers: List[str] = []
    for group in groups:
        for value in _formatter_identifiers(group):
            if value not in identifiers:
                identifiers.append(value)
    joined = ", ".join(identifiers) if identifiers else str(groups[0].get("kind") or "step")
    if language == "uk":
        uncertainty = " з непідтвердженим зв'язком" if certainty == "UNVERIFIED" else " з неоднозначним зв'язком" if certainty == "AMBIGUOUS" else ""
        return f"Цей крок описує доступний потік{uncertainty}: {joined}."
    if language == "de":
        uncertainty = " mit ungesicherter Verbindung" if certainty == "UNVERIFIED" else " mit mehrdeutiger Verbindung" if certainty == "AMBIGUOUS" else ""
        return f"Dieser Schritt beschreibt den verfügbaren Ablauf{uncertainty}: {joined}."
    if language == "fr":
        uncertainty = " avec un lien non confirmé" if certainty == "UNVERIFIED" else " avec un lien ambigu" if certainty == "AMBIGUOUS" else ""
        return f"Cette étape décrit le flux disponible{uncertainty}: {joined}."
    uncertainty = " with an unconfirmed connection" if certainty == "UNVERIFIED" else " with an ambiguous connection" if certainty == "AMBIGUOUS" else ""
    return f"This step describes the available flow{uncertainty}: {joined}."


def _formatter_identifiers(group: Dict[str, Any]) -> List[str]:
    values: List[str] = []
    for key in (
        "symbol",
        "fromSymbol",
        "toSymbol",
        "method",
        "route",
        "topic",
        "schedule",
        "operationIdentity",
        "interfaceIdentity",
        "targetDescriptor",
    ):
        value = group.get(key)
        if isinstance(value, str) and value.strip() and value.strip() not in values:
            values.append(value.strip())
    return values


def write_runtime_config(
    tmp_path: Path,
    *,
    analysis_enabled: bool = True,
    startup_maintenance_enabled: bool = True,
    max_file_size_bytes: int = 500000,
    semantic_auto_build_enabled: bool = False,
    semantic_auto_build_interval_seconds: float = 60.0,
    semantic_failed_retry_backoff_seconds: float = 300.0,
    semantic_building_stale_after_seconds: float = 300.0,
) -> Path:
    config_dir = tmp_path / "config"
    runtime_dir = tmp_path / "var"
    workspace = tmp_path / "workspace"
    service_root = workspace / "forge-ai"
    source_dir = service_root / "src" / "main" / "java"
    source_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "JarvisGateway.java").write_text(
        """
package com.example;

public class JarvisGateway {
    public String status() {
        return helper();
    }

    private String helper() {
        return "UP";
    }
}
""".strip()
        + "\n",
        encoding="utf-8",
    )
    (service_root / "README.md").write_text("JarvisGateway local notes\n", encoding="utf-8")
    (source_dir / "TooLarge.java").write_text("public class TooLarge {}\n" + ("x" * (max_file_size_bytes + 20)), encoding="utf-8")
    (service_root / "build" / "Generated.java").parent.mkdir(parents=True, exist_ok=True)
    (service_root / "build" / "Generated.java").write_text("public class Generated {}\n", encoding="utf-8")

    config_dir.mkdir(parents=True, exist_ok=True)
    (config_dir / "services.yaml").write_text(
        f"""
services:
  forge-ai:
    label: Forge AI
    path: "{service_root.relative_to(workspace).as_posix()}"
    group: platform
    tags: [java]
""".lstrip(),
        encoding="utf-8",
    )
    knowledge_dir = config_dir / "knowledge"
    knowledge_dir.mkdir(exist_ok=True)
    (knowledge_dir / "knowledge-sources.yaml").write_text(
        f"""
catalog:
  type: service_catalog
  path: "{config_dir / "services.yaml"}"
  workspace_root: "{workspace}"
selection:
  include_groups: []
  include_services: []
  exclude_services: []
indexing:
  include:
    - "**/*.java"
    - "**/*.md"
  exclude:
    - "build/**"
    - "**/build/**"
  max_file_size_bytes: {max_file_size_bytes}
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
    workspace-root: "{workspace}"
    startup-maintenance-enabled: {str(startup_maintenance_enabled).lower()}
    logging:
      level: INFO
      console-enabled: false
      file-enabled: false
      directory: "{runtime_dir / "logs"}"
    generative:
      provider: ollama
      base-url: http://localhost:11434
      model: deterministic
      context-tokens: 32768
    services:
      knowledge:
        host: 127.0.0.1
        port: 7081
        storage:
          sqlite-path: "{runtime_dir / "knowledge" / "knowledge.sqlite"}"
        inventory:
          source-catalog-path: "{knowledge_dir / "knowledge-sources.yaml"}"
          service-catalog-path: "{config_dir / "services.yaml"}"
          auto-refresh-enabled: false
          auto-refresh-interval-seconds: 60
        analysis:
          enabled: {str(analysis_enabled).lower()}
          request-timeout-seconds: 5
          max-file-chars: 60000
          max-chunk-chars: 20000
          concurrency: 1
          max-attempts-per-file: 1
          repair-attempts-per-file: 0
        semantic:
          enabled: true
          auto-build-enabled: {str(semantic_auto_build_enabled).lower()}
          auto-build-interval-seconds: {semantic_auto_build_interval_seconds}
          failed-retry-backoff-seconds: {semantic_failed_retry_backoff_seconds}
          building-stale-after-seconds: {semantic_building_stale_after_seconds}
""".lstrip(),
        encoding="utf-8",
    )
    return forge_config


def build_test_app(
    config_file: Path,
    *,
    provider: Optional[AnalysisProvider] = None,
) -> tuple[FastAPI, ForgeSettings, AppConfig, KnowledgeDependencies]:
    env = {
        "FORGE_CONFIG_FILE": str(config_file),
        "FORGE_AI_HOME": str(config_file.parents[1]),
        "FORGE_CONFIG_DIR": str(config_file.parent),
        "FORGE_RUNTIME_DIR": str(config_file.parents[1] / "var"),
        "FORGE_WORKSPACE_ROOT": str(config_file.parents[1] / "workspace"),
    }
    settings = load_forge_settings(config_file=config_file, environ=env)
    app_config = AppConfig.from_forge_settings(settings)
    deps = build_dependencies(
        app_config,
        analysis_provider=provider or DeterministicAnalysisProvider(),
    )
    app = create_app(settings=settings, dependencies=deps)
    app.state.query_interpretation_provider = DeterministicQueryInterpretationProvider()
    app.state.final_flow_formatter_provider = DeterministicFinalFlowFormatterProvider()
    return app, settings, app_config, deps


def sqlite_table_counts(db_path: Path, tables: List[str]) -> Dict[str, int]:
    with sqlite3.connect(db_path) as conn:
        return {table: int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]) for table in tables}
