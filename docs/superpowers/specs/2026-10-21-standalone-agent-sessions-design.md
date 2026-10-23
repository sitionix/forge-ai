# Standalone Agent Sessions — архітектурний дизайн

Дата: 2026-10-21. Статус: проєкт для перевірки перед реалізацією.
База аудиту: `02689707` (`feature/SITIONIX-166`), нова звичайна гілка
`feature/SITIONIX-167`. Номер отримано з локальних гілок та поточного
`git ls-remote --heads origin 'feature/SITIONIX-*'`; найбільший — 166.
Сторонній незакомічений `tools/` не змінено. PR/push/merge не виконуються.

## 1. Мета та незмінні обмеження

Користувач запускає наявного Forge або native Codex агента з його власними
інструкціями та моделлю, отримує довговічну розмову, історію й реальні події
та продовжує той самий provider thread після повторного відкриття UI.
Обраний агент є root thread; жодного прихованого Workflow, NodeRun чи
батьківського Forge-агента. Дозволені native subagents створює сам Codex.

Workflow і Standalone мають різних власників виконання та окрему
персистентність, але один provider invocation path. Чинні Workflow/Dialogue
контракти, історичні snapshots, sandbox, runtime UID та MCP policy зберігаються.
Немає нової Skills inventory, довільних шляхів із браузера чи нового JSON-RPC
клієнта. Зміна глобального LLM profile не змінює Forge agent model.

## 2. Перевірений стан репозиторію

| Компонент | Фактичний стан | Рішення |
| --- | --- | --- |
| `AgentDefinition`, `AgentModelSelection` | Instructions, output schema, provider/model/effort, timestamps уже персистентні | Використати наявне визначення; зберегти власну модель агента |
| `CodexAgentExecutor` | Приймає `NodeExecutionClaim`; готує Workflow/Dialogue input, output schema, routing envelope, MCP, lease callbacks | Залишити owner-specific input/output orchestration; provider виклик спрямувати в спільний port |
| `CodexClient`, `CodexAppServerClient` | Існують fresh/durable виконання, lifecycle, transport, authorization, cancellation callbacks | Використати як єдину нижню реалізацію; розширити тип запиту для chat contract |
| `CodexSessionProtocol` | Уже ставить `ephemeral=false`, перевіряє тотожність resumed thread ID | Повторно використати; заборонити fallback на новий thread |
| Durable version gate | Дозволяє 0.157.0, 0.160.0, 0.160.1; локальний CLI — 0.163.0 | Нову версію дозволити лише після protocol/live characterization |
| `turnStartParams` | Безумовний `request.outputSchema().deepCopy()` | Для plain-text contract schema відсутня; JSON envelope залишається лише у Workflow |
| `codexConfig()` | `agents.enabled=false`; інші обмеження конфігурації | Native multiagent вмикати лише для перевіреного standalone capability contract |
| `AgentSessionLeaseService`, event recorder | Claim містить Workflow/Node identity, repository прив'язаний до workflow storage | Витягнути вузьку fenced ownership boundary; окремі owner adapters |
| Migrations V26/V27/V31/V45/V46 | Суворі Workflow/Node FK, scope/lifecycle triggers, Dialogue owner checks | Не послаблювати та не переписувати; додати standalone storage |
| `ExecutionWorkspaceResolver` | Уже має overload `(projectId, repositoryId, repositoryIds)` | Повторно використати project/repository validation; прибрати лише workflow-specific назви у спільному overload |
| MCP selection/gateway/grant | Selection завантажує Workflow; gateway перевіряє Node та allocation; `McpRuntimeGrant` вимагає Workflow/Node IDs; grants зберігаються у bounded `InMemoryMcpRuntimeGrantRepository` | Ввести типізований execution owner та спільну policy; не підставляти UUID |
| Native agent inventory | `CodexProjectAgentInventory` + `project_agent_inventory.py`, bounded TOML, nofollow fd traversal, opaque identity hash | Повторно використати безпечне читання; resolver повертає приватне повне визначення |
| Остання AI Library | Global + project resources; global sources лише за адміністративним opt-in | Зберегти ці межі й provenance; жодного копіювання personal HOME |
| Dialogue | Окремі revision/requestId/async patterns, але owner — workflow node | Використати контрактні patterns; не перетворювати Dialogue на standalone |
| Nexus | Typed controllers/mappers/usecases/domain clients/call executor/HTTP client | Додати аналогічний вузький session client port |
| Console | AI Library flat list, scoped CSS, toolbar/search, detail routes; окремий router | Використати чинні елементи дизайну та router |

