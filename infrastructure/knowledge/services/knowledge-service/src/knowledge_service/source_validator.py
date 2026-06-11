from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Dict, List

from knowledge_service.source_catalog import SourceDiagnostic

SAFE_ID = re.compile(r"^[A-Za-z0-9_-]+$")


def validate_service_entry(service_id: str, entry: Any) -> List[SourceDiagnostic]:
    diagnostics: List[SourceDiagnostic] = []
    if not service_id or not SAFE_ID.match(service_id):
        diagnostics.append(SourceDiagnostic(service_id or None, "INVALID_SERVICE_ID", "serviceId must contain only letters, numbers, '_' and '-'"))
    if not isinstance(entry, dict):
        diagnostics.append(SourceDiagnostic(service_id, "INVALID_SERVICE_ENTRY", "Service entry must be a map"))
        return diagnostics
    label = entry.get("label")
    path = entry.get("path")
    if not label or not str(label).strip():
        diagnostics.append(SourceDiagnostic(service_id, "MISSING_LABEL", "Service label is required"))
    if not path or not str(path).strip():
        diagnostics.append(SourceDiagnostic(service_id, "MISSING_PATH", "Service path is required"))
    elif Path(str(path)).is_absolute():
        diagnostics.append(SourceDiagnostic(service_id, "ABSOLUTE_SERVICE_PATH", "Service path must be relative"))
    return diagnostics


def is_valid_service_entry(diagnostics: List[SourceDiagnostic]) -> bool:
    fatal = {"INVALID_SERVICE_ID", "INVALID_SERVICE_ENTRY", "MISSING_LABEL", "MISSING_PATH", "ABSOLUTE_SERVICE_PATH"}
    return not any(item.code in fatal for item in diagnostics)
