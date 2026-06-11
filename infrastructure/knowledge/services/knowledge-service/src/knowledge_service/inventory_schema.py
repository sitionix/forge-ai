from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class InventoryBuildSummary:
    status: str
    sourceCount: int
    fileCount: int
    skippedCount: int
    startedAt: str
    completedAt: str
