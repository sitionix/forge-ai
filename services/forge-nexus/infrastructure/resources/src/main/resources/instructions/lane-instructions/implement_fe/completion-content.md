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

Sonar failure handling flow for `implement_fe`:
- Sonar fails -> parse failed conditions.
- If issue condition fails -> fix code in changed production files and rerun Sonar.
- If coverage condition fails -> document coverage condition and continue implement-fe completion path.

## Self-Review Loop (Mandatory)

Before commit and again before the final completion response, run a self-review loop and do not stop on fixable gaps.

Loop:
1. run style/quality checks for changed production files;
2. if any fixable gap is found, fix it immediately;
3. rerun the same checks;
4. repeat until all checks are `PASS`.

Rules:
- Do not finish lane work while self-review has fixable failures.
- Do not ask user what to do for fixable style/quality gaps owned by this lane.
- Do not finish the completion step until the latest self-review loop is fully `PASS`.
- Do not pass without all strategy steps fully completed; include evidence about style, PR, and Sonar issue fixes.


## Boundary

Do not include test results, reviewer notes, backend persistence details, unrelated PR commentary, duplicated API contract data, or invented metrics.
