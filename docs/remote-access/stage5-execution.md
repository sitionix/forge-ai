# Stage 5 — managed execution, revoke and recovery

This stage adds the application/SSH execution path. It does not expose management
REST, Console actions, workflow routing or the Stage 8 `forge-remote` CLI. Stop at
external review; Stage 6 is not included.

## Supported installation and explicit workspace

GRANTOR: Linux with system systemd **254+**, cgroup v2, OpenSSH and Python 3.
The literal-argument systemd option requires 254; missing capability fails closed.
Actual privileged evidence used Ubuntu 24.04/systemd 255 in a disposable Docker
systemd namespace, without host bind mounts. ACCESSOR uses the existing Java 21
Forge runtime and OpenSSH client; no GRANTOR Codex installation/login is needed.

Retain Stage 2's root-owned installation and dedicated `forge-control` Agent with
explicit loopback HTTP bind. Run the updated installer using the existing Stage 2
procedure. It installs `forge-remote-workloads.service` in addition to the existing
invitation supervisor and managed sshd; it does not start arbitrary commands or
create grants. Enable/start that service when enabling the channel. Only these
small OS helpers run as root; Agent stays `forge-control`.

Protected additions:

- `/usr/libexec/forge-remote/`: workload supervisor, execution attachment and
  workspace preparation helpers, owned by root.
- `/var/lib/forge-remote/executions`: root-only durable cleanup records and
  single-execution `.allow` fences, mode 0700.
- `/etc/forge-remote/workspaces`: root-only session context manifests.
- `/run/forge-remote/workload-admin/{control,heartbeat}.sock`: root server,
  forge-control peer identity required. Independent bounded heartbeat workers.
- `/run/forge-remote/workload-peer/attach.sock`: forge-ssh may attach exactly
  three stream descriptors. This does not authorize execution.
- `/var/lib/forge-remote/transport-home`: empty root-owned 0555 SSH transport
  home, replacing the known managed `/nonexistent` setting to keep stderr clean.

A trusted local operator prepares a minimal root filesystem under
`/srv/forge-remote/`, with protected ancestors and `workspace`, `proc`, `dev`,
`tmp`, `run` mount targets, required binaries/libraries and no control secrets.
Do not copy the host's personal home, credentials, DB configuration or sockets.
For an existing session, run the installed `prepare-workspace` helper with
`--session <session UUID> --rootfs /srv/forge-remote/<prepared-rootfs>` as root.
This creates a dedicated non-login `frw-<session prefix>` user/group, root-owned
manifest and owner-only `/srv/forge-remote/workspaces/<session UUID>` directory.
It grants no SSH authorization. Existing identities/manifests are not overwritten;
a partial setup is retained for operator inspection, never automatically deleted.

Commands see a read-only root filesystem and writable `/workspace`. They run
without capabilities, privilege escalation, host network, host control sockets,
Docker socket or host homes. Dependencies must already be available in the
prepared context: V1 does not silently grant network access for package downloads.
The operator supplies the trusted rootfs; this helper is not a general-purpose
image builder or an ACL editor.

## Execution and lifecycle

`RemoteAccessAccessorExecution.start` checks persisted local ACTIVE. The transport
uses a dedicated key, pinned full host key, no personal identities/config/forwarding
and no shared SSH multiplexing. A bounded typed header precedes raw stdin.
stdout/stderr are independent streams, with OS backpressure rather than a captured
unbounded output buffer. The caller must drain both streams concurrently.
`await` returns the real SSH command status; closing the execution cancels it.
There is no retry of commands after transport failure and no local fallback.

The forced helper derives the session from the authenticated key binding. It
registers stream descriptors, then requests `EXEC` admission from Agent. Agent
reloads ACTIVE under its per-session admission gate. The root supervisor accepts
only a one-use attachment matching that session and the current authority epoch.
Before systemd submission it fsyncs a cleanup record and root-owned `.allow` file.
PID 1 uses `ConditionPathExists` to reject a submission whose fence was removed.
A lost connection or completed main process triggers descendant cleanup too.
The forced helper watches loss of SSH stdout/stderr readers even for a quiet
non-PTY command; normal stdin EOF remains valid and does not cancel execution.

Limits: 16 long attachments, 8 control workers, 2 heartbeat workers; no unbounded
executor queue. ACCESSOR permits 16 concurrent SSH processes. Command argv has
128 entries/8192 UTF-8 bytes, a 1–86400 second deadline, 128 tasks and a 1 GiB
memory limit per command. These are operational bounds, not execution statuses.

GRANTOR revoke persists REVOKING under the same admission gate, then performs
key removal and process cleanup outside database transactions. Cleanup removes
all selected start fences first, terminates/reaps launchers, stops every registered
systemd unit and positively checks LoadState/ActiveState/SubState/PID/Job/cgroup.
Unavailable, malformed or unsuccessful inspection is never confirmation.
Failures retain records and REVOKING with a safe persisted diagnostic; subsequent
reconciliation retries. A confirmed successful recovery clears stale failure
metadata with the exact successful transition version, preserving newer writers. Only confirmed key removal and process cleanup permit
REVOKED. Another session's units are not selected for that cleanup.

ACCESSOR first persists local revoke intent. Only authenticated GRANTOR REVOKED
confirms remote cleanup. It persists that evidence before deleting the local key;
a deletion failure retains the key reference and a safe reason for retry. Offline,
lost acknowledgement or SSH denial stays REVOKING with confirmation unavailable.
The old key is never re-enabled to retrieve an acknowledgement. Revoke cannot undo
prior file changes or recover already read data.

## Crash recovery and diagnosis

Agent renews a separate monotonic authority lease every two seconds. Missing
renewal for ten seconds closes admission and stops existing commands. Slow session
cleanup has a separate Java timer and cannot consume heartbeat workers. Supervisor
startup cleans durable records before admitting a fresh epoch. Managed units bind
to its lifecycle; a fifteen-second systemd watchdog handles a hung supervisor.
Unknown cleanup remains fail-closed rather than resetting state to ACTIVE.

Inspect `systemctl status forge-remote-workloads.service`, safe supervisor/Agent
logs and retained execution records as the local operator. Do not delete registry
records or fences to claim cleanup. A failed cleanup needs verified unit/cgroup
shutdown before artifact removal. Workspaces and prepared identities are retained
on revoke; no user data rollback/deletion is implied. Safe uninstall must first
revoke and verify all commands stopped, then remove only operator-confirmed
Forge-managed artifacts; do not touch personal SSH configuration or other units.

See [evidence.md](evidence.md) for exact tests, failure corrections and limitations.
No UI flow, two-machine deployment or live Codex acceptance is claimed here.
