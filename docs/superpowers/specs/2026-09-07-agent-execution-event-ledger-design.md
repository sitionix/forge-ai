# Forge-owned Agent Execution Event Ledger Design

Date: 2026-09-07

Status: approved for implementation

## Goal and boundary

Forge Agent records a provider-neutral, ordered, append-only activity ledger for every newly tracked `AgentExecutionTurn`. The ledger is an additive observability facility. It neither decides when Codex execution is complete nor changes `AgentExecutionResult`, output-port routing, `NodeRun`, or `WorkflowRun` lifecycle semantics.

The implementation starts from Phase 1B at commit `65d06dfc` and preserves its identity ordering, version gate, durable start/resume behavior, single-writer lease, fencing, heartbeat cancellation, completion state machine, command recovery, structured output parsing, and routing.

## Domain model

`AgentExecutionEvent` contains:

- Forge-owned UUID `id`;
- required `agentSessionId`, `agentTurnId`, and `nodeRunId` correlations;
- positive per-turn `sequence` allocated by PostgreSQL;
- provider-neutral `type`;
- nullable provider-neutral `status` and `phase`;
- nullable deterministic `providerEventKey` used only for stable provider identities;
- normalized JSON `payload`;
- `occurredAt`, supplied from supported provider evidence when present and otherwise the Forge observation time;
- Forge `createdAt`.

Supported types are `TURN`, `PLAN`, `REASONING_SUMMARY`, `COMMAND`, `FILE_CHANGE`, `TOOL_CALL`, `AGENT_MESSAGE`, `WARNING`, `ERROR`, `TOKEN_USAGE`, and `CONTEXT_COMPACTION`.

Supported generic statuses are `STARTED`, `IN_PROGRESS`, `COMPLETED`, `SUCCEEDED`, and `FAILED`. Status is nullable for snapshots without a lifecycle concept. `phase` is nullable free technical classification such as `FINAL`, never a Codex method name.

The public and domain payload representation is JSON, but its fields are normalized and whitelisted. Raw notification blobs are not stored.

## Capture lifecycle and legacy truth

`agent_execution_turns.event_capture_status` is nullable for historical Phase 1B rows. The domain/API projects null as `UNAVAILABLE`. New turn allocation writes `NOT_STARTED`. Successful provider turn identity persistence activates capture. Capture remains `ACTIVE` until terminal semantic recording succeeds, then becomes `COMPLETE`.

Any observability-specific write failure attempts a fenced transition to `DEGRADED` and logs the original technical error. `DEGRADED` is sticky: later successful event writes or terminal execution cannot convert it to `COMPLETE`. If marking degraded also fails, Forge logs both failures and leaves business execution unchanged.

Provider conversation and provider turn identity persistence retain their Phase 1B fail-closed behavior. They are not treated as observability failures.

## Database design

Migration `V27__create_agent_execution_event_ledger.sql` adds nullable `event_capture_status` and non-null `next_event_sequence` (default 1) to `agent_execution_turns`, then creates `agent_execution_events`.

The event table has foreign keys that prove the denormalized correlations are consistent:

- `agent_turn_id -> agent_execution_turns(id)`;
- composite `(agent_turn_id, agent_session_id, node_run_id)` references a unique constraint on the same turn columns;
- existing turn/session/node-run foreign keys continue to establish the full ownership chain.

Integrity includes:

- unique `(agent_turn_id, sequence)`;
- partial unique `(agent_turn_id, provider_event_key)` where the key is non-null;
- checks for positive sequence, supported types/statuses, nonblank provider keys, object JSON payload, and bounded serialized payload size;
- an ordered-read index on `(agent_turn_id, sequence)`;
- a trigger rejecting event updates. Cascading deletion is allowed only when the owning turn is deleted through existing lifecycle cleanup.

Sequence allocation and insert occur in one short transaction. A fenced atomic update increments `agent_execution_turns.next_event_sequence` and returns the allocated value. It joins the owning session and requires exact `sessionId`, `leaseOwnerId`, `leaseToken`, an unexpired lease, a matching turn/session/node run, and non-null `provider_turn_id`. This survives worker restarts and serializes concurrent writers without `MAX(sequence)`, timestamps, or JVM counters.

For a deterministic provider key, the repository treats a uniqueness conflict as an idempotent replay and returns the existing event. Non-keyed repeated events always consume independent sequence values and produce independent rows.

## Application boundary

`AgentExecutionEventRepository` is a dedicated domain port for fenced append, capture degradation/completion, and bounded per-turn reads. It does not become part of `AgentExecutionSessionRepository`.

`AgentExecutionEventRecorder` is the application service. It owns correlation from `AgentSessionExecutionClaim`, calls the repository, catches observability failures, marks capture degraded where possible, and never changes or throws over a valid provider result because a normal event could not be stored. A stale lease is rejected by the repository; the existing ownership/heartbeat path remains authoritative for stopping stale execution.

`AgentExecutionEventUseCases` validates `afterSequence >= 0` and applies page-size bounds: default 100 and maximum 200.

## Codex capture flow

The Codex adapter adds a mapper that accepts supported structured notifications and returns zero or one normalized Forge candidates. Unsupported streaming deltas return no candidate. The mapper knows Codex method/item names; no downstream application or Nexus type does.

