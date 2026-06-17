# PR Workflow

## Flow

1. Check current branch.
2. Review changed files.
3. Commit lane-owned changes.
4. Push branch.
5. Create or update an OPEN PR for the current work unit.
6. Wait for required PR checks used by the active lane.
7. Keep PR reference for completion content.

## Tool Boundary

Use only non-interactive `git` and `gh` CLI commands from the current repository.

Do not use GitHub MCP/connectors, Codex Apps, browser UI, or any tool that asks the operator for interactive approval.

The headless orchestrator waits for the Codex turn to finish. Interactive tool calls can leave the lane stuck until timeout.

## Open PR Lookup

Treat only an OPEN pull request as reusable.

Use an open-state query before deciding whether a PR exists:

```text
gh pr list --head <current-branch> --base <base-branch> --state open --json number,url,title,state,isDraft,headRefName,baseRefName
```

Do not use `gh pr view <branch>` as the source of truth for active PR existence, because it can return an old CLOSED pull request for a reused branch name.

If the open-state query returns no PR, create a new PR with `gh pr create`.

If an old closed PR exists for the same branch name, ignore it unless the active step explicitly asks to reopen closed PRs.

## PR Unit

One PR contains one coherent work unit.

Use current ticket branch and ticket identity.

Keep these facts for later steps:

- branch;
- commit hash;
- PR number;
- PR URL;
- required check result.
