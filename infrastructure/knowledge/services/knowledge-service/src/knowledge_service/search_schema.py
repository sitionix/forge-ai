from __future__ import annotations

from typing import List

from pydantic import BaseModel, Field


class SearchRequest(BaseModel):
    query: str
    sourceIds: List[str] = Field(default_factory=list)
    groups: List[str] = Field(default_factory=list)
    limit: int = 20


class InventoryBuildRequest(BaseModel):
    sourceIds: List[str] = Field(default_factory=list)
    groups: List[str] = Field(default_factory=list)
    force: bool = False
