#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ACTION="${1:?Usage: systemd.sh validate|start|stop|restart|status|logs [service]}"
SERVICE="${2:-all}"
RUNTIME_DIR="${FORGE_SYSTEMD_RUNTIME_DIR:-/run/systemd/system}"
UNITS=(forge-knowledge.service forge-jarvis.service forge-agent.service forge-nexus.service)
REVERSE_UNITS=(forge-nexus.service forge-agent.service forge-jarvis.service forge-knowledge.service)
USE_SUDO="${FORGE_SYSTEMD_USE_SUDO:-auto}"

sudo_cmd=()
if [[ "${USE_SUDO}" == "1" || ( "${USE_SUDO}" == "auto" && "${EUID}" -ne 0 ) ]]; then sudo_cmd=(sudo); fi
privileged() { if (( ${#sudo_cmd[@]} )); then "${sudo_cmd[@]}" "$@"; else "$@"; fi; }

validate() {
  command -v systemctl >/dev/null 2>&1 || { echo "Ubuntu Forge runtime requires systemctl." >&2; return 1; }
  [[ -d "${RUNTIME_DIR}" ]] || { echo "systemctl exists but systemd is not the active system runtime." >&2; return 1; }
  systemctl show --property=Version --value >/dev/null 2>&1 || { echo "systemd is present but its manager is not usable." >&2; return 1; }
  local unit state
  for unit in "${UNITS[@]}"; do
    state="$(systemctl show "${unit}" --property=LoadState --value 2>/dev/null || true)"
    [[ "${state}" == "loaded" ]] || { echo "Forge systemd unit is not installed: ${unit}. Run 'just systemd-install'." >&2; return 1; }
  done
}

wait_healthy() {
  local name="$1" url="$2" attempts="${FORGE_RUNTIME_HEALTH_ATTEMPTS:-60}"
  for ((i=0; i<attempts; i++)); do
    curl --connect-timeout 2 --max-time 5 -fsS "${url}" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "${name} did not become healthy: ${url}" >&2
  return 1
}

postgres_running() {
  [[ -n "$(docker compose --project-directory "${ROOT}" ps --status running -q forge-agent-postgres)" ]]
}

start_postgres() {
  docker compose --project-directory "${ROOT}" up -d forge-agent-postgres
  postgres_running || { echo "Postgres did not reach the running state." >&2; return 1; }
}

prepare() {
  if [[ -n "${FORGE_RUNTIME_PREPARE_COMMAND:-}" ]]; then
    "${FORGE_RUNTIME_PREPARE_COMMAND}"
  else
    "${ROOT}/scripts/runtime/prepare.sh"
  fi
}

service_health() {
  curl --connect-timeout 2 --max-time 5 -fsS "$2" >/dev/null 2>&1 && printf '%s' active || printf '%s' unhealthy
}

status() {
  local failed=0 unit state name
  for unit in "${UNITS[@]}"; do
    state="$(systemctl is-active "${unit}" 2>/dev/null || true)"
    name="${unit#forge-}"
    name="${name%.service}"
    if [[ "${state}" == "active" ]]; then
      state="$(service_health "${name}" "$(health_url "${name}")")"
    fi
    printf '%-10s %s\n' "${name}" "${state:-unknown}"
    [[ "${state}" == "active" ]] || failed=1
  done
  if postgres_running; then
    printf '%-10s %s\n' postgres active
  else
    printf '%-10s %s\n' postgres failed
    failed=1
  fi
  return "${failed}"
}

health_url() {
  case "$1" in
    knowledge) printf 'http://127.0.0.1:7081/health' ;;
    jarvis) printf 'http://127.0.0.1:7071/health' ;;
    agent) printf 'http://127.0.0.1:7091/actuator/health' ;;
    nexus) printf 'http://127.0.0.1:9099/fgaisox/actuator/health' ;;
  esac
}

case "${ACTION}" in
  validate) validate ;;
  start)
    validate
    prepare
    start_postgres
    # `start` is a no-op for active units. Restart so freshly built artifacts are always loaded.
    privileged systemctl restart "${UNITS[@]}"
    wait_healthy knowledge http://127.0.0.1:7081/health
    wait_healthy jarvis http://127.0.0.1:7071/health
    wait_healthy agent http://127.0.0.1:7091/actuator/health
    wait_healthy nexus http://127.0.0.1:9099/fgaisox/actuator/health
    ;;
  stop) validate; privileged systemctl stop "${REVERSE_UNITS[@]}"; docker compose --project-directory "${ROOT}" stop forge-agent-postgres ;;
  restart)
    validate
    prepare
    start_postgres
    privileged systemctl restart "${UNITS[@]}"
    wait_healthy knowledge http://127.0.0.1:7081/health
    wait_healthy jarvis http://127.0.0.1:7071/health
    wait_healthy agent http://127.0.0.1:7091/actuator/health
    wait_healthy nexus http://127.0.0.1:9099/fgaisox/actuator/health
    ;;
  status) validate; status ;;
  logs)
    validate
    if [[ "${SERVICE}" == "postgres" ]]; then exec docker compose --project-directory "${ROOT}" logs --follow forge-agent-postgres; fi
    if [[ "${SERVICE}" == "all" ]]; then privileged journalctl --follow --unit forge-knowledge.service --unit forge-jarvis.service --unit forge-agent.service --unit forge-nexus.service; exit $?; fi
    [[ " ${UNITS[*]} " == *" forge-${SERVICE}.service "* ]] || { echo "Unknown service: ${SERVICE}" >&2; exit 2; }
    privileged journalctl --follow --unit "forge-${SERVICE}.service"
    ;;
  *) echo "Unknown systemd action: ${ACTION}" >&2; exit 2 ;;
esac
