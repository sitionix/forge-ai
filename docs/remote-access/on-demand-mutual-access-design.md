# On-demand mutual Remote Access — design checkpoint

## User outcome

One person selects **Give Access** and shares one short-lived token. The other
person selects **Connect** and pastes it. The UI reports a connected bridge only
after each machine can run authorized SSH commands on the other. The two SSH
directions remain separate grants internally, so either machine can revoke its
own inbound access without relying on the peer.

The token is a bearer pairing secret, not an encrypted SSH connection. It may
carry the inviter's pinned host public key and a temporary pairing credential.
OpenSSH encrypts the handshake and command streams. The token must be handled as
a secret, shown once, and never put in a URL, log, or browser storage.

## Cold start and local controls

`just start` starts ordinary Forge and makes the Remote Access page and its
local-only Enable/Connect controls reachable. It does not stage the full Remote
Access package, install or launch sshd, prepare the command rootfs, start the
dedicated Agent/Nexus pair, or create a Remote Access database. A minimal,
root-owned, socket-activated bootstrap helper is installed with Forge so the
unprivileged web process cannot run arbitrary privileged commands. Its only
operation is the reviewed, fixed Remote Access setup. The socket permits the
local operator identity only; browser actions still require the local operator
session, exact loopback Origin, and CSRF protection. A failed setup leaves an
explicit error and no claimed Enabled state.

**Enable** invokes that bootstrap, then starts the dedicated management services
and SSH listener. **Give Access** is available only when the local side is ready.
**Connect** is the local operator's approval to run the same bootstrap
automatically, because pasting a token is meant to complete the bridge without
a separate setup command or confirmation. Package and rootfs preparation occur only at this
point. A repeat Enable/Connect reuses verified installed artifacts and sessions.

The management page stays on ordinary Forge when Remote Access is disabled.
Its current unconditional redirect to the dedicated Nexus on port 9100 must be
removed. Once enabled, the page can use the dedicated typed management API; the
transition must preserve operator authentication and never proxy peer admin
HTTP over the LAN.

## One-token mutual handshake

The existing invitation redeem creates the first direction: connector accesses
inviter. During that authenticated channel, the connector supplies its own
dedicated SSH endpoint and host public key. The inviter generates a separate
reverse-direction session key pair and sends only its public key through the
first authenticated channel. The connector binds that key to its own session
authorization gate. The inviter then connects to the connector using the pinned
host key and proves possession of the new private key. Each direction proves
possession of its own dedicated private key;
peer display names and requested session IDs are not proof of identity.

The user sees one bridge, backed by two directional grants linked by a stable
pair identity in a forward-only migration. Existing one-way sessions remain
valid and are not relabeled as mutual. Neither grant
becomes available for commands before its key binding and confirmation are
persisted. The UI reports **Connected** only when both grants are confirmed
ACTIVE. An incomplete reverse handshake is shown as incomplete and reconciled
or revoked; it must not silently leave a one-way grant described as mutual.
Retries use the same pairing attempt and keys, not a second token redemption.
If either machine lacks a supported Linux/systemd SSH boundary or the reverse
endpoint is unreachable, Connect fails with an explicit incomplete-pair state;
it does not report a completed bridge.

## Revoke and recovery

Disable first fences new admissions locally. It then revokes all inbound and
outbound grants, stops managed commands and descendants, and stops the managed
SSH listener only after local cleanup is confirmed. If a peer is offline,
remote confirmation remains pending; the UI does not claim remote cleanup.
Existing files changed by commands are not rolled back. A single bridge card
shows the state of both directions and identifies any direction still pending.

Crash recovery resumes an in-progress pair from persisted key references and
session state. An expired or consumed invitation cannot create a second pair.
The local private keys, Forge credentials and Codex history are never placed in
the pairing token or sent to the peer.

## Review evidence

Before claiming the feature ready, verify cold `just start` with no Remote
Access preparation, Enable from the real browser, one-token pairing across two
isolated Forge instances, command execution in both directions, partial-handshake
recovery, and revoke during running commands in each direction. The existing
Stage 0–8 isolation, pinned host identity, admission gate, and truthful cleanup
contracts remain in force. No mocked pairing test is called a two-machine E2E.
