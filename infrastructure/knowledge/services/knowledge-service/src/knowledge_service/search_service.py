from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List

from knowledge_service.inventory_store import InventoryStore


class SearchService:
    def __init__(self, store: InventoryStore):
        self.store = store

    def search(self, query: str, source_ids: List[str], groups: List[str], limit: int) -> Dict[str, Any]:
        needle = (query or "").strip()
        if not needle:
            return {"query": query, "results": []}
        status = self.store.status()
        if status.get("status") == "EMPTY":
            return {"query": query, "results": [], "message": "Inventory is empty. Build inventory first."}
        rows, _ = self.store.search_rows(source_ids, groups)
        results: list[dict] = []
        lower = needle.lower()
        for row in rows:
            if len(results) >= limit:
                break
            metadata = json.loads(row["metadata_json"])
            haystack = " ".join([
                row["source_id"], row["display_name"], row["relative_path"],
                " ".join(metadata.get("tags") or []), " ".join(metadata.get("domainKeywords") or []),
            ]).lower()
            if lower in haystack:
                results.append(self._result(row, 1, 1, row["relative_path"], "path", 0.7))
                continue
            content_match = self._content_match(Path(row["absolute_path"]), lower)
            if content_match:
                line_no, snippet = content_match
                results.append(self._result(row, line_no, line_no, snippet, "content", 1.0))
        return {"query": query, "results": results}

    def _content_match(self, path: Path, lower: str) -> tuple[int, str] | None:
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            return None
        for index, line in enumerate(lines, start=1):
            if lower in line.lower():
                start = max(1, index - 2)
                end = min(len(lines), index + 2)
                snippet = "\n".join(lines[start - 1:end])
                return start, snippet
        return None

    def _result(self, row, line_start: int, line_end: int, snippet: str, match_type: str, score: float) -> dict:
        return {
            "sourceId": row["source_id"],
            "displayName": row["display_name"],
            "relativePath": row["relative_path"],
            "lineStart": line_start,
            "lineEnd": line_end,
            "snippet": snippet,
            "matchType": match_type,
            "score": score,
        }
