#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
OS="${FORGE_RUNTIME_OS:-$(uname -s)}"
case "${OS}" in
  Darwin) printf 'MANAGED_LOCAL_SESSION\n' ;;
  Linux)
    linux_id="${FORGE_RUNTIME_LINUX_ID:-}"
    if [[ -z "${linux_id}" && -r /etc/os-release ]]; then
      source /etc/os-release
      linux_id="${ID:-}"
    fi
    [[ "${linux_id}" == "ubuntu" ]] || { echo "Unsupported Linux runtime '${linux_id:-unknown}'. Forge supports Ubuntu systemd and macOS." >&2; exit 1; }
    "${SCRIPT_DIR}/systemd.sh" validate
    printf 'SYSTEMD\n'
    ;;
  *) echo "Unsupported Forge runtime platform: ${OS}" >&2; exit 1 ;;
esac
