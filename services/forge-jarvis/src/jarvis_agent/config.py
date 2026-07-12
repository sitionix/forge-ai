from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Dict, Mapping, Optional
from urllib.parse import urlparse

import yaml
from pydantic import AnyHttpUrl, BaseModel, Field, root_validator, validator

_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")


class LoggingSettings(BaseModel):
    level: str = "INFO"
    console_enabled: bool = True
    file_enabled: bool = True
    directory: Path

    @validator("level")
    def normalize_level(cls, value: str) -> str:
        normalized = value.upper()
        allowed = {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}
        if normalized not in allowed:
            raise ValueError(f"logging.level must be one of {sorted(allowed)}")
        return normalized


class ModelRuntimeSettings(BaseModel):
    provider: str = "ollama"
    base_url: AnyHttpUrl
    model: str = Field(min_length=1)
    request_timeout_seconds: int = Field(default=120, ge=1)

    @validator("base_url")
    def require_local_model_runtime(cls, value: AnyHttpUrl) -> AnyHttpUrl:
        parsed = urlparse(str(value))
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("jarvis model runtime base_url must point to localhost")
        return value


class JarvisKnowledgeSettings(BaseModel):
    request_timeout_seconds: int = Field(default=120, ge=1)


class JarvisSettings(BaseModel):
    host: str = Field(min_length=1)
    port: int = Field(ge=1, le=65535)
    knowledge_base_url: AnyHttpUrl
    model_runtime: ModelRuntimeSettings
    knowledge: JarvisKnowledgeSettings
    actions_file: Path
    system_prompt_path: Path

    @validator("knowledge_base_url")
    def require_local_knowledge(cls, value: AnyHttpUrl) -> AnyHttpUrl:
        parsed = urlparse(str(value))
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("jarvis knowledge_base_url must point to localhost")
        return value


class ServicesSettings(BaseModel):
    jarvis: JarvisSettings


class ForgeSettings(BaseModel):
    home: Path
    config_dir: Path
    runtime_dir: Path
    workspace_root: Optional[Path]
    logging: LoggingSettings
    services: ServicesSettings

    @root_validator
    def ensure_paths_are_absolute(cls, values: Dict[str, Any]) -> Dict[str, Any]:
        for key in ("home", "config_dir", "runtime_dir"):
            path = values.get(key)
            if isinstance(path, Path) and not path.is_absolute():
                values[key] = path.resolve()
        workspace_root = values.get("workspace_root")
        if isinstance(workspace_root, Path) and not workspace_root.is_absolute():
            values["workspace_root"] = workspace_root.resolve()
        return values


class ModelConfig(BaseModel):
    default_model: str
    ollama_base_url: str
    request_timeout_seconds: int


class KnowledgeConfig(BaseModel):
    base_url: str
    request_timeout_seconds: int


class AppConfig(BaseModel):
    repo_root: Path
    config_dir: Path
    log_file: Path
    host: str
    port: int
    model: ModelConfig
    knowledge: KnowledgeConfig
    system_prompt: str
    allowed_actions_path: Path
    logging: LoggingSettings

    class Config:
        arbitrary_types_allowed = True

    @classmethod
    def from_forge_settings(cls, settings: ForgeSettings, environ: Optional[Mapping[str, str]] = None) -> "AppConfig":
        env = dict(os.environ if environ is None else environ)
        jarvis = settings.services.jarvis
        _require_file(jarvis.actions_file, "Jarvis actions file")
        _require_file(jarvis.system_prompt_path, "Jarvis system prompt")
        log_file = Path(env.get("JARVIS_LOG_FILE") or settings.logging.directory / "jarvis-agent.log").resolve()
        return cls(
            repo_root=settings.home,
            config_dir=settings.config_dir,
            log_file=log_file,
            host=jarvis.host,
            port=jarvis.port,
            model=ModelConfig(
                default_model=jarvis.model_runtime.model,
                ollama_base_url=str(jarvis.model_runtime.base_url).rstrip("/"),
                request_timeout_seconds=jarvis.model_runtime.request_timeout_seconds,
            ),
            knowledge=KnowledgeConfig(
                base_url=str(jarvis.knowledge_base_url).rstrip("/"),
                request_timeout_seconds=jarvis.knowledge.request_timeout_seconds,
            ),
            system_prompt=jarvis.system_prompt_path.read_text(encoding="utf-8"),
            allowed_actions_path=jarvis.actions_file,
            logging=settings.logging,
        )