Джерела — актуальні файли перелічених класів і
`docs/ai-library-runtime-evidence.md`, включно з розділом
«Global + project native resources», який замінює старі project-only висновки.
Попередні PASS у цьому evidence не є перевіркою цієї нової функції.

## 3. Варіанти та вибір

1. **Мінімальний shared execution port + окремі owner repositories** — рекомендовано.
   Зберігає наявний Codex transport/lifecycle, переносить лише provider invocation
   та вузькі ownership/fencing hooks. Потребує явних standalone SQL constraints.
2. Спільна універсальна session persistence для всіх owners. Збільшує міграцію
   чинних Workflow/Dialogue та ризик зміни історичних інваріантів; відхилено.
3. Окремий standalone Codex client. Дублює execution engine і security path;
   відхилено як несумісний із завданням.

## 4. Межі спільного виконання

У domain розміщуються immutable provider-neutral request/result, effective
definition, execution owner/lease identity, output contract та event/callback
interfaces. Немає Jackson, Spring, SQL, Codex JSON-RPC або `Path` у domain.
Application готує trusted workspace через наявні ports і runtime context.
Якщо конкретний workspace потребує application `ExecutionWorkspace`, execution
port живе в application поряд з існуючими runtime ports; domain не залежить від нього.

`AgentTurnExecutionPort.execute(request, events)` і `interrupt(executionId)` —
єдина entry point для Workflow та Standalone. Request містить snapshot,
provider/model/effort, звичайний текст або вже підготовлений Workflow payload,
optional provider conversation/version, FRESH/DURABLE mode, trusted workspace,
approved capabilities, owner identity, dispatch/identity/cancellation callbacks.
Output contract — TEXT або STRUCTURED; provider adapter отримує schema тільки
для другого. Workflow routing parsing залишається поза shared port.

`CodexAgentTurnExecutionAdapter` усередині infrastructure перетворює request
на `CodexTurnRequest` і викликає той самий `CodexClient`. Існуючий
`CodexAgentExecutor` стає Workflow adapter над shared port. У shared adapter
централізуються active execution cancellation registration та спільна обробка
provider failures. `CodexRuntimePort` model discovery не розширюється.

Owner adapters забезпечують persist identity, renew, fenced dispatch та event
append. Shared policy перевіряє typed owner proof; вона не знає Workflow routing.
Не копіювати цілком lease/recovery/event engines: спільні алгоритми працюють
через вузькі contracts, а SQL з реальними FK залишається owner-specific.

## 5. Definition resolution і snapshot

Application `AgentDefinitionResolver` dispatch за FORGE/NATIVE_CODEX reference.
Forge resolver завантажує persisted agent, перевіряє project та використовує
його model/effort. Для нових Workflow snapshots застосовується та сама
effective resolution; історичні snapshots не перераховуються.

Native reference — поточний opaque ID AI Library. Сервер сам повторює trusted
inventory resolution у проєкті та enabled global scope. Browser не надає path.
Спільний bounded reader відкриває кожен компонент через nofollow fd, перевіряє
regular file, hardlink, byte/count/time bounds. Повний parser має versioned
allowlist для name/description/developer_instructions/model/reasoning і лише
доведених capability fields. Невідомі execution-affecting поля, imports,
довільні endpoints, credentials, commands чи розширення permissions — явний
UNSUPPORTED_DEFINITION, без часткового виконання. Python TOML parser/version
перевіряється запуском ізольованого helper; не додавати regex TOML parser.

Native model override зберігається. Коли model не задана, server trusted profile
дозволений лише як явне fallback правило при створенні; його effective model
одразу фіксується і показується read-only. Forge model ніколи не підміняється.

Snapshot — versioned immutable value: source/id/name, source fingerprint,
effective instructions/model/effort, original applicable output constraints,
repository IDs, workspace identity, policy, resource identities/fingerprints
та verified provider contract. У chat output constraints не додають Workflow
routing envelope; непридатна для TEXT вимога дає explicit incompatibility.
Public DTO не повертає інструкції, захищені paths чи config.

