#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

HOST="${JARVIS_HOST:-127.0.0.1}"
PORT="${JARVIS_PORT:-7071}"
export OLLAMA_HOME="${OLLAMA_HOME:-${FORGE_RUNTIME_DIR}/jarvis/data/ollama}"

if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  echo "Ollama API: UP"
else
  echo "Ollama API: DOWN"
fi

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Jarvis Agent: UP"
else
  echo "Jarvis Agent: DOWN"
fi
