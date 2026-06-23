from __future__ import annotations

import sqlite3
import asyncio
import time
from typing import Dict, Optional

import pytest
from support import AsgiTestClient as TestClient
from pydantic import ValidationError

from knowledge_service.config import load_forge_settings
from knowledge_service.errors import KnowledgeError
from knowledge_service.graph_schema import GraphAnalysisResult
from support import (
    DeterministicAnalysisProvider,
    FailingAnalysisProvider,
    build_test_app,
    sqlite_table_counts,
    write_runtime_config,
)

pytestmark = pytest.mark.forge_it


PUBLIC_ENDPOINTS = {
    ("GET", "/health"),
    ("GET", "/api/v1/knowledge/status"),
    ("GET", "/api/v1/knowledge/sources"),
    ("POST", "/api/v1/knowledge/inventory/build"),
    ("GET", "/api/v1/knowledge/inventory/status"),
    ("GET", "/api/v1/knowledge/inventory/files"),
    ("POST", "/api/v1/knowledge/context"),
    ("POST", "/api/v1/knowledge/analysis/build"),
    ("POST", "/api/v1/knowledge/analysis/retry-failed"),
    ("GET", "/api/v1/knowledge/analysis/jobs/{job_id}"),
    ("POST", "/api/v1/knowledge/analysis/jobs/{job_id}/stop"),
    ("GET", "/api/v1/knowledge/analysis/status"),
    ("GET", "/api/v1/knowledge/overview"),
    ("GET", "/api/v1/knowledge/analysis/files"),
    ("GET", "/api/v1/knowledge/analysis/diagnostics"),
    ("GET", "/api/v1/knowledge/analysis/graph/manifest"),
    ("GET", "/api/v1/knowledge/analysis/graph/nodes"),
    ("GET", "/api/v1/knowledge/analysis/graph/edges"),
    ("GET", "/api/v1/knowledge/analysis/graph/node/{node_id}"),
    ("GET", "/api/v1/knowledge/analysis/graph/edge/{edge_id}"),
}


class CountingFailingAnalysisProvider:
    name = "deterministic-test"
    version = "1.0"

    def __init__(self) -> None:
        self.calls = 0

    def analyze(
        self,
        payload: Dict[str, object],
        line_count: int,
        repair_prompt: Optional[str] = None,
    ) -> GraphAnalysisResult:
        self.calls += 1
        raise KnowledgeError(
            "ANALYSIS_AI_TRANSPORT_ERROR",
            "deterministic provider failure",
            stage="AI_CALL",
            severity="ERROR",
            raw_preview="provider failed",
        )


class HoldingAnalysisProvider(DeterministicAnalysisProvider):
    def __init__(self) -> None:
        self.release = asyncio.Event()

    async def analyze(self, payload: Dict[str, object], line_count: int, repair_prompt: Optional[str] = None) -> GraphAnalysisResult:
        await self.release.wait()
        result = super().analyze(payload, line_count, repair_prompt)
        return result


def public_routes(app):
    routes = set()
    for route in app.routes:
        path = getattr(route, "path", None)
        methods = getattr(route, "methods", set()) or set()
        if not path or path in {"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"}:
            continue
        for method in methods:
            if method not in {"HEAD", "OPTIONS"}:
                routes.add((method, path))
    return routes


def wait_job(client: TestClient, job_id: str, *terminal: str, timeout: float = 3.0) -> Dict[str, object]:
    expected = set(terminal or ("COMPLETED", "FAILED", "STOPPED"))
    deadline = time.monotonic() + timeout
    last: Dict[str, object] = {}
    while time.monotonic() < deadline:
        last = client.get(f"/api/v1/knowledge/analysis/jobs/{job_id}").json()
        if last.get("status") in expected:
            return last
        client._run(asyncio.sleep(0.01))
    return last


def wait_active_overview(client: TestClient, job_id: str, timeout: float = 3.0) -> Dict[str, object]:
    deadline = time.monotonic() + timeout
    last: Dict[str, object] = {}
    while time.monotonic() < deadline:
        last = client.get("/api/v1/knowledge/overview").json()
        if (last.get("activeJob") or {}).get("jobId") == job_id:
            return last
        client._run(asyncio.sleep(0.01))
    return last


