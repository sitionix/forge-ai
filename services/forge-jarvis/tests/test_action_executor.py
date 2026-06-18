import subprocess
from pathlib import Path
from unittest.mock import Mock, patch

import pytest

from jarvis_agent.action_executor import ActionExecutor
from jarvis_agent.action_registry import ActionNotAllowedError, ActionRegistry
from jarvis_agent.intent_schema import Intent


def registry() -> ActionRegistry:
    root = Path(__file__).resolve().parents[3]
    return ActionRegistry.from_yaml(root / "config" / "jarvis" / "allowed-actions.yaml")


def test_known_safe_action_executes_through_subprocess() -> None:
    command = ["bash", "-lc", "curl -s http://localhost:11434/api/tags >/dev/null && echo 'Ollama is reachable'"]
    completed = subprocess.CompletedProcess(args=command, returncode=0, stdout="Ollama is reachable\n", stderr="")
    with patch("jarvis_agent.action_executor.subprocess.run", return_value=completed) as run:
        result = ActionExecutor(registry()).execute(
            Intent(action="ollama_status", target="health", arguments={}),
            user_text="перевір ollama",
        )

    run.assert_called_once_with(command, check=True, capture_output=True, text=True, timeout=30)
    assert result.executed is True
    assert result.message == "Action executed: ollama_status.health"
    assert result.output == "Ollama is reachable"


def test_unknown_action_is_not_executed() -> None:
    run = Mock()
    with patch("jarvis_agent.action_executor.subprocess.run", run):
        with pytest.raises(ActionNotAllowedError):
            ActionExecutor(registry()).execute(
                Intent(action="delete_files", target="home", arguments={}),
                user_text="delete files",
            )

    run.assert_not_called()


def test_unsupported_action_is_not_executed() -> None:
    run = Mock()
    with patch("jarvis_agent.action_executor.subprocess.run", run):
        with pytest.raises(ActionNotAllowedError):
            ActionExecutor(registry()).execute(
                Intent(action="unsupported", target=None, arguments={"reason": "not supported"}),
                user_text="format my drive",
            )

    run.assert_not_called()


def test_model_generated_shell_command_intent_is_not_executed() -> None:
    run = Mock()
    with patch("jarvis_agent.action_executor.subprocess.run", run):
        with pytest.raises(ActionNotAllowedError):
            ActionExecutor(registry()).execute(
                Intent(action="bash", target="rm_rf", arguments={"command": "rm -rf /"}),
                user_text="delete everything",
            )

    run.assert_not_called()
