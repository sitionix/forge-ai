from __future__ import annotations

import asyncio
import json
import re
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any, Awaitable, Callable, Mapping, Protocol, Sequence

import httpx
from pydantic import BaseModel


ProviderStatus = str
READY: ProviderStatus = "READY"
DEGRADED: ProviderStatus = "DEGRADED"
UNAVAILABLE: ProviderStatus = "UNAVAILABLE"


class AiRuntimeProviderStatus(str, Enum):
    READY = READY
    DEGRADED = DEGRADED
    UNAVAILABLE = UNAVAILABLE


class AiRuntimeEffortResponse(BaseModel):
    effortId: str
    description: str

    class Config:
        extra = "forbid"


class AiRuntimeModelResponse(BaseModel):
    modelId: str
    displayName: str
    description: str | None = None
    modifiedAt: str | None = None
    efforts: list[AiRuntimeEffortResponse] | None = None

    class Config:
        extra = "forbid"


class AiRuntimeProviderResponse(BaseModel):
    providerId: str
    displayName: str
    status: AiRuntimeProviderStatus
    models: list[AiRuntimeModelResponse]
    version: str | None = None
    message: str | None = None

    class Config:
        extra = "forbid"


class AiRuntimeOptionsResponse(BaseModel):
    providers: list[AiRuntimeProviderResponse]

    class Config:
        extra = "forbid"


@dataclass(frozen=True)
class AiRuntimeEffortOption:
    effort_id: str
    description: str

    def public_dict(self) -> dict[str, Any]:
        return {"effortId": self.effort_id, "description": self.description}


@dataclass(frozen=True)
class AiRuntimeModelOption:
    model_id: str
    display_name: str
    description: str | None = None
    modified_at: str | None = None
    efforts: tuple[AiRuntimeEffortOption, ...] = ()

    def public_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "modelId": self.model_id,
            "displayName": self.display_name,
        }
        if self.description:
            payload["description"] = self.description
        if self.modified_at:
            payload["modifiedAt"] = self.modified_at
        if self.efforts:
            payload["efforts"] = [effort.public_dict() for effort in self.efforts]
        return payload


@dataclass(frozen=True)
class AiRuntimeProviderOptions:
    provider_id: str
    display_name: str
    status: ProviderStatus
    models: tuple[AiRuntimeModelOption, ...] = ()
    version: str | None = None
    message: str | None = None

    def public_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "providerId": self.provider_id,
            "displayName": self.display_name,
            "status": self.status,
            "models": [model.public_dict() for model in self.models],
        }
        if self.version:
            payload["version"] = self.version
        if self.status != READY and self.message:
            payload["message"] = self.message
        return payload


class AiRuntimeOptionsSource(Protocol):
    provider_id: str
    display_name: str

    async def discover(self) -> AiRuntimeProviderOptions: ...


@dataclass
class _CacheEntry:
    expires_at: float
    value: AiRuntimeProviderOptions

    def valid(self) -> bool:
        return time.monotonic() < self.expires_at


class AiRuntimeDiscoveryRegistry:
    def __init__(self, sources: Sequence[AiRuntimeOptionsSource] | None = None) -> None:
        self._sources: list[AiRuntimeOptionsSource] = []
        for source in sources or ():
            self.register(source)

    def register(self, source: AiRuntimeOptionsSource) -> None:
        provider_id = str(getattr(source, "provider_id", "") or "").strip().lower()
        if not provider_id:
            raise ValueError("AI runtime discovery provider_id is required")
        if any(existing.provider_id == provider_id for existing in self._sources):
            raise ValueError(f"AI runtime discovery source already registered: {provider_id}")
        self._sources.append(source)

    @property
    def sources(self) -> tuple[AiRuntimeOptionsSource, ...]:
        return tuple(self._sources)

    async def aclose(self) -> None:
        for source in self._sources:
            close = getattr(source, "aclose", None)
            if callable(close):
                result = close()
                if hasattr(result, "__await__"):
                    await result


