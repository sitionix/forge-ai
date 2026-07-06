from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Mapping

from pydantic import ValidationError

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, GraphContractProvider
from knowledge_service.graph_schema import (
    GraphAnalysisResult,
    GraphClaim,
    GraphEdge,
    GraphEvidenceRef,
)


MAX_GRAPH_RAW_PREVIEW_CHARS = 4000
MAX_GRAPH_ERROR_PREVIEW_CHARS = 800


@dataclass(frozen=True)
class GraphAnalysisParseFailure:
    code: str
    message: str
    raw_preview: str
    error_details: list[dict[str, Any]] = field(default_factory=list)


class GraphAnalysisResponseParser:
    def __init__(self, contract_provider: GraphContractProvider | None = None):
        self.contract_provider = contract_provider or GraphContractProvider()

    def parse(
        self,
        raw: str,
        line_count: int,
        contract: AnalysisGraphContract | None = None,
        known_node_kinds: dict[str, str] | None = None,
    ) -> GraphAnalysisResult | GraphAnalysisParseFailure:
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
        effective_contract = self._effective_contract(parsed, contract)
        try:
            if str(parsed.get("schemaVersion") or "").startswith("knowledge.graph.enrichment."):
                enrichment_errors = self._validate_enrichment_payload(parsed, line_count, effective_contract, known_node_kinds or {})
                if enrichment_errors:
                    return GraphAnalysisParseFailure(
                        "ANALYSIS_AI_SCHEMA_INVALID",
                        self._details_message("AI analyzer response does not match graph schema", enrichment_errors),
                        self._preview(raw),
                        enrichment_errors,
                    )
                result = self._parse_enrichment(parsed)
                result.validate_lines(line_count)
                graph_errors = self._validate_result_contract(result, effective_contract, known_node_kinds or {})
                if graph_errors:
                    return GraphAnalysisParseFailure(
                        "ANALYSIS_AI_SCHEMA_INVALID",
                        self._details_message("AI analyzer response does not match graph schema", graph_errors),
                        self._preview(raw),
                        graph_errors,
                    )
                return result
            result_payload = {key: value for key, value in parsed.items() if key != "analysisPolicy"}
            result = GraphAnalysisResult.parse_obj(result_payload)
            result.validate_lines(line_count)
            result.validate_references()
            graph_errors = self._validate_result_contract(result, effective_contract, self._node_kind_map(result))
            if graph_errors:
                return GraphAnalysisParseFailure(
                    "ANALYSIS_AI_SCHEMA_INVALID",
                    self._details_message("AI analyzer response does not match graph schema", graph_errors),
                    self._preview(raw),
                    graph_errors,
                )
            return result
        except ValidationError as exc:
            details = self._validation_error_details(exc, parsed, effective_contract)
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

    def _effective_contract(self, parsed: dict[str, Any], contract: AnalysisGraphContract | None) -> AnalysisGraphContract:
        return contract or self.contract_provider.resolve_payload(parsed)

    def _validation_error_details(self, exc: ValidationError, parsed: Any, contract: AnalysisGraphContract) -> list[dict[str, Any]]:
        details: list[dict[str, Any]] = []
        for error in exc.errors():
            loc = tuple(error.get("loc") or ())
            path = _json_path(loc)
            field = str(loc[-1]) if loc else None
            missing = error.get("type") == "value_error.missing"
            allowed = self._allowed_values_for_path(path, contract)
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
            "invalidValue": self._jsonable(actual),
            "expected": expected,
            "allowedValues": list(allowed_values or []),
            "missingRequiredField": missing_required_field,
        }

    def _graph_validation_error(
        self,
        path: str,
        reason: str,
        *,
        graph_entity_id: str | None = None,
        invalid_value: Any = None,
        allowed_values: list[str] | None = None,
    ) -> dict[str, Any]:
        return {
            "errorType": "GRAPH_VALIDATION_ERROR",
            "jsonPath": path,
            "graphEntityId": graph_entity_id,
            "reason": reason,
            "invalidValue": self._jsonable(invalid_value),
            "allowedValues": list(allowed_values or []),
        }

    def _expected_for_error(self, error: dict[str, Any]) -> str:
        error_type = str(error.get("type") or "")
        if "type_error" in error_type:
            return str(error_type).replace("type_error.", "")
        if "value_error" in error_type:
            return "valid value"
        return str(error.get("msg") or "valid value")

    def _allowed_values_for_path(self, path: str, contract: AnalysisGraphContract) -> list[str]:
        if path.endswith(".nodeKind"):
            return list(contract.allowed_node_kinds)
        if path.endswith(".edgeType"):
            return list(contract.allowed_edge_types)
        if path.endswith(".claimKind"):
            return list(contract.allowed_claim_kinds)
        if path.endswith(".status"):
            return list(contract.allowed_statuses)
        if path.endswith(".factOrigin"):
            return list(contract.allowed_origins)
        if path.endswith(".evidenceKind"):
            return list(contract.allowed_evidence_kinds)
        if path.endswith(".resolutionStatus"):
            return list(contract.allowed_resolution_statuses)
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

    def _validate_enrichment_payload(
        self,
        parsed: dict[str, Any],
        line_count: int,
        contract: AnalysisGraphContract,
        known_node_kinds: dict[str, str],
    ) -> list[dict[str, Any]]:
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
            target = item.get("targetStableKey")
            if not target:
                details.append(
                    self._schema_error(
                        f"{path}.targetStableKey",
                        field="targetStableKey",
                        message="targetStableKey is required.",
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
                        expected="allowed graph claim kind",
                        allowed_values=list(contract.allowed_claim_kinds),
                        missing_required_field="claimKind",
                    )
                )
            elif str(claim_kind) not in contract.allowed_claim_kinds:
                details.append(
                    self._schema_error(
                        f"{path}.claimKind",
                        field="claimKind",
                        message="claimKind is not allowed by the effective analysis graph profiles.",
                        actual=claim_kind,
                        expected="allowed graph claim kind",
                        allowed_values=list(contract.allowed_claim_kinds),
                    )
                )
            self._validate_metadata_contract(item.get("metadata"), f"{path}.metadata", contract, details)
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
                self._validate_metadata_contract(evidence_item.get("metadata"), f"{evidence_path}.metadata", contract, details)
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
            if not edge_type:
                details.append(
                    self._schema_error(
                        f"{path}.edgeType",
                        field="edgeType",
                        message="edgeType is required.",
                        actual=edge_type,
                        expected="allowed graph edge kind",
                        allowed_values=list(contract.allowed_edge_types),
                        missing_required_field="edgeType",
                    )
                )
            elif str(edge_type) not in contract.allowed_edge_types:
                details.append(
                    self._schema_error(
                        f"{path}.edgeType",
                        field="edgeType",
                        message="edgeType is not allowed by the effective analysis graph profiles.",
                        actual=edge_type,
                        expected="allowed graph edge kind",
                        allowed_values=list(contract.allowed_edge_types),
                    )
                )
            else:
                from_key = item.get("fromStableKey")
                to_key = item.get("toStableKey")
                details.extend(self._validate_edge_endpoints(path, str(edge_type), from_key, to_key, known_node_kinds, contract))
            self._validate_metadata_contract(item.get("metadata"), f"{path}.metadata", contract, details)
            self._validate_edge_resolution_status(
                item.get("resolutionStatus"),
                item.get("toStableKey"),
                item.get("unresolvedTarget"),
                path,
                contract,
                details,
            )
            for evidence_index, evidence_item in enumerate(item.get("evidence") or []):
                if isinstance(evidence_item, dict):
                    self._validate_metadata_contract(evidence_item.get("metadata"), f"{path}.evidence[{evidence_index}].metadata", contract, details)
        return details

    def _validate_result_contract(
        self,
        result: GraphAnalysisResult,
        contract: AnalysisGraphContract,
        known_node_kinds: dict[str, str],
    ) -> list[dict[str, Any]]:
        details: list[dict[str, Any]] = []
        for index, node in enumerate(result.nodes):
            path = f"$.nodes[{index}]"
            if node.nodeKind not in contract.allowed_node_kinds:
                details.append(
                    self._schema_error(
                        f"{path}.nodeKind",
                        field="nodeKind",
                        message="nodeKind is not allowed by the effective analysis graph profiles.",
                        actual=node.nodeKind,
                        expected="allowed graph node kind",
                        allowed_values=list(contract.allowed_node_kinds),
                    )
                )
            self._validate_metadata_contract(node.metadata, f"{path}.metadata", contract, details)
        for index, edge in enumerate(result.edges):
            path = f"$.edges[{index}]"
            if edge.edgeType not in contract.allowed_edge_types:
                details.append(
                    self._schema_error(
                        f"{path}.edgeType",
                        field="edgeType",
                        message="edgeType is not allowed by the effective analysis graph profiles.",
                        actual=edge.edgeType,
                        expected="allowed graph edge kind",
                        allowed_values=list(contract.allowed_edge_types),
                    )
                )
            else:
                details.extend(
                    self._validate_edge_endpoints(
                        path,
                        edge.edgeType,
                        edge.fromNodeLocalId,
                        edge.toNodeLocalId,
                        known_node_kinds,
                        contract,
                    )
                )
            self._validate_edge_resolution_status(edge.resolutionStatus, edge.toNodeLocalId, edge.unresolvedTarget, path, contract, details)
            self._validate_metadata_contract(edge.metadata, f"{path}.metadata", contract, details)
            for evidence_index, evidence in enumerate(edge.evidence):
                self._validate_metadata_contract(evidence.metadata, f"{path}.evidence[{evidence_index}].metadata", contract, details)
        for index, claim in enumerate(result.claims):
            path = f"$.claims[{index}]"
            if claim.claimKind not in contract.allowed_claim_kinds:
                details.append(
                    self._schema_error(
                        f"{path}.claimKind",
                        field="claimKind",
                        message="claimKind is not allowed by the effective analysis graph profiles.",
                        actual=claim.claimKind,
                        expected="allowed graph claim kind",
                        allowed_values=list(contract.allowed_claim_kinds),
                    )
                )
            self._validate_metadata_contract(claim.metadata, f"{path}.metadata", contract, details)
            for evidence_index, evidence in enumerate(claim.evidence):
                self._validate_metadata_contract(evidence.metadata, f"{path}.evidence[{evidence_index}].metadata", contract, details)
        return details

    def _validate_metadata_contract(
        self,
        metadata: Any,
        path: str,
        contract: AnalysisGraphContract,
        details: list[dict[str, Any]],
    ) -> None:
        if metadata is None:
            return
        if not isinstance(metadata, dict):
            details.append(self._schema_error(path, field="metadata", message="metadata must be an object.", actual=metadata, expected="object"))
            return
        self._validate_status(metadata.get("status"), f"{path}.status", contract, details)
        self._validate_origin(metadata.get("factOrigin"), f"{path}.factOrigin", contract, details)
        self._validate_evidence_kind(metadata.get("evidenceKind"), f"{path}.evidenceKind", contract, details)

    def _validate_status(self, value: Any, path: str, contract: AnalysisGraphContract, details: list[dict[str, Any]]) -> None:
        if value is None:
            return
        if str(value) not in contract.allowed_statuses:
            details.append(
                self._schema_error(
                    path,
                    field="status",
                    message="status is not declared by the analysis policy.",
                    actual=value,
                    expected="allowed graph status",
                    allowed_values=list(contract.allowed_statuses),
                )
            )

    def _validate_origin(self, value: Any, path: str, contract: AnalysisGraphContract, details: list[dict[str, Any]]) -> None:
        if value is None:
            return
        if str(value) not in contract.allowed_origins:
            details.append(
                self._schema_error(
                    path,
                    field="factOrigin",
                    message="factOrigin is not declared by the analysis policy.",
                    actual=value,
                    expected="allowed graph origin",
                    allowed_values=list(contract.allowed_origins),
                )
            )

    def _validate_evidence_kind(self, value: Any, path: str, contract: AnalysisGraphContract, details: list[dict[str, Any]]) -> None:
        if value is None:
            return
        if str(value) not in contract.allowed_evidence_kinds:
            details.append(
                self._schema_error(
                    path,
                    field="evidenceKind",
                    message="evidenceKind is not declared by the analysis policy.",
                    actual=value,
                    expected="allowed graph evidence kind",
                    allowed_values=list(contract.allowed_evidence_kinds),
                )
            )

    def _validate_resolution_status(self, value: Any, path: str, contract: AnalysisGraphContract, details: list[dict[str, Any]]) -> None:
        if value is None:
            return
        if str(value) not in contract.allowed_resolution_statuses:
            details.append(
                self._schema_error(
                    path,
                    field="resolutionStatus",
                    message="resolutionStatus is not declared by the analysis policy.",
                    actual=value,
                    expected="allowed graph resolution status",
                    allowed_values=list(contract.allowed_resolution_statuses),
                )
            )

    def _validate_edge_resolution_status(
        self,
        value: Any,
        to_id: Any,
        unresolved_target: Any,
        path: str,
        contract: AnalysisGraphContract,
        details: list[dict[str, Any]],
    ) -> None:
        if value is None:
            return
        status_path = f"{path}.resolutionStatus"
        status = str(value)
        if status not in contract.allowed_resolution_statuses:
            self._validate_resolution_status(value, status_path, contract, details)
            return
        resolved_statuses = self._contract_statuses(contract, "RESOLVED")
        no_target_statuses = self._contract_statuses(
            contract,
            "EXTERNAL_TARGET",
            "DYNAMIC_TARGET",
            "UNRESOLVED",
            "MULTIPLE_CANDIDATES",
        )
        unresolved_target_required_statuses = self._contract_statuses(contract, "EXTERNAL_TARGET", "DYNAMIC_TARGET")
        if status in resolved_statuses and not to_id:
            details.append(
                self._schema_error(
                    status_path,
                    field="resolutionStatus",
                    message="RESOLVED edge resolution requires toNodeLocalId.",
                    actual=status,
                    expected="resolution status consistent with edge target",
                    allowed_values=list(contract.allowed_resolution_statuses),
                )
            )
        if status in no_target_statuses and to_id:
            details.append(
                self._schema_error(
                    status_path,
                    field="resolutionStatus",
                    message=f"{status} edge resolution must not have toNodeLocalId.",
                    actual=status,
                    expected="resolution status consistent with edge target",
                    allowed_values=list(contract.allowed_resolution_statuses),
                )
            )
        if status in unresolved_target_required_statuses and not unresolved_target:
            details.append(
                self._schema_error(
                    f"{path}.unresolvedTarget",
                    field="unresolvedTarget",
                    message=f"{status} edge resolution requires unresolvedTarget.",
                    actual=unresolved_target,
                    expected="unresolved target details",
                )
            )

    def _validate_edge_endpoints(
        self,
        path: str,
        edge_type: str,
        from_id: Any,
        to_id: Any,
        known_node_kinds: dict[str, str],
        contract: AnalysisGraphContract,
    ) -> list[dict[str, Any]]:
        details: list[dict[str, Any]] = []
        from_kind = known_node_kinds.get(str(from_id)) if from_id is not None else None
        to_kind = known_node_kinds.get(str(to_id)) if to_id is not None else None
        allowed_from = list(contract.edge_from_kinds.get(edge_type, ()))
        allowed_to = list(contract.edge_to_kinds.get(edge_type, ()))
        if from_kind is not None and allowed_from and from_kind not in allowed_from:
            details.append(
                self._schema_error(
                    f"{path}.fromNodeLocalId",
                    field="fromNodeLocalId",
                    message="edge source node kind violates the analysis policy endpoint rule.",
                    actual=from_kind,
                    expected=f"{edge_type} source endpoint kind",
                    allowed_values=allowed_from,
                )
            )
        if to_kind is not None and allowed_to and to_kind not in allowed_to:
            details.append(
                self._schema_error(
                    f"{path}.toNodeLocalId",
                    field="toNodeLocalId",
                    message="edge target node kind violates the analysis policy endpoint rule.",
                    actual=to_kind,
                    expected=f"{edge_type} target endpoint kind",
                    allowed_values=allowed_to,
                )
            )
        if to_id is not None and not allowed_to:
            details.append(
                self._schema_error(
                    f"{path}.toNodeLocalId",
                    field="toNodeLocalId",
                    message="edge target node is not allowed for this edge type by the analysis policy endpoint rule.",
                    actual=to_id,
                    expected=f"{edge_type} target endpoint kind",
                    allowed_values=[],
                )
            )
        return details

    def _node_kind_map(self, result: GraphAnalysisResult) -> dict[str, str]:
        return {node.localId: node.nodeKind for node in result.nodes}

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
            metadata = self._enrichment_metadata(item)
            claims.append(
                GraphClaim(
                    localId=str(item.get("localId") or f"claim{index}"),
                    nodeLocalId=str(item.get("targetStableKey") or ""),
                    claimKind=str(item.get("claimKind") or ""),
                    summary=str(item.get("summary") or ""),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    confidence=float(item.get("confidence") if item.get("confidence") is not None else 0.0),
                    metadata=metadata,
                )
            )
        edges = []
        for index, item in enumerate(parsed.get("semanticEdges") or [], start=1):
            if not isinstance(item, dict):
                continue
            metadata = self._enrichment_metadata(item)
            edges.append(
                GraphEdge(
                    localId=str(item.get("localId") or f"semantic{index}"),
                    fromNodeLocalId=str(item.get("fromStableKey") or ""),
                    toNodeLocalId=item.get("toStableKey"),
                    edgeType=str(item.get("edgeType") or ""),
                    resolutionStatus=item.get("resolutionStatus"),
                    confidence=float(item.get("confidence") if item.get("confidence") is not None else 0.0),
                    evidence=self._evidence_refs(item.get("evidence") or []),
                    unresolvedTarget=item.get("unresolvedTarget"),
                    metadata=metadata,
                )
            )
        diagnostics = parsed.get("diagnostics") or []
        return GraphAnalysisResult(nodes=[], edges=edges, claims=claims, diagnostics=diagnostics)

    def _enrichment_metadata(self, item: Mapping[str, Any]) -> dict[str, Any]:
        metadata = dict(item.get("metadata") or {})
        metadata.pop("resolutionStatus", None)
        metadata["factOrigin"] = "LLM"
        return metadata

    def _contract_statuses(self, contract: AnalysisGraphContract, *statuses: str) -> set[str]:
        allowed = set(contract.allowed_resolution_statuses)
        return {status for status in statuses if status in allowed}

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


def _json_path(loc: tuple[Any, ...]) -> str:
    path = "$"
    for part in loc:
        if isinstance(part, int):
            path += f"[{part}]"
        else:
            path += f".{part}"
    return path
