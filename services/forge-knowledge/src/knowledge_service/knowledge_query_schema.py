from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, StrictBool, validator

from knowledge_service.language_policy import normalize_language_code


class KnowledgeQueryStatus(str, Enum):
    OK = "OK"
    NO_CANDIDATES = "NO_CANDIDATES"
    AMBIGUOUS = "AMBIGUOUS"
    INVALID_REQUEST = "INVALID_REQUEST"
    QUERY_FAILED = "QUERY_FAILED"


class KnowledgeQueryIntent(str, Enum):
    AUTO = "AUTO"
    FLOW_EXPLANATION = "FLOW_EXPLANATION"


class KnowledgeQueryRequest(BaseModel):
    queryText: str = Field(..., min_length=1)
    intent: KnowledgeQueryIntent = KnowledgeQueryIntent.AUTO
    answerLanguage: Optional[str] = None
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
            return KnowledgeQueryIntent.AUTO
        return value

    @validator("answerLanguage", pre=True, always=True)
    def normalize_answer_language(cls, value: str | None) -> Optional[str]:
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("answerLanguage must be a string")
        if not value.strip():
            return None
        normalized = normalize_language_code(value, allow_auto=True)
        if normalized == "auto":
            return "auto"
        if not normalized:
            raise ValueError("answerLanguage must be omitted, null, auto, or a valid language code")
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


class KnowledgeQueryCoverage(BaseModel):
    searchedSourceCount: int = 0
    matchedSourceCount: int = 0
    matchedNodeCount: int = 0
    discoveredGraphCount: int = 0
    returnedGraphCount: int = 0
    omittedGraphCount: int = 0
    maxFlows: int = 0
    selectedLocalUnitCount: int = 0
    localUnitCount: int = 0
    nodeCount: int = 0
    edgeCount: int = 0
    evidenceCount: int = 0
    provenTransitionCount: int = 0
    openAmbiguousBoundaryCount: int = 0
    openUnresolvedBoundaryCount: int = 0
    truncated: bool = False
    continuationAvailable: bool = False


class KnowledgeQueryGraphCoverage(BaseModel):
    unitCount: int = 0
    sourceCount: int = 0
    localNodeCount: int = 0
    localExecutionTransitionCount: int = 0
    provenCrossSourceTransitionCount: int = 0
    openAmbiguousBoundaryCount: int = 0
    openUnresolvedBoundaryCount: int = 0
    queryEntryUnitCount: int = 0
    topologyEntryUnitCount: int = 0
    cycleCount: int = 0
    orphanResolutionCount: int = 0
    missingUnitMappingCount: int = 0
    complete: bool = True
    truncated: bool = False


class KnowledgeQueryGraphUnitCoverage(BaseModel):
    nodeCount: int = 0
    transitionCount: int = 0
    genericBoundaryCount: int = 0
    topologyBoundaryCount: int = 0
    anchorCount: int = 0
    rootCount: int = 0
    maxDepthReached: int = 0
    cycleDetected: bool = False
    truncated: bool = False


