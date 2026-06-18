#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/forge-env.sh
source "${SCRIPT_DIR}/lib/forge-env.sh"

if curl -fsS "${FORGE_NEXUS_BASE_URL}/actuator/health" >/dev/null 2>&1; then
  echo "Forge Nexus: UP at ${FORGE_NEXUS_BASE_URL}"
else
  echo "Forge Nexus: DOWN at ${FORGE_NEXUS_BASE_URL}"
fi

"${SCRIPT_DIR}/knowledge/status.sh"
"${SCRIPT_DIR}/jarvis/status.sh"
