from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional, Tuple
from urllib.parse import urlparse

import yaml
from pydantic import AnyHttpUrl, BaseModel, Field, root_validator, validator

_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")
DEFAULT_GENERATIVE_MODEL = "qwen2.5-coder:14b"
DEFAULT_GENERATIVE_CONTEXT_TOKENS = 32768
DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS = 180


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


class KnowledgeStorageSettings(BaseModel):
    sqlite_path: Path
    retention_inventory_build_days: int = Field(default=30, ge=1)
    retention_analysis_job_days: int = Field(default=30, ge=1)
    retention_analysis_diagnostic_days: int = Field(default=30, ge=1)
    retention_keep_completed_jobs: int = Field(default=50, ge=1)


class InventorySettings(BaseModel):
    source_catalog_path: Path
    service_catalog_path: Path
    auto_refresh_enabled: bool = True
    auto_refresh_interval_seconds: int = Field(default=60, ge=1)


class GenerativeSettings(BaseModel):
    provider: str = "ollama"
    base_url: AnyHttpUrl = "http://localhost:11434"
    model: str = Field(default=DEFAULT_GENERATIVE_MODEL, min_length=1)
    context_tokens: int = Field(default=DEFAULT_GENERATIVE_CONTEXT_TOKENS, ge=1024)

    @validator("provider")
    def require_ollama_provider(cls, value: str) -> str:
        normalized = str(value or "").strip().lower()
        if normalized != "ollama":
            raise ValueError("generative provider must be ollama")
        return normalized

    @validator("base_url")
    def require_local_model_runtime(cls, value: AnyHttpUrl) -> AnyHttpUrl:
        parsed = urlparse(str(value))
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("generative base_url must point to localhost")
        return value


class AnalysisSettings(BaseModel):
    enabled: bool = True
    request_timeout_seconds: int = Field(default=DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS, ge=1)
    ai_call_timeout_seconds: Optional[int] = Field(default=None, ge=1)
    per_file_timeout_seconds: int = Field(default=120, ge=1)
    stall_threshold_seconds: int = Field(default=300, ge=1)
    max_file_chars: int = Field(default=60000, ge=1)
    max_chunk_chars: int = Field(default=20000, ge=1)
    concurrency: int = Field(default=1, ge=1)
    queue_capacity: int = Field(default=4, ge=1)
    shutdown_grace_seconds: float = Field(default=5.0, ge=0.1)
    max_attempts_per_file: int = Field(default=3, ge=1)
    repair_attempts_per_file: int = Field(default=1, ge=0)

class SemanticSettings(BaseModel):
    enabled: bool = True
    auto_build_enabled: bool = True
    auto_build_interval_seconds: float = Field(default=60.0, ge=0.1)
    failed_retry_backoff_seconds: float = Field(default=300.0, ge=0.0)
    building_stale_after_seconds: float = Field(default=300.0, ge=1.0)
    provider: str = "ollama"
    embedding_model: str = Field(default="embeddinggemma", min_length=1)
    ollama_base_url: AnyHttpUrl = "http://127.0.0.1:11434"
    request_timeout_seconds: int = Field(default=30, ge=1)
    batch_size: int = Field(default=16, ge=1)
    max_document_chars: int = Field(default=4000, ge=1)
    max_edges_per_document: int = Field(default=20, ge=0)
    max_documents_per_build: int = Field(default=20000, ge=1)
    max_search_vectors: int = Field(default=50000, ge=1)
    semantic_top_k: int = Field(default=20, ge=1)
    min_similarity: float = Field(default=0.35, ge=0.0, le=1.0)
    query_timeout_ms: int = Field(default=1500, ge=1)

    @validator("ollama_base_url")
    def require_local_embedding_runtime(cls, value: AnyHttpUrl) -> AnyHttpUrl:
        parsed = urlparse(str(value))
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("knowledge semantic ollama_base_url must point to localhost")
        return value


class KnowledgeSettings(BaseModel):
    host: str = Field(min_length=1)
    port: int = Field(ge=1, le=65535)
    storage: KnowledgeStorageSettings
    inventory: InventorySettings
    analysis: AnalysisSettings
    semantic: SemanticSettings = Field(default_factory=SemanticSettings)


