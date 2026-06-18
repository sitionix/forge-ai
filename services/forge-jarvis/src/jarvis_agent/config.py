from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict
from urllib.parse import urlparse

import yaml


@dataclass(frozen=True)
class ModelConfig:
    default_model: str
    ollama_base_url: str
    request_timeout_seconds: int


@dataclass(frozen=True)
class KnowledgeConfig:
    base_url: str
    request_timeout_seconds: int
    default_max_context_chars: int


@dataclass(frozen=True)
class AppConfig:
    repo_root: Path
    config_dir: Path
    log_file: Path
    host: str
    port: int
    model: ModelConfig
    knowledge: KnowledgeConfig
    system_prompt: str
    chat_prompt: str
    allowed_actions_path: Path


def repo_root() -> Path:
    configured = os.getenv("JARVIS_REPO_ROOT")
    if configured:
        return Path(configured).expanduser().resolve()
    return Path(__file__).resolve().parents[2]


def forge_ai_home(module_root: Path) -> Path:
    configured = os.getenv("FORGE_AI_HOME")
    if configured:
        return Path(configured).expanduser().resolve()
    for candidate in [Path.cwd().resolve(), module_root, *module_root.parents]:
        if (candidate / "config" / "services.yaml").is_file():
            return candidate
        if (candidate / "pom.xml").is_file() and (candidate / "services" / "forge-nexus" / "boot").is_dir():
            return candidate
        if (candidate / "pom.xml").is_file() and (candidate / "boot" / "src" / "main" / "resources" / "services.yaml").is_file():
            return candidate
    return module_root


def _resolve_dir(value: str, module_root: Path) -> Path:
    path = Path(value).expanduser()
    if path.is_absolute():
        return path.resolve()
    return (forge_ai_home(module_root) / path).resolve()


def _config_dir_candidates(module_root: Path):
    candidates = []
    if os.getenv("JARVIS_CONFIG_DIR"):
        candidates.append(_resolve_dir(os.environ["JARVIS_CONFIG_DIR"], module_root))
    if os.getenv("FORGE_CONFIG_DIR"):
        forge_config = _resolve_dir(os.environ["FORGE_CONFIG_DIR"], module_root)
        candidates.extend([forge_config / "jarvis", forge_config / "config" / "jarvis", forge_config])

    forge_home = forge_ai_home(module_root)
    candidates.extend([
        Path.cwd().resolve() / "config" / "jarvis",
        forge_home / "config" / "jarvis",
    ])

    seen = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        yield resolved


def _select_config_dir(module_root: Path) -> Path:
    candidates = list(_config_dir_candidates(module_root))
    for candidate in candidates:
        if (candidate / "model.yaml").exists():
            return candidate
    return candidates[0] if candidates else (forge_ai_home(module_root) / "config" / "jarvis").resolve()


def _load_yaml(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        data = yaml.safe_load(handle) or {}
    if not isinstance(data, dict):
        raise ValueError(f"Expected YAML mapping in {path}")
    return data


def _assert_localhost_url(url: str, label: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme.lower() != "http":
        raise ValueError(f"{label} must use http")
    if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost"}:
        raise ValueError(f"{label} must point to localhost")


def load_app_config() -> AppConfig:
    root = repo_root()
    config_dir = _select_config_dir(root)
    model_path = config_dir / "model.yaml"
    system_prompt_path = config_dir / "system-prompt.md"
    chat_prompt_path = config_dir / "chat-prompt.md"
    allowed_actions_path = config_dir / "allowed-actions.yaml"

    model_data = _load_yaml(model_path)
    model = ModelConfig(
        default_model=str(model_data.get("default_model", "qwen2.5-coder:7b")),
        ollama_base_url=str(model_data.get("ollama_base_url", "http://localhost:11434")).rstrip("/"),
        request_timeout_seconds=int(model_data.get("request_timeout_seconds", 120)),
    )
    _assert_localhost_url(model.ollama_base_url, "Ollama base URL")
    knowledge_data = model_data.get("knowledge", {})
    if not isinstance(knowledge_data, dict):
        raise ValueError("Expected YAML mapping for knowledge")
    knowledge = KnowledgeConfig(
        base_url=str(knowledge_data.get("base_url", "http://127.0.0.1:7081")).rstrip("/"),
        request_timeout_seconds=int(knowledge_data.get("request_timeout_seconds", 120)),
        default_max_context_chars=int(knowledge_data.get("default_max_context_chars", 12000)),
    )
    _assert_localhost_url(knowledge.base_url, "Knowledge base URL")

    default_log_file = forge_ai_home(root) / "var" / "jarvis" / "logs" / "jarvis-agent.log"
    log_file = Path(os.getenv("JARVIS_LOG_FILE", default_log_file)).resolve()
    return AppConfig(
        repo_root=root,
        config_dir=config_dir,
        log_file=log_file,
        host=os.getenv("JARVIS_HOST", "127.0.0.1"),
        port=int(os.getenv("JARVIS_PORT", "7071")),
        model=model,
        knowledge=knowledge,
        system_prompt=system_prompt_path.read_text(encoding="utf-8"),
        chat_prompt=chat_prompt_path.read_text(encoding="utf-8"),
        allowed_actions_path=allowed_actions_path,
    )
