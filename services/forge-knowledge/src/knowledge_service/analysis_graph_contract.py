from __future__ import annotations

from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping, Optional

from knowledge_service.analysis_policy import AnalysisPolicy
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analysis_policy_resolver import AnalysisPolicyResolveRequest, AnalysisPolicyResolution, resolve_analysis_policy
from knowledge_service.errors import KnowledgeError

@dataclass(frozen=True)
class AnalysisGraphContract:
    format_id: Optional[str]
    family: Optional[str]
    extractor_id: Optional[str]
    policy_id: Optional[str]
    prompt_id: Optional[str]
    source_view: Optional[str]
    extractor_mode: Optional[str]
    llm_mode: Optional[str]
    evidence_required: bool
    allow_llm_created_anchors: bool
    trust_llm_created_anchors: bool
    artifact_labels: tuple[str, ...]
    graph_profiles: tuple[str, ...]
    allowed_node_kinds: tuple[str, ...]
    allowed_edge_types: tuple[str, ...]
    allowed_claim_kinds: tuple[str, ...]
    allowed_statuses: tuple[str, ...]
    allowed_origins: tuple[str, ...]
    allowed_evidence_kinds: tuple[str, ...]
    allowed_resolution_statuses: tuple[str, ...]
    semantic_node_kinds: tuple[str, ...]
    semantic_edge_types: tuple[str, ...]
    semantic_claim_kinds: tuple[str, ...]
    edge_from_kinds: Mapping[str, tuple[str, ...]] = field(default_factory=dict)
    edge_to_kinds: Mapping[str, tuple[str, ...]] = field(default_factory=dict)
    resolution_status_rules: Mapping[str, Mapping[str, str]] = field(default_factory=dict)
    unsupported_behavior: Mapping[str, str] = field(default_factory=dict)

    @classmethod
    def from_policy_resolution(cls, policy: AnalysisPolicy, resolution: AnalysisPolicyResolution) -> "AnalysisGraphContract":
        allowed_edge_types = tuple(resolution.allowed_edge_types)
        edge_from_kinds = {
            kind: tuple(policy.graph.edges[kind].from_kinds)
            for kind in allowed_edge_types
            if kind in policy.graph.edges
        }
        edge_to_kinds = {
            kind: tuple(policy.graph.edges[kind].to_kinds)
            for kind in allowed_edge_types
            if kind in policy.graph.edges
        }
        return cls(
            format_id=resolution.format_id,
            family=resolution.family,
            extractor_id=resolution.extractor_id,
            policy_id=resolution.policy_id,
            prompt_id=resolution.prompt_id,
            source_view=resolution.source_view,
            extractor_mode=resolution.extractor_mode,
            llm_mode=resolution.llm_mode,
            evidence_required=resolution.evidence_required,
            allow_llm_created_anchors=resolution.allow_llm_created_anchors,
            trust_llm_created_anchors=resolution.trust_llm_created_anchors,
            artifact_labels=tuple(resolution.artifact_labels),
            graph_profiles=tuple(resolution.effective_graph_profiles),
            allowed_node_kinds=tuple(resolution.allowed_node_kinds),
            allowed_edge_types=tuple(resolution.allowed_edge_types),
            allowed_claim_kinds=tuple(resolution.allowed_claim_kinds),
            allowed_statuses=tuple(resolution.status_kinds),
            allowed_origins=tuple(resolution.origin_kinds),
            allowed_evidence_kinds=tuple(resolution.evidence_kinds),
            allowed_resolution_statuses=tuple(resolution.resolution_statuses),
            semantic_node_kinds=tuple(resolution.semantic_node_kinds),
            semantic_edge_types=tuple(resolution.semantic_edge_types),
            semantic_claim_kinds=tuple(resolution.semantic_claim_kinds),
            edge_from_kinds=edge_from_kinds,
            edge_to_kinds=edge_to_kinds,
            resolution_status_rules=_resolution_status_rules(policy.graph.resolution_statuses, resolution.resolution_statuses),
            unsupported_behavior=dict(resolution.unsupported_behavior),
        )

    @classmethod
    def from_payload(cls, payload: Mapping[str, Any]) -> Optional["AnalysisGraphContract"]:
        raw = payload.get("analysisPolicy")
        if not isinstance(raw, Mapping):
            return None
        return cls(
            format_id=_optional_string(raw.get("formatId")),
            family=_optional_string(raw.get("family")),
            extractor_id=_optional_string(raw.get("extractorId")),
            policy_id=_optional_string(raw.get("policyId")),
            prompt_id=_optional_string(raw.get("promptId")),
            source_view=_optional_string(raw.get("sourceView")),
            extractor_mode=_optional_string(raw.get("extractorMode")),
            llm_mode=_optional_string(raw.get("llmMode")),
            evidence_required=bool(raw.get("evidenceRequired")),
            allow_llm_created_anchors=bool(raw.get("allowLlmCreatedAnchors")),
            trust_llm_created_anchors=bool(raw.get("trustLlmCreatedAnchors")),
            artifact_labels=tuple(_string_list(raw.get("artifactLabels"))),
            graph_profiles=tuple(_string_list(raw.get("graphProfiles"))),
            allowed_node_kinds=tuple(_string_list(raw.get("allowedNodeKinds"))),
            allowed_edge_types=tuple(_string_list(raw.get("allowedEdgeTypes"))),
            allowed_claim_kinds=tuple(_string_list(raw.get("allowedClaimKinds"))),
            allowed_statuses=tuple(_string_list(raw.get("allowedStatuses"))),
            allowed_origins=tuple(_string_list(raw.get("allowedOrigins"))),
            allowed_evidence_kinds=tuple(_string_list(raw.get("allowedEvidenceKinds"))),
            allowed_resolution_statuses=tuple(_string_list(raw.get("allowedResolutionStatuses"))),
            semantic_node_kinds=tuple(_string_list(raw.get("semanticNodeKinds"))),
            semantic_edge_types=tuple(_string_list(raw.get("semanticEdgeTypes"))),
            semantic_claim_kinds=tuple(_string_list(raw.get("semanticClaimKinds"))),
            edge_from_kinds=_edge_endpoint_map(raw.get("edgeEndpointRules"), "from"),
            edge_to_kinds=_edge_endpoint_map(raw.get("edgeEndpointRules"), "to"),
            resolution_status_rules=_resolution_status_rule_payload(raw.get("resolutionStatusRules")),
            unsupported_behavior={str(key): str(value) for key, value in (raw.get("unsupportedBehavior") or {}).items()},
        )

    def to_prompt_dict(self) -> dict[str, Any]:
        return {
            "formatId": self.format_id,
            "family": self.family,
            "extractorId": self.extractor_id,
            "policyId": self.policy_id,
            "promptId": self.prompt_id,
            "sourceView": self.source_view,
            "extractorMode": self.extractor_mode,
            "llmMode": self.llm_mode,
            "evidenceRequired": self.evidence_required,
            "allowLlmCreatedAnchors": self.allow_llm_created_anchors,
            "trustLlmCreatedAnchors": self.trust_llm_created_anchors,
            "artifactLabels": list(self.artifact_labels),
            "graphProfiles": list(self.graph_profiles),
            "allowedNodeKinds": list(self.allowed_node_kinds),
            "allowedEdgeTypes": list(self.allowed_edge_types),
            "allowedClaimKinds": list(self.allowed_claim_kinds),
            "allowedStatuses": list(self.allowed_statuses),
            "allowedOrigins": list(self.allowed_origins),
            "allowedEvidenceKinds": list(self.allowed_evidence_kinds),
            "allowedResolutionStatuses": list(self.allowed_resolution_statuses),
            "semanticNodeKinds": list(self.semantic_node_kinds),
            "semanticEdgeTypes": list(self.semantic_edge_types),
            "semanticClaimKinds": list(self.semantic_claim_kinds),
            "edgeEndpointRules": {
                kind: {"from": list(self.edge_from_kinds.get(kind, ())), "to": list(self.edge_to_kinds.get(kind, ()))}
                for kind in self.allowed_edge_types
            },
            "resolutionStatusRules": {status: dict(rule) for status, rule in self.resolution_status_rules.items()},
            "unsupportedBehavior": dict(self.unsupported_behavior),
            "evidenceRules": {
                "lineRangesRequired": self.evidence_required,
                "materialSupportRequired": self.evidence_required,
                "useProvidedContentOnly": True,
            },
        }

