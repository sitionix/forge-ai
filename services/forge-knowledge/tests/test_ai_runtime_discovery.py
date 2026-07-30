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
    CodexAppServerClient,
    CodexAppServerError,
    CodexAppServerTimeout,
    OllamaAiRuntimeOptionsSource,
)


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
    ("handler", "expected_status", "expected_message"),
    [
        (
            lambda request: (_ for _ in ()).throw(httpx.ConnectError("refused", request=request)),
            UNAVAILABLE,
            "Ollama runtime is not available",
        ),
        (
            lambda request: httpx.Response(200, text="{"),
            UNAVAILABLE,
            "Ollama runtime is not available",
        ),
    ],
)
def test_ollama_runtime_unreachable_or_identity_invalid(handler, expected_status, expected_message):
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
        "message": expected_message,
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
        "message": "Ollama model catalog could not be read",
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
    assert [sent["method"] for sent in process.sent] == ["initialize", "model/list", "model/list"]
    assert process.sent[0]["params"] == {"clientInfo": {"name": "forge-knowledge", "version": "0.1.0"}}
    assert process.sent[1]["params"] == {"includeHidden": False}
    assert process.sent[2]["params"] == {"includeHidden": False, "cursor": "2"}
    assert_forbidden_public_fields_absent(result)


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
        await asyncio.sleep(0)
        first_id = process.sent[1]["id"]
        second_id = process.sent[2]["id"]
        process.push_json({"id": second_id, "result": {"data": ["second"], "nextCursor": None}})
        process.push_json({"id": first_id, "result": {"data": ["first"], "nextCursor": "2"}})
        return await first, await second

    first_result, second_result = asyncio.run(exercise())

    assert first_result["data"] == ["first"]
    assert second_result["data"] == ["second"]


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


def test_aggregate_returns_registered_provider_order_and_isolates_failures_and_timeouts():
    registry = AiRuntimeDiscoveryRegistry(
        [
            StaticSource("ollama", "Ollama", AiRuntimeProviderOptions("ollama", "Ollama", READY)),
            StaticSource("codex", "Codex", AiRuntimeProviderOptions("codex", "Codex", UNAVAILABLE, message="Codex runtime is not available")),
            StaticSource("partial", "Partial", AiRuntimeProviderOptions("partial", "Partial", DEGRADED, message="Partial catalog failed")),
            SlowSource("slow", "Slow"),
            BrokenSource("broken", "Broken"),
        ]
    )
    service = AiRuntimeDiscoveryService(registry, provider_timeout_seconds=0.01)

    result = asyncio.run(service.discover())

    assert [provider["providerId"] for provider in result["providers"]] == ["ollama", "codex", "partial", "slow", "broken"]
    assert [provider["status"] for provider in result["providers"]] == [READY, UNAVAILABLE, DEGRADED, UNAVAILABLE, UNAVAILABLE]
    assert result["providers"][2]["message"] == "Partial catalog failed"
    assert result["providers"][3]["message"] == "Slow runtime discovery timed out"
    assert result["providers"][4]["message"] == "Broken runtime is not available"


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
            while self._scripted:
                action = dict(self._scripted.pop(0))
                if action.get("defer"):
                    break
                if "exit" in action:
                    self.returncode = int(action["exit"])
                    self.stdout.push(b"")
                    self._complete_wait()
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

    async def readline(self) -> bytes:
        return await self._queue.get()

    def push(self, data: bytes) -> None:
        self._queue.put_nowait(data)


def response(result: Mapping[str, Any]) -> dict[str, Any]:
    return {"result": dict(result)}


def notification(method: str, params: Mapping[str, Any]) -> dict[str, Any]:
    return {"method": method, "params": dict(params)}


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
        "actions",
        "capabilities",
        "metadata",
        "usage",
        "limits",
        "authentication",
        "isDefault",
        "serviceTiers",
        "modelContextLimit",
    }
    if isinstance(payload, Mapping):
        assert forbidden.isdisjoint(payload.keys())
        for value in payload.values():
            assert_forbidden_public_fields_absent(value)
    elif isinstance(payload, list):
        for value in payload:
            assert_forbidden_public_fields_absent(value)
