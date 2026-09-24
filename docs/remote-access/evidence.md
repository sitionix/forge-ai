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

`RemoteAccessLivePairingIT` passes the initial six scenario checkpoints:

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

The initial six scenarios reconstruct services/adapters and reopen the Unix
listener while retaining DB/key files. The additional JVM-crash scenarios below
extend this evidence. The existing persistence suite separately recreates full
Spring application contexts. No two-VM OS crash test is claimed. No production host install, live Codex, UI, workload execution or process
cleanup E2E was run. Stage 9 runtime/UI/Codex labels remain NOT_RUN.

`git diff --check` and changed Python `py_compile`: PASS. No Nexus/Console production
changes, migrations or workflow-routing changes. Stage 4 is submitted for external
review; Stage 5 remains unauthorized.

### Final JVM-crash recovery correction

The initial service-reconstruction tests did not expose a real crash blocker:
SIGKILL leaves the UNIX authority socket path behind, and the previous channel
startup refused that path before persisted reconciliation could run. A real
killed-Java-child regression reproduced this failure (RED).

The channel now holds an owner-only sibling file lock for its lifetime, also
excluding a second same-JVM descriptor that could release POSIX process locks.
Startup reclaims only a verified control-owned UNIX socket with the expected
group/mode, one link, explicit connection refusal and unchanged inode. A live
listener, unsafe lock, foreign/symlink path or unknown/ambiguous connection error
is preserved and fails startup. The lock inode is retained across restarts.
Unknown/localized OS errors remain fail-closed instead of being guessed as stale.

GREEN: 9 channel tests pass, including real SIGKILL, active unmanaged listener,
concurrent ownership, unsafe mode, symlink and hardlink preservation. Independent
read-only review found no concrete defect in this correction.

The production SSH/PostgreSQL fixture now also passes two actual process-crash
scenarios (8 checkpoints total):

- A separate GRANTOR JVM halts after its reservation commits but before session
  grant installation, leaving its authority socket. A replacement JVM recovers
  the abandoned socket and persisted grant; the original ACCESSOR session/key
  confirms successfully. No operator socket deletion occurs in the test.
- A separate ACCESSOR JVM receives a real successful SSH confirm and halts before
  persisting local ACTIVE. Its token arrives through stdin, never process args or
  a token file. Recovery uses its existing persisted private-key reference and
  session ID, with no invitation replay.

Both child JVMs use production pairing services/adapters against the real isolated
PostgreSQL peer schemas; these are abrupt process exits without shutdown hooks.
The test remains inside one disposable Linux environment, not two physical hosts
or a systemd/OS reboot exercise. No Stage 5 workload or live Codex claim is added.

Final full Agent verify after the socket/JVM-crash correction: PASS (exit 0),
327 integration tests, zero failures/errors, six existing opt-in skips. All eight
live pairing checkpoints passed in that full run. `git diff --check`: PASS.

## Stage 5 — managed commands, revoke and recovery (2026-09-23)

Base: merged PR #145 `edf49dbfa34643fdbd66fa4aa6a3bbaa78df42d2`.
Scope checked against the human roadmap: Stage 5 only. No REST management,
Console, workflow routing, migration, Stage 8 helper or Codex integration changes.
Operational setup and limitations: [stage5-execution.md](stage5-execution.md).

### Regression-first corrections and review

Direct application regressions cover persisted REVOKING admission denial,
start-versus-revoke serialization, cleanup failure, CAS winners, offline revoke,
confirmed remote revoke before key deletion, retained key on deletion failure,
and independent heartbeat maintenance. Real PostgreSQL regression first failed
because the transition adapter did not recognize the new retained-reference
REVOKED transition; the adapter now validates that exact domain transition and
subsequent reference clearing using the existing optimistic version. V37 and the
session status set remain unchanged.

Local process tests cover independent large streams, stdin, nonzero exit,
cancellation, inactive denial and no retry/fallback. These tests are **not** SSH
or systemd evidence.

Independent review found two required corrections:

