from __future__ import annotations

import json

import pytest

from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.query_interpretation import (
    QueryPlanningProviderUnavailable,
    QueryPlanningRepairExhausted,
    QueryInterpretationPromptRenderer,
    QueryInterpretationProviderResult,
    QueryInterpretationService,
)


class SequenceQueryInterpretationProvider:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def complete(self, llm_input, validation_errors=None, timeout_seconds=None):
        self.calls.append(
            {
                "llmInput": dict(llm_input),
                "validationErrors": list(validation_errors or []),
                "timeoutSeconds": timeout_seconds,
            }
        )
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        raw = response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)
        return QueryInterpretationProviderResult(raw_text=raw, prompt_char_length=100)


def interpretation_payload(**overrides):
    payload = {
        "detectedLanguage": "uk",
        "responseLanguage": "uk",
        "normalizedQuery": "процес виконання SiteController.createSite",
        "searchQueries": ["як працює SiteController.createSite", "site creation execution flow"],
        "codeIdentifiers": ["SiteController.createSite"],
        "concepts": ["site creation"],
    }
    payload.update(overrides)
    return payload


def test_query_interpreter_resolves_mixed_technical_text_to_ukrainian():
    provider = SequenceQueryInterpretationProvider([interpretation_payload()])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO"))

    assert plan.detected_language == "uk"
    assert plan.response_language == "uk"
    assert plan.effective_intent == "FLOW_EXPLANATION"
    assert plan.code_identifiers == ("SiteController.createSite",)
    assert "explicitIntent" not in provider.calls[0]["llmInput"]


def test_query_interpreter_preserves_planner_detected_language_without_backend_override():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(detectedLanguage="en", responseLanguage="en")
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO"))

    assert plan.detected_language == "en"
    assert plan.response_language == "en"
    assert len(provider.calls) == 1


def test_query_interpreter_accepts_french_response_language_from_planner():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(
            detectedLanguage="fr",
            responseLanguage="fr",
            normalizedQuery="comment fonctionne SiteController.createSite",
            searchQueries=["comment fonctionne SiteController.createSite"],
            concepts=["création de site"],
        )
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="comment fonctionne SiteController.createSite", intent="AUTO"))

    assert plan.detected_language == "fr"
    assert plan.response_language == "fr"
    assert len(provider.calls) == 1


def test_query_interpreter_merges_exact_query_identifiers_when_provider_omits_them():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(codeIdentifiers=[])
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO"))

    assert plan.code_identifiers == ("SiteController.createSite",)
    assert len(provider.calls) == 1


def test_query_interpreter_honors_explicit_german_response_language_override():
    provider = SequenceQueryInterpretationProvider([interpretation_payload(responseLanguage="de")])
    service = QueryInterpretationService(provider)

    plan = service.interpret(
        KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO", answerLanguage="de")
    )

    assert plan.detected_language == "uk"
    assert plan.response_language == "de"
    assert provider.calls[0]["llmInput"]["explicitAnswerLanguage"] == "de"


def test_query_interpreter_repairs_detected_ukrainian_with_english_auto_response_language():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(
            detectedLanguage="uk",
            responseLanguage="en",
            normalizedQuery="створити сайт",
            searchQueries=["створити сайт"],
            codeIdentifiers=[],
            concepts=["створення сайту"],
        ),
        interpretation_payload(
            detectedLanguage="uk",
            responseLanguage="uk",
            normalizedQuery="створити сайт",
            searchQueries=["створити сайт"],
            codeIdentifiers=[],
            concepts=["створення сайту"],
        ),
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="створити сайт", intent="AUTO"))

    assert plan.detected_language == "uk"
    assert plan.response_language == "uk"
    assert len(provider.calls) == 2
    assert any("detectedLanguage uk" in error for error in provider.calls[1]["validationErrors"])


