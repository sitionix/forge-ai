# IT HTTP / MockMvc Flow

## Flow

Drive HTTP integration flows through:
`forgeit.mockMvc().ping(...)`
Prefer existing endpoint helper factories, endpoint contracts, constants, and local request/response fixture style.
Use raw `MockMvc.perform(...)` only when that is already the local style for the specific test area.

## Inputs

Use local style for:

- path params;
- query params;
- tokens;
- headers;
- request fixtures;
- response fixtures;
- status expectations.

## Assertions

For REST/API flows, assert:

- HTTP status;
- response body when relevant;
- error body for validation, auth, not-found, or conflict cases;
- headers only when they are part of observable behavior;
- persistence side effects when relevant;
- outbox/inbox side effects when relevant;
- external call side effects when relevant;
- projection side effects when relevant.

Do not assert internal implementation details.

## Request Discipline

Use a single `ping(...)` call when one request is sufficient.
Add additional `ping(...)` calls only when the scenario requires an explicit sequential workflow.
Do not build setup through unnecessary HTTP chains when equivalent test data can be prepared directly through Forge PostgreSQL contracts or entities.