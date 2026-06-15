from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    message: str
    maxContextChars: Optional[int] = Field(default=None, ge=1000, le=50000)

    class Config:
        extra = "forbid"


class ChatDiagnostic(BaseModel):
    code: str
    message: str


class ChatContextItem(BaseModel):
    sourceId: str
    displayName: str
    relativePath: str
    lineStart: int
    lineEnd: int
    reason: str
    score: float
    content: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ChatResponse(BaseModel):
    answer: str
    usedContext: List[ChatContextItem] = Field(default_factory=list)
    diagnostics: List[ChatDiagnostic] = Field(default_factory=list)
