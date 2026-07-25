#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/forge-env.sh
source "${SCRIPT_DIR}/lib/forge-env.sh"

"${SCRIPT_DIR}/knowledge/bootstrap.sh"
"${SCRIPT_DIR}/jarvis/bootstrap.sh"

echo "Forge AI bootstrap complete."
echo "FORGE_AI_HOME=${FORGE_AI_HOME}"
echo "FORGE_CONFIG_DIR=${FORGE_CONFIG_DIR}"
echo "FORGE_RUNTIME_DIR=${FORGE_RUNTIME_DIR}"
echo "FORGE_WORKSPACE_ROOT=${FORGE_WORKSPACE_ROOT}"
