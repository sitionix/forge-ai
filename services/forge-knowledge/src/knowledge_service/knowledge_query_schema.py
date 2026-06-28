from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, validator


DEFAULT_QUERY_MAX_ANCHORS = 5
MAX_QUERY_ANCHORS = 20
DEFAULT_QUERY_DEPTH = 2
MAX_QUERY_DEPTH = 4


class KnowledgeQueryStatus(str, Enum):
    OK = "OK"
    NO_CANDIDATES = "NO_CANDIDATES"
    AMBIGUOUS = "AMBIGUOUS"
    INVALID_REQUEST = "INVALID_REQUEST"
    QUERY_FAILED = "QUERY_FAILED"


class KnowledgeQueryRequest(BaseModel):
    query: str = Field(min_length=1)
    intent: str = "AUTO"
    maxAnchors: int = Field(default=DEFAULT_QUERY_MAX_ANCHORS, ge=1, le=MAX_QUERY_ANCHORS)
    depth: int = Field(default=DEFAULT_QUERY_DEPTH, ge=1, le=MAX_QUERY_DEPTH)

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


class KnowledgeQueryDiagnostic(BaseModel):
    code: str
    message: str
    severity: str = "INFO"
    sourceId: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class KnowledgeQueryMatchedSource(BaseModel):
    sourceId: str
    displayName: str
    score: float


class KnowledgeQueryAnchor(BaseModel):
    sourceId: str
    nodeId: str
    stableKey: str
    kind: str
    label: str
    score: float
    matchReasons: List[str] = Field(default_factory=list)
    snapshotId: Optional[str] = None
    graphRevision: Optional[str] = None
    relativePath: Optional[str] = None
    qualifiedName: Optional[str] = None


class KnowledgeQueryCoverage(BaseModel):
    searchedSourceCount: int = 0
    matchedSourceCount: int = 0
    anchorCount: int = 0
    nodeCount: int = 0
    edgeCount: int = 0
    evidenceCount: int = 0
    truncated: bool = False


class KnowledgeQueryResponse(BaseModel):
    queryId: str
    status: KnowledgeQueryStatus
    intent: str
    matchedSources: List[KnowledgeQueryMatchedSource] = Field(default_factory=list)
    anchors: List[KnowledgeQueryAnchor] = Field(default_factory=list)
    nodes: List[Dict[str, Any]] = Field(default_factory=list)
    edges: List[Dict[str, Any]] = Field(default_factory=list)
    verifiedPaths: List[Dict[str, Any]] = Field(default_factory=list)
    evidence: List[Dict[str, Any]] = Field(default_factory=list)
    unresolved: List[Dict[str, Any]] = Field(default_factory=list)
    external: List[Dict[str, Any]] = Field(default_factory=list)
    coverage: KnowledgeQueryCoverage = Field(default_factory=KnowledgeQueryCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)
