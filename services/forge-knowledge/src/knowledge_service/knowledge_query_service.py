from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Sequence

from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryAnchor,
    KnowledgeQueryCoverage,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryMatchedSource,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryStatus,
)


TOKEN_PATTERN = re.compile(r"[\w.$:/\\-]+", re.UNICODE)


@dataclass(frozen=True)
class QuerySource:
    source_id: str
    display_name: str
    snapshot_id: str
    node_count: int
    edge_count: int


class SourceScopeResolver:
    def __init__(self, graph_store: Any) -> None:
        self.graph_store = graph_store

    def resolve(self) -> tuple[List[QuerySource], List[KnowledgeQueryDiagnostic]]:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        raw_sources = self.graph_store.query_current_graph_sources()
        eligible: List[QuerySource] = []
        for source in raw_sources:
            source_id = str(source.get("sourceId") or "")
            display_name = str(source.get("displayName") or source_id or "unknown")
            snapshot_id = str(source.get("snapshotId") or "")
            node_count = int(source.get("nodeCount") or 0)
            edge_count = int(source.get("edgeCount") or 0)
            if not source_id:
                continue
            if not snapshot_id:
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="SOURCE_WITHOUT_CURRENT_GRAPH",
                        message="Source has no current graph snapshot and was skipped.",
                        severity="INFO",
                        sourceId=source_id,
                    )
                )
                continue
            if node_count <= 0:
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="SOURCE_WITHOUT_GRAPH_FACTS",
                        message="Source has a current graph snapshot but no graph nodes.",
                        severity="INFO",
                        sourceId=source_id,
                    )
                )
                continue
            eligible.append(QuerySource(source_id=source_id, display_name=display_name, snapshot_id=snapshot_id, node_count=node_count, edge_count=edge_count))
        if not raw_sources:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="NO_KNOWN_SOURCES",
                    message="Knowledge has no known graph sources to search.",
                    severity="WARN",
                )
            )
        elif not eligible:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="NO_ELIGIBLE_GRAPH_SOURCES",
                    message="Knowledge has sources, but none have searchable current graph facts.",
                    severity="WARN",
                )
            )
        return eligible, diagnostics


