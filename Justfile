set shell := ["bash", "-euo", "pipefail", "-c"]

root := justfile_directory()
app_pid := root + "/var/forge-ai.pid"
app_log := root + "/var/logs/forge-ai.log"
app_url := "http://127.0.0.1:9099/fgaisox"
knowledge_url := "http://127.0.0.1:7081"
sqlite_path := root + "/infrastructure/knowledge/var/knowledge.sqlite"

default:
    @just --list

start: mongo-start sqlite-start knowledge-start app-start
    @echo "Forge AI stack is up:"
    @echo "  app:       {{app_url}}"
    @echo "  knowledge: {{knowledge_url}}"
    @echo "  mongo:     mongodb://localhost:27019/forge_ai"
    @echo "  sqlite:    {{sqlite_path}}"

stop: app-stop knowledge-stop mongo-stop
    @echo "Forge AI stack stopped."

restart: stop start

status:
    @echo "Mongo:"
    @docker compose ps forge-ai-mongo || true
    @echo
    @echo "SQLite:"
    @if [[ -f "{{sqlite_path}}" ]]; then \
        ls -lh "{{sqlite_path}}"; \
    else \
        echo "missing: {{sqlite_path}}"; \
    fi
    @echo
    @echo "Knowledge:"
    @scripts/knowledge/status.sh
    @echo
    @echo "Forge AI app:"
    @if curl -fsS "{{app_url}}/actuator/health" >/dev/null 2>&1; then \
        echo "UP at {{app_url}}"; \
    else \
        echo "DOWN at {{app_url}}"; \
    fi

mongo-start:
    @docker compose up -d forge-ai-mongo

mongo-stop:
    @docker compose stop forge-ai-mongo >/dev/null || true

sqlite-start:
    @mkdir -p "{{root}}/infrastructure/knowledge/var"
    @if [[ -f "{{sqlite_path}}" ]]; then \
        echo "SQLite store is ready: {{sqlite_path}}"; \
    else \
        echo "SQLite store will be created by Knowledge service: {{sqlite_path}}"; \
    fi

knowledge-start: sqlite-start
    @if [[ ! -x "{{root}}/infrastructure/knowledge/services/knowledge-service/.venv/bin/uvicorn" ]]; then \
        scripts/knowledge/bootstrap.sh; \
    fi
    @scripts/knowledge/start.sh

knowledge-stop:
    @scripts/knowledge/stop.sh

app-start:
    @mkdir -p "{{root}}/var/logs"
    @if curl -fsS "{{app_url}}/actuator/health" >/dev/null 2>&1; then \
        echo "Forge AI app is already UP at {{app_url}}"; \
        exit 0; \
    fi
    @if [[ -f "{{app_pid}}" ]] && kill -0 "$(cat "{{app_pid}}")" >/dev/null 2>&1; then \
        echo "Stopping stale Forge AI app pid $(cat "{{app_pid}}")"; \
        kill "$(cat "{{app_pid}}")" >/dev/null 2>&1 || true; \
        rm -f "{{app_pid}}"; \
        sleep 1; \
    fi
    @mvn -pl boot -am -DskipTests package
    @: > "{{app_log}}"
    @(cd "{{root}}" && \
        WORKSPACE_ROOT="{{root}}/.." \
        MONGODB_URI="mongodb://localhost:27019/forge_ai" \
        java -jar boot/target/boot-0.0.1-SNAPSHOT.jar \
            --spring.docker.compose.enabled=false \
            >> "{{app_log}}" 2>&1 & \
        echo $! > "{{app_pid}}")
    @echo "Forge AI app pid $(cat "{{app_pid}}"), log {{app_log}}"
    @for i in {1..40}; do \
        if curl -fsS "{{app_url}}/actuator/health" >/dev/null 2>&1; then \
            echo "Forge AI app is UP at {{app_url}}"; \
            exit 0; \
        fi; \
        sleep 2; \
    done; \
    echo "Forge AI app did not become healthy. Last log lines:"; \
    tail -n 80 "{{app_log}}" || true; \
    exit 1

app-stop:
    @if [[ -f "{{app_pid}}" ]] && kill -0 "$(cat "{{app_pid}}")" >/dev/null 2>&1; then \
        kill "$(cat "{{app_pid}}")"; \
        echo "Stopped Forge AI app pid $(cat "{{app_pid}}")"; \
    else \
        echo "Forge AI app PID file missing or process is not running."; \
    fi
    @rm -f "{{app_pid}}"

logs:
    @tail -n 120 -f "{{app_log}}"

knowledge-logs:
    @tail -n 120 -f "{{root}}/infrastructure/knowledge/var/logs/knowledge-service.stdout.log"
