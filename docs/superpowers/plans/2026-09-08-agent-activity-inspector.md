# Live Agent Activity Inspector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the live, provider-neutral Forge event ledger for the exact selected Task Execution invocation without changing agent execution behavior.

**Architecture:** A focused `agent-execution-activity.js` presentation module converts Forge events and capture states into safe Activity markup and latest-usage summaries. `TaskExecutionView` remains the orchestration boundary for selected NodeRun/turn identity, sequential cursor loading, independent polling, stale-response rejection, retry, and follow-latest scrolling.

**Tech Stack:** Browser ES modules, JSDOM, Vitest, TypeScript declaration checking, Vite, existing Forge Console CSS.

**Spec:** `docs/superpowers/specs/2026-09-08-agent-activity-inspector-design.md`

## Global Constraints

- Base all work on `origin/main` commit `74674b079c5d71738661f44f502c03d885e5ed7b`, which contains merged PR #121.
- Console reads only `GET /api/v1/infrastructure/agents/agent-execution-turns/{turnId}/events`; do not change Forge Agent or Nexus Phase 2 code.
- Activity is read-only and must not alter `AgentExecutor`, NodeRun lifecycle, routing, output-port selection, session allocation, Fresh/Continued behavior, leases, recovery, cancellation, Context semantics, Prompt, Output, or Failure.
- Reuse `activeJobPollIntervalMs`; add no streaming transport, control action, provider protocol interpretation, hidden reasoning, cost, chart, or combined timeline.
- Apply event pages sequentially with the exact returned cursor, deduplicate by event ID and sequence, and render by `sequence ASC`, never timestamp.
- Dynamic HTML is escaped; command output preserves whitespace; tool request bodies are never expected or displayed.

---

### Task 1: Console event API client

**Files:**
- Modify: `services/forge-console/src/operator/agent-projects-api.js:190-202`
- Modify: `services/forge-console/src/operator/agent-projects-api.d.ts:1`
- Modify: `services/forge-console/tests/agent-projects-page.test.ts:5712`

**Interfaces:**
- Consumes: existing `http.get(path)` and Nexus `/agents` base used by `createAgentProjectsApi`.
- Produces: `getAgentExecutionEvents(turnId, afterSequence = 0, limit = 200): Promise<AgentExecutionEventPage>`.

- [ ] **Step 1: Add an exact failing URL/cursor assertion**

Extend the existing `Console API calls Nexus infrastructure routes only` test:

```ts
client.getAgentExecutionEvents('turn/T value', 17, 200);
expect(http.get).toHaveBeenCalledWith(
  '/agents/agent-execution-turns/turn%2FT%20value/events?afterSequence=17&limit=200'
);
```

- [ ] **Step 2: Verify the client test fails for the missing method**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Console API calls Nexus infrastructure routes only"`

Expected: FAIL because `client.getAgentExecutionEvents` is not a function.

- [ ] **Step 3: Implement exact query forwarding and the public declaration**

Add beside `getAgentExecutionContexts`:

```js
getAgentExecutionEvents(turnId, afterSequence = 0, limit = 200) {
  const query = new URLSearchParams({
    afterSequence: String(afterSequence),
    limit: String(limit),
  });
  return http.get(
    `${root}/agent-execution-turns/${encodeURIComponent(turnId)}/events?${query.toString()}`,
  );
},
```

Keep the declaration compatible with the repository's current JS-module typing while documenting the new method in `agent-projects-api.d.ts` through exported page/event interfaces used by Task 2.

- [ ] **Step 4: Verify the focused API test passes**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Console API calls Nexus infrastructure routes only"`

Expected: PASS with the exact encoded turn and cursor URL.

- [ ] **Step 5: Commit the client boundary**

```bash
git add services/forge-console/src/operator/agent-projects-api.js services/forge-console/src/operator/agent-projects-api.d.ts services/forge-console/tests/agent-projects-page.test.ts
git commit -m "feat(console): read agent execution events"
```

### Task 2: Provider-neutral Activity presentation module

