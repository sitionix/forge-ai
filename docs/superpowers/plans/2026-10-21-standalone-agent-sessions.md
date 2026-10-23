# Standalone Agent Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended here) or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Запускати наявних Forge/native Codex агентів як root довговічних розмов із проєктним UI, історією, подіями та безпечним продовженням.

**Architecture:** Окремий standalone aggregate та PostgreSQL storage використовують спільний provider-neutral execution port. Workflow зберігає input/output routing і власні FK; обидва owners викликають єдиний Codex client. Ownership adapters забезпечують fencing, identity callbacks, MCP policy та recovery.

**Tech Stack:** Java 21, Spring Boot 3.3.4, PostgreSQL/Flyway, JUnit/Mockito/ForgeIT, чинний Codex app-server adapter, Console JavaScript/TypeScript/Vitest/Vite.

**Spec:** [2026-10-21-standalone-agent-sessions-design.md](../specs/2026-10-21-standalone-agent-sessions-design.md), погоджено користувачем 2026-10-21.

## Global Constraints

- Звичайна гілка `feature/SITIONIX-167`, без worktree; зберегти сторонній `tools/`.
- No hidden Workflow/NodeRun, no parent agent, no duplicate agent definitions or Codex execution engine.
- `CodexRuntimePort` залишається model-discovery interface.
- Workflow migrations V26/V27/V31/V45/V46 та історичні snapshots не переписувати і не послаблювати.
- Локальний Codex CLI — `0.163.0`; чинні durable versions — `0.157.0`, `0.160.0`, `0.160.1`. Нову версію дозволяти лише після перевірки.
- Snapshot immutable; source edits не впливають на існуючі sessions; revoked capabilities перевіряти кожного turn та MCP call.
- Lease 30s, heartbeat 10s; bounded execution concurrency/timeouts; жодного replay uncertain dispatch.
- Native approvals/requestUserInput залишаються fail-closed.
- Global resources — лише чинний admin opt-in та isolated read-only mounts; не копіювати personal Skills.
- API не приймає runtime paths/commands/config locations, не повертає credentials/internal instructions/paths.
- Create містить initial message; create/send idempotent; submit asynchronous.
- Message text: 1..32768 Unicode code points після перевірки blank; UI бере цей limit із launch/session contract, request body bounded сервером.
- List default50/max100; messages/events default100/max200; stable cursors та turn identity.
- Native child agents вимикаються для Workflow; enablement/limits не вигадувати з неперевірених config names.
- UI відповідає чинному AI Library; 390px, focus/draft preservation, Back/Forward, real activity.
- No PR, push, merge або GitHub metadata edits. Локальні commits — лише файли цієї задачі після перевірок.
- Final independent review actual implementation; skipped live tests не PASS.

## Review Focus

- Втрачений HTTP receipt після прийнятого send: повтор з тим самим requestId повертає той самий turn без нового side effect (Tasks 5, 9, 10).
- Cancel одночасно з thread/turn identity callback: owned process зупиняється, пізній callback не оживляє turn і не перезаписує наступний lease (Tasks 2, 6, 7).
- Заміна native definition/resource symlink між discovery та execution: відмова, без зміни зафіксованої семантики (Tasks 3, 8).
- Restart після provider completion, але до assistant persistence: відновити рівно одну відповідь або показати recovery required, без повторного turn (Task 7).
- Зміна проєкту/route під час polling або send: стара відповідь не потрапляє в іншу session, draft/focus поточного composer збережені (Tasks 10, 11).

## File map та спільні типи

Нижче `A=services/forge-agent`, `N=services/forge-nexus`, `C=services/forge-console`.
Для Java шляхи завжди мають `src/main/java/com/sitionix/forgeagent` (Agent) або
`src/main/java/com/sitionix/forgeai` (Nexus); тести — відповідний `src/test/java`.
Наприклад, `A/domain: domain/model/execution/ExecutionOwner.java` означає
`services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/execution/ExecutionOwner.java`.
Ця нотація нижче задає точний шлях без повторення package prefix.

- `domain/model/execution`: provider-neutral definition, owner, lease та output contract values.
- `application/runtime`: shared invocation port/request/callbacks, generic heartbeat; не бізнес-політика чату.
- `application/definition`: єдині Forge/native effective-definition rules.
- `domain/model/standalone`, `domain/port/standalone`: aggregate, transitions, persistence contracts.
- `application/standalone`: command/query use cases, worker lifecycle, recovery, public eligibility preview.
- `infrastructure/codex`: єдиний provider adapter, native TOML resolution, normalized events, capabilities.
- `infrastructure/postgres`: standalone SQL/adapter та shared advisory-lock primitive.
- Nexus `standalone` packages: typed contracts/mappers/forwarding; без execution orchestration.
- Console `agent-sessions-*`: state/controller/rendering/API; чинний project page лише підключає routes.

