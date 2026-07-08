from __future__ import annotations

import json
from typing import Any, Iterable, Mapping, Optional

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, ResolutionStatusContract
from knowledge_service.analysis_parse_failure import GraphAnalysisParseFailure
from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef


class TargetResponseParserValidator:
    _TOP_LEVEL_FIELDS = {"claims", "semanticEdges"}
    _CLAIM_FIELDS = {"claimKind", "summary", "evidence"}
    _EDGE_FIELDS = {"edgeType", "toRef", "unresolvedStatus", "unresolvedTarget", "evidence"}
    _EVIDENCE_FIELDS = {"lineStart", "lineEnd"}
    _DEFAULT_CONFIDENCE = 0.8
    _MAX_SUMMARY_CHARS = 600
    _MAX_EVIDENCE_TEXT_CHARS = 2000
    _MAX_UNRESOLVED_TARGET_JSON_CHARS = 2000

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
        target_kind = str(target_anchor.get("kind") or "")
        known_refs = self._known_refs(llm_input)
        ref_to_stable_key = {str(key): str(value) for key, value in (payload.get("_refToStableKey") or {}).items()}
        ref_to_kind = {str(key): str(value) for key, value in (payload.get("_refToKind") or {}).items()}
        target_stable_key = ref_to_stable_key.get(target_ref)
        content_by_line = self._content_by_line(llm_input)
        edge_options = self._edge_options(llm_input, contract, target_kind)
        allowed_edge_types = list(edge_options.keys())
        allowed_unresolved_statuses = self._allowed_unresolved_statuses(llm_input, contract)
        details: list[dict[str, Any]] = []

        if not target_ref:
            details.append(self._schema_error("$.targetAnchor.ref", "Target input anchor ref is required.", expected="targetAnchor.ref"))
        elif target_ref not in known_refs:
            details.append(self._schema_error("$.targetAnchor.ref", "targetAnchor.ref is not in anchorRegistry.", actual=target_ref, expected="known ref"))
        if not target_stable_key:
            details.append(self._schema_error("$.targetAnchor.ref", "targetAnchor.ref has no backend stable key mapping.", actual=target_ref, expected="stable key mapping"))

        self._validate_object_fields(parsed, "$", self._TOP_LEVEL_FIELDS, details)
        claims = parsed.get("claims")
        edges = parsed.get("semanticEdges")
        if not isinstance(claims, list):
            details.append(self._schema_error("$.claims", "claims must be an array.", actual=claims, expected="array"))
            claims = []
        if not isinstance(edges, list):
            details.append(self._schema_error("$.semanticEdges", "semanticEdges must be an array.", actual=edges, expected="array"))
            edges = []

        for index, item in enumerate(claims):
            self._validate_claim(item, index, contract, line_count, details)
        for index, item in enumerate(edges):
            self._validate_edge(
                item,
                index,
                target_kind,
                target_ref,
                known_refs,
                ref_to_kind,
                contract,
                edge_options,
                allowed_unresolved_statuses,
                line_count,
                details,
            )

        if details:
            return self._failure(raw, *self._decorate_details(details, target_ref, target_kind, allowed_edge_types, allowed_unresolved_statuses))
        try:
            return self._to_graph_result(
                parsed,
                target_ref=target_ref,
                target_stable_key=str(target_stable_key),
                ref_to_stable_key=ref_to_stable_key,
                content_by_line=content_by_line,
            )
        except (KeyError, TypeError, ValueError) as exc:
            detail = self._schema_error("$", str(exc), expected="valid graph result")
            return self._failure(raw, *self._decorate_details([detail], target_ref, target_kind, allowed_edge_types, allowed_unresolved_statuses))

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

    def _validate_edge(
        self,
        item: Any,
        index: int,
        target_kind: str,
        target_ref: str,
        known_refs: set[str],
        ref_to_kind: Mapping[str, str],
        contract: AnalysisGraphContract,
        edge_options: Mapping[str, dict[str, Any]],
        allowed_unresolved_statuses: list[str],
        line_count: int,
        details: list[dict[str, Any]],
    ) -> None:
        path = f"$.semanticEdges[{index}]"
        if not isinstance(item, dict):
            details.append(self._schema_error(path, "semantic edge must be an object.", actual=type(item).__name__, expected="object"))
            return
        self._validate_object_fields(item, path, self._EDGE_FIELDS, details)
        edge_type = self._required_string(item, path, "edgeType", details)
        allowed_edge_types = list(edge_options.keys())
        edge_option = edge_options.get(edge_type) if edge_type else None
        if edge_type and edge_type not in allowed_edge_types:
            details.append(
                self._schema_error(
                    f"{path}.edgeType",
                    "edgeType is not listed in edgeOptions for the current target anchor kind.",
                    actual=edge_type,
                    expected="target-scoped allowed edge type",
                    allowed_values=allowed_edge_types,
                )
            )
        elif edge_type:
            allowed_from = list(contract.edge_from_kinds.get(edge_type, ()))
            if allowed_from and target_kind not in allowed_from:
                details.append(
                    self._schema_error(
                        f"{path}.edgeType",
                        "edge source anchor kind violates endpoint rules.",
                        actual=target_kind,
                        expected=f"{edge_type} source kind",
                        allowed_values=allowed_from,
                    )
                )

        has_to_ref = "toRef" in item and item.get("toRef") is not None
        to_ref = item.get("toRef")
        if has_to_ref:
            if not isinstance(to_ref, str) or not to_ref.strip():
                details.append(self._schema_error(f"{path}.toRef", "toRef must be a known ref when present.", actual=to_ref, expected="known ref"))
            elif to_ref == target_ref:
                details.append(
                    self._schema_error(
                        f"{path}.toRef",
                        "self-referential semantic edge is not allowed.",
                        actual=to_ref,
                        expected="non-self target ref",
                        actualToRef=to_ref,
                        targetRef=target_ref,
                    )
                )
            elif to_ref not in known_refs:
                details.append(self._schema_error(f"{path}.toRef", "toRef is not in anchorRegistry.", actual=to_ref, expected="known ref"))
            elif edge_type:
                if edge_option is not None:
                    self._edge_target_option(edge_type, str(to_ref), ref_to_kind, edge_option, path, details)
                self._edge_target_endpoint(edge_type, str(to_ref), ref_to_kind, contract, path, details)
            if "unresolvedStatus" in item:
                details.append(self._schema_error(f"{path}.unresolvedStatus", "unresolvedStatus must be omitted when toRef is present.", actual=item.get("unresolvedStatus"), expected="omit field"))
            if "unresolvedTarget" in item:
                details.append(self._schema_error(f"{path}.unresolvedTarget", "unresolvedTarget must be omitted when toRef is present.", actual=item.get("unresolvedTarget"), expected="omit field"))
        else:
            unresolved_status = self._required_string(item, path, "unresolvedStatus", details)
            if unresolved_status:
                if unresolved_status == "RESOLVED":
                    details.append(self._schema_error(f"{path}.unresolvedStatus", "unresolvedStatus must not be RESOLVED.", actual=unresolved_status, expected="unresolved status"))
                elif edge_option is not None and unresolved_status not in set(edge_option.get("unresolvedStatuses") or []):
                    details.append(
                        self._schema_error(
                            f"{path}.unresolvedStatus",
                            "unresolvedStatus is not listed in edgeOptions for this edgeType.",
                            actual=unresolved_status,
                            expected=f"{edge_type} unresolved status",
                            allowed_values=list(edge_option.get("unresolvedStatuses") or []),
                            edgeType=edge_type,
                        )
                    )
                elif unresolved_status not in allowed_unresolved_statuses:
                    details.append(
                        self._schema_error(
                            f"{path}.unresolvedStatus",
                            "unresolvedStatus is not allowed by the effective analysis graph policy.",
                            actual=unresolved_status,
                            expected="allowed unresolved status",
                            allowed_values=allowed_unresolved_statuses,
                        )
                    )
                self._edge_unresolved_status(item, path, unresolved_status, ResolutionStatusContract.from_graph_contract(contract), details)
        self._evidence_list(item.get("evidence"), f"{path}.evidence", line_count, details)

    def _edge_target_option(
        self,
        edge_type: str,
        to_ref: str,
        ref_to_kind: Mapping[str, str],
        edge_option: Mapping[str, Any],
        path: str,
        details: list[dict[str, Any]],
    ) -> None:
        allowed_refs = self._edge_option_refs(edge_option)
        if to_ref not in allowed_refs:
            details.append(
                self._schema_error(
                    f"{path}.toRef",
                    "toRef is not listed in edgeOptions.toRefs for this edgeType.",
                    actual=to_ref,
                    expected=f"{edge_type} toRef from edgeOptions",
                    allowed_values=list(allowed_refs),
                    edgeType=edge_type,
                    actualToRef=to_ref,
                    actualToRefKind=ref_to_kind.get(to_ref),
                    allowedToKinds=list(edge_option.get("allowedToKinds") or []),
                    allowedToRefs=list(allowed_refs),
                )
            )

    def _edge_target_endpoint(
        self,
        edge_type: str,
        to_ref: str,
        ref_to_kind: Mapping[str, str],
        contract: AnalysisGraphContract,
        path: str,
        details: list[dict[str, Any]],
    ) -> None:
        to_kind = ref_to_kind.get(str(to_ref))
        allowed_to = list(contract.edge_to_kinds.get(edge_type, ()))
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

    def _edge_unresolved_status(
        self,
        item: Mapping[str, Any],
        path: str,
        status: str,
        rules: ResolutionStatusContract,
        details: list[dict[str, Any]],
    ) -> None:
        unresolved_target = item.get("unresolvedTarget")
        if rules.requires_to_ref(status):
            details.append(self._schema_error(f"{path}.unresolvedStatus", "unresolvedStatus requires toRef and cannot be used without toRef.", actual=status, expected="status without required toRef"))
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
            details.append(self._schema_error(f"{path}.unresolvedTarget", "edge resolution must not include unresolvedTarget.", actual=unresolved_target, expected="omit field"))
        if unresolved_target is not None:
            if not isinstance(unresolved_target, dict):
                details.append(self._schema_error(f"{path}.unresolvedTarget", "unresolvedTarget must be an object when present.", actual=unresolved_target, expected="object"))
            else:
                self._validate_unresolved_target(unresolved_target, f"{path}.unresolvedTarget", details)

    def _validate_unresolved_target(self, value: Mapping[str, Any], path: str, details: list[dict[str, Any]]) -> None:
        encoded = json.dumps(dict(value), ensure_ascii=False, sort_keys=True, default=str)
        if len(encoded) > self._MAX_UNRESOLVED_TARGET_JSON_CHARS:
            details.append(
                self._schema_error(
                    path,
                    "unresolvedTarget is too large.",
                    actual=len(encoded),
                    expected=f"{self._MAX_UNRESOLVED_TARGET_JSON_CHARS} chars or fewer",
                )
            )

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
        ref_to_stable_key: Mapping[str, str],
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
        edges: list[GraphEdge] = []
        for index, item in enumerate(parsed.get("semanticEdges") or [], start=1):
            to_ref = item.get("toRef")
            has_to_ref = isinstance(to_ref, str) and bool(to_ref.strip())
            edges.append(
                GraphEdge(
                    localId=f"llm-edge-{target_ref}-{index}",
                    fromNodeLocalId=target_stable_key,
                    toNodeLocalId=ref_to_stable_key[str(to_ref)] if has_to_ref else None,
                    edgeType=str(item["edgeType"]),
                    resolutionStatus="RESOLVED" if has_to_ref else str(item["unresolvedStatus"]),
                    confidence=self._DEFAULT_CONFIDENCE,
                    evidence=self._evidence_refs(item.get("evidence") or [], content_by_line),
                    unresolvedTarget=None if has_to_ref else item.get("unresolvedTarget"),
                    metadata={"factOrigin": "LLM"},
                )
            )
        return GraphAnalysisResult(nodes=[], edges=edges, claims=claims, diagnostics=[])

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

    def _known_refs(self, llm_input: Mapping[str, Any]) -> set[str]:
        registry = llm_input.get("anchorRegistry")
        if not isinstance(registry, list):
            return set()
        return {str(item.get("ref")) for item in registry if isinstance(item, Mapping) and item.get("ref")}

    def _edge_options(self, llm_input: Mapping[str, Any], contract: AnalysisGraphContract, target_kind: str) -> dict[str, dict[str, Any]]:
        raw_options = llm_input.get("edgeOptions")
        if not isinstance(raw_options, list):
            return {}
        target_scoped = [
            edge_type
            for edge_type in contract.allowed_edge_types
            if target_kind in set(contract.edge_from_kinds.get(edge_type, ()))
        ]
        target_scoped_set = set(target_scoped)
        rules = ResolutionStatusContract.from_graph_contract(contract)
        options: dict[str, dict[str, Any]] = {}
        for item in raw_options:
            if not isinstance(item, Mapping):
                continue
            edge_type = item.get("edgeType")
            if not isinstance(edge_type, str) or not edge_type.strip() or edge_type not in target_scoped_set:
                continue
            allowed_to_kinds = [str(kind) for kind in item.get("allowedToKinds") or [] if isinstance(kind, str) and kind.strip()]
            to_refs = []
            for ref_item in item.get("toRefs") or []:
                if not isinstance(ref_item, Mapping):
                    continue
                ref = ref_item.get("ref")
                kind = ref_item.get("kind")
                if not isinstance(ref, str) or not ref.strip() or not isinstance(kind, str) or not kind.strip():
                    continue
                if allowed_to_kinds and kind not in allowed_to_kinds:
                    continue
                to_refs.append({"ref": ref, "kind": kind, "name": str(ref_item.get("name") or "")})
            unresolved_statuses = [
                str(status)
                for status in item.get("unresolvedStatuses") or []
                if isinstance(status, str) and status.strip() and status != "RESOLVED" and not rules.requires_to_ref(str(status))
            ]
            options[edge_type] = {
                "edgeType": edge_type,
                "allowedToKinds": allowed_to_kinds,
                "toRefs": to_refs,
                "unresolvedStatuses": unresolved_statuses,
            }
        return options

    def _edge_option_refs(self, edge_option: Mapping[str, Any]) -> list[str]:
        refs: list[str] = []
        raw_refs = edge_option.get("toRefs")
        if not isinstance(raw_refs, list):
            return refs
        for item in raw_refs:
            if isinstance(item, Mapping) and isinstance(item.get("ref"), str) and item.get("ref").strip():
                refs.append(str(item.get("ref")))
        return refs

    def _allowed_unresolved_statuses(self, llm_input: Mapping[str, Any], contract: AnalysisGraphContract) -> list[str]:
        rules = ResolutionStatusContract.from_graph_contract(contract)
        input_values = self._allowed_values(llm_input, "unresolvedStatus")
        if input_values:
            return [
                status
                for status in input_values
                if status != "RESOLVED" and not rules.requires_to_ref(status)
            ]
        return [
            status
            for status in contract.allowed_resolution_statuses
            if status != "RESOLVED" and not rules.requires_to_ref(status)
        ]

    def _allowed_values(self, llm_input: Mapping[str, Any], key: str) -> list[str]:
        allowed_values = llm_input.get("allowedValues")
        raw_values = allowed_values.get(key) if isinstance(allowed_values, Mapping) else None
        if not isinstance(raw_values, list):
            return []
        return [str(item) for item in raw_values if isinstance(item, str) and item.strip()]

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
        allowed_edge_types: list[str],
        allowed_unresolved_statuses: list[str],
    ) -> list[dict[str, Any]]:
        decorated: list[dict[str, Any]] = []
        for detail in details:
            item = dict(detail)
            item.setdefault("targetRef", target_ref)
            item.setdefault("targetKind", target_kind)
            item.setdefault("targetAllowedEdgeTypes", list(allowed_edge_types))
            item.setdefault("allowedUnresolvedStatuses", list(allowed_unresolved_statuses))
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