def test_route_inventory_matches_manifest(tmp_path):
    app, *_ = build_test_app(write_runtime_config(tmp_path))

    assert public_routes(app) == PUBLIC_ENDPOINTS


def test_successful_startup_from_root_config_and_invalid_config_failure(tmp_path):
    config_file = write_runtime_config(tmp_path)
    app, *_ = build_test_app(config_file)

    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP"}

    bad = config_file.read_text(encoding="utf-8").replace("port: 7081", "port: 70000")
    config_file.write_text(bad, encoding="utf-8")
    with pytest.raises(ValidationError):
        load_forge_settings(
            config_file=config_file,
            environ={
                "FORGE_AI_HOME": str(tmp_path),
                "FORGE_CONFIG_DIR": str(tmp_path / "config"),
                "FORGE_RUNTIME_DIR": str(tmp_path / "var"),
                "FORGE_WORKSPACE_ROOT": str(tmp_path / "workspace"),
            },
        )


def test_inventory_context_analysis_and_sqlite_persistence(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path, max_file_size_bytes=500))

    with TestClient(app) as client:
        status = client.get("/api/v1/knowledge/status").json()
        assert status["inventory"]["status"] == "EMPTY"

        sources = client.get("/api/v1/knowledge/sources").json()
        assert sources["sources"][0]["sourceId"] == "forge-ai"

        inventory = client.post("/api/v1/knowledge/inventory/build", json={}).json()
        assert inventory["status"] == "COMPLETED"
        assert inventory["sourceCount"] == 1
        assert inventory["fileCount"] == 2
        assert inventory["skippedCount"] >= 1

        files = client.get("/api/v1/knowledge/inventory/files?extension=.java").json()
        assert files["total"] == 1
        assert files["files"][0]["relativePath"].endswith("JarvisGateway.java")

        filtered = client.get("/api/v1/knowledge/inventory/files?pathContains=README").json()
        assert filtered["total"] == 1

        context = client.post("/api/v1/knowledge/context", json={"query": "JarvisGateway", "maxChars": 1000}).json()
        assert context["context"][0]["sourceId"] == "forge-ai"

        build = client.post("/api/v1/knowledge/analysis/build", json={"sourceIds": ["forge-ai"], "force": True}).json()
        job = wait_job(client, build["jobId"], "COMPLETED")
        assert job["status"] == "COMPLETED"
        assert job["processedFileCount"] == 2

        analysis_status = client.get("/api/v1/knowledge/analysis/status").json()
        assert analysis_status["status"] == "READY"
        assert analysis_status["symbolCount"] > 0

        services = client.get("/api/v1/knowledge/overview").json()
        assert services["sources"][0]["analysis"]["succeededFiles"] == 2

        analysis_files = client.get("/api/v1/knowledge/analysis/files").json()
        assert analysis_files["total"] == 2

        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai").json()
        assert manifest["totalNodeCount"] > 0
        nodes = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&graphRevision={manifest['graphRevision']}&pageSize=10"
        ).json()
        assert nodes["items"]
        edges = client.get(
            f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&graphRevision={manifest['graphRevision']}&pageSize=10"
        ).json()
        assert "items" in edges
        node_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/node/{nodes['items'][0]['id']}?sourceId=forge-ai&graphRevision={manifest['graphRevision']}&includeEvidence=true"
        ).json()
        assert node_detail["item"]["id"] == nodes["items"][0]["id"]
        assert client.get("/api/v1/knowledge/analysis/symbols").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/relations").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/graph?sourceId=forge-ai").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/graph/slice?sourceId=forge-ai").status_code == 404

        validation_error = client.post("/api/v1/knowledge/context", json={"query": "x", "maxChars": 1})
        assert validation_error.status_code == 422

    counts = sqlite_table_counts(
        app_config.store_path,
        [
            "sources",
            "files",
            "inventory_builds",
            "analysis_jobs",
            "analysis_job_files",
            "analysis_files",
            "analysis_graph_nodes",
            "analysis_graph_edges",
            "analysis_graph_evidence",
            "analysis_graph_diagnostics",
        ],
    )
    assert counts["sources"] == 1
    assert counts["files"] == 2
    assert counts["analysis_jobs"] == 1
    assert counts["analysis_files"] == 2
    assert counts["analysis_graph_nodes"] > 0
    assert counts["analysis_graph_evidence"] > 0
    assert counts["analysis_graph_diagnostics"] > 0

    restarted, *_ = build_test_app(write_runtime_config(tmp_path, max_file_size_bytes=500))
    with TestClient(restarted) as client:
        assert client.get("/api/v1/knowledge/inventory/status").json()["fileCount"] == 2
        assert client.get("/api/v1/knowledge/analysis/status").json()["symbolCount"] == counts["analysis_graph_nodes"]


