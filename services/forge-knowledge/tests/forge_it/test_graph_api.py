from __future__ import annotations

import json
import sqlite3
from urllib.parse import quote

import pytest
from semantic_test_support import seed_semantic_graph
from support import AsgiTestClient as TestClient
from support import build_test_app, write_runtime_config

from knowledge_service.analysis_store import AnalysisStore


pytestmark = pytest.mark.forge_it


LEGACY_GRAPH_OBJECTS = {"analysis_symbols", "analysis_symbol_roles", "analysis_relations"}


def test_it_graph_01_fresh_database_schema_is_current_state_only(tmp_path):
    _, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()

    objects = sqlite_objects(app_config.store_path)

    assert LEGACY_GRAPH_OBJECTS.isdisjoint(objects)
    assert not any(name.startswith("graph_") for name in objects)
    assert {"analysis_graph_state", "analysis_graph_nodes", "analysis_graph_edges"}.issubset(objects)
    for table in ("analysis_graph_nodes", "analysis_graph_edges", "analysis_graph_claims", "analysis_graph_evidence"):
        assert {"id", "source_id"}.issubset(table_columns(app_config.store_path, table))
    assert {"document_id", "source_id", "node_id", "graph_id"}.issubset(table_columns(app_config.store_path, "semantic_documents"))


def test_it_graph_02_destructive_migration_drops_old_graph_storage(tmp_path):
    db_path = tmp_path / "legacy.sqlite"
    create_rejected_graph_storage_fixture(db_path)

    AnalysisStore(db_path).init()

    objects = sqlite_objects(db_path)
    assert not any(name.startswith("graph_") for name in objects)
    assert "analysis_graph_state" in objects
    assert {"id", "source_id", "analysis_file_id", "file_id"}.issubset(table_columns(db_path, "analysis_graph_nodes"))
    assert graph_counts(db_path) == {"state": 0, "nodes": 0, "edges": 0}
    assert sqlite_integrity(db_path) == ("ok", [])


def test_it_graph_03_manifest_pages_details_and_view_use_graph_id(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=6, edge_count=5)

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()
        nodes = traverse_pages(client, "nodes", manifest["graphRevision"], 2)
        edges = traverse_pages(client, "edges", manifest["graphRevision"], 2)
        node_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/node/{quote(nodes[0]['id'])}?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
        ).json()
        edge_detail = client.get(
            f"/api/v1/knowledge/analysis/graph/edge/{quote(edges[0]['id'])}?sourceId=forge-ai&graphRevision={quote(manifest['graphRevision'])}"
        ).json()
        view = client.get(
            f"/api/v1/knowledge/analysis/graph/view?sourceId=forge-ai&flowDomain=CODE&maxNodes=10"
        ).json()

    assert manifest["graphId"]
    assert manifest["totalNodeCount"] == 6
    assert manifest["totalEdgeCount"] == 5
    assert len(nodes) == 6
    assert len(edges) == 5
    assert node_detail["graphId"] == manifest["graphId"]
    assert edge_detail["graphId"] == manifest["graphId"]
    assert view["graphId"] == manifest["graphId"]
    assert {edge["from"] for edge in view["edges"]}.issubset({node["id"] for node in view["nodes"]})


def test_it_graph_04_revision_rejects_stale_graph_id_after_replace(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=4, edge_count=3, graph_suffix="first")

    with TestClient(app) as client:
        manifest = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()

    seed_graph_fixture(app_config.store_path, node_count=2, edge_count=1, graph_suffix="second")

    with TestClient(app) as client:
        stale = client.get(
            f"/api/v1/knowledge/analysis/graph/nodes?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(manifest['graphRevision'])}&pageSize=10"
        )
        current = client.get("/api/v1/knowledge/analysis/graph/manifest?sourceId=forge-ai&flowDomain=CODE").json()

    assert stale.status_code in {400, 409}
    assert stale.json()["code"] == "GRAPH_REVISION_STALE"
    assert current["graphId"] != manifest["graphId"]
    assert current["totalNodeCount"] == 2


def test_it_graph_05_current_state_integrity_counts_are_clean(tmp_path):
    app, _, app_config, deps = build_test_app(write_runtime_config(tmp_path))
    deps.inventory_store.init()
    deps.analysis_store.init()
    seed_graph_fixture(app_config.store_path, node_count=5, edge_count=4)

    with sqlite3.connect(app_config.store_path) as conn:
        conn.row_factory = sqlite3.Row
        counts = current_graph_integrity_counts(conn)

    assert counts == {
        "orphan_semantic_documents": 0,
        "orphan_semantic_vectors": 0,
        "graph_nodes_without_analysis_parent": 0,
        "graph_nodes_without_inventory_parent": 0,
        "semantic_docs_missing_graph_node": 0,
    }


def seed_graph_fixture(
    db_path,
    *,
    source_id: str = "forge-ai",
    node_count: int = 4,
    edge_count: int = 3,
    graph_suffix: str = "one",
) -> str:
    nodes = [
        {
            "id": f"node-{index:05d}",
            "kind": "CALLABLE",
            "name": f"Service{index}.handle",
            "qualified": f"fixture.Service{index}.handle",
            "path": f"src/service_{index:05d}.py",
            "line_start": index + 1,
            "line_end": index + 1,
        }
        for index in range(node_count)
    ]
    edges = []
    for index in range(edge_count):
        source_index = index % max(1, node_count)
        target_index = (index + 1) % max(1, node_count)
        edges.append(
            {
                "id": f"edge-{index:05d}",
                "from": f"node-{source_index:05d}",
                "to": f"node-{target_index:05d}" if node_count else None,
                "type": "CALLS",
            }
        )
    return seed_semantic_graph(
        db_path,
        source_id=source_id,
        graph_suffix=graph_suffix,
        nodes=nodes,
        edges=edges,
        refresh_overview=True,
    )


