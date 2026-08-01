from __future__ import annotations

import asyncio
import concurrent.futures
import json
import logging
import re
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, ClassVar, Coroutine, Mapping, Sequence, cast

from knowledge_service import __application_name__, __version__

LOGGER = logging.getLogger(__name__)


class CodexAppServerError(RuntimeError):
    pass


class CodexAppServerTimeout(CodexAppServerError, TimeoutError):
    pass


class CodexAppServerLifecycleError(CodexAppServerError):
    pass


class CodexAppServerTransportError(CodexAppServerError):
    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        self.status_code = status_code
        super().__init__(message)


class CodexAppServerProtocolError(CodexAppServerError):
    pass


class CodexAppServerEmptyResponse(CodexAppServerProtocolError):
    pass


class CodexProtocol:
    INITIALIZE = "initialize"
    INITIALIZED = "initialized"
    MODEL_LIST = "model/list"
    RATE_LIMITS_READ = "account/rateLimits/read"
    THREAD_START = "thread/start"
    TURN_START = "turn/start"
    TURN_INTERRUPT = "turn/interrupt"
    ITEM_STARTED = "item/started"
    ITEM_COMPLETED = "item/completed"
    TURN_COMPLETED = "turn/completed"
    COMMAND_APPROVAL = "item/commandExecution/requestApproval"
    FILE_CHANGE_APPROVAL = "item/fileChange/requestApproval"
    APPROVAL_POLICY = "never"
    SANDBOX = "read-only"
    DECLINE = "decline"


@dataclass(frozen=True)
class CodexNotificationBufferPolicy:
    max_per_turn: int = 100
    max_turn_ids: int = 100
    max_age_seconds: float = 30.0

    def __post_init__(self) -> None:
        if self.max_per_turn < 1:
            raise ValueError("max_per_turn must be positive")
        if self.max_turn_ids < 1:
            raise ValueError("max_turn_ids must be positive")
        if self.max_age_seconds <= 0:
            raise ValueError("max_age_seconds must be positive")


@dataclass(frozen=True)
class CodexRuntimeSettings:
    command: tuple[str, ...]
    runtime_cwd: Path
    client_name: str
    client_version: str
    request_timeout_seconds: float = 5.0
    interrupt_grace_seconds: float = 1.0
    terminal_after_interrupt_seconds: float = 1.0
    terminate_grace_seconds: float = 1.0
    kill_grace_seconds: float = 1.0
    sync_close_timeout_seconds: float = 3.0
    loop_thread_join_timeout_seconds: float = 2.0
    cancellation_cleanup_timeout_seconds: float = 1.0
    notification_buffer: CodexNotificationBufferPolicy = field(default_factory=CodexNotificationBufferPolicy)

    def __post_init__(self) -> None:
        if not self.command:
            raise ValueError("Codex command is required")
        if not Path(self.runtime_cwd).is_absolute():
            raise ValueError("Codex runtime_cwd must be absolute")


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
    failed_closed: bool = False


@dataclass(frozen=True)
class _BufferedNotification:
    method: str
    params: Any
    created_at: float


