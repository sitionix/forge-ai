from __future__ import annotations

from typing import Any, Dict, Optional
from urllib.parse import urlparse

import httpx

from jarvis_agent.observability import CORRELATION_HEADER, current_correlation_id


class KnowledgeUnavailableError(ConnectionError):
    """Raised when Knowledge cannot be reached."""


class KnowledgeBadResponseError(ValueError):
    """Raised when Knowledge returns malformed JSON."""


class KnowledgeUpstreamResponseError(ConnectionError):
    """Raised when Knowledge returns a controlled non-2xx response."""

    def __init__(self, status_code: int, body: Dict[str, Any]) -> None:
        super().__init__(f"Knowledge returned HTTP {status_code}")
        self.status_code = status_code
        self.body = body


class KnowledgeConfigurationError(ValueError):
    """Raised when Knowledge configuration is unsafe."""


class KnowledgeClient:
    def __init__(
        self,
        base_url: str,
        timeout_seconds: float,
        flow_explanation_timeout_seconds: Optional[float] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.flow_explanation_timeout_seconds = flow_explanation_timeout_seconds or timeout_seconds
        self._client = http_client or httpx.AsyncClient(timeout=self._timeout(timeout_seconds))
        self._validate_base_url()

    async def query(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return await self._post(
            "/api/v1/knowledge/query",
            payload,
            timeout_seconds=self.flow_explanation_timeout_seconds,
        )

    async def _post(self, path: str, payload: Dict[str, Any], timeout_seconds: Optional[float] = None) -> Dict[str, Any]:
        try:
            headers = {}
            correlation_id = current_correlation_id()
            if correlation_id:
                headers[CORRELATION_HEADER] = correlation_id
            if timeout_seconds is None:
                response = await self._client.post(f"{self.base_url}{path}", json=payload, headers=headers)
            else:
                response = await self._client.post(
                    f"{self.base_url}{path}",
                    json=payload,
                    headers=headers,
                    timeout=self._timeout(timeout_seconds),
                )
            if response.status_code >= 400:
                try:
                    data = response.json()
                except ValueError as exc:
                    raise KnowledgeBadResponseError("Knowledge returned invalid JSON") from exc
                if not isinstance(data, dict):
                    raise KnowledgeBadResponseError("Knowledge returned a non-object error response")
                raise KnowledgeUpstreamResponseError(response.status_code, data)
        except httpx.HTTPError as exc:
            raise KnowledgeUnavailableError(f"Knowledge is not reachable at {self.base_url}") from exc
        try:
            data = response.json()
        except ValueError as exc:
            raise KnowledgeBadResponseError("Knowledge returned invalid JSON") from exc
        if not isinstance(data, dict):
            raise KnowledgeBadResponseError("Knowledge returned a non-object query response")
        return data

    async def aclose(self) -> None:
        await self._client.aclose()

    def _validate_base_url(self) -> None:
        parsed = urlparse(self.base_url)
        if parsed.scheme.lower() != "http":
            raise KnowledgeConfigurationError("Knowledge base URL must use http")
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost"}:
            raise KnowledgeConfigurationError("Knowledge base URL must point to localhost")

    @staticmethod
    def _timeout(timeout_seconds: float) -> httpx.Timeout:
        return httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds))
