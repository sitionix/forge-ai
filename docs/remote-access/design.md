# Remote Access — Stage 0 design checkpoint

Status: **Stage 0 evidence collected; external review pending. Production access is NOT READY.** See [evidence](evidence.md).
Only Stage 0 is authorized. This document proposes the installation and security
boundary; a disposable OS probe is not proof of production session authorization. No access feature
has been installed in Forge. Base: `c62d0bd9` (PR #140), 2026-09-22.

## Intent and fixed constraints

B is GRANTOR; A is ACCESSOR. A runs its own authenticated Codex and reaches B by
SSH. B receives commands, not A's Codex login or history. Reverse access requires
another invitation. Agent owns invitation/session authorization; Nexus maps and
proxies typed contracts; Console renders state. A small privileged supervisor owns
only OS provisioning and process containment. Agent must remain unprivileged.

V1 targets a Linux/systemd grantor and a supported Forge accessor with OpenSSH.
The host passed the disposable combined boundary probe after interactive sudo;
no current environment has passed production grantor qualification. The tested
host is Linux 7.0.0-31-generic / systemd 259; the SSH probe uses a separate Debian
container. Neither macOS nor Windows support is claimed. A container without a
system systemd manager and required isolation capabilities is NOT READY.

No workflow routing, NodeType, MANUAL, repository scope, project SSH profiles,
HTTP remote-shell protocol, SCP/SFTP/PTY, forwarding, or production execution is
added at this checkpoint. Remote edits later use ordinary SSH command streams.

## Actual repository reference points

Paths below are relative to repository root and were read for this checkpoint.

| Reference | Observation / design consequence |
| --- | --- |
| `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/SshConnection.java` | Project-owned profile, password/key-path fields, redacted string form; keep separate from global durable sessions. |
| `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/usecase/SshConnectionUseCases.java` | Project ownership checks and profile probes; no invitation or per-channel authority. Do not use a fake project ID. |
| `services/forge-agent/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/RemoteShellCommand.java` | Existing ssh command does not supply the complete isolated configuration/pinning required here. Do not reuse its policy unchanged. |
| `services/forge-agent/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/TypedProcessExecutor.java` | `output()` waits 15 seconds before draining merged output. Not suitable for long-running commands or independent bounded stdout/stderr. |
| `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/runtime/AgentSessionLeaseService.java` | Repository-enforced owner/token fencing is a useful pattern, not a remote-session aggregate to reuse. |
| `services/forge-agent/infrastructure/postgres/src/main/java/com/sitionix/forgeagent/infrastructure/postgres/adapter/PostgresSshConnectionRepository.java` | Domain port → Spring Data/JPA adapter mapping; retain this layering for future aggregates. |
| `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/` | Forward-only Flyway migrations. No migration in Stage 0; leave V36 and execution-session tables unchanged. |
| `scripts/systemd/install.sh`, `scripts/systemd/render-units.sh`, `config/systemd/forge-agent.service.in` | Current install renders the installing user's service identity; environment file is 0600; Agent has NoNewPrivileges. It does not establish a distinct remote workload identity. Never silently reuse the installing user as workload. |
| `services/forge-nexus/application/src/main/java/com/sitionix/forgeai/application/agentproxy/AgentProjectSshConnectionsUseCase.java` | Delegation-only typed use case. New global API remains a separate typed slice. |
| `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/infra/NexusProxyTestManager.java` | One ForgeIT support manager with MockMvc/WireMock; use typed fixtures in Stage 6. |
| `services/forge-console/src/operator/ssh-profile-flow.js`, `operator-router.js`, `agent-projects-api.js` | Existing SSH UI is project-scoped; add a distinct global Remote Access page only in Stage 7. |
| Agent/Nexus `boot/src/main/resources/application.yml` | Ports configured, no explicit loopback bind in these defaults. Source search found no SecurityFilterChain/CSRF configuration in these services. Do not treat current HTTP endpoints as a verified local operator boundary. |

## Options and selected installation design

1. Reuse the operator's account, SSH profile and repository directory: rejected.
   A workload could read credentials, alter authorization, or use privileged groups.
2. Dedicated control/transport identities plus per-session systemd sandbox units:
   selected for Linux V1. Requires privileged setup and a verified minimal supervisor.
3. VM-per-session: stronger separation but introduces lifecycle/platform scope not
   requested for V1. Use disposable VMs for qualification, not a product VM framework.

The following is a proposed installation contract, **not an installed or validated
configuration**. Failure of any mandatory isolation check must prevent readiness.

| Artifact | Proposed owner / permissions | Purpose |
| --- | --- | --- |
| `forge-control` service identity | unprivileged; no sudo/docker/lxd groups | Agent authority, DB access, local credential store |
| `forge-ssh` account | locked password, no supplementary privileged groups | SSH forced helper only; never the workload UID |
| `forge-work-<session>` | unique unprivileged UID per live session; no login/group grants | Workload only; release UID only after verified cleanup |
| `/usr/libexec/forge-remote/*` | root:root directories 0755, binaries 0755 | Trusted helper/supervisor, never in a writable checkout |
| `/etc/forge-remote/` | root:root 0700 | Dedicated sshd config, host private key (0600), installation manifest |
| `/var/lib/forge-remote/authorized/` | root-owned; readable only by managed sshd path | Dedicated authorization source; no personal authorized_keys edits |
| `/var/lib/forge-agent/remote-access/` | forge-control:forge-control 0700, files 0600 | Instance identity, ACCESSOR key references/material and pinned known_hosts |
| `/run/forge-remote/admin.sock` | root:forge-control 0660; protected parent | Narrow supervisor operations, verified SO_PEERCRED |
| `/run/forge-remote/channel.sock` | root:forge-ssh 0660; separate protected parent | Restricted admission/status/confirm channel, no admin operation dispatch |
| `/srv/forge-remote/workspaces/<session>/` | session workload UID; explicit operator preparation | Only writable working context; no automatic home/repository sharing |

ACCESSOR credentials stay under its local control identity. GRANTOR never stores
a session private key. Ephemeral pairing private material is emitted once and
removed on the grantor. Known_hosts uses the full validated host public key,
not only a fingerprint. Files are created exclusively with restrictive permissions,
atomic replacement and symlink-safe directory handling; cleanup touches only
manifest-owned artifacts. No real secrets are used in probes.

### Isolation and local operator boundary

Run a dedicated managed sshd on an explicitly configured LAN address/port (proposed
2222; preflight must reject conflicts). Keep existing SSH configuration untouched.
Install root-owned helpers from a reviewed packaged artifact, not by executing a
writable repository as root. Narrow privileged setup creates accounts, directories,
units and manifest; runtime Agent is never root.

Workload units must use a minimal prepared RootDirectory/mount namespace, read-only
runtime/toolchain, only the explicit writable workspace, private /tmp and /dev,
ProtectHome, NoNewPrivileges, empty capabilities, RestrictSUIDSGID, protected proc
visibility, and no writable cgroup/systemd DBus. No host /run, Docker socket, DB
socket, Forge config, SSH control files or operator home are mounted. Per-session
UIDs prevent cross-session signal/file access. Mount and network namespaces are
mandatory; ordinary `cd`/forced-command restrictions do not provide this isolation.

Default workload network is a private namespace with loopback only and no host
route. Builds can use prepared dependencies and services inside that context. V1
must report this limitation, not silently expose host/LAN network to make tests
work. Any later egress policy needs separate security review; it is not part of
this checkpoint. In particular localhost inside the workload must not be the
host's loopback or an administrative endpoint.

Management APIs must be loopback-bound plus authenticated as the local operator:
proposed bootstrap secret readable only by the operator, exchanged for a short-lived
HttpOnly SameSite=Strict browser session; never in URLs/storage/logs. Unsafe browser
requests require an exact configured Origin and session-bound CSRF token; deny
untrusted Host/Origin, wildcard CORS and unauthenticated bootstrap requests. Agent
requires a distinct Nexus service credential on its local typed API. Peer keys
are not operator credentials. This is a **future Stage 2/6 boundary requiring
review and live negative tests**, not functionality present in today's Forge.
Root/compromised operator is outside this threat boundary.

### SSH identity and pairing

Dedicated authorized key entries bind immutable invitation/session key identity to
a trusted forced helper. Ignore request-supplied session identity as authentication.
Disable PTY, TCP/Unix-socket/agent/X11 forwarding, user rc and environment injection.
The helper exposes only typed pairing or confirm/status/admission operations allowed
for that authenticated binding and local grantor. Authority unavailable → deny.
Before Stage 5, no arbitrary workload command operation exists.

Client policy: `-F /dev/null`, dedicated identity, `IdentitiesOnly=yes`,
`IdentityAgent=none`, `BatchMode=yes`, password/keyboard-interactive disabled,
`StrictHostKeyChecking=yes`, managed UserKnownHostsFile, GlobalKnownHostsFile
`/dev/null`, no multiplexing, ProxyCommand, forwarding or inherited environment.
Build argv directly and validate endpoint/key formats; do not interpolate them into
a local shell. No automatic remote-command retry after transport failure.

`fgpair_v1_` is a typed base64url envelope, not encryption. Invitation server time
and consumed/cancelled state are authoritative. Default TTL five minutes. Token
contains only ephemeral pairing key plus grantor metadata/pin, never session key
or account credentials. Both sides persist one sessionId; successful confirmation
with the dedicated session key is required before ACTIVE.

### Session containment, revoke and crash design

One root-owned systemd slice per session, with command services underneath, named
from validated internal UUIDs. No manual cgroup directory creation. Supervisor
retains registered unit names and fencing generation; callers cannot supply
arbitrary unit properties/paths/UIDs. `KillMode=control-group`, bounded stop timeout
and SIGKILL escalation cover descendants including setsid. Disable delegation.

Admission is serialized against revoke per session: register the managed unit and
check the current authorization generation before starting it. A stale queued
start cannot run after REVOKING. A short persisted intent/registration transaction
precedes OS actions; reconciliation handles partial completion. Never hold DB locks
during network or stop waits. Proving that race is Stage 5 work.

Persist REVOKING first, deny admissions, remove key authorization, stop all registered
units, verify their cgroups empty and no pending starts, then persist REVOKED.
Stop failure remains REVOKING with safe reason. This does not undo file changes or
retract data already read. ACCESSOR offline revoke remains an unconfirmed intent;
permission-denied is not proof of cleanup, and keys are not restored for an ack.

An authority liveness lease and systemd-bound supervisor lifecycle must stop active
workloads when authority/supervisor dies; systemd stop dependencies plus a bounded
watchdog must cover both termination and hangs. Startup reconciles DB state against
managed units and key artifacts before enabling admissions. Exact watchdog timing
and kill acknowledgement require the combined rootful probe; they are not proven
by the user-manager experiment. Do not advertise readiness until those tests pass.

## Stage 0 exit decision

After interactive sudo, a combined real SSH → stub supervisor → system-systemd
probe passed 40 assertions. RootDirectory, DynamicUser, StateDirectory, read-only
filesystem protections and private network/device namespaces were exercised.
Synthetic control-file/socket/admin-listener access was denied, background/setsid
children remained in their unit cgroup, and stopping A did not stop B. Cleanup
removed only the probe's own units, rootfs and state directories.

The probe used DynamicUser per workload unit, not the proposed production
per-session persistent account lifecycle. It proves distinct actual UIDs and
namespace containment, not UID allocation/reuse across multiple commands/restarts.
The production installation must reserve session identity until every command
unit is gone; that lifecycle still needs the Stage 2/5 tests. Do not equate the
fixture supervisor with the future Agent-backed authorization implementation.

Host sshd remains absent: the disposable image supplied it inside RootDirectory.
Privileged operations ran through an interactive sudo terminal. No host sshd,
personal authorized_keys, production Forge configuration or installed users were
changed. The initial capability limitation and fixture failures are retained in
[evidence.md](evidence.md), alongside the successful final run.

This is a design/evidence checkpoint for external review, not acceptance or
production readiness. Stage 1 remains unauthorized. Production authority,
operator authentication/CSRF, revoke races, watchdog and restart reconciliation
are future stage gates, not claims made by the probe.
