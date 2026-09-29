# MCP Stage 6 OAuth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Виконання inline/native збережене з попередніх погоджень; planning subagents не потрібні.

**Goal:** MCP → OAuth → одна кнопка Connect → browser consent → Forge check/tools/permissions без ручного token entry.

**Architecture:** Agent володіє authorization transaction, encrypted credentials та refresh. Nexus — чинний typed proxy і контрольований browser callback; Console — компактний Connect/Cancel/Reconnect UX. Spring OAuth primitives виконують protocol, чинний DB row lock забезпечує serialization; gateway та SDK лишаються власниками поточних MCP policy/protocol checks.

**Tech Stack:** Java 21, Spring Boot 3.3.4, BOM-managed Spring Security OAuth client 6.3.3, existing Jackson/PostgreSQL/AES-GCM, MCP SDK 0.18.4, Console native dialogs/Vitest, ForgeIT.

**Spec:** `docs/superpowers/specs/2026-09-29-mcp-stage-6-oauth-design.md` (approved 2026-09-29).

## Global Constraints

- Stage 6 pre-registered client only; Stage 7 discovery/DCR/CIMD та новий IAM/framework не додаються.
- One-time client setup у Advanced; немає окремої Save перед OAuth Connect. Немає registered client — truthful setup message, не вигаданий client ID.
- No automatic Enable/Disable. Start/Reconnect вимагає disabled connection; permissions редагуються лише disabled.
- Credentials/tokens/verifier не потрапляють у public DTO, logs, runtime, URL чи browser storage. Authorization code/state існують лише у protocol callback і видаляються з адреси через fixed 303 redirect; callback/access logs не записують query.
- Callback із контрольованої deployment configuration, issuer/resource/scopes/connection/browser/state binding, одноразовий bounded state.
- Existing local no-login Forge, Remote Access guards, global sidebar, Caffeine/Registry, typed client/executor/error contract, project/tool/schema policy та runtime grants зберігаються.
- Regular branch `feature/SITIONIX-156`, base `67069c56`; preserve user connection/unrelated changes. No PR changes/merge/deployment secret rotation.
- Live provider, OS/runtime та fresh CI checks мають власні PASS/FAIL/NOT_VERIFIED; fixtures не підміняють live acceptance.

## Review Focus

1. Token rotation змінює ciphertext, але не OAuth authorization identity; grants/inventory залишаються валідними до policy change/reconnect (Tasks 2/4).
2. Cancel після початку token exchange не дає late response відновити credentials; повторний callback не виконує exchange (Task 3).
3. Missing expiry/refresh/scope metadata не підміняється synthesized library values або вигаданим offline access (Tasks 1/4).
4. Popup blocker, denied consent, закрите вікно та parent navigation залишають керований UI без mutation replay (Task 6).
5. OAuth dependency не повертає default global login; реальний cross-site callback і HTTP localhost cookies працюють без широкого guard bypass (Tasks 1/5/7).

## File map / interfaces

Нижче `A` = `services/forge-agent`, `N` = `services/forge-nexus`, `C` = `services/forge-console`; package names залишаються чинними.

