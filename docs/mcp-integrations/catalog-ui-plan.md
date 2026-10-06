# Available MCP catalog UI

This is the Settings UI slice of roadmap Stages 8–9 over the accepted Flow 1 backend. It does not complete OAuth, Recommended presets, or all Stage 8–9 acceptance.

1. Add regression coverage for the existing Nexus available endpoint, catalog navigation/search/pagination, cancellation, inert metadata, and prefilled connection form.
2. Use a compact saved-connections section with one Add integration action. Add integration opens a native Catalog dialog; its secondary Add custom MCP action opens the existing blank form. This supersedes the original inline Connected / Catalog navigation.
3. Fetch one page through Nexus → Agent → official Registry. Preserve opaque cursors, including empty filtered pages. Keep errors scoped to Catalog and existing connections usable during Registry failure.
4. Reuse the existing form for a selected descriptor. Preserve the endpoint template as metadata; require the operator to enter a resolved endpoint before saving. No provider request or connection mutation occurs on selection.
5. Run focused and full Console tests, typecheck and build. Extend the existing browser smoke; verify built assets against the normal main Nexus when available. Record actual verification boundaries.
6. Review the final diff and stop for review. No PR metadata/comments/reviews or merge.

Existing typed HTTP clients, Registry filtering, Caffeine cache, persistence, provider credentials, runtime grants, and explicit permission/Enable semantics remain unchanged. No new framework, feature flag, authentication flow, scheduler, or catalog persistence.

## Icon follow-up

Use the first valid HTTPS Registry `icons[].src` as optional `iconUrl` metadata through the existing typed Agent/Nexus contracts. Console renders a native lazy image with no referrer and a decorative fallback for missing/failed images. Do not download images on the backend, execute SVG markup, or change connection flows. Verify the Registry mapping, typed Nexus ForgeIT, Console image/error behavior, full reactors and actual main browser rendering.

## Compact Settings follow-up

Approved user design: keep a compact MCP section on Settings, move Catalog and integration management into separate native dialogs, and remove duplicate global Catalog/Connected/Refresh controls. Catalog uses 32px decorative icons, whole-row selection and descriptions capped at two lines. URL/version remain metadata for the existing form rather than visible list diagnostics. Search, one-page pagination and its cursor stay unchanged; the catalog alone scrolls within its bounded dialog.

Keep explicit Enable/Disable and disabled-only permission editing unchanged. Form cancellation returns to the previous catalog or saved connection details; close/Escape cancels pending reads so late responses cannot reopen a dismissed dialog. Reuse the existing RequestCoordinator, native dialog focus behavior, Console styles and connection form. No new UI framework, transport, backend policy or dependency.

Verification: focused regressions first, full Console/typecheck/build, built Chrome catalog and existing create/test/permissions/Enable smoke with its explicit management stub, then read-only actual main :9099 empty/catalog/image acceptance and narrow viewport/Escape checks. Run one final read-only review, record exact results, and stop for review without PR operations or merge.

## Catalog latency follow-up

Approved user scope: preload one initial metadata page when Settings mounts and retain only the current rendered page for five minutes. Reopening reuses that page and does not extend its expiry. Opening during its active read does not start another request. A same-query refresh keeps rows visible and retains them with a scoped warning on failure; another search/cursor clears unrelated results. Explicit search and Retry still request refresh, and pagination remains one page per request. Disposal cancels the read and clears the page. No storage, timer, crawler, scheduler, or new cache abstraction; the accepted Agent Spring `@Cacheable`/Caffeine configuration stays unchanged.

Verify controlled-clock TTL, in-flight deduplication, empty-page reuse, current-cursor reopen, stale refresh failure, search separation and cancellation with Console tests. Extend the existing Chrome harness to prove preload while the dialog is closed and reopen without another available request, against built assets and actual main :9099.

## User-approved 50-second Registry wait

Registry reads use a 50s default. Only Nexus GET `/api/v1/integrations/mcp/available` receives a dedicated 55s read budget for forwarding that bounded result; other typed Agent calls keep their existing 30s budget. Reuse the same `ForgeAgentHttpClient`, executor and JDK HttpClient, selecting a Spring request factory in its existing configuration. No new proxy, framework, retry or job API. Browser fetch stays independent from the saved-connection read; close/custom remain usable during a pending response. Verify actual HTTP-request budgets, a synchronized synthetic concurrent read beyond the ordinary request deadline, pending UI/native Chrome fixtures and the exact live GitHub search after standard startup.
