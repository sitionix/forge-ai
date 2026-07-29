from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from knowledge_service.flow_graph_contract import FlowGraphEdge
from knowledge_service.graph_query_contract import graph_query_contract


class FlowBoundaryKind(str, Enum):
    CURRENT_TARGET_NODE_MISSING = "CURRENT_TARGET_NODE_MISSING"
    CROSS_SOURCE_TARGET = "CROSS_SOURCE_TARGET"
    EXTERNAL = "EXTERNAL"
    UNRESOLVED = "UNRESOLVED"


@dataclass(frozen=True)
class FlowBoundaryProjection:
    kind: FlowBoundaryKind
    target: str | None
    resolution_status: str


class FlowBoundaryClassifier:
    def project(self, edge: FlowGraphEdge) -> FlowBoundaryProjection:
        target = self._target_descriptor(edge)
        resolution_status = str(edge.resolution_status or "RESOLVED")
        boundary_reason = str(edge.boundary_reason or "").strip().upper()
        if boundary_reason == FlowBoundaryKind.CURRENT_TARGET_NODE_MISSING.value:
            return FlowBoundaryProjection(
                kind=FlowBoundaryKind.CURRENT_TARGET_NODE_MISSING,
                target=target,
                resolution_status=resolution_status,
            )
        if boundary_reason == FlowBoundaryKind.CROSS_SOURCE_TARGET.value:
            return FlowBoundaryProjection(
                kind=FlowBoundaryKind.CROSS_SOURCE_TARGET,
                target=target or edge.to_node_id,
                resolution_status=resolution_status,
            )
        if self._is_external(edge):
            return FlowBoundaryProjection(
                kind=FlowBoundaryKind.EXTERNAL,
                target=target,
                resolution_status=resolution_status,
            )
        return FlowBoundaryProjection(
            kind=FlowBoundaryKind.UNRESOLVED,
            target=target,
            resolution_status=resolution_status,
        )

    def _is_external(self, edge: FlowGraphEdge) -> bool:
        external_status = graph_query_contract().external_target_status.upper()
        return bool(edge.external) or str(edge.resolution_status or "").upper() == external_status

    def _target_descriptor(self, edge: FlowGraphEdge) -> str | None:
        target = edge.unresolved_target or {}
        if not isinstance(target, dict):
            return None
        for key in ("name", "qualifiedName", "target", "kindHint", "displayName", "label", "symbol"):
            value = target.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
        return None


FLOW_BOUNDARY_CLASSIFIER = FlowBoundaryClassifier()
