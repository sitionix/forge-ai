# Post-Reorganization Hardening Audit

Date: 2026-06-18

## Baseline

- Current branch: `feature/SITIONIX-36`
- Reorganization commit inspected: `d5d4bc2 [SITIONIX-36] devide too seversl ms`
- Last pre-reorganization commit used as baseline: `1a5ef9d`
- Baseline diff command: `git diff --name-status 1a5ef9d..HEAD`
- Target-area baseline diff command: `git diff --name-status 1a5ef9d..HEAD -- services/forge-knowledge services/forge-jarvis services/forge-console config scripts Justfile pom.xml compose.yaml services/forge-nexus/boot/pom.xml`
- Target-area baseline changed-file count: 129
- Working-tree baseline before this hardening pass: clean `git status --short`, empty `git diff --name-status`, empty `git diff --cached --name-status`.

## Reorganization File Inventory

Review decision legend:

- Retained: behavior and ownership are still appropriate after hardening.
- Refactored: implementation changed for typed configuration, dependency injection, tests, or formatting.
- Moved: accepted as the committed service reorganization destination.
- Removed: deleted because it was a relocation hack or duplicated runtime config.
- Replaced: superseded by a canonical root/runtime-configured implementation.

All files below were reviewed. Group decisions apply unless an exception is listed later.

