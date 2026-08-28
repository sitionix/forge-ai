#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="${FORGE_AI_HOME:-$(cd -- "${SCRIPT_DIR}/.." && pwd)}"
ACTION="${1:?Usage: runtime-ownership.sh assert-dev-inactive|assert-systemd-inactive|assert-pid-not-systemd-owned PID}"
UNITS=(forge-agent.service forge-nexus.service forge-knowledge.service forge-jarvis.service)
PID_FILES=(
  "${FORGE_AI_HOME}/var/forge-agent.pid"
  "${FORGE_AI_HOME}/var/forge-ai.pid"
  "${FORGE_RUNTIME_DIR:-${FORGE_AI_HOME}/var}/knowledge/knowledge-service.pid"
  "${FORGE_RUNTIME_DIR:-${FORGE_AI_HOME}/var}/jarvis/jarvis-agent.pid"
)
PORTS=(7091 9099 7081 7071)

systemd_main_pids() {
  command -v systemctl >/dev/null 2>&1 || return 0
  systemctl show --property=MainPID --value "${UNITS[@]}" 2>/dev/null || true
}

pid_is_systemd_owned() {
  local candidate="$1"
  grep -Fqx "${candidate}" <<< "$(systemd_main_pids)"
}

dev_runtime_active() {
  local pid_file pid
  for pid_file in "${PID_FILES[@]}"; do
    [[ -f "${pid_file}" ]] || continue
    pid="$(head -n 1 "${pid_file}" 2>/dev/null || true)"
    if [[ "${pid}" =~ ^[0-9]+$ ]] && kill -0 "${pid}" >/dev/null 2>&1; then
      return 0
    fi
  done
  command -v lsof >/dev/null 2>&1 || return 1
  local port cwd
  for port in "${PORTS[@]}"; do
    while IFS= read -r pid; do
      [[ "${pid}" =~ ^[0-9]+$ ]] || continue
      pid_is_systemd_owned "${pid}" && continue
      cwd="$(readlink "/proc/${pid}/cwd" 2>/dev/null || true)"
      if [[ "${cwd}" == "${FORGE_AI_HOME}" || "${cwd}" == "${FORGE_AI_HOME}/"* ]]; then
        return 0
      fi
    done < <(lsof -t -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)
  done
  return 1
}

systemd_runtime_active() {
  command -v systemctl >/dev/null 2>&1 || return 1
  local state
  state="$(systemctl is-active "${UNITS[@]}" 2>/dev/null || true)"
  grep -Eq '^(active|activating)$' <<< "${state}"
}

case "${ACTION}" in
  assert-dev-inactive)
    if dev_runtime_active; then
      echo "Forge development runtime is active. Stop it with 'just stop' before starting systemd runtime." >&2
      exit 1
    fi
    ;;
  assert-systemd-inactive)
    if systemd_runtime_active; then
      echo "Forge systemd runtime is active. Stop it with 'just systemd-stop' before starting development runtime." >&2
      exit 1
    fi
    ;;
  assert-pid-not-systemd-owned)
    pid="${2:?PID is required}"
    if pid_is_systemd_owned "${pid}"; then
      echo "Refusing to stop systemd-owned Forge PID ${pid}. Stop it with 'just systemd-stop'." >&2
      exit 1
    fi
    ;;
  *)
    echo "Unknown runtime ownership check: ${ACTION}" >&2
    exit 2
    ;;
esac
