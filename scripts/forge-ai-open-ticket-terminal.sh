#!/usr/bin/env bash
set -euo pipefail

die() {
  echo "forge-ai-open-ticket-terminal: $*" >&2
  exit 1
}

ticket_id="${1:-}"
base_url="${2:-}"
watcher_id="${3:-}"
verbosity="${4:-minimal}"
ticket_label="${5:-}"
launcher="${6:-auto}"

[[ -n "$ticket_id" ]] || die "ticketId is required"
[[ -n "$base_url" ]] || die "baseUrl is required"
[[ -n "$watcher_id" ]] || die "watcherId is required"

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
watch_script="${script_dir}/forge-ai-watch-ticket.sh"
[[ -x "$watch_script" ]] || chmod +x "$watch_script"

manual_watch_command() {
  printf '%q %q %q %q %q\n' "$watch_script" "$ticket_id" "$base_url" "$watcher_id" "$verbosity"
}

watch_command() {
  manual_watch_command
}

detected_launcher() {
  case "${launcher}" in
    auto|"")
      if [[ "${TERM_PROGRAM:-}" == "iTerm.app" ]]; then
        printf '%s' "iterm"
      else
        printf '%s' "terminal"
      fi
      ;;
    *)
      printf '%s' "$launcher"
      ;;
  esac
}

if ! command -v osascript >/dev/null 2>&1; then
  echo "Terminal open failed. Run manually:" >&2
  manual_watch_command >&2
  exit 1
fi

open_in_terminal() {
  osascript - "$(watch_command)" <<'APPLESCRIPT'
on run argv
  set commandText to item 1 of argv
  tell application "Terminal"
    activate
    do script commandText
  end tell
end run
APPLESCRIPT
}

open_in_iterm() {
  osascript - "$(watch_command)" <<'APPLESCRIPT'
on run argv
  set commandText to item 1 of argv
  tell application "iTerm"
    activate
    if (count of windows) is 0 then
      create window with default profile command commandText
    else
      tell current window
        create tab with default profile command commandText
      end tell
    end if
  end tell
end run
APPLESCRIPT
}

opened="0"
case "$(detected_launcher)" in
  iterm)
    open_in_iterm && opened="1"
    ;;
  terminal)
    open_in_terminal && opened="1"
    ;;
  auto|"")
    ;;
  none)
    echo "Terminal open disabled. Run manually:" >&2
    manual_watch_command >&2
    exit 1
    ;;
  *)
    if open_in_iterm; then
      opened="1"
    elif open_in_terminal; then
      opened="1"
    fi
    ;;
esac

if [[ "$opened" != "1" ]]; then
  echo "Terminal open failed. Run manually:" >&2
  manual_watch_command >&2
  exit 1
fi
