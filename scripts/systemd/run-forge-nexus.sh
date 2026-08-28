#!/usr/bin/env bash
set -euo pipefail

: "${FORGE_AI_HOME:?FORGE_AI_HOME is required}"

exec java -jar "${FORGE_AI_HOME}/services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar" \
  --spring.docker.compose.enabled=false
