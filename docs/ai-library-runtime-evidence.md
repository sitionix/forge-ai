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
Library sends only initialize/initialized, skills/list and bounded
threadless mcpServerStatus/list; it never starts a
thread/turn, executes a skill/tool, or issues MCP grants. Existing model/list-only
request allowlist remains intact.

| Category | Current Codex result |
| --- | --- |
| Skills | Native skills/list, unified provider-global plus selected-project contexts |
| Agents | Bounded read-only project `.codex/agents/*.toml` definitions; no runtime agent-list RPC |
| Flows | UNSUPPORTED for verified 0.163.0 protocol |
| Tools | Native threadless mcpServerStatus/list, MCP-only catalog |

The initial implementation assumed MCP discovery required a thread's execution
grants. A fresh 0.163.0 schema inspection and isolated native probe disproved that
assumption: `mcpServerStatus/list` accepts no threadId and returns MCP catalog
metadata without a thread/turn or tool execution. The query now uses only this
native read-only operation, not execution grants or Forge MCP database inventory.

`MCP_TOOLS_ONLY` explicitly describes coverage. Built-in Codex tools are not
exposed by this inventory API; a supported empty MCP list never claims that Codex
has no built-in tools. Dashboard counts say MCP tools and Library explains scope.
Flows remain UNSUPPORTED and were explicitly deferred by the user. Agents use native project definition files, without claiming a complete built-in/user-wide agent catalog. User explicitly requested native resources only; Forge
Agents, Workflows and configured MCP catalog entities are not substituted.

The production launcher forbids arbitrary mcp_servers/plugins in base/project
configuration and does not add execution-scoped MCP configuration to neutral
inventory processes. Therefore production may correctly return EMPTY/0 MCP tools.
The synthetic native MCP fixture proves protocol support, not availability of
personal, Forge-configured, or execution-scoped servers in that managed process.
Actual deployed queries additionally returned 419 native tools; the inspected
item identified its source as `MCP · codex_apps`. Provider-native catalogs can
therefore be present without adding arbitrary MCP configuration. This observed
count is account/runtime dependent and is never hardcoded.

Tool pagination is limited to 20 pages of 100 servers and 10,000 entries, within
the existing shared 30-second inventory deadline. Per-server and per-tool errors
retain usable metadata from other sources/pages; repeated cursors and limits are
explicit incomplete failures. Native tool schemas, endpoint/config/auth metadata
and credentials are never projected into Library DTOs.

Canonical native skill metadata is capability-gated to exactly **0.163.0**;
unverified versions return UNAVAILABLE/RUNTIME_PROTOCOL_UNVERIFIED for runtime
categories. Project agent definitions remain readable independently of runtime startup. This affects inventory only, not existing model/execution support.
Requests have a 30-second inventory deadline, existing transport frame limit,
32 managed CWD limit and 10,000-entry processing bound. Limit failures are explicit.
Nexus caps response bytes at 8 MiB and the complete HTTP response at 45 seconds,
including streaming body. Cancellation does not drain oversized response tails.
Snapshots are not persisted; immediate navigation reuse expires after 30 seconds.

## Authorization and provenance

Internal Agent query reuses the installer's protected local service identity,
`FORGE_CODEX_SERVICE_TOKEN_FILE` (default `/etc/forge-ai/codex-service/token`).
Both services accept `forge.ai-library.service-token-file` as a deployment
identity override. Existing Agent `forge.agent.remote-access.service-secret-file`
and Nexus `forge.remote-access.service-secret-file` remain legacy fallbacks;
their original protected-file format is preserved. Library requires explicit
loopback server bind and `server.forward-headers-strategy=none`; it does not
require Remote Access activation. Library and Remote Access filters each own
only their own routes, so independently configured identities do not conflict.
It shares the existing bind/header guard and runtime authorization infrastructure. Missing identity fails closed and
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
- PASS: Console **841 tests / 42 files**, typecheck and production build.
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

