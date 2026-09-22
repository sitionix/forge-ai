# Stage 0 evidence — 2026-09-22

**Stage 0: READY_FOR_REVIEW. Production access: NOT READY.** External acceptance
has not been given. Base `c62d0bd9`, branch `feature/SITIONIX-134`.
[Design](design.md), [stage gates](roadmap.md).

## Environment and authorization

- Host Linux 7.0.0-31-generic, PID 1 systemd 259 (259.5-0ubuntu3.4).
- Host client OpenSSH_10.2p1 Ubuntu-2ubuntu3.6, OpenSSL 3.5.5.
- Disposable image OpenSSH_10.0p2 Debian-7+deb13u4, OpenSSL 3.5.6.
- Host `/usr/sbin/sshd` absent. Initial `sudo -n true` required interactive
  authentication. These facts initially produced NOT READY, not a fabricated PASS.
- Following the user's instruction, a separate terminal performed interactive
  sudo. Password was entered in the terminal, never read or logged by the probe.
  `sudo id -u` confirmed root; the combined probe then ran in that terminal.
- User systemd is available but globally reports degraded. Its test units worked;
  this does not certify general host health.
- No host SSH installation/configuration, personal keys, production Forge service,
  database, persistent user account or workflow was changed.

## Reproduction

Read the scripts before running privileged fixtures. They are tests, not production
installers. Use only the exported image built here, not an untrusted tar archive.

```sh
docker build -t forge-remote-stage0-probe:local docs/remote-access/probes
docker run --rm --network none --name forge-remote-stage0-ssh-probe forge-remote-stage0-probe:local
python3 docs/remote-access/probes/process_boundary.py

docker create --name forge-stage0-rootfs-export forge-remote-stage0-probe:local
docker export -o /tmp/forge-stage0-rootfs.tar forge-stage0-rootfs-export
docker rm forge-stage0-rootfs-export
# Run this in an interactive terminal:
sudo python3 docs/remote-access/probes/privileged_boundary.py /tmp/forge-stage0-rootfs.tar
```

The image build downloads packages into the image, not the host. The standalone
SSH container has no host mounts, no published ports, no privileged flag and no
external network. Only generated synthetic keys/canaries are used. Image and
exported `/tmp/forge-stage0-rootfs.tar` remain as reproduction artifacts, not
installed services. No production keys or credentials are read.

## Baseline probes

`ssh_boundary.py`: exit 0, 11 assertions. Real sshd: pairing/session forced command,
requested shell side effect absent, wrong host pin rejected, PTY/direct TCP/remote
TCP forwarding denied, workload cannot write authorization/helper, read synthetic
control canary or connect to protected Unix listener. The listener is a fixture,
not a Forge supervisor. This test alone does not test systemd.

`process_boundary.py`: exit 0, 12 assertions. Two actual user-systemd units, each
with parent, background child and setsid child. Actual /proc membership matches
separate cgroups. Stopping A removes all its PIDs; B remains alive. This test alone
does not prove cross-UID containment or production revoke.

## Combined privileged probe

`privileged_boundary.py`: final exit **0**, **40 PASS assertions**, followed by:

```text
BOUNDARY_PROBE_PASS: real SSH + stub supervisor + distinct UIDs + system systemd; NOT Forge runtime E2E
CLEANUP_PASS: only owned units/rootfs removed
```

The full successful assertion output is retained in
[privileged-result.txt](probes/privileged-result.txt).

Actual path tested:

```text
host SSH client enters isolated sshd network namespace
→ pinned synthetic key authenticates to rootfs-only forgepeer account
→ root-owned forced Python helper (pair / session a / session b)
→ Unix admission socket checks SO_PEERCRED UID
→ fixed-operation root stub supervisor
→ systemd-run workload service with RootDirectory + DynamicUser + StateDirectory
→ real processes under distinct workload UID / private network / managed cgroup
→ systemctl stop A, verify every A PID gone and all B PIDs alive
```

The sshd namespace listens on its own 127.0.0.1:22222, never host/LAN. Host `nsenter`
is test-driver privilege, not access granted to a workload. Each workload has a
separate network namespace, PrivateDevices/PrivateTmp, NoNewPrivileges, empty
capability bounding set, ProtectSystem=strict, ProtectHome, ProtectControlGroups,
ProtectProc=invisible, RestrictSUIDSGID and only its StateDirectory writable.

