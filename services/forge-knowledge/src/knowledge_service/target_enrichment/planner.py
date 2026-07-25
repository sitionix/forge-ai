from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.target_enrichment.registry import AnchorRefRegistry


@dataclass(frozen=True)
class PlannedTargetAnchor:
    ref: str
    stable_key: str
    kind: str


@dataclass(frozen=True)
class LlmEnrichmentPlan:
    registry: AnchorRefRegistry
    targets: tuple[PlannedTargetAnchor, ...]


class LlmEnrichmentPlanner:
    def plan(
        self,
        static_graph: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        *,
        max_target_calls: int,
        source_id: Optional[str] = None,
        relative_path: Optional[str] = None,
    ) -> LlmEnrichmentPlan:
        registry = AnchorRefRegistry.build(static_graph, contract)
        eligible_kinds = set(contract.semantic_node_kinds)
        targets = tuple(
            PlannedTargetAnchor(entry.ref, entry.stable_key, entry.kind)
            for entry in registry.entries
            if entry.kind in eligible_kinds
        )
        if not targets:
            raise KnowledgeError(
                "ANALYSIS_TARGET_PLANNING_EMPTY",
                "No semantically eligible target anchors were available for LLM enrichment.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                allowedNodeKinds=list(contract.allowed_node_kinds),
                semanticNodeKinds=list(contract.semantic_node_kinds),
            )
        if max_target_calls < 1 or len(targets) > max_target_calls:
            raise KnowledgeError(
                "ANALYSIS_TARGET_PLAN_TOO_LARGE",
                "Target-anchor enrichment plan exceeds the configured maximum target calls per file.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                sourceId=source_id,
                relativePath=relative_path,
                targetCount=len(targets),
                maxTargetCalls=max_target_calls,
                semanticNodeKinds=list(contract.semantic_node_kinds),
            )
        return LlmEnrichmentPlan(registry=registry, targets=targets)
