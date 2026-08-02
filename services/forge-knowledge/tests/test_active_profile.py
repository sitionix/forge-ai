from __future__ import annotations

import asyncio
import json
import logging
import sqlite3
from pathlib import Path
from typing import Any

import pytest
from support import AsgiTestClient, build_test_app, write_runtime_config

from knowledge_service.active_profile import (
    ActiveLlmEffortResponse,
    ActiveLlmProfilePutRequest,
    ActiveLlmRuntime,
    ActiveLlmSelectionResponse,
    ActiveProfileService,
    ActiveProfileStore,
    ActiveRuntimeGenerativeProvider,
    LlmUsageRegistry,
    PersistedActiveProfile,
)
from knowledge_service.ai_runtime_discovery import AiRuntimeProfileMetadata
from knowledge_service.codex_app_server import CodexProtocol
from knowledge_service.codex_usage import CodexLlmUsageSource
from knowledge_service.generative_runtime import GenerativeProviderRegistry, GenerativeRequest, GenerativeResponse


class FakeDiscovery:
    def __init__(self, providers: list[dict[str, Any]], *, error: Exception | None = None, cached: bool = False) -> None:
        self.providers = providers
        self.error = error
        self.cached = cached
        self.discover_calls = 0

    async def discover(self) -> dict[str, Any]:
        self.discover_calls += 1
        if self.error is not None:
            raise self.error
        return {"providers": self.providers}

    def cached_profile_metadata(self, provider_id: str, model_id: str) -> AiRuntimeProfileMetadata:
        if self.error is not None:
            raise self.error
        if not self.cached:
            return AiRuntimeProfileMetadata(provider_display_name=None, model_display_name=None)
        for provider in self.providers:
            if provider.get("providerId") != provider_id:
                continue
            model_display_name = None
            for model in provider.get("models") or []:
                if isinstance(model, dict) and model.get("modelId") == model_id:
                    model_display_name = model.get("displayName")
                    break
            return AiRuntimeProfileMetadata(
                provider_display_name=provider.get("displayName"),
                model_display_name=model_display_name,
            )
        return AiRuntimeProfileMetadata(provider_display_name=None, model_display_name=None)


class FakeUsageSource:
    def __init__(self, value=None, error: Exception | None = None, provider_id: str = "codex") -> None:
        self.value = value
        self.error = error
        self.provider_id = provider_id

    async def usage(self):
        if self.error is not None:
            raise self.error
        return self.value


class FakeCodexUsageClient:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.payload = payload

    async def request(self, method: str, params=None):
        assert method == CodexProtocol.RATE_LIMITS_READ
        return self.payload


class RecordingProvider:
    provider_id = "ollama"
    provider_version = "1"

    def __init__(self) -> None:
        self.requests: list[GenerativeRequest] = []

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        self.requests.append(request)
        return GenerativeResponse(
            raw_text=request.model_id,
            provider_id=self.provider_id,
            provider_version=self.provider_version,
            model_id=request.model_id,
            duration_ms=1,
            prompt_char_length=len(request.prompt),
            prompt_hash="p",
            response_char_length=len(request.model_id),
            response_hash="r",
        )

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse:
        return self.generate(request)


def ollama_ready(*models: str) -> dict[str, Any]:
    return {
        "providerId": "ollama",
        "displayName": "Ollama Runtime",
        "status": "READY",
        "models": [{"modelId": model, "displayName": f"Display {model}"} for model in models],
    }


def codex_ready() -> dict[str, Any]:
    return {
        "providerId": "codex",
        "displayName": "Codex",
        "status": "READY",
        "models": [
            {
                "modelId": "gpt-5.6-luna",
                "displayName": "GPT-5.6-Luna",
                "efforts": [{"effortId": "low", "description": "Fast"}, {"effortId": "high", "description": "Deep"}],
            }
        ],
    }


