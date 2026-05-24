# Test IT Instructions

## Goal

Add or update backend integration tests for the assigned service scope.

The result of this lane is covered QA Lead integration test cases plus a successful `test-it` completion callback.

---

## Execution

- Work only inside the assigned backend scope.
- Use QA Lead `integrationTestCases` as the primary test target.
- Use Implement BE completion context as factual implementation context.
- Implement integration tests only for the assigned scope and relevant backend flows.
- Change only integration-test artifacts (`src/it`, `forge-it`, `*IT`); do not change unit-test artifacts (`src/test`, `*Test`).
- Keep changes aligned with existing service-local integration-test style.
- Reuse existing endpoint helpers, ForgeIT support interfaces, DB contracts, fixtures, and test utilities.
- If a QA case is duplicate, unsafe, impossible, or outside the assigned scope, do not pretend it was covered; report the exact reason in final output.
- After IT tests are updated, update the PR according to the provided PR workflow.
- After the PR workflow is satisfied, call the `test-it` completion endpoint.

---

## Integration Test Boundary

Integration tests verify observable behavior through real application boundaries.

Use integration tests for:

- boot-module HTTP/API flows;
- Spring request pipeline behavior;
- persistence effects;
- projection state;
- outbox/inbox behavior;
- Kafka producer/consumer behavior;
- real outbound HTTP dependency behavior through WireMock;
- service wiring that cannot be validated by unit tests.

Do not use IT tests for direct class-level behavior that belongs to unit tests.

Controller tests that directly instantiate controllers with Mockito are unit tests, not IT tests.

---

## ForgeIT Setup

Use the existing service-local ForgeIT setup.

Prefer the existing service-local support interface that extends `ForgeIT`.

Use `@IntegrationTest` for backend ITs unless the existing local test is an explicit infrastructure exception.

Autowire exactly one non-static ForgeIT support field per test class, following existing repository rules.

Use only the ForgeIT features already used by the target service unless the test case requires an additional supported feature.

Common ForgeIT entrypoints:

- `forgeit.mockMvc()` for HTTP/API flows;
- `forgeit.postgresql()` for DB setup and assertions;
- `forgeit.wiremock()` for outbound HTTP dependencies;
- `forgeit.kafka()` for Kafka/outbox/inbox flows when the service already uses it.

Do not create ad-hoc infrastructure setup when ForgeIT support already exists for the needed feature.

ForgeIT style is mandatory (no alternatives):

- Use `@IntegrationTest` on backend IT classes.
- Use service-local Forge support (`forgeit`/`testManager`) as the single entry point for infra features.
- Keep HTTP execution in Forge MockMvc DSL (`forgeit.mockMvc().ping(...)` and service endpoint contracts).
- Keep fixture-driven requests/responses in `src/test/resources/forge-it/**`.
- Use Forge PostgreSQL support for setup/verification (`forgeit.postgresql()`).
- Rely on Forge cleanup lifecycle (`@IntegrationTest` cleanup); do not add ad-hoc cleanup logic.

If an existing test is not in ForgeIT style, refactor it to ForgeIT style as part of the lane before reporting success.

Global composition rules:

- Keep `@Autowired` fields minimal: only one Forge entry point/manager per IT class.
- Do not autowire extra application beans in IT tests.
- Use mocks only for true external dependencies that cannot be validated through Forge runtime boundaries.
- Do not mock internal application collaborators when the flow is testable through ForgeIT.

---

## HTTP / MockMvc Flow

Drive HTTP integration flows through `forgeit.mockMvc().ping(...)`.

Prefer existing endpoint helper factories or constants from the service.

Use path params, query params, tokens, headers, request fixtures, response fixtures, and status expectations through the existing local helper style.

Use raw `MockMvc.perform(...)` only when that is already the local style for the specific test area.

For REST/API flows, assert:

- HTTP status;
- response body when relevant;
- error body for validation/auth/not-found/conflict cases;
- headers only when they are part of observable behavior;
- persistence, outbox, external call, or projection side effects when relevant.

Do not assert internal implementation details.
Do not introduce non-Forge HTTP execution style in new or updated tests.

Request execution discipline:

- Use a single `ping(...)` call for a test whenever one request is sufficient.
- Add additional `ping(...)` calls only when the scenario requires an explicit sequential workflow.
- Do not build setup through unnecessary HTTP chains when equivalent test data can be prepared directly via Forge PostgreSQL contracts/entities.

---

## PostgreSQL / Persistence

Use `forgeit.postgresql()` for DB setup and verification when persistence is part of the expected behavior.

Use existing service-local DB contracts.

Use contract graphs for setup when available.

Use lookup/reference contracts with the existing cleanup policy.

Use mutable business entities with the existing cleanup policy.

Seed only the minimum state required for the test case.

Verify DB state only when persistence or projection state is part of the QA case.

Use existing assertion style:

- contract/entity assertions;
- fetched entities;
- ignored dynamic fields for generated IDs, timestamps, hashes, relations, or metadata;
- relation fetching only when relation content is relevant.

