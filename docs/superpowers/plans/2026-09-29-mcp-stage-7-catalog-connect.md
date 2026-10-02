# MCP Stage 7 Catalog Connect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Approved by user for inline implementation.

**Goal:** Catalog row Connect starts provider sign-in without the Custom MCP form; Custom remains a separate flow.

**Architecture:** Agent owns authentication discovery, issuer-bound client selection/registration and connection creation. Nexus uses its existing typed Agent client and scoped browser boundary. Console reuses the Stage 6 OAuth popup/result lifecycle without depending on the connection form.

**Tech Stack:** Existing Java/Spring Boot, Spring HTTP/Jackson/OAuth client primitives, PostgreSQL/AES-GCM, Console JavaScript/TypeScript tests, ForgeIT and existing joined Chrome fixture. No new framework/dependency stack.

**Spec:** `docs/superpowers/specs/2026-09-29-mcp-stage-7-catalog-connect-design.md` — user-approved 2026-09-29. The plan is approved for implementation. Execution method: inline, current checkout/branch; no worktrees or implementer subagents.

## Global Constraints

- One catalog action: Connect → provider authorization → safe callback → authoritative read/Test → list. No Name/URL/auth/client-settings form in this path.
- Preserve Add custom MCP, disabled-only permissions and explicit Enable; no automatic tool/project grants or Enable/Disable.
- Controller → use case → domain port → adapter → existing executor → existing `ForgeAgentHttpClient`; adapter map → execute → map.
- Metadata/registration use Spring HTTP/Jackson, existing endpoint/TLS policy, redirect NEVER and NO_PROXY. No raw exception/body/header logging.
- Reuse existing encrypted connection credentials and Stage 6 transactions; no additional credential database or global MCP flag.
- Discovery runs only on Connect; public Registry pagination/Caffeine are unchanged.
- No hidden stdio GitHub bridge, shared confidential secret in distributable, central OAuth service, PR metadata/comments/reviews or merge.
- Live GitHub requires a Forge-owned registered App and protected provisioning. Missing App is a real acceptance blocker, not a successful mocked compatibility claim.
- Protocol reference: verified accessible https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization. Roadmap's 2026-07-28 registration URL was inaccessible during plan preparation: NOT_VERIFIED. Verify that reference before claiming its revision supported; do not upgrade SDK wire protocol for discovery.

## Review Focus

1. A no-auth MCP opens no pointless sign-in window; a GET with an open event stream does not hang discovery indefinitely — Task 1/4.
2. A catalog URL template/PAT-only provider does not silently create an unusable connection or open the Custom form — Task 2/4.
3. Forged metadata cannot redirect Forge credentials or bind a client to another issuer/resource — Task 1/2.
4. Popup blocked, double-click, cancellation and uncertain POST outcome do not replay connection creation or leave global Settings blocked — Task 4.
5. Multiple connections/accounts for the same provider, reconnect and late callbacks cannot reuse the wrong tokens/approvals — Task 2/5.

## Shared contracts

Agent domain additions under `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/`:

- `model/McpAuthenticationMetadata.java`: record holding `boolean oauthRequired`, `URI resource`, `URI issuer`, `URI authorizationEndpoint`, `URI tokenEndpoint`, optional `URI revocationEndpoint`, optional `URI registrationEndpoint`, `boolean clientIdMetadataSupported`, `Set<String> scopes`, `Set<String> clientAuthenticationMethods`, `Set<String> codeChallengeMethods`.
- `model/McpOAuthClientRegistration.java`: `McpOAuthConfiguration configuration`, `McpOAuthCredentials credentials`; redacted `toString()` and never a public DTO.
- `model/McpConnectResult.java`: `McpConnection connection`, nullable `McpOAuthStart authorization`; connection must be disabled and the optional start must reference that connection.
- `port/McpAuthenticationDiscovery.java`: `McpAuthenticationMetadata discover(URI endpoint, long deadlineNanos)`; policy failure/unavailable/invalid metadata are safe exceptions.
- `port/McpOAuthClientRegistrationProvider.java`: `McpOAuthClientRegistration resolve(McpAuthenticationMetadata metadata, long deadlineNanos)`; install-client priority, then supported CIMD, then supported DCR, otherwise safe setup-required error.

No failure enum, routing framework or generic request/deadline/caching abstraction.

### Task 1: Protected-resource/authorization metadata discovery

