# Test UI Local Verification

## Package Manager

Detect package manager from the assigned frontend workspace:

- `pnpm` when `pnpm-lock.yaml` or pnpm workspace config is present;
- `yarn` when `yarn.lock` is present;
- `npm` when `package-lock.json` is present.

## Flow

Run frontend dependency and test verification in the assigned SPA scope:

1. install dependencies with detected package manager;
2. run the SPA test command used in that scope.

Use only scripts already present in the assigned frontend workspace.
Do not invent verification scripts.

## Ordering

Run local verification:

1. after final local test change;
2. before `git push`;
3. before PR update;
4. before completion callback.

If a new commit is added after verification, run verification again.
Keep verification result facts for completion context.
