from __future__ import annotations

import hashlib
import time
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any, NoReturn

from knowledge_service.codex_app_server import (
    CodexAppServerClient,
    CodexAppServerEmptyResponse,
    CodexAppServerLifecycleError,
    CodexAppServerProtocolError,
    CodexAppServerTimeout,
    CodexAppServerTransportError,
    CodexTurnResult,
)
from knowledge_service.generative_runtime.core import (
    GenerativeProviderEmptyResponse,
    GenerativeProviderProtocolError,
    GenerativeProviderTimeout,
    GenerativeProviderTransportError,
    GenerativeRequest,
    GenerativeResponse,
)


class CodexGenerativeProvider:
    provider_id = "codex"

    def __init__(self, client: CodexAppServerClient, *, timeout_seconds: float) -> None:
        self._client = client
        self.timeout_seconds = float(timeout_seconds)
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")

    @property
    def provider_version(self) -> str:
        version = self._client.version
        if version is None:
            raise GenerativeProviderProtocolError("codex generation started before app-server initialization", provider_id=self.provider_id)
        return version

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        started = time.perf_counter()
        timeout = self._timeout(request.timeout_seconds)
        with self._transport_error_boundary():
            result = self._client.run_turn_sync(
                prompt=request.prompt,
                model_id=request.model_id,
                effort_id=request.effort_id,
                response_mode=request.response_mode,
                timeout_seconds=timeout,
            )
        return self._normalize_response(request, result, started)

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse:
        started = time.perf_counter()
        timeout = self._timeout(request.timeout_seconds)
        with self._transport_error_boundary():
            result = await self._client.run_turn(
                prompt=request.prompt,
                model_id=request.model_id,
                effort_id=request.effort_id,
                response_mode=request.response_mode,
                timeout_seconds=timeout,
            )
        return self._normalize_response(request, result, started)

    @contextmanager
    def _transport_error_boundary(self) -> Iterator[None]:
        try:
            yield
        except (CodexAppServerTimeout, CodexAppServerEmptyResponse, CodexAppServerProtocolError, CodexAppServerTransportError, CodexAppServerLifecycleError) as exc:
            self._raise_provider_error(exc)

    def _raise_provider_error(self, exc: Exception) -> NoReturn:
        if isinstance(exc, CodexAppServerTimeout):
            raise GenerativeProviderTimeout("codex generation request timed out", provider_id=self.provider_id) from exc
        if isinstance(exc, CodexAppServerEmptyResponse):
            raise GenerativeProviderEmptyResponse("codex returned no response text", provider_id=self.provider_id) from exc
        if isinstance(exc, CodexAppServerProtocolError):
            raise GenerativeProviderProtocolError("codex generation protocol error", provider_id=self.provider_id) from exc
        status_code = exc.status_code if isinstance(exc, CodexAppServerTransportError) else None
        raise GenerativeProviderTransportError(
            "codex generation transport error",
            provider_id=self.provider_id,
            status_code=status_code,
        ) from exc

    def close(self) -> None:
        return None

    async def aclose(self) -> None:
        return None

    def _normalize_response(self, request: GenerativeRequest, result: CodexTurnResult, started: float) -> GenerativeResponse:
        raw_text = result.raw_text
        metadata: dict[str, Any] = {
            "threadId": result.thread_id,
            "turnId": result.turn_id,
            "turnStatus": result.turn_status,
        }
        if request.effort_id is not None:
            metadata["requestedEffort"] = request.effort_id
        if result.token_usage is not None:
            metadata["tokenUsage"] = dict(result.token_usage)
        if result.warnings:
            metadata["warnings"] = list(result.warnings)
        metadata.update(result.model_metadata)
        return GenerativeResponse(
            raw_text=raw_text,
            provider_id=self.provider_id,
            provider_version=result.server_version,
            model_id=request.model_id,
            duration_ms=round((time.perf_counter() - started) * 1000, 3),
            prompt_char_length=len(request.prompt),
            prompt_hash=self._sha256(request.prompt),
            response_char_length=len(raw_text),
            response_hash=self._sha256(raw_text),
            provider_metadata=metadata,
        )

    def _timeout(self, timeout_seconds: float | None) -> float:
        if timeout_seconds is None:
            return self.timeout_seconds
        requested = float(timeout_seconds)
        if requested <= 0:
            raise ValueError("timeout_seconds must be positive")
        return min(self.timeout_seconds, requested)

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()
