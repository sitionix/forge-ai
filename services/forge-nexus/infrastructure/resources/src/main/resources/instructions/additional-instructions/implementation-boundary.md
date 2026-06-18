# Implementation Boundary

## Scope

Use this file for production implementation lanes.
Implementation lanes own production code changes for their assigned scope.
Implementation lanes do not own new behavior-validation tests.

## Work

Implement only the requested production behavior.

Keep changes:

- assigned-scope only;
- minimal;
- direct;
- aligned with existing repository structure;
- compatible with provided contracts and generated artifacts.

If the requested behavior already exists, avoid unnecessary code changes.

## Test Boundary

Implementation lanes do not add new test classes or new test methods even task requires tests.
Implementation lanes may update existing tests only when required for compatibility with changed production code.
When behavior validation requires new tests, preserve the need for the appropriate test lane.

## Diff Review

Before local verification:

- review changed files;
- remove unrelated changes;
- remove stale code related to the replaced production flow;
- keep changed code consistent with local style;
- keep new duplication low;
- preserve existing behavior unless the lane task explicitly requires a behavior change.