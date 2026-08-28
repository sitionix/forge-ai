#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SYSTEM_PATH="/usr/bin:/bin:/usr/sbin:/sbin"
PASSED=0
TOTAL=0
TEMP_DIRS=()
CHILD_PIDS=()

cleanup() {
  local pid
  for pid in "${CHILD_PIDS[@]:-}"; do
    kill "${pid}" >/dev/null 2>&1 || true
    wait "${pid}" 2>/dev/null || true
  done
  local dir
  for dir in "${TEMP_DIRS[@]:-}"; do
    rm -rf "${dir}"
  done
}
trap cleanup EXIT

new_temp_dir() {
  local dir
  dir="$(mktemp -d)"
  TEMP_DIRS+=("${dir}")
  printf '%s' "${dir}"
}

pass() {
  printf 'ok - %s\n' "$1"
  PASSED=$((PASSED + 1))
}

fail() {
  printf 'not ok - %s\n' "$1" >&2
  exit 1
}

run_case() {
  local name="$1"
  shift
  TOTAL=$((TOTAL + 1))
  "$@" && pass "${name}" || fail "${name}"
}

write_fake_curl_static() {
  local bin_dir="$1"
  local exit_code="$2"
  cat > "${bin_dir}/curl" <<EOF
#!/usr/bin/env bash
exit ${exit_code}
EOF
  chmod +x "${bin_dir}/curl"
}

write_fake_curl_ready_file() {
  local bin_dir="$1"
  cat > "${bin_dir}/curl" <<'EOF'
#!/usr/bin/env bash
if [[ -f "${TEST_READY_FILE}" ]]; then
  exit 0
fi
exit 22
EOF
  chmod +x "${bin_dir}/curl"
}

write_fake_ollama_touch_ready() {
  local bin_dir="$1"
  cat > "${bin_dir}/ollama" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "serve" ]]; then
  : > "${TEST_READY_FILE}"
  sleep 60
fi
EOF
  chmod +x "${bin_dir}/ollama"
}

write_fake_ollama_fail() {
  local bin_dir="$1"
  cat > "${bin_dir}/ollama" <<'EOF'
#!/usr/bin/env bash
exit 17
EOF
  chmod +x "${bin_dir}/ollama"
}

write_fake_systemctl() {
  local bin_dir="$1"
  cat > "${bin_dir}/systemctl" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  is-active)
    printf '%s\n' "${TEST_SYSTEMD_STATE:-inactive}" inactive inactive inactive
    [[ "${TEST_SYSTEMD_STATE:-inactive}" == "active" || "${TEST_SYSTEMD_STATE:-inactive}" == "activating" ]]
    ;;
  show)
    printf '%s\n' "${TEST_SYSTEMD_MAIN_PID:-0}" 0 0 0
    ;;
  stop)
    exit 0
    ;;
esac
EOF
  chmod +x "${bin_dir}/systemctl"
}

write_fake_lsof_for_port() {
  local bin_dir="$1"
  cat > "${bin_dir}/lsof" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"TCP:${TEST_LISTENER_PORT}"* ]]; then
  printf '%s\n' "${TEST_SYSTEMD_MAIN_PID}"
fi
EOF
  chmod +x "${bin_dir}/lsof"
}

write_fake_ollama_never_ready() {
  local bin_dir="$1"
  cat > "${bin_dir}/ollama" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "serve" ]]; then
  sleep 60
fi
EOF
  chmod +x "${bin_dir}/ollama"
}

case_ollama_api_already_reachable() {
  local dir bin output
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  write_fake_curl_static "${bin}" 0
  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1")"
  [[ "${output}" == *"Ollama API is available"* ]]
  [[ ! -f "${dir}/var/ollama/ollama.pid" ]]
}

case_ollama_cli_absent() {
  local dir bin output
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  write_fake_curl_static "${bin}" 22
  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1")"
  [[ "${output}" == *"WARNING: Ollama is not installed or not on PATH; continuing without Ollama"* ]]
  [[ ! -f "${dir}/var/ollama/ollama.pid" ]]
}

