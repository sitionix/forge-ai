#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ACTION="${1:?Usage: managed-session.sh start|stop|restart|status|logs|attach [service]}"
SERVICE="${2:-all}"
SESSION="${FORGE_MANAGED_SESSION_NAME:-forge-ai}"
STATE_DIR="${FORGE_MANAGED_SESSION_DIR:-${ROOT}/var/managed-session}"
LOG_DIR="${STATE_DIR}/logs"
SERVICES=(knowledge jarvis agent nexus)

require_tmux() { command -v tmux >/dev/null 2>&1 || { echo "The macOS managed runtime requires tmux (brew install tmux)." >&2; exit 1; }; }
has_session() { tmux has-session -t "=${SESSION}" 2>/dev/null; }
valid_service() { [[ " ${SERVICES[*]} postgres all " == *" $1 "* ]]; }
pane_state() {
  local value
  value="$(tmux display-message -p -t "=${SESSION}:$1" '#{pane_dead}:#{pane_dead_status}' 2>/dev/null || true)"
  case "${value}" in 0:*) printf 'RUNNING' ;; 1:0) printf 'STOPPED' ;; 1:*) printf 'FAILED(%s)' "${value#*:}" ;; *) printf 'MISSING' ;; esac
}
service_state() {
  local service="$1" state
  state="$(pane_state "${service}")"
  if [[ "${state}" == "RUNNING" ]] && ! curl --connect-timeout 2 --max-time 5 -fsS "$(health_url "${service}")" >/dev/null 2>&1; then
    state=UNHEALTHY
  fi
  printf '%s' "${state}"
}
health_url() {
  case "$1" in
    knowledge) printf 'http://127.0.0.1:7081/health' ;;
    jarvis) printf 'http://127.0.0.1:7071/health' ;;
    agent) printf 'http://127.0.0.1:7091/actuator/health' ;;
    nexus) printf 'http://127.0.0.1:9099/fgaisox/actuator/health' ;;
  esac
}
wait_healthy() {
  local service="$1" attempts="${FORGE_RUNTIME_HEALTH_ATTEMPTS:-60}"
  for ((i=0; i<attempts; i++)); do
    [[ "$(pane_state "${service}")" == "RUNNING" ]] || { echo "${service} exited during startup: $(pane_state "${service}")" >&2; return 1; }
    curl --connect-timeout 2 --max-time 5 -fsS "$(health_url "${service}")" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "${service} did not become healthy." >&2
  return 1
}
start_service() {
  local service="$1" command log
  command="$(printf '%q ' env FORGE_AI_HOME="${ROOT}" FORGE_RUNTIME_DIR="${ROOT}/var" "${ROOT}/scripts/runtime/run-${service}.sh")"
  log="${LOG_DIR}/${service}.log"
  : > "${log}"
  tmux new-window -d -t "=${SESSION}" -n "${service}" "${command}"
  tmux pipe-pane -o -t "=${SESSION}:${service}" "cat >> $(printf '%q' "${log}")"
}
start() {
  require_tmux
  if has_session; then echo "Forge managed session '${SESSION}' already exists."; status; return; fi
  if [[ -n "${FORGE_RUNTIME_PREPARE_COMMAND:-}" ]]; then
    "${FORGE_RUNTIME_PREPARE_COMMAND}"
  else
    "${ROOT}/scripts/runtime/prepare.sh"
  fi
  docker compose --project-directory "${ROOT}" up -d forge-agent-postgres
  [[ -n "$(docker compose --project-directory "${ROOT}" ps --status running -q forge-agent-postgres)" ]] || { echo "Postgres did not reach the running state." >&2; return 1; }
  mkdir -p "${LOG_DIR}"
  tmux new-session -d -s "${SESSION}" -n runtime "while :; do sleep 3600; done"
  tmux set-option -t "=${SESSION}" remain-on-exit on >/dev/null
  local service
  for service in "${SERVICES[@]}"; do start_service "${service}"; done
  tmux kill-window -t "=${SESSION}:runtime"
  for service in "${SERVICES[@]}"; do wait_healthy "${service}"; done
  status
}
stop() {
  require_tmux
  if has_session; then tmux kill-session -t "=${SESSION}"; else echo "Forge managed session is not running."; fi
  docker compose --project-directory "${ROOT}" stop forge-agent-postgres
}
status() {
  require_tmux
  local failed=0 service state
  if ! has_session; then echo "Forge managed session '${SESSION}' is STOPPED."; return 1; fi
  for service in "${SERVICES[@]}"; do state="$(service_state "${service}")"; printf '%-10s %s\n' "${service}" "${state}"; [[ "${state}" == "RUNNING" ]] || failed=1; done
  if [[ -n "$(docker compose --project-directory "${ROOT}" ps --status running -q forge-agent-postgres)" ]]; then
    printf '%-10s %s\n' postgres RUNNING
  else
    printf '%-10s %s\n' postgres FAILED
    failed=1
  fi
  return "${failed}"
}

case "${ACTION}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs)
    require_tmux; valid_service "${SERVICE}" || { echo "Unknown service: ${SERVICE}" >&2; exit 2; }
    if [[ "${SERVICE}" == "postgres" ]]; then exec docker compose --project-directory "${ROOT}" logs --follow forge-agent-postgres; fi
    if [[ "${SERVICE}" == "all" ]]; then
      for service in "${SERVICES[@]}"; do [[ -f "${LOG_DIR}/${service}.log" ]] || { echo "No log exists for ${service}." >&2; exit 1; }; done
      exec tail -n 200 -F "${LOG_DIR}"/knowledge.log "${LOG_DIR}"/jarvis.log "${LOG_DIR}"/agent.log "${LOG_DIR}"/nexus.log
    fi
    [[ -f "${LOG_DIR}/${SERVICE}.log" ]] || { echo "No log exists for ${SERVICE}." >&2; exit 1; }
    exec tail -n 200 -F "${LOG_DIR}/${SERVICE}.log"
    ;;
  attach)
    require_tmux; valid_service "${SERVICE}" && [[ "${SERVICE}" != "all" && "${SERVICE}" != "postgres" ]] || { echo "Attach requires nexus, agent, knowledge, or jarvis." >&2; exit 2; }
    has_session || { echo "Forge managed session is not running." >&2; exit 1; }
    tmux select-window -t "=${SESSION}:${SERVICE}"
    exec tmux attach-session -t "=${SESSION}"
    ;;
  *) echo "Unknown managed-session action: ${ACTION}" >&2; exit 2 ;;
esac
