set shell := ["bash", "-euo", "pipefail", "-c"]

root := justfile_directory()
app_pid := root + "/var/forge-ai.pid"
app_log := root + "/var/logs/forge-ai.log"
agent_pid := root + "/var/forge-agent.pid"
agent_log := root + "/var/logs/forge-agent.log"
app_url := "http://127.0.0.1:9099/fgaisox"
agent_url := "http://127.0.0.1:7091"
knowledge_url := "http://127.0.0.1:7081"
jarvis_url := "http://127.0.0.1:7071"
sqlite_path := root + "/var/knowledge/knowledge.sqlite"

# Rebuild and start all services, or one deployable service: agent, nexus, knowledge, jarvis.
start service="all":
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{service}}" in
        all) just --justfile "{{root}}/Justfile" _start-all ;;
        agent) just --justfile "{{root}}/Justfile" _agent-restart ;;
        nexus) just --justfile "{{root}}/Justfile" _nexus-restart ;;
        knowledge) just --justfile "{{root}}/Justfile" _knowledge-rebuild-restart ;;
        jarvis) just --justfile "{{root}}/Justfile" _jarvis-rebuild-restart ;;
        *)
            echo "Unknown service '{{service}}'. Allowed: all, agent, nexus, knowledge, jarvis." >&2
            exit 2
            ;;
    esac

_start-all: _start-preflight _app-stop _jarvis-stop _knowledge-stop _ollama-stop _postgres-start _sqlite-start _ollama-start _console-build _knowledge-start _jarvis-start _app-start _console-live-check
    @echo "Forge AI stack is up:"
    @echo "  app:       {{app_url}}"
    @echo "  agent:     {{agent_url}}"
    @echo "  knowledge: {{knowledge_url}}"
    @echo "  jarvis:    {{jarvis_url}}"
    @echo "  postgres:  postgresql://localhost:54329/forge_agent"
    @echo "  sqlite:    {{sqlite_path}}"

stop: _app-stop _jarvis-stop _knowledge-stop _ollama-stop _postgres-stop
    @echo "Forge AI stack stopped."

status:
    @scripts/status.sh

# Build runtime artifacts and install/update systemd units for this checkout.
systemd-install:
    @mvn -pl services/forge-agent/boot -am -DskipTests package
    @scripts/console/build.sh
    @mvn -pl services/forge-nexus/boot -am -DskipTests package
    @if [[ ! -x "{{root}}/services/forge-knowledge/.venv/bin/uvicorn" ]]; then scripts/knowledge/bootstrap.sh; fi
    @if [[ ! -x "{{root}}/services/forge-jarvis/.venv/bin/uvicorn" ]]; then scripts/jarvis/bootstrap.sh; fi
    @scripts/systemd/install.sh

# Start the installed Forge systemd services. Docker Postgres remains Docker-managed.
systemd-start:
    @scripts/systemd/control.sh start

# Stop the installed Forge systemd services. Docker Postgres remains Docker-managed.
systemd-stop:
    @scripts/systemd/control.sh stop

# Restart the installed Forge systemd services. Docker Postgres remains Docker-managed.
systemd-restart:
    @scripts/systemd/control.sh restart

# Show status for installed Forge systemd services and the Docker-managed Postgres container.
systemd-status:
    @scripts/systemd/control.sh status

_start-preflight:
    #!/usr/bin/env bash
    set -euo pipefail
    missing=()
    for command in curl docker java mvn npm python3; do
        if ! command -v "${command}" >/dev/null 2>&1; then
            missing+=("${command}")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        echo "Cannot start Forge AI stack; missing required command(s): ${missing[*]}" >&2
        exit 1
    fi
    if ! docker compose version >/dev/null 2>&1; then
        echo "Cannot start Forge AI stack; Docker Compose is required." >&2
        exit 1
    fi

_postgres-start:
    @docker compose up -d forge-agent-postgres

_postgres-stop:
    @docker compose stop forge-agent-postgres >/dev/null || true

_sqlite-start:
    @mkdir -p "{{root}}/var/knowledge"
    @if [[ -f "{{sqlite_path}}" ]]; then \
        echo "SQLite store is ready: {{sqlite_path}}"; \
    else \
        echo "SQLite store will be created by Knowledge service: {{sqlite_path}}"; \
    fi