case_ollama_start_succeeds() {
  local dir bin output pid
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  export TEST_READY_FILE="${dir}/ready"
  write_fake_curl_ready_file "${bin}"
  write_fake_ollama_touch_ready "${bin}"
  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1")"
  [[ "${output}" == *"Ollama started and API became reachable"* ]]
  pid="$(awk -F= '$1 == "PID" { print $2; exit }' "${dir}/var/ollama/ollama.pid")"
  kill -0 "${pid}" >/dev/null 2>&1
  "${ROOT_DIR}/scripts/ollama/stop-owned.sh" >/dev/null
}

case_ollama_start_fails() {
  local dir bin output
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  write_fake_curl_static "${bin}" 22
  write_fake_ollama_fail "${bin}"
  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1")"
  [[ "${output}" == *"WARNING: Ollama could not be started; continuing without Ollama"* ]]
}

case_ollama_readiness_timeout() {
  local dir bin output pid
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  write_fake_curl_static "${bin}" 22
  write_fake_ollama_never_ready "${bin}"
  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" FORGE_OLLAMA_OPTIONAL_READINESS_SECONDS=1 "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1")"
  [[ "${output}" == *"WARNING: Ollama could not be started; continuing without Ollama"* ]]
  pid="$(awk -F= '$1 == "PID" { print $2; exit }' "${dir}/var/ollama/ollama.pid")"
  kill -0 "${pid}" >/dev/null 2>&1
  PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/stop-owned.sh" >/dev/null
}

case_external_ollama_not_marked_owned() {
  local dir bin output external_pid
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  write_fake_curl_static "${bin}" 0
  sleep 60 &
  external_pid="$!"
  CHILD_PIDS+=("${external_pid}")
  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1")"
  [[ "${output}" == *"using existing runtime"* ]]
  [[ ! -f "${dir}/var/ollama/ollama.pid" ]]
  kill -0 "${external_pid}" >/dev/null 2>&1
}

case_forge_owned_ollama_stopped() {
  local dir bin pid
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  write_fake_curl_static "${bin}" 22
  write_fake_ollama_never_ready "${bin}"
  PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" FORGE_OLLAMA_OPTIONAL_READINESS_SECONDS=1 "${ROOT_DIR}/scripts/ollama/start-optional.sh" "http://127.0.0.1:1" >/dev/null
  pid="$(awk -F= '$1 == "PID" { print $2; exit }' "${dir}/var/ollama/ollama.pid")"
  PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/stop-owned.sh" >/dev/null
  ! kill -0 "${pid}" >/dev/null 2>&1
  [[ ! -f "${dir}/var/ollama/ollama.pid" ]]
}

case_external_ollama_not_stopped() {
  local dir external_pid
  dir="$(new_temp_dir)"
  sleep 60 &
  external_pid="$!"
  CHILD_PIDS+=("${external_pid}")
  FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/stop-owned.sh" >/dev/null
  kill -0 "${external_pid}" >/dev/null 2>&1
}

case_stale_ollama_pid_cleanup() {
  local dir pid_file
  dir="$(new_temp_dir)"
  pid_file="${dir}/var/ollama/ollama.pid"
  mkdir -p "$(dirname -- "${pid_file}")"
  printf 'PID=999999\nOWNER=forge-ai-ollama\nCOMMAND=ollama serve\n' > "${pid_file}"
  FORGE_RUNTIME_DIR="${dir}/var" "${ROOT_DIR}/scripts/ollama/stop-owned.sh" >/dev/null
  [[ ! -f "${pid_file}" ]]
}

case_setsid_absent() {
  local dir bin pid_file log_file pid
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  cat > "${bin}/setsid" <<'EOF'
#!/usr/bin/env bash
exit 99
EOF
  chmod +x "${bin}/setsid"
  pid_file="${dir}/service.pid"
  log_file="${dir}/service.log"
  PATH="${bin}:${SYSTEM_PATH}" bash -c 'source "$1"; forge_start_background "$2" "$3" "$4" sleep 60' bash "${ROOT_DIR}/scripts/lib/process.sh" "${pid_file}" "${log_file}" "${dir}"
  pid="$(cat "${pid_file}")"
  CHILD_PIDS+=("${pid}")
  kill -0 "${pid}" >/dev/null 2>&1
  ! grep -q 'setsid' "${ROOT_DIR}/scripts/knowledge/start.sh"
  ! grep -q 'setsid' "${ROOT_DIR}/scripts/jarvis/start.sh"
  ! grep -q 'setsid' "${ROOT_DIR}/Justfile"
}

