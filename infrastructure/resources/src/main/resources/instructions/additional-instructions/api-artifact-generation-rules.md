# API Artifact Generation Rules

## Metadata

Resolve API generation targets from:

`app-afesox/apis/metadata.yml`

Supported API artifact kinds:

- `api_first`;
- `client`;
- `frontend`.

## Target Selection

For backend service contracts consumed by another Java service, generate:

- `api_first`;
- `client`.

For BFF or Workspace-facing REST contracts consumed by frontend, generate:

- `api_first`;
- configured frontend target.

Use only targets present in metadata.

## Artifact Evidence

Report generated artifacts from:

- workflow output;
- workflow logs;
- PR comment written by the generation workflow;
- generated artifact summary/comment.

Each artifact result includes at least one evidence reference:

- PR reference;
- workflow run id or URL;
- workflow comment id or URL;
- raw workflow output snippet;
- generated target name from metadata.
