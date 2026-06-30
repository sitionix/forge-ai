import sqlite3
from typing import Optional

from knowledge_service.embedding_provider import EmbeddingProviderError, FakeDeterministicEmbeddingProvider
from knowledge_service.overview_projection import read_overview, refresh_overview_for_sources
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


class MissingModelEmbeddingProvider:
    model = "embeddinggemma"

    def embed_texts(self, texts):
        raise EmbeddingProviderError(
            "SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE",
            "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model.",
            details={"statusCode": 404, "model": "embeddinggemma"},
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
    assert payload["totalFactCount"] == 0
    assert payload["indexedFactCount"] == 0
    assert payload["percentOfFacts"] == 0.0
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
        assert (
            conn.execute(
                "SELECT COUNT(*) FROM semantic_documents WHERE source_id = ? AND graph_revision = ?",
                ("semantic-source", state.graph_revision),
            ).fetchone()[0]
            == state.total_node_count
        )
        assert (
            conn.execute(
                "SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ? AND graph_revision = ?",
                ("semantic-source", state.graph_revision),
            ).fetchone()[0]
            == state.indexed_node_count
        )
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


def test_semantic_index_status_carries_matching_old_revision_vectors_for_current_facts(tmp_path):
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
    with sqlite3.connect(db_path) as conn:
        old_vector_count = conn.execute(
            "SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ? AND graph_revision = ?",
            ("semantic-source", old_state.graph_revision),
        ).fetchone()[0]
        current_vector_count = conn.execute(
            "SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ? AND graph_revision = ?",
            ("semantic-source", stale_state.graph_revision),
        ).fetchone()[0]

    assert old_vector_count == old_state.indexed_node_count == 1
    assert current_vector_count == 0
    assert stale_state.status == SemanticIndexStatus.STALE
    assert stale_state.indexed_node_count == 1
    assert stale_state.total_node_count == 2
    assert stale_state.progress_percent == 50.0


def test_semantic_index_status_ignores_unrelated_old_revision_vectors(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, snapshot_suffix="old")
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig())
    builder.build(["semantic-source"], force=True)

    seed_semantic_graph(
        db_path,
        snapshot_suffix="new",
        nodes=[
            {"id": "node-client", "kind": "CALLABLE", "name": "KnowledgeClient.query", "qualified": "jarvis.KnowledgeClient.query"},
        ],
    )

    stale_state = SemanticIndexStore(db_path).status_for_source("semantic-source")

    assert stale_state.status == SemanticIndexStatus.STALE
    assert stale_state.indexed_node_count == 0
    assert stale_state.progress_percent == 0.0


def test_overview_flat_semantic_percent_10_inventory_5_facts_3_indexed(tmp_path):
    overview = _overview_progress_fixture(tmp_path, inventory_total=10, facts_available=5, indexed_facts=3)
    source = overview["sources"][0]

    assert "semanticIndex" not in source
    assert source["factsProgress"] == {"completedCount": 5, "totalCount": 10, "percent": 50.0}
    assert source["analysis"]["percent"] == 50.0
    assert source["analysis"]["semanticPercent"] == 30.0


def test_overview_flat_semantic_percent_with_no_indexed_facts(tmp_path):
    overview = _overview_progress_fixture(tmp_path, inventory_total=100, facts_available=10, indexed_facts=0)
    source = overview["sources"][0]

    assert source["analysis"]["percent"] == 10.0
    assert source["analysis"]["semanticPercent"] == 0.0


def test_overview_flat_semantic_percent_all_available_facts_indexed(tmp_path):
    overview = _overview_progress_fixture(tmp_path, inventory_total=100, facts_available=10, indexed_facts=10)
    source = overview["sources"][0]

    assert source["analysis"]["percent"] == 10.0
    assert source["analysis"]["semanticPercent"] == 10.0


def test_overview_flat_semantic_percent_100_inventory_50_facts_25_indexed(tmp_path):
    overview = _overview_progress_fixture(tmp_path, inventory_total=100, facts_available=50, indexed_facts=25)
    source = overview["sources"][0]

    assert source["analysis"]["percent"] == 50.0
    assert source["analysis"]["semanticPercent"] == 25.0


def test_overview_flat_semantic_percent_does_not_treat_node_coverage_as_source_coverage(tmp_path):
    overview = _overview_progress_fixture(
        tmp_path,
        inventory_total=113,
        facts_available=41,
        indexed_facts=2,
        semantic_node_count=2,
    )
    source = overview["sources"][0]

    assert source["analysis"]["processedFiles"] == 41
    assert source["analysis"]["percent"] == 36.3
    assert source["analysis"]["semanticPercent"] == 1.8


def test_overview_active_analysis_does_not_hide_indexed_semantic_progress(tmp_path):
    overview = _overview_progress_fixture(tmp_path, inventory_total=100, facts_available=20, indexed_facts=5, active=True)
    source = overview["sources"][0]

    assert source["analysis"]["status"] == "RUNNING"
    assert source["factsProgress"]["percent"] == 20.0
    assert source["analysis"]["semanticPercent"] == 5.0


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


