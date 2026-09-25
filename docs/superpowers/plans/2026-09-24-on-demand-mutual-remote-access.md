# On-Demand Mutual Remote Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One Give Access token and one Connect action establish two confirmed, separately revocable SSH directions, with no Remote Access preparation during ordinary `just start`.

**Architecture:** Ordinary Forge serves a loopback-only cold-state control path with exact Origin and CSRF checks. A narrow root-owned bootstrap prepares the dedicated Remote Access runtime and starts the host's system OpenSSH service only when Give Access or Connect is selected. Forge uses a dedicated system account with restricted, root-managed keys; it does not launch a second SSH daemon. The existing one-way SSH admission machinery is used twice in one durable pairing attempt; the browser calls the pair ready only after both grants are ACTIVE. The browser has no separate operator password prompt; local browser access is the chosen management boundary.

**Tech Stack:** Linux/systemd, OpenSSH, Python 3, Java 21/Spring Boot, PostgreSQL/Flyway, Nexus typed HTTP client, Console JavaScript.

**Spec:** `docs/remote-access/on-demand-mutual-access-design.md`

## Global Constraints

- Keep Forge Agent, Nexus, and the browser unprivileged; only the narrow systemd bootstrap runs privileged setup.
- Never use a token, peer display name, requested session ID, or base64 encoding as proof of SSH identity.
- Keep strict pinned host keys, per-session SSH keys, the Stage 5 workload fence and confirmed process cleanup.
- Never stop or reconfigure unrelated system SSH access when Forge access is disabled.
- New Connect has no one-way-success fallback: both directions ACTIVE or an explicit incomplete state.
- Existing one-way sessions remain visible as legacy records, never displayed as completed mutual bridges.
- Do not edit applied migrations or run Flyway repair against the operator's shared database.
- Keep feature tests in an isolated database/runtime; never run a PR worktree's ordinary Agent against the shared developer database.

## Review Focus

1. Missing local SSH capability during Connect: fail before reporting a bridge, with no active reciprocal grant.
2. Wrong connector host key or changed endpoint: reject reverse SSH even if first direction is ACTIVE.
3. Lost response after first grant: retry the same pair and keys; do not consume the invitation twice.
4. Peer offline during Disable: fence local admissions and show pending remote cleanup, not success.
5. Concurrent Connect/revoke: stale pairing writes cannot reactivate a revoked direction.

---

### Task 1: Cold control page and narrow bootstrap

**Files:**
- Modify: `scripts/runtime/systemd.sh`, `scripts/systemd/render-units.sh`, `config/systemd/forge-remote-agent.service.in`, `config/systemd/forge-remote-nexus.service.in`
- Modify: `scripts/remote-access/prepare_startup.py`, `scripts/runtime/stage-remote-access.sh`
- Create: `scripts/remote-access/bootstrap.py`, `config/systemd/forge-remote-bootstrap.socket.in`, `config/systemd/forge-remote-bootstrap.service.in`
- Modify: `services/forge-nexus/api-rest/src/main/java/com/sitionix/forgeai/api/remoteaccess/RemoteAccessOperatorController.java`
- Test: `scripts/remote-access/tests/test_startup.py`, `scripts/remote-access/tests/test_management_setup.py`, `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/RemoteAccessOperatorHttpIT.java`

**Interfaces:**
- Consumes: local loopback browser request, exact Origin, CSRF token, fixed setup command.
- Produces: cold-state GET/POST bootstrap API, plus a root bootstrap socket accepting only a fixed `ENABLE` operation from the Forge Nexus service identity.

- [ ] Write a failing startup regression: invoke the `just start` shell path with mocked service commands; assert no call to `stage-remote-access.sh`, `prepare_startup.py`, apt, system sshd start, or dedicated service start. Assert ordinary Agent/Nexus health waits remain.
- [ ] Write a failing bootstrap regression: an unauthorized peer or arbitrary command frame is denied before any subprocess; an authorized `PREPARE` invokes exactly the reviewed package setup and reports a typed success or safe failure.
- [ ] Write a failing Nexus HTTP test: on ordinary Nexus with Remote Access disabled, a loopback browser can load bootstrap state and trigger setup only with its session CSRF token and exact Origin; LAN and cross-site requests cannot invoke bootstrap.
- [ ] Run the focused Python and Nexus tests; record the expected failures.
- [ ] Implement a root-owned socket-activated bootstrap, with `SO_PEERCRED` authorization and fixed command dispatch. Install only this small helper/socket at ordinary startup. Move package staging, system OpenSSH installation/start, DB preparation, rootfs preparation, and dedicated service launch behind `PREPARE`. Protect a dedicated SSH account with per-key forced commands; use the system host key and never create a second sshd unit. Keep an explicit in-progress result and fail closed on partial setup.
- [ ] Make ordinary Nexus serve the local cold control path. After successful setup, hand off to the dedicated typed Remote Access client with no extra login prompt. No raw JSON proxy or generic shell executor.
- [ ] Re-run focused tests, `python3 -m unittest discover -s scripts/remote-access/tests -p 'test_*.py' -v`, Nexus verify, and `git diff --check`; commit the independently testable cold-bootstrap change.

### Task 2: Two directional grants from one token

