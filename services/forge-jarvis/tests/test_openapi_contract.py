from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict

from jarvis_agent.main import create_app


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
        "OpenAPI contract changed. If intentional, refresh with: cd services/forge-jarvis && python tests/contracts/refresh_openapi.py"
    )


def test_jarvis_query_request_schema_is_query_plan_v2() -> None:
    spec = normalized_openapi(create_app())
    schema = spec["components"]["schemas"]["JarvisQueryRequest"]
    properties = schema["properties"]

    assert set(properties) == {"queryText", "intent", "answerLanguage", "includeTests", "maxFlows"}
    assert set(schema["required"]) == {"queryText"}
    assert "query" not in properties
    assert properties["intent"]["default"] == "AUTO"
    assert "default" not in properties["answerLanguage"]
    assert properties["includeTests"]["default"] is False
    assert properties["maxFlows"]["default"] == 10
    assert spec["components"]["schemas"]["JarvisQueryIntent"]["enum"] == [
        "AUTO",
        "FLOW_EXPLANATION",
    ]
