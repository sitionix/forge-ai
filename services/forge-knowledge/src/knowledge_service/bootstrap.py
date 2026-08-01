from __future__ import annotations

import logging
from dataclasses import dataclass, field
from logging.handlers import RotatingFileHandler
from typing import Any

from knowledge_service import __application_name__, __version__
from knowledge_service.active_profile import (
    ActiveLlmRuntime,
    ActiveProfileService,
    ActiveProfileStore,
    ActiveRuntimeGenerativeProvider,
    LlmUsageRegistry,
)
from knowledge_service.ai_runtime_discovery import (
    AiRuntimeDiscoveryRegistry,
    AiRuntimeDiscoveryService,
    CodexAiRuntimeOptionsSource,
    OllamaAiRuntimeOptionsSource,
)
from knowledge_service.analysis_client import ProviderBackedAnalysisClient
from knowledge_service.analysis_service import AnalysisProvider, AnalysisSupervisor
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.codex_app_server import CodexAppServerClient, CodexNotificationBufferPolicy, CodexRuntimeSettings
from knowledge_service.codex_usage import CodexLlmUsageSource
from knowledge_service.config import AppConfig, ForgeSettings
from knowledge_service.generative_runtime import CodexGenerativeProvider, GenerativeProviderRegistry, OllamaGenerativeProvider
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_refresh import AsyncInventoryScheduler, InventoryRefreshService
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.storage_operations import RetentionPolicy, StorageOperations


@dataclass
class KnowledgeDependencies:
    inventory_store: InventoryStore
    analysis_store: AnalysisStore
    graph_store: AnalysisStore
    source_resolver: InventoryFileResolver
    analysis_provider: AnalysisProvider | None
    analysis_supervisor: AnalysisSupervisor
    inventory_refresh: InventoryRefreshService
    inventory_scheduler: AsyncInventoryScheduler
    storage_operations: StorageOperations
    ai_runtime_discovery: AiRuntimeDiscoveryService | None = None
    active_profile_service: ActiveProfileService | None = None
    active_llm_runtime: ActiveLlmRuntime | None = None
    generative_registry: GenerativeProviderRegistry | None = None
    generative_provider: Any | None = None
    codex_app_server_client: CodexAppServerClient | None = None
    _codex_app_server_client_closed: bool = field(default=False, init=False, repr=False)

    async def aclose(self) -> None:
        if self.ai_runtime_discovery is not None:
            await self.ai_runtime_discovery.aclose()
        if self.generative_registry is not None:
            await self.generative_registry.aclose()
        if self.codex_app_server_client is not None and not self._codex_app_server_client_closed:
            self._codex_app_server_client_closed = True
            await self.codex_app_server_client.aclose()


def build_dependencies(
    config: AppConfig,
    *,
    analysis_provider: AnalysisProvider | None = None,
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
    codex_client = CodexAppServerClient(settings=codex_runtime_settings(config))
    generative_registry, _startup_generative_provider = build_generative_runtime(config, codex_client=codex_client)
    active_profile_store = ActiveProfileStore(config.store_path)
    active_profile = active_profile_store.init(provider_id=config.analysis_provider, model_id=config.analysis_model)
    active_llm_runtime = ActiveLlmRuntime(generative_registry, active_profile)
    generative_provider = ActiveRuntimeGenerativeProvider(active_llm_runtime)
    ai_runtime_discovery = build_ai_runtime_discovery(config, codex_client=codex_client)
    usage_registry = LlmUsageRegistry()
    usage_registry.register(CodexLlmUsageSource(codex_client))
    active_profile_service = ActiveProfileService(
        active_profile_store,
        active_llm_runtime,
        ai_runtime_discovery,
        usage_registry,
    )
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
        ai_runtime_discovery=ai_runtime_discovery,
        active_profile_service=active_profile_service,
        active_llm_runtime=active_llm_runtime,
        generative_registry=generative_registry,
        generative_provider=generative_provider,
        codex_app_server_client=codex_client,
    )


def build_generative_runtime(config: AppConfig, *, codex_client: CodexAppServerClient) -> tuple[GenerativeProviderRegistry, Any]:
    ollama_provider = OllamaGenerativeProvider(
        config.analysis_base_url,
        timeout_seconds=config.analysis_ai_call_timeout_seconds,
    )
    codex_provider = CodexGenerativeProvider(
        codex_client,
        timeout_seconds=config.analysis_ai_call_timeout_seconds,
    )
    registry = GenerativeProviderRegistry()
    registry.register(ollama_provider)
    registry.register(codex_provider)
    return registry, registry.resolve(config.analysis_provider)


def build_ai_runtime_discovery(config: AppConfig, *, codex_client: CodexAppServerClient) -> AiRuntimeDiscoveryService:
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
            codex_client,
        ),
    )
    return AiRuntimeDiscoveryService(registry, provider_timeout_seconds=timeout_seconds + 1.0)


def codex_runtime_settings(config: AppConfig) -> CodexRuntimeSettings:
    timeout_seconds = min(float(config.analysis_ai_call_timeout_seconds), 5.0)
    runtime_dir = config.codex_app_server_runtime_dir
    if runtime_dir is None:
        raise ValueError("Codex app-server runtime directory must be configured")
    return CodexRuntimeSettings(
        command=tuple(config.codex_app_server_command),
        runtime_cwd=runtime_dir,
        client_name=__application_name__,
        client_version=__version__,
        request_timeout_seconds=timeout_seconds,
        interrupt_grace_seconds=config.codex_interrupt_grace_seconds,
        terminal_after_interrupt_seconds=config.codex_terminal_after_interrupt_seconds,
        terminate_grace_seconds=config.codex_terminate_grace_seconds,
        kill_grace_seconds=config.codex_kill_grace_seconds,
        sync_close_timeout_seconds=config.codex_sync_close_timeout_seconds,
        loop_thread_join_timeout_seconds=config.codex_loop_thread_join_timeout_seconds,
        cancellation_cleanup_timeout_seconds=config.codex_cancellation_cleanup_timeout_seconds,
        notification_buffer=CodexNotificationBufferPolicy(
            max_per_turn=config.codex_max_buffered_notifications_per_turn,
            max_turn_ids=config.codex_max_buffered_turn_ids,
            max_age_seconds=config.codex_buffer_ttl_seconds,
        ),
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
    logger.__dict__["_forge_configured"] = True
    logging.getLogger("knowledge_service.analysis").setLevel(settings.logging.level)


def analyzer_identity(dependencies: KnowledgeDependencies) -> tuple[str, str]:
    provider = dependencies.analysis_provider
    if provider is not None:
        return provider.name, provider.version
    return ProviderBackedAnalysisClient.name, ProviderBackedAnalysisClient.version
