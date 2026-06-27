#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

CONFIG_FILE="${FORGE_CONFIG_DIR}/knowledge/knowledge-sources.yaml"
FORCE=false

if [[ "${1:-}" == "--force" ]]; then
  FORCE=true
fi

mkdir -p "${FORGE_CONFIG_DIR}/knowledge"

if [[ -f "${CONFIG_FILE}" && "${FORCE}" != "true" ]]; then
  echo "Local config already exists: ${CONFIG_FILE}"
  echo "Use --force to overwrite."
  exit 0
fi

cat > "${CONFIG_FILE}" <<YAML
catalog:
  type: service_catalog
  path: "\${FORGE_CONFIG_DIR}/services.yaml"
  workspace_root: "\${FORGE_WORKSPACE_ROOT}"

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
YAML

echo "Generated local Knowledge config: ${CONFIG_FILE}"
echo "Default FORGE_CONFIG_DIR=${FORGE_CONFIG_DIR}"
echo "Default FORGE_WORKSPACE_ROOT=${FORGE_WORKSPACE_ROOT}"