```text
M Justfile
A config/agent.yml
A config/forge-ai.yaml
A config/instructions.yaml
A config/jarvis/allowed-actions.yaml
A config/jarvis/chat-prompt.md
A config/jarvis/model.yaml
A config/jarvis/system-prompt.md
A config/knowledge/analysis-prompt.md
A config/knowledge/knowledge-sources.example.yaml
A config/knowledge/knowledge-sources.yaml
A config/knowledge/knowledge.defaults.yaml
A config/lane-strategies.yml
A config/local.example.yaml
A config/services.yaml
M pom.xml
A scripts/bootstrap.sh
M scripts/forge-ai-start.sh
M scripts/jarvis/bootstrap.sh
M scripts/jarvis/smoke-test.sh
M scripts/jarvis/start.sh
M scripts/jarvis/status.sh
M scripts/jarvis/stop.sh
M scripts/knowledge/bootstrap.sh
M scripts/knowledge/init-local-config.sh
M scripts/knowledge/inventory-build.sh
M scripts/knowledge/start.sh
M scripts/knowledge/status.sh
M scripts/knowledge/stop.sh
M scripts/knowledge/validate-config.sh
A scripts/lib/forge-env.sh
A scripts/start.sh
A scripts/status.sh
A scripts/stop.sh
A scripts/validate-config.sh
A services/forge-console/README.md
A services/forge-console/src/operator/agents.html
A services/forge-console/src/operator/index.html
A services/forge-console/src/operator/jarvis.html
A services/forge-console/src/operator/knowledge-graph.html
A services/forge-console/src/operator/knowledge.html
A services/forge-console/src/operator/lane.html
A services/forge-console/src/operator/new-task.html
A services/forge-console/src/operator/operator-ui.css
A services/forge-console/src/operator/operator-ui.js
A services/forge-console/src/operator/service.html
A services/forge-console/src/operator/services.html
A services/forge-console/src/operator/ticket.html
A services/forge-jarvis/README.md
A services/forge-jarvis/pyproject.toml
A services/forge-jarvis/setup.py
A services/forge-jarvis/src/jarvis_agent/__init__.py
A services/forge-jarvis/src/jarvis_agent/action_executor.py
A services/forge-jarvis/src/jarvis_agent/action_registry.py
A services/forge-jarvis/src/jarvis_agent/chat_prompt.py
A services/forge-jarvis/src/jarvis_agent/chat_schema.py
A services/forge-jarvis/src/jarvis_agent/config.py
A services/forge-jarvis/src/jarvis_agent/intent_parser.py
A services/forge-jarvis/src/jarvis_agent/intent_schema.py
A services/forge-jarvis/src/jarvis_agent/knowledge_client.py
A services/forge-jarvis/src/jarvis_agent/main.py
A services/forge-jarvis/src/jarvis_agent/ollama_client.py
A services/forge-jarvis/src/jarvis_agent/security.py
A services/forge-jarvis/tests/test_action_executor.py
A services/forge-jarvis/tests/test_action_registry.py
A services/forge-jarvis/tests/test_api.py
A services/forge-jarvis/tests/test_config.py
A services/forge-jarvis/tests/test_intent_schema.py
A services/forge-knowledge/README.md
A services/forge-knowledge/pyproject.toml
A services/forge-knowledge/setup.py
A services/forge-knowledge/src/knowledge_service/__init__.py
A services/forge-knowledge/src/knowledge_service/analysis_client.py
A services/forge-knowledge/src/knowledge_service/analysis_response_parser.py
A services/forge-knowledge/src/knowledge_service/analysis_schema.py
A services/forge-knowledge/src/knowledge_service/analysis_service.py
A services/forge-knowledge/src/knowledge_service/analysis_store.py
A services/forge-knowledge/src/knowledge_service/anchor_enrichment.py
A services/forge-knowledge/src/knowledge_service/config.py
A services/forge-knowledge/src/knowledge_service/context_builder.py
A services/forge-knowledge/src/knowledge_service/context_schema.py
A services/forge-knowledge/src/knowledge_service/context_service.py
A services/forge-knowledge/src/knowledge_service/errors.py
A services/forge-knowledge/src/knowledge_service/file_classification.py
A services/forge-knowledge/src/knowledge_service/file_filters.py
A services/forge-knowledge/src/knowledge_service/file_metadata.py
A services/forge-knowledge/src/knowledge_service/file_scanner.py
A services/forge-knowledge/src/knowledge_service/freshness_service.py
A services/forge-knowledge/src/knowledge_service/graph_analysis.py
A services/forge-knowledge/src/knowledge_service/graph_call_intelligence.py
A services/forge-knowledge/src/knowledge_service/graph_model.py
A services/forge-knowledge/src/knowledge_service/graph_response_parser.py
A services/forge-knowledge/src/knowledge_service/graph_schema.py
A services/forge-knowledge/src/knowledge_service/graph_validation.py
A services/forge-knowledge/src/knowledge_service/inventory_builder.py
A services/forge-knowledge/src/knowledge_service/inventory_file_resolver.py
A services/forge-knowledge/src/knowledge_service/inventory_refresh.py
A services/forge-knowledge/src/knowledge_service/inventory_schema.py
A services/forge-knowledge/src/knowledge_service/inventory_store.py
A services/forge-knowledge/src/knowledge_service/java_parser_adapter.py
A services/forge-knowledge/src/knowledge_service/knowledge_defaults.py
A services/forge-knowledge/src/knowledge_service/main.py
A services/forge-knowledge/src/knowledge_service/path_security.py
A services/forge-knowledge/src/knowledge_service/retrieval_ranker.py
A services/forge-knowledge/src/knowledge_service/service_catalog_provider.py
A services/forge-knowledge/src/knowledge_service/skipped_reasons.py
A services/forge-knowledge/src/knowledge_service/snippet_extractor.py
A services/forge-knowledge/src/knowledge_service/source_catalog.py
A services/forge-knowledge/src/knowledge_service/source_config.py
A services/forge-knowledge/src/knowledge_service/source_validator.py
A services/forge-knowledge/src/knowledge_service/structural_analysis.py
A services/forge-knowledge/src/knowledge_service/structural_model.py
A services/forge-knowledge/src/knowledge_service/tokenizer.py
A services/forge-knowledge/tests/conftest.py
A services/forge-knowledge/tests/test_analysis.py
A services/forge-knowledge/tests/test_api.py
A services/forge-knowledge/tests/test_context_api.py
A services/forge-knowledge/tests/test_context_service.py
A services/forge-knowledge/tests/test_file_filters.py
A services/forge-knowledge/tests/test_inventory_builder.py
A services/forge-knowledge/tests/test_inventory_file_resolver.py
A services/forge-knowledge/tests/test_inventory_store.py
A services/forge-knowledge/tests/test_path_security.py
A services/forge-knowledge/tests/test_service_catalog_provider.py
A services/forge-knowledge/tests/test_source_config.py
A services/forge-knowledge/tests/test_source_validator.py
A services/forge-knowledge/tests/test_structural_analysis.py
A services/forge-nexus/boot/pom.xml
```

## File Decisions

