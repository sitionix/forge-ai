# Phase 6C native session fork

## Runtime contract

Fork is an explicit context command, not a Workflow Builder policy or a new context mode. It supports WorkflowNode, WorkflowIteration and SharedSessionGroup reuse. Fresh contexts cannot be forked. The original provider history is never reconstructed or copied into Forge.

`POST /api/v1/agent-execution-sessions/{sessionId}/fork-context` takes no body. The Agent owns eligibility and the state machine; Nexus forwards the typed command and response. The response includes parent history and the current successor, including a successor with no turns.

## Persistence and eligibility

V34 adds `context_forked_at`, `forked_from_session_id`, and `forked_from_turn_id`. Parent and exact source turn are foreign keys; a composite foreign key ensures that the turn belongs to the parent. Self-parenting is rejected. Lineage follows existing deletion cascades. Reset retirement remains separate. All six current-session unique indexes exclude both reset and fork retirement, while provider conversation uniqueness is unchanged.

A source must be reusable, current, IDLE, without execution ownership, leases, failures, terminal outcome or closure, in a nonterminal WorkflowRun. No QUEUED/STARTING/ACTIVE turn may exist at preparation. Provider conversation/version must exist, and the latest Forge turn must be SUCCEEDED with a nonblank provider turn ID. Older successful turns are never selected as substitutes. Persisted provider support and installed audited version are validated before preparation mutates state.

## Transactions and races

Prepare locks WorkflowRun, the existing allocator scope and the source session, reads/locks the latest turn, increments the fencing token, and changes IDLE to FORKING. FORKING has no execution lease or active NodeRun. No child or synthetic turn is created.

Provider I/O runs outside database transactions and is never automatically retried. Successful finalization reacquires the same locks, validates the exact prepared identity/token and WorkflowRun truth, marks the parent fork-retired and IDLE, inserts an IDLE child, and moves only post-prepare QUEUED turns in original sequence order to child sequences 1..N. Completed parent turns remain unchanged. Normal acquire accepts only WAITING/IDLE current sessions, so FORKING never executes queued turns.

If allocation wins, preparation is BUSY before any provider fork. If preparation wins, allocation may queue on the source and finalization transfers that work atomically. Provider failure restores the authoritative source and keeps its queued work. Stop or newer ownership prevents child installation with FORK_CONFLICT; a returned but uncommitted provider thread is never authoritative.

Reset uses the same allocator lock. Reset-first rejects Fork; FORKING rejects Reset as BUSY. A fork-retired parent cannot be reset as current; its child has normal Reset semantics. Retry eligibility also distinguishes a fork-retired source from a current resumable context.

Worker recovery processes at most one stale FORKING candidate per poll after five minutes. It never repeats fork or discovers/adopts child threads. An eligible source returns to IDLE with a new fencing token, retaining queued turns; terminal workflow truth closes it. Late success/failure from an older preparation cannot commit or abort a newer operation.

## Provider protocol and continuation

Codex CLI 0.154.0 receives exactly one request:

```json
{"method":"thread/fork","params":{"threadId":"persisted source thread","lastTurnId":"persisted latest successful turn","ephemeral":false,"excludeTurns":true}}
```

The returned `thread.id` must be textual, nonblank and different from the parent. Fork sends no thread/start, thread/resume or turn/start. A dedicated short-lived app-server transport releases the fork child's provider writer before returning; this lifecycle requirement was detected by the installed-provider acceptance test.

The child retains WorkflowRun/node/repository/iteration/group identity. Shared children keep null node/agent owners. A child's first real allocation starts at Forge sequence 1 and resumes its already-persisted provider thread; its second allocation is sequence 2 and resumes the same child again. Shared turns continue to carry the current NodeRun developer instructions and output schema.

## Inspection and Console

`AgentExecutionContext` is an inspection model with a nullable turn; `AgentExecutionAllocation` remains a real session plus real execution turn. Context reads use a left join so zero-turn children are visible. Only read DTO invocation fields are nullable.

