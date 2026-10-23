# AI Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose one truthful, read-only runtime library for the selected project and Knowledge Active Profile.

**Architecture:** Nexus resolves one immutable active profile per snapshot and delegates a typed query to Agent. Agent validates project context and calls four typed inventory ports inside one request-scoped provider session. Codex infrastructure owns native discovery, mapping, isolation and cleanup; Console renders provider-neutral snapshots.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Jackson, JUnit/Mockito, existing Maven integration harness, JavaScript with TypeScript declarations, Vitest/jsdom and Vite.

**Spec:** `docs/superpowers/specs/2026-10-21-ai-library-design.md` (approved by user on 2026-10-21).

## Global Constraints

- No second provider selector, inventory persistence, Global/Local views, browser-supplied paths, or native artifact execution.
- Forge Agents and Workflows remain separate bounded contexts.
- One session per snapshot, no process per category and no indefinite session cache.
- No thread/start, turn/start, tool calls, shell commands or skill execution.
- No new roots or relaxed sandbox.
- Resolve Knowledge Active Profile once per inventory snapshot in Nexus.
- No push, merge or PR edits.
- Work in existing regular branch `feature/SITIONIX-166`; preserve unrelated `tools/`.
- User-facing communication is Ukrainian. Existing product copy remains consistent with Console English conventions.

## Review Focus

- A profile changes away and back while a request runs: generation/revision checks must reject the old response, not merely compare provider IDs (Task 6).
- Two repositories contain the same relative skill path: source-qualified identities remain distinct (Tasks 1–3).
- A provider returns usable entries plus discovery errors: retain entries, mark incomplete and suppress misleading Dashboard counts (Tasks 1–3, 5).
- A logout races process construction or cleanup: the owned process must terminate and the lease must be released (Task 3).
- A malformed encoded item route or disappearing item must not crash navigation or show another project's details (Tasks 5–6).

## File and contract map

Agent paths below are rooted at `services/forge-agent`; Nexus paths at `services/forge-nexus`. Java source directories retain their existing package prefixes `com/sitionix/forgeagent` and `com/sitionix/forgeai` respectively.

| Files | Responsibility |
| --- | --- |
| Agent `domain/.../model/library/*.java` | Immutable typed context, descriptors, results and snapshot |
| Agent `domain/.../port/library/*.java` | Four category ports plus request-scoped provider session factory |
| Agent `application/.../library/GetAiLibraryUseCase.java` | Project validation, context resolution, typed aggregation |
| Agent `infrastructure/codex/.../library` classes in existing Codex package | Version/capability policy, mapping, approved inventory operations |
| Agent existing Codex client lifecycle helpers | Share initialization/ownership without widening request(String,JsonNode) |
| Agent `api-rest/.../api/library/*.java` | Internal query controller and allowlisted DTO mapper |
| Nexus `domain/.../model/library/*.java`, `port/AiLibraryClient.java`, `usecase/GetAiLibrary.java` | Independent typed boundary |
| Nexus `application/.../usecase/GetAiLibraryUseCase.java` | Knowledge profile composition |
| Nexus `clients/agent-client/.../agentclient/library/*.java` | Authenticated bounded Agent HTTP query |
| Nexus `api-rest/.../api/ForgeAiLibraryController.java`, `api/library/*.java` | Public GET and response DTOs |
| Console `src/operator/ai-library-{controller,view}.js`, matching `.d.ts` | Request/state lifecycle and accessible rendering |
| Console `src/models/ai-library.ts` | Provider-neutral API types |
| Console existing project page/dashboard/API/HTML/CSS and AI runtime view | Navigation, card, profile invalidation integration |

`...` means the Java source root and package prefix stated above, not a new directory literally named `...`. Keep Codex classes in `com.sitionix.forgeagent.infrastructure.codex` to reuse package-private infrastructure. Descriptor files are separate public records; no JsonNode or infrastructure types in domain.

### Task 1: Typed inventory contracts and status invariants