class KnowledgeQueryGraphUnit(BaseModel):
    unitId: str
    sourceId: str
    graphRevision: str
    querySelectedInitial: bool = False
    recursivelyDiscovered: bool = False
    roots: List[Dict[str, Any]] = Field(default_factory=list)
    anchors: List[Dict[str, Any]] = Field(default_factory=list)
    nodes: List[Dict[str, Any]] = Field(default_factory=list)
    localTransitions: List[Dict[str, Any]] = Field(default_factory=list)
    genericBoundaries: List[Dict[str, Any]] = Field(default_factory=list)
    topologyBoundaries: List[Dict[str, Any]] = Field(default_factory=list)
    supportingContext: List[Dict[str, Any]] = Field(default_factory=list)
    evidence: List[Dict[str, Any]] = Field(default_factory=list)
    complete: bool = True
    coverage: KnowledgeQueryGraphUnitCoverage = Field(default_factory=KnowledgeQueryGraphUnitCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeQueryGraphTransition(BaseModel):
    transitionId: str
    kind: str = "PROVEN_BOUNDARY_CONTINUATION"
    verificationStatus: str = "PROVEN"
    resolutionId: str
    sourceUnitId: str
    targetUnitId: str
    requiredBoundary: Dict[str, Any]
    providedBoundary: Dict[str, Any]
    targetSeeds: List[Dict[str, Any]] = Field(default_factory=list)
    provingDescriptorFingerprintHashes: List[str] = Field(default_factory=list)
    evidenceRefs: List[Dict[str, Any]] = Field(default_factory=list)


class KnowledgeQueryGraphOpenBoundary(BaseModel):
    requiredBoundary: Dict[str, Any]
    sourceUnitIds: List[str] = Field(default_factory=list)
    status: str
    viableCandidateOwners: List[Dict[str, Any]] = Field(default_factory=list)
    viableCandidateBoundaries: List[Dict[str, Any]] = Field(default_factory=list)
    rejectionReasonCodes: List[str] = Field(default_factory=list)
    descriptorFingerprintHashes: List[str] = Field(default_factory=list)
    diagnostics: List[str] = Field(default_factory=list)


class KnowledgeQueryGraph(BaseModel):
    graphId: str
    queryEntryUnitIds: List[str] = Field(default_factory=list)
    topologyEntryUnitIds: List[str] = Field(default_factory=list)
    units: List[KnowledgeQueryGraphUnit] = Field(default_factory=list)
    provenTransitions: List[KnowledgeQueryGraphTransition] = Field(default_factory=list)
    openBoundaries: List[KnowledgeQueryGraphOpenBoundary] = Field(default_factory=list)
    complete: bool = True
    coverage: KnowledgeQueryGraphCoverage = Field(default_factory=KnowledgeQueryGraphCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeQueryResponse(BaseModel):
    queryId: str
    status: KnowledgeQueryStatus
    intent: str
    matchedSources: List[KnowledgeQueryMatchedSource] = Field(default_factory=list)
    matchedNodes: List[KnowledgeQueryMatchedNodePreview] = Field(default_factory=list)
    graphs: List[KnowledgeQueryGraph] = Field(default_factory=list)
    coverage: KnowledgeQueryCoverage = Field(default_factory=KnowledgeQueryCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeGraphAnswerQueryEntry(BaseModel):
    unitId: str
    sourceId: str
    root: Dict[str, Any] = Field(default_factory=dict)


class KnowledgeGraphAnswer(BaseModel):
    graphId: str
    sources: List[str] = Field(default_factory=list)
    queryEntries: List[KnowledgeGraphAnswerQueryEntry] = Field(default_factory=list)
    text: str
    complete: bool = True
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeHumanQueryResponse(BaseModel):
    answerLanguage: str
    answers: List[KnowledgeGraphAnswer] = Field(default_factory=list)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeQueryToolContextGraph(BaseModel):
    graphId: str
    queryEntryUnitIds: List[str] = Field(default_factory=list)
    topologyEntryUnitIds: List[str] = Field(default_factory=list)
    units: List[KnowledgeQueryGraphUnit] = Field(default_factory=list)
    provenTransitions: List[KnowledgeQueryGraphTransition] = Field(default_factory=list)
    openBoundaries: List[KnowledgeQueryGraphOpenBoundary] = Field(default_factory=list)
    complete: bool = True
    coverage: KnowledgeQueryGraphCoverage = Field(default_factory=KnowledgeQueryGraphCoverage)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)


class KnowledgeQueryToolContextResponse(BaseModel):
    queryText: str
    graphs: List[KnowledgeQueryToolContextGraph] = Field(default_factory=list)
    diagnostics: List[KnowledgeQueryDiagnostic] = Field(default_factory=list)
