from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Mapping, Optional, Protocol, Tuple

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, contract_payload
from knowledge_service.analysis_policy import (
    AnalysisPolicy,
    ExtractorDefinition,
    policy_allows_extractor_fallback,
    policy_requires_llm,
)
from knowledge_service.analysis_policy_resolver import AnalysisPolicyResolveRequest, AnalysisPolicyResolution, resolve_analysis_policy
from knowledge_service.anchor_enrichment import AnchorAwareGraphValidator
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_policy_validator import GraphPolicyValidator
from knowledge_service.graph_schema import GraphAnalysisResult, GraphNode
from knowledge_service.structural_analysis import StaticGraphMaterializer, StructuralAnalysisEngine
from knowledge_service.target_enrichment import FileEnrichmentMerger, LlmEnrichmentInputBuilder, LlmEnrichmentPlanner, TargetPromptRenderer


class AnalyzerProvider(Protocol):
    name: str
    version: str

    def analyze(
        self,
        payload: Dict[str, Any],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult | Awaitable[GraphAnalysisResult]: ...


class TargetProgressTracker(Protocol):
    def start_file(self, job_id: str, source_id: str, relative_path: str) -> None: ...

    def set_total_targets(self, job_id: str, source_id: str, relative_path: str, total_targets: int) -> None: ...

    def increment_completed(self, job_id: str, source_id: str, relative_path: str) -> None: ...


AnalyzeWithRetry = Callable[
    [AnalyzerProvider, Dict[str, Any], int],
    Awaitable[Tuple[GraphAnalysisResult, List[Dict[str, Any]], Dict[str, Any]]],
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
        graph.nodes.extend(self._structured_region_nodes(context, extractor))
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

    def _structured_region_nodes(self, context: AnalyzerExecutionContext, extractor: ExtractorDefinition) -> List[GraphNode]:
        node_kind = self._select_structured_region_node_kind(context, extractor)
        if node_kind is None:
            return []
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
                    nodeKind=node_kind,
                    name=label,
                    language=self._language(context),
                    qualifiedName=None,
                    displayName=label,
                    parentLocalId=self._file_stable_key(context),
                    lineStart=line_number,
                    lineEnd=line_number,
                    confidence=0.72,
                    metadata={
                        **self._file_metadata(context, extractor.id),
                        "sourceKind": "STRUCTURED_TEXT_REGION",
                        "stableKey": local_id,
                    },
                )
            )
            if len(nodes) >= 25:
                break
        return nodes

    def _select_structured_region_node_kind(self, context: AnalyzerExecutionContext, extractor: ExtractorDefinition) -> Optional[str]:
        produced = set(extractor.produces.nodes)
        allowed = set(context.graph_contract.allowed_node_kinds)
        if "CONFIG" in produced and "CONFIG" in allowed:
            return "CONFIG"
        return None

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


