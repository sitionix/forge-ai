from __future__ import annotations

import re
from typing import Any


FORBIDDEN_RESPONSE_LANGUAGES = frozenset({"ru"})

_LANGUAGE_CODE_RE = re.compile(r"^[a-z]{2,3}$")


def normalize_language_code(value: Any, *, allow_auto: bool = False, allow_und: bool = False) -> str:
    normalized = str(value or "").strip().lower()
    if not normalized:
        return ""
    if allow_auto and normalized == "auto":
        return "auto"
    normalized = normalized.split("-", 1)[0]
    if normalized == "unresolved":
        normalized = "und"
    if allow_und and normalized == "und":
        return "und"
    if normalized == "und":
        return ""
    if _LANGUAGE_CODE_RE.match(normalized):
        return normalized
    return ""


def normalize_detected_language(value: Any) -> str:
    return normalize_language_code(value, allow_und=True)


def normalize_response_language(value: Any, *, allow_auto: bool = False) -> str:
    normalized = normalize_language_code(value, allow_auto=allow_auto)
    if normalized == "auto":
        return normalized
    if is_forbidden_response_language(normalized):
        return ""
    return normalized


def is_forbidden_response_language(value: Any) -> bool:
    normalized = normalize_language_code(value)
    return bool(normalized and normalized in FORBIDDEN_RESPONSE_LANGUAGES)