class FlowExplanationQuerySettings(BaseModel):
    request_timeout_seconds: int = Field(default=DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS, ge=1)


class QuerySettings(BaseModel):
    default_response_language: str = Field(default="en", min_length=2)
    flow_explanation: FlowExplanationQuerySettings = Field(default_factory=FlowExplanationQuerySettings)

    @validator("default_response_language")
    def normalize_default_response_language(cls, value: str) -> str:
        normalized = str(value or "").strip().lower().split("-", 1)[0]
        if not re.match(r"^[a-z]{2,3}$", normalized) or normalized == "und":
            raise ValueError("query.default_response_language must be a language code")
        return normalized


class ServicesSettings(BaseModel):
    knowledge: KnowledgeSettings


class ForgeSettings(BaseModel):
    home: Path
    config_dir: Path
    runtime_dir: Path
    workspace_root: Optional[Path]
    logging: LoggingSettings
    generative: GenerativeSettings = Field(default_factory=GenerativeSettings)
    query: QuerySettings = Field(default_factory=QuerySettings)
    services: ServicesSettings

    @root_validator
    def ensure_runtime_dirs_are_absolute(cls, values: Dict[str, Any]) -> Dict[str, Any]:
        for key in ("home", "config_dir", "runtime_dir"):
            path = values.get(key)
            if isinstance(path, Path) and not path.is_absolute():
                values[key] = path.resolve()
        workspace_root = values.get("workspace_root")
        if isinstance(workspace_root, Path) and not workspace_root.is_absolute():
            values["workspace_root"] = workspace_root.resolve()
        return values


