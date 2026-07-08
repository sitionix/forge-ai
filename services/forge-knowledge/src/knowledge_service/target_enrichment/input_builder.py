from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Mapping, Optional

from knowledge_service.analysis_graph_contract import contract_payload
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
            "targetAnchor": _anchor_context(target_entry),
            "contextAnchors": [_anchor_context(entry, role="context") for entry in registry.entries if entry.ref != target_entry.ref],
            "claimScope": _claim_scope(target_entry),
            "allowedValues": {
                "claimKind": list(context.graph_contract.allowed_claim_kinds),
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


def _anchor_context(entry: Any, *, role: Optional[str] = None) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "kind": entry.kind,
        "name": entry.name,
        "qualifiedName": entry.qualified_name,
        "lineStart": entry.line_start,
        "lineEnd": entry.line_end,
    }
    if getattr(entry, "body_line_start", None) is not None:
        payload["bodyLineStart"] = entry.body_line_start
    if getattr(entry, "body_line_end", None) is not None:
        payload["bodyLineEnd"] = entry.body_line_end
    if entry.signature:
        payload["signature"] = entry.signature
    if entry.return_type:
        payload["returnType"] = entry.return_type
    if entry.type_name:
        payload["typeName"] = entry.type_name
    if entry.annotations:
        payload["annotations"] = [dict(item) for item in entry.annotations]
    if role:
        payload["role"] = role
    return {key: value for key, value in payload.items() if value is not None}


def _claim_scope(entry: Any) -> dict[str, Any]:
    kind = str(entry.kind or "")
    base: dict[str, Any] = {
        "targetKind": kind,
        "targetName": entry.name,
        "targetLineStart": entry.line_start,
        "targetLineEnd": entry.line_end,
    }
    if getattr(entry, "body_line_start", None) is not None:
        base["bodyLineStart"] = entry.body_line_start
    if getattr(entry, "body_line_end", None) is not None:
        base["bodyLineEnd"] = entry.body_line_end
    if kind == "FILE":
        base["rules"] = [
            "Describe file-level purpose only.",
            "Do not summarize individual methods as FILE claims.",
            "If only method-level behavior is grounded, return no FILE claim.",
        ]
    elif kind == "TYPE":
        base["rules"] = [
            "Describe class/type-level responsibility only.",
            "Do not duplicate every method scenario as TYPE claims.",
            "Evidence must stay inside the current type range.",
        ]
    elif kind == "CALLABLE":
        base["rules"] = [
            "Describe only the current callable responsibility.",
            "Every evidence range must be inside the current callable range.",
            "Use method body evidence for responsibility, side effects, and data access.",
            "Do not describe another method, caller, or callee as this callable.",
        ]
    else:
        base["rules"] = ["Describe only the current target anchor."]
    return {key: value for key, value in base.items() if value is not None}