**Files:**
- Create: `services/forge-console/src/operator/agent-execution-activity.js`
- Create: `services/forge-console/src/operator/agent-execution-activity.d.ts`
- Create: `services/forge-console/tests/agent-execution-activity.test.ts`
- Modify: `services/forge-console/tests/jsdom.d.ts:40`

**Interfaces:**
- Consumes: normalized Forge event objects containing `sequence`, `type`, `status`, `phase`, `payload`, and `occurredAt`.
- Produces: `captureStatusPresentation(status)`, `latestTokenUsage(events)`, `renderAgentExecutionActivityEvent(event)`, and `renderAgentExecutionActivityEvents(events)`.

- [ ] **Step 1: Write failing capture-state and latest-usage tests**

Create tests that assert the exact mappings and omission of absent usage values:

```ts
expect(captureStatusPresentation('NOT_STARTED')).toEqual({ label: 'Waiting', tone: 'waiting' });
expect(captureStatusPresentation('ACTIVE')).toEqual({ label: 'Live', tone: 'live' });
expect(captureStatusPresentation('COMPLETE')).toEqual({ label: 'Complete', tone: 'complete' });
expect(captureStatusPresentation('DEGRADED')).toEqual({ label: 'Incomplete', tone: 'incomplete' });
expect(captureStatusPresentation('UNAVAILABLE')).toEqual({ label: 'Unavailable', tone: 'unavailable' });

const usage = latestTokenUsage([
  event(1, 'TOKEN_USAGE', { total: { input: 12000, cached: 8100 } }),
  event(2, 'TOKEN_USAGE', { total: { input: 12400, output: 2300 }, modelContextWindow: 200000 }),
]);
expect(usage).toEqual(['Input 12.4k', 'Output 2.3k', 'Context 200k']);
```

- [ ] **Step 2: Verify presentation imports fail before implementation**

Run: `npm test -- --run tests/agent-execution-activity.test.ts`

Expected: FAIL because `agent-execution-activity.js` does not exist.

- [ ] **Step 3: Implement capture presentation, safe primitives, timestamps, numbers, and latest usage**

Implement local helpers for HTML escaping, safe structured formatting, human-readable token counts, milliseconds, timestamps, and status normalization. `latestTokenUsage` must scan by sequence and use only the last TOKEN_USAGE payload; it must not synthesize zeros or cost.

- [ ] **Step 4: Add failing table-driven tests for every Forge event type**

Use one table containing:

```ts
[
  event(1, 'TURN', {}, { status: 'STARTED' }),
  event(2, 'PLAN', { explanation: 'Smallest safe change', steps: [{ step: 'Inspect', status: 'completed' }, { step: 'Patch', status: 'inProgress' }, { step: 'Test', status: 'pending' }] }),
  event(3, 'REASONING_SUMMARY', { summary: ['Visible summary', { decision: 'additive' }] }),
  event(4, 'COMMAND', { command: 'printf "a\\n b"', cwd: '/workspace', output: 'a\\n b', exitCode: 1, durationMs: 12700, truncated: true, originalBytes: 100, storedBytes: 20 }, { status: 'FAILED' }),
  event(5, 'FILE_CHANGE', { changes: [{ path: 'src/App.java', operation: 'UPDATE', summary: 'Mapper' }], paths: ['src/Test.java'] }),
  event(6, 'TOOL_CALL', { toolKind: 'MCP', tool: 'search', server: 'drive', operation: 'files.search', providerStatus: 'completed', responseSummary: { result: 'found' }, requestBody: 'must-not-render' }, { status: 'SUCCEEDED' }),
  event(7, 'AGENT_MESSAGE', { text: 'Implementation complete.' }, { phase: 'FINAL' }),
  event(8, 'WARNING', { message: 'Context was compacted.' }),
  event(9, 'ERROR', { message: 'Provider returned an execution error.' }),
  event(10, 'TOKEN_USAGE', { total: { input: 2 } }),
  event(11, 'CONTEXT_COMPACTION', { status: 'completed' }),
  event(12, 'FUTURE_EVENT', { value: '<safe>' }),
]
```

