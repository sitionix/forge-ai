# Implement FE Local Verification

## Flow

Run frontend verification after the final local production code change, before push/PR update/final completion response.

Detect package manager from assigned frontend workspace:

- `pnpm` for `pnpm-lock.yaml` or workspace config;
- `yarn` for `yarn.lock`;
- `npm` for `package-lock.json`.

Use detected package manager to install dependencies.
Run existing frontend verification scripts used by the assigned scope (for example build/typecheck/lint, and tests only when compatibility tests were updated or local conventions require them).
Do not invent new scripts.

## Ordering

1. after final local code change;
2. before `git push`;
3. before PR update;
4. before the final completion response.

If a new commit is added after verification, run verification again.
Keep verification result facts for completion context.
