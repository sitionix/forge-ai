from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from pydantic import ValidationError

from knowledge_service.graph_schema import (
    ALLOWED_GRAPH_CLAIM_KINDS,
    ALLOWED_GRAPH_EDGE_TYPES,
    ALLOWED_GRAPH_NODE_KINDS,
    ALLOWED_RESOLUTION_STATUSES,
    GraphAnalysisResult,
    GraphClaim,
    GraphEdge,
    GraphEvidenceRef,
)
from knowledge_service.graph_validation import json_path


MAX_GRAPH_RAW_PREVIEW_CHARS = 4000
MAX_GRAPH_ERROR_PREVIEW_CHARS = 800


@dataclass(frozen=True)
class GraphAnalysisParseFailure:
    code: str
    message: str
    raw_preview: str
    error_details: list[dict[str, Any]] = field(default_factory=list)


class GraphAnalysisResponseParser:
    def parse(self, raw: str, line_count: int) -> GraphAnalysisResult | GraphAnalysisParseFailure:
        if raw is None or not raw.strip():
            return GraphAnalysisParseFailure(
                "ANALYSIS_AI_EMPTY_RESPONSE",
                "AI analyzer returned an empty response",
                "",
                [
                    {
                        "errorType": "JSON_PARSE_ERROR",
                        "message": "AI analyzer returned an empty response.",
                        "line": 1,
                        "column": 1,
                        "charPosition": 0,
                        "rawPreview": "",
                        "responseTruncated": False,
                    }
                ],
            )
        parsed, loaded, load_error = self._load_json(raw)
        if not loaded:
            extracted = self._extract_first_json_object(raw)
            if extracted is None:
                detail = self._json_parse_detail(raw, load_error)
                return GraphAnalysisParseFailure(
                    "ANALYSIS_AI_INVALID_JSON",
                    self._json_parse_message(detail),
                    self._preview(raw),
                    [detail],
                )
            parsed, loaded, extracted_error = self._load_json(extracted)
            if not loaded:
                detail = self._json_parse_detail(extracted, extracted_error)
                return GraphAnalysisParseFailure(
                    "ANALYSIS_AI_INVALID_JSON",
                    self._json_parse_message(detail),
                    self._preview(raw),
                    [detail],
                )
        if not isinstance(parsed, dict):
            detail = self._schema_error(
                "$",
                field=None,
                message="AI analyzer response must be one JSON object.",
                actual=type(parsed).__name__,
                expected="JSON object",
            )
            return GraphAnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", self._details_message("AI analyzer response does not match graph schema", [detail]), self._preview(raw), [detail])
        try:
            if str(parsed.get("schemaVersion") or "").startswith("knowledge.graph.enrichment."):
                enrichment_errors = self._validate_enrichment_payload(parsed, line_count)
                if enrichment_errors:
                    return GraphAnalysisParseFailure(
                        "ANALYSIS_AI_SCHEMA_INVALID",
                        self._details_message("AI analyzer response does not match graph schema", enrichment_errors),
                        self._preview(raw),
                        enrichment_errors,
                    )
                result = self._parse_enrichment(parsed)
                result.validate_lines(line_count)
                return result
            result = GraphAnalysisResult.parse_obj(parsed)
            result.validate_lines(line_count)
            result.validate_references()
            return result
        except ValidationError as exc:
            details = self._validation_error_details(exc, parsed)
            return GraphAnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", self._details_message("AI analyzer response does not match graph schema", details), self._preview(raw), details)
        except (ValueError, TypeError) as exc:
            detail = self._graph_validation_error("$", str(exc))
            return GraphAnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", self._details_message("AI analyzer response does not match graph schema", [detail]), self._preview(raw), [detail])

    def _load_json(self, raw: str) -> tuple[Any | None, bool, json.JSONDecodeError | None]:
        try:
            return json.loads(raw), True, None
        except json.JSONDecodeError as exc:
            return None, False, exc

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
        return raw[:MAX_GRAPH_RAW_PREVIEW_CHARS]

    def _json_parse_detail(self, raw: str, exc: json.JSONDecodeError | None) -> dict[str, Any]:
        position = exc.pos if exc is not None else 0
        return {
            "errorType": "JSON_PARSE_ERROR",
            "message": exc.msg if exc is not None else "No complete JSON object found.",
            "line": exc.lineno if exc is not None else 1,
            "column": exc.colno if exc is not None else 1,
            "charPosition": position,
            "rawPreview": self._preview_around(raw, position),
            "responseTruncated": self._appears_truncated(raw, exc),
        }

    def _preview_around(self, raw: str, position: int) -> str:
        if not raw:
            return ""
        radius = MAX_GRAPH_ERROR_PREVIEW_CHARS // 2
        start = max(0, position - radius)
        end = min(len(raw), position + radius)
        preview = raw[start:end]
        if start > 0:
            preview = "..." + preview
        if end < len(raw):
            preview = preview + "..."
        return preview

    def _appears_truncated(self, raw: str, exc: json.JSONDecodeError | None) -> bool:
        stripped = raw.rstrip()
        if not stripped:
            return False
        if self._extract_first_json_object(raw) is None and "{" in raw:
            return True
        if stripped[-1] in "{[,:":  # incomplete JSON structures commonly end this way.
            return True
        if exc is None:
            return False
        near_end = exc.pos >= max(0, len(stripped) - 2)
        message = exc.msg.lower()
        return near_end and any(term in message for term in ("unterminated", "expecting value", "expecting property name", "expecting ',' delimiter"))

    def _json_parse_message(self, detail: dict[str, Any]) -> str:
        return (
            "AI analyzer returned invalid JSON: "
            f"JSON parse error at line {detail.get('line')} column {detail.get('column')}: {detail.get('message')}"
        )

    def _details_message(self, prefix: str, details: list[dict[str, Any]]) -> str:
        summaries = [self._detail_summary(detail) for detail in details[:3]]
        text = "; ".join(item for item in summaries if item)
        if len(details) > 3:
            text = f"{text}; and {len(details) - 3} more error(s)"
        if len(text) > 480:
            text = text[:480].rstrip() + "..."
        return f"{prefix}: {text}" if text else prefix

    def _detail_summary(self, detail: dict[str, Any]) -> str:
        error_type = detail.get("errorType")
        if error_type == "JSON_PARSE_ERROR":
            return f"JSON parse error at line {detail.get('line')} column {detail.get('column')}: {detail.get('message')}"
        if error_type == "SCHEMA_VALIDATION_ERROR":
            path = detail.get("jsonPath") or "$"
            if detail.get("missingRequiredField"):
                return f"{path} is missing required field {detail.get('missingRequiredField')}"
            allowed = detail.get("allowedValues") or []
            allowed_text = f" Allowed values: {allowed}." if allowed else ""
            actual = detail.get("actual")
            return f"{path} has invalid value {actual!r}; expected {detail.get('expected')}.{allowed_text}"
        if error_type == "GRAPH_VALIDATION_ERROR":
            return f"{detail.get('jsonPath') or detail.get('graphEntityId') or '$'}: {detail.get('reason') or detail.get('message')}"
        return str(detail.get("message") or detail)

    def _validation_error_details(self, exc: ValidationError, parsed: Any) -> list[dict[str, Any]]:
        details: list[dict[str, Any]] = []
        for error in exc.errors():
            loc = tuple(error.get("loc") or ())
            path = json_path(loc)
            field = str(loc[-1]) if loc else None
            missing = error.get("type") == "value_error.missing"
            allowed = self._allowed_values_for_path(path)
            details.append(
                self._schema_error(
                    path,
                    field=field,
                    message=str(error.get("msg") or "Schema validation failed."),
                    actual=None if missing else self._actual_at_path(parsed, loc),
                    expected="required field" if missing else self._expected_for_error(error),
                    allowed_values=allowed,
                    missing_required_field=field if missing else None,
                )
            )
        return details or [self._schema_error("$", field=None, message=str(exc), expected="valid graph schema")]

    def _schema_error(
        self,
        json_path_value: str,
        *,
        field: str | None,
        message: str,
        actual: Any = None,
        expected: str | None = None,
        allowed_values: list[str] | None = None,
        missing_required_field: str | None = None,
    ) -> dict[str, Any]:
        return {
            "errorType": "SCHEMA_VALIDATION_ERROR",
            "jsonPath": json_path_value,
            "field": field,
            "message": message,
            "actual": self._jsonable(actual),
            "expected": expected,
            "allowedValues": list(allowed_values or []),
            "missingRequiredField": missing_required_field,
        }

    def _graph_validation_error(self, path: str, reason: str, *, graph_entity_id: str | None = None, allowed_values: list[str] | None = None) -> dict[str, Any]:
        return {
            "errorType": "GRAPH_VALIDATION_ERROR",
            "jsonPath": path,
            "graphEntityId": graph_entity_id,
            "reason": reason,
            "allowedValues": list(allowed_values or []),
        }

    def _expected_for_error(self, error: dict[str, Any]) -> str:
        error_type = str(error.get("type") or "")
        if "type_error" in error_type:
            return str(error_type).replace("type_error.", "")
        if "value_error" in error_type:
            return "valid value"
        return str(error.get("msg") or "valid value")

    def _allowed_values_for_path(self, path: str) -> list[str]:
        if path.endswith(".nodeKind"):
            return sorted(ALLOWED_GRAPH_NODE_KINDS)
        if path.endswith(".edgeType"):
            return sorted(ALLOWED_GRAPH_EDGE_TYPES)
        if path.endswith(".claimKind"):
            return sorted(ALLOWED_GRAPH_CLAIM_KINDS)
        if path.endswith(".resolutionStatus"):
            return sorted(ALLOWED_RESOLUTION_STATUSES)
        return []

    def _actual_at_path(self, parsed: Any, loc: tuple[Any, ...]) -> Any:
        value = parsed
        for part in loc:
            try:
                if isinstance(value, dict):
                    value = value[part]
                elif isinstance(value, list) and isinstance(part, int):
                    value = value[part]
                else:
                    return None
            except (KeyError, IndexError, TypeError):
                return None
        return self._jsonable(value)

    def _jsonable(self, value: Any) -> Any:
        try:
            json.dumps(value)
            return value
        except TypeError:
            return str(value)

    def _validate_enrichment_payload(self, parsed: dict[str, Any], line_count: int) -> list[dict[str, Any]]:
        details: list[dict[str, Any]] = []
        claims = parsed.get("claims")
        if claims is not None and not isinstance(claims, list):
            details.append(
                self._schema_error("$.claims", field="claims", message="claims must be an array.", actual=claims, expected="array")
            )
            return details
        for index, item in enumerate(claims or []):
            path = f"$.claims[{index}]"
            if not isinstance(item, dict):
                details.append(self._schema_error(path, field=None, message="claim must be an object.", actual=type(item).__name__, expected="object"))
                continue
            details.extend(self._validate_required_string(item, path, "localId"))
            target = item.get("targetStableKey") or item.get("nodeLocalId")
            if not target:
                details.append(
                    self._schema_error(
                        f"{path}.targetStableKey",
                        field="targetStableKey",
                        message="targetStableKey or nodeLocalId is required.",
                        actual=None,
                        expected="non-empty string pointing to the FILE anchor",
                        missing_required_field="targetStableKey",
                    )
                )
            claim_kind = item.get("claimKind")
            if not claim_kind:
                details.append(
                    self._schema_error(
                        f"{path}.claimKind",
                        field="claimKind",
                        message="claimKind is required.",
                        actual=claim_kind,
                        expected="RESPONSIBILITY",
                        allowed_values=["RESPONSIBILITY"],
                        missing_required_field="claimKind",
                    )
                )
            elif str(claim_kind).upper() != "RESPONSIBILITY":
                details.append(
                    self._schema_error(
                        f"{path}.claimKind",
                        field="claimKind",
                        message="claimKind must be RESPONSIBILITY for generic config enrichment.",
                        actual=claim_kind,
                        expected="RESPONSIBILITY",
                        allowed_values=["RESPONSIBILITY"],
                    )
                )
            details.extend(self._validate_required_string(item, path, "summary"))
            evidence = item.get("evidence")
            if not isinstance(evidence, list) or not evidence:
                details.append(
                    self._schema_error(
                        f"{path}.evidence",
                        field="evidence",
                        message="evidence is required for grounded generic enrichment claims.",
                        actual=evidence,
                        expected="non-empty array of evidence line ranges",
                        missing_required_field="evidence" if evidence is None else None,
                    )
                )
                continue
            for evidence_index, evidence_item in enumerate(evidence):
                evidence_path = f"{path}.evidence[{evidence_index}]"
                if not isinstance(evidence_item, dict):
                    details.append(
                        self._schema_error(evidence_path, field=None, message="evidence must be an object.", actual=type(evidence_item).__name__, expected="object")
                    )
                    continue
                for field_name in ("lineStart", "lineEnd"):
                    if evidence_item.get(field_name) is None:
                        details.append(
                            self._schema_error(
                                f"{evidence_path}.{field_name}",
                                field=field_name,
                                message=f"{field_name} is required.",
                                actual=None,
                                expected="integer source line number",
                                missing_required_field=field_name,
                            )
                        )
                    elif not isinstance(evidence_item.get(field_name), int):
                        details.append(
                            self._schema_error(
                                f"{evidence_path}.{field_name}",
                                field=field_name,
                                message=f"{field_name} must be an integer.",
                                actual=evidence_item.get(field_name),
                                expected="integer source line number",
                            )
                        )
                line_start = evidence_item.get("lineStart")
                line_end = evidence_item.get("lineEnd")
                if isinstance(line_start, int) and isinstance(line_end, int) and (line_start < 1 or line_end < line_start or line_end > max(line_count, 1)):
                    details.append(self._graph_validation_error(evidence_path, "Evidence line range outside file."))
        semantic_edges = parsed.get("semanticEdges")
        if semantic_edges is not None and not isinstance(semantic_edges, list):
            details.append(
                self._schema_error("$.semanticEdges", field="semanticEdges", message="semanticEdges must be an array.", actual=semantic_edges, expected="array")
            )
        for index, item in enumerate(semantic_edges or []):
            path = f"$.semanticEdges[{index}]"
            if not isinstance(item, dict):
                details.append(self._schema_error(path, field=None, message="semantic edge must be an object.", actual=type(item).__name__, expected="object"))
                continue
            edge_type = item.get("edgeType")
            if edge_type is not None and str(edge_type).upper() not in ALLOWED_GRAPH_EDGE_TYPES:
                details.append(
                    self._schema_error(
                        f"{path}.edgeType",
                        field="edgeType",
                        message="edgeType is not an allowed graph edge type.",
                        actual=edge_type,
                        expected="allowed graph edge type",
                        allowed_values=sorted(ALLOWED_GRAPH_EDGE_TYPES),
                    )
                )
        return details

    def _validate_required_string(self, item: dict[str, Any], path: str, field_name: str) -> list[dict[str, Any]]:
        value = item.get(field_name)
        if value is None:
            return [
                self._schema_error(
                    f"{path}.{field_name}",
                    field=field_name,
                    message=f"{field_name} is required.",
                    actual=None,
                    expected="non-empty string",
                    missing_required_field=field_name,
                )
            ]
        if not isinstance(value, str) or not value.strip():
            return [
                self._schema_error(
                    f"{path}.{field_name}",
                    field=field_name,
                    message=f"{field_name} must be a non-empty string.",
                    actual=value,
                    expected="non-empty string",
                )
            ]
        return []

    def _parse_enrichment(self, parsed: dict[str, Any]) -> GraphAnalysisResult:
        claims = []
        for index, item in enumerate(parsed.get("claims") or [], start=1):
            if not isinstance(item, dict):
                continue
            claims.append(
                GraphClaim(
                    localId=str(item.get("localId") or f"claim{index}"),
                    nodeLocalId=str(item.get("targetStableKey") or item.get("nodeLocalId") or ""),
                    claimKind=str(item.get("claimKind") or "UNKNOWN"),
                    summary=str(item.get("summary") or ""),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    confidence=float(item.get("confidence") if item.get("confidence") is not None else 0.0),
                    metadata=dict(item.get("metadata") or {}),
                )
            )
        edges = []
        for index, item in enumerate(parsed.get("semanticEdges") or [], start=1):
            if not isinstance(item, dict):
                continue
            metadata = dict(item.get("metadata") or {})
            metadata.setdefault("factOrigin", "LLM")
            if item.get("resolutionStatus"):
                metadata.setdefault("resolutionStatus", item.get("resolutionStatus"))
            edges.append(
                GraphEdge(
                    localId=str(item.get("localId") or f"semantic{index}"),
                    fromNodeLocalId=str(item.get("fromStableKey") or item.get("fromNodeLocalId") or ""),
                    toNodeLocalId=item.get("toStableKey") or item.get("toNodeLocalId"),
                    edgeType=str(item.get("edgeType") or "UNKNOWN"),
                    confidence=float(item.get("confidence") if item.get("confidence") is not None else 0.0),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    unresolvedTarget=item.get("unresolvedTarget"),
                    metadata=metadata,
                )
            )
        diagnostics = parsed.get("diagnostics") or []
        return GraphAnalysisResult(nodes=[], edges=edges, claims=claims, diagnostics=diagnostics)

    def _evidence_refs(self, values: list[Any]) -> list[GraphEvidenceRef]:
        refs: list[GraphEvidenceRef] = []
        for item in values:
            if not isinstance(item, dict):
                continue
            refs.append(
                GraphEvidenceRef(
                    lineStart=int(item.get("lineStart")),
                    lineEnd=int(item.get("lineEnd")),
                    text=item.get("text"),
                    metadata=dict(item.get("metadata") or {}),
                )
            )
        return refs
