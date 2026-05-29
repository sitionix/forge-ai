# Test IT Instructions

## Outcome

Add or update backend integration tests for assigned QA Lead integration test cases in the assigned backend service scope.

## Ownership

Test IT lane owns:

- backend integration-test artifacts;
- covered QA Lead integration case facts;
- backend integration-test verification facts;
- test-it completion content.

## Strategy

Execute steps in order.

1. Preparation  
   Read `additional-instructions/preparation-to-work.md`.

2. IT test context  
   Read:
    - `additional-instructions/scope-context-usage.md`
    - `lane-instructions/test_it/it-test-context.md`

3. ForgeIT setup  
   Read:
    - `additional-instructions/test-lane-boundary.md`
    - `lane-instructions/test_it/forge-it-setup.md`
    - `additional-instructions/java-style-basics.md`

4. Case implementation  
   Read `lane-instructions/test_it/case-implementation.md`.

5. Flow-specific rules  
   Read only the files needed by the active QA cases:
    - `lane-instructions/test_it/http-mockmvc-flow.md`
    - `lane-instructions/test_it/postgresql-flow.md`
    - `lane-instructions/test_it/wiremock-flow.md`
    - `lane-instructions/test_it/kafka-flow.md`
    - `lane-instructions/test_it/fixtures.md`

6. Local verification  
   Read `additional-instructions/backend-maven-verification.md`.

7. Pull request  
   Read `additional-instructions/pr-workflow.md`.

8. Completion callback  
   Read:
    - `lane-instructions/test_it/completion-content.md`
    - `additional-instructions/completion-callback.md`.

## Completion Content

Return test-it lane facts only:

- scope;
- summary;
- covered QA/IT case names.