**Files:** create `SpringMcpAuthenticationDiscovery.java` and `McpOAuthDiscoveryConfiguration.java` under Agent local `mcp/oauth`; create domain metadata/port above; test `SpringMcpAuthenticationDiscoveryTest.java` and configuration test in the same package. Reuse `McpEndpointPolicy`, `McpOAuthProperties`, `McpOAuthHttpConfiguration`.

**Interfaces:** produces `McpAuthenticationDiscovery.discover(URI, long)` and typed metadata from the shared contract. No persistence mutations.

- [ ] Write local-HTTP production-configuration tests: parsed Bearer challenge resource_metadata/scope, path/root protected-resource fallback, RFC8414/OIDC issuer paths, exact issuer/resource mismatch, ambiguous authorization servers, non-S256 server, 401 without usable metadata, 403, no-auth, open response, malformed/oversize/redirect/private-host responses. Assert no Forge Authorization/cookie/client credentials sent.
- [ ] Run `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local -am -Dtest=SpringMcpAuthenticationDiscoveryTest,McpOAuthDiscoveryConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`; confirm RED before implementation.
- [ ] Implement `discover(URI, long deadlineNanos)` using Spring HTTP response exchange for the endpoint challenge (close the response without consuming an advertised MCP event stream). Read metadata using typed Jackson DTOs; parse headers with Spring's existing challenge parser for quoted Bearer parameters if the installed API provides it. If it does not, keep `parseChallenge(HttpHeaders)` as a private tested method in this adapter, supporting multiple challenges, quoted commas/escaped quotes and duplicate-parameter rejection; no parser framework. Follow only standard discovery paths; fallback only on endpoint-not-found, not malformed/security failure.
- [ ] Use existing OAuth connect/read/TLS settings and probe response cap (`forge.mcp.probe.max-response-bytes`, default 1048576). Add positive `forge.mcp.oauth.discovery-timeout=20s` as a total metadata/registration preparation deadline; validate it is at most 25s to stay below the ordinary 30s Agent HTTP budget. `McpConnectService` computes one monotonic deadline and passes it to discovery and registration; each request is capped by remaining time. This is a management preparation invariant, not a tool-call timeout ceiling. Do not allocate a generic deadline framework. Configuration tests must reject budgets incompatible with the documented normal management bound.
- [ ] Apply endpoint policy before every outbound URL, exact resource/issuer checks, explicit selection when multiple authorization servers have no uniquely configured issuer. Require authorization-code/S256 compatibility. Treat challenge scopes as authoritative; when absent, use validated protected-resource scopes_supported per pinned MCP scope-selection contract (final-review ruling in Stage 7 evidence).
- [ ] Run focused tests GREEN; commit only Task 1 files.

### Task 2: Issuer-bound client selection and Agent Connect orchestration

**Files:** create `SpringMcpOAuthClientRegistrationProvider.java`, `McpOAuthRegistrationProperties.java` under Agent local `mcp/oauth` and wire them in `McpOAuthDiscoveryConfiguration.java`, shared registration/result records and port, `application/mcp/McpConnectService.java`, `api/mcp/McpConnectController.java`; modify `AgentMcpProtectedConfiguration`, existing `McpOAuthException` safe factories, Agent application.yml and scoped handler only as required. Tests: `McpOAuthClientRegistrationProviderTest`, `McpConnectServiceTest`, `McpConnectControllerTest`.

**Interfaces:** `McpConnectService.connect(String displayName, URI endpoint, String browserBinding): McpConnectResult`; Agent `POST /api/v1/integrations/mcp/connect`, input displayName/endpoint/write-only browserBinding, output connection/optional authorization. BrowserBinding is required for OAuth and generated by Nexus, never taken from the browser JSON.