@dataclass(frozen=True)
class ResolutionStatusContract:
    rules: Mapping[str, Mapping[str, str]]

    @classmethod
    def from_graph_contract(cls, contract: AnalysisGraphContract) -> "ResolutionStatusContract":
        return cls(contract.resolution_status_rules)

    def requires_to_ref(self, status: str) -> bool:
        return self._rule(status).get("toRef") == "required"

    def forbids_to_ref(self, status: str) -> bool:
        return self._rule(status).get("toRef") == "forbidden"

    def requires_unresolved_target(self, status: str) -> bool:
        return self._rule(status).get("unresolvedTarget") == "required"

    def allows_unresolved_target(self, status: str) -> bool:
        return self._rule(status).get("unresolvedTarget", "optional") != "forbidden"

    def _rule(self, status: str) -> Mapping[str, str]:
        return self.rules.get(status) or {}


class GraphContractProvider:
    def __init__(self, policy: Optional[AnalysisPolicy] = None, policy_path: Optional[str | Path] = None):
        self.policy = policy or _load_cached_policy(str(policy_path) if policy_path is not None else None)

    def resolve(self, relative_path: str, content: Optional[str] = None) -> AnalysisGraphContract:
        if not relative_path:
            raise KnowledgeError(
                "ANALYSIS_POLICY_RELATIVE_PATH_REQUIRED",
                "Analysis policy resolution requires a non-empty relative path.",
            )
        resolution = resolve_analysis_policy(
            self.policy,
            AnalysisPolicyResolveRequest(relative_path=relative_path, content=content),
        )
        if not resolution.supported:
            raise KnowledgeError(
                resolution.failure_code or "ANALYSIS_POLICY_RESOLUTION_FAILED",
                resolution.failure_message or "Analysis policy resolution failed.",
                relativePath=relative_path,
                extension=resolution.extension,
                formatId=resolution.format_id,
                unsupportedBehavior=dict(resolution.unsupported_behavior),
            )
        return AnalysisGraphContract.from_policy_resolution(self.policy, resolution)

    def resolve_payload(self, payload: Mapping[str, Any]) -> AnalysisGraphContract:
        contract = AnalysisGraphContract.from_payload(payload)
        if contract is not None:
            return contract
        raise KnowledgeError(
            "ANALYSIS_POLICY_CONTRACT_REQUIRED",
            "Analyzer payload must include the resolved YAML analysisPolicy contract.",
            relativePath=str(payload.get("relativePath") or ""),
        )


