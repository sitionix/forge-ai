from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class FileMetadata:
    sourceId: str
    sourcePath: str
    absolutePath: str
    relativePath: str
    extension: str
    language: str
    flowDomain: str
    sizeBytes: int
    contentHash: str
    lastModified: str
    lineCount: int
    decodePolicy: str
