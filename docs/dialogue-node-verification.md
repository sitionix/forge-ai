# Dialogue node — перевірка

Дата: 2026-10-07. Гілка: `feature/SITIONIX-157`. База: `ec45fabc169bdce539f9c530bd7b33a20d6bf570`.

## Реалізовано

Окрема нода DIALOGUE: збережений діалог, уточнення, робочий draft, версійований підсумок та явні ACCEPT / REWORK / DEFER. Використовує існуючий Codex executor, сесії, MCP gateway й Nexus API. Сервер перевіряє бізнес-схему агента, точну revision, request-id, блокуючі питання й дозволений вихід. Прийнятий результат незмінний; наступна нода отримує envelope з результатом і provenance.

Console підтримує створення ноди, переписку, історію, питання/рішення/джерела, підтвердження конкретного підсумку, відображення завершеного результату та вибір activity окремого ходу агента.

## Автоматичні перевірки

- Console: **752/752**, 39 файлів; TypeScript typecheck і production build пройшли.
- Фокусовані інтеграційні тести Agent/Nexus та mapper після останніх виправлень: Maven BUILD SUCCESS.
- Повний фінальний Maven verify: **BUILD SUCCESS**. Agent — **1715 tests: 1703 passed, 12 skipped, 0 failures/errors**; Nexus — **393/393**. Прогін включає unit та integration suites і фінальну збірку.
- Flow integration: INITIAL → CHAT → CHAT → SUMMARY → ACCEPT/DEFER; той самий conversation, різні turns, точний downstream envelope; REWORK створює нову ноду/сесію, повтор старого completion не повторює маршрутизацію.
- Restart integration: очікування відповіді, queued CHAT, expired active turn з пізнім callback та незавершена маршрутизація completion переживають реальний перезапуск Spring application із тією самою БД.
- Migration integration: V43 → V46 зберігає старі Manual nodes/ports/context. Legacy migration assertions перевіряють усі історичні значення й окремо дозволяють тільки навмисно додану nullable Dialogue binding.
- Перевірено stale revisions, payload-changing request replay, ліміти, schema violations, cancellation, recovery, exact turn MCP authorization та сумісність звичайних вузлів.
- Вигляд перевірено у headless Chrome на широкому та вузькому viewport; браузерну взаємодію додатково перевіряють Console integration tests.

## Незалежне рев’ю

Проведено одне рев’ю всієї гілки агентом із чистим контекстом. Чотири важливі знахідки виправлені з RED → GREEN регресіями:

1. Polling міг підмінити підсумок під сфокусованою кнопкою ACCEPT. Тепер підтверджується зафіксована версія; нова revision скасовує підтвердження.
2. Завершений результат показувався як робочий draft. Тепер є завершений підсумок, вибраний вихід і metadata після reload.
3. Ігнорувались бізнес-назви та описи виходів. Тепер вони проходять із snapshot через Agent/Nexus до Console.
4. Activity показувало тільки останній хід. Тепер попередній хід можна вибрати, polling зберігає вибір, а events завантажуються за точним turn ID.

## Межі загальних перевірок

Загальний `scripts/test.sh` виявив **19 збоїв Knowledge** (983/1002). Knowledge typecheck має **65 mypy errors у 15 файлах**, lint — **1902 Ruff errors**. Файли Knowledge/Jarvis не змінені відносно бази цієї гілки; їхні збої не виправлялись у межах Dialogue. Jarvis 79/79, portable startup 15/15 пройшли. Це означає, що весь monorepo наразі не зелений.

## Прийняті рішення та їхні наслідки

