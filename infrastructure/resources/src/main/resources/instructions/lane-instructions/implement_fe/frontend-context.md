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

Use architect handoff as the primary implementation direction.
Use lane task and runtime context as factual source of truth.

## Scope

Work inside the assigned frontend scope.

Identify:

- SPA app or frontend package path;
- owned frontend behavior;
- relevant route, page, component, hook, client, state, mapper, or style area;
- API-generated frontend package inputs when present;
- expected user-visible behavior;
- non-goals and constraints.

## Architecture Escalation

Do not read architecture files by default.
Use architect handoff, scope context, and repository structure first.

Read `architecture/architecture-rules.md` only when frontend/backend ownership, BFF dependency, generated artifact source, or cross-boundary execution order is unclear.
Read `architecture/system-architecture.md` only when the browser-to-BFF-to-service runtime path is unclear after checking architect handoff, scope context, and repository evidence.

Use the smallest architecture context needed for the current frontend implementation decision.

## Backend And API Boundary

Frontend implementation follows existing backend contracts.
When frontend behavior depends on backend payloads or endpoints, use provided contract or generated frontend artifact facts.
When the task only changes frontend rendering, UI-local mapping, or UI-local state without backend behavior changes, implement inside the frontend scope directly.
When required backend payloads, endpoints, or generated frontend artifacts are missing, keep that fact for completion context instead of creating local backend-shaped substitutes.

## Result

Keep these facts for later steps:

- assigned frontend scope;
- affected frontend app or package;
- affected route, page, component, hook, client, state, mapper, or style area;
- provided generated frontend artifact inputs;
- user-visible behavior to implement;
- compatibility-only test updates, if any;
- test-ui handoff need, if behavior validation requires new tests.