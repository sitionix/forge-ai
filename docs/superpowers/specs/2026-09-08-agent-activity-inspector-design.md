# Live Agent Activity Inspector Design

## Goal

Extend the existing Task Execution node details panel with a read-only Activity section for the exact selected NodeRun invocation. Activity reads the Phase 2 Forge-owned `AgentExecutionEvent` ledger for that invocation's verified Forge turn and never changes execution, lifecycle, routing, session, context, or output behavior.

## Scope and invariants

- PR #121 is present in the Phase 3 base commit `74674b079c5d71738661f44f502c03d885e5ed7b`.
- The existing NodeRun Invocation selector and Context history remain the only invocation navigation model.
- The selection path is `selected NodeRun -> AgentExecutionContext -> turnId -> event API`.
- Activity is observational. API or rendering failures remain contained inside Activity.
- `AgentExecutor`, Codex semantics, NodeRun lifecycle, workflow routing, output selection, session allocation, context behavior, leases, recovery, cancellation, ledger persistence, and Nexus/Forge Agent contracts are unchanged.
- No streaming, controls, hidden reasoning, cost calculation, token charts, combined timelines, provider protocol knowledge, or new backend event types are introduced.

## User experience

The node details order becomes Invocation, Overview, Context, Activity, Prompt, Output, and Failure. The existing sections retain their semantics. Activity has a real heading, capture-state label, bounded vertical scroll area, semantic event list, compact latest usage summary, and local retry/new-event buttons.

Capture states map as follows:

| Forge state | UI state | Supporting behavior |
| --- | --- | --- |
| `NOT_STARTED` | Waiting | `Waiting for agent activity.` when empty; continue polling |
| `ACTIVE` | Live | Empty copy says the agent is active and waiting for its first event; continue polling |
| `COMPLETE` | Complete | Empty copy says no activity events were recorded; stop polling |
| `DEGRADED` | Incomplete | Preserve events and show `Some agent activity may be missing.`; stop polling |
| `UNAVAILABLE` | Unavailable | Show `Activity was not recorded for this invocation.`; stop polling |

A NodeRun without verified context and a real `turnId` shows Activity unavailable and makes no events request. Capture status describes recording only and never overrides the NodeRun or turn result shown elsewhere.

## Presentation boundary

Create `agent-execution-activity.js` and its declaration file. The module accepts provider-neutral Forge events and produces escaped, compact HTML/display fragments, capture-status presentation, and latest usage derivation. It does not call APIs, know selection, own polling, interpret Codex protocol names, or render tool request bodies.

`TaskExecutionView` owns selection-linked Activity state, loading, pagination, polling, retry, stale-response checks, and scroll-follow behavior. This preserves the current view as the integration boundary without embedding all event formatting in it.

## Event presentation

Events are deduplicated and rendered strictly by numeric `sequence ASC`; timestamps are display metadata only.

| Forge type | Presentation |
| --- | --- |
| `TURN` | Compact started/completed/failed lifecycle row using event `status` |
| `PLAN` | Explanation and ordered steps with supplied provider-neutral statuses |
| `REASONING_SUMMARY` | Provider-visible reasoning summary from safe string/array/object payload forms |
| `COMMAND` | Command, status, cwd, exit code, duration, collapsed preserved-whitespace output, and visible truncation state |
| `FILE_CHANGE` | Normalized operation/path/summary rows without synthetic diffs |
| `TOOL_CALL` | Safe kind/server/tool/operation/status plus collapsed response summary; never request input/body |
| `AGENT_MESSAGE` | Clear activity-history message card; final phase may receive emphasis but never replaces NodeRun Output |
| `WARNING` | Visible warning diagnostic without lifecycle inference |
| `ERROR` | Visible error diagnostic without lifecycle inference |
| `TOKEN_USAGE` | Omitted from the primary event list and used as the latest-known compact usage summary; missing fields are omitted |
| `CONTEXT_COMPACTION` | Compact context-compacted row with safe metadata when present |
| unknown | Safe `Activity`, event type, and formatted payload fallback |

All dynamic values are HTML escaped. Structured values are formatted safely. Long content wraps, while command output may scroll horizontally inside its own `pre`. Hidden reasoning and tool request bodies are neither expected nor represented.

## API and Activity state

Add `getAgentExecutionEvents(turnId, afterSequence = 0, limit = 200)` to the existing Agent Projects API. It calls the existing Nexus route under `/agents/agent-execution-turns/{encodedTurnId}/events` with exact `afterSequence` and `limit` query parameters.

Activity is an independent side channel in `TaskExecutionView`, conceptually containing:

- selected `workflowRunId`, `nodeRunId`, and `turnId` identity;
- `events`, `cursor`, and `captureStatus`;
- `loading`, `error`, request-in-flight, timer, and load-sequence state;
- follow-latest mode and unseen-event count.

WorkflowRun polling and Activity polling do not share requests or error state. Both use the configured `activeJobPollIntervalMs`; Activity introduces no second frequency.

## Incremental loading and polling

Selecting a verified turn resets Activity and immediately requests `afterSequence=0&limit=200`. Each response is applied only to the matching selection identity, then new events are deduplicated by event ID and sequence, sorted by sequence, and appended. If `hasMore` is true, the next page is requested sequentially with the exact returned `nextAfterSequence`; pages never overlap or restart from zero.

After catch-up, Activity schedules a non-overlapping poll only for unknown initial state, `NOT_STARTED`, or `ACTIVE`. Terminal capture states stop Activity polling even if the WorkflowRun is otherwise active. Conversely, Activity may continue briefly after a terminal WorkflowRun until capture becomes terminal.

Initial failures display `Activity could not be loaded.` with Retry. Refresh failures preserve existing events and display `Activity refresh failed.` with Retry. A last-known live/waiting state may poll again later. These errors never alter graph refresh, Context, Prompt, Output, Failure, or selection.

## Stale-response protection

Every Activity load captures an exact identity containing task ID/load sequence, workflow run ID/load sequence, selected NodeRun ID, turn ID, and Activity load sequence. Response pages, errors, pagination continuations, timers, and scroll updates apply only while that identity is current.

Task change, WorkflowRun change, visual node change, invocation change, Context history navigation, close, and dispose invalidate the Activity load sequence and timer. Late requests are logically discarded. Existing `FOLLOW_LATEST` and `PINNED_INVOCATION` behavior remains authoritative: Activity follows whatever NodeRun the existing selection logic selects and never advances independently.

## Follow-latest behavior

Each invocation starts in follow-latest mode. Before rendering appended events, the view records whether the Activity scroller is near its bottom. If so, it scrolls to the latest item after rendering. If the operator has scrolled upward, rendering preserves the reading position and increments a `N new events` button. Clicking it scrolls to the bottom, clears the count, and resumes follow mode.

Polling never focuses controls or uses an aggressive live region. Completed historical turns land on the latest activity after their initial catch-up.

## Testing

Use TDD for the API client, pure presentation module, and TaskExecutionView orchestration. Focused coverage includes every current event type and unknown fallback, escaping, whitespace/truncation, safe tool/reasoning boundaries, exact cursor forwarding, sequential pagination, deduplication, non-overlap, stale turn responses, capture transitions, terminal polling, isolated errors, legacy turns, pinned selection, Context history navigation, and follow-latest behavior.

Final verification runs the complete Console suite, TypeScript check, production build, and repository `git diff --check`. Manual acceptance uses a real Phase 2 ledger only when a runnable local environment and observable workflow are available. GitHub CI must be green before review readiness is reported.