- [ ] Write tests for installation-client > CIMD > DCR priority; exact issuer binding; missing/unreachable CIMD; accepted redirect URI/application_type; public/private endpoint denials; DCR auth-method validation and redacted failures. Assert no registration request for pre-registered clients and no confidential credential sent to another endpoint.
- [ ] Define typed Boot `forge.mcp.oauth.clients` entries (`issuer`, `client-id`, `client-authentication-method`, optional `client-secret-file`, `scopes`) and optional HTTPS `forge.mcp.oauth.client-id-metadata-uri`. Protected file loading uses the existing protected reader; no secret-valued application.yml fields. CIMD document must have exact client_id/callback identity and matching auth method; unavailable/mismatched configuration cannot be treated as usable.
- [ ] Implement registration with existing Spring HTTP/Jackson: DCR request uses current controlled callback, client_name Forge, authorization_code, response_type code, application_type native for loopback callback and web otherwise, supported token_endpoint_auth_method. Validate returned client ID/secret/auth method/redirects; keep returned credentials only in the existing encrypted connection row. Do not build registration-access-token management or a parallel cache.
- [ ] Write `connectCreatesDisabledOAuthThenStartsExistingTransaction`, `noAuthConnectCreatesDisabledConnection`, `missingRegistrationMakesZeroConnectionWrites`, `templateAndPatOnlyFailSafely`, `discoveryFailureMakesZeroMutations`, `startFailureKeepsCreatedConnectionDisabled` tests. No connection creation until successful metadata/client resolution. A safe failed outcome does not claim the connection was never saved.
- [ ] Run focused local/application/api tests RED, then implement `connect(...)` through existing `McpConnectionService.create(...)` with SELECTED empty projects and existing `McpOAuthService.start(...)`. No DB lock/transaction held during network discovery/registration; Stage 6 creation/start boundaries remain.
- [ ] Use safe `MCP_OAUTH_SETUP_REQUIRED` for unavailable registration and specific safe template/PAT-only guidance; preserve existing unavailable/invalid/denied envelopes. No catch-all classification by exception message.
- [ ] Run focused tests GREEN and commit Task 2. Do not invent or register a GitHub App under an unknown owner.

### Task 3: Typed Nexus Connect and browser-binding boundary

**Files:** modify Nexus domain `ForgeAgentMcpClient`, `ManageAgentMcpConnections`, `AgentMcpConnectionsUseCase`; add domain `McpConnectCommand`/`McpConnectResult`; add typed inbound/outbound DTOs under agent-client; modify `ForgeAgentHttpClient`, `ForgeAgentMcpClientAdapter`, `McpClientMapper`, `ForgeAiMcpOAuthController`. Tests/fixtures extend existing `NexusMcpOAuthIT`, `NexusAgentMockMvcEndpoints`, `ForgeAgentWireMockEndpoints`, existing ForgeIT fixture directories.

**Interfaces:** Nexus `POST /api/v1/infrastructure/agents/integrations/mcp/connect`; browser JSON `{displayName,endpoint}` only. Use case/port `connect(McpConnectCommand command, String browserBinding): McpConnectResult`. Client typed call `connectMcp(McpConnectOutbound request): McpConnectInbound`.

- [ ] Write direct mapper/adapter/use-case tests and ForgeIT contracts: OAuth result sets existing per-transaction HttpOnly SameSite=Lax cookie/no-store; no-auth result has no auth cookie; forged browserBinding/client settings/Origin/content-type/local validation denial makes zero Agent calls. Validate connection/start IDs agree.
- [ ] Run focused Nexus tests RED before production edits.
- [ ] Reuse the existing OAuth controller's exact Origin/content-type check for typed body and existing binding/cookie helper. Preserve old start/callback/cancel routes. Ordinary Custom CRUD stays unchanged.
- [ ] Implement request mapper → existing executor.execute typed call → response mapper. Do not change `ForgeAgentClientCallExecutor`, invent another transport or move errors out of scoped advice.
- [ ] Add valid upstream 409 setup-required, 500, malformed502 and unavailable503 ForgeIT cases; assert synthetic secrets absent TRACE/access logs/API response. Use `Endpoint.createContract`, ProxyTestManager and existing fixtures.
- [ ] Run tests GREEN; commit Task 3.

### Task 4: Catalog Connect without Custom form

**Files:** modify Console `mcp-catalog.js`, `settings-page.js`/`.d.ts`, `mcp-api.js`/`.d.ts`, `mcp-oauth-flow.js`/`.d.ts`; add focused `mcp-catalog-connect.js`/`.d.ts` only to separate the existing Custom form owner from catalog orchestration; adjust Settings markup/CSS for row Connect/status. Tests: `mcp-catalog.test.ts`, `mcp-compact-settings.test.ts`, `mcp-oauth.test.ts`, new `mcp-catalog-connect.test.ts`, `mcp-api.test.ts`.

