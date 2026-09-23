# Remote Access Stage 3 implementation plan

**Goal:** Issue one-time, five-minute invitation tokens; no session activation or execution.
**Spec:** Human Remote Access roadmap Stage 3; docs/remote-access/design.md.
**Architecture:** Agent persists invitation metadata and checks authoritative server state on each pairing channel. A narrow root supervisor accepts only control-UID invitation grant installation/removal; SSH identity remains derived from authenticated key bindings. Token serialization is typed and secrets are redacted. No REST/UI until their later stages.
**Execution:** Inline, regression first; user authorized this stage after merging PR #143.

## Work and verification

- [x] Add invitation eligibility regression to real Agent service/channel boundary; expired/cancelled/consumed/wrong-key/wrong-grantor fail closed. Extend typed authority and forced pairing command, without session creation.
- [x] Add token/key tests: typed versioned roundtrip, strict envelope/size/endpoint/key validation, self pairing denial, redaction. Generate ephemeral Ed25519 material without durable grantor private-key storage.
- [x] Add create/cancel/list service tests: explicit advertised endpoint, five-minute TTL, persist then provision before token emission, compensation on provisioning failure, metadata-only reads, cancellation cannot revoke sessions.
- [x] Add narrow protected supervisor tests and actual SSH pairing tests: only control UID may mutate invitation grants, idempotent cleanup touches only matching grants, pairing key cannot read session status or execute, authority outage denies.
- [x] Wire stage-specific boot configuration; explicit endpoint/identity, no interface guessing or management REST.
- [x] Run focused then full Agent/Nexus/Console regression and actual Docker SSH suite. Document exact mocked/real boundaries and operational setup.
- [x] Review final scope, commit/push, open separate Stage 3 PR; stop READY_FOR_REVIEW.

## Review focus

Partial authorization write must be removable, and no token escapes provisioning failure. Expiry must use the grantor clock even if token metadata is altered. A pairing key must never select a session operation. Root file operations must reject symlinks/foreign files and preserve unrelated grants. Secrets must not appear in exceptions, logs, metadata listings, or default string representations.

## Implementation decisions / review record

- Reused the existing isolated worktree; created feature/SITIONIX-137 from merged main after inspecting local/remote branch numbers.
- Added only invitation grant operations to a narrow privileged supervisor because the Stage 2 authorization source is root-owned; Agent remains unprivileged.
- No management REST/UI or activation handshake in Stage 3. Pairing channel eligibility acknowledgement is not a session/execution status.
- Read-only reviewer found two actual defects: fractional Jackson coercion and Stage 2 helper upgrade conflict. Both reproduced, fixed, tested and re-reviewed. No remaining findings were reported.
- Verification and real/stubbed boundaries are recorded in docs/remote-access/evidence.md. Stage 4 is not authorized.
