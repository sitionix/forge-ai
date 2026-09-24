# Forge AI — MCP Integrations: roadmap та план реалізації

**Дата:** 23 вересня 2026 року.\
**Призначення:** самодостатній документ для поетапної реалізації в Codex.\
**Goal:** один раз підключити зовнішній MCP у Forge та автоматично надати його інструменти дозволеним агентським виконанням, не передаючи їм зовнішні довгоживучі credentials.\
**Architecture:** глобальні Settings → Integrations; керування через Console → Nexus → Agent; Agent володіє підключеннями, OAuth і контрольованим MCP gateway. Codex залишається наявним виконавцем та отримує нативні MCP tools через цей gateway.\
**Tech stack:** наявні Java/Spring, PostgreSQL, JavaScript Console та Codex app-server; сумісний офіційний MCP SDK і OAuth-бібліотеки після перевірки Stage 0. Новий frontend framework, окремий мікросервіс і власний MCP SDK не потрібні.\
**Spec:** продуктові та архітектурні вимоги містяться в розділах 1–6 цього документа; Stage 0 уточнює лише реалізаційні точки та сумісність, не перепроєктовує погоджений продукт.

> Для agentic workers: використовувати доступний `superpowers:executing-plans` або `superpowers:subagent-driven-development` для **поточного погодженого стейджу**, а не для автоматичної реалізації всієї roadmap. Код починається після локального перевіреного плану відповідного стейджу. Чекбокси нижче — tracking роботи, а не підтвердження її виконання.

---

## 1. Що будуємо

Користувач відкриває нижню кнопку **Settings** у sidebar, заходить в **Integrations**, підключає готовий сервіс із каталогу або додає **Custom MCP**. Forge зберігає підключення та захищені credentials, отримує доступні tools і дає користувачу визначити доступ для проєктів.

При старті агента Forge сам визначає дозволені підключення і конфігурує MCP у runtime. Агент бачить справжні інструменти зі схемами викликів. Йому не треба шукати токени, читати `.env`, встановлювати сторонні CLI або здогадуватися про адресу сервера.

**Підключення глобальне для відповідного власника/інсталяції Forge. Дозвіл використовувати його — окремий.** Проєкт не володіє копією OAuth-токена. Різні підключення одного сервісу підтримуються: наприклад, GitHub Work і GitHub Personal.

### Очікуваний кінцевий сценарій

```text
Settings → Integrations → Add integration / Add custom MCP
→ вибір endpoint і авторизація
→ перевірка MCP та отримання tools
→ вибір доступних проєктів і дозволених tools
→ збереження
→ запуск звичайного workflow
→ агент отримує MCP tools
→ реальний виклик через Forge gateway
→ результат та safe activity у поточному execution UI
```

**Критерій продуктового успіху:** сценарій працює після перезапуску Forge і з чистого агентського середовища без ручного налаштування MCP у персональному Codex home.

---

## 2. Підстава документа та межі перевірки

### 2.1. Що перевірено в коді перед складанням roadmap

Переглянуто актуальні файли репозиторію `sitionix/forge-ai` на дату документа:

| Референс | Підтверджений факт | Значення для реалізації |
|---|---|---|
| `services/forge-agent/pom.xml` | Java 21, Spring Boot 3.3.4; модулі domain/application/api-rest/infrastructure/boot; PostgreSQL та Codex dependencies | Підбирати сумісні бібліотеки; не оновлювати всю платформу заради MCP. |
| `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexAppServerClient.java` | `threadStartParams()` передає конфігурацію; `codexConfig()` керує web search, shell і agents | Тут є точка передачі execution-specific MCP configuration. |
| `.../CodexSessionProtocol.java` в тому самому package | `resumeThread()` формує окремий request; у переглянутій реалізації не передає нову MCP-конфігурацію | Підтримка лише `thread/start` не закриває feature. |
| `.../CodexAgentExecutionEventMapper.java` | MCP calls вже мапляться у `TOOL_CALL`; є sanitizer і bounded payload | Розширювати наявну activity, а не заводити другий event ledger. |
| `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/NexusAgentProxyIT.java` та чинні Nexus plans | Agent API має typed Nexus proxy та ForgeIT тести | UI management flow повинен пройти через Nexus. |

Під час попереднього огляду також знайдено `services/forge-console/src/operator/operator-bootstrap.js` з централізованим `initSidebar()` і `DefaultCodexAppServerProcessStarter.java` з запуском app-server. Stage 0 повторно звіряє ці точки з локальним кодом.

**Не перевірено тут:** встановлений у користувача Codex binary, реальні мережеві межі sandbox, фактична OAuth-сумісність його серверів, живий connection flow або результати тестів. Тому roadmap починається з короткої перевірки сумісності, а не з припущення, що всі API вже працюють.

Аудити Forge/Jarvis/Knowledge від 15 червня 2026 року — історичний контекст, не актуальна карта модулів. Не переносити старі шляхи з них у новий код.

### 2.2. Джерела вимог

Погоджений UX і глобальне володіння підключеннями — продуктова основа з обговорення. Деталі етапів, gateway grants, тестів і rollout нижче — запропонований план реалізації.

Правила якості брати з актуальних репозиторних інструкцій та наданих документів:

- `Forge-AI-Task-and-PR-Review-Rules(1).md` — відповідальність, найпростіша достатня реалізація, typed Nexus, ForgeIT, відсутність самовільних PR-операцій.
- `Codex-Task-Lifecycle-&-PR-Review-Strategy.txt` — окремий audit після implementation; acceptance коду не дорівнює дозволу на merge.

Зовнішні протокольні факти позначено `[W0]`–`[W7]`; адреси джерел наведені наприкінці. Вони не замінюють перевірку встановлених версій.

---

## 3. Незмінні архітектурні рішення

### 3.1. Три наявні сервіси, без нового мікросервісу

| Частина | Відповідальність |
|---|---|
| **forge-console** | Settings, Connected/Catalog, форми, redirect/reconnect UX, відображення доступу й діагностики. |
| **forge-nexus** | Typed management BFF: прийняти запит, замапити, делегувати в Agent, повернути відповідь. Без OAuth state machine, credentials storage та MCP routing. |
| **forge-agent** | Підключення, права використання, credentials, OAuth client, discovery/probe, каталог, MCP gateway та конфігурація execution. |
| **Codex infrastructure** | Адаптація дозволених підключень до реально підтриманого app-server API; start/resume/recovery; наявні execution events. |

```text
Керування:
Browser → Console → Nexus typed API → Agent application → storage / OAuth / MCP client

Виконання:
Codex MCP client → Agent internal MCP gateway → external MCP server
```

Management API та runtime gateway — різні межі. Runtime credential не дозволяє створювати підключення, змінювати проєктні права або читати credentials.

### 3.2. Готовий сервер і Custom MCP використовують один connection flow

Картка каталогу лише підставляє descriptor: назву, endpoint або поле instance URL, транспорт, опис і підтримані auth options. Вона не створює окремий runtime, окрему таблицю підключень чи `GitHubExecutor`.

Каталог не є потрібним для виконання вже створеного підключення. Видалення або недоступність запису каталогу не повинні знищувати користувацьку конфігурацію.

### 3.3. Перший scope — remote Streamable HTTP та tools

