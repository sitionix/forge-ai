from __future__ import annotations

import logging
import threading
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Optional, Sequence

from knowledge_service.semantic_builder import SemanticBuildRunResult, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStatus, SemanticIndexStore


logger = logging.getLogger(__name__)


BuilderFactory = Callable[[], SemanticIndexBuilder]


@dataclass(frozen=True)
class SemanticWorkerTickResult:
    status: str
    selected_source_ids: list[str] = field(default_factory=list)
    build_result: Optional[SemanticBuildRunResult] = None
    diagnostics: list[dict[str, object]] = field(default_factory=list)


class SemanticBuildCoordinator:
    def __init__(self, db_path: Path, lock: threading.Lock, builder_factory: BuilderFactory) -> None:
        self.db_path = Path(db_path)
        self.lock = lock
        self._builder_factory = builder_factory

    def acquire(self, *, blocking: bool) -> bool:
        return self.lock.acquire(blocking=blocking)

    def release(self) -> None:
        self.lock.release()

    def build_locked(
        self,
        source_ids: Optional[Sequence[str]],
        *,
        force: bool = False,
        build_id: Optional[str] = None,
    ) -> SemanticBuildRunResult:
        return self._builder_factory().build(source_ids, force=force, build_id=build_id)

    def try_build(
        self,
        source_ids: Optional[Sequence[str]],
        *,
        force: bool = False,
        build_id: Optional[str] = None,
    ) -> Optional[SemanticBuildRunResult]:
        if not self.acquire(blocking=False):
            return None
        try:
            return self.build_locked(source_ids, force=force, build_id=build_id)
        finally:
            self.release()


class SemanticIndexBackgroundWorker:
    def __init__(
        self,
        db_path: Path,
        coordinator: SemanticBuildCoordinator,
        *,
        enabled: bool,
        interval_seconds: float,
        failed_retry_backoff_seconds: float,
        building_stale_after_seconds: float,
        time_fn: Callable[[], datetime] | None = None,
    ) -> None:
        self.db_path = Path(db_path)
        self.coordinator = coordinator
        self.enabled = bool(enabled)
        self.interval_seconds = max(0.1, float(interval_seconds or 0.1))
        self.failed_retry_backoff_seconds = max(0.0, float(failed_retry_backoff_seconds or 0.0))
        self.building_stale_after_seconds = max(1.0, float(building_stale_after_seconds or 1.0))
        self._time_fn = time_fn or (lambda: datetime.now(timezone.utc))
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._last_tick: Optional[SemanticWorkerTickResult] = None
        self._last_error: Optional[str] = None

    @property
    def is_running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> bool:
        if not self.enabled or self.is_running:
            return False
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run_loop, name="knowledge-semantic-auto-build", daemon=True)
        self._thread.start()
        return True

    def stop(self, timeout_seconds: float = 5.0) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=max(0.1, float(timeout_seconds or 0.1)))
        self._thread = None

    def run_once(self) -> SemanticWorkerTickResult:
        if not self.enabled:
            return self._remember(SemanticWorkerTickResult(status="DISABLED"))
        selected = self.select_source_ids()
        if not selected:
            return self._remember(SemanticWorkerTickResult(status="IDLE"))
        build_id = f"semantic-auto-build-{uuid.uuid4()}"
        result = self.coordinator.try_build(selected, force=False, build_id=build_id)
        if result is None:
            return self._remember(
                SemanticWorkerTickResult(
                    status="BUSY",
                    selected_source_ids=selected,
                    diagnostics=[
                        {
                            "code": "SEMANTIC_BUILD_ALREADY_RUNNING",
                            "message": "A semantic index build is already running.",
                            "severity": "INFO",
                        }
                    ],
                )
            )
        return self._remember(
            SemanticWorkerTickResult(
                status=result.status,
                selected_source_ids=selected,
                build_result=result,
                diagnostics=list(result.diagnostics),
            )
        )

    def select_source_ids(self) -> list[str]:
        store = SemanticIndexStore(self.db_path)
        store.reconcile_missing_states()
        now = self._time_fn()
        selected: list[str] = []
        for state in store.list_states():
            source_id = str(state.get("source_id") or "")
            if not source_id:
                continue
            status = store.status_for_source(source_id)
            if status.total_node_count <= 0 or not status.graph_revision:
                continue
            if status.status == SemanticIndexStatus.READY and not status.ready:
                selected.append(source_id)
                continue
            if status.status in {SemanticIndexStatus.PENDING, SemanticIndexStatus.STALE}:
                selected.append(source_id)
                continue
            if status.status == SemanticIndexStatus.FAILED and self._retry_backoff_elapsed(status.completed_at or status.updated_at, now):
                selected.append(source_id)
                continue
            if status.status == SemanticIndexStatus.BUILDING and self._building_is_stale(status.started_at or status.updated_at, now):
                store.mark_source_stale(source_id, status.graph_revision, status.total_node_count)
                selected.append(source_id)
        return sorted(set(selected))

    def status(self) -> dict[str, object]:
        return {
            "enabled": self.enabled,
            "running": self.is_running,
            "intervalSeconds": self.interval_seconds,
            "failedRetryBackoffSeconds": self.failed_retry_backoff_seconds,
            "buildingStaleAfterSeconds": self.building_stale_after_seconds,
            "lastTickStatus": self._last_tick.status if self._last_tick is not None else None,
            "lastSelectedSourceIds": self._last_tick.selected_source_ids if self._last_tick is not None else [],
            "lastError": self._last_error,
        }

    def _run_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.run_once()
                self._last_error = None
            except Exception:
                self._last_error = "Semantic auto build worker tick failed."
                logger.warning("Semantic auto build worker tick failed.")
            self._stop_event.wait(self.interval_seconds)

    def _remember(self, result: SemanticWorkerTickResult) -> SemanticWorkerTickResult:
        self._last_tick = result
        return result

    def _retry_backoff_elapsed(self, timestamp: Optional[str], now: datetime) -> bool:
        elapsed = _elapsed_seconds(timestamp, now)
        return elapsed is not None and elapsed >= self.failed_retry_backoff_seconds

    def _building_is_stale(self, timestamp: Optional[str], now: datetime) -> bool:
        elapsed = _elapsed_seconds(timestamp, now)
        return elapsed is not None and elapsed >= self.building_stale_after_seconds


def _elapsed_seconds(timestamp: Optional[str], now: datetime) -> Optional[float]:
    if not timestamp:
        return None
    try:
        parsed = datetime.fromisoformat(str(timestamp))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    return max(0.0, (now - parsed).total_seconds())
