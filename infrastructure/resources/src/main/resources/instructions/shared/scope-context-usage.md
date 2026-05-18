## Scope Context Usage

Use the provided `scopeContext` to understand the assigned service boundary.

Use:

- `label` to identify the assigned service;
- `group` to distinguish backend, frontend, tool, or other service category;
- `tags` to understand technical/runtime characteristics;
- `domainKeywords` to detect likely domain relevance;
- `ownsBusinessAreas` to determine what this scope actually owns;
- `architectureRefs` only when additional architecture context is needed.

Rules:

- Do not treat `tags` as requirements.
- Do not treat `domainKeywords` as requirements.
- Do not infer ownership from keywords alone.
- Prefer `ownsBusinessAreas` when deciding whether this scope owns a business/domain responsibility.
- If `ownsBusinessAreas` is missing or inconclusive, use `domainKeywords`, `tags`, analyzer input, and task wording only as supporting context.
- If this scope does not own a described behavior, classify it as dependency, constraint, risk, or omit it if unrelated.
