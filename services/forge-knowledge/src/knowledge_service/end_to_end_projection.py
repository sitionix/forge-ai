from __future__ import annotations

from dataclasses import asdict, is_dataclass
from typing import Any, Mapping, Sequence

from knowledge_service.boundary_resolution import BoundaryIdentity, BoundaryOwnerIdentity, EvidenceReference
from knowledge_service.end_to_end_flow import EndToEndFlowGraph
from knowledge_service.entrypoint_flow_engine import LocalFlowUnit
from knowledge_service.flow_graph_contract import FlowGraphEdge, FlowGraphEvidence, FlowGraphNode
from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryDiagnostic,
    KnowledgeQueryGraph,
    KnowledgeQueryGraphCoverage,
    KnowledgeQueryGraphOpenBoundary,
    KnowledgeQueryGraphTransition,
    KnowledgeQueryGraphUnit,
    KnowledgeQueryGraphUnitCoverage,
    KnowledgeQueryToolContextGraph,
    KnowledgeQueryToolContextResponse,
)


class EndToEndProjectionBuilder:
    def graphs(self, graphs: Sequence[EndToEndFlowGraph]) -> list[KnowledgeQueryGraph]:
        return [self.graph(graph) for graph in sorted(graphs or (), key=lambda item: item.stable_graph_id)]

    def graph(self, graph: EndToEndFlowGraph) -> KnowledgeQueryGraph:
        return KnowledgeQueryGraph(
            graphId=graph.stable_graph_id,
            queryEntryUnitIds=list(graph.query_entry_unit_ids),
            topologyEntryUnitIds=list(graph.topology_entry_unit_ids),
            units=[self.unit(ref.local_unit, query_selected=ref.query_selected_initial, recursively_discovered=ref.recursively_discovered) for ref in graph.unit_refs],
            provenTransitions=[self.transition(item) for item in graph.proven_cross_source_transitions],
            openBoundaries=[self.open_boundary(item) for item in graph.open_boundaries],
            complete=bool(graph.coverage.complete),
            coverage=self.coverage(graph.coverage),
            diagnostics=[
                KnowledgeQueryDiagnostic(code=item.code, message=item.message, severity=item.severity, sourceId=item.source_id, metadata=dict(item.metadata or {}))
                for item in graph.diagnostics
            ],
        )

    def tool_response(self, request: Any, execution: Any) -> KnowledgeQueryToolContextResponse:
        graphs = [KnowledgeQueryToolContextGraph(**graph.dict()) for graph in self.graphs(getattr(execution, "selected_graphs", ()) or ())]
        return KnowledgeQueryToolContextResponse(
            queryText=request.queryText,
            graphs=graphs,
            diagnostics=list(getattr(getattr(execution, "response", None), "diagnostics", []) or ()),
        )

    def unit(self, unit: LocalFlowUnit, *, query_selected: bool, recursively_discovered: bool) -> KnowledgeQueryGraphUnit:
        return KnowledgeQueryGraphUnit(
            unitId=unit.unit_id,
            sourceId=unit.source_id,
            graphRevision=str(unit.graph_revision or ""),
            querySelectedInitial=query_selected,
            recursivelyDiscovered=recursively_discovered,
            roots=[
                {
                    "node": self.node(root.node),
                    "origin": root.origin.value if hasattr(root.origin, "value") else str(root.origin),
                    "distanceToNearestSeed": root.distance_to_nearest_seed,
                }
                for root in unit.roots
            ],
            anchors=[
                {
                    "originalAnchor": self._model_dict(anchor.original_anchor),
                    "expandedSeed": self.node(anchor.expanded_seed),
                    "anchorToSeedReasons": list(anchor.anchor_to_seed_reasons),
                    "queryProvenance": list(anchor.query_provenance),
                    "distanceToNearestRoot": anchor.distance_to_nearest_root,
                }
                for anchor in unit.anchors
            ],
            nodes=[self.node(item) for item in unit.execution_nodes],
            localTransitions=[self.edge(item) for item in unit.execution_transitions],
            genericBoundaries=[self.local_boundary_identity(item) for item in unit.generic_boundaries],
            topologyBoundaries=[self.edge(item) for item in unit.topology_boundaries],
            supportingContext=[self.node(item) for item in unit.supporting_context],
            evidence=[self.evidence(item) for item in unit.evidence],
            complete=bool(unit.complete),
            coverage=KnowledgeQueryGraphUnitCoverage(
                nodeCount=unit.coverage.node_count,
                transitionCount=unit.coverage.transition_count,
                genericBoundaryCount=unit.coverage.generic_boundary_count,
                topologyBoundaryCount=unit.coverage.topology_boundary_count,
                anchorCount=unit.coverage.anchor_count,
                rootCount=unit.coverage.root_count,
                maxDepthReached=unit.coverage.max_depth_reached,
                cycleDetected=unit.coverage.cycle_detected,
                truncated=unit.coverage.truncated,
            ),
            diagnostics=list(unit.diagnostics),
        )

    def transition(self, item: Any) -> KnowledgeQueryGraphTransition:
        return KnowledgeQueryGraphTransition(
            transitionId=item.stable_transition_id,
            kind=item.transition_kind,
            verificationStatus=item.verification_status,
            resolutionId=item.resolution_id,
            sourceUnitId=item.source_unit_id,
            targetUnitId=item.target_unit_id,
            requiredBoundary=self.endpoint(item.required_endpoint),
            providedBoundary=self.endpoint(item.provided_endpoint),
            targetSeeds=[self._dataclass_identity(seed) for seed in item.target_seed_identities],
            provingDescriptorFingerprintHashes=[fingerprint.fingerprint_hash for fingerprint in item.proving_descriptor_fingerprints],
            evidenceRefs=[self.evidence_ref(ref) for ref in item.evidence_references],
        )

    def open_boundary(self, item: Any) -> KnowledgeQueryGraphOpenBoundary:
        return KnowledgeQueryGraphOpenBoundary(
            requiredBoundary=self.boundary_identity(item.required_boundary_identity),
            sourceUnitIds=list(item.source_unit_ids),
            status=item.status.value if hasattr(item.status, "value") else str(item.status),
            viableCandidateOwners=[self.owner_identity(owner) for owner in item.viable_candidate_owner_identities],
            viableCandidateBoundaries=[self.boundary_identity(boundary) for boundary in item.viable_candidate_boundary_identities],
            rejectionReasonCodes=list(item.rejection_reason_codes),
            descriptorFingerprintHashes=list(item.descriptor_fingerprint_hashes),
            diagnostics=list(item.diagnostics),
        )

    def coverage(self, item: Any) -> KnowledgeQueryGraphCoverage:
        return KnowledgeQueryGraphCoverage(
            unitCount=item.unit_count,
            sourceCount=item.source_count,
            localNodeCount=item.local_node_count,
            localExecutionTransitionCount=item.local_execution_transition_count,
            provenCrossSourceTransitionCount=item.proven_cross_source_transition_count,
            openAmbiguousBoundaryCount=item.open_ambiguous_boundary_count,
            openUnresolvedBoundaryCount=item.open_unresolved_boundary_count,
            queryEntryUnitCount=item.query_entry_unit_count,
            topologyEntryUnitCount=item.topology_entry_unit_count,
            cycleCount=item.cycle_count,
            orphanResolutionCount=item.orphan_resolution_count,
            missingUnitMappingCount=item.missing_unit_mapping_count,
            complete=item.complete,
            truncated=item.truncated,
        )

    def node(self, item: FlowGraphNode) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "nodeId": item.node_id,
            "stableKey": item.stable_key,
            "kind": item.node_kind,
            "label": item.label,
            "qualifiedName": item.qualified_name,
            "relativePath": item.relative_path,
            "lineStart": item.line_start,
            "lineEnd": item.line_end,
            "summary": item.summary,
            "entrypoint": item.entrypoint,
            "entrypointKind": item.entrypoint_kind,
            "entrypointHttpMethod": item.entrypoint_http_method,
            "entrypointRoute": item.entrypoint_route,
            "entrypointTopic": item.entrypoint_topic,
            "entrypointSchedule": item.entrypoint_schedule,
            "entrypointInterfaceMethod": item.entrypoint_interface_method,
            "executionRole": item.execution_role,
            "flowDomain": item.flow_domain,
        }

    def edge(self, item: FlowGraphEdge) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "edgeId": item.edge_id,
            "edgeType": item.edge_type,
            "fromNodeId": item.from_node_id,
            "toNodeId": item.to_node_id,
            "resolutionStatus": item.resolution_status,
            "toSourceId": item.to_source_id,
            "toGraphRevision": item.to_graph_revision or item.to_graph_id,
            "external": item.external,
            "unresolvedTarget": item.unresolved_target,
            "metadata": item.metadata,
            "evidenceIds": list(item.evidence_ids),
            "flowDomain": item.flow_domain,
            "boundaryReason": item.boundary_reason,
        }

    def local_boundary_identity(self, item: Any) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "boundaryId": item.boundary_id,
            "boundaryKey": item.stable_key,
            "ownerNodeId": item.owner_node_id,
            "role": item.role,
            "status": item.status,
            "provenance": item.provenance,
            "confidence": item.confidence,
            "flowDomain": item.flow_domain,
            "descriptorFingerprintHashes": sorted(
                {
                    str(getattr(descriptor, "descriptor_id", "") or "")
                    for descriptor in item.descriptors
                    if str(getattr(descriptor, "descriptor_id", "") or "")
                }
            ),
            "evidenceRefs": [self.evidence(evidence) for evidence in item.evidence],
        }

    def endpoint(self, item: Any) -> dict[str, Any]:
        return {
            "boundary": self.boundary_identity(item.boundary_identity),
            "ownerSourceId": item.owner_source_id,
            "ownerGraphRevision": item.owner_graph_revision,
            "ownerNodeId": item.owner_node_id,
            "role": item.role,
            "localUnitIds": list(item.local_unit_ids),
        }

    def boundary_identity(self, item: BoundaryIdentity) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision,
            "boundaryKey": item.boundary_key,
            "ownerNodeId": item.owner_node_id,
        }

    def owner_identity(self, item: BoundaryOwnerIdentity) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision,
            "ownerNodeId": item.owner_node_id,
            "boundary": self.boundary_identity(item.boundary_identity),
        }

    def evidence_ref(self, item: EvidenceReference) -> dict[str, Any]:
        return self._dataclass_identity(item)

    def evidence(self, item: FlowGraphEvidence) -> dict[str, Any]:
        return {
            "sourceId": item.source_id,
            "graphRevision": item.graph_revision or item.graph_id,
            "evidenceId": item.evidence_id,
            "nodeId": item.node_id,
            "edgeId": item.edge_id,
            "relativePath": item.relative_path,
            "lineStart": item.line_start,
            "lineEnd": item.line_end,
            "excerpt": item.text,
            "ownerKind": item.owner_kind,
            "ownerSourceId": item.owner_source_id,
            "ownerNodeId": item.owner_node_id,
            "ownerEdgeId": item.owner_edge_id,
        }

    def _dataclass_identity(self, item: Any) -> dict[str, Any]:
        if is_dataclass(item):
            return {self._camel(key): value for key, value in asdict(item).items()}
        return dict(item) if isinstance(item, Mapping) else {"value": item}

    def _model_dict(self, item: Any) -> dict[str, Any]:
        if hasattr(item, "dict"):
            return dict(item.dict(exclude_none=True))
        if is_dataclass(item):
            return self._dataclass_identity(item)
        return dict(item) if isinstance(item, Mapping) else {}

    def _camel(self, key: str) -> str:
        head, *tail = key.split("_")
        return head + "".join(part[:1].upper() + part[1:] for part in tail)
