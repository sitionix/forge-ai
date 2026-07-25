from __future__ import annotations

import json
from typing import Any, Dict, List

import httpx


class OllamaUnavailableError(ConnectionError):
    """Raised when Ollama cannot be reached."""


class OllamaBadResponseError(ValueError):
    """Raised when Ollama returns malformed JSON."""


class OllamaClient:
    def __init__(self, base_url: str, model: str, context_tokens: int, timeout_seconds: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.context_tokens = int(context_tokens)
        if self.context_tokens < 1024:
            raise ValueError("Jarvis Ollama context_tokens must be at least 1024")
        self.timeout_seconds = timeout_seconds
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))

    async def health(self) -> None:
        try:
            response = await self._client.get(f"{self.base_url}/api/tags", timeout=5)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise OllamaUnavailableError(f"Ollama is not reachable at {self.base_url}") from exc

    async def classify_intent(self, system_prompt: str, user_text: str, actions: List[Dict[str, Any]]) -> str:
        prompt = f"{system_prompt}\n\nAvailable actions and targets:\n{json.dumps(actions, ensure_ascii=False)}\n\nUser command:\n{user_text}\n"
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "format": "json",
            "options": {"temperature": 0, "num_ctx": self.context_tokens},
        }
        try:
            response = await self._client.post(f"{self.base_url}/api/generate", json=payload)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise OllamaUnavailableError(f"Ollama is not reachable at {self.base_url}") from exc

        data = self._json_object(response)
        return str(data.get("response", ""))

    async def generate_text(self, prompt: str) -> str:
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0, "num_ctx": self.context_tokens},
        }
        try:
            response = await self._client.post(f"{self.base_url}/api/generate", json=payload)
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise OllamaUnavailableError(f"Ollama is not reachable at {self.base_url}") from exc

        data = self._json_object(response)
        return str(data.get("response", "")).strip()

    def _json_object(self, response: httpx.Response) -> Dict[str, Any]:
        try:
            data = response.json()
        except ValueError as exc:
            raise OllamaBadResponseError("Ollama returned invalid JSON") from exc
        if not isinstance(data, dict):
            raise OllamaBadResponseError("Ollama returned a non-object response")
        return data

    async def aclose(self) -> None:
        await self._client.aclose()
