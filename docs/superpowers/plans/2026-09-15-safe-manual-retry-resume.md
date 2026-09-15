# Safe Manual Retry / Resume Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit, fail-closed operator Retry/Resume that creates exactly one new NodeRun after Phase 5B proved the prior provider turn terminal.

**Architecture:** Persist a single-parent retry lineage on `node_runs`, validate and create retries in one locked transaction, and let the existing worker/session allocator execute the new attempt. Derive current logical attempts by excluding NodeRuns with a direct retry child, and expose backend-derived eligibility through Forge Agent and the Nexus proxy so Console never invents safety decisions.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA/JdbcTemplate, PostgreSQL/Flyway, Maven, vanilla JavaScript, TypeScript declarations, Vitest/jsdom.

**Spec:** `docs/superpowers/specs/2026-09-15-safe-manual-retry-resume-design.md`

## Global Constraints

- Start from `main` at `704a19b0`, merged Phase 5B / PR #124.
- Never automatically retry or execute a provider turn from the retry endpoint.
- Never permit `ACTIVE`, `UNKNOWN`, unrelated failures, incomplete recovery truth, or unsafe parallel graph state.
- Reusable retry must reuse the exact safe session and conversation; no fresh fallback.
- Preserve all old NodeRun, turn, provider identity, recovery evidence, event, and timestamp data.
- One roadmap unit produces one PR from `feature/SITIONIX-117`.
- Keep Phase 5A/5B, normal execution, routing, fencing, ledger, activity, and snapshot semantics unchanged.

---

### Task 1: Persist retry lineage and map it end to end

**Files:**
- Create: `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/V30__add_node_run_retry_lineage.sql`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/NodeRun.java`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/NodeRunRepository.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/entity/NodeRunEntity.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresNodeRunMapper.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresNodeRunRepository.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/repository/SpringDataNodeRunRepository.java`
- Test: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentRuntimeMigrationIT.java`
- Test: `services/forge-agent/infrastructure/postgres/src/test/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresWorkflowRunRepositoryTest.java`

**Interfaces:**
- Produces: `NodeRun.retryOfNodeRunId(): UUID`, `NodeRunRepository.findRetryChild(UUID): Optional<NodeRun>`.

- [ ] **Step 1: Write migration and repository tests that initially fail**

Assert historical rows load with `retryOfNodeRunId == null`, a child round-trips its parent ID, a foreign parent fails, self-reference fails, and two children for one parent violate uniqueness.

```java
assertThat(repository.findById(child.id()).orElseThrow().retryOfNodeRunId()).isEqualTo(parent.id());
assertThatThrownBy(() -> save(secondChild)).isInstanceOf(DataIntegrityViolationException.class);
```

- [ ] **Step 2: Run the focused tests and confirm red**

Run: `mvn -B -ntp -pl services/forge-agent/infrastructure/postgres -am -Dtest=PostgresWorkflowRunRepositoryTest test`

Expected: compilation/test failure because retry lineage does not exist.

- [ ] **Step 3: Add the migration and mapping**

```sql
ALTER TABLE node_runs
    ADD COLUMN retry_of_node_run_id UUID NULL REFERENCES node_runs(id),
    ADD CONSTRAINT chk_node_runs_retry_not_self CHECK (retry_of_node_run_id IS NULL OR retry_of_node_run_id <> id);

CREATE UNIQUE INDEX uq_node_runs_retry_of
    ON node_runs(retry_of_node_run_id)
    WHERE retry_of_node_run_id IS NOT NULL;
