from __future__ import annotations

from typing import Any, Dict, List

from pydantic import BaseModel, Field


class SemanticIndexBuildRequest(BaseModel):
    sourceIds: List[str] = Field(default_factory=list)
    force: bool = False
    async_: bool = Field(default=True, alias="async")

    class Config:
        allow_population_by_field_name = True
        extra = "forbid"


class SemanticIndexBuildResponse(BaseModel):
    jobId: str
    status: str
    sourceIds: List[str] = Field(default_factory=list)
    diagnostics: List[Dict[str, Any]] = Field(default_factory=list)
    results: List[Dict[str, Any]] = Field(default_factory=list)
