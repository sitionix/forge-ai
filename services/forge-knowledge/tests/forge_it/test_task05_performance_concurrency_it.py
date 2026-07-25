from __future__ import annotations

import concurrent.futures
import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Callable
from urllib.parse import quote

import pytest
from forge_it.test_graph_api import seed_graph_fixture, sqlite_integrity
from forge_it.test_task04_storage_observability_it import _assert_observed_response, _read_current_endpoints, _seed_context_chunk
from support import AsgiResponse
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config

from knowledge_service.main import _graph_view_response
from knowledge_service.overview_projection import read_overview

pytestmark = pytest.mark.forge_it


WARMUP_SAMPLES = 2
MEASURED_SAMPLES = 8


def test_perf_kno_01_overview_uses_kpi_projection_with_bounded_samples(tmp_path, monkeypatch):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=12, edge_count=11)

    file_reads: list[str] = []
    original_read_text = Path.read_text
    original_open = Path.open

    def guarded_read_text(self: Path, *args, **kwargs):
        file_reads.append(str(self))
        return original_read_text(self, *args, **kwargs)

    def guarded_open(self: Path, *args, **kwargs):
        file_reads.append(str(self))
        return original_open(self, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", guarded_read_text)
    monkeypatch.setattr(Path, "open", guarded_open)

    with TestClient(app) as client:
        samples = _sample_route(lambda: client.get("/api/v1/knowledge/overview"))

    assert not file_reads
    _assert_samples(samples, max_p50_ms=25, max_p95_ms=60, max_p99_ms=80, max_queries=20, max_db_ms=20, max_bytes=8192)
    assert all("/analysis/graph/" not in sample.route_key for sample in samples)


def test_perf_kno_02_context_fts_metadata_retrieval_is_bounded(tmp_path, monkeypatch):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    for index in range(20):
        _seed_context_chunk(app_config.store_path, content=f"JarvisGateway metadata chunk {index}")
    _mark_inventory_ready(app_config.store_path)

    calls: list[dict[str, object]] = []
    original = deps.inventory_store.search_context_chunks

    def wrapped_search(query, source_ids, groups, limit, include_content):
        calls.append({"limit": limit, "includeContent": include_content})
        return original(query, source_ids, groups, limit, include_content)

    monkeypatch.setattr(deps.inventory_store, "search_context_chunks", wrapped_search)
    with sqlite3.connect(app_config.store_path) as conn:
        plan = " ".join(
            str(item)
            for row in conn.execute(
                "EXPLAIN QUERY PLAN SELECT chunk_id FROM context_chunks_fts WHERE context_chunks_fts MATCH ? LIMIT 16",
                ("jarvis*",),
            ).fetchall()
            for item in row
        ).upper()

    with TestClient(app) as client:
        samples = _sample_route(
            lambda: client.post(
                "/api/v1/knowledge/context",
                json={"query": "JarvisGateway", "includeContent": False, "maxItems": 2, "maxChars": 1024},
            )
        )

    assert "VIRTUAL TABLE" in plan or "FTS" in plan
    assert calls and all(call == {"limit": 16, "includeContent": False} for call in calls)
    for sample in samples:
        body = json.loads(sample.response.body.decode("utf-8"))
        assert len(body["context"]) <= 2
        assert all(item.get("content") is None for item in body["context"])
        assert "metadata chunk" not in sample.response.body.decode("utf-8")
    _assert_samples(samples, max_p50_ms=35, max_p95_ms=80, max_p99_ms=120, max_queries=6, max_db_ms=35, max_bytes=8192)


def test_perf_kno_03_final_graph_routes_are_bounded_and_legacy_routes_absent(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=24, edge_count=23)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        revision = quote(manifest["graphRevision"])
        routes = [
            lambda: client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE"),
            lambda: client.get(f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=5"),
            lambda: client.get(f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&pageSize=5"),
            lambda: client.get(f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={revision}"),
            lambda: client.get(f"/api/v1/knowledge/analysis/graph/edge/edge-00000?sourceId=forge-ai&graphRevision={revision}"),
            lambda: client.get(f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={revision}&cursor=bad"),
        ]
        all_samples = []
        for route in routes:
            all_samples.extend(_sample_route(route, measured=4))
        assert client.get("/api/v1/knowledge/analysis/graph?sourceId=forge-ai").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/graph/slice?sourceId=forge-ai").status_code == 404

    for sample in all_samples:
        assert sample.response.status_code in {200, 400}
    _assert_samples(all_samples, max_p50_ms=45, max_p95_ms=110, max_p99_ms=160, max_queries=11, max_db_ms=60, max_bytes=32768)


def test_perf_kno_04_sqlite_writer_and_readers_keep_current_graph_visibility_bounded(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=6, edge_count=5)
    _seed_context_chunk(app_config.store_path)

    for reader_count in (1, 5, 10):
        started = threading.Event()
        release_writer = threading.Event()
        errors: list[str] = []
        latencies: list[float] = []
        observed: list[tuple[str, int, int, str, str]] = []

        def writer() -> None:
            try:
                with sqlite3.connect(app_config.store_path, timeout=5) as conn:
                    conn.execute("PRAGMA journal_mode=WAL")
                    conn.execute("BEGIN IMMEDIATE")
                    conn.execute(
                        "INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count) VALUES (datetime('now'), NULL, 'RUNNING', 1, 1, 0)"
                    )
                    started.set()
                    assert release_writer.wait(timeout=5)
                seed_graph_fixture(app_config.store_path, node_count=4, edge_count=3, graph_suffix=f"writer-{reader_count}")
                deps.storage_operations.run_maintenance(checkpoint_mode="PASSIVE", run_optimize=False)
            except Exception as exc:
                errors.append(str(exc))
                started.set()

        def reader() -> list[tuple[str, int, int, str, str]]:
            before = time.perf_counter()
            result = _read_current_endpoints(app)
            latencies.append((time.perf_counter() - before) * 1000)
            return result

        thread = threading.Thread(target=writer)
        thread.start()
        assert started.wait(timeout=5)

        with concurrent.futures.ThreadPoolExecutor(max_workers=reader_count) as executor:
            futures = [executor.submit(reader) for _ in range(reader_count)]
            for future in futures:
                observed.extend(future.result(timeout=10))
        release_writer.set()
        thread.join(timeout=10)

        assert not thread.is_alive()
        assert not errors
        assert latencies and _percentile(latencies, 95) < 500
        assert not any("database is locked" in error.lower() for error in errors)
        assert {row[0] for row in observed}
        for graph_id, manifest_nodes, manifest_edges, node_graph_id, edge_graph_id in observed:
            if graph_id == "STALE":
                assert manifest_nodes == manifest_edges == 0
                assert node_graph_id == edge_graph_id == "GRAPH_REVISION_STALE"
                continue
            assert node_graph_id == graph_id
            assert edge_graph_id == graph_id
            assert (manifest_nodes, manifest_edges) in {(6, 5), (4, 3)}

    assert sqlite_integrity(app_config.store_path) == ("ok", [])


def test_ui_nav_real_04_backend_overview_returns_while_graph_view_is_still_running(tmp_path, monkeypatch):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=360, edge_count=720)

    graph_started = threading.Event()
    graph_finished = threading.Event()
    release_graph = threading.Event()
    original_graph_view = deps.analysis_store.graph_view

    def slow_graph_view(*args, **kwargs):
        graph_started.set()
        try:
            release_graph.wait(timeout=1)
            return original_graph_view(*args, **kwargs)
        finally:
            graph_finished.set()

    monkeypatch.setattr(deps.analysis_store, "graph_view", slow_graph_view)

    graph_result: dict[str, object] = {}
    graph_errors: list[BaseException] = []

    def graph_worker() -> None:
        try:
            graph_result["response"] = _graph_view_response(
                deps.analysis_store,
                "forge-ai",
                "CODE",
                None,
                None,
                None,
                "show",
                True,
                True,
                None,
                500,
            )
        except BaseException as exc:
            graph_errors.append(exc)

    thread = threading.Thread(target=graph_worker)
    thread.start()
    assert graph_started.wait(timeout=1)

    overview_started = time.perf_counter()
    overview_payload = read_overview(deps.inventory_store.db_path)
    overview_ms = (time.perf_counter() - overview_started) * 1000
    graph_still_running = not graph_finished.is_set()

    release_graph.set()
    thread.join(timeout=2)

    assert not thread.is_alive()
    assert not graph_errors
    graph_response = graph_result["response"]

    assert isinstance(overview_payload.get("sources"), list)
    assert graph_response.status_code == 200
    assert graph_still_running
    assert overview_ms < 150


class _RouteSample:
    def __init__(self, response: AsgiResponse) -> None:
        self.response = response
        self.metrics = _assert_observed_response(response)
        self.route_ms = self.metrics["route"]
        self.db_ms = self.metrics["db"]
        self.queries = self.metrics["queries"]
        self.bytes = int(response.headers["x-response-bytes"])
        self.route_key = response.headers["x-route-key"]


def _sample_route(factory: Callable[[], AsgiResponse], measured: int = MEASURED_SAMPLES) -> list[_RouteSample]:
    for _ in range(WARMUP_SAMPLES):
        response = factory()
        assert response.status_code < 500
    return [_RouteSample(factory()) for _ in range(measured)]


def _assert_samples(
    samples: list[_RouteSample],
    *,
    max_p50_ms: float,
    max_p95_ms: float,
    max_p99_ms: float,
    max_queries: int,
    max_db_ms: float,
    max_bytes: int,
) -> None:
    durations = [sample.route_ms for sample in samples]
    assert _percentile(durations, 50) <= max_p50_ms
    assert _percentile(durations, 95) <= max_p95_ms
    assert _percentile(durations, 99) <= max_p99_ms
    assert max(sample.queries for sample in samples) <= max_queries
    assert max(sample.db_ms for sample in samples) <= max_db_ms
    assert max(sample.bytes for sample in samples) <= max_bytes


def _percentile(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0
    index = min(len(ordered) - 1, int(round((percentile / 100) * (len(ordered) - 1))))
    return ordered[index]


def _mark_inventory_ready(db_path: Path) -> None:
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            INSERT INTO inventory_builds(started_at, completed_at, status, source_count, file_count, skipped_count, skipped_reasons_json)
            VALUES (datetime('now'), datetime('now'), 'COMPLETED', 1, 1, 0, '{}')
            """
        )
