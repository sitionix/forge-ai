from __future__ import annotations

import base64
import json
import sqlite3
import threading
from datetime import datetime, timezone
from urllib.parse import quote

import pytest
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config
from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.errors import KnowledgeError


pytestmark = pytest.mark.forge_it


LEGACY_GRAPH_OBJECTS = {"analysis_symbols", "analysis_symbol_roles", "analysis_relations"}


def test_it_graph_01_fresh_database_schema_is_snapshot_only(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()

    objects = sqlite_objects(app_config.store_path)

    assert LEGACY_GRAPH_OBJECTS.isdisjoint(objects)
    assert {"graph_snapshots", "graph_current_snapshots", "analysis_graph_nodes", "analysis_graph_edges"}.issubset(objects)
    assert_source_snapshot_constraints_reject_wrong_source(app_config.store_path)


def test_it_graph_02_representative_legacy_migration_preserves_multi_source_graphs(tmp_path):
    db_path = tmp_path / "legacy.sqlite"
    create_legacy_symbol_relation_fixture(db_path)

    store = AnalysisStore(db_path)
    store.init()

    assert graph_counts(db_path) == {"snapshots": 2, "nodes": 5, "edges": 3, "current": 2}
    assert LEGACY_GRAPH_OBJECTS.isdisjoint(sqlite_objects(db_path))
    assert current_snapshot(db_path, "legacy-source") == "legacy-symbols:legacy-source"
    assert current_snapshot(db_path, "config-source") == "legacy-symbols:config-source"
    assert snapshot_metric_count(db_path, "legacy-symbols:legacy-source") > 0
    assert snapshot_metric_count(db_path, "legacy-symbols:config-source") > 0
    assert migrated_claim_count(db_path) == 3
    assert edge_endpoint_violations(db_path) == 0
    assert snapshot_state(db_path, "partial:legacy-source") is None
    assert_source_snapshot_constraints_reject_wrong_source(db_path)
    assert sqlite_integrity(db_path) == ("ok", [])


def test_it_graph_03_migration_idempotency_creates_no_duplicate_snapshots_rows_or_pointers(tmp_path):
    db_path = tmp_path / "legacy-idempotent.sqlite"
    create_legacy_symbol_relation_fixture(db_path)

    AnalysisStore(db_path).init()
    first_counts = graph_counts(db_path)
    first_objects = sqlite_objects(db_path)

    AnalysisStore._initialized_paths.discard(str(db_path.resolve()))
    AnalysisStore(db_path).init()

    assert graph_counts(db_path) == first_counts
    assert sqlite_objects(db_path) == first_objects
    assert LEGACY_GRAPH_OBJECTS.isdisjoint(sqlite_objects(db_path))
    assert sqlite_integrity(db_path) == ("ok", [])


@pytest.mark.parametrize(
    "stage",
    ["after_canonical_schema", "after_legacy_copy", "before_current_activation", "after_pointer_mutation"],
)
def test_it_graph_04_migration_rolls_back_each_fault_stage_and_retries_successfully(tmp_path, stage):
    rollback_db = tmp_path / f"rollback-{stage}.sqlite"
    create_legacy_symbol_relation_fixture(rollback_db)
    original_legacy_rows = legacy_counts(rollback_db)

    AnalysisStore._migration_fault_stage = stage
    try:
        with pytest.raises(RuntimeError):
            AnalysisStore(rollback_db).init()
    finally:
        AnalysisStore._migration_fault_stage = None

    assert legacy_counts(rollback_db) == original_legacy_rows
    assert LEGACY_GRAPH_OBJECTS.issubset(sqlite_objects(rollback_db))
    assert current_snapshot(rollback_db, "legacy-source") == "partial:legacy-source"
    assert current_snapshot(rollback_db, "config-source") is None
    assert sqlite_integrity(rollback_db) == ("ok", [])

    AnalysisStore._initialized_paths.discard(str(rollback_db.resolve()))
    AnalysisStore(rollback_db).init()

    assert graph_counts(rollback_db) == {"snapshots": 2, "nodes": 5, "edges": 3, "current": 2}
    assert LEGACY_GRAPH_OBJECTS.isdisjoint(sqlite_objects(rollback_db))
    assert edge_endpoint_violations(rollback_db) == 0
    assert sqlite_integrity(rollback_db) == ("ok", [])


def test_it_graph_05_unpublished_snapshot_invisible_to_current_api(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=4, edge_count=3)
    insert_graph_snapshot(app_config.store_path, "forge-ai", "job-2:forge-ai", 8, 7, state="BUILDING")

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        nodes = traverse_pages(client, "nodes", manifest["graphRevision"], 10)
        edges = traverse_pages(client, "edges", manifest["graphRevision"], 10)

    assert manifest["snapshotId"] == "job-1:forge-ai"
    assert len(nodes) == 4
    assert len(edges) == 3


def test_it_graph_06_atomic_activation_with_concurrent_readers(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=8, edge_count=7)
    insert_graph_snapshot(app_config.store_path, "forge-ai", "job-2:forge-ai", 5, 4, state="BUILDING")
    observed = []

    with TestClient(app) as client:
        def read_loop():
            for _ in range(20):
                manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
                node_page = client.get(
                    f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&pageSize=20"
                ).json()
                edge_page = client.get(
                    f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&pageSize=20"
                ).json()
                node_detail = client.get(
                    f"/api/v1/knowledge/analysis/graph/node/{quote(node_page['items'][0]['id'])}?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
                ).json()
                edge_detail = client.get(
                    f"/api/v1/knowledge/analysis/graph/edge/{quote(edge_page['items'][0]['id'])}?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
                ).json()
                observed.append(
                    (
                        manifest["snapshotId"],
                        manifest["totalNodeCount"],
                        manifest["totalEdgeCount"],
                        node_page["snapshotId"],
                        len(node_page["items"]),
                        edge_page["snapshotId"],
                        len(edge_page["items"]),
                        node_detail["snapshotId"],
                        edge_detail["snapshotId"],
                    )
                )

        reader = threading.Thread(target=read_loop)
        reader.start()
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn: AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, "job-2:forge-ai"))
        reader.join()

    assert observed
    assert set(observed).issubset({
        ("job-1:forge-ai", 8, 7, "job-1:forge-ai", 8, "job-1:forge-ai", 7, "job-1:forge-ai", "job-1:forge-ai"),
        ("job-2:forge-ai", 5, 4, "job-2:forge-ai", 5, "job-2:forge-ai", 4, "job-2:forge-ai", "job-2:forge-ai"),
    })
    with TestClient(app) as client:
        final_manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
    assert final_manifest["snapshotId"] == "job-2:forge-ai"


def test_it_graph_07_failed_validation_preserves_current(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)
    insert_graph_snapshot(app_config.store_path, "forge-ai", "bad:forge-ai", 2, 0, state="BUILDING")
    with sqlite3.connect(app_config.store_path) as conn:
        conn.execute("PRAGMA foreign_keys = OFF")
        conn.execute(
            """
            INSERT INTO analysis_graph_edges(id, snapshot_id, job_id, source_id, from_node_id, to_node_id, edge_type, resolution_status, confidence, metadata_json, status, created_at, fact_origin, flow_domain)
            VALUES ('bad-edge', 'bad:forge-ai', 'bad', 'forge-ai', 'missing', 'node-00000', 'CALLS', 'RESOLVED', 1.0, '{}', 'TRUSTED', ?, 'STATIC', 'CODE')
            """,
            (datetime.now(timezone.utc).isoformat(),),
        )
    with pytest.raises(KnowledgeError):
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn: AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, "bad:forge-ai"))

    assert current_snapshot(app_config.store_path, "forge-ai") == "job-1:forge-ai"


def test_it_graph_08_interruption_recovery_keeps_current(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)
    insert_graph_snapshot(app_config.store_path, "forge-ai", "running:forge-ai", 2, 1, state="BUILDING", job_id="running")
    deps.analysis_store.create_job({"jobId": "running", "status": "RUNNING", "sourceCount": 1, "fileCount": 1, "sourceIds": ["forge-ai"]})

    deps.analysis_store.mark_interrupted_jobs()

    assert current_snapshot(app_config.store_path, "forge-ai") == "job-1:forge-ai"
    assert snapshot_state(app_config.store_path, "running:forge-ai") == "FAILED"


def test_it_graph_09_activation_idempotency_and_stale_race(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=2, edge_count=1)
    insert_graph_snapshot(app_config.store_path, "forge-ai", "b:forge-ai", 3, 2, state="BUILDING")
    store = AnalysisStore(app_config.store_path)
    store._write_with_busy_retry(lambda conn: store._publish_graph_snapshot(conn, "b:forge-ai"))
    store._write_with_busy_retry(lambda conn: store._publish_graph_snapshot(conn, "b:forge-ai"))
    assert current_snapshot(app_config.store_path, "forge-ai") == "b:forge-ai"
    insert_graph_snapshot(app_config.store_path, "forge-ai", "c:forge-ai", 4, 3, state="BUILDING")
    store._write_with_busy_retry(lambda conn: store._publish_graph_snapshot(conn, "c:forge-ai"))
    with pytest.raises(KnowledgeError):
        store._write_with_busy_retry(lambda conn: store._publish_graph_snapshot(conn, "b:forge-ai"))
    assert current_snapshot(app_config.store_path, "forge-ai") == "c:forge-ai"


