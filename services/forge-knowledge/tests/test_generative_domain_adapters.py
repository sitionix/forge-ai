from __future__ import annotations

import asyncio
import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

import httpx

from knowledge_service.analysis_client import OllamaAnalysisClient, ProviderBackedAnalysisClient
from knowledge_service.analysis_graph_contract import GraphContractProvider, contract_payload
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.errors import KnowledgeError
from knowledge_service.formatter_provider import ProviderBackedEndToEndFormatterClient
from knowledge_service.formatter_protocol import EndToEndFormatterProviderError
from knowledge_service.generative_runtime import GenerativeProviderProtocolError, GenerativeRequest, GenerativeResponse
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.query_interpretation import (
    ProviderBackedQueryInterpretationClient,
    QueryInterpretationService,
    QueryPlanningRepairExhausted,
)
from knowledge_service.target_enrichment import TARGET_INPUT_SCHEMA_VERSION, TARGET_REQUEST_KIND


class SharedFakeGenerativeProvider:
    provider_id = "fake-provider"
    provider_version = "test"

    def __init__(self, responses: list[str]) -> None:
        self.responses = list(responses)
        self.requests: list[GenerativeRequest] = []

    def generate(self, request: GenerativeRequest) -> GenerativeResponse:
        self.requests.append(request)
        raw_text = self.responses.pop(0)
        if isinstance(raw_text, Exception):
            raise raw_text
        return self._response(request, raw_text)

    async def generate_async(self, request: GenerativeRequest) -> GenerativeResponse:
        self.requests.append(request)
        raw_text = self.responses.pop(0)
        if isinstance(raw_text, Exception):
            raise raw_text
        return self._response(request, raw_text)

    def close(self) -> None:
        return None

    async def aclose(self) -> None:
        return None

    def _response(self, request: GenerativeRequest, raw_text: str) -> GenerativeResponse:
        return GenerativeResponse(
            raw_text=raw_text,
            provider_id=self.provider_id,
            provider_version=self.provider_version,
            model_id=request.model_id,
            duration_ms=2.0,
            prompt_char_length=len(request.prompt),
            prompt_hash=_sha256(request.prompt),
            response_char_length=len(raw_text),
            response_hash=_sha256(raw_text),
            provider_metadata={"done": True},
        )


