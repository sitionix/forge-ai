# Phase 5D: explicit context retirement

## Contract

`POST /api/v1/agent-execution-sessions/{sessionId}/reset-context` retires an exact reusable session and returns all its context rows, including historical turns. Each row exposes `contextResetAt`, `resetAllowed`, and `resetReason`. Nexus forwards this typed contract.

Reset requires `REUSE_WITHIN_WORKFLOW_NODE`, `IDLE`, no active NodeRun, no lease owner/expiry, and no `QUEUED`, `STARTING`, or `ACTIVE` turns. Inconsistent terminal/failure fields also fail closed. Conflicts are `AGENT_CONTEXT_RESET_BUSY` or `AGENT_CONTEXT_RESET_NOT_ALLOWED`. A repeated request for an already-retired reusable session succeeds without another write or allocation.

V31 adds nullable `context_reset_at TIMESTAMPTZ` with no historical backfill. The GLOBAL and PER_SCOPE reusable unique indexes retain their scope keys and add `context_reset_at IS NULL`. Provider conversation uniqueness is unchanged. Reset changes only the marker; session status, provider identity, turns, events, and workflow snapshots remain intact.

The initial unlocked lookup obtains immutable scope identity. Both Reset and allocation then acquire the existing transaction advisory scope lock before a session row lock. Reset winning excludes A from the next allocation; allocation winning attaches its queued turn before Reset can inspect pending work and reject it. No replacement is allocated by Reset. The normal allocator creates B lazily, with sequence 1 and no provider conversation; subsequent allocation reuses B with sequence 2.

For terminal provider recovery, safe current reusable context offers Resume. Explicit retirement changes the backend action to Retry while preserving the NodeRun context policy and retry inputs/lineage.

Console applies returned retirement truth before refreshing, retains history and Activity, and treats refresh errors separately from successful Reset. Backend eligibility controls the action. Command guards, selection generations, and Activity identity preserve single-flight behavior and reject stale responses.

## Real installed Codex acceptance — 2026-09-15

Audited installed CLI: `codex-cli 0.154.0`; model: `gpt-5.6-sol`. The gated test uses real Codex processes with a read-only protocol recorder, real PostgreSQL allocation, lease persistence, normal lifecycle completion, and reviewer re-entry. The recovery variant also uses real provider inspection and the Phase 5C Retry use case.

`ForgeAgentPortAwareExecutionIT.liveCodexResetStartsNewConversationAndLaterResumesOnlyNewSession` passed both parameter values with zero failures and no skipped cases.

| Scenario | Session A | Session B |
| --- | --- | --- |
| Completed idle invocation | `86169ace-9682-4a07-ab38-29f36d491721` | `a62697bc-6785-4173-8fc1-3f284a7ae58a` |
| Terminal recovery, Resume → Reset → Retry | `df2c62f5-fd92-4f0e-9c5c-9a1f850d0f8e` | `bc6d6382-b452-4538-b5b9-8c7f06748eea` |

| Scenario | Provider conversation A | Provider conversation B |
| --- | --- | --- |
| Completed idle invocation | `01a0a516-cf19-7ce1-8b35-342d2b3d08de` | `01a0a516-e53f-7d93-adf3-d8713e954863` |
| Terminal recovery | `01a0a517-1446-7090-b4f0-1c8a8d36df2c` | `01a0a517-26a2-7d22-8d0e-ede1a90e985f` |

Both scenarios asserted zero provider requests during Reset; B sequence 1 with `thread/start`; B sequence 2 with `thread/resume` of B's conversation; no resume of A; unchanged historical NodeRun/Turn/Activity; and final WorkflowRun `SUCCEEDED`.

Reproduce the gated acceptance:

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am \
  -Dtest=ResetAgentExecutionContextUseCaseTest -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=ForgeAgentPortAwareExecutionIT#liveCodexResetStartsNewConversationAndLaterResumesOnlyNewSession' \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dforge.codex.live-reset-e2e=true verify
```

The gate requires Docker, authenticated installed audited Codex, and provider connectivity. Ordinary verification leaves live-provider gates disabled.

## Local verification

- `mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify`: PASS; 928 tests, 0 failures/errors, 5 live-provider gate skips.
- `mvn -B -ntp -pl services/forge-nexus/boot -am verify`: PASS; 216 tests.
- Console `npm test -- --run`: PASS; 508 tests across 17 files.
- Console `npm run typecheck` and `npm run build`: PASS.
- `git diff --check`: PASS.
- Separate gated Codex acceptance above: PASS; 2 cases, no skips.

Database coverage includes both blocked Reset/allocation orderings, pending work rejection, active/failed/closed/Fresh rejection, idempotency, unchanged history, recovery action switching, GLOBAL/PER_SCOPE index migration, and per-repository allocation isolation. Console coverage includes command/refresh failures, historical selection, recovery action refresh, double-click, Stop coordination, deferred Activity, and stale run/poll responses.
