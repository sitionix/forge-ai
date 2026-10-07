# Dialogue: розмова з агентом усередині workflow

Дата: 2026-10-07. Стадія: специфікація для перегляду власником, реалізація ще не почалася.

## Мета та погоджений напрям

Додати окрему ноду `DIALOGUE` до Forge. Людина має вести кілька раундів переписки з обраним агентом, уточнювати задачу, переглядати поточний результат і явно завершувати діалог вибором виходу workflow. Перша реалізація — діалог у Forge Console. Notion і створення агентів грумінгу Ancestor — наступні окремі задачі.

Критерій успіху: агент отримує upstream-контекст, ставить питання, враховує текстові відповіді, створює валідний результат; користувач приймає конкретну версію або повертає її на дослідження; лише після цього активується відповідна гілка графа. Перезапуск, повторний HTTP-запит чи дві відкриті вкладки не гублять розмову і не дублюють виконання.

Припущення, яке потребує підтвердження разом зі специфікацією: основна переписка відбувається у Forge; користувач може повернутися до неї пізніше, без відкритої вкладки браузера.

## Підстави в поточному коді

- `NodeType` має `AGENT` і `MANUAL`.
- `ManualSelectionRequest` приймає тільки `outputPortId`; UI ручного вузла показує кнопки виходів.
- `WorkflowGraphValidator` і `WorkflowRunSnapshotBuilder` забороняють прив'язувати агента до `MANUAL`.
- `NodeRunWorker` завершує звичайний агентський NodeRun після одного результату `AgentExecutor.execute`.
- `agent_execution_turns.node_run_id` має UNIQUE-обмеження у V26. Методи `findByNodeRunId` і `acquire` покладаються на один хід на NodeRun.
- Наявні сесії вже мають збережену provider conversation identity, lease, fencing, heartbeat, recovery та журнал подій.
- Nexus відповідає за типізоване проксі; семантика workflow належить Forge Agent.

Отже, чат потребує нового життєвого циклу та контрольованого розширення прив'язки ходів. Просто додати textarea до `MANUAL` або прибрати UNIQUE недостатньо.

## Межі першої версії

Входить:

- тип ноди `DIALOGUE`, обраний агент, редаговані входи й виходи;
- текстова переписка, серверне збереження історії та послідовні раунди агента;
- панель поточного результату й явне завершення на конкретній ревізії;
- робота після оновлення сторінки та перезапуску сервісу;
- типізовані API Agent → Nexus → Console;
- activity для кожного ходу, скасування, контроль конфліктів і тестування;
- читання обраних робочих репозиторіїв у read-only sandbox.

Не входить:

- синхронізація Notion, сповіщення, розклад і автоматичне читання зовнішніх відповідей;
- MCP у діалозі, запис файлів, виконання імплементації або зовнішні зміни;
- вкладення, голос, редагування/видалення вже надісланих повідомлень;
- спільне редагування повідомлення кількома людьми;
- потокове відображення токенів, приховані міркування моделі;
- використання сесії попереднього агента або спільної сесії між різними нодами;
- довільна зміна графа чи агента під час поточного запуску.

Відповіді користувача можуть містити звичайний текст і блоки коду. Повідомлення рендеряться без виконання HTML. Вихідні посилання не повинні відкривати виконувані URL-схеми.

## Поведінка редактора workflow

У палітрі є `Dialogue` у категорії участі людини поруч із `Manual`.

Нода має:

- `nodeType = DIALOGUE`;
- обов'язковий `targetId` агента цього самого проєкту;
- тільки GLOBAL scope у першій версії;
- наявний `inputMode`: TASK_AND_DEPENDENCIES, DEPENDENCIES_ONLY або інший уже підтримуваний режим;
- наявний вибір робочих репозиторіїв для GLOBAL агентської ноди;
- власну сесію на кожне виконання ноди, без перемикача shared/fresh context у UI;
- виходи з бізнесовою назвою та описом, порядком і типізованим призначенням `ACCEPT`, `REWORK` або `DEFER`.

