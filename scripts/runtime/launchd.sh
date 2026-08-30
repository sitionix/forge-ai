#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
ACTION="${1:?Usage: launchd.sh start|stop|restart|status|logs [service]}"
SERVICE="${2:-all}"
DOMAIN="gui/${UID}"
PLIST_DIR="${FORGE_LAUNCHD_DIR:-${HOME:?HOME is required}/Library/LaunchAgents}"
LOG_DIR="${FORGE_LAUNCHD_LOG_DIR:-${ROOT}/var/launchd/logs}"
SERVICES=(knowledge jarvis agent nexus)
REVERSE_SERVICES=(nexus agent jarvis knowledge)

label() { printf 'ai.forge.%s' "$1"; }
plist() { printf '%s/%s.plist' "${PLIST_DIR}" "$(label "$1")"; }
target() { printf '%s/%s' "${DOMAIN}" "$(label "$1")"; }

validate() {
  [[ "${FORGE_RUNTIME_OS:-$(uname -s)}" == Darwin ]] || { echo "The LAUNCHD backend is supported only on macOS." >&2; return 1; }
  command -v launchctl >/dev/null 2>&1 || { echo "macOS Forge runtime requires launchctl." >&2; return 1; }
  launchctl print "${DOMAIN}" >/dev/null 2>&1 || { echo "The launchd user domain ${DOMAIN} is unavailable." >&2; return 1; }
}

is_loaded() { launchctl print "$(target "$1")" >/dev/null 2>&1; }

is_owned_plist() {
  local file
  file="$(plist "$1")"
  [[ -f "${file}" ]] && grep -Fq "<string>${ROOT}/scripts/runtime/run-$1.sh</string>" "${file}"
}

assert_owned() {
  local service="$1"
  is_owned_plist "${service}" || {
    echo "Forge launchd service '${service}' is not installed for this checkout (${ROOT}). Run 'just start'." >&2
    return 1
  }
}

prepare() {
  if [[ -n "${FORGE_RUNTIME_PREPARE_COMMAND:-}" ]]; then
    "${FORGE_RUNTIME_PREPARE_COMMAND}"
  else
    "${ROOT}/scripts/runtime/prepare.sh"
  fi
}

postgres_running() {
  [[ -n "$(docker compose --project-directory "${ROOT}" ps --status running -q forge-agent-postgres)" ]]
}

start_postgres() {
  docker compose --project-directory "${ROOT}" up -d forge-agent-postgres
  postgres_running || { echo "Postgres did not reach the running state." >&2; return 1; }
}

install_plists() {
  local service file
  mkdir -p "${PLIST_DIR}" "${LOG_DIR}"
  for service in "${SERVICES[@]}"; do
    file="$(plist "${service}")"
    if is_loaded "${service}" && ! is_owned_plist "${service}"; then
      echo "A foreign launchd service already owns $(label "${service}"); refusing to replace it." >&2
      return 1
    fi
    "${ROOT}/scripts/launchd/render-plist.sh" "${service}" "${file}" "${LOG_DIR}"
  done
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
  local service="$1" attempts="${FORGE_RUNTIME_HEALTH_ATTEMPTS:-60}" details state exit_code
  for ((i=0; i<attempts; i++)); do
    if ! details="$(launchctl print "$(target "${service}")" 2>/dev/null)"; then
      echo "${service} LaunchAgent disappeared before becoming healthy." >&2
      return 1
    fi
    state="$(sed -n 's/^[[:space:]]*state = //p' <<<"${details}" | head -n 1)"
    if [[ "${state}" != running ]]; then
      exit_code="$(sed -n 's/^[[:space:]]*last exit code = //p' <<<"${details}" | head -n 1)"
      echo "${service} LaunchAgent is not running (state=${state:-unknown}${exit_code:+, last exit code=${exit_code}})." >&2
      return 1
    fi
    if curl --connect-timeout 2 --max-time 5 -fsS "$(health_url "${service}")" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "${service} LaunchAgent is running but did not become healthy: $(health_url "${service}")" >&2
  return 1
}

start_services() {
  local service
  for service in "${SERVICES[@]}"; do
    assert_owned "${service}"
    if is_loaded "${service}"; then
      launchctl kickstart -k "$(target "${service}")"
    else
      launchctl bootstrap "${DOMAIN}" "$(plist "${service}")"
    fi
  done
  for service in "${SERVICES[@]}"; do wait_healthy "${service}"; done
}

stop_services() {
  local service
  for service in "${REVERSE_SERVICES[@]}"; do
    if is_loaded "${service}"; then
      assert_owned "${service}"
      launchctl bootout "$(target "${service}")"
    fi
  done
}

status() {
  local service details state failed=0
  for service in "${SERVICES[@]}"; do
    if details="$(launchctl print "$(target "${service}")" 2>/dev/null)"; then
      if ! is_owned_plist "${service}"; then
        state=foreign
        failed=1
      else
        state="$(sed -n 's/^[[:space:]]*state = //p' <<<"${details}" | head -n 1)"
        if [[ "${state}" == running ]]; then
          if curl --connect-timeout 2 --max-time 5 -fsS "$(health_url "${service}")" >/dev/null 2>&1; then state=active; else state=unhealthy; failed=1; fi
        else
          state="${state:-loaded}"
          failed=1
        fi
      fi
    else
      state=stopped
      failed=1
    fi
    printf '%-10s %s\n' "${service}" "${state}"
  done
  if postgres_running; then printf '%-10s %s\n' postgres active; else printf '%-10s %s\n' postgres failed; failed=1; fi
  return "${failed}"
}

case "${ACTION}" in
  start)
    validate
    install_plists
    prepare
    start_postgres
    start_services
    ;;
  stop)
    validate
    stop_services
    docker compose --project-directory "${ROOT}" stop forge-agent-postgres
    ;;
  restart)
    validate
    stop_services
    start_postgres
    prepare
    install_plists
    start_services
    ;;
  status) validate; status ;;
  logs)
    validate
    if [[ "${SERVICE}" == postgres ]]; then exec docker compose --project-directory "${ROOT}" logs --follow forge-agent-postgres; fi
    if [[ "${SERVICE}" == all ]]; then exec tail -n 200 -F "${LOG_DIR}"/*.log; fi
    [[ " ${SERVICES[*]} " == *" ${SERVICE} "* ]] || { echo "Unknown service: ${SERVICE}" >&2; exit 2; }
    assert_owned "${SERVICE}"
    exec tail -n 200 -F "${LOG_DIR}/${SERVICE}.out.log" "${LOG_DIR}/${SERVICE}.err.log"
    ;;
  *) echo "Unknown launchd action: ${ACTION}" >&2; exit 2 ;;
esac