- `services/forge-knowledge/**`: moved by the reorganization, then refactored where needed for typed settings, application factory composition, deterministic tests, logging, and quality gates. The graph schema, parser-backed analysis semantics, and structural analysis behavior were retained. Several files were reformatted by `ruff format`; those formatting-only changes were reviewed and are intentional.
- `services/forge-jarvis/**`: moved by the reorganization, then refactored for typed settings, application factory composition, injected clients/executor, response parsing, route tests, and quality gates. Jarvis command and chat behavior were retained.
- `services/forge-console/**`: retained current pages and URLs, replaced the monolithic-only project shape with a strict TypeScript/Vite/Vitest project, and added runtime config loading. Existing DOM UI behavior was preserved.
- `config/forge-ai.yaml`: retained as the canonical root runtime configuration and refactored to own service ports, URLs, SQLite path, logging paths, model runtime settings, timeouts, and console polling intervals.
- `config/jarvis/model.yaml`: removed. It duplicated runtime provider/model/base URL configuration now owned by `config/forge-ai.yaml`.
- `config/knowledge/knowledge.defaults.yaml`: retained for Knowledge domain/indexing defaults, refactored to remove duplicated runtime analysis settings.
- `config/services.yaml`, `config/agent.yml`, `config/lane-strategies.yml`, `config/instructions.yaml`, `config/knowledge/analysis-prompt.md`, `config/knowledge/knowledge-sources.yaml`, `config/jarvis/*.md`, `config/jarvis/allowed-actions.yaml`: retained as domain resources rather than deployment settings.
- `scripts/**`: retained and extended. Added root verification scripts, updated service status/start scripts to read host/port/model runtime URLs through the typed service settings, and updated `scripts/validate-config.sh` so validation no longer depends on removed duplicated Jarvis config.
- `Justfile`: retained and extended with `test`, `test-python`, `test-console`, `test-forge-it`, `lint`, and `typecheck`.
- `services/forge-nexus/boot/pom.xml`: minimally refactored so Nexus packages Console build output from `services/forge-console/dist` as `static/operator/...`. No Nexus internals were refactored.
- `.gitignore` and `.ignore`: added generated Forge Console output/dependencies to avoid committing or scanning `dist` and `node_modules`.

## Architecture Problems Found and Fixed

- Knowledge loaded configuration and constructed stores/runners at module import and global app state. Fixed with `create_app(settings, dependencies)`, typed `KnowledgeDependencies`, and lifespan composition.
- Jarvis loaded configuration and constructed external clients/executors globally. Fixed with `create_app(settings, dependencies)` and typed `JarvisDependencies`.
- Runtime values were split across root config, Knowledge defaults, and Jarvis model config. Fixed by centralizing operational settings in `config/forge-ai.yaml`.
- Service shell scripts carried copied host/port/model-runtime defaults. Fixed by reading defaults through the typed Python settings loaders and preserving environment variables only as explicit overrides.
- Knowledge tests used a `sys.path.insert` relocation hack in `tests/conftest.py`. Removed.
- Console JavaScript embedded operational runtime values in page script constants. Fixed with `runtime-config.js/json` and a typed runtime config module.
- Jarvis and Knowledge external JSON handling crossed too far into application code. Fixed malformed-response handling at adapter boundaries for Jarvis Knowledge/Ollama clients and deterministic provider injection for Knowledge tests.

## Relocation Hack Results

Commands run:

- `rg -n "sys\\.path|ImportError|infrastructure/knowledge|infrastructure/jarvis" .`
- `rg -n "\\.\\./\\.\\./\\.\\./|Path\\(__file__\\).*parent.*parent" services scripts config`
- `rg -n "/home/|/Users/|C:\\\\|Documents/|Sitionix/" .`
- `rg -n "127\\.0\\.0\\.1|localhost|7071|7081|9099|11434" services`
- `rg -n "sys\\.path\\.insert|sys\\.path\\.append" services`

Findings:

- No `sys.path.insert` or `sys.path.append` remains under `services`.
- No production import fallback relocation hacks were added.
- Remaining `infrastructure/knowledge` and `infrastructure/jarvis` matches under `services` are Nexus public endpoint contract strings (`/api/v1/infrastructure/...`), Nexus module names, test fixtures, and historical docs. They are not Python relocation paths.
- Remaining `Sitionix/...` matches are repository identifiers in service catalogs and Nexus tests, not developer workstation paths.
- No active `/home/...`, `/Users/...`, `Documents/...`, or workstation absolute path is used by the Python services, Console runtime code, scripts, or config.

## Canonical Configuration

Canonical root runtime config: `config/forge-ai.yaml`.

Centralized values:

- `forge.ai.home`, `config-dir`, `runtime-dir`, `workspace-root`
- logging level, console/file flags, log directory
- Knowledge host, port, SQLite path, source and service catalogs, inventory refresh interval, analysis provider/base URL/model/prompt/timeouts/limits/retry counts
- Jarvis host, port, Knowledge base URL, model runtime provider/base URL/model/timeout, prompt/action paths
- Console API mode and polling intervals

