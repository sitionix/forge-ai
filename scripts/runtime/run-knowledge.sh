#!/usr/bin/env bash
set -euo pipefail
: "${FORGE_AI_HOME:?FORGE_AI_HOME is required}"
ROOT="${FORGE_AI_HOME}/services/forge-knowledge"
exec env KNOWLEDGE_MODULE_DIR="${ROOT}" PYTHONPATH="${ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${ROOT}/.venv/bin/python3" -m uvicorn knowledge_service.main:app --app-dir "${ROOT}/src" --host "${KNOWLEDGE_HOST:-127.0.0.1}" --port "${KNOWLEDGE_PORT:-7081}"
