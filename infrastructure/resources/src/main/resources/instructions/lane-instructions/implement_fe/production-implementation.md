# Implement FE Production Implementation

## Work

Implement requested frontend behavior in assigned frontend scope using lane task, runtime context, architect handoff, provided API contract results, and provided generated frontend artifacts.

## Frontend Structure

Preserve existing structure and responsibilities across:

- route/page;
- component;
- hook/state;
- client/adapter;
- mapper/helper;
- style/UI primitives.

Keep route/page composition, component interaction, state transitions, transport concerns, mapping, and presentation concerns in their existing layers.

## API And Generated Artifacts

When task depends on API integration, treat generated frontend packages/client artifacts as source of truth.

Use generated artifacts for BFF calls when provided for the required flow.
Do not invent API operations, fields, hooks, clients, package names, endpoint paths, payload shapes, enum values, or contract behavior.
Do not implement manual API clients/fetch wrappers/contract-shaped DTOs when generated artifacts already exist.
If generated artifacts cannot be used, keep exact mismatch evidence.

## Mapping And UI State

Use existing frontend mappers/transformers/helpers for request/response/view-model mapping.
Add or extend mapping helpers only when needed.
Keep mapping/normalization out of rendering code where project already separates it.
Keep business/domain decisions out of presentational components.
Keep loading/empty/error/validation/success state behavior consistent with local UI conventions.

## Test Boundary

Do not add new test classes, files, or methods.
Only compatibility updates to existing tests are allowed when required by changed frontend production code.
If behavior validation needs new tests, hand off to `test_ui`.

## FE Code Quality

Before local verification:

- keep frontend boundaries clean;
- remove stale code from replaced frontend flow;
- reduce new duplication in changed frontend code.
