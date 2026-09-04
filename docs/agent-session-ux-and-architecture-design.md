# Agent Context UX and Session Architecture Contract

Date: 2026-09-04

Status: Phase 1A design, ready for implementation review

Scope: Forge Console, workflow/runtime API shape, and Forge-owned persistence contract. Production durable provider sessions, resume execution, event capture, and runtime controls are explicitly out of scope.

## Decision summary

Forge calls the node property **Context**. Its two values are:

| Domain value | Builder label | Meaning |
| --- | --- | --- |
| `FRESH_EACH_NODE_RUN` | **Fresh each invocation** | Every NodeRun starts an independent provider conversation. |
| `REUSE_WITHIN_WORKFLOW_NODE` | **Continue in this workflow** | NodeRuns for the same snapshotted logical node and scope in one WorkflowRun share one Forge-owned session. |

`FRESH_EACH_NODE_RUN` is the database, domain, API, and Console default for existing and new nodes. Runtime identity never belongs to a reusable Workflow. The policy is copied through `Node -> RunNode -> NodeRun`; concrete sessions and turns exist only under a WorkflowRun.

The Builder uses two radio rows in a dedicated Context section below Execution. Only continued-context nodes receive a quiet `↻ Context` badge, in both Builder and modern runtime graphs. Runtime details use `Fresh`, `New`, and `Continued` for an invocation's relationship to context and use `Waiting`, `Active`, `Idle`, `Failed`, and `Unavailable` for session lifecycle. Provider identifiers appear only in a collapsed **Technical details** disclosure.

## 1. Current UX and implementation audit

### Workflow Builder

The current Builder is a canvas of fixed-width agent nodes. Each card shows the agent name, a two-line instruction excerpt, input/output port labels, and delete action. Clicking rather than dragging opens one modal node editor. The editor currently contains:

1. side-by-side `INPUTS` and `OUTPUTS` sections with compact/editing rows;
2. `Input content`, rendered as a select or read-only `Original task` for a root node;
3. `Execution`, rendered as a select with `Once` and `Per repository`;
4. Cancel and Save actions owned by the existing dialog.

This structure provides a strong hierarchy: topology first, invocation inputs next, execution projection next. Context determines behavior across projected invocations, so it belongs immediately after Execution, not among ports, agent instructions, or global workflow settings. The modal already scrolls, the body is a vertical grid, and its two-column port layout collapses at narrow widths; a full-width fieldset fits without another modal or side panel.

The Builder maintains an in-memory draft and explicitly normalizes `inputMode` and `scopeMode` on clone and save. New nodes explicitly default to `GLOBAL` and `DEPENDENCIES_ONLY`. Phase 1B must follow this pattern for `contextMode`, including normalization of absent values to fresh.

### Builder graph

Builder cards are already dense around their left and right port columns. Their center has room for one small metadata line, but not a second status system. Default behavior should therefore remain invisible. A single opt-in badge is enough to make potentially surprising sharing discoverable.

### Task Execution

Task Execution currently has:

- a workflow-execution history sidebar;
- an execution summary above the main content;
- a pan/zoom runtime graph;
- a fixed 320px Node details panel;
- a modern graph projection backed by the WorkflowRun snapshot;
- a legacy graph fallback for executions without a runtime graph.

The modern projection already creates one visual unit per `(sourceNodeId, repositoryId)`: a GLOBAL node creates one unlabeled unit and a PER_SCOPE node creates one unit per repository in snapshot order. It groups all NodeRuns for that unit, numbers them in chronological order, shows latest status and run count on the graph card, and retains the selected invocation in a details-panel `Invocation` select. This is the correct base model. Context must annotate that invocation model rather than replace it or create session nodes/edges on the graph.

The existing details panel presents an invocation selector, an overview grid, Prompt, Output, and Failure. Context belongs directly after the selector and before general invocation metadata: it explains how the selected invocation relates to the others. Technical identifiers fit the existing native `<details>` disclosure pattern used for Prompt.

### Current backend and snapshot model

The reusable `Node` stores target agent, input policy, ports, position, and scope. `WorkflowRunSnapshotBuilder` resolves the mutable agent definition and copies agent data, execution model, input mode, position, and scope into `RunNode`. `NodeRunFactory` then copies execution-relevant values from `RunNode` into every invocation. PostgreSQL mirrors these layers in `workflow_nodes`, `workflow_run_nodes`, and `node_runs`.

This established path is the required policy path:

