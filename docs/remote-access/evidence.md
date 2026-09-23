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

## PR #141 cleanup regression fix

The original finally block ignored systemctl return codes and accepted empty
ActiveState output. When both stop/show failed to connect to the bus, it removed
artifacts and emitted CLEANUP_PASS without confirming unit termination. The prior
successful live run did not cover this failure path.

TDD reproduction used the original main() and mocked only external operations:
stop/show returned exit 1, empty stdout and `Failed to connect to bus`. The new
regression failed on the unchanged implementation with:

```text
AssertionError: 'CLEANUP_PASS' unexpectedly found in
'CLEANUP_PASS: only owned units/rootfs removed\n'
Ran 1 test ... FAILED (failures=1)
```

The cleanup implementation is now a small function in the same probe. Every unit
gets bounded stop and inspection attempts even if an earlier attempt raises or
times out. Inspection must succeed and provide exactly LoadState, ActiveState,
SubState and MainPID. Only inactive/dead with MainPID=0 is accepted, with loaded
or explicitly not-found LoadState. A nonzero stop is tolerated only if a successful
subsequent inspection positively identifies absence. Stop exceptions/timeouts
remain failures even if a later query reports absence. Unknown, failed, active,
deactivating, empty, duplicate or incomplete state responses fail closed.

The real host's `systemctl show` for a nonexistent test unit returned exit 0 with
LoadState=not-found, ActiveState=inactive, SubState=dead, MainPID=0. No stderr text
is parsed. reset-failed is no longer used. If any unit is unconfirmed, rootfs and
state directories/symlinks remain untouched, CLEANUP_FAILED identifies the retained
rootfs, and the raised RuntimeError leaves the probe unsuccessful. Successful
cleanup during an earlier probe exception does not catch or suppress that exception.

Mocked regression verification (not live SSH/systemd):

```sh
python3 -m unittest discover -s docs/remote-access/probes -p 'test_privileged_cleanup.py' -v
# 9 tests passed, including parameterized subTest cases.
python3 -m py_compile docs/remote-access/probes/privileged_boundary.py docs/remote-access/probes/test_privileged_cleanup.py
git diff --check
```

Tests call actual cleanup code (and actual main for early-failure regressions),
use temporary directories, and do not require root or touch real /var/lib/private.
Negative cases assert concrete CLEANUP_FAILED, retained artifacts, no CLEANUP_PASS,
no rmtree/unlink calls and attempts for the remaining units. Positive cases assert
only owned artifacts removed; the original early failure remains the raised error.

Actual combined privileged rerun after the fix: interactive sudo, same isolated
rootfs/system-systemd environment, **exit 0, 40 PASS assertions, CLEANUP_PASS**.
This is real SSH/process evidence with the same explicitly stubbed supervisor,
not production remote-access/Codex E2E. The captured rerun output was copied to
privileged-result.txt; its text is byte-identical to the previous successful run
because the existing success-path messages did not change. The previous output
was not used as evidence of running the fix.

Verified script SHA-256: `8f5e617cb5434ecb025ee06c6ffbbd8d580cef16e1400e6e0281df4a21f955ce`.
Local rerun capture: `/tmp/forge-stage0-cleanup-fix.log`, exit capture:
`/tmp/forge-stage0-cleanup-fix.exit`. No production suites needed or run for this
probe-only correction. No Stage 1 work or PR metadata/review/comment/merge changes.

## Stage 1 — persistence and credentials (2026-09-22)

PR #141 was merged by the user's explicit admin-merge authorization. Stage 1 is
based on `b6516ca2e6b6289e6365707683fd730f3faa43c2`, branch
`feature/SITIONIX-135`. Stage 2 remains unauthorized.

The new boundary consists of immutable invitation/session aggregates, narrow
repository ports, JDBC adapters and forward-only V37 migration. Existing
SshConnection and AgentExecutionSession models/migrations are unchanged. The
singleton Forge identity survives context restart. Invitation expiry remains a
server-clock comparison; there is no expiry scheduler or implicit ACTIVE state.

