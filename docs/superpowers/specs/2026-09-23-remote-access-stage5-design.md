# Remote Access Stage 5 — execution, revoke and recovery

Status: implemented in the Stage 5 review unit; see evidence.md for validation and limitations.
Base: PR #145, merged at `edf49dbfa34643fdbd66fa4aa6a3bbaa78df42d2`.
Authority: human Remote Access roadmap, Stage 5. Stages 6–9 are excluded.

## Outcome and existing boundaries

An ACTIVE ACCESSOR can run a non-interactive command on its GRANTOR through the
existing pinned SSH channel. Input, separate output streams, exit status, timeout
and cancellation must be real. A GRANTOR persists REVOKING before cleanup and
reports REVOKED only after positively confirmed cleanup. No replay after a
transport failure and no local execution fallback.

Current extension points were inspected:

- `RemoteAccessAccessorPairing` and `RemoteAccessGrantorPairing` own handshake
  recovery. They must retain the PR #145 admitted-confirm timestamp semantics.
- `RemoteAccessChannelService` checks immutable authenticated key bindings against
  persisted GRANTOR state. `RemoteAccessChannelServer` owns the exclusive local
  authority socket lifecycle and bounded control requests.
- `RemoteAccessSessionRepository.transition` uses optimistic versions. Its
  persisted session remains the authorization source of truth.
- `forced_command.py` currently permits only pair/redeem/confirm/status. It never
  delegates arbitrary text to a host shell.
- `invitation_supervisor.py` owns public-key installation/removal only. Its
  existing single-request loop must not carry long-running workload streams.
- `RemoteAccessControlProcess` is a bounded pairing RPC executor, not a streaming
  executor. The existing `TypedProcessExecutor` is also unsuitable for this path.
- The Stage 0 rootful probe proves selected systemd isolation mechanisms with a
  stub supervisor. It does not prove the production execution/revoke gate.

## Selected implementation approach

Extend the existing Java authority with dedicated execution/revocation use cases
and narrow domain ports. Add a separate root-owned workload supervisor process
and installer-owned systemd unit. Preserve the existing invitation supervisor's
responsibility. Do not make Agent root, add an HTTP remote shell, or change
workflow executors, session states, NodeTypes or repository routing.

Alternatives rejected: running commands as forge-ssh exposes control-adjacent
identity; putting command streams through the pairing executor introduces short
RPC deadlines and output buffering; a VM-per-command lifecycle exceeds V1 scope.

### Explicit prepared context

The operator prepares a root-owned session manifest before command execution:
canonical session UUID, dedicated workload UID/GID, read-only root filesystem,
and a writable session workspace. Preparation refuses symlinks, writable control
parents, conflicting ownership and privileged groups. It never silently shares
an operator home or repository. Missing preparation is NOT READY.

Each session retains its workload identity until all owned command units and
pending starts are positively absent. Commands use systemd services below a
session-specific slice, `KillMode=control-group`, bounded stop/SIGKILL,
`Delegate=no`, no capabilities, NoNewPrivileges, protected homes/proc/devices,
read-only root filesystem and only the explicitly prepared writable workspace.
No host /run, control socket, DB socket, Docker socket or credential directory is
mounted. Private network has no host route; dependencies must be prepared in the
context. Peer input cannot select unit properties, host mounts, UID or rootfs.

The isolated rootfs is a trusted operator artifact. Workload cwd is interpreted
inside that namespace; it cannot become a host-side path or systemd option.

### SSH command transport

The existing pinned SSH builder remains authoritative for identity and host
checking. A new typed command request carries bounded argv, namespace-relative
cwd, timeout and execution identity in a bounded control header. All following
SSH stdin bytes belong to command stdin. stdout and stderr remain separate raw
streams, and the SSH command exit status represents the workload result.

The helper derives the binding from authenticated SSH key evidence; supplied
session IDs are never credentials. It requests admission through the protected
Agent channel, then attaches only the single supervisor execution authorized by
that admission. Attach authorization is one-use, bound to that execution and
session; it is not a new persistent credential. No command text goes through a
local shell. Explicit remote shell execution is possible only as an argv inside
the isolated workload.

The supervisor receives stream descriptors over its protected local connection;
its daemon must not block waiting for a command to finish. Stream pumps use fixed
buffers and OS backpressure, never accumulating complete output. Concurrency and
pending attachments are bounded. Stdin EOF closes only workload stdin. Timeout,
cancel and connection loss stop the registered unit, including descendants;
transport loss never repeats the command. Descendants cannot outlive a completed
execution by keeping inherited output descriptors open.

### Admission versus revoke

Use the existing exclusive authority ownership plus a per-session admission
critical section. All production GRANTOR lifecycle transitions to REVOKING,
including expiry reconciliation, participate in this gate. No independent stale
writer may authorize execution.

