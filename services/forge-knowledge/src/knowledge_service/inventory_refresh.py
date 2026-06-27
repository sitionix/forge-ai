from __future__ import annotations

import logging
import asyncio
import threading
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.config import AppConfig
from knowledge_service.errors import KnowledgeError
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider
from knowledge_service.source_config import SourceConfig, require_source_config


LOGGER = logging.getLogger(__name__)


class InventoryRefreshService:
    def __init__(self, config: AppConfig, store: InventoryStore):
        self.config = config
        self.store = store
        self._lock = threading.Lock()

    def build(
        self,
        source_ids: Optional[List[str]] = None,
        groups: Optional[List[str]] = None,
        *,
        wait: bool = True,
        block_if_analysis_active: bool = True,
    ) -> Dict[str, Any]:
        return asyncio.run(self.build_async(source_ids, groups, wait=wait, block_if_analysis_active=block_if_analysis_active))

    async def build_async(
        self,
        source_ids: Optional[List[str]] = None,
        groups: Optional[List[str]] = None,
        *,
        wait: bool = True,
        block_if_analysis_active: bool = True,
    ) -> Dict[str, Any]:
        return await self._with_lock(
            lambda source_config: self._build_locked(source_config, source_ids or [], groups or []),
            wait=wait,
            block_if_analysis_active=block_if_analysis_active,
        )

    async def build_then(
        self,
        source_ids: Optional[List[str]],
        groups: Optional[List[str]],
        callback: Callable[[], Dict[str, Any]],
    ) -> Dict[str, Any]:
        async def run(source_config: SourceConfig) -> Dict[str, Any]:
            self._build_locked(source_config, source_ids or [], groups or [])
            result = callback()
            if asyncio.iscoroutine(result):
                return await result
            return result

        return await self._with_lock(run, wait=True, block_if_analysis_active=True)

    async def build_available_while_analysis_runs(self, *, wait: bool = False) -> Dict[str, Any]:
        def run(source_config: SourceConfig) -> Dict[str, Any]:
            active_source_ids = self._active_analysis_source_ids()
            if active_source_ids is None:
                raise KnowledgeError(
                    "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS",
                    "Inventory refresh is blocked because active AI analysis source scope is not known",
                )
            if not active_source_ids:
                return self._build_locked(source_config, [], [])
            catalog = ServiceYamlCatalogProvider(source_config).load()
            known_source_ids = {source.sourceId for source in catalog.sources}
            known_source_ids.update(self.store.source_ids())
            refresh_source_ids = sorted(known_source_ids - active_source_ids)
            if not refresh_source_ids:
                raise KnowledgeError(
                    "INVENTORY_REFRESH_NO_AVAILABLE_SOURCES",
                    "No inventory sources are available while AI analysis is running",
                )
            return self._build_locked(source_config, refresh_source_ids, [])

        return await self._with_lock(run, wait=wait, block_if_analysis_active=False)

    async def _with_lock(
        self,
        action: Callable[[SourceConfig], Dict[str, Any]],
        *,
        wait: bool,
        block_if_analysis_active: bool,
    ) -> Dict[str, Any]:
        acquired = self._lock.acquire(blocking=wait)
        if not acquired:
            raise KnowledgeError("INVENTORY_BUILD_ALREADY_RUNNING", "Inventory build is already running")
        try:
            if block_if_analysis_active and AnalysisStore(self.store.db_path).active_job() is not None:
                raise KnowledgeError("INVENTORY_BUILD_BLOCKED_BY_ANALYSIS", "Inventory refresh is blocked while AI analysis is running")
            source_config = require_source_config(self.config.local_config_path)
            result = action(source_config)
            if asyncio.iscoroutine(result):
                return await result
            return result
        finally:
            self._lock.release()

    def _build_locked(self, source_config: SourceConfig, source_ids: List[str], groups: List[str]) -> Dict[str, Any]:
        result = InventoryBuilder(source_config, self.store).build(source_ids, groups)
        AnalysisStore(self.store.db_path).cleanup_stale_files(self._cleanup_source_scope(source_config, source_ids, groups))
        return result

    def _active_analysis_source_ids(self) -> Optional[set[str]]:
        active_job = AnalysisStore(self.store.db_path).active_job()
        if active_job is None:
            return set()
        source_ids = active_job.get("sourceIds") or []
        if not source_ids:
            return None
        return set(source_ids)

    def _cleanup_source_scope(self, source_config: SourceConfig, source_ids: List[str], groups: List[str]) -> Optional[List[str]]:
        if source_ids:
            return sorted(set(source_ids))
        if groups:
            catalog = ServiceYamlCatalogProvider(source_config).load()
            return sorted(source.sourceId for source in catalog.sources if source.group in groups)
        return None


