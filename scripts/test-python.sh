#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

run_pytest() {
  local service_dir="$1"
  local python="${service_dir}/.venv/bin/python3"
  if [[ ! -x "${python}" ]]; then
    python="python3"
  fi

  (
    cd "${service_dir}"
    PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 "${python}" -m pytest
  )
}

run_pytest "${ROOT_DIR}/services/forge-knowledge"
run_pytest "${ROOT_DIR}/services/forge-jarvis"
