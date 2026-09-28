# MCP Stage 5 — Settings / Custom MCP evidence

Date: 2026-09-28. Branch: `feature/SITIONIX-152`, based on merged Stage 4 main `0ecef20364124a6f27889de2b48e1b822b77106e`.

Scope: approved [Stage 5 design](../superpowers/specs/2026-09-28-mcp-stage-5-design.md) and [inline plan](../superpowers/plans/2026-09-28-mcp-stage-5.md). No Stage 6, catalog cards, OAuth, new frontend framework, transport stack, schema, production setup or sandbox permission change.

## Result and release boundary

Settings → Integrations now shows saved global connections and supports Custom HTTP creation, Test connection, tool/project permissions, explicit Enable/Disable, Edit and confirmed Remove through the existing typed Nexus routes. Credentials are write-only. New connections remain disabled with no approved tools or selected projects.

**Milestone A is NOT_VERIFIED. Keep the PR draft.** The joined fixture uses actual Console, Nexus, Agent management, PostgreSQL, MCP SDK, production gateway and installed native Codex. It substitutes the trusted execution lease and privileged verifier and invokes Codex directly with disposable configuration. It does not exercise the normal Agent worker → managed systemd launcher → Forge-generated config path, full Agent/Forge restart or privileged mount isolation. These are acceptance prerequisites, not successful mocked deployment checks.

## Exact changed-file map

Paths below are relative to the repository root. No Maven/npm dependency changes.

| File | Responsibility |
|---|---|
| `services/forge-console/src/operator/settings.html` | Global Integrations shell, login, details and native Custom dialog |
| `services/forge-console/src/operator/settings-page.js` | Session, list/details and explicit lifecycle actions; cancellation and page ownership |
| `services/forge-console/src/operator/settings-page.d.ts` | Existing Console declaration pattern |
| `services/forge-console/src/operator/mcp-api.js` | Context-aware existing management routes, private CSRF, fixed safe errors, no mutation retry |
| `services/forge-console/src/operator/mcp-api.d.ts` | Connection, command, approval and inventory types |
| `services/forge-console/src/operator/mcp-connections-view.js` | Text-safe metadata/details, factual derived labels |
| `services/forge-console/src/operator/mcp-connections-view.d.ts` | View contracts |
| `services/forge-console/src/operator/mcp-connection-form.js` | Saved-resource create/test, credential cleanup, access editing, ambiguous-save reconciliation |
| `services/forge-console/src/operator/mcp-connection-form.d.ts` | Form lifecycle contracts |
| `services/forge-console/src/operator/operator-bootstrap.js` | Bottom Settings entry, existing router registration and BFCache remount |
| `services/forge-console/src/operator/operator-ui.css` | Existing styling, dialog and compact navigation layout |
| `services/forge-console/tests/mcp-api.test.ts` | Routes/CSRF, external auth vs operator expiry, payload/cause canaries and stale session |
| `services/forge-console/tests/settings-page.test.ts` | Global navigation, states/details, actions, logout and late reads |
| `services/forge-console/tests/mcp-connection-form.test.ts` | Creation/access, credentials, cancellation, duplicate prevention, uncertain save and dialog lifecycle |
| `services/forge-console/scripts/mcp-settings-browser-smoke.mjs` | Real Chrome, built assets; explicit stub mode or disposable actual Nexus mode |
| `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/security/OperatorSessionController.java` | Add fixed `csrfHeader=X-Forge-CSRF` to MCP-only canonical session |
| `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/security/OperatorPublicRoutes.java` | Narrow Console asset paths, existing traversal guards |
| `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/remoteaccess/CombinedOperatorSessionController.java` | Canonical facade delegating to accepted Remote Access session owner |
| `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/remoteaccess/RemoteAccessSecurityConfiguration.java` | Exact combined canonical login/session matchers; unchanged role/CSRF owner |
| `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/remoteaccess/RemoteAccessBrowserFilter.java` | Exact combined POST login exception on nested dispatch; existing Host/Origin guards |
| `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/remoteaccess/RemoteAccessProxyErrors.java` | Include canonical facade in existing safe scoped advice |
| `services/forge-nexus/api-rest/src/test/java/com/sitionix/forgeai/api/security/OperatorSessionControllerTest.java` | CSRF header regression |
| `services/forge-nexus/api-rest/src/test/java/com/sitionix/forgeai/api/security/OperatorPublicRoutesTest.java` | Public assets vs protected/encoded/traversal paths |
| `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/NexusCombinedOperatorSessionIT.java` | Typed ForgeIT canonical read/logout sharing legacy session |
| `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/NexusCombinedOperatorHttpIT.java` | Actual canonical login/cookie/Origin/CSRF/logout and existing dispatch guards |
| `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/mcp/McpSettingsAcceptanceHttpTest.java` | Opt-in actual UI/backend/database/gateway/native CLI join; explicit substituted execution/OS boundary |
| `scripts/runtime/tests/stage5_codex_fixture.py` | Reuse disposable native fixture against actual Stage 5 gateway and issued grants |
| `scripts/runtime/tests/stage4_codex_fixture.py` | Parameterize fixture version gate; Stage 4 default stays exactly 0.157.0 |
| `docs/superpowers/specs/2026-09-28-mcp-stage-5-design.md` | Approved scope/design |
| `docs/superpowers/plans/2026-09-28-mcp-stage-5.md` | Approved inline implementation plan |
| `docs/mcp-integrations/stage-5-evidence.md` | This factual evidence and remaining boundaries |

