# Manual Node roadmap review and phase 6

## Merged foundation

- Phase 1 / PR #133: NodeType, nullable manual metadata, conditional DB constraints, immutable snapshot; backfill AGENT without permanent DB/JPA defaults.
- Phase 2 / PR #134: WAITING_FOR_MANUAL, separate manual lifecycle, active-state/cancellation/restart behavior without agent sessions/workspace/executor.
- Phase 3 / PR #135: transactional manual-selection, idempotence/conflict, existing completion/routing, Nexus proxy and Console API; direct layer unit coverage.
- Phase 4 / PR #136: Builder Manual palette/preset, read-only ports and type-aware save/reload.
- Phase 5 / PR #137: Task Execution buttons from runtime snapshot, selected result, typed errors and stale-response protection.

## Phase 6 scope

One PR, then stop for external review/fixes. Existing implementation is exercised before considering any production change.

1. Forge Agent persisted complete graph: Task Input → Implementer → Manual; Retry → Implementer; Continue → Reviewer → Task Output. Use real HTTP commands, PostgreSQL repositories, lifecycle and routing. Only external Agent execution/workspace are deterministic test doubles.
2. Assert normal completion/result, new invocation/frame on loop, second waiting Manual, one downstream activation under simultaneous selections, and cancellation without provider cancellation. Assert no Manual sessions, turns or workspace/executor calls.
3. Restart full application contexts and datasource connections against the same PostgreSQL database. Cover WAITING persistence and decision committed before routing, then continue by actual HTTP.
4. Nexus sequential GET waiting → POST selection contract must preserve snapshot/decision/downstream resolution and invocation fields through the real proxy stack with stubbed upstream.
5. Console test uses the actual TaskExecutionView, API and HTTP client with a fake fetch transport: waiting render → click runtime output UUID → exact HTTP request → selected and downstream response render.
6. Run focused Agent tests, full Agent/Nexus verifies, and Console tests/typecheck/build. Record the exact boundaries of test doubles; no claim of live-provider or deployed cross-service E2E.

## Phase 7 remains separate

Final production-schema migration audit, Agent-only regressions, immutable runtime source guarantees, no Manual Agent sessions, repeated-request routing guarantees, crash recovery, Manual Task Output and execution-budget bounds, and final full service validation. Existing tests support these checks but do not replace the final audit.

No MCP, fake Manual Agent, executor registry, SystemNode framework, special action semantics or separate routing engine.

## Verified outcome

`REAL_MANUAL_NODE_E2E_PASS` for the persisted runtime scope described above.

- ForgeAgentManualFlowIT: 5 tests, zero failures/errors.
- ForgeAgentManualRestartIT: 2 tests, zero failures/errors; also executed by full failsafe verify.
- Full Forge Agent verify and full Nexus verify: exit 0.
- Console: 541 tests passed; typecheck/build: exit 0.
- Independent code review: ACCEPT. Production implementation unchanged.

The marker refers to real PostgreSQL workflow/node persistence and production routing, including full application-context restart. It does not imply live external-provider execution or a deployed browser-to-Nexus-to-Agent system test.
