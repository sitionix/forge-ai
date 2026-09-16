# Phase 6B — explicit shared session groups

## Ownership and identity

`SHARED_SESSION_GROUP` is opt-in. V33 adds nullable `agent_execution_sessions.context_group_key`; CHECK constraints require null source node/agent ownership, nonblank group and a non-null iteration for shared sessions. Other modes still require both owners and no session group. Historical rows are unchanged. Separate partial unique indexes cover GLOBAL and PER_SCOPE current non-reset sessions; provider conversation uniqueness is unchanged.

The existing allocator resolves `(workflowRunId, contextIterationId, contextGroupKey, repositoryId)`, without a member node or agent. Allocation and Reset use the same transaction advisory lock, followed by the existing session row lock. Sequences are allocated under that lock. The existing writer, lease and fencing rules remain authoritative. The migration preserves the node FK for old modes, adds a direct workflow FK for group-owned sessions, and validates repository scope against immutable group snapshots.

## Compatibility and lifetime

`ContextIterationPolicy` rejects mixed shared/nonshared iteration modes and mixed GLOBAL/PER_SCOPE for a group. `WorkflowRunGraph` checks provider/model/effort on immutable RunNode snapshots before execution persistence. Agent identities, instructions, schemas and ports may differ. No template group is automatically converted.

`NodeRunFactory.iteration` handles both iteration-scoped modes with the existing incoming-contribution rules: entering starts a UUID, same group/repository propagates it, leaving/re-entering creates a new UUID, conflicting identities fail closed. Execution frames never define context identity. Phase 6A continues to allocate separate node-owned sessions.

## Per-turn provider contract

The current NodeRun remains the instruction, output-schema and routing authority. Shared Codex resume explicitly supplies current developer instructions; each turn/start supplies its current schema. Existing context modes keep their previous resume parameters. Protocol tests exercise A/B/A with distinct instructions and schemas, one thread/start and two thread/resume calls. Executor tests additionally verify B's routing schema.

## Lifecycle and UI

Reset retires the whole group session under the allocator's shared lock. Replacement allocation keeps the iteration and starts a new provider conversation; subsequent members reuse it. Retry copies group, iteration and consumed inputs. Recovery eligibility checks group ownership explicitly and exact NodeRun/turn association; TERMINAL recovery resumes, explicit reset retries on a replacement, ACTIVE/UNKNOWN remain fail-closed. Stop records the actual active NodeRun and interrupts only its provider turn; historical turns remain unchanged.

Agent and every Nexus DTO/mapper carry nullable sourceNodeId and group/iteration. Nexus is a typed proxy. Builder offers “Shared session within iteration” and explains its boundary. Inspector shows Shared session, Group and Iteration; Started, Current and history name actual agents and shared turn numbers. History remains grouped by sessionId; metadata mismatches cannot enable verified Activity.

## Verification coverage

Tests cover shared nodes/agents and ordered sequences; new iterations; distinct group keys; repository isolation; conflicting modes/scopes/models; pre-persistence rejection; fan-in; concurrent first allocation; single active writer; reset/allocation in both lock orderings; reset replacement/reuse; retry/recovery; Stop; nullable DTO ownership; historical migration and existing three modes.

The critical database scenario uses preparation → A → B → A → B → outside → A → B → A → B. Each four-turn iteration has one session/conversation; the two iterations differ. A/B have deliberately different instructions and schemas. A post-snapshot template edit does not affect the run.

## Reproduce real installed Codex acceptance

```bash
mvn -B -ntp -Dapi.version=1.40 -pl services/forge-agent/boot -am \
  -Dtest=NodeRunFactoryTest -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dit.test=ForgeAgentPortAwareExecutionIT#liveCodexSharedIterationsAndReset' \
  -Dfailsafe.failIfNoSpecifiedTests=false -Dforge.codex.live-shared-e2e=true verify
```

The gate uses PostgreSQL allocation/leases, installed authenticated Codex, and recorded protocol requests. Routing is deterministic in the fixture. It verifies exact current instructions/schema, conversation IDs and start/resume methods. An explicit reset after the first iteration's group work allocates a replacement inside that same iteration and verifies a new thread/start.

## Local verification — 2026-09-16

- Full requested Agent verify: 983 tests, 0 failures/errors, 7 gated skips.
- Full requested Nexus verify: 219 tests, 0 failures/errors.
- Console: 513 tests passed; typecheck and build passed.
- Migration suite: 10 tests passed, including V32 history and V33 shared constraints.
- Independent production review and scoped follow-up: no blocking findings; ambiguous shared Started/Current labels fixed and tested.
- `git diff --check`: clean.

Run installed-Codex gates sequentially with other Agent integration tests: the existing fixture cleanup removes the common project workspace, so concurrent Maven test processes can invalidate a live provider's cwd.

## Installed Codex evidence — 2026-09-16

PASS, CLI `0.154.0`, model `gpt-5.6-sol`, one gated test executed without skips.

| Context | Forge session | Provider conversation |
| --- | --- | --- |
| Iteration A | `062cfe3e-3247-4b3c-bdf0-ebb41d191567` | `01a0a948-8c25-7412-bb27-48fb50854517` |
| Reset replacement in A | `4af56523-0ce1-42d2-aeeb-9c948b0b3773` | `01a0a948-e619-78a0-b544-4865936bf5b1` |
| Iteration B | `0ce97394-2bbf-4f46-8806-9951f661fc22` | `01a0a948-fd0f-7323-9820-06ac2e0a8fa8` |

Iteration A: `c337c57f-34e3-473f-aaa0-86fdfc9b1d0c`. Iteration B: `b54a94a9-8999-46cb-b183-83315defef7a`.

Each iteration used ordered turns 1/2/3/4: start, resume, resume, resume. Exact request assertions verified each resume's conversation, current developer instructions and current output schema. No A conversation was resumed in B. The workflow finished `SUCCEEDED`.

Reset evidence is an additional allocator/provider probe within iteration A: after retirement, the normal allocator created a new sequence-1 session and real Codex thread/start. The probe intentionally delivers no extra workflow edge; ordinary routing remains covered by the eight-turn workflow and lifecycle integration tests.

CI is tracked on the PR against its exact head commit; it is separate from the installed-Codex gate.
