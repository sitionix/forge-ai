#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
USE_SUDO="${FORGE_SYSTEMD_USE_SUDO:-auto}"
if [[ "${USE_SUDO}" == "0" ]]; then
  PACKAGE_DIR="${FORGE_REMOTE_ACCESS_PACKAGE_DIR:-/usr/local/lib/forge-remote-setup}"
  JAR_DIR="${FORGE_REMOTE_ACCESS_JAR_DIR:-/usr/local/lib/forge-remote}"
  AGENT_JAR="${FORGE_REMOTE_ACCESS_AGENT_JAR_SOURCE:-${ROOT}/services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar}"
else
  PACKAGE_DIR=/usr/local/lib/forge-remote-setup
  JAR_DIR=/usr/local/lib/forge-remote
  AGENT_JAR="${FORGE_REMOTE_ACCESS_AGENT_JAR_SOURCE:-${ROOT}/services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar}"
fi

[[ -f "${AGENT_JAR}" && ! -L "${AGENT_JAR}" ]] || { echo 'REMOTE_ACCESS_AGENT_JAR_NOT_READY' >&2; exit 1; }
[[ ! -L "${PACKAGE_DIR}" && ! -L "${JAR_DIR}" ]] || { echo 'REMOTE_ACCESS_PACKAGE_CONFLICT' >&2; exit 1; }

sudo_cmd=()
if [[ "${USE_SUDO}" == "1" || ( "${USE_SUDO}" == "auto" && "${EUID}" -ne 0 ) ]]; then sudo_cmd=(sudo); fi
privileged() { if (( ${#sudo_cmd[@]} )); then "${sudo_cmd[@]}" "$@"; else "$@"; fi; }

if systemctl is-active --quiet forge-remote-agent.service 2>/dev/null; then
  if [[ ! -f "${JAR_DIR}/forge-agent.jar" ]] || ! cmp -s "${AGENT_JAR}" "${JAR_DIR}/forge-agent.jar"; then
    echo 'REMOTE_ACCESS_UPGRADE_REQUIRES_DRAIN' >&2
    exit 1
  fi
fi

privileged install -d -m 0755 "${PACKAGE_DIR}" "${JAR_DIR}"
for name in install.py prepare_startup.py prepare_management.py prepare_local_exec.py \
            prepare_rootfs.py rootfs.Dockerfile \
            forced_command.py invitation_supervisor.py workload_supervisor.py \
            workload_units.py execution_channel.py prepare_workspace.py forge-remote; do
  [[ ! -L "${PACKAGE_DIR}/${name}" ]] || { echo 'REMOTE_ACCESS_PACKAGE_CONFLICT' >&2; exit 1; }
  mode=0755
  [[ "${name}" == rootfs.Dockerfile ]] && mode=0644
  privileged install -m "${mode}" "${ROOT}/scripts/remote-access/${name}" "${PACKAGE_DIR}/${name}"
done
[[ ! -L "${JAR_DIR}/forge-agent.jar" ]] || { echo 'REMOTE_ACCESS_PACKAGE_CONFLICT' >&2; exit 1; }
privileged install -m 0644 "${AGENT_JAR}" "${JAR_DIR}/forge-agent.jar"
echo 'REMOTE_ACCESS_PACKAGE_STAGED'
