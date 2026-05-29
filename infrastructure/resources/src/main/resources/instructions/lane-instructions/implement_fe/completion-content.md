# Implement FE Completion Content

Use only lane facts that match fields supported by the provided completion contract.

## Allowed Content

Use only these semantic groups when supported:

- `scope`;
- `summary`;
- `changedFiles`;
- `affectedSurfaces`;
- `uiBehavior`;
- `sonar`.

## Field Semantics

- `scope`: assigned frontend scope from runtime context.
- `summary`: short factual summary of implemented frontend production behavior.
- `changedFiles`: frontend source files changed by this lane (compatibility-only test updates only when contract supports them).
- `affectedSurfaces`: user-facing or frontend-technical surfaces changed by this lane.
- `uiBehavior`: factual user-visible behaviors implemented by this lane.
- `sonar`: aggregated Sonar result from implementation Sonar gate;

Implementation lanes never use coverage as a gate, decision, failure reason, or work item.
If coverage-like fields exist in the completion contract, they must not influence implementation behavior.
Do not add/modify tests for coverage.

## Boundary

Do not include test results, reviewer notes, backend persistence details, unrelated PR commentary, duplicated API contract data, or invented metrics.
