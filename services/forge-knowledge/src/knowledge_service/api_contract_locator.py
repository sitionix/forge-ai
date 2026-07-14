from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Optional

import yaml


@dataclass(frozen=True)
class ApiContractOperation:
    http_method: str
    route: str


class ApiContractLocator:
    OPENAPI_OPERATION_METHODS = {"get", "post", "put", "delete", "patch", "head", "options", "trace"}

    def __init__(self, workspace_root: Optional[Path] = None) -> None:
        self.workspace_root = workspace_root

    def interface_method(self, interface_name: str, operation_id: str) -> str | None:
        spec = self._api_first_spec(interface_name)
        if spec is None:
            return None
        return f"{spec.interface_name}.{operation_id}"

    def locate_operation(self, interface_name: str, operation_id: str) -> ApiContractOperation | None:
        spec = self._api_first_spec(interface_name)
        if spec is None:
            return None
        openapi_path = self._contract_path(spec.contract_package, spec.api_name)
        if openapi_path is None:
            return None
        openapi = self._read_yaml_mapping(openapi_path)
        paths = openapi.get("paths") if isinstance(openapi.get("paths"), dict) else {}
        for route, raw_path_item in paths.items():
            path_item = self._resolve_openapi_path_item(openapi_path, raw_path_item)
            for method, operation in path_item.items():
                if str(method).lower() not in self.OPENAPI_OPERATION_METHODS or not isinstance(operation, dict):
                    continue
                if operation.get("operationId") == operation_id:
                    return ApiContractOperation(http_method=str(method).upper(), route=str(route))
        return None

    def _contract_path(self, contract_package: str, api_name: str) -> Path | None:
        workspace_root = self._workspace_root()
        if workspace_root is None:
            return None
        return workspace_root / contract_package / "apis" / api_name / "rest" / "openapi.yml"

    def _api_first_spec(self, interface_name: str) -> "_ApiFirstSpec | None":
        normalized = str(interface_name or "").strip()
        parts = normalized.split(".")
        try:
            api_first_index = parts.index("api_first")
        except ValueError:
            return None
        if api_first_index < 2 or len(parts) <= api_first_index + 2 or parts[api_first_index + 1] != "api":
            return None
        return _ApiFirstSpec(
            interface_name=normalized,
            contract_package=parts[api_first_index - 2].replace("_", "-"),
            api_name=parts[api_first_index - 1],
        )

    def _resolve_openapi_path_item(self, openapi_path: Path, raw_path_item: Any) -> Dict[str, Any]:
        if not isinstance(raw_path_item, dict):
            return {}
        ref = raw_path_item.get("$ref")
        if not ref:
            return raw_path_item
        ref_file = str(ref).split("#", 1)[0]
        if not ref_file:
            return {}
        return self._read_yaml_mapping((openapi_path.parent / ref_file).resolve())

    def _read_yaml_mapping(self, path: Path) -> Dict[str, Any]:
        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError):
            return {}
        return data if isinstance(data, dict) else {}

    def _workspace_root(self) -> Path | None:
        if self.workspace_root is not None:
            return self.workspace_root
        configured = os.environ.get("FORGE_WORKSPACE_ROOT")
        return Path(configured) if configured else None


@dataclass(frozen=True)
class _ApiFirstSpec:
    interface_name: str
    contract_package: str
    api_name: str