Grantor reservation conditionally consumes the invitation and inserts its unique
PROVISIONING session in one database transaction. A deferred redemption FK is
checked at commit. Accessor invitation IDs intentionally have no FK to local
invitations, because those invitations belong to the remote grantor. Session
transitions validate domain state and use version/status compare-and-set, so a
stale writer cannot reactivate a revoked session.

The accessor stores supplied private material through a narrow local adapter,
then persists only the opaque UUID reference. Directory/file modes are 0700/0600;
creation is exclusive, symlink/insecure ancestor paths fail closed, and exceptions
remove only newly created owned material. Private material has a redacted string
representation, defensive byte copies and explicit zeroization on close. No
public DTO, token generation, SSH authorization or remote operation is added.

### Test method and limits

Domain and key-store regressions were written before their implementations.
The initial database regression failed against V36 because the new tables did
not exist; V37 plus adapters made the real-PostgreSQL tests pass. Unit tests use
JUnit/Mockito/AssertJ with direct SUTs. Persistence tests use actual PostgreSQL 16
via Testcontainers, including concurrent redemption, transaction rollback,
V36-to-V37 migration, stale transitions and complete Spring context restart.
Credential tests use real temporary POSIX files and synthetic key material.
These are persistence/filesystem tests, **not live SSH/Codex E2E**.

Independent review found that a default REQUIRED transaction could join an
outer transaction and let its later rollback escape key compensation. A real
PostgreSQL regression was added for that boundary. Provisioning must own its
commit boundary; callers must not invoke it inside an ambient transaction.

DB and filesystem writes are not claimed to be atomic. A process/host crash
between key creation and DB commit can leave an orphan key. Targeted crash
reconciliation, cryptographic key validation/generation, activation proof and
confirmed revoke cleanup belong to later stages. No production grant can exist
at this stage. Key storage currently requires the tested Linux/POSIX filesystem
semantics and a protected control-user directory; it does not install the Stage 2
OS identity boundary. Default directory:
`${FORGE_RUNTIME_DIR:./var}/agent/remote-access/credentials`, override property
`forge.agent.remote-access.credential-directory`.

Verification results are recorded below after the final run. No live SSH or
Codex E2E was run for Stage 1 (NOT_RUN); unchanged Stage 0 evidence is historical,
not a claim of production remote access readiness.

### Final Stage 1 verification

- Ambient-transaction regression before correction: **RED**, Maven exit 1,
  `Expecting code to raise a throwable.` After correction: **GREEN** in the
  focused and full suites. Local capture: `/tmp/remote-stage1-ambient-red.log`.
- Focused Stage 1 matrix: **37 tests, 0 failures/errors/skips**, including 11 real
  PostgreSQL tests. Capture: `/tmp/remote-stage1-focused.log`.
- `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`: **exit 0**,
  782 reported tests, 780 executed successfully and 2 existing opt-in live Codex
  tests skipped (live-recovery-e2e/live-session-e2e flags absent). Final run is
  after the transaction fix. Capture: `/tmp/remote-stage1-agent-verify.log`.
- `mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify`: **exit 0**,
  236 tests, no failures/errors/skips. `/tmp/remote-stage1-nexus-verify.log`.
- Console `npm ci --ignore-scripts`, `npm test`, `npm run typecheck`,
  `npm run build`: **exit 0**, 20 test files / 545 tests passed.
  `/tmp/remote-stage1-console.log`.
- `git diff --check` and `git diff --cached --check`: **PASS**. Production changes
  are restricted to new Stage 1 Agent models/ports/service/adapters/V37; no
  existing production file or old migration is changed.
- Independent code re-review: no remaining blocking finding after the
  transaction-boundary fix. This is not external stage acceptance.

Stage 1: **READY_FOR_REVIEW**. Stage 2 is not started. No new PR metadata,
reviews/comments, deployment, SSH grants or live Codex execution were produced.


## Stage 2 — managed SSH/channel boundary (2026-09-22)

