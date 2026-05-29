# Implement FE Frontend Context

## Source

Use:

- lane task;
- runtime context;
- assigned frontend scope;
- scope context;
- architect implementation handoff;
- API contract results and generated frontend artifact facts when present;
- repository evidence from the assigned frontend scope.

Use architect handoff as primary implementation direction.

## Scope Discovery

Identify:

- frontend app/package path;
- owned frontend behavior;
- affected route/page/component/hook/client/state/mapper/style area;
- API-generated frontend package inputs when present;
- expected user-visible behavior;
- non-goals and constraints.

## Architecture Escalation

Do not read architecture files by default. Use architect handoff, scope context, and repository evidence first.

Read only when still unclear:

- `architecture/architecture-rules.md` for frontend/backend ownership, BFF dependency, generated artifact source, or cross-boundary execution order;
- `architecture/system-architecture.md` for browser-to-BFF-to-service runtime path.

Use the smallest architecture file needed.

## Backend/API Boundary Detection

Detect whether task is:

- frontend-only (rendering/UI-local mapping/UI-local state), or
- API/generated-artifact dependent.

If required backend payloads/endpoints/generated artifacts are missing, keep exact evidence for completion context.

## Result Facts

Keep for later steps:

- assigned frontend scope;
- affected frontend app/package;
- affected route/page/component/hook/client/state/mapper/style area;
- provided generated frontend artifact inputs;
- user-visible behavior to implement;
- compatibility-only test updates, if any;
- `test_ui` handoff need when behavior validation requires new tests.
