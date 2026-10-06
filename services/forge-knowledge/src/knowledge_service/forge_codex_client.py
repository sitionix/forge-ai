"""The narrow, authenticated Agent bridge. This module never starts Codex."""

from __future__ import annotations

import asyncio
import concurrent.futures
import json
import math
import os
import stat
import threading
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

import httpx

from knowledge_service.codex_app_server import (
    CodexAppServerError,
    CodexAppServerLifecycleError,
    CodexAppServerProtocolError,
    CodexAppServerTimeout,
    CodexAppServerTransportError,
    CodexGenerationPolicy,
    CodexTurnResult,
)


class ForgeCodexClient:
    def __init__(self, base_url: str, token_file: Path, *, transport: Any = None) -> None:
        url = urlparse(base_url)
        if (
            url.scheme != "http"
            or url.hostname not in {"127.0.0.1", "localhost", "::1"}
            or url.username
            or url.password
            or url.query
            or url.fragment
            or url.path not in {"", "/"}
        ):
            raise ValueError("Forge Codex bridge must use a local Agent URL")
        self._base = base_url.rstrip("/") + "/internal/v1/codex"
        self._token_file = Path(token_file)
        self._transport = transport
        self._owned: set[str] = set()
        self._lock = threading.Lock()
        self._closed = False
        self.version: str | None = None

    def _token(self) -> str:
        try:
            fd = os.open(self._token_file, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid() or info.st_nlink != 1:
                    raise ValueError()
                value = stream.read(257).decode("ascii")
                if not 32 <= len(value) <= 256 or not all(c.isalnum() or c in "-_" for c in value):
                    raise ValueError()
                return value
        except (OSError, ValueError, UnicodeError):
            raise CodexAppServerTransportError("Forge Codex service credential unavailable") from None

    def _require_open(self) -> None:
        with self._lock:
            if self._closed:
                raise CodexAppServerLifecycleError("Forge Codex bridge closed")

    async def _http(self, method: str, path: str, *, params=None, payload=None, timeout=15.0):
        try:
            async with httpx.AsyncClient(transport=self._transport, trust_env=False, follow_redirects=False) as client:  # noqa: SIM117
                async with client.stream(
                    method, self._base + path, params=params, json=payload, headers={"Authorization": "Bearer " + self._token()}, timeout=timeout
                ) as response:
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        content.extend(chunk)
                        if len(content) > 4 * 1024 * 1024:
                            raise CodexAppServerProtocolError("Forge Codex bridge response exceeded limit")
                    if response.status_code >= 300:
                        raise CodexAppServerTransportError("Forge Codex bridge request rejected", status_code=response.status_code)
                    version = response.headers.get("X-Forge-Codex-Version")
                    if version:
                        self.version = version
                    if response.status_code == 204:
                        return None
                    data = json.loads(content)
                    if not isinstance(data, dict):
                        raise CodexAppServerProtocolError("Forge Codex bridge returned invalid JSON")
                    return data
        except httpx.TimeoutException:
            raise CodexAppServerTimeout("Forge Codex bridge timed out") from None
        except httpx.HTTPError:
            raise CodexAppServerTransportError("Forge Codex bridge unavailable") from None
        except (ValueError, UnicodeError):
            raise CodexAppServerProtocolError("Forge Codex bridge returned invalid JSON") from None

    async def initialize(self) -> str:
        await self.request("model/list", {"limit": 1})
        if self.version is None:
            raise CodexAppServerProtocolError("Forge Codex bridge omitted version")
        return self.version

    async def request(self, method: str, params=None):
        self._require_open()
        params = {} if params is None else params
        if not isinstance(params, dict):
            raise CodexAppServerProtocolError("Unsupported Forge Codex operation")
        if method == "model/list" and not (set(params) - {"cursor", "limit", "includeHidden"}):
            return await self._http("GET", "/models", params=params)
        if method == "account/rateLimits/read" and not params:
            return await self._http("GET", "/usage")
        raise CodexAppServerProtocolError("Unsupported Forge Codex operation")

    async def run_turn(self, *, prompt: str, model_id: str, effort_id: str | None, response_mode: Any, timeout_seconds: float) -> CodexTurnResult:
        self._require_open()
        timeout = float(timeout_seconds)
        mode = str(getattr(response_mode, "value", response_mode))
        if not math.isfinite(timeout) or not 0 < timeout <= 5400 or len(prompt.encode("utf-8")) > 1024 * 1024:
            raise ValueError("Invalid Forge Codex generation bounds")
        request_id = str(uuid4())
        path = "/generations/" + request_id
        with self._lock:
            if self._closed:
                raise CodexAppServerLifecycleError("Forge Codex bridge closed")
            self._owned.add(request_id)

        async def execute():
            await self._http(
                "POST",
                "/generations",
                payload={
                    "requestId": request_id,
                    "prompt": prompt,
                    "modelId": model_id,
                    "effortId": effort_id,
                    "responseMode": mode,
                    "timeoutSeconds": timeout,
                },
                timeout=min(timeout, 15),
            )
            while True:
                result = await self._http("GET", path, timeout=min(timeout, 15))
                status = result.get("status")
                if status in {"queued", "running"}:
                    await asyncio.sleep(0.05)
                    continue
                if status != "completed":
                    raise CodexAppServerTransportError("Forge Codex generation failed")
                text = result.get("rawText")
                if not isinstance(text, str) or not all(isinstance(result.get(k), str) for k in ("threadId", "turnId", "serverVersion")):
                    raise CodexAppServerProtocolError("Forge Codex generation result invalid")
                if mode == "json_object":
                    text = CodexGenerationPolicy().unwrap_json_object(text)
                self.version = result["serverVersion"]
                return CodexTurnResult(
                    text,
                    result["threadId"],
                    result["turnId"],
                    status,
                    result["serverVersion"],
                    result.get("tokenUsage"),
                    tuple(result.get("warnings") or ()),
                    result.get("modelMetadata") or {},
                )

        try:
            result = await asyncio.wait_for(execute(), timeout)
        except (asyncio.TimeoutError, CodexAppServerTimeout):
            await self._delete_owned(request_id)
            raise CodexAppServerTimeout("Forge Codex generation timed out") from None
        except BaseException:
            await self._delete_owned(request_id)
            raise
        else:
            with self._lock:
                self._owned.discard(request_id)
            return result

    async def _delete_owned(self, request_id):
        # Cancellation cleanup has its own small deadline, including on Python 3.10.
        try:
            await asyncio.wait_for(self._http("DELETE", "/generations/" + request_id, timeout=2), 2)
        except (CodexAppServerError, asyncio.TimeoutError, asyncio.CancelledError):
            return  # Retain ownership so close can retry; Agent enforces the job deadline.
        with self._lock:
            self._owned.discard(request_id)

    async def aclose(self):
        with self._lock:
            self._closed = True
            owned = tuple(self._owned)
        await asyncio.gather(*(self._delete_owned(job) for job in owned))
        with self._lock:
            if self._owned:
                raise CodexAppServerLifecycleError("Forge Codex job cleanup incomplete")

    @staticmethod
    def _sync(coroutine):
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(coroutine)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
            return worker.submit(asyncio.run, coroutine).result()

    def initialize_sync(self):
        return self._sync(self.initialize())

    def request_sync(self, method, params=None):
        return self._sync(self.request(method, params))

    def run_turn_sync(self, **kwargs):
        return self._sync(self.run_turn(**kwargs))

    def close(self):
        return self._sync(self.aclose())
