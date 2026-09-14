# Provider-Aware Restart Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reconcile expired tracked Forge executions against the exact persisted provider turn, persist `TERMINAL`/`ACTIVE`/`UNKNOWN` truth under a crash-safe recovery fence, and never start a duplicate provider turn.

**Architecture:** Postgres atomically claims one expired turn and later commits an application-selected outcome under a distinct recovery lease. `AgentExecutionRecoveryService` performs Forge-terminal short-circuiting, workspace resolution, inspector selection, and provider-neutral classification outside database transactions. The Codex inspector starts a fresh app-server process and pages `thread/turns/list` for the exact persisted turn; unsupported or ambiguous evidence fails closed.

**Tech Stack:** Java 21, Spring Boot, JDBC/PostgreSQL, Flyway, Jackson JSON-RPC, JUnit 5, AssertJ, Mockito, Maven, Codex app-server `0.154.0`.

**Spec:** `docs/superpowers/specs/2026-09-14-provider-aware-restart-reconciliation-design.md`

## Global Constraints

- Start from `main` commit `c1dae2cb`, the merge of Phase 5A / PR #123.
- Recovery processes at most one expired candidate per `NodeRunWorker.poll()`.
- No provider call occurs in a database transaction.
- Recovery never invokes `thread/start`, `thread/resume`, or `turn/start`.
- Exact persisted `providerConversationId + providerTurnId` correlation is mandatory.
- Missing identity, unsupported provider/version, timeout, transport error, malformed response, or ambiguity maps to `UNKNOWN`.
- No output/routing reconstruction, normal execution redesign, Console/Nexus control, or Phase 5C behavior.
- Existing Fresh/Continued, GLOBAL/PER_SCOPE, heartbeat, timeout, Stop, event ledger, activity, output parsing, routing, and workflow completion behavior stays unchanged.
- Every behavior change follows red-green-refactor and each listed test must be observed failing for the intended reason before production code is added.

## File map

- Domain recovery truth and fencing records live under `domain/model`; the repository port owns only claim and fenced commit operations.
- Application inspection contracts and `AgentExecutionRecoveryService` live under `application/runtime`; no Codex JSON crosses this boundary.
- `CodexRecoveryInspector` and `CodexRecoveryProtocol` live under `infrastructure/codex`; the protocol owns JSON-RPC shapes and exact response parsing.
- `PostgresAgentExecutionSessionRepository` owns bounded selection, lock order, DB-time recovery leases, and atomic reconciliation writes.
- `V29__add_agent_execution_recovery.sql` adds nullable turn recovery evidence and recovery fencing columns without backfill.
- Existing boot integration suites provide real PostgreSQL lifecycle/fencing coverage and gated live Codex acceptance.

---

### Task 1: Audit and contract-test exact Codex recovery inspection

**Files:**
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutionRecoveryInspection.java`
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutionRecoveryInspector.java`
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/ProviderTurnRecoveryResult.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/ProviderTurnRecoveryState.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/ProviderTurnRecoveryTerminalOutcome.java`
- Create: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexRecoveryProtocol.java`
- Create: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexRecoveryInspector.java`
- Create: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexRecoveryProtocolTest.java`
- Create: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexRecoveryInspectorTest.java`
- Create: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexRecoveryE2ETest.java`
- Modify: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexProtocol.java`
- Modify: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexAppServerClient.java`
- Modify: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexDurableSessionProtocolTest.java`
- Modify: `docs/codex-durable-session-protocol-audit.md`

**Interfaces:**
- Produces: `AgentExecutionRecoveryInspector.supports(String providerId, String providerVersion)` and `inspect(AgentExecutionRecoveryInspection inspection)`.
- Produces: `AgentExecutionRecoveryInspection(String providerId, String providerVersion, String providerConversationId, String providerTurnId, ExecutionWorkspace executionWorkspace)`.
- Produces: `ProviderTurnRecoveryResult(ProviderTurnRecoveryState state, ProviderTurnRecoveryTerminalOutcome terminalOutcome, String diagnostic)` with static factories `terminal`, `active`, and `unknown`.
- Produces: `CodexRecoveryProtocol.inspectTurn(CodexJsonRpcTransport transport, String threadId, String turnId, Duration timeout)`.

- [ ] **Step 1: Write failing protocol tests for exact turn classification**

Use literal responses for `completed`, `failed`, `interrupted`, and `inProgress`:

```json
{"data":[{"id":"turn-target","items":[],"status":"completed"}],"nextCursor":null}
```

Assert `completed -> TERMINAL/SUCCEEDED`, `failed -> TERMINAL/FAILED`, `interrupted -> TERMINAL/CANCELLED`, and `inProgress -> ACTIVE`. Every request must be `thread/turns/list` with `{threadId, cursor?, limit:100, sortDirection:"desc", itemsView:"notLoaded"}` and no `includeTurns` field.

- [ ] **Step 2: Write failing pagination and ambiguity tests**

Cover a target on page two, unknown turn after `nextCursor:null`, blank/mismatched IDs, repeated cursors, malformed `data`, unknown status, unknown-thread JSON-RPC error, and timeout. Each non-exact result must be `UNKNOWN`.

- [ ] **Step 3: Run focused tests and confirm RED**

```bash
mvn -pl services/forge-agent/infrastructure/codex -am -Dtest=CodexRecoveryProtocolTest,CodexRecoveryInspectorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation/test failure because the recovery contracts do not exist.