1. A launched `systemd-run` process could submit after an absent-unit inspection
   and registry deletion. Tests first failed for a missing start fence, unreaped
   launcher and incorrectly successful cleanup. The production registry now
   creates a root-only durable `.allow`; PID 1 evaluates `ConditionPathExists`.
   Every cleanup path removes the fence before waits, reaps the launcher, verifies
   unit/cgroup/job absence and only then removes the record. Failed reap retains
   the record while attempts continue for other commands. Disconnect shares that
   cleanup path. A real delayed submission after removal was also tested below.
2. A shared worker pool let long attachments starve control/heartbeats. The
   supervisor now has separate bounded attachment/control/heartbeat pools and a
   dedicated heartbeat socket selected by the Java adapter. A local Unix-socket
   regression fills all sixteen attachment workers, starts a blocked control
   request and still receives the heartbeat response. This is a concurrency test,
   not a remote SSH load test. Independent re-review found both blockers closed.

Early live runs also exposed two real integration failures: sshd emitted a
missing-home warning into command stderr, and systemd-run could return zero when
a managed process was stopped. The managed transport now has an empty protected
home; explicitly cancelled executions return nonzero even if the launcher says
zero. Tests cover both. A fixture-only assertion was corrected to wait boundedly
for actual supervisor automatic restart/registry reconciliation after SIGKILL.

### Actual privileged execution fixture

Command (exit 0):

```bash
mvn -q -Dapi.version=1.44 -Dforge.remote-access.live-execution=true \
  -Dtest=RemoteAccessExecutionServiceTest,RemoteAccessAccessorExecutionTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=RemoteAccessLiveExecutionIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -pl services/forge-agent/boot -am verify
```

This opt-in test starts a disposable privileged Linux systemd container and a real
PostgreSQL container. The two peers use separate persisted schemas/instance IDs,
production Java services/adapters and real OpenSSH. Root setup only prepares the
explicit rootfs/workspaces and injects supervisor crashes; it is not a replacement
Agent authority. No host bind mounts, personal credentials or production services
are used. systemd 255, Java 21 and OpenSSH are actual processes, not mocks.

The successful rerun after both review corrections emitted:

```text
PASS actual PID 1 rejects workload submitted after cleanup fence removal
PASS real SSH literal argv, separate stderr and nonzero exit
PASS real SSH stdin and large independent streams
PASS real managed command timeout
PASS workload isolation and persistent explicit workspace
PASS revoke stops setsid descendants, denies new execution and preserves another session
PASS unavailable authority lease stops existing workload
PASS actual supervisor SIGKILL stops bound workload units
PASS actual supervisor SIGSTOP watchdog stops bound workload units
STAGE5_FIXTURE_PASS: real SSH, PostgreSQL, production authority and systemd cleanup
```

The fixture verifies an already-running command in session B remains alive during
session A revoke, and the setsid child is gone. Literal arguments include spaces,
quotes, dollar expansion, command substitution and systemd specifiers. Separate
150 KB stdout / 200 KB stderr streams are drained concurrently. The workload
cannot see control/private-key/Docker paths or reach host loopback, cannot write
the rootfs, and can persist changes only in its prepared workspace. Post-crash
registry emptiness and the restarted supervisor are positively checked.

### Scope of evidence

- Mocked lifecycle/inspection failures prove application ordering and truthful
  failure handling; they do not prove an OS kill succeeded.
- The default Agent verify skips the privileged Stage 5 opt-in fixture. Its
  successful explicit run above is separate from ordinary CI checks.
- Existing real Stage 4 pairing/restart cases remain in full Agent verification;
  the separate Stage 2–4 Docker suite uses a stub authority and labels it so.
- This is one isolated Linux host environment with two logical persisted peers,
  not two physical machines/VMs or a reboot test. Other platforms are NOT READY.
- UI and actual Codex sessions are NOT_RUN. Stage 9 labels
  `REMOTE_ACCESS_RUNTIME_E2E_PASS`, `REMOTE_ACCESS_UI_FLOW_PASS` and
  `REMOTE_ACCESS_CODEX_LIVE_PASS` are not claimed by Stage 5.
