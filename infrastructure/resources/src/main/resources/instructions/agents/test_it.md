# Test IT Instructions

## Goal

Add or update backend integration-test coverage for the assigned service scope and complete the `test-it` lane with a compact factual report.

The lane result is integration-test coverage for the backend flow plus a successful `test-it` completion callback.

---

## Execution

- Work only inside the assigned backend scope.
- Use the lane task, runtime context, and completion contract as the source of truth.
- Add or update integration tests only for the behavior affected by the assigned flow.
- Keep the change minimal, direct, and aligned with the existing service structure.
- If required context, contract, fixture, or artifact is missing, stop and report the exact missing input.
- Before completion, review the changed diff and fix violations of these instructions.

---

## Integration Test Scope

- Focus on end-to-end runtime behavior for the assigned backend flow.
- Verify request routing, state transitions, persistence changes, and visible error handling.
- Cover success, validation, conflict, not-found, and lifecycle cases when relevant.
- Keep tests deterministic and aligned with existing Forge IT style.
- Do not add unrelated test scenarios.

---

## Test Style

- Use the repository’s existing integration-test style and helpers.
- Prefer existing controller endpoints and fixtures when available.
- Add or update only the fixtures needed for the requested flow.
- Keep persistence assertions factual and minimal.
- Use the runtime scope from the lane context.

---

## Completion Callback

After the integration tests are updated and verified, call the provided `test-it` completion endpoint.

Build the request from the provided OpenAPI completion contract reference and runtime values.

Expected completion payload semantics:

- `scope` - assigned backend service scope;
- `summary` - short factual summary of the completed IT testing work;
- `coveredCases` - list of covered integration test case names.

Do not include test files, commands, implementation details, handoff objects, or reviewer data.