```text
Workflow Node.contextMode
        snapshot at WorkflowRun creation
RunNode.contextMode
        copy at NodeRun creation
NodeRun.contextMode
```

Editing a Workflow after the run starts cannot affect `RunNode`, so carrying context mode through the same path preserves the current snapshot guarantee.

### Phase 0 durable-protocol findings that constrain this design

The protocol audit proves Codex CLI `0.153.2` can durably start and resume a conversation across app-server processes. It also establishes constraints the Forge model must enforce:

- provider conversation and turn IDs are opaque, nonblank strings, not Forge IDs;
- a durable provider conversation ID must be persisted before starting a turn;
- a provider turn ID must be persisted before dependent operations;
- resume must use the exact stored identity and never fall back silently to start;
- one session permits at most one writer/active turn;
- `turn/completed` was not observed despite being in the schema, so completion detection remains an explicit Phase 1B prerequisite;
- full-history hydration is deprecated; the UX relationship must come from Forge turn records, not provider-history fetching;
- Codex version compatibility is not established and should be recorded as technical metadata.

## 2. User model and terminology

Users configure **context**, not threads, memory, or database sessions.

- **Invocation** is the existing UI term for one NodeRun.
- **Context** is the prior conversation available to an agent invocation.
- **Fresh** means an invocation has an independent, non-reusable conversation.
- **New** means the first invocation created a reusable context.
- **Continued** means an invocation used the reusable context created by an earlier invocation.
- **Turn N** is the sequence number within a reusable context, not the graph-wide invocation number. They normally match for a GLOBAL node, but must remain separate concepts because legacy data, failed pre-turn setup, and future reset/fork behavior can make them differ.

The primary UI may use “session” only in explanatory prose if necessary; headings and actions use Context. Domain code uses `AgentExecutionSession` because it represents Forge ownership and lifecycle.

Context retention is bounded by one WorkflowRun. It does not survive into a later execution of the same reusable Workflow. It never crosses nodes, repositories, or workflows in Phase 1B.

## 3. Workflow Builder design

### Placement and control

Add a full-width `<fieldset>` after Execution and before the dialog error/actions:

```text
EXECUTION
[ Per repository                         v ]

CONTEXT                                      [?]
(o) Fresh each invocation
    Starts this agent with clean context every time this node runs.

( ) Continue in this workflow
    Reuses this node's context when the workflow returns to it.
    Each repository keeps its own independent context.   [PER_SCOPE only]
```

Use native radio inputs wrapped by full-width selectable rows. This is preferable to cards because the current Console has restrained bordered rows rather than large settings cards, and preferable to a segmented control because the descriptions are necessary to understand the consequence.

Exact copy:

- Section label: `Context`
- Option 1: `Fresh each invocation`
- Option 1 description: `Starts this agent with clean context every time this node runs.`
- Option 2: `Continue in this workflow`
- Option 2 description: `Reuses this node's context when the workflow returns to it.`
- Conditional PER_SCOPE note: `Each repository keeps its own independent context.`
- Help tooltip: `Context is kept only for this node during one workflow execution.`

The radio group is always enabled for an editable Workflow node. There is no provider-specific disabled state in Phase 1B: unsupported provider behavior must be rejected by server validation on save or run creation with a clear error, not shown as a control Forge cannot reliably evaluate from the Builder. While saving, the existing dialog and workflow Save behavior disables mutation consistently; no separate context loading state is introduced.

### Selected, focus, keyboard, and responsive behavior

- The selected row has the current green border, faint green background, and checked native radio. Color is not the only cue.
- Hover changes only the border/background. Disabled rows, if introduced with later capability negotiation, use `disabled`, reduced opacity, `cursor: not-allowed`, and inline reason text.
- Tab enters the radio group; arrow keys move between choices; Space selects, following native browser behavior. The entire row label is clickable.
- A visible `:focus-visible` ring uses the Console focus color and is not clipped by the row.
- Labels and descriptions are programmatically associated through `<fieldset>`, `<legend>`, `<label>`, and described-by IDs. The help icon is a keyboard-focusable button with the full tooltip in accessible text.
- At all widths the choices stack vertically. The fieldset uses the modal's full available width; descriptions wrap. It does not join the Inputs/Outputs columns or introduce horizontal scrolling.

### Draft, save, and validation behavior

