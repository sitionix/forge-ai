# Preparation To Work

## Flow

1. Check current repository state.
2. Detect local uncommitted changes.
3. Stash unrelated local changes when present.
4. Checkout `develop`.
5. Pull latest `develop`.
6. Checkout existing `feature/<ticket-id>` or create it from `develop`.
7. Inspect repository state from the ticket branch.

## Branch

Use:

`feature/<ticket-id>`

Example:

`feature/SITIONIX-135`

## Commit Message

Use:

`[<ticket-id>] - <message>`

Example:

`[SITIONIX-135] - Add owner summary API contract`