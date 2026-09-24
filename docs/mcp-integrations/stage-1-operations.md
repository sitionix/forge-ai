# Stage 1 — operator prerequisites and runtime boundary

Default: `FORGE_MCP_ENABLED=false`. Runtime and management-auth prerequisites have
passed scoped code review and synthetic tests. The reviewed disposable runtime
probe passed18 assertions with cleanup; see stage-1-evidence.md and raw result.
This does not prove installed sudo routing or production Java deployment, which
remain **NOT_VERIFIED**. No host installation is performed by these source changes.

## Runtime contract

With `forge.mcp.enabled=true`, Codex app-server and every local Git invocation use
`RuntimeProcessLauncher`, never the old same-UID ProcessBuilder branch. Spring
injects the enabled launcher explicitly; legacy constructors preserve off-mode
unit fixtures. A `SmartInitializingSingleton` runs the actual helper probe before
the launcher becomes ready. Missing/unsafe helper, same UID, unavailable systemd,
probe denial failure, or unconfirmed cleanup fail startup with a generic error.

`RuntimeBoundaryVerifier.verifyProtectedPaths(List<Path>)` is the Task 2b entry
point for key/DB/operator/service credential files. It validates regular files,
owner-only mode, one hard link, non-writable trusted ancestors and no symlinks,
then asks the real runtime UID to try read/write access. It never reads or returns
credential contents. Startup without those paths proves the runtime/config boundary,
not that future credential files have been provisioned correctly; Task 2b must
invoke the protected-path verifier for its actual configured files.

`ManagedRuntimeProcess.terminateOwnedUnit()` calls the fixed helper's owned stop
and acknowledges success only after systemd/cgroup confirmation. A pipe exit or a
ProcessHandle is not cleanup proof. Codex transport/recovery use this branch;
Git uses it on success, timeout, failure and interruption. Cleanup remains bounded
and retryable after failure. RuntimeMaxSec=7200 in the example exceeds existing
90-minute turn and 30-minute clone deadlines; allowed configuration range is
5500–14400 seconds. Runtime units bind to the configured Agent service lifecycle.

The helper accepts only `start codex UUID cwd`, `start git UUID argv...`,
`stop UUID`, and `probe` (bounded JSON stdin). Fixed installation UUID plus strict
canonical execution UUID determine unit names. Root-owned receipts serialize
start/stop and reconcile owned previous executions on startup. Truncated receipts
are conservatively treated as executions requiring cleanup. There are no
caller-selected executables, properties, environment variables, PIDs or unit names.

Git's `-C` operands must be canonical managed paths. The helper adds an exact
`safe.directory=<validated -C path>` for shared control-owned checkouts. It never
adds `safe.directory=*` or a subtree wildcard. Repository hooks still execute;
the boundary is runtime UID and service containment, not disabled hooks.

## Packaged artifacts and prerequisites

Review these artifacts; this document is not authorization to install them:

- `scripts/runtime/forge-runtime-launcher.py`: root-owned executable, mode 0755,
  installed as `/usr/local/libexec/forge-runtime-launcher`; fixed
  `/usr/bin/python3 -I` shebang. Root owns every ancestor and none is group/world
  writable or a symlink. The interpreter and fixed executable paths must be trusted.
- `scripts/runtime/runtime-launcher.json.example`: render actual installation UUID,
  existing dedicated control/runtime UIDs and runtime primary GID, fixed binaries,
  managed roots and Agent unit. Install as `/etc/forge/runtime-launcher.json`,
  root:root 0600, trusted ancestors. Example IDs are placeholders, not provisioning.
- `config/sudoers/forge-runtime.in`: render control account, validate with
  `visudo -cf`, install root:root 0440 through separately approved deployment.
  Grant only the exact helper; never Python, shell, systemd-run or systemctl.
