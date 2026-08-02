from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.config import (
    DEFAULT_CODEX_APP_SERVER_COMMAND,
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
    DEFAULT_GENERATIVE_MODEL,
    AppConfig,
    load_forge_settings,
)
from knowledge_service.query_interpretation import LocalOllamaQueryInterpretationClient


class RecordingQueryHttpClient:
    def __init__(self):
        self.posts = []

    def post(self, url, *, json, timeout):
        self.posts.append({"url": url, "json": json, "timeout": timeout})
        return RecordingQueryResponse()

    def close(self):
        return None


class RecordingQueryResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return {"response": "{}"}


def test_knowledge_generative_defaults_are_canonical_when_not_overridden(tmp_path):
    config_file = _minimal_forge_config(tmp_path)

    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    assert config.analysis_model == DEFAULT_GENERATIVE_MODEL
    assert config.analysis_context_tokens == DEFAULT_GENERATIVE_CONTEXT_TOKENS
    assert config.analysis_request_timeout_seconds == 180
    assert config.human_query_request_timeout_seconds == 180


def test_analyzer_and_query_interpreter_resolve_same_generative_model_and_context(tmp_path):
    config_file = _minimal_forge_config(tmp_path)
    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    analyzer = OllamaAnalysisClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    interpreter = LocalOllamaQueryInterpretationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    try:
        assert analyzer.model == interpreter.model == DEFAULT_GENERATIVE_MODEL
        assert analyzer.context_tokens == interpreter.context_tokens == DEFAULT_GENERATIVE_CONTEXT_TOKENS
    finally:
        asyncio.run(analyzer.aclose())
        interpreter.close()


def test_root_generative_config_changes_knowledge_analyzer_and_query_interpreter(tmp_path):
    config_file = _minimal_forge_config(
        tmp_path,
        generative_model="root-shared-model",
        generative_context_tokens=4096,
    )
    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    analyzer = OllamaAnalysisClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    interpreter = LocalOllamaQueryInterpretationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    try:
        assert config.analysis_model == "root-shared-model"
        assert config.analysis_context_tokens == 4096
        assert analyzer.model == interpreter.model == "root-shared-model"
        assert analyzer.context_tokens == interpreter.context_tokens == 4096
    finally:
        asyncio.run(analyzer.aclose())
        interpreter.close()


def test_root_human_query_timeout_changes_knowledge_deadline(tmp_path):
    config_file = _minimal_forge_config(tmp_path, human_query_timeout_seconds=42)

    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    assert config.human_query_request_timeout_seconds == 42


def test_codex_app_server_defaults_are_owned_by_settings_model(tmp_path):
    config_file = _minimal_forge_config(tmp_path)

    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    assert config.codex_app_server.command == DEFAULT_CODEX_APP_SERVER_COMMAND
    assert config.codex_app_server.runtime_dir == tmp_path / "var" / "knowledge" / "codex-runtime"
    assert config.codex_app_server.request_timeout_seconds == 5.0
    assert config.codex_app_server.discovery_timeout_cap_seconds == 5.0
    assert config.codex_app_server.max_buffered_notifications_per_turn == 100


def test_codex_app_server_configured_values_override_defaults_without_truthiness_fallback(tmp_path):
    config_file = _minimal_forge_config(
        tmp_path,
        codex_app_server="""
        codex-app-server:
          command: ["codex-custom", "app-server", "--stdio"]
          request-timeout-seconds: 7
          discovery-timeout-cap-seconds: 6
          discovery-timeout-allowance-seconds: 2
          max-buffered-notifications-per-turn: 3
          max-buffered-turn-ids: 4
          buffer-ttl-seconds: 5
""",
    )

    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    assert config.codex_app_server.command == ("codex-custom", "app-server", "--stdio")
    assert config.codex_app_server.request_timeout_seconds == 7
    assert config.codex_app_server.discovery_timeout_cap_seconds == 6
    assert config.codex_app_server.discovery_timeout_allowance_seconds == 2
    assert config.codex_app_server.max_buffered_notifications_per_turn == 3
    assert config.codex_app_server.max_buffered_turn_ids == 4
    assert config.codex_app_server.buffer_ttl_seconds == 5


@pytest.mark.parametrize(
    "codex_app_server",
    [
        """
        codex-app-server:
          command: ["codex", "  "]
""",
        """
        codex-app-server:
          request-timeout-seconds: 0
""",
    ],
)
def test_codex_app_server_explicit_invalid_values_are_rejected(tmp_path, codex_app_server: str):
    config_file = _minimal_forge_config(tmp_path, codex_app_server=codex_app_server)

    with pytest.raises(ValueError):
        load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))