class CodexGenerationPolicy:
    SAFE_ITEM_TYPES: ClassVar[set[str]] = {
        "userMessage",
        "reasoning",
        "agentMessage",
        "plan",
        "contextCompaction",
    }
    FORBIDDEN_ITEM_TYPES: ClassVar[set[str]] = {
        "commandExecution",
        "fileChange",
        "mcpToolCall",
        "dynamicToolCall",
        "webSearch",
        "collabToolCall",
        "imageView",
    }
    APPROVAL_RESPONSES: ClassVar[dict[str, Mapping[str, str]]] = {
        CodexProtocol.COMMAND_APPROVAL: {"decision": CodexProtocol.DECLINE},
        CodexProtocol.FILE_CHANGE_APPROVAL: {"decision": CodexProtocol.DECLINE},
    }
    TERMINAL_STATUSES: ClassVar[set[str]] = {"completed", "failed", "interrupted"}

    def approval_response(self, method: str) -> Mapping[str, str] | None:
        return self.APPROVAL_RESPONSES.get(method)

    def validate_generation_item_type(self, item_type: str | None) -> CodexAppServerProtocolError | None:
        if item_type in self.SAFE_ITEM_TYPES:
            return None
        if item_type in self.FORBIDDEN_ITEM_TYPES:
            return CodexAppServerProtocolError(f"Codex emitted forbidden side-effect item: {item_type}")
        return CodexAppServerProtocolError(f"Codex emitted unknown generation item type: {item_type or '<missing>'}")

    def validate_terminal_status(self, status: str | None) -> str:
        if status is None:
            raise CodexAppServerProtocolError("Codex turn/completed omitted status")
        if status not in self.TERMINAL_STATUSES:
            raise CodexAppServerProtocolError(f"Codex turn completed with unknown status: {status}")
        return status

    def unwrap_json_object(self, raw_text: str) -> str:
        try:
            payload = json.loads(raw_text)
        except json.JSONDecodeError as exc:
            raise CodexAppServerProtocolError("Codex JSON_OBJECT wrapper was not valid JSON") from exc
        if not isinstance(payload, dict):
            raise CodexAppServerProtocolError("Codex JSON_OBJECT wrapper root was not an object")
        if set(payload) != {"json"}:
            raise CodexAppServerProtocolError("Codex JSON_OBJECT wrapper must contain only json")
        nested = payload.get("json")
        if not isinstance(nested, str):
            raise CodexAppServerProtocolError("Codex JSON_OBJECT json field must be a string")
        try:
            parsed = json.loads(nested)
        except json.JSONDecodeError as exc:
            raise CodexAppServerProtocolError("Codex JSON_OBJECT nested payload was not valid JSON") from exc
        if not isinstance(parsed, dict):
            raise CodexAppServerProtocolError("Codex JSON_OBJECT nested payload root was not an object")
        return nested


