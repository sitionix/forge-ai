# QA Lead Test Case Design

## Work

Create downstream test cases for the assigned scope.
Each case covers one concrete behavior or risk.
Cover the flow deeper than happy path
Use relevant case categories:

- happy path;
- validation;
- missing fields;
- invalid fields;
- not found;
- authorization;
- ownership;
- lifecycle or status;
- conflict or duplicate request;
- retry or idempotency;
- persistence state;
- projection state;
- transaction consistency;
- external dependency failure;
- event flow;
- concurrency or ordering;
- boundary values;
- user misuse;
- regression risk.

Skip irrelevant categories.

## Backend Scope Cases

For backend scopes, design integration/API/backend behavior cases for the downstream IT lane.

Cases may cover:

- REST/API observable behavior;
- persistence state;
- projection state;
- outbox/inbox behavior;
- Kafka producer or consumer behavior;
- external HTTP dependency behavior;
- transaction consistency;
- authorization and ownership.

## Frontend Scope Cases

For frontend scopes, design UI behavior cases for the downstream UI test lane.

Cases may cover:

- render state;
- user interaction result;
- validation feedback;
- loading state;
- empty state;
- error state;
- route-level behavior;
- API integration behavior visible to the user;
- regression-prone UI behavior.

## Case Fields

Each case must define:

- tested flow;
- case kind;
- given;
- when;
- then;
- data checks when relevant;
- priority.

## Case Kind

Assign one kind per case.
Allowed values:

- `HAPPY_PATH`
- `VALIDATION`
- `AUTHORIZATION`
- `OWNERSHIP`
- `LIFECYCLE`
- `NOT_FOUND`
- `CONFLICT`
- `IDEMPOTENCY`
- `PERSISTENCE`
- `EXTERNAL_DEPENDENCY`
- `EVENT_FLOW`
- `CONCURRENCY`
- `EDGE_CASE`
- `REGRESSION`

## Priority

Allowed values:

- `HIGH`
- `MEDIUM`
- `LOW`

Use `HIGH` for:

- core happy path;
- critical failure path;
- authorization or ownership behavior;
- consistency behavior;
- lifecycle behavior;
- contract-critical behavior;
- regression-prone behavior.

Use `MEDIUM` for useful alternative or negative coverage.
Use `LOW` for secondary edge cases.

## Flow Identification

For REST/API flows, use method and path from contract or runtime context.
Use operation id only when it exists in provided context.
For event flows, use event/topic/payload names only when they exist in provided context.
For UI flows, use route/page/component names only when they exist in runtime context, analyzer context, or repository evidence.

## Given / When / Then

`given` describes preconditions.
`when` describes the tested action.
`then` describes observable expected results.

Use data checks only for relevant persistence, projection, event, or UI state.
Data checks describe expected state, not test implementation code.

## Boundary

Do not write test code.
Do not write implementation steps.
Do not invent operation ids, endpoints, events, fields, payload shapes, routes, components, or test files.