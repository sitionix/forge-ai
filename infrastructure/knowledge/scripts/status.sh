#!/usr/bin/env bash
set -euo pipefail

HOST="${KNOWLEDGE_HOST:-127.0.0.1}"
PORT="${KNOWLEDGE_PORT:-7081}"

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Knowledge service: UP at http://${HOST}:${PORT}"
else
  echo "Knowledge service: DOWN at http://${HOST}:${PORT}"
fi