- Agent domain `A/domain/src/main/java/com/sitionix/forgeagent/domain/model/`: додати `McpOAuthConfiguration` (public client metadata), `McpOAuthAuthorization` (URL + private verifier), `McpOAuthTokens`, `McpOAuthCredentials` (client secret/tokens), `McpOAuthTransaction`, `McpOAuthCallback` (state/browser binding/code/error/issuer); додати OAUTH у `McpAuthType`. Secret models — redacted classes, не автосеріалізовані public records. Domain client-auth method — validated protocol string (`none`, `client_secret_post`, `client_secret_basic`), не Spring type.
- `McpConnection` додає nullable OAuth configuration та internal nullable `UUID oauthAuthorizationId`; public reads не видають internal ID/секрети. `McpConnectionState` допускає encrypted setup без usable token **тільки для OAUTH**; інваріант NONE/BEARER/SECRET_HEADERS не послаблюється.
- Agent ports: `McpOAuthClient.authorization(config, redirect, state)`, `exchange(config, credentials, code, verifier)`, `refresh(config, credentials)`, `revoke(config, credentials)`; `McpOAuthCredentialCipher.encrypt/decrypt(owner,id,typedCredentials)` перевикористовує чинний cipher; `McpOAuthTransactionRepository.insert/claim/findClaimed/delete(owner,connection,transaction)`.
- OAuth infrastructure `A/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/mcp/oauth/`: `SpringMcpOAuthClient`, `McpOAuthHttpConfiguration`, `McpOAuthProperties`, `JacksonMcpOAuthCredentialCipher`. Standard Jackson DTO/converter extension, не новий parser/SDK.
- Agent application `A/application/src/main/java/com/sitionix/forgeagent/application/mcp/`: `McpOAuthService.start(UUID id, String browserBinding)`, `complete(McpOAuthCallback callback)`, `cancel(UUID id, UUID transactionId, String browserBinding)`; `McpCredentialService.resolve(McpConnection): byte[]` (null для NONE, provider access token bytes для OAUTH). Secret values never mapped to public responses.
- Agent API: `A/api-rest/src/main/java/com/sitionix/forgeagent/api/mcp/McpOAuthController.java` і scoped handler; extend current connection request/response mapping for nullable OAuth setup і write-only client secret. Connection create/update приймають typed OAuth setup credential окремо від чинного bearer/header secret; усі constructors/mappers оновити без compatibility overloads. Start/result DTOs містять тільки transaction ID/authorization URL/connection ID/safe result.
- Nexus: extend `N/domain/.../ForgeAgentMcpClient`, `ManageAgentMcpConnections`, existing application proxy, `ForgeAgentHttpClient`, `ForgeAgentMcpClientAdapter`, `McpClientMapper`, API mapper/DTOs; add `N/api-rest/src/main/java/com/sitionix/forgeai/api/mcp/ForgeAiMcpOAuthController.java`. Adapter map → execute → map; shared executor не знає OAuth.
- Console: modify `C/src/operator/settings.html`, `mcp-connection-form.js/.d.ts`, `mcp-api.js/.d.ts`, existing CSS; add small `mcp-oauth.js/.d.ts`, `mcp-oauth-result.html`/entry for browser completion. Не дублювати navigation/router/dialog framework.

### Task 1: Standard OAuth client і безпечна typed credential encoding

**Files:** Agent local OAuth files above, `A/infrastructure/local/pom.xml`, `A/infrastructure/local/.../protocol/SdkMcpRemoteClient.java`; tests `.../src/test/java/com/sitionix/forgeagent/infrastructure/local/mcp/oauth/SpringMcpOAuthClientTest.java`, `McpOAuthHttpConfigurationTest.java`, `JacksonMcpOAuthCredentialCipherTest.java`; boot `AgentMcpProtectedConfigurationTest`.

**Interfaces:** input public `McpOAuthConfiguration` має issuer, authorization/token/optional revoke URI, client ID, standard client-auth method, scopes, resource. Client secret міститься тільки в `McpOAuthCredentials`. `McpOAuthTokens` зберігає nullable expiry/refresh expiry та наявність confirmed scope metadata; отримані scope не вигадуються. Configuration default connect 2s/read 5s, positive bounded configured durations, transaction TTL 10m; це defaults, не arbitrary maximum.