У межах цієї roadmap підтримати віддалені HTTP MCP, а не інсталяцію локальних пакетів. Немає запуску `npx`, `uvx`, Python scripts або Docker images із каталогу.

Спочатку потрібні discovery та виконання **tools**. Resources/prompts як окремі MCP capabilities, sampling, elicitation і інтерактивні MCP Apps не входять у першу поставку. Результати tools обробляються лише в межах явно підтриманої версії SDK/provider; непідтриманий формат не видається за успішно оброблений.

SSE як формат відповіді Streamable HTTP не плутати зі старим окремим HTTP+SSE transport. Не заявляти legacy transport support, якщо його немає у перевіреному adapter.

### 3.4. Один gateway module, окреме представлення кожного connection

Рекомендована проста модель: gateway має connection-scoped endpoint; Codex отримує окремий MCP server entry для кожного дозволеного connection.

Це дозволяє залишити upstream tool names і schemas без саморобного агрегатора. Два сервери з tool `search` розрізняються стабільними Forge server aliases. Alias походить від immutable connection identity, а не лише від display name або неперевіреного upstream `serverInfo.name`.

Конкретний URL route закріплюється у Stage 0 відповідно до поточного routing. Цей route приймає лише MCP-протокол, не довільну upstream URL і не generic HTTP forwarding.

### 3.5. Не будувати паралельний агентний framework

Не додавати MCP workflow node, fake agent, SystemNode framework, новий scheduler, новий context engine або другого власника provider sessions. Це інструменти звичайного агента в чинному workflow.

Не підключати MCP автоматично до Knowledge analysis, Jarvis та інших виконавців із власними обмеженнями. Scope першої реалізації — перевірений engineering execution path у `forge-agent`.

---

## 4. Контракти доступу, lifecycle і даних

### 4.1. Connection та каталог — різні поняття

**Connection** містить необхідний мінімум: identity, власника в поточній моделі Forge, display name, endpoint, transport, auth configuration, enabled, правила доступу проєктам, явний набір дозволених tools і останню перевірку.

**Catalog entry** містить metadata та setup descriptor без користувацьких credentials. Посилання connection на джерело каталогу необов'язкове; connection зберігає власну підтверджену конфігурацію.

Не створювати довільну таблицю загальних settings, якщо її єдина функція — сховати connection model у JSON. Не будувати універсальний Vault, IAM або marketplace framework.

Не вводити модель organization/tenant лише для цієї задачі. Перевикористати чинного власника. Для single-user install глобальність означає цю інсталяцію, а не передачу особистих акаунтів будь-якому майбутньому користувачу.

### 4.2. Доступ для проєктів

У UI: **All projects** або **Selected projects**. Користувач обирає це при завершенні connection flow. До явного підтвердження connection не доступне агентам.

Порожній Selected projects означає відсутність доступу, а не All projects. Кожен project identifier перевіряється відносно чинного власника.

Runtime project/execution identity визначає сервер із чинного виконання. Значення із prompt, tool arguments або довільного request header не є доказом права доступу.

`GLOBAL`/`PER_SCOPE` у workflow визначають робочий контекст, а не права на зовнішні сервіси. Обидва режими дотримуються однакової connection policy.

**Project access — це дозвіл використовувати credential, а не універсальний фільтр ресурсів усередині зовнішнього сервісу.** Обмеження конкретних repositories/організацій забезпечують права провайдера. Не обіцяти repo isolation лише через вибір проєкту у Forge.

### 4.3. Дозволені tools

При підключенні показати отримані інструменти та явно зберегти дозволений набір. Можна дати дію «вибрати всі поточні», але не прихований wildcard на всі майбутні tools.

Після отримання нового inventory новий tool або виявлена зміна його schema не розширюють доступ непомітно. Змінений інструмент потребує повторного підтвердження перед новими викликами; незмінені інструменти продовжують працювати. Inventory оновлюється при Test connection, перед новим invocation та за підтриманим SDK сигналом зміни списку. Не обіцяти миттєве виявлення прихованої зміни upstream між перевірками.

Не робити security-класифікацію за назвою `get_*` або прапорцем `readOnlyHint`: annotations від сервера не є самі по собі гарантією безпеки [W4]. Якщо дозволені write tools, UI прямо повідомляє, що агент може виконувати їх без додаткового діалогу. Per-call approval engine не входить у scope; непогоджені tools недоступні.

### 4.4. Credentials

Forge є єдиним власником зовнішніх credentials. Зберігати їх зашифрованими, з автентифікованим шифруванням від стандартної бібліотеки; ключ — поза БД. Для ротації зберегти identifier encryption key, не будувати окремий versioning framework.

Credentials write-only для management API. GET/list повертають лише наявність налаштування та безпечні metadata. Маска `••••` ніколи не є новим значенням секрету. Update явно відрізняє «залишити», «замінити» і «видалити».

Не передавати зовнішні access/refresh tokens або client secrets у prompt, Codex config, arguments, environment, rollout, activity payload чи логи. Приватний master key і DB credentials також не успадковуються дочірнім runtime.

Для OAuth окремо захищаються client credentials, access token, refresh token та незавершений authorization transaction. Немає обов'язкового refresh token: провайдер може його не видати [W1].

### 4.5. Runtime grants

На кожний connection у конкретному активному invocation видається короткоживучий, непрогнозований credential для **Forge gateway**, не зовнішній токен. Він пов'язаний з execution identity, connection identity, дозволеним набором tools, endpoint configuration та строком дії.

Термін дії обмежений чинним execution deadline. Новий invocation/resume отримує новий grant; завершення, cancel і error відкликають старий. Не використовувати довільний короткий TTL, що обриває штатний довгий turn, без механізму його підтримки.

Можна зберігати hashed grants у пам'яті Agent: після restart старі недійсні, recovery випускає нові. Це runtime mechanism, не новий довгоживучий domain aggregate чи обов'язкова таблиця.

Кожен gateway call перевіряє поточний grant і поточну connection policy **до** upstream I/O. MCP session identifier, коли він є в обраній ревізії протоколу, не замінює авторизацію.

**Важлива межа гарантії:** runtime credential потенційно доступний агентському процесу, який ним користується. Тому він обмежений invocation/connection/tools. Секретність зовнішніх credentials потребує реальної ізоляції сховища, ключів, environment і management API; одне шифрування БД або `workspace-write` цього не доводить.

### 4.6. Зміни під час виконання

| Подія | Контракт |
|---|---|
| Додали connection або розширили права | З'являється в наступному invocation; без автоматичного розширення прав поточного turn. |
| Disable, Remove, звуження project/tool access | Наступний не відправлений upstream call відхиляється. Відкликані grants не оживають після Enable. |
| Змінили endpoint або auth identity | Відкликати grants, скинути перевірку; credentials не переносити на іншу адресу/identity автоматично. |
| Штатний OAuth refresh того самого authorization context | Нові credentials використовує gateway; агенту вони не передаються. |
| Upstream виклик уже відправлений до Disable | Best-effort cancellation; не обіцяти скасування виконаного зовнішнього запису. |
| Timeout після можливої write-операції | Не replay-ити автоматично. Результат може бути невідомим. |
| Один зовнішній сервер недоступний | Це не робить інші підключення недоступними і не створює нового глобального workflow gate. |
| Recovery | Поточна перевірка прав, нові grants; жодного автоматичного повтору невідомого write-call. |

