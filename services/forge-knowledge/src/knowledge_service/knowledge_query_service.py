from __future__ import annotations

import time
import uuid
from collections import defaultdict
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any, Dict, List, Mapping, Sequence, Tuple

from knowledge_service.anchor_expansion_contract import (
    AnchorExpansionBundle,
    AnchorExpansionEdge,
    AnchorExpansionNode,
    AnchorExpansionRequest,
)
from knowledge_service.entrypoint_flow_engine import EntrypointFlow, EntrypointFlowEngine
from knowledge_service.entrypoint_flow_store import EntrypointFlowGraphRepository
from knowledge_service.flow_family import FlowFamily, FlowFamilyAssembler, FlowFamilyAssemblyResult
from knowledge_service.flow_narrative import FlowNarrativePlan, FlowNarrativePlanner, replace_plan_fragments
from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryCoverage,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryMatchedNode,
    KnowledgeQueryMatchedNodePreview,
    KnowledgeQueryMatchedSource,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryStatus,
)
from knowledge_service.knowledge_search import (
    CandidateMerger,
    DeterministicCodeSearchEngine,
    MergedCandidate,
    QueryNormalizer,
    SearchCandidate,
    SearchConfig,
    SearchDocument,
)
from knowledge_service.operation_facts import (
    AvailableOperationFact,
    clean_identity,
    merge_semantic_operation_facts,
    normalize_http_method,
    normalize_route,
    normalize_transport_kind,
)
from knowledge_service.query_interpretation import QueryRetrievalPlan


@dataclass(frozen=True)
class KnowledgeQueryPolicy:
    max_search_documents: int = 5000
    max_candidates_per_provider: int = 100
    max_display_candidates: int = 20
    min_lexical_score: float = 0.28
    min_fuzzy_score: float = 0.58
    fuzzy_max_edit_distance: int = 3
    enable_fuzzy_search: bool = True
    enable_search_diagnostics: bool = True
    plan_candidate_min_score: float = 0.42
    plan_candidate_top_delta: float = 0.18
    exact_identifier_min_score: float = 0.75
    exact_identifier_top_delta: float = 0.12
    plan_flow_min_relevance_score: float = 0.05
    plan_flow_top_delta: float = 0.25


class CandidatePoolKind(str, Enum):
    EXACT = "EXACT"
    PATH = "PATH"
    QUALIFIED_NAME = "QUALIFIED_NAME"
    LEXICAL = "LEXICAL"
    FUZZY = "FUZZY"
    SEMANTIC = "SEMANTIC"


_PRECISE_IDENTIFIER_REASONS = {
    "QUALIFIED_NAME_EXACT",
    "QUALIFIED_NAME_SUFFIX",
    "QUALIFIED_NAME_LEAF",
    "QUALIFIED_SEGMENT_SUFFIX",
    "QUALIFIED_NAME_MATCH",
    "NAME_MATCH",
    "STABLE_KEY_MATCH",
    "PATH_MATCH",
}


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
    CALLABLE_OVERRIDE_IMPLEMENTATION = "CALLABLE_OVERRIDE_IMPLEMENTATION"
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
    graph_status: str | None = None


@dataclass(frozen=True)
class KnowledgeQueryExecutionResult:
    response: KnowledgeQueryResponse
    flows: tuple[FlowFamily, ...] = ()
    narrative_plans: tuple[FlowNarrativePlan, ...] = ()
    raw_flows: tuple[EntrypointFlow, ...] = ()
    family_assembly: FlowFamilyAssemblyResult | None = None


