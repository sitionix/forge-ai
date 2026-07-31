from __future__ import annotations

import asyncio
import json
from typing import Any, Mapping, Sequence

import httpx
import pytest

from knowledge_service.ai_runtime_discovery import (
    DEGRADED,
    READY,
    UNAVAILABLE,
    AiRuntimeDiscoveryRegistry,
    AiRuntimeDiscoveryService,
    AiRuntimeEffortOption,
    AiRuntimeModelOption,
    AiRuntimeProviderOptions,
    CodexAiRuntimeOptionsSource,
    OllamaAiRuntimeOptionsSource,
)
from knowledge_service.codex_app_server import CodexAppServerClient, CodexAppServerError, CodexAppServerTimeout


def _connect_refused(request: httpx.Request) -> httpx.Response:
    raise httpx.ConnectError("refused", request=request)


def test_public_provider_model_and_effort_shape_omits_absent_optionals():
    provider = AiRuntimeProviderOptions(
        provider_id="codex",
        display_name="Codex",
        status=READY,
        version="0.146.0",
        models=(
            AiRuntimeModelOption(
                model_id="gpt-5.6-sol",
                display_name="GPT-5.6-Sol",
                description="Latest frontier agentic coding model.",
                efforts=(AiRuntimeEffortOption("low", "Fast responses with lighter reasoning"),),
            ),
            AiRuntimeModelOption(model_id="qwen2.5-coder:14b", display_name="qwen2.5-coder:14b"),
        ),
    )

    payload = provider.public_dict()

    assert payload == {
        "providerId": "codex",
        "displayName": "Codex",
        "status": "READY",
        "version": "0.146.0",
        "models": [
            {
                "modelId": "gpt-5.6-sol",
                "displayName": "GPT-5.6-Sol",
                "description": "Latest frontier agentic coding model.",
                "efforts": [{"effortId": "low", "description": "Fast responses with lighter reasoning"}],
            },
            {"modelId": "qwen2.5-coder:14b", "displayName": "qwen2.5-coder:14b"},
        ],
    }
    assert_forbidden_public_fields_absent(payload)


def test_ollama_maps_version_tags_completion_models_and_modified_at_without_show_calls():
    seen: list[tuple[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.method, request.url.path))
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.30.6"})
        if request.url.path == "/api/tags":
            return httpx.Response(
                200,
                json={
                    "models": [
                        {
                            "name": "qwen2.5-coder:14b",
                            "model": "qwen2.5-coder:14b",
                            "modified_at": "2026-06-14T11:24:58.816615534+03:00",
                            "capabilities": ["completion", "tools"],
                            "digest": "hidden",
                        },
                        {
                            "name": "embeddinggemma:latest",
                            "model": "embeddinggemma:latest",
                            "modified_at": "2026-06-07T15:54:20.901357178+03:00",
                            "capabilities": ["embedding"],
                        },
                    ]
                },
            )
        raise AssertionError(f"unexpected Ollama path: {request.url.path}")

    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(handler)),
    )

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": "READY",
        "version": "0.30.6",
        "models": [
            {
                "modelId": "qwen2.5-coder:14b",
                "displayName": "qwen2.5-coder:14b",
                "modifiedAt": "2026-06-14T11:24:58.816615534+03:00",
            }
        ],
    }
    assert seen == [("GET", "/api/version"), ("GET", "/api/tags")]


def test_ollama_runtime_absent_from_first_request_returns_unavailable():
    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(_connect_refused)),
    )

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": "UNAVAILABLE",
        "models": [],
    }


def test_ollama_ready_then_cache_expiry_then_runtime_absent_returns_unavailable():
    available = True
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if not available:
            raise httpx.ConnectError("refused", request=request)
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.30.6"})
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": "m", "model": "m", "capabilities": ["completion"]}]})
        raise AssertionError(f"unexpected Ollama path: {request.url.path}")

    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(handler)),
    )

    first = asyncio.run(source.discover()).public_dict()
    available = False
    if source._catalog_cache is not None:
        source._catalog_cache.expires_at = 0.0
    second = asyncio.run(source.discover()).public_dict()

    assert first["status"] == READY
    assert second == {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": "UNAVAILABLE",
        "models": [],
    }
    assert seen == ["/api/version", "/api/tags", "/api/version"]