class AppConfig(BaseModel):
    module_dir: Path
    host: str
    port: int
    local_config_path: Path
    store_path: Path
    inventory_auto_refresh_enabled: bool = True
    inventory_auto_refresh_interval_seconds: int = 60
    analysis_enabled: bool = True
    analysis_provider: str = "ollama"
    analysis_base_url: str = "http://localhost:11434"
    analysis_model: str = DEFAULT_GENERATIVE_MODEL
    analysis_request_timeout_seconds: int = DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS
    analysis_ai_call_timeout_seconds: int = DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS
    flow_explanation_request_timeout_seconds: int = DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS
    query_default_response_language: str = "en"
    analysis_per_file_timeout_seconds: int = 120
    analysis_stall_threshold_seconds: int = 300
    analysis_context_tokens: int = DEFAULT_GENERATIVE_CONTEXT_TOKENS
    analysis_max_file_chars: int = 60000
    analysis_max_chunk_chars: int = 20000
    analysis_concurrency: int = 1
    analysis_queue_capacity: int = 4
    analysis_shutdown_grace_seconds: float = 5.0
    analysis_max_attempts_per_file: int = 3
    analysis_repair_attempts_per_file: int = 1
    semantic_enabled: bool = True
    semantic_auto_build_enabled: bool = True
    semantic_auto_build_interval_seconds: float = 60.0
    semantic_failed_retry_backoff_seconds: float = 300.0
    semantic_building_stale_after_seconds: float = 300.0
    semantic_provider: str = "ollama"
    semantic_embedding_model: str = "embeddinggemma"
    semantic_ollama_base_url: str = "http://127.0.0.1:11434"
    semantic_request_timeout_seconds: int = 30
    semantic_batch_size: int = 16
    semantic_max_document_chars: int = 4000
    semantic_max_edges_per_document: int = 20
    semantic_max_documents_per_build: int = 20000
    semantic_max_search_vectors: int = 50000
    semantic_top_k: int = 20
    semantic_min_similarity: float = 0.35
    semantic_query_timeout_ms: int = 1500
    retention_inventory_build_days: int = 30
    retention_analysis_job_days: int = 30
    retention_analysis_diagnostic_days: int = 30
    retention_keep_completed_jobs: int = 50
    logging: LoggingSettings = Field(default_factory=lambda: LoggingSettings(directory=forge_ai_home() / "var" / "logs"))
    runtime_dir: Path = Field(default_factory=lambda: forge_ai_home() / "var")
    workspace_root: Optional[Path] = None
    config_dir: Path = Field(default_factory=lambda: forge_ai_home() / "config")

    class Config:
        arbitrary_types_allowed = True

    def __init__(self, *args: Any, **data: Any) -> None:
        if args:
            fields = ["module_dir", "host", "port", "local_config_path", "store_path"]
            for field, value in zip(fields, args):
                data.setdefault(field, value)
        module_dir = Path(data.get("module_dir", knowledge_module_dir()))
        data.setdefault("runtime_dir", module_dir / "var")
        data.setdefault("config_dir", module_dir / "config")
        data.setdefault("workspace_root", module_dir.parent)
        data.setdefault("logging", LoggingSettings(directory=module_dir / "var" / "logs"))
        super().__init__(**data)

    @property
    def analysis_retry_attempts(self) -> int:
        return self.analysis_max_attempts_per_file

    @validator("semantic_ollama_base_url")
    def require_local_semantic_runtime(cls, value: str) -> str:
        parsed = urlparse(str(value))
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("knowledge semantic_ollama_base_url must point to localhost")
        return str(value).rstrip("/")

    @classmethod
    def from_forge_settings(cls, settings: ForgeSettings, module_dir: Optional[Path] = None) -> "AppConfig":
        knowledge = settings.services.knowledge
        generative = settings.generative
        analysis = knowledge.analysis
        semantic = knowledge.semantic
        return cls(
            module_dir=(module_dir or knowledge_module_dir()).resolve(),
            host=knowledge.host,
            port=knowledge.port,
            local_config_path=knowledge.inventory.source_catalog_path,
            store_path=knowledge.storage.sqlite_path,
            inventory_auto_refresh_enabled=knowledge.inventory.auto_refresh_enabled,
            inventory_auto_refresh_interval_seconds=knowledge.inventory.auto_refresh_interval_seconds,
            analysis_enabled=analysis.enabled,
            analysis_provider=generative.provider,
            analysis_base_url=str(generative.base_url).rstrip("/"),
            analysis_model=generative.model,
            analysis_request_timeout_seconds=analysis.request_timeout_seconds,
            analysis_ai_call_timeout_seconds=analysis.ai_call_timeout_seconds or analysis.request_timeout_seconds,
            flow_explanation_request_timeout_seconds=settings.query.flow_explanation.request_timeout_seconds,
            query_default_response_language=settings.query.default_response_language,
            analysis_per_file_timeout_seconds=analysis.per_file_timeout_seconds,
            analysis_stall_threshold_seconds=analysis.stall_threshold_seconds,
            analysis_context_tokens=generative.context_tokens,
            analysis_max_file_chars=analysis.max_file_chars,
            analysis_max_chunk_chars=analysis.max_chunk_chars,
            analysis_concurrency=analysis.concurrency,
            analysis_queue_capacity=analysis.queue_capacity,
            analysis_shutdown_grace_seconds=analysis.shutdown_grace_seconds,
            analysis_max_attempts_per_file=analysis.max_attempts_per_file,
            analysis_repair_attempts_per_file=analysis.repair_attempts_per_file,
            semantic_enabled=semantic.enabled,
            semantic_auto_build_enabled=semantic.auto_build_enabled,
            semantic_auto_build_interval_seconds=semantic.auto_build_interval_seconds,
            semantic_failed_retry_backoff_seconds=semantic.failed_retry_backoff_seconds,
            semantic_building_stale_after_seconds=semantic.building_stale_after_seconds,
            semantic_provider=semantic.provider,
            semantic_embedding_model=semantic.embedding_model,
            semantic_ollama_base_url=str(semantic.ollama_base_url).rstrip("/"),
            semantic_request_timeout_seconds=semantic.request_timeout_seconds,
            semantic_batch_size=semantic.batch_size,
            semantic_max_document_chars=semantic.max_document_chars,
            semantic_max_edges_per_document=semantic.max_edges_per_document,
            semantic_max_documents_per_build=semantic.max_documents_per_build,
            semantic_max_search_vectors=semantic.max_search_vectors,
            semantic_top_k=semantic.semantic_top_k,
            semantic_min_similarity=semantic.min_similarity,
            semantic_query_timeout_ms=semantic.query_timeout_ms,
            retention_inventory_build_days=knowledge.storage.retention_inventory_build_days,
            retention_analysis_job_days=knowledge.storage.retention_analysis_job_days,
            retention_analysis_diagnostic_days=knowledge.storage.retention_analysis_diagnostic_days,
            retention_keep_completed_jobs=knowledge.storage.retention_keep_completed_jobs,
            logging=settings.logging,
            runtime_dir=settings.runtime_dir,
            workspace_root=settings.workspace_root,
            config_dir=settings.config_dir,
        )


