# API Completion Content

## Scope

Completion payload represents API lane facts only.
Build final completion payload from the provided final-step completion payload contract.
Use this file only for semantic content selection.

## Allowed Content

Use only these semantic content groups when the final-step completion payload contract supports them:

- changed contract units;
- operation metadata;
- generated artifacts;
- dependency/import snippets;
- DTO/client hints;
- downstream consumption notes.

## Artifact Evidence Discipline

Use generated artifact evidence from trusted generation outputs.
Do not invent artifact coordinates.
Do not manually construct dependency or import coordinates.

## Boundary

Do not include:

- implementation handoff;
- test planning content;
- completion transport mechanics;
- OpenAPI request-construction mechanics;
- retry or HTTP delivery verification rules;
- generation target-selection logic.
