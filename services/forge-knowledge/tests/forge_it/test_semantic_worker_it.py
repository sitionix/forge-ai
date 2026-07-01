import threading

from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config

from knowledge_service.embedding_provider import FakeDeterministicEmbeddingProvider
from knowledge_service.knowledge_query_schema import KnowledgeQueryRequest
from knowledge_service.knowledge_query_service import build_knowledge_query_service
from knowledge_service.semantic_builder import SemanticBuildConfig, SemanticIndexBuilder
from knowledge_service.semantic_index import SemanticIndexStatus, SemanticIndexStore
from knowledge_service.semantic_worker import SemanticBuildCoordinator, SemanticIndexBackgroundWorker
from semantic_test_support import seed_semantic_graph


def test_analysis_publication_pending_state_is_auto_built_by_worker_run_once(tmp_path):
    _, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(app_config.store_path)
    store = SemanticIndexStore(app_config.store_path)
    store.mark_current_graph_pending("semantic-source")
    assert store.status_for_source("semantic-source").status == SemanticIndexStatus.PENDING

    worker = _worker(app_config.store_path, FakeDeterministicEmbeddingProvider(dimension=16))
    result = worker.run_once()

    state = store.status_for_source("semantic-source")
    assert result.status == "COMPLETED"
    assert state.status == SemanticIndexStatus.READY
    assert state.indexed_node_count == state.total_node_count == 1


def test_reanalysis_stale_state_is_auto_built_for_new_revision(tmp_path):
    _, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    provider = FakeDeterministicEmbeddingProvider(dimension=16)
    worker = _worker(app_config.store_path, provider)
    seed_semantic_graph(app_config.store_path, graph_suffix="old")
    worker.run_once()
    store = SemanticIndexStore(app_config.store_path)
    old_revision = store.status_for_source("semantic-source").graph_revision

    seed_semantic_graph(
        app_config.store_path,
        graph_suffix="new",
        nodes=[
            {"id": "node-query", "kind": "CALLABLE", "name": "JarvisQueryService.query", "qualified": "jarvis.JarvisQueryService.query"},
            {"id": "node-client", "kind": "CALLABLE", "name": "KnowledgeClient.query", "qualified": "jarvis.KnowledgeClient.query"},
        ],
    )
    stale = store.status_for_source("semantic-source")
    assert stale.status == SemanticIndexStatus.STALE

    result = worker.run_once()

    state = store.status_for_source("semantic-source")
    assert result.status == "COMPLETED"
    assert state.status == SemanticIndexStatus.READY
    assert state.graph_revision != old_revision
    assert state.total_node_count == 2


def test_knowledge_lifespan_starts_semantic_worker_when_enabled(tmp_path):
    app, _, app_config, _ = build_test_app(
        write_runtime_config(tmp_path, semantic_auto_build_enabled=True, semantic_auto_build_interval_seconds=60)
    )
    provider = FakeDeterministicEmbeddingProvider(dimension=16)
    app.state.semantic_builder_factory = lambda: SemanticIndexBuilder(
        app_config.store_path,
        provider,
        config=SemanticBuildConfig(batch_size=1),
    )

    with TestClient(app) as client:
        status = client.get("/api/v1/knowledge/status").json()["semantic"]
        assert app.state.semantic_worker.is_running is True
        assert status["autoBuildEnabled"] is True
        assert status["autoWorkerConfigured"] is True
        assert status["autoWorkerRunning"] is True


def test_knowledge_lifespan_does_not_start_semantic_worker_when_disabled(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path, semantic_auto_build_enabled=False))
    seed_semantic_graph(app_config.store_path)

    with TestClient(app) as client:
        status = client.get("/api/v1/knowledge/status").json()["semantic"]
        assert app.state.semantic_worker.is_running is False
        assert status["autoBuildEnabled"] is False
        assert status["autoWorkerRunning"] is False

    assert SemanticIndexStore(app_config.store_path).status_for_source("semantic-source").status == SemanticIndexStatus.PENDING


def test_query_works_while_semantic_failed_or_pending(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(app_config.store_path)
    provider = FakeDeterministicEmbeddingProvider(model="embeddinggemma", dimension=16)
    service = build_knowledge_query_service(deps.analysis_store, app_config, embedding_provider=provider)

    pending = service.query(KnowledgeQueryRequest(query="JarvisQueryService query"))
    assert pending.status in {"OK", "AMBIGUOUS"}
    assert pending.matchedNodes

    store = SemanticIndexStore(app_config.store_path)
    current = store.status_for_source("semantic-source")
    store.mark_source_failed(
        "semantic-source",
        current.graph_revision,
        current.total_node_count,
        error="Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model.",
        diagnostics=[{"code": "SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE", "severity": "WARN"}],
    )
    failed = service.query(KnowledgeQueryRequest(query="JarvisQueryService query"))

    assert failed.status in {"OK", "AMBIGUOUS"}
    assert failed.matchedNodes
    assert any(diagnostic.code == "SEMANTIC_INDEX_FAILED" for diagnostic in failed.diagnostics)


def test_semantic_query_uses_worker_built_ready_index(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        claims=[
            {
                "id": "claim-query",
                "node_id": "node-query",
                "summary": "Receives Jarvis query text and sends it to Knowledge.",
                "evidence_ids": ["ev-node-query"],
            }
        ],
    )
    provider = FakeDeterministicEmbeddingProvider(
        dimension=32,
        semantic_keywords={"jarvis_query": ("jarvis", "query", "knowledge")},
    )
    worker = _worker(app_config.store_path, provider)
    worker.run_once()

    service = build_knowledge_query_service(deps.analysis_store, app_config, embedding_provider=provider)
    response = service.query(KnowledgeQueryRequest(query="Jarvis query Knowledge handoff"))

    assert SemanticIndexStore(app_config.store_path).status_for_source("semantic-source").status == SemanticIndexStatus.READY
    assert any("SEMANTIC_VECTOR_SIMILARITY" in node.matchReasons for node in response.matchedNodes)


def _worker(db_path, provider):
    return SemanticIndexBackgroundWorker(
        db_path,
        SemanticBuildCoordinator(
            db_path,
            threading.Lock(),
            lambda: SemanticIndexBuilder(db_path, provider, config=SemanticBuildConfig(batch_size=1)),
        ),
        enabled=True,
        interval_seconds=60,
        failed_retry_backoff_seconds=0,
        building_stale_after_seconds=300,
    )
