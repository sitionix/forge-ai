from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping

from knowledge_service.knowledge_query_schema import KnowledgeGraphAnswerQueryEntry, KnowledgeQueryDiagnostic


class NarrationClauseKind(str, Enum):
    UNIT_INTRODUCTION = "UNIT_INTRODUCTION"
    UNIT_ROOTS = "UNIT_ROOTS"
    UNIT_ANCHORS = "UNIT_ANCHORS"
    UNIT_EXECUTION_NODES = "UNIT_EXECUTION_NODES"
    UNIT_LOCAL_TRANSITIONS = "UNIT_LOCAL_TRANSITIONS"
    UNIT_TOPOLOGY_BOUNDARIES = "UNIT_TOPOLOGY_BOUNDARIES"
    UNIT_GENERIC_BOUNDARIES = "UNIT_GENERIC_BOUNDARIES"
    UNIT_SUPPORTING_CONTEXT = "UNIT_SUPPORTING_CONTEXT"
    UNIT_EVIDENCE = "UNIT_EVIDENCE"
    UNIT_COVERAGE = "UNIT_COVERAGE"
    PROVEN_CONTINUATION = "PROVEN_CONTINUATION"
    OPEN_BOUNDARY = "OPEN_BOUNDARY"
    BRANCH = "BRANCH"
    CONVERGENCE = "CONVERGENCE"
    CYCLE_REFERENCE = "CYCLE_REFERENCE"
    SHARED_UNIT_REFERENCE = "SHARED_UNIT_REFERENCE"


class NarrationSemanticOperation(str, Enum):
    PRESENT_UNIT = "PRESENT_UNIT"
    PRESENT_UNIT_ROOTS = "PRESENT_UNIT_ROOTS"
    PRESENT_QUERY_ANCHORS = "PRESENT_QUERY_ANCHORS"
    PRESENT_EXECUTION_NODES = "PRESENT_EXECUTION_NODES"
    EXECUTES_LOCAL_TRANSITION = "EXECUTES_LOCAL_TRANSITION"
    PRESENT_TOPOLOGY_BOUNDARY = "PRESENT_TOPOLOGY_BOUNDARY"
    PRESENT_GENERIC_BOUNDARY = "PRESENT_GENERIC_BOUNDARY"
    PRESENT_SUPPORTING_CONTEXT = "PRESENT_SUPPORTING_CONTEXT"
    PRESENT_EVIDENCE = "PRESENT_EVIDENCE"
    PRESENT_COVERAGE = "PRESENT_COVERAGE"
    CONTINUES_WITH_PROVEN_TARGET = "CONTINUES_WITH_PROVEN_TARGET"
    HAS_AMBIGUOUS_CONTINUATION = "HAS_AMBIGUOUS_CONTINUATION"
    HAS_UNRESOLVED_CONTINUATION = "HAS_UNRESOLVED_CONTINUATION"
    BRANCHES_TO = "BRANCHES_TO"
    CONVERGES_AT = "CONVERGES_AT"
    REFERENCES_CYCLE = "REFERENCES_CYCLE"
    REFERENCES_SHARED_UNIT = "REFERENCES_SHARED_UNIT"


class FormatterAssertionPredicate(str, Enum):
    BOUNDARY_STATUS = "BOUNDARY_STATUS"
    PROOF_STATUS = "PROOF_STATUS"
    TARGET_SELECTION_STATUS = "TARGET_SELECTION_STATUS"
    CANDIDATE_CARDINALITY = "CANDIDATE_CARDINALITY"
    CONNECTIVITY_STATUS = "CONNECTIVITY_STATUS"
    UNIT_STATUS = "UNIT_STATUS"
    LOCAL_EXECUTION_STATUS = "LOCAL_EXECUTION_STATUS"
    STRUCTURAL_RELATION = "STRUCTURAL_RELATION"


class FormatterAssertionValue(str, Enum):
    AMBIGUOUS = "AMBIGUOUS"
    UNRESOLVED = "UNRESOLVED"
    PROVEN = "PROVEN"
    NOT_PROVEN = "NOT_PROVEN"
    NONE = "NONE"
    MULTIPLE = "MULTIPLE"
    UNIT_PRESENT = "UNIT_PRESENT"
    HAS_LOCAL_TRANSITIONS = "HAS_LOCAL_TRANSITIONS"
    NO_LOCAL_TRANSITIONS = "NO_LOCAL_TRANSITIONS"
    HAS_OPEN_TOPOLOGY_BOUNDARY = "HAS_OPEN_TOPOLOGY_BOUNDARY"
    TRUNCATED = "TRUNCATED"
    COMPLETE = "COMPLETE"
    BRANCH = "BRANCH"
    CONVERGENCE = "CONVERGENCE"
    CYCLE = "CYCLE"
    SHARED_UNIT = "SHARED_UNIT"


