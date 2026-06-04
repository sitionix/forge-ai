# Forge AI (`forge-ai`) - Full Technical Overview

This document describes the current Forge AI project from architecture to runtime flow, instruction loading, configs, and operational behavior.

## 1. Purpose

`forge-ai` orchestrates multi-lane AI execution for engineering tickets.

At a high level it does three things:
- builds a lane graph per ticket (`analyzer`, `architect`, `api`, `implement_*`, `test_*`, `reviewer`, etc.);
- schedules/starts ready lanes and sends execution input to the headless Codex app-server runtime;
- validates supervised final-step completion payloads, creates downstream tasks, and advances lane/ticket state.

## 2. Module Structure (Maven Multi-Module)

Root modules:
- `domain` - domain models, ports, repository contracts, lane/ticket abstractions.
- `application` - use cases, lane execution orchestration, scheduling job.
- `api-rest` - API-first REST controllers for ticket start and operator/diagnostic endpoints.
- `infrastructure/resources` - instruction repository + instruction resources (`instructions/*.md`, `instructions.yaml`).
- `infrastructure/mongodb` - Mongo adapters and repositories.
- `infrastructure/codex-cli` - Codex app-server JSON-RPC transport adapter for supervised lane execution.
- `boot` - Spring Boot entrypoint and runtime configuration binding.
- `jacoco-report` - reporting module.

## 3. Runtime Entry Points

### 3.1 Start ticket
REST endpoint implemented in `ForgeAiStartController.startForge(...)`:
- receives `StartForgeRequestDTO`;
- maps to `ForgeAiStartCommand`;
- calls `StartForgeAiTaskUseCase`;
- returns created ticket + lanes.

Main classes:
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiStartController.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java`

### 3.2 Scheduler loop
`ReadyToStartLaneJob` runs periodically:
- loads all lanes with `READY_TO_START`;
- calls `lane.getAgent().executeLane(lane)`.

Main class:
- `application/src/main/java/com/sitionix/forgeai/application/job/ReadyToStartLaneJob.java`

### 3.3 Lane supervised completion
Lane completion is not an HTTP transport protocol. The supervised runner validates the final strategy-step response and then:
- reads `evidence.completionPayload`;
- delegates validation/completion through `LaneCompletionDispatcher`;
- calls `lane.getAgent().validateFinalCompletionPayload(...)` and `lane.getAgent().completeLane(...)`;
- creates downstream `AgentTicket` payloads for target lanes selected from `agent.yml` `produces`;
- marks the source lane completed when downstream assignment conditions are satisfied.

Main classes:
- `application/src/main/java/com/sitionix/forgeai/application/laneexecution/LaneCompletionDispatcher.java`
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/LaneCompletionSupport.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteLaneCompletionUseCase.java`

## 4. Agent/Lane Graph Source of Truth

Lane definitions are configured in:
- `boot/src/main/resources/agent.yml`

Each agent defines:
- `id`
- `enabled`
- `scope_mode` (`per_scope` or `global`)
- `groups` (`backend`, `frontend`)
- `depends_on` - readiness dependencies
- `produces` - downstream lanes that may receive completion output
- `input_payloads` - payload contract accepted by this target agent from each source agent
- `completion` flags such as API evidence, optional outputs, or completion report payload

At startup, `AgentInfoInjector` injects this config into `Agent` enum, binds executors, and validates that every output-producing `produces` edge has a matching target `input_payloads` contract.

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
Codex-backed executors:
- load source lane from repository;
- resolve input task payloads via `LaneTaskResolver` from `inputTaskIds`;
- hand the prepared execution input to the supervised strategy-driven runner.

Classes:
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/SupervisedTaskDrivenAgentExecutor.java`
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

## 7. Operator Runtime Visibility

The headless Codex runtime stays headless. Codex transport is still app-server JSON-RPC over stdio, but operator visibility is exposed separately:
- Spring Boot progress logger: `com.sitionix.forgeai.codex.progress`
- ticket-scoped operator watcher terminal:
  - `scripts/forge-ai-open-ticket-terminal.sh`
  - `scripts/forge-ai-watch-ticket.sh`

The local start helper opens **one operator terminal per created ticket**, not per lane and not per Codex session.
The real local entrypoint is:

```bash
just forge-ai-start
```

That command delegates to `forge-ai/scripts/forge-ai-start.sh`, which creates the ticket and opens the watcher terminal. If local scripts are bypassed, server-side fallback can be enabled with:

```yaml
forge:
  ai:
    operator:
      ticket-terminal:
        auto-open-on-ticket-start: true
        launcher: auto