Do not add manual cleanup when ForgeIT cleanup already handles the test lifecycle.
Do not bypass Forge PostgreSQL support with custom SQL/probing utilities when Forge contracts/entities cover the scenario.

---

## WireMock / External HTTP Dependencies

Use `forgeit.wiremock()` only for real outbound HTTP dependencies.

Use existing WireMock endpoint helpers and fixture conventions.

Create stubs before executing the system under test.

Verify outbound calls only when the flow depends on those calls.

Use default mappings when the service already uses default-driven WireMock style.

Override only the request, response, status, path params, query params, or delay needed by the scenario.

Do not use WireMock to replace internal service logic that should be tested through application behavior.

---

## Kafka / Outbox / Inbox

Use `forgeit.kafka()` and existing local helpers only when the assigned flow involves Kafka, outbox, inbox, event publishing, or event consuming.

For write flows, assert outbox rows when event publishing is part of the contract.

For consumer flows, publish the input event and verify projection/inbox state.

For worker flows, verify dispatch result and persisted state transitions.

Do not add Kafka assertions when the assigned flow does not involve event behavior.

---

## Fixtures

Use JSON fixtures under the service’s existing `src/test/resources/forge-it` layout.

Follow local folder and naming conventions.

Use default fixtures for reusable baseline payloads.

Use scenario-specific fixtures only when the scenario materially differs.

Prefer fixture mutation for small dynamic changes instead of duplicating large JSON files.

Keep request, response, DB entity, WireMock, Kafka payload, metadata, and expected fixtures in the same style as the target service.

Do not introduce a new fixture layout for one test.

---

## QA Case Mapping

Map QA Lead `integrationTestCases` into concrete IT tests.

Prefer one QA case per test method.

Use clear test names that reflect the covered QA case.

Each implemented case should verify the actual observable behavior described by the QA case.

Relevant case categories may include:

- happy path;
- validation;
- missing or invalid fields;
- authorization;
- ownership;
- lifecycle/status;
- not found;
- conflict or duplicate request;
- retry or idempotency;
- persistence/projection state;
- transaction consistency;
- external dependency failure;
- event flow;
- concurrency or ordering only when the flow truly requires it;
- regression risk.

Skip irrelevant categories.

Do not create generic checklist tests.

---

## Given / When / Then

Use clear test structure.

`given` prepares DB state, fixtures, WireMock stubs, Kafka input, tokens, headers, or runtime state.

`when` executes one observable flow.

`then` asserts the response and relevant durable side effects.

Keep each test focused on one behavior or risk.

Do not over-test implementation internals.

Verification discipline:

- Do not use `ArgumentCaptor` in IT tests.
- Prefer direct observable assertions (HTTP response, DB state, outbox/inbox/event side effects).
- When a mock is allowed, verify interactions directly without captors.

---

## Test Independence Rule

Integration tests are independent units.

- Keep setup and assertions inside each test method.
- Do not introduce private support/helper methods for scenario setup, request execution, or assertions.
- Do not extract reusable test logic/classes for IT flows.
- The only allowed reuse is endpoint/default contract reuse (for example `assertDefault`, `applyDefault`, and default fixture contracts).

---

## Negative Cases

For validation failures:

- mutate request fixtures to produce invalid input;
- assert status and concrete error response;
- assert no persistence or downstream side effect when relevant.

For auth/ownership failures:

- use missing/invalid token or user-context headers according to local style;
- assert `401` or `403`;
- assert no persistence or downstream forwarding when relevant.

For not-found/conflict cases:

- model the missing/conflicting state through DB fixtures, request fixtures, or WireMock response;
- assert status and error response;
- assert state remains consistent.

---

## Concurrency / Repeated Behavior

Add concurrency, ordering, retry, or repeated-call tests only when the QA case or assigned flow explicitly depends on that behavior.

Do not make concurrency tests a default baseline.

Keep them bounded and deterministic.

---

## Strict Prohibitions

- Do not start Spring contexts manually outside `@IntegrationTest`.
- Do not use MockMvc standalone setup/builders for IT lane tests.
- Do not introduce custom test harnesses that duplicate ForgeIT behavior.
- Do not mix unit-test style controller mocking into IT lane classes.

---

## Completion Callback

After IT tests are implemented and verified, call the provided `test-it` completion endpoint.

Build the request from the provided OpenAPI completion contract reference and runtime values.

The completion payload represents IT lane report facts only.

Expected semantic content:

- `scope` — assigned backend service scope;
- `summary` — short factual summary of completed IT work;
- `coveredCases` — list of QA/IT test case names covered by this lane.

`coveredCases` must be a list of strings.

One string equals one covered test case name.

Do not include test files, test commands, full QA Lead test-case objects, DB checks, fixtures, artifacts, operations, implementation handoff, or invented metrics.

---

## Completion Rule

Call completion only after:

- relevant QA Lead integration test cases were processed;
- IT tests were added or updated;
- PR workflow was completed;
- covered cases can be reported truthfully.

If a QA case was not covered, report the exact reason in final output and do not include it in `coveredCases`.

If completion cannot be submitted, report the exact failure in final output.
