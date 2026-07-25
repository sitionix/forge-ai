from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any, Dict, Optional

import httpx
import pytest

from knowledge_service.analysis_client import OllamaAnalysisClient
from knowledge_service.analysis_graph_contract import GraphContractProvider, contract_payload
from knowledge_service.analysis_policy_loader import load_analysis_policy
from knowledge_service.analysis_runtime_events import AnalysisRuntimeContext, analysis_runtime_context, emit_runtime_event
from knowledge_service.analysis_schema import AnalysisBuildRequest
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.target_enrichment import TARGET_INPUT_SCHEMA_VERSION, TARGET_REQUEST_KIND
from test_analysis import SupervisorHarness, app_config_with_retries, build_inventory, wait_job


JOB_ID = "runtime-diagnostics-job"
SOURCE_ID = "edge-gateway"
RELATIVE_PATH = "src/main/java/example/ObjectHandler.java"
CONTENT_HASH = "content-hash"
POLICY_PATH = Path(__file__).resolve().parents[3] / "config" / "knowledge" / "analysis-policy.yaml"


class FakeOllamaResponse:
    def __init__(self, body: Dict[str, Any]):
        self._body = body
        self.text = json.dumps(body)

    def json(self) -> Dict[str, Any]:
        return self._body

    def raise_for_status(self) -> None:
        return None


class FakeAsyncClient:
    def __init__(self, outcome: FakeOllamaResponse | Exception):
        self.outcome = outcome
        self.requests: list[Dict[str, Any]] = []

    async def post(self, url: str, json: Dict[str, Any]) -> FakeOllamaResponse:
        self.requests.append({"url": url, "json": json})
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome

    async def aclose(self) -> None:
        return None


