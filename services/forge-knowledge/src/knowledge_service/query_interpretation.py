from __future__ import annotations

import hashlib
import json
import re
import time
from collections import deque
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

import httpx

from knowledge_service.config import (
    DEFAULT_GENERATIVE_CONTEXT_TOKENS,
    DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
)
from knowledge_service.generative_runtime import GenerativeProvider, GenerativeRequest, OllamaGenerativeProvider, ResponseMode
from knowledge_service.knowledge_query_schema import KnowledgeQueryIntent, KnowledgeQueryRequest
from knowledge_service.language_policy import (
    is_forbidden_response_language,
    normalize_detected_language,
    normalize_response_language,
)

QUERY_INTERPRETATION_FAILED = "QUERY_INTERPRETATION_FAILED"

_CODE_LIKE_RE = re.compile(r"\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+\b|\b(?:[A-Z][A-Za-z0-9_$]*[A-Z][A-Za-z0-9_$]*|[a-z]+[A-Z][A-Za-z0-9_$]*)\b")
_DEFAULT_MIN_CALL_TIMEOUT_SECONDS = 0.01
_DEADLINE_COMPLETION_GRACE_SECONDS = 0.005

_ALLOWED_KEYS = {
    "detectedLanguage",
    "responseLanguage",
    "normalizedQuery",
    "searchQueries",
    "codeIdentifiers",
    "concepts",
}


@dataclass(frozen=True)
class QueryInterpretationProviderResult:
    raw_text: str
    prompt_char_length: int


@dataclass(frozen=True)
class QueryInterpretation:
    detected_language: str
    response_language: str
    normalized_query: str
    search_queries: tuple[str, ...]
    code_identifiers: tuple[str, ...]
    concepts: tuple[str, ...]


@dataclass(frozen=True)
class QueryRetrievalPlan:
    original_query: str
    normalized_query: str
    search_queries: tuple[str, ...]
    code_identifiers: tuple[str, ...]
    concepts: tuple[str, ...]
    effective_intent: str
    detected_language: str
    response_language: str

    def query_inputs(self) -> list[tuple[str, str]]:
        result: list[tuple[str, str]] = []
        self._append(result, "ORIGINAL_QUERY", self.original_query)
        self._append(result, "NORMALIZED_QUERY", self.normalized_query)
        for query in self.search_queries:
            self._append(result, "SEARCH_QUERY", query)
        for identifier in self.code_identifiers:
            self._append(result, "CODE_IDENTIFIER", identifier)
        return result

    def _append(self, values: list[tuple[str, str]], reason: str, value: str) -> None:
        normalized = str(value or "").strip()
        if not normalized:
            return
        key = normalized.casefold()
        if any(existing.casefold() == key for _, existing in values):
            return
        values.append((reason, normalized))


class QueryInterpretationFailed(Exception):
    pass


class QueryPlanningDeadlineExceeded(QueryInterpretationFailed):
    pass


class QueryPlanningProviderUnavailable(QueryInterpretationFailed):
    pass


class QueryPlanningMalformedResponse(QueryInterpretationFailed):
    pass


class QueryPlanningRepairExhausted(QueryInterpretationFailed):
    pass


class QueryInterpretationContractViolation(QueryPlanningMalformedResponse):
    def __init__(self, errors: Sequence[str]) -> None:
        self.errors = [str(error) for error in errors if str(error).strip()]
        super().__init__("; ".join(self.errors) or "query interpretation violated output contract")


