# MCP Normal Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task in the existing checkout. Do not create a worktree or delegate unless the user requests it.

**Goal:** Make authenticated MCP Settings work after normal `just start`, with persistent protected prerequisites and no global MCP activation switch.

**Architecture:** Main Spring Boot applications always compose MCP. Dedicated Remote Access processes select narrow application entrypoints in their existing boot artifacts. Existing systemd installation provisions the protected files and runtime isolation; Settings reuses the Console design system and existing management flows.

**Tech Stack:** Java 21, existing Spring Boot/Spring typed HTTP, PostgreSQL, existing MCP SDK 0.18.4, Python 3, systemd, existing Console JavaScript/Vitest/Chrome.

**Spec:** [2026-09-28-mcp-normal-runtime-design.md](../specs/2026-09-28-mcp-normal-runtime-design.md), approved by the user on 2026-09-28.

## Global Constraints

- Use regular branch `feature/SITIONIX-154`; preserve unrelated changes.
- No replacement boolean, profile, rollout/environment activation switch.
- Preserve `connection.enabled`, AES-GCM, protected files, runtime UID isolation, grants, revocation, project/tool/schema policy, CSRF and typed APIs.
- No new database table/schema, management endpoint, IAM framework, HTTP stack or frontend framework.
- Never regenerate existing valid keys/credentials on repeat startup; never expose secrets in configuration values, command arguments, URLs, logs or browser storage.
- No PR creation, metadata/comments/reviews changes or merge. Fresh CI must reference the implementation commit.
- Real local acceptance targets Ubuntu/systemd on port 9099; macOS isolation remains NOT_VERIFIED rather than silently using an unsafe fallback.
- Implementation/local verification completed; actual results and limits are recorded in [normal-runtime-evidence.md](../../mcp-integrations/normal-runtime-evidence.md). Checkboxes below describe the approved steps, not independent evidence.

## Review Focus

- Interrupted or concurrent preparation must not overwrite an existing encryption key: exclusive creation and repeat/partial-install tests in Task 3.
- A new application entrypoint must not be discovered by the other application's component scan: packaged entrypoint/context route tests in Task 1.
- Existing local edits in managed repositories must survive workspace adoption: source/destination and conflict tests in Task 4.
- A public Settings asset must not bypass operator guards or turn a failed backend request into empty success: auth/section-error tests in Tasks 2 and 5.
- Healthy services from a previous build must not qualify as successful normal startup: restart and exact generated configuration checks in Task 6.

## Task 1: Give dedicated Remote Access explicit application ownership

**Files:**
- Create Agent `services/forge-agent/boot/src/main/java/com/sitionix/forgeremote/agent/RemoteAccessAgentApplication.java` and Nexus `services/forge-nexus/boot/src/main/java/com/sitionix/forgeremote/nexus/RemoteAccessNexusApplication.java`.
- Modify Agent/Nexus `boot/pom.xml`, `config/systemd/forge-remote-agent.service.in`, `config/systemd/forge-remote-nexus.service.in`.
- Modify existing Agent `boot/.../RemoteAccessManagementConfiguration.java` and Nexus `boot/.../RemoteAccessOperatorConfiguration.java`, API `remoteaccess/RemoteAccessSecurityConfiguration.java` only to separate composition-owned auth wiring.
- Create respective boot `RemoteAccessApplicationCompositionTest.java`; update current Remote Access config/HTTP ForgeIT as needed.

**Interfaces:** Dedicated applications expose existing Remote Access APIs and health only. Main `ForgeAgentApplication`/`Application` retain their entrypoints and complete functionality. The archive launcher selects the dedicated application class in the dedicated unit, not a feature property.

- [ ] Write composition regressions asserting dedicated roots contain the existing RA controller/security/services and exclude MCP controllers, general Agent worker/Codex starter and general Nexus Agent adapters. Assert main scanning excludes the dedicated root packages.
- [ ] Run those regressions on the old composition; capture the failure proving the dedicated process currently uses the general application.
- [ ] Compose RA application/API/local RA packages, RA JDBC adapters and installation identity explicitly with Spring imports/scans; retain existing auto-configuration and Flyway. Import shared infrastructure individually where needed (Clock, transaction support, existing client properties/REST support), not full general application packages.
- [ ] Select the dedicated class with Spring Boot's supported archive launcher in each dedicated unit; verify packaged startup rather than merely creating an annotation context.
- [ ] Extract narrowly shared RA auth/bind wiring if necessary so dedicated RA retains its scoped session/guard and main combined mode retains one operator session. Select ownership through the composition, not a new property switch. Existing RA enablement settings remain RA settings.
- [ ] Run focused RA config, auth, alternate-path and typed endpoint regressions. Record exact skips; commit this independently reviewable boundary.

