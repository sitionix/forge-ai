# Jarvis Agent Service

FastAPI service for converting local text commands into strict JSON intents through Ollama, then executing only allowlisted actions from `config/jarvis/allowed-actions.yaml`.

Run from the Forge AI repository root:

```bash
scripts/jarvis/start.sh
```

Or run the service directly:

```bash
cd services/forge-jarvis
python3 -m venv .venv
. .venv/bin/activate
pip install -e ".[test]"
cd ../..
JARVIS_REPO_ROOT="$PWD" \
JARVIS_CONFIG_DIR="$PWD/config/jarvis" \
JARVIS_LOG_FILE="$PWD/var/jarvis/logs/jarvis-agent.log" \
services/forge-jarvis/.venv/bin/uvicorn jarvis_agent.main:app \
  --app-dir services/forge-jarvis/src \
  --host 127.0.0.1 \
  --port 7071
```
