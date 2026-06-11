from knowledge_service.inventory_builder import InventoryBuilder
from knowledge_service.inventory_store import InventoryStore
from knowledge_service.search_service import SearchService
from knowledge_service.source_config import load_source_config


def build_store(tmp_path):
    workspace = tmp_path / "workspace"
    service = workspace / "svc"
    service.mkdir(parents=True)
    (service / "README.md").write_text("JarvisGateway appears here\n", encoding="utf-8")
    (service / "ignored.py").write_text("OutsideInventory\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text("services:\n  svc:\n    label: Service\n    path: svc\n    tags: [backend]\n    domain_keywords: [payments]\n", encoding="utf-8")
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(f"catalog:\n  path: \"{catalog}\"\n  workspace_root: \"{workspace}\"\nindexing:\n  include: [\"**/*.md\"]\n", encoding="utf-8")
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    InventoryBuilder(load_source_config(config_file), store).build([], [])
    return store


def test_search_returns_path_and_content_matches(tmp_path):
    store = build_store(tmp_path)
    service = SearchService(store)

    assert service.search("README", [], [], 10)["results"][0]["matchType"] == "path"
    content = service.search("JarvisGateway", [], [], 10)["results"][0]
    assert content["matchType"] == "content"
    assert content["lineStart"] == 1


def test_search_empty_inventory_and_does_not_read_outside_inventory(tmp_path):
    empty = SearchService(InventoryStore(tmp_path / "empty.sqlite")).search("x", [], [], 10)
    assert empty["message"] == "Inventory is empty. Build inventory first."

    store = build_store(tmp_path)
    assert SearchService(store).search("OutsideInventory", [], [], 10)["results"] == []
