from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator, Tuple

from knowledge_service.file_filters import is_excluded_file, is_included_file
from knowledge_service.file_metadata import FileMetadata
from knowledge_service.path_security import is_under_root, safe_relative_path
from knowledge_service.skipped_reasons import SkippedBreakdown, SkippedReason
from knowledge_service.source_catalog import SourceMetadata
from knowledge_service.source_config import IndexingConfig


def scan_source(source: SourceMetadata, indexing: IndexingConfig) -> Tuple[list[FileMetadata], SkippedBreakdown]:
    files: list[FileMetadata] = []
    skipped = SkippedBreakdown()
    if not source.rootExists:
        skipped.increment(SkippedReason.MISSING_SOURCE_ROOT)
        return files, skipped
    for path in _walk_files(source.absoluteRoot):
        if path.is_symlink() and not is_under_root(path, source.absoluteRoot):
            skipped.increment(SkippedReason.SYMLINK_OUTSIDE_ROOT)
            continue
        try:
            relative_path = safe_relative_path(path, source.absoluteRoot)
            stat = path.stat()
        except ValueError:
            skipped.increment(SkippedReason.UNSAFE_PATH)
            continue
        except OSError:
            skipped.increment(SkippedReason.UNREADABLE)
            continue
        if is_excluded_file(relative_path, indexing.exclude):
            skipped.increment(SkippedReason.EXCLUDED_BY_PATTERN)
            continue
        if not is_included_file(relative_path, indexing.include):
            skipped.increment(SkippedReason.NOT_INCLUDED)
            continue
        if stat.st_size > indexing.max_file_size_bytes:
            skipped.increment(SkippedReason.TOO_LARGE)
            continue
        try:
            if _is_binary_file(path):
                skipped.increment(SkippedReason.BINARY)
                continue
        except OSError:
            skipped.increment(SkippedReason.UNREADABLE)
            continue
        try:
            content = path.read_bytes()
        except OSError:
            skipped.increment(SkippedReason.UNREADABLE)
            continue
        decoded = content.decode("utf-8", errors="replace")
        files.append(FileMetadata(
            sourceId=source.sourceId,
            sourcePath=source.path,
            absolutePath=str(path.resolve()),
            relativePath=relative_path,
            extension=_extension(path),
            sizeBytes=stat.st_size,
            contentHash=hashlib.sha256(content).hexdigest(),
            lastModified=datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
            lineCount=_line_count(decoded),
            decodePolicy="utf-8:replace",
        ))
    return files, skipped


def _walk_files(root: Path) -> Iterator[Path]:
    for path in root.rglob("*"):
        if path.is_file():
            yield path


def _extension(path: Path) -> str:
    if path.name == "pom.xml":
        return ".xml"
    return path.suffix


def _is_binary_file(path: Path, sample_size: int = 8192) -> bool:
    sample = path.read_bytes()[:sample_size]
    return b"\0" in sample


def _line_count(text: str) -> int:
    if text == "":
        return 0
    return text.count("\n") + (0 if text.endswith("\n") else 1)
