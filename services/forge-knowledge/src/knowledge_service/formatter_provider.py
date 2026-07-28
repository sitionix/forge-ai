from __future__ import annotations

import hashlib
import json
import time
from typing import Any, Mapping, Sequence

import httpx

from knowledge_service.formatter_protocol import (
    EndToEndFormatterDeadlineExceeded,
    EndToEndFormatterProviderError,
    EndToEndFormatterProviderResult,
)


class EndToEndFormatterPromptRenderer:
    def render(self, formatter_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        payload = json.dumps(dict(formatter_input), ensure_ascii=False, indent=2, sort_keys=True)
        errors = "\n".join(f"- {item}" for item in validation_errors or ())
        repair = f"\nPrevious JSON failed validation. Correct these exact issues:\n{errors}\n" if errors else ""
        return (
            "Localize canonical narration clauses as grounded prose.\n"
            "Return strict JSON only. Do not include prose outside JSON.\n"
            "The JSON shape is exactly: {\"clauses\":[{\"clauseRef\":\"string\",\"referencedCanonicalRefs\":[\"string\"],\"textTemplate\":\"string with {{ref:canonical-ref}} placeholders\"}]}.\n"
            "Return exactly one clause per supplied clause, in the supplied clauseOrder.\n"
            "referencedCanonicalRefs may contain only allowedCanonicalRefs for that same clause.\n"
            "Every referencedCanonicalRef must appear as a {{ref:...}} placeholder, and every placeholder must be declared.\n"
            "Use responseLanguage for every textTemplate.\n"
            "Do not add introductions, conclusions, extra paragraphs, or claims outside the supplied clause semanticOperation.\n"
            "Use placeholders instead of printing canonical IDs, symbols, routes, methods, transitions, or sources directly.\n"
            f"{repair}"
            "BEGIN_CANONICAL_FORMATTER_INPUT_JSON\n"
            f"{payload}\n"
            "END_CANONICAL_FORMATTER_INPUT_JSON\n"
        )


class LocalOllamaEndToEndFormatterClient:
    name = "local-ollama-end-to-end-formatter"

    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: float,
        *,
        renderer: EndToEndFormatterPromptRenderer | None = None,
    ) -> None:
        self.base_url = str(base_url or "").rstrip("/")
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.renderer = renderer or EndToEndFormatterPromptRenderer()
        self._client = httpx.Client(timeout=timeout_seconds)

    def generate(
        self,
        formatter_input: Mapping[str, Any],
        *,
        deadline_at: float,
        cancel_event: Any | None,
        validation_errors: Sequence[str] = (),
    ) -> EndToEndFormatterProviderResult:
        if cancel_event is not None and getattr(cancel_event, "is_set", lambda: False)():
            raise EndToEndFormatterDeadlineExceeded("canonical formatter cancelled")
        remaining = max(0.0, deadline_at - time.monotonic())
        if remaining <= 0.0:
            raise EndToEndFormatterDeadlineExceeded("canonical formatter deadline exceeded")
        timeout_seconds = max(0.001, min(float(self.timeout_seconds or remaining), remaining))
        prompt = self.renderer.render(formatter_input, validation_errors)
        prompt_hash = _sha256(prompt)
        started = time.perf_counter()
        try:
            response = self._client.post(
                f"{self.base_url}/api/generate",
                json={
                    "model": self.model,
                    "prompt": prompt,
                    "stream": False,
                    "format": "json",
                    "options": {"temperature": 0},
                },
                timeout=timeout_seconds,
            )
            response.raise_for_status()
            payload = response.json()
            raw_text = str(payload.get("response") or "")
        except httpx.TimeoutException as exc:
            raise EndToEndFormatterDeadlineExceeded("canonical formatter provider timed out") from exc
        except Exception as exc:
            raise EndToEndFormatterProviderError("canonical formatter provider failed") from exc
        if not raw_text.strip():
            raise EndToEndFormatterProviderError("canonical formatter provider returned an empty response")
        return EndToEndFormatterProviderResult(
            raw_text=raw_text,
            prompt_char_length=len(prompt),
            prompt_hash=prompt_hash,
            duration_ms=round((time.perf_counter() - started) * 1000, 3),
            provider_name=self.name,
            provider_model=self.model,
        )

    def close(self) -> None:
        self._client.close()


def _sha256(value: str) -> str:
    return hashlib.sha256(str(value or "").encode("utf-8")).hexdigest()
