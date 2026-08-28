#!/usr/bin/env bash
set -euo pipefail

: "${FORGE_AI_HOME:?FORGE_AI_HOME is required}"

JARVIS_ROOT="${FORGE_AI_HOME}/services/forge-jarvis"
PYTHON="${JARVIS_ROOT}/.venv/bin/python3"

exec env \
  JARVIS_REPO_ROOT="${JARVIS_ROOT}" \
  PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" \
  "${PYTHON}" -m uvicorn jarvis_agent.main:app \
  --app-dir "${JARVIS_ROOT}/src" \
  --host "${JARVIS_HOST:-127.0.0.1}" \
  --port "${JARVIS_PORT:-7071}"
