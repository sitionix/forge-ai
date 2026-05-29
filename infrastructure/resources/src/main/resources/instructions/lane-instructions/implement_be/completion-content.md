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

## Boundary

Do not include QA plans, test reports, reviewer notes, unrelated downstream work, or invented metrics.
