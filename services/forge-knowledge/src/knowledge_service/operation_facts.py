from __future__ import annotations

import re
from dataclasses import dataclass, replace
from typing import Sequence


@dataclass(frozen=True)
class OperationFactEvidence:
    source_id: str
    relative_path: str | None = None
    line_start: int | None = None
    line_end: int | None = None
    excerpt: str | None = None


@dataclass(frozen=True)
class OperationFactEligibility:
    status: str | None = None
    rejection_reason: str | None = None
    flow_domain: str | None = None
    inventory_current: bool = False
    analyzed_current: bool = False


@dataclass(frozen=True)
class AvailableOperationFact:
    owner_source_id: str
    owner_graph_id: str
    owner_graph_revision: str | None
    owner_node_id: str
    source_id: str
    execution_role: str | None
    transport_kind: str | None
    direction_role: str | None
    method: str | None
    normalized_route: str | None
    topic: str | None = None
    schedule: str | None = None
    operation_identity: str | None = None
    interface_identity: str | None = None
    request_contract_identity: str | None = None
    response_contract_identity: str | None = None
    target_service_identity: str | None = None
    owner_qualified_name: str | None = None
    owner_relative_path: str | None = None
    owner_edge_id: str | None = None
    evidence: tuple[OperationFactEvidence, ...] = ()
    eligibility: OperationFactEligibility | None = None
    source_channel: str = "ENTRYPOINT_HINT"

    @property
    def owner_key(self) -> tuple[str, str, str]:
        return (self.owner_source_id, self.owner_graph_revision or self.owner_graph_id, self.owner_node_id)

    @property
    def structural_owner(self) -> str:
        edge_part = f":{self.owner_edge_id}" if self.owner_edge_id else ""
        return ":".join((self.owner_source_id, self.owner_graph_revision or self.owner_graph_id, self.owner_node_id)) + edge_part

    @property
    def semantic_owner(self) -> str:
        return ":".join((self.owner_source_id, self.owner_graph_revision or self.owner_graph_id, self.owner_node_id))

    def with_direction(self, direction_role: str | None) -> "AvailableOperationFact":
        return replace(self, direction_role=direction_role)

    def operation_key(self, fragment_key: str) -> tuple[str, str, str, str, str, str]:
        return (
            fragment_key,
            normalize_transport_kind(self.transport_kind) or "",
            normalize_http_method(self.method) or "",
            normalize_route(self.normalized_route) or "",
            self.operation_identity or self.interface_identity or "",
            self.semantic_owner,
        )


_HTTP_METHODS = {"DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE"}
_SEMANTIC_IDENTITY_FIELDS = (
    "operation_identity",
    "interface_identity",
    "request_contract_identity",
    "response_contract_identity",
    "target_service_identity",
)


def normalize_transport_kind(value: object) -> str | None:
    text = str(value or "").strip().upper()
    return text or None


def normalize_http_method(value: object) -> str | None:
    text = str(value or "").strip().upper()
    if not text:
        return None
    return text


def normalize_route(value: object) -> str | None:
    text = str(value or "").strip()
    if not text:
        return None
    text = re.sub(r"/+", "/", text)
    if not text.startswith("/"):
        text = f"/{text}"
    if len(text) > 1:
        text = text.rstrip("/")
    return text


def clean_identity(value: object) -> str | None:
    text = str(value or "").strip()
    return text or None


def split_operation_interface_identity(value: object) -> tuple[str | None, str | None]:
    text = clean_identity(value)
    if not text:
        return None, None
    upper = text.upper()
    if any(upper.startswith(f"{method} ") or upper.startswith(f"HTTP {method} ") for method in _HTTP_METHODS):
        return text, None
    if "/" in text and any(method in upper.split() for method in _HTTP_METHODS):
        return text, None
    return None, text


def edge_backed_http_direction(metadata: dict[str, object], *, execution_continuation: bool) -> str | None:
    if not execution_continuation:
        return None
    raw = str(metadata.get("directionRole") or "").strip().upper()
    if raw:
        return "OUTBOUND" if raw == "OUTBOUND" else None
    return "OUTBOUND"


def merge_semantic_operation_facts(facts: Sequence[AvailableOperationFact]) -> tuple[AvailableOperationFact, ...]:
    groups: list[AvailableOperationFact] = []
    for fact in sorted(facts, key=_semantic_fact_processing_key):
        matches = [
            index
            for index, existing in enumerate(groups)
            if semantic_operation_facts_compatible(existing, fact)
        ]
        if len(matches) == 1:
            groups[matches[0]] = merge_available_operation_facts(groups[matches[0]], fact)
            continue
        if len(matches) > 1 and not _has_semantic_identity(fact):
            groups.append(fact)
            continue
        if matches:
            groups[matches[0]] = merge_available_operation_facts(groups[matches[0]], fact)
            continue
        groups.append(fact)
    return tuple(sorted(groups, key=semantic_operation_fact_sort_key))


def semantic_operation_facts_compatible(left: AvailableOperationFact, right: AvailableOperationFact) -> bool:
    if semantic_operation_base_key(left) != semantic_operation_base_key(right):
        return False
    for field in _SEMANTIC_IDENTITY_FIELDS:
        left_value = clean_identity(getattr(left, field))
        right_value = clean_identity(getattr(right, field))
        if left_value and right_value and left_value != right_value:
            return False
    return True


