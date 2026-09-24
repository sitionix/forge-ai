# Stage 7 — Remote Access Console design checkpoint

Status: approved by the user to proceed after roadmap verification. Stage 6 merged in PR #147 at `58f2854f`.
The user authorized Stage 7 on 2026-09-24. Stage 8 remains outside scope.

## Outcome and boundaries

Add one global Remote Access page to the existing Console. A local operator can
create/copy/cancel an invitation, connect to a grantor using its token, inspect
both directions of the relationship, check connectivity, and request/retry revoke.
Reuse the Stage 6 API and Stage 5 state semantics. No backend protocol, lifecycle,
workflow, project SSH profile, execution helper or runtime changes.

## Chosen integration

Use `src/operator/remote-access.html`, a focused page module and typed API facade,
registered in `operator-bootstrap.js` / existing `OperatorRouter`. Add a global
sidebar link and reuse operator CSS, DOM helpers, `RequestCoordinator` and
`PollingCoordinator`. Ship through the existing static-copy build.

A project SSH-profile extension would conflate global sessions with project
connections. A separate frontend application would duplicate routing and assets.
The global existing Console module fits the roadmap without either change.

## Operator access

On mount, call GET `/agents/remote-access/operator/session` under the existing
context-aware infrastructure prefix. On 401 show a local operator login form.
POST login uses the Stage 6 bootstrap secret; clear its input after submission.
Keep CSRF only in memory, use the existing HttpOnly cookie, and send X-CSRF-TOKEN
on mutations. Logout/dispose clears in-memory credentials and transient secrets.
An expired operator session returns to login; do not automatically replay a mutation.

The API facade must not expose raw secret-bearing response bodies through common
error diagnostics. Use safe code/message/correlation fields, no request/response
logging, browser persistence or URL tokens. Use same-origin requests only.

## Page flow

Authenticated header: Connect, Give Access, Refresh, and Log out.
Readiness comes from GET capabilities. Show actionable safe diagnostics; do not
claim unavailable operations are ready. Invite POST supports explicit advertisedHost
where required; no new interface-discovery endpoint or guessed LAN address.

Give Access opens a bounded form. Successful POST shows the token once, with Copy,
server expiresAt countdown and Cancel invitation. Clipboard failure is visible.
Clear the token on dialog close, expiry, cancel, logout or navigation. GET invitation
metadata can restore pending invitation information but cannot restore its token.
Do not automatically cancel an invitation just because its dialog closes.

Connect accepts a token and previews its grantor display name/SSH endpoint before
submission. Token envelope decoding is presentation only: labels are untrusted,
never authorization or authoritative expiry evidence. Check format/size for UX;
Agent remains authoritative. Do not render/log the embedded pairing private key.
Clear token and preview on completion or close. Disable duplicate submission.
Display 201 ACTIVE and 202 PROVISIONING honestly. After uncertain failure refresh
session metadata before presenting retry; require token re-entry for another POST.
Retry remains Agent's existing durable-attempt behavior, no new frontend attempt ID.

## Session lists and actions

Two sections: I can access (ACCESSOR), Access granted to (GRANTOR).
Cards show peer label, endpoint, authorization status, last checked/seen and
connectivity observation separately. ACTIVE is never labeled Online. ACCESSOR
status is the latest confirmed observation, not proof of current grantor reachability.

Provide Check and role-appropriate Disconnect/Revoke Access. A revoke response
with REVOKING remains visible with pending/error/retry information. Network errors
never synthesize REVOKED, remove audit rows or clear an unresolved failure.
Confirmed REVOKED displays the completed state. Do not add Forget locally.
Only explicit Check POST performs a connectivity probe; polling GET does not.

## Requests and disposal

Use a single non-overlapping metadata polling loop while authenticated and mounted,
paused while hidden. Refresh more frequently while provisioning/revoking, otherwise
use the existing status interval. Stop timers and abort reads on disposal/logout.
Generation/request guards reject late responses from old pages, login sessions,
dialogs and pre-mutation refreshes. Mutation pending state prevents double actions.
Aborting a local request is not advertised as undoing a server-side operation.
Never impose a new 30-second browser timeout on the 120-second upstream lifecycle.

## Test-first implementation and evidence

Vitest/jsdom follows existing Console test patterns, exercising actual page and
API facade with controlled HTTP responses. First add failing cases for login/CSRF,
role lists, create/copy/cancel, preview/connect/202/retry, double-click and stale
responses, polling disposal, offline/revoke failure truthfulness, escaped peer
labels, and no browser persistence/URL secrets. Verify sidebar/router ownership.

Use the production HTML in page tests where practical. Run Console full tests,
typecheck/build, Agent and Nexus verify, and relevant existing SSH regression per
roadmap. A browser smoke check must name which components are real or stubbed.
Do not label jsdom/stub HTTP as real two-machine SSH or live Codex acceptance.

End with one Stage 7 implementation PR and READY_FOR_REVIEW; no merge or Stage 8.
