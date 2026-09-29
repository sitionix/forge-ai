# MCP Stage 7 — Connect із каталогу без Custom форми

Статус: DRAFT для review. Production implementation ще не змінено.

## Вимога користувача

У рядку каталогу є одна кнопка **Connect**. Натискання починає browser sign-in
у провайдера без проміжного діалогу Name/URL/Authentication/Client ID. Forge
сам визначає OAuth metadata і client configuration. Технічні налаштування
клієнта не стають завданням звичайного користувача.

Окремий **Add custom MCP** та його чинна форма залишаються. Catalog Connect
ніколи не викликає `McpConnectionForm.openCreate(..., server)`.

Це завершення UX із Stage 7 roadmap, поверх чинного Stage 6 OAuth core.
Stage 6 уже перевіряє code exchange/refresh, але його Advanced form не
реалізує потрібний каталоговий Connect.

## Перевірені точки зміни

- `services/forge-console/src/operator/mcp-catalog.js`: сьогодні весь рядок
  є кнопкою Configure, яка передає descriptor у `onSelect`.
- `services/forge-console/src/operator/settings-page.js`: `onSelect` викликає
  `add(server)`, завантажує projects і відкриває Custom form. Саме цей перехід
  вилучається для каталогу; `add()` лишається тільки Custom дією.
- `services/forge-console/src/operator/mcp-oauth-flow.js`: чинний popup,
  safe-result BroadcastChannel, cancellation та authoritative read.
  Його lifecycle перевикористовується без залежності від Custom form.
- `services/forge-console/src/operator/mcp-api.js`: typed browser API.
- Nexus: чинні MCP controller/use case/domain port/client adapter/mapper та
  `ForgeAgentHttpClient`; map → execute → map, scoped error handler.
- Agent: `McpConnectionService`, `McpOAuthService`, encrypted persistence,
  `McpEndpointPolicy` та Spring OAuth client configuration.

## Продуктовий flow

1. Користувач натискає Connect навпроти Registry descriptor.
2. Browser window відкривається в обробнику click, щоб не втратити user gesture.
   Рядок тимчасово показує Connecting; сторінка й навігація не блокуються.
3. Typed Nexus передає endpoint/name Agent. Browser не надсилає issuer,
   client secret чи OAuth endpoints і не обирає arbitrary return URL.
4. Agent перевіряє endpoint та визначає supported authentication. Для OAuth
   отримує protected-resource metadata і authorization-server metadata.
5. Agent знаходить клієнта для exact issuer: installation configuration →
   CIMD, якщо provider і deployment його підтримують → DCR за advertised
   registration endpoint. Не пробує непогоджені URL навмання.
6. Agent створює disabled connection без tools/projects approvals і запускає
   чинний Stage 6 transaction. Nexus повертає safe start DTO та встановлює
   чинний browser-binding cookie. Browser переходить на authorization URL.
7. Після consent чинний callback/exchange зберігає encrypted credentials.
   Settings перечитує authoritative connection, виконує чинний Test і
   оновлює список. Custom form не відкривається ні до, ні після входу.
8. Permissions/Enable залишаються окремими чинними діями в details. Успішний
   OAuth не означає згоду на всі tools, всі projects або автоматичне Enable.

«Без проміжних станів» означає відсутність зайвих користувацьких форм.
Внутрішній disabled/transaction lifecycle лишається для fail-closed доступу;
UI не відображає його як технічний setup wizard.

## Ownership і межі

Connect — application orchestration на Agent, не browser orchestration
із ручних create/update/client-settings calls. Вузька typed Connect operation
проксіюється чинним Nexus pattern. Її input — descriptor display name та
endpoint; output — safe OAuth start або confirmed no-auth connection.
Конкретні DTO/endpoint contracts деталізуються implementation plan.

Metadata/registration HTTP використовує Spring HTTP і Jackson та чинну
network/TLS policy, bounded body/read/connect limits, redirect NEVER і
NO_PROXY. Немає нового HTTP framework, OAuth engine, IAM чи мікросервісу.
Registry GET і його 5-minute Caffeine cache не змінюються й не виконують
discovery для кожного елемента сторінки: discovery лише після Connect.

