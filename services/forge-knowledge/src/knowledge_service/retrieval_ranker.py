from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List


class RetrievalRanker:
    def score(self, row, metadata: Dict[str, Any], query_terms: List[str], content_matches: List[int], query: str = "") -> tuple[float, str, str]:
        relative_path = str(row["relative_path"])
        filename = Path(relative_path).name
        haystack_path = relative_path.lower()
        haystack_filename = filename.lower()
        source_text = " ".join(
            [
                str(row["source_id"]),
                str(row["display_name"]),
                str(metadata.get("path") or ""),
                str(row["group_name"] or ""),
                " ".join(metadata.get("tags") or []),
                " ".join(metadata.get("domainKeywords") or []),
                " ".join(metadata.get("ownsBusinessAreas") or []),
                str(metadata.get("contractRefs") or ""),
            ]
        ).lower()
        source_path = str(metadata.get("path") or "").lower()
        source_id = str(row["source_id"]).lower()
        flow_domain = str(self._row_value(row, "flow_domain") or "").upper()
        is_runtime_source = flow_domain == "CODE"
        is_test_file = flow_domain == "TEST"
        is_workflow_file = flow_domain == "WORKFLOW"
        has_contract_metadata = bool(metadata.get("contractRefs"))
        wants_workflow = any(
            term in query_terms for term in ["workflow", "workflows", "deploy", "deployment", "ci", "cd", "pipeline", "github", "action", "actions"]
        )
        wants_tests = any(term in query_terms for term in ["test", "tests", "testing", "unit", "it", "integration", "spec", "coverage"])
        wants_contract = any(
            term in query_terms for term in ["api", "apis", "endpoint", "endpoints", "contract", "contracts", "openapi", "schema", "schemas", "path", "paths"]
        )

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
            score += min(0.75, 0.25 * metadata_hits)
            reasons.append("matched source metadata")
            if match_type == "metadata":
                match_type = "metadata"
        path_metadata_hits = sum(1 for term in query_terms if term in source_path)
        if path_metadata_hits:
            score += min(0.35, 0.2 * path_metadata_hits)
            reasons.append("matched catalog source path")
        if any(term == source_id for term in query_terms):
            score += 0.5
            reasons.append("matched source id")
        has_match = score > 0
        if has_match and is_runtime_source and not wants_contract and not wants_workflow and not wants_tests:
            score += 0.35
            reasons.append("preferred runtime source")
        if has_match and is_test_file and not wants_tests:
            score -= 0.45
            reasons.append("down-ranked test file")
        elif has_match and is_test_file:
            score += 0.35
            reasons.append("preferred test file")
        if has_match and is_workflow_file and not wants_workflow:
            score -= 0.8
            reasons.append("down-ranked workflow file")
        elif has_match and is_workflow_file:
            score += 0.45
            reasons.append("preferred workflow file")
        if has_match and has_contract_metadata and wants_contract:
            score += 1.0
            reasons.append("preferred contract file")
        if not reasons:
            reasons.append("selected by keyword search")
        return round(max(score, 0.0), 4), match_type, "; ".join(reasons)

    def _row_value(self, row: Any, key: str) -> Any:
        try:
            if hasattr(row, "keys") and key not in row.keys():
                return None
            return row[key]
        except (KeyError, IndexError):
            return None
