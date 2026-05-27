# Test UI Completion Content

## Scope

Completion payload represents UI-test lane facts only.
Build final callback payload from the provided OpenAPI completion contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the OpenAPI completion contract supports them:

- `scope`;
- `summary`;
- `coveredCases`;
- `sonar`.

## `scope`

Use assigned frontend SPA scope from runtime context.

## `summary`

Short factual summary of completed UI-test work.
Mention only UI-test work completed by this lane.

## `coveredCases`

List UI behavior cases validated in this lane.
Each covered case should describe:

- tested UI behavior;
- relevant route, page, component, or user interaction when known;
- scenario kind;
- expected visible result;
- source QA case when available.

Use QA Lead case wording when it maps cleanly to implemented tests.
For skipped QA cases, include only if the OpenAPI completion contract supports skipped or notes fields.
Do not pretend skipped, duplicate, unsafe, impossible, or out-of-scope cases were covered.

## `sonar`

Use the real SonarCloud result collected by the test coverage Sonar gate.
`sonar` is one aggregated object for the lane, not per file.
Report only metrics supported by the OpenAPI completion contract.
For test-ui, Sonar content is about changed UI-test code and coverage.

## Boundary

Do not include:

- backend details;
- backend integration-test reports;
- unit-test reports;
- reviewer status;
- implementation handoff;
- PR commentary unrelated to completion fields;
- invented metrics;
- generated artifact lists already reported by API lane.