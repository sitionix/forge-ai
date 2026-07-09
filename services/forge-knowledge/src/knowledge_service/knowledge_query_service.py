from __future__ import annotations

import re
import time
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Sequence, Tuple

from knowledge_service.knowledge_search import (
    DeterministicCodeSearchEngine,
    QueryNormalizer,
    SearchConfig,
    SearchDocument,
    compact_identifier,
)
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

@dataclass(frozen=True)
class KnowledgeQueryPolicy:
    max_search_documents: int = 5000
    max_search_candidates: int = 100
    max_candidates_per_provider: int = 100
    max_matched_nodes: int = 5
    graph_slice_depth: int = 2
    max_traversal_nodes: int = 80
    max_flow_paths: int = 25
    max_edges_per_traversal: int = 2000
    max_execution_ms: int = 250
    max_evidence_refs: int = 25
    min_lexical_score: float = 0.28
    min_fuzzy_score: float = 0.58
    fuzzy_max_edit_distance: int = 3
    enable_fuzzy_search: bool = True
    enable_search_diagnostics: bool = True


@dataclass(frozen=True)
class QuerySource:
    source_id: str
    display_name: str
    graph_id: str
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
            graph_id = str(source.get("graphId") or "")
            node_count = int(source.get("nodeCount") or 0)
            edge_count = int(source.get("edgeCount") or 0)
            if not source_id:
                continue
            if not graph_id:
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="SOURCE_WITHOUT_CURRENT_GRAPH",
                        message="Source has no current graph and was skipped.",
                        severity="INFO",
                        sourceId=source_id,
                    )
                )
                continue
            if node_count <= 0:
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="SOURCE_WITHOUT_GRAPH_FACTS",
                        message="Source has a current graph but no graph nodes.",
                        severity="INFO",
                        sourceId=source_id,
                    )
                )
                continue
            eligible.append(QuerySource(source_id=source_id, display_name=display_name, graph_id=graph_id, node_count=node_count, edge_count=edge_count))
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
    def __init__(self, graph_store: Any, search_engine: DeterministicCodeSearchEngine | None = None) -> None:
        self.graph_store = graph_store
        self.search_engine = search_engine or DeterministicCodeSearchEngine()
        self.normalizer = QueryNormalizer()

    def search(
        self,
        query: str,
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
    ) -> tuple[List[KnowledgeQueryMatchedNode], List[KnowledgeQueryDiagnostic], bool]:
        search_query = self.normalizer.normalize(query)
        if not search_query.tokens or not eligible_sources:
            return [], [], False
        raw_documents, document_truncated = self._load_search_documents(search_query.tokens, eligible_sources, policy)
        documents = [SearchDocument.from_graph_node(candidate) for candidate in raw_documents if candidate.get("sourceId") or candidate.get("source_id")]
        result = self.search_engine.search(query, documents, self._search_config(policy))
        matched_nodes = [
            KnowledgeQueryMatchedNode(**candidate.document.to_matched_node_dict(candidate.score, candidate.reasons))
            for candidate in result.candidates
        ]
        truncated = len(matched_nodes) > policy.max_matched_nodes
        diagnostics: List[KnowledgeQueryDiagnostic] = [self._search_diagnostic(item) for item in result.diagnostics]
        if policy.enable_search_diagnostics and (document_truncated or result.candidate_limit_reached):
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="SEARCH_CANDIDATE_LIMIT_REACHED",
                    message="Search reached an internal candidate safety limit before ranking completed.",
                    severity="INFO",
                    metadata={
                        "maxSearchDocuments": policy.max_search_documents,
                        "maxCandidatesPerProvider": policy.max_candidates_per_provider,
                        "maxSearchCandidates": policy.max_search_candidates,
                    },
                )
            )
        if policy.enable_search_diagnostics and documents and not matched_nodes:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="SEARCH_MATCHES_BELOW_THRESHOLD",
                    message="Search inspected current graph facts, but deterministic matches did not clear ranking thresholds.",
                    severity="INFO",
                )
            )
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

    def _search_diagnostic(self, item: Dict[str, Any]) -> KnowledgeQueryDiagnostic:
        metadata = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
        return KnowledgeQueryDiagnostic(
            code=str(item.get("code") or "SEARCH_DIAGNOSTIC"),
            message=str(item.get("message") or "Search diagnostic."),
            severity=str(item.get("severity") or "INFO"),
            sourceId=item.get("sourceId"),
            metadata=dict(metadata),
        )

    def _load_search_documents(
        self,
        tokens: Sequence[str],
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
    ) -> tuple[List[Dict[str, Any]], bool]:
        source_ids = [source.source_id for source in eligible_sources]
        requested_limit = max(1, int(policy.max_search_documents or 1)) + 1
        if hasattr(self.graph_store, "query_search_documents"):
            raw_documents = list(self.graph_store.query_search_documents(source_ids, requested_limit))
        else:
            raw_documents = list(self.graph_store.query_anchor_candidates(list(tokens), source_ids, requested_limit))
        truncated = len(raw_documents) > policy.max_search_documents
        if truncated:
            raw_documents = raw_documents[: policy.max_search_documents]
        return raw_documents, truncated

    def _search_config(self, policy: KnowledgeQueryPolicy) -> SearchConfig:
        return SearchConfig(
            max_candidates_per_provider=policy.max_candidates_per_provider,
            max_total_candidates=policy.max_search_candidates,
            min_lexical_score=policy.min_lexical_score,
            min_fuzzy_score=policy.min_fuzzy_score,
            fuzzy_max_edit_distance=policy.fuzzy_max_edit_distance,
            enable_fuzzy_search=policy.enable_fuzzy_search,
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

_FLOW_FROM_TO_RE = re.compile(r"\bfrom\s+(?P<source>.+?)\s+to\s+(?P<target>.+)$", re.IGNORECASE)
_FLOW_HOW_USE_RE = re.compile(r"\bhow\s+does\s+(?P<source>.+?)\s+use\s+(?P<target>.+)$", re.IGNORECASE)
_FLOW_STOP_TOKENS = {
    "show",
    "flow",
    "from",
    "to",
    "how",
    "does",
    "do",
    "use",
    "uses",
    "using",
    "map",
    "maps",
    "mapped",
}


@dataclass(frozen=True)
class _FlowQuerySpec:
    source_text: str
    target_text: str
    source_tokens: tuple[str, ...]
    target_tokens: tuple[str, ...]
    source_compact: str
    target_compact: str


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
    structural_incoming: Dict[NodeKey, List[EdgeKey]]
    structural_outgoing: Dict[NodeKey, List[EdgeKey]]
    evidence: List[Dict[str, Any]]
    store_truncated: bool = False


class FlowPathExtractor:
    def __init__(self, graph_store: Any | None = None) -> None:
        self.graph_store = graph_store
        self.normalizer = QueryNormalizer()

    def extract(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
        query: str | None = None,
    ) -> tuple[List[KnowledgeQueryFlowPath], List[KnowledgeQueryDiagnostic], bool, Dict[str, Any]]:
        if not matched_nodes:
            return [], [], False, self._empty_adjacency_bundle()
        flow_spec = self._flow_query_spec(query)
        adjacency_bundle = self._load_adjacency(matched_nodes, slice_bundle, evidence, policy, include_structural=flow_spec is not None)
        adjacency = self._build_adjacency(adjacency_bundle)
        flow_paths_by_key: Dict[tuple[str, tuple[str, ...], tuple[str, ...], tuple[str, ...]], KnowledgeQueryFlowPath] = {}
        state = _TraversalState(policy=policy, started_at=time.monotonic())
        stop_reasons: set[str] = set()

        if flow_spec is not None:
            self._add_direct_flow_query_paths(flow_paths_by_key, matched_nodes, flow_spec, adjacency, state, policy)
            flow_paths = list(flow_paths_by_key.values())
            for index, flow_path in enumerate(flow_paths, start=1):
                flow_path.flowId = f"flow-{index}"
            diagnostics = self._flow_diagnostics(flow_paths, adjacency, state, stop_reasons, policy)
            return flow_paths, diagnostics, adjacency.store_truncated or state.truncated, adjacency_bundle

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

        diagnostics = self._flow_diagnostics(flow_paths, adjacency, state, stop_reasons, policy)
        return flow_paths, diagnostics, adjacency.store_truncated or state.truncated, adjacency_bundle

    def _flow_diagnostics(
        self,
        flow_paths: Sequence[KnowledgeQueryFlowPath],
        adjacency: _Adjacency,
        state: _TraversalState,
        stop_reasons: set[str],
        policy: KnowledgeQueryPolicy,
    ) -> List[KnowledgeQueryDiagnostic]:
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
        return diagnostics

    def _load_adjacency(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
        include_structural: bool = False,
    ) -> Dict[str, Any]:
        if self.graph_store is not None and hasattr(self.graph_store, "load_call_adjacency_for_sources"):
            return dict(
                self.graph_store.load_call_adjacency_for_sources(
                    self._source_scopes(matched_nodes),
                    max_edges=policy.max_edges_per_traversal,
                    max_evidence=500 if include_structural else policy.max_evidence_refs,
                    include_structural=include_structural,
                )
            )
        return {
            "nodes": list(slice_bundle.get("nodes") or []),
            "edges": [
                edge
                for edge in slice_bundle.get("edges") or []
                if self._edge_type(edge) == "CALLS" or (include_structural and self._edge_type(edge) in {"DECLARES", "USES_FIELD"})
            ],
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
            key = (source_id, matched_node.graphId or "")
            grouped.setdefault(key, set()).add(matched_node.nodeId)
        return [
            {"sourceId": source_id, "graphId": graph_id, "nodeIds": sorted(node_ids)} for (source_id, graph_id), node_ids in sorted(grouped.items())
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
        structural_incoming: Dict[NodeKey, List[EdgeKey]] = {}
        structural_outgoing: Dict[NodeKey, List[EdgeKey]] = {}
        for edge in adjacency_bundle.get("edges") or []:
            edge_key = self._edge_key(edge)
            from_key = self._edge_from_key(edge)
            if edge_key is None or from_key is None:
                continue
            edge_copy = dict(edge)
            edges_by_key[edge_key] = edge_copy
            to_key = self._edge_to_key(edge)
            if self._edge_type(edge) == "CALLS":
                outgoing.setdefault(from_key, []).append(edge_key)
                if to_key is not None:
                    incoming.setdefault(to_key, []).append(edge_key)
            elif self._edge_type(edge) in {"DECLARES", "USES_FIELD"}:
                structural_outgoing.setdefault(from_key, []).append(edge_key)
                if to_key is not None:
                    structural_incoming.setdefault(to_key, []).append(edge_key)

        def sort_key(edge_key: EdgeKey) -> tuple[str, str, str]:
            edge = edges_by_key[edge_key]
            return (str(edge.get("id") or ""), str(edge.get("fromNodeId") or ""), str(edge.get("toNodeId") or ""))

        for values in incoming.values():
            values.sort(key=sort_key)
        for values in outgoing.values():
            values.sort(key=sort_key)
        for values in structural_incoming.values():
            values.sort(key=sort_key)
        for values in structural_outgoing.values():
            values.sort(key=sort_key)
        return _Adjacency(
            nodes_by_key=nodes_by_key,
            edges_by_key=edges_by_key,
            incoming=incoming,
            outgoing=outgoing,
            structural_incoming=structural_incoming,
            structural_outgoing=structural_outgoing,
            evidence=[dict(item) for item in adjacency_bundle.get("evidence") or []],
            store_truncated=bool(adjacency_bundle.get("truncated")),
        )

    def _flow_query_spec(self, query: str | None) -> Optional[_FlowQuerySpec]:
        raw_query = str(query or "").strip()
        if not raw_query:
            return None
        match = _FLOW_FROM_TO_RE.search(raw_query) or _FLOW_HOW_USE_RE.search(raw_query)
        if not match:
            return None
        source_text = self._clean_flow_fragment(match.group("source"))
        target_text = self._clean_flow_fragment(match.group("target"))
        if not source_text or not target_text:
            return None
        source_query = self.normalizer.normalize(source_text)
        target_query = self.normalizer.normalize(target_text)
        return _FlowQuerySpec(
            source_text=source_text,
            target_text=target_text,
            source_tokens=tuple(self._flow_tokens(source_query.tokens)),
            target_tokens=tuple(self._flow_tokens(target_query.tokens)),
            source_compact=source_query.compact,
            target_compact=target_query.compact,
        )

    def _clean_flow_fragment(self, value: str) -> str:
        return str(value or "").strip().strip(" \t\r\n:;,.?!")

    def _flow_tokens(self, tokens: Sequence[str]) -> List[str]:
        result: List[str] = []
        seen: set[str] = set()
        for token in tokens:
            normalized = str(token or "").lower()
            if not normalized or normalized in _FLOW_STOP_TOKENS or len(normalized) < 3:
                continue
            if normalized in seen:
                continue
            seen.add(normalized)
            result.append(normalized)
        return result

    def _add_direct_flow_query_paths(
        self,
        flow_paths_by_key: Dict[tuple[str, tuple[str, ...], tuple[str, ...], tuple[str, ...]], KnowledgeQueryFlowPath],
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        flow_spec: _FlowQuerySpec,
        adjacency: _Adjacency,
        state: _TraversalState,
        policy: KnowledgeQueryPolicy,
    ) -> None:
        source_candidates = self._rank_flow_callables(adjacency, flow_spec.source_tokens, flow_spec.source_compact)
        target_candidates = self._rank_target_callables(adjacency, matched_nodes, flow_spec)
        if not source_candidates or not target_candidates:
            return

        source_candidates = self._near_top_flow_candidates(source_candidates, limit=20)
        target_candidates = self._near_top_flow_candidates(target_candidates, limit=50)
        path_candidates: List[tuple[float, _TraversalPath, tuple[str, ...]]] = []
        seen_paths: set[tuple[tuple[str, ...], tuple[str, ...]]] = set()
        for source_score, source_key in source_candidates:
            for target_score, target_key in target_candidates:
                if len(path_candidates) >= policy.max_flow_paths * 4:
                    state.truncated = True
                    break
                if source_key == target_key:
                    continue
                path = self._shortest_call_path(source_key, target_key, adjacency, state, max_depth=max(1, policy.graph_slice_depth + 2))
                if path is None:
                    continue
                dedupe_key = (tuple(node_key[2] for node_key in path.node_keys), tuple(edge_key[2] for edge_key in path.edge_keys))
                if dedupe_key in seen_paths:
                    continue
                seen_paths.add(dedupe_key)
                path_score = source_score + target_score - (0.25 * len(path.edge_keys))
                matched_ids = tuple(self._matched_ids_for_direct_path(matched_nodes, path))
                path_candidates.append((path_score, path, matched_ids))
            if state.truncated:
                break

        path_candidates.sort(
            key=lambda item: (
                -item[0],
                len(item[1].edge_keys),
                self._path_first_line(item[1], adjacency),
                [node_key[2] for node_key in item[1].node_keys],
            )
        )
        for _, path, matched_ids in path_candidates[: policy.max_flow_paths]:
            self._add_flow_path(
                flow_paths_by_key,
                matched_nodes[0],
                path,
                adjacency,
                policy,
                matched_node_ids=list(matched_ids),
            )

    def _near_top_flow_candidates(self, candidates: Sequence[tuple[float, NodeKey]], limit: int) -> List[tuple[float, NodeKey]]:
        if not candidates:
            return []
        top_score = candidates[0][0]
        threshold = max(1.0, top_score - 4.0) if top_score >= 5.0 else 1.0
        selected = [candidate for candidate in candidates if candidate[0] >= threshold]
        return list(selected[:limit] or candidates[:limit])

    def _rank_flow_callables(
        self,
        adjacency: _Adjacency,
        tokens: Sequence[str],
        compact: str,
    ) -> List[tuple[float, NodeKey]]:
        scored: List[tuple[float, NodeKey]] = []
        for node_key, node in adjacency.nodes_by_key.items():
            if self._node_kind(node) != "CALLABLE":
                continue
            score = self._flow_node_score(node, tokens, compact)
            if score > 0:
                scored.append((score, node_key))
        scored.sort(key=lambda item: (-item[0], self._node_label(adjacency.nodes_by_key[item[1]]), item[1][2]))
        return scored

    def _path_first_line(self, path: _TraversalPath, adjacency: _Adjacency) -> int:
        edge_ids = {edge_key[2] for edge_key in path.edge_keys}
        evidence_lines = [
            int(item.get("lineStart") or 0)
            for item in adjacency.evidence
            if item.get("edgeId") in edge_ids and int(item.get("lineStart") or 0) > 0
        ]
        if evidence_lines:
            return min(evidence_lines)
        node_lines = [
            int((adjacency.nodes_by_key.get(node_key) or {}).get("lineStart") or 0)
            for node_key in path.node_keys[1:]
            if int((adjacency.nodes_by_key.get(node_key) or {}).get("lineStart") or 0) > 0
        ]
        return min(node_lines) if node_lines else 1_000_000

    def _rank_target_callables(
        self,
        adjacency: _Adjacency,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        flow_spec: _FlowQuerySpec,
    ) -> List[tuple[float, NodeKey]]:
        scored_by_key: Dict[NodeKey, float] = {}
        for score, node_key in self._rank_flow_callables(adjacency, flow_spec.target_tokens, flow_spec.target_compact):
            if score >= self._minimum_target_score(flow_spec):
                scored_by_key[node_key] = max(scored_by_key.get(node_key, 0.0), score)

        for matched_node in matched_nodes:
            matched_key = self._resolve_matched_key(matched_node, adjacency.nodes_by_key)
            if matched_key is None:
                continue
            matched_graph_node = adjacency.nodes_by_key.get(matched_key) or {}
            matched_score = self._flow_node_score(matched_graph_node, flow_spec.target_tokens, flow_spec.target_compact)
            if matched_score <= 0:
                continue
            if self._node_kind(matched_graph_node) == "TYPE":
                for child_key in self._declared_callables(matched_key, adjacency):
                    child_score = self._flow_node_score(adjacency.nodes_by_key.get(child_key) or {}, flow_spec.target_tokens, flow_spec.target_compact)
                    scored_by_key[child_key] = max(scored_by_key.get(child_key, 0.0), matched_score + max(child_score, 1.0))
            elif self._node_kind(matched_graph_node) == "FIELD":
                for child_key in self._field_target_callables(matched_key, adjacency):
                    child_score = self._flow_node_score(adjacency.nodes_by_key.get(child_key) or {}, flow_spec.target_tokens, flow_spec.target_compact)
                    scored_by_key[child_key] = max(scored_by_key.get(child_key, 0.0), matched_score + max(child_score, 1.0))

        result = [(score, node_key) for node_key, score in scored_by_key.items() if score > 0]
        result.sort(key=lambda item: (-item[0], self._node_label(adjacency.nodes_by_key[item[1]]), item[1][2]))
        return result

    def _minimum_target_score(self, flow_spec: _FlowQuerySpec) -> float:
        if len(flow_spec.target_compact) >= 6 or len(flow_spec.target_tokens) >= 2:
            return 4.0
        return 1.0

    def _declared_callables(self, type_key: NodeKey, adjacency: _Adjacency) -> List[NodeKey]:
        result: List[NodeKey] = []
        for edge_key in adjacency.structural_outgoing.get(type_key) or []:
            edge = adjacency.edges_by_key.get(edge_key) or {}
            if self._edge_type(edge) != "DECLARES":
                continue
            target_key = self._edge_to_key(edge)
            if target_key is None:
                continue
            if self._node_kind(adjacency.nodes_by_key.get(target_key) or {}) == "CALLABLE":
                result.append(target_key)
        return result

    def _field_target_callables(self, field_key: NodeKey, adjacency: _Adjacency) -> List[NodeKey]:
        field_node = adjacency.nodes_by_key.get(field_key) or {}
        field_name = str(field_node.get("name") or field_node.get("label") or "").strip()
        result: List[NodeKey] = []
        seen: set[NodeKey] = set()
        for edge_key in adjacency.structural_incoming.get(field_key) or []:
            edge = adjacency.edges_by_key.get(edge_key) or {}
            if self._edge_type(edge) != "USES_FIELD":
                continue
            caller_key = self._edge_from_key(edge)
            if caller_key is None:
                continue
            for call_edge_key in adjacency.outgoing.get(caller_key) or []:
                call_edge = adjacency.edges_by_key.get(call_edge_key) or {}
                if not self._call_uses_field(call_edge, field_name):
                    continue
                target_key = self._edge_to_key(call_edge)
                if target_key is None or target_key in seen:
                    continue
                if self._node_kind(adjacency.nodes_by_key.get(target_key) or {}) != "CALLABLE":
                    continue
                seen.add(target_key)
                result.append(target_key)
        return result

    def _call_uses_field(self, edge: Dict[str, Any], field_name: str) -> bool:
        if not field_name:
            return True
        metadata = edge.get("metadata") if isinstance(edge.get("metadata"), dict) else {}
        receiver_text = str(metadata.get("receiverText") or "")
        receiver_compact = compact_identifier(receiver_text.rsplit(".", 1)[-1] if receiver_text else receiver_text)
        return receiver_compact == compact_identifier(field_name)

    def _shortest_call_path(
        self,
        source_key: NodeKey,
        target_key: NodeKey,
        adjacency: _Adjacency,
        state: _TraversalState,
        max_depth: int,
    ) -> Optional[_TraversalPath]:
        queue: List[tuple[NodeKey, tuple[NodeKey, ...], tuple[EdgeKey, ...], set[NodeKey]]] = [(source_key, (source_key,), (), {source_key})]
        while queue:
            current_key, node_keys, edge_keys, visited = queue.pop(0)
            if len(edge_keys) >= max_depth:
                continue
            if not state.allow_step():
                return None
            for edge_key in adjacency.outgoing.get(current_key) or []:
                edge = adjacency.edges_by_key.get(edge_key) or {}
                if self._is_external_edge(edge) or self._is_unresolved_edge(edge):
                    continue
                next_key = self._edge_to_key(edge)
                if next_key is None or next_key[0] != current_key[0] or next_key in visited:
                    continue
                next_node_keys = (*node_keys, next_key)
                next_edge_keys = (*edge_keys, edge_key)
                if next_key == target_key:
                    return _TraversalPath(
                        node_keys=next_node_keys,
                        edge_keys=next_edge_keys,
                        boundary_edge_keys=(),
                        stop_reason="TERMINAL_NODE",
                        complete=True,
                    )
                queue.append((next_key, next_node_keys, next_edge_keys, {*visited, next_key}))
        return None

    def _matched_ids_for_direct_path(self, matched_nodes: Sequence[KnowledgeQueryMatchedNode], path: _TraversalPath) -> List[str]:
        path_ids = {node_key[2] for node_key in path.node_keys}
        matched_ids = [matched_node.nodeId for matched_node in matched_nodes if matched_node.nodeId in path_ids]
        return matched_ids or [path.node_keys[0][2], path.node_keys[-1][2]]

    def _flow_node_score(self, node: Dict[str, Any], tokens: Sequence[str], compact: str) -> float:
        if not node:
            return 0.0
        text_values = [
            str(node.get("id") or ""),
            str(node.get("name") or ""),
            str(node.get("label") or ""),
            str(node.get("qualifiedName") or ""),
            str(node.get("relativePath") or ""),
        ]
        node_tokens = set(self._flow_tokens(self.normalizer.normalize(" ".join(text_values)).tokens))
        query_tokens = {token for token in tokens if token}
        overlap = len(query_tokens.intersection(node_tokens))
        node_compacts = {
            "name": compact_identifier(node.get("name") or ""),
            "label": compact_identifier(node.get("label") or ""),
            "qualified": compact_identifier(node.get("qualifiedName") or ""),
            "path": compact_identifier(node.get("relativePath") or ""),
        }
        score = float(overlap)
        compact_matched = False
        if compact:
            if node_compacts["name"] == compact or node_compacts["label"] == compact:
                score += 10.0
                compact_matched = True
            elif node_compacts["qualified"].endswith(compact):
                score += 9.0
                compact_matched = True
            elif compact in node_compacts["qualified"]:
                score += 5.0
                compact_matched = True
            elif compact in node_compacts["path"]:
                score += 2.0
                compact_matched = True
        if overlap <= 0 and not compact_matched:
            return 0.0
        for token in query_tokens:
            if token in {node_compacts["name"], node_compacts["label"]}:
                score += 2.0
        if self._node_kind(node) == "CALLABLE":
            score += 1.0
        if "test" not in query_tokens and self._is_test_node(node):
            score -= 4.0
        return max(score, 0.0)

    def _node_kind(self, node: Dict[str, Any]) -> str:
        return str(node.get("nodeKind") or node.get("node_kind") or "").upper()

    def _node_label(self, node: Dict[str, Any]) -> str:
        return str(node.get("label") or node.get("name") or node.get("qualifiedName") or node.get("id") or "")

    def _is_test_node(self, node: Dict[str, Any]) -> bool:
        text = " ".join(str(node.get(field) or "") for field in ("qualifiedName", "relativePath", "label", "name")).lower()
        return "/test/" in text or "test." in text or text.endswith("test") or "test_" in text

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
                if self._is_external_edge(edge):
                    results.append(
                        _TraversalPath(
                            node_keys=node_keys,
                            edge_keys=edge_keys,
                            boundary_edge_keys=(edge_key,),
                            stop_reason="EXTERNAL_TARGET",
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
        matched_node_ids: Optional[List[str]] = None,
    ) -> None:
        source_id = path.node_keys[0][0] if path.node_keys else matched_node.sourceId
        node_ids = [node_key[2] for node_key in path.node_keys]
        edge_ids = [edge_key[2] for edge_key in path.edge_keys]
        boundary_edge_ids = [edge_key[2] for edge_key in path.boundary_edge_keys]
        dedupe_key = (source_id, tuple(node_ids), tuple(edge_ids), tuple(boundary_edge_ids))
        existing = flow_paths_by_key.get(dedupe_key)
        if existing is not None:
            for matched_node_id in matched_node_ids or [matched_node.nodeId]:
                if matched_node_id not in existing.matchedNodeIds:
                    existing.matchedNodeIds.append(matched_node_id)
            return
        nodes = [dict(adjacency.nodes_by_key.get(node_key) or self._fallback_node(node_key)) for node_key in path.node_keys]
        edges = [dict(adjacency.edges_by_key[edge_key]) for edge_key in path.edge_keys if edge_key in adjacency.edges_by_key]
        evidence = self._evidence_for_path(adjacency.evidence, source_id, path, policy)
        flow_paths_by_key[dedupe_key] = KnowledgeQueryFlowPath(
            flowId="flow-pending",
            sourceId=source_id,
            matchedNodeIds=matched_node_ids or [matched_node.nodeId],
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
            item_edge = str(item.get("edgeId") or "")
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
        exact_key = (matched_node.sourceId, matched_node.graphId or "", matched_node.nodeId)
        if exact_key in nodes_by_key:
            return exact_key
        candidates = [
            key
            for key in nodes_by_key
            if key[0] == matched_node.sourceId and key[2] == matched_node.nodeId and (not matched_node.graphId or key[1] == matched_node.graphId)
        ]
        return sorted(candidates)[0] if candidates else None

    def _node_key(self, node: Dict[str, Any]) -> Optional[NodeKey]:
        node_id = str(node.get("id") or node.get("nodeId") or "")
        source_id = str(node.get("sourceId") or "")
        graph_id = str(node.get("graphId") or node.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return (source_id, graph_id, node_id)

    def _edge_key(self, edge: Dict[str, Any]) -> Optional[EdgeKey]:
        edge_id = str(edge.get("id") or edge.get("edgeId") or "")
        source_id = str(edge.get("sourceId") or "")
        graph_id = str(edge.get("graphId") or edge.get("graphRevision") or "")
        if not edge_id or not source_id:
            return None
        return (source_id, graph_id, edge_id)

    def _edge_from_key(self, edge: Dict[str, Any]) -> Optional[NodeKey]:
        node_id = str(edge.get("fromNodeId") or "")
        source_id = str(edge.get("sourceId") or "")
        graph_id = str(edge.get("graphId") or edge.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return (source_id, graph_id, node_id)

    def _edge_to_key(self, edge: Dict[str, Any]) -> Optional[NodeKey]:
        node_id = str(edge.get("toNodeId") or "")
        source_id = str(edge.get("sourceId") or "")
        graph_id = str(edge.get("graphId") or edge.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return (source_id, graph_id, node_id)

    def _edge_type(self, edge: Dict[str, Any]) -> str:
        return str(edge.get("edgeType") or "").upper()

    def _is_unresolved_edge(self, edge: Dict[str, Any]) -> bool:
        status = str(edge.get("resolutionStatus") or "").upper()
        if status in {"UNRESOLVED", "DYNAMIC_TARGET", "MULTIPLE_CANDIDATES", "INTERFACE_TARGET", "AMBIGUOUS"}:
            return True
        return not self._is_external_edge(edge) and self._edge_to_key(edge) is None

    def _is_external_edge(self, edge: Dict[str, Any]) -> bool:
        return bool(edge.get("external")) or str(edge.get("resolutionStatus") or "").upper() == "EXTERNAL_TARGET"

    def _fallback_node(self, node_key: NodeKey) -> Dict[str, Any]:
        return {"id": node_key[2], "sourceId": node_key[0], "graphId": node_key[1], "label": node_key[2]}

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
            query=request.query,
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
            key = str(item.get("sourceId") or "") + ":" + str(item.get("graphId") or "") + ":" + str(item.get(field) or item)
            if key in seen:
                continue
            seen.add(key)
            result.append(dict(item))
        return result

    def _query_id(self) -> str:
        return str(uuid.uuid4())


def build_knowledge_query_service(graph_store: Any, app_config: Any | None = None, embedding_provider: Any | None = None) -> KnowledgeQueryService:
    search_engine = None
    semantic_provider = _semantic_candidate_provider(graph_store, app_config, embedding_provider)
    if semantic_provider is not None:
        search_engine = DeterministicCodeSearchEngine(extra_broad_providers=[semantic_provider])
    return KnowledgeQueryService(
        source_scope_resolver=SourceScopeResolver(graph_store),
        anchor_searcher=UnifiedAnchorSearcher(graph_store, search_engine=search_engine),
        graph_slice_service=GraphSliceQueryService(graph_store),
        flow_path_extractor=FlowPathExtractor(graph_store),
        evidence_builder=EvidenceBundleBuilder(),
    )


def _semantic_candidate_provider(graph_store: Any, app_config: Any | None, embedding_provider: Any | None):
    if not hasattr(graph_store, "db_path"):
        return None
    enabled = bool(getattr(app_config, "semantic_enabled", True)) if app_config is not None else embedding_provider is not None
    if not enabled:
        return None
    try:
        from knowledge_service.embedding_provider import OllamaEmbeddingProvider
        from knowledge_service.semantic_search import SemanticCandidateProvider, SemanticSearchConfig
    except Exception:
        return None
    provider = embedding_provider
    if provider is None:
        if app_config is None:
            return None
        provider = OllamaEmbeddingProvider(
            getattr(app_config, "semantic_ollama_base_url", "http://127.0.0.1:11434"),
            getattr(app_config, "semantic_embedding_model", "embeddinggemma"),
            getattr(app_config, "semantic_request_timeout_seconds", 30),
        )
    return SemanticCandidateProvider(
        graph_store.db_path,
        provider,
        config=SemanticSearchConfig(
            enabled=enabled,
            max_search_vectors=getattr(app_config, "semantic_max_search_vectors", 50000) if app_config is not None else 50000,
            semantic_top_k=getattr(app_config, "semantic_top_k", 20) if app_config is not None else 20,
            min_similarity=getattr(app_config, "semantic_min_similarity", 0.35) if app_config is not None else 0.35,
            query_timeout_ms=getattr(app_config, "semantic_query_timeout_ms", 1500) if app_config is not None else 1500,
        ),
    )
