from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List

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


class JarvisQueryRequest(BaseModel):
    queryText: str = Field(..., min_length=1)
    intent: JarvisQueryIntent
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


class JarvisQueryResponse(BaseModel):
    queryId: str
    status: JarvisQueryStatus
    intent: str
    matchedSources: List[Dict[str, Any]] = Field(default_factory=list)
    matchedNodes: List[Dict[str, Any]] = Field(default_factory=list)
    flowPaths: List[Dict[str, Any]] = Field(default_factory=list)
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
