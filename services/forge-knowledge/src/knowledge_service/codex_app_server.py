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

LOGGER = logging.getLogger(__name__)


class CodexAppServerError(RuntimeError):
    pass


class CodexAppServerTimeout(CodexAppServerError, TimeoutError):
    pass


class CodexAppServerLifecycleError(CodexAppServerError):
    pass


class CodexAppServerTransportError(CodexAppServerError):
    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        error_code: str | None = None,
        details: Mapping[str, Any] | None = None,
    ) -> None:
        self.status_code = status_code
        self.error_code = error_code
        self.details = dict(details or {})
        super().__init__(message)


class CodexAppServerRemoteError(CodexAppServerError):
    def __init__(self, message: str, *, error_code: int | None = None, status_code: int | None = None) -> None:
        self.error_code = error_code
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
    METHOD_NOT_SUPPORTED = -32601


@dataclass(frozen=True)
class CodexNotificationBufferPolicy:
    max_per_turn: int
    max_turn_ids: int
    max_age_seconds: float

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
    stdio_stream_limit_bytes: int
    request_timeout_seconds: float
    discovery_timeout_cap_seconds: float
    discovery_timeout_allowance_seconds: float
    interrupt_grace_seconds: float
    terminal_after_interrupt_seconds: float
    terminate_grace_seconds: float
    kill_grace_seconds: float
    sync_close_timeout_seconds: float
    loop_thread_join_timeout_seconds: float
    cancellation_cleanup_timeout_seconds: float
    notification_buffer: CodexNotificationBufferPolicy

    def __post_init__(self) -> None:
        if not self.command:
            raise ValueError("Codex command is required")
        if not Path(self.runtime_cwd).is_absolute():
            raise ValueError("Codex runtime_cwd must be absolute")
        if self.stdio_stream_limit_bytes <= 0:
            raise ValueError("stdio_stream_limit_bytes must be positive")
        for field_name in (
            "request_timeout_seconds",
            "discovery_timeout_cap_seconds",
            "discovery_timeout_allowance_seconds",
            "interrupt_grace_seconds",
            "terminal_after_interrupt_seconds",
            "terminate_grace_seconds",
            "kill_grace_seconds",
            "sync_close_timeout_seconds",
            "loop_thread_join_timeout_seconds",
            "cancellation_cleanup_timeout_seconds",
        ):
            if getattr(self, field_name) <= 0:
                raise ValueError(f"{field_name} must be positive")
        minimum_sync_close = (
            self.terminate_grace_seconds
            + self.kill_grace_seconds
            + self.cancellation_cleanup_timeout_seconds
        )
        if self.sync_close_timeout_seconds < minimum_sync_close:
            raise ValueError("sync_close_timeout_seconds must cover process termination and cleanup allowances")


@dataclass(frozen=True)
class CodexTurnResult:
    raw_text: str
    thread_id: str
    turn_id: str
    turn_status: str
    server_version: str
    token_usage: Mapping[str, Any] | None = None
    warnings: tuple[Any, ...] = ()
    model_metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass
class _ActiveTurn:
    thread_id: str
    turn_id: str
    future: asyncio.Future[_CompletedTurn]
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


@dataclass(frozen=True)
class _CompletedTurn:
    raw_text: str
    thread_id: str
    turn_id: str
    turn_status: str
    token_usage: Mapping[str, Any] | None = None
    warnings: tuple[Any, ...] = ()
    model_metadata: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class _ServerRequestHandlingResult:
    response: Mapping[str, Any] | None
    invalidate_after_response: CodexAppServerProtocolError | None = None


@dataclass(frozen=True)
class _SubmittedCoroutine:
    result: concurrent.futures.Future[Any]
    completed: concurrent.futures.Future[None]


def _effective_timeout(timeout: float | None, configured_timeout: float) -> float:
    if timeout is None:
        return float(configured_timeout)
    requested = float(timeout)
    if requested <= 0:
        raise ValueError("timeout must be positive")
    return requested


def _deadline_after(timeout: float) -> float:
    return asyncio.get_running_loop().time() + timeout


def _remaining(deadline: float) -> float:
    return max(deadline - asyncio.get_running_loop().time(), 0.0)


def _remaining_or_timeout(deadline: float, timeout_message: str) -> float:
    remaining = _remaining(deadline)
    if remaining <= 0:
        raise CodexAppServerTimeout(timeout_message)
    return remaining


async def _wait_with_deadline(awaitable: Awaitable[Any], *, deadline: float, timeout_message: str) -> Any:
    try:
        return await asyncio.wait_for(awaitable, timeout=_remaining(deadline))
    except TimeoutError as exc:
        raise CodexAppServerTimeout(timeout_message) from exc


def _normalize_transport_exception(
    exc: BaseException,
    message: str,
    *,
    stream_name: str | None = None,
    configured_limit_bytes: int | None = None,
    pending_methods: Sequence[str] = (),
) -> CodexAppServerTransportError:
    if isinstance(exc, CodexAppServerTransportError):
        return exc
    if isinstance(exc, (asyncio.LimitOverrunError, ValueError)) and "Separator is found, but chunk is longer than limit" in str(exc):
        stream = stream_name or "stdio"
        details: dict[str, Any] = {
            "stream": stream,
            "exceptionClass": exc.__class__.__name__,
        }
        if configured_limit_bytes is not None:
            details["configuredLimitBytes"] = configured_limit_bytes
        methods = [method for method in pending_methods if method]
        if methods:
            details["pendingMethods"] = methods[:5]
            if len(methods) == 1:
                details["method"] = methods[0]
        return CodexAppServerTransportError(
            f"Codex app-server {stream} JSON-RPC frame exceeded configured limit",
            error_code="CODEX_STDIO_FRAME_TOO_LARGE",
            details=details,
        )
    return CodexAppServerTransportError(message)