Checks include:

- Positive host connection to a synthetic admin listener, then failed workload
  connections to that same loopback port. Namespace inode checks independently
  prove both workloads differ from the host and each other.
- Actual workload UIDs differ from root, SSH identity and each other.
- Both workloads fail authorization/helper writes, control canary/host private-key
  reads and admission-socket connection; no Docker socket or system bus is visible.
- Each can write its own result file; the separate protected workspace fixture is
  inaccessible. This is not a complete cross-session ACL attack suite.
- Parent/background/setsid PIDs all match their system-managed cgroups. Stopping A
  leaves no A processes and does not stop B. No manual cgroup directory creation.
- No caller-supplied arbitrary unit properties/commands are accepted by the fixture.

## Failures that informed the final probe

1. Initial combined run: sshd could not see `/run/sshd` because systemd supplies the
   isolated runtime mount. Fixed fixture startup to create it inside RootDirectory.
2. PTY check inherited the interactive terminal stdin and timed out. Fixed test
   driver to supply DEVNULL; this is a non-interactive probe, not stdin forwarding.
3. Numeric workload UID without a host user failed with systemd 217/USER. Replaced
   fixture identities with DynamicUser and StateDirectory, avoiding host user edits.
4. Successful run passed 35 assertions. Final strengthened run added positive admin
   listener reachability and explicit actual UID/network namespace checks: 40 pass.

Every attempted run reported cleanup of its own units/rootfs. Final script checks
unit inactivity before removing owned artifacts. Image export container was removed.
No failure was treated as acceptance, and no production feature was manually fixed.

## Acceptance matrix — test scope, not external approval

| Stage 0 requirement | Evidence | Boundary of claim |
| --- | --- | --- |
| Wrong host key denied | Real SSH exit 255 / host verification failure | Synthetic pinned full key, not production invitation provisioning |
| Pairing cannot open shell/PTY/forwarding | Forced marker, no requested side effect; PTY, direct and remote TCP denied | Dedicated agent/X11/Unix forwarding attempts NOT_RUN; sshd DisableForwarding enabled |
| Forced session command | Actual session helpers reach stub supervisor | No Agent-backed session gate, persistent pairing or lifecycle yet |
| Workload cannot change control files | Real workload UID + read-only filesystem/permissions | Synthetic authorization/helper/private-key files only |
| Workload cannot access control/admin | Actual Unix permission denial + private network denial to known-live host listener | Production admin auth/Origin/CSRF still Stage 2/6 work |
| Background/setsid remain managed | Actual PID/cgroup membership and disappearance | System-systemd probe, not application revoke fencing |
| Cleanup one session preserves another | A gone, B alive | Two fixed test sessions, not persistence/restart recovery |
| Missing capabilities explicit | Initial NOT READY, then interactive sudo/rootfs prerequisites met | No claim that production install is ready |

## Remaining limits and future stage gates

- Supervisor is a fixed-operation **stub**, not the privileged production service.
  No real invitation/session state, DB, key installation recovery or authoritative
  REVOKING/REVOKED transition is exercised. Runtime E2E is NOT_RUN.
- DynamicUser is per test service. Production session UID retention/reuse across
  commands and restarts remains to be implemented and tested. See design.
- Workload cannot reach the synthetic admin/control path tested here; this does not
  implement operator browser authentication, Origin/CSRF or Nexus service auth.
- No watchdog/authority-crash/restart, start-vs-revoke race, forwarding variant
  matrix, escape penetration suite, complete toolchain build or two-host LAN test.
- Actual Codex and UI were not used. REMOTE_ACCESS_RUNTIME_E2E_PASS,
  REMOTE_ACCESS_UI_FLOW_PASS, REMOTE_ACCESS_CODEX_LIVE_PASS: all **NOT_RUN**.
- Only docs and disposable probes changed. Product Agent/Nexus/Console suites were
  not rerun; they cannot prove these OS boundaries. Python syntax and new-file
  whitespace checks are run separately.

Stage 1 remains unauthorized. The user subsequently authorized a Stage 0 review PR.
No reviews/comments, merge or deployment were performed.
