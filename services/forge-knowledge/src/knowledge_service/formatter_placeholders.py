from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Mapping, Sequence

from knowledge_service.canonical_narration_contract import CanonicalNarrationClause

_PLACEHOLDER_RE = re.compile(r"\{\{ref:([^{}]+)\}\}")


@dataclass(frozen=True)
class PlaceholderValidationResult:
    errors: tuple[str, ...]
    rendered_text: str
    placeholder_refs: tuple[str, ...]


def validate_clause_placeholders(
    text_template: str,
    referenced_canonical_refs: Sequence[str],
    clause: CanonicalNarrationClause,
) -> PlaceholderValidationResult:
    errors: list[str] = []
    placeholders = tuple(_PLACEHOLDER_RE.findall(text_template))
    referenced = tuple(str(item) for item in referenced_canonical_refs)
    allowed = set(clause.allowed_canonical_refs)
    placeholder_set = set(placeholders)
    referenced_set = set(referenced)
    unknown_placeholders = sorted(ref for ref in placeholder_set if ref not in allowed)
    if unknown_placeholders:
        errors.append(f"formatter clause {clause.clause_ref} contains unknown placeholders: {unknown_placeholders}")
    missing_placeholders = sorted(ref for ref in referenced_set if ref not in placeholder_set)
    if missing_placeholders:
        errors.append(f"formatter clause {clause.clause_ref} declares refs without placeholders: {missing_placeholders}")
    undeclared_placeholders = sorted(ref for ref in placeholder_set if ref not in referenced_set)
    if undeclared_placeholders:
        errors.append(f"formatter clause {clause.clause_ref} contains undeclared placeholders: {undeclared_placeholders}")
    rendered = render_placeholders(text_template, clause.display_values)
    if not rendered.strip():
        errors.append(f"formatter clause {clause.clause_ref} rendered text must be non-empty")
    return PlaceholderValidationResult(errors=tuple(errors), rendered_text=rendered.strip(), placeholder_refs=placeholders)


def render_placeholders(text_template: str, display_values: Mapping[str, str]) -> str:
    return _PLACEHOLDER_RE.sub(lambda match: display_values.get(match.group(1), _canonical_ref_display(match.group(1))), text_template)


def _canonical_ref_display(ref: str) -> str:
    text = str(ref or "").strip()
    if not text:
        return ""
    if ":" not in text:
        return text
    return text.rsplit(":", 1)[-1]