def test_it_graph_10_11_stable_cursor_across_activation_and_expired_after_retention(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=10, edge_count=9)
    store = AnalysisStore(app_config.store_path)
    with TestClient(app) as client:
        manifest_a = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        first = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest_a['graphRevision'])}&pageSize=5"
        ).json()
        for name, count in (("b", 2), ("c", 3), ("d", 4)):
            insert_graph_snapshot(app_config.store_path, "forge-ai", f"{name}:forge-ai", count, max(count - 1, 0), state="BUILDING")
            store._write_with_busy_retry(lambda conn, snapshot=f"{name}:forge-ai": store._publish_graph_snapshot(conn, snapshot))
            if name == "b":
                second = client.get(
                    f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest_a['graphRevision'])}&pageSize=5&cursor={quote(first['nextCursor'])}"
                )
                assert second.status_code == 200
                ids = [item["id"] for item in first["items"]] + [item["id"] for item in second.json()["items"]]
                assert ids == [f"node-{index:05d}" for index in range(10)]
        expired = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest_a['graphRevision'])}&pageSize=5&cursor={quote(first['nextCursor'])}"
        )
    assert expired.status_code == 410
    assert expired.json()["code"] == "GRAPH_SNAPSHOT_EXPIRED"


def test_it_graph_12_18_retention_safety_and_database_integrity(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    for source in ("forge-ai", "other"):
        for index in range(5):
            snapshot = f"job-{index}:{source}"
            insert_graph_snapshot(app_config.store_path, source, snapshot, 3 + index, 2 + index, state="BUILDING")
            AnalysisStore(app_config.store_path)._write_with_busy_retry(
                lambda conn, candidate=snapshot: AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, candidate)
            )
        insert_graph_snapshot(app_config.store_path, source, f"failed:{source}", 1, 0, state="FAILED")
        insert_graph_snapshot(app_config.store_path, source, f"building:{source}", 1, 0, state="BUILDING")
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn, candidate=source: AnalysisStore(app_config.store_path)._retain_graph_snapshots(conn, candidate))
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn, candidate=source: AnalysisStore(app_config.store_path)._retain_graph_snapshots(conn, candidate))

    with sqlite3.connect(app_config.store_path) as conn:
        retained = conn.execute("SELECT source_id, snapshot_id FROM graph_current_snapshots ORDER BY source_id").fetchall()
        retained_snapshots = conn.execute("SELECT source_id, snapshot_id FROM graph_snapshots ORDER BY source_id, snapshot_id").fetchall()
        tombstones = conn.execute("SELECT source_id, snapshot_id FROM graph_snapshot_tombstones ORDER BY source_id, snapshot_id").fetchall()
        orphan_edges = conn.execute(
            """
            SELECT COUNT(*)
            FROM analysis_graph_edges edge
            LEFT JOIN analysis_graph_nodes node ON node.snapshot_id = edge.snapshot_id AND node.id = edge.from_node_id
            WHERE node.id IS NULL
            """
        ).fetchone()[0]
        deleted_child_rows = {
            table: conn.execute(
                f"""
                SELECT COUNT(*)
                FROM {table} child
                JOIN graph_snapshot_tombstones tombstone ON tombstone.snapshot_id = child.snapshot_id
                """
            ).fetchone()[0]
            for table in ("analysis_graph_nodes", "analysis_graph_edges", "analysis_graph_evidence", "analysis_graph_claims", "analysis_graph_diagnostics", "graph_snapshot_metrics")
        }

    assert [tuple(row) for row in retained] == [("forge-ai", "job-4:forge-ai"), ("other", "job-4:other")]
    assert [tuple(row) for row in retained_snapshots] == [
        ("forge-ai", "job-2:forge-ai"),
        ("forge-ai", "job-3:forge-ai"),
        ("forge-ai", "job-4:forge-ai"),
        ("other", "job-2:other"),
        ("other", "job-3:other"),
        ("other", "job-4:other"),
    ]
    assert ("forge-ai", "failed:forge-ai") in [tuple(row) for row in tombstones]
    assert ("forge-ai", "building:forge-ai") in [tuple(row) for row in tombstones]
    assert ("other", "failed:other") in [tuple(row) for row in tombstones]
    assert ("other", "building:other") in [tuple(row) for row in tombstones]
    assert orphan_edges == 0
    assert deleted_child_rows == {
        "analysis_graph_nodes": 0,
        "analysis_graph_edges": 0,
        "analysis_graph_evidence": 0,
        "analysis_graph_claims": 0,
        "analysis_graph_diagnostics": 0,
        "graph_snapshot_metrics": 0,
    }
    assert sqlite_integrity(app_config.store_path) == ("ok", [])
    assert LEGACY_GRAPH_OBJECTS.isdisjoint(sqlite_objects(app_config.store_path))


def test_it_graph_13_bounded_pages_sql_first_filtering_and_fixed_query_counts(tmp_path, monkeypatch):
    observed_counts = []
    for label, node_count, edge_count in (("small", 12, 11), ("large", 1200, 1500)):
        app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path / label))
        deps.inventory_store.init()
        deps.analysis_store.init()
        seed_graph_fixture(app_config.store_path, node_count=node_count, edge_count=edge_count)
        original_connect = deps.analysis_store._connect
        traced_statements: list[str] = []

        def traced_connect(*args, **kwargs):
            conn = original_connect(*args, **kwargs)
            conn.set_trace_callback(traced_statements.append)
            return conn

        monkeypatch.setattr(deps.analysis_store, "_connect", traced_connect)

        def count_selects(action):
            traced_statements.clear()
            response = action()
            statements = [statement for statement in traced_statements if statement.lstrip().upper().startswith("SELECT")]
            return response, statements

        with TestClient(app) as client:
            manifest_response, manifest_sql = count_selects(
                lambda: client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")
            )
            manifest = manifest_response.json()
            assert len(manifest_sql) == 2
            assert client.get(f"/api/v1/knowledge/analysis/graph/nodes?graphRevision={quote(manifest['graphRevision'])}&pageSize=0").status_code == 422
            assert client.get(f"/api/v1/knowledge/analysis/graph/nodes?graphRevision={quote(manifest['graphRevision'])}&pageSize=-1").status_code == 422
            assert client.get(f"/api/v1/knowledge/analysis/graph/nodes?graphRevision={quote(manifest['graphRevision'])}&pageSize=5001").status_code == 422
            assert client.get(
                f"/api/v1/knowledge/analysis/graph/nodes?graphRevision={quote(manifest['graphRevision'])}&includeExternal=bad"
            ).status_code == 400

            nodes_response, node_sql = count_selects(
                lambda: client.get(
                    f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&pageSize=10"
                )
            )
            edges_response, edge_sql = count_selects(
                lambda: client.get(
                    f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&pageSize=10"
                )
            )
            node_detail_response, node_detail_sql = count_selects(
                lambda: client.get(
                    f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
                )
            )
            edge_detail_response, edge_detail_sql = count_selects(
                lambda: client.get(
                    f"/api/v1/knowledge/analysis/graph/edge/edge-00000?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}&includeEvidence=true"
                )
            )
            nodes = traverse_pages(client, "nodes", manifest["graphRevision"], 499)
            edges = traverse_pages(client, "edges", manifest["graphRevision"], 701)

        assert nodes_response.status_code == 200
        assert edges_response.status_code == 200
        assert node_detail_response.status_code == 200
        assert edge_detail_response.status_code == 200
        assert len(node_sql) == 2
        assert len(edge_sql) == 2
        assert len(node_detail_sql) == 5
        assert len(edge_detail_sql) == 3
        assert " ORDER BY n.id" in node_sql[-1] and " LIMIT " in node_sql[-1]
        assert " ORDER BY e.id" in edge_sql[-1] and " LIMIT " in edge_sql[-1]
        assert len(nodes) == node_count
        assert len(edges) == edge_count
        assert len({item["id"] for item in nodes}) == node_count
        assert len({item["id"] for item in edges}) == edge_count
        assert all("evidence" not in item and "claims" not in item for item in nodes)
        assert all("evidence" not in item for item in edges)
        observed_counts.append((len(manifest_sql), len(node_sql), len(edge_sql), len(node_detail_sql), len(edge_detail_sql)))

    assert observed_counts[0] == observed_counts[1] == (2, 2, 2, 5, 3)


def test_it_graph_14_complete_snapshot_specific_node_edge_detail_error_matrix(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)
    with TestClient(app) as client:
        manifest_a = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        insert_graph_snapshot(app_config.store_path, "forge-ai", "b:forge-ai", 2, 1, state="BUILDING")
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn: AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, "b:forge-ai"))
        node_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}"
        )
        edge_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/edge/edge-00000?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}&includeEvidence=true"
        )
        missing_node = client.get(
            f"/api/v1/knowledge/analysis/graph/node/missing-node?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}"
        )
        missing_edge = client.get(
            f"/api/v1/knowledge/analysis/graph/edge/missing-edge?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}"
        )
        wrong_source = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=other&graphRevision={quote(manifest_a['graphRevision'])}"
        )
        wrong_snapshot_item = client.get(
            f"/api/v1/knowledge/analysis/graph/node/b-node-00000?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}"
        )
        never_revision = graph_revision_for_snapshot(app_config.store_path, "never:forge-ai", "forge-ai", "CODE")
        never_snapshot = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(never_revision)}"
        )
        for name, count in (("c", 3), ("d", 4)):
            insert_graph_snapshot(app_config.store_path, "forge-ai", f"{name}:forge-ai", count, max(count - 1, 0), state="BUILDING")
            AnalysisStore(app_config.store_path)._write_with_busy_retry(
                lambda conn, candidate=f"{name}:forge-ai": AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, candidate)
            )
        expired_snapshot = client.get(
            f"/api/v1/knowledge/analysis/graph/edge/edge-00000?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}"
        )

    assert node_detail.status_code == 200
    assert node_detail.json()["snapshotId"] == manifest_a["snapshotId"]
    assert node_detail.json()["item"]["id"] == "node-00000"
    assert node_detail.json()["item"]["claims"][0]["metadata"]["legacy"] is False
    assert edge_detail.status_code == 200
    assert edge_detail.json()["snapshotId"] == manifest_a["snapshotId"]
    assert edge_detail.json()["item"]["id"] == "edge-00000"
    assert edge_detail.json()["item"]["evidence"]
    assert missing_node.status_code == 404
    assert missing_node.json()["code"] == "GRAPH_NODE_NOT_FOUND"
    assert missing_edge.status_code == 404
    assert missing_edge.json()["code"] == "GRAPH_EDGE_NOT_FOUND"
    assert wrong_source.status_code == 409
    assert wrong_source.json()["code"] == "GRAPH_SNAPSHOT_SOURCE_MISMATCH"
    assert wrong_snapshot_item.status_code == 409
    assert wrong_snapshot_item.json()["code"] == "GRAPH_ITEM_SCOPE_MISMATCH"
    assert never_snapshot.status_code == 404
    assert never_snapshot.json()["code"] == "GRAPH_SNAPSHOT_NOT_FOUND"
    assert expired_snapshot.status_code == 410
    assert expired_snapshot.json()["code"] == "GRAPH_SNAPSHOT_EXPIRED"


