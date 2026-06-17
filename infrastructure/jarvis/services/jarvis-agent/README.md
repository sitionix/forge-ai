# Jarvis Agent Service

FastAPI service for converting local text commands into strict JSON intents through Ollama, then executing only allowlisted actions from `infrastructure/jarvis/config/allowed-actions.yaml`.

Run from the Forge AI repository root:

```bash
scripts/jarvis/start.sh
```

Or run the service directly:

```bash
cd infrastructure/jarvis
cd services/jarvis-agent
python3 -m venv .venv
. .venv/bin/activate
pip install -e ".[test]"
cd ../../..
JARVIS_REPO_ROOT="$PWD" \
JARVIS_CONFIG_DIR="$PWD/config" \
JARVIS_LOG_FILE="$PWD/var/logs/jarvis-agent.log" \
services/jarvis-agent/.venv/bin/uvicorn jarvis_agent.main:app \
  --app-dir services/jarvis-agent/src \
  --host 127.0.0.1 \
  --port 7071
```
