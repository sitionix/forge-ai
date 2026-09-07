# Agent Execution Sessions Phase 1B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship Forge-owned fresh and workflow-node-reusable agent execution sessions with durable Codex continuation, fenced single-writer ownership, frozen snapshot policy, and the approved Builder/Task Execution UX.

**Architecture:** Forge Agent owns normalized session and turn records and a PostgreSQL lease token that fences every provider-derived mutation. NodeRun allocation, session claim, provider identity persistence, and terminal completion are separate short transactions; provider calls occur outside transactions. Forge Console and Nexus consume verified runtime projections without deriving membership or owning provider semantics.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway/JPA, Jackson, JUnit 5/AssertJ/Mockito, Codex app-server JSON-RPC `0.153.2`, vanilla JavaScript, Vitest/jsdom, Maven.

**Spec:** `docs/superpowers/specs/2026-09-04-agent-execution-sessions-design.md`

## Global Constraints

- `docs/agent-session-ux-and-architecture-design.md` and `docs/codex-durable-session-protocol-audit.md` remain authoritative.
- Domain/API enum values are exactly `FRESH_EACH_NODE_RUN` and `REUSE_WITHIN_WORKFLOW_NODE`; absent legacy values alone normalize to Fresh.
- Lease duration is exactly 30 seconds and renewal cadence exactly 10 seconds, using database time.
- No database transaction spans a Codex call.
- Resume never falls back to `thread/start`; all IDs are opaque and exact-pair correlated.
- Current NodeRun input envelope, routing, output-port selection, GLOBAL/PER_SCOPE projection, feedback loops, and legacy history remain unchanged.
- Phase 2 event persistence, Activity, steering, manual reset/fork, cross-node sharing, and cross-WorkflowRun memory are excluded.

---

### Task 1: Context policy domain, snapshots, persistence, and API

**Files:**
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/NodeContextMode.java`
- Create: `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/V25__add_agent_node_context_policy.sql`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/Node.java`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/RunNode.java`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/NodeRun.java`
- Modify: workflow/run-node/node-run PostgreSQL entities and mappers under `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/`
- Modify: workflow request/response and runtime DTOs plus `ForgeAgentApiMapper`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/WorkflowRunSnapshotBuilderTest.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/NodeRunFactoryTest.java`
- Test: `services/forge-agent/infrastructure/postgres/src/test/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresWorkflowRepositoryTest.java`
- Test: `services/forge-agent/infrastructure/postgres/src/test/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresWorkflowRunRepositoryTest.java`
- Test: `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/ForgeAgentApiMapperTest.java`

**Interfaces:**
- Produces: `NodeContextMode`, non-null `contextMode()` on all three node layers, nullable `Integer contextTrackingVersion()` on `NodeRun`.

- [ ] **Step 1: Write failing policy and snapshot tests**

Add literal assertions that absent request JSON maps to `FRESH_EACH_NODE_RUN`, an unknown value returns validation failure, explicit reuse round-trips, snapshot copies reuse, a later Workflow edit cannot change the stored `RunNode`, and `NodeRunFactory` writes tracking version `1`.

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `mvn -pl services/forge-agent/application,services/forge-agent/infrastructure/postgres,services/forge-agent/api-rest -am -Dtest=WorkflowRunSnapshotBuilderTest,NodeRunFactoryTest,PostgresWorkflowRepositoryTest,PostgresWorkflowRunRepositoryTest,ForgeAgentApiMapperTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: compilation/assertion failures because context fields and enum do not exist.

- [ ] **Step 3: Implement enum propagation and migration**

Create:

```java
public enum NodeContextMode {
    FRESH_EACH_NODE_RUN,
    REUSE_WITHIN_WORKFLOW_NODE;

    public static NodeContextMode legacyDefault(NodeContextMode value) {
        return value == null ? FRESH_EACH_NODE_RUN : value;
    }
}
```

Migration V25 must backfill all three `context_mode VARCHAR(48)` columns to Fresh, set NOT NULL and exact CHECK constraints, remove temporary defaults, and add nullable `node_runs.context_tracking_version` without backfilling it. Constructors/mappers normalize only null legacy values; Jackson handles unknown non-null enum values as typed bad requests.

- [ ] **Step 4: Run focused and migration tests and verify GREEN**

