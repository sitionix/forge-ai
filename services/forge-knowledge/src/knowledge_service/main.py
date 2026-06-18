from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse, Response

from knowledge_service.analysis_schema import AnalysisBuildRequest
from knowledge_service.analysis_service import AnalysisJobRunner
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.bootstrap import KnowledgeDependencies, analyzer_identity, build_dependencies, configure_logging
from knowledge_service.config import AppConfig, ForgeSettings, load_forge_settings
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.graph_slice_service import GraphSliceRequest, GraphSliceService
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import BackgroundInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_schema import InventoryBuildRequest
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import load_source_config
from knowledge_service.structural_analysis import GRAPH_ENGINE_VERSION

app_config: Optional[AppConfig] = None
store: Optional[InventoryStore] = None
analysis_runner: Optional[AnalysisJobRunner] = None


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
        deps.inventory_scheduler.start()
        try:
            yield
        finally:
            deps.inventory_scheduler.stop()

    app = FastAPI(title="Knowledge Service", version="0.1.0", lifespan=lifespan)
    if settings is not None and dependencies is not None:
        app.state.forge_settings = settings
        app.state.app_config = AppConfig.from_forge_settings(settings)
        app.state.knowledge_dependencies = dependencies

    @app.exception_handler(KnowledgeError)
    async def knowledge_error_handler(_: Request, exc: KnowledgeError) -> JSONResponse:
        status = 400
        if exc.code == "KNOWLEDGE_CONFIG_MISSING":
            status = 200
        elif exc.code.endswith("_NOT_FOUND") or exc.code == "SERVICE_CATALOG_NOT_FOUND":
            status = 404
        elif exc.code in {
            "ANALYSIS_JOB_ALREADY_RUNNING",
            "INVENTORY_BUILD_ALREADY_RUNNING",
            "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS",
            "GRAPH_SNAPSHOT_STALE",
        }:
            status = 409
        return JSONResponse(status_code=status, content={"code": exc.code, "message": exc.message})

    @app.get("/health")
    async def health() -> Dict[str, str]:
        return {"status": "UP"}

    @app.get("/api/v1/knowledge/status")
    async def status(request: Request) -> Dict[str, Any]:
        config, deps = _state(request)
        source_config = load_source_config(config.local_config_path)
        inventory = deps.inventory_store.status()
        analysis = deps.analysis_store.status()
        freshness = _unknown_freshness()
        if source_config is not None and analysis.get("lastCompletedAt"):
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
            "inventoryRefresh": deps.inventory_scheduler.status(),
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
            return deps.inventory_refresh.build(body.sourceIds, body.groups)
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
            return deps.inventory_refresh.build_then(
                body.sourceIds,
                body.groups,
                lambda: deps.analysis_runner.start(body),
            )
        except Exception as exc:
            if isinstance(exc, KnowledgeError):
                raise
            raise KnowledgeError("ANALYSIS_BUILD_FAILED", "Analysis build failed") from exc

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
        return deps.analysis_runner.stop(job_id)

    @app.get("/api/v1/knowledge/analysis/status")
    async def analysis_status(request: Request) -> Dict[str, Any]:
        config, deps = _state(request)
        result = deps.analysis_store.status()
        source_config = load_source_config(config.local_config_path)
        if source_config is None or not result.get("lastCompletedAt"):
            result["freshness"] = _unknown_freshness()
        else:
            result["freshness"] = KnowledgeFreshnessService(source_config, deps.inventory_store).check()
        return result

    @app.get("/api/v1/knowledge/services/status")
    async def services_status(request: Request) -> Dict[str, Any]:
        config, deps = _state(request)
        source_config = load_source_config(config.local_config_path)
        catalog_result = ServiceYamlCatalogProvider(source_config).load() if source_config is not None else None
        analyzer_name, analyzer_version = analyzer_identity(deps)
        return deps.analysis_store.service_status(
            catalog_result.sources if catalog_result is not None else None,
            analyzer_name,
            analyzer_version,
            GRAPH_ENGINE_VERSION,
            deps.inventory_store.status(),
        )

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

    @app.get("/api/v1/knowledge/analysis/symbols")
    async def analysis_symbols(
        request: Request,
        sourceId: Optional[str] = None,
        role: Optional[str] = None,
        kind: Optional[str] = None,
        pathContains: Optional[str] = None,
        nameContains: Optional[str] = None,
        limit: int = Query(100, ge=1, le=1000),
        offset: int = Query(0, ge=0),
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.analysis_store.symbols(sourceId, role, kind, pathContains, nameContains, limit, offset)

    @app.get("/api/v1/knowledge/analysis/relations")
    async def analysis_relations(
        request: Request,
        sourceId: Optional[str] = None,
        relation: Optional[str] = None,
        fromSymbolId: Optional[str] = None,
        toSymbolId: Optional[str] = None,
        limit: int = Query(100, ge=1, le=1000),
        offset: int = Query(0, ge=0),
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.analysis_store.relations(sourceId, relation, fromSymbolId, toSymbolId, limit, offset)

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
    ) -> JSONResponse:
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
        )
        etag = manifest["etag"]
        headers = {
            "ETag": etag,
            "X-Graph-Revision": manifest["graphRevision"],
            "Cache-Control": "private, no-cache",
            "Server-Timing": "db;dur=0, projection;dur=0, serialization;dur=0",
        }
        if request.headers.get("if-none-match") == etag:
            return Response(status_code=304, headers=headers)
        return JSONResponse(content=manifest, headers=headers)

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
        )
        return JSONResponse(
            content=page,
            headers={
                "X-Graph-Revision": graphRevision,
                "Cache-Control": "private, no-cache",
                "Server-Timing": "db;dur=0, projection;dur=0, serialization;dur=0",
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
        )
        return JSONResponse(
            content=page,
            headers={
                "X-Graph-Revision": graphRevision,
                "Cache-Control": "private, no-cache",
                "Server-Timing": "db;dur=0, projection;dur=0, serialization;dur=0",
            },
        )

    @app.get("/api/v1/knowledge/analysis/graph")
    async def analysis_graph(
        request: Request,
        sourceId: Optional[str] = None,
        graphNodeId: Optional[str] = None,
        graphEdgeId: Optional[str] = None,
        inventoryFileId: Optional[str] = None,
        flowDomain: Optional[str] = None,
        factOrigin: Optional[str] = None,
        nodeKind: Optional[str] = None,
        edgeType: Optional[str] = None,
        depth: int = Query(2, ge=0, le=4),
        limit: int = Query(150, ge=0),
        includeEvidence: bool = False,
        includeClaims: bool = True,
        includeDiagnostics: bool = True,
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return deps.analysis_store.graph(
            sourceId,
            graphNodeId,
            graphEdgeId,
            inventoryFileId,
            flowDomain,
            factOrigin,
            nodeKind,
            edgeType,
            depth,
            limit,
            includeEvidence,
            includeDiagnostics,
            includeClaims,
        )

    @app.get("/api/v1/knowledge/analysis/graph/slice")
    async def analysis_graph_slice(
        request: Request,
        sourceId: Optional[str] = None,
        rootGraphNodeId: Optional[str] = None,
        stableKey: Optional[str] = None,
        flowDomain: str = "CODE",
        direction: str = "OUTBOUND",
        depth: int = Query(2, ge=0, le=4),
        maxNodes: int = Query(80, ge=0),
        maxEdges: int = Query(120, ge=0),
        includeExternal: str = "collapsed",
        includeUnresolved: bool = True,
        includeTests: bool = False,
        includeWorkflow: bool = False,
        edgeTypes: Optional[str] = None,
        nodeKinds: Optional[str] = None,
        includeEvidence: bool = False,
        includeClaims: bool = True,
        includeIsolated: bool = False,
    ) -> Dict[str, Any]:
        _, deps = _state(request)
        return GraphSliceService(deps.graph_store).slice(
            GraphSliceRequest(
                source_id=sourceId,
                root_graph_node_id=rootGraphNodeId,
                stable_key=stableKey,
                flow_domain=flowDomain,
                direction=direction,
                depth=depth,
                max_nodes=maxNodes,
                max_edges=maxEdges,
                include_external=includeExternal,
                include_unresolved=includeUnresolved,
                include_tests=includeTests,
                include_workflow=includeWorkflow,
                edge_types=_csv_set(edgeTypes),
                node_kinds=_csv_set(nodeKinds),
                include_evidence=includeEvidence,
                include_claims=includeClaims,
                include_isolated=includeIsolated,
            )
        )

    return app


def _state(request: Request) -> tuple[AppConfig, KnowledgeDependencies]:
    if hasattr(request.app.state, "app_config") and hasattr(request.app.state, "knowledge_dependencies"):
        return request.app.state.app_config, request.app.state.knowledge_dependencies
    if app_config is not None and store is not None:
        analysis_store = AnalysisStore(store.db_path)
        runner = analysis_runner or AnalysisJobRunner(store, app_config)
        refresh = InventoryRefreshService(app_config, store)
        scheduler = BackgroundInventoryScheduler(refresh, app_config)
        return app_config, KnowledgeDependencies(
            inventory_store=store,
            analysis_store=analysis_store,
            graph_store=analysis_store,
            source_resolver=InventoryFileResolver(store),
            analysis_provider=None,
            analysis_runner=runner,
            inventory_refresh=refresh,
            inventory_scheduler=scheduler,
        )
    raise RuntimeError("Knowledge app dependencies are not initialized")


def _csv_set(value: Optional[str]) -> Optional[set[str]]:
    if not value:
        return None
    return {item.strip() for item in value.split(",") if item.strip()}


def _unknown_freshness() -> Dict[str, Any]:
    return {
        "status": "UNKNOWN",
        "checkedAt": None,
        "newFiles": 0,
        "modifiedFiles": 0,
        "deletedFiles": 0,
        "affectedScannedFiles": 0,
    }


app = create_app()
