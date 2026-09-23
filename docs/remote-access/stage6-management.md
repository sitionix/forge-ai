# Stage 6 — local management API and typed Nexus integration

Status: design checkpoint for external approval; implementation not started.
Base: PR #146 merged into main (`a102de5c`). Stages 7–9 are excluded.

## Intent and existing code

Expose the existing invitation, pairing, session observation and revoke lifecycle
through local HTTP management. Agent remains the sole persisted authority; Nexus
maps and delegates. Inter-machine control remains SSH. No execution endpoint,
Console page, remote helper, new lifecycle state or workflow change is included.

The existing building blocks are `RemoteAccessInvitations`,
`RemoteAccessAccessorPairing`, `RemoteAccessExecutionService`,
`RemoteAccessAccessorExecution`, and their repository/transport ports. Reuse them
rather than duplicating their lifecycle. Existing `RemoteAccessHttpBindValidator`
protects channel-enabled Agent startup. `RemoteAccessPairingTransport.status`
already supports a bounded authenticated SSH observation. The session aggregate
already stores connectivity and observation timestamps, but currently has no
operation/repository CAS for updating them.

Nexus follows `ForgeAiProjectSshConnectionsController` →
`ManageAgentProjectSshConnections` → `AgentProjectSshConnectionsUseCase` →
`ForgeAgentClient` adapter as the local pattern: thin controller, MapStruct mapper,
transport-free domain port, map/execute/map adapter and typed HTTP client.
`NexusProxyTestManager` remains the single ForgeIT support manager.

## Operator and service boundaries

Implement the boundary previously specified in `design.md`, rather than treating
loopback as authentication:

- Opt-in management configuration requires explicit loopback binds, an exact
  configured browser origin and separate protected secret files for operator
  bootstrap and Nexus-to-Agent authentication. Missing/unsafe configuration fails
  startup. Feature disabled preserves ordinary service behavior.
- Nexus exposes local operator login/session/logout under its Remote Access
  prefix. Login accepts a typed bootstrap-secret request, requires the configured
  Origin and Host, rotates the session identifier, and returns a session-bound
  CSRF token. Session expires after 15 minutes, is invalidated by logout and is
  not durable across Nexus restart. Secrets are not browser-persisted.
- Use standard Spring Security session/CSRF facilities in a path-scoped chain,
  not a bespoke session/permission framework. Cookie is HttpOnly, SameSite=Strict,
  host-only and restricted to this API prefix. Loopback HTTP is supported;
  Secure is mandatory when the configured browser origin uses HTTPS.
- Authenticated browser mutations require exact Origin and a session-bound CSRF
  header, including logout. Login is protected against login-CSRF by exact Origin,
  JSON-only typed body, no CORS allowance, and bounded login attempt throttling.
  GET session bootstrap returns CSRF only to the authenticated same-origin client.
- Validate the actual connection address and configured Host/Origin, ignoring
  caller-supplied forwarded headers. Do not enable permissive CORS or trust a
  reverse proxy implicitly. Remote browser/LAN management is outside this stage.
- Agent management endpoints require the distinct Nexus service credential plus
  loopback binding. Pairing/session SSH credentials cannot authenticate HTTP.
  No browser cookie/CSRF token or pairing token is forwarded as service identity.
- Installation documents and generated protected runtime configuration identify
  the secret-file owners and permissions. Workload identities cannot read them.
  Setup fails on unsafe/missing files; no default credential or silent fallback.

Alternative considered: a static credential pasted on every browser request.
Rejected because it exposes a long-lived secret to routine browser operations and
fails the existing short-lived session design. A global application authentication
redesign is unnecessary; the new chain applies only to Remote Access paths.

## Typed operations

Agent prefix `/api/v1/remote-access`; Nexus prefix
`/api/v1/infrastructure/agents/remote-access`.

| Method/path | Behavior |
| --- | --- |
| GET capabilities | Safe readiness/operation availability and explicit configuration diagnostics; no provisioning side effect |
| POST invitations | Optional advertisedHost; configured SSH port/user and local display name; 201 metadata plus one-time token |
| GET invitations | Metadata only, never token/private key |
| DELETE invitations/{id} | Idempotent cancel for known invitation; consumed invitation never revokes its session |
| POST sessions | Typed pairingToken input; reuse persisted invitation-bound attempt and key; 201 only ACTIVE, 202 only ongoing PROVISIONING |
| GET sessions | Safe local-role/session/observation summaries without SSH calls |
| GET sessions/{id} | Same safe details for a locally owned session; unknown/foreign local resource 404 |
| POST sessions/{id}/check | Explicit bounded authenticated SSH status observation on ACCESSOR; update observation through optimistic CAS |
| DELETE sessions/{id} | Dispatch local role to existing revoke service; 200 only confirmed REVOKED, 202 REVOKING; retain audit row |

