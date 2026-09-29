# Stage 7 — direct catalog Connect evidence

Date: 2026-09-29. Branch: `feature/SITIONIX-156`.
Scope: the approved catalog Connect spec/plan dated 2026-09-29. Existing Custom,
Registry page/cache, Stage 3/4 gateway and Stage 6 token lifecycle are preserved.
No PR metadata/comments/reviews/merge actions are part of this work.

## Implementation map

- Agent domain: `McpAuthenticationMetadata`, `McpAuthenticationDiscovery`,
  `McpOAuthClientRegistration`, `McpOAuthClientRegistrationProvider`, `McpConnectResult`.
- Agent local `mcp/oauth`: `McpOAuthDiscoveryConfiguration`,
  `McpOAuthDiscoveryProperties`, `McpOAuthRegistrationProperties`,
  `McpOAuthMetadataHttpClient`, `SpringMcpAuthenticationDiscovery`,
  `SpringMcpOAuthClientRegistrationProvider`.
- Agent application/API: `McpConnectService`, `McpConnectController`,
  scoped `McpConnectionsExceptionHandler`; boot `AgentMcpProtectedConfiguration`
  includes optional configured client-secret files in the existing runtime boundary.
- Nexus domain/application: `McpConnectCommand`, `McpConnectResult`, existing
  `ManageAgentMcpConnections`/`ForgeAgentMcpClient`/`AgentMcpConnectionsUseCase`.
- Nexus agent-client: existing `ForgeAgentHttpClient`, `ForgeAgentMcpClientAdapter`,
  `McpClientMapper`; new typed `McpConnectInbound`/`McpConnectOutbound`.
  `ForgeAgentClientCallExecutor` is unchanged.
- Nexus API: existing `ForgeAiMcpOAuthController` owns typed
  `POST /api/v1/infrastructure/agents/integrations/mcp/connect`, generated browser
  binding, Origin/content-type checks, HttpOnly transaction cookie and no-store.
  Existing callback/start/cancel paths remain. Browser body is name/endpoint only.
- Console: `mcp-catalog.js` renders explicit row Connect; `mcp-catalog-connect.js`
  owns catalog preparation/Test/reconciliation; `mcp-oauth-flow.js` shares existing
  popup/result/cancel lifecycle; `mcp-api.js` uses typed same-origin Connect.
  `settings-page.js` keeps Add custom MCP separate; `openCreate` has no catalog
  prefill branch. Existing Console `.button` styles and navigation are reused.

## Verified contracts

Discovery reads the unauthenticated challenge and standard protected-resource/
authorization-server metadata. Exact issuer/resource, S256, endpoint policy,
redirect rejection, NO_PROXY, existing SSL context, response cap and a shared
monotonic preparation deadline are enforced. Issuer-bound installed client has
priority, then configured supported HTTPS CIMD, then advertised DCR. No Forge
credential/connection token is sent to discovery/registration.

External preparation finishes before existing connection/start persistence. New
connections are disabled with SELECTED empty project access and no tool approvals.
DCR credentials use the existing AES-GCM encrypted connection row, not another
schema/cache. The authoritative connection is re-read after OAuth start.

Console opens the popup synchronously, detaches opener, validates authorization,
uses the existing safe BroadcastChannel result, then authoritative GET → existing
Test → list refresh. No Custom form/projects read before redirect, no automatic
Enable/Disable/approve. No-auth closes the unused popup and checks the saved
connection. Template/manual-credential guidance stays local to the row. Popup
reopen reuses the same attempt; double-click/cancel/uncertain outcome does not
replay Connect. Global sidebar/search/close remain available.

## Regression evidence and rulings

- Task 1 missing discovery interfaces/tests were RED before implementation.
  Open response regression initially took 5.004s: Spring JDK response close drains
  the body. Standard Spring Simple request factory explicitly closes the challenge
  body, using existing SSL context, NO_PROXY and redirect prohibition. No custom
  MCP stream transport was introduced.
- Preparation20s plus at most one socket read5s fits25s below Nexus30s. Incompatible
  configuration fails visibly; this is a management preparation invariant, not
  a universal tool-call ceiling.
- Task 2 registration/orchestration/controller tests were RED before production
  interfaces. Duplicate issuer and invalid DCR identity/expiry/auth/callback tests
  fail closed. Optional installation secret paths use the existing UID verifier.
- Task 3 ForgeIT caught Spring resolver DEBUG printing the raw transport message.
  Production level for `ExceptionHandlerExceptionResolver` is WARN; scoped safe
  handler WARNs and internal transport causes are preserved. Cost: resolver DEBUG
  details are suppressed for other routes too. No executor/error framework added.
