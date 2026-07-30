#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

JARVIS_ROOT="${FORGE_AI_HOME}/services/forge-jarvis"
PYTHON="${JARVIS_ROOT}/.venv/bin/python3"
if [[ -x "${PYTHON}" ]]; then
  read -r CONFIG_HOST CONFIG_PORT CONFIG_OLLAMA_BASE_URL < <(
    PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" - <<'PY'
from jarvis_agent.config import load_forge_settings

settings = load_forge_settings()
jarvis = settings.services.jarvis
print(jarvis.host, jarvis.port, settings.generative.base_url)
PY
  )
else
  CONFIG_HOST="127.0.0.1"
  CONFIG_PORT="7071"
  CONFIG_OLLAMA_BASE_URL="http://127.0.0.1:11434"
fi
HOST="${JARVIS_HOST:-${CONFIG_HOST}}"
PORT="${JARVIS_PORT:-${CONFIG_PORT}}"
OLLAMA_TAGS_URL="${CONFIG_OLLAMA_BASE_URL%/}/api/tags"
PID_FILE="${FORGE_RUNTIME_DIR}/jarvis/jarvis-agent.pid"
PID="-"
if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [[ -z "${PID}" ]] || ! kill -0 "${PID}" >/dev/null 2>&1; then
    PID="-"
  fi
fi
LISTENER="-"
if command -v lsof >/dev/null 2>&1; then
  LISTENER="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true)"
  LISTENER="${LISTENER:-"-"}"
fi

if curl --max-time 10 -fsS "${OLLAMA_TAGS_URL}" >/dev/null 2>&1; then
  echo "Ollama API: UP"
else
  echo "Ollama API: DOWN"
fi

if curl --max-time 10 -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Jarvis Agent: UP at http://${HOST}:${PORT} pid=${PID} listener=${LISTENER}"
else
  echo "Jarvis Agent: DOWN at http://${HOST}:${PORT} pid=${PID} listener=${LISTENER}"
fi
