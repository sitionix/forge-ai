#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
AGENT_DIR="${MODULE_DIR}/services/jarvis-agent"
PID_FILE="${MODULE_DIR}/var/jarvis-agent.pid"
STDOUT_LOG="${MODULE_DIR}/var/logs/jarvis-agent.stdout.log"
HOST="${JARVIS_HOST:-127.0.0.1}"
PORT="${JARVIS_PORT:-7071}"

mkdir -p "${MODULE_DIR}/var/logs" "${MODULE_DIR}/var/data/ollama"
export OLLAMA_HOME="${OLLAMA_HOME:-${MODULE_DIR}/var/data/ollama}"
OLLAMA_RUNTIME_HOME="${MODULE_DIR}/var"

if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  if command -v ollama >/dev/null 2>&1; then
    echo "Starting Ollama in the background..."
    nohup env HOME="${OLLAMA_RUNTIME_HOME}" OLLAMA_HOME="${OLLAMA_HOME}" ollama serve > "${MODULE_DIR}/var/logs/ollama.log" 2>&1 &
  else
    echo "Ollama is not installed or not on PATH."
    exit 1
  fi
fi

for _ in {1..30}; do
  if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  echo "Ollama API is not reachable at http://127.0.0.1:11434"
  echo "Last Ollama log lines:"
  tail -n 40 "${MODULE_DIR}/var/logs/ollama.log" || true
  exit 1
fi

if [[ ! -x "${AGENT_DIR}/.venv/bin/uvicorn" ]]; then
  echo "Jarvis dependencies are missing. Run scripts/jarvis/bootstrap.sh first."
  exit 1
fi

if curl -fsS "http://${HOST}:${PORT}/health" >/dev/null 2>&1; then
  echo "Jarvis Agent is already reachable at http://${HOST}:${PORT}/health"
elif [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" >/dev/null 2>&1; then
  echo "Jarvis Agent is already running with PID $(cat "${PID_FILE}")"
else
  echo "Starting Jarvis Agent on ${HOST}:${PORT}..."
  (
    cd "${MODULE_DIR}"
    setsid nohup env \
      JARVIS_REPO_ROOT="${MODULE_DIR}" \
      JARVIS_CONFIG_DIR="${MODULE_DIR}/config" \
      JARVIS_LOG_FILE="${MODULE_DIR}/var/logs/jarvis-agent.log" \
      JARVIS_HOST="${HOST}" \
      JARVIS_PORT="${PORT}" \
      "${AGENT_DIR}/.venv/bin/uvicorn" jarvis_agent.main:app \
      --app-dir "${AGENT_DIR}/src" \
      --host "${HOST}" \
      --port "${PORT}" \
      > "${STDOUT_LOG}" 2>&1 &
    echo $! > "${PID_FILE}"
  )
fi

echo
echo "Jarvis health: http://${HOST}:${PORT}/health"
