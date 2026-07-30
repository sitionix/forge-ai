#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/forge-env.sh
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"
# shellcheck source=../lib/process.sh
source "${FORGE_AI_HOME}/scripts/lib/process.sh"

PID_FILE="${FORGE_RUNTIME_DIR}/ollama/ollama.pid"
OWNER="forge-ai-ollama"

if [[ ! -f "${PID_FILE}" ]]; then
  echo "Forge-owned Ollama is not running."
  exit 0
fi

PID="$(awk -F= '$1 == "PID" { print $2; exit }' "${PID_FILE}" 2>/dev/null || true)"
OWNER_VALUE="$(awk -F= '$1 == "OWNER" { print $2; exit }' "${PID_FILE}" 2>/dev/null || true)"

if [[ "${OWNER_VALUE}" != "${OWNER}" || ! "${PID}" =~ ^[0-9]+$ ]]; then
  echo "Removing invalid Forge-owned Ollama PID file: ${PID_FILE}"
  rm -f "${PID_FILE}"
  exit 0
fi

if ! forge_pid_is_running "${PID}"; then
  echo "Removing stale Forge-owned Ollama PID file: ${PID_FILE}"
  rm -f "${PID_FILE}"
  exit 0
fi

COMMAND_LINE="$(ps -p "${PID}" -o command= 2>/dev/null || true)"
if [[ "${COMMAND_LINE}" != *"ollama"* || "${COMMAND_LINE}" != *"serve"* ]]; then
  echo "Refusing to stop pid ${PID}; Forge-owned Ollama PID file does not match a running 'ollama serve' process."
  rm -f "${PID_FILE}"
  exit 0
fi

kill "${PID}" >/dev/null 2>&1 || true
for _ in 1 2 3 4 5; do
  if ! forge_pid_is_running "${PID}"; then
    break
  fi
  sleep 1
done

if forge_pid_is_running "${PID}"; then
  echo "WARNING: Forge-owned Ollama pid ${PID} did not stop after SIGTERM."
else
  echo "Stopped Forge-owned Ollama PID ${PID}."
fi
rm -f "${PID_FILE}"
