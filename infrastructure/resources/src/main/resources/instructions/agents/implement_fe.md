# Implement FE Instructions

## Goal

Implement frontend-owned production code for the assigned scope using the lane task, runtime context, and any relevant API-generated frontend artifacts.

The result of this lane is frontend implementation plus a successful `implement-fe` completion callback.

---

## Execution

- Work only inside the assigned frontend scope.
- Use the lane task and runtime context as the implementation source.
- Use API-generated frontend artifacts only when they are present and relevant to the assigned scope.
- Implement the requested user-facing behavior directly and minimally.
- If the requested behavior already exists, avoid unnecessary changes.
- If the required scope context or API dependency is missing, stop and report the exact missing input.
- Review the final diff before completion and remove unrelated changes.
- Implement-fe lane MUST NOT add new test classes or new test methods.
- Implement-fe lane MAY update existing tests only when required for compatibility with changed frontend production code.
- If behavior validation requires new tests, implement-fe lane MUST hand off to test-unit lane instead of adding tests.

---

## Frontend Structure

- Follow the existing frontend module structure in the assigned scope.
- Preserve existing route, page, component, hook, client, state, and style conventions.
- Reuse existing mappers, hooks, clients, and UI primitives where they already fit the requested behavior.
- Keep implementation focused on the assigned scope and task.
- Avoid broad refactors unless the lane task explicitly requires them.

---

## Frontend Boundaries

- Keep UI composition, state handling, data-fetch integration, and view mapping responsibilities separated according to existing SPA architecture.
- Keep transport/client concerns in existing client/adaptor layers.
- Keep view mapping and normalization in existing mapper/helper layers when the SPA already uses them.
- Preserve existing behavior unless lane task explicitly requires behavior change.
- Prefer small explicit implementation over broad refactoring.

---

## API Context

- Treat API-generated frontend packages and client artifacts as source of truth when the task depends on API integration.
- Use the provided frontend dependency and evidence notes from the runtime input.
- Do not invent API operations, fields, hooks, clients, package names, or contract behavior.
- Do not repeat API artifacts in the completion payload.

---

## Mapping And UI State

- Use existing frontend mappers/transformers for request/response/view-model mapping.
- Add or extend mapping helpers only when changed code requires it.
- Keep mapping logic out of page/component rendering code when project already separates it.
- Keep business/domain decisions out of presentational components.
- Keep state transitions explicit and consistent with existing SPA patterns.

---

## Code Quality

Before completion:

- Check changed files against these instructions.
- Keep route/page/component behavior consistent with the existing frontend style.
- Remove stale code related to the replaced frontend behavior.
- Avoid introducing duplicate UI logic in changed files.
- Do not introduce new Sonar issues in changed frontend code.

## Sonar Verify

Before completion, wait for SonarCloud result for the frontend PR update.

Use only SonarCloud output for completion metrics.

Do not invent Sonar numbers.

Sonar verification MUST use active polling of PR checks until SonarCloud result is available or retry budget is exhausted.
Minimum retries: 5 attempts with backoff (30s, 60s, 90s, 120s, 150s).
Only infrastructure-level unavailability after retries is a valid reason to stop Sonar verification.
In that case, report exact evidence and request next user instruction.

Do not complete the lane with serious new Sonar issues in changed frontend code.

Unacceptable issues include:

- security issues;
- unused imports;
- unused variables;
- dead code;
- obvious null/undefined safety bugs;
- duplicated large code blocks;
- unreadable control flow in changed code;
- broken behavior-level checks in changed test updates related to this lane.

Only minor style-level issues may be tolerated when they are harmless and consistent with local style.

Coverage and issue metrics in completion payload must come from SonarCloud only.

## Completion Callback

After frontend implementation is complete, call the provided `implement-fe` completion endpoint.

Build the request from the provided OpenAPI completion contract reference and runtime values.

The completion payload represents frontend implementation facts only.

Allowed payload content:

- `scope`
- `summary`
- `changedFiles`
- `affectedSurfaces`
- `uiBehavior`
- `sonar`

### `scope`

Assigned frontend scope from runtime context.

### `summary`

Short factual summary of the implemented frontend behavior.

### `changedFiles`

List only frontend source files changed by this lane.

Each item must contain:

- `path`
- `reason`

### `affectedSurfaces`

Describe the user-facing or frontend-technical surfaces changed by this lane.

Allowed surface types are defined by the OpenAPI contract.

Each item must contain:

- `type`
- `name`
- `summary`

### `uiBehavior`

List user-visible behaviors that are now implemented.

Do not include test results, reviewer notes, backend persistence details, or duplicated API contract data.
