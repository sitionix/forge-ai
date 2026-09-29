# Stage 6 OAuth — фактичні передумови та evidence

Дата: 2026-09-29. Branch: `feature/SITIONIX-156`, baseline `67069c56`.
Implementation реалізовано Tasks 1–6; фактичні фінальні результати нижче. Production deployment та live OAuth **NOT_VERIFIED**.

## Historical pre-implementation code map (baseline 67069c56)

- Agent `domain/model/McpAuthType.java`: NONE/BEARER/SECRET_HEADERS; OAuth відсутній.
- Agent `application/mcp/McpConnectionService.java`: disabled creation, explicit Enable, credential mutation/revocation.
- Agent `infrastructure/postgres/adapter/PostgresMcpConnectionRepository.java`: owner-scoped `SELECT ... FOR UPDATE`, transactional `change`.
- Agent `infrastructure/postgres/.../db/migration/V38__add_mcp_connections.sql`: connection/credential persistence; latest current migration V42.
- Agent `infrastructure/local/mcp/AesGcmMcpCredentialCipher.java`: чинний protected encrypted storage.
- Agent `application/mcp/McpGatewayService.java`: grant identity наразі залежить від ciphertext; без зміни логічної OAuth identity refresh зламає grants.
- Agent `application/mcp/McpProbeService.java` та `PostgresMcpToolInventoryRepository.java`: decrypt і ciphertext snapshot comparison; OAuth rotation має бути врахована.
- Agent `infrastructure/local/mcp/protocol/McpClientConfiguration.java`, `SdkMcpRemoteClient.java`: чинні SDK, SSL bundle/network policy; вони не реалізують OAuth.
- Nexus `ForgeAgentHttpClient`, `ForgeAgentMcpClientAdapter`, `McpClientMapper`, scoped `McpConnectionsExceptionHandler`: чинна typed proxy/error boundary.
- Console `src/operator/mcp-connection-form.js`, `settings.html`, `mcp-api.js`: поточна ручна credential форма, без OAuth popup/callback.

## Historical disposable probes (не повторені acceptance checks)

Власний fixture `/tmp/forge-stage6-oauth-probe-SANR4k`, Maven Boot parent **3.3.4**, OAuth client **6.3.3**, Java 21; synthetic credentials, local ephemeral loopback HTTP server. Personal configs і production credentials не змінювались. Це executable probes, **не JUnit suite**; Maven показав `No tests to run`.

Команди: `mvn -B -ntp -f <fixture>/pom.xml package dependency:build-classpath`, потім `java -cp <fixture classes + Maven classpath> OAuthProbe`, `ExpiryProbe`, `BootstrapProbe`.

| Capability | Результат | Межа доказу |
| --- | --- | --- |
| Стандартний Spring PKCE S256/code verifier | PASS | `OAuth2AuthorizationRequestCustomizers.withPkce()` |
| Code exchange + resource form parameter | PASS | `DefaultAuthorizationCodeTokenResponseClient`, стандартний converter extension; synthetic AS |
| Refresh token replacement | PASS | `DefaultRefreshTokenTokenResponseClient`; synthetic rotated response |
| Відсутній provider expiry не втрачається | PASS | converter delegate extension зберігає presence і raw 0/3600/absent; production integration не виконана |
| Default library missing expiry | PASS | спостережене synthesized значення **1 с**; не є provider contract |
| Boot library bootstrap без нового default login | PASS | з oauth2-client + starter-web fixture HTTP 200, 0 SecurityFilterChain; не production Forge acceptance |
| Boot explicit security exclusions fixture | PASS | HTTP 200, 0 SecurityFilterChain; це не доводить потребу нових exclusions |
| Redirect/private endpoint/NO_PROXY negative tests | NOT_VERIFIED | fixture налаштований Redirect.NEVER/NO_PROXY, негативні сценарії не виконані |
| SSL bundle, real cross-site cookies, application logs canaries | NOT_VERIFIED | ще не інтегровано у Forge |
| Serialized DB refresh/restart/cancel races | NOT_VERIFIED | code inspection не є runtime proof |
| Реальна GitHub App/OAuth App registration | NOT_VERIFIED | client ID/callback/permissions не надані й не перевірені |
| Live GitHub OAuth → MCP tools → runtime call | NOT_VERIFIED | synthetic AS не замінює live provider |
| Full Agent/Nexus/Console Stage 6 verify / CI | NOT_VERIFIED | Stage 6 implementation ще немає |