- [ ] RED: `codeExchangeSendsPkceResourceAndFixedRedirect`, `refreshRotatesTokens`, `missingExpiryRemainsUnknown`, `zeroExpiryIsNotUnknown`, `malformedExpiryFailsSafely`, `omittedRefreshDoesNotEraseExistingRefreshOnRotation`, `providerErrorDoesNotLeakCanary`, `redirectAndProxyDoNotReceiveClientSecrets`. Local HTTP/TLS fixtures only; wrong endpoints rejected before call. Run `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local -am -Dtest=SpringMcpOAuthClientTest,McpOAuthHttpConfigurationTest,JacksonMcpOAuthCredentialCipherTest -Dsurefire.failIfNoSpecifiedTests=false test`; missing implementation is RED, not successful coverage.
- [ ] Implement BOM-managed `spring-security-oauth2-client` **without OAuth/web-security starter or spring-security-config**. Use existing SSL bundles, JDK Redirect.NEVER/NO_PROXY; standard token clients/converters/PKCE. Standard converter delegate records actual expiry presence/value before synthesized dates; malformed/negative metadata safe rejected. Standard form POST for configured provider revocation; no provider body logging.
- [ ] Reuse/extract current SDK endpoint validation unchanged into local `McpEndpointPolicy` for OAuth token/revoke endpoints; exact configured private allowances only. Browser authorization URL is validated metadata, not a server-side arbitrary HTTP call. Test SSL bundle and existing SDK policy regressions.
- [ ] GREEN: focused command above plus `McpClientConfigurationTest,SdkMcpRemoteClientProbeTest`; boot regression asserts normal management remains available without new login. Disposable bootstrap gave HTTP 200 already; add exclusions only if actual application test proves necessary, not preemptively. Cipher tests assert original owner/purpose binding and no token in toString/public serialization.
- [ ] Commit task files only: `feat(mcp): add standard OAuth client primitives`.

### Task 2: Same-connection persistence і one-shot transaction

**Files:** `A/infrastructure/postgres/src/main/resources/db/migration/V43__add_mcp_oauth.sql`; existing `PostgresMcpConnectionRepository`, `PostgresMcpToolInventoryRepository`; new `PostgresMcpOAuthTransactionRepository`; domain models/ports above; `McpConnectionService`; test `A/boot/src/test/java/com/sitionix/forgeagent/it/tests/McpOAuthPersistenceIT.java` and existing `McpConnectionPersistenceIT`.

**Interfaces:** existing `mcp_connections` gets `oauth_configuration JSONB`, `oauth_authorization_id UUID`; existing credential row holds encrypted typed OAuth envelope. Single transaction table FK connection/owner: ID, state/browser hashes, connection/config snapshot, encrypted verifier, expiry, claimed timestamp. Latest migration currently V42; recheck before allocating V43. `McpToolInventoryRepository.replace` adds expected nullable OAuth authorization ID; compares ID for OAUTH, current ciphertext for existing auth types.

- [ ] RED: `existingCredentialsSurviveOAuthMigration`, `setupSecretIsEncryptedButConnectionNotUsable`, `stateClaimIsSingleUseAndOwnerScoped`, `wrongBindingDoesNotConsumeLegitimateState`, `cancelDeletesClaimedTransaction`, `deletedConnectionCascadesTransaction`, `refreshKeepsAuthorizationIdentityAndInventory`. Run boot IT via `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am -Dtest=McpOAuthPersistenceIT,McpConnectionPersistenceIT -Dsurefire.failIfNoSpecifiedTests=false test`; actual Docker/DB prerequisites failure => NOT_VERIFIED, not skip-as-PASS.
- [ ] Implement additive migration, typed Jackson metadata mapping, atomic transaction claim and existing row-lock ownership. New Connect sets authorization ID; refresh retains it; config/endpoint/auth changes reset it and revoke grants/approvals. No parallel credential DB store. Public metadata excludes internal identity and secret.
- [ ] Bound transactions by one current transaction per connection and TTL; delete expired rows during start/claim, no scheduler/crawler. Cancel/reconnect/delete and completion lock **connection then transaction** in the same order. Claim persists before exchange; claimed row remains cancellable while HTTP is in flight.
- [ ] GREEN: persistence tests plus CRUD/encryption/project-access regressions. Real DB concurrent barriers prove only one claim; process restart reads valid encrypted credentials and incomplete expired/claimed transaction safely requires Connect again.
- [ ] Commit: `feat(mcp): persist OAuth configuration and authorization transactions`.

