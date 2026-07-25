from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class SourceDiagnostic:
    sourceId: Optional[str]
    code: str
    message: str


@dataclass(frozen=True)
class SourceMetadata:
    sourceId: str
    displayName: str
    group: Optional[str]
    path: str
    absoluteRoot: Path
    rootExists: bool
    tags: List[str] = field(default_factory=list)
    domainKeywords: List[str] = field(default_factory=list)
    ownsBusinessAreas: List[str] = field(default_factory=list)
    tests: List[str] = field(default_factory=list)
    contractRefs: Dict[str, Any] = field(default_factory=dict)
    db: Any = None
    deploy: Any = None

    def public_dict(self, include_absolute_root: bool = False) -> Dict[str, Any]:
        result = {
            "sourceId": self.sourceId,
            "displayName": self.displayName,
            "group": self.group,
            "path": self.path,
            "rootExists": self.rootExists,
            "tags": self.tags,
            "domainKeywords": self.domainKeywords,
            "ownsBusinessAreas": self.ownsBusinessAreas,
            "tests": self.tests,
        }
        if self.contractRefs:
            result["contractRefs"] = self.contractRefs
        if include_absolute_root:
            result["absoluteRoot"] = str(self.absoluteRoot)
        return result


@dataclass(frozen=True)
class SourceCatalogResult:
    sources: List[SourceMetadata]
    diagnostics: List[SourceDiagnostic]
