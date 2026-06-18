#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/forge-env.sh
source "${SCRIPT_DIR}/lib/forge-env.sh"

if command -v just >/dev/null 2>&1; then
  (cd "${FORGE_AI_HOME}" && just start)
else
  "${SCRIPT_DIR}/knowledge/start.sh"
  "${SCRIPT_DIR}/jarvis/start.sh"
  echo "Forge Nexus was not started because 'just' is not available."
  echo "Install just or start the Java app with: mvn -pl services/forge-nexus/boot -am -DskipTests package"
  exit 1
fi
