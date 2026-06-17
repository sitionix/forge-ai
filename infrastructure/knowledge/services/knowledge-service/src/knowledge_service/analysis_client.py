from __future__ import annotations

import json
import socket
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict

from knowledge_service.graph_schema import GraphAnalysisResult
from knowledge_service.graph_response_parser import GraphAnalysisResponseParser
from knowledge_service.errors import KnowledgeError


class OllamaAnalysisClient:
    name = "ai-file-analyzer"
    version = "1"

    def __init__(self, base_url: str, model: str, timeout_seconds: int, prompt_path: Path, context_tokens: int = 4096):
        self.base_url = self._require_localhost(base_url.rstrip("/"))
        self.model = model
        self.timeout_seconds = timeout_seconds
        self.context_tokens = max(1024, context_tokens)
        self.prompt = prompt_path.read_text(encoding="utf-8") if prompt_path.exists() else ""
        self.parser = GraphAnalysisResponseParser()

    def analyze(self, payload: Dict[str, Any], line_count: int, repair_prompt: str | None = None) -> GraphAnalysisResult:
        prompt = self._prompt(payload, repair_prompt)
        body = json.dumps({
            "model": self.model,
            "prompt": prompt,
            "stream": False,
            "format": "json",
            "options": {
                "num_ctx": self.context_tokens,
            },
        }).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/api/generate",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        response_body = ""
        try:
            with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                response_body = response.read().decode("utf-8", errors="replace")
                raw = json.loads(response_body)
        except (TimeoutError, socket.timeout) as exc:
            raise KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out") from exc
        except urllib.error.HTTPError as exc:
            error_body = exc.read().decode("utf-8", errors="replace")
            raise KnowledgeError(
                "ANALYSIS_AI_TRANSPORT_ERROR",
                f"AI analyzer HTTP error {exc.code}",
                raw_preview=error_body,
            ) from exc
        except urllib.error.URLError as exc:
            if isinstance(getattr(exc, "reason", None), (TimeoutError, socket.timeout)):
                raise KnowledgeError("ANALYSIS_AI_TIMEOUT", "AI analyzer request timed out") from exc
            raise KnowledgeError("ANALYSIS_AI_TRANSPORT_ERROR", "AI analyzer transport error") from exc
        except json.JSONDecodeError as exc:
            raise KnowledgeError(
                "ANALYSIS_AI_TRANSPORT_ERROR",
                "AI analyzer returned invalid Ollama envelope JSON",
                raw_preview=response_body,
            ) from exc
        response_text = raw.get("response")
        if not isinstance(response_text, str):
            raise KnowledgeError("ANALYSIS_AI_EMPTY_RESPONSE", "AI analyzer returned no response text", raw_preview="")
        parsed = self.parser.parse(response_text, line_count)
        if isinstance(parsed, GraphAnalysisResult):
            return parsed
        raise KnowledgeError(parsed.code, parsed.message, raw_preview=parsed.raw_preview)

    def _prompt(self, payload: Dict[str, Any], repair_prompt: str | None = None) -> str:
        parts = [
            self.prompt,
        ]
        if repair_prompt:
            parts.append(repair_prompt)
        parts.extend([
            "File metadata and content JSON:",
            json.dumps(payload, ensure_ascii=False),
        ])
        return "\n".join(parts)

    def _require_localhost(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme not in {"http", "https"} or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise KnowledgeError("ANALYSIS_BASE_URL_INVALID", "Analysis AI base URL must be localhost")
        return base_url