- Opening an old node with absent/null `contextMode` selects fresh.
- New node drafts set `contextMode: FRESH_EACH_NODE_RUN` explicitly.
- Saving the node editor writes the normalized enum into the workflow draft.
- Saving the Workflow sends `contextMode` for every node.
- Unknown values are rejected, not coerced. Only absent/null legacy values normalize to fresh.
- The node editor Save applies the choice to the draft; the top-level Workflow Save persists it, matching current editor semantics.

## 4. Workflow graph design

### Badge decision

Only `REUSE_WITHIN_WORKFLOW_NODE` nodes display a badge:

```text
↻ Context
```

Use a small circular-arrow SVG or CSS mask from the Console's icon treatment with `aria-hidden="true"`; do not rely on a Unicode glyph for accessible naming. Visible text remains `Context`. Tooltip and accessible label are exactly: `Continues context when this node runs again in this workflow.`

The badge sits beneath the agent instruction excerpt in the Builder card's center column. It is visually secondary: muted green text, faint green background, one-pixel border, compact mono type. It does not increase card width. Node height may increase only when the badge exists; port/layout bounds must use measured/computed height as they do for variable port rows.

The same badge appears on modern runtime graph cards, below the repository label and above invocation status. It indicates the snapshotted policy, even before the node is reached. It does not change with lifecycle status and is not clickable independently; clicking the card continues to select the visual unit/invocation. The runtime tooltip is: `Invocations of this node share context within this workflow execution.`

Fresh/default nodes show no badge. The legacy runtime graph shows no context badge because it has no reliable snapshotted policy. There is no graph legend entry: one self-explanatory opt-in badge does not justify permanent legend noise.

## 5. Task Execution design

### Details-panel hierarchy

Keep the current invocation selector. Insert Context immediately below it, followed by the existing overview, Prompt, Output, and Failure:

```text
Invocation
[ #3 latest · RUNNING                   v ]

CONTEXT
● Continued                              Turn 3
  Keeps context in this workflow

  Status       Active
  Scope        forge-agent
  Started      Invocation #1
  Current      Invocation #3

  Context history
  [#1 ✓ New] ─ [#2 ✓ Continued] ─ [#3 • Continued]

Agent          Implementer
Status         RUNNING
Input mode     Previous outputs only
Started        ...
Finished       ...

▸ Technical details
▸ Prompt
OUTPUT
```

The card is compact and border-separated, not a permanent large panel. Selecting a history chip changes the existing invocation selector and selected NodeRun; it does not navigate away or mutate execution.

### Two orthogonal status dimensions

Do not combine invocation relationship and session lifecycle into one ambiguous badge.

**Invocation relationship**

| Label | When shown | Supporting copy |
| --- | --- | --- |
| `Fresh` | `FRESH_EACH_NODE_RUN`; each NodeRun is independent | `Started with clean context` |
| `New` | First turn of a reusable session | `Started reusable context for this workflow` |
| `Continued` | Sequence greater than 1 in a reusable session | `Continued context from an earlier invocation` |
| `Unavailable` | Legacy/incomplete metadata prevents a truthful classification | `Context information was not recorded` |

**Context lifecycle**

| Label | Domain state | UI meaning |
| --- | --- | --- |
| `Waiting` | `CREATING` or `RESUMING` | Forge is establishing the context; no active provider turn is accepted yet. |
| `Active` | `ACTIVE` | This session owns exactly one in-progress turn, linked to the selected/running NodeRun. |
| `Idle` | `IDLE` | Reusable context exists and has no active turn; it may be continued later in this WorkflowRun. |
| `Failed` | `FAILED` | Context creation/resume/identity/persistence failed. Show the safe human message and a link/selection to the failed invocation. |
| `Unavailable` | no session metadata, unsupported legacy record, or redacted/inaccessible runtime metadata | Forge cannot determine lifecycle; never infer `Idle` or `Fresh`. |

A fresh invocation does not have a reusable context lifecycle. Its card shows `Fresh`, its NodeRun status, and `Context is not reused`; it does not misleadingly show `Idle` after completion.

For a reusable session, a completed selected turn may show `New` or `Continued` while the session itself shows `Idle`. If a later invocation is Active, selecting an earlier turn still reports the current session lifecycle as `Active` and names `Current Invocation #N`; the selected turn remains visually identified in history.

### Context policy while viewing an execution

Context policy is read-only in Task Execution, whether the execution is queued, active, or complete. The user can inspect `Fresh each invocation` or `Continue in this workflow`, but cannot edit it there. “Edit workflow” may navigate to the reusable Workflow in a later general UX change, but any edit affects future WorkflowRuns only. Phase 1B adds no active-run policy control and no reset action.

