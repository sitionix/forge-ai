# Test Unit Instructions

## Outcome

Add or update backend unit tests for affected source files in the assigned service scope.

## Ownership

Test Unit lane owns:

- backend unit-test artifacts;
- affected backend source file coverage facts;
- backend unit-test verification facts;
- test coverage Sonar result;
- test-unit completion content.

## Strategy

Execute steps in order.

1. Preparation  
   Read `additional-instructions/preparation-to-work.md`.

2. Unit test context  
   Read:
   - `additional-instructions/scope-context-usage.md`
   - `lane-instructions/test_unit/unit-test-context.md`

3. Unit test implementation  
   Read:
   - `additional-instructions/test-lane-boundary.md`
   - `lane-instructions/test_unit/unit-test-core.md`
   - `additional-instructions/java-style-basics.md`
   - `lane-instructions/test_unit/unit-test-controller.md` for controllers or API adapters.
   - `lane-instructions/test_unit/unit-test-mapper.md` for mappers or mapping helpers.
   - `lane-instructions/test_unit/unit-test-usecase-service.md` for use cases, services, validators, security/application components, or domain/application behavior classes.
   - `lane-instructions/test_unit/unit-test-generated-artifacts.md` when affected code/tests use generated DTOs, clients, contract types, event artifacts, producers, or consumers.

4. Local verification  
   Read `additional-instructions/backend-maven-verification.md`.

5. Pull request and coverage Sonar gate  
   Read:
   - `additional-instructions/pr-workflow.md`
   - `additional-instructions/sonar-cloud-base.md`
   - `additional-instructions/test-coverage-sonar-gate.md`

6. Completion callback  
   Read:
   - `lane-instructions/test_unit/completion-content.md`
   - `additional-instructions/completion-callback.md`.
