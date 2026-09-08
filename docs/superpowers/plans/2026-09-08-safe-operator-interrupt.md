# Phase 5A Safe Operator Interrupt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an operator `Stop run` command that safely and coherently cancels a WorkflowRun through the existing Forge/Codex lifecycle.

**Architecture:** Forge Agent serializes cancellation on the WorkflowRun row, secures the existing in-memory Codex cancellation objects before changing persistence, commits established tracked-session cancellation plus WorkflowRun cancellation, then invokes secured actions after commit. Nexus remains a transparent typed proxy, and Console renders backend truth after an inline-confirmed single-flight request.

**Tech Stack:** Java 21, Spring transactions and MVC HTTP interfaces, PostgreSQL/JDBC repositories, JUnit/AssertJ/Mockito, vanilla JavaScript, Vitest/jsdom.

**Spec:** `docs/superpowers/specs/2026-09-08-safe-operator-interrupt-design.md`

## Global Constraints

- Do not redesign agent start/resume, context allocation, lease/heartbeat, timeout, events, routing, completion, or Task Execution Activity.
- Reuse `CodexAgentExecutor.activeExecutions`, `CodexExecutionIdentityCallbacks.executionStarted(...)`, `interruptWithoutWaiting()`, and `AgentExecutionSessionRepository.cancel(...)`.
- A missing handle for a RUNNING tracked execution fails with `AGENT_EXECUTION_INTERRUPT_UNAVAILABLE` before any lifecycle mutation.
- Cancellation actions execute once and only after authoritative database commit.
- No provider identity appears in public APIs and no new event type or control ledger is added.

---

### Task 1: Provider-neutral cancellation reservation

**Files:**
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutor.java`
- Modify: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexAgentExecutor.java`
- Test: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexAgentExecutorTest.java`
- Test: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexAppServerClientTest.java`

**Interfaces:**
- Produces: `Optional<Runnable> secureCancellation(UUID nodeRunId)` on `AgentExecutor`.
- Preserves: `cancel(NodeExecutionClaim)` delegates to the same idempotent `ExecutionCancellation` used by operator cancellation.

- [ ] Add tests proving an executing tracked claim exposes one secured action, duplicate invocation runs the registered callback once, and missing/completed executions return empty.
- [ ] Run the focused Codex tests and observe failure because `secureCancellation` does not exist.
- [ ] Add the default empty API and implement it in `CodexAgentExecutor` by returning `ExecutionCancellation::cancel` from the existing map.
- [ ] Run focused Codex tests; retain the existing exact-turn and pre-turn transport tests as proof that the registered action still selects the correct path.
- [ ] Commit the green provider-boundary change.

### Task 2: Transactional WorkflowRun cancellation orchestration

**Files:**
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/CancelWorkflowRunUseCase.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/WorkflowExecutionCoordinator.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/usecase/CancelWorkflowRunUseCaseTest.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/NodeRunLifecycleTest.java`
- Test: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java`

**Interfaces:**
- Consumes: `AgentExecutor.secureCancellation(UUID)` and existing repositories.
- Produces: `void execute(UUID workflowRunId)` and reusable authoritative `cancelActiveNodeRuns(WorkflowRun)` persistence.

- [ ] Add application tests for queued cancellation, active tracked cancellation, all-node cancellation, duplicate cancellation, terminal conflict, missing-handle fail-closed behavior, and cancellation actions running after transaction completion.
- [ ] Run the focused application tests and observe the missing use case/API failures.
- [ ] Implement transaction-template orchestration: lock run, return idempotently for CANCELLED, reject SUCCEEDED/FAILED, secure every RUNNING tracked handle, cancel all pending/running nodes, save WorkflowRun CANCELLED with `finishedAt`, then invoke handles after transaction completion.
- [ ] Add/retain lifecycle tests proving late success and late failure are absorbed after cancellation and no completion processing/routing occurs.
- [ ] Extend PostgreSQL integration coverage for tracked turn/session/capture/lease truth and rollback on missing handle.
- [ ] Run application and targeted boot integration tests, then commit.

