from __future__ import annotations

import hashlib
import time
from typing import Any

from knowledge_service.codex_app_server import (
    CodexAppServerClient,
    CodexAppServerEmptyResponse,
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
        self.timeout_seconds = max(0.001, float(timeout_seconds))

    @property
    def provider_version(self) -> str:
        return self._client.version or "app-server"

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        started = time.perf_counter()
        timeout = self._timeout(request.timeout_seconds)
        try:
            result = self._client.run_turn_sync(
                prompt=request.prompt,
                model_id=request.model_id,
                effort_id=request.effort_id,
                response_mode=request.response_mode,
                timeout_seconds=timeout,
            )
        except CodexAppServerTimeout as exc:
            raise GenerativeProviderTimeout("codex generation request timed out", provider_id=self.provider_id) from exc
        except CodexAppServerEmptyResponse as exc:
            raise GenerativeProviderEmptyResponse("codex returned no response text", provider_id=self.provider_id) from exc
        except CodexAppServerProtocolError as exc:
            raise GenerativeProviderProtocolError("codex generation protocol error", provider_id=self.provider_id) from exc
        except CodexAppServerTransportError as exc:
            raise GenerativeProviderTransportError(
                "codex generation transport error",
                provider_id=self.provider_id,
                status_code=exc.status_code,
            ) from exc
        return self._normalize_response(request, result, started)

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse:
        started = time.perf_counter()
        timeout = self._timeout(request.timeout_seconds)
        try:
            result = await self._client.run_turn(
                prompt=request.prompt,
                model_id=request.model_id,
                effort_id=request.effort_id,
                response_mode=request.response_mode,
                timeout_seconds=timeout,
            )
        except CodexAppServerTimeout as exc:
            raise GenerativeProviderTimeout("codex generation request timed out", provider_id=self.provider_id) from exc
        except CodexAppServerEmptyResponse as exc:
            raise GenerativeProviderEmptyResponse("codex returned no response text", provider_id=self.provider_id) from exc
        except CodexAppServerProtocolError as exc:
            raise GenerativeProviderProtocolError("codex generation protocol error", provider_id=self.provider_id) from exc
        except CodexAppServerTransportError as exc:
            raise GenerativeProviderTransportError(
                "codex generation transport error",
                provider_id=self.provider_id,
                status_code=exc.status_code,
            ) from exc
        return self._normalize_response(request, result, started)

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
            provider_version=self.provider_version,
            model_id=request.model_id,
            duration_ms=round((time.perf_counter() - started) * 1000, 3),
            prompt_char_length=len(request.prompt),
            prompt_hash=self._sha256(request.prompt),
            response_char_length=len(raw_text),
            response_hash=self._sha256(raw_text),
            provider_metadata=metadata,
        )

    def _timeout(self, timeout_seconds: float | None) -> float:
        configured = max(0.001, float(self.timeout_seconds or 0.001))
        if timeout_seconds is None:
            return configured
        return max(0.001, min(configured, float(timeout_seconds)))

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()
