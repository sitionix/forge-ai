from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

from knowledge_service.inventory_store import InventoryStore
from knowledge_service.path_security import is_under_root


SUPPORTED_DECODE_POLICY = "utf-8:replace"


@dataclass(frozen=True)
class InventoryFileContent:
    row: sqlite3.Row
    metadata: Dict[str, Any]
    lines: List[str]
    content: str
    lineCount: int
    decodePolicy: str


@dataclass(frozen=True)
class InventoryFileReadResult:
    content: Optional[InventoryFileContent]
    diagnostic: Optional[Dict[str, Any]] = None

    @property
    def ok(self) -> bool:
        return self.content is not None


class InventoryFileResolver:
    def __init__(self, store: InventoryStore):
        self.store = store

    def read(self, row: sqlite3.Row) -> InventoryFileReadResult:
        metadata = self._metadata(row)
        root = self._source_root(row, metadata)
        if root is None:
            return self._failure(row, "FILE_SOURCE_ROOT_MISSING", "Indexed file source root is not available")
        path = Path(row["absolute_path"])
        if not is_under_root(path, root):
            return self._failure(row, "FILE_OUTSIDE_SOURCE_ROOT", "Indexed file path is outside its source root")
        if not path.exists() or not path.is_file():
            return self._failure(row, "FILE_MISSING", "Indexed file no longer exists")
        if not is_under_root(path, root):
            return self._failure(row, "FILE_OUTSIDE_SOURCE_ROOT", "Indexed file path is outside its source root")
        decode_policy = row["decode_policy"] or SUPPORTED_DECODE_POLICY
        if decode_policy != SUPPORTED_DECODE_POLICY:
            return self._failure(row, "FILE_DECODE_POLICY_UNSUPPORTED", f"Unsupported indexed file decode policy: {decode_policy}")
        try:
            content = path.read_bytes().decode("utf-8", errors="replace")
        except OSError:
            return self._failure(row, "FILE_UNREADABLE", "Indexed file could not be read safely")
        lines = content.splitlines()
        return InventoryFileReadResult(
            InventoryFileContent(
                row=row,
                metadata=metadata,
                lines=lines,
                content="\n".join(lines),
                lineCount=len(lines),
                decodePolicy=decode_policy,
            )
        )

    def read_by_id(self, file_id: int) -> InventoryFileReadResult:
        row = self._row_by_id(file_id)
        if row is None:
            return InventoryFileReadResult(
                None,
                {
                    "code": "FILE_NOT_INDEXED",
                    "message": "Inventory file id was not found",
                    "fileId": file_id,
                },
            )
        return self.read(row)

    def _row_by_id(self, file_id: int) -> Optional[sqlite3.Row]:
        self.store.init()
        with self.store._connect() as conn:
            return conn.execute(
                """
                SELECT f.*, s.display_name, s.group_name, s.tags_json, s.metadata_json
                FROM files f
                JOIN sources s ON s.source_id = f.source_id
                WHERE f.id = ?
                """,
                (file_id,),
            ).fetchone()

    def _metadata(self, row: sqlite3.Row) -> Dict[str, Any]:
        try:
            decoded = json.loads(row["metadata_json"] or "{}")
        except json.JSONDecodeError:
            return {}
        return decoded if isinstance(decoded, dict) else {}

    def _source_root(self, row: sqlite3.Row, metadata: Dict[str, Any]) -> Optional[Path]:
        root = metadata.get("absoluteRoot") or row["source_path"]
        if not root:
            return None
        return Path(root)

    def _failure(self, row: sqlite3.Row, code: str, message: str) -> InventoryFileReadResult:
        return InventoryFileReadResult(
            None,
            {
                "code": code,
                "message": message,
                "sourceId": row["source_id"],
                "relativePath": row["relative_path"],
            },
        )
