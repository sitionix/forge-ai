from __future__ import annotations

import re
import time
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence, Tuple

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
    graph_slice_depth: int = 2
    max_traversal_nodes: int = 80
    max_flow_paths: int = 25
    max_edges_per_traversal: int = 2000
    max_execution_ms: int = 250
    max_evidence_refs: int = 25


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
                    code="RESULT_LIMIT_REACHED",
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
            slice_bundle = self.graph_store.query_graph_slice([matched_node.dict() for matched_node in matched_nodes], policy.graph_slice_depth)
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


NodeKey = Tuple[str, str, str]
EdgeKey = Tuple[str, str, str]


@dataclass(frozen=True)
class _TraversalPath:
    node_keys: tuple[NodeKey, ...]
    edge_keys: tuple[EdgeKey, ...]
    boundary_edge_keys: tuple[EdgeKey, ...]
    stop_reason: str
    complete: bool


@dataclass
class _TraversalState:
    policy: KnowledgeQueryPolicy
    started_at: float
    expanded: int = 0
    truncated: bool = False

    def allow_step(self) -> bool:
        elapsed_ms = (time.monotonic() - self.started_at) * 1000
        if elapsed_ms > self.policy.max_execution_ms or self.expanded >= self.policy.max_traversal_nodes:
            self.truncated = True
            return False
        self.expanded += 1
        return True


@dataclass
class _Adjacency:
    nodes_by_key: Dict[NodeKey, Dict[str, Any]]
    edges_by_key: Dict[EdgeKey, Dict[str, Any]]
    incoming: Dict[NodeKey, List[EdgeKey]]
    outgoing: Dict[NodeKey, List[EdgeKey]]
    evidence: List[Dict[str, Any]]
    store_truncated: bool = False