class CanonicalReferenceKind(str, Enum):
    UNIT = "unit"
    SOURCE = "source"
    ROOT = "root"
    ANCHOR = "anchor"
    NODE = "node"
    EDGE = "edge"
    TOPOLOGY_BOUNDARY = "topology-boundary"
    GENERIC_BOUNDARY = "generic-boundary"
    CONTEXT = "context"
    EVIDENCE = "evidence"
    COVERAGE = "coverage"
    TRANSITION = "transition"
    RESOLUTION = "resolution"
    REQUIRED_BOUNDARY = "required-boundary"
    PROVIDED_BOUNDARY = "provided-boundary"
    OPEN_BOUNDARY = "open-boundary"
    BRANCH = "branch"
    CONVERGENCE = "convergence"
    CYCLE = "cycle"
    SHARED_UNIT = "shared-unit"
    CANDIDATE_OWNER = "candidate-owner"
    CANDIDATE_BOUNDARY = "candidate-boundary"


@dataclass(frozen=True)
class CanonicalFormatterAssertion:
    assertion_ref: str
    predicate: FormatterAssertionPredicate
    subject_ref: str
    object_ref: str | None = None
    value: FormatterAssertionValue | str | None = None

    def to_audit_payload(self) -> dict[str, str | None]:
        return {
            "assertionRef": self.assertion_ref,
            "predicate": self.predicate.value,
            "subjectRef": self.subject_ref,
            "objectRef": self.object_ref,
            "value": self.value.value if isinstance(self.value, FormatterAssertionValue) else self.value,
        }


@dataclass(frozen=True)
class CanonicalFactOwnership:
    fact_ref: str
    owner_clause_ref: str


@dataclass(frozen=True)
class CanonicalNarrationClause:
    clause_ref: str
    clause_kind: NarrationClauseKind
    semantic_operation: NarrationSemanticOperation
    subject_refs: tuple[str, ...]
    object_refs: tuple[str, ...]
    qualifier_refs: tuple[str, ...]
    canonical_fact_refs: tuple[str, ...]
    display_values: Mapping[str, str]
    ordering_key: tuple[str, ...]
    required_assertions: tuple[CanonicalFormatterAssertion, ...] = ()
    allowed_canonical_refs: tuple[str, ...] = ()


@dataclass(frozen=True)
class CanonicalNarrationPlan:
    graph_id: str
    response_language: str
    clauses: tuple[CanonicalNarrationClause, ...]
    canonical_fact_ownership: tuple[CanonicalFactOwnership, ...]
    complete: bool
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()
    sources: tuple[str, ...] = ()
    query_entries: tuple[KnowledgeGraphAnswerQueryEntry, ...] = ()
    topology_entries: tuple[str, ...] = ()
    planning_duration_ms: float = 0.0

    @property
    def canonical_fact_refs(self) -> tuple[str, ...]:
        return tuple(item.fact_ref for item in self.canonical_fact_ownership)


@dataclass(frozen=True)
class CycleMembership:
    cycle_unit_ids: tuple[str, ...]
    cycle_transition_ids: tuple[str, ...]