Assert titles/content, plan glyph and textual status fallback, command `FAILED` without invocation-failure text, collapsed Output, visible `Output truncated`, file operations/paths, collapsed tool response, absence of `requestBody` and its value, final-message styling, absence of TOKEN_USAGE from the primary list, context compaction, and unknown fallback.

- [ ] **Step 5: Add explicit escaping and reasoning-boundary assertions**

```ts
expect(html).not.toContain('<script>');
expect(html).toContain('&lt;script&gt;');
expect(html).not.toContain('Chain of thought');
expect(html).not.toContain('Hidden reasoning');
expect(commandHtml).toContain('<pre');
expect(commandHtml).toContain('a\n b');
```

- [ ] **Step 6: Verify event rendering tests fail for unimplemented renderers**

Run: `npm test -- --run tests/agent-execution-activity.test.ts`

Expected: FAIL at the first missing event renderer assertion.

- [ ] **Step 7: Implement compact semantic markup for every event and unknown fallback**

Return one `<li class="agent-activity-event ...">` per primary event. Use `<details><summary>` for command Output and tool response summaries. PLAN status glyphs are `✓` completed, `•` in-progress/running, and `○` pending; unknown statuses include escaped textual status. TOKEN_USAGE returns an empty primary row and is represented only through `latestTokenUsage`.

- [ ] **Step 8: Verify all presentation tests and typecheck pass**

Run: `npm test -- --run tests/agent-execution-activity.test.ts && npm run typecheck`

Expected: all Activity presentation tests PASS and TypeScript reports no errors.

- [ ] **Step 9: Commit the presentation boundary**

```bash
git add services/forge-console/src/operator/agent-execution-activity.js services/forge-console/src/operator/agent-execution-activity.d.ts services/forge-console/tests/agent-execution-activity.test.ts services/forge-console/tests/jsdom.d.ts
git commit -m "feat(console): render agent activity events"
```

### Task 3: Selection-linked incremental Activity state

**Files:**
- Modify: `services/forge-console/src/operator/task-execution-view.js:653-910,1251-1540,1990-2042`
- Modify: `services/forge-console/src/operator/task-execution-view.d.ts:1-62`
- Modify: `services/forge-console/tests/agent-projects-page.test.ts:2643-2740`

**Interfaces:**
- Consumes: selected NodeRun, `contextForNodeRun(nodeRun.id)?.turnId`, `api.getAgentExecutionEvents(turnId, cursor, 200)`, and Task 2 render helpers.
- Produces: independent Activity state and methods `syncSelectedActivity()`, `loadActivityPage(identity)`, `pollActivity()`, `retryActivity()`, `invalidateActivity()`, and `isCurrentActivity(identity)`.

- [ ] **Step 1: Add failing legacy and invocation-navigation integration tests**

For a tracked two-invocation context, select `impl-2`, assert the first call uses `turn-b, 0, 200`, click existing `[data-context-node-run="impl-1"]`, and assert Activity resets and calls `turn-a, 0, 200`. Resolve both pages and assert only Turn A content remains. For a NodeRun without `contextTrackingVersion`/verified context, assert no event API call and exact unavailable copy.

- [ ] **Step 2: Run the focused integration tests and verify failure**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity"`

Expected: FAIL because Task Execution neither calls the events API nor renders Activity.

- [ ] **Step 3: Add Activity state, identity invalidation, initial loading, and section placement**

Initialize state with:

```js
activityTurnId: null,
activityEvents: [],
activityCursor: 0,
activityCaptureStatus: null,
activityLoading: false,
activityError: '',
activityPollInFlight: null,
activityNewEventCount: 0,
activityFollowLatest: true,
```

Add instance fields `activityLoadSequence` and `activityPollTimer`. In `renderNodeDetails`, insert `renderActivity(nodeRun, context)` immediately after `renderContextDetails`. Selection methods call `syncSelectedActivity()` only after the existing NodeRun selection has been updated; they do not implement a new selection policy.