**Files:**
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/model/RemoteAccessSession.java`, `RemoteAccessPairingRequest.java`
- Modify: `services/forge-agent/domain/src/main/java/com/sitionix/forgeagent/domain/port/RemoteAccessPairingTransport.java`, `RemoteAccessPeerPairing.java`
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/remoteaccess/RemoteAccessAccessorPairing.java`, `RemoteAccessGrantorPairing.java`
- Modify: `services/forge-agent/infrastructure/local/src/main/java/com/sitionix/forgeagent/infrastructure/local/remoteaccess/LocalRemoteAccessPairingTransport.java`, `RemoteAccessChannelServer.java`
- Create: next unused forward-only migration under `services/forge-agent/infrastructure/postgres/src/main/resources/db/migration/` for stable pair identity and reciprocal state.
- Test: `services/forge-agent/application/src/test/java/com/sitionix/forgeagent/application/remoteaccess/RemoteAccessPairingLifecycleTest.java`, `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/RemoteAccessLivePairingIT.java`

**Interfaces:**
- Consumes: the existing invitation token, pinned inviter host key, dedicated pairing transport.
- Produces: one durable pair identity linking two existing directional session records, with both authenticated keys confirmed before pair readiness.

- [ ] Write failing application tests: one token yields two linked grants; wrong reverse host key, missing reverse endpoint, second redeem, and failed reverse confirm never yield ready. A lost response resumes the same pair and keys. Concurrent revoke cannot be overwritten by pairing.
- [ ] Run the focused pairing lifecycle test and confirm the failures target the absent reciprocal handshake.
- [ ] Add a typed reciprocal message on the authenticated first SSH channel carrying a protected internal reverse invitation. The inviter redeems it using a dedicated reverse session key and pins the connector's host key from that invitation. Keep both long-lived session private keys local to their owners.
- [ ] Persist the pair link via a new migration and optimistic repository transitions. Treat any partial pair as explicit pending/failed state. Do not return legacy one-way ACTIVE as mutual success.
- [ ] Run focused application/persistence tests and the privileged real-SSH pairing fixture in isolated databases; commit the pairing change.

### Task 3: Truthful pair state, revoke, and browser behavior

**Files:**
- Modify: `services/forge-agent/application/src/main/java/com/sitionix/forgeagent/application/remoteaccess/RemoteAccessControlService.java`
- Modify: `services/forge-agent/api-rest/src/main/java/com/sitionix/forgeagent/api/remoteaccess/RemoteAccessController.java`
- Modify: `services/forge-nexus/domain/src/main/java/com/sitionix/forgeai/domain/remoteaccess/RemoteAccessModels.java`
- Modify: `services/forge-nexus/clients/agent-client/src/main/java/com/sitionix/forgeai/infrastructure/agentclient/remoteaccess/RemoteAccessClientDtos.java`
- Modify: `services/forge-console/src/operator/remote-access-location.js`, `remote-access-api.js`, `remote-access-page.js`, `remote-access-view.js`
- Test: `services/forge-console/tests/remote-access-page.test.ts`, `services/forge-console/tests/remote-access-api.test.ts`, `services/forge-nexus/boot/src/test/java/com/sitionix/forgeproxyit/RemoteAccessProxyIT.java`

**Interfaces:**
- Consumes: pair-linked directional status from Task 2 and cold control from Task 1.
- Produces: one browser bridge card with per-direction status; Connect triggers local preparation and shows success only after both confirmed ACTIVE.

- [ ] Write failing Agent/Nexus/Console tests: Connect from cold state invokes Enable once; incomplete reverse direction is not a successful bridge; Disable fences both directions and keeps pending state when remote cleanup is unconfirmed; a stale HTTP response cannot restore a revoked card.
- [ ] Run the focused tests and confirm the expected failures.
- [ ] Remove the unconditional `:9100` redirect. Render the page in ordinary Forge, use typed cold control until ready, and display paired state with both directions. Keep credentials and pairing token out of URL, storage, logs, and UI error objects.
- [ ] Reuse existing per-session revoke and process cleanup for each direction; aggregate the result truthfully. Do not treat a closed SSH TCP connection as proof that the remote managed workload stopped.
- [ ] Run Console tests/typecheck/build, Nexus verify, focused Agent tests, and `git diff --check`; commit the pair-state/UI change.

### Task 4: Host and two-machine acceptance without touching shared dev state

**Files:**
- Modify: `docs/remote-access/evidence.md`, `docs/remote-access/roadmap.md`
- Test: `scripts/remote-access/tests/test_startup.py`, `services/forge-agent/boot/src/test/java/com/sitionix/forgeagent/it/tests/RemoteAccessLiveExecutionIT.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: factual labels for cold startup, on-demand Enable, mutual pairing, commands/revoke in both directions, and limitations.

- [ ] Run `mvn -q -Dapi.version=1.44 -pl services/forge-agent/boot -am verify`, `mvn -q -Dapi.version=1.44 -f services/forge-nexus/pom.xml verify`, Python suite, Console tests/typecheck/build, and `git diff --check`.
- [ ] Run a privileged isolated fixture with two Forge instances: cold startup does not start system SSH; one token forms two authenticated grants through the system SSH listener; each direction runs a command; revoking either inbound grant stops its own commands and does not invent peer cleanup.
- [ ] If two physical machines and live Codex are unavailable, label those checks NOT_RUN. Never call mock tests live SSH/Codex E2E.
- [ ] Update evidence and PR #151 with exact results. Keep branch unmerged for external review.
