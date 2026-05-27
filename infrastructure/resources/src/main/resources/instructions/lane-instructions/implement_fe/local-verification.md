# Implement FE Local Verification

## Flow

Run frontend verification after the final local production code change.
Use the assigned frontend scope.
Detect the repository package manager from the assigned frontend workspace:

- `pnpm` when `pnpm-lock.yaml` or workspace config is used;
- `yarn` when `yarn.lock` is used;
- `npm` when `package-lock.json` is used.

Run dependency installation using the detected package manager.
Run the frontend verification commands used by the assigned scope.
Prefer existing package scripts and local conventions, such as:

- build;
- typecheck;
- lint;
- test only when existing compatibility tests were updated by this lane or local conventions require tests for production verification.

Do not invent new verification scripts.

Use only scripts already present in the assigned frontend workspace.

## Ordering

Run local verification:

1. after the final local code change;
2. before `git push`;
3. before PR update;
4. before completion callback.

If a new commit is added after verification, run verification again.
Keep verification result facts for completion context.