def test_usage_registry_rejects_blank_and_duplicate_provider_ids():
    registry = LlmUsageRegistry()
    registry.register(FakeUsageSource(provider_id="codex"))

    with pytest.raises(ValueError):
        registry.register(FakeUsageSource(provider_id=" Codex "))
    with pytest.raises(ValueError):
        registry.register(FakeUsageSource(provider_id=" "))


def test_get_active_profile_initializes_from_current_configuration_exact_contract(tmp_path: Path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    assert deps.active_profile_service is not None

    with AsgiTestClient(app) as client:
        response = client.get("/api/v1/knowledge/active-profile")

    assert response.status_code == 200
    assert response.json() == {
        "revision": 1,
            "llmProfile": {
                "providerId": "ollama",
                "modelId": "qwen2.5-coder:14b",
                "effort": None,
                "providerDisplayName": None,
                "modelDisplayName": None,
            },
        "usage": None,
    }


def test_active_profile_get_adds_display_metadata_without_persisting_it(tmp_path: Path):
    store = ActiveProfileStore(tmp_path / "active.sqlite")
    persisted = store.init(provider_id="ollama", model_id="qwen")
    runtime = _runtime(persisted)
    discovery = FakeDiscovery([ollama_ready("qwen")], cached=True)
    service = ActiveProfileService(
        store,
        runtime,
        discovery,
        LlmUsageRegistry(),
    )

    response = asyncio.run(service.get_active_profile())

    assert response.llmProfile.providerDisplayName == "Ollama Runtime"
    assert response.llmProfile.modelDisplayName == "Display qwen"
    assert discovery.discover_calls == 0
    with sqlite3.connect(store.db_path) as conn:
        stored = json.loads(conn.execute("SELECT profile_json FROM active_profile").fetchone()[0])
    assert stored == {"llmProfile": {"providerId": "ollama", "modelId": "qwen", "effort": None}}


def test_active_profile_get_keeps_ids_when_display_metadata_lookup_fails(tmp_path: Path):
    store = ActiveProfileStore(tmp_path / "active.sqlite")
    persisted = store.init(provider_id="ollama", model_id="qwen")
    service = ActiveProfileService(
        store,
        _runtime(persisted),
        FakeDiscovery([], error=RuntimeError("catalog unavailable")),
        LlmUsageRegistry(),
    )

    response = asyncio.run(service.get_active_profile())

    assert response.llmProfile.providerId == "ollama"
    assert response.llmProfile.modelId == "qwen"
    assert response.llmProfile.providerDisplayName is None
    assert response.llmProfile.modelDisplayName is None


def test_existing_profile_is_not_overwritten_on_restart_and_usage_is_not_persisted(tmp_path: Path):
    config = write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b")
    first, _, first_config, first_deps = build_test_app(config)
    first_deps.active_profile_service = _active_profile_service(
        first_config,
        first_deps,
        FakeDiscovery([ollama_ready("qwen2.5-coder:14b", "qwen2.5-coder:32b")]),
    )
    with AsgiTestClient(first) as client:
        put = client.put(
            "/api/v1/knowledge/active-profile/llm-profile",
            {
                "expectedRevision": 1,
                "providerId": "ollama",
                "modelId": "qwen2.5-coder:32b",
                "effort": None,
            },
        )
    assert put.status_code == 200

    restarted, *_ = build_test_app(config)
    with AsgiTestClient(restarted) as client:
        response = client.get("/api/v1/knowledge/active-profile")
    assert response.json()["revision"] == 2
    assert response.json()["llmProfile"]["modelId"] == "qwen2.5-coder:32b"

    with sqlite3.connect(first_config.store_path) as conn:
        stored = conn.execute("SELECT profile_json FROM active_profile WHERE singleton_id = 'active'").fetchone()[0]
    assert "usage" not in json.loads(stored)
    assert "providerDisplayName" not in stored


def test_put_replaces_llm_profile_increments_revision_and_updates_status(tmp_path: Path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service = _active_profile_service(
        app_config,
        deps,
        FakeDiscovery([ollama_ready("qwen2.5-coder:14b", "qwen2.5-coder:32b")]),
    )

    with AsgiTestClient(app) as client:
        put = client.put(
            "/api/v1/knowledge/active-profile/llm-profile",
            {
                "expectedRevision": 1,
                "providerId": "ollama",
                "modelId": "qwen2.5-coder:32b",
                "effort": None,
            },
        )
        status = client.get("/api/v1/knowledge/status")

    assert put.status_code == 200
    assert put.json() == {
        "revision": 2,
        "llmProfile": {
            "providerId": "ollama",
            "modelId": "qwen2.5-coder:32b",
            "effort": None,
        },
    }
    assert status.json()["generative"]["revision"] == 2
    assert status.json()["generative"]["providerId"] == "ollama"
    assert status.json()["generative"]["modelId"] == "qwen2.5-coder:32b"


def test_put_creates_singleton_when_record_is_absent(tmp_path: Path):
    store = ActiveProfileStore(tmp_path / "active.sqlite")
    initial = PersistedActiveProfile(
        revision=1,
        llm_profile=ActiveLlmSelectionResponse(providerId="ollama", modelId="qwen2.5-coder:14b", effort=None),
    )
    service = ActiveProfileService(
        store,
        _runtime(initial),
        FakeDiscovery([ollama_ready("qwen2.5-coder:14b")]),
        LlmUsageRegistry(),
    )

    response = asyncio.run(
        service.replace_llm_profile(
            ActiveLlmProfilePutRequest(
                expectedRevision=1,
                providerId="ollama",
                modelId="qwen2.5-coder:14b",
                effort=None,
            )
        )
    )

    assert response.revision == 2
    assert response.llmProfile == ActiveLlmSelectionResponse(providerId="ollama", modelId="qwen2.5-coder:14b", effort=None)


def test_stale_revision_returns_409(tmp_path: Path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service = _active_profile_service(
        app_config,
        deps,
        FakeDiscovery([ollama_ready("qwen2.5-coder:14b", "qwen2.5-coder:32b")]),
    )

    with AsgiTestClient(app) as client:
        first = client.put(
            "/api/v1/knowledge/active-profile/llm-profile",
            {"expectedRevision": 1, "providerId": "ollama", "modelId": "qwen2.5-coder:32b", "effort": None},
        )
        stale = client.put(
            "/api/v1/knowledge/active-profile/llm-profile",
            {"expectedRevision": 1, "providerId": "ollama", "modelId": "qwen2.5-coder:14b", "effort": None},
        )

    assert first.status_code == 200
    assert stale.status_code == 409
    assert stale.json()["code"] == "ACTIVE_PROFILE_REVISION_CONFLICT"


def test_active_llm_profile_put_request_accepts_explicit_effort_contract():
    request = ActiveLlmProfilePutRequest.parse_obj(
        {
            "expectedRevision": 1,
            "providerId": "codex",
            "modelId": "gpt-5.6-luna",
            "effort": {"effortId": "high"},
        }
    )

    assert request.dict() == {
        "expectedRevision": 1,
        "providerId": "codex",
        "modelId": "gpt-5.6-luna",
        "effort": {"effortId": "high"},
    }


def test_put_validation_errors_leave_previous_profile_active(tmp_path: Path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service = _active_profile_service(
        app_config,
        deps,
        FakeDiscovery([
            ollama_ready("qwen2.5-coder:14b"),
            {"providerId": "offline", "displayName": "Offline", "status": "UNAVAILABLE", "models": []},
            codex_ready(),
        ]),
    )

    cases = [
        ({"expectedRevision": 1, "providerId": "missing", "modelId": "x", "effort": None}, "ACTIVE_LLM_PROVIDER_NOT_FOUND"),
        ({"expectedRevision": 1, "providerId": "offline", "modelId": "x", "effort": None}, "ACTIVE_LLM_PROVIDER_UNAVAILABLE"),
        ({"expectedRevision": 1, "providerId": "ollama", "modelId": "missing", "effort": None}, "ACTIVE_LLM_MODEL_NOT_FOUND"),
        (
            {"expectedRevision": 1, "providerId": "ollama", "modelId": "qwen2.5-coder:14b", "effort": {"effortId": "high"}},
            "ACTIVE_LLM_EFFORT_NOT_SUPPORTED",
        ),
        ({"expectedRevision": 1, "providerId": "codex", "modelId": "gpt-5.6-luna", "effort": None}, "ACTIVE_LLM_EFFORT_REQUIRED"),
        (
            {"expectedRevision": 1, "providerId": "codex", "modelId": "gpt-5.6-luna", "effort": {"effortId": "unknown"}},
            "ACTIVE_LLM_EFFORT_NOT_SUPPORTED",
        ),
    ]

    with AsgiTestClient(app) as client:
        for body, code in cases:
            response = client.put("/api/v1/knowledge/active-profile/llm-profile", body)
            assert response.status_code in {400, 404, 409}
            assert response.json()["code"] == code
            current = client.get("/api/v1/knowledge/active-profile").json()
            assert current["revision"] == 1
            assert current["llmProfile"]["modelId"] == "qwen2.5-coder:14b"


def test_codex_activation_is_executable_when_provider_and_effort_are_valid(tmp_path: Path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service = _active_profile_service(
        app_config,
        deps,
        FakeDiscovery([ollama_ready("qwen2.5-coder:14b"), codex_ready()]),
    )

    with AsgiTestClient(app) as client:
        response = client.put(
            "/api/v1/knowledge/active-profile/llm-profile",
            {
                "expectedRevision": 1,
                "providerId": "codex",
                "modelId": "gpt-5.6-luna",
                "effort": {"effortId": "high"},
            },
        )

    assert response.status_code == 200
    assert response.json() == {
        "revision": 2,
        "llmProfile": {
            "providerId": "codex",
            "modelId": "gpt-5.6-luna",
            "effort": {"effortId": "high"},
        },
    }


def test_usage_registry_handles_unregistered_provider_and_source_failure(tmp_path: Path, caplog: pytest.LogCaptureFixture):
    registry = LlmUsageRegistry()
    registry.register(FakeUsageSource(error=RuntimeError("token secret@example.com should not leak")))
    store = ActiveProfileStore(tmp_path / "active.sqlite")
    persisted = store.init(provider_id="codex", model_id="gpt-5.6-luna")
    service = ActiveProfileService(store, _runtime(persisted, provider_id="codex"), FakeDiscovery([codex_ready()]), registry)

    with caplog.at_level(logging.WARNING, logger="knowledge_service.active_profile"):
        response = asyncio.run(service.get_active_profile())

    assert response.usage is None
    assert registry.resolve_optional("ollama") is None
    assert "RuntimeError" in caplog.text
    assert "token secret@example.com should not leak" not in caplog.text


def test_usage_source_failure_through_http_returns_null_usage_without_secret_text(tmp_path: Path, caplog: pytest.LogCaptureFixture):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    usage_registry = LlmUsageRegistry()
    usage_registry.register(FakeUsageSource(provider_id="ollama", error=RuntimeError("secret-token-123 should not leak")))
    assert deps.active_llm_runtime is not None
    deps.active_profile_service = ActiveProfileService(
        ActiveProfileStore(app_config.store_path),
        deps.active_llm_runtime,
        FakeDiscovery([ollama_ready("qwen2.5-coder:14b")]),
        usage_registry,
    )

    with caplog.at_level(logging.WARNING, logger="knowledge_service.active_profile"), AsgiTestClient(app) as client:
        response = client.get("/api/v1/knowledge/active-profile")

    assert response.status_code == 200
    assert response.json()["usage"] is None
    assert "secret-token-123" not in response.body.decode("utf-8")
    assert "secret-token-123" not in caplog.text


def test_registered_codex_source_returns_dynamic_windows_and_ignores_bad_ones():
    source = CodexLlmUsageSource(
        FakeCodexUsageClient(
            {
                "rateLimits": {
                    "limitId": "codex",
                    "primary": {"usedPercent": 34, "windowDurationMins": 300, "resetsAt": 1785436200},
                    "secondary": {"usedPercent": 160, "windowDurationMins": 10080, "resetsAt": 1785834000},
                },
                "rateLimitsByLimitId": {
                    "codex": {
                        "limitId": "codex",
                        "primary": {"usedPercent": 22, "windowDurationMins": 1440, "resetsAt": 1785436200},
                    },
                    "unrelated": {
                        "limitId": "unrelated",
                        "primary": {"usedPercent": 55, "windowDurationMins": 90, "resetsAt": 1785436200},
                    },
                },
            }
        )
    )

    usage = asyncio.run(source.usage())

    assert usage is not None
    assert [window.windowDurationMinutes for window in usage.windows] == [300, 1440, 10080]
    assert [window.usedPercent for window in usage.windows] == [34, 22, 100]


def test_codex_source_returns_none_without_rate_limits_and_ignores_invalid_windows_independently():
    assert asyncio.run(CodexLlmUsageSource(FakeCodexUsageClient({})).usage()) is None
    usage = asyncio.run(
        CodexLlmUsageSource(
            FakeCodexUsageClient(
                {
                    "rateLimits": {
                        "primary": {"usedPercent": "bad", "windowDurationMins": 300, "resetsAt": 1785436200},
                        "secondary": {"usedPercent": 61, "windowDurationMins": 10080, "resetsAt": 1785834000},
                    }
                }
            )
        ).usage()
    )
    assert usage is not None
    assert [window.windowDurationMinutes for window in usage.windows] == [10080]


def test_running_operation_keeps_old_snapshot_and_new_operation_gets_new_snapshot():
    provider = RecordingProvider()
    registry = GenerativeProviderRegistry()
    registry.register(provider)
    runtime = ActiveLlmRuntime(
        registry,
        PersistedActiveProfile(
            revision=1,
            llm_profile=ActiveLlmSelectionResponse(providerId="ollama", modelId="old-model", effort=None),
        ),
    )
    old_snapshot = runtime.capture()
    runtime.activate(
        PersistedActiveProfile(
            revision=2,
            llm_profile=ActiveLlmSelectionResponse(providerId="ollama", modelId="new-model", effort=None),
        )
    )

    old_response = old_snapshot.provider.generate(GenerativeRequest(prompt="x", model_id=old_snapshot.model_id))
    new_snapshot = runtime.capture()
    new_response = new_snapshot.provider.generate(GenerativeRequest(prompt="x", model_id=new_snapshot.model_id))

    assert old_response.model_id == "old-model"
    assert new_response.model_id == "new-model"


def test_active_runtime_rewrites_model_and_effort_without_mutating_old_snapshot():
    provider = RecordingProvider()
    registry = GenerativeProviderRegistry()
    registry.register(provider)
    runtime = ActiveLlmRuntime(
        registry,
        PersistedActiveProfile(
            revision=1,
            llm_profile=ActiveLlmSelectionResponse(providerId="ollama", modelId="gpt-5.6-luna", effort=ActiveLlmEffortResponse(effortId="high")),
        ),
    )

    active_provider = ActiveRuntimeGenerativeProvider(runtime)
    response = active_provider.generate(GenerativeRequest(prompt="x", model_id="ignored", effort_id="ignored"))

    assert response.model_id == "gpt-5.6-luna"
    assert provider.requests[0].effort_id == "high"
    assert provider.requests[0].metadata["activeModelId"] == "gpt-5.6-luna"


def _runtime(persisted: PersistedActiveProfile, *, provider_id: str = "ollama") -> ActiveLlmRuntime:
    provider = RecordingProvider()
    provider.provider_id = provider_id
    registry = GenerativeProviderRegistry()
    registry.register(provider)
    return ActiveLlmRuntime(registry, persisted)


def _active_profile_service(app_config, deps, discovery: FakeDiscovery) -> ActiveProfileService:
    assert deps.active_llm_runtime is not None
    return ActiveProfileService(
        ActiveProfileStore(app_config.store_path),
        deps.active_llm_runtime,
        discovery,
        LlmUsageRegistry(),
    )
