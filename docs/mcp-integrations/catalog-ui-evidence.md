# MCP Catalog UI — evidence, 2026-09-29

Scope: the Settings Catalog UI slice of roadmap Stages 8–9, using the previously accepted Flow 1 backend. This does not claim completion of Recommended presets, OAuth, or all Stage 8–9 acceptance. See [plan](catalog-ui-plan.md).

## Original implementation (superseded UI layout)

- Settings keeps Connected for saved integrations. Add integration opens Catalog; Add custom MCP opens the existing blank connection form.
- `mcp-api.js` reads the existing Nexus available endpoint with URL-encoded search, cursor and limit. Nexus → Agent → official Registry transport/cache architecture is unchanged.
- `mcp-catalog.js` handles search, one-page loading, opaque cursor pagination, empty/error states and explicit read retry. Empty filtered pages retain Next page when upstream provides a cursor. No automatic pagination, provider handshake or tool call.
- Text metadata uses DOM `textContent`, without external links or executable markup. The icon follow-up below adds native images. Selecting a descriptor reads projects and prefills the existing form. It does not create, test, approve or enable a connection.
- Endpoint templates are preserved in the form. The operator must replace variables with a full endpoint before saving; unresolved placeholders are rejected locally.
- Existing request coordination cancels superseded reads, navigation-away reads and disposal reads. Registry failure does not remove Connected or Custom flows.
- The existing Console design system supplies navigation, cards, input/buttons and heading contrast. Global sidebar/Projects remain visible. Existing permission editing and explicit Enable semantics are unchanged.

## Original UI slice verification (implementation `65c1ac77`)

