#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/forge-env.sh
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

OUTPUT_DIR="${1:?Usage: render-units.sh OUTPUT_DIR [ENV_FILE]}"
ENV_FILE="${2:-${OUTPUT_DIR}/forge-ai.env}"
TEMPLATE_DIR="${FORGE_AI_HOME}/config/systemd"
SYSTEMD_USER="${FORGE_SYSTEMD_USER:-$(id -un)}"
SYSTEMD_GROUP="${FORGE_SYSTEMD_GROUP:-$(id -gn)}"

mkdir -p "${OUTPUT_DIR}" "$(dirname -- "${ENV_FILE}")"

render_template() {
  local template="$1"
  local target="$2"
  local content
  content="$(< "${template}")"
  content="${content//@FORGE_AI_HOME@/${FORGE_AI_HOME}}"
  content="${content//@FORGE_SYSTEMD_ENV_FILE@/${ENV_FILE}}"
  content="${content//@FORGE_SYSTEMD_USER@/${SYSTEMD_USER}}"
  content="${content//@FORGE_SYSTEMD_GROUP@/${SYSTEMD_GROUP}}"
  printf '%s\n' "${content}" > "${target}"
}

env_line() {
  local key="$1"
  local value="$2"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s="%s"\n' "${key}" "${value}"
}

render_template "${TEMPLATE_DIR}/forge-agent.service.in" "${OUTPUT_DIR}/forge-agent.service"
render_template "${TEMPLATE_DIR}/forge-nexus.service.in" "${OUTPUT_DIR}/forge-nexus.service"
render_template "${TEMPLATE_DIR}/forge-knowledge.service.in" "${OUTPUT_DIR}/forge-knowledge.service"
render_template "${TEMPLATE_DIR}/forge-jarvis.service.in" "${OUTPUT_DIR}/forge-jarvis.service"

{
  env_line "FORGE_AI_HOME" "${FORGE_AI_HOME}"
  env_line "FORGE_CONFIG_DIR" "${FORGE_CONFIG_DIR}"
  env_line "FORGE_RUNTIME_DIR" "${FORGE_RUNTIME_DIR}"
  env_line "FORGE_WORKSPACE_ROOT" "${FORGE_WORKSPACE_ROOT}"
  env_line "WORKSPACE_ROOT" "${WORKSPACE_ROOT}"
  env_line "FORGE_AGENT_BASE_URL" "http://127.0.0.1:7091"
  env_line "FORGE_AGENT_DB_URL" "${FORGE_AGENT_DB_URL:-jdbc:postgresql://localhost:54329/forge_agent}"
  env_line "FORGE_AGENT_DB_USERNAME" "${FORGE_AGENT_DB_USERNAME:-forge_agent}"
  env_line "FORGE_AGENT_DB_PASSWORD" "${FORGE_AGENT_DB_PASSWORD:-forge_agent}"
  env_line "FORGE_AGENT_PORT" "7091"
  env_line "FORGE_NEXUS_BASE_URL" "http://127.0.0.1:9099/fgaisox"
  env_line "FORGE_KNOWLEDGE_BASE_URL" "http://127.0.0.1:7081"
  env_line "FORGE_JARVIS_BASE_URL" "http://127.0.0.1:7071"
  env_line "KNOWLEDGE_HOST" "${KNOWLEDGE_HOST:-127.0.0.1}"
  env_line "KNOWLEDGE_PORT" "7081"
  env_line "JARVIS_CONFIG_DIR" "${JARVIS_CONFIG_DIR:-${FORGE_CONFIG_DIR}/jarvis}"
  env_line "JARVIS_LOG_FILE" "${JARVIS_LOG_FILE:-${FORGE_RUNTIME_DIR}/jarvis/logs/jarvis-agent.log}"
  env_line "JARVIS_HOST" "${JARVIS_HOST:-127.0.0.1}"
  env_line "JARVIS_PORT" "7071"
} > "${ENV_FILE}"

chmod 0600 "${ENV_FILE}"
printf 'Rendered Forge systemd units in %s\n' "${OUTPUT_DIR}"
printf 'Rendered Forge systemd environment in %s\n' "${ENV_FILE}"