def test_ollama_ready_then_health_success_malformed_catalog_returns_degraded():
    degraded_catalog = False
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.path)
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.30.6"})
        if request.url.path == "/api/tags" and degraded_catalog:
            return httpx.Response(200, text="{")
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": "m", "model": "m", "capabilities": ["completion"]}]})
        raise AssertionError(f"unexpected Ollama path: {request.url.path}")

    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(handler)),
    )

    first = asyncio.run(source.discover()).public_dict()
    degraded_catalog = True
    if source._catalog_cache is not None:
        source._catalog_cache.expires_at = 0.0
    second = asyncio.run(source.discover()).public_dict()

    assert first["status"] == READY
    assert second == {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": "DEGRADED",
        "models": [],
        "version": "0.30.6",
    }
    assert seen == ["/api/version", "/api/tags", "/api/version", "/api/tags"]


def test_ollama_accepts_capabilities_from_details_when_explicitly_present():
    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(
            base_url="http://127.0.0.1:11434",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={"version": "0.30.6"}
                    if request.url.path == "/api/version"
                    else {"models": [{"name": "m", "model": "m", "details": {"capabilities": ["completion"]}}]},
                )
            ),
        ),
    )

    result = asyncio.run(source.discover())

    assert [model.model_id for model in result.models] == ["m"]


@pytest.mark.parametrize(
    ("handler", "expected_status"),
    [
        (
            lambda request: (_ for _ in ()).throw(httpx.ConnectError("refused", request=request)),
            UNAVAILABLE,
        ),
        (
            lambda request: httpx.Response(200, text="{"),
            UNAVAILABLE,
        ),
    ],
)
def test_ollama_runtime_unreachable_or_identity_invalid(handler, expected_status):
    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(handler)),
    )

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": expected_status,
        "models": [],
    }


@pytest.mark.parametrize(
    "tags_response",
    [
        httpx.Response(503, json={"error": "down"}),
        httpx.Response(200, text="{"),
        httpx.Response(200, json={"models": [{"name": "missing-model", "capabilities": ["completion"]}]}),
    ],
)
def test_ollama_catalog_failure_after_identity_success_is_degraded(tags_response):
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.30.6"})
        return tags_response

    source = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(handler)),
    )

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": "DEGRADED",
        "models": [],
        "version": "0.30.6",
    }


def test_codex_initializes_and_maps_model_list_efforts_pagination_and_hidden_filtering():
    process = FakeCodexProcess(
        [
            notification("remoteControl/status/changed", {"status": "disabled"}),
            response(
                {
                    "userAgent": "forge-knowledge/0.146.0 (Mac OS 26.5.2; arm64)",
                    "codexHome": "/Users/test/.codex",
                    "platformFamily": "unix",
                    "platformOs": "macos",
                }
            ),
            response(
                {
                    "data": [
                        codex_model("gpt-5.6-sol", "GPT-5.6-Sol", hidden=False),
                        codex_model("hidden", "Hidden", hidden=True),
                    ],
                    "nextCursor": "2",
                }
            ),
            response({"data": [codex_model("gpt-5.6-luna", "GPT-5.6-Luna", efforts=[])], "nextCursor": None}),
        ]
    )
    client = CodexAppServerClient(process_factory=lambda command: async_value(process))
    source = CodexAiRuntimeOptionsSource(client)

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "codex",
        "displayName": "Codex",
        "status": "READY",
        "version": "0.146.0",
        "models": [
            {
                "modelId": "gpt-5.6-sol",
                "displayName": "GPT-5.6-Sol",
                "description": "desc gpt-5.6-sol",
                "efforts": [{"effortId": "low", "description": "Fast"}],
            },
            {
                "modelId": "gpt-5.6-luna",
                "displayName": "GPT-5.6-Luna",
                "description": "desc gpt-5.6-luna",
            },
        ],
    }
    assert [sent["method"] for sent in process.sent] == ["initialize", "initialized", "model/list", "model/list"]
    assert process.sent[0]["params"] == {"clientInfo": {"name": "forge-knowledge", "version": "0.1.0"}}
    assert process.sent[2]["params"] == {"includeHidden": False}
    assert process.sent[3]["params"] == {"includeHidden": False, "cursor": "2"}
    assert_forbidden_public_fields_absent(result)


