from __future__ import annotations

from typing import Any, Mapping


TARGET_INPUT_SCHEMA_VERSION = "knowledge.graph.enrichment.input.v2"
TARGET_REQUEST_KIND = "TARGET_ANCHOR_ENRICHMENT"

BEGIN_INPUT_MARKER = "BEGIN_LLM_INPUT_JSON"
END_INPUT_MARKER = "END_LLM_INPUT_JSON"
INPUT_JSON_PLACEHOLDER = "{{LLM_INPUT_JSON}}"
REPAIR_INSTRUCTIONS_PLACEHOLDER = "{{REPAIR_INSTRUCTIONS}}"
TARGET_RESPONSE_SHAPE_PLACEHOLDER = "{{TARGET_RESPONSE_SHAPE}}"


def is_target_enrichment_payload(payload: Mapping[str, Any]) -> bool:
    return payload.get("requestKind") == TARGET_REQUEST_KIND and isinstance(payload.get("llmInput"), Mapping)
