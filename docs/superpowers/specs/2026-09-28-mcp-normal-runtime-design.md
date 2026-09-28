# MCP normal runtime and Settings product flow

Status: approved by the user on 2026-09-28; implementation and local runtime verification completed. See [actual evidence](../../mcp-integrations/normal-runtime-evidence.md).

## Requested outcome

MCP is part of the main Forge application, not a globally optional feature.
`git pull` followed by `just start` provisions its prerequisites and exposes the
authenticated management API behind Settings on port 9099. An authenticated fresh
installation returns an empty connection list. Per-connection enablement remains
an explicit operator decision.

Use the existing Spring Boot composition, typed Nexus ports/clients, PostgreSQL
storage, systemd launcher, and Console components. No replacement feature switch,
new IAM framework, management endpoint, database schema, or frontend framework.
Do not modify PR metadata, comments, reviews, or merge state.

## Observed baseline, 2026-09-28

Checkout: `main`, `0e42368f1f1a469371bce2a603058149fd898cc3`, matching
`origin/main` after fetch. The working tree was clean. Regular implementation
branch: `feature/SITIONIX-154`, derived from local and remote branch identifiers.

Actual local HTTP checks, without configuration overrides:

| Check | Result |
| --- | --- |
| Agent `127.0.0.1:7091/actuator/health` | HTTP 200 |
| Nexus `127.0.0.1:9099/fgaisox/actuator/health` | HTTP 200 |
| Settings `/fgaisox/operator/settings.html` | HTTP 200 |
| `/fgaisox/api/v1/operator/session` | HTTP 404 |
| `/fgaisox/api/v1/infrastructure/agents/integrations/mcp/connections` | HTTP 404 |

These establish the defect; they do not establish a corrected runtime.
`sudo -n true` currently fails because interactive authentication is required.
The four main systemd services are active. No service was stopped or reconfigured
during discovery, and no secret contents were printed.

## Application ownership

Main Agent and Nexus always construct their MCP controllers, application services,
typed clients, security boundary, and gateway. Remove the obsolete global
configuration field, conditions, environment mapping, and downgrade guards.
Remove the repository query used solely to detect a global downgrade and its
consumers. Keep encryption, file validation, connection disable, revocation,
project/tool policy, and schema verification.

The existing dedicated Remote Access Agent and Nexus need explicit, narrow Spring
Boot composition roots in their existing boot artifacts. Their systemd units
select these application entrypoints directly; no boolean, profile, or environment
switch selects general Forge functionality. They compose existing Remote Access
controllers, services, adapters, persistence, auth, health, and recovery only.
They do not scan the full main application, start the general worker, or register
MCP/project/workflow routes. Main application scanning must not accidentally
discover the dedicated composition roots.

Use Spring Boot's supported executable-archive launcher and an explicit application
class for the dedicated units, retaining the current main executable entrypoints.
Do not add a microservice or duplicate the Remote Access implementation.

The main combined deployment retains its single current Remote Access operator
session owner. Main MCP management remains present whether Remote Access is
configured or not. Selecting the existing operator session owner is different
from enabling/disabling MCP. Dedicated Remote Access roots omit the general
combined-session configuration altogether. Route ownership, Host/Origin checks,
CSRF, distinct service audiences, and restrictive bind requirements remain.

## Normal provisioning

Extend `scripts/systemd/install.sh` and its runtime preparation path. Provision
missing protected material before starting the main applications. Environment
files contain paths and origins, not credential contents. Normal Agent startup
reads its database password from the protected file rather than receiving a
plaintext database password through its generated environment or command line.

Provision and preserve:

- Agent AES-GCM key ring, Agent service credential, and database credential.
- Nexus service credential with the Agent audience value, and a distinct operator
  bootstrap credential, using the current Remote Access owner when shared.
- Explicit loopback browser origin for the main Nexus on port 9099.
- The existing root-owned runtime launcher, fixed launcher configuration, narrow
  validated sudoers entry, runtime UID/GID, dedicated runtime home, Codex home,
  trusted runtime binaries/resources and isolated base/system configuration.
- Agent systemd permissions necessary for the existing narrow sudo route; runtime
  transient units retain `NoNewPrivileges=yes` and their existing containment.

Installation is idempotent. Missing secrets are created once with exclusive,
no-follow creation and explicit restrictive modes, independent of umask. Existing
valid files are retained byte-for-byte. Reject symlinks, hardlinks, unsafe
ownership/ancestors, invalid contents, or conflicting existing material. Do not
repair such conflicts by rotating credentials or replacing the encryption key.
Agent and Nexus service files may contain the same audience credential but are
separately protected according to their process owners. Operator and service
credentials must differ. Preserve the active database password; do not rotate
the existing database as an incidental startup change.

The launcher must remain an actual prerequisite. Removing its old disabled
branch must not substitute a same-UID Codex/Git fallback or skip the protected
path probe. Test-only mocks are not runtime isolation evidence.

### Workspace prerequisite discovered during inspection

