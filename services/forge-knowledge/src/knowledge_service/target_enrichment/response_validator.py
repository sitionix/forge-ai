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
    _OLD_CONTRACT_FIELDS = {
        "schemaVersion",
        "localId",
        "targetRef",
        "fromRef",
        "toRef",
        "edgeType",
        "unresolvedStatus",
        "unresolvedTarget",
        "resolutionStatus",
        "confidence",
        "diagnostics",
        "text",
    }
    _DEFAULT_CONFIDENCE = 0.8
    _MAX_SUMMARY_CHARS = 600
    _MAX_EVIDENCE_TEXT_CHARS = 2000
    _CLOSING_BRACE_LINES = {"}", "};", ");", ")"}

    def parse(
        self,
        raw: str,
        *,
        payload: Mapping[str, Any],
        line_count: int,
        contract: AnalysisGraphContract,
    ) -> GraphAnalysisResult | GraphAnalysisParseFailure:
        context_errors: list[dict[str, Any]] = []
        llm_input = payload.get("llmInput")
        target_anchor = None
        if not isinstance(llm_input, Mapping):
            context_errors.append(
                self._validation_error(
                    "CLAIMS_MISSING",
                    "$",
                    "Target input context is missing.",
                    expected="llmInput",
                )
            )
            llm_input = {}
        else:
            target_anchor = llm_input.get("targetAnchor")
        if not isinstance(target_anchor, Mapping):
            context_errors.append(
                self._validation_error(
                    "CLAIMS_MISSING",
                    "$.targetAnchor",
                    "Target input anchor is missing.",
                    expected="targetAnchor",
                )
            )
            target_anchor = {}

        target_ref = str(payload.get("targetRef") or target_anchor.get("ref") or "")
        target_kind = str(payload.get("targetKind") or target_anchor.get("kind") or "")
        target_name = str(target_anchor.get("name") or "")
        ref_to_stable_key = {str(key): str(value) for key, value in (payload.get("_refToStableKey") or {}).items()}
        target_stable_key = ref_to_stable_key.get(target_ref)
        content_by_line = self._content_by_line(llm_input)
        target_range = self._target_range(target_anchor, line_count, context_errors)

        parsed, load_error = self._load(
            raw,
            target_ref=target_ref,
            target_kind=target_kind,
            target_name=target_name,
            target_range=target_range,
        )
        if load_error is not None:
            return load_error
        if not isinstance(parsed, dict):
            detail = self._validation_error(
                "RESPONSE_NOT_OBJECT",
                "$",
                "Response must be one JSON object.",
                actual=type(parsed).__name__,
                expected="object",
            )
            return self._failure(raw, [detail], target_ref, target_kind, target_name, target_range)

        details: list[dict[str, Any]] = list(context_errors)
        if not target_ref:
            details.append(
                self._validation_error(
                    "CLAIMS_MISSING",
                    "$.targetRef",
                    "Target payload ref is required.",
                    expected="targetRef",
                )
            )
        if not target_stable_key:
            details.append(
                self._validation_error(
                    "CLAIMS_MISSING",
                    "$.targetRef",
                    "targetRef has no backend stable key mapping.",
                    actual=target_ref,
                    expected="stable key mapping",
                )
            )

        self._validate_object_fields(parsed, "$", self._TOP_LEVEL_FIELDS, details)
        if "claims" not in parsed:
            details.append(
                self._validation_error(
                    "CLAIMS_MISSING",
                    "$.claims",
                    "claims is required.",
                    actual=None,
                    expected="claims array",
                    missing_required_field="claims",
                )
            )
            claims: list[Any] = []
        else:
            claims_value = parsed.get("claims")
            if not isinstance(claims_value, list):
                details.append(
                    self._validation_error(
                        "CLAIMS_NOT_ARRAY",
                        "$.claims",
                        "claims must be an array.",
                        actual=claims_value,
                        expected="array",
                    )
                )
                claims = []
            else:
                claims = claims_value

        for index, item in enumerate(claims):
            self._validate_claim(item, index, contract, line_count, content_by_line, target_range, details)

        if details:
            return self._failure(raw, details, target_ref, target_kind, target_name, target_range)
        try:
            return self._to_graph_result(
                parsed,
                target_ref=target_ref,
                target_stable_key=str(target_stable_key),
                content_by_line=content_by_line,
            )
        except (KeyError, TypeError, ValueError) as exc:
            detail = self._validation_error(
                "CLAIM_SCHEMA_INVALID",
                "$",
                str(exc),
                expected="valid graph result",
            )
            return self._failure(raw, [detail], target_ref, target_kind, target_name, target_range)

    def _validate_claim(
        self,
        item: Any,
        index: int,
        contract: AnalysisGraphContract,
        line_count: int,
        content_by_line: Mapping[int, str],
        target_range: Mapping[str, Any],
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.claims[{index}]"
        if not isinstance(item, dict):
            details.append(
                self._validation_error(
                    "CLAIM_NOT_OBJECT",
                    path,
                    "claim must be an object.",
                    actual=type(item).__name__,
                    expected="object",
                )
            )
            return
        self._validate_object_fields(item, path, self._CLAIM_FIELDS, details)
        claim_kind = self._required_string(item, path, "claimKind", details)
        if claim_kind and claim_kind not in contract.allowed_claim_kinds:
            details.append(
                self._validation_error(
                    "CLAIM_KIND_INVALID",
                    f"{path}.claimKind",
                    "claimKind is not allowed by the effective analysis graph policy.",
                    actual=claim_kind,
                    expected="allowed claim kind",
                    allowed_values=list(contract.allowed_claim_kinds),
                )
            )
        summary = self._required_string(item, path, "summary", details)
        if summary and len(summary) > self._MAX_SUMMARY_CHARS:
            details.append(
                self._validation_error(
                    "SUMMARY_TOO_LONG",
                    f"{path}.summary",
                    "summary is too long.",
                    actual=len(summary),
                    expected=f"{self._MAX_SUMMARY_CHARS} chars or fewer",
                )
            )
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, str(claim_kind or ""), content_by_line, target_range, details)

    def _evidence_list(
        self,
        value: Any,
        path: str,
        line_count: int,
        claim_kind: str,
        content_by_line: Mapping[int, str],
        target_range: Mapping[str, Any],
        details: list[dict[str, Any]],
    ) -> None:
        if value is None:
            details.append(
                self._validation_error(
                    "EVIDENCE_MISSING",
                    path,
                    "evidence is required.",
                    actual=None,
                    expected="non-empty evidence array",
                    missing_required_field="evidence",
                )
            )
            return
        if not isinstance(value, list):
            details.append(
                self._validation_error(
                    "EVIDENCE_NOT_ARRAY",
                    path,
                    "evidence must be an array.",
                    actual=value,
                    expected="array",
                )
            )
            return
        if not value:
            details.append(
                self._validation_error(
                    "EVIDENCE_MISSING",
                    path,
                    "evidence must be a non-empty array.",
                    actual=value,
                    expected="non-empty evidence array",
                )
            )
            return
        for index, item in enumerate(value):
            evidence_path = f"{path}[{index}]"
            if not isinstance(item, dict):
                details.append(
                    self._validation_error(
                        "EVIDENCE_NOT_ARRAY",
                        evidence_path,
                        "evidence item must be an object.",
                        actual=type(item).__name__,
                        expected="object evidence item",
                    )
                )
                continue
            self._validate_object_fields(item, evidence_path, self._EVIDENCE_FIELDS, details)
            line_start = self._required_int(item, evidence_path, "lineStart", details)
            line_end = self._required_int(item, evidence_path, "lineEnd", details)
            if line_start is None or line_end is None:
                continue
            evidence_range = {"lineStart": line_start, "lineEnd": line_end}
            if line_start > line_end:
                details.append(
                    self._validation_error(
                        "EVIDENCE_RANGE_INVERTED",
                        evidence_path,
                        "Evidence line range is inverted: lineStart must be <= lineEnd.",
                        actual=evidence_range,
                        expected="lineStart <= lineEnd",
                        evidence_range=evidence_range,
                    )
                )
                continue
            if line_start < 1 or line_end > max(line_count, 1):
                details.append(
                    self._validation_error(
                        "EVIDENCE_RANGE_OUTSIDE_FILE",
                        evidence_path,
                        "Evidence line range is outside file bounds.",
                        actual=evidence_range,
                        expected=f"1 <= lineStart <= lineEnd <= {max(line_count, 1)}",
                        evidence_range=evidence_range,
                    )
                )
                continue
            if not self._inside_target_range(line_start, line_end, target_range):
                details.append(
                    self._validation_error(
                        "EVIDENCE_RANGE_OUTSIDE_TARGET",
                        evidence_path,
                        "Evidence line range is outside target anchor range.",
                        actual=evidence_range,
                        expected=f"{target_range.get('lineStart')} <= lineStart <= lineEnd <= {target_range.get('lineEnd')}",
                        evidence_range=evidence_range,
                    )
                )
                continue
            if target_range.get("kind") == "CALLABLE" and not self._has_material_callable_evidence(line_start, line_end, content_by_line, claim_kind):
                line_class = self._line_class(line_start, line_end, content_by_line)
                details.append(
                    self._validation_error(
                        "EVIDENCE_NOT_MATERIAL",
                        evidence_path,
                        "Evidence points only to a comment, blank line, or closing brace.",
                        actual={"lineStart": line_start, "lineEnd": line_end, "lineClass": line_class},
                        expected="Evidence must cite material code lines that support the claim.",
                        evidence_range=evidence_range,
                    )
                )

    def _target_range(self, target_anchor: Mapping[str, Any], line_count: int, details: list[dict[str, Any]]) -> dict[str, Any]:
        kind = str(target_anchor.get("kind") or "")
        line_start = self._plain_int(target_anchor.get("lineStart"))
        line_end = self._plain_int(target_anchor.get("lineEnd"))
        body_line_start = self._plain_int(target_anchor.get("bodyLineStart"))
        body_line_end = self._plain_int(target_anchor.get("bodyLineEnd"))
        if line_start is None or line_end is None:
            details.append(
                self._validation_error(
                    "EVIDENCE_RANGE_MISSING",
                    "$.targetAnchor",
                    "targetAnchor lineStart and lineEnd are required for target-local claim validation.",
                    actual={"lineStart": target_anchor.get("lineStart"), "lineEnd": target_anchor.get("lineEnd")},
                    expected="target anchor line range",
                )
            )
            return {"kind": kind, "lineStart": 1, "lineEnd": max(line_count, 1), "bodyLineStart": body_line_start, "bodyLineEnd": body_line_end}
        if line_start > line_end:
            details.append(
                self._validation_error(
                    "EVIDENCE_RANGE_INVERTED",
                    "$.targetAnchor",
                    "targetAnchor line range is inverted.",
                    actual={"lineStart": line_start, "lineEnd": line_end},
                    expected="lineStart <= lineEnd",
                )
            )
        return {"kind": kind, "lineStart": line_start, "lineEnd": line_end, "bodyLineStart": body_line_start, "bodyLineEnd": body_line_end}

    def _inside_target_range(self, line_start: int, line_end: int, target_range: Mapping[str, Any]) -> bool:
        kind = target_range.get("kind")
        if kind == "FILE":
            return True
        target_start = target_range.get("lineStart")
        target_end = target_range.get("lineEnd")
        if not isinstance(target_start, int) or not isinstance(target_end, int):
            return False
        return target_start <= line_start <= line_end <= target_end

    def _has_material_callable_evidence(self, line_start: int, line_end: int, content_by_line: Mapping[int, str], claim_kind: str) -> bool:
        if claim_kind == "ENTRYPOINT_HINT":
            return True
        return self._line_class(line_start, line_end, content_by_line) == "MATERIAL"

    def _line_class(self, line_start: int, line_end: int, content_by_line: Mapping[int, str]) -> str:
        seen_comment = False
        seen_closing = False
        seen_blank = False
        for line_number in range(line_start, line_end + 1):
            text = str(content_by_line.get(line_number) or "").strip()
            if not text:
                seen_blank = True
                continue
            if text in self._CLOSING_BRACE_LINES:
                seen_closing = True
                continue
            if text.startswith("//"):
                seen_comment = True
                continue
            return "MATERIAL"
        if seen_comment and not seen_closing:
            return "COMMENT_ONLY"
        if seen_closing and not seen_comment:
            return "CLOSING_BRACE_ONLY"
        if seen_blank and not seen_comment and not seen_closing:
            return "BLANK_ONLY"
        if seen_comment:
            return "COMMENT_ONLY"
        if seen_closing:
            return "CLOSING_BRACE_ONLY"
        return "NON_MATERIAL"

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

    def _load(
        self,
        raw: str,
        *,
        target_ref: str,
        target_kind: str,
        target_name: str,
        target_range: Mapping[str, Any],
    ) -> tuple[Any | None, GraphAnalysisParseFailure | None]:
        if raw is None or not str(raw).strip():
            detail = self._validation_error(
                "JSON_PARSE_ERROR",
                "$",
                "AI analyzer returned an empty response.",
                actual="",
                expected="one valid JSON object",
                raw_preview="",
                response_truncated=False,
            )
            return None, self._json_failure("", detail, target_ref, target_kind, target_name, target_range)
        try:
            return json.loads(raw), None
        except json.JSONDecodeError as exc:
            detail = self._validation_error(
                "JSON_PARSE_ERROR",
                "$",
                f"JSON parse error at line {exc.lineno} column {exc.colno}: {exc.msg}",
                actual={"line": exc.lineno, "column": exc.colno, "charPosition": exc.pos},
                expected="one valid JSON object",
                line=exc.lineno,
                column=exc.colno,
                char_position=exc.pos,
                raw_preview=str(raw)[:800],
                response_truncated=len(str(raw)) > 800,
            )
            return None, self._json_failure(str(raw), detail, target_ref, target_kind, target_name, target_range)

    def _validate_object_fields(self, item: Mapping[str, Any], path: str, allowed: set[str], details: list[dict[str, Any]]) -> None:
        extra = sorted(set(item.keys()) - allowed)
        for field_name in extra:
            code = "UNKNOWN_TOP_LEVEL_FIELD" if path == "$" else "UNKNOWN_CLAIM_FIELD"
            if field_name == "semanticEdges":
                code = "SEMANTIC_EDGES_RETURNED"
            elif field_name in self._OLD_CONTRACT_FIELDS:
                code = "OLD_CONTRACT_FIELD_RETURNED"
            details.append(
                self._validation_error(
                    code,
                    f"{path}.{field_name}",
                    "Unknown or removed field is not allowed by the target-anchor claims-only response contract.",
                    actual=field_name,
                    expected="no extra fields",
                    field=field_name,
                )
            )

    def _required_string(self, item: Mapping[str, Any], path: str, field_name: str, details: list[dict[str, Any]]) -> Optional[str]:
        value = item.get(field_name)
        missing_code = "CLAIM_KIND_MISSING" if field_name == "claimKind" else "SUMMARY_MISSING"
        if value is None:
            details.append(
                self._validation_error(
                    missing_code,
                    f"{path}.{field_name}",
                    f"{field_name} is required.",
                    actual=None,
                    expected="non-empty string",
                    missing_required_field=field_name,
                )
            )
            return None
        if not isinstance(value, str) or not value.strip():
            details.append(
                self._validation_error(
                    missing_code,
                    f"{path}.{field_name}",
                    f"{field_name} must be a non-empty string.",
                    actual=value,
                    expected="non-empty string",
                )
            )
            return None
        return value

    def _required_int(self, item: Mapping[str, Any], path: str, field_name: str, details: list[dict[str, Any]]) -> Optional[int]:
        value = item.get(field_name)
        if value is None:
            details.append(
                self._validation_error(
                    "EVIDENCE_RANGE_MISSING",
                    f"{path}.{field_name}",
                    f"{field_name} is required.",
                    actual=None,
                    expected="integer",
                    missing_required_field=field_name,
                )
            )
            return None
        if not isinstance(value, int) or isinstance(value, bool):
            details.append(
                self._validation_error(
                    "EVIDENCE_RANGE_NOT_INTEGER",
                    f"{path}.{field_name}",
                    f"{field_name} must be an integer.",
                    actual=value,
                    expected="integer",
                )
            )
            return None
        return value

    def _plain_int(self, value: Any) -> Optional[int]:
        if isinstance(value, bool) or value is None:
            return None
        if isinstance(value, int):
            return value
        return None

    def _decorate_details(
        self,
        details: list[dict[str, Any]],
        target_ref: str,
        target_kind: str,
        target_name: str,
        target_range: Mapping[str, Any],
    ) -> list[dict[str, Any]]:
        decorated: list[dict[str, Any]] = []
        report_target_range = self._public_target_range(target_range)
        for detail in details:
            item = dict(detail)
            item.setdefault("targetRef", target_ref)
            item.setdefault("targetKind", target_kind)
            if target_name:
                item.setdefault("targetName", target_name)
            if report_target_range:
                item.setdefault("targetRange", report_target_range)
                if report_target_range.get("lineStart") is not None:
                    item.setdefault("targetLineStart", report_target_range.get("lineStart"))
                if report_target_range.get("lineEnd") is not None:
                    item.setdefault("targetLineEnd", report_target_range.get("lineEnd"))
            evidence_range = item.get("evidenceRange")
            if isinstance(evidence_range, Mapping):
                if evidence_range.get("lineStart") is not None:
                    item.setdefault("evidenceLineStart", evidence_range.get("lineStart"))
                if evidence_range.get("lineEnd") is not None:
                    item.setdefault("evidenceLineEnd", evidence_range.get("lineEnd"))
            decorated.append(item)
        return decorated

    def _validation_error(
        self,
        code: str,
        path: str,
        message: str,
        *,
        actual: Any = None,
        expected: Optional[str] = None,
        allowed_values: Optional[list[str]] = None,
        missing_required_field: Optional[str] = None,
        field: Optional[str] = None,
        evidence_range: Optional[Mapping[str, Any]] = None,
        raw_preview: Optional[str] = None,
        response_truncated: Optional[bool] = None,
        line: Optional[int] = None,
        column: Optional[int] = None,
        char_position: Optional[int] = None,
    ) -> dict[str, Any]:
        error: dict[str, Any] = {
            "code": code,
            "errorType": code,
            "jsonPath": path,
            "message": message,
            "actual": actual,
            "invalidValue": actual,
            "expected": expected,
        }
        if allowed_values is not None:
            error["allowedValues"] = list(allowed_values)
        if missing_required_field is not None:
            error["missingRequiredField"] = missing_required_field
        if field is not None:
            error["field"] = field
        if evidence_range is not None:
            error["evidenceRange"] = dict(evidence_range)
        if raw_preview is not None:
            error["rawPreview"] = raw_preview
        if response_truncated is not None:
            error["responseTruncated"] = response_truncated
        if line is not None:
            error["line"] = line
        if column is not None:
            error["column"] = column
        if char_position is not None:
            error["charPosition"] = char_position
        return error

    def _validation_report(
        self,
        details: list[dict[str, Any]],
        target_ref: str,
        target_kind: str,
        target_name: str,
        target_range: Mapping[str, Any],
    ) -> dict[str, Any]:
        report: dict[str, Any] = {
            "errorType": "TARGET_RESPONSE_VALIDATION_FAILED",
            "targetRef": target_ref,
            "targetKind": target_kind,
            "validationErrors": details,
        }
        if target_name:
            report["targetName"] = target_name
        public_range = self._public_target_range(target_range)
        if public_range:
            report["targetRange"] = public_range
        return report

    def _public_target_range(self, target_range: Mapping[str, Any]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key in ("lineStart", "lineEnd"):
            value = target_range.get(key)
            if value is not None:
                result[key] = value
        return result

    def _json_failure(
        self,
        raw: str,
        detail: dict[str, Any],
        target_ref: str,
        target_kind: str,
        target_name: str,
        target_range: Mapping[str, Any],
    ) -> GraphAnalysisParseFailure:
        details = self._decorate_details([detail], target_ref, target_kind, target_name, target_range)
        report = self._validation_report(details, target_ref, target_kind, target_name, target_range)
        report["errorType"] = "TARGET_RESPONSE_JSON_PARSE_FAILED"
        return GraphAnalysisParseFailure(
            "ANALYSIS_AI_INVALID_JSON",
            str(detail.get("message") or "AI analyzer returned invalid JSON."),
            str(raw)[:4000],
            details,
            report,
        )

    def _failure(
        self,
        raw: str,
        details: list[dict[str, Any]],
        target_ref: str,
        target_kind: str,
        target_name: str,
        target_range: Mapping[str, Any],
    ) -> GraphAnalysisParseFailure:
        decorated = self._decorate_details(details, target_ref, target_kind, target_name, target_range)
        summaries = "; ".join(f"{detail.get('code')} at {detail.get('jsonPath')}: {detail.get('message')}" for detail in decorated[:3])
        if len(decorated) > 3:
            summaries = f"{summaries}; and {len(decorated) - 3} more error(s)"
        report = self._validation_report(decorated, target_ref, target_kind, target_name, target_range)
        return GraphAnalysisParseFailure(
            "ANALYSIS_AI_SCHEMA_INVALID",
            f"AI analyzer response failed target-anchor validation: {summaries}",
            str(raw)[:4000],
            decorated,
            report,
        )