def test_it_graph_node_details_01_node_detail_includes_responsibility_summary_fields(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        response = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}&includeEvidence=true"
        )

    item = response.json()["item"]
    assert response.status_code == 200
    assert item["claimSummary"] == "Fixture responsibility"
    assert item["responsibilitySummary"] == "Fixture responsibility"
    assert item["claims"][0]["summary"] == "Fixture responsibility"
    assert item["evidence"][0]["id"] == "evidence-node-0"


def test_it_graph_node_details_02_node_detail_includes_bounded_outgoing_edges(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=40, edge_count=0)
    add_node_detail_relation_fixture(app_config.store_path, outgoing_count=30, incoming_count=1)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        response = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
        )

    outgoing = response.json()["item"]["relations"]["outgoing"]
    first = outgoing["items"][0]
    assert response.status_code == 200
    assert outgoing["totalCount"] == 30
    assert len(outgoing["items"]) == 25
    assert first["edgeKind"] == "CALLS"
    assert first["sourceNodeId"] == "node-00000"
    assert first["sourceName"] == "Name0"
    assert first["sourceKind"] == "FILE"
    assert first["targetNodeId"] == "node-00001"
    assert first["targetName"] == "Name1"
    assert first["targetKind"] == "TYPE"
    assert first["sourcePath"] == "src/GraphFixture.java"
    assert first["lineStart"] == 10
    assert first["lineEnd"] == 10
    assert first["confidence"] == 0.8
    assert first["evidenceCount"] == 0


def test_it_graph_node_details_03_node_detail_includes_bounded_incoming_edges(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=40, edge_count=0)
    add_node_detail_relation_fixture(app_config.store_path, outgoing_count=1, incoming_count=30)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        response = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
        )

    incoming = response.json()["item"]["relations"]["incoming"]
    first = incoming["items"][0]
    assert response.status_code == 200
    assert incoming["totalCount"] == 30
    assert len(incoming["items"]) == 25
    assert first["edgeKind"] == "REFERENCES"
    assert first["sourceNodeId"] == "node-00001"
    assert first["sourceName"] == "Name1"
    assert first["sourceKind"] == "TYPE"
    assert first["targetNodeId"] == "node-00000"
    assert first["targetName"] == "Name0"
    assert first["targetKind"] == "FILE"


def test_it_graph_node_details_04_node_with_no_edges_returns_empty_relation_groups(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=0)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        response = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00002?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
        )

    relations = response.json()["item"]["relations"]
    assert response.status_code == 200
    assert relations["incoming"] == {"totalCount": 0, "items": []}
    assert relations["outgoing"] == {"totalCount": 0, "items": []}


def test_it_graph_node_details_05_wrong_source_and_stale_revision_cannot_leak_edges(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=0)

    with TestClient(app) as client:
        manifest_a = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        wrong_source = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=other&graphRevision={quote(manifest_a['graphRevision'])}"
        )
        insert_graph_snapshot(app_config.store_path, "forge-ai", "b:forge-ai", 3, 2, state="BUILDING")
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn: AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, "b:forge-ai"))
        stale_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/node/node-00000?sourceId=forge-ai&graphRevision={quote(manifest_a['graphRevision'])}"
        )

    assert wrong_source.status_code == 409
    assert wrong_source.json()["code"] == "GRAPH_SNAPSHOT_SOURCE_MISMATCH"
    assert stale_detail.status_code == 200
    assert stale_detail.json()["snapshotId"] == manifest_a["snapshotId"]
    assert stale_detail.json()["item"]["relations"]["incoming"]["totalCount"] == 0
    assert stale_detail.json()["item"]["relations"]["outgoing"]["totalCount"] == 0


def test_it_graph_15_final_knowledge_route_contract(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")
        assert manifest.status_code == 200
        assert client.get("/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision=" + quote(manifest.json()["graphRevision"])).status_code == 200
        assert client.get("/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision=" + quote(manifest.json()["graphRevision"])).status_code == 200
        assert client.get("/api/v1/knowledge/analysis/graph?sourceId=forge-ai").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/graph/slice?sourceId=forge-ai").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/symbols?sourceId=forge-ai").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/relations?sourceId=forge-ai").status_code == 404


def test_it_graph_stale_01_normal_current_get_for_contract_source_does_not_409(tmp_path):
    source_id = "app-afesox-contracts"
    snapshot_id = "contracts-1:app-afesox-contracts"
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    insert_graph_snapshot(app_config.store_path, source_id, snapshot_id, 3, 0)
    publish_graph_snapshot(app_config.store_path, snapshot_id)

    with TestClient(app) as client:
        metadata = client.get(f"/api/v1/knowledge/analysis/graph/metadata?sourceId={source_id}")
        manifest = client.get(f"/api/v1/knowledge/analysis/graph/manifest?sourceId={source_id}&flowDomain=CODE")
        view = client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId={source_id}&flowDomain=CODE&maxNodes=80")
        revision = quote(manifest.json()["graphRevision"])
        nodes = client.get(f"/api/v1/knowledge/analysis/graph/nodes?sourceId={source_id}&flowDomain=CODE&graphRevision={revision}&pageSize=10")
        edges = client.get(f"/api/v1/knowledge/analysis/graph/edges?sourceId={source_id}&flowDomain=CODE&graphRevision={revision}&pageSize=10")

    for response in (metadata, manifest, view, nodes, edges):
        assert response.status_code == 200
        assert response.json().get("code") != "GRAPH_SNAPSHOT_STALE"
    assert metadata.json()["graphAvailable"] is True
    assert manifest.json()["snapshotId"] == snapshot_id
    assert view.json()["sourceId"] == source_id
    assert view.json()["edges"] == []
    assert edges.json()["items"] == []


def test_it_graph_stale_02_empty_graph_no_edges_is_not_stale(tmp_path):
    source_id = "app-afesox-contracts"
    snapshot_id = "contracts-empty:app-afesox-contracts"
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    insert_graph_snapshot(app_config.store_path, source_id, snapshot_id, 4, 0)
    publish_graph_snapshot(app_config.store_path, snapshot_id)

    with TestClient(app) as client:
        response = client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId={source_id}&flowDomain=CODE&maxNodes=80")

    assert response.status_code == 200
    body = response.json()
    assert body.get("code") != "GRAPH_SNAPSHOT_STALE"
    assert body["visibleNodeCount"] == 4
    assert body["visibleEdgeCount"] == 0
    assert body["totalMatchingEdgeCount"] == 0
    assert body["hiddenEdgeCount"] == 0
    assert body["edges"] == []


def test_it_graph_stale_03_no_current_snapshot_is_not_stale(tmp_path):
    source_id = "app-afesox-contracts"
    expired_snapshot = "expired-contracts:app-afesox-contracts"
    now = datetime.now(timezone.utc).isoformat()
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    with sqlite3.connect(app_config.store_path) as conn:
        conn.execute("PRAGMA foreign_keys = OFF")
        conn.execute(
            """
            INSERT OR REPLACE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES (?, 'API Contracts', 'api', '.', 1, '[]', '{}', ?)
            """,
            (source_id, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_snapshot_tombstones(snapshot_id, source_id, expired_at, reason)
            VALUES (?, ?, ?, 'RETENTION')
            """,
            (expired_snapshot, source_id, now),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES (?, ?, ?)
            """,
            (source_id, expired_snapshot, now),
        )

    with TestClient(app) as client:
        metadata = client.get(f"/api/v1/knowledge/analysis/graph/metadata?sourceId={source_id}")
        manifest = client.get(f"/api/v1/knowledge/analysis/graph/manifest?sourceId={source_id}&flowDomain=CODE")
        view = client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId={source_id}&flowDomain=CODE&maxNodes=80")

    for response in (metadata, manifest, view):
        assert response.status_code == 200
        assert response.json().get("code") != "GRAPH_SNAPSHOT_STALE"
    assert metadata.json()["graphAvailable"] is False
    assert manifest.json()["snapshotId"] is None
    assert manifest.json()["totalNodeCount"] == 0
    assert view.json()["snapshotId"] is None
    assert view.json()["nodes"] == []
    assert view.json()["edges"] == []


def test_it_graph_stale_04_explicit_stale_revision_returns_controlled_409(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)

    with TestClient(app) as client:
        stale = client.get("/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision=stale&pageSize=10")

    assert stale.status_code == 409
    body = stale.json()
    assert body["code"] == "GRAPH_SNAPSHOT_STALE"
    assert body["correlationId"]


def test_it_graph_stale_05_source_switch_clears_stale_state(tmp_path):
    source_id = "app-afesox-contracts"
    snapshot_id = "contracts-switch:app-afesox-contracts"
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=10, edge_count=9)
    insert_graph_snapshot(app_config.store_path, source_id, snapshot_id, 2, 0)
    publish_graph_snapshot(app_config.store_path, snapshot_id)

    with TestClient(app) as client:
        source_a_manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        source_a_page = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(source_a_manifest['graphRevision'])}&pageSize=2"
        ).json()
        metadata_b = client.get(f"/api/v1/knowledge/analysis/graph/metadata?sourceId={source_id}")
        manifest_b = client.get(f"/api/v1/knowledge/analysis/graph/manifest?sourceId={source_id}&flowDomain=CODE")
        view_b = client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId={source_id}&flowDomain=CODE&maxNodes=80")

    assert source_a_page["nextCursor"]
    for response in (metadata_b, manifest_b, view_b):
        assert response.status_code == 200
        assert response.json().get("code") != "GRAPH_SNAPSHOT_STALE"
    assert metadata_b.json()["sourceId"] == source_id
    assert manifest_b.json()["snapshotId"] == snapshot_id
    assert view_b.json()["sourceId"] == source_id
    assert view_b.json()["edges"] == []