Run the Step 2 command plus `mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentRuntimeMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test`.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent
git commit -m "feat: snapshot agent context policy"
```

### Task 2: Provider capability validation before WorkflowRun persistence

**Files:**
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/AgentExecutionProviderCapability.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/AgentExecutionProviderCapabilities.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/WorkflowRunSnapshotBuilder.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/WorkflowRunUseCases.java`
- Create/modify: Codex capability adapter/configuration under `services/forge-agent/infrastructure/codex` and `services/forge-agent/boot`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/usecase/WorkflowRunUseCasesTest.java`

**Interfaces:**
- Produces: `boolean supports(String providerId, AgentExecutionProviderCapability capability)` and capability `DURABLE_CONTEXT`.

- [ ] **Step 1: Write failing atomic validation tests**

Test Fresh on an unsupported provider succeeds, reuse on supported Codex succeeds, and reuse on unsupported provider throws `AGENT_CONTEXT_MODE_UNSUPPORTED` before `workflowRunRepository.save`, graph persistence, NodeRun creation, or executor interaction.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/application -am -Dtest=WorkflowRunUseCasesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: unsupported reuse is not rejected.

- [ ] **Step 3: Implement the application capability port**

Validate the fully built proposed snapshot before its first persistence call. Codex returns true only for `DURABLE_CONTEXT`; unknown providers return false. Use the existing typed validation exception with message `<agent> cannot keep context during an execution because its provider does not support durable context.`

- [ ] **Step 4: Verify GREEN and application context wiring**

Run: `mvn -pl services/forge-agent/application -am -Dtest=WorkflowRunUseCasesTest -Dsurefire.failIfNoSpecifiedTests=false test && mvn -pl services/forge-agent/boot -am test`

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent
git commit -m "feat: validate durable context capability"
```

### Task 3: Session and turn schema, models, and deterministic allocation

**Files:**
- Create: domain models/enums `AgentExecutionSession`, `AgentExecutionTurn`, `AgentExecutionSessionStatus`, `AgentExecutionTurnStatus`, `AgentExecutionTerminalOutcome`.
- Create: domain port `AgentExecutionSessionRepository`.
- Create: `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/V26__create_agent_execution_sessions.sql`
- Create: session/turn JPA entities, Spring Data repositories, mapper, and `PostgresAgentExecutionSessionRepository`.
- Modify: `NodeRunFactory` and the coordinator persistence boundary that saves newly activated NodeRuns.
- Test: `PostgresAgentExecutionSessionRepositoryTest.java`, `NodeRunFactoryTest.java`, `ForgeAgentRuntimeMigrationIT.java`.

**Interfaces:**
- Produces: `allocate(NodeRun, RunNode, providerId)` returning verified session/turn linkage; lookup by NodeRun and deterministic scope.

- [ ] **Step 1: Write failing identity and constraint tests**

Cover two Fresh NodeRuns yielding different sessions; same run/node GLOBAL reuse yielding one session and sequences 1/2; PER_SCOPE repositories yielding different sessions; different source node and different WorkflowRun yielding different sessions; unique NodeRun linkage; fresh second turn and wrong repository nullability rejected by PostgreSQL.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/infrastructure/postgres,services/forge-agent/application -am -Dtest=PostgresAgentExecutionSessionRepositoryTest,NodeRunFactoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement exact Phase 1A relational contract**

V26 creates the exact fields/checks/indexes in Phase 1A §8, including partial unique GLOBAL/PER_SCOPE indexes, provider identity uniqueness, turn sequence/NodeRun uniqueness, active-writer partial uniqueness, composite RunNode FK, and deferred fresh-turn constraint trigger. Allocate NodeRun + session + queued turn in one transaction and retry only deterministic first-session unique races.

- [ ] **Step 4: Verify GREEN including database constraints**

Run the Step 2 command and the migration IT.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent
git commit -m "feat: persist agent sessions and turns"
```

### Task 4: Atomic lease claim, renewal, release, and crash takeover

**Files:**
- Create: `AgentSessionLease`, `AgentSessionExecutionClaim`, and `AgentSessionLeaseService` in application runtime.
- Extend: `AgentExecutionSessionRepository` with acquire/renew/guarded persistence/complete/takeover operations.
- Modify: PostgreSQL adapter with row locks and database-time CAS queries.
- Test: application lease service and PostgreSQL concurrency tests.

**Interfaces:**
- Produces: `claim(nodeRunId, ownerId)`, `renew(sessionId, ownerId, token)`, guarded `persistConversation`, `persistTurn`, `completeSuccess`, `completeFailure`, and `recoverExpired`.

- [ ] **Step 1: Write failing fencing tests**

Test token 0→1 acquire, same-owner renewal without increment, unexpired competing owner rejection, two concurrent claims allowing only one provider claim, expiry takeover token N→N+1, and a late N completion updating zero rows with `STALE_AGENT_SESSION_LEASE` while every session/turn/NodeRun field remains at N+1 state.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/application,services/forge-agent/infrastructure/postgres -am -Dtest=AgentSessionLeaseServiceTest,PostgresAgentExecutionSessionRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement short fenced transactions**

Acquire locks the session, selects only lowest queued sequence, assigns a process owner, increments token exactly once, sets `database_now()+30 seconds`, turn STARTING, session CREATING/RESUMING, active NodeRun, and NodeRun RUNNING atomically. Renewal uses the exact owner/token/unexpired predicate and 30-second extension. Release atomically records turn/NodeRun outcome and clears ownership. Takeover never starts provider work; it reconciles already-terminal evidence or fails uncertain work according to Fresh/continued rules.

- [ ] **Step 4: Verify GREEN under real concurrent transactions**

Run the Step 2 command repeatedly with `-Dsurefire.rerunFailingTestsCount=2`.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent
git commit -m "feat: fence agent session ownership"
```

