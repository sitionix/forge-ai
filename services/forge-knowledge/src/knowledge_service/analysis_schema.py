from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Extra, Field, validator

class AnalysisBuildRequest(BaseModel):
    sourceIds: List[str] = Field(default_factory=list)
    groups: List[str] = Field(default_factory=list)
    force: bool = False
    maxFiles: Optional[int] = None
    concurrency: int = 1
    selection: str = "DEFAULT"

    class Config:
        extra = Extra.forbid

    @validator("concurrency")
    def concurrency_range(cls, value: int) -> int:
        return max(1, min(value, 3))

    @validator("selection")
    def selection_allowed(cls, value: str) -> str:
        normalized = str(value or "DEFAULT").upper()
        if normalized not in {"DEFAULT", "FAILED_ONLY"}:
            raise ValueError("Unsupported analysis selection")
        return normalized


class RetryFailedAnalysisRequest(BaseModel):
    sourceIds: List[str] = Field(default_factory=list)
    concurrency: int = 1
    idempotencyKey: Optional[str] = None

    class Config:
        extra = Extra.forbid

    @validator("concurrency")
    def retry_concurrency_range(cls, value: int) -> int:
        return max(1, min(value, 3))
