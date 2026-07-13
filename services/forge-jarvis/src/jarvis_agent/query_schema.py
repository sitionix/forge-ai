from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, StrictBool, validator


class JarvisQueryIntent(str, Enum):
    FLOW_EXPLANATION = "FLOW_EXPLANATION"
    COMPONENT_USAGE = "COMPONENT_USAGE"
    COMPONENT_RESPONSIBILITY = "COMPONENT_RESPONSIBILITY"
    CODE_LOCATION = "CODE_LOCATION"
    ARCHITECTURE_OVERVIEW = "ARCHITECTURE_OVERVIEW"
    UNKNOWN = "UNKNOWN"


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


class JarvisFlowAnswer(BaseModel):
    source: str
    entrypoint: str
    text: str

    class Config:
        extra = "forbid"


class JarvisHumanAnswerResponse(BaseModel):
    answerLanguage: str
    answers: List[JarvisFlowAnswer] = Field(default_factory=list)
    diagnostics: List[JarvisQueryDiagnostic] = Field(default_factory=list)

    class Config:
        extra = "forbid"