- Task 4 six direct Connect tests were RED on the old Custom-prefill flow. An
  additional RED proved an uncertain OAuth failure could replay creation; the
  catalog retains that uncertain identity until operator reconciliation/reload.
- Joined regression isolated503 before redirect to header-only401 cleanup.
  `empty401BodyPreservesBearerChallengeAndDiscoversMetadata` and
  `empty404MetadataResponseStillUsesTheStandardRootFallback` were RED on the old
  helper, then GREEN: Spring's empty error stream cannot erase an already-observed
  auth/not-found status. Readable bodies are still closed; other failures persist.
- An initial joined attempt hit the existing asynchronous Enable UI condition
  before the new catalog slice; later runs completed that Stage 5/6 slice. No
  timeout increase or production policy change was used to hide that observation.

## Executed verification

| Check | Status | Evidence |
|---|---|---|
| Discovery/registration/config focused | PASS | 17 + 9 + 1 tests; zero failures/errors/skips. Header-only401/404 RED→GREEN. |
| Nexus focused unit + ForgeIT | PASS | Mapper/adapter/controller/use-case tests; 7 catalog + 2 existing OAuth IT. Origin/unknown input local denial, generated cookie binding,409/500 preservation, malformed502, safe unavailable503/canaries. |
| Console typecheck/tests/build | PASS | 667 tests, zero failures; `tsc --noEmit` and production build exit0. |
| Joined browser/runtime regression | PASS | Opt-in `McpSettingsAcceptanceHttpTest`, 1 test/0skips: actual built Console, Chrome, Nexus, Agent, disposable PostgreSQL, fakeAS/DCR. Two distinct authorization identities, no automatic permissions/Enable, disabled grants denied. Existing native gateway/refresh/reconnect/revocation slice passes. |
| Full Agent verify | PASS | 1505 tests; zero failures/errors, 10 explicit opt-in skips. Joined acceptance above was separately executed. |
| Full Nexus verify | PASS | 374 tests, zero failures/errors/skips. |
| Runtime Python suite | PASS | 47 tests, exit0. |
| Agent local/Nexus agent-client dependency analysis | PASS | Both BUILD SUCCESS; no new POM/dependency additions. Existing/transitive declaration warnings remain (below). |
| `git diff --check` | PASS | Exit0. |
| Normal local `just start` | NOT_VERIFIED | Pending normal provisioning/restart; no test-only enable switch. |
| Normal :9099 Settings/catalog browser | NOT_VERIFIED | Pending read-only smoke; user's existing connection must be preserved. |
| Fresh exact-SHA CI | NOT_VERIFIED | Pending feature-branch workflow; no PR mutation. |
| LIVE_PROVIDER (GitHub OAuth) | NOT_VERIFIED | Registered Forge-owned App/callback/protected installation credentials were not supplied. |
| Public CIMD deployment | NOT_VERIFIED | Controlled local TLS fixture verifies contract; no provider-accessible production HTTPS publication. |
| Fresh privileged OS UID/systemd probes | NOT_VERIFIED | Joined fixture substitutes verifier/execution lease. Historical Stage 3/4/6 probes are not repeated claims. |

Commands executed (from repository root):

```bash
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local -am -Dtest=SpringMcpAuthenticationDiscoveryTest,McpOAuthClientRegistrationProviderTest,McpOAuthDiscoveryConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am -Dtest=McpConnectClientTest,McpCatalogConnectControllerTest,McpOAuthCallbackTest,ForgeAgentMcpClientAdapterTest,McpClientMapperTest,AgentMcpConnectionsUseCaseTest -Dit.test=NexusMcpCatalogConnectIT,NexusMcpOAuthIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify
mvn -B -ntp -Dapi.version=1.44 -Dforge.codex.stage5-e2e=true -pl services/forge-agent/boot -am -Dtest=McpSettingsAcceptanceHttpTest -Dsurefire.failIfNoSpecifiedTests=false test
npm --prefix services/forge-console run typecheck
npm --prefix services/forge-console test
npm --prefix services/forge-console run build
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local -am dependency:analyze
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/clients/agent-client -am dependency:analyze
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v
git diff --check
```

Dependency analysis is not a zero-warning claim. Among unchanged declarations:
Agent local reports unused boot-autoconfigure/junit-jupiter and transitive
Spring/Jackson/JUnit imports; Nexus client reports starter-test and transitive
Spring/Jackson/Mockito/JUnit imports. No added dependency stack or unrelated POM
cleanup is part of Stage 7.

No live-provider/normal runtime outcome is inferred from local stubs. Auth metadata
contract is pinned to the checked 2025-11-25 reference; roadmap2026-07-28 source
was unavailable, so that newer source/SDK protocol upgrade remains NOT_VERIFIED.
