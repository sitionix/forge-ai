#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
for command in curl docker java mvn npm python3; do
  command -v "${command}" >/dev/null 2>&1 || { echo "Required command is missing: ${command}" >&2; exit 1; }
done
docker compose version >/dev/null 2>&1 || { echo "Docker Compose is required." >&2; exit 1; }
cd "${ROOT}"
mvn -pl services/forge-agent/boot -am -DskipTests package
scripts/console/build.sh
mvn -pl services/forge-nexus/boot -am -DskipTests package
[[ -x services/forge-knowledge/.venv/bin/python3 ]] || scripts/knowledge/bootstrap.sh
[[ -x services/forge-jarvis/.venv/bin/python3 ]] || scripts/jarvis/bootstrap.sh
