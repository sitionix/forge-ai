#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT}/var/reports"
REPORT="${REPORT_DIR}/graph-benchmark-$(date +%Y%m%d-%H%M%S).md"
mkdir -p "${REPORT_DIR}"

{
  echo "# Graph Benchmark"
  echo
  echo "- generatedAt: $(date --iso-8601=seconds)"
  echo
  echo "## Backend Snapshot Smoke"
} > "${REPORT}"

cd "${ROOT}/services/forge-knowledge"
if PYTHONPATH=tests:src python3 -m pytest tests/forge_it/test_graph_snapshot_api.py -q >> "${REPORT}" 2>&1; then
  echo "- backend: pass" >> "${REPORT}"
else
  echo "- backend: fail" >> "${REPORT}"
fi

cd "${ROOT}/services/forge-console"
{
  echo
  echo "## Console Build Smoke"
} >> "${REPORT}"
if npm run build >> "${REPORT}" 2>&1; then
  echo "- console build: pass" >> "${REPORT}"
else
  echo "- console build: fail" >> "${REPORT}"
fi

echo "Graph benchmark report: ${REPORT}"
