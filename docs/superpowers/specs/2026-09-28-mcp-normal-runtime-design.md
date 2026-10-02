# MCP normal runtime and Settings product flow

The user approved normal MCP startup and subsequently explicitly removed Forge
operator authentication and internal Nexus → Agent bearer authentication from this
task. This amendment supersedes the former combined-session/security-file design.
There is no local/remote mode, compatibility switch, or replacement feature flag.

## Product contract

`git pull → just start → :9099 Settings → Integrations → MCP` works directly.
A fresh connection inventory returns HTTP 200 with `[]`. Console loads it without
login, cookie, CSRF token or operator credential. Errors remain scoped to the MCP
card; the sidebar, Projects, Settings and Add integration remain visible.

Catalog stays `Console → Nexus → Agent → official MCP Registry → one page`.
Nexus uses its existing typed Agent HTTP client and scoped MCP error handler.
Agent uses its existing typed Registry HTTP client and bounded five-minute cache.
No HTTP stack, framework, extra endpoint or subsequent stage is introduced.

## Ownership

Main Agent and Nexus always compose MCP. The removed global activation setting
has no replacement. Per-connection `enabled` is unchanged. Delete the independent
Forge operator session/filter/controller and Agent management bearer filter;
delete typed-client bearer injection and their secret-file provisioning.
Remote Access retains its own scoped existing security and pairing/SSH protocol.
It neither authenticates MCP nor controls general Console availability. Dedicated
Remote Access processes use narrow Spring composition roots in the existing boot
artifacts, selected by the existing systemd units, without general MCP routes.

External MCP provider credentials, AES-GCM encryption, runtime grants, isolation,
project access, tool approvals, schema checks and revocation remain intact. They
are distinct from the removed Forge login and internal service bearer.

## Persistent runtime prerequisites

Normal systemd installation retains the existing protected AES-GCM key ring and
database password. Missing material is created once with restrictive modes;
existing valid material is preserved without rotation. No MCP operator/service
secret or Nexus authentication environment is generated. Existing unused files
are not overwritten or rotated, and unrelated Remote Access secrets are untouched.

The existing isolated Codex/Git launcher, runtime identity, trusted binaries,
systemd ownership and managed workspace remain mandatory. Workspace adoption
preserves source changes and rejects reserved metadata conflicts. The reserved
clone-attempt parent retains mode 2750, as required by the existing Java adapter.
No personal Codex configuration, credentials or history is copied.

## Verification

Use typed ForgeIT contracts for unauthenticated MCP/catalog CRUD and upstream
errors; preserve provider-secret canaries and scoped Remote Access regressions.
Verify Console, full Agent/Nexus reactors and runtime Python tests. Run actual
`just stop → just start`, inspect generated/effective configuration and new service
timestamps, then real Chrome against built assets on :9099 without an HTTP stub.
Repeat normal start and compare the existing key/database material byte-for-byte.
Record exact PASS/FAIL/NOT_VERIFIED in
[normal-runtime-evidence.md](../../mcp-integrations/normal-runtime-evidence.md).
No PR metadata, comments, reviews or merge changes.