Список конфігурації, виданий invocation, фіксується без секретів у чинному execution snapshot. Snapshot потрібний для пояснення виконання, але не є дозволом обійти поточне відкликання.

### 4.7. Статус підключення — read model, не новий workflow lifecycle

Окремо зберігати configured/enabled та результат останньої перевірки. UI виводить «Потрібна перевірка», «Підключено», «Потрібен вхід», «Недоступно», «Вимкнено» з цих фактів і показує час перевірки.

OAuth callback success означає отримання credentials, а не працездатність tools. Після нього потрібна реальна MCP-перевірка. Нуль доступних tools — окреме зрозуміле повідомлення, не фальшива готовність агента.

Немає нового великого persisted enum зі статусами, що дублюють enabled, expiry, OAuth transaction та transport error.

---

## 5. UX-контракт

### 5.1. Навігація

```text
Sidebar                         Settings → Integrations
Projects                        [ Connected ] [ Catalog ]
Jarvis                          MCP-підключення для агентів Forge
Knowledge
                                GitHub · Work       Підключено       ⋯
                                GitLab · Ancestor   Потрібен вхід    ⋯
                                Internal Docs       Вимкнено        ⋯
──────────
⚙ Settings                      [ Add integration ] [ Add custom MCP ]
```

Settings один, глобальний, не залежить від обраного проєкту. Перед додаванням звірити паралельні зміни sidebar/settings, зокрема Remote Access; не створювати дві кнопки налаштувань.

Connected — налаштовані підключення; Catalog — доступні для встановлення. Коли функції Catalog ще немає, не показувати порожню fake-вкладку з непрацюючими кнопками.

### 5.2. Custom flow

**Ввести:** Name, Server URL; Authentication = Detect automatically з можливістю ручного вибору.

**Перевірити:** backend probe визначає доступний сценарій. Auto-detect — UI orchestration, а не окремий auth type у credentials storage. No auth, bearer token, контрольовані secret headers та OAuth використовують спільний flow.

**Підтвердити:** показати реальний host, список tools, проєктний доступ і дозволи. Для внутрішньої адреси потрібна явна network policy. Advanced розкриває нестандартні headers і OAuth client settings.

**Завершити:** повідомити, які tools доступні, та повернути в Connected. Не зберігати secrets у localStorage/sessionStorage; очистити форму після відправлення/скасування.

### 5.3. Connection details

Показувати display name, endpoint host, account label лише коли він достовірно відомий, last checked time, access для проєктів і tools. Universal `whoami` у MCP не припускати.

Дії: Test connection, Edit, Reconnect, Enable/Disable, Remove. Remove підтверджується. Disable зберігає конфігурацію, але припиняє доступ. Remove видаляє секрети та grants; зовнішній revoke виконується лише коли підтримується, і його невдача не відновлює локальний доступ.

Loading/error/empty states, клавіатурна навігація, focus у modal/drawer, доступні labels та захист від double-submit обов'язкові. Polling лише для видимого активного flow, з cleanup після navigation.

---

## 6. Правила реалізації та перевірки

Один roadmap item зазвичай дорівнює одному самостійному PR-sized результату. Якщо стейдж треба розбити, зробити це **до** коду, за незалежними acceptance boundaries, без зміни загального scope.

```text
Поточний код + найближчий прийнятий референс
→ локальний план одного стейджу
→ failing tests
→ мінімальна production implementation
→ focused unit / IT / UI verification
→ audit-only
→ точкові fixes
→ review результату
→ наступний стейдж після погодження
```

Не створювати PR, не змінювати його metadata, не публікувати comments/reviews і не merge-ити без прямого запиту. Не вимагати від звіту зайвих git-операцій чи repo status даних.

Nexus зберігає typed DTO/mapper/use case/client boundaries та чинний ForgeIT стиль. Для management IT використовувати наявні endpoint contracts і fixtures, не raw MockMvc/WireMock helpers.

**Важливе розмежування:** довільні tool schemas та arguments — частина MCP-протоколу. Їх обробляє MCP SDK у відповідному infrastructure boundary, без hardcode DTO на кожний зовнішній tool. Це не дозвіл зробити management API Nexus generic JSON proxy. Console отримує typed tool summaries; повні protocol payloads не розносяться по всіх шарах.

Не переписувати протокол вручну і не підключати Spring AI/новий orchestration framework лише для доступу до MCP. Невеликі adapters допустимі там, де їх вимагає реальна межа відповідальності.

Мережеві та auth перевірки нижче — необхідні через Custom URL, credentials і agent execution; вони не привід розширювати задачу на загальну платформу безпеки.

### Review focus для всієї roadmap

| Ризик | Де доводиться |
|---|---|
| Працює лише fresh thread, але не resume/recovery | Stages 0, 4, 10 |
| Секрети або зайві домашні MCP потрапляють у runtime | Stages 0, 1, 4, 10 |
| Disable видно в UI, але старий агент продовжує calls | Stages 3, 4, 5, 10 |
| Два одночасні OAuth refresh знищують чинний refresh token | Stage 6 |
| Каталог змінює endpoint, scopes або tools без згоди | Stages 2, 3, 8, 9 |

---

## 7. Загальний порядок

| Stage | Результат | Залежить від |
|---|---|---|
| **0** | Перевірені точки інтеграції, runtime/protocol/security compatibility та план першого implementation slice | Погоджена roadmap |
| **1** | Connection model, encrypted persistence, project/tool policy, захищений management boundary | 0 |
| **2** | HTTP MCP client: probe, discovery, tool inventory і контрольовані виклики | 1 |
| **3** | Execution-scoped MCP gateway із grants та живим відкликанням | 2 |
| **4** | Реальна інтеграція Codex: fresh/resume/recovery, tools і activity | 3 |
| **5** | Settings → Connected та Custom MCP end-to-end через UI | 4 |
| **6** | Forge-owned OAuth lifecycle із pre-registered client | 2–5 |
| **7** | OAuth discovery/registration та завершений Connect/Reconnect UX | 6 |
| **8** | Recommended catalog, підключення з карток через той самий flow | 7 |
| **9** | Динамічний офіційний Registry, кеш і All servers | 8 |
| **10** | Cold-start acceptance, ізоляція, failure/recovery, runbook і release evidence | 9 |

**Milestone A — Stage 5:** користувач уже підключає Custom HTTP MCP із token/no-auth і агент ним працює.\
**Milestone B — Stage 7:** OAuth працює повністю там, де сумісний сервер і deployment.\
**Milestone C — Stage 10:** каталоги та весь погоджений продукт пройшли перевірку.

Не відкладати всі тести до Stage 10. Він перевіряє весь зібраний продукт, а не вперше додає access control чи захист credentials.

---

## Stage 0 — Runtime compatibility та точна карта змін

**Мета:** прибрати конкретні невідомі перед production implementation. Це audit/probe, не нова реалізація MCP.

**Зона:** чинний Console/Nexus/Agent code, Codex binary та безпечні ізольовані protocol fixtures.

### Що зробити

