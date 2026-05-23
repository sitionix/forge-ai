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

---

## Frontend Structure

- Follow the existing frontend module structure in the assigned scope.
- Preserve existing route, page, component, hook, client, state, and style conventions.
- Reuse existing mappers, hooks, clients, and UI primitives where they already fit the requested behavior.
- Keep implementation focused on the assigned scope and task.
- Avoid broad refactors unless the lane task explicitly requires them.

---

## API Context

- Treat API-generated frontend packages and client artifacts as source of truth when the task depends on API integration.
- Use the provided frontend dependency and evidence notes from the runtime input.
- Do not invent API operations, fields, hooks, clients, package names, or contract behavior.
- Do not repeat API artifacts in the completion payload.

---

## Code Quality

Before completion:

- Check changed files against these instructions.
- Keep route/page/component behavior consistent with the existing frontend style.
- Remove stale code related to the replaced frontend behavior.
- Avoid introducing duplicate UI logic in changed files.

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
