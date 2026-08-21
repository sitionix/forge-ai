# Local AI Platform Configuration

Root configuration lives under `config/`, with ownership split by service.

- `config/forge-ai.yaml` contains shared process endpoints and platform defaults.
- `config/services.yaml` is the service catalog consumed by Forge Knowledge and other catalog-aware services.
- `config/knowledge/**` belongs to Forge Knowledge.
- `config/jarvis/**` belongs to Forge Jarvis.
- Agent definitions, workflows, scope, execution, and instructions belong to Forge Agent.

Forge Nexus imports only its optional `forge-ai.yaml` file. Its typed clients are configured with downstream base URLs and timeouts; Nexus does not load local agent, workflow, instruction, lane, workspace, Git, or service-management configuration.

Common environment variables include `FORGE_AI_HOME`, `FORGE_CONFIG_DIR`, `FORGE_RUNTIME_DIR`, `FORGE_AGENT_BASE_URL`, `FORGE_KNOWLEDGE_BASE_URL`, and `FORGE_JARVIS_BASE_URL`.

Knowledge and Jarvis retain their service-specific configuration resolution documented in their own service directories.
