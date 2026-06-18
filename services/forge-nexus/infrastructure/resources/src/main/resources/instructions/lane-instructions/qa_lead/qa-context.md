# QA Lead QA Context

## Source

Use:

- runtime context;
- analyzer output;
- lane task;
- assigned scope;
- scope context;
- contracts referenced by runtime input;
- available upstream completion results when runtime provides them.

Use analyzer output as the primary QA input.
Use contracts as source of truth for:

- methods;
- paths;
- operation ids;
- payloads;
- events;
- observable behavior.

Use implementation completion results only when runtime provides them.
Implementation completion results are factual context only for:

- changed flows;
- persistence changes;
- changed files;
- user-visible behavior changes.

QA Lead does not wait for implementation completion.

## Scope

Prepare QA context only for the assigned scope.
If another service or frontend app is mentioned, treat it as one of:

- dependency;
- precondition;
- external effect;
- integration boundary;
- non-owned behavior.

Owned behavior becomes QA target context.
Non-owned behavior becomes dependency or boundary context.
Unrelated behavior is omitted.

## Downstream Target

For backend scopes, prepare cases for backend integration testing.
For frontend scopes, prepare cases for UI behavior testing.
Use the provided final-step completion payload contract as the source of truth for final field names and payload shape.

## Result

Keep these facts for later steps:

- assigned scope;
- scope group;
- tested behavior area;
- relevant contracts;
- relevant dependencies;
- relevant constraints;
- relevant non-goals;
- risk areas;
- observable expected behavior.
