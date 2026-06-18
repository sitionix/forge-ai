#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

export OLLAMA_HOME="${OLLAMA_HOME:-${FORGE_RUNTIME_DIR}/jarvis/data/ollama}"

JARVIS_ROOT="${FORGE_AI_HOME}/services/forge-jarvis"
PYTHON="${JARVIS_ROOT}/.venv/bin/python3"
if [[ -x "${PYTHON}" ]]; then
  read -r CONFIG_HOST CONFIG_PORT CONFIG_OLLAMA_BASE_URL < <(
    PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" - <<'PY'
from jarvis_agent.config import load_forge_settings

settings = load_forge_settings()
jarvis = settings.services.jarvis
print(jarvis.host, jarvis.port, jarvis.model_runtime.base_url)
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

if curl -fsS "${OLLAMA_TAGS_URL}" >/dev/null 2>&1; then
  echo "Ollama API: UP"
else
  echo "Ollama API: DOWN"
fi

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Jarvis Agent: UP"
else
  echo "Jarvis Agent: DOWN"
fi