- [ ] **Step 4: Implement the minimal contracts and parser**

```java
public enum ProviderTurnRecoveryState { TERMINAL, ACTIVE, UNKNOWN }
public enum ProviderTurnRecoveryTerminalOutcome { SUCCEEDED, FAILED, CANCELLED, UNKNOWN }
```

Add protocol constants `THREAD_READ`, `THREAD_TURNS_LIST`, and `THREAD_ITEMS_LIST`; recovery calls only `THREAD_TURNS_LIST` unless live evidence proves another read necessary. Bound pagination to 100 pages and reject cursor loops.

- [ ] **Step 5: Implement the fresh-process inspector and version gate**

`CodexRecoveryInspector` starts a workspace transport, initializes it, requires persisted and live version to equal the audited recovery version, calls the protocol, and always closes the process. `supports` accepts only provider `codex` and the audited version. No Jackson type crosses the application interface.

- [ ] **Step 6: Prove normal durable execution on installed `0.154.0` before moving the shared pin**

```bash
mvn -pl services/forge-agent/infrastructure/codex -am -Dforge.codex.live-session-e2e=true -Dtest=CodexDurableSessionE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
```

If green, replace the old `0.153.2` durable pin and assertions with `0.154.0`. If it fails, keep normal durable execution pinned and make recovery support return false, producing `UNKNOWN`; document the evidence without weakening the gate.

- [ ] **Step 7: Run the fresh-process recovery gate before database work**

Create a durable terminal turn, close its original client, inspect the exact IDs through a newly started inspector process, and assert `TERMINAL/SUCCEEDED`. Record the recovery-process methods and assert none are `thread/start`, `thread/resume`, or `turn/start`.

```bash
mvn -pl services/forge-agent/infrastructure/codex -am -Dforge.codex.live-recovery-e2e=true -Dtest=CodexRecoveryE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 8: Verify GREEN and document the exact protocol**

Re-run focused tests. Document installed version, request/response shapes, fresh-process terminal/active/unknown behavior, and whether exact cross-process interrupt is proven.

- [ ] **Step 9: Commit**

```bash
git add services/forge-agent/application services/forge-agent/domain services/forge-agent/infrastructure/codex docs/codex-durable-session-protocol-audit.md docs/superpowers/specs/2026-09-14-provider-aware-restart-reconciliation-design.md
git commit -m "feat(agent): inspect persisted provider turns"
```

### Task 2: Add nullable recovery evidence and fencing persistence

**Files:**
- Create: `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/V29__add_agent_execution_recovery.sql`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/AgentExecutionTurn.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresAgentExecutionSessionRepository.java`
- Modify: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentRuntimeMigrationIT.java`

**Interfaces:**
- Extend `AgentExecutionTurn` with `providerRecoveryState`, `providerRecoveryTerminalOutcome`, and `providerRecoveryCheckedAt` before lifecycle timestamps.
- Keep recovery owner/token/expiry internal to repository claims; normal read DTOs expose only provider recovery evidence.

- [ ] **Step 1: Write a failing V29 migration test**

Migrate a schema through V28, insert a historical tracked turn, migrate to latest, and assert evidence/owner/expiry are `NULL` and `recovery_lease_token=0`. Assert invalid classification/outcome and negative token violate constraints.

- [ ] **Step 2: Run migration test and confirm RED**

```bash
mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentRuntimeMigrationIT -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Add V29 and mapper fields**

