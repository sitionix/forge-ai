from __future__ import annotations

import asyncio
import functools
import hashlib
import json
import logging
import os
import sqlite3
import threading
import time
import uuid
from collections import deque
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional, Sequence

import anyio
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, Response

from knowledge_service.analysis_schema import AnalysisBuildRequest, RetryFailedAnalysisRequest
from knowledge_service.analysis_service import AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.bootstrap import KnowledgeDependencies, build_dependencies, configure_logging
from knowledge_service.config import (
    AppConfig,
    ForgeSettings,
    load_forge_settings,
)
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.errors import KnowledgeError
from knowledge_service.flow_formatter import (
    FlowFormatterAllPlansFailed,
    FlowFormatterAnswerService,
    FlowFormatterDeadlineExceeded,
    FlowFormatterPromptRenderer,
    FlowFormatterSegmentPlanner,
    LocalOllamaFlowFormatterClient,
)
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.flow_explanations import FlowProjectionBuilder
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_schema import InventoryBuildRequest
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.knowledge_query_schema import (
    KnowledgeHumanQueryResponse,
    KnowledgeQueryDiagnostic,
    KnowledgeQueryRequest,
    KnowledgeQueryToolContextResponse,
)
from knowledge_service.knowledge_query_service import build_knowledge_query_service
from knowledge_service.language_policy import is_forbidden_response_language
from knowledge_service.observability import (
    CORRELATION_HEADER,
    ObservabilityMiddleware,
    current_route_metrics,
    sanitize_correlation_id,
)
from knowledge_service.overview_projection import read_overview
from knowledge_service.query_interpretation import (
    QUERY_INTERPRETATION_FAILED,
    LocalOllamaQueryInterpretationClient,
    QueryInterpretationFailed,
    QueryInterpretationPromptRenderer,
    QueryInterpretationService,
    QueryPlanningDeadlineExceeded,
    QueryPlanningMalformedResponse,
    QueryPlanningProviderUnavailable,
    QueryPlanningRepairExhausted,
)
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_schema import SemanticIndexBuildRequest, SemanticIndexBuildResponse
from knowledge_service.semantic_worker import SemanticBuildCoordinator, SemanticIndexBackgroundWorker
from knowledge_service.embedding_provider import OllamaEmbeddingProvider
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import load_source_config
from knowledge_service.storage_operations import StorageOperations

app_config: Optional[AppConfig] = None
store: Optional[InventoryStore] = None
analysis_supervisor: Optional[AnalysisSupervisor] = None
LOGGER = logging.getLogger(__name__)
HUMAN_QUERY_TERMINAL_AUDIT_PREFIX = "human-query-terminal-"


