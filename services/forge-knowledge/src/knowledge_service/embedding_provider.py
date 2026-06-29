from __future__ import annotations

import hashlib
import json
import math
import re
import urllib.parse
from typing import Any, Mapping, Optional, Protocol, Sequence

import httpx


_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)
_CAMEL_BOUNDARY_1 = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_CAMEL_BOUNDARY_2 = re.compile(r"(?<=[A-Z])(?=[A-Z][a-z])")


class EmbeddingProviderError(Exception):
    def __init__(self, code: str, message: str, *, details: Optional[Mapping[str, Any]] = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.details = dict(details or {})

    def diagnostic(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, "severity": "WARN", "metadata": self.details}


class EmbeddingProvider(Protocol):
    model: str

    def embed_texts(self, texts: Sequence[str]) -> list[list[float]]:
        ...


class FakeDeterministicEmbeddingProvider:
    def __init__(
        self,
        *,
        model: str = "fake-deterministic-embedding",
        dimension: int = 32,
        semantic_keywords: Optional[Mapping[str, Sequence[str]]] = None,
    ) -> None:
        self.model = model
        self.dimension = max(8, int(dimension or 8))
        self.semantic_keywords = {name: tuple(str(token).lower() for token in tokens) for name, tokens in (semantic_keywords or {}).items()}

    def embed_texts(self, texts: Sequence[str]) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        vector = [0.0 for _ in range(self.dimension)]
        lowered = str(text or "").lower()
        for token in _tokens(text):
            digest = hashlib.sha256(token.encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimension
            vector[index] += 1.0
        for group_name, keywords in sorted(self.semantic_keywords.items()):
            weight = sum(1.0 for keyword in keywords if keyword and keyword in lowered)
            if weight <= 0:
                continue
            digest = hashlib.sha256(f"group:{group_name}".encode("utf-8")).digest()
            index = int.from_bytes(digest[:4], "big") % self.dimension
            vector[index] += 2.0 * weight
        return vector


class OllamaEmbeddingProvider:
    def __init__(
        self,
        base_url: str,
        model: str,
        timeout_seconds: int,
        *,
        client: Optional[httpx.Client] = None,
    ) -> None:
        self.base_url = self._require_local_base_url(str(base_url or "").rstrip("/"))
        self.model = str(model or "").strip()
        if not self.model:
            raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic embedding model must not be empty.")
        self.timeout_seconds = max(1, int(timeout_seconds or 1))
        self._client = client or httpx.Client(timeout=httpx.Timeout(self.timeout_seconds, connect=min(5, self.timeout_seconds)))

    def embed_texts(self, texts: Sequence[str]) -> list[list[float]]:
        values = [str(text or "") for text in texts]
        if not values:
            return []
        response_text = ""
        try:
            response = self._client.post(
                f"{self.base_url}/api/embed",
                json={"model": self.model, "input": values},
            )
            response_text = response.text
            response.raise_for_status()
            payload = response.json()
        except httpx.TimeoutException as exc:
            raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic embedding request timed out.") from exc
        except httpx.HTTPStatusError as exc:
            raise EmbeddingProviderError(
                "SEMANTIC_PROVIDER_UNAVAILABLE",
                f"Semantic embedding provider returned HTTP {exc.response.status_code}.",
                details={"statusCode": exc.response.status_code, "preview": exc.response.text[:200]},
            ) from exc
        except httpx.HTTPError as exc:
            raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic embedding provider transport failed.") from exc
        except (json.JSONDecodeError, ValueError) as exc:
            raise EmbeddingProviderError(
                "SEMANTIC_PROVIDER_UNAVAILABLE",
                "Semantic embedding provider returned invalid JSON.",
                details={"preview": response_text[:200]},
            ) from exc
        return self._parse_embeddings(payload, len(values))

    def _parse_embeddings(self, payload: Any, expected_count: int) -> list[list[float]]:
        if not isinstance(payload, dict):
            raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic embedding response must be a JSON object.")
        embeddings = payload.get("embeddings")
        if not isinstance(embeddings, list):
            raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic embedding response missing embeddings array.")
        if len(embeddings) != expected_count:
            raise EmbeddingProviderError(
                "SEMANTIC_PROVIDER_UNAVAILABLE",
                "Semantic embedding response count did not match request count.",
                details={"expected": expected_count, "actual": len(embeddings)},
            )
        parsed: list[list[float]] = []
        dimension: Optional[int] = None
        for index, embedding in enumerate(embeddings):
            if not isinstance(embedding, list) or not embedding:
                raise EmbeddingProviderError(
                    "SEMANTIC_PROVIDER_UNAVAILABLE",
                    "Semantic embedding response contained an invalid vector.",
                    details={"index": index},
                )
            vector: list[float] = []
            for value in embedding:
                if not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                    raise EmbeddingProviderError(
                        "SEMANTIC_PROVIDER_UNAVAILABLE",
                        "Semantic embedding response contained a non-numeric vector value.",
                        details={"index": index},
                    )
                vector.append(float(value))
            if dimension is None:
                dimension = len(vector)
            elif len(vector) != dimension:
                raise EmbeddingProviderError(
                    "SEMANTIC_PROVIDER_UNAVAILABLE",
                    "Semantic embedding response contained mixed vector dimensions.",
                    details={"expectedDimension": dimension, "actualDimension": len(vector)},
                )
            parsed.append(vector)
        return parsed

    def _require_local_base_url(self, base_url: str) -> str:
        parsed = urllib.parse.urlparse(base_url)
        hostname = (parsed.hostname or "").lower()
        if parsed.scheme not in {"http", "https"} or hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise EmbeddingProviderError("SEMANTIC_PROVIDER_UNAVAILABLE", "Semantic embedding base URL must point to localhost.")
        return base_url


def _tokens(text: str) -> tuple[str, ...]:
    tokens: list[str] = []
    seen: set[str] = set()
    for piece in _TOKEN_RE.findall(str(text or "")):
        split = _CAMEL_BOUNDARY_2.sub(" ", piece)
        split = _CAMEL_BOUNDARY_1.sub(" ", split)
        for part in split.split():
            lowered = part.lower()
            if lowered and lowered not in seen:
                seen.add(lowered)
                tokens.append(lowered)
        compact = "".join(char.lower() for char in piece if char.isalnum())
        if len(compact) >= 3 and compact not in seen:
            seen.add(compact)
            tokens.append(compact)
    return tuple(tokens)