def load_app_config(config_file: Optional[Path] = None, environ: Optional[Mapping[str, str]] = None) -> AppConfig:
    settings = load_forge_settings(config_file=config_file, environ=environ)
    return AppConfig.from_forge_settings(settings)


def load_forge_settings(config_file: Optional[Path] = None, environ: Optional[Mapping[str, str]] = None) -> ForgeSettings:
    env = dict(os.environ if environ is None else environ)
    root = forge_ai_home(env=env)
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

    raw = _knowledge_settings_payload(forge_ai, base_env)
    _apply_knowledge_env_overrides(raw, base_env)
    return ForgeSettings.parse_obj(raw)


def knowledge_module_dir() -> Path:
    return Path(__file__).resolve().parents[2]


def forge_ai_home(environ: Optional[Mapping[str, str]] = None, env: Optional[Mapping[str, str]] = None) -> Path:
    values = dict(os.environ if environ is None and env is None else (environ or env or {}))
    configured = values.get("FORGE_AI_HOME")
    if configured:
        return Path(configured).expanduser().resolve()
    for candidate in [Path.cwd().resolve(), knowledge_module_dir(), *knowledge_module_dir().parents]:
        if (candidate / "config" / "forge-ai.yaml").is_file() and (candidate / "services").is_dir():
            return candidate
        if (candidate / "config" / "services.yaml").is_file():
            return candidate
    return knowledge_module_dir().parents[1].resolve()


