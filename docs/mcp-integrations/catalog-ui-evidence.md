# MCP Catalog UI — evidence, 2026-09-29

Scope: the Settings Catalog UI slice of roadmap Stages 8–9, using the previously accepted Flow 1 backend. This does not claim completion of Recommended presets, OAuth, or all Stage 8–9 acceptance. See [plan](catalog-ui-plan.md).

## Implementation

- Settings keeps Connected for saved integrations. Add integration opens Catalog; Add custom MCP opens the existing blank connection form.
- `mcp-api.js` reads the existing Nexus available endpoint with URL-encoded search, cursor and limit. Nexus → Agent → official Registry transport/cache architecture is unchanged.
- `mcp-catalog.js` handles search, one-page loading, opaque cursor pagination, empty/error states and explicit read retry. Empty filtered pages retain Next page when upstream provides a cursor. No automatic pagination, provider handshake or tool call.
- Metadata uses DOM `textContent`, without external links/images or executable markup. Selecting a descriptor reads projects and prefills the existing form. It does not create, test, approve or enable a connection.
- Endpoint templates are preserved in the form. The operator must replace variables with a full endpoint before saving; unresolved placeholders are rejected locally.
- Existing request coordination cancels superseded reads, navigation-away reads and disposal reads. Registry failure does not remove Connected or Custom flows.
- The existing Console design system supplies navigation, cards, input/buttons and heading contrast. Global sidebar/Projects remain visible. Existing permission editing and explicit Enable semantics are unchanged.

## Verification

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
| FULL_AGENT_VERIFY | NOT_VERIFIED | Not rerun for this frontend-only change. Runtime packaging is not full verification. Previous backend verification remains historical evidence in normal-runtime-evidence.md. |
| FULL_NEXUS_VERIFY | NOT_VERIFIED | Not rerun for this frontend-only change. No backend/client/cache changes. |
| LIVE_PROVIDER | NOT_VERIFIED | Catalog reads/select/cancel did not initialize or call an external MCP provider. |
| OAUTH_RECOMMENDED | NOT_VERIFIED | Outside this UI slice; no verified/Recommended badges or OAuth support inferred from Registry metadata. |
| CI | NOT_VERIFIED | No fresh CI result is claimed for this branch. |

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
