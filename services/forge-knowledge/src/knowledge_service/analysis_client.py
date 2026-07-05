from __future__ import annotations

import json
import urllib.parse
from typing import Any, Dict

import httpx

from knowledge_service.analysis_graph_contract import AnalysisGraphContract, AnalysisPromptRenderer
from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.graph_response_parser import GraphAnalysisResponseParser
from knowledge_service.errors import KnowledgeError


class OllamaAnalysisClient:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, base_url: str, model: str, timeout_seconds: int, context_tokens: int = 4096):
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = max(1024, context_tokens)
        self.prompt_renderer = AnalysisPromptRenderer()
        self.contract_provider = self.prompt_renderer.provider
        self.parser = GraphAnalysisResponseParser(self.contract_provider)
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(timeout_seconds, connect=min(5, timeout_seconds)))

    async def analyze(self, payload: Dict[str, Any], line_count: int, repair_prompt: str | None = None) -> GraphAnalysisResult:
        contract = self.contract_provider.resolve_payload(payload)
        prompt = self._prompt(payload, repair_prompt, contract)
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
            raise KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out") from exc
        except httpx.HTTPStatusError as exc:
            raise KnowledgeError(
                "ANALYSIS_AI_TRANSPORT_ERROR",
                f"AI analyzer HTTP error {exc.response.status_code}",
                raw_preview=exc.response.text,
            ) from exc
        except httpx.HTTPError as exc:
            raise KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error") from exc
        except (json.JSONDecodeError, ValueError) as exc:
            raise KnowledgeError(
                "ANALYSIS_AI_TRANSPORT_ERROR",
                "AI analyzer returned invalid Ollama envelope JSON",
                raw_preview=response_body,
            ) from exc
        response_text = raw.get("response")
        if not isinstance(response_text, str):
            raise KnowledgeError("ANALYSIS_AI_EMPTY_RESPONSE", "AI analyzer returned no response text", raw_preview="")
        parsed = self.parser.parse(response_text, line_count, contract=contract, known_node_kinds=self._known_node_kinds(payload))
        if isinstance(parsed, GraphAnalysisResult):
            return parsed
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
        parts = []
        rendered_prompt = self.prompt_renderer.render_for_payload(payload, contract=contract)
        if rendered_prompt:
            parts.append(rendered_prompt)
        if repair_prompt:
            parts.append(repair_prompt)
        parts.extend(
            [
                "File metadata and content JSON:",
                json.dumps(payload, ensure_ascii=False),
            ]
        )
        return "\n".join(parts)

    def _known_node_kinds(self, payload: Dict[str, Any]) -> dict[str, str]:
        static_anchors = payload.get("staticAnchors")
        if not isinstance(static_anchors, dict):
            return {}
        result: dict[str, str] = {}
        for item in static_anchors.get("nodes") or []:
            if not isinstance(item, dict):
                continue
            key = item.get("targetStableKey")
            kind = item.get("nodeKind")
            if isinstance(key, str) and isinstance(kind, str):
                result[key] = kind
        return result

    def _require_localhost(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise KnowledgeError("ANALYSIS_BASE_URL_INVALID", "Analysis AI base URL must be localhost")
        return base_url