def test_semantic_index_builder_missing_model_failure_is_controlled_and_deduped(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, source_id="semantic-source-a")
    seed_semantic_graph(db_path, source_id="semantic-source-b")

    result = SemanticIndexBuilder(db_path, MissingModelEmbeddingProvider(), config=SemanticBuildConfig()).build(
        ["semantic-source-a", "semantic-source-b"],
        force=True,
    )

    assert result.status == "FAILED"
    assert [diagnostic["code"] for diagnostic in result.diagnostics] == ["SEMANTIC_EMBEDDING_MODEL_UNAVAILABLE"]
    assert result.diagnostics[0]["message"] == (
        "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model."
    )
    assert "/home/" not in result.diagnostics[0]["message"]
    assert "Traceback" not in result.diagnostics[0]["message"]
    for source_id in ("semantic-source-a", "semantic-source-b"):
        state = SemanticIndexStore(db_path).status_for_source(source_id)
        assert state.status == SemanticIndexStatus.FAILED
        assert state.last_error == (
            "Embedding model is not available in local Ollama: embeddinggemma. Pull or configure an installed embedding model."
        )


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


def _overview_progress_fixture(
    tmp_path,
    *,
    inventory_total: int,
    facts_available: int,
    indexed_facts: int,
    semantic_node_count: Optional[int] = None,
    active: bool = False,
):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "semantic-source"
    node_total = semantic_node_count if semantic_node_count is not None else facts_available
    nodes = [
        {
            "id": f"node-{index:03d}",
            "kind": "CALLABLE",
            "name": f"Service{index}.handle",
            "qualified": f"fixture.Service{index}.handle",
            "path": f"src/generated_{index:03d}.py",
        }
        for index in range(1, node_total + 1)
    ]
    seed_semantic_graph(db_path, nodes=nodes)
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        conn.execute("DELETE FROM analysis_files WHERE source_id = ?", (source_id,))
        conn.execute("DELETE FROM files WHERE source_id = ?", (source_id,))
        for index in range(1, inventory_total + 1):
            file_id = 90000 + index
            relative_path = f"src/generated_{index:03d}.py"
            content_hash = f"hash-{source_id}-generated-{index:03d}"
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, ?, '.', '.', ?, '.py', 'python', 'CODE', 100, ?, 'now', 100, 'utf-8:replace', 'now')
                """,
                (file_id, source_id, relative_path, content_hash),
            )
            if index <= facts_available:
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_files(
                        file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status,
                        analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version, flow_domain
                    )
                    VALUES (?, ?, ?, ?, 'semantic-fixture', '1', 'ANALYZED', 'now', 1, 0, '[]', 'GRAPH_V1', 'CODE')
                    """,
                    (file_id, source_id, relative_path, content_hash),
                )
            if index <= node_total:
                conn.execute(
                    """
                    UPDATE analysis_graph_nodes
                    SET inventory_file_id = ?,
                        analysis_file_id = ?,
                        stable_key = ?
                    WHERE source_id = ?
                      AND id = ?
                    """,
                    (
                        file_id,
                        file_id,
                        f"{source_id}|{relative_path}|CALLABLE|Service{index}.handle",
                        source_id,
                        f"node-{index:03d}",
                    ),
                )
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig(batch_size=10))
    if indexed_facts > 0:
        builder.build([source_id], force=True)
    store = SemanticIndexStore(db_path)
    state = store.status_for_source(source_id)
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        first_file = conn.execute(
            "SELECT id, source_path, absolute_path, relative_path, content_hash FROM files WHERE source_id = ? ORDER BY id LIMIT 1",
            (source_id,),
        ).fetchone()
        assert first_file is not None
        if indexed_facts > 0:
            indexed_node_count = min(indexed_facts, node_total)
            keep_node_ids = {f"node-{index:03d}" for index in range(1, indexed_node_count + 1)}
            placeholders = ",".join("?" for _ in keep_node_ids)
            conn.execute(
                f"""
                DELETE FROM semantic_vectors
                WHERE source_id = ?
                  AND node_id NOT IN ({placeholders})
                """,
                [source_id, *sorted(keep_node_ids)],
            )
            conn.execute(
                f"""
                DELETE FROM semantic_documents
                WHERE source_id = ?
                  AND node_id NOT IN ({placeholders})
                """,
                [source_id, *sorted(keep_node_ids)],
            )
            if indexed_node_count < node_total:
                SemanticIndexStore.mark_source_building_conn(
                    conn,
                    source_id,
                    state.graph_revision,
                    node_total,
                    indexed_node_count=indexed_node_count,
                    build_id="fixture-partial-build",
                )
            else:
                SemanticIndexStore.mark_source_ready_conn(
                    conn,
                    source_id,
                    state.graph_revision,
                    node_total,
                    indexed_node_count,
                    embedding_model="fake-deterministic-embedding",
                    embedding_dimension=8,
                    build_id="fixture-ready-build",
                )
        else:
            current = store.status_for_source(source_id)
            SemanticIndexStore.mark_source_pending_conn(conn, source_id, current.graph_revision, node_total)
        if active:
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_jobs(
                    job_id, status, started_at, completed_at, source_count, file_count, processed_file_count,
                    failed_file_count, current_source_id, current_relative_path, source_ids_json,
                    last_progress_at, symbol_count, relation_count, diagnostics_json, mode
                )
                VALUES ('active-job', 'RUNNING', 'now', NULL, 1, ?, ?, 0, ?, ?, ?, 'now', 0, 0, '[]', 'FULL')
                """,
                (inventory_total, facts_available, source_id, first_file["relative_path"], '["semantic-source"]'),
            )
        refresh_overview_for_sources(conn, [source_id])
    return read_overview(db_path)