`ForgeRootResolver` currently derives managed workspaces from the source checkout;
the helper requires trusted ancestors and its runtime units protect home
directories. Simply installing credentials cannot make a checkout under a
personal home a supported isolated workspace.

Introduce an explicit managed-workspace path in the existing workspace
configuration/adapter, provisioned under a trusted system location for the normal
runtime. This is a path, not an activation switch. Preserve project records and
existing managed checkout contents. Do not recursively change permissions on the
source checkout or personal home. Existing workspace adoption needs a bounded,
reviewable migration that preserves the source and refuses unsafe/conflicting
destinations; no silent deletion or re-clone over operator changes. The detailed
implementation plan must cover adoption and references to existing clone-attempt
paths before enabling the new default.

The supported local verification target here is this Ubuntu/systemd host. The
existing launchd backend cannot use the Linux-only launcher or `/proc` verifier;
macOS runtime isolation is not established by this design or by Linux tests.
Do not add a flag or weaken isolation to conceal that limitation. Report macOS
separately as NOT_VERIFIED and resolve supported-platform scope before claiming
universal normal-start acceptance.

## Settings presentation

Reuse `initSidebar` and the existing Console design system. Keep a compact Settings
page header, followed by Integrations and one MCP panel containing the description
“Connect external MCP tools.”, connected integrations or “No integrations
connected”, and the primary “Add integration” action. Keep details and dialogs
within that section. Keep section errors and recovery controls local to MCP;
backend 404 must still be observable rather than masked as an empty list.

Reuse the current operator login and mutation behavior. An installation that
requires operator authentication continues to require it; no automatic login,
browser-persisted credentials, automatic connection enablement, or auth bypass.
Provisioning makes the existing bootstrap file available without manual file
creation; operational documentation explains the owner and protected location.

## Principal existing files

| Boundary | Files |
| --- | --- |
| Main composition | Agent `boot/.../ForgeAgentApplication.java`, `AgentMcpProtectedConfiguration.java`, `mcp/AgentMcpGatewayConfiguration.java`; Nexus `boot/.../Application.java` |
| Obsolete downgrade | Agent `AgentMcpDowngradeConfiguration.java`, `McpDowngradeGuard.java`; Nexus `api-rest/.../security/NexusMcpDowngradeConfiguration.java`; Agent repository `hasRetainedCredentials()` and related dependencies |
| Runtime isolation | Agent `infrastructure/local/.../runtime/{RuntimeBoundaryProperties,RuntimeBoundaryVerifier,RuntimeProcessLauncher}.java`; Codex starter; Git runner; local workspace adapter |
| Security/client | Agent/Nexus `api-rest/.../security/McpManagementProperties.java`, management configurations/controllers; Nexus `ForgeAgentHttpClientConfiguration.java` and existing combined operator configurations |
| Deployment | `scripts/runtime/{prepare,systemd,run-agent,run-nexus}.sh`, `scripts/systemd/{install,render-units}.sh`, `config/systemd/forge-*.service.in`, existing launcher and sudoers template |
| Product UI | `services/forge-console/src/operator/{settings.html,settings-page.js,mcp-connections-view.js,operator-ui.css}` and shared bootstrap |
| Acceptance | Agent `McpSettingsAcceptanceHttpTest.java`, Agent/Nexus ForgeIT auth contracts; Console browser smoke; `scripts/runtime/tests` |

## Regression and acceptance

Start with failing regressions for unconditional main MCP route registration,
normal protected provisioning, repeat-start preservation, dedicated Remote Access
composition, and the Settings empty-state hierarchy. Remove obsolete global-mode
test properties and off-mode tests; retain negative auth, runtime grant denial,
encryption, secret canaries, and per-connection disabled-state coverage.

Normal runtime acceptance must consume the files/config generated by the same
installer used by `just start`. A disposable directory/database is allowed, but
there is no alternate MCP activation path. Validate actual install output, then
run `just stop` and `just start`, check normal service health, authenticate through
the existing operator route, and read the real management list. Do not clear
existing connections to manufacture a fresh empty result; use a disposable fresh
database for that assertion.

Run Chrome against the actual built Settings asset at the real Nexus on port 9099.
Assert visible shared sidebar, Projects, active Settings, MCP section, empty state,
Add action, no unavailable-installation message, correct port, and desktop layout.
No HTTP stub qualifies for this acceptance.

Run the user-requested Console typecheck/test/build, complete Agent and Nexus
verify with API 1.44, Python runtime regressions, and `git diff --check`. Audit the
entire repository for the removed global switch, replacement switches, obsolete
branches, and operational instructions. Clearly label historical designs obsolete.

Fresh CI must match the eventual implementation commit. The Build workflow
supports `workflow_dispatch`; no PR metadata or comments need to change. Existing
main CI is not evidence for this fix.

Record every requested evidence key as PASS, FAIL, or NOT_VERIFIED. Until
implementation and its real acceptance run, all corrected-runtime, browser, full
regression, removal, and fresh-CI claims are NOT_VERIFIED. The baseline 200 health
and asset responses above are not post-fix PASS claims.
