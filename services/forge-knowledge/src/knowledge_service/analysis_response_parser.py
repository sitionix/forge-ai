from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from pydantic import ValidationError

from knowledge_service.analysis_schema import AnalysisResult


MAX_RAW_PREVIEW_CHARS = 4000


@dataclass(frozen=True)
class AnalysisParseFailure:
    code: str
    message: str
    raw_preview: str


class AiAnalysisResponseParser:
    def parse(self, raw: str, line_count: int) -> AnalysisResult | AnalysisParseFailure:
        if raw is None or not raw.strip():
            return AnalysisParseFailure("ANALYSIS_AI_EMPTY_RESPONSE", "AI analyzer returned an empty response", "")
        parsed, loaded = self._load_json(raw)
        if not loaded:
            extracted = self._extract_first_json_object(raw)
            if extracted is None:
                return AnalysisParseFailure("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", self._preview(raw))
            parsed, loaded = self._load_json(extracted)
            if not loaded:
                return AnalysisParseFailure("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", self._preview(raw))
        if not isinstance(parsed, dict):
            return AnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", "AI analyzer response must be one JSON object", self._preview(raw))
        try:
            result = AnalysisResult.parse_obj(parsed)
            result.validate_lines(line_count)
            self._validate_relation_references(result)
            return result
        except (ValidationError, ValueError) as exc:
            return AnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", self._schema_message(exc), self._preview(raw))

    def _load_json(self, raw: str) -> tuple[Any | None, bool]:
        try:
            return json.loads(raw), True
        except json.JSONDecodeError:
            return None, False

    def _extract_first_json_object(self, raw: str) -> str | None:
        start = raw.find("{")
        if start < 0:
            return None
        depth = 0
        in_string = False
        escaped = False
        for index in range(start, len(raw)):
            char = raw[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return raw[start : index + 1]
        return None

    def _preview(self, raw: str) -> str:
        return raw[:MAX_RAW_PREVIEW_CHARS]

    def _schema_message(self, exc: Exception) -> str:
        text = str(exc).replace("\n", " ")
        if len(text) > 480:
            text = text[:480].rstrip() + "..."
        return f"AI analyzer response does not match schema: {text}"

    def _validate_relation_references(self, result: AnalysisResult) -> None:
        local_ids = {symbol.localId for symbol in result.symbols}
        for relation in result.relations:
            if relation.fromLocalId not in local_ids or relation.toLocalId not in local_ids:
                raise ValueError("Relation references an unknown symbol localId")
