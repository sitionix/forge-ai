from __future__ import annotations

import logging
from dataclasses import dataclass
from logging.handlers import RotatingFileHandler
from typing import Optional

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_service import AnalysisProvider, AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig, ForgeSettings
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.storage_operations import StorageOperations
from knowledge_service.storage_operations import RetentionPolicy


@dataclass(frozen=True)
class KnowledgeDependencies:
    inventory_store: InventoryStore
    analysis_store: AnalysisStore
    graph_store: AnalysisStore
    source_resolver: InventoryFileResolver
    analysis_provider: Optional[AnalysisProvider]
    analysis_supervisor: AnalysisSupervisor
    inventory_refresh: InventoryRefreshService
    inventory_scheduler: AsyncInventoryScheduler
    storage_operations: StorageOperations


def build_dependencies(
    config: AppConfig,
    *,
    analysis_provider: Optional[AnalysisProvider] = None,
) -> KnowledgeDependencies:
    inventory_store = InventoryStore(config.store_path)
    analysis_store = AnalysisStore(config.store_path)
    inventory_store.init()
    analysis_store.init()
    storage_operations = StorageOperations(
        config.store_path,
        RetentionPolicy(
            inventory_build_days=config.retention_inventory_build_days,
            analysis_job_days=config.retention_analysis_job_days,
            analysis_diagnostic_days=config.retention_analysis_diagnostic_days,
            keep_completed_jobs=config.retention_keep_completed_jobs,
        ),
    )
    if config.startup_maintenance_enabled:
        storage_operations.startup_maintenance()
        if config.analysis_enabled:
            analysis_store.mark_interrupted_jobs()
    inventory_refresh = InventoryRefreshService(config, inventory_store)
    inventory_scheduler = AsyncInventoryScheduler(inventory_refresh, config)
    logger = logging.getLogger("knowledge_service.analysis")
    supervisor = AnalysisSupervisor(
        inventory_store,
        config,
        analysis_provider=analysis_provider,
        logger=logger,
    )
    return KnowledgeDependencies(
        inventory_store=inventory_store,
        analysis_store=analysis_store,
        graph_store=analysis_store,
        source_resolver=InventoryFileResolver(inventory_store),
        analysis_provider=analysis_provider,
        analysis_supervisor=supervisor,
        inventory_refresh=inventory_refresh,
        inventory_scheduler=inventory_scheduler,
        storage_operations=storage_operations,
    )


def configure_logging(settings: ForgeSettings) -> None:
    logger = logging.getLogger("knowledge_service")
    logger.setLevel(settings.logging.level)
    logger.propagate = False
    if getattr(logger, "_forge_configured", False):
        return
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
    if settings.logging.console_enabled:
        stream_handler = logging.StreamHandler()
        stream_handler.setFormatter(formatter)
        logger.addHandler(stream_handler)
    if settings.logging.file_enabled:
        settings.logging.directory.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(
            settings.logging.directory / "knowledge-service.log",
            maxBytes=1_000_000,
            backupCount=3,
        )
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    setattr(logger, "_forge_configured", True)
    logging.getLogger("knowledge_service.analysis").setLevel(settings.logging.level)


def analyzer_identity(dependencies: KnowledgeDependencies) -> tuple[str, str]:
    provider = dependencies.analysis_provider
    if provider is not None:
        return provider.name, provider.version
    return OllamaAnalysisClient.name, OllamaAnalysisClient.version
