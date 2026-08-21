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

start: _start-preflight _app-stop _jarvis-stop _knowledge-stop _ollama-stop _postgres-start _sqlite-start _ollama-start _console-build _knowledge-start _jarvis-start _app-start _console-live-check
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

_jarvis-start:
    @if [[ ! -x "{{root}}/services/forge-jarvis/.venv/bin/uvicorn" ]]; then \
        scripts/jarvis/bootstrap.sh; \
    fi
    @scripts/jarvis/start.sh

_jarvis-stop:
    @scripts/jarvis/stop.sh

_ollama-start:
    @scripts/ollama/start-optional.sh

_ollama-stop:
    @scripts/ollama/stop-owned.sh

_console-build:
    @scripts/console/build.sh

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