def _select_config_file(root: Path, env: Mapping[str, str], explicit: Optional[Path]) -> Optional[Path]:
    if explicit is not None:
        return explicit.expanduser().resolve()
    configured = env.get("FORGE_CONFIG_FILE")
    if configured:
        return Path(_expand(configured, env)).expanduser().resolve()
    config_dir = Path(env["FORGE_CONFIG_DIR"])
    candidates = [
        config_dir / "forge-ai.yaml",
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


def _knowledge_settings_payload(forge_ai: Mapping[str, Any], env: Mapping[str, str]) -> Dict[str, Any]:
    services = _mapping_field(forge_ai, "services")
    knowledge = _mapping_field(services, "knowledge")
    logging = _mapping_field(forge_ai, "logging")
    generative = _mapping_field(forge_ai, "generative")
    query = _mapping_field(forge_ai, "query")
    flow_explanation = _mapping_field(query, "flow-explanation", "flow_explanation")
    inventory = _mapping_field(knowledge, "inventory")
    storage = _mapping_field(knowledge, "storage")
    analysis = _mapping_field(knowledge, "analysis")
    semantic = _mapping_field(knowledge, "semantic")
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
        "generative": {
            "provider": str(generative.get("provider")) if "provider" in generative else "ollama",
            "base_url": str(generative.get("base-url") or generative.get("base_url") or "http://localhost:11434"),
            "model": str(generative.get("model") or DEFAULT_GENERATIVE_MODEL),
            "context_tokens": _int_config(
                generative.get("context-tokens")
                or generative.get("context_tokens"),
                env,
                DEFAULT_GENERATIVE_CONTEXT_TOKENS,
            ),
        },
        "query": {
            "default_response_language": str(
                query.get("default-response-language")
                or query.get("default_response_language")
                or "en"
            ),
            "flow_explanation": {
                "request_timeout_seconds": _int_config(
                    flow_explanation.get("request-timeout-seconds")
                    or flow_explanation.get("request_timeout_seconds"),
                    env,
                    DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS,
                )
            }
        },
        "services": {
            "knowledge": {
                "host": str(knowledge.get("host") or "127.0.0.1"),
                "port": int(knowledge.get("port") or 7081),
                "storage": {
                    "sqlite_path": _path(
                        str(storage.get("sqlite-path") or storage.get("sqlite_path") or "${FORGE_RUNTIME_DIR}/knowledge/knowledge.sqlite"), env
                    ),
                },
                "inventory": {
                    "source_catalog_path": _path(
                        str(
                            inventory.get("source-catalog-path")
                            or inventory.get("source_catalog_path")
                            or "${FORGE_CONFIG_DIR}/knowledge/knowledge-sources.yaml"
                        ),
                        env,
                    ),
                    "service_catalog_path": _path(
                        str(inventory.get("service-catalog-path") or inventory.get("service_catalog_path") or "${FORGE_CONFIG_DIR}/services.yaml"), env
                    ),
                    "auto_refresh_enabled": _bool(inventory.get("auto-refresh-enabled", inventory.get("auto_refresh_enabled", True))),
                    "auto_refresh_interval_seconds": int(
                        inventory.get("auto-refresh-interval-seconds") or inventory.get("auto_refresh_interval_seconds") or 60
                    ),
                },
                "analysis": {
                    "enabled": _bool(analysis.get("enabled", True)),
                    "request_timeout_seconds": int(
                        analysis.get("request-timeout-seconds")
                        or analysis.get("request_timeout_seconds")
                        or DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS
                    ),
                    "ai_call_timeout_seconds": int(
                        analysis.get("ai-call-timeout-seconds")
                        or analysis.get("ai_call_timeout_seconds")
                        or analysis.get("request-timeout-seconds")
                        or analysis.get("request_timeout_seconds")
                        or DEFAULT_FLOW_EXPLANATION_REQUEST_DEADLINE_SECONDS
                    ),
                    "per_file_timeout_seconds": int(analysis.get("per-file-timeout-seconds") or analysis.get("per_file_timeout_seconds") or 120),
                    "stall_threshold_seconds": int(analysis.get("stall-threshold-seconds") or analysis.get("stall_threshold_seconds") or 300),
                    "max_file_chars": int(analysis.get("max-file-chars") or analysis.get("max_file_chars") or 60000),
                    "max_chunk_chars": int(analysis.get("max-chunk-chars") or analysis.get("max_chunk_chars") or 20000),
                    "concurrency": int(analysis.get("concurrency") or 1),
                    "queue_capacity": int(analysis.get("queue-capacity") or analysis.get("queue_capacity") or 4),
                    "shutdown_grace_seconds": float(analysis.get("shutdown-grace-seconds") or analysis.get("shutdown_grace_seconds") or 5.0),
                    "max_attempts_per_file": int(analysis.get("max-attempts-per-file") or analysis.get("max_attempts_per_file") or 3),
                    "repair_attempts_per_file": int(analysis.get("repair-attempts-per-file") or analysis.get("repair_attempts_per_file") or 1),
                },
                "semantic": {
                    "enabled": _bool(semantic.get("enabled", True)),
                    "auto_build_enabled": _bool(semantic.get("auto-build-enabled", semantic.get("auto_build_enabled", True))),
                    "auto_build_interval_seconds": float(
                        semantic.get("auto-build-interval-seconds") or semantic.get("auto_build_interval_seconds") or 60.0
                    ),
                    "failed_retry_backoff_seconds": float(
                        semantic.get("failed-retry-backoff-seconds") or semantic.get("failed_retry_backoff_seconds") or 300.0
                    ),
                    "building_stale_after_seconds": float(
                        semantic.get("building-stale-after-seconds") or semantic.get("building_stale_after_seconds") or 300.0
                    ),
                    "provider": str(semantic.get("provider") or "ollama"),
                    "embedding_model": str(semantic.get("embedding-model") or semantic.get("embedding_model") or "embeddinggemma"),
                    "ollama_base_url": str(
                        semantic.get("ollama-base-url") or semantic.get("ollama_base_url") or "http://127.0.0.1:11434"
                    ),
                    "request_timeout_seconds": int(
                        semantic.get("request-timeout-seconds") or semantic.get("request_timeout_seconds") or 30
                    ),
                    "batch_size": int(semantic.get("batch-size") or semantic.get("batch_size") or 16),
                    "max_document_chars": int(semantic.get("max-document-chars") or semantic.get("max_document_chars") or 4000),
                    "max_edges_per_document": int(
                        semantic.get("max-edges-per-document") or semantic.get("max_edges_per_document") or 20
                    ),
                    "max_documents_per_build": int(
                        semantic.get("max-documents-per-build") or semantic.get("max_documents_per_build") or 20000
                    ),
                    "max_search_vectors": int(semantic.get("max-search-vectors") or semantic.get("max_search_vectors") or 50000),
                    "semantic_top_k": int(semantic.get("semantic-top-k") or semantic.get("semantic_top_k") or 20),
                    "min_similarity": float(semantic.get("min-similarity") or semantic.get("min_similarity") or 0.35),
                    "query_timeout_ms": int(semantic.get("query-timeout-ms") or semantic.get("query_timeout_ms") or 1500),
                },
            }
        },
    }


