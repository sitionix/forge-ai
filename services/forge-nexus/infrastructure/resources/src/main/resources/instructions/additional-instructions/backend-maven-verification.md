# Backend Maven Verification

## Scope

Use this verification gate for backend lanes that change backend production or backend test files.

## Flow

Run backend verification after the final local change.
Use the assigned backend service scope.
Required command:
`mvn clean install`
Use repository-approved wrapper equivalent only when that is the local convention.
Do not skip tests.

### Test Unit Lane Rule

For `test_unit` lane ownership, the blocking verification gate is unit tests for the affected backend modules.

- `mvn clean install` is still preferred to collect full evidence.
- Integration-test/failsafe failures (`*IT`, `src/it`, ForgeIT) are non-blocking for `test_unit` completion.
- If unit tests pass and only integration tests fail, continue `test_unit` completion and record exact integration-failure evidence.
- Do not modify integration tests from `test_unit` lane only to satisfy this gate.

## Ordering

Run local verification:

1. after the final local change;
2. before `git push`;
3. before PR update;
4. before the final completion response.

If a new commit is added after verification, run verification again.
Completion response uses only verification still valid for the pushed commit.
Keep verification result facts for completion context.

## Failure

Do not push commits when verification fails.
Do not finish completion when verification failed.
Exception for `test_unit`: completion is allowed when unit-test gate passed and only integration/failsafe tests failed.
When verification cannot be executed because of missing dependencies or environment constraints, keep exact failure evidence.
