from __future__ import annotations

import hashlib
import codecs
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterator, Optional, Tuple

from knowledge_service.file_classification import FileClassifier, UNKNOWN_FLOW_DOMAIN
from knowledge_service.file_filters import is_excluded_file, is_excluded_file_exception, is_included_file
from knowledge_service.file_metadata import ContextChunk, FileMetadata
from knowledge_service.path_security import is_under_root, safe_relative_path
from knowledge_service.skipped_reasons import SkippedBreakdown, SkippedReason
from knowledge_service.source_catalog import SourceMetadata
from knowledge_service.source_config import IndexingConfig

DECODE_POLICY = "utf-8:replace"
READ_BUFFER_SIZE = 64 * 1024
BINARY_SAMPLE_SIZE = 8192


def scan_source(
    source: SourceMetadata,
    indexing: IndexingConfig,
    classifier: FileClassifier,
    previous_files: Optional[Dict[str, Dict[str, object]]] = None,
) -> Tuple[list[FileMetadata], SkippedBreakdown]:
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
        excluded = is_excluded_file(relative_path, indexing.exclude)
        if excluded and not is_excluded_file_exception(relative_path, indexing.exclude_exceptions):
            skipped.increment(SkippedReason.EXCLUDED_BY_PATTERN)
            continue
        if not is_included_file(relative_path, indexing.include):
            skipped.increment(SkippedReason.NOT_INCLUDED)
            continue
        classification = classifier.classify(relative_path)
        if classification.flow_domain == UNKNOWN_FLOW_DOMAIN:
            skipped.increment(SkippedReason.NOT_INCLUDED)
            continue
        if stat.st_size > indexing.max_file_size_bytes:
            skipped.increment(SkippedReason.TOO_LARGE)
            continue
        last_modified = datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()
        previous = (previous_files or {}).get(relative_path)
        previous_size = previous.get("sizeBytes") if previous else None
        previous_modified = str(previous.get("lastModified") or "") if previous else ""
        if previous and int(str(previous_size or -1)) == stat.st_size and previous_modified == last_modified:
            files.append(
                FileMetadata(
                    sourceId=source.sourceId,
                    sourcePath=source.path,
                    absolutePath=str(path.resolve()),
                    relativePath=relative_path,
                    extension=classification.extension,
                    language=classification.language,
                    flowDomain=classification.flow_domain,
                    sizeBytes=stat.st_size,
                    contentHash=str(previous.get("contentHash") or ""),
                    lastModified=last_modified,
                    lineCount=int(str(previous.get("lineCount") or 0)),
                    decodePolicy=str(previous.get("decodePolicy") or DECODE_POLICY),
                    chunks=(),
                    changed=False,
                )
            )
            continue
        try:
            content_hash, chunks, line_count, is_binary = _read_changed_file(path)
        except (OSError, UnicodeError):
            skipped.increment(SkippedReason.UNREADABLE)
            continue
        if is_binary:
            skipped.increment(SkippedReason.BINARY)
            continue
        files.append(
            FileMetadata(
                sourceId=source.sourceId,
                sourcePath=source.path,
                absolutePath=str(path.resolve()),
                relativePath=relative_path,
                extension=classification.extension,
                language=classification.language,
                flowDomain=classification.flow_domain,
                sizeBytes=stat.st_size,
                contentHash=content_hash,
                lastModified=last_modified,
                lineCount=line_count,
                decodePolicy=DECODE_POLICY,
                chunks=tuple(chunks),
                changed=True,
            )
        )
    return files, skipped


def _walk_files(root: Path) -> Iterator[Path]:
    for path in root.rglob("*"):
        if path.is_file():
            yield path


def _read_changed_file(path: Path) -> tuple[str, list[ContextChunk], int, bool]:
    checksum = hashlib.sha256()
    sample = b""
    chunker = _LineChunker()
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    is_binary = False
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(READ_BUFFER_SIZE)
            if not chunk:
                break
            checksum.update(chunk)
            if len(sample) < BINARY_SAMPLE_SIZE:
                sample += chunk[: BINARY_SAMPLE_SIZE - len(sample)]
                if b"\0" in sample:
                    is_binary = True
            if not is_binary:
                chunker.feed(decoder.decode(chunk, final=False))
    if is_binary:
        return checksum.hexdigest(), [], 0, True
    tail = decoder.decode(b"", final=True)
    if tail:
        chunker.feed(tail)
    chunks, line_count = chunker.finish()
    return checksum.hexdigest(), chunks, line_count, False


class _LineChunker:
    def __init__(self, chunk_lines: int = 80, overlap_lines: int = 8):
        self.chunk_lines = chunk_lines
        self.overlap_lines = overlap_lines
        self.lines: list[str] = []
        self.current_start = 1
        self.pending = ""
        self.line_count = 0
        self.chunks: list[ContextChunk] = []

    def feed(self, text: str) -> None:
        if not text:
            return
        parts = (self.pending + text).split("\n")
        self.pending = parts.pop()
        for line in parts:
            self._append_line(line.rstrip("\r"))

    def finish(self) -> tuple[list[ContextChunk], int]:
        if self.pending:
            self._append_line(self.pending.rstrip("\r"))
            self.pending = ""
        self._flush(final=True)
        return self.chunks, self.line_count

    def _append_line(self, line: str) -> None:
        self.line_count += 1
        self.lines.append(line)
        if len(self.lines) >= self.chunk_lines:
            self._flush(final=False)

    def _flush(self, final: bool) -> None:
        if not self.lines:
            return
        if not final and len(self.lines) < self.chunk_lines:
            return
        line_end = self.current_start + len(self.lines) - 1
        content = "\n".join(self.lines).strip()
        if content:
            self.chunks.append(ContextChunk(self.current_start, line_end, content))
        if final:
            self.lines = []
            return
        keep = min(self.overlap_lines, len(self.lines))
        self.lines = self.lines[-keep:] if keep else []
        self.current_start = line_end - keep + 1 if keep else line_end + 1