### Task 3: Agent Connect / callback / cancellation lifecycle

**Files:** `McpOAuthService`, `McpOAuthController`, scoped `McpOAuthException`/handler, `AgentMcpProtectedConfiguration`; new application `McpOAuthServiceTest`, API `McpOAuthControllerTest`; extend normal management guards only for exact new routes.

**Interfaces:** Agent `POST /api/v1/integrations/mcp/connections/{id}/oauth/start`, `DELETE .../{id}/oauth/transactions/{transactionId}`, internal `POST /api/v1/integrations/mcp/oauth/callback`. Configured callback `http://127.0.0.1:9099/fgaisox/api/v1/infrastructure/agents/integrations/mcp/oauth/callback`; installation override must be explicit controlled URI. State includes transaction UUID + unguessable standard Spring nonce; browser binding passed through typed request and hashed at rest.

- [ ] RED: `connectRequiresDisabledConfiguredConnection`, `callbackPersistsOnlyUsableEncryptedToken`, `denialDoesNotExchange`, `invalidStateBrowserIssuerResourceRedirectRejectedBeforeExchange`, `repeatedExpiredCallbackDoesNotExchange`, `lateExchangeAfterCancelOrEditCannotPersist`, `reconnectResetsApprovalsWithoutEnabling`, `safeErrorCanaries`. Run application focused `-pl services/forge-agent/application -am -Dtest=McpOAuthServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` with standard Maven flags.
- [ ] Implement start validates registered config and disabled state, snapshots owner/endpoint/resource/scopes/issuer/redirect. Claim verifies callback state and browser binding **before** exchange. Provided `iss` must equal configured issuer; missing `iss` is permitted for pre-registered providers without response issuer, with pinned configured token endpoint. Do not claim a missing issuer was observed/verified.
- [ ] Exchange outside DB lock; finalize under connection→transaction lock only if same claimed unexpired transaction/config/identity still exists. Cancel while in-flight deletes row; late token result discarded/wiped. No auto replay. API returns safe metadata, not raw provider error_description/cause/body.
- [ ] GREEN: application/API tests, unchanged guard/CRUD tests. OAuth success ends at encrypted persistence; Console triggers existing separate Test request, preserving current Nexus timeout contract. No background job/status API.
- [ ] Commit: `feat(mcp): add fail-closed OAuth connection lifecycle`.

### Task 4: Shared credential resolution / serialized refresh

**Files:** `McpCredentialService`, `McpProbeService`, `McpGatewayService`, current gateway wiring; tests `McpCredentialServiceTest`, `McpGatewayServiceTest`, `McpProbeServiceTest`, joined `McpGatewayEndToEndHttpTest`, persistence IT.

**Interfaces:** `resolve(McpConnection)` returns current protocol secret bytes; existing SDK receives BEARER for logical OAUTH. OAuth identity uses `oauthAuthorizationId`, existing auth identity stays ciphertext-based. Domain remains free of Spring HTTP/Jackson and provider token DTOs never reach runtime.

- [ ] RED: `twoCallsRefreshOnlyOnce`, `refreshVsDeleteAndReconnectNeverOverwrite`, `missingExpiryDoesNotTriggerInventedRefresh`, `expiredWithoutRefreshRequiresReconnect`, `invalidGrantMakesConnectionUnusable`, `scopeChangeRequiresConsent`, `refreshRotationKeepsExistingGrant`, `deniedGrantDoesNotRefreshOrCallUpstream`. Real row lock/concurrency tests use explicit barriers and bounded waits, not sleeps/Atomic test hooks in production.
- [ ] Implement OAuth refresh inside existing `connections.change` lock with bounded token HTTP call and atomic encrypted replacement; preserve absent refresh token according to response semantics, honor optional refresh expiry. Invalid grant clears usable status and revokes runtime access; unknown expiry is not claimed immortal, and observed 401/403 surfaces explicit reconnect/scope error without interrupted tool-write retry.
- [ ] Resolve only after policy admission; recheck grant/policy immediately before tools/call. Never refresh disabled/revoked/disallowed runtime connections. No gateway grants carry provider token; schema recheck/session/list/call deadlines unchanged.
- [ ] GREEN: focused application/gateway fixtures and DB races; rotation stable identity, reconnect/config changes invalidate it. Existing bearer/header/NONE, same-session schema mismatch, disable/project/tool/schema denial zero-upstream tests remain green.
- [ ] Commit: `feat(mcp): resolve OAuth credentials and serialize token refresh`.

