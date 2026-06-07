# Forge AI / Jarvis Contract

Implemented communication model:

```text
Operator UI
  -> Forge AI REST API
  -> JarvisGateway
  -> HttpJarvisGateway
  -> Jarvis local FastAPI service
  -> Ollama / allowlisted local actions
```

The UI does not call Jarvis directly.

## Forge AI Backend Endpoints

```http
GET  /fgaisox/api/v1/infrastructure/jarvis/status
GET  /fgaisox/api/v1/infrastructure/jarvis/actions
POST /fgaisox/api/v1/infrastructure/jarvis/command
```

Controller:

```text
api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureJarvisController.java
```

Application port:

```text
application/src/main/java/com/sitionix/forgeai/application/infrastructure/jarvis/JarvisGateway.java
```

HTTP adapter:

```text
infrastructure/jarvis-client/src/main/java/com/sitionix/forgeai/infrastructure/jarvisclient/HttpJarvisGateway.java
```

Config:

```text
forge.ai.infrastructure.jarvis.base-url=http://127.0.0.1:7071
```

Allowed hosts are `127.0.0.1` and `localhost`. Non-localhost base URLs fail validation.

## Status

Forge endpoint:

```http
GET /fgaisox/api/v1/infrastructure/jarvis/status
```

Jarvis endpoint:

```http
GET /api/v1/jarvis/status
```

Response shape:

```json
{
  "status": "UP",
  "host": "127.0.0.1",
  "port": 7071,
  "model": {
    "defaultModel": "qwen2.5-coder:7b"
  },
  "ollama": {
    "baseUrl": "http://localhost:11434",
    "status": "UP"
  },
  "actions": {
    "count": 2
  }
}
```

Status does not execute actions.

## Actions

Forge endpoint:

```http
GET /fgaisox/api/v1/infrastructure/jarvis/actions
```

Response shape:

```json
{
  "actions": [
    {
      "action": "ollama_status",
      "description": "Check Ollama local API",
      "targets": ["health"]
    },
    {
      "action": "system_status",
      "description": "Show basic local system status",
      "targets": ["basic"]
    }
  ]
}
```

Raw command arrays are not exposed.

## Command

Forge endpoint:

```http
POST /fgaisox/api/v1/infrastructure/jarvis/command
```

Request:

```json
{
  "text": "перевір ollama"
}
```

Success:

```json
{
  "input": "перевір ollama",
  "intent": {
    "action": "ollama_status",
    "target": "health",
    "arguments": {}
  },
  "execution": {
    "executed": true,
    "message": "Action executed: ollama_status.health",
    "output": "Ollama is reachable"
  }
}
```

Forge AI does not parse natural language and does not execute shell commands. It proxies the text to Jarvis.

## Error Model

| Condition | HTTP | Code |
| --- | ---: | --- |
| Blank command | `400` | `INVALID_COMMAND` |
| Jarvis unavailable | `503` | `JARVIS_UNAVAILABLE` |
| Jarvis timeout | `504` | `JARVIS_TIMEOUT` |
| Bad/invalid Jarvis response | `502` | `JARVIS_BAD_RESPONSE` |
| Unsupported action from Jarvis | `403` | `UNSUPPORTED_ACTION` |
| Ollama unavailable from Jarvis | `503` | `OLLAMA_UNAVAILABLE` |
| Jarvis action execution failed | `502` | `ACTION_EXECUTION_FAILED` |

Controlled error response:

```json
{
  "code": "JARVIS_UNAVAILABLE",
  "message": "Jarvis is unavailable"
}
```

## Security Constraints

- Forge AI must not read or execute Jarvis allowlist commands.
- Forge AI must not expose raw command arrays.
- Forge AI must only proxy to localhost Jarvis.
- Browser JavaScript must only call Forge AI backend endpoints.
- Jarvis remains the only executor.
- Jarvis must not mutate Forge AI ticket/lane state.
- Jarvis must not depend on Forge AI ticket/lane domain.

## Next Task

Add automated browser-level/static UI verification for `jarvis.html` and the sidebar once the project has an established UI test harness.
