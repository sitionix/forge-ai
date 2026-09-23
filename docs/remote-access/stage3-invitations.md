# Stage 3 — Give Access / invitation lifecycle

This stage adds local application operations and the invitation-only SSH channel.
It does not expose management HTTP endpoints or UI, activate a session, or run a
workload. Those are Stage 4/5/6/7 deliverables. A `PAIRING_ALLOWED` channel reply
means only that the authenticated invitation key is currently eligible; it is
not a session state, consume operation, or execution permission.

## Local application path

`RemoteAccessInvitations.create(explicitEndpoint, displayName)` validates the
caller/configured advertised endpoint and obtains the installed SSH host public
key from the protected supervisor. It generates an ephemeral Ed25519 pairing key,
persists metadata with a five-minute expiry, commits, installs its public-key
grant, and only then emits `RemoteAccessInvitationCreated` with the token.
Private key material is created in an owner-only temporary directory, removed
before successful return, and not written to the database/credential store. The
in-memory key holder is destroyed after encoding. The token itself is necessarily
a bearer secret held by its caller and must be displayed once by the later API/UI.

Creation never enumerates LAN interfaces or chooses a host silently. The caller
must supply the operator's advertised host, installed SSH port and `forge-ssh`
username. V1 token endpoints accept IPv4/DNS and IPv6 literals beginning with a
hex digit; colon-leading compressed IPv6 is not supported by the existing client
endpoint contract. No DNS request is made during token validation.

`get` / `list` return `RemoteAccessInvitation` metadata only. The type contains no
private key/token field. No token recovery/read API exists. `cancel` persists the
cancelled state before attempting removal; repeating cancellation retries owned
cleanup. A consumed invitation is not cancelled and its session is not touched;
only its obsolete pairing key is eligible for removal.

Provisioning is explicitly **not** a DB/filesystem transaction. Failures after
insert attempt cancellation and independent grant removal, return no token, and
report incomplete cleanup if necessary. An expired/cancelled/consumed invitation
is denied by the authority even when its authorization file is stale. A scheduled
local cleanup, enabled only with the Remote Access channel, starts after one
second and retries every minute to remove those obsolete grants. It does not
change expiry enums or restore grants. Cleanup failures are logged with a fixed,
secret-free message and retried; metadata remains for audit.

## Token and pairing authority

The envelope is `fgpair_v1_<base64url-payload>`. The payload is a typed Jackson
record with strict scalar types, duplicate/trailing JSON rejection, bounded input,
and version/field/key validation. Base64url is **not encryption**. Error messages
and secret-bearing objects have redacted representations. OpenSSH validates
private-key material; only Ed25519 public keys in the expected binary format are
accepted. The full host public key is carried for pinning, not just a fingerprint.
Self-pairing is rejected by persisted Forge instance ID.

`forced_command.py` derives binding from sshd's authenticated public-key proof.
An `invitation` binding permits only `pair`. It passes `PAIR <grantor> <invitation>
<fingerprint>` to the Agent channel, which checks local instance, matching stored
fingerprint, unconsumed/uncancelled state and the **grantor's** clock. Client/token
expiry is never used to authorize. Session `status`, shell, PTY, forwarding,
confirm and arbitrary commands remain unavailable to the pairing key. The next
stage adds the actual consume/key-exchange handshake; the existing atomic DB
reservation already prevents two sessions redeeming one invitation.

## Privileged installation

The Agent continues to run as `forge-control`, with explicit loopback HTTP bind
required whenever the channel is enabled. Full HTTP authentication/CSRF remains
Stage 6; no new management endpoint is added here.

The existing root-owned package installation also installs:

- `/usr/libexec/forge-remote/invitation-supervisor`, root-owned 0755;
- `forge-remote-invitations.service`, root process with group `forge-control`,
  restricted capabilities and writable paths;
- a systemd-owned runtime directory `/run/forge-remote/admin` (0750), containing
  `invitations.sock` (root:forge-control 0660).

The socket checks SO_PEERCRED against `forge-control`; peer/transport/workload
identities cannot administer it. There are exactly three operations: read the
managed host **public** key; install an invitation public-key binding; remove
that exact invitation public-key binding. No caller-controlled paths, arbitrary
commands, session grant operations, UID allocation or workload operations exist.

The helper validates root-owned protected directories/files, rejects symlinks and
foreign bindings, atomically replaces owned file contents, preserves unrelated
key entries, and makes install/remove idempotent. It serializes bounded requests
in one process. A partial binding-only installation can be removed safely. It
never reads personal authorized_keys. The installer still starts no service and
creates no grant.

After reviewed privileged setup, the local operator starts the dedicated Agent,
managed sshd and `forge-remote-invitations.service`. systemd creates/removes the
supervisor runtime directory for its lifecycle; the helper refuses an unknown
pre-existing socket. Do not run the Agent itself as root.

For upgrade, stop the managed sshd **and `forge-remote-invitations.service`** before invoking the privileged installer, as
required by its existing endpoint preflight. Setup recognizes the exact reviewed
Stage 2 forced helper (SHA-256
`28d18e70e272a7385badbdfe3e36f95abf9ecd83357a93ce5819f9237b289abe`)
and atomically replaces it with the new root-owned package version. Identical
current bytes are preserved. Unknown modified files, wrong ownership/mode and
symlinks remain conflicts; there is no unconditional overwrite path. The old
helper is retained as a test fixture and actual upgrade is tested in Docker.
The installer preserves host keys, DB state, bindings and authorization entries;
no host-wide upgrade/uninstall occurs. A host in-place installation was not run.

## Evidence boundaries

See `evidence.md` for commands/results. Java tests exercise real PostgreSQL-backed
authority and altered token expiry. Docker tests exercise real OpenSSH, the
installed forced helper and actual root supervisor/control UID but use a stub
Agent authority. They are not an end-to-end live two-Forge pairing/activation
run. Supervisor units receive static `systemd-analyze verify`; the Docker fixture
starts its process directly, without a system systemd manager. No live Codex,
UI, session activation or workload execution claim is made.
