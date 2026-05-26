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
- Implement-be lane MUST NOT add new test classes or new test methods.
- Implement-be lane MAY update existing tests only when required for compatibility with changed backend production code.
- If behavior validation requires new tests, implement-be lane MUST hand off to test-unit lane instead of adding tests.

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

- Add or change persistencблe only when required by the lane task.
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

## Sonar Verify

Before completion, the implement-be lane must have a real SonarCloud result for the PR update that contains the backend changes.
Use only SonarCloud output as the source for `sonar.issues`.
Do not invent, estimate, default, infer, or locally calculate Sonar issue numbers.
`sonar.issues = 0` is valid only when SonarCloud explicitly reports zero issues.
If PR workflow has not created or updated a PR, SonarCloud cannot be considered available.
If SonarCloud result is not available, do not call the completion endpoint.
If SonarCloud result cannot be obtained, report the lane as blocked/failed in the final output with exact reason.
Sonar verification MUST use active polling of PR checks until SonarCloud result is available or retry budget is exhausted.
Minimum retries: 5 attempts with backoff (30s, 60s, 90s, 120s, 150s).
Only infrastructure-level unavailability after retries is a valid reason to stop Sonar verification.
In that case, report exact evidence and request next user instruction.
The implement-be lane reports only Sonar issues for changed backend production code.
Do not track, report, or react to coverage in the implement-be lane.
Do not add tests to satisfy coverage in the implement-be lane.
If Sonar reports issues caused by changed backend production code, fix the production code before completion.
Do not complete the lane with serious new Sonar issues in changed backend production code.
Allowed minor Sonar issues are limited to harmless style-level issues that do not affect correctness, security, maintainability, or runtime behavior.
Sonar duplication gate:
- `sonar.duplications` for changed backend production code must be `< 3.0%`.
- If duplication is `>= 3.0%`, reduce duplication in changed code and wait for a new SonarCloud result.

## Git Connectivity Gate

Before push, PR update, or Sonar polling, verify git remote connectivity/auth works for the current repository.

Rules:

- If git remote/auth is unavailable (for example auth denied, host unreachable, TLS/SSH failure), stop the lane as blocked.
- Do not call completion callback without successful push/PR update.
- Report exact git transport evidence in final output.

## Completion Callback
To complete the lane you need:
 - implement new backend behavior as required by the lane task.
 - mvn clean install with the changed code and ensure all tests pass.
 - create a PR with the changed code
 - Poll SonarCloud for the PR until the result is available and contains no new serious issues.
 - Fix any new serious Sonar issues in changed backend production code before completion.

After backend implementation is complete, call the provided `implement-be` completion endpoint.
Build the request from the provided OpenAPI completion contract reference and runtime values.
The completion payload represents backend implementation facts only.
Allowed payload content:

- `scope`
- `summary`
- `changedFiles`
- `integrationFlows`
- `persistenceChanges`
- `sonar`

### `scope`

Assigned service scope from runtime context.

Example:

```json
"scope": "automationservice-sox"