def _normalize_connection_failure(exc: Exception) -> Exception:
    if isinstance(exc, CodexAppServerError):
        return exc
    return CodexAppServerTransportError("Codex app-server transport failed")


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
        on_notification: Callable[[str, Any], Awaitable[None]],
        on_server_request: Callable[[int, str, Any], Awaitable[_ServerRequestHandlingResult]],
        on_connection_failed: Callable[[Exception], None],
    ) -> None:
        self._settings = settings
        self._process_factory = process_factory
        self._on_notification = on_notification
        self._on_server_request = on_server_request
        self._on_connection_failed = on_connection_failed
        self._process: Any | None = None
        self._cleanup_process: Any | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._pending: dict[int, asyncio.Future[Any]] = {}
        self._pending_methods: dict[int, str] = {}
        self._next_request_id = 1
        self._start_lock: asyncio.Lock | None = None
        self._write_lock: asyncio.Lock | None = None
        self._connection_invalidated = False
        self._connection_failure: Exception | None = None
        self._accepting = True

    async def start(self) -> None:
        self._ensure_locks()
        assert self._start_lock is not None
        async with self._start_lock:
            if self.is_healthy:
                return
            if self._cleanup_process is not None:
                await self.stop(CodexAppServerTransportError("Codex app-server restarting"))
                if self._cleanup_process is not None:
                    raise CodexAppServerLifecycleError("Codex app-server process cleanup is still pending")
            else:
                await self.stop(CodexAppServerTransportError("Codex app-server restarting"))
            self._accepting = True
            try:
                self._process = await self._process_factory(self._settings.command)
            except FileNotFoundError as exc:
                raise CodexAppServerTransportError("Codex app-server executable was not found") from exc
            except (PermissionError, OSError) as exc:
                raise CodexAppServerTransportError("Codex app-server process could not be started") from exc
            self._connection_invalidated = False
            self._connection_failure = None
            self._reader_task = asyncio.create_task(self._read_stdout(), name="codex-app-server-stdout")
            self._stderr_task = asyncio.create_task(self._drain_stream(getattr(self._process, "stderr", None), "stderr"), name="codex-app-server-stderr")

    @property
    def is_healthy(self) -> bool:
        return self._process is not None and self._returncode(self._process) is None and not self._connection_invalidated

    @property
    def has_cleanup_process(self) -> bool:
        return self._cleanup_process is not None

    def raise_if_failed(self) -> None:
        if self._connection_failure is not None:
            raise self._connection_failure

    async def request(self, method: str, params: Mapping[str, Any] | None = None, *, timeout: float | None = None) -> Any:
        if not self._accepting:
            raise CodexAppServerTransportError("Codex app-server client is closing")
        deadline = _deadline_after(_effective_timeout(timeout, self._settings.request_timeout_seconds))
        await _wait_with_deadline(
            self.start(),
            deadline=deadline,
            timeout_message=f"Codex app-server request timed out: {method}",
        )
        request_id = self._allocate_request_id()
        payload: dict[str, Any] = {"id": request_id, "method": method}
        if params is not None:
            payload["params"] = dict(params)
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        self._pending_methods[request_id] = method
        submitted = False

        def mark_submitted() -> None:
            nonlocal submitted
            submitted = True

        try:
            await self._write_json(payload, deadline=deadline, on_submitted=mark_submitted)
            result = await _wait_with_deadline(
                future,
                deadline=deadline,
                timeout_message=f"Codex app-server request timed out: {method}",
            )
            if self._connection_failure is not None:
                raise self._connection_failure
            return result
        except CodexAppServerTimeout as exc:
            await self.invalidate(exc)
            raise
        except CodexAppServerTransportError as exc:
            failure = self._connection_failure if self._connection_invalidated and self._connection_failure is not None else exc
            await self.invalidate(failure)
            raise failure
        except asyncio.CancelledError:
            if submitted:
                try:
                    await self.invalidate(CodexAppServerTransportError("Codex app-server request was cancelled after submission"))
                except (Exception, asyncio.CancelledError) as cleanup_exc:
                    LOGGER.debug("Codex app-server cancellation cleanup failed", exc_info=cleanup_exc)
            raise
        finally:
            self._pending.pop(request_id, None)
            self._pending_methods.pop(request_id, None)

    async def write_notification(self, method: str, params: Mapping[str, Any] | None = None, *, timeout: float | None = None) -> None:
        payload: dict[str, Any] = {"method": method}
        if params is not None:
            payload["params"] = dict(params)
        deadline = _deadline_after(_effective_timeout(timeout, self._settings.request_timeout_seconds))
        submitted = False

        def mark_submitted() -> None:
            nonlocal submitted
            submitted = True

        try:
            await self._write_json(payload, deadline=deadline, on_submitted=mark_submitted)
        except CodexAppServerTransportError as exc:
            await self.invalidate(exc)
            raise
        except asyncio.CancelledError:
            if submitted:
                try:
                    await self.invalidate(CodexAppServerTransportError("Codex app-server notification was cancelled after submission"))
                except (Exception, asyncio.CancelledError) as cleanup_exc:
                    LOGGER.debug("Codex app-server notification cancellation cleanup failed", exc_info=cleanup_exc)
            raise

    async def stop(self, exc: Exception | None = None) -> None:
        self._accepting = False
        await self._cancel_and_drain_reader_tasks()
        self._fail_pending(exc or CodexAppServerTransportError("Codex app-server stopped"))
        process = self._process or self._cleanup_process
        self._process = None
        if process is not None:
            self._cleanup_process = process
        self._connection_invalidated = False
        self._connection_failure = None
        if process is not None:
            cleanup_complete = False
            try:
                await self._terminate_process(process)
                cleanup_complete = True
            finally:
                if cleanup_complete and self._cleanup_process is process:
                    self._cleanup_process = None

    async def invalidate(self, exc: Exception) -> None:
        exc = _normalize_connection_failure(exc)
        if self._connection_invalidated and self._connection_failure is not None:
            return
        self._connection_invalidated = True
        self._connection_failure = exc
        self._fail_pending(exc)
        self._on_connection_failed(exc)
        process = self._process
        self._process = None
        if process is not None:
            self._cleanup_process = process
        termination_error: BaseException | None = None
        try:
            if process is not None and self._returncode(process) is None:
                await self._terminate_process(process)
        except (CodexAppServerError, TimeoutError, OSError, asyncio.CancelledError) as exc_info:
            termination_error = exc_info
        finally:
            await self._cancel_and_drain_reader_tasks()
            if termination_error is None and process is not None and self._cleanup_process is process:
                self._cleanup_process = None
        if termination_error is not None:
            raise termination_error

    async def force_stop(self, exc: Exception) -> None:
        self._accepting = False
        self._fail_pending(exc)
        process = self._process or self._cleanup_process
        self._process = None
        if process is not None:
            self._cleanup_process = process
        self._connection_invalidated = False
        self._connection_failure = None
        await self._cancel_and_drain_reader_tasks()
        if process is not None and self._returncode(process) is None:
            terminate = getattr(process, "terminate", None)
            if callable(terminate):
                terminate()
            kill = getattr(process, "kill", None)
            if callable(kill):
                kill()
            wait = getattr(process, "wait", None)
            if callable(wait):
                try:
                    await asyncio.wait_for(wait(), timeout=self._settings.kill_grace_seconds)
                except TimeoutError as timeout:
                    raise CodexAppServerLifecycleError("Codex app-server process did not exit during forced cleanup") from timeout
        if process is not None and self._returncode(process) is not None and self._cleanup_process is process:
            self._cleanup_process = None

    async def _cancel_and_drain_reader_tasks(self) -> None:
        current = asyncio.current_task()
        tasks = tuple(task for task in (self._reader_task, self._stderr_task) if task is not None and task is not current)
        for task in tasks:
            if not task.done():
                task.cancel()
        for task in tasks:
            try:
                await task
            except asyncio.CancelledError:
                pass
            except Exception as cleanup_exc:
                LOGGER.debug("Codex app-server reader task failed during shutdown", exc_info=cleanup_exc)
        if self._reader_task is not current:
            self._reader_task = None
        if self._stderr_task is not current:
            self._stderr_task = None

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

    async def _write_json(self, payload: Mapping[str, Any], *, deadline: float, on_submitted: Callable[[], None] | None = None) -> None:
        process = self._process
        if process is None or self._returncode(process) is not None:
            raise CodexAppServerTransportError("Codex app-server process is not running")
        stdin = getattr(process, "stdin", None)
        if stdin is None:
            raise CodexAppServerTransportError("Codex app-server stdin is unavailable")
        encoded = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        self._ensure_locks()
        assert self._write_lock is not None
        acquired = False
        try:
            await asyncio.wait_for(self._write_lock.acquire(), timeout=_remaining(deadline))
            acquired = True
            stdin.write(encoded)
            if on_submitted is not None:
                on_submitted()
            drain = getattr(stdin, "drain", None)
            if callable(drain):
                await asyncio.wait_for(drain(), timeout=_remaining(deadline))
        except TimeoutError as exc:
            raise CodexAppServerTransportError("Codex app-server write timed out") from exc
        except (BrokenPipeError, ConnectionResetError, OSError) as exc:
            raise _normalize_transport_exception(exc, "Codex app-server write failed") from exc
        finally:
            if acquired:
                self._write_lock.release()

    async def _read_stdout(self) -> None:
        stream = getattr(self._process, "stdout", None)
        try:
            while stream is not None:
                line = await stream.readline()
                if not line:
                    break
                await self._handle_line(line)
                if self._connection_invalidated:
                    break
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001 - reader task is the transport failure boundary.
            if not self._accepting:
                return
            await self.invalidate(
                _normalize_transport_exception(
                    exc,
                    "Codex app-server stdout transport failed",
                    stream_name="stdout",
                    configured_limit_bytes=self._settings.stdio_stream_limit_bytes,
                    pending_methods=tuple(self._pending_methods.values()),
                )
            )
        finally:
            if self._accepting and not self._connection_invalidated and self._process is not None:
                try:
                    asyncio.get_running_loop()
                except RuntimeError:
                    exit_error = CodexAppServerTransportError("Codex app-server exited before response")
                    self._connection_invalidated = True
                    self._connection_failure = exit_error
                    self._fail_pending(exit_error)
                    self._on_connection_failed(exit_error)
                    self._process = None
                else:
                    await self.invalidate(CodexAppServerTransportError("Codex app-server exited before response"))

    async def _drain_stream(self, stream: Any | None, stream_name: str) -> None:
        if stream is None:
            return
        try:
            while await stream.readline():
                pass
        except asyncio.CancelledError:
            raise
        except Exception as drain_exc:  # noqa: BLE001 - stderr reader task is a transport failure boundary.
            if self._accepting:
                await self.invalidate(
                    _normalize_transport_exception(
                        drain_exc,
                        f"Codex app-server {stream_name} transport failed",
                        stream_name=stream_name,
                        configured_limit_bytes=self._settings.stdio_stream_limit_bytes,
                        pending_methods=tuple(self._pending_methods.values()),
                    )
                )
            return

    async def _handle_line(self, line: bytes | str) -> None:
        text = line.decode("utf-8", errors="replace") if isinstance(line, bytes) else str(line)
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            await self.invalidate(CodexAppServerProtocolError("Codex app-server emitted malformed JSON-RPC"))
            return
        if not isinstance(payload, Mapping):
            await self.invalidate(CodexAppServerProtocolError("Codex app-server emitted malformed JSON-RPC envelope"))
            return
        if "method" in payload and "id" in payload:
            await self._handle_server_request(payload)
            return
        if "method" in payload:
            await self._handle_notification(payload)
            return
        if "id" in payload:
            await self._handle_response(payload)
            return
        await self.invalidate(CodexAppServerProtocolError("Codex app-server emitted unclassifiable JSON-RPC envelope"))

    async def _handle_response(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("id")
        if type(request_id) is not int:
            await self.invalidate(CodexAppServerProtocolError("Codex app-server response id was invalid"))
            return
        future = self._pending.get(request_id)
        if future is None or future.done():
            await self.invalidate(CodexAppServerProtocolError("Codex app-server response id did not match a pending request"))
            return
        has_error = "error" in payload
        has_result = "result" in payload
        if has_error and has_result:
            exc = CodexAppServerProtocolError("Codex app-server response contained both result and error")
            future.set_exception(exc)
            await self.invalidate(exc)
            return
        if has_error:
            self._pending.pop(request_id, None)
            try:
                remote_exc = _error_from_envelope(payload.get("error"), require_jsonrpc_error=True)
            except CodexAppServerProtocolError as protocol_exc:
                future.set_exception(protocol_exc)
                await self.invalidate(protocol_exc)
                return
            future.set_exception(remote_exc)
            return
        if has_result:
            self._pending.pop(request_id, None)
            future.set_result(payload.get("result"))
            return
        exc = CodexAppServerProtocolError("Codex app-server response omitted result and error")
        future.set_exception(exc)
        await self.invalidate(exc)

    async def _handle_notification(self, payload: Mapping[str, Any]) -> None:
        method = payload.get("method")
        params = payload.get("params")
        if not isinstance(method, str):
            await self.invalidate(CodexAppServerProtocolError("Codex app-server notification method was invalid"))
            return
        try:
            await self._on_notification(method, params)
        except CodexAppServerProtocolError as exc:
            await self.invalidate(exc)

    async def _handle_server_request(self, payload: Mapping[str, Any]) -> None:
        request_id = payload.get("id")
        method = payload.get("method")
        params = payload.get("params")
        if type(request_id) is not int:
            await self.invalidate(CodexAppServerProtocolError("Codex app-server server request id was invalid"))
            return
        if not isinstance(method, str):
            await self.invalidate(CodexAppServerProtocolError("Codex app-server server request method was invalid"))
            return
        try:
            result = await self._on_server_request(request_id, method, params)
        except CodexAppServerProtocolError as exc:
            await self.invalidate(exc)
            return
        if result.response is not None:
            try:
                await self._write_json(result.response, deadline=_deadline_after(self._settings.request_timeout_seconds))
            except CodexAppServerTransportError as exc:
                await self.invalidate(exc)
                return
        if result.invalidate_after_response is not None:
            await self.invalidate(result.invalidate_after_response)

    def _allocate_request_id(self) -> int:
        request_id = self._next_request_id
        self._next_request_id += 1
        return request_id

    def _fail_pending(self, exc: Exception) -> None:
        for future in self._pending.values():
            if not future.done():
                future.set_exception(exc)
        self._pending.clear()
        self._pending_methods.clear()

    def _ensure_locks(self) -> None:
        if self._start_lock is None:
            self._start_lock = asyncio.Lock()
        if self._write_lock is None:
            self._write_lock = asyncio.Lock()

    def _returncode(self, process: Any) -> int | None:
        return getattr(process, "returncode", None)


class CodexTurnExecutor:
    _FAIL_CLOSED_SERVER_REQUEST = "__codex_fail_closed_server_request"

    def __init__(self, settings: CodexRuntimeSettings, policy: CodexGenerationPolicy, transport_getter: Callable[[], CodexProcessTransport]) -> None:
        self._settings = settings
        self._policy = policy
        self._transport_getter = transport_getter
        self._active_turns: dict[str, _ActiveTurn] = {}
        self._buffered_turn_notifications: dict[str, list[_BufferedNotification]] = {}
        self._owned_tasks: set[asyncio.Task[None]] = set()
        self._pre_registration_turns = 0

    async def run_turn(
        self,
        *,
        prompt: str,
        model_id: str,
        effort_id: str | None,
        response_mode: Any,
        timeout_seconds: float,
        server_version: str,
    ) -> CodexTurnResult:
        active: _ActiveTurn | None = None
        transport = self._transport_getter()
        deadline = float(timeout_seconds)
        if deadline <= 0:
            raise ValueError("timeout_seconds must be positive")
        pre_registration = True
        self._pre_registration_turns += 1
        try:
            async with asyncio.timeout_at(deadline):  # type: ignore[attr-defined]
                thread_result = await transport.request(
                    CodexProtocol.THREAD_START,
                    self._thread_start_params(),
                    timeout=_remaining_or_timeout(deadline, "Codex app-server turn timed out"),
                )
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
                turn_result = await transport.request(
                    CodexProtocol.TURN_START,
                    turn_params,
                    timeout=_remaining_or_timeout(deadline, "Codex app-server turn timed out"),
                )
                turn_id = self._extract_turn_id(turn_result)
                transport.raise_if_failed()
                active = _ActiveTurn(
                    thread_id=thread_id,
                    turn_id=turn_id,
                    future=asyncio.get_running_loop().create_future(),
                    unwrap_json_payload=json_mode,
                )
                self._active_turns[turn_id] = active
                self._pre_registration_turns -= 1
                pre_registration = False
                self._replay_buffered_turn_notifications(active)
                transport.raise_if_failed()
                result = await asyncio.shield(active.future)
                return CodexTurnResult(
                    raw_text=result.raw_text,
                    thread_id=result.thread_id,
                    turn_id=result.turn_id,
                    turn_status=result.turn_status,
                    server_version=server_version,
                    token_usage=result.token_usage,
                    warnings=result.warnings,
                    model_metadata=result.model_metadata,
                )
        except CodexAppServerProtocolError as exc:
            if active is not None:
                self._active_turns.pop(active.turn_id, None)
                if not active.future.done():
                    active.future.set_exception(exc)
            self._buffered_turn_notifications.clear()
            await transport.invalidate(exc)
            raise
        except TimeoutError as exc:
            if active is not None:
                await self._interrupt_turn(active)
                await self._await_terminal_after_interrupt(active)
                self._active_turns.pop(active.turn_id, None)
            raise CodexAppServerTimeout("Codex app-server turn timed out") from exc
        except asyncio.CancelledError:
            if active is not None:
                self._active_turns.pop(active.turn_id, None)
                try:
                    await transport.invalidate(CodexAppServerTransportError("Codex app-server turn was cancelled after submission"))
                except (Exception, asyncio.CancelledError) as cleanup_exc:
                    LOGGER.debug("Codex app-server turn cancellation cleanup failed", exc_info=cleanup_exc)
            raise
        finally:
            if pre_registration:
                self._pre_registration_turns -= 1
            if active is not None and active.future.done():
                self._active_turns.pop(active.turn_id, None)

    async def handle_notification(self, method: str, params: Any) -> None:
        self._prune_expired_buffered_notifications()
        if method == CodexProtocol.ITEM_STARTED:
            self._handle_item_event(CodexProtocol.ITEM_STARTED, params)
        elif method == CodexProtocol.ITEM_COMPLETED:
            self._handle_item_event(CodexProtocol.ITEM_COMPLETED, params)
        elif method == CodexProtocol.TURN_COMPLETED:
            self._handle_turn_completed(params)

    async def handle_server_request(self, request_id: int, method: str, params: Any) -> _ServerRequestHandlingResult:
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
                    "code": CodexProtocol.METHOD_NOT_SUPPORTED,
                    "message": "Codex server requests are not supported by Forge Knowledge generation",
                },
            }
        turn_id = _resolve_turn_id(params, "server request", required=False)
        active = self._active_turns.get(turn_id or "") if turn_id is not None else None
        invalidation: CodexAppServerProtocolError | None = None
        if turn_id is None and self._pre_registration_turns > 0:
            invalidation = CodexAppServerProtocolError("Codex app-server sent unscoped server request before turn registration")
        if active is not None and not active.future.done():
            self._schedule_fail_active_turn(active, CodexAppServerProtocolError("Codex app-server requested unsupported side effect"))
        elif turn_id is not None:
            try:
                self._buffer_turn_notification(
                    turn_id,
                    self._FAIL_CLOSED_SERVER_REQUEST,
                    {"reason": "Codex app-server requested unsupported side effect"},
                )
            except CodexAppServerProtocolError as exc:
                invalidation = exc
        elif turn_id is None and invalidation is None:
            for active_turn in tuple(self._active_turns.values()):
                if not active_turn.future.done():
                    self._schedule_fail_active_turn(active_turn, CodexAppServerProtocolError("Codex app-server requested unsupported side effect"))
        return _ServerRequestHandlingResult(response=response, invalidate_after_response=invalidation)

    def fail_active_work(self, exc: Exception) -> None:
        try:
            current = asyncio.current_task()
        except RuntimeError:
            current = None
        for task in tuple(self._owned_tasks):
            if task is not current and not task.done():
                task.cancel()
        for active in self._active_turns.values():
            if not active.future.done():
                active.future.set_exception(exc)
        self._active_turns.clear()
        self._buffered_turn_notifications.clear()

    async def drain_owned_tasks(self) -> None:
        tasks = tuple(self._owned_tasks)
        if not tasks:
            return
        await asyncio.gather(*tasks, return_exceptions=True)

    def _thread_start_params(self) -> dict[str, Any]:
        try:
            self._settings.runtime_cwd.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise CodexAppServerTransportError("Codex app-server runtime directory could not be prepared") from exc
        return {
            "ephemeral": True,
            "approvalPolicy": CodexProtocol.APPROVAL_POLICY,
            "sandbox": CodexProtocol.SANDBOX,
            "cwd": str(self._settings.runtime_cwd),
        }

    def _handle_item_event(self, method: str, params: Any) -> None:
        if not isinstance(params, Mapping):
            raise CodexAppServerProtocolError(f"Codex {method} params must be an object")
        turn_id = _resolve_turn_id(params, method, required=False)
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
        turn_id = _resolve_turn_id(params, CodexProtocol.TURN_COMPLETED, required=True)
        if turn_id is None:
            raise CodexAppServerProtocolError("Codex turn/completed omitted turnId")
        status = _resolve_turn_completed_status(params)
        turn = params.get("turn")
        completion_params = {**dict(turn), **dict(params)} if isinstance(turn, Mapping) else dict(params)
        completion_params["turnId"] = turn_id
        completion_params["status"] = status
        active = self._active_turns.get(turn_id)
        if active is None or active.future.done():
            self._buffer_turn_notification(turn_id, CodexProtocol.TURN_COMPLETED, completion_params)
            return
        self._complete_active_turn(active, completion_params)

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
                _CompletedTurn(
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
        task = asyncio.create_task(self._fail_active_turn(active, exc), name=f"codex-app-server-fail-turn-{active.turn_id}")
        self._owned_tasks.add(task)
        task.add_done_callback(self._owned_tasks.discard)

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
        if expired:
            raise CodexAppServerProtocolError("Codex buffered notification expired before turn registration")

    def _replay_buffered_turn_notifications(self, active: _ActiveTurn) -> None:
        self._prune_expired_buffered_notifications()
        buffered = self._buffered_turn_notifications.pop(active.turn_id, [])
        for event in buffered:
            if event.method == self._FAIL_CLOSED_SERVER_REQUEST:
                reason = event.params.get("reason") if isinstance(event.params, Mapping) else None
                self._schedule_fail_active_turn(active, CodexAppServerProtocolError(str(reason or "Codex app-server requested unsupported side effect")))
            elif event.method in {CodexProtocol.ITEM_STARTED, CodexProtocol.ITEM_COMPLETED}:
                self._handle_item_event(event.method, event.params)
            elif event.method == CodexProtocol.TURN_COMPLETED:
                self._handle_turn_completed(event.params)

    def _extract_thread_id(self, payload: Any) -> str:
        if not isinstance(payload, Mapping):
            raise CodexAppServerProtocolError(f"Codex {CodexProtocol.THREAD_START} result must be an object")
        return _resolve_thread_id(payload, CodexProtocol.THREAD_START)

    def _extract_turn_id(self, payload: Any) -> str:
        if not isinstance(payload, Mapping):
            raise CodexAppServerProtocolError(f"Codex {CodexProtocol.TURN_START} result must be an object")
        return _resolve_turn_id(payload, CodexProtocol.TURN_START, required=True) or ""


class CodexAppServerClient:
    _VERSION_PATTERN = re.compile(r"^[^/]+/([^ ]+)")

    def __init__(
        self,
        *,
        settings: CodexRuntimeSettings,
        process_factory: Callable[[Sequence[str]], Awaitable[Any]] | None = None,
    ) -> None:
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
        self._close_task: asyncio.Task[None] | None = None

    @property
    def version(self) -> str | None:
        return self._version

    async def initialize(self) -> str:
        self._raise_if_closed()
        return await self._await_threadsafe(lambda: self._initialize_public(), cancel_cleanup=True)

    async def request(self, method: str, params: Mapping[str, Any] | None = None) -> Any:
        self._raise_if_closed()
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
        self._raise_if_closed()
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
        self._raise_if_closed()
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
        if self._closed:
            return
        cleanup_finished = False
        loop = self._loop
        if loop is not None and loop.is_running():
            await self._await_threadsafe(lambda: self._close_inner())
            cleanup_finished = True
        else:
            if self._transport is not None and self._transport.has_cleanup_process:
                raise CodexAppServerLifecycleError("Codex app-server process cleanup is still pending")
            self._closed = True
            cleanup_finished = True
        if cleanup_finished:
            self._stop_loop_thread()

    def close(self) -> None:
        if self._closed:
            return
        cleanup_future: concurrent.futures.Future[Any] | None = None
        cleanup_finished = False
        try:
            loop = self._loop
            if loop is not None and loop.is_running():
                cleanup_future = self._submit(lambda: self._close_inner(), allow_closing=True)
                try:
                    cleanup_future.result(timeout=self._settings.sync_close_timeout_seconds)
                    cleanup_finished = True
                except TimeoutError:
                    forced = asyncio.run_coroutine_threadsafe(self._force_close_after_timeout(), loop)
                    try:
                        forced.result(timeout=self._settings.cancellation_cleanup_timeout_seconds)
                        try:
                            cleanup_future.result(timeout=0)
                        except concurrent.futures.CancelledError:
                            pass
                        except Exception as cleanup_exc:
                            LOGGER.debug("Codex app-server timed-out close task finished with error", exc_info=cleanup_exc)
                    except (TimeoutError, concurrent.futures.CancelledError) as exc:
                        raise CodexAppServerLifecycleError("Codex app-server close cleanup could not be completed") from exc
                    except CodexAppServerLifecycleError:
                        raise
                    except Exception as exc:
                        raise CodexAppServerLifecycleError("Codex app-server close cleanup could not be completed") from exc
                    cleanup_finished = True
                    raise CodexAppServerLifecycleError("Codex app-server close cleanup timed out")
            else:
                self._closed = True
                cleanup_finished = True
        finally:
            if cleanup_finished or self._closed:
                self._stop_loop_thread()

    async def _initialize_public(self) -> str:
        await self._ensure_initialized(deadline=_deadline_after(self._settings.request_timeout_seconds))
        if self._version is None:
            raise CodexAppServerProtocolError("Codex app-server did not return a version")
        return self._version

    async def _request_inner(self, method: str, params: Mapping[str, Any] | None = None) -> Any:
        deadline = _deadline_after(self._settings.request_timeout_seconds)
        await self._ensure_initialized(deadline=deadline)
        return await self._require_transport().request(
            method,
            params,
            timeout=_remaining_or_timeout(deadline, f"Codex app-server request timed out: {method}"),
        )

    async def _run_turn_inner(
        self,
        *,
        prompt: str,
        model_id: str,
        effort_id: str | None,
        response_mode: Any,
        timeout_seconds: float,
    ) -> CodexTurnResult:
        timeout = float(timeout_seconds)
        if timeout <= 0:
            raise ValueError("timeout_seconds must be positive")
        deadline = _deadline_after(timeout)
        await self._ensure_initialized(deadline=deadline)
        if self._version is None:
            raise CodexAppServerProtocolError("Codex app-server did not return a version")
        return await self._turn_executor.run_turn(
            prompt=prompt,
            model_id=model_id,
            effort_id=effort_id,
            response_mode=response_mode,
            timeout_seconds=deadline,
            server_version=self._version,
        )

    async def _ensure_initialized(self, *, deadline: float) -> None:
        if self._closing or self._closed:
            raise CodexAppServerTransportError("Codex app-server client is closed")
        if self._initialize_lock is None:
            self._initialize_lock = asyncio.Lock()
        acquired = False
        try:
            await asyncio.wait_for(self._initialize_lock.acquire(), timeout=_remaining(deadline))
            acquired = True
            transport = self._require_transport()
            if transport.is_healthy and self._initialized:
                return
            self._initialized = False
            self._version = None
            await _wait_with_deadline(
                transport.start(),
                deadline=deadline,
                timeout_message="Codex app-server initialize timed out",
            )
            try:
                response = await transport.request(
                    CodexProtocol.INITIALIZE,
                    {"clientInfo": {"name": self._settings.client_name, "version": self._settings.client_version}},
                    timeout=_remaining_or_timeout(deadline, "Codex app-server initialize timed out"),
                )
                user_agent = _non_blank(response.get("userAgent")) if isinstance(response, Mapping) else None
                version = self._extract_version(user_agent)
                if version is None:
                    raise CodexAppServerProtocolError("Codex app-server did not return a version")
                self._version = version
                self._initialized = True
                await transport.write_notification(
                    CodexProtocol.INITIALIZED,
                    {},
                    timeout=_remaining_or_timeout(deadline, "Codex app-server initialize timed out"),
                )
            except CodexAppServerRemoteError:
                self._initialized = False
                self._version = None
                raise
            except asyncio.CancelledError:
                try:
                    await transport.stop(CodexAppServerTransportError("Codex app-server initialize cancelled"))
                except (Exception, asyncio.CancelledError) as cleanup_exc:
                    LOGGER.debug("Codex app-server initialize cancellation cleanup failed", exc_info=cleanup_exc)
                self._initialized = False
                self._version = None
                raise
            except BaseException:
                await transport.stop(CodexAppServerTransportError("Codex app-server initialize failed"))
                self._initialized = False
                self._version = None
                raise
        except TimeoutError as exc:
            raise CodexAppServerTimeout("Codex app-server initialize timed out") from exc
        finally:
            if acquired:
                self._initialize_lock.release()

    async def _close_inner(self) -> None:
        if self._closed:
            return
        self._closing = True
        self._close_task = asyncio.current_task()
        try:
            self._turn_executor.fail_active_work(CodexAppServerTransportError("Codex app-server stopped"))
            if self._transport is not None:
                await self._transport.stop(CodexAppServerTransportError("Codex app-server stopped"))
            await self._turn_executor.drain_owned_tasks()
            self._initialized = False
            self._version = None
            self._initialize_lock = None
            self._closed = True
        finally:
            if self._close_task is asyncio.current_task():
                self._close_task = None
            self._closing = False

    async def _force_close_after_timeout(self) -> None:
        close_task = self._close_task
        if close_task is not None and not close_task.done():
            close_task.cancel()
        self._turn_executor.fail_active_work(CodexAppServerTransportError("Codex app-server stopped"))
        if self._transport is not None:
            await self._transport.force_stop(CodexAppServerTransportError("Codex app-server stopped"))
        await self._turn_executor.drain_owned_tasks()
        if close_task is not None and close_task is not asyncio.current_task() and not close_task.done():
            try:
                await asyncio.wait_for(close_task, timeout=self._settings.cancellation_cleanup_timeout_seconds)
            except asyncio.CancelledError:
                pass
            except TimeoutError as exc:
                raise CodexAppServerLifecycleError("Codex app-server close cleanup task did not finish") from exc
            except Exception as cleanup_exc:
                LOGGER.debug("Codex app-server timed-out close task finished with error", exc_info=cleanup_exc)
        self._initialized = False
        self._version = None
        self._initialize_lock = None
        self._closed = True
        self._closing = False
        self._close_task = None

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

    async def _handle_notification(self, method: str, params: Any) -> None:
        await self._turn_executor.handle_notification(method, params)

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
            limit=self._settings.stdio_stream_limit_bytes,
        )

    def _extract_version(self, user_agent: str | None) -> str | None:
        if user_agent is None:
            return None
        match = self._VERSION_PATTERN.match(user_agent)
        return match.group(1) if match else None

    async def _await_threadsafe(self, coro_factory: Callable[[], Awaitable[Any]], *, cancel_cleanup: bool = False) -> Any:
        submitted = self._submit_tracked(coro_factory)
        try:
            return await asyncio.wrap_future(submitted.result)
        except asyncio.CancelledError:
            submitted.result.cancel()
            try:
                await asyncio.wait_for(
                    asyncio.wrap_future(submitted.completed),
                    timeout=self._settings.cancellation_cleanup_timeout_seconds,
                )
            except TimeoutError:
                pass
            if cancel_cleanup:
                try:
                    await asyncio.wrap_future(self._submit(lambda: self._close_inner()))
                except Exception as cleanup_exc:
                    LOGGER.debug("Codex app-server cancellation cleanup failed", exc_info=cleanup_exc)
            raise

    def _submit_tracked(self, coro_factory: Callable[[], Awaitable[Any]]) -> _SubmittedCoroutine:
        self._raise_if_closed()
        loop = self._ensure_loop_thread()
        result_future: concurrent.futures.Future[Any] = concurrent.futures.Future()
        completed_future: concurrent.futures.Future[None] = concurrent.futures.Future()

        def start() -> None:
            try:
                task = loop.create_task(cast(Coroutine[Any, Any, Any], coro_factory()))
            except Exception as exc:  # noqa: BLE001 - loop-thread submission is the async/sync boundary.
                if not result_future.cancelled():
                    result_future.set_exception(exc)
                completed_future.set_result(None)
                return

            def cancel_task(_future: concurrent.futures.Future[Any]) -> None:
                if _future.cancelled() and not task.done():
                    task.cancel()

            result_future.add_done_callback(lambda _future: loop.call_soon_threadsafe(cancel_task, _future))
            if result_future.cancelled():
                task.cancel()

            def finish(done: asyncio.Task[Any]) -> None:
                try:
                    if not result_future.cancelled():
                        result_future.set_result(done.result())
                except asyncio.CancelledError:
                    result_future.cancel()
                except Exception as exc:  # noqa: BLE001 - loop-thread task failures are propagated through the public future.
                    if not result_future.cancelled():
                        result_future.set_exception(exc)
                finally:
                    if not completed_future.done():
                        completed_future.set_result(None)

            task.add_done_callback(finish)

        loop.call_soon_threadsafe(start)
        return _SubmittedCoroutine(result=result_future, completed=completed_future)

    def _submit(self, coro_factory: Callable[[], Awaitable[Any]], *, allow_closing: bool = False) -> concurrent.futures.Future[Any]:
        if allow_closing:
            if self._closed:
                raise CodexAppServerTransportError("Codex app-server client is closed")
        else:
            self._raise_if_closed()
        loop = self._ensure_loop_thread()
        return asyncio.run_coroutine_threadsafe(cast(Coroutine[Any, Any, Any], coro_factory()), loop)

    def _raise_if_closed(self) -> None:
        if self._closed or self._closing:
            raise CodexAppServerTransportError("Codex app-server client is closed")

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