Контракти tasks 2–8 визначаються перед consumers; нові типи не містять Spring,
Jackson чи infrastructure types у domain. `ExecutionWorkspace` лишається application value.

## Task 1: Characterize встановлений provider contract

**Files:**
- Modify `A/infrastructure/codex: infrastructure/codex/CodexDurableSessionE2ETest.java` (test tree), `CodexNativeLibrarySmokeTest.java` (test tree).
- Create `A/infrastructure/codex: infrastructure/codex/CodexStandaloneRuntimeSmokeTest.java` (test tree).
- Create `docs/standalone-agent-runtime-evidence.md`.

**Interfaces:** Consumes чинні CodexSessionProtocol/CodexJsonRpcTransport/process starter та opt-in fixtures. Produces verified version/capability matrix і test fixtures; не змінює production version gate.

- [ ] Написати opt-in smoke із контрольованим HOME/CODEX_HOME/workspace: start durable root → turn → завершити process → resume same ID → follow-up. Assert unchanged thread ID/cwd/instructions/model, no wrapper parent, cleanup усіх owned processes.
- [ ] Початкову characterization виконати через наявні `CodexSessionProtocol`/transport у test fixture: production `CodexAppServerClient` наразі правильно відхиляє 0.163.0. Не вимикати його version gate і не писати другий JSON-RPC transport. Після Task7 повторити той самий scenario через production client/adapter.
- [ ] Додати interruption/turns-list inspection та root → native child probe з harmless task; перевірити реальні parent/child IDs, inherited permissions, runtime limits, локальну/глобальну TOML selection. Native Skill fixture має дати перевірюваний результат читання без сторонніх side effects.
- [ ] Запустити без production змін: `mvn -f services/forge-agent/pom.xml -pl infrastructure/codex -am -Dtest=CodexStandaloneRuntimeSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test` із явними opt-in binary/auth settings за pattern наявних E2E fixtures. Не друкувати credentials і не копіювати особистий config.
- [ ] Записати точні binary version, generated-schema evidence, config fields та limits, реальний outcome, skip/unsupported причини. Перевірити managed launcher binary окремо від shell CLI; shell-only smoke не доводить runtime UID isolation.
- [ ] Якщо auth/runtime недоступний — залишити smoke NOT_VERIFIED і production support fail-closed; детерміністичні тести та решта плану можуть тривати. Acceptance native-subagents залишається невиконаним до реального smoke.

## Task 2: Shared invocation port без зміни Workflow contract

**Files:**
- Create `A/domain: domain/model/execution/{ExecutionOwner,ExecutionLease,EffectiveAgentDefinition,AgentOutputContract,AgentRuntimeCapabilities}.java` (кожен клас — окремий файл).
- Create `A/application: application/runtime/{AgentTurnExecutionPort,AgentTurnExecutionRequest,AgentTurnResult,AgentTurnEventSink,AgentTurnIdentityCallbacks}.java`.
- Create `A/infrastructure/codex: infrastructure/codex/CodexAgentTurnExecutionAdapter.java`.
- Modify existing `CodexAgentExecutor.java`, `CodexTurnRequest.java`, `CodexAppServerClient.java` у тому самому package.
- Test `CodexAgentTurnExecutionAdapterTest.java`, existing `CodexAgentExecutorTest.java`, `CodexAppServerTurnClientTest.java`, `CodexDurableSessionProtocolTest.java`.

**Interfaces:**
- `AgentTurnExecutionPort.execute(AgentTurnExecutionRequest request, AgentTurnEventSink events): AgentTurnResult`; `interrupt(UUID executionId): void`.
- Request: executionId, typed ExecutionOwner, immutable definition, input text, output contract/schema string, ExecutionWorkspace, FRESH/DURABLE mode, optional saved thread/version, capabilities, MCP preparation, identity callbacks. Result: assistant text/thread/turn/version.
- Callbacks: `executionStarted(Runnable cancel)`, `conversationStarted(String id,String version)`, `turnStarted(String id)`, `dispatchTurnStart(Runnable write)`. Sink: normalized candidate/capture completion/degradation.
- `ExecutionOwner` sealed Workflow(project/session/turn/node/run IDs)/Standalone(project/session/turn IDs), без nullable fake ownership.

