#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/forge-env.sh
source "${SCRIPT_DIR}/lib/forge-env.sh"

if command -v just >/dev/null 2>&1; then
  (cd "${FORGE_AI_HOME}" && just _app-stop) || true
else
  PID_FILE="${FORGE_AI_HOME}/var/forge-ai.pid"
  if [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" >/dev/null 2>&1; then
    kill "$(cat "${PID_FILE}")"
    echo "Stopped Forge Nexus PID $(cat "${PID_FILE}")."
  else
    echo "Forge Nexus PID file missing or process is not running."
  fi
  rm -f "${PID_FILE}"
fi

"${SCRIPT_DIR}/jarvis/stop.sh"
"${SCRIPT_DIR}/knowledge/stop.sh"
"${SCRIPT_DIR}/ollama/stop-owned.sh"