**Interfaces:** `McpApi.connectCatalog({displayName,endpoint}, signal): Promise<McpConnectResult>`; the TypeScript result is `{connection:McpConnection, authorization:{transactionId:string,connectionId:string,authorizationUrl:string}|null}` with same-origin credentials for cookie; `McpCatalogConnect.connect(server)` handles one active attempt and returns control to Settings callbacks. Add `McpOAuthFlow.connectCatalog(server)` with injected `startCatalog:(server,signal)=>Promise<McpConnectResult>`; this entry opens the popup synchronously before awaiting the API result, shares validated navigation/result/cancel logic, handles no-auth result separately, and does not call the Custom persist function. Keep Custom `connect(connection,command)` behavior.

- [ ] Write `catalogConnectDoesNotOpenCustomDialog`: one explicit Connect button per row; synchronous popup open; one connect API call; zero projects/create/update/form.openCreate calls. API result performs provider navigation immediately, not an Authentication form.
- [ ] Add result tests: safe callback → authoritative GET → existing Test → refresh list; no Custom dialog after success; no setEnabled/approve calls. No-auth closes unused popup and reads/tests the saved connection without provider redirect.
- [ ] Add blocked-popup reopen, double-click, denial, cancellation, route disposal, uncertain create response and late callback tests. Only the affected row is pending; global sidebar/search/close remain usable. No automatic POST replay.
- [ ] Run focused Console tests RED, implement wiring, run GREEN. `settings-page.add()` becomes Custom-only; remove server prefill argument/branch from `McpConnectionForm.openCreate` and its declaration/caller/tests. `mcpCustom` continues opening the existing form.
- [ ] Remove arrow/Configure semantics from catalog selection; use row metadata + Connect action and local safe result/error. Registration unavailable shows a short Forge setup message, never a Client ID form. URL templates and PAT-only responses give Custom guidance without silently switching flow.
- [ ] Run Console typecheck/all tests/build and commit Task 4. No new frontend framework or provider preset engine.

### Task 5: Joined browser regression, normal runtime and evidence

**Files:** extend Agent boot `McpSettingsAcceptanceHttpTest` and existing provider fixture, Console `scripts/mcp-settings-browser-smoke.mjs`; add `docs/mcp-integrations/stage-7-evidence.md`/`stage-7-operations.md`. Correct current operational instructions to separate Custom Advanced setup from catalog Connect, without rewriting historical evidence.

- [ ] Extend existing disposable joined Agent/Postgres/Nexus/Chrome fixture with discoverable AS/DCR endpoint: one catalog Connect, cross-site consent/return, authoritative read/probe, no Custom form and no auto-enable. Two same-provider connections keep separate authorization identities. Disabled policy/explicit Enable/gateway checks reuse Stage 3/4 fixtures.
- [ ] Exercise pre-registered-client and supported/unavailable CIMD with disposable metadata HTTP fixtures; wrong issuer/resource/client metadata/callback and malicious endpoints produce zero credential/tool calls. Keep synthetic canaries in real logs.
- [ ] Run focused joined acceptance plus existing OAuth/persistence/refresh/management regressions; write exact executed/skipped counts, RED/GREEN evidence and boundaries.
- [ ] Run `npm --prefix services/forge-console run typecheck`, `npm --prefix services/forge-console test`, `npm --prefix services/forge-console run build`.
- [ ] Run `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` and corresponding `services/forge-nexus/boot` full verify.
- [ ] Run affected Agent local/Nexus agent-client reactor `dependency:analyze`; document existing warnings separately from new dependencies. Run `python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v` and `git diff --check`.
- [ ] Using normal existing provisioning, run `just start` when local sudo authentication is available and read-only Settings/browser smoke against :9099. Preserve user's saved GitHub bearer connection; never clear/replace its credentials for acceptance. Do not claim empty inventory on this nonempty installation.
- [ ] Request existing feature-branch CI without creating/updating a PR; inspect exact SHA and fresh job results. Live provider remains NOT_VERIFIED until real Forge App provisioning and consent/token/MCP probe are observed.
- [ ] Commit evidence/operations, stop for user review; do not merge.

## Plan self-review

- Spec sections map to Tasks 1–5; catalog metadata/cache and Custom management are preserved.
- Shared record/port/route names above are used consistently; no alternate executor/error path.
- Each Review Focus input has a responsible regression task.
- GitHub registration is not fabricated by DCR. A missing Forge App remains an explicit external prerequisite, so fake-AS GREEN cannot be reported as live GitHub GREEN.
- Plan approval authorizes inline code execution; it does not authorize registration under an unknown GitHub owner, publishing a metadata site, adding a cloud service or distributing confidential credentials.