Add six columns on `agent_execution_turns`: three nullable evidence fields, nullable owner/expiry, and `recovery_lease_token BIGINT NOT NULL DEFAULT 0`; add allowed-value, owner/expiry null-pair, and non-negative-token checks. Update all turn select aliases and `turn(ResultSet)` without changing allocation.

- [ ] **Step 4: Verify GREEN and commit**

```bash
mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentRuntimeMigrationIT,ForgeAgentPortAwareExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test
git add services/forge-agent/domain services/forge-agent/infrastructure/postgres services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentRuntimeMigrationIT.java
git commit -m "feat(agent): persist provider recovery evidence"
```

### Task 3: Replace repository-owned decisions with claim and fenced commit

**Files:**
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/AgentExecutionRecoveryClaim.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/AgentExecutionRecoveryDisposition.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/AgentExecutionRecoveryReconciliation.java`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/AgentExecutionSessionRepository.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresAgentExecutionSessionRepository.java`
- Modify: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java`

**Interfaces:**
- Produces: `Optional<AgentExecutionRecoveryClaim> claimExpiredRecovery(String ownerId)`.
- Produces: `boolean reconcileRecovery(AgentExecutionRecoveryClaim claim, AgentExecutionRecoveryReconciliation reconciliation)`.
- Removes: `int recoverExpired(String ownerId)`.
- Claim fields include session/turn/NodeRun/WorkflowRun IDs, repository ID, persisted provider identity, context mode, NodeRun status/failure, and recovery owner/token/expiry.
- Dispositions: `FORGE_TERMINAL`, `PROVIDER_TERMINAL_RESULT_LOST`, `PROVIDER_ACTIVE_FAIL_CLOSED`, `PROVIDER_UNKNOWN_FAIL_CLOSED`, plus `PROVIDER_INTERRUPTED` only if Task 1 proves it.

- [ ] **Step 1: Write failing bounded claim tests**

Create two expired executions. Assert one deterministic claim, DB-time 30-second recovery lease, incremented recovery token, unchanged expired normal lease, and exact persisted identities. A second owner cannot claim the first before recovery expiry but can claim the other candidate.

- [ ] **Step 2: Write failing race and crash tests**

Race two claims for one session and assert one owner. Expire its recovery lease, reclaim with a second owner, and assert the old token cannot reconcile while the new token commits exactly once.

- [ ] **Step 3: Confirm RED**

```bash
mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentPortAwareExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 4: Implement bounded claim with established lock order**

Select one candidate with `LIMIT 1`; lock WorkflowRun, NodeRun, session, and exact active turn. Claim only with expired normal lease and absent/expired recovery lease. Use `CURRENT_TIMESTAMP` and return committed token/expiry.

- [ ] **Step 5: Write failing disposition tests**

For each disposition assert exact NodeRun/turn/session transitions, active-capture degradation, no event insertion, DB-time checked-at, and cleared recovery owner/expiry. `FORGE_TERMINAL` preserves all NodeRun history and leaves provider recovery evidence null.

- [ ] **Step 6: Implement one fenced reconciliation transaction**

Validate exact turn/session/owner/token and `recovery_lease_expires_at>CURRENT_TIMESTAMP` before mutation. Apply only the selected disposition. Clear the expired normal lease and increment its token so late callbacks stay stale.

- [ ] **Step 7: Verify GREEN and commit**

```bash
git add services/forge-agent/domain services/forge-agent/infrastructure/postgres services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java
git commit -m "feat(agent): fence restart reconciliation"
```

### Task 4: Orchestrate recovery outside transactions

**Files:**
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutionRecoveryService.java`
- Create: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/AgentExecutionRecoveryServiceTest.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentSessionLeaseService.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/NodeRunLifecycle.java`

