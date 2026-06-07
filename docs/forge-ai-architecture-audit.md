# Forge AI Architecture Audit

## Scope

This audit documents the current local Forge AI implementation in `/home/chekoteela/Documents/java/Sitionix/forge-ai` on branch `feature/SITIONIX-27`.

It is based on actual repository files and is intentionally separate from Jarvis implementation work. Jarvis is infrastructure-local under `infrastructure/jarvis`; it is not part of the Forge AI Maven module graph.

## Current Local Change Map

The working tree is dirty and should not be pulled/reset/force-checked-out until these local changes are reviewed.

| Path | Status | Classification | Notes |
| --- | --- | --- | --- |
| `.gitignore` | modified | Build/runtime config | Adds ignores for Maven cache, Python virtualenv/cache, Jarvis runtime logs/pid/data, and Python bytecode. |
| `docs/forge-ai-current-state-analysis.md` | untracked | Forge AI docs | Previous current-state report. |
| `docs/forge-ai-jarvis-ux-audit.md` | untracked | Forge AI docs / Jarvis docs | UX audit and target UX direction. |
| `docs/jarvis-infrastructure-module.md` | untracked | Jarvis docs | Infrastructure module spec. |
| `docs/jarvis-module.md` | untracked | Jarvis docs | Earlier Jarvis module documentation. |
| `infrastructure/jarvis/config/allowed-actions.yaml` | untracked | Jarvis config | Conservative allowlisted actions. |
| `infrastructure/jarvis/config/model.yaml` | untracked | Jarvis config | Ollama model config. |
| `infrastructure/jarvis/config/system-prompt.md` | untracked | Jarvis config | Intent-classifier prompt. |
| `infrastructure/jarvis/scripts/*.sh` | untracked | Jarvis script/wrapper | Module-owned bootstrap/start/status/smoke-test/stop scripts. |
| `infrastructure/jarvis/services/jarvis-agent/**` | untracked | Jarvis module move | Python FastAPI service, package config, and tests. |
| `scripts/jarvis/*.sh` | untracked | Jarvis script/wrapper | Repository-root convenience wrappers delegating into `infrastructure/jarvis/scripts`. |

Ignored runtime files currently exist locally but are not tracked:

- `infrastructure/jarvis/var/**`;
- `infrastructure/jarvis/services/jarvis-agent/.venv/**`;
- `infrastructure/jarvis/services/jarvis-agent/.pytest_cache/**`;
- Python `__pycache__` and package metadata.

## Maven Module Map

Root `pom.xml` is a Maven multi-module build with Java 21 and Spring Boot 3.3.4.

| Module | Packaging | Role |
| --- | --- | --- |
| `domain` | jar | Domain models, domain ports, repository contracts, usecase interfaces. |
| `application` | jar | Use case implementations, scheduler jobs, lane execution orchestration, operator run services. |
| `api-rest` | jar | REST controllers, generated API interface implementations, DTO mappers, exception handling. |
| `infrastructure` | pom | Parent for Java infrastructure adapters. |
| `infrastructure/mongodb` | jar | MongoDB documents, Spring Data repositories, domain repository adapters. |
| `infrastructure/codex-cli` | jar | Codex app-server process startup and JSON-RPC session adapter. |
| `infrastructure/resources` | jar | Classpath-backed instruction, strategy, and completion contract repositories. |
| `infrastructure/github-cli` | jar | GitHub evidence CLI adapter. |
| `boot` | jar | Spring Boot application entrypoint, config binding, static Operator UI. |
| `jacoco-report` | jar/pom | Test coverage reporting module. |

`infrastructure/pom.xml` currently lists only the Java infrastructure submodules:

```text
mongodb
codex-cli
resources
github-cli
```

`infrastructure/jarvis` is intentionally not listed there. It is a Python/local-runtime infrastructure module, not part of Java compile.

## Runtime Map

| Component | Port | Source | Current observed state |
| --- | ---: | --- | --- |
| Forge AI Spring Boot | `9099` | `boot/src/main/resources/application.yml` | Not listening during audit. |
| Forge AI context path | `/fgaisox` | `boot/src/main/resources/application.yml` | Applies to REST and static Operator UI. |
| MongoDB | `27019` | `spring.data.mongodb.uri=${MONGODB_URI:mongodb://localhost:27019/forge_ai}` | Not listening during audit. |
| Old standalone Jarvis | `7070` | `/home/chekoteela/local-ai/jarvis-local` | Listening on `127.0.0.1:7070` by `uvicorn` PID `14045`. |
| Forge-owned Jarvis | `7071` during verification | `JARVIS_PORT=7071 scripts/jarvis/*` | Listening on `127.0.0.1:7071` by `uvicorn` PID `54759`. |
| Ollama | `11434` | Jarvis model config / start script | Not listening during audit. |

## Spring Boot Entrypoint