Виходи початкового preset: `Прийняти й продовжити` (ACCEPT), `Повернути на дослідження` (REWORK), `Відкласти` (DEFER). Користувач може змінювати назви та підключення. Рівно один ACCEPT обов'язковий; REWORK і DEFER необов'язкові, кожен не більше одного. Призначення виходів не визначається з тексту їхніх назв.

Відсутня гілка на виході має ту саму семантику завершення, що й у чинному графі. DEFER завершує цю ноду з погодженим результатом відкладення; це не приховане очікування і не скасування всього workflow. Для відкладення після цього запускають нове виконання за правилами наявної системи.

Агент, модель, інструкції, schema, порти, призначення виходів і робочі репозиторії фіксуються у snapshot запуску. Редагування definition не змінює відкритий діалог.

## Екран діалогу

У Task Execution при виборі ноди Dialogue відкривається панель:

1. Назва агента, зрозумілий стан і доступ до початкового контексту.
2. Послідовна історія `Агент` / `Ви` з часом надсилання.
3. Поле повідомлення й кнопка `Надіслати`; Enter додає новий рядок, Ctrl/Cmd+Enter надсилає.
4. Поточний результат: текст агента, структурована чернетка, питання, рішення та джерела.
5. Дії `Підготувати підсумок` і кнопки виходів із явним підтвердженням прийнятої ревізії.

Завершений діалог доступний для читання з позначенням прийнятої версії й обраного виходу. Історичні виконання тієї самої ноди не змішуються.

Під час відповіді агента показувати `Агент відповідає…`; надсилання наступного повідомлення й завершення заблоковані до завершення ходу. Локальна ненадіслана чернетка залишається при помилці запиту. Повтор того самого запиту не додає другого повідомлення. При підтвердженому збереженні повідомлення поле очищується.

Панель оновлюється polling, використовуючи наявні підходи Console; немає потреби у WebSocket для першої версії. Вихід зі сторінки не скасовує хід. Прочитання відновлюється зі збереженого серверного стану, а не з пам'яті вкладки.

## Життєвий цикл

Один Dialogue NodeRun містить багато внутрішніх ходів. Ці ходи не створюють додаткових виконань графа.

Після активації ноди система зберігає діалог і початковий хід агента. Агент одразу отримує immutable input envelope і повинен сформулювати, що вже відомо та що потребує уточнення. Перше повідомлення користувача не є обов'язковим для старту.

Стан NodeRun:

- PENDING: виконання створене, початковий хід ще не захоплений worker;
- RUNNING: є queued/active агентський хід або підготовка підсумку;
- WAITING_FOR_DIALOGUE: агентський хід завершений, очікується людина;
- SUCCEEDED: користувач явно обрав вихід і зафіксував завершення;
- FAILED або CANCELLED: чинні семантики помилки/скасування.

WAITING_FOR_DIALOGUE є активним станом для правил завершення workflow. Workflow залишається RUNNING; окремий статус workflow не додається. Очікування людини не має таймауту моделі й не тримає worker thread, provider process, session lease або активний MCP grant.

Стан діалогу всередині WAITING_FOR_DIALOGUE розрізняє `AWAITING_REPLY` та `AWAITING_REVIEW`. У UI це `Потрібна ваша відповідь` або `Підсумок готовий до перегляду`. AWAITING_REVIEW можливий тільки після успішного SUMMARY; CHAT та INITIAL залишають ноду в AWAITING_REPLY. Агентський `readyForReview` є пропозицією готовності, а не прийняттям користувачем. SUMMARY із відкритими blocking-питаннями також доступний для перегляду і REWORK/DEFER, але ACCEPT недоступний.

Нове повідомлення інвалідує доступність прийняття попереднього підсумку. `Підготувати підсумок` запускає окремий хід SUMMARY без вигаданого user message. Після його завершення можна прийняти отриману ревізію або продовжити розмову.

## Контракт відповіді агента

