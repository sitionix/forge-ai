# Generation Workflow

Use generation workflow evidence for artifact coordinates.
When changed contract surfaces require generated artifacts, generation is part of the API lane result.

## Flow

1. Confirm PR identity for the changed contract unit.
2. Wait for required PR checks.
3. Resolve generation targets from authoritative metadata.
4. Trigger generation for resolved targets.
5. Capture workflow run ids.
6. Watch each run by id:

`gh run watch <run-id>`

7. Collect generated artifact evidence.
8. Return traceable generation result:

`request -> target -> artifact`

## Result

Keep these facts for completion content:

- contract request;
- generation target;
- workflow run id;
- workflow run URL;
- generated artifact;
- artifact evidence.