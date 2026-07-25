from __future__ import annotations

import fnmatch
from pathlib import Path
from typing import Iterable

EXCLUDED_DIR_NAMES = {".git", ".idea", "target", "build", "dist", "node_modules", ".venv", "var", "logs"}


def matches_any(relative_path: str, patterns: Iterable[str]) -> bool:
    normalized = relative_path.replace("\\", "/")
    name = Path(normalized).name
    for pattern in patterns:
        if fnmatch.fnmatch(normalized, pattern) or fnmatch.fnmatch(name, pattern):
            return True
        if pattern.startswith("**/") and fnmatch.fnmatch(normalized, pattern[3:]):
            return True
    return False


def should_include_file(
    relative_path: str,
    include: Iterable[str],
    exclude: Iterable[str],
    exclude_exceptions: Iterable[str] = (),
) -> bool:
    normalized = relative_path.replace("\\", "/")
    if is_excluded_file(normalized, exclude) and not is_excluded_file_exception(normalized, exclude_exceptions):
        return False
    return is_included_file(normalized, include)


def is_excluded_file(relative_path: str, exclude: Iterable[str]) -> bool:
    normalized = relative_path.replace("\\", "/")
    parts = normalized.split("/")
    return matches_any(normalized, exclude) or any(part in EXCLUDED_DIR_NAMES for part in parts[:-1])


def is_excluded_file_exception(relative_path: str, exclude_exceptions: Iterable[str]) -> bool:
    normalized = relative_path.replace("\\", "/")
    return matches_any(normalized, exclude_exceptions)


def is_included_file(relative_path: str, include: Iterable[str]) -> bool:
    normalized = relative_path.replace("\\", "/")
    return matches_any(normalized, include)


def is_binary_file(path: Path, sample_size: int = 8192) -> bool:
    try:
        with path.open("rb") as handle:
            sample = handle.read(sample_size)
    except OSError:
        return True
    return b"\0" in sample
