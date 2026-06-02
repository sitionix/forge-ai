# Forge AI (`forge-ai`) - Full Technical Overview

This document describes the current Forge AI project from architecture to runtime flow, instruction loading, configs, and operational behavior.

## 1. Purpose

`forge-ai` orchestrates multi-lane AI execution for engineering tickets.

At a high level it does three things:
- builds a lane graph per ticket (`analyzer`, `architect`, `api`, `implement_*`, `test_*`, `reviewer`, etc.);
- schedules/starts ready lanes and sends execution input to Codex CLI;
- accepts lane completion callbacks, creates downstream tasks, and advances lane/ticket state.

## 2. Module Structure (Maven Multi-Module)

Root modules:
- `domain` - domain models, ports, repository contracts, lane/ticket abstractions.
- `application` - use cases, lane execution orchestration, scheduling job.
- `api-rest` - REST controller, callback orchestration use cases, API mappers, validators.
- `infrastructure/resources` - instruction repository + instruction resources (`instructions/*.md`, `instructions.yaml`).
- `infrastructure/mongodb` - Mongo adapters and repositories.
- `infrastructure/codex-cli` - adapter that serializes prompt payload and launches `codex` CLI.
- `boot` - Spring Boot entrypoint and runtime configuration binding.
- `jacoco-report` - reporting module.

## 3. Runtime Entry Points

### 3.1 Start ticket
REST endpoint implemented in `ForgeAiController.startForge(...)`:
- receives `StartForgeRequestDTO`;
- maps to `ForgeAiStartCommand`;
- calls `StartForgeAiTaskUseCase`;
- returns created ticket + lanes.

Main classes:
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiController.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java`

### 3.2 Scheduler loop
`ReadyToStartLaneJob` runs periodically:
- loads all lanes with `READY_TO_START`;
- calls `lane.getAgent().executeLane(lane)`.

Main class:
- `application/src/main/java/com/sitionix/forgeai/application/job/ReadyToStartLaneJob.java`

### 3.3 Lane callback completion
Each lane completion endpoint in `ForgeAiController`:
- validates scope/lane state (`LaneScopeValidator` and lane-specific checks);
- maps completion DTO to downstream `AgentTicket` payloads;
- calls `CompleteAgentTasksUseCase` or specific orchestration use case;
- marks source lane completed when downstream assignment conditions are satisfied.

## 4. Agent/Lane Graph Source of Truth

Lane definitions are configured in:
- `boot/src/main/resources/agent.yml`

Each agent defines:
- `id`
- `enabled`
- `scope_mode` (`per_scope` or `global`)
- `groups` (`backend`, `frontend`)
- `depends_on`
- `produces`

At startup, `AgentInfoInjector` injects this config into `Agent` enum and binds executors.

Main class:
- `application/src/main/java/com/sitionix/forgeai/config/AgentInfoInjector.java`

## 5. Lane Creation Flow

`StartForgeAiTaskUseCase`:
1. resolves selected services from incoming request;
2. enumerates all enabled agents;
3. expands scope by `ScopeMode`:
   - per-service path for `per_scope`
   - `global` pseudo-scope for global agents;
4. filters agents by service group compatibility;
5. builds lane dependencies from `depends_on` with scope mapping;
6. creates `Ticket` with lanes:
   - `ANALYZER` starts as `READY_TO_START`
   - other lanes start as `NOT_STARTED`.

Class:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java`

## 6. Lane Execution Flow to Codex

### 6.1 Prepare execution input
`PrepareAgentExecutionInputUseCase.execute(lane)` builds `AgentExecutionInput`:
- `ticketId`, `ticketKey`, `laneId`
- `agentInstruction`
- `contractApi.path` and `contractApi.endpoint`
- `additionalInstructions`
- `sharedInstructions`

It atomically moves lane from `READY_TO_START` to `IN_PROGRESS` via:
- `ticketRepository.moveLaneToInProgressIfReady(laneId)`.

Then `enrichWithTasks(...)` injects:
- lane tasks (`AgentTicketPayload` set)
- `scopeContext` from `services.yaml` (`label`, `tags`, `domainKeywords`, `ownsBusinessAreas`, etc.) when available.