def create_app(
    settings: Optional[ForgeSettings] = None,
    dependencies: Optional[KnowledgeDependencies] = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        forge_settings = settings or load_forge_settings()
        app_config = AppConfig.from_forge_settings(forge_settings)
        configure_logging(forge_settings)
        deps = dependencies or build_dependencies(app_config)
        app.state.forge_settings = forge_settings
        app.state.app_config = app_config
        app.state.knowledge_dependencies = deps
        app.state.semantic_build_coordinator = _semantic_build_coordinator(app, app_config, deps.inventory_store.db_path)
        app.state.semantic_worker = _semantic_background_worker(app, app_config, deps.inventory_store.db_path)
        if app_config.analysis_enabled:
            await deps.analysis_supervisor.start_lifespan()
        await deps.inventory_scheduler.start()
        app.state.semantic_worker.start()
        try:
            yield
        finally:
            app.state.semantic_worker.stop(app_config.analysis_shutdown_grace_seconds)
            await deps.inventory_scheduler.stop()
            if app_config.analysis_enabled:
                await deps.analysis_supervisor.shutdown()

    app = FastAPI(title="Knowledge Service", version="0.1.0", lifespan=lifespan)
    app.state.semantic_build_jobs = {}
    app.state.semantic_build_lock = threading.Lock()
    if settings is not None and dependencies is not None:
        app.state.forge_settings = settings
        app.state.app_config = AppConfig.from_forge_settings(settings)
        app.state.knowledge_dependencies = dependencies

    app.add_middleware(ObservabilityMiddleware)

    @app.exception_handler(KnowledgeError)
    async def knowledge_error_handler(request: Request, exc: KnowledgeError) -> JSONResponse:
        status = 400
        if exc.code == "KNOWLEDGE_CONFIG_MISSING":
            status = 200
        elif exc.code.endswith("_NOT_FOUND") or exc.code == "SERVICE_CATALOG_NOT_FOUND":
            status = 404
        elif exc.code in {
            "GRAPH_CURSOR_SOURCE_MISMATCH",
            "GRAPH_CURSOR_RESOURCE_MISMATCH",
            "GRAPH_CURSOR_QUERY_MISMATCH",
            "GRAPH_CURSOR_INVALID",
            "GRAPH_FILTER_INVALID",
        }:
            status = 400
        elif exc.code in {
            "ANALYSIS_JOB_ALREADY_RUNNING",
            "INVENTORY_BUILD_ALREADY_RUNNING",
            "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS",
            "GRAPH_REVISION_STALE",
            "GRAPH_ITEM_SCOPE_MISMATCH",
        }:
            status = 409
        return JSONResponse(status_code=status, content=_safe_error(request, exc.code, exc.message))

    @app.exception_handler(sqlite3.OperationalError)
    async def sqlite_operational_error_handler(request: Request, exc: sqlite3.OperationalError) -> JSONResponse:
        message = str(exc)
        lower_message = message.lower()
        if "database is locked" in lower_message or "database is busy" in lower_message or "locked" in lower_message:
            return JSONResponse(
                status_code=503,
                content=_safe_error(request, "KNOWLEDGE_DB_BUSY", "Knowledge database is busy; retry the request."),
            )
        return JSONResponse(
            status_code=500,
            content=_safe_error(request, "KNOWLEDGE_DB_ERROR", "Knowledge database query failed."),
        )

    @app.get("/health")
    async def health() -> Dict[str, str]:
        return {"status": "UP"}

    @app.get("/api/v1/knowledge/status")
    async def status(request: Request, includeFreshness: bool = False) -> Dict[str, Any]:
        config, deps = _state(request)
        source_config = load_source_config(config.local_config_path)
        inventory = deps.inventory_store.status()
        analysis = deps.analysis_store.status()
        freshness = _unknown_freshness()
        if includeFreshness and source_config is not None and analysis.get("lastCompletedAt"):
            freshness = KnowledgeFreshnessService(source_config, deps.inventory_store).check()
        base: Dict[str, Any] = {
            "status": "UP",
            "module": "knowledge",
            "catalog": {"configured": source_config is not None, "type": source_config.catalog.type if source_config else None},
            "inventory": {
                "implemented": True,
                "status": inventory.get("status"),
                "lastBuildAt": inventory.get("lastBuildAt"),
                "sourceCount": inventory.get("sourceCount", 0),
                "fileCount": inventory.get("fileCount", 0),
                "skippedCount": inventory.get("skippedCount", 0),
                "skippedBreakdown": inventory.get("skippedBreakdown", {"total": 0, "byReason": {}}),
            },
            "inventoryRefresh": await deps.inventory_scheduler.status(),
            "coverage": {
                "scannedFiles": analysis.get("scannedFileCount", 0),
                "eligibleFiles": analysis.get("fileCount", 0),
                "completedAt": analysis.get("lastCompletedAt"),
            },
            "freshness": freshness,
            "generative": {
                "provider": config.analysis_provider,
                "model": config.analysis_model,
                "contextTokens": config.analysis_context_tokens,
                "humanQueryRequestTimeoutSeconds": config.human_query_request_timeout_seconds,
            },
            "semantic": _semantic_status(request.app, config),
        }
        if source_config is None:
            base["catalog"] = {"configured": False}
            base["message"] = "No local knowledge-sources.yaml configured"
        return base

    @app.get("/api/v1/knowledge/sources")
    async def sources(request: Request) -> Dict[str, Any]:
        config, _ = _state(request)
        source_config = load_source_config(config.local_config_path)
        if source_config is None:
            return {"sources": [], "message": "No local knowledge-sources.yaml configured"}
        result = ServiceYamlCatalogProvider(source_config).load()
        return {
            "catalog": {"type": source_config.catalog.type, "configured": True},
            "sources": [source.public_dict() for source in result.sources],
            "diagnostics": [diag.__dict__ for diag in result.diagnostics],
        }

    @app.post("/api/v1/knowledge/inventory/build")
    async def inventory_build(request: Request, body: InventoryBuildRequest) -> Dict[str, Any]:
        _, deps = _state(request)
        try:
            return await deps.inventory_refresh.build_async(body.sourceIds, body.groups)
        except Exception as exc:
            if isinstance(exc, KnowledgeError):
                raise
            raise KnowledgeError("INVENTORY_BUILD_FAILED", "Inventory build failed") from exc

    @app.get("/api/v1/knowledge/inventory/status")
    async def inventory_status(request: Request) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.inventory_store.status()

    @app.get("/api/v1/knowledge/inventory/files")
    async def inventory_files(
        request: Request,
        sourceId: Optional[str] = None,
        pathContains: Optional[str] = None,
        extension: Optional[str] = None,
        limit: int = Query(100, ge=1, le=1000),
        offset: int = Query(0, ge=0),
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.inventory_store.files(sourceId, pathContains, extension, limit, offset)

    @app.post("/api/v1/knowledge/context")
    async def context(request: Request, body: ContextRequest) -> Dict[str, Any]:
        _, deps = _state(request)
        if not body.query or not body.query.strip():
            raise KnowledgeError("CONTEXT_QUERY_INVALID", "Context query must not be empty")
        try:
            return ContextService(deps.inventory_store).context(body)
        except Exception as exc:
            if isinstance(exc, KnowledgeError):
                raise
            raise KnowledgeError("CONTEXT_BUILD_FAILED", "Context build failed") from exc

    @app.post(
        "/api/v1/knowledge/query",
        response_model=KnowledgeHumanQueryResponse,
        response_model_exclude_none=True,
    )
    async def knowledge_query(request: Request, body: KnowledgeQueryRequest):
        if is_forbidden_response_language(body.answerLanguage):
            correlation_id = _request_correlation_id(request)
            _record_human_query_terminal_audit(
                request,
                body,
                correlation_id=correlation_id,
                retrieval_plan=None,
                query_result=None,
                selected_flows=(),
                interpretation_records=[],
                answer_records=[],
                pipeline_records=[],
                terminal_status=422,
                terminal_error_code="RESPONSE_LANGUAGE_NOT_ALLOWED",
                terminal_error_message="The requested response language is not allowed.",
                terminal_stage="QUERY_INTERPRETATION",
                unexpected_exception_class=None,
                unexpected_exception_stage=None,
            )
            return _public_error_response(
                422,
                "RESPONSE_LANGUAGE_NOT_ALLOWED",
                "The requested response language is not allowed.",
                correlation_id=correlation_id,
            )
        config, _ = _state(request)
        deadline_at = time.monotonic() + _human_query_request_deadline_seconds(config)
        cancel_event = threading.Event()
        return await _run_in_thread(
            _knowledge_human_query_response,
            request,
            body,
            cancel_event,
            deadline_at,
            request_cancel_event=cancel_event,
        )

    @app.post(
        "/api/v1/knowledge/query/tool-context",
        response_model=KnowledgeQueryToolContextResponse,
        response_model_exclude_none=True,
    )
    async def knowledge_query_tool_context(request: Request, body: KnowledgeQueryRequest):
        if is_forbidden_response_language(body.answerLanguage):
            return _forbidden_response_language_response()
        return await _run_in_thread(
            _knowledge_query_tool_context_response,
            request,
            body,
        )

    @app.post("/api/v1/knowledge/semantic/index/build", response_model=SemanticIndexBuildResponse)
    async def semantic_index_build(request: Request, body: SemanticIndexBuildRequest) -> SemanticIndexBuildResponse:
        config, deps = _state(request)
        jobs, _ = _semantic_job_state(request.app)
        coordinator = _semantic_build_coordinator(request.app, config, deps.inventory_store.db_path)
        job_id = f"semantic-build-{uuid.uuid4()}"
        if not config.semantic_enabled:
            response = {
                "jobId": job_id,
                "status": "COMPLETED",
                "sourceIds": [],
                "diagnostics": [{"code": "SEMANTIC_DISABLED", "message": "Semantic indexing is disabled.", "severity": "INFO"}],
                "results": [],
            }
            jobs[job_id] = response
            return SemanticIndexBuildResponse(**response)
        if not coordinator.acquire(blocking=False):
            response = {
                "jobId": job_id,
                "status": "RUNNING",
                "sourceIds": body.sourceIds,
                "diagnostics": [
                    {"code": "SEMANTIC_BUILD_ALREADY_RUNNING", "message": "A semantic index build is already running.", "severity": "INFO"}
                ],
                "results": [],
            }
            jobs[job_id] = response
            return SemanticIndexBuildResponse(**response)
        jobs[job_id] = {"jobId": job_id, "status": "QUEUED", "sourceIds": body.sourceIds, "diagnostics": [], "results": []}
        if body.async_:
            thread = threading.Thread(
                target=_run_semantic_build_job,
                args=(jobs, coordinator, job_id, body.sourceIds, body.force),
                name="knowledge-semantic-index-build",
                daemon=True,
            )
            thread.start()
            return SemanticIndexBuildResponse(**jobs[job_id])
        try:
            result = coordinator.build_locked(body.sourceIds, force=body.force, build_id=job_id).to_dict()
            jobs[job_id] = result
            return SemanticIndexBuildResponse(**result)
        finally:
            coordinator.release()

    @app.get("/api/v1/knowledge/semantic/index/jobs/{job_id}", response_model=SemanticIndexBuildResponse)
    async def semantic_index_job(request: Request, job_id: str) -> SemanticIndexBuildResponse:
        jobs, _ = _semantic_job_state(request.app)
        job = jobs.get(job_id)
        if job is None:
            raise KnowledgeError("SEMANTIC_BUILD_JOB_NOT_FOUND", "Semantic build job not found")
        return SemanticIndexBuildResponse(**job)

    @app.post("/api/v1/knowledge/analysis/build")
    async def analysis_build(request: Request, body: AnalysisBuildRequest) -> Dict[str, Any]:
        _, deps = _state(request)
        try:
            return await deps.inventory_refresh.build_then(
                body.sourceIds,
                body.groups,
                lambda: deps.analysis_supervisor.start(body),
            )
        except Exception as exc:
            if isinstance(exc, KnowledgeError):
                raise
            raise KnowledgeError("ANALYSIS_BUILD_FAILED", "Analysis build failed") from exc

    @app.post("/api/v1/knowledge/analysis/retry-failed")
    async def analysis_retry_failed(request: Request, body: RetryFailedAnalysisRequest) -> Dict[str, Any]:
        _, deps = _state(request)
        try:
            return await deps.analysis_supervisor.retry_failed(body)
        except Exception as exc:
            if isinstance(exc, KnowledgeError):
                raise
            raise KnowledgeError("RETRY_SELECTION_FAILED", "Retry failed selection could not be created") from exc

    @app.get("/api/v1/knowledge/analysis/jobs/{job_id}")
    async def analysis_job(request: Request, job_id: str) -> Dict[str, Any]:
        _, deps = _state(request)
        job = deps.analysis_store.job(job_id)
        if job is None:
            raise KnowledgeError("ANALYSIS_JOB_NOT_FOUND", "Analysis job not found")
        return job

    @app.post("/api/v1/knowledge/analysis/jobs/{job_id}/stop")
    async def analysis_job_stop(request: Request, job_id: str) -> Dict[str, Any]:
        _, deps = _state(request)
        return await deps.analysis_supervisor.stop(job_id)

    @app.get("/api/v1/knowledge/analysis/status")
    async def analysis_status(request: Request, includeFreshness: bool = False) -> Dict[str, Any]:
        config, deps = _state(request)
        result = deps.analysis_store.status()
        result["currentFileProgress"] = _current_file_progress(deps)
        source_config = load_source_config(config.local_config_path)
        if not includeFreshness or source_config is None or not result.get("lastCompletedAt"):
            result["freshness"] = _unknown_freshness()
        else:
            result["freshness"] = KnowledgeFreshnessService(source_config, deps.inventory_store).check()
        return result

    @app.get("/api/v1/knowledge/analysis/current-file-progress")
    async def analysis_current_file_progress(request: Request) -> Dict[str, Any]:
        _, deps = _state(request)
        return _current_file_progress(deps)

    @app.get("/api/v1/knowledge/overview")
    async def overview(request: Request) -> Dict[str, Any]:
        _, deps = _state(request)
        result = read_overview(deps.inventory_store.db_path)
        result["currentFileProgress"] = _current_file_progress(deps)
        return result

    @app.get("/api/v1/knowledge/analysis/files")
    async def analysis_files(
        request: Request,
        sourceId: Optional[str] = None,
        status: Optional[str] = None,
        pathContains: Optional[str] = None,
        limit: int = Query(100, ge=1, le=1000),
        offset: int = Query(0, ge=0),
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.analysis_store.files(sourceId, status, pathContains, limit, offset)

    @app.get("/api/v1/knowledge/analysis/diagnostics")
    async def analysis_diagnostics(
        request: Request,
        sourceId: Optional[str] = None,
        limit: int = Query(100, ge=1, le=500),
        offset: int = Query(0, ge=0),
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.analysis_store.diagnostics(sourceId, limit, offset)

    @app.get("/api/v1/knowledge/analysis/graph/metadata")
    async def analysis_graph_metadata(
        request: Request,
        sourceId: Optional[str] = None,
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.analysis_store.graph_metadata(sourceId)

    @app.get("/api/v1/knowledge/analysis/graph/manifest")
    async def analysis_graph_manifest(
        request: Request,
        sourceId: Optional[str] = None,
        flowDomain: Optional[str] = None,
        factOrigin: Optional[str] = None,
        nodeKind: Optional[str] = None,
        edgeType: Optional[str] = None,
        includeExternal: str = "show",
        includeUnresolved: bool = True,
        includeIsolated: bool = True,
        search: Optional[str] = None,
    ) -> Response:
        _, deps = _state(request)
        manifest = deps.analysis_store.graph_manifest(
            sourceId,
            flowDomain,
            fact_origin=factOrigin,
            node_kind=nodeKind,
            edge_type=edgeType,
            include_external=includeExternal,
            include_unresolved=includeUnresolved,
            include_isolated=includeIsolated,
            search=search,
        )
        etag = manifest["etag"]
        headers = {
            "ETag": etag,
            "X-Graph-Revision": manifest["graphRevision"],
            "Cache-Control": "private, no-cache",
        }
        if request.headers.get("if-none-match") == etag:
            return Response(status_code=304, headers=headers)
        return JSONResponse(content=manifest, headers=headers)

    @app.get("/api/v1/knowledge/analysis/graph/view")
    async def analysis_graph_view(
        request: Request,
        sourceId: Optional[str] = None,
        flowDomain: Optional[str] = None,
        factOrigin: Optional[str] = None,
        nodeKind: Optional[str] = None,
        edgeType: Optional[str] = None,
        includeExternal: str = "show",
        includeUnresolved: bool = True,
        includeIsolated: bool = True,
        search: Optional[str] = None,
        maxNodes: int = Query(80, ge=0, le=5000),
    ) -> JSONResponse:
        _, deps = _state(request)
        return await _run_in_thread(
            _graph_view_response,
            deps.analysis_store,
            sourceId,
            flowDomain,
            factOrigin,
            nodeKind,
            edgeType,
            includeExternal,
            includeUnresolved,
            includeIsolated,
            search,
            maxNodes,
        )

    @app.get("/api/v1/knowledge/analysis/graph/nodes")
    async def analysis_graph_nodes(
        request: Request,
        graphRevision: str,
        cursor: Optional[str] = None,
        pageSize: int = Query(500, ge=1, le=5000),
        sourceId: Optional[str] = None,
        flowDomain: Optional[str] = None,
        factOrigin: Optional[str] = None,
        nodeKind: Optional[str] = None,
        includeExternal: str = "show",
        includeUnresolved: bool = True,
        includeIsolated: bool = True,
        search: Optional[str] = None,
    ) -> JSONResponse:
        _, deps = _state(request)
        page = deps.analysis_store.graph_nodes(
            graphRevision,
            cursor,
            pageSize,
            sourceId,
            flowDomain,
            fact_origin=factOrigin,
            node_kind=nodeKind,
            include_external=includeExternal,
            include_unresolved=includeUnresolved,
            include_isolated=includeIsolated,
            search=search,
        )
        return JSONResponse(
            content=page,
            headers={
                "X-Graph-Revision": graphRevision,
                "Cache-Control": "private, no-cache",
            },
        )

    @app.get("/api/v1/knowledge/analysis/graph/edges")
    async def analysis_graph_edges(
        request: Request,
        graphRevision: str,
        cursor: Optional[str] = None,
        pageSize: int = Query(1000, ge=1, le=5000),
        sourceId: Optional[str] = None,
        flowDomain: Optional[str] = None,
        factOrigin: Optional[str] = None,
        edgeType: Optional[str] = None,
        includeExternal: str = "show",
        includeUnresolved: bool = True,
        search: Optional[str] = None,
    ) -> JSONResponse:
        _, deps = _state(request)
        page = deps.analysis_store.graph_edges(
            graphRevision,
            cursor,
            pageSize,
            sourceId,
            flowDomain,
            fact_origin=factOrigin,
            edge_type=edgeType,
            include_external=includeExternal,
            include_unresolved=includeUnresolved,
            search=search,
        )
        return JSONResponse(
            content=page,
            headers={
                "X-Graph-Revision": graphRevision,
                "Cache-Control": "private, no-cache",
            },
        )

    @app.get("/api/v1/knowledge/analysis/graph/node/{node_id}")
    async def analysis_graph_node_detail(
        request: Request,
        node_id: str,
        graphRevision: str,
        sourceId: Optional[str] = None,
        includeEvidence: bool = False,
    ) -> JSONResponse:
        _, deps = _state(request)
        detail = deps.analysis_store.graph_node_detail(graphRevision, node_id, sourceId, includeEvidence)
        return JSONResponse(
            content=detail,
            headers={
                "X-Graph-Revision": graphRevision,
                "Cache-Control": "private, no-cache",
            },
        )

    @app.get("/api/v1/knowledge/analysis/graph/edge/{edge_id}")
    async def analysis_graph_edge_detail(
        request: Request,
        edge_id: str,
        graphRevision: str,
        sourceId: Optional[str] = None,
        includeEvidence: bool = False,
    ) -> JSONResponse:
        _, deps = _state(request)
        detail = deps.analysis_store.graph_edge_detail(graphRevision, edge_id, sourceId, includeEvidence)
        return JSONResponse(
            content=detail,
            headers={
                "X-Graph-Revision": graphRevision,
                "Cache-Control": "private, no-cache",
            },
        )

    return app


def _graph_view_response(
    analysis_store: AnalysisStore,
    source_id: Optional[str],
    flow_domain: Optional[str],
    fact_origin: Optional[str],
    node_kind: Optional[str],
    edge_type: Optional[str],
    include_external: str,
    include_unresolved: bool,
    include_isolated: bool,
    search: Optional[str],
    max_nodes: int,
) -> JSONResponse:
    view = analysis_store.graph_view(
        source_id,
        flow_domain,
        fact_origin=fact_origin,
        node_kind=node_kind,
        edge_type=edge_type,
        include_external=include_external,
        include_unresolved=include_unresolved,
        include_isolated=include_isolated,
        search=search,
        max_nodes=max_nodes,
    )
    return JSONResponse(
        content=view,
        headers={
            "X-Graph-Revision": view["graphRevision"],
            "Cache-Control": "private, no-cache",
        },
    )


def _knowledge_human_query_response(
    request: Request,
    body: KnowledgeQueryRequest,
    cancel_event: threading.Event | None = None,
    deadline_at: float | None = None,
):
    config, deps = _state(request)
    total_started = time.perf_counter()
    request_deadline_seconds = _human_query_request_deadline_seconds(config)
    deadline_at = deadline_at if deadline_at is not None else time.monotonic() + request_deadline_seconds
    correlation_id = _request_correlation_id(request)
    terminal_stage = "QUERY_INTERPRETATION"
    terminal_status = 503
    terminal_error_code = "KNOWLEDGE_QUERY_FAILED"
    terminal_error_message = "Knowledge query failed before a factual answer could be built."
    unexpected_exception_class: str | None = None
    unexpected_exception_stage: str | None = None
    retrieval_plan = None
    query_result = None
    selected_flows = ()
    interpretation_records = []
    answer_records = []
    pipeline_records = []
    fetch_duration_ms = 0.0
    family_assembly_duration_ms = 0.0
    answer_service = None
    try:
        if is_forbidden_response_language(body.answerLanguage):
            terminal_status = 422
            terminal_error_code = "RESPONSE_LANGUAGE_NOT_ALLOWED"
            terminal_error_message = "The requested response language is not allowed."
            return _public_error_response(
                terminal_status,
                terminal_error_code,
                terminal_error_message,
                correlation_id=correlation_id,
            )
        if time.monotonic() >= deadline_at:
            terminal_status = 504
            terminal_error_code = "HUMAN_QUERY_TIMEOUT"
            terminal_error_message = "Knowledge human query timed out."
            return _public_error_response(
                terminal_status,
                terminal_error_code,
                terminal_error_message,
                correlation_id=correlation_id,
            )
        interpretation_service, close_interpreter = _query_interpretation_service(request, config)
        try:
            retrieval_plan = interpretation_service.interpret(body, deadline_at=deadline_at)
        finally:
            interpretation_records = [dict(record) for record in interpretation_service.audit_records]
            if close_interpreter:
                close_interpreter()
        terminal_stage = "RETRIEVAL"
        retrieval_started = time.perf_counter()
        query_result = build_knowledge_query_service(deps.graph_store, config).query_with_flows(body, plan=retrieval_plan)
        fetch_duration_ms = round((time.perf_counter() - retrieval_started) * 1000, 3)
        selected_flows = tuple(query_result.narrative_plans or ())
        terminal_stage = "FLOW_ASSEMBLY"
        if not selected_flows:
            _record_query_interpretation_audits(request, body, interpretation_service.audit_records)
            terminal_status = 404
            terminal_error_code = "NO_GROUNDED_GRAPH_CANDIDATES"
            terminal_error_message = "No grounded graph candidates were found."
            return _public_error_response(
                terminal_status,
                terminal_error_code,
                terminal_error_message,
                correlation_id=correlation_id,
            )
        family_assembly_duration_ms = fetch_duration_ms
        answer_service, close_formatter = _flow_formatter_service(request, config)
        try:
            terminal_stage = "FORMATTER_PLAN_BUILDING"
            result = answer_service.answer(
                body,
                query_result,
                plan=retrieval_plan,
                deadline_at=deadline_at,
                cancel_event=cancel_event,
            )
        finally:
            answer_records = [dict(record) for record in answer_service.audit_records]
            pipeline_records = [dict(record) for record in answer_service.pipeline_records]
            if close_formatter:
                close_formatter()
        if pipeline_records:
            pipeline_records[0]["fetchDurationMs"] = fetch_duration_ms
            pipeline_records[0]["familyAssemblyDurationMs"] = family_assembly_duration_ms
        terminal_stage = "FINAL_FORMATTER"
        response = answer_service.to_response(result)
        terminal_status = 200
        terminal_error_code = None
        terminal_error_message = None
        terminal_stage = "SUCCESS"
        _record_human_answer_audits(request, body, answer_records, interpretation_service.audit_records)
        return response
    except QueryPlanningDeadlineExceeded:
        terminal_stage = "QUERY_INTERPRETATION"
        terminal_status = 504
        terminal_error_code = "QUERY_PLANNING_TIMEOUT"
        terminal_error_message = "Knowledge query planning timed out."
        return _public_error_response(
            terminal_status,
            terminal_error_code,
            terminal_error_message,
            correlation_id=correlation_id,
        )
    except (QueryPlanningProviderUnavailable, QueryPlanningMalformedResponse, QueryPlanningRepairExhausted):
        terminal_stage = "QUERY_INTERPRETATION"
        terminal_status = 502
        terminal_error_code = QUERY_INTERPRETATION_FAILED
        terminal_error_message = "The local model could not interpret the query."
        return _public_error_response(
            terminal_status,
            terminal_error_code,
            terminal_error_message,
            correlation_id=correlation_id,
        )
    except QueryInterpretationFailed:
        terminal_stage = "QUERY_INTERPRETATION"
        terminal_status = 502
        terminal_error_code = QUERY_INTERPRETATION_FAILED
        terminal_error_message = "The local model could not interpret the query."
        return _public_error_response(
            terminal_status,
            terminal_error_code,
            terminal_error_message,
            correlation_id=correlation_id,
        )
    except FlowFormatterDeadlineExceeded:
        terminal_stage = getattr(answer_service, "current_stage", None) or "FINAL_FORMATTER"
        terminal_status = 504
        terminal_error_code = "ANSWER_GENERATION_TIMEOUT"
        terminal_error_message = "Knowledge answer generation timed out."
        _record_human_answer_audits(request, body, answer_records, interpretation_service.audit_records)
        return _public_error_response(
            terminal_status,
            terminal_error_code,
            terminal_error_message,
            correlation_id=correlation_id,
        )
    except FlowFormatterAllPlansFailed:
        terminal_stage = getattr(answer_service, "current_stage", None) or "FINAL_FORMATTER"
        terminal_status = 502
        terminal_error_code = "FINAL_FORMATTER_FAILED"
        terminal_error_message = "The local model could not format a factual answer."
        _record_human_answer_audits(request, body, answer_records, interpretation_service.audit_records)
        return _public_error_response(
            terminal_status,
            terminal_error_code,
            terminal_error_message,
            correlation_id=correlation_id,
        )
    except Exception as exc:
        unexpected_exception_class = type(exc).__name__
        unexpected_exception_stage = getattr(answer_service, "current_stage", None) or terminal_stage
        terminal_stage = "UNEXPECTED_EXCEPTION"
        terminal_status = 503
        terminal_error_code = "KNOWLEDGE_QUERY_FAILED"
        terminal_error_message = "Knowledge query failed before a factual answer could be built."
        LOGGER.exception(
            "knowledge_human_query_unexpected_exception",
            extra={
                "correlationId": correlation_id,
                "terminalStage": terminal_stage,
                "failureStage": unexpected_exception_stage,
                "exceptionClass": unexpected_exception_class,
            },
        )
        return _public_error_response(
            terminal_status,
            terminal_error_code,
            terminal_error_message,
            correlation_id=correlation_id,
        )
    finally:
        total_duration_ms = round((time.perf_counter() - total_started) * 1000, 3)
        if pipeline_records:
            pipeline_records[0]["totalDurationMs"] = total_duration_ms
        elif selected_flows:
            pipeline_records = [{
                "narrativePlanCount": len(selected_flows),
                "walkthroughStepCount": 0,
                "branchCount": 0,
                "gapCount": 0,
                "answerCount": 0,
                "fetchDurationMs": fetch_duration_ms,
                "familyAssemblyDurationMs": family_assembly_duration_ms,
                "walkthroughPlanningDurationMs": 0.0,
                "formatterPlanningDurationMs": 0.0,
                "formatterDurationMs": 0.0,
                "stitchingDurationMs": 0.0,
                "textRenderingDurationMs": 0.0,
                "totalDurationMs": total_duration_ms,
                "formatterProviderCallCount": 0,
                "formatterRepairCallCount": 0,
                "formatterOutputSplitCallCount": 0,
                "formatterSegmentCount": 0,
                "formatterGroupCount": 0,
                "formatterSerializationCount": 0,
                "finalAnswerProviderCallCount": 0,
                "groundingProviderCallCount": 0,
                "analysisProviderCallCount": 0,
                "toolContextFormatterCallCount": 0,
            }]
        _record_human_query_terminal_audit(
            request,
            body,
            correlation_id=correlation_id,
            retrieval_plan=retrieval_plan,
            query_result=query_result,
            selected_flows=selected_flows,
            interpretation_records=interpretation_records,
            answer_records=answer_records,
            pipeline_records=pipeline_records,
            terminal_status=terminal_status,
            terminal_error_code=terminal_error_code,
            terminal_error_message=terminal_error_message,
            terminal_stage=terminal_stage,
            unexpected_exception_class=unexpected_exception_class,
            unexpected_exception_stage=unexpected_exception_stage,
        )


def _knowledge_query_tool_context_response(
    request: Request,
    body: KnowledgeQueryRequest,
):
    config, deps = _state(request)
    if is_forbidden_response_language(body.answerLanguage):
        return _forbidden_response_language_response()
    try:
        deadline_at = time.monotonic() + _human_query_request_deadline_seconds(config)
        interpretation_service, close_interpreter = _query_interpretation_service(request, config)
        try:
            retrieval_plan = interpretation_service.interpret(body, deadline_at=deadline_at)
        finally:
            _record_query_interpretation_audits(request, body, interpretation_service.audit_records)
            _write_human_answer_audit_artifact(config, body, [], interpretation_service.audit_records)
            if close_interpreter:
                close_interpreter()
        query_result = build_knowledge_query_service(deps.graph_store, config).query_with_flows(body, plan=retrieval_plan)
        if not tuple(query_result.flows or ()):
            return _public_error_response(
                404,
                "NO_GROUNDED_GRAPH_CANDIDATES",
                "No grounded graph candidates were found.",
            )
        return FlowProjectionBuilder().to_tool_response(body, query_result)
    except QueryPlanningDeadlineExceeded:
        return _public_error_response(
            504,
            "QUERY_PLANNING_TIMEOUT",
            "Knowledge query planning timed out.",
        )
    except (QueryPlanningProviderUnavailable, QueryPlanningMalformedResponse, QueryPlanningRepairExhausted, QueryInterpretationFailed):
        return _public_error_response(
            502,
            QUERY_INTERPRETATION_FAILED,
            "The local model could not interpret the query.",
        )
    except Exception:
        return _public_error_response(
            503,
            "KNOWLEDGE_QUERY_FAILED",
            "Knowledge query failed before tool context could be built.",
        )


def _public_error_response(status_code: int, code: str, message: str, *, correlation_id: str | None = None) -> JSONResponse:
    content = {"code": code, "message": message}
    if correlation_id:
        content["correlationId"] = correlation_id
    return JSONResponse(status_code=status_code, content=content)


def _forbidden_response_language_response() -> JSONResponse:
    return _public_error_response(
        422,
        "RESPONSE_LANGUAGE_NOT_ALLOWED",
        "The requested response language is not allowed.",
    )


def _request_correlation_id(request: Request) -> str:
    metrics = current_route_metrics()
    if metrics is not None and metrics.correlation_id:
        return metrics.correlation_id
    headers = getattr(request, "headers", None)
    return sanitize_correlation_id(headers.get(CORRELATION_HEADER) if headers is not None else None)


def _record_human_query_terminal_audit(
    request: Request,
    body: KnowledgeQueryRequest,
    *,
    correlation_id: str,
    retrieval_plan,
    query_result,
    selected_flows,
    interpretation_records,
    answer_records,
    pipeline_records,
    terminal_status: int,
    terminal_error_code: str | None,
    terminal_error_message: str | None,
    terminal_stage: str,
    unexpected_exception_class: str | None,
    unexpected_exception_stage: str | None,
) -> None:
    config, _ = _state(request)
    record = _human_query_terminal_audit_record(
        body,
        correlation_id=correlation_id,
        retrieval_plan=retrieval_plan,
        query_result=query_result,
        selected_flows=tuple(selected_flows or ()),
        interpretation_records=list(interpretation_records or []),
        answer_records=list(answer_records or []),
        pipeline_records=list(pipeline_records or []),
        terminal_status=terminal_status,
        terminal_error_code=terminal_error_code,
        terminal_error_message=terminal_error_message,
        terminal_stage=terminal_stage,
        unexpected_exception_class=unexpected_exception_class,
        unexpected_exception_stage=unexpected_exception_stage,
    )
    existing = getattr(request.app.state, "human_query_terminal_audit_artifacts", None)
    if existing is None:
        existing = deque(maxlen=max(0, int(config.query_audit_memory_max_records)))
        request.app.state.human_query_terminal_audit_artifacts = existing
    existing.append(record)
    _write_human_query_terminal_audit_artifact(config, record)


def _human_query_terminal_audit_record(
    body: KnowledgeQueryRequest,
    *,
    correlation_id: str,
    retrieval_plan,
    query_result,
    selected_flows,
    interpretation_records,
    answer_records,
    pipeline_records,
    terminal_status: int,
    terminal_error_code: str | None,
    terminal_error_message: str | None,
    terminal_stage: str,
    unexpected_exception_class: str | None,
    unexpected_exception_stage: str | None,
) -> Dict[str, Any]:
    selected_flow_summaries = _selected_flow_audit_summaries(selected_flows)
    family_assembly_summary = _family_assembly_terminal_payload(query_result, selected_flow_summaries)
    walkthrough_metrics = _walkthrough_terminal_metrics(pipeline_records)
    retrieval_summary = _retrieval_terminal_payload(query_result)
    query_interpreter = _query_interpreter_terminal_payload(retrieval_plan, interpretation_records, terminal_stage, terminal_error_code)
    query_interpreter_call_count = int(query_interpreter.get("providerCallCount") or 0)
    return {
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "correlationId": correlation_id,
        "queryText": body.queryText,
        "answerLanguage": body.answerLanguage,
        "intent": body.intent.value if hasattr(body.intent, "value") else str(body.intent),
        "includeTests": bool(body.includeTests),
        "maxFlows": int(body.maxFlows),
        "queryInterpreter": query_interpreter,
        "queryInterpreterCallCount": query_interpreter_call_count,
        "retrieval": retrieval_summary,
        "candidateCount": int(retrieval_summary.get("matchedNodeCount") or 0),
        "familyAssembly": family_assembly_summary,
        "rawCandidateFlowCount": family_assembly_summary.get("rawCandidateFlowCount"),
        "discoveredFamilyCount": family_assembly_summary.get("discoveredFamilyCount"),
        "selectedFamilyCount": family_assembly_summary.get("selectedFamilyCount"),
        "verifiedFragmentCount": _verified_fragment_count(selected_flows),
        "narrativePlanCount": int(walkthrough_metrics.get("narrativePlanCount") or len(selected_flow_summaries)),
        "walkthroughStepCount": int(walkthrough_metrics.get("walkthroughStepCount") or 0),
        "branchCount": int(walkthrough_metrics.get("branchCount") or 0),
        "gapCount": int(walkthrough_metrics.get("gapCount") or 0),
        "answerCount": int(walkthrough_metrics.get("answerCount") or 0),
        "fetchDurationMs": float(walkthrough_metrics.get("fetchDurationMs") or 0.0),
        "familyAssemblyDurationMs": float(walkthrough_metrics.get("familyAssemblyDurationMs") or 0.0),
        "walkthroughPlanningDurationMs": float(walkthrough_metrics.get("walkthroughPlanningDurationMs") or 0.0),
        "textRenderingDurationMs": float(walkthrough_metrics.get("textRenderingDurationMs") or 0.0),
        "formatterGroupCount": int(walkthrough_metrics.get("formatterGroupCount") or 0),
        "formatterSegmentCount": int(walkthrough_metrics.get("formatterSegmentCount") or 0),
        "formatterSerializationCount": int(walkthrough_metrics.get("formatterSerializationCount") or 0),
        "formatterPlanningDurationMs": float(walkthrough_metrics.get("formatterPlanningDurationMs") or 0.0),
        "formatterDurationMs": float(walkthrough_metrics.get("formatterDurationMs") or 0.0),
        "totalFormatterDurationMs": float(walkthrough_metrics.get("totalFormatterDurationMs") or 0.0),
        "stitchingDurationMs": float(walkthrough_metrics.get("stitchingDurationMs") or 0.0),
        "totalDurationMs": float(walkthrough_metrics.get("totalDurationMs") or 0.0),
        "familyRoots": family_assembly_summary.get("familyRoots"),
        "selectedFlowCount": len(selected_flow_summaries),
        "selectedSources": sorted({item["source"] for item in selected_flow_summaries if item.get("source")}),
        "selectedEntrypoints": [item["entrypoint"] for item in selected_flow_summaries if item.get("entrypoint")],
        "selectedFlows": selected_flow_summaries,
        "walkthrough": walkthrough_metrics,
        "walkthroughPlans": pipeline_records,
        "formatterRecords": [_compact_provider_audit_record(dict(record)) for record in (answer_records or [])],
        "providerCallCount": int(walkthrough_metrics.get("formatterProviderCallCount") or 0),
        "repairCallCount": int(walkthrough_metrics.get("formatterRepairCallCount") or 0),
        "finalAnswerProviderCallCount": int(walkthrough_metrics.get("finalAnswerProviderCallCount") or 0),
        "formatterProviderCallCount": int(walkthrough_metrics.get("formatterProviderCallCount") or 0),
        "formatterRepairCallCount": int(walkthrough_metrics.get("formatterRepairCallCount") or 0),
        "formatterOutputSplitCallCount": int(walkthrough_metrics.get("formatterOutputSplitCallCount") or 0),
        "groundingProviderCallCount": int(walkthrough_metrics.get("groundingProviderCallCount") or 0),
        "analysisProviderCallCount": int(walkthrough_metrics.get("analysisProviderCallCount") or 0),
        "toolContextFormatterCallCount": int(walkthrough_metrics.get("toolContextFormatterCallCount") or 0),
        "terminalHttpStatus": int(terminal_status),
        "terminalErrorCode": terminal_error_code,
        "terminalErrorMessage": terminal_error_message,
        "terminalStage": terminal_stage,
        "unexpectedExceptionClass": unexpected_exception_class,
        "unexpectedExceptionStage": unexpected_exception_stage,
    }


def _family_assembly_terminal_payload(query_result, selected_flow_summaries: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    assembly = getattr(query_result, "family_assembly", None)
    raw_flows = tuple(getattr(query_result, "raw_flows", ()) or ())
    selected_roots = [
        {"source": item.get("source"), "entrypoint": item.get("entrypoint")}
        for item in selected_flow_summaries
    ]
    return {
        "rawCandidateFlowCount": int(getattr(assembly, "raw_candidate_flow_count", len(raw_flows))),
        "discoveredFamilyCount": int(getattr(assembly, "discovered_family_count", len(selected_flow_summaries))),
        "selectedFamilyCount": len(selected_flow_summaries),
        "familyRoots": selected_roots,
        "rootReachability": dict(getattr(assembly, "root_reachability", {}) or {}),
    }


def _walkthrough_terminal_metrics(pipeline_records) -> Dict[str, Any]:
    records = [record for record in pipeline_records if isinstance(record, dict)]

    def total_int(key: str) -> int:
        return sum(int(record.get(key) or 0) for record in records)

    def total_float(key: str) -> float:
        return round(sum(float(record.get(key) or 0.0) for record in records), 3)

    return {
        "narrativePlanCount": total_int("narrativePlanCount"),
        "walkthroughStepCount": total_int("walkthroughStepCount"),
        "branchCount": total_int("branchCount"),
        "gapCount": total_int("gapCount"),
        "answerCount": total_int("answerCount"),
        "fetchDurationMs": total_float("fetchDurationMs"),
        "familyAssemblyDurationMs": total_float("familyAssemblyDurationMs"),
        "walkthroughPlanningDurationMs": total_float("walkthroughPlanningDurationMs"),
        "textRenderingDurationMs": total_float("textRenderingDurationMs"),
        "formatterGroupCount": total_int("formatterGroupCount"),
        "formatterSegmentCount": total_int("formatterSegmentCount"),
        "formatterSerializationCount": total_int("formatterSerializationCount"),
        "formatterPlanningDurationMs": total_float("formatterPlanningDurationMs"),
        "formatterDurationMs": total_float("formatterDurationMs"),
        "totalFormatterDurationMs": total_float("totalFormatterDurationMs"),
        "stitchingDurationMs": total_float("stitchingDurationMs"),
        "totalDurationMs": max((float(record.get("totalDurationMs") or 0.0) for record in records), default=0.0),
        "formatterProviderCallCount": total_int("formatterProviderCallCount"),
        "formatterRepairCallCount": total_int("formatterRepairCallCount"),
        "formatterOutputSplitCallCount": total_int("formatterOutputSplitCallCount"),
        "finalAnswerProviderCallCount": total_int("finalAnswerProviderCallCount"),
        "groundingProviderCallCount": total_int("groundingProviderCallCount"),
        "analysisProviderCallCount": total_int("analysisProviderCallCount"),
        "toolContextFormatterCallCount": total_int("toolContextFormatterCallCount"),
    }


def _verified_fragment_count(selected_flows) -> int:
    return sum(len(tuple(getattr(plan, "fragments", ()) or ())) for plan in tuple(selected_flows or ()))


def _query_interpreter_terminal_payload(retrieval_plan, interpretation_records, terminal_stage: str, terminal_error_code: str | None) -> Dict[str, Any]:
    validation_errors = [
        str(error)
        for record in interpretation_records
        for error in record.get("validationErrors", [])
        if str(error).strip()
    ]
    payload = {
        "providerCallCount": len(interpretation_records),
        "detectedLanguage": getattr(retrieval_plan, "detected_language", None),
        "responseLanguage": getattr(retrieval_plan, "response_language", None),
        "effectiveIntent": getattr(retrieval_plan, "effective_intent", None),
        "normalizedQuery": getattr(retrieval_plan, "normalized_query", None),
        "searchQueries": list(getattr(retrieval_plan, "search_queries", ()) or ()),
        "codeIdentifiers": list(getattr(retrieval_plan, "code_identifiers", ()) or ()),
        "concepts": list(getattr(retrieval_plan, "concepts", ()) or ()),
        "validationAttempts": len(interpretation_records),
        "validationErrors": validation_errors,
        "status": "SUCCESS" if retrieval_plan is not None else "FAILED",
        "records": [_compact_provider_audit_record(record) for record in interpretation_records],
    }
    if retrieval_plan is None and terminal_stage == "QUERY_INTERPRETATION":
        payload["errorCode"] = terminal_error_code
    return payload


def _retrieval_terminal_payload(query_result) -> Dict[str, Any]:
    response = getattr(query_result, "response", None)
    coverage = getattr(response, "coverage", None)
    matched_sources = getattr(response, "matchedSources", []) or []
    matched_nodes = getattr(response, "matchedNodes", []) or []
    return {
        "queryId": getattr(response, "queryId", None),
        "status": getattr(getattr(response, "status", None), "value", getattr(response, "status", None)),
        "matchedSourceCount": len(matched_sources),
        "matchedNodeCount": len(matched_nodes),
        "flowCount": getattr(coverage, "flowCount", None),
        "nodeCount": getattr(coverage, "nodeCount", None),
        "edgeCount": getattr(coverage, "edgeCount", None),
        "evidenceCount": getattr(coverage, "evidenceCount", None),
    }


def _selected_flow_audit_summaries(selected_flows) -> list[Dict[str, Any]]:
    projector = FlowProjectionBuilder()
    summaries: list[Dict[str, Any]] = []
    for index, flow in enumerate(tuple(selected_flows or ()), start=1):
        source, entrypoint = projector.flow_answer_identity(flow)
        coverage = getattr(flow, "coverage", None)
        evidence_items = tuple(getattr(flow, "evidence", ()) or ())
        summaries.append(
            {
                "flowIndex": index,
                "source": source,
                "entrypoint": entrypoint,
                "nodeCount": int(getattr(coverage, "node_count", len(getattr(flow, "nodes", ()) or ()))),
                "transitionCount": int(getattr(coverage, "transition_count", len(getattr(flow, "transitions", ()) or ()))),
                "boundaryCount": int(getattr(coverage, "boundary_count", len(getattr(flow, "boundary_transitions", ()) or ()))),
                "evidenceRecordCount": len(evidence_items),
                "evidenceExcerptUtf8Bytes": sum(len(str(getattr(item, "text", "") or "").encode("utf-8")) for item in evidence_items),
                "supportingRelationCount": len(tuple(getattr(flow, "supporting_transitions", ()) or ())),
                "subordinateEntrypointCount": int(getattr(flow, "subordinate_entrypoint_count", 0) or 0),
            }
        )
    return summaries


def _compact_provider_audit_record(record: Dict[str, Any]) -> Dict[str, Any]:
    allowed = {
        "provider",
        "model",
        "promptLength",
        "promptHash",
        "rawResponseLength",
        "rawResponseHash",
        "attemptCount",
        "requestedLanguage",
        "resolvedLanguage",
        "detectedLanguage",
        "validationErrors",
        "postValidationErrors",
        "durationMs",
        "remainingDeadlineBeforeCall",
        "remainingDeadlineAfterCall",
        "responseLanguage",
        "segmentIndex",
        "segmentCount",
        "groupCount",
        "renderedInputTokens",
        "reservedOutputTokens",
        "minimumValidOutputTokens",
        "contextTokens",
        "truncated",
        "errorClass",
    }
    return {key: record.get(key) for key in allowed if key in record}


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()


def _record_human_answer_audits(
    request: Request,
    body: KnowledgeQueryRequest,
    records,
    interpretation_records=None,
) -> None:
    interpretation_records = list(interpretation_records or [])
    if not records and not interpretation_records:
        return
    config, _ = _state(request)
    _record_query_interpretation_audits(request, body, interpretation_records)
    existing = getattr(request.app.state, "human_answer_audit_artifacts", None)
    if existing is None:
        existing = deque(maxlen=max(0, int(config.query_audit_memory_max_records)))
        request.app.state.human_answer_audit_artifacts = existing
    existing.extend(dict(record) for record in records)
    _write_human_answer_audit_artifact(config, body, records, interpretation_records)


def _record_query_interpretation_audits(request: Request, body: KnowledgeQueryRequest, records) -> None:
    if not records:
        return
    config, _ = _state(request)
    existing = getattr(request.app.state, "query_interpretation_audit_artifacts", None)
    if existing is None:
        existing = deque(maxlen=max(0, int(config.query_audit_memory_max_records)))
        request.app.state.query_interpretation_audit_artifacts = existing
    existing.extend(dict(record) for record in records)


def _write_human_answer_audit_artifact(
    config: AppConfig,
    body: KnowledgeQueryRequest,
    records,
    interpretation_records=None,
) -> None:
    configured_directory = config.query_audit_directory
    directory = str(configured_directory or os.environ.get("FORGE_KNOWLEDGE_HUMAN_ANSWER_AUDIT_DIR") or "").strip()
    if not directory or int(config.query_audit_max_retained_files) <= 0:
        return
    try:
        root = Path(directory)
        root.mkdir(parents=True, exist_ok=True)
        payload = {
            "requestPayload": body.dict(exclude_none=True),
            "providerCallCount": len(records),
            "queryInterpretationProviderCallCount": len(interpretation_records or []),
            "interpretationRecords": [dict(record) for record in (interpretation_records or [])],
            "records": [dict(record) for record in records],
        }
        filename = f"human-answer-{int(time.time() * 1000)}-{uuid.uuid4().hex}.json"
        target = root / filename
        tmp = root / f".{filename}.tmp"
        with tmp.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        os.replace(tmp, target)
        _cleanup_human_answer_audit_files(
            root,
            max_retained_files=config.query_audit_max_retained_files,
            max_file_age_seconds=config.query_audit_max_file_age_seconds,
        )
    except Exception as exc:
        LOGGER.warning("human_answer_audit_write_failed", extra={"error": type(exc).__name__, "directory": directory})


def _write_human_query_terminal_audit_artifact(config: AppConfig, record: Dict[str, Any]) -> None:
    configured_directory = config.query_audit_directory
    directory = str(configured_directory or os.environ.get("FORGE_KNOWLEDGE_HUMAN_ANSWER_AUDIT_DIR") or "").strip()
    if not directory or int(config.query_audit_max_retained_files) <= 0:
        return
    try:
        root = Path(directory)
        root.mkdir(parents=True, exist_ok=True)
        filename = f"{HUMAN_QUERY_TERMINAL_AUDIT_PREFIX}{int(time.time() * 1000)}-{uuid.uuid4().hex}.json"
        target = root / filename
        tmp = root / f".{filename}.tmp"
        with tmp.open("w", encoding="utf-8") as handle:
            json.dump(record, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        os.replace(tmp, target)
        _cleanup_human_answer_audit_files(
            root,
            max_retained_files=config.query_audit_max_retained_files,
            max_file_age_seconds=config.query_audit_max_file_age_seconds,
            pattern=f"{HUMAN_QUERY_TERMINAL_AUDIT_PREFIX}*.json",
        )
    except Exception as exc:
        LOGGER.warning("human_query_terminal_audit_write_failed", extra={"error": type(exc).__name__, "directory": directory})


def _cleanup_human_answer_audit_files(
    root: Path,
    *,
    max_retained_files: int,
    max_file_age_seconds: Optional[int],
    pattern: str = "human-answer-*.json",
) -> None:
    now = time.time()
    files = sorted(
        (path for path in root.glob(pattern) if path.is_file()),
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=True,
    )
    for path in files:
        if max_file_age_seconds is not None and now - path.stat().st_mtime > max_file_age_seconds:
            path.unlink(missing_ok=True)
    files = sorted(
        (path for path in root.glob(pattern) if path.is_file()),
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=True,
    )
    for path in files[max(0, int(max_retained_files)):]:
        path.unlink(missing_ok=True)


async def _run_in_thread(func, *args, request_cancel_event: threading.Event | None = None, **kwargs):
    try:
        return await anyio.to_thread.run_sync(functools.partial(func, *args, **kwargs), abandon_on_cancel=True)
    except asyncio.CancelledError:
        if request_cancel_event is not None:
            request_cancel_event.set()
        raise


def _state(request: Request) -> tuple[AppConfig, KnowledgeDependencies]:
    if hasattr(request.app.state, "app_config") and hasattr(request.app.state, "knowledge_dependencies"):
        return request.app.state.app_config, request.app.state.knowledge_dependencies
    if app_config is not None and store is not None:
        analysis_store = AnalysisStore(store.db_path)
        supervisor = analysis_supervisor or AnalysisSupervisor(store, app_config)
        refresh = InventoryRefreshService(app_config, store)
        scheduler = AsyncInventoryScheduler(refresh, app_config)
        return app_config, KnowledgeDependencies(
            inventory_store=store,
            analysis_store=analysis_store,
            graph_store=analysis_store,
            source_resolver=InventoryFileResolver(store),
            analysis_provider=None,
            analysis_supervisor=supervisor,
            inventory_refresh=refresh,
            inventory_scheduler=scheduler,
            storage_operations=StorageOperations(store.db_path),
        )
    raise RuntimeError("Knowledge app dependencies are not initialized")


def _query_interpretation_service(
    request: Request,
    config: AppConfig,
) -> tuple[QueryInterpretationService, Optional[Any]]:
    injected_provider = getattr(request.app.state, "query_interpretation_provider", None)
    request_deadline_seconds = _human_query_request_deadline_seconds(config)
    default_response_language = getattr(config, "query_default_response_language", "en")
    if injected_provider is not None:
        return QueryInterpretationService(
            injected_provider,
            default_response_language=default_response_language,
            request_deadline_seconds=request_deadline_seconds,
            provider_name=config.analysis_provider,
            provider_model=config.analysis_model,
            audit_max_records=config.query_audit_memory_max_records,
        ), None
    provider = LocalOllamaQueryInterpretationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
        renderer=QueryInterpretationPromptRenderer(),
    )
    return QueryInterpretationService(
        provider,
        default_response_language=default_response_language,
        request_deadline_seconds=request_deadline_seconds,
        provider_name=config.analysis_provider,
        provider_model=config.analysis_model,
        audit_max_records=config.query_audit_memory_max_records,
    ), provider.close


def _flow_formatter_service(
    request: Request,
    config: AppConfig,
) -> tuple[FlowFormatterAnswerService, Optional[Any]]:
    injected_provider = getattr(request.app.state, "final_flow_formatter_provider", None)
    request_deadline_seconds = _human_query_request_deadline_seconds(config)
    segment_planner = FlowFormatterSegmentPlanner(context_tokens=config.analysis_context_tokens)
    formatter_model = str(os.environ.get("FORGE_FLOW_FORMATTER_MODEL") or config.analysis_model)
    if injected_provider is not None:
        return FlowFormatterAnswerService(
            injected_provider,
            segment_planner=segment_planner,
            request_deadline_seconds=request_deadline_seconds,
            provider_name=config.analysis_provider,
            provider_model=formatter_model,
            audit_max_records=config.query_audit_memory_max_records,
        ), None
    provider = LocalOllamaFlowFormatterClient(
        config.analysis_base_url,
        formatter_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
        renderer=FlowFormatterPromptRenderer(),
    )
    return FlowFormatterAnswerService(
        provider,
        segment_planner=segment_planner,
        request_deadline_seconds=request_deadline_seconds,
        provider_name=config.analysis_provider,
        provider_model=formatter_model,
        audit_max_records=config.query_audit_memory_max_records,
    ), provider.close


def _human_query_request_deadline_seconds(config: AppConfig) -> float:
    return max(0.001, float(config.human_query_request_timeout_seconds))


def _current_file_progress(dependencies: KnowledgeDependencies) -> Dict[str, Any]:
    progress = getattr(dependencies.analysis_supervisor, "current_file_progress", None)
    if callable(progress):
        return progress()
    return {"active": False, "entries": []}


def _semantic_job_state(app: FastAPI):
    if not hasattr(app.state, "semantic_build_jobs"):
        app.state.semantic_build_jobs = {}
    if not hasattr(app.state, "semantic_build_lock"):
        app.state.semantic_build_lock = threading.Lock()
    return app.state.semantic_build_jobs, app.state.semantic_build_lock


def _semantic_index_builder(config: AppConfig, db_path) -> SemanticIndexBuilder:
    provider = OllamaEmbeddingProvider(
        config.semantic_ollama_base_url,
        config.semantic_embedding_model,
        config.semantic_request_timeout_seconds,
    )
    return SemanticIndexBuilder(
        db_path,
        provider,
        config=SemanticBuildConfig(
            enabled=config.semantic_enabled,
            embedding_model=config.semantic_embedding_model,
            batch_size=config.semantic_batch_size,
            max_document_chars=config.semantic_max_document_chars,
            max_edges_per_document=config.semantic_max_edges_per_document,
            max_documents_per_build=config.semantic_max_documents_per_build,
        ),
    )


def _semantic_build_coordinator(app: FastAPI, config: AppConfig, db_path) -> SemanticBuildCoordinator:
    existing = getattr(app.state, "semantic_build_coordinator", None)
    if isinstance(existing, SemanticBuildCoordinator) and existing.db_path == db_path:
        return existing
    _, lock = _semantic_job_state(app)
    factory = getattr(app.state, "semantic_builder_factory", None)
    if factory is None:
        def factory():
            return _semantic_index_builder(config, db_path)

    coordinator = SemanticBuildCoordinator(db_path, lock, factory)
    app.state.semantic_build_coordinator = coordinator
    return coordinator


def _semantic_background_worker(app: FastAPI, config: AppConfig, db_path) -> SemanticIndexBackgroundWorker:
    existing = getattr(app.state, "semantic_worker", None)
    if isinstance(existing, SemanticIndexBackgroundWorker) and existing.db_path == db_path:
        return existing
    coordinator = _semantic_build_coordinator(app, config, db_path)
    worker = SemanticIndexBackgroundWorker(
        db_path,
        coordinator,
        enabled=config.semantic_enabled and config.semantic_auto_build_enabled,
        interval_seconds=config.semantic_auto_build_interval_seconds,
        failed_retry_backoff_seconds=config.semantic_failed_retry_backoff_seconds,
        building_stale_after_seconds=config.semantic_building_stale_after_seconds,
    )
    app.state.semantic_worker = worker
    return worker


def _run_semantic_build_job(jobs, coordinator: SemanticBuildCoordinator, job_id: str, source_ids, force: bool) -> None:
    try:
        jobs[job_id] = {**jobs[job_id], "status": "RUNNING"}
        result = coordinator.build_locked(source_ids, force=force, build_id=job_id).to_dict()
        jobs[job_id] = result
    except Exception:
        jobs[job_id] = {
            "jobId": job_id,
            "status": "FAILED",
            "sourceIds": list(source_ids or []),
            "diagnostics": [{"code": "SEMANTIC_BUILD_FAILED", "message": "Semantic index build failed.", "severity": "WARN"}],
            "results": [],
        }
    finally:
        coordinator.release()


def _semantic_status(app: FastAPI, config: AppConfig) -> Dict[str, Any]:
    worker = getattr(app.state, "semantic_worker", None)
    worker_status = worker.status() if isinstance(worker, SemanticIndexBackgroundWorker) else {}
    return {
        "enabled": config.semantic_enabled,
        "autoBuildEnabled": config.semantic_auto_build_enabled,
        "autoWorkerConfigured": isinstance(worker, SemanticIndexBackgroundWorker),
        "autoWorkerRunning": bool(worker_status.get("running")),
        "embeddingModel": config.semantic_embedding_model,
        "worker": worker_status,
    }


def _unknown_freshness() -> Dict[str, Any]:
    return {
        "status": "UNKNOWN",
        "checkedAt": None,
        "newFiles": 0,
        "modifiedFiles": 0,
        "deletedFiles": 0,
        "affectedScannedFiles": 0,
    }


def _safe_error(request: Request, code: str, message: str) -> Dict[str, Any]:
    metrics = current_route_metrics()
    correlation_id = metrics.correlation_id if metrics else sanitize_correlation_id(request.headers.get(CORRELATION_HEADER))
    route = metrics.route_key if metrics else None
    payload: Dict[str, Any] = {"code": code, "message": message, "correlationId": correlation_id}
    if route:
        payload["route"] = route
    return payload


app = create_app()