Session summaries expose identity, peer display name, endpoint, public host
fingerprint, role, lifecycle timestamps/state, last observed connectivity and safe
failure metadata. Omit private-key references and all credentials. Invitation
create and operator responses use `Cache-Control: no-store`; apply no-store across
the feature to avoid caches retaining session metadata or errors.

No implicit inverse SSH access exists for GRANTOR. Its check endpoint reports
UNKNOWN and records local check time without fabricating reachability or opening
a reverse connection. ACCESSOR observations never set authorization ACTIVE or
REVOKED merely because transport succeeded/failed. A failed probe records
UNREACHABLE with checkedAt; lastSeenAt advances only on authenticated response.
Concurrent observation/lifecycle writes use the existing version CAS and respect
the winner. GET remains read-only with respect to network observation.

A retry of a still-valid local pairing attempt reuses the existing persisted key.
If token expiry prevents decoding for a later restart recovery, use the existing
session ID and server-side reconciliation, not invitation-key replay. POST must
not return 202 for a terminal/failed attempt; return its safe typed conflict/error.

## Errors and secrets

Safe error body: code/message/correlationId. Invalid body/token 400; unauthorized
401; forbidden Origin/CSRF 403; unknown local resource 404; consumed/conflicting
state or host identity mismatch 409; positively known expired/cancelled invitation
410; unavailable setup/peer 503. Preserve upstream status/code through Nexus.

Do not infer remote error causes from stderr or label an ambiguous SSH failure
as expiry/identity mismatch. Only emit a specific code when existing local or
structured authenticated evidence supports it; otherwise return an unavailable
failure without secrets. Preserve 202 for actual ongoing durable provisioning.

Use typed request/response and error DTOs at each boundary, redacted toString for
secret-bearing records, standard Jackson and validation. No JsonNode, Object,
raw JSON forwarding or domain dependency on HTTP DTOs. Inspect existing error
handling carefully: generic upstream exceptions currently retain response text
and some handlers log throwable causes. The new slice must not log payloads,
tokens, credentials or raw upstream exceptions. Use synthetic secret canaries in
serialization/error/log tests, never real credentials.

## Implementation boundaries

1. Agent application management facade delegates existing lifecycle services;
   explicit capabilities/configuration and observation responsibilities.
2. Minimal typed aggregate observation operation plus repository validation/CAS;
   existing columns suffice, no schema/lifecycle migration.
3. Agent REST DTO/mapper/controller/error boundary and service authentication.
4. Nexus domain port/use case, dedicated typed client mappings and API endpoints;
   operator session/Origin/CSRF configuration remains outside transport-free domain.
5. Protected runtime configuration and installation documentation.

The Stage 5 atomic revoke and real cancellation code remain intact. No public
command-execution REST API, peer HTTP protocol, generic auth/parser framework,
Console page or Stage 8 helper is introduced.

## Regression-first verification

- Unit: controller/use-case/mapper/adapter behavior using direct SUT, JUnit 5,
  MockitoExtension and AssertJ; mapper expected-vs-actual assertions.
- Agent: safe DTO exclusion/redaction; local ownership; no-network GET; positive
  and negative check CAS; role-specific revoke; exact 201/202/200 lifecycle truth;
  duplicate connect attempts reuse key/session; unavailable configuration errors.
- Production-configured HTTP tests: missing/wrong service credential, wrong
  Host/Origin, missing/wrong CSRF, unauthenticated/expired operator session,
  bootstrap/login/logout, cookie attributes, no-store, malformed secret-bearing
  request/exception redaction, wildcard bind startup failure.
- Nexus ForgeIT: existing single manager, typed endpoints and fixtures; all nine
  management operations; upstream 400/404/409/410/503 and code/correlationId
  preservation; zero upstream calls for auth/CSRF/body rejection.
- Actual loopback HTTP smoke with disposable fixtures proves the configured
  browser-to-Nexus-to-Agent boundary. Mark SSH components explicitly real/stubbed;
  do not call a mocked browser/API test live SSH or Codex E2E.
- Full Agent/Nexus verification, Console regression/typecheck/build, Python
  Remote Access tests, affected installation tests and actual Stage 4/5 SSH suite.
  Retain the real close→SSH disconnect→systemd/descendants/registry/fence cleanup
  assertion and unrelated-session isolation.

Stage completion requires reviewable production changes and passing evidence,
then `READY_FOR_REVIEW`. No merge, PR metadata/comment mutation or next stage
without the corresponding user instruction.
