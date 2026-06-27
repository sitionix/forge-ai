from __future__ import annotations

import json
from typing import Any, Dict, List

from knowledge_service.context_builder import ContextBuilder
from knowledge_service.context_schema import ContextItem, ContextRequest
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.retrieval_ranker import RetrievalRanker


class ContextService:
    def __init__(self, store: InventoryStore):
        self.store = store
        self.ranker = RetrievalRanker()
        self.builder = ContextBuilder()

    def context(self, request: ContextRequest) -> Dict[str, Any]:
        status = self.store.status()
        if status.get("status") == "EMPTY":
            return self.builder.empty_inventory(request.query, request.maxChars)

        rows = self.store.search_context_chunks(
            request.query,
            request.sourceIds,
            request.groups,
            limit=min(request.maxItems * 8, 200),
            include_content=request.includeContent,
        )
        ranked = self._rank(request, rows)
        return self._budget(request, ranked)

    def _rank(self, request: ContextRequest, rows: List[Any]) -> List[Dict[str, Any]]:
        query_terms = self._query_terms(request.query)
        candidates: list[dict[str, Any]] = []
        for row in rows:
            metadata = json.loads(row["metadata_json"] or "{}")
            score, match_type, reason = self.ranker.score(row, metadata, query_terms, [int(row["line_start"])], request.query)
            candidates.append({"row": row, "metadata": metadata, "score": score, "matchType": match_type, "reason": reason})
        candidates.sort(
            key=lambda item: (
                -item["score"],
                float(item["row"]["rank_score"] or 0.0),
                item["row"]["source_id"],
                item["row"]["relative_path"],
                int(item["row"]["line_start"]),
            )
        )
        return candidates

    def _query_terms(self, query: str) -> List[str]:
        cleaned = []
        for raw in query.replace("/", " ").replace("\\", " ").replace(".", " ").replace("_", " ").replace("-", " ").split():
            term = raw.strip().lower()
            if term and term not in cleaned:
                cleaned.append(term)
        compact = query.strip().lower()
        if compact and len(cleaned) <= 1 and compact not in cleaned:
            cleaned.insert(0, compact)
        return cleaned

    def _budget(self, request: ContextRequest, candidates: List[Any]) -> Dict[str, Any]:
        items: list[ContextItem] = []
        used_chars = 0
        truncated = False
        for candidate in candidates:
            remaining = request.maxChars - used_chars
            if remaining <= 0:
                truncated = True
                break
            row = candidate["row"]
            raw_content = str(row["content"] or "") if request.includeContent else ""
            content = raw_content[:remaining]
            line_end = int(row["line_end"])
            if raw_content and len(content) < len(raw_content):
                line_end = min(line_end, int(row["line_start"]) + content.count("\n"))
            content_chars = len(content)
            if content_chars == 0 and request.includeContent:
                continue
            if raw_content and len(content) < len(raw_content):
                truncated = True
            used_chars += content_chars
            metadata = candidate["metadata"]
            item_content = content if request.includeContent else None
            items.append(
                ContextItem(
                    sourceId=row["source_id"],
                    displayName=row["display_name"],
                    group=row["group_name"],
                    relativePath=row["relative_path"],
                    lineStart=int(row["line_start"]),
                    lineEnd=line_end,
                    content=item_content,
                    matchType=candidate["matchType"],
                    reason=candidate["reason"],
                    score=round(candidate["score"], 4),
                    metadata={
                        "tags": metadata.get("tags") or [],
                        "domainKeywords": metadata.get("domainKeywords") or [],
                        "ownsBusinessAreas": metadata.get("ownsBusinessAreas") or [],
                    },
                )
            )
            if len(items) >= request.maxItems:
                truncated = truncated or len(candidates) > len(items)
                break
        return self.builder.build(request.query, items, request.maxChars, used_chars, truncated)