- [ ] Прочитати чинні правила, перевірити готові settings/navigation, auth, PostgreSQL migrations, secret handling, Agent execution, Nexus client та ForgeIT/E2E patterns.
- [ ] Зафіксувати конкретну карту файлів: що розширюється, найближчі прийняті controller/use case/mapper/repository/tests. Не вигадувати obsolete classes: перелічити їх лише якщо вони реально є.
- [ ] Перевірити фактично встановлений Codex та отримати його protocol schema підтриманою ним командою. Не вважати актуальну online документацію доказом наявності API в цьому binary [W5].
- [ ] На disposable environment із синтетичними credentials довести MCP configuration для fresh thread **і** resume, спосіб інжекції gateway credential, фактичний перелік callable servers та відсутність успадкованих несанкціонованих MCP.
- [ ] Перевірити конфігураційні шари home/project/plugin. Визначити, як дати execution потрібні MCP, не зламавши durable provider history, provider authentication і чинні workspace roots.
- [ ] Визначити мережевий шлях runtime → gateway і gateway → зовнішній сервер. Не відкривати shell необмежений Internet заради MCP.
- [ ] Перевірити synthetic canaries: агентський shell не читає key file, backend DB credentials, management credential і parent-process secrets. Перевірити не лише environment, а й доступні файли та `/proc` відповідно до реального deployment.
- [ ] Вибрати офіційний Java MCP SDK, сумісний з поточною Java/Spring без загального upgrade, та OAuth client library. Зафіксувати підтримані протокольні ревізії окремо на обох плечах gateway.
- [ ] Перевірити достатність SDK test transport для protocol tests. Якщо ForgeIT/E2E уже має потрібні contracts — перевикористати, не створювати новий runner.

### Результат

`docs/mcp-integrations/stage-0-evidence.md`: карта файлів, бібліотеки та protocol matrix, runnable probe commands, результати, security boundary, конкретні blockers і план Stage 1.

Online `latest` MCP на дату перевірки веде на ревізію 2026-07-28 [W1, W4]. Це **не вимога** негайно оновити runtime до неї. Не змішувати повідомлення різних ревізій; зокрема не припускати однаковий session/initialization lifecycle.

### Acceptance

Є доказ, що вибраний шлях дозволяє реальні MCP calls у потрібному provider без зовнішніх токенів у ньому. Є конкретний спосіб старту/resume та захисту management/secrets.

Якщо потрібен provider upgrade або додаткове розмежування runtime, описати одну мінімальну prerequisite-задачу і не маскувати її як готову сумісність. Не переписувати всю execution infrastructure.

**Поза scope:** production tables/endpoints/UI, реальні OAuth grants, повна реалізація майбутніх стейджів.

---

## Stage 1 — Connection domain, persistence та management boundary

**Мета:** Forge надійно зберігає підключення й правила доступу незалежно від проєкту.

**Зона:** `forge-agent/domain`, `application`, `api-rest`, наявний PostgreSQL adapter/migrations; Nexus typed management slice.

**Вхід:** file map та security рішення Stage 0.\
**Вихід:** створення, читання metadata, редагування, enable/disable/remove; читання дозволів для invocation без доступу до секретів.

### Реалізація

- [ ] Додати мінімальну connection model за розділом 4. На цьому етапі — no-auth, bearer і контрольовані secret headers. OAuth-specific persistence додається у Stage 6, без порожнього фреймворку.
- [ ] Написати forward-only DB migration у чинному стилі; використовувати чинну DB та transaction boundary.
- [ ] Реалізувати authenticated encryption storage та write-only credential update semantics. Ciphertext прив’язати до connection/owner через authenticated metadata, щоб перестановка encrypted blobs не змінювала чужі credentials. Missing/wrong key дає явну помилку, не plaintext fallback. Мінімальна ротація: configured active key для нових записів, явно задані попередні keys для читання і контрольоване перешифрування тестових/операторських записів; без окремого key-management сервісу.
- [ ] Додати project policy All/Selected та явний tool allowlist. До discovery allowlist порожній, connection не готове для runtime.
- [ ] Реалізувати typed management endpoints і Nexus forwarding. Exact routes — за контрактом Stage 0; не додавати паралельного direct Console → Agent API.
- [ ] Усі операції захистити чинною operator auth. За потреби реалізувати мінімальну prerequisite з Stage 0 до відкриття API. Loopback bind сам по собі не захист від іншого локального процесу чи browser-origin атаки.
- [ ] Secret-bearing request bodies та callback/query diagnostics виключити з logging. GET metadata не містить credentials або encrypted blobs.
- [ ] Remove очищає локальні секрети; історичні execution records не знищуються. Не зберігати секрети заради історії.

### Тести

- [ ] CRUD і restart persistence; metadata round-trip; create/update rollback без orphan credentials.
- [ ] Відсутній ключ, неправильний ключ, пошкоджений або перенесений між connections ciphertext і непідтриманий key id — контрольована відмова без витоку. Ротація active key та перешифрування не втрачають credentials.
- [ ] Однакова service name у двох connections не змішує identity/credentials.
- [ ] All/Selected/empty Selected, foreign project, видалений проєкт та неавторизований management caller.
- [ ] Replace/keep/remove secret; маска з UI не стає секретом.
- [ ] Unit tests окремо від DB IT; Nexus ForgeIT перевіряє forwarding та zero upstream calls при local rejection.

### Acceptance

Connection переживає restart, секрети недоступні через read API/логи, policy має однозначний зміст. Неправильний caller не може ані змінити configuration, ані видати собі доступ.

**Поза scope:** зовнішні виклики, OAuth, каталог і нова settings-сторінка.

---

## Stage 2 — HTTP MCP client, probe та tool inventory

**Мета:** Forge вміє реально перевірити remote MCP та отримати його інструменти.

**Зона:** окремий MCP infrastructure module/package в Agent, невеликі application ports; extension typed management API для probe та tool summaries.

**Вхід:** connection configuration/credentials із Stage 1.\
**Вихід:** перевірений protocol result, tool inventory та typed call boundary для gateway.

### Реалізація

- [ ] Підключити обраний SDK. Initialization/capability handling, request metadata, sessions або відсутність sessions реалізувати згідно з обраною ревізією, не вручну за прикладом із іншої версії.
- [ ] Реалізувати bounded Test connection: мережа/TLS → auth → protocol compatibility → `tools/list` з усіма сторінками → safe report.
- [ ] Для 401 повернути потребу авторизації; для 403 — недостатні права, не автоматично «токен протух». Не починати OAuth у runtime.
- [ ] Отримати tool names/descriptions/schemas через MCP. SDK types і довільний JSON залишаються всередині protocol boundary; Console отримує typed summaries [W4].
- [ ] Виявляти зміну tool inventory/schema, підтримувати явне підтвердження дозволів. Server metadata/description не інтерпретувати як інструкції Forge.
- [ ] Реалізувати контрольований client call для наступного стейджу. Зберігати semantics `isError`, structured result та protocol failure, а не перетворювати їх усі на success string.
- [ ] Увести bounded connect/read/tool timeouts, response/tool-inventory limits із функціональними properties. Обмеження дає явну діагностику, не мовчазне обрізання schema або результату.
- [ ] Застосувати спільну outbound policy до endpoint, redirects, metadata і schema-reference fetches. Заборонити довільні schemes/userinfo/credential-bearing URL; не пересилати authorization на інший origin через redirect.
- [ ] Приватні мережі дозволяти явним адміністративним правилом для потрібних hosts/ports; перевіряти resolved addresses, redirects і DNS-rebinding сценарій. Metadata/link-local addresses не дозволяти широким правилом «увесь internal network» [W3].
- [ ] TLS перевіряється; self-hosted CA — контрольована конфігурація trust store, не `verify=false`.

