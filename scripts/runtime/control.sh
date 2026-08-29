#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ACTION="${1:?Usage: control.sh start|stop|restart|status|logs|attach [service]}"
SERVICE="${2:-all}"
BACKEND="$("${SCRIPT_DIR}/resolve-backend.sh")"
case "${BACKEND}" in
  SYSTEMD) exec "${SCRIPT_DIR}/systemd.sh" "${ACTION}" "${SERVICE}" ;;
  MANAGED_LOCAL_SESSION) exec "${SCRIPT_DIR}/managed-session.sh" "${ACTION}" "${SERVICE}" ;;
  *) echo "Internal error: unknown runtime backend ${BACKEND}" >&2; exit 2 ;;
esac
