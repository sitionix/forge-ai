from __future__ import annotations

from typing import Any, Iterable

from knowledge_service.graph_schema import BoundaryFact, GraphAnalysisResult, GraphClaim, GraphNode


class FileEnrichmentMerger:
    def merge(self, target_results: Iterable[GraphAnalysisResult]) -> GraphAnalysisResult:
        nodes: list[GraphNode] = []
        claims: list[GraphClaim] = []
        boundaries: list[BoundaryFact] = []
        diagnostics: list[dict[str, Any]] = []
        seen_nodes: set[str] = set()
        seen_claims: set[tuple[Any, ...]] = set()
        seen_boundaries: set[tuple[Any, ...]] = set()
        for result in target_results:
            diagnostics.extend(dict(item) for item in result.diagnostics or [])
            for node in result.nodes:
                if node.localId in seen_nodes:
                    continue
                seen_nodes.add(node.localId)
                nodes.append(node)
            for claim in result.claims:
                key = self._claim_key(claim)
                if key in seen_claims:
                    continue
                seen_claims.add(key)
                claims.append(claim)
            for boundary in result.boundaries:
                key = self._boundary_key(boundary)
                if key in seen_boundaries:
                    continue
                seen_boundaries.add(key)
                boundaries.append(boundary)
        return GraphAnalysisResult(nodes=nodes, edges=[], claims=claims, boundaries=boundaries, diagnostics=diagnostics)

    def _claim_key(self, claim: GraphClaim) -> tuple[Any, ...]:
        return (
            claim.nodeLocalId,
            claim.claimKind,
            _normalize_text(claim.summary),
            tuple((item.lineStart, item.lineEnd) for item in claim.evidence),
        )

    def _boundary_key(self, boundary: BoundaryFact) -> tuple[Any, ...]:
        return (
            boundary.nodeLocalId,
            boundary.role,
            tuple(
                sorted(
                    (
                        descriptor.path,
                        _normalize_text(str(descriptor.value)),
                        descriptor.origin,
                        tuple((item.lineStart, item.lineEnd) for item in descriptor.evidence),
                    )
                    for descriptor in boundary.descriptors
                )
            ),
        )

def _normalize_text(value: str) -> str:
    return " ".join(str(value or "").split()).casefold()