_knowledge-start: _sqlite-start
    @if [[ ! -x "{{root}}/services/forge-knowledge/.venv/bin/uvicorn" ]]; then \
        scripts/knowledge/bootstrap.sh; \
    fi
    @scripts/knowledge/start.sh

_knowledge-stop:
    @scripts/knowledge/stop.sh

_knowledge-rebuild-restart: _knowledge-stop
    @scripts/knowledge/bootstrap.sh
    @just --justfile "{{root}}/Justfile" _knowledge-start
    @echo "Knowledge rebuilt and restarted."

_jarvis-start:
    @if [[ ! -x "{{root}}/services/forge-jarvis/.venv/bin/uvicorn" ]]; then \
        scripts/jarvis/bootstrap.sh; \
    fi
    @scripts/jarvis/start.sh

_jarvis-stop:
    @scripts/jarvis/stop.sh

_jarvis-rebuild-restart: _jarvis-stop
    @scripts/jarvis/bootstrap.sh
    @just --justfile "{{root}}/Justfile" _jarvis-start
    @echo "Jarvis rebuilt and restarted."

_ollama-start:
    @scripts/ollama/start-optional.sh

_ollama-stop:
    @scripts/ollama/stop-owned.sh

_console-build:
    @scripts/console/build.sh

_agent-restart: _start-preflight _postgres-start
    @just --justfile "{{root}}/Justfile" _java-service-rebuild-restart agent

_nexus-restart: _start-preflight _console-build
    @just --justfile "{{root}}/Justfile" _java-service-rebuild-restart nexus
    @just --justfile "{{root}}/Justfile" _console-live-check

_java-service-rebuild-restart service:
    #!/usr/bin/env bash
    set -euo pipefail
    source "{{root}}/scripts/lib/process.sh"
    case "{{service}}" in
        agent)
            display_name="Forge Agent"
            pid_file="{{agent_pid}}"
            log_file="{{agent_log}}"
            port="7091"
            health_url="{{agent_url}}/actuator/health"
            module="services/forge-agent/boot"
            jar="services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar"
            start_command=(
                env
                FORGE_AGENT_DB_URL="jdbc:postgresql://localhost:54329/forge_agent"
                FORGE_AGENT_DB_USERNAME="forge_agent"
                FORGE_AGENT_DB_PASSWORD="forge_agent"
                FORGE_AGENT_PORT="7091"
                java -jar "${jar}"
                --spring.docker.compose.enabled=false
            )
            ;;
        nexus)
            display_name="Forge Nexus"
            pid_file="{{app_pid}}"
            log_file="{{app_log}}"
            port="9099"
            health_url="{{app_url}}/actuator/health"
            module="services/forge-nexus/boot"
            jar="services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar"
            start_command=(
                env
                WORKSPACE_ROOT="{{root}}/.."
                java -jar "${jar}"
                --spring.docker.compose.enabled=false
            )
            ;;
        *)
            echo "Unsupported Java service '{{service}}'." >&2
            exit 2
            ;;
    esac

    if [[ -f "${pid_file}" ]] && kill -0 "$(cat "${pid_file}")" >/dev/null 2>&1; then
        pid="$(cat "${pid_file}")"
        echo "Stopping ${display_name} pid ${pid}"
        kill "${pid}" >/dev/null 2>&1 || true
        rm -f "${pid_file}"
        sleep 1
    fi
    if command -v lsof >/dev/null 2>&1; then
        listener_pids="$(lsof -t -iTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "${listener_pids}" ]]; then
            echo "Stopping ${display_name} listener pid(s) ${listener_pids}"
            for pid in ${listener_pids}; do
                kill "${pid}" >/dev/null 2>&1 || true
            done
            rm -f "${pid_file}"
            sleep 1
        fi
        for _ in {1..20}; do
            if ! lsof -t -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
                break
            fi
            sleep 0.5
        done
        if lsof -t -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
            echo "${display_name} port ${port} is still occupied after stop." >&2
            exit 1
        fi
    fi

    echo "Rebuilding ${display_name}..."
    mvn -pl "${module}" -am -DskipTests package
    mkdir -p "{{root}}/var/logs"
    : > "${log_file}"
    forge_start_background "${pid_file}" "${log_file}" "{{root}}" "${start_command[@]}"
    for _ in {1..40}; do
        if curl -fsS "${health_url}" >/dev/null 2>&1; then
            echo "${display_name} rebuilt and UP: ${health_url}"
            exit 0
        fi
        sleep 2
    done
    echo "${display_name} did not become healthy. Last log lines:" >&2
    tail -n 80 "${log_file}" || true
    exit 1

