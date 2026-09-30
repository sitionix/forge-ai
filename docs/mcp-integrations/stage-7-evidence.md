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

Automatic protected-resource, OAuth metadata, issuer and authorization/token/registration/revocation
URLs require HTTPS. HTTP is accepted only for explicitly allowlisted private
fixture/development addresses, using the existing endpoint allowlist. This does
not redesign the existing manual Custom OAuth path. GET400/405 performs one bounded
SDK-typed initialize POST to observe the auth challenge; a successful temporary
session receives DELETE (404 means gone;405 means server does not support client
termination). No tools/list or tool call is made by this fallback. The regular Test
still confirms the saved connection. Challenge scopes are authoritative; otherwise
protected-resource `scopes_supported` is used. Empty DCR scopes are omitted.

References: [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
and [MCP transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).

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

## Final independent review and one fix pass

Five Important findings, no Critical/Minor findings. All five new regression
checks were observed RED before the fix, then GREEN in the focused suite:

1. Public HTTP OAuth downgrade: authorization/token/registration/revocation targets
   rejected before registration or credential use. Explicit private HTTP fixtures remain supported.
2. POST-only/session-required servers: GET405/400 → SDK-typed initialize → no-auth
   success or401 metadata discovery. A temporary session is released with DELETE.
   Spring Simple factory must send bodyless DELETE without chunked output; this
   was isolated by its unexpected EOF, not hidden by a timeout increase.
3. Waiting popup: **Open sign-in** remains reachable after manually closing the
   provider window. Reopen uses the same transaction, one Connect POST; catalog
   close explicitly cancels. COOP does not trigger automatic cancellation.
4. Missing challenge scope uses validated protected-resource scopes. This corrects
   the earlier least-privilege ruling against the pinned contract; challenge scope
   still overrides metadata and installed-client scope ceilings stay enforced.
5. DCR without scopes omits the optional field rather than sending `scope:""`.

The reviewer declined to classify the synchronous blank popup for no-auth as a
bug: it is the approved reservation that browsers require before async discovery,
then closes without redirect. Cost: a brief blank window for a no-auth connection.
Only one independent branch review and one implementation fix pass were used.
No extra review cycle or unrelated refactoring was introduced.

## Executed verification

| Check | Status | Evidence |
|---|---|---|
| Discovery/registration/config focused | PASS | 22 + 10 + 1 tests; zero failures/errors/skips. Header-only401/404 RED→GREEN. |
| Nexus focused unit + ForgeIT | PASS | Mapper/adapter/controller/use-case tests; 7 catalog + 2 existing OAuth IT. Origin/unknown input local denial, generated cookie binding,409/500 preservation, malformed502, safe unavailable503/canaries. |
| Console typecheck/tests/build | PASS | 668 tests, zero failures; `tsc --noEmit` and production build exit0. |
| Joined browser/runtime regression | PASS | Opt-in `McpSettingsAcceptanceHttpTest`, 1 test/0skips: actual built Console, Chrome, Nexus, Agent, disposable PostgreSQL, fakeAS/DCR. Two distinct authorization identities, GET405 → POST401 discovery, no automatic permissions/Enable, disabled grants denied. Existing native gateway/refresh/reconnect/revocation slice passes. |
| Full Agent verify | PASS | 1511 tests; zero failures/errors, 10 explicit opt-in skips. Joined acceptance above was separately executed. |
| Full Nexus verify | PASS | 374 tests, zero failures/errors/skips. |
| Runtime Python suite | PASS | 47 tests, exit0. |
| Agent local/Nexus agent-client dependency analysis | PASS | Both BUILD SUCCESS; no new POM/dependency additions. Existing/transitive declaration warnings remain (below). |
| `git diff --check` | PASS | Exit0. |
| Pre-restart Settings asset on old running process | FAIL | Two read-only requests timed out after5s; health and connection list responded200 (list empty). This is not acceptance of the new build; restart is pending. |
| Normal local `just start` | PASS | Interactive terminal authenticated successfully; normal just start exit0. Main knowledge/jarvis/Agent/Nexus/Postgres active; dedicated Remote Access units inactive. No test-only switch or replacement startup. |
| Normal :9099 Settings/catalog browser | PASS | Real Chrome, actual built assets and main Nexus: NORMAL_SETTINGS_EMPTY_BROWSER_PASS and NORMAL_SETTINGS_CATALOG_BROWSER_PASS. Global sidebar/Projects/active Settings, empty result, Add action, compact desktop/mobile catalog, one Connect, no Custom dialog. No HTTP stub or connection mutation. |
| Fresh exact-SHA CI | NOT_VERIFIED | Pending post-commit feature-branch workflow at evidence write time; final report will state observed exact-SHA CI status. Earlier298f9bda run36569717386 succeeded but does not verify this fix. No PR mutation. |
| LIVE_PROVIDER (GitHub OAuth) | NOT_VERIFIED | Registered Forge-owned App/callback/protected installation credentials were not supplied. |
| Public CIMD deployment | NOT_VERIFIED | Controlled local TLS fixture verifies contract; no provider-accessible production HTTPS publication. |
| Fresh privileged OS UID/systemd probes | NOT_VERIFIED | Joined fixture substitutes verifier/execution lease. Historical Stage 3/4/6 probes are not repeated claims. |

Normal-runtime details: main Nexus health200, main Agent healthy in `just status`,
Settings asset200, authenticated-free local MCP list200 with `[]`; saved list was
empty before and after, unchanged. Invalid `{}` to the normal Connect route with
correct Origin returns400, proving the new route exists without creating a
connection. Chrome remains on9099; global sidebar and Projects are visible.
The pre-restart asset timeout above resolved after the normal restart. No cause
is claimed from that observation alone. Live provider authorization is still a
separate NOT_VERIFIED check, despite real Registry/catalog reads succeeding.

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
just start
just status
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=empty node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
```

Dependency analysis is not a zero-warning claim. Among unchanged declarations:
Agent local reports unused boot-autoconfigure/junit-jupiter and transitive
Spring/Jackson/JUnit imports; Nexus client reports starter-test and transitive
Spring/Jackson/Mockito/JUnit imports. No added dependency stack or unrelated POM
cleanup is part of Stage 7.

No live-provider/normal runtime outcome is inferred from local stubs. Auth metadata
contract is pinned to the checked 2025-11-25 reference; roadmap2026-07-28 source
was unavailable, so that newer source/SDK protocol upgrade remains NOT_VERIFIED.

## Catalog cache and sign-in UX follow-up — 2026-09-29

Current catalog TTL is **five minutes**. Agent uses the existing
`@Cacheable`/Caffeine configuration, maximum 1,000 entries, distinct
search/cursor/limit keys and one Registry page per cache miss. Console retains
the loaded page for five minutes without extending expiry on reads. No
persistence, crawler, credentials cache or new HTTP stack was introduced. Both
caches are in-memory and reset with their owner.

The original GitHub popup flash was reproduced against normal main Nexus:
`POST /fgaisox/api/v1/infrastructure/agents/integrations/mcp/connect` for
`https://api.githubcopilot.com/mcp/` returned **409 MCP_OAUTH_SETUP_REQUIRED**.
The frontend reserved `about:blank` synchronously, then closed it in the preparation
error path before provider navigation. No successful GitHub authorization occurred.
The official GitHub host integration guide says DCR is unsupported and requires a
registered host GitHub App/OAuth App:
https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md
Existing CLI authorization was not imported or reused.

The preparation window now uses the existing Console stylesheet, inert text DOM,
a status message, and a fixed safe error with Close. Preparation failures remain
visible rather than flashing closed; cancellation/disposal still closes owned
windows. Definite pre-creation rejections allow explicit Retry without automatic
POST replay. Uncertain creation outcomes retain the existing reconciliation guard.
Successful provider navigation, opener isolation, callback confirmation, saved
connection permissions and explicit Enable behavior remain unchanged.

Regression evidence before changes:
- Production Caffeine ticker test failed at the five-minute boundary because the
  page remained cached (expected five upstream calls, observed four).
- Console cache regressions failed at the five-minute boundary because the page
  remained cached instead of starting a new request.
- Preparation-window DOM regression failed because the window body was empty.
- Definite-rejection retry regression failed because the action was disabled.

Verification after changes:
- **PASS** Console typecheck, all 670 tests and build.
- **PASS** focused production Registry configuration/cache tests: nine tests.
- **PASS** full Agent verify: 1,511 tests, zero failures/errors, ten explicit opt-in
  skips. Skipped OS/provider acceptance is not fresh verified evidence.
- **PASS** full Nexus verify: 374 tests, zero failures/errors/skips.
- **PASS** joined `McpSettingsAcceptanceHttpTest`: one test, zero skips;
  `STAGE6_JOINED_OAUTH_GATEWAY_PASS` and `STAGE7_JOINED_CATALOG_CONNECT_PASS`.
  Real Chrome/built assets/Nexus/Agent, disposable authorization provider/database;
  successful fixture OAuth is not successful live GitHub OAuth. Existing mocked
  privileged runtime-boundary limitations remain.
- **PASS** `git diff --check`.
- **NOT_VERIFIED** fresh CI for these uncommitted follow-up changes.
- **NOT_VERIFIED** real GitHub OAuth success: installed Forge OAuth client missing;
  the observed live Connect preparation is the explicit 409 failure above.
- **PASS** updated normal-runtime browser error presentation after final `just start`
  (exit 0): real Chrome, official Registry GitHub row, actual main Nexus 409,
  styled error window remains open, opener is null, Close works, saved connections
  are unchanged. `GITHUB_SETUP_REQUIRED_BROWSER_PASS` and
  `SETTINGS_BROWSER_ACTUAL_NEXUS_PASS`; `/tmp/forge-cache-github-browser.log`,
  Settings error-row screenshot `/tmp/forge-github-setup-settings.png`. This is verified failure handling,
  **not** successful GitHub authorization. Agent/Nexus health and Settings HTTP 200.
  The first browser attempt found a missing title caused by replacing the head
  after setting document.title; a failing DOM regression established this and
  moving the assignment after head replacement fixed it. The final browser run passed.

Commands/logs:
- `npm --prefix services/forge-console run typecheck`: `/tmp/forge-cache-typecheck.log`
- `npm --prefix services/forge-console test`: `/tmp/forge-cache-console-full.log`
- `npm --prefix services/forge-console run build`: `/tmp/forge-cache-build.log`
- `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`:
  `/tmp/forge-cache-agent-full.log`
- `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify`:
  `/tmp/forge-cache-nexus-full.log`
- `mvn -B -ntp -Dapi.version=1.44 -Dforge.codex.stage5-e2e=true -pl services/forge-agent/boot -am -Dtest=McpSettingsAcceptanceHttpTest -Dsurefire.failIfNoSpecifiedTests=false test`:
  `/tmp/forge-cache-joined.log`

Final live boundary: `GITHUB_CONNECT = FAIL` (409 setup required),
`GITHUB_SUCCESSFUL_SIGN_IN = NOT_VERIFIED`, `GITHUB_FAILURE_UX = PASS`.
The failure-UX browser check does not close the missing-installation-client blocker.

## GitHub App preparation — 2026-09-30

Read-only GitHub API confirmed `sitionix/forge-ai` is owned by the personal
account `sitionix` (`owner.type=User`), and the authenticated account has
repository admin permission. A pre-filled GitHub App registration form for that
account was opened in the existing browser session; this is **not** proof that
the App was submitted, installed, or granted access.

Live GitHub OAuth metadata has no `token_endpoint_auth_methods_supported` and
advertises multiple `scopes_supported`. GitHub Apps use fine-grained permissions
instead of OAuth scopes. Before correction, Forge would request every advertised
scope and reject an installed `client_secret_post` client. New focused tests
reproduced both failures before the change, then passed after Forge distinguished
advertised scopes from an explicit challenge and accepted the configured method
when metadata omits the method list. The existing explicit-challenge scope test
remains green. The registration details and protected credential boundary are
recorded in `github-sign-in-setup.md`.

`GITHUB_APP_CREATED = NOT_VERIFIED`; `GITHUB_APP_INSTALLED = NOT_VERIFIED`;
`GITHUB_CLIENT_CONFIGURED = NOT_VERIFIED`; `GITHUB_SUCCESSFUL_SIGN_IN = NOT_VERIFIED`.
The focused OAuth discovery/registration tests passed (34 tests, no failures,
errors or skips). Fresh full Agent verify passed (1,513 tests, no failures or
errors, ten opt-in skips); the command was
`mvn -B -ntp -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`.
`git diff --check` passed. Historical report XML was excluded by modification
time when counting this run.

Subsequent local setup verified the registered App with a short-lived App JWT:
slug `forge-ai-mcp-sitionix`, personal owner `sitionix`, matching Client ID, and
read-only Contents/Issues/Pull requests permissions. GitHub's repository
installation endpoint returned the selected installation for `sitionix/forge-ai`.
The private key was only used for these read-only checks; its downloaded file
mode was corrected from `0664` to `0600` and it is not supplied to Forge OAuth.
The Client secret was entered through a local masked dialog and stored in a
separate `0600` file; neither its value nor the App JWT was printed. The main
Agent systemd unit now loads optional `oauth-clients.env` without changing the
Remote Access unit. The main Agent restarted and returned HTTP 200 health; its
effective environment has the four OAuth client config keys but no secret value.
All 48 Python runtime tests and `git diff --check` passed after the unit change.
Nexus served the current built Settings asset and returned an empty connection
list before the first live Connect.
The normal Nexus available-catalog endpoint returned HTTP 200 for exact search
`io.github.github/github-mcp-server`, with title `GitHub` and endpoint
`https://api.githubcopilot.com/mcp/`; this is Registry metadata, not an MCP
handshake or authorized connection.

`GITHUB_APP_CREATED = PASS`; `GITHUB_APP_INSTALLED_FOR_FORGE_AI = PASS`;
`GITHUB_CLIENT_CONFIGURED = PASS`; `MAIN_AGENT_HEALTH = PASS`;
`GITHUB_SUCCESSFUL_SIGN_IN = NOT_VERIFIED` until the actual browser consent,
callback, authoritative connection read, and Test complete.

Subsequent live verification on 2026-09-30: the normal Nexus connection-list
endpoint returned HTTP 200 with one `GitHub` connection for
`https://api.githubcopilot.com/mcp/`, `authType=OAUTH`,
`credentialConfigured=true`, `checkedAt=2026-09-30T09:07:26.322648Z`, and
`safeDiagnostic=null`. The saved inventory endpoint returned HTTP 200 with
45 tools, including `get_file_contents`. The catalog Connect flow runs Test
before showing Connected, and the authoritative `checkedAt` confirms that a
successful check was persisted. No credential or token was read. The connection
remains `enabled=false`, with no approved tools or allowed projects; runtime
access still requires explicit permission editing and Enable. The browser
consent screen itself was not observed by this verification.

`GITHUB_AUTHORIZED_CONNECTION = PASS`; `GITHUB_MCP_TEST = PASS`;
`GITHUB_TOOL_INVENTORY = PASS`; `GITHUB_RUNTIME_ACCESS = NOT_VERIFIED`.

PR preparation verification on the current tree (2026-09-30): Console
typecheck, all 670 tests and build passed; all 48 Python runtime tests passed.
`mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`
completed `BUILD SUCCESS` with 1,513 fresh tests, zero failures/errors and ten
opt-in skips. `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am
verify` completed `BUILD SUCCESS` with 374 fresh tests, zero failures/errors
or skips. The counts exclude older report XML by modification time.
`git diff --check` passed. Fresh PR CI remains **NOT_VERIFIED** until it runs.

## PR #160 Registry cache contract correction — 2026-09-30

Agent's production Caffeine page cache and Console's retained current page now
expire five minutes after loading. The Agent cache remains bounded to 1,000
entries and distinguishes search, cursor and limit; no new configuration or
refresh mechanism was introduced. The focused production-cache test uses a
controllable Caffeine ticker and proves a hit at five minutes minus one
nanosecond, then a new Registry request exactly at five minutes. The Console
test proves its page is reused before, and refreshed at, the same boundary.

Both changed tests failed against the prior implementation before the TTL edit:
Agent expected a fifth Registry call at the five-minute boundary but observed
four; two Console cases expected a refresh but observed none. After the TTL
edit, the focused Agent tests passed (9/9) and Console cache tests passed (6/6).
The full Console typecheck, 670 tests and build passed. Full Agent verify
completed `BUILD SUCCESS`: 1,513 fresh tests, zero failures/errors and ten
opt-in skips. `git diff --check` passed. Fresh CI for this correction remains
**NOT_VERIFIED** until the new PR head checks complete.
