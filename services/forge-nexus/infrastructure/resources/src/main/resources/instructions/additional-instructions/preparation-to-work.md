# Preparation To Work

## Flow

The orchestrator starts Codex in the repository assigned to the current lane.
Use that current working directory as the execution repository.

This step prepares only the current repository. Do not prepare any other repository in this step.

1. Run all git commands from the current working directory.
2. Check current repository state.
3. Detect local uncommitted changes.
4. Stash unrelated local changes when present.
5. Checkout `develop`.
6. Pull latest `develop`.
7. Checkout existing `feature/<ticket-key>` or create it from `develop`.
8. Inspect repository state from the ticket branch.

Do not clone repositories.
Do not change remote URLs.
Do not fallback to `main` when `develop` is missing.
If the required repository or `develop` branch is unavailable, report that exact setup problem in the step evidence instead of improvising.
Do not include task payloads, previous step results, prompts, responses, or full command output in evidence.

## Branch

Use:

`feature/<ticket-key>`

Example:

`feature/SITIONIX-135`

## Commit Message

Use:

`[<ticket-id>] - <message>`

Example:

`[SITIONIX-135] - Add owner summary API contract`

## Minimal Evidence

Return only these evidence fields:

```json
{
  "repository": "/absolute/path/to/current/repository",
  "branch": "feature/<ticket-key>",
  "baseBranch": "develop",
  "headCommit": "<git rev-parse HEAD>",
  "clean": true
}
```

Rules:

- `repository` must be the absolute path returned by `pwd`.
- `branch` must be the actual current git branch.
- `baseBranch` must be `develop`.
- `headCommit` must be the current `git rev-parse HEAD` value.
- `clean` must be `true` only when `git status --porcelain=v1` is empty.
