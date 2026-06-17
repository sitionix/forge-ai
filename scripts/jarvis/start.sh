#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
exec "${FORGE_AI_DIR}/infrastructure/jarvis/scripts/start.sh" "$@"
