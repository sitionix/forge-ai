# Implement BE Instructions

## Goal

Implement backend-owned production code for the assigned service scope using the lane task, runtime context, provided contracts, and generated artifacts.

The result of this lane is backend implementation plus a successful `implement-be` completion callback.

---

## Execution

- Work inside the assigned backend scope.
- Use the lane task and runtime context as the implementation source.
- Use provided contract references and generated artifacts when the task depends on them.
- Implement only the requested backend behavior.
- Keep the change minimal, direct, and aligned with the existing service structure.
- If the requested behavior already exists, avoid unnecessary code changes.
- If required context, contract, or artifact is missing, stop and report the exact missing input.
- Before completion, review the changed diff and fix violations of these instructions.

---

## Backend Structure

- Keep controller, application, domain, and infrastructure responsibilities separated.
- Controllers accept requests, delegate to application/use cases, and return mapped responses.
- Application/use-case code handles orchestration and transactional application behavior.
- Domain code owns business rules and domain state transitions.
- Infrastructure code owns persistence, external clients, messaging adapters, and technical integrations.
- Preserve existing behavior unless the lane task explicitly requires a behavior change.
- Prefer small explicit implementation over broad refactoring.

---

## Contracts And Generated Artifacts

- Treat provided API/event specifications as source of truth for implemented boundaries.
- Use generated DTOs, clients, and artifact coordinates exactly as provided.
- Implement controllers, clients, consumers, and producers according to the relevant specification.
- Do not invent fields, endpoints, payload shapes, enum values, topics, or artifact coordinates.
- If generated artifacts conflict with the task, stop and report the conflict.

---

## Mapping

- Use existing mappers for request/response/domain/persistence mapping.
- Add or extend a mapper when changed code needs mapping.
- Keep field mapping out of controllers, clients, consumers, producers, and repositories.
- Keep business decisions out of mappers.

---

## Persistence

- Add or change persistence only when required by the lane task.
- Follow the service’s existing persistence conventions.
- Keep migrations, entities, repositories, and adapters minimal and explicit.
- Use the service’s existing enum/state persistence style.
- Remove stale persistence code only when the lane task replaces that flow.

---

## Code Quality

Before completion:

- Check changed files against these instructions.
- Keep changed code consistent with existing service style.
- Keep controller/application/domain/infrastructure boundaries clean.
- Remove stale code related to the replaced backend flow.
- Avoid new duplication in changed backend code.
- Do not leave fully qualified class names in changed Java code.
- Do not introduce new Sonar issues in changed backend code.

## Completion Callback

After backend implementation is complete, call the provided `implement-be` completion endpoint.

Build the request from the provided OpenAPI completion contract reference and runtime values.

The completion payload represents backend implementation facts only.

Allowed payload content:

- `scope`
- `summary`
- `changedFiles`
- `integrationFlows`
- `persistenceChanges`

### `scope`

Assigned service scope from runtime context.

Example:

```json
"scope": "automationservice-sox"