class RuntimeEventFailingAnalyzer:
    name = "runtime-event-fake"
    version = "1"

    def analyze(
        self,
        payload: Dict[str, Any],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult:
        emit_runtime_event(
            stage="LLM_REQUEST",
            event_type="PROVIDER_REQUEST",
            status="STARTED",
            metadata={
                "provider": "fake",
                "model": "fake-model",
                "requestTimeoutSeconds": 5,
                "promptCharLength": 123,
                "promptHash": "fake-prompt-hash",
            },
        )
        emit_runtime_event(
            stage="LLM_RESPONSE",
            event_type="PROVIDER_RESPONSE",
            status="FAILED",
            error_code="ANALYSIS_AI_INVALID_JSON",
            error_message="fake invalid JSON",
            metadata={
                "provider": "fake",
                "model": "fake-model",
                "responseCharLength": 1,
                "responsePreviewHead": "{",
                "responsePreviewTail": "{",
                "responseTruncated": True,
            },
        )
        raise KnowledgeError(
            "ANALYSIS_AI_INVALID_JSON",
            "fake invalid JSON",
            raw_preview="{",
            error_details=[{"errorType": "JSON_PARSE_ERROR", "responseTruncated": True}],
        )


def test_ollama_success_persists_llm_request_and_response_diagnostics(tmp_path):
    store = _runtime_store(tmp_path)
    client = _client_with_response(
        {
            "response": _valid_enrichment_json(),
            "done": True,
            "done_reason": "stop",
            "total_duration": 123456,
            "load_duration": 1000,
            "prompt_eval_count": 41,
            "prompt_eval_duration": 2000,
            "eval_count": 17,
            "eval_duration": 3000,
        }
    )

    _run_with_runtime_context(store, client.analyze(_payload(), 1))

    events = store.runtime_events(job_id=JOB_ID, relative_path=RELATIVE_PATH)["events"]
    assert [event["stage"] for event in events] == ["LLM_REQUEST", "LLM_RESPONSE"]
    request, response = events
    assert request["status"] == "STARTED"
    assert request["attempt"] == 1
    assert request["metadata"]["provider"] == "ollama"
    assert request["metadata"]["model"] == "diagnostic-model"
    assert request["metadata"]["requestTimeoutSeconds"] == 7
    assert request["metadata"]["numCtx"] == 32768
    assert request["metadata"]["promptCharLength"] > 0
    assert request["metadata"]["promptLineCount"] > 0
    assert len(request["metadata"]["promptHash"]) == 64
    assert response["status"] == "COMPLETED"
    assert response["durationMs"] is not None
    assert response["metadata"]["responseCharLength"] == len(_valid_enrichment_json())
    assert response["metadata"]["responsePreviewHead"] == _valid_enrichment_json()
    assert response["metadata"]["responsePreviewTail"] == _valid_enrichment_json()
    assert response["metadata"]["responseTruncated"] is False
    assert len(response["metadata"]["responseHash"]) == 64
    assert response["metadata"]["providerResponseMetadata"]["done_reason"] == "stop"
    assert response["metadata"]["providerResponseMetadata"]["prompt_eval_count"] == 41
    assert response["metadata"]["providerResponseMetadata"]["eval_count"] == 17


def test_ollama_timeout_persists_failed_runtime_diagnostic(tmp_path):
    store = _runtime_store(tmp_path)
    client = OllamaAnalysisClient("http://127.0.0.1:11434", "diagnostic-model", 9, 32768)
    client._client = FakeAsyncClient(httpx.TimeoutException("timed out"))  # type: ignore[assignment]

    with pytest.raises(KnowledgeError) as exc:
        _run_with_runtime_context(store, client.analyze(_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_TIMEOUT"
    events = store.runtime_events(job_id=JOB_ID, relative_path=RELATIVE_PATH)["events"]
    assert [event["stage"] for event in events] == ["LLM_REQUEST", "LLM_RESPONSE"]
    failure = events[1]
    assert failure["status"] == "FAILED"
    assert failure["errorCode"] == "ANALYSIS_AI_TIMEOUT"
    assert failure["errorMessage"] == "AI analyzer request timed out"
    assert failure["attempt"] == 1
    assert failure["metadata"]["requestTimeoutSeconds"] == 9
    assert failure["metadata"]["model"] == "diagnostic-model"


def test_parser_failure_persists_response_and_parse_diagnostics(tmp_path):
    store = _runtime_store(tmp_path)
    client = _client_with_response({"response": "{", "done": True, "done_reason": "length"})

    with pytest.raises(KnowledgeError) as exc:
        _run_with_runtime_context(store, client.analyze(_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_INVALID_JSON"
    events = store.runtime_events(job_id=JOB_ID, relative_path=RELATIVE_PATH)["events"]
    assert [event["stage"] for event in events] == ["LLM_REQUEST", "LLM_RESPONSE", "LLM_PARSE"]
    response = events[1]
    parser_failure = events[2]
    assert response["status"] == "COMPLETED"
    assert response["metadata"]["responsePreviewHead"] == "{"
    assert parser_failure["status"] == "FAILED"
    assert parser_failure["eventType"] == "PARSER_FAILURE"
    assert parser_failure["errorCode"] == "ANALYSIS_AI_INVALID_JSON"
    assert parser_failure["metadata"]["model"] == "diagnostic-model"
    assert parser_failure["metadata"]["responsePreviewHead"] == "{"
    assert parser_failure["metadata"]["responsePreviewTail"] == "{"
    assert parser_failure["metadata"]["responseTruncated"] is False
    assert parser_failure["metadata"]["parserErrorDetails"][0]["errorType"] == "JSON_PARSE_ERROR"
    assert parser_failure["metadata"]["validationReport"]["errorType"] == "TARGET_RESPONSE_JSON_PARSE_FAILED"
    assert parser_failure["metadata"]["validationErrors"][0]["code"] == "JSON_PARSE_ERROR"


def test_parser_failure_persists_structured_validation_report(tmp_path):
    store = _runtime_store(tmp_path)
    invalid_json = json.dumps(
        {
            "claims": [
                {
                    "claimKind": "RESPONSIBILITY",
                    "summary": "Uses evidence outside the file.",
                    "evidence": [{"lineStart": 2, "lineEnd": 2}],
                }
            ]
        }
    )
    client = _client_with_response({"response": invalid_json, "done": True})

    with pytest.raises(KnowledgeError) as exc:
        _run_with_runtime_context(store, client.analyze(_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_SCHEMA_INVALID"
    assert exc.value.details["validation_report"]["validationErrors"][0]["code"] == "EVIDENCE_RANGE_OUTSIDE_FILE"
    events = store.runtime_events(job_id=JOB_ID, relative_path=RELATIVE_PATH)["events"]
    assert [event["stage"] for event in events] == ["LLM_REQUEST", "LLM_RESPONSE", "LLM_PARSE"]
    response = events[1]
    parser_failure = events[2]
    assert len(response["metadata"]["responseHash"]) == 64
    assert parser_failure["metadata"]["responseHash"] == response["metadata"]["responseHash"]
    assert parser_failure["metadata"]["validationReport"]["errorType"] == "TARGET_RESPONSE_VALIDATION_FAILED"
    assert parser_failure["metadata"]["validationReport"]["targetRef"] == "F1"
    assert parser_failure["metadata"]["validationReport"]["targetKind"] == "FILE"
    assert parser_failure["metadata"]["validationReport"]["targetRange"] == {"lineStart": 1, "lineEnd": 1}
    validation_error = parser_failure["metadata"]["validationErrors"][0]
    assert validation_error["code"] == "EVIDENCE_RANGE_OUTSIDE_FILE"
    assert validation_error["jsonPath"] == "$.claims[0].evidence[0]"
    assert validation_error["actual"] == {"lineStart": 2, "lineEnd": 2}
    assert validation_error["evidenceRange"] == {"lineStart": 2, "lineEnd": 2}


def test_long_response_preview_is_bounded(tmp_path):
    store = _runtime_store(tmp_path)
    long_response = _valid_enrichment_json() + ("x" * 5000)
    client = _client_with_response({"response": long_response, "done": True})

    with pytest.raises(KnowledgeError) as exc:
        _run_with_runtime_context(store, client.analyze(_payload(), 1))

    assert exc.value.code == "ANALYSIS_AI_INVALID_JSON"
    events = store.runtime_events(job_id=JOB_ID, relative_path=RELATIVE_PATH)["events"]
    assert [event["stage"] for event in events] == ["LLM_REQUEST", "LLM_RESPONSE", "LLM_PARSE"]
    response = events[1]
    parser_failure = events[2]
    assert response["metadata"]["responseCharLength"] == len(long_response)
    assert len(response["metadata"]["responsePreviewHead"]) <= 2000
    assert len(response["metadata"]["responsePreviewTail"]) <= 2000
    assert response["metadata"]["responseTruncated"] is True
    assert response["metadata"]["maxPreviewChars"] == 2000
    assert response["metadata"]["responsePreviewHead"] != long_response
    assert response["metadata"]["responsePreviewTail"] != long_response
    assert parser_failure["status"] == "FAILED"
    assert parser_failure["errorCode"] == "ANALYSIS_AI_INVALID_JSON"
    assert parser_failure["metadata"]["responseTruncated"] is True
    assert parser_failure["metadata"]["parserErrorDetails"][0]["errorType"] == "JSON_PARSE_ERROR"


def test_runtime_diagnostics_survive_failed_file(tmp_path):
    store, _, _ = build_inventory(tmp_path)
    runner = SupervisorHarness(store, app_config_with_retries(tmp_path, 1))
    job_id = runner.start(AnalysisBuildRequest(), RuntimeEventFailingAnalyzer())["jobId"]
    final = wait_job(store, job_id)

    assert final["status"] == "COMPLETED"
    assert final["failedFiles"] == 1
    events = AnalysisStore(store.db_path).runtime_events(job_id=job_id, relative_path=RELATIVE_PATH)["events"]
    assert [event["stage"] for event in events] == ["LLM_REQUEST", "LLM_RESPONSE"]
    assert events[0]["attempt"] == 1
    assert events[0]["sourceId"] == SOURCE_ID
    assert events[0]["inventoryFileId"] == 1
    assert events[1]["status"] == "FAILED"
    assert events[1]["errorCode"] == "ANALYSIS_AI_INVALID_JSON"


def _runtime_store(tmp_path) -> AnalysisStore:
    store = AnalysisStore(tmp_path / "knowledge.sqlite")
    store.create_job(
        {
            "jobId": JOB_ID,
            "mode": "FULL",
            "status": "RUNNING",
            "startedAt": None,
            "completedAt": None,
            "sourceCount": 1,
            "fileCount": 1,
            "processedFileCount": 0,
            "failedFileCount": 0,
            "currentSourceId": SOURCE_ID,
            "currentRelativePath": RELATIVE_PATH,
            "sourceIds": [SOURCE_ID],
            "lastProgressAt": None,
            "diagnostics": [],
        }
    )
    return store


def _client_with_response(raw: Dict[str, Any]) -> OllamaAnalysisClient:
    client = OllamaAnalysisClient("http://127.0.0.1:11434", "diagnostic-model", 7, 32768)
    client._client = FakeAsyncClient(FakeOllamaResponse(raw))  # type: ignore[assignment]
    return client


def _run_with_runtime_context(store: AnalysisStore, awaitable):
    context = AnalysisRuntimeContext(
        job_id=JOB_ID,
        source_id=SOURCE_ID,
        inventory_file_id=1,
        analysis_file_id=1,
        relative_path=RELATIVE_PATH,
        content_hash=CONTENT_HASH,
        attempt=1,
        recorder=store.record_runtime_event,
    )
    with analysis_runtime_context(context):
        return asyncio.run(awaitable)


def _payload() -> Dict[str, Any]:
    content = "public class ObjectHandler {}"
    contract = GraphContractProvider(policy=load_analysis_policy(POLICY_PATH)).resolve(RELATIVE_PATH, content)
    stable_key = f"{SOURCE_ID}|{RELATIVE_PATH}|FILE"
    llm_input = {
        "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
        "requestKind": TARGET_REQUEST_KIND,
        "file": {
            "sourceId": SOURCE_ID,
            "relativePath": RELATIVE_PATH,
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
    }
    return {
        "sourceId": SOURCE_ID,
        "relativePath": RELATIVE_PATH,
        "targetRef": "F1",
        "targetKind": "FILE",
        "requestKind": TARGET_REQUEST_KIND,
        "schemaVersion": TARGET_INPUT_SCHEMA_VERSION,
        "llmInput": llm_input,
        "_refToStableKey": {"F1": stable_key},
        "_stableKeyToRef": {stable_key: "F1"},
        "_refToKind": {"F1": "FILE"},
        "analysisPolicy": contract_payload(contract),
    }


def _valid_enrichment_json() -> str:
    return json.dumps({"claims": []})
