# Test Unit Context

## Source

Use:

- lane task;
- runtime context;
- assigned backend scope;
- scope context;
- Implement BE completion facts;
- affected backend source files from runtime context;
- provided API/event/generated artifact facts when the affected source files use them;
- repository evidence from the assigned backend service.

Use affected source files as the primary unit-test target.
Use Implement BE completion facts as factual implementation context.
Use runtime context as source of truth for assigned scope, ticket, lane, contract references, and callback references.

## Scope

Work inside the assigned backend service scope.

Identify:

- backend service path;
- affected source files;
- affected class or classes under test;
- existing unit-test files for those source files;
- existing unit-test style in the same service/module;
- collaborators used by the affected source files;
- generated DTOs, clients, producers, consumers, or contract types used by the affected code;
- behavior changes that need unit-level coverage.

## Test Target

Process affected source files file by file.
For each affected source file, decide whether to:

- update an existing unit test;
- add a new unit test file;
- confirm existing unit coverage already covers the changed behavior.

Create unit tests only for behavior affected by this lane.
If an affected file is not suitable for unit testing, keep the exact reason for completion content.

Examples:

- configuration-only file;
- generated file;
- pure DTO with no behavior;
- class already fully covered by existing unit tests;
- behavior belongs to integration testing.

## Unit Boundary

Unit tests validate class-level behavior.

Unit tests isolate the class under test.

Integration behavior belongs to `test_it`, not `test_unit`.

Do not use QA Lead integration cases as primary input for this lane.

## Result

Keep these facts for later steps:

- assigned backend scope;
- affected source files;
- source files covered by unit tests;
- source files checked but not changed;
- unit test files changed;
- generated artifact inputs used by tests;
- local verification command.