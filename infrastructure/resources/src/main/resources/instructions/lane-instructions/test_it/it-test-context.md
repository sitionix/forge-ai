# Test IT Context

## Source

Use:

- lane task;
- runtime context;
- assigned backend scope;
- scope context;
- QA Lead `integrationTestCases`;
- Implement BE completion facts;
- repository evidence from the assigned backend service.

Use QA Lead `integrationTestCases` as the primary test target.
Use Implement BE completion facts as factual implementation context.
Use runtime context as source of truth for assigned scope, ticket, lane, contract references, and callback references.

## Scope

Work inside the assigned backend service scope.

Identify:

- backend service path;
- relevant backend flow;
- QA Lead integration cases for this scope;
- changed implementation facts from Implement BE;
- affected HTTP/API, persistence, projection, outbox/inbox, Kafka, or external HTTP behavior;
- existing ForgeIT support interface;
- existing endpoint helpers;
- existing DB contracts;
- existing fixtures;
- existing WireMock helpers;
- existing Kafka helpers;
- existing local IT style.

## Test Target

Map QA Lead `integrationTestCases` into concrete backend integration tests.
Prefer one QA case per test method.
If a QA case is duplicate, already covered, unsafe, impossible, or outside the assigned backend scope, keep the exact reason for completion content.
Only truthfully covered cases may appear in `coveredCases`.

## Artifact Boundary

Change only backend integration-test artifacts.
Allowed integration-test locations and names include local repository conventions such as:

- `src/it`;
- `forge-it`;
- `*IT`.

Do not change unit-test artifacts such as:

- `src/test`;
- `*Test`.

Do not change production code from this lane.

## Result

Keep these facts for later steps:

- assigned backend scope;
- QA cases selected for IT implementation;
- QA cases skipped with exact reasons;
- changed IT files;
- covered case names;
- local verification command.