def test_it_graph_meta_01_metadata_endpoint_independent_of_graph_metrics(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=6, edge_count=5)
    with sqlite3.connect(app_config.store_path) as conn:
        conn.execute("DELETE FROM graph_snapshot_metrics WHERE snapshot_id = 'job-1:forge-ai'")

    with TestClient(app) as client:
        response = client.get("/api/v1/knowledge/analysis/graph/metadata?sourceId=forge-ai")

    assert response.status_code == 200
    metadata = response.json()
    assert metadata["sourceId"] == "forge-ai"
    assert metadata["sourceName"] == "Forge AI"
    assert metadata["graphAvailable"] is True
    assert metadata["snapshotId"] == "job-1:forge-ai"
    assert "nodes" not in metadata
    assert "edges" not in metadata
    assert "evidence" not in metadata
    assert metadata.get("code") != "GRAPH_SNAPSHOT_METRICS_MISSING"


def test_it_graph_meta_02_manifest_valid_filter_backfills_missing_metrics_and_matches_pages(tmp_path, monkeypatch):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=12, edge_count=11)
    with sqlite3.connect(app_config.store_path) as conn:
        conn.execute("DELETE FROM graph_snapshot_metrics WHERE snapshot_id = 'job-1:forge-ai'")
    original_connect = deps.analysis_store._connect
    traced_statements: list[str] = []

    def traced_connect(*args, **kwargs):
        conn = original_connect(*args, **kwargs)
        conn.set_trace_callback(traced_statements.append)
        return conn

    monkeypatch.setattr(deps.analysis_store, "_connect", traced_connect)

    with TestClient(app) as client:
        manifest_response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")
        manifest_selects = [statement for statement in traced_statements if statement.lstrip().upper().startswith("SELECT")]
        traced_statements.clear()
        manifest = manifest_response.json()
        nodes = traverse_pages(client, "nodes", manifest["graphRevision"], 20)
        edges = traverse_pages(client, "edges", manifest["graphRevision"], 20)

    assert manifest_response.status_code == 200
    assert manifest.get("code") != "GRAPH_SNAPSHOT_METRICS_MISSING"
    assert manifest["totalNodeCount"] == len(nodes)
    assert manifest["totalEdgeCount"] == len(edges)
    assert len(manifest_selects) <= 12


def test_it_graph_meta_03_invalid_filter_returns_controlled_400(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=3, edge_count=2)

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&includeExternal=collapsed",
            headers={"X-Correlation-Id": "graph-invalid-filter"},
        )

    assert response.status_code == 400
    payload = response.json()
    assert payload["code"] == "GRAPH_FILTER_INVALID"
    assert payload["correlationId"] == "graph-invalid-filter"
    assert "src/GraphFixture.java" not in json.dumps(payload)
    assert "Traceback" not in json.dumps(payload)


def test_it_graph_meta_04_filter_matrix_parity(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=15, edge_count=14)

    supported_queries = [
        "sourceId=forge-ai",
        "sourceId=forge-ai&flowDomain=CODE",
        "sourceId=forge-ai&factOrigin=STATIC",
        "sourceId=forge-ai&nodeKind=CALLABLE",
        "sourceId=forge-ai&edgeType=CALLS",
        "sourceId=forge-ai&includeExternal=hide",
        "sourceId=forge-ai&includeUnresolved=false",
        "sourceId=forge-ai&includeIsolated=false",
        "sourceId=forge-ai&flowDomain=CONFIG",
        "sourceId=forge-ai&search=Name1",
        "sourceId=forge-ai&search=GraphFixture",
        "sourceId=forge-ai&search=CALLABLE",
    ]
    with TestClient(app) as client:
        for query in supported_queries:
            manifest_response = client.get(f"/api/v1/knowledge/analysis/graph/manifest?{query}")
            assert manifest_response.status_code == 200, query
            manifest = manifest_response.json()
            page_query = f"{query}&graphRevision={quote(manifest['graphRevision'])}&pageSize=50"
            nodes = client.get(f"/api/v1/knowledge/analysis/graph/nodes?{page_query}")
            edges = client.get(f"/api/v1/knowledge/analysis/graph/edges?{page_query}")
            assert nodes.status_code == 200, query
            assert edges.status_code == 200, query
            assert manifest["totalNodeCount"] == len(nodes.json()["items"])
            assert manifest["totalEdgeCount"] == len(edges.json()["items"])

        unsupported = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&includeExternal=bad")
        unsupported_search = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&search=bad%25")
    assert unsupported.status_code == 400
    assert unsupported.json()["code"] == "GRAPH_FILTER_INVALID"
    assert unsupported_search.status_code == 400
    assert unsupported_search.json()["code"] == "GRAPH_FILTER_INVALID"


def test_it_graph_filter_be_01_page_size_bounds_20_40_80_120_200_and_all(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=260, edge_count=520)

    with TestClient(app) as client:
        manifest_response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true")
        assert manifest_response.status_code == 200
        manifest = manifest_response.json()
        assert manifest.get("code") not in {"GRAPH_FILTER_INVALID", "GRAPH_SNAPSHOT_METRICS_MISSING"}
        assert manifest["totalNodeCount"] > 200
        assert manifest["totalEdgeCount"] > 200

        for page_size in (20, 40, 80, 120, 200):
            nodes = client.get(
                f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&graphRevision={quote(manifest['graphRevision'])}&pageSize={page_size}"
            )
            edges = client.get(
                f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&graphRevision={quote(manifest['graphRevision'])}&pageSize={page_size}"
            )
            assert nodes.status_code == 200, page_size
            assert edges.status_code == 200, page_size
            node_page = nodes.json()
            edge_page = edges.json()
            assert len(node_page["items"]) <= page_size
            assert len(edge_page["items"]) <= page_size
            assert len(node_page["items"]) == page_size
            assert len(edge_page["items"]) == page_size
            assert node_page["nextCursor"]
            assert edge_page["nextCursor"]
            assert not node_page["complete"]
            assert not edge_page["complete"]

        all_nodes = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&graphRevision={quote(manifest['graphRevision'])}&pageSize=5000"
        )
        all_edges = client.get(
            f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&graphRevision={quote(manifest['graphRevision'])}&pageSize=5000"
        )
        assert all_nodes.status_code == 200
        assert all_edges.status_code == 200
        assert len(all_nodes.json()["items"]) == manifest["totalNodeCount"]
        assert len(all_edges.json()["items"]) == manifest["totalEdgeCount"]
        assert all_nodes.json()["complete"] is True
        assert all_edges.json()["complete"] is True
        assert not all_nodes.json().get("nextCursor")
        assert not all_edges.json().get("nextCursor")


def test_it_graph_filter_be_02_monotonic_page_size_counts(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=260, edge_count=520)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true").json()
        counts = []
        for page_size in (20, 40, 80, 120, 200):
            response = client.get(
                f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&graphRevision={quote(manifest['graphRevision'])}&pageSize={page_size}"
            )
            assert response.status_code == 200
            counts.append(len(response.json()["items"]))
        all_response = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&graphRevision={quote(manifest['graphRevision'])}&pageSize=5000"
        )
    assert counts == [20, 40, 80, 120, 200]
    assert counts == sorted(counts)
    assert len(all_response.json()["items"]) == 260
    assert counts[-1] <= len(all_response.json()["items"])


def test_it_graph_filter_be_03_cursor_bound_to_query_fingerprint(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=260, edge_count=520)

    base_query = "sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeUnresolved=true&includeIsolated=true"
    with TestClient(app) as client:
        manifest = client.get(f"/api/v1/knowledge/analysis/graph/manifest?{base_query}").json()
        first_page = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?{base_query}&graphRevision={quote(manifest['graphRevision'])}&pageSize=5"
        ).json()
        assert first_page["nextCursor"]
        valid_next = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?{base_query}&graphRevision={quote(manifest['graphRevision'])}&pageSize=5&cursor={quote(first_page['nextCursor'])}"
        )
        assert valid_next.status_code == 200

        changed_queries = [
            "sourceId=forge-ai&flowDomain=CONFIG&includeExternal=show&includeUnresolved=true&includeIsolated=true",
            "sourceId=forge-ai&flowDomain=CODE&factOrigin=DERIVED&includeExternal=show&includeUnresolved=true&includeIsolated=true",
            "sourceId=forge-ai&flowDomain=CODE&nodeKind=CALLABLE&includeExternal=show&includeUnresolved=true&includeIsolated=true",
            "sourceId=forge-ai&flowDomain=CODE&includeExternal=hide&includeUnresolved=true&includeIsolated=true",
            "sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeUnresolved=false&includeIsolated=true",
            "sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeUnresolved=true&includeIsolated=false",
            "sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeUnresolved=true&includeIsolated=true&search=Name1",
        ]
        for changed_query in changed_queries:
            mismatch = client.get(
                f"/api/v1/knowledge/analysis/graph/nodes?{changed_query}&graphRevision={quote(manifest['graphRevision'])}&pageSize=5&cursor={quote(first_page['nextCursor'])}"
            )
            assert mismatch.status_code == 400, changed_query
            assert mismatch.json()["code"] == "GRAPH_CURSOR_QUERY_MISMATCH"