@dataclass(frozen=True)
class ContinuationAssemblyResult:
    families: tuple[FlowFamily, ...]
    operation_facts: tuple[AvailableOperationFact, ...]
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...]


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
            graph_status = str(source.get("graphStatus") or "").strip() or None
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
                    graph_status=graph_status,
                )
            )
            if graph_status and graph_status != "READY":
                diagnostics.append(
                    KnowledgeQueryDiagnostic(
                        code="PARTIAL_SOURCE_GRAPH_STATE",
                        message="Source graph state is not READY, but current eligible facts remain queryable.",
                        severity="INFO",
                        sourceId=source_id,
                        metadata={"graphStatus": graph_status},
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
        include_tests: bool = False,
    ) -> CandidateRetrievalResult:
        search_query = self.normalizer.normalize(query)
        if not search_query.tokens or not eligible_sources:
            return self._empty_result()
        raw_documents, document_truncated = self._load_search_documents(search_query.tokens, eligible_sources, policy)
        if not include_tests:
            raw_documents = [item for item in raw_documents if str(item.get("flowDomain") or "").upper() != "TEST"]
        documents = [SearchDocument.from_graph_node(candidate) for candidate in raw_documents if candidate.get("sourceId") or candidate.get("source_id")]
        result = self.search_engine.search(query, documents, self._search_config(policy, eligible_sources, include_tests))
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

    def search_plan(
        self,
        plan: QueryRetrievalPlan,
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
        include_tests: bool = False,
    ) -> CandidateRetrievalResult:
        query_inputs = plan.query_inputs()
        combined_tokens = self.normalizer.normalize(" ".join(value for _, value in query_inputs)).tokens
        if not combined_tokens or not eligible_sources:
            return self._empty_result()
        raw_documents, document_truncated = self._load_search_documents(combined_tokens, eligible_sources, policy)
        if not include_tests:
            raw_documents = [item for item in raw_documents if str(item.get("flowDomain") or "").upper() != "TEST"]
        documents = [SearchDocument.from_graph_node(candidate) for candidate in raw_documents if candidate.get("sourceId") or candidate.get("source_id")]
        config = self._search_config(policy, eligible_sources, include_tests)
        raw_candidates: List[SearchCandidate] = []
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        candidate_limit_reached = False
        for query_reason, query_value in query_inputs:
            result = self.search_engine.search(query_value, documents, config)
            candidate_limit_reached = candidate_limit_reached or bool(getattr(result, "candidate_limit_reached", False))
            for item in result.diagnostics:
                metadata = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
                diagnostics.append(self._search_diagnostic({**item, "metadata": {**metadata, "queryReason": query_reason}}))
            raw_candidates.extend(self._annotated_candidates(getattr(result, "raw_candidates", []) or [], query_reason))

        pools = self._candidate_pools(raw_candidates)
        merged_candidates = self.candidate_merger.merge(raw_candidates) if raw_candidates else []
        merged_candidates = self._filter_plan_candidates(merged_candidates, plan, policy)
        all_candidates = [self._matched_node(candidate) for candidate in merged_candidates]
        display_limit = max(1, int(policy.max_display_candidates or 1))
        display_candidates = all_candidates[:display_limit]
        truncated = document_truncated or candidate_limit_reached
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

    def _filter_plan_candidates(
        self,
        candidates: Sequence[MergedCandidate],
        plan: QueryRetrievalPlan,
        policy: KnowledgeQueryPolicy,
    ) -> List[MergedCandidate]:
        ranked = list(candidates)
        if not ranked:
            return []
        ranked = [
            candidate
            for candidate in ranked
            if not self._is_expansion_only_candidate(candidate)
        ]
        if not ranked:
            return []
        if plan.code_identifiers:
            exact_identifier = [
                candidate
                for candidate in ranked
                if self._is_precise_identifier_candidate(candidate)
            ]
            if exact_identifier:
                callable_exact = [candidate for candidate in exact_identifier if self._node_kind(candidate.document.node_kind) == "CALLABLE"]
                if callable_exact:
                    exact_identifier = callable_exact
                top_exact = max(candidate.score for candidate in exact_identifier)
                threshold = max(policy.exact_identifier_min_score, top_exact - policy.exact_identifier_top_delta)
                return [candidate for candidate in exact_identifier if candidate.score >= threshold] or exact_identifier

        callable_ranked = [candidate for candidate in ranked if self._node_kind(candidate.document.node_kind) == "CALLABLE"]
        if callable_ranked:
            top_callable_score = max(candidate.score for candidate in callable_ranked)
            ranked = [
                candidate
                for candidate in ranked
                if self._node_kind(candidate.document.node_kind) == "CALLABLE"
                or candidate.score > top_callable_score
            ]
        top_score = max(candidate.score for candidate in ranked)
        threshold = max(policy.plan_candidate_min_score, top_score - policy.plan_candidate_top_delta)
        return [
            candidate
            for candidate in ranked
            if candidate.score >= threshold
        ]

    def _is_expansion_only_candidate(self, candidate: MergedCandidate) -> bool:
        reasons = set(candidate.reasons)
        if "QUERY_EXPANSION" not in reasons:
            return False
        return not reasons.intersection({"QUERY_ORIGINAL", "QUERY_NORMALIZED", "QUERY_EXACT_IDENTIFIER"})

    def _is_precise_identifier_candidate(self, candidate: MergedCandidate) -> bool:
        reasons = set(candidate.reasons)
        if "QUERY_EXACT_IDENTIFIER" not in reasons:
            return False
        return any(
            (reason.startswith("EXACT") and reason != "EXACT_KIND")
            or reason in _PRECISE_IDENTIFIER_REASONS
            for reason in reasons
        )

    def _node_kind(self, value: str) -> str:
        return str(value or "").upper()

    def _annotated_candidates(self, candidates: Sequence[SearchCandidate], query_reason: str) -> List[SearchCandidate]:
        result: List[SearchCandidate] = []
        marker_reason = {
            "ORIGINAL_QUERY": "QUERY_ORIGINAL",
            "NORMALIZED_QUERY": "QUERY_NORMALIZED",
            "SEARCH_QUERY": "QUERY_EXPANSION",
            "CODE_IDENTIFIER": "QUERY_EXACT_IDENTIFIER",
        }.get(query_reason, "QUERY_EXPANSION")
        score_bonus = {
            "ORIGINAL_QUERY": 0.015,
            "NORMALIZED_QUERY": 0.0,
            "SEARCH_QUERY": 0.0,
            "CODE_IDENTIFIER": 0.045,
        }.get(query_reason, 0.0)
        priority_bonus = -2 if query_reason == "CODE_IDENTIFIER" else 0
        for candidate in candidates:
            adjusted_score = min(1.0, float(candidate.score) + score_bonus)
            adjusted_priority = max(1, int(candidate.priority) + priority_bonus)
            adjusted = replace(candidate, score=adjusted_score, priority=adjusted_priority)
            result.append(adjusted)
            result.append(
                SearchCandidate(
                    document=candidate.document,
                    provider="QueryPlanCandidateProvider",
                    reason=marker_reason,
                    score=adjusted_score,
                    confidence=candidate.confidence,
                    priority=adjusted_priority,
                )
            )
        return result

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
        total_limit = max(1, int(policy.max_search_documents or 1))
        if hasattr(self.graph_store, "query_search_documents"):
            raw_documents = list(self.graph_store.query_search_documents(source_ids, total_limit + 1))
            truncated = len(raw_documents) > total_limit
            if truncated:
                raw_documents = raw_documents[:total_limit]
        else:
            raw_documents = list(self.graph_store.query_anchor_candidates(list(tokens), source_ids, total_limit + 1))
            truncated = len(raw_documents) > total_limit
            if truncated:
                raw_documents = raw_documents[:total_limit]
        return raw_documents, truncated

    def _search_config(self, policy: KnowledgeQueryPolicy, eligible_sources: Sequence[QuerySource], include_tests: bool) -> SearchConfig:
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
            document_hydrator=lambda source_node_pairs: self._hydrate_search_documents(source_node_pairs, eligible_sources, include_tests),
        )

    def _hydrate_search_documents(
        self,
        source_node_pairs: Sequence[Tuple[str, str]],
        eligible_sources: Sequence[QuerySource],
        include_tests: bool,
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
            if not include_tests and document.flow_domain.upper() == "TEST":
                continue
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

    def discard_role(self, key: AnchorNodeKey, role: AnchorRole) -> None:
        item = self.items.get(key)
        if item is None:
            return
        item.roles.discard(role)

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
        declares_out, declares_in, uses_field_in, overrides_by_contract = self._structural_edge_indexes(bundle)
        truncated = bool(bundle.truncated)
        diagnostics: List[KnowledgeQueryDiagnostic] = []

        def add_expanded(
            origin: KnowledgeQueryMatchedNode,
            raw_node: AnchorExpansionNode | None,
            roles: set[AnchorRole],
            reason: AnchorExpansionReason,
        ) -> None:
            if not raw_node:
                return
            node = self._matched_node_from_graph_node(raw_node, origin)
            node_key = accumulator.node_key(node)
            origin_key = accumulator.node_key(origin)
            if node_key is None or origin_key is None:
                return
            accumulator.add_anchor(node, roles, {reason}, origin.nodeId, node.score)

        for candidate in original_candidates:
            origin_key = accumulator.node_key(candidate)
            if origin_key is None:
                continue
            kind = self._node_kind(candidate.nodeKind)
            if kind == "CALLABLE":
                candidate_graph_node = graph_nodes.get(origin_key)
                if candidate_graph_node and candidate_graph_node.entrypoint_contract:
                    accumulator.discard_role(origin_key, AnchorRole.FLOW_SEED)
                    accumulator.add_role_reason(origin_key, AnchorRole.CONTEXT, AnchorExpansionReason.CALLABLE_PARENT_CONTEXT)
                for parent_key in self._parent_keys(origin_key, graph_nodes, declares_in):
                    add_expanded(candidate, graph_nodes.get(parent_key), {AnchorRole.CONTEXT}, AnchorExpansionReason.CALLABLE_PARENT_CONTEXT)
                for implementation_key in overrides_by_contract.get(origin_key, []):
                    add_expanded(
                        candidate,
                        graph_nodes.get(implementation_key),
                        {AnchorRole.FLOW_SEED, AnchorRole.ENTRYPOINT_CANDIDATE},
                        AnchorExpansionReason.CALLABLE_OVERRIDE_IMPLEMENTATION,
                    )
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
    ) -> tuple[
        Dict[AnchorNodeKey, List[AnchorNodeKey]],
        Dict[AnchorNodeKey, List[AnchorNodeKey]],
        Dict[AnchorNodeKey, List[AnchorNodeKey]],
        Dict[AnchorNodeKey, List[AnchorNodeKey]],
    ]:
        declares_out: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        declares_in: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        uses_field_in: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
        overrides_by_contract: Dict[AnchorNodeKey, List[AnchorNodeKey]] = defaultdict(list)
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
            elif edge_type == "OVERRIDES":
                overrides_by_contract[to_key].append(from_key)
        return declares_out, declares_in, uses_field_in, overrides_by_contract

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
        return self._edge_node_key(edge, edge.to_node_id, source_id=edge.to_source_id, graph_id=edge.to_graph_revision or edge.to_graph_id)

    def _edge_node_key(
        self,
        edge: AnchorExpansionEdge,
        node_id_value: str,
        *,
        source_id: str | None = None,
        graph_id: str | None = None,
    ) -> AnchorNodeKey | None:
        source_id = str(source_id or edge.source_id or "")
        graph_id = str(graph_id or edge.graph_id or "")
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

