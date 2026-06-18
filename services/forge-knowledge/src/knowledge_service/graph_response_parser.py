from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

from pydantic import ValidationError

from knowledge_service.graph_schema import GraphAnalysisResult, GraphClaim, GraphEdge, GraphEvidenceRef


MAX_GRAPH_RAW_PREVIEW_CHARS = 4000


@dataclass(frozen=True)
class GraphAnalysisParseFailure:
    code: str
    message: str
    raw_preview: str


class GraphAnalysisResponseParser:
    def parse(self, raw: str, line_count: int) -> GraphAnalysisResult | GraphAnalysisParseFailure:
        if raw is None or not raw.strip():
            return GraphAnalysisParseFailure("ANALYSIS_AI_EMPTY_RESPONSE", "AI analyzer returned an empty response", "")
        parsed, loaded = self._load_json(raw)
        if not loaded:
            extracted = self._extract_first_json_object(raw)
            if extracted is None:
                return GraphAnalysisParseFailure("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", self._preview(raw))
            parsed, loaded = self._load_json(extracted)
            if not loaded:
                return GraphAnalysisParseFailure("ANALYSIS_AI_INVALID_JSON", "AI analyzer returned invalid JSON", self._preview(raw))
        if not isinstance(parsed, dict):
            return GraphAnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", "AI analyzer response must be one JSON object", self._preview(raw))
        try:
            if str(parsed.get("schemaVersion") or "").startswith("knowledge.graph.enrichment."):
                result = self._parse_enrichment(parsed)
                result.validate_lines(line_count)
                return result
            result = GraphAnalysisResult.parse_obj(parsed)
            result.validate_lines(line_count)
            result.validate_references()
            return result
        except (ValidationError, ValueError) as exc:
            return GraphAnalysisParseFailure("ANALYSIS_AI_SCHEMA_INVALID", self._schema_message(exc), self._preview(raw))

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
        return raw[:MAX_GRAPH_RAW_PREVIEW_CHARS]

    def _schema_message(self, exc: Exception) -> str:
        text = str(exc).replace("\n", " ")
        if len(text) > 480:
            text = text[:480].rstrip() + "..."
        return f"AI analyzer response does not match graph schema: {text}"

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
