from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Extra, Field, validator


GRAPH_SCHEMA_VERSION = "knowledge.graph.analysis.v1"
GRAPH_ANALYSIS_ENGINE_VERSION = "GRAPH_V1"


class GraphNodeKind(str, Enum):
    FILE = "FILE"
    MODULE = "MODULE"
    TYPE = "TYPE"
    CALLABLE = "CALLABLE"
    FIELD = "FIELD"
    DATA = "DATA"
    CONFIG = "CONFIG"
    RESOURCE = "RESOURCE"
    EXTERNAL = "EXTERNAL"
    UNKNOWN = "UNKNOWN"


class GraphEdgeType(str, Enum):
    CONTAINS = "CONTAINS"
    DECLARES = "DECLARES"
    CALLS = "CALLS"
    REFERENCES = "REFERENCES"
    IMPORTS = "IMPORTS"
    IMPLEMENTS = "IMPLEMENTS"
    EXTENDS = "EXTENDS"
    OVERRIDES = "OVERRIDES"
    RETURNS = "RETURNS"
    READS = "READS"
    WRITES = "WRITES"
    CONFIGURES = "CONFIGURES"
    PUBLISHES = "PUBLISHES"
    CONSUMES = "CONSUMES"
    DEPENDS_ON = "DEPENDS_ON"
    UNKNOWN = "UNKNOWN"


class GraphClaimKind(str, Enum):
    RESPONSIBILITY = "RESPONSIBILITY"
    ROLE = "ROLE"
    SIDE_EFFECT = "SIDE_EFFECT"
    ENTRYPOINT_HINT = "ENTRYPOINT_HINT"
    DATA_ACCESS_HINT = "DATA_ACCESS_HINT"
    EXTERNAL_BOUNDARY_HINT = "EXTERNAL_BOUNDARY_HINT"
    TEST_HINT = "TEST_HINT"
    UNKNOWN = "UNKNOWN"


class GraphResolutionStatus(str, Enum):
    RESOLVED = "RESOLVED"
    UNRESOLVED = "UNRESOLVED"
    AMBIGUOUS = "AMBIGUOUS"
    MULTIPLE_CANDIDATES = "MULTIPLE_CANDIDATES"
    INTERFACE_TARGET = "INTERFACE_TARGET"
    EXTERNAL_TARGET = "EXTERNAL_TARGET"
    DYNAMIC_TARGET = "DYNAMIC_TARGET"
    UNKNOWN = "UNKNOWN"


class GraphFactStatus(str, Enum):
    CANDIDATE = "CANDIDATE"
    TRUSTED = "TRUSTED"
    REJECTED = "REJECTED"
    DERIVED = "DERIVED"
    STALE = "STALE"


class GraphFactOrigin(str, Enum):
    STATIC = "STATIC"
    LLM = "LLM"
    DERIVED = "DERIVED"
    RESOLVER = "RESOLVER"
    REPAIR = "REPAIR"
    IMPORT = "IMPORT"
    UNKNOWN = "UNKNOWN"


class GraphFlowDomain(str, Enum):
    CODE = "CODE"
    TEST = "TEST"
    CONFIG = "CONFIG"
    WORKFLOW = "WORKFLOW"
    DATA = "DATA"
    DOC = "DOC"
    BUILD = "BUILD"
    UNKNOWN = "UNKNOWN"


class GraphEvidenceKind(str, Enum):
    NODE = "NODE"
    EDGE = "EDGE"
    CLAIM = "CLAIM"


class GraphDiagnosticStage(str, Enum):
    INVENTORY_SELECTION = "INVENTORY_SELECTION"
    FILE_READ = "FILE_READ"
    STATIC_EXTRACT = "STATIC_EXTRACT"
    AI_CALL = "AI_CALL"
    JSON_PARSE = "JSON_PARSE"
    SCHEMA_VALIDATE = "SCHEMA_VALIDATE"
    AI_JSON_PARSE = "AI_JSON_PARSE"
    AI_SCHEMA = "AI_SCHEMA"
    CANDIDATE_VALIDATE = "CANDIDATE_VALIDATE"
    GRAPH_RESOLVE = "GRAPH_RESOLVE"
    FACT_PROMOTE = "FACT_PROMOTE"
    STORE = "STORE"
    PROJECTION = "PROJECTION"