### Task 3: Forge Agent HTTP command

**Files:**
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/ForgeAgentController.java`
- Test: `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/ForgeAgentControllerTest.java`

**Interfaces:**
- Consumes: `CancelWorkflowRunUseCase.execute(UUID)`.
- Produces: `POST /api/v1/workflow-runs/{runId}/cancel -> 204` with existing typed exception mapping.

- [ ] Add controller tests for the exact route and no-content response.
- [ ] Run the focused controller test and observe the missing endpoint failure.
- [ ] Add the controller dependency and `@PostMapping` method without a body DTO.
- [ ] Run Forge application/API/Codex tests and commit.

### Task 4: Transparent Nexus proxy

**Files:**
- Create: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/usecase/CancelAgentWorkflowRun.java`
- Create: `services/forge-nexus/application/src/main/java/com/sitionix/forgeai/application/usecase/agentproxy/CancelAgentWorkflowRunUseCase.java`
- Modify: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/port/ForgeAgentClient.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentHttpClient.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientAdapter.java`
- Modify: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureAgentsController.java`
- Test: corresponding Nexus application, client-adapter, controller, and boot proxy tests.

**Interfaces:**
- Produces: `ForgeAgentClient.cancelWorkflowRun(UUID)`, `CancelAgentWorkflowRun.execute(UUID)`, and `POST /api/v1/infrastructure/agents/workflow-runs/{runId}/cancel -> 204`.

- [ ] Add tests proving exact public-to-upstream route/ID mapping and unchanged typed upstream conflict responses.
- [ ] Run focused Nexus tests and observe missing contracts.
- [ ] Add the straight-through domain use case, adapter, HTTP exchange, and controller method with no business branch.
- [ ] Run Nexus unit/integration tests and commit.

### Task 5: Task Execution Stop run control

**Files:**
- Modify: `services/forge-console/src/operator/agent-projects-api.js`
- Modify: `services/forge-console/src/operator/agent-projects-api.d.ts`
- Modify: `services/forge-console/src/operator/task-execution-view.js`
- Modify: `services/forge-console/src/operator/task-execution-view.d.ts`
- Modify: `services/forge-console/src/operator/agent-projects-page.js`
- Modify: `services/forge-console/src/operator/operator-ui.css`
- Test: `services/forge-console/tests/task-execution-view.test.ts`
- Test: `services/forge-console/tests/agent-projects-page.test.ts`

**Interfaces:**
- Produces: `cancelWorkflowRun(runId)` API helper and Task Execution callbacks/state for request, confirmation, refresh, and local errors.

- [ ] Add Vitest cases for status visibility, inline confirmation, no request on first click, one request during pending state, backend refresh after success, pinned Activity retention, and intact UI plus local error after failure.
- [ ] Run focused Console tests and observe the missing control/API failures.
- [ ] Implement the no-body POST helper and render the inline confirmation in run controls using existing danger styles.
- [ ] Wire single-flight state in `agent-projects-page.js`; on success refresh run and contexts without selection mutation, and on failure keep polling/content while refreshing backend truth.
- [ ] Run focused and full Console tests, typecheck, and build; commit.

### Task 6: Full verification and delivery

**Files:**
- Review all modified files and generated test reports; no production file is added outside the scoped modules.

**Interfaces:**
- Produces: pushed exact HEAD, GitHub PR, CI result, and real-acceptance evidence/status.

- [ ] Run `mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify`.
- [ ] Run `mvn -B -ntp -pl services/forge-nexus/boot -am verify`.
- [ ] Run `npm test -- --run`, `npm run typecheck`, and `npm run build` in `services/forge-console`.
- [ ] Run `git diff --check`, inspect the exact diff, and apply the verification/review skills.
- [ ] Run gated real Codex acceptance if the local stack and credentials are available; record an explicit blocker otherwise.
- [ ] Push `feature/SITIONIX-115`, create one PR targeting `main`, and wait for exact-HEAD GitHub CI to become green or report its truthful state.