case_sha256sum_absent_shasum_fallback() {
  local dir bin hash
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  printf 'abc' > "${dir}/data.txt"
  cat > "${bin}/sha256sum" <<'EOF'
#!/usr/bin/env bash
exit 127
EOF
  cat > "${bin}/shasum" <<'EOF'
#!/usr/bin/env bash
echo "fallbackhash  $3"
EOF
  chmod +x "${bin}/sha256sum" "${bin}/shasum"
  hash="$(PATH="${bin}:${SYSTEM_PATH}" bash -c 'source "$1"; forge_sha256 "$2"' bash "${ROOT_DIR}/scripts/lib/portable.sh" "${dir}/data.txt")"
  [[ "${hash}" == "fallbackhash" ]]
}

case_gnu_stat_absent_bsd_fallback() {
  local dir bin stamp
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  printf 'abc' > "${dir}/data.txt"
  cat > "${bin}/stat" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "-c" ]]; then
  exit 1
fi
if [[ "${1:-}" == "-f" ]]; then
  echo "bsd-stamp"
  exit 0
fi
exit 2
EOF
  chmod +x "${bin}/stat"
  stamp="$(PATH="${bin}:${SYSTEM_PATH}" bash -c 'source "$1"; forge_file_stamp "$2"' bash "${ROOT_DIR}/scripts/lib/portable.sh" "${dir}/data.txt")"
  [[ "${stamp}" == "bsd-stamp" ]]
}

case_jarvis_start_has_no_ollama_gate() {
  ! grep -E 'OLLAMA|ollama' "${ROOT_DIR}/scripts/jarvis/start.sh" >/dev/null
}

write_fake_npm_logger() {
  local bin_dir="$1"
  cat > "${bin_dir}/npm" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${TEST_NPM_LOG}"
if [[ "$*" == *" ci" ]]; then
  mkdir -p "${TEST_CONSOLE_ROOT}/node_modules/.bin"
  printf '#!/usr/bin/env bash\n' > "${TEST_CONSOLE_ROOT}/node_modules/.bin/vite"
  chmod +x "${TEST_CONSOLE_ROOT}/node_modules/.bin/vite"
fi
EOF
  chmod +x "${bin_dir}/npm"
}

case_console_build_installs_dependencies_when_vite_missing() {
  local dir bin output
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}" "${dir}/console"
  export TEST_NPM_LOG="${dir}/npm.log"
  export TEST_CONSOLE_ROOT="${dir}/console"
  write_fake_npm_logger "${bin}"

  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_CONSOLE_ROOT="${TEST_CONSOLE_ROOT}" "${ROOT_DIR}/scripts/console/build.sh")"
  [[ "${output}" == *"Installing Forge Console dependencies"* ]]
  grep -qx -- "--prefix ${TEST_CONSOLE_ROOT} ci" "${TEST_NPM_LOG}"
  grep -qx -- "--prefix ${TEST_CONSOLE_ROOT} run build" "${TEST_NPM_LOG}"
}

case_console_build_skips_install_when_vite_exists() {
  local dir bin output
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}" "${dir}/console/node_modules/.bin"
  printf '#!/usr/bin/env bash\n' > "${dir}/console/node_modules/.bin/vite"
  chmod +x "${dir}/console/node_modules/.bin/vite"
  export TEST_NPM_LOG="${dir}/npm.log"
  export TEST_CONSOLE_ROOT="${dir}/console"
  write_fake_npm_logger "${bin}"

  output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_CONSOLE_ROOT="${TEST_CONSOLE_ROOT}" "${ROOT_DIR}/scripts/console/build.sh")"
  [[ "${output}" != *"Installing Forge Console dependencies"* ]]
  ! grep -q ' ci$' "${TEST_NPM_LOG}"
  grep -qx -- "--prefix ${TEST_CONSOLE_ROOT} run build" "${TEST_NPM_LOG}"
}