@dataclass(frozen=True)
class FormatterValidationSummary:
    missing_clause_refs: tuple[str, ...] = field(default_factory=tuple)
    duplicate_clause_refs: tuple[str, ...] = field(default_factory=tuple)
    unknown_clause_refs: tuple[str, ...] = field(default_factory=tuple)
    omitted_fact_refs: tuple[str, ...] = field(default_factory=tuple)
    duplicate_fact_refs: tuple[str, ...] = field(default_factory=tuple)
    unowned_fact_refs: tuple[str, ...] = field(default_factory=tuple)
    validated_clause_count: int = 0
    public_clause_count: int = 0
    narration_contract_matched: bool = False
    errors: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class CanonicalNarrationMetrics:
    selected_graph_count: int = 0
    answer_count: int = 0
    narration_clause_count: int = 0
    narration_clause_refs: tuple[str, ...] = field(default_factory=tuple)
    narration_clause_kinds: tuple[str, ...] = field(default_factory=tuple)
    narration_semantic_operations: tuple[str, ...] = field(default_factory=tuple)
    canonical_fact_count: int = 0
    canonical_fact_ownership: tuple[CanonicalFactOwnership, ...] = field(default_factory=tuple)
    duplicate_canonical_fact_count: int = 0
    unowned_canonical_fact_count: int = 0
    missing_clause_count: int = 0
    duplicate_clause_count: int = 0
    unknown_clause_count: int = 0
    validated_clause_count: int = 0
    public_clause_count: int = 0
    narration_contract_matched: bool = False
    proven_transition_clause_count: int = 0
    ambiguous_boundary_clause_count: int = 0
    unresolved_boundary_clause_count: int = 0
    branch_clause_count: int = 0
    convergence_clause_count: int = 0
    cycle_clause_count: int = 0
    shared_unit_clause_count: int = 0
    formatter_segment_count: int = 0
    formatter_serialization_count: int = 0
    formatter_provider_call_count: int = 0
    formatter_repair_call_count: int = 0
    narration_planning_duration_ms: float = 0.0
    formatter_duration_ms: float = 0.0
    total_formatter_duration_ms: float = 0.0

    @classmethod
    def empty(cls, *, selected_graph_count: int = 0, answer_count: int = 0) -> CanonicalNarrationMetrics:
        return cls(selected_graph_count=int(selected_graph_count), answer_count=int(answer_count))

    def to_audit_payload(self) -> dict[str, object]:
        return {
            "selectedGraphCount": self.selected_graph_count,
            "answerCount": self.answer_count,
            "narrationClauseCount": self.narration_clause_count,
            "narrationClauseRefs": list(self.narration_clause_refs),
            "narrationClauseKinds": list(self.narration_clause_kinds),
            "narrationSemanticOperations": list(self.narration_semantic_operations),
            "canonicalFactCount": self.canonical_fact_count,
            "canonicalFactOwnership": [
                {"factRef": item.fact_ref, "ownerClauseRef": item.owner_clause_ref}
                for item in self.canonical_fact_ownership
            ],
            "duplicateCanonicalFactCount": self.duplicate_canonical_fact_count,
            "unownedCanonicalFactCount": self.unowned_canonical_fact_count,
            "missingClauseCount": self.missing_clause_count,
            "duplicateClauseCount": self.duplicate_clause_count,
            "unknownClauseCount": self.unknown_clause_count,
            "validatedClauseCount": self.validated_clause_count,
            "publicClauseCount": self.public_clause_count,
            "narrationContractMatched": self.narration_contract_matched,
            "provenTransitionClauseCount": self.proven_transition_clause_count,
            "ambiguousBoundaryClauseCount": self.ambiguous_boundary_clause_count,
            "unresolvedBoundaryClauseCount": self.unresolved_boundary_clause_count,
            "branchClauseCount": self.branch_clause_count,
            "convergenceClauseCount": self.convergence_clause_count,
            "cycleClauseCount": self.cycle_clause_count,
            "sharedUnitClauseCount": self.shared_unit_clause_count,
            "formatterSegmentCount": self.formatter_segment_count,
            "formatterSerializationCount": self.formatter_serialization_count,
            "formatterProviderCallCount": self.formatter_provider_call_count,
            "formatterRepairCallCount": self.formatter_repair_call_count,
            "narrationPlanningDurationMs": self.narration_planning_duration_ms,
            "formatterDurationMs": self.formatter_duration_ms,
            "totalFormatterDurationMs": self.total_formatter_duration_ms,
        }


def canonical_ref(kind: CanonicalReferenceKind, *parts: object) -> str:
    return ":".join((kind.value, *(str(part) for part in parts if str(part).strip())))


def sorted_unique(values: tuple[str, ...] | list[str] | set[str]) -> tuple[str, ...]:
    return tuple(sorted(dict.fromkeys(str(item) for item in values if str(item).strip())))


def valid_canonical_ref(value: object) -> bool:
    text = str(value or "").strip()
    if not text or ":" not in text:
        return False
    prefix = text.split(":", 1)[0]
    return prefix in {item.value for item in CanonicalReferenceKind}
