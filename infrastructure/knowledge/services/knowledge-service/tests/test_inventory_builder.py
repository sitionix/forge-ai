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


def test_inventory_status_returns_counts_and_files_paginate(tmp_path):
    config = make_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(config, store).build([], [])

    assert store.status()["fileCount"] == 1
    assert store.status()["skippedBreakdown"]["byReason"]["TOO_LARGE"] == 1
    page = store.files(None, None, None, 1, 0)
    assert page["limit"] == 1
    assert page["total"] == 1


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
