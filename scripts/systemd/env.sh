#!/usr/bin/env bash
set -euo pipefail

forge_systemd_load_env() {
  local env_file="${1:-}"
  if [[ -n "${env_file}" && -f "${env_file}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${env_file}"
    set +a
  fi
  if [[ -z "${FORGE_AI_HOME:-}" ]]; then
    echo "FORGE_AI_HOME is required." >&2
    exit 1
  fi
}
