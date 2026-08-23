# Forge Nexus

Forge Nexus is a typed proxy gateway. It owns no agent, workflow, execution, local workspace, Git, runtime-state, or service-management semantics.

Physical Maven modules:

- `services/forge-nexus/boot`
- `services/forge-nexus/api-rest`
- `services/forge-nexus/application`
- `services/forge-nexus/domain`
- `services/forge-nexus/clients/knowledge-client`
- `services/forge-nexus/clients/agent-client`

`services/forge-nexus/pom.xml` is the Java Nexus Maven aggregator. The repository root `pom.xml` is only the lightweight platform aggregator and points at `services/forge-nexus`.

Public local base URL:

- `http://127.0.0.1:9099/fgaisox`

Surviving paths expose typed Forge Agent and Forge Knowledge client operations plus the infrastructure proxy boundary for Knowledge and Jarvis.
