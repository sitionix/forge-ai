from __future__ import annotations

import asyncio
import functools
import sqlite3
import threading
import time
import uuid
from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

import anyio
from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, Response

from knowledge_service.analysis_schema import AnalysisBuildRequest, RetryFailedAnalysisRequest
from knowledge_service.analysis_service import AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.bootstrap import KnowledgeDependencies, build_dependencies, configure_logging
from knowledge_service.config import (
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
    AppConfig,
    ForgeSettings,
    load_forge_settings,
)
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.flow_explanations import (
    FLOW_EXPLANATION_LIMIT_REACHED,
    CompactFlowProjector,
    FlowExplanationDeadlineExceeded,
    FlowExplanationService,
    HumanAnswerGenerationFailed,
    HumanAnswerPromptRenderer,
    HumanFlowAnswerService,
    LocalOllamaFlowExplanationClient,
)
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_schema import InventoryBuildRequest
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.knowledge_query_schema import (
    KnowledgeQueryDiagnostic,
    KnowledgeQueryFlowExplanationResponse,
    KnowledgeQueryRequest,
    KnowledgeQueryResponse,
    KnowledgeQueryStatus,
    KnowledgeQueryToolContextResponse,
)
from knowledge_service.knowledge_query_service import build_knowledge_query_service
from knowledge_service.observability import (
    CORRELATION_HEADER,
    ObservabilityMiddleware,
    current_route_metrics,
    sanitize_correlation_id,
)
from knowledge_service.overview_projection import read_overview
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
        await deps.analysis_supervisor.start_lifespan()
        await deps.inventory_scheduler.start()
        app.state.semantic_worker.start()
        try:
            yield
        finally:
            app.state.semantic_worker.stop(app_config.analysis_shutdown_grace_seconds)
            await deps.inventory_scheduler.stop()
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
                "flowExplanationRequestTimeoutSeconds": config.flow_explanation_request_timeout_seconds,
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

    @app.post("/api/v1/knowledge/query", response_model=KnowledgeQueryResponse)
    async def knowledge_query(request: Request, body: KnowledgeQueryRequest) -> KnowledgeQueryResponse:
        return await _run_in_thread(_knowledge_query_response, request, body)

    @app.post(
        "/api/v1/knowledge/query/flow-explanations",
        response_model=KnowledgeQueryFlowExplanationResponse,
        response_model_exclude_none=True,
    )
    async def knowledge_query_flow_explanations(request: Request, body: KnowledgeQueryRequest):
        config, _ = _state(request)
        deadline_at = time.monotonic() + _flow_explanation_request_deadline_seconds(config)
        cancel_event = threading.Event()
        return await _run_in_thread(
            _knowledge_query_flow_explanations_response,
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


def _knowledge_query_response(request: Request, body: KnowledgeQueryRequest) -> KnowledgeQueryResponse:
    config, deps = _state(request)
    try:
        return build_knowledge_query_service(deps.graph_store, config).query(body)
    except Exception:
        return KnowledgeQueryResponse(
            queryId="query-failed",
            status=KnowledgeQueryStatus.QUERY_FAILED,
            intent=body.intent,
            diagnostics=[
                KnowledgeQueryDiagnostic(
                    code="KNOWLEDGE_QUERY_FAILED",
                    message="Knowledge query failed before a factual bundle could be built.",
                    severity="ERROR",
                )
            ],
        )


def _knowledge_query_flow_explanations_response(
    request: Request,
    body: KnowledgeQueryRequest,
    cancel_event: threading.Event | None = None,
    deadline_at: float | None = None,
):
    config, deps = _state(request)
    request_deadline_seconds = _flow_explanation_request_deadline_seconds(config)
    deadline_at = deadline_at if deadline_at is not None else time.monotonic() + request_deadline_seconds
    if time.monotonic() >= deadline_at:
        return _expired_flow_explanation_response(body)
    try:
        query_result = build_knowledge_query_service(deps.graph_store, config).query_with_flows(body)
        if not tuple(query_result.flows or ()):
            return _public_error_response(
                404,
                "NO_GROUNDED_GRAPH_CANDIDATES",
                "No grounded graph candidates were found.",
            )
        answer_service, close_provider = _human_answer_service(request, config, cancel_event)
        try:
            return answer_service.answer(body, query_result, deadline_at=deadline_at)
        finally:
            if close_provider:
                close_provider()
    except FlowExplanationDeadlineExceeded:
        return _public_error_response(
            504,
            "FLOW_EXPLANATION_TIMEOUT",
            "Knowledge flow explanation timed out.",
        )
    except HumanAnswerGenerationFailed:
        return _public_error_response(
            502,
            "HUMAN_ANSWER_GENERATION_FAILED",
            "The local model could not produce a grounded answer.",
        )
    except Exception:
        return _public_error_response(
            503,
            "KNOWLEDGE_QUERY_FAILED",
            "Knowledge query failed before a factual answer could be built.",
        )


def _knowledge_query_tool_context_response(
    request: Request,
    body: KnowledgeQueryRequest,
):
    config, deps = _state(request)
    try:
        query_result = build_knowledge_query_service(deps.graph_store, config).query_with_flows(body)
        if not tuple(query_result.flows or ()):
            return _public_error_response(
                404,
                "NO_GROUNDED_GRAPH_CANDIDATES",
                "No grounded graph candidates were found.",
            )
        return CompactFlowProjector().to_tool_response(body, query_result)
    except Exception:
        return _public_error_response(
            503,
            "KNOWLEDGE_QUERY_FAILED",
            "Knowledge query failed before tool context could be built.",
        )


def _deadline_exhausted_diagnostic() -> KnowledgeQueryDiagnostic:
    return KnowledgeQueryDiagnostic(
        code=FLOW_EXPLANATION_LIMIT_REACHED,
        message="Flow explanation request deadline was exhausted before flow explanation work could start.",
        severity="WARN",
        metadata={"stage": "BEFORE_QUERY"},
    )


def _expired_flow_explanation_response(body: KnowledgeQueryRequest) -> JSONResponse:
    return _public_error_response(
        504,
        "FLOW_EXPLANATION_TIMEOUT",
        "Knowledge flow explanation timed out.",
    )


def _expired_tool_context_response(body: KnowledgeQueryRequest) -> JSONResponse:
    return _public_error_response(
        504,
        "TOOL_CONTEXT_TIMEOUT",
        "Knowledge tool context timed out.",
    )


def _public_error_response(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status_code, content={"code": code, "message": message})


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


def _flow_explanation_service(
    request: Request,
    config: AppConfig,
    cancel_event: threading.Event | None = None,
) -> tuple[FlowExplanationService, Optional[Any]]:
    injected_provider = getattr(request.app.state, "flow_explanation_provider", None)
    max_prompt_chars = max(
        DEFAULT_GENERATIVE_CONTEXT_TOKENS,
        int(config.analysis_context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS) * 4,
    )
    request_deadline_seconds = _flow_explanation_request_deadline_seconds(config)
    if injected_provider is not None:
        return FlowExplanationService(
            injected_provider,
            max_prompt_chars=max_prompt_chars,
            request_deadline_seconds=request_deadline_seconds,
            cancel_event=cancel_event,
        ), None
    provider = LocalOllamaFlowExplanationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
    )
    return FlowExplanationService(
        provider,
        max_prompt_chars=max_prompt_chars,
        request_deadline_seconds=request_deadline_seconds,
        cancel_event=cancel_event,
    ), provider.close