class KnowledgeQueryService:
    def __init__(
        self,
        source_scope_resolver: SourceScopeResolver,
        anchor_searcher: UnifiedAnchorSearcher,
        flow_repository: EntrypointFlowGraphRepository,
        flow_engine: EntrypointFlowEngine | None = None,
        policy: KnowledgeQueryPolicy | None = None,
        anchor_expander: AnchorExpansionService | None = None,
    ) -> None:
        self.source_scope_resolver = source_scope_resolver
        self.anchor_searcher = anchor_searcher
        self.flow_repository = flow_repository
        self.flow_engine = flow_engine or EntrypointFlowEngine(flow_repository)
        self.policy = policy or KnowledgeQueryPolicy()
        self.anchor_expander = anchor_expander or AnchorExpansionService(getattr(flow_repository, "graph_store", None))
        self.family_assembler = FlowFamilyAssembler()
        self.narrative_planner = FlowNarrativePlanner()

    def query(self, request: KnowledgeQueryRequest, plan: QueryRetrievalPlan | None = None) -> KnowledgeQueryResponse:
        return self.query_with_flows(request, plan=plan).response

    def query_with_flows(self, request: KnowledgeQueryRequest, plan: QueryRetrievalPlan | None = None) -> KnowledgeQueryExecutionResult:
        query_started = time.monotonic()
        repository_metrics_before = self._repository_metrics()
        diagnostics: List[KnowledgeQueryDiagnostic] = []
        eligible_sources, scope_diagnostics = self.source_scope_resolver.resolve()
        diagnostics.extend(scope_diagnostics)
        candidate_started = time.monotonic()
        if plan is not None:
            candidate_result = self.anchor_searcher.search_plan(plan, eligible_sources, self.policy, bool(request.includeTests))
            effective_intent = plan.effective_intent
        else:
            candidate_result = self.anchor_searcher.search(request.queryText, eligible_sources, self.policy, bool(request.includeTests))
            effective_intent = request.intent.value
        candidate_ms = (time.monotonic() - candidate_started) * 1000
        diagnostics.extend(candidate_result.diagnostics)
        if not candidate_result.all_candidates:
            return KnowledgeQueryExecutionResult(
                response=KnowledgeQueryResponse(
                    queryId=self._query_id(),
                    status=KnowledgeQueryStatus.NO_CANDIDATES,
                    intent=effective_intent,
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
        flow_seed_nodes = anchor_result.flow_seed_nodes
        request_flow_limit = max(1, min(int(request.maxFlows or 10), 10))
        build_result = self.flow_engine.build(
            flow_seed_nodes,
            max_flows=0,
            include_tests=bool(request.includeTests),
        )
        raw_flows = build_result.flows
        supporting_nodes, supporting_relations = self._supporting_relations(raw_flows, bool(request.includeTests))
        assembly_result = self.family_assembler.assemble(
            raw_flows,
            supporting_nodes=supporting_nodes,
            supporting_relations=supporting_relations,
        )
        flows = self.family_assembler.rank(assembly_result.families)
        if plan is not None:
            flows = self._select_plan_flows(flows, plan)
        continuation_result = self._assemble_exact_downstream_continuations(
            flows,
            eligible_sources,
            include_tests=bool(request.includeTests),
        )
        flows = continuation_result.families
        operation_facts = continuation_result.operation_facts
        diagnostics.extend(continuation_result.diagnostics)
        narrative_plans, narrative_diagnostics = self.narrative_planner.assemble(
            flows,
            max_plans=request_flow_limit,
            operation_facts=operation_facts,
        )
        diagnostics.extend(narrative_diagnostics)
        selected_fragments = []
        seen_fragment_keys: set[str] = set()
        selected_fragment_keys: list[str] = []
        for narrative_plan in narrative_plans:
            for fragment in narrative_plan.fragments:
                if fragment.key in seen_fragment_keys:
                    continue
                seen_fragment_keys.add(fragment.key)
                selected_fragment_keys.append(fragment.key)
                selected_fragments.append(fragment)
        hydrated_families = tuple(self.flow_repository.hydrate_evidence([fragment.family for fragment in selected_fragments]))
        hydrated_by_key = {
            fragment.key: hydrated
            for fragment, hydrated in zip(selected_fragments, hydrated_families)
        }
        narrative_plans = tuple(replace_plan_fragments(narrative_plan, hydrated_by_key) for narrative_plan in narrative_plans)
        selected_flows = tuple(hydrated_by_key[key] for key in selected_fragment_keys if key in hydrated_by_key)
        public_flows = self.flow_engine.public_flows(selected_flows)
        repository_metric_delta = self._repository_metric_delta(repository_metrics_before, self._repository_metrics())
        request_traversal_stats = dict(build_result.traversal_stats or {})
        request_traversal_stats.update(repository_metric_delta)
        omitted_plan_count = max(
            (int(item.metadata.get("omittedPlanCount") or 0) for item in narrative_diagnostics if item.code == "NARRATIVE_PLAN_MAX_FLOWS_REACHED"),
            default=0,
        )
        diagnostics.extend(build_result.diagnostics)
        diagnostics.extend(assembly_result.diagnostics)
        diagnostics.append(KnowledgeQueryDiagnostic(
            code="ENTRYPOINT_FLOW_TIMINGS",
            message="Entrypoint flow query stage timings.",
            severity="INFO",
            metadata={
                "candidateSearchMs": round(candidate_ms, 3),
                **(build_result.stage_timings_ms or {}),
                "totalMs": round((time.monotonic() - query_started) * 1000, 3),
                **request_traversal_stats,
                "availableOperationFactCount": len(operation_facts),
                "rawCandidateFlowCount": assembly_result.raw_candidate_flow_count,
                "discoveredFamilyCount": assembly_result.discovered_family_count,
                "selectedFamilyCount": len(selected_flows),
                "narrativePlanCount": len(narrative_plans),
                "omittedPlanCount": omitted_plan_count,
            },
        ))
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
                intent=effective_intent,
                matchedSources=matched_sources,
                matchedNodes=[self._matched_node_preview(item) for item in display_matched_nodes],
                flows=public_flows,
                coverage=KnowledgeQueryCoverage(
                    searchedSourceCount=len(eligible_sources),
                    matchedSourceCount=len(matched_sources),
                    matchedNodeCount=len(matched_nodes),
                    flowCount=len(narrative_plans),
                    nodeCount=sum(flow.coverage.node_count for flow in selected_flows),
                    edgeCount=sum(flow.coverage.transition_count + flow.coverage.boundary_count for flow in selected_flows),
                    evidenceCount=sum(len(flow.evidence) for flow in selected_flows),
                    truncated=candidate_result.truncated or anchor_result.truncated or omitted_plan_count > 0,
                    continuationAvailable=candidate_result.truncated or anchor_result.truncated or omitted_plan_count > 0,
                ),
                diagnostics=diagnostics,
            ),
            flows=selected_flows,
            narrative_plans=narrative_plans,
            raw_flows=raw_flows,
            family_assembly=assembly_result,
        )

    def _repository_metrics(self) -> Dict[str, int]:
        if not hasattr(self.flow_repository, "metrics"):
            return {}
        return {
            str(key): int(value)
            for key, value in (self.flow_repository.metrics() or {}).items()
            if isinstance(value, int)
        }

    def _repository_metric_delta(self, before: Mapping[str, int], after: Mapping[str, int]) -> Dict[str, int]:
        return {
            key: max(0, int(after.get(key, 0)) - int(before.get(key, 0)))
            for key in sorted(set(before) | set(after))
        }

    def _available_operation_facts(
        self,
        flows: Sequence[EntrypointFlow | FlowFamily],
        include_tests: bool,
    ):
        if not flows or not hasattr(self.flow_repository, "load_available_operation_facts"):
            return ()
        node_keys = {
            (node.source_id, node.graph_revision or node.graph_id, node.node_id)
            for flow in flows
            for node in flow.nodes
        }
        return self.flow_repository.load_available_operation_facts(node_keys, include_tests=include_tests)

    def _assemble_exact_downstream_continuations(
        self,
        initial_families: Sequence[FlowFamily],
        eligible_sources: Sequence[QuerySource],
        *,
        include_tests: bool,
    ) -> ContinuationAssemblyResult:
        families: list[FlowFamily] = list(initial_families)
        if not families:
            return ContinuationAssemblyResult((), (), ())
        operation_facts: tuple[AvailableOperationFact, ...] = self._available_operation_facts(families, include_tests)
        if not hasattr(self.flow_repository, "load_matching_inbound_operation_facts"):
            return ContinuationAssemblyResult(tuple(families), operation_facts, ())

        diagnostics: list[KnowledgeQueryDiagnostic] = []
        known_family_keys = {self._flow_family_key(family) for family in families}
        processed_operations: set[tuple[str, str, str, str, str, str]] = set()
        eligible_source_ids = [source.source_id for source in eligible_sources]

        while True:
            fragments = self.narrative_planner.fragments(families, operation_facts=operation_facts)
            outbound_facts: list[AvailableOperationFact] = []
            for fragment in fragments:
                for fact in fragment.operation_facts:
                    if not self._is_usable_outbound_http_fact(fact):
                        continue
                    operation_key = fact.operation_key(fragment.key)
                    if operation_key in processed_operations:
                        continue
                    processed_operations.add(operation_key)
                    outbound_facts.append(fact)
            if not outbound_facts:
                break

            inbound_facts = self.flow_repository.load_matching_inbound_operation_facts(
                outbound_facts,
                eligible_source_ids=eligible_source_ids,
                include_tests=include_tests,
            )
            discovered: list[FlowFamily] = []
            for outbound in sorted(outbound_facts, key=self._operation_fact_sort_key):
                matches = [
                    inbound
                    for inbound in inbound_facts
                    if self._operation_facts_match(outbound, inbound)
                    and self._operation_owner_key(inbound) not in known_family_keys
                ]
                owner_keys = sorted({self._operation_owner_key(fact) for fact in matches})
                if len(owner_keys) > 1:
                    diagnostics.append(
                        KnowledgeQueryDiagnostic(
                            code="FLOW_CONTINUATION_AMBIGUOUS",
                            message="Multiple current inbound HTTP operation facts matched an outbound operation; no downstream flow was selected.",
                            severity="INFO",
                            sourceId=outbound.owner_source_id,
                            metadata={
                                "transportKind": "HTTP",
                                "method": normalize_http_method(outbound.method),
                                "route": normalize_route(outbound.normalized_route),
                                "candidateCount": len(owner_keys),
                            },
                        )
                    )
                    continue
                if len(owner_keys) != 1:
                    continue
                inbound = sorted(
                    [fact for fact in matches if self._operation_owner_key(fact) == owner_keys[0]],
                    key=self._operation_fact_sort_key,
                )[0]
                new_families = [
                    family
                    for family in self._families_for_inbound_operation(inbound, include_tests=include_tests)
                    if self._flow_family_key(family) not in known_family_keys
                ]
                for family in new_families:
                    known_family_keys.add(self._flow_family_key(family))
                discovered.extend(new_families)
            if not discovered:
                break
            families.extend(discovered)
            operation_facts = merge_semantic_operation_facts((
                *operation_facts,
                *self._available_operation_facts(discovered, include_tests),
            ))

        return ContinuationAssemblyResult(tuple(families), operation_facts, tuple(diagnostics))

    def _families_for_inbound_operation(
        self,
        inbound: AvailableOperationFact,
        *,
        include_tests: bool,
    ) -> tuple[FlowFamily, ...]:
        anchor = KnowledgeQueryMatchedNode(
            sourceId=inbound.owner_source_id,
            nodeId=inbound.owner_node_id,
            stableKey=inbound.structural_owner,
            nodeKind="CALLABLE",
            label=self._operation_owner_label(inbound),
            score=1.0,
            matchReasons=["TYPED_HTTP_OPERATION_MATCH"],
            graphId=inbound.owner_graph_id or None,
            graphRevision=inbound.owner_graph_revision or inbound.owner_graph_id or None,
            relativePath=inbound.owner_relative_path,
            qualifiedName=inbound.owner_qualified_name,
            flowDomain=inbound.eligibility.flow_domain if inbound.eligibility is not None else None,
        )
        build_result = self.flow_engine.build([anchor], max_flows=0, include_tests=include_tests)
        if not build_result.flows:
            return ()
        supporting_nodes, supporting_relations = self._supporting_relations(build_result.flows, include_tests)
        assembly = self.family_assembler.assemble(
            build_result.flows,
            supporting_nodes=supporting_nodes,
            supporting_relations=supporting_relations,
        )
        return self.family_assembler.rank(assembly.families)

    def _is_usable_outbound_http_fact(self, fact: AvailableOperationFact) -> bool:
        if normalize_transport_kind(fact.transport_kind) != "HTTP":
            return False
        if str(fact.direction_role or "").strip().upper() != "OUTBOUND":
            return False
        if not normalize_http_method(fact.method) or not normalize_route(fact.normalized_route):
            return False
        if fact.eligibility is not None and (not fact.eligibility.inventory_current or not fact.eligibility.analyzed_current):
            return False
        return True

    def _operation_facts_match(self, outbound: AvailableOperationFact, inbound: AvailableOperationFact) -> bool:
        if normalize_transport_kind(outbound.transport_kind) != normalize_transport_kind(inbound.transport_kind):
            return False
        if normalize_http_method(outbound.method) != normalize_http_method(inbound.method):
            return False
        if normalize_route(outbound.normalized_route) != normalize_route(inbound.normalized_route):
            return False
        target_identity = clean_identity(outbound.target_service_identity)
        if target_identity and inbound.owner_source_id != target_identity:
            return False
        for attr in (
            "operation_identity",
            "interface_identity",
            "request_contract_identity",
            "response_contract_identity",
        ):
            outbound_value = clean_identity(getattr(outbound, attr))
            inbound_value = clean_identity(getattr(inbound, attr))
            if outbound_value and inbound_value and outbound_value != inbound_value:
                return False
        return True

    def _operation_owner_key(self, fact: AvailableOperationFact) -> str:
        return ":".join((fact.owner_source_id, fact.owner_graph_revision or fact.owner_graph_id, fact.owner_node_id))

    def _flow_family_key(self, family: FlowFamily) -> str:
        return ":".join((family.key.source_id, family.key.graph_revision, family.key.entrypoint_node_id))

    def _operation_owner_label(self, fact: AvailableOperationFact) -> str:
        qualified = clean_identity(fact.owner_qualified_name)
        if qualified:
            return qualified
        return fact.owner_node_id

    def _operation_fact_sort_key(self, fact: AvailableOperationFact) -> tuple[str, str, str, str, str, str]:
        return (
            fact.owner_source_id,
            fact.owner_graph_revision or fact.owner_graph_id,
            fact.owner_node_id,
            normalize_transport_kind(fact.transport_kind) or "",
            normalize_http_method(fact.method) or "",
            normalize_route(fact.normalized_route) or "",
        )

    def _supporting_relations(
        self,
        raw_flows: Sequence[EntrypointFlow],
        include_tests: bool,
    ):
        if not raw_flows or not hasattr(self.flow_repository, "load_supporting_relations"):
            return {}, ()
        node_keys = {
            (node.source_id, node.graph_revision or node.graph_id, node.node_id)
            for flow in raw_flows
            for node in flow.nodes
        }
        return self.flow_repository.load_supporting_relations(node_keys, include_tests=include_tests)

    def _select_plan_flows(
        self,
        flows: Sequence[EntrypointFlow],
        plan: QueryRetrievalPlan,
    ) -> tuple[EntrypointFlow, ...]:
        if not flows:
            return ()
        exact = self._filter_flows_by_code_identifiers(flows, plan.code_identifiers)
        if plan.code_identifiers:
            return exact
        return self._rank_flows_by_grounded_relevance(flows)

    def _filter_flows_by_code_identifiers(
        self,
        flows: Sequence[EntrypointFlow],
        identifiers: Sequence[str],
    ) -> tuple[EntrypointFlow, ...]:
        exact_identifiers = [identifier for identifier in identifiers if str(identifier or "").strip()]
        if not exact_identifiers:
            return ()
        result: List[EntrypointFlow] = []
        for flow in flows:
            if any(self._flow_matches_identifier(flow, identifier) for identifier in exact_identifiers):
                result.append(flow)
        return tuple(result)

    def _flow_matches_identifier(self, flow: EntrypointFlow, identifier: str) -> bool:
        normalized = str(identifier or "").strip()
        if not normalized:
            return False
        for candidate in self._flow_identifier_candidates(flow):
            if candidate == normalized:
                return True
            if self._has_symbol_suffix(candidate, normalized):
                return True
        return False

    def _has_symbol_suffix(self, candidate: str, identifier: str) -> bool:
        if len(candidate) <= len(identifier) or not candidate.endswith(identifier):
            return False
        delimiter_index = len(candidate) - len(identifier) - 1
        return delimiter_index >= 0 and candidate[delimiter_index] in {".", "#", ":", "/", "$"}

    def _flow_identifier_candidates(self, flow: EntrypointFlow) -> set[str]:
        candidates: set[str] = set()
        for node in flow.nodes:
            candidates.update(
                str(value or "").strip()
                for value in (
                    node.label,
                    node.qualified_name,
                    node.entrypoint_route,
                    node.entrypoint_topic,
                    node.entrypoint_interface_method,
                )
                if str(value or "").strip()
            )
        for anchor in flow.anchors:
            candidates.update(
                str(value or "").strip()
                for value in (anchor.label,)
                if str(value or "").strip()
            )
        return candidates

    def _rank_flows_by_grounded_relevance(self, flows: Sequence[EntrypointFlow]) -> tuple[EntrypointFlow, ...]:
        scored = [
            (max(float(flow.relevance_score or 0.0), self._aggregate_anchor_score(flow)), flow)
            for flow in flows
        ]
        if not scored:
            return ()
        top_score = max(score for score, _flow in scored)
        threshold = max(self.policy.plan_flow_min_relevance_score, top_score - self.policy.plan_flow_top_delta)
        selected = [
            flow
            for score, flow in sorted(scored, key=lambda item: (-item[0], item[1].key.source_id, item[1].key.entrypoint_node_id))
            if score >= threshold
        ]
        return tuple(selected)

    def _aggregate_anchor_score(self, flow: EntrypointFlow) -> float:
        if not flow.anchors:
            return 0.0
        best = max(float(anchor.score or 0.0) for anchor in flow.anchors)
        support = min(len(flow.anchors), 10) * 0.01
        proximity = 0.01 / (1 + min(anchor.distance for anchor in flow.anchors))
        return best + support + proximity

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

    def _matched_node_preview(self, item: KnowledgeQueryMatchedNode) -> KnowledgeQueryMatchedNodePreview:
        return KnowledgeQueryMatchedNodePreview(
            sourceId=item.sourceId,
            nodeKind=item.nodeKind,
            label=item.label,
            score=item.score,
            matchReasons=item.matchReasons,
            relativePath=item.relativePath,
            qualifiedName=item.qualifiedName,
            flowDomain=item.flowDomain,
        )

    def _is_ambiguous(self, matched_nodes: Sequence[KnowledgeQueryMatchedNode]) -> bool:
        if len(matched_nodes) < 2:
            return False
        top = matched_nodes[0].score
        top_sources = {matched_node.sourceId for matched_node in matched_nodes if top - matched_node.score <= 0.03}
        return len(top_sources) > 1

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
        flow_repository=EntrypointFlowGraphRepository(graph_store),
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
