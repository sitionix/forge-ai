from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence


@dataclass(frozen=True)
class _SegmentGlob:
    pattern: str

    def matches(self, segment: str) -> bool:
        previous = [False] * (len(segment) + 1)
        previous[0] = True
        for token in self.pattern:
            current = [False] * (len(segment) + 1)
            if token == "*":
                current[0] = previous[0]
                for index in range(1, len(segment) + 1):
                    current[index] = previous[index] or current[index - 1]
            elif token == "?":
                for index in range(1, len(segment) + 1):
                    current[index] = previous[index - 1]
            else:
                for index, char in enumerate(segment, start=1):
                    current[index] = previous[index - 1] and char == token
            previous = current
        return previous[len(segment)]


@dataclass(frozen=True)
class _CompiledPattern:
    raw: str
    segments: tuple[_SegmentGlob | None, ...]

    def matches(self, path_segments: Sequence[str]) -> bool:
        pattern_count = len(self.segments)
        path_count = len(path_segments)
        matched = [[False] * (path_count + 1) for _ in range(pattern_count + 1)]
        matched[0][0] = True
        for pattern_index, pattern_segment in enumerate(self.segments):
            for path_index in range(path_count + 1):
                if not matched[pattern_index][path_index]:
                    continue
                if pattern_segment is None:
                    matched[pattern_index + 1][path_index] = True
                    if path_index < path_count:
                        matched[pattern_index][path_index + 1] = True
                elif path_index < path_count and pattern_segment.matches(path_segments[path_index]):
                    matched[pattern_index + 1][path_index + 1] = True
        return matched[pattern_count][path_count]


class PathGlobMatcher:
    """Segment-aware, POSIX-style path glob matcher.

    Supported wildcards:
    - * matches zero or more characters inside one segment.
    - ? matches exactly one character inside one segment.
    - ** as a complete segment matches zero or more complete path segments.
    """

    def __init__(self, patterns: Iterable[str] = ()) -> None:
        self._patterns = tuple(self._compile(pattern) for pattern in patterns if str(pattern).strip())

    @property
    def patterns(self) -> tuple[str, ...]:
        return tuple(pattern.raw for pattern in self._patterns)

    def matches(self, relative_path: str) -> bool:
        normalized = self.normalize_path(relative_path)
        path_segments = tuple(normalized.split("/")) if normalized else ()
        return any(pattern.matches(path_segments) for pattern in self._patterns)

    @classmethod
    def match_any(cls, relative_path: str, patterns: Iterable[str]) -> bool:
        return cls(patterns).matches(relative_path)

    @staticmethod
    def normalize_path(path: str) -> str:
        value = str(path or "").replace("\\", "/").strip()
        if "\0" in value:
            raise ValueError("path must not contain NUL bytes")
        while value.startswith("./"):
            value = value[2:]
        segments = [segment for segment in value.strip("/").split("/") if segment and segment != "."]
        return "/".join(segments)

    @classmethod
    def _compile(cls, pattern: str) -> _CompiledPattern:
        normalized = cls.normalize_path(pattern)
        if not normalized:
            raise ValueError("path glob pattern must not be empty")
        segments: list[_SegmentGlob | None] = []
        for segment in normalized.split("/"):
            segments.append(None if segment == "**" else _SegmentGlob(_collapse_stars(segment)))
        return _CompiledPattern(normalized, tuple(segments))


def _collapse_stars(segment: str) -> str:
    collapsed = []
    previous_was_star = False
    for char in segment:
        if char == "*":
            if previous_was_star:
                continue
            previous_was_star = True
        else:
            previous_was_star = False
        collapsed.append(char)
    return "".join(collapsed)
