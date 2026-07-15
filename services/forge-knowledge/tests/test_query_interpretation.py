from __future__ import annotations

import json

import pytest

from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.query_interpretation import (
    QueryInterpretationFailed,
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


def test_query_interpreter_merges_exact_query_identifiers_when_provider_omits_them():
    provider = SequenceQueryInterpretationProvider([
        interpretation_payload(codeIdentifiers=[])
    ])
    service = QueryInterpretationService(provider)

    plan = service.interpret(KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO"))

    assert plan.code_identifiers == ("SiteController.createSite",)
    assert len(provider.calls) == 1


def test_query_interpreter_honors_explicit_response_language_override():
    provider = SequenceQueryInterpretationProvider([interpretation_payload(responseLanguage="en")])
    service = QueryInterpretationService(provider)

    plan = service.interpret(
        KnowledgeQueryRequest(queryText="як працює SiteController.createSite", intent="AUTO", answerLanguage="en")
    )

    assert plan.detected_language == "uk"
    assert plan.response_language == "en"
    assert provider.calls[0]["llmInput"]["explicitAnswerLanguage"] == "en"


def test_query_interpreter_accepts_russian_detected_language_only_with_ukrainian_response():
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


def test_query_interpreter_failure_does_not_silently_continue_with_bad_json():
    provider = SequenceQueryInterpretationProvider(["not json", "still not json"])
    service = QueryInterpretationService(provider)

    with pytest.raises(QueryInterpretationFailed):
        service.interpret(KnowledgeQueryRequest(queryText="how is a site created", intent="AUTO"))

    assert len(provider.calls) == 2


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
