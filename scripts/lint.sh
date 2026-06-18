#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

run_ruff() {
  local service_dir="$1"
  local python="${service_dir}/.venv/bin/python3"
  if [[ ! -x "${python}" ]]; then
    python="python3"
  fi

  (
    cd "${service_dir}"
    "${python}" -m ruff check .
    "${python}" -m ruff format --check .
  )
}

run_ruff "${ROOT_DIR}/services/forge-knowledge"
run_ruff "${ROOT_DIR}/services/forge-jarvis"
