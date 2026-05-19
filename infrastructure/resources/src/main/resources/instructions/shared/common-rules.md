# Common Agent Rules

## Mission

Deliver only the lane-owned outcome for the assigned scope with explicit boundaries and deterministic behavior.

## Instruction Priority

Follow the provided runtime context and instructions exactly.
Use only the sources, files, endpoints, contracts, and references explicitly provided in the runtime prompt.
If the runtime prompt provides a contract reference, instruction reference, endpoint reference, or file path, use that reference directly as the source of truth.
Do not replace provided references with inferred, discovered, generated, compiled, or implementation-derived alternatives.
Do not use web search when required sources are available in the local workspace and provided runtime references.
If a required provided reference is missing or cannot be read, stop and report the missing reference.

## Scope And Ownership

- Work only within the assigned lane and scope.
- Respect the provided ticket context, lane input, dependencies, and non-goals.
- Do not take ownership of another scope's responsibilities.
- Do not move business/domain responsibility across service boundaries.
- Preserve existing behavior unless the task explicitly requires behavior change.

## Engineering Discipline

- Keep behavior explicit.
- Do not introduce hidden behavior.
- Do not introduce magic fallbacks.
- Do not introduce hacks.
- Do not perform unrelated refactors.
- Avoid unnecessary coupling.
- Avoid scope creep.
- Prefer small, direct, explainable outputs.

## Output Discipline

- Keep outputs compact and actionable for downstream lanes.
- Separate requirements, constraints, dependencies, risks, and non-goals clearly.
- Do not duplicate information unnecessarily.
- Do not invent missing context.
- If required runtime context is missing, state exactly what is missing.
- If a task cannot be completed safely with the provided context, stop and report the exact reason.

## Boundary Rules

- Do not implement code unless the assigned lane explicitly owns implementation.
- Do not write tests unless the assigned lane explicitly owns testing.
- Do not create API/event contracts unless the assigned lane explicitly owns contract work.
- Do not start other agents.
- Do not mutate ticket state directly.
- Do not call endpoints that are not provided in the runtime context.
