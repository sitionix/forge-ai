from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional, Type

from pydantic import BaseModel, Extra, Field, validator

from knowledge_service.file_classification import FileClassifier
from knowledge_service.knowledge_defaults import load_knowledge_defaults


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


def enum_values(enum_type: Type[Enum]) -> List[str]:
    return [item.value for item in enum_type]


ALLOWED_NODE_KINDS = set(enum_values(GraphNodeKind))
ALLOWED_EDGE_TYPES = set(enum_values(GraphEdgeType))
ALLOWED_RESOLUTION_STATUSES = set(enum_values(GraphResolutionStatus))
ALLOWED_CLAIM_KINDS = set(enum_values(GraphClaimKind))
ALLOWED_FACT_STATUSES = set(enum_values(GraphFactStatus))
ALLOWED_FACT_ORIGINS = set(enum_values(GraphFactOrigin))
ALLOWED_FLOW_DOMAINS = set(enum_values(GraphFlowDomain))

MIN_TRUSTED_CONFIDENCE = 0.5
_DEFAULT_FILE_CLASSIFIER: Optional[FileClassifier] = None


def classify_flow_domain(relative_path: str, extension: Optional[str] = None) -> GraphFlowDomain:
    flow_domain = _default_file_classifier().classify(relative_path, extension).flow_domain
    try:
        return GraphFlowDomain(flow_domain)
    except ValueError:
        return GraphFlowDomain.UNKNOWN


def _default_file_classifier() -> FileClassifier:
    global _DEFAULT_FILE_CLASSIFIER
    if _DEFAULT_FILE_CLASSIFIER is None:
        defaults = load_knowledge_defaults()
        knowledge = defaults.get("knowledge") or {}
        _DEFAULT_FILE_CLASSIFIER = FileClassifier.from_config(knowledge.get("file_classification") or {})
    return _DEFAULT_FILE_CLASSIFIER


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
