from __future__ import annotations

import hashlib
import json
import time
import urllib.parse
from typing import Any, Mapping

import httpx

from knowledge_service.generative_runtime.core import (
    GenerativeProviderEmptyResponse,
    GenerativeProviderProtocolError,
    GenerativeProviderTimeout,
    GenerativeProviderTransportError,
    GenerativeRequest,
    GenerativeResponse,
    ResponseMode,
)


class OllamaGenerativeProvider:
    provider_id = "ollama"
    provider_version = "1"

    _METADATA_KEYS = (
        "done",
        "done_reason",
        "total_duration",
        "load_duration",
        "prompt_eval_count",
        "prompt_eval_duration",
        "eval_count",
        "eval_duration",
    )

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float,
        sync_client: httpx.Client | None = None,
        async_client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = self._require_localhost(str(base_url or "").rstrip("/"))
        self.timeout_seconds = float(timeout_seconds)
        self._sync_client = sync_client
        self._async_client = async_client
        self._owns_sync_client = sync_client is None
        self._owns_async_client = async_client is None

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        timeout = self._timeout(request.timeout_seconds)
        started = time.perf_counter()
        try:
            response = self._post_sync(request, timeout)
            response_text = str(getattr(response, "text", "") or "")
            response.raise_for_status()
            payload = response.json()
        except httpx.TimeoutException as exc:
            raise GenerativeProviderTimeout("ollama generation request timed out", provider_id=self.provider_id) from exc
        except httpx.HTTPStatusError as exc:
            raise GenerativeProviderTransportError(
                f"ollama generation HTTP error {exc.response.status_code}",
                provider_id=self.provider_id,
                status_code=exc.response.status_code,
                response_text=exc.response.text,
            ) from exc
        except httpx.HTTPError as exc:
            raise GenerativeProviderTransportError("ollama generation transport error", provider_id=self.provider_id) from exc
        except (json.JSONDecodeError, ValueError) as exc:
            raise GenerativeProviderProtocolError(
                "ollama returned invalid envelope JSON",
                provider_id=self.provider_id,
                response_text=locals().get("response_text", ""),
            ) from exc
        return self._normalize_response(request, payload, started)

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse:
        timeout = self._timeout(request.timeout_seconds)
        started = time.perf_counter()
        try:
            response = await self._post_async(request, timeout)
            response_text = str(getattr(response, "text", "") or "")
            response.raise_for_status()
            payload = response.json()
        except httpx.TimeoutException as exc:
            raise GenerativeProviderTimeout("ollama generation request timed out", provider_id=self.provider_id) from exc
        except httpx.HTTPStatusError as exc:
            raise GenerativeProviderTransportError(
                f"ollama generation HTTP error {exc.response.status_code}",
                provider_id=self.provider_id,
                status_code=exc.response.status_code,
                response_text=exc.response.text,
            ) from exc
        except httpx.HTTPError as exc:
            raise GenerativeProviderTransportError("ollama generation transport error", provider_id=self.provider_id) from exc
        except (json.JSONDecodeError, ValueError) as exc:
            raise GenerativeProviderProtocolError(
                "ollama returned invalid envelope JSON",
                provider_id=self.provider_id,
                response_text=locals().get("response_text", ""),
            ) from exc
        return self._normalize_response(request, payload, started)

    def close(self) -> None:
        if self._owns_sync_client and self._sync_client is not None:
            self._sync_client.close()

    async def aclose(self) -> None:
        if self._owns_async_client and self._async_client is not None:
            await self._async_client.aclose()

    def _request_envelope(self, request: GenerativeRequest) -> dict[str, Any]:
        envelope: dict[str, Any] = {
            "model": request.model_id,
            "prompt": request.prompt,
            "stream": False,
        }
        if request.response_mode == ResponseMode.JSON_OBJECT:
            envelope["format"] = "json"
        options: dict[str, Any] = {}
        if request.context_tokens is not None:
            options["num_ctx"] = int(request.context_tokens)
        if request.temperature is not None:
            options["temperature"] = request.temperature
        if options:
            envelope["options"] = options
        return envelope

    def _post_sync(self, request: GenerativeRequest, timeout: float) -> httpx.Response:
        client = self._require_sync_client()
        try:
            return client.post(
                f"{self.base_url}/api/generate",
                json=self._request_envelope(request),
                timeout=httpx.Timeout(timeout, connect=min(5.0, timeout)),
            )
        except TypeError as exc:
            if "timeout" not in str(exc):
                raise
            return client.post(
                f"{self.base_url}/api/generate",
                json=self._request_envelope(request),
            )

    async def _post_async(self, request: GenerativeRequest, timeout: float) -> httpx.Response:
        client = self._require_async_client()
        try:
            return await client.post(
                f"{self.base_url}/api/generate",
                json=self._request_envelope(request),
                timeout=httpx.Timeout(timeout, connect=min(5.0, timeout)),
            )
        except TypeError as exc:
            if "timeout" not in str(exc):
                raise
            return await client.post(
                f"{self.base_url}/api/generate",
                json=self._request_envelope(request),
            )

    def _require_sync_client(self) -> httpx.Client:
        if self._sync_client is None:
            self._sync_client = httpx.Client(timeout=httpx.Timeout(self.timeout_seconds, connect=min(5.0, self.timeout_seconds)))
        return self._sync_client

    def _require_async_client(self) -> httpx.AsyncClient:
        if self._async_client is None:
            self._async_client = httpx.AsyncClient(timeout=httpx.Timeout(self.timeout_seconds, connect=min(5.0, self.timeout_seconds)))
        return self._async_client

    def _normalize_response(self, request: GenerativeRequest, payload: Any, started: float) -> GenerativeResponse:
        if not isinstance(payload, Mapping):
            raise GenerativeProviderProtocolError("ollama envelope root must be an object", provider_id=self.provider_id)
        raw_text = payload.get("response")
        if not isinstance(raw_text, str) or not raw_text.strip():
            raise GenerativeProviderEmptyResponse("ollama returned no response text", provider_id=self.provider_id)
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
            provider_metadata={key: payload.get(key) for key in self._METADATA_KEYS if key in payload},
        )

    def _timeout(self, timeout_seconds: float | None) -> float:
        configured = max(0.001, float(self.timeout_seconds or 0.001))
        if timeout_seconds is None:
            return configured
        return max(0.001, min(configured, float(timeout_seconds)))

    def _require_localhost(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise ValueError("generative Ollama base_url must point to localhost")
        return base_url

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()
