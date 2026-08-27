#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "${SCRIPT_DIR}/env.sh"
forge_systemd_load_env "${1:-}"

exec java -jar "${FORGE_AI_HOME}/services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar" \
  --spring.docker.compose.enabled=false
