set shell := ["bash", "-euo", "pipefail", "-c"]

root := justfile_directory()
app_pid := root + "/var/forge-ai.pid"
app_log := root + "/var/logs/forge-ai.log"
app_url := "http://127.0.0.1:9099/fgaisox"
knowledge_url := "http://127.0.0.1:7081"
jarvis_url := "http://127.0.0.1:7071"
sqlite_path := root + "/infrastructure/knowledge/var/knowledge.sqlite"

start: _mongo-start _sqlite-start _knowledge-start _jarvis-start _app-start
    @echo "Forge AI stack is up:"
    @echo "  app:       {{app_url}}"
    @echo "  knowledge: {{knowledge_url}}"
    @echo "  jarvis:    {{jarvis_url}}"
    @echo "  mongo:     mongodb://localhost:27019/forge_ai"
    @echo "  sqlite:    {{sqlite_path}}"

stop: _app-stop _jarvis-stop _knowledge-stop _mongo-stop
    @echo "Forge AI stack stopped."

_mongo-start:
    @docker compose up -d forge-ai-mongo

_mongo-stop:
    @docker compose stop forge-ai-mongo >/dev/null || true

_sqlite-start:
    @mkdir -p "{{root}}/infrastructure/knowledge/var"
    @if [[ -f "{{sqlite_path}}" ]]; then \
        echo "SQLite store is ready: {{sqlite_path}}"; \
    else \
        echo "SQLite store will be created by Knowledge service: {{sqlite_path}}"; \
    fi

_knowledge-start: _sqlite-start
    @if [[ ! -x "{{root}}/infrastructure/knowledge/services/knowledge-service/.venv/bin/uvicorn" ]]; then \
        scripts/knowledge/bootstrap.sh; \
    fi
    @scripts/knowledge/start.sh

_knowledge-stop:
    @scripts/knowledge/stop.sh

_jarvis-start:
    @if [[ ! -x "{{root}}/infrastructure/jarvis/services/jarvis-agent/.venv/bin/uvicorn" ]]; then \
        scripts/jarvis/bootstrap.sh; \
    fi
    @scripts/jarvis/start.sh

_jarvis-stop:
    @scripts/jarvis/stop.sh

_app-start:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "{{root}}/var/logs"
    if curl -fsS "{{app_url}}/actuator/health" >/dev/null 2>&1; then
        echo "Forge AI app is already UP at {{app_url}}; restarting with a fresh build"
    fi
    if [[ -f "{{app_pid}}" ]] && kill -0 "$(cat "{{app_pid}}")" >/dev/null 2>&1; then
        echo "Stopping Forge AI app pid $(cat "{{app_pid}}")"
        kill "$(cat "{{app_pid}}")" >/dev/null 2>&1 || true
        rm -f "{{app_pid}}"
        sleep 1
    elif command -v lsof >/dev/null 2>&1; then
        app_port_pid="$(lsof -t -iTCP:9099 -sTCP:LISTEN 2>/dev/null || true)"
        if [[ -n "${app_port_pid}" ]]; then
            echo "Stopping Forge AI app listener pid ${app_port_pid}"
            kill "${app_port_pid}" >/dev/null 2>&1 || true
            rm -f "{{app_pid}}"
            sleep 1
        fi
    fi
    mvn -pl boot -am -DskipTests package
    : > "{{app_log}}"
    (
        cd "{{root}}"
        setsid -f env \
            WORKSPACE_ROOT="{{root}}/.." \
            MONGODB_URI="mongodb://localhost:27019/forge_ai" \
            java -jar boot/target/boot-0.0.1-SNAPSHOT.jar \
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

_app-stop:
    @if [[ -f "{{app_pid}}" ]] && kill -0 "$(cat "{{app_pid}}")" >/dev/null 2>&1; then \
        kill "$(cat "{{app_pid}}")"; \
        echo "Stopped Forge AI app pid $(cat "{{app_pid}}")"; \
    else \
        echo "Forge AI app PID file missing or process is not running."; \
    fi
    @rm -f "{{app_pid}}"

_logs:
    @tail -n 120 -f "{{app_log}}"

_knowledge-logs:
    @tail -n 120 -f "{{root}}/infrastructure/knowledge/var/logs/knowledge-service.stdout.log"
