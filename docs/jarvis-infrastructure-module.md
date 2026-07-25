# Jarvis Infrastructure Module

Jarvis is a bounded infrastructure module inside Forge AI. It owns local assistant command classification and safe allowlisted execution. It is not a Forge AI lane, not an `agent.yml` agent, and not a ticket/lane orchestration engine.

Forge AI Operator UI is the intended UI surface. Ollama is the model runtime only.

## Location

```text
infrastructure/jarvis/
  config/
    allowed-actions.yaml
    model.yaml
    system-prompt.md
  scripts/
    bootstrap.sh
    start.sh
    status.sh
    smoke-test.sh
    stop.sh
  services/
    jarvis-agent/
      src/jarvis_agent/
      tests/
```

Root wrappers remain in:

```text
scripts/jarvis/
```

They delegate to `infrastructure/jarvis/scripts/*`.

## Runtime Commands

```bash
scripts/jarvis/bootstrap.sh
JARVIS_PORT=7071 scripts/jarvis/start.sh
JARVIS_PORT=7071 scripts/jarvis/status.sh
JARVIS_PORT=7071 scripts/jarvis/smoke-test.sh
scripts/jarvis/stop.sh
```

`7071` is used while the old standalone Jarvis may still own `7070`.

## Local Jarvis API

```http
GET  /health
GET  /api/v1/jarvis/status
GET  /api/v1/jarvis/actions
POST /api/v1/jarvis/command
```

`/health` is lightweight. `/api/v1/jarvis/status` returns runtime status, configured model, Ollama status, and allowlisted action count. It does not execute actions and does not expose raw command arrays.

## Config

Model:

```text
infrastructure/jarvis/config/model.yaml
```

```yaml
default_model: qwen2.5-coder:7b
ollama_base_url: http://localhost:11434
request_timeout_seconds: 120
```

Allowlist:

```text
infrastructure/jarvis/config/allowed-actions.yaml
```

Current safe actions:

- `system_status.basic`
- `ollama_status.health`

No arbitrary shell action exists. Raw command arrays are module config, not UI/API data.

## Execution Flow

```text
User text
  -> Jarvis command endpoint
  -> Ollama intent classification
  -> strict JSON intent
  -> ActionRegistry allowlist lookup
  -> Security validation
  -> ActionExecutor subprocess.run(static configured command)
```

Forge AI never executes Jarvis shell actions. Forge AI proxies to Jarvis through `JarvisGateway`.

## Security Rules

- Jarvis binds to localhost by default.
- Unknown action is rejected.
- Unknown target is rejected.
- Invalid model JSON is rejected.
- Model output is never executed as a command.
- Raw user text is never passed to shell.
- `sudo`, package installation, destructive filesystem operations, and unsafe shell tokens are blocked.
- Status/actions endpoints never execute actions.
- Browser JavaScript must call Forge AI backend endpoints, not the Jarvis direct port.

## Forge AI Boundary

```text
Operator UI
  -> /fgaisox/api/v1/infrastructure/jarvis/*
  -> ManageJarvisInfrastructure
  -> JarvisGateway
  -> HttpJarvisGateway
  -> http://127.0.0.1:7071/api/v1/jarvis/*
```

Forbidden:

- `domain -> Jarvis`
- `Jarvis -> Forge AI ticket/lane domain`
- `Operator UI -> Jarvis direct port`
- `Forge AI -> arbitrary shell execution`

## Tests

```bash
PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 infrastructure/jarvis/services/jarvis-agent/.venv/bin/pytest infrastructure/jarvis/services/jarvis-agent/tests
```

Smoke:

```bash
JARVIS_PORT=7071 scripts/jarvis/smoke-test.sh
```