Base: merged Stage 1 PR #142, `3b374d0e1746768f72014e0c39d3fa44190d4583`.
Branch: `feature/SITIONIX-136`. The user's “merged” message authorized this next
stage; Stage 3 is not started. Setup/ownership and operational limits are in
[stage2-installation.md](stage2-installation.md).

Implemented: dedicated sshd config/unit/installation, global forced helper,
authenticated-key binding lookup, restricted Unix socket with kernel peer identity,
current persisted Agent session authorization and isolated pinned client status
argv. No Agent root execution, key-publication API, pairing/activation, workload,
HTTP remote shell or project/workflow routing changes. CI now runs the new Python
and disposable SSH suites in the Agent job.

Tests preceded implementation for authority, Unix channel, client policy and
installer/helper. Independent review found two required installer defects:

- A dangling host public-key symlink could redirect ssh-keygen's root write.
  Regression invoked the actual extracted generation block and failed before
  correction. Both destinations are now checked before any key generation;
  failure leaves the unrelated target and private key absent. Existing public
  material must also match the private host identity.
- After reboot, `/run/sshd` was not supplied by the managed setup, so startup
  depended on the unrelated host sshd. The real container regression removed
  that directory, applied only managed tmpfiles, and still saw sshd -t fail.
  Adding standard root:root 0755 provisioning made the same regression pass.

A final client check found that owner-only files under a writable ancestor could
still be replaced. The actual client accepted that insecure test path before the
fix. It now rejects foreign-owned and writable non-sticky ancestors; the
regression explicitly sets its POSIX preconditions, independently of umask.

Final verification:

- `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`: exit 0.
  Surefire: 787 reported, 785 passed / 2 existing opt-in skips. Failsafe: 322
  reported, 316 passed / 6 existing opt-in skips. No failures/errors.
  `RemoteAccessPersistenceIT`: 12 passed, including current persisted revoke
  authorization. Focused IT results left in Surefire by -Dtest were excluded
  from the unit count to avoid counting them twice.
- `mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify`: exit 0;
  236 unit and 38 integration tests passed.
- Console: 20 files / 545 tests passed; typecheck and build exit 0.
- Python installer/helper unittest: 12 passed; py_compile passed.
- Disposable real SSH suite: **28 PASS assertions**, exit 0. Captured actual
  output: [stage2-ssh-result.txt](stage2-ssh-result.txt). Includes global forced
  command despite a bare authorized-key fixture, host pin mismatch, foreign key,
  forged command/session, peer environment injection, PTY/subsystem/forwarding,
  provisioning/denied/unavailable authority, protected files, idempotent setup,
  missing-systemd NOT READY and reboot-style tmpfiles restoration.
- The production client argv was also inspected by actual `ssh -G`; effective
  identity, pin, forwarding and local-command configuration matched the policy.
- Whitespace checks passed. No old migration, SshConnection or execution/workspace
  semantics changed.

Evidence limits: real sshd + installed production helper/config, but **stubbed
control authority** in the Docker suite. Actual Agent authorization against
PostgreSQL and actual Unix peer-credential transport are tested separately.
The container runs without privileged mode, host mounts or host/LAN networking.
Systemd unit syntax and boot tmpfiles are real checks; a live systemd-managed
service start was **NOT_RUN** in this container. Its installer explicitly reports
NOT READY without the system manager. No production host installation or grants,
workload containment/revoke execution, or live Codex E2E are claimed.

Recorded implementation decisions: direct Agent-owned channel socket needs no
privileged runtime operation at Stage 2; dynamic publication supervisor belongs
to Stage 3. Existing Agent service ownership is not silently migrated. Stale
socket paths fail closed and need verified operator cleanup until later crash
reconciliation. Client paths currently reject spaces/expansion characters. These
limits are explicit in the installation guide, not silent fallbacks.

READY_FOR_REVIEW. Independent review findings are fixed with RED→GREEN evidence;
this is not external stage acceptance or a claim of production access readiness.


## PR #143 correction — loopback-only authority HTTP bind

