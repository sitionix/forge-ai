from __future__ import annotations

import json
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
            allowed_edge_types=tuple(_string_list(raw.get("allowedEdgeKinds"))),
            allowed_claim_kinds=tuple(_string_list(raw.get("allowedClaimKinds"))),
            allowed_statuses=tuple(_string_list(raw.get("allowedStatuses"))),
            allowed_origins=tuple(_string_list(raw.get("allowedOrigins"))),
            allowed_evidence_kinds=tuple(_string_list(raw.get("allowedEvidenceKinds"))),
            allowed_resolution_statuses=tuple(_string_list(raw.get("allowedResolutionStatuses"))),
            semantic_node_kinds=tuple(_string_list(raw.get("semanticNodeKinds"))),
            semantic_edge_types=tuple(_string_list(raw.get("semanticEdgeKinds"))),
            semantic_claim_kinds=tuple(_string_list(raw.get("semanticClaimKinds"))),
            edge_from_kinds=_edge_endpoint_map(raw.get("edgeEndpointRules"), "from"),
            edge_to_kinds=_edge_endpoint_map(raw.get("edgeEndpointRules"), "to"),
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
            "allowedEdgeKinds": list(self.allowed_edge_types),
            "allowedClaimKinds": list(self.allowed_claim_kinds),
            "allowedStatuses": list(self.allowed_statuses),
            "allowedOrigins": list(self.allowed_origins),
            "allowedEvidenceKinds": list(self.allowed_evidence_kinds),
            "allowedResolutionStatuses": list(self.allowed_resolution_statuses),
            "semanticNodeKinds": list(self.semantic_node_kinds),
            "semanticEdgeKinds": list(self.semantic_edge_types),
            "semanticClaimKinds": list(self.semantic_claim_kinds),
            "edgeEndpointRules": {
                kind: {"from": list(self.edge_from_kinds.get(kind, ())), "to": list(self.edge_to_kinds.get(kind, ()))}
                for kind in self.allowed_edge_types
            },
            "unsupportedBehavior": dict(self.unsupported_behavior),
            "evidenceRules": {
                "lineRangesRequired": self.evidence_required,
                "materialSupportRequired": self.evidence_required,
                "useProvidedContentOnly": True,
            },
        }

    def render_contract_block(self) -> str:
        lines = [
            "Analysis graph contract:",
            f"- formatId: {self.format_id or ''}",
            f"- extractorId: {self.extractor_id or ''}",
            f"- policyId: {self.policy_id or ''}",
            f"- promptId: {self.prompt_id or ''}",
            f"- sourceView: {self.source_view or ''}",
            f"- llmMode: {self.llm_mode or ''}",
            f"- graphProfiles: {_join(self.graph_profiles)}",
            f"- allowedNodeKinds: {_join(self.allowed_node_kinds)}",
            f"- allowedEdgeKinds: {_join(self.allowed_edge_types)}",
            f"- allowedClaimKinds: {_join(self.allowed_claim_kinds)}",
            f"- allowedStatuses: {_join(self.allowed_statuses)}",
            f"- allowedOrigins: {_join(self.allowed_origins)}",
            f"- allowedEvidenceKinds: {_join(self.allowed_evidence_kinds)}",
            f"- allowedResolutionStatuses: {_join(self.allowed_resolution_statuses)}",
            f"- semanticNodeKinds: {_join(self.semantic_node_kinds)}",
            f"- semanticEdgeKinds: {_join(self.semantic_edge_types)}",
            f"- semanticClaimKinds: {_join(self.semantic_claim_kinds)}",
            "- unsupportedBehavior:",
        ]
        lines.extend(f"  - {key}: {value}" for key, value in self.unsupported_behavior.items())
        lines.extend(
            [
                "- evidenceRules:",
                "  - Cite exact source line ranges for every claim or semantic edge.",
                "  - Use only facts materially supported by the provided file content and static anchors.",
                "  - Omit unsupported facts instead of inventing replacements.",
            ]
        )
        return "\n".join(lines)


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


class AnalysisPromptRenderer:
    def __init__(self, policy: Optional[AnalysisPolicy] = None, policy_path: Optional[str | Path] = None):
        self.provider = GraphContractProvider(policy=policy, policy_path=policy_path)
        self.policy = self.provider.policy

    def render_for_payload(
        self,
        payload: Mapping[str, Any],
        contract: AnalysisGraphContract | None = None,
    ) -> str:
        contract = contract or self.provider.resolve_payload(payload)
        prompt_id = _prompt_id(payload) or contract.prompt_id
        template = self._template(prompt_id)
        response_shape = self._response_shape(prompt_id)
        return self.render(template, contract, response_shape)

    def render(self, template: str, contract: AnalysisGraphContract, response_shape: str | None = None) -> str:
        if response_shape is not None:
            template = template.replace("{{GRAPH_RESPONSE_SHAPE}}", response_shape)
        block = contract.render_contract_block()
        if "{{ANALYSIS_GRAPH_CONTRACT}}" in template:
            return template.replace("{{ANALYSIS_GRAPH_CONTRACT}}", block)
        return "\n\n".join(part for part in (template.strip(), block) if part)

    def _template(self, prompt_id: Optional[str]) -> str:
        if not prompt_id:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_REQUIRED",
                "Analysis policy prompt id is required for prompt rendering.",
            )
        if prompt_id not in self.policy.prompts:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_MISSING",
                f"Analysis policy prompt id is not declared: {prompt_id}",
                promptId=prompt_id,
            )
        path = self.policy.prompt_path(prompt_id)
        if not path.exists():
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_FILE_MISSING",
                f"Analysis policy prompt file does not exist: {path}",
                promptId=prompt_id,
                promptPath=str(path),
            )
        return path.read_text(encoding="utf-8")

    def _response_shape(self, prompt_id: str) -> str:
        path = self.policy.prompt_response_shape_path(prompt_id)
        if not path.exists():
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_FILE_MISSING",
                f"Analysis policy response shape file does not exist: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
            )
        text = path.read_text(encoding="utf-8").strip()
        try:
            json.loads(text)
        except json.JSONDecodeError as exc:
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_INVALID_JSON",
                f"Analysis policy response shape file is invalid JSON: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
                jsonError=str(exc),
            ) from exc
        return text


def contract_payload(contract: AnalysisGraphContract) -> dict[str, Any]:
    return contract.to_prompt_dict()


@lru_cache(maxsize=8)
def _load_cached_policy(path: Optional[str]) -> AnalysisPolicy:
    return load_analysis_policy(path or None)


def _prompt_id(payload: Mapping[str, Any]) -> Optional[str]:
    raw = payload.get("analysisPolicy")
    if isinstance(raw, Mapping):
        prompt_id = raw.get("promptId")
        if isinstance(prompt_id, str) and prompt_id:
            return prompt_id
    return None


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


def _join(values: tuple[str, ...]) -> str:
    return ", ".join(values)
