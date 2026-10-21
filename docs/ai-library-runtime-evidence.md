# AI Library — implementation and verification evidence

Implemented on regular branch `feature/SITIONIX-166`, based on main `d94f5d5b`.
No assigned external ticket was supplied; branch identifier was derived from the
highest local/remote SITIONIX feature identifier under repository instructions.
No worktree, inventory database/schema migration, push, merge or PR changes.
Unrelated untracked `tools/` remains untouched.

## Delivered behavior

- One Project Overview card and `#/projects/{projectId}/ai-library` page with
  read-only provider/model, search, category navigation, details, Refresh and
  bounded rendering (100 entries per page). Direct URLs and history work.
- Nexus GET `/api/v1/infrastructure/agents/projects/{projectId}/ai-library`
  resolves Knowledge Active Profile once and sends a typed immutable context to
  Agent. Individual Forge Agent model settings are independent.
- Agent validates project identity before runtime use and resolves only managed
  project/cloned repository workspaces through existing ports. Four distinct
  typed category ports share one request-scoped provider session.
- No stale project/profile generation is applied. Same-window/cross-tab profile
  notifications invalidate results; visible library checks revision every five
  seconds and on focus. Other-client changes become observable on the next check,
  not instantaneously. No provider inventory is launched by these profile checks.
- AVAILABLE, EMPTY, UNSUPPORTED, UNAVAILABLE and FAILED remain distinct. Partial
  skill results survive parsing diagnostics; incomplete/failed category counts
  are suppressed. Opaque IDs retain distinct same-name resources.

## Actual runtime operations and limits

The installed default managed-runtime binary reports Codex **0.163.0**. Its
locally generated protocol supports `skills/list` and `mcpServerStatus/list`.
No native agent/flow inventory or generic built-in tools/list operation is present.
Library sends only initialize/initialized and skills/list; it never starts a
thread/turn, executes a skill/tool, or issues MCP grants. Existing model/list-only
request allowlist remains intact.

| Category | Current Codex result |
| --- | --- |
| Skills | Native skills/list, unified provider-global plus selected-project contexts |
| Agents | UNSUPPORTED for verified 0.163.0 protocol |
| Flows | UNSUPPORTED for verified 0.163.0 protocol |
| Tools | UNAVAILABLE / MCP_EXECUTION_CONTEXT_REQUIRED |

The available MCP inventory operation needs approved execution-scoped context
under the existing Forge grants contract. This read-only feature does not create
execution sessions or substitute database tools. Thus **Tools discovery is not
available in this phase**. In the requested final-report vocabulary,
`TOOLS_INVENTORY=FAIL` records that availability limitation; API truthfully returns
UNAVAILABLE, not an empty or fabricated catalog.

Canonical native skill metadata is capability-gated to exactly **0.163.0**;
unverified versions return UNAVAILABLE/RUNTIME_PROTOCOL_UNVERIFIED for all
categories. This affects inventory only, not existing model/execution support.
Requests have a 30-second inventory deadline, existing transport frame limit,
32 managed CWD limit and 10,000-entry processing bound. Limit failures are explicit.
Nexus caps response bytes at 8 MiB and the complete HTTP response at 45 seconds,
including streaming body. Cancellation does not drain oversized response tails.
Snapshots are not persisted; immediate navigation reuse expires after 30 seconds.

## Authorization and provenance

Internal Agent query requires the existing protected local service identity:
Agent `forge.agent.remote-access.service-secret-file`, Nexus
`forge.remote-access.service-secret-file`, explicit loopback server bind and
`server.forward-headers-strategy=none`. Library does not require Remote Access to
be enabled. It shares the existing bind/header guard, secret validation and
runtime process/authorization infrastructure. Missing identity fails closed and
returns unavailable library data; browser headers cannot authorize internal calls.
Existing Project has no owner ACL field: authorization follows the installation's
existing operator/project management model rather than introducing fictitious
per-user ownership semantics.

Native metadata paths are never serialized. Global roots derive from the
initialize response of the managed process and the verified Codex layout. Control
UID does not gain read access to runtime HOME0700. Repo paths receive local
canonical/symlink checks; canonical native global identities receive managed-root
containment and extra local checks where metadata is accessible.

