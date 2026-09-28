# MCP Stage 5 — Settings and Custom connections

## Purpose and scope

The operator sees saved global MCP connections in **Settings → Integrations**, can add a Custom HTTP MCP, test it, select permitted tools/projects, and enable it for ordinary Agent execution. Connections remain independent of the currently selected project. This implements [roadmap Stage 5](../../mcp-integrations/roadmap.md#stage-5--settings-та-custom-mcp-через-ui); the user approved that scope on 2026-09-28.

Reuse Stage 1 persistence/authentication, Stage 2 probe/inventory, Stage 3 policy/revocation, and Stage 4 native runtime. No catalog, OAuth flow, provider presets, new UI framework/router, scheduler, database schema, or runtime redesign. Registry Flow 1 remains untouched.

Work on regular branch `feature/SITIONIX-152`, based on merged main `0ecef203`. Personal configuration, production secrets, deployment and sandbox-network permissions remain unchanged.

## Existing code and integration points

| Responsibility | Existing reference | Stage 5 change |
| --- | --- | --- |
| Sidebar and page lifecycle | `services/forge-console/src/operator/operator-bootstrap.js`, `operator-router.js`, `operator-ui.css` | Bottom Settings entry, register Settings page in the current router, preserve existing navigation. |
| Form/view ownership | `remote-access-page.js`, `remote-access-view.js`, `request-coordinator.js` | A focused Settings page, MCP form/view and feature API using the same lifecycle patterns. No modification of unrelated page internals. |
| Connection API | Nexus `api-rest/.../mcp/ForgeAiMcpConnectionsController.java` | Reuse list/get/create/update/test/inventory/approve/enabled/delete endpoints. |
| Typed backend proxy | Nexus `ForgeAgentHttpClient`, `ForgeAgentMcpClientAdapter`, `ForgeAgentClientCallExecutor`, `McpConnectionsExceptionHandler` | Preserve map → execute → map and existing public error boundary. |
| Project options | Console `agent-projects-api.js`, Nexus `/api/v1/infrastructure/agents/projects` | Read existing project metadata through the authenticated same-origin client. |
| Operator ownership | `OperatorSessionController`, `OperatorManagementAuthenticationFilter`, `RemoteAccessOperatorController`, `RemoteAccessSecurityConfiguration` | Minimal canonical session facade for the combined mode, backed by the already accepted Remote Access authentication owner. |
| Static packaging | `services/forge-console/scripts/copy-static.mjs`, Nexus boot Maven resource copy | Build and package the new page normally; allow only Console static resources needed before login. |

## UI and flows

Use the existing English Console labels, typography, panels, buttons and native dialog styling. Settings is pinned below the main sidebar links and remains accessible without a selected project. The page has an Integrations heading, Connected list, Refresh, Add custom MCP and the current operator session controls.

### Connected list and details

Read saved connections, not the Registry. Show display name, endpoint host, authentication method, credential-configured indicator, last successful check and tool/project access. Account metadata is displayed only if an existing safe backend field supplies it; do not invent an account identity.

Derived labels are factual: Disabled, Not checked, Credentials required, No approved tools, No allowed projects, or Enabled. “Enabled” does not claim live reachability. `checkedAt` is the last successful check; a failed current test is shown separately and does not overwrite this historical meaning. Render endpoint, tool descriptions and diagnostics as text; map diagnostic/error codes to fixed UI messages rather than rendering arbitrary error body/message.

Details provide Test connection, Edit, tool/project permissions, Enable/Disable and Remove. Empty tool selection and `SELECTED` with no projects remain valid explicit deny-all policies. The UI distinguishes a saved connection from tools being permitted to an agent.

### Add Custom MCP

1. Enter display name, concrete HTTP(S) endpoint and auth choice: no-auth, bearer token or secret headers. Variables/templates are not resolved here. Native browser validation rejects obviously invalid input; Agent remains the business validation owner.
2. **Save and test** creates one disabled saved connection, then invokes its existing `/{id}/test` endpoint. The current backend probes saved IDs, so no unsaved-probe API or draft persistence model is introduced. The UI makes the disabled save visible before probing.
3. On success, show discovered tool names/descriptions and project choices. Tools are unchecked initially; default project scope is `SELECTED` with no projects. The operator explicitly chooses access.
4. Save permissions using existing update and allowed-tools endpoints; enable only through the existing enabled endpoint. Re-read server state after each confirmed operation.

A failed test leaves the saved connection visibly disabled and editable, with Retry or Remove. Closing before save creates nothing; closing after save keeps the explicitly saved disabled connection. Never silently delete a saved resource on dialog cancel. If save succeeds but the following request fails, retain its ID and reconcile before another create; no hidden mutation retry.

`MCP_AUTH_REQUIRED` asks the operator to choose token/headers. Unsupported OAuth is explained as unavailable in this stage; no OAuth redirect or fake connected state. The existing protocol does not distinguish an OAuth challenge from all other auth-required failures, so the UI does not claim a more specific diagnosis than the backend provides.

### Edit and credential replacement

Load only safe connection metadata. Credential input is blank; `credentialConfigured` supplies the saved-secret indicator. Unchanged credentials use `KEEP`, replacement uses `REPLACE`, removal uses `REMOVE`. Changing endpoint/auth with an existing credential requires explicit replacement/removal, following Agent policy. Never prefill or fetch an existing secret.

Endpoint/auth/credential changes invalidate inventory/approvals according to existing backend behavior. After update/test, read current connection and inventory again. Preserve only approvals the backend still returns with matching schema fingerprints; newly discovered or changed tools require explicit approval. Do not invent an “always reset all approvals” policy.

### Test, toggle, remove and access changes

Test is management-only and never calls a discovered tool. Confirm Remove. Disable/remove/access reduction use existing backend revocation and update from confirmed responses; buttons alone are not the authorization mechanism. On ambiguous failure, refresh state and require an explicit subsequent action. Do not silently replay create/update/enable/delete.

## Authentication and secret boundaries

The currently accepted MCP-only owner exposes `/api/v1/operator/session` with `FG_SESSION` and `X-Forge-CSRF`; combined mode uses the existing Remote Access session and `X-CSRF-TOKEN`. A single Settings page must work in both modes and reuse an existing Remote Access login.

The selected approach is a narrow combined-mode facade at the canonical `/api/v1/operator/session` GET/POST/DELETE routes. It uses `RemoteAccessOperatorAuthentication`, the existing Spring session/security context and CSRF repository, with exactly the existing local-session/login/logout semantics. It does not create a second cookie, credential configuration or session store. The existing Remote Access routes remain functional.

Canonical session responses add `csrfHeader` alongside the existing `csrfToken`: `X-Forge-CSRF` in MCP-only, `X-CSRF-TOKEN` in combined. The Console accepts only those two fixed names and stores both values in private memory. The facade's POST uses `bootstrapSecret` to match the existing canonical login request, forwarding it only to the selected existing owner. GET does not weaken that owner's authentication semantics; DELETE requires its current CSRF validation.

Security matchers explicitly recognize only the canonical combined login/session aliases; no prefix-based management bypass. Preserve Host, Origin, loopback, forwarded-header, CSRF, expiry, redispatch and role checks. Add exact static-resource access for the public Console login shell and its packaged modules/CSS; management APIs remain guarded. Test hostile path variants and alternate routes.

Other considered approaches: build-time auth mode would drift from runtime feature flags; browser trial logins would complicate secret routing. The canonical facade keeps the browser contract small and preserves each existing owner.

Use a focused MCP API module rather than the generic infrastructure client's raw-body error previews. Requests are same-origin, `credentials: same-origin`, `cache: no-store`, redirects rejected, and bounded by lifecycle cancellation. Error parsing retains only an allowlisted code and safe correlation identifier; fixed UI messages never expose headers, body, credentials or exception causes. Distinguish `MCP_AUTH_REQUIRED` from an expired operator session: a failed probe must not automatically discard a valid operator login. Verify session when necessary without replaying a mutation.

Read bearer/header values only for submission; clear secret inputs immediately and release request state on completion, cancel, logout, pagehide and dispose. No secrets in URL/hash, storage, dataset attributes, rendered summaries or debug output. JavaScript cannot guarantee memory erasure of temporary strings; the contract is no persistence or later rendering. Operator credential and MCP credential fields have independent ownership.

## Request lifecycle and accessibility

Reuse `RequestCoordinator` for cancellation and stale-response suppression. Keep page state and dialog state small and separate. Use one pending mutation per dialog/connection; disable double submit. Cancel/navigation abort requests and invalidate their ownership so late responses cannot reopen a dialog, refill secrets or overwrite a newer selection. BFCache restoration establishes fresh page ownership and session state.

Provide loading, empty, invalid, failure and retry states. Dialog has a labelled title, keyboard access, Escape/cancel, focus restoration and proper focus containment. Forms have labelled password inputs, error association and live status messages. Do not introduce polling unless a current flow actually requires it; list refresh follows user action and confirmed mutations.

## Verification and acceptance

1. Console API tests: exact methods/paths/body/CSRF, both session modes, existing-session reuse, safe failures, cancellation, no credential leakage, disabled feature state.
2. Component/lifecycle tests: empty/list/details/create/edit/replace/test/permissions/toggle/remove, no-project and no-tool states, failed probe, ambiguous create followed by reconciliation, double submit, stale response, cancel/pagehide/BFCache, focus/keyboard and sidebar regression.
3. Nexus ForgeIT using existing `Endpoint.createContract`, `ProxyTestManager` and fixtures: canonical session in MCP-only/combined, old Remote Access login still works, shared session/expiry/logout, wrong Origin/CSRF and alternate routes denied with zero Agent calls, typed CRUD/probe/approvals preserved. Public static shell access does not expose management routes.
4. Headless browser smoke through real Console assets with disposable profiles and local synthetic fixtures. Cover no secret in DOM after submission, browser storage, URL or API read responses; do not use live credentials.
5. A joined local acceptance fixture should prove browser creation/selection → normal Agent execution → actual read-only tool call, persisted connection after restart, then Disable denies the next dispatch. Use the existing Stage 3/4 fixtures and infrastructure rather than replacing the Agent execution with a browser stub. Report each exercised boundary honestly.
6. Run Console typecheck/test/build; focused Nexus tests then full Agent/Nexus verify; runtime Python regressions and `git diff --check`. Record results, skips, environment limits and CI in `docs/mcp-integrations/stage-5-evidence.md`.

Root-systemd mount isolation and the full deployed workflow from Stage 4 remain **NOT_VERIFIED** until a privileged disposable run is actually performed. Mocked browser/API tests cannot establish that boundary or full Milestone A. No production provisioning is authorized by this design. If the joined acceptance cannot be completed locally, keep its exact missing boundary as **NOT_VERIFIED**, do not claim complete Milestone A, and return implementation/evidence for review.

No merge or next roadmap stage follows automatically from implementation.
