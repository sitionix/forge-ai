#!/usr/bin/env bash
set -euo pipefail

die() {
  echo "forge-ai-watch-ticket: $*" >&2
  exit 1
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "required command not found: $command_name"
}

ticket_id="${1:-}"
base_url="${2:-}"
watcher_id="${3:-}"
verbosity="${4:-minimal}"

[[ -n "$ticket_id" ]] || die "ticketId is required"
[[ -n "$base_url" ]] || die "baseUrl is required"
[[ -n "$watcher_id" ]] || die "watcherId is required"

printf '\033]0;Forge AI | ticket=%s\007' "$ticket_id"

require_command curl
require_command jq

api_base="${base_url%/}/api/v1/forge-ai/operator/tickets/${ticket_id}"
terminal_status="RUNNING"
heartbeat_pid=""
interrupt_sent="0"
watcher_connected="0"
snapshot_json=""
color_enabled="1"

if [[ ! -t 1 || "${NO_COLOR:-}" == "1" || "${TERM:-}" == "dumb" ]]; then
  color_enabled="0"
fi

ansi() {
  if [[ "$color_enabled" == "1" ]]; then
    printf '\033[%sm' "$1"
  fi
}

color_reset() {
  ansi "0"
}

color_fg() {
  ansi "$1"
}

color_bold() {
  ansi "1"
}

color_dim() {
  ansi "2"
}

color_agent_scope() {
  local key="$1"
  local sum=0
  local char
  local palette=(38 39 44 45 69 75 81 111 117 123 141 149 177 184 190 208)
  for ((i=0; i<${#key}; i++)); do
    char="${key:i:1}"
    printf -v ord '%d' "'$char"
    sum=$((sum + ord))
  done
  printf '%s' "38;5;${palette[$((sum % ${#palette[@]}))]}"
}

event_color() {
  local event_type="$1"
  case "$event_type" in
    STEP_PERSISTED|LANE_COMPLETED|TICKET_COMPLETED|STEP_VALIDATION_PASSED|VALIDATED|PROCESS_TERMINATED)
      printf '%s' "1;32"
      ;;
    STEP_VALIDATION_FAILED|LANE_FAILED|TICKET_FAILED|PROCESS_STDERR)
      printf '%s' "1;31"
      ;;
    CORRECTION_STARTED|HEARTBEAT|STREAM_RETRY|WAITING|RETRY)
      printf '%s' "1;33"
      ;;
    STEP_STARTED|TURN_STARTED|LANE_STARTED|PROCESS_STARTED|SESSION_STARTED)
      printf '%s' "1;36"
      ;;
    TICKET_CANCELLED|TICKET_INTERRUPT_REQUESTED|LANE_INTERRUPTED)
      printf '%s' "1;35"
      ;;
    *)
      printf '%s' "1;37"
      ;;
  esac
}

print_with_color() {
  local color="$1"
  shift
  printf '%s' "$(color_fg "$color")"
  printf '%s' "$*"
  printf '%s' "$(color_reset)"
}

print_event_line() {
  local color="$1"
  local prefix="$2"
  local label="$3"
  shift 3
  local suffix="$*"
  print_with_color "$color" "$prefix $label"
  if [[ -n "$suffix" ]]; then
    printf ' %s' "$suffix"
  fi
  printf '\n'
}

print_agent_scope_line() {
  local color="$1"
  local agent="$2"
  local scope="$3"
  printf '           '
  print_with_color "$color" "$agent / $scope"
  printf '\n'
}

print_detail_line() {
  printf '           '
  printf '%s' "$(color_dim)"
  printf '%s' "$*"
  printf '%s\n' "$(color_reset)"
}

uppercase_or_dash() {
  local value="$1"
  if [[ -z "$value" || "$value" == "null" ]]; then
    printf '%s' "-"
    return
  fi
  printf '%s' "$value" | tr '[:lower:]' '[:upper:]'
}

