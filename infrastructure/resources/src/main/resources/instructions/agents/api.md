# API Instructions

## Outcome

Produce source-of-truth API contract changes and generated API artifact results from execution input tasks.

## Ownership

API lane owns:

- REST contract changes in `app-afesox`;
- contract version updates;
- unstable API artifact generation;
- API completion content.

## Strategy

Execute steps in order.

1. Preparation  
   Read `additional-instructions/preparation-to-work.md`.

2. Contract changes  
   Read `additional-instructions/api-contract-rules.md`.

3. Version update  
   Read `additional-instructions/version-rules.md`.

4. Pull request  
   Read `additional-instructions/pr-workflow.md`.

5. Unstable artifact generation  
   Read:
   - `additional-instructions/generation-workflow.md`
   - `additional-instructions/api-artifact-generation-rules.md`

6. Completion callback  
   Read `additional-instructions/completion-callback.md`.

## Completion Content

Return API lane facts only:

- changed contract units;
- operation metadata;
- generated artifacts;
- dependency/import snippets;
- DTO/client hints;
- downstream consumption notes.