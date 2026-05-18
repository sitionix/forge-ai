#!/usr/bin/env bash
set -euo pipefail

die() {
  echo "forge-ai-start: $*" >&2
  exit 1
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "required command not found: $command_name"
}

log_step() {
  echo
  echo "[forge-ai-start] $*"
}

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
APP_CONFIG_FILE="${REPO_ROOT}/forge-ai/boot/src/main/resources/application.yml"
SERVICES_FILE=""
SKIP_FORGE_AI_REBUILD="${SKIP_FORGE_AI_REBUILD:-0}"
FORGE_AI_DIR="${REPO_ROOT}/forge-ai"
FORGE_AI_PID_FILE="${REPO_ROOT}/.forge-ai-local.pid"
FORGE_AI_LOG_FILE="${REPO_ROOT}/.forge-ai-local.log"
FORGE_AI_JAR_RELATIVE_PATH="boot/target/boot-0.0.1-SNAPSHOT.jar"
FORGE_AI_STARTED_LOCAL_THIS_RUN="0"

validate_repo_root() {
  [[ "$(pwd)" == "$REPO_ROOT" ]] || die "run this command from repository root: $REPO_ROOT"
  [[ -r "$APP_CONFIG_FILE" ]] || die "application config is missing or unreadable: $APP_CONFIG_FILE"
  resolve_services_file
  [[ -r "$SERVICES_FILE" ]] || die "services config is missing or unreadable: $SERVICES_FILE"
}

resolve_services_file() {
  local services_resource
  services_resource="$(yq -r '.forge.ai.launcher.services-config-resource' "$APP_CONFIG_FILE")"
  [[ -n "$services_resource" && "$services_resource" != "null" ]] || die "forge.ai.launcher.services-config-resource is not configured in $APP_CONFIG_FILE"

  case "$services_resource" in
    classpath:*)
      SERVICES_FILE="${REPO_ROOT}/forge-ai/boot/src/main/resources/${services_resource#classpath:}"
      ;;
    *)
      die "unsupported services-config-resource format: $services_resource (expected classpath:...)"
      ;;
  esac
}

base_url() {
  local url
  url="$(yq -r '.forge.ai.launcher.default-base-url' "$APP_CONFIG_FILE")"
  [[ -n "$url" && "$url" != "null" ]] || die "forge.ai.launcher.default-base-url is not configured in $APP_CONFIG_FILE"
  printf '%s' "$url"
}

