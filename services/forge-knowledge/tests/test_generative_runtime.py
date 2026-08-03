from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from knowledge_service.ai_runtime_discovery import AiRuntimeDiscoveryRegistry, AiRuntimeDiscoveryService, CodexAiRuntimeOptionsSource
from knowledge_service.bootstrap import KnowledgeDependencies, build_generative_runtime
from knowledge_service.codex_app_server import CodexAppServerClient, CodexNotificationBufferPolicy, CodexRuntimeSettings, CodexTurnResult
from knowledge_service.config import AppConfig
from knowledge_service.generative_runtime import (
    CodexGenerativeProvider,
    GenerativeProviderDuplicateError,
    GenerativeProviderEmptyResponse,
    GenerativeProviderNotFoundError,
    GenerativeProviderProtocolError,
    GenerativeProviderRegistry,
    GenerativeProviderTimeout,
    GenerativeProviderTransportError,
    GenerativeRequest,
    GenerativeResponse,
    OllamaGenerativeProvider,
    ResponseMode,
)


class FakeProvider:
    provider_id = "fake"
    provider_version = "test"

    def __init__(self) -> None:
        self.closed = False
        self.aclosed = False

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        return GenerativeResponse(
            raw_text="{}",
            provider_id=self.provider_id,
            provider_version=self.provider_version,
            model_id=request.model_id,
            duration_ms=1.0,
            prompt_char_length=len(request.prompt),
            prompt_hash="prompt",
            response_char_length=2,
            response_hash="response",
            provider_metadata={"done": True},
        )

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse:
        return self.generate(request)

    def close(self) -> None:
        self.closed = True

    async def aclose(self) -> None:
        self.aclosed = True


def test_generative_request_normalizes_blank_effort_to_none():
    assert GenerativeRequest(prompt="x", model_id="m", effort_id="   ").effort_id is None


def test_generative_request_positional_response_mode_contract_is_preserved():
    request = GenerativeRequest("prompt", "model", ResponseMode.JSON_OBJECT)

    assert request.response_mode == ResponseMode.JSON_OBJECT
    assert request.effort_id is None


class SingleCallSyncClient:
    def __init__(self, response: httpx.Response | Exception) -> None:
        self.response = response
        self.calls = []

    def post(self, url: str, *, json, timeout):
        self.calls.append({"url": url, "json": json, "timeout": timeout})
        if isinstance(self.response, Exception):
            raise self.response
        if getattr(self.response, "_request", None) is None:
            self.response._request = httpx.Request("POST", url)
        return self.response

    def close(self) -> None:
        return None


class SingleCallAsyncClient:
    def __init__(self, response: httpx.Response | Exception) -> None:
        self.response = response
        self.calls = []

    async def post(self, url: str, *, json, timeout):
        self.calls.append({"url": url, "json": json, "timeout": timeout})
        if isinstance(self.response, Exception):
            raise self.response
        if getattr(self.response, "_request", None) is None:
            self.response._request = httpx.Request("POST", url)
        return self.response

    async def aclose(self) -> None:
        return None


class CloseCountingCodexClient:
    version = "test"

    def __init__(self) -> None:
        self.close_count = 0
        self.initialize_count = 0

    async def initialize(self) -> str:
        self.initialize_count += 1
        return "test"

    async def aclose(self) -> None:
        self.close_count += 1


class FailingCleanup:
    def __init__(self, message: str) -> None:
        self.message = message
        self.close_count = 0

    async def aclose(self) -> None:
        self.close_count += 1
        raise RuntimeError(self.message)


class CancellingCleanup:
    def __init__(self) -> None:
        self.close_count = 0

    async def aclose(self) -> None:
        self.close_count += 1
        raise asyncio.CancelledError


class VersionRaceCodexClient:
    @property
    def version(self) -> str:
        raise AssertionError("provider must use the version captured in CodexTurnResult")

    def run_turn_sync(self, **kwargs):
        return CodexTurnResult(
            raw_text="answer",
            thread_id="thread-a",
            turn_id="turn-a",
            turn_status="completed",
            server_version="0.146.0",
        )