def test_codex_executable_absent_returns_unavailable():
    async def missing_process(command: Sequence[str]) -> Any:
        raise FileNotFoundError(command[0])

    source = CodexAiRuntimeOptionsSource(CodexAppServerClient(process_factory=missing_process))

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "codex",
        "displayName": "Codex",
        "status": "UNAVAILABLE",
        "models": [],
    }


def test_codex_cached_catalog_does_not_mask_current_runtime_absence():
    first_process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            response({"data": [codex_model("gpt-5.6-sol", "GPT-5.6-Sol")], "nextCursor": None}),
        ]
    )
    processes: list[FakeCodexProcess] = [first_process]

    async def process_factory(command: Sequence[str]) -> Any:
        if not processes:
            raise FileNotFoundError(command[0])
        return processes.pop(0)

    source = CodexAiRuntimeOptionsSource(CodexAppServerClient(process_factory=process_factory))

    async def exercise():
        first = await source.discover()
        first_process.terminate()
        second = await source.discover()
        return first.public_dict(), second.public_dict()

    first, second = asyncio.run(exercise())

    assert first["status"] == READY
    assert second == {
        "providerId": "codex",
        "displayName": "Codex",
        "status": "UNAVAILABLE",
        "models": [],
    }


def test_codex_client_correlates_out_of_order_responses():
    process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            {"defer": True},
            {"defer": True},
        ],
    )
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1)

    async def exercise():
        await client.initialize()
        first = asyncio.create_task(client.request("model/list", {"includeHidden": False}))
        second = asyncio.create_task(client.request("model/list", {"includeHidden": False, "cursor": "2"}))
        while len(process.sent) < 4:
            await asyncio.sleep(0)
        first_id = process.sent[2]["id"]
        second_id = process.sent[3]["id"]
        process.push_json({"id": second_id, "result": {"data": ["second"], "nextCursor": None}})
        process.push_json({"id": first_id, "result": {"data": ["first"], "nextCursor": "2"}})
        return await first, await second

    first_result, second_result = asyncio.run(exercise())

    assert first_result["data"] == ["first"]
    assert second_result["data"] == ["second"]


def test_codex_initialize_timeout_cleans_process_and_next_discovery_restarts():
    timeout_process = FakeCodexProcess([{"defer": True}])
    restart_process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            response({"data": [codex_model("gpt-5.6-sol", "GPT-5.6-Sol")], "nextCursor": None}),
        ]
    )
    processes = [timeout_process, restart_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), request_timeout_seconds=0.01)
    source = CodexAiRuntimeOptionsSource(client)

    async def exercise():
        first = await source.discover()
        assert client._pending == {}
        assert client._process is None
        assert client._reader_task is None
        assert client._stderr_task is None
        second = await source.discover()
        await client.aclose()
        await client.aclose()
        return first, second

    first, second = asyncio.run(exercise())

    assert first.status == UNAVAILABLE
    assert timeout_process.terminated is True
    assert second.status == READY
    assert restart_process.terminated is True


def test_codex_initialize_error_cleans_process_and_pending_requests():
    process = FakeCodexProcess([jsonrpc_error(-32000, "initialize failed")])
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1)

    with pytest.raises(CodexAppServerError):
        asyncio.run(client.initialize())

    assert process.terminated is True
    assert client._pending == {}
    assert client._process is None
    assert client._reader_task is None
    assert client._stderr_task is None


def test_codex_initialize_unusable_response_cleans_process_and_pending_requests():
    process = FakeCodexProcess([response({"platformFamily": "unix"})])
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1)

    with pytest.raises(CodexAppServerError):
        asyncio.run(client.initialize())

    assert process.terminated is True
    assert client._pending == {}
    assert client._process is None
    assert client._reader_task is None
    assert client._stderr_task is None


def test_codex_initialize_cancellation_cleans_process_and_pending_requests():
    process = FakeCodexProcess([{"defer": True}])
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1)

    async def exercise():
        task = asyncio.create_task(client.initialize())
        while not process.sent:
            await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(exercise())

    assert process.terminated is True
    assert client._pending == {}
    assert client._process is None
    assert client._reader_task is None
    assert client._stderr_task is None