### Technical details

Place a collapsed `Technical details` disclosure after Context and before Prompt. It may show fields only when present:

- Forge session ID;
- provider;
- provider conversation ID;
- provider turn ID;
- model and effort;
- provider/CLI version;
- repository ID;
- session/turn timestamps.

Values use mono text, wrap safely, and have copy buttons only if the Console already has an accessible copy pattern at implementation time. Missing fields render `Not recorded`, not fabricated values. Raw IDs never appear on the graph, summary, invocation selector, or primary Context card.

## 6. Invocation and context history

### Reusable context

The history strip is derived from Forge `AgentExecutionTurn.sequence`, ordered ascending. Each chip contains the existing invocation number, terminal/running icon, and relationship:

```text
Context history
[#1 ✓ New] ─ [#2 ✓ Continued] ─ [#3 • Continued]
```

Connectors communicate that all chips are turns in one context. The selected invocation has the normal selected outline. Icons have accessible text (`Succeeded`, `Running`, `Failed`, and so on). On a narrow panel the strip wraps as a list while retaining connector/order semantics; it never requires horizontal page scrolling.

If more than five turns exist, show first, previous, selected, next, and latest as applicable, with an inline `+N earlier` expander. Expanding remains within the card and uses a vertical list. This scales without replacing the Invocation select, which remains the canonical selection control.

### Fresh repeated invocations

For fresh policy there is no connected context-history strip. Show a compact **Invocations** list with separated chips and a one-line explanation:

```text
Independent invocations
[#1 ✓ Fresh]   [#2 ✓ Fresh]   [#3 • Fresh]
Each invocation started with clean context.
```

No connector line appears. This is the primary visual distinction between three turns sharing one context and three independent conversations. Chips still select the existing NodeRun.

### Missing or partial linkage

If a reusable NodeRun has `contextMode=REUSE_WITHIN_WORKFLOW_NODE` but lacks a session/turn link, show `Unavailable — Context information was not recorded` for that invocation and exclude it from a fabricated connected chain. If some turns are present, show the verified chain plus a warning row naming the unlinked invocation numbers. Never infer membership from provider IDs, timestamps, or adjacency alone.

## 7. GLOBAL and PER_SCOPE semantics

Session scope is deterministic from the snapshotted RunNode and NodeRun repository:

```text
GLOBAL:
(workflowRunId, sourceNodeId, repositoryId = null) -> one reusable context

PER_SCOPE:
(workflowRunId, sourceNodeId, repositoryId) -> one reusable context per repository
```

`sourceAgentId` is snapshotted/session metadata, not part of the identity key: the logical workflow node owns context. It is still stored to audit which agent definition was snapshotted.

The modern graph already projects PER_SCOPE nodes into separate repository cards. Each card's Context card and history are therefore repository-local. The Scope row is:

- GLOBAL: `Workflow`;
- PER_SCOPE: the human repository name, falling back to `Repository unavailable` while preserving the ID only in Technical details.

For an overview before a specific invocation is selected, a PER_SCOPE continued-context node may summarize:

```text
CONTEXTS · PER REPOSITORY
forge-agent       Turn 3    Idle
forge-console     Turn 1    Active
forge-nexus       Turn 2    Failed
```

Rows select the already projected repository visual unit. Use virtualization or the existing scroll container for many repositories; show repository search/filter only when the current runtime graph adds a general scalable repository navigation need. Never aggregate turn numbers across repositories and never draw lines between repository contexts.

Builder conditional copy (“Each repository keeps its own independent context.”), repository-separated runtime cards, and repository-local histories jointly prevent an implication of hidden shared memory.

## 8. Backend and domain contract

### Policy models

```java
enum NodeContextMode {
    FRESH_EACH_NODE_RUN,
    REUSE_WITHIN_WORKFLOW_NODE
}
```

Add non-null `contextMode` to `Node`, `RunNode`, and `NodeRun`. API request/response values use the exact enum strings. Database columns are `context_mode VARCHAR(48) NOT NULL` with a check constraint.

Migration rules:

- add `workflow_nodes.context_mode` with a temporary default of `FRESH_EACH_NODE_RUN`;
- backfill null/existing rows to `FRESH_EACH_NODE_RUN`, then remove the database default after enforcing not-null/check constraints, matching the current scope migration pattern;
- add and backfill `workflow_run_nodes.context_mode` to fresh for old snapshots;
- add and backfill `node_runs.context_mode` to fresh for policy readability;
- add nullable `node_runs.context_tracking_version`; migration leaves historical rows null and new Phase 1B NodeRuns write `1`. A null version makes invocation relationship/lifecycle legacy-unavailable even when the policy column was backfilled to fresh, so schema compatibility never becomes false historical evidence;
- application normalization defaults only absent/null legacy values to fresh and rejects unknown non-null values.

