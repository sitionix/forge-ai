# MCP Integrations Stage 1 — evidence / PR #148 corrections

## PR #148 — Codex lifecycle та Nexus error boundary (2026-09-24)

**Root cause:** `CodexJsonRpcTransport` запускав stdout reader до присвоєння обох reader references. Ранній EOF міг викликати `invalidate → closeManagedProcess → completeCleanup → joinReader`, поки `stderrReaderThread` був `null`. Join падав, owned stop уже виконувався, але `cleanupComplete` залишався `false`. Новий deterministic `stdoutEofDuringConstructionWaitsForBothReadersBeforeConfirmingCleanup` керує цим порядком через latches і bounded joins: на старій реалізації **RED** (7 tests, 1 failure, expected cleanupComplete=true); після створення обох unstarted readers та їх запуску під `lifecycleLock` **GREEN**. Інші lifecycle сценарії (звичайний close, EOF за живого pipe, уже завершений pipe, response timeout, blocked stdin, retry невдалого stop, recovery) залишилися в focused suite. Три повторні focused runs: по **27 tests, 0 failures/errors/skips**, `BUILD SUCCESS`.

**Nexus error boundary:** `ForgeAgentMcpClientAdapter` виконує map → звичайний `ForgeAgentClientCallExecutor.execute` → map. Спільний executor тільки зберігає transport status/body/headers/cause у чинному `AgentClientException`; MCP JSON і public response обробляє scoped `McpConnectionsExceptionHandler` через configured Jackson mapper та typed `InfrastructureErrorResponse`. Валідні 400/404/409/422/500 зберігають status/code/message/optional correlationId; malformed → static 502, `ResourceAccessException` → static 503, локальна validation/unreadable request → 400. WARN повідомлення не містять raw payload або throwable. Видалено `executeMcp`, `parseMcpError`, `invalidMcpResponse`, MCP-specific error mapper і `McpAgentClientException`; source search не знайшов їхніх Java consumers. Окремий Nexus ForgeIT `mcpAdvicePreservesTransportWrapperWithoutPublishingItsRawCause` на старій реалізації був **RED**: очікуваний upstream409 перетворювався на502. Після refactor — **GREEN**; synthetic header/cause не потрапляють у response або captured logs. Handler unit також виявив числовий `code`, який Jackson міг перетворити на string; strict node type validation виправила цей випадок до фінального run.

| Перевірка цієї корекції | Фактичний результат |
|---|---|
| Focused Codex lifecycle/transport/recovery, 3 runs | 27 tests кожен; 0 failures/errors/skips; exit0 |
| Focused Nexus executor/adapter/handler та HTTP auth matrix | 15 unit/config + 27 ForgeIT = 42; 0 failures/errors/skips; exit0 |
| Повний Agent verify | 1284 tests; failures0/errors0/skipped9; exit0/BUILD SUCCESS |
| Повний Nexus verify | 363 tests; failures0/errors0/skipped0; exit0/BUILD SUCCESS |
| `git diff --check` | PASS |

Повні команди: `mvn -o -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` та аналогічно `-pl services/forge-nexus/boot -am verify`. Docker29 потребував cached dependency mode та API1.44; особисті Maven/Codex configs не змінювалися. Full logs: `/tmp/forge-mcp-pr148-final-agent.log`, `/tmp/forge-mcp-pr148-final-nexus.log`; JSON reports зібрані лише з XML після `1790243137`. RED logs: `/tmp/forge-mcp-pr148-lifecycle-red.log`, `/tmp/forge-mcp-pr148-error-red.log`; focused GREEN: `/tmp/forge-mcp-pr148-lifecycle-green{1,2,3}.log`, `/tmp/forge-mcp-pr148-nexus-focused.log`. Дев'ять Agent skips — opt-in live checks, **NOT_VERIFIED**. Для кодового commit `b84eac34` свіжий [CI run #35983992676](https://github.com/sitionix/forge-ai/actions/runs/35983992676) завершився: Forge Agent, Forge Nexus, Knowledge, Console і Jarvis — PASS. Попередній head `a9b88313` мав Agent failure у цьому lifecycle test. Production deployment, TLS, privileged OS/sandbox probes та live network залишаються **NOT_VERIFIED** і не перевиконувалися в цій корекції.

