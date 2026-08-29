#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SERVICE="${1:?Usage: render-plist.sh service output-file log-directory}"
OUTPUT="${2:?Usage: render-plist.sh service output-file log-directory}"
LOG_DIR="${3:?Usage: render-plist.sh service output-file log-directory}"

case "${SERVICE}" in knowledge|jarvis|agent|nexus) ;; *) echo "Unknown Forge service: ${SERVICE}" >&2; exit 2 ;; esac

xml_escape() {
  local value="$1"
  value="${value//&/&amp;}"
  value="${value//</&lt;}"
  value="${value//>/&gt;}"
  value="${value//\"/&quot;}"
  value="${value//\'/&apos;}"
  printf '%s' "${value}"
}

root_xml="$(xml_escape "${ROOT}")"
runner_xml="$(xml_escape "${ROOT}/scripts/runtime/run-${SERVICE}.sh")"
stdout_xml="$(xml_escape "${LOG_DIR}/${SERVICE}.out.log")"
stderr_xml="$(xml_escape "${LOG_DIR}/${SERVICE}.err.log")"
path_xml="$(xml_escape "${PATH:-/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin}")"

mkdir -p "$(dirname -- "${OUTPUT}")"
tmp="${OUTPUT}.tmp.$$"
trap 'rm -f "${tmp}"' EXIT
{
  printf '%s\n' '<?xml version="1.0" encoding="UTF-8"?>'
  printf '%s\n' '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
  printf '%s\n' '<plist version="1.0"><dict>'
  printf '  <key>Label</key><string>ai.forge.%s</string>\n' "${SERVICE}"
  printf '  <key>ProgramArguments</key><array><string>%s</string></array>\n' "${runner_xml}"
  printf '  <key>WorkingDirectory</key><string>%s</string>\n' "${root_xml}"
  printf '  <key>EnvironmentVariables</key><dict><key>FORGE_AI_HOME</key><string>%s</string><key>PATH</key><string>%s</string></dict>\n' "${root_xml}" "${path_xml}"
  printf '%s\n' '  <key>RunAtLoad</key><true/>'
  printf '%s\n' '  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>'
  printf '  <key>StandardOutPath</key><string>%s</string>\n' "${stdout_xml}"
  printf '  <key>StandardErrorPath</key><string>%s</string>\n' "${stderr_xml}"
  printf '%s\n' '</dict></plist>'
} > "${tmp}"
chmod 0644 "${tmp}"
mv "${tmp}" "${OUTPUT}"
trap - EXIT
