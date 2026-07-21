from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Mapping, Sequence

from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphNode
from knowledge_service.graph_query_contract import GraphQueryContract, graph_query_contract


EXECUTION_CONTINUATION = "executionContinuation"
SUPPORTING_RELATION = "supportingRelation"
ROOT_ELIGIBILITY = "rootEligibility"
TRANSPORT_CONNECTOR = "transportConnector"
FAMILY_TRAVERSAL = "familyTraversal"

EXECUTABLE_ENTRYPOINT = "executableEntrypoint"
CONTRACT_DECLARATION = "contractDeclaration"
CLIENT_OPERATION = "clientOperation"
SUPPORTING_DECLARATION = "supportingDeclaration"
INFERRED_TOPOLOGY_ROOT = "inferredTopologyRoot"


@dataclass(frozen=True)
class GraphRelationSemantics:
    edge_relation_semantics: Mapping[str, tuple[str, ...]]
    executable_entrypoint_role: str = EXECUTABLE_ENTRYPOINT
    contract_declaration_role: str = CONTRACT_DECLARATION
    client_operation_role: str = CLIENT_OPERATION
    supporting_declaration_role: str = SUPPORTING_DECLARATION
    inferred_topology_root_role: str = INFERRED_TOPOLOGY_ROOT

    @classmethod
    def from_query_contract(cls, contract: GraphQueryContract | None = None) -> "GraphRelationSemantics":
        resolved = contract or graph_query_contract()
        return cls(edge_relation_semantics=resolved.edge_relation_semantics)

    def edge_semantics(self, edge_type: str) -> tuple[str, ...]:
        return tuple(self.edge_relation_semantics.get(str(edge_type or "").upper(), ()))

    def edge_types_with(self, behavior: str) -> tuple[str, ...]:
        requested = str(behavior or "")
        return tuple(
            sorted(
                edge_type
                for edge_type, behaviors in self.edge_relation_semantics.items()
                if requested in set(behaviors)
            )
        )

    def is_execution_continuation(self, edge: FlowGraphEdge) -> bool:
        return EXECUTION_CONTINUATION in self.edge_semantics(edge.edge_type)

    def is_supporting_relation(self, edge: FlowGraphEdge) -> bool:
        return SUPPORTING_RELATION in self.edge_semantics(edge.edge_type)

    def is_family_traversal(self, edge: FlowGraphEdge) -> bool:
        return FAMILY_TRAVERSAL in self.edge_semantics(edge.edge_type)

    def is_transport_connector(self, edge: FlowGraphEdge) -> bool:
        if TRANSPORT_CONNECTOR in self.edge_semantics(edge.edge_type):
            return True
        metadata = edge.metadata if isinstance(edge.metadata, dict) else {}
        return bool(metadata.get("transportConnector"))

    def execution_role(self, node: FlowGraphNode) -> str:
        raw_role = str(node.execution_role or "").strip().upper()
        if raw_role == "EXECUTABLE" and node.entrypoint:
            return self.executable_entrypoint_role
        if raw_role == "CONTRACT_DECLARATION":
            return self.contract_declaration_role
        if raw_role == "CLIENT_OPERATION":
            return self.client_operation_role
        if raw_role == "SUPPORTING_DECLARATION":
            return self.supporting_declaration_role
        if raw_role == "INFERRED_TOPOLOGY_ROOT":
            return self.inferred_topology_root_role
        if node.entrypoint:
            return self.executable_entrypoint_role
        return self.supporting_declaration_role

    def may_root_family(self, node: FlowGraphNode) -> bool:
        return self.execution_role(node) == self.executable_entrypoint_role


@lru_cache(maxsize=1)
def graph_relation_semantics() -> GraphRelationSemantics:
    return GraphRelationSemantics.from_query_contract()


def relation_semantics_for_edge_types(edge_types: Sequence[str], behavior: str) -> tuple[str, ...]:
    selected = set(str(edge_type or "").upper() for edge_type in edge_types)
    semantics = graph_relation_semantics()
    return tuple(edge_type for edge_type in semantics.edge_types_with(behavior) if edge_type in selected)
