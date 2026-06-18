from __future__ import annotations

import sqlite3
import asyncio
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from fastapi import FastAPI

from knowledge_service.analysis_schema import AnalysisResult
from knowledge_service.analysis_service import AnalysisProvider, JobExecutor
from knowledge_service.bootstrap import KnowledgeDependencies, build_dependencies
from knowledge_service.config import AppConfig, ForgeSettings, load_forge_settings
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.main import create_app


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


class InlineJobExecutor:
    def submit(self, action: Callable[[], None]) -> None:
        action()


class HoldingJobExecutor:
    def __init__(self) -> None:
        self.actions: List[Callable[[], None]] = []

    def submit(self, action: Callable[[], None]) -> None:
        self.actions.append(action)

    def run_next(self) -> None:
        self.actions.pop(0)()


class DeterministicAnalysisProvider:
    name = "deterministic-test"
    version = "1.0"

    def analyze(
        self,
        payload: Dict[str, object],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult | AnalysisResult:
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
    ) -> GraphAnalysisResult | AnalysisResult:
        raise KnowledgeError(
            "ANALYSIS_AI_TRANSPORT_ERROR",
            "deterministic provider failure",
            stage="AI_CALL",
            severity="ERROR",
            raw_preview="provider failed",
        )


def write_runtime_config(tmp_path: Path, *, max_file_size_bytes: int = 500000) -> Path:
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
    (knowledge_dir / "analysis-prompt.md").write_text("Return graph JSON.\n", encoding="utf-8")
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
    logging:
      level: INFO
      console-enabled: false
      file-enabled: false
      directory: "{runtime_dir / "logs"}"
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
          enabled: true
          provider: deterministic
          base-url: http://localhost:11434
          model: deterministic
          prompt-path: "{knowledge_dir / "analysis-prompt.md"}"
          request-timeout-seconds: 5
          context-tokens: 1024
          max-file-chars: 60000
          max-chunk-chars: 20000
          concurrency: 1
          max-attempts-per-file: 1
          repair-attempts-per-file: 0
""".lstrip(),
        encoding="utf-8",
    )
    return forge_config


def build_test_app(
    config_file: Path,
    *,
    provider: Optional[AnalysisProvider] = None,
    executor: Optional[JobExecutor] = None,
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
        job_executor=executor or InlineJobExecutor(),
    )
    return create_app(settings=settings, dependencies=deps), settings, app_config, deps


def sqlite_table_counts(db_path: Path, tables: List[str]) -> Dict[str, int]:
    with sqlite3.connect(db_path) as conn:
        return {table: int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]) for table in tables}