Source agent edits не змінюють snapshot. На кожному turn перевіряються project
access, repository availability та актуальні capability revocations.
Execution-affecting зовнішні Skills/agent resources та configuration мають
порівнюваний fingerprint; непідтримуваний fingerprint або зміна ресурсу дає
RESOURCE_CHANGED/COMPATIBILITY_REQUIRED. Не копіювати personal Skills у workspace.
Перевірка має охоплювати також native child definitions і project instructions,
які provider фактично успадковує; поки це не доведено, combination недоступна.

## 6. Aggregate, storage та конкурентність

Domain: `StandaloneAgentSession`, `StandaloneAgentTurn`, `StandaloneAgentMessage`.
Session містить project/reference/snapshot/provider/model/thread/version,
repository/workspace identity, revision/timestamps та independent session status.
Turn містить requestId, executionId, providerTurnId, state, failure/recovery state,
leaseOwner/token/deadline та dispatch state.

Нові таблиці: standalone_agent_sessions, standalone_agent_turns,
standalone_agent_messages, standalone_agent_events. Session має project FK;
turn/message/event мають composite owner FK, unique sequence та unique requestId
у session scope. Partial unique index забороняє дві nonterminal turns у session.
Provider conversation identity unique у provider scope. Snapshot immutable
через update contract та DB guard. Session revision змінюється атомарно.

Create із requestId, agent reference, repository IDs та initial message — одна
транзакція snapshot/session/user message/queued turn. Повтор ідентичного requestId
повертає попередній receipt; інший payload із тим самим ID — conflict.
Duplicate detection перед revision check. Subsequent send має requestId,
expectedRevision, text. Другий активний turn відхиляється, черга приховано не росте.

Worker claim із DB fencing token; heartbeat 10s/lease 30s відповідно до чинної
політики. Кожен identity/event/finalization запис перевіряє owner/token/deadline.
Persist thread ID до turn dispatch, persist turn ID до залежних дій; ambiguous
dispatch не replay-иться. Messages отримують monotonic per-session sequence.
Terminal assistant message та turn/session transition записуються атомарно.

Session CREATED/READY/RUNNING/AWAITING_USER/FAILED/COMPLETED/CANCELLED відрізняється
від turn QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/RECOVERY_REQUIRED. Успішний turn
повертає session у READY. Звичайна відповідь/уточнення не утримує provider process.
Нормальне продовження доступне після failed/cancelled turn, якщо cleanup і provider
outcome доведені. Uncertain recovery блокує новий turn явним recovery state.
Stop скасовує поточний turn; не видаляє історію або thread.

## 7. MCP, Skills та native subagents

Виділити `ExecutionOwnershipVerifier` з Workflow і Standalone adapters та
спільним project/connection policy. Gateway перевіряє реальне ownership,
активний fenced dispatch, enabled connection, tool allowlist, deadline,
credential identity і revocation на кожній операції. Grant owner — sealed
WorkflowExecution/StandaloneExecution; не nullable fake Workflow fields.
Повторно використати чинний bounded in-memory grant repository: рестарт
інвалідовує всі bearer grants. Не вводити нову credential/grant persistence.
Durable standalone ownership перевіряється через нові session/turn FK storage;
чинні Workflow FK не змінювати.
Немає bearer в public DTO/logs/events. Cleanup/recovery завжди revoke grants.

Skills використовують чинний native resolution та opt-in read-only global mounts.
Native subagents доступні тільки в standalone після live verification точних
0.163.0 controls: enablement, bounded concurrency/depth/lifetime, local/global
custom definition selection та inherited sandbox/MCP. Назви конфігураційних
полів не вважаються доведеними лише зі schema presence. Якщо provider не може
забезпечити межу, combo відхиляється, а не вмикається глобально для Workflow.
Чинний fail-closed handler unsupported native approvals/requestUserInput
зберігається; safe limitation відображається користувачу.

## 8. Runtime та recovery: що вже доведено