def _error_from_envelope(error: Any, *, require_jsonrpc_error: bool = False) -> CodexAppServerError:
    error_code: int | None = None
    status_code: int | None = None
    message = "Codex app-server returned an error"
    if isinstance(error, Mapping):
        raw_code = error.get("code")
        if type(raw_code) is int:
            error_code = raw_code
        elif require_jsonrpc_error:
            raise CodexAppServerProtocolError("Codex app-server error response code was invalid")
        if require_jsonrpc_error and not isinstance(error.get("message"), str):
            raise CodexAppServerProtocolError("Codex app-server error response message was invalid")
        info = error.get("codexErrorInfo")
        if not isinstance(info, Mapping):
            info = error.get("data") if isinstance(error.get("data"), Mapping) else None
        if isinstance(info, Mapping):
            raw_status = info.get("status")
            try:
                status_code = int(raw_status) if raw_status is not None else None
            except (TypeError, ValueError):
                status_code = None
    elif require_jsonrpc_error:
        raise CodexAppServerProtocolError("Codex app-server error response was invalid")
    return CodexAppServerRemoteError(message, error_code=error_code, status_code=status_code)


def _resolve_thread_id(payload: Mapping[str, Any], context: str) -> str:
    value = _resolve_identity(
        context,
        (
            ("threadId", payload, "threadId"),
            ("thread.id", payload.get("thread"), "id"),
            ("thread.sessionId", payload.get("thread"), "sessionId"),
        ),
        required_message=f"Codex {context} result omitted threadId",
    )
    assert value is not None
    return value