case_systemd_units_render_stable_services() {
  local dir units env_file output
  dir="$(new_temp_dir)"
  units="${dir}/units"
  env_file="${dir}/env/forge-ai.env"
  mkdir -p "${dir}/workspace root" "${dir}/runtime" "${dir}/config"

  output="$(
    FORGE_RUNTIME_DIR="${dir}/runtime" \
    FORGE_CONFIG_DIR="${dir}/config" \
    FORGE_WORKSPACE_ROOT="${dir}/workspace root" \
    FORGE_SYSTEMD_USER="forge-user" \
    FORGE_SYSTEMD_GROUP="forge-group" \
    "${ROOT_DIR}/scripts/systemd/render-units.sh" "${units}" "${env_file}"
  )"

  [[ "${output}" == *"Rendered Forge systemd units"* ]]
  for unit in forge-agent.service forge-nexus.service forge-knowledge.service forge-jarvis.service; do
    [[ -f "${units}/${unit}" ]]
    grep -Fqx "User=forge-user" "${units}/${unit}"
    grep -Fqx "Group=forge-group" "${units}/${unit}"
    grep -Fqx "EnvironmentFile=${env_file}" "${units}/${unit}"
    grep -Fqx "StandardOutput=journal" "${units}/${unit}"
    grep -Fqx "StandardError=journal" "${units}/${unit}"
  done

  grep -Fqx "WorkingDirectory=${ROOT_DIR}" "${units}/forge-agent.service"
  grep -Fqx "WorkingDirectory=${ROOT_DIR}" "${units}/forge-nexus.service"
  grep -Fqx "WorkingDirectory=${ROOT_DIR}/services/forge-knowledge" "${units}/forge-knowledge.service"
  grep -Fqx "WorkingDirectory=${ROOT_DIR}/services/forge-jarvis" "${units}/forge-jarvis.service"
  grep -Fqx "ExecStart=${ROOT_DIR}/scripts/systemd/run-forge-agent.sh" "${units}/forge-agent.service"
  grep -Fqx "ExecStart=${ROOT_DIR}/scripts/systemd/run-forge-nexus.sh" "${units}/forge-nexus.service"
  grep -Fqx "ExecStart=${ROOT_DIR}/scripts/systemd/run-forge-knowledge.sh" "${units}/forge-knowledge.service"
  grep -Fqx "ExecStart=${ROOT_DIR}/scripts/systemd/run-forge-jarvis.sh" "${units}/forge-jarvis.service"

  grep -Fqx 'FORGE_AGENT_PORT="7091"' "${env_file}"
  grep -Fqx 'FORGE_NEXUS_BASE_URL="http://127.0.0.1:9099/fgaisox"' "${env_file}"
  grep -Fqx 'KNOWLEDGE_PORT="7081"' "${env_file}"
  grep -Fqx 'JARVIS_PORT="7071"' "${env_file}"
  grep -Fqx "WORKSPACE_ROOT=\"${dir}/workspace root\"" "${env_file}"
  ! grep -RE 'PIDFile|nohup' "${units}" >/dev/null
  ! grep -R 'FORGE_AGENT_DB_PASSWORD' "${units}" >/dev/null
}

case_systemd_launch_path_has_no_pid_file_or_nohup() {
  ! grep -RE 'PIDFile|nohup|forge_start_background|PID_FILE' "${ROOT_DIR}/scripts/systemd" "${ROOT_DIR}/config/systemd" >/dev/null
  ! grep -RE 'source .*forge-ai\.env|forge_systemd_load_env|\$\{1:-\}' "${ROOT_DIR}/scripts/systemd/run-forge-"*.sh >/dev/null
  grep -Fq 'exec java -jar "${FORGE_AI_HOME}/services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar"' "${ROOT_DIR}/scripts/systemd/run-forge-agent.sh"
  grep -Fq 'exec java -jar "${FORGE_AI_HOME}/services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar"' "${ROOT_DIR}/scripts/systemd/run-forge-nexus.sh"
  grep -Fq 'knowledge_service.main:app' "${ROOT_DIR}/scripts/systemd/run-forge-knowledge.sh"
  grep -Fq 'jarvis_agent.main:app' "${ROOT_DIR}/scripts/systemd/run-forge-jarvis.sh"
}

