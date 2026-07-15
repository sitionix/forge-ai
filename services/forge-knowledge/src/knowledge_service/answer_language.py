from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List


_CYRILLIC_RE = re.compile(r"[\u0400-\u04FF]")
_LATIN_RE = re.compile(r"[A-Za-z]")
_CYRILLIC_WORD_RE = re.compile(r"[А-Яа-яЁёІіЇїЄєҐґ]+")
_HTTP_ROUTE_RE = re.compile(r"(?<!\w)/(?:[A-Za-z0-9._~!$&'()*+,;=:@%-]+/?)+")
_QUOTED_LITERAL_RE = re.compile(r"(['\"])(?:\\.|(?!\1).)*\1")
_DOTTED_SYMBOL_RE = re.compile(r"\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+\b")
_CAMEL_SYMBOL_RE = re.compile(r"\b(?:[A-Z][A-Za-z0-9_$]*[A-Z][A-Za-z0-9_$]*|[a-z]+[A-Z][A-Za-z0-9_$]*)\b")
_CONSTANT_RE = re.compile(r"\b[A-Z][A-Z0-9_]{2,}\b")
_SPECULATIVE_PROSE_RE = re.compile(r"(?i)\b(likely|probably|maybe|assuming|presumably)\b|default\s+Spring\s+Boot")
_INTERNAL_REF_RE = re.compile(r"(?i)\b(?:nodeRef|transitionRef|boundaryRef|evidenceRefs?|analysis-graph-[a-z-]+:[a-f0-9]+)\b")
_RUSSIAN_SPECIFIC_RE = re.compile(r"[ыэёъЫЭЁЪ]")
_UKRAINIAN_SPECIFIC_RE = re.compile(r"[іїєґІЇЄҐ]")
_RUSSIAN_MARKERS = {
    "базу",
    "возвращает",
    "выполняется",
    "данные",
    "для",
    "записываться",
    "записываются",
    "запрос",
    "имени",
    "как",
    "контроллер",
    "метод",
    "нужно",
    "обработки",
    "обработчик",
    "ответ",
    "отправить",
    "перед",
    "пользователь",
    "пользователя",
    "получает",
    "после",
    "проверка",
    "приходит",
    "работает",
    "результат",
    "сайта",
    "сайт",
    "система",
    "сохранением",
    "сохраняет",
    "создать",
    "создает",
    "этого",
}
_UKRAINIAN_MARKERS = {
    "відповідь",
    "виконується",
    "дані",
    "записує",
    "запит",
    "контролер",
    "користувач",
    "надходить",
    "обробки",
    "обробник",
    "отримує",
    "перед",
    "передає",
    "перевірка",
    "після",
    "повертає",
    "працює",
    "результат",
    "сайт",
    "сервіс",
    "створення",
    "створює",
}


@dataclass(frozen=True)
class HumanAnswerValidationResult:
    valid: bool
    errors: List[str]


class HumanAnswerTextValidator:
    def validate(self, text: str, language: str) -> HumanAnswerValidationResult:
        errors: List[str] = []
        normalized = text.strip()
        if _INTERNAL_REF_RE.search(normalized):
            errors.append("Response must not expose internal graph refs or analysis ids.")
        if _SPECULATIVE_PROSE_RE.search(strip_technical_tokens(normalized)):
            errors.append("Response must not infer or speculate about behavior absent from the verified flow facts.")
        language_error = self._language_error(normalized, language)
        if language_error:
            errors.append(language_error)
        return HumanAnswerValidationResult(valid=not errors, errors=errors)

    def _language_error(self, text: str, language: str) -> str | None:
        normalized_language = str(language or "").strip().lower().split("-", 1)[0]
        prose = strip_technical_tokens(text)
        if is_russian_prose(prose):
            return "Response prose must not be Russian."
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


def is_russian_prose(value: str) -> bool:
    words = [item.lower().replace("ё", "е") for item in _CYRILLIC_WORD_RE.findall(str(value or ""))]
    if len(words) < 3:
        return False
    prose = " ".join(words)
    russian_specific = len(_RUSSIAN_SPECIFIC_RE.findall(prose))
    ukrainian_specific = len(_UKRAINIAN_SPECIFIC_RE.findall(prose))
    russian_score = sum(1 for word in words if word in _RUSSIAN_MARKERS)
    ukrainian_score = sum(1 for word in words if word in _UKRAINIAN_MARKERS)
    if russian_specific >= 1 and ukrainian_specific == 0 and len(words) >= 3:
        return True
    if russian_score >= 3 and russian_score >= ukrainian_score + 2:
        return True
    if russian_score >= 2 and russian_specific >= 1 and russian_score > ukrainian_score:
        return True
    return False
