#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
source "${FORGE_AI_HOME}/scripts/lib/forge-env.sh"

KNOWLEDGE_ROOT="${FORGE_AI_HOME}/services/forge-knowledge"
PYTHON="${KNOWLEDGE_ROOT}/.venv/bin/python"
if [[ ! -x "${PYTHON}" ]]; then
  PYTHON="python3"
fi

PYTHONPATH="${KNOWLEDGE_ROOT}/src${PYTHONPATH:+:${PYTHONPATH}}" KNOWLEDGE_MODULE_DIR="${KNOWLEDGE_ROOT}" "${PYTHON}" - <<'PY'
from knowledge_service.config import load_app_config
from knowledge_service.source_config import require_source_config
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider

app_config = load_app_config()
config = require_source_config(app_config.local_config_path)
result = ServiceYamlCatalogProvider(config).load()
print(f"Knowledge config valid. config={app_config.local_config_path} sources={len(result.sources)} diagnostics={len(result.diagnostics)}")
PY
