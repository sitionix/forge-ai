from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, validator


class JarvisQueryStatus(str, Enum):
    OK = "OK"
    NO_CANDIDATES = "NO_CANDIDATES"
    AMBIGUOUS = "AMBIGUOUS"
    INVALID_REQUEST = "INVALID_REQUEST"
    QUERY_FAILED = "QUERY_FAILED"


class JarvisQueryRequest(BaseModel):
    query: str = Field(min_length=1)
    intent: str = "AUTO"
    maxAnchors: int = Field(default=5, ge=1, le=20)
    depth: int = Field(default=2, ge=1, le=4)

    class Config:
        extra = "forbid"

    @validator("query")
    def query_must_not_be_blank(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("query must not be empty")
        return normalized

    @validator("intent")
    def normalize_intent(cls, value: Optional[str]) -> str:
        normalized = (value or "AUTO").strip().upper()
        return normalized or "AUTO"


class JarvisQueryResponse(BaseModel):
    queryId: str
    status: JarvisQueryStatus
    intent: str
    matchedSources: List[Dict[str, Any]] = Field(default_factory=list)
    anchors: List[Dict[str, Any]] = Field(default_factory=list)
    nodes: List[Dict[str, Any]] = Field(default_factory=list)
    edges: List[Dict[str, Any]] = Field(default_factory=list)
    verifiedPaths: List[Dict[str, Any]] = Field(default_factory=list)
    evidence: List[Dict[str, Any]] = Field(default_factory=list)
    unresolved: List[Dict[str, Any]] = Field(default_factory=list)
    external: List[Dict[str, Any]] = Field(default_factory=list)
    coverage: Dict[str, Any] = Field(default_factory=dict)
    diagnostics: List[Dict[str, Any]] = Field(default_factory=list)

    class Config:
        extra = "allow"
