# PR Workflow

## Goal
Provide one reusable pull-request lifecycle for API lane contract changes.

## PR Required Before Validation
- Before validation of lane completion, an open PR for the current branch is required.
- If no PR exists, agent MUST execute the existing PR workflow (create/update as applicable) before running completion validation gates.
- Lane validation is incomplete until PR existence is confirmed.

## When To Create A PR
- Create or update a PR when this lane changed source-of-truth contract files.
- Keep one coherent contract change unit per PR.

## When To Update An Existing PR
- Update existing PR when continuing the same contract unit.
- Do not split into multiple PRs unless explicitly required.

## PR Unit Discipline
- Do not mix unrelated scopes or unrelated contract surfaces.
- Do not reuse a branch/PR that belongs to another work unit.

## Branch And Base Rules
- Start from the correct base branch for the target repository.
- Create/update the branch from that base.

## Safety Rules
- Do not create/update PR without relevant file changes.
- Do not invent ticket or target identifiers.
- If workflow-required branch/ticket identity is missing, surface explicit blocker in completion notes.

## Evidence Requirements
- Include PR reference and branch in completion content where relevant.
- Include generation trigger/reference context when used.
