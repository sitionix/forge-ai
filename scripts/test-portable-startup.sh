#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SYSTEM_PATH="/usr/bin:/bin:/usr/sbin:/sbin"
PASSED=0
TOTAL=0
TEMP_DIRS=()
cleanup() { for dir in "${TEMP_DIRS[@]:-}"; do rm -rf "${dir}"; done; }
trap cleanup EXIT
tmp() { local dir; dir="$(mktemp -d)"; TEMP_DIRS+=("${dir}"); printf '%s' "${dir}"; }
run_case() { local name="$1"; shift; TOTAL=$((TOTAL + 1)); if "$@"; then printf 'ok - %s\n' "${name}"; PASSED=$((PASSED + 1)); else printf 'not ok - %s\n' "${name}" >&2; exit 1; fi; }

fake_systemd_bin() {
  local bin="$1"
  cat > "${bin}/systemctl" <<'EOF'
#!/usr/bin/env bash
printf 'systemctl %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
if [[ "${TEST_SYSTEMD_USABLE:-1}" != 1 ]]; then exit 1; fi
case "$*" in
  "show --property=Version --value") echo 257 ;;
  *"--property=LoadState --value"*) echo loaded ;;
  is-active*) echo active ;;
  start*) [[ "${TEST_SYSTEMD_START_EXIT:-0}" == 0 ]] || exit "${TEST_SYSTEMD_START_EXIT}" ;;
esac
EOF
  cat > "${bin}/journalctl" <<'EOF'
#!/usr/bin/env bash
printf 'journalctl %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
EOF
  cat > "${bin}/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
  chmod +x "${bin}/systemctl" "${bin}/journalctl" "${bin}/sudo"
}

fake_common_bin() {
  local bin="$1"
  cat > "${bin}/docker" <<'EOF'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
if [[ "$*" == *"ps --status running -q"* ]]; then echo postgres-container; fi
EOF
  cat > "${bin}/curl" <<'EOF'
#!/usr/bin/env bash
exit "${TEST_CURL_EXIT:-0}"
EOF
  chmod +x "${bin}/docker" "${bin}/curl"
}

fake_tmux_bin() {
  local bin="$1"
  cat > "${bin}/tmux" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
state="${TEST_TMUX_STATE}"
printf 'tmux %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
case "${1:-}" in
  has-session) [[ -d "${state}/session" ]] ;;
  new-session) mkdir -p "${state}/session"; printf '0:0' > "${state}/session/runtime" ;;
  set-option|pipe-pane) ;;
  new-window)
    while (($#)); do [[ "$1" == -n ]] && { shift; name="$1"; break; }; shift; done
    printf '0:0' > "${state}/session/${name}"
    ;;
  kill-window) rm -f "${state}/session/runtime" ;;
  kill-session) rm -rf "${state}/session" ;;
  display-message)
    target=""; while (($#)); do [[ "$1" == -t ]] && { shift; target="$1"; }; shift || true; done
    name="${target##*:}"; cat "${state}/session/${name}"
    ;;
  select-window|attach-session) ;;
esac
EOF
  cat > "${bin}/tail" <<'EOF'
#!/usr/bin/env bash
printf 'tail %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
EOF
  chmod +x "${bin}/tmux" "${bin}/tail"
}

case_backend_resolution() {
  local dir bin log runtime
  dir="$(tmp)"; bin="${dir}/bin"; log="${dir}/log"; runtime="${dir}/systemd"; mkdir -p "${bin}" "${runtime}"
  export TEST_RUNTIME_LOG="${log}"
  fake_systemd_bin "${bin}"
  [[ "$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin "${ROOT}/scripts/runtime/resolve-backend.sh")" == MANAGED_LOCAL_SESSION ]]
  [[ "$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${runtime}" "${ROOT}/scripts/runtime/resolve-backend.sh")" == SYSTEMD ]]
}

case_unusable_systemd_never_falls_back() {
  local dir bin output
  dir="$(tmp)"; bin="${dir}/bin"; mkdir -p "${bin}" "${dir}/systemd"; export TEST_RUNTIME_LOG="${dir}/log"
  fake_systemd_bin "${bin}"
  if output="$(PATH="${bin}:${SYSTEM_PATH}" TEST_SYSTEMD_USABLE=0 FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${dir}/systemd" "${ROOT}/scripts/runtime/control.sh" start 2>&1)"; then return 1; fi
  [[ "${output}" == *"manager is not usable"* ]]
  ! grep -q tmux "${dir}/log"
}

case_inactive_systemd_never_selects() {
  local dir bin output
  dir="$(tmp)"; bin="${dir}/bin"; mkdir -p "${bin}"; export TEST_RUNTIME_LOG="${dir}/log"
  fake_systemd_bin "${bin}"
  if output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${dir}/absent" "${ROOT}/scripts/runtime/resolve-backend.sh" 2>&1)"; then return 1; fi
  [[ "${output}" == *"not the active system runtime"* ]]
}

case_systemd_action_failure_never_falls_back() {
  local dir bin
  dir="$(tmp)"; bin="${dir}/bin"; mkdir -p "${bin}" "${dir}/systemd"; export TEST_RUNTIME_LOG="${dir}/log"
  fake_systemd_bin "${bin}"; fake_common_bin "${bin}"
  if PATH="${bin}:${SYSTEM_PATH}" TEST_SYSTEMD_START_EXIT=23 FORGE_SYSTEMD_USE_SUDO=0 FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${dir}/systemd" "${ROOT}/scripts/runtime/control.sh" start >/dev/null 2>&1; then return 1; fi
  ! grep -q tmux "${dir}/log"
}