- `config/systemd/forge-agent-mcp-isolation.conf.in`: explicit opt-in drop-in for
  the narrow sudo route. It changes Agent `NoNewPrivileges` to false. The default
  Agent unit is unchanged; runtime transient units always retain NNP=true.

Use distinct non-root control/runtime accounts. Runtime must have no supplementary
or privileged primary groups and must not share the control primary GID. No Docker
socket/group, control secrets, personal HOME or general privileged executor is
provided. Runtime services clear the child environment, set only dedicated
HOME/CODEX_HOME, PATH, LANG and noninteractive Git, drop capabilities, protect the
system/cgroup filesystem and control /proc visibility, and stop their whole cgroup.
The helper does not turn on Codex shell network access.

When MCP management is enabled, provision three distinct protected Agent files:
the AES key ring (`active=<id>` and `key.<id>=<base64-32-byte-key>` lines), the
service bearer, and the database password. Nexus uses separate protected operator
bootstrap and Agent service bearer files. Bearers contain at least 32 random bytes
encoded as unpadded base64url. Configure file paths only; do not put credential
values in environment variables, process arguments, URLs, or application YAML.
The Nexus bootstrap and Agent service bearer values must differ.

With MCP enabled alone, an operator sends the bootstrap secret in the JSON body of
`POST /api/v1/operator/session` from the configured browser origin with its exact
`Origin` and `Host` headers. Nexus returns a host-only `FG_SESSION` HttpOnly,
SameSite Strict cookie and a `csrfToken`; the browser sends that cookie on later
requests and `X-Forge-CSRF` plus exact `Origin` for mutations. `GET` on the same
session route returns the current session-bound CSRF value; `DELETE` logs out and clears the cookie.
Sessions expire on the server and restart invalidates them. HTTPS origins set the
Secure cookie flag; plain HTTP is accepted only on a loopback origin. Nexus sends
its separate service bearer to the configured Agent origin for typed REST and log
streaming calls. Do not supply the operator bootstrap or browser cookie to Agent.

MCP connection metadata is managed at Nexus
`/api/v1/infrastructure/agents/integrations/mcp/connections` and Agent
`/api/v1/integrations/mcp/connections`. New connections start disabled. The
only supported transport is `STREAMABLE_HTTP`; credential input is write-only.
Use `credentialChange=REPLACE` with a new bearer or secret headers, `KEEP` to
retain the existing credential during an update, or `REMOVE` to clear it.
Reads expose only `credentialConfigured`. Discovery is not available in Stage 1,
so `allowedTools` must be empty; an empty `SELECTED` project set denies access.

For key rotation, put a new 32-byte AES key in the protected Agent key file as
`key.<new-id>=<base64-32-byte-key>`, retain the old `key.<old-id>` entry, and set
`active=<new-id>`. The cipher rereads the protected key file on each operation;
a restart is recommended to rerun startup verification after controlled
provisioning, but is not required for key lookup. Verify protected-file mode and
runtime read denial before rotation. Existing ciphertext remains under its old key until rotated.
For each credential-bearing connection, an authenticated operator sends
`POST /api/v1/infrastructure/agents/integrations/mcp/connections/{id}/reencrypt`
with exact `Origin`/`Host` and the active mode's session/CSRF: `FG_SESSION` plus
`X-Forge-CSRF` for MCP-only, or `FORGE_REMOTE_OPERATOR` plus `X-CSRF-TOKEN`
in combined mode (see below). HTTP 204 confirms
that single record was reencrypted to the active key; no plaintext is returned.
Verify all retained rows have the new key ID through controlled database metadata
inspection before removing the old key from the protected file and restarting.
If an old key is missing or wrong, the action fails safely and leaves that record
unchanged. Do not remove an old key while any retained ciphertext still needs it.

