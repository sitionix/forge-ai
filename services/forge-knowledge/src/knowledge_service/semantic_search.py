from __future__ import annotations

import json
import math
import sqlite3
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

from knowledge_service.embedding_provider import EmbeddingProvider, EmbeddingProviderError
from knowledge_service.knowledge_search import CandidateProvider, SearchCandidate, SearchConfig, SearchDocument, SearchQuery
from knowledge_service.observability import observed_connect
from knowledge_service.semantic_index import SQLITE_SEMANTIC_BUSY_TIMEOUT_MS, SemanticIndexStatus, SemanticIndexStore, ensure_semantic_index_schema


@dataclass(frozen=True)
class SemanticSearchConfig:
    enabled: bool = True
    max_search_vectors: int = 50000
    semantic_top_k: int = 20
    min_similarity: float = 0.35
    query_timeout_ms: int = 1500


@dataclass(frozen=True)
class SemanticVectorMatch:
    source_id: str
    node_id: str
    document_id: str
    similarity: float
    document_type: str = ""


@dataclass(frozen=True)
class SemanticVectorSearchResult:
    matches: list[SemanticVectorMatch]
    diagnostics: list[dict[str, Any]] = field(default_factory=list)
    scanned_count: int = 0


class SemanticVectorStore:
    def __init__(self, db_path: Path, *, config: SemanticSearchConfig | None = None) -> None:
        self.db_path = db_path
        self.config = config or SemanticSearchConfig()

    def search(
        self,
        query_vector: Sequence[float],
        *,
        source_revisions: Mapping[str, str],
        embedding_model: str,
    ) -> SemanticVectorSearchResult:
        diagnostics: list[dict[str, Any]] = []
        if not query_vector or not source_revisions:
            return SemanticVectorSearchResult(matches=[], diagnostics=diagnostics)
        max_vectors = max(1, int(self.config.max_search_vectors or 1))
        rows = self._load_vector_rows(source_revisions, embedding_model, max_vectors + 1)
        if len(rows) > max_vectors:
            rows = rows[:max_vectors]
            diagnostics.append(
                {
                    "code": "SEMANTIC_VECTOR_LIMIT_REACHED",
                    "message": "Semantic vector scan reached the configured safety limit.",
                    "severity": "INFO",
                    "metadata": {"maxSearchVectors": max_vectors},
                }
            )
        matches: list[SemanticVectorMatch] = []
        mismatch_sources: set[str] = set()
        for row in rows:
            vector = _parse_vector(row["vector_json"])
            if not vector:
                continue
            if len(vector) != len(query_vector):
                source_id = str(row["source_id"])
                if source_id not in mismatch_sources:
                    diagnostics.append(
                        {
                            "code": "SEMANTIC_DIMENSION_MISMATCH",
                            "message": "Semantic vector dimension did not match the query embedding dimension.",
                            "severity": "WARN",
                            "sourceId": source_id,
                            "metadata": {
                                "expectedDimension": len(query_vector),
                                "actualDimension": len(vector),
                                "embeddingModel": embedding_model,
                            },
                        }
                    )
                    mismatch_sources.add(source_id)
                continue
            similarity = cosine_similarity(query_vector, vector)
            if similarity < self.config.min_similarity:
                continue
            matches.append(
                SemanticVectorMatch(
                    source_id=str(row["source_id"]),
                    node_id=str(row["node_id"]),
                    document_id=str(row["document_id"]),
                    similarity=similarity,
                    document_type=str(row["document_type"] or ""),
                )
            )
        matches.sort(key=lambda match: (-round(match.similarity, 8), match.source_id, match.node_id, match.document_id))
        return SemanticVectorSearchResult(matches=matches[: max(1, self.config.semantic_top_k)], diagnostics=diagnostics, scanned_count=len(rows))

    def _load_vector_rows(self, source_revisions: Mapping[str, str], embedding_model: str, limit: int) -> list[sqlite3.Row]:
        clauses: list[str] = []
        params: list[Any] = []
        for source_id, graph_revision in sorted(source_revisions.items()):
            clauses.append("(v.source_id = ? AND v.graph_id = ?)")
            params.extend([source_id, graph_revision])
        if not clauses:
            return []
        with self._connect() as conn:
            return conn.execute(
                f"""
                SELECT v.document_id, v.source_id, v.node_id, v.graph_id, v.embedding_dimension, v.vector_json, d.document_type
                FROM semantic_vectors v
                JOIN semantic_documents d
                  ON d.document_id = v.document_id
                 AND d.source_id = v.source_id
                 AND d.node_id = v.node_id
                 AND d.graph_id = v.graph_id
                WHERE v.embedding_model = ?
                  AND d.status = 'READY'
                  AND ({' OR '.join(clauses)})
                ORDER BY v.source_id, v.node_id, v.document_id
                LIMIT ?
                """,
                [embedding_model, *params, max(1, int(limit or 1))],
            ).fetchall()

    def _connect(self) -> sqlite3.Connection:
        conn = observed_connect(self.db_path, timeout=SQLITE_SEMANTIC_BUSY_TIMEOUT_MS / 1000.0)
        conn.execute(f"PRAGMA busy_timeout = {SQLITE_SEMANTIC_BUSY_TIMEOUT_MS}")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        ensure_semantic_index_schema(conn)
        return conn