class CodexProcessTransport:
    def __init__(
        self,
        settings: CodexRuntimeSettings,
        *,
        process_factory: Callable[[Sequence[str]], Awaitable[Any]],
        on_notification: Callable[[str, Any], None],
        on_server_request: Callable[[int, str, Any], Mapping[str, Any] | None],
        on_connection_failed: Callable[[Exception], None],
    ) -> None:
        self._settings = settings
        self._process_factory = process_factory
        self._on_notification = on_notification
        self._on_server_request = on_server_request
        self._on_connection_failed = on_connection_failed
        self._process: Any | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._pending: dict[int, asyncio.Future[Any]] = {}
        self._next_request_id = 1
        self._start_lock: asyncio.Lock | None = None
        self._write_lock: asyncio.Lock | None = None
        self._connection_invalidated = False
        self._accepting = True

    async def start(self) -> None:
        self._ensure_locks()
        assert self._start_lock is not None
        async with self._start_lock:
            if self.is_healthy:
                return
            await self.stop(CodexAppServerTransportError("Codex app-server restarting"))
            self._accepting = True
            try:
                self._process = await self._process_factory(self._settings.command)
            except FileNotFoundError as exc:
                raise CodexAppServerTransportError("Codex app-server executable was not found") from exc
            self._connection_invalidated = False
            self._reader_task = asyncio.create_task(self._read_stdout(), name="codex-app-server-stdout")
            self._stderr_task = asyncio.create_task(self._drain_stream(getattr(self._process, "stderr", None)), name="codex-app-server-stderr")

    @property
    def is_healthy(self) -> bool:
        return self._process is not None and self._returncode(self._process) is None and not self._connection_invalidated

    async def request(self, method: str, params: Mapping[str, Any] | None = None, *, timeout: float | None = None) -> Any:
        if not self._accepting:
            raise CodexAppServerTransportError("Codex app-server client is closing")
        await self.start()
        request_id = self._allocate_request_id()
        payload: dict[str, Any] = {"id": request_id, "method": method}
        if params is not None:
            payload["params"] = dict(params)
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await self._write_json(payload)
            return await asyncio.wait_for(future, timeout=timeout or self._settings.request_timeout_seconds)
        except TimeoutError as exc:
            raise CodexAppServerTimeout(f"Codex app-server request timed out: {method}") from exc
        finally:
            self._pending.pop(request_id, None)

    async def write_notification(self, method: str, params: Mapping[str, Any] | None = None) -> None:
        payload: dict[str, Any] = {"method": method}
        if params is not None:
            payload["params"] = dict(params)
        await self._write_json(payload)

    async def stop(self, exc: Exception | None = None) -> None:
        self._accepting = False
        for task in (self._reader_task, self._stderr_task):
            if task is not None:
                task.cancel()
        for task in (self._reader_task, self._stderr_task):
            if task is None:
                continue
            try:
                await task
            except asyncio.CancelledError:
                pass
            except Exception as cleanup_exc:
                LOGGER.debug("Codex app-server reader task failed during shutdown", exc_info=cleanup_exc)
        self._reader_task = None
        self._stderr_task = None
        self._fail_pending(exc or CodexAppServerTransportError("Codex app-server stopped"))
        process = self._process
        self._process = None
        self._connection_invalidated = False
        if process is not None:
            await self._terminate_process(process)

    async def invalidate(self, exc: Exception) -> None:
        self._connection_invalidated = True
        self._fail_pending(exc)
        self._on_connection_failed(exc)
        process = self._process
        self._process = None
        if process is not None and self._returncode(process) is None:
            await self._terminate_process(process)
        stderr_task = self._stderr_task
        if stderr_task is not None and not stderr_task.done():
            stderr_task.cancel()

    async def _terminate_process(self, process: Any) -> None:
        if self._returncode(process) is not None:
            return
        terminate = getattr(process, "terminate", None)
        if callable(terminate):
            terminate()
        wait = getattr(process, "wait", None)
        if not callable(wait):
            return
        try:
            await asyncio.wait_for(wait(), timeout=self._settings.terminate_grace_seconds)
            return
        except TimeoutError:
            kill = getattr(process, "kill", None)
            if callable(kill):
                kill()
            try:
                await asyncio.wait_for(wait(), timeout=self._settings.kill_grace_seconds)
            except TimeoutError as exc:
                raise CodexAppServerLifecycleError("Codex app-server process did not exit after kill") from exc

    async def _write_json(self, payload: Mapping[str, Any]) -> None:
        process = self._process
        if process is None or self._returncode(process) is not None:
            raise CodexAppServerTransportError("Codex app-server process is not running")
        stdin = getattr(process, "stdin", None)
        if stdin is None:
            raise CodexAppServerTransportError("Codex app-server stdin is unavailable")
        encoded = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        self._ensure_locks()
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
                if self._connection_invalidated:
                    break
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - reader task is the transport failure boundary.
            await self.invalidate(exc)
        finally:
            if not self._connection_invalidated and self._process is not None:
                exit_error = CodexAppServerTransportError("Codex app-server exited before response")
                self._fail_pending(exit_error)
                self._on_connection_failed(exit_error)

    async def _drain_stream(self, stream: Any | None) -> None:
        if stream is None:
            return
        try:
            while await stream.readline():
                pass
        except asyncio.CancelledError:
            raise
        except Exception as drain_exc:
            LOGGER.debug("Codex app-server stream drain failed", exc_info=drain_exc)
            return

    def _handle_line(self, line: bytes | str) -> None:
        text = line.decode("utf-8", errors="replace") if isinstance(line, bytes) else str(line)
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server emitted malformed JSON-RPC")))
            return
        if not isinstance(payload, Mapping):
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server emitted malformed JSON-RPC envelope")))
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
        asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server emitted unclassifiable JSON-RPC envelope")))

    def _handle_response(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("id")
        if type(request_id) is not int:
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server response id was invalid")))
            return
        future = self._pending.get(request_id)
        if future is None or future.done():
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server response id did not match a pending request")))
            return
        if "error" in payload:
            self._pending.pop(request_id, None)
            future.set_exception(_error_from_envelope(payload.get("error")))
            return
        if "result" in payload:
            self._pending.pop(request_id, None)
            future.set_result(payload.get("result"))
            return
        exc = CodexAppServerProtocolError("Codex app-server response omitted result and error")
        future.set_exception(exc)
        asyncio.create_task(self.invalidate(exc))

    def _handle_notification(self, payload: Mapping[str, Any]) -> None:
        method = payload.get("method")
        params = payload.get("params")
        if not isinstance(method, str):
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server notification method was invalid")))
            return
        self._on_notification(method, params)

    def _handle_server_request(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("id")
        method = payload.get("method")
        params = payload.get("params")
        if type(request_id) is not int:
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server server request id was invalid")))
            return
        if not isinstance(method, str):
            asyncio.create_task(self.invalidate(CodexAppServerProtocolError("Codex app-server server request method was invalid")))
            return
        try:
            response = self._on_server_request(request_id, method, params)
        except CodexAppServerProtocolError as exc:
            asyncio.create_task(self.invalidate(exc))
            return
        if response is not None:
            asyncio.create_task(self._write_json(response))

    def _allocate_request_id(self) -> int:
        request_id = self._next_request_id
        self._next_request_id += 1
        return request_id

    def _fail_pending(self, exc: Exception) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(exc)
        self._pending.clear()

    def _ensure_locks(self) -> None:
        if self._start_lock is None:
            self._start_lock = asyncio.Lock()
        if self._write_lock is None:
            self._write_lock = asyncio.Lock()

    def _returncode(self, process: Any) -> int | None:
        return getattr(process, "returncode", None)


class CodexTurnExecutor:
    def __init__(self, settings: CodexRuntimeSettings, policy: CodexGenerationPolicy, transport_getter: Callable[[], CodexProcessTransport]) -> None:
        self._settings = settings
        self._policy = policy
        self._transport_getter = transport_getter
        self._active_turns: dict[str, _ActiveTurn] = {}
        self._buffered_turn_notifications: dict[str, list[_BufferedNotification]] = {}

    async def run_turn(
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
                transport = self._transport_getter()
                thread_result = await transport.request(CodexProtocol.THREAD_START, self._thread_start_params(), timeout=timeout)
                thread_id = self._extract_thread_id(thread_result)
                turn_params: dict[str, Any] = {
                    "threadId": thread_id,
                    "input": [{"type": "text", "text": str(prompt or "")}],
                    "model": str(model_id or ""),
                }
                if effort_id is not None:
                    turn_params["effort"] = str(effort_id)
                json_mode = str(getattr(response_mode, "value", response_mode)) == "json_object"
                if json_mode:
                    turn_params["outputSchema"] = _json_object_output_schema()
                turn_result = await transport.request(CodexProtocol.TURN_START, turn_params, timeout=timeout)
                turn_id = self._extract_turn_id(turn_result)
                active = _ActiveTurn(
                    thread_id=thread_id,
                    turn_id=turn_id,
                    future=asyncio.get_running_loop().create_future(),
                    unwrap_json_payload=json_mode,
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

    def handle_notification(self, method: str, params: Any) -> None:
        if method == CodexProtocol.ITEM_STARTED:
            self._handle_item_event(CodexProtocol.ITEM_STARTED, params)
        elif method == CodexProtocol.ITEM_COMPLETED:
            self._handle_item_event(CodexProtocol.ITEM_COMPLETED, params)
        elif method == CodexProtocol.TURN_COMPLETED:
            self._handle_turn_completed(params)

    def handle_server_request(self, request_id: int, method: str, params: Any) -> Mapping[str, Any]:
        if params is not None and not isinstance(params, Mapping):
            raise CodexAppServerProtocolError("Codex server request params must be an object")
        approval_response = self._policy.approval_response(method)
        response: Mapping[str, Any]
        if approval_response is not None:
            response = {"id": request_id, "result": dict(approval_response)}
        else:
            response = {
                "id": request_id,
                "error": {
                    "code": -32601,
                    "message": "Codex server requests are not supported by Forge Knowledge generation",
                },
            }
        turn_id = _extract_turn_id(params)
        if params is not None and "turnId" in params and turn_id is None:
            raise CodexAppServerProtocolError("Codex server request turnId was invalid")
        active = self._active_turns.get(turn_id or "") if turn_id is not None else None
        if active is not None and not active.future.done():
            self._schedule_fail_active_turn(active, CodexAppServerProtocolError("Codex app-server requested unsupported side effect"))
        elif turn_id is None:
            for active_turn in tuple(self._active_turns.values()):
                if not active_turn.future.done():
                    self._schedule_fail_active_turn(active_turn, CodexAppServerProtocolError("Codex app-server requested unsupported side effect"))
        return response

    def fail_active_work(self, exc: Exception) -> None:
        for active in self._active_turns.values():
            if not active.future.done():
                active.future.set_exception(exc)
        self._active_turns.clear()
        self._buffered_turn_notifications.clear()

    def _thread_start_params(self) -> dict[str, Any]:
        self._settings.runtime_cwd.mkdir(parents=True, exist_ok=True)
        return {
            "ephemeral": True,
            "approvalPolicy": CodexProtocol.APPROVAL_POLICY,
            "sandbox": CodexProtocol.SANDBOX,
            "cwd": str(self._settings.runtime_cwd),
        }

    def _handle_item_event(self, method: str, params: Any) -> None:
        if not isinstance(params, Mapping):
            raise CodexAppServerProtocolError(f"Codex {method} params must be an object")
        turn_id = _extract_turn_id(params)
        item = params.get("item") if "item" in params else params
        if not isinstance(item, Mapping):
            raise CodexAppServerProtocolError(f"Codex {method} item must be an object")
        if turn_id is None:
            raise CodexAppServerProtocolError(f"Codex {method} omitted turnId")
        item_type = _non_blank(item.get("type"))
        violation = self._policy.validate_generation_item_type(item_type)
        active = self._active_turns.get(turn_id)
        if active is None or active.future.done():
            self._buffer_turn_notification(turn_id, method, params)
            return
        if violation is not None:
            self._schedule_fail_active_turn(active, violation)
            return
        if method == CodexProtocol.ITEM_COMPLETED and item_type == "agentMessage":
            text = _extract_text(item)
            if text is not None:
                active.messages.append(text)

    def _handle_turn_completed(self, params: Any) -> None:
        if not isinstance(params, Mapping):
            raise CodexAppServerProtocolError("Codex turn/completed params must be an object")
        turn_id = _extract_turn_id(params)
        if turn_id is None:
            raise CodexAppServerProtocolError("Codex turn/completed omitted turnId")
        active = self._active_turns.get(turn_id)
        if active is None or active.future.done():
            self._buffer_turn_notification(turn_id, CodexProtocol.TURN_COMPLETED, params)
            return
        self._complete_active_turn(active, params)

    def _complete_active_turn(self, active: _ActiveTurn, params: Mapping[str, Any]) -> None:
        if active.failed_closed:
            return
        try:
            status = self._policy.validate_terminal_status(_non_blank(params.get("status")))
        except CodexAppServerProtocolError as exc:
            active.future.set_exception(exc)
            raise
        usage = params.get("tokenUsage")
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
            if fallback is not None:
                active.messages.append(fallback)
        if status == "completed":
            raw_text = active.messages[-1] if active.messages else ""
            if active.unwrap_json_payload:
                raw_text = self._policy.unwrap_json_object(raw_text)
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
        active.future.set_exception(CodexAppServerTransportError("Codex turn was interrupted unexpectedly"))

    def _schedule_fail_active_turn(self, active: _ActiveTurn, exc: Exception) -> None:
        active.failed_closed = True
        active.messages.clear()
        asyncio.create_task(self._fail_active_turn(active, exc))

    async def _fail_active_turn(self, active: _ActiveTurn, exc: Exception) -> None:
        active.failed_closed = True
        active.messages.clear()
        await self._interrupt_turn(active)
        if not active.future.done():
            active.future.set_exception(exc)

    async def _interrupt_turn(self, active: _ActiveTurn) -> None:
        if active.interrupt_sent:
            return
        active.interrupt_sent = True
        try:
            await self._transport_getter().request(
                CodexProtocol.TURN_INTERRUPT,
                {"threadId": active.thread_id, "turnId": active.turn_id},
                timeout=self._settings.interrupt_grace_seconds,
            )
        except CodexAppServerError:
            return

    async def _await_terminal_after_interrupt(self, active: _ActiveTurn) -> None:
        try:
            await asyncio.wait_for(asyncio.shield(active.future), timeout=self._settings.terminal_after_interrupt_seconds)
        except (CodexAppServerError, TimeoutError, asyncio.CancelledError):
            return

    def _buffer_turn_notification(self, turn_id: str, method: str, params: Any) -> None:
        self._prune_expired_buffered_notifications()
        policy = self._settings.notification_buffer
        if turn_id not in self._buffered_turn_notifications and len(self._buffered_turn_notifications) >= policy.max_turn_ids:
            raise CodexAppServerProtocolError("Codex buffered notification turn-id limit exceeded")
        buffered = self._buffered_turn_notifications.setdefault(turn_id, [])
        if len(buffered) >= policy.max_per_turn:
            raise CodexAppServerProtocolError("Codex buffered notification per-turn limit exceeded")
        buffered.append(_BufferedNotification(method=method, params=params, created_at=time.monotonic()))

    def _prune_expired_buffered_notifications(self) -> None:
        cutoff = time.monotonic() - self._settings.notification_buffer.max_age_seconds
        expired = [turn_id for turn_id, items in self._buffered_turn_notifications.items() if any(item.created_at < cutoff for item in items)]
        for turn_id in expired:
            self._buffered_turn_notifications.pop(turn_id, None)

    def _replay_buffered_turn_notifications(self, turn_id: str) -> None:
        self._prune_expired_buffered_notifications()
        buffered = self._buffered_turn_notifications.pop(turn_id, [])
        for event in buffered:
            if event.method in {CodexProtocol.ITEM_STARTED, CodexProtocol.ITEM_COMPLETED}:
                self._handle_item_event(event.method, event.params)
            elif event.method == CodexProtocol.TURN_COMPLETED:
                self._handle_turn_completed(event.params)

    def _extract_required_id(self, payload: Any, key: str, method: str) -> str:
        if not isinstance(payload, Mapping):
            raise CodexAppServerProtocolError(f"Codex {method} result must be an object")
        value = _non_blank(payload.get(key))
        if value is None:
            raise CodexAppServerProtocolError(f"Codex {method} result omitted {key}")
        return value

    def _extract_thread_id(self, payload: Any) -> str:
        return self._extract_required_id(payload, "threadId", CodexProtocol.THREAD_START)

    def _extract_turn_id(self, payload: Any) -> str:
        return self._extract_required_id(payload, "turnId", CodexProtocol.TURN_START)


class CodexAppServerClient:
    _VERSION_PATTERN = re.compile(r"^[^/]+/([^ ]+)")

    def __init__(
        self,
        *,
        settings: CodexRuntimeSettings | None = None,
        client_name: str | None = None,
        client_version: str | None = None,
        command: Sequence[str] = ("codex", "app-server", "--stdio"),
        request_timeout_seconds: float = 5.0,
        process_factory: Callable[[Sequence[str]], Awaitable[Any]] | None = None,
        runtime_cwd: Path | str | None = None,
    ) -> None:
        if settings is None:
            if runtime_cwd is None:
                raise ValueError("Codex runtime_cwd is required")
            settings = CodexRuntimeSettings(
                command=tuple(command),
                runtime_cwd=Path(runtime_cwd),
                client_name=client_name or __application_name__,
                client_version=client_version or __version__,
                request_timeout_seconds=max(0.001, float(request_timeout_seconds)),
            )
        self._settings = settings
        self._policy = CodexGenerationPolicy()
        self._transport: CodexProcessTransport | None = None
        self._turn_executor = CodexTurnExecutor(settings, self._policy, self._require_transport)
        self._process_factory = process_factory or self._default_process_factory
        self._initialized = False
        self._version: str | None = None
        self._initialize_lock: asyncio.Lock | None = None
        self._closing = False
        self._closed = False
        self._thread_lock = threading.RLock()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None

    @property
    def version(self) -> str | None:
        return self._version

    async def initialize(self) -> str:
        return await self._await_threadsafe(lambda: self._initialize_public(), cancel_cleanup=True)

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
        try:
            loop = self._loop
            if loop is not None and loop.is_running():
                await self._await_threadsafe(lambda: self._close_inner())
        finally:
            self._stop_loop_thread()

    def close(self) -> None:
        try:
            loop = self._loop
            if loop is not None and loop.is_running():
                self._submit(lambda: self._close_inner()).result(timeout=self._settings.sync_close_timeout_seconds)
        finally:
            self._stop_loop_thread()

    async def _initialize_public(self) -> str:
        await self._ensure_initialized()
        if self._version is None:
            raise CodexAppServerProtocolError("Codex app-server did not return a version")
        return self._version

    async def _request_inner(self, method: str, params: Mapping[str, Any] | None = None) -> Any:
        await self._ensure_initialized()
        return await self._require_transport().request(method, params)

    async def _run_turn_inner(
        self,
        *,
        prompt: str,
        model_id: str,
        effort_id: str | None,
        response_mode: Any,
        timeout_seconds: float,
    ) -> CodexTurnResult:
        await self._ensure_initialized()
        return await self._turn_executor.run_turn(
            prompt=prompt,
            model_id=model_id,
            effort_id=effort_id,
            response_mode=response_mode,
            timeout_seconds=timeout_seconds,
        )

    async def _ensure_initialized(self) -> None:
        if self._closing or self._closed:
            raise CodexAppServerTransportError("Codex app-server client is closed")
        if self._initialize_lock is None:
            self._initialize_lock = asyncio.Lock()
        async with self._initialize_lock:
            transport = self._require_transport()
            if transport.is_healthy and self._initialized:
                return
            self._initialized = False
            self._version = None
            await transport.start()
            try:
                response = await transport.request(
                    CodexProtocol.INITIALIZE,
                    {"clientInfo": {"name": self._settings.client_name, "version": self._settings.client_version}},
                )
                user_agent = _non_blank(response.get("userAgent")) if isinstance(response, Mapping) else None
                version = self._extract_version(user_agent)
                if version is None:
                    raise CodexAppServerProtocolError("Codex app-server did not return a version")
                self._version = version
                self._initialized = True
                await transport.write_notification(CodexProtocol.INITIALIZED, {})
            except BaseException:
                await transport.stop(CodexAppServerTransportError("Codex app-server initialize failed"))
                self._initialized = False
                self._version = None
                raise

    async def _close_inner(self) -> None:
        if self._closed:
            return
        self._closing = True
        try:
            self._turn_executor.fail_active_work(CodexAppServerTransportError("Codex app-server stopped"))
            if self._transport is not None:
                await self._transport.stop(CodexAppServerTransportError("Codex app-server stopped"))
        finally:
            self._initialized = False
            self._version = None
            self._initialize_lock = None
            self._closed = True
            self._closing = False

    def _require_transport(self) -> CodexProcessTransport:
        if self._transport is None:
            self._transport = CodexProcessTransport(
                self._settings,
                process_factory=self._process_factory,
                on_notification=self._handle_notification,
                on_server_request=self._turn_executor.handle_server_request,
                on_connection_failed=self._on_connection_failed,
            )
        return self._transport

    def _handle_notification(self, method: str, params: Any) -> None:
        try:
            self._turn_executor.handle_notification(method, params)
        except CodexAppServerProtocolError as exc:
            asyncio.create_task(self._require_transport().invalidate(exc))

    def _on_connection_failed(self, exc: Exception) -> None:
        self._initialized = False
        self._version = None
        self._turn_executor.fail_active_work(exc)

    async def _default_process_factory(self, command: Sequence[str]) -> Any:
        return await asyncio.create_subprocess_exec(
            *command,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

    def _extract_version(self, user_agent: str | None) -> str | None:
        if user_agent is None:
            return None
        match = self._VERSION_PATTERN.match(user_agent)
        return match.group(1) if match else None

    async def _await_threadsafe(self, coro_factory: Callable[[], Awaitable[Any]], *, cancel_cleanup: bool = False) -> Any:
        future = self._submit(coro_factory)
        try:
            return await asyncio.wrap_future(future)
        except asyncio.CancelledError:
            future.cancel()
            deadline = time.monotonic() + self._settings.cancellation_cleanup_timeout_seconds
            while not future.done() and time.monotonic() < deadline:
                await asyncio.sleep(0.01)
            if cancel_cleanup:
                try:
                    await asyncio.wrap_future(self._submit(lambda: self._close_inner()))
                except Exception as cleanup_exc:
                    LOGGER.debug("Codex app-server cancellation cleanup failed", exc_info=cleanup_exc)
            raise

    def _submit(self, coro_factory: Callable[[], Awaitable[Any]]) -> concurrent.futures.Future[Any]:
        loop = self._ensure_loop_thread()
        return asyncio.run_coroutine_threadsafe(cast(Coroutine[Any, Any, Any], coro_factory()), loop)

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
        if loop is not None and loop.is_running():
            loop.call_soon_threadsafe(loop.stop)
        if thread is not None and thread.is_alive():
            thread.join(timeout=self._settings.loop_thread_join_timeout_seconds)
            if thread.is_alive():
                with self._thread_lock:
                    self._loop = loop
                    self._thread = thread
                raise CodexAppServerLifecycleError("Codex app-server event loop thread did not stop")
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
            raw_status = info.get("status")
            try:
                status_code = int(raw_status) if raw_status is not None else None
            except (TypeError, ValueError):
                status_code = None
    return CodexAppServerTransportError(message, status_code=status_code)


def _extract_turn_id(params: Any) -> str | None:
    if not isinstance(params, Mapping):
        return None
    value = _non_blank(params.get("turnId"))
    if value is not None:
        return value
    item = params.get("item")
    if isinstance(item, Mapping):
        return _non_blank(item.get("turnId"))
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