### Тести

- [ ] No-auth, bearer, secret headers, unsupported protocol, неправильний endpoint, invalid credentials і 403.
- [ ] Paginated tools, empty tools, duplicate/changed names, invalid schema, bounded oversized payload, timeout.
- [ ] MCP error проти tool `isError`; supported structured results збережені без втрат.
- [ ] SSRF: public → private redirect, IPv6/loopback/link-local, DNS зміна, header injection та credentials redirect leakage.
- [ ] Probe виконує лише protocol discovery; він не викликає довільний write-tool «для перевірки».

### Acceptance

HTTP 200 не прирівнюється до MCP readiness. Чинний сервер дає реальний inventory; невалідний — точну безпечну причину. Усе це працює на детермінованих fixtures без live provider у CI.

**Поза scope:** gateway, provider runtime, OAuth redirect, каталог.

---

## Stage 3 — Execution-scoped MCP gateway

**Мета:** runtime використовує дозволені tools, але не володіє зовнішніми credentials.

**Зона:** Agent internal MCP endpoint, runtime grant issuer/validator та policy enforcement. Без generic security framework.

**Вхід:** execution identity, connection policy і MCP client Stage 2.\
**Вихід:** connection-scoped MCP endpoint і ephemeral credential, придатні для Codex.

### Реалізація

- [ ] Видати окремі grants за розділом 4.5 тільки через внутрішній execution path. Tool call не може передати projectId і отримати інший доступ.
- [ ] Реалізувати SDK-based MCP server boundary та підтримані `tools/list`/`tools/call`. Upstream auth і runtime auth не змішуються [W3].
- [ ] До кожного list/call перевіряти execution, expiry, connection enabled, власника, project access, endpoint binding та allowlist. Відмова означає **zero external calls**.
- [ ] Повернути лише дозволені інструменти. Вгаданий заборонений tool name не обходить фільтр `tools/list`.
- [ ] Реалізувати sticky revoke: disable/remove/permission reduction/endpoint edit відкликають відповідні grants. Re-enable не оживляє старий credential.
- [ ] Не змішувати upstream connection state між різними account/credential contexts; не додавати глобальний pool, який ламає ізоляцію.
- [ ] Обробляти cancellation та resource cleanup. Не заявляти непідтримані MCP capabilities. Непідтримані sampling/elicitation calls отримують protocol-compatible відмову, а не зависання.
- [ ] Не повторювати write-capable tool calls після transport timeout/connection reset. Protocol request id не є гарантією exactly-once.
- [ ] Додати мінімальну safe telemetry: connection, execution, tool, duration, outcome; без body/secrets і без другого event framework.

### Тести

- [ ] Grants чужого invocation/project/connection, прострочені/відкликані/невідомі credentials та неавторизований `tools/list`.
- [ ] Заборонений tool, видалений tool, змінена schema; zero upstream I/O на rejection.
- [ ] Disable під час роботи, повторний Enable, видалення connection; наступний call заборонений.
- [ ] Два connections із однаковим tool name, два одночасні invocations і правильна маршрутизація credentials.
- [ ] Cancel, timeout, upstream failure, Agent restart; старі grants не працюють.
- [ ] Write endpoint fixture рахує виклики: ambiguous timeout не спричиняє другий запит.

### Acceptance

Стандартний MCP client виконує дозволений tool через gateway. Ні browser management credentials, ні зовнішній token не приймаються як runtime grant. Відкликання доведене фактичними upstream call counters.

**Поза scope:** UI, OAuth, каталог, нова workflow lifecycle.

---

## Stage 4 — Codex runtime: fresh, resume, recovery та activity

**Мета:** справжній Forge agent отримує інструменти без ручного config або пошуку доступів.

**Зона:** Codex infrastructure і чинний execution/application path.

**Основні референси:** `CodexAppServerClient`, `CodexSessionProtocol`, `DefaultCodexAppServerProcessStarter`, `CodexTurnRequest`, чинні execution workspace/snapshot/recovery компоненти, `CodexAgentExecutionEventMapper` та sanitizer.

### Реалізація

- [ ] Перед invocation визначити доступні connections/tools та видати grants. У snapshot записати лише безпечні identifiers/metadata і фактично виданий набір.
- [ ] Згенерувати нативні MCP server entries зі стабільними aliases, gateway endpoints і grant references. Застосувати перевірений у Stage 0 config path, не особистий `~/.codex/config.toml`.
- [ ] Підтримати fresh, durable resume і recovery. Не змінити provider conversation identity, context iteration та workspace semantics заради MCP.
- [ ] Перевірити effective MCP inventory після config merge. Налаштування home/project/plugins не додають сторонніх MCP в обхід Forge policy.
- [ ] Захистити environment та secret paths згідно зі Stage 0. Одне `builder.environment().clear()` недостатнє як доказ ізоляції; перевірити доступні sibling/parent process і management endpoints.
- [ ] Не перезапускати довільно історію conversation і не робити ephemeral provider home на кожен turn, якщо це ламає resume. Використати перевірений спосіб configuration isolation із збереженням durable store.
- [ ] Додати коротку довідку призначення connections; schema береться через MCP. Не дублювати повний tool catalog у developer prompt.
- [ ] Недоступний connection має видиму startup/runtime diagnostics; агент не отримує неіснуючі tools і не шукає fallback credentials. Інші tools і workflow без MCP не ламаються.
- [ ] Cleanup grants на success/error/cancel; відновлення з новими grants. Усі запити після revoke перевіряє gateway, навіть якщо provider досі показує старий tool у контексті.
- [ ] Перевикористати `TOOL_CALL` activity. Відображати connection/tool/outcome і невелику safe summary; raw result/headers/config не зберігати як diagnostics. Повний результат, коли він потрібний агенту, належить provider context, а не другому application log.

### Тести

- [ ] App-server contract tests проти реальної перевіреної schema; strict mocks не є єдиним доказом API compatibility.
- [ ] Один справжній read-only MCP call від реального Codex до контрольованого fixture; фактичний результат підтверджує execution.
- [ ] Fresh → наступний turn → resume → recovery; нові grants, та сама потрібна conversation identity.
- [ ] `GLOBAL` і `PER_SCOPE`, одночасні проєкти, no-MCP workflow, один broken connection поряд із робочим.
- [ ] Sentinel personal/project MCP не з'являється; user config не змінено; зовнішні secrets і key canaries не доступні shell.
- [ ] Secrets відсутні в activity, process arguments, error paths і provider config snapshots. Scope-limited runtime grant не плутати із зовнішнім secret.

### Acceptance

Підключення, створене management API, доступне реальному agent execution як MCP tool. Доказ містить start і resume, а не лише сформований config. Чинний engineering E2E без MCP проходить без регресії.

**Поза scope:** новий provider, plugin installation, remote shell access і автоматичне відкриття sandbox network.

---

## Stage 5 — Settings та Custom MCP через UI

**Мета:** перший корисний користувацький результат без очікування OAuth/catalog.

**Зона:** `forge-console`, typed Nexus management extension за потреби, browser tests.

### Реалізація