def test_shared_human_query_timeout_env_override_changes_knowledge_deadline(tmp_path):
    config_file = _minimal_forge_config(tmp_path)
    environ = _env(tmp_path, config_file)
    environ["FORGE_HUMAN_QUERY_REQUEST_TIMEOUT_SECONDS"] = "73"

    settings = load_forge_settings(config_file=config_file, environ=environ)
    config = AppConfig.from_forge_settings(settings)

    assert config.human_query_request_timeout_seconds == 73


def test_analyzer_timeout_env_override_does_not_change_human_query_deadline(tmp_path):
    config_file = _minimal_forge_config(tmp_path, human_query_timeout_seconds=42)
    environ = _env(tmp_path, config_file)
    environ["KNOWLEDGE_ANALYSIS_REQUEST_TIMEOUT_SECONDS"] = "300"

    settings = load_forge_settings(config_file=config_file, environ=environ)
    config = AppConfig.from_forge_settings(settings)

    assert config.analysis_request_timeout_seconds == 300
    assert config.human_query_request_timeout_seconds == 42


@pytest.mark.parametrize("provider", ["openai", "custom", ""])
def test_generative_provider_must_be_ollama(tmp_path, provider):
    config_file = _minimal_forge_config(tmp_path, generative_provider=provider)

    with pytest.raises(ValueError, match="generative provider must be ollama"):
        load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))


def test_generative_context_below_minimum_fails_loading(tmp_path):
    config_file = _minimal_forge_config(tmp_path, generative_context_tokens=512)

    with pytest.raises(ValueError):
        load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))


def test_knowledge_ollama_clients_reject_context_below_minimum():
    with pytest.raises(ValueError, match="context_tokens must be at least 1024"):
        OllamaAnalysisClient("http://127.0.0.1:11434", "model", 120, 512)
    with pytest.raises(ValueError, match="Query interpretation context_tokens must be at least 1024"):
        LocalOllamaQueryInterpretationClient("http://127.0.0.1:11434", "model", 120, 512)


def test_query_interpretation_ollama_payload_uses_exact_loaded_context():
    recorder = RecordingQueryHttpClient()
    client = LocalOllamaQueryInterpretationClient(
        "http://127.0.0.1:11434",
        "qwen2.5-coder:14b",
        120,
        32768,
        http_client=recorder,
    )
    try:
        result = client.complete({"queryText": "Explain Unit.run", "answerLanguage": "AUTO"})
    finally:
        client.close()

    assert result.raw_text == "{}"
    assert recorder.posts[0]["json"]["model"] == "qwen2.5-coder:14b"
    assert recorder.posts[0]["json"]["options"]["num_ctx"] == 32768


def _minimal_forge_config(
    tmp_path: Path,
    *,
    generative_provider: str = "ollama",
    generative_model: str = DEFAULT_GENERATIVE_MODEL,
    generative_context_tokens: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS,
    human_query_timeout_seconds: int = 180,
    codex_app_server: str = "",
) -> Path:
    config_dir = tmp_path / "config"
    runtime_dir = tmp_path / "var"
    workspace = tmp_path / "workspace"
    config_dir.mkdir(parents=True)
    runtime_dir.mkdir(parents=True)
    workspace.mkdir(parents=True)
    config_file = config_dir / "forge-ai.yaml"
    config_file.write_text(
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
    generative:
      provider: {generative_provider}
      base-url: http://localhost:11434
      model: {generative_model}
      context-tokens: {generative_context_tokens}
    query:
      human-query:
        request-timeout-seconds: {human_query_timeout_seconds}
    services:
      knowledge:
        host: 127.0.0.1
        port: 7081
        storage:
          sqlite-path: "{runtime_dir / "knowledge" / "knowledge.sqlite"}"
        inventory:
          source-catalog-path: "{config_dir / "knowledge" / "knowledge-sources.yaml"}"
          service-catalog-path: "{config_dir / "services.yaml"}"
        {codex_app_server.rstrip()}
""".lstrip(),
        encoding="utf-8",
    )
    return config_file


def _env(tmp_path: Path, config_file: Path) -> dict[str, str]:
    return {
        "FORGE_CONFIG_FILE": str(config_file),
        "FORGE_AI_HOME": str(tmp_path),
        "FORGE_CONFIG_DIR": str(tmp_path / "config"),
        "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
        "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
    }