class SemanticCandidateProvider(CandidateProvider):
    name = "SEMANTIC"

    def __init__(
        self,
        db_path: Path,
        embedding_provider: EmbeddingProvider,
        *,
        config: SemanticSearchConfig | None = None,
        vector_store: Optional[SemanticVectorStore] = None,
    ) -> None:
        self.db_path = db_path
        self.embedding_provider = embedding_provider
        self.config = config or SemanticSearchConfig()
        self.vector_store = vector_store or SemanticVectorStore(db_path, config=self.config)
        self.last_diagnostics: list[dict[str, Any]] = []

    def search(self, query: SearchQuery, documents: Sequence[SearchDocument], config: SearchConfig) -> list[SearchCandidate]:
        self.last_diagnostics = []
        if not self.config.enabled:
            return []
        started_at = time.monotonic()
        source_revisions = self._source_revisions(documents, config)
        if not source_revisions:
            return []
        ready_revisions = self._ready_revisions(source_revisions)
        if not ready_revisions:
            return []
        try:
            query_vectors = self.embedding_provider.embed_texts([query.raw])
        except EmbeddingProviderError as exc:
            self.last_diagnostics.append(
                {
                    "code": "SEMANTIC_PROVIDER_UNAVAILABLE",
                    "message": exc.message,
                    "severity": "WARN",
                    "metadata": exc.details,
                }
            )
            return []
        except Exception:
            self.last_diagnostics.append(
                {
                    "code": "SEMANTIC_PROVIDER_UNAVAILABLE",
                    "message": "Semantic embedding provider failed.",
                    "severity": "WARN",
                }
            )
            return []
        if not query_vectors or not query_vectors[0]:
            self.last_diagnostics.append(
                {"code": "SEMANTIC_PROVIDER_UNAVAILABLE", "message": "Semantic embedding provider returned no query vector.", "severity": "WARN"}
            )
            return []
        if self._timed_out(started_at):
            self.last_diagnostics.append(
                {"code": "SEMANTIC_PROVIDER_UNAVAILABLE", "message": "Semantic query timed out before vector scan.", "severity": "WARN"}
            )
            return []
        try:
            result = self.vector_store.search(query_vectors[0], source_revisions=ready_revisions, embedding_model=self.embedding_provider.model)
        except Exception:
            self.last_diagnostics.append(
                {
                    "code": "SEMANTIC_PROVIDER_UNAVAILABLE",
                    "message": "Semantic vector search failed; deterministic search was used.",
                    "severity": "WARN",
                    "metadata": {"embeddingModel": self.embedding_provider.model},
                }
            )
            return []
        self.last_diagnostics.extend(result.diagnostics)
        documents_by_key = {(document.source_id, document.node_id): document for document in documents}
        missing_pairs = [
            (match.source_id, match.node_id)
            for match in result.matches
            if (match.source_id, match.node_id) not in documents_by_key
        ]
        for document in self._hydrate_documents(missing_pairs, config):
            documents_by_key.setdefault((document.source_id, document.node_id), document)
        candidates: list[SearchCandidate] = []
        unhydrated_matches: list[SemanticVectorMatch] = []
        for match in result.matches:
            document = documents_by_key.get((match.source_id, match.node_id))
            if document is None:
                unhydrated_matches.append(match)
                continue
            score = min(0.88, 0.42 + 0.46 * max(0.0, min(1.0, match.similarity)))
            confidence = "HIGH" if score >= 0.74 else "MEDIUM"
            candidates.append(
                SearchCandidate(
                    document,
                    self.name,
                    "SEMANTIC_VECTOR_SIMILARITY",
                    score,
                    confidence,
                    52,
                    metadata={
                        "semanticDocumentId": match.document_id,
                        "similarity": round(match.similarity, 6),
                        "embeddingModel": self.embedding_provider.model,
                        "semanticDocumentType": match.document_type or None,
                    },
                )
            )
        if unhydrated_matches:
            self.last_diagnostics.append(self._hit_not_hydrated_diagnostic(unhydrated_matches, result.matches, candidates))
        if not result.matches:
            self.last_diagnostics.append(
                {
                    "code": "SEMANTIC_NO_CANDIDATES",
                    "message": "Semantic index was searched but no vector candidates cleared the similarity threshold.",
                    "severity": "INFO",
                    "metadata": {
                        "scannedCount": result.scanned_count,
                        "hitCount": 0,
                        "hydratedCount": 0,
                        "embeddingModel": self.embedding_provider.model,
                    },
                }
            )
        return candidates

    def _source_revisions(self, documents: Sequence[SearchDocument], config: SearchConfig) -> dict[str, str]:
        source_revisions = {
            str(source_id): str(graph_revision or "")
            for source_id, graph_revision in dict(getattr(config, "source_revisions", {}) or {}).items()
            if str(source_id or "")
        }
        if source_revisions:
            return source_revisions
        for document in documents:
            if document.source_id:
                source_revisions.setdefault(document.source_id, document.graph_revision or document.graph_id or "")
        return source_revisions

    def _hydrate_documents(self, source_node_pairs: Sequence[tuple[str, str]], config: SearchConfig) -> list[SearchDocument]:
        requested: list[tuple[str, str]] = []
        seen: set[tuple[str, str]] = set()
        for source_id, node_id in source_node_pairs:
            key = (str(source_id or ""), str(node_id or ""))
            if not key[0] or not key[1] or key in seen:
                continue
            seen.add(key)
            requested.append(key)
        if not requested:
            return []
        hydrator = getattr(config, "document_hydrator", None)
        if hydrator is None:
            return []
        try:
            return list(hydrator(requested))
        except Exception:
            self.last_diagnostics.append(
                {
                    "code": "SEMANTIC_HIT_NOT_HYDRATED",
                    "message": "Semantic vector hits could not be hydrated from the current graph.",
                    "severity": "WARN",
                    "metadata": {
                        "requestedCount": len(requested),
                        "embeddingModel": self.embedding_provider.model,
                    },
                }
            )
            return []

    def _hit_not_hydrated_diagnostic(
        self,
        unhydrated_matches: Sequence[SemanticVectorMatch],
        matches: Sequence[SemanticVectorMatch],
        candidates: Sequence[SearchCandidate],
    ) -> dict[str, Any]:
        source_ids = sorted({match.source_id for match in unhydrated_matches if match.source_id})
        sample = [
            {
                "sourceId": match.source_id,
                "similarity": round(match.similarity, 6),
            }
            for match in unhydrated_matches[:5]
        ]
        diagnostic: dict[str, Any] = {
            "code": "SEMANTIC_HIT_NOT_HYDRATED",
            "message": "Semantic vector hits were skipped because their graph nodes could not be hydrated from the current graph.",
            "severity": "WARN",
            "metadata": {
                "hitCount": len(matches),
                "hydratedCount": len(candidates),
                "unhydratedCount": len(unhydrated_matches),
                "embeddingModel": self.embedding_provider.model,
                "sample": sample,
            },
        }
        if len(source_ids) == 1:
            diagnostic["sourceId"] = source_ids[0]
        return diagnostic

    def _ready_revisions(self, source_revisions: Mapping[str, str]) -> dict[str, str]:
        ready: dict[str, str] = {}
        with self._connect() as conn:
            for source_id, expected_revision in sorted(source_revisions.items()):
                status = SemanticIndexStore.status_for_source_conn(conn, source_id)
                if status.status == SemanticIndexStatus.READY and status.ready and status.graph_revision and status.embedding_model == self.embedding_provider.model:
                    if expected_revision and expected_revision != status.graph_revision:
                        self.last_diagnostics.append(
                            {
                                "code": "SEMANTIC_INDEX_STALE",
                                "message": "Semantic index graph revision did not match the current query graph; deterministic search was used.",
                                "severity": "INFO",
                                "sourceId": source_id,
                                "metadata": {
                                    "graphRevision": status.graph_revision,
                                    "expectedGraphRevision": expected_revision,
                                    "embeddingModel": status.embedding_model,
                                },
                            }
                        )
                        continue
                    ready[source_id] = status.graph_revision
                    continue
                if status.status == SemanticIndexStatus.FAILED:
                    self.last_diagnostics.append(
                        {
                            "code": "SEMANTIC_INDEX_FAILED",
                            "message": "Semantic index is failed for this source; deterministic search was used.",
                            "severity": "WARN",
                            "sourceId": source_id,
                            "metadata": {
                                "graphRevision": status.graph_revision,
                                "embeddingModel": status.embedding_model,
                                "expectedEmbeddingModel": self.embedding_provider.model,
                            },
                        }
                    )
                elif status.status == SemanticIndexStatus.STALE:
                    self.last_diagnostics.append(
                        {
                            "code": "SEMANTIC_INDEX_STALE",
                            "message": "Semantic index is stale for this source; deterministic search was used.",
                            "severity": "INFO",
                            "sourceId": source_id,
                            "metadata": {
                                "graphRevision": status.graph_revision,
                                "expectedGraphRevision": expected_revision or None,
                                "embeddingModel": status.embedding_model,
                                "expectedEmbeddingModel": self.embedding_provider.model,
                            },
                        }
                    )
                else:
                    message = "Semantic index is not ready for this source; deterministic search was used."
                    if status.status == SemanticIndexStatus.READY and status.embedding_model != self.embedding_provider.model:
                        message = "Semantic index embedding model does not match the query embedding model; deterministic search was used."
                    self.last_diagnostics.append(
                        {
                            "code": "SEMANTIC_INDEX_NOT_READY",
                            "message": message,
                            "severity": "INFO",
                            "sourceId": source_id,
                            "metadata": {
                                "status": status.status.value,
                                "graphRevision": status.graph_revision,
                                "expectedGraphRevision": expected_revision or None,
                                "embeddingModel": status.embedding_model,
                                "expectedEmbeddingModel": self.embedding_provider.model,
                            },
                        }
                    )
        return ready

    def _connect(self) -> sqlite3.Connection:
        conn = observed_connect(self.db_path, timeout=SQLITE_SEMANTIC_BUSY_TIMEOUT_MS / 1000.0)
        conn.execute(f"PRAGMA busy_timeout = {SQLITE_SEMANTIC_BUSY_TIMEOUT_MS}")
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        ensure_semantic_index_schema(conn)
        return conn

    def _timed_out(self, started_at: float) -> bool:
        return (time.monotonic() - started_at) * 1000 > max(1, int(self.config.query_timeout_ms or 1))


def cosine_similarity(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right) or not left:
        return 0.0
    dot = 0.0
    left_norm = 0.0
    right_norm = 0.0
    for left_value, right_value in zip(left, right):
        left_float = float(left_value)
        right_float = float(right_value)
        dot += left_float * right_float
        left_norm += left_float * left_float
        right_norm += right_float * right_float
    if left_norm <= 0.0 or right_norm <= 0.0:
        return 0.0
    similarity = dot / (math.sqrt(left_norm) * math.sqrt(right_norm))
    if abs(similarity - 1.0) <= 1e-12:
        return 1.0
    return max(0.0, min(1.0, similarity))


def _parse_vector(value: Any) -> list[float]:
    try:
        parsed = json.loads(value or "[]")
    except (TypeError, ValueError):
        return []
    if not isinstance(parsed, list):
        return []
    vector: list[float] = []
    for item in parsed:
        if not isinstance(item, (int, float)) or not math.isfinite(float(item)):
            return []
        vector.append(float(item))
    return vector
