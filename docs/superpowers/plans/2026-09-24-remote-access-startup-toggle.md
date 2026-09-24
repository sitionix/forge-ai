# Remote Access startup preparation and Enable / Disable plan

**Goal:** `just start` prepares the protected Remote Access runtime. The local Remote Access page offers a clear Enable / Disable flow. Disable revokes all sessions and stops managed commands; a pending remote revoke remains visibly pending.

**Design:** [2026-09-24-remote-access-startup-toggle-design.md](../specs/2026-09-24-remote-access-startup-toggle-design.md)

**Base:** current `origin/main` at `597a2b98`, isolated branch `feature/SITIONIX-146`. Preserve the ordinary Forge Agent/Nexus units and Stage 2–8 SSH/session boundaries. Do not run privileged installation against the development host as part of unit tests. A live host rollout is a separate deployment action.

## File map and boundaries

| Unit | Existing files to extend | New files likely needed | Responsibility |
| --- | --- | --- | --- |
| Startup | `scripts/runtime/systemd.sh`, `scripts/systemd/install.sh`, `scripts/systemd/render-units.sh`, `config/systemd/*.in` | dedicated unit templates, root-owned package installer wrapper, `scripts/remote-access/tests/test_startup.py` | Idempotent privileged setup, protected package/secret placement, units and readiness; no browser authority |
| Agent state | `services/forge-agent/application/.../remoteaccess/*`, `services/forge-agent/infrastructure/postgres/...`, migration directory | switch aggregate/port/adapter, next forward-only migration, focused tests | Persist admission state with optimistic updates; no fake session status |
| Agent control | `RemoteAccessManagement`, `RemoteAccessInvitations`, pairing/execution admission code, `RemoteAccessController` and DTOs | focused unit/IT tests | Enable, bulk Disable/retry, admission fencing, truthful cleanup state |
| Nexus | `RemoteAccessProxyController`, DTO/mapper, use case, typed client adapter | focused controller/adapter/ForgeIT tests | Authenticated typed proxy; no business validation or privileged process control |
| Console | `remote-access.html`, `remote-access-page.js`, `remote-access-api.js`, `operator-ui.css`, `remote-access-view.js` | extend `remote-access-api.test.ts`, `remote-access-page.test.ts` | Clear readiness/action/pending/error UX without secret persistence |
| Evidence | `docs/remote-access/*` | startup/toggle evidence section | Distinguish mocked tests, real local SSH/systemd, and unrun two-machine Codex tests |

Do not choose concrete migration number or fixed management ports until rechecking the implementation branch immediately before that task. If another migration lands first, use the next free forward-only number. No old migration edits.

## Task 1 — protected startup package and dedicated units

1. Add a regression test that runs the systemd renderer/installer in disposable directories and shows that current `just start` has no Remote Access units or protected configuration. Assert the ordinary four units retain their existing user/bind settings.
2. Add a root-owned, non-writable deployment package for the reviewed `scripts/remote-access` helpers and a root-owned Agent JAR. The dedicated `forge-control` unit must execute that JAR, never the writable checkout. Use the existing installer, `prepare_management.py`, `prepare_local_exec.py`, and workspace preparation rather than duplicating their security checks. Keep setup idempotent and preserve secrets/host key on repeat.
3. Render separate loopback Agent/Nexus management units and protected `EnvironmentFile`s. Do not set `FORGE_REMOTE_ACCESS_ENABLED` on ordinary wildcard Nexus or channel-enabled on ordinary Agent. Start services after Postgres and supervisors; check HTTP readiness and socket permissions. `just stop` and status must account for the dedicated units without relabeling an unconfirmed revoke as disabled.
4. Determine SSH listen address from one unambiguous active default LAN route; interactive startup prompts if ambiguous, noninteractive startup requires an explicit setting. Test loopback/wildcard rejection, multi-interface ambiguity, occupied port, root-owned conflict, lack of privilege, repeated start, and active-supervisor upgrade refusal. Do not rotate keys or stop active sessions on routine `just start`.
5. Run focused Python startup/setup tests and `git diff --check`. Review generated units/env names and filesystem modes; tests must never print protected values.

**Review gate:** Fresh `just start` can prepare a ready but disabled installation in an isolated systemd environment. Ordinary Forge still starts. A failed bootstrap has a specific `NOT_READY` reason and does not claim Remote Access ready.

## Task 2 — persisted switch and admission fence

1. Write failing domain and repository tests for initial `DISABLED`, `DISABLED → ENABLED → DISABLING → DISABLED`, forbidden transitions, concurrent CAS, and restart roundtrip. Add one small Agent-owned switch model, repository port/adapter, and forward-only migration with one local-instance row. Preserve session and invitation schema.
2. Write failing admission tests for invitation creation, pairing redemption/confirmation, new SSH command, and local helper execution under `DISABLED` and `DISABLING`. Add checks at authoritative Agent use cases and the SSH gate; a UI-only check does not count. Use a versioned switch read/transition so Enable cannot race past Disable. Existing ACTIVE sessions are not silently rewritten when the switch changes.
3. Run focused domain/application/persistence tests and `git diff --check`.

