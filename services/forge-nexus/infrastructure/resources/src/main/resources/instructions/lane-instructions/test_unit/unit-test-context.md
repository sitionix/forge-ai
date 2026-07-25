# Test Unit Context

## Inputs

Use lane task, runtime context, assigned backend scope, scope context, Implement BE completion facts, affected backend source files, and repository evidence from the assigned backend service.

Affected source files are the primary test target.

## Discovery

For each affected source file, identify:

- class kind: controller/API adapter, mapper/helper, usecase/service/validator/security/application, or other;
- existing unit-test file and local test style in the same module;
- collaborators used by the class;
- generated DTO/client/contract/event artifacts used by the class.

## Coverage Decision

For each affected source file, choose one:

- update existing unit test;
- add new unit test;
- keep existing coverage when changed behavior is already covered;
- mark not suitable for unit testing with exact reason.

Common not-suitable cases: generated file, pure DTO without behavior, configuration-only file, behavior owned by integration tests.

## Conditional Packs

Load only needed implementation packs:

- `unit-test-controller.md`
- `unit-test-mapper.md`
- `unit-test-usecase-service.md`
- `unit-test-generated-artifacts.md`

## Completion Facts

Keep: scope; affected source files covered/checked; source files checked but unchanged; changed unit-test files; generated artifact evidence when relevant; local verification evidence.
