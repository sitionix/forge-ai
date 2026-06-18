# IT Fixtures

## Layout

Use JSON fixtures under the service’s existing ForgeIT resources layout:

`src/test/resources/forge-it/**`

Follow local folder and naming conventions.
Use default fixtures for reusable baseline payloads.
Use scenario-specific fixtures only when the scenario materially differs.
Prefer fixture mutation for small dynamic changes instead of duplicating large JSON files.

## Consistency

Keep these fixture types in the same style as the target service:

- request fixtures;
- response fixtures;
- DB entity fixtures;
- WireMock fixtures;
- Kafka payload fixtures;
- metadata fixtures;
- expected result fixtures.

Do not introduce a new fixture layout for one test.
Do not duplicate large fixtures when local mutation helpers or default contracts already exist.