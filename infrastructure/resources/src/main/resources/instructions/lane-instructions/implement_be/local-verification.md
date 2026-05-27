# Implement BE Local Verification

## Flow

Run backend verification after the final local production code change.
Use the assigned backend service scope.
Required command:

`mvn clean install`

Use repository-approved wrapper equivalent only when that is the local convention.
Do not skip tests.

## Ordering

Run local verification:

1. after the final local code change;
2. before `git push`;
3. before PR update;
4. before completion callback.

If a new commit is added after verification, run verification again.
Keep verification result facts for completion context.