def test_analysis_build_skips_current_analyzed_files_with_diagnostics(tmp_path):
    provider = CountingFailingAnalysisProvider()
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path), provider=provider)

    with TestClient(app) as client:
        assert client.post("/api/v1/knowledge/inventory/build", json={}).status_code == 200
        first_build = client.post("/api/v1/knowledge/analysis/build", json={"sourceIds": ["forge-ai"], "force": False}).json()
        first_job = wait_job(client, first_build["jobId"], "COMPLETED")
        assert first_job["status"] == "COMPLETED"
        assert first_job["processedFileCount"] == 2
        assert provider.calls == 2

        second_build = client.post("/api/v1/knowledge/analysis/build", json={"sourceIds": ["forge-ai"], "force": False}).json()
        second_job = wait_job(client, second_build["jobId"], "COMPLETED")
        assert second_job["status"] == "COMPLETED"
        assert second_job["fileCount"] == 0
        assert second_job["processedFileCount"] == 0
        assert provider.calls == 2

        services = client.get("/api/v1/knowledge/overview").json()
        assert services["sources"][0]["analysis"]["succeededFiles"] == 2
        assert services["sources"][0]["analysis"]["pendingFiles"] == 0

    with sqlite3.connect(app_config.store_path) as conn:
        rows = conn.execute("SELECT status, last_error_code FROM analysis_files ORDER BY relative_path").fetchall()
    assert [row[0] for row in rows] == ["ANALYZED", "ANALYZED"]
    assert all(row[1] for row in rows)


def test_analysis_build_runs_pending_files_through_provider_after_skipping_current_files(tmp_path):
    provider = CountingFailingAnalysisProvider()
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path), provider=provider)

    with TestClient(app) as client:
        assert client.post("/api/v1/knowledge/inventory/build", json={}).status_code == 200

        first_build = client.post(
            "/api/v1/knowledge/analysis/build",
            json={"sourceIds": ["forge-ai"], "force": False, "maxFiles": 1},
        ).json()
        first_job = wait_job(client, first_build["jobId"], "COMPLETED")
        assert first_job["status"] == "COMPLETED"
        assert first_job["fileCount"] == 1
        assert first_job["processedFileCount"] == 1
        assert provider.calls == 1

        second_build = client.post(
            "/api/v1/knowledge/analysis/build",
            json={"sourceIds": ["forge-ai"], "force": False},
        ).json()
        second_job = wait_job(client, second_build["jobId"], "COMPLETED")
        assert second_job["status"] == "COMPLETED"
        assert second_job["fileCount"] == 1
        assert second_job["processedFileCount"] == 1
        assert provider.calls == 2

        third_build = client.post(
            "/api/v1/knowledge/analysis/build",
            json={"sourceIds": ["forge-ai"], "force": False},
        ).json()
        third_job = wait_job(client, third_build["jobId"], "COMPLETED")
        assert third_job["status"] == "COMPLETED"
        assert third_job["fileCount"] == 0
        assert provider.calls == 2

        services = client.get("/api/v1/knowledge/overview").json()
        assert services["sources"][0]["analysis"]["succeededFiles"] == 2
        assert services["sources"][0]["analysis"]["pendingFiles"] == 0

    with sqlite3.connect(app_config.store_path) as conn:
        second_job_files = conn.execute(
            "SELECT status FROM analysis_job_files WHERE job_id = ?",
            (second_build["jobId"],),
        ).fetchall()
    assert [row[0] for row in second_job_files] == ["ANALYZED_WITH_DIAGNOSTICS"]


