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

Use architect handoff as the primary implementation direction.

Use lane task and runtime context as factual source of truth.

## Scope

Work inside the assigned backend service scope.

Identify:

- service/module path;
- owned backend behavior;
- relevant dependencies;
- contract or generated artifact inputs;
- expected production behavior;
- non-goals and constraints.

## Architecture Escalation

Do not read architecture files by default.
Use repository structure and architect handoff first.

Read `architecture/internal-service-architecture.md` only when backend module or layer placement is unclear.
Read `architecture/architecture-rules.md` only when ownership, cross-boundary flow, contract source-of-truth, or generated-artifact compatibility is unclear.
Read `architecture/system-architecture.md` only when the cross-service runtime path is unclear after checking architect handoff, scope context, and repository evidence.

Use the smallest architecture context needed for the current implementation decision.

## Result

Keep these facts for later steps:

- assigned service scope;
- affected backend flow;
- target module or layer;
- required generated DTO/client/event artifact inputs;
- production files expected to change;
- compatibility-only test updates, if any;
- test-lane handoff need, if behavior validation requires new tests.