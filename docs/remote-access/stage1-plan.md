# Remote Access Stage 1 Implementation Plan

> Execute with superpowers:subagent-driven-development; regression tests first.

Goal: persistent invitation/session lifecycle and restrictive local credential storage, without SSH access.
Architecture: immutable domain aggregates and narrow repository ports; existing Postgres adapters; filesystem adapter for opaque key references; application transactional orchestration.
Tech stack: Java 21, Spring, JdbcTemplate/PostgreSQL, Flyway, JUnit 5/AssertJ/Mockito, POSIX filesystem.
Spec: user Stage 1 in Remote Access Roadmap; docs/remote-access/design.md. Stage0 merge b6516ca2.

## Global constraints
Only Stage1. No SSH provisioning, key generation, network pairing, controllers, runtime execution, production supervisor, workflow changes or Stage2+. No new PR metadata/merge. Stop READY_FOR_REVIEW.
Tests first. Public material typed. Private key bytes local only, DB opaque reference. No generic vault/lifecycle framework. Do not alter old migrations/SshConnection/AgentExecutionSession.

## Review focus
- Concurrent redemption creates one winner and rolls back session if invitation reservation fails.
- Persisted terminal state cannot be overwritten by a stale aggregate.
- Expiry uses caller's injected server Clock, exact deadline rejected.
- Root/key symlinks, insecure permissions, and failure cleanup cannot overwrite/delete somebody else's key.
- Secret-bearing string/serialization and exception messages do not expose private material.

### Task 1: Typed domain aggregates and lifecycle tests
Owner: domain implementer. Files domain/model/RemoteAccess*.java; application/src/test/.../RemoteAccessDomainTest.java.
- [x] Failing transition/default/deadline/role/validation tests.
- [x] Implement RemoteAccessEndpoint, Role, SessionStatus, Connectivity; RemoteAccessInvitation and RemoteAccessSession immutable records.
- [x] Validate immutable construction + methods without transport dependencies. No networking.
- [x] Focused tests and review.

### Task 2: Local credential storage
Owner: storage implementer. Files domain/port/RemoteAccessCredentialStore.java, domain/model/RemoteAccessPrivateKey.java, infrastructure/local/.../LocalRemoteAccessCredentialStore.java and its tests.
- [x] Failing permissions, exclusive creation, restart/read/delete, secret redaction and failure cleanup tests.
- [x] Opaque UUID reference for each session's supplied private material. POSIX0700 directory/0600 file, fail closed on symlinks/insecure permissions. No SSH key generation.
- [x] No public bean getter for private material; explicit copyBytes and close zeroizes internal bytes; redacted toString. No domain Jackson dependency.
- [x] Tests verify write/read filesystem failure safety and no accidental serialization (application test where Jackson already available).

### Task 3: Persistence and application lifecycle
Owner: controller. Files domain/port repository ports; Postgres JDBC adapters; next migration; application service; boot real-Postgres IT.
- [x] Failing repository/migration/restart/race tests.
- [x] Invitation/session tables, singleton instance identity, constraints. One invitation/session, versioned compare-and-set session updates.
- [x] Transactional grantor reserve creates session and consumes invitation once. Accessor persists local provisioning metadata/key before later remote call (none in Stage1).
- [x] Failure removes only newly created key; no DB/filesystem atomicity claims. Lifecycle operations do not publish ACTIVE on creation.
- [x] Identity/aggregate roundtrip across application restart; migration preserves legacy schema.

### Task 4: Integration, independent review and verification
- [x] Focused tests and regression matrix review; resolve required findings.
- [x] Agent verify; Nexus verify; Console tests/typecheck/build. Changed OS scripts none.
- [x] Stage1 evidence/limitations and stop READY_FOR_REVIEW. No Stage2.

## Interface agreement
Domain records use domain.model package and existing UUID/Instant conventions.
Invitation: id,grantorInstanceId,RemoteAccessEndpoint endpoint,pairingPublicKey,pairingFingerprint,createdAt,expiresAt,consumedAt,cancelledAt,redeemedSessionId.
Session: id,invitationId,RemoteAccessRole localRole,grantorInstanceId,accessorInstanceId,peerDisplayName,endpoint,pinnedHostPublicKey,sessionPublicKey,sessionFingerprint,UUID localPrivateKeyReference,status,createdAt,provisioningExpiresAt,activatedAt,revokeRequestedAt,revokedAt,connectivity,lastSeenAt,lastCheckedAt,failureCode,failureMessage,long version.
Endpoint: host,port,username.
Invitation methods: isUsable(Instant), redeem(UUID sessionId,Instant), cancel(Instant).
Session methods: activate(Instant), requestRevoke(Instant), confirmRevoked(Instant). No implicit ACTIVE/default fallback. Each changed copy increments version; idempotent same-state revoke returns same instance.
Credential port: UUID store(UUID sessionId,RemoteAccessPrivateKey); RemoteAccessPrivateKey read(UUID reference); void delete(UUID reference). Reference is session UUID; no arbitrary paths in domain.

## Review correction
The provisioning service must own its transaction boundary. A real PostgreSQL
regression reproduced that joining an ambient transaction bypasses compensation.
Both entry points now reject ambient transactions before identity/key writes.
Independent re-review found no remaining blocking defect; final full Agent
verify passed (780 executed, 2 opt-in live tests skipped). Nexus 236 and Console
545 tests/typecheck/build passed. Stage 1 is READY_FOR_REVIEW; Stage 2 not started.
