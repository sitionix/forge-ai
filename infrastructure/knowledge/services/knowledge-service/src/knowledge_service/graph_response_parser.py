from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Dict, List, Sequence

from pydantic import ValidationError

from knowledge_service.graph_schema import (
    GRAPH_SCHEMA_VERSION,
    GraphAnalysisResponse,
    GraphClaimKind,
    GraphDiagnosticStage,
    GraphEdgeType,
    GraphNodeKind,
    enum_values,
)
from knowledge_service.graph_validation import GraphValidationError, GraphValidationErrorCode, json_path


MAX_RAW_PREVIEW_CHARS = 4000


@dataclass(frozen=True)
class GraphAnalysisParseFailure:
    code: str
    message: str
    raw_preview: str
    validation_errors: List[GraphValidationError]


class GraphAnalysisResponseParser:
    def parse(self, raw: str, line_count: int | None = None) -> GraphAnalysisResponse | GraphAnalysisParseFailure:
        if raw is None or not raw.strip():
            return self._failure(
                "ANALYSIS_AI_EMPTY_RESPONSE",
                "AI analyzer returned an empty response",
                "",
                [GraphValidationError(
                    code=GraphValidationErrorCode.EMPTY_RESPONSE,
                    path="$",
                    stage=GraphDiagnosticStage.JSON_PARSE,
                    message="AI analyzer returned an empty response.",
                    expected="One JSON object matching the graph analysis schema.",
                    actual="",
                    repair_hint="Return exactly one JSON object. Do not include markdown or prose.",
                )],
            )
        parsed, parse_error = self._load_json(raw)
        if parse_error is not None:
            extracted = self._extract_first_json_object(raw)
            if extracted is None:
                return self._invalid_json_failure(raw, parse_error)
            parsed, parse_error = self._load_json(extracted)
            if parse_error is not None:
                return self._invalid_json_failure(raw, parse_error)
        if not isinstance(parsed, dict):
            return self._failure(
                "ANALYSIS_AI_SCHEMA_INVALID",
                "AI analyzer response must be one JSON object",
                self._preview(raw),
                [GraphValidationError(
                    code=GraphValidationErrorCode.RESPONSE_NOT_OBJECT,
                    path="$",
                    stage=GraphDiagnosticStage.SCHEMA_VALIDATE,
                    message="AI analyzer response must be one JSON object.",
                    expected="JSON object with schemaVersion, file, nodes, edges, claims, and diagnostics.",
                    actual=parsed,
                    repair_hint="Return a single JSON object matching the graph analysis schema.",
                )],
            )
        try:
            return GraphAnalysisResponse.parse_obj(parsed)
        except ValidationError as exc:
            return self._failure(
                "ANALYSIS_AI_SCHEMA_INVALID",
                self._schema_message(exc),
                self._preview(raw),
                self._schema_errors(exc, parsed, line_count),
            )

    def _load_json(self, raw: str) -> tuple[Any | None, json.JSONDecodeError | None]:
        try:
            return json.loads(raw), None
        except json.JSONDecodeError as exc:
            return None, exc

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
                    return raw[start:index + 1]
        return None

    def _preview(self, raw: str) -> str:
        return raw[:MAX_RAW_PREVIEW_CHARS]

    def _failure(
        self,
        code: str,
        message: str,
        raw_preview: str,
        validation_errors: Sequence[GraphValidationError],
    ) -> GraphAnalysisParseFailure:
        return GraphAnalysisParseFailure(code, message, raw_preview, list(validation_errors))

    def _invalid_json_failure(self, raw: str, exc: json.JSONDecodeError) -> GraphAnalysisParseFailure:
        message = f"AI analyzer returned invalid JSON: {exc.msg} at line {exc.lineno}, column {exc.colno}"
        return self._failure(
            "ANALYSIS_AI_INVALID_JSON",
            message,
            self._preview(raw),
            [GraphValidationError(
                code=GraphValidationErrorCode.INVALID_JSON,
                path="$",
                stage=GraphDiagnosticStage.JSON_PARSE,
                message=message,
                expected="Valid JSON object matching the graph analysis schema.",
                actual={"line": exc.lineno, "column": exc.colno, "message": exc.msg},
                repair_hint="Return valid JSON only. Remove markdown fences, prose, trailing commas, and unterminated strings.",
            )],
        )

    def _schema_message(self, exc: Exception) -> str:
        text = str(exc).replace("\n", " ")
        if len(text) > 480:
            text = text[:480].rstrip() + "..."
        return f"AI analyzer graph response does not match schema: {text}"

    def _schema_errors(self, exc: ValidationError, parsed: Dict[str, Any], line_count: int | None) -> List[GraphValidationError]:
        errors: List[GraphValidationError] = []
        for item in exc.errors():
            loc = item.get("loc") or ()
            path = json_path(loc)
            error_type = str(item.get("type") or "")
            field = str(loc[-1]) if loc else ""
            actual = self._lookup(parsed, loc)
            code = GraphValidationErrorCode.SCHEMA_INVALID
            if error_type == "value_error.missing":
                code = GraphValidationErrorCode.MISSING_REQUIRED_FIELD
                actual = None
            elif error_type.startswith("type_error"):
                code = GraphValidationErrorCode.INVALID_FIELD_TYPE
            errors.append(GraphValidationError(
                code=code,
                path=path,
                stage=GraphDiagnosticStage.SCHEMA_VALIDATE,
                message=self._field_message(code, path, field, item.get("msg")),
                expected=self._expected(field, line_count),
                actual=actual,
                allowed_values=self._allowed_values(field),
                repair_hint=self._repair_hint(code, field),
            ))
        return errors or [GraphValidationError(
            code=GraphValidationErrorCode.SCHEMA_INVALID,
            path="$",
            stage=GraphDiagnosticStage.SCHEMA_VALIDATE,
            message="AI analyzer response does not match the graph schema.",
            expected="Graph analysis response schema.",
            actual=None,
            repair_hint="Return one graph response JSON object matching the schema.",
        )]

    def _lookup(self, value: Any, loc: Sequence[Any]) -> Any:
        current = value
        for part in loc:
            if isinstance(current, dict) and part in current:
                current = current[part]
            elif isinstance(current, list) and isinstance(part, int) and 0 <= part < len(current):
                current = current[part]
            else:
                return None
        return current

    def _field_message(self, code: GraphValidationErrorCode, path: str, field: str, fallback: Any) -> str:
        if code == GraphValidationErrorCode.MISSING_REQUIRED_FIELD:
            return f"Required field {field or path} is missing."
        if code == GraphValidationErrorCode.INVALID_FIELD_TYPE:
            return f"Field {field or path} has the wrong JSON type."
        return f"Schema validation failed at {path}: {fallback or 'invalid value'}."

    def _expected(self, field: str, line_count: int | None) -> str:
        if field == "schemaVersion":
            return f"Exactly {GRAPH_SCHEMA_VERSION}."
        if field == "nodeKind":
            return "One of allowed GraphNodeKind enum values."
        if field == "edgeType":
            return "One of allowed GraphEdgeType enum values."
        if field == "claimKind":
            return "One of allowed GraphClaimKind enum values."
        if field in {"lineStart", "lineEnd"}:
            suffix = f" between 1 and {line_count}" if line_count else " inside the analyzed file"
            return f"Integer line number{suffix}."
        return "Required graph analysis schema field with the documented JSON type."

    def _allowed_values(self, field: str) -> List[str]:
        if field == "nodeKind":
            return enum_values(GraphNodeKind)
        if field == "edgeType":
            return enum_values(GraphEdgeType)
        if field == "claimKind":
            return enum_values(GraphClaimKind)
        if field == "schemaVersion":
            return [GRAPH_SCHEMA_VERSION]
        return []

    def _repair_hint(self, code: GraphValidationErrorCode, field: str) -> str:
        if code == GraphValidationErrorCode.MISSING_REQUIRED_FIELD:
            if field in {"nodeKind", "edgeType", "claimKind"}:
                return f"Add {field} using one of the allowed values. Use UNKNOWN if unsure."
            return f"Add required field {field} with a schema-valid value."
        if code == GraphValidationErrorCode.INVALID_FIELD_TYPE:
            return f"Change {field} to the JSON type required by the graph schema."
        if field == "schemaVersion":
            return f"Set schemaVersion to {GRAPH_SCHEMA_VERSION}."
        return "Fix this field to match the graph response schema."
