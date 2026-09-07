# Agent Execution Sessions Phase 1B Design

Date: 2026-09-04

Status: implementation design for review

Sources of truth:

- `docs/agent-session-ux-and-architecture-design.md`
- `docs/codex-durable-session-protocol-audit.md`

This document turns the approved Phase 1A contract into implementation boundaries. It does not rename states, relax constraints, or add Phase 2 scope.

## Outcome and scope

Every Phase 1B agent `NodeRun` owns exactly one Forge `AgentExecutionTurn`. The turn belongs to a Forge `AgentExecutionSession`. Fresh node runs each receive a distinct one-shot session; continued node runs deterministically reuse a session only inside the same `(workflowRunId, sourceNodeId, repositoryId-or-null)` scope. Provider identities remain runtime metadata and never enter reusable workflow definitions.

Included work is policy propagation, schema and persistence, provider capability validation, session allocation, lease/fencing ownership, crash recovery, Codex durable start/resume/completion, runtime APIs, Builder controls, Task Execution context/history/technical details, migration compatibility, and the required tests. The Phase 2 event ledger, Activity UI, steering, reset/fork, shared contexts, and cross-WorkflowRun memory remain excluded.

## Domain and persistence boundaries

`NodeContextMode` is a required domain enum with `FRESH_EACH_NODE_RUN` and `REUSE_WITHIN_WORKFLOW_NODE`. It is copied immutably through `Node`, `RunNode`, and `NodeRun`. Request parsing rejects unknown non-null strings; only absent or null legacy workflow payloads normalize to Fresh. New `NodeRun` records write `contextTrackingVersion=1`; migrated historical records retain null so API/UI code cannot mistake a compatibility backfill for observed execution history.

PostgreSQL adds `context_mode` to `workflow_nodes`, `workflow_run_nodes`, and `node_runs`, plus `context_tracking_version` on `node_runs`. New `agent_execution_sessions` and `agent_execution_turns` tables use the exact Phase 1A columns, checks, foreign keys, partial indexes, and null-safe uniqueness rules. A fresh-session constraint trigger enforces sequence 1 and at most one turn. Continued allocation serializes the deterministic scope row, with a unique-index retry for first-writer creation races. Session/turn allocation and NodeRun creation are one short transaction.

Domain ports separate read models from guarded mutations:

- allocation resolves or creates the exact session and appends one queued turn;
- claim atomically acquires an available session, increments its fencing token, promotes the lowest queued turn, and moves its NodeRun to running;
- renewal compares session ID, owner, token, and database-time expiry;
- provider identity writes and terminal writes require the same current, unexpired fence;
- recovery takes over only expired active ownership and applies the approved conservative outcome.

All database expiry decisions use PostgreSQL time. Lease duration is 30 seconds and renewal cadence is 10 seconds. No transaction remains open during a provider call.

## Snapshot and provider capability validation

Workflow definitions stay provider-neutral. `WorkflowRunSnapshotBuilder` resolves each snapshotted agent and its execution model, then asks an application port whether that provider supports `DURABLE_CONTEXT`. Validation processes the complete proposed graph before persistence or scheduling. A continued node on an unsupported provider throws the existing typed validation shape with code `AGENT_CONTEXT_MODE_UNSUPPORTED`; no runtime graph, root NodeRun, session, turn, or provider operation is created.

The Codex adapter advertises durable context support for the pinned audited contract. Capability ownership remains in provider infrastructure and the application port, never in Forge Console or Nexus.

## Scheduling, ownership, and recovery

NodeRun creation always allocates its session/turn while leaving the NodeRun pending. Polling claims only the lowest queued turn for a session. A reusable session with a valid active owner leaves later NodeRuns pending; busy is scheduling state rather than a failed provider attempt.

The claim transaction locks the session and turn, verifies workflow eligibility, resolves the canonical NodeRun input envelope and workspace, sets owner and token, sets the turn to `STARTING`, sets the session to `CREATING` or `RESUMING`, and sets the NodeRun to `RUNNING`. The resulting claim carries the Forge session ID, turn ID, owner ID, fencing token, context mode, sequence, and optional persisted provider conversation ID in addition to the existing workflow input and routing data.

A process-stable random owner ID identifies each worker process. A heartbeat renews ownership every 10 seconds while setup or execution is nonterminal. Loss of renewal stops further provider-side mutation/interrupt and causes local callbacks to enter only the stale-result rejection path.

Every provider-result mutation is one short transaction guarded by `(sessionId, leaseOwnerId, leaseToken, leaseExpiresAt > database_now())`. Stale writes update nothing and surface `STALE_AGENT_SESSION_LEASE`. Completion atomically writes turn and NodeRun outcome, clears active ownership, and transitions continued sessions to `IDLE` or fresh sessions to `CLOSED`. Session-corrupting failures make a continued session `FAILED`.

On restart, recovery does not clear leases. Once an active lease expires, a worker increments the token and examines the same active turn. A durably terminal NodeRun is reconciled and released; an uncertain nonterminal operation is failed without any provider start/resume/turn-start. Waiting turns do not bypass a failed continued session.

## Provider orchestration and persistence order

