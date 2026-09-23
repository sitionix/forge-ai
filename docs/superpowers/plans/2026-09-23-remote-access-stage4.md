# Remote Access Stage 4 — durable pairing and activation

**Goal:** One persisted session/key per invitation on each peer; ACTIVE requires SSH proof of the new session key.
**Spec:** Human Remote Access roadmap Stage 4; docs/remote-access/design.md and roadmap.md.
**Base:** PR #144 merged at 43a1ea51. Worktree /tmp/forge-remote-stage1, feature/SITIONIX-138.
**Execution:** TDD; independent OS and typed transport work, application lifecycle and end-to-end integration coordinated locally. Each component and final diff receives read-only review.

## Shared contracts

- RemoteAccessPairingRequest(UUID sessionId, UUID accessorInstanceId, String accessorDisplayName, String sessionPublicKey): public metadata only.
- RemoteAccessPeerPairing.redeem(RemoteAccessInvitationBinding, RemoteAccessPairingRequest) -> UUID; confirm(RemoteAccessKeyBinding) -> Optional<RemoteAccessSessionStatus>.
- RemoteAccessPairingTransport.redeem(RemoteAccessSession accessor, RemoteAccessPrivateKey invitationKey, String accessorDisplayName), confirm(RemoteAccessSession) and status(RemoteAccessSession) -> RemoteAccessSessionStatus for the latter two. Transport owns credential retrieval and strict SSH invocation.
- RemoteAccessSessionGrants.install/remove(RemoteAccessSession): managed root session-public-key grants only.
- SSH commands: invitation `redeem` with bounded typed JSON stdin; existing invitation `pair` is eligibility probe. New session `confirm` and existing `status`; no execution/revoke commands.
- Authority frame: `REDEEM <grantorUUID> <invitationUUID> <fingerprint> <base64url-json>`, reply `PROVISIONING <sessionUUID>`. `CONFIRM <grantorUUID> <sessionUUID> <fingerprint>`, reply ACTIVE or DENIED. Existing PAIR/STATUS unchanged.
- Supervisor: SESSION_INSTALL / SESSION_REMOVE with grantor UUID, session UUID, Ed25519 public key, maintaining the existing root-owned authenticated binding format.

## Tasks

- [x] OS boundary: forced helper bounded stdin, new session-only confirm; narrow session grant install/remove, known-version upgrade, Python/real SSH regression (owner: OS agent).
- [x] Typed Java transport: request DTO mapping, channel dispatch, dedicated pinned SSH client, bounded control process execution/no command retry, Java tests (owner: transport agent).
- [x] Grantor/application: atomic consume/reservation using existing persistence service; install key after commit; confirm authenticated key before CAS activation; restart reconciliation and expired grant cleanup (owner: controller).
- [x] Accessor/application: persist key + attempt before RPC; reuse invitation attempt on retry; lost response/ack recovery with session key only; no token persistence. Unconfirmed expired accessor moves to REVOKING and retains key/audit state, never claims remote cleanup (owner: controller).
- [x] Real two-peer SSH/persistence integration: distinct identities/databases and private credential ownership, concurrent redeem, lost responses and restarts, wrong keys/consumed token, no execution (owner: controller).
- [ ] Focused + Agent/Nexus/Console and OS suites, scoped reviews/fixes, evidence, separate PR and CI; stop before Stage 5.

## Invariants / failure policy

No DB locks during SSH or root helper calls. DB/filesystem writes are not atomic; grantor restores only unexpired PROVISIONING grants and removes expired/REVOKING grants. ACTIVE is never inferred from INSERT or metadata. Expired accessor with unknown remote outcome remains REVOKING pending confirmation; denial is not proof of remote cleanup. No runtime commands, UI, public REST, auth redesign, workflow changes, or migrations unless current persistence demonstrably cannot enforce the required uniqueness.