render_block() {
  local line="$1"
  local event_type
  event_type="$(jq -r '.eventType // ""' <<<"$line")"
  local timestamp
  timestamp="$(jq -r '(.timestamp // "") | sub("^.*T"; "") | sub("\\..*$"; "") | sub("Z$"; "")' <<<"$line")"
  local prefix="[${timestamp:---:--:--}]"
  local event_color_code
  local agent_scope_color
  local agent_value
  local scope_value
  agent_value="$(uppercase_or_dash "$(jq -r '.agentId // "-"' <<<"$line")")"
  scope_value="$(jq -r '.scope // "-"' <<<"$line")"
  event_color_code="$(event_color "$event_type")"
  agent_scope_color="$(color_agent_scope "${agent_value}/${scope_value}")"

  case "$event_type" in
    TICKET_STARTED)
      print_event_line "$event_color_code" "$prefix" "TICKET STARTED"
      ;;
    WATCHER_CONNECTED)
      print_event_line "$event_color_code" "$prefix" "START" "ticket watcher connected"
      ;;
    TICKET_TERMINAL_OPENED)
      if [[ "$verbosity" != "minimal" ]]; then
        print_event_line "$event_color_code" "$prefix" "TERMINAL OPENED"
      fi
      ;;
    LANE_STARTED)
      print_event_line "$event_color_code" "$prefix" "LANE STARTED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "$(printf 'lane=%s execution=%s pid=%s' \
        "$(jq -r '.laneId // "-"' <<<"$line")" \
        "$(jq -r '.executionId // "-"' <<<"$line")" \
        "$(jq -r '.codexProcessPid // "-"' <<<"$line")")"
      ;;
    SESSION_STARTED)
      if [[ "$verbosity" != "minimal" ]]; then
        print_event_line "$event_color_code" "$prefix" "CODEX SESSION STARTED"
        print_detail_line "$(printf 'thread=%s pid=%s' \
          "$(jq -r '.codexThreadId // "-"' <<<"$line")" \
          "$(jq -r '.codexProcessPid // "-"' <<<"$line")")"
      fi
      ;;
    STEP_STARTED)
      print_event_line "$event_color_code" "$prefix" \
        "STEP $(jq -r '.stepOrder // "-"' <<<"$line")/$(jq -r '.totalSteps // "-"' <<<"$line") STARTED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "$(printf '%s - %s' \
        "$(jq -r '.stepId // "-"' <<<"$line")" \
        "$(jq -r '.stepTitle // "-"' <<<"$line")")"
      ;;
    TURN_STARTED)
      print_event_line "$event_color_code" "$prefix" "TURN STARTED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "$(printf 'step=%s turn=%s' \
        "$(jq -r '.stepId // "-"' <<<"$line")" \
        "$(jq -r '.activeTurnId // "-"' <<<"$line")")"
      ;;
    COMMAND_STARTED)
      print_event_line "$event_color_code" "$prefix" "CMD STARTED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "$(jq -r '.message // "-"' <<<"$line")"
      ;;
    COMMAND_COMPLETED)
      print_event_line "$event_color_code" "$prefix" "CMD COMPLETED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      ;;
    HEARTBEAT)
      if [[ "$verbosity" == "minimal" ]]; then
        return
      fi
      print_event_line "$event_color_code" "$prefix" "RUNNING"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "step=$(jq -r '.stepId // "-"' <<<"$line")"
      ;;
    STEP_VALIDATION_PASSED)
      print_event_line "$event_color_code" "$prefix" "VALIDATED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "step=$(jq -r '.stepId // "-"' <<<"$line")"
      ;;
    STEP_RESPONSE_RECEIVED)
      if [[ "$verbosity" != "minimal" ]]; then
        print_event_line "$event_color_code" "$prefix" "STEP RESPONSE RECEIVED"
        print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
        print_detail_line "step=$(jq -r '.stepId // "-"' <<<"$line")"
      fi
      ;;
    STEP_VALIDATION_FAILED)
      print_event_line "$event_color_code" "$prefix" "VALIDATION FAILED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "step=$(jq -r '.stepId // "-"' <<<"$line")"
      ;;
    CORRECTION_STARTED)
      print_event_line "$event_color_code" "$prefix" "CORRECTION STARTED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "step=$(jq -r '.stepId // "-"' <<<"$line")"
      ;;
    STEP_PERSISTED)
      print_event_line "$event_color_code" "$prefix" \
        "STEP $(jq -r '.stepOrder // "-"' <<<"$line")/$(jq -r '.totalSteps // "-"' <<<"$line") DONE"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "$(jq -r '.stepId // "-"' <<<"$line") persisted"
      ;;
    NEXT_STEP)
      if [[ "$verbosity" != "minimal" ]]; then
        print_event_line "$event_color_code" "$prefix" "NEXT" "$(jq -r '.message // "-"' <<<"$line")"
      fi
      ;;
    LANE_COMPLETED)
      print_event_line "$event_color_code" "$prefix" "LANE DONE"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      ;;
    LANE_FAILED)
      print_event_line "$event_color_code" "$prefix" "LANE FAILED"
      print_agent_scope_line "$agent_scope_color" "$agent_value" "$scope_value"
      print_detail_line "$(jq -r '.message // "-"' <<<"$line")"
      ;;
    TICKET_COMPLETED)
      terminal_status="COMPLETED"
      print_event_line "$event_color_code" "$prefix" "TICKET COMPLETED"
      ;;
    TICKET_INTERRUPT_REQUESTED)
      print_event_line "$event_color_code" "$prefix" "TICKET INTERRUPT REQUESTED"
      ;;
    TICKET_CANCELLED)
      terminal_status="CANCELLED"
      print_event_line "$event_color_code" "$prefix" "TICKET CANCELLED"
      print_detail_line "$(jq -r '.message // "-"' <<<"$line")"
      ;;
    TICKET_FAILED)
      terminal_status="FAILED"
      print_event_line "$event_color_code" "$prefix" "TICKET FAILED"
      print_detail_line "$(jq -r '.message // "-"' <<<"$line")"
      ;;
    PROCESS_STDERR)
      if [[ "$verbosity" != "minimal" ]]; then
        print_event_line "$event_color_code" "$prefix" "STDERR" "$(jq -r '.message // "-"' <<<"$line")"
      fi
      ;;
    COMMAND_OUTPUT|AGENT_MESSAGE_DELTA|PLAN)
      if [[ "$verbosity" != "minimal" ]]; then
        print_event_line "$event_color_code" "$prefix" "$event_type" "$(jq -r '.message // "-"' <<<"$line")"
      fi
      ;;
    *)
      print_event_line "$event_color_code" "$prefix" "$event_type" "$(jq -r '.message // "-"' <<<"$line")"
      ;;
  esac
}