The application executes an owned claim through an explicit session-capable provider interface. Fresh and first continued invocations start conversations; only continued sessions request `ephemeral=false`. Existing continued sessions resume the exact stored conversation. There is no resume-to-start fallback.

The order is mandatory:

1. Start or resume a newly initialized app-server process.
2. Validate a nonblank returned thread ID and, for resume, exact equality with the requested opaque ID.
3. Persist the provider conversation ID under the current fence.
4. Issue `turn/start` with the canonical current NodeRun input envelope.
5. Validate and persist the opaque provider turn ID under the current fence.
6. Only then consume turn-scoped notifications, process output, or issue interrupt.

Persistence failure halts provider progress and records `AGENT_CONTEXT_PERSISTENCE_FAILED` when the fence still belongs to the worker. Start, resume, and identity failures use the approved typed codes. Resume failure text states: `Could not continue the existing context. No fresh context was started.`

## Codex completion state machine

Completion is correlated to the exact expected `(threadId, turnId)`. Notifications missing required identities, referencing another target, regressing from terminal state, or producing contradictory terminal outcomes fail explicitly.

The tracker records the authoritative completed `agentMessage` item for the target turn but does not complete on that event. Each later completed agent message replaces the prior candidate, allowing intermediate agent messages. Successful terminal completion requires both:

- a completed agent-message candidate for the expected thread and turn; and
- terminal confirmation for that same target, supplied by either compatible `turn/completed` success or `thread/status/changed` to `idle` after the target turn has started.

For audited Codex `0.153.2`, final `item/completed`, optional target `thread/tokenUsage/updated`, and target-thread idle prove the turn is no longer active; the last completed agent message becomes final output only at idle. If `turn/completed` arrives, its exact IDs and status are validated and it may provide terminal confirmation without waiting for a duplicate idle event. Failure from `turn/completed`, target-scoped provider error, failed completed item, or malformed lifecycle terminates as failure. Idle before target `turn/started`, or idle without a final agent output, is a protocol failure rather than success. Arbitrary sleeps are not part of completion detection; existing policy timeout remains only a safety bound and triggers exact-pair interrupt after provider turn identity has been persisted.

Focused transcript tests cover the observed `0.153.2` sequence without `turn/completed`, the compatible path with it, intermediate agent messages, wrong IDs, premature idle, provider failure, malformed identities, and contradictory terminal signals.

## API and Console

Forge Agent responses add snapshotted `contextMode` and verified read-only session/turn data needed by the approved UI. Nexus remains a thin typed proxy and performs no session derivation. Session membership and ordering come only from Forge session/turn records, never timestamps or provider IDs.

Builder adds the exact native `<fieldset>` radio group and copy from Phase 1A. Draft cloning, new-node defaults, editor save, workflow serialization, unknown-value rejection, disabled state, focus-visible styling, row labels, described-by associations, and native arrow/Space keyboard behavior are covered. Only continued nodes receive the `↻ Context` badge, and computed node height/edge bounds include its row.

Modern Task Execution cards use `RunNode.contextMode`; legacy graphs show no badge. Node details keep the Invocation selector and add Context, repository-local verified history, and a collapsed Technical details disclosure. Continued turns are connected by session and sequence; fresh invocations are unconnected. More than five continued turns use the approved compact window and inline expansion. PER_SCOPE filtering requires both source node and repository. Historical null tracking versions render `Unavailable` messaging and never claim Fresh/New/Continued lifecycle truth.

## Test and delivery strategy

Implementation proceeds in vertical TDD slices: policy/snapshot/migration; session allocation constraints; guarded claim/renew/release/recovery; provider capabilities; Codex protocol and completion; worker integration; API/Nexus mapping; Builder; runtime UX; PostgreSQL integration; and E2E.

The deterministic session E2E uses the production orchestration with a contract provider double to prove feedback-loop routing, same/different Forge identities, ordered provider calls, exact resume identity, and PER_SCOPE separation. A separately tagged real Codex capability E2E launches installed `codex app-server` processes, supplies a unique secret only to Implementer turn 1, exits that process, resumes the persisted thread in Implementer turn 2, and asserts recall plus stored identity evidence. It fails explicitly when enabled without compatible Codex `0.153.2` credentials/runtime; it is not silently replaced by a mock.

Final verification runs the full Maven reactor, Forge Console tests, migration/PostgreSQL integration tests, Codex transcript tests, deterministic session E2E, enabled real Codex E2E in the configured environment, and `git diff --check`. The final diff is reviewed against every Phase 1A section and Phase 0 ordering/completion constraint.

## Explicit invariants

- Context never crosses a source node, repository scope, or WorkflowRun.
- Agent definition ID and display name are never session identity keys.
- Persistent history never replaces the current explicit NodeRun input envelope.
- Only one provider-writing operation owns a reusable session.
- A stale token cannot mutate session, turn, NodeRun, output, failure, or future event data.
- Failed resume never starts a new conversation.
- Provider identities are opaque and are never inferred from latest history.
- Historical compatibility data is not presented as verified runtime truth.
- Session metadata does not alter routing, output-port selection, graph topology, or projection.
