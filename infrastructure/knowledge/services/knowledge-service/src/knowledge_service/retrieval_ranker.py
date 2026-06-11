from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List


class RetrievalRanker:
    def score(self, row, metadata: Dict[str, Any], query_terms: List[str], content_matches: List[int]) -> tuple[float, str, str]:
        relative_path = str(row["relative_path"])
        filename = Path(relative_path).name
        haystack_path = relative_path.lower()
        haystack_filename = filename.lower()
        source_text = " ".join([
            str(row["source_id"]),
            str(row["display_name"]),
            str(row["group_name"] or ""),
            " ".join(metadata.get("tags") or []),
            " ".join(metadata.get("domainKeywords") or []),
            " ".join(metadata.get("ownsBusinessAreas") or []),
            str(metadata.get("contractRefs") or ""),
        ]).lower()

        score = 0.0
        reasons: list[str] = []
        match_type = "metadata"
        if query_terms and all(term in haystack_path for term in query_terms):
            score += 0.9
            reasons.append("matched query terms in path")
            match_type = "path"
        elif any(term in haystack_path for term in query_terms):
            score += 0.55
            reasons.append("matched query term in path")
            match_type = "path"
        if any(term in haystack_filename for term in query_terms):
            score += 0.35
            reasons.append("matched filename")
            match_type = "path"
        if content_matches:
            score += min(0.8, 0.25 + 0.08 * len(content_matches))
            reasons.append(f"matched content on {len(content_matches)} line(s)")
            if match_type == "metadata":
                match_type = "content"
        metadata_hits = sum(1 for term in query_terms if term in source_text)
        if metadata_hits:
            score += min(0.45, 0.15 * metadata_hits)
            reasons.append("matched source metadata")
            if match_type == "metadata":
                match_type = "metadata"
        if not reasons:
            reasons.append("selected by keyword search")
        return round(score, 4), match_type, "; ".join(reasons)
