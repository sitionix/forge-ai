# Forge AI

Forge AI is a local platform composed of independently owned services.

- `services/forge-agent` owns agents, workflows, scope, execution, NodeRuns, task execution, and Codex orchestration.
- `services/forge-nexus` owns platform-facing APIs and thin typed proxies to infrastructure services, including Forge Agent.
- `services/forge-console` provides the operator UI.
- `services/forge-knowledge` and `services/forge-jarvis` own their respective knowledge and assistant capabilities.

Nexus forwards typed Forge Agent requests and responses through its controller, mapper, use-case, domain-port, adapter, call-executor, and typed-client boundary. It does not interpret agent or workflow execution semantics.

Runtime configuration is under `config/`. Agent and workflow configuration belongs to Forge Agent.

See each service README and `docs/` for service-specific and historical architecture material.
