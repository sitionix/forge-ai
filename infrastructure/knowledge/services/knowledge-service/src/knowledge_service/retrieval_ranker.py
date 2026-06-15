from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, List


class RetrievalRanker:
    def score(self, row, metadata: Dict[str, Any], query_terms: List[str], content_matches: List[int], query: str = "") -> tuple[float, str, str]:
        relative_path = str(row["relative_path"])
        filename = Path(relative_path).name
        haystack_path = relative_path.lower()
        haystack_filename = filename.lower()
        source_text = " ".join([
            str(row["source_id"]),
            str(row["display_name"]),
            str(metadata.get("path") or ""),
            str(row["group_name"] or ""),
            " ".join(metadata.get("tags") or []),
            " ".join(metadata.get("domainKeywords") or []),
            " ".join(metadata.get("ownsBusinessAreas") or []),
            str(metadata.get("contractRefs") or ""),
        ]).lower()
        source_path = str(metadata.get("path") or "").lower()
        source_id = str(row["source_id"]).lower()
        is_runtime_source = "/src/main/" in f"/{haystack_path}"
        is_test_file = (
            "/src/test/" in f"/{haystack_path}"
            or "/__tests__/" in f"/{haystack_path}"
            or haystack_filename.endswith("test.java")
            or ".test." in haystack_filename
            or ".spec." in haystack_filename
        )
        is_workflow_file = (
            haystack_path.startswith(".github/workflows/")
            or haystack_path.startswith(".github/actions/")
            or "/.github/workflows/" in haystack_path
            or "/.github/actions/" in haystack_path
        )
        is_contract_file = (
            "openapi" in haystack_path
            or "/apis/" in f"/{haystack_path}"
            or "/paths/" in f"/{haystack_path}"
            or "/schemas/" in f"/{haystack_path}"
        )
        wants_workflow = any(term in query_terms for term in ["workflow", "workflows", "deploy", "deployment", "ci", "cd", "pipeline", "github", "action", "actions"])
        wants_tests = any(term in query_terms for term in ["test", "tests", "testing", "unit", "it", "integration", "spec", "coverage"])
        wants_contract = any(term in query_terms for term in ["api", "apis", "endpoint", "endpoints", "contract", "contracts", "openapi", "schema", "schemas", "path", "paths"])

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
        if is_runtime_source and not wants_contract and not wants_workflow and not wants_tests:
            score += 0.35
            reasons.append("preferred runtime source")
        if is_test_file and not wants_tests:
            score -= 0.45
            reasons.append("down-ranked test file")
        elif is_test_file:
            score += 0.35
            reasons.append("preferred test file")
        if is_workflow_file and not wants_workflow:
            score -= 0.8
            reasons.append("down-ranked workflow file")
        elif is_workflow_file:
            score += 0.45
            reasons.append("preferred workflow file")
        if is_contract_file and wants_contract:
            score += 1.0
            reasons.append("preferred contract file")
        if not reasons:
            reasons.append("selected by keyword search")
        return round(max(score, 0.0), 4), match_type, "; ".join(reasons)