def test_registry_registers_resolves_rejects_unknown_and_duplicate():
    registry = GenerativeProviderRegistry()
    provider = FakeProvider()
    registry.register(provider)

    assert registry.resolve("fake") is provider
    with pytest.raises(GenerativeProviderNotFoundError):
        registry.resolve("missing")
    with pytest.raises(GenerativeProviderDuplicateError):
        registry.register(FakeProvider())


def test_bootstrap_generative_registry_resolves_ollama_and_codex(tmp_path):
    config = AppConfig(
        module_dir=tmp_path,
        host="127.0.0.1",
        port=1,
        local_config_path=tmp_path / "sources.yaml",
        store_path=tmp_path / "knowledge.sqlite",
        runtime_dir=tmp_path / "var",
    )

    codex_client = CodexAppServerClient(
        settings=CodexRuntimeSettings(
            command=("codex", "app-server", "--stdio"),
            runtime_cwd=tmp_path / "var" / "codex-runtime",
            client_name="forge-knowledge",
            client_version="0.146.0",
            request_timeout_seconds=1,
            discovery_timeout_cap_seconds=1,
            discovery_timeout_allowance_seconds=0.1,
            interrupt_grace_seconds=0.1,
            terminal_after_interrupt_seconds=0.1,
            terminate_grace_seconds=0.1,
            kill_grace_seconds=0.1,
            sync_close_timeout_seconds=3,
            loop_thread_join_timeout_seconds=0.5,
            cancellation_cleanup_timeout_seconds=0.1,
            cancellation_poll_interval_seconds=0.001,
            notification_buffer=CodexNotificationBufferPolicy(max_per_turn=100, max_turn_ids=100, max_age_seconds=30.0),
        )
    )
    registry, startup_provider = build_generative_runtime(config, codex_client=codex_client)

    assert startup_provider.provider_id == "ollama"
    assert registry.resolve("ollama").provider_id == "ollama"
    assert isinstance(registry.resolve("codex"), CodexGenerativeProvider)


def test_codex_provider_uses_turn_result_version_after_client_invalidation_race():
    provider = CodexGenerativeProvider(VersionRaceCodexClient(), timeout_seconds=3)

    response = provider.generate(GenerativeRequest(prompt="prompt", model_id="gpt-5.6-luna"))

    assert response.provider_version == "0.146.0"


def test_shared_codex_client_has_single_lifecycle_owner():
    client = CloseCountingCodexClient()
    discovery = AiRuntimeDiscoveryService(
        AiRuntimeDiscoveryRegistry([CodexAiRuntimeOptionsSource(client, cache_ttl_seconds=30.0, max_page_count=100)])
    )
    registry = GenerativeProviderRegistry()
    registry.register(CodexGenerativeProvider(client, timeout_seconds=3))
    deps = KnowledgeDependencies(
        inventory_store=None,
        analysis_store=None,
        graph_store=None,
        source_resolver=None,
        analysis_provider=None,
        analysis_supervisor=None,
        inventory_refresh=None,
        inventory_scheduler=None,
        storage_operations=None,
        ai_runtime_discovery=discovery,
        generative_registry=registry,
        codex_app_server_client=client,
    )

    asyncio.run(discovery.aclose())
    assert client.close_count == 0

    asyncio.run(deps.aclose())
    asyncio.run(deps.aclose())

    assert client.close_count == 1
    assert client.initialize_count == 0


def test_dependency_shutdown_attempts_codex_after_discovery_failure():
    discovery = FailingCleanup("discovery")
    client = CloseCountingCodexClient()
    deps = KnowledgeDependencies(
        inventory_store=None,
        analysis_store=None,
        graph_store=None,
        source_resolver=None,
        analysis_provider=None,
        analysis_supervisor=None,
        inventory_refresh=None,
        inventory_scheduler=None,
        storage_operations=None,
        ai_runtime_discovery=discovery,
        generative_registry=None,
        codex_app_server_client=client,
    )

    with pytest.raises(RuntimeError):
        asyncio.run(deps.aclose())

    assert discovery.close_count == 1
    assert client.close_count == 1


