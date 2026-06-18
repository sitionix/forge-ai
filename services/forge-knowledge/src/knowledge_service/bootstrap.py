from __future__ import annotations

import logging
from dataclasses import dataclass
from logging.handlers import RotatingFileHandler
from typing import Optional

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_service import AnalysisJobRunner, AnalysisProvider, JobExecutor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig, ForgeSettings
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import BackgroundInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore


@dataclass(frozen=True)
class KnowledgeDependencies:
    inventory_store: InventoryStore
    analysis_store: AnalysisStore
    graph_store: AnalysisStore
    source_resolver: InventoryFileResolver
    analysis_provider: Optional[AnalysisProvider]
    analysis_runner: AnalysisJobRunner
    inventory_refresh: InventoryRefreshService
    inventory_scheduler: BackgroundInventoryScheduler


def build_dependencies(
    config: AppConfig,
    *,
    analysis_provider: Optional[AnalysisProvider] = None,
    job_executor: Optional[JobExecutor] = None,
) -> KnowledgeDependencies:
    inventory_store = InventoryStore(config.store_path)
    analysis_store = AnalysisStore(config.store_path)
    inventory_store.init()
    analysis_store.init()
    analysis_store.mark_interrupted_jobs()
    inventory_refresh = InventoryRefreshService(config, inventory_store)
    inventory_scheduler = BackgroundInventoryScheduler(inventory_refresh, config)
    logger = logging.getLogger("knowledge_service.analysis")
    runner = AnalysisJobRunner(
        inventory_store,
        config,
        analysis_provider=analysis_provider,
        job_executor=job_executor,
        logger=logger,
    )
    return KnowledgeDependencies(
        inventory_store=inventory_store,
        analysis_store=analysis_store,
        graph_store=analysis_store,
        source_resolver=InventoryFileResolver(inventory_store),
        analysis_provider=analysis_provider,
        analysis_runner=runner,
        inventory_refresh=inventory_refresh,
        inventory_scheduler=inventory_scheduler,
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