**Files:** Create Agent `domain/src/main/java/com/sitionix/forgeagent/domain/model/library/{LibraryProviderContext,LibraryContext,InventoryStatus,InventoryDiagnostic,InventoryResult,LibraryProvenance,SkillDescriptor,AgentDescriptor,FlowDescriptor,ToolDescriptor,LibraryCategories,LibrarySnapshot}.java`; ports in `domain/src/main/java/com/sitionix/forgeagent/domain/port/library/{SkillInventoryPort,AgentInventoryPort,FlowInventoryPort,ToolInventoryPort,LibraryProviderSession,LibraryProviderSessionFactory}.java`. Test `domain/src/test/java/com/sitionix/forgeagent/domain/model/library/InventoryResultTest.java`.

**Interfaces:** Each category port has `InventoryResult<SpecificDescriptor> discover(LibraryContext context)`. `LibraryProviderSession extends AutoCloseable` exposes `skills()`, `agents()`, `flows()`, `tools()` and `void close()`. Factory exposes `boolean supports(String providerId)` and `LibraryProviderSession open(LibraryContext context)`. LibraryContext contains project UUID, immutable provider context, trusted primary Path and bounded trusted workspace Paths; no browser DTO constructs it.

- [ ] Write failing `InventoryResultTest`: EMPTY has no items; AVAILABLE has items; FAILED may retain items and incomplete diagnostics; UNSUPPORTED and UNAVAILABLE have no fabricated entries. Assert defensive list copies and stable distinct identities for equal names.
- [ ] Run `mvn -f services/forge-agent/pom.xml -pl domain test`; verify new assertions fail before implementation.
- [ ] Implement records and narrow interfaces. Provider context has profile revision, provider/model IDs, effort ID and optional display labels. Descriptors have opaque ID, name, safe description and internal provenance; category result has status, items and typed diagnostic codes.
- [ ] Repeat domain tests; require PASS. Commit only Task 1 files.

### Task 2: Project-scoped application orchestration

**Files:** Create `application/src/main/java/com/sitionix/forgeagent/application/library/GetAiLibraryUseCase.java` and `application/src/test/java/com/sitionix/forgeagent/application/library/GetAiLibraryUseCaseTest.java`.

**Interfaces:** `LibrarySnapshot execute(UUID projectId, LibraryProviderContext provider)`. Consume Task 1 factories, ProjectRepository, ProjectRepositoryLinkRepository and LocalProjectWorkspacePort. Provider may be absent; project validation still occurs first.

- [ ] Write failing tests: project not found causes no workspace/runtime calls; absent profile returns UNAVAILABLE; unknown provider returns UNSUPPORTED categories; known provider opens/closes exactly one session; Skills failure preserves Tools success; provider parameters come from the supplied immutable server context.
- [ ] Add project A/B fixtures and assert each receives only its managed root and successfully cloned repository paths from `resolveRepositoryWorkspaceStates`. Exclude uncloned paths and other projects. A workspace-resolution failure produces UNAVAILABLE without launching runtime.
- [ ] Run `mvn -f services/forge-agent/pom.xml -pl application -am test`; require the new tests to fail first.
- [ ] Implement context validation, bounded workspace resolution and four independently guarded typed calls in try-with-resources. Select factories by provider support; duplicate registrations fail clearly. No filesystem layout, JSON or provider method strings in this use case.
- [ ] Repeat tests; require PASS. Commit Task 2 files.

### Task 3: Read-only Codex inventory and safe lifecycle

**Files:** Create in `infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/`: `CodexLibrarySessionFactory.java`, `CodexLibrarySession.java`, `CodexSkillInventoryAdapter.java`, `CodexLibraryCapabilities.java`, `CodexLibraryMetadataMapper.java`. Modify `CodexAppServerClient.java` only to share lifecycle/initialize helpers; extract `CodexReadOnlySessionFactory.java` if needed to avoid duplicated ownership. Add matching tests under `infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/`; add deterministic protocol fixtures under its `src/test/resources/library/`.

**Interfaces:** Implement Task 1 session factory for provider ID from existing Codex provider conventions. Internal read-only session exposes `listSkills(List<Path> cwds, boolean forceReload)` and version; it does not expose arbitrary request methods. Transport continues to reject unsolicited server execution requests.

