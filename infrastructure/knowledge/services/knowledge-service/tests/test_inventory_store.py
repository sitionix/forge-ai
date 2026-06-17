from knowledge_service.inventory_store import InventoryStore


def test_empty_inventory_status(tmp_path):
    status = InventoryStore(tmp_path / "knowledge.sqlite").status()

    assert status["status"] == "EMPTY"
    assert status["skippedCount"] == 0
    assert status["skippedBreakdown"] == {"total": 0, "byReason": {}}
