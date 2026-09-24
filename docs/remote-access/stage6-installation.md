# Stage 6 — local management setup

Stage 6 exposes local management only. Inter-machine pairing/control remains SSH.
No UI, execution HTTP endpoint, workflow changes or Codex helper is added.

## Protected identities and configuration

Agent continues to run unprivileged as `forge-control`. Nexus runs as the local
operator. Each service requires an explicit loopback HTTP bind when Remote Access
management is enabled. Forwarded-header processing must remain `none`; this flow
does not trust a LAN reverse proxy or a peer-supplied address.

On the target machine, after existing Remote Access OS preparation, an operator
with sudo prepares a new protected directory under a root-owned parent:

```sh
sudo python3 scripts/remote-access/prepare_management.py \
  --directory /etc/forge-remote/management \
  --agent-user forge-control \
  --operator-user <local-operator-running-nexus> \
  --origin http://127.0.0.1:9099
```

The command prepares only files, never starts/restarts services or grants SSH
access. Existing mismatched/symlink/unsafe artifacts are rejected, not replaced.
Repeat with the same configuration preserves credentials.

| Artifact | Owner and mode | Purpose |
| --- | --- | --- |
| `management/` | setup/root; traversable, not group/other writable | Protected parent |
| `agent-service.secret` | forge-control, 0600 | Agent verifies the dedicated Nexus identity |
| `nexus-service.secret` | local operator, 0600 | Same service secret available to Nexus only |
| `operator.secret` | local operator, 0600 | Distinct bootstrap credential for browser operator login |
| `agent.env` | forge-control, 0600 | Explicit loopback bind, enable flag and secret-file reference |
| `nexus.env` | local operator, 0600 | Explicit loopback bind, enable flag, exact origin and secret-file references |

Attach `agent.env` only to the prepared Agent unit with an `EnvironmentFile=`
directive, and `nexus.env` only to the Nexus unit. Use the existing systemd setup
path; do not add both to the shared service environment. These generated files
contain paths/configuration, not plaintext credentials. The setup command does
not modify the installed units or choose which existing runtime to restart.

SSH endpoint selection remains explicit: set Agent
`forge.agent.remote-access.advertised-host` or supply `advertisedHost` when creating
an invitation. SSH port/user use the prepared dedicated listener (defaults 2222,
forge-ssh). An ACCESSOR does not need its own grantor SSH channel enabled.

Do not expose these files, the parent config, Forge credentials or admin sockets
inside the workload namespace. The Stage 0/2/5 workload isolation remains required.
This feature does not promise protection against root or a compromised operator.

## Local operator HTTP boundary

Nexus's normal servlet context is `/fgaisox`; browser endpoints therefore use
`/fgaisox/api/v1/infrastructure/agents/remote-access`.

- POST `/operator/login` with typed JSON `{ "secret": "<bootstrap secret>" }`.
  Require exact configured Origin and Host. Do not put the secret in command-line
  arguments, URLs, browser persistence or logs. Use an in-memory request body.
- Successful login creates a fresh HttpOnly, SameSite=Strict, host-only
  `FORGE_REMOTE_OPERATOR` cookie, scoped to the complete Remote Access path.
  HTTPS origins use Secure cookies. V1 permits explicit loopback HTTP.
- Login returns `{ "csrfToken": "..." }`; use `X-CSRF-TOKEN` with the session
  cookie and exact Origin for all mutations. This token is session-bound and is
  not a pairing token or service credential.
- GET `/operator/session` returns the current CSRF token to the authenticated
  same-origin caller. POST `/operator/logout` requires CSRF and invalidates the
  session. Sessions expire after 15 minutes even with continued activity and are
  lost on Nexus restart. Bootstrap attempts are bounded to 10/minute per process.
- All feature responses use `Cache-Control: no-store`. A peer key cannot
  authenticate either local HTTP boundary. The operator secret is never forwarded
  to Agent; Nexus uses the distinct local service credential.

The existing ordinary Forge endpoints preserve their prior behavior. This is
**not** a claim that every Forge HTTP endpoint now requires operator login.

## Remote Access upstream timeout

Nexus uses `forge.remote-access.agent-read-timeout` (default `120s`), separately
from the ordinary `forge.ai.infrastructure.agent.read-timeout` (`30s`). Generated
`nexus.env` explicitly includes `FORGE_REMOTE_ACCESS_AGENT_READ_TIMEOUT=120s`.
When enabled, values below `100s` fail startup: the Stage 5 SSH revoke bound is
90 seconds with a 10-second minimum HTTP/process margin. Agent connect timeout
is still reused; Agent lifecycle bounds are unchanged. Expiry of the dedicated
read timeout still maps to safe `503 REMOTE_ACCESS_UNAVAILABLE`.

Existing generated environments are never overwritten by setup. For an existing
installation, the operator must add this non-secret timeout setting to its
protected Nexus EnvironmentFile; preserve existing credentials and permissions.

## Lifecycle and error truth

The nine management endpoints match `roadmap.md`. Agent owns state; Nexus only
maps/delegates. GET does not open SSH. ACCESSOR check performs a bounded authenticated
SSH probe; GRANTOR check reports UNKNOWN without creating reverse access. Probe
observations never authorize a session or infer successful remote cleanup.

Connect returns 201 for ACTIVE and 202 for a persisted provisioning attempt;
retries reuse its durable key/session. Revoke returns 200 only for confirmed
REVOKED, otherwise 202 for persisted REVOKING. No audit hard-delete or local
"Forget" is exposed. A failed local key deletion remains explicitly visible.

Known typed errors retain upstream status/code/message/correlationId. Invalid
bodies and unauthenticated/invalid-Origin/CSRF requests are rejected locally
before upstream invocation.

An expired or cancelled pairing key may be removed before SSH can authenticate.
The current SSH protocol then cannot distinguish expiry/cancellation, a pinned
host mismatch and transport failure. The user approved retaining this limitation
for Stage 6: never fabricate HTTP 410/409 from an ambiguous SSH failure or from the
editable token expiresAt. Report unavailable/unconfirmed pairing with safe
instructions to verify or recreate the invitation; preserve bounded persisted
recovery. Active sessions use a separate key and do not expire with the invitation.

## Cleanup

Disable management in the specific runtime before removing these files. Removing
management configuration is not a session revoke and must not be presented as one.
Use the confirmed-revoke flow to stop workloads. Remove only the six explicitly
Forge-managed artifacts after reviewing ownership; do not remove personal SSH
keys, DB state or unrelated systemd/environment files.
