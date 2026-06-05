# Event Artifact Generation Rules

## Metadata

Resolve event generation targets from:

`app-afesox/apis/metadata.yml`

Supported event artifact kinds:

- `event-producer`;
- `event-consumer`.

Event generation target names start with `EVENT`.

## Target Selection

Generate only targets present in metadata.

For an event produced by the changed service, generate the matching producer target.

For Java services that consume the event, generate the matching consumer target when metadata exposes it.

Do not invent target names. The generation request must use the exact metadata `name`.

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
- generated target name from metadata;
- generated producer or consumer Maven coordinates when available.
