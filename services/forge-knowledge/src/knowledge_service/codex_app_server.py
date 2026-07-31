from __future__ import annotations

import asyncio
import concurrent.futures
import json
import re
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, ClassVar, Mapping, Sequence


class CodexAppServerError(RuntimeError):
    pass


class CodexAppServerTimeout(CodexAppServerError, TimeoutError):
    pass


class CodexAppServerTransportError(CodexAppServerError):
    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        self.status_code = status_code
        super().__init__(message)


class CodexAppServerProtocolError(CodexAppServerError):
    pass


class CodexAppServerEmptyResponse(CodexAppServerProtocolError):
    pass


@dataclass(frozen=True)
class CodexTurnResult:
    raw_text: str
    thread_id: str
    turn_id: str
    turn_status: str
    token_usage: Mapping[str, Any] | None = None
    warnings: tuple[Any, ...] = ()
    model_metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass
class _ActiveTurn:
    thread_id: str
    turn_id: str
    future: asyncio.Future[CodexTurnResult]
    unwrap_json_payload: bool = False
    messages: list[str] = field(default_factory=list)
    token_usage: Mapping[str, Any] | None = None
    warnings: list[Any] = field(default_factory=list)
    model_metadata: dict[str, Any] = field(default_factory=dict)
    interrupt_sent: bool = False