Resolution order implemented in both Python services:

1. Explicit service loader argument.
2. `FORGE_CONFIG_FILE`.
3. `FORGE_CONFIG_DIR/forge-ai.yaml`.
4. Repository root `config/forge-ai.yaml`.
5. Packaged/default values only where needed for tests and first startup.

Supported environment variables:

- `FORGE_AI_HOME`
- `FORGE_CONFIG_DIR`
- `FORGE_CONFIG_FILE`
- `FORGE_RUNTIME_DIR`
- `FORGE_WORKSPACE_ROOT`
- Existing `KNOWLEDGE_*` and `JARVIS_*` overrides mapped into the typed settings model.

Remaining intentional defaults:

- Localhost defaults for local-only service startup.
- Local model runtime defaults for Ollama when root config does not override.
- Test-only packaged/default config generated into temporary directories.

## Typing Improvements

- Knowledge: `ForgeSettings`, `KnowledgeSettings`, storage/inventory/analysis/logging settings, typed dependency container, `AnalysisProvider`, `JobExecutor`, app factory.
- Jarvis: `ForgeSettings`, `JarvisSettings`, model runtime/knowledge/logging settings, typed dependency container, `ModelClient`, `KnowledgeContextClient`, app factory.
- Console: strict TypeScript, typed runtime config, HTTP client, API modules, response models, component helpers, and jsdom/Vitest tests.
- Mypy is enabled through each service pyproject. Knowledge keeps scoped mypy overrides only for legacy graph/parser/sqlite internals that were explicitly out of scope for rewrite; new bootstrap/config/API orchestration code is checked.

## Forge Knowledge IT Matrix

Location: `services/forge-knowledge/tests/forge_it/test_knowledge_service_it.py`

| Method | Path | Covered |
| --- | --- | --- |
| GET | `/health` | yes |
| GET | `/api/v1/knowledge/status` | yes |
| GET | `/api/v1/knowledge/sources` | yes |
| POST | `/api/v1/knowledge/inventory/build` | yes |
| GET | `/api/v1/knowledge/inventory/status` | yes |
| GET | `/api/v1/knowledge/inventory/files` | yes |
| POST | `/api/v1/knowledge/context` | yes |
| POST | `/api/v1/knowledge/analysis/build` | yes |
| GET | `/api/v1/knowledge/analysis/jobs/{job_id}` | yes |
| POST | `/api/v1/knowledge/analysis/jobs/{job_id}/stop` | yes |
| GET | `/api/v1/knowledge/analysis/status` | yes |
| GET | `/api/v1/knowledge/overview` | yes |
| GET | `/api/v1/knowledge/analysis/files` | yes |
| GET | `/api/v1/knowledge/analysis/graph/manifest` | yes |
| GET | `/api/v1/knowledge/analysis/graph/nodes` | yes |
| GET | `/api/v1/knowledge/analysis/graph/edges` | yes |
| GET | `/api/v1/knowledge/analysis/graph/node/{node_id}` | yes |
| GET | `/api/v1/knowledge/analysis/graph/edge/{edge_id}` | yes |

Knowledge IT scenarios covered:

- startup from root config
- invalid config failure
- route inventory guard
- OpenAPI contract snapshot guard
- health/status/sources
- inventory build over temporary workspace
- inventory persistence, skipped file reporting, filters, context retrieval
- deterministic analysis build with fake `AnalysisProvider`
- completed, stopped, and failed analysis states
- symbols, relations, graph nodes, graph edges, evidence, diagnostics persistence
- graph slice closure
- restart with same temporary SQLite
- migration idempotency
- API validation errors and dependency/provider failures

SQLite invariants verified against real temporary databases:

- `sources`
- `files`
- `analysis_jobs`
- `analysis_files`
- `analysis_graph_nodes`
- `analysis_graph_edges`
- `analysis_graph_evidence`
- `analysis_graph_diagnostics`

## Forge Jarvis IT Matrix

Location: `services/forge-jarvis/tests/forge_it/test_jarvis_service_it.py`

| Method | Path | Covered |
| --- | --- | --- |
| GET | `/health` | yes |
| GET | `/api/v1/jarvis/status` | yes |
| GET | `/api/v1/jarvis/actions` | yes |
| POST | `/api/v1/jarvis/command` | yes |
| POST | `/api/v1/jarvis/chat` | yes |

Jarvis IT scenarios covered:

