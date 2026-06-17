from __future__ import annotations

import json
from typing import Any, Dict, List

import httpx


class OllamaUnavailableError(ConnectionError):
    """Raised when Ollama cannot be reached."""


class OllamaClient:
    def __init__(self, base_url: str, model: str, timeout_seconds: int) -> None:
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds

    async def health(self) -> None:
        try:
            async with httpx.AsyncClient(timeout=5) as client:
                response = await client.get(f"{self.base_url}/api/tags")
                response.raise_for_status()
        except httpx.HTTPError as exc:
            raise OllamaUnavailableError(f"Ollama is not reachable at {self.base_url}") from exc

    async def classify_intent(self, system_prompt: str, user_text: str, actions: List[Dict[str, Any]]) -> str:
        await self.health()
        prompt = (
            f"{system_prompt}\n\n"
            f"Available actions and targets:\n{json.dumps(actions, ensure_ascii=False)}\n\n"
            f"User command:\n{user_text}\n"
        )
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "format": "json",
            "options": {"temperature": 0},
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.post(f"{self.base_url}/api/generate", json=payload)
                response.raise_for_status()
        except httpx.HTTPError as exc:
            raise OllamaUnavailableError(f"Ollama is not reachable at {self.base_url}") from exc

        data = response.json()
        return str(data.get("response", ""))

    async def generate_text(self, prompt: str) -> str:
        await self.health()
        payload = {
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0},
        }
        try:
            async with httpx.AsyncClient(timeout=self.timeout_seconds) as client:
                response = await client.post(f"{self.base_url}/api/generate", json=payload)
                response.raise_for_status()
        except httpx.HTTPError as exc:
            raise OllamaUnavailableError(f"Ollama is not reachable at {self.base_url}") from exc

        data = response.json()
        return str(data.get("response", "")).strip()