module_rows() {
  local rows

  rows="$(yq -r '
    .services
    | to_entries[]
    | [
        .key,
        (.value.label // .key),
        (.value.path // ""),
        (.value.group // "unknown"),
        ((.value.tags // []) | join(","))
      ]
    | @tsv
  ' "$SERVICES_FILE" | sed '/^$/d')"

  [[ -n "$rows" ]] || die "no selectable modules were found in ${SERVICES_FILE}"
  printf '%s' "$rows"
}

module_paths() {
  local rows="$1"
  printf '%s\n' "$rows" | cut -f3
}

select_modules() {
  local paths="$1"
  local selection

  selection="$(printf '%s\n' "$paths" | fzf \
    --multi \
    --prompt='Modules> ' \
    --height=70% \
    --reverse \
    --border || true)"

  selection="$(trim "$selection")"
  [[ -n "$selection" ]] || die "module selection is empty"

  printf '%s' "$selection"
}

resolve_selected_modules() {
  local selected_paths="$1"
  local candidate_rows="$2"
  local line
  local selected_row
  local resolved_rows=""

  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    selected_row="$(printf '%s\n' "$candidate_rows" | awk -F '\t' -v selected_path="$line" '$3 == selected_path {print $0}')"
    [[ -n "$selected_row" ]] || die "selected module path is not present in the YAML-derived candidate set: $line"
    if [[ "$(printf '%s\n' "$selected_row" | wc -l | tr -d ' ')" != "1" ]]; then
      die "selected module path is not unique in the YAML-derived candidate set: $line"
    fi
    if [[ -n "$resolved_rows" ]]; then
      resolved_rows+=$'\n'
    fi
    resolved_rows+="$selected_row"
  done <<< "$selected_paths"

  printf '%s' "$resolved_rows"
}

read_ticket_number() {
  local value
  printf '\nEnter task number (max 5 digits):\n' >&2
  IFS= read -r value || true
  value="$(trim "$value")"
  [[ "$value" =~ ^[0-9]{1,5}$ ]] || die "task number must be 1..5 digits"
  printf 'SITIONIX-%s' "$value"
}

read_task_description() {
  local line
  local task=""
  local pending_empty_lines=0

  printf '\nEnter the full task description for Forge AI.\n' >&2
  printf 'Submit with two empty lines.\n' >&2

  while IFS= read -r line; do
    if [[ -z "$line" ]]; then
      pending_empty_lines=$((pending_empty_lines + 1))
      if (( pending_empty_lines >= 2 )); then
        break
      fi
      continue
    fi

    while (( pending_empty_lines > 0 )); do
      if [[ -n "$task" ]]; then
        task+=$'\n'
      fi
      pending_empty_lines=$((pending_empty_lines - 1))
    done

    if [[ -n "$task" ]]; then
      task+=$'\n'
    fi
    task+="$line"
  done

  task="$(trim "$task")"
  [[ -n "$task" ]] || die "task description is empty"
  printf '%s' "$task"
}

is_forge_ai_up() {
  local url="$1"
  curl -fsS "${url}/actuator/health" >/dev/null 2>&1
}

is_forge_ai_process_alive() {
  if [[ ! -f "$FORGE_AI_PID_FILE" ]]; then
    return 1
  fi
  local pid
  pid="$(cat "$FORGE_AI_PID_FILE" 2>/dev/null || true)"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" >/dev/null 2>&1
}

stop_listener_on_forge_ai_port() {
  local pids
  pids="$(lsof -t -iTCP:9099 -sTCP:LISTEN 2>/dev/null || true)"
  [[ -n "$pids" ]] || return 0

  log_step "Stopping processes listening on 9099: $pids"
  local pid
  for pid in $pids; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  sleep 1
}

start_local_forge_ai_process() {
  local should_rebuild="${1:-1}"

  if is_forge_ai_process_alive; then
    if [[ "$should_rebuild" == "1" ]]; then
      log_step "Stopping existing local Forge AI process (pid=$(cat "$FORGE_AI_PID_FILE")) for rebuild..."
    else
      log_step "Stopping stale local Forge AI process (pid=$(cat "$FORGE_AI_PID_FILE")) before start..."
    fi
    kill "$(cat "$FORGE_AI_PID_FILE")" >/dev/null 2>&1 || true
    rm -f "$FORGE_AI_PID_FILE"
    sleep 1
  fi

  if docker compose ps --status running forge-ai-service | grep -q 'fgaisox-service'; then
    log_step "Stopping docker forge-ai-service to free port 9099 for local JVM..."
    docker compose stop forge-ai-service >/dev/null
  fi
  stop_listener_on_forge_ai_port

  if [[ "$should_rebuild" == "1" ]]; then
    log_step "Building local Forge AI jar..."
    (
      cd "$FORGE_AI_DIR"
      mvn -pl boot -am -DskipTests package
    )
  else
    log_step "Starting without rebuild (using existing jar)..."
  fi

  local jar_path="${FORGE_AI_DIR}/${FORGE_AI_JAR_RELATIVE_PATH}"
  [[ -f "$jar_path" ]] || die "forge-ai jar not found: $jar_path"

  log_step "Starting local Forge AI process..."
  rm -f "$FORGE_AI_PID_FILE"
  : > "$FORGE_AI_LOG_FILE"
  (
    cd "$FORGE_AI_DIR"
    WORKSPACE_ROOT="$REPO_ROOT" MONGODB_URI="mongodb://localhost:27018/forge_ai" java -jar "$FORGE_AI_JAR_RELATIVE_PATH"
  ) >>"$FORGE_AI_LOG_FILE" 2>&1 &
  echo "$!" > "$FORGE_AI_PID_FILE"
  FORGE_AI_STARTED_LOCAL_THIS_RUN="1"
  echo "[forge-ai-start] Local Forge AI pid=$(cat "$FORGE_AI_PID_FILE")"
  echo "[forge-ai-start] Logs: $FORGE_AI_LOG_FILE"
}

ensure_forge_ai_up() {
  local url="$1"

  if is_forge_ai_up "$url"; then
    log_step "Forge AI is already healthy. Reusing existing run."
    echo "[forge-ai-start] Forge AI is healthy."
    FORGE_AI_STARTED_LOCAL_THIS_RUN="0"
    return 0
  fi

  log_step "Starting MongoDB for Forge AI..."
  just infra-up forge-ai-mongo

  if [[ "$SKIP_FORGE_AI_REBUILD" != "1" ]]; then
    start_local_forge_ai_process "1"
  else
    log_step "Forge AI is not healthy. Starting without rebuild."
    start_local_forge_ai_process "0"
  fi

  log_step "Checking Forge AI health: ${url}/actuator/health"
  if is_forge_ai_up "$url"; then
    echo "[forge-ai-start] Forge AI is healthy."
    return 0
  fi

  local attempts=20
  local sleep_seconds=3
  local i
  log_step "Waiting for Forge AI to become healthy..."
  for ((i=1; i<=attempts; i++)); do
    if is_forge_ai_up "$url"; then
      echo "[forge-ai-start] Forge AI is healthy (attempt ${i}/${attempts})."
      return 0
    fi
    echo "[forge-ai-start] attempt ${i}/${attempts}: not ready yet"
    sleep "$sleep_seconds"
  done

  die "Forge AI did not become healthy at ${url}/actuator/health"
}

ensure_existing_forge_ai_up() {
  local url="$1"
  if ! is_forge_ai_up "$url"; then
    die "Forge AI is not healthy at ${url}/actuator/health (existing-only mode)"
  fi
  log_step "Forge AI is already healthy. Reusing existing run."
  echo "[forge-ai-start] Forge AI is healthy."
  FORGE_AI_STARTED_LOCAL_THIS_RUN="0"
}

shell_quote() {
  local value="$1"
  printf "'%s'" "${value//\'/\'\"\'\"\'}"
}

start_task() {
  local url="$1"
  local ticket="$2"
  local task="$3"
  local selected_modules_rows="$4"
  local source_tty="$5"

  local service_ids_json
  service_ids_json="$(printf '%s\n' "$selected_modules_rows" | cut -f1 | jq -Rsc 'split("\n") | map(select(length > 0))')"

  local payload
  payload="$(jq -cn --arg ticket "$ticket" --arg task "$task" --argjson serviceIds "$service_ids_json" '{ticket: $ticket, task: $task, serviceIds: $serviceIds}')"

  log_step "Submitting request to Forge AI"
  echo "[forge-ai-start] ticket=${ticket}"
  echo "[forge-ai-start] serviceIds=${service_ids_json}"
  echo "[forge-ai-start] POST ${url}/api/v1/forge-ai/start"

  local response
  response="$(curl -fsS -X POST "${url}/api/v1/forge-ai/start" \
    -H 'Content-Type: application/json' \
    -H "X-Terminal-TTY: ${source_tty}" \
    -d "$payload")"
  printf '%s\n' "$response"
  if [[ "$FORGE_AI_STARTED_LOCAL_THIS_RUN" == "1" ]]; then
    echo "[forge-ai-start] Forge AI app log tail:"
    tail -n 40 "$FORGE_AI_LOG_FILE" || true
  else
    echo "[forge-ai-start] Reused existing Forge AI process; skipping local app log tail."
  fi
}

main() {
  require_command yq
  require_command fzf
  require_command curl
  require_command jq
  require_command just

  validate_repo_root

  local available_module_rows
  local selectable_module_paths
  local selected_module_paths
  local selected_modules
  local ticket
  local task
  local forge_ai_base_url
  local force_rebuild="0"
  local existing_only="0"
  local source_tty

  while (($# > 0)); do
    case "$1" in
      -r|--rebuild)
        force_rebuild="1"
        shift
        ;;
      -e|--existing)
        existing_only="1"
        shift
        ;;
      *)
        die "unknown argument: $1 (supported: -r | --rebuild | -e | --existing)"
        ;;
    esac
  done

  available_module_rows="$(module_rows)"
  selectable_module_paths="$(module_paths "$available_module_rows")"
  selected_module_paths="$(select_modules "$selectable_module_paths")"
  selected_modules="$(resolve_selected_modules "$selected_module_paths" "$available_module_rows")"

  ticket="$(read_ticket_number)"
  task="$(read_task_description)"
  forge_ai_base_url="$(base_url)"
  source_tty="$(tty)"

  if [[ "$force_rebuild" == "1" && "$existing_only" == "1" ]]; then
    die "flags -r/--rebuild and -e/--existing are mutually exclusive"
  fi

  if [[ "$force_rebuild" == "1" ]]; then
    SKIP_FORGE_AI_REBUILD="0"
    log_step "Rebuild flag detected (-r): Forge AI will be rebuilt."
  elif [[ "$existing_only" == "1" ]]; then
    SKIP_FORGE_AI_REBUILD="1"
    log_step "Existing-only flag detected (-e): will not start Forge AI, only reuse healthy existing instance."
  else
    SKIP_FORGE_AI_REBUILD="1"
    log_step "Rebuild flag not set: running without rebuild."
  fi

  if [[ "$existing_only" == "1" ]]; then
    ensure_existing_forge_ai_up "$forge_ai_base_url"
  else
    ensure_forge_ai_up "$forge_ai_base_url"
  fi
  start_task "$forge_ai_base_url" "$ticket" "$task" "$selected_modules" "$source_tty"
}

main "$@"