- Звичайна feature-гілка у наявному checkout, відповідно до AGENTS.md; ізоляція обмежена гілкою, паралельні сторонні зміни треба зберігати.
- Вбудовано Dialogue у наявний executor/session engine. Provider continuity, fencing і MCP grants мають спільний життєвий цикл зі звичайними агентами; окремого чатового runtime немає.
- Перевірка схеми використовує вже доступний networknt validator; зовнішні schema resources вимкнені, remote `$ref` не завантажуються.
- Claim/result storage tests розділяють транзакції як реальний runtime; deferred session trigger перевіряється на commit.
- Неоднозначні expired executions завершуються помилкою без автоматичного replay чи нового conversation; повторна спроба потребує нового запуску.
- Cancellation синхронізує Dialogue та queued/active provider work; context reset для Dialogue заборонений. Це зберігає послідовність історії.
- Нові DB constraints додані у V46 без переписування попередніх міграцій; звичайні active-node queries враховують WAITING_FOR_DIALOGUE.
- Nullable Dialogue fields не змінюють JSON звичайних Nexus nodes/ports; описи додаються тільки Dialogue run ports.
- Testcontainers запускався з `-Dapi.version=1.44`: локальний Docker не підтримує bundled client default 1.32. Конфігурацію Docker не змінювали.
- Під час verification/package JAR замінюється на диску; живі systemd services перезапускаються після останньої збірки перед smoke.
- Живий smoke використовує ізольований локальний проєкт із забороною tools/external writes в інструкціях; він перевіряє provider continuity й маршрутизацію, а не Notion integration.

## Deferred minors

- Timestamps повідомлень зберігаються, але не показуються в Dialogue panel; точний час окремого повідомлення потребує API.
- Повний initial input не виводиться безпосередньо в Dialogue panel; його треба дивитись у task/upstream nodes.

Attachments, streaming та синхронізація Notion board залишаються окремими задачами, як визначено в дизайні.

## Codex runtime compatibility

Живий smoke виявив, що чинний allowlist збережених сесій не включав встановлений CLI `0.160.1`. Додано тільки цю конкретну версію після RED → GREEN регресій. Два opt-in тести на справжньому `0.160.1` пройшли: новий процес відновлює conversation та пам’ятає попередній факт; recovery читає точний завершений turn без thread/start, thread/resume чи turn/start. Невідомі версії й зміна версії між ходами лишаються заборонені.

Протокол звірено з [офіційною документацією Codex App Server](https://learn.chatgpt.com/docs/app-server); сумісність конкретного встановленого CLI підтверджена локальними native тестами.

Команда аудиту: `mvn -B -pl services/forge-agent/infrastructure/codex -am test -Dtest=CodexAppServerTurnClientTest,CodexRecoveryInspectorTest,CodexDurableSessionE2ETest,CodexRecoveryE2ETest -Dsurefire.failIfNoSpecifiedTests=false -Dforge.codex.live-session-e2e=true -Dforge.codex.live-recovery-e2e=true -Dforge.codex.live-model=gpt-6.1-sol`.

Smoke потребує selected/cloned repository за чинним task/workspace контрактом. Використано окремий публічний octocat/Hello-World checkout через звичайний API; робочі репозиторії Ancestor та MCP permissions не змінені.

Повторний повний Agent verify після додавання CLI 0.160.1: BUILD SUCCESS, 1713 tests / 0 failures / 0 errors / 11 skipped. Команда: `mvn -B -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`. Повний попередній Agent/Nexus прогін: `mvn -B -Dapi.version=1.44 -pl services/forge-agent/boot,services/forge-nexus/boot -am verify`.

Живий provider також виявив несумісний `uniqueItems` у внутрішньому Dialogue envelope. Provider schema прибирає тільки цей keyword; серверна схема зберігає його й відхиляє повтори `userMessageIds`. Бізнес-схема агента не послаблюється. RED → GREEN regression і opt-in `CodexDialogueReplySchemaE2ETest` на справжньому gpt-6.1-sol пройшли. Обмеження provider schema звірені з [офіційною документацією Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

Остаточний повний Agent verify після provider schema виправлення: BUILD SUCCESS, 1715 tests / 0 failures / 0 errors / 12 skipped. Native schema тест запускався окремо з `-Dforge.codex.live-dialogue-e2e=true`; у звичайному suite він пропущений разом із іншими opt-in live тестами.
