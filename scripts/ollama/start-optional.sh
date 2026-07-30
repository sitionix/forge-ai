#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/forge-env.sh
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"
# shellcheck source=../lib/process.sh
source "${FORGE_AI_HOME}/scripts/lib/process.sh"

PID_FILE="${FORGE_RUNTIME_DIR}/ollama/ollama.pid"
LOG_FILE="${FORGE_RUNTIME_DIR}/ollama/logs/ollama.log"
OWNER="forge-ai-ollama"
READINESS_SECONDS="${FORGE_OLLAMA_OPTIONAL_READINESS_SECONDS:-6}"

resolve_ollama_base_url() {
  if [[ -n "${1:-}" ]]; then
    printf '%s' "${1%/}"
    return 0
  fi

  local jarvis_root="${FORGE_AI_HOME}/services/forge-jarvis"
  local python="${jarvis_root}/.venv/bin/python3"
  local resolved
  if [[ -x "${python}" ]]; then
    if resolved="$(PYTHONPATH="${jarvis_root}/src${PYTHONPATH:+:${PYTHONPATH}}" "${python}" - <<'PY' 2>/dev/null
from jarvis_agent.config import load_forge_settings

settings = load_forge_settings()
print(str(settings.generative.base_url).rstrip("/"))
PY
    )"; then
      if [[ -n "${resolved}" ]]; then
        printf '%s' "${resolved%/}"
        return 0
      fi
    fi
  fi

  local config_file="${FORGE_CONFIG_FILE:-${FORGE_CONFIG_DIR}/forge-ai.yaml}"
  if [[ -r "${config_file}" ]]; then
    resolved="$(
      awk '
        /^[[:space:]]*generative:[[:space:]]*$/ {
          in_generative = 1
          generative_indent = match($0, /[^[:space:]]/) - 1
          next
        }
        in_generative {
          indent = match($0, /[^[:space:]]/) - 1
          if (indent <= generative_indent) {
            in_generative = 0
          } else if ($0 ~ /^[[:space:]]*base-url:[[:space:]]*/) {
            sub(/^[[:space:]]*base-url:[[:space:]]*/, "", $0)
            gsub(/^["'\''"]|["'\''"]$/, "", $0)
            print $0
            exit
          }
        }
      ' "${config_file}"
    )"
    if [[ -n "${resolved}" ]]; then
      printf '%s' "${resolved%/}"
      return 0
    fi
  fi

  printf '%s' "http://localhost:11434"
}

ollama_api_reachable() {
  local base_url="$1"
  curl --max-time 2 -fsS "${base_url%/}/api/version" >/dev/null 2>&1
}

owned_pid_from_file() {
  [[ -f "${PID_FILE}" ]] || return 1
  local pid owner
  pid="$(awk -F= '$1 == "PID" { print $2; exit }' "${PID_FILE}" 2>/dev/null || true)"
  owner="$(awk -F= '$1 == "OWNER" { print $2; exit }' "${PID_FILE}" 2>/dev/null || true)"
  [[ "${owner}" == "${OWNER}" && "${pid}" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "${pid}"
}

cleanup_stale_owned_pid() {
  [[ -f "${PID_FILE}" ]] || return 0

  local pid
  if ! pid="$(owned_pid_from_file)"; then
    echo "Removing invalid Forge-owned Ollama PID file: ${PID_FILE}"
    rm -f "${PID_FILE}"
    return 0
  fi

  if ! forge_pid_is_running "${pid}"; then
    echo "Removing stale Forge-owned Ollama PID file: ${PID_FILE}"
    rm -f "${PID_FILE}"
    return 0
  fi

  local command_line
  command_line="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
  if [[ "${command_line}" != *"ollama"* || "${command_line}" != *"serve"* ]]; then
    echo "Removing stale Forge-owned Ollama PID file for non-Ollama pid ${pid}"
    rm -f "${PID_FILE}"
  fi
}

wait_for_ollama() {
  local base_url="$1"
  local pid="$2"
  local waited=0

  while (( waited < READINESS_SECONDS )); do
    if ollama_api_reachable "${base_url}"; then
      return 0
    fi
    if ! forge_pid_is_running "${pid}"; then
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

BASE_URL="$(resolve_ollama_base_url "${1:-}")"
mkdir -p "$(dirname -- "${PID_FILE}")" "$(dirname -- "${LOG_FILE}")"

if ollama_api_reachable "${BASE_URL}"; then
  echo "Ollama API is available at ${BASE_URL}; using existing runtime."
  rm -f "${PID_FILE}"
  exit 0
fi

cleanup_stale_owned_pid
if pid="$(owned_pid_from_file)"; then
  if forge_pid_is_running "${pid}"; then
    echo "WARNING: Forge-owned Ollama process ${pid} is running but API is not reachable; continuing without Ollama"
    exit 0
  fi
fi

if ! command -v ollama >/dev/null 2>&1; then
  echo "WARNING: Ollama is not installed or not on PATH; continuing without Ollama"
  exit 0
fi

echo "Ollama API is not reachable at ${BASE_URL}; attempting optional 'ollama serve' startup."
: >> "${LOG_FILE}"
(
  cd "${FORGE_AI_HOME}"
  set -m
  nohup ollama serve </dev/null >> "${LOG_FILE}" 2>&1 &
  forge_write_owned_pid_file "${PID_FILE}" "$!" "${OWNER}" "ollama serve"
)

PID="$(owned_pid_from_file || true)"
if [[ -z "${PID}" ]]; then
  echo "WARNING: Ollama could not be started; continuing without Ollama"
  exit 0
fi

if wait_for_ollama "${BASE_URL}" "${PID}"; then
  echo "Ollama started and API became reachable at ${BASE_URL}"
  exit 0
fi

if ! forge_pid_is_running "${PID}"; then
  rm -f "${PID_FILE}"
fi
echo "WARNING: Ollama could not be started; continuing without Ollama"
exit 0
