from __future__ import annotations

import asyncio
import json
import logging
import sqlite3
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping, Protocol

from pydantic import BaseModel, Field, root_validator

from knowledge_service.ai_runtime_discovery import READY, AiRuntimeDiscoveryService
from knowledge_service.errors import KnowledgeError
from knowledge_service.generative_runtime import GenerativeProviderRegistry, GenerativeRequest

LOGGER = logging.getLogger(__name__)
_SINGLETON_ID = "active"


class ActiveLlmEffortResponse(BaseModel):
    effortId: str

    class Config:
        extra = "forbid"


class ActiveLlmSelectionResponse(BaseModel):
    providerId: str
    modelId: str
    effort: ActiveLlmEffortResponse | None

    class Config:
        extra = "forbid"


class ActiveLlmProfileDetailsResponse(ActiveLlmSelectionResponse):
    providerDisplayName: str | None = None
    modelDisplayName: str | None = None

    class Config:
        extra = "forbid"


class LlmUsageWindowResponse(BaseModel):
    kind: str
    usedPercent: int
    windowDurationMinutes: int
    resetAt: str

    class Config:
        extra = "forbid"


class LlmUsageResponse(BaseModel):
    windows: list[LlmUsageWindowResponse]

    class Config:
        extra = "forbid"


class ActiveProfileResponse(BaseModel):
    revision: int
    llmProfile: ActiveLlmProfileDetailsResponse
    usage: LlmUsageResponse | None

    class Config:
        extra = "forbid"


class ActiveLlmProfilePutRequest(BaseModel):
    expectedRevision: int = Field(..., ge=1)
    providerId: str
    modelId: str
    effort: ActiveLlmEffortResponse | None

    class Config:
        extra = "forbid"

    @root_validator(pre=True)
    def _require_effort_field(cls, values: Any) -> Any:
        if isinstance(values, Mapping) and "effort" not in values:
            raise ValueError("effort is required")
        return values


class ActiveLlmProfilePutResponse(BaseModel):
    revision: int
    llmProfile: ActiveLlmSelectionResponse

    class Config:
        extra = "forbid"


@dataclass(frozen=True)
class PersistedActiveProfile:
    revision: int
    llm_profile: ActiveLlmSelectionResponse


@dataclass(frozen=True)
class ActiveLlmSnapshot:
    revision: int
    provider_id: str
    model_id: str
    effort_id: str | None
    provider: Any


