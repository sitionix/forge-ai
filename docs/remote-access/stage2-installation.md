# Stage 2 — managed SSH installation and channel authorization

Historical Stage 2 boundary; see [Stage 3 additions](stage3-invitations.md) for invitation grants. Stage 2 only. No invitation issuance, activation handshake, workload execution,
remote file edits, admin HTTP API, or production supervisor is available yet.
The installer creates an **empty** authorization source and never starts sshd.
A prepared installation is not an ACTIVE access session.

## Supported prerequisites

Linux with a system systemd manager; system OpenSSH sshd/client, Ed25519 support,
`/usr/bin/python3`, useradd/usermod and a dedicated explicit local listen address.
The current live SSH test uses Debian in a disposable Docker container. It tests
actual sshd and installed helpers, not running systemd; the installer correctly
reports NOT READY there after preparing artifacts. No macOS/Windows qualification
or production grantor deployment is claimed.

Ports must be 1024..65535 and available. Port 22, wildcard/multicast addresses and
injection characters are rejected. Stop the **managed** sshd before repeating
installation: preflight refuses an occupied port, including its own running
listener. Existing host sshd and personal authorized_keys remain untouched.

For a Stage 3→4 package upgrade, also stop `forge-remote-invitations.service`
before running setup. systemd removes its `forge-remote/admin` RuntimeDirectory
when stopped. The installer refuses to change supervisor bytes while this
directory exists (including stale/unknown state); replacing a Python file does
not replace an already running interpreter. Verify the managed process is stopped
before investigating a stale directory. Unchanged package bytes remain idempotent.
Restart the supervisor and managed sshd only after successful setup.


## Root-owned setup package

Install the reviewed `scripts/remote-access/install.py` and `forced_command.py`
from this review unit into a root-owned, non-writable package directory, for
example `/usr/local/lib/forge-remote-setup`, with root-owned non-writable ancestors.
Run the protected package, not a writable worktree, with:

```sh
sudo /usr/bin/python3 -I /usr/local/lib/forge-remote-setup/install.py \
  --listen-address <this-machine-LAN-IP> --port 2222
```

No runtime grants or arbitrary commands are provisioned. The installer refuses
conflicting files rather than overwriting them. Repeated setup with the same
configuration preserves host identity, existing managed authorization and
unrelated SSH configuration. A different configuration needs explicit operator
migration; this stage does not silently rotate host identity.

Created artifacts:

| Artifact | Owner/mode | Purpose |
| --- | --- | --- |
| `forge-control` | dedicated unprivileged user, no login | Agent authority/control state |
| `forge-ssh` | separate unprivileged user; unusable password, dedicated `/bin/sh` required by sshd's forced-command mechanism | transport only |
| `/usr/libexec/forge-remote/forced-command` | root:root 0755 | isolated Python helper, no shell dispatch |
| `/etc/forge-remote/` | root:root 0700 | marker, dedicated sshd config, Ed25519 host key 0600 |
| `/var/lib/forge-remote/authorized/keys` | root:forge-ssh 0640, parent 0750 | initially empty public key source |
| `/var/lib/forge-remote/bindings/` | root:forge-ssh 0750 | initially empty immutable key-association records |
| `/run/forge-remote/channel/` | forge-control:forge-ssh 0750 | restricted Unix socket parent |
| `/run/forge-remote/channel/authority.sock` | forge-control:forge-ssh 0660 | created only by explicitly enabled Agent |
| `/run/forge-remote/channel/authority.sock.lock` | forge-control, 0600 | Stage 4 lifetime lock; retained across restarts to prevent competing authority startup |
| `/etc/systemd/system/forge-remote-sshd.service` | root:root 0644 | dedicated sshd, never host sshd replacement |
| `/etc/tmpfiles.d/forge-remote.conf` | root:root 0644 | restores runtime directories and standard root:root 0755 `/run/sshd` after reboot |

`forge-control` has only its own group plus `forge-ssh` for setting the channel
socket group. It receives no sudo/docker/lxd rights. The conventional shared `/run/sshd` directory is provisioned with standard
root:root 0755 permissions and is never removed by this installer. No workload identity/context
is created in Stage 2. Workload isolation remains mandatory before Stage 5.

## Agent wiring

The installer does **not** retarget or restart an existing Forge Agent. Prepare a
dedicated control-owned Forge runtime using the existing systemd renderer's
`FORGE_SYSTEMD_USER=forge-control` and `FORGE_SYSTEMD_GROUP=forge-control` options.
The operator must supply that runtime's DB configuration and protected runtime
paths. Do not copy personal Codex credentials/history or grant privileged groups.
Enable the channel only on that Agent with the Spring property:

```text
forge.agent.remote-access.channel-enabled=true
```

