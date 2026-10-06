# MCP Stage 6 — браузерне підключення через OAuth

Статус: APPROVED користувачем 2026-09-29; реалізація триває; повний browser OAuth flow ще недоступний.

## Продуктовий результат

Користувач обирає MCP у Settings, вибирає **OAuth** і натискає **Connect**. Ця одна дія зберігає налаштування та відкриває окреме вікно браузера для входу й підтвердження дозволів провайдера. Окремої кнопки Save перед авторизацією немає. Після повернення Forge перевіряє підключення, показує tools і дає підтвердити project/tool permissions. Введення PAT не є основним сценарієм OAuth-підключення. Bearer/no-auth/secret headers залишаються доступними для серверів, які використовують їх.

Діалог не очікує завершення браузерного входу із заблокованими Close/Cancel. Відмова або закриття OAuth-вікна залишає connection disabled і дозволяє почати Connect повторно. Успішний OAuth не вмикає connection автоматично; Enable зберігає чинну explicit semantics.

## Scope та підтверджені передумови

Це Stage 6 `docs/mcp-integrations/roadmap.md`: pre-registered client, Authorization Code + PKCE, encrypted token storage, refresh/reconnect. Automatic discovery, DCR/CIMD та універсальна автоматична авторизація всіх Registry servers належать Stage 7 і не додаються тут.

Поточний `McpAuthType` підтримує NONE/BEARER/SECRET_HEADERS. OAuth routes, callback та OAuth credential lifecycle відсутні. Основний Agent використовує Spring Boot 3.3.4. Disposable probes підтвердили сумісні Spring Security OAuth client 6.3.3 primitives для PKCE S256, code exchange, resource parameter та refresh rotation. Missing expiry потребує вузького стандартного converter extension: бібліотека сама підставляє 1 с, що не можна зберігати як заявлений провайдером expiry. TLS/private endpoint/error/cross-site/browser інтеграція у Forge ще **NOT_VERIFIED**; точні межі записані в `docs/mcp-integrations/stage-6-evidence.md`.

Офіційний GitHub Remote MCP потребує токена, отриманого host application. GitHub вимагає pre-registered GitHub App або OAuth App; автоматичної DCR для цього сервера немає. Реальна Forge app registration, client ID, дозволений callback і доступні permissions зараз **NOT_VERIFIED**. Не використовувати чужий client ID/secret і не вшивати спільний confidential secret у Forge distributable.

Референс: https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md

## Ownership і чинні точки інтеграції

- Console: `services/forge-console/src/operator/mcp-connection-form.js` і Settings dialogs — кнопка Connect, pending/cancel/reconnect та результат. Токени ніколи не повертаються у форму або browser storage.
- Nexus: чинні MCP controller/use case/domain port/adapter/mapper та `ForgeAgentHttpClient` — typed start/callback/result calls за чинним map → execute → map. Public error envelope залишається у scoped MCP handler.
- Agent: чинний MCP application layer володіє connection та OAuth lifecycle. Infrastructure використовує стандартні Spring OAuth client primitives для code exchange/refresh, а не власну реалізацію OAuth protocol.
- Persistence: `McpConnectionRepository.change(...)` уже має owner-scoped row lock та atomic update; `mcp_connection_credentials` і `AesGcmMcpCredentialCipher` забезпечують чинний encrypted storage. OAuth додає тільки потрібну конфігурацію/metadata до того самого connection; secret payload містить provider tokens/client secret, а не новий паралельний credential store.
- Probe/gateway: розв'язання актуального access token належить Agent application boundary перед чинним MCP protocol call. Runtime одержує тільки чинний execution grant; provider/refresh tokens залишаються на Agent.

## Основні flows

1. **Start:** backend перевіряє connection і конфігурацію registered client; створює одноразову обмежену в часі transaction з PKCE S256 і browser binding; повертає authorization URL.
2. **Browser consent:** Console відкриває authorization URL; користувач входить на GitHub і підтверджує permissions.
3. **Callback:** Nexus приймає контрольований callback та передає typed result Agent. Перевіряються state, browser binding, очікуваний issuer/resource/redirect та актуальність connection. Повторний або прострочений callback відхиляється.
4. **Exchange/check:** стандартний OAuth client обмінює code, Agent атомарно зберігає encrypted credentials. Після safe callback result Console окремим чинним Test request запускає MCP probe; token exchange і probe не складаються в один довгий Nexus request. Далі діють чинні disabled-only permissions і explicit Enable. OAuth result та tool readiness показуються окремо.
5. **Refresh/reconnect:** refresh serialization та replacement використовують DB concurrency mechanism. Delete/reconnect не перезаписуються запізнілими результатами. Відсутні refresh/expiry дані не вигадуються; invalid grant потребує Reconnect, insufficient scope не спричиняє нескінченний refresh.

Callback і кінцевий Settings redirect походять із контрольованої deployment configuration, а не довільного Host/returnUrl. Browser binding потрібен для OAuth transaction і не повертає окремий operator login у звичайний локальний Forge. Cross-site callback/SameSite поведінка перевіряється реальним браузером. Code, verifier, state binding, tokens і raw transport payload не потрапляють у логи або public errors.

Client ID/secret, issuer, authorization/token endpoints, resource та scopes — одноразовий setup у Advanced, а не основна форма входу. Немає зареєстрованого клієнта — показати конкретну setup передумову; не вдавати успішне автоматичне discovery. Close/Cancel не запускають mutations повторно. Reconnect enabled connection спочатку потребує чинного explicit Disable; OAuth не додає автоматичного Disable або Enable. Refresh має зберігати логічну identity авторизації: зміна ciphertext при rotation не повинна сама відкликати чинні runtime grants або зіпсувати inventory comparison. Новий Connect/Reconnect змінює identity та очищає старі approvals.

## Перевірки перед завершенням

Disposable fake authorization server: success, denial, PKCE/state/issuer/resource mismatch, closed window, timeout, repeated callback, changed/deleted connection, token expiry/rotation, concurrent refresh та refresh-versus-delete/reconnect. Existing typed Nexus ForgeIT contracts, secret canaries, persistence restart, bearer/no-auth/header regressions, gateway project/tool/schema/Disable enforcement. Real built Console browser flow перевіряє consent/return і відсутність auto-enable.

Full Agent/Nexus verify, Console typecheck/tests/build, runtime tests, diff check і fresh CI. Окремий live GitHub OAuth acceptance вимагає дійсної registered Forge app; fake AS не є доказом живої сумісності. До фактичного запуску live checks мають статус NOT_VERIFIED.

## Відкриті передумови

1. Чи існує GitHub/OAuth App для Forge, яку можна використовувати для локального callback?
2. Exact callback registration і live provider flow ще NOT_VERIFIED. Не обіцяти універсальну доступність OAuth для кожного Registry entry у Stage 6.
3. Implementation plan: `docs/superpowers/plans/2026-09-29-mcp-stage-6-oauth.md`; до review плану production OAuth не додається.