class CodexAppServerClient:
    _VERSION_PATTERN = re.compile(r"^[^/]+/([^ ]+)")
    _SIDE_EFFECT_ITEM_TYPES: ClassVar[set[str]] = {
        "commandExecution",
        "fileChange",
        "mcpToolCall",
        "dynamicToolCall",
    }

    def __init__(
        self,
        *,
        client_name: str = "forge-knowledge",
        client_version: str = "0.1.0",
        command: Sequence[str] = ("codex", "app-server", "--stdio"),
        request_timeout_seconds: float = 5.0,
        process_factory: Callable[[Sequence[str]], Awaitable[Any]] | None = None,
        runtime_cwd: Path | str | None = None,
    ) -> None:
        self._client_name = client_name
        self._client_version = client_version
        self._command = tuple(command)
        self._request_timeout_seconds = max(0.001, float(request_timeout_seconds))
        self._process_factory = process_factory or self._default_process_factory
        self._runtime_cwd = Path(runtime_cwd) if runtime_cwd is not None else Path("/tmp/forge-knowledge-codex-runtime")
        self._process: Any | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._pending: dict[int, asyncio.Future[Any]] = {}
        self._active_turns: dict[str, _ActiveTurn] = {}
        self._buffered_turn_notifications: dict[str, list[tuple[str, Any]]] = {}
        self._next_request_id = 1
        self._initialized = False
        self._version: str | None = None

        self._thread_lock = threading.RLock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._start_lock: asyncio.Lock | None = None
        self._write_lock: asyncio.Lock | None = None

    @property
    def version(self) -> str | None:
        return self._version

    async def initialize(self) -> str:
        return await self._await_threadsafe(lambda: self._initialize_public())

    async def request(self, method: str, params: Mapping[str, Any] | None = None) -> Any:
        return await self._await_threadsafe(lambda: self._request_inner(method, params))

    async def run_turn(
        self,
        *,
        prompt: str,
        model_id: str,
        effort_id: str | None,
        response_mode: Any,
        timeout_seconds: float,
    ) -> CodexTurnResult:
        return await self._await_threadsafe(
            lambda: self._run_turn_inner(
                prompt=prompt,
                model_id=model_id,
                effort_id=effort_id,
                response_mode=response_mode,
                timeout_seconds=timeout_seconds,
            )
        )

    def run_turn_sync(
        self,
        *,
        prompt: str,
        model_id: str,
        effort_id: str | None,
        response_mode: Any,
        timeout_seconds: float,
    ) -> CodexTurnResult:
        future = self._submit(
            lambda: self._run_turn_inner(
                prompt=prompt,
                model_id=model_id,
                effort_id=effort_id,
                response_mode=response_mode,
                timeout_seconds=timeout_seconds,
            )
        )
        return future.result()

    async def aclose(self) -> None:
        loop = self._loop
        if loop is not None and loop.is_running():
            await self._await_threadsafe(lambda: self._stop_process())
        self._stop_loop_thread()

    def close(self) -> None:
        loop = self._loop
        if loop is not None and loop.is_running():
            self._submit(lambda: self._stop_process()).result(timeout=3.0)
        self._stop_loop_thread()

    async def _initialize_public(self) -> str:
        await self._ensure_started()
        if self._version is None:
            raise CodexAppServerTransportError("Codex app-server did not return a version")
        return self._version

    async def _request_inner(self, method: str, params: Mapping[str, Any] | None = None, *, timeout: float | None = None) -> Any:
        await self._ensure_started()
        request_id = self._allocate_request_id()
        payload: dict[str, Any] = {"id": request_id, "method": method}
        if params is not None:
            payload["params"] = dict(params)
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await self._write_json(payload)
            return await asyncio.wait_for(future, timeout=timeout or self._request_timeout_seconds)
        except TimeoutError as exc:
            raise CodexAppServerTimeout(f"Codex app-server request timed out: {method}") from exc
        finally:
            self._pending.pop(request_id, None)

    async def _run_turn_inner(
        self,
        *,
        prompt: str,
        model_id: str,
        effort_id: str | None,
        response_mode: Any,
        timeout_seconds: float,
    ) -> CodexTurnResult:
        active: _ActiveTurn | None = None
        timeout = max(0.001, float(timeout_seconds))
        try:
            async with asyncio.timeout(timeout):
                await self._ensure_started()
                thread_result = await self._request_inner("thread/start", self._thread_start_params(), timeout=timeout)
                thread_id = self._extract_thread_id(thread_result)
                turn_params: dict[str, Any] = {
                    "threadId": thread_id,
                    "input": [{"type": "text", "text": str(prompt or "")}],
                    "model": str(model_id or ""),
                }
                if effort_id is not None:
                    turn_params["effort"] = str(effort_id)
                if str(getattr(response_mode, "value", response_mode)) == "json_object":
                    turn_params["outputSchema"] = _json_object_output_schema()
                turn_result = await self._request_inner("turn/start", turn_params, timeout=timeout)
                turn_id = self._extract_turn_id(turn_result)
                active = _ActiveTurn(
                    thread_id=thread_id,
                    turn_id=turn_id,
                    future=asyncio.get_running_loop().create_future(),
                    unwrap_json_payload=str(getattr(response_mode, "value", response_mode)) == "json_object",
                )
                self._active_turns[turn_id] = active
                self._replay_buffered_turn_notifications(turn_id)
                return await asyncio.shield(active.future)
        except TimeoutError as exc:
            if active is not None:
                await self._interrupt_turn(active)
                await self._await_terminal_after_interrupt(active)
                self._active_turns.pop(active.turn_id, None)
            raise CodexAppServerTimeout("Codex app-server turn timed out") from exc
        except asyncio.CancelledError:
            if active is not None:
                await self._interrupt_turn(active)
                self._active_turns.pop(active.turn_id, None)
            raise
        finally:
            if active is not None and active.future.done():
                self._active_turns.pop(active.turn_id, None)

    def _thread_start_params(self) -> dict[str, Any]:
        self._runtime_cwd.mkdir(parents=True, exist_ok=True)
        return {
            "ephemeral": True,
            "approvalPolicy": "never",
            "sandbox": "read-only",
            "cwd": str(self._runtime_cwd),
        }

    async def _ensure_started(self) -> None:
        self._ensure_loop_resources()
        assert self._start_lock is not None
        async with self._start_lock:
            if self._process is not None and self._returncode(self._process) is None and self._initialized:
                return
            await self._stop_process()
            try:
                self._process = await self._process_factory(self._command)
            except FileNotFoundError as exc:
                raise CodexAppServerTransportError("Codex app-server executable was not found") from exc
            self._initialized = False
            self._version = None
            self._reader_task = asyncio.create_task(self._read_stdout(), name="codex-app-server-stdout")
            self._stderr_task = asyncio.create_task(self._drain_stream(getattr(self._process, "stderr", None)), name="codex-app-server-stderr")
            try:
                response = await self._initialize_request()
                user_agent = _non_blank(response.get("userAgent")) if isinstance(response, Mapping) else None
                version = self._extract_version(user_agent)
                if version is None:
                    raise CodexAppServerProtocolError("Codex app-server did not return a version")
                self._version = version
                self._initialized = True
                await self._write_json({"method": "initialized", "params": {}})
            except BaseException:
                cleanup = asyncio.create_task(self._stop_process())
                try:
                    await asyncio.shield(cleanup)
                except asyncio.CancelledError:
                    try:
                        await cleanup
                    except Exception:  # noqa: BLE001, S110 - cleanup is best-effort while preserving the original failure.
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
        finally:
            self._pending.pop(request_id, None)

    async def _write_json(self, payload: Mapping[str, Any]) -> None:
        self._ensure_loop_resources()
        process = self._process
        if process is None or self._returncode(process) is not None:
            raise CodexAppServerTransportError("Codex app-server process is not running")
        stdin = getattr(process, "stdin", None)
        if stdin is None:
            raise CodexAppServerTransportError("Codex app-server stdin is unavailable")
        encoded = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        assert self._write_lock is not None
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
        except Exception as exc:  # noqa: BLE001 - stdout reader must fail pending work for any reader crash.
            self._fail_connection(exc)
        finally:
            self._initialized = False
            self._fail_connection(CodexAppServerTransportError("Codex app-server exited before response"))

    async def _drain_stream(self, stream: Any | None) -> None:
        if stream is None:
            return
        try:
            while await stream.readline():
                pass
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - stderr is diagnostic-only and must not kill the transport.
            return

    def _handle_line(self, line: bytes | str) -> None:
        text = line.decode("utf-8", errors="replace") if isinstance(line, bytes) else str(line)
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            self._fail_connection(CodexAppServerProtocolError("Codex app-server emitted malformed JSON-RPC"))
            return
        if not isinstance(payload, Mapping):
            self._fail_connection(CodexAppServerProtocolError("Codex app-server emitted malformed JSON-RPC envelope"))
            return
        if "method" in payload and "id" in payload:
            self._handle_server_request(payload)
            return
        if "method" in payload:
            self._handle_notification(payload)
            return
        if "id" in payload:
            self._handle_response(payload)
            return
        self._fail_connection(CodexAppServerProtocolError("Codex app-server emitted unclassifiable JSON-RPC envelope"))

    def _handle_response(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("id")
        if not isinstance(request_id, int):
            self._fail_connection(CodexAppServerProtocolError("Codex app-server response id was invalid"))
            return
        future = self._pending.pop(request_id, None)
        if future is None or future.done():
            return
        if "error" in payload:
            future.set_exception(_error_from_envelope(payload.get("error")))
            return
        if "result" not in payload:
            future.set_exception(CodexAppServerProtocolError("Codex app-server response omitted result"))
            return
        future.set_result(payload.get("result"))

    def _handle_notification(self, payload: Mapping[str, Any]) -> None:
        method = payload.get("method")
        params = payload.get("params")
        if not isinstance(method, str):
            self._fail_connection(CodexAppServerProtocolError("Codex app-server notification method was invalid"))
            return
        if method == "item/completed":
            self._handle_item_completed(params)
        elif method == "turn/completed":
            self._handle_turn_completed(params)

    def _handle_server_request(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("id")
        if isinstance(request_id, int):
            asyncio.create_task(
                self._write_json(
                    {
                        "id": request_id,
                        "error": {
                            "code": -32601,
                            "message": "Codex server requests are not supported by Forge Knowledge generation",
                        },
                    }
                )
            )
        params = payload.get("params")
        turn_id = _extract_turn_id(params)
        active = self._active_turns.get(turn_id or "") if turn_id is not None else None
        if active is not None and not active.future.done():
            asyncio.create_task(self._fail_active_turn(active, CodexAppServerProtocolError("Codex app-server requested unsupported side effect")))
        elif self._active_turns:
            for active_turn in tuple(self._active_turns.values()):
                if not active_turn.future.done():
                    asyncio.create_task(
                        self._fail_active_turn(
                            active_turn,
                            CodexAppServerProtocolError("Codex app-server requested unsupported side effect"),
                        )
                    )

    def _handle_item_completed(self, params: Any) -> None:
        if not isinstance(params, Mapping):
            self._fail_connection(CodexAppServerProtocolError("Codex item/completed params must be an object"))
            return
        turn_id = _extract_turn_id(params)
        active = self._active_turns.get(turn_id or "")
        if active is None or active.future.done():
            if turn_id is not None:
                self._buffer_turn_notification(turn_id, "item/completed", params)
            return
        item = params.get("item") if isinstance(params.get("item"), Mapping) else params
        if not isinstance(item, Mapping):
            self._fail_connection(CodexAppServerProtocolError("Codex item/completed item must be an object"))
            return
        item_type = _non_blank(item.get("type"))
        if item_type in self._SIDE_EFFECT_ITEM_TYPES:
            asyncio.create_task(self._fail_active_turn(active, CodexAppServerProtocolError(f"Codex emitted forbidden side-effect item: {item_type}")))
            return
        if item_type == "agentMessage":
            text = _extract_text(item)
            if text is not None:
                active.messages.append(text)

    def _handle_turn_completed(self, params: Any) -> None:
        if not isinstance(params, Mapping):
            self._fail_connection(CodexAppServerProtocolError("Codex turn/completed params must be an object"))
            return
        turn_id = _extract_turn_id(params)
        active = self._active_turns.get(turn_id or "")
        if active is None or active.future.done():
            if turn_id is not None:
                self._buffer_turn_notification(turn_id, "turn/completed", params)
            return
        status = _non_blank(params.get("status"))
        turn = params.get("turn")
        if status is None and isinstance(turn, Mapping):
            status = _non_blank(turn.get("status"))
        status = status or "completed"
        usage = params.get("tokenUsage") or params.get("usage")
        if usage is None and isinstance(turn, Mapping):
            usage = turn.get("tokenUsage") or turn.get("usage")
        if isinstance(usage, Mapping):
            active.token_usage = dict(usage)
        warnings = params.get("warnings")
        if isinstance(warnings, list):
            active.warnings.extend(warnings)
        for key in ("model", "modelId", "requestedModel", "resolvedModel", "serviceTier"):
            value = params.get(key)
            if value is not None and isinstance(value, (str, int, float, bool)):
                active.model_metadata[key] = value
        if not active.messages:
            fallback = _extract_text(params)
            if fallback is None and isinstance(turn, Mapping):
                fallback = _extract_text(turn)
            if fallback is not None:
                active.messages.append(fallback)
        if status == "completed":
            raw_text = active.messages[-1] if active.messages else ""
            if active.unwrap_json_payload:
                raw_text = _unwrap_json_payload(raw_text)
            if not raw_text.strip():
                active.future.set_exception(CodexAppServerEmptyResponse("Codex turn completed without agent message"))
                return
            active.future.set_result(
                CodexTurnResult(
                    raw_text=raw_text,
                    thread_id=active.thread_id,
                    turn_id=active.turn_id,
                    turn_status=status,
                    token_usage=active.token_usage,
                    warnings=tuple(active.warnings),
                    model_metadata=dict(active.model_metadata),
                )
            )
            return
        if status == "failed":
            active.future.set_exception(_error_from_envelope(params.get("error") or params.get("codexErrorInfo")))
            return
        if status == "interrupted":
            active.future.set_exception(CodexAppServerTransportError("Codex turn was interrupted unexpectedly"))
            return
        active.future.set_exception(CodexAppServerProtocolError(f"Codex turn completed with unknown status: {status}"))

    async def _fail_active_turn(self, active: _ActiveTurn, exc: Exception) -> None:
        await self._interrupt_turn(active)
        if not active.future.done():
            active.future.set_exception(exc)

    async def _interrupt_turn(self, active: _ActiveTurn) -> None:
        if active.interrupt_sent:
            return
        active.interrupt_sent = True
        try:
            await self._request_inner("turn/interrupt", {"threadId": active.thread_id, "turnId": active.turn_id}, timeout=min(1.0, self._request_timeout_seconds))
        except CodexAppServerError:
            return

    async def _await_terminal_after_interrupt(self, active: _ActiveTurn) -> None:
        try:
            await asyncio.wait_for(asyncio.shield(active.future), timeout=min(1.0, self._request_timeout_seconds))
        except (Exception, asyncio.CancelledError):  # noqa: BLE001 - terminal wait is best-effort after interrupt.
            return

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
                except Exception:  # noqa: BLE001, S110 - shutdown continues after task cleanup failures.
                    pass
        self._reader_task = None
        self._stderr_task = None
        self._fail_connection(CodexAppServerTransportError("Codex app-server stopped"))
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

    def _extract_required_id(self, payload: Any, key: str, method: str) -> str:
        if not isinstance(payload, Mapping):
            raise CodexAppServerProtocolError(f"Codex {method} result must be an object")
        value = _non_blank(payload.get(key)) or _non_blank(payload.get("id"))
        if value is None:
            raise CodexAppServerProtocolError(f"Codex {method} result omitted {key}")
        return value

    def _extract_thread_id(self, payload: Any) -> str:
        if isinstance(payload, Mapping):
            thread = payload.get("thread")
            if isinstance(thread, Mapping):
                value = _non_blank(thread.get("id"))
                if value is not None:
                    return value
        return self._extract_required_id(payload, "threadId", "thread/start")

    def _extract_turn_id(self, payload: Any) -> str:
        if isinstance(payload, Mapping):
            turn = payload.get("turn")
            if isinstance(turn, Mapping):
                value = _non_blank(turn.get("id"))
                if value is not None:
                    return value
        return self._extract_required_id(payload, "turnId", "turn/start")

    def _extract_version(self, user_agent: str | None) -> str | None:
        if user_agent is None:
            return None
        match = self._VERSION_PATTERN.match(user_agent)
        return match.group(1) if match else None

    def _fail_connection(self, exc: Exception) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(exc)
        self._pending.clear()
        for active in self._active_turns.values():
            if not active.future.done():
                active.future.set_exception(exc)
        self._active_turns.clear()
        self._buffered_turn_notifications.clear()

    def _buffer_turn_notification(self, turn_id: str, method: str, params: Any) -> None:
        buffered = self._buffered_turn_notifications.setdefault(turn_id, [])
        buffered.append((method, params))
        if len(buffered) > 100:
            buffered.pop(0)

    def _replay_buffered_turn_notifications(self, turn_id: str) -> None:
        buffered = self._buffered_turn_notifications.pop(turn_id, [])
        for method, params in buffered:
            if method == "item/completed":
                self._handle_item_completed(params)
            elif method == "turn/completed":
                self._handle_turn_completed(params)

    def _returncode(self, process: Any) -> int | None:
        return getattr(process, "returncode", None)

    def _ensure_loop_resources(self) -> None:
        if self._start_lock is None:
            self._start_lock = asyncio.Lock()
        if self._write_lock is None:
            self._write_lock = asyncio.Lock()

    async def _await_threadsafe(self, coro_factory: Callable[[], Awaitable[Any]]) -> Any:
        future = self._submit(coro_factory)
        try:
            return await asyncio.wrap_future(future)
        except asyncio.CancelledError:
            future.cancel()
            for _ in range(100):
                if future.done():
                    break
                await asyncio.sleep(0.01)
            raise

    def _submit(self, coro_factory: Callable[[], Awaitable[Any]]) -> concurrent.futures.Future[Any]:
        loop = self._ensure_loop_thread()
        return asyncio.run_coroutine_threadsafe(coro_factory(), loop)

    def _ensure_loop_thread(self) -> asyncio.AbstractEventLoop:
        with self._thread_lock:
            if self._loop is not None and self._loop.is_running():
                return self._loop
            loop = asyncio.new_event_loop()
            ready = threading.Event()

            def run() -> None:
                asyncio.set_event_loop(loop)
                ready.set()
                loop.run_forever()

            thread = threading.Thread(target=run, name="codex-app-server-loop", daemon=True)
            thread.start()
            ready.wait()
            self._loop = loop
            self._thread = thread
            return loop

    def _stop_loop_thread(self) -> None:
        with self._thread_lock:
            loop = self._loop
            thread = self._thread
            self._loop = None
            self._thread = None
            self._start_lock = None
            self._write_lock = None
        if loop is not None and loop.is_running():
            loop.call_soon_threadsafe(loop.stop)
        if thread is not None and thread.is_alive():
            thread.join(timeout=2.0)
        if loop is not None and not loop.is_closed():
            loop.close()


def _json_object_output_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {
            "json": {
                "type": "string",
                "description": "The complete JSON object requested by the user, serialized as a JSON string. The string itself must parse as a JSON object.",
            }
        },
        "required": ["json"],
        "additionalProperties": False,
    }


def _unwrap_json_payload(raw_text: str) -> str:
    try:
        payload = json.loads(raw_text)
    except json.JSONDecodeError:
        return raw_text
    if not isinstance(payload, Mapping):
        return raw_text
    nested = payload.get("json")
    if not isinstance(nested, str):
        return raw_text
    try:
        parsed = json.loads(nested)
    except json.JSONDecodeError:
        return raw_text
    if not isinstance(parsed, Mapping):
        return raw_text
    return nested


def _error_from_envelope(error: Any) -> CodexAppServerError:
    status_code: int | None = None
    message = "Codex app-server returned an error"
    if isinstance(error, Mapping):
        raw_message = _non_blank(error.get("message"))
        if raw_message is not None:
            message = raw_message
        info = error.get("codexErrorInfo")
        if not isinstance(info, Mapping):
            info = error.get("data") if isinstance(error.get("data"), Mapping) else None
        if isinstance(info, Mapping):
            raw_status = info.get("status") or info.get("statusCode") or info.get("httpStatus")
            try:
                status_code = int(raw_status) if raw_status is not None else None
            except (TypeError, ValueError):
                status_code = None
    return CodexAppServerTransportError(message, status_code=status_code)


def _extract_turn_id(params: Any) -> str | None:
    if not isinstance(params, Mapping):
        return None
    for key in ("turnId", "turn_id"):
        value = _non_blank(params.get(key))
        if value is not None:
            return value
    item = params.get("item")
    if isinstance(item, Mapping):
        for key in ("turnId", "turn_id"):
            value = _non_blank(item.get(key))
            if value is not None:
                return value
    turn = params.get("turn")
    if isinstance(turn, Mapping):
        for key in ("turnId", "turn_id", "id"):
            value = _non_blank(turn.get(key))
            if value is not None:
                return value
    return None


def _extract_text(payload: Mapping[str, Any]) -> str | None:
    value = payload.get("text")
    if isinstance(value, str):
        return value
    value = payload.get("content")
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts: list[str] = []
        for part in value:
            if isinstance(part, Mapping) and isinstance(part.get("text"), str):
                parts.append(part["text"])
        if parts:
            return "".join(parts)
    message = payload.get("message")
    if isinstance(message, Mapping):
        return _extract_text(message)
    if isinstance(message, str):
        return message
    return None


def _non_blank(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    stripped = value.strip()
    return stripped or None
