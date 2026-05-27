# Implement BE Production Implementation

## Work

Implement the requested backend behavior in the assigned backend scope.

Use:

- lane task;
- runtime context;
- architect handoff;
- provided API/event specifications;
- provided generated DTOs, clients, producers, consumers, and artifact coordinates;
- existing service structure.

Keep the change minimal, direct, and aligned with existing code.

## Backend Structure

Keep responsibilities separated:

- controllers accept requests, delegate to application/use cases, and return mapped responses;
- application/use-case code handles orchestration and transactional application behavior;
- domain code owns business rules and domain state transitions;
- infrastructure code owns persistence, external clients, messaging adapters, and technical integrations;
- boot code owns runtime wiring and composition.

Preserve existing behavior unless the lane task explicitly requires a behavior change.

Prefer small explicit implementation over broad refactoring.

## Contracts And Generated Artifacts

Treat provided API/event specifications as source of truth for implemented boundaries.

Use generated artifacts exactly as provided.

Implement relevant backend boundaries according to the specification:

- controllers;
- clients;
- consumers;
- producers.

Use generated DTOs, clients, event wrappers, and artifact coordinates from provided evidence.

Do not invent fields, endpoints, payload shapes, enum values, topics, or artifact coordinates.

## Mapping

Use existing mappers for request, response, domain, persistence, client, and event mapping.

Add or extend a mapper when changed code needs mapping.

Keep field mapping out of:

- controllers;
- clients;
- consumers;
- producers;
- repositories.

Keep business decisions out of mappers.

## Persistence

Add or change persistence only when required by the lane task.

Follow the service’s existing persistence conventions.

Keep these minimal and explicit:

- migrations;
- entities;
- repositories;
- adapters;
- persistence mappers.

Use the service’s existing enum and state persistence style.

Remove stale persistence code only when the lane task replaces that flow.

## Code Quality

Before local verification:

- keep changed code consistent with existing service style;
- keep controller/application/domain/infrastructure/boot boundaries clean;
- reduce new duplication in changed backend code;
- use imports instead of fully qualified class names;
- keep Java code aligned with `java-style-basics.md`.