| Check | Result | Evidence |
| --- | --- | --- |
| TDD | PASS | Before implementation, API regression failed because `available` did not exist and four Catalog regressions failed because Catalog UI did not exist. After implementation focused tests passed. |
| FOCUSED_CONSOLE | PASS | API, Catalog, Settings and connection-form tests: 4 files, 36 tests. Independently repeated by the read-only reviewer. |
| CONSOLE_TESTS | PASS | Final full run: 629 tests in 29 files, zero failures. |
| TYPECHECK | PASS | `npm --prefix services/forge-console run typecheck`. |
| BUILD | PASS | `npm --prefix services/forge-console run build`. |
| BUILT_BROWSER_STUB | PASS | Real Chrome with built assets and explicitly stubbed management backend: Catalog and existing Custom save/test/permissions/Enable flow passed. This is not runtime/provider proof. |
| HEADING_REGRESSION | PASS | Added browser assertion failed on inherited dark heading text; passed after applying the existing light Console color. Sidebar geometry is awaited after responsive transitions. |
| NORMAL_JUST_START | PASS | Standard `just start`, no MCP activation overrides; Agent/Nexus packaged and restarted normally. |
| MAIN_HEALTH | PASS | Main Agent and Nexus health HTTP 200/UP; knowledge, Jarvis and Postgres healthy. Dedicated Remote Access services remained inactive. |
| REAL_CATALOG_BROWSER | PASS | Real Chrome against `http://127.0.0.1:9099/fgaisox/operator/settings.html`: Catalog loaded actual Registry metadata; Connect prefilled name/endpoint; cancel and return to Connected left saved connections byte-equivalent as JSON. Sidebar/Projects present, no horizontal overflow, browser stayed on 9099. No advertised MCP endpoint request. |
| REAL_EMPTY_BROWSER | PASS | Same actual main path: legitimate empty saved inventory, Add action, active Settings, global navigation and compact empty layout passed. |
| FINAL_REVIEW | PASS | Fresh read-only reviewer found no critical, important or minor findings; no PR operations. Subsequent small heading fix was verified RED→GREEN with browser regression and final full Console suite. |
| DIFF_CHECK | PASS | `git diff --check`. |
| FULL_AGENT_VERIFY | PASS | Fresh CI full reactor verify: 1,408 declared tests, zero failures/errors, 11 skips. Not rerun locally; normal runtime packaging is not verification. |
| FULL_NEXUS_VERIFY | PASS | Fresh CI full reactor verify: 352 declared tests, zero failures/errors/skips; includes 69 ForgeIT tests. Not rerun locally. |
| LIVE_PROVIDER | NOT_VERIFIED | Catalog reads/select/cancel did not initialize or call an external MCP provider. |
| OAUTH_RECOMMENDED | NOT_VERIFIED | Outside this UI slice; no verified/Recommended badges or OAuth support inferred from Registry metadata. |
| CI | PASS | [Build 36534870270](https://github.com/sitionix/forge-ai/actions/runs/36534870270), explicitly dispatched for implementation commit `65c1ac77d063bf781a5b159bbb2898a13adddd38`: all five service jobs succeeded. Subsequent evidence update changes documentation only. |

Agent CI skips: CodexRecoveryLifecycleTest (1), CodexMcpInventoryVerifierTest (1), McpGatewayRuntimeFilterTest (1), AgentMcpProtectedConfigurationTest (1), ForgeAgentProjectAssetIT (1), ForgeAgentPortAwareExecutionIT (6). Skipped/native/provider scenarios are not certified by the green reactor. CI also passed 110 Remote Access Python tests with 2 skips and its offline Docker regression fixture. No new live-provider or joined native-runtime acceptance is claimed by this catalog UI change.

Commands:

```sh
npm --prefix services/forge-console test -- tests/mcp-catalog.test.ts tests/mcp-api.test.ts tests/settings-page.test.ts tests/mcp-connection-form.test.ts
npm --prefix services/forge-console run typecheck
npm --prefix services/forge-console test
npm --prefix services/forge-console run build
FORGE_SETTINGS_ACTION=catalog node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
just start
just status
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=empty node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
git diff --check
```

No PR metadata/comments/reviews or merge changed. No backend security, saved connections, schema, credentials, HTTP/cache configuration, runtime grants or policy changes.

## Registry icon follow-up

Optional Registry `icons[].src` now maps to a nullable `iconUrl` through the existing Agent domain/API and Nexus inbound mapper/domain/API. Agent selects the first valid HTTPS URL with a host and without userInfo; absent/invalid icons do not discard a supported server. Backend services do not download the image.

Console renders a decorative native image at 48×48, using lazy loading and `no-referrer`. It rejects non-HTTPS/credential-bearing image URLs, never embeds SVG markup, and keeps a compact initial-letter fallback for missing images or image errors. Registry search, cursor pagination, cache and connection actions are unchanged.

| Follow-up check | Result | Evidence |
| --- | --- | --- |
| TDD | PASS | Production Registry HTTP regression failed because iconUrl was absent; six new Console cases failed on missing image/fallback before implementation, then passed. |
| REGISTRY_MAPPING | PASS | Focused production HTTP fixture, optional/malformed icon URLs and existing Registry/cache regressions: 9 tests. No live MCP endpoint calls. |
| NEXUS_TYPED_MAPPING | PASS | Existing mapper/adapter tests and new ForgeIT preserve optional iconUrl and cursor through the typed proxy. |
| CONSOLE | PASS | 635 tests in 29 files; typecheck/build; built Chrome Catalog and Custom stub smoke passed. Independent reviewer repeated 12 catalog tests and found no findings. |
| FULL_AGENT_VERIFY | PASS | Fresh local reactor verify: 1,408 declared tests, zero failures/errors, 10 skips. |
| FULL_NEXUS_VERIFY | PASS | Fresh local reactor verify: 353 tests, zero failures/errors/skips, including 70 ForgeIT. |
| REAL_ICON_BROWSER | PASS | Normal `just start` exit 0; all main services healthy. Two fresh Chrome runs against actual :9099 loaded a real Registry PNG (`complete` and `naturalWidth > 0`), retained no-referrer, sidebar/Projects and unchanged saved inventory. Actual empty-state browser regression also passed. |
| CI | PASS | [Fresh Build 36536485418](https://github.com/sitionix/forge-ai/actions/runs/36536485418) for implementation `21dbba3946c6e7f41757f75cc918cf1cd431f776`: all five jobs succeeded. Subsequent commits contain browser diagnostics/evidence only, with no production-code changes. |

Local Agent skips: CodexManagedRecoveryLifecycleTest (1), McpGatewaySdkHttpTest (1), McpGatewayRuntimeFilterTest (1), RemoteAccessManagementHttpIT (1), ForgeAgentPortAwareExecutionIT (6). Those native/opt-in scenarios are not certified by full verify.

Fresh follow-up Agent CI: 1,409 declared tests, zero failures/errors, 11 skips. CI skips were CodexRecoveryLifecycleTest (1), CodexMcpInventoryVerifierTest (1), McpGatewayRuntimeFilterTest (1), AgentMcpProtectedConfigurationTest (1), ForgeAgentProjectAssetIT (1), ForgeAgentPortAwareExecutionIT (6). These are fresh log observations, not inferred from the original slice CI.

The first real image-load attempt exceeded the existing five-second browser condition bound. Added CDP diagnostics retain only safe `net::ERR_*` labels, no URLs/headers/body. Two subsequent fresh-profile runs passed without any production-code change or relaxed TLS/network policy. The exact cause of the first timeout is NOT_VERIFIED; it is not counted as a successful run. This acceptance fetched the advertised static icon, not the MCP endpoint, and did not execute a handshake/tool call or create a connection.

```sh
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog-icons node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
```

## Compact Settings follow-up — 2026-09-29

This supersedes the original inline Connected/Catalog layout above. Settings now has one primary Add integration action and compact saved rows. A native Catalog dialog owns search, a bounded scrolling list, next-page control and the secondary Add custom MCP link. Catalog rows have 32px icons, title and at most two description lines; selecting a row opens the existing form. A separate native details dialog owns Edit, Test, explicit Enable/Disable and confirmed Remove. Backend APIs, Registry/cache, credentials and permissions are unchanged.

Form close returns to the same catalog without another page request, or to authoritative saved details. The saved connection snapshot is carried through the existing form close callback, including when an authoritative read is still pending. Catalog dismissal aborts only the preparation read in RequestCoordinator's `add` slot; current mutations retain their separate `action` slot. No new coordination abstraction.

| Check | Result | Evidence |
| --- | --- | --- |
| TDD | PASS | Four initial layout regressions failed before implementation. A pending authoritative-save regression then reproduced incorrect catalog restoration. Final review reproduced delayed `/projects` reopening the form after catalog close; its new regression failed before the dedicated `add` cancellation fix, then passed. |
| FOCUSED_CONSOLE | PASS | Compact Settings, Catalog, Settings and connection-form suites: 41 tests / 4 files. Fresh read-only reviewer independently repeated all 41. |
| CONSOLE_TESTS | PASS | Final full Console run: 641 tests / 30 files, zero failures. |
| TYPECHECK_BUILD | PASS | Standalone Console typecheck and production build exited 0 after fixing a new test reading undeclared private `pending`. The initial sequential local command masked that nonzero typecheck exit; fresh CI exposed it. Final full Console run was repeated: 641/641 passed. |
| BUILT_BROWSER_STUB | PASS | Real Chrome/built assets: compact Catalog, selection/cancel, native Escape/focus, 375px fit, and existing Custom create/test/disabled permissions/explicit Enable/credential replacement passed. Management backend is an explicit stub, not a runtime or provider proof. |
| FINAL_REVIEW | PASS | Fresh read-only review accepted after the demonstrated late-project-read fix; no remaining blockers reported. |
| NORMAL_RUNTIME_FINAL | PASS | Final `just start` exited 0. Actual served JS/CSS contain final cancellation/layout fixes. Main Agent :7091 and Nexus :9099 health are UP; knowledge/Jarvis/Postgres active, dedicated Remote Access inactive. Real Chrome empty state, Catalog, 375px fit and actual Registry PNG passed without saved connection changes. |
| CI | PASS | [Build 36539832753](https://github.com/sitionix/forge-ai/actions/runs/36539832753) for `8187208f27f2cb2d22441b7f6e6fb6c40a14afd9`: all five service jobs succeeded. Console: 641 tests, typecheck/build. First follow-up Build 36539656001 failed Console on the test-only typecheck issue above; it remains a failed historical attempt. |
| FULL_AGENT_VERIFY | PASS | Fresh CI reactor: 1,409 declared tests, zero failures/errors, 11 skips. |
| FULL_NEXUS_VERIFY | PASS | Fresh CI reactor: 353 tests, zero failures/errors/skips, including 70 ForgeIT. |
| LIVE_PROVIDER | NOT_VERIFIED | This follow-up does not initialize or call advertised MCP endpoints. |
| JOINED_RUNTIME | NOT_VERIFIED | Stage 3/4 grant revocation and policy acceptance are not rerun for this frontend layout change. |

No PR metadata/comments/reviews or merge operations. Full local backend reactors are not rerun for this frontend-only follow-up; previous evidence above remains historical rather than a fresh result.

Browser harness correction: a programmatic catalog row click originally left focus in a nonempty search input. Native Escape cleared that input instead of dismissing the dialog. The harness now focuses the selected row, matching real keyboard/pointer selection; actual icon/search/cancel/Escape passed. One earlier initial page wait also timed out; exact cause is NOT_VERIFIED. These failed attempts are not counted as successful runs.

Compact UI verification commands:

```sh
npm --prefix services/forge-console test -- tests/mcp-compact-settings.test.ts tests/mcp-catalog.test.ts tests/settings-page.test.ts tests/mcp-connection-form.test.ts
npm --prefix services/forge-console run typecheck
npm --prefix services/forge-console test
npm --prefix services/forge-console run build
FORGE_SETTINGS_ACTION=catalog node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
just start
just status
curl -fsS http://127.0.0.1:7091/actuator/health
curl -fsS http://127.0.0.1:9099/fgaisox/actuator/health
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=empty node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog-icons node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
git diff --check
```

Actual-main served `settings-page.js` and `operator-ui.css` SHA-256 matched the committed production source. Browser acceptance preserves saved inventory byte-equivalent as parsed JSON; Catalog selection reads metadata/projects only, and does not save, test, approve or enable.

Compact follow-up Agent CI skips: CodexRecoveryLifecycleTest (1), CodexMcpInventoryVerifierTest (1), McpGatewayRuntimeFilterTest (1), AgentMcpProtectedConfigurationTest (1), ForgeAgentProjectAssetIT (1), ForgeAgentPortAwareExecutionIT (6). These skipped scenarios and LIVE_PROVIDER/JOINED_RUNTIME are not certified by the green CI. Subsequent evidence-only commit does not change verified production code.

## Live Catalog timeout investigation — 2026-09-29

A subsequent user screenshot showed the scoped Catalog error. Reproduced actual Nexus and Agent HTTP 503 / MCP_REGISTRY_UNAVAILABLE at approximately five seconds. The configured Registry read timeout is 5s. Direct public Registry requests also stalled before the first response byte despite successful TCP/TLS establishment (20s and 10s bounds expired); this is not a request for the entire registry.

Pagination was verified independently: the direct Registry page contained exactly 20 entries and a nextCursor. Production maps supported remote metadata from that one page and preserves its cursor. After recovery, actual Nexus returned 19 supported entries and a cursor; an explicit second request with that opaque cursor returned HTTP 200 in 0.407s, 17 supported entries and another cursor. No automatic page traversal or connection mutation occurred.

Disposable Java probes compared JDK HTTP/1.1 and HTTP/2, then the actual Spring typed Registry configuration. Both HTTP versions had successes and timeouts; a protocol-version cause is NOT_VERIFIED. A fresh production-config probe also timed out once and subsequently succeeded on two sequential requests (20 entries each). The running Agent/Nexus path then recovered without a code change, timeout increase or restart. Real Chrome actual-main Catalog acceptance passed again. Exact cause of the transient upstream/network failures is NOT_VERIFIED; earlier green browser acceptance does not imply continuous Registry availability.

A speculative configuration-test draft failed compilation because this module does not depend on Mockito/Spring Test. It was removed after its HTTP-version hypothesis was contradicted; it is not a regression-test PASS and no production change was made. No new dependency, retry policy, HTTP transport, cache/pagination configuration or public error mapping was introduced by this investigation.

## Repeated Catalog timeout correction — 2026-09-29

The user reproduced the error again after the temporary recovery above. Further disposable probes used the same OpenJDK binary as the systemd Agent and the accepted Spring typed HTTP stack. Five requests per protocol with a 5s budget produced four failures each; HTTP/1.1 versus HTTP/2 is not the cause. With a longer probe budget, a valid bounded Registry page (18 entries) arrived in 24.577s; a second request returned in 0.984s. This demonstrates slow successful Registry responses exceeding Forge's 5s budget. [Registry issue #1252](https://github.com/modelcontextprotocol/registry/issues/1252) independently reports 20–25s reads; its server-side explanation remains a hypothesis, not a proven cause of our observation.

The normal Agent Registry read-timeout default is now 28s, preserving a 2s margin to the existing 30s Nexus → Agent read budget. The initial 25s candidate still timed out on the first actual-main cold request and was increased before final acceptance; the external report also contains a successful 25.818s response. This is not an unlimited timeout or a guarantee of upstream availability. The existing environment override remains available. No HTTP version change, second HTTP stack, retry, new property, cache/pagination change or global Agent timeout increase. Upstream requests remain bounded and genuine timeout/unavailable failures still map to MCP_REGISTRY_UNAVAILABLE.

`McpRegistryRuntimeConfigurationTest` loads the actual normal `application.yml` and production Registry configuration with only a disposable loopback base URL. It synchronizes request arrival, holds the valid response across the former deadline for six seconds, then releases it and verifies typed page/cursor mapping. RED: the former default completed exceptionally at five seconds. GREEN: the updated normal configuration kept the request in flight and returned the valid page. Existing nine Registry transport/filter/pagination/cache tests also passed. No live service is used by regression tests, no synthetic enable switch, and no new dependencies.

The browser harness now permits 30s specifically for bounded Catalog loading/search while retaining its previous 5s budget for ordinary UI conditions and image completion. This avoids a test-only 5s deadline masking a valid production response.

| Follow-up | Result | Evidence |
| --- | --- | --- |
| FOCUSED_REGISTRY | PASS | Nine existing production Registry tests plus the new normal-runtime slow-page regression; Maven focused run BUILD SUCCESS. |
| NORMAL_RUNTIME | PASS | Final standard `just start` exited 0 with 28s. Main Agent/Nexus health UP, knowledge/Jarvis/Postgres active; dedicated Remote Access inactive. Fresh main Catalog page returned HTTP 200 in 0.848s after restart. The initial 25s candidate returned 503 at 25.080s and its browser run failed; those attempts are not counted as PASS. |
| REAL_BROWSER | PASS | Actual main :9099 Chrome Catalog and icon/search acceptance passed after final restart; native selection/cancel/Escape, 375px fit, sidebar/Projects and unchanged saved connections preserved. The same `limit=20` page was read again after 319.942s (past the unchanged 5min expire-after-write TTL): HTTP 200 in 0.738s, 19 supported entries and a cursor. Explicit next-page request returned HTTP 200 in 17.230s, 17 supported entries and another cursor, demonstrating successful waiting beyond the former 5s budget. |
| CI | PASS | [Build 36543137470](https://github.com/sitionix/forge-ai/actions/runs/36543137470) for final source `1ce9b016906da7fe8a0c0a53767299ca7d80fff8`: all five service jobs succeeded. Full Agent: 1,410 tests, zero failures/errors, 11 skips. Full Nexus: 353 tests, zero failures/errors/skips, including 70 ForgeIT. Console: 641 tests, typecheck/build. Earlier 25s source Build 36542712436 also passed but is not substituted for the final source run. |
| UPSTREAM_SERVER_CAUSE | NOT_VERIFIED | Successful slow responses prove the client budget mismatch; Registry internals causing latency are not established. |

Final correction review: ACCEPT after checking the 28s/30s budget relationship, unchanged HTTP/cache/pagination architecture and repeated focused tests. Fresh CI Agent skips remain CodexRecoveryLifecycleTest (1), CodexMcpInventoryVerifierTest (1), McpGatewayRuntimeFilterTest (1), AgentMcpProtectedConfigurationTest (1), ForgeAgentProjectAssetIT (1), ForgeAgentPortAwareExecutionIT (6); no new live MCP-provider or joined native-runtime acceptance is claimed. Full local backend reactors were not repeated: the focused local run and fresh full CI results are distinct. Subsequent evidence-only commit does not change the verified runtime/configuration. External Registry responses exceeding 28s still legitimately fail closed; this correction does not guarantee continuous upstream availability.

```sh
mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am -Dtest=McpRegistryRuntimeConfigurationTest,McpRegistryCatalogAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test
just start
just status
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
FORGE_SETTINGS_BASE_URL=http://127.0.0.1:9099/fgaisox FORGE_SETTINGS_ACTION=catalog-icons node services/forge-console/scripts/mcp-settings-browser-smoke.mjs
git diff --check
```

## Catalog page preload and reuse — 2026-09-29

User approved this bounded UI improvement after observing slow Catalog reads. Settings now preloads one first page independently of the saved-connection list, without opening the dialog. `McpCatalog` retains only the current rendered page and its submitted search/cursor identity for five minutes after a successful read. Reopening neither extends TTL nor duplicates an in-flight read. Expired same-page refresh keeps rows visible; failure retains them with a safe “last loaded page” warning and an explicit Retry. Search and pagination clear unrelated rows and continue to fetch at most one page. Empty pages are reusable. Closing cancels active reads; disposal clears retained state. No browser storage, automatic retries, periodic refresh, or new caching abstraction.

The Agent's existing Spring `@Cacheable("mcpRegistryPages")` and Caffeine five-minute / maximumSize=1000 cache remain unchanged, as do typed HTTP, request parameters, Registry timeout, connection persistence, provider credentials and explicit Enable/Disable semantics. This reduces repeated UI waiting; it does not make the external Registry faster or guarantee availability of a cold read.

| Verification | Result | Evidence |
| --- | --- | --- |
| RED_REGRESSION | PASS | All five new `mcp-catalog-cache.test.ts` cases failed on the previous implementation because mounting Settings made zero available requests. |
| FOCUSED_CONSOLE | PASS | Catalog reuse, existing Catalog and Settings tests: 26/26. Controlled `Date.now` checks expiry at exactly five minutes without a real wait, non-sliding reopen, retained refresh/failure rows, explicit Retry, empty pages, cursor reopen, changed search and cancellation. Compact dialog coverage passed in the initial combined focused run; that run also exposed three old total-request assertions, updated to distinguish the connection read from the approved metadata prefetch. |
| FULL_CONSOLE | PASS | 646 tests in 31 files; typecheck and build passed separately. |
| BUILT_CHROME_STUB | PASS | Existing create/test/permissions/explicit Enable flow and Catalog smoke passed using built assets and the explicitly local management stub. This is not Agent/runtime acceptance. |
| NORMAL_START | PASS | Standard `just start` exited 0 after native sudo authentication. Final `just status`: knowledge/Jarvis/Agent/Nexus/Postgres active; dedicated Remote Access inactive. A status read during startup briefly reported knowledge unhealthy; final status was healthy. |
| ACTUAL_MAIN_BROWSER | PASS | Real built assets served by normal Nexus :9099: empty Settings acceptance and Catalog acceptance passed. Catalog harness verifies preload while its dialog is closed, exactly one available request, repeated open/close/open with no additional request and the same row DOM identity. Desktop/narrow layout, sidebar/Projects, native selection/cancel/Escape and unchanged saved-connection inventory also passed. Separate actual-main catalog-icon/search acceptance passed. Served `mcp-catalog.js` matched the built asset byte-for-byte. No HTTP stub substitutes for this run. |
| CI | PASS | [Build 36545805664](https://github.com/sitionix/forge-ai/actions/runs/36545805664) for source `213dbbb52f0cdcb919fec14e25c1cd36827c4916`: all five service jobs succeeded. Full Agent: 1,410 tests, zero failures/errors, 11 skips. Full Nexus: 353 tests, zero failures/errors/skips, including 70 ForgeIT. Console: 646 tests in 31 files, typecheck/build. Subsequent evidence-only commit does not change that verified source. Full local backend reactors were not repeated for this frontend-only change; these full verify results are from fresh CI. |
| UPSTREAM_LATENCY_CAUSE | NOT_VERIFIED | Existing slow Registry observations remain historical evidence; provider internals were not re-probed. |

Final read-only review found no critical or important defect. Deferred minor: the disposal regression uses one microtask wait before checking late-result DOM; a stronger completed-chain synchronization would improve that assertion. Existing RequestCoordinator superseded-result/cancellation coverage remains in the suite. No new live MCP-provider call, joined Stage 3/4 runtime probe or OS-isolation acceptance is claimed by this frontend-only follow-up.

Agent CI skips remain CodexRecoveryLifecycleTest (1), CodexMcpInventoryVerifierTest (1), McpGatewayRuntimeFilterTest (1), AgentMcpProtectedConfigurationTest (1), ForgeAgentProjectAssetIT (1), ForgeAgentPortAwareExecutionIT (6). Skips are not runtime-probe PASS.

## User-approved 50-second wait without blocking Settings — 2026-09-29

The exact `io.github.github/github-mcp-server` Registry search returned a valid page directly in 34.404s, while the then-current Forge 28s budget returned 503 in 28.010s. A direct latest-version lookup also confirmed the official active GitHub remote descriptor. This is a timeout rather than an absent server. The user explicitly requested a 50s wait with a responsive page.

Normal Agent Registry read timeout is now 50s. Nexus applies a separate 55s read budget only to GET `/api/v1/integrations/mcp/available`, using two Spring JDK request factories over the same existing HttpClient and typed `ForgeAgentHttpClient`. Ordinary Agent calls retain their existing 30s budget. The explicit normal Nexus property is `forge.mcp.catalog.agent-read-timeout` (environment mapping `FORGE_MCP_CATALOG_AGENT_READ_TIMEOUT`). No new HTTP stack/proxy, executor routing, async job, polling, automatic retry, schema, persistence or feature switch. Caffeine and browser page-reuse behavior remain unchanged. These are bounded read budgets, not an unlimited wait or guarantee of Registry availability.

Settings connections and Catalog already use independent asynchronous browser reads. No new production UI mechanism was needed: regression coverage now holds Catalog pending and proves the loaded Settings empty state, enabled Add action, immediate close, Custom form opening, request abort and zero mutations. Browser abort does not claim cancellation of the server-side Registry request; that remains bounded by its timeout.

| Verification | Result | Evidence |
| --- | --- | --- |
| RED_CONFIG | PASS | Nexus actual outgoing `HttpRequest.timeout` was 30s instead of requested 55s; Agent normal configuration was 28s instead of 50s. The initial Agent assertion used the environment's generic conversion service and errored; it was corrected to the existing Boot conversion service before observing the expected 28s/50s regression failure. |
| FOCUSED_AGENT | PASS | Nine existing Registry client/filter/cache tests and the normal-runtime configuration regression passed. |
| FOCUSED_NEXUS | PASS | Four tests passed: actual catalog55s/ordinary30s requests, a real synchronized loopback Catalog request held beyond a synthetic ordinary500ms bound while connections read succeeds, existing adapter and ordinary-client credential-isolation tests. The first compile found an existing direct configuration constructor invocation; that fixture was updated for the added timeout argument before the green run. |
| CONSOLE | PASS | 647 tests in 31 files, typecheck and build. Pending Catalog regression is bounded and does not wait 50 real seconds. |
| PENDING_CHROME_FIXTURE | PASS | Real Chrome/built assets with an explicitly local HTTP stub that never responds to Catalog: Settings loads, close works, Custom form opens, sidebar/Projects remain visible and zero create/test calls occur. This proves UI responsiveness under a held response; it is not a live-provider assertion. |
| NORMAL_RUNTIME | PASS | Standard `just start` exited 0; final knowledge/Jarvis/Agent/Nexus/Postgres active, dedicated Remote Access inactive. Actual-main Settings empty-state Chrome acceptance passed while the exact GitHub HTTP read was running. |
| EXACT_GITHUB_SEARCH | PASS | Actual main Nexus :9099 returned HTTP200 in 35.294s, beyond the former ordinary30s deadline: one GitHub descriptor `io.github.github/github-mcp-server`, title GitHub, version1.12.2, endpoint `https://api.githubcopilot.com/mcp/`, cursor null. No connection was created or provider handshake performed. |
| FRESH_CI | PASS | [Build 36549140155](https://github.com/sitionix/forge-ai/actions/runs/36549140155) for source `30e8746fef5ff25bf97b682dadbff1b07893a549`: all five service jobs succeeded. Full Agent: 1,410 tests, zero failures/errors, 11 skips. Full Nexus: 355 tests, zero failures/errors/skips, including 70 ForgeIT. Console: 647 tests, typecheck/build. Full local backend reactors were not repeated; full verify results are from this fresh CI. Subsequent evidence-only commit does not change verified source. |

Fresh read-only review: ACCEPT, no important findings. OS-level runtime isolation, joined gateway/Codex behavior, live GitHub tools/credentials and Registry internal latency cause were not re-probed by this timeout change.

Actual-main Catalog Chrome acceptance also passed after the restart, preserving preload/reopen behavior, desktop/narrow layout, sidebar/Projects, selection/cancel/Escape and unchanged saved connections. Agent skips remain the same 11 listed above; no skipped probe is counted as PASS.