- Browser checks now also cover the deployed Nexus with live inventory (see below).
  Error/failure variants and 10,000-item behavior remain deterministic fixtures.
- Native Tools cover MCP catalog metadata only. Built-in tool inventory remains
  unsupported, and execution-scoped MCP catalogs are not included.
- Agents/Flows have no native inventory operation in the verified protocol. Project agent definitions are supported through the native TOML file format; Flows are deferred.
- A new runtime version requires canonical-path/protocol validation before enabling
  its inventory adapter capability. Existing inference support is unchanged.
- No new credentials are provisioned: normal runtime startup already installs the
  service identity. Missing identity still fails closed.

## Execution rulings

- Regular branch instead of worktree follows explicit user instructions; unrelated
  local files were preserved. Cost: shared-checkout isolation is not automatic.
- Reused GitRepositoryPort name resolution because repository links store URLs.
  Cost: inventory becomes unavailable if trusted workspace resolution fails.
- Pinned canonical-path contract instead of opening runtime HOME or adding a
  privileged metadata API. Cost: unverified native versions remain unavailable.
- Preserved MCP grants rather than creating discovery executions. A verified
  threadless metadata query now supports native MCP Tools; execution-scoped
  connections and built-in tool catalogs remain outside its coverage.
- Reused service identity independently of Remote Access activation and shared its
  bind guard. Cost: identity and explicit loopback settings remain prerequisites.
- Native smoke, browser fixture and deployed API smoke each prove their stated
  scopes. Deployed browser verification now covers the Library flow; it is not a claim
  of full application-wide browser E2E.

## Deployed API verification after startup

The first real Nexus query returned HTTP 200 with the correct active profile but
all categories `UNAVAILABLE / RUNTIME_UNAVAILABLE`. The original wiring required
Remote Access credentials, absent from normal runtime installation. The fix uses
the existing installer-owned service identity by default, retaining legacy
configuration validation and keeping the two authentication routes independent.
Regression tests exercise normal runtime identity without Remote Access, legacy
0400/newline format, independent credentials, and strict service token safety.
Maven integration tests explicitly avoid reading the workstation service identity.
Full Agent and Nexus `verify` passed after the fix; independent review accepted
the final source changes. No Console sources changed in this follow-up.

After rebuilding and restarting Agent/Nexus, actual public Nexus queries returned:

| Project | Skills | Agents | Flows | Tools |
| --- | --- | --- | --- | --- |
| Ancestor | AVAILABLE, 5 | UNSUPPORTED | UNSUPPORTED | UNAVAILABLE |
| Dashboard smoke | AVAILABLE, 5 | UNSUPPORTED | UNSUPPORTED | UNAVAILABLE |

Both snapshots used Knowledge's active revision 2, `codex / gpt-5.6-sol`, and
returned real native skills: `imagegen`, `openai-docs`, `review-agent`,
`skill-creator`, `skill-installer`. Tools diagnostics were
`MCP_EXECUTION_CONTEXT_REQUIRED`. Responses took about 7.8s and 2.6s respectively;
these are observations, not latency guarantees. No project files or provider
selection were changed for the smoke. Matching skill lists do not themselves prove
project-local differences; isolated native A/B fixtures cover that case.
Unauthenticated internal Library POST returned 401; a public request for an
unknown project returned 404.

## Library UX follow-up

Replaced nested Dashboard-style cards with a scoped flat list: consistent spacing,
subtle row separators, readable sans-serif body text, concise description previews,
and complete descriptions in details. Search, category counts/statuses and native
route links remain functional. Single-page pagination is hidden; empty/error
states explain recovery and errors remain announced through the live status region.

- PASS: Console 839 tests / 42 files, typecheck, build and git diff check.
- PASS: independent review, including the loading-to-error accessibility fix.
- PASS: actual Chrome against deployed Nexus and native inventory: direct detail
  reload, return to list, search/no-results/clear, unsupported category, Refresh,
  Browser Back/Forward, desktop and 390px mobile layout. Both widths showed five
  native skills without document horizontal overflow. Temporary viewport reset.
