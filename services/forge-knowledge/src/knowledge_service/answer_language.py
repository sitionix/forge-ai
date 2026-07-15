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
_RUSSIAN_FUNCTION_WORDS = {
    "и",
    "из",
    "к",
    "как",
    "после",
    "при",
    "с",
    "это",
    "этого",
    "этот",
    "затем",
}
_UKRAINIAN_FUNCTION_WORDS = {
    "та",
    "й",
    "як",
    "після",
    "потім",
    "через",
    "його",
}
_RUSSIAN_EXACT_MARKERS = {
    "база",
    "базе",
    "базу",
    "данные",
    "дальше",
    "запрос",
    "значение",
    "код",
    "контроллер",
    "метод",
    "нужно",
    "обработчик",
    "объект",
    "операции",
    "параметров",
    "передается",
    "приложение",
    "процесс",
    "результат",
    "сайт",
    "сайта",
    "сервис",
    "система",
    "сообщение",
    "создает",
    "ответ",
    "пользователь",
    "пользователя",
}
_UKRAINIAN_EXACT_MARKERS = {
    "бере",
    "дані",
    "далі",
    "додаток",
    "запит",
    "значення",
    "код",
    "контролер",
    "маршрут",
    "обробник",
    "потік",
    "результат",
    "сайт",
    "сервіс",
    "статус",
}
_RUSSIAN_STEMS = (
    "возвращ",
    "выполн",
    "вызыва",
    "записыва",
    "начина",
    "обработ",
    "обраща",
    "операц",
    "отправ",
    "переда",
    "получ",
    "пользовател",
    "провер",
    "принима",
    "приход",
    "работа",
    "сохраня",
    "созда",
    "формир",
)
_UKRAINIAN_STEMS = (
    "виклика",
    "викон",
    "запис",
    "має",
    "надход",
    "оброб",
    "отриму",
    "переда",
    "перевір",
    "поверта",
    "працю",
    "прийма",
    "проход",
    "створ",
)


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
    russian_score = _language_marker_score(
        words,
        exact_markers=_RUSSIAN_EXACT_MARKERS,
        stems=_RUSSIAN_STEMS,
        function_words=_RUSSIAN_FUNCTION_WORDS,
    ) + (russian_specific * 2)
    ukrainian_score = _language_marker_score(
        words,
        exact_markers=_UKRAINIAN_EXACT_MARKERS,
        stems=_UKRAINIAN_STEMS,
        function_words=_UKRAINIAN_FUNCTION_WORDS,
    ) + (ukrainian_specific * 2)
    if ukrainian_specific >= 2 and ukrainian_score >= russian_score:
        return False
    if russian_specific >= 1 and ukrainian_specific == 0:
        return True
    if russian_score >= 4 and russian_score >= ukrainian_score + 2:
        return True
    if russian_score >= 3 and russian_specific >= 1 and russian_score > ukrainian_score:
        return True
    if ukrainian_specific == 0 and russian_score >= 3 and russian_score >= ukrainian_score + 2:
        return True
    return False


def _language_marker_score(words: List[str], *, exact_markers: set[str], stems: tuple[str, ...], function_words: set[str]) -> int:
    score = 0
    for word in words:
        if word in exact_markers:
            score += 2
        if word in function_words:
            score += 1
        if any(word.startswith(stem) for stem in stems):
            score += 1
    return score
