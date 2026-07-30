from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict

from knowledge_service.main import create_app


def normalized_openapi(app) -> Dict[str, Any]:
    spec = app.openapi()
    paths: Dict[str, Any] = {}
    for path, path_item in sorted(spec.get("paths", {}).items()):
        paths[path] = {}
        for method, operation in sorted(path_item.items()):
            if method not in {"get", "post", "put", "patch", "delete"}:
                continue
            paths[path][method] = {
                "parameters": operation.get("parameters", []),
                "requestBody": operation.get("requestBody"),
                "responses": operation.get("responses", {}),
            }
    return {
        "openapi": spec.get("openapi"),
        "paths": paths,
        "components": spec.get("components", {}),
    }


def test_openapi_contract_snapshot() -> None:
    snapshot_path = Path(__file__).parent / "contracts" / "openapi.json"
    expected = json.loads(snapshot_path.read_text(encoding="utf-8"))

    assert normalized_openapi(create_app()) == expected, (
        "OpenAPI contract changed. If intentional, refresh with: cd services/forge-knowledge && python tests/contracts/refresh_openapi.py"
    )


def test_knowledge_query_request_schema_is_query_plan_v2() -> None:
    spec = normalized_openapi(create_app())
    schema = spec["components"]["schemas"]["KnowledgeQueryRequest"]
    properties = schema["properties"]

    assert set(properties) == {"queryText", "intent", "answerLanguage", "includeTests", "maxFlows"}
    assert set(schema["required"]) == {"queryText"}
    assert "query" not in properties
    assert properties["intent"]["default"] == "AUTO"
    assert "default" not in properties["answerLanguage"]
    assert properties["includeTests"]["default"] is False
    assert properties["maxFlows"]["default"] == 10
    assert spec["components"]["schemas"]["KnowledgeQueryIntent"]["enum"] == [
        "AUTO",
        "FLOW_EXPLANATION",
    ]


def test_ai_runtime_openapi_schema_is_typed() -> None:
    spec = normalized_openapi(create_app())
    operation = spec["paths"]["/api/v1/knowledge/ai-runtime"]["get"]
    response_schema = operation["responses"]["200"]["content"]["application/json"]["schema"]

    assert response_schema == {"$ref": "#/components/schemas/AiRuntimeOptionsResponse"}
    schemas = spec["components"]["schemas"]
    root = schemas["AiRuntimeOptionsResponse"]
    provider = schemas["AiRuntimeProviderResponse"]
    model = schemas["AiRuntimeModelResponse"]
    effort = schemas["AiRuntimeEffortResponse"]

    assert root["required"] == ["providers"]
    assert root["properties"]["providers"]["type"] == "array"
    assert root["properties"]["providers"]["items"] == {"$ref": "#/components/schemas/AiRuntimeProviderResponse"}

    assert set(provider["required"]) == {"providerId", "displayName", "status", "models"}
    assert provider["properties"]["status"] == {"$ref": "#/components/schemas/AiRuntimeProviderStatus"}
    assert provider["properties"]["models"]["type"] == "array"
    assert provider["properties"]["version"]["type"] == "string"
    assert "message" not in provider["properties"]
    assert schemas["AiRuntimeProviderStatus"]["enum"] == ["READY", "DEGRADED", "UNAVAILABLE"]

    assert set(model["required"]) == {"modelId", "displayName"}
    assert model["properties"]["efforts"]["type"] == "array"
    assert model["properties"]["efforts"]["items"] == {"$ref": "#/components/schemas/AiRuntimeEffortResponse"}
    assert model["properties"]["description"]["type"] == "string"
    assert model["properties"]["modifiedAt"]["type"] == "string"

    assert set(effort["required"]) == {"effortId", "description"}
