from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Extra, Field, validator


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
