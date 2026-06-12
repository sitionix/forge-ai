from __future__ import annotations

from typing import Any, Dict, Optional

from fastapi import FastAPI, Query, Request
from fastapi.responses import JSONResponse

from knowledge_service.config import load_app_config
from knowledge_service.context_schema import ContextRequest
from knowledge_service.context_service import ContextService
from knowledge_service.errors import ConfigMissingError, KnowledgeError
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.search_schema import InventoryBuildRequest, SearchRequest
from knowledge_service.search_service import SearchService
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import load_source_config, require_source_config

app_config = load_app_config()
store = InventoryStore(app_config.store_path)
app = FastAPI(title="Knowledge Service", version="0.1.0")


@app.exception_handler(KnowledgeError)
async def knowledge_error_handler(_: Request, exc: KnowledgeError) -> JSONResponse:
    status = 404 if exc.code == "SERVICE_CATALOG_NOT_FOUND" else 400
    if exc.code == "KNOWLEDGE_CONFIG_MISSING":
        status = 200
    return JSONResponse(status_code=status, content={"code": exc.code, "message": exc.message})


@app.get("/health")
async def health() -> Dict[str, str]:
    return {"status": "UP"}


@app.get("/api/v1/knowledge/status")
async def status() -> Dict[str, Any]:
    config = load_source_config(app_config.local_config_path)
    inventory = store.status()
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
        "search": {"implemented": True, "mode": "keyword"},
        "vectorStore": {"implemented": False, "enabled": False},
        "rag": {"implemented": False, "enabled": False},
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
    config = require_source_config(app_config.local_config_path)
    try:
        return InventoryBuilder(config, store).build(request.sourceIds, request.groups)
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


@app.post("/api/v1/knowledge/search")
async def search(request: SearchRequest) -> Dict[str, Any]:
    if not request.query or not request.query.strip():
        raise KnowledgeError("SEARCH_QUERY_INVALID", "Search query must not be empty")
    return SearchService(store).search(request.query, request.sourceIds, request.groups, max(1, min(request.limit, 100)))


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
