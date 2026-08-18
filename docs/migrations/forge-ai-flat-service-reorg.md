# Forge AI Flat Service Reorg

## Purpose

This migration removes redundant service nesting introduced during the physical platform split. `forge-knowledge` and `forge-jarvis` are now the Python project roots themselves, not parent folders around another service project.

No Knowledge analysis, graph API, Jarvis chat, Jarvis command execution, REST endpoint, or UI behavior was rewritten.

## Before

- `services/forge-knowledge/services/knowledge-service/pyproject.toml`
- `services/forge-knowledge/services/knowledge-service/src/knowledge_service`
- `services/forge-knowledge/services/knowledge-service/tests`
- `services/forge-knowledge/config`
- `services/forge-knowledge/docs`
- `services/forge-knowledge/scripts`
- `services/forge-knowledge/var`
- `services/forge-jarvis/services/jarvis-agent/pyproject.toml`
- `services/forge-jarvis/services/jarvis-agent/src/jarvis_agent`
- `services/forge-jarvis/services/jarvis-agent/tests`
- `services/forge-jarvis/config`
- `services/forge-jarvis/scripts`
- `services/forge-jarvis/var`
- `services/forge-console/static/operator`
- root `pom.xml` directly aggregated Java modules under `services/forge-nexus/*`

## After

- `services/forge-knowledge/pyproject.toml`
- `services/forge-knowledge/src/knowledge_service`
- `services/forge-knowledge/tests`
- `services/forge-jarvis/pyproject.toml`
- `services/forge-jarvis/src/jarvis_agent`
- `services/forge-jarvis/tests`
- `services/forge-console/src/operator`
- `services/forge-nexus/pom.xml`
- `services/forge-nexus/boot`
- `services/forge-nexus/api-rest`
- `services/forge-nexus/application`
- `services/forge-nexus/domain`
- `services/forge-nexus/infrastructure`

Root platform locations are canonical:

- config: `config/`
- docs: `docs/`
- scripts: `scripts/`
- runtime: `var/`

## Moved

- Knowledge Python project files moved from `services/forge-knowledge/services/knowledge-service/*` to `services/forge-knowledge/*`.
- Jarvis Python project files moved from `services/forge-jarvis/services/jarvis-agent/*` to `services/forge-jarvis/*`.
- Knowledge config moved to `config/knowledge`.
- Jarvis config moved to `config/jarvis`.
- Knowledge docs moved to `docs/knowledge`.
- Jarvis service notes moved to `docs/jarvis`.
- Console UI moved from `services/forge-console/static/operator` to `services/forge-console/src/operator`.
- Knowledge runtime data moved to `var/knowledge`.
- Jarvis service-local runtime data moved under `var/jarvis/legacy-service-local-var` to avoid deleting Open WebUI/cache artifacts.
- The Java aggregator moved to `services/forge-nexus/pom.xml`.
- Root `pom.xml` became a lightweight platform aggregator for `services/forge-nexus`.

## Updated

- Root scripts now directly operate on `services/forge-knowledge` and `services/forge-jarvis`.
- Service-local `scripts/`, `config/`, `docs/`, and `var/` directories were removed from Knowledge and Jarvis service roots.
- Boot Maven resources now package `services/forge-console/src` as `static`.
- Python config path resolution now treats the flat service root as the module root.
- Knowledge SQLite default now resolves to `${FORGE_RUNTIME_DIR}/knowledge/knowledge.sqlite`.
- Jarvis runtime logs now resolve under `${FORGE_RUNTIME_DIR}/jarvis/logs`.
- Maven parent `relativePath` values were updated for the `services/forge-nexus` aggregator.

## Runtime Data

Runtime data was moved, not reset. Existing Knowledge SQLite files and backups are under `var/knowledge`. Jarvis runtime artifacts are under `var/jarvis`; the service-local Open WebUI snapshot was preserved as `var/jarvis/legacy-service-local-var`.

## Validation

Passed:

- required filesystem assertions for flat Knowledge, Jarvis, Console, Nexus, and removed old root implementation directories
- `mvn -q -DskipTests compile`
- `find services/forge-nexus/boot/target/classes/static/operator -maxdepth 2 -type f | sort`
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 services/forge-knowledge/.venv/bin/python3 -m pytest -q services/forge-knowledge/tests`: 159 passed
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 services/forge-jarvis/.venv/bin/python3 -m pytest -q services/forge-jarvis/tests`: 32 passed
- `scripts/knowledge/validate-config.sh`
- `scripts/validate-config.sh`
- `scripts/knowledge/status.sh`
- `scripts/jarvis/status.sh`
- `scripts/status.sh`
- `mvn -q -pl services/forge-nexus/api-rest -am -Dtest=ForgeAiInfrastructureJarvisControllerTest,ForgeAiInfrastructureKnowledgeControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -pl services/forge-nexus/infrastructure/jarvis-client -am -Dtest=HttpJarvisGatewayTest,JarvisClientPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -pl services/forge-nexus/infrastructure/knowledge-client -am -Dtest=HttpKnowledgeGatewayTest,KnowledgeClientPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -pl services/forge-nexus/application -am -Dtest=ServiceConfigCodexLaneWorkspaceResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`
- `mvn -q -pl services/forge-nexus/infrastructure/resources -am -Dtest=ResourceOperatorConfigResourceRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

Status output after the move:

- Forge Nexus: UP at `http://127.0.0.1:9099/fgaisox`
- Knowledge service: DOWN at `http://127.0.0.1:7081` because it was stopped before moving runtime data
- Jarvis Agent: UP
- Ollama API: UP

## Known Issues

No migration test failures remain. The Knowledge service was not restarted during validation; the status script correctly reports DOWN without path errors.
