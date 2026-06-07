from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict

import yaml


@dataclass(frozen=True)
class ModelConfig:
    default_model: str
    ollama_base_url: str
    request_timeout_seconds: int


@dataclass(frozen=True)
class AppConfig:
    repo_root: Path
    config_dir: Path
    log_file: Path
    host: str
    port: int
    model: ModelConfig
    system_prompt: str
    allowed_actions_path: Path


def repo_root() -> Path:
    configured = os.getenv("JARVIS_REPO_ROOT")
    if configured:
        return Path(configured).expanduser().resolve()
    return Path(__file__).resolve().parents[4]


def _load_yaml(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle) or {}
    if not isinstance(data, dict):
        raise ValueError(f"Expected YAML mapping in {path}")
    return data


def load_app_config() -> AppConfig:
    root = repo_root()
    configured_config_dir = os.getenv("JARVIS_CONFIG_DIR")
    if configured_config_dir:
        config_dir = Path(configured_config_dir).expanduser().resolve()
    elif (root / "config" / "model.yaml").exists():
        config_dir = (root / "config").resolve()
    else:
        config_dir = (root / "config" / "jarvis").resolve()
    model_path = config_dir / "model.yaml"
    system_prompt_path = config_dir / "system-prompt.md"
    allowed_actions_path = config_dir / "allowed-actions.yaml"

    model_data = _load_yaml(model_path)
    model = ModelConfig(
        default_model=str(model_data.get("default_model", "qwen2.5-coder:7b")),
        ollama_base_url=str(model_data.get("ollama_base_url", "http://localhost:11434")).rstrip("/"),
        request_timeout_seconds=int(model_data.get("request_timeout_seconds", 120)),
    )

    log_file = Path(os.getenv("JARVIS_LOG_FILE", root / "logs" / "jarvis-agent.log")).resolve()
    return AppConfig(
        repo_root=root,
        config_dir=config_dir,
        log_file=log_file,
        host=os.getenv("JARVIS_HOST", "127.0.0.1"),
        port=int(os.getenv("JARVIS_PORT", "7070")),
        model=model,
        system_prompt=system_prompt_path.read_text(encoding="utf-8"),
        allowed_actions_path=allowed_actions_path,
    )
