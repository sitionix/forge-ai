#!/usr/bin/env bash
set -euo pipefail

HOST="${KNOWLEDGE_HOST:-127.0.0.1}"
PORT="${KNOWLEDGE_PORT:-7081}"
curl -fsS -X POST "http://${HOST}:${PORT}/api/v1/knowledge/inventory/build" \
  -H "Content-Type: application/json" \
  -d "${1:-{}}"
