from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List, Optional


_CYRILLIC_RE = re.compile(r"[\u0400-\u04FF]")
_LATIN_RE = re.compile(r"[A-Za-z]")
_HTTP_ROUTE_RE = re.compile(r"(?<!\w)/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+/?)+")
_QUOTED_LITERAL_RE = re.compile(r"(['\"])(?:\\.|(?!\1).)*\1")
_DOTTED_SYMBOL_RE = re.compile(r"\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+\b")
_CAMEL_SYMBOL_RE = re.compile(r"\b(?:[A-Z][A-Za-z0-9_$]*[A-Z][A-Za-z0-9_$]*|[a-z]+[A-Z][A-Za-z0-9_$]*)\b")
_CONSTANT_RE = re.compile(r"\b[A-Z][A-Z0-9_]{2,}\b")
_MARKDOWN_HEADING_RE = re.compile(r"(?m)^\s{0,3}#{1,6}\s+\S")
_MARKDOWN_TABLE_RE = re.compile(r"(?m)^\s*\|.+\|\s*$")
_MARKDOWN_TABLE_RULE_RE = re.compile(r"(?m)^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$")
_AUDIT_LABEL_RE = re.compile(r"(?im)^\s*(?:\d+\.\s*)?(Data enters|Action|Next step|Returns|Observable result):")
_NUMBERED_STEP_RE = re.compile(r"(?m)^\s*(\d+)\.\s+\S")
_LETTERED_SUBSTEP_RE = re.compile(r"(?im)^\s+[a-zа-яіїєґ]\)\s+\S")
_SPECULATIVE_PROSE_RE = re.compile(r"(?i)\b(likely|probably|maybe|assuming|presumably)\b|default\s+Spring\s+Boot")
_GRAPH_TERMINOLOGY_RE = re.compile(
    r"(?i)(?:"
    r"\b(UNRESOLVED_CALL|nodeRef|transitionRef|boundaryRef|evidenceRefs?|refs?|nodes?|transitions?|boundar(?:y|ies)|scores?|graph\s+node|internal\s+id)\b|"
    r"\bunresolved\s+calls?\b"
    r")"
)


class AnswerLanguageResolver:
    def resolve(self, query_text: str, explicit_language: Optional[str] = None) -> str:
        explicit = str(explicit_language or "").strip().lower()
        if explicit and explicit != "auto":
            return explicit.split("-", 1)[0]
        natural_query = strip_technical_tokens(query_text)
        if _CYRILLIC_RE.search(natural_query):
            return "uk"
        if _LATIN_RE.search(natural_query):
            return "en"
        return "en"


@dataclass(frozen=True)
class HumanAnswerValidationResult:
    valid: bool
    errors: List[str]


class HumanAnswerTextValidator:
    def validate(self, text: str, language: str) -> HumanAnswerValidationResult:
        errors: List[str] = []
        normalized = text.strip()
        if self._contains_markdown(normalized):
            errors.append("Response must be escaped plain text without Markdown markers, headings, tables, bold, or inline-code backticks.")
        if _AUDIT_LABEL_RE.search(normalized):
            errors.append("Response must be a natural numbered walkthrough, not an audit template with Data enters/Action/Next step/Returns/Observable result labels.")
        if _LETTERED_SUBSTEP_RE.search(normalized):
            errors.append("Response must use numbered steps only and must not introduce lettered or bullet substeps.")
        if _GRAPH_TERMINOLOGY_RE.search(normalized):
            errors.append("Response must not expose graph terminology, unresolved edge labels, refs, nodes, transitions, boundaries, scores, or internal IDs.")
        numbered_error = self._numbered_walkthrough_error(normalized)
        if numbered_error:
            errors.append(numbered_error)
        if _SPECULATIVE_PROSE_RE.search(strip_technical_tokens(normalized)):
            errors.append("Response must not infer or speculate about behavior absent from the verified flow facts.")
        language_error = self._language_error(normalized, language)
        if language_error:
            errors.append(language_error)
        return HumanAnswerValidationResult(valid=not errors, errors=errors)

    def _contains_markdown(self, text: str) -> bool:
        if "**" in text or "__" in text or "`" in text:
            return True
        if _MARKDOWN_HEADING_RE.search(text):
            return True
        if _MARKDOWN_TABLE_RE.search(text) or _MARKDOWN_TABLE_RULE_RE.search(text):
            return True
        return False

    def _numbered_walkthrough_error(self, text: str) -> str | None:
        first_line = next((line.strip() for line in text.splitlines() if line.strip()), "")
        matches = [int(match.group(1)) for match in _NUMBERED_STEP_RE.finditer(text)]
        if not first_line.startswith("1."):
            return "Response must be a numbered walkthrough whose first non-empty line starts with 1."
        if len(matches) < 2 or matches[0] != 1 or 2 not in matches:
            return "Response must contain at least steps 1 and 2 as numbered walkthrough lines."
        for line in text.splitlines():
            stripped = line.strip()
            if stripped and _NUMBERED_STEP_RE.match(line) is None:
                return "Response must keep every non-empty line as a numbered walkthrough step, without unnumbered paragraphs."
        return None

    def _language_error(self, text: str, language: str) -> str | None:
        normalized_language = str(language or "").strip().lower().split("-", 1)[0]
        prose = strip_technical_tokens(text)
        cyrillic_count = len(_CYRILLIC_RE.findall(prose))
        latin_count = len(_LATIN_RE.findall(prose))
        if normalized_language == "uk":
            if cyrillic_count < 8:
                return "Response prose must be in Ukrainian for the resolved answer language uk."
            return None
        if normalized_language == "en":
            if latin_count < 8 or cyrillic_count > 5:
                return "Response prose must be in English for the resolved answer language en."
            return None
        return None


def strip_technical_tokens(value: str) -> str:
    text = str(value or "")
    for pattern in (
        _QUOTED_LITERAL_RE,
        _HTTP_ROUTE_RE,
        _DOTTED_SYMBOL_RE,
        _CAMEL_SYMBOL_RE,
        _CONSTANT_RE,
    ):
        text = pattern.sub(" ", text)
    return text
