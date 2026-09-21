# Manual Node phase 7 — final hardening audit

Phases 1–6 are merged as PRs #133–#138. This final phase adds execution-budget integration coverage and records the audit. Work stays on a new branch in the current directory; submit one PR and stop for external review. No new runtime architecture or migration is needed.

## Acceptance evidence

All Agent integration classes below live in `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/`.

| Requirement | Evidence |
| --- | --- |
| Upgrade the pre-feature schema without changing historical Agent data | `ForgeAgentRuntimeMigrationIT.nodeTypeMigrationDefaultsExistingRowsToAgentAndKeepsAgentConstraints` creates V33 historical rows, migrates to current, and compares old columns. V34 backfills AGENT, enforces NOT NULL/type and conditional metadata constraints, then removes all three temporary defaults. V35 permits waiting only for MANUAL. This validates the repository's V33 baseline, not a live production database. |
| Persistence writes explicitly supply nodeType | Three JPA entities have no AGENT initializer; repository mappers write `.nodeType().name()` and read `NodeType.valueOf(...)`. Legacy compatibility remains in API/domain. |
| Existing Agent behavior | Full Agent verify retains the existing Agent-only lifecycle, session, context, routing and migration suites; `ForgeAgentManualRuntimeIT.existingAgentExecutionRoutesToManualAndStopsThere` also covers the boundary between both types. |
| No Manual Agent sessions, executor or workspace | Manual runtime/selection tests verify no interactions and no session rows. `ForgeAgentManualFlowIT` checks exact Agent-only execution/workspace calls and zero Manual sessions/turns in a mixed graph. |
| Immutable runtime outputs and stale template safety | `ForgeAgentManualNodeFoundationIT.manualTypeAndNullAgentFieldsSurvivePersistenceAndTemplateChanges`; `ForgeAgentManualSelectionIT.immutableSnapshotSuppliesAllowedPortsAndAuditName`. Selection reads the snapshotted port repository, including audit name. |
| HTTP repetition and concurrent decisions route once | `ForgeAgentManualSelectionIT.selectionUsesExistingRoutingAndRepeatedRequestDoesNotActivateTwice`, `concurrentSelectionsSerializeAndRouteOnce`; mixed graph `ForgeAgentManualFlowIT.simultaneousSelectionsActivateExactlyOneAgentBranch`. |
| Waiting and commit-before-routing survive restart | `ForgeAgentManualRestartIT.fullApplicationRestartPreservesManualWaitAndRecoversUnroutedDecision` closes the full application context and starts a new context/datasource on the same PostgreSQL container, covering both persisted states. |
| Manual output can be Task Output | `ForgeAgentManualSelectionIT.manualOutputCanCompleteWorkflowAndSelectionRemainsIdempotentAfterCompletion`; restart integration also ends with a Manual Task Output. |
| Loops remain bounded by the existing budget | New `ForgeAgentManualBudgetIT.manualLoopFailsAtExistingExecutionBudgetWithoutCreatingAnotherInvocation`: real PostgreSQL + MockMvc HTTP endpoint, A → B → A, limit 3. The next selection fails the workflow with `WORKFLOW_EXECUTION_BUDGET_EXCEEDED`; replay and worker polls cannot create a fourth invocation. No Agent definitions, sessions or provider execution. |
| Builder and execution UI | Full Console suite includes Manual creation/save/reload/read-only editing and runtime snapshot buttons, exact request IDs, pending/error handling, stale-response protection, selected output and downstream rendering. |
| Nexus thin proxy contract | Full Nexus verify includes sequential waiting GET → selection POST with runtime snapshot ports and downstream connection resolutions; direct controller/use-case/client adapter coverage remains. |

## Verification

Run the focused budget test, then the complete service checks:

```sh
mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am -Dtest=ForgeAgentManualBudgetIT -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
```

In the Console directory: `npm test`, `npm run typecheck`, `npm run build`.

Tests use real runtime persistence/routing and full-context restart. External Agent execution in mixed-flow tests is deterministic; Nexus upstream and Console fetch are contract test doubles. This is not a claim of live-provider or deployed cross-service testing.

## Verified outcome

- Focused Manual budget test: 1 test, zero failures/errors/skips; also executed successfully by full Agent failsafe verify.
- Full Forge Agent verify: exit 0. Existing environment-gated skips remain; no production code changed.
- Full Forge Nexus verify: exit 0, 246 tests with no failures/errors/skips.
- Console: 541 tests passed; typecheck and build: exit 0.
- Independent schema/snapshot audit and code review: no blocking findings.

The remaining roadmap checkpoint is external review and merge of this phase 7 PR.