```

Append `UUID retryOfNodeRunId` to the canonical NodeRun record and update every constructor/copy site explicitly. Add Spring Data child lookup and repository delegation.

- [ ] **Step 4: Run mapping and migration tests green**

Run: `mvn -B -ntp -pl services/forge-agent/infrastructure/postgres -am -Dtest=PostgresWorkflowRunRepositoryTest test`

- [ ] **Step 5: Commit lineage persistence**

```bash
git add services/forge-agent
git commit -m "feat(agent): persist node run retry lineage"
```

### Task 2: Define retry eligibility and atomic creation

**Files:**
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/RecoveredNodeRunRetryAction.java`
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/RecoveredNodeRunRetryEligibility.java`
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/RetryRecoveredNodeRunResult.java`
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/RecoveredNodeRunRetryEligibilityService.java`
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/RetryRecoveredNodeRunUseCase.java`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/AgentExecutionSessionRepository.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresAgentExecutionSessionRepository.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/usecase/RetryRecoveredNodeRunUseCaseTest.java`

**Interfaces:**
- Produces: `RetryRecoveredNodeRunResult(UUID nodeRunId, WorkflowRun workflowRun)`.
- Produces: `RecoveredNodeRunRetryEligibility(action, reasonCode)` where action is `RETRY`, `RESUME`, or `NONE`.
- Consumes: exact `AgentExecutionAllocation` from the Phase 5B repository.

- [ ] **Step 1: Write failing eligibility/use-case tests**

Cover eligible Fresh and reusable attempts, every required rejection, immutable copied fields, WorkflowRun reopening, result clearing, unsafe other cancelled leaf, incomplete allocation, unsafe reusable session, and an existing direct child returned idempotently.

```java
final RetryRecoveredNodeRunResult result = useCase.execute(WORKFLOW_RUN_ID, FAILED_NODE_RUN_ID);
assertThat(result.workflowRun().status()).isEqualTo(WorkflowRunStatus.RUNNING);
assertThat(result.nodeRunId()).isNotEqualTo(FAILED_NODE_RUN_ID);
verify(nodeRuns).saveAndFlush(argThat(child -> child.retryOfNodeRunId().equals(FAILED_NODE_RUN_ID)));
```

For `ACTIVE` and `UNKNOWN`, assert `ConflictException.code()` and verify no `save`, no allocation, and no workflow lifecycle write.

- [ ] **Step 2: Run tests red**

Run: `mvn -B -ntp -pl services/forge-agent/application -am -Dtest=RetryRecoveredNodeRunUseCaseTest test`

- [ ] **Step 3: Implement a pure eligibility evaluator**

Use exact constants and no heuristics:

```java
if (run.status() != WorkflowRunStatus.FAILED
        || target.status() != NodeRunStatus.FAILED
        || target.failure() == null
        || !"AGENT_EXECUTION_RECOVERY_REQUIRED".equals(target.failure().code())
        || target.output() != null
        || target.routingCompletedAt() != null
        || allocation.turn().providerRecoveryState() != ProviderTurnRecoveryState.TERMINAL) {
    return RecoveredNodeRunRetryEligibility.none("WORKFLOW_RUN_RETRY_NOT_ALLOWED");
}
```

For reusable context require `IDLE`, absent lease/active NodeRun, nonblank conversation/provider version, and the exact scoped session. Reject another `CANCELLED` current leaf with `WORKFLOW_RUN_RETRY_UNSAFE`.

- [ ] **Step 4: Implement the locked transaction**

```java
@Transactional
public RetryRecoveredNodeRunResult execute(UUID workflowRunId, UUID nodeRunId) {
    WorkflowRun run = workflows.findByIdForUpdate(workflowRunId).orElseThrow(...);
    NodeRun target = nodeRuns.findByIdForUpdate(nodeRunId).orElseThrow(...);
    Optional<NodeRun> existing = nodeRuns.findRetryChild(target.id());
    if (existing.isPresent()) return result(existing.get(), run);
    eligibility.requireEligible(run, target);
    NodeRun child = nodeRuns.saveAndFlush(retryFactory.copy(target, clock.instant()));
    WorkflowRun reopened = workflows.saveLifecycle(reopen(run));
    return new RetryRecoveredNodeRunResult(child.id(), reopened);
}
```

Copy all immutable snapshot fields and clear every mutable lifecycle/result field. Do not call session allocation or provider code here.

- [ ] **Step 5: Run use-case tests green**

Run: `mvn -B -ntp -pl services/forge-agent/application -am -Dtest=RetryRecoveredNodeRunUseCaseTest test`

- [ ] **Step 6: Commit atomic retry creation**

```bash
git add services/forge-agent
git commit -m "feat(agent): create recovered retries atomically"
```

### Task 3: Make workflow completion retry-lineage aware

**Files:**
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/NodeRunRetryLineage.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/QuiescenceWorkflowCompletionPolicy.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/WorkflowCompletionContext.java`
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/repository/SpringDataNodeRunRepository.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/QuiescenceWorkflowCompletionPolicyTest.java`
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/runtime/WorkflowExecutionCoordinatorTest.java`
- Test: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java`

**Interfaces:**
- Produces: `NodeRunRetryLineage.currentLeaves(List<NodeRun>): List<NodeRun>`.

- [ ] **Step 1: Add failing completion tests**

```java
assertThat(policy.evaluate(runWith(aFailedSuperseded, bRunning)))
        .isInstanceOf(ContinueWorkflowDecision.class);
