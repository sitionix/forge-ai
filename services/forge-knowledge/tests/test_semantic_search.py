import json
import math
import sqlite3
from datetime import datetime, timezone

from knowledge_service.knowledge_search import QueryNormalizer, SearchConfig
from knowledge_service.semantic_index import SemanticIndexStore, ensure_semantic_index_schema
from knowledge_service.semantic_search import (
    SemanticCandidateProvider,
    SemanticSearchConfig,
    SemanticVectorMatch,
    SemanticVectorStore,
    classify_semantic_acceptance,
    cosine_similarity,
)


def test_cosine_similarity_is_deterministic():
    assert cosine_similarity([1.0, 0.0], [1.0, 0.0]) == 1.0
    assert cosine_similarity([1.0, 0.0], [0.0, 1.0]) == 0.0
    assert cosine_similarity([1.0, 1.0], [1.0, 1.0]) == 1.0


def test_vector_search_applies_topk_and_min_similarity(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(
        db_path,
        [
            ("node-a", [1.0, 0.0]),
            ("node-b", [0.8, 0.2]),
            ("node-c", [0.0, 1.0]),
        ],
    )
    store = SemanticVectorStore(db_path, config=SemanticSearchConfig(semantic_top_k=1, min_similarity=0.5))

    result = store.search([1.0, 0.0], source_revisions={"source-a": "revision-a"}, embedding_model="fake")

    assert [match.node_id for match in result.matches] == ["node-a"]
    assert result.matches[0].similarity == 1.0


def test_vector_search_ignores_dimension_mismatch_with_diagnostic(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(db_path, [("node-a", [1.0, 0.0, 0.0])])
    store = SemanticVectorStore(db_path, config=SemanticSearchConfig(min_similarity=0.0))

    result = store.search([1.0, 0.0], source_revisions={"source-a": "revision-a"}, embedding_model="fake")

    assert result.matches == []
    assert any(diagnostic["code"] == "SEMANTIC_DIMENSION_MISMATCH" for diagnostic in result.diagnostics)


def test_vector_search_reports_scan_guardrail(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(db_path, [(f"node-{index}", [1.0, 0.0]) for index in range(3)])
    store = SemanticVectorStore(db_path, config=SemanticSearchConfig(max_search_vectors=2, semantic_top_k=5, min_similarity=0.0))

    result = store.search([1.0, 0.0], source_revisions={"source-a": "revision-a"}, embedding_model="fake")

    assert len(result.matches) == 2
    assert any(diagnostic["code"] == "SEMANTIC_VECTOR_LIMIT_REACHED" for diagnostic in result.diagnostics)


def test_vector_search_uses_source_diverse_semantic_topk_without_forcing_weak_sources(tmp_path):
    rows = [
        _semantic_row("source-a", "a-1", 0.99),
        _semantic_row("source-a", "a-2", 0.98),
        _semantic_row("source-a", "a-3", 0.97),
        _semantic_row("source-a", "a-4", 0.96),
        _semantic_row("source-b", "b-strong", 0.965),
        _semantic_row("source-c", "c-weak", 0.20),
    ]
    forward_db = tmp_path / "forward.sqlite"
    reversed_db = tmp_path / "reversed.sqlite"
    _seed_vectors(forward_db, rows)
    _seed_vectors(reversed_db, list(reversed(rows)))
    config = SemanticSearchConfig(max_search_vectors=20, semantic_top_k=3, min_similarity=0.5)

    forward = SemanticVectorStore(forward_db, config=config).search(
        [1.0, 0.0],
        source_revisions={"source-a": "revision-a", "source-b": "revision-b", "source-c": "revision-c"},
        embedding_model="fake",
    )
    reversed_sources = SemanticVectorStore(forward_db, config=config).search(
        [1.0, 0.0],
        source_revisions={"source-c": "revision-c", "source-b": "revision-b", "source-a": "revision-a"},
        embedding_model="fake",
    )
    reversed_rows = SemanticVectorStore(reversed_db, config=config).search(
        [1.0, 0.0],
        source_revisions={"source-a": "revision-a", "source-b": "revision-b", "source-c": "revision-c"},
        embedding_model="fake",
    )

    selected = [(match.source_id, match.node_id) for match in forward.matches]
    assert len(selected) == 3
    assert ("source-b", "b-strong") in selected
    assert sum(1 for source_id, _node_id in selected if source_id == "source-a") == 2
    assert all(source_id != "source-c" for source_id, _node_id in selected)
    assert selected == [(match.source_id, match.node_id) for match in reversed_sources.matches]
    assert selected == [(match.source_id, match.node_id) for match in reversed_rows.matches]

    diagnostic = _semantic_diagnostic(forward)
    assert diagnostic["vectorsEligibleBySource"] == {"source-a": 4, "source-b": 1, "source-c": 1}
    assert diagnostic["vectorsScannedBySource"] == {"source-a": 4, "source-b": 1, "source-c": 1}
    assert diagnostic["vectorsTruncatedBySource"] == {}
    assert diagnostic["semanticMatchesAboveThresholdBySource"] == {"source-a": 4, "source-b": 1}
    assert diagnostic["semanticMatchesSelectedBySource"] == {"source-a": 2, "source-b": 1}
    assert diagnostic["semanticResultBudgetReached"] is True
    assert diagnostic["sourcesStarved"] == []


def test_vector_search_ties_and_limits_are_deterministic(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(
        db_path,
        [
            _semantic_row("source-b", "same-b", 0.91),
            _semantic_row("source-a", "same-a", 0.91),
            _semantic_row("source-c", "same-c", 0.91),
        ],
    )
    store = SemanticVectorStore(db_path, config=SemanticSearchConfig(max_search_vectors=2, semantic_top_k=5, min_similarity=0.5))

    result = store.search(
        [1.0, 0.0],
        source_revisions={"source-c": "revision-c", "source-b": "revision-b", "source-a": "revision-a"},
        embedding_model="fake",
    )

    assert [(match.source_id, match.node_id) for match in result.matches] == [
        ("source-a", "same-a"),
        ("source-b", "same-b"),
    ]
    vector_limit = next(diagnostic for diagnostic in result.diagnostics if diagnostic["code"] == "SEMANTIC_VECTOR_LIMIT_REACHED")
    metadata = vector_limit["metadata"]
    assert metadata["vectorsEligibleBySource"] == {"source-a": 1, "source-b": 1, "source-c": 1}
    assert metadata["vectorsScannedBySource"] == {"source-a": 1, "source-b": 1, "source-c": 0}
    assert metadata["vectorsTruncatedBySource"] == {"source-c": 1}
    assert metadata["sourcesStarved"] == ["source-c"]


def test_semantic_candidate_provider_excludes_stale_graph_revision(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(db_path, [_semantic_row("source-a", "node-a", 0.95, graph_id="old-revision")])
    _seed_current_graph(db_path, "source-a", "node-a", graph_revision="current-revision")
    SemanticIndexStore(db_path).mark_source_ready(
        "source-a",
        "old-revision",
        1,
        1,
        embedding_model="fake",
        embedding_dimension=2,
    )
    provider = SemanticCandidateProvider(db_path, _FakeEmbeddingProvider())

    candidates = provider.search(
        QueryNormalizer().normalize("query"),
        [],
        SearchConfig(source_revisions={"source-a": "current-revision"}),
    )

    assert candidates == []
    assert any(diagnostic["code"] == "SEMANTIC_INDEX_STALE" for diagnostic in provider.last_diagnostics)


def test_semantic_candidate_provider_excludes_wrong_embedding_model(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(db_path, [_semantic_row("source-a", "node-a", 0.95)])
    _seed_current_graph(db_path, "source-a", "node-a", graph_revision="revision-a")
    SemanticIndexStore(db_path).mark_source_ready(
        "source-a",
        "revision-a",
        1,
        1,
        embedding_model="other-model",
        embedding_dimension=2,
    )
    provider = SemanticCandidateProvider(db_path, _FakeEmbeddingProvider())

    candidates = provider.search(
        QueryNormalizer().normalize("query"),
        [],
        SearchConfig(source_revisions={"source-a": "revision-a"}),
    )

    assert candidates == []
    assert any(diagnostic["code"] == "SEMANTIC_INDEX_NOT_READY" for diagnostic in provider.last_diagnostics)


def test_semantic_acceptance_classification_keeps_unavailable_and_stale_distinct():
    unavailable = classify_semantic_acceptance(
        [{"code": "SEMANTIC_PROVIDER_UNAVAILABLE"}],
        deterministic_evidence_inspected=True,
    )
    stale = classify_semantic_acceptance(
        [{"code": "SEMANTIC_INDEX_STALE"}],
        deterministic_evidence_inspected=True,
    )
    no_match = classify_semantic_acceptance(
        [{"code": "SEMANTIC_SOURCE_DIVERSE_DIAGNOSTICS"}, {"code": "SEMANTIC_NO_CANDIDATES"}],
        deterministic_evidence_inspected=True,
    )
    not_inspected = classify_semantic_acceptance([], deterministic_evidence_inspected=False)

    assert unavailable.semantic_execution_state == "SEMANTIC_PROVIDER_UNAVAILABLE"
    assert unavailable.data_sufficiency_classification == "CANNOT_CLASSIFY_PERSISTED_DATA_SUFFICIENCY"
    assert stale.semantic_execution_state == "SEMANTIC_INDEX_STALE"
    assert stale.data_sufficiency_classification == "CANNOT_CLASSIFY_PERSISTED_DATA_SUFFICIENCY"
    assert no_match.semantic_execution_state == "SEMANTIC_NO_MATCH_ABOVE_THRESHOLD"
    assert no_match.data_sufficiency_classification == "PERSISTED_DATA_INSUFFICIENT_PROVEN"
    assert not_inspected.data_sufficiency_classification == "CANNOT_CLASSIFY_PERSISTED_DATA_SUFFICIENCY"


def test_unhydrated_semantic_diagnostic_does_not_expose_internal_ids(tmp_path):
    provider = SemanticCandidateProvider(tmp_path / "knowledge.sqlite", _FakeEmbeddingProvider())

    diagnostic = provider._hit_not_hydrated_diagnostic(
        [
            SemanticVectorMatch(
                source_id="source-a",
                node_id="secret-node-db-id",
                document_id="secret-vector-db-id",
                similarity=0.75,
            )
        ],
        [],
        [],
    )

    payload = json.dumps(diagnostic)
    assert "secret-node-db-id" not in payload
    assert "secret-vector-db-id" not in payload
    assert diagnostic["metadata"]["sample"] == [{"sourceId": "source-a", "similarity": 0.75}]


class _FakeEmbeddingProvider:
    model = "fake"

    def embed_texts(self, texts):
        return [[1.0, 0.0] for _ in texts]


def _semantic_row(source_id, node_id, similarity, *, graph_id=None):
    return {
        "source_id": source_id,
        "node_id": node_id,
        "graph_id": graph_id or f"revision-{source_id.rsplit('-', 1)[-1]}",
        "vector": [float(similarity), math.sqrt(max(0.0, 1.0 - float(similarity) ** 2))],
        "model": "fake",
    }


def _semantic_diagnostic(result):
    return next(diagnostic["metadata"] for diagnostic in result.diagnostics if diagnostic["code"] == "SEMANTIC_SOURCE_DIVERSE_DIAGNOSTICS")


def _seed_current_graph(db_path, source_id, node_id, *, graph_revision):
    with sqlite3.connect(db_path) as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS files(source_id TEXT, relative_path TEXT, content_hash TEXT)")
        conn.execute("CREATE TABLE IF NOT EXISTS analysis_files(source_id TEXT, relative_path TEXT, content_hash TEXT)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS analysis_graph_nodes(
                id TEXT, source_id TEXT, status TEXT, node_kind TEXT, relative_path TEXT, content_hash TEXT
            )
            """
        )
        conn.execute("CREATE TABLE IF NOT EXISTS analysis_graph_state(source_id TEXT, graph_id TEXT, content_identity TEXT)")
        conn.execute("INSERT INTO files(source_id, relative_path, content_hash) VALUES (?, 'src/Node.java', 'hash')", (source_id,))
        conn.execute("INSERT INTO analysis_files(source_id, relative_path, content_hash) VALUES (?, 'src/Node.java', 'hash')", (source_id,))
        conn.execute(
            """
            INSERT INTO analysis_graph_nodes(id, source_id, status, node_kind, relative_path, content_hash)
            VALUES (?, ?, 'TRUSTED', 'CALLABLE', 'src/Node.java', 'hash')
            """,
            (node_id, source_id),
        )
        conn.execute(
            "INSERT INTO analysis_graph_state(source_id, graph_id, content_identity) VALUES (?, ?, ?)",
            (source_id, graph_revision, graph_revision),
        )


def _seed_vectors(db_path, rows):
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        ensure_semantic_index_schema(conn)
        for row in rows:
            if isinstance(row, dict):
                node_id = row["node_id"]
                source_id = row.get("source_id", "source-a")
                graph_id = row.get("graph_id", "revision-a")
                vector = row["vector"]
                model = row.get("model", "fake")
            else:
                node_id, vector = row
                source_id = "source-a"
                graph_id = "revision-a"
                model = "fake"
            document_id = f"doc-{node_id}"
            conn.execute(
                """
                INSERT OR REPLACE INTO semantic_documents(
                    document_id, source_id, node_id, node_kind, document_type, graph_id, builder_version,
                    text_hash, text, claim_ids_payload, evidence_ids_payload, status, created_at, updated_at
                )
                VALUES (?, ?, ?, 'CALLABLE', 'NODE_CONTEXT', ?, 1, ?, ?, '[]', '[]', 'READY', ?, ?)
                """,
                (document_id, source_id, node_id, graph_id, f"hash-{node_id}", node_id, now, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO semantic_vectors(
                    document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension,
                    vector_json, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (document_id, source_id, node_id, graph_id, model, len(vector), json.dumps(vector), now, now),
            )
