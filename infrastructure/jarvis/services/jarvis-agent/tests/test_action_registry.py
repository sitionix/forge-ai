from pathlib import Path

import pytest

from jarvis_agent.action_registry import ActionNotAllowedError, ActionRegistry
from jarvis_agent.intent_schema import Intent


def registry() -> ActionRegistry:
    root = Path(__file__).resolve().parents[3]
    return ActionRegistry.from_yaml(root / "config" / "allowed-actions.yaml")


def test_allowed_action_loaded() -> None:
    target = registry().resolve(Intent(action="ollama_status", target="health", arguments={}))

    assert target.command == ["bash", "-lc", "curl -s http://localhost:11434/api/tags >/dev/null && echo 'Ollama is reachable'"]


def test_removed_url_action_is_not_present() -> None:
    public_actions = registry().public_actions()["actions"]

    assert all(action["action"] != "open_url" for action in public_actions)
    removed_target = "open_" + "web" + "ui"
    assert all(removed_target not in action["targets"] for action in public_actions)


def test_unknown_action_rejected() -> None:
    with pytest.raises(ActionNotAllowedError):
        registry().resolve(Intent(action="delete_files", target="home", arguments={}))


def test_unknown_target_rejected() -> None:
    with pytest.raises(ActionNotAllowedError):
        registry().resolve(Intent(action="ollama_status", target="unknown", arguments={}))
