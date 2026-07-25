import pytest
from pydantic import ValidationError

from jarvis_agent.intent_parser import IntentParseError, parse_intent
from jarvis_agent.intent_schema import Intent


def test_valid_intent_accepted() -> None:
    intent = Intent.parse_obj({"action": "ollama_status", "target": "health", "arguments": {}})

    assert intent.action == "ollama_status"
    assert intent.target == "health"
    assert intent.arguments == {}


def test_unknown_fields_rejected() -> None:
    with pytest.raises(ValidationError):
        Intent.parse_obj({"action": "ollama_status", "target": "health", "arguments": {}, "command": "curl"})


def test_missing_action_rejected() -> None:
    with pytest.raises(ValidationError):
        Intent.parse_obj({"target": "firefox", "arguments": {}})


def test_invalid_json_rejected() -> None:
    with pytest.raises(IntentParseError):
        parse_intent("not json")