def test_codex_request_cancellation_clears_pending_entry_and_shutdown_is_idempotent():
    process = FakeCodexProcess([response({"userAgent": "forge-knowledge/0.146.0"}), {"defer": True}])
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1)

    async def exercise():
        await client.initialize()
        task = asyncio.create_task(client.request("model/list", {"includeHidden": False}))
        while len(process.sent) < 3:
            await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        for _ in range(20):
            if client._pending == {}:
                break
            await asyncio.sleep(0.01)
        assert client._pending == {}
        await client.aclose()
        await client.aclose()

    asyncio.run(exercise())

    assert process.terminated is True


def test_codex_request_timeout_process_exit_restart_and_shutdown():
    timeout_process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            {"defer": True},
        ]
    )
    restart_process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            response({"data": [codex_model("gpt-5.6-sol", "GPT-5.6-Sol")], "nextCursor": None}),
        ]
    )
    processes = [timeout_process, restart_process]
    client = CodexAppServerClient(process_factory=lambda command: async_value(processes.pop(0)), request_timeout_seconds=0.01)

    async def exercise():
        await client.initialize()
        with pytest.raises(CodexAppServerTimeout):
            await client.request("model/list", {"includeHidden": False})
        timeout_process.terminate()
        source = CodexAiRuntimeOptionsSource(client)
        result = await source.discover()
        await client.aclose()
        return result

    result = asyncio.run(exercise())

    assert result.status == READY
    assert timeout_process.terminated is True
    assert restart_process.terminated is True


def test_codex_exit_before_response_is_reported_as_unavailable_or_degraded():
    process = FakeCodexProcess([{"exit": 1}])
    client = CodexAppServerClient(process_factory=lambda command: async_value(process), request_timeout_seconds=1)

    with pytest.raises(CodexAppServerError):
        asyncio.run(client.initialize())


@pytest.mark.parametrize(
    "scripted",
    [
        [
            {"result": {"userAgent": "forge-knowledge/0.146.0"}},
            {"result": {"data": [], "nextCursor": "A"}},
            {"result": {"data": [], "nextCursor": "A"}},
        ],
        [
            {"result": {"userAgent": "forge-knowledge/0.146.0"}},
            {"result": {"data": [], "nextCursor": "A"}},
            {"result": {"data": [], "nextCursor": "B"}},
            {"result": {"data": [], "nextCursor": "A"}},
        ],
    ],
)
def test_codex_repeated_or_cyclic_pagination_cursor_returns_degraded(scripted):
    process = FakeCodexProcess(scripted)
    source = CodexAiRuntimeOptionsSource(CodexAppServerClient(process_factory=lambda command: async_value(process)))

    result = asyncio.run(source.discover()).public_dict()

    assert result == {
        "providerId": "codex",
        "displayName": "Codex",
        "status": "DEGRADED",
        "models": [],
        "version": "0.146.0",
    }


def test_codex_pagination_page_limit_returns_degraded():
    process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            response({"data": [], "nextCursor": "A"}),
            response({"data": [], "nextCursor": "B"}),
        ]
    )
    source = CodexAiRuntimeOptionsSource(
        CodexAppServerClient(process_factory=lambda command: async_value(process)),
        max_page_count=2,
    )

    result = asyncio.run(source.discover()).public_dict()

    assert result["status"] == DEGRADED
    assert [sent["method"] for sent in process.sent] == ["initialize", "initialized", "model/list", "model/list"]


def test_aggregate_returns_registered_provider_order_and_isolates_failures_and_timeouts():
    registry = AiRuntimeDiscoveryRegistry(
        [
            StaticSource("ollama", "Ollama", AiRuntimeProviderOptions("ollama", "Ollama", READY)),
            StaticSource("codex", "Codex", AiRuntimeProviderOptions("codex", "Codex", UNAVAILABLE)),
            StaticSource("partial", "Partial", AiRuntimeProviderOptions("partial", "Partial", DEGRADED)),
            SlowSource("slow", "Slow"),
            BrokenSource("broken", "Broken"),
        ]
    )
    service = AiRuntimeDiscoveryService(registry, provider_timeout_seconds=0.01)

    result = asyncio.run(service.discover())

    assert [provider["providerId"] for provider in result["providers"]] == ["ollama", "codex", "partial", "slow", "broken"]
    assert [provider["status"] for provider in result["providers"]] == [READY, UNAVAILABLE, DEGRADED, UNAVAILABLE, UNAVAILABLE]
    assert all("message" not in provider for provider in result["providers"])


