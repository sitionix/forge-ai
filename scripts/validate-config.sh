#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/forge-env.sh
source "${SCRIPT_DIR}/lib/forge-env.sh"

failures=0

require_file() {
  local path="$1"
  if [[ -f "${path}" ]]; then
    echo "OK: ${path}"
  else
    echo "FAIL: missing ${path}"
    failures=$((failures + 1))
  fi
}

require_dir() {
  local path="$1"
  if [[ -d "${path}" ]]; then
    echo "OK: ${path}"
  else
    echo "FAIL: missing directory ${path}"
    failures=$((failures + 1))
  fi
}

require_local_http_url() {
  local label="$1"
  local url="$2"
  if [[ "${url}" =~ ^http://(127\.0\.0\.1|localhost)(:[0-9]+)?(/.*)?$ ]]; then
    echo "OK: ${label} ${url}"
  else
    echo "FAIL: ${label} must be a localhost http URL: ${url}"
    failures=$((failures + 1))
  fi
}

require_file "${FORGE_CONFIG_DIR}/forge-ai.yaml"
require_file "${FORGE_CONFIG_DIR}/services.yaml"
require_file "${FORGE_CONFIG_DIR}/agent.yml"
require_file "${FORGE_CONFIG_DIR}/lane-strategies.yml"
require_file "${FORGE_CONFIG_DIR}/instructions.yaml"
require_file "${FORGE_CONFIG_DIR}/knowledge/knowledge.defaults.yaml"
require_file "${FORGE_CONFIG_DIR}/knowledge/knowledge-sources.yaml"
require_file "${FORGE_CONFIG_DIR}/knowledge/analysis-prompt.md"
require_file "${FORGE_CONFIG_DIR}/jarvis/model.yaml"
require_file "${FORGE_CONFIG_DIR}/jarvis/allowed-actions.yaml"
require_file "${FORGE_CONFIG_DIR}/jarvis/system-prompt.md"
require_file "${FORGE_CONFIG_DIR}/jarvis/chat-prompt.md"
require_dir "${FORGE_WORKSPACE_ROOT}"

require_local_http_url "Forge Nexus" "${FORGE_NEXUS_BASE_URL}"
require_local_http_url "Forge Knowledge" "${FORGE_KNOWLEDGE_BASE_URL}"
require_local_http_url "Forge Jarvis" "${FORGE_JARVIS_BASE_URL}"

if [[ -f "${FORGE_CONFIG_DIR}/jarvis/model.yaml" ]]; then
  OLLAMA_URL="$(sed -n 's/^[[:space:]]*ollama_base_url:[[:space:]]*//p' "${FORGE_CONFIG_DIR}/jarvis/model.yaml" | head -n 1)"
else
  OLLAMA_URL=""
fi
require_local_http_url "Ollama" "${OLLAMA_URL:-http://localhost:11434}"

if [[ ${failures} -ne 0 ]]; then
  echo "Forge AI config validation failed with ${failures} issue(s)."
  exit 1
fi

"${SCRIPT_DIR}/knowledge/validate-config.sh"
echo "Forge AI config validation passed."