Додатковий scan Agent/Nexus full logs та focused Nexus log для семи відомих synthetic body/header/cause/credential canaries дав 0 matches у кожному; точний список у verification JSON. Це доповнює HTTP та `CapturedOutput` assertions і не є доказом для довільного нового payload.

Наведені нижче 1283/362 та попередній опис executor є **історичними результатами до цієї корекції**, не поточним станом. Перевірений auth fix збережено без redesign.

Поточні auth/error виправлення перевірено після локального включення main `58f2854f03b7abc2413be2f7afa98cb7de13d6ed` (Remote Access Stage6), correction base `d6fa685e`. Гілка `feature/SITIONIX-142`, checkout `/tmp/forge-mcp-stage1`. Original checkout і його сторонні зміни збережено. PR metadata/comments/reviews та merge не змінювалися. Stage2+ не реалізовано.

Попередній integration blocker відтворено кодом і виправлено: Agent має явного власника route, Nexus combined mode — одну чинну RA session authority. Нижче наведено фактичні synthetic HTTP докази; production/runtime deployment не оголошується перевіреним. Історичні Stage1 результати на Stage5 base `a102de5c` збережено окремо.

[Точна карта файлів](stage-1-file-map.md) · [Machine-readable verification](stage-1-verification.json) · [Runbook](stage-1-operations.md) · [Рішення](stage-1-decisions.md) · [Наступний Stage2 — лише план](stage-2-plan.md).

## PR #148 — попередня перевірка auth та errors (історична)

| Перевірка | Фактичний результат |
|---|---|
| Повний Agent verify | 1283 tests, failures0/errors0/skipped9, exit0/BUILD SUCCESS |
| Повний Nexus verify | 362 tests, failures0/errors0/skipped0, exit0/BUILD SUCCESS |
| Focused error regression | 10 unit + 17 Nexus IT + 9 Agent IT, failures0/errors0/skipped0 |
| Focused auth matrix | 20 unit + 41 IT, failures0/errors0/skipped0 |

```sh
mvn -o -B -Dapi.version=1.44 -f services/forge-agent/pom.xml verify
mvn -o -B -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
git diff --check
```

Fresh XML totals зібрано лише зі звітів після початку цих full runs (`1790239281`); source fingerprint і точні skips/reasons — у verification JSON. Логи `/tmp/forge-mcp-pr148-fixes/full-agent-verify.log`, `full-nexus-verify.log`, `auth-final.log`, `error-unit-final2.log`, `error-nexus-it-final.log`, `error-agent-it.log`. Agent skips — opt-in live checks, **NOT_VERIFIED**, не PASS. Попередні Python/privileged runtime результати нижче історичні: їх не повторювали для auth/error correction.

| Mode / boundary | Перевірений контракт |
|---|---|
| MCP-only | `NexusOperatorSessionIT` (17), `AgentMcpManagementGuardIT` (9): існуючі login/guard/CRUD/error contracts; full suite також зберігає HTTPS-origin regression |
| RA-only | `RemoteAccessOperatorHttpIT` (2), `RemoteAccessManagementHttpIT` (2): чинні login/service guards через реальний Tomcat |
| Combined Nexus ForgeIT | `NexusCombinedOperatorSessionIT` (3): лише RA login і одна MockHttpSession, RA read/mutation, MCP CRUD, Origin/CSRF/session denial та zero typed upstream calls; existing endpoint descriptors/managers і WireMock |
| Combined Nexus Tomcat | `NexusCombinedOperatorHttpIT` (4): context-wide cookie/реальний CookieManager, distinct outbound service credentials, rotation/logout, aliases і public-to-protected FORWARD/INCLUDE/ASYNC без upstream |
| Combined Agent Tomcat | `AgentCombinedManagementGuardIT` (4): disposable PostgreSQL, MCP CRUD, RA read та cancellation controller reachability, cross-audience denial/aliases, FORWARD/INCLUDE/ASYNC без target invocation, ERROR збереження первинного404 |
| Configuration / runtime wiring | optional consistent MCP aliases; conflicting aliases/рівні credential values відхиляються; Agent verifier отримує четвертий active RA secret path лише за активної feature |

