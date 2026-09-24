# Remote Access startup preparation and Enable / Disable

## Purpose

A local Forge operator should be able to open Remote Access, choose **Enable**, then use **Give Access** or **Connect**. **Disable** must stop local managed work and revoke every session; closing an SSH connection without revoking its authorization is insufficient. `just start` prepares the operating-system and management prerequisites. The page must explain a real setup or cleanup failure instead of showing a generic request error.

This is an extension of the merged Remote Access Stages 2–8. It does not replace their SSH gate, session state machine, process supervisor, operator authentication, or per-session revoke behavior. It does not start Stage 9 live two-machine acceptance or add Codex credentials to the peer.

## Selected approach

Use the existing privileged `just start` installation phase for an idempotent, root-owned bootstrap. Run a dedicated `forge-control` Agent and a loopback-only Remote Access Nexus management instance. Keep the existing ordinary Agent and Nexus units, their bind behavior, and their responsibilities unchanged. A persisted Agent-owned feature switch controls admission; the browser never invokes `sudo`, `systemctl`, or a root shell.

The dedicated managed SSH listener may run while access is disabled, but it has no usable feature grants. The security invariant is that disabled means no new invitation, pairing, session confirmation, or workload admission and no live local grant or managed workload after confirmed cleanup. A listening SSH port alone does not count as access.

Two alternatives were considered and rejected:

- Exposing privileged installation or `systemctl` through the ordinary Nexus endpoint would place a root-control operation behind a service that currently listens on non-loopback interfaces.
- Merely stopping SSH connections or hiding the page would leave authorized keys and persisted ACTIVE sessions able to reconnect.

## Startup ownership

`just start` prepares the reviewed Remote Access package in a root-owned, non-writable location and runs the existing installers from there. It creates or verifies the dedicated users, managed SSH configuration, host key, runtime directories, workload and invitation supervisors, protected operator/service secret files, Stage 8 local-exec socket/helper, and systemd units. It prepares only the explicitly configured Forge workspace for managed execution, using the existing workspace preparation boundary. It starts the dedicated supervisors, Agent, and Nexus in dependency order and checks readiness. Ordinary Forge services remain separate. A repeated start preserves host identity, credentials, session records, and the persisted switch. It does not silently re-enable a disabled installation.

The managed SSH listener needs an explicit LAN address. Startup may select the sole eligible address of the active default LAN route after excluding loopback and container/virtual links. If several eligible addresses remain, interactive `just start` asks the local operator to choose one; noninteractive start requires an explicit Forge startup setting and fails with `REMOTE_ACCESS_ADDRESS_REQUIRED` until supplied. It never binds wildcard or guesses among multiple interfaces. The page displays the selected address and diagnostic. This task does not expose arbitrary network reconfiguration in the browser.

Existing root-owned installation conflicts, occupied ports, missing OS capabilities, failed unit startup, and package-upgrade conflicts fail closed with safe diagnostics. In particular, `just start` must not stop an active supervisor merely to replace its executable or revoke sessions as an upgrade side effect. A failed Remote Access bootstrap must not claim Remote Access ready or corrupt ordinary Forge services.

The dedicated Agent uses an explicit loopback bind, protected DB configuration, and the established Remote Access channel and management flags. The management Nexus uses an explicit loopback bind, protected service/operator secrets, the existing operator Origin/CSRF/session boundary, and the dedicated read timeout. Neither service shares the ordinary wildcard HTTP listener. `just start` reports the local management URL and protected operator credential location, never the credential value in logs. Operator sign-in remains required before Enable, Disable, Give Access, or Connect.

## Feature state and API

The Agent persists a single local Remote Access switch with `DISABLED`, `ENABLED`, and `DISABLING` states and a version for optimistic updates. It is independent of `RemoteAccessSession.status`; no fake session state is introduced. Initial state is `DISABLED`. A restart recovers `DISABLING` rather than treating it as success. Existing session and invitation tables remain the authority for individual grants and cleanup.

The typed Agent management API exposes switch status and Enable/Disable operations; the dedicated Nexus maps and delegates them through its existing typed client, preserving authentication and safe errors. GET reports readiness, switch state, selected SSH endpoint, counts of unresolved grants/workloads, and bounded diagnostics without returning credentials. The UI uses those operations to render Enable or Disable. The API must be mounted when the switch is disabled so that an operator can enable it; a disabled switch must never turn the management path into a 404.

