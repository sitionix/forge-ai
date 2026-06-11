from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator, Tuple

from knowledge_service.file_filters import is_binary_file, should_include_file
from knowledge_service.file_metadata import FileMetadata
from knowledge_service.path_security import is_under_root, safe_relative_path
from knowledge_service.source_catalog import SourceMetadata
from knowledge_service.source_config import IndexingConfig


def scan_source(source: SourceMetadata, indexing: IndexingConfig) -> Tuple[list[FileMetadata], int]:
    files: list[FileMetadata] = []
    skipped = 0
    if not source.rootExists:
        return files, skipped
    for path in _walk_files(source.absoluteRoot):
        if path.is_symlink() and not is_under_root(path, source.absoluteRoot):
            skipped += 1
            continue
        try:
            relative_path = safe_relative_path(path, source.absoluteRoot)
            stat = path.stat()
        except OSError:
            skipped += 1
            continue
        if not should_include_file(relative_path, indexing.include, indexing.exclude):
            skipped += 1
            continue
        if stat.st_size > indexing.max_file_size_bytes or is_binary_file(path):
            skipped += 1
            continue
        try:
            content = path.read_bytes()
        except OSError:
            skipped += 1
            continue
        files.append(FileMetadata(
            sourceId=source.sourceId,
            sourcePath=source.path,
            absolutePath=str(path.resolve()),
            relativePath=relative_path,
            extension=_extension(path),
            sizeBytes=stat.st_size,
            contentHash=hashlib.sha256(content).hexdigest(),
            lastModified=datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat(),
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
