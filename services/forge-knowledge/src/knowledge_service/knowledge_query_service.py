from __future__ import annotations

import time
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any, Dict, List, Mapping, Sequence, Tuple

from knowledge_service.anchor_expansion_contract import (
    AnchorExpansionBundle,
    AnchorExpansionEdge,
    AnchorExpansionNode,
    AnchorExpansionRequest,
)
from knowledge_service.boundary_contract import LocalBoundaryFact
from knowledge_service.boundary_resolution import (
    BOUNDARY_ROLE_REQUIRED,
    BoundaryCandidateLoadLimits,
    BoundaryOwnerIdentity,
    BoundaryResolutionDiagnostic,
    BoundaryResolutionResult,
    BoundaryResolutionTruncationState,
    BoundaryResolverMetrics,
    BoundaryTargetMaterialization,
    BoundaryTargetMaterializationStatus,
    BoundaryTargetSeedIdentity,
    BoundaryTargetSeedRelation,
    GenericBoundaryResolver,
    boundary_identity,
)
from knowledge_service.end_to_end_flow import EndToEndFlowAssembler, EndToEndFlowAssemblyResult, EndToEndFlowDiagnostic
from knowledge_service.end_to_end_projection import EndToEndProjectionBuilder
from knowledge_service.end_to_end_selection import EndToEndGraphSelectionResult, EndToEndGraphSelector
from knowledge_service.entrypoint_flow_engine import EntrypointFlowEngine, EntrypointFlowSeedProvenance, LocalFlowUnit
from knowledge_service.entrypoint_flow_store import EntrypointFlowGraphRepository
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
from knowledge_service.local_flow_selection import LocalFlowUnitSelectionResult, LocalFlowUnitSelector
from knowledge_service.query_interpretation import QueryRetrievalPlan


@dataclass(frozen=True)
class KnowledgeQueryPolicy:
    max_search_documents: int = 5000
    max_candidates_per_provider: int = 100
    max_selected_anchors: int = 12
    max_display_candidates: int = 20
    min_lexical_score: float = 0.28
    min_fuzzy_score: float = 0.58
    fuzzy_max_edit_distance: int = 3
    enable_fuzzy_search: bool = True
    enable_search_diagnostics: bool = True
    plan_candidate_min_score: float = 0.42
    exact_identifier_min_score: float = 0.75
    fallback_anchor_trigger_count: int = 3
    plan_flow_min_relevance_score: float = 0.05
    plan_flow_top_delta: float = 0.25


class CandidatePoolKind(str, Enum):
    EXACT = "EXACT"
    PATH = "PATH"
    QUALIFIED_NAME = "QUALIFIED_NAME"
    LEXICAL = "LEXICAL"
    FUZZY = "FUZZY"
    SEMANTIC = "SEMANTIC"


class RetrievalPhase(str, Enum):
    PRIMARY = "PRIMARY"
    FALLBACK = "FALLBACK"


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

_MAX_BOUNDARY_RESOLUTION_ROUNDS = 8
_MAX_BOUNDARY_TARGET_UNITS = 50


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
class AnchorSeedExpansion:
    original_source_id: str
    original_graph_revision: str
    original_node_id: str
    original_stable_key: str
    expanded_seed_source_id: str
    expanded_seed_graph_revision: str
    expanded_seed_node_id: str
    expanded_seed_stable_key: str
    reason: AnchorExpansionReason


@dataclass(frozen=True)
class ExpandedAnchor:
    node: KnowledgeQueryMatchedNode
    roles: tuple[AnchorRole, ...]
    reasons: tuple[AnchorExpansionReason, ...]
    originNodeIds: tuple[str, ...]
    score: float
    seedExpansions: tuple[AnchorSeedExpansion, ...] = ()


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
class RetrievalScope:
    primary_sources: tuple[QuerySource, ...]
    fallback_sources: tuple[QuerySource, ...] = ()

    @classmethod
    def primary(cls, sources: Sequence[QuerySource]) -> RetrievalScope:
        return cls(primary_sources=tuple(sources), fallback_sources=())


@dataclass(frozen=True)
class SourceDocumentLoadStats:
    phase: RetrievalPhase
    source_id: str
    eligible_document_count: int
    allocated_document_count: int
    inspected_document_count: int
    truncated_document_count: int
    starved: bool = False


@dataclass(frozen=True)
class SourceDocumentLoadResult:
    documents: list[SearchDocument]
    stats: tuple[SourceDocumentLoadStats, ...]
    truncated: bool = False


@dataclass(frozen=True)
class PhaseRetrievalOutput:
    phase: RetrievalPhase
    raw_candidates: list[SearchCandidate]
    merged_candidates: list[MergedCandidate]
    diagnostics: list[KnowledgeQueryDiagnostic]
    document_stats: tuple[SourceDocumentLoadStats, ...]
    document_truncated: bool = False
    candidate_limit_reached: bool = False


@dataclass(frozen=True)
class KnowledgeQueryExecutionResult:
    response: KnowledgeQueryResponse
    local_unit_selection: LocalFlowUnitSelectionResult | None = None
    local_units: tuple[LocalFlowUnit, ...] = ()
    boundary_resolution: BoundaryResolutionResult | None = None
    end_to_end_assembly: EndToEndFlowAssemblyResult | None = None
    graph_selection: EndToEndGraphSelectionResult | None = None
    selected_graphs: tuple[Any, ...] = ()
    presentation_plans: tuple[Any, ...] = ()


@dataclass(frozen=True)
class ContinuationAssemblyResult:
    initial_selected_local_unit_ids: tuple[str, ...]
    local_units: tuple[LocalFlowUnit, ...] = ()
    boundary_resolution: BoundaryResolutionResult | None = None
    target_seed_provenance: tuple[EntrypointFlowSeedProvenance, ...] = ()
    diagnostics: tuple[KnowledgeQueryDiagnostic, ...] = ()


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


class SourceDiverseAnchorSelector:
    """Deterministic quality-first selection with marginal diversity scoring."""

    def select(
        self,
        candidates: Sequence[MergedCandidate],
        policy: KnowledgeQueryPolicy,
        *,
        preserve_candidates: Sequence[MergedCandidate] = (),
    ) -> list[MergedCandidate]:
        budget = max(1, int(policy.max_selected_anchors or 1))
        eligible = self.usable_candidates(candidates, policy)
        if not eligible:
            return []

        by_key = {self._candidate_key(candidate): candidate for candidate in eligible}
        preserved_keys = [self._candidate_key(candidate) for candidate in preserve_candidates if self._candidate_key(candidate) in by_key]
        selected: list[MergedCandidate] = []
        selected_keys: set[tuple[str, str, str]] = set()
        source_counts: Counter[str] = Counter()
        selected_query_inputs: set[str] = set()

        for key in preserved_keys:
            if key in selected_keys or len(selected) >= budget:
                continue
            candidate = by_key[key]
            self._append_selected(candidate, selected, selected_keys, source_counts, selected_query_inputs)

        while len(selected) < budget:
            remaining = [candidate for candidate in eligible if self._candidate_key(candidate) not in selected_keys]
            if not remaining:
                break
            scored = [
                (
                    self._marginal_quality(candidate, source_counts, selected_query_inputs),
                    self._quality(candidate),
                    candidate,
                )
                for candidate in remaining
            ]
            scored.sort(key=lambda item: (-round(item[0], 6), -round(item[1], 6), self._candidate_sort_key(item[2])))
            best_marginal, _quality, best = scored[0]
            if best_marginal < max(0.0, policy.plan_candidate_min_score - 0.12):
                break
            self._append_selected(best, selected, selected_keys, source_counts, selected_query_inputs)
        return selected

    def usable_candidates(self, candidates: Sequence[MergedCandidate], policy: KnowledgeQueryPolicy) -> list[MergedCandidate]:
        usable = [
            candidate
            for candidate in candidates
            if not self._is_expansion_only_candidate(candidate)
            and self._quality(candidate) >= self._minimum_quality(candidate, policy)
        ]
        return sorted(usable, key=lambda candidate: (-round(self._quality(candidate), 6), self._candidate_sort_key(candidate)))

    def _append_selected(
        self,
        candidate: MergedCandidate,
        selected: list[MergedCandidate],
        selected_keys: set[tuple[str, str, str]],
        source_counts: Counter[str],
        selected_query_inputs: set[str],
    ) -> None:
        selected.append(candidate)
        selected_keys.add(self._candidate_key(candidate))
        source_counts[candidate.document.source_id] += 1
        selected_query_inputs.update(candidate.query_inputs)

    def _minimum_quality(self, candidate: MergedCandidate, policy: KnowledgeQueryPolicy) -> float:
        if self._is_precise_identifier_candidate(candidate):
            return min(policy.exact_identifier_min_score, 0.72)
        return max(0.0, float(policy.plan_candidate_min_score or 0.0))

    def _marginal_quality(
        self,
        candidate: MergedCandidate,
        source_counts: Counter[str],
        selected_query_inputs: set[str],
    ) -> float:
        quality = self._quality(candidate)
        quality -= min(0.14, 0.045 * source_counts[candidate.document.source_id])
        if set(candidate.query_inputs).difference(selected_query_inputs):
            quality += 0.015
        if RetrievalPhase.FALLBACK.value in candidate.retrieval_phases and RetrievalPhase.PRIMARY.value not in candidate.retrieval_phases:
            quality -= 0.015
        return quality

    def _quality(self, candidate: MergedCandidate) -> float:
        # Anchor quality combines bounded, independent components: merged
        # provider score, exactness, unique real providers, unique query inputs,
        # and confidence. Query variants never count as provider agreement.
        score = float(candidate.score or 0.0)
        if self._is_precise_identifier_candidate(candidate):
            score += 0.055
        elif any(reason.startswith("EXACT") and reason != "EXACT_KIND" for reason in candidate.reasons):
            score += 0.035
        score += min(0.04, 0.01 * max(0, len(set(candidate.providers)) - 1))
        score += min(0.025, 0.006 * max(0, len(set(candidate.query_inputs)) - 1))
        if str(candidate.confidence or "").upper() == "HIGH":
            score += 0.01
        return min(1.2, score)

    def _is_expansion_only_candidate(self, candidate: MergedCandidate) -> bool:
        reasons = set(candidate.reasons)
        if "QUERY_EXPANSION" not in reasons:
            return False
        return not reasons.intersection({"QUERY_ORIGINAL", "QUERY_NORMALIZED", "QUERY_EXACT_IDENTIFIER"})

    def _is_precise_identifier_candidate(self, candidate: MergedCandidate) -> bool:
        reasons = set(candidate.reasons)
        if "QUERY_EXACT_IDENTIFIER" not in reasons:
            return False
        return any((reason.startswith("EXACT") and reason != "EXACT_KIND") or reason in _PRECISE_IDENTIFIER_REASONS for reason in reasons)

    def _candidate_key(self, candidate: MergedCandidate) -> tuple[str, str, str]:
        return (
            candidate.document.source_id,
            candidate.document.graph_revision or candidate.document.graph_id or "",
            candidate.document.node_id,
        )

    def _candidate_sort_key(self, candidate: MergedCandidate) -> tuple[str, str, str, str, str]:
        return (
            candidate.document.source_id,
            candidate.document.graph_revision or candidate.document.graph_id or "",
            candidate.document.node_kind,
            (candidate.document.label or candidate.document.name or "").lower(),
            candidate.document.node_id,
        )


