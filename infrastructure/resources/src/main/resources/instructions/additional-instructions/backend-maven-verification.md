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

## Ordering

Run local verification:

1. after the final local change;
2. before `git push`;
3. before PR update;
4. before completion callback.

If a new commit is added after verification, run verification again.
Completion callback uses only verification still valid for the pushed commit.
Keep verification result facts for completion context.

## Failure

Do not push commits when verification fails.
Do not call completion callback when verification failed.
When verification cannot be executed because of missing dependencies or environment constraints, keep exact failure evidence.