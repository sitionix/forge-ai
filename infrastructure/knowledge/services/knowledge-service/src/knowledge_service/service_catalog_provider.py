from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, Dict, List

import yaml

from knowledge_service.errors import KnowledgeError
from knowledge_service.source_catalog import SourceCatalogResult, SourceDiagnostic, SourceMetadata
from knowledge_service.source_config import SourceConfig
from knowledge_service.source_validator import is_valid_service_entry, validate_service_entry


class SourceCatalogProvider(ABC):
    @abstractmethod
    def load(self) -> SourceCatalogResult:
        raise NotImplementedError


class ServiceYamlCatalogProvider(SourceCatalogProvider):
    def __init__(self, config: SourceConfig):
        self.config = config

    def load(self) -> SourceCatalogResult:
        try:
            data = yaml.safe_load(self.config.catalog.path.read_text(encoding="utf-8")) or {}
        except yaml.YAMLError as exc:
            raise KnowledgeError("SERVICE_CATALOG_INVALID", f"Service catalog is invalid YAML: {exc}") from exc
        services = data.get("services")
        if not isinstance(services, dict):
            raise KnowledgeError("SERVICE_CATALOG_INVALID", "Service catalog must contain a root services map")

        sources: List[SourceMetadata] = []
        diagnostics: List[SourceDiagnostic] = []
        for service_id, entry in services.items():
            service_id = str(service_id)
            entry_diagnostics = validate_service_entry(service_id, entry)
            diagnostics.extend(entry_diagnostics)
            if not is_valid_service_entry(entry_diagnostics):
                continue
            if not self._selected(service_id, entry):
                continue
            service_path = str(entry["path"]).strip()
            absolute_root = (self.config.catalog.workspace_root / service_path).resolve()
            sources.append(SourceMetadata(
                sourceId=service_id,
                displayName=str(entry["label"]).strip(),
                group=_optional_text(entry.get("group")),
                path=service_path,
                absoluteRoot=absolute_root,
                rootExists=absolute_root.exists() and absolute_root.is_dir(),
                tags=_string_list(entry.get("tags")),
                domainKeywords=_string_list(entry.get("domain_keywords")),
                ownsBusinessAreas=_string_list(entry.get("owns_business_areas")),
                tests=_string_list(entry.get("tests")),
                contractRefs=entry.get("contract_refs") if isinstance(entry.get("contract_refs"), dict) else {},
                db=entry.get("db"),
                deploy=entry.get("deploy"),
            ))
        return SourceCatalogResult(sources=sources, diagnostics=diagnostics)

    def _selected(self, service_id: str, entry: Dict[str, Any]) -> bool:
        selection = self.config.selection
        if service_id in selection.exclude_services:
            return False
        if selection.include_services and service_id not in selection.include_services:
            return False
        if selection.include_groups and str(entry.get("group") or "") not in selection.include_groups:
            return False
        return True


def _string_list(value: Any) -> List[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value if item is not None and str(item).strip()]


def _optional_text(value: Any) -> str | None:
    if value is None or not str(value).strip():
        return None
    return str(value)