The API must return snapshotted `RunNode.contextMode` in the runtime graph and `NodeRun.contextMode` with each invocation. Builder responses return `Node.contextMode`. No session or provider identity is included in Workflow node DTOs.

### Forge-owned runtime records

```text
AgentExecutionSession
  id                         UUID, Forge-owned primary key
  workflowRunId              UUID, required
  sourceNodeId               UUID, required
  sourceAgentId              UUID, required snapshot metadata
  repositoryId               UUID, nullable only for GLOBAL
  providerId                 string, required opaque provider key
  providerConversationId     string, nullable until durable start is persisted
  providerVersion            string, nullable technical metadata
  status                     CREATING | RESUMING | IDLE | ACTIVE | FAILED | CLOSED
  activeNodeRunId            UUID, nullable; required while CREATING, RESUMING, or ACTIVE
  failureCode                string, nullable
  failureMessage             string, nullable safe human text
  createdAt                  instant, required
  updatedAt                  instant, required
  closedAt                   instant, nullable; required only for CLOSED

AgentExecutionTurn
  id                         UUID, Forge-owned primary key
  agentSessionId             UUID, required
  nodeRunId                  UUID, required and unique
  providerTurnId             string, nullable until turn/start is persisted
  sequence                   positive integer, unique within session
  status                     WAITING | ACTIVE | SUCCEEDED | FAILED | CANCELLED
  failureCode                string, nullable
  failureMessage             string, nullable safe human text
  startedAt                  instant, nullable
  finishedAt                 instant, nullable
  createdAt                  instant, required
  updatedAt                  instant, required
```

`NodeRun` references its Forge turn through the unique `AgentExecutionTurn.nodeRunId`; APIs may expose `agentSessionId` and `agentTurnId` as read-only runtime fields for convenient rendering. The normalized relational source of truth remains the turn row, avoiding two writable relationship columns. Fresh NodeRuns do not create `AgentExecutionSession`/`AgentExecutionTurn` records in Phase 1B; their policy plus normal NodeRun lifecycle is sufficient. This avoids calling ephemeral provider conversations durable “sessions” and keeps persistence focused on resumable state.

### Constraints and indexes

- Unique session scope with null-safe semantics:
  - GLOBAL partial unique index on `(workflow_run_id, source_node_id)` where `repository_id IS NULL`;
  - PER_SCOPE partial unique index on `(workflow_run_id, source_node_id, repository_id)` where `repository_id IS NOT NULL`.
- Foreign key session `(workflow_run_id, source_node_id)` references the snapshotted run node.
- Repository presence is validated against snapshotted `RunNode.scopeMode`: GLOBAL requires null; PER_SCOPE requires a repository in `workflow_run_repositories`.
- Unique turn `(agent_session_id, sequence)` and unique turn `node_run_id`.
- At most one nonterminal writer turn per session via a partial unique index on `agent_session_id WHERE status IN ('WAITING', 'ACTIVE')` plus transactional/leased ownership.
- `activeNodeRunId` must identify that session's WAITING or ACTIVE turn throughout CREATING, RESUMING, and ACTIVE session states. Updates to session state and turn ownership occur in one transaction.
- Provider conversation ID is unique within `(provider_id, provider_conversation_id)` when non-null so Forge cannot accidentally assign one provider conversation to two sessions.
- Provider turn ID is unique within `(agent_session_id, provider_turn_id)` when non-null.

### Allocation and execution rules

For `REUSE_WITHIN_WORKFLOW_NODE`, session lookup/create uses only the deterministic scope key from the NodeRun and snapshotted RunNode. The algorithm is transactional and idempotent:

1. Lock or atomically create the scope's Forge session.
2. Reject/leave waiting if another turn owns it; never start a concurrent writer.
3. Allocate the next sequence and Forge turn linked to the NodeRun.
4. For a new session, durable-start the provider conversation, validate the nonblank ID, and persist it before `turn/start`.
5. For an existing session, resume the exact persisted conversation, require the returned ID to match, and persist failure without calling start on any error/mismatch.
6. Start the provider turn, validate and persist its ID before waiting, interrupting, or correlating notifications.
7. Correlate provider notifications by exact provider conversation/turn pair; never attach an event to “latest”.
8. Finish the Forge turn and transition the session to IDLE, or mark both appropriately failed. `CLOSED` is reserved for terminal WorkflowRun cleanup/retention semantics; Phase 1B need not call a provider close API.