- [ ] **Step 4: Verify legacy/navigation tests pass**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity"`

Expected: tracked turns fetch exact IDs, navigation resets state, and legacy invocation makes no call.

- [ ] **Step 5: Add a failing two-page cursor and deduplication test**

Configure page 1 with sequences 1..200, `nextAfterSequence: 200`, `hasMore: true`; page 2 with 201..225 plus a duplicate ID/sequence, `nextAfterSequence: 225`, `hasMore: false`. Assert calls are exactly `[['turn-a', 0, 200], ['turn-a', 200, 200]]`, final sequences are 1..225 once, and cursor is 225.

- [ ] **Step 6: Implement sequential exact-cursor catch-up**

`loadActivityPage(identity)` awaits one request, verifies identity, merges unseen event IDs/sequences, sets the exact response cursor/capture status, renders, then awaits the next page only when `hasMore` is true. It must never recursively start a second page before the current response is applied.

- [ ] **Step 7: Verify the cursor test passes**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity loads incremental pages"`

Expected: PASS with sequences 1..225 and no request from zero after page 1.

- [ ] **Step 8: Add failing non-overlap and stale-response tests**

Hold Turn A's promise unresolved, trigger two Activity polls, and assert one call remains in flight. Change selection to Turn B, resolve B, then resolve A; assert rendered/state events contain only B and Turn A cannot change capture status or errors.

- [ ] **Step 9: Implement non-overlap and exact stale identity**

Capture identity fields:

```js
{
  taskId: this.state.taskId,
  taskLoadSequence: this.taskLoadSequence,
  workflowRunId: this.state.selectedRunId,
  runLoadSequence: this.runLoadSequence,
  nodeRunId: this.state.selectedNodeRunId,
  turnId: context.turnId,
  activityLoadSequence: this.activityLoadSequence,
}
```

`isCurrentActivity` compares every field. Task/run/node/invocation changes, `close()`, and `dispose()` increment `activityLoadSequence`, clear the Activity timer, and reset side-channel state. `activityPollInFlight` blocks overlap but is never used as the sole stale check.

- [ ] **Step 10: Verify cursor, overlap, and stale tests pass together**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity"`

Expected: all new Activity orchestration tests PASS.

- [ ] **Step 11: Commit selection and loading behavior**

```bash
git add services/forge-console/src/operator/task-execution-view.js services/forge-console/src/operator/task-execution-view.d.ts services/forge-console/tests/agent-projects-page.test.ts
git commit -m "feat(console): load selected invocation activity"
```

### Task 4: Capture polling and isolated errors

**Files:**
- Modify: `services/forge-console/src/operator/task-execution-view.js:850-915,1251-1310,1990-2042`
- Modify: `services/forge-console/tests/agent-projects-page.test.ts`

**Interfaces:**
- Consumes: Task 3 Activity identity/state and existing `pollIntervalMs` derived from `activeJobPollIntervalMs`.
- Produces: capture-aware, non-overlapping Activity polling and local retry behavior independent of WorkflowRun polling.

- [ ] **Step 1: Add failing capture lifecycle tests**

Use fake timers/pages to drive `NOT_STARTED -> ACTIVE -> COMPLETE`. Assert labels `Waiting -> Live -> Complete`, waiting copy, and no further calls after COMPLETE. Drive `ACTIVE -> DEGRADED`; assert events remain, label `Incomplete`, exact missing-activity warning, and polling stops. Drive `UNAVAILABLE`; assert unavailable copy and no subsequent Activity poll.

- [ ] **Step 2: Add failing post-WorkflowRun-terminal polling assertion**

Return a terminal WorkflowRun while Activity remains ACTIVE; advance `activeJobPollIntervalMs` and assert the event endpoint is still polled until a COMPLETE page arrives.

- [ ] **Step 3: Run lifecycle tests and verify capture-aware behavior is absent**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity capture"`

Expected: FAIL because Activity polling is not scheduled/stopped by capture state.

