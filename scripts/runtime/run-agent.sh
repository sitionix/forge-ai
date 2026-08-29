#!/usr/bin/env bash
set -euo pipefail
: "${FORGE_AI_HOME:?FORGE_AI_HOME is required}"
exec env FORGE_AGENT_DB_URL="${FORGE_AGENT_DB_URL:-jdbc:postgresql://localhost:54329/forge_agent}" FORGE_AGENT_DB_USERNAME="${FORGE_AGENT_DB_USERNAME:-forge_agent}" FORGE_AGENT_DB_PASSWORD="${FORGE_AGENT_DB_PASSWORD:-forge_agent}" FORGE_AGENT_PORT="7091" java -jar "${FORGE_AI_HOME}/services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar" --spring.docker.compose.enabled=false
