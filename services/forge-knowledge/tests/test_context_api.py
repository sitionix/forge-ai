from support import AsgiTestClient as TestClient

from support import build_test_app, write_runtime_config


def configured_client(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))
    client = TestClient(app)
    client.__enter__()
    build = client.post("/api/v1/knowledge/inventory/build", json={})
    assert build.status_code == 200
    return client


def test_context_api_returns_context_snippets_for_known_query(tmp_path):
    client = configured_client(tmp_path)
    try:
        result = client.post("/api/v1/knowledge/context", json={"query": "JarvisGateway"})
    finally:
        client.__exit__(None, None, None)

    assert result.status_code == 200
    assert result.json()["context"][0]["relativePath"].endswith("JarvisGateway.java")


def test_context_api_respects_source_ids(tmp_path):
    client = configured_client(tmp_path)
    try:
        result = client.post("/api/v1/knowledge/context", json={"query": "Jarvis", "sourceIds": ["forge-ai"]})
    finally:
        client.__exit__(None, None, None)

    assert result.status_code == 200
    assert {item["sourceId"] for item in result.json()["context"]} == {"forge-ai"}


def test_context_api_respects_groups(tmp_path):
    client = configured_client(tmp_path)
    try:
        result = client.post("/api/v1/knowledge/context", json={"query": "Jarvis", "groups": ["platform"]})
    finally:
        client.__exit__(None, None, None)

    assert result.status_code == 200
    assert {item["sourceId"] for item in result.json()["context"]} == {"forge-ai"}


def test_context_api_respects_max_chars(tmp_path):
    client = configured_client(tmp_path)
    try:
        result = client.post("/api/v1/knowledge/context", json={"query": "Jarvis", "maxChars": 1000})
    finally:
        client.__exit__(None, None, None)

    assert result.status_code == 200
    assert result.json()["budget"]["usedChars"] <= 1000


def test_context_api_empty_inventory_diagnostic(tmp_path):
    app, _, _, _ = build_test_app(write_runtime_config(tmp_path))

    with TestClient(app) as client:
        result = client.post("/api/v1/knowledge/context", json={"query": "Jarvis"})

    assert result.status_code == 200
    assert result.json()["diagnostics"][0]["code"] == "INVENTORY_EMPTY"


def test_context_api_does_not_read_unindexed_file(tmp_path):
    client = configured_client(tmp_path)
    try:
        result = client.post("/api/v1/knowledge/context", json={"query": "Generated"})
    finally:
        client.__exit__(None, None, None)

    assert result.status_code == 200
    assert result.json()["context"] == []