case_systemd_lifecycle() {
  local dir bin action
  dir="$(tmp)"; bin="${dir}/bin"; mkdir -p "${bin}" "${dir}/systemd"; export TEST_RUNTIME_LOG="${dir}/log"
  fake_systemd_bin "${bin}"; fake_common_bin "${bin}"
  for action in start stop restart status; do
    PATH="${bin}:${SYSTEM_PATH}" FORGE_SYSTEMD_USE_SUDO=0 FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${dir}/systemd" "${ROOT}/scripts/runtime/control.sh" "${action}" >/dev/null
  done
  grep -q 'systemctl start forge-knowledge.service' "${dir}/log"
  grep -q 'systemctl stop forge-nexus.service' "${dir}/log"
  grep -q 'systemctl restart forge-knowledge.service' "${dir}/log"
  grep -q 'systemctl is-active forge-agent.service' "${dir}/log"
}

case_managed_session_lifecycle() {
  local dir bin prepare
  dir="$(tmp)"; bin="${dir}/bin"; prepare="${dir}/prepare"; mkdir -p "${bin}"; export TEST_RUNTIME_LOG="${dir}/log" TEST_TMUX_STATE="${dir}/tmux"
  fake_common_bin "${bin}"; fake_tmux_bin "${bin}"
  cat > "${prepare}" <<'EOF'
#!/usr/bin/env bash
printf 'prepare\n' >> "${TEST_RUNTIME_LOG}"
EOF
  chmod +x "${prepare}"
  local envs=(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin FORGE_RUNTIME_PREPARE_COMMAND="${prepare}" FORGE_MANAGED_SESSION_DIR="${dir}/managed" FORGE_RUNTIME_HEALTH_ATTEMPTS=1)
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" start >/dev/null
  [[ -d "${dir}/tmux/session" ]]
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" status >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" logs agent >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" logs all >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" attach agent >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" start >/dev/null
  [[ "$(grep -c '^prepare$' "${dir}/log")" == 1 ]]
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" restart >/dev/null
  [[ "$(grep -c '^prepare$' "${dir}/log")" == 2 ]]
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" stop >/dev/null
  [[ ! -d "${dir}/tmux/session" ]]
}

case_failed_child_is_real() {
  local dir bin prepare output
  dir="$(tmp)"; bin="${dir}/bin"; prepare="${dir}/prepare"; mkdir -p "${bin}"; export TEST_RUNTIME_LOG="${dir}/log" TEST_TMUX_STATE="${dir}/tmux"
  fake_common_bin "${bin}"; fake_tmux_bin "${bin}"; printf '#!/usr/bin/env bash\n' > "${prepare}"; chmod +x "${prepare}"
  PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin FORGE_RUNTIME_PREPARE_COMMAND="${prepare}" FORGE_MANAGED_SESSION_DIR="${dir}/managed" FORGE_RUNTIME_HEALTH_ATTEMPTS=1 "${ROOT}/scripts/runtime/control.sh" start >/dev/null
  printf '1:17' > "${dir}/tmux/session/agent"
  if output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin FORGE_MANAGED_SESSION_DIR="${dir}/managed" "${ROOT}/scripts/runtime/control.sh" status 2>&1)"; then return 1; fi
  [[ "${output}" == *"FAILED(17)"* ]]
}

case_architecture_cleanup() {
  ! rg -n '_dev-start|_stop-dev|forge_start_background|forge-agent\.pid|forge-ai\.pid|nohup' "${ROOT}/Justfile" "${ROOT}/scripts/runtime" >/dev/null
  ! rg -n 'lsof|kill .*port|kill .*listener' "${ROOT}/scripts/runtime" >/dev/null
  [[ ! -e "${ROOT}/scripts/runtime-ownership.sh" && ! -e "${ROOT}/scripts/lib/process.sh" ]]
  for command in start stop restart status logs attach; do rg -q "^${command}([[:space:]].*)?:" "${ROOT}/Justfile"; done
}

case_systemd_units_use_runtime_runners() {
  local dir
  dir="$(tmp)"
  FORGE_AI_HOME="${ROOT}" "${ROOT}/scripts/systemd/render-units.sh" "${dir}/units" "${dir}/forge-ai.env" "${dir}/installed.env" >/dev/null
  rg -q 'scripts/runtime/run-agent\.sh' "${dir}/units/forge-agent.service"
  rg -q 'scripts/runtime/run-nexus\.sh' "${dir}/units/forge-nexus.service"
  rg -q 'scripts/runtime/run-knowledge\.sh' "${dir}/units/forge-knowledge.service"
  rg -q 'scripts/runtime/run-jarvis\.sh' "${dir}/units/forge-jarvis.service"
}

run_case "backend resolver selects systemd and managed session" case_backend_resolution
run_case "unusable systemd never falls back" case_unusable_systemd_never_falls_back
run_case "systemctl without an active systemd runtime is rejected" case_inactive_systemd_never_selects
run_case "a selected systemd action never falls back" case_systemd_action_failure_never_falls_back
run_case "systemd lifecycle uses one owner" case_systemd_lifecycle
run_case "managed session start/status/logs/attach/restart/stop" case_managed_session_lifecycle
run_case "managed session reports a failed child" case_failed_child_is_real
run_case "legacy PID/nohup launcher is removed" case_architecture_cleanup
run_case "rendered systemd units use the runtime service runners" case_systemd_units_use_runtime_runners
printf 'Portable startup shell tests: PASS %s/%s\n' "${PASSED}" "${TOTAL}"
