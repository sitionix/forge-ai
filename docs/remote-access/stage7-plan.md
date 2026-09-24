# Stage 7 Console Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, regression first.

**Goal:** Add global Remote Access UI using the merged Stage 6 management API.
**Architecture:** Existing Console page/router/CSS, focused API facade, page lifecycle
and rendering modules. Agent remains authoritative; frontend holds no persistent secrets.
**Tech Stack:** Existing ES modules, declaration files, Vitest/jsdom, same-origin fetch.
**Spec:** `docs/remote-access/stage7-ui-design.md` and original Stage 7 roadmap.

## Global constraints

No Stage 8, backend/protocol/workflow changes, new dependencies, browser secret
persistence or automatic replay of mutations. Existing HTTP-only operator cookie
and in-memory CSRF. Preserve 201/202 and 200/202 behavior and separate connectivity.

## Review focus

- Logout or closed dialog while a response is in flight: no token resurrection.
- Poll started before revoke: must not overwrite its newer result.
- Pairing outcome unknown: refresh metadata before retry, no automatic POST replay.
- Hostile peer/error content: inert text, no raw credential diagnostic rendering.
- Capabilities differ by role: missing GIVE_ACCESS must not block valid CONNECT.

## Task 1 — API boundary

Files: `src/operator/remote-access-api.js`, `.d.ts`, `tests/remote-access-api.test.ts`
(relative to `services/forge-console`).
Produces `RemoteAccessApi` with operatorSession/login/logout/capabilities/invitations/
invite/cancel/sessions/connect/check/revoke, plus clear() for local CSRF.
Consumes native same-origin fetch and contextPathFromLocation.

- [x] Add failing tests for endpoint paths, explicit same-origin cookie transport,
  CSRF mutations, 201/202/200/202 response status, no token persistence,
  local missing-CSRF rejection and safe errors with malicious secret-bearing bodies.
  Example: `expect(init.headers['X-CSRF-TOKEN']).toBe('csrf-fixture')` after real login.
- [x] Run `npm test -- tests/remote-access-api.test.ts` and record RED.
- [x] Implement facade: `request(method,path,body,signal)` sends no-store/same-origin,
  parses success DTOs, maps failures without raw response body, never retries POST.
- [x] Run focused and full Console tests; commit boundary and tests.

## Task 2 — page, rendering and navigation

Files: `remote-access.html`, `remote-access-page.js`, `.d.ts`,
`remote-access-view.js`, `operator-bootstrap.js`, `operator-ui.css`,
`tests/remote-access-page.test.ts`, existing router ownership tests as needed.
Consumes RemoteAccessApi and existing RequestCoordinator/PollingCoordinator.
Produces mount()/dispose() page integrated as `remote-access` in OperatorRouter.

- [x] Add tests against production HTML (jsdom), fake HTTP at fetch boundary.
  Cover all review-focus cases, login/logout, readiness/address input, token
  preview/copy/countdown/cancel/clear, role cards/check/revoke/retry, double submits,
  session expiry, stale navigation responses and polling stops.
- [x] Run page tests RED before implementation.
- [x] Implement page ownership and state: authenticated generation, dialog generation,
  pending guards, abortable reads and per-mutation refresh invalidation. Render
  peer values with textContent/escaping, no secrets in datasets/URLs/errors.
- [x] Register sidebar/page; use existing CSS and bootstrap, no new router.
- [x] Run focused tests, full Console tests/typecheck/build; commit.

## Task 3 — review, verification and delivery

- [x] Independently review final diff against spec and fix blocking findings with RED→GREEN.
- [x] Run full Agent/Nexus verify, Python Remote Access tests, existing isolated SSH
  regression; label real/stub components precisely. Browser smoke if available.
- [x] Update roadmap/evidence/install usage; `git diff --check`.
- [ ] Push separate Stage 7 PR, await CI, stop READY_FOR_REVIEW. No Stage 8 or merge.