The Stage 2 authority Agent **requires an explicit loopback HTTP bind** whenever
the channel is enabled. Standard Spring Boot `server.address` maps from the
Agent-specific `FORGE_AGENT_HOST` variable. The existing systemd renderer emits
`FORGE_AGENT_HOST="127.0.0.1"` for `FORGE_SYSTEMD_USER=forge-control`. An explicitly
supplied `FORGE_AGENT_HOST` is preserved; an unsafe value then fails startup.
Ordinary Agent generation without this variable remains unchanged, and other
services do not consume this Agent-specific setting from the shared environment.

For an independently prepared runtime, explicitly set either:

```text
FORGE_AGENT_HOST=127.0.0.1
```

or `server.address=127.0.0.1`. Validation never silently fills in a bind address.
The boot validator checks the actual typed factory address after Boot's server
configuration and before the HTTP listener starts. Missing/empty, wildcard
(`0.0.0.0`, `::`) and LAN/non-loopback addresses are rejected with the channel
enabled. IPv6 loopback `::1` is recognized by the typed validator; actual binding
also requires OS IPv6 support. Hostnames are judged by the resolved address,
not their spelling. With the channel disabled, no new bind validation runs.

This does **not** authenticate Agent HTTP APIs. Operator authentication, browser
sessions/CSRF and Nexus service credentials remain Stage 6.

Default remains disabled. The socket requires the exact protected parent owner,
group and 0750 permissions. A listener started under another user fails setup.
It never creates the protected parent directory. Stage 4 adds recovery of a
confirmed abandoned socket under an exclusive lifetime lock. Only a verified
control-owned UNIX socket with the configured group/mode and a refused local
connection is eligible; active listeners, unknown errors and foreign files are
preserved and fail startup. The lock file stays in place across restarts to avoid
unlink races. Normal shutdown removes only the server's own socket inode.
Unknown/conflicting artifacts still require operator inspection; startup never
blindly unlinks the path.

The managed systemd unit can subsequently be started by the local operator. It
still has no working keys until later-stage grant provisioning. The Stage 2 code
contains no key-install management API and no peer HTTP API.

## Authenticated channel path

The managed daemon globally enforces `ForceCommand` and disables forwarding,
PTY, user rc/environment and all non-public-key authentication. `ExposeAuthInfo`
provides the actual authenticated public key. See the upstream
[sshd_config manual](https://man.openbsd.org/sshd_config#ExposeAuthInfo).

The helper reads that sshd-owned session proof (owner-only regular file), computes
the authenticated Ed25519 fingerprint, then loads the matching root-owned binding
record by SHA-256 hex. Record content is `session <grantor UUID> <session UUID>
<SHA256 fingerprint>`. Binding records contain public association metadata only;
state/authorization stays exclusively in Agent/Postgres. Missing/foreign/malformed
bindings fail closed. Pairing binding kind is denied in Stage 2.

Only the exact remote command `status` exists. No request-supplied session ID is
accepted. The helper sends one bounded ASCII `STATUS <grantor> <session>
<fingerprint>` frame over the Unix socket. Kernel SO_PEERCRED checks both ends:
Agent accepts only forge-ssh; helper accepts only forge-control. Agent reloads the
session each request and verifies local grantor, GRANTOR role, key fingerprint,
current state and provisioning deadline. ACTIVE and unexpired PROVISIONING may
read their own status. Revoking/revoked, foreign/missing, malformed, timeout or
unavailable authority produce denial, never shell fallback.

This is a small SSH-local control protocol, not a general executor or HTTP shell.
The listener has bounded frame size, worker/queue counts and transport deadlines.
No private material or internal exception is returned in the response.

## Accessor policy and limits

`RemoteAccessSshCommand.status` constructs argv only; it does not execute or retry.
It requires owner-only regular identity/pin files and validates the **full**
Ed25519 host key in known_hosts against the persisted session. Explicit `-F
/dev/null`, identity/agent/password exclusions, strict pinning, disabled proxies,
multiplexing, local commands and forwarding isolate the client from user config.
Key/pin ancestors must be root/control-owned and must not permit untrusted replacement
(writable non-sticky directories are rejected). Managed key/pin paths currently require ASCII paths without spaces, percent/token
expansion characters or symlinks; unsupported paths fail explicitly. No automatic
fallback to personal keys or local execution exists.

Host/private key generation for pairing/session access, key publication, confirm
and activation are later stages. This stage generates only the managed sshd host
identity. Private session keys remain on ACCESSOR.

## Verification

```sh
python3 -m unittest discover -s scripts/remote-access/tests -p 'test_managed_ssh.py' -v
docker build -f scripts/remote-access/tests/Dockerfile -t forge-remote-stage2-test .
docker run --rm --network none forge-remote-stage2-test
mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
```

The container has no host socket/filesystem mounts, networking to host/LAN, or
privileged mode. It installs actual artifacts as container root, uses real sshd
and separate transport/control UIDs with synthetic keys. The authority process in
that test is deliberately a stub. Actual Agent DB decisions are separately tested
with PostgreSQL, and the Unix listener with real peer credentials. This is not
REMOTE_ACCESS_RUNTIME_E2E_PASS or REMOTE_ACCESS_CODEX_LIVE_PASS.
