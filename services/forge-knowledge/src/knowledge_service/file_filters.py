from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from knowledge_service.path_glob import PathGlobMatcher


@dataclass(frozen=True)
class FilePathFilter:
    include: PathGlobMatcher
    exclude: PathGlobMatcher
    exclude_exceptions: PathGlobMatcher

    @classmethod
    def from_patterns(
        cls,
        include: Iterable[str],
        exclude: Iterable[str],
        exclude_exceptions: Iterable[str] = (),
    ) -> "FilePathFilter":
        return cls(
            include=PathGlobMatcher(include),
            exclude=PathGlobMatcher(exclude),
            exclude_exceptions=PathGlobMatcher(exclude_exceptions),
        )

    def should_include_file(self, relative_path: str) -> bool:
        if self.is_excluded_file(relative_path) and not self.is_excluded_file_exception(relative_path):
            return False
        return self.is_included_file(relative_path)

    def is_excluded_file(self, relative_path: str) -> bool:
        return self.exclude.matches(relative_path)

    def is_excluded_file_exception(self, relative_path: str) -> bool:
        return self.exclude_exceptions.matches(relative_path)

    def is_included_file(self, relative_path: str) -> bool:
        return self.include.matches(relative_path)


def should_include_file(
    relative_path: str,
    include: Iterable[str],
    exclude: Iterable[str],
    exclude_exceptions: Iterable[str] = (),
) -> bool:
    return FilePathFilter.from_patterns(include, exclude, exclude_exceptions).should_include_file(relative_path)


def is_excluded_file(relative_path: str, exclude: Iterable[str]) -> bool:
    return PathGlobMatcher(exclude).matches(relative_path)


def is_excluded_file_exception(relative_path: str, exclude_exceptions: Iterable[str]) -> bool:
    return PathGlobMatcher(exclude_exceptions).matches(relative_path)


def is_included_file(relative_path: str, include: Iterable[str]) -> bool:
    return PathGlobMatcher(include).matches(relative_path)


def is_binary_file(path: Path, sample_size: int = 8192) -> bool:
    try:
        with path.open("rb") as handle:
            sample = handle.read(sample_size)
    except OSError:
        return True
    return b"\0" in sample
