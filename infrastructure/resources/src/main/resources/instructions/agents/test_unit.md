# Test Unit Instructions

## Goal

Add or update backend unit tests for affected source files in the assigned service scope.

The result of this lane is unit-test coverage for affected files plus a successful `test-unit` completion callback.

---

## Execution

- Work only inside the assigned backend scope.
- Use affected source files from runtime context as the primary test target.
- Add or update unit tests only for behavior affected by those files.
- Keep changes minimal and aligned with existing test style.
- Process affected files file by file.
- Reuse existing test patterns from the same service/module when available.
- If required context, source file, contract type, generated DTO, or test dependency is missing, stop and report the exact missing input.
- After tests are updated, update the PR according to the provided PR workflow.
- After PR update, wait for SonarCloud result.
- Use only SonarCloud output for completion metrics.

---

## Unit Test Scope

Unit tests must isolate the class under test.

Mock injected collaborators and external dependencies.

Use real objects for commands, DTOs, value objects, domain objects, config objects, and expected results when their fields are part of the assertion.

Allow mocks for passive transport objects in controller or adapter unit tests when the object itself is not the subject of the test.

Do not use Spring context bootstrapping for unit tests.

Do not use MockMvc in unit tests.

MockMvc belongs to integration tests.

---

## Test Structure

Use the repository’s existing unit-test style:

- JUnit 5;
- Mockito extension;
- AssertJ assertions;
- direct SUT construction in `@BeforeEach`;
- `// given`, `// when`, `// then` sections;
- `verifyNoMoreInteractions(...)` in `@AfterEach` when the existing test style uses it.

Each test should verify observable behavior.

Verify collaborator interactions only when they are behavior-relevant.

Use `verifyNoInteractions(...)` for negative paths where skipped collaborators are part of expected behavior.

Use `catchThrowable(...)` or `assertThatThrownBy(...)` for exception paths, matching the existing service style.

---

## Fixture And Builder Rules

Build test data through private helper methods.

Prefer one canonical helper method per object type.

Put stable/default values inside the helper method.

Pass only dynamic values as helper method arguments.

Do not repeat large object construction blocks across test methods.

Avoid mutable shared fixture state for expected values.

Use explicit helper parameters instead of mutating class fields to build expected state.

---

## Use Case And Service Tests

For use cases, services, validators, and security/application components:

- mock external collaborators;
- build real request/command/domain data when values matter;
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

---

## Controller Unit Tests

Controller unit tests call controller methods directly.

Mock controller dependencies such as use cases, mappers, request/context objects, or passive transport objects when needed.

Do not use MockMvc.

Do not start Spring context.

Controller unit tests should verify delegation, mapping result, and response shape at method level.

HTTP wiring belongs to integration tests.

---

## Mapper Unit Tests

Mapper tests should use this shape:

1. build given source object;
2. build expected target object;
3. call mapper;
4. compare actual result with expected result.

Instantiate mapper implementation directly in `@BeforeEach`.

Mock nested mapper dependencies only when the mapper under test depends on another mapper/collaborator.

Prefer full object comparison.

Use recursive comparison only when equality is unavailable or not meaningful for the target object.

Do not turn mapper tests into long manual field-by-field assertion scripts unless there is no reliable object comparison option.

---

## Generated Artifacts And Contracts In Tests

Use already-provided generated DTOs, clients, and contract types when tests require them.

Do not generate contracts.

Do not create replacement DTOs or ad-hoc test shapes.

Do not invent fields, enum values, endpoint paths, payloads, topics, or artifact coordinates.

If a generated artifact is unavailable or conflicts with the affected code, stop and report the exact issue.

---

## Sonar And Quality Gate

Before completion, changed test code must be clean enough for the lane.

Do not finish with serious new Sonar issues in changed test code.

Unacceptable issues include:

- security issues;
- unused imports;
- unused variables;
- dead code;
- broken assertions;
- obvious null bugs;
- flaky tests;
- duplicated large setup blocks;
- unreadable test structure;
- accidental extra interactions left unverified.

Only minor style-level issues may be tolerated when they are harmless and consistent with local style, for example generic type naming nits.

Coverage is a hard gate.

`sonar.coveragePercent` must be at least `90.0`.

If coverage is below `90.0`, add or improve unit tests and wait for a new SonarCloud result.

Do not invent Sonar numbers.

Completion metrics must be copied from SonarCloud output only.

---

## Completion Callback

After unit-test work is complete and SonarCloud result satisfies the lane gate, call the provided `test-unit` completion endpoint.

Build the request from the provided OpenAPI completion contract reference and runtime values.

The completion payload represents unit-test lane facts only.

Expected semantic content:

- `scope` — assigned backend service scope;
- `summary` — short factual summary of completed unit-test work;
- `affectedFiles` — affected source files covered or checked by this lane;
- `sonar` — aggregated SonarCloud result for the lane.

`affectedFiles` must contain source files, not test files.

`sonar` is one aggregated object for the lane, not per file.

Use runtime `scope`.

Use runtime `ticketId` and `laneId` in the callback path.

Do not include test files, test commands, integration test cases, QA notes, implementation handoff, reviewer status, or invented metrics.

---

## Completion Rule

Call completion only after:

- affected source files were processed;
- required unit tests were added or updated;
- PR workflow was completed;
- SonarCloud result was received;
- coverage is at least `90.0`;
- completion payload can be filled truthfully.

If completion cannot be submitted, report the exact failure in the final output.