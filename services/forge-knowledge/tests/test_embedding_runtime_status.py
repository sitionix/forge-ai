from __future__ import annotations

import asyncio

import httpx

from knowledge_service.embedding_runtime_status import OllamaEmbeddingRuntimeStatusProvider


def test_embedding_status_semantic_disabled_returns_disabled_without_probe():
    calls = 0

    async def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(500)

    provider = _provider(enabled=False, transport=httpx.MockTransport(handler))

    status = asyncio.run(provider.status())

    assert status.status == "DISABLED"
    assert status.diagnostic is None
    assert calls == 0


def test_embedding_status_provider_unreachable_is_unavailable():
    async def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("unreachable", request=request)

    provider = _provider(transport=httpx.MockTransport(handler))

    status = asyncio.run(provider.status())

    assert status.status == "UNAVAILABLE"
    assert status.diagnostic.code == "SEMANTIC_PROVIDER_UNAVAILABLE"


def test_embedding_status_model_absent_is_unavailable():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.32.5"})
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": "qwen2.5:0.5b"}]})
        return httpx.Response(500)

    provider = _provider(transport=httpx.MockTransport(handler))

    status = asyncio.run(provider.status())

    assert status.status == "UNAVAILABLE"
    assert status.provider_version == "0.32.5"
    assert status.diagnostic.code == "SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE"


def test_embedding_status_malformed_embed_response_is_degraded():
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.32.5"})
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": "embeddinggemma:latest"}]})
        if request.url.path == "/api/embed":
            return httpx.Response(200, json={"embeddings": [[]]})
        return httpx.Response(500)

    provider = _provider(transport=httpx.MockTransport(handler))

    status = asyncio.run(provider.status())

    assert status.status == "DEGRADED"
    assert status.diagnostic.code == "SEMANTIC_EMBEDDING_PROBE_FAILED"


def test_embedding_status_success_reports_ready_dimension():
    provider = _provider(transport=httpx.MockTransport(_ready_handler()))

    status = asyncio.run(provider.status())

    assert status.status == "READY"
    assert status.provider_version == "0.32.5"
    assert status.embedding_dimension == 768
    assert status.diagnostic is None


def test_embedding_status_probe_results_are_cached():
    counts = {"embed": 0}

    provider = _provider(transport=httpx.MockTransport(_ready_handler(counts=counts)), cache_ttl_seconds=30.0)

    async def run() -> None:
        first = await provider.status()
        second = await provider.status()
        assert first is second

    asyncio.run(run())

    assert counts["embed"] == 1


def test_embedding_status_concurrent_callers_share_one_probe():
    counts = {"embed": 0}

    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/embed":
            counts["embed"] += 1
            await asyncio.sleep(0.01)
            return httpx.Response(200, json={"embeddings": [[0.1 for _ in range(768)]]})
        return await _ready_handler()(request)

    provider = _provider(transport=httpx.MockTransport(handler), cache_ttl_seconds=30.0)

    async def run() -> None:
        statuses = await asyncio.gather(*(provider.status() for _ in range(8)))
        assert {status.status for status in statuses} == {"READY"}

    asyncio.run(run())

    assert counts["embed"] == 1


def test_embedding_status_cache_expiry_triggers_one_refresh():
    counts = {"embed": 0}
    provider = _provider(transport=httpx.MockTransport(_ready_handler(counts=counts)), cache_ttl_seconds=0.0)

    async def run() -> None:
        await provider.status()
        await provider.status()

    asyncio.run(run())

    assert counts["embed"] == 2


def _provider(
    *,
    enabled: bool = True,
    transport: httpx.MockTransport,
    cache_ttl_seconds: float = 30.0,
) -> OllamaEmbeddingRuntimeStatusProvider:
    return OllamaEmbeddingRuntimeStatusProvider(
        enabled=enabled,
        provider_id="ollama",
        base_url="http://127.0.0.1:11434",
        model_id="embeddinggemma",
        timeout_seconds=1,
        cache_ttl_seconds=cache_ttl_seconds,
        client=httpx.AsyncClient(transport=transport),
    )


def _ready_handler(counts: dict[str, int] | None = None):
    async def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/version":
            return httpx.Response(200, json={"version": "0.32.5"})
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": "embeddinggemma:latest"}]})
        if request.url.path == "/api/embed":
            if counts is not None:
                counts["embed"] += 1
            return httpx.Response(200, json={"embeddings": [[0.1 for _ in range(768)]]})
        return httpx.Response(404)

    return handler
