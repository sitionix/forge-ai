#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

PID_FILE="${FORGE_RUNTIME_DIR}/knowledge/knowledge-service.pid"

stopped=0
if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}")"
  if kill -0 "${PID}" >/dev/null 2>&1; then
    kill "${PID}" >/dev/null 2>&1 || true
    echo "Stopped Knowledge service PID ${PID}."
    stopped=1
  else
    echo "Knowledge service PID ${PID} is not running."
  fi
  rm -f "${PID_FILE}"
fi

PORT="${KNOWLEDGE_PORT:-7081}"
if command -v lsof >/dev/null 2>&1; then
  pids="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    echo "Stopping Knowledge listener pid(s) ${pids} on port ${PORT}."
    for pid in ${pids}; do
      cmd="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
      if [[ "${cmd}" == *"knowledge_service.main:app"* ]]; then
        kill "${pid}" >/dev/null 2>&1 || true
        stopped=1
      else
        echo "Refusing to stop non-Knowledge process on port ${PORT}: pid ${pid} ${cmd}"
        exit 1
      fi
    done
  fi
fi

if [[ "${stopped}" == "0" ]]; then
  echo "Knowledge service is not running."
fi
