# Test Unit Implementation

## Unit Test Scope

Unit tests must isolate the class under test.
Mock injected collaborators and external dependencies.
Use real objects for:

- commands;
- DTOs;
- value objects;
- domain objects;
- config objects;
- expected results;

when their fields are part of the assertion.
Allow mocks for passive transport objects in controller or adapter unit tests when the object itself is not the subject of the test.
Do not use Spring context bootstrapping for unit tests.
Do not use MockMvc in unit tests.
MockMvc belongs to integration tests.

## Test Structure

Use the repository’s existing unit-test style.

Default backend unit-test style:

- JUnit 5;
- Mockito extension;
- AssertJ assertions;
- direct SUT construction in `@BeforeEach`;
- `// given`, `// when`, `// then` sections;
- `verifyNoMoreInteractions(...)` in `@AfterEach` when the existing test style uses it.

Each test verifies observable behavior.
Verify collaborator interactions only when they are behavior-relevant.
Use `verifyNoInteractions(...)` for negative paths where skipped collaborators are part of expected behavior.
Use `catchThrowable(...)` or `assertThatThrownBy(...)` for exception paths, matching the existing service style.

## Fixture And Builder Rules

Build test data through private helper methods.
Prefer one canonical helper method per object type.
Put stable/default values inside the helper method.
Pass only dynamic values as helper method arguments.
Do not repeat large object construction blocks across test methods.
Avoid mutable shared fixture state for expected values.
Use explicit helper parameters instead of mutating class fields to build expected state.

## Use Case And Service Tests

For use cases, services, validators, and security/application components:

- mock external collaborators;
- build real request, command, domain, and result data when values matter;
- call the SUT directly;
- assert returned result or thrown exception;
- capture internally created objects only when their content is part of observable behavior;
- verify important interactions;
- avoid asserting private implementation details.

Typical collaborators to mock:

- repositories;
- clients;
- producers;
- consumers;
- external services;
- token/hash services;
- authentication manager;
- clock or id providers;
- mapper dependencies when testing a class that depends on a mapper.

## Controller Unit Tests

Controller unit tests call controller methods directly.
Mock controller dependencies such as:

- use cases;
- mappers;
- request/context objects;
- passive transport objects when needed.

Verify:

- delegation;
- mapped input;
- mapped output;
- response shape at method level.

HTTP wiring belongs to integration tests.
Do not use MockMvc.
Do not start Spring context.

## Mapper Unit Tests

Mapper tests use this shape:

1. build given source object;
2. build expected target object;
3. call mapper;
4. compare actual result with expected result.

Instantiate mapper implementation directly in `@BeforeEach`.
Mock nested mapper dependencies only when the mapper under test depends on another mapper or collaborator.
Prefer full object comparison.
Use recursive comparison only when equality is unavailable or not meaningful for the target object.
Do not turn mapper tests into long manual field-by-field assertion scripts unless there is no reliable object comparison option.

## Generated Artifacts And Contracts

Use already-provided generated DTOs, clients, and contract types when tests require them.
Do not generate contracts.
Do not create replacement DTOs or ad-hoc test shapes.
Do not invent fields, enum values, endpoint paths, payloads, topics, or artifact coordinates.
If a generated artifact is unavailable or conflicts with the affected code, keep exact evidence for completion content.

## Case Selection

Cover behavior affected by changed source files.
Relevant unit-test cases may include:

- happy path;
- validation branch;
- exception path;
- skipped collaborator path;
- authorization or ownership check;
- state transition;
- mapper transformation;
- repository/client/producer interaction;
- null or empty input behavior when the production code handles it;
- boundary value;
- regression-prone branch.

Skip irrelevant categories.
Do not create generic checklist tests.

## Naming

Use clear test names that describe the behavior under test.
Prefer names based on observable behavior.
Avoid names based only on private implementation details.

## Test Artifacts

Change only unit-test artifacts.

Allowed unit-test locations and names include local repository conventions such as:

- `src/test`;
- `*Test`.

Do not change integration-test artifacts such as:

- `src/it`;
- `forge-it`;
- `*IT`.

Do not change production code from this lane.