- startup from canonical root config
- invalid config failure
- route inventory guard
- OpenAPI contract snapshot guard
- health and dependency status
- model provider down
- public actions do not expose command arrays
- blank command validation
- allowlisted command execution through fake executor
- unsupported action
- invalid model intent JSON
- action execution failure
- blank chat validation
- chat with Knowledge context and empty context
- Knowledge unavailable and malformed Knowledge response
- model unavailable and malformed model response
- diagnostics on success and failure

Jarvis remains stateless; no SQLite was added.

## Console Results

- Added `package.json`, `package-lock.json`, `tsconfig.json`, `vite.config.ts`.
- Added strict TypeScript modules under `src/api`, `src/config`, `src/models`, and `src/components`.
- Added `runtime-config.js` and `runtime-config.json` under `src/operator`.
- Updated existing operator HTML pages to load runtime config before `operator-ui.js`.
- Updated `operator-ui.js` to derive API base paths and polling intervals from runtime config.
- Updated the Console build script to parse root `config/forge-ai.yaml` and generate `dist/operator/runtime-config.js` plus `runtime-config.json` during `npm run build`.
- Browser code defaults to same-origin API calls.
- No hardcoded backend host or port remains in Console runtime code.
- Nexus packaging now uses `services/forge-console/dist`, generated by `npm run build`.

## OpenAPI Contract Guards

- Knowledge snapshot: `services/forge-knowledge/tests/contracts/openapi.json`
- Jarvis snapshot: `services/forge-jarvis/tests/contracts/openapi.json`
- Refresh commands:
  - `cd services/forge-knowledge && PYTHONPATH=src .venv/bin/python3 tests/contracts/refresh_openapi.py`
  - `cd services/forge-jarvis && PYTHONPATH=src .venv/bin/python3 tests/contracts/refresh_openapi.py`

## Commands Run

Discovery:

- `git status --short`
- `git diff --name-status`
- `git diff --cached --name-status`
- `git log --oneline --decorate -n 30`
- `git diff --name-status 1a5ef9d..HEAD`
- `git diff --stat 1a5ef9d..HEAD`

Python service gates:

- `cd services/forge-knowledge && .venv/bin/python3 -m ruff check .`
- `cd services/forge-knowledge && .venv/bin/python3 -m ruff format --check .`
- `cd services/forge-knowledge && .venv/bin/python3 -m mypy src`
- `cd services/forge-knowledge && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python3 -m pytest -q`
- `cd services/forge-knowledge && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python3 -m pytest -q -m forge_it`
- `cd services/forge-jarvis && .venv/bin/python3 -m ruff check .`
- `cd services/forge-jarvis && .venv/bin/python3 -m ruff format --check .`
- `cd services/forge-jarvis && .venv/bin/python3 -m mypy src`
- `cd services/forge-jarvis && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python3 -m pytest -q`
- `cd services/forge-jarvis && PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 .venv/bin/python3 -m pytest -q -m forge_it`

Console gates:

- `cd services/forge-console && npm ci`
- `cd services/forge-console && npm run typecheck`
- `cd services/forge-console && npm test`
- `cd services/forge-console && npm run build`

Root gates:

- `scripts/lint.sh`
- `scripts/typecheck.sh`
- `scripts/test-python.sh`
- `scripts/test-forge-it.sh`
- `scripts/test-console.sh`
- `scripts/test.sh`
- `mvn -q -DskipTests compile`
- `scripts/validate-config.sh`

Runtime smoke after Knowledge access report:

- `scripts/status.sh`
- `scripts/knowledge/start.sh`
- `scripts/jarvis/start.sh`
- `bash -n scripts/knowledge/start.sh`
- `bash -n scripts/jarvis/start.sh`
- `curl http://127.0.0.1:7081/api/v1/knowledge/context`
- `curl http://127.0.0.1:7081/api/v1/knowledge/analysis/status`
- `curl http://127.0.0.1:9099/fgaisox/operator/knowledge.html`
- `curl http://127.0.0.1:9099/fgaisox/operator/knowledge-graph.html`
- `curl http://127.0.0.1:9099/fgaisox/operator/operator-ui.js`
- `curl http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/status`
- `curl http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/knowledge/services/status`
- `curl http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/jarvis/status`

## Results

