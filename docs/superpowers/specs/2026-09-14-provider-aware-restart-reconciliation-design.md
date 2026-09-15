# Provider-Aware Restart Reconciliation Design

Date: 2026-09-14
Phase: 5B
Base: `main` at `c1dae2cb` (merged Phase 5A / PR #123)

## Goal

Replace provider-blind expiry handling with a bounded, fenced reconciliation flow that inspects the exact persisted provider thread and turn after Forge loses execution ownership. Recovery classifies the provider turn as `TERMINAL`, `ACTIVE`, or `UNKNOWN`, persists that evidence, and never starts or resumes provider execution.

Phase 5B does not reconstruct output, routing, or normal execution. It also does not add operator recovery controls.

## Safety invariants

- If a provider turn may have been accepted, recovery never invokes `thread/start`, `thread/resume`, or `turn/start`.
- Provider evidence is correlated to the exact persisted `(providerConversationId, providerTurnId)` pair. Thread existence, thread `idle`, ledger events, and activity observations are not independently sufficient evidence.
- Missing identity, unsupported provider/version, protocol failure, timeout, mismatch, or ambiguity becomes `UNKNOWN`.
- Provider calls never occur inside a database transaction.
- Every reconciliation write is fenced by the current recovery owner, recovery lease token, and unexpired recovery lease according to database time.
- Forge terminal NodeRun truth is checked and preserved before provider inspection. Later provider observations never rewrite terminal NodeRun history.
- Recovery remains bounded to one expired candidate per worker poll.
- The normal scheduler cannot reacquire the orphaned NodeRun while it is claimed or after it is reconciled.
- Existing Fresh/Continued execution, allocation, GLOBAL/PER_SCOPE scoping, heartbeat, Stop, event ledger, activity, output parsing, routing, and workflow completion semantics remain unchanged.

## Considered approaches

### Selected: application orchestration with a two-phase repository protocol

The repository atomically claims one expired execution and returns an immutable snapshot. An application service performs provider-neutral inspection outside the transaction and submits a provider-neutral reconciliation command. The repository then atomically validates fencing and commits the chosen state transition.

This keeps database locking, DB-time leases, and atomic writes in Postgres while moving lifecycle interpretation and provider interaction into their proper layers.

### Rejected: retain orchestration in the Postgres repository

Adding provider inspection to `recoverExpired` would either hold locks during external I/O or force provider-specific branching into persistence infrastructure. Both make crash behavior and transaction duration unsafe.

### Rejected: make the Codex adapter own Forge reconciliation

The adapter can establish provider evidence but cannot authoritatively decide NodeRun, turn, session, and event-capture transitions. That would also prevent a provider-neutral recovery contract.

### Rejected: resume or replace ambiguous execution

Automatic resume, fresh thread creation, or a new turn would violate the duplicate-turn invariant. Ambiguity must remain visible and fail closed.

## Domain and port model

Add an immutable `AgentExecutionRecoveryClaim` carrying only:

- Forge IDs: session, turn, NodeRun, WorkflowRun;
- provider identity: provider ID/version, conversation ID, turn ID;
- context mode;
- recovery fencing: owner ID, token, and lease expiry;
- the execution workspace/context needed by the provider inspector;
- the already-locked Forge NodeRun terminal snapshot when applicable.

Workspace resolution belongs to application orchestration. The database snapshot therefore carries repository/project identity sufficient for the existing `ExecutionWorkspaceResolver`, rather than a filesystem path persisted by Postgres.

Add provider-neutral recovery types:

- `ProviderTurnRecoveryState`: `TERMINAL`, `ACTIVE`, `UNKNOWN`;
- `ProviderTurnRecoveryTerminalOutcome`: `SUCCEEDED`, `FAILED`, `CANCELLED`, `UNKNOWN`;
- `ProviderTurnRecoveryResult`: classification plus optional terminal outcome and a concise diagnostic;
- `AgentExecutionRecoveryInspection`: provider/version, exact conversation/turn IDs, resolved execution workspace,
  and one provider-neutral absolute inspection deadline;
- `AgentExecutionRecoveryInspector`: inspection port with no Codex JSON types;
- `AgentExecutionRecoveryReconciliation`: the application-selected Forge outcome submitted for persistence.

The inspector registry selects by provider ID. Unsupported provider IDs or versions are classified without invoking provider mutation.

Refactor `AgentExecutionSessionRepository` so recovery exposes two operations:

1. `claimExpiredRecovery(ownerId)` claims at most one eligible expired execution and returns its snapshot.
2. `reconcileRecovery(claim, reconciliation)` commits only if the claim still owns the same unexpired recovery lease and token.

Normal execution lease methods remain unchanged. Recovery ownership uses distinct columns so its state is explicit and a stale normal execution lease cannot accidentally fence a recovery write.

## Persistence

Add a Flyway migration after `V28`.

`agent_execution_turns` gains nullable columns:

- `provider_recovery_state` constrained to `TERMINAL`, `ACTIVE`, or `UNKNOWN`;
- `provider_recovery_terminal_outcome` constrained to `SUCCEEDED`, `FAILED`, `CANCELLED`, or `UNKNOWN`;
- `provider_recovery_checked_at`;
- `recovery_owner_id`;
- `recovery_lease_token`, non-negative with a default of zero;
- `recovery_lease_expires_at`.

Historical rows remain `NULL` for recovery classification and timestamp. No state is inferred or backfilled. Ownership columns are cleared by authoritative reconciliation; the monotonically increasing token remains as fencing history.

Recovery ownership is turn-scoped because the evidence and final classification concern the tracked provider turn. The session execution lease remains expired and unchanged until final reconciliation atomically updates the session lifecycle.

The claim transaction:

1. selects one session in `CREATING`, `RESUMING`, or `ACTIVE` whose normal execution lease expired, ordered deterministically and limited to one;
2. locks the workflow run, NodeRun, session, and exact active turn in the established order;
3. refuses candidates with an existing unexpired recovery claim;
4. increments the turn recovery token and assigns owner/expiry using `CURRENT_TIMESTAMP`;
5. returns the exact persisted identities and Forge state.

If Forge crashes after inspection but before commit, another worker may reclaim after recovery lease expiry and inspect again. A delayed result from the first worker fails the owner/token/expiry predicate and changes nothing.

## Application recovery service

`AgentExecutionRecoveryService.reconcileExpired(ownerId)` replaces `AgentSessionLeaseService.recoverExpired`. `NodeRunWorker.poll()` invokes it before scanning normal pending work.

For each bounded poll:

1. claim one expired execution;
2. if no claim exists, return zero;
3. if the claim says the NodeRun is already `SUCCEEDED`, `FAILED`, or `CANCELLED`, skip provider inspection and request the existing terminal reconciliation semantics;
4. validate persisted provider ID, version, conversation ID, and turn ID;
5. derive the absolute inspection deadline as the earlier of recovery lease expiry minus a three-second
   repository-commit reserve and application clock time plus the 27-second maximum inspection budget;
6. resolve the execution workspace and invoke the provider-neutral inspector on a dedicated virtual thread outside
   a transaction, waiting only until the absolute inspection deadline;
7. map inspection exceptions, timeouts, unsupported contracts, malformed data, and identity contradictions to `UNKNOWN`;
8. submit one fenced reconciliation command;
9. report one recovered item only after an authoritative commit; a stale result is rejected and is not counted.

The service contains no Codex JSON parsing. The worker contains no provider branching. If an inspector ignores
interruption or produces a late result, the application commits `UNKNOWN` from the timed wait; that late result has
no repository callback and cannot mutate reconciliation state.

The Codex adapter supervises its complete fresh-process lifecycle on a dedicated virtual thread. It subtracts its
configured graceful-plus-force cleanup reserve from the provider phase, atomically owns any process returned by the
starter, and force-terminates a process that is registered after cancellation. Before terminating a native launcher,
the owner snapshots its descendant handles, terminates descendants child-first, terminates the root through the
nonblocking `ProcessHandle` API, and repeats termination on the saved handles. Transport cleanup likewise captures
the tree before closing stdin, so a wrapper that exits on EOF cannot hide a child that retains inherited stdio.
Initialize and every
`thread/turns/list` page cap their individual timeout to the remaining request budget. If synchronous start,
JSON-RPC write/flush, or stdin close blocks, the supervising thread aborts the owned process to unblock its pipes and
waits no later than the overall inspection deadline. Normal successful inspection does not issue the force path.
Deadline exhaustion fails closed as `UNKNOWN`, without extending the 30-second recovery lease or adding a heartbeat.

## Reconciliation semantics

### Forge already terminal

Provider inspection is skipped. Existing NodeRun state, output, failure, and finish time remain authoritative. The turn and session are reconciled using the existing Phase 5A-compatible terminal semantics, including cancellation and session-corrupting failures. Active event capture becomes `DEGRADED`; no provider event is fabricated. Recovery classification remains `NULL` because no post-crash provider inspection occurred.

### Provider `TERMINAL`

Persist `provider_recovery_state=TERMINAL`, the provider-proven terminal outcome when available, and DB-time `provider_recovery_checked_at`.

Because Phase 5B does not reconstruct output or routing, a still-running NodeRun and turn become `FAILED` with:

- code: `AGENT_EXECUTION_RECOVERY_REQUIRED`;
- message: `The provider turn is terminal, but Forge restarted before execution result/routing was committed.`

Active event capture becomes `DEGRADED`; no synthetic provider completion/failure event is appended.

For `REUSE_WITHIN_WORKFLOW_NODE`, a provider-proven healthy terminal thread returns the session to `IDLE`. For `FRESH_EACH_NODE_RUN`, the session becomes `CLOSED` with terminal outcome `FAILED`.

### Provider `ACTIVE`

Persist `provider_recovery_state=ACTIVE` and the check timestamp. Recovery never issues a new turn.

The Codex audit must determine whether a fresh app-server process can safely interrupt the exact persisted `(threadId, turnId)`. If the contract test and real audit prove this, the existing `turn/interrupt` protocol is reused and the same inspector operation returns evidence that interruption completed safely. Forge then fails the NodeRun and turn with `AGENT_EXECUTION_RECOVERY_INTERRUPTED`; reusable context returns to `IDLE` only when subsequent exact provider evidence proves it safe, and fresh context closes.

If exact cross-process interruption or safe post-interrupt state cannot be proven, recovery performs no mutation and fails Forge ownership closed with `AGENT_EXECUTION_RECOVERY_PROVIDER_ACTIVE`. It does not claim the provider stopped. Reusable context remains `FAILED` and unavailable; fresh context closes as failed.

### Provider `UNKNOWN`

Persist `provider_recovery_state=UNKNOWN` and the check timestamp. The NodeRun and turn become `FAILED` with `AGENT_EXECUTION_RECOVERY_UNKNOWN`; active event capture becomes `DEGRADED`.

Reusable context becomes `FAILED` and cannot be automatically reused. Fresh context becomes `CLOSED` with terminal outcome `FAILED`. Recovery never guesses IDs, resets context, routes output, resumes a thread, or starts a turn.

## Codex recovery protocol audit

The implementation begins with contract and live audit work against installed Codex CLI `0.154.0`. Phase 5A still pins durable execution to the previously audited `0.153.2`; the shared pin may move to `0.154.0` only after the existing durable-session suite and the new fresh-process recovery audit pass for that exact version.

The smallest candidate protocol is:

1. start and initialize a fresh app-server process;
2. inspect metadata with `thread/read` without `includeTurns=true` when needed to validate the exact thread;
3. page `thread/turns/list` to locate the exact persisted turn ID;
4. use `thread/items/list` only if the turn schema does not itself provide a conclusive state and item evidence is required by the audited contract;
5. correlate any thread status only after the exact target turn is located.

The audit treats exact turn state as authoritative when the response schema and live restart behavior agree. Thread existence is never terminal evidence, and thread `idle` is never success evidence. An absent/ambiguous turn, unknown thread, protocol error, timeout, or mismatch is `UNKNOWN`.

No implementation will use `thread/read(includeTurns=true)`. Inspection will not call `thread/start`, `thread/resume`, or `turn/start`.

The audit will record the exact request/response shapes, pagination behavior, terminal status values, unknown-thread/turn behavior, and whether active-turn reproduction and cross-process exact-turn interrupt are reliable. If `0.154.0` cannot prove a classification deterministically, production returns `UNKNOWN`.

## Duplicate-turn and scheduler protection

The orphaned NodeRun is already `RUNNING`; the scheduler only scans `PENDING`. The recovery claim additionally fences the session/turn so only one recovery worker can act. Final reconciliation makes the NodeRun terminal and clears normal ownership without ever changing it back to `PENDING`.

Tests explicitly record every Codex method invoked during `TERMINAL`, `ACTIVE`, `UNKNOWN`, missing-identity, unsupported-version, protocol-error, and stale-worker scenarios. Every scenario asserts zero `turn/start`, `thread/start`, and `thread/resume` requests. Worker integration asserts the claimed orphan is not submitted through normal execution while unrelated pending work can still proceed.

## Testing strategy

Implementation follows red-green-refactor cycles.

### Repository and application tests

- terminal Forge truth skips inspection and preserves NodeRun history;
- provider `TERMINAL`, `ACTIVE`, and `UNKNOWN` persist exact recovery metadata and specified lifecycle outcomes;
- missing provider identity and unsupported provider version fail closed without provider mutation;
- reusable and fresh sessions receive their distinct safe/fail-closed terminal states;
- active capture degrades without fabricated ledger events;
- two workers racing yield one recovery owner and one authoritative commit;
- recovery lease expiry permits reclaim, while the old owner cannot commit;
- the bounded claim processes at most one candidate;
- the scheduler cannot reacquire or submit the orphaned NodeRun;
- existing success, continuation, scoping, re-entry, heartbeat, timeout, Stop/retry, ledger, activity, routing, and workflow completion tests remain green.

### Codex contract tests

Using fresh fake app-server processes, cover:

- exact terminal turn after process restart;
- exact active turn when reproducible;
- unknown thread;
- unknown turn;
- inspection failure and timeout;
- slow multi-page inspection shares one deadline, and timeout plus process cleanup remains inside it;
- blocking process start, JSON-RPC write/flush, and stdin close all return `UNKNOWN` within the total lifecycle
  deadline; a process returned after cancellation is force-terminated;
- process registration versus cancellation is linearizable, force termination is idempotent, and successful
  inspection does not issue an unnecessary force kill;
- a real wrapper plus inherited-stdio child cannot block lifecycle abort or transport close, and neither root nor
  captured child remains alive after the bounded cleanup;
- unsupported version without launching a process;
- identity mismatch and ambiguous response;
- absence of `thread/read(includeTurns=true)`, `thread/start`, `thread/resume`, and `turn/start` during recovery;
- exact `turn/interrupt` only if cross-process safety is proven.

### Gated real Codex acceptance

A gated test starts a durable tracked execution, records its thread and turn IDs, crosses a client/app-server process boundary, and inspects the same IDs from a fresh process. It asserts a classification based on real evidence, zero duplicate turn starts in the recovery process, persisted Forge recovery state through the integrated scenario, and successful execution of an unrelated WorkflowRun afterward. Non-deterministic provider evidence expects `UNKNOWN`.

## Verification and delivery

Run focused application, Postgres, Codex contract, and gated live recovery tests while developing. Before PR creation run:

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify
git diff --check
```

Push the single Phase 5B branch, create one PR against `main`, and require GitHub CI green on the exact final HEAD before reporting readiness for review.

## Out of scope

No manual Resume, Retry, Reset context, automatic restart/resume, provider output or routing reconstruction, new cross-workflow context, steering, fork, compaction, budget policy, SSE/WebSocket, recovery modal, or Nexus business logic is included.
