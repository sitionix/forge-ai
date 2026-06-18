#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

JARVIS_ROOT="${FORGE_AI_HOME}/services/forge-jarvis"
PYTHON="${JARVIS_ROOT}/.venv/bin/python3"
PID_FILE="${FORGE_RUNTIME_DIR}/jarvis/jarvis-agent.pid"
STDOUT_LOG="${FORGE_RUNTIME_DIR}/jarvis/logs/jarvis-agent.stdout.log"
HOST="${JARVIS_HOST:-127.0.0.1}"
PORT="${JARVIS_PORT:-7071}"
export JARVIS_CONFIG_DIR="${JARVIS_CONFIG_DIR:-${FORGE_CONFIG_DIR}/jarvis}"

mkdir -p "${FORGE_RUNTIME_DIR}/jarvis/logs" "${FORGE_RUNTIME_DIR}/jarvis/data/ollama"
export OLLAMA_HOME="${OLLAMA_HOME:-${FORGE_RUNTIME_DIR}/jarvis/data/ollama}"
OLLAMA_RUNTIME_HOME="${FORGE_RUNTIME_DIR}/jarvis"

if ! curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
  if command -v ollama >/dev/null 2>&1; then
    echo "Starting Ollama in the background..."
    nohup env HOME="${OLLAMA_RUNTIME_HOME}" OLLAMA_HOME="${OLLAMA_HOME}" ollama serve > "${FORGE_RUNTIME_DIR}/jarvis/logs/ollama.log" 2>&1 &
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
  tail -n 40 "${FORGE_RUNTIME_DIR}/jarvis/logs/ollama.log" || true
  exit 1
fi

if [[ ! -x "${PYTHON}" ]] || ! PYTHONPATH="${JARVIS_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" "${PYTHON}" -c 'import uvicorn' >/dev/null 2>&1; then
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
fi

echo
echo "Jarvis health: http://${HOST}:${PORT}/health"
