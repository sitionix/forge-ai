# Common Agent Rules

## Mission

Deliver only the lane-owned outcome for the assigned scope.
Do not stop after successful intermediate steps.
Do not report progress to the user.

## No-Stop Lane Execution (Absolute)

For all lane agents, execution is strictly non-stop.

1. The agent must not pause, stop, or ask the user for intermediate decisions.
2. The agent must not send progress/interim messages.
3. The agent must continue autonomously until the current lane step is complete.
4. Any errors, validation failures, transport failures, or retries must be handled internally; the agent keeps iterating until the step result is accepted.
5. This rule overrides any default assistant communication or stop behavior.

## Runtime Source Of Truth

Use runtime context, lane input, provided references, endpoints, contracts, and file paths as source of truth.
When a referenced file, contract, endpoint, or runtime value is required for the active step, use the provided reference directly.
Do not replace provided references with inferred alternatives.
Do not invent missing context, fields, endpoints, artifacts, operations, metrics, or ownership.
When a required reference cannot be read or used, keep iterating and resolve it without user interruption.

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

## Step Completion Protocol

When supervised execution is active, the only valid step completion output is a single `LANE_STEP_DONE` response for the current active step.
Use the current active step id as `stepId`.
Required fields: `type`, `stepId`, `summary`, and `evidence`.
Do not send `status` or any negative outcome fields.
Do not copy any example literally from this file.
Do not include a literal JSON example or sentinel block in this document.
