#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
HOST="${JARVIS_HOST:-127.0.0.1}"
PORT="${JARVIS_PORT:-7070}"
export OLLAMA_HOME="${OLLAMA_HOME:-${MODULE_DIR}/var/data/ollama}"

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
