import sqlite3
from pathlib import Path
from typing import Dict, Optional, Tuple

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.embedding_provider import EmbeddingProviderError, FakeDeterministicEmbeddingProvider
from knowledge_service.inventory_store import InventoryStore
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


def _force_analysis_store_reinit(db_path: Path) -> None:
    AnalysisStore._initialized_paths.discard(str(db_path.resolve()))
    AnalysisStore(db_path).init()


def _current_graph_revision(conn: sqlite3.Connection, source_id: str) -> str:
    return SemanticIndexStore.compute_graph_revision_conn(conn, source_id)


def _current_semantic_integrity_counts(conn: sqlite3.Connection, source_id: str) -> Dict[str, int]:
    computed_revision = _current_graph_revision(conn, source_id)
    return {
        "current_state_identity_missing_with_facts": int(
            conn.execute(
                """
                SELECT COUNT(*)
                FROM analysis_graph_state state
                WHERE state.source_id = ?
                  AND (state.content_identity IS NULL OR state.content_identity = '')
                  AND EXISTS (
                      SELECT 1
                      FROM analysis_graph_nodes n
                      WHERE n.source_id = state.source_id
                  )
                """,
                (source_id,),
            ).fetchone()[0]
            or 0
        ),
        "semantic_docs_not_matching_computed": int(
            conn.execute(
                """
                SELECT COUNT(*)
                FROM semantic_documents
                WHERE source_id = ?
                  AND graph_id != ?
                """,
                (source_id, computed_revision),
            ).fetchone()[0]
            or 0
        ),
        "orphan_semantic_vectors": int(
            conn.execute(
                """
                SELECT COUNT(*)
                FROM semantic_vectors v
                LEFT JOIN semantic_documents d
                  ON d.document_id = v.document_id
                WHERE d.document_id IS NULL
                """
            ).fetchone()[0]
            or 0
        ),
        "orphan_semantic_documents": int(
            conn.execute(
                """
                SELECT COUNT(*)
                FROM semantic_documents d
                LEFT JOIN analysis_graph_nodes n
                  ON n.source_id = d.source_id
                 AND n.id = d.node_id
                WHERE d.source_id = ?
                  AND n.id IS NULL
                """,
                (source_id,),
            ).fetchone()[0]
            or 0
        ),
    }


