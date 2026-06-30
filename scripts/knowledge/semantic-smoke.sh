#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

PYTHON_BIN="${PYTHON:-services/forge-knowledge/.venv/bin/python3}"
if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "FAIL python: $PYTHON_BIN is not executable. Run scripts/knowledge/bootstrap.sh first."
  exit 2
fi

PYTHONPATH="services/forge-knowledge/src" "$PYTHON_BIN" - <<'PY'
from __future__ import annotations

import json
import sqlite3
import urllib.error
import urllib.request
from typing import Any

from knowledge_service.config import load_app_config
from knowledge_service.semantic_worker import SemanticIndexBackgroundWorker


def http_json(method: str, url: str, payload: dict[str, Any] | None = None, timeout_seconds: float = 20.0) -> tuple[int, dict[str, Any]]:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8")
            return response.status, json.loads(body or "{}")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(body or "{}")
        except json.JSONDecodeError:
            parsed = {"error": body[:200]}
        return exc.code, parsed


def fail(label: str, message: str, code: int = 1) -> None:
    print(f"FAIL {label}: {message}")
    raise SystemExit(code)


def table_exists(conn: sqlite3.Connection, table_name: str) -> bool:
    return conn.execute("SELECT 1 FROM sqlite_master WHERE type IN ('table', 'virtual table') AND name = ?", (table_name,)).fetchone() is not None


config = load_app_config()
base_url = config.semantic_ollama_base_url.rstrip("/")
model = config.semantic_embedding_model
knowledge_url = f"http://{config.host}:{config.port}"
print(
    "config semantic "
    f"model={model} baseUrl={base_url} enabled={config.semantic_enabled} "
    f"autoBuildEnabled={config.semantic_auto_build_enabled}"
)
print(f"OK auto worker module import: {SemanticIndexBackgroundWorker.__name__}")

runtime_status = None
try:
    status_code, status_payload = http_json("GET", f"{knowledge_url}/api/v1/knowledge/status")
    if status_code == 200:
        runtime_status = status_payload
    else:
        print(f"AUTO WORKER: runtime status unavailable HTTP {status_code}")
except Exception as exc:
    print(f"AUTO WORKER: runtime status unavailable: {type(exc).__name__}: {exc}")

if runtime_status is not None:
    semantic_status = runtime_status.get("semantic")
    if not isinstance(semantic_status, dict) or "autoWorkerConfigured" not in semantic_status:
        fail("auto worker wiring", "Knowledge status does not expose semantic auto worker wiring.")
    if semantic_status.get("autoBuildEnabled") and semantic_status.get("autoWorkerConfigured") and semantic_status.get("autoWorkerRunning"):
        print("AUTO WORKER: configured/running path verified where possible")
    elif semantic_status.get("autoBuildEnabled"):
        fail("auto worker running", "semantic auto-build is enabled but the Knowledge worker is not running.")
    else:
        print("AUTO WORKER: disabled by config")
elif config.semantic_auto_build_enabled:
    print("AUTO WORKER: configured in local config; live running status not verified because Knowledge is unavailable")
else:
    print("AUTO WORKER: disabled by config")

try:
    tags_status, tags = http_json("GET", f"{base_url}/api/tags")
except Exception as exc:
    fail("ollama reachable", f"{type(exc).__name__}: {exc}")
if tags_status != 200:
    fail("ollama reachable", f"HTTP {tags_status}")
print("OK ollama reachable")

models: set[str] = set()
for item in tags.get("models", []):
    if not isinstance(item, dict):
        continue
    for field in ("name", "model"):
        value = str(item.get(field) or "").strip()
        if not value:
            continue
        models.add(value)
        if ":" in value:
            models.add(value.split(":", 1)[0])
if model not in models:
    print(f"EMBEDDING MODEL: missing {model}")
    print("SEMANTIC BUILD: blocked by local embedding model")
    fail("embedding model available", f"Embedding model is not available in local Ollama: {model}. Pull or configure an installed embedding model.")
print(f"OK embedding model available: {model}")

embed_status, embed_payload = http_json("POST", f"{base_url}/api/embed", {"model": model, "input": "test"})
if embed_status != 200:
    fail("api/embed", f"HTTP {embed_status}: {embed_payload.get('error') or 'embedding request failed'}")
embeddings = embed_payload.get("embeddings")
if not isinstance(embeddings, list) or not embeddings or not isinstance(embeddings[0], list) or not embeddings[0]:
    fail("api/embed", "response did not contain a non-empty embeddings array")
print(f"OK api/embed returned embeddings dimension={len(embeddings[0])}")

with sqlite3.connect(config.store_path) as conn:
    conn.row_factory = sqlite3.Row
    if not table_exists(conn, "graph_current_snapshots"):
        print("SKIP semantic build state: no current graph snapshot table is available")
        raise SystemExit(0)
    row = conn.execute("SELECT source_id FROM graph_current_snapshots ORDER BY source_id LIMIT 1").fetchone()
if row is None:
    print("SKIP semantic build state: no current graph source is available")
    raise SystemExit(0)

source_id = str(row["source_id"])
with sqlite3.connect(config.store_path) as conn:
    conn.row_factory = sqlite3.Row
    if not table_exists(conn, "semantic_index_state"):
        fail("semantic build state", f"no semantic_index_state table for source={source_id}")
    state = conn.execute("SELECT * FROM semantic_index_state WHERE source_id = ?", (source_id,)).fetchone()
if state is None:
    if config.semantic_auto_build_enabled:
        fail("semantic build state", f"source={source_id} has no semantic state; auto worker has not picked it up yet")
    fail("semantic build state", f"source={source_id} has no semantic state and auto-build is disabled")
status = str(state["status"])
if status == "READY":
    print(f"SEMANTIC BUILD: ready source={source_id} indexed={state['indexed_node_count']}/{state['total_node_count']}")
elif status == "FAILED":
    print(f"SEMANTIC BUILD: failed source={source_id} reason={state['last_error'] or 'unknown'}")
    fail("semantic build state", "semantic index is FAILED")
else:
    fail("semantic build state", f"source={source_id} status={status}; worker has not completed the semantic index")
PY
