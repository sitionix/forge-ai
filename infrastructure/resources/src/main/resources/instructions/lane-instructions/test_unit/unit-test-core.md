# Test Unit Core

Use this file for all `test_unit` tasks.

## Boundary

- Unit tests only.
- Isolate the class under test.
- Do not start Spring context.
- Do not use MockMvc.
- MockMvc belongs to integration tests.

## Style

Follow local repository unit-test style:

- JUnit 5;
- Mockito extension;
- AssertJ assertions;
- direct SUT construction in `@BeforeEach`;
- clear `// given`, `// when`, `// then` blocks.

Verify observable behavior first. Verify collaborator interactions only when behavior-relevant.

## Fixtures

- Use private helper/builders for test data.
- Keep stable defaults in helpers.
- Pass only dynamic values as helper parameters.
- Avoid mutable shared fixture state.
- Avoid large duplicated setup blocks.

## Case Baseline

For each affected source file, cover only changed behavior. Choose relevant cases such as:

- happy path;
- validation/exception branch;
- skipped collaborator behavior;
- boundary/regression-prone branch.

Skip irrelevant categories.

## Naming And Artifacts

- Use behavior-focused test names.
- Change unit-test artifacts only (`src/test`, `*Test`).
- Do not change integration-test artifacts (`src/it`, `forge-it`, `*IT`).

Implementation details for specific class kinds are loaded from conditional unit-kind files.
