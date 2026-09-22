# Remote Access — gated implementation roadmap

Authority: the human-provided “Forge AI — Remote Access Roadmap”, 2026-09-22.
This is a navigation/checkpoint map, not a replacement or relaxation of that task.
Stage 0 PR #141 was merged with explicitly authorized admin merge at
`b6516ca2e6b6289e6365707683fd730f3faa43c2`. The user authorized Stage 1 next.
Each stage remains a separate review unit; no Stage 2+ authorization is implied.
No new PR metadata, comments/reviews or merges without an explicit request.
Production remote access remains NOT READY: Stage 1 only adds local persistence
and credential storage, not SSH authorization or execution.

| Stage | Deliverable | Required exit evidence | State |
| --- | --- | --- | --- |
| 0 | Actual-code design; isolated SSH/process probes | Key pinning, forced commands, control/workload separation, descendants cleanup and session isolation; explicit missing-capability diagnosis | Merged PR #141; combined real SSH/system-systemd probe passed 40 assertions using a stub supervisor |
| 1 | Invitation/session aggregates, forward migration, instance identity and local key store | Transitions, concurrent reservation, DB roundtrip/restart, permissions/redaction; no SSH access | READY_FOR_REVIEW; 37 focused tests, full Agent/Nexus and Console regression passed |
| 2 | Narrow privileged setup, managed sshd/helpers and fail-closed session gate | Binding cannot be forged; revoked/foreign/unavailable denied; inherited client config cannot bypass pinning; idempotent setup | Not started / not authorized |
| 3 | Give Access and invitation lifecycle | Five-minute server TTL; single-use token, cancellation/expiry; malformed/key/endpoint checks; no secret GET/list | Not started / not authorized |
| 4 | Pairing, durable key exchange and activation | Two real SSH instances; one session; concurrent redeem; lost responses/restarts; session-key confirmation; bounded provisioning cleanup | Not started / not authorized |
| 5 | Streaming commands, revoke and recovery | Bounded streams/stdin/exit/cancel; start-vs-revoke fencing; descendant cleanup; isolated sessions; restart/failure truthfulness | Not started / not authorized |
| 6 | Agent local management API and typed Nexus proxy | Production HTTP auth/Origin/CSRF, error/status preservation, redaction; ForgeIT typed fixtures, zero upstream calls on rejection | Not started / not authorized |
| 7 | Global Remote Access Console | Connect/Give Access; role-separated lists; no ACTIVE=Online; REVOKING visible; stale/double-submit/polling/secret tests and existing UI regression | Not started / not authorized |
| 8 | Local `forge-remote exec` helper | Actual remote read/edit/test, literal argv, cancellation, no local fallback or secret output; local Codex tools remain local | Not started / not authorized |
| 9 | Two-machine/VM E2E and operational audit | Real persistence/SSH/cleanup; independent session; crash/offline/security cases; separately labeled actual Codex live acceptance | Not started / not authorized |

## Immutable cross-stage rules

- A ACCESSOR → B GRANTOR only. No credentials/history transfer or reverse grant.
- Agent is the authority; Nexus is typed proxy; Console is presentation; supervisor
  performs narrow privileged OS work. No root Agent or duplicate session source.
- Invitation expiry is derived from server time, consumed/cancelled fields and
  expiresAt. One invitation creates at most one session.
- Session states: PROVISIONING → ACTIVE → REVOKING → REVOKED, also PROVISIONING →
  REVOKING → REVOKED. Reachability is separate observation, not authorization.
- REVOKED requires confirmed cleanup. Offline accessor intent is not remote proof.
- No transaction pretends DB+filesystem are atomic. Partial states reconcile.
- No runtime/workflow/NodeType/MANUAL/repository-scope changes in this roadmap.
- No generic permission/parser framework, raw JSON HTTP boundary, silent fallback,
  inherited personal SSH identities or secret logging.
- Regression test first, then minimal production changes in the authorized stage.
  Stage 0 is the design/probe exception: no production feature implementation.
- Full product regression when production stages change code: Agent verify,
  Nexus verify, Console tests/typecheck/build, changed OS script and actual SSH
  suite. Those suites do not substitute for security/process evidence.

## API checkpoint (Stage 6 only)

Agent `/api/v1/remote-access`; Nexus
`/api/v1/infrastructure/agents/remote-access`. Typed endpoints: GET capabilities;
POST/GET invitations; DELETE invitation; POST/GET sessions; GET session;
POST session/check; DELETE session. GET never silently connects to SSH.
Create token response only, `Cache-Control: no-store`. Session creation 201 only
when complete, otherwise 202. Revoke 200 only for confirmed REVOKED, otherwise
202 REVOKING. Safe code/message/correlationId errors retain upstream semantics.
No peer HTTP admin/pairing API and no local Forget operation in V1.

## Evidence and review discipline

Review architecture → contracts → production → security/errors/logging →
dependencies → unit → integration/SSH → CI. Style preference alone is not a
blocker. Mocked tests are never labeled live SSH/Codex E2E. The final Stage 9 labels
REMOTE_ACCESS_RUNTIME_E2E_PASS, REMOTE_ACCESS_UI_FLOW_PASS and
REMOTE_ACCESS_CODEX_LIVE_PASS require their respective real evidence; current
status for all three is NOT_RUN.

Each stage stops with Changed / Tests / Result / Limitations / READY_FOR_REVIEW.
This means submitted for external review, not accepted or ready for production.
No next-stage work without a new user command.
