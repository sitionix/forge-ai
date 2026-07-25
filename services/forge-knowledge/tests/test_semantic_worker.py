import json
import sqlite3
import threading
from datetime import datetime, timedelta, timezone

from knowledge_service.embedding_provider import EmbeddingProviderError, FakeDeterministicEmbeddingProvider
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStatus, SemanticIndexStore
from knowledge_service.semantic_worker import SemanticBuildCoordinator, SemanticIndexBackgroundWorker
from semantic_test_support import seed_semantic_graph


class CountingEmbeddingProvider(FakeDeterministicEmbeddingProvider):
    def __init__(self, *, dimension=16):
        super().__init__(dimension=dimension)
        self.calls = 0

    def embed_texts(self, texts):
        self.calls += 1
        return super().embed_texts(texts)


class MissingModelEmbeddingProvider:
    model = "embeddinggemma"

    def __init__(self):
        self.calls = 0

    def embed_texts(self, texts):
        self.calls += 1
        raise EmbeddingProviderError(
            "SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE",
            "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model.",
            details={"statusCode": 404, "model": "embeddinggemma"},
        )


def test_semantic_auto_build_disabled_does_not_start_build(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider, enabled=False)

    result = worker.run_once()

    assert result.status == "DISABLED"
    assert provider.calls == 0
    assert SemanticIndexStore(db_path).status_for_source("semantic-source").status == SemanticIndexStatus.PENDING


def test_semantic_worker_run_once_picks_pending_and_builds_ready(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)

    result = worker.run_once()

    assert result.status == "COMPLETED"
    assert result.selected_source_ids == ["semantic-source"]
    state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    assert state.status == SemanticIndexStatus.READY
    assert state.indexed_node_count == state.total_node_count == 1
    assert provider.calls == 1


def test_semantic_worker_builds_pending_source_with_active_analysis(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    _seed_active_analysis_job(db_path, "semantic-source")
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)

    result = worker.run_once()

    state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    assert result.status == "COMPLETED"
    assert result.selected_source_ids == ["semantic-source"]
    assert state.status == SemanticIndexStatus.READY
    assert state.indexed_node_count == state.total_node_count == 1
    assert provider.calls == 1


def test_semantic_worker_builds_pending_source_after_analysis_is_complete(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    _seed_active_analysis_job(db_path, "semantic-source", job_status="COMPLETED", file_status="ANALYZED")
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)

    result = worker.run_once()

    state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    assert result.status == "COMPLETED"
    assert result.selected_source_ids == ["semantic-source"]
    assert state.status == SemanticIndexStatus.READY
    assert state.indexed_node_count == state.total_node_count == 1
    assert provider.calls == 1


def test_semantic_worker_run_once_picks_stale_and_builds_new_revision(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, graph_suffix="old")
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)
    worker.run_once()
    old_revision = SemanticIndexStore(db_path).status_for_source("semantic-source").graph_revision
    seed_semantic_graph(
        db_path,
        graph_suffix="new",
        nodes=[
            {"id": "node-query", "nodeKind": "CALLABLE", "name": "JarvisQueryService.query", "qualified": "jarvis.JarvisQueryService.query"},
            {"id": "node-client", "nodeKind": "CALLABLE", "name": "KnowledgeClient.query", "qualified": "jarvis.KnowledgeClient.query"},
        ],
    )

    result = worker.run_once()

    state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    assert result.status == "COMPLETED"
    assert state.status == SemanticIndexStatus.READY
    assert state.graph_revision != old_revision
    assert state.total_node_count == 2


def test_semantic_worker_builds_stale_source_with_active_analysis(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, graph_suffix="old")
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)
    worker.run_once()
    old_revision = SemanticIndexStore(db_path).status_for_source("semantic-source").graph_revision
    seed_semantic_graph(
        db_path,
        graph_suffix="new",
        nodes=[
            {"id": "node-query", "nodeKind": "CALLABLE", "name": "JarvisQueryService.query", "qualified": "jarvis.JarvisQueryService.query"},
            {"id": "node-client", "nodeKind": "CALLABLE", "name": "KnowledgeClient.query", "qualified": "jarvis.KnowledgeClient.query"},
        ],
    )
    _seed_active_analysis_job(db_path, "semantic-source")
    provider.calls = 0

    result = worker.run_once()
    state = SemanticIndexStore(db_path).status_for_source("semantic-source")

    assert result.status == "COMPLETED"
    assert state.status == SemanticIndexStatus.READY
    assert state.graph_revision != old_revision
    assert state.total_node_count == 2
    assert state.indexed_node_count == 2
    assert provider.calls == 2


