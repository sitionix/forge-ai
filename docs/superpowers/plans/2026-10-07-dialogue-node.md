# Dialogue Node Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Додати збережений багатораундовий діалог з агентом у workflow, який завершується лише явним вибором користувача на актуальній версії підсумку.

**Architecture:** Forge Agent володіє Dialogue lifecycle, історією, revisions та completion. Використовуються чинні provider client, execution sessions/turns, lease/fencing, MCP gateway та completion processor. Nexus проксіює типізовані контракти; Console додає редактор ноди й панель переписки.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, наявний Codex app-server client, JavaScript Console, Vitest, JUnit та Forge IT.

**Spec:** [2026-10-07-dialogue-node-design.md](../specs/2026-10-07-dialogue-node-design.md).

## Global Constraints

- Працювати в `feature/SITIONIX-157` у поточному checkout; не створювати worktree.
- Повторно використовувати provider client, session/turn infrastructure, MCP gateway і політику доступу, activity, workspace resolver, completion routing та компоненти Console.
- Не будувати другий executor, окремий клієнт Notion або нову систему дозволів.
- Dialogue має тільки GLOBAL scope і власну сесію на конкретний NodeRun.
- Dialogue output dispositions: ACCEPT, REWORK, DEFER; рівно один ACCEPT, не більше одного кожного іншого.
- INITIAL, CHAT, SUMMARY — внутрішні ходи; вони не збільшують кількість graph NodeRuns.
- `max-dialogue-turns-per-node-run` має початкове значення 100; повідомлення — 1–16 000 Unicode code points, whitespace-only заборонений.
- API requestId — UUID, expectedRevision — обов'язковий optimistic concurrency token.
- WAITING_FOR_DIALOGUE є активним станом; очікування людини не тримає provider call або lease.
- ACCEPT стосується лише актуальної SUMMARY revision із non-null валідним draft, readyForReview=true і без blocking questions.
- Notion board sync, спеціальні нові інструменти, attachments і streaming поза scope.
- Не змінювати налаштування доступу існуючих MCP connections під час реалізації або тестів.
- Unit/IT використовують deterministic provider; live сценарій запускається в окремому тестовому проєкті без зовнішніх записів.

## Review Focus

1. Browser загубив відповідь на успішний POST: повтор requestId не дублює повідомлення чи provider turn, навіть зі старою expectedRevision — Task 3.
2. Інша вкладка надіслала уточнення після показу summary: ACCEPT старої ревізії відхиляється — Tasks 3, 7.
3. Дозволи MCP змінилися між репліками: новий хід не використовує старий grant або збережений список tools — Task 4.
4. Source node повторно активується у циклі графа: новий NodeRun має окрему conversation й transcript — Tasks 2, 8.
5. Сервер перезапустився після completion commit до routing: exact обрана гілка активується один раз — Tasks 5, 8.

## File Structure

Нові вузькі класи зосередити у пакетах `domain/model/dialogue`, `domain/port/dialogue`, `application/dialogue`, `api/dialogue` Forge Agent та відповідних agentproxy шарах Nexus. Provider conversation не переносити до цих пакетів: вона лишається у чинному infrastructure/codex та execution session storage.

Основні нові файли:

- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/dialogue/DialogueSnapshot.java`: збережений authoritative state.
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/dialogue/DialogueRepository.java`: збереження й блокування aggregate.
- `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/dialogue/DialogueCommands.java`: читання, send, summary, complete.
- `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/dialogue/DialogueTurnWorker.java`: claim queued turn та використання спільного execution runtime.
- `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/dialogue/DialogueCompletionPolicy.java`: перевірка й фіксація користувацького завершення.
- `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresDialogueRepository.java`: transactional aggregate storage.
- `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/dialogue/DialogueController.java`: typed HTTP contracts.
- `services/forge-console/src/operator/dialogue-view.js`: chat UI та команди, без lifecycle логіки на клієнті.

Наявні великі файли змінювати для делегування окремим Dialogue класам; не вкладати весь чат у TaskExecutionView або CodexAgentExecutor.

## Task 1: Нода, порти та snapshot