def _seed_inventory_membership_lifecycle(
    db_path: Path,
    *,
    inventory_relative_path: Optional[str],
    inventory_content_hash: Optional[str],
    analysis_relative_path: str = "x.java",
    analysis_content_hash: str = "h1",
) -> None:
    source_id = "A"
    analysis_file_id = 1001
    inventory_file_id = 2001
    node_id = "node-x"
    evidence_id = "evidence-x"
    edge_id = "edge-x"
    claim_id = "claim-x"
    diagnostic_id = "diagnostic-x"
    document_id = "doc-x"
    now = "2026-07-01T00:00:00+00:00"
    InventoryStore(db_path).init()
    AnalysisStore(db_path).init()
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.row_factory = sqlite3.Row
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES (?, 'Source A', 'test', '.', 1, '[]', '{}', ?)
            """,
            (source_id, now),
        )
        if inventory_relative_path is not None and inventory_content_hash is not None:
            conn.execute(
                """
                INSERT OR REPLACE INTO files(
                    id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                    size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
                )
                VALUES (?, ?, '.', '.', ?, '.java', 'java', 'CODE', 10, ?, ?, 1, 'utf-8:replace', ?)
                """,
                (inventory_file_id, source_id, inventory_relative_path, inventory_content_hash, now, now),
            )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_files(
                file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status,
                analyzed_at, diagnostics_json, engine_version, flow_domain
            )
            VALUES (?, ?, ?, ?, 'fixture-analyzer', '1', 'ANALYZED', ?, '[]', 'GRAPH_V1', 'CODE')
            """,
            (analysis_file_id, source_id, analysis_relative_path, analysis_content_hash, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_nodes(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                stable_key, node_kind, language, name, qualified_name, display_name, parent_node_id,
                line_start, line_end, confidence, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, 'job-x', ?, ?, ?, ?, ?, ?, ?, 'CALLABLE', 'java', 'handle', 'A.handle', 'handle', NULL,
                    1, 1, 0.9, 'TRUSTED', ?, ?, 'AI', 'CODE')
            """,
            (
                node_id,
                source_id,
                analysis_file_id,
                analysis_file_id,
                analysis_file_id,
                analysis_relative_path,
                analysis_content_hash,
                f"{source_id}|{analysis_relative_path}|CALLABLE|handle",
                now,
                now,
            ),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_evidence(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                line_start, line_end, excerpt, excerpt_hash, evidence_kind, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, 'job-x', ?, ?, ?, ?, ?, ?, 1, 1, 'class X {}', 'excerpt-hash', 'CLAIM', ?, ?, 'AI', 'CODE')
            """,
            (evidence_id, source_id, analysis_file_id, analysis_file_id, analysis_file_id, analysis_relative_path, analysis_content_hash, now, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_edges(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                from_node_id, to_node_id, edge_type, resolution_status, confidence, unresolved_target_json,
                metadata_json, status, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, 'job-x', ?, ?, ?, ?, ?, ?, ?, NULL, 'CALLS', 'UNRESOLVED', 0.8,
                    NULL, '{}', 'TRUSTED', ?, ?, 'AI', 'CODE')
            """,
            (
                edge_id,
                source_id,
                analysis_file_id,
                analysis_file_id,
                analysis_file_id,
                analysis_relative_path,
                analysis_content_hash,
                node_id,
                now,
                now,
            ),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_claims(
                id, job_id, source_id, node_id, claim_kind, summary, confidence, status,
                rejection_reason, created_at, updated_at, fact_origin, flow_domain
            )
            VALUES (?, 'job-x', ?, ?, 'RESPONSIBILITY', 'Handles x.', 0.9, 'TRUSTED', NULL, ?, ?, 'AI', 'CODE')
            """,
            (claim_id, source_id, node_id, now, now),
        )
        conn.execute(
            "INSERT OR REPLACE INTO analysis_graph_edge_evidence(edge_id, evidence_id) VALUES (?, ?)",
            (edge_id, evidence_id),
        )
        conn.execute(
            "INSERT OR REPLACE INTO analysis_graph_claim_evidence(claim_id, evidence_id) VALUES (?, ?)",
            (claim_id, evidence_id),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_diagnostics(
                id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path, content_hash,
                severity, stage, code, message, candidate_id, line_start, line_end,
                metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES (?, 'job-x', ?, ?, ?, ?, ?, ?, 'INFO', 'ANALYSIS', 'DIAG', 'diagnostic',
                    NULL, 1, 1, '{}', ?, 'AI', 'CODE')
            """,
            (diagnostic_id, source_id, analysis_file_id, analysis_file_id, analysis_file_id, analysis_relative_path, analysis_content_hash, now),
        )
        graph_id = SemanticIndexStore.compute_graph_revision_conn(conn, source_id)
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_state(
                source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, updated_at
            )
            VALUES (?, ?, ?, 1, 1, 1, 1, ?)
            """,
            (source_id, graph_id, graph_id, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO semantic_documents(
                document_id, source_id, node_id, node_kind, graph_id, document_type, builder_version,
                text_hash, text, claim_ids_payload, evidence_ids_payload, status, created_at, updated_at
            )
            VALUES (?, ?, ?, 'CALLABLE', ?, 'node', ?, 'text-hash', 'semantic text', ?, ?, 'READY', ?, ?)
            """,
            (document_id, source_id, node_id, graph_id, SEMANTIC_BUILDER_VERSION, f'["{claim_id}"]', f'["{evidence_id}"]', now, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO semantic_vectors(
                document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension,
                vector_json, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, 'fake-deterministic', 2, '[0.0, 1.0]', ?, ?)
            """,
            (document_id, source_id, node_id, graph_id, now, now),
        )


def _lifecycle_counts(conn: sqlite3.Connection) -> Dict[str, int]:
    return {
        "analysis_files": int(conn.execute("SELECT COUNT(*) FROM analysis_files WHERE source_id = 'A'").fetchone()[0]),
        "nodes": int(conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = 'A'").fetchone()[0]),
        "edges": int(conn.execute("SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = 'A'").fetchone()[0]),
        "claims": int(conn.execute("SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = 'A'").fetchone()[0]),
        "evidence": int(conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = 'A'").fetchone()[0]),
        "diagnostics": int(conn.execute("SELECT COUNT(*) FROM analysis_graph_diagnostics WHERE source_id = 'A'").fetchone()[0]),
        "semantic_documents": int(conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = 'A'").fetchone()[0]),
        "semantic_vectors": int(conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = 'A'").fetchone()[0]),
    }


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
                "SELECT COUNT(*) FROM semantic_documents WHERE source_id = ? AND graph_id = ?",
                ("semantic-source", state.graph_revision),
            ).fetchone()[0]
            == state.total_node_count
        )
        assert (
            conn.execute(
                "SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ? AND graph_id = ?",
                ("semantic-source", state.graph_revision),
            ).fetchone()[0]
            == state.indexed_node_count
        )
        row = conn.execute("SELECT claim_ids_payload, evidence_ids_payload FROM semantic_documents").fetchone()
    assert row[0] == '["claim-trusted"]'
    assert row[1] == '["ev-node-query"]'


def test_semantic_index_builder_stale_to_ready_for_new_revision(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, graph_suffix="old")
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig())
    builder.build(["semantic-source"], force=True)
    old_state = SemanticIndexStore(db_path).status_for_source("semantic-source")

    seed_semantic_graph(
        db_path,
        graph_suffix="new",
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


def test_graph_revision_tracks_arity_fields_and_ignores_edge_debug_metadata(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "semantic-source"
    seed_semantic_graph(
        db_path,
        source_id=source_id,
        nodes=[
            {"id": "caller", "kind": "CALLABLE", "name": "Caller.handle", "qualified": "fixture.Caller.handle"},
            {"id": "target", "kind": "CALLABLE", "name": "Target.call", "qualified": "fixture.Target.call"},
        ],
        edges=[{"id": "call-edge", "from": "caller", "to": "target", "type": "CALLS"}],
    )

    with sqlite3.connect(db_path) as conn:
        baseline = _current_graph_revision(conn, source_id)
        conn.execute("UPDATE analysis_graph_edges SET metadata_json = json_set(metadata_json, '$.debugNote', 'changed') WHERE id = 'call-edge'")
        assert _current_graph_revision(conn, source_id) == baseline

        conn.execute("UPDATE analysis_graph_nodes SET parameter_count = 1 WHERE id = 'target'")
        parameter_revision = _current_graph_revision(conn, source_id)
        assert parameter_revision != baseline

        conn.execute("UPDATE analysis_graph_nodes SET parameter_count = NULL WHERE id = 'target'")
        reset_revision = _current_graph_revision(conn, source_id)
        assert reset_revision == baseline

        conn.execute("UPDATE analysis_graph_edges SET argument_count = 1 WHERE id = 'call-edge'")
        argument_revision = _current_graph_revision(conn, source_id)
        assert argument_revision != baseline


def test_semantic_index_status_invalidates_old_revision_vectors_after_file_identity_change(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, graph_suffix="old")
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig())
    builder.build(["semantic-source"], force=True)
    old_state = SemanticIndexStore(db_path).status_for_source("semantic-source")

    seed_semantic_graph(
        db_path,
        graph_suffix="new",
        nodes=[
            {"id": "node-query", "kind": "CALLABLE", "name": "JarvisQueryService.query", "qualified": "jarvis.JarvisQueryService.query"},
            {"id": "node-client", "kind": "CALLABLE", "name": "KnowledgeClient.query", "qualified": "jarvis.KnowledgeClient.query"},
        ],
    )

    stale_state = SemanticIndexStore(db_path).status_for_source("semantic-source")
    with sqlite3.connect(db_path) as conn:
        old_vector_count = conn.execute(
            "SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ? AND graph_id = ?",
            ("semantic-source", old_state.graph_revision),
        ).fetchone()[0]
        current_vector_count = conn.execute(
            "SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ? AND graph_id = ?",
            ("semantic-source", stale_state.graph_revision),
        ).fetchone()[0]

    assert old_state.indexed_node_count == 1
    assert old_vector_count == 0
    assert current_vector_count == 0
    assert stale_state.status == SemanticIndexStatus.STALE
    assert stale_state.indexed_node_count == 0
    assert stale_state.total_node_count == 2
    assert stale_state.progress_percent == 0.0


def test_semantic_index_status_ignores_unrelated_old_revision_vectors(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    seed_semantic_graph(db_path, graph_suffix="old")
    builder = SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig())
    builder.build(["semantic-source"], force=True)

    seed_semantic_graph(
        db_path,
        graph_suffix="new",
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


def test_stale_analysis_cleanup_removes_deleted_file_semantic_rows(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _overview_progress_fixture(tmp_path, inventory_total=10, facts_available=10, indexed_facts=3, semantic_node_count=3)
    with sqlite3.connect(db_path) as conn:
        conn.execute("DELETE FROM files WHERE id = 90001")

    AnalysisStore(db_path).cleanup_stale_files(["semantic-source"])

    with sqlite3.connect(db_path) as conn:
        assert _semantic_counts(conn) == (2, 2)
        assert conn.execute("SELECT COUNT(*) FROM analysis_files WHERE file_id = 90001").fetchone()[0] == 0
    source = read_overview(db_path)["sources"][0]
    assert source["analysis"]["totalFiles"] == 9
    assert source["analysis"]["processedFiles"] == 9
    assert source["analysis"]["semanticPercent"] == 22.2


def test_stale_analysis_cleanup_removes_changed_file_semantic_rows(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _overview_progress_fixture(tmp_path, inventory_total=10, facts_available=10, indexed_facts=3, semantic_node_count=3)
    with sqlite3.connect(db_path) as conn:
        conn.execute("UPDATE files SET content_hash = 'changed-hash' WHERE id = 90001")

    AnalysisStore(db_path).cleanup_stale_files(["semantic-source"])

    with sqlite3.connect(db_path) as conn:
        assert _semantic_counts(conn) == (2, 2)
        assert conn.execute("SELECT COUNT(*) FROM analysis_files WHERE file_id = 90001").fetchone()[0] == 0
    source = read_overview(db_path)["sources"][0]
    assert source["analysis"]["totalFiles"] == 10
    assert source["analysis"]["processedFiles"] == 9
    assert source["analysis"]["semanticPercent"] == 20.0


def test_stale_analysis_cleanup_preserves_unchanged_file_semantic_rows(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    before = _overview_progress_fixture(tmp_path, inventory_total=10, facts_available=10, indexed_facts=3, semantic_node_count=3)

    AnalysisStore(db_path).cleanup_stale_files(["semantic-source"])

    with sqlite3.connect(db_path) as conn:
        assert _semantic_counts(conn) == (3, 3)
    after = read_overview(db_path)
    assert after["sources"][0]["analysis"]["semanticPercent"] == before["sources"][0]["analysis"]["semanticPercent"] == 30.0


def test_inventory_membership_cleanup_preserves_unchanged_identity(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_inventory_membership_lifecycle(db_path, inventory_relative_path="x.java", inventory_content_hash="h1")

    AnalysisStore(db_path).cleanup_stale_files(["A"])

    with sqlite3.connect(db_path) as conn:
        assert _lifecycle_counts(conn) == {
            "analysis_files": 1,
            "nodes": 1,
            "edges": 1,
            "claims": 1,
            "evidence": 1,
            "diagnostics": 1,
            "semantic_documents": 1,
            "semantic_vectors": 1,
        }


def test_inventory_membership_cleanup_deletes_changed_hash_facts(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_inventory_membership_lifecycle(db_path, inventory_relative_path="x.java", inventory_content_hash="h2")

    AnalysisStore(db_path).cleanup_stale_files(["A"])

    with sqlite3.connect(db_path) as conn:
        assert _lifecycle_counts(conn) == {
            "analysis_files": 0,
            "nodes": 0,
            "edges": 0,
            "claims": 0,
            "evidence": 0,
            "diagnostics": 0,
            "semantic_documents": 0,
            "semantic_vectors": 0,
        }
        assert conn.execute("SELECT COUNT(*) FROM files WHERE source_id = 'A' AND relative_path = 'x.java' AND content_hash = 'h2'").fetchone()[0] == 1
    state = AnalysisStore(db_path).current_analysis_state(["A"])
    assert state["totalFiles"] == 1
    assert state["pendingFiles"] == 1
    assert state["succeededFiles"] == 0


def test_inventory_membership_cleanup_deletes_removed_file_facts(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_inventory_membership_lifecycle(db_path, inventory_relative_path=None, inventory_content_hash=None)

    AnalysisStore(db_path).cleanup_stale_files(["A"])

    with sqlite3.connect(db_path) as conn:
        assert _lifecycle_counts(conn) == {
            "analysis_files": 0,
            "nodes": 0,
            "edges": 0,
            "claims": 0,
            "evidence": 0,
            "diagnostics": 0,
            "semantic_documents": 0,
            "semantic_vectors": 0,
        }
    state = AnalysisStore(db_path).current_analysis_state(["A"])
    assert state["totalFiles"] == 0
    assert state["pendingFiles"] == 0


def test_inventory_membership_cleanup_does_not_preserve_by_bare_hash(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _seed_inventory_membership_lifecycle(
        db_path,
        inventory_relative_path="new.java",
        inventory_content_hash="h1",
        analysis_relative_path="old.java",
        analysis_content_hash="h1",
    )

    AnalysisStore(db_path).cleanup_stale_files(["A"])

    with sqlite3.connect(db_path) as conn:
        assert _lifecycle_counts(conn) == {
            "analysis_files": 0,
            "nodes": 0,
            "edges": 0,
            "claims": 0,
            "evidence": 0,
            "diagnostics": 0,
            "semantic_documents": 0,
            "semantic_vectors": 0,
        }
        assert conn.execute("SELECT COUNT(*) FROM files WHERE source_id = 'A' AND relative_path = 'new.java' AND content_hash = 'h1'").fetchone()[0] == 1
    state = AnalysisStore(db_path).current_analysis_state(["A"])
    assert state["totalFiles"] == 1
    assert state["pendingFiles"] == 1


def test_deleting_current_graph_node_cascades_semantic_cache(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _overview_progress_fixture(tmp_path, inventory_total=3, facts_available=3, indexed_facts=3, semantic_node_count=3)

    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute(
            """
            DELETE FROM analysis_graph_nodes
            WHERE source_id = 'semantic-source'
              AND id = 'node-001'
            """
        )

        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = 'semantic-source'").fetchone()[0] == 2
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = 'semantic-source'").fetchone()[0] == 2


def test_deleting_analysis_file_cascades_current_graph_and_semantic_cache(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _overview_progress_fixture(tmp_path, inventory_total=3, facts_available=3, indexed_facts=3, semantic_node_count=3)

    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("DELETE FROM analysis_files WHERE file_id = 90001")

        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = 'semantic-source'").fetchone()[0] == 2
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = 'semantic-source'").fetchone()[0] == 2
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = 'semantic-source'").fetchone()[0] == 2


def test_deleting_analysis_file_cascades_graph_children_and_evidence_links(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "cascade-source"
    node_id = f"{source_id}:node-query"
    seed_semantic_graph(
        db_path,
        source_id=source_id,
        claims=[{"id": "claim-cascade", "node_id": node_id, "summary": "Cascade claim.", "evidence_ids": ["ev-node-query"]}],
        edges=[{"id": "edge-cascade", "from": node_id, "to": None, "unresolved": {"name": "missing"}}],
    )

    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        file_id = conn.execute("SELECT file_id FROM analysis_files WHERE source_id = ?", (source_id,)).fetchone()[0]
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = ?", (source_id,)).fetchone()[0] == 2
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_claim_evidence").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edge_evidence").fetchone()[0] == 1

        conn.execute("DELETE FROM analysis_files WHERE file_id = ?", (file_id,))

        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?", (source_id,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edges WHERE source_id = ?", (source_id,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_claims WHERE source_id = ?", (source_id,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence WHERE source_id = ?", (source_id,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_claim_evidence").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edge_evidence").fetchone()[0] == 0


def test_deleting_claim_edge_or_evidence_cascades_join_rows(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "join-cascade-source"
    node_id = f"{source_id}:node-query"
    seed_semantic_graph(
        db_path,
        source_id=source_id,
        claims=[{"id": "claim-cascade", "node_id": node_id, "summary": "Cascade claim.", "evidence_ids": ["ev-node-query"]}],
        edges=[{"id": "edge-cascade", "from": node_id, "to": None, "unresolved": {"name": "missing"}}],
    )

    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("DELETE FROM analysis_graph_claims WHERE id = 'claim-cascade'")
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_claim_evidence").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence WHERE id = 'ev-node-query'").fetchone()[0] == 1

        conn.execute("DELETE FROM analysis_graph_edges WHERE id = 'edge-cascade'")
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edge_evidence").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_evidence WHERE id = 'ev-edge-cascade'").fetchone()[0] == 1

    seed_semantic_graph(
        db_path,
        source_id=source_id,
        claims=[{"id": "claim-cascade", "node_id": node_id, "summary": "Cascade claim.", "evidence_ids": ["ev-node-query"]}],
        edges=[{"id": "edge-cascade", "from": node_id, "to": None, "unresolved": {"name": "missing"}}],
    )
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("DELETE FROM analysis_graph_evidence WHERE id IN ('ev-node-query', 'ev-edge-cascade')")
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_claim_evidence").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_edge_evidence").fetchone()[0] == 0


def test_init_drops_legacy_graph_storage_and_creates_current_state_schema(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    with sqlite3.connect(db_path) as conn:
        conn.executescript(
            """
            CREATE TABLE graph_old_parent(id TEXT PRIMARY KEY, source_id TEXT);
            CREATE TABLE graph_old_current(
                id TEXT PRIMARY KEY,
                parent_id TEXT REFERENCES graph_old_parent(id) ON DELETE RESTRICT
            );
            CREATE TABLE graph_old_metrics(
                id TEXT PRIMARY KEY,
                parent_id TEXT REFERENCES graph_old_parent(id) ON DELETE CASCADE
            );
            CREATE TABLE analysis_graph_nodes(id TEXT, obsolete_graph_ref TEXT, source_id TEXT, PRIMARY KEY(obsolete_graph_ref, id));
            CREATE TABLE semantic_documents(document_id TEXT PRIMARY KEY, source_id TEXT, node_id TEXT, graph_marker TEXT);
            """
        )

    AnalysisStore(db_path).init()

    with sqlite3.connect(db_path) as conn:
        tables = {
            row[0]
            for row in conn.execute("SELECT name FROM sqlite_master WHERE type = 'table'").fetchall()
        }
        assert not any(name.startswith("graph_") for name in tables)
        assert "analysis_graph_state" in tables
        for table in ("analysis_graph_nodes", "analysis_graph_edges", "analysis_graph_claims", "analysis_graph_evidence"):
            columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
            assert {"id", "source_id"}.issubset(columns)


def test_init_preserves_valid_current_state_semantic_cache(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "semantic-source"
    seed_semantic_graph(db_path)
    SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig()).build([source_id], force=True)

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        computed_revision = _current_graph_revision(conn, source_id)
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert (
            conn.execute("SELECT DISTINCT graph_id FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[
                "graph_id"
            ]
            == computed_revision
        )

    _force_analysis_store_reinit(db_path)

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        computed_revision = _current_graph_revision(conn, source_id)
        state_row = conn.execute("SELECT graph_id, content_identity FROM analysis_graph_state WHERE source_id = ?", (source_id,)).fetchone()
        assert state_row["graph_id"] == computed_revision
        assert state_row["content_identity"] == computed_revision
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT DISTINCT graph_id FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[
            "graph_id"
        ] == computed_revision
        state = SemanticIndexStore.status_for_source_conn(conn, source_id)
        assert state.status == SemanticIndexStatus.READY
        assert state.indexed_node_count == 1
        assert _current_semantic_integrity_counts(conn, source_id) == {
            "current_state_identity_missing_with_facts": 0,
            "semantic_docs_not_matching_computed": 0,
            "orphan_semantic_vectors": 0,
            "orphan_semantic_documents": 0,
        }


def test_init_purges_stale_semantic_docs_for_old_graph_id(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "semantic-source"
    seed_semantic_graph(db_path)
    SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig()).build([source_id], force=True)

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        computed_revision = _current_graph_revision(conn, source_id)
        now = "2026-06-30T00:00:00+00:00"
        conn.execute(
            """
            INSERT INTO semantic_documents(
                document_id, source_id, node_id, node_kind, document_type, graph_id, builder_version,
                text_hash, text, claim_ids_payload, evidence_ids_payload, status, created_at, updated_at
            )
            VALUES (
                'stale-doc', ?, 'node-query', 'CALLABLE', 'node', 'old-revision', ?,
                'stale-hash', 'stale text', '[]', '[]', 'READY', ?, ?
            )
            """,
            (source_id, SEMANTIC_BUILDER_VERSION, now, now),
        )
        conn.execute(
            """
            INSERT INTO semantic_vectors(
                document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension,
                vector_json, created_at, updated_at
            )
            VALUES ('stale-doc', ?, 'node-query', 'old-revision', 'fake-deterministic', 8, '[0.0]', ?, ?)
            """,
            (source_id, now, now),
        )
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0] == 2

    _force_analysis_store_reinit(db_path)

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        computed_revision = _current_graph_revision(conn, source_id)
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE graph_id = 'old-revision'").fetchone()[0] == 0
        assert conn.execute("SELECT DISTINCT graph_id FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[
            "graph_id"
        ] == computed_revision
        assert _current_semantic_integrity_counts(conn, source_id) == {
            "current_state_identity_missing_with_facts": 0,
            "semantic_docs_not_matching_computed": 0,
            "orphan_semantic_vectors": 0,
            "orphan_semantic_documents": 0,
        }


def test_init_purges_semantic_docs_when_current_graph_has_no_facts(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "empty-source"
    AnalysisStore(db_path).init()
    now = "2026-06-30T00:00:00+00:00"

    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT INTO semantic_documents(
                document_id, source_id, node_id, node_kind, document_type, graph_id, builder_version,
                text_hash, text, claim_ids_payload, evidence_ids_payload, status, created_at, updated_at
            )
            VALUES (
                'empty-doc', ?, 'missing-node', 'CALLABLE', 'node', 'old-revision', ?,
                'empty-hash', 'stale text', '[]', '[]', 'READY', ?, ?
            )
            """,
            (source_id, SEMANTIC_BUILDER_VERSION, now, now),
        )
        conn.execute(
            """
            INSERT INTO semantic_vectors(
                document_id, source_id, node_id, graph_id, embedding_model, embedding_dimension,
                vector_json, created_at, updated_at
            )
            VALUES ('empty-doc', ?, 'missing-node', 'old-revision', 'fake-deterministic', 8, '[0.0]', ?, ?)
            """,
            (source_id, now, now),
        )

    _force_analysis_store_reinit(db_path)

    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ?", (source_id,)).fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_state WHERE source_id = ?", (source_id,)).fetchone()[0] == 0


def test_init_preserves_graph_facts_when_inventory_numeric_id_changes(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    source_id = "semantic-source"
    seed_semantic_graph(
        db_path,
        nodes=[
            {
                "id": "node-query",
                "kind": "CALLABLE",
                "name": "SemanticFixture.handle",
                "qualified": "fixture.SemanticFixture.handle",
                "path": "src/semantic_fixture.py",
            }
        ],
    )
    SemanticIndexBuilder(db_path, FakeDeterministicEmbeddingProvider(dimension=8), config=SemanticBuildConfig()).build([source_id], force=True)

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        file_row = conn.execute(
            """
            SELECT f.*
            FROM files f
            WHERE f.source_id = ?
            LIMIT 1
            """,
            (source_id,),
        ).fetchone()
        assert file_row is not None
        original_analysis_file_id = int(file_row["id"])
        replacement_inventory_id = original_analysis_file_id + 100000
        conn.execute("UPDATE files SET id = ? WHERE id = ?", (replacement_inventory_id, original_analysis_file_id))

    AnalysisStore._initialized_paths.discard(str(db_path.resolve()))
    AnalysisStore(db_path).init()

    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        assert conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = ?", (source_id,)).fetchone()[0] == 1
        node = conn.execute("SELECT analysis_file_id, inventory_file_id, file_id FROM analysis_graph_nodes WHERE source_id = ?", (source_id,)).fetchone()
        assert int(node["analysis_file_id"]) == original_analysis_file_id
        assert int(node["inventory_file_id"]) == original_analysis_file_id
        assert int(node["file_id"]) == original_analysis_file_id
        assert (
            conn.execute(
                """
                SELECT COUNT(*)
                FROM analysis_graph_nodes n
                WHERE n.source_id = ?
                  AND EXISTS (
                      SELECT 1
                      FROM analysis_files af
                      WHERE af.source_id = n.source_id
                        AND af.relative_path = n.relative_path
                        AND af.content_hash = n.content_hash
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM files f
                      WHERE f.source_id = n.source_id
                        AND f.relative_path = n.relative_path
                        AND f.content_hash = n.content_hash
                  )
                """,
                (source_id,),
            ).fetchone()[0]
            == 1
        )


def test_overview_semantic_percent_uses_identity_when_numeric_ids_differ(tmp_path):
    db_path = tmp_path / "knowledge.sqlite"
    _overview_progress_fixture(tmp_path, inventory_total=10, facts_available=10, indexed_facts=3, semantic_node_count=3)
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        conn.execute(
            """
            UPDATE analysis_graph_nodes
            SET inventory_file_id = 999001,
                analysis_file_id = 999001
            WHERE source_id = 'semantic-source'
              AND id = 'node-001'
            """
        )
        graph = SemanticIndexStore.current_graph_info_conn(conn, "semantic-source")
        conn.execute("UPDATE semantic_documents SET graph_id = ? WHERE source_id = ?", (graph.graph_revision, "semantic-source"))
        conn.execute("UPDATE semantic_vectors SET graph_id = ? WHERE source_id = ?", (graph.graph_revision, "semantic-source"))
        SemanticIndexStore.mark_source_ready_conn(
            conn,
            "semantic-source",
            graph.graph_revision,
            3,
            3,
            embedding_model="fake-deterministic-embedding",
            embedding_dimension=8,
        )
        refresh_overview_for_sources(conn, ["semantic-source"])

    source = read_overview(db_path)["sources"][0]
    assert source["analysis"]["totalFiles"] == 10
    assert source["analysis"]["semanticPercent"] == 30.0


def test_semantic_cleanup_has_no_source_specific_runtime_hardcode():
    source_text = "\n".join(path.read_text(encoding="utf-8") for path in Path("src/knowledge_service").glob("*.py"))

    assert "wagssox" not in source_text
    assert "bffssox" not in source_text


def test_runtime_logic_has_no_old_new_file_id_lifecycle_terms():
    source_text = "\n".join(path.read_text(encoding="utf-8") for path in Path("src/knowledge_service").glob("*.py"))

    for forbidden in ("old_file_id", "new_file_id", "oldFileId", "newFileId"):
        assert forbidden not in source_text


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
        conn.execute("PRAGMA foreign_keys = ON")
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
                        analyzed_at, diagnostics_json, engine_version, flow_domain
                    )
                    VALUES (?, ?, ?, ?, 'semantic-fixture', '1', 'ANALYZED', 'now', '[]', 'GRAPH_V1', 'CODE')
                    """,
                    (file_id, source_id, relative_path, content_hash),
                )
            if index <= node_total:
                conn.execute(
                    """
                    INSERT OR REPLACE INTO analysis_graph_nodes(
                        id, job_id, source_id, inventory_file_id, analysis_file_id, file_id, relative_path,
                        content_hash, stable_key, node_kind, language, name, qualified_name, display_name,
                        parent_node_id, line_start, line_end, confidence, status, created_at,
                        updated_at, fact_origin, flow_domain
                    )
                    VALUES (?, 'semantic-progress-fixture', ?, ?, ?, ?, ?, ?, ?, 'CALLABLE', 'python', ?, ?, ?, NULL, ?, ?, 0.96, 'TRUSTED', 'now', 'now', 'STATIC', 'CODE')
                    """,
                    (
                        f"node-{index:03d}",
                        source_id,
                        file_id,
                        file_id,
                        file_id,
                        relative_path,
                        content_hash,
                        f"{source_id}|{relative_path}|CALLABLE|Service{index}.handle",
                        f"Service{index}.handle",
                        f"fixture.Service{index}.handle",
                        f"Service{index}.handle",
                        index,
                        index,
                    ),
                )
        graph_id = SemanticIndexStore.compute_graph_revision_conn(conn, source_id)
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_state(
                source_id, graph_id, content_identity, node_count, edge_count, claim_count, evidence_count, updated_at
            )
            VALUES (?, ?, ?, ?, 0, 0, 0, 'now')
            """,
            (source_id, graph_id, graph_id, node_total),
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
                    last_progress_at, diagnostics_json, mode
                )
                VALUES ('active-job', 'RUNNING', 'now', NULL, 1, ?, ?, 0, ?, ?, ?, 'now', '[]', 'FULL')
                """,
                (inventory_total, facts_available, source_id, first_file["relative_path"], '["semantic-source"]'),
            )
        refresh_overview_for_sources(conn, [source_id])
    return read_overview(db_path)


def _semantic_counts(conn: sqlite3.Connection) -> Tuple[int, int]:
    documents = conn.execute("SELECT COUNT(*) FROM semantic_documents WHERE source_id = 'semantic-source'").fetchone()[0]
    vectors = conn.execute("SELECT COUNT(*) FROM semantic_vectors WHERE source_id = 'semantic-source'").fetchone()[0]
    return int(documents), int(vectors)
