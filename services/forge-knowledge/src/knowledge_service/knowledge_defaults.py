from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any, Dict, Iterator, Optional

import yaml

_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}")


def knowledge_module_dir() -> Path:
    return Path(os.environ.get("KNOWLEDGE_MODULE_DIR", Path(__file__).resolve().parents[2])).resolve()


def forge_ai_home(module_dir: Optional[Path] = None) -> Path:
    configured = os.environ.get("FORGE_AI_HOME")
    if configured:
        return Path(configured).expanduser().resolve()

    module = (module_dir or knowledge_module_dir()).resolve()
    candidates = [Path.cwd().resolve(), module, *module.parents]
    for candidate in candidates:
        if (candidate / "config" / "services.yaml").is_file():
            return candidate
        if (candidate / "pom.xml").is_file() and (candidate / "services" / "forge-nexus" / "boot").is_dir():
            return candidate
        if (candidate / "pom.xml").is_file() and (candidate / "boot" / "src" / "main" / "resources" / "services.yaml").is_file():
            return candidate
    return module.parents[1].resolve() if len(module.parents) > 1 else module


def default_env(module_dir: Optional[Path] = None) -> Dict[str, str]:
    home = forge_ai_home(module_dir)
    values = dict(os.environ)
    values.setdefault("FORGE_AI_HOME", str(home))
    values.setdefault("FORGE_CONFIG_DIR", str(home / "config"))
    values.setdefault("FORGE_RUNTIME_DIR", str(home / "var"))
    values.setdefault("FORGE_WORKSPACE_ROOT", str(home.parent))
    return values


def expand_config_text(value: str, module_dir: Optional[Path] = None) -> str:
    env = default_env(module_dir)
    expanded = os.path.expanduser(value)
    for _ in range(10):
        next_value = _ENV_PATTERN.sub(lambda match: env.get(match.group(1), match.group(2) or ""), expanded)
        if next_value == expanded:
            break
        expanded = next_value
    return expanded


def resolve_config_path(
    value: str,
    *,
    config_file: Optional[Path] = None,
    module_dir: Optional[Path] = None,
    prefer_root: bool = False,
) -> Path:
    module = module_dir or knowledge_module_dir()
    path = Path(expand_config_text(value, module)).expanduser()
    if path.is_absolute():
        return path.resolve()

    root = forge_ai_home(module)
    bases = []
    if prefer_root:
        bases.append(root)
    if config_file is not None:
        bases.append(config_file.resolve().parent)
    if not prefer_root:
        bases.append(root)

    seen = set()
    for base in bases:
        if base in seen:
            continue
        seen.add(base)
        candidate = (base / path).resolve()
        if candidate.exists():
            return candidate
    return ((bases[0] if bases else root) / path).resolve()


def knowledge_config_dir_candidates(module_dir: Optional[Path] = None) -> Iterator[Path]:
    module = module_dir or knowledge_module_dir()
    candidates = []
    if os.environ.get("KNOWLEDGE_CONFIG_DIR"):
        candidates.append(resolve_config_path(os.environ["KNOWLEDGE_CONFIG_DIR"], module_dir=module, prefer_root=True))
    if os.environ.get("FORGE_CONFIG_DIR"):
        forge_config = resolve_config_path(os.environ["FORGE_CONFIG_DIR"], module_dir=module, prefer_root=True)
        candidates.extend([forge_config / "knowledge", forge_config / "config" / "knowledge", forge_config])
    candidates.extend(
        [
            Path.cwd().resolve() / "config" / "knowledge",
            forge_ai_home(module) / "config" / "knowledge",
        ]
    )

    seen = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        yield resolved


def find_knowledge_config_file(filename: str, module_dir: Optional[Path] = None) -> Optional[Path]:
    for config_dir in knowledge_config_dir_candidates(module_dir):
        candidate = config_dir / filename
        if candidate.exists():
            return candidate
    return None


def knowledge_sources_path(module_dir: Optional[Path] = None) -> Path:
    module = module_dir or knowledge_module_dir()
    configured = os.environ.get("KNOWLEDGE_CONFIG")
    if configured:
        return resolve_config_path(configured, module_dir=module, prefer_root=True)
    found = find_knowledge_config_file("knowledge-sources.yaml", module)
    if found is not None:
        return found
    return forge_ai_home(module) / "config" / "knowledge" / "knowledge-sources.yaml"


def load_knowledge_defaults(defaults_path: Optional[Path] = None) -> Dict[str, Any]:
    path = defaults_path or find_knowledge_config_file("knowledge.defaults.yaml")
    if path is None or not path.exists():
        return {}
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}
