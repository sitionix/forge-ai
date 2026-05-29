# API Generation Rules

## Scope

REST/API-specific rules for API lane contract work.

## Contract Surfaces

- Source of truth: `app-afesox/apis/<api-family>/rest`.
- Work only in referenced or resolvable contract surfaces from execution tasks.
- Use task-provided change intent as executable contract delta source.
- Do not treat abstract scopes like `CROSS_SERVICE` as concrete contract surfaces.
- Resolve concrete contract surfaces from task intent, consumers, service metadata, and `app-afesox/apis/metadata.yml`.

If concrete contract surface ownership cannot be resolved, report the missing surface information in completion notes instead of inventing a target.

## Schema Composition
All objects must have DTO in their name, e.g., `UserDTO`.
For DTO object schemas in REST contracts, do not use:

- `allOf`
- `anyOf`
- `oneOf`

Describe DTO fields explicitly via `properties`.

## Version Rules

- Compare changed REST surface version in the current branch/PR against `develop`.
- If current version equals `develop`, bump once to `develop + 1`.
- If current version is already higher than `develop`, keep it.
- Do not increment repeatedly for repeated runs on the same PR.
- Keep REST metadata version and OpenAPI `info.version` synchronized when contract changes require version updates.

## Metadata And Targets

- Resolve generation target names only from `app-afesox/apis/metadata.yml`.
- Do not invent target names.
- Do not use blank target names.

## API Artifact Kinds

Supported artifact kinds:

- `api_first`
- `client`
- `frontend`

## Generation Target Selection

Resolve generated artifact targets from the concrete contract surface and service role.

Rules:

- For backend service contracts consumed by another Java service:
  - generate/update `api_first`;
  - generate/update `client`.

- For BFF/Workspace-facing REST contracts consumed by frontend:
  - generate/update `api_first`;
  - generate/update frontend/NPM target when configured by metadata.

- Do not generate a Java client for BFF unless metadata and task intent explicitly require a service-to-service consumer of BFF.
- Do not generate frontend/NPM artifacts for backend-only service contracts unless metadata maps that surface to a frontend target.

If a required target cannot be resolved from metadata, report it in completion notes instead of inventing a target.

## Completion Output Discipline

Keep completion content scope-relevant and contract-result oriented.

Each completion contract result should group:

- operation metadata;
- generated artifacts for that operation or contract unit;
- exact dependency/import snippets;
- DTO/client hints when available;
- short downstream notes.

Do not produce implementation handoffs, assign implementation work, or split metadata and artifacts into unrelated root lists.
Avoid unrelated artifact noise.

## Generated Artifact Evidence

Generated artifacts must be taken only from the generation workflow result.

Allowed artifact sources:

- workflow output;
- workflow logs;
- PR comment written by the generation workflow;
- generated artifact summary/comment produced by the official generation pipeline.

Do not invent, infer, or manually construct artifact coordinates.
Report artifacts only when visible in one of the allowed evidence sources above.

Each reported artifact must include evidence pointing to where it was found.

Artifact evidence should include at least one of:

- PR URL or PR number;
- workflow run URL or run id;
- workflow comment URL or comment id;
- raw workflow output snippet;
- generated target name from metadata.

If artifact generation succeeds but no artifact coordinates are found, report this in completion notes and do not fabricate dependencies.
