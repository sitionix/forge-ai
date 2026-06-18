#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

"${ROOT_DIR}/scripts/test-python.sh"
"${ROOT_DIR}/scripts/test-console.sh"

(
  cd "${ROOT_DIR}"
  mvn -q -DskipTests compile
)