**Files:**
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/NodeType.java`, `NodeRunStatus.java`, `NodeContextMode.java`, `Node.java`, `NodePort.java`, `RunPort.java`.
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/graph/WorkflowGraphValidator.java`.
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/WorkflowRunSnapshotBuilder.java`, `NodeRunFactory.java`.
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/ForgeAgentApiMapper.java` і відповідні node/port DTO.
- Modify: workflow/node/port entities і mappers в `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/`.
- Create: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/dialogue/DialogueOutputDisposition.java`.
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/graph/WorkflowGraphValidatorTest.java`.
- Create test: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentDialogueFoundationIT.java`.

**Interfaces:** Produces NodeType.DIALOGUE, NodeRunStatus.WAITING_FOR_DIALOGUE, internal NodeContextMode.DIALOGUE_WITHIN_NODE_RUN, nullable `dialogueDisposition` на NodePort/RunPort. Старі constructors зберігають ordinary defaults. Dialogue validation має один ACCEPT, GLOBAL scope, target того самого проєкту; mode DIALOGUE не дозволений ordinary nodes.

- [x] Написати failing tests `dialogueRequiresGlobalAgentAndExactlyOneAccept`, `dialogueDispositionRoundTripsThroughSnapshot`, `waitingDialoguePreventsWorkflowCompletion`; перевірити також MANUAL target prohibition та ordinary null disposition.
- [x] Запустити `mvn -pl services/forge-agent/application -am -Dtest=WorkflowGraphValidatorTest,QuiescenceWorkflowCompletionPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`; зафіксувати failure через відсутню Dialogue поведінку.
- [x] Додати типізовані поля й snapshot propagation; pending Dialogue не алокує ordinary one-turn execution у NodeRunFactory.
- [x] Додати міграцію V44 за актуальним номером: persisted enums/constraints, nullable port disposition та dialogue context mode. Старі міграції не редагувати.
- [x] Прогнати foundation IT: `mvn -pl services/forge-agent/boot -am -Dtest=WorkflowGraphValidatorTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ForgeAgentDialogueFoundationIT -Dfailsafe.failIfNoSpecifiedTests=false verify`.
- [x] Закомітити лише foundation зміни після green.

## Task 2: Dialogue storage і точна прив'язка execution turns

**Files:**
- Create: DialogueSnapshot, DialogueMessage, DialogueTurn, DialogueRevision та DialogueCompletion у `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/dialogue/`.
- Create: DialogueRepository і PostgresDialogueRepository за File Structure.
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/AgentExecutionSessionRepository.java`.
- Modify: `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresAgentExecutionSessionRepository.java`.
- Create migration: `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/V45__add_dialogue_storage_and_execution_binding.sql` (номер актуалізувати, якщо з'явилася інша міграція).
- Create test: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentDialogueStorageIT.java`.

**Interfaces:** `DialogueRepository.find(UUID nodeRunId) -> Optional<DialogueSnapshot>`, `lock(UUID nodeRunId) -> DialogueSnapshot`; aggregate writes лише у owning workflow/node transaction. Execution port: `allocateDialogueTurn(NodeRun node, UUID dialogueTurnId, String providerId) -> AgentExecutionAllocation`, `findByExecutionTurnId(UUID turnId) -> Optional<AgentExecutionAllocation>`, `acquireTurn(UUID turnId, String ownerId) -> Optional<AgentSessionExecutionClaim>`. Ordinary find/acquire за NodeRun explicitly exclude dialogue turns.

- [ ] Написати failing IT `multipleDialogueTurnsShareOneInvocationSession`, `ordinaryTurnRemainsUnique`, `dialogueBindingRejectsWrongOwner`, `reactivatedNodeHasIndependentConversation`.
- [ ] Запустити `mvn -pl services/forge-agent/boot -am -DskipTests package`, потім focused IT без skipTests; failure має доводити відсутній storage/binding, не незапущений PostgreSQL.
- [ ] Додати aggregate storage з append-only message sequence, immutable revisions, command IDs/payload fingerprints і unique queued/active DialogueTurn.
- [ ] Замінити ordinary UNIQUE node_run_id частковим index; додати exact dialogue_turn_id FK/uniqueness і session ownership checks. Зберегти single active writer та session sequence constraints.
- [ ] Оновити ordinary lookups/claim; усунути неоднозначний findFirst для multiple-turn NodeRun. Dialogue session keyed конкретним invocation.
- [ ] Запустити `mvn -pl services/forge-agent/boot -am -DskipTests=false -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ForgeAgentDialogueStorageIT,ForgeAgentManualFlowIT -Dfailsafe.failIfNoSpecifiedTests=false verify`; перевірити всі IT результати.
- [ ] Закомітити storage/binding після green.

## Task 3: Команди переписки й ревізії

**Files:**
- Create: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/dialogue/DialogueCommands.java`, `DialogueCommandValidation.java`, `DialogueProperties.java`.
- Create test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/dialogue/DialogueCommandValidationTest.java`.
- Create IT: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentDialogueCommandsIT.java`.

**Interfaces:** `get(UUID runId, UUID nodeRunId) -> DialogueSnapshot`; `messages(UUID runId, UUID nodeRunId, long afterSequence, int limit) -> DialogueMessagePage`; `send(UUID runId, UUID nodeRunId, UUID requestId, long expectedRevision, String text) -> DialogueSnapshot`; `summarize(UUID runId, UUID nodeRunId, UUID requestId, long expectedRevision) -> DialogueSnapshot`. `initialize(UUID nodeRunId)` створює exact INITIAL turn один раз. Виходи send/summary — вже committed authoritative snapshot.

- [ ] Написати failing tests: whitespace-only, 16 000/16 001 code points включно з emoji, preserved newlines, max turn budget 100.
- [ ] Написати failing IT: lost-response retry зі старою revision; same ID/different payload conflict; concurrent send/summary; USER message і queued turn зберігаються разом; нове повідомлення інвалідує summary.
- [ ] Запустити focused application unit + CommandsIT за Maven командами Tasks 1/2 зі зміненими test names; прочитати RED.
- [ ] Реалізувати validation, command fingerprint, lock order workflow → node → dialogue → session/turn; dedup виконується перед revision check.
- [ ] Додати INITIAL dispatch через Dialogue lifecycle delegation з NodeRunWorker. У WAITING state не залишати lease або pending provider process.
- [ ] Прогнати unit/IT до green і закомітити.

## Task 4: Багатораундовий запуск через наявний Codex і MCP

**Files:**
- Create: DialogueTurnWorker, DialogueTurnLifecycle і DialogueTurnResultPolicy в `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/dialogue/`.
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutor.java`, `NodeExecutionClaim.java`, `AgentSessionLeaseService.java`.
- Modify: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexAgentExecutor.java`; extract shared execution helper у цьому самому пакеті.
- Create: `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/DialogueExecutionDeveloperInstructions.java`.
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/mcp/McpExecutionSelectionService.java`.
- Create tests: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/dialogue/DialogueTurnResultPolicyTest.java`, `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexDialogueExecutionTest.java`.

**Interfaces:** `DialogueExecutionRequest` містить exact workflow/node/execution turn IDs, session claim, workspace, snapshot agent/model/schema, turn kind, input revision та triggering message. Спільний provider execution helper приймає typed request/contract, повертає provider output; DialogueTurnResultPolicy перевіряє message/draft/questions/decisions/sources/readyForReview. Звичайний AgentExecutor.execute(NodeExecutionClaim) зберігає свій контракт. MCP selection отримує minimal trusted execution context замість дубльованих перевірок.

- [ ] Написати failing tests: initial start → chat resume → summary resume на exact conversation; ordinary request не змінений; dialogue instructions не наказують обрати порт.
- [ ] Написати failing result tests: SUMMARY null/invalid draft; ready with blocking questions; неіснуючий decision message ID; shape invalid output не створює assistant reply.
- [ ] Написати failing MCP tests: project/tool access, новий grant на turn, changed permissions між turns, revoke після result/error/stop, відсутність grant між репліками.
- [ ] Запустити focused application/codex tests і зафіксувати RED.
- [ ] Виділити спільні transport/session/MCP execution операції з чинного CodexAgentExecutor; реалізувати Dialogue request policy через них. Не копіювати executor body.
- [ ] Реалізувати committed turn result → assistant message/revision/wait state atomically; claim та callbacks fenced exact turn/lease.
- [ ] Прогнати existing Codex tests і нові Dialogue tests, закомітити green.

## Task 5: Completion, cancellation, recovery та activity

**Files:**
- Create: DialogueCompletionPolicy, DialogueRecoveryHandler у `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/dialogue/`.
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/CancelWorkflowRunUseCase.java`, `ResetAgentExecutionContextUseCase.java`, `AgentExecutionContextUseCases.java`.
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentExecutionRecoveryService.java`, `NodeRunCompletionProcessor.java` та existing routing policy лише для explicit selected Dialogue output.
- Create tests: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/dialogue/DialogueCompletionPolicyTest.java`.
- Create IT: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentDialogueLifecycleIT.java`.

**Interfaces:** `DialogueCommands.complete(UUID runId, UUID nodeRunId, UUID requestId, long expectedRevision, UUID summaryRevisionId, UUID outputPortId) -> DialogueSnapshot`; exact schema envelope `{contractVersion:1,result,dialogue}`. Accepted completion closes session and makes NodeRun SUCCEEDED in one transaction; existing completion worker routes after commit. DialogueRecoveryHandler applies conservative recovery to dialogue turns without ordinary NodeRun completion.

- [ ] Написати failing tests на ACCEPT без current SUMMARY/ready/draft, blocking questions, wrong port; REWORK/DEFER з unresolved questions дозволені з current valid summary.
- [ ] Написати failing IT на duplicate completion, send vs complete, callback after stop, expiry fencing, active-turn restart, waiting restart, completion-before-routing restart.
- [ ] Запустити RED, реалізувати exact completion checks, immutable accepted revision, ordinary recoverable routing і session closure.
- [ ] Додати cancellation/recovery dispatch за owning node type; open Dialogue context reset відхиляти явно. Activity отримувати за explicit execution turn ID.
- [ ] Прогнати DialogueLifecycleIT та існуючі manual/cancellation/context/recovery tests; перевірити zero duplicate downstream activations.
- [ ] Закомітити green.

## Task 6: Agent API й типізоване проксі Nexus

**Files:**
- Create: DialogueController та typed requests/responses в `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/dialogue/`.
- Modify: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/agentproxy/` для Dialogue controller/mapper та node/port contracts.
- Create: Dialogue query/command models і use-case interfaces в `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/model/agentproxy/` та `domain/usecase/`.
- Create: use-case proxies в `services/forge-nexus/application/src/main/java/com/sitionix/forgeai/application/usecase/agentproxy/`.
- Modify: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/port/ForgeAgentClient.java`.
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/ForgeAgentClientAdapter.java`, `ForgeAgentHttpClient.java`, `ForgeAgentClientMapper.java`; create typed Dialogue DTOs in `dto/`.
- Create IT: `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/NexusDialogueIT.java`.

**Interfaces:** GET dialogue, GET cursor messages, POST messages/summary/complete за точними URI специфікації. 202 accepted queued command; 200 completion/dedup; typed conflicts preserve codes. Nexus exposes ці маршрути через наявний infrastructure API root; Console не викликає Agent напряму.

- [ ] Написати failing Agent controller tests на всі маршрути, validation, ownership, status/conflict codes і Unicode text.
- [ ] Написати failing Nexus contract IT із WireMock upstream: передача IDs/revision/text/disposition/schema та errors; незмінність typed JSON і URL encoding.
- [ ] Запустити focused RED; реалізувати Agent DTO/controller та всі чинні Nexus proxy layers, без dialogue lifecycle у Nexus.
- [ ] Прогнати `mvn -pl services/forge-nexus/boot -am -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=NexusDialogueIT -Dfailsafe.failIfNoSpecifiedTests=false verify` та Agent API focused tests.
- [ ] Закомітити green.

## Task 7: Builder і Console chat

**Files:**
- Create: `services/forge-console/src/operator/dialogue-view.js`.
- Modify: `services/forge-console/src/operator/workflow-builder.js`, `task-execution-view.js`, `agent-projects-api.js`, `operator-ui.css`.
- Create tests: `services/forge-console/tests/dialogue-view.test.ts`, `dialogue-workflow-builder.test.ts`, `dialogue-api.test.ts`.
- Modify existing tests: `services/forge-console/tests/task-execution-view.test.ts`, `manual-task-execution.test.ts` лише для нових integration branches.

**Interfaces:** DialogueView uses AgentProjectsApi.getDialogue/listDialogueMessages/sendDialogueMessage/summarizeDialogue/completeDialogue. Клієнтські POST створюють UUID requestId; retry того самого payload повторно використовує його. UI використовує authoritative revision і summaryRevisionId, ніколи локально не оголошує прийняття.

- [ ] Написати failing UI tests: palette/preset, GLOBAL/target/dispositions, round-trip builder snapshot.
- [ ] Написати failing chat tests: persisted history pagination, multiline/keyboard send, queued state, summary render, new-message stale acceptance, exact complete revision, retry без дублювання, historical read-only.
- [ ] Написати failing safety/accessibility tests: escaped HTML/URLs, long text, focus, accessible labels, дві вкладки з conflict response.
- [ ] Запустити `npm --prefix services/forge-console test -- tests/dialogue-view.test.ts tests/dialogue-workflow-builder.test.ts tests/dialogue-api.test.ts`; прочитати RED.
- [ ] Реалізувати окрему DialogueView та делегування з TaskExecutionView; polling не породжує provider turns, off-page reply зберігається сервером.
- [ ] Запустити `npm --prefix services/forge-console test`, `npm --prefix services/forge-console run typecheck`, `npm --prefix services/forge-console run build`; переглянути chat на широкому/вузькому екрані доступним browser tool.
- [ ] Закомітити green.

## Task 8: Наскрізна перевірка й документація результатів

**Files:**
- Create IT: `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentDialogueFlowIT.java`.
- Create: `docs/dialogue-node-operations.md`, `docs/dialogue-node-verification.md`.
- Update: поточний plan checkboxes фактичними результатами.

**Interfaces:** Реальний persisted graph upstream → Dialogue → reviewer; deterministic executor тільки на provider boundary. Live verification окремо доводить native conversation resume, інструменти й exact downstream payload.

- [ ] Написати failing E2E IT на два CHAT turns, summary invalidation, ACCEPT exact revision, REWORK/DEFER, loop reactivation isolation, restart і cancellation.
- [ ] Прогнати RED; виправлення робити в owning компоненті з regression test, не в test fixture.
- [ ] Запустити focused E2E IT, потім `scripts/test.sh`, `scripts/typecheck.sh`, `scripts/lint.sh`; перевірити звіти й явно записати будь-які unrelated failures.
- [ ] Зібрати й запустити проєкт штатним `just start`; якщо потрібна sudo authentication, дати користувачу ввести пароль у локальному терміналі.
- [ ] У тестовому проєкті запустити live INITIAL → CHAT → CHAT → SUMMARY → ACCEPT; перевірити conversation ID незмінний, turn IDs різні, transcript збережений і reviewer отримує exact accepted result. Не вмикати нових MCP permissions заради smoke test.
- [ ] Перевірити migration старої БД на disposable fixture; production data не використовувати для destructive tests.
- [ ] Записати фактичні test counts/commands/live evidence і обмеження у verification doc. Провести whole-branch review до оголошення завершення; PR не створювати без окремого запиту.

## Plan Self-Review

- Tasks 1–2 покривають persisted model, validation, snapshot і many-turn binding.
- Tasks 3–5 покривають усі state transitions, idempotency, concurrency, completion, recovery та бюджети.
- Task 4 використовує чинні інструменти й перевірки доступу; не створює другий executor.
- Tasks 6–7 покривають Agent/Nexus contracts, chat UX, builder, accessibility і stale UI.
- Task 8 покриває реальний graph, upgrade, регресії та відмінність mock/live verification.
- Усі п'ять Review Focus сценаріїв мають owning tests.
- Метод виконання рекомендується Native: tasks послідовно змінюють спільні session/turn та API контракти. До реалізації потрібен перегляд цього плану; якщо обрано Subagent-driven, делегування виконується лише після такого вибору користувачем.