def traverse_pages(client, resource: str, graph_revision: str, page_size: int):
    items = []
    cursor = None
    while True:
        cursor_param = f"&cursor={quote(cursor)}" if cursor else ""
        response = client.get(
            f"/api/v1/knowledge/analysis/graph/{resource}?sourceId=forge-ai&flowDomain=CODE&graphRevision={quote(graph_revision)}&pageSize={page_size}{cursor_param}"
        )
        assert response.status_code == 200
        page = response.json()
        assert "graphId" in page
        items.extend(page["items"])
        if page["complete"]:
            return items
        cursor = page["nextCursor"]


def create_rejected_graph_storage_fixture(db_path) -> None:
    with sqlite3.connect(db_path) as conn:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.executescript(
            """
            CREATE TABLE graph_old_parent(id TEXT PRIMARY KEY, source_id TEXT NOT NULL);
            CREATE TABLE graph_old_current(
                id TEXT PRIMARY KEY,
                parent_id TEXT NOT NULL,
                FOREIGN KEY(parent_id) REFERENCES graph_old_parent(id) ON DELETE RESTRICT
            );
            CREATE TABLE graph_old_metrics(
                id TEXT PRIMARY KEY,
                parent_id TEXT NOT NULL,
                metric_value INTEGER,
                FOREIGN KEY(parent_id) REFERENCES graph_old_parent(id) ON DELETE CASCADE
            );
            CREATE TABLE analysis_graph_nodes(
                id TEXT,
                obsolete_graph_ref TEXT,
                source_id TEXT,
                PRIMARY KEY(obsolete_graph_ref, id),
                FOREIGN KEY(obsolete_graph_ref) REFERENCES graph_old_parent(id) ON DELETE CASCADE
            );
            CREATE TABLE analysis_graph_edges(id TEXT, obsolete_graph_ref TEXT, source_id TEXT, from_node_id TEXT, to_node_id TEXT);
            INSERT INTO graph_old_parent(id, source_id) VALUES ('old-graph', 'forge-ai');
            INSERT INTO graph_old_current(id, parent_id) VALUES ('current', 'old-graph');
            INSERT INTO graph_old_metrics(id, parent_id, metric_value) VALUES ('nodes', 'old-graph', 1);
            INSERT INTO analysis_graph_nodes(id, obsolete_graph_ref, source_id)
            VALUES ('legacy-node', 'old-graph', 'forge-ai');
            """
        )


def sqlite_objects(db_path) -> set[str]:
    with sqlite3.connect(db_path) as conn:
        return {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'view', 'trigger', 'index')").fetchall()}


def table_columns(db_path, table: str) -> set[str]:
    with sqlite3.connect(db_path) as conn:
        return {row[1] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}


def graph_counts(db_path) -> dict[str, int]:
    with sqlite3.connect(db_path) as conn:
        return {
            "state": conn.execute("SELECT COUNT(*) FROM analysis_graph_state").fetchone()[0],
            "nodes": conn.execute("SELECT COUNT(*) FROM analysis_graph_nodes").fetchone()[0],
            "edges": conn.execute("SELECT COUNT(*) FROM analysis_graph_edges").fetchone()[0],
        }


def current_graph_integrity_counts(conn: sqlite3.Connection) -> dict[str, int]:
    return {
        "orphan_semantic_documents": conn.execute(
            """
            SELECT COUNT(*)
            FROM semantic_documents d
            LEFT JOIN analysis_graph_nodes n ON n.id = d.node_id AND n.source_id = d.source_id
            WHERE n.id IS NULL
            """
        ).fetchone()[0],
        "orphan_semantic_vectors": conn.execute(
            """
            SELECT COUNT(*)
            FROM semantic_vectors v
            LEFT JOIN semantic_documents d ON d.document_id = v.document_id
            WHERE d.document_id IS NULL
            """
        ).fetchone()[0],
        "graph_nodes_without_analysis_parent": conn.execute(
            """
            SELECT COUNT(*)
            FROM analysis_graph_nodes n
            LEFT JOIN analysis_files af
              ON af.file_id = n.analysis_file_id
             AND af.source_id = n.source_id
             AND af.relative_path = n.relative_path
             AND af.content_hash = n.content_hash
            WHERE af.file_id IS NULL
            """
        ).fetchone()[0],
        "graph_nodes_without_inventory_parent": conn.execute(
            """
            SELECT COUNT(*)
            FROM analysis_graph_nodes n
            LEFT JOIN files f
              ON f.id = n.file_id
             AND f.source_id = n.source_id
             AND f.relative_path = n.relative_path
             AND f.content_hash = n.content_hash
            WHERE f.id IS NULL
            """
        ).fetchone()[0],
        "semantic_docs_missing_graph_node": conn.execute(
            """
            SELECT COUNT(*)
            FROM semantic_documents d
            LEFT JOIN analysis_graph_nodes n ON n.id = d.node_id AND n.source_id = d.source_id
            WHERE n.id IS NULL
            """
        ).fetchone()[0],
    }


def sqlite_integrity(db_path):
    with sqlite3.connect(db_path) as conn:
        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        foreign_key_rows = conn.execute("PRAGMA foreign_key_check").fetchall()
    return integrity, foreign_key_rows