The Context card exposes Fork only from backend `forkAllowed`, renders lineage and parent history, and labels a zero-turn child as waiting for its next invocation. Reset/Fork/Retry/Stop share single-flight behavior. POST truth is applied immediately, refresh failures are warnings, and stale responses must not undo committed fork truth or newer execution progress. Provider IDs remain in Technical details.

## Verification record

Verification commands and exact outcomes are recorded in the implementation report. Installed-provider acceptance is gated separately from the default suite; mock protocol tests are not presented as live provider evidence.

### Installed Codex / Forge evidence (2026-09-16)

The separately gated `ForgeAgentPortAwareExecutionIT#liveCodexForkPersistsSuccessorAndNextTwoNodeRunsResumeOnlyChild` passed against installed Codex CLI 0.154.0 and PostgreSQL. It executed two real source NodeRuns, persisted their provider identities, invoked the application Fork command, then executed two real child NodeRuns.

| Identity | Recorded value |
|---|---|
| Forge source session A | `076e29a4-0dc9-4293-8a3b-e1c864a31954` |
| Forge child session B | `03e508f5-a641-4b3b-83ef-e817148c61e1` |
| Provider source TA | `01a0aa4f-c37d-7fe1-8ef0-17d8c8fc8ebb` |
| Provider child TB | `01a0aa4f-ffd0-7f13-941e-98dd3444a7cc` |
| Exact Forge source Turn 2 | `2a6bacfd-14cd-48e1-912b-455c308a7bcb` |
| Exact provider `lastTurnId` | `01a0aa4f-e498-7410-ab08-f354712f2d5f` |

Assertions passed: source fork retirement, exact child parent/turn lineage, zero-turn child before allocation, zero turn/start during Fork, child sequences 1 and 2 both thread/resume TB, no later resume of TA, unchanged historical source turn/provider identity. Log: `/tmp/phase6c-forge-live.log`.

### Shared instruction acceptance blocker

An independent installed-provider test sends a directive constant exclusively in the next node's `developerInstructions`; the constant is absent from the user input and output schema. Codex 0.154.0 failed both ordinary shared resume (without Fork) and shared resume after Fork. The source directive remained effective in the ordinary control. The new output schema was honored, which is why an enum-only assertion would incorrectly hide the instruction failure.

Read-only inspection of the ordinary control's native rollout found the old developer directive and no occurrence of the new directive. The exact resume wire includes the requested new developer instructions. This is not accepted as passing shared behavior. The separately gated tests remain strict; Phase 6C must not be declared ready for review unless this native instruction-override requirement is resolved.

### Local verification

- `mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am verify`: PASS; boot integration total 299, zero failures/errors, seven gated skips. `/tmp/phase6c-agent-verify.log`.
- `mvn -B -ntp -pl services/forge-nexus/boot -am verify`: PASS. `/tmp/nexus-fork-verify.log`.
- Console `npm test -- --run`: PASS, 520 tests. `npm run typecheck` and `npm run build`: PASS.
- Focused migration/fork/concurrency suites: PASS, 39 tests including one gated skip. `/tmp/phase6c-fork-it.log`.
- Exact fork protocol tests and existing durable Start/Resume tests: PASS in Agent verification.
- Gated real Forge persistence/continuation acceptance: PASS, one live test, no skips.
- Gated real shared instruction acceptance: FAIL, two tests, including the ordinary-resume control; `/tmp/fork-provider-sentinel.log`.
- `git diff --check`: PASS.

To reproduce the shared blocker separately:

```bash
mvn -B -ntp -pl services/forge-agent/infrastructure/codex -am test \
  -Dtest=CodexContextForkE2ETest -Dforge.codex.live-fork-e2e=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

To reproduce real Forge lineage/continuation:

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am \
  -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=ForgeAgentPortAwareExecutionIT#liveCodexForkPersistsSuccessorAndNextTwoNodeRunsResumeOnlyChild' \
  -Dforge.codex.live-fork-e2e=true -Dfailsafe.failIfNoSpecifiedTests=false verify
```
