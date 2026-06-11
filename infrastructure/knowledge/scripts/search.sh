#!/usr/bin/env bash
set -euo pipefail

HOST="${KNOWLEDGE_HOST:-127.0.0.1}"
PORT="${KNOWLEDGE_PORT:-7081}"
QUERY="${1:-}"
if [[ -z "${QUERY}" ]]; then
  echo "Usage: $0 <query>"
  exit 1
fi
curl -fsS -X POST "http://${HOST}:${PORT}/api/v1/knowledge/search" \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"${QUERY}\",\"limit\":20}"