## Task 2: Remove global MCP disabled mode from main applications

**Files:**
- Modify Agent/Nexus `boot/src/main/resources/application.yml` and `api-rest/.../security/McpManagementProperties.java`.
- Modify all currently conditional MCP controllers/configurations/services, Agent `AgentMcpProtectedConfiguration.java`, `mcp/AgentMcpGatewayConfiguration.java`, Nexus `ForgeAgentHttpClientConfiguration.java`, and combined session/credential configurations.
- Modify Agent `infrastructure/local/.../runtime/{RuntimeBoundaryProperties,RuntimeBoundaryVerifier,RuntimeProcessLauncher}.java`, Codex starter, Git runner, local workspace/Docker adapters and their tests.
- Delete Agent `AgentMcpDowngradeConfiguration.java`, `McpDowngradeGuard.java`, Nexus `NexusMcpDowngradeConfiguration.java`, and tests solely about global disabled/downgrade mode.
- Remove `McpConnectionRepository.hasRetainedCredentials()` and its PostgreSQL/in-memory implementations and downgrade-only tests/dependencies.
- Update existing MCP config/guard/ForgeIT/joined fixtures; replace `McpGatewayDisabledConfigurationTest` with unconditional-composition coverage.
- Update ordinary Agent/Nexus ForgeIT fixtures as well: their positive requests now need the normal service/operator authentication. Use the existing typed endpoint contracts and managers; do not modify generated manager implementations or bypass production filters.

**Interfaces:** `RuntimeBoundaryProperties(String helper)` has no enablement member. `RuntimeProcessLauncher` never selects a same-UID fallback. Existing Agent service credentials are mandatory typed-client dependencies on main Nexus. Per-connection enable APIs and all request/response DTOs stay unchanged.

- [ ] Write a main-composition regression with valid disposable prerequisite files but no activation property; assert MCP controller/client/gateway registration and protected file checks. Capture RED on the old conditions.
- [ ] Remove conditions, YAML mapping, property fields and old downgrade-only code; remove `@DependsOn` references to deleted guards.
- [ ] Remove runtime `enabled()` branches, disabled constructors/fallbacks and their consumers. Always verify isolation and use the managed launcher for Codex/Git; update unit tests to inject/mock their actual dependency rather than a disabled mode. Preserve fail-closed local Compose handling and safe descriptor-based cleanup.
- [ ] Make general Nexus HTTP/SSE clients always use `AgentServiceCredential`; retain redirect policy, timeouts, typed transport wrapper, executor and scoped MCP error handler.
- [ ] Keep combined management auth using the existing RA owner when composed on main. Separate dedicated RA exclusion from main MCP availability. Preserve distinct service audiences and wrong-credential/Origin/CSRF/alternative-route denials with zero upstream calls.
- [ ] Remove all global MCP activation overrides, including child Nexus arguments in `McpSettingsAcceptanceHttpTest`; keep ordinary test-specific ports, DBs and synthetic files.
- [ ] Supply ordinary positive ForgeIT cases with provisioned synthetic credentials and actual authenticated requests/session cookies through existing endpoint contracts. Negative auth cases remain unauthenticated/wrong-credential cases with zero upstream calls. Do not globally auto-authenticate a negative test or disable its filter.
- [ ] Run focused MCP configuration, runtime launcher/starter/Git/workspace, error-boundary and Agent/Nexus management ForgeIT tests. Commit the removal without introducing replacement switches.

## Task 3: Provision persistent prerequisites through normal systemd installation

**Files:**
- Create `scripts/runtime/prepare_mcp.py` and `scripts/runtime/tests/test_mcp_provisioning.py`.
- Modify `scripts/systemd/install.sh`, `scripts/systemd/render-units.sh`, `scripts/runtime/run-agent.sh`, and the main Agent/Nexus systemd templates.
- Reuse `scripts/runtime/forge-runtime-launcher.py`, existing runtime launcher configuration format and `config/sudoers/forge-runtime.in`; remove the obsolete opt-in-only isolation drop-in contract.