The enabled Stage 2 authority previously had no invariant preventing its ordinary
Agent HTTP listener from binding wildcard/LAN interfaces. This correction adds
Agent-owned `FORGE_AGENT_HOST` → standard `server.address` mapping and a small
boot-level validator. It runs after Boot's server factory configuration and
rejects null/non-loopback resolved InetAddress before HTTP listener creation.
With the channel disabled the validator is absent; legacy bind behavior remains.
No default is substituted by validation.

The existing systemd renderer explicitly emits `FORGE_AGENT_HOST=127.0.0.1` for
its dedicated forge-control runtime. Explicit operator-provided values are
preserved and unsafe enabled configurations fail at startup. A new renderer
regression first failed because both dedicated loopback and explicit ordinary
host values were absent; it passes after the mapping correction. Other services
ignore this Agent-owned variable in the common env file.

Tests cover disabled/missing and disabled/LAN; enabled IPv4/IPv6 loopback and
localhost; enabled missing/empty, IPv4/IPv6 wildcard and LAN rejection. Typed
hostname-address fixtures exercise LAN/loopback resolution without external DNS.
The actual Agent SpringBootTest starts real Tomcat on port 0 with the channel
flag enabled, production FORGE_AGENT_HOST mapping, PostgreSQL and an HTTP request
to the bound 127.0.0.1 connector. Only the separately tested privileged Unix
socket lifecycle is mocked in that HTTP IT; HTTP binding is not mocked. IPv6 is
covered at config level; this correction's live HTTP check uses IPv4 loopback.

This is a local bind restriction, not HTTP authentication. Full operator login,
browser sessions, CSRF and Nexus service credentials remain Stage 6. SSH gate,
channel/helper/client semantics, persisted sessions and V37 remain unchanged.
Verification of the corrected implementation:

- Focused Agent boot/config and HTTP bind tests: PASS.
- Full `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`: PASS.
  The reports include 4 validator tests, 1 real HTTP bind IT and all 12
  RemoteAccessPersistenceIT tests, without failures or skips in these classes.
- Stage 2 `test_managed_ssh.py`: 13 tests PASS, including generated runtime config.
- Stage 2 Docker image build: PASS; `docker run --rm --network none
  forge-remote-stage2-test`: PASS, 28 real SSH assertions. That suite uses its
  existing stub authority; it is not a live full Agent/Codex E2E.
- `git diff --check`: PASS.

No Stage 3+ functionality was added. These results provide correction evidence
for external review, not stage acceptance.


## Stage 3 — invitation-only Give Access (2026-09-23)

Base: merged PR #143, `887a57701599df4de452a207e1be6a0d77e4474f`.
The user authorized Stage 3 after merge. Branch `feature/SITIONIX-137`.
Implementation and installation details: [stage3-invitations.md](stage3-invitations.md).
No production host installation, pairing session activation, REST/UI or workload
execution was performed. No Stage 4+ work is included.

### Regression and independent review

- Invitation eligibility RED: fail-closed stub denied a valid stored invitation;
  the positive assertion failed. GREEN uses local identity, matching fingerprint,
  authoritative persisted consume/cancel state and server-clock expiry.
- Forced helper RED: an invitation `pair` request returned DENIED. GREEN permits
  only its bound pairing check; session status/commands remain denied.
- Unix channel RED: the new PAIR frame returned DENIED; GREEN reaches only the
  typed invitation authority, not session status.
- New token/key, lifecycle, supervisor/client and grant-store tests were written
  before their implementation; their initial runs failed on absent types/modules.
  These are new-feature scaffolding failures, not claims of pre-existing defects.
- Independent read-only review found two defects and both were fixed: known
  Stage 2 helper installation conflicted rather than upgrading; Jackson accepted
  fractional numeric version/port values. Upgrade regression reproduced the real
  installation conflict, and fractional-token regression failed with "Expecting
  code to raise a throwable". Both now pass. Re-review confirmed both corrections;
  this does not substitute for external stage acceptance.
