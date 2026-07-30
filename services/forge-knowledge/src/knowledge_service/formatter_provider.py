from __future__ import annotations

import json
import time
from typing import Any, Mapping, Sequence

import httpx

from knowledge_service.formatter_protocol import (
    EndToEndFormatterDeadlineExceeded,
    EndToEndFormatterProviderError,
    EndToEndFormatterProviderResult,
)
from knowledge_service.generative_runtime import (
    GenerativeProvider,
    GenerativeProviderEmptyResponse,
    GenerativeProviderError,
    GenerativeProviderTimeout,
    GenerativeRequest,
    OllamaGenerativeProvider,
    ResponseMode,
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


class ProviderBackedEndToEndFormatterClient:
    name = "provider-backed-end-to-end-formatter"

    def __init__(
        self,
        provider: GenerativeProvider,
        model: str,
        timeout_seconds: float,
        *,
        renderer: EndToEndFormatterPromptRenderer | None = None,
    ) -> None:
        self.provider = provider
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.renderer = renderer or EndToEndFormatterPromptRenderer()

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
        started = time.perf_counter()
        try:
            response = self.provider.generate(
                GenerativeRequest(
                    prompt=prompt,
                    model_id=self.model,
                    response_mode=ResponseMode.JSON_OBJECT,
                    timeout_seconds=timeout_seconds,
                    temperature=0,
                )
            )
        except GenerativeProviderTimeout as exc:
            raise EndToEndFormatterDeadlineExceeded("canonical formatter provider timed out") from exc
        except GenerativeProviderEmptyResponse as exc:
            raise EndToEndFormatterProviderError("canonical formatter provider returned an empty response") from exc
        except GenerativeProviderError as exc:
            raise EndToEndFormatterProviderError("canonical formatter provider failed") from exc
        except Exception as exc:
            raise EndToEndFormatterProviderError("canonical formatter provider failed") from exc
        raw_text = response.raw_text
        if not raw_text.strip():
            raise EndToEndFormatterProviderError("canonical formatter provider returned an empty response")
        return EndToEndFormatterProviderResult(
            raw_text=raw_text,
            prompt_char_length=response.prompt_char_length,
            prompt_hash=response.prompt_hash,
            duration_ms=response.duration_ms or round((time.perf_counter() - started) * 1000, 3),
            provider_name=getattr(self.provider, "provider_id", self.name),
            provider_model=response.model_id,
        )

    def close(self) -> None:
        return None


class LocalOllamaEndToEndFormatterClient(ProviderBackedEndToEndFormatterClient):
    name = "local-ollama-end-to-end-formatter"

    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: float,
        *,
        renderer: EndToEndFormatterPromptRenderer | None = None,
        http_client: httpx.Client | None = None,
    ) -> None:
        self._ollama_provider = OllamaGenerativeProvider(
            base_url,
            timeout_seconds=timeout_seconds,
            sync_client=http_client,
        )
        self._ollama_provider._owns_sync_client = True
        super().__init__(
            self._ollama_provider,
            model,
            timeout_seconds,
            renderer=renderer,
        )

    def close(self) -> None:
        self._ollama_provider.close()
