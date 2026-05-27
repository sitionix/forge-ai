# Test UI Local Verification

## Package Manager

Detect the package manager from the assigned frontend workspace:

- use `pnpm` when `pnpm-lock.yaml` or pnpm workspace config is present;
- use `yarn` when `yarn.lock` is present;
- use `npm` when `package-lock.json` is present.

Use the repository-local package manager command.

## Flow

Run frontend dependency and test verification in the assigned SPA scope.
Required verification:

1. install dependencies with the detected package manager;
2. run the SPA test command used in that scope.

Use only scripts already present in the assigned frontend workspace.
Do not invent new verification scripts.
Use existing local conventions for app/package selection in monorepo workspaces.

## Ordering

Run local verification:

1. after the final local test change;
2. before `git push`;
3. before PR update;
4. before completion callback.

If a new commit is added after verification, run verification again.
Keep verification result facts for completion context.
Completion callback uses only verification still valid for the pushed commit.