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

fake_launchd_bin() {
  local bin="$1"
  cat > "${bin}/launchctl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
state="${TEST_LAUNCHD_STATE}"
printf 'launchctl %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
case "${1:-}" in
  print)
    if [[ "${2:-}" == gui/*/* ]]; then
      name="${2##*/}"
      [[ -f "${state}/${name}" ]] || exit 113
      printf 'state = running\npid = 123\n'
    fi
    ;;
  bootstrap)
    mkdir -p "${state}"
    name="$(basename "${3}" .plist)"
    : > "${state}/${name}"
    ;;
  kickstart) ;;
  bootout) rm -f "${state}/${2##*/}" ;;
esac
EOF
  cat > "${bin}/tail" <<'EOF'
#!/usr/bin/env bash
printf 'tail %s\n' "$*" >> "${TEST_RUNTIME_LOG}"
EOF
  chmod +x "${bin}/launchctl" "${bin}/tail"
}

case_backend_resolution() {
  local dir bin log runtime
  dir="$(tmp)"; bin="${dir}/bin"; log="${dir}/log"; runtime="${dir}/systemd"; mkdir -p "${bin}" "${runtime}"
  export TEST_RUNTIME_LOG="${log}"
  fake_systemd_bin "${bin}"
  fake_launchd_bin "${bin}"
  export TEST_LAUNCHD_STATE="${dir}/launchd"
  [[ "$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin "${ROOT}/scripts/runtime/resolve-backend.sh")" == LAUNCHD ]]
  [[ "$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${runtime}" "${ROOT}/scripts/runtime/resolve-backend.sh")" == SYSTEMD ]]
}

case_unusable_systemd_never_falls_back() {
  local dir bin output
  dir="$(tmp)"; bin="${dir}/bin"; mkdir -p "${bin}" "${dir}/systemd"; export TEST_RUNTIME_LOG="${dir}/log"
  fake_systemd_bin "${bin}"
  if output="$(PATH="${bin}:${SYSTEM_PATH}" TEST_SYSTEMD_USABLE=0 FORGE_RUNTIME_OS=Linux FORGE_RUNTIME_LINUX_ID=ubuntu FORGE_SYSTEMD_RUNTIME_DIR="${dir}/systemd" "${ROOT}/scripts/runtime/control.sh" start 2>&1)"; then return 1; fi
  [[ "${output}" == *"manager is not usable"* ]]
  ! grep -q launchctl "${dir}/log"
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
  ! grep -q launchctl "${dir}/log"
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

case_launchd_lifecycle() {
  local dir bin prepare
  dir="$(tmp)"; bin="${dir}/bin"; prepare="${dir}/prepare"; mkdir -p "${bin}"; export TEST_RUNTIME_LOG="${dir}/log" TEST_LAUNCHD_STATE="${dir}/launchd"
  fake_common_bin "${bin}"; fake_launchd_bin "${bin}"
  cat > "${prepare}" <<'EOF'
#!/usr/bin/env bash
printf 'prepare\n' >> "${TEST_RUNTIME_LOG}"
EOF
  chmod +x "${prepare}"
  local envs=(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin FORGE_RUNTIME_PREPARE_COMMAND="${prepare}" FORGE_LAUNCHD_DIR="${dir}/plists" FORGE_LAUNCHD_LOG_DIR="${dir}/logs" FORGE_RUNTIME_HEALTH_ATTEMPTS=1)
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" start >/dev/null
  [[ -f "${dir}/launchd/ai.forge.agent" ]]
  grep -Fq "${ROOT}/scripts/runtime/run-agent.sh" "${dir}/plists/ai.forge.agent.plist"
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" status >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" logs agent >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" logs all >/dev/null
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" restart >/dev/null
  [[ "$(grep -c '^prepare$' "${dir}/log")" == 2 ]]
  env "${envs[@]}" "${ROOT}/scripts/runtime/control.sh" stop >/dev/null
  [[ ! -e "${dir}/launchd/ai.forge.agent" ]]
}

case_launchd_rejects_foreign_loaded_service() {
  local dir bin prepare output
  dir="$(tmp)"; bin="${dir}/bin"; prepare="${dir}/prepare"; mkdir -p "${bin}" "${dir}/launchd" "${dir}/plists"
  export TEST_RUNTIME_LOG="${dir}/log" TEST_LAUNCHD_STATE="${dir}/launchd"
  fake_common_bin "${bin}"; fake_launchd_bin "${bin}"; printf '#!/usr/bin/env bash\n' > "${prepare}"; chmod +x "${prepare}"
  : > "${dir}/launchd/ai.forge.agent"
  printf '%s\n' '<plist><string>/another/checkout/scripts/runtime/run-agent.sh</string></plist>' > "${dir}/plists/ai.forge.agent.plist"
  if output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_OS=Darwin FORGE_RUNTIME_PREPARE_COMMAND="${prepare}" FORGE_LAUNCHD_DIR="${dir}/plists" FORGE_LAUNCHD_LOG_DIR="${dir}/logs" "${ROOT}/scripts/runtime/control.sh" start 2>&1)"; then return 1; fi
  [[ "${output}" == *"foreign launchd service"* ]]
  [[ -f "${dir}/launchd/ai.forge.agent" ]]
}

case_architecture_cleanup() {
  ! rg -n '_dev-start|_stop-dev|forge_start_background|forge-agent\.pid|forge-ai\.pid|nohup|tmux|MANAGED_LOCAL_SESSION' "${ROOT}/Justfile" "${ROOT}/scripts/runtime" "${ROOT}/scripts/launchd" >/dev/null
  ! rg -n 'lsof|kill .*port|kill .*listener' "${ROOT}/scripts/runtime" "${ROOT}/scripts/launchd" >/dev/null
  [[ ! -e "${ROOT}/scripts/runtime-ownership.sh" && ! -e "${ROOT}/scripts/lib/process.sh" ]]
  [[ ! -e "${ROOT}/scripts/runtime/managed-session.sh" ]]
  for command in start stop restart status logs; do rg -q "^${command}([[:space:]].*)?:" "${ROOT}/Justfile"; done
  ! rg -q '^attach([[:space:]].*)?:' "${ROOT}/Justfile"
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

run_case "backend resolver selects systemd and launchd" case_backend_resolution
run_case "unusable systemd never falls back" case_unusable_systemd_never_falls_back
run_case "systemctl without an active systemd runtime is rejected" case_inactive_systemd_never_selects
run_case "a selected systemd action never falls back" case_systemd_action_failure_never_falls_back
run_case "systemd lifecycle uses one owner" case_systemd_lifecycle
run_case "launchd start/status/logs/restart/stop" case_launchd_lifecycle
run_case "launchd does not replace a foreign loaded service" case_launchd_rejects_foreign_loaded_service
run_case "legacy PID/nohup launcher is removed" case_architecture_cleanup
run_case "rendered systemd units use the runtime service runners" case_systemd_units_use_runtime_runners
printf 'Portable startup shell tests: PASS %s/%s\n' "${PASSED}" "${TOTAL}"
