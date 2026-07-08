from __future__ import annotations

import json
from typing import Any, Iterable, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, ResolutionStatusContract
from knowledge_service.analysis_parse_failure import GraphAnalysisParseFailure
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef
from knowledge_service.target_enrichment.constants import TARGET_RESPONSE_SCHEMA_VERSION


class TargetResponseParserValidator:
    _TOP_LEVEL_FIELDS = {"schemaVersion", "claims", "semanticEdges", "diagnostics"}
    _CLAIM_FIELDS = {"localId", "targetRef", "claimKind", "summary", "confidence", "evidence"}
    _EDGE_FIELDS = {"localId", "fromRef", "toRef", "edgeType", "resolutionStatus", "confidence", "evidence", "unresolvedTarget"}
    _EVIDENCE_FIELDS = {"lineStart", "lineEnd", "text"}
    _DIAGNOSTIC_FIELDS = {"code", "stage", "severity", "message"}
    _DIAGNOSTIC_SEVERITIES = {"INFO", "WARN", "ERROR"}

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
        target_ref = str(target_anchor.get("ref") or "")
        known_refs = self._known_refs(llm_input)
        ref_to_stable_key = {str(key): str(value) for key, value in (payload.get("_refToStableKey") or {}).items()}
        ref_to_kind = {str(key): str(value) for key, value in (payload.get("_refToKind") or {}).items()}
        details: list[dict[str, Any]] = []

        self._validate_object_fields(parsed, "$", self._TOP_LEVEL_FIELDS, details)
        if parsed.get("schemaVersion") != TARGET_RESPONSE_SCHEMA_VERSION:
            details.append(
                self._schema_error(
                    "$.schemaVersion",
                    "schemaVersion must match the target-anchor response contract.",
                    actual=parsed.get("schemaVersion"),
                    expected=TARGET_RESPONSE_SCHEMA_VERSION,
                )
            )
        claims = parsed.get("claims")
        edges = parsed.get("semanticEdges")
        diagnostics = parsed.get("diagnostics")
        if not isinstance(claims, list):
            details.append(self._schema_error("$.claims", "claims must be an array.", actual=claims, expected="array"))
            claims = []
        if not isinstance(edges, list):
            details.append(self._schema_error("$.semanticEdges", "semanticEdges must be an array.", actual=edges, expected="array"))
            edges = []
        if not isinstance(diagnostics, list):
            details.append(self._schema_error("$.diagnostics", "diagnostics must be an array.", actual=diagnostics, expected="array"))
            diagnostics = []

        for index, item in enumerate(claims):
            self._validate_claim(item, index, target_ref, known_refs, contract, line_count, details)
        for index, item in enumerate(edges):
            self._validate_edge(item, index, target_ref, known_refs, ref_to_kind, contract, line_count, details)
        for index, item in enumerate(diagnostics):
            self._validate_diagnostic(item, index, details)

        if details:
            return self._failure(raw, *details)
        try:
            return self._to_graph_result(
                parsed,
                target_ref=target_ref,
                ref_to_stable_key=ref_to_stable_key,
            )
        except (TypeError, ValueError) as exc:
            return self._failure(raw, self._schema_error("$", str(exc), expected="valid graph result"))

    def _validate_claim(
        self,
        item: Any,
        index: int,
        target_ref: str,
        known_refs: set[str],
        contract: AnalysisGraphContract,
        line_count: int,
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.claims[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "claim must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._CLAIM_FIELDS, details)
        self._required_string(item, path, "localId", details)
        target = self._required_string(item, path, "targetRef", details)
        if target and target not in known_refs:
            details.append(self._schema_error(f"{path}.targetRef", "targetRef is not in anchorRegistry.", actual=target, expected="known ref"))
        if target and target != target_ref:
            details.append(self._schema_error(f"{path}.targetRef", "claim targetRef must equal targetAnchor.ref.", actual=target, expected=target_ref))
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
        self._required_string(item, path, "summary", details)
        self._confidence(item, path, details)
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, details)

    def _validate_edge(
        self,
        item: Any,
        index: int,
        target_ref: str,
        known_refs: set[str],
        ref_to_kind: Mapping[str, str],
        contract: AnalysisGraphContract,
        line_count: int,
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.semanticEdges[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "semantic edge must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._EDGE_FIELDS, details)
        self._required_string(item, path, "localId", details)
        from_ref = self._required_string(item, path, "fromRef", details)
        if from_ref and from_ref not in known_refs:
            details.append(self._schema_error(f"{path}.fromRef", "fromRef is not in anchorRegistry.", actual=from_ref, expected="known ref"))
        if from_ref and from_ref != target_ref:
            details.append(self._schema_error(f"{path}.fromRef", "semantic edge fromRef must equal targetAnchor.ref.", actual=from_ref, expected=target_ref))
        to_ref = item.get("toRef")
        if to_ref is not None and (not isinstance(to_ref, str) or not to_ref.strip()):
            details.append(self._schema_error(f"{path}.toRef", "toRef must be a known ref or null.", actual=to_ref, expected="known ref or null"))
        elif isinstance(to_ref, str) and to_ref not in known_refs:
            details.append(self._schema_error(f"{path}.toRef", "toRef is not in anchorRegistry.", actual=to_ref, expected="known ref or null"))
        edge_type = self._required_string(item, path, "edgeType", details)
        if edge_type and edge_type not in contract.allowed_edge_types:
            details.append(
                self._schema_error(
                    f"{path}.edgeType",
                    "edgeType is not allowed by the effective analysis graph policy.",
                    actual=edge_type,
                    expected="allowed edge type",
                    allowed_values=list(contract.allowed_edge_types),
                )
            )
        elif edge_type:
            self._edge_endpoint(edge_type, from_ref, to_ref, ref_to_kind, contract, path, details)
        resolution_status = self._required_string(item, path, "resolutionStatus", details)
        if resolution_status and resolution_status not in contract.allowed_resolution_statuses:
            details.append(
                self._schema_error(
                    f"{path}.resolutionStatus",
                    "resolutionStatus is not allowed by the effective analysis graph policy.",
                    actual=resolution_status,
                    expected="allowed resolution status",
                    allowed_values=list(contract.allowed_resolution_statuses),
                )
            )
        elif resolution_status:
            self._edge_resolution_status(item, path, resolution_status, ResolutionStatusContract.from_graph_contract(contract), details)
        self._confidence(item, path, details)
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, details)

    def _validate_diagnostic(self, item: Any, index: int, details: list[dict[str, Any]]) -> None:
        path = f"$.diagnostics[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "diagnostic must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._DIAGNOSTIC_FIELDS, details)
        self._required_string(item, path, "code", details)
        stage = self._required_string(item, path, "stage", details)
        if stage and stage != "LLM_ENRICHMENT":
            details.append(self._schema_error(f"{path}.stage", "diagnostic stage must be LLM_ENRICHMENT.", actual=stage, expected="LLM_ENRICHMENT"))
        severity = self._required_string(item, path, "severity", details)
        if severity and severity not in self._DIAGNOSTIC_SEVERITIES:
            details.append(
                self._schema_error(
                    f"{path}.severity",
                    "diagnostic severity is not allowed.",
                    actual=severity,
                    expected="diagnostic severity",
                    allowed_values=sorted(self._DIAGNOSTIC_SEVERITIES),
                )
            )
        self._required_string(item, path, "message", details)

    def _edge_endpoint(
        self,
        edge_type: str,
        from_ref: Optional[str],
        to_ref: Any,
        ref_to_kind: Mapping[str, str],
        contract: AnalysisGraphContract,
        path: str,
        details: list[dict[str, Any]],
    ) -> None:
        from_kind = ref_to_kind.get(str(from_ref)) if from_ref else None
        to_kind = ref_to_kind.get(str(to_ref)) if to_ref else None
        allowed_from = list(contract.edge_from_kinds.get(edge_type, ()))
        allowed_to = list(contract.edge_to_kinds.get(edge_type, ()))
        if from_kind is not None and allowed_from and from_kind not in allowed_from:
            details.append(
                self._schema_error(
                    f"{path}.fromRef",
                    "edge source anchor kind violates endpoint rules.",
                    actual=from_kind,
                    expected=f"{edge_type} source kind",
                    allowed_values=allowed_from,
                )
            )
        if to_kind is not None and allowed_to and to_kind not in allowed_to:
            details.append(
                self._schema_error(
                    f"{path}.toRef",
                    "edge target anchor kind violates endpoint rules.",
                    actual=to_kind,
                    expected=f"{edge_type} target kind",
                    allowed_values=allowed_to,
                )
            )
        if to_ref is not None and not allowed_to:
            details.append(
                self._schema_error(
                    f"{path}.toRef",
                    "edge target is not allowed by endpoint rules for this edge type.",
                    actual=to_ref,
                    expected=f"{edge_type} target kind",
                    allowed_values=[],
                )
            )

    def _edge_resolution_status(
        self,
        item: Mapping[str, Any],
        path: str,
        status: str,
        rules: ResolutionStatusContract,
        details: list[dict[str, Any]],
    ) -> None:
        to_ref = item.get("toRef")
        unresolved_target = item.get("unresolvedTarget")
        if rules.requires_to_ref(status) and not to_ref:
            details.append(self._schema_error(f"{path}.resolutionStatus", "edge resolution requires toRef.", actual=status, expected="toRef"))
        if rules.forbids_to_ref(status) and to_ref:
            details.append(self._schema_error(f"{path}.resolutionStatus", "edge resolution must not have toRef.", actual=status, expected="null toRef"))
        if rules.requires_unresolved_target(status) and not isinstance(unresolved_target, dict):
            details.append(
                self._schema_error(
                    f"{path}.unresolvedTarget",
                    "edge resolution requires unresolvedTarget.",
                    actual=unresolved_target,
                    expected="object",
                )
            )
        if unresolved_target is not None and not rules.allows_unresolved_target(status):
            details.append(self._schema_error(f"{path}.unresolvedTarget", "edge resolution must not include unresolvedTarget.", actual=unresolved_target, expected="null"))
        if to_ref is None and unresolved_target is not None and not isinstance(unresolved_target, dict):
            details.append(self._schema_error(f"{path}.unresolvedTarget", "unresolvedTarget must be an object or null.", actual=unresolved_target, expected="object or null"))

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
            text = item.get("text")
            if text is not None and not isinstance(text, str):
                details.append(self._schema_error(f"{evidence_path}.text", "evidence text must be a string.", actual=text, expected="string"))
            if isinstance(text, str) and len(text) > 2000:
                details.append(self._schema_error(f"{evidence_path}.text", "evidence text is too long.", actual=len(text), expected="2000 chars or fewer"))

    def _to_graph_result(
        self,
        parsed: Mapping[str, Any],
        *,
        target_ref: str,
        ref_to_stable_key: Mapping[str, str],
    ) -> GraphAnalysisResult:
        claims: list[GraphClaim] = []
        for item in parsed.get("claims") or []:
            stable_key = ref_to_stable_key[str(item["targetRef"])]
            claims.append(
                GraphClaim(
                    localId=str(item["localId"]),
                    nodeLocalId=stable_key,
                    claimKind=str(item["claimKind"]),
                    summary=str(item["summary"]),
                    confidence=float(item["confidence"]),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    metadata={"factOrigin": "LLM"},
                )
            )
        edges: list[GraphEdge] = []
        for item in parsed.get("semanticEdges") or []:
            to_ref = item.get("toRef")
            edges.append(
                GraphEdge(
                    localId=str(item["localId"]),
                    fromNodeLocalId=ref_to_stable_key[str(item["fromRef"] or target_ref)],
                    toNodeLocalId=ref_to_stable_key[str(to_ref)] if to_ref else None,
                    edgeType=str(item["edgeType"]),
                    resolutionStatus=str(item["resolutionStatus"]),
                    confidence=float(item["confidence"]),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    unresolvedTarget=item.get("unresolvedTarget"),
                    metadata={"factOrigin": "LLM"},
                )
            )
        return GraphAnalysisResult(
            nodes=[],
            edges=edges,
            claims=claims,
            diagnostics=[dict(item) for item in parsed.get("diagnostics") or []],
        )

    def _evidence_refs(self, value: Iterable[Any]) -> list[GraphEvidenceRef]:
        refs: list[GraphEvidenceRef] = []
        for item in value:
            refs.append(GraphEvidenceRef(lineStart=int(item["lineStart"]), lineEnd=int(item["lineEnd"]), text=item.get("text")))
        return refs

    def _known_refs(self, llm_input: Mapping[str, Any]) -> set[str]:
        registry = llm_input.get("anchorRegistry")
        if not isinstance(registry, list):
            return set()
        return {str(item.get("ref")) for item in registry if isinstance(item, Mapping) and item.get("ref")}

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
        if not isinstance(value, int):
            details.append(self._schema_error(f"{path}.{field_name}", f"{field_name} must be an integer.", actual=value, expected="integer"))
            return None
        return value

    def _confidence(self, item: Mapping[str, Any], path: str, details: list[dict[str, Any]]) -> None:
        value = item.get("confidence")
        if value is None:
            details.append(
                self._schema_error(
                    f"{path}.confidence",
                    "confidence is required.",
                    actual=None,
                    expected="number between 0 and 1",
                    missing_required_field="confidence",
                )
            )
            return
        if not isinstance(value, (int, float)) or isinstance(value, bool) or float(value) < 0 or float(value) > 1:
            details.append(self._schema_error(f"{path}.confidence", "confidence must be a number between 0 and 1.", actual=value, expected="number between 0 and 1"))

    def _schema_error(
        self,
        path: str,
        message: str,
        *,
        actual: Any = None,
        expected: Optional[str] = None,
        allowed_values: Optional[list[str]] = None,
        missing_required_field: Optional[str] = None,
    ) -> dict[str, Any]:
        return {
            "errorType": "SCHEMA_VALIDATION_ERROR",
            "jsonPath": path,
            "message": message,
            "actual": actual,
            "invalidValue": actual,
            "expected": expected,
            "allowedValues": list(allowed_values or []),
            "missingRequiredField": missing_required_field,
        }

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
