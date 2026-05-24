#!/usr/bin/env bash
set -euo pipefail

workspace_root="${1:-}"
prompt_file_path="${2:-}"

append_no_proxy_entry() {
  local current="${1:-}"
  local entry="${2:-}"
  if [[ -z "$entry" ]]; then
    printf '%s' "$current"
    return
  fi
  if [[ -z "$current" ]]; then
    printf '%s' "$entry"
    return
  fi
  case ",$current," in
    *",$entry,"*) printf '%s' "$current" ;;
    *) printf '%s,%s' "$current" "$entry" ;;
  esac
}

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

# Ensure local callbacks never route through inherited proxy settings from parent shells/profiles.
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy
no_proxy_value="${NO_PROXY:-${no_proxy:-}}"
no_proxy_value="$(append_no_proxy_entry "$no_proxy_value" "127.0.0.1")"
no_proxy_value="$(append_no_proxy_entry "$no_proxy_value" "localhost")"
no_proxy_value="$(append_no_proxy_entry "$no_proxy_value" "::1")"
no_proxy_value="$(append_no_proxy_entry "$no_proxy_value" "*")"
export NO_PROXY="$no_proxy_value"
export no_proxy="$no_proxy_value"

PROMPT="$(cat "${prompt_file_path}")"
rm -f "${prompt_file_path}"
exec codex --no-alt-screen -C "${workspace_root}" "${PROMPT}"
