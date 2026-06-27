from __future__ import annotations

import asyncio
import sqlite3
import threading
from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, Response

from knowledge_service.analysis_schema import AnalysisBuildRequest, RetryFailedAnalysisRequest
from knowledge_service.analysis_service import AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.bootstrap import KnowledgeDependencies, build_dependencies, configure_logging
from knowledge_service.config import AppConfig, ForgeSettings, load_forge_settings
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_schema import InventoryBuildRequest
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.observability import (
    CORRELATION_HEADER,
    ObservabilityMiddleware,
    current_route_metrics,
    sanitize_correlation_id,
)
from knowledge_service.overview_projection import read_overview
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
        await deps.analysis_supervisor.start_lifespan()
        await deps.inventory_scheduler.start()
        try:
            yield
        finally:
            await deps.inventory_scheduler.stop()
            await deps.analysis_supervisor.shutdown()

    app = FastAPI(title="Knowledge Service", version="0.1.0", lifespan=lifespan)
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
        elif exc.code == "GRAPH_SNAPSHOT_EXPIRED":
            status = 410
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
            "GRAPH_SNAPSHOT_STALE",
            "GRAPH_SNAPSHOT_SOURCE_MISMATCH",
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
        source_config = load_source_config(config.local_config_path)
        if not includeFreshness or source_config is None or not result.get("lastCompletedAt"):
            result["freshness"] = _unknown_freshness()
        else:
            result["freshness"] = KnowledgeFreshnessService(source_config, deps.inventory_store).check()
        return result

    @app.get("/api/v1/knowledge/overview")
    async def overview(request: Request) -> Dict[str, Any]:
        _, deps = _state(request)
        return read_overview(deps.inventory_store.db_path)

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
        return deps.analysis_store.graph_snapshot_metadata(sourceId)

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
        manifest = deps.analysis_store.graph_snapshot_manifest(
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
        if request.client and request.client.host == "testclient":
            return _graph_view_response(
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
        page = deps.analysis_store.graph_snapshot_nodes(
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
        page = deps.analysis_store.graph_snapshot_edges(
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
        detail = deps.analysis_store.graph_snapshot_node_detail(graphRevision, node_id, sourceId, includeEvidence)
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
        detail = deps.analysis_store.graph_snapshot_edge_detail(graphRevision, edge_id, sourceId, includeEvidence)
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
    view = analysis_store.graph_snapshot_view(
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


async def _run_in_thread(func, *args, **kwargs):
    loop = asyncio.get_running_loop()
    future = loop.create_future()

    def worker() -> None:
        try:
            result = func(*args, **kwargs)
        except BaseException as exc:
            loop.call_soon_threadsafe(_set_future_exception, future, exc)
            return
        loop.call_soon_threadsafe(_set_future_result, future, result)

    thread = threading.Thread(target=worker, name="knowledge-graph-view", daemon=True)
    thread.start()
    try:
        return await asyncio.shield(future)
    except asyncio.CancelledError:
        return await future


def _set_future_result(future, result) -> None:
    if not future.cancelled():
        future.set_result(result)


def _set_future_exception(future, exc: BaseException) -> None:
    if not future.cancelled():
        future.set_exception(exc)


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
