from support import AsgiTestClient as TestClient

from support import build_test_app, write_runtime_config
from knowledge_service.flow_explanations import FlowExplanationProviderResult
from semantic_test_support import seed_semantic_graph


class FakeFlowExplanationProvider:
    def __init__(self):
        self.calls = []

    def complete(self, llm_input, validation_errors=None):
        self.calls.append(dict(llm_input))
        response = {
            "title": "A.start to B.work",
            "narrative": ["A.start calls B.work using the provided evidence.", "B.work completes the flow as the next ordered step."],
            "steps": [
                {"order": step["order"], "explanation": f"{step['symbol']} is part of this flow.", "evidenceRefs": [item["ref"] for item in step.get("evidence", [])]}
                for step in llm_input["steps"]
            ],
            "boundaries": [],
        }
        import json

        return FlowExplanationProviderResult(raw_text=json.dumps(response), prompt_char_length=100)


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


def test_query_flow_explanations_endpoint_returns_per_flow_text(tmp_path):
    app, _, app_config, _ = build_test_app(write_runtime_config(tmp_path))
    seed_semantic_graph(
        app_config.store_path,
        source_id="source-a",
        nodes=[
            {"id": "a-start", "nodeKind": "CALLABLE", "name": "A.start", "qualified": "A.start", "path": "src/A.java"},
            {"id": "b-work", "nodeKind": "CALLABLE", "name": "B.work", "qualified": "B.work", "path": "src/B.java"},
        ],
        edges=[{"id": "edge-a-b", "fromNodeId": "a-start", "toNodeId": "b-work", "edgeType": "CALLS"}],
    )
    provider = FakeFlowExplanationProvider()
    app.state.flow_explanation_provider = provider

    with TestClient(app) as client:
        payload = client.post("/api/v1/knowledge/query/flow-explanations", json={"queryText": "A.start"}).json()

    assert payload["flowPaths"]
    assert payload["flowExplanations"][0]["flowIndex"] == 1
    assert payload["flowExplanations"][0]["narrative"]
    assert [step["order"] for step in payload["flowExplanations"][0]["steps"]] == [1, 2]
    assert len(provider.calls) == 1
