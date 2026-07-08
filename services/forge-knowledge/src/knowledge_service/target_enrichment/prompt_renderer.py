from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract
from knowledge_service.analysis_policy import AnalysisPolicy
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.errors import KnowledgeError
from knowledge_service.target_enrichment.constants import (
    BEGIN_INPUT_MARKER,
    END_INPUT_MARKER,
    INPUT_JSON_PLACEHOLDER,
    REPAIR_INSTRUCTIONS_PLACEHOLDER,
    TARGET_RESPONSE_SHAPE_PLACEHOLDER,
)


class TargetPromptRenderer:
    def __init__(
        self,
        policy: Optional[AnalysisPolicy] = None,
        policy_path: Optional[str | Path] = None,
        default_prompt_id: Optional[str] = None,
    ):
        self.policy = policy or load_analysis_policy(policy_path)
        self.default_prompt_id = default_prompt_id
        self._template_cache: dict[str, str] = {}
        self._response_shape_cache: dict[str, dict[str, Any]] = {}

    def render(
        self,
        payload: Mapping[str, Any],
        repair_prompt: Optional[str] = None,
        *,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> str:
        llm_input = payload.get("llmInput")
        if not isinstance(llm_input, Mapping):
            raise KnowledgeError(
                "ANALYSIS_TARGET_INPUT_REQUIRED",
                "Analyzer provider requires a target-anchor LLM input payload.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                relativePath=str(payload.get("relativePath") or ""),
            )
        prompt_id = self._prompt_id(payload=payload, contract=contract)
        return (
            self._template(prompt_id)
            .replace(INPUT_JSON_PLACEHOLDER, self._input_json_block(llm_input))
            .replace(REPAIR_INSTRUCTIONS_PLACEHOLDER, str(repair_prompt or ""))
            .replace(TARGET_RESPONSE_SHAPE_PLACEHOLDER, self._response_shape_text(prompt_id))
            .strip()
        )

    def estimate_prompt_chars(
        self,
        payload: Mapping[str, Any],
        repair_prompt: Optional[str] = None,
        *,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> int:
        return len(self.render(payload, repair_prompt, contract=contract))

    def ensure_within_budget(
        self,
        payload: Mapping[str, Any],
        budget_chars: int,
        repair_prompt: Optional[str] = None,
        *,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> int:
        rendered_prompt_chars = self.estimate_prompt_chars(payload, repair_prompt, contract=contract)
        if rendered_prompt_chars > budget_chars:
            raise KnowledgeError(
                "ANALYSIS_LLM_TARGET_INPUT_TOO_LARGE",
                "Rendered target-anchor LLM prompt exceeds the configured analysis budget; no fallback prompt was used.",
                stage="LLM_ENRICHMENT",
                severity="ERROR",
                sourceId=payload.get("sourceId"),
                relativePath=payload.get("relativePath"),
                targetRef=payload.get("targetRef"),
                targetKind=payload.get("targetKind"),
                budgetChars=budget_chars,
                renderedPromptChars=rendered_prompt_chars,
            )
        return rendered_prompt_chars

    def response_shape(
        self,
        *,
        payload: Optional[Mapping[str, Any]] = None,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> dict[str, Any]:
        prompt_id = self._prompt_id(payload=payload, contract=contract)
        return self._response_shape_for_prompt_id(prompt_id)

    def _response_shape_for_prompt_id(self, prompt_id: str) -> dict[str, Any]:
        if prompt_id in self._response_shape_cache:
            return _json_copy(self._response_shape_cache[prompt_id])
        path = self._response_shape_path(prompt_id)
        text = path.read_text(encoding="utf-8").strip()
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError as exc:
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_INVALID_JSON",
                f"Analysis policy response shape file is invalid JSON: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
                jsonError=str(exc),
            ) from exc
        if not isinstance(parsed, dict):
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_INVALID_JSON",
                f"Analysis policy response shape file must contain a JSON object: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
                actualType=type(parsed).__name__,
            )
        self._response_shape_cache[prompt_id] = _json_copy(parsed)
        return _json_copy(parsed)

    def _input_json_block(self, llm_input: Mapping[str, Any]) -> str:
        return "\n".join(
            [
                BEGIN_INPUT_MARKER,
                json.dumps(dict(llm_input), ensure_ascii=False, indent=2, sort_keys=True),
                END_INPUT_MARKER,
            ]
        )

    def _response_shape_text(self, prompt_id: str) -> str:
        return json.dumps(self._response_shape_for_prompt_id(prompt_id), ensure_ascii=False, indent=2, sort_keys=True)

    def _template(self, prompt_id: str) -> str:
        if prompt_id in self._template_cache:
            return self._template_cache[prompt_id]
        if prompt_id not in self.policy.prompts:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_MISSING",
                f"Analysis policy target prompt id is not declared: {prompt_id}",
                promptId=prompt_id,
            )
        path = self.policy.prompt_path(prompt_id)
        if not path.exists():
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_FILE_MISSING",
                f"Analysis policy target prompt file does not exist: {path}",
                promptId=prompt_id,
                promptPath=str(path),
            )
        template = path.read_text(encoding="utf-8")
        missing = [
            placeholder
            for placeholder in (
                INPUT_JSON_PLACEHOLDER,
                REPAIR_INSTRUCTIONS_PLACEHOLDER,
                TARGET_RESPONSE_SHAPE_PLACEHOLDER,
            )
            if placeholder not in template
        ]
        if missing:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_TEMPLATE_INVALID",
                "Analysis policy target prompt template is missing required placeholders.",
                promptId=prompt_id,
                promptPath=str(path),
                missingPlaceholders=missing,
            )
        self._template_cache[prompt_id] = template
        return template

    def _response_shape_path(self, prompt_id: str) -> Path:
        if prompt_id not in self.policy.prompts:
            raise KnowledgeError(
                "ANALYSIS_POLICY_PROMPT_MISSING",
                f"Analysis policy target prompt id is not declared: {prompt_id}",
                promptId=prompt_id,
            )
        path = self.policy.prompt_response_shape_path(prompt_id)
        if not path.exists():
            raise KnowledgeError(
                "ANALYSIS_POLICY_RESPONSE_SHAPE_FILE_MISSING",
                f"Analysis policy target response shape file does not exist: {path}",
                promptId=prompt_id,
                responseShapePath=str(path),
            )
        return path

    def _prompt_id(
        self,
        *,
        payload: Optional[Mapping[str, Any]] = None,
        contract: Optional[AnalysisGraphContract] = None,
    ) -> str:
        raw_policy = payload.get("analysisPolicy") if isinstance(payload, Mapping) else None
        if isinstance(raw_policy, Mapping):
            raw_prompt_id = raw_policy.get("promptId")
            if isinstance(raw_prompt_id, str) and raw_prompt_id.strip():
                return raw_prompt_id.strip()
        if contract is not None and isinstance(contract.prompt_id, str) and contract.prompt_id.strip():
            return contract.prompt_id.strip()
        if isinstance(self.default_prompt_id, str) and self.default_prompt_id.strip():
            return self.default_prompt_id.strip()
        raise KnowledgeError(
            "ANALYSIS_POLICY_PROMPT_REQUIRED",
            "Target-anchor prompt id is required in the resolved analysis policy contract.",
            stage="LLM_ENRICHMENT",
            severity="ERROR",
            relativePath=str(payload.get("relativePath") or "") if isinstance(payload, Mapping) else "",
        )


def _json_copy(value: Mapping[str, Any]) -> dict[str, Any]:
    return json.loads(json.dumps(dict(value)))
