from __future__ import annotations

import concurrent.futures
import json
import re
import sqlite3
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List
from urllib.parse import quote

import pytest
from forge_it.test_graph_snapshot_api import (
    LEGACY_GRAPH_OBJECTS,
    create_legacy_symbol_relation_fixture,
    current_snapshot,
    edge_endpoint_violations,
    graph_counts,
    insert_graph_snapshot,
    legacy_counts,
    seed_graph_fixture,
    sqlite_integrity,
    sqlite_objects,
)
from support import AsgiResponse
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.overview_projection import read_overview, rebuild_overview
from knowledge_service.storage_operations import RetentionPolicy, StorageOperations

pytestmark = pytest.mark.forge_it


IMPORTANT_TABLES = (
    "inventory_builds",
    "sources",
    "files",
    "context_chunks",
    "analysis_jobs",
    "analysis_job_files",
    "analysis_files",
    "graph_snapshots",
    "graph_current_snapshots",
    "graph_snapshot_tombstones",
    "analysis_graph_nodes",
    "analysis_graph_edges",
    "analysis_graph_evidence",
    "analysis_graph_claims",
    "analysis_graph_diagnostics",
    "analysis_schema_migrations",
)

FORBIDDEN_TIMING_SEGMENTS = (
    "db;dur=0, projection;dur=0",
    "projection;dur=0",
    "serialization;dur=0",
    "serialization;dur=0.001",
)