assertThat(policy.evaluate(runWith(aFailedSuperseded, bFailedLeaf)))
        .isInstanceOf(FailedWorkflowDecision.class);
```

Add integration coverage proving reopened workflows are not immediately selected because of historical A, while failed leaf B remains selected.

- [ ] **Step 2: Run focused completion tests red**

Run: `mvn -B -ntp -pl services/forge-agent/application -am -Dtest=QuiescenceWorkflowCompletionPolicyTest,WorkflowExecutionCoordinatorTest test`

- [ ] **Step 3: Filter completion input to current leaves**

```java
Set<UUID> superseded = nodeRuns.stream()
        .map(NodeRun::retryOfNodeRunId)
        .filter(Objects::nonNull)
        .collect(toSet());
return nodeRuns.stream().filter(run -> !superseded.contains(run.id())).toList();
```

Keep full NodeRun history available to API/routing repositories; filter only the logical completion view. Update the completion-candidate JPQL with `not exists` child lineage.

- [ ] **Step 4: Run completion tests green**

Run: `mvn -B -ntp -pl services/forge-agent/application -am -Dtest=QuiescenceWorkflowCompletionPolicyTest,WorkflowExecutionCoordinatorTest test`

- [ ] **Step 5: Commit retry-aware completion**

```bash
git add services/forge-agent
git commit -m "fix(agent): complete workflows from retry leaves"
```

### Task 4: Prove normal Fresh and reusable worker behavior

**Files:**
- Modify: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentPortAwareExecutionIT.java`
- Modify: `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexDurableSessionE2ETest.java`
- Modify: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/infrastructure/codex/LiveCodexRecoveryFixture.java`
- Create: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/infrastructure/codex/LiveCodexRecoveryResumeE2ETest.java`

**Interfaces:**
- Consumes: `RetryRecoveredNodeRunUseCase` and unchanged normal `NodeRunWorker` allocation.

- [ ] **Step 1: Add failing integrated Fresh retry test**

Recover terminal A, call retry, poll the normal worker, and assert old A/turn unchanged, child B uses a distinct session/conversation, routes once, and produces the expected terminal WorkflowRun.

- [ ] **Step 2: Add failing reusable Resume test**

Capture method calls from the fake Codex app server and assert:

```java
assertThat(resumed.session().id()).isEqualTo(recovered.session().id());
assertThat(resumed.session().providerConversationId()).isEqualTo(recovered.session().providerConversationId());
assertThat(resumed.turn().sequence()).isEqualTo(recovered.turn().sequence() + 1);
assertThat(methods).contains("thread/resume", "turn/start").doesNotContain("thread/start");
```

- [ ] **Step 3: Run integrated tests and fix only normal-path incompatibilities**

Run: `mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am -Dtest=ForgeAgentPortAwareExecutionIT test`

Expected: PASS without adding provider calls to the retry endpoint.

- [ ] **Step 4: Add gated real Codex recovery → Resume acceptance**

Use the installed audited CLI and existing fixture conventions. Persist exact thread/turn, simulate lost Forge result, run Phase 5B reconciliation, invoke retry, execute the worker, then assert same conversation, new turn IDs, resume-only protocol, immutable old attempt, exactly-once routing, successful workflow, and a later unrelated run.

- [ ] **Step 5: Commit execution acceptance**

```bash
git add services/forge-agent
git commit -m "test(agent): cover recovered retry execution"
```

### Task 5: Add Forge Agent retry API and backend eligibility truth