**Interfaces:** `prepare(root: Path, control_uid: int, control_gid: int, database_credential: bytes, operator_origin: str) -> dict[str, Path]` creates/validates protected MCP material. File paths are returned to environment rendering; secret values are not. A separate root installer step installs the reviewed launcher, validated sudoers and fixed runtime configuration/accounts/resources.

- [ ] Add synthetic-file regressions: initial 0600 material, correct ownership, distinct operator/service values, valid key ring, same Agent audience for Nexus, restrictive umask, byte-identical repeat setup and preservation after an interrupted partial setup.
- [ ] Add rejection tests for symlinks, hardlinks, unsafe modes/owners/parents, invalid existing key/credential and conflicting service copies. Assert failure does not modify existing bytes.
- [ ] Run the new tests RED before implementing preparation.
- [ ] Implement exclusive no-follow creation and existing-file validation. Import the active configured database password into its protected file once; do not generate a new password for an existing PostgreSQL installation or print it. Prepare shared operator authority from the current owner when applicable.
- [ ] Wire preparation into existing install before unit restart. Render only credential/key/database file paths and explicit main origin. Remove plaintext main DB credential forwarding from generated main environment/run-agent arguments.
- [ ] Install/preserve the dedicated runtime account without privileged/supplementary groups, stable 0700 HOME/CODEX_HOME, root-owned helper, fixed configuration and isolated Codex base/system config. Copy only required installed software/resources; never personal Codex auth/history/config. Validate sudoers before installing it. Main Agent can use only the existing narrow runtime sudo route; child units keep containment.
- [ ] Run provisioning tests and existing runtime/systemd rendering/install regressions with disposable directories. Record mocked-unit limitations; commit.

## Task 4: Provision a trusted managed workspace without losing existing content

**Files:**
- Modify Agent `infrastructure/local/.../{ForgeRootResolver,LocalProjectWorkspaceAdapter}.java` and their tests; Agent `boot/src/main/resources/application.yml`.
- Create `scripts/runtime/prepare_workspaces.py` and `scripts/runtime/tests/test_workspace_adoption.py`; wire into existing systemd installation/preparation.

**Interfaces:** Main `forge.agent.workspace-root` is a path to the managed directory, default provisioned under `/srv/forge/workspaces/forge-projects`; it is not an activation setting. `adopt(source: Path, destination: Path) -> None` runs file copying under the control identity after privileged allocation of the trusted parent/destination.

- [ ] Add workspace-resolution tests independent of the source checkout and adoption tests preserving modified/untracked repository files and the original source. Test repeat adoption, existing conflicting destination, source/destination symlink and interrupted-copy handling.
- [ ] Run RED with current checkout-derived resolution and absent adoption.
- [ ] Make the existing workspace adapter use the configured managed path throughout project/repository/clone-attempt resolution and containment validation. Retain no-follow descriptor cleanup and protected parent requirements.
- [ ] Allocate trusted ancestors with root ownership, control-owned managed root and runtime group using existing required 2750/2770 parent/checkout boundaries. Do not chmod/chown personal HOME or the source checkout.
- [ ] Adopt only the old managed `forge-projects` subtree while services are stopped: preserve source, copy through no-follow traversal as control user into a staging destination, refuse overwrite/conflicts, then publish the complete destination. Never follow copied symlinks into external data or run privileged recursive copying of runtime-controlled content.
- [ ] Inspect durable references before adoption: `ProjectRepositoryCloneAttempt` paths are currently transient. Add regression coverage for any persisted old-root references found; if an unsupported active execution/reference prevents safe adoption, fail with a scoped diagnostic rather than losing data or fabricating success.
- [ ] Run workspace and Git/runtime regressions; verify existing project records/listing stay unchanged. Commit.

## Task 5: Present Settings as an integrations page

**Files:**
- Modify `services/forge-console/src/operator/{settings.html,settings-page.js,mcp-connections-view.js,operator-ui.css}`.
- Modify `services/forge-console/tests/settings-page.test.ts` and related view tests; extend `scripts/mcp-settings-browser-smoke.mjs` only for actual-runtime read-only empty-state assertions.

