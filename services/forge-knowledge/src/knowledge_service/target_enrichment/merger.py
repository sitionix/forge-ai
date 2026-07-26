from __future__ import annotations

import json
from typing import Any, Iterable

from knowledge_service.graph_schema import BoundaryDescriptor, BoundaryFact, GraphAnalysisResult, GraphClaim, GraphEvidenceRef, GraphNode


class FileEnrichmentMerger:
    def merge(self, target_results: Iterable[GraphAnalysisResult]) -> GraphAnalysisResult:
        nodes: list[GraphNode] = []
        claims: list[GraphClaim] = []
        boundaries: list[BoundaryFact] = []
        diagnostics: list[dict[str, Any]] = []
        seen_nodes: set[str] = set()
        seen_claims: set[tuple[Any, ...]] = set()
        boundary_by_key: dict[tuple[Any, ...], BoundaryFact] = {}
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
                existing = boundary_by_key.get(key)
                if existing is not None:
                    boundary_by_key[key] = self._merge_boundary(existing, boundary)
                    continue
                boundary_by_key[key] = boundary
                boundaries.append(boundary)
        boundaries = [boundary_by_key[self._boundary_key(boundary)] for boundary in boundaries if self._boundary_key(boundary) in boundary_by_key]
        return GraphAnalysisResult(nodes=nodes, edges=[], claims=claims, boundaries=boundaries, diagnostics=diagnostics)

    def _claim_key(self, claim: GraphClaim) -> tuple[Any, ...]:
        return (
            claim.nodeLocalId,
            claim.claimKind,
            _normalize_text(claim.summary),
            tuple((item.lineStart, item.lineEnd) for item in claim.evidence),
        )

    def _boundary_key(self, boundary: BoundaryFact) -> tuple[Any, ...]:
        metadata = dict(boundary.metadata or {})
        identity = boundary.identity or metadata.get("boundaryIdentity")
        if identity:
            return (boundary.nodeLocalId, boundary.role, str(identity))
        return (
            boundary.nodeLocalId,
            boundary.role,
            tuple((item.lineStart, item.lineEnd) for item in self._boundary_evidence(boundary)),
            tuple(sorted((descriptor.path, _json_dump(descriptor.value), descriptor.origin) for descriptor in boundary.descriptors)),
        )

    def _merge_boundary(self, first: BoundaryFact, second: BoundaryFact) -> BoundaryFact:
        descriptors_by_key: dict[tuple[str, str, str], BoundaryDescriptor] = {}
        for descriptor in [*first.descriptors, *second.descriptors]:
            key = (descriptor.path, _json_dump(descriptor.value), str(descriptor.origin or "LLM").upper())
            existing = descriptors_by_key.get(key)
            if existing is None:
                descriptors_by_key[key] = descriptor
                continue
            descriptors_by_key[key] = existing.copy(update={"evidence": self._merge_evidence(existing.evidence, descriptor.evidence)})
        return first.copy(
            update={
                "confidence": max(float(first.confidence), float(second.confidence)),
                "evidence": self._merge_evidence(first.evidence, second.evidence),
                "descriptors": sorted(
                    descriptors_by_key.values(),
                    key=lambda descriptor: (descriptor.path, str(descriptor.origin or ""), _json_dump(descriptor.value)),
                ),
            }
        )

    def _boundary_evidence(self, boundary: BoundaryFact) -> list[GraphEvidenceRef]:
        return sorted(
            [*boundary.evidence, *(item for descriptor in boundary.descriptors for item in descriptor.evidence)],
            key=lambda item: (item.lineStart, item.lineEnd),
        )

    def _merge_evidence(self, first: list[GraphEvidenceRef], second: list[GraphEvidenceRef]) -> list[GraphEvidenceRef]:
        by_key = {_evidence_key(item): item for item in [*first, *second]}
        return [by_key[key] for key in sorted(by_key)]

def _normalize_text(value: str) -> str:
    return " ".join(str(value or "").split()).casefold()

def _json_dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)

def _evidence_key(item: GraphEvidenceRef) -> tuple[int, int, str, str]:
    return (
        int(item.lineStart),
        int(item.lineEnd),
        str(item.text or ""),
        _json_dump(item.metadata or {}),
    )