Перший bootstrap probe мав неправильне очікування HTTP 401 і впав; діагностичний повтор встановив фактичні HTTP 200/0 chains. Не заявляємо, що сама вузька OAuth dependency активує default login. Перший запуск ExpiryProbe до завершення compile отримав ClassNotFoundException; після завершення compile probe пройшов. Ці невдалі спроби не приховані й не є зеленими regression tests.

## Primary references / prerequisites

[GitHub MCP host integration](https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md): потрібний pre-registered host client; DCR відсутній.
[GitHub OAuth authorization](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps): PKCE S256, callback і token contract; refresh/expiry не слід вважати гарантованими.

Нормальний UX: choose MCP → OAuth → Connect → provider browser consent → safe Forge result → existing Test → permissions → explicit Enable. One-time registered-client setup належить Advanced/admin configuration. Stage 7 discovery/DCR не підміняється вигаданими client credentials. User-owned saved GitHub connection не використовується для destructive probes.

Approved design та конкретний implementation plan знаходяться в `docs/superpowers/specs/2026-09-29-mcp-stage-6-oauth-design.md` та `docs/superpowers/plans/2026-09-29-mcp-stage-6-oauth.md`.

## Implemented file / responsibility map

Paths below are relative to `services/forge-agent` (`A`),
`services/forge-nexus` (`N`), `services/forge-console` (`C`).

- `A/domain/.../model/McpOAuthConfiguration`, `McpOAuthAuthorization`,
  `McpOAuthTokens`, `McpOAuthCredentials`, `McpOAuthTransaction`, `McpOAuthCallback`;
  ports `McpOAuthClient`, `McpOAuthCredentialCipher`, `McpOAuthTransactionRepository`.
  Domain has no Spring HTTP/Jackson dependency. OAUTH protocol secret maps to BEARER
  only inside the existing SDK boundary.
- `A/infrastructure/local/.../mcp/oauth/McpOAuthHttpConfiguration`,
  `SpringMcpOAuthClient`, `McpOAuthProperties`, `JacksonMcpOAuthCredentialCipher`:
  BOM-managed Spring Security 6.3.3 Authorization Code/refresh token clients,
  standard PKCE/converters, resource parameter, existing endpoint policy/SSL bundle,
  bounded timeouts, redirects NEVER/NO_PROXY. Actual optional expiry/scope metadata
  is preserved rather than inferred from library synthetic defaults. Redacted
  form representation prevents standard RestTemplate DEBUG from printing secrets.
- `A/infrastructure/postgres/.../db/migration/V43__add_mcp_oauth.sql`,
  `PostgresMcpConnectionRepository`, `PostgresMcpOAuthTransactionRepository`,
  `PostgresMcpToolInventoryRepository`: existing encrypted credential row, public
  configuration and internal stable authorization UUID; one bounded temporary
  transaction/connection. Row lock order connection → transaction, claim persists
  before exchange. No second credential database or framework.
- `A/application/.../mcp/McpOAuthService`: start/callback/cancel, exact state/browser/
  configured issuer/config snapshot binding, one-shot callback, late completion
  discarded after cancel/edit/delete. Missing response issuer is permitted only
  with configured pinned token endpoint; it is not recorded as observed issuer.
- `A/application/.../mcp/McpCredentialService`, `McpProbeService`,
  `McpGatewayService`, `McpConnectionService`: shared encrypted credential resolution,
  serialized refresh, stable grants/inventory identity across rotation, policy admission
  before refresh and recheck before call, explicit reconnect on invalid grant/scope/auth
  rejection; no tool-write retry. Local delete commits before bounded best-effort revoke.
- `A/api-rest/.../mcp/McpOAuthController`, existing connection mapper/DTO/handler,
  `A/boot/.../config/AgentMcpProtectedConfiguration`: typed lifecycle/setup APIs,
  write-only client secret, ordinary main runtime composition. No new login/feature flag.
- `N/.../ForgeAgentHttpClient`, `ForgeAgentMcpClientAdapter`, `McpClientMapper`,
  existing use case/port: map → existing executor → map. Shared executor unchanged.