Creation/resume work must not hold the WorkflowRun coordination database lock across a provider call. Single-writer ownership needs a database lease/lock with fencing or an equivalent cross-worker mechanism, not only an in-process mutex. A failed persistence step halts provider progress because continuing would lose the recovery identity.

### Snapshot/edit invariants

- Only context policy is reusable configuration.
- Session/turn/provider IDs never enter `Workflow`, `Node`, Workflow update requests, or Builder drafts.
- `RunNode.contextMode` is immutable for the life of its WorkflowRun.
- Every NodeRun copies the RunNode mode; a later Workflow edit cannot alter it.
- Session scope uses `sourceNodeId`, not mutable agent identity or display name.
- A new WorkflowRun uses the current Workflow policy and starts with no sessions from earlier runs.

## 9. State model

```text
No session
  allocate first reusable invocation -> CREATING / turn WAITING

CREATING
  durable identity persisted -> ACTIVE after turn identity persisted
  provider/persistence error -> FAILED / turn FAILED

IDLE
  allocate next invocation -> RESUMING / turn WAITING

RESUMING
  exact identity resumed and turn persisted -> ACTIVE
  any resume error or identity mismatch -> FAILED / turn FAILED
  (never -> CREATING as fallback)

ACTIVE
  successful turn -> IDLE / turn SUCCEEDED
  turn failure -> IDLE / turn FAILED when conversation remains valid
  session-corrupting or identity failure -> FAILED / turn FAILED
  workflow cancellation -> IDLE or CLOSED / turn CANCELLED per cleanup policy

FAILED
  terminal for automatic Phase 1B execution; no implicit retry or replacement

CLOSED
  terminal retained metadata; no further turns
```

`Waiting` is a UI projection of CREATING/RESUMING before a provider turn becomes active. A NodeRun waiting behind another writer remains PENDING and has no allocated turn; details derive `Waiting for this context` from the scoped session's nonterminal writer. When ownership becomes available, one transaction acquires the fenced writer lease, allocates the WAITING turn, sets `activeNodeRunId`, and marks the NodeRun RUNNING. Provider start/resume happens only after that commit. A setup failure then marks the turn, session, and NodeRun FAILED with a context-specific failure code. This boundary prevents a RUNNING NodeRun without an owned setup operation.

Recommended error codes include `AGENT_CONTEXT_START_FAILED`, `AGENT_CONTEXT_RESUME_FAILED`, `AGENT_CONTEXT_IDENTITY_MISMATCH`, `AGENT_CONTEXT_PERSISTENCE_FAILED`, and `AGENT_CONTEXT_BUSY`. Error messages must say whether existing context could not be continued and explicitly state that no fresh context was started.

## 10. Failure, unavailable, and historical states

### Resume failure

The selected invocation shows:

```text
CONTEXT
! Continued                              Turn 3
  Status       Failed
  Scope        forge-agent

Could not continue the existing context.
No fresh context was started.
```

The NodeRun is FAILED, the graph uses its existing failure treatment, and the task failure summary links to it. The full safe message/code appears in the existing Failure section; provider error details and IDs remain under Technical details. There is no automatic or visual transition to Fresh.

### Session busy or waiting

If another invocation owns the session, the later invocation shows `Waiting for this context` and remains queued/pending according to the chosen worker transition boundary. This is serialization, not failure. `AGENT_CONTEXT_BUSY` is used only if a lease cannot be recovered or a configured wait policy expires; Phase 1B does not expose a manual takeover control.

### Old historical executions

Old WorkflowRun snapshots and NodeRuns may have no reliable session metadata. They remain fully readable with the current graph, invocation selector, Prompt, Output, Failure, and timestamps.

- Legacy graph: no context badge/card; show one muted note in details: `Context information was not recorded for this execution.`
- Modern snapshot backfilled to fresh but NodeRuns have null `contextTrackingVersion`: policy may read `Fresh each invocation (legacy default)`, but relationship/lifecycle is `Unavailable`; do not state that Forge observed a fresh start.
- Partial metadata: render only verified fields and identify unlinked invocations as unavailable.
- Missing repository display metadata: show `Repository unavailable`; raw ID is technical-only.

This intentionally distinguishes schema backward compatibility from historical truth.