def _old(days: int = 90) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def test_it_storage_01_fresh_db_maintenance(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    diagnostics = deps.storage_operations.diagnostics()

    assert app_config.store_path.exists()
    assert diagnostics["journalMode"] == "wal"
    assert diagnostics["busyTimeoutMs"] > 0
    assert diagnostics["foreignKeys"] is True
    assert diagnostics["integrityCheck"] == "ok"
    assert diagnostics["foreignKeyCheckResult"] == "ok"
    assert deps.storage_operations.run_maintenance().integrity_check == "ok"


def test_it_storage_02_representative_migration_idempotency(tmp_path):
    db_path = tmp_path / "representative.sqlite"
    _seed_representative_old_db(db_path)

    AnalysisStore(db_path).init()
    InventoryStore(db_path).init()
    _seed_current_schema_runtime_rows(db_path)

    first_counts = _table_counts(db_path, IMPORTANT_TABLES)
    first_objects = sqlite_objects(db_path)
    first_overview_sources = {source["sourceId"]: source["inventory"] for source in read_overview(db_path)["sources"]}
    first_current = _current_snapshots(db_path)

    AnalysisStore._initialized_paths.discard(str(db_path.resolve()))
    AnalysisStore(db_path).init()

    assert _table_counts(db_path, IMPORTANT_TABLES) == first_counts
    assert sqlite_objects(db_path) == first_objects
    assert LEGACY_GRAPH_OBJECTS.isdisjoint(sqlite_objects(db_path))
    assert {source["sourceId"]: source["inventory"] for source in read_overview(db_path)["sources"]} == first_overview_sources
    assert _current_snapshots(db_path) == first_current == {
        "config-source": "legacy-symbols:config-source",
        "forge-ai": "job-1:forge-ai",
        "legacy-source": "legacy-symbols:legacy-source",
    }
    assert _duplicate_count(db_path, "graph_snapshots", "source_id, snapshot_id") == 0
    assert _duplicate_count(db_path, "graph_current_snapshots", "source_id") == 0
    assert _duplicate_count(db_path, "analysis_job_files", "id") == 0
    assert edge_endpoint_violations(db_path) == 0
    assert sqlite_integrity(db_path) == ("ok", [])


def test_it_storage_03_migration_rollback_and_retry(tmp_path):
    db_path = tmp_path / "rollback.sqlite"
    _seed_representative_old_db(db_path)
    original_legacy_rows = legacy_counts(db_path)
    original_inventory_rows = _table_counts(db_path, ("inventory_builds", "sources", "files", "context_chunks"))

    AnalysisStore._migration_fault_stage = "before_current_activation"
    try:
        with pytest.raises(RuntimeError):
            AnalysisStore(db_path).init()
    finally:
        AnalysisStore._migration_fault_stage = None
        AnalysisStore._initialized_paths.discard(str(db_path.resolve()))

    assert legacy_counts(db_path) == original_legacy_rows
    assert _table_counts(db_path, ("inventory_builds", "sources", "files", "context_chunks")) == original_inventory_rows
    assert LEGACY_GRAPH_OBJECTS.issubset(sqlite_objects(db_path))
    assert current_snapshot(db_path, "legacy-source") == "partial:legacy-source"
    assert current_snapshot(db_path, "config-source") is None
    assert sqlite_integrity(db_path) == ("ok", [])

    AnalysisStore(db_path).init()
    assert graph_counts(db_path) == {"snapshots": 2, "nodes": 5, "edges": 3, "current": 2}
    assert LEGACY_GRAPH_OBJECTS.isdisjoint(sqlite_objects(db_path))
    assert sqlite_integrity(db_path) == ("ok", [])


def test_it_storage_04_retention_protects_current_state(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    with sqlite3.connect(app_config.store_path) as conn:
        conn.execute(
            "INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json) VALUES (?, ?, ?, ?, ?, ?, '{}')",
            ("current", "source-a", "job-current", "PUBLISHED", _old(), _old()),
        )
        conn.execute("INSERT INTO graph_current_snapshots(source_id, snapshot_id, published_at) VALUES (?, ?, ?)", ("source-a", "current", _old()))
        conn.execute(
            "INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json) VALUES (?, ?, ?, ?, ?, ?, '{}')",
            ("expired", "source-a", "job-old", "PUBLISHED", _old(), _old()),
        )
        conn.execute(
            "INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json) VALUES (?, ?, ?, ?, ?, ?, '{}')",
            ("cursor-protected", "source-a", "job-cursor", "PUBLISHED", _old(), _old()),
        )
        conn.execute(
            "INSERT INTO graph_snapshot_tombstones(snapshot_id, source_id, expired_at, reason) VALUES (?, ?, ?, ?)",
            ("cursor-protected", "source-a", (datetime.now(timezone.utc) + timedelta(days=1)).isoformat(), "cursor"),
        )
    result = StorageOperations(app_config.store_path, RetentionPolicy(keep_snapshots_per_source=1)).run_maintenance()

    with sqlite3.connect(app_config.store_path) as conn:
        remaining = {row[0] for row in conn.execute("SELECT snapshot_id FROM graph_snapshots")}
    assert {"current", "cursor-protected"}.issubset(remaining)
    assert "expired" not in remaining
    assert result.retention["graph_snapshots"] >= 1


def test_it_storage_05_retention_idempotency(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    with sqlite3.connect(app_config.store_path) as conn:
        conn.execute(
            "INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count) VALUES (?, ?, 'COMPLETED', 0, 0, 0)",
            (_old(), _old()),
        )
    first = deps.storage_operations.run_maintenance().retention
    second = deps.storage_operations.run_maintenance().retention
    assert first["inventory_builds"] >= 0
    assert all(value == 0 for value in second.values())


def test_it_storage_06_wal_checkpoint_and_bounded_growth(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    db_path = app_config.store_path
    before = _wal_size(db_path)

    reader = sqlite3.connect(db_path)
    try:
        reader.execute("PRAGMA journal_mode=WAL")
        reader.execute("BEGIN")
        reader.execute("SELECT COUNT(*) FROM inventory_builds").fetchone()
        with sqlite3.connect(db_path) as writer:
            writer.execute("PRAGMA journal_mode=WAL")
            writer.execute("PRAGMA wal_autocheckpoint=1000000")
            for batch in range(12):
                payload = "x" * 2048
                writer.execute(
                    """
                    INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, error_message)
                    VALUES (?, ?, 'COMPLETED', 0, 0, 0, ?)
                    """,
                    (_old(batch + 1), _old(batch + 1), payload),
                )
            writer.commit()
        after_writes = _wal_size(db_path)
    finally:
        reader.rollback()
        reader.close()

    assert after_writes > before

    result = deps.storage_operations.run_maintenance(checkpoint_mode="TRUNCATE")
    after_checkpoint = _wal_size(db_path)
    diagnostics = deps.storage_operations.diagnostics()

    assert result.checkpoint["mode"] == "TRUNCATE"
    assert after_checkpoint < after_writes
    assert after_checkpoint <= 4096
    assert diagnostics["integrityCheck"] == "ok"
    assert diagnostics["foreignKeyCheckResult"] == "ok"
    with sqlite3.connect(db_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM inventory_builds").fetchone()[0] >= 12


def test_it_storage_07_writer_plus_readers(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=6, edge_count=5)
    _seed_context_chunk(app_config.store_path)
    insert_graph_snapshot(app_config.store_path, "forge-ai", "job-2:forge-ai", 4, 3, state="BUILDING")

    for reader_count in (1, 5, 10):
        started = threading.Event()
        release_writer = threading.Event()
        errors: list[str] = []
        observed: list[tuple[str, int, int, str, str]] = []

        def writer() -> None:
            try:
                store = AnalysisStore(app_config.store_path)
                with sqlite3.connect(app_config.store_path, timeout=5) as conn:
                    conn.execute("PRAGMA journal_mode=WAL")
                    conn.execute("BEGIN IMMEDIATE")
                    conn.execute(
                        "INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count) VALUES (?, ?, 'RUNNING', 1, 1, 0)",
                        (_now(), None),
                    )
                    started.set()
                    assert release_writer.wait(timeout=5)
                store._write_with_busy_retry(lambda write_conn: store._publish_graph_snapshot(write_conn, "job-2:forge-ai"))
                deps.storage_operations.run_maintenance(checkpoint_mode="PASSIVE", run_optimize=False)
            except Exception as exc:  # pragma: no cover - assertion reports captured message
                errors.append(str(exc))
                started.set()

        thread = threading.Thread(target=writer)
        thread.start()
        assert started.wait(timeout=5)

        with concurrent.futures.ThreadPoolExecutor(max_workers=reader_count) as executor:
            futures = [executor.submit(_read_current_endpoints, app) for _ in range(reader_count)]
            for future in futures:
                observed.extend(future.result(timeout=10))
        release_writer.set()
        thread.join(timeout=10)

        assert not thread.is_alive()
        assert not errors
        assert observed
        assert {row[0] for row in observed}.issubset({"job-1:forge-ai", "job-2:forge-ai"})
        for snapshot_id, manifest_nodes, manifest_edges, node_snapshot, edge_snapshot in observed:
            assert node_snapshot == snapshot_id
            assert edge_snapshot == snapshot_id
            assert (manifest_nodes, manifest_edges) in {(6, 5), (4, 3)}

    diagnostics = deps.storage_operations.diagnostics()
    assert diagnostics["integrityCheck"] == "ok"
    assert diagnostics["foreignKeyCheckResult"] == "ok"
    assert current_snapshot(app_config.store_path, "forge-ai") == "job-2:forge-ai"


def test_it_obs_01_knowledge_measured_timings_are_real(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)
    _seed_context_chunk(app_config.store_path)
    _seed_analysis_job(app_config.store_path, "job-observed")

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        revision = quote(manifest["graphRevision"])
        routes = [
            ("GET", "/api/v1/knowledge/overview"),
            ("POST", "/api/v1/knowledge/context", {"query": "JarvisGateway", "includeContent": False}),
            ("GET", "/api/v1/knowledge/analysis/status"),
            ("GET", "/api/v1/knowledge/analysis/jobs/job-observed"),
            ("GET", "/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE"),
            ("GET", f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=10"),
            ("GET", f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=10"),
            ("GET", f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={revision}"),
            ("GET", f"/api/v1/knowledge/analysis/graph/edge/edge-00000?sourceId=forge-ai&graphRevision={revision}"),
        ]
        responses = [_request_observed(client, route, index) for index, route in enumerate(routes)]

    for response in responses:
        _assert_observed_response(response)


def test_it_obs_02_graph_route_observability(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=4, edge_count=3)

    with TestClient(app) as client:
        manifest_response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE", headers={"X-Correlation-Id": "obs-graph"})
        manifest = manifest_response.json()
        revision = quote(manifest["graphRevision"])
        responses = [
            manifest_response,
            client.get(f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=10", headers={"X-Correlation-Id": "obs-graph"}),
            client.get(f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=10", headers={"X-Correlation-Id": "obs-graph"}),
            client.get(f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={revision}", headers={"X-Correlation-Id": "obs-graph"}),
            client.get(f"/api/v1/knowledge/analysis/graph/edge/edge-00000?sourceId=forge-ai&graphRevision={revision}", headers={"X-Correlation-Id": "obs-graph"}),
        ]

    for response in responses:
        metrics = _assert_observed_response(response, expected_correlation="obs-graph")
        assert metrics["queries"] >= 1
        assert response.headers["x-route-key"].startswith("GET /api/v1/knowledge/analysis/graph")


def test_it_obs_03_context_observability(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    _seed_context_chunk(app_config.store_path, content="JarvisGateway secret source file content")
    with TestClient(app) as client:
        response = client.post(
            "/api/v1/knowledge/context",
            json={"query": "JarvisGateway", "includeContent": False},
            headers={"X-Correlation-Id": "obs-context"},
        )
    body = response.json()
    _assert_observed_response(response, expected_correlation="obs-context")
    assert all(item.get("content") is None for item in body["context"])
    assert "secret source file content" not in response.body.decode("utf-8")


def test_it_obs_06_public_error_redaction(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=2, edge_count=1)
    forbidden = [
        str(tmp_path),
        "Traceback",
        "sqlite",
        "http://127.0.0.1",
        "http://localhost",
        "SYSTEM PROMPT",
        "OLLAMA RAW PROMPT",
        "source file content",
        "retrieved context content",
        "command array",
        "secret-token",
    ]

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        revision = quote(manifest["graphRevision"])
        responses = [
            client.get("/api/v1/knowledge/analysis/jobs/missing"),
            client.get(f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&graphRevision={revision}&cursor=bad"),
            client.get(f"/api/v1/knowledge/analysis/graph/node/missing-node?sourceId=forge-ai&graphRevision={revision}"),
            client.get(f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=__missing_source__&graphRevision={revision}"),
        ]
        with sqlite3.connect(app_config.store_path) as conn:
            conn.execute("DROP TABLE graph_current_snapshots")
        responses.append(client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai"))

    for response in responses:
        text = response.body.decode("utf-8")
        body = response.json()
        assert response.status_code >= 400
        assert isinstance(body, dict)
        assert body.get("correlationId")
        assert body.get("code")
        assert body.get("message")
        assert not any(value in text for value in forbidden)


def _seed_representative_old_db(db_path: Path) -> None:
    create_legacy_symbol_relation_fixture(db_path)
    now = _now()
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute(
            """
            CREATE TABLE inventory_builds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at TEXT NOT NULL,
                completed_at TEXT,
                status TEXT NOT NULL,
                source_count INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                skipped_count INTEGER NOT NULL,
                skipped_reasons_json TEXT,
                error_message TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE sources (
                source_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                group_name TEXT,
                path TEXT NOT NULL,
                root_exists INTEGER NOT NULL,
                tags_json TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                last_seen_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_id TEXT NOT NULL,
                source_path TEXT NOT NULL,
                absolute_path TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                extension TEXT,
                language TEXT,
                flow_domain TEXT,
                size_bytes INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                last_modified TEXT NOT NULL,
                line_count INTEGER NOT NULL DEFAULT 0,
                decode_policy TEXT NOT NULL DEFAULT 'utf-8:replace',
                indexed_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE context_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                content TEXT NOT NULL,
                indexed_at TEXT NOT NULL
            )
            """
        )
        conn.execute("CREATE VIRTUAL TABLE context_chunks_fts USING fts5(content, source_id UNINDEXED, relative_path UNINDEXED, chunk_id UNINDEXED)")
        conn.execute(
            """
            CREATE TABLE inventory_source_state (
                source_id TEXT PRIMARY KEY,
                eligible_file_count INTEGER NOT NULL,
                skipped_count INTEGER,
                skipped_reasons_json TEXT,
                last_inventory_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE analysis_jobs (
                job_id TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                started_at TEXT,
                completed_at TEXT,
                source_count INTEGER NOT NULL,
                file_count INTEGER NOT NULL,
                processed_file_count INTEGER NOT NULL,
                failed_file_count INTEGER NOT NULL,
                current_source_id TEXT,
                current_relative_path TEXT,
                last_progress_at TEXT,
                symbol_count INTEGER NOT NULL,
                relation_count INTEGER NOT NULL,
                diagnostics_json TEXT NOT NULL,
                source_ids_json TEXT,
                skipped_unchanged_file_count INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        conn.execute("CREATE TABLE analysis_schema_migrations(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)")
        conn.execute(
            "INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json) VALUES (?, ?, 'COMPLETED', 2, 2, 0, '{}')",
            (_old(10), _old(10)),
        )
        for index, source_id in enumerate(("legacy-source", "config-source")):
            conn.execute(
                "INSERT INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at) VALUES (?, ?, 'platform', '.', 1, '[]', '{}', ?)",
                (source_id, source_id, now),
            )
            conn.execute(
                """
                INSERT INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
                VALUES (?, ?, '.', '.', ?, '.java', 'java', 'CODE', 100, ?, ?, 10, 'utf-8:replace', ?)
                """,
                (index + 1, source_id, f"src/{source_id}.java", f"hash-{source_id}", now, now),
            )
            conn.execute(
                "INSERT INTO context_chunks(file_id, source_id, relative_path, content_hash, line_start, line_end, content, indexed_at) VALUES (?, ?, ?, ?, 1, 3, ?, ?)",
                (index + 1, source_id, f"src/{source_id}.java", f"hash-{source_id}", f"{source_id} JarvisGateway context", now),
            )
            conn.execute(
                "INSERT INTO context_chunks_fts(rowid, content, source_id, relative_path, chunk_id) VALUES (?, ?, ?, ?, ?)",
                (index + 1, f"{source_id} JarvisGateway context", source_id, f"src/{source_id}.java", index + 1),
            )
            conn.execute(
                "INSERT INTO inventory_source_state(source_id, eligible_file_count, skipped_count, skipped_reasons_json, last_inventory_at) VALUES (?, 1, 0, '{}', ?)",
                (source_id, now),
            )
        conn.execute(
            """
            INSERT INTO analysis_jobs(
                job_id, status, started_at, completed_at, source_count, file_count, processed_file_count, failed_file_count,
                current_source_id, current_relative_path, last_progress_at, symbol_count, relation_count, diagnostics_json,
                source_ids_json, skipped_unchanged_file_count
            )
            VALUES ('legacy-job', 'COMPLETED', ?, ?, 2, 2, 2, 0, 'legacy-source', 'src/legacy-source.java', ?, 5, 3, '[{"code":"OLD"}]', '["legacy-source","config-source"]', 1)
            """,
            (_old(8), _old(8), _old(8)),
        )


def _seed_current_schema_runtime_rows(db_path: Path) -> None:
    seed_graph_fixture(db_path, node_count=3, edge_count=2)
    _seed_analysis_job(db_path, "runtime-job")
    with sqlite3.connect(db_path) as conn:
        conn.row_factory = sqlite3.Row
        now = _now()
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_job_files(
                id, job_id, source_id, inventory_file_id, analysis_file_id, relative_path, extension, content_hash,
                line_count, decode_policy, flow_domain, status, attempt_count, started_at, completed_at,
                diagnostics_json, engine_version, created_at, updated_at
            )
            VALUES ('runtime-job:file-1', 'runtime-job', 'forge-ai', 1, 1, 'src/GraphFixture.java', '.java', 'hash-1',
                    100, 'utf-8:replace', 'CODE', 'COMPLETED', 1, ?, ?, '[]', 'GRAPH_V1', ?, ?)
            """,
            (now, now, now, now),
        )
        conn.execute(
            "INSERT OR REPLACE INTO graph_snapshot_tombstones(snapshot_id, source_id, expired_at, reason) VALUES ('job-1:forge-ai', 'forge-ai', ?, 'cursor')",
            ((datetime.now(timezone.utc) + timedelta(days=1)).isoformat(),),
        )
        rebuild_overview(conn)
    _seed_context_chunk(db_path)


def _seed_analysis_job(db_path: Path, job_id: str) -> None:
    now = _now()
    with sqlite3.connect(db_path) as conn:
        available = {row[1] for row in conn.execute("PRAGMA table_info(analysis_jobs)").fetchall()}
        columns = [
            "job_id",
            "status",
            "started_at",
            "completed_at",
            "source_count",
            "file_count",
            "processed_file_count",
            "failed_file_count",
            "current_source_id",
            "current_relative_path",
            "last_progress_at",
            "symbol_count",
            "relation_count",
            "diagnostics_json",
        ]
        values: List[Any] = [
            job_id,
            "COMPLETED",
            now,
            now,
            1,
            1,
            1,
            0,
            "forge-ai",
            "src/GraphFixture.java",
            now,
            3,
            2,
            "[]",
        ]
        if "source_ids_json" in available:
            columns.append("source_ids_json")
            values.append('["forge-ai"]')
        if "engine_version" in available:
            columns.append("engine_version")
            values.append("GRAPH_V1")
        if "mode" in available:
            columns.append("mode")
            values.append("FULL")
        conn.execute(
            f"""
            INSERT OR REPLACE INTO analysis_jobs({", ".join(columns)})
            VALUES ({", ".join("?" for _ in columns)})
            """,
            values,
        )


def _seed_context_chunk(db_path: Path, content: str = "JarvisGateway context metadata") -> None:
    now = _now()
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES ('forge-ai', 'Forge AI', 'platform', '.', 1, '[]', '{}', ?)
            """,
            (now,),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO files(id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain, size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at)
            VALUES (1, 'forge-ai', '.', '.', 'src/GraphFixture.java', '.java', 'java', 'CODE', 100, 'hash-1', ?, 100, 'utf-8:replace', ?)
            """,
            (now, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO context_chunks(id, file_id, source_id, relative_path, content_hash, line_start, line_end, content, indexed_at)
            VALUES (1, 1, 'forge-ai', 'src/GraphFixture.java', 'hash-1', 1, 3, ?, ?)
            """,
            (content, now),
        )
        conn.execute("DELETE FROM context_chunks_fts WHERE rowid = 1")
        conn.execute(
            "INSERT INTO context_chunks_fts(rowid, content, source_id, relative_path, chunk_id) VALUES (1, ?, 'forge-ai', 'src/GraphFixture.java', 1)",
            (content,),
        )


def _read_current_endpoints(app) -> list[tuple[str, int, int, str, str]]:
    client = TestClient(app)
    overview = client.get("/api/v1/knowledge/overview")
    manifest_response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")
    context = client.post("/api/v1/knowledge/context", json={"query": "JarvisGateway", "includeContent": False})
    assert overview.status_code == 200
    assert manifest_response.status_code == 200
    assert context.status_code == 200
    manifest = manifest_response.json()
    revision = quote(manifest["graphRevision"])
    nodes = client.get(f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=20").json()
    node = client.get(f"/api/v1/knowledge/analysis/graph/node/{quote(nodes['items'][0]['id'])}?sourceId=forge-ai&graphRevision={revision}").json()
    edges = client.get(f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=20").json()
    edge = client.get(f"/api/v1/knowledge/analysis/graph/edge/{quote(edges['items'][0]['id'])}?sourceId=forge-ai&graphRevision={revision}").json()
    return [(manifest["snapshotId"], manifest["totalNodeCount"], manifest["totalEdgeCount"], node["snapshotId"], edge["snapshotId"])]


def _request_observed(client: TestClient, route: tuple[Any, ...], index: int) -> AsgiResponse:
    method, path, *payload = route
    headers = {"X-Correlation-Id": f"obs-knowledge-{index}"}
    if method == "GET":
        return client.get(path, headers=headers)
    return client.post(path, json=payload[0], headers=headers)


def _assert_observed_response(response: AsgiResponse, expected_correlation: str | None = None) -> Dict[str, float]:
    assert response.status_code < 500
    timing = response.headers["server-timing"]
    assert not any(segment in timing for segment in FORBIDDEN_TIMING_SEGMENTS)
    parsed = _parse_server_timing(timing)
    assert parsed["route"] > 0
    assert parsed["db"] >= 0
    assert parsed["queries"] >= 1
    assert int(response.headers["x-response-bytes"]) >= 0
    assert response.headers["x-route-key"]
    assert response.headers["x-correlation-id"]
    if expected_correlation is not None:
        assert response.headers["x-correlation-id"] == expected_correlation
    return parsed


def _parse_server_timing(value: str) -> Dict[str, float]:
    route = re.search(r"(?:^|,\s*)route;dur=([0-9]+(?:\.[0-9]+)?)", value)
    db = re.search(r"(?:^|,\s*)db;dur=([0-9]+(?:\.[0-9]+)?);desc=\"queries=([0-9]+)\"", value)
    assert route is not None, value
    assert db is not None, value
    return {"route": float(route.group(1)), "db": float(db.group(1)), "queries": float(db.group(2))}


def _table_counts(db_path: Path, tables: Iterable[str]) -> Dict[str, int]:
    with sqlite3.connect(db_path) as conn:
        return {
            table: int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            for table in tables
            if conn.execute("SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?", (table,)).fetchone()
        }


def _current_snapshots(db_path: Path) -> Dict[str, str]:
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute("SELECT source_id, snapshot_id FROM graph_current_snapshots ORDER BY source_id").fetchall()
    return {row[0]: row[1] for row in rows}


def _duplicate_count(db_path: Path, table: str, columns: str) -> int:
    with sqlite3.connect(db_path) as conn:
        return int(
            conn.execute(
                f"""
                SELECT COUNT(*)
                FROM (
                    SELECT {columns}, COUNT(*) AS count
                    FROM {table}
                    GROUP BY {columns}
                    HAVING count > 1
                )
                """
            ).fetchone()[0]
        )


def _wal_size(db_path: Path) -> int:
    wal = Path(f"{db_path}-wal")
    return wal.stat().st_size if wal.exists() else 0
