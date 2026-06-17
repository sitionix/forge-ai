from __future__ import annotations

from dataclasses import dataclass
from typing import List

from pydantic import BaseModel, Field


class InventoryBuildRequest(BaseModel):
    sourceIds: List[str] = Field(default_factory=list)
    groups: List[str] = Field(default_factory=list)
    force: bool = False


@dataclass(frozen=True)
class InventoryBuildSummary:
    status: str
    sourceCount: int
    fileCount: int
    skippedCount: int
    startedAt: str
    completedAt: str