**Interfaces:**
- Produces: `int AgentExecutionRecoveryService.reconcileExpired()` with an instance-unique owner ID.
- Consumes: session repository, `List<AgentExecutionRecoveryInspector>`, `WorkflowRunRepository`, and `ExecutionWorkspaceResolver`.
- Removes expiry orchestration from lease service and lifecycle.

- [ ] **Step 1: Write failing Forge-terminal tests**

For `SUCCEEDED`, `FAILED`, and `CANCELLED`, assert no inspector/workspace call and one `FORGE_TERMINAL` reconciliation preserving the claim's failure metadata.

- [ ] **Step 2: Write failing classification tests**

Assert `TERMINAL -> AGENT_EXECUTION_RECOVERY_REQUIRED`, `ACTIVE -> AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE`, and `UNKNOWN -> AGENT_EXECUTION_RECOVERY_UNKNOWN`. Verify the required terminal-loss message and exact inspection IDs/workspace.

- [ ] **Step 3: Write failing closed-path tests**

Missing thread/turn, unsupported provider/version, missing WorkflowRun/workspace, inspector exception/timeout, and null result all select `PROVIDER_UNKNOWN_FAIL_CLOSED` without execution mutation.

Add an inspector test double that records `TransactionSynchronizationManager.isActualTransactionActive()` and assert it is `false`, proving the provider boundary runs after the claim transaction has closed.

- [ ] **Step 4: Confirm RED**

```bash
mvn -pl services/forge-agent/application -am -Dtest=AgentExecutionRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Implement minimal orchestration**

Keep claim and commit as separate repository calls. Resolve workspace only after Forge-terminal short-circuit. Convert provider/workspace failures to diagnostic `UNKNOWN`; never retry, resume, or start execution.

- [ ] **Step 6: Verify GREEN and commit**

```bash
git add services/forge-agent/application
git commit -m "feat(agent): orchestrate expired turn recovery"
```

### Task 5: Keep worker orchestration-light and protect scheduling

**Files:**
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/NodeRunWorker.java`
- Modify: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/NodeRunWorkerTest.java`
- Modify: `services/forge-agent/boot/src/main/java/com/sitionix/forgeagent/ForgeAgentWorkerConfiguration.java`

**Interfaces:**
- Worker receives `AgentExecutionRecoveryService` and invokes `reconcileExpired()` once before `findPendingIds()`.
- No provider-specific branch enters the worker.

- [ ] **Step 1: Write failing ordering and duplicate-protection tests**

Verify recovery completes before the pending scan. Return an orphan ID from a hostile repository fake, make `tryStart` refuse it, and assert no executor submission. Let an unrelated pending NodeRun execute once in the same poll.

- [ ] **Step 2: Confirm RED**

```bash
mvn -pl services/forge-agent/application -am -Dtest=NodeRunWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 3: Wire the service without changing normal paths**

Remove `NodeRunLifecycle.recoverExpiredSessions()`. Preserve executor, heartbeat, cancellation, success, and failure behavior.

- [ ] **Step 4: Verify GREEN and commit**

```bash
git add services/forge-agent/application services/forge-agent/boot/src/main/java/com/sitionix/forgeagent/ForgeAgentWorkerConfiguration.java
git commit -m "refactor(agent): delegate restart reconciliation"
```

### Task 6: Prove integrated semantics and duplicate-turn protection

**Files:**
- Modify: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java`

- [ ] **Step 1: Add failing terminal Forge truth matrix**

Cover `SUCCEEDED`, `FAILED`, and `CANCELLED`; assert no inspection, exact NodeRun history, Phase 5A-compatible session/turn state, and no fabricated event.

- [ ] **Step 2: Add failing provider state matrix**

For reusable and fresh sessions cover `TERMINAL`, `ACTIVE`, `UNKNOWN`, missing identity, and unsupported version. Assert exact codes, evidence/timestamp, session state, capture degradation, and zero normal executor/`turn/start` calls.

- [ ] **Step 3: Add fencing, crash, and scheduler regressions**

Prove one owner in a two-worker race, reclaim after expiry, stale commit rejection, no transition back to `PENDING`, and successful unrelated WorkflowRun execution afterward.

- [ ] **Step 4: Make integration tests GREEN**

```bash
mvn -pl services/forge-agent/boot -am -Dtest=ForgeAgentPortAwareExecutionIT,ForgeAgentScopedExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 5: Commit**

