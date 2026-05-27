# Implement BE Completion Content

## Scope

Completion payload represents backend implementation facts only.
Build final callback payload from the provided OpenAPI completion contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the OpenAPI completion contract supports them:

- `scope`;
- `summary`;
- `changedFiles`;
- `integrationFlows`;
- `persistenceChanges`;
- `sonar`.

## `scope`

Use assigned backend service scope from runtime context.

## `summary`

Short factual summary of implemented backend production behavior.
Mention only behavior implemented by this lane.

## `changedFiles`

List backend production files changed by this lane.
Each changed file fact should explain why the file changed.
Include compatibility-only test updates only when the OpenAPI contract supports them and they were required by production code compatibility.
Do not report new test classes or new test methods.

## `integrationFlows`

Describe backend runtime flows affected by this lane.

Use only factual changed-flow information, such as:

- REST controller flow;
- application/use-case flow;
- domain flow;
- persistence flow;
- outbound client flow;
- event producer flow;
- event consumer flow.

Do not include QA plans, test reports, reviewer notes, or unrelated downstream work.

## `persistenceChanges`

Report persistence facts only when production persistence changed.

Relevant facts may include:

- entity change;
- repository change;
- migration change;
- persistence mapper change;
- state or enum persistence change.

Use an empty or omitted value according to the OpenAPI contract when no persistence changed.

## `sonar`

Use the real SonarCloud result collected by the implementation Sonar gate.
Report only metrics supported by the OpenAPI completion contract.
Do not include coverage unless the OpenAPI completion contract explicitly requires it.