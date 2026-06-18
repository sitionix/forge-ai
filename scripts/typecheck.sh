#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

run_mypy() {
  local service_dir="$1"
  local python="${service_dir}/.venv/bin/python3"
  if [[ ! -x "${python}" ]]; then
    python="python3"
  fi

  (
    cd "${service_dir}"
    "${python}" -m mypy src
  )
}

run_mypy "${ROOT_DIR}/services/forge-knowledge"
run_mypy "${ROOT_DIR}/services/forge-jarvis"

(
  cd "${ROOT_DIR}/services/forge-console"
  npm run typecheck
)
