# Local AI Platform Startup

From a fresh clone:

```bash
scripts/bootstrap.sh
scripts/validate-config.sh
scripts/start.sh
scripts/status.sh
```

`scripts/start.sh` delegates to the existing `Justfile` when `just` is available. Service control scripts live only under root `scripts/`:

```bash
scripts/knowledge/bootstrap.sh
scripts/knowledge/start.sh
scripts/knowledge/status.sh
scripts/knowledge/validate-config.sh

scripts/jarvis/bootstrap.sh
scripts/jarvis/start.sh
scripts/jarvis/status.sh
scripts/jarvis/smoke-test.sh
```

## Override Workspace Root

The service catalog uses paths relative to the workspace containing the repos. Override it with:

```bash
FORGE_WORKSPACE_ROOT=/absolute/path/to/workspace scripts/validate-config.sh
```

The default is the parent directory of `FORGE_AI_HOME`.

## Status Checks

Read-only checks:

```bash
scripts/status.sh
curl -fsS http://127.0.0.1:9099/fgaisox/actuator/health
curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/jarvis/status
curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/status
curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/services/status
```

Do not run inventory or analysis build endpoints as a health check; they can be expensive.

## Stop

```bash
scripts/stop.sh
```

This stops the Java app when managed by the local PID/Justfile flow, then stops Jarvis and Knowledge through the root scripts.

## Physical Service Roots

- Forge Nexus: `services/forge-nexus`
- Forge Console: `services/forge-console/src/operator`
- Forge Knowledge: `services/forge-knowledge`
- Forge Jarvis: `services/forge-jarvis`
