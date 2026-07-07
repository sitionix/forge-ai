from __future__ import annotations

import json
import time
import urllib.parse
from typing import Any, Dict

import httpx

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, AnalysisPromptRenderer
from knowledge_service.analysis_runtime_events import emit_runtime_event, runtime_preview, text_hash, utc_now
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.graph_response_parser import GraphAnalysisResponseParser
from knowledge_service.errors import KnowledgeError
from knowledge_service.target_enrichment import TargetPromptRenderer, TargetResponseParserValidator, is_target_enrichment_payload


class OllamaAnalysisClient:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: int,
        context_tokens: int = 4096,
        http_client: httpx.AsyncClient | None = None,
    ):
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = max(1024, context_tokens)
        self.prompt_renderer = AnalysisPromptRenderer()
        self.target_prompt_renderer = TargetPromptRenderer()
        self.contract_provider = self.prompt_renderer.provider
        self.parser = GraphAnalysisResponseParser(self.contract_provider)
        self.target_parser = TargetResponseParserValidator()
        self._client = http_client or httpx.AsyncClient(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))

    async def analyze(self, payload: Dict[str, Any], line_count: int, repair_prompt: str | None = None) -> GraphAnalysisResult:
        contract = self.contract_provider.resolve_payload(payload)
        prompt = self._prompt(payload, repair_prompt, contract)
        request_started_at = utc_now()
        request_started = time.perf_counter()
        request_metadata = self._request_metadata(payload, prompt, repair_prompt)
        emit_runtime_event(
            stage="LLM_REQUEST",
            event_type="PROVIDER_REQUEST",
            status="STARTED",
            started_at=request_started_at,
            metadata=request_metadata,
        )
        response_body = ""
        try:
            response = await self._client.post(
                f"{self.base_url}/api/generate",
                json={
                    "model": self.model,
                    "prompt": prompt,
                    "stream": False,
                    "format": "json",
                    "options": {
                        "num_ctx": self.context_tokens,
                    },
                },
            )
            response_body = response.text
            response.raise_for_status()
            raw = response.json()
        except httpx.TimeoutException as exc:
            self._emit_failed_response(
                request_metadata,
                request_started_at,
                request_started,
                "ANALYSIS_AI_TIMEOUT",
                "AI analyzer request timed out",
            )
            raise KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out") from exc
        except httpx.HTTPStatusError as exc:
            self._emit_failed_response(
                request_metadata,
                request_started_at,
                request_started,
                "ANALYSIS_AI_TRANSPORT_ERROR",
                f"AI analyzer HTTP error {exc.response.status_code}",
                response_text=exc.response.text,
            )
            raise KnowledgeError(
                "ANALYSIS_AI_TRANSPORT_ERROR",
                f"AI analyzer HTTP error {exc.response.status_code}",
                raw_preview=exc.response.text,
            ) from exc
        except httpx.HTTPError as exc:
            self._emit_failed_response(
                request_metadata,
                request_started_at,
                request_started,
                "ANALYSIS_AI_TRANSPORT_ERROR",
                "AI analyzer transport error",
            )
            raise KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error") from exc
        except (json.JSONDecodeError, ValueError) as exc:
            self._emit_failed_response(
                request_metadata,
                request_started_at,
                request_started,
                "ANALYSIS_AI_TRANSPORT_ERROR",
                "AI analyzer returned invalid Ollama envelope JSON",
                response_text=response_body,
            )
            raise KnowledgeError(
                "ANALYSIS_AI_TRANSPORT_ERROR",
                "AI analyzer returned invalid Ollama envelope JSON",
                raw_preview=response_body,
            ) from exc
        response_text = raw.get("response")
        if not isinstance(response_text, str):
            self._emit_failed_response(
                {**request_metadata, **self._provider_response_metadata(raw, "")},
                request_started_at,
                request_started,
                "ANALYSIS_AI_EMPTY_RESPONSE",
                "AI analyzer returned no response text",
            )
            raise KnowledgeError("ANALYSIS_AI_EMPTY_RESPONSE", "AI analyzer returned no response text", raw_preview="")
        response_metadata = {**request_metadata, **self._provider_response_metadata(raw, response_text)}
        emit_runtime_event(
            stage="LLM_RESPONSE",
            event_type="PROVIDER_RESPONSE",
            status="COMPLETED",
            started_at=request_started_at,
            completed_at=utc_now(),
            duration_ms=self._duration_ms(request_started),
            metadata=response_metadata,
        )
        if not is_target_enrichment_payload(payload):
            raise KnowledgeError(
                "ANALYSIS_TARGET_INPUT_REQUIRED",
                "AI analyzer requires the target-anchor enrichment input contract.",
                relativePath=str(payload.get("relativePath") or ""),
                stage="LLM_ENRICHMENT",
                severity="ERROR",
            )
        parsed = self.target_parser.parse(response_text, payload=payload, line_count=line_count, contract=contract)
        if isinstance(parsed, GraphAnalysisResult):
            return parsed
        self._emit_parse_failure(parsed, response_text, response_metadata)
        raise KnowledgeError(
            parsed.code,
            parsed.message,
            raw_preview=parsed.raw_preview,
            error_details=parsed.error_details,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    def _prompt(
        self,
        payload: Dict[str, Any],
        repair_prompt: str | None = None,
        contract: AnalysisGraphContract | None = None,
    ) -> str:
        if not is_target_enrichment_payload(payload):
            raise KnowledgeError(
                "ANALYSIS_TARGET_INPUT_REQUIRED",
                "AI analyzer requires the target-anchor enrichment input contract.",
                relativePath=str(payload.get("relativePath") or ""),
                stage="LLM_ENRICHMENT",
                severity="ERROR",
            )
        return self.target_prompt_renderer.render(payload, repair_prompt)

    def _llm_payload(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        llm_input = payload.get("llmInput")
        if isinstance(llm_input, dict):
            return dict(llm_input)
        return {}

    def _require_localhost(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise KnowledgeError("ANALYSIS_BASE_URL_INVALID", "Analysis AI base URL must be localhost")
        return base_url

    def _request_metadata(self, payload: Dict[str, Any], prompt: str, repair_prompt: str | None) -> Dict[str, Any]:
        return {
            "provider": "ollama",
            "model": self.model,
            "requestTimeoutSeconds": self.timeout_seconds,
            "contextTokens": self.context_tokens,
            "numCtx": self.context_tokens,
            "numPredict": None,
            "promptCharLength": len(prompt),
            "promptLineCount": prompt.count("\n") + 1 if prompt else 0,
            "promptHash": text_hash(prompt),
            "sourceId": payload.get("sourceId"),
            "relativePath": payload.get("relativePath"),
            "targetRef": payload.get("targetRef"),
            "targetKind": payload.get("targetKind"),
            "repairAttempt": repair_prompt is not None,
        }

    def _provider_response_metadata(self, raw: Dict[str, Any], response_text: str) -> Dict[str, Any]:
        preview = runtime_preview(response_text)
        provider_metadata_keys = (
            "done",
            "done_reason",
            "total_duration",
            "load_duration",
            "prompt_eval_count",
            "prompt_eval_duration",
            "eval_count",
            "eval_duration",
        )
        return {
            "responseCharLength": preview["charLength"],
            "responsePreviewHead": preview["head"],
            "responsePreviewTail": preview["tail"],
            "responseTruncated": preview["truncated"],
            "maxPreviewChars": preview["maxPreviewChars"],
            "providerResponseMetadata": {key: raw.get(key) for key in provider_metadata_keys if key in raw},
        }

    def _emit_failed_response(
        self,
        metadata: Dict[str, Any],
        started_at: str,
        started: float,
        error_code: str,
        error_message: str,
        response_text: str | None = None,
    ) -> None:
        failure_metadata = dict(metadata)
        if response_text is not None:
            preview = runtime_preview(response_text)
            failure_metadata.update(
                {
                    "responseCharLength": preview["charLength"],
                    "responsePreviewHead": preview["head"],
                    "responsePreviewTail": preview["tail"],
                    "responseTruncated": preview["truncated"],
                    "maxPreviewChars": preview["maxPreviewChars"],
                }
            )
        emit_runtime_event(
            stage="LLM_RESPONSE",
            event_type="PROVIDER_RESPONSE",
            status="FAILED",
            started_at=started_at,
            completed_at=utc_now(),
            duration_ms=self._duration_ms(started),
            error_code=error_code,
            error_message=error_message,
            metadata=failure_metadata,
        )

    def _emit_parse_failure(self, parsed: Any, response_text: str, response_metadata: Dict[str, Any]) -> None:
        preview = runtime_preview(response_text)
        details = self._bounded_parser_details(getattr(parsed, "error_details", []) or [])
        response_truncated = preview["truncated"]
        for detail in details:
            if detail.get("responseTruncated") is not None:
                response_truncated = bool(detail.get("responseTruncated"))
                break
        emit_runtime_event(
            stage="LLM_PARSE",
            event_type="PARSER_FAILURE",
            status="FAILED",
            error_code=getattr(parsed, "code", "ANALYSIS_AI_PARSE_FAILED"),
            error_message=getattr(parsed, "message", "AI response parser failed"),
            metadata={
                **response_metadata,
                "responseCharLength": preview["charLength"],
                "responsePreviewHead": preview["head"],
                "responsePreviewTail": preview["tail"],
                "responseTruncated": response_truncated,
                "maxPreviewChars": preview["maxPreviewChars"],
                "parserErrorDetails": details,
            },
        )

    def _bounded_parser_details(self, details: Any) -> list[Dict[str, Any]]:
        if isinstance(details, dict):
            details = [details]
        if not isinstance(details, list):
            return []
        bounded: list[Dict[str, Any]] = []
        for detail in details[:10]:
            if not isinstance(detail, dict):
                continue
            item: Dict[str, Any] = {}
            for key, value in detail.items():
                if isinstance(value, str):
                    preview = runtime_preview(value, 500)
                    item[key] = preview["head"]
                    if preview["truncated"]:
                        item[f"{key}Truncated"] = True
                else:
                    item[key] = value
            bounded.append(item)
        return bounded

    def _duration_ms(self, started: float) -> int:
        return max(0, int((time.perf_counter() - started) * 1000))
