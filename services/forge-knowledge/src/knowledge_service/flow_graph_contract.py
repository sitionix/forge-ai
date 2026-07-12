from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence, Tuple


FlowNodeKey = Tuple[str, str, str]
FlowEdgeKey = Tuple[str, str, str]


@dataclass(frozen=True)
class FlowGraphSourceScope:
    source_id: str
    graph_id: str
    graph_revision: str | None = None
    node_ids: tuple[str, ...] = ()
    include_tests: bool = False


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
    external: bool = False
    unresolved_target: dict[str, object] | None = None
    evidence_ids: tuple[str, ...] = ()
    flow_domain: str | None = None


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
class FlowGraphBundle:
    nodes: tuple[FlowGraphNode, ...] = ()
    edges: tuple[FlowGraphEdge, ...] = ()
    evidence: tuple[FlowGraphEvidence, ...] = ()
    truncated: bool = False


def dedupe_evidence(items: Sequence[FlowGraphEvidence]) -> tuple[FlowGraphEvidence, ...]:
    by_key: dict[tuple[str, str], FlowGraphEvidence] = {}
    for item in items:
        by_key.setdefault((item.source_id, item.evidence_id), item)
    return tuple(by_key[key] for key in sorted(by_key))
