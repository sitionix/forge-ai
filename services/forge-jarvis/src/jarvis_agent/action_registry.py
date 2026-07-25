from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List

import yaml

from jarvis_agent.intent_schema import Intent
from jarvis_agent.security import validate_command


class ActionNotAllowedError(ValueError):
    """Raised when an action or target is not allowlisted."""


@dataclass(frozen=True)
class ActionTarget:
    name: str
    command: List[str]


@dataclass(frozen=True)
class ActionDefinition:
    name: str
    description: str
    targets: Dict[str, ActionTarget]


class ActionRegistry:
    def __init__(self, actions: Dict[str, ActionDefinition]) -> None:
        self._actions = actions

    @classmethod
    def from_yaml(cls, path: Path) -> "ActionRegistry":
        with path.open("r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle) or {}
        actions_data = data.get("actions", {})
        if not isinstance(actions_data, dict):
            raise ValueError("allowed-actions.yaml must contain an actions mapping")

        actions: Dict[str, ActionDefinition] = {}
        for action_name, action_data in actions_data.items():
            if not isinstance(action_data, dict):
                raise ValueError(f"Invalid action entry: {action_name}")
            targets_data = action_data.get("targets", {})
            if not isinstance(targets_data, dict):
                raise ValueError(f"Invalid targets for action: {action_name}")

            targets: Dict[str, ActionTarget] = {}
            for target_name, target_data in targets_data.items():
                command = target_data.get("command") if isinstance(target_data, dict) else None
                if not isinstance(command, list) or not all(isinstance(part, str) for part in command):
                    raise ValueError(f"Invalid command for {action_name}.{target_name}")
                validate_command(command)
                targets[target_name] = ActionTarget(name=target_name, command=command)

            actions[action_name] = ActionDefinition(
                name=action_name,
                description=str(action_data.get("description", "")),
                targets=targets,
            )

        return cls(actions)

    def public_actions(self) -> Dict[str, List[Dict[str, Any]]]:
        return {
            "actions": [
                {
                    "action": action.name,
                    "description": action.description,
                    "targets": sorted(action.targets.keys()),
                }
                for action in sorted(self._actions.values(), key=lambda item: item.name)
            ]
        }

    def available_actions_for_prompt(self) -> List[Dict[str, Any]]:
        return self.public_actions()["actions"]

    def resolve(self, intent: Intent) -> ActionTarget:
        if intent.action == "unsupported":
            raise ActionNotAllowedError("Unsupported intent is not executable")

        action = self._actions.get(intent.action)
        if action is None:
            raise ActionNotAllowedError(f"Unknown action: {intent.action}")
        if intent.target is None:
            raise ActionNotAllowedError(f"Target is required for action: {intent.action}")
        target = action.targets.get(intent.target)
        if target is None:
            raise ActionNotAllowedError(f"Unknown target: {intent.action}.{intent.target}")
        return target
