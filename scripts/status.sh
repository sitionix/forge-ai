#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/forge-env.sh
source "${SCRIPT_DIR}/lib/forge-env.sh"

pid_file_status() {
  local label="$1"
  local pid_file="$2"
  if [[ -f "${pid_file}" ]]; then
    local pid
    pid="$(cat "${pid_file}" 2>/dev/null || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      printf '%s' "${pid}"
      return 0
    fi
  fi
  printf '%s' "-"
}

listener_pids() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -t -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true
  fi
}

file_stamp() {
  local path="$1"
  if [[ -f "${path}" ]]; then
    stat -c '%y' "${path}" 2>/dev/null | cut -d'.' -f1
  else
    printf '%s' "-"
  fi
}

file_hash() {
  local path="$1"
  if [[ -f "${path}" ]]; then
    sha256sum "${path}" | awk '{print $1}'
  else
    printf '%s' "-"
  fi
}

NEXUS_PID_FILE="${FORGE_AI_HOME}/var/forge-ai.pid"
NEXUS_JAR="${FORGE_AI_HOME}/services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar"
NEXUS_PID="$(pid_file_status "Forge Nexus" "${NEXUS_PID_FILE}")"
NEXUS_LISTENERS="$(listener_pids 9099)"
if curl --max-time 10 --retry 3 --retry-delay 1 --retry-connrefused -fsS "${FORGE_NEXUS_BASE_URL}/actuator/health" >/dev/null 2>&1; then
  echo "Forge Nexus: UP at ${FORGE_NEXUS_BASE_URL} pid=${NEXUS_PID} listener=${NEXUS_LISTENERS:-"-"} jarTimestamp=$(file_stamp "${NEXUS_JAR}")"
else
  echo "Forge Nexus: DOWN at ${FORGE_NEXUS_BASE_URL} pid=${NEXUS_PID} listener=${NEXUS_LISTENERS:-"-"} jarTimestamp=$(file_stamp "${NEXUS_JAR}")"
fi

BUILT_JS="${FORGE_AI_HOME}/services/forge-console/dist/operator/operator-ui.js"
LIVE_JS="$(mktemp)"
if curl --max-time 10 --retry 3 --retry-delay 1 --retry-connrefused -fsS "${FORGE_NEXUS_BASE_URL}/operator/operator-ui.js" > "${LIVE_JS}" 2>/dev/null; then
  if cmp -s "${BUILT_JS}" "${LIVE_JS}"; then
    echo "Console: static asset built=${BUILT_JS} timestamp=$(file_stamp "${BUILT_JS}") hash=$(file_hash "${BUILT_JS}") liveMatch=yes"
  else
    echo "Console: static asset built=${BUILT_JS} timestamp=$(file_stamp "${BUILT_JS}") builtHash=$(file_hash "${BUILT_JS}") liveHash=$(file_hash "${LIVE_JS}") liveMatch=no"
  fi
else
  echo "Console: static asset built=${BUILT_JS} timestamp=$(file_stamp "${BUILT_JS}") hash=$(file_hash "${BUILT_JS}") liveMatch=unknown (Nexus not serving)"
fi
rm -f "${LIVE_JS}"

"${SCRIPT_DIR}/knowledge/status.sh"
"${SCRIPT_DIR}/jarvis/status.sh"