class UnifiedAnchorSearcher:
    def __init__(self, graph_store: Any) -> None:
        self.graph_store = graph_store

    def search(self, query: str, eligible_sources: Sequence[QuerySource], max_anchors: int) -> List[KnowledgeQueryAnchor]:
        tokens = self._tokens(query)
        if not tokens or not eligible_sources:
            return []
        raw_candidates = self.graph_store.query_anchor_candidates(tokens, [source.source_id for source in eligible_sources], max_anchors * 20)
        anchors: List[KnowledgeQueryAnchor] = []
        seen: set[tuple[str, str, str]] = set()
        for candidate in raw_candidates:
            anchor = self._anchor(candidate, tokens)
            key = (anchor.sourceId, anchor.snapshotId or "", anchor.nodeId)
            if key in seen:
                continue
            seen.add(key)
            anchors.append(anchor)
        anchors.sort(key=lambda item: (-item.score, item.sourceId, item.label.lower(), item.nodeId))
        return anchors[:max_anchors]

    def _tokens(self, query: str) -> List[str]:
        seen: set[str] = set()
        tokens: List[str] = []
        for match in TOKEN_PATTERN.findall(query):
            token = match.strip(" .,:;!?()[]{}'\"")
            if len(token) < 2:
                continue
            lowered = token.lower()
            if lowered in seen:
                continue
            seen.add(lowered)
            tokens.append(token)
        return tokens

    def _anchor(self, candidate: Dict[str, Any], tokens: Sequence[str]) -> KnowledgeQueryAnchor:
        reasons: set[str] = set()
        score = 0.0
        field_values = {
            "ID_MATCH": [candidate.get("id"), candidate.get("nodeId")],
            "STABLE_KEY_MATCH": [candidate.get("stableKey")],
            "KIND_MATCH": [candidate.get("kind"), candidate.get("nodeKind")],
            "NAME_MATCH": [candidate.get("name"), candidate.get("label"), candidate.get("displayName")],
            "QUALIFIED_NAME_MATCH": [candidate.get("qualifiedName")],
            "PATH_MATCH": [candidate.get("relativePath")],
            "SUMMARY_MATCH": [candidate.get("summary")],
            "METADATA_MATCH": [candidate.get("metadataText")],
        }
        weights = {
            "NAME_MATCH": 0.95,
            "QUALIFIED_NAME_MATCH": 0.9,
            "STABLE_KEY_MATCH": 0.88,
            "ID_MATCH": 0.82,
            "PATH_MATCH": 0.72,
            "KIND_MATCH": 0.55,
            "SUMMARY_MATCH": 0.52,
            "METADATA_MATCH": 0.45,
        }
        for token in tokens:
            lowered = token.lower()
            for reason, values in field_values.items():
                for value in values:
                    text = str(value or "").lower()
                    if not text:
                        continue
                    if text == lowered:
                        score = max(score, weights[reason])
                        reasons.add(reason)
                    elif lowered in text:
                        score = max(score, max(weights[reason] - 0.08, 0.1))
                        reasons.add(reason)
        confidence = float(candidate.get("confidence") or 0.0)
        degree = float(candidate.get("degree") or 0.0)
        score = min(1.0, score + min(confidence, 1.0) * 0.03 + min(degree, 10.0) * 0.002)
        if not reasons:
            reasons.add("LEXICAL_MATCH")
            score = max(score, 0.25)
        return KnowledgeQueryAnchor(
            sourceId=str(candidate.get("sourceId") or ""),
            nodeId=str(candidate.get("id") or candidate.get("nodeId") or ""),
            stableKey=str(candidate.get("stableKey") or candidate.get("id") or ""),
            kind=str(candidate.get("kind") or candidate.get("nodeKind") or ""),
            label=str(candidate.get("label") or candidate.get("displayName") or candidate.get("name") or candidate.get("id") or ""),
            score=round(score, 4),
            matchReasons=sorted(reasons),
            snapshotId=str(candidate.get("snapshotId") or "") or None,
            graphRevision=str(candidate.get("graphRevision") or "") or None,
            relativePath=candidate.get("relativePath"),
            qualifiedName=candidate.get("qualifiedName"),
        )


class GraphSliceQueryService:
    def __init__(self, graph_store: Any) -> None:
        self.graph_store = graph_store

    def build(self, anchors: Sequence[KnowledgeQueryAnchor], depth: int) -> tuple[Dict[str, List[Dict[str, Any]]], List[KnowledgeQueryDiagnostic]]:
        if not anchors:
            return self._empty_slice(), []
        try:
            slice_bundle = self.graph_store.query_graph_slice([anchor.dict() for anchor in anchors], depth)
        except Exception:
            return self._empty_slice(), [
                KnowledgeQueryDiagnostic(
                    code="GRAPH_SLICE_FAILED",
                    message="Graph slice could not be built from the selected anchors.",
                    severity="WARN",
                )
            ]
        return {
            "nodes": list(slice_bundle.get("nodes") or []),
            "edges": list(slice_bundle.get("edges") or []),
            "evidence": list(slice_bundle.get("evidence") or []),
            "unresolved": list(slice_bundle.get("unresolved") or []),
            "external": list(slice_bundle.get("external") or []),
            "verifiedPaths": list(slice_bundle.get("verifiedPaths") or []),
        }, []

    def _empty_slice(self) -> Dict[str, List[Dict[str, Any]]]:
        return {"nodes": [], "edges": [], "evidence": [], "unresolved": [], "external": [], "verifiedPaths": []}


