# Test UI Implementation

## SPA Stack Boundary

Use only the existing SPA language, test framework, assertion style, helpers, fixtures, mocks, and test utilities already used in the assigned scope.
Do not introduce a new language, test runner, assertion library, or UI test framework.
Do not add backend behavior, database behavior, messaging behavior, or service-internal integration behavior.

## UI Test Scope

Validate user-visible frontend behavior for the assigned scope, including when relevant:

- render state;
- user interaction result;
- validation feedback;
- loading/empty/error state;
- route-level behavior;
- visible API integration behavior;
- disabled/enabled UI state;
- modal/sheet/menu/tab/selected-state behavior.

Do not test private implementation details.

## Test Structure

Use repository-local frontend test style.

- `given`: prepare render input, mocks, fixtures, route state, user state, or props;
- `when`: perform one user interaction or one observable UI flow;
- `then`: assert visible result and behavior-relevant effects.

Each test verifies one clear behavior.
Use explicit negative-path assertions for error/validation paths.

## Fixtures And Helpers

Use existing SPA helpers/fixtures/mocks.
Prefer one canonical helper per fixture/object type.
Keep defaults in helper definitions and pass only dynamic arguments.

## Generated Artifacts

When the SPA already uses generated frontend artifacts or contract types, use those existing artifacts.
Do not generate contracts.
Do not invent API fields, payload structures, endpoint paths, enum values, operation names, hook names, package names, or package coordinates.
If required generated artifacts are unavailable or conflicting, keep exact evidence for completion content.

## Case Mapping

Map QA Lead UI cases to concrete tests.
Do not report skipped, duplicate, unsafe, impossible, or out-of-scope cases as covered.
Skip irrelevant categories.

## Naming

Use clear test names based on user-visible behavior.