Локальний `codex --version` повернув `codex-cli 0.163.0`.
`codex app-server generate-json-schema --experimental` успішно створив schema
у `/tmp/forge-standalone-codex-0163-schema`. Вона містить:

- ThreadStartParams: ephemeral, developerInstructions, model, cwd,
  runtimeWorkspaceRoots, approvalPolicy, sandbox, config, multiAgentMode.
- ThreadResumeParams: threadId, developerInstructions, model, cwd,
  runtimeWorkspaceRoots, config, excludeTurns.
- TurnStartParams: input/threadId обов'язкові; outputSchema необов'язковий.
- ThreadTurnsListParams: threadId, cursor/limit/sortDirection/itemsView.
- `codex features list`: multi_agent stable; multi_agent_v2 stable.

Це schema/CLI evidence, **не PASS live execution**. Ще не доведені root turn,
durable resume, child delegation, actual managed-runtime binary equivalence,
custom-definition inheritance, Skills execution та termination/restart cleanup.

Перед version allowlist update: ізольований реальний start(false)/turn/stop
process/resume/turn/turns-list із тим самим thread ID та trusted workspace,
запис identity callbacks, interruption і cleanup. Якщо inspection не доводить
outcome, RECOVERY_REQUIRED без автоматичного repeat side effects. Existing
recovery protocol повертає terminal classification, але не відновлює assistant
text: додати bounded shared provider inspection для terminal reply; без reply
не фабрикувати історію й не вважати reconciliation повністю завершеним.

## 9. API і UI

Nexus base `/api/v1/infrastructure/agents/projects/{projectId}/agent-sessions`;
Agent використовує чинний власний prefix. POST create; GET bounded list;
GET /{sessionId}; GET/POST /messages; GET /events; POST /cancel.
Create/send повертають 202 receipt із IDs/revision/state, duplicate — 200.
History/event cursors — bounded afterSequence, list — stable keyset
(lastActivityAt,id), default 50/max100. Для messages/events default100/max200.
Публічні помилки: not-found/cross-project, stale revision, busy, unavailable
definition, changed resource, version mismatch, resume failed, recovery required.
Existing exception mapping і service authentication використовуються повторно.
Controllers → mapper → usecase → domain client → adapter → call executor → typed
HTTP client; не універсальний JSON proxy і не Knowledge profile orchestration.

Console: Start Session у native Library details та Forge agent details;
server eligibility/preview показує модель та причину недоступності без виконання.
Компактна форма: agent/project/repositories/initial text/read-only model.
Sessions entry і routes `#/projects/{projectId}/sessions[/{sessionId}]`.
Список із search/status/last activity/pagination. Chat header/status, timeline,
persistent composer, Send/Stop, secondary collapsible activity з actual events.
Bounded cursor polling через Nexus, одна активна request generation,
AbortController при navigation, merge by sequence/turn identity, без duplicate.
Composer DOM не замінюється при polling, unsent text та keyboard focus стабільні.
Live status announcements, доступні назви, 390px без horizontal overflow.
Немає нового UI framework чи глобального redesign dashboard.

## 10. План верифікації та критерій завершення

Implementation plan має впорядкувати: provider characterization → shared port
та Workflow regressions → definition resolver → domain/storage/fencing →
ownership/MCP/recovery → typed API → Console → інтеграційна та live перевірка.

Обов'язкові тести: повний domain/application matrix із user task; PostgreSQL
constraints/races/idempotency/cursors; однаковий root thread на двох turns;
instruction/model/TEXT payload; stale lease/revocation/cross-project denial;
interruption/restart/uncertain dispatch без replay; typed Nexus HTTP/ForgeIT;
Console navigation/focus/mobile; реальні Skills та native subagent smoke.

Після реалізації виконати Agent і Nexus `verify` (для Docker використати чинний
`-Dapi.version=1.44` за потреби), ForgeIT, Console test/typecheck/build,
runtime launcher/security regressions, migration tests, релевантний
`dependency:analyze`, `git diff --check`. Opt-in skipped tests не є PASS smoke.
Окрема незалежна перевірка actual diff перед completion; жодного PR/GitHub edit.
Фінальний звіт — точний matrix із task; runtime/browser NOT_VERIFIED там, де
немає фактичного доказу. Цей дизайн не оголошує реалізацію завершеною.
