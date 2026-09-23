# Remote Access Stage 5 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Execute regression-first in the existing isolated branch.

**Goal:** Managed SSH execution and truthful persisted revoke/recovery.
**Architecture:** Java owns session authorization; a narrow root supervisor owns registered systemd units. SSH carries streams. Admission and REVOKING serialize without holding database transactions across OS waits.
**Tech Stack:** Java 21, Spring, PostgreSQL, Python standard library, OpenSSH, Linux/systemd.
**Spec:** `docs/superpowers/specs/2026-09-23-remote-access-stage5-design.md`, subordinate to the human Stage 5 roadmap.

## Global Constraints

- No Stage 6 REST/auth/UI, Stage 8 CLI or workflow routing changes.
- Existing session states, pinned identities and Stage 4 recovery remain intact.
- REVOKED requires positive cleanup evidence; unavailable is never success.
- No arbitrary remote execution outside prepared isolated workloads.
- No automatic command replay or local fallback.
- No DB lock across network/process waits.

## Review Focus

- Start registration versus persisted revoke and supervisor restart.
- Backpressured output and detached descendants must not escape cancellation.
- Session identity versus caller-supplied command fields.
- Lost revoke acknowledgement and credential cleanup crash ordering.
- Systemd inspection failures and unavailable isolation capabilities.

### Task 1: Grantor and accessor lifecycle gates

Files under `services/forge-agent/{domain,application}`: new `RemoteAccessWorkloads`, `RemoteAccessPeerExecution`, `RemoteAccessAccessorExecution`, `RemoteAccessExecutionService`; application tests.
Interfaces: workloads `start(binding, attachment)`, `stop(sessionId)`, `heartbeat(epoch)`; peer `start(binding, attachment)` and `revoke(binding)`; accessor revoke transport returns authenticated session status.

- [x] Write direct tests for revoke CAS/cleanup failure, ACTIVE-only admission, admission-vs-revoke barriers, accessor offline and key deletion failure.
- [x] Run focused Maven tests and record RED before implementation.
- [x] Implement narrow authority gate with reload/CAS and cleanup outside DB transaction; reuse it from pairing expiry/reconciliation.
- [x] Run focused lifecycle tests GREEN, preserving all Stage 4 cases.

### Task 2: Workload systemd boundary

Files: `scripts/remote-access/workload_supervisor.py`, `workload_units.py`, tests `test_workloads.py`; installer/runtime units.
Interfaces: protected admin socket START/STOP/HEARTBEAT and peer attachment socket; one-use attachment ID is bound to authenticated session before START.

- [x] Write Python tests for strict requests, durable registration, one-use attach, fail-closed unit inspection, cancellation and epoch/heartbeat expiry.
- [x] Observe RED; implement registry, prepared context checks, bounded systemd start/stop and root-owned socket identity checks.
- [x] Add installer tests before adding root-owned supervisor service, watchdog and protected runtime directories.
- [x] Verify unit tests; test stdout/stderr/stdin streaming using real processes without calling that systemd E2E.

### Task 3: SSH integration and accessor execution

Files: `forced_command.py`, Agent local remoteaccess adapters, channel server/config and tests.
Interfaces: SSH `exec` has a bounded typed header then raw stdin; stdout/stderr remain separate; `revoke` is fixed control operation. Dedicated execution handle exposes streams, wait and cancellation.

- [x] Add rejected binding/malformed request tests and large-output/nonzero-exit/cancel tests before implementation.
- [x] Implement descriptor attachment before Agent admission, one START, bounded stream transport and no retry.
- [x] Add lifecycle-owned heartbeat/reconciliation only when channel enabled; restart starts closed.
- [x] Run all Python and focused Java suites; preserve pairing fixtures.

### Task 4: Actual privileged evidence and regression

Files: new Stage 5 isolated integration fixture/tests, `docs/remote-access/evidence.md`, stage5 operational documentation and roadmap.

- [x] Real SSH + production Java/Postgres + real systemd: streams, timeout, descendants, independent sessions, start/revoke, authority and supervisor crash/restart, negative isolation canaries.
- [x] Label missing privileged capabilities NOT_RUN/NOT READY; never substitute mocks.
- [x] Run full Agent/Nexus verify, Console tests/typecheck/build, changed script suites and `git diff --check`.
- [x] Fresh whole-branch review and resolve correctness/security findings with regression tests.
- [ ] Publish the separate Stage 5 review unit under the user's existing PR workflow instruction; stop at READY_FOR_REVIEW, never merge Stage 5 or start Stage 6.