def test_it_graph_filter_be_04_search_by_node_file_kind_and_cursor_fingerprint(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=260, edge_count=520)

    cases = [
        ("Name1", lambda item: "Name1" in item["name"]),
        ("GraphFixture", lambda item: item["relativePath"] == "src/GraphFixture.java"),
        ("CALLABLE", lambda item: item["nodeKind"] == "CALLABLE"),
    ]
    with TestClient(app) as client:
        for search, predicate in cases:
            query = f"sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&search={quote(search)}"
            manifest = client.get(f"/api/v1/knowledge/analysis/graph/manifest?{query}")
            assert manifest.status_code == 200, search
            assert manifest.json().get("code") not in {"GRAPH_FILTER_INVALID", "GRAPH_SNAPSHOT_METRICS_MISSING"}
            page = client.get(
                f"/api/v1/knowledge/analysis/graph/nodes?{query}&graphRevision={quote(manifest.json()['graphRevision'])}&pageSize=10"
            )
            assert page.status_code == 200, search
            items = page.json()["items"]
            assert 0 < len(items) <= 10
            assert all(predicate(item) for item in items)

        name_manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&search=Name1").json()
        name_page = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&search=Name1&graphRevision={quote(name_manifest['graphRevision'])}&pageSize=5"
        ).json()
        assert name_page["nextCursor"]
        search_mismatch = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&search=CALLABLE&graphRevision={quote(name_manifest['graphRevision'])}&pageSize=5&cursor={quote(name_page['nextCursor'])}"
        )
        assert search_mismatch.status_code == 400
        assert search_mismatch.json()["code"] == "GRAPH_CURSOR_QUERY_MISMATCH"


def test_it_graph_view_01_returns_nodes_with_internal_edges(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=320, edge_count=700)

    with TestClient(app) as client:
        response = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&maxNodes=40")

    assert response.status_code == 200
    view = response.json()
    node_ids = {node["id"] for node in view["nodes"]}
    assert view["selectionPolicy"] == "RELATIONSHIP_AWARE"
    assert view["visibleNodeCount"] == 40
    assert len(view["nodes"]) == 40
    assert view["edges"]
    assert all(edge["fromNodeId"] in node_ids and edge["toNodeId"] in node_ids for edge in view["edges"])
    assert view["hiddenNodeCount"] == view["totalMatchingNodeCount"] - view["visibleNodeCount"]
    assert view["hiddenEdgeCount"] == view["totalMatchingEdgeCount"] - view["visibleEdgeCount"]
    assert view["hiddenBoundaryEdgeCount"] >= 0
    assert view["hasMore"] is True


def test_it_graph_view_02_max_monotonic_and_deterministic(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=320, edge_count=700)

    with TestClient(app) as client:
        views = [
            client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&maxNodes={max_nodes}").json()
            for max_nodes in (20, 40, 80, 120, 200, 0)
        ]
        repeated = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&maxNodes=40").json()

    node_counts = [view["visibleNodeCount"] for view in views]
    edge_counts = [view["visibleEdgeCount"] for view in views]
    assert node_counts == [20, 40, 80, 120, 200, 320]
    assert node_counts == sorted(node_counts)
    assert edge_counts == sorted(edge_counts)
    assert [node["id"] for node in views[1]["nodes"]] == [node["id"] for node in repeated["nodes"]]
    assert views[4]["visibleNodeCount"] > views[2]["visibleNodeCount"]


def test_it_graph_view_03_relationship_aware_selection_prefers_connected_cluster(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=60, edge_count=0)
    replace_edges_with_late_connected_cluster(app_config.store_path, start=30, node_count=60, edge_count=90)

    with TestClient(app) as client:
        view = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&maxNodes=20").json()

    node_ids = {node["id"] for node in view["nodes"]}
    assert view["visibleNodeCount"] == 20
    assert view["visibleEdgeCount"] > 0
    assert all(int(node_id.rsplit("-", 1)[1]) >= 30 for node_id in node_ids)
    assert all(edge["fromNodeId"] in node_ids and edge["toNodeId"] in node_ids for edge in view["edges"])


def test_it_graph_view_04_filters_apply_before_selection(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=320, edge_count=700)

    cases = [
        ("flowDomain=CODE", lambda node, edge: node["flowDomain"] == "CODE"),
        ("includeExternal=hide", lambda node, edge: node["nodeKind"] != "EXTERNAL"),
        ("includeIsolated=false", lambda node, edge: node["degree"] > 0),
        ("search=CALLABLE", lambda node, edge: node["nodeKind"] == "CALLABLE"),
        ("includeUnresolved=false", lambda node, edge: edge is None or edge["resolutionStatus"] != "UNRESOLVED"),
    ]
    with TestClient(app) as client:
        for query, predicate in cases:
            response = client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&{query}&maxNodes=40")
            assert response.status_code == 200, query
            payload = response.json()
            assert payload.get("code") not in {"GRAPH_FILTER_INVALID", "GRAPH_SNAPSHOT_METRICS_MISSING"}
            assert all(predicate(node, None) for node in payload["nodes"]), query
            assert all(predicate(payload["nodes"][0], edge) for edge in payload["edges"]) if payload["nodes"] else True
            node_ids = {node["id"] for node in payload["nodes"]}
            assert all(edge["fromNodeId"] in node_ids and edge["toNodeId"] in node_ids for edge in payload["edges"])


def test_it_graph_view_05_invalid_filter_returns_controlled_400(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=20, edge_count=30)

    with TestClient(app) as client:
        response = client.get(
            "/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&includeExternal=collapsed&maxNodes=20",
            headers={"X-Correlation-Id": "graph-view-invalid"},
        )

    assert response.status_code == 400
    payload = response.json()
    assert payload["code"] == "GRAPH_FILTER_INVALID"
    assert payload["correlationId"] == "graph-view-invalid"
    assert "src/GraphFixture.java" not in json.dumps(payload)
    assert "Traceback" not in json.dumps(payload)


def test_it_graph_view_06_query_fingerprint_distinguishes_filter_shapes(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=120, edge_count=200)

    with TestClient(app) as client:
        first = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&maxNodes=40").json()
        repeat = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&maxNodes=40").json()
        changed_external = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=hide&maxNodes=40").json()
        changed_search = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&search=CALLABLE&maxNodes=40").json()
        changed_max = client.get("/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&maxNodes=80").json()

    assert first["queryFingerprint"] == repeat["queryFingerprint"]
    assert first["queryFingerprint"] != changed_external["queryFingerprint"]
    assert first["queryFingerprint"] != changed_search["queryFingerprint"]
    assert first["queryFingerprint"] == changed_max["queryFingerprint"]
    assert [node["id"] for node in first["nodes"]] != [node["id"] for node in changed_search["nodes"]]


def test_it_graph_view_07_performance_query_bound(tmp_path, monkeypatch):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=320, edge_count=700)
    original_connect = deps.analysis_store._connect
    traced_statements: list[str] = []

    def traced_connect(*args, **kwargs):
        conn = original_connect(*args, **kwargs)
        conn.set_trace_callback(traced_statements.append)
        return conn

    monkeypatch.setattr(deps.analysis_store, "_connect", traced_connect)

    with TestClient(app) as client:
        for max_nodes in (20, 40, 80, 120, 200):
            traced_statements.clear()
            response = client.get(f"/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&includeExternal=show&includeIsolated=true&maxNodes={max_nodes}")
            statements = [statement for statement in traced_statements if statement.lstrip().upper().startswith("SELECT")]
            assert response.status_code == 200
            payload = response.json()
            assert payload["visibleNodeCount"] == max_nodes
            assert len(json.dumps(payload)) < 500_000
            assert len(statements) <= 14