- `N/api-rest/.../mcp/ForgeAiMcpOAuthController`, `McpOAuthBrowserProperties`,
  `McpOAuthCallbackLoggingFilter`, `McpConnectionsExceptionHandler`, Boot registration:
  exact Origin/JSON OAuth start/cancel, per-transaction HttpOnly SameSite=Lax cookie,
  exact callback route, fixed 303 with allowlisted UUID/result only. Callback query/
  parameter map excluded from framework logging; Tomcat access log condition excludes
  marked callback. This is transaction binding, not an operator session. Existing RA
  application composition/guards retained.
- `C/src/operator/mcp-oauth-flow.js`, `mcp-connection-form.js`, `mcp-api.js`,
  `settings.html`, existing CSS; `mcp-oauth-result.html/js`: one Connect, synchronous
  popup, detached opener, same-origin BroadcastChannel UUID/result signal followed
  by authoritative get + Test; Advanced registered setup; Cancel/Close/retry usable.
  Existing form owns persistence/reconciliation. Enabled Reconnect requires explicit
  Disable. Test/Connect/permissions never Enable automatically. Reconnect compares
  metadata field values rather than JSON property order.

## Regression evidence

- Task 1: OAuth HTTP/TLS/cipher tests and existing protocol/storage regressions.
  Actual DEBUG canary first failed on standard form `toString`; scoped redacted form
  passed. Timeout classification failed first; typed ResourceAccessException cause
  mapping fixed it. No raw exception/body logging was copied.
- Task 2: real migration 42→43 preserves encrypted legacy bearer; real PostgreSQL
  single-use/owner/browser binding/cascade/inventory tests. Existing constructors
  updated, no compatibility overload path.
- Task 3: lifecycle/API tests cover denial, repeated/expired/wrong callback, cancellation
  before consent and during exchange, edit/delete late completion, approvals reset,
  usable encrypted token persistence without Enable. Focused 47 tests, zero skips.
- Task 4: focused application/gateway/persistence 53 tests, zero skips. Real row locks
  with explicit `pg_stat_activity` barriers prove refresh once under concurrency,
  delete uses newest rotated token and reconnect cannot be overwritten. Disabled/
  disallowed runtime denies before credential resolver/upstream. Unknown expiry does
  not invent refresh or offline access; missing refresh metadata preserved correctly.
- Task 5: 15 focused unit +12 ForgeIT tests, zero skips; typed HTTP/cookie/callback,
  wrong Origin/missing cookie local zero-upstream, actual Agent errors preserved,
  application/framework TRACE code/state/binding canaries. Accepted ForgeIT uses
  failsafe `-Dit.test=... verify`; the plan's initial surefire selector caused observed
  NoClassDefFoundError and was corrected without changing classpath/framework.
- Task 6: 30 focused tests; full Console then 656/656. Task 7 added Reconnect test:
  identical metadata in backend field order first failed (zero update/start), then
  passed after field-based comparison. Full Console now 657/657.

## Joined acceptance boundary

`McpSettingsAcceptanceHttpTest` reuses existing Stage 5 browser/native gateway fixture.
Disposable real Boot Agent/PostgreSQL, actual Nexus jar and built Console assets;
Chrome visits the actual Nexus path, no management HTTP stub. Pre-registered fake AS
uses `localhost:<ephemeral>` vs Nexus `127.0.0.1:<ephemeral>` to exercise cross-site
HTTP callback/SameSite=Lax; registered redirect/resource/PKCE verifier checked by AS.

Flow: fresh disabled create → Decline → Reconnect same resource/client setup → consent
→ authoritative Test/tools → disabled permissions → Nexus process restart → explicit
Enable → existing native Codex gateway fixture with two independent grants → one refresh
rotation → local delete/provider revoke → old grants denied, zero additional tool calls.
Cookies are inspected through Chrome CDP for the callback path and cleared after both
completed attempts. Provider opener is null; browser storage/input/rendered content has
no provider tokens/client secret. Runtime process gets grants, not provider credentials.

This fixture substitutes trusted execution lease/runtime boundary dependencies exactly
as the existing Stage 5 fixture does. It does **not** prove real systemd worker ownership,
UID/cgroup/OS secret denial, a normal whole-Forge restart, or live GitHub compatibility.
Agent restart is not inferred from repository reread; Nexus process restart is separate.
No user connection/production secrets/configuration was mutated.

## Verification commands / current results