- [ ] **Step 4: Implement the independent Activity timer**

Schedule with `this.pollIntervalMs` only when the current selection has a turn, no request/timer exists, and capture status is unknown, `NOT_STARTED`, or `ACTIVE`. Stop on `COMPLETE`, `DEGRADED`, or `UNAVAILABLE`. Do not consult WorkflowRun terminality when deciding whether an ACTIVE capture needs a final page.

- [ ] **Step 5: Verify capture lifecycle tests pass**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity capture"`

Expected: all lifecycle transitions and terminal polling assertions PASS.

- [ ] **Step 6: Add failing initial/refresh error and Retry tests**

Initial rejection must show `Activity could not be loaded.` and a real Retry button while Context and Output remain visible. A later rejection after one successful event page must keep that event and show `Activity refresh failed.`; Context and Output remain unchanged. Clicking Retry must make one new exact-cursor request.

- [ ] **Step 7: Implement contained error handling and retry**

Set initial versus refresh copy based on whether `activityEvents.length` is zero. Never replace events on failure. Bind `data-activity-retry` to invalidate the pending timer/error and request the current turn from its current cursor; later live-state polling may also retry.

- [ ] **Step 8: Verify all polling/error tests pass**

Run: `npm test -- --run tests/agent-projects-page.test.ts -t "Activity"`

Expected: Activity failures remain local, events survive refresh failure, and Retry works.

- [ ] **Step 9: Commit Activity lifecycle behavior**

```bash
git add services/forge-console/src/operator/task-execution-view.js services/forge-console/tests/agent-projects-page.test.ts
git commit -m "feat(console): poll agent activity safely"
```

### Task 5: Follow-latest, accessibility, and bounded styling

**Files:**
- Modify: `services/forge-console/src/operator/task-execution-view.js:1251-1310`
- Modify: `services/forge-console/src/operator/operator-ui.css:1909-1990,5829-5860`
- Modify: `services/forge-console/tests/task-execution-view.test.ts`
- Modify: `services/forge-console/tests/agent-projects-page.test.ts`

**Interfaces:**
- Consumes: Activity list/scroller rendered by Tasks 2-4 and `activityNewEventCount`/`activityFollowLatest` state.
- Produces: bounded scroll area, near-bottom detection, unseen-event button, focus-safe polling, and accessible semantic structure.

- [ ] **Step 1: Add failing CSS contract tests**

Assert `.node-run-activity` has bounded layout and min-width protection, `.agent-activity-scroll` has `max-height` and `overflow-y:auto`, event content uses `overflow-wrap:anywhere`, and command/output `pre` alone may use horizontal overflow. Assert no Activity rule adds page-level horizontal scrolling.

- [ ] **Step 2: Add failing follow-latest DOM tests**

Stub Activity scroller `scrollHeight`, `clientHeight`, `scrollTop`, and `scrollTo`. At bottom, append one event and assert it scrolls to latest. Set `scrollTop` above the near-bottom threshold, append four events, assert the position is preserved and button text is `4 new events`. Click the actual button and assert scroll-to-bottom, count reset, and follow mode restored.

- [ ] **Step 3: Add failing accessibility assertions**

Assert Activity has a heading, timeline is an `ol`/`ul`, Retry/new-event controls are `<button type="button">`, disclosures are native `<details>/<summary>`, and `document.activeElement` remains on a preselected user control after background render.

- [ ] **Step 4: Run follow/CSS tests and verify failure**

Run: `npm test -- --run tests/task-execution-view.test.ts tests/agent-projects-page.test.ts -t "Activity"`

Expected: FAIL because bounded/follow-latest behavior and styling are incomplete.

- [ ] **Step 5: Implement scroll preservation and bounded Forge-native styling**

Before Activity markup replacement, record near-bottom state and current `scrollTop`. After replacement, scroll only when following; otherwise restore the prior offset and increment unseen count by newly accepted primary events. Invocation changes force follow mode and initial completed catch-up scrolls to latest. Use existing ink/accent/success/failure/warning tones plus visible status text/icons.