- [ ] Написати tests: structured Workflow request/output незмінний; TEXT не має outputSchema/envelope/ports; один root start; exact resume, no fallback; cancellation до/після registration; no duplicate lifecycle/transport.
- [ ] Запустити focused Codex tests; red має бути через відсутні port/adapter або нинішній unconditional schema dereference.
- [ ] Реалізувати adapter над чинним `CodexClient`; перенести лише invocation/cancellation registry та provider error translation. `CodexAgentExecutor` залишає Workflow input/output/schema/routing та owner callbacks і викликає shared port.
- [ ] Зберегти всі existing fresh/tracked/durable/Dialogue paths, включно з legacy untracked fixtures; Workflow capabilities не вмикають native children. TEXT omits schema; не порожній JSON schema замість plain text.
- [ ] Виконати весь codex module tests, включно з existing Dialogue/event/recovery fixtures; результат PASS перед локальним commit цієї extraction.

## Task 3: Shared effective definition resolution і secure native loading

**Files:**
- Create `A/application: application/definition/{AgentDefinitionResolver,ResolveAgentDefinitionRequest,ForgeAgentDefinitionResolver,AgentDefinitionResolutionService,AgentDefinitionFingerprint}.java`.
- Create `A/domain: domain/port/NativeAgentDefinitionPort.java`, `domain/model/execution/AgentReference.java`.
- Create `A/infrastructure/codex: infrastructure/codex/CodexNativeAgentDefinitionAdapter.java`.
- Modify `CodexProjectAgentInventory.java`, resource `A/infrastructure/codex/src/main/resources/codex/project_agent_inventory.py`; extract shared nofollow reader into resource `codex/agent_definition_reader.py` if both need it.
- Modify `A/application: application/runtime/WorkflowRunSnapshotBuilder.java`.
- Tests: `AgentDefinitionResolutionServiceTest.java`, `ForgeAgentDefinitionResolverTest.java`, `CodexNativeAgentDefinitionAdapterTest.java`, existing `CodexProjectAgentInventoryTest.java`, `WorkflowRunSnapshotBuilderTest.java`.

**Interfaces:** `AgentDefinitionResolver.resolve(ResolveAgentDefinitionRequest): EffectiveAgentDefinition`; `NativeAgentDefinitionPort.resolve(AgentReference, LibraryContext): EffectiveAgentDefinition`. Request includes verified project/repository scope, invocation contract, optional server-trusted native fallback model. `AgentReference(source,id)` не містить path.

- [ ] Написати tests: Forge project denial; model/effort з source agent, незалежно від profile; editable source не змінює snapshot; same rules for new Workflow snapshots, historical runs untouched.
- [ ] Додати native fixtures: multi-line TOML instructions/model/effort, duplicate keys, malformed UTF-8, limits, unknown runtime fields, symlink/hardlink/component swap, opaque ID іншого project, disabled global resources, same-name distinct agents.
- [ ] Реалізувати bounded secure resolution з тим самим fd/nofollow traversal і opaque identity formula. Allowlist capability keys — тільки Task1 verified contract; невідоме execution-affecting поле відхиляється. Не конвертувати TOML regex-ом і не довіряти paths із API.
- [ ] Native fallback: лише коли definition не задає model, використати зафіксований server-trusted profile context за існуючим Library pattern. Forge resolver цей fallback ігнорує; відсутній native fallback дає MODEL_UNAVAILABLE. Нова model setting або browser model selector не додається.
- [ ] Workflow snapshot builder використовує Forge resolver rules для нових run nodes, не читає native files для старих runs і не переписує snapshots. Batched source lookup зберегти.
- [ ] Запустити application/Codex resolver tests та AI Library regressions; локальний commit після PASS.

## Task 4: Standalone domain invariants та PostgreSQL storage

