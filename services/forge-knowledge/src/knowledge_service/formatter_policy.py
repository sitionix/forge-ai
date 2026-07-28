from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class FormatterPolicy:
    max_serialized_clause_chars: int = 12000
    max_serialized_segment_chars: int = 60000
    max_repair_attempts: int = 1
    max_clauses_per_segment: int = 24

    def __post_init__(self) -> None:
        if self.max_serialized_clause_chars < 1024:
            raise ValueError("max_serialized_clause_chars must be at least 1024")
        if self.max_serialized_segment_chars < self.max_serialized_clause_chars:
            raise ValueError("max_serialized_segment_chars must be at least max_serialized_clause_chars")
        if self.max_repair_attempts < 0:
            raise ValueError("max_repair_attempts must be non-negative")
        if self.max_clauses_per_segment < 1:
            raise ValueError("max_clauses_per_segment must be at least 1")