Client identity bound to issuer; отримані DCR credentials зберігаються
захищено й повторно використовуються для відповідного connection. Existing
saved connections не змінюються каталоговим читанням. Подвійний click не
створює паралельні frontend attempts; невизначений create outcome не
повторюється автоматично. Cross-tab створення окремих connections не
трактується як помилка: різні provider accounts дозволені.

## Реальні обмеження провайдерів

GitHub Remote MCP офіційно не підтримує DCR. Для нього необхідна
зареєстрована **Forge-owned GitHub App/OAuth App**. Це одноразова передумова
постачальника/інсталяції Forge, не Client ID форма при кожному Connect.

Перевірений референс:
https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md

Не підставляти чужу application identity та не вшивати confidential client
secret у frontend, репозиторій чи distributable image. Поточний remote-only
flow не замінюється офіційним stdio GitHub server чи прихованим bridge.

**BLOCKER для live GitHub acceptance:** власний зареєстрований Forge App,
його дозволений callback і спосіб protected provisioning ще не надані.
Discovery не усуває цю вимогу GitHub. Записати точну передумову, не робити
фіктивний redirect і не повідомляти Connected без token exchange/probe.

CIMD потребує контрольованої HTTPS metadata URL, доступної провайдеру.
Local `127.0.0.1` не є таким deployment. Не створювати центральний Forge
OAuth service заради цього flow. За відсутності реєстрації — коротке
повідомлення в рядку про недоступне підключення на стороні Forge, без
відкриття технічної форми для користувача.

No-auth MCP може пройти Connect без provider redirect. Для PAT-only та
URL-template entries неможливо чесно обіцяти автоматичний OAuth: рядок
пояснює конкретну вимогу й посилається на вже існуючий Custom flow, не
створює ще одну форму й не намагається resolve template variables сам.

## Cancel/error UX

Callback denial, blocked popup, expired transaction, wrong issuer і network
failure показуються біля відповідного catalog row/connection. Popup-blocked
має лише Open sign-in action для того самого attempt. Cancel не replay-ить
mutations і не автоматично Enable/Disable існуюче connection. Reconnect
існуючого enabled connection зберігає вимогу explicit Disable.

Safe result не переносить tokens/code/raw payload у Settings URL, storage
чи application/framework logs. Browser не має provider client secret.

## Acceptance і перевірки

- Console unit: catalog click не відкриває `mcpConnectionDialog`, не викликає
  `openCreate`, не завантажує projects перед redirect; одна Connect дія.
- Existing Custom form, disabled-only permission editing, navigation,
  pagination/cache/icon behavior залишаються green.
- Disposable HTTP/AS tests: discovery challenge/metadata, issuer binding,
  registered-client priority, supported DCR, supported CIMD deployment,
  unavailable registration, redirects, private hosts, oversize/malformed JSON,
  timeout. Zero token/registration calls після policy denial.
- Typed Nexus ForgeIT: Connect request/error contracts, cookie/origin checks,
  upstream preservation і secret canaries; executor не має OAuth parsing.
- Real built Console + joined Agent/Nexus fake AS: row Connect → provider
  consent → callback → authoritative GET/Test → list без Custom dialog.
  Cancel/denial/double-click не створюють auto-enable або ширших approvals.
- Existing Stage 6 refresh/reconnect/rotation/credential redaction та
  Stage 3/4 grant enforcement не регресують.
- Full Agent/Nexus verify, Console typecheck/tests/build, runtime tests,
  dependency analysis для змінених модулів, diff check і fresh CI.
- Live GitHub: NOT_VERIFIED до справжнього Forge App setup і фактичного
  browser consent/token exchange/MCP probe. Fake AS не доводить GitHub.

## Межі поставки

Немає нового Settings framework, provider executor, OAuth cloud service,
нової глобальної MCP feature flag, автоматичних tool/project grants,
automatic Enable, зміни Codex runtime або Remote Access. PR metadata,
comments/reviews та merge state не змінюються під час реалізації.
