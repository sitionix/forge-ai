from __future__ import annotations

import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Sequence

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, GraphContractProvider
from knowledge_service.graph_schema import (
    GRAPH_SCHEMA_VERSION,
    GraphDiagnosticSeverity,
    GraphDiagnosticStage,
)


class GraphValidationErrorCode(str, Enum):
    EMPTY_RESPONSE = "EMPTY_RESPONSE"
    INVALID_JSON = "INVALID_JSON"
    RESPONSE_NOT_OBJECT = "RESPONSE_NOT_OBJECT"
    SCHEMA_INVALID = "SCHEMA_INVALID"
    MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD"
    INVALID_FIELD_TYPE = "INVALID_FIELD_TYPE"
    INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE"
    INVALID_CONFIDENCE = "INVALID_CONFIDENCE"
    CONFIDENCE_BELOW_THRESHOLD = "CONFIDENCE_BELOW_THRESHOLD"
    LINE_RANGE_INVALID = "LINE_RANGE_INVALID"
    LINE_RANGE_OUTSIDE_FILE = "LINE_RANGE_OUTSIDE_FILE"
    UNRESOLVED_LOCAL_REFERENCE = "UNRESOLVED_LOCAL_REFERENCE"
    MISSING_EVIDENCE = "MISSING_EVIDENCE"
    DUPLICATE_LOCAL_ID = "DUPLICATE_LOCAL_ID"
    FILE_IDENTITY_MISMATCH = "FILE_IDENTITY_MISMATCH"


@dataclass(frozen=True)
class GraphValidationError:
    code: GraphValidationErrorCode | str
    path: str
    message: str
    expected: Optional[str] = None
    actual: Any = None
    allowed_values: List[str] = field(default_factory=list)
    repair_hint: Optional[str] = None
    severity: GraphDiagnosticSeverity | str = GraphDiagnosticSeverity.ERROR
    candidate_id: Optional[str] = None
    line_start: Optional[int] = None
    line_end: Optional[int] = None
    stage: GraphDiagnosticStage | str = GraphDiagnosticStage.CANDIDATE_VALIDATE

    def to_dict(self) -> Dict[str, Any]:
        return {
            "code": self._value(self.code),
            "path": self.path,
            "message": self.message,
            "expected": self.expected,
            "actual": self._jsonable(self.actual),
            "allowedValues": list(self.allowed_values),
            "repairHint": self.repair_hint,
            "severity": self._value(self.severity),
            "candidateId": self.candidate_id,
            "lineStart": self.line_start,
            "lineEnd": self.line_end,
            "stage": self._value(self.stage),
        }

    @classmethod
    def from_dict(cls, value: Dict[str, Any]) -> "GraphValidationError":
        return cls(
            code=value.get("code") or GraphValidationErrorCode.SCHEMA_INVALID,
            path=value.get("path") or "$",
            message=value.get("message") or "Graph validation failed.",
            expected=value.get("expected"),
            actual=value.get("actual"),
            allowed_values=list(value.get("allowedValues") or value.get("allowed_values") or []),
            repair_hint=value.get("repairHint") or value.get("repair_hint"),
            severity=value.get("severity") or GraphDiagnosticSeverity.ERROR,
            candidate_id=value.get("candidateId") or value.get("candidate_id"),
            line_start=value.get("lineStart") if value.get("lineStart") is not None else value.get("line_start"),
            line_end=value.get("lineEnd") if value.get("lineEnd") is not None else value.get("line_end"),
            stage=value.get("stage") or GraphDiagnosticStage.CANDIDATE_VALIDATE,
        )

    def compact_dict(self) -> Dict[str, Any]:
        data = self.to_dict()
        return {
            key: data[key] for key in ("code", "path", "message", "expected", "actual", "allowedValues", "repairHint") if data.get(key) not in (None, [], "")
        }

    def _value(self, value: Any) -> Any:
        return value.value if isinstance(value, Enum) else value

    def _jsonable(self, value: Any) -> Any:
        try:
            json.dumps(value)
            return value
        except TypeError:
            return str(value)


class GraphRepairPromptBuilder:
    def __init__(self, contract_provider: GraphContractProvider | None = None):
        self.contract_provider = contract_provider or GraphContractProvider()

    def build(
        self,
        payload: Dict[str, Any],
        raw_response_preview: Optional[str],
        errors: Sequence[GraphValidationError],
        attempt: int,
        max_attempts: int,
        compact: bool = False,
        contract: AnalysisGraphContract | None = None,
    ) -> str:
        effective_contract = contract or self.contract_provider.resolve_payload(payload)
        error_payload = [error.compact_dict() if compact else error.to_dict() for error in errors[:25]]
        repair_context = {
            "schemaVersion": GRAPH_SCHEMA_VERSION,
            "file": {
                "sourceId": payload.get("sourceId"),
                "inventoryFileId": payload.get("inventoryFileId"),
                "relativePath": payload.get("relativePath"),
                "contentHash": payload.get("contentHash"),
                "lineCount": payload.get("lineCount"),
            },
            "allowedValues": {
                "nodeKind": list(effective_contract.allowed_node_kinds),
                "edgeType": list(effective_contract.allowed_edge_types),
                "claimKind": list(effective_contract.allowed_claim_kinds),
                "status": list(effective_contract.allowed_statuses),
                "factOrigin": list(effective_contract.allowed_origins),
                "evidenceKind": list(effective_contract.allowed_evidence_kinds),
                "resolutionStatus": list(effective_contract.allowed_resolution_statuses),
            },
            "validationErrors": error_payload,
            "attempt": attempt,
            "maxAttempts": max_attempts,
            "rawResponsePreview": raw_response_preview,
        }
        return "\n".join(
            [
                "Repair the previous graph analysis response.",
                "Return JSON only. Do not use markdown or prose outside JSON.",
                "Preserve valid candidates when possible and fix only the listed validation errors.",
                "Do not invent new facts. Remove candidates that cannot be fixed using evidence in the provided file.",
                "Use only the allowed graph contract values below.",
                "Every node, edge, and claim must have valid evidence line ranges inside the analyzed file when required.",
                "Every local reference must point to an existing localId in the repaired response.",
                "The repaired response must still match schemaVersion knowledge.graph.analysis.v1 and the exact file identity.",
                "Structured validation feedback JSON:",
                json.dumps(repair_context, ensure_ascii=False, default=str),
            ]
        )


def enum_validation_error(
    *,
    path: str,
    message: str,
    actual: Any,
    allowed_values: Sequence[str],
    candidate_id: Optional[str] = None,
    repair_hint: Optional[str] = None,
) -> GraphValidationError:
    return GraphValidationError(
        code=GraphValidationErrorCode.INVALID_ENUM_VALUE,
        path=path,
        message=message,
        expected="Allowed graph enum value.",
        actual=actual,
        allowed_values=list(allowed_values),
        repair_hint=repair_hint or "Replace the value with one of the allowed graph contract values, or remove the unsupported candidate.",
        candidate_id=candidate_id,
    )


def json_path(loc: Sequence[Any]) -> str:
    path = "$"
    for part in loc:
        if isinstance(part, int):
            path += f"[{part}]"
        else:
            path += f".{part}"
    return path