### Task 5: Typed Nexus boundary і controlled browser callback

**Files:** Nexus existing port/use case/adapter/mapper/client/API DTOs listed above; new `ForgeAiMcpOAuthController`, properties and `McpOAuthCallbackTest`; current `NexusMcpManagementIT`, `NexusAgentMockMvcEndpoints`, `ForgeAgentWireMockEndpoints` typed ForgeIT fixtures; `McpConnectionsExceptionHandlerTest`.

**Interfaces:** public POST `.../connections/{id}/oauth/start`, DELETE `.../connections/{id}/oauth/transactions/{transactionId}`, exact GET `.../integrations/mcp/oauth/callback`. Nexus creates random HttpOnly per-transaction browser-binding cookie, SameSite=Lax, path `/fgaisox/api/v1/infrastructure/agents/integrations/mcp` (common callback/cancel scope), expiry=transaction TTL, Secure on HTTPS. Plain localhost HTTP is supported without globally weakening cookies. Start public result only transaction ID/authorization URL. OAuth start/cancel require exact configured browser Origin and JSON/non-simple request; cancel additionally validates that transaction's cookie. This is transaction CSRF protection, not a new operator login/session.

- [ ] RED: typed start/cancel/callback forwarding, no operator login added, absent/invalid cookie zero Agent callback calls; wrong state reaches owning Agent boundary but causes zero provider exchange calls (Task 3). Wrong Origin/cancel binding rejected locally for these OAuth mutations; exact cross-site callback allowed, alternate route denied; safe upstream status/code/message/correlation retained. Existing main MCP fetch uses `credentials:'omit'`, so test actual OAuth cookie acceptance explicitly. Use existing `Endpoint.createContract(...)`/ProxyTestManager fixtures; no custom MockMvc harness.
- [ ] Extend existing typed client and map → execute → map; generic executor unchanged. Callback alone accepts provider GET and verifies transaction cookie; no wide management-auth/CSRF exemptions, and no reintroduction of deleted main operator guards. RA guards remain unchanged. No returnUrl or Host-derived redirect accepted. Clear transaction cookie after completion/cancel.
- [ ] Send fixed 303 to `/fgaisox/operator/mcp-oauth-result.html` with allowlisted safe connection ID/transaction ID/result code only. Error/denial page cannot expose code/state/verifier/provider body. Exclude callback query from application/access logging and capture canaries across framework logs; no raw exception logging.
- [ ] GREEN: `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am -Dtest=NexusMcpManagementIT,McpOAuthCallbackTest,ForgeAgentMcpClientAdapterTest,McpClientMapperTest,McpConnectionsExceptionHandlerTest -Dsurefire.failIfNoSpecifiedTests=false test`. Adapter tests mock executor; integration proves actual typed endpoint/cookies/errors.
- [ ] Commit: `feat(mcp): proxy OAuth lifecycle and handle browser callback`.

### Task 6: Normal compact OAuth UX

**Files:** Console files above; new `C/tests/mcp-oauth.test.ts`, extend `mcp-connection-form.test.ts`, Settings tests and real-browser acceptance fixture using built assets against real Nexus.

**Interfaces:** `McpOAuthFlow.connect(connection, command)`, `cancel()`, `dispose()` coordinates existing API create/update plus new start/cancel. `McpApi.request` gets optional `credentials='omit'`; only OAuth start/cancel pass `'same-origin'` so Set-Cookie/cancel binding work; existing calls retain current transport behavior. Primary CTA **Connect** (existing saved connection **Reconnect**); one-time provider fields under **Advanced**. The normal form shows integration identity, auth choice and provider description, not token/client diagnostics.

