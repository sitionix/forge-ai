# Preparation To Work Workflow

## Purpose
Reference workflow for scripted repository preparation before Agent Bus lane execution.

## Applies To
Preparation is executed by `agent-bus/scripts/prepare-agent-bus-repos.sh` before lanes start.

## Repository Preparation
Before inspecting source/API state:
1. Check current repository state.
2. Detect local uncommitted changes.
3. If local changes exist, stash them (`git stash push -u -m "agent-bus-prep-<ticket-id>"`).
4. Switch to `develop`.
5. Pull latest `develop`.
6. Checkout `feature/<ticket-id>` if it exists, otherwise create it from `develop`.
7. Inspect repository state from that ticket branch.

## Branch Naming
Use:

```text
feature/<ticket-id>
```

Example:

```text
feature/SITIONIX-135
```

Do not invent a different branch name unless the task explicitly provides one.

## Commit Message
Architect does not commit or create PRs in this workflow.

Use:

```text
[<ticket-id>] - <message>
```

Example:

```text
[SITIONIX-135] - Add owner summary API contract
```

## Safety Rules
- Do not overwrite unrelated local changes.
- Do not work on stale `develop`.
- Do not reuse an unrelated feature branch.
- Do not mix unrelated task changes into the same PR.