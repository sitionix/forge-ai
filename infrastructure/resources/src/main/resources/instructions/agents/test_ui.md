# Test UI Instructions

## Goal

Add or update UI tests for affected frontend behavior in the assigned SPA scope.

The result of this lane is verified UI behavior plus a successful `test-ui` completion callback.

---

## Execution

- Work only inside the assigned frontend SPA scope.
- Use only the existing SPA stack already used in that scope.
- Add or update tests only for behavior affected by this lane.
- Keep changes minimal and aligned with existing frontend test style.
- Reuse existing test patterns, helpers, and fixtures from the same SPA module.
- If required context, source file, component/page context, or test dependency is missing, stop and report the exact missing input.
- After tests are updated, update the PR according to the provided PR workflow.
- After PR update, wait for SonarCloud result.
- Use only SonarCloud output for completion metrics.

---

## SPA Stack Boundary

Test code must stay in the same language and test framework already used by the assigned SPA.

Do not introduce another language, test runner, assertion library, or UI test framework that is not already used in that SPA scope.

Do not add backend, database, messaging, or service-internal integration behavior in this lane.

---

## UI Test Scope

UI tests must validate user-visible behavior for assigned frontend scope.

Cover only observable outcomes, for example:

- render state;
- user interaction result;
- validation feedback;
- loading/empty/error states;
- route-level behavior when relevant.

Do not test private implementation details.

Do not move into backend ownership.

Keep tests deterministic and avoid flaky timing assumptions.

---

## Test Structure

Use repository-local frontend style.

Keep tests behavior-focused:

- `given` — setup render/input/mocks/fixtures;
- `when` — perform one interaction or one observable flow;
- `then` — assert visible result and behavior-relevant effects.

Each test should verify one clear behavior.

Verify collaborator interactions only when needed for observable UI behavior.

Use explicit negative-path assertions for error/validation paths.

---

## Fixtures And Helpers

Build test data using existing SPA helpers/fixtures.

Prefer one canonical helper per fixture/object type.

Keep defaults in helper definitions.

Pass only dynamic values as helper arguments.

Do not duplicate large setup blocks across tests.

Avoid mutable shared fixture state.

---

## Generated Artifacts

If the SPA already uses generated frontend artifacts or contract types, use those existing artifacts.

Do not generate new contracts or invent ad-hoc API shapes in this lane.

Do not invent fields, payload structures, endpoint paths, enum values, or package coordinates.

If required generated artifacts are missing or conflicting, stop and report the exact issue.

---

## Sonar And Quality Gate

Before completion, changed UI-test code must pass lane quality gate.

Do not finish with serious new Sonar issues in changed UI-test code.

Unacceptable issues include:

- security issues;
- unused imports;
- unused variables;
- dead code;
- broken assertions;
- flaky tests;
- duplicated large setup blocks;
- unreadable test structure;
- accidental extra interactions left unverified.

Only minor style-level issues may be tolerated when they are harmless and consistent with local style.

Coverage is a hard gate.

`sonar.coveragePercent` must be at least `90.0`.

If coverage is below `90.0`, add or improve UI tests and wait for a new SonarCloud result.

Do not stop after the first SonarCloud failure if coverage is below `90.0`.

You must continue an iterative test-improvement loop (update tests -> update PR -> wait for SonarCloud) until either:

- coverage reaches at least `90.0`; or
- a real blocker is hit (for example missing dependency, environment failure, or required context gap) and reported explicitly.

Coverage below `90.0` without a blocker is not a terminal state and completion callback is forbidden in this state.

Do not invent Sonar numbers.

Completion metrics must be copied from SonarCloud output only.

---


## Local Verification Gate

Before completion callback, run a full frontend dependency and test verification in the assigned SPA scope.

Required gate:
- execute package installation with the repository package manager (`npm install`/`pnpm install`/`yarn install` based on the SPA);
- execute the SPA test command used in that scope;
- completion is forbidden if install or tests fail.

If install/tests cannot be executed because of missing dependencies/environment constraints, report exact blocker and do not call completion.

Push and callback ordering rule:
- run this verification after the final local code change for the lane and before `git push`;
- do not push commits if verification failed;
- do not call completion callback unless verification is still valid for the pushed commit (no new commits after verification).

## Completion Callback

After UI-test work is complete and SonarCloud result satisfies the lane gate, call the provided `test-ui` completion endpoint.

Build the request from provided OpenAPI completion contract reference and runtime values.

The completion payload represents UI-test lane facts only.

Expected semantic content:

- `scope` — assigned frontend SPA scope;
- `summary` — short factual summary of completed UI-test work;
- `coveredCases` — covered UI behavior cases validated in this lane;
- `sonar` — aggregated SonarCloud result for the lane.

`sonar` is one aggregated object for the lane, not per file.

Use runtime `scope`.

Use runtime `ticketId` and `laneId` in callback path.

Do not include backend details, integration-test reports, reviewer status, or invented metrics.

---

## Completion Rule

Call completion only after:

- affected frontend behavior was processed;
- required UI tests were added or updated;
- PR workflow was completed;
- SonarCloud result was received;
- coverage is at least `90.0`;
- completion payload can be filled truthfully.

If completion cannot be submitted, report exact failure in final output.
