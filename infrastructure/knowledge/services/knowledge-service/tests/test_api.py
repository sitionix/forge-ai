import asyncio

from knowledge_service import main
from knowledge_service.config import AppConfig


def test_health_and_status_endpoint_reports_vector_and_rag_not_implemented(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "app_config", AppConfig(tmp_path, "127.0.0.1", 7081, tmp_path / "missing.yaml", tmp_path / "knowledge.sqlite"))
    monkeypatch.setattr(main, "store", main.InventoryStore(tmp_path / "knowledge.sqlite"))

    assert asyncio.run(main.health()) == {"status": "UP"}
    payload = asyncio.run(main.status())

    assert payload["status"] == "UP"
    assert payload["catalog"]["configured"] is False
    assert payload["inventory"]["skippedCount"] == 0
    assert payload["inventory"]["skippedBreakdown"] == {"total": 0, "byReason": {}}
    assert payload["vectorStore"]["implemented"] is False
    assert payload["rag"]["implemented"] is False


def test_missing_config_sources_response(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "app_config", AppConfig(tmp_path, "127.0.0.1", 7081, tmp_path / "missing.yaml", tmp_path / "knowledge.sqlite"))

    payload = asyncio.run(main.sources())

    assert payload["sources"] == []
    assert payload["message"] == "No local knowledge-sources.yaml configured"