case_systemd_start_refuses_dev_owned_process() {
  local dir output pid
  dir="$(new_temp_dir)"
  mkdir -p "${dir}/var"
  sleep 60 &
  pid="$!"
  CHILD_PIDS+=("${pid}")
  printf '%s\n' "${pid}" > "${dir}/var/forge-agent.pid"
  if output="$(FORGE_AI_HOME="${dir}" "${ROOT_DIR}/scripts/runtime-ownership.sh" assert-dev-inactive 2>&1)"; then
    return 1
  fi
  [[ "${output}" == *"Forge development runtime is active"* ]]
  kill -0 "${pid}" >/dev/null 2>&1
}

case_dev_start_refuses_active_systemd_without_killing() {
  local dir bin output
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}"
  printf '%s\n' '#!/usr/bin/env bash' 'printf "active\\ninactive\\ninactive\\ninactive\\n"' > "${bin}/systemctl"
  chmod +x "${bin}/systemctl"
  if output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_AI_HOME="${dir}" "${ROOT_DIR}/scripts/runtime-ownership.sh" assert-systemd-inactive 2>&1)"; then
    return 1
  fi
  [[ "${output}" == *"Forge systemd runtime is active"* ]]
  ! grep -q 'kill' "${bin}/systemctl"
}

case_just_stop_refuses_systemd_state() {
  local state dir bin output pid
  for state in active activating; do
    dir="$(new_temp_dir)"
    bin="${dir}/bin"
    mkdir -p "${bin}"
    write_fake_systemctl "${bin}"
    sleep 60 &
    pid="$!"
    CHILD_PIDS+=("${pid}")
    if output="$(PATH="${bin}:${SYSTEM_PATH}" TEST_SYSTEMD_STATE="${state}" just --justfile "${ROOT_DIR}/Justfile" stop 2>&1)"; then
      return 1
    fi
    [[ "${output}" == *"Forge systemd runtime is active"* ]]
    kill -0 "${pid}" >/dev/null 2>&1
  done
}

case_service_dev_stop_preserves_systemd_main_pid() {
  local service pid_name dir bin pid output
  for service in knowledge jarvis; do
    dir="$(new_temp_dir)"
    bin="${dir}/bin"
    mkdir -p "${bin}" "${dir}/${service}"
    write_fake_systemctl "${bin}"
    sleep 60 &
    pid="$!"
    CHILD_PIDS+=("${pid}")
    if [[ "${service}" == "knowledge" ]]; then
      pid_name="knowledge-service.pid"
    else
      pid_name="jarvis-agent.pid"
    fi
    printf '%s\n' "${pid}" > "${dir}/${service}/${pid_name}"
    if output="$(PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}" TEST_SYSTEMD_MAIN_PID="${pid}" "${ROOT_DIR}/scripts/${service}/stop.sh" 2>&1)"; then
      return 1
    fi
    [[ "${output}" == *"systemd-owned Forge PID ${pid}"* ]]
    kill -0 "${pid}" >/dev/null 2>&1
  done
}

case_java_port_fallback_preserves_systemd_main_pid() {
  local port dir bin pid output
  for port in 9099 7091; do
    dir="$(new_temp_dir)"
    bin="${dir}/bin"
    mkdir -p "${bin}"
    write_fake_systemctl "${bin}"
    write_fake_lsof_for_port "${bin}"
    sleep 60 &
    pid="$!"
    CHILD_PIDS+=("${pid}")
    if output="$(PATH="${bin}:${SYSTEM_PATH}" TEST_LISTENER_PORT="${port}" TEST_SYSTEMD_MAIN_PID="${pid}" just --justfile "${ROOT_DIR}/Justfile" _app-stop 2>&1)"; then
      return 1
    fi
    [[ "${output}" == *"systemd-owned Forge PID ${pid}"* ]]
    kill -0 "${pid}" >/dev/null 2>&1
  done
}

case_normal_dev_owned_process_is_stoppable() {
  local dir bin pid
  dir="$(new_temp_dir)"
  bin="${dir}/bin"
  mkdir -p "${bin}" "${dir}/knowledge"
  write_fake_systemctl "${bin}"
  sleep 60 &
  pid="$!"
  CHILD_PIDS+=("${pid}")
  printf '%s\n' "${pid}" > "${dir}/knowledge/knowledge-service.pid"
  PATH="${bin}:${SYSTEM_PATH}" FORGE_RUNTIME_DIR="${dir}" TEST_SYSTEMD_MAIN_PID=0 "${ROOT_DIR}/scripts/knowledge/stop.sh"
  ! kill -0 "${pid}" >/dev/null 2>&1
}

