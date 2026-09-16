# Phase 6A: iteration-scoped context lifetime

## Runtime contract

`REUSE_WITHIN_WORKFLOW_ITERATION` adds a lifetime boundary inside one WorkflowRun. Implementer and Reviewer keep separate Forge sessions and separate provider conversations. A common `contextGroupKey` only propagates lifetime identity.

Workflow Node → immutable RunNode snapshot → NodeRun copies `contextMode` and `contextGroupKey`. Template edits affect future WorkflowRuns only. The integration workflow changes the template group after creating the run and verifies that all subsequent invocations retain the snapshotted group.

On activation, the existing planner supplies exactly the delivered contribution source NodeRuns to the factory. Same workflow, group and repository identities are deduplicated: zero creates a UUID; one propagates; multiple throw `AGENT_CONTEXT_ITERATION_CONFLICT`. Completion persists that typed failure and rolls back target creation. No target NodeRun, session or provider execution is created. Contributions from outside the group do not carry identity; leaving and later entering starts a new UUID.

UUID identity is independent of ExecutionFrame. The two-entry integration scenario asserts distinct feedback frames with identical iteration IDs. GLOBAL uses null repository; PER_SCOPE identities and sessions stay independent per repository. Template validation rejects a group mixing GLOBAL/PER_SCOPE, blank iteration groups, or group keys on existing modes.

## Persistence and allocation

V32 adds `context_group_key` to `workflow_nodes`, `workflow_run_nodes`, and `node_runs`; `context_iteration_id` to `node_runs` and `agent_execution_sessions`. CHECK constraints enforce mode/group/identity combinations. Historical rows retain null iteration IDs. Provider conversation uniqueness is unchanged.

The normal allocator reuses `(workflowRunId, contextMode, contextIterationId, sourceNodeId, repositoryId)` among non-reset sessions. Separate GLOBAL/PER_SCOPE partial unique indexes permit different iterations and reject duplicate current sessions within one iteration. Retired sessions remain historical; replacement allocation inside the same iteration is allowed.

Reset and allocation keep the existing transaction advisory scope lock, followed by session row locking. The lock is deliberately broader than the new reusable key and serializes both orderings safely. The integration race test covers both existing reusable mode and iteration mode. No new allocator, writer, provider thread sharing, lease or fencing mechanism is introduced.

## Retry, recovery, reset and UI

Retry copies group, UUID and consumed inputs. Safe terminal recovery offers Resume on the same current session. Explicit Reset changes the action to Retry and the next allocation starts a replacement session, sequence 1, inside the same iteration. ACTIVE/UNKNOWN recovery stays fail-closed with no duplicate invocation. Ordinary reset/feedback tests verify unchanged history and later reuse of the replacement.

Agent REST and every Nexus layer (API, domain and agent-client DTO/mappers) carry snapshot and invocation metadata. Builder exposes the third mode and required iteration group. Execution details show mode, group, deterministic per-group/per-repository iteration number and technical UUID. Session IDs remain authoritative for history. Inconsistent iteration/session metadata renders Unavailable and cannot enable verified Activity.

## Real installed Codex evidence — 2026-09-15

Installed audited CLI `0.154.0`, model `gpt-5.6-sol`. The actual workflow uses Preparation → Implementer ↔ Reviewer → Intermediate; Intermediate enters the same Implementer again through its next-iteration port. Each independent iteration rejects once and then approves. Deterministic test routing chooses the ports; all eight Implementer/Reviewer provider calls use real Codex, normal PostgreSQL allocation/leases and lifecycle completion, with read-only protocol recording.

| Iteration | UUID | Implementer provider conversation | Reviewer provider conversation |
| --- | --- | --- | --- |
| A | `6e1419eb-77c3-4976-aadd-70f5ff461e0a` | `01a0a59a-37f5-7191-957a-7233e4a1c2ba` | `01a0a59a-4baf-72e3-a2af-44f61183ab22` |
| B | `840b77ec-de2a-4b1d-a84f-0a3000d679ce` | `01a0a59a-87f0-7ba3-a937-cfaed77b0eff` | `01a0a59a-99db-7681-a1c2-ed65cf5a0fc3` |

PASS: four different Forge sessions/conversations; first calls in both iterations use `thread/start` without `thread/resume`; feedback calls use `thread/resume` with the exact current session conversation. A conversation is never resumed in B. Workflow finished `SUCCEEDED`.

Reproduce:

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am \
  -Dtest=NodeRunFactoryTest -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=ForgeAgentPortAwareExecutionIT#liveCodexIsolatesTwoIndependentIterations' \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dforge.codex.live-iteration-e2e=true verify
```

The live gate requires Docker, authenticated installed audited Codex and provider connectivity. Ordinary verification skips live gates.

## Verification — 2026-09-16

- Agent requested full `verify`: 946 tests, 0 failures/errors, 6 gate skips.
- Nexus requested full `verify`: 217 tests, 0 failures/errors.
- Console full tests: 510 passed across 17 files.
- Console typecheck and build: PASS.
- `git diff --check`: PASS.
- Separate real Codex iteration gate: PASS, one case, no skips.

Tests include two independent entries, both nodes' continuation, frame independence, template mutation, fan-in with outside input/conflicting identities, two repositories, consumed retry inputs, terminal/ACTIVE/UNKNOWN recovery, reset/history/replacement, both reset/allocation race orderings, historical migration and DB uniqueness. Final GitHub CI is tracked on the PR against its exact head commit.
