# Test IT Completion Content

## Scope

Completion payload represents IT lane report facts only.
Build final completion payload from the provided final-step completion payload contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the final-step completion payload contract supports them:

- `scope`;
- `summary`;
- `coveredCases`.

## `scope`

Use assigned backend service scope from runtime context.

## `summary`

Short factual summary of completed integration-test work.
Mention only IT work completed by this lane.

## `coveredCases`

List QA/IT test case names covered by this lane.
`coveredCases` must be a list of strings.
One string equals one covered test case name.
Include only truthfully covered cases.
If a QA case was skipped, duplicate, unsafe, impossible, or outside assigned scope, do not include it in `coveredCases`.
Keep skipped-case reasons for final output when needed.

## Boundary

Do not include:

- test files;
- test commands;
- full QA Lead test-case objects;
- DB checks;
- fixtures;
- artifacts;
- operations;
- implementation handoff;
- unit-test reports;
- UI-test reports;
- reviewer status;
- invented metrics.
