# Stage 4 — durable pairing and activation

This stage adds application services and SSH control messages only. It opens no
workload execution path, management HTTP endpoint, Console page or reverse grant.
`ACTIVE` means the new session key completed the handshake; it does not mean
online, nor does it permit arbitrary commands before Stage 5.

## Pairing path

`RemoteAccessAccessorPairing.connect` validates the typed invitation envelope and
persists a dedicated session key and PROVISIONING attempt before calling SSH.
The private key lives only in the ACCESSOR credential store; the database stores
its reference. The invitation key is temporary, cleared/removed after use, and
is never persisted with the session. Repeated local connect reuses the invitation's
existing attempt rather than generating another session/key.

The invitation-only `redeem` forced command accepts bounded typed JSON with
session ID, accessor identity/display metadata and session public key. The
root-owned binding derives from sshd's authenticated key evidence; neither the
JSON session ID nor display metadata proves identity. The helper forwards a
bounded REDEEM frame to the local authority socket.

`RemoteAccessGrantorPairing` uses the existing transaction-owned provisioning
service to consume the invitation and insert one GRANTOR PROVISIONING session.
Its unique invitation constraint and atomic reservation prevent concurrent
redeems from creating a second session. The transaction commits before the
root supervisor installs the new public-key binding. Database and filesystem
operations are deliberately separate; a failed installation leaves a durable
reservation for reconciliation. The consumed invitation cannot authorize a
second redeem even if its old SSH grant has not yet been removed.

The ACCESSOR then opens a new SSH connection using its dedicated session key and
pinned host key, issuing `confirm`. Only the GRANTOR's matching key binding may
perform PROVISIONING → ACTIVE before its server-side deadline. Repeated confirm
of the same ACTIVE session is idempotent. ACCESSOR records ACTIVE only after that
confirmation, never merely after sending redeem or inserting its local row.

## Recovery and deadlines

The authority takes an exclusive lock in its protected socket directory for its
lifetime. After a JVM crash, startup can reclaim only a verified abandoned UNIX
socket with refused connections and unchanged inode. Live listeners and unknown
artifacts remain fail-closed; the retained lock file prevents competing starts.

Both sides retain the same session ID. The GRANTOR reinstalls missing grants for
unexpired provisioning rows after restart. A lost redeem response is recovered
with the persisted session key; the consumed pairing token is not a recovery
credential. A lost activation acknowledgement is handled by repeating confirm
with that same key. Neither path generates a replacement session.

Provisioning has a five-minute local deadline on each peer. GRANTOR expiry first
persists REVOKING, removes its managed grant, and only then confirms REVOKED.
Cleanup errors retain REVOKING plus a safe failure reason. No workload exists in
this stage; active-session workload cancellation belongs to Stage 5.

ACCESSOR expiry with an unconfirmed remote outcome records REVOKING and retains
its credential/audit state. SSH denial is not evidence of remote cleanup. This
stage does not invent a successful remote revoke acknowledgement. If the process
crashes before its first redeem reaches GRANTOR, the persisted attempt cannot
recover a nonexistent remote reservation through its session key: it expires
with that same explicit unconfirmed outcome. A new invitation is required for a
new attempt; the old local attempt is not silently replaced.

The boot reconciler retries in bounded individual control operations on its own
lifecycle-owned timer. It neither registers a replacement Spring scheduler nor
occupies the existing workflow/lease scheduler during SSH waits. Grantor
reconciliation runs only when its channel is enabled; ACCESSOR recovery does not
require exposing an inbound channel. Safe failure fields use optimistic version
checks, as do lifecycle transitions. No SSH wait holds a database transaction.

## Transport and installation

`LocalRemoteAccessPairingTransport` uses a separate bounded control executor,
strict pinned known_hosts, a dedicated identity, no user SSH configuration or
agent/password fallback, no forwarding and no connection multiplexing. A control
transport error does not automatically replay redeem. Temporary private files
have owner-only permissions and are removed after each call.

The supervisor adds only SESSION_INSTALL/SESSION_REMOVE to its existing narrow
public-key operations. Bindings remain immutable and root-owned. Neither peer
metadata nor the workload/transport identity gains supervisor administration.

For package upgrades, stop both the managed sshd and
`forge-remote-invitations.service`; see [installation](stage2-installation.md).
An existing supervisor runtime directory blocks replacement of differing
supervisor bytes. The installer cannot assume that replacing a Python file
changes the running interpreter. Identical package bytes remain idempotent.

## Verification boundaries

Results and commands are recorded in [evidence](evidence.md). The OS-only Docker
suite deliberately uses a stub Agent authority and verifies helper/supervisor
routing. `RemoteAccessLivePairingIT` is a separate real SSH/PostgreSQL test using
production Java pairing services and authority with isolated persisted peer
states, including abruptly terminated grantor/accessor JVMs at handshake crash
points. Neither is a live Codex, UI, Stage 5 process-cleanup or two-physical-machine
acceptance test. Stage 9 evidence labels remain NOT_RUN.