def test_query_interpreter_allows_generic_concepts_without_code_identifier_routing():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(
            detectedLanguage="uk",
            responseLanguage="uk",
            normalizedQuery="створити сайт",
            searchQueries=["створити сайт"],
            codeIdentifiers=[],
            concepts=["HTML", "CSS", "JavaScript"],
        )
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="створити сайт", intent="AUTO"))

    assert plan.code_identifiers == ()
    assert plan.concepts == ("HTML", "CSS", "JavaScript")


def test_query_interpreter_accepts_russian_detected_language_with_ukrainian_response():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(
            detectedLanguage="ru",
            responseLanguage="uk",
            normalizedQuery="як працює контролер",
            searchQueries=["як працює контролер"],
            codeIdentifiers=[],
            concepts=["контролер"],
        )
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="Как работает контроллер", intent="AUTO"))

    assert plan.detected_language == "ru"
    assert plan.response_language == "uk"


def test_query_interpreter_accepts_russian_detected_language_with_english_response():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(
            detectedLanguage="ru",
            responseLanguage="en",
            normalizedQuery="how controller works",
            searchQueries=["how controller works"],
            codeIdentifiers=[],
            concepts=["controller"],
        )
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="Как работает контроллер", intent="AUTO"))

    assert plan.detected_language == "ru"
    assert plan.response_language == "en"


def test_query_interpreter_repairs_forbidden_response_language_once():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(detectedLanguage="ru", responseLanguage="ru"),
        interpretation_payload(detectedLanguage="ru", responseLanguage="uk"),
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="Как работает SiteController.createSite", intent="AUTO"))

    assert plan.detected_language == "ru"
    assert plan.response_language == "uk"
    assert len(provider.calls) == 2
    assert any("forbidden response language" in error for error in provider.calls[1]["validationErrors"])


def test_query_interpreter_repeated_forbidden_response_language_fails_closed():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(detectedLanguage="ru", responseLanguage="ru"),
        interpretation_payload(detectedLanguage="ru", responseLanguage="ru"),
    ])
    service = QueryInterpretationService(provider)

    with pytest.raises(QueryPlanningRepairExhausted):
        service.interpret(KnowledgeQueryRequest(queryText="Как работает SiteController.createSite", intent="AUTO"))

    assert len(provider.calls) == 2
    assert any("forbidden response language" in error for error in provider.calls[1]["validationErrors"])


def test_query_interpreter_repairs_invented_code_identifier_once():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(codeIdentifiers=["InventedController.run"]),
        interpretation_payload(),
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO"))

    assert plan.code_identifiers == ("SiteController.createSite",)
    assert len(provider.calls) == 2
    assert any("exact substring" in error for error in provider.calls[1]["validationErrors"])


def test_query_interpreter_malformed_response_repairs_once_then_fails_closed():
    provider = SequenceQueryInterpretationProvider(["not json", "still not json"])
    service = QueryInterpretationService(provider)

    with pytest.raises(QueryPlanningRepairExhausted):
        service.interpret(KnowledgeQueryRequest(queryText="how does SiteController.createSite work", intent="AUTO"))

    assert len(provider.calls) == 2
    assert any("strict JSON" in error for error in provider.calls[1]["validationErrors"])
    assert len(service.audit_records) == 2


def test_query_interpreter_provider_unavailable_fails_closed():
    provider = SequenceQueryInterpretationProvider([RuntimeError("provider unavailable")])
    service = QueryInterpretationService(provider)

    with pytest.raises(QueryPlanningProviderUnavailable):
        service.interpret(KnowledgeQueryRequest(queryText="Как работает SiteController.createSite", intent="AUTO"))

    assert len(provider.calls) == 1
    assert list(service.audit_records) == []


def test_query_interpreter_prompt_is_generic():
    prompt = QueryInterpretationPromptRenderer().render(
        {
            "queryText": "how does ClassName.methodName work",
            "explicitAnswerLanguage": None,
            "defaultResponseLanguage": "en",
        }
    )

    forbidden = ("SiteController", "CreateSiteImpl", "SiteRepository", "SiteCreatedPayload", "/api/v1/sites", "stsssox")
    assert not any(token in prompt for token in forbidden)
    assert "Return strict JSON only" in prompt
    assert "uk|en" not in prompt
