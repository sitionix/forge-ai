# Stage 6 implementation plan

Authority: human roadmap Stage 6 and approved stage6-management.md.
Execution: inline, regression first. No Stage 7/8/9, merge or host runtime changes.

## Task 1: Agent management and observations

Add `RemoteAccessManagement` application facade delegating invitation/pairing/revoke.
Use `list/get/createInvitation/cancelInvitation/connect/check/revoke/capabilities`.
Add `RemoteAccessSession.observe(connectivity, checkedAt)` and repository
`recordObservation(before, connectivity, checkedAt)` with optimistic version CAS.
No lifecycle mutation in observation; GRANTOR and keyless REVOKED checks are UNKNOWN.
Tests: no-network GET, foreign ownership, reachable/unreachable checks, no reverse
access, CAS winner, delegation, no implicit environment readiness.
RED: focused tests before implementation; GREEN: application suite.

## Task 2: Agent typed HTTP and local service boundary

Add typed Remote Access DTOs, explicit mapper, controller and safe advice.
All management endpoints are opt-in; service credential from protected file,
explicit loopback bind and no credential-bearing logs. Requests validated before
application invocation. Return 201/202 and 200/202 from actual lifecycle states.
Tests: exact response shape/status, invalid input, secrets, service auth, disabled
paths, explicit bind and actual loopback HTTP socket via Boot integration test.

## Task 3: Nexus typed proxy and operator authentication

Add transport-free management port/usecase, typed client DTOs and MapStruct mapping,
map/execute/map adapter, thin API controller and safe typed error advice.
Use existing client/ForgeIT patterns, separate service credential, no request-body
forwarding or generic permission framework. Browser auth uses Spring Security
session/CSRF, typed login, exact origin/host, context-aware cookie path and expiry.
Tests: direct usecase/mapper/adapter/controller, production filter chain and ForgeIT
operations with zero upstream requests on local rejection. Local retry uses Agent
persisted attempt; no Nexus session-lifecycle authority.

## Task 4: Runtime configuration and evidence

Add protected management runtime setup/config regression; never edit actual host
runtime. Document exact owners/permissions, localhost URL, bootstrap, cleanup and
scope. Update roadmap authorized Stage 6 and merged Stage 5.
Tests: generated config, secret-file modes/redaction, no external/broad bind.

## Task 5: Full verification and review

Run Agent/Nexus verify with Docker API 1.44, Console tests/typecheck/build, Python
Remote Access tests and real Stage 4/5 SSH suites. Record actual/stubbed components.
Fresh whole-branch review; fix concrete defects regression-first. Stop for external
review with READY_FOR_REVIEW, no next stage.

## Interface checks and review focus

Agent DTO ↔ Nexus client DTO ↔ domain ↔ API: same fields and typed errors.
HTTP codes must reflect persisted lifecycle; never fake 202 on terminal failure.
Cookie path includes /fgaisox; require Origin on login and unsafe requests.
No-network GET and observation CAS cannot change authorization or erase failures.
No raw upstream exceptions/token bodies in logs; no private key references in DTO.
Connection failure cannot prove revoke or remote expiry. Idempotent retry must
never replace a persisted attempt's session key.