class QueryInterpretationPromptRenderer:
    def render(self, llm_input: Mapping[str, Any], validation_errors: Sequence[str] | None = None) -> str:
        validation_block = ""
        if validation_errors:
            validation_block = "\nPrevious JSON failed validation. Correct these exact issues:\n"
            validation_block += "\n".join(f"- {error}" for error in validation_errors)
            validation_block += "\n"
        context_json = json.dumps(dict(llm_input), ensure_ascii=False, indent=2, sort_keys=True)
        return (
            "Interpret one developer knowledge query for retrieval planning.\n"
            "Return strict JSON only. Do not include prose outside JSON.\n"
            "The JSON shape is exactly: "
            "{\"detectedLanguage\":\"string\",\"responseLanguage\":\"string\","
            "\"normalizedQuery\":\"string\",\"searchQueries\":[\"string\"],"
            "\"codeIdentifiers\":[\"string\"],\"concepts\":[\"string\"]}.\n"
            "Detect the dominant natural-language prose separately from code identifiers.\n"
            "Use ISO 639 language codes for detectedLanguage and responseLanguage. Unresolved detectedLanguage is und.\n"
            "Use detectedLanguage \"und\" only when the query contains no meaningful natural-language prose.\n"
            "responseLanguage must be a valid supported language code and must not be ru.\n"
            "If explicitAnswerLanguage is a language code, responseLanguage must be exactly that code. "
            "If explicitAnswerLanguage is null or auto and detectedLanguage is valid and non-forbidden, responseLanguage must equal detectedLanguage. "
            "If detectedLanguage is und or forbidden, choose an allowed responseLanguage using defaultResponseLanguage when no better language is grounded by the query.\n"
            "normalizedQuery must be one non-empty retrieval phrase.\n"
            "searchQueries may contain at most 4 additive retrieval phrases.\n"
            "codeIdentifiers may contain at most 10 exact code symbols, and every value must be an exact substring of queryText.\n"
            "For natural-language-only queries with no literal code syntax in queryText, codeIdentifiers must be an empty array.\n"
            "concepts may contain at most 10 concise retrieval concepts; do not use them for source ids, graph ids, file paths, routes, or guessed class and method names.\n"
            "Do not return source ids, graph ids, file paths, entrypoints, database identifiers, guessed class names, or final answers.\n"
            "Do not invent code identifiers; preserve exact identifier spelling from queryText.\n"
            f"{validation_block}"
            "BEGIN_QUERY_INTERPRETATION_INPUT_JSON\n"
            f"{context_json}\n"
            "END_QUERY_INTERPRETATION_INPUT_JSON\n"
        )