class ActiveProfileStore:
    def __init__(self, db_path: Path) -> None:
        self.db_path = Path(db_path)

    def init(self, *, provider_id: str, model_id: str) -> PersistedActiveProfile:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            self._create_schema(conn)
            existing = self._read(conn)
            if existing is not None:
                return existing
            profile = ActiveLlmSelectionResponse(providerId=provider_id, modelId=model_id, effort=None)
            conn.execute(
                """
                INSERT INTO active_profile(singleton_id, revision, profile_json, created_at, updated_at)
                VALUES(?, 1, ?, ?, ?)
                """,
                (_SINGLETON_ID, self._profile_json(profile), self._now(), self._now()),
            )
            return PersistedActiveProfile(revision=1, llm_profile=profile)

    def read(self) -> PersistedActiveProfile | None:
        with self._connect() as conn:
            self._create_schema(conn)
            return self._read(conn)

    def replace_llm_profile(self, expected_revision: int, profile: ActiveLlmSelectionResponse) -> PersistedActiveProfile:
        with self._connect() as conn:
            self._create_schema(conn)
            current = self._read(conn)
            if current is None:
                if int(expected_revision) != 1:
                    raise KnowledgeError(
                        "ACTIVE_PROFILE_REVISION_CONFLICT",
                        "The active profile was changed by another request",
                    )
                revision = int(expected_revision) + 1
                conn.execute(
                    """
                    INSERT INTO active_profile(singleton_id, revision, profile_json, created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?)
                    """,
                    (_SINGLETON_ID, revision, self._profile_json(profile), self._now(), self._now()),
                )
                return PersistedActiveProfile(revision=revision, llm_profile=profile)
            if current.revision != int(expected_revision):
                raise KnowledgeError(
                    "ACTIVE_PROFILE_REVISION_CONFLICT",
                    "The active profile was changed by another request",
                )
            revision = current.revision + 1
            conn.execute(
                """
                UPDATE active_profile
                SET revision = ?, profile_json = ?, updated_at = ?
                WHERE singleton_id = ?
                """,
                (revision, self._profile_json(profile), self._now(), _SINGLETON_ID),
            )
            return PersistedActiveProfile(revision=revision, llm_profile=profile)

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        return conn

    def _create_schema(self, conn: sqlite3.Connection) -> None:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS active_profile (
              singleton_id TEXT PRIMARY KEY CHECK (singleton_id = 'active'),
              revision INTEGER NOT NULL CHECK (revision >= 1),
              profile_json TEXT NOT NULL,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """
        )

    def _read(self, conn: sqlite3.Connection) -> PersistedActiveProfile | None:
        row = conn.execute(
            "SELECT revision, profile_json FROM active_profile WHERE singleton_id = ?",
            (_SINGLETON_ID,),
        ).fetchone()
        if row is None:
            return None
        try:
            payload = json.loads(str(row["profile_json"]))
            llm_profile = ActiveLlmSelectionResponse.parse_obj(payload.get("llmProfile"))
        except Exception as exc:
            raise KnowledgeError("ACTIVE_PROFILE_INVALID", "Stored active profile is invalid") from exc
        return PersistedActiveProfile(revision=int(row["revision"]), llm_profile=llm_profile)

    def _profile_json(self, profile: ActiveLlmSelectionResponse) -> str:
        return json.dumps(
            {
                "llmProfile": {
                    "providerId": profile.providerId,
                    "modelId": profile.modelId,
                    "effort": profile.effort.dict() if profile.effort is not None else None,
                }
            },
            separators=(",", ":"),
            sort_keys=True,
        )

    def _now(self) -> str:
        return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class ActiveLlmRuntime:
    def __init__(self, registry: GenerativeProviderRegistry, initial: PersistedActiveProfile) -> None:
        self._registry = registry
        self._lock = threading.RLock()
        self._snapshot = self._snapshot_for(initial)

    def capture(self) -> ActiveLlmSnapshot:
        with self._lock:
            return self._snapshot

    def activate(self, persisted: PersistedActiveProfile) -> ActiveLlmSnapshot:
        snapshot = self._snapshot_for(persisted)
        with self._lock:
            self._snapshot = snapshot
            return snapshot

    def _snapshot_for(self, persisted: PersistedActiveProfile) -> ActiveLlmSnapshot:
        provider_id = persisted.llm_profile.providerId
        provider = self._registry.resolve(provider_id)
        effort = persisted.llm_profile.effort
        return ActiveLlmSnapshot(
            revision=persisted.revision,
            provider_id=provider_id,
            model_id=persisted.llm_profile.modelId,
            effort_id=effort.effortId if effort is not None else None,
            provider=provider,
        )

    def resolve_provider(self, provider_id: str) -> Any:
        return self._registry.resolve(provider_id)


class ActiveProfileService:
    def __init__(
        self,
        store: ActiveProfileStore,
        runtime: ActiveLlmRuntime,
        discovery: AiRuntimeDiscoveryService,
        usage_registry: LlmUsageRegistry,
    ) -> None:
        self._store = store
        self._runtime = runtime
        self._discovery = discovery
        self._usage_registry = usage_registry
        self._activation_lock = asyncio.Lock()

    def active_llm_snapshot(self) -> ActiveLlmSnapshot:
        return self._runtime.capture()

    async def get_active_profile(self) -> ActiveProfileResponse:
        persisted = self._require_profile()
        usage = await self._usage_or_null(persisted.llm_profile.providerId)
        return ActiveProfileResponse(
            revision=persisted.revision,
            llmProfile=await self._present_llm_profile(persisted.llm_profile),
            usage=usage,
        )

    async def replace_llm_profile(self, request: ActiveLlmProfilePutRequest) -> ActiveLlmProfilePutResponse:
        candidate = ActiveLlmSelectionResponse(
            providerId=_clean_id(request.providerId),
            modelId=str(request.modelId or "").strip(),
            effort=request.effort,
        )
        async with self._activation_lock:
            current = self._store.read()
            if current is not None and current.revision != request.expectedRevision:
                raise KnowledgeError(
                    "ACTIVE_PROFILE_REVISION_CONFLICT",
                    "The active profile was changed by another request",
                )
            if current is None and request.expectedRevision != 1:
                raise KnowledgeError(
                    "ACTIVE_PROFILE_REVISION_CONFLICT",
                    "The active profile was changed by another request",
                )
            await self._validate_candidate(candidate)
            persisted = self._store.replace_llm_profile(request.expectedRevision, candidate)
            try:
                self._runtime.activate(persisted)
            except Exception:
                LOGGER.exception("Active profile persisted but runtime activation failed")
                raise
            return ActiveLlmProfilePutResponse(revision=persisted.revision, llmProfile=persisted.llm_profile)

    def _require_profile(self) -> PersistedActiveProfile:
        persisted = self._store.read()
        if persisted is None:
            raise KnowledgeError("ACTIVE_PROFILE_NOT_FOUND", "Active profile is not initialized")
        return persisted

    async def _validate_candidate(self, candidate: ActiveLlmSelectionResponse) -> None:
        provider_id = _clean_id(candidate.providerId)
        model_id = _clean_id(candidate.modelId)
        if not provider_id:
            raise KnowledgeError("ACTIVE_LLM_PROVIDER_NOT_FOUND", "Active LLM provider was not found")
        if not model_id:
            raise KnowledgeError("ACTIVE_LLM_MODEL_NOT_FOUND", "Active LLM model was not found")
        provider_options = None
        for provider in (await self._discovery.discover()).get("providers", []):
            if isinstance(provider, Mapping) and provider.get("providerId") == provider_id:
                provider_options = provider
                break
        if provider_options is None:
            raise KnowledgeError("ACTIVE_LLM_PROVIDER_NOT_FOUND", "Active LLM provider was not found")
        if provider_options.get("status") != READY:
            raise KnowledgeError("ACTIVE_LLM_PROVIDER_UNAVAILABLE", "Active LLM provider is unavailable")
        model_options = None
        for model in provider_options.get("models") or []:
            if isinstance(model, Mapping) and model.get("modelId") == model_id:
                model_options = model
                break
        if model_options is None:
            raise KnowledgeError("ACTIVE_LLM_MODEL_NOT_FOUND", "Active LLM model was not found")
        self._validate_effort(candidate.effort, model_options)
        try:
            provider = self._runtime.resolve_provider(provider_id)
        except Exception as exc:
            raise KnowledgeError("ACTIVE_LLM_PROVIDER_NOT_EXECUTABLE", "Active LLM provider is not executable") from exc
        if not callable(getattr(provider, "generate", None)) and not callable(getattr(provider, "generate_async", None)):
            raise KnowledgeError("ACTIVE_LLM_PROVIDER_NOT_EXECUTABLE", "Active LLM provider is not executable")

    def _validate_effort(self, effort: ActiveLlmEffortResponse | None, model_options: Mapping[str, Any]) -> None:
        efforts = model_options.get("efforts")
        available = [
            str(item.get("effortId"))
            for item in efforts or []
            if isinstance(item, Mapping) and str(item.get("effortId") or "").strip()
        ]
        if not available:
            if effort is not None:
                raise KnowledgeError("ACTIVE_LLM_EFFORT_NOT_SUPPORTED", "Active LLM model does not support effort")
            return
        if effort is None:
            raise KnowledgeError("ACTIVE_LLM_EFFORT_REQUIRED", "Active LLM effort is required for this model")
        if effort.effortId not in available:
            raise KnowledgeError("ACTIVE_LLM_EFFORT_NOT_SUPPORTED", "Active LLM effort is not supported for this model")

    async def _usage_or_null(self, provider_id: str) -> LlmUsageResponse | None:
        source = self._usage_registry.resolve_optional(provider_id)
        if source is None:
            return None
        try:
            return await source.usage()
        except Exception as exc:  # noqa: BLE001 - usage sources are provider-owned; active-profile GET must stay available.
            LOGGER.warning(
                "Active profile usage lookup failed for provider %s: %s",
                provider_id,
                type(exc).__name__,
            )
            return None

    async def _present_llm_profile(self, profile: ActiveLlmSelectionResponse) -> ActiveLlmProfileDetailsResponse:
        try:
            metadata = self._discovery.cached_profile_metadata(profile.providerId, profile.modelId)
        except Exception:
            LOGGER.exception("Active profile display metadata lookup failed")
            provider_display_name = None
            model_display_name = None
        else:
            provider_display_name = _optional_display_name(metadata.provider_display_name)
            model_display_name = _optional_display_name(metadata.model_display_name)
        return ActiveLlmProfileDetailsResponse(
            providerId=profile.providerId,
            modelId=profile.modelId,
            effort=profile.effort,
            providerDisplayName=provider_display_name,
            modelDisplayName=model_display_name,
        )


class LlmUsageSource(Protocol):
    provider_id: str

    async def usage(self) -> LlmUsageResponse | None: ...


class LlmUsageRegistry:
    def __init__(self) -> None:
        self._sources: dict[str, LlmUsageSource] = {}

    def register(self, source: LlmUsageSource) -> None:
        provider_id = _clean_id(source.provider_id)
        if not provider_id:
            raise ValueError("LLM usage provider_id is required")
        if provider_id in self._sources:
            raise ValueError(f"LLM usage source already registered: {provider_id}")
        self._sources[provider_id] = source

    def resolve_optional(self, provider_id: str) -> LlmUsageSource | None:
        return self._sources.get(_clean_id(provider_id))


class ActiveRuntimeGenerativeProvider:
    provider_id = "active"
    provider_version = "1"

    def __init__(self, runtime: ActiveLlmRuntime) -> None:
        self._runtime = runtime

    def generate(self, request: GenerativeRequest):
        snapshot = self._runtime.capture()
        return snapshot.provider.generate(_request_for_snapshot(request, snapshot))

    async def generate_async(self, request: GenerativeRequest):
        snapshot = self._runtime.capture()
        return await snapshot.provider.generate_async(_request_for_snapshot(request, snapshot))

    async def aclose(self) -> None:
        return None


def _request_for_snapshot(request: GenerativeRequest, snapshot: ActiveLlmSnapshot) -> GenerativeRequest:
    metadata = dict(request.metadata or {})
    metadata.update(
        {
            "activeProfileRevision": snapshot.revision,
            "activeProviderId": snapshot.provider_id,
            "activeModelId": snapshot.model_id,
        }
    )
    return GenerativeRequest(
        prompt=request.prompt,
        model_id=snapshot.model_id,
        effort_id=snapshot.effort_id,
        response_mode=request.response_mode,
        timeout_seconds=request.timeout_seconds,
        context_tokens=request.context_tokens,
        temperature=request.temperature,
        metadata=metadata,
    )


def _clean_id(value: str) -> str:
    return str(value or "").strip().lower()


def _optional_display_name(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None