_app-start:
    #!/usr/bin/env bash
    set -euo pipefail
    source "{{root}}/scripts/lib/process.sh"
    mkdir -p "{{root}}/var/logs"
    if [[ -f "{{agent_pid}}" ]] && kill -0 "$(cat "{{agent_pid}}")" >/dev/null 2>&1; then
        echo "Stopping Forge Agent pid $(cat "{{agent_pid}}")"
        kill "$(cat "{{agent_pid}}")" >/dev/null 2>&1 || true
        rm -f "{{agent_pid}}"
        sleep 1
    fi
    if command -v lsof >/dev/null 2>&1; then
        agent_port_pid="$(lsof -t -iTCP:7091 -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "${agent_port_pid}" ]]; then
            echo "Stopping Forge Agent listener pid ${agent_port_pid}"
            for pid in ${agent_port_pid}; do
                kill "${pid}" >/dev/null 2>&1 || true
            done
            rm -f "{{agent_pid}}"
            sleep 1
        fi
    fi
    if command -v lsof >/dev/null 2>&1; then
        for i in {1..20}; do
            if ! lsof -t -iTCP:7091 -sTCP:LISTEN >/dev/null 2>&1; then
                break
            fi
            sleep 0.5
        done
        if lsof -t -iTCP:7091 -sTCP:LISTEN >/dev/null 2>&1; then
            echo "Forge Agent port 7091 is still occupied after stop."
            lsof -iTCP:7091 -sTCP:LISTEN || true
            exit 1
        fi
    fi
    mvn -pl services/forge-agent/boot -am -DskipTests package
    : > "{{agent_log}}"
    forge_start_background \
        "{{agent_pid}}" \
        "{{agent_log}}" \
        "{{root}}" \
        env \
        FORGE_AGENT_DB_URL="jdbc:postgresql://localhost:54329/forge_agent" \
        FORGE_AGENT_DB_USERNAME="forge_agent" \
        FORGE_AGENT_DB_PASSWORD="forge_agent" \
        FORGE_AGENT_PORT="7091" \
        java -jar services/forge-agent/boot/target/boot-0.0.1-SNAPSHOT.jar \
        --spring.docker.compose.enabled=false
    sleep 1
    if [[ -f "{{agent_pid}}" ]]; then
        echo "Forge Agent pid $(cat "{{agent_pid}}"), log {{agent_log}}"
    else
        echo "Forge Agent starting, log {{agent_log}}"
    fi
    for i in {1..40}; do
        if curl -fsS "{{agent_url}}/actuator/health" >/dev/null 2>&1; then
            echo "Forge Agent is UP at {{agent_url}}"
            break
        fi
        sleep 2
    done
    if ! curl -fsS "{{agent_url}}/actuator/health" >/dev/null 2>&1; then
        echo "Forge Agent did not become healthy. Last log lines:"
        tail -n 80 "{{agent_log}}" || true
        exit 1
    fi
    if [[ -f "{{app_pid}}" ]] && kill -0 "$(cat "{{app_pid}}")" >/dev/null 2>&1; then
        echo "Stopping Forge AI app pid $(cat "{{app_pid}}")"
        kill "$(cat "{{app_pid}}")" >/dev/null 2>&1 || true
        rm -f "{{app_pid}}"
        sleep 1
    fi
    if command -v lsof >/dev/null 2>&1; then
        app_port_pid="$(lsof -t -iTCP:9099 -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "${app_port_pid}" ]]; then
            echo "Stopping Forge AI app listener pid ${app_port_pid}"
            for pid in ${app_port_pid}; do
                kill "${pid}" >/dev/null 2>&1 || true
            done
            rm -f "{{app_pid}}"
            sleep 1
        fi
    fi
    if command -v lsof >/dev/null 2>&1; then
        for i in {1..20}; do
            if ! lsof -t -iTCP:9099 -sTCP:LISTEN >/dev/null 2>&1; then
                break
            fi
            sleep 0.5
        done
        if lsof -t -iTCP:9099 -sTCP:LISTEN >/dev/null 2>&1; then
            echo "Forge AI app port 9099 is still occupied after stop."
            lsof -iTCP:9099 -sTCP:LISTEN || true
            exit 1
        fi
    fi
    mvn -pl services/forge-nexus/boot -am -DskipTests package
    : > "{{app_log}}"
    forge_start_background \
        "{{app_pid}}" \
        "{{app_log}}" \
        "{{root}}" \
        env \
        WORKSPACE_ROOT="{{root}}/.." \
        java -jar services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar \
        --spring.docker.compose.enabled=false
    sleep 1
    if [[ -f "{{app_pid}}" ]]; then
        echo "Forge AI app pid $(cat "{{app_pid}}"), log {{app_log}}"
    else
        echo "Forge AI app starting, log {{app_log}}"
    fi
    for i in {1..40}; do
        if curl -fsS "{{app_url}}/actuator/health" >/dev/null 2>&1; then
            echo "Forge AI app is UP at {{app_url}}"
            exit 0
        fi
        sleep 2
    done
    echo "Forge AI app did not become healthy. Last log lines:"
    tail -n 80 "{{app_log}}" || true
    exit 1

