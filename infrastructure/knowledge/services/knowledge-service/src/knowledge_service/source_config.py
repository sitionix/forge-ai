from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml

from knowledge_service.errors import ConfigMissingError, KnowledgeError
from knowledge_service.file_classification import FileClassifier
from knowledge_service.knowledge_defaults import load_knowledge_defaults


@dataclass(frozen=True)
class CatalogConfig:
    type: str
    path: Path
    workspace_root: Path


@dataclass(frozen=True)
class SelectionConfig:
    include_groups: List[str] = field(default_factory=list)
    include_services: List[str] = field(default_factory=list)
    exclude_services: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class IndexingConfig:
    include: List[str] = field(default_factory=list)
    exclude: List[str] = field(default_factory=list)
    max_file_size_bytes: int = 500000
    chunk_size_chars: int = 3000
    chunk_overlap_chars: int = 300


@dataclass(frozen=True)
class SourceConfig:
    catalog: CatalogConfig
    selection: SelectionConfig
    indexing: IndexingConfig
    file_classifier: FileClassifier


def load_source_config(path: Path) -> Optional[SourceConfig]:
    if not path.exists():
        return None
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        raise KnowledgeError("KNOWLEDGE_CONFIG_INVALID", f"Knowledge config is invalid YAML: {exc}") from exc

    catalog = data.get("catalog") or {}
    catalog_path = Path(str(catalog.get("path") or ""))
    workspace_root = Path(str(catalog.get("workspace_root") or ""))
    if not catalog_path.is_absolute():
        raise KnowledgeError("KNOWLEDGE_CONFIG_INVALID", "catalog.path must be an absolute path")
    if not workspace_root.is_absolute():
        raise KnowledgeError("KNOWLEDGE_CONFIG_INVALID", "catalog.workspace_root must be an absolute path")
    if not catalog_path.exists():
        raise KnowledgeError("SERVICE_CATALOG_NOT_FOUND", f"Service catalog not found: {catalog_path}")
    if not workspace_root.exists() or not workspace_root.is_dir():
        raise KnowledgeError("KNOWLEDGE_CONFIG_INVALID", f"catalog.workspace_root must be an existing directory: {workspace_root}")

    selection = data.get("selection") or {}
    indexing = data.get("indexing") or {}
    defaults = load_knowledge_defaults()
    knowledge_defaults = defaults.get("knowledge") or {}
    indexing_defaults = knowledge_defaults.get("indexing") or {}
    default_include = _string_list(knowledge_defaults.get("include_defaults"))
    default_exclude = _string_list(knowledge_defaults.get("exclude_defaults"))
    return SourceConfig(
        catalog=CatalogConfig(
            type=str(catalog.get("type") or "service_catalog"),
            path=catalog_path,
            workspace_root=workspace_root,
        ),
        selection=SelectionConfig(
            include_groups=_string_list(selection.get("include_groups")),
            include_services=_string_list(selection.get("include_services")),
            exclude_services=_string_list(selection.get("exclude_services")),
        ),
        indexing=IndexingConfig(
            include=_string_list(indexing.get("include")) or default_include,
            exclude=_string_list(indexing.get("exclude")) or default_exclude,
            max_file_size_bytes=int(indexing.get("max_file_size_bytes") or indexing_defaults.get("max_file_size_bytes") or 500000),
            chunk_size_chars=int(indexing.get("chunk_size_chars") or indexing_defaults.get("chunk_size_chars") or 3000),
            chunk_overlap_chars=int(indexing.get("chunk_overlap_chars") or indexing_defaults.get("chunk_overlap_chars") or 300),
        ),
        file_classifier=FileClassifier.from_config(knowledge_defaults.get("file_classification") or {}),
    )


def require_source_config(path: Path) -> SourceConfig:
    config = load_source_config(path)
    if config is None:
        raise ConfigMissingError()
    return config


def _string_list(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if item is not None and str(item).strip()]
