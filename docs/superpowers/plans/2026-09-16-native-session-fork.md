# Phase 6C Native Session Fork Implementation Plan

**Goal:** Implement the user-specified fork-and-switch of a current durable reusable context via native Codex thread/fork.
**Architecture:** A fenced FORKING preparation and atomic finalization share allocator scope locks. Provider I/O runs outside transactions. A separate context inspection model permits zero-turn successors. Nexus proxies backend truth and Console applies command results before refreshing.
**Spec:** Phase 6C specification in the implementation request (authoritative).

## Constraints
- Audited Codex 0.154.0 only; exact persisted threadId and lastTurnId; ephemeral=false, excludeTurns=true; no provider retries/history copies/turn starts.
- Preserve reuse identity and completed turns; move only post-prepare queued turns in sequence order.
- Reset and Fork share lock domain; WorkflowRun locks precede session locks to serialize Stop without deadlock.
- Fence every prepared operation using the session's monotonically increasing token (without assigning an execution lease).
- Recover stale FORKING without adopting or retrying provider forks.

## Tasks
- [ ] Provider port, Codex implementation, exact wire tests and gated installed-provider acceptance. Port: AgentContextForkProvider.validateSupport(providerId, providerVersion), fork(providerId, providerVersion, providerConversationId, providerTurnId) returning String.
- [x] Domain lineage, eligibility, migration V34, persistence prepare/finalize/recovery and application command. Add AgentExecutionContext(session, nullable turn, workflowTerminal) read model; retain real AgentExecutionAllocation invariants.
- [x] Agent API and authoritative eligibility; typed Nexus proxy and nullable turn fields.
- [x] Console Fork action, lineage and zero-turn display, single-flight command and stale refresh protection.
- [ ] Migration/concurrency/integration tests; complete requested Maven, Console and installed-provider checks.
- [ ] Review final changes, commit, push feature branch and verify exact HEAD GitHub CI before declaring review readiness.

## Verification
Run focused fork protocol and DB race tests first, then both requested Maven verify commands, Console test/typecheck/build, git diff --check. Record real provider evidence separately from mock tests. No completion claim without exact final HEAD CI.

## Current verification status
Implementation and default local verification pass. Real Forge persistence/resume acceptance passes. Shared developer-instruction live acceptance fails on installed Codex 0.154.0, including a no-fork control. See docs/verification/phase-6c-native-session-fork.md. Do not mark review-ready while this gate fails.
