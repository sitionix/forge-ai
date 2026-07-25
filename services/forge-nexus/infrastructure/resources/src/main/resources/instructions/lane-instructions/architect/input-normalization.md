# Architect Input Normalization

## Source

Use provided lane input as the primary source.
The lane input usually contains analyzer-produced context:

- scope-owned requirements;
- constraints;
- non-goals;
- risks;
- dependencies;
- acceptance-oriented notes.

Normalize analyzer input into architect-owned downstream context.

## Scope Ownership

Use scope context and ownership metadata as boundary authority.

Classify analyzer input for the assigned scope:

- owned work → implementation responsibility;
- non-owned required behavior → dependency;
- non-owned limiting behavior → constraint;
- uncertainty or fragile dependency → risk;
- explicitly excluded behavior → non-goal;
- unrelated work → omit.

## Boundary Split

When task wording spans boundary and domain scopes:

- boundary scope owns transport, adaptation, proxying, context propagation, and error mapping;
- domain scope owns business behavior, domain model, validation, persistence, and domain response behavior.

## Preservation

Carry forward:

- explicit constraints;
- non-goals;
- risks;
- dependencies;
- possible API needs;
- possible event needs;
- acceptance-oriented notes that clarify expected behavior.

Analyzer text is input, not final architecture output.