def test_semantic_worker_skips_ready_current_revision(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)
    worker.run_once()
    provider.calls = 0

    result = worker.run_once()

    assert result.status == "IDLE"
    assert result.selected_source_ids == []
    assert provider.calls == 0


def test_semantic_worker_rebuilds_ready_state_without_current_vectors(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    store = SemanticIndexStore(db_path)
    current = store.status_for_source("semantic-source")
    store.mark_source_ready(
        "semantic-source",
        current.graph_revision,
        current.total_node_count,
        current.total_node_count,
        embedding_model="fake-deterministic-embedding",
        embedding_dimension=16,
    )
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)

    result = worker.run_once()

    state = store.status_for_source("semantic-source")
    assert result.status == "COMPLETED"
    assert result.selected_source_ids == ["semantic-source"]
    assert state.status == SemanticIndexStatus.READY
    assert state.ready is True
    assert state.indexed_node_count == state.total_node_count == 1
    assert provider.calls == 1


def test_semantic_worker_skips_missing_or_zero_node_sources(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(
        db_path,
        nodes=[{"id": "node-package", "nodeKind": "PACKAGE", "name": "example", "qualified": "example"}],
    )
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider)

    result = worker.run_once()

    assert result.status == "IDLE"
    assert provider.calls == 0
    assert SemanticIndexStore(db_path).status_for_source("semantic-source").status == SemanticIndexStatus.MISSING


def test_semantic_worker_skips_failed_before_retry_backoff(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    store = SemanticIndexStore(db_path)
    current = store.status_for_source("semantic-source")
    store.mark_source_failed(
        "semantic-source",
        current.graph_revision,
        current.total_node_count,
        error="test failure",
        diagnostics=[{"code": "SEMANTIC_BUILD_FAILED"}],
    )
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider, retry_backoff_seconds=300)

    result = worker.run_once()

    assert result.status == "IDLE"
    assert provider.calls == 0
    assert store.status_for_source("semantic-source").status == SemanticIndexStatus.FAILED


def test_semantic_worker_retries_failed_after_backoff(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    store = SemanticIndexStore(db_path)
    current = store.status_for_source("semantic-source")
    store.mark_source_failed(
        "semantic-source",
        current.graph_revision,
        current.total_node_count,
        error="test failure",
        diagnostics=[{"code": "SEMANTIC_BUILD_FAILED"}],
    )
    _age_state(db_path, "semantic-source", seconds=600)
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider, retry_backoff_seconds=300)

    result = worker.run_once()

    assert result.status == "COMPLETED"
    assert provider.calls == 1
    assert store.status_for_source("semantic-source").status == SemanticIndexStatus.READY


def test_semantic_worker_skips_active_building(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    store = SemanticIndexStore(db_path)
    current = store.status_for_source("semantic-source")
    store.mark_source_building("semantic-source", current.graph_revision, current.total_node_count, build_id="active-build")
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider, building_stale_after_seconds=300)

    result = worker.run_once()

    assert result.status == "IDLE"
    assert provider.calls == 0
    assert store.status_for_source("semantic-source").status == SemanticIndexStatus.BUILDING