## 11. Future extension points

Phase 1B keeps the existing single details column, but components should be structured so it can evolve into:

```text
Overview | Activity | Result
```

- **Overview** owns invocation selection, Context, core status/timing, and configuration snapshot.
- **Activity** will own a chronological execution ledger: plans, commands, file changes, tests, MCP/tool calls, warnings, token usage, and compaction. It is keyed by Forge turn and exact provider identities; Phase 1B does not create the event ledger.
- **Result** will own final output, schema-rendered results, and failure summary; existing Output can migrate here later.

Future context controls—interrupt, steering, reset/start fresh, and fork—belong in the Context card action area, gated by lifecycle and permissions. They are not Builder policy options. Context compaction belongs as an Activity event plus Context metadata/action. Shared context groups, cross-node context, and cross-workflow memory would expand the Builder Context choices and replace the deterministic scope key with an explicit group/memory reference; the two-value `NodeContextMode` remains unambiguous and can be extended or paired with a separate grouping property without reinterpreting existing values.

No Phase 1B UI reserves empty buttons or tabs. It uses component boundaries, stable terminology, Forge turn identity, and a collapsed technical section so later observability can be added without redesigning graph semantics.

## 12. Exact Phase 1B boundary

### Included in Phase 1B

- `NodeContextMode` across domain, validation, DTOs, persistence, and OpenAPI/fixtures if present.
- Backward-compatible migrations for `workflow_nodes`, `workflow_run_nodes`, and `node_runs`.
- Snapshot propagation `Node -> RunNode -> NodeRun`.
- Builder radio-row Context control, defaults, validation, responsive/accessibility behavior, and `↻ Context` badge.
- Forge `AgentExecutionSession` and `AgentExecutionTurn` persistence, repositories, constraints, deterministic GLOBAL/PER_SCOPE allocation, and NodeRun linkage.
- Single-writer ownership with cross-worker fencing/locking and ordered identity persistence.
- Explicit durable provider start and exact resume orchestration for supported/pinned provider version, without modifying the fresh execution path's semantics.
- Resolution and contract testing of Codex `0.153.2` completion detection before enabling durable execution.
- Runtime API fields required for the Context card/history and technical disclosure.
- Runtime graph opt-in badge, details Context card, invocation/history relationship, PER_SCOPE presentation, failure, waiting, and legacy/unavailable states.
- Tests covering defaults, snapshots, migrations, constraints, serialization, no-fallback failure, API mapping, Builder accessibility/serialization, projection/history, and all acceptance scenarios below.

### Explicitly later

- generalized execution event ledger or Activity tab;
- commands, file changes, tests, MCP/tool-call, plan, reasoning-summary, warning, usage, or compaction UI/storage;
- live steering, interrupt controls, manual reset/start-fresh, fork, retry/takeover, or session close controls;
- shared context groups, cross-node context, cross-workflow memory, or contexts surviving a WorkflowRun;
- full provider-history hydration;
- multiple concurrent writers;
- changes to graph topology representing sessions;
- broad `Overview | Activity | Result` tab conversion.

## 13. Explicit UX question resolutions

1. **Property name:** `Context`; domain name `NodeContextMode`.
2. **Default:** `FRESH_EACH_NODE_RUN` / `Fresh each invocation` for existing and new nodes.
3. **Builder control:** dedicated full-width fieldset with two descriptive native radio rows below Execution.
4. **Graph badge:** yes, only for continued-context nodes.
5. **Badge:** `↻ Context`; circular-arrow icon is decorative, visible label is text, tooltip explains reuse.
6. **First reusable invocation:** `New` with `Started reusable context for this workflow`.
7. **Resumed invocation:** `Continued` with `Continued context from an earlier invocation`.
8. **Shared repeated invocations:** connected, selectable Context history chips ordered by Forge turn sequence.
9. **Fresh repeated invocations:** unconnected `Independent invocations` chips, each labeled Fresh, plus explicit clean-context copy.
10. **PER_SCOPE:** one projected repository unit and one session/history per repository; overview rows list repository-local turn/status.
11. **Technical IDs:** collapsed Technical details in Node details, never primary graph/UI.
12. **Editing during execution:** runtime view is read-only; reusable Workflow edits affect future WorkflowRuns only.
13. **Old NodeRuns:** remain readable; context is absent or marked Unavailable/legacy, never inferred.
14. **Boundary:** Phase 1B includes policy/snapshot/session/turn foundation and compact runtime representation; event observability and controls are later.