def _human_answer_service(
    request: Request,
    config: AppConfig,
    cancel_event: threading.Event | None = None,
) -> tuple[HumanFlowAnswerService, Optional[Any]]:
    injected_provider = getattr(request.app.state, "flow_explanation_provider", None)
    max_prompt_chars = max(
        DEFAULT_GENERATIVE_CONTEXT_TOKENS,
        int(config.analysis_context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS) * 4,
    )
    request_deadline_seconds = _flow_explanation_request_deadline_seconds(config)
    if injected_provider is not None:
        return HumanFlowAnswerService(
            injected_provider,
            max_prompt_chars=max_prompt_chars,
            request_deadline_seconds=request_deadline_seconds,
            cancel_event=cancel_event,
        ), None
    provider = LocalOllamaFlowExplanationClient(
        config.analysis_base_url,
        config.analysis_model,
        config.analysis_ai_call_timeout_seconds,
        config.analysis_context_tokens,
        renderer=HumanAnswerPromptRenderer(),
    )
    return HumanFlowAnswerService(
        provider,
        max_prompt_chars=max_prompt_chars,
        request_deadline_seconds=request_deadline_seconds,
        cancel_event=cancel_event,
    ), provider.close


def _flow_explanation_request_deadline_seconds(config: AppConfig) -> float:
    return max(0.001, float(config.flow_explanation_request_timeout_seconds))


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
