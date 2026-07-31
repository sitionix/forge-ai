from __future__ import annotations

import asyncio
import json
import sqlite3
from pathlib import Path
from typing import Any

from support import AsgiTestClient, build_test_app, write_runtime_config

from knowledge_service.active_profile import (
    ActiveLlmEffortResponse,
    ActiveLlmProfilePutRequest,
    ActiveLlmProfileResponse,
    ActiveLlmRuntime,
    ActiveRuntimeGenerativeProvider,
    LlmUsageProvider,
    PersistedActiveProfile,
)
from knowledge_service.generative_runtime import GenerativeProviderRegistry, GenerativeRequest, GenerativeResponse


class FakeDiscovery:
    def __init__(self, providers: list[dict[str, Any]]) -> None:
        self.providers = providers

    async def discover(self) -> dict[str, Any]:
        return {"providers": self.providers}


class FakeUsage:
    def __init__(self, value=None, error: Exception | None = None) -> None:
        self.value = value
        self.error = error

    async def usage_for(self, provider_id: str):
        if self.error is not None:
            raise self.error
        return self.value


def ollama_ready(*models: str) -> dict[str, Any]:
    return {
        "providerId": "ollama",
        "displayName": "Ollama",
        "status": "READY",
        "models": [{"modelId": model, "displayName": model} for model in models],
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


def test_get_active_profile_initializes_from_current_configuration_exact_contract(tmp_path: Path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    service = deps.active_profile_service
    assert service is not None
    service._usage_provider = FakeUsage(None)

    with AsgiTestClient(app) as client:
        response = client.get("/api/v1/knowledge/active-profile")

    assert response.status_code == 200
    assert response.json() == {
        "revision": 1,
        "llmProfile": {
            "providerId": "ollama",
            "modelId": "qwen2.5-coder:14b",
            "effort": None,
        },
        "usage": None,
    }


def test_existing_profile_is_not_overwritten_on_restart_and_usage_is_not_persisted(tmp_path: Path):
    config = write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b")
    first, _, first_config, first_deps = build_test_app(config)
    first_deps.active_profile_service._discovery = FakeDiscovery([ollama_ready("qwen2.5-coder:14b", "qwen2.5-coder:32b")])
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
    assert put.json()["revision"] == 2

    restarted, *_ = build_test_app(config)
    with AsgiTestClient(restarted) as client:
        response = client.get("/api/v1/knowledge/active-profile")
    assert response.json()["revision"] == 2
    assert response.json()["llmProfile"]["modelId"] == "qwen2.5-coder:32b"

    with sqlite3.connect(first_config.store_path) as conn:
        stored = conn.execute("SELECT profile_json FROM active_profile WHERE singleton_id = 'active'").fetchone()[0]
    assert "usage" not in json.loads(stored)


def test_put_creates_singleton_profile_when_record_is_absent(tmp_path: Path):
    app, _, config, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service._discovery = FakeDiscovery([ollama_ready("qwen2.5-coder:14b", "qwen2.5-coder:32b")])
    with sqlite3.connect(config.store_path) as conn:
        conn.execute("DELETE FROM active_profile")

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
        current = client.get("/api/v1/knowledge/active-profile")

    assert put.status_code == 200
    assert put.json()["revision"] == 2
    assert current.json()["revision"] == 2
    assert current.json()["llmProfile"]["modelId"] == "qwen2.5-coder:32b"


def test_put_replaces_llm_profile_increments_revision_and_updates_status(tmp_path: Path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service._discovery = FakeDiscovery([ollama_ready("qwen2.5-coder:14b", "qwen2.5-coder:32b")])

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
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    service = deps.active_profile_service
    service._discovery = FakeDiscovery([
        ollama_ready("qwen2.5-coder:14b"),
        {"providerId": "offline", "displayName": "Offline", "status": "UNAVAILABLE", "models": []},
        codex_ready(),
    ])

    cases = [
        ({"expectedRevision": 1, "providerId": "missing", "modelId": "x", "effort": None}, "ACTIVE_LLM_PROVIDER_NOT_FOUND"),
        ({"expectedRevision": 1, "providerId": "offline", "modelId": "x", "effort": None}, "ACTIVE_LLM_PROVIDER_UNAVAILABLE"),
        ({"expectedRevision": 1, "providerId": "ollama", "modelId": "missing", "effort": None}, "ACTIVE_LLM_MODEL_NOT_FOUND"),
        ({"expectedRevision": 1, "providerId": "codex", "modelId": "gpt-5.6-luna", "effort": None}, "ACTIVE_LLM_EFFORT_REQUIRED"),
        (
            {"expectedRevision": 1, "providerId": "codex", "modelId": "gpt-5.6-luna", "effort": {"effortId": "unknown"}},
            "ACTIVE_LLM_EFFORT_NOT_SUPPORTED",
        ),
        (
            {"expectedRevision": 1, "providerId": "ollama", "modelId": "qwen2.5-coder:14b", "effort": {"effortId": "high"}},
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


def test_stale_expected_revision_returns_409(tmp_path: Path):
    app, *_ = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    with AsgiTestClient(app) as client:
        response = client.put(
            "/api/v1/knowledge/active-profile/llm-profile",
            {"expectedRevision": 99, "providerId": "ollama", "modelId": "qwen2.5-coder:14b", "effort": None},
        )
    assert response.status_code == 409
    assert response.json()["code"] == "ACTIVE_PROFILE_REVISION_CONFLICT"


def test_usage_failure_returns_profile_with_usage_null(tmp_path: Path):
    app, _, _, deps = build_test_app(write_runtime_config(tmp_path, generative_model="qwen2.5-coder:14b"))
    deps.active_profile_service._usage_provider = FakeUsage(error=RuntimeError("token secret@example.com should not leak"))

    with AsgiTestClient(app) as client:
        response = client.get("/api/v1/knowledge/active-profile")

    assert response.status_code == 200
    assert response.json()["usage"] is None
    assert "secret@example.com" not in response.body.decode("utf-8")


def test_codex_usage_maps_available_rate_limit_windows_to_public_contract():
    class FakeCodexClient:
        async def request(self, method: str, params=None):
            assert method == "account/rateLimits/read"
            return {
                "rateLimits": {
                    "primary": {"usedPercent": 34, "windowDurationMins": 300, "resetsAt": 1785436200},
                    "secondary": {"usedPercent": 61, "windowDurationMins": 10080, "resetsAt": 1785834000},
                }
            }

    usage = asyncio.run(LlmUsageProvider(FakeCodexClient()).usage_for("codex"))

    assert usage.dict() == {
        "windows": [
            {
                "kind": "PRIMARY",
                "usedPercent": 34,
                "windowDurationMinutes": 300,
                "resetAt": "2026-07-30T18:30:00Z",
            },
            {
                "kind": "SECONDARY",
                "usedPercent": 61,
                "windowDurationMinutes": 10080,
                "resetAt": "2026-08-04T09:00:00Z",
            },
        ]
    }


def test_running_operation_keeps_old_snapshot_and_new_operation_gets_new_snapshot(tmp_path: Path):
    class RecordingProvider:
        provider_id = "ollama"
        provider_version = "1"

        def generate(self, request: GenerativeRequest) -> GenerativeResponse:
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

    registry = GenerativeProviderRegistry()
    registry.register(RecordingProvider())
    runtime = ActiveLlmRuntime(
        registry,
        PersistedActiveProfile(
            revision=1,
            llm_profile=ActiveLlmProfileResponse(providerId="ollama", modelId="old-model", effort=None),
        ),
    )
    old_snapshot = runtime.capture()
    runtime.activate(
        PersistedActiveProfile(
            revision=2,
            llm_profile=ActiveLlmProfileResponse(providerId="ollama", modelId="new-model", effort=None),
        )
    )

    old_response = old_snapshot.provider.generate(GenerativeRequest(prompt="x", model_id=old_snapshot.model_id))
    new_snapshot = runtime.capture()
    new_response = new_snapshot.provider.generate(GenerativeRequest(prompt="x", model_id=new_snapshot.model_id))

    assert old_response.model_id == "old-model"
    assert new_response.model_id == "new-model"


def test_active_runtime_rewrites_model_and_effort_without_mutating_old_snapshot(tmp_path: Path):
    class RecordingProvider:
        provider_id = "codex"
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

    provider = RecordingProvider()
    registry = GenerativeProviderRegistry()
    registry.register(provider)
    runtime = ActiveLlmRuntime(
        registry,
        PersistedActiveProfile(
            revision=1,
            llm_profile=ActiveLlmProfileResponse(
                providerId="codex",
                modelId="old-model",
                effort=ActiveLlmEffortResponse(effortId="low"),
            ),
        ),
    )
    old_snapshot = runtime.capture()
    active_provider = ActiveRuntimeGenerativeProvider(runtime)
    runtime.activate(
        PersistedActiveProfile(
            revision=2,
            llm_profile=ActiveLlmProfileResponse(
                providerId="codex",
                modelId="new-model",
                effort=ActiveLlmEffortResponse(effortId="high"),
            ),
        )
    )

    old_response = old_snapshot.provider.generate(
        GenerativeRequest(prompt="x", model_id=old_snapshot.model_id, effort_id=old_snapshot.effort_id)
    )
    new_response = active_provider.generate(GenerativeRequest(prompt="x", model_id="ignored", effort_id="ignored"))

    assert old_response.model_id == "old-model"
    assert new_response.model_id == "new-model"
    assert provider.requests[0].effort_id == "low"
    assert provider.requests[1].effort_id == "high"
