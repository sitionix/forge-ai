#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
PID_FILE="${MODULE_DIR}/var/knowledge-service.pid"

if [[ ! -f "${PID_FILE}" ]]; then
  echo "Knowledge service PID file not found."
  exit 0
fi

PID="$(cat "${PID_FILE}")"
if kill -0 "${PID}" >/dev/null 2>&1; then
  kill "${PID}"
  echo "Stopped Knowledge service PID ${PID}."
else
  echo "Knowledge service PID ${PID} is not running."
fi
rm -f "${PID_FILE}"
