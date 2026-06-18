# Local AI Platform Reorg Discovery

Discovery was run before implementation with `git status --short`, Maven/Python baseline checks, `find` over Maven/Python/config/script locations, Operator UI static files, and `rg` over service paths, config names, ports, URLs, and endpoint prefixes.

## Maven Modules

Root aggregator modules:

- `boot`
- `api-rest`
- `application`
- `domain`
- `infrastructure`
- `jacoco-report`

Infrastructure aggregator modules:

- `mongodb`
- `codex-cli`
- `resources`
- `github-cli`
- `local-cli`
- `jarvis-client`
- `knowledge-client`
- `knowledge-sqlite`

## Python Services

Knowledge:

- module root: `infrastructure/knowledge`
- service root: `infrastructure/knowledge/services/knowledge-service`
- pyproject: `infrastructure/knowledge/services/knowledge-service/pyproject.toml`
- package: `knowledge_service`
- tests: `infrastructure/knowledge/services/knowledge-service/tests`

Jarvis:

- module root: `infrastructure/jarvis`
- service root: `infrastructure/jarvis/services/jarvis-agent`
- pyproject: `infrastructure/jarvis/services/jarvis-agent/pyproject.toml`
- package: `jarvis_agent`
- tests: `infrastructure/jarvis/services/jarvis-agent/tests`

## Java Facade And Clients

Forge Nexus maps to existing Java modules rather than a physical move in this phase:

- public app: `boot`
- REST controllers: `api-rest`
- use cases: `application`
- domain contracts: `domain`
- Jarvis client: `infrastructure/jarvis-client`
- Knowledge client: `infrastructure/knowledge-client`
- legacy/local sqlite Knowledge adapter: `infrastructure/knowledge-sqlite`

## Forge Console

Operator UI assets are served from:

- `services/forge-console/src/operator/index.html`
- `services/forge-console/src/operator/jarvis.html`
- `services/forge-console/src/operator/knowledge.html`
- `services/forge-console/src/operator/knowledge-graph.html`
- shared JS/CSS under the same directory

`operator-ui.js` builds `infrastructureApiBase` from the Spring context path and `/api/v1/infrastructure`, so the browser calls Forge Nexus rather than Python service ports.

## Scripts

Existing root wrappers:

- `scripts/knowledge/bootstrap.sh`
- `scripts/knowledge/start.sh`
- `scripts/knowledge/status.sh`
- `scripts/knowledge/stop.sh`
- `scripts/knowledge/smoke-test.sh` was not present; Knowledge has `inventory-build.sh`, `init-local-config.sh`, and `validate-config.sh`
- `scripts/jarvis/bootstrap.sh`
- `scripts/jarvis/start.sh`
- `scripts/jarvis/status.sh`
- `scripts/jarvis/stop.sh`
- `scripts/jarvis/smoke-test.sh`

Service-owned scripts exist under:

- `infrastructure/knowledge/scripts`
- `infrastructure/jarvis/scripts`

Root stack helpers also existed in `Justfile` and `scripts/forge-ai-start.sh`.

## Config Files Found

Classpath/Spring:

- `boot/src/main/resources/application.yml`
- `config/services.yaml`
- `config/agent.yml`
- `config/lane-strategies.yml`
- `infrastructure/resources/src/main/resources/instructions.yaml`

Knowledge:

- `infrastructure/knowledge/config/knowledge.defaults.yaml`
- `infrastructure/knowledge/config/knowledge-sources.example.yaml`
- ignored local `infrastructure/knowledge/config/knowledge-sources.yaml`
- `infrastructure/knowledge/config/analysis-prompt.md`

Jarvis:

- `infrastructure/jarvis/config/model.yaml`
- `infrastructure/jarvis/config/allowed-actions.yaml`
- `infrastructure/jarvis/config/system-prompt.md`
- `infrastructure/jarvis/config/chat-prompt.md`

## Env Vars And Ports Found

Existing variables:

- `MONGODB_URI`
- `WORKSPACE_ROOT`
- `JARVIS_REPO_ROOT`
- `JARVIS_CONFIG_DIR`
- `JARVIS_LOG_FILE`
- `JARVIS_HOST`
- `JARVIS_PORT`
- `OLLAMA_HOME`
- `KNOWLEDGE_MODULE_DIR`
- `KNOWLEDGE_HOST`
- `KNOWLEDGE_PORT`
- `KNOWLEDGE_CONFIG`
- `KNOWLEDGE_STORE_PATH`
- `KNOWLEDGE_INVENTORY_AUTO_REFRESH_ENABLED`
- `KNOWLEDGE_INVENTORY_AUTO_REFRESH_INTERVAL_SECONDS`
- `KNOWLEDGE_ANALYSIS_*`

Ports:

- Forge Nexus/Spring Boot: `9099`
- Forge Jarvis: `7071`
- Forge Knowledge: `7081`
- Ollama: `11434`
- MongoDB default: `27019`

## Hardcoded Paths

The ignored local Knowledge config had workspace-specific absolute paths for the service catalog and workspace root. Those were replaced with `${FORGE_CONFIG_DIR}` and `${FORGE_WORKSPACE_ROOT}`.

Generated `target/` files contain absolute build paths after Maven runs; these are build output and remain ignored. The matching repository string in `services.yaml` is a GitHub repository slug, not a local filesystem path.

## Baseline Checks

Baseline before edits:

- `mvn -q -DskipTests compile`: passed
- Knowledge pytest without plugin control: failed before tests due auto-loaded ROS pytest plugin missing `lark`
- Jarvis pytest without plugin control: failed before tests due auto-loaded ROS pytest plugin missing `lark`
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/pytest -q tests` in Knowledge: 156 passed
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/pytest -q tests` in Jarvis: 31 passed