class AsyncInventoryScheduler:
    def __init__(self, refresh: InventoryRefreshService, config: AppConfig):
        self.refresh = refresh
        self.config = config
        self._stop: Optional[asyncio.Event] = None
        self._state_lock = threading.Lock()
        self._task: Optional[asyncio.Task[None]] = None
        self._state: Dict[str, Any] = {
            "enabled": config.inventory_auto_refresh_enabled,
            "intervalSeconds": config.inventory_auto_refresh_interval_seconds,
            "status": "IDLE" if config.inventory_auto_refresh_enabled else "DISABLED",
            "lastStartedAt": None,
            "lastCompletedAt": None,
            "lastErrorCode": None,
            "lastErrorMessage": None,
            "runCount": 0,
            "skipCount": 0,
        }

    async def start(self) -> None:
        if not self.config.inventory_auto_refresh_enabled:
            return
        if self._task is not None and not self._task.done():
            return
        self._stop = asyncio.Event()
        self._task = asyncio.create_task(self._loop(), name="knowledge-inventory-refresh")

    async def stop(self) -> None:
        if self._stop is not None:
            self._stop.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await asyncio.wait_for(self._task, timeout=5)
            except (asyncio.CancelledError, asyncio.TimeoutError):
                pass
            self._task = None

    async def run_once(self) -> Dict[str, Any]:
        if not self.config.inventory_auto_refresh_enabled:
            await self._set_state(status="DISABLED")
            return await self.status()
        started_at = self._now()
        await self._set_state(status="RUNNING", lastStartedAt=started_at, lastErrorCode=None, lastErrorMessage=None)
        try:
            await self.refresh.build_available_while_analysis_runs(wait=False)
            await self._set_state(status="READY", lastCompletedAt=self._now(), increment="runCount")
        except KnowledgeError as exc:
            if exc.code in {
                "INVENTORY_BUILD_BLOCKED_BY_ANALYSIS",
                "INVENTORY_BUILD_ALREADY_RUNNING",
                "INVENTORY_REFRESH_NO_AVAILABLE_SOURCES",
                "KNOWLEDGE_CONFIG_MISSING",
            }:
                await self._set_state(
                    status="SKIPPED",
                    lastCompletedAt=self._now(),
                    lastErrorCode=exc.code,
                    lastErrorMessage=exc.message,
                    increment="skipCount",
                )
            else:
                await self._set_state(
                    status="FAILED",
                    lastCompletedAt=self._now(),
                    lastErrorCode=exc.code,
                    lastErrorMessage=exc.message,
                )
                LOGGER.warning("Background inventory refresh failed: %s", exc.message)
        except Exception as exc:
            await self._set_state(
                status="FAILED",
                lastCompletedAt=self._now(),
                lastErrorCode="INVENTORY_REFRESH_FAILED",
                lastErrorMessage=str(exc),
            )
            LOGGER.exception("Background inventory refresh failed")
        return await self.status()

    async def status_async(self) -> Dict[str, Any]:
        return await self.status()

    async def status(self) -> Dict[str, Any]:
        with self._state_lock:
            return dict(self._state)

    async def _loop(self) -> None:
        while self._stop is not None and not self._stop.is_set():
            await self.run_once()
            try:
                if self._stop is None:
                    return
                await asyncio.wait_for(self._stop.wait(), timeout=self.config.inventory_auto_refresh_interval_seconds)
                return
            except asyncio.TimeoutError:
                continue

    async def _set_state(self, **updates: Any) -> None:
        increment = updates.pop("increment", None)
        with self._state_lock:
            self._state.update(updates)
            if increment:
                self._state[increment] = int(self._state.get(increment) or 0) + 1

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat()