Enable requires successful bootstrap readiness and an authenticated local operator. It atomically changes `DISABLED` to `ENABLED`, then allows the existing invitation and pairing flows. It cannot make a partially installed or unsafe service ready by silently ignoring a missing prerequisite. Repeated Enable is idempotent. It does not create an invitation or session on its own.

Disable first persists `DISABLING`, which immediately blocks new invitation creation, token redemption, session confirmation, and command admission. It cancels outstanding invitations, invokes the existing per-session revoke paths, removes grants, and stops all managed workloads. Existing SSH channels must not admit a new command. Each session retains its truthful `REVOKING`/`REVOKED` status. Only after local grant and workload cleanup is positively confirmed, and all required remote acknowledgements are confirmed, does the switch become `DISABLED`. A failed or offline remote revoke remains `DISABLING` with a safe pending reason and supports retry/reconciliation. The UI never labels this state as disabled or successful cleanup.

For an ACCESSOR session, local new execution is blocked immediately even when its GRANTOR is offline. The remote authorization cannot be claimed revoked until an authenticated GRANTOR response confirms it. For a GRANTOR session, removal of the local grant and managed processes is authoritative for local access. A failed cleanup cannot be masked by merely closing the SSH transport. Optimistic updates and retries must respect a concurrent newer state and remain bounded.

Normal `just stop` is not a substitute for Disable: current fail-closed authority and supervisor behavior applies, and restart preserves the persisted switch. The UI must distinguish service unavailability from a deliberate disabled state.

## Console behavior

On load, the page obtains the operator session and switch/readiness status. When disabled and ready, it shows **Enable Remote Access** plus the selected local SSH endpoint. Give Access and Connect are unavailable until Enable succeeds. When enabled, existing invitation/session controls work unchanged and a **Disable Remote Access** action appears. Disable requests confirmation that all sessions will be revoked and running remote commands terminated. The page shows progress and the exact safe pending reason while `DISABLING`; it offers retry and keeps existing session cards visible. A failed HTTP request never becomes a success state. When bootstrap is incomplete, the page shows the concrete `NOT_READY` diagnostic instead of the current generic 404 message.

The page never stores the operator secret, pairing token, or private key in browser persistence or URLs. The ordinary Forge sidebar may link to the dedicated local management URL, but a browser running on another machine must not be silently sent to that machine's own `localhost`; show that Remote Access management is local to the Forge host.

## Verification

- Startup: fresh install, repeated start, reboot, disabled restart, enabled restart, unsafe bind, ambiguous address, occupied port, missing root/OS capability, conflicting artifact, and active-supervisor upgrade refusal. Existing ordinary Forge startup remains healthy.
- Boundary: the management endpoint is reachable only on loopback and still requires the existing operator Origin/session/CSRF checks. No browser route can execute arbitrary privileged commands. Secrets stay redacted. Disabled management returns a typed state, not 404.
- Agent: enable idempotency; admission denied in `DISABLED`/`DISABLING`; invitation cancellation; grantor and accessor revoke; start-vs-disable race; CAS winner preservation; restart reconciliation; cleanup failure remains pending.
- Real SSH/systemd: Disable during a long-running command closes its channel, stops its managed unit and descendants, removes registry/fence and grants, and prevents reconnect with the old key. A separate unrelated Forge service remains healthy. An unreachable remote peer leaves ACCESSOR revocation visibly pending.
- Console/Nexus: readiness, enable, disable, pending and error rendering; typed forwarding; no secret persistence; ordinary Forge pages and existing Remote Access invitation/connect tests stay green.

Live two-machine pairing and Codex execution retain their Stage 9 evidence labels. Unit or mocked browser tests must not be reported as live SSH or Codex acceptance.

## Delivery boundary

Implement as reviewable increments: (1) idempotent privileged startup preparation and dedicated local services; (2) Agent-owned persisted switch and bulk revoke/reconciliation with typed Nexus API; (3) Console Enable/Disable and diagnostics; (4) real local SSH/systemd verification and regression. Each increment includes its own tests. No PR metadata, review publication, merge, or deployment to another machine is part of this design document.
