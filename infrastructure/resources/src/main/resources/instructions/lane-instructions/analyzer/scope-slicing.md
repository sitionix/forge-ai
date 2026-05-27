# Analyzer Scope Slicing

## Source

Use:

- original ticket task text from runtime lane input;
- assigned scope;
- service metadata;
- scope context;
- ownership context;
- dependency and non-goal context from runtime input.

Analyzer works on one assigned scope.

## Classification

Classify task content for the assigned scope:

- owned executable work → `requirements`;
- non-owned but required behavior → `dependencies`;
- non-owned constraints that prevent wrong implementation → `constraints`;
- excluded or explicitly out-of-scope behavior → `nonGoals`;
- uncertainty, ordering concern, missing context, or fragile dependency → `risks`;
- observable expected behavior → `acceptanceNotes`;
- unrelated scope work → omit.

## Ownership Resolution

Use service metadata and ownership context to decide whether the assigned scope is:

- domain owner;
- adapter or boundary scope;
- frontend scope;
- tool/shared scope.

For domain owner scopes, keep requirements in domain-owned behavior:

- domain model behavior;
- application or use-case flow;
- persistence responsibility;
- domain validation;
- ownership/access semantics;
- domain response data;
- integration points required by adapter layers.

For adapter or boundary scopes, keep requirements in boundary-owned behavior:

- transport;
- adaptation;
- proxying;
- context propagation;
- error mapping;
- view or consumer-facing response shaping.

When task wording spans boundary and domain scopes:

- boundary scope owns transport/adaptation/proxy behavior;
- domain scope owns persistence/business/domain behavior.

## API And Event Signals

Preserve possible API or event needs as dependencies, risks, or architect notes.
For domain owner scopes, when another scope must call this capability synchronously, mention possible service API or contract need for architect.
API-related notes are not limited to Workspace-facing or public endpoints.
Analyzer does not decide final API or event requirement status.