from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Extra, Field, validator


ALLOWED_SYMBOL_KINDS = {
    "FILE", "CLASS", "INTERFACE", "METHOD", "FUNCTION", "FIELD", "CONFIG_ENTRY",
    "CONTRACT_OPERATION", "DTO", "RECORD", "UNKNOWN",
}
ALLOWED_ROLES = {
    "ENTRYPOINT", "HTTP_HANDLER", "EVENT_HANDLER", "COMMAND_HANDLER", "QUERY_HANDLER",
    "USE_CASE", "APPLICATION_SERVICE", "DOMAIN_MODEL", "REPOSITORY", "CLIENT", "MAPPER",
    "DTO", "CONFIGURATION", "CONTRACT", "TEST", "UTILITY", "UNKNOWN",
}
ALLOWED_RELATIONS = {
    "DECLARES", "CONTAINS", "CALLS", "IMPLEMENTS", "EXTENDS", "INJECTS", "MAPS_TO", "USES",
    "READS_FROM", "WRITES_TO", "PUBLISHES", "CONSUMES", "CONFIGURES", "REFERENCES_CONTRACT",
    "REFERENCES_DTO", "RELATED_TO", "UNKNOWN",
}


class AnalysisBuildRequest(BaseModel):
    sourceIds: List[str] = Field(default_factory=list)
    groups: List[str] = Field(default_factory=list)
    force: bool = False
    maxFiles: Optional[int] = None
    concurrency: int = 1

    class Config:
        extra = Extra.forbid

    @validator("concurrency")
    def concurrency_range(cls, value: int) -> int:
        return max(1, min(value, 3))


class AnalysisRole(BaseModel):
    role: str
    confidence: float
    evidence: List[str]

    class Config:
        extra = Extra.forbid

    @validator("role")
    def allowed_role(cls, value: str) -> str:
        if value not in ALLOWED_ROLES:
            raise ValueError("Unsupported role")
        return value

    @validator("confidence")
    def confidence_range(cls, value: float) -> float:
        if value < 0 or value > 1:
            raise ValueError("Confidence must be between 0 and 1")
        return value

    @validator("evidence")
    def evidence_required(cls, value: List[str], values: Dict[str, Any]) -> List[str]:
        if values.get("role") != "UNKNOWN" and not value:
            raise ValueError("Evidence is required for non-UNKNOWN role")
        return value


class AnalysisSymbol(BaseModel):
    localId: str
    name: str
    kind: str
    roles: List[AnalysisRole] = Field(default_factory=list)
    lineStart: int
    lineEnd: int
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid

    @validator("kind")
    def allowed_kind(cls, value: str) -> str:
        if value not in ALLOWED_SYMBOL_KINDS:
            raise ValueError("Unsupported symbol kind")
        return value


class AnalysisRelation(BaseModel):
    fromLocalId: str
    toLocalId: str
    relation: str
    confidence: float
    evidence: List[str]
    lineStart: int
    lineEnd: int
    metadata: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = Extra.forbid

    @validator("relation")
    def allowed_relation(cls, value: str) -> str:
        if value not in ALLOWED_RELATIONS:
            raise ValueError("Unsupported relation")
        return value

    @validator("confidence")
    def relation_confidence_range(cls, value: float) -> float:
        if value < 0 or value > 1:
            raise ValueError("Confidence must be between 0 and 1")
        return value

    @validator("evidence")
    def relation_evidence_required(cls, value: List[str], values: Dict[str, Any]) -> List[str]:
        if values.get("relation") != "UNKNOWN" and not value:
            raise ValueError("Evidence is required for non-UNKNOWN relation")
        return value


class AnalysisResult(BaseModel):
    fileSummary: str
    symbols: List[AnalysisSymbol] = Field(default_factory=list)
    relations: List[AnalysisRelation] = Field(default_factory=list)
    diagnostics: List[Dict[str, Any]] = Field(default_factory=list)

    class Config:
        extra = Extra.forbid

    def validate_lines(self, line_count: int) -> None:
        for item in [*self.symbols, *self.relations]:
            if item.lineStart < 1 or item.lineEnd < item.lineStart or item.lineEnd > max(line_count, 1):
                raise ValueError("Line range outside file")
