from __future__ import annotations

import json
from typing import Any, Iterable, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract
from knowledge_service.analysis_parse_failure import GraphAnalysisParseFailure
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEvidenceRef


class TargetResponseParserValidator:
    _TOP_LEVEL_FIELDS = {"claims"}
    _CLAIM_FIELDS = {"claimKind", "summary", "evidence"}
    _EVIDENCE_FIELDS = {"lineStart", "lineEnd"}
    _DEFAULT_CONFIDENCE = 0.8
    _MAX_SUMMARY_CHARS = 600
    _MAX_EVIDENCE_TEXT_CHARS = 2000

    def parse(
        self,
        raw: str,
        *,
        payload: Mapping[str, Any],
        line_count: int,
        contract: AnalysisGraphContract,
    ) -> GraphAnalysisResult | GraphAnalysisParseFailure:
        parsed, load_error = self._load(raw)
        if load_error is not None:
            return load_error
        if not isinstance(parsed, dict):
            return self._failure(raw, self._schema_error("$", "Response must be one JSON object.", actual=type(parsed).__name__, expected="object"))

        llm_input = payload.get("llmInput")
        if not isinstance(llm_input, Mapping):
            return self._failure(raw, self._schema_error("$", "Target input context is missing.", expected="llmInput"))
        target_anchor = llm_input.get("targetAnchor")
        if not isinstance(target_anchor, Mapping):
            return self._failure(raw, self._schema_error("$.targetAnchor", "Target input anchor is missing.", expected="targetAnchor"))

        target_ref = str(payload.get("targetRef") or target_anchor.get("ref") or "")
        target_kind = str(payload.get("targetKind") or target_anchor.get("kind") or "")
        ref_to_stable_key = {str(key): str(value) for key, value in (payload.get("_refToStableKey") or {}).items()}
        target_stable_key = ref_to_stable_key.get(target_ref)
        content_by_line = self._content_by_line(llm_input)
        details: list[dict[str, Any]] = []

        if not target_ref:
            details.append(self._schema_error("$.targetRef", "Target payload ref is required.", expected="targetRef"))
        if not target_stable_key:
            details.append(self._schema_error("$.targetRef", "targetRef has no backend stable key mapping.", actual=target_ref, expected="stable key mapping"))

        self._validate_object_fields(parsed, "$", self._TOP_LEVEL_FIELDS, details)
        claims = parsed.get("claims")
        if not isinstance(claims, list):
            details.append(self._schema_error("$.claims", "claims must be an array.", actual=claims, expected="array"))
            claims = []

        for index, item in enumerate(claims):
            self._validate_claim(item, index, contract, line_count, details)

        if details:
            return self._failure(raw, *self._decorate_details(details, target_ref, target_kind))
        try:
            return self._to_graph_result(
                parsed,
                target_ref=target_ref,
                target_stable_key=str(target_stable_key),
                content_by_line=content_by_line,
            )
        except (KeyError, TypeError, ValueError) as exc:
            detail = self._schema_error("$", str(exc), expected="valid graph result")
            return self._failure(raw, *self._decorate_details([detail], target_ref, target_kind))

    def _validate_claim(
        self,
        item: Any,
        index: int,
        contract: AnalysisGraphContract,
        line_count: int,
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.claims[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "claim must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._CLAIM_FIELDS, details)
        claim_kind = self._required_string(item, path, "claimKind", details)
        if claim_kind and claim_kind not in contract.allowed_claim_kinds:
            details.append(
                self._schema_error(
                    f"{path}.claimKind",
                    "claimKind is not allowed by the effective analysis graph policy.",
                    actual=claim_kind,
                    expected="allowed claim kind",
                    allowed_values=list(contract.allowed_claim_kinds),
                )
            )
        summary = self._required_string(item, path, "summary", details)
        if summary and len(summary) > self._MAX_SUMMARY_CHARS:
            details.append(self._schema_error(f"{path}.summary", "summary is too long.", actual=len(summary), expected=f"{self._MAX_SUMMARY_CHARS} chars or fewer"))
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, details)

    def _evidence_list(self, value: Any, path: str, line_count: int, details: list[dict[str, Any]]) -> None:
        if not isinstance(value, list) or not value:
            details.append(self._schema_error(path, "evidence must be a non-empty array.", actual=value, expected="non-empty array"))
            return
        for index, item in enumerate(value):
            evidence_path = f"{path}[{index}]"
            if not isinstance(item, dict):
                details.append(self._schema_error(evidence_path, "evidence must be an object.", actual=type(item).__name__, expected="object"))
                continue
            self._validate_object_fields(item, evidence_path, self._EVIDENCE_FIELDS, details)
            line_start = self._required_int(item, evidence_path, "lineStart", details)
            line_end = self._required_int(item, evidence_path, "lineEnd", details)
            if line_start is not None and line_end is not None and (line_start < 1 or line_end < line_start or line_end > max(line_count, 1)):
                details.append(self._graph_error(evidence_path, "Evidence line range outside file."))

    def _to_graph_result(
        self,
        parsed: Mapping[str, Any],
        *,
        target_ref: str,
        target_stable_key: str,
        content_by_line: Mapping[int, str],
    ) -> GraphAnalysisResult:
        claims: list[GraphClaim] = []
        for index, item in enumerate(parsed.get("claims") or [], start=1):
            claims.append(
                GraphClaim(
                    localId=f"llm-claim-{target_ref}-{index}",
                    nodeLocalId=target_stable_key,
                    claimKind=str(item["claimKind"]),
                    summary=str(item["summary"]),
                    confidence=self._DEFAULT_CONFIDENCE,
                    evidence=self._evidence_refs(item.get("evidence") or [], content_by_line),
                    metadata={"factOrigin": "LLM"},
                )
            )
        return GraphAnalysisResult(nodes=[], edges=[], claims=claims, diagnostics=[])

    def _evidence_refs(self, value: Iterable[Any], content_by_line: Mapping[int, str]) -> list[GraphEvidenceRef]:
        refs: list[GraphEvidenceRef] = []
        for item in value:
            line_start = int(item["lineStart"])
            line_end = int(item["lineEnd"])
            refs.append(
                GraphEvidenceRef(
                    lineStart=line_start,
                    lineEnd=line_end,
                    text=self._evidence_text(content_by_line, line_start, line_end),
                )
            )
        return refs

    def _evidence_text(self, content_by_line: Mapping[int, str], line_start: int, line_end: int) -> str:
        text = "\n".join(content_by_line.get(line, "") for line in range(line_start, line_end + 1))
        if len(text) > self._MAX_EVIDENCE_TEXT_CHARS:
            return text[: self._MAX_EVIDENCE_TEXT_CHARS].rstrip()
        return text

    def _content_by_line(self, llm_input: Mapping[str, Any]) -> dict[int, str]:
        file_payload = llm_input.get("file")
        lines = file_payload.get("contentLines") if isinstance(file_payload, Mapping) else None
        content_by_line: dict[int, str] = {}
        if not isinstance(lines, list):
            return content_by_line
        for item in lines:
            if not isinstance(item, Mapping):
                continue
            line = item.get("line")
            if isinstance(line, int) and not isinstance(line, bool):
                content_by_line[int(line)] = str(item.get("text") or "")
        return content_by_line

    def _load(self, raw: str) -> tuple[Any | None, GraphAnalysisParseFailure | None]:
        if raw is None or not str(raw).strip():
            return None, self._failure("", self._schema_error("$", "AI analyzer returned an empty response.", expected="JSON object"))
        try:
            return json.loads(raw), None
        except json.JSONDecodeError as exc:
            return None, GraphAnalysisParseFailure(
                "ANALYSIS_AI_INVALID_JSON",
                f"AI analyzer returned invalid JSON: JSON parse error at line {exc.lineno} column {exc.colno}: {exc.msg}",
                str(raw)[:4000],
                [
                    {
                        "errorType": "JSON_PARSE_ERROR",
                        "message": exc.msg,
                        "line": exc.lineno,
                        "column": exc.colno,
                        "charPosition": exc.pos,
                        "rawPreview": str(raw)[:800],
                        "responseTruncated": len(str(raw)) > 800,
                    }
                ],
            )

    def _validate_object_fields(self, item: Mapping[str, Any], path: str, allowed: set[str], details: list[dict[str, Any]]) -> None:
        extra = sorted(set(item.keys()) - allowed)
        for field_name in extra:
            details.append(
                self._schema_error(
                    f"{path}.{field_name}",
                    "Unknown field is not allowed by the target-anchor response contract.",
                    actual=field_name,
                    expected="no extra fields",
                )
            )

    def _required_string(self, item: Mapping[str, Any], path: str, field_name: str, details: list[dict[str, Any]]) -> Optional[str]:
        value = item.get(field_name)
        if value is None:
            details.append(
                self._schema_error(
                    f"{path}.{field_name}",
                    f"{field_name} is required.",
                    actual=None,
                    expected="non-empty string",
                    missing_required_field=field_name,
                )
            )
            return None
        if not isinstance(value, str) or not value.strip():
            details.append(self._schema_error(f"{path}.{field_name}", f"{field_name} must be a non-empty string.", actual=value, expected="non-empty string"))
            return None
        return value

    def _required_int(self, item: Mapping[str, Any], path: str, field_name: str, details: list[dict[str, Any]]) -> Optional[int]:
        value = item.get(field_name)
        if value is None:
            details.append(
                self._schema_error(
                    f"{path}.{field_name}",
                    f"{field_name} is required.",
                    actual=None,
                    expected="integer",
                    missing_required_field=field_name,
                )
            )
            return None
        if not isinstance(value, int) or isinstance(value, bool):
            details.append(self._schema_error(f"{path}.{field_name}", f"{field_name} must be an integer.", actual=value, expected="integer"))
            return None
        return value

    def _decorate_details(
        self,
        details: list[dict[str, Any]],
        target_ref: str,
        target_kind: str,
    ) -> list[dict[str, Any]]:
        decorated: list[dict[str, Any]] = []
        for detail in details:
            item = dict(detail)
            item.setdefault("targetRef", target_ref)
            item.setdefault("targetKind", target_kind)
            decorated.append(item)
        return decorated

    def _schema_error(
        self,
        path: str,
        message: str,
        *,
        actual: Any = None,
        expected: Optional[str] = None,
        allowed_values: Optional[list[str]] = None,
        missing_required_field: Optional[str] = None,
        **extra: Any,
    ) -> dict[str, Any]:
        error = {
            "errorType": "SCHEMA_VALIDATION_ERROR",
            "jsonPath": path,
            "message": message,
            "actual": actual,
            "invalidValue": actual,
            "expected": expected,
            "allowedValues": list(allowed_values or []),
            "missingRequiredField": missing_required_field,
        }
        error.update({key: value for key, value in extra.items() if value is not None})
        return error

    def _graph_error(self, path: str, reason: str) -> dict[str, Any]:
        return {
            "errorType": "GRAPH_VALIDATION_ERROR",
            "jsonPath": path,
            "reason": reason,
            "invalidValue": None,
            "allowedValues": [],
        }

    def _failure(self, raw: str, *details: dict[str, Any]) -> GraphAnalysisParseFailure:
        summaries = "; ".join(str(detail.get("reason") or detail.get("message") or detail) for detail in details[:3])
        if len(details) > 3:
            summaries = f"{summaries}; and {len(details) - 3} more error(s)"
        return GraphAnalysisParseFailure(
            "ANALYSIS_AI_SCHEMA_INVALID",
            f"AI analyzer response does not match target-anchor graph schema: {summaries}",
            str(raw)[:4000],
            list(details),
        )
