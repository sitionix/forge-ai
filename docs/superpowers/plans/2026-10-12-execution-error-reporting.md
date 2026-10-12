# Execution error reporting implementation plan

**Goal:** Preserve safe upstream failure reasons and codes through Forge's existing execution events and node/dialogue failures.

**Architecture:** Use the existing exception, NodeRunFailure and event payload structures. Share the existing text redaction rules with terminal failure summaries; retain only explicit diagnostic fields rather than raw provider/tool payloads. No statuses, tables, retries, routing or authorization changes.

**Tech stack:** Java 21, Jackson, JUnit/Mockito, console JavaScript/Vitest.

## Evidence and boundaries

- Recorded run `e6cd050f-3ac8-4b28-a714-a1ee963dfdd9`: architect returned domain BLOCKED successfully; dialogue execution failed at 11:44:52 UTC with generic messages and provider OTHER, willRetry=false. Its original upstream cause is unrecoverable from this evidence.
- The 12:02 browser sign-in rejection and 12:11 runtime authState ERROR are separate observations. A browserless provider-status request returning LLM_BROWSER_DENIED is expected caller denial, not evidence of this execution's cause.
- Provider notification/turn error -> CodexTurnStateTracker currently drops upstream code -> CodexTransportException -> CodexAgentExecutor masks MCP-enabled failures -> DialogueTurnLifecycle drops reason.
- CodexExecutionEventObserver -> CodexAgentExecutionEventMapper masks diagnostics under MCP mode -> AgentExecutionEventRecorder -> Postgres event payload -> ForgeAgentApiMapper passes payload and session/turn/node IDs -> console activity renders message only; terminal turn message is hidden.
- AGENTS.md requires automatically numbered feature/SITIONIX branches. Local and live remote branches inspected; worktree uses feature/SITIONIX-161 from main 63565b89. No repository .agents/skills directory exists.

## Scope and acceptance

- Preserve upstream code when supplied; keep UNKNOWN/AGENT_EXECUTOR_FAILED when absent. MCP mode alone never establishes MCP origin.
- Preserve bounded safe message, explicit provider origin/turn stage and retryability in events. Existing provider event key, session/turn/node IDs and approved server/tool names provide correlation.
- Explicit MCP boundary exceptions retain their safe reason; approved MCP tool errors retain error.message/error.code only, never arguments/results.
- Installation authorization and output validation keep their existing typed codes. Execution FAILED remains separate from domain output BLOCKED.
- Redact authorization schemes, token/key/password/secret assignments (including JSON), URL credentials/query secrets and private keys before persistence or diagnostic logging. Limit terminal summaries to 2048 characters; events keep existing byte bounds.
- Preserve the primary execution error if grant cleanup also fails, reporting cleanup through safe correlated metadata. No cleanup or execution routing changes.
- No deploy, restart, Forge start, credentials/security changes, database schemas or physical commands.

## Implementation and test steps

- [x] Add failing regression tests in CodexAgentExecutionEventMapperTest and CodexTurnStateTrackerTest for MCP-enabled provider errors, actual tool errors, auth rejection, future/unknown codes, retryability and redaction.
- [x] Extend existing text sanitizer with shared domain text redaction, safe bounded NodeRunFailure summaries, and code preservation in existing Codex exceptions/tracker. Remove mode-based masking in executor/mapper; preserve primary failure during cleanup.
- [x] Add lifecycle/worker/executor regressions proving safe reasons/codes survive and stale identity, recovery and successful routing remain unchanged.
- [x] Add console rendering tests for failure reason, diagnostic metadata, retry true/false and HTML escaping; render these in existing activity rows.
- [x] Run targeted red/green tests, then Forge agent verification and console tests/typecheck/build. Default integration run encountered Docker API 1.32 rejection; process-only `-Dapi.version=1.44` resolved it, and full verify passed. Console: 761 passing tests, typecheck and build passed. Native/live tests remain environment-gated.
- [ ] Inspect final diff, commit, publish branch and create DRAFT PR if existing publication access permits. Never merge. Independent exact-SHA review and later regression review belong to the parent workflow.

## Remaining unknown

The real 11:44:52 upstream failure cannot be reconstructed; this fix makes subsequent supported error frames inspectable without claiming auth or MCP caused that historical failure.