- [ ] Додати/розширити єдиний Settings entry внизу sidebar та Integrations page у поточному UI стилі. Не вводити React/новий router заради цієї сторінки.
- [ ] Connected list із display name, host/account metadata, derived status, last check та доступом до details.
- [ ] Custom flow: URL/name → probe → no-auth/token/headers → tools/project permissions → enable. OAuth challenge до Stage 6–7 показує зрозумілу непідтриману дію, не fake success.
- [ ] Додати Test connection, Edit, Enable/Disable, Remove і tool/project access editing.
- [ ] Розділити «сервер підключений» та «інструменти дозволені агентам». Нуль tools або порожній project access видно явно.
- [ ] Loading/validation/error/empty/retry, focus та keyboard navigation; double-submit/cancel/navigation cleanup.
- [ ] Credentials write-only, жодних secrets у browser storage. Remove/disable результат оновлюється з backend, не лише optimistic badge.

### Тести

- [ ] UI component/browser сценарії empty/list/detail/create/edit/token replacement/errors.
- [ ] Після додавання через UI запускається звичайний агент і виконує дозволений fixture tool.
- [ ] Після Disable з UI наступний runtime call реально відхилений; Forbidden не лише прихований кнопкою.
- [ ] Секрет не потрапив у DOM після завершення форми, storage, URL або backend read response.
- [ ] Sidebar/settings не залежать від project selection; existing pages regression проходить.

### Acceptance — Milestone A

```text
Чистий UI → додати Custom MCP із bearer або no-auth
→ обрати projects/tools → агент викликає tool
→ перезапустити Forge → повторити без ручного MCP config
→ Disable → подальший виклик заборонено
```

**Поза scope:** каталог, OAuth client registration, красиві непрацюючі картки.

---

## Stage 6 — Forge-owned OAuth core

**Мета:** браузерна авторизація, secure storage, refresh та reconnect працюють для pre-registered client.

**Зона:** Agent OAuth infrastructure/application/persistence, typed Nexus endpoints/callback, Console OAuth actions.

### Реалізація

- [ ] Додати OAuth auth configuration до того самого Connection. Client secrets і tokens використовують secret storage Stage 1.
- [ ] Реалізувати Authorization Code + PKCE S256 через стандартну library. OAuth state одноразовий, обмежений у часі й прив'язаний до owner/browser transaction, connection, очікуваного issuer, endpoint і запитаних scopes.
- [ ] Pre-registered client settings задаються адміністративною конфігурацією/Advanced form. Не вшивати спільний confidential client secret у frontend, репозиторій або distributable image.
- [ ] Callback URI будувати з контрольованого deployment configuration, а не довільного Host/returnUrl. Redirect після callback лише на дозволену Forge сторінку.
- [ ] Перевіряти issuer, state, PKCE, redirect та authorization response перед token exchange. Дотримуватись resource binding відповідної MCP auth revision [W1].
- [ ] Browser callback має працювати при реальному cross-site return і чинних SameSite cookies. Не вимикати auth/CSRF для всіх management routes заради OAuth.
- [ ] Token exchange → encrypted persistence → MCP probe → tool permission confirmation. Успішний code exchange ще не означає доступні tools.
- [ ] Реалізувати refresh із per-connection serialization/чинним DB concurrency mechanism і atomic refresh-token replacement. Concurrent reconnect/delete не перезаписується запізнілим refresh.
- [ ] При invalid refresh grant показати Reconnect; при 403 insufficient scope не запускати нескінченний refresh. Нові scopes лише після згоди користувача.
- [ ] Немає refresh token — підтримати expiry/reconnect без заяви про безкінечний offline access. Немає expiry metadata — не вигадувати її.
- [ ] Remove/revoke працює локально одразу; зовнішнє відкликання — за підтримкою провайдера. Не автоматично replay-ити interrupted write після reauth.

### Тести

- [ ] Повний OAuth flow з локальним fake authorization server; verify PKCE/state/resource/redirect/issuer.
- [ ] User denied, закрита вкладка, expired state, repeated callback, wrong browser binding, wrong issuer, connection змінено/видалено під час входу.
- [ ] Missing/invalid/expired refresh token, refresh rotation, два concurrent calls, refresh-versus-delete та refresh-versus-reconnect.
- [ ] Restart після збереження credentials; незавершені transactions або безпечно відновлюються, або явно вимагають почати Connect заново.
- [ ] Callback URL, logs, API responses, UI storage і runtime не містять токенів; synthetic секрети шукаються у негативних error paths.

### Acceptance

Один реальний сумісний OAuth MCP проходить Connect → tools → agent call → refresh/reconnect. Fake AS забезпечує детерміновані негативні тести, але не заміняє окремий live compatibility evidence.

**Поза scope:** автоматична реєстрація всіх клієнтів, public Forge OAuth authorization server, універсальний SSO subsystem.

---

## Stage 7 — Discovery, client registration та завершений OAuth UX

**Мета:** готові й custom servers підключаються без зайвих полів там, де протокол дозволяє автоматизацію.

### Реалізація

- [ ] Discover protected-resource metadata, authorization server metadata та підтриманий OIDC discovery згідно з pinned auth contract. Metadata fetch проходить ту саму network/TLS policy, що і MCP endpoint.
- [ ] Пріоритет: наявний pre-registered client → CIMD за підтримки й придатного deployment → DCR за підтримки → явний ручний client setup. Немає «пробуємо всі URL навмання» [W2].
- [ ] Для CIMD потрібен реальний HTTPS metadata URL, доступний authorization server; локальний `localhost` сам по собі таким URL не є. Не будувати центральний Forge cloud service: metadata URL є контрольованою deployment configuration.
- [ ] Для DCR застосувати відповідний `application_type` та redirect rules; registration credentials прив'язати до issuer. Зміна issuer не переносить старі client credentials автоматично.
- [ ] DCR — compatibility path, а не єдиний спосіб OAuth. У перевіреній актуальній специфікації він deprecated на користь CIMD [W2].
- [ ] Доповнити Custom auto-detect, Connect/Reconnect, cancel/return, admin-setup-required та insufficient-scope UX. Ручні client settings залишаються в Advanced.
- [ ] Показати актуальний host, auth domain і requested access до redirect. Account label не вигадувати, якщо провайдер його не повертає.
- [ ] Не надавати автоматично всі discovered scopes або майбутні write tools; дотримуватись погодженого scope/tool selection.

### Тести

- [ ] Pre-registration priority, CIMD available/unreachable, DCR-only server, unsupported registration, wrong issuer binding.
- [ ] Local native deployment проти remote browser deployment; правильний callback/application type.
- [ ] Злочинні metadata endpoints/redirects, oversize discovery, loop, partial outage та false capability advertisement.
- [ ] Connect/Reconnect через реальний browser callback; closed window, double-submit і повторне відкриття Connected.
- [ ] Два незалежні OAuth connections одного provider не змішують акаунти.

### Acceptance — Milestone B

OAuth підтримується відповідно до реальних можливостей сервера. Неможливість автоматичної реєстрації веде до конкретного client setup, не до загального «MCP не працює».

**Поза scope:** claims про сумісність з усіма провайдерами, credentials-sharing між інсталяціями, нові auth-flow engines.

---

## Stage 8 — Recommended catalog

**Мета:** користувач обирає знайому картку замість ручного endpoint там, де це реально підтримано.

### Реалізація

