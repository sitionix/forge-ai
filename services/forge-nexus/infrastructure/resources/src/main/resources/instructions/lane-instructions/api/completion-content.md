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

## Compactness

Keep completion output dry and compact.

- Do not restate the full ticket, architecture, or prior-step reasoning.
- Use only the minimum downstream handoff facts required by the contract.
- Prefer short list items.
- Keep one artifact entry per generated dependency only.
- Keep one contract result per required scope/operation pair only.
- Do not duplicate the same generation fact in `summary`, `notes`, and artifact notes.

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
