from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class AppConfig:
    module_dir: Path
    host: str
    port: int
    local_config_path: Path
    store_path: Path
    inventory_auto_refresh_enabled: bool = True
    inventory_auto_refresh_interval_seconds: int = 300
    analysis_enabled: bool = True
    analysis_provider: str = "ollama"
    analysis_base_url: str = "http://localhost:11434"
    analysis_model: str = "qwen2.5-coder:14b"
    analysis_request_timeout_seconds: int = 180
    analysis_context_tokens: int = 8192
    analysis_max_file_chars: int = 60000
    analysis_max_chunk_chars: int = 20000
    analysis_concurrency: int = 1
    analysis_max_attempts_per_file: int = 3
    analysis_repair_attempts_per_file: int = 1

    @property
    def analysis_retry_attempts(self) -> int:
        return self.analysis_max_attempts_per_file


def load_app_config() -> AppConfig:
    module_dir = Path(os.environ.get("KNOWLEDGE_MODULE_DIR", Path(__file__).resolve().parents[4])).resolve()
    defaults_path = module_dir / "config" / "knowledge.defaults.yaml"
    defaults = yaml.safe_load(defaults_path.read_text(encoding="utf-8")) if defaults_path.exists() else {}
    knowledge = defaults.get("knowledge") or {}
    service = knowledge.get("service") or {}
    inventory = knowledge.get("inventory") or {}
    analysis = defaults.get("analysis") or {}
    store = Path(os.environ.get("KNOWLEDGE_STORE_PATH") or inventory.get("store_path") or "infrastructure/knowledge/var/knowledge.sqlite")
    if not store.is_absolute():
        store = module_dir.parents[1] / store
    return AppConfig(
        module_dir=module_dir,
        host=os.environ.get("KNOWLEDGE_HOST") or str(service.get("host") or "127.0.0.1"),
        port=int(os.environ.get("KNOWLEDGE_PORT") or service.get("port") or 7081),
        local_config_path=Path(os.environ.get("KNOWLEDGE_CONFIG") or module_dir / "config" / "knowledge-sources.yaml"),
        store_path=store,
        inventory_auto_refresh_enabled=str(
            os.environ.get("KNOWLEDGE_INVENTORY_AUTO_REFRESH_ENABLED")
            or inventory.get("auto_refresh_enabled")
            or True
        ).lower() != "false",
        inventory_auto_refresh_interval_seconds=max(1, int(
            os.environ.get("KNOWLEDGE_INVENTORY_AUTO_REFRESH_INTERVAL_SECONDS")
            or inventory.get("auto_refresh_interval_seconds")
            or 300
        )),
        analysis_enabled=str(os.environ.get("KNOWLEDGE_ANALYSIS_ENABLED", analysis.get("enabled", True))).lower() != "false",
        analysis_provider=str(os.environ.get("KNOWLEDGE_ANALYSIS_PROVIDER") or analysis.get("provider") or "ollama"),
        analysis_base_url=str(os.environ.get("KNOWLEDGE_ANALYSIS_BASE_URL") or analysis.get("base_url") or "http://localhost:11434"),
        analysis_model=str(os.environ.get("KNOWLEDGE_ANALYSIS_MODEL") or analysis.get("model") or "qwen2.5-coder:14b"),
        analysis_request_timeout_seconds=int(os.environ.get("KNOWLEDGE_ANALYSIS_REQUEST_TIMEOUT_SECONDS") or analysis.get("request_timeout_seconds") or 180),
        analysis_context_tokens=int(os.environ.get("KNOWLEDGE_ANALYSIS_CONTEXT_TOKENS") or analysis.get("context_tokens") or 8192),
        analysis_max_file_chars=int(os.environ.get("KNOWLEDGE_ANALYSIS_MAX_FILE_CHARS") or analysis.get("max_file_chars") or 60000),
        analysis_max_chunk_chars=int(os.environ.get("KNOWLEDGE_ANALYSIS_MAX_CHARS") or analysis.get("max_chunk_chars") or 20000),
        analysis_concurrency=int(os.environ.get("KNOWLEDGE_ANALYSIS_CONCURRENCY") or analysis.get("concurrency") or 1),
        analysis_max_attempts_per_file=max(1, int(
            os.environ.get("KNOWLEDGE_ANALYSIS_MAX_ATTEMPTS_PER_FILE")
            or os.environ.get("KNOWLEDGE_ANALYSIS_RETRY_ATTEMPTS")
            or analysis.get("max_attempts_per_file")
            or analysis.get("retry_attempts")
            or 3
        )),
        analysis_repair_attempts_per_file=max(0, int(
            os.environ.get("KNOWLEDGE_ANALYSIS_REPAIR_ATTEMPTS_PER_FILE")
            or analysis.get("repair_attempts_per_file")
            or 1
        )),
    )
