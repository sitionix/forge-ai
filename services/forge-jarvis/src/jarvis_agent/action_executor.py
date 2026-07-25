from __future__ import annotations

import asyncio
import logging
import os
import signal
from typing import Optional

from jarvis_agent.action_registry import ActionNotAllowedError, ActionRegistry
from jarvis_agent.intent_schema import ExecutionResult, Intent
from jarvis_agent.security import SecurityError, assert_no_user_text_in_command, validate_command


class ActionExecutionError(RuntimeError):
    """Raised when an allowlisted action fails during execution."""


class ActionExecutor:
    def __init__(
        self,
        registry: ActionRegistry,
        logger: Optional[logging.Logger] = None,
        *,
        timeout_seconds: int = 30,
        max_output_bytes: int = 16_384,
        max_concurrency: int = 4,
    ) -> None:
        self._registry = registry
        self._logger = logger or logging.getLogger(__name__)
        self._timeout_seconds = timeout_seconds
        self._max_output_bytes = max_output_bytes
        self._max_concurrency = max(1, max_concurrency)
        self._semaphore: Optional[asyncio.Semaphore] = None
        self._semaphore_loop: Optional[asyncio.AbstractEventLoop] = None
        self._processes: set[asyncio.subprocess.Process] = set()

    def execute(self, intent: Intent, user_text: str) -> ExecutionResult:
        return asyncio.run(self.execute_async(intent, user_text))

    async def execute_async(self, intent: Intent, user_text: str) -> ExecutionResult:
        try:
            target = self._registry.resolve(intent)
            validate_command(target.command)
            assert_no_user_text_in_command(target.command, user_text)
        except (ActionNotAllowedError, SecurityError):
            self._logger.warning("action rejected", extra={"intent": intent.dict()})
            raise

        self._logger.info("action accepted", extra={"intent": intent.dict()})
        semaphore = self._get_semaphore()
        try:
            await asyncio.wait_for(semaphore.acquire(), timeout=self._timeout_seconds)
        except asyncio.TimeoutError:
            raise ActionExecutionError("Action executor concurrency limit reached")
        process: Optional[asyncio.subprocess.Process] = None
        try:
            process = await asyncio.create_subprocess_exec(
                *target.command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                start_new_session=True,
            )
            self._processes.add(process)
            try:
                stdout, stderr, output_limited = await asyncio.wait_for(self._collect_output(process), timeout=self._timeout_seconds)
            except asyncio.TimeoutError as exc:
                await self._terminate_process_tree(process)
                self._logger.exception("action execution timed out")
                raise ActionExecutionError("Allowlisted action timed out") from exc
            return_code = await process.wait()
            if return_code != 0 and not output_limited:
                await self._terminate_process_tree(process)
                self._logger.warning("action execution failed", extra={"intent": intent.dict(), "returnCode": return_code})
                raise ActionExecutionError("Failed to execute allowlisted action")
        except (OSError, asyncio.CancelledError):
            await self._terminate_process_tree(process)
            raise
        finally:
            if process is not None:
                self._processes.discard(process)
            semaphore.release()

        output = self._bounded_output(stdout, stderr)
        message = _success_message(intent.action, intent.target)
        self._logger.info("action execution succeeded", extra={"intent": intent.dict()})
        return ExecutionResult(executed=True, message=message, output=output or None)

    def close(self) -> None:
        return None

    async def aclose(self) -> None:
        await asyncio.gather(*(self._terminate_process_tree(process) for process in list(self._processes)), return_exceptions=True)

    def _get_semaphore(self) -> asyncio.Semaphore:
        loop = asyncio.get_running_loop()
        if self._semaphore is None or self._semaphore_loop is not loop:
            self._semaphore = asyncio.Semaphore(self._max_concurrency)
            self._semaphore_loop = loop
        return self._semaphore

    async def _collect_output(self, process: asyncio.subprocess.Process) -> tuple[str, str, bool]:
        stdout_task = asyncio.create_task(self._read_limited(process.stdout))
        stderr_task = asyncio.create_task(self._read_limited(process.stderr))
        try:
            stdout, stderr = await asyncio.gather(stdout_task, stderr_task)
        except OutputLimitExceeded:
            await self._terminate_process_tree(process)
            stdout_task.cancel()
            stderr_task.cancel()
            await asyncio.gather(stdout_task, stderr_task, return_exceptions=True)
            stdout = stdout_task.result() if stdout_task.done() and not stdout_task.cancelled() and stdout_task.exception() is None else ""
            stderr = stderr_task.result() if stderr_task.done() and not stderr_task.cancelled() and stderr_task.exception() is None else ""
            return stdout + "\n[output truncated]", stderr, True
        except asyncio.CancelledError:
            stdout_task.cancel()
            stderr_task.cancel()
            await asyncio.gather(stdout_task, stderr_task, return_exceptions=True)
            raise
        return stdout, stderr, False

    async def _read_limited(self, stream: Optional[asyncio.StreamReader]) -> str:
        if stream is None:
            return ""
        collected = bytearray()
        while True:
            chunk = await stream.read(min(4096, max(1, self._max_output_bytes - len(collected))))
            if not chunk:
                break
            collected.extend(chunk)
            if len(collected) >= self._max_output_bytes:
                raise OutputLimitExceeded
        return bytes(collected).decode("utf-8", errors="replace")

    def _bounded_output(self, stdout: str, stderr: str) -> str:
        output = "\n".join(part for part in [stdout.strip(), stderr.strip()] if part)
        encoded = output.encode("utf-8", errors="replace")
        if len(encoded) <= self._max_output_bytes:
            return output
        return encoded[: self._max_output_bytes].decode("utf-8", errors="replace") + "\n[output truncated]"

    async def _terminate_process_tree(self, process: Optional[asyncio.subprocess.Process]) -> None:
        if process is None or process.pid is None or process.returncode is not None:
            return
        try:
            os.killpg(process.pid, signal.SIGTERM)
            await asyncio.wait_for(process.wait(), timeout=2)
        except OSError:
            return
        except asyncio.TimeoutError:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except OSError:
                return
            await process.wait()


class OutputLimitExceeded(RuntimeError):
    """Raised internally when a subprocess exceeds the configured output cap."""


def _success_message(action: str, target) -> str:
    if action == "open_application":
        return f"Application launch requested: {target}"
    if action == "open_url":
        return f"URL open requested: {target}"
    return f"Action executed: {action}.{target}"
