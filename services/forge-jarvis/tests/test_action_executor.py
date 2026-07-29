from pathlib import Path
from typing import List, Optional

import pytest

from jarvis_agent.action_executor import ActionExecutionError, ActionExecutor
from jarvis_agent.action_registry import ActionNotAllowedError, ActionRegistry
from jarvis_agent.intent_schema import Intent


def registry(tmp_path: Path, command: Optional[List[str]] = None) -> ActionRegistry:
    command = command or ["bash", "-c", "printf 'jarvis-ok'"]
    yaml_path = tmp_path / "allowed-actions.yaml"
    yaml_path.write_text(
        """
actions:
  safe:
    description: "safe test command"
    targets:
      run:
        command: COMMAND
""".replace("COMMAND", repr(command)),
        encoding="utf-8",
    )
    return ActionRegistry.from_yaml(yaml_path)


def test_known_safe_action_executes_through_async_subprocess(tmp_path) -> None:
    result = ActionExecutor(registry(tmp_path)).execute(
        Intent(action="safe", target="run", arguments={}),
        user_text="run safe command",
    )

    assert result.executed is True
    assert result.message == "Action executed: safe.run"
    assert result.output == "jarvis-ok"


def test_unknown_action_is_not_executed(tmp_path) -> None:
    with pytest.raises(ActionNotAllowedError):
        ActionExecutor(registry(tmp_path)).execute(
            Intent(action="delete_files", target="home", arguments={}),
            user_text="delete files",
        )


def test_unsupported_action_is_not_executed(tmp_path) -> None:
    with pytest.raises(ActionNotAllowedError):
        ActionExecutor(registry(tmp_path)).execute(
            Intent(action="unsupported", target=None, arguments={"reason": "not supported"}),
            user_text="format my drive",
        )


def test_model_generated_shell_command_intent_is_not_executed(tmp_path) -> None:
    with pytest.raises(ActionNotAllowedError):
        ActionExecutor(registry(tmp_path)).execute(
            Intent(action="bash", target="rm_rf", arguments={"command": "rm -rf /"}),
            user_text="delete everything",
        )


def test_output_is_capped_before_full_collection(tmp_path) -> None:
    executor = ActionExecutor(
        registry(tmp_path, ["bash", "-c", "yes jarvis | head -n 1000"]),
        max_output_bytes=64,
    )

    result = executor.execute(Intent(action="safe", target="run", arguments={}), user_text="cap output")

    assert result.output is not None
    assert len(result.output.encode("utf-8")) <= 96
    assert "[output truncated]" in result.output


def test_timeout_terminates_command(tmp_path) -> None:
    executor = ActionExecutor(
        registry(tmp_path, ["bash", "-c", "sleep 5"]),
        timeout_seconds=1,
    )

    with pytest.raises(ActionExecutionError, match="timed out"):
        executor.execute(Intent(action="safe", target="run", arguments={}), user_text="timeout")
