#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"${ROOT}/scripts/test-graph-backend.sh"
"${ROOT}/scripts/test-graph-console.sh"
