# Test Lane Boundary

## Scope

Use this file for lanes that own test artifacts.
Test lanes own test code and test verification for their assigned scope.
Test lanes do not own production implementation.

## Work

Add or update tests only for behavior affected by the assigned lane task.
Keep changes:

- assigned-scope only;
- test-artifact only;
- minimal;
- behavior-focused;
- aligned with existing local test style.

Use upstream implementation completion facts as factual context.
Use QA Lead cases when the lane receives them.
Use repository-local test patterns, helpers, fixtures, and commands.

## Production Code Boundary

Do not change production code.
Do not patch production behavior from a test lane.
Do not add generated contracts, DTOs, clients, hooks, or backend payload shapes from a test lane.
When production behavior is missing or incorrect, keep the exact fact for completion context instead of fixing production code.

## Test Quality

Each test should validate one clear behavior or risk.
Prefer observable behavior over private implementation details.
Keep setup readable.
Avoid large duplicated setup blocks.
Avoid mutable shared fixture state.
Keep tests deterministic.
Avoid flaky timing assumptions.

## Diff Review

Before local verification:

- review changed files;
- remove unrelated changes;
- remove stale or duplicate test code;
- keep test names clear;
- keep assertions behavior-relevant;
- keep helpers and fixtures consistent with local style.