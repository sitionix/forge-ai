# Stage 6 OAuth — фактичні передумови та evidence

Дата: 2026-09-29. Branch: `feature/SITIONIX-156`, baseline `67069c56`.
Stage 6 production implementation, deployment та live OAuth наразі **NOT_VERIFIED**.

## Code map

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

## Disposable probes

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
