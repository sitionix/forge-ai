from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, ResolutionStatusContract, contract_payload
from knowledge_service.analysis_policy import AnalysisPolicy
from knowledge_service.errors import KnowledgeError
from knowledge_service.target_enrichment.constants import TARGET_INPUT_SCHEMA_VERSION, TARGET_REQUEST_KIND
from knowledge_service.target_enrichment.planner import PlannedTargetAnchor
from knowledge_service.target_enrichment.prompt_renderer import TargetPromptRenderer
from knowledge_service.target_enrichment.registry import AnchorRefRegistry


class LlmEnrichmentInputBuilder:
    def __init__(
        self,
        policy: Optional[AnalysisPolicy] = None,
        policy_path: Optional[str | Path] = None,
        response_shape: Optional[Mapping[str, Any]] = None,
    ):
        self._response_shape = _json_copy(response_shape) if response_shape is not None else None
        self._response_shape_loader = None if response_shape is not None else TargetPromptRenderer(policy=policy, policy_path=policy_path)

    def build(
        self,
        *,
        context: Any,
        registry: AnchorRefRegistry,
        target: PlannedTargetAnchor,
        budget_chars: int,
    ) -> dict[str, Any]:
        target_entry = registry.entry_for_ref(target.ref)
        target_allowed_edge_types = _target_allowed_edge_types(context.graph_contract, target_entry.kind)
        allowed_unresolved_statuses = _allowed_unresolved_statuses(context.graph_contract)
        edge_options = _edge_options(context.graph_contract, registry, target_entry.kind, target_allowed_edge_types, allowed_unresolved_statuses)
        llm_input = {
            "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
            "requestKind": TARGET_REQUEST_KIND,
            "file": {
                "sourceId": context.row.get("source_id"),
                "relativePath": context.row.get("relative_path"),
                "language": self._language(context),
                "format": context.policy_resolution.format_id,
                "lineCount": context.line_count,
                "contentLines": [{"line": index, "text": line} for index, line in enumerate(context.content_lines, start=1)],
            },
            "anchorRegistry": registry.to_llm_list(),
            "targetAnchor": target_entry.to_llm_dict(),
            "allowedValues": {
                "claimKind": list(context.graph_contract.allowed_claim_kinds),
                "edgeType": target_allowed_edge_types,
                "unresolvedStatus": allowed_unresolved_statuses,
            },
            "edgeOptions": edge_options,
            "endpointRules": {
                edge_type: {
                    "fromKinds": list(context.graph_contract.edge_from_kinds.get(edge_type, ())),
                    "toKinds": list(context.graph_contract.edge_to_kinds.get(edge_type, ())),
                }
                for edge_type in target_allowed_edge_types
            },
            "responseShape": self.response_shape(contract=context.graph_contract),
        }
        return {
            "sourceId": context.row.get("source_id"),
            "relativePath": context.row.get("relative_path"),
            "targetRef": target.ref,
            "targetKind": target.kind,
            "requestKind": TARGET_REQUEST_KIND,
            "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
            "budgetChars": int(budget_chars),
            "analysisPolicy": contract_payload(context.graph_contract),
            "llmInput": llm_input,
            "_refToStableKey": dict(registry.ref_to_stable_key),
            "_stableKeyToRef": dict(registry.stable_key_to_ref),
            "_refToKind": dict(registry.ref_to_kind),
        }

    def response_shape(
        self,
        *,
        payload: Optional[Mapping[str, Any]] = None,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> dict[str, Any]:
        if self._response_shape is not None:
            return _json_copy(self._response_shape)
        if self._response_shape_loader is None:
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_REQUIRED",
                "Target response shape loader is not configured.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
            )
        return self._response_shape_loader.response_shape(payload=payload, contract=contract)

    def _language(self, context: Any) -> Optional[str]:
        language = str(context.row.get("language") or "").strip().lower()
        if language and language != "unknown":
            return language
        return context.policy_resolution.family or context.policy_resolution.format_id


def _json_copy(value: Mapping[str, Any]) -> dict[str, Any]:
    return json.loads(json.dumps(dict(value)))


def _target_allowed_edge_types(contract: AnalysisGraphContract, target_kind: str) -> list[str]:
    return [
        edge_type
        for edge_type in contract.allowed_edge_types
        if target_kind in set(contract.edge_from_kinds.get(edge_type, ()))
    ]


def _allowed_unresolved_statuses(contract: AnalysisGraphContract) -> list[str]:
    rules = ResolutionStatusContract.from_graph_contract(contract)
    return [
        status
        for status in contract.allowed_resolution_statuses
        if status != "RESOLVED" and not rules.requires_to_ref(status)
    ]


def _edge_options(
    contract: AnalysisGraphContract,
    registry: AnchorRefRegistry,
    target_kind: str,
    target_allowed_edge_types: list[str],
    allowed_unresolved_statuses: list[str],
) -> list[dict[str, Any]]:
    # TODO: Split graph edge types from LLM-emittable edge types in policy.
    entries = registry.entries
    options: list[dict[str, Any]] = []
    for edge_type in target_allowed_edge_types:
        allowed_to_kinds = list(contract.edge_to_kinds.get(edge_type, ()))
        allowed_to_set = set(allowed_to_kinds)
        to_refs = [
            {
                "ref": entry.ref,
                "kind": entry.kind,
                "name": entry.name,
            }
            for entry in entries
            if entry.kind in allowed_to_set
        ]
        options.append(
            {
                "edgeType": edge_type,
                "fromKind": target_kind,
                "allowedToKinds": allowed_to_kinds,
                "toRefs": to_refs,
                "unresolvedStatuses": list(allowed_unresolved_statuses),
            }
        )
    return options
