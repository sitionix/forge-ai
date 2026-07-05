import json
import sqlite3
from datetime import datetime, timezone

from knowledge_service.semantic_index import ensure_semantic_index_schema
from knowledge_service.semantic_search import SemanticSearchConfig, SemanticVectorStore, cosine_similarity


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
    assert any(diagnostic["code"] == "SEMANTIC_INDEX_FAILED" for diagnostic in result.diagnostics)


def test_vector_search_reports_scan_guardrail(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_vectors(db_path, [(f"node-{index}", [1.0, 0.0]) for index in range(3)])
    store = SemanticVectorStore(db_path, config=SemanticSearchConfig(max_search_vectors=2, semantic_top_k=5, min_similarity=0.0))

    result = store.search([1.0, 0.0], source_revisions={"source-a": "revision-a"}, embedding_model="fake")

    assert len(result.matches) == 2
    assert any(diagnostic["code"] == "SEMANTIC_VECTOR_LIMIT_REACHED" for diagnostic in result.diagnostics)


def _seed_vectors(db_path, rows):
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        ensure_semantic_index_schema(conn)
        for node_id, vector in rows:
            document_id = f"doc-{node_id}"
            conn.execute(
                """
                INSERT OR REPLACE INTO semantic_documents(
                    document_id, source_id, node_id, node_kind, document_type, graph_id, builder_version,
                    text_hash, text, claim_ids_payload, evidence_ids_payload, status, created_at, updated_at
                )
                VALUES (?, 'source-a', ?, 'CALLABLE', 'NODE_CONTEXT', 'revision-a', 1, ?, ?, '[]', '[]', 'READY', ?, ?)
                """,
                (document_id, node_id, f"hash-{node_id}", node_id, now, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO semantic_vectors(
                    document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension,
                    vector_json, created_at, updated_at
                )
                VALUES (?, 'source-a', ?, 'revision-a', 'fake', ?, ?, ?, ?)
                """,
                (document_id, node_id, len(vector), json.dumps(vector), now, now),
            )
