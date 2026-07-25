# Local AI Platform Service Boundary Reorg

## Before

- Java app and Operator UI were served from existing Maven modules.
- Jarvis lived under `infrastructure/jarvis`.
- Knowledge lived under `infrastructure/knowledge`.
- Config source files were split between Boot classpath resources and service-local config directories.
- Ignored local Knowledge config contained developer-specific absolute paths.

## After

Logical components are documented under:

- `services/forge-nexus`
- `services/forge-console`
- `services/forge-knowledge`
- `services/forge-jarvis`

Root config is introduced under `config/` and preferred by Spring, Knowledge, and Jarvis loaders while existing component/classpath locations remain fallbacks.
The Operator resource editor now writes `agent.yml` and `lane-strategies.yml` to root config when those files exist, with Boot resource fallback for compatibility.

## Files Moved

No existing Maven modules, Python service directories, static UI assets, or runtime data files were moved.

Canonical config files were copied into root `config/`:

- service catalog, agent, lane strategy, and instruction config
- Knowledge defaults, sources, and analysis prompt
- Jarvis model, allowed actions, and prompts

## Files Not Moved And Why

- Java Maven modules: moving them now would risk aggregation, IDE, classpath, and test behavior.
- Operator UI assets: Boot already serves the required static paths from `boot/src/main/resources/static/operator`.
- Python service roots: current import paths, pyproject metadata, venvs, scripts, and tests are stable.
- Knowledge SQLite runtime data: moving it could silently change runtime state.

## Compatibility Wrappers

Existing root wrappers under `scripts/knowledge/*` and `scripts/jarvis/*` remain. New root scripts add platform-level management:

- `scripts/bootstrap.sh`
- `scripts/start.sh`
- `scripts/stop.sh`
- `scripts/status.sh`
- `scripts/validate-config.sh`

## Validation Commands Run

Baseline:

- `mvn -q -DskipTests compile`: passed
- Knowledge pytest without plugin control: failed before tests because ROS pytest plugin auto-load required missing `lark`
- Jarvis pytest without plugin control: failed before tests because ROS pytest plugin auto-load required missing `lark`
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/pytest -q tests` in Knowledge: 156 passed
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/pytest -q tests` in Jarvis: 31 passed

Post-change:

- `scripts/validate-config.sh`: passed; Knowledge resolved `config/knowledge/knowledge-sources.yaml` with 12 sources and 0 diagnostics
- `find config -maxdepth 4 -type f`: confirmed root config files
- `find services -maxdepth 4 -type f`: confirmed logical service README files
- hardcoded local path scan: only matched the existing GitHub repository slug in `services.yaml` and its existing test assertion
- `rg -n "127.0.0.1|localhost|7071|7081|9099" config boot api-rest application domain infrastructure services scripts`: reviewed local URLs and ports
- `mvn -q -DskipTests compile`: passed
- `mvn -q -pl api-rest -am -Dtest=ForgeAiInfrastructureJarvisControllerTest,ForgeAiInfrastructureKnowledgeControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `mvn -q -pl infrastructure/jarvis-client -Dtest=HttpJarvisGatewayTest,JarvisClientPropertiesTest test`: passed
- `mvn -q -pl infrastructure/knowledge-client -am -Dtest=HttpKnowledgeGatewayTest,KnowledgeClientPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `mvn -q -pl infrastructure/resources -am -Dtest=ResourceOperatorConfigResourceRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/pytest -q tests` in Knowledge: 159 passed
- `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/pytest -q tests` in Jarvis: 32 passed
- `scripts/status.sh` outside the managed sandbox: Forge Nexus UP, Knowledge UP, Ollama UP, Jarvis UP
- Direct smoke after restarting Knowledge with the updated script: Forge Nexus health, Jarvis local status, Knowledge local status, Knowledge local services status, Jarvis proxy status, Knowledge proxy status, and Knowledge proxy services status returned success

Notes:

- Isolated Maven test runs without `-am` failed with `NoClassDefFoundError` for upstream application classes; the reactor-aware commands above passed.
- Python pytest without `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1` still fails before project tests because the host auto-loads ROS pytest plugins that require missing `lark`.
- The managed sandbox produced false DOWN status samples for quiet localhost curl probes; rerunning `scripts/status.sh` outside the sandbox verified the actual runtime status as UP.

## Follow-Up Tasks

- Consider importing `config/local.yaml` from Spring only after deciding the local override policy.
- Consider physically moving Python services under `services/` in a later dedicated task with wrappers and pyproject/test updates.
- Consider physically moving Java modules under `services/forge-nexus` only in a dedicated Maven relocation task.
- Consider moving Console assets only after Boot resource serving is covered by smoke tests.
