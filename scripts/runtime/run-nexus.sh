#!/usr/bin/env bash
set -euo pipefail
: "${FORGE_AI_HOME:?FORGE_AI_HOME is required}"
# shellcheck source=../lib/portable.sh
source "${FORGE_AI_HOME}/scripts/lib/portable.sh"
JAVA_COMMAND="$(forge_java_command)"
exec env WORKSPACE_ROOT="${WORKSPACE_ROOT:-$(cd -- "${FORGE_AI_HOME}/.." && pwd)}" "${JAVA_COMMAND}" -jar "${FORGE_AI_HOME}/services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar" --spring.docker.compose.enabled=false
