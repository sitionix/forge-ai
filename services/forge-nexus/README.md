# Forge Nexus

Forge Nexus is the Java facade/gateway and the only external/operator local API entrypoint.

Physical Maven modules:

- `services/forge-nexus/boot`
- `services/forge-nexus/api-rest`
- `services/forge-nexus/application`
- `services/forge-nexus/domain`
- `services/forge-nexus/infrastructure/jarvis-client`
- `services/forge-nexus/infrastructure/knowledge-client`
- `services/forge-nexus/infrastructure/knowledge-sqlite`
- supporting Java infrastructure modules under `services/forge-nexus/infrastructure/*`

`services/forge-nexus/pom.xml` is the Java Nexus Maven aggregator. The repository root `pom.xml` is only the lightweight platform aggregator and points at `services/forge-nexus`.

Public local base URL:

- `http://127.0.0.1:9099/fgaisox`

Compatibility rule: existing `/fgaisox/actuator/health` and `/fgaisox/api/v1/infrastructure/*` paths are preserved.
