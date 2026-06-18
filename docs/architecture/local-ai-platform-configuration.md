# Local AI Platform Configuration

Root config now lives under `config/` and is the preferred source of truth.

Tracked root files:

- `config/forge-ai.yaml`
- `config/services.yaml`
- `config/agent.yml`
- `config/lane-strategies.yml`
- `config/instructions.yaml`
- `config/local.example.yaml`
- `config/knowledge/knowledge.defaults.yaml`
- `config/knowledge/knowledge-sources.yaml`
- `config/knowledge/knowledge-sources.example.yaml`
- `config/knowledge/analysis-prompt.md`
- `config/jarvis/model.yaml`
- `config/jarvis/allowed-actions.yaml`
- `config/jarvis/system-prompt.md`
- `config/jarvis/chat-prompt.md`

## Environment Variables

Common variables:

- `FORGE_AI_HOME`: repo root
- `FORGE_CONFIG_DIR`: root config directory, default `${FORGE_AI_HOME}/config`
- `FORGE_RUNTIME_DIR`: root runtime directory, default `${FORGE_AI_HOME}/var`
- `FORGE_WORKSPACE_ROOT`: parent workspace containing service repositories, default `${FORGE_AI_HOME}/..`
- `FORGE_NEXUS_BASE_URL`: default `http://127.0.0.1:9099/fgaisox`
- `FORGE_KNOWLEDGE_BASE_URL`: default `http://127.0.0.1:7081`
- `FORGE_JARVIS_BASE_URL`: default `http://127.0.0.1:7071`

Preserved service-specific variables:

- Jarvis: `JARVIS_CONFIG_DIR`, `JARVIS_REPO_ROOT`, `JARVIS_LOG_FILE`, `JARVIS_HOST`, `JARVIS_PORT`, `OLLAMA_HOME`
- Knowledge: `KNOWLEDGE_CONFIG`, `KNOWLEDGE_CONFIG_DIR`, `KNOWLEDGE_MODULE_DIR`, `KNOWLEDGE_HOST`, `KNOWLEDGE_PORT`, `KNOWLEDGE_STORE_PATH`, `KNOWLEDGE_ANALYSIS_*`

## Resolution Order

Spring Boot imports root files first and keeps classpath fallbacks:

1. `file:${FORGE_CONFIG_DIR}/forge-ai.yaml`
2. `file:${FORGE_CONFIG_DIR}/services.yaml`
3. `file:${FORGE_CONFIG_DIR}/agent.yml`
4. `file:${FORGE_CONFIG_DIR}/instructions.yaml`
5. `file:${FORGE_CONFIG_DIR}/lane-strategies.yml`
6. classpath `services.yaml`, `agent.yml`, `instructions.yaml`, `lane-strategies.yml`

The Operator resource editor also prefers `${FORGE_CONFIG_DIR}/agent.yml` and `${FORGE_CONFIG_DIR}/lane-strategies.yml`, then falls back to `services/forge-nexus/boot/src/main/resources` when root files are absent.

Knowledge config resolution:

1. `KNOWLEDGE_CONFIG` file path
2. `KNOWLEDGE_CONFIG_DIR`
3. `FORGE_CONFIG_DIR/knowledge`
4. `FORGE_CONFIG_DIR/config/knowledge` for callers that point `FORGE_CONFIG_DIR` at the repo root
5. `./config/knowledge`
6. `${FORGE_AI_HOME}/config/knowledge`

Jarvis config resolution:

1. `JARVIS_CONFIG_DIR`
2. `FORGE_CONFIG_DIR/jarvis`
3. `FORGE_CONFIG_DIR/config/jarvis` for callers that point `FORGE_CONFIG_DIR` at the repo root
4. `./config/jarvis`
5. `${FORGE_AI_HOME}/config/jarvis`

## Path Expansion

Knowledge source config supports:

- absolute paths
- config-file-relative paths
- root-relative paths
- `${ENV_VAR}`
- `${ENV_VAR:default}`
- `~`

Missing service catalogs and invalid workspace roots fail with explicit diagnostics. Developer-specific absolute paths are not used as fallback.

## Runtime Data

The Knowledge SQLite default is `${FORGE_RUNTIME_DIR}/knowledge/knowledge.sqlite`, which resolves to `var/knowledge/knowledge.sqlite` from the repo root by default. Jarvis runtime logs and local model runtime data resolve under `${FORGE_RUNTIME_DIR}/jarvis`. Existing runtime data was moved to root `var/`; it was not reset or regenerated.