- Existing DB test fixtures used a deliberately incomplete fingerprint; the new
  invitation binding correctly rejected it. The relevant fixture now uses a
  valid SHA256 fingerprint shape. Persisted production semantics were unchanged.

### Verification

- Focused application tests: 5 invitation lifecycle tests and 2 invitation gate
  tests pass, alongside existing session authority tests.
- Typed token tests: 4 pass, including malformed envelope/key/endpoint, self
  pairing, fractional/scalar rejection and redaction.
- Actual Unix channel/client tests pass; only the test authority/supervisor
  responses are fixtures in those Java transport tests.
- Real PostgreSQL `RemoteAccessPersistenceIT`: 14 tests pass in full verify,
  including restart/consume/cancel/expiry, concurrent reservation and changed
  token expiry not extending the server's persisted lifetime.
- Final `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`
  after the strict-number correction: PASS (exit 0). Failsafe: 325 tests, zero
  failures/errors, six existing opt-in live-executor skips.
- `mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify`: PASS.
- Console: 545 tests PASS; `npm run typecheck` and `npm run build`: PASS.
- `python3 -m unittest discover -s scripts/remote-access/tests -p 'test_*.py' -v`:
  19 tests PASS. CI discovery now includes both grant-store and existing SSH tests.
- Docker build PASS; `docker run --rm --network none forge-remote-stage3-test`:
  46 assertions PASS. The fixture uses real root installation/upgrade, actual
  root supervisor, separate control/transport UIDs, real sshd, host pinning and
  forced helper. It checks pairing-only permissions, authority denial, grant
  removal, preservation of an independent session grant and protected files/socket.
- The Docker Agent authority is explicitly a stub. Systemd units receive static
  verification; the new supervisor is started directly inside the container.
  This is not a full two-Forge/systemd E2E or live Codex run. The existing Stage 9
  runtime/UI/Codex acceptance labels remain NOT_RUN.

`git diff --check`: PASS. No Nexus/Console production changes or migration
changes. READY_FOR_REVIEW; Stage 4 stays unauthorized.

## Stage 4 — persisted SSH pairing and activation (2026-09-23)

Base: PR #144 merged at `43a1ea51a30b9bdf869bd67e97ea7f7d86f468b2`.
The user authorized this stage after merge. Branch `feature/SITIONIX-138`.
Implementation/recovery detail: [stage4-pairing.md](stage4-pairing.md).
No host installation, workload execution, peer HTTP API, UI or Stage 5 work.

### Regression and review evidence

- Application lifecycle RED initially failed on absent Stage 4 contracts/services;
  GREEN covers reservation-before-install, no premature activation, matching-key
  proof, expired/failed grant cleanup, and session-key recovery after lost ACK.
  New ACCESSOR tests assert durable preparation precedes the first SSH operation
  and an existing attempt does not create a new key or redeem the token again.
- Typed Unix REDEEM socket regression demonstrated Jackson Integer→String
  coercion despite `ALLOW_COERCION_OF_SCALARS=false`. Explicit Textual coercion
  rejection fixes the peer payload. The same focused token-envelope regression
  reproduced the issue and now rejects numeric/boolean display-name fields.
- Independent scoped OS review found that overwriting supervisor Python bytes
  would leave an old running interpreter active. Regression first failed;
  installer preflight now refuses version-changing upgrade while its managed
  runtime directory exists. The real SSH fixture starts the exact Stage 3
  supervisor, observes refusal without file changes, then stops/removes the
  managed runtime fixture, upgrades, restarts and installs a session grant.
  Re-review found that correction complete; it is not external stage acceptance.
- Scheduler isolation regression was RED with pairing on the ambient application
  scheduler: an unrelated scheduled task could not proceed while recovery was
  blocked. GREEN uses a lifecycle-owned private timer, leaving the existing
  workflow/lease executor routing unchanged. The test intentionally constrains
  the ambient scheduler to one thread; the current production heartbeat executor
  has two threads, so this demonstrates isolation rather than claiming every
  production recovery call previously stopped all workflow activity.
