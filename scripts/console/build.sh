#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

CONSOLE_ROOT="${FORGE_CONSOLE_ROOT:-${FORGE_AI_HOME}/services/forge-console}"
VITE_BIN="${CONSOLE_ROOT}/node_modules/.bin/vite"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required to build Forge Console static assets." >&2
  exit 1
fi

echo "Building Forge Console static assets..."
if [[ ! -x "${VITE_BIN}" ]]; then
  echo "Installing Forge Console dependencies..."
  npm --prefix "${CONSOLE_ROOT}" ci
fi

npm --prefix "${CONSOLE_ROOT}" run build
