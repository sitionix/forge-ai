from __future__ import annotations

from contextlib import asynccontextmanager
from typing import Any, Dict, Optional

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_schema import AnalysisBuildRequest
from knowledge_service.analysis_service import AnalysisJobRunner
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import load_app_config
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.errors import KnowledgeError
from knowledge_service.freshness_service import KnowledgeFreshnessService
from knowledge_service.graph_slice_service import GraphSliceRequest, GraphSliceService
from knowledge_service.inventory_refresh import BackgroundInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.inventory_schema import InventoryBuildRequest
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import load_source_config

app_config = load_app_config()
store = InventoryStore(app_config.store_path)
analysis_runner = AnalysisJobRunner(store, app_config)
inventory_refresh = InventoryRefreshService(app_config, store)
inventory_scheduler = BackgroundInventoryScheduler(inventory_refresh, app_config)
AnalysisStore(app_config.store_path).mark_interrupted_jobs()


@asynccontextmanager
async def lifespan(_: FastAPI):
    get_inventory_scheduler().start()
    try:
        yield
    finally:
        get_inventory_scheduler().stop()


app = FastAPI(title="Knowledge Service", version="0.1.0", lifespan=lifespan)


@app.exception_handler(KnowledgeError)
async def knowledge_error_handler(_: Request, exc: KnowledgeError) -> JSONResponse:
    status = 400
    if exc.code == "KNOWLEDGE_CONFIG_MISSING":
        status = 200
    elif exc.code.endswith("_NOT_FOUND") or exc.code == "SERVICE_CATALOG_NOT_FOUND":
        status = 404
    elif exc.code in {"ANALYSIS_JOB_ALREADY_RUNNING", "INVENTORY_BUILD_ALREADY_RUNNING", "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS"}:
        status = 409
    return JSONResponse(status_code=status, content={"code": exc.code, "message": exc.message})


def get_inventory_refresh() -> InventoryRefreshService:
    global inventory_refresh, inventory_scheduler
    if inventory_refresh.config != app_config or inventory_refresh.store is not store:
        inventory_refresh = InventoryRefreshService(app_config, store)
        inventory_scheduler = BackgroundInventoryScheduler(inventory_refresh, app_config)
    return inventory_refresh


def get_inventory_scheduler() -> BackgroundInventoryScheduler:
    get_inventory_refresh()
    return inventory_scheduler


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "UP"}


@app.get("/api/v1/knowledge/status")
async def status() -> Dict[str, Any]:
    config = load_source_config(app_config.local_config_path)
    inventory = store.status()
    analysis = AnalysisStore(app_config.store_path).status()
    freshness = {"status": "UNKNOWN", "checkedAt": None, "newFiles": 0, "modifiedFiles": 0, "deletedFiles": 0, "affectedScannedFiles": 0}
    if config is not None and analysis.get("lastCompletedAt"):
        freshness = KnowledgeFreshnessService(config, store).check()
    base = {
        "status": "UP",
        "module": "knowledge",
        "catalog": {"configured": config is not None, "type": config.catalog.type if config else None},
        "inventory": {
            "implemented": True,
            "status": inventory.get("status"),
            "lastBuildAt": inventory.get("lastBuildAt"),
            "sourceCount": inventory.get("sourceCount", 0),
            "fileCount": inventory.get("fileCount", 0),
            "skippedCount": inventory.get("skippedCount", 0),
            "skippedBreakdown": inventory.get("skippedBreakdown", {"total": 0, "byReason": {}}),
        },
        "inventoryRefresh": get_inventory_scheduler().status(),
        "coverage": {
            "scannedFiles": analysis.get("scannedFileCount", 0),
            "eligibleFiles": analysis.get("fileCount", 0),
            "completedAt": analysis.get("lastCompletedAt"),
        },
        "freshness": freshness,
    }
    if config is None:
        base["catalog"] = {"configured": False}
        base["message"] = "No local knowledge-sources.yaml configured"
    return base


@app.get("/api/v1/knowledge/sources")
async def sources() -> Dict[str, Any]:
    config = load_source_config(app_config.local_config_path)
    if config is None:
        return {"sources": [], "message": "No local knowledge-sources.yaml configured"}
    result = ServiceYamlCatalogProvider(config).load()
    return {
        "catalog": {"type": config.catalog.type, "configured": True},
        "sources": [source.public_dict() for source in result.sources],
        "diagnostics": [diag.__dict__ for diag in result.diagnostics],
    }


@app.post("/api/v1/knowledge/inventory/build")
async def inventory_build(request: InventoryBuildRequest) -> Dict[str, Any]:
    try:
        return get_inventory_refresh().build(request.sourceIds, request.groups)
    except Exception as exc:
        if isinstance(exc, KnowledgeError):
            raise
        raise KnowledgeError("INVENTORY_BUILD_FAILED", "Inventory build failed") from exc