class QueryInterpretationService:
    def __init__(
        self,
        provider: Any,
        *,
        default_response_language: str = "en",
        request_deadline_seconds: float = DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS,
        min_call_timeout_seconds: float = _DEFAULT_MIN_CALL_TIMEOUT_SECONDS,
        renderer: QueryInterpretationPromptRenderer | None = None,
        provider_name: str | None = None,
        provider_model: str | None = None,
        audit_max_records: int = 200,
    ) -> None:
        self.provider = provider
        self.default_response_language = self._normalize_response_language(default_response_language) or "en"
        self.request_deadline_seconds = max(0.001, float(request_deadline_seconds or DEFAULT_HUMAN_QUERY_REQUEST_DEADLINE_SECONDS))
        self.min_call_timeout_seconds = max(0.001, float(min_call_timeout_seconds or _DEFAULT_MIN_CALL_TIMEOUT_SECONDS))
        self.renderer = renderer or QueryInterpretationPromptRenderer()
        self.provider_name = provider_name
        self.provider_model = provider_model
        self.audit_records: deque[dict[str, Any]] = deque(maxlen=max(0, int(audit_max_records)))

    def interpret(self, request: KnowledgeQueryRequest, *, deadline_at: float | None = None) -> QueryRetrievalPlan:
        if deadline_at is None:
            deadline_at = time.monotonic() + self.request_deadline_seconds
        explicit_language = self._explicit_language(request.answerLanguage)
        llm_input = {
            "queryText": request.queryText,
            "explicitAnswerLanguage": explicit_language,
            "defaultResponseLanguage": self.default_response_language,
        }
        validation_errors: Sequence[str] | None = None
        for attempt_count in (1, 2):
            result = self._complete_with_deadline(
                llm_input,
                deadline_at,
                validation_errors=validation_errors,
                attempt_count=attempt_count,
            )
            try:
                interpretation = self._validate(
                    result.raw_text,
                    request,
                    explicit_language=explicit_language,
                )
                self._record_resolved_languages(interpretation)
                return QueryRetrievalPlan(
                    original_query=request.queryText,
                    normalized_query=interpretation.normalized_query,
                    search_queries=interpretation.search_queries,
                    code_identifiers=interpretation.code_identifiers,
                    concepts=interpretation.concepts,
                    effective_intent=KnowledgeQueryIntent.FLOW_EXPLANATION.value,
                    detected_language=interpretation.detected_language,
                    response_language=interpretation.response_language,
                )
            except QueryInterpretationContractViolation as exc:
                self._record_validation_errors(attempt_count=attempt_count, errors=exc.errors)
                if attempt_count == 1:
                    validation_errors = exc.errors
                    continue
                raise QueryPlanningRepairExhausted("query interpretation repair failed validation") from exc
        raise QueryPlanningRepairExhausted("query interpretation repair failed validation")

    def _complete_with_deadline(
        self,
        llm_input: Mapping[str, Any],
        deadline_at: float,
        *,
        validation_errors: Sequence[str] | None,
        attempt_count: int,
    ) -> QueryInterpretationProviderResult:
        remaining = max(0.0, deadline_at - time.monotonic())
        if remaining <= self.min_call_timeout_seconds:
            raise QueryPlanningDeadlineExceeded("query interpretation deadline exceeded")
        prompt = self.renderer.render(llm_input, validation_errors)
        try:
            result = self.provider.complete(llm_input, validation_errors=validation_errors, timeout_seconds=remaining)
        except (TimeoutError, httpx.TimeoutException) as exc:
            raise QueryPlanningDeadlineExceeded("query interpretation deadline exceeded") from exc
        except Exception as exc:
            raise QueryPlanningProviderUnavailable(str(type(exc).__name__)) from exc
        if time.monotonic() > deadline_at + _DEADLINE_COMPLETION_GRACE_SECONDS:
            raise QueryPlanningDeadlineExceeded("query interpretation deadline exceeded")
        self._record_audit(prompt, result.raw_text, attempt_count=attempt_count, llm_input=llm_input)
        return QueryInterpretationProviderResult(
            raw_text=str(result.raw_text),
            prompt_char_length=int(getattr(result, "prompt_char_length", len(prompt)) or len(prompt)),
        )

    def _validate(
        self,
        raw_text: str,
        request: KnowledgeQueryRequest,
        *,
        explicit_language: str | None,
    ) -> QueryInterpretation:
        errors: list[str] = []
        try:
            payload = json.loads(raw_text)
        except Exception as exc:
            raise QueryInterpretationContractViolation(["Response must be strict JSON."]) from exc
        if not isinstance(payload, dict):
            raise QueryInterpretationContractViolation(["Response JSON root must be an object."])
        extra_keys = [key for key in payload if key not in _ALLOWED_KEYS]
        if extra_keys:
            errors.append(f"Response must not include extra fields: {', '.join(sorted(extra_keys))}.")
        missing = [key for key in _ALLOWED_KEYS if key not in payload]
        if missing:
            errors.append(f"Response missing required fields: {', '.join(sorted(missing))}.")

        raw_response_language = payload.get("responseLanguage")
        detected = self._normalize_detected_language(payload.get("detectedLanguage"))
        response_language = self._normalize_response_language(raw_response_language)
        normalized_query = str(payload.get("normalizedQuery") or "").strip() if isinstance(payload.get("normalizedQuery"), str) else ""

        if not detected:
            errors.append("detectedLanguage must be a language code or und.")
        if is_forbidden_response_language(raw_response_language):
            errors.append("responseLanguage must not be a forbidden response language.")
        if not response_language:
            errors.append("responseLanguage must be a valid non-forbidden language code.")
        if not normalized_query:
            errors.append("normalizedQuery must be one non-empty string.")

        expected_response_language = explicit_language
        if not expected_response_language and detected and detected != "und" and not is_forbidden_response_language(detected):
            expected_response_language = detected
        if response_language and expected_response_language and response_language != expected_response_language:
            if explicit_language:
                errors.append(f"responseLanguage must match explicitAnswerLanguage {expected_response_language}.")
            else:
                errors.append(f"responseLanguage must match detectedLanguage {expected_response_language} when no explicitAnswerLanguage is supplied.")

        search_queries = self._string_list(payload.get("searchQueries"), "searchQueries", 4, errors)
        code_identifiers = self._string_list(payload.get("codeIdentifiers"), "codeIdentifiers", 10, errors)
        code_identifiers = self._merge_exact_query_identifiers(code_identifiers, request.queryText, errors)
        concepts = self._string_list(payload.get("concepts"), "concepts", 10, errors)

        for identifier in code_identifiers:
            if identifier not in request.queryText:
                errors.append(f"codeIdentifiers value {identifier!r} must be an exact substring of queryText.")
        self._validate_no_invented_code_like_values("normalizedQuery", [normalized_query], request.queryText, errors)
        self._validate_no_invented_code_like_values("searchQueries", search_queries, request.queryText, errors)

        if errors:
            raise QueryInterpretationContractViolation(errors)

        return QueryInterpretation(
            detected_language=detected or "und",
            response_language=response_language or self.default_response_language,
            normalized_query=normalized_query,
            search_queries=tuple(search_queries),
            code_identifiers=tuple(code_identifiers),
            concepts=tuple(concepts),
        )

    def _string_list(self, value: Any, field_name: str, max_items: int, errors: list[str]) -> list[str]:
        if not isinstance(value, list):
            errors.append(f"{field_name} must be a list.")
            return []
        if len(value) > max_items:
            errors.append(f"{field_name} must contain at most {max_items} items.")
        result: list[str] = []
        seen: set[str] = set()
        for index, item in enumerate(value):
            if not isinstance(item, str):
                errors.append(f"{field_name}[{index}] must be a string.")
                continue
            normalized = item.strip()
            if not normalized:
                continue
            key = normalized.casefold()
            if key in seen:
                continue
            seen.add(key)
            result.append(normalized)
        if len(result) > max_items:
            errors.append(f"{field_name} must contain at most {max_items} distinct items.")
        return result

    def _validate_no_invented_code_like_values(
        self,
        field_name: str,
        values: Sequence[str],
        query_text: str,
        errors: list[str],
    ) -> None:
        for value in values:
            for match in _CODE_LIKE_RE.finditer(value):
                identifier = match.group(0)
                if identifier not in query_text:
                    errors.append(f"{field_name} contains invented code-like identifier {identifier!r}.")

    def _merge_exact_query_identifiers(self, values: Sequence[str], query_text: str, errors: list[str]) -> list[str]:
        result: list[str] = []
        seen: set[str] = set()
        for value in [*values, *[match.group(0) for match in _CODE_LIKE_RE.finditer(query_text)]]:
            normalized = str(value or "").strip()
            if not normalized:
                continue
            if normalized not in query_text:
                errors.append(f"codeIdentifiers value {normalized!r} must be an exact substring of queryText.")
                continue
            key = normalized.casefold()
            if key in seen:
                continue
            seen.add(key)
            result.append(normalized)
            if len(result) >= 10:
                break
        return result

    def _explicit_language(self, value: str | None) -> str | None:
        normalized = self._normalize_response_language(value, allow_auto=True)
        if not normalized:
            return None
        if normalized == "auto":
            return None
        return normalized

    def _normalize_detected_language(self, value: Any) -> str:
        return normalize_detected_language(value)

    def _normalize_response_language(self, value: Any, *, allow_auto: bool = False) -> str:
        return normalize_response_language(value, allow_auto=allow_auto)

    def _record_audit(self, prompt: str, raw_response: str, *, attempt_count: int, llm_input: Mapping[str, Any]) -> None:
        self.audit_records.append(
            {
                "provider": self._provider_name(),
                "model": self._provider_model(),
                "promptLength": len(prompt),
                "promptHash": self._sha256(prompt),
                "rawResponseLength": len(raw_response),
                "rawResponseHash": self._sha256(raw_response),
                "attemptCount": attempt_count,
                "requestedLanguage": str(llm_input.get("explicitAnswerLanguage") or "AUTO"),
                "resolvedLanguage": "",
                "detectedLanguage": "",
            }
        )

    def _record_resolved_languages(self, interpretation: QueryInterpretation) -> None:
        for record in self.audit_records:
            if not record.get("resolvedLanguage"):
                record["resolvedLanguage"] = interpretation.response_language
            if not record.get("detectedLanguage"):
                record["detectedLanguage"] = interpretation.detected_language

    def _record_validation_errors(self, *, attempt_count: int, errors: Sequence[str]) -> None:
        for record in reversed(self.audit_records):
            if record.get("attemptCount") == attempt_count:
                record["validationErrors"] = [str(error) for error in errors]
                return

    def _provider_name(self) -> str:
        value = self.provider_name or getattr(self.provider, "name", None)
        return str(value or self.provider.__class__.__name__)

    def _provider_model(self) -> str:
        value = self.provider_model or getattr(self.provider, "model", None)
        return str(value or "")

    def _sha256(self, value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()


class ProviderBackedQueryInterpretationClient:
    def __init__(
        self,
        provider: GenerativeProvider,
        model: str,
        timeout_seconds: int,
        context_tokens: int,
        effort_id: str | None = None,
        renderer: QueryInterpretationPromptRenderer | None = None,
    ) -> None:
        self.provider = provider
        self.model = model
        self.effort_id = effort_id
        self.timeout_seconds = timeout_seconds
        self.context_tokens = int(context_tokens or DEFAULT_GENERATIVE_CONTEXT_TOKENS)
        if self.context_tokens < 1024:
            raise ValueError("Query interpretation context_tokens must be at least 1024")
        self.renderer = renderer or QueryInterpretationPromptRenderer()
        self.provider_id = str(getattr(provider, "provider_id", provider.__class__.__name__))
        self.name = self.provider_id

    def complete(
        self,
        llm_input: Mapping[str, Any],
        validation_errors: Sequence[str] | None = None,
        timeout_seconds: float | None = None,
    ) -> QueryInterpretationProviderResult:
        prompt = self.renderer.render(llm_input, validation_errors)
        call_timeout = self._call_timeout(timeout_seconds)
        response = self.provider.generate(
            GenerativeRequest(
                prompt=prompt,
                model_id=self.model,
                effort_id=self.effort_id,
                response_mode=ResponseMode.JSON_OBJECT,
                timeout_seconds=call_timeout,
                context_tokens=self.context_tokens,
            )
        )
        return QueryInterpretationProviderResult(raw_text=response.raw_text, prompt_char_length=response.prompt_char_length)

    def close(self) -> None:
        return None

    def _call_timeout(self, timeout_seconds: float | None) -> float:
        configured = max(0.001, float(self.timeout_seconds or 0.001))
        if timeout_seconds is None:
            return configured
        return max(0.001, min(configured, float(timeout_seconds)))


class LocalOllamaQueryInterpretationClient(ProviderBackedQueryInterpretationClient):
    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: int,
        context_tokens: int,
        http_client: httpx.Client | None = None,
        renderer: QueryInterpretationPromptRenderer | None = None,
    ) -> None:
        self._ollama_provider = OllamaGenerativeProvider(
            base_url,
            timeout_seconds=timeout_seconds,
            sync_client=http_client,
        )
        self._ollama_provider._owns_sync_client = True
        super().__init__(
            self._ollama_provider,
            model,
            timeout_seconds,
            context_tokens,
            effort_id=None,
            renderer=renderer,
        )

    def close(self) -> None:
        self._ollama_provider.close()