class GraphDiagnosticSeverity(str, Enum):
    INFO = "INFO"
    WARN = "WARN"
    ERROR = "ERROR"


class GraphDiagnosticCode(str, Enum):
    AI_DIAGNOSTIC = "ANALYSIS_AI_DIAGNOSTIC"
    FILE_IDENTITY_MISMATCH = "ANALYSIS_GRAPH_FILE_IDENTITY_MISMATCH"
    DUPLICATE_NODE_LOCAL_ID = "ANALYSIS_GRAPH_DUPLICATE_NODE_LOCAL_ID"
    DUPLICATE_EDGE_LOCAL_ID = "ANALYSIS_GRAPH_DUPLICATE_EDGE_LOCAL_ID"
    DUPLICATE_CLAIM_LOCAL_ID = "ANALYSIS_GRAPH_DUPLICATE_CLAIM_LOCAL_ID"
    UNSUPPORTED_NODE_KIND = "ANALYSIS_GRAPH_UNSUPPORTED_NODE_KIND"
    UNSUPPORTED_EDGE_TYPE = "ANALYSIS_GRAPH_UNSUPPORTED_EDGE_TYPE"
    UNSUPPORTED_CLAIM_KIND = "ANALYSIS_GRAPH_UNSUPPORTED_CLAIM_KIND"
    CONFIDENCE_INVALID = "ANALYSIS_GRAPH_CONFIDENCE_INVALID"
    CONFIDENCE_BELOW_THRESHOLD = "ANALYSIS_GRAPH_CONFIDENCE_BELOW_THRESHOLD"
    LINE_RANGE_INVALID = "ANALYSIS_GRAPH_LINE_RANGE_INVALID"
    NODE_EVIDENCE_MISSING = "ANALYSIS_GRAPH_NODE_EVIDENCE_MISSING"
    NODE_PARENT_MISSING = "ANALYSIS_GRAPH_NODE_PARENT_MISSING"
    EDGE_SOURCE_MISSING = "ANALYSIS_GRAPH_EDGE_SOURCE_MISSING"
    EDGE_TARGET_MISSING = "ANALYSIS_GRAPH_EDGE_TARGET_MISSING"
    EDGE_EVIDENCE_MISSING = "ANALYSIS_GRAPH_EDGE_EVIDENCE_MISSING"
    CLAIM_NODE_MISSING = "ANALYSIS_GRAPH_CLAIM_NODE_MISSING"
    CLAIM_EVIDENCE_MISSING = "ANALYSIS_GRAPH_CLAIM_EVIDENCE_MISSING"


MIN_TRUSTED_CONFIDENCE = 0.5


class GraphAnalysisFile(BaseModel):
    sourceId: str
    inventoryFileId: int
    relativePath: str
    contentHash: str
    lineCount: int

    class Config:
        extra = Extra.forbid


class GraphEvidenceRange(BaseModel):
    lineStart: int
    lineEnd: int

    class Config:
        extra = Extra.forbid


class GraphNodeCandidate(BaseModel):
    localId: str
    nodeKind: str
    name: str
    qualifiedName: Optional[str] = None
    displayName: Optional[str] = None
    parentLocalId: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None
    confidence: float = 0.5
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid


class GraphEdgeCandidate(BaseModel):
    localId: str
    edgeType: str
    fromLocalId: str
    toLocalId: Optional[str] = None
    unresolvedTarget: Optional[Dict[str, Any]] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None
    confidence: float = 0.5
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid


