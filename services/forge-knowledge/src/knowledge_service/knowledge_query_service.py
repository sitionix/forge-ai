from __future__ import annotations

import uuid
from collections import defaultdict
from dataclasses import dataclass
from enum import Enum
from typing import Any, Dict, List, Optional, Sequence, Tuple

from knowledge_service.anchor_expansion_contract import (
    AnchorExpansionBundle,
    AnchorExpansionEdge,
    AnchorExpansionNode,
    AnchorExpansionRequest,
)
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
from knowledge_service.flow_builder import (
    FlowBuilder,
    FlowGraphBundle,
    FlowGraphEdge,
    FlowGraphEvidence,
    FlowGraphNode,
    FlowGraphSourceScope,
    FlowUnit,
    FlowNodeKey,
    flow_graph_bundle_to_public_bundle,
)

@dataclass(frozen=True)
class KnowledgeQueryPolicy:
    max_search_documents: int = 5000
    max_candidates_per_provider: int = 100
    max_display_candidates: int = 20
    graph_slice_depth: int = 2
    max_traversal_nodes: int = 80
    max_flow_paths: int = 25
    max_flow_upstream_depth: int = 8
    max_flow_downstream_depth: int = 12
    max_flow_branching_per_node: int = 8
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


@dataclass(frozen=True)
class KnowledgeQueryExecutionResult:
    response: KnowledgeQueryResponse
    flow_units: tuple[FlowUnit, ...] = ()


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

        graph_nodes = self._bundle_nodes(bundle)
        declares_out, declares_in, uses_field_in = self._structural_edge_indexes(bundle)
        truncated = bool(bundle.truncated)
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        added_by_origin: Dict[AnchorNodeKey, int] = defaultdict(int)
        expanded_added = 0

        def add_expanded(
            origin: KnowledgeQueryMatchedNode,
            raw_node: AnchorExpansionNode | None,
            roles: set[AnchorRole],
            reason: AnchorExpansionReason,
        ) -> None:
            nonlocal expanded_added, truncated
            if not raw_node:
                return
            node = self._matched_node_from_graph_node(raw_node, origin)
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
                    child_kind = self._node_kind(child.node_kind if child else "")
                    if child_kind == "CALLABLE":
                        add_expanded(candidate, child, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.TYPE_DECLARED_CALLABLE)
                    elif child_kind == "FIELD":
                        add_expanded(candidate, child, {AnchorRole.CONTEXT}, AnchorExpansionReason.TYPE_DECLARED_FIELD)
                continue
            if kind == "FILE":
                type_children: List[AnchorNodeKey] = []
                for child_key in declares_out.get(origin_key, []):
                    child = graph_nodes.get(child_key)
                    child_kind = self._node_kind(child.node_kind if child else "")
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
                        if self._node_kind(contained.node_kind if contained else "") == "CALLABLE":
                            add_expanded(candidate, contained, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.FILE_DECLARED_NODE)
                continue
            if kind == "FIELD":
                for callable_key in uses_field_in.get(origin_key, []):
                    callable_node = graph_nodes.get(callable_key)
                    if self._node_kind(callable_node.node_kind if callable_node else "") == "CALLABLE":
                        add_expanded(candidate, callable_node, {AnchorRole.FLOW_SEED}, AnchorExpansionReason.FIELD_USED_BY_CALLABLE)
                for parent_key in self._parent_keys(origin_key, graph_nodes, declares_in):
                    add_expanded(candidate, graph_nodes.get(parent_key), {AnchorRole.CONTEXT}, AnchorExpansionReason.TYPE_DECLARED_FIELD)

        for key in self._entrypoint_keys(bundle, graph_nodes):
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
    ) -> List[AnchorExpansionRequest]:
        requested: List[AnchorExpansionRequest] = []
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
            requested.append(
                AnchorExpansionRequest(
                    source_id=source_id,
                    graph_id=expected_graph_id,
                    graph_revision=expected_revision or None,
                    node_id=node_id,
                )
            )
        return requested

    def _bundle_nodes(self, bundle: AnchorExpansionBundle) -> Dict[AnchorNodeKey, AnchorExpansionNode]:
        nodes: Dict[AnchorNodeKey, AnchorExpansionNode] = {}
        for node in bundle.nodes:
            key = self._node_key(node)
            if key is not None:
                nodes[key] = node
        return nodes

    def _structural_edge_indexes(
        self,
        bundle: AnchorExpansionBundle,
    ) -> tuple[Dict[AnchorNodeKey, List[AnchorNodeKey]], Dict[AnchorNodeKey, List[AnchorNodeKey]], Dict[AnchorNodeKey, List[AnchorNodeKey]]]:
        declares_out: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        declares_in: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        uses_field_in: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        for edge in sorted(bundle.edges, key=self._edge_sort_key):
            edge_type = str(edge.edge_type or "").upper()
            from_key = self._edge_from_key(edge)
            to_key = self._edge_to_key(edge)
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
        graph_nodes: Dict[AnchorNodeKey, AnchorExpansionNode],
        declares_in: Dict[AnchorNodeKey, List[AnchorNodeKey]],
    ) -> List[AnchorNodeKey]:
        result: List[AnchorNodeKey] = []
        seen: set[AnchorNodeKey] = set()
        for parent_key in declares_in.get(node_key, []):
            if parent_key not in seen:
                seen.add(parent_key)
                result.append(parent_key)
        node = graph_nodes.get(node_key)
        if node and node.parent_node_id:
            parent_node_id = str(node.parent_node_id)
            parent_key = (node_key[0], node_key[1], parent_node_id)
            if parent_key not in seen:
                result.append(parent_key)
        return result

    def _entrypoint_keys(
        self,
        bundle: AnchorExpansionBundle,
        graph_nodes: Dict[AnchorNodeKey, AnchorExpansionNode],
    ) -> set[AnchorNodeKey]:
        keys: set[AnchorNodeKey] = set()
        for key, node in graph_nodes.items():
            if node.entrypoint:
                keys.add(key)
        for hint in bundle.entrypoint_hints:
            if hint.source_id and hint.graph_id and hint.node_id:
                key = (str(hint.source_id), str(hint.graph_id), str(hint.node_id))
                keys.add(key)
        return keys

    def _matched_node_from_graph_node(
        self,
        node: AnchorExpansionNode,
        origin: KnowledgeQueryMatchedNode,
    ) -> KnowledgeQueryMatchedNode:
        label = str(node.label or node.node_id)
        return KnowledgeQueryMatchedNode(
            sourceId=str(node.source_id),
            nodeId=str(node.node_id),
            stableKey=str(node.stable_key or node.node_id),
            nodeKind=str(node.node_kind or ""),
            label=label,
            score=float(origin.score),
            matchReasons=list(origin.matchReasons),
            graphId=str(node.graph_id) if node.graph_id else None,
            graphRevision=str(node.graph_revision) if node.graph_revision else None,
            relativePath=node.relative_path or origin.relativePath,
            qualifiedName=node.qualified_name or origin.qualifiedName,
        )

    def _node_key(self, node: AnchorExpansionNode) -> AnchorNodeKey | None:
        source_id = str(node.source_id or "")
        graph_id = str(node.graph_id or "")
        node_id = str(node.node_id or "")
        if not source_id or not graph_id or not node_id:
            return None
        return (source_id, graph_id, node_id)

    def _edge_from_key(self, edge: AnchorExpansionEdge) -> AnchorNodeKey | None:
        return self._edge_node_key(edge, edge.from_node_id)

    def _edge_to_key(self, edge: AnchorExpansionEdge) -> AnchorNodeKey | None:
        return self._edge_node_key(edge, edge.to_node_id)

    def _edge_node_key(self, edge: AnchorExpansionEdge, node_id_value: str) -> AnchorNodeKey | None:
        source_id = str(edge.source_id or "")
        graph_id = str(edge.graph_id or "")
        node_id = str(node_id_value or "")
        if not source_id or not graph_id or not node_id:
            return None
        return (source_id, graph_id, node_id)

    def _edge_sort_key(self, edge: AnchorExpansionEdge) -> tuple[str, str, str, str, str, str]:
        return (
            str(edge.source_id or ""),
            str(edge.graph_id or ""),
            str(edge.edge_type or ""),
            str(edge.from_node_id or ""),
            str(edge.to_node_id or ""),
            str(edge.edge_id or ""),
        )

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


