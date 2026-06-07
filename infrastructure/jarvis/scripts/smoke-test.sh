#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "FAIL: $1"
  exit 1
}

command -v python3 >/dev/null 2>&1 || fail "python3 is required"

HOST="${JARVIS_HOST:-127.0.0.1}"
PORT="${JARVIS_PORT:-7070}"

curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1 || fail "Ollama API is not reachable"
curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1 || fail "Jarvis health endpoint is not reachable"

RESPONSE="$(curl -fsS -X POST "http://${HOST}:${PORT}/api/v1/jarvis/command" \
  -H "Content-Type: application/json" \
  -d '{"text":"перевір ollama"}')"

python3 - "$RESPONSE" <<'PY'
import json
import sys

data = json.loads(sys.argv[1])
if "intent" not in data:
    raise SystemExit("response does not contain intent")
intent = data["intent"]
allowed = {
    ("ollama_status", "health"),
    ("system_status", "basic"),
    ("unsupported", None),
}
pair = (intent.get("action"), intent.get("target"))
if pair not in allowed:
    raise SystemExit(f"unexpected intent: {pair}")
print("PASS: smoke test returned valid JSON with an allowlisted intent")
PY
