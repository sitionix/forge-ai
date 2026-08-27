#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ACTION="${1:?Usage: control.sh start|stop|restart|status}"
USE_SUDO="${FORGE_SYSTEMD_USE_SUDO:-auto}"
UNITS=(forge-knowledge.service forge-jarvis.service forge-agent.service forge-nexus.service)
REVERSE_UNITS=(forge-nexus.service forge-agent.service forge-jarvis.service forge-knowledge.service)

if ! command -v systemctl >/dev/null 2>&1; then
  echo "systemctl is required to manage Forge systemd services on this host." >&2
  exit 1
fi

sudo_cmd=()
if [[ "${USE_SUDO}" == "1" || ( "${USE_SUDO}" == "auto" && "${EUID}" -ne 0 ) ]]; then
  sudo_cmd=(sudo)
fi

run_privileged() {
  if (( ${#sudo_cmd[@]} > 0 )); then
    "${sudo_cmd[@]}" "$@"
  else
    "$@"
  fi
}

case "${ACTION}" in
  start)
    docker compose --project-directory "${FORGE_AI_HOME}" up -d forge-agent-postgres
    run_privileged systemctl start "${UNITS[@]}"
    ;;
  stop)
    run_privileged systemctl stop "${REVERSE_UNITS[@]}"
    ;;
  restart)
    docker compose --project-directory "${FORGE_AI_HOME}" up -d forge-agent-postgres
    run_privileged systemctl restart "${UNITS[@]}"
    ;;
  status)
    run_privileged systemctl --no-pager status "${UNITS[@]}" || true
    docker compose --project-directory "${FORGE_AI_HOME}" ps forge-agent-postgres || true
    ;;
  *)
    echo "Unknown systemd action: ${ACTION}" >&2
    exit 2
    ;;
esac
