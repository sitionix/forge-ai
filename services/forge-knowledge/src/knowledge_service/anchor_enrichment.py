from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

from knowledge_service.graph_analysis import confidence_status
from knowledge_service.graph_schema import BoundaryDescriptor, BoundaryFact, GraphAnalysisResult, GraphClaim, GraphEdge, GraphNode


class AnchorAwareGraphValidator:
    def merge(self, static_graph: GraphAnalysisResult, enrichment: Optional[GraphAnalysisResult], line_count: int) -> GraphAnalysisResult:
        if enrichment is None:
            return static_graph
        anchors = {node.localId: node for node in static_graph.nodes}
        source_nodes = {node.localId: node for node in enrichment.nodes}
        diagnostics: List[Dict[str, Any]] = list(static_graph.diagnostics or [])
        accepted_claims: List[GraphClaim] = list(static_graph.claims)
        accepted_edges: List[GraphEdge] = list(static_graph.edges)
        accepted_boundaries: list[BoundaryFact] = list(static_graph.boundaries)

        for node in enrichment.nodes:
            diagnostics.append(
                {
                    "severity": "WARN",
                    "stage": "ANCHOR_VALIDATION",
                    "code": "LLM_UNANCHORED_STRUCTURE_CANDIDATE",
                    "message": "LLM returned structure for a parser-supported file; structure was not trusted.",
                    "nodeLocalId": node.localId,
                    "nodeKind": node.nodeKind,
                    "name": node.name,
                }
            )

        for claim in enrichment.claims:
            target_local_id = self._resolve_claim_target(claim, anchors, source_nodes)
            if target_local_id is None:
                diagnostics.append(
                    {
                        "severity": "WARN",
                        "stage": "ANCHOR_VALIDATION",
                        "code": "LLM_CLAIM_TARGET_NOT_FOUND",
                        "message": "LLM claim target did not match any parser anchor.",
                        "claimLocalId": claim.localId,
                        "nodeLocalId": claim.nodeLocalId,
                        "claimKind": claim.claimKind,
                    }
                )
                continue
            node = anchors[target_local_id]
            validated = self._validated_claim(claim, target_local_id, node, line_count, diagnostics)
            accepted_claims.append(validated)

        for edge in enrichment.edges:
            diagnostics.append(
                {
                    "severity": "WARN",
                    "stage": "ANCHOR_VALIDATION",
                    "code": "LLM_EDGE_TOPOLOGY_REJECTED",
                    "message": "LLM enrichment edges are not accepted; graph topology is static/backend-owned.",
                    "edgeLocalId": edge.localId,
                    "edgeType": edge.edgeType,
                }
            )

        for boundary in enrichment.boundaries:
            target_local_id = self._resolve_boundary_target(boundary, anchors, source_nodes)
            if target_local_id is None:
                diagnostics.append(
                    {
                        "severity": "WARN",
                        "stage": "ANCHOR_VALIDATION",
                        "code": "LLM_BOUNDARY_TARGET_NOT_FOUND",
                        "message": "LLM boundary target did not match any parser anchor.",
                        "boundaryLocalId": boundary.localId,
                        "nodeLocalId": boundary.nodeLocalId,
                        "role": boundary.role,
                    }
                )
                continue
            validated = self._validated_boundary(
                boundary,
                target_local_id,
                anchors[target_local_id],
                line_count,
                diagnostics,
                accepted_boundaries,
            )
            accepted_boundaries.append(validated)

        return GraphAnalysisResult(
            nodes=list(static_graph.nodes),
            edges=accepted_edges,
            claims=accepted_claims,
            boundaries=accepted_boundaries,
            diagnostics=diagnostics,
        )

    def _resolve_claim_target(self, claim: GraphClaim, anchors: Dict[str, GraphNode], source_nodes: Dict[str, GraphNode]) -> Optional[str]:
        direct = self._resolve_edge_endpoint(claim.nodeLocalId, anchors, source_nodes)
        if direct:
            return direct
        return None

    def _resolve_boundary_target(self, boundary: BoundaryFact, anchors: dict[str, GraphNode], source_nodes: dict[str, GraphNode]) -> str | None:
        return self._resolve_edge_endpoint(boundary.nodeLocalId, anchors, source_nodes)

    def _resolve_edge_endpoint(self, local_id: Optional[str], anchors: Dict[str, GraphNode], source_nodes: Dict[str, GraphNode]) -> Optional[str]:
        if not local_id:
            return None
        if local_id in anchors:
            return local_id
        source_node = source_nodes.get(local_id)
        if source_node is None:
            return None
        candidates = [
            anchor
            for anchor in anchors.values()
            if anchor.nodeKind == source_node.nodeKind and source_node.qualifiedName and anchor.qualifiedName == source_node.qualifiedName
        ]
        if len(candidates) == 1:
            return candidates[0].localId
        candidates = [anchor for anchor in anchors.values() if anchor.nodeKind == source_node.nodeKind and anchor.name == source_node.name]
        if len(candidates) == 1:
            return candidates[0].localId
        return None

    def _validated_claim(self, claim: GraphClaim, target_local_id: str, node: GraphNode, line_count: int, diagnostics: List[Dict[str, Any]]) -> GraphClaim:
        metadata = dict(claim.metadata or {})
        metadata.setdefault("factOrigin", "LLM")
        metadata.setdefault("sourceKind", claim.claimKind)
        status = metadata.get("status") or confidence_status(claim.confidence)
        rejection_reason = None
        if claim.claimKind == "RESPONSIBILITY":
            if node.nodeKind == "CALLABLE" and not self._evidence_overlaps_node(claim, node):
                rejection_reason = "ANALYSIS_GRAPH_CALLABLE_EVIDENCE_OUTSIDE_METHOD"
            elif node.nodeKind == "TYPE" and not self._evidence_overlaps_node(claim, node):
                rejection_reason = "ANALYSIS_GRAPH_TYPE_EVIDENCE_OUTSIDE_TYPE"
            elif node.nodeKind not in {"FILE", "TYPE", "CALLABLE"}:
                rejection_reason = "ANALYSIS_GRAPH_RESPONSIBILITY_UNSUPPORTED_NODE_KIND"
        if not claim.evidence:
            rejection_reason = rejection_reason or "ANALYSIS_GRAPH_CLAIM_EVIDENCE_MISSING"
        for item in claim.evidence:
            if item.lineStart < 1 or item.lineEnd < item.lineStart or item.lineEnd > max(line_count, 1):
                rejection_reason = rejection_reason or "ANALYSIS_GRAPH_LINE_RANGE_INVALID"
        if rejection_reason:
            status = "CANDIDATE"
            metadata["status"] = status
            metadata["rejectionReason"] = rejection_reason
            diagnostics.append(
                {
                    "severity": "WARN",
                    "stage": "ANCHOR_VALIDATION",
                    "code": rejection_reason,
                    "message": "LLM claim was not trusted because its evidence did not satisfy parser anchor validation.",
                    "claimLocalId": claim.localId,
                    "nodeLocalId": target_local_id,
                    "nodeKind": node.nodeKind,
                }
            )
        else:
            metadata["status"] = status
        return claim.copy(
            update={
                "nodeLocalId": target_local_id,
                "metadata": metadata,
            }
        )

    def _validated_boundary(
        self,
        boundary: BoundaryFact,
        target_local_id: str,
        node: GraphNode,
        line_count: int,
        diagnostics: list[dict[str, Any]],
        accepted_boundaries: list[BoundaryFact],
    ) -> BoundaryFact:
        metadata = {
            key: value
            for key, value in (boundary.metadata or {}).items()
            if key not in {"factOrigin", "status", "rejectionReason", "flowDomain"}
        }
        metadata["factOrigin"] = "LLM"
        status = confidence_status(boundary.confidence)
        rejection_reason = None
        evidence_ranges = [*boundary.evidence, *(item for descriptor in boundary.descriptors for item in descriptor.evidence)]
        if not evidence_ranges:
            rejection_reason = "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_MISSING"
        for item in evidence_ranges:
            if item.lineStart < 1 or item.lineEnd < item.lineStart or item.lineEnd > max(line_count, 1):
                rejection_reason = rejection_reason or "ANALYSIS_GRAPH_LINE_RANGE_INVALID"
            elif not self._evidence_range_overlaps_node(item, node):
                rejection_reason = rejection_reason or "ANALYSIS_GRAPH_BOUNDARY_EVIDENCE_OUTSIDE_OWNER"
        if not boundary.descriptors:
            rejection_reason = rejection_reason or "ANALYSIS_GRAPH_BOUNDARY_DESCRIPTOR_MISSING"
        if rejection_reason:
            status = "CANDIDATE"
            metadata["status"] = status
            metadata["rejectionReason"] = rejection_reason
            diagnostics.append(
                {
                    "severity": "WARN",
                    "stage": "ANCHOR_VALIDATION",
                    "code": rejection_reason,
                    "message": "LLM boundary was not trusted because its descriptors or evidence did not satisfy parser anchor validation.",
                    "boundaryLocalId": boundary.localId,
                    "nodeLocalId": target_local_id,
                    "nodeKind": node.nodeKind,
                }
            )
        else:
            metadata["status"] = status
        identity = self._matching_static_boundary_identity(boundary, target_local_id, accepted_boundaries)
        metadata["boundaryIdentity"] = identity or self._llm_boundary_identity(boundary, target_local_id)
        descriptors = [
            BoundaryDescriptor(
                path=descriptor.path,
                value=descriptor.value,
                origin="LLM",
                confidence=descriptor.confidence,
                evidence=list(descriptor.evidence or boundary.evidence),
            )
            for descriptor in boundary.descriptors
        ]
        return boundary.copy(
            update={
                "identity": metadata["boundaryIdentity"],
                "nodeLocalId": target_local_id,
                "descriptors": descriptors,
                "origin": "LLM",
                "status": str(status),
                "flowDomain": None,
                "metadata": metadata,
            }
        )

    def _matching_static_boundary_identity(
        self,
        boundary: BoundaryFact,
        target_local_id: str,
        accepted_boundaries: list[BoundaryFact],
    ) -> str | None:
        candidates = [
            candidate
            for candidate in accepted_boundaries
            if candidate.nodeLocalId == target_local_id
            and candidate.role == boundary.role
            and self._boundary_fact_origin(candidate) in {"STATIC", "DERIVED"}
        ]
        exact_descriptor_matches = [
            candidate
            for candidate in candidates
            if self._boundary_evidence_compatible(boundary, candidate)
            and self._boundary_has_shared_descriptor(boundary, candidate)
        ]
        if len(exact_descriptor_matches) == 1:
            return self._boundary_identity(exact_descriptor_matches[0])
        evidence_matches = [candidate for candidate in candidates if self._boundary_evidence_compatible(boundary, candidate)]
        if len(evidence_matches) == 1:
            return self._boundary_identity(evidence_matches[0])
        return None

    def _boundary_fact_origin(self, boundary: BoundaryFact) -> str:
        metadata = dict(boundary.metadata or {})
        return str(boundary.origin or metadata.get("factOrigin") or "LLM").upper()

    def _boundary_identity(self, boundary: BoundaryFact) -> str:
        metadata = dict(boundary.metadata or {})
        return str(boundary.identity or metadata.get("boundaryIdentity") or metadata.get("stableKey") or boundary.localId)

    def _llm_boundary_identity(self, boundary: BoundaryFact, target_local_id: str) -> str:
        descriptor_identity = [
            (
                descriptor.path,
                self._json_dump(descriptor.value),
                self._evidence_signature(descriptor.evidence),
            )
            for descriptor in boundary.descriptors
        ]
        return "LLM_BOUNDARY:" + self._json_dump(
            {
                "node": target_local_id,
                "role": boundary.role,
                "evidence": self._evidence_signature(self._boundary_evidence(boundary)),
                "descriptors": sorted(descriptor_identity),
            }
        )

    def _boundary_has_shared_descriptor(self, first: BoundaryFact, second: BoundaryFact) -> bool:
        first_descriptors = {
            (descriptor.path, self._json_dump(descriptor.value))
            for descriptor in first.descriptors
        }
        second_descriptors = {
            (descriptor.path, self._json_dump(descriptor.value))
            for descriptor in second.descriptors
        }
        return bool(first_descriptors & second_descriptors)

    def _boundary_evidence_compatible(self, first: BoundaryFact, second: BoundaryFact) -> bool:
        first_evidence = self._boundary_evidence(first)
        second_evidence = self._boundary_evidence(second)
        if not first_evidence or not second_evidence:
            return False
        return any(self._ranges_overlap(left, right) for left in first_evidence for right in second_evidence)

    def _boundary_evidence(self, boundary: BoundaryFact) -> list[Any]:
        return [*boundary.evidence, *(item for descriptor in boundary.descriptors for item in descriptor.evidence)]

    def _evidence_signature(self, evidence: list[Any]) -> list[tuple[int, int]]:
        return sorted({(int(item.lineStart), int(item.lineEnd)) for item in evidence})

    def _json_dump(self, value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)

    def _ranges_overlap(self, first: Any, second: Any) -> bool:
        return int(first.lineStart) <= int(second.lineEnd) and int(first.lineEnd) >= int(second.lineStart)

    def _evidence_overlaps_node(self, claim: GraphClaim, node: GraphNode) -> bool:
        if node.lineStart is None or node.lineEnd is None:
            return False
        for item in claim.evidence:
            if self._evidence_range_overlaps_node(item, node):
                return True
        return False

    def _evidence_range_overlaps_node(self, evidence: Any, node: GraphNode) -> bool:
        if node.lineStart is None or node.lineEnd is None:
            return False
        return evidence.lineStart <= node.lineEnd and evidence.lineEnd >= node.lineStart