**Files:**
- Create `A/domain: domain/model/standalone/{StandaloneAgentSession,StandaloneAgentTurn,StandaloneAgentMessage,StandaloneSessionStatus,StandaloneTurnStatus,StandaloneSessionSnapshot,StandaloneCommandReceipt,StandaloneSessionPage,StandaloneMessagePage,StandaloneEventPage}.java`.
- Create `A/domain: domain/port/standalone/{StandaloneSessionRepository,StandaloneExecutionRepository,StandaloneEventRepository}.java`.
- Create `A/infrastructure/postgres/src/main/resources/db/migration/V48__create_standalone_agent_sessions.sql` (перед створенням знову перевірити зайняті migration numbers).
- Create `A/infrastructure/postgres: infrastructure/postgres/adapter/standalone/{PostgresStandaloneSessionRepository,PostgresStandaloneExecutionRepository,PostgresStandaloneEventRepository,StandaloneSnapshotCodec}.java`.
- Tests: domain `StandaloneAgentSessionTest.java`; boot `it/tests/{ForgeAgentStandaloneMigrationIT,ForgeAgentStandaloneStorageIT}.java`.

**Interfaces:** Session repository `find(projectId,sessionId)`, `lock(projectId,sessionId)`, `create(session,turn,message,requestHash)`, `findReceipt(projectId,requestId)`, `appendTurn(sessionId,expectedRevision,turn,message,requestHash)`, bounded list/messages. Execution repository supplies fenced claim/renew/identity/finalize/cancel/recovery operations used by Task6. Event repository appends normalized candidate under lease and reads afterSequence.

- [ ] Написати domain tests на immutable snapshot/copy, valid state transitions, successful turn → READY, safe failed/cancelled turn reuse, recovery-required block та independent turn/session status.
- [ ] Написати migration/storage tests: session без Workflow/NodeRun; project/composite turn-message-event FK; immutable snapshot; unique requestId/provider conversation; one active turn; stale revision; sequence constraints; cross-project reads; existing Workflow/Dialogue constraints залишаються суворими.
- [ ] Реалізувати окремі tables/SQL/typed adapters за наявним PostgreSQL pattern; JSON snapshot versioned; runtime credentials не персистити. Claims використовують DB clock, token monotonic, row locking; no SQL у domain.
- [ ] Запустити focused domain та PostgreSQL ForgeIT із `-Dapi.version=1.44`; перевірити конкурентні транзакції реальним PostgreSQL, не mock-only.
- [ ] Локальний commit після PASS; old migrations не входять у diff.

## Task 5: Create/preview/send/query use cases

**Files:**
- Create `A/application: application/standalone/{StandaloneSessionCommands,StandaloneSessionQueries,StandaloneSessionLaunchPreview,StandaloneCommandValidation,StandaloneSessionAccess}.java`.
- Create `A/domain: domain/model/standalone/{CreateStandaloneSession,SendStandaloneMessage,StandaloneLaunchPreview}.java`.
- Modify `A/application: application/runtime/ExecutionWorkspaceResolver.java` лише для нейтральних назв/діагностик reusable overload.
- Tests: `StandaloneSessionCommandsTest.java`, `StandaloneSessionQueriesTest.java`, `StandaloneSessionLaunchPreviewTest.java`.

**Interfaces:** `create(UUID projectId,CreateStandaloneSession): StandaloneCommandReceipt`; `send(UUID projectId,UUID sessionId,SendStandaloneMessage): StandaloneCommandReceipt`; `get/list/messages/events` з project scope. Create includes requestId/reference/repository IDs/initial text/server context; send includes requestId/expectedRevision/text. Preview resolves without thread/turn/grants and returns name/model/launchability/safe diagnostic.

- [ ] Написати tests: inaccessible project/agent/repository; source snapshot; duplicate create/send після втрати receipt; same requestId + changed payload conflict; duplicate detection перед revision check; busy/stale revision; message ordering; bounded limit/cursor validation; thread IDs/paths/instructions не public.
- [ ] Перевірити blank/empty/32768/32769-code-point messages, surrogate pairs, duplicate repository IDs та oversized request; server і composer показують однаковий limit, помилка не створює session/turn/message.
- [ ] Реалізувати atomic create і send через repositories Task4, definition resolver Task3 та trusted workspace resolver. Не виконувати Codex у HTTP transaction і не створювати Workflow.
- [ ] Preview і create використовують однакові resolution rules; create повторно перевіряє definition fingerprint, щоб модель у preview не підмінялася непомітно.
- [ ] Запустити application tests; додати ForgeIT create/duplicate tests у `ForgeAgentStandaloneCommandsIT.java`; commit після PASS.

## Task 6: Shared lease/event/dispatch hooks та bounded standalone worker

