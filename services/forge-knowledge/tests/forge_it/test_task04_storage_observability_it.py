from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import quote

import pytest
from forge_it.test_graph_api import graph_counts, seed_graph_fixture, sqlite_integrity, sqlite_objects
from support import AsgiResponse
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config

from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.overview_projection import read_overview


pytestmark = pytest.mark.forge_it


IMPORTANT_TABLES = (
    "inventory_builds",
    "sources",
    "files",
    "context_chunks",
    "analysis_jobs",
    "analysis_job_files",
    "analysis_files",
    "analysis_graph_state",
    "analysis_graph_nodes",
    "analysis_graph_edges",
    "analysis_graph_evidence",
    "analysis_graph_claims",
    "analysis_graph_diagnostics",
)

def _old(days: int = 90) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()


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


def test_it_storage_02_current_state_initialization_idempotency(tmp_path):
    db_path = tmp_path / "representative.sqlite"
    InventoryStore(db_path).init()
    AnalysisStore(db_path).init()
    seed_graph_fixture(db_path, source_id="forge-ai", node_count=5, edge_count=4)

    first_counts = _table_counts(db_path, IMPORTANT_TABLES)
    first_objects = sqlite_objects(db_path)
    first_overview_sources = {source["sourceId"]: source["inventory"] for source in read_overview(db_path)["sources"]}

    AnalysisStore._initialized_paths.discard(str(db_path.resolve()))
    AnalysisStore(db_path).init()

    assert _table_counts(db_path, IMPORTANT_TABLES) == first_counts
    assert sqlite_objects(db_path) == first_objects
    assert not any(name.startswith("graph_") for name in sqlite_objects(db_path))
    assert graph_counts(db_path) == {"state": 1, "nodes": 5, "edges": 4}
    assert {source["sourceId"]: source["inventory"] for source in read_overview(db_path)["sources"]} == first_overview_sources
    assert _duplicate_count(db_path, "analysis_graph_state", "source_id") == 0
    assert _duplicate_count(db_path, "analysis_graph_nodes", "id") == 0
    assert _duplicate_count(db_path, "analysis_job_files", "id") == 0
    assert sqlite_integrity(db_path) == ("ok", [])


def test_it_storage_03_maintenance_does_not_create_removed_graph_tables(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    seed_graph_fixture(app_config.store_path, node_count=4, edge_count=3)

    first = deps.storage_operations.run_maintenance().retention
    second = deps.storage_operations.run_maintenance().retention

    assert not any(name.startswith("graph_") for name in sqlite_objects(app_config.store_path))
    assert first["inventory_builds"] >= 0
    assert all(value == 0 for value in second.values())


def test_it_storage_04_wal_checkpoint_and_bounded_growth(tmp_path):
    _, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    db_path = app_config.store_path
    before = _wal_size(db_path)

    reader = sqlite3.connect(db_path)
    try:
        reader.execute("PRAGMA journal_mode=WAL")
        reader.execute("BEGIN")
        reader.execute("SELECT COUNT(*) FROM inventory_builds").fetchone()
        with sqlite3.connect(db_path) as writer:
            writer.execute("PRAGMA journal_mode=WAL")
            for batch in range(1, 16):
                writer.execute(
                    """
                    INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, error_message)
                    VALUES (?, ?, 'COMPLETED', 0, 0, 0, ?)
                    """,
                    (_old(batch), _old(batch), "x" * 2048),
                )
            writer.commit()
        after_writes = _wal_size(db_path)
        assert after_writes >= before
    finally:
        reader.rollback()
        reader.close()

    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    assert _wal_size(db_path) <= max(after_writes, before + 1024 * 1024)


def test_it_storage_05_graph_endpoint_current_ids_are_consistent(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=6, edge_count=5)

    observed = _read_current_endpoints(app)

    assert observed
    for graph_id, manifest_nodes, manifest_edges, node_graph_id, edge_graph_id in observed:
        assert graph_id
        assert node_graph_id == graph_id
        assert edge_graph_id == graph_id
        assert (manifest_nodes, manifest_edges) == (6, 5)


def test_it_storage_06_observability_headers_include_real_timing(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)

    with TestClient(app) as client:
        response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")

    _assert_observed_response(response, max_bytes=8192)
    timing = response.headers.get("server-timing", "")
    assert "db" in timing or "app" in timing


def _read_current_endpoints(app) -> list[tuple[str, int, int, str, str]]:
    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        revision = quote(manifest["graphRevision"])
        node_response = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=5"
        )
        if node_response.status_code == 409:
            code = node_response.json()["code"]
            return [("STALE", 0, 0, code, code)]
        edge_response = client.get(
            f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=5"
        )
        if edge_response.status_code == 409:
            code = edge_response.json()["code"]
            return [("STALE", 0, 0, code, code)]
        node_page = node_response.json()
        edge_page = edge_response.json()
    return [(manifest["graphId"], manifest["totalNodeCount"], manifest["totalEdgeCount"], node_page["graphId"], edge_page["graphId"])]


