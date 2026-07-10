from __future__ import annotations

import time
import uuid
from collections import defaultdict
from dataclasses import dataclass
from enum import Enum
from typing import Any, Dict, List, Optional, Sequence, Tuple

from knowledge_service.knowledge_search import (
    CandidateMerger,
    DeterministicCodeSearchEngine,
    MergedCandidate,
    QueryNormalizer,
    SearchConfig,
    SearchCandidate,
    SearchDocument,
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
    max_candidates_per_provider: int = 100
    max_display_candidates: int = 20
    graph_slice_depth: int = 2
    max_traversal_nodes: int = 80
    max_flow_paths: int = 25
    max_edges_per_traversal: int = 2000
    max_expanded_anchors: int = 200
    max_anchor_expansion_per_candidate: int = 30
    max_execution_ms: int = 250
    max_evidence_refs: int = 25
    min_lexical_score: float = 0.28
    min_fuzzy_score: float = 0.58
    fuzzy_max_edit_distance: int = 3
    enable_fuzzy_search: bool = True
    enable_search_diagnostics: bool = True


class CandidatePoolKind(str, Enum):
    EXACT = "EXACT"
    PATH = "PATH"
    QUALIFIED_NAME = "QUALIFIED_NAME"
    LEXICAL = "LEXICAL"
    FUZZY = "FUZZY"
    SEMANTIC = "SEMANTIC"


@dataclass(frozen=True)
class CandidateRetrievalResult:
    pools: Dict[CandidatePoolKind, List[KnowledgeQueryMatchedNode]]
    all_candidates: List[KnowledgeQueryMatchedNode]
    display_candidates: List[KnowledgeQueryMatchedNode]
    diagnostics: List[KnowledgeQueryDiagnostic]
    truncated: bool = False


class AnchorRole(str, Enum):
    ORIGINAL_CANDIDATE = "ORIGINAL_CANDIDATE"
    FLOW_SEED = "FLOW_SEED"
    CONTEXT = "CONTEXT"
    ENTRYPOINT_CANDIDATE = "ENTRYPOINT_CANDIDATE"


class AnchorExpansionReason(str, Enum):
    ORIGINAL_MATCH = "ORIGINAL_MATCH"
    FILE_DECLARED_NODE = "FILE_DECLARED_NODE"
    TYPE_DECLARED_CALLABLE = "TYPE_DECLARED_CALLABLE"
    TYPE_DECLARED_FIELD = "TYPE_DECLARED_FIELD"
    FIELD_USED_BY_CALLABLE = "FIELD_USED_BY_CALLABLE"
    CALLABLE_PARENT_CONTEXT = "CALLABLE_PARENT_CONTEXT"
    CLAIM_ATTACHED_NODE = "CLAIM_ATTACHED_NODE"
    ENTRYPOINT_HINT = "ENTRYPOINT_HINT"


@dataclass(frozen=True)
class ExpandedAnchor:
    node: KnowledgeQueryMatchedNode
    roles: tuple[AnchorRole, ...]
    reasons: tuple[AnchorExpansionReason, ...]
    originNodeIds: tuple[str, ...]
    score: float


@dataclass(frozen=True)
class AnchorExpansionResult:
    original_candidates: List[KnowledgeQueryMatchedNode]
    expanded_anchors: List[ExpandedAnchor]
    flow_seed_nodes: List[KnowledgeQueryMatchedNode]
    context_nodes: List[KnowledgeQueryMatchedNode]
    diagnostics: List[KnowledgeQueryDiagnostic]
    truncated: bool = False


@dataclass(frozen=True)
class QuerySource:
    source_id: str
    display_name: str
    graph_id: str
    graph_revision: str
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
            graph_revision = str(source.get("graphRevision") or source.get("graph_revision") or graph_id)
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
            eligible.append(
                QuerySource(
                    source_id=source_id,
                    display_name=display_name,
                    graph_id=graph_id,
                    graph_revision=graph_revision,
                    node_count=node_count,
                    edge_count=edge_count,
                )
            )
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
        self.candidate_merger = CandidateMerger()

    def search(
        self,
        query: str,
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
    ) -> CandidateRetrievalResult:
        search_query = self.normalizer.normalize(query)
        if not search_query.tokens or not eligible_sources:
            return self._empty_result()
        raw_documents, document_truncated = self._load_search_documents(search_query.tokens, eligible_sources, policy)
        documents = [SearchDocument.from_graph_node(candidate) for candidate in raw_documents if candidate.get("sourceId") or candidate.get("source_id")]
        result = self.search_engine.search(query, documents, self._search_config(policy, eligible_sources))
        raw_candidates = list(getattr(result, "raw_candidates", []) or [])
        pools = self._candidate_pools(raw_candidates)
        all_candidates = self._all_candidates(raw_candidates)
        if not all_candidates:
            all_candidates = [self._matched_node(candidate) for candidate in result.candidates]
            pools = self._fallback_candidate_pools(result.candidates)
        display_limit = max(1, int(policy.max_display_candidates or 1))
        display_candidates = all_candidates[:display_limit]
        truncated = document_truncated or bool(getattr(result, "candidate_limit_reached", False))
        diagnostics: List[KnowledgeQueryDiagnostic] = [self._search_diagnostic(item) for item in result.diagnostics]
        if policy.enable_search_diagnostics and truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="SEARCH_CANDIDATE_LIMIT_REACHED",
                    message="Search reached an internal candidate safety limit before ranking completed.",
                    severity="INFO",
                    metadata={
                        "maxSearchDocuments": policy.max_search_documents,
                        "maxCandidatesPerProvider": policy.max_candidates_per_provider,
                    },
                )
            )
        if policy.enable_search_diagnostics and documents and not all_candidates:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="SEARCH_MATCHES_BELOW_THRESHOLD",
                    message="Search inspected current graph facts, but deterministic matches did not clear ranking thresholds.",
                    severity="INFO",
                )
            )
        if policy.enable_search_diagnostics and len(display_candidates) < len(all_candidates):
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="MATCHED_NODE_PREVIEW_LIMITED",
                    message="Matched node preview is limited for response size; graph processing used the full candidate set.",
                    severity="INFO",
                    metadata={"displayed": len(display_candidates), "internalCandidates": len(all_candidates)},
                )
            )
        return CandidateRetrievalResult(
            pools=pools,
            all_candidates=all_candidates,
            display_candidates=display_candidates,
            diagnostics=diagnostics,
            truncated=truncated,
        )

    def _empty_result(self) -> CandidateRetrievalResult:
        return CandidateRetrievalResult(
            pools={kind: [] for kind in CandidatePoolKind},
            all_candidates=[],
            display_candidates=[],
            diagnostics=[],
            truncated=False,
        )

    def _candidate_pools(self, candidates: Sequence[SearchCandidate]) -> Dict[CandidatePoolKind, List[KnowledgeQueryMatchedNode]]:
        grouped: Dict[CandidatePoolKind, List[SearchCandidate]] = defaultdict(list)
        for candidate in candidates:
            grouped[self._candidate_pool_kind(candidate.provider, candidate.reason)].append(candidate)
        pools: Dict[CandidatePoolKind, List[KnowledgeQueryMatchedNode]] = {kind: [] for kind in CandidatePoolKind}
        for kind, pool_candidates in grouped.items():
            pools[kind] = [self._matched_node(candidate) for candidate in self.candidate_merger.merge(pool_candidates)]
        return pools

    def _all_candidates(self, candidates: Sequence[SearchCandidate]) -> List[KnowledgeQueryMatchedNode]:
        return [self._matched_node(candidate) for candidate in self.candidate_merger.merge(candidates)] if candidates else []

    def _fallback_candidate_pools(self, candidates: Sequence[MergedCandidate]) -> Dict[CandidatePoolKind, List[KnowledgeQueryMatchedNode]]:
        pools: Dict[CandidatePoolKind, List[KnowledgeQueryMatchedNode]] = {kind: [] for kind in CandidatePoolKind}
        for candidate in candidates:
            matched_node = self._matched_node(candidate)
            for kind in self._candidate_pool_kinds(candidate):
                pools[kind].append(matched_node)
        return pools

    def _matched_node(self, candidate: MergedCandidate) -> KnowledgeQueryMatchedNode:
        return KnowledgeQueryMatchedNode(**candidate.document.to_matched_node_dict(candidate.score, candidate.reasons))

    def _candidate_pool_kinds(self, candidate: MergedCandidate) -> List[CandidatePoolKind]:
        kinds: set[CandidatePoolKind] = set()
        for provider in candidate.providers:
            kinds.add(self._candidate_pool_kind(provider, ""))
        for reason in candidate.reasons:
            kinds.add(self._candidate_pool_kind("", reason))
        ordered = {kind: index for index, kind in enumerate(CandidatePoolKind)}
        return sorted(kinds or {CandidatePoolKind.EXACT}, key=lambda kind: ordered[kind])

    def _candidate_pool_kind(self, provider: str, reason: str) -> CandidatePoolKind:
        provider_name = str(provider or "")
        provider_upper = provider_name.upper()
        reason_upper = str(reason or "").upper()
        if provider_upper == "SEMANTIC" or reason_upper.startswith("SEMANTIC"):
            return CandidatePoolKind.SEMANTIC
        if (
            provider_name == "PathCandidateProvider"
            or reason_upper.startswith("PATH_")
            or reason_upper
            in {
                "PATH_MATCH",
                "EXACT_PATH",
                "EXACT_FILE_NAME",
                "EXACT_FILE_STEM",
                "EXACT_FILE_COMPACT",
                "EXACT_FILE_STEM_COMPACT",
                "EXACT_ENDPOINT",
                "EXACT_DECLARING_FILE",
            }
        ):
            return CandidatePoolKind.PATH
        if provider_name == "QualifiedNameCandidateProvider" or "QUALIFIED" in reason_upper:
            return CandidatePoolKind.QUALIFIED_NAME
        if provider_name == "LexicalCandidateProvider" or reason_upper.startswith("LEXICAL"):
            return CandidatePoolKind.LEXICAL
        if provider_name == "FuzzyCandidateProvider" or reason_upper.startswith("FUZZY"):
            return CandidatePoolKind.FUZZY
        return CandidatePoolKind.EXACT

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

    def _search_config(self, policy: KnowledgeQueryPolicy, eligible_sources: Sequence[QuerySource]) -> SearchConfig:
        return SearchConfig(
            max_candidates_per_provider=policy.max_candidates_per_provider,
            min_lexical_score=policy.min_lexical_score,
            min_fuzzy_score=policy.min_fuzzy_score,
            fuzzy_max_edit_distance=policy.fuzzy_max_edit_distance,
            enable_fuzzy_search=policy.enable_fuzzy_search,
            source_revisions={
                source.source_id: source.graph_revision or source.graph_id
                for source in eligible_sources
                if source.source_id and (source.graph_revision or source.graph_id)
            },
            document_hydrator=lambda source_node_pairs: self._hydrate_search_documents(source_node_pairs, eligible_sources),
        )

    def _hydrate_search_documents(
        self,
        source_node_pairs: Sequence[Tuple[str, str]],
        eligible_sources: Sequence[QuerySource],
    ) -> List[SearchDocument]:
        if not source_node_pairs or not hasattr(self.graph_store, "query_search_documents_by_node_ids"):
            return []
        expected_revision_by_source = {
            source.source_id: source.graph_revision or source.graph_id
            for source in eligible_sources
            if source.source_id and (source.graph_revision or source.graph_id)
        }
        requested: List[Tuple[str, str]] = []
        requested_keys: set[Tuple[str, str]] = set()
        for source_id, node_id in source_node_pairs:
            key = (str(source_id or ""), str(node_id or ""))
            if not key[0] or not key[1] or key[0] not in expected_revision_by_source:
                continue
            if key in requested_keys:
                continue
            requested_keys.add(key)
            requested.append(key)
        if not requested:
            return []
        raw_documents = self.graph_store.query_search_documents_by_node_ids(requested, len(requested))
        documents: List[SearchDocument] = []
        for raw_document in raw_documents:
            document = SearchDocument.from_graph_node(raw_document)
            key = (document.source_id, document.node_id)
            if key not in requested_keys:
                continue
            expected_revision = expected_revision_by_source.get(document.source_id)
            actual_revision = document.graph_revision or document.graph_id
            if expected_revision and actual_revision and actual_revision != expected_revision:
                continue
            documents.append(document)
        return documents