**Files:**
- Create `A/application: application/runtime/{ExecutionLeaseOperations,FencedExecutionHeartbeat,ExecutionEventRecorder}.java`.
- Modify existing `AgentSessionHeartbeat.java`, `AgentSessionLeaseService.java`, `AgentExecutionEventRecorder.java` для делегування спільним алгоритмам, зі збереженням existing APIs.
- Create `A/application: application/standalone/{StandaloneTurnLifecycle,StandaloneTurnWorker,StandaloneTurnExecutionFactory,StandaloneExecutionCallbacks}.java`.
- Create `A/infrastructure/postgres: infrastructure/postgres/adapter/{PostgresExecutionDispatchLock,standalone/PostgresStandaloneDispatchGuard}.java`; existing `PostgresAgentExecutionDispatchGuard.java` використовує shared lock primitive.
- Create `A/boot: StandaloneAgentConfiguration.java`.
- Tests: `FencedExecutionHeartbeatTest.java`, `StandaloneTurnLifecycleTest.java`, `StandaloneTurnWorkerTest.java`, boot `it/tests/ForgeAgentStandaloneTurnsIT.java`.

**Interfaces:** `ExecutionLeaseOperations.renew(ExecutionLease): void`; heartbeat `(Runnable renew,Runnable ownershipLost,ScheduledExecutorService)` + `verifyOwnership/close`. `StandaloneTurnLifecycle.claim(UUID turnId): Optional<AgentTurnExecutionRequest>`, `succeed(request,result)`, `fail(request,error)`. Shared event recorder uses fenced append/capture operations without NodeRun knowledge.

- [ ] Написати tests на one claim, stale renewal/callback/event/finalization, persist thread before dispatch, persist turn before dependent actions, cancel до callback і cancel/finish race, no active process після ownership loss.
- [ ] Реалізувати PostgreSQL fencing/advisory lock з окремими owner lock namespaces; recovery бере відповідний lock перед row locks. Не тримати DB transaction, поки provider відповідає; dispatch hook охоплює лише write.
- [ ] Worker використовує shared execution port, lease heartbeat та event normalization; без окремого Codex client. Session/assistant/turn finalization атомарні; failure не знищує recoverable thread.
- [ ] Додати bounded admission: default2 concurrent standalone turns, configured range1..8; acquire slot до DB claim; bounded polling batch100; default execution deadline10m/max30m, менше launcher hard lifetime. Після process cleanup slot повертається, unresolved cleanup блокує його до reconciliation.
- [ ] Запустити Workflow/Dialogue lease/event/dispatch regressions і real-DB race tests. Commit після PASS.

## Task 7: Cancellation та recovery без side-effect replay

**Files:**
- Create `A/application: application/standalone/{StandaloneSessionCancellation,StandaloneSessionRecovery}.java`.
- Modify `A/application: application/runtime/ProviderTurnRecoveryResult.java` з backward-compatible factories та optional recovered assistant text.
- Modify existing `A/infrastructure/codex: infrastructure/codex/{CodexRecoveryInspector,CodexRecoveryProtocol}.java`; add bounded terminal-item inspection у тому самому provider path.
- Extend standalone execution PostgreSQL adapter/dispatch guard і boot scheduler Task6.
- Tests: `StandaloneSessionRecoveryTest.java`, existing Codex recovery tests, boot `it/tests/ForgeAgentStandaloneRestartIT.java`.

**Interfaces:** `StandaloneSessionCancellation.cancel(projectId,sessionId,requestId,expectedRevision): StandaloneCommandReceipt`; `StandaloneSessionRecovery.poll(): void`; provider inspection повертає existing outcome і optional recovered reply, не новий runtime interface.

- [ ] Написати crash-window tests: queued; thread saved до dispatch; ambiguous turn/start write до ID; turn ID saved; completed provider до DB commit; missing/ambiguous inspection; failed/interrupt; stale recovery worker. Assert zero automatic repeat turns та unchanged provider conversation.
- [ ] Реалізувати durable cancel intent → interrupt owned execution → cleanup/revoke → fenced terminal update. Не показувати CANCELLED як підтверджений outcome, доки cleanup/inspection невизначені.
- [ ] Reuse recovery lifecycle/process inspection; bounded terminal reply retrieval та deduplication by turn ID. Completed без доведеного assistant reply дає explicit recovery required, без вигаданого повідомлення.
- [ ] Лише після Task1 real evidence оновити version support для перевіреної 0.163.0; mismatch або thread/resume failure не стартує заміну thread. Existing Workflow recovery outcomes не змінювати.
- [ ] Запустити real-DB restart/races та всі Codex recovery regressions; commit після PASS.

