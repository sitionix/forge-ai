#!/usr/bin/env bash
set -euo pipefail
: "${FORGE_AI_HOME:?FORGE_AI_HOME is required}"
ROOT="${FORGE_AI_HOME}/services/forge-jarvis"
exec env JARVIS_REPO_ROOT="${ROOT}" PYTHONPATH="${ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${ROOT}/.venv/bin/python3" -m uvicorn jarvis_agent.main:app --app-dir "${ROOT}/src" --host "${JARVIS_HOST:-127.0.0.1}" --port "${JARVIS_PORT:-7071}"
