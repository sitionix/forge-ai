# Forge AI Current State Analysis

## Repository

- Path: `/home/chekoteela/Documents/java/Sitionix/forge-ai`
- Remote: `git@github.com:sitionix/forge-ai.git`
- Branch used for this integration: `feature/SITIONIX-27`

## Project Type And Runtime

Forge AI is a Java 21, Spring Boot 3.3.4, multi-module Maven backend. It orchestrates multi-lane AI execution for engineering tickets.

Root modules:

- `domain` - ticket, lane, agent, Codex, operator, repository contracts, and use case interfaces.
- `application` - use cases, lane execution orchestration, prompt building, scheduler, operator services.
- `api-rest` - REST controllers and API DTO mapping.
- `infrastructure/resources` - instruction resources, lane strategy resources, completion contracts.
- `infrastructure/mongodb` - MongoDB persistence adapters.
- `infrastructure/codex-cli` - Codex app-server JSON-RPC adapter over stdio.
- `infrastructure/github-cli` - GitHub evidence adapter.
- `boot` - Spring Boot entrypoint and runtime config.
- `jacoco-report` - reporting module.

## Local Startup

The README states the local entrypoint is:

```bash
just forge-ai-start
```

The underlying helper is:

- `scripts/forge-ai-start.sh`

That script expects to run from the parent workspace containing `forge-ai`, builds/starts the local Spring Boot service, creates a ticket from user input, and opens a ticket watcher terminal.

The Spring Boot app is:

- `boot/src/main/java/com/sitionix/forgeai/Application.java`

Default server config:

- Port: `9099`
- Context path: `/fgaisox`
- Health: `/fgaisox/actuator/health`

## Database And Storage

Forge AI uses MongoDB by default:

- Config: `boot/src/main/resources/application.yml`
- URI: `${MONGODB_URI:mongodb://localhost:27019/forge_ai}`

Persistence adapters live under:

- `infrastructure/mongodb`

Repository contracts live under:

- `domain/src/main/java/com/sitionix/forgeai/domain/repository`

## REST API

Controllers:

- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiStartController.java`
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiOperatorController.java`
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiTicketOperatorController.java`
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiOperatorUiController.java`

Important endpoints documented in README:

- `GET /fgaisox/api/v1/forge-ai/operator/tickets/active`
- `GET /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}`
- `GET /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/stream?watcherId=...&verbosity=minimal`
- `POST /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/watchers/{watcherId}/heartbeat`
- `POST /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/interrupt`
- `GET /fgaisox/api/v1/forge-ai/operator/executions`
- `GET /fgaisox/api/v1/forge-ai/operator/executions/active`
- `GET /fgaisox/api/v1/forge-ai/operator/executions/{executionId}`
- `POST /fgaisox/api/v1/forge-ai/operator/executions/{executionId}/interrupt`

Start ticket endpoint is implemented through generated API interface `ForgeAiApi` in `ForgeAiStartController.startForge(...)`.

## Ticket, Lane, Agent, And Job Model

Ticket and lane domain classes:

- `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/Ticket.java`
- `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/lane/Lane.java`
- `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/lane/Agent.java`

Agent graph source:

- `boot/src/main/resources/agent.yml`

Configured agents include:

- `analyzer`
- `architect`
- `api`
- `event`
- `qa_lead`
- `implement_be`
- `implement_fe`
- `test_unit`
- `test_it`
- `test_ui`
- `reviewer`

Lane creation starts in:

- `application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java`

Ready lane scheduling:

- `application/src/main/java/com/sitionix/forgeai/application/job/ReadyToStartLaneJob.java`

Default schedule:

- `forge-ai.jobs.ready-to-start.fixed-delay-ms: 10000`

## Codex Launch Flow

Codex integration is a headless app-server JSON-RPC adapter over stdio.

Main classes:

- `domain/src/main/java/com/sitionix/forgeai/domain/repository/CodexSessionRepository.java`
- `infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexAppServerSessionRepository.java`
- `infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexJsonRpcClient.java`
- `infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/DefaultCodexAppServerProcessStarter.java`

Default command:

```text
codex app-server --stdio
```

Defined in:

- `CodexAppServerProperties.command`

## Terminal Windows And Operator Visibility

Terminal watcher scripts:

- `scripts/forge-ai-open-ticket-terminal.sh`
- `scripts/forge-ai-watch-ticket.sh`

Server-side launcher classes:

- `application/src/main/java/com/sitionix/forgeai/application/operator/ShellTicketOperatorTerminalLauncher.java`
- `application/src/main/java/com/sitionix/forgeai/application/operator/TicketOperatorTerminalAutoOpenService.java`
- `application/src/main/java/com/sitionix/forgeai/application/operator/TicketOperatorRunService.java`

The local helper opens one ticket-scoped watcher terminal per created ticket.

## Prompt Construction

Prompt construction lives in:

- `application/src/main/java/com/sitionix/forgeai/application/laneexecution/LaneStepPromptBuilder.java`

It builds:

- `START_PROMPT`
- `STEP_PROMPT`
- `CORRECTION_PROMPT`

Instruction resources are loaded from:

- `infrastructure/resources/src/main/resources/instructions.yaml`
- `infrastructure/resources/src/main/resources/instructions/**`

Resource loader:

- `infrastructure/resources/src/main/java/com/sitionix/forgeai/infrastructure/resources/ResourceInstructionRepository.java`

## Logs And Execution State

Execution state is persisted through MongoDB repositories in `infrastructure/mongodb`.

Codex progress is handled by:

- `application/src/main/java/com/sitionix/forgeai/application/laneexecution/LaneExecutionProgressService.java`

Progress logger:

- `com.sitionix.forgeai.codex.progress`

Operator run/event state:

- `domain/src/main/java/com/sitionix/forgeai/domain/model/operator/TicketOperatorRun.java`
- `domain/src/main/java/com/sitionix/forgeai/domain/model/operator/TicketOperatorEvent.java`
- `infrastructure/mongodb/src/main/java/com/sitionix/forgeai/infrastructure/mongodb/entity/operator`

## Action Execution Concepts

Forge AI already executes Codex app-server processes and opens local watcher terminals, but these are bounded platform actions. It does not contain a general local assistant command executor.

Jarvis must remain separate from:

- Codex lane execution.
- `agent.yml` lane graph.
- ticket operator terminal launching.

## Jarvis Fit

Jarvis now lives as a separate infrastructure subsystem:

- `infrastructure/jarvis/services/jarvis-agent`
- `infrastructure/jarvis/config`
- `infrastructure/jarvis/scripts`
- `scripts/jarvis` root wrappers

This keeps Forge AI as the central local platform while preserving Jarvis as a bounded infrastructure module with its own local command safety boundary.