Для кожного агентського ходу діалогу використовується окремий Forge-owned envelope, а не звичайний контракт AGENT, який наказує обрати outputPortId.

Envelope містить:

- `message`: непорожній видимий текст відповіді;
- `draft`: null або результат відповідно до snapshotted AgentOutputSchema;
- `questions`: список питань зі стабільним у межах діалогу ID, текстом, причиною, ознакою blocking і рекомендацією, якщо вона є;
- `decisions`: погоджені рішення з посиланням на повідомлення користувача, що їх підтверджує;
- `sources`: використані джерела з посиланням або шляхом та ревізією, якщо вона відома;
- `readyForReview`: boolean.

Для CHATTING `draft` може бути null. Для SUMMARY потрібна валідна `draft` за schema агента; якщо schema відсутня, застосовується чинна політика schema-less результатів Forge. Null ніколи не є готовим підсумком. Відкрите blocking-питання не дозволяє `readyForReview=true`.

Відповідь агента не змінює workflow routing. Ідентифікатори виходів не входять до повноважень агента діалогу. Текстове «приймаю» у розмові не завершує ноду: потрібна окрема дія користувача. Кожен CHAT input передає нове повідомлення з його ID та revision; INITIAL передає immutable upstream context, SUMMARY — явний запит на підсумок і видимі IDs повідомлень. Server-provided provenance не змішується з текстом вимог.

Джерела та рішення — видимі структуровані твердження агента. Вони не є автоматично перевіреними фактами; кінцевий reviewer у майбутньому grooming-flow перевірятиме їх незалежно. Сервер перевіряє schema та існування вказаних message IDs, але не приписує собі перевірку істинності тексту.

## Збережені сутності та ревізії

Forge Agent володіє сутностями:

- Dialogue: один на Dialogue NodeRun, стан, revision, поточна summary revision, прийнятий вихід/ревізія, timestamps;
- DialogueMessage: append-only послідовність USER/ASSISTANT, content, timestamp, origin turn ID; provider error показується як стан помилки, а не вигадане повідомлення агента;
- DialogueTurn: INITIAL, CHAT або SUMMARY; request ID, triggering message ID для CHAT, input revision, стан, execution turn ID, збережений результат і failure;
- DialogueRevision: immutable валідний draft + questions/decisions/sources, створений конкретним успішним turn;
- DialogueCompletion: immutable output choice, disposition, accepted revision і request ID завершення.

Revision збільшується на кожній серверній зміні діалогу. Версія підсумку посилається на точну ревізію контексту, для якої його підготували. Тільки revision, створена SUMMARY, може бути summaryRevisionId для завершення. Draft від CHAT/INITIAL залишається робочою чернеткою. Після нових повідомлень старий draft зберігається як історія, але не придатний для ACCEPT.

USER message і його queued turn зберігаються атомарно до виклику provider. ASSISTANT message, завершення turn, нова revision і перехід до очікування зберігаються атомарно після валідного результату. Тривалий provider call виконується поза DB-транзакцією.

Історія сторінкується за монотонним cursor; основний GET повертає стан, актуальний підсумок і першу сторінку історії. Немає мовчазного обрізання контексту чи transcript. До початкового введення застосовується конфігурований ліміт розміру. Для перевищення контексту provider — явна помилка; автоматична компактизація бізнесових рішень поза scope.

## Інтеграція з наявним execution runtime

Не створювати другу незалежну систему provider conversation, lease та recovery.

Розширити чинні execution session/turn contracts:

