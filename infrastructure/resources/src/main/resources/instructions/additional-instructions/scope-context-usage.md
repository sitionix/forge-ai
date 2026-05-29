# Scope Context Usage

Use `scopeContext` to understand the assigned service boundary.

Use:

- `label` for assigned service identity;
- `group` for backend/frontend/tool category;
- `tags` for technical/runtime characteristics;
- `domainKeywords` for supporting domain hints;
- `ownsBusinessAreas` for ownership;
- `architectureRefs` only when architecture context is needed.

Rules:

- `tags` are not requirements.
- `domainKeywords` are not requirements.
- Ownership comes from `ownsBusinessAreas` first.
- If ownership is unclear, use task wording, analyzer input, tags, and domain keywords only as supporting context.
- Non-owned behavior becomes dependency, constraint, risk, or is omitted when unrelated.