# Phase 5A Safe Operator Interrupt Design

## Scope

Add one WorkflowRun-level `Stop run` command spanning Forge Agent, the existing Nexus agent proxy, and Task Execution. The command cancels every pending or running NodeRun in the selected run, uses the existing Codex execution cancellation action for active provider work, and does not alter normal execution, session allocation, routing, event normalization, or recovery architecture.

Per-node cancellation, steering, retry/resume, new event types, provider reconstruction, and a control-command ledger remain out of scope.

## Forge Agent architecture

`CancelWorkflowRunUseCase` owns orchestration. It locks the exact WorkflowRun and obtains its non-terminal NodeRuns. For every RUNNING tracked execution it must first secure a local provider cancellation handle addressed by `nodeRunId`. If any required handle is unavailable, it raises `AGENT_EXECUTION_INTERRUPT_UNAVAILABLE` before lifecycle persistence and the transaction rolls back unchanged.

The provider-neutral cancellation boundary exposes only an opaque, idempotent action. `CodexAgentExecutor` implements that boundary using its existing `activeExecutions` map and `ExecutionCancellation`; it does not add another map or transport abstraction. Heartbeat lease-loss cancellation delegates to the same underlying object.

Once all required handles are secured, Forge applies authoritative cancellation in the same transaction:

- tracked NodeRuns use `AgentExecutionSessionRepository.cancel(nodeRunId)`, preserving the established turn/session/lease/event-capture semantics;
- pending or historical untracked NodeRuns use the existing NodeRun `CANCELLED` lifecycle shape;
- the WorkflowRun becomes `CANCELLED` and receives `finishedAt`;
- terminal NodeRuns, turns, sessions, and capture states are preserved.

Provider actions run only after the database transaction commits. A rollback never interrupts provider work. An exact Codex identity continues through the existing `turn/interrupt` followed by transport close; a pre-turn execution continues through the existing transport-only close action. The cancellation object provides exactly-once execution across operator and heartbeat requests.

## Concurrency and lifecycle ordering

The WorkflowRun row is the serialization boundary shared with node start/completion. If natural completion commits first, cancel observes `SUCCEEDED` or `FAILED` and returns a typed non-cancellable conflict without rewriting history. If cancellation commits first, lease fencing makes the worker claim stale and the existing terminal-cancellation absorption in `NodeRunLifecycle` and `NodeRunCompletionPersistence` ignores late success/failure. Event persistence already rejects stale claims, and routing sees the terminal run and cannot create downstream or re-entry NodeRuns.

Concurrent duplicate Stop requests serialize on the WorkflowRun lock. The first request performs the lifecycle transition and schedules the provider action; later requests observe `CANCELLED` and succeed idempotently without another interrupt.

## HTTP boundaries

Forge Agent exposes `POST /api/v1/workflow-runs/{runId}/cancel` and returns `204 No Content`. Unknown IDs use existing not-found semantics. `SUCCEEDED` and `FAILED` produce a typed conflict; `CANCELLED` is idempotent success.

Nexus exposes `POST /api/v1/infrastructure/agents/workflow-runs/{runId}/cancel`. Its use case and client adapter forward the Forge ID unchanged and preserve upstream status/body/typed error behavior. Nexus contains no cancellation decisions.

## Console behavior

Task Execution renders the danger-styled `Stop run` control only for `QUEUED` and `RUNNING` runs. The first click opens one inline confirmation: “Stop this run? Active agent execution will be interrupted.” Confirmation sends one request; the button becomes disabled and reads `Stopping…` while in flight.

The browser never fabricates lifecycle state. On success it immediately reloads the selected WorkflowRun and execution contexts, while the normal Activity poll obtains the final capture state. The pinned invocation and existing Activity remain selected and visible. On failure, the run, context, Activity, selection, and polling remain intact; a local cancellation error is shown and backend state is refreshed so a concurrent terminal outcome is displayed truthfully.

## Verification

Tests cover active tracked cancellation, exact Codex interrupt, pre-turn close, missing-handle rollback, duplicate requests, both completion race outcomes, multiple active/pending nodes, late worker/event suppression, Forge and Nexus routes/error propagation, and Console visibility/confirmation/single-flight/success/error behavior. Final verification uses the requested full Forge Agent, Nexus, and Console commands plus `git diff --check`, GitHub CI on the exact pushed HEAD, and a separate gated real Codex acceptance when the required runtime is available.