The real native smoke establishes that 0.163.0 canonicalizes directory and
intermediate symlinks and ignores a symlink SKILL.md file. Foreign canonical
identities are omitted with an explicit incomplete diagnostic. **This validates
returned metadata isolation; it is not a claim that native Codex never traverses
outside a project before returning metadata.** Existing launcher/sandbox authority
is unchanged. Forge does not read or execute discovered skill contents.
Presentation fields and diagnostics are allowlisted; paths, configuration,
credentials, transport metadata and dependency commands are not returned.

## Verification performed

- PASS: `mvn -f services/forge-agent/pom.xml -Dapi.version=1.44 clean verify`.
  Full existing unit/integration suites, including project/workflow/task/MCP
  regression tests. Opt-in live tests retain their existing skip conditions.
- PASS: focused final Agent management/bind/composition and AI Library/Remote
  Access integration verification after sharing the guard.
- PASS: final Codex/domain/application/local suites, with opt-in native smoke.
- PASS: `mvn -f services/forge-nexus/pom.xml -Dapi.version=1.44 verify`, including
  typed GET/client composition, real local HTTP auth and bounded streaming tests.
- PASS: Console **835 tests / 42 files**, typecheck and production build.
- PASS: real installed Codex read-only inventory smoke with isolated temporary
  HOME/CODEX_HOME and project A/B. Distinct same-name global/repo skills,
  alternate global location, canonical symlink cases and process cleanup checked.
- PASS: actual Chrome against built Console assets and a deterministic local API
  fixture: Overview card, category/status display, details, search, Refresh,
  Back/Forward and direct-detail reload. This was not deployed-Nexus/browser E2E.
- PASS: independent reviewer audited the whole implementation and subsequent
  fixes; final verdict ACCEPT. Findings fixed: profile recovery, global roots,
  FAILED classification, total HTTP-body deadline, error cache policy, explicit
  MCP explanation and shared loopback/forwarded-header protection.
- PASS: git diff checks, including staged new files before commits.

First Agent integration attempts identified Docker's old default client API and
stale generated V48/V49 migration files in target, not source changes. The
existing documented API1.44 override and a clean build resolved these. A clean main
archive in /tmp independently passed the migration test; no migrations were edited.

## Not verified / remaining limitations

- Full deployed managed-launcher/UID/authorized-session end-to-end inventory was
  not run. The native smoke used the real installed binary with an isolated HOME;
  production retains the existing managed starter and lifecycle.
- Native Tools remain UNAVAILABLE under the execution-scoped grants contract.
- Agents/Flows have no native inventory operation in the verified protocol.
- A new runtime version requires canonical-path/protocol validation before enabling
  its inventory adapter capability. Existing inference support is unchanged.
- Existing service identity must be configured; no credential provisioning or
  workstation service deployment was performed.

## Execution rulings

- Regular branch instead of worktree follows explicit user instructions; unrelated
  local files were preserved. Cost: shared-checkout isolation is not automatic.
- Reused GitRepositoryPort name resolution because repository links store URLs.
  Cost: inventory becomes unavailable if trusted workspace resolution fails.
- Pinned canonical-path contract instead of opening runtime HOME or adding a
  privileged metadata API. Cost: unverified native versions remain unavailable.
- Preserved MCP grants rather than creating discovery executions. Cost: Tools
  inventory is unavailable until a safe native/context contract exists.
- Reused service identity independently of Remote Access activation and shared its
  bind guard. Cost: identity and explicit loopback settings remain prerequisites.
- Native smoke and browser fixture prove their stated scopes, not deployment.
  Cost: full deployed managed-runtime integration remains unverified.

## Changed files

