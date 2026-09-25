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
local-only Give Access/Connect controls reachable. It does not stage the full Remote
Access package, install or start the system SSH service, prepare the command
rootfs, start the dedicated Agent/Nexus pair, or create a Remote Access database. A minimal,
root-owned, socket-activated bootstrap helper is installed with Forge so the
unprivileged web process cannot run arbitrary privileged commands. Its only
operation is the reviewed, fixed Remote Access setup. The socket permits the
Forge Nexus service identity only. Browser actions require a loopback connection,
exact local Origin and CSRF protection, with no separate operator login prompt.
This authorizes a local browser on the machine; HTTP cannot prove its Linux UID.
A failed setup leaves an
explicit error and no claimed Enabled state.

Selecting **Give Access** or **Connect** invokes that bootstrap, then starts the dedicated management services
and the existing system OpenSSH service. Forge does not install or run a second
`sshd`. Its dedicated transport account uses a protected, root-managed
`authorized_keys` file in its own home. Each key carries a fixed forced command
and SSH restrictions; the forced command resolves the authenticated key's
root-owned binding before asking the Agent's authority gate. The system host
Ed25519 key is pinned by the peer. Forge does not change personal SSH keys or
system-wide authentication rules. The setup is automatic within the selected
action: no separate Enable or sign-in step is required. Package and rootfs
preparation occur only at this point. A repeat action reuses verified installed
artifacts and sessions.

The management page stays on ordinary Forge when Remote Access is disabled.
Its current unconditional redirect to the dedicated Nexus on port 9100 must be
removed. Once enabled, the page can use the dedicated typed management API; the
transition must preserve the local-only Origin/CSRF boundary and never proxy peer admin
HTTP over the LAN.

## One-token mutual handshake

The existing invitation redeem creates the first direction: connector accesses
inviter. The connector then creates an internal short-lived invitation for the
reverse direction and sends its token only through the first authenticated SSH
control channel. The inviter redeems that internal invitation, pins the
connector's SSH host key and obtains its own dedicated reverse session key.
The connector's reverse invitation and token are never shown as a second user
step or exposed by list/GET. The connector chooses its local SSH address from
the route toward the inviter. When the inviter has one usable LAN address,
Give Access selects it automatically; with ambiguous interfaces the UI asks
which reachable address to advertise. Each direction proves
possession of its own dedicated private key;
peer display names and requested session IDs are not proof of identity.
The first direction can become ACTIVE as a directional grant before the reverse
exchange completes, but the pair is never reported Connected at that point.
If the reverse exchange is temporarily unavailable, the incomplete pair is
retried from persisted state. An unconsumed reverse invitation that expires
causes the incomplete grants to enter bounded cleanup; unconfirmed cleanup
remains visible as pending. A retry uses the same pair and
internal invitation while its provisioning deadline is valid. The internal
reverse token needs protected, recoverable local storage until both directions
are confirmed; it must not be logged, returned through GET, or stored in the
browser.

The user sees one bridge card, backed by two directional grants linked by a stable
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
outbound Forge grants and stops managed commands and descendants. It does not
stop `ssh.service` or disconnect unrelated SSH users. If a peer is offline,
remote confirmation remains pending; the UI does not claim remote cleanup.
Existing files changed by commands are not rolled back. A single bridge card
shows the state of both directions and identifies any direction still pending.

Crash recovery resumes an in-progress pair from persisted key references and
session state. An expired or consumed invitation cannot create a second pair.
The local private keys, Forge credentials and Codex history are never placed in
the pairing token or sent to the peer.

## Review evidence

Before claiming the feature ready, verify cold `just start` with no Remote
Access preparation, Enable from the real browser using the system OpenSSH
service, one-token pairing across two
isolated Forge instances, command execution in both directions, partial-handshake
recovery, and revoke during running commands in each direction. The existing
Stage 0–8 isolation, pinned host identity, admission gate, and truthful cleanup
contracts remain in force. No mocked pairing test is called a two-machine E2E.