def load_app_config(config_file: Optional[Path] = None, environ: Optional[Mapping[str, str]] = None) -> AppConfig:
    env = dict(os.environ if environ is None else environ)
    settings = load_forge_settings(config_file=config_file, environ=env)
    return AppConfig.from_forge_settings(settings, env)


def load_forge_settings(config_file: Optional[Path] = None, environ: Optional[Mapping[str, str]] = None) -> ForgeSettings:
    env = dict(os.environ if environ is None else environ)
    root = forge_ai_home(env)
    base_env = _default_env(root, env)
    selected = _select_config_file(root, base_env, config_file)
    data = _load_yaml_mapping(selected) if selected and selected.exists() else {}
    forge_ai = _forge_ai_data(data)

    base_env["FORGE_CONFIG_DIR"] = str(
        Path(_expand(str(forge_ai.get("config-dir") or forge_ai.get("config_dir") or base_env["FORGE_CONFIG_DIR"]), base_env)).resolve()
    )
    base_env["FORGE_RUNTIME_DIR"] = str(
        Path(_expand(str(forge_ai.get("runtime-dir") or forge_ai.get("runtime_dir") or base_env["FORGE_RUNTIME_DIR"]), base_env)).resolve()
    )
    base_env["FORGE_WORKSPACE_ROOT"] = str(
        Path(_expand(str(forge_ai.get("workspace-root") or forge_ai.get("workspace_root") or base_env["FORGE_WORKSPACE_ROOT"]), base_env)).resolve()
    )

    raw = _jarvis_settings_payload(forge_ai, base_env)
    _apply_jarvis_env_overrides(raw, base_env)
    return ForgeSettings.parse_obj(raw)


def forge_ai_home(env: Optional[Mapping[str, str]] = None) -> Path:
    values = dict(os.environ if env is None else env)
    configured = values.get("FORGE_AI_HOME")
    if configured:
        return Path(configured).expanduser().resolve()
    for candidate in [Path.cwd().resolve(), Path(__file__).resolve(), *Path(__file__).resolve().parents]:
        if candidate.is_file():
            continue
        if (candidate / "config" / "forge-ai.yaml").is_file() and (candidate / "services").is_dir():
            return candidate
        if (candidate / "config" / "services.yaml").is_file():
            return candidate
    return Path(__file__).resolve().parents[4]


