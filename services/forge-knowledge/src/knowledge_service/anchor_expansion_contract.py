from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass(frozen=True)
class AnchorExpansionRequest:
    source_id: str
    graph_id: str
    graph_revision: Optional[str]
    node_id: str


@dataclass(frozen=True)
class AnchorExpansionNode:
    source_id: str
    graph_id: str
    graph_revision: Optional[str]
    node_id: str
    stable_key: str
    node_kind: str
    label: str
    parent_node_id: Optional[str] = None
    relative_path: Optional[str] = None
    qualified_name: Optional[str] = None
    entrypoint: bool = False
    score: Optional[float] = None


@dataclass(frozen=True)
class AnchorExpansionEdge:
    source_id: str
    graph_id: str
    graph_revision: Optional[str]
    edge_id: str
    edge_type: str
    from_node_id: str
    to_node_id: str


@dataclass(frozen=True)
class AnchorEntrypointHint:
    source_id: str
    graph_id: str
    graph_revision: Optional[str]
    node_id: str
    claim_id: str


@dataclass(frozen=True)
class AnchorExpansionBundle:
    nodes: Tuple[AnchorExpansionNode, ...] = ()
    edges: Tuple[AnchorExpansionEdge, ...] = ()
    entrypoint_hints: Tuple[AnchorEntrypointHint, ...] = ()
    truncated: bool = False
