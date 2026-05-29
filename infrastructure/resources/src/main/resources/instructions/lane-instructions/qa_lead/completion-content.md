# QA Lead Completion Content

## Scope

Completion payload represents QA Lead planning facts only.
Build final callback payload from the provided OpenAPI completion contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the OpenAPI completion contract supports them:

- `scope`;
- `summary`;
- `integrationTestCases`;
- `unitTestNotes`.

If the OpenAPI completion contract provides frontend-specific or UI-specific test-case fields, use those fields for frontend scope cases.
Do not invent completion fields.

## `scope`

Use assigned scope from runtime context.

## `summary`

Short factual summary of QA focus for the assigned scope.
Mention only QA context produced by this lane.

## Boundary

Completion content is not test code.
Completion content is not implementation work.
Completion content is not PR/Sonar evidence.
Completion content is not reviewer feedback.
Completion content must not include invented endpoints, fields, operations, events, routes, components, or test files.