def contract_payload(contract: AnalysisGraphContract) -> dict[str, Any]:
    return contract.to_prompt_dict()


@lru_cache(maxsize=8)
def _load_cached_policy(path: Optional[str]) -> AnalysisPolicy:
    return load_analysis_policy(path or None)


def _optional_string(value: Any) -> Optional[str]:
    return value if isinstance(value, str) else None


def _string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def _edge_endpoint_map(value: Any, side: str) -> dict[str, tuple[str, ...]]:
    if not isinstance(value, Mapping):
        return {}
    result: dict[str, tuple[str, ...]] = {}
    for kind, rule in value.items():
        if not isinstance(kind, str) or not isinstance(rule, Mapping):
            continue
        result[kind] = tuple(_string_list(rule.get(side)))
    return result


def _resolution_status_rules(raw_rules: Mapping[str, Mapping[str, Any]], allowed_statuses: list[str]) -> dict[str, dict[str, str]]:
    return {
        status: _resolution_status_rule(raw_rules.get(status))
        for status in allowed_statuses
    }


def _resolution_status_rule(value: Any) -> dict[str, str]:
    if not isinstance(value, Mapping):
        return {}
    rule: dict[str, str] = {}
    to_ref = value.get("toRef")
    if isinstance(to_ref, str):
        rule["toRef"] = to_ref
    unresolved_target = value.get("unresolvedTarget")
    if isinstance(unresolved_target, str):
        rule["unresolvedTarget"] = unresolved_target
    return rule


def _resolution_status_rule_payload(value: Any) -> dict[str, dict[str, str]]:
    if not isinstance(value, Mapping):
        return {}
    result: dict[str, dict[str, str]] = {}
    for status, rule in value.items():
        if isinstance(status, str):
            result[status] = _resolution_status_rule(rule)
    return result
