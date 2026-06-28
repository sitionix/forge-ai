from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence

from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryCoverage,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryFlowPath,
    KnowledgeQueryMatchedNode,
    KnowledgeQueryMatchedSource,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryStatus,
)


TOKEN_PATTERN = re.compile(r"[\w.$:/\\-]+", re.UNICODE)


@dataclass(frozen=True)
class KnowledgeQueryPolicy:
    max_search_candidates: int = 100
    max_matched_nodes: int = 5
    traversal_depth: int = 2
    max_flow_paths: int = 5
    max_evidence_chars: int = 2000


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

    def search(
        self,
        query: str,
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
    ) -> tuple[List[KnowledgeQueryMatchedNode], List[KnowledgeQueryDiagnostic], bool]:
        tokens = self._tokens(query)
        if not tokens or not eligible_sources:
            return [], [], False
        raw_candidates = self.graph_store.query_anchor_candidates(tokens, [source.source_id for source in eligible_sources], policy.max_search_candidates)
        matched_nodes: List[KnowledgeQueryMatchedNode] = []
        seen: set[tuple[str, str, str]] = set()
        for candidate in raw_candidates:
            matched_node = self._matched_node(candidate, tokens)
            key = (matched_node.sourceId, matched_node.snapshotId or "", matched_node.nodeId)
            if key in seen:
                continue
            seen.add(key)
            matched_nodes.append(matched_node)
        matched_nodes.sort(key=lambda item: (-item.score, item.sourceId, item.label.lower(), item.nodeId))
        truncated = len(matched_nodes) > policy.max_matched_nodes
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        if truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="FLOW_RESULT_LIMIT_REACHED",
                    message="Matched graph nodes exceeded the query policy and were truncated before flow extraction.",
                    severity="INFO",
                    metadata={"limit": policy.max_matched_nodes},
                )
            )
        return matched_nodes[: policy.max_matched_nodes], diagnostics, truncated

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

    def _matched_node(self, candidate: Dict[str, Any], tokens: Sequence[str]) -> KnowledgeQueryMatchedNode:
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
        return KnowledgeQueryMatchedNode(
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

    def build(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        policy: KnowledgeQueryPolicy,
    ) -> tuple[Dict[str, List[Dict[str, Any]]], List[KnowledgeQueryDiagnostic]]:
        if not matched_nodes:
            return self._empty_slice(), []
        try:
            slice_bundle = self.graph_store.query_graph_slice([matched_node.dict() for matched_node in matched_nodes], policy.traversal_depth)
        except Exception:
            return self._empty_slice(), [
                KnowledgeQueryDiagnostic(
                    code="GRAPH_SLICE_FAILED",
                    message="Graph slice could not be built from the selected matched nodes.",
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


class FlowPathExtractor:
    def extract(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
    ) -> tuple[List[KnowledgeQueryFlowPath], List[KnowledgeQueryDiagnostic], bool]:
        if not matched_nodes:
            return [], [], False
        nodes = list(slice_bundle.get("nodes") or [])
        edges = list(slice_bundle.get("edges") or [])
        node_by_id = {str(node.get("id") or node.get("nodeId") or ""): node for node in nodes if node.get("id") or node.get("nodeId")}
        calls_by_from: Dict[str, List[Dict[str, Any]]] = {}
        for edge in edges:
            if str(edge.get("edgeType") or edge.get("relation") or edge.get("kind") or "").upper() != "CALLS":
                continue
            from_node = str(edge.get("fromNodeId") or edge.get("from") or "")
            to_node = str(edge.get("toNodeId") or edge.get("to") or "")
            if not from_node or not to_node or from_node not in node_by_id or to_node not in node_by_id:
                continue
            calls_by_from.setdefault(from_node, []).append(edge)
        for outgoing in calls_by_from.values():
            outgoing.sort(key=lambda item: str(item.get("id") or ""))

        flow_paths: List[KnowledgeQueryFlowPath] = []
        truncated = False
        for matched_node in matched_nodes:
            if len(flow_paths) >= policy.max_flow_paths:
                truncated = True
                break
            node_id = matched_node.nodeId
            if node_id not in node_by_id:
                continue
            path_edges = self._first_calls_path(node_id, calls_by_from, policy)
            if not path_edges:
                continue
            node_ids = [node_id]
            for edge in path_edges:
                node_ids.append(str(edge.get("toNodeId") or edge.get("to") or ""))
            unique_nodes = [node_by_id[current] for current in node_ids if current in node_by_id]
            stop_reason = "TERMINAL_NODE"
            complete = True
            last_node_id = node_ids[-1] if node_ids else node_id
            if len(path_edges) >= policy.traversal_depth and calls_by_from.get(last_node_id):
                stop_reason = "FLOW_RESULT_LIMIT_REACHED"
                complete = False
                truncated = True
            flow_paths.append(
                KnowledgeQueryFlowPath(
                    flowId=f"flow-{len(flow_paths) + 1}",
                    sourceId=matched_node.sourceId,
                    nodes=unique_nodes,
                    edges=path_edges,
                    evidence=self._evidence_for_path(evidence, matched_node.sourceId, unique_nodes, path_edges, policy),
                    complete=complete,
                    stopReason=stop_reason,
                )
            )

        diagnostics: List[KnowledgeQueryDiagnostic] = []
        if not flow_paths:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="FLOW_PATH_EXTRACTION_NOT_READY",
                    message="Matched graph nodes were found, but no CALLS flow path could be extracted from the current graph slice.",
                    severity="INFO",
                )
            )
        if truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="FLOW_RESULT_LIMIT_REACHED",
                    message="Flow extraction reached the internal query policy limit.",
                    severity="INFO",
                    metadata={"maxFlowPaths": policy.max_flow_paths, "traversalDepth": policy.traversal_depth},
                )
            )
        return flow_paths, diagnostics, truncated

    def _first_calls_path(
        self,
        start_node_id: str,
        calls_by_from: Dict[str, List[Dict[str, Any]]],
        policy: KnowledgeQueryPolicy,
    ) -> List[Dict[str, Any]]:
        current = start_node_id
        seen = {start_node_id}
        path: List[Dict[str, Any]] = []
        for _ in range(policy.traversal_depth):
            outgoing = calls_by_from.get(current) or []
            next_edge: Optional[Dict[str, Any]] = None
            for edge in outgoing:
                target = str(edge.get("toNodeId") or edge.get("to") or "")
                if target and target not in seen:
                    next_edge = edge
                    break
            if next_edge is None:
                break
            path.append(next_edge)
            current = str(next_edge.get("toNodeId") or next_edge.get("to") or "")
            seen.add(current)
        return path

    def _evidence_for_path(
        self,
        evidence: Sequence[Dict[str, Any]],
        source_id: str,
        nodes: Sequence[Dict[str, Any]],
        edges: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
    ) -> List[Dict[str, Any]]:
        node_ids = {str(node.get("id") or node.get("nodeId") or "") for node in nodes}
        edge_ids = {str(edge.get("id") or edge.get("edgeId") or edge.get("graphEdgeId") or "") for edge in edges}
        selected: List[Dict[str, Any]] = []
        char_budget = policy.max_evidence_chars
        for item in evidence:
            item_source = str(item.get("sourceId") or "")
            item_node = str(item.get("nodeId") or "")
            item_edge = str(item.get("edgeId") or item.get("graphEdgeId") or "")
            if item_source not in {"", source_id}:
                continue
            if item_node and item_node not in node_ids:
                continue
            if item_edge and item_edge not in edge_ids:
                continue
            text_size = len(str(item.get("text") or item.get("summary") or item))
            if text_size > char_budget:
                break
            char_budget -= text_size
            selected.append(dict(item))
        return selected


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
        flow_path_extractor: FlowPathExtractor,
        evidence_builder: EvidenceBundleBuilder,
        policy: KnowledgeQueryPolicy | None = None,
    ) -> None:
        self.source_scope_resolver = source_scope_resolver
        self.anchor_searcher = anchor_searcher
        self.graph_slice_service = graph_slice_service
        self.flow_path_extractor = flow_path_extractor
        self.evidence_builder = evidence_builder
        self.policy = policy or KnowledgeQueryPolicy()

    def query(self, request: KnowledgeQueryRequest) -> KnowledgeQueryResponse:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        eligible_sources, scope_diagnostics = self.source_scope_resolver.resolve()
        diagnostics.extend(scope_diagnostics)
        matched_nodes, search_diagnostics, search_truncated = self.anchor_searcher.search(request.query, eligible_sources, self.policy)
        diagnostics.extend(search_diagnostics)
        if not matched_nodes:
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

        slice_bundle, slice_diagnostics = self.graph_slice_service.build(matched_nodes, self.policy)
        diagnostics.extend(slice_diagnostics)
        evidence_bundle = self.evidence_builder.build(slice_bundle)
        flow_paths, flow_diagnostics, flow_truncated = self.flow_path_extractor.extract(
            matched_nodes,
            slice_bundle,
            evidence_bundle["evidence"],
            self.policy,
        )
        diagnostics.extend(flow_diagnostics)
        matched_sources = self._matched_sources(matched_nodes, eligible_sources)
        status = KnowledgeQueryStatus.OK
        if len(matched_sources) > 1 and self._is_ambiguous(matched_nodes):
            status = KnowledgeQueryStatus.AMBIGUOUS
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="MULTIPLE_SOURCES_MATCHED",
                    message="Multiple sources produced similarly scored matched nodes.",
                    severity="INFO",
                )
            )
        return KnowledgeQueryResponse(
            queryId=self._query_id(),
            status=status,
            intent=request.intent,
            matchedSources=matched_sources,
            matchedNodes=matched_nodes,
            flowPaths=flow_paths,
            nodes=slice_bundle["nodes"],
            edges=slice_bundle["edges"],
            verifiedPaths=evidence_bundle["verifiedPaths"],
            evidence=evidence_bundle["evidence"],
            unresolved=slice_bundle["unresolved"],
            external=slice_bundle["external"],
            coverage=KnowledgeQueryCoverage(
                searchedSourceCount=len(eligible_sources),
                matchedSourceCount=len(matched_sources),
                matchedNodeCount=len(matched_nodes),
                flowPathCount=len(flow_paths),
                nodeCount=len(slice_bundle["nodes"]),
                edgeCount=len(slice_bundle["edges"]),
                evidenceCount=len(evidence_bundle["evidence"]),
                truncated=search_truncated or flow_truncated,
                continuationAvailable=search_truncated or flow_truncated,
            ),
            diagnostics=diagnostics,
        )

    def _matched_sources(
        self, matched_nodes: Sequence[KnowledgeQueryMatchedNode], eligible_sources: Sequence[QuerySource]
    ) -> List[KnowledgeQueryMatchedSource]:
        display_names = {source.source_id: source.display_name for source in eligible_sources}
        scores: Dict[str, float] = {}
        for matched_node in matched_nodes:
            scores[matched_node.sourceId] = max(scores.get(matched_node.sourceId, 0.0), matched_node.score)
        return [
            KnowledgeQueryMatchedSource(sourceId=source_id, displayName=display_names.get(source_id, source_id), score=round(score, 4))
            for source_id, score in sorted(scores.items(), key=lambda item: (-item[1], item[0]))
        ]

    def _is_ambiguous(self, matched_nodes: Sequence[KnowledgeQueryMatchedNode]) -> bool:
        if len(matched_nodes) < 2:
            return False
        top = matched_nodes[0].score
        top_sources = {matched_node.sourceId for matched_node in matched_nodes if top - matched_node.score <= 0.03}
        return len(top_sources) > 1

    def _query_id(self) -> str:
        return str(uuid.uuid4())


def build_knowledge_query_service(graph_store: Any) -> KnowledgeQueryService:
    return KnowledgeQueryService(
        source_scope_resolver=SourceScopeResolver(graph_store),
        anchor_searcher=UnifiedAnchorSearcher(graph_store),
        graph_slice_service=GraphSliceQueryService(graph_store),
        flow_path_extractor=FlowPathExtractor(),
        evidence_builder=EvidenceBundleBuilder(),
    )