```

Useful ticket-scoped operator endpoints:
- `GET /fgaisox/api/v1/forge-ai/operator/tickets/active`
- `GET /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}`
- `GET /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/stream?watcherId=...&verbosity=minimal`
- `POST /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/watchers/{watcherId}/heartbeat`
- `POST /fgaisox/api/v1/forge-ai/operator/tickets/{ticketId}/interrupt`

Optional execution-scoped endpoints still exist for direct diagnostics:
- `GET /fgaisox/api/v1/forge-ai/operator/executions`
- `GET /fgaisox/api/v1/forge-ai/operator/executions/active`
- `GET /fgaisox/api/v1/forge-ai/operator/executions/{executionId}`
- `POST /fgaisox/api/v1/forge-ai/operator/executions/{executionId}/interrupt`

Local commands:

```bash
curl -s http://localhost:9099/fgaisox/api/v1/forge-ai/operator/tickets/active | jq
curl -s http://localhost:9099/fgaisox/api/v1/forge-ai/operator/tickets/<ticketId> | jq
curl -s -X POST http://localhost:9099/fgaisox/api/v1/forge-ai/operator/tickets/<ticketId>/interrupt | jq

scripts/forge-ai-watch-ticket.sh <ticketId> http://localhost:9099/fgaisox <watcherId> minimal
scripts/forge-ai-open-ticket-terminal.sh <ticketId> http://localhost:9099/fgaisox <watcherId> minimal
```

App-server diagnostics:

```bash
codex --version
codex doctor
codex debug app-server send-message-v2 ping
```

## 8. Instruction System (How instructions are loaded)

### 8.1 Sources
- `infrastructure/resources/src/main/resources/instructions.yaml` - instruction routing config.
- `infrastructure/resources/src/main/resources/instructions/agents/*.md` - lane-specific instructions.
- `infrastructure/resources/src/main/resources/instructions/shared/*.md` - shared instructions for all lanes.
- `infrastructure/resources/src/main/resources/instructions/additional-instructions/*.md` - reusable workflow packs.
- `infrastructure/resources/src/main/resources/instructions/architecture/*.md` - architecture guidance.

### 8.2 Loader behavior
`ResourceInstructionRepository` loads instruction file text from classpath at startup:
- per-agent main instruction
- per-agent additional instruction texts
- shared instruction texts

On `findInstructionsByAgentId(agentId)` it returns in-memory text bundles.

Important: current implementation returns **full text content**, not file refs.

Class:
- `infrastructure/resources/src/main/java/com/sitionix/forgeai/infrastructure/resources/ResourceInstructionRepository.java`

## 9. Completion and Downstream Task Routing

### 9.1 Generic downstream completion flow
`CompleteAgentTasksUseCase.complete(sourceLaneId, tickets)`:
- if no downstream tickets: complete lane immediately (`CompleteAgentUseCase`);
- if tickets exist: persist each ticket via `CreateAgentTaskUseCase` and assign to produced lane.

Classes:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentTasksUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CreateAgentTaskUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteAgentUseCase.java`

### 9.2 Agent/YAML-driven completion contracts
Each `Agent` delegates completion behavior to its bound executor:
- `Agent.validateFinalCompletionPayload(...)`
- `Agent.completeLane(...)`
- `Agent.inputPayloadTypeFrom(sourceAgent)`
- `Agent.requiresApiCompletionEvidence()`
- `Agent.requiresCompletionOutputForEveryTarget()`

`LaneCompletionSupport` is generic:
- target lanes come from `LaneRepository.findCompletionTargetLanes(sourceLaneId)`;
- that repository method uses the source agent `produces` list from `agent.yml`;
- output payloads are matched by target `agent` + `scope`;
- payload classes are resolved from the target agent `input_payloads` map in `agent.yml`;
- output behavior is resolved from the source agent `completion` config in `agent.yml`.

There are no lane-completion REST endpoints in the normal supervised flow.

### 9.3 Adding a new agent
To add a new agent that participates in the ticket flow out of the box, keep the lane graph and payload routing driven by `agent.yml`. Do not add scheduler, dispatcher, readiness, or hardcoded downstream-agent changes for a normal agent.

Required steps:
- Add the agent to `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/lane/Agent.java` with its stable `id` and executor bean name.
- Add the agent config to `boot/src/main/resources/agent.yml`.
- Set `enabled`, `scope_mode`, and `groups` so lane creation can derive the correct scopes.
- Set `depends_on` so readiness is computed from YAML, not from code.
- Set `produces` for downstream lanes this agent may feed.
- For every source agent that can produce input for this agent, add `input_payloads.<source_agent_id>`.
- For every target agent in this agent's `produces`, ensure the target has a matching `input_payloads.<this_agent_id>` entry.
- Configure `completion` only when the default behavior is not enough, for example `requires_api_evidence`, `writes_produced_lane_outputs`, `requires_output_for_every_target`, or `report_payload`.
- Add typed payload classes under `domain/src/main/java/com/sitionix/forgeai/domain/model/ticket/agentticket`.
- Register every new payload id in `AgentTicketPayloadType` so YAML payload ids resolve to Java payload classes.
- Add a Spring executor under `application/src/main/java/com/sitionix/forgeai/application/agentexecutor`.
- For a normal supervised Codex lane, extend `SupervisedTaskDrivenAgentExecutor`, implement `ExecuteAgent<YourPayload>`, and call `executeWithSupervisor(lane)` from `executeLane(...)`.
- Implement `validateFinalCompletionPayload(...)` in the executor. Start with generic `LaneCompletionSupport` validation, then add only agent-specific validation.
- Implement `completeLane(...)` in the executor. Use `LaneCompletionSupport.completeProducedLaneInputs(...)` for downstream output routing unless the agent intentionally has no produced outputs or writes a completion report.
- Add the agent instruction file under `infrastructure/resources/src/main/resources/instructions/agents`.
- Add step instruction files under `infrastructure/resources/src/main/resources/instructions/lane-instructions/<agent_id>`.
- Register instruction routing in `infrastructure/resources/src/main/resources/instructions.yaml`.
- Add the agent strategy and ordered steps to `boot/src/main/resources/lane-strategies.yml`.

Validation pattern:

```java
@Override
public void validateFinalCompletionPayload(final ReadyToStartLane lane,
                                           final Map<String, Object> completionPayload) {
    this.laneCompletionSupport.validateProducedLaneInputs(lane, completionPayload);
    this.validateAgentSpecificRules(lane, completionPayload);
}

@Override
public void completeLane(final ReadyToStartLane lane,
                         final Map<String, Object> completionPayload) {
    this.laneCompletionSupport.completeProducedLaneInputs(lane, completionPayload);
}
```

If the agent must not produce downstream tasks, validate that explicitly:

```java
@Override
public void validateFinalCompletionPayload(final ReadyToStartLane lane,
                                           final Map<String, Object> completionPayload) {
    this.laneCompletionSupport.validateNoOutputs(completionPayload);
}

@Override
public void completeLane(final ReadyToStartLane lane,
                         final Map<String, Object> completionPayload) {
    this.completeAgentTasks.complete(lane.getLaneId(), List.of());
}
```

Rules:
- `agent.yml` remains the source of truth for dependencies, produced lanes, target scopes, and payload contracts.
- Executors may validate their own completion payloads, but they must not hardcode the whole lane chain.
- Codex does not choose payload types. The server renders the expected payload shape into the final-step prompt and validates the returned JSON against the YAML-backed contract.
- `validateFinalCompletionPayload(...)` must not create tasks or mutate lane state.
- `completeLane(...)` is the only executor completion method that may create downstream tasks, mark targets `NOT_NEEDED`, write a completion report, or finish the lane.
- Adding a new agent should not require changes to `ReadyToStartLaneJob`, `LaneCompletionDispatcher`, `LaneCompletionSupport`, readiness calculation, lane status semantics, or ticket resolution rules.

Minimum tests:
- Unit test the new executor validation and completion behavior.
- Add/update config tests proving `agent.yml` has valid `produces` and `input_payloads` contracts.
- Add/update `LaneStrategiesConfigurationIT` expectations so the new strategy exists and step order is valid.
- Add an integration test proving the lane is created from `agent.yml`.
- Add an integration test proving dependencies make the lane ready only after required upstream lanes are terminal.
- Add an integration test proving the final completion payload creates expected downstream tasks or marks expected target lanes `NOT_NEEDED`.
- Add at least one full-flow integration test that includes the new agent and reaches reviewer/ticket completion when applicable.

Verification commands:

```bash
mvn -pl application -am test
mvn -pl boot -am verify
mvn -f pom.xml clean install
```

### 9.4 Reviewer completion
`CompleteReviewerTaskUseCase`:
- finds reviewer lane;
- sets reviewer lane `COMPLETED`;
- sets ticket status `RESOLVED`;
- saves ticket.

Class:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteReviewerTaskUseCase.java`

## 10. Lane Status and Readiness Rules

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

## 11. Scope Validation and Safety Checks

Completion validation is internal to the supervised flow:
- lane must be `IN_PROGRESS` before `CompleteLaneCompletionUseCase` accepts completion;
- `LaneCompletionSupport` verifies every completion output matches an actual target lane by `agent` and `scope`;
- scope mismatches fail before any downstream task is created;
- API lane completion validates required generated dependency evidence through `ValidateApiLaneEvidenceUseCase`.

Classes:
- `application/src/main/java/com/sitionix/forgeai/application/usecase/CompleteLaneCompletionUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/LaneCompletionSupport.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/ValidateApiLaneEvidenceUseCase.java`

## 12. Persistence Model (Mongo)

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

## 13. API Contract and DTO Mapping

REST controllers use generated DTOs from the API-first package:
- `com.app_afesox.fgaisox.api_first.*`

Mapping layer:
- `ForgeAiApiMapper`
- `ForgeAiOperatorApiMapper`

Main REST controllers:
- `ForgeAiStartController`
- `ForgeAiTicketOperatorController`
- `ForgeAiOperatorController`

## 14. Configuration Files (YAML) and Roles

### 14.1 `boot/src/main/resources/application.yml`
Defines:
- server port/context path (`9099`, `/fgaisox`)
- Mongo connection
- config imports (`services.yaml`, `agent.yml`, `instructions.yaml`)
- scheduler delay (`forge-ai.jobs.ready-to-start.fixed-delay-ms`)
- default launcher base URL

### 14.2 `boot/src/main/resources/agent.yml`
Defines lane graph and dependencies between agents.

### 14.3 `boot/src/main/resources/services.yaml`
Defines service catalog and metadata:
- path, group, tags
- domain ownership hints
- contract references (`api` / `events`)
- generated artifact names
- deploy/db metadata

### 14.4 `infrastructure/resources/src/main/resources/instructions.yaml`
Defines instruction resolution:
- main instruction file per agent
- additional instructions per agent
- shared instruction list used for all agents

### 14.5 CI workflow YAML
- `.github/workflows/build.yml`
- `.github/workflows/cleanup-merged-branch.yml`

## 15. Scripts

Repository scripts:
- `scripts/forge-ai-start.sh` - local start helper.
- `scripts/forge-ai-open-ticket-terminal.sh` - local operator terminal opener.
- `scripts/forge-ai-watch-ticket.sh` - ticket-scoped operator stream watcher.

Codex CLI transport is handled through the app-server JSON-RPC adapter. No prompt-file launcher scripts remain in the normal runtime.

## 16. Testing Layout

- Unit tests per module in `src/test`.
- Integration tests in `boot/src/test/java/com/sitionix/forgeai/it/*` with ForgeIT style.
- Mapper tests in `api-rest/src/test/java/com/sitionix/forgeai/mapper/*`.
- Instruction loader tests in `infrastructure/resources/src/test/*`.

## 17. End-to-End Runtime Sequence (Condensed)

1. Client calls `startForge`.
2. Ticket + lane graph is created in Mongo.
3. Scheduler picks `READY_TO_START` lanes.
4. Lane executor builds `AgentExecutionInput` (instructions + tasks + scope context).
5. Codex app-server JSON-RPC session is launched headlessly.
6. Supervised runner executes `lane-strategies.yml` steps one turn at a time.
7. Final step returns validated `LANE_STEP_DONE` JSON with `evidence.completionPayload`.
8. `LaneCompletionDispatcher` validates scope/state and routes payload to downstream tasks.
9. Source lane is completed when downstream assignment is consistent.
10. New produced lanes become `READY_TO_START` when dependencies are terminal.
11. Reviewer lane becomes ready when all other lanes are terminal.
12. Reviewer completion marks ticket `RESOLVED`.

## 18. Important Current Characteristics

- Instruction loading is eager + text-inline (full markdown content in payload).
- No built-in staged/lazy instruction loading at orchestrator level yet.
- Lane execution is polling-based (`ReadyToStartLaneJob`) rather than event-stream based.
- Completion target routing is driven by `agent.yml` `produces`; readiness is still driven by `depends_on`.

## 19. Useful File Index (Quick Jump)

Core flow:
- `api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiStartController.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/StartForgeAiTaskUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/job/ReadyToStartLaneJob.java`
- `application/src/main/java/com/sitionix/forgeai/application/usecase/PrepareAgentExecutionInputUseCase.java`
- `application/src/main/java/com/sitionix/forgeai/application/laneexecution/LaneCompletionDispatcher.java`
- `application/src/main/java/com/sitionix/forgeai/application/agentexecutor/LaneCompletionSupport.java`
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