def test_graph_snapshot_manifest_cursor_pagination_details_and_legacy_route_deletion(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=125, edge_count=360)

    with TestClient(app) as client:
        manifest_response = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE")
        assert manifest_response.status_code == 200
        assert manifest_response.headers["etag"]
        assert manifest_response.headers["x-graph-revision"]
        manifest = manifest_response.json()
        assert manifest["graphRevision"]
        assert manifest["totalNodeCount"] == 125
        assert manifest["totalEdgeCount"] == 360
        assert manifest["nodeTypeCounts"]["CALLABLE"] > 0
        assert manifest["edgeTypeCounts"]["CALLS"] > 0

        not_modified = client.get(
            "/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE",
            headers={"If-None-Match": manifest_response.headers["etag"]},
        )
        assert not_modified.status_code == 304

        nodes = traverse_pages(client, "nodes", manifest["graphRevision"], page_size=37)
        assert len(nodes) == manifest["totalNodeCount"]
        assert len({node["id"] for node in nodes}) == len(nodes)
        assert [node["id"] for node in nodes] == sorted(node["id"] for node in nodes)

        edges = traverse_pages(client, "edges", manifest["graphRevision"], page_size=53)
        assert len(edges) == manifest["totalEdgeCount"]
        assert len({edge["id"] for edge in edges}) == len(edges)
        assert [edge["id"] for edge in edges] == sorted(edge["id"] for edge in edges)
        node_ids = {node["id"] for node in nodes}
        assert all(edge["fromNodeId"] in node_ids and edge["toNodeId"] in node_ids for edge in edges if edge["toNodeId"])

        invalid_cursor = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&cursor=bad"
        )
        assert invalid_cursor.status_code == 400
        assert invalid_cursor.json()["code"] == "GRAPH_CURSOR_INVALID"

        first_node_page = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&pageSize=5"
        ).json()
        assert first_node_page["nextCursor"]
        tampered_payload = json.loads(base64.urlsafe_b64decode(first_node_page["nextCursor"] + "=" * (-len(first_node_page["nextCursor"]) % 4)))
        tampered_payload["last"]["id"] = "node-99999"
        tampered_cursor = base64.urlsafe_b64encode(json.dumps(tampered_payload, separators=(",", ":")).encode("utf-8")).decode("ascii").rstrip("=")
        tampered = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&cursor={quote(tampered_cursor)}"
        )
        assert tampered.status_code == 400
        assert tampered.json()["code"] == "GRAPH_CURSOR_INVALID"
        cross_resource = client.get(
            f"/api/v1/knowledge/analysis/graph/edges?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&cursor={quote(first_node_page['nextCursor'])}"
        )
        assert cross_resource.status_code == 400
        assert cross_resource.json()["code"] == "GRAPH_CURSOR_RESOURCE_MISMATCH"

        stale = client.get("/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision=stale&pageSize=10")
        assert stale.status_code == 409
        assert stale.json()["code"] == "GRAPH_SNAPSHOT_STALE"

        no_external = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeExternal=hide").json()
        assert no_external["totalNodeCount"] < manifest["totalNodeCount"]
        cross_filter = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&includeExternal=hide&graphRevision={quote(no_external['graphRevision'])}&cursor={quote(first_node_page['nextCursor'])}"
        )
        assert cross_filter.status_code == 400
        assert cross_filter.json()["code"] == "GRAPH_CURSOR_QUERY_MISMATCH"

        no_isolated = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE&includeIsolated=false").json()
        assert no_isolated["totalNodeCount"] < manifest["totalNodeCount"]

        insert_graph_snapshot(app_config.store_path, "other", "other-1:other", 10, 9, state="BUILDING")
        AnalysisStore(app_config.store_path)._write_with_busy_retry(lambda conn: AnalysisStore(app_config.store_path)._publish_graph_snapshot(conn, "other-1:other"))
        other_manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=other&flowDomain=CODE").json()
        other_page = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=other&flowDomain=CODE&graphRevision={quote(other_manifest['graphRevision'])}&pageSize=5"
        ).json()
        cross_source = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&cursor={quote(other_page['nextCursor'])}"
        )
        assert cross_source.status_code == 400
        assert cross_source.json()["code"] == "GRAPH_CURSOR_SOURCE_MISMATCH"

        node_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/node/{quote(nodes[0]['id'])}?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}&includeEvidence=true"
        )
        assert node_detail.status_code == 200
        assert node_detail.json()["item"]["id"] == nodes[0]["id"]
        assert len(node_detail.json()["item"].get("claims", [])) <= 100

        edge_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/edge/{quote(edges[0]['id'])}?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}&includeEvidence=true"
        )
        assert edge_detail.status_code == 200
        assert edge_detail.json()["item"]["id"] == edges[0]["id"]

        assert client.get("/api/v1/knowledge/analysis/graph?sourceId=forge-ai&flowDomain=CODE&limit=80").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/graph/slice?sourceId=forge-ai&flowDomain=CODE&maxNodes=20&maxEdges=30").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/symbols?sourceId=forge-ai").status_code == 404
        assert client.get("/api/v1/knowledge/analysis/relations?sourceId=forge-ai").status_code == 404


def traverse_pages(client: TestClient, kind: str, graph_revision: str, page_size: int):
    cursor = None
    items = []
    while True:
        path = f"/api/v1/knowledge/analysis/graph/{kind}?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(graph_revision)}&pageSize={page_size}"
        if cursor:
            path += f"&cursor={quote(cursor)}"
        page = client.get(path).json()
        items.extend(page["items"])
        cursor = page.get("nextCursor")
        if page["complete"]:
            assert not cursor
            return items
        assert cursor


def publish_graph_snapshot(db_path, snapshot_id: str) -> None:
    store = AnalysisStore(db_path)
    store._write_with_busy_retry(lambda conn: store._publish_graph_snapshot(conn, snapshot_id))


def sqlite_objects(db_path):
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute(
            """
            SELECT name
            FROM sqlite_master
            WHERE type IN ('table', 'view', 'trigger', 'index')
            """
        ).fetchall()
    return {row[0] for row in rows}


def sqlite_integrity(db_path):
    with sqlite3.connect(db_path) as conn:
        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        foreign_keys = conn.execute("PRAGMA foreign_key_check").fetchall()
    return integrity, foreign_keys


def assert_source_snapshot_constraints_reject_wrong_source(db_path) -> None:
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute(
            """
            INSERT OR IGNORE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json)
            VALUES ('constraint:snapshot', 'constraint-source-a', 'constraint-job', 'PUBLISHED', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', '{}')
            """
        )
        invalid_statements = [
            """
            INSERT INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES ('constraint-source-b', 'constraint:snapshot', '2026-01-01T00:00:00Z')
            """,
            """
            INSERT INTO analysis_graph_nodes(id, snapshot_id, job_id, source_id, stable_key, node_kind, name, confidence, status, metadata_json, created_at)
            VALUES ('constraint-node', 'constraint:snapshot', 'constraint-job', 'constraint-source-b', 'stable', 'CALLABLE', 'node', 1.0, 'TRUSTED', '{}', '2026-01-01T00:00:00Z')
            """,
            """
            INSERT INTO analysis_graph_evidence(id, snapshot_id, job_id, source_id, content_hash, line_start, line_end, excerpt_hash, evidence_kind, metadata_json, created_at)
            VALUES ('constraint-evidence', 'constraint:snapshot', 'constraint-job', 'constraint-source-b', 'hash', 1, 1, 'excerpt', 'SUMMARY', '{}', '2026-01-01T00:00:00Z')
            """,
            """
            INSERT INTO analysis_graph_diagnostics(id, snapshot_id, job_id, source_id, severity, stage, code, message, metadata_json, created_at)
            VALUES ('constraint-diagnostic', 'constraint:snapshot', 'constraint-job', 'constraint-source-b', 'WARN', 'TEST', 'CODE', 'message', '{}', '2026-01-01T00:00:00Z')
            """,
        ]
        for statement in invalid_statements:
            with pytest.raises(sqlite3.IntegrityError):
                conn.execute(statement)
        conn.execute(
            """
            INSERT INTO analysis_graph_nodes(id, snapshot_id, job_id, source_id, stable_key, node_kind, name, confidence, status, metadata_json, created_at)
            VALUES ('constraint-node', 'constraint:snapshot', 'constraint-job', 'constraint-source-a', 'stable', 'CALLABLE', 'node', 1.0, 'TRUSTED', '{}', '2026-01-01T00:00:00Z')
            """
        )
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                """
                INSERT INTO analysis_graph_edges(id, snapshot_id, job_id, source_id, from_node_id, to_node_id, edge_type, resolution_status, confidence, metadata_json, status, created_at)
                VALUES ('constraint-edge', 'constraint:snapshot', 'constraint-job', 'constraint-source-b', 'constraint-node', 'constraint-node', 'CALLS', 'RESOLVED', 1.0, '{}', 'TRUSTED', '2026-01-01T00:00:00Z')
                """
            )
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                """
                INSERT INTO analysis_graph_claims(id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence, status, evidence_ids_json, metadata_json, created_at)
                VALUES ('constraint-claim', 'constraint:snapshot', 'constraint-job', 'constraint-source-b', 'constraint-node', 'ROLE', 'summary', 1.0, 'TRUSTED', '[]', '{}', '2026-01-01T00:00:00Z')
                """
            )


def current_snapshot(db_path, source_id: str):
    with sqlite3.connect(db_path) as conn:
        row = conn.execute("SELECT snapshot_id FROM graph_current_snapshots WHERE source_id = ?", (source_id,)).fetchone()
    return row[0] if row else None


def snapshot_state(db_path, snapshot_id: str):
    with sqlite3.connect(db_path) as conn:
        row = conn.execute("SELECT state FROM graph_snapshots WHERE snapshot_id = ?", (snapshot_id,)).fetchone()
    return row[0] if row else None


def graph_revision_for_snapshot(db_path, snapshot_id: str, source_id: str, flow_domain: str | None = None) -> str:
    store = AnalysisStore(db_path)
    with store._connect() as conn:
        query = store._graph_query(conn, "manifest", snapshot_id, source_id, flow_domain, None, None, None, "show", True, True)
        return store._graph_snapshot_revision(query)


def graph_counts(db_path):
    with sqlite3.connect(db_path) as conn:
        return {
            "snapshots": conn.execute("SELECT COUNT(*) FROM graph_snapshots").fetchone()[0],
            "nodes": conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0],
            "edges": conn.execute("SELECT COUNT(*) FROM analysis_graph_edges").fetchone()[0],
            "current": conn.execute("SELECT COUNT(*) FROM graph_current_snapshots").fetchone()[0],
        }


def snapshot_metric_count(db_path, snapshot_id: str) -> int:
    with sqlite3.connect(db_path) as conn:
        return conn.execute("SELECT COUNT(*) FROM graph_snapshot_metrics WHERE snapshot_id = ?", (snapshot_id,)).fetchone()[0]


def migrated_claim_count(db_path) -> int:
    with sqlite3.connect(db_path) as conn:
        return conn.execute("SELECT COUNT(*) FROM analysis_graph_claims").fetchone()[0]


