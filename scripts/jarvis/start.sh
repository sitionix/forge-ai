#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

JARVIS_ROOT="${FORGE_AI_HOME}/services/forge-jarvis"
PYTHON="${JARVIS_ROOT}/.venv/bin/python3"
PID_FILE="${FORGE_RUNTIME_DIR}/jarvis/jarvis-agent.pid"
STDOUT_LOG="${FORGE_RUNTIME_DIR}/jarvis/logs/jarvis-agent.stdout.log"
export JARVIS_CONFIG_DIR="${JARVIS_CONFIG_DIR:-${FORGE_CONFIG_DIR}/jarvis}"

mkdir -p "${FORGE_RUNTIME_DIR}/jarvis/logs" "${FORGE_RUNTIME_DIR}/jarvis/data/ollama"
export OLLAMA_HOME="${OLLAMA_HOME:-${FORGE_RUNTIME_DIR}/jarvis/data/ollama}"
OLLAMA_RUNTIME_HOME="${FORGE_RUNTIME_DIR}/jarvis"

if [[ ! -x "${PYTHON}" ]] || ! PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" -c 'import uvicorn' >/dev/null 2>&1; then
  echo "Jarvis dependencies are missing. Run scripts/jarvis/bootstrap.sh first."
  exit 1
fi

read -r CONFIG_HOST CONFIG_PORT CONFIG_OLLAMA_BASE_URL < <(
  PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" - <<'PY'
from jarvis_agent.config import load_forge_settings

settings = load_forge_settings()
jarvis = settings.services.jarvis
print(jarvis.host, jarvis.port, jarvis.model_runtime.base_url)
PY
)
HOST="${JARVIS_HOST:-${CONFIG_HOST}}"
PORT="${JARVIS_PORT:-${CONFIG_PORT}}"
OLLAMA_TAGS_URL="${CONFIG_OLLAMA_BASE_URL%/}/api/tags"

if ! curl -fsS "${OLLAMA_TAGS_URL}" >/dev/null 2>&1; then
  if command -v ollama >/dev/null 2>&1; then
    echo "Starting Ollama in the background..."
    nohup env HOME="${OLLAMA_RUNTIME_HOME}" OLLAMA_HOME="${OLLAMA_HOME}" ollama serve > "${FORGE_RUNTIME_DIR}/jarvis/logs/ollama.log" 2>&1 &
  else
    echo "Ollama is not installed or not on PATH."
    exit 1
  fi
fi

for _ in {1..30}; do
  if curl -fsS "${OLLAMA_TAGS_URL}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "${OLLAMA_TAGS_URL}" >/dev/null 2>&1; then
  echo "Ollama API is not reachable at ${CONFIG_OLLAMA_BASE_URL}"
  echo "Last Ollama log lines:"
  tail -n 40 "${FORGE_RUNTIME_DIR}/jarvis/logs/ollama.log" || true
  exit 1
fi

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Jarvis Agent is already reachable at http://${HOST}:${PORT}/health"
  exit 0
fi

if [[ -f "${PID_FILE}" ]]; then
  existing_pid="$(cat "${PID_FILE}")"
  existing_cmd="$(ps -p "${existing_pid}" -o command= 2>/dev/null || true)"
  if [[ -n "${existing_cmd}" && "${existing_cmd}" == *"jarvis_agent.main:app"* ]]; then
    echo "Jarvis PID ${existing_pid} exists but health check failed; stopping unhealthy service."
    kill "${existing_pid}" >/dev/null 2>&1 || true
    sleep 1
  else
    echo "Ignoring stale Jarvis PID file: ${existing_pid}"
  fi
  rm -f "${PID_FILE}"
fi

if command -v lsof >/dev/null 2>&1; then
  port_pids="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "${port_pids}" ]]; then
    echo "Jarvis port ${PORT} is occupied by pid(s): ${port_pids}"
    exit 1
  fi
fi

echo "Starting Jarvis Agent on ${HOST}:${PORT}..."
(
  cd "${JARVIS_ROOT}"
  setsid nohup env \
    JARVIS_REPO_ROOT="${JARVIS_ROOT}" \
    FORGE_AI_HOME="${FORGE_AI_HOME}" \
    FORGE_CONFIG_DIR="${FORGE_CONFIG_DIR}" \
    FORGE_RUNTIME_DIR="${FORGE_RUNTIME_DIR}" \
    FORGE_WORKSPACE_ROOT="${FORGE_WORKSPACE_ROOT}" \
    JARVIS_CONFIG_DIR="${JARVIS_CONFIG_DIR}" \
    JARVIS_LOG_FILE="${FORGE_RUNTIME_DIR}/jarvis/logs/jarvis-agent.log" \
    JARVIS_HOST="${HOST}" \
    JARVIS_PORT="${PORT}" \
    PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" \
    "${PYTHON}" -m uvicorn jarvis_agent.main:app \
    --app-dir "${JARVIS_ROOT}/src" \
    --host "${HOST}" \
    --port "${PORT}" \
    > "${STDOUT_LOG}" 2>&1 &
  echo $! > "${PID_FILE}"
)

for _ in {1..30}; do
  if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
    echo
    echo "Jarvis health: http://${HOST}:${PORT}/health"
    exit 0
  fi
  sleep 1
done

echo "Jarvis Agent did not become healthy at http://${HOST}:${PORT}/health"
echo "Last Jarvis log lines:"
tail -n 80 "${STDOUT_LOG}" || true
exit 1