class AnalyzerRuntime:
    def __init__(
        self,
        policy: AnalysisPolicy,
        *,
        policy_resolver: Optional[AnalyzerPolicyRuntimeResolver] = None,
        extractor_registry: Optional[ExtractorRegistry] = None,
        anchor_validator: Optional[AnchorAwareGraphValidator] = None,
        graph_policy_validator: Optional[GraphPolicyValidator] = None,
        enrichment_planner: Optional[LlmEnrichmentPlanner] = None,
        target_input_builder: Optional[LlmEnrichmentInputBuilder] = None,
        file_enrichment_merger: Optional[FileEnrichmentMerger] = None,
        target_prompt_renderer: Optional[TargetPromptRenderer] = None,
        target_progress_tracker: Optional[TargetProgressTracker] = None,
    ) -> None:
        self.policy = policy
        self.policy_resolver = policy_resolver or AnalyzerPolicyRuntimeResolver(policy)
        self.extractor_registry = extractor_registry or ExtractorRegistry()
        self.anchor_validator = anchor_validator or AnchorAwareGraphValidator()
        self.graph_policy_validator = graph_policy_validator or GraphPolicyValidator(policy)
        self.enrichment_planner = enrichment_planner or LlmEnrichmentPlanner()
        self.target_prompt_renderer = target_prompt_renderer or TargetPromptRenderer(policy=policy)
        self.target_input_builder = target_input_builder or LlmEnrichmentInputBuilder(policy=policy)
        self.file_enrichment_merger = file_enrichment_merger or FileEnrichmentMerger()
        self.target_progress_tracker = target_progress_tracker

    async def execute(
        self,
        row: Mapping[str, Any],
        metadata: Dict[str, Any],
        content_lines: List[str],
        analyzer: AnalyzerProvider,
        analyze_with_retry: AnalyzeWithRetry,
        *,
        job_id: Optional[str] = None,
    ) -> AnalyzerRuntimeResult:
        context = self.policy_resolver.resolve(row, metadata, content_lines)
        extractor_result = self.extractor_registry.extract(self.policy, context)
        self._validate_extractor_output(extractor_result, context)
        payload = self._result_payload(context)
        attempt_state = self._empty_attempt_state()
        retry_diagnostics: List[Dict[str, Any]] = []
        enrichment_result: Optional[GraphAnalysisResult] = None
        llm_called = False
        if self._requires_llm(context):
            source_id = str(context.row.get("source_id") or "")
            relative_path = str(context.row.get("relative_path") or "")
            if job_id and self.target_progress_tracker is not None:
                self.target_progress_tracker.start_file(job_id, source_id, relative_path)
            self._enforce_llm_input_limits(context)
            budget_chars = int(self.policy.defaults.max_file_chars)
            plan = self.enrichment_planner.plan(
                extractor_result.graph_result,
                context.graph_contract,
                max_target_calls=int(self.policy.defaults.max_target_calls_per_file),
                source_id=context.row.get("source_id"),
                relative_path=context.row.get("relative_path"),
            )
            if job_id and self.target_progress_tracker is not None:
                self.target_progress_tracker.set_total_targets(job_id, source_id, relative_path, len(plan.targets))
            target_results: List[GraphAnalysisResult] = []
            target_attempt_states: List[Dict[str, Any]] = []
            for target in plan.targets:
                target_payload = self.target_input_builder.build(
                    context=context,
                    registry=plan.registry,
                    target=target,
                    budget_chars=budget_chars,
                )
                self.target_prompt_renderer.ensure_within_budget(target_payload, budget_chars, contract=context.graph_contract)
                result, target_retry_diagnostics, target_attempt_state = await analyze_with_retry(analyzer, target_payload, context.line_count)
                retry_diagnostics.extend(target_retry_diagnostics)
                target_attempt_states.append(target_attempt_state)
                target_result = self._graph_result(result)
                self.graph_policy_validator.validate_llm_enrichment(
                    target_result,
                    context.graph_contract,
                    context.line_count,
                    relative_path=str(context.row.get("relative_path") or ""),
                    static_graph=extractor_result.graph_result,
                )
                if job_id and self.target_progress_tracker is not None:
                    self.target_progress_tracker.increment_completed(job_id, source_id, relative_path)
                target_results.append(target_result)
            enrichment_result = self.file_enrichment_merger.merge(target_results)
            self.graph_policy_validator.validate_llm_enrichment(
                enrichment_result,
                context.graph_contract,
                context.line_count,
                relative_path=str(context.row.get("relative_path") or ""),
                static_graph=extractor_result.graph_result,
            )
            attempt_state = self._merge_attempt_states(target_attempt_states)
            llm_called = True
        final_result = self.anchor_validator.merge(extractor_result.graph_result, enrichment_result, context.line_count)
        final_result.diagnostics.extend(self._runtime_diagnostics(retry_diagnostics))
        self.graph_policy_validator.validate_final_graph(
            final_result,
            context.graph_contract,
            context.line_count,
            relative_path=str(context.row.get("relative_path") or ""),
        )
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

    def _graph_result(self, result: GraphAnalysisResult) -> GraphAnalysisResult:
        if isinstance(result, GraphAnalysisResult):
            return result
        raise KnowledgeError("ANALYSIS_AI_SCHEMA_INVALID", "AI analyzer returned an unsupported analysis result")

    def _runtime_diagnostics(self, diagnostics: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        result: List[Dict[str, Any]] = []
        for diagnostic in diagnostics:
            item = dict(diagnostic)
            item.setdefault("severity", "WARN" if diagnostic.get("code") != "ANALYSIS_FILE_FAILED" else "ERROR")
            item.setdefault("stage", "LLM_ENRICHMENT")
            result.append(item)
        return result

    def _empty_attempt_state(self) -> Dict[str, Any]:
        return {
            "attempt_count": 0,
            "last_attempt_at": None,
            "last_error_code": None,
            "last_error_message": None,
            "last_raw_response_preview": None,
        }

    def _merge_attempt_states(self, states: List[Dict[str, Any]]) -> Dict[str, Any]:
        if not states:
            return self._empty_attempt_state()
        return {
            "attempt_count": sum(int(state.get("attempt_count") or 0) for state in states),
            "last_attempt_at": next((state.get("last_attempt_at") for state in reversed(states) if state.get("last_attempt_at")), None),
            "last_error_code": next((state.get("last_error_code") for state in reversed(states) if state.get("last_error_code")), None),
            "last_error_message": next((state.get("last_error_message") for state in reversed(states) if state.get("last_error_message")), None),
            "last_raw_response_preview": next((state.get("last_raw_response_preview") for state in reversed(states) if state.get("last_raw_response_preview")), None),
        }

    def _result_payload(self, context: AnalyzerExecutionContext) -> Dict[str, Any]:
        return {
            "sourceId": context.row.get("source_id"),
            "relativePath": context.row.get("relative_path"),
            "lineCount": context.line_count,
            "language": self._language(context),
            "format": context.policy_resolution.format_id,
            "analysisPolicy": contract_payload(context.graph_contract),
        }

    def _language(self, context: AnalyzerExecutionContext) -> Optional[str]:
        language = str(context.row.get("language") or "").strip().lower()
        if language and language != "unknown":
            return language
        return context.policy_resolution.family or context.policy_resolution.format_id

    def _validate_extractor_output(self, extractor_result: ExtractorResult, context: AnalyzerExecutionContext) -> None:
        extractor = self._extractor_validation_contract(extractor_result)
        self.graph_policy_validator.validate_extractor_output(
            extractor_result.graph_result,
            context.graph_contract,
            extractor,
            context.line_count,
            relative_path=str(context.row.get("relative_path") or ""),
            extractor_id=extractor_result.extractor_id,
            implementation=extractor_result.implementation,
            used_fallback=extractor_result.used_fallback,
        )

    def _extractor_validation_contract(self, extractor_result: ExtractorResult) -> ExtractorDefinition:
        if extractor_result.used_fallback:
            fallback = self.policy.extractors.get("file_anchor")
            if fallback is not None:
                return fallback
        extractor = self.policy.extractors.get(extractor_result.extractor_id)
        if extractor is not None:
            return extractor
        raise KnowledgeError(
            "ANALYSIS_EXTRACTOR_OUTPUT_INVALID",
            "Extractor output cannot be validated because the extractor is not declared.",
            stage="STATIC_EXTRACTION",
            severity="ERROR",
            extractorId=extractor_result.extractor_id,
            implementation=extractor_result.implementation,
        )


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
