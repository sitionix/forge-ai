# Implement BE Completion Content

Use only lane facts that match fields supported by the provided completion contract.

## Allowed Content

Use only these semantic groups when supported:

- `scope`;
- `summary`;
- `changedFiles`;
- `integrationFlows`;
- `persistenceChanges`;
- `sonar`.

## Field Semantics

- `scope`: assigned backend service scope from runtime context.
- `summary`: short factual summary of implemented backend production behavior.
- `changedFiles`: backend production files changed by this lane; include compatibility-only test updates only when contract supports them.
- `integrationFlows`: factual backend runtime flows affected by this lane.
- `persistenceChanges`: persistence facts only when persistence changed.
- `sonar`: aggregated Sonar result from implementation Sonar gate;

Implementation lanes never use coverage as a gate, decision, failure reason, or work item.
If coverage-like fields exist in the completion contract, they must not influence implementation behavior.
Do not add/modify tests for coverage.

Sonar failure handling flow for `implement_be`:
- Sonar fails -> parse failed conditions.
- If issue condition fails -> fix code in changed production files and rerun Sonar.
- If coverage condition fails -> document coverage condition and continue implement-be completion path.

## Self-Review Loop (Mandatory)

Before commit and again before completion callback, run a self-review loop and do not stop on fixable gaps.

Loop:
1. run style/quality checks for changed production files;
2. if any fixable gap is found, fix it immediately;
3. rerun the same checks;
4. repeat until all checks are `PASS`.

Rules:
- Do not finish lane work while self-review has fixable failures.
- Do not ask user what to do for fixable style/quality gaps owned by this lane.
- Do not send completion callback until the latest self-review loop is fully `PASS`.
- Do not pass without all strategy steps(except callback)  fully completed, need evidence about style/pr/Sonar issues fixes.

## Boundary

Do not include QA plans, test reports, reviewer notes, unrelated downstream work, or invented metrics.