def test_analysis_build_is_visible_as_active_job_before_worker_runs(tmp_path):
    provider = HoldingAnalysisProvider()
    app, *_ = build_test_app(write_runtime_config(tmp_path), provider=provider)

    with TestClient(app) as client:
        assert client.post("/api/v1/knowledge/inventory/build", json={}).status_code == 200
        build = client.post("/api/v1/knowledge/analysis/build", json={"sourceIds": ["forge-ai"], "force": False}).json()

        job = client.get(f"/api/v1/knowledge/analysis/jobs/{build['jobId']}").json()
        assert job["status"] in {"QUEUED", "RUNNING"}
        provider.release.set()
        completed = wait_job(client, build["jobId"], "COMPLETED")
        assert completed["status"] == "COMPLETED"


def test_stopped_job_state_and_idempotent_schema_migration(tmp_path):
    provider = HoldingAnalysisProvider()
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path), provider=provider)

    with TestClient(app) as client:
        assert client.post("/api/v1/knowledge/inventory/build", json={}).status_code == 200
        build = client.post("/api/v1/knowledge/analysis/build", json={"force": True}).json()
        stop = client.post(f"/api/v1/knowledge/analysis/jobs/{build['jobId']}/stop", json={}).json()
        assert stop["status"] == "STOP_REQUESTED"
        job = wait_job(client, build["jobId"], "STOPPED")
        assert job["status"] == "STOPPED"

    deps.inventory_store.init()
    deps.analysis_store.init()
    with sqlite3.connect(app_config.store_path) as conn:
        assert conn.execute("SELECT COUNT(*) FROM analysis_jobs").fetchone()[0] == 1


def test_failed_file_and_provider_failure_diagnostics_are_persisted(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path), provider=FailingAnalysisProvider())

    with TestClient(app) as client:
        assert client.post("/api/v1/knowledge/inventory/build", json={}).status_code == 200
        build = client.post("/api/v1/knowledge/analysis/build", json={"force": True}).json()
        job = wait_job(client, build["jobId"], "COMPLETED")
        assert job["status"] == "COMPLETED"
        files = client.get("/api/v1/knowledge/analysis/files").json()
        assert files["files"][0]["diagnostics"]

    with sqlite3.connect(app_config.store_path) as conn:
        diagnostics = conn.execute("SELECT diagnostics_json FROM analysis_files").fetchone()[0]
    assert "ANALYSIS_AI_TRANSPORT_ERROR" in diagnostics


def test_missing_indexed_file_reports_failed_analysis_state(tmp_path):
    provider = HoldingAnalysisProvider()
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path), provider=provider)

    with TestClient(app) as client:
        client.post("/api/v1/knowledge/inventory/build", json={})
        indexed = client.get("/api/v1/knowledge/inventory/files?extension=.java").json()["files"][0]
        build = client.post("/api/v1/knowledge/analysis/build", json={"force": True}).json()
        (tmp_path / "workspace" / "forge-ai" / indexed["relativePath"]).unlink()
        provider.release.set()
        job = wait_job(client, build["jobId"], "COMPLETED")
        assert job["failedFileCount"] == 1

    with sqlite3.connect(app_config.store_path) as conn:
        row = conn.execute("SELECT status, diagnostics_json FROM analysis_files WHERE status = 'FAILED'").fetchone()
    assert row[0] == "FAILED"
    assert "FILE_UNREADABLE" in row[1]


def test_dependency_error_maps_to_controlled_api_error(tmp_path):
    config_file = write_runtime_config(tmp_path)
    (tmp_path / "config" / "knowledge" / "knowledge-sources.yaml").write_text("catalog: {}\n", encoding="utf-8")
    app, *_ = build_test_app(config_file)

    with TestClient(app) as client:
        response = client.post("/api/v1/knowledge/inventory/build", json={})

    assert response.status_code == 400
    assert response.json()["code"] == "KNOWLEDGE_CONFIG_INVALID"