def test_dependency_shutdown_attempts_codex_after_registry_failure():
    registry = FailingCleanup("registry")
    client = CloseCountingCodexClient()
    deps = KnowledgeDependencies(
        inventory_store=None,
        analysis_store=None,
        graph_store=None,
        source_resolver=None,
        analysis_provider=None,
        analysis_supervisor=None,
        inventory_refresh=None,
        inventory_scheduler=None,
        storage_operations=None,
        ai_runtime_discovery=None,
        generative_registry=registry,
        codex_app_server_client=client,
    )

    with pytest.raises(RuntimeError):
        asyncio.run(deps.aclose())

    assert registry.close_count == 1
    assert client.close_count == 1


def test_dependency_shutdown_preserves_cancellation_after_remaining_cleanup():
    discovery = CancellingCleanup()
    registry = FailingCleanup("registry")
    client = CloseCountingCodexClient()
    deps = KnowledgeDependencies(
        inventory_store=None,
        analysis_store=None,
        graph_store=None,
        source_resolver=None,
        analysis_provider=None,
        analysis_supervisor=None,
        inventory_refresh=None,
        inventory_scheduler=None,
        storage_operations=None,
        ai_runtime_discovery=discovery,
        generative_registry=registry,
        codex_app_server_client=client,
    )

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(deps.aclose())

    assert discovery.close_count == 1
    assert registry.close_count == 1
    assert client.close_count == 1


def test_registry_closes_sync_and_async_resources():
    registry = GenerativeProviderRegistry()
    provider = FakeProvider()
    registry.register(provider)

    asyncio.run(registry.aclose())

    assert provider.aclosed is True
    assert provider.closed is True


def test_ollama_sync_generation_normalizes_request_response_and_metadata():
    captured = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(
            200,
            json={
                "response": "{\"ok\":true}",
                "done": True,
                "total_duration": 100,
                "eval_count": 2,
                "ignored": "not exported",
            },
        )

    provider = OllamaGenerativeProvider(
        "http://127.0.0.1:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    response = provider.generate(
        GenerativeRequest(
            prompt="Prompt",
            model_id="model",
            response_mode=ResponseMode.JSON_OBJECT,
            context_tokens=32768,
        )
    )

    assert captured == [
        {
            "model": "model",
            "prompt": "Prompt",
            "stream": False,
            "format": "json",
            "options": {"num_ctx": 32768},
        }
    ]
    assert response.raw_text == "{\"ok\":true}"
    assert response.provider_id == "ollama"
    assert response.model_id == "model"
    assert response.prompt_char_length == 6
    assert response.response_char_length == 11
    assert response.provider_metadata == {"done": True, "total_duration": 100, "eval_count": 2}


def test_ollama_formatter_envelope_uses_temperature_without_context():
    captured = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(200, json={"response": "{}"})

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    provider.generate(
        GenerativeRequest(
            prompt="Format",
            model_id="formatter",
            response_mode=ResponseMode.JSON_OBJECT,
            temperature=0,
        )
    )

    assert captured[0] == {
        "model": "formatter",
        "prompt": "Format",
        "stream": False,
        "format": "json",
        "options": {"temperature": 0},
    }


def test_ollama_omits_optional_options_when_absent():
    captured = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(200, json={"response": "plain"})

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(handler)),
    )

    provider.generate(GenerativeRequest(prompt="Plain", model_id="model"))

    assert "format" not in captured[0]
    assert "options" not in captured[0]


def test_ollama_returns_normal_response_for_blank_string_response():
    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"response": ""}))),
    )

    response = provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    assert response.raw_text == ""
    assert response.response_char_length == 0


