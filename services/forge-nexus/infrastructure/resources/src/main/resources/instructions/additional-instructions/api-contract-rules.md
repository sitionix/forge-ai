# API Contract Rules

## Source

Use execution input `tasks` as the contract intent source.

Preparation is already complete before this step starts.
Do not run repository setup, clone, remote reconfiguration, `checkout develop`, or branch recreation in this step.

Use the source-of-truth REST contract repository and paths from the rendered scope context:

- `scope.service.contractRefs.api.sourceRepo`
- `scope.service.contractRefs.api.apiFamily`
- `scope.service.contractRefs.api.root`
- `scope.service.contractRefs.api.schemas`
- `scope.service.contractRefs.api.operations`

For global lanes, use the matching `scope.relatedServices[*].contractRefs.api` entries.

Resolve the concrete API surface from:

- execution task intent;
- rendered service metadata;
- consumers;
- contract metadata in the configured contract source repository.

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
