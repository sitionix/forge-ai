from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Mapping, Optional, Protocol, Tuple, Union

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, contract_payload
from knowledge_service.analysis_policy import (
    AnalysisPolicy,
    ExtractorDefinition,
    policy_allows_extractor_fallback,
    policy_requires_llm,
)
from knowledge_service.analysis_policy_resolver import AnalysisPolicyResolveRequest, AnalysisPolicyResolution, resolve_analysis_policy
from knowledge_service.analysis_schema import AnalysisResult
from knowledge_service.anchor_enrichment import AnchorAwareGraphValidator
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_analysis import LegacyAnalysisProjectionAdapter
from knowledge_service.graph_schema import GraphAnalysisResult, GraphNode
from knowledge_service.structural_analysis import GRAPH_ENGINE_VERSION, StaticGraphMaterializer, StructuralAnalysisEngine


class AnalyzerProvider(Protocol):
    name: str
    version: str

    def analyze(
        self,
        payload: Dict[str, Any],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> Union[GraphAnalysisResult, AnalysisResult, Awaitable[Union[GraphAnalysisResult, AnalysisResult]]]: ...


AnalyzeWithRetry = Callable[
    [AnalyzerProvider, Dict[str, Any], int],
    Awaitable[Tuple[Union[GraphAnalysisResult, AnalysisResult], List[Dict[str, Any]], Dict[str, Any]]],
]


@dataclass(frozen=True)
class AnalyzerExecutionContext:
    row: Dict[str, Any]
    metadata: Dict[str, Any]
    content_lines: List[str]
    content: str
    policy_resolution: AnalysisPolicyResolution
    graph_contract: AnalysisGraphContract

    @property
    def line_count(self) -> int:
        return len(self.content_lines)


@dataclass(frozen=True)
class ExtractorResult:
    graph_result: GraphAnalysisResult
    extractor_id: str
    implementation: str
    used_fallback: bool = False
    diagnostics: List[Dict[str, Any]] = field(default_factory=list)


@dataclass(frozen=True)
class AnalyzerRuntimeResult:
    graph_result: GraphAnalysisResult
    payload: Dict[str, Any]
    diagnostics: List[Dict[str, Any]]
    attempt_state: Dict[str, Any]
    llm_called: bool


class AnalyzerPolicyRuntimeResolver:
    def __init__(self, policy: AnalysisPolicy):
        self.policy = policy

    def resolve(self, row: Mapping[str, Any], metadata: Dict[str, Any], content_lines: List[str]) -> AnalyzerExecutionContext:
        row_data = _row_dict(row)
        content = "\n".join(content_lines)
        relative_path = str(row_data.get("relative_path") or "")
        resolution = resolve_analysis_policy(
            self.policy,
            AnalysisPolicyResolveRequest(relative_path=relative_path, content=content, content_lines=content_lines),
        )
        if not resolution.supported:
            raise KnowledgeError(
                resolution.failure_code or "ANALYSIS_POLICY_RESOLUTION_FAILED",
                resolution.failure_message or "Analysis policy resolution failed.",
                stage="POLICY_RESOLUTION",
                severity="ERROR",
                relativePath=relative_path,
                extension=resolution.extension,
                formatId=resolution.format_id,
                unsupportedBehavior=dict(resolution.unsupported_behavior),
            )
        return AnalyzerExecutionContext(
            row=row_data,
            metadata=metadata,
            content_lines=content_lines,
            content=content,
            policy_resolution=resolution,
            graph_contract=AnalysisGraphContract.from_policy_resolution(self.policy, resolution),
        )


class ExtractorRegistry:
    def __init__(
        self,
        structural_engine: Optional[StructuralAnalysisEngine] = None,
        static_materializer: Optional[StaticGraphMaterializer] = None,
    ) -> None:
        self.structural_engine = structural_engine or StructuralAnalysisEngine()
        self.static_materializer = static_materializer or StaticGraphMaterializer()
        self._handlers = {
            "file_anchor": self._file_anchor,
            "java_static_parser": self._java_static_parser,
            "structured_text_parser": self._structured_text_light,
            "document_heading_parser": self._document_heading_light,
        }

    def extract(self, policy: AnalysisPolicy, context: AnalyzerExecutionContext) -> ExtractorResult:
        extractor_id = context.policy_resolution.extractor_id
        extractor = policy.extractors.get(str(extractor_id or ""))
        if extractor is None:
            return self._unsupported_extractor(context, extractor_id, None)
        handler = self._handlers.get(extractor.implementation)
        if handler is None:
            return self._unsupported_extractor(context, extractor.id, extractor)
        try:
            return handler(context, extractor)
        except Exception as exc:
            if self._allows_file_anchor_fallback(context):
                fallback = self._file_anchor(context, extractor, used_fallback=True)
                fallback.graph_result.diagnostics.append(
                    self._diagnostic(
                        context,
                        "ANALYSIS_EXTRACTOR_FALLBACK_USED",
                        f"Extractor {extractor.id} failed; file_anchor fallback was used.",
                        extractor_id=extractor.id,
                        implementation=extractor.implementation,
                        severity="WARN",
                        metadata={"exceptionType": type(exc).__name__, "message": str(exc)},
                    )
                )
                return fallback
            raise KnowledgeError(
                "ANALYSIS_EXTRACTOR_FAILED",
                f"Extractor {extractor.id} failed: {type(exc).__name__}: {exc}",
                stage="STATIC_EXTRACTION",
                severity="ERROR",
                extractorId=extractor.id,
                implementation=extractor.implementation,
            ) from exc

    def _unsupported_extractor(
        self,
        context: AnalyzerExecutionContext,
        extractor_id: Optional[str],
        extractor: Optional[ExtractorDefinition],
    ) -> ExtractorResult:
        if self._allows_file_anchor_fallback(context):
            fallback = self._file_anchor(context, extractor, used_fallback=True)
            fallback.graph_result.diagnostics.append(
                self._diagnostic(
                    context,
                    "ANALYSIS_UNSUPPORTED_EXTRACTOR_FALLBACK_USED",
                    f"Extractor {extractor_id or '<missing>'} is unavailable; file_anchor fallback was used.",
                    extractor_id=extractor_id,
                    implementation=extractor.implementation if extractor is not None else None,
                    severity="WARN",
                )
            )
            return fallback
        raise KnowledgeError(
            "UNSUPPORTED_EXTRACTOR",
            f"Analysis extractor is not implemented: {extractor_id or '<missing>'}",
            stage="STATIC_EXTRACTION",
            severity="ERROR",
            extractorId=extractor_id,
            unsupportedBehavior=dict(context.policy_resolution.unsupported_behavior),
        )

    def _java_static_parser(self, context: AnalyzerExecutionContext, extractor: ExtractorDefinition) -> ExtractorResult:
        structural_result = self.structural_engine.parse(context.row, context.content_lines)
        graph = self.static_materializer.to_graph(structural_result)
        if self._has_parser_failure(graph) and self._allows_file_anchor_fallback(context):
            fallback = self._file_anchor(context, extractor, used_fallback=True)
            fallback.graph_result.diagnostics.extend(graph.diagnostics)
            fallback.graph_result.diagnostics.append(
                self._diagnostic(
                    context,
                    "ANALYSIS_EXTRACTOR_FALLBACK_USED",
                    f"Extractor {extractor.id} did not produce trusted structure; file_anchor fallback was used.",
                    extractor_id=extractor.id,
                    implementation=extractor.implementation,
                    severity="WARN",
                )
            )
            return fallback
        return ExtractorResult(graph_result=graph, extractor_id=extractor.id, implementation=extractor.implementation)

    def _file_anchor(
        self,
        context: AnalyzerExecutionContext,
        extractor: Optional[ExtractorDefinition],
        *,
        used_fallback: bool = False,
    ) -> ExtractorResult:
        graph = self._file_anchor_graph(context, extractor.id if extractor is not None else "file_anchor")
        return ExtractorResult(
            graph_result=graph,
            extractor_id=extractor.id if extractor is not None else "file_anchor",
            implementation=extractor.implementation if extractor is not None else "file_anchor",
            used_fallback=used_fallback,
        )

    def _structured_text_light(self, context: AnalyzerExecutionContext, extractor: ExtractorDefinition) -> ExtractorResult:
        graph = self._file_anchor_graph(context, extractor.id)
        graph.nodes.extend(self._structured_region_nodes(context, extractor.id))
        return ExtractorResult(graph_result=graph, extractor_id=extractor.id, implementation=extractor.implementation)

    def _document_heading_light(self, context: AnalyzerExecutionContext, extractor: ExtractorDefinition) -> ExtractorResult:
        return self._file_anchor(context, extractor)

    def _file_anchor_graph(self, context: AnalyzerExecutionContext, extractor_id: str) -> GraphAnalysisResult:
        metadata = self._file_metadata(context, extractor_id)
        return GraphAnalysisResult(
            nodes=[
                GraphNode(
                    localId=self._file_stable_key(context),
                    nodeKind="FILE",
                    name=str(context.row.get("relative_path") or "").rsplit("/", 1)[-1],
                    language=self._language(context),
                    qualifiedName=None,
                    displayName=str(context.row.get("relative_path") or "").rsplit("/", 1)[-1],
                    parentLocalId=None,
                    lineStart=1,
                    lineEnd=max(context.line_count, 1),
                    confidence=1.0,
                    metadata=metadata,
                )
            ],
            edges=[],
            claims=[],
            diagnostics=[],
        )

    def _structured_region_nodes(self, context: AnalyzerExecutionContext, extractor_id: str) -> List[GraphNode]:
        nodes: List[GraphNode] = []
        seen: set[str] = set()
        for line_number, text in enumerate(context.content_lines, start=1):
            label = self._structured_label(text)
            if label is None or label in seen:
                continue
            seen.add(label)
            local_id = "|".join([self._file_stable_key(context), "STRUCTURE", label])
            nodes.append(
                GraphNode(
                    localId=local_id,
                    nodeKind="CONFIG",
                    name=label,
                    language=self._language(context),
                    qualifiedName=None,
                    displayName=label,
                    parentLocalId=self._file_stable_key(context),
                    lineStart=line_number,
                    lineEnd=line_number,
                    confidence=0.72,
                    metadata={
                        **self._file_metadata(context, extractor_id),
                        "sourceKind": "STRUCTURED_TEXT_REGION",
                        "stableKey": local_id,
                        "structuralRangeSource": "LIGHT_EXTRACTOR",
                    },
                )
            )
            if len(nodes) >= 25:
                break
        return nodes

    def _structured_label(self, text: str) -> Optional[str]:
        stripped = text.strip()
        if not stripped or stripped.startswith(("#", "//")):
            return None
        mapping_match = re.match(r"^([A-Za-z0-9_.-]{1,80})\s*[:=]", stripped)
        if mapping_match:
            return mapping_match.group(1)
        tag_match = re.match(r"^<([A-Za-z_][A-Za-z0-9_.:-]{0,79})(?:\s|>|/>)", stripped)
        if tag_match and not stripped.startswith("</"):
            return tag_match.group(1).split(":")[-1]
        return None

    def _file_metadata(self, context: AnalyzerExecutionContext, extractor_id: str) -> Dict[str, Any]:
        metadata = {
            "sourceKind": "FILE",
            "stableKey": self._file_stable_key(context),
            "factOrigin": "STATIC",
            "parser": extractor_id,
            "extractorId": extractor_id,
            "engineVersion": GRAPH_ENGINE_VERSION,
            "structuralRangeSource": "EXTRACTOR",
        }
        flow_domain = _row_value(context.row, "flow_domain")
        if flow_domain:
            metadata["flowDomain"] = str(flow_domain).upper()
        return metadata

    def _diagnostic(
        self,
        context: AnalyzerExecutionContext,
        code: str,
        message: str,
        *,
        extractor_id: Optional[str],
        implementation: Optional[str],
        severity: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        return {
            "code": code,
            "message": message,
            "severity": severity,
            "stage": "STATIC_EXTRACTION",
            "sourceId": context.row.get("source_id"),
            "relativePath": context.row.get("relative_path"),
            "metadata": {
                key: value
                for key, value in {
                    "extractorId": extractor_id,
                    "implementation": implementation,
                    **(metadata or {}),
                }.items()
                if value is not None
            },
        }

    def _has_parser_failure(self, graph: GraphAnalysisResult) -> bool:
        return any(str(item.get("code") or "").startswith("STRUCTURAL_PARSER_") and item.get("severity") == "ERROR" for item in graph.diagnostics or [])

    def _allows_file_anchor_fallback(self, context: AnalyzerExecutionContext) -> bool:
        try:
            return policy_allows_extractor_fallback(context.policy_resolution.extractor_mode)
        except ValueError as exc:
            raise KnowledgeError(
                "ANALYSIS_POLICY_UNSUPPORTED_EXTRACTOR_MODE",
                str(exc),
                stage="POLICY_RESOLUTION",
                severity="ERROR",
                extractorMode=context.policy_resolution.extractor_mode,
                unsupportedBehavior=dict(context.policy_resolution.unsupported_behavior),
            ) from exc

    def _file_stable_key(self, context: AnalyzerExecutionContext) -> str:
        return "|".join([str(context.row.get("source_id") or ""), str(context.row.get("relative_path") or ""), "FILE"])

    def _language(self, context: AnalyzerExecutionContext) -> Optional[str]:
        language = str(context.row.get("language") or "").strip().lower()
        if language and language != "unknown":
            return language
        return context.policy_resolution.family or context.policy_resolution.format_id


class AnalyzerPayloadBuilder:
    def build(self, context: AnalyzerExecutionContext, extractor_result: ExtractorResult) -> Dict[str, Any]:
        row = context.row
        policy_payload = contract_payload(context.graph_contract)
        metadata = self._metadata(context, extractor_result)
        return {
            "sourceId": row.get("source_id"),
            "serviceLabel": row.get("display_name"),
            "group": row.get("group_name"),
            "tags": _json_list(row.get("tags_json")),
            "relativePath": row.get("relative_path"),
            "extension": context.policy_resolution.extension or row.get("extension"),
            "sizeBytes": row.get("size_bytes"),
            "contentHash": row.get("content_hash"),
            "lineCount": context.line_count,
            "language": self._language(context),
            "format": context.policy_resolution.format_id,
            "metadata": metadata,
            "contentLines": [{"line": index, "text": line} for index, line in enumerate(context.content_lines, start=1)],
            "staticAnchors": self._static_anchor_payload(extractor_result.graph_result),
            "analysisPolicy": policy_payload,
        }

    def _metadata(self, context: AnalyzerExecutionContext, extractor_result: ExtractorResult) -> Dict[str, Any]:
        metadata = {key: value for key, value in context.metadata.items() if key != "absoluteRoot"}
        flow_domain = _row_value(context.row, "flow_domain")
        if flow_domain:
            metadata["flowDomain"] = str(flow_domain).upper()
        metadata["extractorId"] = extractor_result.extractor_id
        metadata["extractorImplementation"] = extractor_result.implementation
        metadata["extractorFallbackUsed"] = extractor_result.used_fallback
        if context.policy_resolution.artifact_labels:
            metadata["artifactLabels"] = list(context.policy_resolution.artifact_labels)
        return metadata

    def _language(self, context: AnalyzerExecutionContext) -> Optional[str]:
        language = str(context.row.get("language") or "").strip().lower()
        if language and language != "unknown":
            return language
        return context.policy_resolution.family or context.policy_resolution.format_id

    def _static_anchor_payload(self, static_graph: GraphAnalysisResult) -> Dict[str, Any]:
        return {
            "nodes": [
                {
                    "targetStableKey": node.localId,
                    "nodeKind": node.nodeKind,
                    "name": node.name,
                    "qualifiedName": node.qualifiedName,
                    "lineStart": node.lineStart,
                    "lineEnd": node.lineEnd,
                    "parentStableKey": node.parentLocalId,
                    "metadata": node.metadata,
                }
                for node in static_graph.nodes
            ],
            "callsites": [
                {
                    "callsiteStableKey": edge.localId,
                    "fromStableKey": edge.fromNodeLocalId,
                    "toStableKey": edge.toNodeLocalId,
                    "edgeType": edge.edgeType,
                    "resolutionStatus": edge.metadata.get("resolutionStatus"),
                    "lineStart": edge.evidence[0].lineStart if edge.evidence else None,
                    "lineEnd": edge.evidence[0].lineEnd if edge.evidence else None,
                    "unresolvedTarget": edge.unresolvedTarget,
                    "metadata": edge.metadata,
                }
                for edge in static_graph.edges
                if edge.edgeType == "CALLS"
            ],
            "diagnostics": static_graph.diagnostics,
        }


class AnalyzerRuntime:
    def __init__(
        self,
        policy: AnalysisPolicy,
        *,
        policy_resolver: Optional[AnalyzerPolicyRuntimeResolver] = None,
        extractor_registry: Optional[ExtractorRegistry] = None,
        payload_builder: Optional[AnalyzerPayloadBuilder] = None,
        anchor_validator: Optional[AnchorAwareGraphValidator] = None,
        legacy_adapter: Optional[LegacyAnalysisProjectionAdapter] = None,
    ) -> None:
        self.policy = policy
        self.policy_resolver = policy_resolver or AnalyzerPolicyRuntimeResolver(policy)
        self.extractor_registry = extractor_registry or ExtractorRegistry()
        self.payload_builder = payload_builder or AnalyzerPayloadBuilder()
        self.anchor_validator = anchor_validator or AnchorAwareGraphValidator()
        self.legacy_adapter = legacy_adapter or LegacyAnalysisProjectionAdapter()

    async def execute(
        self,
        row: Mapping[str, Any],
        metadata: Dict[str, Any],
        content_lines: List[str],
        analyzer: AnalyzerProvider,
        analyze_with_retry: AnalyzeWithRetry,
    ) -> AnalyzerRuntimeResult:
        context = self.policy_resolver.resolve(row, metadata, content_lines)
        extractor_result = self.extractor_registry.extract(self.policy, context)
        payload = self.payload_builder.build(context, extractor_result)
        attempt_state = self._empty_attempt_state()
        retry_diagnostics: List[Dict[str, Any]] = []
        enrichment_result: Optional[GraphAnalysisResult] = None
        llm_called = False
        if self._requires_llm(context):
            self._enforce_llm_input_limits(context)
            result, retry_diagnostics, attempt_state = await analyze_with_retry(analyzer, payload, context.line_count)
            enrichment_result = self._graph_result(result)
            llm_called = True
        final_result = self.anchor_validator.merge(extractor_result.graph_result, enrichment_result, context.line_count)
        final_result.diagnostics.extend(self._runtime_diagnostics(retry_diagnostics))
        self._validate_final_result(final_result, context.line_count)
        return AnalyzerRuntimeResult(
            graph_result=final_result,
            payload=payload,
            diagnostics=retry_diagnostics,
            attempt_state=attempt_state,
            llm_called=llm_called,
        )

    def _requires_llm(self, context: AnalyzerExecutionContext) -> bool:
        try:
            return policy_requires_llm(context.policy_resolution.llm_mode)
        except ValueError as exc:
            raise KnowledgeError(
                "ANALYSIS_POLICY_UNSUPPORTED_LLM_MODE",
                str(exc),
                stage="POLICY_RESOLUTION",
                severity="ERROR",
                llmMode=context.policy_resolution.llm_mode,
                unsupportedBehavior=dict(context.policy_resolution.unsupported_behavior),
            ) from exc

    def _enforce_llm_input_limits(self, context: AnalyzerExecutionContext) -> None:
        max_file_chars = int(self.policy.defaults.max_file_chars)
        if len(context.content) <= max_file_chars:
            return
        raise KnowledgeError(
            "ANALYSIS_FILE_TOO_LARGE",
            "File exceeds analysis policy maxFileChars; no partial extractor facts were persisted.",
            stage="FILE_ANALYSIS",
            severity="ERROR",
            sourceId=context.row.get("source_id"),
            relativePath=context.row.get("relative_path"),
            maxFileChars=max_file_chars,
            contentCharCount=len(context.content),
        )

    def _graph_result(self, result: Union[GraphAnalysisResult, AnalysisResult]) -> GraphAnalysisResult:
        if isinstance(result, GraphAnalysisResult):
            return result
        if isinstance(result, AnalysisResult):
            return self.legacy_adapter.convert(result)
        raise KnowledgeError("ANALYSIS_AI_SCHEMA_INVALID", "AI analyzer returned an unsupported analysis result")

    def _runtime_diagnostics(self, diagnostics: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        for diagnostic in diagnostics:
            item = dict(diagnostic)
            item.setdefault("severity", "WARN" if diagnostic.get("code") != "ANALYSIS_FILE_FAILED" else "ERROR")
            item.setdefault("stage", "LLM_ENRICHMENT")
            result.append(item)
        return result

    def _validate_final_result(self, graph_result: GraphAnalysisResult, line_count: int) -> None:
        try:
            graph_result.validate_lines(line_count)
            graph_result.validate_references()
        except (TypeError, ValueError) as exc:
            raise KnowledgeError(
                "ANALYSIS_GRAPH_VALIDATION_FAILED",
                f"Final graph analysis result is invalid: {exc}",
                stage="GRAPH_VALIDATION",
                severity="ERROR",
            ) from exc

    def _empty_attempt_state(self) -> Dict[str, Any]:
        return {
            "attempt_count": 0,
            "last_attempt_at": None,
            "last_error_code": None,
            "last_error_message": None,
            "last_raw_response_preview": None,
        }


def _row_dict(row: Mapping[str, Any]) -> Dict[str, Any]:
    if isinstance(row, dict):
        return dict(row)
    try:
        return {key: row[key] for key in row.keys()}
    except AttributeError:
        return dict(row)


def _row_value(row: Mapping[str, Any], key: str) -> Any:
    if isinstance(row, dict):
        return row.get(key)
    try:
        if key in row.keys():
            return row[key]
    except AttributeError:
        return None
    return None


def _json_list(value: Any) -> List[Any]:
    if isinstance(value, list):
        return value
    if not value:
        return []
    try:
        parsed = json.loads(str(value))
    except (TypeError, json.JSONDecodeError):
        return []
    return parsed if isinstance(parsed, list) else []
