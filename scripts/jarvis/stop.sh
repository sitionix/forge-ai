#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

PID_FILE="${FORGE_RUNTIME_DIR}/jarvis/jarvis-agent.pid"

stopped=0
if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [[ -n "${PID}" ]] && kill -0 "${PID}" >/dev/null 2>&1; then
    kill "${PID}" >/dev/null 2>&1 || true
    echo "Stopped Jarvis Agent PID ${PID}"
    stopped=1
  fi
  rm -f "${PID_FILE}"
fi

PORT="${JARVIS_PORT:-7071}"
if command -v lsof >/dev/null 2>&1; then
  pids="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    echo "Stopping Jarvis listener pid(s) ${pids} on port ${PORT}."
    for pid in ${pids}; do
      cmd="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
      if [[ "${cmd}" == *"jarvis_agent.main:app"* ]]; then
        kill "${pid}" >/dev/null 2>&1 || true
        stopped=1
      else
        echo "Refusing to stop non-Jarvis process on port ${PORT}: pid ${pid} ${cmd}"
        exit 1
      fi
    done
  fi
fi

if [[ "${stopped}" == "0" ]]; then
  echo "Jarvis Agent is not running."
else
  echo "Jarvis Agent stopped."
fi
