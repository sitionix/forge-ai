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


class KnowledgeQueryEntrypointOrigin(str, Enum):
    EXPLICIT_GRAPH_FACT = "EXPLICIT_GRAPH_FACT"
    INFERRED_ROOT = "INFERRED_ROOT"


class FlowExplanationStatus(str, Enum):
    OK = "OK"
    FAILED = "FAILED"


class KnowledgeQueryRequest(BaseModel):
    queryText: str = Field(..., min_length=1)
    intent: KnowledgeQueryIntent = KnowledgeQueryIntent.UNKNOWN
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
    def default_missing_intent(cls, value: KnowledgeQueryIntent) -> KnowledgeQueryIntent:
        if value is None:
            return KnowledgeQueryIntent.UNKNOWN
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
    flowDomain: Optional[str] = None


class KnowledgeQueryMatchedNodePreview(BaseModel):
    sourceId: str
    nodeKind: str
    label: str
    score: float
    matchReasons: List[str] = Field(default_factory=list)
    relativePath: Optional[str] = None
    qualifiedName: Optional[str] = None
    flowDomain: Optional[str] = None


class KnowledgeQueryFlowNode(BaseModel):
    nodeRef: str
    label: str
    kind: str
    qualifiedName: Optional[str] = None
    relativePath: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None


class KnowledgeQueryFlowOrigin(BaseModel):
    anchorRef: str
    label: str
    score: float
    distance: int
    matchReasons: List[str] = Field(default_factory=list)


class KnowledgeQueryFlowTransition(BaseModel):
    transitionRef: str
    fromNodeRef: str
    toNodeRef: str
    evidenceRefs: List[str] = Field(default_factory=list)


class KnowledgeQueryFlowBoundary(BaseModel):
    boundaryRef: str
    fromNodeRef: str
    kind: str
    resolutionStatus: str
    target: Optional[str] = None
    evidenceRefs: List[str] = Field(default_factory=list)


class KnowledgeQueryFlowEvidence(BaseModel):
    evidenceRef: str
    ownerRef: str
    relativePath: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None
    excerpt: Optional[str] = None


class KnowledgeQueryFlowCoverage(BaseModel):
    nodeCount: int = 0
    transitionCount: int = 0
    boundaryCount: int = 0
    anchorCount: int = 0
    maxDepthReached: int = 0
    cycleDetected: bool = False
    truncated: bool = False


class KnowledgeQueryFlow(BaseModel):
    flowIndex: int
    source: str
    entrypoint: KnowledgeQueryFlowNode
    entrypointOrigin: KnowledgeQueryEntrypointOrigin
    matchedAnchors: List[KnowledgeQueryFlowOrigin] = Field(default_factory=list)
    nodes: List[KnowledgeQueryFlowNode] = Field(default_factory=list)
    transitions: List[KnowledgeQueryFlowTransition] = Field(default_factory=list)
    boundaries: List[KnowledgeQueryFlowBoundary] = Field(default_factory=list)
    evidence: List[KnowledgeQueryFlowEvidence] = Field(default_factory=list)
    complete: bool = True
    coverage: KnowledgeQueryFlowCoverage = Field(default_factory=KnowledgeQueryFlowCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeQueryCoverage(BaseModel):
    searchedSourceCount: int = 0
    matchedSourceCount: int = 0
    matchedNodeCount: int = 0
    flowCount: int = 0
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
    matchedNodes: List[KnowledgeQueryMatchedNodePreview] = Field(default_factory=list)
    flows: List[KnowledgeQueryFlow] = Field(default_factory=list)
    coverage: KnowledgeQueryCoverage = Field(default_factory=KnowledgeQueryCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class FlowExplanationStep(BaseModel):
    nodeRef: str
    nodeLabel: str
    explanation: Optional[str] = None
    transitionRefs: List[str] = Field(default_factory=list)
    evidenceRefs: List[str] = Field(default_factory=list)


class FlowExplanationNarrative(BaseModel):
    text: str
    nodeRefs: List[str] = Field(default_factory=list)
    transitionRefs: List[str] = Field(default_factory=list)
    boundaryRefs: List[str] = Field(default_factory=list)


class FlowExplanationTransition(BaseModel):
    transitionRef: str
    explanation: Optional[str] = None
    evidenceRefs: List[str] = Field(default_factory=list)


class FlowExplanationBoundary(BaseModel):
    boundaryRef: str
    kind: str
    explanation: Optional[str] = None
    evidenceRefs: List[str] = Field(default_factory=list)


class FlowExplanation(BaseModel):
    flowIndex: int
    title: str = ""
    narrative: List[FlowExplanationNarrative] = Field(default_factory=list)
    steps: List[FlowExplanationStep] = Field(default_factory=list)
    transitionExplanations: List[FlowExplanationTransition] = Field(default_factory=list)
    boundaries: List[FlowExplanationBoundary] = Field(default_factory=list)
    status: FlowExplanationStatus = FlowExplanationStatus.OK


class KnowledgeQueryFlowExplanationResponse(KnowledgeQueryResponse):
    flowExplanations: List[FlowExplanation] = Field(default_factory=list)


class FlowToolAddress(BaseModel):
    service: Optional[str] = None
    relativePath: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None


class FlowToolEvidence(BaseModel):
    ref: str
    relativePath: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None
    excerpt: Optional[str] = None


class FlowToolStep(BaseModel):
    nodeRef: str
    symbol: str
    kind: str
    address: FlowToolAddress = Field(default_factory=FlowToolAddress)
    explanation: Optional[str] = None
    evidence: List[FlowToolEvidence] = Field(default_factory=list)


class FlowToolTransition(BaseModel):
    transitionRef: str
    fromNodeRef: str
    toNodeRef: str
    fromSymbol: str
    toSymbol: str
    explanation: Optional[str] = None
    evidence: List[FlowToolEvidence] = Field(default_factory=list)


class FlowToolBoundary(BaseModel):
    boundaryRef: str
    fromNodeRef: str
    kind: str
    target: Optional[str] = None
    explanation: Optional[str] = None
    evidence: List[FlowToolEvidence] = Field(default_factory=list)


class FlowToolContext(BaseModel):
    flowIndex: int
    status: FlowExplanationStatus = FlowExplanationStatus.OK
    title: str = ""
    narrative: List[FlowExplanationNarrative] = Field(default_factory=list)
    steps: List[FlowToolStep] = Field(default_factory=list)
    transitions: List[FlowToolTransition] = Field(default_factory=list)
    boundaries: List[FlowToolBoundary] = Field(default_factory=list)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeQueryToolContextResponse(BaseModel):
    queryText: str
    answerLanguage: str
    status: KnowledgeQueryStatus
    flows: List[FlowToolContext] = Field(default_factory=list)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)
