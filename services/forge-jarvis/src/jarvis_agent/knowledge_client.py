from __future__ import annotations

from typing import Any, Dict, List
from urllib.parse import urlparse

import httpx

from jarvis_agent.chat_schema import ChatContextItem, ChatDiagnostic


class KnowledgeUnavailableError(ConnectionError):
    """Raised when Knowledge cannot be reached."""


class KnowledgeBadResponseError(ValueError):
    """Raised when Knowledge returns malformed JSON."""


class KnowledgeConfigurationError(ValueError):
    """Raised when Knowledge configuration is unsafe."""


class KnowledgeClient:
    def __init__(self, base_url: str, timeout_seconds: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))
        self._validate_base_url()

    async def context(self, query: str, max_context_chars: int) -> Dict[str, Any]:
        payload = {
            "query": query,
            "maxChars": max_context_chars,
            "includeContent": True,
        }
        try:
            response = await self._client.post(f"{self.base_url}/api/v1/knowledge/context", json=payload)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise KnowledgeUnavailableError(f"Knowledge is not reachable at {self.base_url}") from exc
        try:
            data = response.json()
        except ValueError as exc:
            raise KnowledgeBadResponseError("Knowledge returned invalid JSON") from exc
        if not isinstance(data, dict):
            raise KnowledgeBadResponseError("Knowledge returned a non-object response")
        return data

    async def aclose(self) -> None:
        await self._client.aclose()

    def _validate_base_url(self) -> None:
        parsed = urlparse(self.base_url)
        if parsed.scheme.lower() != "http":
            raise KnowledgeConfigurationError("Knowledge base URL must use http")
        if (parsed.hostname or "").lower() not in {"127.0.0.1", "localhost"}:
            raise KnowledgeConfigurationError("Knowledge base URL must point to localhost")


def used_context_items(bundle: Dict[str, Any]) -> List[ChatContextItem]:
    items = bundle.get("context", [])
    if not isinstance(items, list):
        return []
    result: list[ChatContextItem] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        metadata = item.get("metadata")
        result.append(
            ChatContextItem(
                sourceId=str(item.get("sourceId", "")),
                displayName=str(item.get("displayName", "")),
                relativePath=str(item.get("relativePath", "")),
                lineStart=int(item.get("lineStart", 0)),
                lineEnd=int(item.get("lineEnd", 0)),
                reason=str(item.get("reason", "")),
                score=float(item.get("score", 0.0)),
                content=item.get("content"),
                metadata=dict(metadata) if isinstance(metadata, dict) else {},
            )
        )
    return result


def diagnostics(bundle: Dict[str, Any]) -> List[ChatDiagnostic]:
    raw = bundle.get("diagnostics", [])
    if not isinstance(raw, list):
        return []
    result: list[ChatDiagnostic] = []
    for item in raw:
        if isinstance(item, dict):
            result.append(
                ChatDiagnostic(
                    code=str(item.get("code", "KNOWLEDGE_DIAGNOSTIC")),
                    message=str(item.get("message", "")),
                )
            )
    return result