Entrypoint:

```text
boot/src/main/java/com/sitionix/forgeai/Application.java
```

Runtime config:

```text
boot/src/main/resources/application.yml
```

Important settings:

- `server.port: 9099`;
- `server.servlet.context-path: /fgaisox`;
- imports `services.yaml`, `agent.yml`, `instructions.yaml`, and `lane-strategies.yml`;
- MongoDB URI defaults to `mongodb://localhost:27019/forge_ai`;
- actuator exposes `health,info`;
- Codex progress logging is configured under `forge.ai.codex.progress`;
- ticket terminal watcher behavior is configured under `forge.ai.operator.ticket-terminal`;
- ready-lane scheduler delay is `forge-ai.jobs.ready-to-start.fixed-delay-ms`.

## API Controllers

| Controller | File | Responsibility |
| --- | --- | --- |
| `ForgeAiStartController` | `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiStartController.java` | Implements generated `ForgeAiApi`; maps `StartForgeRequestDTO` to `ForgeAiStartCommand`; starts a ticket through `StartForgeAiTask`. |
| `ForgeAiOperatorUiController` | `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiOperatorUiController.java` | Implements generated `ForgeAiOperatorUiApi`; backs static Operator UI tickets/services/graph/lane APIs. Also has explicit `GET/PUT /api/v1/forge-ai/operator/ui/agents/config...` endpoints. |
| `ForgeAiOperatorController` | `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiOperatorController.java` | Implements generated `ForgeAiOperatorExecutionApi`; exposes active execution diagnostics and interrupt by execution id. |
| `ForgeAiTicketOperatorController` | `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiTicketOperatorController.java` | Implements generated `ForgeAiOperatorTicketApi`; exposes ticket-scoped operator run snapshots, NDJSON stream, heartbeat, and ticket interrupt. |
| `ForgeAiExceptionHandler` | `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiExceptionHandler.java` | REST exception mapping. |

Key API groups currently used or documented:

```text
POST /fgaisox/... start Forge ticket API from generated ForgeAiApi
GET  /fgaisox/api/v1/forge-ai/operator/ui/tickets
POST /fgaisox/api/v1/forge-ai/operator/ui/tickets
POST /fgaisox/api/v1/forge-ai/operator/ui/tickets/{ticketId}/execute
DELETE /fgaisox/api/v1/forge-ai/operator/ui/tickets/{ticketId}
GET  /fgaisox/api/v1/forge-ai/operator/ui/tickets/{ticketId}/graph
GET  /fgaisox/api/v1/forge-ai/operator/ui/tickets/{ticketId}/lanes/{laneId}
GET  /fgaisox/api/v1/forge-ai/operator/ui/services
GET  /fgaisox/api/v1/forge-ai/operator/ui/agents/config
PUT  /fgaisox/api/v1/forge-ai/operator/ui/agents/config/resources
GET  /fgaisox/api/v1/forge-ai/operator/executions
GET  /fgaisox/api/v1/forge-ai/operator/executions/active
GET  /fgaisox/api/v1/forge-ai/operator/executions/{executionId}
POST /fgaisox/api/v1/forge-ai/operator/executions/{executionId}/interrupt
GET  /fgaisox/api/v1/forge-ai/operator/tickets/active
GET  /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}
GET  /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/stream
POST /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/watchers/{watcherId}/heartbeat
POST /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/interrupt
GET  /fgaisox/actuator/health
```

## Domain Model Structure

Important domain files:

| File | Role |
| --- | --- |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/Ticket.java` | Aggregate-like ticket model containing task description, status, source TTY, and lanes. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/lane/Lane.java` | Lane state: agent, scope, service id, dependencies, status, attempt, input task ids. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/lane/Agent.java` | Agent enum and behavior dispatch to configured executor beans. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/AgentTicket.java` | Downstream task payload assigned from producing lanes to target lanes. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/laneexecution/LaneExecution.java` | Lane execution lifecycle state. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/laneexecution/LaneStepExecution.java` | Persisted strategy step result/evidence. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/codex/*` | Codex session, turn command/response, progress events, execution input context. |
| `domain/src/main/java/com/sitionix/forgeai/domain/model/operator/*` | Ticket operator run/event/status models. |

Important domain repository/port interfaces:

- `TicketRepository`;
- `LaneRepository`;
- `AgentTicketRepository`;
- `LaneExecutionRepository`;
- `LaneStrategyRepository`;
- `InstructionRepository`;
- `CompletionPayloadContractRepository`;
- `CodexSessionRepository`;
- `TicketOperatorRunRepository`;
- `TicketOperatorEventRepository`.

## Config-Driven Lane Graph

Agent/lane graph source:

```text
boot/src/main/resources/agent.yml
```

Current agents:

- `analyzer`;
- `architect`;
- `api`;
- `event`;
- `qa_lead`;
- `implement_be`;
- `implement_fe`;
- `test_unit`;
- `test_it`;
- `test_ui`;
- `reviewer`.

`agent.yml` defines:

- `enabled`;
- `scope_mode`;
- `groups`;
- `depends_on`;
- `produces`;
- `input_payloads`;
- completion flags such as `requires_api_evidence`, `writes_produced_lane_outputs`, `requires_output_for_every_target`, and `report_payload`.

Lane strategy source:

```text
boot/src/main/resources/lane-strategies.yml
```

This file defines per-agent strategy steps, instruction refs, task placeholders, and completion contract placeholders.

Service catalog/source scopes:

```text
boot/src/main/resources/services.yaml
```

This file defines service ids, paths, groups, repo/deploy/db/test metadata, domain keywords, and contract refs.

## Ticket Creation Flow

Main use case:

```text
application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java
```

Flow:

1. `ForgeAiStartController.startForge(...)` receives a generated API DTO.
2. `ForgeAiApiMapper` maps it into `ForgeAiStartCommand`, including terminal TTY from `TerminalTtyResolver`.
3. `StartForgeAiTaskUseCase.execute(...)` calls private `create(...)`.
4. `selectedServices(...)` validates ticket key, task text, and service ids against `ServicePropertiesProvider`.
5. `mapLane(...)` enumerates enabled `Agent.values()`.
6. Agent scope expansion comes from `agent.getInfo().getScopeMode().laneScopes(...)`.
7. Agent/service group compatibility filters lanes.
8. `resolveDependencies(...)` maps `depends_on` agents into scoped `LaneDependency` values.
9. `buildLane(...)` creates lanes with generated UUIDs.
10. `resolveLaneStatus(...)` sets `ANALYZER` to `READY_TO_START`; other lanes start `NOT_STARTED`.
11. `TicketRepository.save(...)` persists the ticket.
12. If auto-open is requested, `TicketOperatorRunService.initializeRun(...)` creates operator run state and `TicketOperatorTerminalAutoOpenService` may open a watcher terminal.

## Lane / Job Execution Flow

Scheduler:

```text
application/src/main/java/com/sitionix/forgeai/application/job/ReadyToStartLaneJob.java
```

Flow:

1. Scheduled method runs on `forge-ai.jobs.ready-to-start.fixed-delay-ms`.
2. It calls `TicketRepository.findAllReadyToStartLanes()`.
3. It skips lanes for cancelled ticket operator runs through `ManageTicketOperatorRuns.isExecutionBlocked(...)`.
4. It prevents duplicate dispatch with an in-memory `dispatchingLaneIds` set.
5. It atomically moves lane to `IN_PROGRESS` via `ticketRepository.moveLaneToInProgressIfReady(laneId)`.
6. It submits async work to `laneExecutionTaskExecutor`.
7. It executes `lane.getAgent().executeLane(lane)`, which delegates to the configured agent executor.

Execution use case:

```text
application/src/main/java/com/sitionix/forgeai/application/usecase/SupervisedLaneExecutionUseCase.java
```

Flow:

1. Loads `LaneStrategy` from `LaneStrategyRepository`.
2. Creates a `LaneExecution` through `LaneExecutionProgressService.createStartingExecution(...)`.
3. Opens a Codex session through `CodexSessionRepository.openSession(...)`.
4. Builds a start prompt and per-step prompts through `LaneStepPromptBuilder`.
5. Sends one turn per strategy step through `CodexSessionRepository.submitTurn(...)`.
6. Parses each response with `LaneStepDoneResultParser`.
7. On final step, validates final completion payload through `LaneCompletionDispatcher.validateFinalCompletionPayload(...)`.
8. Persists each step as `LaneStepExecution`.
9. On final step, calls `LaneCompletionDispatcher.completeLane(...)`.
10. Publishes operator events through `ManageTicketOperatorRuns`.
11. Closes the Codex session when completed.

Completion/downstream routing:

- `application/src/main/java/com/sitionix/forgeai/application/laneexecution/LaneCompletionDispatcher.java`;
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/LaneCompletionSupport.java`;
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteLaneCompletionUseCase.java`;
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentTasksUseCase.java`;
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CreateAgentTaskUseCase.java`.

Target lanes come from the configured `produces` relationships and matching target `input_payloads`, not from hardcoded Jarvis-style command actions.

## Codex Launch Flow

Domain port:

```text
domain/src/main/java/com/sitionix/forgeai/domain/repository/CodexSessionRepository.java
```

Infrastructure adapter:

```text
infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexAppServerSessionRepository.java
```

Process starter:

```text
infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/DefaultCodexAppServerProcessStarter.java
```

Default command config:

```text
infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexAppServerProperties.java
```

Flow:

1. `DefaultCodexAppServerProcessStarter.start()` copies configured command and starts it with `new ProcessBuilder(command).start()`.
2. It detects Codex version by running the first command token with `--version`.
3. `CodexAppServerSessionRepository.openSession(...)` creates a local session id and wraps the process in `CodexJsonRpcClient`.
4. It sends JSON-RPC `initialize`.
5. It sends `thread/start` with `cwd`, `runtimeWorkspaceRoots`, `approvalPolicy`, `sandbox`, `serviceName`, and optional model/provider.
6. `submitTurn(...)` sends JSON-RPC `turn/start`.
7. If the turn is not immediately completed, it waits for completion events through `CodexJsonRpcClient.awaitCompletedTurn(...)`.
8. It extracts assistant response text and returns `CodexTurnResponse`.
9. Progress events are emitted through optional `CodexProgressObserver`.

This is a Codex orchestration transport. It must stay separate from Jarvis allowlisted local actions.

## MongoDB Persistence

Mongo adapter module:

```text
infrastructure/mongodb
```

Documents/collections:

| Document | Collection |
| --- | --- |
| `TicketDocument` | `tickets` |
| `AgentTicketDocument` | `agent_tickets` |
| `LaneExecutionDocument` | `lane_executions` |
| `LaneStepExecutionDocument` | `lane_step_executions` |
| `TicketOperatorRunDocument` | `ticket_operator_runs` |
| `TicketOperatorEventDocument` | `ticket_operator_events` |

Repository adapters:

- `TicketRepositoryImpl`;
- `LaneRepositoryImpl`;
- `AgentTicketRepositoryImpl`;
- `LaneExecutionRepositoryImpl`;
- `TicketOperatorRunRepositoryImpl`;
- `TicketOperatorEventRepositoryImpl`.

Spring Data repositories:

- `TicketJpaRepository`;
- `AgentTicketJpaRepository`;
- `LaneExecutionJpaRepository`;
- `LaneStepExecutionJpaRepository`;
- `TicketOperatorRunJpaRepository`;
- `TicketOperatorEventJpaRepository`.

Jarvis should not write to these collections directly.

## Instruction / Resource Infrastructure

Resource module:

```text
infrastructure/resources
```

Important classes:

- `ResourceInstructionRepository`;
- `ResourceLaneStrategyRepository`;
- `ResourceCompletionPayloadContractRepository`;
- `InstructionResourcesProperties`;
- `LaneStrategiesProperties`.

Instruction resources live under:

```text
infrastructure/resources/src/main/resources/instructions/
```

This is the Forge AI prompt/instruction system for lane agents. Jarvis has its own `infrastructure/jarvis/config/system-prompt.md` and should not be mixed into lane instruction resources.

## Operator Visibility

Operator runtime is ticket-scoped and execution-aware:

- `TicketOperatorRunService`;
- `TicketOperatorEventService`;
- `TicketOperatorWatcherLeaseMonitorJob`;
- `TicketOperatorTerminalAutoOpenService`;
- `ShellTicketOperatorTerminalLauncher`;
- `TicketOperatorStreamResourceFactory`;
- `scripts/forge-ai-watch-ticket.sh`;
- `scripts/forge-ai-open-ticket-terminal.sh`.

The UI and terminal watcher show ticket/lane/Codex progress. Jarvis command audit should remain its own infrastructure concern until a Forge-side Jarvis proxy is added.

## Local Scripts

Current Forge scripts:

```text
scripts/forge-ai-start.sh
scripts/forge-ai-watch-ticket.sh
scripts/forge-ai-open-ticket-terminal.sh
```

Current Jarvis wrappers:

```text
scripts/jarvis/bootstrap.sh
scripts/jarvis/start.sh
scripts/jarvis/status.sh
scripts/jarvis/smoke-test.sh
scripts/jarvis/stop.sh
```

`README.md` mentions `just forge-ai-start`, but no `Justfile` or `justfile` exists in the repository at this audit point.

## Architecture Risks

- Adding Jarvis as an `Agent` or `agent.yml` lane would corrupt the boundary between engineering-ticket orchestration and local assistant infrastructure.
- Adding arbitrary shell execution to Forge AI application/domain would make the core platform unsafe.
- Letting Jarvis mutate ticket/lane Mongo state would bypass Forge AI use cases and break invariants.
- Letting Operator UI call Jarvis directly long-term would make local port/runtime details part of browser UX.
- Extending Java infrastructure `pom.xml` to include Python Jarvis would break the current Maven module model.

## Recommended Next Architecture Task

Implement a Forge-side Jarvis proxy as a narrow application port and HTTP adapter, without UI work first:

```text
api-rest controller
  -> application JarvisGateway port
  -> infrastructure HTTP client adapter
  -> http://127.0.0.1:7071/api/v1/jarvis/*
```

This gives the Operator UI a stable Forge AI API later while keeping Jarvis independent.
