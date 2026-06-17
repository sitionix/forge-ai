from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, Optional

import yaml


def knowledge_module_dir() -> Path:
    return Path(os.environ.get("KNOWLEDGE_MODULE_DIR", Path(__file__).resolve().parents[4])).resolve()


def load_knowledge_defaults(defaults_path: Optional[Path] = None) -> Dict[str, Any]:
    path = defaults_path or knowledge_module_dir() / "config" / "knowledge.defaults.yaml"
    if not path.exists():
        return {}
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}