### Task 5: Codex start/resume primitives and ordered identity persistence

**Files:**
- Modify: `CodexClient.java`, `CodexAppServerClient.java`, `CodexSessionProtocol.java`, `CodexTurnRequest.java`.
- Create: provider session handle/callback interfaces in application runtime.
- Test: `CodexAppServerClientTest.java`, `CodexSessionProtocolTest.java`.

**Interfaces:**
- Produces: explicit `startConversation(ephemeral, request)`, `resumeConversation(threadId)`, `startTurn(threadId, request)`, and an initialized per-invocation process handle.

- [ ] **Step 1: Write failing protocol-order tests**

Record actual JSON-RPC requests and callbacks. Assert first continued invocation sends `thread/start` with `ephemeral:false`; second sends exactly `thread/resume {threadId, excludeTurns:true}`; resume mismatch/error performs no `thread/start`; conversation persistence callback completes before transport observes `turn/start`; provider turn persistence completes before notifications are accepted.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/infrastructure/codex -am -Dtest=CodexAppServerClientTest,CodexSessionProtocolTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement explicit process-scoped session operations**

Retain fresh behavior but route it through the identity callbacks. A resumed invocation starts and initializes a fresh app-server process, validates exact thread identity, and never falls back. Reject blank IDs. Stop immediately when guarded persistence fails.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent/infrastructure/codex services/forge-agent/application
git commit -m "feat: start and resume Codex sessions"
```

### Task 6: Evidence-based Codex completion state machine

**Files:**
- Modify: `CodexTurnStateTracker.java`, `CodexAppServerClient.java`.
- Create: transcript fixtures for live `0.153.2` notification sequences under Codex test resources.
- Test: `CodexTurnStateTrackerTest.java`, `CodexAppServerClientTest.java`.

**Interfaces:**
- Produces: tracker bound to exact thread/turn with terminal success/failure future and final agent output.

- [ ] **Step 1: Write failing Phase 0 transcript tests**

Replay: `turn/started`, intermediate completed agent message, final completed agent message, token usage, and thread idle without `turn/completed`; require final output only at idle. Add compatible `turn/completed`, wrong IDs, missing IDs, idle-before-start, idle-without-output, provider failure, failed item, and contradictory terminal tests.

- [ ] **Step 2: Verify RED against the current mandatory-turn/completed tracker**

Run: `mvn -pl services/forge-agent/infrastructure/codex -am -Dtest=CodexTurnStateTrackerTest,CodexAppServerClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement the exact state machine**

Bind tracker after persisted `turn/start`; keep the latest completed target `agentMessage` as candidate; complete successfully only after target success `turn/completed` or post-start target-thread idle under the single-writer handle. Validate optional turn completion against exact pair. Fail all malformed or contradictory lifecycle transitions explicitly; keep policy timeout only for safety and interrupt the persisted exact pair.

- [ ] **Step 4: Verify GREEN on both transcript paths**

