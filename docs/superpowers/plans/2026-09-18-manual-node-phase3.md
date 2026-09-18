# Manual Node phase 3: selection API and existing routing

Scope: one PR after merged phases 1–2; stop for external review before builder work.

1. Forge Agent: accept an output port UUID for a waiting manual invocation. Lock workflow then invocation; validate immutable runtime port ownership/direction. Persist SUCCEEDED, selected port, audit JSON and finished time in a separate transaction before invoking existing completion processing.
2. Same selection is idempotent, including after workflow completion; a different selection conflicts. Existing routingCompletedAt locking prevents duplicate activation. The completion worker recovers persisted decisions after a restart.
3. Nexus: thin proxy with typed errors and full manual node/status representation. Console: API method only.
4. Verify real PostgreSQL routing, concurrent requests, immutable snapshots, invalid states/ports, recovery and Manual Task Output; full Agent and Nexus verify; Console tests/typecheck/build.

No schema change, executor registry, special action names or new routing engine. No builder/execution UI changes in this phase.

## HTTP contract

`POST /api/v1/workflow-runs/{workflowRunId}/node-runs/{nodeRunId}/manual-selection`
accepts `{"outputPortId":"<uuid>"}` and returns the updated `WorkflowRunResponse` (200).
Nexus exposes the same suffix under its existing `/agents` proxy route.

- 400: invalid/missing UUID, or `INVALID_MANUAL_OUTPUT_PORT` for a port outside this node's snapshotted outputs.
- 404: workflow/invocation missing or invocation belongs to another workflow run.
- 409: `MANUAL_SELECTION_NOT_ALLOWED`, `MANUAL_SELECTION_NOT_WAITING`, `MANUAL_SELECTION_CONFLICT`, or `WORKFLOW_RUN_NOT_ACTIVE`.
- Repeating a committed selection returns the current workflow, including after workflow completion or cancellation; it never reopens a stopped workflow.

The audit output stores `selectedOutputPortId` and `selectedOutputName`, both from the immutable runtime snapshot. Output labels carry no backend semantics.

## Integration coverage

PostgreSQL tests exercise selected connection delivery and alternative closure, one downstream invocation under repeated/concurrent requests, invalid type/state/port/ownership/body, snapshot immunity to template edits, Task Output completion, completion-worker recovery after decision commit, and cancellation before routing. Existing agent execution/routing tests remain in full verification.
