# Stage 7 — Remote Access Console

The global sidebar opens `operator/remote-access.html` using the existing Nexus
context path (normally `/fgaisox`). Use the exact loopback host/port configured
as the Stage 6 operator origin. Agent/Nexus management and their protected
credentials must already be prepared; this UI does not install SSH or enable services.

## Operator access

Sign in with the local operator credential from Stage 6 preparation. This is not
a pairing token or ChatGPT credential. The browser keeps the existing HttpOnly
operator cookie; the page holds CSRF only in memory. Login credentials are removed
from inputs on submission. No token/credential is written to browser storage or URLs.
On expired authorization sign in again; mutations are never automatically replayed.

## Give Access (this machine is GRANTOR)

Open Give Access. If setup needs a reachable SSH address, enter the intended LAN
address explicitly. Otherwise leave it blank to use the configured advertised
address. Create invitation, copy the one-time token and share it through a trusted
channel. The displayed countdown uses expiresAt; the grantor remains authoritative.
Cancel invitation prevents redemption but does not revoke an existing session.
Closing the form clears its token without cancelling the invitation. Metadata may
still be listed after reload, but the token is never reloaded. Create a new
invitation if needed. Clipboard denial leaves the token selectable for manual copy.

## Connect (this machine is ACCESSOR)

Paste the peer's token, inspect its machine/endpoint preview, then Connect. Preview
is untrusted display metadata; Agent performs real validation and SSH identity checks.
201 ACTIVE and 202 PROVISIONING remain distinct. No reachability is inferred from
ACTIVE. Token input clears when submitted; no automatic retry retains it.

If the request fails ambiguously, Connect remains blocked until a successful
metadata refresh. Inspect existing sessions before deliberately pasting the token
again. An unsuccessful refresh keeps retry blocked. This uses Agent's existing
attempt identity and recovery, not a new browser-side attempt or lifecycle.

## Sessions

- I can access lists ACCESSOR relationships.
- Access granted to lists GRANTOR relationships.
- Authorization and last observed connectivity are separate. Check explicitly
  requests a bounded peer probe; periodic metadata GETs do not open SSH.
- Disconnect / Revoke Access shows REVOKING until confirmation. Offline/errors
  never manufacture REVOKED. Retry uses the same revoke endpoint.
- REVOKED with a pending local credential cleanup still offers Retry credential
  cleanup. Confirmed revoke does not roll back files or data already read.

Polling is non-overlapping, pauses while hidden and stops on navigation. Back/forward
cache restoration mounts a fresh page and restores operator state from the server,
not cached secrets. Old read/login/dialog responses cannot restore cleared state.

## Verification

Console tests use production HTML/modules with controlled fetch responses in jsdom.
The standalone real-browser smoke uses Chrome and an explicitly synthetic management
HTTP fixture, not real Nexus/Agent/SSH. After the usual `npm ci` and build:

```sh
cd services/forge-console
npm test
npm run typecheck
npm run build
node scripts/remote-access-browser-smoke.mjs
```

Set `CHROME_BIN` if Chrome is not named `google-chrome`. The smoke runs headless in
a disposable profile with sandbox disabled for this isolated fixture only; it
never uses personal browser profiles or real credentials. It checks login, invitation,
connect/provisioning, pending/confirmed revoke, no browser storage, reload, actual
browser Back and logout. `SMOKE_SCREENSHOT=/tmp/stage7.png` optionally records the
synthetic session view. Local fixture server and Chrome are closed after the run.

The Stage 9 live two-machine and live Codex acceptance remains NOT_RUN. Stage 8
helper and later work are not part of this PR.
