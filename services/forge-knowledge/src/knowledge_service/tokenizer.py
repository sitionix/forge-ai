from __future__ import annotations

import re
from typing import Iterable, List


_TOKEN_RE = re.compile(r"[A-Za-z0-9]+")
_CAMEL_RE = re.compile(r"(?<!^)(?=[A-Z])")


def tokenize(value: str | None) -> List[str]:
    if not value:
        return []
    raw_tokens: list[str] = []
    normalized = value.replace("/", " ").replace("\\", " ").replace("_", " ").replace("-", " ").replace(".", " ")
    for part in normalized.split():
        raw_tokens.extend(_CAMEL_RE.sub(" ", part).split())
    tokens: list[str] = []
    for raw in raw_tokens:
        for match in _TOKEN_RE.findall(raw):
            token = match.lower()
            if token and token not in tokens:
                tokens.append(token)
    return tokens


def merge_tokens(*groups: Iterable[str]) -> List[str]:
    result: list[str] = []
    for group in groups:
        for token in group:
            if token and token not in result:
                result.append(token)
    return result