class UnifiedAnchorSearcher:
    def __init__(
        self,
        graph_store: Any,
        search_engine: DeterministicCodeSearchEngine | None = None,
        selector: SourceDiverseAnchorSelector | None = None,
    ) -> None:
        self.graph_store = graph_store
        self.search_engine = search_engine or DeterministicCodeSearchEngine()
        self.normalizer = QueryNormalizer()
        self.candidate_merger = CandidateMerger()
        self.selector = selector or SourceDiverseAnchorSelector()

    def search(
        self,
        query: str,
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
        include_tests: bool = False,
    ) -> CandidateRetrievalResult:
        return self.search_scope(
            (("ORIGINAL_QUERY", query),),
            RetrievalScope.primary(eligible_sources),
            policy,
            include_tests=include_tests,
        )

    def search_plan(
        self,
        plan: QueryRetrievalPlan,
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
        include_tests: bool = False,
        scope: RetrievalScope | None = None,
    ) -> CandidateRetrievalResult:
        return self.search_scope(
            self._plan_query_inputs(plan),
            scope or RetrievalScope.primary(eligible_sources),
            policy,
            include_tests=include_tests,
        )

    def search_scope(
        self,
        query_inputs: Sequence[tuple[str, str]],
        scope: RetrievalScope,
        policy: KnowledgeQueryPolicy,
        *,
        include_tests: bool = False,
    ) -> CandidateRetrievalResult:
        query_inputs = tuple((str(reason or ""), str(value or "")) for reason, value in query_inputs if str(value or "").strip())
        combined_tokens = self.normalizer.normalize(" ".join(value for _reason, value in query_inputs)).tokens
        if not combined_tokens or not scope.primary_sources:
            return self._empty_result()

        primary = self._run_phase(RetrievalPhase.PRIMARY, scope.primary_sources, query_inputs, combined_tokens, policy, include_tests)
        outputs = [primary]
        selected_primary = self.selector.select(primary.merged_candidates, policy)
        all_raw_candidates = list(primary.raw_candidates)
        final_merged_candidates = list(primary.merged_candidates)

        if scope.fallback_sources and len(selected_primary) < max(1, int(policy.fallback_anchor_trigger_count or 1)):
            fallback = self._run_phase(RetrievalPhase.FALLBACK, scope.fallback_sources, query_inputs, combined_tokens, policy, include_tests)
            outputs.append(fallback)
            all_raw_candidates.extend(fallback.raw_candidates)
            final_merged_candidates = self.candidate_merger.merge(all_raw_candidates) if all_raw_candidates else []
            selected_candidates = self.selector.select(final_merged_candidates, policy, preserve_candidates=selected_primary)
        else:
            selected_candidates = selected_primary

        display_limit = max(1, int(policy.max_display_candidates or 1))
        all_candidates = [self._matched_node(candidate) for candidate in selected_candidates]
        display_candidates = all_candidates[:display_limit]
        usable_count = len(self.selector.usable_candidates(final_merged_candidates, policy))
        candidate_budget_reached = usable_count > len(selected_candidates) and len(selected_candidates) >= max(1, int(policy.max_selected_anchors or 1))
        truncated = any(output.document_truncated or output.candidate_limit_reached for output in outputs) or candidate_budget_reached
        diagnostics = self._collect_diagnostics(
            outputs,
            scope,
            final_merged_candidates,
            selected_candidates,
            usable_count,
            candidate_budget_reached,
            policy,
        )
        if policy.enable_search_diagnostics and any(output.document_stats for output in outputs) and not all_candidates:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="SEARCH_MATCHES_BELOW_THRESHOLD",
                    message="Search inspected current graph facts, but matches did not clear source-diverse anchor thresholds.",
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
            pools=self._candidate_pools(all_raw_candidates),
            all_candidates=all_candidates,
            display_candidates=display_candidates,
            diagnostics=diagnostics,
            truncated=truncated,
        )

    def _run_phase(
        self,
        phase: RetrievalPhase,
        sources: Sequence[QuerySource],
        query_inputs: Sequence[tuple[str, str]],
        combined_tokens: Sequence[str],
        policy: KnowledgeQueryPolicy,
        include_tests: bool,
    ) -> PhaseRetrievalOutput:
        loaded = self._load_search_documents(combined_tokens, sources, policy, include_tests, phase)
        config = self._search_config(policy, sources, include_tests)
        raw_candidates: list[SearchCandidate] = []
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        candidate_limit_reached = False
        for query_reason, query_value in query_inputs:
            result = self.search_engine.search(query_value, loaded.documents, config)
            candidate_limit_reached = candidate_limit_reached or bool(getattr(result, "candidate_limit_reached", False))
            for item in result.diagnostics:
                metadata = item.get("metadata") if isinstance(item.get("metadata"), dict) else {}
                diagnostics.append(
                    self._search_diagnostic(
                        {
                            **item,
                            "metadata": {
                                **metadata,
                                "queryReason": query_reason,
                                "queryInput": query_value,
                                "retrievalPhase": phase.value,
                            },
                        }
                    )
                )
            raw_candidates.extend(self._annotated_candidates(getattr(result, "raw_candidates", []) or [], query_reason, query_value, phase))
        merged = self.candidate_merger.merge(raw_candidates) if raw_candidates else []
        return PhaseRetrievalOutput(
            phase=phase,
            raw_candidates=raw_candidates,
            merged_candidates=merged,
            diagnostics=diagnostics,
            document_stats=loaded.stats,
            document_truncated=loaded.truncated,
            candidate_limit_reached=candidate_limit_reached,
        )

    def _plan_query_inputs(self, plan: QueryRetrievalPlan) -> tuple[tuple[str, str], ...]:
        original_query = str(plan.original_query or "")
        inputs: list[tuple[str, str]] = []
        for query_reason, query_value in plan.query_inputs():
            if query_reason == "CODE_IDENTIFIER" and str(query_value or "") not in original_query:
                continue
            inputs.append((query_reason, query_value))
        return tuple(inputs)

    def _annotated_candidates(
        self,
        candidates: Sequence[SearchCandidate],
        query_reason: str,
        query_input: str,
        phase: RetrievalPhase,
    ) -> list[SearchCandidate]:
        result: list[SearchCandidate] = []
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
            metadata = {
                **dict(candidate.metadata or {}),
                "queryReason": query_reason,
                "queryInput": query_input,
                "retrievalPhase": phase.value,
                "providerScore": round(float(candidate.score), 6),
                "queryMarkerReason": marker_reason,
            }
            adjusted = replace(candidate, score=adjusted_score, priority=adjusted_priority, metadata=metadata)
            result.append(adjusted)
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

    def _matched_node(self, candidate: MergedCandidate) -> KnowledgeQueryMatchedNode:
        return KnowledgeQueryMatchedNode(**candidate.document.to_matched_node_dict(candidate.score, candidate.reasons))

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

    def _collect_diagnostics(
        self,
        outputs: Sequence[PhaseRetrievalOutput],
        scope: RetrievalScope,
        merged_candidates: Sequence[MergedCandidate],
        selected_candidates: Sequence[MergedCandidate],
        usable_candidate_count: int,
        candidate_budget_reached: bool,
        policy: KnowledgeQueryPolicy,
    ) -> list[KnowledgeQueryDiagnostic]:
        diagnostics: list[KnowledgeQueryDiagnostic] = []
        for output in outputs:
            diagnostics.extend(output.diagnostics)
        if not policy.enable_search_diagnostics:
            return diagnostics
        any_limit_reached = any(output.document_truncated or output.candidate_limit_reached for output in outputs) or candidate_budget_reached
        if any_limit_reached:
            diagnostics.append(
                KnowledgeQueryDiagnostic(
                    code="SEARCH_CANDIDATE_LIMIT_REACHED",
                    message="Search reached a bounded retrieval limit before all candidates could be retained.",
                    severity="INFO",
                    metadata={
                        "maxSearchDocuments": policy.max_search_documents,
                        "maxCandidatesPerProvider": policy.max_candidates_per_provider,
                        "maxSelectedAnchors": policy.max_selected_anchors,
                    },
                )
            )
        diagnostics.append(
            KnowledgeQueryDiagnostic(
                code="SOURCE_DIVERSE_RETRIEVAL_DIAGNOSTICS",
                message="Source-diverse anchor retrieval diagnostics.",
                severity="INFO",
                metadata=self._retrieval_diagnostic_metadata(
                    outputs,
                    scope,
                    merged_candidates,
                    selected_candidates,
                    usable_candidate_count,
                    candidate_budget_reached,
                ),
            )
        )
        return diagnostics

    def _retrieval_diagnostic_metadata(
        self,
        outputs: Sequence[PhaseRetrievalOutput],
        scope: RetrievalScope,
        merged_candidates: Sequence[MergedCandidate],
        selected_candidates: Sequence[MergedCandidate],
        usable_candidate_count: int,
        candidate_budget_reached: bool,
    ) -> dict[str, Any]:
        document_stats = [stats for output in outputs for stats in output.document_stats]
        raw_by_provider_source: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
        primary_raw_count = 0
        fallback_raw_count = 0
        for output in outputs:
            for candidate in output.raw_candidates:
                raw_by_provider_source[candidate.provider][candidate.document.source_id] += 1
            if output.phase == RetrievalPhase.PRIMARY:
                primary_raw_count += len(output.raw_candidates)
            elif output.phase == RetrievalPhase.FALLBACK:
                fallback_raw_count += len(output.raw_candidates)
        merged_by_source = Counter(candidate.document.source_id for candidate in merged_candidates)
        selected_by_source = Counter(candidate.document.source_id for candidate in selected_candidates)
        documents_by_source = {
            stats.source_id: {
                "phase": stats.phase.value,
                "eligible": stats.eligible_document_count,
                "allocated": stats.allocated_document_count,
                "inspected": stats.inspected_document_count,
                "truncated": stats.truncated_document_count,
                "starved": stats.starved,
            }
            for stats in sorted(document_stats, key=lambda item: (item.phase.value, item.source_id))
        }
        return {
            "eligiblePrimarySourceCount": len(scope.primary_sources),
            "eligibleFallbackSourceCount": len(scope.fallback_sources),
            "documentsInspectedBySource": {source_id: value["inspected"] for source_id, value in documents_by_source.items()},
            "documentsTruncatedBySource": {
                source_id: value["truncated"]
                for source_id, value in documents_by_source.items()
                if int(value["truncated"] or 0) > 0
            },
            "documentStatsBySource": documents_by_source,
            "rawCandidatesByProviderAndSource": {
                provider: dict(sorted(source_counts.items()))
                for provider, source_counts in sorted(raw_by_provider_source.items())
            },
            "mergedCandidatesBySource": dict(sorted(merged_by_source.items())),
            "selectedAnchorsBySource": dict(sorted(selected_by_source.items())),
            "primaryCandidateCount": primary_raw_count,
            "fallbackCandidateCount": fallback_raw_count,
            "usableCandidateCount": usable_candidate_count,
            "candidateBudgetReached": candidate_budget_reached,
            "sourcesStarved": sorted(stats.source_id for stats in document_stats if stats.starved),
        }

    def _load_search_documents(
        self,
        tokens: Sequence[str],
        eligible_sources: Sequence[QuerySource],
        policy: KnowledgeQueryPolicy,
        include_tests: bool,
        phase: RetrievalPhase,
    ) -> SourceDocumentLoadResult:
        source_ids = [source.source_id for source in eligible_sources if source.source_id]
        if not source_ids:
            return SourceDocumentLoadResult(documents=[], stats=(), truncated=False)
        total_limit = max(1, int(policy.max_search_documents or 1))
        counts = self._search_document_counts(source_ids, eligible_sources, include_tests)
        allocations = self._allocate_source_document_limits(counts, total_limit)
        expected_revision_by_source = {
            source.source_id: source.graph_revision or source.graph_id
            for source in eligible_sources
            if source.source_id and (source.graph_revision or source.graph_id)
        }
        documents: list[SearchDocument] = []
        stats: list[SourceDocumentLoadStats] = []
        for source in sorted(eligible_sources, key=lambda item: (counts.get(item.source_id, 0), item.source_id)):
            source_id = source.source_id
            eligible_count = max(0, int(counts.get(source_id, 0)))
            allocation = max(0, int(allocations.get(source_id, 0)))
            raw_documents = self._query_search_documents(source_id, tokens, allocation, include_tests) if allocation > 0 else []
            source_documents: list[SearchDocument] = []
            for raw_document in raw_documents:
                if not raw_document.get("sourceId") and not raw_document.get("source_id"):
                    continue
                document = SearchDocument.from_graph_node(raw_document)
                if not include_tests and document.flow_domain.upper() == "TEST":
                    continue
                expected_revision = expected_revision_by_source.get(document.source_id)
                actual_revision = document.graph_revision or document.graph_id
                if expected_revision and actual_revision and actual_revision != expected_revision:
                    continue
                source_documents.append(document)
            documents.extend(source_documents)
            inspected = len(source_documents)
            truncated_count = max(0, eligible_count - inspected)
            stats.append(
                SourceDocumentLoadStats(
                    phase=phase,
                    source_id=source_id,
                    eligible_document_count=eligible_count,
                    allocated_document_count=allocation,
                    inspected_document_count=inspected,
                    truncated_document_count=truncated_count,
                    starved=allocation <= 0 and eligible_count > 0,
                )
            )
        documents.sort(key=lambda item: (item.source_id, item.node_kind, (item.label or item.name or "").lower(), item.node_id))
        return SourceDocumentLoadResult(
            documents=documents,
            stats=tuple(stats),
            truncated=any(item.truncated_document_count > 0 or item.starved for item in stats),
        )

    def _search_document_counts(
        self,
        source_ids: Sequence[str],
        eligible_sources: Sequence[QuerySource],
        include_tests: bool,
    ) -> dict[str, int]:
        if hasattr(self.graph_store, "query_search_document_counts"):
            try:
                raw_counts = self.graph_store.query_search_document_counts(list(source_ids), include_tests=include_tests)
            except TypeError:
                raw_counts = self.graph_store.query_search_document_counts(list(source_ids))
            return {str(source_id): max(0, int(raw_counts.get(source_id, 0))) for source_id in source_ids}
        return {
            source.source_id: max(0, int(source.node_count or 0))
            for source in eligible_sources
            if source.source_id in set(source_ids)
        }

    def _allocate_source_document_limits(self, counts: Mapping[str, int], total_limit: int) -> dict[str, int]:
        positive_counts = {str(source_id): max(0, int(count or 0)) for source_id, count in counts.items() if int(count or 0) > 0}
        allocations = {str(source_id): 0 for source_id in counts}
        if not positive_counts:
            return allocations
        remaining_budget = min(max(1, int(total_limit or 1)), sum(positive_counts.values()))
        active = set(positive_counts)
        while active and remaining_budget > 0:
            share = max(1, remaining_budget // len(active))
            progressed = False
            for source_id in sorted(active, key=lambda value: (positive_counts[value] - allocations[value], value)):
                need = positive_counts[source_id] - allocations[source_id]
                amount = min(share, need, remaining_budget)
                if amount <= 0:
                    continue
                allocations[source_id] += amount
                remaining_budget -= amount
                progressed = True
                if remaining_budget <= 0:
                    break
            active = {source_id for source_id in active if allocations[source_id] < positive_counts[source_id]}
            if not progressed:
                break
        while active and remaining_budget > 0:
            for source_id in sorted(active, key=lambda value: (-(positive_counts[value] - allocations[value]), value)):
                if remaining_budget <= 0:
                    break
                if allocations[source_id] >= positive_counts[source_id]:
                    continue
                allocations[source_id] += 1
                remaining_budget -= 1
            active = {source_id for source_id in active if allocations[source_id] < positive_counts[source_id]}
        return allocations

    def _query_search_documents(
        self,
        source_id: str,
        tokens: Sequence[str],
        allocation: int,
        include_tests: bool,
    ) -> list[dict[str, Any]]:
        safe_limit = max(0, int(allocation or 0))
        if safe_limit <= 0:
            return []
        if hasattr(self.graph_store, "query_search_documents"):
            try:
                return list(self.graph_store.query_search_documents([source_id], safe_limit, include_tests=include_tests))
            except TypeError:
                return list(self.graph_store.query_search_documents([source_id], safe_limit))
        if hasattr(self.graph_store, "query_anchor_candidates"):
            try:
                return list(self.graph_store.query_anchor_candidates(list(tokens), [source_id], safe_limit, include_tests=include_tests))
            except TypeError:
                return list(self.graph_store.query_anchor_candidates(list(tokens), [source_id], safe_limit))
        return []

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
    seed_expansions: set[AnchorSeedExpansion]
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
        origin_anchor: KnowledgeQueryMatchedNode | None = None,
    ) -> tuple[AnchorNodeKey | None, bool]:
        key = self.node_key(node)
        if key is None:
            return None, False
        seed_expansions = self._seed_expansions(origin_anchor, node, reasons)
        existing = self.items.get(key)
        if existing is None:
            self.items[key] = _MutableExpandedAnchor(
                node=node,
                roles=set(roles),
                reasons=set(reasons),
                origin_node_ids={origin_node_id} if origin_node_id else set(),
                seed_expansions=set(seed_expansions),
                score=float(score),
                order=self._next_order,
            )
            self._next_order += 1
            if original:
                self.original_keys.add(key)
            return key, True

        existing.roles.update(roles)
        existing.reasons.update(reasons)
        existing.seed_expansions.update(seed_expansions)
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
                    originNodeIds=tuple(sorted({record.original_node_id for record in item.seed_expansions} or item.origin_node_ids)),
                    score=item.score,
                    seedExpansions=tuple(sorted(item.seed_expansions, key=self._seed_expansion_sort_key)),
                )
            )
        return anchors

    def _seed_expansions(
        self,
        origin_anchor: KnowledgeQueryMatchedNode | None,
        expanded_seed: KnowledgeQueryMatchedNode,
        reasons: set[AnchorExpansionReason],
    ) -> tuple[AnchorSeedExpansion, ...]:
        if origin_anchor is None:
            return ()
        records = []
        for reason in reasons:
            records.append(
                AnchorSeedExpansion(
                    original_source_id=str(origin_anchor.sourceId or ""),
                    original_graph_revision=str(origin_anchor.graphRevision or origin_anchor.graphId or ""),
                    original_node_id=str(origin_anchor.nodeId or ""),
                    original_stable_key=str(origin_anchor.stableKey or origin_anchor.nodeId or ""),
                    expanded_seed_source_id=str(expanded_seed.sourceId or ""),
                    expanded_seed_graph_revision=str(expanded_seed.graphRevision or expanded_seed.graphId or ""),
                    expanded_seed_node_id=str(expanded_seed.nodeId or ""),
                    expanded_seed_stable_key=str(expanded_seed.stableKey or expanded_seed.nodeId or ""),
                    reason=reason,
                )
            )
        return tuple(sorted(set(records), key=self._seed_expansion_sort_key))

    def _seed_expansion_sort_key(self, item: AnchorSeedExpansion) -> tuple[str, str, str, str, str, str, str, str, int]:
        return (
            item.expanded_seed_source_id,
            item.expanded_seed_graph_revision,
            item.expanded_seed_stable_key,
            item.expanded_seed_node_id,
            item.original_source_id,
            item.original_graph_revision,
            item.original_stable_key,
            item.original_node_id,
            _ANCHOR_REASON_ORDER[item.reason],
        )


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
            accumulator.add_anchor(node, roles, {reason}, origin.nodeId, node.score, origin_anchor=origin)

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
            origin_anchor=candidate,
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
        self.local_unit_selector = LocalFlowUnitSelector(
            min_relevance_score=self.policy.plan_flow_min_relevance_score,
            top_delta=self.policy.plan_flow_top_delta,
        )
        self.boundary_resolver = GenericBoundaryResolver()
        self.end_to_end_assembler = EndToEndFlowAssembler()
        self.graph_selector = EndToEndGraphSelector()
        self.graph_projector = EndToEndProjectionBuilder()

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
        seed_provenance = self._flow_seed_provenance(anchor_result)
        request_flow_limit = max(1, min(int(request.maxFlows or 10), 10))
        build_result = self.flow_engine.build(
            flow_seed_nodes,
            max_flows=0,
            include_tests=bool(request.includeTests),
            anchor_seed_provenance=seed_provenance,
        )
        selection_started = time.monotonic()
        local_unit_selection = self.local_unit_selector.select(
            build_result.local_units,
            code_identifiers=tuple(plan.code_identifiers if plan is not None else ()),
        )
        selection_ms = (time.monotonic() - selection_started) * 1000
        continuation_result = self._assemble_generic_boundary_continuations(
            local_unit_selection.selected_units,
            eligible_sources,
            include_tests=bool(request.includeTests),
        )
        resolver_incomplete = self._boundary_resolution_incomplete(continuation_result.boundary_resolution)
        assembly_started = time.monotonic()
        end_to_end_assembly = self.end_to_end_assembler.assemble(
            continuation_result.local_units,
            query_entry_unit_ids=continuation_result.initial_selected_local_unit_ids,
            boundary_resolution=continuation_result.boundary_resolution,
            resolver_truncated=resolver_incomplete,
        )
        assembly_ms = (time.monotonic() - assembly_started) * 1000
        graph_selection = self.graph_selector.select(
            end_to_end_assembly.graphs,
            score_by_unit_id=local_unit_selection.score_by_unit_id,
            selected_initial_unit_ids=local_unit_selection.selected_unit_ids,
            max_graphs=request_flow_limit,
        )
        public_graphs = self.graph_projector.graphs(graph_selection.selected_graphs)
        diagnostics.extend(continuation_result.diagnostics)
        diagnostics.extend(self._end_to_end_assembly_diagnostic(item) for item in end_to_end_assembly.diagnostics)
        diagnostics.extend(self._selection_diagnostic(item) for item in local_unit_selection.diagnostics)
        diagnostics.extend(self._selection_diagnostic(item) for item in graph_selection.diagnostics)
        repository_metric_delta = self._repository_metric_delta(repository_metrics_before, self._repository_metrics())
        request_traversal_stats = dict(build_result.traversal_stats or {})
        request_traversal_stats.update(repository_metric_delta)
        diagnostics.extend(build_result.diagnostics)
        diagnostics.append(KnowledgeQueryDiagnostic(
            code="ENTRYPOINT_FLOW_TIMINGS",
            message="Entrypoint flow query stage timings.",
            severity="INFO",
            metadata={
                "candidateSearchMs": round(candidate_ms, 3),
                "localUnitSelectionDurationMs": round(selection_ms, 3),
                **(build_result.stage_timings_ms or {}),
                "boundaryResolutionDurationMs": self._boundary_resolution_duration_ms(continuation_result.boundary_resolution),
                "endToEndAssemblyDurationMs": round(assembly_ms, 3),
                "totalMs": round((time.monotonic() - query_started) * 1000, 3),
                **request_traversal_stats,
                "selectedLocalUnitCount": len(local_unit_selection.selected_unit_ids),
                "rejectedLocalUnitCount": len(local_unit_selection.rejected_unit_ids),
                "discoveredGraphCount": len(end_to_end_assembly.graphs),
                "selectedGraphCount": len(graph_selection.selected_graphs),
                "omittedGraphCount": len(graph_selection.omitted_graph_ids),
                "provenTransitionCount": sum(graph.coverage.proven_cross_source_transition_count for graph in graph_selection.selected_graphs),
                "openAmbiguousBoundaryCount": sum(graph.coverage.open_ambiguous_boundary_count for graph in graph_selection.selected_graphs),
                "openUnresolvedBoundaryCount": sum(graph.coverage.open_unresolved_boundary_count for graph in graph_selection.selected_graphs),
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
                graphs=public_graphs,
                coverage=KnowledgeQueryCoverage(
                    searchedSourceCount=len(eligible_sources),
                    matchedSourceCount=len(matched_sources),
                    matchedNodeCount=len(matched_nodes),
                    discoveredGraphCount=len(end_to_end_assembly.graphs),
                    returnedGraphCount=len(graph_selection.selected_graphs),
                    omittedGraphCount=len(graph_selection.omitted_graph_ids),
                    maxFlows=request_flow_limit,
                    selectedLocalUnitCount=len(local_unit_selection.selected_unit_ids),
                    localUnitCount=len(continuation_result.local_units),
                    nodeCount=sum(graph.coverage.local_node_count for graph in graph_selection.selected_graphs),
                    edgeCount=sum(graph.coverage.local_execution_transition_count + graph.coverage.proven_cross_source_transition_count for graph in graph_selection.selected_graphs),
                    evidenceCount=sum(len(ref.local_unit.evidence) for graph in graph_selection.selected_graphs for ref in graph.unit_refs),
                    provenTransitionCount=sum(graph.coverage.proven_cross_source_transition_count for graph in graph_selection.selected_graphs),
                    openAmbiguousBoundaryCount=sum(graph.coverage.open_ambiguous_boundary_count for graph in graph_selection.selected_graphs),
                    openUnresolvedBoundaryCount=sum(graph.coverage.open_unresolved_boundary_count for graph in graph_selection.selected_graphs),
                    truncated=candidate_result.truncated or anchor_result.truncated or graph_selection.truncated or resolver_incomplete or end_to_end_assembly.truncated,
                    continuationAvailable=candidate_result.truncated or anchor_result.truncated or graph_selection.truncated or resolver_incomplete or end_to_end_assembly.truncated,
                ),
                diagnostics=diagnostics,
            ),
            local_unit_selection=local_unit_selection,
            local_units=continuation_result.local_units,
            boundary_resolution=continuation_result.boundary_resolution,
            end_to_end_assembly=end_to_end_assembly,
            graph_selection=graph_selection,
            selected_graphs=graph_selection.selected_graphs,
        )

    def _flow_seed_provenance(self, anchor_result: AnchorExpansionResult) -> tuple[EntrypointFlowSeedProvenance, ...]:
        originals_by_identity: dict[tuple[str, str, str, str], KnowledgeQueryMatchedNode] = {}
        for candidate in anchor_result.original_candidates:
            originals_by_identity.setdefault(self._matched_anchor_identity(candidate), candidate)

        reasons_by_pair: dict[tuple[tuple[str, str, str, str], tuple[str, str, str, str]], set[str]] = defaultdict(set)
        seed_by_identity: dict[tuple[str, str, str, str], KnowledgeQueryMatchedNode] = {}
        for expanded in anchor_result.expanded_anchors:
            if AnchorRole.FLOW_SEED not in expanded.roles:
                continue
            seed_identity = self._matched_anchor_identity(expanded.node)
            seed_by_identity.setdefault(seed_identity, expanded.node)
            for expansion in expanded.seedExpansions:
                original_identity = self._seed_expansion_original_identity(expansion)
                expanded_identity = self._seed_expansion_seed_identity(expansion)
                if expanded_identity != seed_identity:
                    continue
                if original_identity not in originals_by_identity:
                    continue
                reasons_by_pair[(original_identity, expanded_identity)].add(
                    str(expansion.reason.value if isinstance(expansion.reason, AnchorExpansionReason) else expansion.reason)
                )

        specs = [
            EntrypointFlowSeedProvenance(
                original_anchor=originals_by_identity[original_identity],
                expanded_seed=seed_by_identity[expanded_identity],
                anchor_to_seed_reasons=tuple(sorted(reasons)),
            )
            for (original_identity, expanded_identity), reasons in sorted(reasons_by_pair.items())
            if original_identity in originals_by_identity and expanded_identity in seed_by_identity and reasons
        ]

        if specs:
            return tuple(sorted(specs, key=self._flow_seed_provenance_sort_key))

        seen: set[tuple[str, str, str, str, str]] = set()
        fallback_specs: list[EntrypointFlowSeedProvenance] = []
        for seed in anchor_result.flow_seed_nodes:
            key = (seed.sourceId, seed.stableKey, seed.nodeId, seed.stableKey, seed.nodeId)
            if key in seen:
                continue
            seen.add(key)
            fallback_specs.append(
                EntrypointFlowSeedProvenance(
                    original_anchor=seed,
                    expanded_seed=seed,
                    anchor_to_seed_reasons=("ORIGINAL_MATCH",),
                )
            )
        return tuple(sorted(fallback_specs, key=self._flow_seed_provenance_sort_key))

    def _matched_anchor_identity(self, item: KnowledgeQueryMatchedNode) -> tuple[str, str, str, str]:
        return (
            str(item.sourceId or ""),
            str(item.graphRevision or item.graphId or ""),
            str(item.nodeId or ""),
            str(item.stableKey or item.nodeId or ""),
        )

    def _seed_expansion_original_identity(self, item: AnchorSeedExpansion) -> tuple[str, str, str, str]:
        return (
            item.original_source_id,
            item.original_graph_revision,
            item.original_node_id,
            item.original_stable_key,
        )

    def _seed_expansion_seed_identity(self, item: AnchorSeedExpansion) -> tuple[str, str, str, str]:
        return (
            item.expanded_seed_source_id,
            item.expanded_seed_graph_revision,
            item.expanded_seed_node_id,
            item.expanded_seed_stable_key,
        )

    def _flow_seed_provenance_sort_key(self, item: EntrypointFlowSeedProvenance) -> tuple[str, str, str, str, str]:
        return (
            item.expanded_seed.sourceId,
            item.expanded_seed.graphRevision or item.expanded_seed.graphId or "",
            item.expanded_seed.stableKey,
            item.original_anchor.stableKey,
            item.original_anchor.nodeId,
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

    def _assemble_generic_boundary_continuations(
        self,
        initial_local_units: Sequence[LocalFlowUnit],
        eligible_sources: Sequence[QuerySource],
        *,
        include_tests: bool,
    ) -> ContinuationAssemblyResult:
        active_units = tuple(sorted({unit.unit_id: unit for unit in initial_local_units or ()}.values(), key=lambda item: item.unit_id))
        local_units_by_id: dict[str, LocalFlowUnit] = {unit.unit_id: unit for unit in active_units}
        initial_selected_local_unit_ids = tuple(sorted(local_units_by_id))
        if not active_units or not hasattr(self.flow_repository, "find_provided_boundary_candidates"):
            return ContinuationAssemblyResult(
                initial_selected_local_unit_ids,
                tuple(sorted(local_units_by_id.values(), key=lambda item: item.unit_id)),
                None,
            )

        diagnostics: list[BoundaryResolutionDiagnostic] = []
        all_resolutions: list[Any] = []
        all_proven_links: list[Any] = []
        all_ambiguous: list[Any] = []
        target_materializations: list[BoundaryTargetMaterialization] = []
        unresolved: set[Any] = set()
        truncated_required_identities: set[Any] = set()
        discovered_owners: dict[BoundaryOwnerIdentity, BoundaryOwnerIdentity] = {}
        materialized_by_owner: dict[tuple[str, str, str], BoundaryTargetMaterialization] = {}
        visited_required: set[Any] = set()
        visited_owners: set[tuple[str, str, str]] = set()
        visited_units: set[str] = set(local_units_by_id)
        initial_active_unit_ids = set(local_units_by_id)
        pending_units: tuple[LocalFlowUnit, ...] = tuple(sorted(active_units, key=lambda item: item.unit_id))
        eligible_source_ids = [source.source_id for source in eligible_sources]
        round_count = 0
        cycle_count = 0
        resolver_limit_reached = False
        resolver_limit_required_identities: set[Any] = set()
        target_units_considered = 0
        target_units_materialized = 0
        target_units_omitted = 0
        partial_target_materialization_count = 0

        while pending_units:
            round_count += 1
            if round_count > _MAX_BOUNDARY_RESOLUTION_ROUNDS:
                resolver_limit_reached = True
                resolver_limit_required_identities.update(
                    boundary_identity(boundary)
                    for boundary in self._required_boundaries(self._units_with_unvisited_required_boundaries(pending_units, visited_required))
                )
                diagnostics.append(
                    BoundaryResolutionDiagnostic(
                        code="BOUNDARY_RESOLUTION_LIMIT_REACHED",
                        message="Generic boundary resolution reached the internal round limit.",
                        severity="WARN",
                        metadata={"roundLimit": _MAX_BOUNDARY_RESOLUTION_ROUNDS},
                    )
                )
                break
            round_units = self._units_with_unvisited_required_boundaries(pending_units, visited_required)
            required_boundaries = self._required_boundaries(round_units)
            if not required_boundaries:
                break
            for boundary in required_boundaries:
                visited_required.add(boundary_identity(boundary))
            candidate_load = self.flow_repository.find_provided_boundary_candidates(
                required_boundaries,
                eligible_source_ids=eligible_source_ids,
                include_tests=include_tests,
                internal_limits=BoundaryCandidateLoadLimits(),
            )
            truncated_required_identities.update(candidate_load.truncated_required_identities)
            round_result = self.boundary_resolver.resolve(round_units, candidate_load)
            all_resolutions.extend(round_result.resolutions)
            all_proven_links.extend(round_result.proven_links)
            all_ambiguous.extend(round_result.ambiguous_links)
            unresolved.update(round_result.unresolved_boundaries)
            diagnostics.extend(round_result.diagnostics)
            next_units: list[LocalFlowUnit] = []
            for link in round_result.proven_links:
                owner_key = (link.target_owner.source_id, link.target_owner.graph_revision, link.target_owner.owner_node_id)
                discovered_owners.setdefault(link.target_owner, link.target_owner)
                if owner_key in visited_owners:
                    cycle_count += 1
                    diagnostics.append(
                        BoundaryResolutionDiagnostic(
                            code="BOUNDARY_RESOLUTION_CYCLE_DETECTED",
                            message="Generic boundary resolution cycle detected; owner was not materialized again.",
                            severity="INFO",
                            source_id=link.target_owner.source_id,
                            metadata={"targetOwner": self._target_owner_metadata(link.target_owner)},
                        )
                    )
                    existing = materialized_by_owner.get(owner_key)
                    if existing is not None:
                        target_materializations.append(
                            self._boundary_target_materialization(
                                link,
                                target_local_unit_ids=existing.target_local_unit_ids,
                                omitted_target_local_unit_ids=existing.omitted_target_local_unit_ids,
                                seed_identities=existing.expanded_target_seed_identities,
                                seed_relations=existing.owner_to_seed_reasons,
                                status=existing.materialization_status,
                                diagnostics=(
                                    BoundaryResolutionDiagnostic(
                                        code="BOUNDARY_RESOLUTION_CYCLE_DETECTED",
                                        message="Generic boundary resolution cycle reused an existing target materialization.",
                                        severity="INFO",
                                        source_id=link.target_owner.source_id,
                                        metadata={"targetOwner": self._target_owner_metadata(link.target_owner)},
                                    ),
                                ),
                            )
                        )
                    continue
                visited_owners.add(owner_key)
                target_result = self._materialize_boundary_target_owner(
                    link.target_owner,
                    eligible_sources,
                    include_tests=include_tests,
                )
                seed_identities = self._target_seed_identities(target_result.target_seed_provenance)
                seed_relations = self._target_seed_relations(target_result.target_seed_provenance)
                candidate_target_units = tuple(sorted(target_result.local_units, key=lambda item: item.unit_id))
                target_units_considered += len(candidate_target_units)
                if not target_result.local_units:
                    target_diagnostic = BoundaryResolutionDiagnostic(
                        code="BOUNDARY_TARGET_UNIT_NOT_MATERIALIZED",
                        message="A proven boundary target owner could not be materialized as a local unit.",
                        severity="WARN",
                        source_id=link.target_owner.source_id,
                        metadata={"targetOwner": self._target_owner_metadata(link.target_owner)},
                    )
                    diagnostics.append(
                        target_diagnostic
                    )
                    target_materializations.append(
                        self._boundary_target_materialization(
                            link,
                            target_local_unit_ids=(),
                            omitted_target_local_unit_ids=(),
                            seed_identities=seed_identities,
                            seed_relations=seed_relations,
                            status=BoundaryTargetMaterializationStatus.NOT_MATERIALIZED,
                            diagnostics=(*self._continuation_boundary_diagnostics(target_result.diagnostics), target_diagnostic),
                        )
                    )
                    continue
                retained_target_unit_ids: list[str] = []
                omitted_target_unit_ids: list[str] = []
                target_limit_diagnosed = False
                for unit in candidate_target_units:
                    if unit.unit_id in visited_units:
                        retained_target_unit_ids.append(unit.unit_id)
                        cycle_count += 1
                        diagnostics.append(
                            BoundaryResolutionDiagnostic(
                                code="BOUNDARY_RESOLUTION_CYCLE_DETECTED",
                                message="Generic boundary resolution cycle detected; local unit was not materialized again.",
                                severity="INFO",
                                source_id=unit.source_id,
                                metadata={"targetUnitId": unit.unit_id},
                            )
                        )
                        continue
                    if target_units_materialized >= _MAX_BOUNDARY_TARGET_UNITS:
                        resolver_limit_reached = True
                        omitted_target_unit_ids.append(unit.unit_id)
                        target_units_omitted += 1
                        if not target_limit_diagnosed:
                            target_limit_diagnosed = True
                            diagnostics.append(
                                BoundaryResolutionDiagnostic(
                                    code="BOUNDARY_RESOLUTION_LIMIT_REACHED",
                                    message="Generic boundary resolution reached the internal target-unit limit.",
                                    severity="WARN",
                                    metadata={"targetUnitLimit": _MAX_BOUNDARY_TARGET_UNITS},
                                )
                            )
                        continue
                    visited_units.add(unit.unit_id)
                    local_units_by_id[unit.unit_id] = unit
                    next_units.append(unit)
                    retained_target_unit_ids.append(unit.unit_id)
                    target_units_materialized += 1
                if omitted_target_unit_ids:
                    partial_target_materialization_count += 1
                materialization_status = (
                    BoundaryTargetMaterializationStatus.PARTIAL
                    if omitted_target_unit_ids
                    else BoundaryTargetMaterializationStatus.MATERIALIZED
                )
                materialization = self._boundary_target_materialization(
                    link,
                    target_local_unit_ids=retained_target_unit_ids,
                    omitted_target_local_unit_ids=omitted_target_unit_ids,
                    seed_identities=seed_identities,
                    seed_relations=seed_relations,
                    status=materialization_status,
                    diagnostics=(
                        *self._continuation_boundary_diagnostics(target_result.diagnostics),
                        *(
                            (
                                BoundaryResolutionDiagnostic(
                                    code="BOUNDARY_TARGET_MATERIALIZATION_PARTIAL",
                                    message="Boundary target materialization retained only the target local units accepted before the target-unit limit.",
                                    severity="WARN",
                                    source_id=link.target_owner.source_id,
                                    metadata={
                                        "targetUnitsConsidered": len(candidate_target_units),
                                        "targetUnitsRetained": len(retained_target_unit_ids),
                                        "targetUnitsOmitted": len(omitted_target_unit_ids),
                                        "omittedTargetLocalUnitIds": tuple(sorted(omitted_target_unit_ids)),
                                    },
                                ),
                            )
                            if omitted_target_unit_ids
                            else ()
                        ),
                    ),
                )
                target_materializations.append(materialization)
                materialized_by_owner.setdefault(owner_key, materialization)
            if resolver_limit_reached:
                resolver_limit_required_identities.update(
                    boundary_identity(boundary)
                    for boundary in self._required_boundaries(self._units_with_unvisited_required_boundaries(next_units, visited_required))
                )
                break
            pending_units = tuple(sorted(next_units, key=lambda item: item.unit_id))

        boundary_result = self._combined_boundary_resolution_result(
            all_resolutions,
            all_proven_links,
            all_ambiguous,
            unresolved,
            truncated_required_identities,
            discovered_owners,
            tuple(sorted((unit for unit in local_units_by_id.values() if unit.unit_id not in initial_active_unit_ids), key=lambda item: item.unit_id)),
            diagnostics,
            round_count,
            cycle_count,
            resolver_limit_reached,
            resolver_limit_required_identities,
            target_units_considered,
            target_units_materialized,
            target_units_omitted,
            partial_target_materialization_count,
            target_materializations,
        )
        public_diagnostics = tuple(self._boundary_resolution_diagnostic(item) for item in boundary_result.diagnostics)
        return ContinuationAssemblyResult(
            initial_selected_local_unit_ids,
            tuple(sorted(local_units_by_id.values(), key=lambda item: item.unit_id)),
            boundary_result,
            diagnostics=public_diagnostics,
        )

    def _materialize_boundary_target_owner(
        self,
        owner: BoundaryOwnerIdentity,
        eligible_sources: Sequence[QuerySource],
        *,
        include_tests: bool,
    ) -> ContinuationAssemblyResult:
        node_key = (owner.source_id, owner.graph_revision, owner.owner_node_id)
        loaded = self.flow_repository.load_nodes({node_key}, include_tests=include_tests) if hasattr(self.flow_repository, "load_nodes") else {}
        node = next((item for key, item in loaded.items() if key[0] == owner.source_id and key[2] == owner.owner_node_id), None)
        if node is None:
            return ContinuationAssemblyResult((), (), None)
        anchor = KnowledgeQueryMatchedNode(
            sourceId=node.source_id,
            nodeId=node.node_id,
            stableKey=node.stable_key or node.node_id,
            nodeKind=node.node_kind,
            label=node.label,
            score=1.0,
            matchReasons=["GENERIC_BOUNDARY_RESOLUTION"],
            graphId=node.graph_id,
            graphRevision=node.graph_revision or node.graph_id,
            relativePath=node.relative_path,
            qualifiedName=node.qualified_name,
            flowDomain=node.flow_domain,
        )
        if str(node.node_kind or "").strip().upper() == "CALLABLE":
            flow_seed_nodes = [anchor]
            seed_provenance = (
                EntrypointFlowSeedProvenance(
                    original_anchor=anchor,
                    expanded_seed=anchor,
                    anchor_to_seed_reasons=("GENERIC_BOUNDARY_PROVIDED_OWNER",),
                ),
            )
        else:
            expansion = self.anchor_expander.expand([anchor], eligible_sources, self.policy)
            flow_seed_nodes = [seed for seed in expansion.flow_seed_nodes if self._matched_node_is_executable(seed)]
            seed_provenance = self._flow_seed_provenance(expansion)
        if not flow_seed_nodes:
            return ContinuationAssemblyResult((), (), None, seed_provenance)
        build_result = self.flow_engine.build(
            flow_seed_nodes,
            max_flows=0,
            include_tests=include_tests,
            anchor_seed_provenance=seed_provenance,
        )
        if not build_result.flows:
            return ContinuationAssemblyResult((), build_result.local_units, None, seed_provenance, tuple(build_result.diagnostics))
        return ContinuationAssemblyResult(
            (),
            build_result.local_units,
            None,
            seed_provenance,
            tuple(build_result.diagnostics),
        )

    def _units_with_unvisited_required_boundaries(
        self,
        units: Sequence[LocalFlowUnit],
        visited_required: set[Any],
    ) -> tuple[LocalFlowUnit, ...]:
        result: list[LocalFlowUnit] = []
        for unit in units:
            retained = tuple(
                boundary
                for boundary in unit.generic_boundaries
                if str(boundary.role or "").strip().upper() == BOUNDARY_ROLE_REQUIRED
                and boundary_identity(boundary) not in visited_required
            )
            if retained:
                result.append(replace(unit, generic_boundaries=retained))
        return tuple(sorted(result, key=lambda item: item.unit_id))

    def _required_boundaries(self, units: Sequence[LocalFlowUnit]) -> tuple[LocalBoundaryFact, ...]:
        by_identity: dict[Any, LocalBoundaryFact] = {}
        for unit in units:
            for boundary in unit.generic_boundaries:
                if str(boundary.role or "").strip().upper() == BOUNDARY_ROLE_REQUIRED:
                    by_identity.setdefault(boundary_identity(boundary), boundary)
        return tuple(by_identity[key] for key in sorted(by_identity))

    def _matched_node_is_executable(self, node: KnowledgeQueryMatchedNode) -> bool:
        return str(node.nodeKind or "").strip().upper() == "CALLABLE"

    def _empty_boundary_resolution_result(
        self,
        diagnostics: Sequence[BoundaryResolutionDiagnostic],
        *,
        active_unit_provenance_missing: bool = False,
        active_unit_ids: Sequence[str] = (),
    ) -> BoundaryResolutionResult:
        metrics = BoundaryResolverMetrics()
        aggregate = BoundaryResolutionDiagnostic(
            code="GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS",
            message="Generic boundary resolution diagnostics.",
            severity="INFO",
            metadata={
                "requiredBoundaryCount": 0,
                "eligibleProvidedBoundaryCount": 0,
                "providedCandidatesBySource": {},
                "descriptorFingerprintsQueried": 0,
                "candidatePairsEvaluated": 0,
                "PROVENCount": 0,
                "AMBIGUOUSCount": 0,
                "UNRESOLVEDCount": 0,
                "candidateSetsTruncated": 0,
                "conflictCount": 0,
                "evidenceInsufficientCount": 0,
                "targetOwnersDiscovered": 0,
                "targetUnitsConsidered": 0,
                "targetUnitsMaterialized": 0,
                "targetUnitsOmitted": 0,
                "partialTargetMaterializationCount": 0,
                "resolutionRounds": 0,
                "resolutionCyclesDetected": 0,
                "resolverSQLStatements": 0,
                "candidateDescriptorRowsScanned": 0,
                "candidateDescriptorRowsMatchedExactly": 0,
                "candidateDescriptorRowBudget": 0,
                "candidateDescriptorScanTruncated": False,
                "candidateSourcesInspected": 0,
                "candidateSourcesTruncated": 0,
                "candidatePagesLoaded": 0,
                "requiredCandidateSetsIncomplete": 0,
            },
        )
        return BoundaryResolutionResult(
            resolutions=(),
            proven_links=(),
            ambiguous_links=(),
            unresolved_boundaries=(),
            discovered_provided_owners=(),
            diagnostics=(*tuple(diagnostics), aggregate),
            truncation=BoundaryResolutionTruncationState(
                active_unit_provenance_missing=active_unit_provenance_missing,
                active_unit_ids=tuple(sorted({str(item or "") for item in active_unit_ids if str(item or "")})),
            ),
            metrics=metrics,
        )

    def _boundary_resolution_incomplete(self, boundary_result: BoundaryResolutionResult | None) -> bool:
        if boundary_result is None:
            return False
        truncation = boundary_result.truncation
        return (
            truncation.candidate_sets_truncated > 0
            or truncation.resolver_limit_reached
            or truncation.recursion_limit_reached
            or truncation.candidate_descriptor_scan_truncated
            or truncation.active_unit_provenance_missing
        )

    def _combined_boundary_resolution_result(
        self,
        resolutions: Sequence[Any],
        proven_links: Sequence[Any],
        ambiguous_links: Sequence[Any],
        unresolved_boundaries: set[Any],
        truncated_required_identities: set[Any],
        discovered_owners: Mapping[BoundaryOwnerIdentity, BoundaryOwnerIdentity],
        discovered_local_units: Sequence[LocalFlowUnit],
        diagnostics: Sequence[BoundaryResolutionDiagnostic],
        round_count: int,
        cycle_count: int,
        resolver_limit_reached: bool,
        resolver_limit_required_identities: set[Any],
        target_units_considered: int,
        target_units_materialized: int,
        target_units_omitted: int,
        partial_target_materialization_count: int,
        target_materializations: Sequence[BoundaryTargetMaterialization],
    ) -> BoundaryResolutionResult:
        proven = tuple(sorted(proven_links, key=lambda item: item.resolution_id))
        ambiguous = tuple(sorted(ambiguous_links, key=lambda item: item.resolution_id))
        unresolved = tuple(sorted(unresolved_boundaries))
        candidates_by_source: dict[str, int] = defaultdict(int)
        for item in diagnostics:
            if item.code != "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS":
                continue
            for source_id, count in dict(item.metadata.get("providedCandidatesBySource") or {}).items():
                candidates_by_source[str(source_id)] += int(count or 0)
        metrics = BoundaryResolverMetrics(
            required_boundary_count=len(resolutions),
            eligible_provided_boundary_count=max(
                (
                    int(item.metadata.get("eligibleProvidedBoundaryCount") or 0)
                    for item in diagnostics
                    if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
                ),
                default=0,
            ),
            provided_candidates_by_source=dict(sorted(candidates_by_source.items())),
            descriptor_fingerprints_queried=sum(
                int(item.metadata.get("descriptorFingerprintsQueried") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_pairs_evaluated=sum(len(item.evaluated_candidates) for item in resolutions),
            proven_count=len(proven),
            ambiguous_count=len(ambiguous),
            unresolved_count=len(unresolved),
            candidate_sets_truncated=len(truncated_required_identities),
            conflict_count=sum(
                1
                for resolution in resolutions
                for candidate in resolution.evaluated_candidates
                if candidate.conflicting_descriptors
            ),
            evidence_insufficient_count=sum(
                1
                for resolution in resolutions
                for candidate in resolution.evaluated_candidates
                if "BOUNDARY_EVIDENCE_INSUFFICIENT" in candidate.rejection_reasons
            ),
            target_owners_discovered=len(discovered_owners),
            target_units_considered=target_units_considered,
            target_units_materialized=target_units_materialized,
            target_units_omitted=target_units_omitted,
            partial_target_materialization_count=partial_target_materialization_count,
            resolution_rounds=round_count,
            resolution_cycles_detected=cycle_count,
            resolver_sql_statements=sum(
                int(item.metadata.get("resolverSQLStatements") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_descriptor_rows_scanned=sum(
                int(item.metadata.get("candidateDescriptorRowsScanned") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_descriptor_rows_matched_exactly=sum(
                int(item.metadata.get("candidateDescriptorRowsMatchedExactly") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_descriptor_row_budget=max(
                (
                    int(item.metadata.get("candidateDescriptorRowBudget") or 0)
                    for item in diagnostics
                    if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
                ),
                default=0,
            ),
            candidate_descriptor_scan_truncated=any(
                bool(item.metadata.get("candidateDescriptorScanTruncated"))
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_sources_inspected=sum(
                int(item.metadata.get("candidateSourcesInspected") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_sources_truncated=sum(
                int(item.metadata.get("candidateSourcesTruncated") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            candidate_pages_loaded=sum(
                int(item.metadata.get("candidatePagesLoaded") or 0)
                for item in diagnostics
                if item.code == "GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS"
            ),
            required_candidate_sets_incomplete=len(truncated_required_identities),
        )
        aggregate = BoundaryResolutionDiagnostic(
            code="GENERIC_BOUNDARY_RESOLUTION_DIAGNOSTICS",
            message="Generic boundary resolution diagnostics.",
            severity="INFO",
            metadata={
                "requiredBoundaryCount": metrics.required_boundary_count,
                "eligibleProvidedBoundaryCount": metrics.eligible_provided_boundary_count,
                "providedCandidatesBySource": metrics.provided_candidates_by_source,
                "descriptorFingerprintsQueried": metrics.descriptor_fingerprints_queried,
                "candidatePairsEvaluated": metrics.candidate_pairs_evaluated,
                "PROVENCount": metrics.proven_count,
                "AMBIGUOUSCount": metrics.ambiguous_count,
                "UNRESOLVEDCount": metrics.unresolved_count,
                "candidateSetsTruncated": metrics.candidate_sets_truncated,
                "conflictCount": metrics.conflict_count,
                "evidenceInsufficientCount": metrics.evidence_insufficient_count,
                "targetOwnersDiscovered": metrics.target_owners_discovered,
                "targetUnitsConsidered": metrics.target_units_considered,
                "targetUnitsMaterialized": metrics.target_units_materialized,
                "targetUnitsOmitted": metrics.target_units_omitted,
                "partialTargetMaterializationCount": metrics.partial_target_materialization_count,
                "resolutionRounds": metrics.resolution_rounds,
                "resolutionCyclesDetected": metrics.resolution_cycles_detected,
                "resolverSQLStatements": metrics.resolver_sql_statements,
                "candidateDescriptorRowsScanned": metrics.candidate_descriptor_rows_scanned,
                "candidateDescriptorRowsMatchedExactly": metrics.candidate_descriptor_rows_matched_exactly,
                "candidateDescriptorRowBudget": metrics.candidate_descriptor_row_budget,
                "candidateDescriptorScanTruncated": metrics.candidate_descriptor_scan_truncated,
                "candidateSourcesInspected": metrics.candidate_sources_inspected,
                "candidateSourcesTruncated": metrics.candidate_sources_truncated,
                "candidatePagesLoaded": metrics.candidate_pages_loaded,
                "requiredCandidateSetsIncomplete": metrics.required_candidate_sets_incomplete,
            },
        )
        return BoundaryResolutionResult(
            resolutions=tuple(sorted(resolutions, key=lambda item: item.resolution_id)),
            proven_links=proven,
            ambiguous_links=ambiguous,
            unresolved_boundaries=unresolved,
            discovered_provided_owners=tuple(sorted(discovered_owners.values(), key=lambda item: (item.source_id, item.graph_revision, item.owner_node_id))),
            discovered_local_units=tuple(sorted(discovered_local_units, key=lambda item: item.unit_id)),
            target_materializations=tuple(sorted(target_materializations, key=lambda item: (item.resolution_id, item.selected_provided_boundary_identity, item.target_owner_identity))),
            diagnostics=(*tuple(diagnostics), aggregate),
            truncation=BoundaryResolutionTruncationState(
                candidate_sets_truncated=metrics.candidate_sets_truncated,
                resolver_limit_reached=resolver_limit_reached,
                recursion_limit_reached=round_count > _MAX_BOUNDARY_RESOLUTION_ROUNDS,
                candidate_descriptor_scan_truncated=metrics.candidate_descriptor_scan_truncated,
                truncated_required_identities=tuple(sorted(truncated_required_identities)),
                descriptor_scan_truncated_required_identities=tuple(sorted(truncated_required_identities))
                if metrics.candidate_descriptor_scan_truncated
                else (),
                resolver_limit_required_identities=tuple(sorted(resolver_limit_required_identities)),
            ),
            metrics=metrics,
        )

    def _boundary_target_materialization(
        self,
        link: Any,
        *,
        target_local_unit_ids: Sequence[str],
        omitted_target_local_unit_ids: Sequence[str] = (),
        seed_identities: Sequence[BoundaryTargetSeedIdentity],
        seed_relations: Sequence[BoundaryTargetSeedRelation],
        status: BoundaryTargetMaterializationStatus,
        diagnostics: Sequence[BoundaryResolutionDiagnostic],
    ) -> BoundaryTargetMaterialization:
        return BoundaryTargetMaterialization(
            resolution_id=link.resolution_id,
            selected_provided_boundary_identity=link.provided_boundary_identity,
            target_owner_identity=link.target_owner,
            target_local_unit_ids=tuple(sorted({str(item or "") for item in target_local_unit_ids if str(item or "")})),
            expanded_target_seed_identities=tuple(sorted(set(seed_identities))),
            owner_to_seed_reasons=tuple(sorted(set(seed_relations))),
            materialization_status=status,
            diagnostics=tuple(sorted(diagnostics, key=lambda item: (item.code, item.source_id or "", item.message))),
            omitted_target_local_unit_ids=tuple(sorted({str(item or "") for item in omitted_target_local_unit_ids if str(item or "")})),
        )

    def _target_seed_identities(
        self,
        seed_provenance: Sequence[EntrypointFlowSeedProvenance],
    ) -> tuple[BoundaryTargetSeedIdentity, ...]:
        identities = {
            BoundaryTargetSeedIdentity(
                source_id=str(item.expanded_seed.sourceId or ""),
                graph_revision=str(item.expanded_seed.graphRevision or item.expanded_seed.graphId or ""),
                node_id=str(item.expanded_seed.nodeId or ""),
                stable_key=str(item.expanded_seed.stableKey or item.expanded_seed.nodeId or ""),
            )
            for item in seed_provenance
            if str(item.expanded_seed.nodeId or "")
        }
        return tuple(sorted(identities))

    def _target_seed_relations(
        self,
        seed_provenance: Sequence[EntrypointFlowSeedProvenance],
    ) -> tuple[BoundaryTargetSeedRelation, ...]:
        reasons_by_seed: dict[BoundaryTargetSeedIdentity, set[str]] = defaultdict(set)
        for item in seed_provenance:
            seed = BoundaryTargetSeedIdentity(
                source_id=str(item.expanded_seed.sourceId or ""),
                graph_revision=str(item.expanded_seed.graphRevision or item.expanded_seed.graphId or ""),
                node_id=str(item.expanded_seed.nodeId or ""),
                stable_key=str(item.expanded_seed.stableKey or item.expanded_seed.nodeId or ""),
            )
            if not seed.node_id:
                continue
            reasons_by_seed[seed].update(str(reason or "") for reason in item.anchor_to_seed_reasons if str(reason or ""))
        return tuple(
            sorted(
                (
                    BoundaryTargetSeedRelation(seed_identity=seed, reasons=tuple(sorted(reasons)))
                    for seed, reasons in reasons_by_seed.items()
                )
            )
        )

    def _continuation_boundary_diagnostics(
        self,
        diagnostics: Sequence[KnowledgeQueryDiagnostic],
    ) -> tuple[BoundaryResolutionDiagnostic, ...]:
        return tuple(
            BoundaryResolutionDiagnostic(
                code=item.code,
                message=item.message,
                severity=item.severity,
                source_id=item.sourceId,
                metadata=dict(item.metadata or {}),
            )
            for item in diagnostics
        )

    def _boundary_resolution_diagnostic(self, item: BoundaryResolutionDiagnostic) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code=item.code,
            message=item.message,
            severity=item.severity,
            sourceId=item.source_id,
            metadata=dict(item.metadata or {}),
        )

    def _end_to_end_assembly_diagnostic(self, item: EndToEndFlowDiagnostic) -> KnowledgeQueryDiagnostic:
        return KnowledgeQueryDiagnostic(
            code=item.code,
            message=item.message,
            severity=item.severity,
            sourceId=item.source_id,
            metadata=dict(item.metadata or {}),
        )

    def _selection_diagnostic(self, item: Mapping[str, Any]) -> KnowledgeQueryDiagnostic:
        metadata = dict(item)
        code = str(metadata.pop("code", "END_TO_END_GRAPH_SELECTION_DIAGNOSTICS"))
        return KnowledgeQueryDiagnostic(
            code=code,
            message="Canonical flow graph selection diagnostics.",
            severity="INFO",
            metadata=metadata,
        )

    def _boundary_resolution_duration_ms(self, boundary_result: BoundaryResolutionResult | None) -> float:
        if boundary_result is None:
            return 0.0
        return 0.0

    def _target_owner_metadata(self, owner: BoundaryOwnerIdentity) -> dict[str, Any]:
        return {
            "sourceId": owner.source_id,
            "graphRevision": owner.graph_revision,
            "ownerNodeId": owner.owner_node_id,
            "boundaryKey": owner.boundary_identity.boundary_key,
        }

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
