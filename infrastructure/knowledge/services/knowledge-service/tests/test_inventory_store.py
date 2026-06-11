from knowledge_service.inventory_store import InventoryStore


def test_empty_inventory_status(tmp_path):
    assert InventoryStore(tmp_path / "knowledge.sqlite").status()["status"] == "EMPTY"