def _resolve_turn_id(params: Any, context: str, *, required: bool) -> str | None:
    if not isinstance(params, Mapping):
        if required:
            raise CodexAppServerProtocolError(f"Codex {context} omitted turnId")
        return None
    item = params.get("item")
    return _resolve_identity(
        context,
        (
            ("turnId", params, "turnId"),
            ("turn.id", params.get("turn"), "id"),
            ("item.turnId", item, "turnId"),
            ("item.turn.id", item.get("turn") if isinstance(item, Mapping) else None, "id"),
        ),
        required_message=f"Codex {context} omitted turnId" if required else None,
    )


def _resolve_turn_completed_status(params: Mapping[str, Any]) -> str:
    value = _resolve_identity(
        CodexProtocol.TURN_COMPLETED,
        (
            ("status", params, "status"),
            ("turn.status", params.get("turn"), "status"),
        ),
        required_message="Codex turn/completed omitted status",
    )
    assert value is not None
    return value


def _resolve_identity(
    context: str,
    locations: Sequence[tuple[str, Any, str]],
    *,
    required_message: str | None,
) -> str | None:
    values: list[tuple[str, str]] = []
    for label, container, key in locations:
        if not isinstance(container, Mapping) or key not in container:
            continue
        raw = container.get(key)
        value = _non_blank(raw)
        if value is None:
            raise CodexAppServerProtocolError(f"Codex {context} {label} was invalid")
        values.append((label, value))
    if not values:
        if required_message is not None:
            raise CodexAppServerProtocolError(required_message)
        return None
    distinct = {value for _, value in values}
    if len(distinct) > 1:
        labels = ", ".join(label for label, _ in values)
        raise CodexAppServerProtocolError(f"Codex {context} contained conflicting identities: {labels}")
    return values[0][1]


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
