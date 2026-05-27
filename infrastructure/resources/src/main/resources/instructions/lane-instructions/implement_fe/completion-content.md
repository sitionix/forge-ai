# Implement FE Completion Content

## Scope

Completion payload represents frontend implementation facts only.
Build final callback payload from the provided OpenAPI completion contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the OpenAPI completion contract supports them:

- `scope`;
- `summary`;
- `changedFiles`;
- `affectedSurfaces`;
- `uiBehavior`;
- `sonar`.

## `scope`

Use assigned frontend scope from runtime context.

## `summary`

Short factual summary of implemented frontend production behavior.
Mention only behavior implemented by this lane.

## `changedFiles`

List frontend source files changed by this lane.
Each changed file fact should explain why the file changed.
Include compatibility-only test updates only when the OpenAPI contract supports them and they were required by production code compatibility.
Do not report new test classes, new test files, or new test methods.

## `affectedSurfaces`

Describe user-facing or frontend-technical surfaces changed by this lane.
Use only surfaces supported by the OpenAPI completion contract.
Relevant surfaces may include:

- route;
- page;
- component;
- hook;
- client;
- state;
- mapper;
- style;
- package;
- API integration.

Each affected surface should contain:

- type;
- name;
- summary.

## `uiBehavior`

List user-visible behaviors implemented by this lane.
Use factual behavior descriptions.
Do not include:

- test results;
- reviewer notes;
- backend persistence details;
- duplicated API contract data;
- generated artifact lists already provided by API lane;
- invented Sonar metrics.

## `sonar`

Use the real SonarCloud result collected by the implementation Sonar gate.
Report only metrics supported by the OpenAPI completion contract.
For implement-fe, Sonar content is about changed frontend production code.
Do not include coverage unless the OpenAPI completion contract explicitly requires it.