class AiRuntimeDiscoveryService:
    def __init__(self, registry: AiRuntimeDiscoveryRegistry, *, provider_timeout_seconds: float = 5.0) -> None:
        self._registry = registry
        self._provider_timeout_seconds = max(0.001, float(provider_timeout_seconds))

    async def discover(self) -> dict[str, Any]:
        tasks = [self._discover_one(source) for source in self._registry.sources]
        providers = await asyncio.gather(*tasks)
        return {"providers": [provider.public_dict() for provider in providers]}

    async def _discover_one(self, source: AiRuntimeOptionsSource) -> AiRuntimeProviderOptions:
        try:
            return await asyncio.wait_for(source.discover(), timeout=self._provider_timeout_seconds)
        except TimeoutError:
            return AiRuntimeProviderOptions(
                provider_id=source.provider_id,
                display_name=source.display_name,
                status=UNAVAILABLE,
                message=f"{source.display_name} runtime discovery timed out",
            )
        except Exception:
            return AiRuntimeProviderOptions(
                provider_id=source.provider_id,
                display_name=source.display_name,
                status=UNAVAILABLE,
                message=f"{source.display_name} runtime is not available",
            )

    async def aclose(self) -> None:
        await self._registry.aclose()


class OllamaAiRuntimeOptionsSource:
    provider_id = "ollama"
    display_name = "Ollama"

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 5.0,
        cache_ttl_seconds: float = 30.0,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        self.base_url = str(base_url or "").rstrip("/")
        self.timeout_seconds = max(0.001, float(timeout_seconds))
        self.cache_ttl_seconds = max(0.001, float(cache_ttl_seconds))
        self._http_client = http_client
        self._owns_http_client = http_client is None
        self._version: str | None = None
        self._catalog_cache: _CacheEntry | None = None

    async def discover(self) -> AiRuntimeProviderOptions:
        if self._catalog_cache is not None and self._catalog_cache.valid():
            cached = self._catalog_cache.value
            version = await self._probe_version()
            if version is None:
                return AiRuntimeProviderOptions(
                    provider_id=self.provider_id,
                    display_name=self.display_name,
                    status=UNAVAILABLE,
                    message="Ollama runtime is not available",
                )
            return AiRuntimeProviderOptions(
                provider_id=self.provider_id,
                display_name=self.display_name,
                status=READY,
                version=version,
                models=cached.models,
            )
        version = await self._probe_version()
        if version is None:
            return AiRuntimeProviderOptions(
                provider_id=self.provider_id,
                display_name=self.display_name,
                status=UNAVAILABLE,
                message="Ollama runtime is not available",
            )
        models = await self._read_models()
        if models is None:
            return AiRuntimeProviderOptions(
                provider_id=self.provider_id,
                display_name=self.display_name,
                status=DEGRADED,
                version=version,
                message="Ollama model catalog could not be read",
            )
        result = AiRuntimeProviderOptions(
            provider_id=self.provider_id,
            display_name=self.display_name,
            status=READY,
            version=version,
            models=tuple(models),
        )
        self._catalog_cache = _CacheEntry(time.monotonic() + self.cache_ttl_seconds, result)
        return result

    async def aclose(self) -> None:
        if self._owns_http_client and self._http_client is not None:
            await self._http_client.aclose()

    async def _probe_version(self) -> str | None:
        try:
            response = await self._client().get("/api/version", timeout=self._timeout())
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError, json.JSONDecodeError):
            return None
        if not isinstance(payload, Mapping):
            return None
        version = payload.get("version")
        if not isinstance(version, str) or not version.strip():
            return None
        self._version = version
        return version

    async def _read_models(self) -> list[AiRuntimeModelOption] | None:
        try:
            response = await self._client().get("/api/tags", timeout=self._timeout())
            response.raise_for_status()
            payload = response.json()
        except (httpx.HTTPError, ValueError, json.JSONDecodeError):
            return None
        if not isinstance(payload, Mapping):
            return None
        raw_models = payload.get("models")
        if not isinstance(raw_models, list):
            return None
        models: list[AiRuntimeModelOption] = []
        for raw_model in raw_models:
            if not isinstance(raw_model, Mapping):
                return None
            capabilities = raw_model.get("capabilities")
            if capabilities is None and isinstance(raw_model.get("details"), Mapping):
                capabilities = raw_model["details"].get("capabilities")
            if not _string_list_contains(capabilities, "completion"):
                continue
            model_id = _non_blank(raw_model.get("model"))
            display_name = _non_blank(raw_model.get("name"))
            if model_id is None or display_name is None:
                return None
            models.append(
                AiRuntimeModelOption(
                    model_id=model_id,
                    display_name=display_name,
                    modified_at=_non_blank(raw_model.get("modified_at")),
                )
            )
        return models

    def _client(self) -> httpx.AsyncClient:
        if self._http_client is None:
            self._http_client = httpx.AsyncClient(base_url=self.base_url, timeout=self._timeout())
        return self._http_client

    def _timeout(self) -> httpx.Timeout:
        timeout = self.timeout_seconds
        return httpx.Timeout(timeout, connect=min(1.0, timeout))