- [ ] Додати компактний server descriptor format і невеликий curated набір у чинному config/resource стилі. Descriptor містить setup metadata, не execution implementation.
- [ ] Показати Catalog → Recommended. Add integration відкриває каталог; Add custom MCP лишається доступним незалежно від нього.
- [ ] Картка веде в той самий Connect flow із підставленими полями. Instance URL потрібний для self-hosted server, а не зашитий приватний домен.
- [ ] Для кожної рекомендованої інтеграції зафіксувати docs reference, підтриманий transport/auth, deployment prerequisites та результати connection test.
- [ ] Перший цільовий preset — GitHub remote MCP; self-hosted GitLab — наступний після перевірки реальної доступності конкретного інстансу. Якщо server не відповідає контракту, показувати вимогу/несумісність, не встановлювати прихований community bridge.
- [ ] GitHub OAuth потребує зареєстрованого GitHub/OAuth App; офіційний host guide не обіцяє DCR [W6]. За відсутності client setup картка це прямо пояснює; PAT лишається окремим явно обраним auth path.
- [ ] Для GitLab перевірити версію, налаштування та prerequisites за його актуальними docs; не вважати довільний GitLab URL готовим MCP endpoint [W7].
- [ ] Статус Recommended означає «цей flow перевірено з Forge», не аудит безпеки всього провайдера.

### Тести

- [ ] Один connection engine для preset/custom; жодних provider-specific executors.
- [ ] Дубль одного provider, custom display name, instance URL, missing OAuth client settings.
- [ ] Безпечні metadata/icons rendering; невідомий descriptor не запускає command/package.
- [ ] Оновлення descriptor не змінює endpoint/auth у вже створеному connection.

### Acceptance

Користувач натискає картку, проходить правдивий setup/auth і отримує ті самі runtime guarantees, що й для Custom MCP. Є щонайменше один перевірений real provider, а не лише намальовані картки.

**Поза scope:** community ratings, billing, plugin bundles, package installation.

---

## Stage 9 — Official MCP Registry та All servers

**Мета:** динамічно наповнювати каталог без runtime-залежності від зовнішнього сервісу.

Офіційний Registry має read-only API та cursor pagination, але перебуває у preview і не гарантує uptime/data durability. Його рекомендована модель споживання — локальна персистентна копія з періодичним оновленням [W0].

### Реалізація

- [ ] Agent adapter для Registry, локальний bounded cache та періодичне оновлення на чинному scheduling mechanism. Ніякого нового scheduler service.
- [ ] Cursor pagination, коректне URL encoding names/versions, timeout/backoff, збереження останнього валідного snapshot.
- [ ] Search/filter API працює з локальним cache; браузер не ходить безпосередньо в Registry. Видно last sync та недоступність свіжого оновлення.
- [ ] Normalization entry → той самий descriptor Stage 8. Підтримані HTTP entries можуть підключатись; stdio/package-only — явно unsupported, без кнопки виконати package.
- [ ] Не робити outbound probe кожного server URL при sync: cache metadata не є приводом сканувати всі довільні адреси.
- [ ] Видалені/deprecated записи не пропонуються як нові рекомендовані installs. Existing connection отримує попередження; його endpoint і secrets не мігрують автоматично.
- [ ] Registry publisher identity не називати аудитом безпеки. Metadata/HTML/icons — недовірений input; не виконувати scripts/SVG active content і не тягнути arbitrary image URL backend-ом без policy.
- [ ] Curated Recommended overlay контролюється Forge, а не self-declared metadata сервера.

### Тести

- [ ] Multi-page sync, duplicate/update, malformed entry, interrupted pagination, upstream rate limit/outage і restart із cache.
- [ ] Last-known-good snapshot не стирається через часткову/невдалу синхронізацію.
- [ ] Пошук/filter/pagination у UI; remote HTTP проти local-only entry.
- [ ] Malicious description/icon/endpoint, endpoint change, deprecated/deleted entry.
- [ ] Registry недоступний: Connected list і реальні agent calls продовжують працювати.

### Acceptance

All servers динамічний, але виконання агентів не залежить від Registry. Каталог не видає credentials, не змінює існуючі підключення і не встановлює сторонній код.

**Поза scope:** власний публічний Registry API, mirror всіх пакетів, рейтинги й автоматичне trusted-install.

---

## Stage 10 — Повний acceptance, операційна перевірка та runbook

**Мета:** довести цілісний результат і дати зрозуміле обслуговування без відкриття нового scope.

### Обов'язкові end-to-end сценарії

| Сценарій | Що має бути доведено |
|---|---|
| Custom bearer/no-auth | Browser create → project/tool selection → real agent tool call. |
| OAuth | Connect → provider callback → MCP probe → agent call → refresh або чесний Reconnect. |
| Cold start | Restart Forge/runtime; connections і OAuth state після завершеного входу збережені; нові grants; ручне налаштування не потрібне. |
| Durable execution | Fresh, наступний invocation, resume і recovery з правильними tools та conversation identity. |
| Project isolation | Дозволений проєкт працює; недозволений не виконує ні list, ні call до upstream. |
| Concurrent accounts | Два однакові providers/два проєкти не змішують tokens або результати. |
| Revoke | Disable/Remove/access reduction з UI; після серверного revoke наступний dispatch відхиляється. Enable не відновлює старий grant. |
| External outage | Broken MCP не ламає інші integrations; Registry outage не ламає жодного existing connection. |
| Security boundary | Агент не читає external credentials/master key/DB access, не змінює connection management; inherited MCP відсутні. |
| Write ambiguity | Timeout/cancel не спричиняють прихованого повтору зовнішнього write. |
| Regression | Чинний engineering workflow, session activity, project navigation і no-MCP execution працюють. |

### Як перевіряти

- [ ] Детерміновані unit/ForgeIT/browser tests із локальними MCP/OAuth/Registry fixtures запускаються в CI. Мережа до реальних providers у них не потрібна.
- [ ] Окремий explicit live smoke використовує тестовий акаунт/репозиторій та реальну встановлену версію Codex. Read-only за замовчуванням; write acceptance лише на disposable resource за явним дозволом.
- [ ] Перевірити working runtime, а не лише proxy call з curl. У live evidence є дія з UI, реальний tool result і перевірка відкликання.
- [ ] Просканувати синтетичні secret canaries у logs, exceptions, API responses, storage exports, process/config artifacts і provider rollouts. Не переносити справжні секрети в test reports.
- [ ] Перевірити cleanup незавершеного OAuth, grants після crash і bounded resources під паралельними calls.
- [ ] Перевірити backup/restore зашифрованих credentials із правильним ключем; без ключа — явна непрацездатність, а не втрата контролю. Перевірити процедуру ротації key на тестових даних.

### Runbook

Створити `docs/mcp-integrations/operations.md` із фактичними командами й конфігами:

- де задаються gateway address, public callback URL, allowed internal hosts і custom CA;
- де зберігається encryption key та як робиться backup/restore/rotation;
- як підключити custom token server і configured OAuth provider;
- як перевірити connection, знайти safe diagnostics і відкликати доступ;
- як відрізнити auth expiry, insufficient scope, network/TLS problem та unsupported server;
- як працюють schema/tool changes і коли потрібний новий invocation;
- як вимкнути MCP без втрати звичайних workflows;
- які protocol/SDK/Codex versions реально пройшли acceptance;
- що не підтримано: stdio installs, універсальне repo-level filtering, повний per-call approval та сторонні MCP capabilities.

