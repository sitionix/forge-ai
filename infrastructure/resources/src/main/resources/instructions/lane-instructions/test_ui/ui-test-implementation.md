# Test UI Implementation

## SPA Stack Boundary

Use only the existing SPA language, test framework, assertion style, helpers, fixtures, mocks, and test utilities already used in the assigned SPA scope.
Do not introduce a new language, test runner, assertion library, UI test framework, backend test framework, database test setup, messaging test setup, or service-internal integration behavior.

## UI Test Scope

Validate user-visible frontend behavior for the assigned scope.
Relevant observable outcomes include:

- render state;
- user interaction result;
- validation feedback;
- loading state;
- empty state;
- error state;
- route-level behavior;
- visible API integration behavior;
- disabled/enabled UI state;
- modal, sheet, menu, tab, or selected-state behavior when relevant.

Do not test private implementation details.
Do not move into backend ownership.
Keep tests deterministic and avoid flaky timing assumptions.

## Test Structure

Use repository-local frontend test style.
Keep tests behavior-focused:

- `given` prepares render input, mocks, fixtures, route state, user state, or component props;
- `when` performs one user interaction or one observable UI flow;
- `then` asserts visible result and behavior-relevant effects.

Each test verifies one clear behavior.
Verify collaborator interactions only when needed for observable UI behavior.
Use explicit negative-path assertions for error and validation paths.

## Fixtures And Helpers

Build test data using existing SPA helpers and fixtures.
Prefer one canonical helper per fixture or object type.
Keep stable defaults inside helper definitions.
Pass only dynamic values as helper arguments.
Do not duplicate large setup blocks across tests.
Avoid mutable shared fixture state.
Use existing mock/server/client setup style from the same SPA module.

## Generated Artifacts

If the SPA already uses generated frontend artifacts or contract types, use those existing artifacts.
Do not generate contracts.
Do not invent API fields, payload structures, endpoint paths, enum values, operation names, hook names, package names, or package coordinates.
If required generated artifacts are unavailable or conflict with the tested flow, keep exact evidence for completion content.

## Case Mapping

Map QA Lead UI cases to concrete tests.
Relevant case categories may include:

- happy path;
- validation feedback;
- missing or invalid input;
- loading state;
- empty state;
- error state;
- route behavior;
- authorization-visible behavior;
- ownership-visible behavior;
- API failure visible to user;
- regression-prone UI behavior.

Skip irrelevant categories.
Do not create generic checklist tests.

## Naming

Use clear test names that describe user-visible behavior.
Prefer names based on what the user sees or does.
Avoid names based only on implementation internals.