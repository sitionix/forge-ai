from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Extra, Field, validator


GRAPH_SCHEMA_VERSION = "knowledge.graph.analysis.v1"


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
    parameter_count: Optional[int] = None
    parameterTypes: List[str] = Field(default_factory=list)
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

    @validator("parameter_count")
    def parameter_count_non_negative(cls, value: Optional[int]) -> Optional[int]:
        if value is not None and value < 0:
            raise ValueError("parameter_count must be non-negative")
        return value


class GraphEdge(BaseModel):
    localId: str
    fromNodeLocalId: str
    toNodeLocalId: Optional[str] = None
    edgeType: str
    resolutionStatus: Optional[str] = None
    argument_count: Optional[int] = None
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

    @validator("argument_count")
    def argument_count_non_negative(cls, value: Optional[int]) -> Optional[int]:
        if value is not None and value < 0:
            raise ValueError("argument_count must be non-negative")
        return value

    @validator("resolutionStatus")
    def resolution_status_non_empty(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("resolutionStatus must be a non-empty string")
        return normalized


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
