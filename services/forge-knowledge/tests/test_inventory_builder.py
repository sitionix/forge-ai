from knowledge_service.analysis_store import AnalysisStore
from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.source_config import load_source_config


def make_config(tmp_path):
    workspace = tmp_path / "workspace"
    service = workspace / "svc"
    service.mkdir(parents=True)
    (service / "src").mkdir()
    (service / "src" / "App.java").write_text("class App {}\n", encoding="utf-8")
    (service / "src" / "note.txt").write_text("skip\n", encoding="utf-8")
    (service / "target").mkdir()
    (service / "target" / "Generated.java").write_text("skip\n", encoding="utf-8")
    (service / ".env").write_text("SECRET=x\n", encoding="utf-8")
    (service / "big.md").write_text("x" * 200, encoding="utf-8")
    (service / "binary.md").write_bytes(b"hello\0world")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  svc:
    label: Service
    path: svc
    group: backend
  missing:
    label: Missing
    path: missing
    group: backend
""",
        encoding="utf-8",
    )
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java", "**/*.md"]
  exclude: ["target/**", "**/.env"]
  max_file_size_bytes: 100
""",
        encoding="utf-8",
    )
    return load_source_config(config_file)


def test_inventory_build_scans_existing_selected_sources_and_respects_filters(tmp_path):
    config = make_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    result = InventoryBuilder(config, store).build([], [])

    assert result["sourceCount"] == 1
    assert result["fileCount"] == 1
    assert result["skippedCount"] == 6
    assert result["skippedBreakdown"] == {
        "total": 6,
        "byReason": {
            "NOT_INCLUDED": 1,
            "EXCLUDED_BY_PATTERN": 2,
            "TOO_LARGE": 1,
            "BINARY": 1,
            "MISSING_SOURCE_ROOT": 1,
        },
    }
    files = store.files(None, None, None, 100, 0)
    assert files["total"] == 1
    assert files["files"][0]["relativePath"] == "src/App.java"
    assert files["files"][0]["lineCount"] == 1
    assert files["files"][0]["decodePolicy"] == "utf-8:replace"


def test_inventory_status_returns_counts_and_files_paginate(tmp_path):
    config = make_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(config, store).build([], [])

    assert store.status()["fileCount"] == 1
    assert store.status()["skippedBreakdown"]["byReason"]["TOO_LARGE"] == 1
    page = store.files(None, None, None, 1, 0)
    assert page["limit"] == 1
    assert page["total"] == 1


def test_inventory_build_excludes_idea_project_files(tmp_path):
    workspace = tmp_path / "workspace"
    service = workspace / "svc"
    (service / "src").mkdir(parents=True)
    (service / ".idea").mkdir()
    (service / "src" / "App.java").write_text("class App {}\n", encoding="utf-8")
    (service / ".idea" / "workspace.xml").write_text("<project />\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text("services:\n  svc:\n    label: Service\n    path: svc\n    group: backend\n", encoding="utf-8")
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java", "**/*.xml"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")

    result = InventoryBuilder(load_source_config(config_file), store).build([], [])

    assert result["fileCount"] == 1
    assert result["skippedBreakdown"]["byReason"]["EXCLUDED_BY_PATTERN"] == 1
    files = store.files("svc", None, None, 100, 0)["files"]
    assert [file["relativePath"] for file in files] == ["src/App.java"]


def test_inventory_build_counts_symlink_outside_root_when_supported(tmp_path):
    workspace = tmp_path / "workspace"
    service = workspace / "svc"
    service.mkdir(parents=True)
    outside = tmp_path / "outside.md"
    outside.write_text("outside\n", encoding="utf-8")
    try:
        (service / "outside.md").symlink_to(outside)
    except OSError:
        return
    catalog = tmp_path / "services.yaml"
    catalog.write_text("services:\n  svc:\n    label: Service\n    path: svc\n    group: backend\n", encoding="utf-8")
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.md"]
""",
        encoding="utf-8",
    )

    result = InventoryBuilder(load_source_config(config_file), InventoryStore(tmp_path / "knowledge.sqlite")).build([], [])

    assert result["fileCount"] == 0
    assert result["skippedBreakdown"]["byReason"]["SYMLINK_OUTSIDE_ROOT"] == 1


def test_scoped_inventory_build_preserves_other_service_inventory(tmp_path):
    workspace = tmp_path / "workspace"
    first = workspace / "first-service"
    second = workspace / "second-service"
    (first / "src").mkdir(parents=True)
    (second / "src").mkdir(parents=True)
    (first / "src" / "First.java").write_text("class First {}\n", encoding="utf-8")
    (second / "src" / "Second.java").write_text("class Second {}\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  first-service:
    label: First Service
    path: first-service
    group: backend
  second-service:
    label: Second Service
    path: second-service
    group: backend
""",
        encoding="utf-8",
    )
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    config = load_source_config(config_file)
    store = InventoryStore(tmp_path / "knowledge.sqlite")

    InventoryBuilder(config, store).build([], [])
    (second / "src" / "NewSecond.java").write_text("class NewSecond {}\n", encoding="utf-8")
    result = InventoryBuilder(config, store).build(["second-service"], [])

    assert result["sourceCount"] == 1
    assert store.files("first-service", None, None, 100, 0)["total"] == 1
    assert store.files("second-service", None, None, 100, 0)["total"] == 2


def test_scoped_stale_analysis_cleanup_preserves_other_service_history(tmp_path):
    workspace = tmp_path / "workspace"
    first = workspace / "first-service"
    second = workspace / "second-service"
    (first / "src").mkdir(parents=True)
    (second / "src").mkdir(parents=True)
    (first / "src" / "First.java").write_text("class First {}\n", encoding="utf-8")
    (second / "src" / "Second.java").write_text("class Second {}\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  first-service:
    label: First Service
    path: first-service
    group: backend
  second-service:
    label: Second Service
    path: second-service
    group: backend
""",
        encoding="utf-8",
    )
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java"]
""",
        encoding="utf-8",
    )
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config_file), store).build([], [])
    analysis_store = AnalysisStore(store.db_path)
    with store._connect() as conn:
        rows = conn.execute("SELECT id, source_id, relative_path, content_hash FROM files ORDER BY source_id").fetchall()
        for row in rows:
            analysis_store.mark_file(row["id"], {
                "source_id": row["source_id"],
                "relative_path": row["relative_path"],
                "content_hash": row["content_hash"],
                "analyzer_name": "ai-file-analyzer",
                "analyzer_version": "1",
                "status": "ANALYZED",
                "analyzed_at": "2026-06-14T12:00:00+00:00",
                "symbol_count": 1,
                "relation_count": 0,
                "diagnostics": [],
            })
        conn.execute("DELETE FROM files WHERE source_id = ?", ("first-service",))

    analysis_store.cleanup_stale_files(["second-service"])

    assert analysis_store.files("first-service", None, None, 100, 0)["total"] == 1
    assert analysis_store.files("second-service", None, None, 100, 0)["total"] == 1
