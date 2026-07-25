import sqlite3

from knowledge_service.inventory_store import InventoryStore


def test_empty_inventory_status(tmp_path):
    status = InventoryStore(tmp_path / "knowledge.sqlite").status()

    assert status["status"] == "EMPTY"
    assert status["skippedCount"] == 0
    assert status["skippedBreakdown"] == {"total": 0, "byReason": {}}


def test_inventory_store_uses_wal_and_configurable_busy_timeout(tmp_path):
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    store.init()

    with sqlite3.connect(store.db_path) as conn:
        assert conn.execute("PRAGMA journal_mode").fetchone()[0].lower() == "wal"

    with store._connect(busy_timeout_ms=123) as conn:
        assert conn.execute("PRAGMA busy_timeout").fetchone()[0] == 123
