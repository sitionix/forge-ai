# Dialogue node

Dialogue pauses one workflow invocation for a persisted conversation with its assigned project agent. Add **Dialogue · agent name** in the workflow palette, connect its input and outcomes, and save the workflow. It runs once globally and uses a separate provider conversation for each invocation; a REWORK loop starts a new conversation.

Open the node in task execution to read its history, answer questions, and inspect the working draft. Enter inserts a line; Ctrl+Enter or ⌘+Enter sends. **Prepare summary** requests a reviewed version of the draft. The workflow stays RUNNING while waiting for a person, without retaining a provider lease or MCP grant.

| Outcome | Requirement | Effect |
| --- | --- | --- |
| ACCEPT | Current SUMMARY, valid draft, readyForReview, no blocking questions | Routes the exact accepted result through the ACCEPT port. |
| REWORK | Current SUMMARY with a valid draft | Routes through REWORK; unresolved questions remain in the saved summary. |
| DEFER | Current SUMMARY with a valid draft | Routes through DEFER with the current draft and questions. |

Exactly one ACCEPT output is required. REWORK and DEFER are optional and unique. Editing a port changes its label and description independently of its typed outcome. Dialogue only supports GLOBAL execution and `DIALOGUE_WITHIN_NODE_RUN` context; its target must be an agent in the same project. Existing working-repository selection and project tool access apply.

Sending another reply invalidates the previous summary. Another tab's edit produces a revision conflict; Console reloads the conversation and preserves the unsent reply. An ambiguous network failure exposes **Retry request**, which uses the original UUID and payload rather than submitting another provider turn. History, revisions and accepted results persist across reloads and application restarts. Historical invocations are read-only.

| Dialogue state | Meaning |
| --- | --- |
| INITIALIZING | The invocation has been created. |
| RUNNING | An INITIAL, CHAT or SUMMARY turn is queued or executing. |
| AWAITING_REPLY | The agent returned a working draft or questions; the user can reply or request a summary. |
| AWAITING_REVIEW | The current summary is available for explicit outcome selection. |
| COMPLETED | An outcome was committed; normal completion routing proceeds. |
| FAILED | Execution or contract validation failed, or recovery could not safely confirm a dispatched turn. |
| CANCELLED | The owning workflow was stopped. |

The graph's node remains `WAITING_FOR_DIALOGUE` between turns. INITIAL, CHAT and SUMMARY are internal turns of that one NodeRun, not additional graph invocations. Agent activity is retrieved using the exact execution turn ID. The context reset action is forbidden for Dialogue.

## HTTP contracts

Console uses Nexus at `/api/v1/infrastructure/agents/workflow-runs/{runId}/node-runs/{nodeRunId}/dialogue`. Agent's corresponding root is `/api/v1/workflow-runs/{runId}/node-runs/{nodeRunId}/dialogue`.

| Route | Request | Response |
| --- | --- | --- |
| GET root | — | State, revision, latest structured reply, active turn, completion and first message page. |
| GET `/messages` | `afterSequence=0&limit=100` | Ordered messages, nextSequence, hasMore. Limit 1–200. |
| POST `/messages` | requestId UUID, expectedRevision, text | 202 newly queued; 200 identical retry. |
| POST `/summary` | requestId UUID, expectedRevision | 202 newly queued; 200 identical retry. |
| POST `/complete` | requestId UUID, expectedRevision, summaryRevisionId UUID, outputPortId UUID | 200 committed completion or identical retry. |

Every mutation requires `expectedRevision`. Reusing a UUID with a different payload is a conflict. Identical retries are deduplicated before checking the old revision. A stale revision or summary is rejected with a typed 409; wrong workflow ownership is 404. Nexus preserves upstream error codes.

The provider reply contains `message`, `draft`, `questions`, `decisions`, `sources` and `readyForReview`. The draft is checked against the agent's snapshotted business JSON Schema; external schema loading is disabled. SUMMARY requires a non-null draft. Decisions reference actual USER message UUIDs. The model does not select an output port.

Accepted node output:

```json
{
  "contractVersion": 1,
  "result": { "business": "draft matching the agent schema" },
  "dialogue": {
    "disposition": "ACCEPT",
    "summaryRevisionId": "UUID",
    "revision": 12,
    "acceptedAt": "ISO-8601 instant",
    "questions": [],
    "decisions": [],
    "sources": [],
    "transcript": { "nodeRunId": "UUID", "uri": "/api/v1/workflow-runs/.../node-runs/.../dialogue/messages" }
  }
}
```

Downstream agents consume this envelope through existing input contributions. Completion and session closure commit atomically; the ordinary completion worker routes after commit and can resume routing after restart.

## Limits and recovery

Defaults: `forge.agent.dialogue.max-dialogue-turns-per-node-run=100`, `forge.agent.dialogue.max-message-code-points=16000`, `forge.agent.dialogue.max-initial-input-code-points=128000`. INITIAL and SUMMARY consume the same turn budget as CHAT. Reserve a turn for the final summary. Empty or whitespace-only replies are rejected; emoji count as Unicode code points and newlines are preserved.

Provider execution reuses the existing Codex client, session callbacks, heartbeat, workspace resolver and MCP gateway. Durable sessions accept the audited Codex CLI versions 0.157.0, 0.160.0 and 0.160.1; an unknown version or a version change inside a session is rejected. Each turn evaluates current project/tool permissions and receives a new grant. No grant remains between replies. Stopping a workflow cancels/fences active execution and keeps its transcript; queued and waiting dialogue need no provider cancellation handle.

A restart preserves waiting history and queued commands. A provider dispatch with uncertain completion is conservatively failed and is never replayed automatically or moved to a fresh conversation. A late callback cannot change a cancelled, failed or newer revision.

This first version has polling, text messages and explicit outcomes. Attachments, token streaming and Notion board synchronization are separate work.

## Reviewing in Console

Prepare summary creates a version that can be reviewed. Choosing an outcome opens an inline confirmation showing the exact draft, output name, description and disposition. Confirm outcome submits that version; if polling receives a newer revision, the confirmation is invalidated and must be opened again. Completed nodes show the final draft, chosen output, summary revision and completion time after reload.

The activity selector switches between persisted provider turns and keeps the selected turn during polling. Conversation messages remain in the Dialogue panel; provider events for the selected turn appear in Agent activity.

Dialogue uses a provider-compatible reply schema and validates the returned reply against the full server contract. The internal `uniqueItems` constraint on decision message references is enforced on the server because strict Structured Outputs does not support it. User business schemas retain their original constraints.
