# API Contract Rules

## Source

Use execution input `tasks` as the contract intent source.

Use source-of-truth REST contracts under:

`app-afesox/apis/<api-family>/rest`

Resolve the concrete API surface from:

- execution task intent;
- service metadata;
- consumers;
- `app-afesox/apis/metadata.yml`.

Abstract scope names such as `CROSS_SERVICE` are routing hints, not API family names.

## Work

Apply required REST contract changes inside the resolved API surface:

- paths;
- operations;
- request bodies;
- response bodies;
- parameters;
- schemas;
- contract-visible errors.

Use task-provided change intent as the executable contract delta.

## Result

Keep these facts for later steps:

- affected API family;
- changed contract files;
- changed operations;
- changed schemas;
- consumers mentioned by the task.