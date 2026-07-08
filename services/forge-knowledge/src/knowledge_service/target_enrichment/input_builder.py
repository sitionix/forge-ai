from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, contract_payload
from knowledge_service.analysis_policy import AnalysisPolicy
from knowledge_service.errors import KnowledgeError
from knowledge_service.target_enrichment.constants import TARGET_INPUT_SCHEMA_VERSION, TARGET_REQUEST_KIND, TARGET_RESPONSE_SCHEMA_VERSION
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
                "edgeType": list(context.graph_contract.allowed_edge_types),
                "resolutionStatus": list(context.graph_contract.allowed_resolution_statuses),
            },
            "endpointRules": {
                edge_type: {
                    "fromKinds": list(context.graph_contract.edge_from_kinds.get(edge_type, ())),
                    "toKinds": list(context.graph_contract.edge_to_kinds.get(edge_type, ())),
                }
                for edge_type in context.graph_contract.allowed_edge_types
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
