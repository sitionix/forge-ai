set shell := ["bash", "-euo", "pipefail", "-c"]

root := justfile_directory()
app_pid := root + "/var/forge-ai.pid"
app_log := root + "/var/logs/forge-ai.log"
app_url := "http://127.0.0.1:9099/fgaisox"
knowledge_url := "http://127.0.0.1:7081"
jarvis_url := "http://127.0.0.1:7071"
sqlite_path := root + "/var/knowledge/knowledge.sqlite"

start: _app-stop _jarvis-stop _knowledge-stop _mongo-start _sqlite-start _console-build _knowledge-start _jarvis-start _app-start _console-live-check
    @echo "Forge AI stack is up:"
    @echo "  app:       {{app_url}}"
    @echo "  knowledge: {{knowledge_url}}"
    @echo "  jarvis:    {{jarvis_url}}"
    @echo "  mongo:     mongodb://localhost:27019/forge_ai"
    @echo "  sqlite:    {{sqlite_path}}"

stop: _app-stop _jarvis-stop _knowledge-stop _mongo-stop
    @echo "Forge AI stack stopped."

status:
    @scripts/status.sh

_mongo-start:
    @docker compose up -d forge-ai-mongo

_mongo-stop:
    @docker compose stop forge-ai-mongo >/dev/null || true

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

_console-build:
    @echo "Building Forge Console static assets..."
    @npm --prefix "{{root}}/services/forge-console" run build

_app-start:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "{{root}}/var/logs"
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
    (
        cd "{{root}}"
        setsid -f env \
            WORKSPACE_ROOT="{{root}}/.." \
            MONGODB_URI="mongodb://localhost:27019/forge_ai" \
            java -jar services/forge-nexus/boot/target/boot-0.0.1-SNAPSHOT.jar \
            --spring.docker.compose.enabled=false \
            >> "{{app_log}}" 2>&1
    )
    sleep 1
    app_port_pid="$(lsof -t -iTCP:9099 -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "${app_port_pid}" ]]; then
        echo "${app_port_pid}" > "{{app_pid}}"
    fi
    if [[ -f "{{app_pid}}" ]]; then
        echo "Forge AI app pid $(cat "{{app_pid}}"), log {{app_log}}"
    else
        echo "Forge AI app starting, log {{app_log}}"
    fi
    for i in {1..40}; do
        if curl -fsS "{{app_url}}/actuator/health" >/dev/null 2>&1; then
            app_port_pid="$(lsof -t -iTCP:9099 -sTCP:LISTEN 2>/dev/null || true)"
            if [[ -n "${app_port_pid}" ]]; then
                echo "${app_port_pid}" > "{{app_pid}}"
            fi
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
    live_file="$(mktemp)"
    curl --retry 10 --retry-delay 1 --retry-connrefused -fsS "{{app_url}}/operator/operator-ui.js" > "${live_file}"
    built_file="{{root}}/services/forge-console/dist/operator/operator-ui.js"
    if ! cmp -s "${built_file}" "${live_file}"; then
        echo "Live operator-ui.js does not match built Console asset."
        echo "Built: ${built_file} $(wc -c < "${built_file}") bytes $(sha256sum "${built_file}" | awk '{print $1}')"
        echo "Live:  ${live_file} $(wc -c < "${live_file}") bytes $(sha256sum "${live_file}" | awk '{print $1}')"
        exit 1
    fi
    echo "Console live operator-ui.js matches built asset ($(wc -c < "${built_file}") bytes, $(sha256sum "${built_file}" | awk '{print $1}'))."

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
    if [[ "${stopped}" == "0" ]]; then
        echo "Forge AI app is not running."
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