- Prepared files and accounts intentionally survive revoke. Cleanup does not
  roll back previous file edits. Offline/lost acknowledgement may require operator
  diagnosis and remains visibly unconfirmed; no key is regranted for recovery.

### Final regression results

All commands below completed with exit 0 on the final production changes:

```bash
mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify
mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
python3 -m unittest discover -s scripts/remote-access/tests -p 'test_*.py' -v
python3 -m py_compile scripts/remote-access/*.py \
  scripts/remote-access/tests/test_workloads.py \
  scripts/remote-access/tests/stage5/run_fixture.py
docker build -f scripts/remote-access/tests/Dockerfile -t forge-remote-stage2-test .
docker run --rm --network none forge-remote-stage2-test
(cd services/forge-console && npm test && npm run typecheck && npm run build)
git diff --check
```

Agent failsafe summary: 329 completed, zero failures/errors, seven opt-in skips
(including the separately executed privileged Stage 5 test). Nexus: 236 unit and
38 integration tests, zero failures/errors. Python: 58 tests, including 25 workload
checks. Console: 545 tests across 20 files, plus typecheck and build. Docker SSH
suite reported the existing Stage 2/3/4 boundary checkpoints with stub-authority
labels. The explicit Stage 5 real-authority run is recorded separately above.

### PR #146 follow-up — recovery diagnostics and real close cancellation

The review regressions reproduced stale `REMOTE_ACCESS_CLEANUP_PENDING`,
`REMOTE_ACCESS_REVOKE_UNCONFIRMED` and `REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING`
after successful recovery (focused run: 19 tests, four failures before the fix).
The two application services now clear failure metadata through the existing
`recordFailure(snapshot, null, null)` CAS only after the corresponding successful
transition. The snapshot is the exact transition result, not an arbitrary newer
row. If a later writer wins, its failure survives. ACCESSOR does not continue key
cleanup after losing the remote-confirmation metadata CAS. Domain states, SQL
schema and lifecycle transition rules are unchanged.

Focused regressions now pass, including actual failure followed by retry, clean
metadata before key deletion, failed key deletion followed by retry, and newer
writers winning transition/metadata races. The privileged PostgreSQL fixture also
seeds persisted failures and confirms both peers' code/message are null after
successful revoke.

The new cancellation scenario initially **failed with real SSH/systemd**. After
`RemoteAccessCommandExecution.close()` the SSH process had exited, but systemd
reported `active/running`, the shell and setsid child were still sleeping, and
both registry record and `.allow` remained. The original helper waited only for
the supervisor response; a non-PTY sshd disconnect did not terminate the quiet
forced helper. This was a runtime defect, not merely missing test coverage.

The minimal helper correction watches stdout/stderr reader loss using `poll`
ERR/HUP/NVAL alongside the existing supervisor result. A disconnected SSH output
closes the attachment, triggering the existing supervisor cleanup path. It never
consumes stdin or treats normal stdin EOF as cancellation. Real pipe/socket tests
cover both output descriptors and preserve the normal command result.

The successful privileged rerun used the existing Stage 5 Maven command above
with `-Dforge.remote-access.live-execution=true` and emitted the additional line:

```text
PASS real SSH close cancellation removes main, setsid child, systemd unit, registry and fence; unrelated session survives
```

Before closing, the root test driver captures the exact registered execution and
verifies its running MainPID, live child and fence. Java calls the real execution
handle's `close()` and asserts SSH exit within five seconds. The root driver only
**observes** systemd/proc/registry afterward: within fifteen seconds the unit must
be inactive/dead with zero PID/no job (or confirmed not-found), both PIDs absent,
and the captured record/fence removed. It never issues STOP or revoke for this
scenario. Both session rows remain ACTIVE, heartbeats continue, and an already
running command in session B is confirmed alive afterward. A 150-second command
timeout cannot explain this bounded cancellation result.