class CodexAppServerError(RuntimeError):
    pass


class CodexAppServerTimeout(CodexAppServerError, TimeoutError):
    pass


class CodexAppServerClient:
    _VERSION_PATTERN = re.compile(r"^[^/]+/([^ ]+)")

    def __init__(
        self,
        *,
        client_name: str = "forge-knowledge",
        client_version: str = "0.1.0",
        command: Sequence[str] = ("codex", "app-server", "--stdio"),
        request_timeout_seconds: float = 5.0,
        process_factory: Callable[[Sequence[str]], Awaitable[Any]] | None = None,
    ) -> None:
        self._client_name = client_name
        self._client_version = client_version
        self._command = tuple(command)
        self._request_timeout_seconds = max(0.001, float(request_timeout_seconds))
        self._process_factory = process_factory or self._default_process_factory
        self._process: Any | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._pending: dict[int, asyncio.Future[Any]] = {}
        self._next_request_id = 1
        self._start_lock = asyncio.Lock()
        self._write_lock = asyncio.Lock()
        self._initialized = False
        self._version: str | None = None

    @property
    def version(self) -> str | None:
        return self._version

    async def initialize(self) -> str:
        await self._ensure_started()
        if self._version is None:
            raise CodexAppServerError("Codex app-server did not return a version")
        return self._version

    async def request(self, method: str, params: Mapping[str, Any] | None = None) -> Any:
        await self._ensure_started()
        request_id = self._allocate_request_id()
        payload: dict[str, Any] = {"id": request_id, "method": method}
        if params is not None:
            payload["params"] = dict(params)
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await self._write_json(payload)
            return await asyncio.wait_for(future, timeout=self._request_timeout_seconds)
        except TimeoutError as exc:
            raise CodexAppServerTimeout(f"Codex app-server request timed out: {method}") from exc
        except BaseException:
            raise
        finally:
            self._pending.pop(request_id, None)

    async def aclose(self) -> None:
        await self._stop_process()

    async def _ensure_started(self) -> None:
        async with self._start_lock:
            if self._process is not None and self._returncode(self._process) is None and self._initialized:
                return
            await self._stop_process()
            try:
                self._process = await self._process_factory(self._command)
            except FileNotFoundError as exc:
                raise CodexAppServerError("Codex app-server executable was not found") from exc
            self._initialized = False
            self._version = None
            self._reader_task = asyncio.create_task(self._read_stdout(), name="codex-app-server-stdout")
            self._stderr_task = asyncio.create_task(self._drain_stream(getattr(self._process, "stderr", None)), name="codex-app-server-stderr")
            try:
                response = await self._initialize_request()
                user_agent = _non_blank(response.get("userAgent")) if isinstance(response, Mapping) else None
                version = self._extract_version(user_agent)
                if version is None:
                    raise CodexAppServerError("Codex app-server did not return a version")
                self._version = version
                self._initialized = True
            except BaseException:
                cleanup = asyncio.create_task(self._stop_process())
                try:
                    await asyncio.shield(cleanup)
                except asyncio.CancelledError:
                    try:
                        await cleanup
                    except Exception:
                        pass
                raise

    async def _initialize_request(self) -> Any:
        request_id = self._allocate_request_id()
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await self._write_json(
                {
                    "id": request_id,
                    "method": "initialize",
                    "params": {
                        "clientInfo": {
                            "name": self._client_name,
                            "version": self._client_version,
                        }
                    },
                }
            )
            return await asyncio.wait_for(future, timeout=self._request_timeout_seconds)
        except TimeoutError as exc:
            raise CodexAppServerTimeout("Codex app-server initialize timed out") from exc
        except BaseException:
            raise
        finally:
            self._pending.pop(request_id, None)

    async def _write_json(self, payload: Mapping[str, Any]) -> None:
        process = self._process
        if process is None or self._returncode(process) is not None:
            raise CodexAppServerError("Codex app-server process is not running")
        stdin = getattr(process, "stdin", None)
        if stdin is None:
            raise CodexAppServerError("Codex app-server stdin is unavailable")
        encoded = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        async with self._write_lock:
            stdin.write(encoded)
            drain = getattr(stdin, "drain", None)
            if callable(drain):
                await drain()

    async def _read_stdout(self) -> None:
        stream = getattr(self._process, "stdout", None)
        try:
            while stream is not None:
                line = await stream.readline()
                if not line:
                    break
                self._handle_line(line)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self._fail_pending(exc)
        finally:
            self._fail_pending(CodexAppServerError("Codex app-server exited before response"))
            self._initialized = False

    async def _drain_stream(self, stream: Any | None) -> None:
        if stream is None:
            return
        try:
            while await stream.readline():
                pass
        except asyncio.CancelledError:
            raise
        except Exception:
            return

    def _handle_line(self, line: bytes | str) -> None:
        text = line.decode("utf-8", errors="replace") if isinstance(line, bytes) else str(line)
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            return
        if not isinstance(payload, Mapping) or "id" not in payload:
            return
        request_id = payload.get("id")
        if not isinstance(request_id, int):
            return
        future = self._pending.pop(request_id, None)
        if future is None or future.done():
            return
        if "error" in payload:
            future.set_exception(CodexAppServerError("Codex app-server returned an error"))
            return
        future.set_result(payload.get("result"))

    async def _stop_process(self) -> None:
        for task in (self._reader_task, self._stderr_task):
            if task is not None:
                task.cancel()
        for task in (self._reader_task, self._stderr_task):
            if task is not None:
                try:
                    await task
                except asyncio.CancelledError:
                    pass
                except Exception:
                    pass
        self._reader_task = None
        self._stderr_task = None
        self._fail_pending(CodexAppServerError("Codex app-server stopped"))
        process = self._process
        self._process = None
        self._initialized = False
        self._version = None
        if process is None or self._returncode(process) is not None:
            return
        terminate = getattr(process, "terminate", None)
        if callable(terminate):
            terminate()
        wait = getattr(process, "wait", None)
        if callable(wait):
            try:
                await asyncio.wait_for(wait(), timeout=1.0)
                return
            except TimeoutError:
                kill = getattr(process, "kill", None)
                if callable(kill):
                    kill()
                try:
                    await asyncio.wait_for(wait(), timeout=1.0)
                except TimeoutError:
                    pass

    async def _default_process_factory(self, command: Sequence[str]) -> Any:
        return await asyncio.create_subprocess_exec(
            *command,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

    def _allocate_request_id(self) -> int:
        request_id = self._next_request_id
        self._next_request_id += 1
        return request_id

    def _extract_version(self, user_agent: str | None) -> str | None:
        if user_agent is None:
            return None
        match = self._VERSION_PATTERN.match(user_agent)
        return match.group(1) if match else None

    def _fail_pending(self, exc: Exception) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(exc)
        self._pending.clear()

    def _returncode(self, process: Any) -> int | None:
        return getattr(process, "returncode", None)


class CodexAiRuntimeOptionsSource:
    provider_id = "codex"
    display_name = "Codex"

    def __init__(self, client: CodexAppServerClient, *, cache_ttl_seconds: float = 30.0, max_page_count: int = 100) -> None:
        self._client = client
        self.cache_ttl_seconds = max(0.001, float(cache_ttl_seconds))
        self.max_page_count = max(1, int(max_page_count))
        self._catalog_cache: _CacheEntry | None = None

    async def discover(self) -> AiRuntimeProviderOptions:
        if self._catalog_cache is not None and self._catalog_cache.valid():
            cached = self._catalog_cache.value
            try:
                version = await self._client.initialize()
            except CodexAppServerError:
                return AiRuntimeProviderOptions(
                    provider_id=self.provider_id,
                    display_name=self.display_name,
                    status=UNAVAILABLE,
                    message="Codex runtime is not available",
                )
            return AiRuntimeProviderOptions(
                provider_id=self.provider_id,
                display_name=self.display_name,
                status=READY,
                version=version,
                models=cached.models,
            )
        try:
            version = await self._client.initialize()
        except CodexAppServerError:
            return AiRuntimeProviderOptions(
                provider_id=self.provider_id,
                display_name=self.display_name,
                status=UNAVAILABLE,
                message="Codex runtime is not available",
            )
        try:
            models = await self._read_all_models()
        except CodexAppServerError:
            return AiRuntimeProviderOptions(
                provider_id=self.provider_id,
                display_name=self.display_name,
                status=DEGRADED,
                version=version,
                message="Codex model catalog could not be read",
            )
        result = AiRuntimeProviderOptions(
            provider_id=self.provider_id,
            display_name=self.display_name,
            status=READY,
            version=version,
            models=tuple(models),
        )
        self._catalog_cache = _CacheEntry(time.monotonic() + self.cache_ttl_seconds, result)
        return result

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _read_all_models(self) -> list[AiRuntimeModelOption]:
        models: list[AiRuntimeModelOption] = []
        cursor: str | None = None
        seen_cursors: set[str] = set()
        page_count = 0
        while True:
            if page_count >= self.max_page_count:
                raise CodexAppServerError("Codex model/list pagination exceeded maximum page count")
            page_count += 1
            params: dict[str, Any] = {"includeHidden": False}
            if cursor:
                params["cursor"] = cursor
            payload = await self._client.request("model/list", params)
            if not isinstance(payload, Mapping):
                raise CodexAppServerError("Codex model/list result was not an object")
            raw_models = payload.get("data")
            if not isinstance(raw_models, list):
                raise CodexAppServerError("Codex model/list data was not a list")
            for raw_model in raw_models:
                model = self._map_model(raw_model)
                if model is not None:
                    models.append(model)
            next_cursor = payload.get("nextCursor")
            if not isinstance(next_cursor, str) or not next_cursor:
                return models
            if next_cursor in seen_cursors:
                raise CodexAppServerError("Codex model/list pagination cursor repeated")
            seen_cursors.add(next_cursor)
            cursor = next_cursor

    def _map_model(self, raw_model: Any) -> AiRuntimeModelOption | None:
        if not isinstance(raw_model, Mapping):
            raise CodexAppServerError("Codex model entry was not an object")
        if raw_model.get("hidden") is True:
            return None
        model_id = _non_blank(raw_model.get("id")) or _non_blank(raw_model.get("model"))
        display_name = _non_blank(raw_model.get("displayName"))
        if model_id is None or display_name is None:
            raise CodexAppServerError("Codex model entry is missing id/displayName")
        efforts: list[AiRuntimeEffortOption] = []
        raw_efforts = raw_model.get("supportedReasoningEfforts")
        if isinstance(raw_efforts, list):
            for raw_effort in raw_efforts:
                if not isinstance(raw_effort, Mapping):
                    continue
                effort_id = _non_blank(raw_effort.get("reasoningEffort"))
                description = _non_blank(raw_effort.get("description"))
                if effort_id and description:
                    efforts.append(AiRuntimeEffortOption(effort_id=effort_id, description=description))
        return AiRuntimeModelOption(
            model_id=model_id,
            display_name=display_name,
            description=_non_blank(raw_model.get("description")),
            efforts=tuple(efforts),
        )


def _non_blank(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None


def _string_list_contains(value: Any, expected: str) -> bool:
    if not isinstance(value, list):
        return False
    return any(isinstance(item, str) and item == expected for item in value)
