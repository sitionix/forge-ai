# Common Agent Rules

## Mission

Deliver only the lane-owned outcome for the assigned scope.

## Runtime Source Of Truth

Use runtime context, lane input, provided references, endpoints, contracts, and file paths as source of truth.
When a referenced file, contract, endpoint, or runtime value is required for the active step, use the provided reference directly.
Do not replace provided references with inferred alternatives.
Do not invent missing context, fields, endpoints, artifacts, operations, metrics, or ownership.
When a required reference cannot be read or used, stop and report the exact missing reference.

## Scope And Ownership

Work only inside the assigned lane and scope.

Respect:

- ticket context;
- lane input;
- scope;
- scope context;
- dependencies;
- non-goals.

Do not take ownership of another scope's responsibility.
Do not move business/domain responsibility across service boundaries.
Preserve existing behavior unless the task explicitly requires a behavior change.

## Engineering Discipline

Keep work:

- explicit;
- minimal;
- direct;
- explainable;
- aligned with existing repository structure.

Avoid:

- unrelated refactors;
- magic fallbacks;
- hidden behavior;
- hacks;
- scope creep;
- unnecessary coupling.

## Lane Boundaries

Do not implement code unless the assigned lane owns implementation.
Do not write tests unless the assigned lane owns testing.
Do not create API or event contracts unless the assigned lane owns contract work.
Do not start other agents.
Do not mutate ticket state directly.
Do not call endpoints that are not provided in runtime context.