## Task 8: Capability revalidation, MCP ownership і native children

**Files:**
- Create `A/application: application/mcp/{ExecutionOwnershipVerifier,WorkflowExecutionOwnershipVerifier,StandaloneExecutionOwnershipVerifier}.java`.
- Modify existing `McpExecutionSelectionService.java`, `McpGatewayService.java`; domain `McpRuntimeGrant.java` використовує typed owner Task2.
- Modify existing `A/infrastructure/local: infrastructure/local/mcp/gateway/InMemoryMcpRuntimeGrantRepository.java` лише для owner representation, зберігаючи expiry/admission/revocation semantics.
- Create `A/application: application/standalone/StandaloneCapabilityRevalidation.java`; `A/domain: domain/port/AgentResourceCompatibilityPort.java`.
- Create `A/infrastructure/codex: infrastructure/codex/{CodexAgentResourceCompatibilityAdapter,CodexStandaloneCapabilities}.java`; modify existing Codex request/config/event mapper.
- Modify `scripts/runtime/forge-runtime-launcher.py` тільки якщо Task1 довів потрібну bounded змішану конфігурацію; не послаблювати base/project config deny rules.
- Tests: `ExecutionOwnershipVerifierTest.java`, `StandaloneCapabilityRevalidationTest.java`, existing MCP selection/gateway/security/launcher suites, `CodexStandaloneCapabilitiesTest.java`.

**Interfaces:** verifier `verify(ExecutionOwner,ExecutionLease): VerifiedExecutionAccess(projectId,turnId,deadline)`; shared selection `prepare(VerifiedExecutionAccess,Instant deadline)`; resource compatibility `verify(EffectiveAgentDefinition,ExecutionWorkspace)` → compatible або typed failure.

- [ ] Написати tests: fake Workflow IDs не потрібні/не приймаються; cross-project, stale lease, revoked connection/tool/credential, wrong dispatch/turn, expiry, restart invalidates bearer. Перевіряти та сама policy для обох owners.
- [ ] Реалізувати owner adapters через справжні repositories; shared connection eligibility та gateway admission. Зберегти bounded in-memory grant hashes, no new tokens persistence; revoke при cancel/finish/recovery/ownership loss.
- [ ] Fingerprint execution-affecting native Skills, child definitions і project instructions у trusted context; resource swap/change/revocation до turn → explicit incompatibility. Не стверджувати snapshot safety для неохоплених provider inputs; unsupported combinations fail closed.
- [ ] Застосувати лише Task1 доведені native controls/limits для standalone. Root instructions/model залишаються root; Codex вирішує чи делегувати; children не отримують ширших sandbox/MCP permissions. Workflow native agents залишаються disabled.
- [ ] Невідомі/unsupported native approval/user-input events показують safe limitation; no autoapproval. Existing event mapper передає actual tool/child IDs або explicit unsupported category.
- [ ] Запустити existing MCP security, runtime launcher Python tests і real native smoke; commit після PASS, live skip записати окремо.

## Task 9: Project-scoped Agent REST та Nexus typed BFF

**Files:**
- Create `A/api-rest: api/standalone/{StandaloneAgentSessionController,StandaloneSessionApiMapper,StandaloneSessionDtos}.java`.
- Modify Agent `api/security/AgentManagementRoutePolicy.java`; create `api/security/StandaloneSessionServiceFilter.java`; create boot `StandaloneSessionManagementConfiguration.java`.
- Create `N/domain: domain/model/standalone/StandaloneSessionContracts.java`, `domain/port/StandaloneAgentSessionClient.java`, `domain/usecase/StandaloneAgentSessionUseCases.java`.
- Create `N/application: application/usecase/standalone/StandaloneAgentSessionUseCase.java`.
- Create `N/api-rest: api/standalone/{ForgeAiStandaloneAgentSessionController,StandaloneSessionApiMapper,StandaloneSessionDtos}.java`.
- Create `N/clients/agent-client: infrastructure/agentclient/standalone/{StandaloneAgentSessionClientAdapter,StandaloneAgentSessionHttpClient,StandaloneAgentSessionClientMapper,StandaloneSessionClientDtos,StandaloneSessionHttpClientConfiguration}.java`.
- Tests: Agent/Nexus controller/mapper/usecase/client tests; boot Agent `it/tests/ForgeAgentStandaloneHttpIT.java`, Nexus `forgeproxyit/NexusStandaloneAgentSessionIT.java`.