def edge_endpoint_violations(db_path) -> int:
    with sqlite3.connect(db_path) as conn:
        return conn.execute(
            """
            SELECT COUNT(*)
            FROM analysis_graph_edges edge
            LEFT JOIN analysis_graph_nodes source_node
              ON source_node.snapshot_id = edge.snapshot_id
             AND source_node.id = edge.from_node_id
            LEFT JOIN analysis_graph_nodes target_node
              ON target_node.snapshot_id = edge.snapshot_id
             AND target_node.id = edge.to_node_id
            WHERE source_node.id IS NULL
               OR (edge.to_node_id IS NOT NULL AND target_node.id IS NULL)
            """
        ).fetchone()[0]


def legacy_counts(db_path):
    with sqlite3.connect(db_path) as conn:
        return {
            table: conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            for table in ("analysis_symbols", "analysis_symbol_roles", "analysis_relations")
        }


def create_legacy_symbol_relation_fixture(db_path) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            CREATE TABLE analysis_symbols (
                symbol_id TEXT PRIMARY KEY,
                file_id INTEGER NOT NULL,
                source_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                summary TEXT,
                metadata_json TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE analysis_symbol_roles (
                symbol_id TEXT NOT NULL,
                role TEXT NOT NULL,
                confidence REAL NOT NULL,
                evidence_json TEXT NOT NULL,
                classifier TEXT NOT NULL,
                classifier_version TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE analysis_relations (
                relation_id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                from_symbol_id TEXT NOT NULL,
                to_symbol_id TEXT NOT NULL,
                relation TEXT NOT NULL,
                confidence REAL NOT NULL,
                evidence_json TEXT NOT NULL,
                line_start INTEGER NOT NULL,
                line_end INTEGER NOT NULL,
                metadata_json TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            INSERT INTO analysis_symbols(symbol_id, file_id, source_id, relative_path, name, kind, line_start, line_end, summary, metadata_json)
            VALUES
              ('legacy-a', 1, 'legacy-source', 'src/A.java', 'A', 'CLASS', 1, 4, 'A type', ?),
              ('legacy-b', 1, 'legacy-source', 'src/A.java', 'call', 'METHOD', 2, 3, 'call method', ?),
              ('legacy-c', 2, 'legacy-source', 'src/B.java', 'field', 'FIELD', 8, 8, 'field', ?),
              ('config-a', 3, 'config-source', 'config/app.yaml', 'app.yaml', 'FILE', 1, 20, 'config file', ?),
              ('config-b', 3, 'config-source', 'config/app.yaml', 'timeout', 'SETTING', 3, 3, 'timeout setting', ?)
            """,
            (
                json.dumps({"flowDomain": "CODE", "createdAt": now}),
                json.dumps({"flowDomain": "CODE", "createdAt": now}),
                json.dumps({"flowDomain": "CODE", "createdAt": now}),
                json.dumps({"flowDomain": "CONFIG", "createdAt": now}),
                json.dumps({"flowDomain": "CONFIG", "createdAt": now}),
            ),
        )
        conn.execute(
            """
            INSERT INTO analysis_symbol_roles(symbol_id, role, confidence, evidence_json, classifier, classifier_version)
            VALUES
              ('legacy-b', 'HTTP_HANDLER', 0.9, '["annotation"]', 'legacy', '1'),
              ('legacy-c', 'STATE_FIELD', 0.7, '["assignment"]', 'legacy', '1'),
              ('config-b', 'CONFIG_VALUE', 0.8, '["yaml-key"]', 'legacy', '1')
            """
        )
        conn.execute(
            """
            INSERT INTO analysis_relations(relation_id, source_id, from_symbol_id, to_symbol_id, relation, confidence, evidence_json, line_start, line_end, metadata_json)
            VALUES
              ('legacy-edge', 'legacy-source', 'legacy-a', 'legacy-b', 'DECLARES', 0.95, '["nested"]', 1, 3, ?),
              ('legacy-edge-2', 'legacy-source', 'legacy-b', 'legacy-c', 'REFERENCES', 0.75, '["field-use"]', 2, 8, ?),
              ('config-edge', 'config-source', 'config-a', 'config-b', 'DECLARES', 0.85, '["yaml-path"]', 1, 3, ?)
            """,
            (
                json.dumps({"flowDomain": "CODE"}),
                json.dumps({"flowDomain": "CODE"}),
                json.dumps({"flowDomain": "CONFIG"}),
            ),
        )
        conn.execute(
            """
            CREATE TABLE graph_snapshots (
                snapshot_id TEXT PRIMARY KEY,
                source_id TEXT NOT NULL,
                job_id TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at TEXT NOT NULL,
                published_at TEXT,
                manifest_json TEXT NOT NULL DEFAULT '{}',
                content_identity TEXT
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE graph_current_snapshots (
                source_id TEXT PRIMARY KEY,
                snapshot_id TEXT NOT NULL,
                published_at TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE analysis_graph_nodes (
                id TEXT NOT NULL,
                snapshot_id TEXT,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
                stable_key TEXT NOT NULL,
                node_kind TEXT NOT NULL,
                language TEXT,
                name TEXT NOT NULL,
                qualified_name TEXT,
                display_name TEXT,
                parent_node_id TEXT,
                line_start INTEGER,
                line_end INTEGER,
                confidence REAL NOT NULL,
                status TEXT NOT NULL,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                PRIMARY KEY(snapshot_id, id)
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE analysis_graph_diagnostics (
                id TEXT NOT NULL,
                snapshot_id TEXT,
                job_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                inventory_file_id INTEGER,
                analysis_file_id INTEGER,
                severity TEXT NOT NULL,
                stage TEXT NOT NULL,
                code TEXT NOT NULL,
                message TEXT NOT NULL,
                candidate_id TEXT,
                line_start INTEGER,
                line_end INTEGER,
                metadata_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                fact_origin TEXT,
                flow_domain TEXT,
                PRIMARY KEY(snapshot_id, id)
            )
            """
        )
        conn.execute(
            """
            INSERT INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
            VALUES ('partial:legacy-source', 'legacy-source', 'partial', 'BUILDING', ?, NULL, '{}', NULL)
            """,
            (now,),
        )
        conn.execute(
            """
            INSERT INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES ('legacy-source', 'partial:legacy-source', ?)
            """,
            (now,),
        )
        conn.execute(
            """
            INSERT INTO analysis_graph_nodes(
                id, snapshot_id, job_id, source_id, stable_key, node_kind, name, confidence, status, metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES ('partial-node', 'partial:legacy-source', 'partial', 'legacy-source', 'partial-node', 'CALLABLE', 'partial', 0.5, 'DERIVED', '{}', ?, 'STATIC', 'CODE')
            """,
            (now,),
        )
        conn.execute(
            """
            INSERT INTO analysis_graph_diagnostics(
                id, snapshot_id, job_id, source_id, severity, stage, code, message, metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES ('partial-diagnostic', 'partial:legacy-source', 'partial', 'legacy-source', 'WARN', 'MIGRATION_FIXTURE', 'PARTIAL', 'partial row', '{}', ?, 'STATIC', 'CODE')
            """,
            (now,),
        )


def insert_graph_snapshot(db_path, source_id: str, snapshot_id: str, node_count: int, edge_count: int, state: str = "BUILDING", job_id: str | None = None) -> None:
    now = datetime.now(timezone.utc).isoformat()
    job = job_id or snapshot_id.split(":", 1)[0]
    prefix = snapshot_id.split(":", 1)[0]
    file_id = abs(hash((source_id, snapshot_id))) % 1000000 + 100
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute(
            """
            INSERT OR IGNORE INTO sources(source_id, display_name, group_name, path, root_exists, tags_json, metadata_json, last_seen_at)
            VALUES (?, ?, 'platform', '.', 1, '[]', '{}', ?)
            """,
            (source_id, source_id, now),
        )
        conn.execute(
            """
            INSERT OR IGNORE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version, flow_domain)
            VALUES (?, ?, ?, ?, 'fixture', '1', 'ANALYZED', ?, ?, ?, '[]', 'GRAPH_V1', 'CODE')
            """,
            (file_id, source_id, f"src/{prefix}.java", f"hash-{snapshot_id}", now, node_count, edge_count),
        )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, manifest_json)
            VALUES (?, ?, ?, ?, ?, '{}')
            """,
            (snapshot_id, source_id, job, state, now),
        )
        for index in range(node_count):
            node_id = f"{prefix}-node-{index:05d}" if prefix not in {"job-1", "fixture"} else f"node-{index:05d}"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_nodes(
                    id, snapshot_id, job_id, source_id, stable_key, node_kind, name, confidence, status, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, 'CALLABLE', ?, 0.9, 'TRUSTED', '{}', ?, 'STATIC', 'CODE')
                """,
                (node_id, snapshot_id, job, source_id, f"stable:{snapshot_id}:{node_id}", node_id, now),
            )
        if node_count:
            first_node = f"{prefix}-node-00000" if prefix not in {"job-1", "fixture"} else "node-00000"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                    line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, 'SUMMARY', ?, 1, 1, '{}', ?, 'STATIC', 'CODE')
                """,
                (f"{prefix}-evidence-00000", snapshot_id, job, source_id, file_id, f"hash-{snapshot_id}", f"excerpt-{snapshot_id}", now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_claims(
                    id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence,
                    status, evidence_ids_json, metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, 'RESPONSIBILITY', 'Fixture responsibility', 0.8,
                        'TRUSTED', ?, '{}', NULL, ?, 'STATIC', 'CODE')
                """,
                (f"{prefix}-claim-00000", snapshot_id, job, source_id, first_node, json.dumps([f"{prefix}-evidence-00000"]), now),
            )
        conn.execute(
            """
            INSERT OR REPLACE INTO analysis_graph_diagnostics(
                id, snapshot_id, job_id, source_id, analysis_file_id, severity, stage, code, message,
                metadata_json, created_at, fact_origin, flow_domain
            )
            VALUES (?, ?, ?, ?, ?, 'INFO', 'FIXTURE', 'GRAPH_FIXTURE', 'fixture diagnostic', '{}', ?, 'STATIC', 'CODE')
            """,
            (f"{prefix}-diagnostic-00000", snapshot_id, job, source_id, file_id, now),
        )
        for index in range(edge_count):
            from_node = f"{prefix}-node-{index % max(node_count, 1):05d}" if prefix not in {"job-1", "fixture"} else f"node-{index % max(node_count, 1):05d}"
            to_node = f"{prefix}-node-{(index + 1) % max(node_count, 1):05d}" if prefix not in {"job-1", "fixture"} else f"node-{(index + 1) % max(node_count, 1):05d}"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, from_node_id, to_node_id, edge_type, resolution_status, confidence, metadata_json, status, created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, ?, ?, ?, ?, 'CALLS', 'RESOLVED', 0.8, '{}', 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (f"{prefix}-edge-{index:05d}", snapshot_id, job, source_id, from_node, to_node, now),
            )
    if state == "PUBLISHED":
        refresh_snapshot_projection(db_path, snapshot_id, source_id, now)


def seed_graph_fixture(db_path, node_count: int, edge_count: int) -> None:
    now = datetime.now(timezone.utc).isoformat()
    snapshot_id = "job-1:forge-ai"
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
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
            INSERT OR REPLACE INTO analysis_files(file_id, source_id, relative_path, content_hash, analyzer_name, analyzer_version, status, analyzed_at, symbol_count, relation_count, diagnostics_json, engine_version)
            VALUES (1, 'forge-ai', 'src/GraphFixture.java', 'hash-1', 'fixture', '1', 'ANALYZED', ?, ?, ?, '[]', 'GRAPH_V1')
            """,
            (now, node_count, edge_count),
        )
        manifest = {
            "graphRevision": f"forge-ai:CODE:graph-snapshot:fixture",
            "snapshotId": snapshot_id,
            "sourceId": "forge-ai",
            "totalNodeCount": node_count,
            "totalEdgeCount": edge_count,
            "nodeTypeCounts": {kind: sum(1 for index in range(node_count) if ["FILE", "TYPE", "CALLABLE", "FIELD", "EXTERNAL"][index % 5] == kind) for kind in ["FILE", "TYPE", "CALLABLE", "FIELD", "EXTERNAL"]},
            "edgeTypeCounts": {kind: sum(1 for index in range(edge_count) if ["DECLARES", "CALLS", "REFERENCES", "IMPORTS"][index % 4] == kind) for kind in ["DECLARES", "CALLS", "REFERENCES", "IMPORTS"]},
        }
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_snapshots(snapshot_id, source_id, job_id, state, created_at, published_at, manifest_json, content_identity)
            VALUES (?, 'forge-ai', 'job-1', 'PUBLISHED', ?, ?, ?, ?)
            """,
            (snapshot_id, now, now, json.dumps(manifest), manifest["graphRevision"]),
        )
        for index in range(node_count):
            kind = ["FILE", "TYPE", "CALLABLE", "FIELD", "EXTERNAL"][index % 5]
            node_id = f"node-{index:05d}"
            degree_target = None if index == node_count - 1 else "node-00000"
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_nodes(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, stable_key, node_kind, language, name,
                    qualified_name, display_name, parent_node_id, line_start, line_end, confidence, status, metadata_json,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, 'job-1', 'forge-ai', 1, 1, ?, ?, 'java', ?, ?, ?, ?, ?, ?, 0.9, 'TRUSTED', ?, ?, 'STATIC', 'CODE')
                """,
                (
                    node_id,
                    snapshot_id,
                    f"stable:{node_id}",
                    kind,
                    f"Name{index}",
                    f"com.example.Name{index}",
                    f"Name{index}",
                    degree_target if index % 7 == 0 else None,
                    index + 1,
                    index + 1,
                    json.dumps({"displayScore": 1.0 / (index + 1)}),
                    now,
                ),
            )
        if node_count:
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                    line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES ('evidence-node-0', ?, 'job-1', 'forge-ai', 1, 'hash-1', 'SUMMARY', 'node-hash', 1, 1, '{}', ?, 'STATIC', 'CODE')
                """,
                (snapshot_id, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_evidence(
                    id, snapshot_id, job_id, source_id, analysis_file_id, content_hash, evidence_kind, excerpt_hash,
                    line_start, line_end, metadata_json, created_at, fact_origin, flow_domain
                )
                VALUES ('evidence-edge-0', ?, 'job-1', 'forge-ai', 1, 'hash-1', 'CALLSITE', 'edge-hash', 2, 2, '{}', ?, 'STATIC', 'CODE')
                """,
                (snapshot_id, now),
            )
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_claims(
                    id, snapshot_id, job_id, source_id, node_id, claim_kind, summary, confidence,
                    status, evidence_ids_json, metadata_json, rejection_reason, created_at, fact_origin, flow_domain
                )
                VALUES ('claim-node-0', ?, 'job-1', 'forge-ai', 'node-00000', 'RESPONSIBILITY', 'Fixture responsibility', 0.8,
                        'TRUSTED', '["evidence-node-0"]', '{"legacy":false}', NULL, ?, 'STATIC', 'CODE')
                """,
                (snapshot_id, now),
            )
        for index in range(edge_count):
            from_node = f"node-{index % (node_count - 1):05d}"
            to_node = f"node-{(index * 7 + 3) % (node_count - 1):05d}"
            relation = ["DECLARES", "CALLS", "REFERENCES", "IMPORTS"][index % 4]
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, 'job-1', 'forge-ai', 1, 1, ?, ?, ?, 'RESOLVED', 0.8, ?, NULL, ?, 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (
                    f"edge-{index:05d}",
                    snapshot_id,
                    from_node,
                    to_node,
                    relation,
                    "evidence-edge-0" if index == 0 else None,
                    json.dumps({"flowScore": 0.5}),
                    now,
                ),
            )
        conn.execute(
            """
            INSERT OR REPLACE INTO graph_current_snapshots(source_id, snapshot_id, published_at)
            VALUES ('forge-ai', ?, ?)
            """,
            (snapshot_id, now),
        )

    refresh_snapshot_projection(db_path, snapshot_id, "forge-ai", now)


def add_node_detail_relation_fixture(db_path, outgoing_count: int, incoming_count: int) -> None:
    now = datetime.now(timezone.utc).isoformat()
    snapshot_id = "job-1:forge-ai"
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        for index in range(outgoing_count):
            target_index = (index % 39) + 1
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, 'job-1', 'forge-ai', 1, 1, 'node-00000', ?, 'CALLS',
                        'RESOLVED', 0.8, NULL, NULL, ?, 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (
                    f"node-detail-out-{index:05d}",
                    snapshot_id,
                    f"node-{target_index:05d}",
                    json.dumps({"sourcePath": "src/GraphFixture.java", "lineStart": index + 10, "lineEnd": index + 10}),
                    now,
                ),
            )
        for index in range(incoming_count):
            source_index = (index % 39) + 1
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, 'job-1', 'forge-ai', 1, 1, ?, 'node-00000', 'REFERENCES',
                        'RESOLVED', 0.8, NULL, NULL, ?, 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (
                    f"node-detail-in-{index:05d}",
                    snapshot_id,
                    f"node-{source_index:05d}",
                    json.dumps({"sourcePath": "src/GraphFixture.java", "lineStart": index + 40, "lineEnd": index + 40}),
                    now,
                ),
            )


def replace_edges_with_late_connected_cluster(db_path, start: int, node_count: int, edge_count: int) -> None:
    now = datetime.now(timezone.utc).isoformat()
    snapshot_id = "job-1:forge-ai"
    with sqlite3.connect(db_path) as conn:
        conn.execute("DELETE FROM analysis_graph_edges WHERE snapshot_id = ?", (snapshot_id,))
        cluster_size = max(1, node_count - start)
        for index in range(edge_count):
            from_index = start + (index % cluster_size)
            to_index = start + ((index + 1) % cluster_size)
            conn.execute(
                """
                INSERT OR REPLACE INTO analysis_graph_edges(
                    id, snapshot_id, job_id, source_id, inventory_file_id, analysis_file_id, from_node_id, to_node_id, edge_type,
                    resolution_status, confidence, evidence_id, unresolved_target_json, metadata_json, status,
                    created_at, fact_origin, flow_domain
                )
                VALUES (?, ?, 'job-1', 'forge-ai', 1, 1, ?, ?, 'CALLS', 'RESOLVED', 0.8, NULL, NULL, ?, 'TRUSTED', ?, 'STATIC', 'CODE')
                """,
                (
                    f"edge-{index:05d}",
                    snapshot_id,
                    f"node-{from_index:05d}",
                    f"node-{to_index:05d}",
                    json.dumps({"flowScore": 0.9}),
                    now,
                ),
            )
        conn.execute(
            "UPDATE analysis_files SET relation_count = ? WHERE file_id = 1",
            (edge_count,),
        )
    refresh_snapshot_projection(db_path, snapshot_id, "forge-ai", now)


def refresh_snapshot_projection(db_path, snapshot_id: str, source_id: str, published_at: str | None = None) -> None:
    store = AnalysisStore(db_path)

    def refresh(conn):
        store._rebuild_graph_snapshot_metrics(conn, snapshot_id, source_id)
        manifest = store._stored_graph_manifest(conn, snapshot_id, source_id, published_at)
        conn.execute(
            """
            UPDATE graph_snapshots
            SET manifest_json = ?, content_identity = ?
            WHERE snapshot_id = ?
            """,
            (json.dumps(manifest, sort_keys=True), manifest["graphRevision"], snapshot_id),
        )

    store._write_with_busy_retry(refresh)