class EvidenceBundleBuilder:
    def build(self, slice_bundle: Dict[str, List[Dict[str, Any]]]) -> Dict[str, List[Dict[str, Any]]]:
        return {
            "evidence": self._dedupe(slice_bundle.get("evidence") or []),
            "verifiedPaths": self._dedupe(slice_bundle.get("verifiedPaths") or []),
        }

    def _dedupe(self, items: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        seen: set[str] = set()
        for item in items:
            key = str(item.get("id") or item.get("pathId") or item)
            if key in seen:
                continue
            seen.add(key)
            result.append(dict(item))
        return result


class KnowledgeQueryService:
    def __init__(
        self,
        source_scope_resolver: SourceScopeResolver,
        anchor_searcher: UnifiedAnchorSearcher,
        graph_slice_service: GraphSliceQueryService,
        evidence_builder: EvidenceBundleBuilder,
    ) -> None:
        self.source_scope_resolver = source_scope_resolver
        self.anchor_searcher = anchor_searcher
        self.graph_slice_service = graph_slice_service
        self.evidence_builder = evidence_builder

    def query(self, request: KnowledgeQueryRequest) -> KnowledgeQueryResponse:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        eligible_sources, scope_diagnostics = self.source_scope_resolver.resolve()
        diagnostics.extend(scope_diagnostics)
        anchors = self.anchor_searcher.search(request.query, eligible_sources, request.maxAnchors)
        if not anchors:
            return KnowledgeQueryResponse(
                queryId=self._query_id(),
                status=KnowledgeQueryStatus.NO_CANDIDATES,
                intent=request.intent,
                coverage=KnowledgeQueryCoverage(searchedSourceCount=len(eligible_sources), matchedSourceCount=0),
                diagnostics=[
                    *diagnostics,
                    KnowledgeQueryDiagnostic(
                        code="NO_GRAPH_CANDIDATES",
                        message="No graph nodes matched the query across eligible analyzed sources.",
                        severity="INFO",
                    ),
                ],
            )

        slice_bundle, slice_diagnostics = self.graph_slice_service.build(anchors, request.depth)
        diagnostics.extend(slice_diagnostics)
        evidence_bundle = self.evidence_builder.build(slice_bundle)
        matched_sources = self._matched_sources(anchors, eligible_sources)
        status = KnowledgeQueryStatus.OK
        if len(matched_sources) > 1 and self._is_ambiguous(anchors):
            status = KnowledgeQueryStatus.AMBIGUOUS
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="MULTIPLE_SOURCES_MATCHED",
                    message="Multiple sources produced similarly scored anchors.",
                    severity="INFO",
                )
            )
        return KnowledgeQueryResponse(
            queryId=self._query_id(),
            status=status,
            intent=request.intent,
            matchedSources=matched_sources,
            anchors=anchors,
            nodes=slice_bundle["nodes"],
            edges=slice_bundle["edges"],
            verifiedPaths=evidence_bundle["verifiedPaths"],
            evidence=evidence_bundle["evidence"],
            unresolved=slice_bundle["unresolved"],
            external=slice_bundle["external"],
            coverage=KnowledgeQueryCoverage(
                searchedSourceCount=len(eligible_sources),
                matchedSourceCount=len(matched_sources),
                anchorCount=len(anchors),
                nodeCount=len(slice_bundle["nodes"]),
                edgeCount=len(slice_bundle["edges"]),
                evidenceCount=len(evidence_bundle["evidence"]),
                truncated=False,
            ),
            diagnostics=diagnostics,
        )

    def _matched_sources(self, anchors: Sequence[KnowledgeQueryAnchor], eligible_sources: Sequence[QuerySource]) -> List[KnowledgeQueryMatchedSource]:
        display_names = {source.source_id: source.display_name for source in eligible_sources}
        scores: Dict[str, float] = {}
        for anchor in anchors:
            scores[anchor.sourceId] = max(scores.get(anchor.sourceId, 0.0), anchor.score)
        return [
            KnowledgeQueryMatchedSource(sourceId=source_id, displayName=display_names.get(source_id, source_id), score=round(score, 4))
            for source_id, score in sorted(scores.items(), key=lambda item: (-item[1], item[0]))
        ]

    def _is_ambiguous(self, anchors: Sequence[KnowledgeQueryAnchor]) -> bool:
        if len(anchors) < 2:
            return False
        top = anchors[0].score
        top_sources = {anchor.sourceId for anchor in anchors if top - anchor.score <= 0.03}
        return len(top_sources) > 1

    def _query_id(self) -> str:
        return str(uuid.uuid4())


def build_knowledge_query_service(graph_store: Any) -> KnowledgeQueryService:
    return KnowledgeQueryService(
        source_scope_resolver=SourceScopeResolver(graph_store),
        anchor_searcher=UnifiedAnchorSearcher(graph_store),
        graph_slice_service=GraphSliceQueryService(graph_store),
        evidence_builder=EvidenceBundleBuilder(),
    )