```bash
git add services/forge-agent/boot/src/test
git commit -m "test(agent): cover provider-aware reconciliation"
```

### Task 7: Add gated real Codex restart acceptance

**Files:**
- Modify: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java`
- Modify: `docs/codex-durable-session-protocol-audit.md`

**Interfaces:**
- Gate: `forge.codex.live-recovery-e2e=true`.
- Initial durable execution creates identities; recovery uses a new process, commits through the real Postgres repository, and leaves unrelated execution operational.

- [ ] **Step 1: Write the gated restart test**

In the existing boot integration fixture, start a real tracked durable turn, capture and persist thread/turn/version, let execution finish without calling Forge success persistence, close the original provider process, expire the Forge execution lease, and call `AgentExecutionRecoveryService.reconcileExpired()`. Assert `TERMINAL`, `AGENT_EXECUTION_RECOVERY_REQUIRED`, degraded capture, correct session disposition, and no duplicate provider turn. Then execute an unrelated WorkflowRun successfully.

- [ ] **Step 2: Audit active-turn interruption conservatively**

If an active turn can be held deterministically, inspect it from a fresh process. Attempt exact `turn/interrupt` only in the audit test. Enable production interruption only if response and subsequent exact-turn state repeatedly prove safety; otherwise document unsupported and keep `ACTIVE` fail-closed.

- [ ] **Step 3: Run gated live tests**

```bash
mvn -pl services/forge-agent/boot -am -Dforge.codex.live-session-e2e=true -Dforge.codex.live-recovery-e2e=true -Dtest=CodexDurableSessionE2ETest,CodexRecoveryE2ETest,ForgeAgentPortAwareExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 4: Finalize evidence and commit**

```bash
git add services/forge-agent/infrastructure/codex/src/test services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java docs/codex-durable-session-protocol-audit.md
git commit -m "test(agent): add live restart recovery acceptance"
```

### Task 8: Full regression, review, PR, and exact-HEAD CI

**Files:**
- Modify only files required by failures attributable to Phase 5B.

- [ ] **Step 1: Run focused recovery suites together**

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am -Dtest=AgentExecutionRecoveryServiceTest,CodexRecoveryProtocolTest,CodexRecoveryInspectorTest,NodeRunWorkerTest,ForgeAgentRuntimeMigrationIT,ForgeAgentPortAwareExecutionIT,ForgeAgentScopedExecutionIT -Dsurefire.failIfNoSpecifiedTests=false test
```

- [ ] **Step 2: Run required full Forge Agent verification**

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify
```

- [ ] **Step 3: Check repository hygiene and exact diff**

```bash
git diff --check origin/main...HEAD
git status --short
git diff --stat origin/main...HEAD
```

- [ ] **Step 4: Request code review and fix every Critical/Important finding through a failing test**

Review `origin/main...HEAD` against the spec and acceptance criteria, then rerun focused and full verification.

- [ ] **Step 5: Push and create the single PR**

```bash
git push -u origin feature/SITIONIX-116
gh pr create --base main --head feature/SITIONIX-116 --title "Phase 5B: add provider-aware restart reconciliation" --body-file /tmp/forge-phase5b-pr-body.md
```

The PR body includes architecture, migration, protocol findings, state semantics, fencing, duplicate-turn tests, live result, and verification.

- [ ] **Step 6: Require GitHub CI green on exact final HEAD**

Compare `git rev-parse HEAD` with `gh pr view --json headRefOid --jq .headRefOid`, then monitor `gh pr checks "$(gh pr view --json number --jq .number)" --watch`. Reproduce any Phase 5B failure locally, add a failing regression test, fix, rerun full verification, push, and monitor the new HEAD.

- [ ] **Step 7: Prepare the final report**

Report exact files, architecture, V29/model additions, installed/audited Codex version, exact APIs, state semantics, fencing/crash behavior, duplicate-turn coverage, gated live result, full verification, final HEAD, PR URL, exact-HEAD CI, and review readiness.
