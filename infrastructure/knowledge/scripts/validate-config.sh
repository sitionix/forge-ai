#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
CONFIG_FILE="${MODULE_DIR}/config/knowledge-sources.yaml"
SERVICE_DIR="${MODULE_DIR}/services/knowledge-service"

if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "No local knowledge-sources.yaml configured yet."
  exit 0
fi

PYTHON="${SERVICE_DIR}/.venv/bin/python"
if [[ ! -x "${PYTHON}" ]]; then
  PYTHON="python3"
fi

KNOWLEDGE_MODULE_DIR="${MODULE_DIR}" "${PYTHON}" - <<'PY'
from knowledge_service.config import load_app_config
from knowledge_service.source_config import require_source_config
from knowledge_service.service_catalog_provider import ServiceYamlCatalogProvider

config = require_source_config(load_app_config().local_config_path)
result = ServiceYamlCatalogProvider(config).load()
print(f"Knowledge config valid. sources={len(result.sources)} diagnostics={len(result.diagnostics)}")
PY