AnchorNodeKey = Tuple[str, str, str]

_ANCHOR_ROLE_ORDER = {role: index for index, role in enumerate(AnchorRole)}
_ANCHOR_REASON_ORDER = {reason: index for index, reason in enumerate(AnchorExpansionReason)}


@dataclass
class _MutableExpandedAnchor:
    node: KnowledgeQueryMatchedNode
    roles: set[AnchorRole]
    reasons: set[AnchorExpansionReason]
    origin_node_ids: set[str]
    score: float
    order: int


class _AnchorAccumulator:
    def __init__(self, graph_id_by_source: Dict[str, str]) -> None:
        self.graph_id_by_source = graph_id_by_source
        self.items: Dict[AnchorNodeKey, _MutableExpandedAnchor] = {}
        self.original_keys: set[AnchorNodeKey] = set()
        self._next_order = 0

    def add_anchor(
        self,
        node: KnowledgeQueryMatchedNode,
        roles: set[AnchorRole],
        reasons: set[AnchorExpansionReason],
        origin_node_id: str,
        score: float,
        *,
        original: bool = False,
    ) -> tuple[AnchorNodeKey | None, bool]:
        key = self.node_key(node)
        if key is None:
            return None, False
        existing = self.items.get(key)
        if existing is None:
            self.items[key] = _MutableExpandedAnchor(
                node=node,
                roles=set(roles),
                reasons=set(reasons),
                origin_node_ids={origin_node_id} if origin_node_id else set(),
                score=float(score),
                order=self._next_order,
            )
            self._next_order += 1
            if original:
                self.original_keys.add(key)
            return key, True

        existing.roles.update(roles)
        existing.reasons.update(reasons)
        if origin_node_id:
            existing.origin_node_ids.add(origin_node_id)
        if original:
            self.original_keys.add(key)
            existing.node = node
        elif AnchorRole.ORIGINAL_CANDIDATE not in existing.roles and score > existing.score:
            existing.node = node
        existing.score = max(existing.score, float(score))
        return key, False

    def add_role_reason(self, key: AnchorNodeKey, role: AnchorRole, reason: AnchorExpansionReason) -> None:
        item = self.items.get(key)
        if item is None:
            return
        item.roles.add(role)
        item.reasons.add(reason)

    def has_key(self, key: AnchorNodeKey | None) -> bool:
        return key is not None and key in self.items

    def node_key(self, node: KnowledgeQueryMatchedNode) -> AnchorNodeKey | None:
        source_id = str(node.sourceId or "")
        node_id = str(node.nodeId or "")
        if not source_id or not node_id:
            return None
        return (source_id, str(node.graphId or self.graph_id_by_source.get(source_id) or ""), node_id)

    def anchors(self) -> List[ExpandedAnchor]:
        anchors: List[ExpandedAnchor] = []
        for item in sorted(self.items.values(), key=lambda value: value.order):
            anchors.append(
                ExpandedAnchor(
                    node=item.node,
                    roles=tuple(sorted(item.roles, key=lambda role: _ANCHOR_ROLE_ORDER[role])),
                    reasons=tuple(sorted(item.reasons, key=lambda reason: _ANCHOR_REASON_ORDER[reason])),
                    originNodeIds=tuple(sorted(item.origin_node_ids)),
                    score=item.score,
                )
            )
        return anchors