Run the Step 2 command.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent/infrastructure/codex
git commit -m "fix: recognize terminal Codex turns safely"
```

### Task 7: Integrate session ownership with NodeRun worker lifecycle

**Files:**
- Modify: `NodeExecutionClaim.java`, `NodeRunLifecycle.java`, `NodeRunWorker.java`, `NodeRunCompletionPersistence.java`, cancellation/reconciliation paths.
- Create: `AgentSessionHeartbeat.java` and provider orchestration service.
- Test: `NodeRunLifecycleTest.java`, `NodeRunWorkerTest.java`, `WorkflowExecutionCoordinatorTest.java`.

**Interfaces:**
- Consumes: fenced claim and explicit provider operations from Tasks 4–6.
- Produces: provider execution outside DB transactions, guarded terminal writes, and pending behavior for busy sessions.

- [ ] **Step 1: Write failing scheduling and failure tests**

Assert busy continued NodeRun remains PENDING and executor is untouched; ownership claim carries the unchanged canonical input envelope; start/resume/mismatch/persistence failures map to approved codes; resume failure text includes both required sentences; heartbeat loss rejects the local result; success preserves selected output-port routing and completion processing.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/application -am -Dtest=NodeRunLifecycleTest,NodeRunWorkerTest,WorkflowExecutionCoordinatorTest -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement worker orchestration and heartbeat**

Generate one stable random owner ID per worker bean. Claim through the session service before submission, schedule renewal every 10 seconds, invoke the provider outside transactions, cancel heartbeat on terminal exit, and apply every result/failure through fenced completion. Keep normal workflow reconciliation after the guarded terminal write.

- [ ] **Step 4: Verify GREEN and unchanged routing tests**

Run the Step 2 command plus `mvn -pl services/forge-agent/application -am test`.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent/application services/forge-agent/boot
git commit -m "feat: execute node runs through owned sessions"
```

### Task 8: Runtime API and thin Nexus mapping

**Files:**
- Create: Forge Agent session/turn runtime DTO records.
- Modify: Forge Agent `NodeRunResponse`, `WorkflowRunGraphResponse`, and `ForgeAgentApiMapper`.
- Modify: corresponding Nexus client, domain, API DTOs, and mappers without adding business logic.
- Test: Forge Agent mapper/controller tests, Nexus client mapper tests, `AgentProxyApiMapperTest`, `NexusAgentProxyIT`.

**Interfaces:**
- Produces: read-only context policy/tracking/session/turn projection containing only verified metadata.

- [ ] **Step 1: Write failing exact-shape mapping tests**

Use literal DTOs for modern continued, modern fresh, partial linkage, and legacy null tracking. Assert Nexus preserves every field unchanged and does not derive relationship labels.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/api-rest,services/forge-nexus/clients/agent-client,services/forge-nexus/api-rest,services/forge-nexus/boot -am -Dtest=ForgeAgentApiMapperTest,ForgeAgentControllerTest,ForgeAgentClientMapperTest,AgentProxyApiMapperTest,NexusAgentProxyIT -Dsurefire.failIfNoSpecifiedTests=false test`

- [ ] **Step 3: Implement additive typed mappings**

Expose Forge session/turn IDs, modes/statuses/sequence/failures, provider/model/version/repository metadata, and timestamps only when stored. Workflow node DTOs contain context policy only and never runtime IDs.

- [ ] **Step 4: Verify GREEN**

Run the Step 2 command.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent/api-rest services/forge-nexus
git commit -m "feat: expose agent context runtime state"
```

### Task 9: Workflow Builder Context control and graph badge

**Files:**
- Modify: `services/forge-console/src/operator/workflow-builder.js` and associated markup/styles.
- Test: existing/new Workflow Builder Vitest file under `services/forge-console/tests/`.

**Interfaces:**
- Consumes: node `contextMode` API enum.
- Produces: native radio draft/save behavior and opt-in graph badge with measured card height.

- [ ] **Step 1: Write failing jsdom behavior tests**

Test old/new defaults, explicit serialization, unknown-value rejection, exact copy, native fieldset/legend/radio/label/described-by roles, whole-row selection, disabled save state, focus-visible class/style contract, PER_SCOPE note, arrow/Space native behavior, continued badge/tooltip, Fresh omission, and changed `nodeHeight`/edge endpoints.

- [ ] **Step 2: Verify RED**

Run: `npm test -- --run tests/workflow-builder.test.ts` from `services/forge-console`.

- [ ] **Step 3: Implement the approved Builder UX**

Define exact constants and strict normalization; set Fresh on new drafts; include mode in clone/editor/save payload; render the full-width native radio fieldset below Execution; render `↻ Context` only for reuse and include its row in computed bounds.

- [ ] **Step 4: Verify GREEN plus typecheck/build**

Run: `npm test -- --run tests/workflow-builder.test.ts && npm run typecheck && npm run build`.

- [ ] **Step 5: Commit**

```bash
git add services/forge-console
git commit -m "feat: configure node context in builder"
```

### Task 10: Task Execution context, history, PER_SCOPE, and legacy UX

**Files:**
- Modify: `services/forge-console/src/operator/task-execution-view.js`
- Modify: `services/forge-console/src/operator/task-execution-view.d.ts` and associated styles/markup.
- Test: `services/forge-console/tests/task-execution-view.test.ts`.

**Interfaces:**
- Consumes: verified runtime graph, NodeRun context tracking, and session/turn projection.
- Produces: Context card/history/technical details while retaining existing Invocation selection.