### Фінальний acceptance

Усі підтримані flows працюють з UI і чистого runtime; права enforced сервером; зовнішні secrets залишаються за перевіреною boundary; каталог не впливає на availability. Невиконаний live check позначається `NOT_VERIFIED`, не замінюється зеленими mocks.

---

## 8. Що свідомо не входить у цю roadmap

Не реалізовувати локальні MCP package installs, довільні shell commands, новий plugin marketplace, MCP workflow nodes, agent/provider framework, окремий Vault/IAM service, всеохопну модель permissions на рівнях workflow/node/agent, billing, ratings, universal resource authorization або автоматичну міграцію персонального Codex config.

SSH, `git push`, прямий shell HTTP і MCP tools — різні канали. Ця feature не робить їх взаємозамінними й не роздає shell доступи через MCP credentials.

Безпекові межі, яких прямо вимагають Custom URL, OAuth і runtime grants, входять у свої стейджі. Теоретичне hardening без конкретного contract need не додає нескінченні нові acceptance requirements.

---

## 9. Контроль завершення та звіт Codex

Після кожного стейджу оновлювати одну progress table у цьому документі або його репозиторній копії:

| Stage | Реалізація | Unit / IT / UI | Live evidence | Відкриті blockers |
|---|---|---|---|---|
| 0 | Evidence збережено: [stage-0-evidence.md](stage-0-evidence.md) | Disposable protocol / SDK / boundary probes; див. evidence | Зовнішні provider credentials не використовувалися | Runtime/management prerequisites визначено; неперевірене позначено NOT_VERIFIED |
| 1 | Реалізовано; frozen a102de5c audit ACCEPT, draft PR | Agent1257/0fail/0error/9live skips; Nexus306/0/0/0; Python25/25 | Disposable UID/systemd/Codex/Git PASS18; production NOT_VERIFIED | Відомий конфлікт auth із concurrently merged Stage6; combined-mode integration/tests до acceptance |
| 2 | Не розпочато | Не виконано | Не виконано | — |
| 3 | Не розпочато | Не виконано | Не виконано | — |
| 4 | Не розпочато | Не виконано | Не виконано | — |
| 5 | Не розпочато | Не виконано | Не виконано | — |
| 6 | Не розпочато | Не виконано | Не виконано | — |
| 7 | Не розпочато | Не виконано | Не виконано | — |
| 8 | Не розпочато | Не виконано | Не виконано | — |
| 9 | Не розпочато | Не виконано | Не виконано | — |
| 10 | Не розпочато | Не виконано | Не виконано | — |

Це tracking документа, не нові domain statuses.

Фінальний звіт одного стейджу:

```text
STAGE: <номер і назва>

IMPLEMENTED
Що реально змінено в production та UI.

REFERENCE / RESPONSIBILITIES
Яку чинну реалізацію перевикористано; які файли/межі відповідальності змінено.

VERIFICATION
Точні запущені команди й результати.
Unit, integration, browser і live перевірки — окремо.

ACCEPTANCE
Кожний критерій: підтверджений evidence або NOT_VERIFIED.

LIMITATIONS / BLOCKERS
Конкретні невиконані речі без маскування під успіх.

NEXT
Наступний стейдж за roadmap, без його самовільної реалізації.
```

Audit-only перевіряє фактичний код і тести окремо від implementation summary. Знайдені реальні correctness/security/architecture defects виправляються в межах поточного результату; style preference не перетворюється на нову фазу.

---

## 10. Стартовий запит для Codex

```text
Прочитай прикріплену Forge AI MCP Integrations roadmap повністю.

Працюємо над MCP-підключеннями на рівні Forge application, не над MCP workflow node.
Архітектура погоджена: Settings → Integrations; Console → typed Nexus → Agent;
Forge-owned credentials/OAuth; execution-scoped MCP gateway; native Codex tools.

Зараз виконай ЛИШЕ Stage 0.

Перевір актуальний код, найближчі прийняті референси, встановлений Codex protocol,
fresh/resume configuration, конфігураційні шари, persistence, management auth,
реальні sandbox/secret boundaries та сумісний MCP SDK.

Без production implementation і без реальних OAuth grants. Для runtime probes
використовуй disposable fixtures і synthetic credentials. Не змінюй особистий
Codex config, не відкривай sandbox network і не чіпай production secrets.

Збережи docs/mcp-integrations/stage-0-evidence.md:
точна карта файлів, перевірені protocol/SDK capabilities, результати probes,
reference implementations, необхідні мінімальні prerequisites та план Stage 1.
Якщо локально якийсь runtime недоступний — явно познач NOT_VERIFIED і не заявляй PASS.

Дотримуйся чинних правил Forge AI, typed Nexus boundaries та ForgeIT стилю.
Не створюй новий framework, окремий мікросервіс або нові доменні сутності для зручності.
Не створюй і не змінюй PR, його metadata чи comments і не merge-ь.

Після Stage 0 зупинись та поверни evidence і план Stage 1. Решту roadmap не реалізовуй.
```

Для наступних запусків використовувати той самий документ і явно називати один поточний Stage. Повний документ потрібен для незмінності архітектури; це не інструкція виконати всі стейджі одним великим патчем.

---

## 11. Первинні зовнішні джерела

Перевірені 23 вересня 2026 року. Код і protocol schema встановлених компонентів залишаються джерелом правди щодо їхньої фактичної сумісності. Посилання на `latest` можуть змінитися; Stage 0 фіксує конкретний контракт.

**[W0] Official MCP Registry Aggregators.** Read-only REST API, cursor pagination, локальне збереження, preview/availability caveats.

```text
https://modelcontextprotocol.io/registry/registry-aggregators
```

**[W1] MCP Authorization, ревізія 2026-07-28.** Discovery, OAuth flow, issuer/resource binding, token handling та refresh limitations.

```text
https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization
```

**[W2] MCP Client Registration, ревізія 2026-07-28.** Pre-registration, CIMD, DCR compatibility, issuer binding та deployment constraints.

```text
https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration
```

**[W3] MCP Security Best Practices.** Credential separation, token passthrough, SSRF, confused-deputy та network boundaries.

```text
https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices
```

**[W4] MCP Tools, ревізія 2026-07-28.** Tools discovery/calls, names/schemas, untrusted annotations, result/error semantics. Не копіювати wire examples у старішу protocol implementation.

```text
https://modelcontextprotocol.io/specification/2026-07-28/server/tools
```

**[W5] Official Codex App Server та MCP documentation.** Поточна документація описує configuration, MCP status/reload, native tools і thread lifecycle; наявність цих можливостей у Forge binary перевіряється окремо.

```text
https://developers.openai.com/codex/app-server
https://developers.openai.com/codex/mcp
```

**[W6] GitHub Remote MCP Integration Guide for MCP Host Authors.** OAuth/GitHub App prerequisites, PAT alternative та provider-specific limitations.

```text
https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md
```

**[W7] GitLab MCP server documentation.** Актуальні setup/auth та instance prerequisites; не доказ налаштувань конкретного користувацького GitLab.

```text
https://docs.gitlab.com/user/model_context_protocol/mcp_server/
```
