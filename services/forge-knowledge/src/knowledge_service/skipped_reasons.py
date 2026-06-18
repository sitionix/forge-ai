from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Dict


class SkippedReason(str, Enum):
    EXCLUDED_BY_PATTERN = "EXCLUDED_BY_PATTERN"
    NOT_INCLUDED = "NOT_INCLUDED"
    TOO_LARGE = "TOO_LARGE"
    BINARY = "BINARY"
    UNREADABLE = "UNREADABLE"
    UNSAFE_PATH = "UNSAFE_PATH"
    SYMLINK_OUTSIDE_ROOT = "SYMLINK_OUTSIDE_ROOT"
    MISSING_SOURCE_ROOT = "MISSING_SOURCE_ROOT"
    UNKNOWN = "UNKNOWN"


@dataclass
class SkippedBreakdown:
    by_reason: Dict[str, int] = field(default_factory=dict)

    def increment(self, reason: SkippedReason, count: int = 1) -> None:
        if count <= 0:
            return
        key = reason.value
        self.by_reason[key] = self.by_reason.get(key, 0) + count

    def merge(self, other: "SkippedBreakdown") -> None:
        for reason, count in other.by_reason.items():
            if count > 0:
                self.by_reason[reason] = self.by_reason.get(reason, 0) + count

    @property
    def total(self) -> int:
        return sum(self.by_reason.values())

    def public_dict(self) -> Dict[str, object]:
        return {
            "total": self.total,
            "byReason": {reason: count for reason, count in self.by_reason.items() if count > 0},
        }


def normalize_skipped_breakdown(value: object, skipped_count: int = 0) -> Dict[str, object]:
    if not isinstance(value, dict):
        return {"total": skipped_count or 0, "byReason": {}}
    by_reason = value.get("byReason")
    if not isinstance(by_reason, dict):
        by_reason = {}
    normalized = {
        str(reason): int(count)
        for reason, count in by_reason.items()
        if isinstance(count, int) and count > 0
    }
    total = value.get("total")
    if not isinstance(total, int):
        total = sum(normalized.values()) if normalized else skipped_count or 0
    return {"total": total, "byReason": normalized}
