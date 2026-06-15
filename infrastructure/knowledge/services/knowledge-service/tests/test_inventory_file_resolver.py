from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_file_resolver import InventoryFileResolver
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.source_config import load_source_config


def build_inventory(tmp_path, content=b"class App {}\n"):
    workspace = tmp_path / "workspace"
    service = workspace / "svc"
    (service / "src").mkdir(parents=True)
    (service / "src" / "App.java").write_bytes(content)
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  svc:
    label: Service
    path: svc
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
    return store, service / "src" / "App.java"


def first_row(store):
    rows, _ = store.search_rows([], [])
    assert len(rows) == 1
    return rows[0]


def test_inventory_persists_line_count_and_decode_policy_for_invalid_utf8(tmp_path):
    store, _ = build_inventory(tmp_path, b"class App {}\n// bad: \xff\n")

    file = store.files(None, None, None, 10, 0)["files"][0]

    assert file["lineCount"] == 2
    assert file["decodePolicy"] == "utf-8:replace"


def test_inventory_persists_zero_line_count_for_empty_file(tmp_path):
    store, _ = build_inventory(tmp_path, b"")

    file = store.files(None, None, None, 10, 0)["files"][0]

    assert file["lineCount"] == 0
    assert file["decodePolicy"] == "utf-8:replace"


def test_resolver_reads_normal_indexed_file(tmp_path):
    store, _ = build_inventory(tmp_path)

    result = InventoryFileResolver(store).read(first_row(store))

    assert result.ok
    assert result.content.lines == ["class App {}"]
    assert result.content.content == "class App {}"
    assert result.content.lineCount == 1
    assert result.content.decodePolicy == "utf-8:replace"


def test_resolver_rejects_path_traversal_outside_source_root(tmp_path):
    store, _ = build_inventory(tmp_path)
    outside = tmp_path / "outside.java"
    outside.write_text("class Outside {}\n", encoding="utf-8")
    with store._connect() as conn:
        conn.execute("UPDATE files SET absolute_path = ? WHERE relative_path = ?", (str(outside), "src/App.java"))

    result = InventoryFileResolver(store).read(first_row(store))

    assert not result.ok
    assert result.diagnostic["code"] == "FILE_OUTSIDE_SOURCE_ROOT"


def test_resolver_rejects_symlink_escape_outside_source_root(tmp_path):
    store, path = build_inventory(tmp_path)
    outside = tmp_path / "outside.java"
    outside.write_text("class Outside {}\n", encoding="utf-8")
    path.unlink()
    try:
        path.symlink_to(outside)
    except OSError:
        return

    result = InventoryFileResolver(store).read(first_row(store))

    assert not result.ok
    assert result.diagnostic["code"] == "FILE_OUTSIDE_SOURCE_ROOT"


def test_resolver_returns_controlled_diagnostic_for_missing_file(tmp_path):
    store, path = build_inventory(tmp_path)
    path.unlink()

    result = InventoryFileResolver(store).read(first_row(store))

    assert not result.ok
    assert result.diagnostic["code"] == "FILE_MISSING"


def test_resolver_reads_by_inventory_file_id(tmp_path):
    store, _ = build_inventory(tmp_path)
    row = first_row(store)

    result = InventoryFileResolver(store).read_by_id(row["id"])

    assert result.ok
    assert result.content.content == "class App {}"