def test_semantic_worker_recovers_stale_building_and_builds(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    store = SemanticIndexStore(db_path)
    current = store.status_for_source("semantic-source")
    store.mark_source_building("semantic-source", current.graph_revision, current.total_node_count, build_id="stale-build")
    _age_state(db_path, "semantic-source", seconds=600)
    provider = CountingEmbeddingProvider()
    worker = _worker(db_path, provider, building_stale_after_seconds=300)

    result = worker.run_once()

    assert result.status == "COMPLETED"
    assert provider.calls == 1
    assert store.status_for_source("semantic-source").status == SemanticIndexStatus.READY


def test_semantic_worker_provider_failure_marks_failed_and_survives_without_retry_spam(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    provider = MissingModelEmbeddingProvider()
    worker = _worker(db_path, provider, retry_backoff_seconds=300)

    first = worker.run_once()
    second = worker.run_once()

    state = SemanticIndexStore(db_path).get_state("semantic-source")
    diagnostics = json.loads(state["diagnostics_json"])
    assert first.status == "FAILED"
    assert second.status == "IDLE"
    assert provider.calls == 1
    assert state["status"] == "FAILED"
    assert state["last_error"] == (
        "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model."
    )
    assert [item["code"] for item in diagnostics] == ["SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE"]
    assert "Traceback" not in state["last_error"]
    assert "/home/" not in state["last_error"]


def test_semantic_worker_uses_shared_lock_and_does_not_duplicate_manual_build(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path)
    provider = CountingEmbeddingProvider()
    lock = threading.Lock()
    coordinator = _coordinator(db_path, provider, lock=lock)
    worker = SemanticIndexBackgroundWorker(
        db_path,
        coordinator,
        enabled=True,
        interval_seconds=60,
        failed_retry_backoff_seconds=300,
        building_stale_after_seconds=300,
    )

    lock.acquire()
    try:
        result = worker.run_once()
    finally:
        lock.release()

    assert result.status == "BUSY"
    assert result.selected_source_ids == ["semantic-source"]
    assert provider.calls == 0
    assert SemanticIndexStore(db_path).status_for_source("semantic-source").status == SemanticIndexStatus.PENDING


def _worker(
    db_path,
    provider,
    *,
    enabled=True,
    retry_backoff_seconds=0,
    building_stale_after_seconds=300,
):
    return SemanticIndexBackgroundWorker(
        db_path,
        _coordinator(db_path, provider),
        enabled=enabled,
        interval_seconds=60,
        failed_retry_backoff_seconds=retry_backoff_seconds,
        building_stale_after_seconds=building_stale_after_seconds,
    )


def _coordinator(db_path, provider, *, lock=None):
    return SemanticBuildCoordinator(
        db_path,
        lock or threading.Lock(),
        lambda: SemanticIndexBuilder(db_path, provider, config=SemanticBuildConfig(batch_size=1)),
    )


def _age_state(db_path, source_id, *, seconds):
    old = (datetime.now(timezone.utc) - timedelta(seconds=seconds)).isoformat()
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            UPDATE semantic_index_state
            SET updated_at = ?, started_at = ?, completed_at = ?
            WHERE source_id = ?
            """,
            (old, old, old, source_id),
        )


def _seed_active_analysis_job(db_path, source_id, *, job_status="RUNNING", file_status="RUNNING"):
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        file_row = conn.execute(
            "SELECT id, relative_path, content_hash FROM files WHERE source_id = ? ORDER BY id LIMIT 1",
            (source_id,),
        ).fetchone()
        assert file_row is not None
        job_id = f"active-analysis:{source_id}:{job_status}:{file_status}"
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_jobs(
                job_id, status, started_at, completed_at, source_count, file_count, processed_file_count,
                failed_file_count, current_source_id, current_relative_path, source_ids_json,
                last_progress_at, diagnostics_json, mode
            )
            VALUES (?, ?, ?, ?, 1, 1, ?, 0, ?, ?, ?, ?, '[]', 'FULL')
            """,
            (
                job_id,
                job_status,
                now,
                now if job_status == "COMPLETED" else None,
                1 if file_status not in {"PENDING", "RUNNING"} else 0,
                source_id,
                file_row[1],
                json.dumps([source_id]),
                now,
            ),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_job_files(
                id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, extension,
                content_hash, line_count, decode_policy, flow_domain, status, attempt_count,
                started_at, completed_at, diagnostics_json, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, '.py', ?, 100, 'utf-8:replace', 'CODE', ?, 1, ?, ?, '[]', ?, ?)
            """,
            (
                f"{job_id}:file",
                job_id,
                source_id,
                file_row[0],
                file_row[0] if file_status not in {"PENDING", "RUNNING"} else None,
                file_row[1],
                file_row[2],
                file_status,
                now if file_status in {"RUNNING", "ANALYZED"} else None,
                now if file_status not in {"PENDING", "RUNNING"} else None,
                now,
                now,
            ),
        )
