# Local AI Platform Components

The local platform boundary is:

```text
User / Browser / Phone / Codex
        |
        v
Forge Nexus
  Java facade / gateway / public local API
        |
        +--> Forge Console
        |
        +--> Forge Knowledge
        |
        +--> Forge Jarvis
```

## Forge Nexus

Forge Nexus is the Java facade and public local API entrypoint. It owns the moved Maven modules:

- `services/forge-nexus/boot`
- `services/forge-nexus/api-rest`
- `services/forge-nexus/application`
- `services/forge-nexus/domain`
- `services/forge-nexus/infrastructure/jarvis-client`
- `services/forge-nexus/infrastructure/knowledge-client`
- `services/forge-nexus/infrastructure/knowledge-sqlite`
- other Java infrastructure modules under `services/forge-nexus/infrastructure/*`

Existing public paths remain unchanged under `/fgaisox`, including actuator health and `/api/v1/infrastructure/jarvis/*` plus `/api/v1/infrastructure/knowledge/*`.

## Forge Console

Forge Console is the Operator UI under `services/forge-console/src/operator`. Forge Nexus includes `services/forge-console/src` as a Boot resource source and packages it as `static/operator`, so served UI URLs remain unchanged. The UI calls Forge Nexus through `/api/v1/infrastructure`; it does not call Jarvis or Knowledge service ports directly.

## Forge Knowledge

Forge Knowledge is the flat Python project under `services/forge-knowledge`, with `pyproject.toml`, `src/knowledge_service`, and `tests` directly in that service root. It owns inventory, context, analysis, graph, and GraphSlice runtime. Local endpoints under `/api/v1/knowledge/*` remain internal and unchanged.

## Forge Jarvis

Forge Jarvis is the flat Python project under `services/forge-jarvis`, with `pyproject.toml`, `src/jarvis_agent`, and `tests` directly in that service root. It owns assistant chat, command classification, and allowlisted local actions. Local endpoints under `/api/v1/jarvis/*` remain internal and unchanged.

## Physical Layout

The root implementation directories `boot`, `api-rest`, `application`, `domain`, `infrastructure`, and `jacoco-report` were removed from the root layout. The implementation now lives under `services/forge-*`. Root `config/`, `docs/`, `scripts/`, and `var/` are the canonical platform-level locations.
