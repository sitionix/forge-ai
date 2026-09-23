# Remote Access Stage 2 Implementation Plan

> Execute inline with superpowers:executing-plans; regression first, final independent review.

Goal: managed Linux SSH installation and current Agent authorization on every channel, with no workload execution.
Spec: human roadmap Stage 2 and docs/remote-access/design.md, merged Stage 1 base 3b374d0e.
Architecture: dedicated sshd and root-owned forced helper; protected local Unix socket reaches unprivileged Agent authority; dedicated pinned SSH client argv. No HTTP peer/admin API.

## Constraints and decisions
- Only Stage 2; no invitations/session activation UI or arbitrary commands. Pairing/confirm remain unavailable until their stages. Session `status` is the only successful control operation in Stage 2.
- Daemon-wide ForceCommand consumes sshd ExposeAuthInfo proof and selects a root-owned binding by authenticated key hash, not SSH_ORIGINAL_COMMAND. Caller-supplied IDs/commands cannot replace binding.
- Agent checks local instance, GRANTOR role, key fingerprint and persisted current state every time. PROVISIONING can query its own status before deadline; ACTIVE can query status. REVOKING/REVOKED and unavailable authority deny.
- Setup installs an empty protected authorization file. No production grants are fabricated for tests.
- Stage 2 needs no privileged runtime operation: Agent directly owns the restricted channel socket. Dynamic key publication/admin supervisor operations arrive with Stage 3; command containment remains Stage 5. This refines the proposed Stage 0 root-owned channel endpoint without giving Agent root.
- Installer creates dedicated users only, validates ownership of its packaged source and installed paths, uses fixed artifact paths, and refuses conflicts. It leaves host sshd/personal keys untouched. No runtime install is performed on the developer host.
- Stage 0's workload namespace is not replaced by a forced command; no workload runs yet. Peer keys receive no admin REST access. Agent HTTP must remain operator-controlled; no peer HTTP listener is added here.

## Review focus
- A revoked grant whose authorized_keys line remains cannot query status or execute anything.
- Request text cannot forge another grant/resource/key binding, forward traffic, or invoke a shell.
- Socket authorization rejects wrong Unix principal and fails closed on timeout/crash/malformed messages.
- Installer refuses symlinks, insecure packaged code, unexpected owners/users and malformed endpoints; repeated setup preserves host identity and unrelated SSH files.
- Client config/agent/proxy/multiplexing must not override pinning or the dedicated identity.

## Tasks
1. Agent authority and channel: write direct domain-service tests for current-state/key/local-instance failures; implement narrow typed port and service. Add bounded Unix listener and strict small SSH-control frame tests, including SO_PEERCRED and unavailable authority. Wire only under explicit boot configuration.
2. Managed installation/helper: Python unittest regression first. Root-owned fixed forced helper accepts only `status`, sends fixed key binding through the Unix channel and validates bounded response; everything else fails. Installation/preflight is idempotent, supports Linux/systemd only and does not provision a working grant. Tests use temporary roots/mocks for privileged setup; label them accordingly.
3. Dedicated client policy: test argv, pin persistence and key/endpoint validation; use full host public key and managed known_hosts, explicit OpenSSH isolation options, no shell interpolation or retries. No execution facade before Stage 5.
4. Real SSH and product verification: disposable sshd integration tests exercise installed helper/config and stubbed authority explicitly, plus real Agent/Postgres authorization IT. Test wrong host pin, forbidden forwarding/PTY/subsystem/arbitrary commands, foreign binding and stale/revoked/unavailable state. Run full Agent/Nexus and Console regressions; changed Python suite and actual SSH suite. Final independent review, fix required defects, create one Stage 2 PR, stop READY_FOR_REVIEW.

## Implemented decisions and review fixes
- ForceCommand is daemon-wide, so even a bare authorized key cannot get a shell; actual key proof selects protected association metadata.
- Pairing/confirm remain denied; no endpoint or key-publication operation was introduced early.
- Installation does not migrate/restart the operator's existing Agent. Its dedicated prepared runtime must run as forge-control before explicitly enabling the channel. Incorrect ownership fails startup.
- Root setup prepares artifacts but reports NOT READY without a system systemd manager. Live SSH testing runs installed production sshd/config/helper with a stub authority; it does not claim systemd runtime or Codex E2E.
- Independent review identified dangling public-key symlink and missing reboot /run/sshd provisioning. Both reproduced RED, fixed, and passed real filesystem/sshd regressions.
- Final client review identified replaceable pin ancestors. A regression accepted the insecure path before the fix (RED); the client now rejects foreign-owned/writable non-sticky ancestors (GREEN).
- Final complete Agent verification passed; Nexus verify, Console 545 tests/typecheck/build and 28 real-SSH assertions passed. Stage 3 remains unstarted.

All four tasks complete. READY_FOR_REVIEW; Stage 3 not started.