- додається session context mode `DIALOGUE_WITHIN_NODE_RUN`, недоступний звичайним AGENT нодам;
- така сесія має обов'язкове прив'язування до конкретного Dialogue NodeRun; унікальна за цим виконанням;
- кожний DialogueTurn має окремий AgentExecutionTurn у цій сесії;
- `agent_execution_turns` отримує nullable `dialogue_turn_id` із FK та unique; ordinary execution має null;
- UNIQUE на node_run_id замінюється частковим UNIQUE для ordinary turns; для Dialogue зберігаються session sequence uniqueness та один active writer;
- DB перевіряє відповідність dialogue_turn, owning NodeRun і session. Ordinary turn не може належати DIALOGUE, dialogue turn — AGENT або MANUAL;
- методи пошуку/claim ordinary execution явно вибирають ordinary turn; для діалогу використовують exact execution turn ID. Немає `findFirst()` серед різних ходів;
- session завершення окремого діалогового turn звільняє lease і залишає сесію IDLE; завершення ноди закриває сесію;
- при повторній активації тієї самої source node створюється нова розмова, старий transcript не підхоплюється.

Provider transport, conversation identity callbacks, heartbeat, dispatch fencing і activity залишаються спільними. Розділяється execution request/result contract: звичайний workflow turn і dialogue turn мають типізовані запити з різною completion policy. Результат dialogue turn не викликає звичайний `NodeRunLifecycle.succeed`.

Повторно використати Codex durable conversation start/resume. Не застосовувати звичайні instructions, що наказують завершити граф або вибрати порт. Dialogue instructions прямо відділяють upstream дані, повідомлення користувача і контракт відповіді. Provider ID, модель та effort зберігаються зі snapshot агента.

Окремий DialogueTurnWorker виконує пошук queued ходів; він використовує спільний execution infrastructure, але керується Dialogue lifecycle. NodeRunWorker делегує старт DIALOGUE відповідній lifecycle policy. Нода не утримує thread між повідомленнями.

GLOBAL Dialogue workspace використовує чинний resolver і snapshot репозиторіїв, а provider sandbox для Dialogue — read-only. MCP execution preparation для Dialogue не викликається. Наявний AGENT runtime з його workspace-write і MCP behavior не змінюється.

Кількість внутрішніх ходів контролюється окремим конфігурованим бюджетом `max-dialogue-turns-per-node-run`, початкове значення 100. Враховуються INITIAL, CHAT і SUMMARY. До створення нового turn перевіряється бюджет. При вичерпанні новий turn не створюється; історія доступна, можна завершити через REWORK/DEFER з уже наявним валідним підсумком або скасувати workflow. Ліміт graph NodeRuns не витрачається на повідомлення.

## API та межі сервісів

Agent API в контексті owning workflow/node run:

- GET `/api/v1/workflow-runs/{runId}/node-runs/{nodeRunId}/dialogue`: стан, revision, summary, messages cursor;
- GET `.../dialogue/messages?afterSequence=...&limit=...`: наступна сторінка історії;
- POST `.../dialogue/messages`: `requestId`, `expectedRevision`, `text`;
- POST `.../dialogue/summary`: `requestId`, `expectedRevision`;
- POST `.../dialogue/complete`: `requestId`, `expectedRevision`, `summaryRevisionId`, `outputPortId`.

Mutations повертають authoritative state після commit. HTTP 202 для accepted queued agent turn; 200 для завершення або ідемпотентного повтору. Обмеження text — від 1 до 16 000 Unicode code points після перевірки на whitespace-only; зміст повідомлення не нормалізується шляхом видалення внутрішніх пробілів чи рядків. `requestId` — клієнтський UUID, унікальний у межах діалогу для всіх команд.

Повтор requestId з тим самим payload повертає підтвердження початкової операції та актуальний стан; з іншим payload — `DIALOGUE_REQUEST_CONFLICT`. Ідемпотентний повтор розпізнається перед перевіркою expectedRevision. Друга нова команда зі старою revision — `DIALOGUE_REVISION_CONFLICT`.

Команди в неправильному стані, для іншого owning run, чужого проєкту або не-DIALOGUE ноди відхиляються явно. Немає endpoint довільного переприв'язування conversation ID.

Nexus передає typed requests/responses за наявними controller → mapper → use-case → port → adapter/client шарами. Він не зберігає розмови, не обирає виходи та не реалізує lifecycle. Console викликає тільки Nexus.

