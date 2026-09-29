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
| CI | FAIL | First follow-up build 36539656001 exposed the test-only typecheck issue above; corrected follow-up verification is pending. Prior green Catalog/icon builds do not verify this new UI change. |
| LIVE_PROVIDER | NOT_VERIFIED | This follow-up does not initialize or call advertised MCP endpoints. |
| JOINED_RUNTIME | NOT_VERIFIED | Stage 3/4 grant revocation and policy acceptance are not rerun for this frontend layout change. |

No PR metadata/comments/reviews or merge operations. Full local backend reactors are not rerun for this frontend-only follow-up; previous evidence above remains historical rather than a fresh result.

Browser harness correction: a programmatic catalog row click originally left focus in a nonempty search input. Native Escape cleared that input instead of dismissing the dialog. The harness now focuses the selected row, matching real keyboard/pointer selection; actual icon/search/cancel/Escape passed. One earlier initial page wait also timed out; exact cause is NOT_VERIFIED. These failed attempts are not counted as successful runs.
