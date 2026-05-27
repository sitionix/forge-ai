# Implement FE Production Implementation

## Work

Implement the requested frontend behavior in the assigned frontend scope.

Use:

- lane task;
- runtime context;
- architect handoff;
- provided API contract results;
- provided generated frontend packages, clients, DTOs, hooks, types, and artifact coordinates;
- existing frontend module structure.

Keep the change minimal, direct, and aligned with existing frontend code.
If the requested behavior already exists, avoid unnecessary code changes.

## Frontend Structure

Follow the existing frontend module structure in the assigned scope.
Preserve existing conventions for:

- routes;
- pages;
- components;
- hooks;
- clients;
- state;
- mappers;
- styles;
- UI primitives.

Reuse existing mappers, hooks, clients, UI primitives, and helpers where they fit the requested behavior.
Prefer small explicit implementation over broad refactoring.

## Frontend Boundaries

Keep responsibilities separated according to the existing SPA architecture:

- route/page code owns screen composition and navigation-level behavior;
- component code owns local rendering and user interaction;
- hook/state code owns UI state transitions and data-loading state;
- client/adapter code owns transport and API integration;
- mapper/helper code owns request, response, and view-model mapping;
- style code owns presentation details.

Keep transport/client concerns in existing client or adapter layers.
Keep mapping and normalization out of page/component rendering code when the project already separates it.
Keep business/domain decisions out of presentational components.
Preserve existing behavior unless the lane task explicitly requires a behavior change.

## API And Generated Artifacts

Treat API-generated frontend packages and client artifacts as source of truth when the task depends on API integration.
Use provided frontend dependency and evidence notes from runtime input.
Use generated frontend artifacts for BFF calls when they are provided for the required flow.
Use existing frontend-local adapters only when they are already the local integration pattern and do not conflict with provided generated artifacts.
Do not invent API operations, fields, hooks, clients, package names, endpoint paths, payload shapes, enum values, or contract behavior.
Do not implement manual API clients, manual fetch wrappers, or contract-shaped DTOs for a flow when generated artifacts already exist.
If generated artifacts exist but cannot be used because of a concrete technical mismatch, keep exact mismatch evidence for completion context instead of implementing a parallel manual API path.
Do not repeat API artifacts in the completion payload.

## Mapping And UI State

Use existing frontend mappers, transformers, and helpers for request, response, and view-model mapping.
Add or extend mapping helpers when changed code needs mapping.
Keep state transitions explicit and consistent with existing SPA patterns.
Keep loading, empty, error, validation, and success states consistent with local UI conventions.
Keep user-visible behavior clear and traceable to the lane task.

## Test Boundary

Implement FE lane does not add new test classes, new test files, or new test methods.
Implement FE lane may update existing tests only when required for compatibility with changed frontend production code.
When behavior validation requires new tests, preserve the need for `test_ui` lane instead of adding tests here.

## Code Quality

Before local verification:

- review changed diff;
- remove unrelated changes;
- remove stale code related to the replaced frontend flow;
- keep route/page/component/hook/client/state/mapper/style boundaries clean;
- keep changed code consistent with existing frontend style;
- reduce new duplication in changed frontend code.