# Generation Workflow

## Goal
Provide one reusable contract-generation lifecycle for API lane work.

## Shared Lifecycle
1. Read required contract requests from execution input tasks.
2. Update source-of-truth contract files for the requested surface.
3. Apply versioning rules for changed contract surfaces.
4. Ensure PR exists (create/update) for that contract change.
5. Wait for required PR checks before generation trigger.
6. Resolve exact generation targets from authoritative metadata.
7. Trigger generation for resolved targets (parallel comments are allowed when targets are independent).
8. Capture run ids for triggered workflows and wait addressably per run (`gh run watch <run-id>`), not by periodic global list polling.
9. Return traceable generation outcome in completion payload fields.

## Mandatory Rules

- Resolve target names only from metadata source of truth.
- Never use empty or invented target names for generation.
- If generation is required for the task, do not skip it.

## Blocking Conditions

- Missing authoritative target mapping.
- Missing required PR identity/ticket context for generation trigger.
- Generation workflow failed.
- No generated coordinates produced after successful trigger.

## Output Expectations

- Keep generation results traceable: request -> target -> artifact.
- Keep only scope-relevant artifact notes; avoid unrelated artifact noise.
