#!/usr/bin/env bash
set -euo pipefail

workspace_root="${1:-}"
prompt_file_path="${2:-}"

if [[ -z "${workspace_root}" || -z "${prompt_file_path}" ]]; then
  echo "[forge-ai] usage: run-codex-with-prompt-file.sh <workspace_root> <prompt_file_path>" >&2
  exit 1
fi

if [[ ! -f "${prompt_file_path}" ]]; then
  echo "[forge-ai] prompt file not found: ${prompt_file_path}" >&2
  exit 1
fi

cd "${workspace_root}"
echo "[forge-ai] cwd=$(pwd)"
echo "[forge-ai] starting interactive codex"
PROMPT="$(cat "${prompt_file_path}")"
rm -f "${prompt_file_path}"
exec codex --no-alt-screen -C "${workspace_root}" "${PROMPT}"