- [ ] RED: `oneConnectSavesAndOpensConsent`, `oauthDoesNotAskForBearerToken`, `popupCreatedDuringClickBeforeAwait`, `popupBlockedLeavesRetryAndCloseUsable`, `deniedOrClosedPopupDoesNotReplayMutation`, `lateOrForeignMessageIgnored`, `successReReadsAndTestsBeforePermissions`, `probeFailureKeepsSavedDisabledConnection`, `enabledReconnectRequiresExplicitDisable`, `advancedSetupIsSecondary`, `noTokensInStorageOrRenderedHtml`.
- [ ] Implement popup opened synchronously on click, navigated only after successful typed start. Connect shows **Continue in the sign-in window** with active Cancel/Close; not page-wide pending lock. No auto-open after popup blocked; offer explicit retry button/link to the same authorized URL while current transaction remains valid. Do not create duplicate saved connection on retry/cancel; reuse existing uncertain-create reconciliation.
- [ ] Completion page removes protocol query via backend redirect, posts safe signal only to configured same-origin opener, then closes; parent accepts exact origin + popup Window + transaction/connection IDs. Signal is not trusted as completed state: authoritative get + existing Test confirm readiness. Popup-close/parent-unload cancellation is best-effort and server TTL is final guarantee, not an invented promise of guaranteed abort.
- [ ] GREEN: `npm --prefix services/forge-console test -- tests/mcp-oauth.test.ts tests/mcp-connection-form.test.ts`, then typecheck/tests/build. Desktop/mobile keyboard focus, Escape/Cancel, readable provider error, no giant raw error panel; global Settings/Projects navigation remains usable.
- [ ] Commit: `feat(console): connect MCP with browser OAuth`.

### Task 7: Joined acceptance, review і exact evidence

**Files:** `docs/mcp-integrations/stage-6-evidence.md`, normal runtime acceptance scripts/tests (existing `scripts/runtime/tests` and Stage 5/4 gateway fixtures); update operational OAuth docs, not roadmap stages.

- [ ] Run fake AS + real disposable Agent/Postgres/Nexus + built Console browser: fresh connection → Connect → cross-site provider → Forge → tools → disabled-only policy → explicit Enable → current gateway runtime call → refresh/reconnect. Check actual SameSite/localhost callback, no sensitive cookies/token browser storage, no popup cross-origin trust, restart and runtime no-token isolation. Fixtures use their own DB/dirs and never mutate user's saved GitHub connection.
- [ ] Run `npm --prefix services/forge-console run typecheck`, `npm --prefix services/forge-console test`, `npm --prefix services/forge-console run build`; full `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` and Nexus equivalent; `python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v`; Agent local `dependency:analyze`; `git diff --check`. Record counts/skips and dependency warnings accurately.
- [ ] Review whole branch with one fresh read-only reviewer as required by `executing-plans`; fix factual/security/architecture findings and re-run affected tests. Preserve PR metadata/comments/reviews/merge state.
- [ ] Live GitHub acceptance only after owner provides valid registered Forge app/client/callback prerequisites through protected setup. No secret in chat. Without them **LIVE_PROVIDER = NOT_VERIFIED** and Stage 6 live acceptance remains unmet; fake AS does not close it. Fresh PR CI is NOT_VERIFIED until actual run exists and finishes green.
- [ ] Commit exact evidence; stop for review. No Stage 7 work and no automatic PR creation/merge.

## Self-review / approval boundary

All Stage 6 roadmap bullets map to Tasks 1–7. Five Review Focus cases have explicit regression ownership. Standard primitives, secret/public separation, refresh identity, callback single use, row lock ordering and safe UX are spelled out; existing user connection is not a fixture. OAuth App registration/live acceptance remain prerequisites, not assumptions. Plan is ready for review; production implementation has not started.