All earlier live checkpoints also passed in that rerun, including delayed
submission fencing, timeout, revoke, authority loss, SIGKILL and watchdog. This
remains an isolated real Linux/SSH/PostgreSQL/systemd fixture, not live Codex or
a two-physical-machine claim. Independent read-only review found no required
code defects in the CAS correction or SSH-disconnect correction.

Follow-up verification: focused Java 19/19, full Agent verify, Python 60/60,
privileged Stage 5 fixture and existing Stage 2–4 Docker SSH suite all exit 0.
Python compilation and `git diff --check` also pass. No Stage 6 work is included.

### PR #146 follow-up — atomic successful revoke and diagnostics

This correction supersedes the separate success-time `recordFailure(..., null,
null)` write described in the preceding follow-up. That write could fail after
GRANTOR had removed the grant, stopped workloads and committed REVOKED, turning
the authenticated response into DENIED even though cleanup was complete.

Typed aggregate operations now produce the complete successful target in one
version increment: `confirmRevokedAndClearFailure`,
`confirmRemoteRevokedAndClearFailure` (retaining the ACCESSOR key reference), and
`clearRevokedCredentialAndFailure`. Existing lifecycle operations remain available
with their previous semantics. The PostgreSQL adapter reconstructs the exact
permitted target and updates lifecycle, key reference, diagnostics and version in
one statement guarded by id/version/status. No detached arbitrary replacement,
unconditional diagnostic update, schema migration or additional state was added.

After successful cleanup and a successful CAS, GRANTOR returns REVOKED directly;
no secondary write or read can invalidate that committed acknowledgement. A lost
CAS reloads/respects its winner. ACCESSOR confirmation and later successful key
cleanup each clear their resolved diagnostics in their respective atomic state
change. A new actual credential deletion failure remains visible as
REMOTE_ACCESS_CREDENTIAL_CLEANUP_PENDING.

Regression-first evidence:

- Four new application scenarios failed on the old flow: acknowledgement depended
  on secondary diagnostics; authenticated revoke retained the old failure when
  diagnostics failed; and both lost-CAS/database-failure retries produced a target
  with stale credential diagnostics.
- Focused application/domain tests now pass (35 tests). The acknowledgement test
  configures secondary diagnostic writes and post-commit reads to throw and proves
  neither is needed. Success targets contain null code/message and increment the
  version once. Competing newer failures/states remain untouched.
- Credential retry tests model completed physical deletion with an in-memory key
  store, fail the first reference-clearing CAS or DB operation, reconstruct
  the service and reconcile. Repeating idempotent delete on the missing key then
  converges to REVOKED with null key reference and diagnostics; no key store/create
  operation or remote confirmation is performed. These are direct application
  regressions, not a claim of filesystem-crash E2E.
- Three new PostgreSQL scenarios failed against the old adapter's permitted-target
  validation, then passed after the atomic SQL change. The 19-test persistence
  suite verifies exact persisted targets across adapter restart, version +1,
  stale-CAS rejection and rejection of forged detached failure changes.

The first persistence command without `-Dapi.version=1.44` was blocked because
the local Docker daemon rejected the client's default API 1.32 (minimum 1.40).
The successful persistence verification uses the same API 1.44 override as the
full Agent/live commands. Docker configuration and project dependencies were not
changed. Initial test fixture setup was also corrected to insert version-zero
PROVISIONING before advancing through the normal repository operations.

SSH protocol, forced/execution helpers, supervisor/systemd isolation, migration,
UI and workflow routing are unchanged by this correction. Stage 6 remains excluded.

Independent review also identified timestamp precision as a false newer-writer
signal: a full-record equality check could skip immediate key deletion after
PostgreSQL normalized a nanosecond Instant to microseconds. A deterministic
regression first failed with the same version and normalized timestamp; the guard
now compares CAS versions. A real newer writer still increments that version and
is preserved.

Final verification after the timestamp-precision correction:

- Focused application/domain command: PASS, 35 tests.
- PostgreSQL persistence command with `-Dapi.version=1.44`: PASS, 19 tests.
- Full Forge Agent `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`: PASS.
- Privileged `RemoteAccessLiveExecutionIT` with
  `-Dforge.remote-access.live-execution=true`: PASS with real SSH, PostgreSQL,
  production authority and systemd. Its unchanged cancellation scenario confirms
  that `RemoteAccessCommandExecution.close()` removes the main process, setsid
  child, systemd unit, execution registry and allow fence while the unrelated
  session survives. Timeout, revoke, authority-loss and supervisor crash/watchdog
  checkpoints also pass. This is runtime E2E evidence, not live Codex acceptance.
- `git diff --check`: PASS. No SSH/helper/supervisor or cancellation-fixture changes.

## Stage 6 — local management API and typed Nexus boundary

Scope: nine local management operations, operator/service HTTP boundaries, safe
DTOs/errors, explicit connectivity observation CAS and protected runtime setup.
No Stage 7 UI, Stage 8 helper, SSH protocol change, lifecycle status or migration.

Verification on the Stage 6 implementation:

- Focused Agent management/controller/service-filter regressions: PASS. GET/list
  do not invoke SSH, foreign local sessions are rejected, GRANTOR checks do not
  create reverse access, observation does not mutate lifecycle/failure metadata,
  and stale observation CAS respects a newer writer.
- `RemoteAccessPersistenceIT`: PASS, 20 tests, real PostgreSQL including persisted
  observation timestamps/version and competing failure-write protection.
- `RemoteAccessManagementHttpIT`: PASS, 2 tests, real Agent Boot configuration,
  loopback Tomcat and PostgreSQL. Missing service credential is rejected; valid
  service identity reads actual persisted state; malformed secret-bearing bodies
  are not echoed. Matrix/encoded path GET and POST cannot bypass authentication.
- Nexus focused controller/client/operator-session/config tests: PASS.
- `RemoteAccessProxyIT`: PASS, 17 tests using the existing single
  `NexusProxyTestManager`, typed endpoint contracts and standard fixtures. Real
  Nexus mapping/client/security and WireMock Agent cover all nine operations,
  201/202 and 200/202, known upstream 400/404/409/410/503 preservation and zero
  upstream calls for local validation/Origin rejection. Authenticated session is
  a test fixture here, not a claim of browser login E2E.
- `RemoteAccessOperatorHttpIT`: PASS, 2 tests, real Nexus Boot/Tomcat listener,
  actual login/cookie/Origin/CSRF path and typed HTTP client to an explicitly stubbed
  Agent HTTP server. Cookie is HttpOnly/SameSite=Strict and includes `/fgaisox` in
  its path. Distinct service credential reaches the stub; operator credential does
  not. Encoded/matrix paths remain protected under servlet-scoped registration.
- Full Forge Agent verify: PASS, also run with
  `-Dforge.remote-access.live-execution=true`. The same final run includes real
  Stage 4 pairing/restart cases and Stage 5 SSH/PostgreSQL/systemd execution.
  The unchanged `RemoteAccessCommandExecution.close()` scenario confirms SSH,
  main/setsid descendant, systemd unit, registry and fence cleanup while preserving
  the unrelated session. Authority-loss, supervisor SIGKILL/watchdog, revoke,
  timeout and stream checkpoints pass.
- Full Forge Nexus verify: PASS. Existing ordinary endpoint behavior remains
  covered, including encoded systemd unit names.
- Console: 20 files / 545 tests PASS; typecheck and build PASS, no Console changes.
- Python Remote Access: 64 tests PASS, including four new management-setup tests.
- `git diff --check` and Python compilation: PASS.

Commands:

```sh
mvn -q -Dapi.version=1.44 -Dforge.remote-access.live-execution=true \
  -pl services/forge-agent/boot -am verify
mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify
python3 -m unittest discover -s scripts/remote-access/tests -p 'test_*.py' -v
python3 -m py_compile scripts/remote-access/prepare_management.py \
  scripts/remote-access/tests/test_management_setup.py
# In services/forge-console:
npm test
npm run typecheck
npm run build
```

