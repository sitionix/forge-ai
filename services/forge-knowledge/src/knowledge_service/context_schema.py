from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class ContextRequest(BaseModel):
    query: str
    sourceIds: List[str] = Field(default_factory=list)
    groups: List[str] = Field(default_factory=list)
    maxChars: int = Field(default=12000, ge=1000, le=50000)
    maxItems: int = Field(default=12, ge=1, le=50)
    includeContent: bool = True


class ContextDiagnostic(BaseModel):
    code: str
    message: str


class ContextBudget(BaseModel):
    maxChars: int
    usedChars: int
    truncated: bool


class ContextSource(BaseModel):
    sourceId: str
    displayName: str
    reason: str


class ContextItem(BaseModel):
    sourceId: str
    displayName: str
    group: Optional[str] = None
    relativePath: str
    lineStart: int
    lineEnd: int
    content: Optional[str] = None
    matchType: str
    reason: str
    score: float
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ContextResponse(BaseModel):
    query: str
    context: List[ContextItem] = Field(default_factory=list)
    sourcesUsed: List[ContextSource] = Field(default_factory=list)
    budget: ContextBudget
    diagnostics: List[ContextDiagnostic] = Field(default_factory=list)
