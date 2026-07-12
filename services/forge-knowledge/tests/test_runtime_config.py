from __future__ import annotations

import asyncio
from pathlib import Path

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.config import (
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
    DEFAULT_GENERATIVE_MODEL,
    AppConfig,
    load_forge_settings,
)
from knowledge_service.flow_explanations import LocalOllamaFlowExplanationClient


def test_knowledge_generative_defaults_are_canonical_when_not_overridden(tmp_path):
    config_file = _minimal_forge_config(tmp_path)

    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    assert config.analysis_model == DEFAULT_GENERATIVE_MODEL
    assert config.analysis_context_tokens == DEFAULT_GENERATIVE_CONTEXT_TOKENS
    assert config.analysis_request_timeout_seconds == 180


def test_analyzer_and_flow_explanations_resolve_same_generative_model_and_context(tmp_path):
    config_file = _minimal_forge_config(tmp_path)
    settings = load_forge_settings(config_file=config_file, environ=_env(tmp_path, config_file))
    config = AppConfig.from_forge_settings(settings)

    analyzer = OllamaAnalysisClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    flow = LocalOllamaFlowExplanationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    try:
        assert analyzer.model == flow.model == DEFAULT_GENERATIVE_MODEL
        assert analyzer.context_tokens == flow.context_tokens == DEFAULT_GENERATIVE_CONTEXT_TOKENS
    finally:
        asyncio.run(analyzer.aclose())
        flow.close()


def test_root_generative_config_changes_knowledge_analyzer_and_flow_explanations(tmp_path):
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
    flow = LocalOllamaFlowExplanationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    try:
        assert config.analysis_model == "root-shared-model"
        assert config.analysis_context_tokens == 4096
        assert analyzer.model == flow.model == "root-shared-model"
        assert analyzer.context_tokens == flow.context_tokens == 4096
    finally:
        asyncio.run(analyzer.aclose())
        flow.close()


def _minimal_forge_config(
    tmp_path: Path,
    *,
    generative_model: str = DEFAULT_GENERATIVE_MODEL,
    generative_context_tokens: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS,
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
      provider: ollama
      base-url: http://localhost:11434
      model: {generative_model}
      context-tokens: {generative_context_tokens}
    services:
      knowledge:
        host: 127.0.0.1
        port: 7081
        storage:
          sqlite-path: "{runtime_dir / "knowledge" / "knowledge.sqlite"}"
        inventory:
          source-catalog-path: "{config_dir / "knowledge" / "knowledge-sources.yaml"}"
          service-catalog-path: "{config_dir / "services.yaml"}"
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