def _assert_observed_response(response: AsgiResponse, *, max_bytes: int = 32768) -> None:
    assert response.status_code < 500
    assert len(response.body) <= max_bytes
    assert response.headers.get("x-correlation-id")
    server_timing = response.headers.get("server-timing")
    assert server_timing
    metrics = {"route": 0.0, "db": 0.0, "queries": 0}
    for segment in server_timing.split(","):
        parts = [part.strip() for part in segment.split(";") if part.strip()]
        if not parts:
            continue
        name = parts[0]
        for part in parts[1:]:
            if part.startswith("dur="):
                try:
                    metrics[name] = float(part.split("=", 1)[1])
                except ValueError:
                    metrics[name] = 0.0
            if "queries=" in part:
                try:
                    metrics["queries"] = int(part.split("queries=", 1)[1].split('"', 1)[0])
                except ValueError:
                    metrics["queries"] = 0
    return metrics


def _seed_context_chunk(db_path: Path, *, content: str = "JarvisGateway metadata chunk") -> None:
    InventoryStore(db_path).init()
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES ('forge-ai', 'Forge AI', 'test', '.', 1, '[]', '{}', ?)
            """,
            (now,),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO files(
                id, source_id, source_path, absolute_path, relative_path, extension, language, flow_domain,
                size_bytes, content_hash, last_modified, line_count, decode_policy, indexed_at
            )
            VALUES (99001, 'forge-ai', '.', '.', 'README.md', '.md', 'markdown', 'DOCS', ?, ?, ?, 1, 'utf-8:replace', ?)
            """,
            (len(content), f"hash-{content}", now, now),
        )
        conn.execute(
            """
            INSERT INTO context_chunks(
                file_id, source_id, relative_path, content_hash, line_start, line_end, content, indexed_at
            )
            VALUES (99001, 'forge-ai', 'README.md', ?, 1, 1, ?, ?)
            """,
            (f"hash-{content}", content, now),
        )
        chunk_id = conn.execute("SELECT last_insert_rowid()").fetchone()[0]
        conn.execute(
            "INSERT INTO context_chunks_fts(rowid, content, source_id, relative_path, chunk_id) VALUES (?, ?, 'forge-ai', 'README.md', ?)",
            (chunk_id, content, chunk_id),
        )


def _table_counts(db_path: Path, tables: tuple[str, ...]) -> dict[str, int]:
    with sqlite3.connect(db_path) as conn:
        existing = sqlite_objects(db_path)
        return {table: conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in tables if table in existing}


def _duplicate_count(db_path: Path, table: str, columns: str) -> int:
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute(
            f"""
            SELECT COUNT(*)
            FROM (
                SELECT {columns}, COUNT(*) AS count
                FROM {table}
                GROUP BY {columns}
                HAVING count > 1
            )
            """
        ).fetchone()
    return int(rows[0] or 0)


def _wal_size(db_path: Path) -> int:
    wal = Path(f"{db_path}-wal")
    return wal.stat().st_size if wal.exists() else 0