**Interfaces:** Agent `/api/v1/projects/{projectId}/agent-sessions`, Nexus `/api/v1/infrastructure/agents/projects/{projectId}/agent-sessions`; create/list/get/messages/events/cancel. Add POST `/launch-preview` у цій же collection для native resolution/read-only model та launchability. Typed DTOs map Task5 commands і bounded pages; 202 accepted/200 duplicate; no workflow IDs.

- [ ] Написати typed composition/error tests на реальному local HTTP: unknown/cross-project404, invalid400, revision/busy409, service identity401/403, recovery/version/resource errors; duplicate lost-receipt response; bounded cursors, no leaked internal fields.
- [ ] Реалізувати thin controller → mapper → usecase → domain client → adapter → existing `ForgeAgentClientCallExecutor` → typed HTTP client. Existing error handling, connect/read timeouts, loopback destination/no redirects.
- [ ] Internal session API вимагає existing protected service identity; default file `/etc/forge-ai/codex-service/token`, existing token readers/bind guard, browser Origin/Sec-Fetch denied, controller marker check fail-closed навіть без filter bean. Не поширювати Library read-only marker на session mutations.
- [ ] Nexus public requests не приймають native fallback model/provider context. Для native preview/create сервер один раз отримує trusted context через existing `KnowledgeActiveProfileClient` за Library pattern; лише fallback Task3, ніколи Forge override. Preview fingerprint перевіряється create.
- [ ] Запустити Agent/Nexus units та ForgeIT. Не тримати HTTP request до завершення execution; commit після PASS.

## Task 10: Console API/controller та sessions navigation

**Files:**
- Create `C/src/models/agent-sessions.ts`.
- Create `C/src/operator/{agent-sessions-api.js,agent-sessions-api.d.ts,agent-sessions-controller.js,agent-sessions-controller.d.ts}`.
- Modify `C/src/operator/{agent-projects-page.js,agent-projects.html,project-dashboard-view.js}`.
- Tests `C/tests/{agent-sessions-api.test.ts,agent-sessions-controller.test.ts}`, existing `agent-projects-page.test.ts`.

**Interfaces:** API preview/create/list/get/messages/send/events/cancel accepts AbortSignal; controller `open(projectId,sessionId)`, `loadPage(query,cursor)`, `send(text)`, `cancel()`, `dispose()`, `subscribe(listener)`. State maps messages/events by server sequence, includes revision/turn/current project/generation; pending command keeps same requestId on retry.

- [ ] Написати fake-timer tests: reconnect/event dedup, mixed turn identity, old-route responses ignored, abort/dispose, no overlapping polls, busy composer, lost-receipt retry, search/page reset, stale revision refresh without automatic resend.
- [ ] Реалізувати bounded cursor polling2s під час RUNNING/queued, 5s у READY; pause hidden tab, refresh on focus; stop requests/timers on route dispose. History pages incremental, не завантажувати все при кожному poll.
- [ ] Додати Sessions Project Overview/navigation entry і exact deep links `#/projects/{id}/sessions` та `.../{sessionId}` через чинний hash/Back/Forward handler у agent-projects-page, без другого router.
- [ ] Запустити focused Console tests та typecheck; commit після PASS.

## Task 11: Launch form, chat, activity та responsive rendering

**Files:**
- Create `C/src/operator/{agent-sessions-view.js,agent-sessions-view.d.ts}`.
- Modify `C/src/operator/{ai-library-view.js,ai-library-view.d.ts,agent-projects-page.js,operator-ui.css,agent-projects.html}`.
- Tests `C/tests/agent-sessions-view.test.ts`; extend `ai-library-view.test.ts`, `agent-projects-page.test.ts`.

**Interfaces:** `AgentSessionsView.mount(host,handlers)`, `render(state)`, `dispose()`; launch form consumes server preview Task9, submits Task10 create. Existing Forge agent details and native Library details call same launch handler with reference/project.

- [ ] Написати tests: launch з обох джерел, unavailable definition explanation, project-local scope, repositories/initial text/read-only model, submit acknowledgement/open session; error preserves initial task.
- [ ] Додати session list search/status/name/lastActivity/open/keyset pagination, loading/empty/error states та agent/session navigation links. Використати чинні Library icons/typography/spacing/borders/toolbar styles; не копіювати агентів у Library.
- [ ] Створити chat header/status/timeline/composer/Send/Stop і collapsed secondary activity; append/update окремі DOM nodes, composer не перемонтовується. Persist unsent draft у controller per session, не затирати введений після submit текст пізнім receipt.
- [ ] Додати tests на XSS text escaping, focus/selection/draft preservation при poll/route return, ordered messages/events, accessible live status, recovery errors, cleanup on dispose.
- [ ] Перевірити CSS390px: min-width0/wrapping, activity не перекриває timeline, composer доступний із довгим текстом/назвами. Запустити Console test/typecheck/build; commit після PASS.

