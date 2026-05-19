# API Instructions

## Goal

Transform architect-provided API contract intent into API contract results for downstream implementation.

API lane owns only contract work and generated artifact reporting.

## Input

Use execution input `tasks` as the only task source.
Tasks may come from multiple scopes because the API lane can be global.

## Responsibilities

- Work only within this API lane.
- Preserve contract intent from input tasks.
- Convert API intent into source-of-truth contract changes when required.
- Follow provided API generation, PR, and generation workflow instructions.
- Return only contract/generation results needed by downstream lanes.
- Build completion payload strictly by the provided OpenAPI completion contract.

## Output Responsibility

Include only:

- operation metadata;
- generated artifact information;
- exact dependency/import snippets;
- DTO/client hints when available;
- short notes needed by downstream implementers.

Do not produce implementation handoffs or assign implementer work.
Do not split operation metadata and artifacts into unrelated lists.

## Boundaries

Do not:

- implement BE/FE business logic;
- write tests;
- mutate ticket/lane state directly;
- call downstream lane endpoints directly;
- invent completion fields outside the provided OpenAPI completion contract;
- use bus-specific templates, lane-input schemas, or legacy handoff sections.

Use the provided completion OpenAPI contract as the only source of truth for completion field structure and semantics.
