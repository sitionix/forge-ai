from __future__ import annotations

import asyncio
import logging
import math
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping, Protocol

import httpx

LOGGER = logging.getLogger(__name__)
EMBEDDING_STATUS_CACHE_TTL_SECONDS = 30.0
EMBEDDING_STATUS_PROBE_TEXT = "Forge AI semantic embedding health check"


@dataclass(frozen=True)
class EmbeddingRuntimeDiagnostic:
    code: str
    message: str


@dataclass(frozen=True)
class EmbeddingRuntimeStatusSnapshot:
    provider_id: str
    model_id: str
    status: str
    provider_version: str | None
    embedding_dimension: int | None
    last_checked_at: str
    diagnostic: EmbeddingRuntimeDiagnostic | None


class EmbeddingRuntimeStatusProvider(Protocol):
    async def status(self) -> EmbeddingRuntimeStatusSnapshot:
        ...

    async def aclose(self) -> None:
        ...


class OllamaEmbeddingRuntimeStatusProvider:
    def __init__(
        self,
        *,
        enabled: bool,
        provider_id: str,
        base_url: str,
        model_id: str,
        timeout_seconds: int,
        cache_ttl_seconds: float = EMBEDDING_STATUS_CACHE_TTL_SECONDS,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.enabled = bool(enabled)
        self.provider_id = str(provider_id or "").strip().lower()
        self.base_url = str(base_url or "").rstrip("/")
        self.model_id = str(model_id or "").strip()
        self.timeout_seconds = max(1, int(timeout_seconds or 1))
        self.cache_ttl_seconds = max(0.0, float(cache_ttl_seconds))
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(self.timeout_seconds, connect=min(5, self.timeout_seconds)))
        self._owns_client = client is None
        self._lock = asyncio.Lock()
        self._cached: EmbeddingRuntimeStatusSnapshot | None = None
        self._cached_at = 0.0
        self._inflight: asyncio.Task[EmbeddingRuntimeStatusSnapshot] | None = None

    async def status(self) -> EmbeddingRuntimeStatusSnapshot:
        async with self._lock:
            now = time.monotonic()
            if self._cached is not None and now - self._cached_at < self.cache_ttl_seconds:
                return self._cached
            if self._inflight is None or self._inflight.done():
                self._inflight = asyncio.create_task(self._refresh_and_cache())
            task = self._inflight
        return await asyncio.shield(task)

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _refresh_and_cache(self) -> EmbeddingRuntimeStatusSnapshot:
        started = time.monotonic()
        snapshot = await self._refresh()
        duration_ms = round((time.monotonic() - started) * 1000.0, 1)
        LOGGER.info(
            "Embedding runtime status refreshed providerId=%s modelId=%s status=%s providerVersion=%s embeddingDimension=%s diagnosticCode=%s durationMs=%s",
            snapshot.provider_id,
            snapshot.model_id,
            snapshot.status,
            snapshot.provider_version,
            snapshot.embedding_dimension,
            snapshot.diagnostic.code if snapshot.diagnostic is not None else None,
            duration_ms,
        )
        async with self._lock:
            self._cached = snapshot
            self._cached_at = time.monotonic()
            if self._inflight is not None and self._inflight.done():
                self._inflight = None
        return snapshot

    async def _refresh(self) -> EmbeddingRuntimeStatusSnapshot:
        checked_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        if not self.enabled:
            return self._snapshot("DISABLED", checked_at=checked_at)
        if self.provider_id != "ollama":
            return self._snapshot(
                "UNAVAILABLE",
                checked_at=checked_at,
                diagnostic=EmbeddingRuntimeDiagnostic("SEMANTIC_PROVIDER_UNAVAILABLE", "Configured embedding provider is unavailable."),
            )
        version = await self._version()
        if version is None:
            return self._snapshot(
                "UNAVAILABLE",
                checked_at=checked_at,
                diagnostic=EmbeddingRuntimeDiagnostic("SEMANTIC_PROVIDER_UNAVAILABLE", "Configured embedding provider is unavailable."),
            )
        tags = await self._tags()
        if tags is None:
            return self._snapshot(
                "UNAVAILABLE",
                checked_at=checked_at,
                provider_version=version,
                diagnostic=EmbeddingRuntimeDiagnostic("SEMANTIC_PROVIDER_UNAVAILABLE", "Configured embedding provider is unavailable."),
            )
        if not _model_present(tags, self.model_id):
            return self._snapshot(
                "UNAVAILABLE",
                checked_at=checked_at,
                provider_version=version,
                diagnostic=EmbeddingRuntimeDiagnostic("SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE", "Configured embedding model is unavailable."),
            )
        dimension = await self._probe_dimension()
        if dimension is None:
            return self._snapshot(
                "DEGRADED",
                checked_at=checked_at,
                provider_version=version,
                diagnostic=EmbeddingRuntimeDiagnostic("SEMANTIC_EMBEDDING_PROBE_FAILED", "Configured embedding model probe failed."),
            )
        return self._snapshot("READY", checked_at=checked_at, provider_version=version, embedding_dimension=dimension)

    async def _version(self) -> str | None:
        try:
            response = await self._client.get(f"{self.base_url}/api/version")
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError):
            return None
        version = payload.get("version") if isinstance(payload, Mapping) else None
        return str(version).strip() if version else None

    async def _tags(self) -> list[Mapping[str, Any]] | None:
        try:
            response = await self._client.get(f"{self.base_url}/api/tags")
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError):
            return None
        models = payload.get("models") if isinstance(payload, Mapping) else None
        return [model for model in models if isinstance(model, Mapping)] if isinstance(models, list) else None

    async def _probe_dimension(self) -> int | None:
        try:
            response = await self._client.post(
                f"{self.base_url}/api/embed",
                json={"model": self.model_id, "input": EMBEDDING_STATUS_PROBE_TEXT},
            )
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError):
            return None
        embeddings = payload.get("embeddings") if isinstance(payload, Mapping) else None
        if not isinstance(embeddings, list) or len(embeddings) != 1:
            return None
        vector = embeddings[0]
        if not isinstance(vector, list) or not vector:
            return None
        for value in vector:
            if not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                return None
        return len(vector)

    def _snapshot(
        self,
        status: str,
        *,
        checked_at: str,
        provider_version: str | None = None,
        embedding_dimension: int | None = None,
        diagnostic: EmbeddingRuntimeDiagnostic | None = None,
    ) -> EmbeddingRuntimeStatusSnapshot:
        return EmbeddingRuntimeStatusSnapshot(
            provider_id=self.provider_id,
            model_id=self.model_id,
            status=status,
            provider_version=provider_version,
            embedding_dimension=embedding_dimension,
            last_checked_at=checked_at,
            diagnostic=diagnostic,
        )


def _model_present(models: list[Mapping[str, Any]], configured_model: str) -> bool:
    configured = str(configured_model or "").strip()
    if not configured:
        return False
    for model in models:
        name = str(model.get("name") or model.get("model") or "").strip()
        if name == configured:
            return True
        if ":" not in configured and name == f"{configured}:latest":
            return True
    return False