class FlowPathExtractor:
    def __init__(self, graph_store: Any | None = None) -> None:
        self.graph_store = graph_store

    def extract(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
    ) -> tuple[List[KnowledgeQueryFlowPath], List[KnowledgeQueryDiagnostic], bool, Dict[str, Any]]:
        if not matched_nodes:
            return [], [], False, self._empty_adjacency_bundle()
        adjacency_bundle = self._load_adjacency(matched_nodes, slice_bundle, evidence, policy)
        adjacency = self._build_adjacency(adjacency_bundle)
        flow_paths_by_key: Dict[tuple[str, tuple[str, ...], tuple[str, ...], tuple[str, ...]], KnowledgeQueryFlowPath] = {}
        state = _TraversalState(policy=policy, started_at=time.monotonic())
        stop_reasons: set[str] = set()

        for matched_node in matched_nodes:
            if len(flow_paths_by_key) >= policy.max_flow_paths:
                state.truncated = True
                break
            matched_key = self._resolve_matched_key(matched_node, adjacency.nodes_by_key)
            if matched_key is None:
                continue
            upstream_paths = self._upstream_paths(matched_key, adjacency, state, policy.max_flow_paths)
            for upstream_path in upstream_paths:
                if len(flow_paths_by_key) >= policy.max_flow_paths:
                    state.truncated = True
                    break
                if not upstream_path.complete:
                    self._add_flow_path(flow_paths_by_key, matched_node, upstream_path, adjacency, policy)
                    stop_reasons.add(upstream_path.stop_reason)
                    continue
                downstream_paths = self._downstream_paths(
                    matched_key,
                    adjacency,
                    state,
                    policy.max_flow_paths - len(flow_paths_by_key),
                    set(upstream_path.node_keys),
                )
                for downstream_path in downstream_paths:
                    combined = self._combine_paths(upstream_path, downstream_path)
                    if not combined.edge_keys and not combined.boundary_edge_keys:
                        continue
                    self._add_flow_path(flow_paths_by_key, matched_node, combined, adjacency, policy)
                    stop_reasons.add(combined.stop_reason)
                    if len(flow_paths_by_key) >= policy.max_flow_paths:
                        state.truncated = True
                        break
                if len(flow_paths_by_key) >= policy.max_flow_paths:
                    break

        flow_paths = list(flow_paths_by_key.values())
        for index, flow_path in enumerate(flow_paths, start=1):
            flow_path.flowId = f"flow-{index}"

        diagnostics: List[KnowledgeQueryDiagnostic] = []
        if not flow_paths:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="NO_CALLS_PATH",
                    message="Matched graph nodes were found, but no verified CALLS path could be built from current graph facts.",
                    severity="INFO",
                )
            )
        if adjacency.store_truncated or state.truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="RESULT_LIMIT_REACHED",
                    message="Flow extraction reached an internal safety limit.",
                    severity="INFO",
                    metadata={
                        "maxTraversalNodes": policy.max_traversal_nodes,
                        "maxFlowPaths": policy.max_flow_paths,
                        "maxEdgesPerTraversal": policy.max_edges_per_traversal,
                        "maxExecutionMs": policy.max_execution_ms,
                        "maxEvidenceRefs": policy.max_evidence_refs,
                    },
                )
            )
        if "CYCLE_DETECTED" in stop_reasons:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="CYCLE_DETECTED",
                    message="Flow extraction stopped one or more paths at a CALLS cycle.",
                    severity="INFO",
                )
            )
        return flow_paths, diagnostics, adjacency.store_truncated or state.truncated, adjacency_bundle

    def _load_adjacency(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
    ) -> Dict[str, Any]:
        if self.graph_store is not None and hasattr(self.graph_store, "load_call_adjacency_for_sources"):
            return dict(
                self.graph_store.load_call_adjacency_for_sources(
                    self._source_scopes(matched_nodes),
                    max_edges=policy.max_edges_per_traversal,
                    max_evidence=policy.max_evidence_refs,
                )
            )
        return {
            "nodes": list(slice_bundle.get("nodes") or []),
            "edges": [edge for edge in slice_bundle.get("edges") or [] if self._edge_type(edge) == "CALLS"],
            "evidence": list(evidence),
            "unresolved": list(slice_bundle.get("unresolved") or []),
            "external": list(slice_bundle.get("external") or []),
            "verifiedPaths": list(slice_bundle.get("verifiedPaths") or []),
            "truncated": False,
        }

    def _source_scopes(self, matched_nodes: Sequence[KnowledgeQueryMatchedNode]) -> List[Dict[str, Any]]:
        grouped: Dict[tuple[str, str], set[str]] = {}
        for matched_node in matched_nodes:
            source_id = matched_node.sourceId
            if not source_id or not matched_node.nodeId:
                continue
            key = (source_id, matched_node.snapshotId or "")
            grouped.setdefault(key, set()).add(matched_node.nodeId)
        return [
            {"sourceId": source_id, "snapshotId": snapshot_id, "nodeIds": sorted(node_ids)} for (source_id, snapshot_id), node_ids in sorted(grouped.items())
        ]

    def _build_adjacency(self, adjacency_bundle: Dict[str, Any]) -> _Adjacency:
        nodes_by_key: Dict[NodeKey, Dict[str, Any]] = {}
        for node in adjacency_bundle.get("nodes") or []:
            key = self._node_key(node)
            if key is not None:
                nodes_by_key[key] = dict(node)

        edges_by_key: Dict[EdgeKey, Dict[str, Any]] = {}
        incoming: Dict[NodeKey, List[EdgeKey]] = {}
        outgoing: Dict[NodeKey, List[EdgeKey]] = {}
        for edge in adjacency_bundle.get("edges") or []:
            if self._edge_type(edge) != "CALLS":
                continue
            edge_key = self._edge_key(edge)
            from_key = self._edge_from_key(edge)
            if edge_key is None or from_key is None:
                continue
            edge_copy = dict(edge)
            edges_by_key[edge_key] = edge_copy
            outgoing.setdefault(from_key, []).append(edge_key)
            to_key = self._edge_to_key(edge)
            if to_key is not None:
                incoming.setdefault(to_key, []).append(edge_key)

        def sort_key(edge_key: EdgeKey) -> tuple[str, str, str]:
            edge = edges_by_key[edge_key]
            return (str(edge.get("id") or ""), str(edge.get("fromNodeId") or ""), str(edge.get("toNodeId") or ""))

        for values in incoming.values():
            values.sort(key=sort_key)
        for values in outgoing.values():
            values.sort(key=sort_key)
        return _Adjacency(
            nodes_by_key=nodes_by_key,
            edges_by_key=edges_by_key,
            incoming=incoming,
            outgoing=outgoing,
            evidence=[dict(item) for item in adjacency_bundle.get("evidence") or []],
            store_truncated=bool(adjacency_bundle.get("truncated")),
        )

    def _upstream_paths(
        self,
        matched_key: NodeKey,
        adjacency: _Adjacency,
        state: _TraversalState,
        limit: int,
    ) -> List[_TraversalPath]:
        results: List[_TraversalPath] = []

        def visit(current_key: NodeKey, node_keys_reversed: tuple[NodeKey, ...], edge_keys_reversed: tuple[EdgeKey, ...], visited: set[NodeKey]) -> None:
            if len(results) >= limit:
                state.truncated = True
                return
            if not state.allow_step():
                results.append(
                    _TraversalPath(
                        node_keys=node_keys_reversed,
                        edge_keys=edge_keys_reversed,
                        boundary_edge_keys=(),
                        stop_reason="RESULT_LIMIT_REACHED",
                        complete=False,
                    )
                )
                return
            incoming_edges = adjacency.incoming.get(current_key) or []
            if not incoming_edges:
                results.append(
                    _TraversalPath(
                        node_keys=node_keys_reversed,
                        edge_keys=edge_keys_reversed,
                        boundary_edge_keys=(),
                        stop_reason="TERMINAL_NODE",
                        complete=True,
                    )
                )
                return
            for edge_key in incoming_edges:
                edge = adjacency.edges_by_key[edge_key]
                source_key = self._edge_from_key(edge)
                if source_key is None or source_key[0] != current_key[0]:
                    results.append(
                        _TraversalPath(
                            node_keys=node_keys_reversed,
                            edge_keys=edge_keys_reversed,
                            boundary_edge_keys=(edge_key,),
                            stop_reason="SOURCE_BOUNDARY",
                            complete=True,
                        )
                    )
                    continue
                if source_key in visited:
                    results.append(
                        _TraversalPath(
                            node_keys=(source_key, *node_keys_reversed),
                            edge_keys=(edge_key, *edge_keys_reversed),
                            boundary_edge_keys=(),
                            stop_reason="CYCLE_DETECTED",
                            complete=False,
                        )
                    )
                    continue
                visit(source_key, (source_key, *node_keys_reversed), (edge_key, *edge_keys_reversed), {*visited, source_key})

        visit(matched_key, (matched_key,), (), {matched_key})
        return results or [_TraversalPath(node_keys=(matched_key,), edge_keys=(), boundary_edge_keys=(), stop_reason="TERMINAL_NODE", complete=True)]

    def _downstream_paths(
        self,
        start_key: NodeKey,
        adjacency: _Adjacency,
        state: _TraversalState,
        limit: int,
        visited: set[NodeKey],
    ) -> List[_TraversalPath]:
        results: List[_TraversalPath] = []

        def visit(current_key: NodeKey, node_keys: tuple[NodeKey, ...], edge_keys: tuple[EdgeKey, ...], current_visited: set[NodeKey]) -> None:
            if len(results) >= limit:
                state.truncated = True
                return
            if not state.allow_step():
                results.append(
                    _TraversalPath(
                        node_keys=node_keys,
                        edge_keys=edge_keys,
                        boundary_edge_keys=(),
                        stop_reason="RESULT_LIMIT_REACHED",
                        complete=False,
                    )
                )
                return
            outgoing_edges = adjacency.outgoing.get(current_key) or []
            if not outgoing_edges:
                results.append(
                    _TraversalPath(
                        node_keys=node_keys,
                        edge_keys=edge_keys,
                        boundary_edge_keys=(),
                        stop_reason="TERMINAL_NODE",
                        complete=True,
                    )
                )
                return
            for edge_key in outgoing_edges:
                edge = adjacency.edges_by_key[edge_key]
                if self._is_external_edge(edge) and self._edge_to_key(edge) is None:
                    results.append(
                        _TraversalPath(
                            node_keys=node_keys,
                            edge_keys=edge_keys,
                            boundary_edge_keys=(edge_key,),
                            stop_reason="EXTERNAL_NODE",
                            complete=True,
                        )
                    )
                    continue
                if self._is_unresolved_edge(edge):
                    results.append(
                        _TraversalPath(
                            node_keys=node_keys,
                            edge_keys=edge_keys,
                            boundary_edge_keys=(edge_key,),
                            stop_reason="UNRESOLVED_EDGE",
                            complete=True,
                        )
                    )
                    continue
                target_key = self._edge_to_key(edge)
                if target_key is None or target_key[0] != current_key[0] or target_key not in adjacency.nodes_by_key:
                    results.append(
                        _TraversalPath(
                            node_keys=node_keys,
                            edge_keys=edge_keys,
                            boundary_edge_keys=(edge_key,),
                            stop_reason="SOURCE_BOUNDARY",
                            complete=True,
                        )
                    )
                    continue
                if target_key in current_visited:
                    results.append(
                        _TraversalPath(
                            node_keys=(*node_keys, target_key),
                            edge_keys=(*edge_keys, edge_key),
                            boundary_edge_keys=(),
                            stop_reason="CYCLE_DETECTED",
                            complete=False,
                        )
                    )
                    continue
                if self._is_external_node(adjacency.nodes_by_key[target_key]) or self._is_external_edge(edge):
                    results.append(
                        _TraversalPath(
                            node_keys=(*node_keys, target_key),
                            edge_keys=(*edge_keys, edge_key),
                            boundary_edge_keys=(),
                            stop_reason="EXTERNAL_NODE",
                            complete=True,
                        )
                    )
                    continue
                visit(target_key, (*node_keys, target_key), (*edge_keys, edge_key), {*current_visited, target_key})

        visit(start_key, (start_key,), (), set(visited))
        return results or [_TraversalPath(node_keys=(start_key,), edge_keys=(), boundary_edge_keys=(), stop_reason="TERMINAL_NODE", complete=True)]

    def _combine_paths(self, upstream_path: _TraversalPath, downstream_path: _TraversalPath) -> _TraversalPath:
        return _TraversalPath(
            node_keys=(*upstream_path.node_keys, *downstream_path.node_keys[1:]),
            edge_keys=(*upstream_path.edge_keys, *downstream_path.edge_keys),
            boundary_edge_keys=(*upstream_path.boundary_edge_keys, *downstream_path.boundary_edge_keys),
            stop_reason=downstream_path.stop_reason,
            complete=upstream_path.complete and downstream_path.complete,
        )

    def _add_flow_path(
        self,
        flow_paths_by_key: Dict[tuple[str, tuple[str, ...], tuple[str, ...], tuple[str, ...]], KnowledgeQueryFlowPath],
        matched_node: KnowledgeQueryMatchedNode,
        path: _TraversalPath,
        adjacency: _Adjacency,
        policy: KnowledgeQueryPolicy,
    ) -> None:
        source_id = matched_node.sourceId
        node_ids = [node_key[2] for node_key in path.node_keys]
        edge_ids = [edge_key[2] for edge_key in path.edge_keys]
        boundary_edge_ids = [edge_key[2] for edge_key in path.boundary_edge_keys]
        dedupe_key = (source_id, tuple(node_ids), tuple(edge_ids), tuple(boundary_edge_ids))
        existing = flow_paths_by_key.get(dedupe_key)
        if existing is not None:
            if matched_node.nodeId not in existing.matchedNodeIds:
                existing.matchedNodeIds.append(matched_node.nodeId)
            return
        nodes = [dict(adjacency.nodes_by_key.get(node_key) or self._fallback_node(node_key)) for node_key in path.node_keys]
        edges = [dict(adjacency.edges_by_key[edge_key]) for edge_key in path.edge_keys if edge_key in adjacency.edges_by_key]
        evidence = self._evidence_for_path(adjacency.evidence, source_id, path, policy)
        flow_paths_by_key[dedupe_key] = KnowledgeQueryFlowPath(
            flowId="flow-pending",
            sourceId=source_id,
            matchedNodeIds=[matched_node.nodeId],
            nodeIds=node_ids,
            edgeIds=edge_ids,
            boundaryEdgeIds=boundary_edge_ids,
            evidenceIds=[str(item.get("id")) for item in evidence if item.get("id")],
            nodes=nodes,
            edges=edges,
            evidence=evidence,
            complete=path.complete,
            stopReason=path.stop_reason,
        )

    def _evidence_for_path(
        self,
        evidence: Sequence[Dict[str, Any]],
        source_id: str,
        path: _TraversalPath,
        policy: KnowledgeQueryPolicy,
    ) -> List[Dict[str, Any]]:
        node_ids = {node_key[2] for node_key in path.node_keys}
        edge_ids = {edge_key[2] for edge_key in (*path.edge_keys, *path.boundary_edge_keys)}
        selected: List[Dict[str, Any]] = []
        seen: set[str] = set()
        for item in evidence:
            item_source = str(item.get("sourceId") or "")
            item_node = str(item.get("nodeId") or "")
            item_edge = str(item.get("edgeId") or item.get("graphEdgeId") or "")
            item_id = str(item.get("id") or item)
            if item_id in seen:
                continue
            if item_source not in {"", source_id}:
                continue
            if item_node and item_node not in node_ids:
                continue
            if item_edge and item_edge not in edge_ids:
                continue
            if not item_node and not item_edge:
                continue
            selected.append(dict(item))
            seen.add(item_id)
            if len(selected) >= policy.max_evidence_refs:
                break
        return selected

    def _resolve_matched_key(self, matched_node: KnowledgeQueryMatchedNode, nodes_by_key: Dict[NodeKey, Dict[str, Any]]) -> Optional[NodeKey]:
        exact_key = (matched_node.sourceId, matched_node.snapshotId or "", matched_node.nodeId)
        if exact_key in nodes_by_key:
            return exact_key
        candidates = [
            key
            for key in nodes_by_key
            if key[0] == matched_node.sourceId and key[2] == matched_node.nodeId and (not matched_node.snapshotId or key[1] == matched_node.snapshotId)
        ]
        return sorted(candidates)[0] if candidates else None

    def _node_key(self, node: Dict[str, Any]) -> Optional[NodeKey]:
        node_id = str(node.get("id") or node.get("nodeId") or "")
        source_id = str(node.get("sourceId") or "")
        snapshot_id = str(node.get("snapshotId") or node.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return (source_id, snapshot_id, node_id)

    def _edge_key(self, edge: Dict[str, Any]) -> Optional[EdgeKey]:
        edge_id = str(edge.get("id") or edge.get("edgeId") or edge.get("graphEdgeId") or "")
        source_id = str(edge.get("sourceId") or "")
        snapshot_id = str(edge.get("snapshotId") or edge.get("graphRevision") or "")
        if not edge_id or not source_id:
            return None
        return (source_id, snapshot_id, edge_id)

    def _edge_from_key(self, edge: Dict[str, Any]) -> Optional[NodeKey]:
        node_id = str(edge.get("fromNodeId") or edge.get("from") or "")
        source_id = str(edge.get("sourceId") or "")
        snapshot_id = str(edge.get("snapshotId") or edge.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return (source_id, snapshot_id, node_id)

    def _edge_to_key(self, edge: Dict[str, Any]) -> Optional[NodeKey]:
        node_id = str(edge.get("toNodeId") or edge.get("to") or "")
        source_id = str(edge.get("sourceId") or "")
        snapshot_id = str(edge.get("snapshotId") or edge.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return (source_id, snapshot_id, node_id)

    def _edge_type(self, edge: Dict[str, Any]) -> str:
        return str(edge.get("edgeType") or edge.get("relation") or edge.get("kind") or "").upper()

    def _is_unresolved_edge(self, edge: Dict[str, Any]) -> bool:
        status = str(edge.get("resolutionStatus") or "").upper()
        if status in {"UNRESOLVED", "DYNAMIC_TARGET", "MULTIPLE_CANDIDATES", "INTERFACE_TARGET", "AMBIGUOUS"}:
            return True
        return not self._is_external_edge(edge) and self._edge_to_key(edge) is None

    def _is_external_edge(self, edge: Dict[str, Any]) -> bool:
        return bool(edge.get("external")) or str(edge.get("resolutionStatus") or "").upper() == "EXTERNAL_TARGET"

    def _is_external_node(self, node: Dict[str, Any]) -> bool:
        return bool(node.get("external")) or str(node.get("nodeKind") or node.get("kind") or "").upper() == "EXTERNAL"

    def _fallback_node(self, node_key: NodeKey) -> Dict[str, Any]:
        return {"id": node_key[2], "sourceId": node_key[0], "snapshotId": node_key[1], "label": node_key[2]}

    def _empty_adjacency_bundle(self) -> Dict[str, Any]:
        return {"nodes": [], "edges": [], "evidence": [], "unresolved": [], "external": [], "verifiedPaths": [], "truncated": False}


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
        flow_paths, flow_diagnostics, flow_truncated, flow_bundle = self.flow_path_extractor.extract(
            matched_nodes,
            slice_bundle,
            slice_bundle.get("evidence") or [],
            self.policy,
        )
        diagnostics.extend(flow_diagnostics)
        response_bundle = self._merge_bundles(slice_bundle, flow_bundle)
        evidence_bundle = self.evidence_builder.build(response_bundle)
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
            nodes=response_bundle["nodes"],
            edges=response_bundle["edges"],
            verifiedPaths=evidence_bundle["verifiedPaths"],
            evidence=evidence_bundle["evidence"],
            unresolved=response_bundle["unresolved"],
            external=response_bundle["external"],
            coverage=KnowledgeQueryCoverage(
                searchedSourceCount=len(eligible_sources),
                matchedSourceCount=len(matched_sources),
                matchedNodeCount=len(matched_nodes),
                flowPathCount=len(flow_paths),
                nodeCount=len(response_bundle["nodes"]),
                edgeCount=len(response_bundle["edges"]),
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

    def _merge_bundles(self, first: Dict[str, List[Dict[str, Any]]], second: Dict[str, Any]) -> Dict[str, List[Dict[str, Any]]]:
        return {
            "nodes": self._dedupe_items([*(first.get("nodes") or []), *(second.get("nodes") or [])], "id"),
            "edges": self._dedupe_items([*(first.get("edges") or []), *(second.get("edges") or [])], "id"),
            "evidence": self._dedupe_items([*(first.get("evidence") or []), *(second.get("evidence") or [])], "id"),
            "unresolved": self._dedupe_items([*(first.get("unresolved") or []), *(second.get("unresolved") or [])], "id"),
            "external": self._dedupe_items([*(first.get("external") or []), *(second.get("external") or [])], "id"),
            "verifiedPaths": self._dedupe_items([*(first.get("verifiedPaths") or []), *(second.get("verifiedPaths") or [])], "pathId"),
        }

    def _dedupe_items(self, items: Sequence[Dict[str, Any]], field: str) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        seen: set[str] = set()
        for item in items:
            key = str(item.get("sourceId") or "") + ":" + str(item.get("snapshotId") or "") + ":" + str(item.get(field) or item)
            if key in seen:
                continue
            seen.add(key)
            result.append(dict(item))
        return result

    def _query_id(self) -> str:
        return str(uuid.uuid4())


def build_knowledge_query_service(graph_store: Any) -> KnowledgeQueryService:
    return KnowledgeQueryService(
        source_scope_resolver=SourceScopeResolver(graph_store),
        anchor_searcher=UnifiedAnchorSearcher(graph_store),
        graph_slice_service=GraphSliceQueryService(graph_store),
        flow_path_extractor=FlowPathExtractor(graph_store),
        evidence_builder=EvidenceBundleBuilder(),
    )