## Явне завершення й передача результату

Complete можливий тільки без queued/active turn, для активного workflow, з exact актуальною summary revision і портом snapshot цієї ноди.

ACCEPT додатково потребує `readyForReview=true`, валідного non-null draft і відсутності blocking questions. REWORK/DEFER можуть містити blocking questions, але також потребують сформованого поточного summary. Ці виходи не оголошують задачу готовою до імплементації.

NodeRun output є версійованим типізованим envelope:

- `contractVersion`: 1;
- `result`: прийнята/повернена draft за AgentOutputSchema;
- `dialogue`: disposition, summary revision ID, accepted-at timestamp, decisions, questions, sources і посилання на transcript;
- port selection записується сервером у наявне selectedOutputPortId.

AgentOutputSchema описує `result`, а не метадані діалогу. Ця відмінність від ordinary AGENT output документується й відображається у builder preview downstream input. Наступний вузол отримує повний envelope у contribution payload; transcript не дублюється в кожному payload.

Коміт completion, NodeRun SUCCEEDED і закриття session атомарний. Далі наявний recoverable completion processor маршрутизує selectedOutputPortId. Повтор complete не породжує другу активацію; інший вихід після вже прийнятого завершення відхиляється. Crash між commit і routing відновлюється звичайним completion worker.

## Конкурентність, помилки та відновлення

Порядок блокувань mutations і completion: owning WorkflowRun → NodeRun → Dialogue → execution session/turn. Спільний session infrastructure узгоджується з цим порядком; нові callback не беруть блокування у зворотному порядку.

Не більше одного queued/active dialogue turn на діалог. Одночасні send/summary/complete із різних вкладок мають одного переможця; решта отримує явний конфлікт і оновлює UI. Кожен provider результат перевіряє exact turn ID, owning dialogue revision і чинний session lease token. Пізній callback не може додати повідомлення після завершення/скасування або змінити прийнятий draft.

Wait state переживає restart без provider роботи. Queued хід запускається після restart. Для interrupted STARTING/ACTIVE використовувати чинний conservative recovery: не повторювати uncertain provider dispatch автоматично. Якщо завершення turn уже надійно зафіксоване, довести локальне застосування без нового ходу. Невизначений або невідновлюваний хід явно позначити FAILED і завершити відповідний NodeRun як FAILED; transcript лишається доступним. У першій версії немає reset діалогу зі створенням нової прихованої conversation.

Провайдерська помилка, невідповідна schema чи resume identity mismatch не видаються за відповідь агента або успішний підсумок. Звичайний recovery працює тільки з ordinary execution; Dialogue recovery handler застосовує власну completion policy до exact dialogue turn.

Stop workflow скасовує queued turns, interrupt-ить active provider turn чинним механізмом, закриває діалог і забороняє наступні команди. USER messages і успішні попередні replies залишаються в історії. Context reset endpoints відхиляють open Dialogue session; окремий UX reset поза scope.

## Міграції й сумісність

Нові Flyway migrations додають dialogue storage, призначення виходів, стан WAITING_FOR_DIALOGUE, DIALOGUE node type і session/turn binding. Номер міграції визначається за актуальним деревом під час реалізації, старі міграції не редагуються.

Оновлюються всі persisted enums/check constraints/triggers, snapshot entities, DTO, mappers, validators і cancellation/recovery queries, що залежать від типу ноди або одного turn на NodeRun. Особливо перевірити activity/context lookup: ordinary lookup не може випадково повернути перший dialogue turn; UI Dialogue явно обирає хід, для якого читає activity.

Наявні definition і run snapshots залишаються AGENT/MANUAL, без зміни default semantics. Нові поля nullable для старих портів; disposition обов'язковий тільки для DIALOGUE output. Upgrade існуючої БД тестується з історичними ordinary sessions/turns, manual waiting nodes і shared contexts.

## Перевірки, які мають довести реалізацію

Unit / policy:

- validation типу, GLOBAL scope, target agent, output dispositions, snapshot і schema envelope;
- INITIAL → reply → CHAT → reply → SUMMARY → review → complete;
- SUMMARY без draft, blocking questions, invalid schema й stale summary не проходять ACCEPT;
- нове повідомлення інвалідує старе прийняття, idempotent retry не інвалідує повторно;
- read-only/no-MCP Dialogue request і незмінний звичайний AGENT request;
- ліміти text/turn budget, порожній текст, неправильний owning run;
- completion звичайного agent turn не застосовується до Dialogue.

PostgreSQL integration з deterministic provider:

- кілька execution turns на один Dialogue NodeRun, одна session і різні provider turn identities;
- звичайна one-turn uniqueness, shared context і manual behavior збережені;
- duplicate request, same/different payload, дві вкладки, send vs complete, cancellation vs callback;
- рівно один downstream activation на вибраному виході;
- restart під час очікування, після queued commit і між completion commit/routing;
- expired lease, stale callback, невизначений dispatch, invalid resume identity;
- append-only transcript та immutable accepted revision;
- upgrade schema з існуючими sessions/turns і перевірка нових constraints.

Nexus contract:

- усі requests/responses, revision/conflict/failure передаються без втрати семантики;
- правильне escaping повідомлень, schema payload і джерел;
- Nexus не запускає lifecycle чи provider execution.

Console:

- builder створює/редагує/зберігає Dialogue; інші ноди працюють як раніше;
- chat history, draft, questions, submit, summary, output selection;
- pending state, polling, stale revision, повтор запиту та повернення до сторінки;
- HTML/script ін'єкції, keyboard navigation, focus, доступні labels;
- довгі повідомлення й вузький екран не руйнують layout;
- historical dialogue й accepted revision залишаються read-only.

Наскрізне приймання у тестовому проєкті Forge:

1. Upstream agent → Dialogue → downstream reviewer.
2. INITIAL задає питання; два USER messages отримують дві відповіді в тій самій conversation.
3. Підсумок підготовлено, потім нове повідомлення робить його непридатним для ACCEPT.
4. Новий підсумок явно прийнято; reviewer отримує exact accepted result/decisions.
5. Окремі запуски доводять REWORK, DEFER, restart waiting та stop active.
6. Для live provider перевірити actual conversation/turn identity й downstream payload; mock-тести не оголошуються доказом роботи живої моделі.

Команди перевірок визначаються в implementation plan за чинними scripts/pom/package.json. Потрібні focused unit/IT, Console tests/typecheck/build та живий сценарій. Якщо live авторизація або provider недоступні, це явно зазначається; реалізація не оголошується повністю перевіреною.

## Умови приймання

- Діалог підтримує багато раундів без додаткових graph NodeRuns і без змішування різних виконань.
- Людина може повернутися після reload/restart і бачити збережені повідомлення, підсумок та стан.
- Тільки explicit complete завершує ноду; ACCEPT стосується актуальної валідної summary revision.
- Обрана гілка активується один раз із exact output, який переглянув користувач.
- Workflow не завершується, доки Dialogue чекає людину.
- Чинні AGENT/MANUAL, recovery, session isolation, cancellation і MCP доступ звичайних агентів не регресують.
- Архітектурні межі Agent/Nexus/Console збережені; provider transport/leases не дублюються.
- Обов'язкові перевірки пройдені з документованими результатами та межами live verification.

## Самоперевірка специфікації

- Відділено діалоговий turn від завершення graph NodeRun.
- Виявлене one-turn DB-обмеження враховане разом із lookup, claim, recovery та activity.
- Прийняття прив'язане до версії, idempotency і optimistic concurrency визначені.
- Визначено форму downstream output та роль AgentOutputSchema.
- Notion і створення grooming agents не включені до першого implementation scope.
- Помилки й uncertain recovery не маскуються автоматичним повтором або новим контекстом.

Наступний крок після перегляду цієї специфікації власником — implementation plan із послідовністю змін і командами тестування.