class FlowPathExtractor:
    def __init__(self, graph_store: Any | None = None) -> None:
        self.graph_store = graph_store
        self.flow_builder = FlowBuilder()

    def extract(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
        entrypoint_candidate_node_ids: set[FlowNodeKey] | None = None,
    ) -> tuple[List[KnowledgeQueryFlowPath], List[KnowledgeQueryDiagnostic], bool, Dict[str, Any], tuple[FlowUnit, ...]]:
        graph_bundle = self._load_flow_graph(matched_nodes, slice_bundle, evidence, policy)
        result = self.flow_builder.build(graph_bundle, matched_nodes, entrypoint_candidate_node_ids or set(), policy)
        return (
            result.flow_paths,
            result.diagnostics,
            result.truncated,
            flow_graph_bundle_to_public_bundle(graph_bundle),
            result.flow_units,
        )

    def _load_flow_graph(
        self,
        matched_nodes: Sequence[KnowledgeQueryMatchedNode],
        slice_bundle: Dict[str, List[Dict[str, Any]]],
        evidence: Sequence[Dict[str, Any]],
        policy: KnowledgeQueryPolicy,
    ) -> FlowGraphBundle:
        if not matched_nodes:
            return FlowGraphBundle()
        source_scopes = self._source_scopes(matched_nodes)
        if self.graph_store is not None and hasattr(self.graph_store, "load_call_flow_graph"):
            return self.graph_store.load_call_flow_graph(
                source_scopes,
                max_edges=policy.max_edges_per_traversal,
                max_evidence=policy.max_evidence_refs,
            )
        if self.graph_store is not None and hasattr(self.graph_store, "load_call_adjacency_for_sources"):
            legacy_bundle = self.graph_store.load_call_adjacency_for_sources(
                self._legacy_source_scopes(source_scopes),
                max_edges=policy.max_edges_per_traversal,
                max_evidence=policy.max_evidence_refs,
            )
            return self._typed_bundle_from_public_graph(legacy_bundle)
        return self._typed_bundle_from_public_graph(
            {
                "nodes": list(slice_bundle.get("nodes") or []),
                "edges": [edge for edge in slice_bundle.get("edges") or [] if str(edge.get("edgeType") or "").upper() == "CALLS"],
                "evidence": list(evidence),
                "truncated": False,
            }
        )

    def _source_scopes(self, matched_nodes: Sequence[KnowledgeQueryMatchedNode]) -> List[FlowGraphSourceScope]:
        grouped: Dict[tuple[str, str, str | None], set[str]] = {}
        for matched_node in matched_nodes:
            source_id = matched_node.sourceId
            node_id = matched_node.nodeId
            if not source_id or not node_id:
                continue
            key = (source_id, matched_node.graphId or "", matched_node.graphRevision)
            grouped.setdefault(key, set()).add(node_id)
        return [
            FlowGraphSourceScope(
                source_id=source_id,
                graph_id=graph_id,
                graph_revision=graph_revision,
                node_ids=tuple(sorted(node_ids)),
            )
            for (source_id, graph_id, graph_revision), node_ids in sorted(grouped.items())
        ]

    def _legacy_source_scopes(self, source_scopes: Sequence[FlowGraphSourceScope]) -> List[Dict[str, Any]]:
        return [
            {
                "sourceId": scope.source_id,
                "graphId": scope.graph_id,
                "graphRevision": scope.graph_revision,
                "nodeIds": list(scope.node_ids),
            }
            for scope in source_scopes
        ]

    def _typed_bundle_from_public_graph(self, bundle: Dict[str, Any]) -> FlowGraphBundle:
        nodes = tuple(
            node
            for node in (self._typed_node(item) for item in bundle.get("nodes") or [])
            if node is not None
        )
        edges = tuple(
            edge
            for edge in (self._typed_edge(item) for item in bundle.get("edges") or [])
            if edge is not None
        )
        evidence = tuple(
            item
            for item in (self._typed_evidence(item) for item in bundle.get("evidence") or [])
            if item is not None
        )
        evidence_ids_by_edge: Dict[str, List[str]] = defaultdict(list)
        for item in evidence:
            if item.edge_id:
                evidence_ids_by_edge[item.edge_id].append(item.evidence_id)
        if evidence_ids_by_edge:
            edges = tuple(
                FlowGraphEdge(
                    source_id=edge.source_id,
                    graph_id=edge.graph_id,
                    graph_revision=edge.graph_revision,
                    edge_id=edge.edge_id,
                    edge_type=edge.edge_type,
                    from_node_id=edge.from_node_id,
                    to_node_id=edge.to_node_id,
                    resolution_status=edge.resolution_status,
                    external=edge.external,
                    unresolved_target=edge.unresolved_target,
                    evidence_ids=tuple(evidence_ids_by_edge.get(edge.edge_id) or edge.evidence_ids),
                )
                for edge in edges
            )
        return FlowGraphBundle(nodes=nodes, edges=edges, evidence=evidence, truncated=bool(bundle.get("truncated")))

    def _typed_node(self, item: Dict[str, Any]) -> FlowGraphNode | None:
        node_id = str(item.get("id") or item.get("nodeId") or "")
        source_id = str(item.get("sourceId") or "")
        graph_id = str(item.get("graphId") or item.get("graphRevision") or "")
        if not node_id or not source_id:
            return None
        return FlowGraphNode(
            source_id=source_id,
            graph_id=graph_id,
            graph_revision=str(item.get("graphRevision")) if item.get("graphRevision") else None,
            node_id=node_id,
            stable_key=str(item.get("stableKey") or node_id),
            node_kind=str(item.get("nodeKind") or ""),
            label=str(item.get("label") or item.get("name") or node_id),
            qualified_name=str(item.get("qualifiedName")) if item.get("qualifiedName") else None,
            relative_path=str(item.get("relativePath")) if item.get("relativePath") else None,
            summary=str(item.get("summary")) if item.get("summary") else None,
            entrypoint=bool(item.get("entrypoint")),
        )

    def _typed_edge(self, item: Dict[str, Any]) -> FlowGraphEdge | None:
        edge_id = str(item.get("id") or item.get("edgeId") or "")
        source_id = str(item.get("sourceId") or "")
        graph_id = str(item.get("graphId") or item.get("graphRevision") or "")
        from_node_id = str(item.get("fromNodeId") or "")
        if not edge_id or not source_id or not from_node_id:
            return None
        return FlowGraphEdge(
            source_id=source_id,
            graph_id=graph_id,
            graph_revision=str(item.get("graphRevision")) if item.get("graphRevision") else None,
            edge_id=edge_id,
            edge_type=str(item.get("edgeType") or ""),
            from_node_id=from_node_id,
            to_node_id=str(item.get("toNodeId")) if item.get("toNodeId") else None,
            resolution_status=str(item.get("resolutionStatus") or "RESOLVED"),
            external=bool(item.get("external")) or str(item.get("resolutionStatus") or "").upper() == "EXTERNAL_TARGET",
            unresolved_target=item.get("unresolvedTarget") if isinstance(item.get("unresolvedTarget"), dict) else None,
        )

    def _typed_evidence(self, item: Dict[str, Any]) -> FlowGraphEvidence | None:
        evidence_id = str(item.get("id") or "")
        source_id = str(item.get("sourceId") or "")
        graph_id = str(item.get("graphId") or item.get("graphRevision") or "")
        if not evidence_id or not source_id:
            return None
        return FlowGraphEvidence(
            source_id=source_id,
            graph_id=graph_id,
            graph_revision=str(item.get("graphRevision")) if item.get("graphRevision") else None,
            evidence_id=evidence_id,
            node_id=str(item.get("nodeId")) if item.get("nodeId") else None,
            edge_id=str(item.get("edgeId")) if item.get("edgeId") else None,
            relative_path=str(item.get("relativePath")) if item.get("relativePath") else None,
            line_start=int(item.get("lineStart")) if item.get("lineStart") is not None else None,
            line_end=int(item.get("lineEnd")) if item.get("lineEnd") is not None else None,
            text=str(item.get("excerpt")) if item.get("excerpt") else None,
        )


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
        return self.query_with_flow_units(request).response

    def query_with_flow_units(self, request: KnowledgeQueryRequest) -> KnowledgeQueryExecutionResult:
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        eligible_sources, scope_diagnostics = self.source_scope_resolver.resolve()
        diagnostics.extend(scope_diagnostics)
        candidate_result = self.anchor_searcher.search(request.queryText, eligible_sources, self.policy)
        diagnostics.extend(candidate_result.diagnostics)
        if not candidate_result.all_candidates:
            return KnowledgeQueryExecutionResult(
                response=KnowledgeQueryResponse(
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
            )

        matched_nodes = candidate_result.all_candidates
        display_matched_nodes = candidate_result.display_candidates
        anchor_result = self.anchor_expander.expand(matched_nodes, eligible_sources, self.policy)
        diagnostics.extend(anchor_result.diagnostics)
        slice_anchor_nodes = [anchor.node for anchor in anchor_result.expanded_anchors] or matched_nodes
        flow_seed_nodes = anchor_result.flow_seed_nodes
        entrypoint_candidate_node_ids = self._entrypoint_candidate_node_ids(anchor_result.expanded_anchors)
        slice_bundle, slice_diagnostics = self.graph_slice_service.build(slice_anchor_nodes, self.policy)
        diagnostics.extend(slice_diagnostics)
        flow_paths, flow_diagnostics, flow_truncated, flow_bundle, flow_units = self.flow_path_extractor.extract(
            flow_seed_nodes,
            slice_bundle,
            slice_bundle.get("evidence") or [],
            self.policy,
            entrypoint_candidate_node_ids,
        )
        request_flow_limit = max(1, min(int(request.maxFlows or 10), int(self.policy.max_flow_paths or 25)))
        available_flow_count = len(flow_units)
        request_flow_truncated = available_flow_count > request_flow_limit
        flow_paths = flow_paths[:request_flow_limit]
        flow_units = flow_units[:request_flow_limit]
        diagnostics.extend(flow_diagnostics)
        if request_flow_truncated:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="FLOW_QUERY_MAX_FLOWS_REACHED",
                    message="Flow response was truncated by the request maxFlows limit.",
                    severity="INFO",
                    metadata={
                        "returnedFlowCount": len(flow_units),
                        "availableFlowCount": available_flow_count,
                        "maxFlows": request_flow_limit,
                    },
                )
            )
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
        return KnowledgeQueryExecutionResult(
            response=KnowledgeQueryResponse(
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
                    truncated=candidate_result.truncated or anchor_result.truncated or flow_truncated or request_flow_truncated,
                    continuationAvailable=candidate_result.truncated or anchor_result.truncated or flow_truncated or request_flow_truncated,
                ),
                diagnostics=diagnostics,
            ),
            flow_units=flow_units,
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

    def _entrypoint_candidate_node_ids(self, expanded_anchors: Sequence[ExpandedAnchor]) -> set[FlowNodeKey]:
        return {
            (anchor.node.sourceId, anchor.node.graphId or "", anchor.node.nodeId)
            for anchor in expanded_anchors
            if AnchorRole.ENTRYPOINT_CANDIDATE in anchor.roles and anchor.node.sourceId and anchor.node.nodeId
        }

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
