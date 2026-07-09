from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, StrictBool, validator


class KnowledgeQueryStatus(str, Enum):
    OK = "OK"
    NO_CANDIDATES = "NO_CANDIDATES"
    AMBIGUOUS = "AMBIGUOUS"
    INVALID_REQUEST = "INVALID_REQUEST"
    QUERY_FAILED = "QUERY_FAILED"


class KnowledgeQueryIntent(str, Enum):
    FLOW_EXPLANATION = "FLOW_EXPLANATION"
    COMPONENT_USAGE = "COMPONENT_USAGE"
    COMPONENT_RESPONSIBILITY = "COMPONENT_RESPONSIBILITY"
    CODE_LOCATION = "CODE_LOCATION"
    ARCHITECTURE_OVERVIEW = "ARCHITECTURE_OVERVIEW"
    UNKNOWN = "UNKNOWN"


class KnowledgeQueryRequest(BaseModel):
    queryText: str = Field(..., min_length=1)
    intent: KnowledgeQueryIntent
    answerLanguage: str = Field(..., min_length=1)
    includeTests: StrictBool
    maxFlows: int = Field(..., ge=1, le=10)

    class Config:
        extra = "forbid"

    @validator("queryText", pre=True)
    def query_text_must_not_be_blank(cls, value: str) -> str:
        if not isinstance(value, str):
            raise ValueError("queryText must be a string")
        normalized = value.strip()
        if not normalized:
            raise ValueError("queryText must not be empty")
        return normalized

    @validator("answerLanguage", pre=True)
    def answer_language_must_not_be_blank(cls, value: str) -> str:
        if not isinstance(value, str):
            raise ValueError("answerLanguage must be a string")
        normalized = value.strip().lower()
        if not normalized:
            raise ValueError("answerLanguage must not be empty")
        return normalized

    @validator("maxFlows", pre=True)
    def max_flows_must_be_an_integer(cls, value: int) -> int:
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("maxFlows must be an integer")
        return value


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


class KnowledgeQueryMatchedNode(BaseModel):
    sourceId: str
    nodeId: str
    stableKey: str
    nodeKind: str
    label: str
    score: float
    matchReasons: List[str] = Field(default_factory=list)
    graphId: Optional[str] = None
    graphRevision: Optional[str] = None
    relativePath: Optional[str] = None
    qualifiedName: Optional[str] = None


class KnowledgeQueryFlowPath(BaseModel):
    flowId: str
    sourceId: Optional[str] = None
    matchedNodeIds: List[str] = Field(default_factory=list)
    nodeIds: List[str] = Field(default_factory=list)
    edgeIds: List[str] = Field(default_factory=list)
    boundaryEdgeIds: List[str] = Field(default_factory=list)
    evidenceIds: List[str] = Field(default_factory=list)
    nodes: List[Dict[str, Any]] = Field(default_factory=list)
    edges: List[Dict[str, Any]] = Field(default_factory=list)
    evidence: List[Dict[str, Any]] = Field(default_factory=list)
    complete: bool = True
    stopReason: str = "TERMINAL_NODE"


class KnowledgeQueryCoverage(BaseModel):
    searchedSourceCount: int = 0
    matchedSourceCount: int = 0
    matchedNodeCount: int = 0
    flowPathCount: int = 0
    nodeCount: int = 0
    edgeCount: int = 0
    evidenceCount: int = 0
    truncated: bool = False
    continuationAvailable: bool = False


class KnowledgeQueryResponse(BaseModel):
    queryId: str
    status: KnowledgeQueryStatus
    intent: str
    matchedSources: List[KnowledgeQueryMatchedSource] = Field(default_factory=list)
    matchedNodes: List[KnowledgeQueryMatchedNode] = Field(default_factory=list)
    flowPaths: List[KnowledgeQueryFlowPath] = Field(default_factory=list)
    nodes: List[Dict[str, Any]] = Field(default_factory=list)
    edges: List[Dict[str, Any]] = Field(default_factory=list)
    verifiedPaths: List[Dict[str, Any]] = Field(default_factory=list)
    evidence: List[Dict[str, Any]] = Field(default_factory=list)
    unresolved: List[Dict[str, Any]] = Field(default_factory=list)
    external: List[Dict[str, Any]] = Field(default_factory=list)
    coverage: KnowledgeQueryCoverage = Field(default_factory=KnowledgeQueryCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)