To pause integrations while retaining credentials, keep `forge.mcp.enabled=true`
on Agent and Nexus and set each connection's `enabled=false`. The global flag may
be changed to false only after an explicitly authorized deprovision: remove MCP
credentials and their metadata, retire protected key/bootstrap/service files and
configured paths, and account for backups. Agent refuses off-mode startup if any
credential row or `credential_configured` flag remains anywhere in its database,
or if a protected Agent path remains configured. Nexus refuses off-mode startup if
its protected credential paths remain configured. A query failure also refuses
Agent startup. No startup path deletes stored credentials. These guards cannot
discover orphan files whose paths were deliberately removed from configuration or
protect an older binary without the guard; rollback and backup deprovision need
separate operator control.

Runtime home must be a stable runtime-owned 0700 directory under a trusted
root-owned parent, e.g. `/srv/forge-runtime/home`. Workspace roots must be disjoint
from it. Provision its dedicated `.codex` child as a runtime-owned 0700 directory
before starting Codex; leave it empty until separately authorized provider setup.
Configured executable paths must resolve to regular canonical files on the target
host: a symlink such as this host's `/usr/bin/env` is rejected by the launcher.
Install the reviewed Codex package with its root-owned adjacent
`codex-resources/bwrap` ELF resource; copying only the Codex binary is insufficient
for `workspaceWrite` execution. The disposable proof pins Codex 0.156.1 and bwrap
SHA256 `77360cb751ccedc5971391444ac86a8a33c15b04d6b4a6fe45f5d25496e62c4c`.
Review the installed package version and resource hash again before a new rollout.
Configured paths and Codex working directories use ordinary absolute
ASCII path components (letters/digits, underscore, dot, dash); spaces, percent
specifiers and symlink paths are rejected in enabled mode.

Provision `<forge-root>/forge-projects` before enabling the launcher. Its ancestors
must be root-owned/trusted for helper validation; the managed root itself is
control-owned, runtime-group, mode 2750. Control must be a member of the runtime
primary group for shared checkout contents. Runtime cannot change parent entries.
The enabled workspace adapter creates control-owned setgid project/attempt parents
2750 and staging checkout directories 2770. Runtime's umask 0007 makes created
files accessible to that shared group. It does not chmod control HOME/config or
make project parents runtime-writable. Existing repositories require explicit
operator ownership/permission verification; no automatic recursive migration runs.

Enabled cleanup uses `SecureDirectoryStream` relative operations with
`NOFOLLOW_LINKS`. A filesystem provider without secure directory descriptors fails
closed. Runtime renaming a nested directory and replacing its pathname with a
control-tree symlink cannot redirect already-open descriptor operations.

## Deliberate limitations

Local Compose discovery, validation and streaming are unavailable in enabled mode,
rejected with a controlled ValidationException before Docker CLI parsing. Container
ID operations and remote SSH Compose paths retain their existing behavior; their
HTTP authentication belongs to Task 2b. The control process must not parse local
runtime-controlled Compose YAML. Ordinary off-mode behavior remains unchanged.

Dedicated HOME preserves future provider auth/history but does not migrate existing
Forge DB thread IDs or personal Codex history. Existing thread IDs may not resume
from an empty runtime home. Provider login/history provisioning and migration are
operator prerequisites and **NOT_VERIFIED**. Do not copy personal Codex home,
auth or config. Complete config/plugin inventory isolation belongs to Stage 4.
No external provider calls are needed for this task's proof.

## Disposable proof and reproduction

Read and review both helper and fixture immediately before privileged execution.
The fixture is `docs/mcp-integrations/probes/stage1-boundary/privileged_boundary.py`.
Use an absolute trusted `/usr/bin/python3 -I`; do not use unisolated root Python
imports or a Python command selected through PATH. Root run follows independent
review and normal interactive authorization; never enter a password into chat.

The fixture creates only random `/run/forge-stage1-<UUID>` state and recorded
transient systemd units. It uses the existing backup identity only while there are
no unrelated backup processes, a synthetic Agent anchor, four synthetic protected
files, a separate synthetic backend process and a copied, hash-pinned Codex ELF.
The account's existing home/files are never read or used. It creates no permanent users/groups, sudoers entries, systemd installation,
network listeners, personal config or host-secret mounts. All runtime outputs are
read with bounded no-follow regular-file descriptors; scripts are prepared before
runtime activity with exclusive descriptor-based writes.

