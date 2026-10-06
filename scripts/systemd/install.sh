#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORGE_AI_HOME="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

UNIT_DIR="${FORGE_SYSTEMD_UNIT_DIR:-/etc/systemd/system}"
ENV_DIR="${FORGE_SYSTEMD_ENV_DIR:-/etc/forge-ai}"
ENV_FILE="${FORGE_SYSTEMD_ENV_FILE:-${ENV_DIR}/forge-ai.env}"
USE_SUDO="${FORGE_SYSTEMD_USE_SUDO:-auto}"
SKIP_RELOAD="${FORGE_SYSTEMD_SKIP_RELOAD:-0}"
UNITS=(forge-agent.service forge-nexus.service forge-knowledge.service forge-jarvis.service forge-remote-agent.service forge-remote-nexus.service forge-remote-bootstrap.socket forge-remote-bootstrap.service forge-remote-setup.service)
if [[ "${USE_SUDO}" == "0" ]]; then
  BOOTSTRAP_BIN_DIR="${FORGE_REMOTE_BOOTSTRAP_BIN_DIR:-/usr/libexec/forge-remote}"
  BOOTSTRAP_MANIFEST="${FORGE_REMOTE_BOOTSTRAP_MANIFEST:-/etc/forge-ai/forge-remote-enable.json}"
else
  BOOTSTRAP_BIN_DIR=/usr/libexec/forge-remote
  BOOTSTRAP_MANIFEST=/etc/forge-ai/forge-remote-enable.json
fi
AGENT_JAR="${FORGE_REMOTE_ACCESS_AGENT_JAR_SOURCE:-${FORGE_AI_HOME}/services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar}"

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

# Ubuntu 22.04 uses Python 3.10, before TOML parsing joined the standard library.
# The isolated root helper can use only a system-installed backport.
if ! /usr/bin/python3 -I -c 'import tomllib' >/dev/null 2>&1 \
    && ! /usr/bin/python3 -I -c 'import tomli' >/dev/null 2>&1; then
  run_privileged /usr/bin/apt-get install -y python3-tomli
  /usr/bin/python3 -I -c 'import tomli'
fi

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "${tmp_dir}"
}
trap cleanup EXIT

"${SCRIPT_DIR}/render-units.sh" "${tmp_dir}/units" "${tmp_dir}/forge-ai.env" "${ENV_FILE}"
/usr/bin/python3 -I "${FORGE_AI_HOME}/scripts/remote-access/prepare_enable.py" manifest "${FORGE_AI_HOME}" "${tmp_dir}/enable.json" "${AGENT_JAR}"

run_privileged install -d -m 0755 "${UNIT_DIR}" "${ENV_DIR}" "${BOOTSTRAP_BIN_DIR}"
# Provision the main runtime before replacing its old environment or restarting it.
# An optional caller-supplied DB credential travels through an owner-only file only.
/usr/bin/python3 -I - "${tmp_dir}/database-input" <<'PYDB'
import os, pathlib
path = pathlib.Path(__import__('sys').argv[1])
fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
with os.fdopen(fd, 'wb') as stream:
    os.fchmod(stream.fileno(), 0o600)
    stream.write(os.environ.get('FORGE_AGENT_DB_PASSWORD', '').encode())
PYDB
codex_source="$(/usr/bin/python3 -I - "$(command -v codex)" <<'PYCODEX'
import pathlib, platform, sys
cli = pathlib.Path(sys.argv[1]).resolve()
architecture = {'x86_64': ('x64', 'x86_64'), 'aarch64': ('arm64', 'aarch64')}[platform.machine()]
package, cpu = architecture
triple = cpu + '-unknown-linux-musl'
root = cli.parent.parent
candidates = [root / 'node_modules' / '@openai' / ('codex-linux-' + package) / 'vendor' / triple,
              root / 'vendor' / triple, root]
for candidate in candidates:
    if (candidate / 'bin/codex').is_file() and (candidate / 'codex-resources/bwrap').is_file():
        print(candidate)
        break
else:
    raise SystemExit('Installed native Codex/resources are required')
PYCODEX
)"
run_privileged /usr/bin/python3 -I "${FORGE_AI_HOME}/scripts/runtime/install_mcp.py" \
  "${FORGE_AI_HOME}" "${FORGE_SYSTEMD_USER:-$(id -un)}" "${codex_source}" "${tmp_dir}/database-input" \
  --material-root "${FORGE_MCP_MATERIAL_DIR:-${ENV_DIR}/mcp}" \
  --workspace-root "${FORGE_AGENT_WORKSPACE_ROOT:-/srv/forge/workspaces/forge-projects}" \
  --existing-environment "${ENV_FILE}"
run_privileged install -m 0600 "${tmp_dir}/forge-ai.env" "${ENV_FILE}"
run_privileged install -m 0600 "${tmp_dir}/enable.json" "${BOOTSTRAP_MANIFEST}"
run_privileged install -m 0755 "${FORGE_AI_HOME}/scripts/remote-access/bootstrap.py" "${BOOTSTRAP_BIN_DIR}/bootstrap.py"
run_privileged install -m 0755 "${FORGE_AI_HOME}/scripts/remote-access/prepare_enable.py" "${BOOTSTRAP_BIN_DIR}/prepare-enable.py"
run_privileged install -m 0600 "${tmp_dir}/units/control-agent.env" "${ENV_DIR}/forge-remote-agent.env"
run_privileged install -m 0600 "${tmp_dir}/units/control-nexus.env" "${ENV_DIR}/forge-remote-nexus.env"
for unit in "${UNITS[@]}"; do
  run_privileged install -m 0644 "${tmp_dir}/units/${unit}" "${UNIT_DIR}/${unit}"
done

if [[ "${SKIP_RELOAD}" != "1" ]]; then
  run_privileged systemctl daemon-reload
fi

printf 'Installed Forge systemd units to %s\n' "${UNIT_DIR}"
printf 'Installed Forge systemd environment to %s\n' "${ENV_FILE}"