## Verified behavior

| Capability | Evidence | Result |
|---|---|---|
| Existing Remote Access login authorizes canonical session | Same Spring session/token in typed combined ForgeIT; no `OperatorSessionService` bean | PASS |
| Canonical login/logout with same combined owner | Actual Tomcat HTTP; one HttpOnly cookie, fixed CSRF header, Origin/missing/wrong CSRF denials, logout revokes access | PASS |
| MCP-only / Remote Access-only / combined auth | Focused canonical tests and full existing Nexus auth suites | PASS |
| Public shell does not open management | Asset route test, existing forward/include/async and encoded/matrix path tests | PASS |
| Saved global list and truthful labels | Console tests and actual browser/backend metadata reload | PASS |
| Disabled/deny-all creation and explicit permissions/enable | Console unit scenarios plus actual browser → typed Nexus → Agent → PostgreSQL | PASS |
| No duplicate create after probe failure or uncertain save | Form tests; saved ID retained, explicit metadata reconciliation, no automatic mutation replay | PASS |
| Matching schema approvals only | Form selection tests; existing Agent persistence/probe regressions remain authoritative | PASS |
| Secret cleanup and safe errors | API canaries, write-only replacement/cancel/logout tests, Chrome DOM/storage/URL scan | PASS |
| Application log redaction in joined fixture | Captured Agent/framework logs and private Nexus log exclude synthetic operator/service credentials and issued runtime grants | PASS |
| Compact navigation does not obscure Settings | Real Chrome rectangle assertion at 780 px, desktop screenshot at 1280 px | PASS |
| Actual native calls | Codex 0.158.0 fresh/resume, two distinct real gateway grants; local upstream echo counter exactly 2 | PASS, direct CLI fixture only |
| Disable from UI revokes actual runtime dispatch | Actual HTTP 401 with previous grant, counter stays 2 | PASS |
| Forbidden project/no eligible MCP selection | Production gateway/selection services with explicitly synthetic execution lease | PASS at policy boundary |
| Saved connection reload | New PostgreSQL adapter/identity instances read identical persisted metadata | PASS; not an Agent restart |
| Nexus restart | Child Nexus process restarted; browser uses same persisted Agent connection and disables it | PASS; not full Forge restart |

## Commands and results

Focused session regression:

