# Implement BE Backend Context

## Source

Use:

- lane task;
- runtime context;
- assigned backend scope;
- scope context;
- architect implementation handoff;
- API/event contract results and generated artifact facts when present;
- repository evidence from the assigned service.

Use architect handoff as primary implementation direction.

## Scope Discovery

Identify:

- service/module path;
- owned backend behavior;
- relevant dependencies;
- contract or generated artifact inputs;
- expected production behavior;
- non-goals and constraints.

## Architecture Escalation

Do not read architecture files by default. Use architect handoff, scope context, and repository evidence first.

Read only when still unclear:

- `architecture/internal-service-architecture.md` for backend module/layer placement;
- `architecture/architecture-rules.md` for ownership, cross-boundary flow, contract source-of-truth, or generated-artifact compatibility;
- `architecture/system-architecture.md` for cross-service runtime path.

Use the smallest architecture file needed.

## Result Facts

Keep for later steps:

- assigned service scope;
- affected backend flow;
- target module/layer;
- required generated DTO/client/event inputs;
- expected production files to change;
- compatibility-only test updates, if any;
- `test_unit` handoff need when behavior validation requires new tests.