class AnchorExpansionService:
    def __init__(self, graph_store: Any | None = None) -> None:
        self.graph_store = graph_store

    def expand(
        self,
        candidates: Sequence[KnowledgeQueryMatchedNode],
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
    ) -> AnchorExpansionResult:
        original_candidates = list(candidates)
        graph_id_by_source = {source.source_id: source.graph_id for source in eligible_sources if source.source_id}
        revision_by_source = {source.source_id: source.graph_revision for source in eligible_sources if source.source_id}
        accumulator = _AnchorAccumulator(graph_id_by_source)
        for candidate in original_candidates:
            self._add_original_candidate(accumulator, candidate)
        if not original_candidates:
            return self._result(original_candidates, accumulator, [], truncated=False)
        if self.graph_store is None or not hasattr(self.graph_store, "query_anchor_expansion"):
            return self._result(original_candidates, accumulator, [], truncated=False, legacy_flow_seed=True)

        try:
            bundle = self.graph_store.query_anchor_expansion(
                self._source_node_pairs(original_candidates, graph_id_by_source, revision_by_source),
                max_per_anchor=max(1, int(policy.max_anchor_expansion_per_candidate or 1)),
                max_total=max(1, int(policy.max_expanded_anchors or 1)),
            )
        except Exception:
            return self._result(
                original_candidates,
                accumulator,
                [
                    KnowledgeQueryDiagnostic(
                        code="ANCHOR_EXPANSION_FAILED",
                        message="Graph anchor expansion failed; graph processing used the original search candidates.",
                        severity="WARN",
                    )
                ],
                truncated=False,
                legacy_flow_seed=True,
            )

        graph_nodes = self._bundle_nodes(bundle, graph_id_by_source)
        declares_out, declares_in, uses_field_in = self._structural_edge_indexes(bundle, graph_id_by_source)
        truncated = bool(bundle.get("truncated"))
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        added_by_origin: Dict[AnchorNodeKey, int] = defaultdict(int)
        expanded_added = 0

        def add_expanded(
            origin: KnowledgeQueryMatchedNode,
            raw_node: Dict[str, Any] | None,
            roles: set[AnchorRole],
            reason: AnchorExpansionReason,
        ) -> None:
            nonlocal expanded_added, truncated
            if not raw_node:
                return
            node = self._matched_node_from_graph_node(raw_node, origin, graph_id_by_source, revision_by_source)
            node_key = accumulator.node_key(node)
            origin_key = accumulator.node_key(origin)
            if node_key is None or origin_key is None:
                return
            exists = accumulator.has_key(node_key)
            if not exists:
                per_anchor_limit = max(1, int(policy.max_anchor_expansion_per_candidate or 1))
                total_limit = max(1, int(policy.max_expanded_anchors or 1))
                if added_by_origin[origin_key] >= per_anchor_limit or expanded_added >= total_limit:
                    truncated = True
                    return
                added_by_origin[origin_key] += 1
                expanded_added += 1
            accumulator.add_anchor(node, roles, {reason}, origin.nodeId, node.score)

        for candidate in original_candidates:
            origin_key = accumulator.node_key(candidate)
            if origin_key is None:
                continue
            kind = self._node_kind(candidate.nodeKind)
            if kind == "CALLABLE":
                for parent_key in self._parent_keys(origin_key, graph_nodes, declares_in):
                    add_expanded(candidate, graph_nodes.get(parent_key), {AnchorRole.CONTEXT}, AnchorExpansionReason.CALLABLE_PARENT_CONTEXT)
                continue
            if kind == "TYPE":
                for child_key in declares_out.get(origin_key, []):
                    child = graph_nodes.get(child_key)
                    child_kind = self._node_kind(self._raw_node_kind(child))
                    if child_kind == "CALLABLE":
                        add_expanded(candidate, child, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.TYPE_DECLARED_CALLABLE)
                    elif child_kind == "FIELD":
                        add_expanded(candidate, child, {AnchorRole.CONTEXT}, AnchorExpansionReason.TYPE_DECLARED_FIELD)
                continue
            if kind == "FILE":
                type_children: List[AnchorNodeKey] = []
                for child_key in declares_out.get(origin_key, []):
                    child = graph_nodes.get(child_key)
                    child_kind = self._node_kind(self._raw_node_kind(child))
                    if child_kind == "TYPE":
                        type_children.append(child_key)
                        add_expanded(candidate, child, {AnchorRole.CONTEXT}, AnchorExpansionReason.FILE_DECLARED_NODE)
                    elif child_kind == "CALLABLE":
                        add_expanded(candidate, child, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.FILE_DECLARED_NODE)
                    elif child_kind == "FIELD":
                        add_expanded(candidate, child, {AnchorRole.CONTEXT}, AnchorExpansionReason.FILE_DECLARED_NODE)
                for type_child_key in type_children:
                    for contained_key in declares_out.get(type_child_key, []):
                        contained = graph_nodes.get(contained_key)
                        if self._node_kind(self._raw_node_kind(contained)) == "CALLABLE":
                            add_expanded(candidate, contained, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.FILE_DECLARED_NODE)
                continue
            if kind == "FIELD":
                for callable_key in uses_field_in.get(origin_key, []):
                    callable_node = graph_nodes.get(callable_key)
                    if self._node_kind(self._raw_node_kind(callable_node)) == "CALLABLE":
                        add_expanded(candidate, callable_node, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.FIELD_USED_BY_CALLABLE)
                for parent_key in self._parent_keys(origin_key, graph_nodes, declares_in):
                    add_expanded(candidate, graph_nodes.get(parent_key), {AnchorRole.CONTEXT}, AnchorExpansionReason.TYPE_DECLARED_FIELD)

        for key in self._entrypoint_keys(bundle, graph_nodes, graph_id_by_source):
            accumulator.add_role_reason(key, AnchorRole.ENTRYPOINT_CANDIDATE, AnchorExpansionReason.ENTRYPOINT_HINT)

        if truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="ANCHOR_EXPANSION_LIMIT_REACHED",
                    message="Graph anchor expansion reached an internal safety limit.",
                    severity="INFO",
                    metadata={
                        "maxExpandedAnchors": policy.max_expanded_anchors,
                        "maxAnchorExpansionPerCandidate": policy.max_anchor_expansion_per_candidate,
                    },
                )
            )
        return self._result(original_candidates, accumulator, diagnostics, truncated=truncated)

    def _add_original_candidate(self, accumulator: _AnchorAccumulator, candidate: KnowledgeQueryMatchedNode) -> None:
        roles = {AnchorRole.ORIGINAL_CANDIDATE}
        kind = self._node_kind(candidate.nodeKind)
        if kind == "CALLABLE":
            roles.add(AnchorRole.FLOW_SEED)
        elif kind in {"FILE", "TYPE", "FIELD"}:
            roles.add(AnchorRole.CONTEXT)
        accumulator.add_anchor(
            candidate,
            roles,
            {AnchorExpansionReason.ORIGINAL_MATCH},
            candidate.nodeId,
            candidate.score,
            original=True,
        )

    def _result(
        self,
        original_candidates: List[KnowledgeQueryMatchedNode],
        accumulator: _AnchorAccumulator,
        diagnostics: List[KnowledgeQueryDiagnostic],
        *,
        truncated: bool,
        legacy_flow_seed: bool = False,
    ) -> AnchorExpansionResult:
        expanded_anchors = accumulator.anchors()
        flow_seed_nodes = [anchor.node for anchor in expanded_anchors if AnchorRole.FLOW_SEED in anchor.roles]
        if legacy_flow_seed:
            flow_seed_nodes = list(original_candidates)
        context_nodes = [anchor.node for anchor in expanded_anchors if AnchorRole.CONTEXT in anchor.roles]
        if original_candidates and not flow_seed_nodes and not legacy_flow_seed:
            diagnostics = [
                *diagnostics,
                KnowledgeQueryDiagnostic(
                    code="ANCHOR_EXPANSION_NO_FLOW_SEEDS",
                    message="Graph anchor expansion did not find callable flow seeds for the matched candidates.",
                    severity="INFO",
                ),
            ]
        return AnchorExpansionResult(
            original_candidates=original_candidates,
            expanded_anchors=expanded_anchors,
            flow_seed_nodes=flow_seed_nodes,
            context_nodes=context_nodes,
            diagnostics=diagnostics,
            truncated=truncated,
        )

    def _source_node_pairs(
        self,
        candidates: Sequence[KnowledgeQueryMatchedNode],
        graph_id_by_source: Dict[str, str],
        revision_by_source: Dict[str, str],
    ) -> List[Dict[str, str]]:
        requested: List[Dict[str, str]] = []
        seen: set[AnchorNodeKey] = set()
        for candidate in candidates:
            source_id = str(candidate.sourceId or "")
            node_id = str(candidate.nodeId or "")
            expected_graph_id = graph_id_by_source.get(source_id) or ""
            expected_revision = revision_by_source.get(source_id) or ""
            if not source_id or not node_id or source_id not in graph_id_by_source:
                continue
            if candidate.graphId and expected_graph_id and candidate.graphId != expected_graph_id:
                continue
            if candidate.graphRevision and expected_revision and candidate.graphRevision != expected_revision:
                continue
            key = (source_id, expected_graph_id, node_id)
            if key in seen:
                continue
            seen.add(key)
            requested.append({"sourceId": source_id, "graphId": expected_graph_id, "nodeId": node_id})
        return requested

    def _bundle_nodes(self, bundle: Dict[str, Any], graph_id_by_source: Dict[str, str]) -> Dict[AnchorNodeKey, Dict[str, Any]]:
        nodes: Dict[AnchorNodeKey, Dict[str, Any]] = {}
        for raw_node in bundle.get("nodes") or []:
            node = dict(raw_node)
            key = self._raw_node_key(node, graph_id_by_source)
            if key is not None:
                nodes[key] = node
        return nodes

    def _structural_edge_indexes(
        self,
        bundle: Dict[str, Any],
        graph_id_by_source: Dict[str, str],
    ) -> tuple[Dict[AnchorNodeKey, List[AnchorNodeKey]], Dict[AnchorNodeKey, List[AnchorNodeKey]], Dict[AnchorNodeKey, List[AnchorNodeKey]]]:
        declares_out: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        declares_in: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        uses_field_in: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        for edge in sorted((dict(item) for item in bundle.get("edges") or []), key=self._edge_sort_key):
            edge_type = str(edge.get("edgeType") or edge.get("edge_type") or "").upper()
            from_key = self._edge_from_key(edge, graph_id_by_source)
            to_key = self._edge_to_key(edge, graph_id_by_source)
            if from_key is None or to_key is None:
                continue
            if edge_type == "DECLARES":
                declares_out[from_key].append(to_key)
                declares_in[to_key].append(from_key)
            elif edge_type == "USES_FIELD":
                uses_field_in[to_key].append(from_key)
        return declares_out, declares_in, uses_field_in

    def _parent_keys(
        self,
        node_key: AnchorNodeKey,
        graph_nodes: Dict[AnchorNodeKey, Dict[str, Any]],
        declares_in: Dict[AnchorNodeKey, List[AnchorNodeKey]],
    ) -> List[AnchorNodeKey]:
        result: List[AnchorNodeKey] = []
        seen: set[AnchorNodeKey] = set()
        for parent_key in declares_in.get(node_key, []):
            if parent_key not in seen:
                seen.add(parent_key)
                result.append(parent_key)
        node = graph_nodes.get(node_key) or {}
        parent_node_id = str(node.get("parentNodeId") or node.get("parent_node_id") or "")
        if parent_node_id:
            parent_key = (node_key[0], node_key[1], parent_node_id)
            if parent_key not in seen:
                result.append(parent_key)
        return result

    def _entrypoint_keys(
        self,
        bundle: Dict[str, Any],
        graph_nodes: Dict[AnchorNodeKey, Dict[str, Any]],
        graph_id_by_source: Dict[str, str],
    ) -> set[AnchorNodeKey]:
        keys: set[AnchorNodeKey] = set()
        for key, node in graph_nodes.items():
            if node.get("entrypoint"):
                keys.add(key)
        for hint in bundle.get("entrypointHints") or bundle.get("entrypoint_hints") or []:
            key = self._raw_node_key(hint, graph_id_by_source)
            if key is not None:
                keys.add(key)
        return keys

    def _matched_node_from_graph_node(
        self,
        raw_node: Dict[str, Any],
        origin: KnowledgeQueryMatchedNode,
        graph_id_by_source: Dict[str, str],
        revision_by_source: Dict[str, str],
    ) -> KnowledgeQueryMatchedNode:
        source_id = str(raw_node.get("sourceId") or raw_node.get("source_id") or origin.sourceId)
        node_id = str(raw_node.get("nodeId") or raw_node.get("id") or "")
        graph_id = str(raw_node.get("graphId") or raw_node.get("graph_id") or origin.graphId or graph_id_by_source.get(source_id) or "")
        graph_revision = raw_node.get("graphRevision") or raw_node.get("graph_revision") or origin.graphRevision or revision_by_source.get(source_id)
        label = str(raw_node.get("label") or raw_node.get("displayName") or raw_node.get("display_name") or raw_node.get("name") or node_id)
        stable_key = str(raw_node.get("stableKey") or raw_node.get("stable_key") or node_id)
        return KnowledgeQueryMatchedNode(
            sourceId=source_id,
            nodeId=node_id,
            stableKey=stable_key,
            nodeKind=str(raw_node.get("nodeKind") or raw_node.get("node_kind") or ""),
            label=label,
            score=float(origin.score),
            matchReasons=list(origin.matchReasons),
            graphId=graph_id or None,
            graphRevision=str(graph_revision) if graph_revision else None,
            relativePath=raw_node.get("relativePath") or raw_node.get("relative_path") or origin.relativePath,
            qualifiedName=raw_node.get("qualifiedName") or raw_node.get("qualified_name") or origin.qualifiedName,
        )

    def _raw_node_key(self, raw_node: Dict[str, Any], graph_id_by_source: Dict[str, str]) -> AnchorNodeKey | None:
        source_id = str(raw_node.get("sourceId") or raw_node.get("source_id") or "")
        node_id = str(raw_node.get("nodeId") or raw_node.get("id") or "")
        if not source_id or not node_id:
            return None
        return (source_id, str(raw_node.get("graphId") or raw_node.get("graph_id") or graph_id_by_source.get(source_id) or ""), node_id)

    def _edge_from_key(self, edge: Dict[str, Any], graph_id_by_source: Dict[str, str]) -> AnchorNodeKey | None:
        return self._edge_node_key(edge, "fromNodeId", "from_node_id", graph_id_by_source)

    def _edge_to_key(self, edge: Dict[str, Any], graph_id_by_source: Dict[str, str]) -> AnchorNodeKey | None:
        return self._edge_node_key(edge, "toNodeId", "to_node_id", graph_id_by_source)

    def _edge_node_key(self, edge: Dict[str, Any], camel_field: str, snake_field: str, graph_id_by_source: Dict[str, str]) -> AnchorNodeKey | None:
        source_id = str(edge.get("sourceId") or edge.get("source_id") or "")
        node_id = str(edge.get(camel_field) or edge.get(snake_field) or "")
        if not source_id or not node_id:
            return None
        return (source_id, str(edge.get("graphId") or edge.get("graph_id") or graph_id_by_source.get(source_id) or ""), node_id)

    def _edge_sort_key(self, edge: Dict[str, Any]) -> tuple[str, str, str, str, str, str]:
        return (
            str(edge.get("sourceId") or edge.get("source_id") or ""),
            str(edge.get("graphId") or edge.get("graph_id") or ""),
            str(edge.get("edgeType") or edge.get("edge_type") or ""),
            str(edge.get("fromNodeId") or edge.get("from_node_id") or ""),
            str(edge.get("toNodeId") or edge.get("to_node_id") or ""),
            str(edge.get("id") or edge.get("edgeId") or ""),
        )

    def _raw_node_kind(self, raw_node: Dict[str, Any] | None) -> str:
        if not raw_node:
            return ""
        return str(raw_node.get("nodeKind") or raw_node.get("node_kind") or "")

    def _node_kind(self, value: str) -> str:
        return str(value or "").upper()

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
        anchor_expander: AnchorExpansionService | None = None,
    ) -> None:
        self.source_scope_resolver = source_scope_resolver
        self.anchor_searcher = anchor_searcher
        self.graph_slice_service = graph_slice_service
        self.flow_path_extractor = flow_path_extractor
        self.evidence_builder = evidence_builder
        self.policy = policy or KnowledgeQueryPolicy()
        self.anchor_expander = anchor_expander or AnchorExpansionService(getattr(graph_slice_service, "graph_store", None))

    def query(self, request: KnowledgeQueryRequest) -> KnowledgeQueryResponse:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        eligible_sources, scope_diagnostics = self.source_scope_resolver.resolve()
        diagnostics.extend(scope_diagnostics)
        candidate_result = self.anchor_searcher.search(request.queryText, eligible_sources, self.policy)
        diagnostics.extend(candidate_result.diagnostics)
        if not candidate_result.all_candidates:
            return KnowledgeQueryResponse(
                queryId=self._query_id(),
                status=KnowledgeQueryStatus.NO_CANDIDATES,
                intent=request.intent.value,
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

        matched_nodes = candidate_result.all_candidates
        display_matched_nodes = candidate_result.display_candidates
        anchor_result = self.anchor_expander.expand(matched_nodes, eligible_sources, self.policy)
        diagnostics.extend(anchor_result.diagnostics)
        slice_anchor_nodes = [anchor.node for anchor in anchor_result.expanded_anchors] or matched_nodes
        flow_seed_nodes = anchor_result.flow_seed_nodes
        slice_bundle, slice_diagnostics = self.graph_slice_service.build(slice_anchor_nodes, self.policy)
        diagnostics.extend(slice_diagnostics)
        flow_paths, flow_diagnostics, flow_truncated, flow_bundle = self.flow_path_extractor.extract(
            flow_seed_nodes,
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
            intent=request.intent.value,
            matchedSources=matched_sources,
            matchedNodes=display_matched_nodes,
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
                truncated=candidate_result.truncated or anchor_result.truncated or flow_truncated,
                continuationAvailable=candidate_result.truncated or anchor_result.truncated or flow_truncated,
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
        anchor_expander=AnchorExpansionService(graph_store),
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