- Nexus rebuilt with final assets and restarted; Agent/runtime source unchanged.
- Screenshot evidence: `/tmp/forge-ai-library-ux-desktop.jpg` (session-local).

## Native Tools follow-up

- Fresh installed binary: Codex 0.163.0; regenerated experimental JSON schema.
- Isolated native probe returned one real fixture MCP tool without threadId;
  tool executions and model requests both remained zero.
- Native JUnit smoke now covers both skill canonical/project behavior and
  threadless MCP discovery. The fixture HTTP server accepts metadata requests only.
- Deterministic Tools tests cover pagination/cursor and entry limits, duplicate
  display names, supported-empty vs unsupported, sensitive metadata redaction,
  malformed-server isolation, partial page/server failures, cleanup and the
  continued rejection of arbitrary client.request inventory RPC calls.
- Nexus real-HTTP composition and Agent protected query tests carry the MCP-only
  coverage marker and project-scoped tool IDs through the typed boundary.
- Agents and Flows remain native-unsupported; UI says catalog unavailable rather
  than implying that the provider cannot execute agents or flows.
- PASS: final full Agent verify with both opt-in native smoke tests; full Nexus
  verify; Console 840 tests / 42 files; typecheck; build; independent review ACCEPT;
  git diff check. No schema migration or additional inventory database.
- PASS: actual deployed public API for Ancestor and Dashboard smoke: HTTP200,
  active profile revision2 `codex / gpt-5.6-sol`, 5 Skills AVAILABLE, 419 MCP Tools
  AVAILABLE with MCP_TOOLS_ONLY, Agents/Flows UNSUPPORTED. About 5.4s/1.9s in
  the observed requests; no performance guarantee implied.
- PASS: actual Chrome native Tools view, 100 rendered rows and page2-of5,
  search across 419 tools, details and native MCP source, no execute/create action.
  Screenshot: `/tmp/forge-ai-native-tools.jpg` (session-local).
- Managed Agent and Nexus were rebuilt/restarted; launcher sandbox and MCP grants
  configuration were unchanged. No discovered tools or skills were executed.

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

- `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/security/ServiceTokenFile.java`
- `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/security/ServiceTokenFileTest.java`
- `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/library/ServiceTokenFile.java`
- `services/forge-nexus/clients/agent-client/src/test/java/com/sitionix/forgeai/infrastructure/agentclient/library/ServiceTokenFileTest.java`
- `services/forge-agent/boot/pom.xml`
- `services/forge-agent/api-rest/src/test/java/com/sitionix/forgeagent/api/remoteaccess/RemoteAccessServiceFilterTest.java`
- `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/infra/AgentManagementFixture.java`

- `services/forge-agent/infrastructure/codex/src/main/java/com/sitionix/forgeagent/infrastructure/codex/CodexLibraryToolInventory.java`

- `services/forge-agent/infrastructure/codex/src/test/java/com/sitionix/forgeagent/infrastructure/codex/CodexLibraryToolsInventoryTest.java`


## Native project definitions follow-up

The installed 0.163.0 read-only smoke confirms native `skills/list` discovers
both `.codex/skills/*/SKILL.md` and `.agents/skills/*/SKILL.md` within the selected
managed project. Skills continue using that operation, without a second scanner.

Agents use the official standalone `.codex/agents/*.toml` format documented at
https://learn.chatgpt.com/docs/agent-configuration/subagents. A bundled metadata
reader validates `name`, `description` and `developer_instructions`, returning
only sanitized names/descriptions and opaque resource identities. Instructions
are never executed or exposed; model settings never change Knowledge selection.
`PROJECT_AGENT_FILES_ONLY` makes the coverage explicit in the typed API and UI.
Different files with identical names remain distinct, rather than inventing
runtime precedence for a catalog the runtime does not expose.

