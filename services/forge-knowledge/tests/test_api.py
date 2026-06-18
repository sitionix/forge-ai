from support import AsgiTestClient as TestClient

from support import build_test_app, write_runtime_config


def test_health_and_status_endpoint_reports_inventory_state(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "UP"}
        payload = client.get("/api/v1/knowledge/status").json()

    assert payload["status"] == "UP"
    assert payload["catalog"]["configured"] is True
    assert payload["inventory"]["skippedCount"] == 0
    assert payload["inventory"]["skippedBreakdown"] == {"total": 0, "byReason": {}}
    assert payload["inventoryRefresh"]["enabled"] is False
    assert "search" not in payload
    assert "vectorStore" not in payload
    assert "rag" not in payload


def test_missing_config_sources_response(tmp_path):
    config_file = write_runtime_config(tmp_path)
    (tmp_path / "config" / "knowledge" / "knowledge-sources.yaml").unlink()
    app, _, _, _ = build_test_app(config_file)

    with TestClient(app) as client:
        payload = client.get("/api/v1/knowledge/sources").json()

    assert payload["sources"] == []
    assert payload["message"] == "No local knowledge-sources.yaml configured"