@app.get("/api/v1/knowledge/inventory/status")
async def inventory_status() -> Dict[str, Any]:
    return store.status()


@app.get("/api/v1/knowledge/inventory/files")
async def inventory_files(
    sourceId: Optional[str] = None,
    pathContains: Optional[str] = None,
    extension: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
) -> Dict[str, Any]:
    return store.files(sourceId, pathContains, extension, limit, offset)


@app.post("/api/v1/knowledge/context")
async def context(request: ContextRequest) -> Dict[str, Any]:
    if not request.query or not request.query.strip():
        raise KnowledgeError("CONTEXT_QUERY_INVALID", "Context query must not be empty")
    try:
        return ContextService(store).context(request)
    except Exception as exc:
        if isinstance(exc, KnowledgeError):
            raise
        raise KnowledgeError("CONTEXT_BUILD_FAILED", "Context build failed") from exc


@app.post("/api/v1/knowledge/analysis/build")
async def analysis_build(request: AnalysisBuildRequest) -> Dict[str, Any]:
    request = request or AnalysisBuildRequest()
    try:
        return get_inventory_refresh().build_then(
            request.sourceIds,
            request.groups,
            lambda: analysis_runner.start(request),
        )
    except Exception as exc:
        if isinstance(exc, KnowledgeError):
            raise
        raise KnowledgeError("ANALYSIS_BUILD_FAILED", "Analysis build failed") from exc


@app.get("/api/v1/knowledge/analysis/jobs/{job_id}")
async def analysis_job(job_id: str) -> Dict[str, Any]:
    job = AnalysisStore(app_config.store_path).job(job_id)
    if job is None:
        raise KnowledgeError("ANALYSIS_JOB_NOT_FOUND", "Analysis job not found")
    return job


@app.post("/api/v1/knowledge/analysis/jobs/{job_id}/stop")
async def analysis_job_stop(job_id: str) -> Dict[str, Any]:
    return analysis_runner.stop(job_id)


@app.get("/api/v1/knowledge/analysis/status")
async def analysis_status() -> Dict[str, Any]:
    result = AnalysisStore(app_config.store_path).status()
    config = load_source_config(app_config.local_config_path)
    if config is None or not result.get("lastCompletedAt"):
        result["freshness"] = {"status": "UNKNOWN", "checkedAt": None, "newFiles": 0, "modifiedFiles": 0, "deletedFiles": 0, "affectedScannedFiles": 0}
    else:
        result["freshness"] = KnowledgeFreshnessService(config, store).check()
    return result


@app.get("/api/v1/knowledge/services/status")
async def services_status() -> Dict[str, Any]:
    analysis_store = AnalysisStore(app_config.store_path)
    config = load_source_config(app_config.local_config_path)
    catalog_result = ServiceYamlCatalogProvider(config).load() if config is not None else None
    return analysis_store.service_status(
        catalog_result.sources if catalog_result is not None else None,
        OllamaAnalysisClient.name,
        OllamaAnalysisClient.version,
        store.status(),
    )


@app.get("/api/v1/knowledge/analysis/files")
async def analysis_files(
    sourceId: Optional[str] = None,
    status: Optional[str] = None,
    pathContains: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
) -> Dict[str, Any]:
    return AnalysisStore(app_config.store_path).files(sourceId, status, pathContains, limit, offset)


@app.get("/api/v1/knowledge/analysis/symbols")
async def analysis_symbols(
    sourceId: Optional[str] = None,
    role: Optional[str] = None,
    kind: Optional[str] = None,
    pathContains: Optional[str] = None,
    nameContains: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
) -> Dict[str, Any]:
    return AnalysisStore(app_config.store_path).symbols(sourceId, role, kind, pathContains, nameContains, limit, offset)


@app.get("/api/v1/knowledge/analysis/relations")
async def analysis_relations(
    sourceId: Optional[str] = None,
    relation: Optional[str] = None,
    fromSymbolId: Optional[str] = None,
    toSymbolId: Optional[str] = None,
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
) -> Dict[str, Any]:
    return AnalysisStore(app_config.store_path).relations(sourceId, relation, fromSymbolId, toSymbolId, limit, offset)


@app.get("/api/v1/knowledge/analysis/graph")
async def analysis_graph(
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
    return AnalysisStore(app_config.store_path).graph(
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
    return GraphSliceService(AnalysisStore(app_config.store_path)).slice(GraphSliceRequest(
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
    ))


def _csv_set(value: Optional[str]) -> Optional[set[str]]:
    if not value:
        return None
    return {item.strip() for item in value.split(",") if item.strip()}