**Review gate:** No new grant or command can enter while disabled/disabling, including an already-authenticated SSH channel. Management APIs remain available while the switch is disabled.

## Task 3 — truthful bulk Disable and recovery

1. Write failing tests for mixed GRANTOR/ACCESSOR sessions, outstanding invitations, multiple concurrent commands, start-vs-disable, one failed workload cleanup, offline remote peer, repeated Disable, restart reconciliation, and stale CAS winner. Use the existing per-session revoke and invitation cancellation paths. Do not directly delete rows or authorization files from the new coordinator.
2. Persist `DISABLING` before cleanup/admission shutdown. Cancel outstanding invitations, revoke local grants, stop managed workloads and descendants, and request remote revoke for ACCESSOR sessions. Persist `DISABLED` only after all required confirmations. Return counts and a safe pending diagnostic while unresolved. Reconcile on restart with bounded work and no unbounded DB lock or network wait.
3. Include a real privileged local SSH/systemd test: run a command with a setsid child, Disable, verify SSH channel ended, unit/child/registry/fence/grant gone, old key rejected, and an unrelated ordinary Forge service healthy. Do not call supervisor STOP directly as a substitute for the public Disable path.
4. Run focused Agent tests, persistence IT, privileged fixture, full Agent verify, and `git diff --check`.

**Review gate:** Closing a socket without removing authorization fails the test. An offline ACCESSOR peer stays visibly `DISABLING`; no false success or silent reconnection.

## Task 4 — typed management API and Nexus delegation

1. Add failing Agent API tests for `GET /api/v1/remote-access/capabilities` including switch/readiness details, and authenticated POST Enable/Disable operations. Define typed status/diagnostic DTOs; never return private keys, secret paths, or raw exception text. Keep the controller mounted with switch `DISABLED`.
2. Add equivalent Nexus routes under `/api/v1/infrastructure/agents/remote-access`. Extend the existing proxy DTO/mapper/use-case/client adapter; Nexus must only map and delegate. Preserve operator Origin, session, CSRF, service bearer, safe errors, `Cache-Control: no-store`, and the dedicated timeout. Test no upstream call on local auth/validation rejection.
3. Test an ordinary wildcard Nexus with feature disabled remains unchanged; dedicated management Nexus starts only on explicit loopback. A 404 on operator/session when the switch is disabled is a regression.
4. Run focused Agent/Nexus tests, ForgeIT, both full Maven verifies, and `git diff --check`.

**Review gate:** The page can query readiness and toggle after operator sign-in. Unauthenticated or cross-origin calls cannot enable access. No root operation is exposed through HTTP.

## Task 5 — Console UX

1. Add failing `remote-access-api.test.ts` and `remote-access-page.test.ts` cases before editing UI: disabled ready state, enable success/error, enabled actions, Disable confirmation, pending counts/retry, incomplete bootstrap diagnostic, 401 re-login, stale response after navigation, double click, keyboard focus, and no secret persistence. Assert Give Access/Connect never fire while disabled.
2. Add a visually clear status card above sessions. Disabled state has one primary **Enable Remote Access** action; enabled state has **Connect** and **Give Access** as primary actions and visually secondary **Disable Remote Access**. Give a human-readable reason for unavailable actions. The destructive confirmation says that all sessions will be revoked and running commands stopped. `DISABLING` keeps session cards visible, shows unresolved count and retry, and never says “Disabled” before confirmation.
3. Use existing `RequestCoordinator`/`PollingCoordinator` to serialize mutation, abort stale GETs, avoid duplicate POSTs, stop polling on navigation, and refresh after ambiguous transport failures. Keep one-time token handling unchanged. Use live regions, focus restoration, responsive layout, and safe text rendering; no token/secret in localStorage, URL, or logs.
4. Run Console focused tests, full `npm test`, `npm run typecheck`, `npm run build`, and the existing synthetic browser smoke. Label that smoke synthetic, not live SSH. Run `git diff --check`.

**Review gate:** A local operator can understand the next action and the actual authorization state without reading logs. A failed or pending operation is never displayed as success.

## Task 6 — end-to-end regression and documentation

1. Exercise a fresh isolated Linux/systemd install and a repeat `just start`: management URL, operator login, Enable, Give Access token, local two-instance Connect, Disable during a long command, cleanup, old-key rejection, restart with persisted disabled state. Capture exact commands, versions, results, and limitations in `docs/remote-access/evidence.md`.
2. Re-run all changed Python suites, Agent verify, Nexus verify, Console tests/typecheck/build, and local privileged SSH fixture. Check ordinary Forge project/workflow health. Run `git diff --check` and inspect production diff for privilege or secret regressions.
3. Keep live two-physical-machine pairing and live Codex evidence as `NOT_RUN` unless actually executed. Do not call this Stage 9 acceptance solely because local fixture tests pass.

**Review focus:** root-owned code execution; normal Forge bind unchanged; persisted switch/CAS races; offline remote revoke truthfulness; no reconnection with old key; browser cannot reach privileged commands; no generic 404 or false “Disabled” state.