## Task 12: Повна інтеграція, live acceptance та незалежний review

**Files:**
- Create `A/boot` tests `it/tests/ForgeAgentStandaloneFlowIT.java`; extend restart/http/MCP fixtures з Tasks4–9.
- Extend Nexus `NexusStandaloneAgentSessionIT.java` для full typed transport lifecycle.
- Update `docs/standalone-agent-runtime-evidence.md` із командами/результатами та final report matrix із task.

**Interfaces:** Consumes реалізацію Tasks1–11. Produces reviewable branch, actual verification evidence та чесний acceptance status.

- [ ] Додати/запустити інтеграційний flow: selected agent → snapshot/session → user turn → worker/root response/events → persisted result → service restart → follow-up same thread. Assert Workflow/NodeRun table counts не змінилися, provider root без Forge parent.
- [ ] Виконати `mvn -f services/forge-agent/pom.xml -Dapi.version=1.44 clean verify` та `mvn -f services/forge-nexus/pom.xml -Dapi.version=1.44 verify`; перевірити actual reports на skipped opt-in tests. Agent integration/ForgeIT, Nexus ForgeIT, Workflow/Dialogue/MCP regressions мають бути зелені.
- [ ] У `services/forge-console`: `npm test`, `npm run typecheck`, `npm run build`. Для changed runtime: `python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py'` та relevant managed-runtime opt-in tests.
- [ ] Виконати relevant module `mvn dependency:analyze` через reactor lifecycle з resolved sibling artifacts; нові used/undeclared або unused dependencies виправити, baseline warnings відокремити. `git diff --check` і перевірка нових unstaged файлів окремо.
- [ ] Controlled real runtime/UI smoke: два turns same thread, reopen UI/history/continuation, actual activity, Stop, native Skill та native child invocation, no hidden Workflow/parent. Desktop і390px; Back/Forward/deep reload/focus; перевірити process cleanup та denied unsupported in-turn request.
- [ ] Провести окремий незалежний review actual implementation: hidden owners/duplicate execution, DDD dependencies, resolver TOCTOU, snapshot coverage, MCP bypass, cancellation/recovery races, limits, obsolete dependencies. Для native execution — один свіжий reviewer наприкінці; виправити findings і повторити лише релевантні checks.
- [ ] Скласти final report у точному user matrix format. PASS лише з доказом; runtime/browser без real execution — NOT_VERIFIED; acceptance implementation gaps — FAIL. Нічого не публікувати у GitHub.

## Порядок виконання та локальні commits

Tasks1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12.
Task1 є verification prerequisite для production provider feature enablement,
а не дозвіл вигадати native configuration при недоступному runtime.
Після кожного task виконати вказаний test cycle (red → minimal implementation
→ green), оновити checkbox, переглянути staged diff і локально commit лише
файли задачі. Не commit сторонній `tools/`, build artifacts чи credentials.

## Self-review перед handoff

- [x] Усі розділи spec мають task: architecture/port2; resolution3; aggregate/storage4–5;
  fencing/events6; recovery7; capabilities/MCP8; API9; UI10–11; acceptance/review12.
- [x] Provider schema evidence відокремлено від live proof; installed/managed binary identity не змішується.
- [x] Ports і consumers узгоджені; домен не залежить від `ExecutionWorkspace`/Codex/Jackson.
- [x] П'ять Review Focus failure modes мають названі tests у відповідних tasks.
- [x] Жодного Worktree, Workflow FK weakening, нового credential storage або PR action.
- [x] Initial create/send contract, model fallback, Stop/recovery semantics і bounded paging визначено.

## Execution handoff

План очікує перегляду користувача та вибору execution method.
Рекомендація: **Native** — реалізація основним агентом у цій сесії та один
незалежний reviewer actual branch наприкінці. Tasks тісно пов'язані shared
contracts; це зменшує повторне читання security/runtime context.
Альтернатива: **Subagent-driven** — окремий виконавець і reviewer для кожного task,
із більшими витратами контексту. Сам факт цього плану не запускає subagents.