```bash
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am \
  -Dtest=OperatorSessionControllerTest,OperatorPublicRoutesTest \
  -Dit.test=NexusOperatorSessionIT,NexusCombinedOperatorSessionIT,NexusCombinedOperatorHttpIT \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
```

PASS: 3 focused unit tests and 29 selected ForgeIT/HTTP tests, zero skips. Initial unit RED: missing header and public Console routes. The plan's `test` invocation for `*IT` could not discover ForgeIT because this module intentionally excludes ForgeIT from Surefire; actual IT verification used the accepted Failsafe `verify` path.

Console:

```bash
npm --prefix services/forge-console run typecheck
npm --prefix services/forge-console test
npm --prefix services/forge-console run build
node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
```

PASS: typecheck/build; 27 files, 614 tests; real Chrome default stub flow. Browser replacement/cancel is stub-backed, not a live provider claim. Native close-event and compact-navigation failures were reproduced before fixes. No-auth create payload regression failed on `KEEP` before omitting update-only credential action.

Joined actual disposable fixture (build Nexus jar with current Console assets first):

```bash
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am \
  -Dtest=McpSettingsAcceptanceHttpTest -Dforge.codex.stage5-e2e=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

PASS: 1 test, zero skips. Actual disposable PostgreSQL with existing migrations, actual Nexus jar, actual Agent controllers/services/cipher/SDK/gateway, real Chrome, no-auth HTTP echo and synthetic model. Execution session/node/workflow repositories and privileged `RuntimeBoundaryVerifier` are mocked explicitly. Native CLI config is crafted only in disposable fixture HOME/CODEX_HOME. It is not a normal Agent worker execution. Strict Stage 4 version gate first rejected installed 0.158.0; Stage 5 fixture explicitly tests 0.158.0 while the existing Stage 4 default remains 0.157.0. Neither a production Codex version nor personal config was changed.

Full regression:

```bash
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v
git diff --check
```

- Agent: PASS, 1427 tests, zero failures/errors, 10 skips across 171 test classes. Skips by opt-in condition: `forge.codex.live-recovery-e2e` (2), `forge.codex.stage4-e2e` (1), `forge.codex.stage5-e2e` (1; explicitly run separately), `forge.remote-access.live-execution` (1), and `forge.codex.live-cancellation-e2e` / `live-shared-e2e` / `live-reset-e2e` / `live-iteration-e2e` / `live-session-e2e` (1 each). Default full verify does not execute native opt-in fixtures.
- Nexus: PASS, 381 tests, zero failures/errors/skips across 64 test classes.
- Runtime Python: PASS, 20 tests.
- Whitespace check: PASS.
- Dependency analysis: NOT_APPLICABLE; no dependency/POM changes.
- Fresh PR CI: NOT_VERIFIED until exact-head run completes.

## Remaining prerequisites / NOT_VERIFIED

- `MILESTONE_A`: normal Agent-owned workflow after UI create; actual managed launcher/credential-file/config injection; Agent/Forge restart followed by fresh runtime grant without manual MCP config.
- Root-systemd mount/process/reader sandbox isolation and management-secret unreadability. Prior [Stage 4 evidence](stage-4-evidence.md) is historical and was not rerun as privileged Stage 5 deployment evidence.
- Native joined fixture on Stage 4's pinned 0.157.0 CLI: NOT_VERIFIED here; installed disposable probe used 0.158.0.
- Production deployment/provisioning; existing `forge.mcp.enabled` and protected management files remain prerequisites. No default feature flag was changed.
- `LIVE_PROVIDER = NOT_VERIFIED`; all MCP/model endpoints in the acceptance fixture are local synthetic fixtures. `LIVE_REGISTRY = NOT_VERIFIED`; Stage 5 did not use catalog calls.
- Actual bearer/secret-header Custom UI joined to a provider: NOT_VERIFIED; browser stub verifies write-only UI behavior, existing Stage 1/2 backend suites verify credential boundaries separately.

No historical OS probe, mocked execution lease, repository reload, Nexus-only restart or green default CI should be presented as full Milestone A acceptance.