**Auth semantics:** Agent RA routes перевіряє лише зареєстрований RA service guard; решту protected management — MCP guard. Guards повторно визначають target на dispatch, duplicate Authorization відхиляється. На Nexus combined mode використовує тільки чинний RA `/operator/login`, `FORGE_REMOTE_OPERATOR` cookie на context root та `X-CSRF-TOKEN`; окремий MCP session backend/controller не створюється. Canonical bootstrap/origin — RA settings; операторський secret не потрібно дублювати. MCP-only зберігає `FG_SESSION`/`X-Forge-CSRF`; RA-only зберігає scoped cookie. MCP TTL застосовується лише MCP-only; combined використовує чинні RA15 хвилин. Два service credentials та operator bootstrap мають різні значення. Див. mode-specific [runbook](stage-1-operations.md#combined-remote-access-and-mcp-management).

**Error semantics:** adapter виконує map → execute → map. Transport executor зберігає status/code/message/optional correlationId валідного error envelope; actual Agent forced500 `MCP_OPERATION_FAILED` перевірено окремим Agent IT, Nexus fixture має той самий envelope. Nexus HTTP tests зберігають500,400,404,409,422 та поля. Malformed/extra/trailing/duplicate/wrong-type error JSON →502 `UPSTREAM_INVALID_RESPONSE`; unavailable transport →503 `UPSTREAM_UNAVAILABLE`. Локальна validation →400 `INVALID_REQUEST` у feature shape. Raw body/headers/causes не переходять у domain exception чи публічну відповідь; log/response/exception canaries перевірені. Додатковий scan двох full-run logs для17 відомих synthetic canaries дав0 matches. Валідний typed upstream message передається за контрактом; це не універсальний детектор довільного secret у довіреному message. Legitimate Agent messages статичні. Nexus503 IT використовує disabled client; транспортний ResourceAccessException покритий unit test.

**Межі:** combined HTTP tests mock RuntimeBoundaryVerifier. Це доказ auth/wiring, не OS isolation активних Nexus secrets. Реальна Agent RA mutation використовує відсутній synthetic invitation і підтверджує typed404 після controller; Nexus mutation використовує typed upstream fixtures. Повний remote SSH lifecycle/provisioning у цьому task не виконувався. Усі файли/credentials synthetic/disposable, без production secrets або personal Codex config; sandbox network не відкривався.

Незалежні scoped code audits: errors — ACCEPT; auth — ACCEPT. Фінальний cross-cutting audit також ACCEPT (spec/quality/evidence), required findings немає; аудитор окремо звірив fresh XML totals і per-file hashes. Повний висновок збережено у [review](stage-1-review.md#фінальний-незалежний-audit-only-pr148-auth--errors). Це code review, не PR acceptance чи merge readiness. Error defect RED: очікуваний500 був502; malformed JSON та raw decoder exception також мали behavior RED→GREEN. Auth початковий RED був compilation failure нового constructor/wiring, **не** behavior RED; це не приховується.

## Реалізовані контракти

- Installation-owned global MCP connection, чинний `ForgeInstanceIdentityRepository`, forward-only V38, atomic metadata/credential mutation з row lock та узгодженим aggregate read.
- AES-256-GCM, random nonce, AAD installation+connection+purpose; active/old key IDs і контрольований reencrypt. KEEP/REPLACE/REMOVE, metadata-only reads. Нові connections disabled, tool allowlist порожня. ALL/SELECTED явні; empty SELECTED та невідомий/видалений project не дозволяють invocation.
- Typed Agent `/api/v1/integrations/mcp/connections` і Nexus `/api/v1/infrastructure/agents/integrations/mcp/connections`: list/get/create/update/enabled/delete/reencrypt. Окремі API/client/domain DTO та mapping. STREAMABLE_HTTP — єдиний підтримуваний transport; зовнішніх MCP calls немає.
- Protected file-only key/service/bootstrap/DB credentials. Nexus operator session, Host/Origin, CSRF, bounded TTL; окремий Agent service bearer для REST/SSE. Enabled control APIs guarded; default-off нові routes відсутні. Retained credentials або configured secret paths забороняють global downgrade до незахищеного режиму.
- Dedicated runtime UID для чинного Codex app-server і local Git, clean environment, protected control files/proc, owned systemd/cgroup cleanup. Local Compose parsing у enabled mode відхиляється до Docker CLI. Жодного нового runtime/framework/microservice.

## Історична автоматична перевірка на Stage5 base a102de5c

| Перевірка | Результат |
|---|---|
| Повний Agent verify | 1257 tests, failures0/errors0/skipped9, exit0/BUILD SUCCESS |
| Повний Nexus verify | 306 tests, failures0/errors0/skipped0, exit0/BUILD SUCCESS |
| Runtime helper Python | 11/11 PASS |
| Disposable fixture safety Python | 14/14 PASS |
| `git diff --check` | PASS на frozen source |

```sh
mvn -o -B -Dapi.version=1.44 -f services/forge-agent/pom.xml verify
mvn -o -B -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py'
python3 -m unittest discover -s docs/mcp-integrations/probes/stage1-boundary/tests -p 'test_*.py'
git diff --check
```

Agent skips: вісім opt-in live Codex checks і один RemoteAccessLiveExecution check; їхні системні properties не задані. Це **NOT_VERIFIED live checks**, а не PASS. Точні назви/reasons та hash product diff — у verification JSON. Maven використовував disposable ForgeIT PostgreSQL/WireMock; Docker29 API pin1.44 не змінював особистий Testcontainers config. Логи виконання: `/tmp/forge-mcp-stage1-ledger/final-agent-verify.log`, `final-nexus-verify.log`.

Security/CRUD proof включає local denial із zero typed adapter calls; service bearer та credential outbound body; malformed nested credential, UUID/transport/tool approval rejection; KEEP/REPLACE/REMOVE; GET після update/enable, 404 після delete; HTTP failed reencrypt із missing/wrong old key та незмінним ciphertext. Перевірено redacted toString, inbound accidental serialization, позитивну outbound serialization, safe exception graph/stack trace та captured application/framework logs із synthetic bearer/header canaries. Довільне стороннє raw wire/body logging не вмикалося і не є покритим контрактом.

## Фактичний runtime proof

**PASS18 assertions, exit0, TASK2A_BOUNDARY_PASS, CLEANUP_PASS**: [raw result із hashes](probes/stage1-boundary/result.txt). Reviewed diagnostic helper copy запустив справжній Codex0.156.1 із pinned штатним bwrap. Native command виконав permitted workspace write у workspaceWrite з explicit `networkAccess=false`.

Підтверджено: runtime UID34 не читає чотири synthetic control files і control `/proc` aliases; clean env; malformed/wrong caller/runtime helper invocation/symlink cwd rejection; Git fsmonitor під runtime UID; cleanup double-fork/setsid descendant; blocked-stdin cleanup; sibling unit залишається живим; Agent SIGKILL зупиняє owned runtime. Fixture створювала тільки random `/run/forge-stage1-UUID` і owned transient units, без permanent users/groups/services/sudoers, production secrets чи personal Codex home.

Proof використовує logging-instrumented helper copy. Independent parity review підтвердив еквівалентність packaged fixture, крім reviewed logging/source selection; packaged fixture повторно з root не запускався. Поточний product helper SHA256 `b86094c047153eb0fff0a9267afa9e72b2820ef190113a31a205ece3a13f5530`, fixture `e330d2d98145a1400116849de5578b5c23b138ede2ab3a790faccc73df9f048f`.

Попередні невдалі attempts не враховуються як PASS: зайнятий nobody UID; symlink `/usr/bin/env`; відсутній dedicated `.codex`; відсутній bundled bwrap; `ProcSubset=pid` приховував необхідний `/proc/sys/kernel/overflowuid`. Після reviewed corrections збережено ProtectProc=invisible та всі UID/cgroup/capability controls і повторено negatives. Cleanup підтверджено. Backup UID34 використано лише для disposable workload; це не рекомендація production account.

Stage0 перевіряв Codex0.155.1; його protocol/fresh/resume evidence збережене окремо у [Stage0 evidence](stage-0-evidence.md). Stage1 proof не переносить автоматично ту matrix на0.156.1. Окремий socket/reachability canary на0.156.1 — **NOT_VERIFIED**; sandbox network не відкривався.

## Історичний review до PR #148 corrections

Task1 ACCEPT після row-lock/update-only/snapshot fixes. Task2a ACCEPT після transport/recovery owned-cleanup fixes, фактичного disposable proof і artifact parity. Task2b ACCEPT після mixed-case HTTPS Secure-cookie regression. Task3 ACCEPT після чотирьох corrections: credential envelope parity, safe client exceptions без raw upstream body/header/cause, log/serialization proof, повна CRUD/negative-rotation vertical. Focused final Task3: Agent3unit+9ForgeIT, Nexus7unit+12ForgeIT, scheduler1, усе green.

При першому full Agent run старий isolated scheduler fixture не мав нового guard bean. Fixture виправлено без послаблення production guard; фінальний повний run вище пройшов. Після rebase додано guard-before-channel/recovery ordering для Stage5 early startup; actual context RED4/1failure → GREEN guard4+scheduler1. Final whole-change audit: **ACCEPT для frozen a102de5c scope**, без нових блокерів у ньому; latest-main Stage6 integration не прийнята. Див. [review](stage-1-review.md).

Task3 частково написано до тестів; strict test-first sequencing не виконано. Actual defect RED/GREEN зафіксовано чесно; tests, які одразу були green, не названі RED за filename.

## Мінімальні operational prerequisites та NOT_VERIFIED

Повний порядок у runbook: окремі control/runtime accounts; trusted root-owned helper/config/executables та bundled bwrap; вузький sudo route; dedicated runtime HOME/.codex; shared workspace ownership/modes; protected key/service/DB та Nexus bootstrap/service files; exact operator origin і HTTPS або explicit loopback HTTP. Enabled startup виконує реальний runtime verifier для фактичних protected paths; `verified=true` bypass немає.

AES key source перечитує protected key file на кожну cipher operation; bootstrap/service/DB loaded credentials потребують restart для застосування. Після provisioning рекомендовано controlled restart для повторного startup proof. Pause — connection.enabled=false зі global flag=true. Deprovision/rollback потребує усунення retained credentials і protected paths; unknown orphan files/інша DB/старий binary guard не знаходить.

**NOT_VERIFIED:** installed sudoers caller routing; full production Java deployment; TLS rollout; provider login/history migration; reboot/power-loss recovery; окремий0.156.1 network-denial canary; runtime denial фактичних active Nexus secret paths та process/fd aliases. Жоден із цих пунктів не позначений PASS. Stage2 outbound policy/discovery, gateway, OAuth, UI та каталог не реалізовувалися.
