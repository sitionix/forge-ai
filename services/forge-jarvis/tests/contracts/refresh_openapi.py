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


def main() -> None:
    snapshot = Path(__file__).with_name("openapi.json")
    snapshot.write_text(json.dumps(normalized_openapi(create_app()), indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