- [ ] **Step 1: Write failing UI projection tests**

Cover runtime badge from RunNode only; Fresh/New/Continued labels and lifecycle; connected same-session sequence versus independent Fresh chips; history chip changing the existing selected invocation; >5 compact/expand; repository-separated sessions and histories; collapsed technical fields; partial/legacy Unavailable; resume failure copy; missing repository label; and unchanged graph topology/card selection.

- [ ] **Step 2: Verify RED**

Run: `npm test -- --run tests/task-execution-view.test.ts` from `services/forge-console`.

- [ ] **Step 3: Implement pure context projection helpers and rendering**

Derive relationship only from `contextTrackingVersion`, exact turn linkage, mode, and sequence. Filter histories by Forge session ID and visual unit repository. Render no badge/card for legacy graph fallback, no connectors for Fresh, and available-only technical rows in a native collapsed `<details>`.

- [ ] **Step 4: Verify GREEN plus complete Console suite**

Run: `npm test && npm run typecheck && npm run build`.

- [ ] **Step 5: Commit**

```bash
git add services/forge-console
git commit -m "feat: show execution context history"
```

### Task 11: Session integration, concurrency, and deterministic E2E

**Files:**
- Create: `ForgeAgentSessionExecutionIT.java` and deterministic session-capable provider under boot tests.
- Extend: DB fixtures/contracts and workflow request/response fixtures.
- Modify: relevant boot test configuration.

**Interfaces:**
- Exercises: production snapshot, allocation, claim, routing loop, fenced completion, and API evidence.

- [ ] **Step 1: Write failing full-flow integration tests**

Build Implementer→Reviewer→Implementer routing. Provider double stores a unique fact by provider conversation and requires resume to recall it. Assert same Forge session/conversation, different Forge turns/provider turn IDs, first `start(false)`, second exact resume, preserved explicit second input envelope, no fallback on resume failure, and two concurrent claims cannot both reach provider work. Repeat for one PER_SCOPE node across repos A/B and prove two isolated resumptions.

- [ ] **Step 2: Verify RED**

Run: `mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentSessionExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test`.

- [ ] **Step 3: Complete integration wiring and fixtures**

Wire deterministic capabilities/provider and expose stored session evidence through production repositories/API. Fix only integration defects revealed by the RED test; do not add provider-history hydration.

- [ ] **Step 4: Verify GREEN with migration and existing runtime acceptance**

Run: `mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentSessionExecutionIT,ForgeAgentRuntimeMigrationIT,ForgeAgentScopedExecutionIT,ForgeAgentPortAwareExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test`.

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent/boot
git commit -m "test: prove durable session execution flow"
```

### Task 12: Real Codex durable capability E2E and final verification

**Files:**
- Create: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexDurableSessionE2ETest.java`
- Modify: test documentation/config only as required to gate explicit live execution.

**Interfaces:**
- Produces: real two-process `0.153.2` evidence with retained unique fact and stored identity assertions.

- [ ] **Step 1: Write the opt-in real E2E around production adapter**

Require an explicit live-test flag, installed `codex-cli 0.153.2`, writable persistent Codex home, and credentials. Start a reusable session, provide a random unique fact, terminate process one, resume from a fresh process, ask for the fact, and assert same Forge session/provider conversation plus distinct Forge/provider turns. The enabled test fails rather than skips on protocol mismatch.

- [ ] **Step 2: Run deterministic suites before live external work**

Run: `mvn test` and, in `services/forge-console`, `npm test && npm run typecheck && npm run build`.

- [ ] **Step 3: Run the explicitly enabled real Codex E2E**

Run: `mvn -pl services/forge-agent/infrastructure/codex -am -Dforge.codex.live-session-e2e=true -Dtest=CodexDurableSessionE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`.

Expected: Codex version `0.153.2`; recalled unique fact; matching conversation ID; different turn IDs; exit 0.

- [ ] **Step 4: Perform final evidence review**

Run `git diff --check origin/main...HEAD`, inspect `git diff --stat origin/main...HEAD`, and review the full diff against every numbered section in both source documents. Confirm no runtime IDs in workflow DTOs, no unguarded provider-result write, no long transaction, no fallback start, and no Phase 2 event/UI additions.

- [ ] **Step 5: Request code review, resolve findings, and rerun verification**

Use `superpowers:requesting-code-review`; fix all Critical/Important findings through TDD. Rerun Maven, Console, live E2E, and diff checks after the last change.

- [ ] **Step 6: Commit final E2E or review fixes**

```bash
git add services scripts docs
git commit -m "test: verify real Codex session continuity"
```