- [ ] Add failing fixture tests for native `skills/list` shape, malformed data, `-32601` unsupported operation, errors with partial skills, disabled entries, duplicate names/distinct paths, repeated same identity across CWDs and unrecognized scopes. Confirm skills disabled by runtime are not counted as available.
- [ ] Test deadline, interruption, revoked authorization, process-start failure, stream-construction failure and cleanup failure; assert one process, one lease, close on all paths, and only initialize/initialized/skills/list messages. Assert existing model/list allowlist still rejects other arbitrary methods.
- [ ] Add bounds tests: at most 32 managed CWDs and 10,000 returned skill entries, 2,000-character safe presentation strings and 30-second total inventory deadline. Over-limit results are FAILED/incomplete, never EMPTY. Use existing transport frame bounds; do not weaken them.
- [ ] Run `mvn -f services/forge-agent/pom.xml -pl infrastructure/codex -am test`; verify new tests fail.
- [ ] Implement shared process ownership through managed starter and authorization observation lease; register cancellation before accessing streams. Use actual primary project CWD. Enforce total remaining deadline across initialization and discovery; always verify cleanup completion and release lease.
- [ ] Map runtime-effective skills without name deduplication. Stable SHA-256 identity inputs include provider/category/native path/plugin identity; source path remains internal. Validate provenance against trusted roots, reject symlink escapes, and omit raw paths/errors/config/tool dependencies from presentation. Test absolute-path and credential-shaped text redaction and XSS strings.
- [ ] Inspect managed-version protocol when safely accessible. Retain generated 0.163.0 schema excerpts as deterministic fixture evidence; do not equate PATH binary with managed version. Verify duplicate-name behavior in isolated global/repo fixtures without executing skills. Add only protocol-backed precedence rules; retain distinct identities otherwise and document verification limits.
- [ ] Agents and Flows adapters return UNSUPPORTED for confirmed absent native operations. Tools adapter returns UNAVAILABLE with `MCP_EXECUTION_CONTEXT_REQUIRED` when native MCP inventory exists but requires unavailable approved execution context; return UNSUPPORTED only for an absent operation. No NodeExecutionClaim, tracked session, MCP grants or database inventory is created by the query.
- [ ] Repeat Codex and application tests; require PASS. Commit Task 3 files.

### Task 4: Authenticated Agent boundary and Nexus composition

**Files:** Create Agent `api-rest/src/main/java/com/sitionix/forgeagent/api/library/{AiLibraryController,AiLibraryApiMapper,AiLibraryQueryRequest,AiLibraryResponse}.java` and matching tests. Modify Agent `api/security/AgentManagementRoutePolicy.java`, `api/remoteaccess/RemoteAccessServiceFilter.java` and their tests to include the exact library query route in the existing loopback service identity boundary. Create Nexus files from the contract map plus client/API/application tests. Add Agent `boot/src/test/java/com/sitionix/forgeagent/library/AiLibraryHttpIntegrationTest.java`.

**Interfaces:** Internal POST `/api/v1/projects/{projectId}/ai-library/query` consumes only typed nullable provider context. Public GET `/api/v1/infrastructure/agents/projects/{projectId}/ai-library` has no provider/path input. Nexus `AiLibraryClient.query(UUID, LibraryProviderContext)` returns its own typed LibrarySnapshot; `GetAiLibrary.execute(UUID)` resolves Knowledge once then invokes this port. DTOs expose projectId, profileRevision, provider, typed categories and safe items.

