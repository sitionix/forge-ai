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


def load_app_config() -> AppConfig:
    module_dir = Path(os.environ.get("KNOWLEDGE_MODULE_DIR", Path(__file__).resolve().parents[4])).resolve()
    defaults_path = module_dir / "config" / "knowledge.defaults.yaml"
    defaults = yaml.safe_load(defaults_path.read_text(encoding="utf-8")) if defaults_path.exists() else {}
    knowledge = defaults.get("knowledge") or {}
    service = knowledge.get("service") or {}
    inventory = knowledge.get("inventory") or {}
    store = Path(os.environ.get("KNOWLEDGE_STORE_PATH") or inventory.get("store_path") or "infrastructure/knowledge/var/knowledge.sqlite")
    if not store.is_absolute():
        store = module_dir.parents[1] / store
    return AppConfig(
        module_dir=module_dir,
        host=os.environ.get("KNOWLEDGE_HOST") or str(service.get("host") or "127.0.0.1"),
        port=int(os.environ.get("KNOWLEDGE_PORT") or service.get("port") or 7081),
        local_config_path=Path(os.environ.get("KNOWLEDGE_CONFIG") or module_dir / "config" / "knowledge-sources.yaml"),
        store_path=store,
    )