- [ ] **Step 6: Verify follow/CSS/accessibility tests pass**

Run: `npm test -- --run tests/task-execution-view.test.ts tests/agent-projects-page.test.ts -t "Activity"`

Expected: all Activity layout, follow, button, focus, and semantic assertions PASS.

- [ ] **Step 7: Run all focused Activity tests**

Run: `npm test -- --run tests/agent-execution-activity.test.ts tests/task-execution-view.test.ts tests/agent-projects-page.test.ts`

Expected: all selected test files PASS without unhandled rejections.

- [ ] **Step 8: Commit UX behavior**

```bash
git add services/forge-console/src/operator/task-execution-view.js services/forge-console/src/operator/operator-ui.css services/forge-console/tests/task-execution-view.test.ts services/forge-console/tests/agent-projects-page.test.ts
git commit -m "feat(console): follow live agent activity"
```

### Task 6: Regression, review, manual acceptance, and PR

**Files:**
- Inspect: all changed files
- Modify only if a failing test identifies an in-scope Phase 3 defect.

**Interfaces:**
- Consumes: complete Phase 3 implementation.
- Produces: verified branch, pushed PR, GitHub CI result, and evidence-qualified final report.

- [ ] **Step 1: Run formatting and focused safety searches**

```bash
git diff --check origin/main...HEAD
rg -n "item/completed|thread/status/changed|mcpToolCall|requestBody|arguments|chain of thought|hidden reasoning" services/forge-console/src/operator/agent-execution-activity.js services/forge-console/src/operator/task-execution-view.js
```

Expected: diff check passes; no Codex protocol or request-body dependencies appear in production Console code.

- [ ] **Step 2: Run complete Console verification**

```bash
cd services/forge-console
npm test -- --run
npm run typecheck
npm run build
```

Expected: all Console tests PASS, TypeScript exits 0, and Vite production build exits 0.

- [ ] **Step 3: Review the final diff for architecture constraints**

Run `git diff --stat origin/main...HEAD` and `git diff origin/main...HEAD -- services/forge-console`. Confirm only the Console client/presentation/view/styles/tests plus approved design/plan docs changed; no Forge Agent/Nexus/runtime lifecycle files changed.

- [ ] **Step 4: Perform real-ledger acceptance when the local stack and credentials are available**

Run one observable agent workflow containing turn start, plan, failed command, reasoning summary, file change, successful command, final message, and turn completion. Verify the selected invocation shows sequence order. Run or inspect an Implementer #1 -> Reviewer -> Implementer #2 retained-context loop and PER_SCOPE executions; verify Context history selects exact separate turn timelines. If the environment cannot execute these flows, record that manual evidence is unavailable rather than fabricating success.

- [ ] **Step 5: Commit any test-driven in-scope corrections and verify a clean worktree**

```bash
git status --short
git log --oneline --decorate origin/main..HEAD
```

Expected: no uncommitted files and a focused Phase 3 commit series.

- [ ] **Step 6: Use `superpowers:requesting-code-review` before publication**

Review the implementation against the spec and required test matrix. Fix only demonstrated Phase 3 defects using a new failing regression test first, then rerun Step 2.

- [ ] **Step 7: Use `superpowers:finishing-a-development-branch` to push and create one PR**

Push `feature/SITIONIX-114`, open a single Phase 3 PR against `main`, and include architecture invariants plus focused/full verification results in the body.

- [ ] **Step 8: Require GitHub CI green**

Use `gh pr checks --watch --fail-fast <PR URL or number>`. If CI fails, inspect the exact job logs, reproduce locally where possible, add a failing regression test, fix, rerun full verification, push, and watch again.

- [ ] **Step 9: Record final evidence**

Report final UX structure, files changed/created, Activity state, cursor/polling/stale/follow behavior, per-event rendering, capture behavior, focused and full verification, manual acceptance evidence or explicit environmental limitation, full CI result, final HEAD, PR URL, and review readiness.