Admission reloads the persisted ACTIVE session and validates the binding under
that gate. It durably registers a supervisor-owned execution before starting a
unit and waits only a bounded amount for start registration. The gate is held
until that admission succeeds or fails. Database transactions remain short;
there is no DB transaction/row lock around SSH, supervisor waits or process stop.

Revoke obtains the same gate, persists REVOKING with CAS, and releases it. Every
later admission reloads REVOKING and fails. Cleanup closes the supervisor's
session gate, cancels pending starts/attachments, removes the SSH grant and stops
all registered units. The root-owned registry identifies units using generated
UUID names, never arbitrary peer-supplied names. It is cleanup bookkeeping, not a
second source of authorization.

If a start was admitted before REVOKING but its systemd job is still pending,
cleanup must cancel that job and prove there is no remaining pending start.
Persisted registration precedes systemd submission so a crash cannot orphan an
unregistered command. CAS loss always reloads/respects the winner.

### Revoke result and ACCESSOR recovery

GRANTOR cleanup requires successful machine-readable systemd inspection,
confirmed stopped/absent units, empty owned cgroups and no pending jobs. Empty
output, timeout, nonzero return, malformed fields or reset-failed are not proof.
Failures leave REVOKING with a bounded safe reason and retained registry. Cleanup
continues bounded attempts for other commands in that session. Other sessions'
units and grants are untouched.

The authenticated revoke channel can remain open long enough to return the
confirmed result after its key grant is removed. ACCESSOR first persists its own
REVOKING, preventing local new executions, then requests remote revoke. It only
confirms REVOKED and removes the local credential after authenticated confirmed
cleanup. Offline/lost acknowledgement remains REVOKING, credential retained.
Permission denied is not confirmation; the grant is never restored for an ack.
This deliberately preserves an unconfirmed audit outcome when the reply is lost
and the old key can no longer connect.

Credential removal failures require a safe persisted cleanup reason and retry;
no failure may silently drop the last reference to a still-present private key.
No audit row is hard-deleted.

### Crash and hang handling

The workload supervisor has a new process epoch after restart, initially closed
for admission. It reconciles all owned durable registrations and systemd units,
stopping old workloads before enabling new admission. Existing grants never
imply execution readiness. Agent startup reconciles REVOKING first and never
reinstalls revoked grants.

Managed units bind to the workload-supervisor service lifecycle. A systemd
watchdog bounds a hung supervisor. An authenticated local authority heartbeat
with monotonic expiry closes admission and stops workloads if Agent dies or
hangs. A stale epoch cannot renew or attach new commands. The supervisor feeds
its own watchdog only while authority monitoring/cleanup can make progress.
Shutdown/restart cannot declare cleanup complete without the same positive unit
checks. Lease/watchdog durations are bounded fail-closed mechanisms, not fake
session states or ACTIVE connectivity claims.

## Required regression evidence

Write failing tests before implementing each responsibility:

1. Admission versus revoke with deterministic barriers, including a pending
   systemd start, CAS loss and an already-open SSH channel. No new admission after
   persisted REVOKING and no orphan pending job after confirmed REVOKED.
2. Real stdin, independent large stdout/stderr, nonzero exit, long-running output,
   output backpressure, timeout/cancel, broken transport and no retry/fallback.
3. Real systemd parent/background/setsid cleanup; session B survives revoke A.
4. Inspection failure, failed kill and inaccessible supervisor leave REVOKING;
   successful cleanup preserves unrelated artifacts and original command errors.
5. Agent crash/hang, supervisor crash/hang and restart during each registration,
   start and revoke boundary; old epochs cannot start commands.
6. Workload attempts against protected files, authority/admin sockets and HTTP,
   other sessions, sudo/root, Docker, mounts and inherited descriptors all fail.
7. ACCESSOR offline revoke, lost ack, private-key cleanup failure and restart
   retain truthful state. Existing Stage 4 live pairing and JVM crash cases stay
   green, including the confirm-vs-expiry regression.

Unit mocks establish ordering, not OS/security proof. The privileged suite must
use real sshd, production authority/persistence and a real system systemd manager
in an isolated environment. Missing capabilities produce NOT_RUN/NOT READY, not
PASS. Existing Docker Stage 4 SSH tests alone cannot establish systemd cleanup.
No real credential/history reads; use synthetic canaries.

Run full Agent and Nexus verify, Console tests/typecheck/build, Python suites,
actual privileged execution tests and diff checks. Record exact commands, failures,
reruns and environment limitations in evidence. No Stage 9 live Codex claim.

## Review boundary

This document records the approved design. Implementation uses an additional
root-owned per-execution ConditionPathExists fence against delayed submission,
and independent attachment/control/heartbeat pools following review findings.
See the file-level plan and evidence for actual tested behavior. Stop after Stage 5 review unit;
no management REST/UI, forge-remote CLI or subsequent stage implementation.
