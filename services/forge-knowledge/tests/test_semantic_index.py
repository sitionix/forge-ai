import sqlite3

from knowledge_service.semantic_index import (
    SEMANTIC_BUILDER_VERSION,
    SemanticIndexStatus,
    SemanticIndexStatusView,
    SemanticIndexStore,
)


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
