from __future__ import annotations

import logging
import subprocess
from typing import Optional

from jarvis_agent.action_registry import ActionNotAllowedError, ActionRegistry
from jarvis_agent.intent_schema import ExecutionResult, Intent
from jarvis_agent.security import SecurityError, assert_no_user_text_in_command, validate_command


class ActionExecutionError(RuntimeError):
    """Raised when an allowlisted action fails during execution."""


class ActionExecutor:
    def __init__(self, registry: ActionRegistry, logger: Optional[logging.Logger] = None) -> None:
        self._registry = registry
        self._logger = logger or logging.getLogger(__name__)

    def execute(self, intent: Intent, user_text: str) -> ExecutionResult:
        try:
            target = self._registry.resolve(intent)
            validate_command(target.command)
            assert_no_user_text_in_command(target.command, user_text)
        except (ActionNotAllowedError, SecurityError):
            self._logger.warning("action rejected", extra={"intent": intent.dict()})
            raise

        self._logger.info("action accepted", extra={"intent": intent.dict()})
        try:
            completed = subprocess.run(
                target.command,
                check=True,
                capture_output=True,
                text=True,
                timeout=30,
            )
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            self._logger.exception("action execution failed")
            raise ActionExecutionError("Failed to execute allowlisted action") from exc

        output = "\n".join(part for part in [completed.stdout.strip(), completed.stderr.strip()] if part)
        message = _success_message(intent.action, intent.target)
        self._logger.info("action execution succeeded", extra={"intent": intent.dict()})
        return ExecutionResult(executed=True, message=message, output=output or None)


def _success_message(action: str, target) -> str:
    if action == "open_application":
        return f"Application launch requested: {target}"
    if action == "open_url":
        return f"URL open requested: {target}"
    return f"Action executed: {action}.{target}"