| Check | Result | Actual evidence |
|---|---|---|
| Console typecheck | PASS | `npm --prefix services/forge-console run typecheck` |
| Console full tests | PASS | `npm --prefix services/forge-console test`: 32 files, 657 tests, zero failures |
| Console build | PASS | `npm --prefix services/forge-console run build` |
| Nexus full verify | PASS | `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/boot -am verify`: BUILD SUCCESS, 361 tests, zero failures/errors/skips (actual Surefire + Failsafe module summaries) |
| Agent full final verify | PASS | `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`: BUILD SUCCESS 02:28; 1465 total, 1455 executed, 10 skips; zero failures/errors |
| Joined browser/native OAuth | PASS | `mvn -B -ntp -Dapi.version=1.44 -Dforge.codex.stage5-e2e=true -pl services/forge-agent/boot -am -Dtest=McpSettingsAcceptanceHttpTest -Dsurefire.failIfNoSpecifiedTests=false test`: 1 test, zero skips; STAGE6_JOINED_OAUTH_GATEWAY_PASS |
| Generated callback canaries | PASS | Actual state/code/verifier, client secret/access/refresh tokens absent captured Agent/Nexus TRACE and enabled Tomcat access logs; ordinary Settings access positively observed |
| Runtime Python | PASS | `python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v`: 47 tests |
| Nexus client dependency analysis | PASS | `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-nexus/clients/agent-client -am dependency:analyze`: BUILD SUCCESS with transitive/starter test warnings; no new Nexus dependency |
| Agent local dependency analysis | PASS | `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/infrastructure/local -am dependency:analyze`: BUILD SUCCESS; new direct-used OAuth2 core and test logback-core explicitly declared with unchanged BOM versions; existing transitive/starter warnings retained |
| No Feign/global MCP enable switch in production | PASS | rg for OpenFeign/EnableFeignClients/FORGE_MCP_ENABLED/forge.mcp.enabled in production source returned no matches |
| Diff whitespace | PASS | `git diff --check` |
| Fresh CI | NOT_VERIFIED | No completed Stage 6 run yet; no PR created or changed |
| LIVE_PROVIDER | NOT_VERIFIED | Registered compatible GitHub client/callback/permissions not supplied; roadmap live acceptance remains unmet |
| Real Agent/whole-Forge restart | NOT_VERIFIED | Nexus restart + independent repository reread only |
| New OS-level secret/sandbox boundary probe | NOT_VERIFIED | Existing managed boundary preserved, not reprobed; joined fixture substitutions do not prove it |
| Production deployment / user GitHub connection | NOT_VERIFIED | Not deployed or mutated |

An initial joined denial/reconnect run failed bounded provider-window target polling.
A diagnostic rerun with unchanged production passed; later native/restart/access-log
runs also passed. This failure is retained as a fixture reliability observation,
not silently relabeled PASS. Initial focused Reconnect regression RED and own spy
cleanup failure are described above; green suites are fresh executions.

## Decisions / review

- Regular checkout branch follows user AGENTS.md, no worktree.
- Narrow RedactedForm addresses actual standard-client DEBUG leakage, without a
  replacement protocol client or global log suppression.
- Constructor metadata fields updated explicitly; no compatibility overloads.
- Transaction repository has one owner-scoped find for both pre/post-claim Cancel;
  completion explicitly requires claimedAt. Spring builds authorization URL outside
  DB lock, followed by snapshot check under lock.
- Delete returns latest state under existing row lock and commits before provider
  revoke; no outer remove transaction. Provider failure cannot restore local access.
- Nexus ForgeIT selected through accepted Failsafe verify; no classpath workaround.
- Exact callback ResponseBodyAdvice redirects already mapped safe errors; scoped
  handler remains sole public mapping owner, callback query/access logs suppressed.
- Detached provider opener + same-origin native BroadcastChannel replaces planned
  postMessage. IDs/result are only signals; authoritative get/Test validates success.
  Live provider browsing-context/COOP compatibility remains NOT_VERIFIED.

Whole-branch final read-only review and its findings will be recorded after the
remaining local verification. Operational instructions: [stage-6-operations.md](stage-6-operations.md).

Full Agent skips: CodexManagedRecoveryLifecycleTest (1), McpGatewaySdkHttpTest (1), McpGatewayRuntimeFilterTest (1), RemoteAccessManagementHttpIT (1), ForgeAgentPortAwareExecutionIT (6). Joined OAuth explicitly ran with zero skips; it is not inferred from the default suite.