def test_analysis_adapter_uses_fake_provider_and_preserves_domain_error_mapping():
    provider = SharedFakeGenerativeProvider(["not json"])
    client = ProviderBackedAnalysisClient(provider, "fake-model", 7, 32768)

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(client.analyze(_analysis_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_INVALID_JSON"
    assert provider.requests[0].model_id == "fake-model"
    assert provider.requests[0].context_tokens == 32768
    assert "BEGIN_LLM_INPUT_JSON" in provider.requests[0].prompt


def test_analysis_adapter_maps_blank_provider_response_to_empty_response_error():
    provider = SharedFakeGenerativeProvider([""])
    client = ProviderBackedAnalysisClient(provider, "fake-model", 7, 32768)

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(client.analyze(_analysis_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_EMPTY_RESPONSE"
    assert exc.value.message == "AI analyzer returned no response text"


def test_analysis_protocol_error_message_is_provider_neutral_with_identity_details():
    provider = SharedFakeGenerativeProvider(
        [GenerativeProviderProtocolError("codex envelope failed", provider_id="codex-test", response_text="bad")]
    )
    provider.provider_id = "codex-test"
    provider.provider_version = "2026-07"
    client = ProviderBackedAnalysisClient(provider, "codex-model", 7, 32768)

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(client.analyze(_analysis_payload(), 1))

    asyncio.run(client.aclose())
    assert exc.value.code == "ANALYSIS_AI_TRANSPORT_ERROR"
    assert "Ollama" not in exc.value.message
    assert exc.value.message == "AI analyzer provider returned an invalid response envelope"
    assert exc.value.details["providerId"] == "codex-test"
    assert exc.value.details["providerVersion"] == "2026-07"
    assert exc.value.details["modelId"] == "codex-model"
    assert exc.value.details["providerErrorClass"] == "GenerativeProviderProtocolError"


def test_ollama_analysis_compatibility_wrapper_preserves_protocol_error_code():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="{")

    client = OllamaAnalysisClient(
        "http://localhost:11434",
        "ollama-model",
        7,
        32768,
        http_client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )

    with pytest.raises(KnowledgeError) as exc:
        asyncio.run(client.analyze(_analysis_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_TRANSPORT_ERROR"
    assert "Ollama" not in exc.value.message


def test_query_adapter_repair_prompt_and_provider_identity_are_retained():
    first = {
        "detectedLanguage": "en",
        "responseLanguage": "ru",
        "normalizedQuery": "Unit.run",
        "searchQueries": [],
        "codeIdentifiers": ["Unit.run"],
        "concepts": [],
    }
    second = {
        "detectedLanguage": "en",
        "responseLanguage": "en",
        "normalizedQuery": "Unit.run",
        "searchQueries": ["Unit.run"],
        "codeIdentifiers": ["Unit.run"],
        "concepts": ["unit execution"],
    }
    provider = SharedFakeGenerativeProvider([json.dumps(first), json.dumps(second)])
    adapter = ProviderBackedQueryInterpretationClient(provider, "fake-model", 30, 4096)
    service = QueryInterpretationService(adapter)

    plan = service.interpret(KnowledgeQueryRequest(queryText="Explain Unit.run", answerLanguage="AUTO"))

    assert plan.response_language == "en"
    assert len(provider.requests) == 2
    assert "Previous JSON failed validation" in provider.requests[1].prompt
    assert service.audit_records[0]["provider"] == "fake-provider"
    assert service.audit_records[0]["model"] == "fake-model"


def test_query_adapter_repairs_blank_first_response_and_succeeds_on_second_attempt():
    provider = SharedFakeGenerativeProvider(
        [
            "",
            json.dumps(
                {
                    "detectedLanguage": "en",
                    "responseLanguage": "en",
                    "normalizedQuery": "Unit.run",
                    "searchQueries": ["Unit.run"],
                    "codeIdentifiers": ["Unit.run"],
                    "concepts": ["unit execution"],
                }
            ),
        ]
    )
    adapter = ProviderBackedQueryInterpretationClient(provider, "fake-model", 30, 4096)
    service = QueryInterpretationService(adapter)

    plan = service.interpret(KnowledgeQueryRequest(queryText="Explain Unit.run", answerLanguage="AUTO"))

    assert plan.normalized_query == "Unit.run"
    assert len(provider.requests) == 2
    assert "Previous JSON failed validation" in provider.requests[1].prompt
    assert any("strict JSON" in error for error in service.audit_records[0]["validationErrors"])


def test_query_adapter_two_blank_responses_end_as_repair_exhausted():
    provider = SharedFakeGenerativeProvider(["", "   "])
    adapter = ProviderBackedQueryInterpretationClient(provider, "fake-model", 30, 4096)
    service = QueryInterpretationService(adapter)

    with pytest.raises(QueryPlanningRepairExhausted):
        service.interpret(KnowledgeQueryRequest(queryText="Explain Unit.run", answerLanguage="AUTO"))

    assert len(provider.requests) == 2
    assert "Previous JSON failed validation" in provider.requests[1].prompt


def test_formatter_adapter_uses_fake_provider_temperature_zero_and_repair_prompt():
    provider = SharedFakeGenerativeProvider([json.dumps({"clauses": []})])
    adapter = ProviderBackedEndToEndFormatterClient(provider, "fake-model", 30)

    result = adapter.generate(
        {"responseLanguage": "en", "clauses": []},
        deadline_at=9999999999.0,
        cancel_event=None,
        validation_errors=("missing placeholder",),
    )

    assert result.raw_text == '{"clauses": []}'
    assert result.provider_name == "fake-provider"
    assert result.provider_model == "fake-model"
    assert provider.requests[0].temperature == 0
    assert provider.requests[0].context_tokens is None
    assert "Previous JSON failed validation" in provider.requests[0].prompt


def test_formatter_adapter_maps_blank_provider_response_to_existing_empty_response_error():
    provider = SharedFakeGenerativeProvider([""])
    adapter = ProviderBackedEndToEndFormatterClient(provider, "fake-model", 30)

    with pytest.raises(EndToEndFormatterProviderError) as exc:
        adapter.generate(
            {"responseLanguage": "en", "clauses": []},
            deadline_at=9999999999.0,
            cancel_event=None,
        )

    assert str(exc.value) == "canonical formatter provider returned an empty response"


def _analysis_payload() -> dict[str, Any]:
    source_id = "edge-gateway"
    relative_path = "src/main/java/example/ObjectHandler.java"
    content = "public class ObjectHandler {}"
    policy_path = Path(__file__).resolve().parents[3] / "config" / "knowledge" / "analysis-policy.yaml"
    contract = GraphContractProvider(policy=load_analysis_policy(policy_path)).resolve(relative_path, content)
    stable_key = f"{source_id}|{relative_path}|FILE"
    return {
        "sourceId": source_id,
        "relativePath": relative_path,
        "targetRef": "F1",
        "targetKind": "FILE",
        "requestKind": TARGET_REQUEST_KIND,
        "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
        "llmInput": {
            "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
            "requestKind": TARGET_REQUEST_KIND,
            "file": {
                "sourceId": source_id,
                "relativePath": relative_path,
                "language": "java",
                "format": "java",
                "lineCount": 1,
                "contentLines": [{"line": 1, "text": content}],
            },
            "targetAnchor": {
                "kind": "FILE",
                "name": "ObjectHandler.java",
                "qualifiedName": None,
                "lineStart": 1,
                "lineEnd": 1,
            },
            "contextAnchors": [],
            "allowedValues": {
                "claimKind": list(contract.allowed_claim_kinds),
            },
            "responseShape": {"claims": []},
        },
        "_refToStableKey": {"F1": stable_key},
        "_stableKeyToRef": {stable_key: "F1"},
        "_refToKind": {"F1": "FILE"},
        "analysisPolicy": contract_payload(contract),
    }


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
