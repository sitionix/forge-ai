#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

UNIT_DIR="${FORGE_SYSTEMD_UNIT_DIR:-/etc/systemd/system}"
ENV_DIR="${FORGE_SYSTEMD_ENV_DIR:-/etc/forge-ai}"
ENV_FILE="${FORGE_SYSTEMD_ENV_FILE:-${ENV_DIR}/forge-ai.env}"
USE_SUDO="${FORGE_SYSTEMD_USE_SUDO:-auto}"
SKIP_RELOAD="${FORGE_SYSTEMD_SKIP_RELOAD:-0}"
UNITS=(forge-agent.service forge-nexus.service forge-knowledge.service forge-jarvis.service)

if [[ "${SKIP_RELOAD}" != "1" ]] && ! command -v systemctl >/dev/null 2>&1; then
  echo "systemctl is required to install Forge systemd units on this host." >&2
  exit 1
fi

sudo_cmd=()
if [[ "${USE_SUDO}" == "1" || ( "${USE_SUDO}" == "auto" && "${EUID}" -ne 0 ) ]]; then
  sudo_cmd=(sudo)
fi

run_privileged() {
  if (( ${#sudo_cmd[@]} > 0 )); then
    "${sudo_cmd[@]}" "$@"
  else
    "$@"
  fi
}

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

"${SCRIPT_DIR}/render-units.sh" "${tmp_dir}/units" "${tmp_dir}/forge-ai.env" "${ENV_FILE}"

run_privileged install -d -m 0755 "${UNIT_DIR}" "${ENV_DIR}"
run_privileged install -m 0600 "${tmp_dir}/forge-ai.env" "${ENV_FILE}"
for unit in "${UNITS[@]}"; do
  run_privileged install -m 0644 "${tmp_dir}/units/${unit}" "${UNIT_DIR}/${unit}"
done

if [[ "${SKIP_RELOAD}" != "1" ]]; then
  run_privileged systemctl daemon-reload
fi

printf 'Installed Forge systemd units to %s\n' "${UNIT_DIR}"
printf 'Installed Forge systemd environment to %s\n' "${ENV_FILE}"
printf 'Start with: just start\n'
