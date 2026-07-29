from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from knowledge_service.canonical_narration_contract import CanonicalNarrationClause


class EndToEndFormatterError(RuntimeError):
    pass


class EndToEndFormatterDeadlineExceeded(TimeoutError):
    pass


class EndToEndFormatterAllGraphsFailed(EndToEndFormatterError):
    pass


class EndToEndFormatterValidationError(EndToEndFormatterError):
    def __init__(self, errors: tuple[str, ...] | list[str]) -> None:
        self.errors = tuple(str(item) for item in errors if str(item).strip())
        super().__init__("; ".join(self.errors) or "canonical formatter validation failed")


class EndToEndFormatterProviderError(EndToEndFormatterError):
    pass


class EndToEndFormatterClauseTooLarge(EndToEndFormatterValidationError):
    def __init__(self, *, graph_id: str, clause_ref: str, serialized_character_count: int, configured_character_budget: int) -> None:
        self.graph_id = graph_id
        self.clause_ref = clause_ref
        self.serialized_character_count = serialized_character_count
        self.configured_character_budget = configured_character_budget
        super().__init__(
            (
                "END_TO_END_FORMATTER_CLAUSE_TOO_LARGE",
                f"graphId={graph_id}",
                f"clauseRef={clause_ref}",
                f"serializedCharacterCount={serialized_character_count}",
                f"configuredCharacterBudget={configured_character_budget}",
            )
        )


@dataclass(frozen=True)
class EndToEndFormatterSegment:
    segment_ref: str
    graph_id: str
    response_language: str
    clause_refs: tuple[str, ...]
    clauses: tuple[CanonicalNarrationClause, ...]
    formatter_input: Mapping[str, Any]
    prompt_hash_seed: str


@dataclass(frozen=True)
class EndToEndFormatterProviderResult:
    raw_text: str
    prompt_char_length: int
    prompt_hash: str
    duration_ms: float
    provider_name: str | None = None
    provider_model: str | None = None


@dataclass(frozen=True)
class ValidatedFormatterClause:
    clause_ref: str
    referenced_canonical_refs: tuple[str, ...]
    text_template: str
    text: str
