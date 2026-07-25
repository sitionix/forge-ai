# Version Rules

## Scope

Use these rules for changed source-of-truth contract surfaces that require generated artifacts.

These rules are contract-type neutral.

They may apply to:

- REST contracts;
- event contracts;
- other generated contract surfaces with metadata-backed versions.

## Source

Compare the changed contract surface version in the current branch against `develop`.
Do not checkout `develop` or recreate branches in this step; preparation already selected the ticket branch.
Read baseline files through git refs such as `git show develop:<path>` or `git show origin/develop:<path>` when needed.

Use the version fields owned by the changed contract surface.

For REST contracts, keep these synchronized when present:

- metadata version;
- OpenAPI `info.version`.

For event contracts, keep these synchronized when present:

- metadata version;
- AsyncAPI version;
- event schema or artifact version declared by the contract surface.

## Flow

For each changed contract surface:

1. read the version from `develop`;
2. read the version from the current branch;
3. if current version equals `develop`, set current version to `develop + 1`;
4. if current version is already greater than `develop`, keep it;
5. synchronize all version declarations owned by that contract surface.

Apply the version update once per changed contract surface.
