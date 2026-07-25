from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence, Tuple


FlowNodeKey = Tuple[str, str, str]
FlowEdgeKey = Tuple[str, str, str]


@dataclass(frozen=True)
class FlowGraphNode:
    source_id: str
    graph_id: str
    graph_revision: str | None
    node_id: str
    stable_key: str
    node_kind: str
    label: str
    qualified_name: str | None = None
    relative_path: str | None = None
    line_start: int | None = None
    line_end: int | None = None
    summary: str | None = None
    entrypoint: bool = False
    entrypoint_kind: str | None = None
    entrypoint_http_method: str | None = None
    entrypoint_route: str | None = None
    entrypoint_topic: str | None = None
    entrypoint_schedule: str | None = None
    entrypoint_interface_method: str | None = None
    flow_domain: str | None = None


@dataclass(frozen=True)
class FlowGraphEdge:
    source_id: str
    graph_id: str
    graph_revision: str | None
    edge_id: str
    edge_type: str
    from_node_id: str
    to_node_id: str | None
    resolution_status: str
    to_source_id: str | None = None
    to_graph_id: str | None = None
    to_graph_revision: str | None = None
    external: bool = False
    unresolved_target: dict[str, object] | None = None
    evidence_ids: tuple[str, ...] = ()
    flow_domain: str | None = None
    boundary_reason: str | None = None


@dataclass(frozen=True)
class FlowGraphEvidence:
    source_id: str
    graph_id: str
    graph_revision: str | None
    evidence_id: str
    node_id: str | None
    edge_id: str | None
    relative_path: str | None
    line_start: int | None
    line_end: int | None
    text: str | None


@dataclass(frozen=True)
class FlowGraphEvidenceKey:
    source_id: str
    evidence_id: str
    node_id: str | None = None
    edge_id: str | None = None


def evidence_key(item: FlowGraphEvidence) -> FlowGraphEvidenceKey:
    return FlowGraphEvidenceKey(
        source_id=item.source_id,
        evidence_id=item.evidence_id,
        node_id=item.node_id,
        edge_id=item.edge_id,
    )


def dedupe_evidence(items: Sequence[FlowGraphEvidence]) -> tuple[FlowGraphEvidence, ...]:
    by_key: dict[FlowGraphEvidenceKey, FlowGraphEvidence] = {}
    for item in items:
        by_key.setdefault(evidence_key(item), item)
    return tuple(by_key[key] for key in sorted(by_key, key=_evidence_key_sort))


def _evidence_key_sort(key: FlowGraphEvidenceKey) -> tuple[str, str, str, str]:
    return (key.source_id, key.evidence_id, key.node_id or "", key.edge_id or "")
