#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${ROOT}/services/forge-knowledge"
PYTHONPATH=tests:src python3 -m pytest \
  tests/forge_it/test_graph_snapshot_api.py \
  tests/forge_it/test_knowledge_service_it.py \
  -q

cd "${ROOT}"
mvn -pl services/forge-nexus/application,services/forge-nexus/infrastructure/knowledge-client,services/forge-nexus/infrastructure/knowledge-sqlite,services/forge-nexus/api-rest -am test
