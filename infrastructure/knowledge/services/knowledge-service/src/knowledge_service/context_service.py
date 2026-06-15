from __future__ import annotations

import json
from typing import Any, Dict, List

from knowledge_service.context_builder import ContextBuilder
from knowledge_service.context_schema import ContextItem, ContextRequest
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.retrieval_ranker import RetrievalRanker
from knowledge_service.snippet_extractor import SnippetExtractor


class ContextService:
    def __init__(self, store: InventoryStore):
        self.store = store
        self.extractor = SnippetExtractor()
        self.ranker = RetrievalRanker()
        self.builder = ContextBuilder()

    def context(self, request: ContextRequest) -> Dict[str, Any]:
        status = self.store.status()
        if status.get("status") == "EMPTY":
            return self.builder.empty_inventory(request.query, request.maxChars)

        rows, _ = self.store.search_rows(request.sourceIds, request.groups)
        query_terms = self._query_terms(request.query)
        candidates = []
        for row in rows:
            metadata = json.loads(row["metadata_json"])
            lines = self.extractor.read_lines(row["absolute_path"], metadata.get("absoluteRoot") or row["source_path"])
            if lines is None:
                continue
            content_matches = self._content_matches(lines, query_terms)
            score, match_type, reason = self.ranker.score(row, metadata, query_terms, content_matches, request.query)
            if score <= 0:
                continue
            matched_lines = content_matches or [0]
            for matched_line in matched_lines[:3]:
                if matched_line > 0:
                    start, end = self.extractor.content_range(lines, matched_line)
                else:
                    start, end = self.extractor.first_meaningful_range(lines, row["extension"] or "", row["relative_path"])
                candidates.append({
                    "row": row,
                    "metadata": metadata,
                    "lines": lines,
                    "lineStart": start,
                    "lineEnd": end,
                    "matchType": "content" if matched_line > 0 else match_type,
                    "reason": reason,
                    "score": score + (0.05 if matched_line > 0 else 0),
                })

        deduped = self._dedupe(candidates)
        deduped.sort(key=lambda item: (-item["score"], item["row"]["source_id"], item["row"]["relative_path"], item["lineStart"]))
        return self._budget(request, deduped[:request.maxItems])

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

    def _content_matches(self, lines: List[str], query_terms: List[str]) -> List[int]:
        matches = []
        for index, line in enumerate(lines, start=1):
            lower = line.lower()
            if any(term in lower for term in query_terms):
                matches.append(index)
        return matches

    def _dedupe(self, candidates: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        result: list[dict] = []
        for candidate in sorted(candidates, key=lambda item: (item["row"]["source_id"], item["row"]["relative_path"], item["lineStart"], -item["score"])):
            duplicate = None
            for existing in result:
                if (existing["row"]["source_id"] == candidate["row"]["source_id"]
                        and existing["row"]["relative_path"] == candidate["row"]["relative_path"]
                        and self._overlaps(existing, candidate)):
                    duplicate = existing
                    break
            if duplicate is None:
                result.append(candidate)
                continue
            duplicate["lineStart"] = min(duplicate["lineStart"], candidate["lineStart"])
            duplicate["lineEnd"] = max(duplicate["lineEnd"], candidate["lineEnd"])
            duplicate["score"] = max(duplicate["score"], candidate["score"])
            if candidate["matchType"] == "content":
                duplicate["matchType"] = "content"
        return result

    def _overlaps(self, left: Dict[str, Any], right: Dict[str, Any]) -> bool:
        return left["lineStart"] <= right["lineEnd"] + 1 and right["lineStart"] <= left["lineEnd"] + 1

    def _budget(self, request: ContextRequest, candidates: List[Dict[str, Any]]) -> Dict[str, Any]:
        items: list[ContextItem] = []
        used_chars = 0
        truncated = False
        for candidate in candidates:
            remaining = request.maxChars - used_chars
            if remaining <= 0:
                truncated = True
                break
            content, line_end = self.extractor.slice_content(candidate["lines"], candidate["lineStart"], candidate["lineEnd"], remaining)
            content_chars = len(content)
            if content_chars == 0 and request.includeContent:
                continue
            if line_end < candidate["lineEnd"]:
                truncated = True
            used_chars += content_chars
            metadata = candidate["metadata"]
            item_content = content if request.includeContent else None
            items.append(ContextItem(
                sourceId=candidate["row"]["source_id"],
                displayName=candidate["row"]["display_name"],
                group=candidate["row"]["group_name"],
                relativePath=candidate["row"]["relative_path"],
                lineStart=candidate["lineStart"],
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
            ))
            if len(items) >= request.maxItems:
                truncated = truncated or len(candidates) > len(items)
                break
        return self.builder.build(request.query, items, request.maxChars, used_chars, truncated)
