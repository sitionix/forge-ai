# IT Case Implementation

## QA Case Mapping

Map QA Lead `integrationTestCases` into concrete IT tests.
Prefer one QA case per test method.
Use clear test names that reflect the covered QA case.
Each implemented case must verify the actual observable behavior described by the QA case.
Relevant case categories may include:

- happy path;
- validation;
- missing or invalid fields;
- authorization;
- ownership;
- lifecycle or status;
- not found;
- conflict or duplicate request;
- retry or idempotency;
- persistence or projection state;
- transaction consistency;
- external dependency failure;
- event flow;
- concurrency or ordering only when the flow truly requires it;
- regression risk.

Skip irrelevant categories.
Do not create generic checklist tests.

## Given / When / Then

Use clear test structure.
`given` prepares DB state, fixtures, WireMock stubs, Kafka input, tokens, headers, or runtime state.
`when` executes one observable flow.
`then` asserts response and relevant durable side effects.
Keep each test focused on one behavior or risk.
Prefer direct observable assertions:

- HTTP response;
- DB state;
- outbox state;
- inbox state;
- Kafka/event side effects;
- WireMock outbound call evidence;
- projection state.

Do not over-test implementation internals.
Do not use `ArgumentCaptor` in IT tests.
When a mock is allowed, verify interactions directly without captors.

## Test Independence

Integration tests are independent units.
Keep setup and assertions inside each test method.
Do not introduce private support/helper methods for scenario setup, request execution, or assertions.
Do not extract reusable test logic or reusable test classes for IT flows.
Allowed reuse:

- endpoint/default contract reuse;
- existing `assertDefault`;
- existing `applyDefault`;
- existing default fixture contracts;
- existing local ForgeIT helper style.

## Negative Cases

For validation failures:

- mutate request fixtures to produce invalid input;
- assert status;
- assert concrete error response;
- assert no persistence or downstream side effect when relevant.

For auth or ownership failures:

- use missing or invalid token;
- use user-context headers according to local style;
- assert `401` or `403`;
- assert no persistence or downstream forwarding when relevant.

For not-found or conflict cases:

- model the missing or conflicting state through DB fixtures, request fixtures, or WireMock response;
- assert status;
- assert error response;
- assert state remains consistent.

## Concurrency And Repeated Behavior

Add concurrency, ordering, retry, or repeated-call tests only when the QA case or assigned flow explicitly depends on that behavior.
Do not make concurrency tests a default baseline.
Keep concurrency and ordering tests bounded and deterministic.

## Strict Prohibitions

Do not start Spring contexts manually outside `@IntegrationTest`.
Do not use MockMvc standalone setup or builders for IT lane tests.
Do not introduce custom test harnesses that duplicate ForgeIT behavior.
Do not mix unit-test controller mocking into IT lane classes.