**Interfaces:** Existing DOM IDs, management API calls, form behavior and shared `initSidebar` stay compatible. Exact empty-state text: `No integrations connected`; primary action: `Add integration`; description: `Connect external MCP tools.`

- [ ] Add RED tests asserting compact Settings header → Integrations → MCP section, empty state/Add action and errors contained within MCP while shared navigation remains intact.
- [ ] Reorganize current HTML into one MCP integrations panel; scope existing login/error/notice/management/details controls to that panel. Keep existing dialogs and explicit enable/disable behavior.
- [ ] Update empty-state copy and focused styles using existing tokens/panels/buttons. Preserve visible Projects/Jarvis/Remote Access/Knowledge/Settings navigation and active Settings state. Do not mask upstream 404 as empty success.
- [ ] Run focused page/form/view/navigation tests, then Console typecheck/test/build. Commit.

## Task 6: Prove normal runtime and record exact evidence

**Files:**
- Create `scripts/runtime/mcp-normal-runtime-acceptance.py` using normal generated systemd configuration and existing auth/API contracts; add focused harness tests under `scripts/runtime/tests`.
- Modify joined `McpSettingsAcceptanceHttpTest.java` to use normal provisioned prerequisites without activation overrides; retain gateway fixtures and secret/permission regressions.
- Update `docs/mcp-integrations/stage-1-operations.md`, mark superseded historical global-mode instructions obsolete, and create `docs/mcp-integrations/normal-runtime-evidence.md`.

**Interfaces:** Acceptance reads protected generated paths without printing secrets, authenticates the real Nexus operator route, checks the real Agent/Nexus/Settings endpoints, and invokes Chrome with real built assets. A disposable fresh DB may be selected through ordinary DB configuration; no alternate activation path or stub is accepted.

- [ ] Add an acceptance regression that refuses enable overrides, missing production provisioning output, stub endpoints or reuse of old health as proof of a restart. Assert expected unauthenticated denial, authenticated list and section behavior.
- [ ] Run focused changed Java/ForgeIT and Python/Console tests before full verification.
- [ ] Run `npm --prefix services/forge-console run typecheck`, `npm --prefix services/forge-console test`, and `npm --prefix services/forge-console run build`.
- [ ] Run `mvn -B -ntp -Dapi.version=1.44 -pl services/forge-agent/boot -am verify` and the equivalent Nexus boot command using current dependency access, without personal Maven configuration changes.
- [ ] Run `python3 -m unittest discover -s scripts/runtime/tests -p 'test_*.py' -v` and `git diff --check`.
- [ ] Run the requested whole-repository removed-switch grep; require no production/config/test matches, no equivalent replacement, and only clearly obsolete historical documentation if any retained matches remain. Update operational instructions to normal provisioning.
- [ ] With usable system privileges, run actual `just stop` then `just start`; verify all normal services, main health, Settings HTTP and MCP operator session/list on 9099. Re-run provisioning/start and prove protected material remains byte-identical without exposing it. No connections are deleted to obtain an empty state.
- [ ] Run real Chrome desktop acceptance against actual `:9099` with a fresh disposable DB where required: sidebar/Projects/active Settings, MCP section, empty state, Add action, sane layout and unchanged port. Retain the normal existing database afterwards; never substitute mocked acceptance for this result.
- [ ] Record `NORMAL_JUST_START`, `MAIN_NEXUS_HEALTH`, `MAIN_AGENT_HEALTH`, `SETTINGS_HTTP`, `MCP_SESSION_ROUTE`, `MCP_CONNECTION_LIST`, `EMPTY_STATE`, `REAL_BROWSER`, `GLOBAL_SIDEBAR`, `PROJECTS_STILL_VISIBLE`, `NO_MCP_ENABLE_FLAG`, `FULL_AGENT_VERIFY`, `FULL_NEXUS_VERIFY`, `CONSOLE_TESTS`, `RUNTIME_TESTS`, `CI`, each with PASS/FAIL/NOT_VERIFIED and concrete evidence/limits.
- [ ] Commit final implementation/evidence. Push the task branch if needed for exact-commit Build `workflow_dispatch`; wait for all fresh jobs. Missing/incomplete CI remains NOT_VERIFIED. Do not create or modify a PR.
- [ ] Stop for user review; do not merge.