- [ ] Write failing tests for exactly one Knowledge call per snapshot, separate Forge Agent model settings, absent profile, Knowledge failure, partial statuses and typed error propagation. On Knowledge failure still validate project through Agent using absent context; no runtime launch.
- [ ] Test internal requests without service credentials, from non-loopback addresses, with duplicate Authorization headers or browser Origin/Sec-Fetch headers. Deny them. The controller requires an authenticated request attribute so disabled management filter cannot make the route public. Public query parameters cannot override provider or paths. Verify no-store headers on success/error.
- [ ] Reuse existing management-secret validation and Nexus `RemoteAccessSecretFile` mechanism, with a dedicated bounded library HTTP client using the existing configured service-secret files. No new credential store. Validate loopback URL, forbid redirects, never forward browser Authorization, cap request body at 4 KiB and client response at 8 MiB; HTTP timeout includes bounded cleanup overhead (45 seconds). Missing service identity yields sanitized unavailable response rather than falling back to unprotected Agent calls.
- [ ] Implement typed application, client and API mappers; extend existing exception-handler scope for the new public controller. Do not expose domain provenance. Test serialization excludes paths, tokens, dependencies and raw runtime errors. Verify project missing is 404 even without active profile.
- [ ] Run `mvn -f services/forge-agent/pom.xml verify` and `mvn -f services/forge-nexus/pom.xml verify`; require applicable unit and integration suites PASS, recording environmental blockers separately. Integration fixture supplies deterministic provider sessions and real HTTP authorization; no native artifact execution.
- [ ] Commit Task 4 files after targeted tests pass.

### Task 5: Console library page and Dashboard card

**Files:** Create `services/forge-console/src/models/ai-library.ts`, `src/operator/ai-library-{controller,view}.js` and matching `.d.ts`; create `tests/ai-library-{controller,view}.test.ts`. Modify `src/operator/{agent-projects-api.js,agent-projects-api.d.ts,agent-projects-page.js,agent-projects.html,project-dashboard-view.js,operator-ui.css}` and `tests/{agent-projects-api,agent-projects-page}.test.ts`.

**Interfaces:** API `getAiLibrary(projectId, {signal} = {})`. `AiLibraryController({api,onChange})` exposes `load(projectId,{force=false}={})`, `invalidate()`, `dispose()`; publishes state `{projectId, snapshot, loading, error, generation}`. `AiLibraryView({document,onRefresh,onNavigate})` exposes `render(state,{category,search,page,itemId})` and `dispose()`. Controller owns requests and a short-lived last snapshot; view owns safe rendering and client pagination.

- [ ] Write failing tests for card title/description, read-only provider/model, complete supported counts only, all five statuses, partial entries, Refresh, search and category filters, 10,000 fixture entries with at most 100 rendered results per page, keyboard labels and XSS escaping.
- [ ] Implement one AI Library card with exact description `Capabilities available to the active AI provider`. Guard Dashboard create-button updates for sections with no create action. Never display zero for UNSUPPORTED/UNAVAILABLE/FAILED or incomplete categories.
- [ ] Implement dedicated page with All/Skills/Agents/Flows/Tools navigation and bounded rendering. Show safe item details and unavailable-item message; no filesystem paths or Create controls. Refresh bypasses cached snapshot and requests fresh runtime data.
- [ ] Load one snapshot on overview entry for requested summary counts; reuse for immediate library navigation only within 30 seconds and the same project/profile generation. No inventory timer, no process launch from render, no persistent storage. Preserve existing unrelated entity loaders.
- [ ] Run `npm --prefix services/forge-console test -- tests/ai-library-controller.test.ts tests/ai-library-view.test.ts tests/agent-projects-api.test.ts tests/agent-projects-page.test.ts`; verify failures first, then PASS. Commit Task 5 files.

### Task 6: Provider invalidation and history-safe navigation

**Files:** Modify Console `src/operator/{ai-library-controller.js,agent-projects-page.js,ai-runtime-view.js}`; create `src/operator/active-profile-invalidation.js` and `.d.ts`; modify corresponding Console tests and `tests/jarvis-ai-runtime-modal.test.ts`.

**Interfaces:** `notifyActiveProfileChanged(windowRef, revision)` and `observeActiveProfileChanges(windowRef, callback)` (returns unsubscribe). Notifications contain revision only, no profile credentials. Project page integrates lightweight `/knowledge/active-profile` observations and Task 5 controller invalidation.

