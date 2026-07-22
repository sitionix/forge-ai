from __future__ import annotations

import re
from dataclasses import dataclass, replace


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

    def with_direction(self, direction_role: str | None) -> "AvailableOperationFact":
        return replace(self, direction_role=direction_role)

    def operation_key(self, fragment_key: str) -> tuple[str, str, str, str, str, str]:
        return (
            fragment_key,
            normalize_transport_kind(self.transport_kind) or "",
            normalize_http_method(self.method) or "",
            normalize_route(self.normalized_route) or "",
            self.operation_identity or self.interface_identity or "",
            self.structural_owner,
        )


_HTTP_METHODS = {"DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT", "TRACE"}


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