def test_aggregate_retains_registered_ollama_absent_and_codex_ready():
    ollama = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(_connect_refused)),
    )
    codex_process = FakeCodexProcess(
        [
            response({"userAgent": "forge-knowledge/0.146.0"}),
            response({"data": [codex_model("gpt-5.6-sol", "GPT-5.6-Sol")], "nextCursor": None}),
        ]
    )
    codex = CodexAiRuntimeOptionsSource(CodexAppServerClient(process_factory=lambda command: async_value(codex_process)))
    service = AiRuntimeDiscoveryService(AiRuntimeDiscoveryRegistry([ollama, codex]))

    result = asyncio.run(service.discover())

    assert [provider["providerId"] for provider in result["providers"]] == ["ollama", "codex"]
    assert [provider["status"] for provider in result["providers"]] == [UNAVAILABLE, READY]


def test_aggregate_retains_registered_ollama_ready_and_codex_absent():
    ollama = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(
            base_url="http://127.0.0.1:11434",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={"version": "0.30.6"}
                    if request.url.path == "/api/version"
                    else {"models": [{"name": "m", "model": "m", "capabilities": ["completion"]}]},
                )
            ),
        ),
    )

    async def missing_process(command: Sequence[str]) -> Any:
        raise FileNotFoundError(command[0])

    codex = CodexAiRuntimeOptionsSource(CodexAppServerClient(process_factory=missing_process))
    service = AiRuntimeDiscoveryService(AiRuntimeDiscoveryRegistry([ollama, codex]))

    result = asyncio.run(service.discover())

    assert [provider["providerId"] for provider in result["providers"]] == ["ollama", "codex"]
    assert [provider["status"] for provider in result["providers"]] == [READY, UNAVAILABLE]


def test_aggregate_retains_both_registered_providers_when_both_absent():
    ollama = OllamaAiRuntimeOptionsSource(
        "http://127.0.0.1:11434",
        http_client=httpx.AsyncClient(base_url="http://127.0.0.1:11434", transport=httpx.MockTransport(_connect_refused)),
    )

    async def missing_process(command: Sequence[str]) -> Any:
        raise FileNotFoundError(command[0])

    codex = CodexAiRuntimeOptionsSource(CodexAppServerClient(process_factory=missing_process))
    service = AiRuntimeDiscoveryService(AiRuntimeDiscoveryRegistry([ollama, codex]))

    result = asyncio.run(service.discover())

    assert result["providers"] == [
        {
            "providerId": "ollama",
            "displayName": "Ollama",
            "status": "UNAVAILABLE",
            "models": [],
        },
        {
            "providerId": "codex",
            "displayName": "Codex",
            "status": "UNAVAILABLE",
            "models": [],
        },
    ]


class StaticSource:
    def __init__(self, provider_id: str, display_name: str, result: AiRuntimeProviderOptions) -> None:
        self.provider_id = provider_id
        self.display_name = display_name
        self._result = result

    async def discover(self) -> AiRuntimeProviderOptions:
        return self._result


class BrokenSource:
    def __init__(self, provider_id: str, display_name: str) -> None:
        self.provider_id = provider_id
        self.display_name = display_name

    async def discover(self) -> AiRuntimeProviderOptions:
        raise RuntimeError("boom")


class SlowSource:
    def __init__(self, provider_id: str, display_name: str) -> None:
        self.provider_id = provider_id
        self.display_name = display_name

    async def discover(self) -> AiRuntimeProviderOptions:
        await asyncio.sleep(10)
        return AiRuntimeProviderOptions(self.provider_id, self.display_name, READY)