The reviewed current binary is 0.156.1 (Stage 0 used 0.155.1); the fixture pins SHA256
`0b2e9301d6100dddda3b9d5c80ebaeaa3a2f1962388f2f36f6b96a9f08b1f33f` and records the
copied binary version/hash and installed systemd version. Its pre-service `--version`
invocation uses only that reviewed ELF under backup and an empty dedicated HOME.
Changing the binary hash requires a new explicit review, not editing away the check.

Direct root-driver invocation with synthetic SUDO_UID context proves the actual
helper service path, not installed sudoers caller routing. Installed sudo routing,
full Java/backend deployment, live provider login, old-history migration and
reboot/power-loss recovery remain explicitly **NOT_VERIFIED** unless separately run.
A root process killed outside Python finally may leave its own fixture tree;
anchor RuntimeMaxSec=600 plus BindsTo bounds runtime units. After interruption,
inspect only the recorded fixture UUID units and retain artifacts until cleanup is
positively confirmed. `CLEANUP_UNCONFIRMED` is a failure, never a PASS.

Unprivileged checks:

```sh
python3 -m unittest discover -s scripts/runtime/tests -v
python3 -m unittest discover -s docs/mcp-integrations/probes/stage1-boundary/tests -v
mvn -q -pl services/forge-agent/infrastructure/local,services/forge-agent/infrastructure/git,services/forge-agent/infrastructure/codex -am test
```

These tests cover implementation contracts and off-mode regression. Mocked systemd
assertions are never actual isolation evidence. The final Task 2a ledger records
exact completed commands, counts, source hashes, privileged result and remaining
limits before Stage 1 acceptance can be considered.

## Combined Remote Access and MCP management

With both features enabled, configure the existing Remote Access operator secret
file and explicit loopback origin once (`forge.remote-access.operator-secret-file`,
`forge.remote-access.operator-origin`). MCP bootstrap/origin settings are optional;
if supplied they must resolve to that same operator file and equivalent origin.
`forge.mcp.session-ttl` is inactive in combined mode: the existing Remote Access
15-minute absolute session lifetime applies.

Use only `POST /api/v1/infrastructure/agents/remote-access/operator/login` with
`{"secret":"<bootstrap>"}`. Its `FORGE_REMOTE_OPERATOR` HttpOnly/SameSite Strict cookie
covers the Nexus context root (Secure for HTTPS). The returned `csrfToken` goes in
`X-CSRF-TOKEN` for both RA and MCP mutations, together with the exact Origin.
The existing RA `/operator/session` and `/operator/logout` endpoints manage this
single session. `/api/v1/operator/session` does not create a combined-mode session.
MCP-only retains `FG_SESSION`, `X-Forge-CSRF`, and its existing login endpoint,
including HTTPS non-loopback origins; RA-only retains its scoped cookie.

The existing two service files remain distinct: MCP uses
`forge.mcp.agent-service-credential-file` on Nexus and
`forge.mcp.service-credential-file` on Agent; RA uses
`forge.remote-access.service-secret-file` on Nexus and
`forge.agent.remote-access.service-secret-file` on Agent. Operator bootstrap and
both service credential values must all differ. Combined Agent validates the two
service audiences independently and routes RA requests only to the RA guard.
Other control routes remain protected by the MCP service guard. Both combined
listeners retain the RA loopback bind and no forwarded-header trust requirements.

Agent startup passes the active RA service file to the runtime protected-path
verifier alongside the three MCP files. This does not prove runtime denial of
Nexus-side files: deployment verification of all active Nexus secrets, process
and descriptor aliases remains **NOT_VERIFIED** without an actual runtime probe.