- [ ] Write failing A→B→A profile-race tests and project A→B late-response tests; assert old content clears immediately and late results never apply even when provider/model IDs match again. Test abort-resistant mock responses and failures after a newer success.
- [ ] After successful profile update publish same-window custom event plus cross-tab BroadcastChannel notification; fall back to storage event with transient revision notification if BroadcastChannel unavailable. Unsubscribe and close channels on dispose.
- [ ] Use PollingCoordinator for 5-second visible-library profile checks, pause when hidden, and check on focus/visibility recovery. Revision changes invalidate and reload once, with no overlapping inventory requests. Separate checks do not resolve the profile within an inventory snapshot a second time.
- [ ] Extend route parser for `#/projects/{projectId}/ai-library` and `#/projects/{projectId}/ai-library/{category}/{itemId}` with search/category/page query parameters. Validate/decode route segments defensively. Register hashchange alongside existing popstate handling; prevent duplicate navigation work and clean listeners on dispose.
- [ ] Test direct links, Back/Forward, malformed encoding, removed items, category/search/page restoration and navigation to existing agents/workflows/tasks/logs. Leaving project or page aborts pending inventory and clears details; no request-loop on Back.
- [ ] Run full Console tests, typecheck and build; require PASS. Commit Task 6 files.

### Task 7: Whole-feature verification and independent review

**Files:** Update `docs/superpowers/specs/2026-10-21-ai-library-design.md` with verified findings; create `docs/ai-library-runtime-evidence.md` for protocol/support limits and smoke results. Update this plan's checkboxes only for completed steps.

- [ ] Run `mvn -f services/forge-agent/pom.xml verify` (domain/application/Codex/API/Agent integration coverage) and inspect results, including non-skipped integration tests.
- [ ] Run `mvn -f services/forge-nexus/pom.xml verify` and inspect typed client/API integration results.
- [ ] Run `npm --prefix services/forge-console test`, `npm --prefix services/forge-console run typecheck`, `npm --prefix services/forge-console run build`, and `git diff --check`; require successful exit codes.
- [ ] Where managed runtime access is available, smoke only initialize + skills/list in the trusted managed project, then cleanup. Do not use personal CODEX_HOME or existing interactive threads. If unavailable record LIVE_CODEX_SMOKE=NOT_VERIFIED and the exact access limitation.
- [ ] Where browser tooling/runtime is available, verify real Dashboard card, page, details, search, refresh, Back/Forward and direct URL. Otherwise record BROWSER_E2E=NOT_VERIFIED; jsdom tests are not browser evidence.
- [ ] Perform user-requested independent review of the completed branch against main with a separate reviewer. Review DDD/SOLID, provider neutrality, exact category truthfulness, process ownership, sandbox/auth boundaries, stale revisions, redaction and existing Agent/Workflow/Task/MCP regressions. Fix findings and rerun affected checks only.
- [ ] Produce the user's exact final-report keys with truthful PASS/FAIL/UNSUPPORTED/NOT_VERIFIED values, changed-file list, actual supported runtime operations, unsupported capabilities and remaining findings. Tools must report its actual unavailable native MCP context; do not claim complete tool discovery.

## Plan self-review

Coverage: domain/ports (1), project aggregation/isolation (2), native protocol and lifecycle (3), trusted composition/API/security (4), UI/counts/large inventories (5), revision races/history (6), full checks and independent evidence (7). Review Focus cases are assigned explicitly above. No new persistence, execution permissions or provider selection state is introduced.

Execution recommendation: Native implementation in this session, followed by the user-requested independent whole-branch review. Tasks share typed contracts and lifecycle helpers, so sequential implementation reduces interface drift without repeated implementation contexts. Await user review of this plan and execution-method selection before product code.

## Execution record

Tasks 1–7 implemented; final evidence is `docs/ai-library-runtime-evidence.md`.
The original task checklists above are retained as the approved proposal.
Execution adaptations: shared lifecycle helpers reused existing construction/cleanup
race tests; inventory-specific logout coverage added; global metadata validation
uses the verified canonical-path native contract because managed HOME0700 is
intentionally inaccessible to control UID. Native SKILL.md file symlinks are
ignored, while directory/intermediate symlinks resolve canonically. Tools remain
explicitly unavailable under the existing execution-grant contract.

Final review: independent reviewer ACCEPT; no deferred code findings. Full clean
Agent verify, final focused guards/feature IT, Nexus verify, Console835 tests,
typecheck/build, real native smoke and actual Chrome fixture-backed checks pass.
No push, merge, PR changes or deployment.