class FakeCodexProcess:
    def __init__(self, scripted: Sequence[Mapping[str, Any]]) -> None:
        self.stdin = FakeStdin(self)
        self.stdout = FakeStream()
        self.stderr = FakeStream()
        self.returncode: int | None = None
        self.sent: list[dict[str, Any]] = []
        self.terminated = False
        self._scripted = list(scripted)
        self._wait: asyncio.Future[int | None] | None = None

    def receive(self, data: bytes) -> None:
        for line in data.decode("utf-8").splitlines():
            request = json.loads(line)
            self.sent.append(request)
            if "id" not in request:
                continue
            while self._scripted:
                action = dict(self._scripted.pop(0))
                if action.get("defer"):
                    break
                if "exit" in action:
                    self.returncode = int(action["exit"])
                    self.stdout.push(b"")
                    self._complete_wait()
                    break
                if "error" in action:
                    self.push_json({"id": request["id"], "error": action["error"]})
                    break
                if "method" in action:
                    self.push_json(action)
                    continue
                payload = {"id": request["id"], "result": action["result"]}
                self.push_json(payload)
                break

    def push_json(self, payload: Mapping[str, Any]) -> None:
        self.stdout.push(json.dumps(payload).encode("utf-8") + b"\n")

    def terminate(self) -> None:
        self.terminated = True
        self.returncode = 0
        self.stdout.push(b"")
        self.stderr.push(b"")
        self._complete_wait()

    def kill(self) -> None:
        self.terminate()

    async def wait(self) -> int:
        if self.returncode is not None:
            return self.returncode
        if self._wait is None:
            self._wait = asyncio.get_running_loop().create_future()
        return await self._wait

    def _complete_wait(self) -> None:
        if self._wait is not None and not self._wait.done():
            self._wait.set_result(self.returncode)


class FakeStdin:
    def __init__(self, process: FakeCodexProcess) -> None:
        self._process = process

    def write(self, data: bytes) -> None:
        self._process.receive(data)

    async def drain(self) -> None:
        return None


class FakeStream:
    def __init__(self) -> None:
        self._queue: asyncio.Queue[bytes] = asyncio.Queue()
        self._loop: asyncio.AbstractEventLoop | None = None

    async def readline(self) -> bytes:
        self._loop = asyncio.get_running_loop()
        return await self._queue.get()

    def push(self, data: bytes) -> None:
        if self._loop is not None and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._queue.put_nowait, data)
        else:
            self._queue.put_nowait(data)


def response(result: Mapping[str, Any]) -> dict[str, Any]:
    return {"result": dict(result)}


def notification(method: str, params: Mapping[str, Any]) -> dict[str, Any]:
    return {"method": method, "params": dict(params)}


def jsonrpc_error(code: int, message: str) -> dict[str, Any]:
    return {"error": {"code": code, "message": message}}


def codex_model(model_id: str, display_name: str, *, hidden: bool = False, efforts: list[dict[str, str]] | None = None) -> dict[str, Any]:
    return {
        "id": model_id,
        "model": model_id,
        "displayName": display_name,
        "description": f"desc {model_id}",
        "hidden": hidden,
        "supportedReasoningEfforts": efforts if efforts is not None else [{"reasoningEffort": "low", "description": "Fast"}],
        "isDefault": True,
        "serviceTiers": [{"id": "priority"}],
        "defaultReasoningEffort": "low",
    }


async def async_value(value):
    return value


def assert_forbidden_public_fields_absent(payload: Any) -> None:
    forbidden = {
        "schemaVersion",
        "currentSelection",
        "activeSelection",
        "actions",
        "applyEnabled",
        "profiles",
        "capabilities",
        "metadata",
        "message",
        "usage",
        "limits",
        "rateLimits",
        "authentication",
        "account",
        "isDefault",
        "serviceTiers",
        "speedTiers",
        "runningModels",
        "loadedModels",
        "VRAM",
        "sizeBytes",
        "parameterSize",
        "quantization",
        "family",
        "modelContextLimit",
        "configuredContextTokens",
        "embeddingLength",
        "digest",
    }
    if isinstance(payload, Mapping):
        assert forbidden.isdisjoint(payload.keys())
        for value in payload.values():
            assert_forbidden_public_fields_absent(value)
    elif isinstance(payload, list):
        for value in payload:
            assert_forbidden_public_fields_absent(value)