- `docs/ai-library-runtime-evidence.md`
- `docs/superpowers/plans/2026-10-21-ai-library.md`
- `docs/superpowers/specs/2026-10-21-ai-library-design.md`
- `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/library/AiLibraryController.java`
- `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/library/AiLibraryResponse.java`
- `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/remoteaccess/RemoteAccessServiceFilter.java`
- `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/security/AgentManagementRoutePolicy.java`
- `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/library/AiLibraryControllerTest.java`
- `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/library/GetAiLibraryUseCase.java`
- `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/library/GetAiLibraryUseCaseTest.java`
- `services/forge-agent/boot/src/main/java/com/sitionix/forgeagent/AiLibraryManagementConfiguration.java`
- `services/forge-agent/boot/src/main/java/com/sitionix/forgeagent/RemoteAccessManagementConfiguration.java`
- `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/AiLibraryManagementConfigurationTest.java`
- `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/ForgeAgentAiLibraryIT.java`
- `services/forge-agent/domain/pom.xml`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/AgentDescriptor.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/FlowDescriptor.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/InventoryDiagnostic.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/InventoryResult.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/InventoryStatus.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/LibraryCategories.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/LibraryContext.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/LibraryProvenance.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/LibraryProviderContext.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/LibrarySnapshot.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/SkillDescriptor.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/library/ToolDescriptor.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/library/AgentInventoryPort.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/library/FlowInventoryPort.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/library/LibraryProviderSession.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/library/LibraryProviderSessionFactory.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/library/SkillInventoryPort.java`
- `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/library/ToolInventoryPort.java`
- `services/forge-agent/domain/src/test/java/com/sitionix/forgeagent/domain/model/library/InventoryResultTest.java`
- `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexAppServerClient.java`
- `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexLibraryCapabilities.java`
- `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexLibraryMetadataMapper.java`
- `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexLibrarySessionFactory.java`
- `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexLibraryInventoryTest.java`
- `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexNativeLibrarySmokeTest.java`
- `services/forge-console/src/models/ai-library.ts`
- `services/forge-console/src/operator/active-profile-invalidation.d.ts`
- `services/forge-console/src/operator/active-profile-invalidation.js`
- `services/forge-console/src/operator/agent-projects-api.js`
- `services/forge-console/src/operator/agent-projects-page.js`
- `services/forge-console/src/operator/agent-projects.html`
- `services/forge-console/src/operator/ai-library-controller.d.ts`
- `services/forge-console/src/operator/ai-library-controller.js`
- `services/forge-console/src/operator/ai-library-view.d.ts`
- `services/forge-console/src/operator/ai-library-view.js`
- `services/forge-console/src/operator/ai-runtime-view.js`
- `services/forge-console/src/operator/operator-ui.css`
- `services/forge-console/src/operator/project-dashboard-view.js`
- `services/forge-console/tests/agent-projects-page.test.ts`
- `services/forge-console/tests/ai-library-controller.test.ts`
- `services/forge-console/tests/ai-library-view.test.ts`
- `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiInfrastructureAgentsExceptionHandler.java`
- `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/ForgeAiLibraryController.java`
- `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/library/AiLibraryResponse.java`
- `services/forge-nexus/api-rest/src/test/java/com/sitionix/forgeai/api/ForgeAiInfrastructureAgentsExceptionHandlerTest.java`
- `services/forge-nexus/api-rest/src/test/java/com/sitionix/forgeai/api/ForgeAiLibraryControllerTest.java`
- `services/forge-nexus/application/src/main/java/com/sitionix/forgeai/application/usecase/GetAiLibraryUseCase.java`
- `services/forge-nexus/application/src/test/java/com/sitionix/forgeai/application/usecase/GetAiLibraryUseCaseTest.java`
- `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/library/AiLibraryClientAdapter.java`
- `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/library/AiLibraryHttpTransport.java`
- `services/forge-nexus/clients/agent-client/src/test/java/com/sitionix/forgeai/infrastructure/agentclient/library/AiLibraryClientAdapterTest.java`
- `services/forge-nexus/clients/agent-client/src/test/java/com/sitionix/forgeai/infrastructure/agentclient/library/AiLibraryHttpTransportTest.java`
- `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/model/library/LibraryProviderContext.java`
- `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/model/library/LibrarySnapshot.java`
- `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/port/AiLibraryClient.java`
- `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/usecase/GetAiLibrary.java`