Regression/review evidence:

1. New functionality tests were introduced before their implementing contracts.
   Initial expected missing-type compilation failures were followed by green
   focused tests. Mockito attach and Unix-socket tests required execution outside
   the filesystem/network sandbox; these failures were not production defects.
2. Independent review identified a P1 raw-URI filter mismatch. Actual HTTP on the
   old filter returned 200 for unauthenticated `/remote-access;v=1/sessions`
   (expected 401). The fixed filter uses Spring RequestPath/PathPattern with MVC's
   decoded-segment/matrix semantics; GET/POST matrix and percent-encoded regressions
   are green in the final Agent suite.
3. Full Nexus regression caught the newly global security firewall rejecting an
   existing encoded systemd unit name with 400. Standard security registration is
   now limited to the Remote Access servlet mappings; default Boot/Actuator
   all-path security is not activated. The existing failing test and final full
   Nexus suite pass without modifying the legacy endpoint or its test.
4. Runtime setup regression under umask 077 first produced directory 0700 instead
   of required 0711. Explicit creation permissions now let both protected service
   identities traverse the root-owned parent; credential files remain 0600.
5. The real Nexus HTTP fixture excludes test-only JDBC autoconfiguration pulled
   in by the shaded ForgeIT dependency; production Nexus has no new persistence.

Accepted limitation: current SSH refusal cannot prove whether a removed invitation
key expired, was cancelled, the pinned host differs or the peer is unavailable.
The user explicitly approved preserving the SSH protocol and reporting ambiguous
pairing failure safely, rather than fabricating 410/409 from editable token expiry
or stderr. The 410/409 ForgeIT cases prove typed propagation, not a new remote
SSH diagnostic protocol. Existing durable provisioning/recovery remains authoritative.

Host Forge runtime/systemd configuration was not changed or restarted. Setup tests
use temporary directories and local fixture identities; actual service installation
still requires the explicit protected setup documented in stage6-installation.md.
No live Codex or Stage 7 UI acceptance was run; those remain NOT_RUN.

### Stage 6 correction — dedicated Nexus lifecycle timeout (2026-09-24)

The Remote Access HTTP client inherited the ordinary Agent 30-second read timeout,
shorter than the existing 60-second workload STOP / 90-second SSH revoke bounds.
It now uses `forge.remote-access.agent-read-timeout` (default 120s); enabled
configuration rejects values below 100s (90s control bound plus 10s margin).
Generated Nexus environment explicitly sets 120s. Ordinary Agent client settings,
Agent lifecycle bounds, security and REST contracts are unchanged.

RED evidence on the prior implementation:
- Real loopback HTTP DELETE with a response delayed 31 seconds failed with
  `ResourceAccessException: Request timed out`.
- Captured actual JDK HTTP request carried 30s instead of the configured 120s.
- Five insufficient-timeout configurations incorrectly started.
- Generated-environment regression failed because the dedicated setting was absent.

GREEN evidence:
- `RemoteAccessHttpClientConfigurationTest`: 11 cases pass. Real HTTP revoke
  receives 200/REVOKED after the 31-second upstream delay. Actual outgoing request
  bounds are checked for 100/120/150s and default 120s; ordinary property stays 30s.
  Invalid 30/89/99/0/-1s values fail configuration; disabled feature needs no new
  config. Injected JDK transport timeout maps to safe 503/REMOTE_ACCESS_UNAVAILABLE
  without exposing transport details. This last test simulates timeout expiry; it
  does not wait 120 seconds or claim a live SSH revoke.
- Focused Nexus configuration/operator/client tests: PASS.
- Full `mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify`: PASS.
- Full `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`: PASS.
- Python Remote Access suite: 64 tests PASS, including generated timeout,
  credential permissions/separation, no plaintext env secrets, idempotence and umask.
- `git diff --check`: PASS.

The opt-in privileged Stage 5 execution suite was not separately rerun for this
Nexus-only timeout correction. No Stage 7 work or host runtime changes were made.
