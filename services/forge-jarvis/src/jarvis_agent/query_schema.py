from __future__ import annotations

import re
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, StrictBool, validator


_LANGUAGE_CODE_RE = re.compile(r"^[a-z]{2,3}$")


def _normalize_language_code(value: str) -> str:
    normalized = value.strip().lower()
    if not normalized:
        return ""
    if normalized == "auto":
        return "auto"
    normalized = normalized.split("-", 1)[0]
    if _LANGUAGE_CODE_RE.match(normalized):
        return normalized
    return ""


class JarvisQueryIntent(str, Enum):
    AUTO = "AUTO"
    FLOW_EXPLANATION = "FLOW_EXPLANATION"


class JarvisQueryRequest(BaseModel):
    queryText: str = Field(..., min_length=1)
    intent: JarvisQueryIntent = JarvisQueryIntent.AUTO
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
    def default_missing_intent(cls, value: JarvisQueryIntent) -> JarvisQueryIntent:
        if value is None:
            return JarvisQueryIntent.AUTO
        return value

    @validator("answerLanguage", pre=True, always=True)
    def normalize_answer_language(cls, value: str | None) -> Optional[str]:
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("answerLanguage must be a string")
        if not value.strip():
            return None
        normalized = _normalize_language_code(value)
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


class JarvisQueryDiagnostic(BaseModel):
    code: str
    message: str
    severity: str = "INFO"
    sourceId: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class JarvisGraphAnswerQueryEntry(BaseModel):
    unitId: str
    sourceId: str
    root: Dict[str, Any] = Field(default_factory=dict)

    class Config:
        extra = "forbid"


class JarvisGraphAnswer(BaseModel):
    graphId: str
    sources: List[str] = Field(default_factory=list)
    queryEntries: List[JarvisGraphAnswerQueryEntry] = Field(default_factory=list)
    text: str
    complete: bool = True
    diagnostics: List[JarvisQueryDiagnostic] = Field(default_factory=list)

    class Config:
        extra = "forbid"


class JarvisHumanAnswerResponse(BaseModel):
    answerLanguage: str
    answers: List[JarvisGraphAnswer] = Field(default_factory=list)
    diagnostics: List[JarvisQueryDiagnostic] = Field(default_factory=list)

    class Config:
        extra = "forbid"