## 14. Acceptance scenarios

### Scenario A — stateless node

Builder: `Context · Fresh each invocation`; no graph badge.

```text
Implementer visual unit · 2 runs

Independent invocations
[#1 ✓ Fresh]   [#2 ✓ Fresh]
Each invocation started with clean context.
```

No Forge durable session/turn records exist. Each NodeRun uses the existing fresh execution path.

### Scenario B — persistent feedback loop

Builder and runtime graph: `↻ Context` on Implementer only.

```text
Implementer #1  -> New        Turn 1
Reviewer
Implementer #2  -> Continued  Turn 2
Reviewer
Implementer #3  -> Continued  Turn 3

Context history
[#1 ✓ New] ─ [#2 ✓ Continued] ─ [#3 ✓ Continued]
```

All three Implementer NodeRuns reference three Forge turns in one session keyed by `(workflowRunId, implementerSourceNodeId, null)`. Reviewer behavior is independent according to its own policy.

### Scenario C — PER_SCOPE

Builder conditional note: `Each repository keeps its own independent context.` Runtime graph already shows separate repository units.

```text
Implementer / forge-agent   -> New, session A, Turn 1
Implementer / forge-console -> New, session B, Turn 1

later:
forge-agent                 -> Continued, session A, Turn 2
forge-console               -> Continued, session B, Turn 2
```

The two sessions have different deterministic repository keys and separate histories. No UI connector or aggregate turn numbering crosses repositories.

### Scenario D — resume failure

Invocation #3 shows `Continued · Turn 3`, session `Failed`, the context-specific failure, and `No fresh context was started.` NodeRun #3 and the WorkflowRun follow normal failure policy. Forge records the resume failure and does not issue provider `thread/start`.

### Scenario E — workflow edited after execution starts

WorkflowRun R1 snapshots `Continue in this workflow`. Editing the reusable node to `Fresh each invocation` does not alter R1, its graph badge, allocation, or later feedback-loop NodeRuns. WorkflowRun R2, created after the edit, snapshots Fresh and creates no reusable session. Task Execution exposes both as read-only snapshotted policy.

### Scenario F — historical execution

An old execution retains its graph, invocation selection, Prompt, Output, Failure, and timestamps. It has no context badge. Node details says `Context information was not recorded for this execution.` It does not label old repeated invocations New, Continued, or verified Fresh and never exposes guessed provider/session IDs.

## 15. Major alternatives rejected

- **Select or segmented control in Builder:** compact but hides the descriptions needed to understand a consequential cross-invocation behavior.
- **`Memory: On/Off` switch:** does not state scope, lifetime, or whether the first invocation is fresh; it encourages an incorrect cross-workflow mental model.
- **Cards or another modal:** too visually heavy for a two-choice property and inconsistent with the current compact modal hierarchy.
- **Badge on every node (`Fresh` and `Context`):** makes the default dominate large graphs. Only surprising opt-in behavior needs a marker.
- **Session nodes/edges or graph-colored histories:** confuses execution topology with conversational continuity and scales poorly with PER_SCOPE projection.
- **Replace the Invocation selector with a session navigator:** breaks a working NodeRun selection model and makes fresh invocations second-class.
- **Put provider IDs in normal details:** leaks infrastructure vocabulary and does not explain user semantics.
- **Store provider conversation ID on Workflow Node:** would reuse runtime identity across WorkflowRuns, violate snapshot isolation, and contaminate a reusable definition.
- **Put only provider conversation ID on NodeRun:** duplicates ownership, cannot model lifecycle/single-writer state cleanly, and makes grouping depend on provider infrastructure.
- **Silent resume-to-fresh fallback:** can produce incorrect agent behavior while appearing successful and directly contradicts the audited protocol contract.

## 16. Implementation-review checklist

Phase 1B is ready to plan when reviewers agree that:

- `Context`, radio copy, `↻ Context`, `Fresh`/`New`/`Continued`, and lifecycle labels are final;
- fresh records do not create Forge durable session rows;
- policy is snapshotted through all three node layers;
- session uniqueness and repository nullability enforce the declared scope;
- each reusable NodeRun has exactly one Forge turn and sequence;
- single-writer ownership works across workers/processes;
- start/resume/turn identity persistence order matches the Phase 0 audit;
- resume failure is terminal with no start fallback;
- Codex `0.153.2` completion behavior is contract-tested before production enablement;
- legacy UI never invents context history;
- future Activity and controls do not enter Phase 1B.
