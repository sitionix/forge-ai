from __future__ import annotations

import json

from pydantic import ValidationError

from jarvis_agent.intent_schema import Intent


class IntentParseError(ValueError):
    """Raised when the model output cannot be parsed as a strict intent."""


def parse_intent(raw_output: str) -> Intent:
    try:
        parsed = json.loads(raw_output)
    except json.JSONDecodeError as exc:
        raise IntentParseError("Model did not return valid JSON") from exc

    try:
        return Intent.parse_obj(parsed)
    except ValidationError as exc:
        raise IntentParseError("Model JSON did not match intent schema") from exc