def test_ollama_returns_normal_response_for_whitespace_response():
    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"response": "   "}))),
    )

    response = provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    assert response.raw_text == "   "
    assert response.response_char_length == 3


def test_ollama_async_generation_uses_same_envelope_rules():
    captured = []

    async def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content.decode("utf-8")))
        return httpx.Response(200, json={"response": "{}"})

    provider = OllamaGenerativeProvider(
        "http://[::1]:11434",
        timeout_seconds=10,
        async_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    response = asyncio.run(
        provider.generate_async(
            GenerativeRequest(
                prompt="Prompt",
                model_id="model",
                response_mode=ResponseMode.JSON_OBJECT,
                context_tokens=2048,
            )
        )
    )

    assert response.raw_text == "{}"
    assert captured[0]["options"] == {"num_ctx": 2048}


def test_ollama_sync_generation_posts_once_with_calculated_timeout():
    client = SingleCallSyncClient(httpx.Response(200, json={"response": "{}"}))
    provider = OllamaGenerativeProvider("http://localhost:11434", timeout_seconds=10, sync_client=client)  # type: ignore[arg-type]

    provider.generate(GenerativeRequest(prompt="x", model_id="m", timeout_seconds=3))

    assert len(client.calls) == 1
    assert client.calls[0]["timeout"].read == 3
    assert client.calls[0]["timeout"].connect == 3


def test_ollama_async_generation_posts_once_with_calculated_timeout():
    client = SingleCallAsyncClient(httpx.Response(200, json={"response": "{}"}))
    provider = OllamaGenerativeProvider("http://localhost:11434", timeout_seconds=10, async_client=client)  # type: ignore[arg-type]

    asyncio.run(provider.generate_async(GenerativeRequest(prompt="x", model_id="m", timeout_seconds=4)))

    assert len(client.calls) == 1
    assert client.calls[0]["timeout"].read == 4
    assert client.calls[0]["timeout"].connect == 4


def test_ollama_type_error_from_client_is_not_retried():
    client = SingleCallSyncClient(TypeError("client exploded before request serialization completed"))
    provider = OllamaGenerativeProvider("http://localhost:11434", timeout_seconds=10, sync_client=client)  # type: ignore[arg-type]

    with pytest.raises(TypeError):
        provider.generate(GenerativeRequest(prompt="x", model_id="m", timeout_seconds=3))

    assert len(client.calls) == 1


def test_ollama_failure_classification_and_localhost_validation():
    with pytest.raises(ValueError):
        OllamaGenerativeProvider("http://example.com:11434", timeout_seconds=1)

    def status_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="broken", request=request)

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(status_handler)),
    )
    with pytest.raises(GenerativeProviderTransportError) as status_exc:
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))
    assert status_exc.value.status_code == 500
    assert status_exc.value.response_text == "broken"

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, text="{"))),
    )
    with pytest.raises(GenerativeProviderProtocolError):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"done": True}))),
    )
    with pytest.raises(GenerativeProviderEmptyResponse):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"response": None}))),
    )
    with pytest.raises(GenerativeProviderProtocolError):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"response": 123}))),
    )
    with pytest.raises(GenerativeProviderProtocolError):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    def transport_failure(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route", request=request)

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(transport_failure)),
    )
    with pytest.raises(GenerativeProviderTransportError):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    def timeout_failure(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    provider = OllamaGenerativeProvider(
        "http://localhost:11434",
        timeout_seconds=10,
        sync_client=httpx.Client(transport=httpx.MockTransport(timeout_failure)),
    )
    with pytest.raises(GenerativeProviderTimeout):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))

    timeout_client = SingleCallSyncClient(httpx.ReadTimeout("timeout"))
    provider = OllamaGenerativeProvider("http://localhost:11434", timeout_seconds=10, sync_client=timeout_client)  # type: ignore[arg-type]
    with pytest.raises(GenerativeProviderTimeout):
        provider.generate(GenerativeRequest(prompt="x", model_id="m"))
    assert len(timeout_client.calls) == 1
