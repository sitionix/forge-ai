from __future__ import annotations

from typing import Any, Dict, List, Optional

from knowledge_service.graph_analysis import confidence_status
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphNode


class AnchorAwareGraphValidator:
    def merge(self, static_graph: GraphAnalysisResult, enrichment: Optional[GraphAnalysisResult], line_count: int) -> GraphAnalysisResult:
        if enrichment is None:
            return static_graph
        anchors = {node.localId: node for node in static_graph.nodes}
        source_nodes = {node.localId: node for node in enrichment.nodes}
        diagnostics: List[Dict[str, Any]] = list(static_graph.diagnostics or [])
        accepted_claims: List[GraphClaim] = list(static_graph.claims)
        accepted_edges: List[GraphEdge] = list(static_graph.edges)

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
            from_local_id = self._resolve_edge_endpoint(edge.fromNodeLocalId, anchors, source_nodes)
            to_local_id = self._resolve_edge_endpoint(edge.toNodeLocalId, anchors, source_nodes) if edge.toNodeLocalId else None
            if from_local_id is None:
                diagnostics.append(
                    {
                        "severity": "WARN",
                        "stage": "ANCHOR_VALIDATION",
                        "code": "LLM_EDGE_SOURCE_NOT_FOUND",
                        "message": "LLM semantic edge source did not match any parser anchor.",
                        "edgeLocalId": edge.localId,
                        "fromNodeLocalId": edge.fromNodeLocalId,
                    }
                )
                continue
            if edge.toNodeLocalId and to_local_id is None:
                diagnostics.append(
                    {
                        "severity": "WARN",
                        "stage": "ANCHOR_VALIDATION",
                        "code": "LLM_EDGE_TARGET_NOT_FOUND",
                        "message": "LLM semantic edge target did not match any parser anchor; edge remains unresolved.",
                        "edgeLocalId": edge.localId,
                        "toNodeLocalId": edge.toNodeLocalId,
                    }
                )
            metadata = dict(edge.metadata or {})
            metadata.setdefault("factOrigin", "LLM")
            metadata.setdefault("resolutionStatus", "RESOLVED" if to_local_id else "UNRESOLVED")
            accepted_edges.append(
                edge.copy(
                    update={
                        "fromNodeLocalId": from_local_id,
                        "toNodeLocalId": to_local_id,
                        "metadata": metadata,
                    }
                )
            )

        return GraphAnalysisResult(
            nodes=list(static_graph.nodes),
            edges=accepted_edges,
            claims=accepted_claims,
            diagnostics=diagnostics,
        )

    def _resolve_claim_target(self, claim: GraphClaim, anchors: Dict[str, GraphNode], source_nodes: Dict[str, GraphNode]) -> Optional[str]:
        direct = self._resolve_edge_endpoint(claim.nodeLocalId, anchors, source_nodes)
        if direct:
            return direct
        return None

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
                status = "DEBUG_ONLY"
                rejection_reason = "ANALYSIS_GRAPH_CALLABLE_EVIDENCE_OUTSIDE_METHOD"
            elif node.nodeKind == "TYPE" and not self._evidence_overlaps_node(claim, node):
                status = "DEBUG_ONLY"
                rejection_reason = "ANALYSIS_GRAPH_TYPE_EVIDENCE_OUTSIDE_TYPE"
            elif node.nodeKind not in {"FILE", "TYPE", "CALLABLE"}:
                status = "DEBUG_ONLY"
                rejection_reason = "ANALYSIS_GRAPH_RESPONSIBILITY_UNSUPPORTED_NODE_KIND"
        if not claim.evidence:
            status = "DEBUG_ONLY"
            rejection_reason = rejection_reason or "ANALYSIS_GRAPH_CLAIM_EVIDENCE_MISSING"
        for item in claim.evidence:
            if item.lineStart < 1 or item.lineEnd < item.lineStart or item.lineEnd > max(line_count, 1):
                status = "DEBUG_ONLY"
                rejection_reason = rejection_reason or "ANALYSIS_GRAPH_LINE_RANGE_INVALID"
        if rejection_reason:
            metadata["status"] = status
            metadata["qualityIssue"] = rejection_reason
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

    def _evidence_overlaps_node(self, claim: GraphClaim, node: GraphNode) -> bool:
        if node.lineStart is None or node.lineEnd is None:
            return False
        for item in claim.evidence:
            if item.lineStart <= node.lineEnd and item.lineEnd >= node.lineStart:
                return True
        return False