Main class:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/PrepareAgentExecutionInputUseCase.java`

### 6.2 Resolve tasks for non-analyzer lanes
`TaskDrivenCodexAgentExecutor`:
- loads source lane from repository;
- resolves input task payloads via `LaneTaskResolver` from `inputTaskIds`.

Classes:
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/TaskDrivenCodexAgentExecutor.java`
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/LaneTaskResolver.java`

`AnalyzeAgentExecutor` is special:
- uses original ticket text as analyzer task payload.

### 6.3 Send payload to Codex
Normal production execution uses a supervised Codex session adapter:
- opens one Codex app-server session per lane execution;
- submits one logical turn per strategy step;
- waits for the assistant response for that exact turn;
- persists the accepted step result before the next step starts.

Classes:
- `infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexAppServerSessionRepository.java`
- `infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexJsonRpcClient.java`

## 7. Instruction System (How instructions are loaded)

### 7.1 Sources
- `infrastructure/resources/src/main/resources/instructions.yaml` - instruction routing config.
- `infrastructure/resources/src/main/resources/instructions/agents/*.md` - lane-specific instructions.
- `infrastructure/resources/src/main/resources/instructions/shared/*.md` - shared instructions for all lanes.
- `infrastructure/resources/src/main/resources/instructions/additional-instructions/*.md` - reusable workflow packs.
- `infrastructure/resources/src/main/resources/instructions/architecture/*.md` - architecture guidance.

### 7.2 Loader behavior
`ResourceInstructionRepository` loads instruction file text from classpath at startup:
- per-agent main instruction
- per-agent additional instruction texts
- shared instruction texts

On `findInstructionsByAgentId(agentId)` it returns in-memory text bundles.

Important: current implementation returns **full text content**, not file refs.

Class:
- `infrastructure/resources/src/main/java/com/sitionix/forgeai/infrastructure/resources/ResourceInstructionRepository.java`

## 8. Completion and Downstream Task Routing

### 8.1 Generic downstream completion flow
`CompleteAgentTasksUseCase.complete(sourceLaneId, tickets)`:
- if no downstream tickets: complete lane immediately (`CompleteAgentUseCase`);
- if tickets exist: persist each ticket via `CreateAgentTaskUseCase` and assign to produced lane.

Classes:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentTasksUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CreateAgentTaskUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentUseCase.java`

### 8.2 Lane-specific orchestration use cases (API layer)
- `CompleteArchitectLaneOrchestrationUseCase`:
  - routes implementation ticket to `implement_be` or `implement_fe` based on service group;
  - creates/marks-not-needed `api` and `event` global lanes.
- `CompleteApiLaneOrchestrationUseCase`:
  - filters contracts by produced implementation scopes;
  - creates implementation payloads from contract results.
- `CompleteQaLeadLaneOrchestrationUseCase`:
  - conditionally routes to `test_unit`, `test_it`, `test_ui` per requirements flags.
- `CompleteItTestLaneOrchestrationUseCase`:
  - stores IT completion report and completes lane.
- `CompleteUnitTestLaneOrchestrationUseCase`:
  - creates reviewer payload and routes onward.

Files:
- `api-rest/src/main/java/com/sitionix/forgeai/api/usecase/*.java`

### 8.3 Reviewer completion
`CompleteReviewerTaskUseCase`:
- finds reviewer lane;
- sets reviewer lane `COMPLETED`;
- sets ticket status `RESOLVED`;
- saves ticket.

Class:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteReviewerTaskUseCase.java`

## 9. Lane Status and Readiness Rules

Key statuses:
- `NOT_STARTED`
- `READY_TO_START`
- `IN_PROGRESS`
- `COMPLETED`
- `NOT_NEEDED`

Readiness is computed in `TicketRepositoryImpl.isReadyToStart(...)`:
- all dependencies must be `COMPLETED` or `NOT_NEEDED`;
- reviewer lane becomes ready only when all other lanes are terminal.

`moveReviewerToReadyToStartIfPossible(...)` promotes reviewer accordingly.

Class:
- `infrastructure/mongodb/src/main/java/com/sitionix/forgeai/infrastructure/mongodb/adapter/TicketRepositoryImpl.java`

## 10. Scope Validation and Safety Checks

`LaneScopeValidator` enforces:
- lane/agent type matches callback endpoint;
- callback scope matches lane scope;
- lane must be `IN_PROGRESS` for completion;
- API callback contracts include scopes relevant to produced implementation lanes.

Class:
- `api-rest/src/main/java/com/sitionix/forgeai/api/LaneScopeValidator.java`

## 11. Persistence Model (Mongo)

Primary documents:
- `TicketDocument` (ticket + embedded lanes)
- `LaneDocument`
- `LaneDependencyDocument`
- `AgentTicketDocument`

Adapters:
- `TicketRepositoryImpl`
- `LaneRepositoryImpl`
- `AgentTicketRepositoryImpl`

Mappers:
- `TicketEntityMapper`, `LaneEntityMapper`, `AgentTicketEntityMapper`, etc.

## 12. API Contract and DTO Mapping

Controller uses generated DTOs from API-first package:
- `com.app_afesox.fgaisox.api_first.*`

Mapping layer:
- `ForgeAiApiMapper`
- `AgentTicketApiMapper`
- lane-payload mappers (`ArchitectTicketPayloadApiMapper`, etc.)

All main endpoint methods are in `ForgeAiController`.

## 13. Configuration Files (YAML) and Roles

### 13.1 `boot/src/main/resources/application.yml`
Defines:
- server port/context path (`9099`, `/fgaisox`)
- Mongo connection
- config imports (`services.yaml`, `agent.yml`, `instructions.yaml`)
- scheduler delay (`forge-ai.jobs.ready-to-start.fixed-delay-ms`)
- default launcher base URL

### 13.2 `boot/src/main/resources/agent.yml`
Defines lane graph and dependencies between agents.

### 13.3 `boot/src/main/resources/services.yaml`
Defines service catalog and metadata:
- path, group, tags
- domain ownership hints
- contract references (`api` / `events`)
- generated artifact names
- deploy/db metadata

### 13.4 `infrastructure/resources/src/main/resources/instructions.yaml`
Defines instruction resolution:
- main instruction file per agent
- completion endpoint per agent
- additional instructions per agent
- shared instruction list used for all agents

### 13.5 CI workflow YAML
- `.github/workflows/build.yml`
- `.github/workflows/cleanup-merged-branch.yml`

## 14. Scripts

Repository scripts:
- `scripts/forge-ai-start.sh` - local start helper.
- `scripts/forge-callback-curl.sh` - callback wrapper used by agent instructions.

Codex CLI transport is handled through the app-server JSON-RPC adapter. No prompt-file launcher scripts remain in the normal runtime.

## 15. Testing Layout

- Unit tests per module in `src/test`.
- Integration tests in `boot/src/test/java/com/sitionix/forgeai/it/*` with ForgeIT style.
- Mapper tests in `api-rest/src/test/java/com/sitionix/forgeai/mapper/*`.
- Instruction loader tests in `infrastructure/resources/src/test/*`.

## 16. End-to-End Runtime Sequence (Condensed)

1. Client calls `startForge`.
2. Ticket + lane graph is created in Mongo.
3. Scheduler picks `READY_TO_START` lanes.
4. Lane executor builds `AgentExecutionInput` (instructions + tasks + scope context).
5. Codex CLI is launched with JSON prompt.
6. Agent performs lane work and calls completion endpoint.
7. Controller validates scope/state and routes payload to downstream tasks.
8. Source lane is completed when downstream assignment is consistent.
9. New produced lanes become `READY_TO_START` when dependencies are terminal.
10. Reviewer lane becomes ready when all other lanes are terminal.
11. Reviewer completion marks ticket `RESOLVED`.

## 17. Important Current Characteristics

- Instruction loading is eager + text-inline (full markdown content in payload).
- No built-in staged/lazy instruction loading at orchestrator level yet.
- Lane execution is polling-based (`ReadyToStartLaneJob`) rather than event-stream based.
- Lane routing rules depend on both static graph (`agent.yml`) and callback payload semantics.

## 18. Useful File Index (Quick Jump)

Core flow:
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiController.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/job/ReadyToStartLaneJob.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/PrepareAgentExecutionInputUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentTasksUseCase.java`

Instruction system:
- `infrastructure/resources/src/main/resources/instructions.yaml`
- `infrastructure/resources/src/main/java/com/sitionix/forgeai/infrastructure/resources/ResourceInstructionRepository.java`
- `infrastructure/resources/src/main/resources/instructions/**`

Codex transport:
- `infrastructure/codex-cli/src/main/java/com/sitionix/forgeai/infrastructure/codexcli/adapter/appserver/CodexAppServerSessionRepository.java`

Persistence:
- `infrastructure/mongodb/src/main/java/com/sitionix/forgeai/infrastructure/mongodb/adapter/TicketRepositoryImpl.java`
- `infrastructure/mongodb/src/main/java/com/sitionix/forgeai/infrastructure/mongodb/adapter/LaneRepositoryImpl.java`
- `infrastructure/mongodb/src/main/java/com/sitionix/forgeai/infrastructure/mongodb/adapter/AgentTicketRepositoryImpl.java`

Config:
- `boot/src/main/resources/application.yml`
- `boot/src/main/resources/agent.yml`
- `boot/src/main/resources/services.yaml`
