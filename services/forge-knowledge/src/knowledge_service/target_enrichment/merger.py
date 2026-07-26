from __future__ import annotations

import json
from typing import Any, Iterable

from knowledge_service.graph_analysis import (
    BoundaryLifecycle,
    aggregate_boundary_lifecycle,
    aggregate_boundary_origin,
    boundary_lifecycle_metadata,
    merge_boundary_metadata,
    resolve_boundary_lifecycle,
)
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
        first_origin = self._boundary_origin(first)
        second_origin = self._boundary_origin(second)
        merged_origin = aggregate_boundary_origin([first_origin, second_origin])
        merged_confidence = max(float(first.confidence), float(second.confidence))
        first_lifecycle = self._boundary_lifecycle(first, first_origin)
        second_lifecycle = self._boundary_lifecycle(second, second_origin)
        merged_lifecycle = aggregate_boundary_lifecycle([first_lifecycle, second_lifecycle], merged_confidence)
        descriptors_by_key: dict[tuple[str, str, str], BoundaryDescriptor] = {}
        for descriptor in [*first.descriptors, *second.descriptors]:
            key = (descriptor.path, _json_dump(descriptor.value), str(descriptor.origin or "LLM").upper())
            existing = descriptors_by_key.get(key)
            if existing is None:
                descriptors_by_key[key] = descriptor
                continue
            descriptors_by_key[key] = existing.copy(update={"evidence": self._merge_evidence(existing.evidence, descriptor.evidence)})
        metadata = merge_boundary_metadata(
            self._boundary_metadata(first),
            self._boundary_metadata(second),
            {
                "originContributors": sorted({first_origin, second_origin}),
                "lifecycleContributions": [
                    boundary_lifecycle_metadata(first_origin, first_lifecycle),
                    boundary_lifecycle_metadata(second_origin, second_lifecycle),
                    boundary_lifecycle_metadata(merged_origin, merged_lifecycle),
                ],
                "lifecycleSource": "BACKEND_MERGE",
            },
        )
        metadata["status"] = merged_lifecycle.status
        metadata["lifecycleSource"] = "BACKEND_MERGE"
        if merged_lifecycle.rejection_reason:
            metadata["rejectionReason"] = merged_lifecycle.rejection_reason
        return first.copy(
            update={
                "origin": merged_origin,
                "confidence": merged_confidence,
                "status": merged_lifecycle.status,
                "flowDomain": self._merge_flow_domain(first.flowDomain, second.flowDomain),
                "metadata": metadata,
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

    def _boundary_origin(self, boundary: BoundaryFact) -> str:
        metadata = dict(boundary.metadata or {})
        return str(boundary.origin or metadata.get("factOrigin") or "LLM").upper()

    def _boundary_lifecycle(self, boundary: BoundaryFact, origin: str) -> BoundaryLifecycle:
        metadata = dict(boundary.metadata or {})
        explicit_status = boundary.status or metadata.get("status")
        if origin == "LLM" and metadata.get("lifecycleSource") not in {"BACKEND_VALIDATION", "BACKEND_MERGE"}:
            explicit_status = None
        return resolve_boundary_lifecycle(
            boundary.confidence,
            explicit_status=explicit_status,
            rejection_reason=metadata.get("rejectionReason"),
        )

    def _boundary_metadata(self, boundary: BoundaryFact) -> dict[str, Any]:
        metadata = dict(boundary.metadata or {})
        identity = boundary.identity or metadata.get("boundaryIdentity")
        if identity:
            metadata["boundaryIdentity"] = identity
        return metadata

    def _merge_flow_domain(self, first: str | None, second: str | None) -> str | None:
        first_value = str(first or "").strip().upper()
        second_value = str(second or "").strip().upper()
        if first_value and first_value == second_value:
            return first_value
        return None

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
