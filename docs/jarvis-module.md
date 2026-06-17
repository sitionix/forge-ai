# Jarvis Module

Jarvis is a Forge-owned infrastructure subsystem under:

```text
infrastructure/jarvis
```

Use the current module document for the active contract:

```text
docs/jarvis-infrastructure-module.md
```

Local APIs:

```http
GET  /health
GET  /api/v1/jarvis/status
GET  /api/v1/jarvis/actions
POST /api/v1/jarvis/command
```

Forge AI APIs:

```http
GET  /fgaisox/api/v1/infrastructure/jarvis/status
GET  /fgaisox/api/v1/infrastructure/jarvis/actions
POST /fgaisox/api/v1/infrastructure/jarvis/command
```

Safe sample command:

```bash
curl -s -X POST http://127.0.0.1:7071/api/v1/jarvis/command \
  -H "Content-Type: application/json" \
  -d '{"text":"перевір ollama"}'
```

Current allowlisted actions:

- `system_status.basic`
- `ollama_status.health`

Jarvis remains the only local action executor. Forge AI proxies command text through `JarvisGateway`; it does not parse natural language into actions and does not execute shell commands.