For tracked execution, the app-server client observes notifications alongside the existing `CodexTurnStateTracker`. Before `turn/start` returns and `providerTurnId` is durably persisted, candidates are buffered with their observation order. After the identity callback succeeds, capture is activated, a semantic `TURN/STARTED` candidate is recorded, and buffered candidates are flushed in original observation order. If identity persistence fails, the buffer is discarded and no event is committed.

The existing state tracker remains the only completion authority. When it resolves the exact turn successfully through either schema `turn/completed` or final agent message plus target thread idle, the client reports one semantic completion callback. The recorder emits `TURN/COMPLETED` with provider key `turn:<turnId>:completed`. Repeated schema/fallback evidence is idempotent and cannot create a duplicate terminal event.

## Provider mapping

| Codex evidence | Forge event and normalized payload |
| --- | --- |
| persisted `turn/start` response / `turn/started` | `TURN`, `STARTED`; provider method and turn ID only in technical metadata |
| `turn/plan/updated` or completed plan item | `PLAN`; explanation and ordered step/status snapshot |
| completed reasoning item | `REASONING_SUMMARY`; provider-visible `summary[]` only |
| command item started | `COMMAND`, `STARTED`; command and cwd |
| command item completed | `COMMAND`, `COMPLETED`, `SUCCEEDED`, or `FAILED`; command, cwd, exit code, duration, bounded aggregate output |
| completed file-change item | `FILE_CHANGE`; paths/operations/summary and status |
| MCP/dynamic supported tool item | `TOOL_CALL`; tool, server/provider, operation, status, safe bounded response summary |
| final completed agent message | `AGENT_MESSAGE`, phase `FINAL`; bounded final text |
| scoped warning/error | `WARNING` / `ERROR`; safe message and whitelisted metadata |
| `thread/tokenUsage/updated` | `TOKEN_USAGE`; total, last, model context window, input, cached, cache-write, output, reasoning-output counts when supplied |
| `contextCompaction` item or legacy `thread/compacted` | `CONTEXT_COMPACTION`; status and safe summary |
| audited success decision | `TURN`, `COMPLETED` |

Stable item lifecycle keys use `item:<itemId>:started` and `item:<itemId>:completed`. Turn lifecycle keys use `turn:<turnId>:started` and `turn:<turnId>:completed`. Plan and token updates without stable protocol identity remain unkeyed, so legitimate repetitions are retained.

Unscoped diagnostics are not attached to the active turn. Malformed scoped notifications continue to follow the audited protocol's explicit validation behavior. A failed command or tool event remains intermediate and never directly fails the turn.

## Reasoning, noise, payload bounds, and safety

Only `reasoning.summary[]` is persisted. Hidden reasoning content, private traces, `content[]`, reasoning text deltas, and token-by-token deltas are excluded.

Command output, tool response summaries, agent messages, and other large text fields are UTF-8 bounded. Truncated payloads include `truncated=true`, `originalBytes`, and `storedBytes`. The full normalized JSON object is also protected by a database byte limit.

A shared sanitizer redacts values under credential-like keys and common bearer token, private-key, and secret environment-assignment patterns. Tool request bodies are not retained; only safe operation identity and a sanitized response summary are eligible. No environment map, approval token, or raw provider notification is persisted.

## Read API and Nexus proxy

Forge Agent exposes:

`GET /api/v1/agent-execution-turns/{turnId}/events?afterSequence=0&limit=100`

The typed response contains `turnId`, `captureStatus`, ascending provider-neutral events, `lastSequence`, `nextAfterSequence`, and `hasMore`. Reads request `limit + 1` internally to derive `hasMore`; no unbounded workflow-wide dump is added. A missing turn returns the existing not-found error contract. A historical turn returns an empty page with `UNAVAILABLE` rather than fabricated events.

Nexus exposes the corresponding infrastructure-agent route through controller -> use case -> typed `ForgeAgentClient` -> HTTP client -> mapper. Nexus duplicates the public provider-neutral DTO shape only as required by its existing service boundary and contains no Codex mapping or interpretation.

## Tests and verification

PostgreSQL tests cover atomic ordering for one and multiple turns, restart-safe counters, correlations/FKs/checks, immutable rows, deterministic-key replay, retained non-keyed repetitions, historical null capture status, and stale fencing after token takeover.

Application tests cover capture activation, append degradation, sticky degraded status, unchanged provider result, and page bounds. Codex fixtures cover every mapping category, ignored deltas, pre-identity buffering, discard on identity failure, completion fallback, schema completion deduplication, failed-command recovery, and unchanged structured result/routing.

REST, typed Nexus client, mapper, controller, and proxy integration tests cover pagination, capture status, nullable legacy data, and provider-neutral boundaries.

An explicitly gated live Codex test performs deterministic command activity and verifies that stored events use the actual Forge turn ID and contain ordered `TURN/STARTED`, real command/tool activity, final `AGENT_MESSAGE`, and one `TURN/COMPLETED`. Token usage is asserted only when emitted by live Codex `0.153.2`.

Final verification runs both requested Maven reactor commands, all existing Phase 1B completion/session/fencing tests, the gated live E2E separately, and `git diff --check`.

## Explicit exclusions

No Phase 3 UI, streaming API, steering, interrupt control, reset, fork, token chart, activity tab, command card, or Nexus event interpretation is introduced. No unrelated cleanup or refactor is included.
