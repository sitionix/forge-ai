# Forge AI Physical Reorganization

## Baseline

Baseline captured before the physical move phase.

The repository already contained the previous logical-boundary pass:

- root `config/`
- root platform scripts
- placeholder `services/forge-*` directories with README files
- old implementation roots still present at `boot`, `api-rest`, `application`, `domain`, `infrastructure`, and `jacoco-report`

Baseline commands:

- `git status --short`: dirty tree from the previous boundary/config pass, with old implementation folders still at the root
- `find . -maxdepth 3 -type d | sort`: confirmed root Java modules and `infrastructure/knowledge`, `infrastructure/jarvis`
- `find . -maxdepth 4 -name pom.xml | sort`: confirmed root Maven module paths
- `find . -maxdepth 5 -name pyproject.toml | sort`: confirmed Python service pyprojects under `infrastructure/knowledge` and `infrastructure/jarvis`
- `mvn -q -DskipTests compile`: passed

## Before

Physical implementation roots before this task:

- `boot`
- `api-rest`
- `application`
- `domain`
- `jacoco-report`
- `infrastructure`
- `infrastructure/knowledge`
- `infrastructure/jarvis`
- `boot/src/main/resources/static/operator`

## After

Physical service layout after this task:

- `services/forge-nexus`
- `services/forge-console`
- `services/forge-knowledge`
- `services/forge-jarvis`

## Moves

Completed moves:

- `boot` -> `services/forge-nexus/boot`
- `api-rest` -> `services/forge-nexus/api-rest`
- `application` -> `services/forge-nexus/application`
- `domain` -> `services/forge-nexus/domain`
- `jacoco-report` -> `services/forge-nexus/jacoco-report`
- Java `infrastructure/*` modules -> `services/forge-nexus/infrastructure/*`
- `infrastructure/knowledge` -> `services/forge-knowledge`
- `infrastructure/jarvis` -> `services/forge-jarvis`
- Operator UI -> `services/forge-console/static/operator`

## Updates

Updated areas:

- root Maven module paths
- Maven parent `relativePath`
- Boot resource config for Forge Console static assets
- root and service scripts
- Python config path resolution
- root config references
- docs and migration notes

## Compatibility Wrappers

Root scripts remain as platform entrypoints and compatibility wrappers:

- `scripts/knowledge/*` delegates to `services/forge-knowledge/scripts/*`
- `scripts/jarvis/*` delegates to `services/forge-jarvis/scripts/*`
- `scripts/start.sh`, `scripts/stop.sh`, `scripts/status.sh`, and `scripts/validate-config.sh` establish common `FORGE_*` environment defaults from the repo root

## Runtime Data

Knowledge SQLite runtime data moved from `infrastructure/knowledge/var` to `services/forge-knowledge/var` with the service. Jarvis runtime data moved to `services/forge-jarvis/var`.

During the move, a stale ignored Open WebUI runtime directory was recreated under root `infrastructure/jarvis/var` with owner `nobody:nogroup`. It contained no tracked implementation code. The recreating source was a stale Docker container named `forge-ai-jarvis-open-webui` with this bind mount:

- `/home/chekoteela/Documents/java/Sitionix/forge-ai/infrastructure/jarvis/var/data/open-webui` -> `/app/backend/data`

That stale container was stopped and removed. The root `infrastructure/` directory was then deleted.

Root `infrastructure/` is no longer present after cleanup.

## Validation

Validation results:

- `mvn -q -DskipTests compile`: passed before physical moves
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python3 -m pytest -q tests` from `services/forge-knowledge/services/knowledge-service`: passed, 159 tests
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python3 -m pytest -q tests` from `services/forge-jarvis/services/jarvis-agent`: passed, 32 tests
- `mvn -q -DskipTests compile`: passed after Java Maven module relocation
- `mvn -q -DskipTests compile`: passed after Forge Console static resource relocation
- `find services/forge-nexus/boot/target/classes/static/operator -maxdepth 2 -type f | sort`: confirmed Operator UI resources are packaged under the original `static/operator` path
- `mvn -q -DskipTests compile`: passed after final cleanup and script fixes
- `mvn -q -pl services/forge-nexus/api-rest -am -Dtest=ForgeAiInfrastructureJarvisControllerTest,ForgeAiInfrastructureKnowledgeControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `mvn -q -pl services/forge-nexus/infrastructure/jarvis-client -am -Dtest=HttpJarvisGatewayTest,JarvisClientPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `mvn -q -pl services/forge-nexus/infrastructure/knowledge-client -am -Dtest=HttpKnowledgeGatewayTest,KnowledgeClientPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `mvn -q -pl services/forge-nexus/application -am -Dtest=ServiceConfigCodexLaneWorkspaceResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `mvn -q -pl services/forge-nexus/infrastructure/resources -am -Dtest=ResourceOperatorConfigResourceRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `scripts/knowledge/validate-config.sh`: passed
- `scripts/validate-config.sh`: passed
- `scripts/knowledge/status.sh`: path wrapper passed and reported UP after service start
- `scripts/jarvis/status.sh`: path wrapper passed and reported UP after service start
- `scripts/status.sh`: path wrapper passed
- `curl -fsS http://127.0.0.1:7081/health`: passed
- `curl -fsS http://127.0.0.1:7081/api/v1/knowledge/status`: passed
- `curl -fsS http://127.0.0.1:7071/health`: passed
- `curl -fsS http://127.0.0.1:7071/api/v1/jarvis/status`: passed
- `curl -fsS http://127.0.0.1:9099/fgaisox/actuator/health`: passed
- `curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/jarvis/status`: passed
- `curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/status`: passed
- `curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/services/status`: passed
- `curl -fsS http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/analysis/graph`: passed
- `find . -maxdepth 2 -type d -name infrastructure -print`: no output after stale container cleanup

Notes:

- Plain `.venv/bin/pytest`, `.venv/bin/pip`, and `.venv/bin/uvicorn` scripts had stale shebangs after the physical move. Service scripts were updated to invoke the moved venv with `python3 -m pip` and `python3 -m uvicorn`, with explicit `PYTHONPATH` for the moved `src` roots.
- Running `pytest` without `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1` still fails in this workstation because a global ROS pytest plugin imports missing `lark`. This was already observed before the physical move; the service tests pass with plugin autoload disabled.
