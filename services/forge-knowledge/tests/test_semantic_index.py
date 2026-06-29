import sqlite3

from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import (
    SEMANTIC_BUILDER_VERSION,
    SemanticIndexStatus,
    SemanticIndexStatusView,
    SemanticIndexStore,
)
from semantic_test_support import seed_semantic_graph


class FailingEmbeddingProvider:
    model = "failing-test"

    def embed_texts(self, texts):
        raise RuntimeError("provider failed")


class MixedDimensionEmbeddingProvider:
    model = "mixed-dimension-test"

    def embed_texts(self, texts):
        return [[1.0, 0.0], [1.0, 0.0, 0.0]][: len(texts)]


def test_semantic_index_status_model_supports_all_states():
    assert {status.value for status in SemanticIndexStatus} == {
        "MISSING",
        "PENDING",
        "BUILDING",
        "READY",
        "FAILED",
        "STALE",
    }


def test_semantic_index_tables_are_created_idempotently(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    store = SemanticIndexStore(db_path)

    store.init()
    store.init()

    with sqlite3.connect(db_path) as conn:
        tables = {
            row[0]
            for row in conn.execute(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                """
            ).fetchall()
        }
    assert {"semantic_index_state", "semantic_documents", "semantic_vectors"} <= tables


def test_missing_source_reports_missing_without_ready_progress(tmp_path):
    status = SemanticIndexStore(tmp_path / "knowledge.sqlite").status_for_source("missing-source")

    assert status.status == SemanticIndexStatus.MISSING
    assert status.total_node_count == 0
    assert status.indexed_node_count == 0
    assert status.progress_percent == 0.0
    assert status.ready is False


def test_mark_pending_creates_state(tmp_path):
    store = SemanticIndexStore(tmp_path / "knowledge.sqlite")

    store.mark_source_pending("edge-gateway", "revision-1", 3)
    state = store.get_state("edge-gateway")

    assert state["status"] == "PENDING"
    assert state["graph_revision"] == "revision-1"
    assert state["total_node_count"] == 3
    assert state["indexed_node_count"] == 0


def test_mark_stale_updates_existing_ready_state_when_revision_changes(tmp_path):
    store = SemanticIndexStore(tmp_path / "knowledge.sqlite")

    store.mark_source_ready(
        "edge-gateway",
        "revision-1",
        2,
        2,
        embedding_model="test-model",
        embedding_dimension=8,
    )
    store.mark_source_stale("edge-gateway", "revision-2", 4)
    state = store.get_state("edge-gateway")

    assert state["status"] == "STALE"
    assert state["graph_revision"] == "revision-2"
    assert state["total_node_count"] == 4
    assert state["indexed_node_count"] == 0
    assert state["embedding_model"] == "test-model"
    assert state["embedding_dimension"] == 8


def test_mark_ready_stores_counts_model_and_dimension(tmp_path):
    store = SemanticIndexStore(tmp_path / "knowledge.sqlite")

    store.mark_source_ready(
        "edge-gateway",
        "revision-1",
        5,
        5,
        embedding_model="nomic-embed-text",
        embedding_dimension=768,
        build_id="build-1",
    )
    state = store.get_state("edge-gateway")

    assert state["status"] == "READY"
    assert state["indexed_node_count"] == 5
    assert state["embedding_model"] == "nomic-embed-text"
    assert state["embedding_dimension"] == 768
    assert state["last_build_id"] == "build-1"


def test_mark_failed_stores_controlled_error_and_diagnostics(tmp_path):
    store = SemanticIndexStore(tmp_path / "knowledge.sqlite")

    store.mark_source_failed(
        "edge-gateway",
        "revision-1",
        5,
        error="embedding provider unavailable",
        diagnostics=[{"code": "SEMANTIC_BUILD_FAILED"}],
        build_id="build-1",
    )
    state = store.get_state("edge-gateway")

    assert state["status"] == "FAILED"
    assert state["last_error"] == "embedding provider unavailable"
    assert state["last_build_id"] == "build-1"
    assert state["diagnostics_json"] == '[{"code":"SEMANTIC_BUILD_FAILED"}]'


def test_progress_calculation_is_deterministic():
    assert SemanticIndexStore.progress_percent(0, 10) == 0.0
    assert SemanticIndexStore.progress_percent(1, 3) == 33.3
    assert SemanticIndexStore.progress_percent(7, 7) == 100.0
    assert SemanticIndexStore.progress_percent(8, 7) == 100.0
    assert SemanticIndexStore.progress_percent(1, 0) == 0.0


def test_total_node_count_zero_does_not_report_ready_or_100_percent():
    status = SemanticIndexStatusView(
        source_id="edge-gateway",
        status=SemanticIndexStatus.READY,
        graph_revision="revision-1",
        builder_version=SEMANTIC_BUILDER_VERSION,
        total_node_count=0,
        indexed_node_count=0,
        embedding_model="test-model",
        embedding_dimension=8,
        updated_at="now",
    )

    payload = status.to_dict()

    assert payload["progressPercent"] == 0.0
    assert payload["ready"] is False


def test_semantic_index_builder_pending_to_ready_creates_docs_and_vectors(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        claims=[{"id": "claim-trusted", "node_id": "node-query", "summary": "Trusted indexed summary.", "evidence_ids": ["ev-node-query"]}],
    )
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=12), config=SemanticBuildConfig(batch_size=1))

    result = builder.build(["semantic-source"], force=True)

    assert result.status == "COMPLETED"
    state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    assert state.status == SemanticIndexStatus.READY
    assert state.indexed_node_count == state.total_node_count == 1
    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors").fetchone()[0] == 1
        row = conn.execute("SELECT claim_ids_json, evidence_ids_json FROM semantic_documents").fetchone()
    assert row[0] == '["claim-trusted"]'
    assert row[1] == '["ev-node-query"]'


def test_semantic_index_builder_stale_to_ready_for_new_revision(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, snapshot_suffix="old")
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig())
    builder.build(["semantic-source"], force=True)
    old_state = SemanticIndexStore(db_path).status_for_source("semantic-source")

    seed_semantic_graph(
        db_path,
        snapshot_suffix="new",
        nodes=[
            {"id": "node-query", "kind": "CALLABLE", "name": "JarvisQueryService.query", "qualified": "jarvis.JarvisQueryService.query"},
            {"id": "node-client", "kind": "CALLABLE", "name": "KnowledgeClient.query", "qualified": "jarvis.KnowledgeClient.query"},
        ],
    )
    stale_state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    result = builder.build(["semantic-source"], force=False)
    new_state = SemanticIndexStore(db_path).status_for_source("semantic-source")

    assert stale_state.status == SemanticIndexStatus.STALE
    assert result.status == "COMPLETED"
    assert new_state.status == SemanticIndexStatus.READY
    assert new_state.graph_revision != old_state.graph_revision
    assert new_state.total_node_count == 2


def test_semantic_index_builder_provider_failure_marks_failed_and_graph_unchanged(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    with sqlite3.connect(db_path) as conn:
        before_nodes = conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0]
        before_edges = conn.execute("SELECT COUNT(*) FROM analysis_graph_edges").fetchone()[0]

    result = SemanticIndexBuilder(db_path, FailingEmbeddingProvider(), config=SemanticBuildConfig()).build(["semantic-source"], force=True)

    state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    assert result.status == "FAILED"
    assert state.status == SemanticIndexStatus.FAILED
    assert state.last_error
    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0] == before_nodes
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edges").fetchone()[0] == before_edges


def test_semantic_index_builder_dimension_mismatch_marks_failed(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        nodes=[
            {"id": "node-a", "kind": "CALLABLE", "name": "A.call", "qualified": "example.A.call"},
            {"id": "node-b", "kind": "CALLABLE", "name": "B.call", "qualified": "example.B.call"},
        ],
    )

    result = SemanticIndexBuilder(db_path, MixedDimensionEmbeddingProvider(), config=SemanticBuildConfig()).build(["semantic-source"], force=True)

    assert result.status == "FAILED"
    assert SemanticIndexStore(db_path).status_for_source("semantic-source").status == SemanticIndexStatus.FAILED


def test_semantic_index_builder_idempotent_rebuild_replaces_current_docs(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig())

    builder.build(["semantic-source"], force=True)
    builder.build(["semantic-source"], force=True)

    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors").fetchone()[0] == 1