case_systemd_stop_uses_only_systemctl() {
  awk '/^  stop\)/,/^    ;;/ { print }' "${ROOT_DIR}/scripts/systemd/control.sh" | grep -Fq 'systemctl stop "${REVERSE_UNITS[@]}"'
  ! awk '/^  stop\)/,/^    ;;/ { print }' "${ROOT_DIR}/scripts/systemd/control.sh" | grep -Eq 'kill|_app-stop|knowledge/stop|jarvis/stop'
}

case_systemd_unit_templates_have_no_committed_secrets_or_postgres_unit() {
  ! grep -R 'FORGE_AGENT_DB_PASSWORD=' "${ROOT_DIR}/config/systemd" >/dev/null
  ! grep -R 'forge-agent-postgres.service' "${ROOT_DIR}/config/systemd" >/dev/null
  ! grep -R 'postgres.service' "${ROOT_DIR}/config/systemd" >/dev/null
}

case_dev_launcher_and_systemd_workflows_are_separate() {
  grep -Fqx 'start service="all":' "${ROOT_DIR}/Justfile"
  grep -Fqx 'systemd-start:' "${ROOT_DIR}/Justfile"
  grep -Fqx 'systemd-stop:' "${ROOT_DIR}/Justfile"
  grep -Fqx 'systemd-restart:' "${ROOT_DIR}/Justfile"
  grep -Fq 'forge_start_background' "${ROOT_DIR}/scripts/lib/process.sh"
  ! awk '/^start service="all":/{flag=1; next} /^_start-all:/{flag=0} flag {print}' "${ROOT_DIR}/Justfile" | grep -q 'systemctl'
}

run_case "ollama API already reachable" case_ollama_api_already_reachable
run_case "ollama API unavailable and CLI absent" case_ollama_cli_absent
run_case "ollama CLI start succeeds" case_ollama_start_succeeds
run_case "ollama CLI start fails" case_ollama_start_fails
run_case "ollama readiness times out" case_ollama_readiness_timeout
run_case "external Ollama is not marked Forge-owned" case_external_ollama_not_marked_owned
run_case "Forge-owned Ollama PID is stopped" case_forge_owned_ollama_stopped
run_case "external Ollama is not stopped" case_external_ollama_not_stopped
run_case "stale Ollama PID file cleanup" case_stale_ollama_pid_cleanup
run_case "setsid absent" case_setsid_absent
run_case "sha256sum absent with shasum fallback" case_sha256sum_absent_shasum_fallback
run_case "GNU stat absent with BSD stat fallback" case_gnu_stat_absent_bsd_fallback
run_case "Jarvis startup has no mandatory Ollama gate" case_jarvis_start_has_no_ollama_gate
run_case "Console build installs dependencies when Vite is missing" case_console_build_installs_dependencies_when_vite_missing
run_case "Console build skips dependency install when Vite exists" case_console_build_skips_install_when_vite_exists
run_case "systemd units render stable Forge services" case_systemd_units_render_stable_services
run_case "systemd launch path has no PID file or nohup" case_systemd_launch_path_has_no_pid_file_or_nohup
run_case "systemd unit templates have no committed secrets or Postgres unit" case_systemd_unit_templates_have_no_committed_secrets_or_postgres_unit
run_case "dev launcher and systemd workflows are separate" case_dev_launcher_and_systemd_workflows_are_separate
run_case "systemd start refuses a dev-owned process without killing it" case_systemd_start_refuses_dev_owned_process
run_case "dev start refuses active systemd without killing it" case_dev_start_refuses_active_systemd_without_killing
run_case "just stop refuses active and activating systemd without killing" case_just_stop_refuses_systemd_state
run_case "Knowledge and Jarvis dev stop preserve systemd MainPID" case_service_dev_stop_preserves_systemd_main_pid
run_case "Agent and Nexus port fallback preserve systemd MainPID" case_java_port_fallback_preserves_systemd_main_pid
run_case "normal dev-owned process remains stoppable" case_normal_dev_owned_process_is_stoppable
run_case "systemd stop uses only systemctl" case_systemd_stop_uses_only_systemctl

printf 'Portable startup shell tests: PASS %s/%s\n' "${PASSED}" "${TOTAL}"
