from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from knowledge_service.flow_graph_contract import FlowGraphEvidence, FlowNodeKey


@dataclass(frozen=True)
class LocalBoundaryDescriptor:
    descriptor_id: str
    path: str
    value_type: str
    value: Any
    origin: str
    confidence: float | None = None
    evidence: tuple[FlowGraphEvidence, ...] = ()


@dataclass(frozen=True)
class LocalBoundaryFact:
    boundary_id: str
    stable_key: str
    source_id: str
    graph_id: str
    graph_revision: str | None
    owner_node_id: str
    role: str
    status: str
    provenance: str | None
    confidence: float
    flow_domain: str | None
    descriptors: tuple[LocalBoundaryDescriptor, ...] = ()
    evidence: tuple[FlowGraphEvidence, ...] = ()

    @property
    def owner_key(self) -> FlowNodeKey:
        return (self.source_id, self.graph_revision or self.graph_id, self.owner_node_id)