**Files:**
- Create: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/dto/RecoveredNodeRunRetryResponse.java`
- Create: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/dto/RecoveredNodeRunRetryEligibilityResponse.java`
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/dto/NodeRunResponse.java`
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/dto/WorkflowRunResponse.java`
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/ForgeAgentApiMapper.java`
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/ForgeAgentController.java`
- Test: `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/ForgeAgentControllerTest.java`
- Test: `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/ForgeAgentApiMapperTest.java`
- Test: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentWorkflowRunIT.java`

**Interfaces:**
- Produces: `POST /api/v1/workflow-runs/{workflowRunId}/node-runs/{nodeRunId}/retry`.
- Produces response: `{ nodeRunId, workflowRun }` with each NodeRun carrying lineage and `{ action, reasonCode }` eligibility.

- [ ] **Step 1: Write failing controller and JSON contract tests**

Assert the endpoint delegates once, returns `200`, maps initial/duplicate result identically, and typed conflicts retain their code. Assert GET WorkflowRun includes `retryOfNodeRunId` and backend eligibility.

- [ ] **Step 2: Run API tests red**

Run: `mvn -B -ntp -pl services/forge-agent/api-rest -am -Dtest=ForgeAgentControllerTest,ForgeAgentApiMapperTest test`

- [ ] **Step 3: Add response DTOs and controller method**

```java
@PostMapping("/api/v1/workflow-runs/{workflowRunId}/node-runs/{nodeRunId}/retry")
public ResponseEntity<RecoveredNodeRunRetryResponse> retry(
        @PathVariable UUID workflowRunId, @PathVariable UUID nodeRunId) {
    return ResponseEntity.ok(mapper.toResponse(retryRecoveredNodeRun.execute(workflowRunId, nodeRunId)));
}
```

Use the same eligibility service for GET and mutation mapping; never reproduce business conditions in the controller.

- [ ] **Step 4: Run API tests green and commit**

Run: `mvn -B -ntp -pl services/forge-agent/api-rest -am -Dtest=ForgeAgentControllerTest,ForgeAgentApiMapperTest test`

```bash
git add services/forge-agent
git commit -m "feat(agent): expose recovered retry endpoint"
```

### Task 6: Add the thin typed Nexus proxy

**Files:**
- Create: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/usecase/RetryRecoveredAgentNodeRun.java`
- Create: `services/forge-nexus/application/src/main/java/com/sitionix/forgeai/application/usecase/agentproxy/RetryRecoveredAgentNodeRunUseCase.java`
- Create: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/model/agentproxy/RecoveredAgentNodeRunRetry.java`
- Create: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/model/agentproxy/AgentNodeRunRetryEligibility.java`
- Create: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/dto/RecoveredNodeRunRetryResponse.java`
- Create: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/dto/NodeRunRetryEligibilityResponse.java`
- Create: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/agentproxy/AgentRecoveredNodeRunRetryResponse.java`
- Create: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/agentproxy/AgentNodeRunRetryEligibilityResponse.java`
- Modify: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/port/ForgeAgentClient.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentHttpClient.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientAdapter.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientMapper.java`
- Modify: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureAgentsController.java`
- Modify: Nexus NodeRun/WorkflowRun DTOs and mappers to carry lineage/eligibility unchanged.
- Test: `services/forge-nexus/application/src/test/java/com/sitionix/forgeai/application/usecase/agentproxy/AgentProxyUseCaseTest.java`
- Test: `services/forge-nexus/clients/agent-client/src/test/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientAdapterTest.java`
- Test: `services/forge-nexus/api-rest/src/test/java/com/sitionix/forgeai/api/ForgeAiInfrastructureAgentsControllerTest.java`

**Interfaces:**
- Produces the equivalent Nexus POST endpoint and maps Forge Agent truth without policy branches.

- [ ] **Step 1: Write failing delegation, client mapping, and controller tests**

```java
when(client.retryRecoveredNodeRun(RUN_ID, NODE_RUN_ID)).thenReturn(result);
assertThat(useCase.execute(RUN_ID, NODE_RUN_ID)).isSameAs(result);
verify(client).retryRecoveredNodeRun(RUN_ID, NODE_RUN_ID);
```

- [ ] **Step 2: Run Nexus focused tests red**

Run: `mvn -B -ntp -pl services/forge-nexus/boot -am -Dtest=AgentProxyUseCaseTest,ForgeAgentClientAdapterTest,ForgeAiInfrastructureAgentsControllerTest test`

- [ ] **Step 3: Implement one-to-one typed proxy contracts**

Feign calls Forge Agent at the exact Phase 5C path. Mapper transfers IDs, status, lineage, action, reason code, and WorkflowRun truth. No Nexus method checks failure codes or session state.

- [ ] **Step 4: Run Nexus tests green and commit**

