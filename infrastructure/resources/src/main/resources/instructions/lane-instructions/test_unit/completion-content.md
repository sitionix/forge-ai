# Test Unit Completion Content

## Scope

Completion payload represents unit-test lane facts only.
Build final callback payload from the provided OpenAPI completion contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the OpenAPI completion contract supports them:

- `scope`;
- `summary`;
- `affectedFiles`;
- `sonar`.

## `scope`

Use assigned backend service scope from runtime context.

## `summary`

Short factual summary of completed unit-test work.
Mention only unit-test work completed by this lane.

## `affectedFiles`

List affected backend source files covered or checked by this lane.
`affectedFiles` contains source files, not test files.
For each affected source file, include the unit-test outcome when the OpenAPI contract supports it:

- covered by new or updated unit tests;
- already covered by existing unit tests;
- checked and not suitable for unit testing with exact reason.

Do not report integration-test files.
Do not report UI-test files.
Do not report reviewer notes.

## `sonar`

Use the real SonarCloud result collected by the test coverage Sonar gate.
`sonar` is one aggregated object for the lane, not per file.
Report only metrics supported by the OpenAPI completion contract.
For test-unit, Sonar content is about changed unit-test code and coverage.

## Boundary

Do not include:

- integration-test reports;
- UI-test reports;
- backend implementation handoff;
- reviewer status;
- PR commentary unrelated to completion fields;
- invented metrics;
- generated artifact lists already reported by API lane.