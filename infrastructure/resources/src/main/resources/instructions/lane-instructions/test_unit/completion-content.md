# Test Unit Completion Content

## Scope

Completion payload represents test-unit lane facts only.
If sonar coverage is less then 90%, you need to cover more to satisfy sonar

## Allowed Content

Use only these content groups when supported by the OpenAPI completion contract:

- `scope`;
- `summary`;
- `affectedFiles`;
- `sonar`.

## Field Semantics

- `scope`: assigned backend service scope from runtime context.
- `summary`: short factual unit-test work summary.
- `affectedFiles`: affected backend source files covered or checked by this lane (source files only, never test files).
- `sonar`: one aggregated lane Sonar object from real SonarCloud result.

## Forbidden Content

Do not include integration-test or UI-test reports, production implementation handoff, reviewer status, unrelated PR commentary, or invented metrics.