_console-live-check:
    #!/usr/bin/env bash
    set -euo pipefail
    source "{{root}}/scripts/lib/portable.sh"
    describe_file() {
        local path="$1"
        local hash
        hash="$(forge_sha256 "${path}" 2>/dev/null || printf '%s' unavailable)"
        printf '%s %s bytes %s' "${path}" "$(forge_file_size "${path}")" "${hash}"
    }
    live_file="$(mktemp)"
    curl --retry 10 --retry-delay 1 --retry-connrefused -fsS "{{app_url}}/operator/operator-ui.js" > "${live_file}"
    built_file="{{root}}/services/forge-console/dist/operator/operator-ui.js"
    if ! cmp -s "${built_file}" "${live_file}"; then
        echo "Live operator-ui.js does not match built Console asset."
        echo "Built: $(describe_file "${built_file}")"
        echo "Live:  $(describe_file "${live_file}")"
        exit 1
    fi
    echo "Console live operator-ui.js matches built asset ($(forge_file_size "${built_file}") bytes, $(forge_sha256 "${built_file}" 2>/dev/null || printf '%s' unavailable))."

_app-stop:
    #!/usr/bin/env bash
    set -euo pipefail
    stopped=0
    if [[ -f "{{app_pid}}" ]] && kill -0 "$(cat "{{app_pid}}")" >/dev/null 2>&1; then
        pid="$(cat "{{app_pid}}")"
        kill "${pid}" >/dev/null 2>&1 || true
        echo "Stopped Forge AI app pid ${pid}"
        stopped=1
    fi
    rm -f "{{app_pid}}"
    if command -v lsof >/dev/null 2>&1; then
        pids="$(lsof -t -iTCP:9099 -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "${pids}" ]]; then
            echo "Stopping Forge AI app listener pid(s) ${pids}"
            for pid in ${pids}; do
                kill "${pid}" >/dev/null 2>&1 || true
            done
            stopped=1
        fi
    fi
    if [[ -f "{{agent_pid}}" ]] && kill -0 "$(cat "{{agent_pid}}")" >/dev/null 2>&1; then
        pid="$(cat "{{agent_pid}}")"
        kill "${pid}" >/dev/null 2>&1 || true
        echo "Stopped Forge Agent pid ${pid}"
        stopped=1
    fi
    rm -f "{{agent_pid}}"
    if command -v lsof >/dev/null 2>&1; then
        pids="$(lsof -t -iTCP:7091 -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "${pids}" ]]; then
            echo "Stopping Forge Agent listener pid(s) ${pids}"
            for pid in ${pids}; do
                kill "${pid}" >/dev/null 2>&1 || true
            done
            stopped=1
        fi
    fi
    if [[ "${stopped}" == "0" ]]; then
        echo "Forge AI app and Forge Agent are not running."
    fi

_logs:
    @tail -n 120 -f "{{app_log}}"

_knowledge-logs:
    @tail -n 120 -f "{{root}}/var/knowledge/logs/knowledge-service.stdout.log"

test:
    @scripts/test.sh

lint:
    @scripts/lint.sh

typecheck:
    @scripts/typecheck.sh
