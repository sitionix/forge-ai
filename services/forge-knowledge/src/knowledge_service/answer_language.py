from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List

from knowledge_service.language_policy import is_forbidden_response_language, normalize_detected_language, normalize_response_language

from langdetect import DetectorFactory, LangDetectException, detect_langs


DetectorFactory.seed = 0


_CODE_SPAN_RE = re.compile(r"`[^`]*`")
_HTTP_ROUTE_RE = re.compile(r"(?<!\w)/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+/?)+")
_QUOTED_LITERAL_RE = re.compile(r"(['\"])(?:\\.|(?!\1).)*\1")
_DOTTED_SYMBOL_RE = re.compile(r"\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+\b")
_CAMEL_SYMBOL_RE = re.compile(r"\b(?:[A-Z][A-Za-z0-9_$]*[A-Z][A-Za-z0-9_$]*|[a-z]+[A-Z][A-Za-z0-9_$]*)\b")
_CONSTANT_RE = re.compile(r"\b[A-Z][A-Z0-9_]{2,}\b")
_TECH_TOPIC_RE = re.compile(r"\b[A-Za-z0-9_.-]+\.[A-Za-z0-9_.-]+\b")
_PROSE_WORD_RE = re.compile(r"[^\W\d_]+(?:['-][^\W\d_]+)?", re.UNICODE)
_INTERNAL_REF_RE = re.compile(r"(?i)\b(?:nodeRef|transitionRef|boundaryRef|evidenceRefs?|analysis-graph-[a-z-]+:[a-f0-9]+)\b")
_MIN_PROSE_WORDS = 3
_MIN_PROSE_LETTERS = 18
_MIN_DETECTION_PROBABILITY = 0.55
_UNDETERMINED_LANGUAGE_ERROR = (
    "Response prose language could not be determined; rewrite with sufficient prose in the requested response language."
)


@dataclass(frozen=True)
class HumanAnswerValidationResult:
    valid: bool
    errors: List[str]


class HumanAnswerLanguageDetectorUnavailable(RuntimeError):
    pass


class HumanAnswerTextValidator:
    def validate(self, text: str, language: str) -> HumanAnswerValidationResult:
        errors: List[str] = []
        normalized = text.strip()
        if _INTERNAL_REF_RE.search(normalized):
            errors.append("Response must not expose internal graph refs or analysis ids.")
        language_error = self._language_error(normalized, language)
        if language_error:
            errors.append(language_error)
        return HumanAnswerValidationResult(valid=not errors, errors=errors)

    def _language_error(self, text: str, language: str) -> str | None:
        expected_language = normalize_response_language(language)
        if not expected_language:
            return "Resolved response language must be a valid non-forbidden language code."
        try:
            detected_language = detect_dominant_prose_language(text)
        except HumanAnswerLanguageDetectorUnavailable:
            return "Response prose language validator is unavailable."
        if not detected_language:
            return _UNDETERMINED_LANGUAGE_ERROR
        if is_forbidden_response_language(detected_language):
            return f"Detected response prose language {detected_language} is not allowed."
        if detected_language != expected_language:
            return f"Response prose must be in {expected_language}; detected dominant prose language {detected_language}."
        return None


def strip_technical_tokens(value: str) -> str:
    text = str(value or "")
    for pattern in (
        _CODE_SPAN_RE,
        _QUOTED_LITERAL_RE,
        _HTTP_ROUTE_RE,
        _DOTTED_SYMBOL_RE,
        _CAMEL_SYMBOL_RE,
        _CONSTANT_RE,
        _TECH_TOPIC_RE,
    ):
        text = pattern.sub(" ", text)
    return text


def detect_dominant_prose_language(value: str) -> str:
    if detect_langs is None:
        raise HumanAnswerLanguageDetectorUnavailable("language detector dependency is unavailable")
    prose = _language_detection_text(strip_technical_tokens(value))
    words = _PROSE_WORD_RE.findall(prose)
    if len(words) < _MIN_PROSE_WORDS:
        return ""
    if sum(1 for char in prose if char.isalpha()) < _MIN_PROSE_LETTERS:
        return ""
    try:
        candidates = detect_langs(prose)
    except LangDetectException:
        return ""
    if not candidates:
        return ""
    best = candidates[0]
    if float(getattr(best, "prob", 0.0) or 0.0) < _MIN_DETECTION_PROBABILITY:
        return ""
    return normalize_detected_language(getattr(best, "lang", ""))


def _language_detection_text(value: str) -> str:
    words = _PROSE_WORD_RE.findall(str(value or ""))
    return " ".join(words)
