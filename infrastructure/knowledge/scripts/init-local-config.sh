#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
FORGE_ROOT="$(cd -- "${MODULE_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd -- "${FORGE_ROOT}/.." && pwd)"
CONFIG_FILE="${MODULE_DIR}/config/knowledge-sources.yaml"
FORCE=false

if [[ "${1:-}" == "--force" ]]; then
  FORCE=true
fi

if [[ -f "${CONFIG_FILE}" && "${FORCE}" != "true" ]]; then
  echo "Local config already exists: ${CONFIG_FILE}"
  echo "Use --force to overwrite."
  exit 0
fi

cat > "${CONFIG_FILE}" <<YAML
catalog:
  type: service_catalog
  path: "${FORGE_ROOT}/boot/src/main/resources/services.yaml"
  workspace_root: "${WORKSPACE_ROOT}"

selection:
  include_groups: []
  include_services: []
  exclude_services: []

indexing:
  include:
    - "**/*.java"
    - "**/*.kt"
    - "**/*.ts"
    - "**/*.tsx"
    - "**/*.js"
    - "**/*.md"
    - "**/*.yaml"
    - "**/*.yml"
    - "**/*.json"
    - "**/*.xml"
    - "**/pom.xml"
    - "**/README*"
  exclude:
    - ".git/**"
    - "target/**"
    - "build/**"
    - "dist/**"
    - "node_modules/**"
    - ".venv/**"
    - "var/**"
    - "logs/**"
    - "**/.env"
    - "**/*.class"
    - "**/*.jar"
YAML

echo "Generated local Knowledge config: ${CONFIG_FILE}"
echo "This file is local runtime config and is gitignored."