class GraphClaimCandidate(BaseModel):
    localId: str
    nodeLocalId: str
    claimKind: str
    summary: str
    evidence: List[GraphEvidenceRange] = Field(default_factory=list)
    confidence: float = 0.5
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid

    @validator("summary")
    def summary_must_not_be_blank(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("summary must not be blank")
        return value.strip()


class GraphAnalysisResponse(BaseModel):
    schemaVersion: str
    file: GraphAnalysisFile
    nodes: List[GraphNodeCandidate] = Field(default_factory=list)
    edges: List[GraphEdgeCandidate] = Field(default_factory=list)
    claims: List[GraphClaimCandidate] = Field(default_factory=list)
    diagnostics: List[Dict[str, Any]] = Field(default_factory=list)

    class Config:
        extra = Extra.forbid

    @validator("schemaVersion")
    def schema_version_matches(cls, value: str) -> str:
        if value != GRAPH_SCHEMA_VERSION:
            raise ValueError(f"schemaVersion must be {GRAPH_SCHEMA_VERSION}")
        return value


class GraphEvidenceRef(BaseModel):
    lineStart: int
    lineEnd: int
    text: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid


class GraphNode(BaseModel):
    localId: str
    nodeKind: str
    name: str
    language: Optional[str] = None
    qualifiedName: Optional[str] = None
    displayName: Optional[str] = None
    parentLocalId: Optional[str] = None
    lineStart: Optional[int] = None
    lineEnd: Optional[int] = None
    confidence: float = 1.0
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid

    @validator("confidence")
    def confidence_range(cls, value: float) -> float:
        if value < 0 or value > 1:
            raise ValueError("Confidence must be between 0 and 1")
        return value


class GraphEdge(BaseModel):
    localId: str
    fromNodeLocalId: str
    toNodeLocalId: Optional[str] = None
    edgeType: str
    confidence: float
    evidence: List[GraphEvidenceRef] = Field(default_factory=list)
    unresolvedTarget: Optional[Dict[str, Any]] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid

    @validator("confidence")
    def edge_confidence_range(cls, value: float) -> float:
        if value < 0 or value > 1:
            raise ValueError("Confidence must be between 0 and 1")
        return value


class GraphClaim(BaseModel):
    localId: str
    nodeLocalId: str
    claimKind: str
    summary: str
    evidence: List[GraphEvidenceRef] = Field(default_factory=list)
    confidence: float
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid

    @validator("confidence")
    def claim_confidence_range(cls, value: float) -> float:
        if value < 0 or value > 1:
            raise ValueError("Confidence must be between 0 and 1")
        return value

    @validator("summary")
    def summary_required(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError("Claim summary is required")
        return value.strip()


class GraphAnalysisResult(BaseModel):
    nodes: List[GraphNode] = Field(default_factory=list)
    edges: List[GraphEdge] = Field(default_factory=list)
    claims: List[GraphClaim] = Field(default_factory=list)
    diagnostics: List[Dict[str, Any]] = Field(default_factory=list)

    class Config:
        extra = Extra.forbid

    def validate_lines(self, line_count: int) -> None:
        for node in self.nodes:
            self._validate_optional_range(node.lineStart, node.lineEnd, line_count)
        for edge in self.edges:
            for evidence in edge.evidence:
                self._validate_range(evidence.lineStart, evidence.lineEnd, line_count)
        for claim in self.claims:
            for evidence in claim.evidence:
                self._validate_range(evidence.lineStart, evidence.lineEnd, line_count)

    def validate_references(self) -> None:
        local_ids = {node.localId for node in self.nodes}
        for node in self.nodes:
            if node.parentLocalId and node.parentLocalId not in local_ids:
                raise ValueError("Graph node references an unknown parentLocalId")
        for edge in self.edges:
            if edge.fromNodeLocalId not in local_ids:
                raise ValueError("Graph edge references an unknown fromNodeLocalId")
            if edge.toNodeLocalId and edge.toNodeLocalId not in local_ids:
                raise ValueError("Graph edge references an unknown toNodeLocalId")
        for claim in self.claims:
            if claim.nodeLocalId not in local_ids:
                raise ValueError("Graph claim references an unknown nodeLocalId")

    def _validate_optional_range(self, line_start: Optional[int], line_end: Optional[int], line_count: int) -> None:
        if line_start is None and line_end is None:
            return
        if line_start is None or line_end is None:
            raise ValueError("Line range must include both start and end")
        self._validate_range(line_start, line_end, line_count)

    def _validate_range(self, line_start: int, line_end: int, line_count: int) -> None:
        if line_start < 1 or line_end < line_start or line_end > max(line_count, 1):
            raise ValueError("Line range outside file")
