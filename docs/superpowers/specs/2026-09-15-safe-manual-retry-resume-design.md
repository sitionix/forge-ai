# Safe Manual Retry / Resume Design

Date: 2026-09-15
Phase: 5C
Base: `main` at `704a19b0` (merged Phase 5B / PR #124)

## Goal

Allow an operator to continue a failed WorkflowRun only after Phase 5B persisted proof that the exact previous provider turn is `TERMINAL`. Manual Retry/Resume creates one new NodeRun attempt, preserves the recovered attempt and all of its turn/event history, and delegates execution to the normal worker.

Phase 5C never automatically retries, never starts another provider turn for `ACTIVE` or `UNKNOWN`, and does not reconstruct arbitrary parallel execution.

## Safety invariants

- The recovered NodeRun, AgentExecutionTurn, provider identities, recovery evidence, events, output, failure, and timestamps are immutable.
- A retry is a new `PENDING` NodeRun in the original immutable WorkflowRun snapshot.
- Only the current retry-chain leaf can be retried, and one NodeRun can have at most one direct retry child.
- Eligibility is derived from persisted backend truth and fails closed for missing or legacy data.
- WorkflowRun reopening and retry creation are one database transaction.
- The endpoint persists work only. The normal worker exclusively owns provider execution.
- Reusable context resumes only the exact existing safe `IDLE` Forge session; it never falls back to a fresh session.
- Historical failed attempts are ignored by completion only when a persisted retry child supersedes them.
- Any other `CANCELLED` retry-chain leaf makes the graph unsafe in this first version.

## Considered approaches

### Selected: NodeRun retry lineage plus the existing session model

Add nullable `retryOfNodeRunId` to NodeRun and create the child from the immutable failed snapshot. A transactional application use case owns eligibility, graph safety, idempotency, child creation, and WorkflowRun reopening. Existing worker allocation then naturally creates a new Fresh session or appends a turn to the reusable session.

This is the smallest model that preserves complete history and keeps normal routing, completion, fencing, and provider execution paths authoritative.

### Rejected: a new logical invocation/attempt framework

Introducing a separate logical invocation entity would require broad changes to routing, frames, activation resolution, API views, and completion. Retry lineage already expresses the required one-dimensional history.

### Rejected: resetting the failed NodeRun

Changing the old NodeRun back to `PENDING` would destroy recovery truth, conflate provider turns, and make Event Ledger and timestamps misleading.

## Persistence and lineage

Add Flyway migration `V30`:

- nullable `node_runs.retry_of_node_run_id` referencing `node_runs(id)`;
- a check preventing `id = retry_of_node_run_id`;
- a partial unique index on `retry_of_node_run_id WHERE retry_of_node_run_id IS NOT NULL`.

Historical and normal NodeRuns remain `NULL`. The unique index is the final concurrency guard against sibling attempts. Repository operations expose child lookup and locked WorkflowRun/NodeRun reads. A retry chain is traversed through direct child links; a NodeRun is current exactly when no child exists.

The API and Nexus NodeRun contracts expose `retryOfNodeRunId`. Read responses also expose backend-derived manual recovery state so Console does not infer permission from display strings:

- action: `RETRY`, `RESUME`, or `NONE`;
- rejection reason code when action is `NONE` and the invocation is a recovery failure.

The mutation response contains the authoritative created-or-existing retry NodeRun ID and refreshed WorkflowRun representation.

## Eligibility

The shared application eligibility evaluator permits creation only when all of the following hold:

- WorkflowRun status is `FAILED`;
- target NodeRun belongs to that WorkflowRun and is `FAILED`;
- target failure code is exactly `AGENT_EXECUTION_RECOVERY_REQUIRED`;
- the target has exactly one persisted execution allocation whose turn has `providerRecoveryState=TERMINAL`;
- target output and `routingCompletedAt` are `NULL`;
- target has no retry child;
- target execution snapshot and recovery identity are complete;
- for `REUSE_WITHIN_WORKFLOW_NODE`, the same scoped session is `IDLE`, has no owner or active NodeRun, and retains valid provider conversation/version identity;
- graph continuation passes the conservative rule below.

`ACTIVE`, `UNKNOWN`, their failure codes, unrelated failures, routed output, incomplete legacy truth, `CANCELLED`, `SUCCEEDED`, and inconsistent ownership are rejected. There is no override.

Typed conflicts distinguish at least:

- `WORKFLOW_RUN_RETRY_NOT_ALLOWED` for terminal/status/recovery-truth violations;
- `NODE_RUN_RETRY_SUPERSEDED` when a different leaf is targeted;
- `AGENT_CONTEXT_NOT_SAFELY_REUSABLE` for reusable-session violations;
- `WORKFLOW_RUN_RETRY_UNSAFE` for graph ambiguity.

An already-created direct child is an idempotent success, not an error, provided it belongs to the same target and WorkflowRun.

## Conservative graph continuation

Phase 5B may cancel other active work while failing the WorkflowRun. Phase 5C does not resurrect it.

Before reopening, derive current retry-chain leaves for every NodeRun in the WorkflowRun. If any leaf other than the target is `CANCELLED`, return `WORKFLOW_RUN_RETRY_UNSAFE` without mutation. Superseded historical cancellations do not independently block. Existing failed/blocked current leaves also continue to retain normal workflow failure semantics; retrying one recovered leaf cannot hide an unrelated failure.

This admits normal sequential Implementer → Reviewer flows where the failed NodeRun never routed and no downstream NodeRun was created. It deliberately rejects ambiguous parallel recovery.

## Atomic retry use case

`RetryRecoveredNodeRunUseCase.execute(workflowRunId, nodeRunId)` runs in one transaction with the established lock order:

1. lock the WorkflowRun;
2. lock the target NodeRun;
3. confirm ownership and look up a direct child;
4. if a child exists, return it as the deterministic idempotent result;
5. load and validate exact recovery allocation/session truth;
6. validate target eligibility and graph safety;
7. create a new NodeRun with a new ID, `PENDING`, current `createdAt`, and `retryOfNodeRunId=target.id`;
8. copy immutable execution snapshot fields: source node/agent identity, agent name/instructions/schema, input/context modes, position, execution model, repository, execution/activation frames, and entered input port;
9. leave output, failure, selected output port, routing timestamp, startedAt, and finishedAt empty;
10. reopen WorkflowRun as `RUNNING`, set `finishedAt=NULL`, and clear stale terminal result/source only when present;
11. flush the child before commit so the unique constraint participates in the transaction.

If two transactions race, row locks serialize normal requests. The partial unique index prevents siblings even under an unexpected lock-path regression. A uniqueness loser reloads and returns the persisted direct child in a deterministic idempotent path.

The use case never reads mutable Workflow or Agent definitions.

## Completion and routing semantics

Completion evaluates the current logical attempts, defined as NodeRuns without a direct retry child. `FailedExecutionCompletionRule` considers only current leaves. Active, unrouted-success, open-activation, and quiescent-success rules likewise operate without treating superseded attempts as pending work or current output.

The database query that finds WorkflowRuns requiring completion ignores `FAILED`/`BLOCKED` attempts with a retry child. Therefore reopening does not immediately rediscover the historical failure.

Only superseded failures are weakened:

- `A FAILED → B PENDING/RUNNING/SUCCEEDED`: A remains visible but does not fail the run;
- `A FAILED → B FAILED`: B is the current leaf and fails the run normally;
- successful B enters the existing completion worker and routing path exactly once.

No connection resolution, execution edge, activation frame, Event Ledger row, or prior routing timestamp is rewritten.

## Context behavior

### Fresh

For `FRESH_EACH_NODE_RUN`, retry allocation follows the existing normal path:

`new NodeRun → new Forge session → queued turn sequence 1 → thread/start → turn/start`.

The UI action is `Retry`.

### Reusable

For `REUSE_WITHIN_WORKFLOW_NODE`, eligibility proves that the Phase 5B session is reusable and `IDLE`. Existing session allocation finds the same workflow/node/repository-scoped session, appends the next turn sequence, and normal execution performs:

`same Forge session → thread/resume(existing providerConversationId) → new turn/start`.

The UI action is `Resume`. Existing profile fingerprint, provider version, one-writer, lease, and fencing checks remain authoritative. Any failure to reuse the exact session fails closed; no fresh session or thread is created as fallback.

## API and Nexus

Forge Agent adds:

`POST /api/v1/workflow-runs/{workflowRunId}/node-runs/{nodeRunId}/retry`

The endpoint returns `200 OK` for both initial creation and deterministic duplicate requests, with the retry NodeRun identifier and current WorkflowRun backend truth. Typed conflicts use the existing error envelope and correlation ID conventions.

Nexus adds the equivalent typed domain use case, client method, DTO mapping, and controller endpoint. Nexus contains no eligibility, graph, lineage, or session decisions.

## Console

Task Execution consumes backend action truth:

- Fresh eligible recovery: “Recovery required … [Retry]”;
- reusable eligible recovery: “Recovery required … [Resume]”;
- `ACTIVE`, `UNKNOWN`, unsafe graph, or any ineligible state: truthful read-only recovery status and no enabled action.

The action uses a single in-flight request. It displays `Retrying…` or `Resuming…`, performs no optimistic lifecycle mutation, then refreshes WorkflowRun truth and selects/pins the returned child invocation. The invocation list remains lineage-complete, so the old failed invocation and its Activity remain navigable.

Request and refresh generations prevent an older response from replacing newer backend truth. A rejected mutation preserves the current UI state and displays the typed backend error.

## Crash safety

- Crash after transaction commit: the durable child remains `PENDING` and WorkflowRun `RUNNING`; the normal worker later acquires it.
- Crash during the new provider turn: Phase 5B recovery applies to the new NodeRun/turn without a second mechanism.
- Crash after success before routing: the existing completion worker routes exactly once.
- Crash or timeout before transaction commit: neither child nor reopened WorkflowRun is visible.

## Testing strategy

Implementation follows red-green-refactor cycles.

Backend tests cover:

- Fresh terminal recovery retry, immutable old attempt, new session/thread, successful execution, exactly-once routing, and final workflow result;
- reusable terminal recovery using the same session/conversation, next turn sequence, unchanged old turn, and `thread/resume` without `thread/start`;
- `ACTIVE` and `UNKNOWN` rejection with zero new NodeRuns, turns, or provider calls;
- duplicate concurrent requests producing one child;
- retry chain leaf enforcement and later child retry;
- superseded failure versus current-leaf completion semantics;
- conservative unsafe graph rejection without mutation;
- `CANCELLED` and `SUCCEEDED` WorkflowRuns remaining terminal;
- API response/error mapping, migration constraints, and Nexus thin-proxy behavior;
- unchanged Fresh/Continued, scoping, feedback/re-entry, leases/fencing, Stop, Phase 5B recovery, ledger/activity, and routing suites.

Console tests cover action labels and visibility, single-flight, no optimistic reopen, refresh-and-select, old invocation navigation, backend rejection, and stale response suppression.

The gated real Codex acceptance reproduces a reusable crash-after-provider-terminal window, lets Phase 5B persist `TERMINAL`, invokes Resume, and verifies the same conversation, a new Forge/provider turn, `thread/resume` only, immutable old history, exactly-once routing, successful workflow completion, and a healthy unrelated WorkflowRun afterward.

## Verification and delivery

Run:

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify
mvn -B -ntp -pl services/forge-nexus/boot -am verify

cd services/forge-console
npm test -- --run
npm run typecheck
npm run build

git diff --check
```

Run the gated real Codex recovery → Resume E2E with the installed audited CLI. Push one Phase 5C branch, open one PR against `main`, and require full GitHub CI green on the exact final HEAD.

## Out of scope

No automatic retry, arbitrary normal failure retry, `ACTIVE`/`UNKNOWN` retry, arbitrary parallel reconstruction, Reset context, fork, compaction, steering, cross-WorkflowRun reuse, shared sessions, retry policy, backoff, or budget logic.
