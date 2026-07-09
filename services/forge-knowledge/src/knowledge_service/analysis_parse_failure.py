from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class GraphAnalysisParseFailure:
    code: str
    message: str
    raw_preview: str
    error_details: list[dict[str, Any]] = field(default_factory=list)
    validation_report: dict[str, Any] | None = None
