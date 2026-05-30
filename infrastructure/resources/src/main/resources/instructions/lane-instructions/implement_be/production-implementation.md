# Implement BE Production Implementation

## Work

Implement requested backend behavior in assigned backend scope using lane task, runtime context, architect handoff, and provided contract/generated artifact inputs.

## Backend Structure

Keep responsibilities separated:

- controllers: request handling, delegation, mapped responses;
- application/use-case: orchestration and transactional behavior;
- domain: business rules and state transitions;
- infrastructure: persistence, external clients, messaging adapters, technical integrations;
- boot: runtime wiring/composition.

## Contracts And Generated Artifacts

Treat provided API/event specifications as source of truth for implemented boundaries.

Use generated artifacts exactly as provided for controllers, clients, consumers, and producers.

Do not invent fields, endpoints, payload shapes, enum values, topics, or artifact coordinates.

## Mapping

Use existing mappers for request/response/domain/persistence/client/event mapping.
Add or extend mappers only when needed.

Keep field mapping out of controllers, clients, consumers, producers, and repositories.
Keep business decisions out of mappers.

## Persistence

Change persistence only when required by lane task.
Keep migrations/entities/repositories/adapters/persistence mappers minimal and explicit.
Use existing service enum/state persistence style.

## Test Boundary

Do not add new test classes or new test methods.
Only compatibility updates to existing tests are allowed when required by changed backend production code.
If behavior validation needs new tests, hand off to `test_unit`.

## BE Code Quality

Before local verification:

- keep backend layer boundaries clean;
- remove stale code from replaced backend flow;
- reduce new duplication in changed backend code.