- A final expiry-vs-activation regression reproduced stale ACCESSOR cleanup
  writing PROVISIONING_EXPIRED onto a concurrently activated row after its CAS
  failed. GREEN returns the current row on CAS failure without overwriting its
  failure metadata. Focused re-review confirmed this correction.
- Separate read-only review of lifecycle, persistence and transport reported no
  concrete correctness/security findings in that scope.

### Focused checks

```sh
mvn -q -pl services/forge-agent/application -am \
  -Dtest=RemoteAccessPairingLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -q -pl services/forge-agent/infrastructure/local -am \
  -Dtest=LocalPairingTokensTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 -m unittest discover -s scripts/remote-access/tests -p 'test_*.py' -v
docker build -f scripts/remote-access/tests/Dockerfile -t forge-remote-stage4-test .
docker run --rm --network none forge-remote-stage4-test
```

- Application: 10 tests PASS. Token envelope: 4 tests PASS.
- Focused channel server / transport / bounded process / SSH command / supervisor
  client: 20 tests PASS. These targeted Java tests use fixture peer responses.
- Python: 33 tests PASS. Local socket tests require permission to bind sockets;
  an initial sandbox denial is not counted as a product failure or successful run.
- Docker OS suite: 70 assertions PASS with real sshd, root supervisor, forced
  helper, protected files and separate UIDs. Its Java authority is **stubbed**;
  `STAGE4_PAIRING_SSH_PASS` means routing/boundary coverage, not full pairing.
- Console: `npm run typecheck`, 545 tests and `npm run build` PASS.

### Production pairing integration and final regression

```sh
mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
```

Both final commands passed (exit 0), including the final expiry/CAS correction.
Agent Failsafe: 327 tests, zero failures/errors, six existing opt-in skips.
`RemoteAccessPersistenceIT` includes real PostgreSQL recovery lookups and safe
failure-metadata CAS, in addition to reservation concurrency and application
context restart. The scheduler isolation test passes on the real Spring
scheduling path with an intentionally occupied recovery call.

`RemoteAccessLivePairingIT` passes all six scenario checkpoints:

1. Persisted same-ID ACTIVE on both peers, dedicated private credential only in
   the ACCESSOR store, real SSH status readback.
2. Consumed invitation and wrong session key rejected through actual SSH.
3. Successful real redeem/confirm responses deliberately dropped before local
   acknowledgement; reconstructed services/adapters recover with the same key.
4. Reservation committed before injected grant-install failure; reconstructed
   GRANTOR reconciles the grant and ACCESSOR confirms with its saved session key.
5. Two independent ACCESSORs concurrently redeem through SSH; exactly one
   GRANTOR session becomes ACTIVE and the losing attempt has no GRANTOR row.
6. An injected clock beyond both persisted deadlines causes real grant removal
   and confirmed GRANTOR REVOKED; SSH rejects the old key. ACCESSOR keeps its key
   and REVOKING audit state because rejection is not remote cleanup proof.

This test directly wires production Java services/ports/adapters with three
separate PostgreSQL schemas (A, B, competing accessor), separate credential roots,
actual sshd, root supervisor and protected Unix authority. Test-only fault wrappers
drop real responses or interrupt one installation step; they do not fake successful
SSH/authority/database responses. Startup reproduces the managed runtime directory
ownership inside a disposable Linux container. Initial fixture-only ownership and
missing RuntimeDirectory errors were corrected; successful results come from the
subsequent actual run.

Restart in this pairing fixture reconstructs services/adapters and reopens the
Unix listener while retaining DB/key files; it is not an OS/JVM crash on two VMs.
The existing persistence suite separately recreates full Spring application
contexts. No production host install, live Codex, UI, workload execution or process
cleanup E2E was run. Stage 9 runtime/UI/Codex labels remain NOT_RUN.

`git diff --check` and changed Python `py_compile`: PASS. No Nexus/Console production
changes, migrations or workflow-routing changes. Stage 4 is submitted for external
review; Stage 5 remains unauthorized.