def _apply_knowledge_env_overrides(raw: Dict[str, Any], env: Mapping[str, str]) -> None:
    generative = raw["generative"]
    generative_env_map: Dict[str, Tuple[str, Callable[[str], object]]] = {
        "FORGE_GENERATIVE_PROVIDER": ("provider", str),
        "FORGE_GENERATIVE_BASE_URL": ("base_url", str),
        "FORGE_GENERATIVE_MODEL": ("model", str),
        "FORGE_GENERATIVE_CONTEXT_TOKENS": ("context_tokens", int),
    }
    for name, (field, converter) in generative_env_map.items():
        if env.get(name):
            generative[field] = converter(env[name])
    if env.get("FORGE_FLOW_EXPLANATION_REQUEST_TIMEOUT_SECONDS"):
        raw["query"]["flow_explanation"]["request_timeout_seconds"] = int(env["FORGE_FLOW_EXPLANATION_REQUEST_TIMEOUT_SECONDS"])
    if env.get("FORGE_QUERY_DEFAULT_RESPONSE_LANGUAGE"):
        raw["query"]["default_response_language"] = env["FORGE_QUERY_DEFAULT_RESPONSE_LANGUAGE"]

    knowledge = raw["services"]["knowledge"]
    if env.get("KNOWLEDGE_HOST"):
        knowledge["host"] = env["KNOWLEDGE_HOST"]
    if env.get("KNOWLEDGE_PORT"):
        knowledge["port"] = int(env["KNOWLEDGE_PORT"])
    if env.get("KNOWLEDGE_CONFIG"):
        knowledge["inventory"]["source_catalog_path"] = _path(env["KNOWLEDGE_CONFIG"], env)
    if env.get("KNOWLEDGE_STORE_PATH"):
        knowledge["storage"]["sqlite_path"] = _path(env["KNOWLEDGE_STORE_PATH"], env)
    if env.get("KNOWLEDGE_INVENTORY_AUTO_REFRESH_ENABLED"):
        knowledge["inventory"]["auto_refresh_enabled"] = env["KNOWLEDGE_INVENTORY_AUTO_REFRESH_ENABLED"].lower() != "false"
    if env.get("KNOWLEDGE_INVENTORY_AUTO_REFRESH_INTERVAL_SECONDS"):
        knowledge["inventory"]["auto_refresh_interval_seconds"] = int(env["KNOWLEDGE_INVENTORY_AUTO_REFRESH_INTERVAL_SECONDS"])

    analysis = knowledge["analysis"]
    env_map: Dict[str, Tuple[str, Callable[[str], object]]] = {
        "KNOWLEDGE_ANALYSIS_ENABLED": ("enabled", lambda value: value.lower() != "false"),
        "KNOWLEDGE_ANALYSIS_REQUEST_TIMEOUT_SECONDS": ("request_timeout_seconds", int),
        "KNOWLEDGE_ANALYSIS_AI_CALL_TIMEOUT_SECONDS": ("ai_call_timeout_seconds", int),
        "KNOWLEDGE_ANALYSIS_PER_FILE_TIMEOUT_SECONDS": ("per_file_timeout_seconds", int),
        "KNOWLEDGE_ANALYSIS_STALL_THRESHOLD_SECONDS": ("stall_threshold_seconds", int),
        "KNOWLEDGE_ANALYSIS_MAX_FILE_CHARS": ("max_file_chars", int),
        "KNOWLEDGE_ANALYSIS_MAX_CHARS": ("max_chunk_chars", int),
        "KNOWLEDGE_ANALYSIS_CONCURRENCY": ("concurrency", int),
        "KNOWLEDGE_ANALYSIS_MAX_ATTEMPTS_PER_FILE": ("max_attempts_per_file", int),
        "KNOWLEDGE_ANALYSIS_REPAIR_ATTEMPTS_PER_FILE": ("repair_attempts_per_file", int),
    }
    for name, (field, converter) in env_map.items():
        if env.get(name):
            analysis[field] = converter(env[name])
    if env.get("KNOWLEDGE_ANALYSIS_RETRY_ATTEMPTS") and not env.get("KNOWLEDGE_ANALYSIS_MAX_ATTEMPTS_PER_FILE"):
        analysis["max_attempts_per_file"] = int(env["KNOWLEDGE_ANALYSIS_RETRY_ATTEMPTS"])

    semantic = knowledge["semantic"]
    semantic_env_map: Dict[str, Tuple[str, Callable[[str], object]]] = {
        "KNOWLEDGE_SEMANTIC_ENABLED": ("enabled", lambda value: value.lower() != "false"),
        "KNOWLEDGE_SEMANTIC_AUTO_BUILD_ENABLED": ("auto_build_enabled", lambda value: value.lower() != "false"),
        "KNOWLEDGE_SEMANTIC_AUTO_BUILD_INTERVAL_SECONDS": ("auto_build_interval_seconds", float),
        "KNOWLEDGE_SEMANTIC_FAILED_RETRY_BACKOFF_SECONDS": ("failed_retry_backoff_seconds", float),
        "KNOWLEDGE_SEMANTIC_BUILDING_STALE_AFTER_SECONDS": ("building_stale_after_seconds", float),
        "KNOWLEDGE_SEMANTIC_PROVIDER": ("provider", str),
        "KNOWLEDGE_SEMANTIC_EMBEDDING_MODEL": ("embedding_model", str),
        "KNOWLEDGE_SEMANTIC_OLLAMA_BASE_URL": ("ollama_base_url", str),
        "KNOWLEDGE_SEMANTIC_REQUEST_TIMEOUT_SECONDS": ("request_timeout_seconds", int),
        "KNOWLEDGE_SEMANTIC_BATCH_SIZE": ("batch_size", int),
        "KNOWLEDGE_SEMANTIC_MAX_DOCUMENT_CHARS": ("max_document_chars", int),
        "KNOWLEDGE_SEMANTIC_MAX_EDGES_PER_DOCUMENT": ("max_edges_per_document", int),
        "KNOWLEDGE_SEMANTIC_MAX_DOCUMENTS_PER_BUILD": ("max_documents_per_build", int),
        "KNOWLEDGE_SEMANTIC_MAX_SEARCH_VECTORS": ("max_search_vectors", int),
        "KNOWLEDGE_SEMANTIC_TOP_K": ("semantic_top_k", int),
        "KNOWLEDGE_SEMANTIC_MIN_SIMILARITY": ("min_similarity", float),
        "KNOWLEDGE_SEMANTIC_QUERY_TIMEOUT_MS": ("query_timeout_ms", int),
    }
    for name, (field, converter) in semantic_env_map.items():
        if env.get(name):
            semantic[field] = converter(env[name])


def _bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() not in {"0", "false", "no", "off"}
    return bool(value)


def _int_config(value: Any, env: Mapping[str, str], default: int) -> int:
    if value is None or value == "":
        return int(default)
    if isinstance(value, int):
        return value
    return int(_expand(str(value), env))
