from __future__ import annotations

import inspect
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Mapping, Protocol, runtime_checkable


class ResponseMode(str, Enum):
    TEXT = "text"
    JSON_OBJECT = "json_object"


@dataclass(frozen=True)
class GenerativeRequest:
    prompt: str
    model_id: str
    response_mode: ResponseMode = ResponseMode.TEXT
    timeout_seconds: float | None = None
    context_tokens: int | None = None
    temperature: float | int | None = None
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "prompt", str(self.prompt or ""))
        object.__setattr__(self, "model_id", str(self.model_id or ""))
        if not self.model_id.strip():
            raise ValueError("GenerativeRequest.model_id is required")
        if self.context_tokens is not None and int(self.context_tokens) < 1:
            raise ValueError("GenerativeRequest.context_tokens must be positive when provided")
        if self.timeout_seconds is not None and float(self.timeout_seconds) <= 0:
            raise ValueError("GenerativeRequest.timeout_seconds must be positive when provided")


@dataclass(frozen=True)
class GenerativeResponse:
    raw_text: str
    provider_id: str
    provider_version: str
    model_id: str
    duration_ms: float
    prompt_char_length: int
    prompt_hash: str
    response_char_length: int
    response_hash: str
    provider_metadata: Mapping[str, Any] = field(default_factory=dict)


class GenerativeProviderError(RuntimeError):
    provider_id: str | None

    def __init__(self, message: str, *, provider_id: str | None = None) -> None:
        self.provider_id = provider_id
        super().__init__(message)


class GenerativeProviderTimeout(GenerativeProviderError, TimeoutError):
    pass


class GenerativeProviderTransportError(GenerativeProviderError):
    def __init__(
        self,
        message: str,
        *,
        provider_id: str | None = None,
        status_code: int | None = None,
        response_text: str | None = None,
    ) -> None:
        self.status_code = status_code
        self.response_text = response_text
        super().__init__(message, provider_id=provider_id)


class GenerativeProviderProtocolError(GenerativeProviderError):
    def __init__(self, message: str, *, provider_id: str | None = None, response_text: str | None = None) -> None:
        self.response_text = response_text
        super().__init__(message, provider_id=provider_id)


class GenerativeProviderEmptyResponse(GenerativeProviderProtocolError):
    pass


class GenerativeProviderDuplicateError(GenerativeProviderError):
    pass


class GenerativeProviderNotFoundError(GenerativeProviderError):
    pass


@runtime_checkable
class GenerativeProvider(Protocol):
    provider_id: str
    provider_version: str

    def generate(self, request: GenerativeRequest) -> GenerativeResponse: ...

    def close(self) -> None: ...


@runtime_checkable
class AsyncGenerativeProvider(Protocol):
    provider_id: str
    provider_version: str

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse: ...

    async def aclose(self) -> None: ...


class GenerativeProviderRegistry:
    def __init__(self) -> None:
        self._providers: dict[str, Any] = {}

    def register(self, provider: Any) -> None:
        provider_id = str(getattr(provider, "provider_id", "") or "").strip().lower()
        if not provider_id:
            raise ValueError("generative provider_id is required")
        if provider_id in self._providers:
            raise GenerativeProviderDuplicateError(f"generative provider already registered: {provider_id}", provider_id=provider_id)
        self._providers[provider_id] = provider

    def resolve(self, provider_id: str) -> Any:
        normalized = str(provider_id or "").strip().lower()
        try:
            return self._providers[normalized]
        except KeyError as exc:
            raise GenerativeProviderNotFoundError(f"generative provider is not registered: {normalized}", provider_id=normalized) from exc

    def close(self) -> None:
        for provider in self._providers.values():
            close = getattr(provider, "close", None)
            if callable(close):
                close()

    async def aclose(self) -> None:
        for provider in self._providers.values():
            aclose = getattr(provider, "aclose", None)
            if callable(aclose):
                result = aclose()
                if inspect.isawaitable(result):
                    await result
            close = getattr(provider, "close", None)
            if callable(close):
                close()
