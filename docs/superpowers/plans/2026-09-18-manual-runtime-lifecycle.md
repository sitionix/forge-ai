# Manual Runtime Lifecycle — Phase 2

**Goal:** Persist MANUAL invocations in WAITING_FOR_MANUAL until user action is implemented in Phase 3; keep workflows active and support cancellation/recovery across worker restarts.

**Spec:** Manual Node Roadmap, Phase 2, supplied in the conversation. Separate PR after merged Phase 1; stop for external review after opening this PR.

**Architecture:** The worker dispatches using the persisted node type. A dedicated transactional ManualNodeRunLifecycle locks WorkflowRun then NodeRun, rechecks type/status, and changes PENDING to WAITING_FOR_MANUAL without agent execution, model/context operations, or workspace resolution. Waiting is active for completion and cancellation. Restart reconstructs the worker and scans persisted pending invocations; waiting invocations need no recovery or timeout.

**Scope:** Forge Agent only. No selection API, routing changes, frames/ports changes, Nexus/Console changes or generic executor framework.

## Checklist

- [x] Reproduce missing waiting behavior with real PostgreSQL tests, including Agent → Manual routing.
- [x] Add waiting status and V35 status constraint migration without altering V34/defaults.
- [x] Implement explicit worker dispatch and separate manual lifecycle.
- [x] Include waiting in active-state completion/cancellation, preserving agent execution behavior.
- [x] Verify fresh-worker restart, repeated waiting, cancellation races, no agent sessions/executor/workspace interaction, and mixed workflow routing.
- [x] Run full Forge Agent verify and review the diff.
- Delivery: commit and open a Phase 2 PR; stop for external review before Phase 3.

## Verification

`mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` passed. Five PostgreSQL runtime tests cover waiting, a reconstructed worker reading persisted pending/waiting invocations, mixed Agent → Manual routing, cancellation, and concurrent start/cancel. Manual tests assert no executor/workspace interactions and no agent sessions. The restart test reconstructs the worker; it does not restart the whole application process.