def _select_config_file(root: Path, env: Mapping[str, str], explicit: Optional[Path]) -> Optional[Path]:
    if explicit is not None:
        return explicit.expanduser().resolve()
    configured = env.get("FORGE_CONFIG_FILE")
    if configured:
        return Path(_expand(configured, env)).expanduser().resolve()
    candidates = [
        Path(env["FORGE_CONFIG_DIR"]) / "forge-ai.yaml",
        root / "config" / "forge-ai.yaml",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate.resolve()
    return candidates[-1].resolve()


def _default_env(root: Path, env: Mapping[str, str]) -> Dict[str, str]:
    values = dict(env)
    values.setdefault("FORGE_AI_HOME", str(root))
    values.setdefault("FORGE_CONFIG_DIR", str(root / "config"))
    values.setdefault("FORGE_RUNTIME_DIR", str(root / "var"))
    values.setdefault("FORGE_WORKSPACE_ROOT", str(root.parent))
    return values


def _expand(value: str, env: Mapping[str, str]) -> str:
    expanded = Path(value).expanduser().as_posix() if value.startswith("~") else value
    for _ in range(10):
        next_value = _ENV_PATTERN.sub(lambda match: env.get(match.group(1), match.group(2) or ""), expanded)
        if next_value == expanded:
            return next_value
        expanded = next_value
    return expanded


def _path(value: str, env: Mapping[str, str]) -> Path:
    path = Path(_expand(value, env)).expanduser()
    if path.is_absolute():
        return path.resolve()
    return (Path(env["FORGE_AI_HOME"]) / path).resolve()


def _load_yaml_mapping(path: Path) -> Dict[str, Any]:
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        raise ValueError(f"Forge config is invalid YAML: {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise ValueError(f"Forge config must contain a YAML mapping: {path}")
    return data


def _mapping_field(data: Mapping[str, Any], *names: str) -> Dict[str, Any]:
    for name in names:
        value = data.get(name)
        if isinstance(value, dict):
            return dict(value)
    return {}


def _forge_ai_data(data: Mapping[str, Any]) -> Dict[str, Any]:
    return _mapping_field(_mapping_field(data, "forge"), "ai")


def _jarvis_settings_payload(forge_ai: Mapping[str, Any], env: Mapping[str, str]) -> Dict[str, Any]:
    services = _mapping_field(forge_ai, "services")
    jarvis = _mapping_field(services, "jarvis")
    logging = _mapping_field(forge_ai, "logging")
    model = _mapping_field(jarvis, "model-runtime", "model_runtime")
    knowledge = _mapping_field(jarvis, "knowledge")
    legacy_config_dir = env.get("JARVIS_CONFIG_DIR")
    prompt_base = _path(legacy_config_dir, env) if legacy_config_dir else Path(env["FORGE_CONFIG_DIR"]) / "jarvis"
    return {
        "home": _path(str(forge_ai.get("home") or env["FORGE_AI_HOME"]), env),
        "config_dir": _path(str(forge_ai.get("config-dir") or forge_ai.get("config_dir") or env["FORGE_CONFIG_DIR"]), env),
        "runtime_dir": _path(str(forge_ai.get("runtime-dir") or forge_ai.get("runtime_dir") or env["FORGE_RUNTIME_DIR"]), env),
        "workspace_root": _path(str(forge_ai.get("workspace-root") or forge_ai.get("workspace_root") or env["FORGE_WORKSPACE_ROOT"]), env),
        "logging": {
            "level": logging.get("level", "INFO"),
            "console_enabled": logging.get("console-enabled", logging.get("console_enabled", True)),
            "file_enabled": logging.get("file-enabled", logging.get("file_enabled", True)),
            "directory": _path(str(logging.get("directory") or "${FORGE_RUNTIME_DIR}/logs"), env),
        },
        "services": {
            "jarvis": {
                "host": str(jarvis.get("host") or "127.0.0.1"),
                "port": int(jarvis.get("port") or 7071),
                "knowledge_base_url": str(jarvis.get("knowledge-base-url") or jarvis.get("knowledge_base_url") or "http://127.0.0.1:7081"),
                "model_runtime": {
                    "provider": str(model.get("provider") or "ollama"),
                    "base_url": str(model.get("base-url") or model.get("base_url") or "http://localhost:11434"),
                    "model": str(model.get("model") or "qwen2.5-coder:14b"),
                    "request_timeout_seconds": int(model.get("request-timeout-seconds") or model.get("request_timeout_seconds") or 120),
                },
                "knowledge": {
                    "request_timeout_seconds": int(knowledge.get("request-timeout-seconds") or knowledge.get("request_timeout_seconds") or 120),
                },
                "actions_file": _path(str(jarvis.get("actions-file") or jarvis.get("actions_file") or prompt_base / "allowed-actions.yaml"), env),
                "system_prompt_path": _path(str(jarvis.get("system-prompt-path") or jarvis.get("system_prompt_path") or prompt_base / "system-prompt.md"), env),
            }
        },
    }


def _apply_jarvis_env_overrides(raw: Dict[str, Any], env: Mapping[str, str]) -> None:
    jarvis = raw["services"]["jarvis"]
    if env.get("JARVIS_CONFIG_DIR"):
        config_dir = _path(env["JARVIS_CONFIG_DIR"], env)
        jarvis["actions_file"] = config_dir / "allowed-actions.yaml"
        jarvis["system_prompt_path"] = config_dir / "system-prompt.md"
    if env.get("JARVIS_HOST"):
        jarvis["host"] = env["JARVIS_HOST"]
    if env.get("JARVIS_PORT"):
        jarvis["port"] = int(env["JARVIS_PORT"])
    if env.get("JARVIS_KNOWLEDGE_BASE_URL"):
        jarvis["knowledge_base_url"] = env["JARVIS_KNOWLEDGE_BASE_URL"]
    if env.get("JARVIS_MODEL"):
        jarvis["model_runtime"]["model"] = env["JARVIS_MODEL"]
    if env.get("JARVIS_OLLAMA_BASE_URL"):
        jarvis["model_runtime"]["base_url"] = env["JARVIS_OLLAMA_BASE_URL"]
    if env.get("JARVIS_REQUEST_TIMEOUT_SECONDS"):
        jarvis["model_runtime"]["request_timeout_seconds"] = int(env["JARVIS_REQUEST_TIMEOUT_SECONDS"])


def _require_file(path: Path, label: str) -> None:
    if not path.is_file():
        raise ValueError(f"{label} does not exist: {path}")