The file adapter reads only trusted managed roots through anchored directory FDs,
rejects symlinks, hardlinks and nonregular files, and bounds roots, directory
entries, files, bytes, output and process lifetime. It runs a fixed bundled Python
stdlib parser with an empty environment; no shell or project code is executed.
This metadata reader runs as the control service UID; existing native runtime UID,
sandbox and MCP authorization are unchanged. Missing native folders mean EMPTY,
malformed/unsafe files mean FAILED with any valid entries retained. Excessively
nested TOML and FIFO inputs have focused regressions.

Project-defined MCP servers in `.codex/config.toml` remain blocked by Forge's
existing launcher policy. A server declaration cannot be presented as a tool,
and this feature does not launch arbitrary project commands or create MCP grants.
Only tools returned by the authorized native runtime catalog are listed.


### Project-native follow-up verification (2026-10-21)

- Full Agent `verify` PASS with installed-Codex smoke enabled: 2 native smoke
  tests and 5 project-agent tests pass, including local `.codex/skills` discovery.
- Full Nexus `verify` PASS; focused typed HTTP fixture roundtrip additionally
  passes with `PROJECT_AGENT_FILES_ONLY` and native agent metadata.
- Console 841 tests / 42 files PASS, typecheck PASS, production build PASS.
- Updated Agent/Nexus services restarted successfully. Live public Nexus snapshot
  for Ancestor returns the unchanged Knowledge revision 2/model selection,
  5 Skills AVAILABLE, 0 project Agents EMPTY, 419 MCP Tools AVAILABLE and
  Flows UNSUPPORTED. The inspected managed project has no native agent definitions.
- Chrome against deployed services confirms the scoped empty Agents state,
  native Tools catalog, search, details and source `MCP · codex_apps`.
- Independent review ACCEPT after fixing per-file TOML RecursionError handling.
- `git diff --check` PASS. No push/merge/PR changes.

```text
ACTIVE_LLM_SOURCE=PASS
DDD_BOUNDARIES=PASS
SOLID_PORT_DESIGN=PASS
CODEX_RUNTIME_ADAPTER=PASS
GLOBAL_LOCAL_UNIFICATION=PASS
PROJECT_ISOLATION=PASS
SKILLS_INVENTORY=PASS
AGENTS_INVENTORY=PASS
FLOWS_INVENTORY=UNSUPPORTED
TOOLS_INVENTORY=PASS
AI_LIBRARY_UI=PASS
SECURITY=PASS
NO_HARDCODE=PASS

AGENT_TESTS=PASS
NEXUS_TESTS=PASS
CONSOLE_TESTS=PASS
TYPECHECK=PASS
BUILD=PASS
LIVE_CODEX_SMOKE=PASS
BROWSER_E2E=PASS
DIFF_CHECK=PASS

CHANGED_FILES=See Git branch diff and project-native file list below
SUPPORTED_RUNTIME_OPERATIONS=skills/list,mcpServerStatus/list
UNSUPPORTED_CAPABILITIES=Flows (deferred), built-in Tools catalog, runtime agent-list RPC
REMAINING_FINDINGS=Project Agents coverage is native project TOML definitions only; arbitrary project MCP configuration remains blocked by existing Forge policy; no blocking review findings
```

Project-native file list:
- Agent domain `InventoryDiagnostic.java`
- Codex `CodexProjectAgentInventory.java`, `codex/project_agent_inventory.py`,
  `CodexLibrarySessionFactory.java`
- Codex tests `CodexProjectAgentInventoryTest.java`,
  `CodexLibraryInventoryTest.java`, `CodexNativeLibrarySmokeTest.java`
- Console `ai-library-view.js`, `ai-library-view.test.ts`
- Nexus `LibrarySnapshot.java`, `AiLibraryClientAdapterTest.java`
- This verification evidence document