- Knowledge ruff: passed.
- Knowledge mypy: passed.
- Knowledge pytest: `167 passed`.
- Knowledge Forge IT: `7 passed, 160 deselected`.
- Jarvis ruff: passed.
- Jarvis mypy: passed.
- Jarvis pytest: `42 passed`.
- Jarvis Forge IT: `6 passed, 36 deselected`.
- Console `npm ci`: passed. `npm audit` reports existing dependency advisories: 3 moderate, 1 high, 1 critical.
- Console typecheck: passed.
- Console tests: `4 passed`.
- Console build: passed.
- Root `scripts/test-python.sh`: passed.
- Root `scripts/test-forge-it.sh`: passed.
- Root `scripts/test-console.sh`: passed with sandbox escalation for esbuild postinstall execution.
- Root `scripts/test.sh`: passed with sandbox escalation for npm/esbuild and Maven compile.
- Maven compile: passed.
- Config validation: passed.
- Runtime smoke after Knowledge access report: passed. Nexus, Knowledge, Jarvis, and Ollama are `UP`; Knowledge operator page, Knowledge graph page, operator JS, Nexus Knowledge status/services, Nexus Jarvis status, and direct Knowledge context/status calls return `200`.
- Boot fix: `scripts/knowledge/start.sh` and `scripts/jarvis/start.sh` now read canonical typed config for host/port/runtime URLs and no longer let a stale or unhealthy PID file block service startup.

Note: plain `.venv/bin/python3 -m pytest` in this workstation attempts to load a globally installed ROS pytest plugin from `/opt/ros/foxy` that is incompatible with the project pytest version. Root scripts set `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1` so project tests are deterministic and not dependent on workstation ROS packages.

## Analysis Stall Observation

Read-only inspection performed:

- `scripts/status.sh`
- `scripts/knowledge/status.sh`
- `tail -n 120 var/knowledge/logs/knowledge-service.stdout.log`
- `tail -n 80 var/logs/forge-ai.log`
- read-only Python sqlite queries against `file:var/knowledge/knowledge.sqlite?mode=ro`
- log scans for `ERROR`, `Traceback`, `No such`, old infrastructure paths, `ImportError`, `ModuleNotFoundError`, `FileNotFoundError`

Initial observed state during hardening:

- Knowledge service is currently down at `http://127.0.0.1:7081`; Jarvis is down; Nexus and Ollama are up.
- Latest analysis job `3cb9d236-a8d0-4c69-b1b5-411c494540d0` is `STOPPED`, not `RUNNING`.
- Latest job timestamps:
  - started: `2026-06-17T20:40:51.018839+00:00`
  - completed: `2026-06-17T21:27:29.495491+00:00`
  - last progress: `2026-06-17T21:27:29.495499+00:00`
  - processed: 67 of 349
  - failed: 0
- Recent diagnostics show AI JSON/schema failures with static parser fallback, not path relocation or missing configuration.
- Log scans found no relocation/import/config errors.

Follow-up runtime smoke after the Knowledge access report:

- `scripts/status.sh` reports Nexus, Knowledge, Ollama, and Jarvis as `UP`.
- Knowledge is running as `uvicorn knowledge_service.main:app` on `127.0.0.1:7081`.
- Jarvis is running as `uvicorn jarvis_agent.main:app` on `127.0.0.1:7071`.
- Knowledge analysis status is `READY`; `activeJob` is `null`.
- Latest analysis job is `62c88c63-d951-4384-bf3b-882cd7744fb7`, completed at `2026-06-16T14:57:49.093761+00:00`.
- Freshness is `UP_TO_DATE`; no new, modified, or deleted files are reported.
- The access failure was caused by service startup state: stale/unhealthy PID files caused start scripts to report a service as running when the health endpoint was unreachable.

Disposition:

- No active stalled analysis job was found.
- The original stalled-analysis observation appears to be unrelated model/analysis behavior or an intentionally stopped job.
- The Knowledge access failure was fixed in startup scripts by treating `/health` as authoritative and ignoring or stopping stale/unhealthy PID-file state before starting the service.
- Follow-up should focus on model response/schema reliability and progress UX, not this hardening pass.

## Remaining Follow-ups

- Review and remediate `npm audit` advisories separately; no forced dependency upgrades were applied because they could introduce breaking changes.
- If the project wants plain `pytest` to be immune to global workstation plugins without environment variables, standardize a local virtualenv activation/wrapper policy outside the service code.
- Consider a future targeted typing pass on legacy Knowledge graph/parser internals after graph behavior work is explicitly in scope.
- Consider moving historical docs that still mention old `infrastructure/knowledge` and `infrastructure/jarvis` service roots into a migration-only archive.