send_interrupt_if_needed() {
  if [[ "$watcher_connected" != "1" ]]; then
    return 0
  fi
  if [[ "$terminal_status" == "COMPLETED" || "$terminal_status" == "FAILED" || "$terminal_status" == "CANCELLED" ]]; then
    return 0
  fi
  if [[ "$interrupt_sent" == "1" ]]; then
    return 0
  fi
  interrupt_sent="1"
  curl -fsS -X POST "${api_base}/interrupt?reason=OPERATOR_TICKET_TERMINAL_CLOSED" >/dev/null 2>&1 || true
}

cleanup() {
  if [[ -n "$heartbeat_pid" ]]; then
    kill "$heartbeat_pid" >/dev/null 2>&1 || true
    wait "$heartbeat_pid" 2>/dev/null || true
  fi
  send_interrupt_if_needed
}

trap cleanup INT TERM HUP EXIT

fetch_snapshot() {
  local attempts=15
  local sleep_seconds=2
  local attempt
  local output=""

  for ((attempt=1; attempt<=attempts; attempt++)); do
    if output="$(curl -fsS "${api_base}" 2>/dev/null)"; then
      snapshot_json="$output"
      watcher_connected="1"
      return 0
    fi
    if (( attempt == 1 )); then
      printf '[--:--:--] WAITING\n'
      printf '           Forge AI ticket watcher is waiting for operator endpoint\n'
      printf '           ticket=%s attempt=%s/%s\n' "$ticket_id" "$attempt" "$attempts"
    else
      printf '[--:--:--] RETRY\n'
      printf '           ticket=%s attempt=%s/%s\n' "$ticket_id" "$attempt" "$attempts"
    fi
    sleep "$sleep_seconds"
  done

  printf '[--:--:--] WATCHER FAILED\n'
  printf '           Could not connect to ticket operator endpoint\n'
  printf '           %s\n' "${api_base}"
  return 1
}

stream_ticket_events() {
  local replay_on_connect="true"
  while true; do
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -n "$line" ]] || continue
      render_block "$line"
      if [[ "$terminal_status" == "COMPLETED" || "$terminal_status" == "FAILED" || "$terminal_status" == "CANCELLED" ]]; then
        return 0
      fi
    done < <(curl -fsS -N "${api_base}/stream?watcherId=${watcher_id}&verbosity=${verbosity}&stopOnWindowClose=true&replay=${replay_on_connect}" 2>/dev/null)

    if [[ "$terminal_status" == "COMPLETED" || "$terminal_status" == "FAILED" || "$terminal_status" == "CANCELLED" ]]; then
      return 0
    fi

    replay_on_connect="false"
    printf '[--:--:--] STREAM RETRY\n'
    printf '           ticket=%s watcher=%s\n' "$ticket_id" "$watcher_id"
    sleep 2
  done
}

if ! fetch_snapshot; then
  exec bash -i
fi

ticket_key="$(jq -r '.run.ticketKey // empty' <<<"$snapshot_json")"

printf 'Forge AI Ticket\n'
printf 'ticket: %s\n' "$ticket_id"
if [[ -n "$ticket_key" && "$ticket_key" != "null" ]]; then
  printf 'key:    %s\n' "$ticket_key"
fi
printf 'base:   %s\n\n' "$base_url"

(
  while true; do
    sleep 5
    curl -fsS -X POST "${api_base}/watchers/${watcher_id}/heartbeat" >/dev/null || exit 0
  done
) &
heartbeat_pid="$!"

stream_ticket_events