def semantic_operation_base_key(fact: AvailableOperationFact) -> tuple[str, str, str, str, str, str, str]:
    return (
        fact.owner_source_id,
        fact.owner_graph_revision or fact.owner_graph_id,
        fact.owner_node_id,
        str(fact.direction_role or "").strip().upper(),
        normalize_transport_kind(fact.transport_kind) or "",
        normalize_http_method(fact.method) or "",
        normalize_route(fact.normalized_route) or "",
    )


def semantic_operation_fact_sort_key(fact: AvailableOperationFact) -> tuple[str, str, str, str, str, str, str, str, str, str, str, str]:
    return (
        *semantic_operation_base_key(fact),
        clean_identity(fact.operation_identity) or "",
        clean_identity(fact.interface_identity) or "",
        clean_identity(fact.target_service_identity) or "",
        clean_identity(fact.owner_edge_id) or "",
        clean_identity(fact.source_channel) or "",
    )


def merge_available_operation_facts(left: AvailableOperationFact, right: AvailableOperationFact) -> AvailableOperationFact:
    primary, secondary = sorted((left, right), key=_fact_preference_key)
    return replace(
        primary,
        transport_kind=normalize_transport_kind(_choose(primary.transport_kind, secondary.transport_kind)),
        direction_role=str(_choose(primary.direction_role, secondary.direction_role) or "").strip().upper() or None,
        method=normalize_http_method(_choose(primary.method, secondary.method)),
        normalized_route=normalize_route(_choose(primary.normalized_route, secondary.normalized_route)),
        topic=clean_identity(_choose(primary.topic, secondary.topic)),
        schedule=clean_identity(_choose(primary.schedule, secondary.schedule)),
        operation_identity=clean_identity(_choose(primary.operation_identity, secondary.operation_identity)),
        interface_identity=clean_identity(_choose(primary.interface_identity, secondary.interface_identity)),
        request_contract_identity=clean_identity(_choose(primary.request_contract_identity, secondary.request_contract_identity)),
        response_contract_identity=clean_identity(_choose(primary.response_contract_identity, secondary.response_contract_identity)),
        target_service_identity=clean_identity(_choose(primary.target_service_identity, secondary.target_service_identity)),
        owner_qualified_name=clean_identity(_choose(primary.owner_qualified_name, secondary.owner_qualified_name)),
        owner_relative_path=clean_identity(_choose(primary.owner_relative_path, secondary.owner_relative_path)),
        owner_edge_id=clean_identity(_choose(primary.owner_edge_id, secondary.owner_edge_id)),
        evidence=_merge_operation_fact_evidence(primary.evidence, secondary.evidence),
        eligibility=_merge_operation_fact_eligibility(primary.eligibility, secondary.eligibility),
    )


def _choose(primary: object, secondary: object) -> object:
    return primary if clean_identity(primary) else secondary


def _has_semantic_identity(fact: AvailableOperationFact) -> bool:
    return any(clean_identity(getattr(fact, field)) for field in _SEMANTIC_IDENTITY_FIELDS)


def _semantic_fact_processing_key(fact: AvailableOperationFact) -> tuple[int, tuple[str, str, str, str, str, str, str], tuple[str, str, str, str, str], str]:
    return (
        0 if _has_semantic_identity(fact) else 1,
        semantic_operation_base_key(fact),
        tuple(clean_identity(getattr(fact, field)) or "" for field in _SEMANTIC_IDENTITY_FIELDS),
        fact.structural_owner,
    )


def _fact_preference_key(fact: AvailableOperationFact) -> tuple[int, int, int, str]:
    eligibility = fact.eligibility
    current = bool(eligibility and eligibility.inventory_current and eligibility.analyzed_current)
    source_rank = 1 if str(fact.source_channel or "") == "EDGE_METADATA" else 0
    return (
        0 if current else 1,
        source_rank,
        -_metadata_richness(fact),
        fact.structural_owner,
    )


def _metadata_richness(fact: AvailableOperationFact) -> int:
    values = (
        fact.execution_role,
        fact.transport_kind,
        fact.direction_role,
        fact.method,
        fact.normalized_route,
        fact.topic,
        fact.schedule,
        fact.operation_identity,
        fact.interface_identity,
        fact.request_contract_identity,
        fact.response_contract_identity,
        fact.target_service_identity,
        fact.owner_qualified_name,
        fact.owner_relative_path,
        fact.owner_edge_id,
    )
    return sum(1 for value in values if clean_identity(value)) + len(fact.evidence)


def _merge_operation_fact_evidence(
    left: Sequence[OperationFactEvidence],
    right: Sequence[OperationFactEvidence],
) -> tuple[OperationFactEvidence, ...]:
    by_key: dict[tuple[str, str, int, int, str], OperationFactEvidence] = {}
    for item in (*left, *right):
        key = (
            item.source_id,
            item.relative_path or "",
            item.line_start if item.line_start is not None else -1,
            item.line_end if item.line_end is not None else -1,
            item.excerpt or "",
        )
        by_key.setdefault(key, item)
    return tuple(by_key[key] for key in sorted(by_key))


def _merge_operation_fact_eligibility(
    primary: OperationFactEligibility | None,
    secondary: OperationFactEligibility | None,
) -> OperationFactEligibility | None:
    if primary is None:
        return secondary
    if secondary is None:
        return primary
    return OperationFactEligibility(
        status=clean_identity(primary.status) or clean_identity(secondary.status),
        rejection_reason=clean_identity(primary.rejection_reason) or clean_identity(secondary.rejection_reason),
        flow_domain=clean_identity(primary.flow_domain) or clean_identity(secondary.flow_domain),
        inventory_current=primary.inventory_current or secondary.inventory_current,
        analyzed_current=primary.analyzed_current or secondary.analyzed_current,
    )
