import asyncio
import json
import os

os.environ.setdefault("KNOWLEDGE_STORE_PATH", "/tmp/forge-ai-knowledge-test-main.sqlite")

from knowledge_service import main
from knowledge_service.config import AppConfig
from knowledge_service.inventory_store import InventoryStore


def make_api_config(tmp_path):
    workspace = tmp_path / "workspace"
    backend = workspace / "backend"
    frontend = workspace / "frontend"
    backend.mkdir(parents=True)
    frontend.mkdir(parents=True)
    (backend / "JarvisGateway.java").write_text("public interface JarvisGateway {}\n", encoding="utf-8")
    (frontend / "Ui.md").write_text("Jarvis frontend notes\n", encoding="utf-8")
    (backend / "ignored.txt").write_text("OutsideInventory\n", encoding="utf-8")
    catalog = tmp_path / "services.yaml"
    catalog.write_text(
        """services:
  backend:
    label: Backend
    path: backend
    group: backend
    tags: [java]
  frontend:
    label: Frontend
    path: frontend
    group: frontend
    tags: [ui]
""",
        encoding="utf-8",
    )
    config_file = tmp_path / "knowledge-sources.yaml"
    config_file.write_text(
        f"""catalog:
  path: "{catalog}"
  workspace_root: "{workspace}"
indexing:
  include: ["**/*.java", "**/*.md"]
""",
        encoding="utf-8",
    )
    return config_file


def configure_app(tmp_path, monkeypatch):
    config_file = make_api_config(tmp_path)
    store = InventoryStore(tmp_path / "knowledge.sqlite")
    monkeypatch.setattr(main, "app_config", AppConfig(tmp_path, "127.0.0.1", 7081, config_file, tmp_path / "knowledge.sqlite"))
    monkeypatch.setattr(main, "store", store)
    build = post_json("/api/v1/knowledge/inventory/build", {})
    assert build["status"] == 200
    return store


def post_json(path, payload):
    return asyncio.run(asgi_json("POST", path, payload))


async def asgi_json(method, path, payload):
    body = json.dumps(payload).encode("utf-8")
    messages = []
    received = False

    async def receive():
        nonlocal received
        if received:
            return {"type": "http.disconnect"}
        received = True
        return {"type": "http.request", "body": body, "more_body": False}

    async def send(message):
        messages.append(message)

    scope = {
        "type": "http",
        "asgi": {"version": "3.0"},
        "http_version": "1.1",
        "method": method,
        "path": path,
        "raw_path": path.encode("utf-8"),
        "query_string": b"",
        "headers": [(b"content-type", b"application/json"), (b"accept", b"application/json")],
        "client": ("testclient", 50000),
        "server": ("testserver", 80),
        "scheme": "http",
    }
    await main.app(scope, receive, send)
    status = next(message["status"] for message in messages if message["type"] == "http.response.start")
    response_body = b"".join(message.get("body", b"") for message in messages if message["type"] == "http.response.body")
    return {"status": status, "json": json.loads(response_body.decode("utf-8") or "{}")}


def test_context_api_returns_context_snippets_for_known_query(tmp_path, monkeypatch):
    configure_app(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/context", {"query": "JarvisGateway"})

    assert result["status"] == 200
    assert result["json"]["context"][0]["relativePath"] == "JarvisGateway.java"


def test_context_api_respects_source_ids(tmp_path, monkeypatch):
    configure_app(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/context", {"query": "Jarvis", "sourceIds": ["frontend"]})

    assert result["status"] == 200
    assert {item["sourceId"] for item in result["json"]["context"]} == {"frontend"}


def test_context_api_respects_groups(tmp_path, monkeypatch):
    configure_app(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/context", {"query": "Jarvis", "groups": ["backend"]})

    assert result["status"] == 200
    assert {item["sourceId"] for item in result["json"]["context"]} == {"backend"}


def test_context_api_respects_max_chars(tmp_path, monkeypatch):
    configure_app(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/context", {"query": "Jarvis", "maxChars": 1000})

    assert result["status"] == 200
    assert result["json"]["budget"]["usedChars"] <= 1000


def test_context_api_empty_inventory_diagnostic(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "store", InventoryStore(tmp_path / "empty.sqlite"))

    result = post_json("/api/v1/knowledge/context", {"query": "Jarvis"})

    assert result["status"] == 200
    assert result["json"]["diagnostics"][0]["code"] == "INVENTORY_EMPTY"


def test_context_api_does_not_read_unindexed_file(tmp_path, monkeypatch):
    configure_app(tmp_path, monkeypatch)

    result = post_json("/api/v1/knowledge/context", {"query": "OutsideInventory"})

    assert result["status"] == 200
    assert result["json"]["context"] == []
