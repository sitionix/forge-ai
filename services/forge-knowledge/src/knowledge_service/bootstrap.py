from __future__ import annotations

import logging
from dataclasses import dataclass
from logging.handlers import RotatingFileHandler
from typing import Any, Optional

from knowledge_service.ai_runtime_discovery import (
    AiRuntimeDiscoveryRegistry,
    AiRuntimeDiscoveryService,
    CodexAiRuntimeOptionsSource,
    CodexAppServerClient,
    OllamaAiRuntimeOptionsSource,
)
from knowledge_service.analysis_client import ProviderBackedAnalysisClient
from knowledge_service.analysis_service import AnalysisProvider, AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig, ForgeSettings
from knowledge_service.generative_runtime import GenerativeProviderRegistry, OllamaGenerativeProvider
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
    ai_runtime_discovery: AiRuntimeDiscoveryService | None = None
    generative_registry: Optional[GenerativeProviderRegistry] = None
    generative_provider: Any | None = None

    async def aclose(self) -> None:
        if self.ai_runtime_discovery is not None:
            await self.ai_runtime_discovery.aclose()
        if self.generative_registry is not None:
            await self.generative_registry.aclose()


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
    generative_registry, generative_provider = build_generative_runtime(config)
    if analysis_provider is None:
        analysis_provider = ProviderBackedAnalysisClient(
            generative_provider,
            config.analysis_model,
            min(config.analysis_ai_call_timeout_seconds, config.analysis_per_file_timeout_seconds),
            config.analysis_context_tokens,
        )
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
        ai_runtime_discovery=build_ai_runtime_discovery(config),
        generative_registry=generative_registry,
        generative_provider=generative_provider,
    )


def build_generative_runtime(config: AppConfig) -> tuple[GenerativeProviderRegistry, Any]:
    provider = OllamaGenerativeProvider(
        config.analysis_base_url,
        timeout_seconds=config.analysis_ai_call_timeout_seconds,
    )
    registry = GenerativeProviderRegistry()
    registry.register(provider)
    return registry, registry.resolve(config.analysis_provider)


def build_ai_runtime_discovery(config: AppConfig) -> AiRuntimeDiscoveryService:
    timeout_seconds = min(float(config.analysis_ai_call_timeout_seconds), 5.0)
    registry = AiRuntimeDiscoveryRegistry()
    registry.register(
        OllamaAiRuntimeOptionsSource(
            config.analysis_base_url,
            timeout_seconds=timeout_seconds,
        )
    )
    registry.register(
        CodexAiRuntimeOptionsSource(
            CodexAppServerClient(
                client_name="forge-knowledge",
                client_version="0.1.0",
                request_timeout_seconds=timeout_seconds,
            )
        )
    )
    return AiRuntimeDiscoveryService(registry, provider_timeout_seconds=timeout_seconds + 1.0)


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
    return ProviderBackedAnalysisClient.name, ProviderBackedAnalysisClient.version