Run: `mvn -B -ntp -pl services/forge-nexus/boot -am -Dtest=AgentProxyUseCaseTest,ForgeAgentClientAdapterTest,ForgeAiInfrastructureAgentsControllerTest test`

```bash
git add services/forge-nexus
git commit -m "feat(nexus): proxy recovered node retry"
```

### Task 7: Add Retry/Resume to Task Execution

**Files:**
- Modify: `services/forge-console/src/operator/agent-projects-api.js`
- Modify: `services/forge-console/src/operator/agent-projects-api.d.ts`
- Modify: `services/forge-console/src/operator/task-execution-view.js`
- Modify: `services/forge-console/src/operator/task-execution-view.d.ts`
- Modify: `services/forge-console/src/operator/operator-ui.css`
- Test: `services/forge-console/tests/agent-projects-page.test.ts`

**Interfaces:**
- Consumes: `retryRecoveredNodeRun(workflowRunId, nodeRunId)` and backend `retryEligibility.action`.

- [ ] **Step 1: Write the eight required failing Console tests**

Cover eligible Fresh `Retry`, eligible reusable `Resume`, no enabled action for ACTIVE/UNKNOWN/unsafe, single-flight, no optimistic reopen on rejection, refresh and child selection on success, old Activity navigation, and stale/duplicate response suppression.

```typescript
expect(details.textContent).toContain('Resume');
button.click();
button.click();
expect(fakeApi.retryRecoveredNodeRun).toHaveBeenCalledTimes(1);
expect(view.state.selectedNodeRunId).toBe('retry-child');
```

- [ ] **Step 2: Run Console tests red**

Run: `cd services/forge-console && npm test -- --run tests/agent-projects-page.test.ts`

- [ ] **Step 3: Add the typed API call and state machine**

```javascript
retryRecoveredNodeRun(workflowRunId, nodeRunId) {
  return this.request(`/api/v1/workflow-runs/${workflowRunId}/node-runs/${nodeRunId}/retry`, { method: 'POST' });
}
```

Track the submitting node/action and monotonically increasing request/refresh generations. Disable the button immediately, but do not mutate WorkflowRun/NodeRun status until backend refresh succeeds.

- [ ] **Step 4: Render truthful recovery copy and retain history**

Render from backend action only. On success refresh, apply only the latest generation and select the returned child ID. Keep the complete NodeRun list and current Activity loading behavior.

- [ ] **Step 5: Run Console tests, typecheck, and build**

Run: `cd services/forge-console && npm test -- --run && npm run typecheck && npm run build`

- [ ] **Step 6: Commit Console UI**

```bash
git add services/forge-console
git commit -m "feat(console): add recovered retry controls"
```

### Task 8: Full regression, live acceptance, and PR

**Files:**
- Modify only files required by failures proven to be Phase 5C regressions.

**Interfaces:**
- Produces one reviewable PR with green CI at the exact final HEAD.

- [ ] **Step 1: Run Forge Agent verification**

Run: `mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify`

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run Nexus verification**

Run: `mvn -B -ntp -pl services/forge-nexus/boot -am verify`

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run Console verification**

Run: `cd services/forge-console && npm test -- --run && npm run typecheck && npm run build`

Expected: all tests pass, typecheck succeeds, production build succeeds.

- [ ] **Step 4: Run gated real Codex acceptance**

Run the gated `LiveCodexRecoveryResumeE2ETest` with the repository's existing environment/property convention and audited installed CLI. Record CLI version, test result, exact session/conversation/turn assertions, and unrelated-run result.

- [ ] **Step 5: Perform repository hygiene checks**

Run: `git diff --check && git status --short && git log --oneline origin/main..HEAD`

- [ ] **Step 6: Review all changes against the spec**

Confirm every rejected state is mutation-free; endpoint contains no provider call; old attempts and events are unchanged; completion ignores only superseded attempts; Nexus is policy-free; Console uses backend eligibility.

- [ ] **Step 7: Push and create one PR**

```bash
git push -u origin feature/SITIONIX-117
gh pr create --base main --head feature/SITIONIX-117 --title "Phase 5C: add safe manual retry and resume" --body-file /tmp/phase-5c-pr.md
```

- [ ] **Step 8: Require full GitHub CI green on exact HEAD**

Run: `gh pr checks --watch` and compare `git rev-parse HEAD` with the PR head SHA before reporting readiness.
