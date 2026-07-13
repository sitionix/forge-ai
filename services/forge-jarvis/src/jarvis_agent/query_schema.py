from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, StrictBool, validator


class JarvisQueryStatus(str, Enum):
    OK = "OK"
    NO_CANDIDATES = "NO_CANDIDATES"
    AMBIGUOUS = "AMBIGUOUS"
    INVALID_REQUEST = "INVALID_REQUEST"
    QUERY_FAILED = "QUERY_FAILED"


class JarvisQueryIntent(str, Enum):
    FLOW_EXPLANATION = "FLOW_EXPLANATION"
    COMPONENT_USAGE = "COMPONENT_USAGE"
    COMPONENT_RESPONSIBILITY = "COMPONENT_RESPONSIBILITY"
    CODE_LOCATION = "CODE_LOCATION"
    ARCHITECTURE_OVERVIEW = "ARCHITECTURE_OVERVIEW"
    UNKNOWN = "UNKNOWN"


class JarvisEntrypointOrigin(str, Enum):
    EXPLICIT_GRAPH_FACT = "EXPLICIT_GRAPH_FACT"
    INFERRED_ROOT = "INFERRED_ROOT"


class JarvisFlowExplanationStatus(str, Enum):
    OK = "OK"
    FAILED = "FAILED"


class JarvisQueryRequest(BaseModel):
    queryText: str = Field(..., min_length=1)
    intent: JarvisQueryIntent = JarvisQueryIntent.UNKNOWN
    answerLanguage: str = Field("en", min_length=1)
    includeTests: StrictBool = False
    maxFlows: int = Field(10, ge=1, le=10)

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

    @validator("intent", pre=True, always=True)
    def default_missing_intent(cls, value: JarvisQueryIntent) -> JarvisQueryIntent:
        if value is None:
            return JarvisQueryIntent.UNKNOWN
        return value

    @validator("answerLanguage", pre=True, always=True)
    def normalize_answer_language(cls, value: str) -> str:
        if value is None:
            return "en"
        if not isinstance(value, str):
            raise ValueError("answerLanguage must be a string")
        normalized = value.strip().lower()
        if not normalized:
            return "en"
        return normalized

    @validator("includeTests", pre=True, always=True)
    def default_missing_include_tests(cls, value: StrictBool) -> StrictBool:
        if value is None:
            return False
        return value

    @validator("maxFlows", pre=True, always=True)
    def max_flows_must_be_an_integer(cls, value: int) -> int:
        if value is None:
            return 10
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("maxFlows must be an integer")
        return value


class JarvisQueryDiagnostic(BaseModel):
    code: str
    message: str
    severity: str = "INFO"
    sourceId: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class JarvisFlowNode(BaseModel):
    nodeRef: str
    label: str
    kind: str
    qualifiedName: Optional[str] = None
    relativePath: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None


class JarvisFlowOrigin(BaseModel):
    anchorRef: str
    label: str
    score: float
    distance: int
    matchReasons: List[str] = Field(default_factory=list)


class JarvisFlowTransition(BaseModel):
    transitionRef: str
    fromNodeRef: str
    toNodeRef: str
    evidenceRefs: List[str] = Field(default_factory=list)


class JarvisFlowBoundary(BaseModel):
    boundaryRef: str
    fromNodeRef: str
    kind: str
    resolutionStatus: str
    target: Optional[str] = None
    evidenceRefs: List[str] = Field(default_factory=list)


class JarvisFlowEvidence(BaseModel):
    evidenceRef: str
    ownerRef: str
    relativePath: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None
    excerpt: Optional[str] = None


class JarvisFlowCoverage(BaseModel):
    nodeCount: int = 0
    transitionCount: int = 0
    boundaryCount: int = 0
    anchorCount: int = 0
    maxDepthReached: int = 0
    cycleDetected: bool = False
    truncated: bool = False


class JarvisKnowledgeFlow(BaseModel):
    flowIndex: int
    source: str
    entrypoint: JarvisFlowNode
    entrypointOrigin: JarvisEntrypointOrigin
    matchedAnchors: List[JarvisFlowOrigin] = Field(default_factory=list)
    nodes: List[JarvisFlowNode] = Field(default_factory=list)
    transitions: List[JarvisFlowTransition] = Field(default_factory=list)
    boundaries: List[JarvisFlowBoundary] = Field(default_factory=list)
    evidence: List[JarvisFlowEvidence] = Field(default_factory=list)
    complete: bool = True
    coverage: JarvisFlowCoverage = Field(default_factory=JarvisFlowCoverage)
    diagnostics: List[JarvisQueryDiagnostic] = Field(default_factory=list)


class JarvisFlowExplanationNarrative(BaseModel):
    text: str
    nodeRefs: List[str] = Field(default_factory=list)
    transitionRefs: List[str] = Field(default_factory=list)
    boundaryRefs: List[str] = Field(default_factory=list)


class JarvisFlowExplanationStep(BaseModel):
    nodeRef: str
    nodeLabel: str
    explanation: Optional[str] = None
    transitionRefs: List[str] = Field(default_factory=list)
    evidenceRefs: List[str] = Field(default_factory=list)


class JarvisFlowExplanationTransition(BaseModel):
    transitionRef: str
    explanation: Optional[str] = None
    evidenceRefs: List[str] = Field(default_factory=list)


class JarvisFlowExplanationBoundary(BaseModel):
    boundaryRef: str
    fromNodeRef: str
    kind: str
    resolutionStatus: str
    target: Optional[str] = None
    explanation: Optional[str] = None
    evidenceRefs: List[str] = Field(default_factory=list)


class JarvisFlowExplanation(BaseModel):
    flowIndex: int
    title: str = ""
    narrative: List[JarvisFlowExplanationNarrative] = Field(default_factory=list)
    steps: List[JarvisFlowExplanationStep] = Field(default_factory=list)
    transitionExplanations: List[JarvisFlowExplanationTransition] = Field(default_factory=list)
    boundaries: List[JarvisFlowExplanationBoundary] = Field(default_factory=list)
    status: JarvisFlowExplanationStatus = JarvisFlowExplanationStatus.OK


class JarvisQueryCoverage(BaseModel):
    searchedSourceCount: int = 0
    matchedSourceCount: int = 0
    matchedNodeCount: int = 0
    flowCount: int = 0
    nodeCount: int = 0
    edgeCount: int = 0
    evidenceCount: int = 0
    truncated: bool = False
    continuationAvailable: bool = False


class JarvisQueryResponse(BaseModel):
    queryId: str
    status: JarvisQueryStatus
    intent: str
    matchedSources: List[Dict[str, Any]] = Field(default_factory=list)
    matchedNodes: List[Dict[str, Any]] = Field(default_factory=list)
    flows: List[JarvisKnowledgeFlow] = Field(default_factory=list)
    flowExplanations: List[JarvisFlowExplanation] = Field(default_factory=list)
    coverage: JarvisQueryCoverage = Field(default_factory=JarvisQueryCoverage)
    diagnostics: List[JarvisQueryDiagnostic] = Field(default_factory=list)

    class Config:
        extra = "forbid"
